;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.config
  "Load, deep-merge and validate `site.edn` (DESIGN.md §5.6).

  Precedence, lowest to highest: built-in defaults < site.edn < CLI overrides.

  The generator is invoked from the *site's* directory with its own bb.edn
  (`bb --config ../generator/bb.edn build`), so site paths resolve against the
  working directory while theme resources resolve off the classpath."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.diag :as diag]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Generator identity

(defn generator-version
  "This generator's own version. Baked into src/clogem/version.edn at release;
  DESIGN.md D-14 makes it the thing site.edn's :generator :min-version floor is
  asserted against."
  []
  (or (some-> (io/resource "clogem/version.edn") slurp edn/read-string :version)
      "0.0.0-unknown"))

;; ---------------------------------------------------------------------------
;; Defaults

(def default-locales
  "The five languages of DESIGN.md §6. `:giscus` is mandatory rather than
  decorative: giscus routes `data-lang` into the widget's iframe URL, so an
  unroutable value 404s the widget rather than falling back (§6.8, V12). Neither
  `ms` nor `ta` is in giscus's availableLanguages, so both MUST map to `en`.

  `:og` is the Open Graph locale (D-P3-2). ogp.me specifies `language_TERRITORY`
  and Facebook's locale list has no script-subtag forms, so zh-Hans and zh-Hant
  map to the territories whose script they use, not to `zh_Hans`/`zh_Hant`."
  {:en      {:label "English"       :html-lang "en"      :giscus "en"    :dir :ltr :og "en_US"}
   :zh-Hans {:label "简体中文"       :html-lang "zh-Hans" :giscus "zh-CN" :dir :ltr :og "zh_CN"}
   :zh-Hant {:label "繁體中文"       :html-lang "zh-Hant" :giscus "zh-TW" :dir :ltr :og "zh_TW"}
   :ms      {:label "Bahasa Melayu" :html-lang "ms"      :giscus "en"    :dir :ltr :og "ms_MY"}
   :ta      {:label "தமிழ்"          :html-lang "ta"      :giscus "en"    :dir :ltr :og "ta_IN"}})

(def og-locale-re
  "ogp.me's `og:locale` shape, `language_TERRITORY` (D-P3-2)."
  #"[a-z]{2}_[A-Z]{2}")

(def default-fallback
  "The §5.6 / §6.5 fallback chain after the requested language (D-P3-7)."
  [:site-default :en])

(def giscus-available-languages
  "giscus `availableLanguages`, verified from lib/i18n.tsx (DESIGN.md Appendix A
  item 11). A `:giscus` value outside this set is a build error, because the
  failure it causes — a 404'd iframe and no comment widget at all — is invisible
  until someone loads the page."
  #{"ar" "be" "ca" "de" "en" "eo" "es" "fa" "fr" "gsw" "he" "id" "it" "ja" "ko"
    "nl" "pl" "pt" "ro" "ru" "th" "tr" "uk" "vi" "zh-CN" "zh-TW" "zh-HK"})

(def defaults
  {:site    {:title "clogem-press site" :url "" :base "/"}
   :generator {}
   :langs   {:default  :en
             :priority [:en :zh-Hans :zh-Hant :ms :ta]
             :locales  default-locales}
   :i18n    {:strings-dir "i18n"
             :prefix-default? false
             :fallback default-fallback
             :missing-key :warn
             :category-labels {}
             :show-fallback-notice true}
   :content {:dir "content"
             :category true :tag true :archive true
             :category-text "Notes"
             :extend-frontmatter {}
             :permalink-prefix "/pages/"
             :permalink-length 6
             :write-front-matter true
             :permalinks-file "permalinks.edn"
             :assets-dir "assets"}
   :theme   {:default-mode :auto :page-style :card
             :sidebar-open true          ; true → every sidebar group open; false → only the active trail
             :sidebar-depth 2            ; TOC depth: h2–h3 (front matter `sidebarDepth` overrides)
             :per-page 10                ; homepage / category / tag pagination
             :fonts {:tamil :system}}    ; :system | :self-hosted (§6.9, D-P3-12)
   :nav     []
   :search   {:provider :none}           ; :none | :pagefind (§6.7, D-P3-8)
   :comments {:provider :none}
   :analytics {:provider :none}
   :seo     {:sitemap true :hreflang true :x-default :primary :feeds true}
   :build   {:out "dist"}
   ;; D-P3-8: the per-platform hashes for the default version live in
   ;; `clogem.search/known-sha256`, so a site that pins another version
   ;; does not inherit hashes that cannot match it
   :tools   {:pagefind {:version "1.5.2"}}})

;; ---------------------------------------------------------------------------
;; Loading

(defn read-edn-file
  [f]
  (when (fs/exists? f)
    (try
      (edn/read-string (slurp (fs/file f)))
      (catch Exception e
        (throw (ex-info (str "could not parse " f ": " (ex-message e))
                        {:babashka/exit 1} e))))))

(defn- normalize-langs
  "Canonicalize :locales keys and make :priority total.

  A locale key may be written in any case in site.edn; the canonical spelling is
  whatever the site wrote, and that is what `<html lang>` and filename matching
  use (§6.1: compare lowercased, emit the configured spelling)."
  [{:keys [default priority locales] :as langs}]
  (let [;; deep-merge lets an explicit nil win, so `{:locales {:ms nil}}`
        ;; REMOVES a default locale — the only way a site can have fewer
        ;; than the five defaults
        locales  (into {}
                       (comp (remove (fn [[_ v]] (nil? v)))
                             (map (fn [[k v]] [k (merge {:dir :ltr
                                                         :label (name k)
                                                         :html-lang (name k)}
                                                        v)])))
                       locales)
        known    (set (keys locales))
        priority (vec (concat (filterv known priority)
                              (remove (set priority) (keys locales))))]
    (assoc langs
           :locales  locales
           :priority priority
           ;; Repair so downstream code always has a usable default, but keep
           ;; what was declared so validate! can report the mismatch rather than
           ;; silently substituting a language the site never asked for.
           :default-declared default
           :default  (if (known default) default (first priority))
           ;; lowercased tag → canonical keyword, the lookup the scanner uses
           :by-lower (into {} (map (fn [k] [(u/lower (name k)) k])) known))))

(def ^:private theme-int-rules
  "Typed `:theme` integers: [key valid? what-it-must-be]. Render reads both
  as numbers, so a string or keyword used to surface as a raw
  ClassCastException — for `:sidebar-depth`, half-way through writing dist/."
  [[:per-page      #(and (integer? %) (pos? %))    "a positive integer"]
   [:sidebar-depth #(and (integer? %) (<= 0 % 5))  "an integer from 0 to 5"]])

(defn- check-theme!
  "Repair-then-report, like :langs :default: a bad value is a config error AND
  is replaced by its default, so `doctor` can keep going under the repaired
  config. An explicit nil (deep-merge lets it win) means \"use the default\"
  and is not an error — it is how a site un-sets an inherited value."
  [cfg]
  (reduce (fn [cfg [k ok? what]]
            (let [v       (get-in cfg [:theme k])
                  default (get-in defaults [:theme k])]
              (cond
                (nil? v) (assoc-in cfg [:theme k] default)
                (ok? v)  cfg
                :else
                (do (diag/error! nil (str ":theme " k " is " (pr-str v) ", but it must be " what ".")
                                 (str "Using " default " so the rest of the report is readable, "
                                      "but the build will not run."))
                    (assoc-in cfg [:theme k] default)))))
          cfg theme-int-rules))

(defn- check-floor!
  "D-14: the compatibility floor, hard-failed. A floor that is not a
  `MAJOR.MINOR.PATCH(-pre)?` string is itself a config error, repaired to
  \"no floor\" — `\"abc\"` and the number `0.1` used to pass silently."
  [{:keys [generator] :as cfg}]
  (let [floor (:min-version generator)]
    (cond
      (nil? floor) cfg

      (not (and (string? floor) (re-matches u/version-re floor)))
      (do (diag/error! nil (str ":generator :min-version is " (pr-str floor)
                                ", which is not a version string like \"0.1.1\".")
                       (if (and (string? floor) (re-matches u/version-re (subs floor (min 1 (count floor))))
                                (#{\v \V} (first floor)))
                         (str "Drop the leading v: write " (pr-str (subs floor 1))
                              " — the floor is a version, not a tag name (D-14).")
                         "Write the floor as a quoted MAJOR.MINOR.PATCH string (D-14)."))
          (update cfg :generator dissoc :min-version))

      :else
      (let [have (generator-version)]
        (when-not (u/version>= have floor)
          (diag/error! nil (str "this generator is " have " but site.edn requires :generator "
                                ":min-version " floor ".")
                       (str "Bump the `ref:` in publish.yml — that is the authoritative pin "
                            "(DESIGN.md D-14); site.edn carries the floor, not the pin.")))
        cfg))))

(defn- base-path*
  "`base-path`, usable before it is defined."
  [cfg]
  (u/clean-url (or (get-in cfg [:site :base]) "/")))

(defn- check-fallback!
  "D-P3-7: `:i18n :fallback` is a vector of configured language keywords or
  `:site-default`. A bad value is a config error repaired to the default.

  Only a chain the SITE wrote is validated. The built-in default names `:en`,
  which a site may have removed from :locales; that is not the site's
  mistake, and the default is kept as it is rather than filtered — 0.1.1's
  chain, under which a config map's `:en` value (`:title {:ta … :en …}`)
  still beats its first value for a language that has none of its own."
  [cfg]
  (let [v     (get-in cfg [:i18n :fallback])
        known (set (keys (get-in cfg [:langs :locales])))
        ok?   #(or (= :site-default %) (contains? known %))]
    (cond
      (nil? v)
      (assoc-in cfg [:i18n :fallback] default-fallback)

      (= v default-fallback)
      cfg

      (and (vector? v) (every? ok? v))
      cfg

      :else
      (do (diag/error! nil (str ":i18n :fallback is " (pr-str v)
                                ", but it must be a vector of configured language keywords "
                                "or :site-default, e.g. [:site-default :en].")
                       (str "Configured languages: " (pr-str (vec (keys (get-in cfg [:langs :locales]))))
                            ". Using the default so the rest of the report is readable, "
                            "but the build will not run."))
          (assoc-in cfg [:i18n :fallback] default-fallback)))))

(defn- check-site-url!
  "D-P3-1: `:site :url` is the ORIGIN every absolute URL is built on. Blank
  is legal (analyse warns once). Without a scheme every canonical comes out
  relative, so that is an error. A path that repeats `:base` —
  `https://u.github.io/repo` with `:base \"/repo/\"` — doubles the base in
  every URL, so it is a warning naming the fix. A trailing slash is fine."
  [cfg]
  (let [url (some-> (get-in cfg [:site :url]) str str/trim u/blank->nil)]
    (when url
      (let [[_ scheme host path] (re-matches #"(?i)([a-z][a-z0-9+.-]*)://([^/?#\s]+)([^?#]*)?.*" url)
            path (str/replace (str path) #"/+$" "")
            base (str/replace (base-path* cfg) #"/+$" "")]
        (cond
          (not host)
          (diag/error! nil (str ":site :url is " (pr-str url) ", which has no scheme and host.")
                       (str "Write the site's origin with its scheme, e.g. \"https://example.github.io\" "
                            "— without one every canonical, hreflang and feed URL comes out relative."))

          (and (seq base) (seq path) (= (u/lower path) (u/lower base)))
          (diag/warn! nil (str ":site :url " (pr-str url) " repeats the base path " base
                               ", so every absolute URL would carry it twice.")
                      (str "Drop the path: write " (pr-str (str scheme "://" host))
                           " — :site :base already supplies " (base-path* cfg) "."))

          :else nil)))
    cfg))

(defn- check-x-default!
  "`:seo :x-default` says where the hreflang `x-default` points. `:primary`
  — the bare identity URL, the article's own primary language (D-P3-2) — is
  the only value so far; anything else is a config error repaired to it, so
  a future option has a home rather than a silently ignored key."
  [cfg]
  (let [v (get-in cfg [:seo :x-default])]
    (if (or (nil? v) (= :primary v))
      (assoc-in cfg [:seo :x-default] :primary)
      (do (diag/error! nil (str ":seo :x-default is " (pr-str v) ", but the only supported value is :primary.")
                       (str ":primary points x-default at the bare identity URL (DESIGN.md D-P3-2). "
                            "Using it so the rest of the report is readable, but the build will not run."))
          (assoc-in cfg [:seo :x-default] :primary)))))

(defn- check-enum!
  "A keyword option with a closed set of values: anything else is a config
  error repaired to the default, so `doctor` can keep going."
  [cfg path allowed why]
  (let [v (get-in cfg path)
        default (get-in defaults path)]
    (cond
      (nil? v) (assoc-in cfg path default)
      (contains? allowed v) cfg
      :else
      (do (diag/error! nil (str (str/join " " path) " is " (pr-str v) ", but it must be one of "
                                (str/join ", " (map pr-str (sort allowed))) ".")
                       (str why " Using " (pr-str default) " so the rest of the report is readable, "
                            "but the build will not run."))
          (assoc-in cfg path default)))))

(defn- check-search!
  "D-P3-8 / D-P3-12: the search provider, the Pagefind pin, and the Tamil
  font option."
  [cfg]
  (let [cfg (-> cfg
                (check-enum! [:search :provider] #{:none :pagefind}
                             "Pagefind is the only search provider (DESIGN.md §6.7).")
                (check-enum! [:theme :fonts :tamil] #{:system :self-hosted}
                             ":self-hosted serves a subset Noto Sans Tamil from the site (DESIGN.md §6.9)."))
        {:keys [version sha256]} (get-in cfg [:tools :pagefind])]
    (when-not (and (string? version) (re-matches u/version-re version))
      (diag/error! nil (str ":tools :pagefind :version is " (pr-str version)
                            ", which is not a version string like \"1.5.2\".")))
    (when (some? sha256)
      (when-not (and (map? sha256)
                     (every? (fn [[k v]] (and (string? k) (string? v) (re-matches #"(?i)[0-9a-f]{64}" v)))
                             sha256))
        (diag/error! nil (str ":tools :pagefind :sha256 must map each platform to its hex sha256, e.g. "
                              "{\"x86_64-unknown-linux-musl\" \"aeb1…\"}.")
                     (str "One hash cannot cover several release assets (DESIGN.md §11.2, D-P3-8); "
                          "copy each from the release's .sha256 file."))))
    cfg))

(defn- validate!
  [{:keys [langs comments generator] :as cfg}]
  (let [{:keys [locales priority default default-declared]} langs]
    (when (empty? locales)
      (diag/error! nil ":langs :locales is empty — at least one language must be configured."))
    (when-not (contains? locales default-declared)
      (diag/error! nil (str ":langs :default is " (pr-str default-declared)
                            " which is not in :langs :locales " (pr-str (vec (keys locales))))
                   (str "Falling back to " (pr-str default) " so the rest of the report is "
                        "readable, but fix the config — the build will not run.")))
    (doseq [[k v] locales]
      (when (str/blank? (str (:html-lang v)))
        (diag/error! nil (str "locale " k " has no :html-lang — Pagefind and hreflang both need it.")))
      ;; V12: validate whenever present; require only when giscus is actually in use,
      ;; since a :none-comments site has nothing to route.
      (when-let [og (:og v)]
        (when-not (and (string? og) (re-matches og-locale-re og))
          (diag/error! nil (str "locale " k " has :og " (pr-str og)
                                ", which is not an Open Graph locale like \"en_US\".")
                       (str "ogp.me specifies language_TERRITORY (two lower-case letters, `_`, "
                            "two upper-case letters); zh-Hans is \"zh_CN\", zh-Hant \"zh_TW\"."))))
      (when-let [g (:giscus v)]
        (when-not (giscus-available-languages g)
          (diag/error! nil (str "locale " k " has :giscus " (pr-str g)
                                ", which is not one of giscus's availableLanguages.")
                       (str "giscus routes data-lang into the widget's iframe URL, so an "
                            "unroutable value 404s the widget entirely. Use \"en\" as the fallback.")))))
    (when (= :giscus (:provider comments))
      (doseq [[k v] locales]
        (when-not (:giscus v)
          (diag/error! nil (str "locale " k " has no :giscus mapping but :comments :provider is :giscus.")
                       (str "giscus has no " (name k) " locale; map it to \"en\" — this is mandatory, "
                            "not cosmetic (DESIGN.md §6.8)."))))
      (doseq [k [:repo :repo-id :category-id]]
        (when (str/blank? (str (get comments k)))
          (diag/error! nil (str ":comments " k " is required when :provider is :giscus.")))))
    (when (< (count priority) (count locales))
      (diag/warn! nil ":langs :priority does not cover every locale; missing ones were appended.")))
  (-> cfg check-theme! check-floor! check-fallback! check-site-url! check-x-default! check-search!))

(defn load-config
  "Read site config from `site-dir`, deep-merge over defaults and `overrides`,
  normalize and validate. Emits diagnostics; the caller decides when to raise."
  ([site-dir] (load-config site-dir nil nil))
  ([site-dir config-file overrides]
   (let [site-dir  (fs/normalize (fs/absolutize (or site-dir ".")))
         cfg-file  (fs/path site-dir (or config-file "site.edn"))
         from-file (read-edn-file cfg-file)]
     (when-not from-file
       (diag/warn! (str cfg-file) "no site config found; using built-in defaults."))
     ;; `or {}` because deep-merge lets an explicit nil win — an absent source
     ;; must contribute nothing, not blank the defaults.
     (-> (u/deep-merge defaults (or from-file {}) (or overrides {}))
         (update :langs normalize-langs)
         (assoc :clogem/site-dir    (str site-dir)
                :clogem/config-file (str cfg-file)
                :clogem/version     (generator-version))
         validate!))))

;; ---------------------------------------------------------------------------
;; Derived accessors

(defn site-dir      [cfg] (fs/path (:clogem/site-dir cfg)))
(defn content-dir   [cfg] (fs/path (site-dir cfg) (get-in cfg [:content :dir])))
(defn assets-dir    [cfg] (fs/path (site-dir cfg) (get-in cfg [:content :assets-dir])))
(defn strings-dir   [cfg] (fs/path (site-dir cfg) (get-in cfg [:i18n :strings-dir])))
(defn permalinks-file [cfg] (fs/path (site-dir cfg) (get-in cfg [:content :permalinks-file])))
(defn out-dir       [cfg] (fs/path (site-dir cfg) (get-in cfg [:build :out])))

(defn lang-keys     [cfg] (get-in cfg [:langs :priority]))
(defn default-lang  [cfg] (get-in cfg [:langs :default]))
(defn locale        [cfg lang] (get-in cfg [:langs :locales lang]))
(defn html-lang     [cfg lang] (or (:html-lang (locale cfg lang)) (name lang)))

(defn og-locale     [cfg lang] (:og (locale cfg lang)))

(defn fallback-chain
  "The languages a lookup for `lang` tries, in order (§6.5, D-P3-7): `lang`
  itself, then `:i18n :fallback` with `:site-default` resolved to `:langs
  :default`. Distinct, so a chain never asks the same language twice."
  [cfg lang]
  (vec (distinct
        (cons lang
              (map #(if (= :site-default %) (default-lang cfg) %)
                   (or (get-in cfg [:i18n :fallback]) default-fallback))))))

(defn lang-for-suffix
  "Canonical language keyword for a filename suffix, matched case-insensitively
  over the configured set (§6.1). nil when the suffix is not a configured code."
  [cfg suffix]
  (get-in cfg [:langs :by-lower (u/lower suffix)]))

(defn write-front-matter?
  [cfg]
  (boolean (get-in cfg [:content :write-front-matter])))

(defn site-url-root
  "`:site :url` without a trailing slash, or nil when it is blank. Every
  absolute URL (canonical, hreflang, sitemap, feeds, robots.txt — D-P3-1) is
  this plus a base-inclusive path; nil means emit none of them."
  [cfg]
  (some-> (get-in cfg [:site :url]) str str/trim u/blank->nil (str/replace #"/+$" "")))

(defn absolute-url
  "The absolute URL of a base-inclusive site path, percent-encoded per
  segment (D-P2-3), or nil when `:site :url` is blank (D-P3-1)."
  [cfg path]
  (when-let [root (site-url-root cfg)]
    (str root (u/url-encode-path path))))

(defn base-path
  "Site base path, e.g. \"/\" for a user site or \"/clogem-press/\" for a project
  site. Prefixed onto every emitted URL."
  [cfg]
  (u/clean-url (or (get-in cfg [:site :base]) "/")))
