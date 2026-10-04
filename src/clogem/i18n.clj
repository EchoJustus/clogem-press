;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.i18n
  "UI strings and the string-or-map config resolver (DESIGN.md §6.5).

  One rule, applied uniformly to theme chrome and to config values alike:

      requested lang → :i18n :fallback (default [:site-default :en]) → the key itself

  `:site-default` in the chain is `:langs :default` (D-P3-7). The last step
  renders as ⟦:page/toc⟧ in dev (loud) and as the key's name in production
  (quiet). Interpolation is `{{var}}` only — no pluralization
  machinery, deliberately (§6.5)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.util :as u]))

(defn theme-strings
  [lang]
  (or (some-> (io/resource (str "clogem/theme/resources/i18n/" (name lang) ".edn"))
              slurp edn/read-string)
      {}))

(defn site-strings
  [cfg lang]
  (let [f (fs/path (config/strings-dir cfg) (str (name lang) ".edn"))]
    (if (fs/exists? f)
      (or (edn/read-string (slurp (fs/file f))) {})
      {})))

(defn load-strings
  "Theme defaults deep-merged with the site's overrides, per language."
  [cfg]
  (into {}
        (map (fn [lang] [lang (u/deep-merge (theme-strings lang) (site-strings cfg lang))]))
        (config/lang-keys cfg)))

(defn interpolate
  [s vars]
  (str/replace (str s) #"\{\{\s*([A-Za-z0-9_-]+)\s*\}\}"
               (fn [[whole k]]
                 (let [v (get vars (keyword k) (get vars k))]
                   (if (some? v) (str v) whole)))))

(defn string-file
  "The site's string file for `lang`, as a site-relative path: `i18n/ta.edn`."
  [cfg lang]
  (str (get-in cfg [:i18n :strings-dir]) "/" (name lang) ".edn"))

(def ^:dynamic *missing-keys*
  "An atom of the [key lang] pairs `tr` found no string for, bound by
  `reporting-missing-keys` so each is reported once per build rather than
  once per call (a key the theme reads on every page used to give one line
  per page). Rendering workers inherit the binding (`bound-fn`)."
  nil)

(defn- missing-key-warning!
  [cfg lang k]
  (diag/warn! (string-file cfg lang)
              (str "missing UI string key `" k "` for " (name lang) ": no language along its fallback chain ("
                   (str/join " → " (map name (config/fallback-chain cfg lang))) ") defines it.")
              (str "Add it to " (string-file cfg lang) ". Pages show ⟦" k "⟧ in `bb dev` and `" (name k)
                   "` in a build; :i18n {:missing-key :silent} turns this warning off.")))

(defn report-missing-keys!
  "One warning per [key lang] in `missing`, sorted, naming the string file."
  [cfg missing]
  (doseq [[k lang] (sort-by (fn [[k l]] [(name l) (str k)]) missing)]
    (missing-key-warning! cfg lang k)))

(defmacro reporting-missing-keys
  "Run body collecting the keys `tr` misses, then warn once per (key,
  language) (§6.5, §11.3 item 12) — in `build`, `doctor` and `dev` alike,
  unless `:i18n :missing-key` is `:silent`."
  [cfg & body]
  `(let [seen# (atom #{})
         r#    (binding [*missing-keys* seen#] ~@body)]
     (report-missing-keys! ~cfg @seen#)
     r#))

(defn- note-missing!
  [cfg lang k]
  (when-not (= :silent (get-in cfg [:i18n :missing-key]))
    (if *missing-keys*
      (swap! *missing-keys* conj [k lang])
      (missing-key-warning! cfg lang k))))

(defn tr
  "Resolve a UI string key for `lang`, applying the fallback chain. A key no
  language along it defines renders as ⟦key⟧ in dev (loud) and as the key's
  name in a build (quiet), and is warned about either way (`note-missing!`)."
  ([ctx k] (tr ctx k nil))
  ([{:keys [strings lang cfg dev?]} k vars]
   (let [chain (config/fallback-chain cfg lang)
         hit   (some (fn [l] (get-in strings [l k])) chain)]
     (cond
       hit   (interpolate hit vars)
       :else (do (note-missing! cfg lang k)
                 (if dev? (str "⟦" k "⟧") (str (name k))))))))

(defn resolved-lang
  "The language `tr` takes key `k` from for `lang` — the first along the
  fallback chain that defines it — or nil when none does."
  [{:keys [strings lang cfg]} k]
  (some (fn [l] (when (some? (get-in strings [l k])) l))
        (config/fallback-chain cfg lang)))

(defn resolve-str
  "Config values may be a plain string (same in all languages) or a map keyed by
  language code. One rule to learn (§5.6)."
  [{:keys [lang cfg]} v]
  (cond
    (nil? v)    nil
    (string? v) v
    (map? v)    (let [chain (config/fallback-chain cfg lang)]
                  (or (some #(get v %) chain)
                      (first (vals v))))
    :else       (str v)))

(defn resolve-author
  "An `author` value — front matter's or `:site :author` — as {:name :link}
  for `lang`, or nil. Three shapes: a string; `{:name … :link …}`, whose
  `:name` may itself be per-language; or a per-language map
  `{:en \"Jane\" :zh-Hans \"简\"}`, resolved like every config string."
  [ctx a]
  (let [named? (and (map? a) (or (contains? a :name) (contains? a "name")))
        nm     (if named? (or (:name a) (get a "name")) a)
        nm     (u/blank->nil (str (resolve-str ctx nm)))]
    (when nm
      (cond-> {:name nm}
        named? (assoc :link (or (:link a) (get a "link")))))))

;; ---------------------------------------------------------------------------
;; Site string checks (D-P3-6)

(def ^:private theme-langs
  "The languages the theme ships string files for."
  [:en :zh-Hans :zh-Hant :ms :ta])

(defn theme-keys
  "Every key some theme string file defines."
  [cfg]
  (into #{} (mapcat (comp keys theme-strings))
        (distinct (concat theme-langs (config/lang-keys cfg)))))

(defn- nearest-key
  "The theme key closest to `k` by Damerau-Levenshtein over its printed form,
  within distance 2 — ties to the alphabetically first, so the hint is stable."
  [k known]
  (let [s (u/lower (str k))]
    (some->> known
             (map (fn [t] [(u/damerau-levenshtein s (u/lower (str t))) (str t) t]))
             (filter #(<= (first %) 2))
             sort first peek)))

(defn check-site-strings!
  "Two warnings over the site's `i18n/<lang>.edn` files (D-P3-6), raised in
  analyse so `build` and `doctor` both report them:

    - a key no theme file defines and that is within two edits of one that
      does is a typo — it would silently never be read;
    - any other key no theme file defines is site-added, and is reported
      when some configured language lacks it, since only the fallback chain
      would stand between that language and a `⟦key⟧`."
  [cfg]
  (let [known (theme-keys cfg)
        langs (config/lang-keys cfg)
        site  (into {} (map (fn [l] [l (site-strings cfg l)])) langs)
        file  #(str (get-in cfg [:i18n :strings-dir]) "/" (name %) ".edn")
        added (atom (sorted-map))]
    (doseq [l langs
            k (sort-by str (keys (get site l)))
            :when (not (contains? known k))]
      (if-let [near (nearest-key k known)]
        (diag/warn! (file l) (str "unknown UI string key `" k "` in " (file l)
                                  " — did you mean `" near "`?")
                    "No theme template reads this key, so the string is never shown.")
        (swap! added update k (fnil conj []) l)))
    (doseq [[k have] @added
            :let [missing (remove (set have) langs)]
            :when (seq missing)]
      (diag/warn! (file (first have))
                  (str "site-added UI string key `" k "` is defined in "
                       (str/join ", " (map file have)) " but missing for "
                       (str/join ", " (map name missing)) ".")
                  (str "Add it to " (str/join ", " (map file missing))
                       ", or those languages fall back along :i18n :fallback.")))
    nil))
