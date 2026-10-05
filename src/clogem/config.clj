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
  "The `data-lang` values giscus routes, verified on 2026-10-01 from
  lib/i18n.tsx and its live routes (DESIGN.md Appendix A item 11):
  `availableLanguages` plus `gsw`, `zh-Hans` and `zh-Hant`, which the widget
  also serves. `ms` and `ta` are not among them — both 404. A `:giscus` value
  outside this set is a build error, because the failure it causes — a 404'd
  iframe and no comment widget at all — is invisible until someone loads the
  page."
  #{"ar" "be" "bg" "ca" "cs" "da" "de" "en" "eo" "es" "eu" "fa" "fr" "gr" "hbs"
    "he" "hu" "id" "it" "ja" "kh" "ko" "nl" "pl" "pt" "ro" "ru" "th" "tr" "uk"
    "uz" "vi" "zh-CN" "zh-TW" "zh-HK" "gsw" "zh-Hans" "zh-Hant"})

(def giscus-repo-re
  "`:comments :repo`: a GitHub `owner/name`, as giscus's data-repo takes it."
  #"[A-Za-z0-9-]+/[A-Za-z0-9._-]+")

(def highlight-defaults
  "§11.3 item 10: highlighting is on by default. A test runner may switch
  the provider in `defaults` off; this map keeps the shipped values."
  {:provider :chroma :line-numbers true :copy-button true
   :style "github" :dark-style "github-dark"})

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
             :show-fallback-notice true
             ;; §6.4 rule 4, D-11, D-P3-14: what a stored language
             ;; preference does on a bare URL — :banner | :redirect | :ignore
             :preference :banner}
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
   :highlight highlight-defaults          ; :provider :chroma | :none (§11.3 item 10)
   :comments {:provider :none}
   :analytics {:provider :none}
   :seo     {:sitemap true :hreflang true :x-default :primary :feeds true}
   :build   {:out "dist"}
   ;; D-P3-8: the per-platform hashes for the default version live in the
   ;; tool's descriptor (`clogem.tools`), so a site that pins another
   ;; version does not inherit hashes that cannot match it. The build runs
   ;; Pagefind and Chroma; the fswatcher pod is fetchable (`bb fetch-tool
   ;; --tool fswatcher`) but not yet used (Phase 4 Task D).
   :tools   {:pagefind  {:version "1.5.2"}
             :chroma    {:version "2.27.0"}
             :fswatcher {:version "0.0.7"}}})

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

(def fatal-tool-ids
  "The tools whose `:version` / `:sha256` pin is validated as an ERROR: the
  ones a build actually runs. Any other pinned tool's bad pin is a warning,
  and the config is repaired to the built-in pin — 0.2.0 accepted any
  `:tools :chroma` (its own DESIGN §5.6 sketched one with a single
  `:sha256` string), so a pin nothing reads must not start failing builds.
  The task that makes a build run a tool adds its id here: Chroma in C
  (§11.3 item 10). The fswatcher pod stays out (Task D): only `bb dev` runs
  it, and a pod it cannot fetch, verify or load only means watching by
  polling."
  #{:pagefind :chroma})

(defn- unused-tool-hint
  "B2's hint for a bad pin of a tool not in `fatal-tool-ids`: why it is only
  a warning, and what is used instead."
  [id hint]
  (str (when hint (str hint " "))
       (if (= :fswatcher id)
         "Only `bb dev` runs the fswatcher pod, and it falls back to polling when it cannot, so the built-in pin is used."
         (str "It has no effect in " (generator-version) " (nothing runs "
              (name id) " yet), so the built-in pin is used."))))

(defn- check-search!
  "D-P3-8 / D-P3-12: the search provider, the Pagefind pin, and the Tamil
  font option."
  [cfg]
  (let [cfg (-> cfg
                (check-enum! [:search :provider] #{:none :pagefind}
                             "Pagefind is the only search provider (DESIGN.md §6.7).")
                (check-enum! [:theme :fonts :tamil] #{:system :self-hosted}
                             ":self-hosted serves a subset Noto Sans Tamil from the site (DESIGN.md §6.9)."))
        tool-pins {:pagefind  ["1.5.2"  "x86_64-unknown-linux-musl" "aeb1…" "the release's .sha256 file"]
                   :chroma    ["2.27.0" "linux-amd64"               "91e1…" "the release's checksums.txt"]
                   :fswatcher ["0.0.7"  "linux-amd64"               "f94c…" "your own sha256 of the zip"]}]
    ;; every pinned tool is validated alike (Phase 4 Task A); only a tool in
    ;; `fatal-tool-ids` turns a bad pin into an error
    (reduce
     (fn [cfg [id [eg-version eg-plat eg-hash source]]]
       (let [{:keys [version sha256]} (get-in cfg [:tools id])
             fatal? (contains? fatal-tool-ids id)
             report! (fn [msg hint]
                       (if fatal?
                         (diag/error! nil msg hint)
                         (diag/warn! nil msg (unused-tool-hint id hint))))
             bad-version? (not (and (string? version) (re-matches u/version-re version)))
             bad-sha256?  (and (some? sha256)
                               (not (and (map? sha256)
                                         (every? (fn [[k v]] (and (string? k) (string? v)
                                                                  (re-matches #"(?i)[0-9a-f]{64}" v)))
                                                 sha256))))]
         (when bad-version?
           (report! (str ":tools " id " :version is " (pr-str version)
                         ", which is not a version string like \"" eg-version "\".")
                    nil))
         (when bad-sha256?
           (report! (str ":tools " id " :sha256 must map each platform to its hex sha256, e.g. "
                         "{\"" eg-plat "\" \"" eg-hash "\"}.")
                    (str "One hash cannot cover several release assets (DESIGN.md §11.2, D-P3-8); "
                         "copy each from " source ".")))
         ;; the pin is one unit: a custom version with its hashes dropped
         ;; has no hash to verify against, and the built-in hashes only
         ;; cover the built-in version — so either half bad resets both,
         ;; keeping every other key (:style, :url, :path …)
         (if (and (or bad-version? bad-sha256?) (not fatal?))
           (update-in cfg [:tools id] #(-> % (assoc :version (get-in defaults [:tools id :version]))
                                           (dissoc :sha256)))
           cfg)))
     cfg tool-pins)))

(defn- check-highlight!
  "§11.3 item 10: `:highlight`. Every bad value is a WARNING, repaired to
  the default — highlighting is cosmetic, and 0.2.0 accepted (and ignored)
  any `:highlight` — except that `:provider` must be :chroma or :none to
  mean anything. A style is checked here for shape only (a Chroma style
  name; nothing path-like reaches `--style`); whether the binary has it is
  checked when the build runs it (`clogem.highlight/session`)."
  [cfg]
  (let [hl (get cfg :highlight)
        ;; a BAD value falls back to the shipped default, not to `defaults`
        ;; (which a test runner may have switched off): the warning says
        ;; what a real build does
        repair (fn [cfg k msg]
                 (let [default (get highlight-defaults k)]
                   (diag/warn! nil (str ":highlight " k " is " (pr-str (get-in cfg [:highlight k])) ", " msg
                                        "; using " (pr-str default) ".")
                               "See config.example.edn (DESIGN.md §11.3 item 10).")
                   (assoc-in cfg [:highlight k] default)))]
    (if-not (map? hl)
      (assoc cfg :highlight (:highlight defaults))
      (reduce (fn [cfg [k ok? msg]]
                (let [v (get-in cfg [:highlight k])]
                  (cond (nil? v) (assoc-in cfg [:highlight k] (get-in defaults [:highlight k]))
                        (ok? v)  cfg
                        :else    (repair cfg k msg))))
              cfg
              [[:provider     #{:chroma :none}  "but it must be :chroma or :none"]
               [:line-numbers boolean?          "but it must be true or false"]
               [:copy-button  boolean?          "but it must be true or false"]
               [:style        #(and (string? %) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" %))
                "which is not a Chroma style name such as \"github\""]
               [:dark-style   #(and (string? %) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" %))
                "which is not a Chroma style name such as \"github-dark\""]]))))

(def comments-keys
  "The `:comments` keys the generator reads (D-P3-15). `:mapping` is
  accepted too: §5.6 used to sketch `:mapping :permalink`, and real site.edn
  files carry it, but the mapping is not configurable — every thread is
  keyed on the article's permalink."
  #{:provider :repo :repo-id :category :category-id :mapping})

;; ---------------------------------------------------------------------------
;; Known keys (Phase 4 Task A, part of D-P4-16)

(def known-keys
  "Every config key the generator knows, as {path → {key status}}: `path` is
  the vector of keys leading to a map in site.edn (`[]` is the top level),
  and `status` is

    :ok      — read by this generator;
    :planned — documented (DESIGN.md §5.6, §8 Phase 4) but not implemented
               yet: accepted, with a warning that it has no effect.

  A path segment `:*` matches any key — `[:langs :locales :*]` is every
  locale's map. A map whose path has NO entry here holds user data rather
  than options (`:langs :locales` itself, `:i18n :category-labels`,
  `:content :extend-frontmatter`, `:theme :html-modules`, a `:sha256` map)
  and its keys are not checked.

  An unknown key is a WARNING, never an error (`check-keys!`): it names the
  path and suggests the nearest known key. **A change that makes the
  generator read a new key adds it here** — as `:ok`, or flips a `:planned`
  entry to `:ok` — or every site that sets it gets a warning saying it is
  ignored. The vdoing spellings live in `vdoing-keys`."
  {[]                     {:site :ok :generator :ok :langs :ok :i18n :ok :content :ok
                           :theme :ok :nav :ok :search :ok :comments :ok :analytics :ok
                           :seo :ok :build :ok :tools :ok
                           ;; Phase 4 Task C (§11.3 item 10)
                           :highlight :ok}
   [:site]                {:title :ok :description :ok :url :ok :base :ok :author :ok}
   ;; `:site :author` is checked by `check-author!`: only its {:name :link}
   ;; shape holds options; a per-language map holds user data
   [:generator]           {:repo :ok :min-version :ok}
   [:langs]               {:default :ok :priority :ok :locales :ok}
   [:langs :locales :*]   {:label :ok :html-lang :ok :giscus :ok :dir :ok :og :ok}
   [:i18n]                {:strings-dir :ok :prefix-default? :ok :fallback :ok :missing-key :ok
                           :category-labels :ok :show-fallback-notice :ok :preference :ok}
   [:content]             {:dir :ok :assets-dir :ok :category :ok :tag :ok :archive :ok
                           :category-text :ok :extend-frontmatter :ok :permalink-prefix :ok
                           :permalink-length :ok :write-front-matter :ok :permalinks-file :ok
                           ;; Phase 4 Tasks E2 and F
                           :edit-link :planned :static-dir :planned}
   [:theme]               {:default-mode :ok :page-style :ok :sidebar-open :ok :sidebar-depth :ok
                           :per-page :ok :fonts :ok
                           ;; read, but a non-empty value has no effect yet (check-no-effect!)
                           :html-modules :ok
                           ;; Phase 4 Tasks B1, E1, E2 (DESIGN.md §5.6 sketches several)
                           :social :planned :banner-bg :planned :body-bg-img :planned
                           :body-bg-img-opacity :planned :body-bg-img-interval :planned
                           :title-badge :planned
                           :title-badge-icons :planned :blogger :planned :footer :planned
                           :update-bar :planned :right-menu-bar :planned :page-button :planned
                           :content-bg-style :planned :last-updated :planned
                           :sidebar-collapsed :planned :back-to-top :planned :logo :planned
                           :repo :planned}
   [:theme :fonts]        {:tamil :ok}
   [:highlight]           {:provider :ok :line-numbers :ok :copy-button :ok :style :ok :dark-style :ok}
   [:search]              {:provider :ok
                           ;; Phase 4 Task G
                           :cross-language :planned}
   [:comments]            (zipmap comments-keys (repeat :ok))
   ;; the provider's own keys, as §5.6 sketches them (D-13); a provider
   ;; other than :none has no effect yet (check-no-effect!)
   [:analytics]           {:provider :ok :id :ok :domain :ok :src :ok :website-id :ok}
   [:seo]                 {:sitemap :ok :hreflang :ok :x-default :ok :feeds :ok :indexnow :ok
                           ;; Phase 4 Task F
                           :verification :planned}
   [:seo :indexnow]       {:enabled :ok :key :ok}
   [:build]               {:out :ok}
   [:tools]               {:cache-dir :ok :pagefind :ok :chroma :ok :fswatcher :ok}
   [:tools :pagefind]     {:version :ok :sha256 :ok :path :ok :url :ok}
   [:tools :chroma]       {:version :ok :sha256 :ok :path :ok :url :ok}
   [:tools :fswatcher]    {:version :ok :sha256 :ok :path :ok :url :ok}
   ;; one `:nav` item, at `:nav 0`, `:nav 1 :items 0`, … (`check-nav!`);
   ;; `:text` is a per-language user map and is not walked
   [:nav :*]              {:text :ok :link :ok :items :ok}})

(def author-keys
  "The keys of `:site :author`'s named shape, as `i18n/resolve-author` reads
  them: keywords or strings."
  #{:name :link "name" "link"})

(def retired-keys
  "Keys a design revision removed, as {path → [message hint]}: their own
  warning, in place of the nearest-key suggestion. Still only warnings."
  {[:generator :ref]
   [(str ":generator :ref was removed in design v2.1 (D-14); the generator version is pinned only by "
         "publish.yml's `ref:`. It is ignored.")
    "Delete it; to require a minimum generator, set :generator :min-version."]
   [:tools :chroma :style]
   [(str ":tools :chroma :style belongs under :highlight :style (DESIGN.md §5.6, corrected in §11.3 "
         "item 10). It is ignored.")
    "Move it to :highlight {:style …}."]
   [:tools :chroma :dark-style]
   [(str ":tools :chroma :dark-style belongs under :highlight :dark-style (DESIGN.md §5.6, corrected in "
         "§11.3 item 10). It is ignored.")
    "Move it to :highlight {:dark-style …}."]})

(def vdoing-keys
  "vdoing's `themeConfig` spellings (camelCase, and a few that live in
  another section here) → [the clogem-press path, its status, an optional
  note]. Status is `known-keys`' :ok or :planned, or

    :dropped  — an option Phase 4 removed (DESIGN.md §8);
    :deferred — one deferred past v1 (DESIGN.md §8's deferred list);
    :implicit — what clogem-press always does, so there is nothing to set.

  Consulted only for a key that is not known where it was written, so a
  site migrated from vdoing gets the spelling to use. Every key vdoing's
  `themeConfig` declares (vdoing/types/index.ts) is here."
  {"pageStyle"               [[:theme :page-style] :ok]
   "defaultMode"             [[:theme :default-mode] :ok]
   ;; vdoing's sidebarOpen says whether the sidebar PANEL starts open
   ;; (Layout.vue `created()`: `sidebarOpen === false` closes it); clogem's
   ;; :sidebar-open opens every group, which is vdoing's
   ;; `sidebar.collapsable` inverted (D-P4-16)
   "sidebarOpen"             [[:theme :sidebar-collapsed] :planned
                              "with the opposite sense: sidebarOpen false is :sidebar-collapsed true"]
   "sidebarDepth"            [[:theme :sidebar-depth] :ok]
   "categoryText"            [[:content :category-text] :ok]
   "extendFrontmatter"       [[:content :extend-frontmatter] :ok]
   "category"                [[:content :category] :ok]
   "tag"                     [[:content :tag] :ok]
   "archive"                 [[:content :archive] :ok]
   "author"                  [[:site :author] :ok]
   "nav"                     [[:nav] :ok]
   "search"                  [[:search :provider] :ok]
   "htmlModules"             [[:theme :html-modules] :planned]
   "bodyBgImg"               [[:theme :body-bg-img] :planned]
   "bodyBgImgOpacity"        [[:theme :body-bg-img-opacity] :planned]
   "bodyBgImgInterval"       [[:theme :body-bg-img-interval] :planned]
   "bannerBg"                [[:theme :banner-bg] :planned]
   "titleBadge"              [[:theme :title-badge] :planned]
   "titleBadgeIcons"         [[:theme :title-badge-icons] :planned]
   "blogger"                 [[:theme :blogger] :planned]
   "social"                  [[:theme :social] :planned]
   "footer"                  [[:theme :footer] :planned]
   "updateBar"               [[:theme :update-bar] :planned]
   "rightMenuBar"            [[:theme :right-menu-bar] :planned]
   "pageButton"              [[:theme :page-button] :planned]
   "contentBgStyle"          [[:theme :content-bg-style] :planned]
   "lastUpdated"             [[:theme :last-updated] :planned]
   "logo"                    [[:theme :logo] :planned]
   "repo"                    [[:theme :repo] :planned]
   "editLinks"               [[:content :edit-link] :planned]
   "editLinkText"            [[:content :edit-link] :planned]
   "docsRepo"                [[:content :edit-link] :planned]
   "docsDir"                 [[:content :edit-link] :planned]
   "docsBranch"              [[:content :edit-link] :planned]
   "algolia"                 [nil :deferred "search is Pagefind's (:search :provider :pagefind)"]
   "searchMaxSuggestions"    [nil :dropped]
   "displayAllHeaders"       [nil :dropped]
   "sidebarHoverTriggerOpen" [nil :dropped]
   "sidebar"                 [nil :implicit]})

(defn a-or-an
  "`word` with its indefinite article: \"an analytics\", \"a locale\"."
  [word]
  (str (if (re-find #"(?i)^[aeiou]" (str word)) "an " "a ") word))

(defn- path-str
  [path]
  (str/join " " (map #(if (keyword? %) (str %) (pr-str %)) path)))

(defn- entry-for
  "The `known-keys` entry for `path`, honouring `:*` segments, or nil."
  [path]
  (or (get known-keys path)
      (some (fn [[p m]]
              (when (and (= (count p) (count path))
                         (every? true? (map #(or (= :* %1) (= %1 %2)) p path)))
                m))
            known-keys)))

(defn- section-label
  [path]
  (cond
    (empty? path)                 "top-level"
    (= :nav (first path))         "nav item"
    (= [:langs :locales] (vec (take 2 path))) "locale"
    :else (str/join " " (map #(if (keyword? %) (name %) (str %)) path))))

(defn- key-name [k] (if (keyword? k) (name k) (str k)))

(defn- warn-unknown!
  [path k known]
  (let [kn  (key-name k)
        p   (path-str (conj path k))
        [target status note] (get vdoing-keys kn)
        retired (get retired-keys (conj path k))
        nearest (when-not (or status retired)
                  (some->> (keys known)
                           (map (fn [c] [(u/damerau-levenshtein (u/lower kn) (u/lower (key-name c))) (str c) c]))
                           (filter #(<= (first %) 2))
                           sort first last))]
    (cond
      retired
      (diag/warn! nil (first retired) (second retired))

      (#{:ok :planned} status)
      (diag/warn! nil (str p " is vdoing's spelling; clogem-press reads " (path-str target)
                           (when note (str " (" note ")"))
                           (when (= :planned status)
                             (str " — planned, not implemented in " (generator-version)))
                           ". It is ignored.")
                  (str "Write " (path-str target) " instead (DESIGN.md §5.6)."))

      (= :dropped status)
      (diag/warn! nil (str p " is a vdoing option clogem-press does not have "
                           "(dropped in Phase 4, DESIGN.md §8)" (when note (str "; " note))
                           "; it is ignored."))

      (= :deferred status)
      (diag/warn! nil (str p " is a vdoing option clogem-press defers past v1 (DESIGN.md §8)"
                           (when note (str "; " note)) "."))

      (= :implicit status)
      (diag/warn! nil (str p " is not needed: clogem-press always generates the structured sidebar "
                           "from the numbered directory tree (vdoing's 'structuring' mode); it is ignored.")
                  (str "Remove it. Custom sidebar arrays and sidebar: 'auto' are not supported "
                       "(DESIGN.md §8). vdoing's `collapsable: false` corresponds to :theme :sidebar-open true."))

      :else
      (diag/warn! nil (str p " is not " (a-or-an (section-label path)) " option and is ignored.")
                  (str (when nearest (str "Did you mean " nearest "? "))
                       "The options are "
                       (str/join ", " (map str (sort-by str (keep (fn [[k st]] (when (= :ok st) k)) known))))
                       " (config.example.edn).")))))

(defn- check-author!
  "`:site :author` holds options only in its named shape — {:name … :link …},
  keyword or string keys, as `i18n/resolve-author` reads it. A per-language
  map ({:en \"Jane\" :zh-Hans \"简\"}) is user data, and a string is never
  checked."
  [m path]
  (when (and (map? m) (or (contains? m :name) (contains? m "name")))
    (doseq [k (sort-by str (keys m))
            :when (not (contains? author-keys k))]
      (warn-unknown! path k {:name :ok :link :ok}))))

(defn- check-nav!
  "Walk `:nav` items (`known-keys` `[:nav :*]`) at `:nav 0`, `:nav 1 :items
  0`, …, recursing into `:items` — never into `:text`, a per-language map."
  [items path]
  (when (sequential? items)
    (doseq [[i item] (map-indexed vector items)
            :let [p (conj path i)]
            :when (map? item)]
      (let [known (get known-keys [:nav :*])]
        (doseq [k (sort-by str (keys item))
                :when (not (contains? known k))]
          (warn-unknown! p k known)))
      (check-nav! (:items item) (conj p :items)))))

(defn check-keys!
  "Warn about every key of `m` (a site.edn as read, before defaults are
  merged in) that `known-keys` does not know, or knows as :planned. Never an
  error: a key 0.2.0 ignored silently must not start failing a build.
  Returns nil."
  ([m] (check-keys! m []))
  ([m path]
   (cond
     (= [:nav] path)          (check-nav! m path)
     (= [:site :author] path) (check-author! m path)
     (map? m)
     (if-let [known (entry-for path)]
       (doseq [k (sort-by str (keys m))
               :let [v (get m k)]]
         (case (get known k)
           :ok      (check-keys! v (conj path k))
           :planned (diag/warn! nil (str (path-str (conj path k)) " is planned, not implemented in "
                                         (generator-version) "; it has no effect.")
                                "DESIGN.md §8 Phase 4 lists what is coming.")
           (warn-unknown! path k known)))
       ;; a map of user-named entries whose VALUES are options (locales)
       (when (entry-for (conj path :*))
         (doseq [k (sort-by str (keys m))]
           (check-keys! (get m k) (conj path k))))))
   nil))

(defn- check-no-effect!
  "Settings config.example.edn documents whose implementation is still to
  come: set to anything but their default, they warn that they have no
  effect in this version rather than being silently ignored."
  [cfg]
  (let [v (generator-version)]
    (when (let [hm (get-in cfg [:theme :html-modules])]
            ;; 0.2.0 accepted any value, `false` and `:none` included
            (if (coll? hm) (seq hm) (some? hm)))
      (diag/warn! nil (str ":theme :html-modules has no effect in " v "; nothing is injected.")
                  "htmlModules support is planned for Phase 4 (DESIGN.md §8)."))
    (let [p (get-in cfg [:analytics :provider])]
      (when (and (some? p) (not= :none p))
        (diag/warn! nil (str ":analytics :provider is " (pr-str p) ", which has no effect in " v
                             "; no analytics script is emitted.")
                    "Analytics support is planned for Phase 4 (DESIGN.md §8).")))
    (when (true? (get-in cfg [:seo :indexnow :enabled]))
      (diag/warn! nil (str ":seo :indexnow :enabled true has no effect in " v
                           "; nothing is pushed to IndexNow.")
                  "The IndexNow push is planned for Phase 5 (DESIGN.md §8).")))
  cfg)

(defn- check-comments-keys!
  "A `:mapping` other than :permalink is a warning: it asks for a thread
  mapping the generator does not do. `:mapping :permalink` is accepted
  quietly: it describes exactly what happens. (An unknown `:comments` key
  is `check-keys!`'s.)"
  [cfg]
  (let [c (:comments cfg)]
    (when (and (map? c) (contains? c :mapping) (not= :permalink (:mapping c)))
      (diag/warn! nil (str ":comments :mapping is " (pr-str (:mapping c))
                           ", but threads are always mapped by the article's permalink; it is ignored.")
                  (str "Every variant of an article opens one thread, keyed on /pages/xxxxxx/ "
                       "(DESIGN.md D-P3-15). Remove :mapping, or write :permalink."))))
  cfg)

(def theme-modes
  "`:theme :default-mode` values: the four colour modes of §1.2, each a
  `.theme-mode-*` block in theme.css, on `<html>` since Phase 4 B1 (:light
  is also the :root palette)."
  #{:auto :light :dark :read})

(def page-styles
  "`:theme :page-style` values (vdoing's `pageStyle`). Phase 4 B2 styles
  them; B1 stamps the class `theme-style-*` on `<html>`."
  #{:card :line})

(defn- check-page-style!
  "`:theme :page-style` outside `page-styles` is a WARNING, repaired to
  :card: 0.2.0 accepted any value and stamped it as a class, so a site that
  set one must not start failing."
  [cfg]
  (let [v (get-in cfg [:theme :page-style])]
    (cond
      (nil? v) (assoc-in cfg [:theme :page-style] (get-in defaults [:theme :page-style]))
      (contains? page-styles v) cfg
      :else
      (do (diag/warn! nil (str ":theme :page-style is " (pr-str v) ", but it must be one of "
                               (str/join ", " (map pr-str (sort page-styles))) "; using :card.")
                      "It names vdoing's page style: :card (content on cards) or :line (DESIGN.md §1.2).")
          (assoc-in cfg [:theme :page-style] :card)))))

(defn- check-i18n-comments!
  "D-P3-14 / D-P3-15: the stored-preference behaviour, the comments
  provider and its keys, and the colour mode the page (and giscus) starts
  in — each a closed set."
  [cfg]
  (-> cfg
      (check-enum! [:i18n :preference] #{:banner :redirect :ignore}
                   "It says what a stored language preference does on a bare URL (DESIGN.md §6.4 rule 4, D-11).")
      (check-enum! [:comments :provider] #{:none :giscus}
                   "giscus is the only comments provider (DESIGN.md §6.8).")
      check-comments-keys!
      (check-enum! [:theme :default-mode] theme-modes
                   "It names the colour mode a page starts in (DESIGN.md §1.2, §6.8).")
      check-page-style!))

(defn- real-path
  "`p` absolute, normalized, and with every existing link resolved — so a
  symlink cannot hide that two paths are one directory."
  [p]
  (fs/path (.getCanonicalPath (fs/file (fs/normalize (fs/absolutize p))))))

(defn- within?
  "Is `p` equal to `dir` or inside it?"
  [dir p]
  (let [d (str dir) s (str p)]
    (or (= d s) (str/starts-with? s (str (str/replace d #"[/\\\\]+$" "") java.io.File/separator)))))

(defn- check-out-dir!
  "§11.2 item 46: a build deletes `.html` files in its output directory that
  it did not write, so the output directory must hold nothing but output. It
  is a config error, before anything is written, when `:build :out` resolves
  to the site directory or an ancestor of it, or to a directory holding the
  content directory or a `site.edn`. Repaired to the default so `doctor` can
  keep going."
  [cfg]
  (let [site    (real-path (:clogem/site-dir cfg))
        out-raw (get-in cfg [:build :out])
        out     (when (u/blank->nil (str out-raw)) (real-path (fs/path site (str out-raw))))
        content (real-path (fs/path site (str (get-in cfg [:content :dir]))))
        cfg-name (str (fs/file-name (or (:clogem/config-file cfg) "site.edn")))
        why (cond
              (nil? out)                 "is blank"
              (within? out site)         (if (= (str out) (str site))
                                           "is the site directory itself"
                                           (str "is " out ", which contains the site directory"))
              (within? out content)      (str "contains the content directory " content)
              (or (fs/exists? (fs/path out "site.edn"))
                  (fs/exists? (fs/path out cfg-name)))
              (str "is " out ", which holds a " cfg-name " — another site's directory"))]
    (if why
      (do (diag/error! nil (str ":build :out (" (pr-str out-raw) ") " why "; refusing to build there.")
                       (str "A build removes .html files it did not write from its output directory "
                            "(DESIGN.md §11.2 item 46), so it must be a directory of its own, "
                            "such as the default \"dist\"."))
          (assoc-in cfg [:build :out] (get-in defaults [:build :out])))
      cfg)))

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
      ;; D-P3-15: `data-category` is the category's NAME, which giscus
      ;; shows; §6.8's snippet carried it but config never asked for it
      (doseq [k [:repo :repo-id :category :category-id]]
        (when (str/blank? (str (get comments k)))
          (diag/error! nil (str ":comments " k " is required when :provider is :giscus."))))
      ;; giscus wants `owner/name`; a URL or a bare name builds and then
      ;; breaks the widget on every page at runtime
      (let [repo (:repo comments)]
        (when (and (not (str/blank? (str repo)))
                   (not (and (string? repo) (re-matches giscus-repo-re repo))))
          (diag/error! nil (str ":comments :repo is " (pr-str repo)
                                ", which is not a GitHub repository like \"owner/name\".")
                       (str "giscus takes the repository as owner/name — no URL, no spaces "
                            "(e.g. \"EchoJustus/EchoJustus.github.io\").")))))
    (when (< (count priority) (count locales))
      (diag/warn! nil ":langs :priority does not cover every locale; missing ones were appended.")))
  (-> cfg check-theme! check-floor! check-fallback! check-site-url! check-x-default! check-search!
      check-i18n-comments! check-out-dir! check-no-effect! check-highlight!))

(defn- map-paths
  "Every path in `m` whose value is a map, outermost first."
  ([m] (map-paths m []))
  ([m prefix]
   (mapcat (fn [[k v]]
             (when (map? v)
               (cons (conj prefix k) (map-paths v (conj prefix k)))))
           m)))

(defn- check-shapes!
  "A section the defaults hold as a map must be a map: `:search :pagefind`
  or `:theme {:fonts :self-hosted}` used to reach a later `assoc-in` and
  crash with a ClassCastException. A config error, repaired to the default
  so `doctor` can keep going. nil is allowed — deep-merge lets it un-set a
  section, and every reader falls back to the default. A tool nothing runs
  yet (`:tools :fswatcher`, not in `fatal-tool-ids`) and `:highlight` get a
  warning instead: 0.2.0 accepted any value there."
  [cfg]
  (reduce (fn [cfg path]
            (let [v (get-in cfg path ::absent)]
              (if (or (= ::absent v) (nil? v) (map? v) (not (map? (get-in cfg (pop path)))))
                cfg
                (let [msg (str (str/join " " path) " is " (pr-str v) ", but it must be a map, e.g. "
                               (pr-str (get-in defaults path)) ".")]
                  ;; 0.2.0 accepted any value for a tool nothing runs
                  ;; (`:tools {:chroma "2.27.0"}`): a warning, as for a bad pin
                  (cond
                    (and (= 2 (count path)) (= :tools (first path))
                         (not (contains? fatal-tool-ids (second path))))
                    (diag/warn! nil msg (unused-tool-hint (second path) nil))
                    ;; 0.2.0 accepted any :highlight (it was planned, and
                    ;; ignored); a cosmetic option stays a warning
                    (= [:highlight] path)
                    (diag/warn! nil msg "Using the default.")
                    :else
                    (diag/error! nil msg "Using the default so the rest of the report is readable, but the build will not run."))
                  (assoc-in cfg path (get-in defaults path))))))
          cfg (map-paths defaults)))

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
     ;; D-P4-16: what the site wrote, before defaults or CLI overrides
     (check-keys! from-file)
     ;; `or {}` because deep-merge lets an explicit nil win — an absent source
     ;; must contribute nothing, not blank the defaults.
     (-> (u/deep-merge defaults (or from-file {}) (or overrides {}))
         check-shapes!
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
