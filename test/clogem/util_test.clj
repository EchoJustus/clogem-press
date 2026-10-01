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
    (is (= "_" (u/slug ".")))
    (is (= "_" (u/slug "..")))
    (is (= "_" (u/slug "")))
    (is (= "a-b" (u/slug "a/b")))
    (is (= "中文笔记" (u/slug "中文笔记")) "Unicode kept verbatim")
    (is (= "a--b" (u/slug "a--b")) "no general dash collapse")
    (is (= ".hidden" (u/slug ".hidden")) "no leading-dot trim")))
