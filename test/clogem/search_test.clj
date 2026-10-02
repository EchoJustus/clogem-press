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
  (is (nil? (search/platform "Linux" "riscv64")) "no release asset → nil, and the build says so")
  (is (= "pagefind_extended.exe" (search/binary-name "x86_64-pc-windows-msvc")))
  (testing "every platform has a pinned hash for the default version"
    (let [v (get-in config/defaults [:tools :pagefind :version])]
      (is (= "1.5.2" v))
      (is (= 5 (count (get search/known-sha256 v))))
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
          (is (= ["pagefind_extended"] (map (comp str fs/file-name) (fs/list-dir (fs/parent bin))))
              "only the binary is kept: no tarball, no staging directory")
          (is (= [plat] (map (comp str fs/file-name) (fs/list-dir (fs/parent (fs/parent bin)))))
              "nothing left beside it either")
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
          (is (empty? (fs/list-dir (fs/path dir "cache" "pagefind" "1.5.2")))
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
          (is (re-find #"<header class=\"clogem-navbar\">.*<pagefind-modal-trigger placeholder=\"[^\"]+\"></pagefind-modal-trigger><pagefind-modal></pagefind-modal>" h))))
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
        (is (not (re-find #"(?i)pagefind" (slurp (fs/file f)))) (str f))))))

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
