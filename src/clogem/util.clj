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

(defn url-encode-path
  "Percent-encode every segment of a site path for use in an href, keeping the
  separators: \"/categories/基础/\" → \"/categories/%E5%9F%BA%E7%A1%80/\". The
  page map is keyed by the UNencoded path (that is the directory the file is
  written to); links carry the encoded one (D-P2-3)."
  [path]
  (str/join "/" (map url-encode-segment (str/split (str path) #"/" -1))))

(defn iso-date
  "YYYY-MM-DD from a canonical date string, or the string as written when it
  does not start with a date (D-P2-7: ISO, no locale formats yet)."
  [d]
  (when d
    (if-let [[_ y m dd] (re-find #"^\s*(\d{4})-(\d{1,2})-(\d{1,2})" (str d))]
      (format "%s-%02d-%02d" y (parse-long m) (parse-long dd))   ; `2026-8-1` → 2026-08-01
      (str d))))

(defn date-sort-key
  "A sort key that orders hand-written dates by VALUE: a leading
  `YYYY-M-D[ H:m[:s]]` is zero-padded to `YYYY-MM-DD HH:mm:ss`, so the quoted,
  unpadded `\"2026-9-5\"` sorts before `\"2026-10-01\"` instead of after it.
  Anything that does not start with a date is returned unchanged."
  [d]
  (let [s (str d)]
    (if-let [[_ y mo dd h mi sec]
             (re-find #"^\s*(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T]+(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?" s)]
      (format "%s-%02d-%02d %02d:%02d:%02d" y (parse-long mo) (parse-long dd)
              (or (some-> h parse-long) 0) (or (some-> mi parse-long) 0) (or (some-> sec parse-long) 0))
      s)))

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

(def version-re
  "MAJOR.MINOR.PATCH with an optional semver pre-release — the only shape a
  `:generator :min-version` floor may take (D-14)."
  #"\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")

(defn parse-version
  "\"1.2.3-rc.1\" → {:release [1 2 3] :pre [\"rc\" 1]}; `:pre` is nil for a
  release. A leading `v` and `+build` metadata are ignored, and non-numeric
  release segments are dropped, so the generator's own version string parses
  leniently; a site's FLOOR is checked against `version-re` before it gets
  here."
  [v]
  (let [s          (-> (str v) str/trim (str/replace #"^v" "") (str/replace #"\+.*$" ""))
        [core pre] (str/split s #"-" 2)]
    {:release (into [] (keep #(when (re-matches #"\d+" %) (parse-long %))) (str/split core #"\."))
     :pre     (when-not (str/blank? pre)
                (mapv #(if (re-matches #"\d+" %) (parse-long %) %) (str/split pre #"\.")))}))

(defn- compare-pre
  "Semver §11 precedence of two pre-release identifier lists; nil (a release)
  ranks above every pre-release."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) 1
    (nil? b) -1
    :else
    (or (some (fn [[x y]]
                (let [c (cond
                          (and (number? x) (number? y)) (compare x y)
                          (number? x) -1               ; numeric < alphanumeric
                          (number? y) 1
                          :else (compare x y))]
                  (when-not (zero? c) c)))
              (map vector a b))
        (compare (count a) (count b)))))

(defn version>=
  "Semver precedence: `0.1.0-phase1` is BELOW `0.1.0`, so a pre-release
  generator does not satisfy the floor of the release it precedes. Missing
  release segments count as zero."
  [a b]
  (let [{ar :release ap :pre} (parse-version a)
        {br :release bp :pre} (parse-version b)
        n   (max (count ar) (count br) 3)
        pad (fn [v] (vec (take n (concat v (repeat 0)))))
        c   (compare (pad ar) (pad br))]
    (>= (if (zero? c) (compare-pre ap bp) c) 0)))
