;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round2-test
  "P4-D.2: `bb dev`'s second fix round — new directories in pod mode, a
  `stop!` that waits, `:build :out` from site.edn, the static handler's
  containment and memory, and the wiring mutants that survived D.1.

  Each test names the item it pins down; each failed before its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article and `site-edn`; calls (f dir out)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-r2"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms`; its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(defn- spec-of [v] (:spec (:org.babashka/cli (meta v))))

(defn- parse
  "`args` parsed as `bb <task>` parses them: babashka.cli with the task's
  own spec, defaults included."
  [v args]
  (bcli/parse-opts args {:spec (spec-of v)}))

(def ^:private big-size
  "Over 1 MB, which was `dev/whole-read-limit` until the P4 follow-ups."
  (+ 10 (* 1024 1024)))

(def ^:private huge-size
  "Over `dev/whole-read-limit` (20 MB): mapped."
  (+ 10 (* 20 1024 1024)))

(defn- late-var [sym]
  (let [q (if (namespace sym) sym (symbol "clogem.dev" (name sym)))]
    @(or (resolve q) (throw (ex-info (str "no " q) {})))))

(defmacro ^:private late
  "A var — of clogem.dev unless qualified — resolved when the test runs, so
  a test of something new compiles, and fails, against the code before it."
  [sym]
  `(fn [& args#] (apply (late-var '~sym) args#)))

(defn- quiet [f]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (f)))

;; ---------------------------------------------------------------------------
;; Item 5: `:build :out` in site.edn is honoured

(deftest build-out-in-site-edn-is-honoured-through-the-cli-s-own-options
  (with-site (assoc url-site :build {:out "public"})
    (fn [dir _]
      (testing "no --out: site.edn's :build :out"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
        (is (not (fs/exists? (fs/path dir "dist")))))
      (testing "--out wins over site.edn"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write" "--out" "given"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "given" "pages" "t00001" "index.html"))))
      (testing "dev's and clean's specs agree"
        (is (nil? (:out (parse #'cli/dev []))))
        (is (nil? (:out (parse #'cli/clean []))))
        (quiet #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir))))
        (is (not (fs/exists? (fs/path dir "public"))) "clean removes site.edn's :build :out"))
      (testing "dev serves site.edn's :build :out"
        (let [servers (atom [])
              h (quiet #(dev/start-dev!
                         (assoc (parse #'cli/dev ["--poll" "--no-write"]) :site-dir (str dir))
                         {:run-server! (fn [handler o] (swap! servers conj handler) (fn [& _] nil))
                          :poll-watch! (fn [& _] nil)
                          :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
            (is (= 200 (:status ((first @servers) {:uri "/pages/t00001/"}))))
            (finally (quiet (:stop! h)))))))
    )
  (with-site url-site
    (fn [dir _]
      (testing "neither: dist"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "dist" "pages" "t00001" "index.html")))))))

(deftest serve-s-dir-keeps-its-default
  ;; P4-D.3 item 2: the default is resolved by `serve-settings`, after
  ;; --out and site.edn's :build :out, not by the spec
  (with-site url-site
    (fn [dir _]
      (is (= (str (fs/absolutize (fs/path dir "dist")))
             (str (:dir (dev/serve-settings (assoc (parse #'cli/serve []) :site-dir (str dir)))))))))
  (is (= "x" (:dir (parse #'cli/serve ["--dir" "x"])))))

;; ---------------------------------------------------------------------------
;; Items 6, 7, 8, 16, 17: the static handler

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-r2root"})]
    (try (f root) (finally (fs/delete-tree root)))))

(deftest a-file-is-read-whole-or-mapped-from-one-open
  (testing "item 6, as the P4 follow-ups (item 1) leave it: a file is read
            whole, or from 20 MB mapped, from one open before the response
            exists (a swap cannot mix two versions, a delete cannot make it
            http-kit's 500); P4-D.4 item 1's `File` body was opened only after
            the handler returned"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (with-open [o (java.io.FileOutputStream. (fs/file root "big.bin"))]
          (.write o (byte-array big-size (byte 7))))
        (with-open [f (java.io.RandomAccessFile. (fs/file root "huge.bin") "rw")]
          (.setLength f huge-size))
        (let [h (dev/make-handler root {})]
          (is (bytes? (:body (h {:uri "/small.txt"}))) "small: read whole, from one open")
          (is (= "small" (String. ^bytes (:body (h {:uri "/small.txt"})) "UTF-8")))
          (is (bytes? (:body (h {:uri "/big.bin"}))) "big: read whole too, as http-kit read a File")
          (is (instance? java.nio.ByteBuffer (:body (h {:uri "/huge.bin"}))) "from 20 MB: mapped, as http-kit mapped a File")
          (testing "and http-kit sends them, whole"
            (let [srv (dev/run-server! h {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
              (try
                (let [port (org.httpkit.server/server-port srv)
                      get! (fn [u] (with-open [in (.openStream (java.net.URL. (str "http://127.0.0.1:" port u)))]
                                     (alength (.readAllBytes in))))]
                  (is (= 5 (get! "/small.txt")))
                  (is (= big-size (get! "/big.bin")))
                  (is (= huge-size (get! "/huge.bin"))))
                (finally (org.httpkit.server/server-stop! srv))))))))))

(deftest a-vanished-file-never-puts-a-path-in-a-response
  (testing "item 6 keeps finding 1: big or small, a file gone between the
            check and the open is a 404, and anything thrown a pathless 500"
    (with-root
      (fn [root]
        (let [h (dev/make-handler root {:inject-reload? true :state (atom {:ever-ok? true})})]
          (doseq [ghost [(fs/path root "gone.bin") (fs/path root "gone.html")]]
            (with-redefs [dev/resolve-path (fn [& _] ghost)]
              (let [r (h {:uri "/x"})]
                (is (= 404 (:status r)))
                (is (not (str/includes? (str (:body r)) (str root))))))))))))

(deftest an-index-html-link-cannot-escape-the-root
  (testing "item 7: only the directory was checked, never its index.html"
    (with-root
      (fn [root]
        (let [dist    (fs/path root "dist")
              outside (fs/path root "outside")]
          (fs/create-dirs (fs/path dist "idxdir"))
          (fs/create-dirs (fs/path dist ".pagefind-prev-1-1-1"))
          (fs/create-dirs outside)
          (spit (fs/file outside "secret.html") "<html><body>SECRET</body></html>")
          (fs/create-sym-link (fs/path dist "idxdir" "index.html") (fs/path outside "secret.html"))
          (fs/create-sym-link (fs/path dist "index.html") (fs/path outside "secret.html"))
          (fs/create-dirs (fs/path dist "pagefind"))
          (fs/create-sym-link (fs/path dist ".pagefind-prev-1-1-1" "x.pf_meta") (fs/path outside "secret.html"))
          (let [h (dev/make-handler dist {:inject-reload? true :state (atom {:ever-ok? true})})]
            (doseq [u ["/idxdir/" "/idxdir" "/idxdir/index.html" "/" "/pagefind/x.pf_meta"]]
              (let [r (h {:uri u})]
                (is (= 404 (:status r)) u)
                (is (not (str/includes? (str (:body r)) "SECRET")) u))))
          (testing "a link that stays inside is still served"
            (spit (fs/file dist "real.html") "<html><body>inside</body></html>")
            (fs/create-dirs (fs/path dist "linked"))
            (fs/create-sym-link (fs/path dist "linked" "index.html") (fs/path dist "real.html"))
            (is (= 200 (:status ((dev/make-handler dist {}) {:uri "/linked/"}))))))))))

(deftest a-request-path-is-refused-before-the-filesystem-sees-it
  (testing "item 8: `\\\\host\\share` reached fs/exists? before the
            containment check (an SMB stat on Windows)"
    (with-root
      (fn [root]
        (spit (fs/file root "ok.txt") "ok")
        (let [h     (dev/make-handler root {})
              calls (atom [])
              orig-exists? fs/exists?
              orig-canon   fs/canonicalize]
          (with-redefs [fs/exists?       (fn [p & r] (swap! calls conj (str p)) (apply orig-exists? p r))
                        fs/canonicalize  (fn [p & r] (swap! calls conj (str p)) (apply orig-canon p r))
                        fs/directory?    (fn [p & r] (swap! calls conj (str p)) false)
                        fs/regular-file? (fn [p & r] (swap! calls conj (str p)) false)]
            (doseq [u ["/%5C%5Cattacker.example%5Cshare%5Cx" "/a%5Cb" "/C:/Windows/win.ini" "/c%3A%5Cx"
                       "/x%3Astream" "/%00" "/../../etc/passwd" "/a/%2E%2E/%2E%2E/b"]]
              (reset! calls [])
              (is (#{400 404} (:status (h {:uri u}))) u)
              (is (not-any? #(re-find #"attacker|share|Windows|passwd|stream|\\\\" %) @calls)
                  (str u " touched " (pr-str @calls))))))
        (is (= 200 (:status ((dev/make-handler root {}) {:uri "/a/../ok.txt"}))) "a `..` that stays inside")))))

(deftest the-404-escapes-the-base-as-an-attribute
  (testing "item 16: escape-html left `\"` in an href"
    (with-root
      (fn [root]
        (let [r (:body ((dev/make-handler root {:base "/a\"onmouseover=\"x/"}) {:uri "/nope"}))]
          ;; P4-D.4 item 4: the href is percent-encoded now, the text escaped
          (is (str/includes? r "href=\"/a%22onmouseover%3D%22x/\"") r)
          (is (str/includes? r ">/a&quot;onmouseover=&quot;x/<") r)
          (is (not (str/includes? r "a\"onmouseover")) r))))))

(deftest a-loopback-server-answers-only-loopback-names
  (testing "item 17: DNS rebinding — any Host was answered"
    (with-root
      (fn [root]
        (spit (fs/file root "index.html") "<html><body>x</body></html>")
        (let [h (dev/make-handler root {:check-host? true})]
          (doseq [host ["localhost" "localhost:1888" "127.0.0.1:1888" "127.0.0.1" "[::1]:1888" "[::1]" "LOCALHOST:9" nil]]
            (is (= 200 (:status (h {:uri "/" :headers (if host {"host" host} {})}))) (str host)))
          (doseq [host ["evil.example" "evil.example:1888" "localhost.evil.example" "127.0.0.1.nip.io:1888" "[::2]:1"]]
            (let [r (h {:uri "/" :headers {"host" host}})]
              (is (= 403 (:status r)) host)
              (is (str/starts-with? (:body r) "403 Forbidden")))))
        (testing "another --host: no check"
          (is (= 200 (:status ((dev/make-handler root {}) {:uri "/" :headers {"host" "evil.example"}})))))
        (testing "which binds count as loopback"
          (is (every? (late loopback-ip?) ["127.0.0.1" "::1" "localhost" "127.0.0.2"]))
          (is (not-any? (late loopback-ip?) ["0.0.0.0" "::" "192.168.1.2" "example.com"])))
        (testing "both servers pass it"
          (with-site url-site
            (fn [dir _]
              (let [servers (atom [])
                    h (quiet #(dev/start-dev! {:site-dir (str dir) :poll true :no-write true}
                                              {:run-server! (fn [handler o] (swap! servers conj handler) (fn [& _] nil))
                                               :poll-watch! (fn [& _] nil)
                                               :notify! (fn [_]) :notify-error! (fn [_])}))]
                (try
                  (is (= 403 (:status ((first @servers) {:uri "/" :headers {"host" "evil.example"}}))))
                  (finally (quiet (:stop! h)))))))
          (with-redefs [dev/run-server! (fn [handler _] (fn [& _] handler))]
            (let [srv (quiet #(dev/start-serve! {:dir (str root)}))]
              (is (= 403 (:status ((srv) {:uri "/" :headers {"host" "evil.example"}})))))))))))

;; ---------------------------------------------------------------------------
;; Items 2, 11, 12, 14, 20: stopping, and the search bundles

(defn- fake-dev
  "`start-dev!` over `dir`, polling, with the server and watcher faked and
  `env` merged in; returns its handle."
  [dir opts env]
  (quiet #(dev/start-dev! (merge {:site-dir (str dir) :poll true :no-write true} opts)
                          (merge {:run-server! (fn [& _] (fn [& _] nil))
                                  :poll-watch! (fn [& _] nil)
                                  :notify! (fn [_]) :notify-error! (fn [_])}
                                 env))))

(deftest stop-waits-for-the-build-running
  (testing "item 2: stop! returned at once while the drain loop rebuilt"
    (with-site url-site
      (fn [dir _]
        (let [started (atom 0) ended (atom 0)
              h (fake-dev dir {} {:build (fn [_]
                                           (swap! started inc)
                                           (Thread/sleep 600)
                                           (swap! ended inc)
                                           {})})]
          (is (= 1 @ended) "the first build")
          ((:submit! h) [(str (fs/path dir "content" "01.Guide" "01.t.md"))])
          (is (wait-until #(= 2 @started)))
          ((:stop! h))
          (is (= 2 @ended) "stop! returned only once the build had")
          ((:submit! h) [(str (fs/path dir "content" "01.Guide" "01.t.md"))])
          (Thread/sleep 300)
          (is (= 2 @started) "and no build starts after it"))))))

(deftest stop-during-the-first-build-waits-for-it
  (testing "item 12's window: a stop! handed out before the first build"
    (with-site url-site
      (fn [dir _]
        (let [ended (atom 0) stop (promise) returned (promise)]
          (future (fake-dev dir {} {:on-stop! #(deliver stop %)
                                    :build (fn [_] (Thread/sleep 500) (swap! ended inc) {})}))
          (let [s (deref stop 5000 nil)]
            (is (fn? s) "stop! exists before the first build")
            (Thread/sleep 100)
            (s)
            (deliver returned @ended)
            (is (= 1 @returned) "it waited for that build")))))))

(deftest stop-cancels-a-queued-index-and-kills-the-running-one
  (testing "item 2: an index queued behind the running one started a new
            Pagefind after stop!, which outlived dev and left staging"
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir out]
        (let [bin  (fs/path dir "bin" "pagefind_extended")
              runs (fs/path dir "runs.txt")]
          (fs/create-dirs (fs/parent bin))
          ;; a slow Pagefind that records each start, and writes into its
          ;; staging directory as it goes
          (spit (fs/file bin) (str "#!/bin/sh\necho run >> '" runs "'\n"
                                   "while [ $# -gt 0 ]; do case \"$1\" in --output-path) o=\"$2\"; shift;; esac; shift; done\n"
                                   "mkdir -p \"$o\"; : > \"$o/pagefind-entry.json\"\n"
                                   "sleep 30\n"))
          (fs/set-posix-file-permissions bin "rwxr-xr-x")
          (spit (fs/file dir "site.edn") (pr-str (assoc url-site :search {:provider :pagefind}
                                                        :tools {:pagefind {:path (str bin)}})))
          (let [h (fake-dev dir {} {})]
            (is (wait-until #(fs/exists? runs)) "the first index is running")
            ((:submit! h) [(str (fs/path dir "content" "01.Guide" "01.t.md"))])
            ;; the second build asks for an index: queued behind the first
            (is (wait-until #(some (fn [p] (str/includes? (str (fs/file-name p)) "pagefind-staging"))
                                   (fs/list-dir out))))
            (Thread/sleep 500)
            (let [t0 (System/currentTimeMillis)]
              (quiet (:stop! h))
              (is (< (- (System/currentTimeMillis) t0) 8000) "the running one was killed, not waited out"))
            (Thread/sleep 500)
            (is (= 1 (count (str/split-lines (slurp (fs/file runs))))) "the queued run never started")
            (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) ".pagefind-") (fs/list-dir out)))
                "no staging, no previous bundle left")))))))

(deftest dev-installs-its-shutdown-hook-before-it-starts
  (testing "item 12: the hook came after the first build and the probe"
    (let [order (atom []) hook (atom nil) stopped (atom 0)
          hook-var (resolve 'clogem.dev/add-shutdown-hook!)]
      (is (some? hook-var) "a var the test can see")
      (when hook-var
        (with-redefs-fn {hook-var        (fn [f] (swap! order conj :hook) (reset! hook f))
                         #'dev/start-dev! (fn [_ env]
                                            (swap! order conj :start)
                                            ((:on-stop! env) #(swap! stopped inc))
                                            (throw (ex-info "escape @(promise)" {})))}
          (fn []
            (is (thrown? clojure.lang.ExceptionInfo (dev/dev! {})))
            (is (= [:hook :start] @order))
            (let [o (with-out-str (@hook))]
              (is (= 1 @stopped) "Ctrl-C runs stop!")
              (is (str/includes? o "clogem-press: stopped")))))))))

(deftest every-bundle-a-page-could-have-loaded-stays-servable
  (testing "item 14: two saves in one index run — the page reloaded after
            the second lost its bundle at the coalesced second swap"
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir out]
        (let [bin (fake/fake-pagefind! (fs/path dir "bin"))
              cfg (first (diag/collecting
                          (config/load-config (str dir) nil {:build {:out (str out)}
                                                                    :tools {:pagefind {:path bin}}})))
              h   (dev/make-handler out {:inject-reload? true :state (atom {:ever-ok? true})})
              st  #(:status (h {:uri %}))
              gen! (fn [n] (fs/create-dirs (fs/path out "pagefind" "fragment"))
                     (spit (fs/file out "pagefind" "fragment" (str "gen" n ".pf_fragment")) (str n)))]
          (fs/create-dirs out)
          (spit (fs/file out "index.html") "<html><body>x</body></html>")
          (gen! 0)
          ;; save 1: reload (pages load gen 0), index A starts …
          (let [r1 (System/currentTimeMillis)]
            (Thread/sleep 5)
            ;; … save 2: reload again; gen 0 is still live, so pages load it
            (let [r2 (System/currentTimeMillis)]
              (Thread/sleep 5)
              (search/run-staged! cfg {:keep-since r2})   ; A swaps: gen 0 retired
              (gen! 1)
              (search/run-staged! cfg {:keep-since r2})   ; B, coalesced: gen 1 retired
              (is (= 200 (st "/pagefind/fragment/gen0.pf_fragment")) "what the page reloaded after save 2 loaded")
              (is (= 200 (st "/pagefind/fragment/gen1.pf_fragment")))
              (is (= 2 (count ((late clogem.search/previous-dirs) out))))
              (testing "a reload after both: the older ones go at the next swap"
                (Thread/sleep 5)
                (let [r3 (System/currentTimeMillis)]
                  (Thread/sleep 5)
                  (search/run-staged! cfg {:keep-since r3})
                  (is (= 404 (st "/pagefind/fragment/gen0.pf_fragment")))
                  (is (= 404 (st "/pagefind/fragment/gen1.pf_fragment")))
                  (is (= 1 (count ((late clogem.search/previous-dirs) out))) "the one just retired")))
              (testing "never more than max-previous"
                (dotimes [_ (+ 3 (late-var 'clogem.search/max-previous))] (search/run-staged! cfg {:keep-since 0}))
                (is (= (late-var 'clogem.search/max-previous) (count ((late clogem.search/previous-dirs) out))))))
            r1))))))

(deftest dev-records-the-reload-before-it-is-sent
  (testing "item 14's wiring: the indexer keeps what was live since the
            last reload, so dev must say when that was"
    (let [calls (atom [])
          rebuild (dev/make-rebuild {:build (fn [] {})
                                     :out "/nonexistent"
                                     :state (atom {})
                                     :notify! #(swap! calls conj [:notify %])
                                     :on-reload #(swap! calls conj [:reload])})]
      (quiet #(rebuild []))
      (is (= [[:reload] [:notify "reload"]] @calls)))))

(deftest stop-and-build-remove-the-previous-bundles
  (testing "item 20: .pagefind-prev survived dev's exit and every bb build"
    (with-site url-site
      (fn [dir out]
        (let [h    (fake-dev dir {} {})
              me   (java.lang.ProcessHandle/current)
              st   (.toEpochMilli ^java.time.Instant (.get (.startInstant (.info me))))
              mine (fs/path out (str ".pagefind-prev-" (.pid me) "-" st "-9"))]
          (fs/create-dirs mine)
          ((:stop! h))
          (is (not (fs/exists? mine)) "dev's stop! removes its own"))
        (fs/create-dirs (fs/path out ".pagefind-prev-999999999-1-1"))
        (fs/create-dirs (fs/path out ".pagefind-prev"))
        (quiet #(cli/build {:site-dir (str dir) :no-write true :no-search true}))
        (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) ".pagefind-") (fs/list-dir out)))
            "bb build --no-search sweeps a dead dev's")))))

;; ---------------------------------------------------------------------------
;; Items 1, 3, 4, 9, 13, 24: pod mode

(defn- n [p] (str (fs/normalize (fs/absolutize (str p)))))

(defn- art [id] (str/replace article "t00001" id))

(defn- mtimes
  "path → modified time of every file under `dir` but `dist/`."
  [dir]
  (into {} (for [f (fs/glob dir "**")
                 :when (and (fs/regular-file? f)
                            (not (str/starts-with? (str f) (str (fs/path dir "dist")))))]
             [(str f) (fs/file-time->millis (fs/last-modified-time f))])))

(defn- pod-dev
  "`start-dev!` over `dir` in pod mode with a fake pod: each registration
  and unwatch is logged, and `(event! path)` delivers an event as the pod
  would (through `accept?` first, as `probe-watch!`'s callback does).
  Calls (f ctx), then stops it. With `:accept-all?`, the filter is
  skipped — the n5c mutant; with `:inotify?`, every file a build writes
  in the site (`dist/` aside) is reported as an event, as the real pod
  reports it."
  [dir opts env f & [{:keys [accept-all? inotify?]}]]
  (let [regs (atom []) unwatched (atom []) builds (atom []) out (java.io.StringWriter.)
        cb (atom nil) k (atom 0)
        register! (fn [spec] (swap! regs conj spec) {:w (swap! k inc) :path (:path spec)})
        pod (fn [_cfg on-change {:keys [specs accept? on-ready]}]
              (reset! cb {:on-change on-change :accept? accept?})
              (let [ws (mapv register! specs)]
                (on-ready register! {:unwatch! #(swap! unwatched conj %)
                                     :watchers (zipmap (map :path specs) ws)}))
              true)
        event! (fn [p] (when-let [{:keys [on-change accept?]} @cb]
                         (when (or accept-all? (accept? (str p))) (on-change [(str p)]))))
        build cli/build
        h (binding [*out* out *err* out]
            (dev/start-dev! (merge {:site-dir (str dir) :port 1888 :no-write true} opts)
                            (merge {:run-server! (fn [& _] (fn [& _] nil))
                                    :try-pod-watch! pod
                                    :follow-ms 20
                                    :notify! (fn [_]) :notify-error! (fn [_])
                                    :build (fn [o]
                                             (let [before (when inotify? (mtimes dir))]
                                               (try (build o)
                                                    (finally
                                                      (swap! builds conj o)
                                                      (when inotify?
                                                        (let [after (mtimes dir)]
                                                          (future
                                                            (Thread/sleep 50)
                                                            (doseq [[p t] after :when (not= t (get before p))]
                                                              (event! p)))))))))}
                                   env)))]
    (try
      (f {:h h :regs regs :unwatched unwatched :builds builds :out out :event! event!
          :accept? (:accept? @cb)
          :regs-of (fn [p] (filterv #(= (n p) (:path %)) @regs))})
      (finally (binding [*out* out *err* out] ((:stop! h)))))))

(defn- rebuilt-lines [out]
  (re-seq #"rebuilt in \d+ ms \(([^)]*)\)" (str out)))

(deftest a-directory-created-later-is-registered-and-rescanned
  (testing "item 1: the pod's recursive watch covers only the directories
            that existed at `watch`; a new NN.Topic/ got one rebuild for
            the mkdir and nothing for anything inside it"
    (with-site url-site
      (fn [dir _]
        (pod-dev dir {} {}
                 (fn [{:keys [builds event! regs-of out]}]
                   (let [topic (fs/path dir "content" "07.Topic")]
                     (fs/create-dirs topic)
                     ;; written before the pod hears of the directory: no
                     ;; watch sees it, so only a rescan can
                     (spit (fs/file topic "01.new.md") (art "t00002"))
                     (event! topic)
                     (is (wait-until #(= [{:path (n topic) :recursive? true}] (regs-of topic))) "registered, recursively")
                     (is (wait-until #(= 2 (count @builds))))
                     ;; the line is printed after the build returns: waited
                     ;; for, not read at once (P4-D.3 item 7)
                     (is (wait-until #(some (fn [l] (str/includes? (second l) "01.new.md")) (rebuilt-lines out)))
                         (str out))
                     (testing "and an edit inside it is a rebuild"
                       (spit (fs/file topic "01.new.md") (str (art "t00002") "\nmore\n"))
                       (event! (fs/path topic "01.new.md"))
                       (is (wait-until #(= 3 (count @builds))))))
                   (testing "`mkdir -p a/b/c`: the top one is registered, the rest rescanned"
                     (let [a (fs/path dir "content" "08.A")
                           c (fs/path a "09.B" "10.C")]
                       (fs/create-dirs c)
                       (spit (fs/file c "01.d.md") (art "t00003"))
                       (event! a)
                       (is (wait-until #(= 1 (count (regs-of a)))))
                       (is (wait-until #(= 4 (count @builds))))
                       (is (wait-until #(str/includes? (str out) "01.d.md")) (str out))))
                   (testing "`cp -r` of a tree: one rebuild for all of it"
                     (let [t (fs/path dir "content" "11.Copy")]
                       (fs/create-dirs (fs/path t "x"))
                       (doseq [i (range 5)]
                         (spit (fs/file t "x" (str "0" i ".c.md")) (art (str "cp000" i))))
                       (event! t)
                       (is (wait-until #(= 5 (count @builds))))
                       (Thread/sleep 300)
                       (is (= 5 (count @builds)) "one rebuild")
                       (is (wait-until #(str/includes? (str out) "6 files changed"))
                           (str "the directory and its 5 files: " out))))
                   (testing "a file is not a directory to register"
                     (let [before (count (regs-of (fs/path dir "content" "01.Guide" "01.t.md")))]
                       (event! (fs/path dir "content" "01.Guide" "01.t.md"))
                       (Thread/sleep 200)
                       (is (= before (count (regs-of (fs/path dir "content" "01.Guide" "01.t.md")))))))))))))

(deftest a-replaced-watched-directory-is-registered-again
  (testing "item 3: a directory deleted and made again kept its dead watch"
    (with-site url-site
      (fn [dir _]
        (fs/create-dirs (fs/path dir "overrides"))
        (spit (fs/file dir "overrides" "custom.css") "a{}")
        (pod-dev dir {} {}
                 (fn [{:keys [unwatched event! regs-of builds]}]
                   (let [ov  (fs/path dir "overrides")
                         w0  {:w 1 :path (n ov)}
                         _   (is (= 1 (count (regs-of ov))) "at startup")
                         w0  (some #(when (= (n ov) (:path %)) %) @unwatched)]
                     (is (nil? w0) "nothing unwatched yet")
                     (testing "rm -rf and mkdir: the event naming it re-registers it"
                       (fs/delete-tree ov)
                       (fs/create-dirs ov)
                       (spit (fs/file ov "custom.css") "b{}")
                       (event! ov)
                       (is (wait-until #(= 2 (count (regs-of ov)))))
                       (is (wait-until #(= 1 (count (filter (fn [w] (= (n ov) (:path w))) @unwatched))))
                           "and its old watch is unwatched, not leaked")
                       (is (wait-until #(= 2 (count @builds)))))
                     (testing "replaced with no event between two passes: its file key changed"
                       (fs/move ov (fs/path dir "overrides.old"))
                       (fs/create-dirs ov)
                       (is (wait-until #(= 3 (count (regs-of ov)))))
                       (is (wait-until #(= 2 (count (filter (fn [w] (= (n ov) (:path w))) @unwatched)))))))))))))

(deftest a-symlinked-site-edn-is-watched-at-its-target
  (testing "item 4: only events for the link's own path counted, and an
            edit of the target happens in another directory"
    (with-site url-site
      (fn [dir _]
        (let [shared (fs/path dir "shared")
              target (fs/path shared "site.edn")
              link   (fs/path dir "site.edn")]
          (fs/create-dirs shared)
          (fs/move link target)
          (fs/create-sym-link link target)
          (let [cfg   (first (diag/collecting (config/load-config (str dir))))
                specs (dev/watch-specs cfg)]
            (is (some #{{:path (n shared) :recursive? false :only #{(n target)}}} specs) (pr-str specs))
            (is (some #{{:path (n dir) :recursive? false :only #{(n link)}}} specs) "the link's own directory too")
            (is (dev/watched-event? specs (str target)))
            (is (not (dev/watched-event? specs (str (fs/path shared "other.edn"))))))
          (pod-dev dir {} {}
                   (fn [{:keys [regs-of event! builds]}]
                     (is (= 1 (count (regs-of shared))) "the target's directory is registered")
                     (spit (fs/file target) (pr-str (assoc-in url-site [:site :title] "B")))
                     (event! target)
                     (is (wait-until #(= 2 (count @builds))))
                     (testing "the link pointed elsewhere: followed"
                       (let [other (fs/path dir "elsewhere")]
                         (fs/create-dirs other)
                         (spit (fs/file other "site.edn") (pr-str url-site))
                         (fs/delete link)
                         (fs/create-sym-link link (fs/path other "site.edn"))
                         (event! link)
                         (is (wait-until #(= 3 (count @builds))))
                         (is (wait-until #(= 1 (count (regs-of other))))))))))))))

(deftest a-content-dir-moved-in-site-edn-is-watched-in-pod-mode
  (testing "n4e: without (reset! specs …) in on-config, pod-mode dev never
            followed a moved :content :dir"
    (with-site url-site
      (fn [dir _]
        (fs/create-dirs (fs/path dir "notes" "01.Guide"))
        (spit (fs/file dir "notes" "01.Guide" "01.t.md") article)
        (pod-dev dir {} {}
                 (fn [{:keys [regs-of event! builds accept?]}]
                   (is (not (accept? (str (fs/path dir "notes" "01.Guide" "01.t.md")))) "not yet")
                   (spit (fs/file dir "site.edn") (pr-str (assoc url-site :content {:dir "notes" :write-front-matter false})))
                   (event! (fs/path dir "site.edn"))
                   (is (wait-until #(= 2 (count @builds))))
                   (is (wait-until #(= 1 (count (regs-of (fs/path dir "notes"))))) "notes/ is registered")
                   (is (accept? (str (fs/path dir "notes" "01.Guide" "01.t.md"))) "and its events accepted")
                   (event! (fs/path dir "notes" "01.Guide" "01.t.md"))
                   (is (wait-until #(= 3 (count @builds))))))))))

(deftest the-pod-s-events-are-filtered
  (testing "n5c: without :accept?, a file beside site.edn was a change"
    (with-site url-site
      (fn [dir _]
        (pod-dev dir {} {}
                 (fn [{:keys [accept?]}]
                   (is (some? accept?))
                   (is (accept? (str (fs/path dir "site.edn"))))
                   (is (accept? (str (fs/path dir "content" "01.Guide" "01.t.md"))))
                   (doseq [f ["notes.txt" "permalinks.edn" "dist/index.html"]]
                     (is (not (accept? (str (fs/path dir f)))) f))))))))

(deftest the-build-s-own-writes-never-rebuild
  (testing "n5c's loop: with the filter gone, permalinks.edn (rewritten by
            every build) and front-matter write-back each triggered another
            build, for ever"
    (with-site (assoc-in url-site [:content :write-front-matter] true)
      (fn [dir _]
        (let [a (fs/path dir "content" "01.Guide" "02.new.md")]
          (spit (fs/file a) "---\ntitle: New\ndate: \"2026-02-01 00:00:00\"\n---\n\nBody\n")
          (pod-dev dir {:no-write false} {}
                   (fn [{:keys [builds event!]}]
                     (is (str/includes? (slurp (fs/file a)) "permalink:") "auto-filled")
                     (is (fs/exists? (fs/path dir "permalinks.edn")))
                     (Thread/sleep 700)
                     (is (= 1 (count @builds)) "the first build's own writes rebuild nothing")
                     (event! (fs/path dir "notes.txt"))
                     (is (wait-until #(= 2 (count @builds))) "notes.txt, unfiltered, is one rebuild")
                     (Thread/sleep 700)
                     (is (= 2 (count @builds)) "and then nothing: no loop")
                     (testing "an edit of a file the build wrote is still a change"
                       (spit (fs/file a) (str (slurp (fs/file a)) "\nedited\n"))
                       (event! a)
                       (is (wait-until #(= 3 (count @builds))))
                       (Thread/sleep 700)
                       (is (= 3 (count @builds)))))
                   {:accept-all? true :inotify? true}))))))

(deftest each-startup-path-is-registered-once
  (testing "n4f: without (reset! registered …) every startup path was
            registered again by the follower, with a spurious rebuild"
    (with-site url-site
      (fn [dir _]
        (pod-dev dir {} {}
                 (fn [{:keys [regs builds]}]
                   (Thread/sleep 600)
                   (is (= (count @regs) (count (distinct (map :path @regs)))) (pr-str @regs))
                   (is (= 1 (count @builds)))))))))

(deftest creating-a-watched-top-level-directory-is-one-rebuild
  (testing "item 13: the site-dir watch's event and the follower each
            rebuilt — two rebuilds, two reloads"
    (with-site url-site
      (fn [dir _]
        ;; a follower pass well after the debounce, as dev's 1 s is
        (pod-dev dir {} {:follow-ms 400}
                 (fn [{:keys [builds event! regs-of out]}]
                   (let [ov (fs/path dir "overrides")]
                     (fs/create-dirs ov)
                     (spit (fs/file ov "custom.css") "body{}")
                     (event! ov)
                     (is (wait-until #(= 2 (count @builds))))
                     (Thread/sleep 900)
                     (is (= 2 (count @builds)) (str out))
                     (is (= 1 (count (regs-of ov)))))))))))

(deftest a-content-dir-that-does-not-exist-yet-is-followed
  (testing "item 24: the build failed, so the config was never adopted,
            and the directory was never watched once created"
    (with-site url-site
      (fn [dir _]
        (let [moved (pr-str (assoc url-site :content {:dir "notes" :write-front-matter false}))]
          (testing "with the pod"
            (pod-dev dir {} {}
                     (fn [{:keys [regs-of event! builds out]}]
                       (spit (fs/file dir "site.edn") moved)
                       (event! (fs/path dir "site.edn"))
                       (is (wait-until #(str/includes? (str out) "build failed")))
                       (fs/create-dirs (fs/path dir "notes" "01.Guide"))
                       (spit (fs/file dir "notes" "01.Guide" "01.t.md") article)
                       (is (wait-until #(= 1 (count (regs-of (fs/path dir "notes"))))) "registered once it exists")
                       (is (wait-until #(str/includes? (str out) "rebuilt in")))
                       (is (wait-until #(= 3 (count @builds)))))))
          (fs/delete-tree (fs/path dir "notes"))
          (spit (fs/file dir "site.edn") (pr-str url-site))
          (testing "polling"
            (let [polls (atom [])
                  h (fake-dev dir {} {:poll-watch! (fn [paths _ on-change _]
                                                     (swap! polls conj {:paths paths :on-change on-change}))})]
              (try
                (is (wait-until #(seq @polls)))
                (spit (fs/file dir "site.edn") moved)
                (quiet #((:on-change (first @polls)) [(str (fs/path dir "site.edn"))]))
                (fs/create-dirs (fs/path dir "notes"))
                (is (wait-until #(some #{(str (fs/path dir "notes"))} (map str ((:paths (first @polls))))))
                    "the poll loop's paths follow it")
                (finally (quiet (:stop! h)))))))))))

;; ---------------------------------------------------------------------------
;; Items 1, 4, 18: the real pod (CLOGEM_FSWATCHER, as CI sets it)

(defn- real-pod-dev
  "`start-dev!` over `dir` with the real fswatcher pod and a fake server;
  calls (f out builds), then stops it — the pod with it."
  [dir f]
  (let [out    (java.io.StringWriter.)
        builds (atom 0)
        build  cli/build
        h      (binding [*out* out *err* out]
                 (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true}
                                 {:run-server! (fn [& _] (fn [& _] nil))
                                  :notify! (fn [_]) :notify-error! (fn [_])
                                  :build (fn [o] (try (build o) (finally (swap! builds inc))))}))]
    (try
      (is (str/includes? (str out) "watching via fswatcher")
          (str "CLOGEM_FSWATCHER is set, so a fallback to polling is a failure: " out))
      (f out builds)
      (finally (binding [*out* out *err* out] ((:stop! h)))))))

(defmacro ^:private with-real-pod [test-name & body]
  `(if-not (System/getenv "CLOGEM_FSWATCHER")
     (println ~(str test-name ": CLOGEM_FSWATCHER is not set; skipped"))
     (do ~@body)))

(deftest the-real-pod-sees-a-file-in-a-directory-created-later
  (testing "item 1, with the real pod: content/07.Topic/01.new.md created
            after start, then edited, is two rebuilds"
    (with-real-pod "the-real-pod-sees-a-file-in-a-directory-created-later"
      (with-site url-site
        (fn [dir _]
          (real-pod-dev
           dir
           (fn [out builds]
             (let [topic (fs/path dir "content" "07.Topic")
                   f     (fs/file topic "01.new.md")
                   seen  #(count (filter (fn [[_ c]] (str/includes? c "01.new.md")) (rebuilt-lines out)))]
               (fs/create-dirs topic)
               (Thread/sleep 300)
               (spit f (art "t00002"))
               (is (wait-until #(pos? (seen)) 5000) (str "the new file is a rebuild: " out))
               (let [n0 (seen)]
                 (Thread/sleep 300)
                 (spit f (str (art "t00002") "\nedited\n"))
                 (is (wait-until #(> (seen) n0) 5000) (str "and so is its edit: " out)))
               (testing "and a directory replaced: an edit inside it after is seen"
                 (let [ov (fs/path dir "overrides")]
                   (fs/create-dirs ov)
                   (spit (fs/file ov "custom.css") "a{}")
                   (is (wait-until #(str/includes? (str out) "custom.css") 5000))
                   (Thread/sleep 300)
                   (fs/delete-tree ov)
                   (fs/create-dirs ov)
                   (spit (fs/file ov "custom.css") "b{}")
                   (Thread/sleep 1500)
                   (let [b0 @builds]
                     (spit (fs/file ov "custom.css") "c{}")
                     (is (wait-until #(> @builds b0) 5000) (str "the edit after the replace: " out)))))))))))))

(deftest the-real-pod-sees-a-symlinked-site-edn-s-target-edited
  (testing "item 4, with the real pod: `sed -i` on the target of a
            symlinked site.edn is a rebuild"
    (with-real-pod "the-real-pod-sees-a-symlinked-site-edn-s-target-edited"
      (with-site url-site
        (fn [dir _]
          (let [shared (fs/path dir "shared")
                target (fs/path shared "site.edn")]
            (fs/create-dirs shared)
            (fs/move (fs/path dir "site.edn") target)
            (fs/create-sym-link (fs/path dir "site.edn") target)
            (real-pod-dev
             dir
             (fn [out builds]
               (doseq [title ["A" "B"]]
                 (let [b0 @builds]
                   ;; what `sed -i` does: a temp file renamed over the target
                   (let [tmp (fs/path shared (str "sed" (rand-int 100000)))]
                     (spit (fs/file tmp) (pr-str (assoc-in url-site [:site :title] title)))
                     (fs/move tmp target {:replace-existing true :atomic-move true}))
                   (is (wait-until #(> @builds b0) 5000) (str "edit " title ": " out))
                   (Thread/sleep 300)))
               (testing "an in-place append too"
                 (let [b0 @builds]
                   (spit (fs/file target) "\n" :append true)
                   (is (wait-until #(> @builds b0) 5000) (str out))))))))))))

;; ---------------------------------------------------------------------------
;; Items 19, 21: listen failures and the printed URL

(deftest every-listen-failure-is-one-clear-error
  (testing "item 19: a port out of range, and an address family this host
            lacks, were raw stack traces"
    (with-root
      (fn [root]
        (doseq [[opts why] [[{:ip "127.0.0.1" :port 70000} "a port is a number from 0 to 65535"]
                            [{:ip "127.0.0.1" :port -1} "a port is a number from 0 to 65535"]]]
          (let [e (try (dev/run-server! (dev/make-handler root {}) opts) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) (pr-str opts))
            (is (= 1 (:babashka/exit (ex-data e))))
            (is (str/includes? (str (ex-message e)) why) (str (ex-message e)))))
        (testing "::1 — on a host without IPv6 (this container), a clean error"
          (let [r (try (dev/run-server! (dev/make-handler root {}) {:ip "::1" :port 0 :legacy-return-value? false})
                       (catch clojure.lang.ExceptionInfo e e))]
            (if (instance? clojure.lang.ExceptionInfo r)
              (is (re-find #"cannot listen on \[::1\]:0 — (this machine cannot listen on an IPv6 address|::1 is not an address)"
                           (str (ex-message r)))
                  (str (ex-message r)))
              (do (println "every-listen-failure-is-one-clear-error: this host has IPv6; ::1 listened")
                  (org.httpkit.server/server-stop! r)))))))))

(deftest the-printed-url-has-the-real-port-and-a-name-giscus-allows
  (testing "item 21: --host 0.0.0.0 printed http://0.0.0.0:…, --port 0 printed :0"
    (with-root
      (fn [root]
        (spit (fs/file root "index.html") "x")
        (let [o (java.io.StringWriter.)
              srv (binding [*out* o] (dev/start-serve! {:dir (str root) :port 0 :host "0.0.0.0"}))]
          (try
            (let [port (:local-port (meta srv))]
              (is (pos? port))
              (is (str/includes? (str o) (str "at http://localhost:" port "/")) (str o))
              (is (str/includes? (str o) "listening on every interface") (str o)))
            (finally (srv :timeout 100))))
        (with-site url-site
          (fn [dir _]
            (let [o (java.io.StringWriter.)
                  h (binding [*out* o]
                      (dev/start-dev! {:site-dir (str dir) :poll true :no-write true :port 0}
                                      {:poll-watch! (fn [& _] nil) :notify! (fn [_]) :notify-error! (fn [_])}))]
              (try
                (is (re-find #"dev server at http://localhost:[1-9]\d*/" (str o)) (str o))
                (is (not (str/includes? (str o) ":0/")))
                (finally (quiet (:stop! h)))))))))))

;; ---------------------------------------------------------------------------
;; Item 10: a percent-encoded base

(deftest a-percent-encoded-base-is-the-same-base
  (testing "item 10: \"/%E6%96%87/\" was encoded again — the redirect, the
            printed URL and every link"
    (doseq [b ["/文/" "/%E6%96%87/" "/%e6%96%87"]]
      (is (= "/文/" (config/base-path {:site {:base b}})) b))
    (is (= "/a+b/" (config/base-path {:site {:base "/a+b/"}})) "a plus stays a plus")
    (is (= "/%zz/" (config/base-path {:site {:base "/%zz/"}})) "not valid encoding: as written")
    (with-site (assoc-in url-site [:site :base] "/%E6%96%87/")
      (fn [dir out]
        (quiet #(cli/build {:site-dir (str dir) :no-write true}))
        (is (str/includes? (slurp (fs/file out "index.html")) "href=\"/%E6%96%87/pages/t00001/\""))
        (let [servers (atom []) o (java.io.StringWriter.)
              h (binding [*out* o]
                  (dev/start-dev! {:site-dir (str dir) :poll true :no-write true}
                                  {:run-server! (fn [handler _] (swap! servers conj handler) (fn [& _] nil))
                                   :poll-watch! (fn [& _] nil) :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (str/includes? (str o) "http://localhost:1888/%E6%96%87/") (str o))
            (is (= "/%E6%96%87/" (get-in ((first @servers) {:uri "/"}) [:headers "Location"])))
            (is (= 200 (:status ((first @servers) {:uri "/%E6%96%87/pages/t00001/"}))))
            (finally (quiet (:stop! h)))))))))

;; ---------------------------------------------------------------------------
;; Item 23: a rename-style save of a stylesheet

(deftest a-rename-style-stylesheet-save-swaps-the-css
  (testing "item 23: `sed -i` (and editors whose temp names the submit
            filter does not know) left a temp path in the changed set"
    (with-root
      (fn [root]
        (let [css (str (fs/path root "custom.css"))]
          (spit css "a{}")
          (doseq [tmp ["sedAb12Cd" "custom.css___jb_tmp___" "custom.css___jb_old___" ".goutputstream-XYZ12"]]
            (is (= "css" (dev/reload-kind [css (str (fs/path root tmp))] #{"x.css"} #{"x.css"} true)) tmp))
          (testing "but never a deleted page, or a temp name that still exists"
            (is (= "reload" (dev/reload-kind [css (str (fs/path root "gone.md"))] #{} #{} true)))
            (spit (fs/file root "LICENSE") "x")
            (is (= "reload" (dev/reload-kind [css (str (fs/path root "LICENSE"))] #{} #{} true)))
            (is (= "reload" (dev/reload-kind [(str (fs/path root "sedAb12Cd"))] #{} #{} true)) "nothing left: a reload")))))))

;; ---------------------------------------------------------------------------
;; Item 27: a tool's multi-line failure

(deftest a-tool-failure-keeps-its-lines
  (testing "item 27: one-line made Pagefind's report `exited 3:; Running …`"
    (let [msg (str "clogem-press: search: Pagefind exited 3:\nRunning Pagefind v1.5.2 (Extended)\n"
                   "Running from: /s\nError: Failed to read the HTML files\n"
                   "  hint: The previous search index is still in place.")]
      (is (= (str "Pagefind exited 3:\n    Running Pagefind v1.5.2 (Extended)\n    Running from: /s\n"
                  "    Error: Failed to read the HTML files\n  hint: The previous search index is still in place.")
             ((late tool-output) msg)))
      (is (not (str/includes? (dev/one-line msg) ":;")) (dev/one-line msg)))
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir out]
        (fs/create-dirs out)
        (let [bin (fake/fake-pagefind! (fs/path dir "bin") :exit 3 :output "line one")
              cfg (first (diag/collecting (config/load-config (str dir) nil {:build {:out (str out)}
                                                                              :tools {:pagefind {:path bin}}})))
              o   (with-out-str ((#'dev/indexer (constantly false) (fn [g] (g))) cfg))]
          (is (re-find #"(?m)^clogem-press: search index failed — Pagefind exited 3:\n    line one\n    boom\n  hint: " o) o))))))
