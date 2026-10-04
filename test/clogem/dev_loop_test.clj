;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-loop-test
  "The dev loop of Phase 4 Task D (DESIGN.md §5.4, §11.3 item 12): the
  debounce queue, the spaced and verified pod registration, the dev flag and
  missing-key warnings, the build-error overlay, `:base` and `--host`,
  Pagefind in the background, theme watching, and the generator-owned
  `dist/clogem/` sweep.

  No test here touches the network or sleeps for real where a clock or a
  scheduler can be injected; the SSE test talks to an http-kit server on
  loopback, as a browser would."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.i18n :as i18n]
            [clogem.search :as search]
            [clogem.tools :as tools]
            [org.httpkit.server :as http])
  (:import [java.util.concurrent LinkedBlockingQueue]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(defn- variant [title]
  (str "---\ntitle: " title "\n---\n\n## " title "\n\n" title " body.\n"))

(def ^:private two-langs
  {"01.Guide/01.t.md"    article
   "01.Guide/01.t.ta.md" (variant "எளிய தலைப்பு")})

(def ^:private url-site {:site {:title "S" :url "https://s.example"}})

(defn- with-site
  "Write {rel-path → content} under a temp site, plus `site-edn`, and call
  (f dir out)."
  [files site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-devloop"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (binding [search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- build!
  "Run `cli/build`; returns [result stdout stderr]."
  [dir out & [extra]]
  (let [err (java.io.StringWriter.)
        res (atom nil)
        o   (with-out-str
              (binding [*err* err]
                (reset! res (cli/build (merge {:site-dir (str dir) :out (str out) :no-write true} extra)))))]
    [@res o (str err)]))

(defn- html [out & parts] (slurp (fs/file (apply fs/path out (concat parts ["index.html"])))))

(defn- cfg-for [dir & [overrides]]
  (first (diag/collecting (config/load-config (str dir) nil overrides))))

;; ---------------------------------------------------------------------------
;; 1. The debounce queue

(defn- scripted-take
  "A `take!` for `drain-loop!` over a virtual clock: `events` are
  [[at-ms paths] …] in time order. A wait that an event ends returns it
  (the clock moves to it); one that runs out returns nil (the clock moves on
  by the timeout); with nothing left to deliver and no timeout the loop is
  told to stop. Returns {:take! :clock}."
  [events]
  (let [clock (atom 0)
        q     (atom (vec events))]
    {:clock clock
     :take! (fn [timeout]
              (let [[[at paths] & more] @q]
                (cond
                  (and at (or (nil? timeout) (<= at (+ @clock timeout))))
                  (do (reset! q (vec more)) (swap! clock max at) paths)

                  (nil? timeout) :clogem.dev/stop

                  :else (do (swap! clock + timeout) nil))))}))

(deftest twenty-writes-in-fifty-ms-are-one-rebuild
  (testing "the pod callback used to call rebuild once per event: 20 quick
            writes gave 20 full rebuilds. The queue drains only after 100 ms
            of quiet."
    (let [events  (for [i (range 20)] [(* i 2.5) [(str "/site/content/" i ".md")]])
          {:keys [take! clock]} (scripted-take events)
          batches (atom [])]
      (dev/drain-loop! {:take! take! :on-batch #(swap! batches conj [@clock %])})
      (is (= 1 (count @batches)) (pr-str @batches))
      (let [[at paths] (first @batches)]
        (is (= 20 (count paths)) "every path, once")
        (is (= (sort paths) paths) "sorted")
        (is (= 147.5 at) "100 ms after the last event, not before")))))

(deftest bursts-apart-are-separate-rebuilds-and-duplicates-collapse
  (let [{:keys [take! clock]} (scripted-take [[0 ["/a.md"]] [40 ["/a.md" "/b.md"]]
                                              [400 ["/c.md"]] [450 ["/c.md"]]])
        batches (atom [])]
    (dev/drain-loop! {:take! take! :on-batch #(swap! batches conj [@clock %])})
    (is (= [[140 ["/a.md" "/b.md"]] [550 ["/c.md"]]] @batches))))

(deftest the-loop-survives-an-error
  (testing "an Error (not an Exception) from a rebuild used to end the thread
            silently; the next batch must still be built"
    (let [{:keys [take!]} (scripted-take [[0 ["/a.md"]] [500 ["/b.md"]]])
          seen (atom [])
          out  (with-out-str
                 (dev/drain-loop! {:take! take!
                                   :on-batch (fn [ps]
                                               (swap! seen conj ps)
                                               (when (= ["/a.md"] ps)
                                                 (throw (StackOverflowError. "deep"))))}))]
      (is (= [["/a.md"] ["/b.md"]] @seen))
      (is (str/includes? out "StackOverflowError")))))

(deftest a-failed-rebuild-never-throws-out-of-the-loop
  (let [state (atom {:ever-ok? false})
        sent  (atom [])
        out   (fs/create-temp-dir {:prefix "clogem-out"})
        n     (atom 0)
        rebuild (dev/make-rebuild {:build (fn [] (when (= 1 (swap! n inc)) (throw (AssertionError. "render assert")))
                                            {:pages 1})
                                   :out out :state state
                                   :notify! #(swap! sent conj [:data %])
                                   :notify-error! #(swap! sent conj [:error %])})]
    (try
      (with-out-str
        (is (nil? (rebuild ["/x.md"])) "an AssertionError is caught")
        (is (= {:pages 1} (rebuild ["/x.md"]))))
      (is (= [[:error {:message "render assert" :file nil}] [:data "reload"]] @sent))
      (is (:ever-ok? @state))
      (is (nil? (:error @state)) "the good build clears the error")
      (finally (fs/delete-tree out)))))

(deftest editor-temp-files-are-not-changes
  (doseq [p ["/s/content/.#a.md" "/s/content/a.md~" "/s/content/4913" "/s/content/.a.md.swp"
             "/s/content/a.md.swx" "/s/content/x.tmp" "/s/dist/.clogem-tmp-123-456"
             "/s/content/.clogem-watch-probe-99"]]
    (is (dev/editor-temp? p) p))
  (doseq [p ["/s/content/a.md" "/s/content/4913.md" "/s/content/swp.md" "/s/site.edn"
             "/s/overrides/custom.css"]]
    (is (not (dev/editor-temp? p)) p))
  (testing "the submitter drops them before they reach the queue"
    (let [q (LinkedBlockingQueue.)
          submit! (dev/submitter q)]
      (submit! ["/s/content/.#a.md" "/s/content/a.md~"])
      (is (zero? (.size q)) "a batch of only temp files queues nothing")
      (submit! ["/s/content/4913" "/s/content/a.md"])
      (is (= [["/s/content/a.md"]] (vec q))))))

;; ---------------------------------------------------------------------------
;; 2. The fswatcher pod

(defn- watch-site []
  (let [dir (fs/create-temp-dir {:prefix "clogem-watch"})]
    (fs/create-dirs (fs/path dir "content"))
    (fs/create-dirs (fs/path dir "assets"))
    (fs/create-dirs (fs/path dir "i18n"))
    (fs/create-dirs (fs/path dir "overrides"))
    (spit (fs/file (fs/path dir "site.edn")) "{}")
    (spit (fs/file (fs/path dir "content" "index.md")) "# hi\n")
    dir))

(defn- live-watch
  "A `watch` that delivers a create event for each new file in `path`, by
  polling it on a background thread until `stop` is set."
  [stop]
  (fn [path cb _opts]
    (when (fs/directory? path)
      (let [seen (atom (set (map str (fs/list-dir path))))]
        (future
          (loop [n 0]
            (when (and (not @stop) (< n 600))
              (doseq [p (map str (fs/list-dir path))
                      :when (not (contains? @seen p))]
                (swap! seen conj p)
                (cb {:type :create :path p}))
              (Thread/sleep 5)
              (recur (inc n)))))))
    {:id (str path)}))

(deftest spaced-registration-passes-a-pod-that-hangs-on-back-to-back-calls
  (testing "back-to-back `watch` calls hung the real pod 55–70% of the time;
            a fake that hangs whenever it is called within 5 ms of its last
            call still passes the probe, because calls are 20 ms apart. The
            clock is virtual: the injected `sleep` advances it."
    (let [dir   (watch-site)
          stop  (atom false)
          clock (atom 0)
          calls (atom [])
          fake  (fn [live]
                  (fn [p cb o]
                    (let [now @clock prev (peek @calls)]
                      (swap! calls conj now)
                      (when (and prev (< (- now prev) 5)) @(promise))
                      (live p cb o))))]
      (try
        (let [cfg (cfg-for dir)
              out (with-out-str
                    (is (true? (dev/probe-watch! cfg (fn [_] nil)
                                                 {:watch (fake (live-watch stop)) :unwatch identity
                                                  :sleep #(swap! clock + %) :timeout-ms 3000}))))]
          (is (str/includes? out "delivered a probe event"))
          (is (>= (count @calls) 4) "every watched path was registered")
          (is (every? #(>= % dev/registration-gap-ms) (map - (rest @calls) @calls))
              (str "calls at " @calls)))
        (testing "and the same fake does hang without the gap, so the test can fail"
          (reset! calls [])
          (with-out-str
            (is (false? (dev/probe-watch! (cfg-for dir) (fn [_] nil)
                                          {:watch (fake (live-watch stop)) :unwatch identity
                                           :gap-ms 0 :sleep #(swap! clock + %) :timeout-ms 200})))))
        (finally (reset! stop true) (fs/delete-tree dir))))))

(deftest the-pod-is-loaded-from-the-verified-path
  (testing "not `(load-pod 'org.babashka/fswatcher \"0.0.7\")` — the registry
            downloads and runs an unverified binary — but the path
            `clogem.tools` fetched against the pinned sha256"
    (let [loaded (atom nil)
          asked  (atom nil)]
      (with-redefs [tools/ensure-binary! (fn [tool _cfg] (reset! asked (:id tool)) "/verified/pod-babashka-fswatcher")
                    pods/load-pod (fn [& args] (reset! loaded (vec args)) nil)]
        (with-out-str (dev/load-pod! {})))
      (is (= :fswatcher @asked))
      (is (= ["/verified/pod-babashka-fswatcher"] @loaded)))))

(deftest no-pod-means-polling-with-one-clear-message
  (testing "offline on the first run, or a platform with no asset (there is
            no windows-arm64): one line, then the caller polls"
    (let [loaded (atom false)
          tmp    (fs/create-temp-dir {:prefix "clogem-tools"})]
      (try
        (doseq [[what redefs cfg]
                [["offline" {} {:tools {:fswatcher {:version "0.0.7"
                                                    :url "http://127.0.0.1:9/no/{{platform}}.zip"}}}]
                 ["no asset" {#'tools/platform (fn [& _] nil)} {:tools {:fswatcher {:version "0.0.7"}}}]
                 ["bad hash" {} {:tools {:fswatcher {:version "0.0.7"
                                                     :sha256 {(tools/platform tools/fswatcher) (apply str (repeat 64 "0"))}
                                                     :url "http://127.0.0.1:9/no/{{platform}}.zip"}}}]]]
          (with-redefs-fn (merge {#'tools/getenv (fn [k] (when (= k "CLOGEM_TOOLS_DIR") (str tmp)))
                                  #'pods/load-pod (fn [& _] (reset! loaded true))}
                                 redefs)
            (fn []
              (let [err (java.io.StringWriter.)
                    out (binding [*err* err]
                          (with-out-str
                            (is (nil? (dev/load-pod! (assoc cfg :clogem/site-dir (str tmp)))) what)))]
                (is (= 1 (count (str/split-lines (str/trim out)))) (str what ": " out))
                (is (str/includes? out "watches by polling") what)
                (is (false? @loaded) (str what ": nothing unverified is loaded"))))))
        (finally (fs/delete-tree tmp))))))

;; ---------------------------------------------------------------------------
;; 3. The dev flag and missing-key warnings

(defn- lang-files
  "`i18n/<lang>.edn` for each configured language, sorted as the warnings are."
  [dir]
  (sort (map #(str "i18n/" (name %) ".edn") (config/lang-keys (cfg-for dir)))))

(defn- without-key
  "`i18n/theme-strings` with `k` removed from every language: a theme key
  removed in a scratch copy."
  [k]
  (let [orig i18n/theme-strings]
    (fn [lang] (dissoc (orig lang) k))))

(deftest a-missing-key-warns-once-per-language-and-renders-the-fallback
  (with-site two-langs url-site
    (fn [dir out]
      (with-redefs [i18n/theme-strings (without-key :mode/label)]
        (testing "build: one warning per (key, language) naming the string
                  file — 227 pages used to give one line per call in dev —
                  and the key's name in the page"
          (let [[_ _ err] (build! dir out)
                ws (filter #(str/includes? % "missing UI string key `:mode/label`") (str/split-lines err))]
            (is (= (lang-files dir) (map #(second (re-find #"^warning: (\S+): " %)) ws))
                (str "exactly one per language, naming its file:\n" err))
            (is (str/includes? (html out "pages" "t00001") "aria-label=\"label\""))
            (is (not (str/includes? (html out "pages" "t00001") "⟦")))))
        (testing "dev (the flag passed through `cli/build`): ⟦key⟧, warned once too"
          (let [[r _ err] (build! dir out {:clogem/dev? true})
                ws (filter #(str/includes? % "missing UI string key `:mode/label`") (str/split-lines err))]
            (is (= (count (lang-files dir)) (count ws)) err)
            (is (:clogem/dev? (:clogem/cfg r)))
            (is (str/includes? (html out "pages" "t00001") "aria-label=\"⟦:mode/label⟧\""))))
        (testing ":i18n {:missing-key :silent} turns the warning off"
          (spit (fs/file dir "site.edn") (pr-str (assoc url-site :i18n {:missing-key :silent})))
          (let [[_ _ err] (build! dir out)]
            (is (not (str/includes? err "missing UI string key")) err)))))))

(deftest doctor-reports-a-missing-key-once-per-language
  (with-site two-langs url-site
    (fn [dir _out]
      (with-redefs [i18n/theme-strings (without-key :mode/label)]
        (let [res (atom nil)]
          (binding [*err* (java.io.StringWriter.)]
            (with-out-str (reset! res (cli/doctor {:site-dir (str dir)}))))
          (is (= (lang-files dir)
                 (map :path (filter #(str/includes? (:message %) "`:mode/label`") (:warnings @res))))
              (pr-str (map :message (:warnings @res)))))))))

;; ---------------------------------------------------------------------------
;; 4. The build-error overlay

(defn- sse-client
  "Open `/__reload` on loopback `port`; returns a function that reads lines
  until one satisfies `pred` and returns it (5 s socket timeout)."
  [port]
  (let [s  (java.net.Socket. "127.0.0.1" (int port))
        _  (.setSoTimeout s 5000)
        w  (java.io.OutputStreamWriter. (.getOutputStream s) "UTF-8")
        r  (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))]
    (.write w "GET /__reload HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n")
    (.flush w)
    {:socket s
     :read-until (fn [pred]
                   (loop []
                     (let [l (.readLine r)]
                       (cond (nil? l) nil
                             (pred l) l
                             :else (recur)))))
     :read-line #(.readLine r)}))

(deftest an-sse-client-receives-build-error-and-then-the-reload
  (let [root  (fs/create-temp-dir {:prefix "clogem-sse"})
        state (atom {:ever-ok? true :last-ok? true})
        srv   (http/run-server (dev/make-handler root {:inject-reload? true :state state})
                               {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        fail? (atom true)
        rebuild (dev/make-rebuild {:build (fn [] (if @fail?
                                                   (throw (ex-info "1 content error:\n  - error: content/x.md: bad YAML"
                                                                   {:clogem/errors [{:level :error :path "content/x.md"}]}))
                                                   {:pages 1}))
                                   :out root :state state})]
    (try
      (let [{:keys [read-until read-line socket]} (sse-client (http/server-port srv))]
        (try
          (is (some? (read-until #(= "retry: 500" %))) "connected")
          (with-out-str (rebuild ["/s/content/x.md"]))
          (is (some? (read-until #(= "event: build-error" %))))
          (let [data (read-line)
                m    (json/parse-string (subs data (count "data: ")) true)]
            (is (str/starts-with? data "data: {") data)
            (is (= "content/x.md" (:file m)))
            (is (str/includes? (:message m) "bad YAML")))
          (reset! fail? false)
          (with-out-str (rebuild ["/s/content/x.md"]))
          (is (some? (read-until #(= "data: reload" %))) "the next good build reloads, which clears the overlay")
          (finally (.close ^java.net.Socket socket))))
      (testing "a client that connects while the build is broken gets the error at once"
        (reset! fail? true)
        (with-out-str (rebuild ["/s/content/x.md"]))
        (let [{:keys [read-until socket]} (sse-client (http/server-port srv))]
          (try
            (is (some? (read-until #(= "event: build-error" %))))
            (finally (.close ^java.net.Socket socket)))))
      (finally
        (http/server-stop! srv)
        (fs/delete-tree root)))))

(deftest before-any-good-build-a-page-request-gets-the-error
  (let [root  (fs/create-temp-dir {:prefix "clogem-err"})
        state (atom {:ever-ok? false :error {:message "boom <script>" :file "content/x.md"}})
        h     (dev/make-handler root {:inject-reload? true :state state})]
    (try
      (spit (fs/file root "index.html") "<html><body>old</body></html>")
      (let [r (h {:uri "/"})]
        (is (= 500 (:status r)))
        (is (str/includes? (:body r) "boom &lt;script&gt;") "escaped")
        (is (str/includes? (:body r) "content/x.md"))
        (is (str/includes? (:body r) "EventSource('/__reload')") "it reloads itself once a build succeeds"))
      (is (= 404 (:status (h {:uri "/clogem/css/theme.css"}))) "a non-page request is served (or not) as usual")
      (swap! state assoc :ever-ok? true)
      (is (= 200 (:status (h {:uri "/"}))) "after one good build, the last good output is served")
      (finally (fs/delete-tree root)))))

(deftest the-overlay-is-self-contained-and-leaves-window-clogem-alone
  (let [js (second (re-find #"(?s)<script>(.*)</script>" dev/reload-script))]
    (is (not (str/includes? js "window.clogem")))
    (is (not (re-find #"<link|\.css'" js)) "no stylesheet of its own")
    (if-let [node (fs/which "node")]
      (let [harness (str "function mk(t){return {tagName:t,id:'',style:{},children:[],attrs:{},textContent:'',"
                         "setAttribute:function(k,v){this.attrs[k]=v;},"
                         "appendChild:function(c){this.children.push(c);c.parentNode=this;},"
                         "removeChild:function(c){this.children=this.children.filter(function(x){return x!==c;});}};}"
                         "var body=mk('body');var handlers={};var es;var keys=[];"
                         "var document={body:body,documentElement:mk('html'),createElement:mk,"
                         "getElementById:function(id){return body.children.filter(function(c){return c.id===id;})[0]||null;},"
                         "querySelectorAll:function(){return [];},addEventListener:function(t,f){keys.push(f);}};"
                         "function EventSource(u){es=this;}EventSource.prototype.addEventListener=function(t,f){handlers[t]=f;};"
                         "var location={reload:function(){}};globalThis.clogem={mine:true};var before=globalThis.clogem;"
                         "var g0=Object.keys(globalThis).sort().join();"
                         js
                         ";var r={};handlers['build-error']({data:JSON.stringify({message:'bad <b>yaml</b>',file:'content/x.md'})});"
                         "var o=document.getElementById('clogem-dev-overlay');"
                         "r.shown=!!o;r.text=o?o.children.map(function(c){return c.textContent;}).join('|'):'';"
                         "r.fixed=o?o.style.cssText.indexOf('position:fixed')>=0:false;r.role=o?o.attrs.role:null;"
                         "r.clogem=globalThis.clogem===before&&globalThis.clogem.mine===true;"
                         "r.noGlobals=Object.keys(globalThis).sort().join()===g0;"
                         "handlers['build-error']({data:'not json'});r.once=body.children.length===1;"
                         "keys[0]({key:'Escape'});r.esc=!document.getElementById('clogem-dev-overlay');"
                         "handlers['build-error']({data:'{}'});es.onmessage({data:'css'});"
                         "r.css=!document.getElementById('clogem-dev-overlay');"
                         "console.log(JSON.stringify(r));")
            {:keys [out exit err]} (process/shell {:out :string :err :string :continue true}
                                                  (str node) "-e" harness)
            r (when (zero? exit) (json/parse-string (str/trim out) true))]
        (is (zero? exit) err)
        (is (:shown r) out)
        (is (str/includes? (str (:text r)) "content/x.md"))
        (is (str/includes? (str (:text r)) "bad <b>yaml</b>") "as text, never as markup")
        (is (:fixed r) "inline styles")
        (is (= "alert" (:role r)))
        (is (:clogem r) "window.clogem untouched")
        (is (:noGlobals r) "no global defined")
        (is (:once r) "a second error replaces the overlay")
        (is (:esc r) "Escape hides it")
        (is (:css r) "a CSS swap clears it"))
      (println "the-overlay-is-self-contained: node not found; checked the script text only"))))

;; ---------------------------------------------------------------------------
;; 5. A non-root base, and 6. the bind address

(defn- base-dist []
  (let [root (fs/create-temp-dir {:prefix "clogem-base"})]
    (fs/create-dirs (fs/path root "pages" "abc"))
    (fs/create-dirs (fs/path root "clogem" "css"))
    (spit (fs/file root "index.html") "<html><body>home</body></html>")
    (spit (fs/file root "pages" "abc" "index.html") "<html><body>page</body></html>")
    (spit (fs/file root "clogem" "css" "theme.css") "body{}")
    root))

(deftest dist-is-served-at-the-base
  (testing "with :base \"/project/\" every link is /project/…, which 404'd"
    (let [root (base-dist)]
      (try
        (doseq [inject? [true false]
                :let [h (dev/make-handler root {:inject-reload? inject? :base "/project/"})]]
          (is (= 200 (:status (h {:uri "/project/pages/abc/"}))))
          (is (= 200 (:status (h {:uri "/project/"}))))
          (let [css (h {:uri "/project/clogem/css/theme.css"})]
            (is (= 200 (:status css)))
            (is (str/starts-with? (get-in css [:headers "Content-Type"]) "text/css")))
          (is (= {:status 302 :headers {"Location" "/project/"} :body ""} (h {:uri "/"})))
          (is (= 302 (:status (h {:uri "/project"}))))
          (is (= 404 (:status (h {:uri "/pages/abc/"}))) "outside the base, nothing is served")
          (is (= 404 (:status (h {:uri "/project/../index.html"})))))
        (finally (fs/delete-tree root))))))

(deftest serve-takes-the-site-base-and-listens-on-loopback
  (let [root (base-dist)
        site (fs/create-temp-dir {:prefix "clogem-site"})
        got  (atom nil)]
    (try
      (spit (fs/file site "site.edn") (pr-str {:site {:base "/project/"}}))
      (with-redefs [dev/run-server! (fn [h o] (reset! got [h o]) :server)]
        (with-out-str
          (is (= :server (dev/start-serve! {:dir (str root) :port 1888 :site-dir (str site)})))))
      (let [[h o] @got]
        (is (= {:ip "127.0.0.1" :port 1888} o) "loopback by default")
        (is (= 200 (:status (h {:uri "/project/pages/abc/"}))) "the site's :base"))
      (testing "--base wins, and --host binds elsewhere"
        (with-redefs [dev/run-server! (fn [h o] (reset! got [h o]) :server)]
          (with-out-str (dev/start-serve! {:dir (str root) :port 9 :host "0.0.0.0" :base "/x"
                                           :site-dir (str site)})))
        (let [[h o] @got]
          (is (= {:ip "0.0.0.0" :port 9} o))
          (is (= 200 (:status (h {:uri "/x/pages/abc/"}))))))
      (testing "no site.edn: /"
        (is (= "/" (dev/serve-base {:site-dir (str root)}))))
      (finally (fs/delete-tree root) (fs/delete-tree site)))))

(deftest server-options-default-to-loopback
  (is (= {:ip "127.0.0.1" :port 1888} (dev/server-options {:port 1888})))
  (is (= "127.0.0.1" (:ip (dev/server-options {:host "" :port 1}))))
  (is (= {:ip "::1" :port 2} (dev/server-options {:host "::1" :port 2})))
  (testing "`serve` and `dev` both declare --host, defaulting to loopback"
    (doseq [v [#'cli/serve #'cli/dev]]
      (is (= "127.0.0.1" (get-in (meta v) [:org.babashka/cli :spec :host :default])) (str v)))))

;; ---------------------------------------------------------------------------
;; 7. Pagefind in dev

(deftest the-background-runner-runs-one-at-a-time-and-coalesces
  (let [runs (atom 0) active (atom 0) peak (atom 0)
        gate (promise) finished (promise)
        f    (fn []
               (swap! peak max (swap! active inc))
               (when (= 1 (swap! runs inc)) @gate)
               (swap! active dec))
        req  (dev/background-runner f (fn [g] (future (g) (deliver finished true))))]
    (req)
    (dotimes [_ 5] (req))
    (deliver gate true)
    (is (true? (deref finished 5000 false)))
    (is (= 2 @runs) "five requests during a run are one more run")
    (is (= 1 @peak) "never two at once")))

(deftest dev-builds-defer-search-and-the-staged-index-swaps-in
  (with-site two-langs (assoc url-site :search {:provider :pagefind})
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (assoc url-site :search {:provider :pagefind}
                                                      :tools {:pagefind {:path bin}})))
        (testing "a dev build renders the search UI but leaves indexing to dev"
          (let [[r] (build! dir out {:clogem/dev? true})]
            (is (= :deferred (:search r)))
            (is (nil? (fake/args-of bin)) "Pagefind did not run")
            (is (str/includes? (html out "pages" "t00001") "pagefind"))))
        (testing "the staged index replaces the old bundle whole"
          (fs/create-dirs (fs/path out "pagefind"))
          (spit (fs/file out "pagefind" "stale.pf_fragment") "old")
          (search/run-staged! (cfg-for dir {:build {:out (str out)}}))
          (is (fs/exists? (fs/path out "pagefind" "pagefind-entry.json")))
          (is (not (fs/exists? (fs/path out "pagefind" "stale.pf_fragment"))))
          (is (some #{"--output-path"} (fake/args-of bin)))
          (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) ".pagefind-") (fs/list-dir out)))
              "no staging directory left behind"))
        (testing "a failed index leaves the previous bundle in place"
          (let [bad (fake/fake-pagefind! (fs/path dir "bad") :exit 3)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (search/run-staged! (cfg-for dir {:build {:out (str out)}
                                                           :tools {:pagefind {:path bad}}}))))
            (is (fs/exists? (fs/path out "pagefind" "pagefind-entry.json")))
            (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) ".pagefind-") (fs/list-dir out))))))
        (testing "`bb build` still indexes in place"
          (let [[r] (build! dir out)]
            (is (map? (:search r)))
            (is (some #{"--output-subdir"} (fake/args-of bin)))))))))

(deftest a-killed-index-leaves-nothing-the-next-one-keeps
  (let [out (fs/create-temp-dir {:prefix "clogem-staging"})]
    (try
      (fs/create-dirs (fs/path out ".pagefind-staging-999999999-1"))
      (fs/create-dirs (fs/path out (str ".pagefind-old-" (.pid (java.lang.ProcessHandle/current)) "-2")))
      (fs/create-dirs (fs/path out ".pagefind-staging-1-3"))   ; pid 1 is alive: another process's
      (search/sweep-staging! out)
      (is (= #{".pagefind-staging-1-3"} (set (map #(str (fs/file-name %)) (fs/list-dir out)))))
      (finally (fs/delete-tree out)))))

;; ---------------------------------------------------------------------------
;; 8. Theme hot reload, 9. stylesheets added or removed, 10. config changes

(deftest the-theme-resources-are-watched-when-on-disk
  (let [dir (watch-site)]
    (try
      (let [res (dev/theme-dir false)]
        (is (fs/directory? res))
        (is (some #{(str res)} (map str (dev/watched-paths (cfg-for dir)))) "CSS, JS and strings")
        (is (some #{(str (fs/parent res))} (map str (dev/watched-paths (cfg-for dir) {:reload-code? true})))
            "--reload-code: the theme's .clj too")
        (is (= '[clogem.theme.layout clogem.theme.page]
               (dev/theme-namespaces (fs/parent res)
                                     [(str (fs/path (fs/parent res) "layout.clj"))
                                      (str (fs/path res "css" "theme.css"))
                                      (str (fs/path (fs/parent res) "page.clj"))
                                      "/elsewhere/clogem/theme/x.clj"]))))
      (finally (fs/delete-tree dir)))))

(deftest a-new-or-removed-stylesheet-reloads-instead-of-swapping
  (testing "the client only re-fetches <link>s already on the page, so a new
            overrides/custom.css did not show until a manual reload"
    (let [a #{"css/theme.css"} b #{"css/theme.css" "overrides/custom.css"}]
      (is (= "css" (dev/reload-kind ["/s/overrides/custom.css"] b b true)))
      (is (= "reload" (dev/reload-kind ["/s/overrides/custom.css"] a b true)) "added")
      (is (= "reload" (dev/reload-kind ["/s/overrides/custom.css"] b a true)) "removed")
      (is (= "reload" (dev/reload-kind ["/s/overrides/custom.css" "/s/content/a.md"] b b true)))
      (is (= "reload" (dev/reload-kind ["/s/overrides/custom.css"] b b false)) "after a failed build")
      (is (= "reload" (dev/reload-kind [] b b true)) "initial")))
  (testing "linked-stylesheets reads them from dist/clogem/"
    (let [out (fs/create-temp-dir {:prefix "clogem-css"})]
      (try
        (fs/create-dirs (fs/path out "clogem" "css"))
        (fs/create-dirs (fs/path out "clogem" "overrides"))
        (spit (fs/file out "clogem" "css" "theme.css") "")
        (spit (fs/file out "clogem" "overrides" "custom.css") "")
        (spit (fs/file out "clogem" "icons.svg") "")
        (is (= #{"css/theme.css" "overrides/custom.css"} (dev/linked-stylesheets out)))
        (finally (fs/delete-tree out))))))

(deftest a-site-edn-change-updates-dev-s-config
  (let [seen (atom nil) out (fs/create-temp-dir {:prefix "clogem-cfg"})]
    (try
      (with-out-str
        ((dev/make-rebuild {:build (fn [] {:clogem/cfg {:content {:dir "notes"}}})
                            :out out :state (atom {}) :on-config #(reset! seen %)
                            :notify! (fn [_])})
         ["/s/site.edn"]))
      (is (= {:content {:dir "notes"}} @seen) "so polling follows a moved :content :dir")
      (finally (fs/delete-tree out)))))

(deftest a-deleted-custom-css-is-swept-from-dist-clogem
  (with-site two-langs url-site
    (fn [dir out]
      (fs/create-dirs (fs/path dir "overrides"))
      (spit (fs/file dir "overrides" "custom.css") "body{color:red}")
      (build! dir out)
      (is (fs/exists? (fs/path out "clogem" "overrides" "custom.css")))
      ;; what the sweep must keep: a live build's temp file, a link and its
      ;; target outside, and anything outside dist/clogem/
      (let [live-tmp (fs/path out "clogem" (str ".clogem-tmp-" (.pid (java.lang.ProcessHandle/current)) "-1"))
            outside  (fs/path dir "outside.css")]
        (spit (fs/file live-tmp) "in flight")
        (spit (fs/file outside) "keep me")
        (fs/create-sym-link (fs/path out "clogem" "linked.css") outside)
        (spit (fs/file out "CNAME") "example.com")
        (spit (fs/file out "clogem" "foreign.txt") "not this build's")
        (fs/delete (fs/path dir "overrides" "custom.css"))
        (let [[r o] (build! dir out)]
          (is (not (fs/exists? (fs/path out "clogem" "overrides" "custom.css"))) "the deleted override is gone")
          (is (not (fs/exists? (fs/path out "clogem" "overrides"))) "and its emptied directory")
          (is (not (fs/exists? (fs/path out "clogem" "foreign.txt"))) "dist/clogem/ is the generator's")
          (is (= 2 (:stale-theme r)))
          (is (str/includes? o "removed 2 stale files from") o)
          (is (fs/exists? live-tmp) "a live temp file is left to the temp sweep")
          (is (fs/sym-link? (fs/path out "clogem" "linked.css")) "a link is never deleted")
          (is (= "keep me" (slurp (fs/file outside))) "nor followed")
          (is (fs/exists? (fs/path out "CNAME")) "outside dist/clogem/, non-HTML stays"))))))
