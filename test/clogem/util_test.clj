;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.util-test
  (:require [clojure.test :refer [deftest is testing]]
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
