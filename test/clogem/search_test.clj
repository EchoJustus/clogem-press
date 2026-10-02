;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.search-test
  "Search (D-P3-8 … D-P3-11) and the self-hosted Tamil font (D-P3-12).

  Nothing here touches the network: downloads come from a local http-kit
  server serving a fake tarball, and the binary is a shell-script stand-in.
  The one exception is `the-real-pagefind-indexes-five-languages`, which
  runs the real binary when `CLOGEM_PAGEFIND` names one — CI always sets it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hk]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.i18n :as i18n]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private url-site {:site {:title "S" :url "https://s.example"}})

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(defn- variant [title]
  (str "---\ntitle: " title "\n---\n\n## " title "\n\n" title " body.\n"))

(def ^:private five-langs
  {"01.Guide/01.t.md"         article
   "01.Guide/01.t.zh-Hans.md" (variant "简单的标题")
   "01.Guide/01.t.zh-Hant.md" (variant "繁體標題")
   "01.Guide/01.t.ms.md"      (variant "Tajuk ringkas")
   "01.Guide/01.t.ta.md"      (variant "எளிய தலைப்பு")
   ;; Tamil only: the English catalogue lists it with a fallback marker
   "01.Guide/02.only.ta.md"   (str "---\ntitle: தனி\npermalink: /pages/o00001/\n---\n\nஉரை\n")
   "00.Catalogue/01.Guide.md"
   (str "---\ntitle: Guide catalogue\npermalink: /pages/c00001/\n"
        "pageComponent:\n  name: Catalogue\n  data:\n    path: 01.Guide\n---\n\nbody\n")})

(defn- with-site
  "Write {rel-path → content} under a temp site (plus a site.edn) and call
  (f dir out) with `search/*env*` bound to `env` — {} by default, so CI's
  CLOGEM_PAGEFIND never reaches a test that means to exercise another path."
  [files site-edn f & {:keys [env] :or {env {}}}]
  (let [dir (fs/create-temp-dir {:prefix "clogem-search"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (binding [diag/*sink* (atom [])
                search/*env* env]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- build!
  [dir out & [extra]]
  (with-out-str (cli/build (merge {:site-dir (str dir) :out (str out) :no-write true} extra))))

(defn- build-error
  "The ExceptionInfo `build` raises, or nil."
  [dir out & [extra]]
  (try (build! dir out extra) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- html [out & parts] (slurp (fs/file (apply fs/path out (concat parts ["index.html"])))))

(defn- pagefind-site
  [bin & [more]]
  (merge-with merge url-site {:search {:provider :pagefind} :tools {:pagefind {:path bin}}} more))

(defn- cfg-of
  "A loaded config for `dir` (diagnostics discarded)."
  [dir & [overrides]]
  (first (diag/collecting (config/load-config (str dir) nil overrides))))

;; ---------------------------------------------------------------------------
;; Platform, pins, cache location (D-P3-8)

(deftest platform-maps-every-release-asset
  (is (= "x86_64-unknown-linux-musl"  (search/platform "Linux" "amd64")))
  (is (= "aarch64-unknown-linux-musl" (search/platform "Linux" "aarch64")))
  (is (= "x86_64-apple-darwin"        (search/platform "Mac OS X" "x86_64")))
  (is (= "aarch64-apple-darwin"       (search/platform "Mac OS X" "aarch64")))
  (is (= "x86_64-pc-windows-msvc"     (search/platform "Windows 11" "amd64")))
  (is (= "aarch64-pc-windows-msvc"    (search/platform "Windows 11" "aarch64")) "Windows on ARM has an asset too")
  (is (nil? (search/platform "Linux" "riscv64")) "no release asset → nil, and the build says so")
  (is (= "pagefind_extended.exe" (search/binary-name "x86_64-pc-windows-msvc")))
  (testing "every platform has a pinned hash for the default version"
    (let [v (get-in config/defaults [:tools :pagefind :version])]
      (is (= "1.5.2" v))
      (is (= 6 (count (get search/known-sha256 v))))
      (is (= "4fd44a27ecc7ac517e1293e8d9e31073d1cf16d457f3126c31374c69e836df43"
             (get-in search/known-sha256 [v "aarch64-pc-windows-msvc"]))
          "verified against the release's pagefind_extended-v1.5.2-aarch64-pc-windows-msvc.tar.gz.sha256")
      (is (every? #(re-matches #"[0-9a-f]{64}" %) (vals (get search/known-sha256 v)))))))

(deftest the-asset-url-is-the-extended-release-tarball
  (is (= "https://github.com/Pagefind/pagefind/releases/download/v1.5.2/pagefind_extended-v1.5.2-x86_64-unknown-linux-musl.tar.gz"
         (search/asset-url {:tools {:pagefind {:version "1.5.2"}}} "x86_64-unknown-linux-musl"))
      "the EXTENDED binary: the standard one does not segment Chinese"))

(deftest the-cache-lives-outside-the-site
  (with-site {} {} (fn [dir _]
    (let [cfg (cfg-of dir)]
      (testing "XDG_CACHE_HOME, then ~/.cache"
        (binding [search/*env* {"XDG_CACHE_HOME" "/x/cache"}]
          (is (= (str (fs/path "/x/cache" "clogem-press" "tools" "pagefind" "1.5.2" "p"))
                 (str (search/tool-dir cfg "p")))))
        (binding [search/*env* {}]
          (is (= (str (fs/path (System/getProperty "user.home") ".cache" "clogem-press" "tools"))
                 (str (search/cache-root cfg))))))
      (testing ":tools :cache-dir is relative to the site; CLOGEM_TOOLS_DIR beats it"
        (let [cfg (assoc-in cfg [:tools :cache-dir] ".tools")]
          (is (= (str (fs/path dir ".tools")) (str (search/cache-root cfg))))
          (binding [search/*env* {"CLOGEM_TOOLS_DIR" "/env/tools"}]
            (is (= "/env/tools" (str (search/cache-root cfg)))))))))))

(deftest search-config-is-validated
  (testing "an unknown provider, font option or a single-string sha256 is a config error"
    (doseq [[edn re] [[{:search {:provider :algolia}} #":search :provider is :algolia"]
                      [{:theme {:fonts {:tamil :google}}} #":theme :fonts :tamil is :google"]
                      [{:tools {:pagefind {:sha256 "abc"}}} #":tools :pagefind :sha256 must map each platform"]
                      [{:tools {:pagefind {:version "latest"}}} #":tools :pagefind :version is \"latest\""]]]
      (with-site {} edn (fn [dir _]
        (let [[cfg ds] (diag/collecting (config/load-config (str dir)))]
          (is (some #(re-find re (:message %)) (diag/errors ds)) (pr-str edn))
          (is (= :none (get-in cfg [:search :provider])) "repaired, so doctor can keep going")
          (is (= :system (get-in cfg [:theme :fonts :tamil])))))))
    (with-site {} {} (fn [dir _]
      (let [[cfg ds] (diag/collecting (config/load-config (str dir)))]
        (is (empty? (diag/errors ds)))
        (is (= :none (get-in cfg [:search :provider])) ":none stays the default")
        (is (= :system (get-in cfg [:theme :fonts :tamil]))))))))

;; ---------------------------------------------------------------------------
;; Fetch, verify, cache (D-P3-8) — over a local server, never the network

(defn- with-server
  "Serve `tarball` at /<anything> on a random local port; call (f url-template
  hits) where `hits` counts requests."
  [tarball f]
  (let [hits   (atom 0)
        server (hk/run-server (fn [_]
                                (swap! hits inc)
                                {:status 200 :body (fs/file tarball)})
                              {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (hk/server-port server) "/v{{version}}/{{platform}}.tar.gz") hits)
         (finally (hk/server-stop! server)))))

(deftest a-download-is-verified-unpacked-and-cached
  (with-site {} {} (fn [dir _]
    (let [tgz  (fake/fake-tarball! (fs/path dir "tgz"))
          sha  (search/sha256-hex tgz)
          plat (search/platform)]
      (with-server tgz (fn [url hits]
        (let [cfg (cfg-of dir {:tools {:cache-dir "cache" :pagefind {:url url :sha256 {plat sha}}}})
              bin (search/ensure-binary! cfg)]
          (is (= (str (fs/path dir "cache" "pagefind" "1.5.2" plat "pagefind_extended")) bin))
          (is (fs/executable? bin))
          (is (= 1 @hits))
          (is (= #{"pagefind_extended" search/stamp-name}
                 (set (map (comp str fs/file-name) (fs/list-dir (fs/parent bin)))))
              "only the binary and its stamp are kept: no tarball, no staging directory")
          (is (= #{plat (str plat ".lock")}
                 (set (map (comp str fs/file-name) (fs/list-dir (fs/parent (fs/parent bin))))))
              "nothing left beside it but the (empty) lock file")
          (testing "a second build uses the cache"
            (is (= bin (search/ensure-binary! cfg)))
            (is (= 1 @hits))))))))))

(deftest a-hash-mismatch-is-an-error-and-the-file-is-deleted
  (with-site {} {} (fn [dir _]
    (let [tgz  (fake/fake-tarball! (fs/path dir "tgz"))
          got  (search/sha256-hex tgz)
          want (apply str (repeat 64 "0"))
          plat (search/platform)]
      (with-server tgz (fn [url _]
        (let [cfg (cfg-of dir {:tools {:cache-dir "cache" :pagefind {:url url :sha256 {plat want}}}})
              e   (try (search/ensure-binary! cfg) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (str/includes? (ex-message e) (str "expected " want ", got " got)) (ex-message e))
          (is (= [(str plat ".lock")]
                 (map (comp str fs/file-name) (fs/list-dir (fs/path dir "cache" "pagefind" "1.5.2"))))
              "the bad download is deleted and no binary is installed"))))))))

(deftest no-network-and-no-cache-names-the-path-and-the-way-out
  (with-site {} {} (fn [dir _]
    (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))   ; closed again: refused
          cfg  (cfg-of dir {:tools {:cache-dir "cache"
                                    :pagefind {:url (str "http://127.0.0.1:" port "/x.tar.gz")}}})
          e    (try (search/ensure-binary! cfg) nil (catch clojure.lang.ExceptionInfo e e))
          msg  (ex-message e)]
      (is (= 1 (:babashka/exit (ex-data e))))
      (is (str/includes? msg (str "No cached binary at " (search/tool-dir cfg (search/platform)))) msg)
      (is (str/includes? msg "--no-search") msg)
      (is (str/includes? msg ":search {:provider :none}") msg)))))

(deftest an-unpinned-version-is-refused
  (with-site {} {} (fn [dir _]
    (let [cfg (cfg-of dir {:tools {:cache-dir "cache" :pagefind {:version "9.9.9" :url "http://127.0.0.1:9/x"}}})
          e   (try (search/ensure-binary! cfg) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"no sha256 is pinned for Pagefind 9\.9\.9" (ex-message e)))))))

(deftest a-preinstalled-binary-skips-download-and-verification
  (with-site {} {} (fn [dir _]
    (let [bin (fake/fake-pagefind! (fs/path dir "bin"))
          cfg (cfg-of dir {:tools {:cache-dir "cache" :pagefind {:url "http://127.0.0.1:9/never"}}})]
      (testing ":tools :pagefind :path"
        (is (= bin (search/ensure-binary! (assoc-in cfg [:tools :pagefind :path] bin)))))
      (testing "CLOGEM_PAGEFIND, which wins over :path"
        (binding [search/*env* {"CLOGEM_PAGEFIND" bin}]
          (is (= bin (search/ensure-binary! (assoc-in cfg [:tools :pagefind :path] "nope")))))
        (binding [search/*env* {"CLOGEM_PAGEFIND" (str bin "-missing")}]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not exist"
                                (search/ensure-binary! cfg)))))
      (is (not (fs/exists? (fs/path dir "cache"))) "nothing was fetched")))))

;; ---------------------------------------------------------------------------
;; The cache entry is verified on every use (D-P3-8, §11.2 item 46)

(defn- cfg-for
  [dir url sha & [more]]
  (cfg-of dir (merge-with merge {:tools {:cache-dir "cache" :pagefind {:url url :sha256 {(search/platform) sha}}}} more)))

(deftest a-shared-cache-never-runs-another-pins-binary
  (testing "site A (custom :url and :sha256) and site B (the default pins)
            share one cache; B used to run A's binary"
    (with-site {} {} (fn [dir _]
      (let [plat  (search/platform)
            tgz-a (fake/fake-tarball! (fs/path dir "a") :output "binary A")
            tgz-b (fake/fake-tarball! (fs/path dir "b") :output "binary B")
            sha-a (search/sha256-hex tgz-a)
            sha-b (search/sha256-hex tgz-b)]
        (with-server tgz-a (fn [url-a hits-a]
          (with-server tgz-b (fn [url-b hits-b]
            (let [cfg-a (cfg-for dir url-a sha-a)
                  ;; B: no :sha256 of its own — the built-in table is its pin
                  cfg-b (cfg-of dir {:tools {:cache-dir "cache" :pagefind {:url url-b}}})
                  ;; the stand-in writes its bundle under --site: give it one
                  ;; (without it, it tries `mkdir /pagefind` — root-only)
                  ran   #(-> (babashka.process/shell {:out :string} % "--site" (str (fs/path dir "site-out")))
                             :out str/split-lines first str/trim)]
              (with-redefs [search/known-sha256 {"1.5.2" {plat sha-b}}]
                (is (= "binary A" (ran (search/ensure-binary! cfg-a))))
                (is (= "binary B" (ran (search/ensure-binary! cfg-b)))
                    "B's pin is not A's archive hash: the entry is replaced, not trusted")
                (is (= [1 1] [@hits-a @hits-b]))
                (is (= "binary A" (ran (search/ensure-binary! cfg-a))) "and back again")
                (is (= 2 @hits-a))))))))))))

(deftest a-tampered-or-unstamped-binary-is-fetched-again
  (with-site {} {} (fn [dir _]
    (let [tgz (fake/fake-tarball! (fs/path dir "tgz"))
          sha (search/sha256-hex tgz)]
      (with-server tgz (fn [url hits]
        (let [cfg  (cfg-for dir url sha)
              bin  (search/ensure-binary! cfg)
              good (search/sha256-hex bin)
              stamp (fs/path (fs/parent bin) search/stamp-name)]
          (is (= {:archive-sha256 sha :binary-sha256 good}
                 (clojure.edn/read-string (slurp (fs/file stamp))))
              "the stamp records the verified archive and the extracted binary")
          (testing "one byte appended to the cached binary"
            (spit (fs/file bin) "x" :append true)
            (is (= bin (search/ensure-binary! cfg)))
            (is (= 2 @hits) "detected, deleted and fetched again")
            (is (= good (search/sha256-hex bin))))
          (testing "no stamp: an entry from before the stamp, or not ours"
            (fs/delete stamp)
            (is (= bin (search/ensure-binary! cfg)))
            (is (= 3 @hits))
            (is (fs/exists? stamp)))
          (testing "a stamp for another archive"
            (spit (fs/file stamp) (pr-str {:archive-sha256 (apply str (repeat 64 "1")) :binary-sha256 good}))
            (search/ensure-binary! cfg)
            (is (= 4 @hits)))
          (testing "an intact entry is used as it is"
            (search/ensure-binary! cfg)
            (is (= 4 @hits))))))))))

(defn- bb-exe
  "The babashka running these tests, so a subprocess runs the same version."
  []
  (if (fs/exists? "/proc/self/exe")
    (str (fs/real-path "/proc/self/exe"))
    (str (fs/which "bb"))))

(defn- with-slow-server
  "`with-server`, answering each request after `ms` — widening the window
  in which concurrent cold-cache fetches used to collide."
  [tarball ms f]
  (let [hits   (atom 0)
        server (hk/run-server (fn [_]
                                (swap! hits inc)
                                (Thread/sleep (long ms))
                                {:status 200 :body (fs/file tarball)})
                              {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (hk/server-port server) "/v{{version}}/{{platform}}.tar.gz") hits)
         (finally (hk/server-stop! server)))))

(deftest concurrent-cold-cache-fetches-all-succeed
  (testing "4 builds at once on a cold cache, repeated: 2 of 6 rounds used to
            fail (FileAlreadyExistsException, DirectoryNotEmptyException, or a
            sibling's delete-tree removing a fresh install)"
    (with-site {} {} (fn [dir _]
      (let [tgz (fake/fake-tarball! (fs/path dir "tgz"))
            sha (search/sha256-hex tgz)]
        (with-slow-server tgz 100 (fn [url hits]
          (testing "threads of one process"
            (doseq [round (range 6)]
              (let [cfg (cfg-of dir {:tools {:cache-dir (str "cache-t" round)
                                             :pagefind {:url url :sha256 {(search/platform) sha}}}})
                    results (->> (range 4)
                                 (mapv (fn [_] (future (try (binding [*err* (java.io.StringWriter.)]
                                                              (search/ensure-binary! cfg))
                                                            (catch Throwable e e)))))
                                 (mapv deref))]
                (is (every? string? results) (pr-str round results))
                (is (apply = results))
                (is (fs/executable? (first results))))))
          (is (= 6 @hits) "one fetch per cold cache: the others waited and used it")
          (testing "separate processes (bb fetch-tool)"
            (reset! hits 0)
            (doseq [round (range 3)]
              (let [site (fs/path dir (str "proc-site" round))
                    _    (fs/create-dirs site)
                    _    (spit (fs/file site "site.edn")
                               (pr-str {:tools {:cache-dir "../proc-cache"
                                                :pagefind {:url url :sha256 {(search/platform) sha}}}}))
                    _    (fs/delete-tree (fs/path dir "proc-cache"))
                    procs (mapv (fn [_]
                                  (babashka.process/process
                                   {:out :string :err :string
                                    :extra-env {"CLOGEM_PAGEFIND" "" "CLOGEM_TOOLS_DIR" ""}}
                                   (bb-exe) "--config" (str (fs/absolutize "bb.edn"))
                                   "fetch-tool" "--site-dir" (str site)))
                                (range 4))
                    done (mapv deref procs)]
                (is (every? #(zero? (:exit %)) done) (pr-str (map :err done)))
                (is (apply = (map #(last (str/split-lines (str/trim (:out %)))) done)))))
            (is (= 3 @hits))))))))))

;; ---------------------------------------------------------------------------
;; Network failures are reported, never hung on or dumped (D-P3-8)

(defn- with-raw-server
  "A bare TCP server: (handle socket) per connection, on a thread; call
  (f port). For responses http-kit will not send: a body cut short, a
  server that never answers, a proxy."
  [handle f]
  (let [ss (java.net.ServerSocket. 0)
        conns (atom [])]
    (future
      (try
        (loop []
          (let [s (.accept ss)]
            (swap! conns conj s)
            (future (try (handle s) (catch Exception _ nil)))
            (recur)))
        (catch Exception _ nil)))
    (try (f (.getLocalPort ss))
         (finally (.close ss)
                  (doseq [^java.net.Socket c @conns] (.close c))))))

(defn- read-head
  "The request line and headers from `s`, as [line {lower-name value}]."
  [^java.net.Socket s]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "ISO-8859-1"))
        line (.readLine r)
        headers (loop [h {}]
                  (let [l (.readLine r)]
                    (if (str/blank? l)
                      h
                      (let [[k v] (str/split l #":\s*" 2)]
                        (recur (assoc h (str/lower-case k) v))))))]
    [line headers]))

(defn- respond!
  [^java.net.Socket s head ^bytes body]
  (let [o (.getOutputStream s)]
    (.write o (.getBytes (str head "\r\n\r\n") "ISO-8859-1"))
    (when body (.write o body))
    (.flush o)))

(defn- fetch-error
  [cfg]
  (try (binding [*err* (java.io.StringWriter.)] (search/ensure-binary! cfg) nil)
       (catch clojure.lang.ExceptionInfo e e)))

(deftest a-connection-dropped-mid-download-is-a-clean-error
  (with-site {} {} (fn [dir _]
    (let [tgz (fs/read-all-bytes (fake/fake-tarball! (fs/path dir "tgz")))]
      (with-raw-server
        (fn [s]
          (read-head s)
          (respond! s (str "HTTP/1.1 200 OK\r\nContent-Length: " (* 10 (count tgz)))
                    (byte-array (take 100 tgz)))
          (.close s))
        (fn [port]
          (let [e (fetch-error (cfg-for dir (str "http://127.0.0.1:" port "/x.tar.gz") (apply str (repeat 64 "0"))))]
            (is (some? e) "an ExceptionInfo — it used to be a raw `IOException: closed`")
            (is (= 1 (:babashka/exit (ex-data e))))
            (is (re-find #"failed mid-download" (ex-message e)) (ex-message e))
            (is (str/includes? (ex-message e) "--no-search") "with the offline hint"))))))))

(deftest a-server-that-never-answers-times-out
  (with-site {} {} (fn [dir _]
    (testing "no response at all: the request timeout"
      (with-raw-server
        ;; closes after 20 s, so code without the timeout fails slowly
        ;; rather than hanging the suite
        (fn [s] (read-head s) (Thread/sleep 20000) (.close s))
        (fn [port]
          (binding [search/*timeouts* {:connect-ms 2000 :request-ms 500 :idle-ms 500}]
            (let [t0 (System/currentTimeMillis)
                  e  (fetch-error (cfg-for dir (str "http://127.0.0.1:" port "/x.tar.gz") (apply str (repeat 64 "0"))))]
              (is (re-find #"timed out" (str (ex-message e))) (ex-message e))
              (is (< (- (System/currentTimeMillis) t0) 10000)))))))
    (testing "headers, then a body that stalls: the idle timeout"
      (with-raw-server
        (fn [s] (read-head s)
          (respond! s "HTTP/1.1 200 OK\r\nContent-Length: 100000" (byte-array 10))
          (Thread/sleep 20000)
          (.close s))
        (fn [port]
          (binding [search/*timeouts* {:connect-ms 2000 :request-ms 2000 :idle-ms 500}]
            (let [t0 (System/currentTimeMillis)
                  e  (fetch-error (cfg-for dir (str "http://127.0.0.1:" port "/x.tar.gz") (apply str (repeat 64 "0"))))]
              (is (re-find #"no data for" (str (ex-message e))) (ex-message e))
              (is (< (- (System/currentTimeMillis) t0) 10000)))))))
    (testing "the defaults: 30 s to connect, 5 minutes for the rest"
      (is (= {:connect-ms 30000 :request-ms 300000 :idle-ms 300000} search/*timeouts*))))))

(deftest no-proxy-bypasses-the-proxy
  (with-site {} {} (fn [dir _]
    (let [tgz  (fake/fake-tarball! (fs/path dir "tgz"))
          sha  (search/sha256-hex tgz)
          dead (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))
          proxy (str "http://127.0.0.1:" dead)]
      (with-server tgz (fn [url hits]
        (let [cfg (cfg-for dir url sha)]
          (testing "the proxy is used: a dead one fails the fetch"
            (binding [search/*env* {"HTTP_PROXY" proxy}]
              (is (some? (fetch-error cfg)))
              (is (zero? @hits))))
          (doseq [np ["127.0.0.1" "localhost,127.0.0.1" " 127.0.0.1 , x" "*" "127.0.0.1:1,127.0.0.1"]]
            (fs/delete-tree (fs/path dir "cache"))
            (binding [search/*env* {"HTTP_PROXY" proxy "HTTPS_PROXY" proxy "NO_PROXY" np}]
              (is (string? (search/ensure-binary! cfg)) np)))
          (fs/delete-tree (fs/path dir "cache"))
          (binding [search/*env* {"https_proxy" proxy "no_proxy" "127.0.0.1"}]
            (is (string? (search/ensure-binary! cfg)) "lower-case spellings too"))
          (fs/delete-tree (fs/path dir "cache"))
          (binding [search/*env* {"HTTP_PROXY" proxy "NO_PROXY" "127.0.0.1:1"}]
            (is (some? (fetch-error cfg)) "an entry with another port does not match"))))))
    (let [np? #'search/no-proxy?]
      (binding [search/*env* {"NO_PROXY" ".example.com,github.com,10.0.0.1"}]
        (is (np? "a.example.com" 443))
        (is (np? "example.com" 443))
        (is (np? "objects.github.com" 443))
        (is (np? "GITHUB.COM" 443))
        (is (not (np? "notgithub.com" 443)))
        (is (np? "10.0.0.1" 80))
        (is (not (np? "10.0.0.10" 80))))))))

(deftest proxy-credentials-come-from-the-proxy-uri
  (with-site {} {} (fn [dir _]
    (let [tgz   (fake/fake-tarball! (fs/path dir "tgz"))
          body  (fs/read-all-bytes tgz)
          sha   (search/sha256-hex tgz)
          seen  (atom [])
          want  (str "Basic " (.encodeToString (java.util.Base64/getEncoder) (.getBytes "u ser:p@ss:w" "UTF-8")))]
      (with-raw-server
        (fn [s]
          (let [[line h] (read-head s)]
            (swap! seen conj [line (get h "proxy-authorization")])
            (if (= want (get h "proxy-authorization"))
              (respond! s (str "HTTP/1.1 200 OK\r\nContent-Length: " (count body) "\r\nConnection: close") body)
              (respond! s "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"p\"\r\nContent-Length: 0\r\nConnection: close" nil))
            (.close s)))
        (fn [port]
          (let [cfg (cfg-for dir "http://downloads.example/v{{version}}/{{platform}}.tar.gz" sha)]
            (testing "user-info, percent-decoded, reaches the proxy"
              (binding [search/*env* {"HTTP_PROXY" (str "http://u%20ser:p%40ss:w@127.0.0.1:" port)}]
                (is (string? (search/ensure-binary! cfg)) (pr-str @seen)))
              (is (str/starts-with? (ffirst @seen) "GET http://downloads.example/v1.5.2/") "the proxy got an absolute-form request")
              (is (= want (second (first @seen)))))
            (testing "without credentials the 407 is a clean error"
              (fs/delete-tree (fs/path dir "cache"))
              (binding [search/*env* {"HTTP_PROXY" (str "http://127.0.0.1:" port)}]
                (let [e (fetch-error cfg)]
                  (is (re-find #"HTTP 407" (str (ex-message e))) (ex-message e)))))))))))

;; ---------------------------------------------------------------------------
;; Unpacking (D-P3-8)

(deftest stale-downloads-are-swept-at-the-next-fetch
  (with-site {} {} (fn [dir _]
    (let [tgz  (fake/fake-tarball! (fs/path dir "tgz"))
          plat (search/platform)
          vdir (fs/path dir "cache" "pagefind" "1.5.2")]
      (fs/create-dirs (fs/path vdir (str plat ".partial-123") "sub"))
      (spit (fs/file vdir (str plat ".download-456.tar.gz")) "killed half-way")
      (spit (fs/file vdir "unrelated.txt") "kept")
      (with-server tgz (fn [url _]
        (search/ensure-binary! (cfg-for dir url (search/sha256-hex tgz)))
        (is (= #{plat (str plat ".lock") "unrelated.txt"}
               (set (map (comp str fs/file-name) (fs/list-dir vdir)))))))))))

(deftest a-symlinked-binary-is-refused
  (with-site {} {} (fn [dir _]
    (let [target (fs/path dir "victim")
          _      (spit (fs/file target) "not a binary")
          _      (fs/set-posix-file-permissions target "rw-------")
          tgz    (fake/symlink-tarball! (fs/path dir "tgz") (str target))]
      (with-server tgz (fn [url _]
        (let [e (fetch-error (cfg-for dir url (search/sha256-hex tgz)))]
          (is (re-find #"is a symbolic link" (str (ex-message e))) (ex-message e))
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions target)))
              "the link's target was never chmod-ed")
          (is (not (fs/exists? (search/tool-dir (cfg-for dir url "") (search/platform)))))))))))))

(deftest tar-is-fed-on-stdin-and-a-missing-tar-is-reported
  (with-site {} {} (fn [dir _]
    (let [tgz (fake/fake-tarball! (fs/path dir "tgz"))
          sha (search/sha256-hex tgz)]
      (with-server tgz (fn [url _]
        (testing "a path with a colon, which `tar -xzf <path>` reads as host:path
                  — the shape of every Windows drive letter"
          (let [cfg (cfg-of dir {:tools {:cache-dir "C:cache" :pagefind {:url url :sha256 {(search/platform) sha}}}})]
            (is (fs/executable? (search/ensure-binary! cfg)))))
        (testing "no tar on PATH"
          (binding [search/*tar* "clogem-no-such-tar"]
            (let [e (fetch-error (cfg-for dir url sha))]
              (is (= 1 (:babashka/exit (ex-data e))))
              (is (re-find #"could not run `clogem-no-such-tar` to unpack Pagefind" (str (ex-message e)))
                  (ex-message e))
              (is (empty? (filter #(str/includes? (str (fs/file-name %)) ".partial-")
                                  (fs/list-dir (fs/path dir "cache" "pagefind" "1.5.2"))))
                  "no staging directory is left behind"))))))))))

(deftest clogem-pagefind-is-relative-to-the-working-directory
  (with-site {} {} (fn [dir _]
    (let [bin (fake/fake-pagefind! (fs/path dir "bin"))
          rel (str (fs/relativize (fs/cwd) bin))
          cfg (cfg-of dir)]
      (is (not (fs/absolute? rel)))
      (binding [search/*env* {"CLOGEM_PAGEFIND" rel}]
        (is (= bin (search/ensure-binary! cfg)) "resolved against the cwd, not the site"))
      (binding [search/*env* {"CLOGEM_PAGEFIND" "bin/pagefind_extended"}]
        (let [e (fetch-error cfg)]
          (is (str/includes? (ex-message e) (str "checked " (fs/path (fs/cwd) "bin" "pagefind_extended")))
              (str "the absolute path checked is printed: " (ex-message e)))
          (is (re-find #"does not exist" (ex-message e)))))
      (binding [search/*env* {"CLOGEM_PAGEFIND" (str (fs/path dir "bin"))}]
        (let [e (fetch-error cfg)]
          (is (re-find #"which is a directory" (ex-message e)) (ex-message e))))
      (testing ":tools :pagefind :path stays relative to the site"
        (is (= bin (search/ensure-binary! (assoc-in cfg [:tools :pagefind :path] "bin/pagefind_extended"))))))))))

;; ---------------------------------------------------------------------------
;; When it runs (D-P3-8)

(deftest build-runs-pagefind-last-over-dist
  (with-site {"01.Guide/01.t.md" article} (pagefind-site "BIN")
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
        (let [printed (build! dir out)]
          (is (= ["--site" (str (fs/absolutize out)) "--output-subdir" "pagefind"]
                 (fake/args-of bin)))
          (is (str/includes? printed "search index →") printed)
          (is (str/includes? printed "(2 languages, 3 pages)") "read back from pagefind-entry.json"))
        (testing "--no-search skips it"
          (fs/delete-if-exists (fs/path dir "bin" "args.txt"))
          (build! dir out {:no-search true})
          (is (nil? (fake/args-of bin))))))))

(deftest provider-none-runs-nothing
  (with-site {"01.Guide/01.t.md" article} url-site
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (assoc-in url-site [:tools :pagefind :path] bin)))
        (build! dir out)
        (is (nil? (fake/args-of bin)))
        (is (not (fs/exists? (fs/path out "pagefind"))))))))

(deftest a-failing-pagefind-fails-the-build-after-dist-is-written
  (with-site {"01.Guide/01.t.md" article} {}
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin") :exit 3)]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
        (let [e (build-error dir out)]
          (is (some? e))
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (re-find #"Pagefind exited 3" (ex-message e)))
          (is (str/includes? (ex-message e) "boom") "Pagefind's own output is in the message")
          (is (fs/exists? (fs/path out "index.html"))
              "the known limitation (§11.2): dist/ is already written"))))))

(deftest doctor-does-not-run-search
  (with-site {"01.Guide/01.t.md" article} {}
    (fn [dir _]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
        (with-out-str (cli/doctor {:site-dir (str dir)}))
        (is (nil? (fake/args-of bin)))))))

(deftest dev-takes-no-search-and-hands-it-to-build
  (testing "bb dev rebuilds through cli/build with its own opts, so --no-search
            reaches every rebuild"
    (is (contains? (get-in (meta #'cli/dev) [:org.babashka/cli :spec]) :no-search))
    (is (contains? (get-in (meta #'cli/build) [:org.babashka/cli :spec]) :no-search))
    (is (re-find #"clogem\.cli/build" (slurp "src/clogem/dev.clj"))
        "dev's rebuild is cli/build, which honours :no-search (build-runs-pagefind-last-over-dist)")))

(deftest fetch-tool-prints-the-binary
  (with-site {} {} (fn [dir _]
    (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
      (spit (fs/file dir "site.edn") (pr-str {:tools {:pagefind {:path bin}}}))
      (is (= (str bin "\n") (with-out-str (cli/fetch-tool {:site-dir (str dir)}))))))))

;; ---------------------------------------------------------------------------
;; A reused out dir (§11.2 item 46)

(deftest a-rebuild-into-the-same-out-dir-drops-deleted-pages
  (with-site five-langs {}
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))
            outside (fs/create-temp-dir {:prefix "clogem-outside"})]
        (try
          (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
          (build! dir out)
          (is (fs/exists? (fs/path out "zh-Hant" "pages" "t00001" "index.html")))
          ;; what a user or a CI step put in out/, and what lies beyond it
          (spit (fs/file out "CNAME") "docs.example")
          (spit (fs/file out "google123.html") "google-site-verification")
          (spit (fs/file outside "page.html") "outside")
          (fs/create-sym-link (fs/path out "linked") outside)
          (fs/create-sym-link (fs/path out "link.html") (fs/path outside "page.html"))
          (fs/create-dirs (fs/path out "pagefind" "fragment"))
          (spit (fs/file out "pagefind" "fragment" "stale.pf_fragment") "from an earlier build")
          (fs/delete (fs/path dir "content" "01.Guide" "01.t.zh-Hant.md"))
          (let [printed (build! dir out)]
            (is (not (fs/exists? (fs/path out "zh-Hant" "pages" "t00001" "index.html")))
                "the deleted variant's page is gone, so neither serve nor Pagefind sees it")
            (is (not (fs/exists? (fs/path out "zh-Hant" "pages" "t00001"))) "and its emptied directory")
            (is (re-find #"removed \d+ stale pages? from" printed) printed))
          (is (fs/exists? (fs/path out "zh-Hans" "pages" "t00001" "index.html")))
          (is (fs/exists? (fs/path out "index.html")))
          (is (= "docs.example" (slurp (fs/file out "CNAME"))) "non-HTML files are never deleted")
          (is (not (fs/exists? (fs/path out "google123.html")))
              "an .html file the build did not write is (documented: keep such files in assets/)")
          (is (= "outside" (slurp (fs/file outside "page.html"))) "nothing outside out/ is touched")
          (is (fs/sym-link? (fs/path out "linked")) "a symlinked directory is not descended")
          (is (fs/sym-link? (fs/path out "link.html")) "nor is a symlinked .html file deleted")
          (is (not (fs/exists? (fs/path out "pagefind" "fragment" "stale.pf_fragment")))
              "out/pagefind is replaced whole before Pagefind runs")
          (testing "an .html file from the site's own assets/ is written by the build, so kept"
            (fs/create-dirs (fs/path dir "assets"))
            (spit (fs/file dir "assets" "demo.html") "<p>asset</p>")
            (build! dir out)
            (build! dir out)
            (is (fs/exists? (fs/path out "assets" "demo.html"))))
          (finally (fs/delete-tree outside)))))))

(defn- dangling-links
  "Every root-relative href/src in `out`'s HTML that names no file — the CI
  link resolver, for base `/`."
  [out]
  (for [f (fs/glob out "**.html")
        [_ _ href] (re-seq #"(href|src)=\"(/[^\"]*)\"" (slurp (fs/file f)))
        :let [rel (java.net.URLDecoder/decode (str/replace (first (str/split href #"[?#]")) #"^/" "") "UTF-8")]
        :when (not (or (fs/regular-file? (fs/path out rel))
                       (fs/regular-file? (fs/path out rel "index.html"))))]
    [(str (fs/relativize out f)) href]))

(deftest no-search-builds-pages-without-search
  (testing "--no-search on a :pagefind site: no search box, no Pagefind CSS or
            JS — every page used to link a bundle the build never wrote"
    (with-site five-langs {}
      (fn [dir out]
        (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
          (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
          (build! dir out {:no-search true})
          (is (nil? (fake/args-of bin)) "Pagefind did not run")
          (is (seq (fs/glob out "**.html")))
          (doseq [f (fs/glob out "**.html")]
            (is (not (re-find #"(?i)pagefind" (slurp (fs/file f)))) (str f)))
          (is (not (fs/exists? (fs/path out "clogem" "js" "search.js"))))
          (is (empty? (dangling-links out)) (pr-str (take 5 (dangling-links out))))
          (testing "and the same out dir indexed again without the flag"
            (build! dir out)
            (is (str/includes? (html out) "pagefind-component-ui.js"))))))))

(deftest dev-hands-no-search-to-the-render-config
  (with-site five-langs {}
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
        (let [[cfg _] (#'cli/load-cfg* {:site-dir (str dir) :out (str out) :no-search true})]
          (is (= :none (get-in cfg [:search :provider])) "the flag reaches what render reads"))
        (let [[cfg _] (#'cli/load-cfg* {:site-dir (str dir) :out (str out)})]
          (is (= :pagefind (get-in cfg [:search :provider]))))))))

;; ---------------------------------------------------------------------------
;; Markup (D-P3-9, D-P3-10)

(defn- with-markup
  "Build the five-language fixture with the fake binary; call (f out)."
  [f & [more]]
  (with-site five-langs {}
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin more)))
        (build! dir out)
        (f out)))))

(deftest only-article-and-catalogue-bodies-are-indexed
  (with-markup
    (fn [out]
      (testing "article pages, in every language, and the catalogue page"
        (doseq [parts [["pages" "t00001"] ["zh-Hans" "pages" "t00001"] ["zh-Hant" "pages" "t00001"]
                       ["ms" "pages" "t00001"] ["ta" "pages" "t00001"] ["pages" "c00001"]]]
          (is (re-find #"<article class=\"clogem-article[^\"]*\" data-pagefind-body=\"\">" (apply html out parts))
              (pr-str parts))))
      (testing "homes, index and other pages carry none — so Pagefind skips them"
        (doseq [parts [[] ["zh-Hant"] ["categories"] ["tags"] ["archives"] ["ms" "categories"]]]
          (is (not (str/includes? (apply html out parts) "data-pagefind-body")) (pr-str parts))))
      (testing "chrome inside the body is ignored: heading anchors, the variant bar"
        (let [h (html out "pages" "t00001")]
          (is (re-find #"<a aria-hidden=\"true\" class=\"header-anchor\" data-pagefind-ignore=\"\" href=" h))
          (is (str/includes? h "<p class=\"clogem-variants\" data-pagefind-ignore=\"\">"))))
      (testing "and the fallback marker on a catalogue row"
        (is (re-find #"<span class=\"clogem-fallback\" data-pagefind-ignore=\"\""
                     (html out "pages" "c00001")))))))

(deftest every-page-gets-the-component-ui
  (with-markup
    (fn [out]
      (doseq [[parts lang] [[[] "en"] [["zh-Hans"] "zh-Hans"] [["zh-Hant"] "zh-TW"] [["ms"] "ms"]
                            [["ta"] "ta"] [["pages" "t00001"] "en"] [["zh-Hant" "pages" "t00001"] "zh-TW"]
                            [["tags"] "en"]]
              :let [h (apply html out parts)]]
        (testing (pr-str parts)
          (is (re-find (re-pattern (str "<body[^>]*><pagefind-config bundle-path=\"/pagefind/\"[^>]* lang=\"" lang "\""))
                       h)
              "<pagefind-config> is the FIRST element in <body> (Pagefind #1332)")
          (is (str/includes? h "<link href=\"/pagefind/pagefind-component-ui.css\" rel=\"stylesheet\" />"))
          (is (str/includes? h "<script src=\"/pagefind/pagefind-component-ui.js\" type=\"module\"></script>"))
          (is (re-find #"<header class=\"clogem-navbar\">.*<div class=\"clogem-search\"><pagefind-modal-trigger placeholder=\"[^\"]+\"></pagefind-modal-trigger>" h))
          (is (= (if (= "ms" lang) 0 1) (count (re-seq #"<pagefind-modal>" h)))
              "the Malay page's modal is created by js/search.js AFTER setTranslations: 1.5.2's
               modal nests a second dialog when its strings change under it")))
      (testing "the trigger is labelled with the page's own :nav/search"
        (is (str/includes? (html out "ms") "<pagefind-modal-trigger placeholder=\"Cari\">"))
        (is (str/includes? (html out "zh-Hant") "<pagefind-modal-trigger placeholder=\"搜尋\">"))
        (is (str/includes? (html out "ta") "<pagefind-modal-trigger placeholder=\"தேடு\">"))))))

(deftest malay-gets-its-strings-and-the-rest-ride-pagefind
  (with-markup
    (fn [out]
      (let [ms   (html out "ms")
            attr (second (re-find #"data-clogem-translations=\"([^\"]*)\"" ms))
            tr   (json/parse-string (str/replace attr "&quot;" "\"") false)]
        (is (= 25 (count tr)) "every string of Pagefind's en.json")
        (is (= "Tiada hasil untuk [SEARCH_TERM]" (get tr "zero_results"))
            "Pagefind's placeholders pass through untouched")
        (is (= "[COUNT] hasil untuk [SEARCH_TERM]" (get tr "many_results")))
        (is (str/includes? ms "<script defer=\"defer\" src=\"/clogem/js/search.js\"></script>"))
        (is (fs/exists? (fs/path out "clogem" "js" "search.js"))))
      (doseq [parts [[] ["zh-Hans"] ["zh-Hant"] ["ta"]]
              :let [h (apply html out parts)]]
        (is (not (str/includes? h "data-clogem-translations")) (pr-str parts))
        (is (not (str/includes? h "search.js")) (pr-str parts))))))

(deftest a-site-override-of-a-search-string-is-passed-through
  (with-site five-langs {}
    (fn [dir out]
      (let [bin (fake/fake-pagefind! (fs/path dir "bin"))]
        (spit (fs/file dir "site.edn") (pr-str (pagefind-site bin)))
        (fs/create-dirs (fs/path dir "i18n"))
        (spit (fs/file dir "i18n" "ta.edn") (pr-str {:search/placeholder "இங்கே தேடுக"}))
        (build! dir out)
        (is (str/includes? (html out "ta") "&quot;placeholder&quot;:&quot;இங்கே தேடுக&quot;"))
        (is (not (str/includes? (html out "zh-Hans") "data-clogem-translations")))))))

(deftest ui-language-resolution-mirrors-pagefind
  (with-site {} {} (fn [dir _]
    (let [cfg (cfg-of dir)]
      (is (= "zh-TW" (search/ui-lang cfg :zh-Hant)) "Traditional strings, not zh.json's Simplified")
      (is (= "zh-Hans" (search/ui-lang cfg :zh-Hans)))
      (is (= "ms" (search/ui-lang cfg :ms)))
      (is (= "zh-TW" (search/ui-lang (assoc-in cfg [:langs :locales :zh-Hant :html-lang] "zh-Hant-HK") :zh-Hant)))
      (is (every? search/builtin-ui? ["en" "zh-Hans" "zh-TW" "ta"]))
      (is (not (search/builtin-ui? "ms")) "Pagefind 1.5.2 ships no Malay")
      (is (search/builtin-ui? "pt-BR") "language-region falls back to language")))))

(deftest the-search-strings-are-pagefinds-keys
  (testing "the :search/… keys are exactly Pagefind's en.json keys, and en.edn
            carries en.json's own English"
    (let [en (i18n/theme-strings :en)]
      (is (= 25 (count search/ui-string-keys)))
      (is (every? #(contains? en %) search/ui-string-keys))
      (is (= "No results for [SEARCH_TERM]" (:search/zero-results en)))
      (is (= "No results for [SEARCH_TERM]. Showing results for [DIFFERENT_TERM] instead" (:search/alt-search en))))))

(deftest provider-none-emits-no-search-markup
  (with-site five-langs url-site
    (fn [dir out]
      (build! dir out)
      (doseq [f (fs/glob out "**.html")]
        (is (not (re-find #"(?i)pagefind" (slurp (fs/file f)))) (str f)))
      (is (fs/exists? (fs/path out "clogem" "js" "toc.js")))
      (is (not (fs/exists? (fs/path out "clogem" "js" "search.js")))
          "nor the script that only the search UI loads"))))

;; ---------------------------------------------------------------------------
;; The self-hosted Tamil font (D-P3-12)

(deftest system-fonts-ship-no-font-bytes
  (with-site five-langs url-site
    (fn [dir out]
      (build! dir out)
      (is (not (fs/exists? (fs/path out "clogem" "fonts"))) "no fonts copied")
      (doseq [f (fs/glob out "**.html")]
        (is (not (str/includes? (slurp (fs/file f)) "fonts/")) (str f))))))

(deftest self-hosted-tamil-is-linked-and-resolves
  (with-site five-langs (assoc url-site :theme {:fonts {:tamil :self-hosted}})
    (fn [dir out]
      (build! dir out)
      (let [h (html out "ta" "pages" "t00001")]
        (is (str/includes? h "<link href=\"/clogem/fonts/tamil.css\" rel=\"stylesheet\" />")))
      (let [css (slurp (fs/file out "clogem" "fonts" "tamil.css"))
            urls (map second (re-seq #"url\(\"([^\"]+)\"\)" css))]
        (is (= 2 (count (re-seq #"@font-face" css))))
        (is (= 2 (count (re-seq #"font-display: swap;" css))))
        (is (= 2 (count (re-seq #"unicode-range: U\+0B80-0BFF, U\+200C-200D, U\+25CC;" css))))
        (is (= ["noto-sans-tamil-400.woff2" "noto-sans-tamil-700.woff2"] urls))
        (doseq [u urls
                :let [f (fs/path out "clogem" "fonts" u)]]
          (is (fs/regular-file? f) u)
          (is (< 15000 (fs/size f) 40000) (str u " is a subset, not the full face"))
          (is (= "wOF2" (String. (byte-array (take 4 (fs/read-all-bytes f))) "US-ASCII")) u))
        (is (fs/regular-file? (fs/path out "clogem" "fonts" "OFL.txt")) "the licence travels with the fonts")))))

;; ---------------------------------------------------------------------------
;; The real binary (CI sets CLOGEM_PAGEFIND)

(deftest the-real-pagefind-indexes-five-languages
  (if-let [real (System/getenv "CLOGEM_PAGEFIND")]
    (with-site five-langs (assoc url-site :search {:provider :pagefind})
      (fn [dir out]
        (let [printed (build! dir out)
              entry   (json/parse-string (slurp (fs/file out "pagefind" "pagefind-entry.json")) true)
              ui-js   (slurp (fs/file out "pagefind" "pagefind-component-ui.js"))]
          (is (str/includes? printed "(5 languages, 7 pages)") printed)
          (is (= #{:en :zh-hans :zh-hant :ms :ta} (set (keys (:languages entry)))))
          (is (= {:en 2 :zh-hans 1 :zh-hant 1 :ms 1 :ta 2}
                 (update-vals (:languages entry) :page_count))
              "article variants only, the catalogue page in en; no homes or indexes")
          (testing "the shipped Component UI has what the theme relies on"
            (doseq [el ["pagefind-config" "pagefind-modal" "pagefind-modal-trigger"]]
              (is (str/includes? ui-js (str "customElements.define(\"" el "\"")) el))
            (is (str/includes? ui-js "getInstanceManager"))
            (is (str/includes? ui-js "setTranslations("))
            (is (str/includes? ui-js "getAttribute(\"bundle-path\")"))
            (doseq [k search/ui-string-keys
                    :let [pk (str/replace (name k) "-" "_")]]
              (is (str/includes? ui-js (str pk ":\"")) pk))
            (is (fs/exists? (fs/path out "pagefind" "pagefind-component-ui.css"))))))
      :env {"CLOGEM_PAGEFIND" real})
    (do (is (nil? (System/getenv "GITHUB_ACTIONS"))
            "CI must set CLOGEM_PAGEFIND, so this test always runs there")
        (println "the-real-pagefind-indexes-five-languages: skipped — CLOGEM_PAGEFIND is not set"))))


(defn- word-re [w] (re-pattern (str "(?iu)(?<![\\p{L}\\p{N}])" w "(?![\\p{L}\\p{N}])")))

(deftest the-real-pagefind-indexes-words-not-chrome
  (if-let [real (System/getenv "CLOGEM_PAGEFIND")]
    (let [site (fs/create-temp-dir {:prefix "clogem-demo"})]
      (try
        (fs/copy-tree "examples/demo-site" site)
        (binding [diag/*sink* (atom [])
                  search/*env* {"CLOGEM_PAGEFIND" real}]
          (let [out   (fs/path site "dist")
                _     (with-out-str (cli/build {:site-dir (str site) :out (str out) :no-write true}))
                frags (fake/read-fragments out)
                en    (filter #(= "en" (:lang %)) frags)
                tagged (set (keep #(when (some #{"markdown"} (get-in % [:filters :tag])) (:url %)) frags))]
            (testing "`markdown` finds the pages tagged with it (it was 0 hits:
                      `Localmarkdown`, `Basicsmarkdown容器`)"
              (is (= 2 (count (filter #(and (= "en" (:lang %)) (tagged (:url %))) frags))) "the two English pages")
              (is (= tagged (set (map :url (filter #(re-find (word-re "markdown") (:content %)) frags))))))
            (testing "`2026` no longer matches every page through the date"
              (is (< (count (filter #(re-find (word-re "2026") (:content %)) en)) (/ (count en) 2))
                  (str (count en) " English pages")))
            (testing "no excerpt starts with the date; no result title carries the title tag"
              (doseq [f frags]
                (is (not (re-find #"\d{4}-\d{2}-\d{2}" (subs (:content f) 0 (min 60 (count (:content f))))))
                    (:content f))
                (is (not (str/includes? (str (get-in f [:meta :title])) "原创")) (:url f))))
            (is (= "A post from the year before"
                   (some #(when (= "/pages/y2025a/" (:url %)) (get-in % [:meta :title])) frags)))
            (testing "categories and tags are filters"
              (is (= ["Notes"] (some #(when (= "/pages/y2025a/" (:url %)) (get-in % [:filters :category])) frags))))
            (testing "Repro 1 (§11.2 item 46): delete a variant, rebuild into the same dist/"
              (let [before (count frags)]
                (fs/delete (first (fs/glob (fs/path site "content") "**/06.wide-content.zh-Hant.md")))
                (with-out-str (cli/build {:site-dir (str site) :out (str out) :no-write true}))
                (let [after (fake/read-fragments out)
                      entry (json/parse-string (slurp (fs/file out "pagefind" "pagefind-entry.json")) true)]
                  (is (= (dec before) (count after)))
                  (is (= (count after) (reduce + (map :page_count (vals (:languages entry))))))
                  (is (not-any? #(str/starts-with? (:url %) "/zh-Hant/pages/") (filter #(str/includes? (:content %) "wide") after))))))
            (testing "Repro 2: edit-and-rebuild cycles do not grow the bundle"
              (let [counts (fn [] (mapv #(count (fs/glob (fs/path out "pagefind") %))
                                        ["fragment/*" "index/*" "*.pf_meta"]))
                    f0 (counts)
                    md (first (fs/glob (fs/path site "content") "**/01.getting-started.md"))]
                (dotimes [i 3]
                  (spit (fs/file md) (str "\nEdit " i ".\n") :append true)
                  (with-out-str (cli/build {:site-dir (str site) :out (str out) :no-write true})))
                (is (= f0 (counts)))))))
        (finally (fs/delete-tree site))))
    (do (is (nil? (System/getenv "GITHUB_ACTIONS"))
            "CI must set CLOGEM_PAGEFIND, so this test always runs there")
        (println "the-real-pagefind-indexes-words-not-chrome: skipped — CLOGEM_PAGEFIND is not set"))))
