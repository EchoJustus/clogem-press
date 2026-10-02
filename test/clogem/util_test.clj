;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.util-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.util :as u]))

(deftest clean-url-normalizes
  (is (= "/pages/abc/" (u/clean-url "/pages/abc/")))
  (is (= "/pages/abc/" (u/clean-url "pages/abc")))
  (is (= "/pages/abc/" (u/clean-url "//pages//abc//")))
  (is (= "/" (u/clean-url "/"))))

(deftest locale-independent-lowercasing
  (testing "language tags must not be lower-cased through the default locale"
    ;; Under a Turkish default locale, \"I\" lower-cases to a dotless \"ı\", which
    ;; would break matching for tags like `id` or `IT`. Locale/ROOT avoids it.
    (is (= "zh-hans" (u/lower "ZH-Hans")))
    (is (= "id" (u/lower "ID")))))

(deftest closest-suggests-the-intended-code
  (is (= "zh-Hans" (u/closest "zh-Hanz" ["en" "zh-Hans" "zh-Hant" "ms" "ta"])))
  (is (= "zh-Hant" (u/closest "zh-hant" ["en" "zh-Hans" "zh-Hant"])))
  (is (nil? (u/closest "completely-different" ["en" "ms"]))
      "nothing within the distance threshold yields no suggestion"))

(deftest levenshtein-basics
  (is (= 0 (u/levenshtein "abc" "abc")))
  (is (= 1 (u/levenshtein "abc" "abd")))
  (is (= 3 (u/levenshtein "" "abc")))
  (is (= 1 (u/levenshtein "zh-hanz" "zh-hans"))))

(deftest fragment-encoding
  (is (= "hello-world" (u/url-encode-fragment "hello-world")))
  (is (= "%E4%BD%A0%E5%A5%BD" (u/url-encode-fragment "你好")))
  (testing "characters that are legal and unambiguous in a fragment stay readable"
    (is (= "a-b.c_d" (u/url-encode-fragment "a-b.c_d")))))

(deftest hex-helpers
  (is (= 6 (count (u/random-hex 6))))
  (is (re-matches #"[0-9a-f]{6}" (u/random-hex 6)))
  (is (= 64 (count (u/sha256-hex "x"))))
  (testing "sha1 of a permalink — the giscus strict-mode discussion-body marker"
    (is (= 40 (count (u/sha1-hex "/pages/a1b2c3/"))))
    (is (= (u/sha1-hex "/pages/a1b2c3/") (u/sha1-hex "/pages/a1b2c3/"))
        "pure function of the term, so `clogem migrate` can print it")))

(deftest damerau-levenshtein-counts-a-transposition-as-one-edit
  (is (= 0 (u/damerau-levenshtein "abc" "abc")))
  (is (= 1 (u/damerau-levenshtein "zh-hanz" "zh-hans")) "substitution")
  (is (= 1 (u/damerau-levenshtein "zh-han" "zh-hans")) "deletion")
  (is (= 1 (u/damerau-levenshtein "ta-" "ta")) "insertion")
  (is (= 1 (u/damerau-levenshtein "zh-hnas" "zh-hans")) "adjacent transposition — one edit, not two")
  (is (= 2 (u/levenshtein "zh-hnas" "zh-hans")) "…which plain Levenshtein counts as two")
  (is (= 3 (u/damerau-levenshtein "" "abc")))
  (is (= 3 (u/damerau-levenshtein "api-design" "api-designs-x")) "far from any code"))

(deftest non-bmp-characters-encode-as-their-four-utf8-bytes
  (testing "fix 1: a surrogate pair is one code point, not two `?`s"
    (doseq [f [u/url-encode-segment u/url-encode-fragment]]
      (is (= "%F0%9F%98%80" (f "😀")))
      (is (= "%F0%A0%80%80" (f "𠀀")))
      (is (not (str/includes? (f "a😀b") "%3F"))))
    (is (= "/categories/%F0%9F%98%80fun/" (u/url-encode-path "/categories/😀fun/")))
    (doseq [s ["😀" "𠀀" "a😀b你好" "hello-😀-world"]]
      (is (= s (java.net.URLDecoder/decode (u/url-encode-segment s) "UTF-8")))
      (is (= s (java.net.URLDecoder/decode (u/url-encode-fragment s) "UTF-8"))))))

(deftest slugs-are-legal-windows-file-names
  (testing "fix 9"
    (is (= "q&a-why" (u/slug "Q&A: why?")))
    (is (= "c#-tips" (u/slug "C#: Tips?")))
    (is (not (re-find #"[<>:\"|?*]" (u/slug "a<b>|c*"))))
    (is (not (re-find #"[\x00-\x1F\x7F]" (u/slug "a\u0001b\u007Fc"))))
    (is (= "etc" (u/slug "etc.")))
    (is (= "a" (u/slug "a. . ")))
    (is (= "con_" (u/slug "CON")))
    (is (= "aux_.txt" (u/slug "aux.txt")) "the stem is what Windows reserves")
    (is (= "lpt1_" (u/slug "LPT1")))
    (is (= "console" (u/slug "console")) "only the exact device names")
    ;; D.2.1 fix F: the rest of Windows' reserved list
    (is (= "com0_" (u/slug "COM0")))
    (is (= "lpt0_" (u/slug "LPT0")))
    (is (= "conin$_" (u/slug "CONIN$")) "the whole name is the stem, not `con`")
    (is (= "conout$_" (u/slug "CONOUT$")))
    (is (= "conin$_.txt" (u/slug "conin$.txt")))
    (is (= "com10" (u/slug "com10")) "one digit only")
    (is (= "_" (u/slug ".")))
    (is (= "_" (u/slug "..")))
    (is (= "_" (u/slug "")))
    (is (= "a-b" (u/slug "a/b")))
    (is (= "中文笔记" (u/slug "中文笔记")) "Unicode kept verbatim")
    (is (= "a--b" (u/slug "a--b")) "no general dash collapse")
    (is (= ".hidden" (u/slug ".hidden")) "no leading-dot trim")))

;; ---------------------------------------------------------------------------
;; Damerau-Levenshtein (Phase 4 Task A, D-P4-11)

(defn- reference-damerau-levenshtein
  "The 0.2.0 implementation — a full (m+1)×(n+1) matrix — kept as the oracle
  for the rolling-row rewrite in `clogem.util`. Test-only: it is ~60× slower
  under babashka, which is why it was replaced."
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

(deftest damerau-levenshtein-basics
  (is (= 0 (u/damerau-levenshtein "" "")))
  (is (= 3 (u/damerau-levenshtein "" "abc")))
  (is (= 3 (u/damerau-levenshtein "abc" "")))
  (is (= 1 (u/damerau-levenshtein "zh-hnas" "zh-hans")) "one adjacent transposition")
  (is (= 2 (u/damerau-levenshtein "zh-hsna" "zh-hans")) "not adjacent: two substitutions")
  (is (= 3 (u/damerau-levenshtein "ca" "abc")) "optimal string alignment, not unrestricted DL")
  (is (= 1 (u/damerau-levenshtein "தமிழ்" "தமிழ")) "Tamil, by UTF-16 code unit"))

(deftest damerau-levenshtein-matches-the-matrix-version
  (testing "property-style: the rolling-row rewrite agrees with the 0.2.0
            matrix on random strings — ASCII, Latin with diacritics, CJK
            (BMP and a supplementary-plane character, two code units) and
            Tamil — drawn from small alphabets so that matches and adjacent
            transpositions are common"
    (let [rng       (java.util.Random. 20261002)
          alphabets ["abcde-" "zh-HansHant" "éèaàç" "简體中文汉漢" "தமிழ்கு" "a中த𠀋-"]
          rand-str  (fn [^String alpha]
                      (let [cps (vec (.toArray (.codePoints alpha)))
                            n   (.nextInt rng 9)]
                        (apply str (repeatedly n #(String. (Character/toChars (nth cps (.nextInt rng (count cps)))))))))
          cases     (for [_ (range 3000)
                          :let [alpha (nth alphabets (.nextInt rng (count alphabets)))
                                a     (rand-str alpha)
                                ;; half the time, b is a near neighbour of a
                                b     (if (.nextBoolean rng)
                                        (let [cs (vec a)]
                                          (if (< 1 (count cs))
                                            (let [i (.nextInt rng (dec (count cs)))]
                                              (apply str (assoc cs i (nth cs (inc i)) (inc i) (nth cs i))))
                                            (rand-str alpha)))
                                        (rand-str alpha))]]
                      [a b])
          bad       (remove (fn [[a b]] (= (reference-damerau-levenshtein a b)
                                           (u/damerau-levenshtein a b)))
                            cases)]
      (is (= 3000 (count cases)))
      (is (empty? bad) (pr-str (take 5 bad))))))
