;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-test
  "Dev server: path containment and watcher liveness.

  Neither is decoration. `bb dev` serves the working tree's neighbourhood over
  HTTP, and a watcher that registers but never fires makes the whole loop look
  like a build cache bug."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.dev :as dev]
            [babashka.process]
            [cheshire.core]))

;; ---------------------------------------------------------------------------
;; resolve-file containment

(defn- sandbox
  "A `dist/` to serve, a `dist-readonly/` sibling that must stay unreachable —
  CI creates exactly that pair — and a secret one directory up."
  []
  (let [root (fs/create-temp-dir {:prefix "clogem-dev"})]
    (fs/create-dirs (fs/path root "dist"))
    (fs/create-dirs (fs/path root "dist-readonly"))
    (spit (fs/file (fs/path root "dist" "index.html")) "<h1>ok</h1>")
    (spit (fs/file (fs/path root "dist" "sub" "index.html"))
          (do (fs/create-dirs (fs/path root "dist" "sub")) "<h1>sub</h1>"))
    (spit (fs/file (fs/path root "dist-readonly" "secret.txt")) "not yours")
    (spit (fs/file (fs/path root "secret.txt")) "not yours either")
    root))

(deftest resolve-file-serves-what-it-should
  (let [root (sandbox)
        dist (fs/path root "dist")]
    (try
      (is (some? (dev/resolve-file dist "/index.html")))
      (is (some? (dev/resolve-file dist "/")) "a directory resolves to its index.html")
      (is (some? (dev/resolve-file dist "/sub/")))
      (is (nil? (dev/resolve-file dist "/nope.html")))
      (finally (fs/delete-tree root)))))

(deftest resolve-file-refuses-a-sibling-whose-name-shares-the-root-prefix
  (testing "the containment check compared canonical paths as STRINGS with no
            trailing separator, so /site/dist-readonly passed a /site/dist root.
            CI creates that exact sibling — `build --out dist-readonly` — so the
            dev server would happily serve it."
    (let [root (sandbox)
          dist (fs/path root "dist")]
      (try
        (is (nil? (dev/resolve-file dist "/../dist-readonly/secret.txt"))
            "a sibling directory is not inside the root just because its name starts the same")
        (finally (fs/delete-tree root))))))

(deftest resolve-file-refuses-traversal
  (let [root (sandbox)
        dist (fs/path root "dist")]
    (try
      (doseq [uri ["/../secret.txt"
                   "/sub/../../secret.txt"
                   "/%2e%2e/secret.txt"
                   "/../../etc/passwd"]]
        (is (nil? (dev/resolve-file dist uri)) uri))
      (finally (fs/delete-tree root)))))

(deftest resolve-file-still-allows-a-path-that-only-looks-like-traversal
  (testing "the fix must not be a blunt `..` ban: a legitimate file may be named
            anything, including something with a doubled root prefix inside"
    (let [root (sandbox)
          dist (fs/path root "dist")]
      (try
        (fs/create-dirs (fs/path dist "dist-readonly"))
        (spit (fs/file (fs/path dist "dist-readonly" "page.html")) "mine")
        (is (some? (dev/resolve-file dist "/dist-readonly/page.html"))
            "this one really is under the root")
        (finally (fs/delete-tree root))))))

;; ---------------------------------------------------------------------------
;; The watcher liveness probe (§5.4, Appendix A item 5)

(defn- watch-site
  "A site dir shaped the way `watched-paths` expects."
  []
  (let [dir (fs/create-temp-dir {:prefix "clogem-watch"})]
    (fs/create-dirs (fs/path dir "content"))
    (fs/create-dirs (fs/path dir "assets"))
    (spit (fs/file (fs/path dir "site.edn")) "{}")
    (spit (fs/file (fs/path dir "content" "index.md")) "# hi\n")
    dir))

(defn- cfg-for [dir]
  (first (diag/collecting (config/load-config (str dir)))))

(defn- fake-watcher
  "Stands in for the fswatcher pod.

  `:live? true` emulates a working inotify: a background poller notices files
  appearing under a watched directory and calls the registered callback.
  `:live? false` is the environment Appendix A item 5 describes — registration
  succeeds and no event ever arrives."
  [{:keys [live?]}]
  (let [registered (atom []) unwatched (atom []) stop (atom false)]
    {:registered registered
     :unwatched  unwatched
     :stop       stop
     :watch
     (fn [path _cb-or-opts & _]
       (swap! registered conj path) {:watcher/id (count @registered) :path path})
     :unwatch (fn [w] (swap! unwatched conj w) w)
     :live?   live?}))

(defn- watcher-fns
  [{:keys [registered unwatched stop live?]}]
  {:watch (fn [path cb _opts]
            (swap! registered conj path)
            (when (and live? (fs/directory? path))
              (let [seen (atom (set (map str (fs/list-dir path))))]
                (future
                  (loop [n 0]
                    (when (and (not @stop) (< n 400))
                      (doseq [p (map str (fs/list-dir path))
                              :when (not (contains? @seen p))]
                        (swap! seen conj p)
                        (cb {:type :create :path p}))
                      (Thread/sleep 5)
                      (recur (inc n)))))))
            {:watcher/id (count @registered) :path (str path)})
   :unwatch (fn [w] (swap! unwatched conj w) w)})

(deftest probe-falls-back-when-no-event-is-delivered
  (testing "the case Appendix A item 5 records and the docstring already
            promised to detect: watchers register, nothing ever fires.
            Returning true right after registering made watching silently dead
            with no fallback — the dev loop simply stopped rebuilding."
    (let [dir (watch-site)
          w   (fake-watcher {:live? false})]
      (try
        (let [cfg (cfg-for dir)
              ok  (dev/probe-watch! cfg (fn [_] nil)
                                    (assoc (watcher-fns w) :timeout-ms 300))]
          (is (false? ok))
          (is (seq @(:registered w)) "it did register")
          (is (= (count @(:registered w)) (count @(:unwatched w)))
              "and every registration is torn down before we fall back to polling")
          (is (empty? (filter #(str/includes? (str %) "clogem-watch-probe")
                              (map str (fs/glob dir "**"))))
              "the probe file is cleaned up"))
        (finally (reset! (:stop w) true) (fs/delete-tree dir))))))

(deftest probe-succeeds-when-events-are-delivered
  (let [dir (watch-site)
        w   (fake-watcher {:live? true})]
    (try
      (let [cfg (cfg-for dir)
            ok  (dev/probe-watch! cfg (fn [_] nil)
                                  (assoc (watcher-fns w) :timeout-ms 3000))]
        (is (true? ok))
        (is (empty? @(:unwatched w)) "a live watcher is kept, not torn down")
        (is (empty? (filter #(str/includes? (str %) "clogem-watch-probe")
                            (map str (fs/glob dir "**"))))))
      (finally (reset! (:stop w) true) (fs/delete-tree dir)))))

(deftest the-probe-event-itself-never-triggers-a-rebuild
  (testing "the probe writes into a watched directory, so its own event must be
            swallowed — otherwise starting `bb dev` rebuilds twice"
    (let [dir     (watch-site)
          w       (fake-watcher {:live? true})
          changed (atom [])]
      (try
        (let [cfg (cfg-for dir)]
          (is (true? (dev/probe-watch! cfg #(swap! changed into %)
                                       (assoc (watcher-fns w) :timeout-ms 3000))))
          (is (empty? @changed) "no rebuild from the probe")
          ;; a real change still gets through on the same registration
          (spit (fs/file (fs/path dir "content" "new.md")) "# new\n")
          (Thread/sleep 300)
          (is (some #(str/includes? (str %) "new.md") @changed)
              "and the watcher we kept is still wired to on-change"))
        (finally (reset! (:stop w) true) (fs/delete-tree dir))))))

(deftest probe-declines-when-there-is-nothing-to-probe
  (testing "with no watchable directory there is no way to prove liveness, so
            the conservative answer is polling"
    (let [dir (fs/create-temp-dir {:prefix "clogem-bare"})]
      (try
        (spit (fs/file (fs/path dir "site.edn")) "{}")
        (let [w (fake-watcher {:live? false})]
          (is (false? (dev/probe-watch! (cfg-for dir) (fn [_] nil)
                                        (assoc (watcher-fns w) :timeout-ms 200)))))
        (finally (fs/delete-tree dir))))))

(deftest probe-falls-back-when-registration-itself-blocks
  (testing "`watch` is a synchronous call into a subprocess and was observed
            here to block indefinitely, hanging `bb dev` before it had printed
            anything — strictly worse than the dead-watcher case the probe
            exists for. Registration is on the clock too."
    (let [dir (watch-site)
          started (promise)]
      (try
        (is (false? (dev/probe-watch!
                     (cfg-for dir) (fn [_] nil)
                     {:watch   (fn [_p _cb _opts] (deliver started true) @(promise))
                      :unwatch (fn [w] w)
                      :timeout-ms 300}))
            "we give up on it rather than blocking the dev server for ever")
        (is (true? (deref started 1000 false)) "…having actually attempted it")
        (finally (fs/delete-tree dir))))))

(deftest probe-falls-back-when-registration-throws
  (let [dir (watch-site)]
    (try
      (is (false? (dev/probe-watch!
                   (cfg-for dir) (fn [_] nil)
                   {:watch   (fn [_ _ _] (throw (ex-info "pod is gone" {})))
                    :unwatch (fn [w] w)
                    :timeout-ms 300})))
      (finally (fs/delete-tree dir)))))

(deftest an-abandoned-registration-does-not-drive-rebuilds
  (testing "if a slow `watch` finally lands after we have fallen back, its
            events must not start racing the poll loop"
    (let [dir     (watch-site)
          changed (atom [])
          late    (atom nil)]
      (try
        (is (false? (dev/probe-watch!
                     (cfg-for dir) #(swap! changed into %)
                     {:watch   (fn [_p cb _opts] (reset! late cb) (Thread/sleep 600) {:id 1})
                      :unwatch (fn [w] w)
                      :timeout-ms 200})))
        (Thread/sleep 700)
        (when-let [cb @late] (cb {:type :write :path (str (fs/path dir "content" "index.md"))}))
        (is (empty? @changed) "the abandoned watcher is inert")
        (finally (fs/delete-tree dir))))))

(deftest the-probe-asks-for-a-coalescing-window-inside-its-own
  (testing "the pod's debounce defaults to two seconds — measured, write-anchored
            — so without an explicit :delay-ms the probe is racing a delay it
            never asked for. The property that matters is not the number but
            the ordering: the coalescing window must fit strictly inside the
            probe budget, or a healthy watcher gets written off."
    (let [dir  (watch-site)
          opts (atom nil)]
      (try
        (dev/probe-watch! (cfg-for dir) (fn [_] nil)
                          {:watch   (fn [_p _cb o] (reset! opts o) {:id 1})
                           :unwatch (fn [w] w)
                           :timeout-ms 400})
        (is (some? (:delay-ms @opts)) "the option is passed at all")
        (is (< (:delay-ms @opts) 400)
            (str ":delay-ms " (:delay-ms @opts) " must fit inside the probe budget"))
        (is (true? (:recursive @opts)))
        (finally (fs/delete-tree dir))))))

(deftest the-probe-budget-is-shared-not-per-stage
  (testing "registration and delivery used to get a full timeout each, so the
            advertised window was half of a worst case twice as long"
    (let [dir (watch-site)
          budget 600]
      (try
        (let [t0 (System/currentTimeMillis)
              ;; slow registration, then a watcher that never delivers
              ok (dev/probe-watch! (cfg-for dir) (fn [_] nil)
                                   {:watch   (fn [_p _cb _o] (Thread/sleep 250) {:id 1})
                                    :unwatch (fn [w] w)
                                    :timeout-ms budget})
              elapsed (- (System/currentTimeMillis) t0)]
          (is (false? ok))
          (is (< elapsed (+ budget 400))
              (str "took " elapsed " ms against a " budget " ms budget")))
        (finally (fs/delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; CSS hot-swap with fingerprinted hrefs (D-P4-7)

(deftest css-hot-swap-keeps-the-fingerprint
  (testing "the reload script swaps a stylesheet href that already carries
            ?v=<fingerprint>: URLSearchParams.set adds `t` beside `v`, and
            a second swap replaces `t` rather than appending another"
    (let [js (second (re-find #"(?s)<script>(.*)</script>" dev/reload-script))]
      (is (str/includes? js "searchParams.set('t'"))
      (if-let [node (fs/which "node")]
        (let [harness (str "var links=[{href:'http://localhost:1888/clogem/css/theme.css?v=8882e30e'},"
                           "{href:'http://localhost:1888/pagefind/pagefind-component-ui.css?v=1.5.2'}];"
                           "var document={querySelectorAll:function(){return links;}};"
                           "var handler;function EventSource(){var s=this;setTimeout(function(){"
                           "s.onmessage({data:'css'});s.onmessage({data:'css'});"
                           "console.log(JSON.stringify(links.map(function(l){return l.href;})));},0);}"
                           "var location={reload:function(){}};"
                           js)
              {:keys [out exit err]} (babashka.process/shell {:out :string :err :string :continue true}
                                                             (str node) "-e" harness)
              hrefs (when (zero? exit) (cheshire.core/parse-string (str/trim out)))]
          (is (zero? exit) err)
          (is (= 2 (count hrefs)) out)
          (doseq [h hrefs]
            (is (re-find #"\?v=[0-9a-f.]+&t=\d+$" h) h)))
        (println "css-hot-swap-keeps-the-fingerprint: node not found; checked the script text only")))))
