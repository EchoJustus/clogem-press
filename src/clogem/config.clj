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
             :per-page 10}               ; homepage / category / tag pagination
   :nav     []
   :search   {:provider :none}
   :comments {:provider :none}
   :analytics {:provider :none}
   :seo     {:sitemap true :hreflang true :x-default :primary :feeds true}
   :build   {:out "dist"}})

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

(defn- check-fallback!
  "D-P3-7: `:i18n :fallback` is a vector of configured language keywords or
  `:site-default`. A bad value is a config error repaired to the default. The
  built-in default names `:en`, which a site may have removed from :locales;
  that is not the site's mistake, so the default alone is filtered silently."
  [cfg]
  (let [v     (get-in cfg [:i18n :fallback])
        known (set (keys (get-in cfg [:langs :locales])))
        ok?   #(or (= :site-default %) (contains? known %))]
    (cond
      (nil? v)
      (assoc-in cfg [:i18n :fallback] (filterv ok? default-fallback))

      (= v default-fallback)
      (assoc-in cfg [:i18n :fallback] (filterv ok? v))

      (and (vector? v) (every? ok? v))
      cfg

      :else
      (do (diag/error! nil (str ":i18n :fallback is " (pr-str v)
                                ", but it must be a vector of configured language keywords "
                                "or :site-default, e.g. [:site-default :en].")
                       (str "Configured languages: " (pr-str (vec (keys (get-in cfg [:langs :locales]))))
                            ". Using the default so the rest of the report is readable, "
                            "but the build will not run."))
          (assoc-in cfg [:i18n :fallback] (filterv ok? default-fallback))))))

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
  (-> cfg check-theme! check-floor! check-fallback!))

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
