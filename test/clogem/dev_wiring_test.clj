;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-wiring-test
  "P4-D.1: `bb dev`'s wiring (`dev/start-dev!`, with a fake server, watcher
  and indexer), the handler across a search index swap, the server's
  errors and URL, and following watched paths that appear later.

  Each test names the finding it pins down; each failed before its fix."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.i18n :as i18n]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(defn- with-site
  "A temp site with one article and `site-edn`; calls (f dir out)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-wiring"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms`; its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(defn- wired
  "`dev/start-dev!` over `dir` with everything outside injected: the server
  (its handler and options captured), the watcher (the poll loop's
  arguments captured, nothing run), the indexer and the client
  notifications (into one ordered `log`), and `build` (its options
  captured). Calls (f ctx), then stops it — the drain loop with it."
  [dir opts env f]
  (let [log     (atom [])
        builds  (atom [])
        servers (atom [])
        polls   (atom [])
        build   cli/build
        out     (java.io.StringWriter.)
        err     (java.io.StringWriter.)
        h       (binding [*out* out *err* err]
                  (dev/start-dev!
                   (merge {:site-dir (str dir) :poll true :port 1888 :no-write true} opts)
                   (merge {:run-server!   (fn [handler o] (swap! servers conj [handler o]) (fn [& _] nil))
                           :poll-watch!   (fn [paths interval on-change o]
                                            (swap! polls conj {:paths paths :interval interval
                                                               :on-change on-change :opts o}))
                           :index!        (fn [_] (swap! log conj [:index]))
                           :notify!       (fn [k] (swap! log conj [:notify k]))
                           :notify-error! (fn [e] (swap! log conj [:error (:message e)]))
                           ;; recorded once the build has returned
                           :build         (fn [o]
                                            (try (binding [*out* out *err* err] (build o))
                                                 (finally (swap! builds conj o))))}
                          env)))]
    (try
      (f {:h h :log log :builds builds :servers servers :polls polls :out out :err err
          :handler (first (first @servers)) :sopts (second (first @servers))
          :on-change (fn [paths]
                       (wait-until #(seq @polls))
                       ((:on-change (first @polls)) paths))})
      (finally ((:stop! h))))))

;; ---------------------------------------------------------------------------
;; Finding 9: dev!'s wiring, under the mutants that used to pass

(deftest dev-s-build-gets-build-opts-both-flags-included
  (testing "m1: dropping :clogem/dev? from dev's build call rendered the
            fallback instead of ⟦key⟧ and ran Pagefind inline"
    (with-site url-site
      (fn [dir _]
        (let [opts {:site-dir (str dir) :poll true :port 1888 :no-write true}]
          (wired dir {} {}
                 (fn [{:keys [builds]}]
                   (is (= 1 (count @builds)) "the first build")
                   (is (= (dev/build-opts opts) (first @builds)))
                   (is (true? (:clogem/dev? (first @builds))))
                   (is (true? (:clogem/dev-loop? (first @builds)))))))))))

(deftest dev-s-watcher-submits-to-the-debounced-queue
  (testing "m2: a watcher calling rebuild directly — no debounce, and editor
            temp files rebuilt for"
    (with-site url-site
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [builds on-change]}]
                 (on-change [(str (fs/path dir "content" ".#01.t.md")) (str (fs/path dir "content" "x.swp"))])
                 (Thread/sleep 300)
                 (is (= 1 (count @builds)) "temp files are not changes")
                 (dotimes [i 5]
                   (on-change [(str (fs/path dir "content" "01.Guide" "01.t.md"))]))
                 (is (wait-until #(= 2 (count @builds))))
                 (Thread/sleep 300)
                 (is (= 2 (count @builds)) "five quick changes are one rebuild")))))))

(deftest dev-listens-on-loopback-unless-told
  (testing "m6: dev's server options forced to 0.0.0.0"
    (with-site url-site
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [sopts]}]
                 (is (= {:ip "127.0.0.1" :port 1888} sopts))))
        (wired dir {:host "::1" :port 9} {}
               (fn [{:keys [sopts]}]
                 (is (= {:ip "::1" :port 9} sopts))))))))

(deftest dev-mounts-dist-at-the-base
  (testing "m9: :base dropped from dev's make-handler"
    (with-site (assoc-in url-site [:site :base] "/project/")
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [handler]}]
                 (is (= 200 (:status (handler {:uri "/project/pages/t00001/"}))))
                 (is (= 302 (:status (handler {:uri "/"}))))
                 (is (= 404 (:status (handler {:uri "/pages/t00001/"}))))))))))

(deftest dev-follows-a-moved-content-dir-when-polling
  (testing "m14: (reset! cfg-ref c) dropped — polling kept watching the old
            :content :dir"
    (with-site url-site
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [builds polls on-change]}]
                 (fs/create-dirs (fs/path dir "notes" "01.Guide"))
                 (spit (fs/file dir "notes" "01.Guide" "01.t.md") article)
                 (spit (fs/file dir "site.edn") (pr-str (assoc url-site :content {:dir "notes" :write-front-matter false})))
                 (on-change [(str (fs/path dir "site.edn"))])
                 (is (wait-until #(contains? (set (map str ((:paths (first @polls))))) (str (fs/path dir "notes")))))
                 (let [paths (set (map str ((:paths (first @polls)))))]
                   (is (contains? paths (str (fs/path dir "notes"))) (pr-str paths))
                   (is (not (contains? paths (str (fs/path dir "content")))) (pr-str paths)))))))))

(deftest dev-reloads-before-it-indexes
  (testing "m8: the index request moved before the reload — the page waited
            for Pagefind"
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [log on-change builds]}]
                 (is (= [[:notify "reload"] [:index]] @log) "the first build")
                 (reset! log [])
                 (on-change [(str (fs/path dir "content" "01.Guide" "01.t.md"))])
                 (is (wait-until #(= 2 (count @log))))
                 (is (= [[:notify "reload"] [:index]] @log))
                 (is (= 2 (count @builds)))))))))

(deftest dev-samples-the-stylesheets-before-the-build
  (testing "m15: (reload-kind changed after after …) — adding or deleting
            overrides/custom.css sent a CSS swap, which cannot add a link"
    (with-site url-site
      (fn [dir _]
        (wired dir {} {}
               (fn [{:keys [log on-change builds]}]
                 (reset! log [])
                 (fs/create-dirs (fs/path dir "overrides"))
                 (spit (fs/file dir "overrides" "custom.css") "body{color:red}")
                 (on-change [(str (fs/path dir "overrides" "custom.css"))])
                 (is (wait-until #(= 2 (count @builds))))
                 (is (wait-until #(seq @log)))
                 (is (= [[:notify "reload"]] @log) "added: a reload")
                 (reset! log [])
                 (spit (fs/file dir "overrides" "custom.css") "body{color:blue}")
                 (on-change [(str (fs/path dir "overrides" "custom.css"))])
                 (is (wait-until #(seq @log)))
                 (is (= [[:notify "css"]] @log) "edited: a swap")
                 (reset! log [])
                 (fs/delete (fs/path dir "overrides" "custom.css"))
                 (on-change [(str (fs/path dir "overrides" "custom.css"))])
                 (is (wait-until #(seq @log)))
                 (is (= [[:notify "reload"]] @log) "deleted: a reload")))))))

;; ---------------------------------------------------------------------------
;; Finding 4: the server is bound before the first build; a bind failure
;; is one clear error

(deftest a-bind-failure-is-a-clear-error-and-writes-nothing
  (with-site url-site
    (fn [dir out]
      (let [sock (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
            port (.getLocalPort sock)]
        (try
          (testing "a busy port"
            (let [e (try (dev/run-server! (dev/make-handler (str out) {}) {:ip "127.0.0.1" :port port})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (some? e))
              (is (= 1 (:babashka/exit (ex-data e))))
              (is (str/includes? (ex-message e) (str "cannot listen on 127.0.0.1:" port)) (ex-message e))
              (is (str/includes? (ex-message e) "already in use") (ex-message e))
              (is (str/includes? (ex-message e) "--port") (ex-message e))))
          (testing "an address that is not this machine's"
            (let [e (try (dev/run-server! (dev/make-handler (str out) {}) {:ip "192.0.2.1" :port 0})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (some? e))
              (is (str/includes? (str (ex-message e)) "cannot listen on 192.0.2.1:0") (str (ex-message e)))
              (is (str/includes? (str (ex-message e)) "--host 127.0.0.1"))))
          (testing "dev binds before it builds: nothing is written to dist/"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot listen"
                                  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                                    (dev/start-dev! {:site-dir (str dir) :poll true :port port :no-write true}
                                                    {:poll-watch! (fn [& _] nil)}))))
            (is (not (fs/exists? out)) "no build ran"))
          (finally (.close sock)))))))

;; ---------------------------------------------------------------------------
;; Finding 1: a page that loaded the previous search bundle keeps working

(deftest the-previous-bundle-is-served-across-a-swap
  (with-site (assoc url-site :search {:provider :pagefind})
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))
            cfg (first (diag/collecting (config/load-config (str dir) nil {:build {:out (str out)}
                                                                            :tools {:pagefind {:path bin}}})))
            h   (dev/make-handler out {:inject-reload? true :state (atom {:ever-ok? true})})
            st  (fn [uri] (:status (h {:uri uri})))]
        (fs/create-dirs out)
        (spit (fs/file out "index.html") "<html><body>x</body></html>")
        (search/run-staged! cfg)
        ;; what the page loaded: generation 1's content-hashed files
        (fs/create-dirs (fs/path out "pagefind" "fragment"))
        (spit (fs/file out "pagefind" "fragment" "en_gen1.pf_fragment") "gen 1")
        (spit (fs/file out "pagefind" "pagefind.en_gen1.pf_meta") "gen 1")
        (testing "after the next swap, generation 1's files are still served"
          (search/run-staged! cfg)
          (is (not (fs/exists? (fs/path out "pagefind" "fragment" "en_gen1.pf_fragment"))) "the live bundle is new")
          (is (= 200 (st "/pagefind/fragment/en_gen1.pf_fragment")))
          (is (= 200 (st "/pagefind/pagefind.en_gen1.pf_meta")))
          (is (= 200 (st "/pagefind/pagefind-entry.json")) "the live bundle answers first"))
        (testing "between the swap's two renames, pagefind/ is missing; nothing 404s"
          (fs/move (fs/path out "pagefind") (fs/path out "in-flight"))
          (is (= 200 (st "/pagefind/pagefind-entry.json")))
          (is (= 200 (st "/pagefind/fragment/en_gen1.pf_fragment")))
          (fs/move (fs/path out "in-flight") (fs/path out "pagefind")))
        (testing "one swap later generation 1 is gone (until the next reload, §5.4)"
          (search/run-staged! cfg)
          (is (= 404 (st "/pagefind/fragment/en_gen1.pf_fragment"))))
        (testing "only pagefind/ falls back"
          (is (= 404 (st "/pagefind-entry.json"))))))))

(deftest a-file-gone-between-check-and-serve-is-never-a-500-with-a-path
  (let [root (fs/create-temp-dir {:prefix "clogem-race"})]
    (try
      (fs/create-dirs (fs/path root "pagefind"))
      (spit (fs/file root "pagefind" "a.pf_fragment") "x")
      (let [h     (dev/make-handler root {:inject-reload? true :state (atom {:ever-ok? true})})
            ghost (fs/path root "pagefind" "renamed-away.pf_fragment")]
        (testing "resolved, then renamed away before it is opened"
          (with-redefs [dev/resolve-path (fn [& _] ghost)]
            (let [r (h {:uri "/pagefind/renamed-away.pf_fragment"})]
              (is (= 404 (:status r)))
              (is (not (str/includes? (str (:body r)) (str root))) "no filesystem path"))))
        (testing "anything that throws is a 500 that names no path"
          (with-redefs [dev/resolve-path (fn [& _] (throw (java.io.IOException. (str root "/secret"))))]
            (let [r (h {:uri "/pagefind/a.pf_fragment"})]
              (is (= 500 (:status r)))
              (is (not (str/includes? (str (:body r)) (str root))) (:body r)))))
        (testing "a file is served from a stream opened once, not a File http-kit reopens"
          (is (instance? java.io.InputStream (:body (h {:uri "/pagefind/a.pf_fragment"}))))))
      (finally (fs/delete-tree root)))))

;; ---------------------------------------------------------------------------
;; Finding 5: staging leftovers

(deftest bb-build-sweeps-what-a-killed-dev-left
  (with-site (assoc url-site :search {:provider :pagefind})
    (fn [dir out]
      (let [bin     (fake/fake-pagefind! (fs/path dir "bin"))
            outside (fs/path dir "outside")]
        (spit (fs/file dir "site.edn") (pr-str (assoc url-site :search {:provider :pagefind}
                                                      :tools {:pagefind {:path bin}})))
        (fs/create-dirs out)
        (fs/create-dirs (fs/path out ".pagefind-staging-999999999-1" "fragment"))
        (fs/create-dirs (fs/path out ".pagefind-old-999999999-2"))
        (fs/create-dirs (fs/path out ".pagefind-prev"))
        (fs/create-dirs outside)
        (spit (fs/file outside "keep.txt") "keep")
        (fs/create-sym-link (fs/path out ".pagefind-staging-999999999-3") outside)
        (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
          (cli/build {:site-dir (str dir) :no-write true}))
        (is (= #{"pagefind"} (set (filter #(str/includes? % "pagefind")
                                          (map (comp str fs/file-name) (fs/list-dir out)))))
            "the staging, retired and previous bundles are gone")
        (is (= "keep" (slurp (fs/file outside "keep.txt"))) "a link is removed, never followed")))))

(deftest dev-s-stop-removes-its-own-staging
  (with-site url-site
    (fn [dir out]
      (wired dir {} {}
             (fn [{:keys [h]}]
               (let [mine (fs/path out (str ".pagefind-staging-" (.pid (java.lang.ProcessHandle/current)) "-7"))]
                 (fs/create-dirs mine)
                 ((:stop! h))
                 (is (not (fs/exists? mine)))))))))

;; ---------------------------------------------------------------------------
;; Finding 6: a base that is not ASCII, or has a space

(deftest a-non-ascii-or-spaced-base-is-mounted
  (let [root (fs/create-temp-dir {:prefix "clogem-b"})]
    (try
      (fs/create-dirs (fs/path root "pages" "a"))
      (spit (fs/file root "index.html") "<html><body>home</body></html>")
      (spit (fs/file root "pages" "a" "index.html") "<html><body>a</body></html>")
      (spit (fs/file root "a%20b") "literal")
      (spit (fs/file root "a+b.txt") "plus")
      (doseq [[base enc] [["/文档/" "/%E6%96%87%E6%A1%A3/"] ["/my docs/" "/my%20docs/"]]
              :let [h (dev/make-handler root {:base base})]]
        (testing base
          (is (= 200 (:status (h {:uri enc}))))
          (is (= 200 (:status (h {:uri (str enc "pages/a/")}))))
          (is (= enc (get-in (h {:uri "/"}) [:headers "Location"])) "the redirect is percent-encoded")
          (is (= 302 (:status (h {:uri (subs enc 0 (dec (count enc)))})))))
        (testing (str base ": decoded once — `%2520` is a file named `a%20b`")
          (is (= 200 (:status (h {:uri (str enc "a%2520b")}))))
          (is (= 404 (:status (h {:uri (str enc "a%20b")}))) "no file is named `a b`"))
        (testing (str base ": a decoded `..`, `/` or NUL never escapes")
          (doseq [u [(str enc "..%2F..%2Fetc%2Fpasswd") (str enc "%2E%2E/%2E%2E/etc/passwd")
                     (str enc "%00") (str enc "pages%2F..%2F..%2F..")]]
            (is (#{400 404} (:status (h {:uri u}))) u))
          (is (= 400 (:status (h {:uri (str enc "%zz")}))) "bad percent-encoding")))
      (testing "a + in a path is a plus"
        (is (= 200 (:status ((dev/make-handler root {}) {:uri "/a+b.txt"})))))
      (finally (fs/delete-tree root)))))

;; ---------------------------------------------------------------------------
;; Finding 7: serve with an unreadable site.edn

(deftest serve-with-an-unreadable-config-warns-and-serves-at-root
  (let [site (fs/create-temp-dir {:prefix "clogem-trunc"})]
    (try
      (spit (fs/file site "site.edn") "{:site {:title \"x\" :base \"/p/\"")
      (let [err  (java.io.StringWriter.)
            base (binding [*err* err] (dev/serve-base {:site-dir (str site)}))]
        (is (= "/" base))
        (is (= 1 (count (re-seq #"(?m)^warning: " (str err)))) (str err))
        (is (str/includes? (str err) "served at /") (str err)))
      (is (= "/p/" (dev/serve-base {:site-dir (str site) :base "/p/"})) "--base needs no config")
      (finally (fs/delete-tree site)))))

;; ---------------------------------------------------------------------------
;; Finding 8: the printed URL

(deftest the-printed-url-is-localhost-on-the-default-host
  (testing "giscus allow-lists http://localhost:[0-9]+, not 127.0.0.1"
    (is (= "http://localhost:1888/" (dev/url-for (dev/server-options {:port 1888}) "/")))
    (is (= "http://localhost:1888/project/" (dev/url-for (dev/server-options {:port 1888}) "/project/")))
    (is (= "http://localhost:1888/%E6%96%87%E6%A1%A3/" (dev/url-for (dev/server-options {:port 1888}) "/文档/")))
    (is (= "http://0.0.0.0:9/" (dev/url-for (dev/server-options {:host "0.0.0.0" :port 9}) "/")))
    (is (= "http://[::1]:9/" (dev/url-for (dev/server-options {:host "::1" :port 9}) "/"))))
  (testing "and `localhost` reaches the server dev binds"
    (let [srv (dev/run-server! (dev/make-handler (str (fs/create-temp-dir)) {}) {:ip "127.0.0.1" :port 0
                                                                                :legacy-return-value? false})]
      (try
        (let [port (org.httpkit.server/server-port srv)
              c    (java.net.Socket. "localhost" (int port))]
          (is (.isConnected c))
          (.close c))
        (finally (org.httpkit.server/server-stop! srv))))))

;; ---------------------------------------------------------------------------
;; Finding 13: missing keys, once per session

(deftest a-missing-key-warns-once-per-dev-session
  (with-site (assoc url-site :langs {:default :en :priority [:en :ta]
                                     :locales {:en {:label "English" :html-lang "en"}
                                               :ta {:label "தமிழ்" :html-lang "ta"}}})
    (fn [dir out]
      (let [orig  i18n/theme-strings
            gone? (atom true)
            build (fn []
                    (let [err (java.io.StringWriter.)]
                      (binding [*out* (java.io.StringWriter.) *err* err]
                        (cli/build {:site-dir (str dir) :out (str out) :no-write true :clogem/dev? true}))
                      (count (re-seq #"missing UI string key `:mode/label`" (str err)))))]
        (with-redefs [i18n/theme-strings (fn [lang] (cond-> (orig lang) @gone? (dissoc :mode/label)))]
          (let [n (build)]
            (is (pos? n) "one per language")
            (testing "outside a dev session, every build warns"
              (is (= n (build))))
            (binding [i18n/*session-keys* (atom #{})]
              (is (= n (build)) "in a session: once")
              (is (= 0 (build)) "not again on the next rebuild")
              (reset! gone? false)
              (is (= 0 (build)) "defined again")
              (reset! gone? true)
              (is (= n (build)) "missing once more: warned again"))))))))

(deftest dev-s-rebuilds-share-one-session
  (with-site url-site
    (fn [dir _]
      (let [sessions (atom [])
            build    cli/build]
        (wired dir {} {:build (fn [o] (swap! sessions conj i18n/*session-keys*) (build o))}
               (fn [{:keys [on-change]}]
                 (on-change [(str (fs/path dir "content" "01.Guide" "01.t.md"))])
                 (is (wait-until #(= 2 (count @sessions))))
                 (is (some? (first @sessions)) "bound")
                 (is (apply identical? @sessions) "the same atom for every rebuild")))))))

;; ---------------------------------------------------------------------------
;; Finding 14: messages

(deftest a-signal-stopped-index-prints-nothing
  (with-site (assoc url-site :search {:provider :pagefind})
    (fn [dir out]
      (fs/create-dirs out)
      (doseq [[exit expect] [[130 nil] [143 nil] [3 #"^clogem-press: search index failed — Pagefind exited 3:"]]]
        (let [bin (fake/fake-pagefind! (fs/path dir (str "bin" exit)) :exit exit)
              cfg (first (diag/collecting (config/load-config (str dir) nil {:build {:out (str out)}
                                                                              :tools {:pagefind {:path bin}}})))
              o   (with-out-str ((#'dev/indexer (constantly false) (fn [g] (g))) cfg))]
          (if expect
            (do (is (re-find expect o) o)
                (is (not (str/includes? o "— clogem-press:")) "no doubled prefix"))
            (is (= "" o) (str "exit " exit))))))))

(deftest the-pod-fallback-line-has-one-prefix-and-keeps-the-hint
  (is (= "could not download fswatcher pod from http://x/: refused; Run `bb dev --poll`, or point CLOGEM_FSWATCHER at it."
         (dev/one-line "clogem-press: dev: could not download fswatcher pod from http://x/: refused\n  hint: Run `bb dev --poll`, or point CLOGEM_FSWATCHER at it."))))

;; ---------------------------------------------------------------------------
;; Finding 15: a changed :base or :out is served

(deftest a-changed-base-or-out-is-served-without-a-restart
  (with-site url-site
    (fn [dir _]
      (wired dir {} {}
             (fn [{:keys [handler builds on-change out err]}]
               (is (= 200 (:status (handler {:uri "/pages/t00001/"}))))
               (spit (fs/file dir "site.edn") (pr-str (-> url-site
                                                           (assoc-in [:site :base] "/new/")
                                                           (assoc-in [:build :out] "public"))))
               (on-change [(str (fs/path dir "site.edn"))])
               (is (wait-until #(= 200 (:status (handler {:uri "/new/pages/t00001/"})))) "the new base is mounted")
               (is (= 302 (:status (handler {:uri "/"}))))
               (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
               (is (wait-until #(str/includes? (str out) "the site base changed from / to /new/")) (str out))
               (is (str/includes? (str out) "http://localhost:1888/new/"))
               (is (str/includes? (str out) "the output directory changed")))))))

;; ---------------------------------------------------------------------------
;; Findings 2 and 3: pod mode follows renames and new directories

(deftest site-edn-is-watched-through-its-directory
  (testing "a watch on the file stays on its inode: after `sed -i` or an
            atomic save, later edits went unseen"
    (with-site url-site
      (fn [dir _]
        (let [cfg   (first (diag/collecting (config/load-config (str dir))))
              specs (dev/watch-specs cfg)
              cf    (str (fs/normalize (fs/absolutize (fs/path dir "site.edn"))))
              spec  (first (filter :only specs))]
          (is (= {:path (str (fs/normalize (fs/absolutize dir))) :recursive? false :only cf} spec))
          (is (not-any? #(= cf (:path %)) specs) "never the file itself")
          (is (dev/watched-event? specs cf))
          (is (dev/watched-event? specs (str (fs/path dir "content" "01.Guide" "01.t.md"))))
          (is (not (dev/watched-event? specs (str (fs/path dir "permalinks.edn")))))
          (is (not (dev/watched-event? specs (str (fs/path dir "dist" "index.html")))))
          (is (not (dev/watched-event? specs (str (fs/path dir "sedAb12Cd")))) "sed's temp file")
          (testing "and the probe registers the directory, not recursively"
            (let [calls (atom [])]
              (with-out-str
                (dev/probe-watch! cfg (fn [_])
                                  {:watch (fn [p cb o]
                                            (swap! calls conj [p (:recursive o)])
                                            (when (str/ends-with? p "content")
                                              (future (Thread/sleep 50)
                                                      (doseq [f (fs/glob p ".clogem-watch-probe-*")]
                                                        (cb {:type :create :path (str f)}))))
                                            {:id p})
                                   :unwatch identity :gap-ms 0 :timeout-ms 2000}))
              (is (some #{[(str (fs/normalize (fs/absolutize dir))) false]} @calls) (pr-str @calls))
              (is (not-any? #(= cf (first %)) @calls)))))))))

(deftest a-watched-directory-created-later-is-followed
  (testing "overrides/ (and assets/, i18n/) missing at startup were never
            watched with the pod: creating overrides/custom.css gave 0 rebuilds"
    (with-site url-site
      (fn [dir _]
        (let [registered (atom [])
              register!  (fn [spec] (swap! registered conj spec) {:id (:path spec)})
              pod        (fn [_cfg _on-change {:keys [on-ready specs]}]
                           (doseq [s specs] (register! s))
                           (on-ready register!)
                           true)]
          (wired dir {:poll false} {:try-pod-watch! pod :follow-ms 20}
                 (fn [{:keys [builds log h]}]
                   (is (:pod? h))
                   (let [ov (str (fs/normalize (fs/absolutize (fs/path dir "overrides"))))]
                     (is (not-any? #(= ov (:path %)) @registered) "not there at startup")
                     (reset! log [])
                     (fs/create-dirs (fs/path dir "overrides"))
                     (spit (fs/file dir "overrides" "custom.css") "body{}")
                     (is (wait-until #(some (fn [s] (= ov (:path s))) @registered)) "registered once it exists")
                     (is (wait-until #(= 2 (count @builds))) "and rebuilt for")
                     (is (wait-until #(seq @log)))
                     (is (= [[:notify "reload"]] @log) "a new stylesheet: a reload")
                     (Thread/sleep 100)
                     (is (= 1 (count (filter #(= ov (:path %)) @registered))) "once")))))))))

(deftest follow-new-paths-registers-each-path-once
  (let [dir (fs/create-temp-dir {:prefix "clogem-follow"})]
    (try
      (let [a     (str (fs/path dir "a"))
            specs [{:path a :recursive? true}]
            reg   (atom #{})
            calls (atom 0)]
        (is (= [] (dev/follow-new-paths! specs reg (fn [_] (swap! calls inc)))))
        (fs/create-dirs a)
        (is (= [a] (dev/follow-new-paths! specs reg (fn [_] (swap! calls inc)))))
        (is (= [] (dev/follow-new-paths! specs reg (fn [_] (swap! calls inc)))))
        (testing "deleted and created again: a new inode, registered again"
          (fs/delete a)
          (dev/follow-new-paths! specs reg (fn [_] (swap! calls inc)))
          (fs/create-dirs a)
          (is (= [a] (dev/follow-new-paths! specs reg (fn [_] (swap! calls inc))))))
        (testing "a path the pod refuses: one line naming the restart, and not retried"
          (let [b (str (fs/path dir "b"))]
            (fs/create-dirs b)
            (let [o (with-out-str
                      (is (= [] (dev/follow-new-paths! [{:path b}] reg (fn [_] (throw (ex-info "nope" {})))))))]
              (is (str/includes? o "restart `bb dev`") o))
            (is (= "" (with-out-str (dev/follow-new-paths! [{:path b}] reg (fn [_] (throw (ex-info "nope" {})))))))))
        (is (= 2 @calls)))
      (finally (fs/delete-tree dir)))))
