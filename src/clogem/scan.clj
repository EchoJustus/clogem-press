;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.scan
  "Content tree scanner: vdoing's numbered-directory convention as amended by
  DESIGN.md §6.1 (language suffixes) and v2.1's M1 (identity-scoped duplicate
  numbers).

  The parsing algorithm, verbatim from §6.1:

      segs ← split(filename, \".\")
      last(segs) must be \"md\"                    ; else warn + skip
      body ← segs[0 .. n-2]                        ; drop the extension

      if (count(body) ≥ 2) and (lower(last(body)) ∈ lower(configured :langs codes)):
          lang ← the configured canonical spelling ; \"zh-hans\" → :zh-Hans
          body ← drop-last(body)
      else if (last(body) looks like a near-miss language tag):   ; see near-miss?
          ERROR with a suggestion
      else:
          lang ← the article's default

      order ← parseInt(first(body))                ; NaN or < 0 → warn + skip
      title ← join(rest(body), \".\")              ; \"\" → warn + skip

  Directories are never language-suffixed, so their rule is unchanged: number
  before the first dot, title after it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.util :as u]))

(def excluded-dirs
  "Never part of the numbered tree. `_posts/` and `@pages/` are scanned
  separately; `.vuepress/` is a vdoing artefact we tolerate but ignore."
  #{".vuepress" "@pages" "_posts" "node_modules"})

;; ---------------------------------------------------------------------------
;; Near-miss detection (§6.1, as amended by D-P2-14)

(def ^:private region-variants
  "The small confusables set of §6.1, keyed by primary subtag. These are the
  spellings a human reaches for when the configured code is something else."
  {"zh" ["zh" "zh-CN" "zh-TW" "zh-HK" "zh-SG" "zh-MO" "zh-Hans-CN" "zh-Hant-TW"]
   "en" ["en" "en-US" "en-GB" "en-SG" "en-AU"]
   "ms" ["ms" "ms-MY" "ms-SG"]
   "ta" ["ta" "ta-IN" "ta-SG" "ta-LK"]
   "pt" ["pt" "pt-BR" "pt-PT"]
   "es" ["es" "es-ES" "es-MX"]})

(defn- configured-codes
  "Lower-cased configured language codes."
  [cfg]
  (set (map (comp u/lower name) (config/lang-keys cfg))))

(defn- configured-primaries
  [cfg]
  (set (map #(first (str/split % #"-")) (configured-codes cfg))))

(defn confusables
  "Language tags that are *close enough to* a configured code to be a mistake
  rather than a title, but are not themselves configured."
  [cfg]
  (let [configured (configured-codes cfg)]
    (->> (configured-primaries cfg)
         (mapcat #(get region-variants % [%]))
         (map u/lower)
         (remove configured)
         set)))

(defn- configured-scripts
  "Lower-cased 4-letter script subtags configured for `primary`:
  `zh` → #{\"hans\" \"hant\"} on the demo, `en` → #{}."
  [cfg primary]
  (into #{}
        (keep (fn [code]
                (let [[p sc] (str/split code #"-")]
                  (when (and (= p primary) sc (= 4 (count sc))) sc))))
        (configured-codes cfg)))

(defn- tag-shaped?
  "Rule (c): does the ORIGINAL-case `segment` read as a BCP 47 tag for a
  configured primary? Casing is the signal here, and only here: BCP 47
  writes scripts Title-case (`Hans`) and regions UPPER-case (`TW`), while an
  ordinary hyphenated title is lower-case (`en-dash`, `ta-da`, `ms-word`).

  Anchored: when the first subtag is a script configured for the primary,
  or a 4-letter typo of one (any case, Damerau-Levenshtein ≤ 2), the whole
  segment is matched case-insensitively — primary included — so
  `zh-hant-hk`, `ZH-HANT-HK`, `Zh-Hant-HK` and `zh-hsna` / `ZH-HSNA` stay
  errors. Only configured scripts anchor, so no plain word does.

  Un-anchored, the primary must be lower-case and every later subtag a
  Title-case script, an UPPERCASE region or a 3-digit region (`ta-IN`,
  `zh-Latn-TW`, `en-001`) — or the segment is ALL CAPS: an upper-case
  primary followed only by UPPERCASE 2-letter or 3-digit regions (`EN-NZ`,
  `MS-BN`). A Title-case word after an upper-case primary is a title
  (`MS-Word`). The accepted cost: an all-caps `TA-DA` errors (§6.1)."
  [cfg segment]
  (let [[primary & subs] (str/split segment #"-" -1)
        p           (u/lower primary)
        scripts     (configured-scripts cfg p)
        script-ish? (fn [sub]
                      (and (re-matches #"[A-Za-z]{4}" sub)
                           (some #(<= (u/damerau-levenshtein (u/lower sub) %) 2) scripts)))]
    (boolean
     (and (re-matches #"(?i)[a-z]{2,3}" primary)
          (contains? (configured-primaries cfg) p)
          (seq subs)
          (cond
            (script-ish? (first subs))
            (every? #(re-matches #"(?i)[a-z]{2}|\d{3}" %) (rest subs))

            (re-matches #"[a-z]{2,3}" primary)
            (every? #(or (re-matches #"[A-Z][a-z]{3}" %)
                         (re-matches #"[A-Z]{2}|\d{3}" %))
                    subs)

            (re-matches #"[A-Z]{2,3}" primary)
            (every? #(re-matches #"[A-Z]{2}|\d{3}" %) subs)

            :else false)))))

(defn near-miss?
  "Is a last dot-segment a misspelled language tag rather than title text?

  Phase 1 answered yes for ANY segment matching
  `^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})+$`, which made every hyphenated two- or
  three-letter word a language tag: `02.api-design.md`, `03.my-notes.md` and
  `05.re-frame.md` all hard-errored on a real tree. The pattern is gone
  (D-P2-14). A segment that is not itself a configured code is a near-miss
  iff:

    (a) lower-cased, it is in the confusables set derived from `:langs`
        (`zh`, `zh-CN`, `en-US`, … — so `en-us` is caught here), OR
    (b) lower-cased, its Damerau-Levenshtein distance to some configured
        code is ≤ 1 AND the segment or that code contains a hyphen
        (`zh-hanz`, `zh-han`, `ta-`, `zhhans`, `zh_Hans`) — without the
        hyphen condition every two-letter word one edit from a two-letter
        code would be a tag, and `01.Vue.js.md` → `Vue.js` is a §6.1
        invariant (`js` is one edit from `ms`, as `tax` is from `ta`), OR
    (c) in its ORIGINAL case, it is tag-shaped for a configured primary
        (`tag-shaped?`): `zh-Hanz`, `ta-IN`, `en-001`, `zh-hsna`,
        `zh-hant-hk`, `ZH-HANT-HK`, `EN-NZ` are caught while `en-dash`,
        `ta-da`, `Ta-Da`, `ms-word`, `MS-Word`, `en-bloc`, `api-design` and
        `en-passant` are titles. Rule (c) used
        to lower-case first, which made every `<configured primary>-<2 or 4
        letters>` title an error."
  [cfg segment]
  (let [s (u/lower segment)]
    (and (not (config/lang-for-suffix cfg segment))
         (boolean
          (or (contains? (confusables cfg) s)
              (some #(and (or (str/includes? s "-") (str/includes? % "-"))
                          (<= (u/damerau-levenshtein s %) 1))
                    (configured-codes cfg))
              (tag-shaped? cfg segment))))))

(defn near-miss-hint
  [cfg segment]
  (let [codes (map name (config/lang-keys cfg))]
    (if-let [best (u/closest segment codes)]
      (str "did you mean `" best "`? Configured languages: " (str/join ", " codes) ".")
      (str "configured languages are: " (str/join ", " codes)
           ". Add it to :langs :locales, or rename the file so the last "
           "dot-segment is not language-tag-shaped."))))

;; ---------------------------------------------------------------------------
;; Filename parsing

(defn parse-order
  "vdoing parses the number with JS `parseInt`, which takes the leading digits
  and ignores the rest (\"01abc\" → 1). Reproduced deliberately: an existing
  vdoing tree must parse identically."
  [s]
  (when-let [m (re-find #"^\s*([+-]?\d+)" (str s))]
    (parse-long (second m))))

(defn parse-filename
  "Parse one markdown filename per §6.1.

  Returns {:order :title :lang :suffix?} on success, or {:skip reason} /
  {:error message :hint hint} otherwise. `path` is used only for diagnostics."
  [cfg filename]
  (let [segs (str/split filename #"\." -1)]
    (cond
      (< (count segs) 2)
      {:skip (str "no extension: " filename)}

      (not= "md" (u/lower (last segs)))
      {:skip (str "not a markdown file: " filename)}

      :else
      (let [body0    (vec (butlast segs))
            last-seg (peek body0)
            lang-hit (when (>= (count body0) 2) (config/lang-for-suffix cfg last-seg))]
        (if (and (not lang-hit) (>= (count body0) 2) (near-miss? cfg last-seg))
          {:error (str "unknown language `" last-seg "` in filename " filename)
           :hint  (near-miss-hint cfg last-seg)}
          (let [body  (if lang-hit (vec (butlast body0)) body0)
                order (parse-order (first body))
                title (str/join "." (rest body))]
            (cond
              (nil? order)
              {:skip (str "filename does not start with a number: " filename)}

              (neg? order)
              {:skip (str "negative order in filename: " filename)}

              (str/blank? title)
              {:skip (str "filename has a number but no title: " filename)}

              :else
              {:order order :title title :lang lang-hit :suffix? (boolean lang-hit)})))))))

(defn parse-dirname
  "Directory rule, unchanged from vdoing: number before the first dot, title is
  everything after it. An unnumbered directory is legal at level 1 and sorts
  after the numbered ones."
  [dirname]
  (let [i (str/index-of dirname ".")]
    (if (and i (parse-order (subs dirname 0 i)))
      {:order (parse-order (subs dirname 0 i))
       :title (subs dirname (inc i))
       :numbered? true}
      {:order nil :title dirname :numbered? false})))

;; ---------------------------------------------------------------------------
;; Walking

(defn- relative-segments
  [root p]
  (vec (map str (fs/components (fs/relativize root p)))))

(defn- scan-markdown-file
  [cfg root p {:keys [categories dir-key depth]}]
  (let [filename (fs/file-name p)
        parsed   (parse-filename cfg filename)
        rel      (str (fs/relativize root p))]
    (cond
      (:error parsed) (do (diag/error! rel (:error parsed) (:hint parsed)) nil)
      (:skip parsed)  (do (diag/warn! rel (:skip parsed)) nil)
      :else
      (merge parsed
             {:path       (str p)
              :rel-path   rel
              :dir-key    dir-key
              :depth      depth
              :categories categories
              :kind       :tree}))))

(defn- scan-posts
  "`_posts/` — no numbering, no structured sidebar. Category comes from the
  subfolder when there is one, else :content :category-text. Language suffixes
  still apply, so a post can be translated."
  [cfg posts-dir root]
  (when (fs/directory? posts-dir)
    (->> (fs/glob posts-dir "**.md")
         (sort-by str)
         (keep
          (fn [p]
            (let [filename (fs/file-name p)
                  segs     (str/split filename #"\." -1)
                  body0    (vec (butlast segs))
                  last-seg (peek body0)
                  rel      (str (fs/relativize root p))]
              (if (not= "md" (u/lower (last segs)))
                (do (diag/warn! rel "not a markdown file") nil)
                (let [lang-hit (when (>= (count body0) 2) (config/lang-for-suffix cfg last-seg))
                      near?    (and (not lang-hit) (>= (count body0) 2) (near-miss? cfg last-seg))]
                  (if near?
                    (do (diag/error! rel (str "unknown language `" last-seg "` in filename " filename)
                                     (near-miss-hint cfg last-seg))
                        nil)
                    (let [stem (str/join "." (if lang-hit (butlast body0) body0))
                          sub  (let [c (relative-segments posts-dir (fs/parent p))]
                                 (remove #{"." ""} c))]
                      {:path       (str p)
                       :rel-path   rel
                       :order      nil
                       ;; Identity vs display, and the distinction is
                       ;; load-bearing (§6.2 mechanism 1). The identity of a
                       ;; post is (directory, order, base name) like any other
                       ;; article — and for a post the base name is the FULL
                       ;; stem, date included, with the subfolder in the
                       ;; directory key. Two posts may legitimately share a
                       ;; slug across dates or across subfolders; keying on the
                       ;; date-stripped slug alone merged them into one identity
                       ;; group, which then hard-errors as two files claiming
                       ;; the same language.
                       ;;
                       ;; The language suffix is NOT part of the stem, which is
                       ;; exactly what still lets `…-hello.zh-Hans.md` join
                       ;; `…-hello.md`.
                       :base-title stem
                       ;; a `YYYY-MM-DD-slug` post still *shows* as `slug`
                       :title      (str/replace stem #"^\d{4}-\d{2}-\d{2}-" "")
                       :lang       lang-hit
                       :suffix?    (boolean lang-hit)
                       :dir-key    (str/join "/" (cons "_posts" sub))
                       :depth      1
                       :categories (vec (or (seq sub) [(get-in cfg [:content :category-text])]))
                       :kind       :post}))))))))))

(defn scan-tree
  "Walk the numbered directory tree. Returns a flat vector of entries; the tree
  shape is reconstructed in `clogem.model` from :dir-key and :categories.

  Also emits one `{:kind :dir}` marker per directory, so that
  `check-duplicate-numbers!` can apply D-3 to directories as well as files.
  `scan` filters them out again."
  [cfg]
  (let [root (config/content-dir cfg)]
    (letfn [(walk [dir depth categories dir-key]
              (let [children (sort-by str (fs/list-dir dir))
                    dirs     (filter fs/directory? children)
                    files    (filter #(and (fs/regular-file? %)
                                           (str/ends-with? (u/lower (fs/file-name %)) ".md"))
                                     children)]
                (concat
                 ;; files: at depth 0 (directly under content/) vdoing scans nothing
                 (when (pos? depth)
                   (keep #(scan-markdown-file cfg root % {:categories categories
                                                          :dir-key    dir-key
                                                          :depth      depth})
                         files))
                 (mapcat
                  (fn [d]
                    (let [nm (fs/file-name d)]
                      (cond
                        (str/starts-with? nm ".") nil
                        (excluded-dirs nm)        nil
                        :else
                        (let [{:keys [order title numbered?]} (parse-dirname nm)]
                          (when (and (not numbered?) (>= depth 1))
                            (diag/warn! (str (fs/relativize root d))
                                        (str "directory `" nm "` has no number prefix; "
                                             "vdoing requires numbering below level 1 — "
                                             "it will sort after numbered siblings.")))
                          (cons
                           ;; A marker entry, so `check-duplicate-numbers!` can see
                           ;; directories at all — only files were ever scanned, and
                           ;; D-3's error is not a rule about files. Keyed on the
                           ;; PARENT's dir-key so siblings group together. `scan`
                           ;; drops these; they never reach the model.
                           {:kind :dir :dir-key dir-key :order order
                            :base-title title :title title
                            :path (str d) :rel-path (str (fs/relativize root d))
                            :depth depth}
                           (walk d (inc depth)
                                 (conj categories title)
                                 (str dir-key "/" nm)))))))
                  dirs))))]
      (if-not (fs/directory? root)
        (do (diag/error! (str root) "content directory does not exist.") [])
        (vec (concat (walk root 0 [] "")
                     (scan-posts cfg (fs/path root "_posts") root)))))))

;; ---------------------------------------------------------------------------
;; M1: identity-scoped duplicate numbers

(defn check-duplicate-numbers!
  "v2.1 M1. Within one directory, entries that share both `order` and `title`
  are language variants of ONE article and are legal — that is the whole point
  of the suffix convention. Entries that share an `order` but differ in identity
  are a genuine collision and a hard error.

  Applying vdoing's unscoped rule here would fail the build on every translated
  article, which is why this scoping is load-bearing rather than cosmetic."
  [entries]
  ;; Files: same number AND same identity = language variants, legal.
  (doseq [[dir-key group] (group-by :dir-key (filter #(= :tree (:kind %)) entries))
          [order same-order] (group-by :order group)
          :when order
          ;; identity is the filename-derived title, never the front-matter
          ;; display title — see clogem.model/implicit-key
          :let [identities (group-by #(or (:base-title %) (:title %)) same-order)]
          :when (> (count identities) 1)]
    (diag/error!
     (str (u/blank->nil dir-key) "/")
     (str "duplicate sidebar number " order " for different articles: "
          (str/join ", " (sort (map #(fs/file-name (:path %)) same-order))))
     (str "Same number + same title = language variants of one article (legal). "
          "Same number + different titles = a collision. Renumber one of them "
          "(gaps of 10 are recommended).")))
  ;; Directories: grouped on `order` alone, because directories are never
  ;; language-suffixed (§6.1) — so there is no legal same-number case for them
  ;; at all, and any two siblings sharing a number are a collision. Only files
  ;; were ever checked, which left directories on vdoing's warn-and-overwrite
  ;; behaviour in the one place D-3 says not to have it: two sibling directories
  ;; with the same number simply sorted by a string tie-break.
  (doseq [[dir-key group] (group-by :dir-key (filter #(= :dir (:kind %)) entries))
          [order same-order] (group-by :order group)
          :when (and order (> (count same-order) 1))]
    (diag/error!
     (str (u/blank->nil dir-key) "/")
     (str "duplicate sidebar number " order " for different directories: "
          (str/join ", " (sort (map #(fs/file-name (:path %)) same-order))))
     (str "Directories are never language-suffixed, so two siblings sharing a "
          "number are always a collision — there is no variant case to exempt "
          "(D-3, §6.1). Renumber one of them (gaps of 10 are recommended).")))
  entries)

(defn scan
  "Full scan: walk, apply the identity-scoped duplicate check, then drop the
  directory markers `scan-tree` emitted for it — downstream every entry is a
  Markdown file, and `clogem.model/load-entries` slurps `:path`."
  [cfg]
  (->> (scan-tree cfg)
       check-duplicate-numbers!
       (filterv #(not= :dir (:kind %)))))
