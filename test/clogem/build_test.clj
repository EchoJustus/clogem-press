;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.build-test
  "End-to-end assertions over `examples/demo-site`.

  The demo site is the executable specification of the conventions (§5.1), so
  these are the tests that would catch a convention regression before a
  generator tag is cut and a content repo bumps to it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core]
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

(deftest posts-sort-across-mixed-date-spellings
  (testing "the demo tree deliberately mixes a migrated vdoing post (unquoted
            `date:`, which YAML resolves to a date value) with auto-filled posts
            (quoted, i.e. strings). `(sort-by :date …)` over that mix threw
            ClassCastException on entirely legal content until :date was
            normalized at parse time."
    (let [dates (mapv #(get-in *model* [:articles % :date]) (:posts *model*))]
      (is (every? string? dates)
          (str "one representation, not two: " (pr-str (map type dates))))
      (is (= (vec (reverse (sort dates))) dates)
          "and the index really is newest-first")
      (is (some #{"2026-07-20 08:00:00"} dates)
          "the unquoted YAML timestamp came through in the canonical form"))))

;; ---------------------------------------------------------------------------
;; Phase 2 — index pages (D-P2-2, D-P2-3)

(def non-default-langs ["zh-Hans" "zh-Hant" "ms" "ta"])

(deftest index-pages-exist-per-language
  (testing "D-P2-3: bare for the site-default language, /<lang>/ otherwise —
            the site-default rule, not the per-article one"
    (doseq [kind ["categories" "tags" "archives"]]
      (is (exists? kind "index.html") kind)
      (doseq [l non-default-langs]
        (is (exists? l kind "index.html") (str l "/" kind))
        (is (str/includes? (slurp-out l kind "index.html") (str "lang=\"" l "\""))
            "rendered per language with a matching <html lang>")))))

(deftest categories-overview-shows-every-category-with-localized-labels
  (let [en (slurp-out "categories" "index.html")
        zh (slurp-out "zh-Hans" "categories" "index.html")]
    (doseq [c ["Guide" "Basics" "Notes" "tech"]]
      (is (str/includes? en (str ">" c "<")) c))
    (is (str/includes? zh ">基础<") "D-12: :i18n :category-labels localizes the DISPLAY")
    (is (str/includes? zh "href=\"/zh-Hans/categories/basics/\"")
        "…while the key and the URL slug stay the raw directory name")
    (is (exists? "zh-Hans" "categories" "basics" "index.html"))))

(deftest tags-page-lists-the-tags
  (is (str/includes? (slurp-out "tags" "index.html") ">meta<"))
  (is (exists? "tags" "meta" "index.html")))

(deftest archives-group-by-year-then-month-newest-first
  (let [html (slurp-out "archives" "index.html")]
    (is (str/includes? html "<h2>2026</h2>"))
    (is (< (str/index-of html "<h3>2026-08</h3>") (str/index-of html "<h3>2026-07</h3>"))
        "newest month first")
    (is (str/includes? html "/pages/mig001/") "the July post is in July")))

(deftest an-article-appears-once-per-index-page
  (testing "§6.8: dedupe by identity is structural — three variants, one row"
    (let [html (slurp-out "categories" "basics" "index.html")]
      (is (= 1 (count (re-seq #"/pages/643259/\"" html)))))))

(deftest index-rows-follow-6-8
  (testing "the L variant's title and URL when it exists, else the primary's
            with the fallback notice; row order identical across languages"
    (let [en (slurp-out "categories" "guide" "index.html")
          zh (slurp-out "zh-Hans" "categories" "guide" "index.html")]
      (is (str/includes? en "href=\"/pages/171a98/\"") "Tamil-only: linked at its bare URL")
      (is (re-find #"href=\"/pages/171a98/\"[^<]*</a><span class=\"clogem-fallback\"" en)
          "…and carries the fallback marker on the English page")
      (is (str/includes? zh "href=\"/zh-Hans/pages/643259/\"") "the zh-Hans variant when it exists")
      (is (str/includes? zh "href=\"/pages/171a98/\""))
      (let [order (fn [html] (map second (re-seq #"href=\"(?:/zh-Hans)?(/pages/[^\"]+)\" lang" html)))]
        (is (= (order en) (order zh)) "sort keys come from the primary")))))

(deftest index-pages-are-not-articles-and-the-catalogue-is-not-a-post
  (is (not-any? #(re-find #"@pages" (str (:rel-path %)))
                (mapcat (comp vals :variants) (vals (:articles *model*))))
      "@pages/ files are never scanned as articles")
  (let [cat (group-titled "Guide catalogue")]
    (is (not (some #{(:permalink cat)} (:posts *model*))))
    (is (not (some #{(:permalink cat)} (mapcat val (:categories *model*)))))))

(deftest docs-mode-has-no-index-uris
  (testing "D-P2-2 / :content toggles: category/tag/archive false → no index
            page in the page map at all, in any language"
    (let [m (update *model* :cfg #(-> % (assoc-in [:content :category] false)
                                      (assoc-in [:content :tag] false)
                                      (assoc-in [:content :archive] false)))
          uris (keys (first (diag/collecting (render/page-map m))))]
      (is (seq uris))
      (is (not-any? #(re-find #"/(categories|tags|archives)/" %) uris)))
    (let [m (update *model* :cfg #(assoc-in % [:content :tag] false))
          uris (keys (first (diag/collecting (render/page-map m))))]
      (is (not-any? #(re-find #"/tags/" %) uris))
      (is (some #(re-find #"/categories/" %) uris) "the others stay"))))

(deftest a-user-authored-pages-body-renders-above-the-list
  (testing "D-P2-6: the demo's categoriesPage.md carries no body, so the
            generated bar follows the heading directly — see the golden test
            for the generated files"
    (is (str/includes? (slurp-out "categories" "index.html") "<h1>Categories</h1>"))))

;; ---------------------------------------------------------------------------
;; Phase 2 — sidebar tree (D-P2-1) and the site-wide switcher (D-P2-13)

(defn- page-html
  "Render one URI of a page map built over *model* with `cfg-f` applied."
  [cfg-f uri]
  (let [pm (first (diag/collecting (render/page-map (update *model* :cfg cfg-f))))
        f  (get pm uri)]
    (when f (str (hiccup2.core/html (f))))))

(deftest an-article-shows-only-its-own-top-level-tree
  (let [html (slurp-out "pages" "643259" "index.html")]
    (is (str/includes? html "<details class=\"clogem-sidebar__dir"))
    (is (str/includes? html "<summary>Guide</summary>"))
    (is (str/includes? html "<summary>Basics</summary>") "nested directory")
    (is (str/includes? html "<summary>Advanced</summary>"))
    (is (not (str/includes? html "Heading slug cases")) "02.Notes articles are not in 01.Guide's tree")
    (is (not (str/includes? html "Three levels deep")))
    (is (re-find #"<li class=\"is-active\"><a aria-current=\"page\" href=\"/pages/643259/\"" html)
        "the leaf is marked")
    (is (= 1 (count (re-seq #"/pages/643259/\" lang" html))) "three variants, one leaf")))

(deftest sidebar-open-governs-which-groups-start-open
  (testing "[:theme :sidebar-open] true → every group open; false → only the active trail"
    (let [uri "/pages/643259/"
          all-open (page-html identity uri)
          trail    (page-html #(assoc-in % [:theme :sidebar-open] false) uri)]
      (is (= 3 (count (re-seq #"<details[^>]*open" all-open))) "Guide, Basics and Advanced all open")
      (is (= 2 (count (re-seq #"<details[^>]*open" trail))) "only Guide and Basics — the active trail")
      (is (re-find #"<details class=\"clogem-sidebar__dir is-active-trail\" open" trail))
      (is (re-find #"<details class=\"clogem-sidebar__dir\"><summary>Advanced" trail)))))

(deftest posts-catalogue-and-home-have-no-tree
  (is (not (str/includes? (slurp-out "pages" "284c67" "index.html") "clogem-sidebar"))
      "a post has no structured position (sidebar: auto)")
  (is (not (str/includes? (slurp-out "pages" "559f0f" "index.html") "clogem-sidebar"))
      "sidebar: false on the catalogue page hides the panel")
  (is (not (str/includes? (slurp-out "index.html") "clogem-sidebar")))
  (is (not (str/includes? (slurp-out "categories" "index.html") "clogem-sidebar"))))

(deftest the-switcher-is-site-wide-and-lands-on-the-same-page
  (testing "D-P2-13: every page lists every configured language"
    (doseq [path [["index.html"] ["pages" "3ce486" "index.html"] ["zh-Hans" "categories" "index.html"]]]
      (let [html (apply slurp-out path)]
        (doseq [l ["English" "简体中文" "繁體中文" "Bahasa Melayu" "தமிழ்"]]
          (is (str/includes? html (str ">" l "<")) (str path " lists " l))))))
  (testing "an article with the variant → that variant; without → that language's home"
    (let [html (slurp-out "pages" "643259" "index.html")]
      (is (str/includes? html "href=\"/zh-Hans/pages/643259/\" hreflang=\"zh-Hans\""))
      (is (str/includes? html "href=\"/ta/\" hreflang=\"ta\"") "no Tamil variant → Tamil home")
      (is (str/includes? html "aria-current=\"true\" class=\"is-current\" lang=\"en\""))))
  (testing "an index page → the same page under the other language"
    (let [html (slurp-out "zh-Hans" "categories" "guide" "index.html")]
      (is (str/includes? html "href=\"/categories/guide/\" hreflang=\"en\""))
      (is (str/includes? html "href=\"/ta/categories/guide/\" hreflang=\"ta\"")))))
