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

(def ^:dynamic *session-keys*
  "nil, or an atom of the [key lang] pairs the previous build of a `bb dev`
  session found missing, bound by dev around each rebuild: a rebuild warns
  only about pairs that were not missing last time, so one missing key is
  not the same warnings again on every save — and is warned about again if
  it is defined and then goes missing once more."
  nil)

(defn report-missing-keys!
  "One warning per [key lang] in `missing`, sorted, naming the string file —
  under `*session-keys*`, only the pairs the previous build did not miss."
  [cfg missing]
  (let [fresh (if-let [prev *session-keys*]
                (let [before @prev]
                  (reset! prev (set missing))
                  (remove before missing))
                missing)]
    (doseq [[k lang] (sort-by (fn [[k l]] [(name l) (str k)]) fresh)]
      (missing-key-warning! cfg lang k))))

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
;; Language names (the owner's format, 2026-11-01; DESIGN.md §6.5)

(defn lang-name
  "Language `l`'s name in the page's language: `l` in the page language's
  `:lang/names` (the theme's string file and the site's, merged). Not along
  the fallback chain: the bracket is the name in the language the reader
  chose, and a name in another language is not that. nil unless it is a
  non-blank string."
  [{:keys [strings lang]} l]
  (let [v (get-in strings [lang :lang/names l])]
    (when (string? v) (u/blank->nil (str/trim v)))))

(defn- holds-word?
  "Does `s` hold `w` as a whole word: case-insensitively, with no letter,
  mark or digit on either side? \"Malay\" is not a word of \"Malaysia\"."
  [s w]
  (boolean (re-find (re-pattern (str "(?iu)(?<![\\p{L}\\p{M}\\p{N}])"
                                     (java.util.regex.Pattern/quote w)
                                     "(?![\\p{L}\\p{M}\\p{N}])"))
                    s)))

(defn lang-gloss
  "What a language list adds in brackets after language `l`'s `:label` on
  this page: `l`'s name in the page's language (`lang-name`). nil for the
  page's own language, for a language the page's has no name for, and for
  one whose `:label` already holds that name as a whole word (a label set
  to `简体中文 (Simplified Chinese)`, or to the name itself)."
  [{:keys [cfg lang] :as ctx} l]
  (when (not= l lang)
    (when-let [n (lang-name ctx l)]
      (when-not (holds-word? (str (get-in cfg [:langs :locales l :label])) n)
        n))))

(defn lang-label-text
  "Language `l` as the language lists name it, as text: its `:label`, then
  `lang-gloss` in brackets. `简体中文 (Simplified Chinese)` on an English
  page, `English (英文)` on a Simplified Chinese one."
  [{:keys [cfg] :as ctx} l]
  (let [own (str (get-in cfg [:langs :locales l :label]))]
    (if-let [g (lang-gloss ctx l)] (str own " (" g ")") own)))

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

(defn- check-lang-names!
  "The language names in one site string file (2026-11-01): `:lang/names`
  is a map from configured language codes to non-blank strings, and
  `:lang/name`, this language's own name, is a string. Anything else is
  silently never shown, so it is reported, with the code meant when a
  configured one is that key in another case, or, for a key of five
  characters or more, within two edits of it (two-letter codes are all
  within two edits of one another)."
  [cfg file m]
  (let [langs (config/lang-keys cfg)
        named (when (map? m) (get m :lang/names ::absent))
        own   (when (map? m) (:lang/name m))]
    (when (and (some? own) (not (string? own)))
      (diag/warn! file (str "`:lang/name` in " file " is " (pr-str own)
                            ", but it is this language's own name, a string.")
                  "The names of the other languages go in `:lang/names`, a map: {:lang/names {:en \"English\"}}."))
    (cond
      (or (nil? named) (= ::absent named)) nil
      (not (map? named))
      (diag/warn! file (str "`:lang/names` in " file " is " (pr-str named) ", not a map; no names are taken from it.")
                  "Write it as a map from language code to name: {:lang/names {:en \"English\" :zh-Hans \"Simplified Chinese\"}}.")
      :else
      (doseq [[k n] (sort-by (comp str key) named)]
        (if-not (some #{k} langs)
          (let [code  (when (or (keyword? k) (string? k) (symbol? k)) (name k))
                near  (fn [c] (->> langs
                                   (map (fn [l] [(u/damerau-levenshtein (u/lower c) (u/lower (name l))) (name l) l]))
                                   (filter (fn [[d]] (<= d 2)))
                                   sort first peek))
                meant (when code
                        (or (config/lang-for-suffix cfg code)
                            (when (>= (count code) 5) (near code))))]
            (diag/warn! file (str "`:lang/names` in " file " names " (pr-str k)
                                  ", which is not a configured language"
                                  (if meant (str " — did you mean " meant "?") "."))
                        (str "Its keys are the codes under :langs :locales: "
                             (str/join ", " (map str langs)) ".")))
          (when (and (some? n) (not (and (string? n) (not (str/blank? n)))))
            (diag/warn! file (str "`:lang/names` in " file " gives " (pr-str k) " the name " (pr-str n)
                                  ", not a non-blank string, so its :label stands alone.")
                        "Give it the language's name in this file's language, or nil to show the :label alone.")))))))

(defn check-site-strings!
  "Warnings over the site's `i18n/<lang>.edn` files (D-P3-6), raised in
  analyse so `build` and `doctor` both report them:

    - a key no theme file defines and that is within two edits of one that
      does is a typo — it would silently never be read;
    - any other key no theme file defines is site-added, and is reported
      when some configured language lacks it, since only the fallback chain
      would stand between that language and a `⟦key⟧`;
    - the language names (2026-11-01, `check-lang-names!`): a `:lang/names`
      that is not a map, a key of it that is no configured language, a name
      that is not a non-blank string, and a `:lang/name` that is not a
      string."
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
    (doseq [l langs
            :let [m (get site l)]]
      (check-lang-names! cfg (file l) m))
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
