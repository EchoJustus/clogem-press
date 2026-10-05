;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.config-test
  (:require [babashka.fs :as fs]
            [babashka.process]
            [clojure.test :refer [deftest is testing]]
            [clojure.edn]
            [clojure.set]
            [clojure.string :as str]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n]
            [clogem.util :as u]))

(defn- with-site
  "Write a site.edn into a temp dir and load it."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(deftest deep-merge-semantics
  (testing "maps merge recursively; vectors are replaced wholesale"
    (is (= {:a {:b 1 :c 3}} (u/deep-merge {:a {:b 1 :c 2}} {:a {:c 3}})))
    (is (= {:a [3]} (u/deep-merge {:a [1 2]} {:a [3]}))
        "a site that overrides :nav means to replace it, not to append")))

(deftest defaults-are-applied
  (let [[cfg _] (with-site {})]
    (is (= :en (config/default-lang cfg)))
    (is (= "content" (get-in cfg [:content :dir])))
    (is (true? (config/write-front-matter? cfg)))))

(deftest langs-are-canonicalized
  (let [[cfg _] (with-site {})]
    (testing "suffix matching is case-insensitive, storage is canonical (§6.1)"
      (is (= :zh-Hans (config/lang-for-suffix cfg "zh-hans")))
      (is (= :zh-Hans (config/lang-for-suffix cfg "ZH-HANS")))
      (is (= :zh-Hant (config/lang-for-suffix cfg "Zh-HaNt")))
      (is (nil? (config/lang-for-suffix cfg "js")))
      (is (= "zh-Hans" (config/html-lang cfg :zh-Hans))
          "the emitted <html lang> is the configured spelling, not the lowercased one"))))

(deftest priority-is-made-total
  (let [[cfg _] (with-site {:langs {:priority [:ta]}})]
    (is (= :ta (first (config/lang-keys cfg))))
    (is (= 5 (count (config/lang-keys cfg))) "missing locales are appended")))

(deftest default-must-be-a-configured-locale
  (let [[_ ds] (with-site {:langs {:default :fr}})]
    (is (some #(re-find #":langs :default" (:message %)) (diag/errors ds)))))

;; ---------------------------------------------------------------------------
;; V12 — the mandatory giscus mapping

(deftest giscus-language-must-be-routable
  (testing "an unroutable data-lang 404s the widget iframe, so it is an error"
    (let [[_ ds] (with-site {:langs {:locales {:ta {:label "த" :html-lang "ta" :giscus "ta"}}}})]
      (is (some #(re-find #"availableLanguages" (:message %)) (diag/errors ds))
          "giscus has no `ta` locale; the value must be `en`"))))

(deftest giscus-mapping-is-required-when-giscus-is-the-provider
  (let [[_ ds] (with-site {:langs {:locales {:ta {:label "த" :html-lang "ta" :giscus nil}}}
                           :comments {:provider :giscus :repo "a/b" :repo-id "x" :category-id "y"}})]
    (is (some #(re-find #"no :giscus mapping" (:message %)) (diag/errors ds)))))

(deftest giscus-defaults-map-ms-and-ta-to-en
  (is (= "en" (get-in config/default-locales [:ms :giscus])))
  (is (= "en" (get-in config/default-locales [:ta :giscus])))
  (is (= "zh-CN" (get-in config/default-locales [:zh-Hans :giscus])))
  (is (= "zh-TW" (get-in config/default-locales [:zh-Hant :giscus])))
  (testing "every default is one giscus actually routes"
    (doseq [[k v] config/default-locales]
      (is (contains? config/giscus-available-languages (:giscus v)) (str k)))))

(deftest giscus-not-in-use-means-no-requirement
  (let [[_ ds] (with-site {:comments {:provider :none}})]
    (is (empty? (diag/errors ds)))))

;; ---------------------------------------------------------------------------
;; M3 — the generator version floor

(deftest min-version-floor-is-asserted
  (testing "D-14 as amended: site.edn carries a floor, not a pin"
    (let [[_ ds] (with-site {:generator {:min-version "99.0.0"}})]
      (is (some #(re-find #":min-version" (:message %)) (diag/errors ds)))
      (is (some #(re-find #"publish.yml" (:hint %)) (diag/errors ds))
          "the error must point at the authoritative pin")))
  (let [[_ ds] (with-site {:generator {:min-version "0.0.1"}})]
    (is (empty? (diag/errors ds)))))

(deftest version-comparison
  (is (u/version>= "0.3.0" "0.3.0"))
  (is (u/version>= "0.3.1" "0.3.0"))
  (is (u/version>= "1.0.0" "0.9.9"))
  (is (u/version>= "v0.3.0" "0.3.0") "a leading v is tolerated")
  (is (not (u/version>= "0.3.0-rc1" "0.3.0")) "a pre-release sorts below its release")
  (is (not (u/version>= "0.2.9" "0.3.0")))
  (is (u/version>= "0.3" "0.3.0") "missing segments are zero"))

(deftest version-comparison-follows-semver-precedence
  (testing "fix 16: the Phase 1 generator `0.1.0-phase1` must not satisfy a `0.1.0` floor"
    (is (not (u/version>= "0.1.0-phase1" "0.1.0")))
    (is (u/version>= "0.1.1" "0.1.0"))
    (is (u/version>= "0.1.0" "0.1.0-phase1"))
    (is (u/version>= "0.1.1-rc.1" "0.1.0"))
    (is (not (u/version>= "1.0.0-alpha" "1.0.0-alpha.1")) "a shorter identifier list is lower")
    (is (not (u/version>= "1.0.0-alpha.1" "1.0.0-alpha.beta")) "numeric < alphanumeric")
    (is (not (u/version>= "1.0.0-rc.2" "1.0.0-rc.10")) "numeric identifiers compare numerically")
    (is (u/version>= "1.0.0-beta" "1.0.0-alpha.9"))))

(deftest a-malformed-min-version-is-a-config-error
  (doseq [floor ["abc" 0.1 "0.1" "v0.1.0" ""]]
    (let [[cfg ds] (with-site {:generator {:min-version floor}})]
      (is (some #(re-find #":min-version is" (:message %)) (diag/errors ds)) (pr-str floor))
      (is (nil? (get-in cfg [:generator :min-version])) "repaired to no floor")))
  (let [[_ ds] (with-site {:generator {:min-version "0.1.0-phase1"}})]
    (is (empty? (diag/errors ds)) "a pre-release floor is well-formed"))
  (testing "D.2.1 fix F: a tag-shaped floor is told to drop the leading v"
    (doseq [floor ["v0.1.1" "V0.1.1"]]
      (let [[_ ds] (with-site {:generator {:min-version floor}})
            msg    (str/join "\n" (map diag/format-diagnostic (diag/errors ds)))]
        (is (re-find #"Drop the leading v: write \"0\.1\.1\"" msg) msg)))
    (let [[_ ds] (with-site {:generator {:min-version "abc"}})]
      (is (not-any? #(re-find #"leading v" (str (:hint %))) (diag/errors ds)) "only when the rest is a version"))))

(defn- content-site
  "A valid one-article content tree under `edn`."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-site"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (fs/create-dirs (fs/path dir "content" "01.Guide"))
    (spit (fs/file (fs/path dir "content" "01.Guide" "01.a.md"))
          "---\ntitle: A\ndate: \"2026-01-01 00:00:00\"\npermalink: /pages/aaaaaa/\n---\n\n# A\n\n## H\n\nbody\n")
    dir))

(deftest min-version-abc-fails-doctor
  (let [dir (content-site {:generator {:min-version "abc"}})]
    (try
      (binding [diag/*sink* (atom [])]
        (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/doctor {:site-dir (str dir)})))]
          (when (instance? clojure.lang.ExceptionInfo e)
            (is (= 1 (:babashka/exit (ex-data e)))))))
      (finally (fs/delete-tree dir)))))

(deftest theme-integers-are-type-checked-and-repaired
  (doseq [[k bad] [[:per-page "10"] [:per-page 0] [:per-page :ten]
                   [:sidebar-depth "2"] [:sidebar-depth 6] [:sidebar-depth -1] [:sidebar-depth 1.5]]]
    (let [[cfg ds] (with-site {:theme {k bad}})]
      (is (some #(re-find (re-pattern (str ":theme " k " is")) (:message %)) (diag/errors ds))
          (str k " " (pr-str bad)))
      (is (= (get-in config/defaults [:theme k]) (get-in cfg [:theme k])) "repaired to the default")))
  (testing "an explicit nil means the default, and is not an error"
    (let [[cfg ds] (with-site {:theme {:per-page nil :sidebar-depth nil}})]
      (is (empty? (diag/errors ds)))
      (is (= 10 (get-in cfg [:theme :per-page])))
      (is (= 2 (get-in cfg [:theme :sidebar-depth])))))
  (let [[_ ds] (with-site {:theme {:per-page 3 :sidebar-depth 0}})]
    (is (empty? (diag/errors ds)) "valid values pass")))

(deftest a-string-theme-integer-fails-build-and-doctor-with-no-dist
  (doseq [k [:per-page :sidebar-depth]]
    (let [dir (content-site {:theme {k "3"}})
          out (fs/path dir "dist")]
      (try
        (binding [diag/*sink* (atom [])]
          (testing (str k " — doctor")
            (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/doctor {:site-dir (str dir)})))]
              (when (instance? clojure.lang.ExceptionInfo e)
                (is (= 1 (:babashka/exit (ex-data e))))
                (is (some #(re-find (re-pattern (str ":theme " k " is \"3\"")) (:message %))
                          (:clogem/errors (ex-data e)))
                    "the readable message, not a ClassCastException"))))
          (doseq [no-write [false true]]
            (testing (str k " — build, no-write " no-write)
              (let [e (is (thrown? clojure.lang.ExceptionInfo
                                   (cli/build {:site-dir (str dir) :out (str out) :no-write no-write})))]
                (when (instance? clojure.lang.ExceptionInfo e)
                  (is (= 1 (:babashka/exit (ex-data e))))
                  (is (re-find (re-pattern (str ":theme " k)) (ex-message e)))))
              (is (not (fs/exists? out)) "no dist/"))))
        (finally (fs/delete-tree dir))))))

(deftest paths-are-normalized
  (let [[cfg _] (with-site {})]
    (is (not (clojure.string/includes? (str (config/out-dir cfg)) "/./")))))

;; ---------------------------------------------------------------------------
;; D-P2-12 — config validation is FATAL

(defn- bad-site
  "A valid content tree under a config whose :langs :default names a language
  the site never configured. validate! repairs the default so the rest of a
  doctor report is readable; that repair used to be taken as permission to
  build."
  []
  (let [dir (fs/create-temp-dir {:prefix "clogem-badcfg"})]
    (spit (fs/file dir "site.edn") (pr-str {:langs {:default :fr}}))
    (fs/create-dirs (fs/path dir "content" "01.Guide"))
    (spit (fs/file (fs/path dir "content" "01.Guide" "01.a.md")) "# A\n\nbody\n")
    dir))

(defn- exit-code [e] (:babashka/exit (ex-data e)))

(deftest a-config-error-stops-the-build-before-anything-is-written
  (testing "build exits non-zero, writes no dist/, and touches no source file"
    (let [dir (bad-site)
          src (fs/path dir "content" "01.Guide" "01.a.md")
          before (slurp (fs/file src))]
      (try
        (binding [diag/*sink* (atom [])]      ; keep the printed report off stderr
          (let [e (is (thrown? clojure.lang.ExceptionInfo
                               (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist"))})))]
            (when (instance? clojure.lang.ExceptionInfo e)
              (is (= 1 (exit-code e)) "bb exits with the :babashka/exit status")
              (is (re-find #":langs :default" (ex-message e)))
              (is (re-find #"config error" (ex-message e))))))
        (is (not (fs/exists? (fs/path dir "dist"))) "no dist/ — not even an empty one")
        (is (= before (slurp (fs/file src)))
            "auto-fill runs AFTER the config check, so the source is byte-identical")
        (is (not (fs/exists? (fs/path dir "permalinks.edn"))) "and no ledger was written")
        (finally (fs/delete-tree dir))))))

(deftest a-config-error-fails-doctor-and-fm-fix-too
  (let [dir (bad-site)]
    (try
      (binding [diag/*sink* (atom [])]
        (testing "doctor still produces the content report, then exits non-zero"
          (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/doctor {:site-dir (str dir)})))]
            (when (instance? clojure.lang.ExceptionInfo e)
              (is (= 1 (exit-code e)))
              (is (some #(re-find #":langs :default" (:message %)) (:clogem/errors (ex-data e)))
                  "the config error is IN the doctor report, not just in front of it"))))
        (testing "fm-fix refuses to normalize anything"
          (let [e (is (thrown? clojure.lang.ExceptionInfo (cli/fm-fix {:site-dir (str dir)})))]
            (when (instance? clojure.lang.ExceptionInfo e)
              (is (= 1 (exit-code e)))))
          (is (= "# A\n\nbody\n" (slurp (fs/file (fs/path dir "content" "01.Guide" "01.a.md")))))))
      (finally (fs/delete-tree dir)))))

(deftest a-good-config-still-builds
  (testing "the gate is on ERRORS only — warnings (here: no site.edn at all) pass"
    (let [dir (fs/create-temp-dir {:prefix "clogem-goodcfg"})]
      (try
        (fs/create-dirs (fs/path dir "content" "01.Guide"))
        (spit (fs/file (fs/path dir "content" "01.Guide" "01.a.md")) "# A\n\nbody\n")
        (binding [diag/*sink* (atom [])]
          (is (map? (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist")) :no-write true}))))
        (is (fs/exists? (fs/path dir "dist" "index.html")))
        (finally (fs/delete-tree dir))))))

(deftest the-real-bb-process-exits-non-zero-on-a-config-error
  (testing "end to end, as CI and publish.yml run it: `bb --config bb.edn build`
            in the site directory must exit 1 and leave no dist/"
    (let [dir (bad-site)
          bb-edn (str (fs/absolutize "bb.edn"))
          {:keys [exit err]} (babashka.process/sh {:dir (str dir) :out :string :err :string}
                                                  "bb" "--config" bb-edn "build")]
      (try
        (is (= 1 exit) (str "stderr was: " err))
        (is (re-find #":langs :default" err))
        (is (not (fs/exists? (fs/path dir "dist"))))
        (finally (fs/delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; Theme string parity (§6.5)

(deftest every-language-has-every-theme-string
  (testing "each of the configured languages carries every key en.edn has —
            the fallback chain would hide a gap in production, so it is
            asserted here instead (this is what catches a missing
            :container/theorem)"
    (let [[cfg _] (with-site {})
          en (set (keys (clogem.i18n/theme-strings :en)))]
      (is (seq en))
      (doseq [l (config/lang-keys cfg)]
        (let [ks (set (keys (clogem.i18n/theme-strings l)))]
          (is (empty? (clojure.set/difference en ks))
              (str l " is missing " (pr-str (clojure.set/difference en ks))))
          (is (empty? (clojure.set/difference ks en))
              (str l " has keys en lacks: " (pr-str (clojure.set/difference ks en)))))))))

;; ---------------------------------------------------------------------------
;; Phase 2 defaults and locale removal

(deftest phase-2-theme-defaults
  (let [[cfg _] (with-site {})]
    (is (= 10 (get-in cfg [:theme :per-page])))
    (is (= 2 (get-in cfg [:theme :sidebar-depth])))
    (is (true? (get-in cfg [:theme :sidebar-open])))
    (is (true? (get-in cfg [:content :category])))
    (is (true? (get-in cfg [:content :tag])))
    (is (true? (get-in cfg [:content :archive])))
    (is (= {} (get-in cfg [:i18n :category-labels])))))

(deftest an-explicit-nil-removes-a-default-locale
  (testing "deep-merge lets nil win, and normalize-langs honours it — the only
            way a site can have fewer than the five default languages"
    (let [[cfg ds] (with-site {:langs {:locales {:zh-Hant nil :ms nil :ta nil}}})]
      (is (= [:en :zh-Hans] (config/lang-keys cfg)))
      (is (nil? (config/lang-for-suffix cfg "ms")) "…so `01.Timing.ms.md` is a title again")
      (is (empty? (diag/errors ds))))))

;; ---------------------------------------------------------------------------
;; Phase 3 part A — og:locale, the fallback chain, absolute URLs

(deftest og-locales-default-to-language-territory
  (let [[cfg ds] (with-site {})]
    (is (= {:en "en_US" :zh-Hans "zh_CN" :zh-Hant "zh_TW" :ms "ms_MY" :ta "ta_IN"}
           (into {} (map (fn [l] [l (config/og-locale cfg l)])) (config/lang-keys cfg))))
    (is (empty? (diag/errors ds))))
  (testing "D-P3-2: `zh_Hans` and a bare `en` are not Open Graph locales"
    (doseq [bad ["zh_Hans" "en" "en-US" :en_US]]
      (let [[_ ds] (with-site {:langs {:locales {:zh-Hans {:og bad}}}})]
        (is (some #(re-find #"locale :zh-Hans has :og" (:message %)) (diag/errors ds)) (pr-str bad))))))

(deftest the-fallback-chain-is-configurable
  (let [[cfg ds] (with-site {})]
    (is (= [:site-default :en] (get-in cfg [:i18n :fallback])) "§5.6's default")
    (is (= [:ms :en] (config/fallback-chain cfg :ms)))
    (is (= [:en] (config/fallback-chain cfg :en)) "distinct")
    (is (empty? (diag/errors ds))))
  (let [[cfg ds] (with-site {:langs {:default :zh-Hans} :i18n {:fallback [:site-default :ta]}})]
    (is (= [:ms :zh-Hans :ta] (config/fallback-chain cfg :ms)))
    (is (empty? (diag/errors ds))))
  (testing "the built-in default survives a site that removed :en — unfiltered,
            as 0.1.1 had it: a config map's :en value still comes before its
            first value"
    (let [[cfg ds] (with-site {:langs {:default :ms :locales {:en nil}}})]
      (is (= [:ta :ms :en] (config/fallback-chain cfg :ta)))
      (is (empty? (diag/errors ds)))))
  (testing "D-P3-7: anything but configured languages and :site-default is a config error"
    (doseq [bad [[:fr] [:site-default "en"] :en '(:en) [:zh-hans]]]
      (let [[cfg ds] (with-site {:i18n {:fallback bad}})]
        (is (some #(re-find #":i18n :fallback" (:message %)) (diag/errors ds)) (pr-str bad))
        (is (= [:site-default :en] (get-in cfg [:i18n :fallback])) "repaired to the default")))))

(deftest tr-and-resolve-str-follow-the-configured-chain
  (let [[cfg _] (with-site {:i18n {:fallback [:ta]}})
        strings {:ta {:x/y "தமிழ்"} :en {:x/y "English" :x/only-en "E"}}
        ctx     {:cfg cfg :lang :ms :strings strings}]
    (is (= "தமிழ்" (clogem.i18n/tr ctx :x/y)) "ms → ta, never the default :en first")
    (is (= "only-en" (clogem.i18n/tr ctx :x/only-en)) "past the last fallback: the key's name in production")
    (is (= "⟦:x/only-en⟧" (first (diag/collecting (clogem.i18n/tr (assoc ctx :dev? true) :x/only-en))))
        "…and ⟦k⟧ in dev")
    (is (= "T" (clogem.i18n/resolve-str ctx {:ta "T" :en "E"})))))

(deftest removing-en-keeps-0-1-1s-config-map-fallback
  (testing "G: :locales {:en nil}, default :zh-Hans — a :ta page's title is the
            map's :en value, not its first value"
    (let [[cfg ds] (with-site {:langs {:default :zh-Hans :locales {:en nil}}
                               :site {:title {:ta "TA-TITLE" :en "EN-TITLE"}}})]
      (is (empty? (diag/errors ds)))
      (is (= "EN-TITLE" (clogem.i18n/resolve-str {:cfg cfg :lang :ms} (get-in cfg [:site :title])))
          ":ms → :zh-Hans (absent) → :en")
      (is (= "TA-TITLE" (clogem.i18n/resolve-str {:cfg cfg :lang :ta} (get-in cfg [:site :title]))))))
  (testing "a chain the site WROTE is still validated against :locales"
    (let [[_ ds] (with-site {:langs {:locales {:en nil}} :i18n {:fallback [:site-default :ms :en]}})]
      (is (some #(re-find #":i18n :fallback" (:message %)) (diag/errors ds))))))

(deftest site-url-must-be-an-origin
  (testing "I: no scheme is a config error — every canonical would be relative"
    (doseq [bad ["u.github.io" "//u.github.io" "/repo/"]]
      (let [[_ ds] (with-site {:site {:url bad}})]
        (is (some #(re-find #":site :url .* has no scheme and host" (:message %)) (diag/errors ds)) bad))))
  (testing "I: a path that repeats :base doubles it — a warning that says to drop the path"
    (doseq [url ["https://u.github.io/repo" "https://u.github.io/repo/"]]
      (let [[_ ds] (with-site {:site {:url url :base "/repo/"}})
            ws (filter #(re-find #"repeats the base path" (:message %)) (diag/warnings ds))]
        (is (empty? (diag/errors ds)) url)
        (is (= 1 (count ws)) url)
        (is (str/includes? (str (:hint (first ws))) "Drop the path: write \"https://u.github.io\"") url))))
  (testing "a bare origin, with or without a trailing slash, is quiet"
    (doseq [[url base] [["https://u.github.io" "/repo/"] ["https://u.github.io/" "/"] ["http://localhost:8080/" "/"]]]
      (let [[_ ds] (with-site {:site {:url url :base base}})]
        (is (empty? (filter #(re-find #":site :url" (:message %)) ds)) url)))))

(deftest seo-x-default-is-validated
  (testing "J: :primary is the only legal value; anything else is a config error repaired to it"
    (let [[cfg ds] (with-site {})]
      (is (= :primary (get-in cfg [:seo :x-default])))
      (is (empty? (diag/errors ds))))
    (doseq [bad [:first "primary" :en]]
      (let [[cfg ds] (with-site {:seo {:x-default bad}})]
        (is (some #(re-find #":seo :x-default" (:message %)) (diag/errors ds)) (pr-str bad))
        (is (= :primary (get-in cfg [:seo :x-default])))))))

(deftest absolute-urls-join-the-site-url-and-the-base-inclusive-path
  (let [[cfg _] (with-site {:site {:url "https://x.example/"}})]
    (is (= "https://x.example" (config/site-url-root cfg)) "trailing slash dropped")
    (is (= "https://x.example/categories/%E5%9F%BA%E7%A1%80/" (config/absolute-url cfg "/categories/基础/"))))
  (let [[cfg _] (with-site {:site {:url "  "}})]
    (is (nil? (config/site-url-root cfg)))
    (is (nil? (config/absolute-url cfg "/")) "blank → emit nothing (D-P3-1)")))

(deftest seo-defaults
  (let [[cfg _] (with-site {})]
    (is (true? (get-in cfg [:seo :feeds])))
    (is (true? (get-in cfg [:seo :sitemap])))))

(deftest a-wrong-shaped-section-is-a-config-error
  (testing "a non-map where the defaults hold a map used to crash with a
            ClassCastException from a later assoc-in"
    (doseq [[edn path] [[{:search :pagefind} [:search]]
                        [{:theme {:fonts :self-hosted}} [:theme :fonts]]
                        [{:seo :none} [:seo]]
                        [{:tools {:pagefind "1.5.2"}} [:tools :pagefind]]
                        [{:theme "dark"} [:theme]]
                        [{:langs :en} [:langs]]
                        [{:build "dist"} [:build]]]]
      (let [[cfg ds] (with-site edn)]
        (is (some #(re-find (re-pattern (str "^" (str/join " " path) " is .*, but it must be a map"))
                            (:message %))
                  (diag/errors ds))
            (pr-str edn (map :message (diag/errors ds))))
        (is (map? (get-in cfg path)) "repaired, so doctor can keep going"))))
  (testing "build and doctor exit 1 with the readable message"
    (doseq [edn [{:search :pagefind} {:theme {:fonts :self-hosted}} {:seo :none}]]
      (let [dir (content-site edn)]
        (try
          (binding [diag/*sink* (atom [])]
            (let [e (try (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist")) :no-write true}) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= 1 (:babashka/exit (ex-data e))) (pr-str edn))
              (is (re-find #"must be a map" (str (ex-message e))) (ex-message e)))
            (is (not (fs/exists? (fs/path dir "dist")))))
          (finally (fs/delete-tree dir))))))
  (testing "nil still means the default, and valid maps pass"
    (let [[_ ds] (with-site {:search nil :seo {:sitemap false} :theme {:fonts {:tamil :system}}})]
      (is (empty? (diag/errors ds))))))

(deftest a-non-map-unused-tool-pin-warns-and-is-repaired
  (testing "0.2.0 accepted any :tools :fswatcher value; nothing runs it yet,
            so a non-map is a warning, repaired to the default (Chroma's is
            an error since Phase 4 C: tools-test a-bad-chroma-pin-is-an-error-now)"
    (doseq [[edn path] [[{:tools {:fswatcher ["0.0.7"]}} [:tools :fswatcher]]
                        [{:tools {:fswatcher false}} [:tools :fswatcher]]]]
      (let [[cfg ds] (with-site edn)
            ws (diag/warnings ds)]
        (is (empty? (diag/errors ds)) (pr-str edn (map :message (diag/errors ds))))
        (is (= 1 (count ws)) (pr-str edn (map :message ws)))
        (is (re-find (re-pattern (str "^" (str/join " " path) " is .*, but it must be a map"))
                     (str (:message (first ws))))
            (pr-str edn))
        (is (re-find #"so the built-in pin is used" (str (:hint (first ws)))) (pr-str (:hint (first ws))))
        (is (= (get-in config/defaults path) (get-in cfg path)) "repaired, so later checks see a map"))))
  (testing "Pagefind and Chroma, which a build runs, are errors"
    (doseq [id [:pagefind :chroma]]
      (let [[_ ds] (with-site {:tools {id "1.0.0"}})]
        (is (some #(re-find (re-pattern (str "^:tools " id " is .*, but it must be a map")) (:message %))
                  (diag/errors ds))
            (str id))))))

(deftest the-output-directory-must-be-a-directory-of-its-own
  (testing "§11.2 item 46: a build deletes .html files it did not write from
            its output directory, so out may not be the site, an ancestor of
            it, a directory holding the content dir, or another site's directory"
    (let [dir   (content-site {})
          other (fs/create-temp-dir {:prefix "clogem-other"})
          link  (fs/path other "link-to-site")]
      (spit (fs/file other "site.edn") "{}")
      (spit (fs/file dir "keep.html") "<p>a hand-written page in the site dir</p>")
      (fs/create-sym-link link dir)
      (try
        (binding [diag/*sink* (atom [])]
          (doseq [[out re] [["." #"is the site directory itself"]
                            [(str dir) #"is the site directory itself"]
                            [".." #"which contains the site directory"]
                            ["/" #"which contains the site directory"]
                            [(str link) #"is the site directory itself"]
                            [(str other) #"which holds a site.edn"]]]
            (testing (pr-str out)
              (let [e (try (cli/build {:site-dir (str dir) :out out :no-write true}) nil
                           (catch clojure.lang.ExceptionInfo e e))]
                (is (= 1 (:babashka/exit (ex-data e))))
                (is (re-find re (str (ex-message e))) (ex-message e))
                (is (re-find #"refusing to build there" (str (ex-message e)))))))
          (testing "content outside the site dir, under out"
            (let [d2 (content-site {:content {:dir "../shared/content"}})]
              (try
                (let [e (try (cli/build {:site-dir (str d2) :out "../shared" :no-write true}) nil
                             (catch clojure.lang.ExceptionInfo e e))]
                  (is (re-find #"contains the content directory" (str (ex-message e))) (ex-message e)))
                (finally (fs/delete-tree d2) (fs/delete-tree (fs/path (fs/parent d2) "shared"))))))
          (is (= "<p>a hand-written page in the site dir</p>" (slurp (fs/file dir "keep.html")))
              "refused before anything was written or deleted")
          (is (not (fs/exists? (fs/path dir "index.html"))))
          (is (not (fs/exists? (fs/path other "index.html"))))
          (testing "doctor reports it too"
            (let [[_ ds] (diag/collecting (config/load-config (str dir) nil {:build {:out "."}}))]
              (is (some #(re-find #":build :out" (:message %)) (diag/errors ds)))))
          (testing "a sibling or nested output directory is fine"
            (let [[_ ds] (diag/collecting (config/load-config (str dir) nil {:build {:out "../elsewhere"}}))]
              (is (empty? (diag/errors ds))))
            (let [[_ ds] (diag/collecting (config/load-config (str dir) nil {:build {:out "public/site"}}))]
              (is (empty? (diag/errors ds))))))
        (finally (fs/delete-tree dir) (fs/delete-tree other))))))

;; ---------------------------------------------------------------------------
;; Unknown and not-yet-implemented keys (Phase 4 Task A, part of D-P4-16)

(defn- warning-messages [edn]
  (let [[_ ds] (with-site edn)]
    (is (empty? (diag/errors ds)) "never an error")
    (mapv :message (diag/warnings ds))))

(deftest the-pre-flight-keys-all-warn
  (testing "0.2.0 ignored all of these silently; each now names itself"
    (let [ms (warning-messages {:analytics {:provider :ga4 :id "G-XXXX"}
                                :theme {:html-modules {:sidebar-b "<p>hi</p>"}
                                        :blogger {:name "me"}
                                        :footer {:create-year 2020}
                                        :bodyBgImg "bg.png"}
                                :seo {:indexnow {:enabled true :key "k"}}})]
      (is (some #(= ":analytics :provider is :ga4, which has no effect in 0.2.0; no analytics script is emitted." %) ms))
      (is (some #(= ":theme :html-modules has no effect in 0.2.0; nothing is injected." %) ms))
      (is (some #(= ":theme :blogger is planned, not implemented in 0.2.0; it has no effect." %) ms))
      (is (some #(= ":theme :footer is planned, not implemented in 0.2.0; it has no effect." %) ms))
      (is (some #(= (str ":theme :bodyBgImg is vdoing's spelling; clogem-press reads :theme :body-bg-img"
                         " — planned, not implemented in 0.2.0. It is ignored.") %) ms))
      (is (some #(= ":seo :indexnow :enabled true has no effect in 0.2.0; nothing is pushed to IndexNow." %) ms))
      (is (= 6 (count ms)) (pr-str ms)))))

(deftest unknown-keys-name-the-path-and-the-nearest-known-key
  (let [[_ ds] (with-site {:footr 1
                           :theme {:sidebar-opn false :fonts {:tamill :system}}
                           :langs {:locales {:en {:lable "E"}}}
                           :tools {:pagefind {:versoin "1.5.2"}}})
        ws (diag/warnings ds)
        by (into {} (map (juxt :message :hint)) ws)]
    (is (empty? (diag/errors ds)))
    (is (= [":footr is not a top-level option and is ignored."
            ":langs :locales :en :lable is not a locale option and is ignored."
            ":theme :fonts :tamill is not a theme fonts option and is ignored."
            ":theme :sidebar-opn is not a theme option and is ignored."
            ":tools :pagefind :versoin is not a tools pagefind option and is ignored."]
           (sort (keys by))))
    (is (str/starts-with? (by ":theme :sidebar-opn is not a theme option and is ignored.") "Did you mean :sidebar-open?"))
    (is (str/starts-with? (by ":langs :locales :en :lable is not a locale option and is ignored.") "Did you mean :label?"))
    (is (str/starts-with? (by ":tools :pagefind :versoin is not a tools pagefind option and is ignored.") "Did you mean :version?"))
    (is (str/starts-with? (by ":theme :fonts :tamill is not a theme fonts option and is ignored.") "Did you mean :tamil?"))
    (is (not (str/includes? (str (by ":footr is not a top-level option and is ignored.")) "Did you mean"))
        "nothing within distance 2 of :footr at the top level")))

(deftest vdoing-spellings-name-the-clogem-key
  (let [ms (warning-messages {:theme {:pageStyle :line :defaultMode :dark :sidebarOpen false
                                      :htmlModules {} :updateBar {} :categoryText "x"
                                      :extendFrontmatter {} :searchMaxSuggestions 5}})]
    (doseq [[k target planned?] [[":pageStyle" ":theme :page-style" false]
                                 [":defaultMode" ":theme :default-mode" false]
                                 [":htmlModules" ":theme :html-modules" true]
                                 [":updateBar" ":theme :update-bar" true]
                                 [":categoryText" ":content :category-text" false]
                                 [":extendFrontmatter" ":content :extend-frontmatter" false]]]
      (is (some #(= (str ":theme " k " is vdoing's spelling; clogem-press reads " target
                         (when planned? " — planned, not implemented in 0.2.0") ". It is ignored.") %)
                ms)
          k))
    (is (some #(str/starts-with? % ":theme :searchMaxSuggestions is a vdoing option clogem-press does not have") ms))
    (is (= 8 (count ms)) (pr-str ms))))

(deftest known-and-default-configs-are-quiet
  (testing "nothing that passed in 0.2.0 starts warning: the reference config,
            the demo site and a site with every :tools descriptor"
    (doseq [f ["config.example.edn" "examples/demo-site/site.edn"]]
      (let [edn (clojure.edn/read-string (slurp f))]
        (is (= [] (warning-messages (cond-> edn
                                      ;; the example's :url is real-looking; its
                                      ;; :nav and :content are what is checked
                                      true (assoc-in [:build :out] "dist"))))
            f)))
    (is (= [] (warning-messages {:comments {:provider :none :mapping :permalink}
                                 :theme {:html-modules {}}
                                 :analytics {:provider :none}
                                 :seo {:indexnow {:enabled false :key nil}}
                                 :tools {:chroma {:version "2.27.0"} :fswatcher {:version "0.0.7"}
                                         :cache-dir ".tools"}})))))

(deftest a-new-key-is-registered-in-one-place
  (testing "known-keys is data: a later task adds a key there and the
            warning goes away"
    (is (= :ok (get-in config/known-keys [[:theme] :page-style])))
    (is (= :planned (get-in config/known-keys [[:theme] :blogger])))
    (with-redefs [config/known-keys (assoc-in config/known-keys [[:theme] :blogger] :ok)]
      (is (= [] (warning-messages {:theme {:blogger {:name "me"}}}))))))

;; ---------------------------------------------------------------------------
;; Fix round P4-A.1: config checks

(deftest every-site-author-shape-is-quiet
  (testing "the three shapes i18n/resolve-author reads"
    (doseq [a ["Jane"
               {:en "Jane" :zh-Hans "简"}
               {"name" "Jane" "link" "https://jane.example"}
               {:name {:en "Jane" :zh-Hans "简"} :link "https://jane.example"}]]
      (is (= [] (warning-messages {:site {:author a}})) (pr-str a))))
  (testing "a typo in the named shape warns, suggesting the keyword spelling"
    (doseq [a [{:name "Jane" :lnk "x"} {"name" "Jane" "lnik" "x"}]]
      (let [[_ ds] (with-site {:site {:author a}})
            [w & more] (diag/warnings ds)]
        (is (empty? more) (pr-str a))
        (is (re-find #"^:site :author (:lnk|\"lnik\") is not a site author option and is ignored\.$" (str (:message w)))
            (:message w))
        (is (str/starts-with? (str (:hint w)) "Did you mean :link?") (:hint w))))))

(deftest a-scalar-html-modules-warns-and-never-crashes
  (doseq [v [false :none true 0]]
    (is (= [":theme :html-modules has no effect in 0.2.0; nothing is injected."]
           (warning-messages {:theme {:html-modules v}}))
        (pr-str v)))
  (doseq [v [nil {}]]
    (is (= [] (warning-messages {:theme {:html-modules v}})) (pr-str v))))

(deftest vdoing-statuses-say-what-clogem-does-instead
  (let [[_ ds] (with-site {:sidebarOpen false :sidebar "structuring" :algolia {:appId "x"}
                           :theme {:bodyBgImgInterval 15}})
        by (into {} (map (juxt :message :hint)) (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (contains? by (str ":sidebarOpen is vdoing's spelling; clogem-press reads :theme :sidebar-collapsed "
                           "(with the opposite sense: sidebarOpen false is :sidebar-collapsed true) — planned, "
                           "not implemented in 0.2.0. It is ignored."))
        (pr-str (keys by)))
    (let [m (str ":sidebar is not needed: clogem-press always generates the structured sidebar from the "
                 "numbered directory tree (vdoing's 'structuring' mode); it is ignored.")]
      (is (contains? by m) (pr-str (keys by)))
      (is (= (str "Remove it. Custom sidebar arrays and sidebar: 'auto' are not supported (DESIGN.md §8). "
                  "vdoing's `collapsable: false` corresponds to :theme :sidebar-open true.")
             (by m))))
    (is (contains? by (str ":algolia is a vdoing option clogem-press defers past v1 (DESIGN.md §8); "
                           "search is Pagefind's (:search :provider :pagefind).")))
    (is (contains? by (str ":theme :bodyBgImgInterval is vdoing's spelling; clogem-press reads "
                           ":theme :body-bg-img-interval — planned, not implemented in 0.2.0. It is ignored.")))
    (is (= 4 (count by)) (pr-str (keys by))))
  (is (= [":theme :body-bg-img-interval is planned, not implemented in 0.2.0; it has no effect."]
         (warning-messages {:theme {:body-bg-img-interval 15}}))))

(deftest a-retired-key-says-why
  (let [[_ ds] (with-site {:generator {:ref "v0.2.0"}})
        [w & more] (diag/warnings ds)]
    (is (empty? (diag/errors ds)))
    (is (empty? more))
    (is (= (str ":generator :ref was removed in design v2.1 (D-14); the generator version is pinned only by "
                "publish.yml's `ref:`. It is ignored.")
           (:message w)))
    (is (= "Delete it; to require a minimum generator, set :generator :min-version." (:hint w)))))

(deftest nav-items-are-checked
  (let [[_ ds] (with-site {:nav [{:txt "Home" :link "/"}
                                 {:text "A" :items [{:text "b" :lnk "/b/"}]}]})
        by (into {} (map (juxt :message :hint)) (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (= [":nav 0 :txt is not a nav item option and is ignored."
            ":nav 1 :items 0 :lnk is not a nav item option and is ignored."]
           (sort (keys by))))
    (is (str/starts-with? (by ":nav 0 :txt is not a nav item option and is ignored.") "Did you mean :text?"))
    (is (str/starts-with? (by ":nav 1 :items 0 :lnk is not a nav item option and is ignored.") "Did you mean :link?")))
  (testing ":text is a per-language user map, never walked"
    (is (= [] (warning-messages {:nav [{:text {:en "Home" :zh-Hans "首页" :whatever "x"} :link "/"
                                        :items [{:text {:ms "A"} :link "/a/"}]}]}))))
  (testing "the demo's nav is quiet"
    (is (= [] (warning-messages {:nav (:nav (clojure.edn/read-string (slurp "examples/demo-site/site.edn")))})))))

(deftest unknown-key-messages-use-the-right-article
  (is (= "an analytics" (config/a-or-an "analytics")))
  (is (= "an i18n" (config/a-or-an "i18n")))
  (is (= "a locale" (config/a-or-an "locale")))
  (is (= "a top-level" (config/a-or-an "top-level")))
  (is (= [":analytics :providr is not an analytics option and is ignored."
          ":i18n :fallbak is not an i18n option and is ignored."]
         (sort (warning-messages {:analytics {:providr :none} :i18n {:fallbak [:en]}})))))
