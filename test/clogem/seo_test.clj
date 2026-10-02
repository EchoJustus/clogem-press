;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.seo-test
  (:require [clojure.data.xml :as x]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.seo :as seo]
            [clogem.util :as u]))

(defn- local-offset
  "The JVM default zone's offset at a local date-time, as RFC 3339 prints it."
  [s]
  (let [ldt (java.time.LocalDateTime/parse s)
        off (.getOffset (.getRules (java.time.ZoneId/systemDefault)) ldt)]
    (if (= java.time.ZoneOffset/UTC off) "Z" (str off))))

(deftest rfc3339-dates
  (testing "D-P3-3: a zoneless date is read in the JVM default zone (TZ)"
    (is (= (str "2026-08-01T09:30:00" (local-offset "2026-08-01T09:30:00"))
           (seo/rfc3339 "2026-08-01 09:30:00")))
    (is (= (str "2026-09-05T00:00:00" (local-offset "2026-09-05T00:00:00"))
           (seo/rfc3339 "2026-9-5")) "unpadded and date-only"))
  (testing "a date that carries a zone keeps it"
    (is (= "2026-08-01T09:30:00Z" (seo/rfc3339 "2026-08-01T09:30:00Z")))
    (is (= "2026-08-01T09:30:00+08:00" (seo/rfc3339 "2026-08-01 09:30:00 +0800")))
    (is (= "2026-08-01T09:30:00-05:00" (seo/rfc3339 "2026-08-01T09:30:00-05:00"))))
  (testing "not a date → nil, so the article is left out of the feed"
    (is (nil? (seo/rfc3339 nil)))
    (is (nil? (seo/rfc3339 "yesterday")))))

(deftest the-feed-namespace-is-the-default-one
  (testing "the bb 1.13 data.xml gotcha: qualified tags + :xmlns on the root,
            never an emitted `xmlns:b=` prefix"
    (let [s (seo/emit-xml (seo/sitemap-xml {:site {:url "https://x.example"}} [{:canonical "/a/"}]))]
      (is (str/includes? s "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""))
      (is (not (re-find #"xmlns:[a-z]+=\"http://www.sitemaps.org" s)))
      (is (str/includes? s "<loc>https://x.example/a/</loc>"))
      (is (= "urlset" (name (:tag (x/parse-str s))))))))

(deftest variant-file-name-suggestions
  (is (= "02.intro.zh-Hans.md" (u/variant-file-name "02.intro.md" "zh-Hans" nil)))
  (is (= "02.intro.ms.md" (u/variant-file-name "02.intro.en.md" "ms" "en")) "the source's own suffix is replaced")
  (is (= "02.Vue.js.ta.md" (u/variant-file-name "02.Vue.js.md" "ta" "en")) "a dotted title is not a suffix"))
