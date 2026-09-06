;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.util
  "Small pure helpers shared across the pipeline. Nothing here knows about
  content, config, or HTML."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util Locale]))

;; ---------------------------------------------------------------------------
;; Maps

(defn deep-merge
  "Recursively merge maps; later values win. Non-map values (including vectors,
  which are how :priority and :nav are expressed) are replaced wholesale rather
  than concatenated — a site that overrides :nav means to replace it.

  An explicit `nil` in a later map **wins**, i.e. it unsets the key. That costs
  callers one `(or x {})` for a source that may be absent, and buys the ability
  to write `{:giscus nil}` in site.edn and have it mean something — which config
  validation then has an opinion about."
  ([] nil)
  ([a] a)
  ([a b] (if (and (map? a) (map? b)) (merge-with deep-merge a b) b))
  ([a b & more] (reduce deep-merge (deep-merge a b) more)))

(defn dissoc-in
  [m [k & ks]]
  (if ks
    (if (contains? m k) (update m k dissoc-in ks) m)
    (dissoc m k)))

;; ---------------------------------------------------------------------------
;; Strings

(defn lower
  "Locale-independent lower-casing. `clojure.string/lower-case` uses the default
  locale, which turns \"I\" into a dotless \"ı\" under a Turkish locale — a real
  hazard when the thing being lower-cased is a language tag we then compare
  against a configured set (DESIGN.md §6.1: compare case-insensitively, store
  canonically)."
  [^String s]
  (when s (.toLowerCase s Locale/ROOT)))

(defn blank->nil [s] (when-not (str/blank? s) s))

(defn levenshtein
  "Edit distance, used only to suggest the intended language code for a
  near-miss filename suffix."
  [^String a ^String b]
  (let [m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1 prev (vec (range (inc n)))]
        (if (> i m)
          (peek prev)
          (recur (inc i)
                 (loop [j 1 cur (transient [i])]
                   (if (> j n)
                     (persistent! cur)
                     (recur (inc j)
                            (conj! cur (min (inc (nth prev j))
                                            (inc (nth cur (dec j)))
                                            (+ (nth prev (dec j))
                                               (if (= (.charAt a (dec i))
                                                      (.charAt b (dec j)))
                                                 0 1)))))))))))))

(defn damerau-levenshtein
  "Optimal-string-alignment edit distance: insert, delete, substitute, and
  ONE adjacent transposition each cost 1. Used by the scanner's near-miss rule
  (DESIGN.md §6.1, D-P2-14): a filename segment within distance 1 of a
  configured language code (`zh-hanz`, `zh-han`, `ta-`, `zh-hsna`) is a
  misspelled tag, not a title."
  [^String a ^String b]
  (let [m (count a) n (count b)
        d (make-array Long/TYPE (inc m) (inc n))]
    (dotimes [i (inc m)] (aset d i 0 (long i)))
    (dotimes [j (inc n)] (aset d 0 j (long j)))
    (doseq [i (range 1 (inc m)) j (range 1 (inc n))]
      (let [cost (if (= (.charAt a (dec i)) (.charAt b (dec j))) 0 1)
            best (min (inc (aget d (dec i) j))
                      (inc (aget d i (dec j)))
                      (+ (aget d (dec i) (dec j)) cost))
            best (if (and (> i 1) (> j 1)
                          (= (.charAt a (dec i)) (.charAt b (- j 2)))
                          (= (.charAt a (- i 2)) (.charAt b (dec j))))
                   (min best (inc (aget d (- i 2) (- j 2))))
                   best)]
        (aset d i j (long best))))
    (aget d m n)))

(defn closest
  "The candidate with the smallest edit distance to s, or nil if none is within
  `max-distance`.

  Ties break toward the **earliest** candidate. Callers pass candidates in the
  site's `:langs :priority` order, so `zh-Hanz` — equidistant from `zh-Hans` and
  `zh-Hant` — suggests the one the site listed first. Without a stable rule the
  suggestion would flip on an unrelated config edit."
  ([s candidates] (closest s candidates 3))
  ([s candidates max-distance]
   (when (seq candidates)
     (let [scored (map-indexed (fn [i c] [c (levenshtein (lower s) (lower c)) i]) candidates)
           [best d] (first (sort-by (juxt second #(nth % 2)) scored))]
       (when (<= d max-distance) best)))))

;; ---------------------------------------------------------------------------
;; Hashing / ids

(defn sha256-hex
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn sha1-hex
  "SHA-1 of a string. Used for the giscus strict-mode discussion-body marker
  (DESIGN.md §6.8) — not for anything security-relevant."
  [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn random-hex
  "n random hex characters. vdoing uses 6; older versions emitted 16, which is
  why permalinks are treated as opaque rather than parsed."
  [n]
  (apply str (repeatedly n #(rand-nth "0123456789abcdef"))))

;; ---------------------------------------------------------------------------
;; URLs

(defn ensure-leading-slash [s] (if (str/starts-with? s "/") s (str "/" s)))
(defn ensure-trailing-slash [s] (if (str/ends-with? s "/") s (str s "/")))

(defn clean-url
  "Normalize a site-relative URL to /a/b/ form: leading and trailing slash, no
  doubled separators."
  [s]
  (-> (str s)
      (str/replace #"/{2,}" "/")
      ensure-leading-slash
      ensure-trailing-slash))

(defn url-encode-fragment
  "Percent-encode a heading slug for use in an href fragment.

  nextjournal/markdown preserves non-ASCII in heading ids verbatim (verified:
  `你好世界` → `你好世界`, `வணக்கம் உலகம்` → `வணக்கம்-உலகம்`), which is a valid
  HTML id but must be encoded to appear in a URL. Characters that are legal and
  unambiguous inside a fragment are left alone so English anchors stay readable."
  [s]
  (->> s
       (map (fn [^Character c]
              (let [ch (str c)]
                (if (re-matches #"[A-Za-z0-9\-._~!$&'()*+,;=:@/?]" ch)
                  ch
                  (->> (.getBytes ch "UTF-8")
                       (map #(format "%%%02X" (bit-and % 0xff)))
                       (apply str))))))
       (apply str)))

(defn url-encode-segment
  "Percent-encode one URL *path segment*: everything but RFC 3986 unreserved
  characters is encoded, so `/`, `?`, `#` and `%` inside a category or tag
  name cannot change the URL's structure. Unlike `url-encode-fragment`, this
  encodes the sub-delimiters too — a fragment can carry `?` unencoded, a path
  segment cannot."
  [s]
  (->> (str s)
       (map (fn [^Character c]
              (let [ch (str c)]
                (if (re-matches #"[A-Za-z0-9\-._~]" ch)
                  ch
                  (->> (.getBytes ch "UTF-8")
                       (map #(format "%%%02X" (bit-and % 0xff)))
                       (apply str))))))
       (apply str)))

(defn slug
  "The URL slug of a category or tag (DESIGN.md D-P2-3): lower-cased, with
  whitespace runs and path separators collapsed to `-`; Unicode letters (CJK,
  Tamil) are kept verbatim, percent-encoding being the href side's job
  (`url-encode-segment`) — the same policy the heading slugger follows.

  `.` and `..` are not slugs but directory names, and would escape the index
  directory; they, and the empty string, become `_`."
  [s]
  (let [out (-> (str s)
                str/trim
                lower
                (str/replace #"[\s/\\]+" "-")
                (str/replace #"^-+|-+$" ""))]
    (if (or (str/blank? out) (#{"." ".."} out)) "_" out)))

(defn html-escape
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

;; ---------------------------------------------------------------------------
;; Versions

(defn parse-version
  "\"1.2.3-rc1\" → [1 2 3]. Non-numeric trailers are ignored for comparison."
  [v]
  (->> (str/split (str/replace (str v) #"^v" "") #"[.\-+]")
       (keep #(when (re-matches #"\d+" %) (parse-long %)))
       vec))

(defn version>=
  [a b]
  (let [av (parse-version a) bv (parse-version b)
        n  (max (count av) (count bv))
        pad (fn [v] (vec (take n (concat v (repeat 0)))))]
    (>= (compare (pad av) (pad bv)) 0)))
