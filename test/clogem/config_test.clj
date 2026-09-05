;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.util :as u]))

(defn- with-site
  "Write a site.edn into a temp dir and load it."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(deftest deep-merge-semantics
  (testing "maps merge recursively; vectors are replaced wholesale"
    (is (= {:a {:b 1 :c 3}} (u/deep-merge {:a {:b 1 :c 2}} {:a {:c 3}})))
    (is (= {:a [3]} (u/deep-merge {:a [1 2]} {:a [3]}))
        "a site that overrides :nav means to replace it, not to append")))

(deftest defaults-are-applied
  (let [[cfg _] (with-site {})]
    (is (= :en (config/default-lang cfg)))
    (is (= "content" (get-in cfg [:content :dir])))
    (is (true? (config/write-front-matter? cfg)))))

(deftest langs-are-canonicalized
  (let [[cfg _] (with-site {})]
    (testing "suffix matching is case-insensitive, storage is canonical (§6.1)"
      (is (= :zh-Hans (config/lang-for-suffix cfg "zh-hans")))
      (is (= :zh-Hans (config/lang-for-suffix cfg "ZH-HANS")))
      (is (= :zh-Hant (config/lang-for-suffix cfg "Zh-HaNt")))
      (is (nil? (config/lang-for-suffix cfg "js")))
      (is (= "zh-Hans" (config/html-lang cfg :zh-Hans))
          "the emitted <html lang> is the configured spelling, not the lowercased one"))))

(deftest priority-is-made-total
  (let [[cfg _] (with-site {:langs {:priority [:ta]}})]
    (is (= :ta (first (config/lang-keys cfg))))
    (is (= 5 (count (config/lang-keys cfg))) "missing locales are appended")))

(deftest default-must-be-a-configured-locale
  (let [[_ ds] (with-site {:langs {:default :fr}})]
    (is (some #(re-find #":langs :default" (:message %)) (diag/errors ds)))))

;; ---------------------------------------------------------------------------
;; V12 — the mandatory giscus mapping

(deftest giscus-language-must-be-routable
  (testing "an unroutable data-lang 404s the widget iframe, so it is an error"
    (let [[_ ds] (with-site {:langs {:locales {:ta {:label "த" :html-lang "ta" :giscus "ta"}}}})]
      (is (some #(re-find #"availableLanguages" (:message %)) (diag/errors ds))
          "giscus has no `ta` locale; the value must be `en`"))))

(deftest giscus-mapping-is-required-when-giscus-is-the-provider
  (let [[_ ds] (with-site {:langs {:locales {:ta {:label "த" :html-lang "ta" :giscus nil}}}
                           :comments {:provider :giscus :repo "a/b" :repo-id "x" :category-id "y"}})]
    (is (some #(re-find #"no :giscus mapping" (:message %)) (diag/errors ds)))))

(deftest giscus-defaults-map-ms-and-ta-to-en
  (is (= "en" (get-in config/default-locales [:ms :giscus])))
  (is (= "en" (get-in config/default-locales [:ta :giscus])))
  (is (= "zh-CN" (get-in config/default-locales [:zh-Hans :giscus])))
  (is (= "zh-TW" (get-in config/default-locales [:zh-Hant :giscus])))
  (testing "every default is one giscus actually routes"
    (doseq [[k v] config/default-locales]
      (is (contains? config/giscus-available-languages (:giscus v)) (str k)))))

(deftest giscus-not-in-use-means-no-requirement
  (let [[_ ds] (with-site {:comments {:provider :none}})]
    (is (empty? (diag/errors ds)))))

;; ---------------------------------------------------------------------------
;; M3 — the generator version floor

(deftest min-version-floor-is-asserted
  (testing "D-14 as amended: site.edn carries a floor, not a pin"
    (let [[_ ds] (with-site {:generator {:min-version "99.0.0"}})]
      (is (some #(re-find #":min-version" (:message %)) (diag/errors ds)))
      (is (some #(re-find #"publish.yml" (:hint %)) (diag/errors ds))
          "the error must point at the authoritative pin")))
  (let [[_ ds] (with-site {:generator {:min-version "0.0.1"}})]
    (is (empty? (diag/errors ds)))))

(deftest version-comparison
  (is (u/version>= "0.3.0" "0.3.0"))
  (is (u/version>= "0.3.1" "0.3.0"))
  (is (u/version>= "1.0.0" "0.9.9"))
  (is (u/version>= "v0.3.0" "0.3.0") "a leading v is tolerated")
  (is (u/version>= "0.3.0-rc1" "0.3.0") "pre-release trailers are ignored")
  (is (not (u/version>= "0.2.9" "0.3.0")))
  (is (u/version>= "0.3" "0.3.0") "missing segments are zero"))

(deftest paths-are-normalized
  (let [[cfg _] (with-site {})]
    (is (not (clojure.string/includes? (str (config/out-dir cfg)) "/./")))))
