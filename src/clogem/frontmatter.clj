;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.frontmatter
  "Read and surgically auto-fill front matter (DESIGN.md §5.2 step 2, §7.2).

  Two rules govern everything here:

  1. **Never overwrite a manual value.** Auto-fill only ever *adds* keys that are
     absent. This is what makes the CI write-back idempotent, which is loop guard
     3 in §7.2.

  2. **Never reserialize the block.** vdoing round-trips front matter through
     json2yaml and mangles quoting; we insert the missing lines into the existing
     block and leave every other byte alone. That makes the diff of a
     normalization commit readable, and makes the class of bug impossible rather
     than merely unlikely.

  Both YAML and EDN front matter are accepted. The block is delimited by `---`
  lines; it is parsed as EDN when its first non-blank character is `{`, else as
  YAML."
  (:require [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clogem.diag :as diag]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Splitting

(def ^:private fence-re
  ;; Leading `---` on its own line, the block, then a closing `---` line.
  ;; (?s) so . spans newlines; the body is reluctant so the FIRST closing fence wins.
  #"(?s)\A(﻿)?(---)[ \t]*\r?\n(.*?)(\r?\n)---[ \t]*(\r?\n|\z)")

(defn split-file
  "Split raw file content into {:bom :fm-text :body :has-fm? :eol}.

  `:fm-text` is the raw text between the fences with no trailing newline;
  `:body` is everything after the closing fence, byte-identical."
  [raw]
  (if-let [[whole bom _open inner _nl _close] (re-find fence-re raw)]
    {:bom     (or bom "")
     :has-fm? true
     :fm-text inner
     :body    (subs raw (count whole))
     :eol     (if (str/includes? whole "\r\n") "\r\n" "\n")}
    {:bom     (if (str/starts-with? raw "﻿") "﻿" "")
     :has-fm? false
     :fm-text nil
     :body    (if (str/starts-with? raw "﻿") (subs raw 1) raw)
     :eol     (if (str/includes? raw "\r\n") "\r\n" "\n")}))

(defn edn-block? [fm-text]
  (str/starts-with? (str/triml (or fm-text "")) "{"))

(defn- non-map-front-matter!
  "Front matter that parses but is not a mapping.

  YAML has several document shapes and only one of them is front matter, and
  the two ways they used to fail were both bad in different directions.

  A scalar block (`---`/`just a note`/`---`) parses to a String and a list block
  to a `LazySeq`; `contains?` supports neither, so both surfaced as an
  IllegalArgumentException thrown from inside `compute-additions` — a stack
  trace naming the fill plan rather than the file.

  A **set** (`!!set`) parses to an `OrderedSet`, which `contains?` *does*
  accept, answering `false` for every key. That one never crashed: it reached
  the WRITER, which appended `title: …` lines into a non-mapping block and left
  invalid YAML in the user's source file. The build printed `auto-filled front
  matter in 1 file` and exited 0, and because auto-fill then re-ran on a block
  it could no longer parse, the damage compounded on every subsequent build.

  So this is an error, not a warning, and the severity is the mechanism: both
  `build` and `fm-fix` raise on errors before `apply-fill!` runs, which is what
  makes `the file is skipped for write-back` a structural guarantee rather
  than a promise."
  [path what]
  (diag/error!
   path
   (str "front matter is not a mapping (parsed as " what ").")
   (str "Front matter must be a YAML mapping (`key: value` lines) or an EDN map "
        "(`{:key \"value\"}`). A `---` fence at the very top of the file is read as "
        "front matter, so if this was meant as a horizontal rule or as prose, put a "
        "blank line or some text above it."))
  nil)

(defn- shape-of
  [v]
  (cond
    (sequential? v) "a list"
    (set? v)        "a set"
    (string? v)     "a scalar string"
    (number? v)     "a number"
    (boolean? v)    "a boolean"
    :else           (str "a " (.getName (class v)))))

(def ^:private canonical-date-format
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn canonical-date
  "Coerce a parsed `date:` value to a String, always.

  YAML has two spellings of the same date and they parse to two different Clojure
  types: `date: 2026-08-01 09:30:00` resolves to a `java.util.Date`, while
  `date: \"2026-08-01 09:30:00\"` stays a String. vdoing's own writer emits the
  unquoted form and clogem-press's auto-fill emits the quoted one, so *any*
  migrated tree that has since had a new article added holds both — and
  `(sort-by :date …)` over that tree throws ClassCastException on content that
  is entirely legal. Normalizing at parse time is the only place the fix belongs;
  every consumer downstream then has one type to reason about.

  Date-likes are rendered in **UTC**, because SnakeYAML resolves a zoneless YAML
  timestamp as UTC — so this round-trips what the author wrote rather than
  shifting it by the build machine's offset (auto-fill's `format-date` is a
  different case: a file birthtime is a real instant, and local time is the
  right reading of it).

  A String is returned byte-identical. Normalizing the *type* is what stops the
  crash; rewriting what an author typed is not this function's business, and the
  canonical form already sorts correctly against it. The residual cost is that a
  hand-written non-canonical string (`2026-8-1`) still sorts lexicographically —
  a display-order wrinkle, not an exception."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  v
    (instance? java.util.Date v)
    (.format (java.time.LocalDateTime/ofInstant (.toInstant ^java.util.Date v)
                                                java.time.ZoneOffset/UTC)
             canonical-date-format)
    (instance? java.time.Instant v)
    (.format (java.time.LocalDateTime/ofInstant ^java.time.Instant v
                                                java.time.ZoneOffset/UTC)
             canonical-date-format)
    :else (str v)))

(defn- normalize
  "Post-process a parsed front-matter map into the shapes the rest of the
  pipeline is entitled to assume."
  [m]
  (if (contains? m :date) (update m :date canonical-date) m))

(defn parse-fm
  "Parse a front-matter block to a map with keyword keys. Returns {} for an empty
  block, nil (plus a diagnostic) when the block is malformed or is not a mapping."
  [fm-text path]
  (cond
    (nil? fm-text)             {}
    (str/blank? fm-text)       {}
    (edn-block? fm-text)
    (try (let [m (edn/read-string fm-text)]
           (if (map? m) (normalize m) (non-map-front-matter! path (shape-of m))))
         (catch Exception e
           (diag/error! path (str "malformed EDN front matter: " (ex-message e)))
           nil))
    :else
    (try (let [m (yaml/parse-string fm-text :keywords true)]
           (cond
             (nil? m)  {}
             (map? m)  (normalize m)
             :else     (non-map-front-matter! path (shape-of m))))
         (catch Exception e
           (diag/error! path (str "malformed YAML front matter: " (ex-message e)))
           nil))))

(defn read-file
  "Read a markdown file into {:raw :front-matter :body :has-fm? :edn? :eol}."
  [path]
  (let [raw   (slurp (str path))
        parts (split-file raw)
        fm    (parse-fm (:fm-text parts) path)]
    (assoc parts
           :raw raw
           :path (str path)
           :edn? (edn-block? (:fm-text parts))
           :front-matter (or fm {}))))

;; ---------------------------------------------------------------------------
;; Emitting individual keys
;;
;; Hand-written rather than clj-yaml/generate-string, for two reasons: we must
;; control the exact bytes we insert (a normalization diff should be readable),
;; and we only ever emit the handful of scalar/sequence shapes auto-fill produces.

(defn- yaml-needs-quoting?
  [^String s]
  (or (str/blank? s)
      (re-find #"[:#\[\]{}&*!|>'\"%@`,]" s)
      (re-find #"^[-?]" s)
      (re-find #"^\s|\s$" s)
      (re-matches #"(?i)(true|false|null|~|yes|no|on|off)" s)
      (re-matches #"[-+]?[0-9.eE_]+" s)))

(defn- yaml-scalar
  [v]
  (cond
    (nil? v)     "~"
    (boolean? v) (str v)
    (number? v)  (str v)
    (keyword? v) (name v)
    :else (let [s (str v)]
            (if (yaml-needs-quoting? s)
              (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \")
              s))))

(defn- yaml-lines
  "One key as a sequence of YAML lines."
  [k v]
  (let [k (name k)]
    (cond
      (and (sequential? v) (empty? v)) [(str k ": []")]
      (sequential? v)                  (into [(str k ":")]
                                             (map #(str "  - " (yaml-scalar %)) v))
      (and (map? v) (empty? v))        [(str k ": {}")]
      (map? v)                         (into [(str k ":")]
                                             (map (fn [[mk mv]]
                                                    (str "  " (name mk) ": " (yaml-scalar mv))) v))
      :else                            [(str k ": " (yaml-scalar v))])))

(defn- edn-line
  [k v]
  (str " " (pr-str (keyword (name k))) " " (pr-str v)))

;; ---------------------------------------------------------------------------
;; Surgical insertion

(defn insert-keys
  "Return `raw` with `additions` (an ordered seq of [k v]) inserted into the
  front-matter block. Everything already present is preserved byte-for-byte.

  - existing YAML block  → new lines appended just before the closing fence
  - existing EDN block   → new pairs inserted just before the closing brace
  - no block at all      → a fresh YAML block prepended"
  [raw additions]
  (if (empty? additions)
    raw
    (let [{:keys [bom has-fm? fm-text body eol]} (split-file raw)]
      (cond
        (not has-fm?)
        (str bom "---" eol
             (str/join eol (mapcat (fn [[k v]] (yaml-lines k v)) additions)) eol
             "---" eol
             (when-not (or (str/blank? body) (str/starts-with? body "\n") (str/starts-with? body "\r\n")) eol)
             body)

        (edn-block? fm-text)
        (let [trimmed (str/trimr fm-text)
              close   (.lastIndexOf trimmed "}")
              head    (subs trimmed 0 close)
              tail    (subs trimmed close)]
          (str bom "---" eol
               (str/trimr head) eol
               (str/join eol (map (fn [[k v]] (edn-line k v)) additions))
               tail eol
               "---" eol body))

        :else
        (let [existing (str/trimr fm-text)]
          (str bom "---" eol
               (when-not (str/blank? existing) (str existing eol))
               (str/join eol (mapcat (fn [[k v]] (yaml-lines k v)) additions)) eol
               "---" eol body))))))

;; ---------------------------------------------------------------------------
;; Auto-fill

(def fill-order
  "vdoing writes keys in this order; matching it keeps normalization diffs of a
  migrated vdoing tree minimal."
  [:title :date :permalink :categories :tags])

(defn- format-date
  [inst]
  (let [fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")]
    (.format (java.time.LocalDateTime/ofInstant inst (java.time.ZoneId/systemDefault)) fmt)))

(defn file-date
  "vdoing uses file birthtime. Not every filesystem records one; fall back to
  last-modified rather than to `now`, so a read-only build is reproducible."
  [path]
  (let [attrs (java.nio.file.Files/readAttributes
               (java.nio.file.Path/of (str path) (into-array String []))
               java.nio.file.attribute.BasicFileAttributes
               (into-array java.nio.file.LinkOption []))
        ct    (.toInstant (.creationTime attrs))
        mt    (.toInstant (.lastModifiedTime attrs))]
    (format-date (if (pos? (.toEpochMilli ct)) ct mt))))

(def ^:private fill-rank
  (into {} (map-indexed (fn [i k] [k i]) fill-order)))

(defn compute-additions
  "Which keys auto-fill would add to `entry`, in `fill-order`, as [k v] pairs.

  `resolved` supplies values the caller has already decided — notably
  `:permalink`, which for a language variant is *inherited from its identity
  group* rather than minted (§6.2), and `:categories`, which come from the
  primary variant's path (§6.2 as amended in v2.1).

  An empty `:tags` vector and a `false` extend-frontmatter default are both
  legitimate values to write, so membership is tested with `contains?` rather
  than truthiness."
  [{:keys [front-matter path]} resolved extend-frontmatter]
  (let [candidates (merge (cond-> {:tags []}
                            (:title resolved)     (assoc :title (:title resolved))
                            true                  (assoc :date (or (:date resolved) (file-date path)))
                            (:permalink resolved) (assoc :permalink (:permalink resolved))
                            (seq (:categories resolved)) (assoc :categories (vec (:categories resolved))))
                          extend-frontmatter)]
    (->> (keys candidates)
         (remove #(contains? front-matter %))
         (sort-by #(vector (get fill-rank % 999) (name %)))
         (mapv (fn [k] [k (get candidates k)])))))

(defn write-back!
  "Apply `additions` to the file at `path`. Returns true when the file changed."
  [path additions]
  (if (empty? additions)
    false
    (let [raw (slurp (str path))
          out (insert-keys raw additions)]
      (if (= raw out)
        false
        (do (spit (str path) out) true)))))
