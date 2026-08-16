;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.build-test
  "End-to-end assertions over `examples/demo-site`.

  The demo site is the executable specification of the conventions (§5.1), so
  these are the tests that would catch a convention regression before a
  generator tag is cut and a content repo bumps to it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.render :as render]))

(def demo "examples/demo-site")

(def ^:dynamic *out-dir* nil)
(def ^:dynamic *model* nil)

(defn build-fixture
  [f]
  (let [out (fs/create-temp-dir {:prefix "clogem-build"})
        ;; --no-write: the tests must not mutate the checked-in demo tree
        cfg (first (diag/collecting
                    (config/load-config demo nil {:build {:out (str out)}
                                                  :content {:write-front-matter false}})))
        [model _] (diag/collecting (cli/analyse cfg))]
    (diag/collecting (render/build! cfg model))
    (binding [*out-dir* out *model* model]
      (try (f) (finally (fs/delete-tree out))))))

(use-fixtures :once build-fixture)

(defn- slurp-out [& parts] (slurp (fs/file (apply fs/path *out-dir* parts))))
(defn- exists? [& parts] (fs/exists? (apply fs/path *out-dir* parts)))

(defn- group-titled [title]
  (->> (vals (:articles *model*))
       (filter (fn [g] (some #(= title (:title %)) (vals (:variants g)))))
       first))

;; ---------------------------------------------------------------------------

(deftest demo-site-builds
  (is (pos? (count (:articles *model*))))
  (is (exists? "index.html")))

(deftest one-identity-three-variants
  (testing "M1: three files sharing a number and a base name are ONE article"
    (let [g (group-titled "conventions")]
      (is (some? g))
      (is (= #{:en :zh-Hans :zh-Hant} (set (keys (:variants g)))))
      (is (= 1 (count (filter #(= (:permalink g) (:permalink %)) (vals (:articles *model*)))))))))

(deftest tamil-only-article-serves-tamil-at-its-bare-url
  (testing "§6.3's per-article primary — the case the requirement explicitly allows"
    (let [g (group-titled "தமிழ் மட்டும்")
          uri (str/replace (:permalink g) #"^/|/$" "")
          html (slurp-out uri "index.html")]
      (is (= :ta (:primary g)))
      (is (str/includes? html "lang=\"ta\"")
          "the unprefixed URL is not 'the site default language'")
      (is (not (exists? "ta" uri "index.html"))
          "no content is served at two URLs — nothing to paper over with canonicals"))))

(deftest non-primary-variants-are-prefixed
  (let [g (group-titled "conventions")
        uri (str/replace (:permalink g) #"^/|/$" "")]
    (is (exists? uri "index.html"))
    (is (exists? "zh-Hans" uri "index.html"))
    (is (exists? "zh-Hant" uri "index.html"))))

(deftest chrome-language-equals-content-language
  (testing "§6.4 rule 2 — enforced structurally, not by convention"
    (let [g (group-titled "conventions")
          uri (str/replace (:permalink g) #"^/|/$" "")
          html (slurp-out "zh-Hant" uri "index.html")]
      (is (str/includes? html "lang=\"zh-Hant\""))
      (is (str/includes? html "語言") "the navbar label is Traditional, from zh-Hant.edn")
      (is (not (str/includes? html "语言")) "…and not Simplified"))))

(deftest m2-group-facts-follow-the-primary-variant
  (testing "the mechanism-2 article's zh-Hans variant lives in another directory"
    (let [g (get-in *model* [:articles "/pages/relocated/"])]
      (is (some? g))
      (is (= #{:en :zh-Hans} (set (keys (:variants g)))))
      (is (= ["Deep" "Level2" "Level3"] (:categories g))
          "categories come from the primary variant's path, not the zh-Hans one")
      (doseq [[sub] [[""] ["zh-Hans"]]]
        (let [html (if (str/blank? sub)
                     (slurp-out "pages" "relocated" "index.html")
                     (slurp-out sub "pages" "relocated" "index.html"))]
          (is (str/includes? html "Deep / Level2 / Level3")
              "both variants show the group's categories"))))))

(deftest relative-md-links-are-rewritten-to-permalinks
  (let [g (group-titled "Getting started")
        uri (str/replace (:permalink g) #"^/|/$" "")
        html (slurp-out uri "index.html")
        target (group-titled "conventions")]
    (is (str/includes? html (str "href=\"" (:permalink target) "\"")))
    (is (not (str/includes? html "02.conventions.md")))))

(deftest excluded-directories-are-not-in-the-tree
  (testing "@pages/ is excluded from the numbered scan"
    (is (nil? (group-titled "Categories")))))

(deftest posts-are-scanned-and-can-be-translated
  (let [g (group-titled "Hello from _posts")]
    (is (some? g))
    (is (= #{:en :zh-Hans} (set (keys (:variants g)))))
    (is (= :post (:kind g)))))

(deftest catalogue-page-is-not-an-article
  (let [g (group-titled "Guide catalogue")]
    (is (some? g))
    (is (false? (:article? g)) "pageComponent excludes it from every index")))

(deftest three-levels-deep-parses
  (let [g (group-titled "Three levels deep")]
    (is (= ["Deep" "Level2" "Level3"] (:categories g)))))

(deftest dotted-title-is-not-a-language-suffix
  (let [g (group-titled "Vue.js")]
    (is (some? g))
    (is (= #{:en} (set (keys (:variants g)))))))

(deftest one-home-per-language
  (doseq [[lang path] [[:en ["index.html"]]
                       [:zh-Hans ["zh-Hans" "index.html"]]
                       [:zh-Hant ["zh-Hant" "index.html"]]
                       [:ms ["ms" "index.html"]]
                       [:ta ["ta" "index.html"]]]]
    (is (apply exists? path) (str lang " home"))))

(deftest site-i18n-overrides-win-over-theme-defaults
  (is (str/includes? (slurp-out "index.html") "Latest from the demo")
      "examples/demo-site/i18n/en.edn overrides :index/recent"))

(deftest assets-are-copied-not-symlinked
  (is (exists? "assets" "demo.txt"))
  (is (not (fs/sym-link? (fs/path *out-dir* "assets" "demo.txt")))
      "a house rule, not a platform constraint (v2.1 V15) — a dereferenced
       symlink ships its target's bytes against the 1 GB cap")
  (is (exists? "clogem" "css" "theme.css")))

(deftest read-only-build-emits-stable-urls
  (testing "§7.2 mechanism (c): --no-write still needs reproducible URLs"
    (let [permalinks (set (keys (:articles *model*)))
          out2 (fs/create-temp-dir {:prefix "clogem-build2"})
          cfg2 (first (diag/collecting
                       (config/load-config demo nil {:build {:out (str out2)}
                                                     :content {:write-front-matter false}})))
          [m2 _] (diag/collecting (cli/analyse cfg2))]
      (try
        (is (= permalinks (set (keys (:articles m2)))))
        (finally (fs/delete-tree out2))))))

(deftest only-the-expected-warning-is-emitted
  (testing "the demo tree is clean apart from the deliberate mechanism-2 case"
    (let [cfg (first (diag/collecting
                      (config/load-config demo nil {:content {:write-front-matter false}})))
          [_ ds] (diag/collecting (cli/analyse cfg))]
      (is (empty? (diag/errors ds)))
      (is (= 1 (count (diag/warnings ds)))
          (str "unexpected warnings: " (pr-str (map :message (diag/warnings ds)))))
      (is (re-find #"different directories" (:message (first (diag/warnings ds))))))))
