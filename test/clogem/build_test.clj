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
            [clogem.markdown]
            [clogem.model :as model]
            [clogem.theme.home]
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
          ;; Phase 2 links each category to its index page, so the three
          ;; names are separate anchors rather than one "Deep / Level2 /
          ;; Level3" string; the invariant — both variants show the GROUP's
          ;; categories, i.e. the primary's — is unchanged.
          (is (re-find #"<span class=\"clogem-meta__cats\"[^>]*>(?:<a [^>]*>)?Deep(?:</a>)? / (?:<a [^>]*>)?Level2(?:</a>)? / (?:<a [^>]*>)?Level3" html)
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
  (is (str/includes? (slurp-out "index.html") "Read the full article")
      "examples/demo-site/i18n/en.edn overrides :index/read-more")
  (is (str/includes? (slurp-out "ta" "index.html") "முழுக் கட்டுரையையும் படிக்க")
      "…per language"))

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
    (let [en  (slurp-out "categories" "guide" "index.html")
          zh  (slurp-out "zh-Hans" "categories" "guide" "index.html")
          en2 (slurp-out "categories" "guide" "page" "2" "index.html")
          zh2 (slurp-out "zh-Hans" "categories" "guide" "page" "2" "index.html")
          order (fn [html] (map second (re-seq #"href=\"(?:/zh-Hans)?(/pages/[^\"]+)\" lang" html)))]
      (is (str/includes? en2 "href=\"/pages/171a98/\"") "Tamil-only: linked at its bare URL (page 2 at 3 per page)")
      (is (re-find #"href=\"/pages/171a98/\"[^<]*</a><span class=\"clogem-fallback\"" en2)
          "…and carries the fallback marker on the English page")
      (is (str/includes? zh "href=\"/zh-Hans/pages/643259/\"") "the zh-Hans variant when it exists")
      (is (str/includes? zh2 "href=\"/pages/171a98/\""))
      (is (= (order en) (order zh)) "sort keys come from the primary — page 1")
      (is (= (order en2) (order zh2)) "…and page 2")
      (is (= 3 (count (order en))) ":per-page 3"))))

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

(deftest the-index-title-comes-from-the-owning-pages-file
  (testing "the demo's categoriesPage.md says `title: Categories`; that title
            is used on the English page only — zh-Hans uses its own theme string"
    (is (str/includes? (slurp-out "categories" "index.html") "<h1>Categories</h1>"))
    (is (str/includes? (slurp-out "zh-Hans" "categories" "index.html") "<h1>分类</h1>"))
    (is (str/includes? (slurp-out "zh-Hans" "categories" "index.html") "<title>分类 ·"))))

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

;; ---------------------------------------------------------------------------
;; Phase 2 — homepage (D-P2-4, D-P2-5)

(deftest homepage-list-is-sticky-then-newest
  (let [html (slurp-out "index.html")
        pos  (fn [s] (str/index-of html s))]
    (is (< (pos "href=\"/pages/3ce486/\"") (pos "href=\"/pages/y2025a/\"") (pos "href=\"/pages/cjk001/\""))
        "sticky: true (rank 1, newer) then sticky: 1 (2025) then the newest unpinned article")
    (is (str/includes? html "<article class=\"clogem-post-card is-sticky\"><h2 class=\"clogem-post-card__title\"><span class=\"clogem-sticky\">Pinned</span>"))
    (is (= 2 (count (re-seq #"is-sticky" html))))
    (is (= 3 (count (re-seq #"<article class=\"clogem-post-card" html))) ":per-page 3")))

(deftest homepage-cards-carry-excerpt-title-tag-and-info-line
  (let [html (slurp-out "index.html")]
    (is (str/includes? html "<span class=\"clogem-title-tag\">原创</span>") "titleTag badge")
    (is (str/includes? html "This post is dated 2025") "the excerpt is the content before <!-- more -->")
    (is (not (str/includes? html "Everything below the")) "…and nothing after it")
    (is (str/includes? html "href=\"/categories/notes/\">Notes</a>") "categories link to their index pages")
    (is (str/includes? html "href=\"/tags/archive/\">archive</a>") "tags too")
    (is (str/includes? html "datetime=\"2025-11-05\"") "ISO date")))

(deftest homepage-paginates-in-detailed-mode-only
  (is (exists? "page" "2" "index.html"))
  (is (exists? "page" "6" "index.html") "17 articles (the two catalogue pages are not articles) at 3 per page")
  (is (not (exists? "page" "7" "index.html")))
  (is (exists? "ms" "page" "2" "index.html") "ms inherits index.md's options")
  (is (not (exists? "zh-Hans" "page" "2" "index.html")) "simple mode has no pagination")
  (let [p2 (slurp-out "page" "2" "index.html")]
    (is (str/includes? p2 "href=\"/\" rel=\"prev\""))
    (is (str/includes? p2 "href=\"/page/3/\" rel=\"next\""))
    (is (str/includes? p2 "href=\"/ms/page/2/\" hreflang=\"ms\"") "the switcher lands on page 2 under ms")
    (is (str/includes? p2 "href=\"/zh-Hans/\" hreflang=\"zh-Hans\"") "…and on zh-Hans's home, which has no page 2")))

(deftest homepage-simple-mode-and-right-bar
  (let [zh (slurp-out "zh-Hans" "index.html")
        en (slurp-out "index.html")]
    (is (str/includes? zh "clogem-list--simple"))
    (is (= 3 (count (re-seq #"<li class=\"clogem-row\"" (first (str/split (second (str/split zh #"clogem-list--simple" 2)) #"</ul>" 2)))))
        "simplePostListLength: 3")
    (is (not (str/includes? zh "clogem-pagination")))
    (is (str/includes? zh "<aside class=\"clogem-home-right\">") "the right bar stays on zh-Hans")
    (is (str/includes? zh ">最近更新<") "the update bar, in the page's language")
    (is (str/includes? zh "href=\"/zh-Hans/archives/\"") "…linking to that language's /archives/")
    (is (not (str/includes? en "clogem-home-right")) "hideRightBar: true on index.md")))

(deftest post-list-mode-and-home-ids
  (is (= :detailed (clogem.theme.home/post-list-mode {})))
  (is (= :simple (clogem.theme.home/post-list-mode {:postList "simple"})))
  (is (= :none (clogem.theme.home/post-list-mode {:postList "none"})))
  (is (= :detailed (clogem.theme.home/post-list-mode {:postList "bogus"})))
  (is (= ["b" "a" "c"] (clogem.theme.home/home-ids {:sticky ["b"] :posts ["a" "b" "c"]}))
      "sticky ++ (posts minus sticky): never twice"))

;; ---------------------------------------------------------------------------
;; Phase 2 — article chrome (D-P2-7)

(deftest breadcrumbs-walk-the-category-path
  (let [html (slurp-out "pages" "643259" "index.html")]
    (is (re-find #"<nav aria-label=\"You are here\" class=\"clogem-breadcrumbs\"><ol><li><a href=\"/\">Home</a></li><li><a href=\"/pages/559f0f/\">Guide</a></li><li><a href=\"/categories/basics/\">Basics</a></li></ol></nav>" html)
        "Guide → its Catalogue page; Basics → its category index"))
  (let [html (slurp-out "zh-Hans" "pages" "643259" "index.html")]
    (is (str/includes? html "<li><a href=\"/zh-Hans/categories/basics/\">基础</a></li>")
        "localized label, per-language index, on the zh-Hans variant"))
  (let [html (slurp-out "pages" "a00015" "index.html")]
    (is (str/includes? html "<li><a href=\"/categories/tech/\">tech</a></li>")
        "a post's crumbs derive from its category")))

(deftest article-info-line-links-categories-and-tags
  (let [html (slurp-out "pages" "284c67" "index.html")]
    (is (str/includes? html "datetime=\"2026-08-16\""))
    (is (str/includes? html "href=\"/categories/notes/\">Notes</a>"))
    (is (str/includes? html "href=\"/tags/meta/\">meta</a>"))
    (is (str/includes? (slurp-out "pages" "y2025a" "index.html") "<h1>A post from the year before<span class=\"clogem-title-tag\">原创</span></h1>"))))

(deftest prev-next-follow-tree-order-for-tree-articles
  (let [conv (slurp-out "pages" "643259" "index.html")
        start (slurp-out "pages" "3ce486" "index.html")
        tamil (slurp-out "pages" "171a98" "index.html")]
    (is (str/includes? conv "href=\"/pages/3ce486/\" lang=\"en\" rel=\"prev\"") "prev = getting-started")
    (is (str/includes? conv "href=\"/pages/22deb7/\" lang=\"en\" rel=\"next\"") "next = Vue.js")
    (is (not (str/includes? start "rel=\"prev\"")) "first leaf of 01.Guide")
    (is (str/includes? start "href=\"/pages/643259/\" lang=\"en\" rel=\"next\""))
    (is (str/includes? tamil "href=\"/pages/c0nta1/\" lang=\"en\" rel=\"prev\"") "across subdirectories, in tree order")
    (is (not (str/includes? tamil "rel=\"next\"")) "last leaf of 01.Guide")
    (is (str/includes? (slurp-out "zh-Hans" "pages" "643259" "index.html") "href=\"/pages/3ce486/\" lang=\"en\" rel=\"prev\"")
        "a neighbour without a zh-Hans variant links to its primary")))

(deftest prev-next-follow-date-order-for-posts
  (let [newest (slurp-out "pages" "284c67" "index.html")
        oldest (slurp-out "pages" "y2025a" "index.html")]
    (is (not (str/includes? newest "rel=\"prev\"")))
    (is (str/includes? newest "href=\"/pages/a00015/\" lang=\"en\" rel=\"next\"") "next = older")
    (is (str/includes? oldest "href=\"/pages/ta0001/\" lang=\"ta\" rel=\"prev\"") "prev = newer (the 2026-03 Tamil post)")
    (is (not (str/includes? oldest "rel=\"next\"")))))

(deftest prev-next-front-matter-overrides
  (let [html (slurp-out "pages" "22deb7" "index.html")]
    (is (not (str/includes? html "rel=\"prev\"")) "prev: false hides it")
    (is (str/includes? html "href=\"/pages/17c887/\" lang=\"en\" rel=\"next\"") "next: /pages/17c887/ overrides tree order")))

(deftest root-relative-links-get-the-base
  (testing "vdoing's $withBase: /assets/… and /categories/ written by an author
            work on a project site (§7.3: clogem-press's own docs live at /clogem-press/)"
    (let [cfg {:site {:base "/project/"}}
          ctx {:cfg cfg :articles {} :by-rel-path {} :lang :en :from-path "x.md" :url-for (fn [_ _] "/x/")}]
      (is (= "/project/assets/x.png" (clogem.markdown/rewrite-href ctx "/assets/x.png")))
      (is (= "/project/categories/" (clogem.markdown/rewrite-href ctx "/categories/")))
      (is (= "/project/assets/x.png" (clogem.markdown/rewrite-href ctx "/project/assets/x.png")) "not doubled")
      (is (= "/assets/x.png" (clogem.markdown/rewrite-href (assoc ctx :cfg {:site {:base "/"}}) "/assets/x.png"))))
    (let [html (str (hiccup2.core/html (clogem.markdown/render "![demo](/assets/demo.png)" {:cfg {:site {:base "/p/"}}})))]
      (is (str/includes? html "src=\"/p/assets/demo.png\"") "image srcs are links too")
      (is (str/includes? html "alt=\"demo\"")))))

;; ---------------------------------------------------------------------------
;; Phase 2 — catalogue pages (D-P2-8), nav (D-P2-11), doctor

(deftest catalogue-pages-render-a-card-grid-instead-of-the-body
  (let [guide (slurp-out "pages" "559f0f" "index.html")
        deep  (slurp-out "pages" "c4d33p" "index.html")]
    (is (str/includes? guide "clogem-catalogue__grid"))
    (is (not (str/includes? guide "Catalogue pages render a card grid")) "the body is NOT rendered")
    (is (str/includes? guide "<h3>Basics<span class=\"clogem-bar__count\">Articles: 4</span></h3>"))
    (is (str/includes? guide "<h3>Advanced<span class=\"clogem-bar__count\">Articles: 1</span></h3>"))
    (is (= 1 (count (re-seq #"/pages/643259/\"" guide))) "three variants, one row")
    (is (str/includes? deep "<img alt=\"\" class=\"clogem-catalogue__img\" src=\"/assets/demo.png\" />") "imgUrl")
    (is (str/includes? deep "three-level 03.Deep subtree") "description")
    (is (str/includes? deep "<h3>Level2<span") "one card per child directory…")
    (is (str/includes? deep "<div class=\"clogem-catalogue__sub\"><h4>Level3</h4>") "…nested to the tree's depth")
    (is (str/includes? deep "/pages/ebaa6b/"))
    (is (not (str/includes? deep "clogem-sidebar")) "no tree on a catalogue page")
    (is (not (exists? "zh-Hans" "pages" "c4d33p" "index.html"))
        "a catalogue page is an article with one variant: no /zh-Hans/ copy")))

(deftest catalogue-model-table-feeds-breadcrumbs-and-nav
  (is (= {"/01.Guide" "/pages/559f0f/" "/03.Deep" "/pages/c4d33p/"} (:catalogue *model*)))
  (is (str/includes? (slurp-out "pages" "ebaa6b" "index.html") "<li><a href=\"/pages/c4d33p/\">Deep</a></li>")
      "the Deep crumb links to the Deep catalogue"))

(deftest unknown-or-unresolved-page-components-warn-and-fall-back
  (let [dir (fs/create-temp-dir {:prefix "clogem-pc"})]
    (try
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file (fs/path dir "content" "01.Guide" "01.a.md")) "---\ntitle: A\npermalink: /pages/aaaaaa/\n---\n\nbody\n")
      (spit (fs/file (fs/path dir "content" "01.Guide" "02.bad.md"))
            "---\ntitle: Bad\npermalink: /pages/bbbbbb/\npageComponent:\n  name: Widget\n---\n\nfallback body\n")
      (spit (fs/file (fs/path dir "content" "01.Guide" "03.nowhere.md"))
            "---\ntitle: Nowhere\npermalink: /pages/cccccc/\npageComponent:\n  name: Catalogue\n  data:\n    path: 09.Missing\n---\n\nnowhere body\n")
      (let [cfg (first (diag/collecting (config/load-config (str dir) nil {:content {:write-front-matter false}})))
            [m _] (diag/collecting (cli/analyse cfg))
            [pm ds] (diag/collecting (let [pm (render/page-map m)] (doseq [[_ f] pm] (f)) pm))
            html (fn [uri] (str (hiccup2.core/html ((get pm uri)))))
            msgs (map :message (diag/warnings ds))]
        (is (some #(re-find #"unknown pageComponent `Widget`" %) msgs))
        (is (some #(re-find #"09\.Missing" %) msgs))
        (is (str/includes? (html "/pages/bbbbbb/") "fallback body"))
        (is (str/includes? (html "/pages/cccccc/") "nowhere body")))
      (finally (fs/delete-tree dir)))))

(deftest nav-links-are-prefixed-per-language-with-dropdowns
  (let [zh (slurp-out "zh-Hans" "index.html")
        en (slurp-out "index.html")]
    (is (str/includes? zh "<a href=\"/zh-Hans/\">首页</a>") "`/` → `/zh-Hans/`")
    (is (str/includes? zh "<a href=\"/zh-Hans/categories/\">分类</a>") "site pages get the prefix")
    (is (str/includes? zh "<summary><a href=\"/pages/559f0f/\">指南</a></summary>")
        "a permalink resolves to the article (en-only → bare), and a parent may carry both link and items")
    (is (str/includes? zh "<ul class=\"clogem-navbar__menu\">") ":items make a dropdown")
    (is (str/includes? en "<a href=\"/\">Home</a>"))
    (is (str/includes? en "<a href=\"/categories/\">Categories</a>"))))

(deftest doctor-is-clean-on-the-demo-apart-from-the-mechanism-2-warning
  (testing "doctor-checks! and the in-memory render pass fire only the warning
            the demo tree deliberately contains"
    (let [{:keys [warnings errors]}
          (binding [diag/*sink* (atom [])] ; keep the report off stderr
            (cli/doctor {:site-dir demo}))]
      (is (empty? errors))
      (is (= 1 (count warnings)) (pr-str (map :message warnings)))
      (is (re-find #"different directories" (:message (first warnings)))))))

;; ---------------------------------------------------------------------------
;; Phase 2 — TOC bar (D-P2-9)

(deftest articles-carry-a-toc-built-from-the-same-ast
  (let [html (slurp-out "pages" "643259" "index.html")]
    (is (str/includes? html "<aside class=\"clogem-toc\"><nav aria-label=\"On this page\"><ul>"))
    (is (str/includes? html "<li class=\"level-2\"><a href=\"#numbered-directories\">Numbered directories</a></li>"))
    (is (str/includes? html "<li class=\"level-2\"><a href=\"#language-suffixes\">Language suffixes</a></li>"))
    (is (not (str/includes? html "<a href=\"#conventions\">")) "the leading h1 is not in the TOC")
    (is (str/includes? html "clogem-shell--toc")))
  (let [html (slurp-out "pages" "17c887" "index.html")]
    (is (str/includes? html "<a href=\"#%E0%AE%B5%E0%AE%A3%E0%AE%95%E0%AF%8D%E0%AE%95%E0%AE%AE%E0%AF%8D-%E0%AE%89%E0%AE%B2%E0%AE%95%E0%AE%AE%E0%AF%8D\">வணக்கம் உலகம்</a>")
        "Tamil: encoded href in the TOC…")
    (is (str/includes? html "id=\"வணக்கம்-உலகம்\"") "…unencoded id on the heading — the scroll-spy must decode"))
  (let [html (slurp-out "pages" "3ce486" "index.html")]
    (is (not (str/includes? html "clogem-toc")) "no h2/h3 → no TOC aside at all")))

(deftest the-scroll-spy-is-vendored-and-loaded-with-defer
  (is (exists? "clogem" "js" "toc.js"))
  (is (str/includes? (slurp-out "clogem" "js" "toc.js") "decodeURIComponent")
      "ids are unencoded, hrefs are percent-encoded — mandatory for CJK/Tamil pages")
  (is (str/includes? (slurp-out "pages" "643259" "index.html") "<script defer=\"defer\" src=\"/clogem/js/toc.js\"></script>"))
  (testing "fix 18: only pages that render a TOC load it"
    (is (not (str/includes? (slurp-out "index.html") "toc.js")))
    (is (not (str/includes? (slurp-out "categories" "index.html") "toc.js")))))

(deftest pages-carry-the-localized-site-description
  (testing "fix 18: <meta name=description> from :site :description, per language"
    (let [cfg-f #(assoc-in % [:site :description] {:en "English desc" :zh-Hans "中文描述"})]
      (is (str/includes? (page-html cfg-f "/") "<meta content=\"English desc\" name=\"description\" />"))
      (is (str/includes? (page-html cfg-f "/zh-Hans/categories/") "<meta content=\"中文描述\" name=\"description\" />"))
      (is (not (str/includes? (page-html #(assoc-in % [:site :description] nil) "/") "name=\"description\""))))))

;; ---------------------------------------------------------------------------
;; Phase 2 — containers (D-P2-10)

(deftest containers-render-with-titles-in-the-page-language
  (let [en (slurp-out "pages" "c0nta1" "index.html")
        zh (slurp-out "zh-Hans" "pages" "c0nta1" "index.html")]
    (is (str/includes? en "<div class=\"custom-block tip\">\n<p class=\"custom-block-title\">TIP</p>"))
    (is (str/includes? en "<p class=\"custom-block-title\">Custom title</p>"))
    (is (str/includes? en "<details class=\"custom-block details\"><summary>Details</summary>"))
    (is (str/includes? en "<div class=\"custom-block theorem\"><p class=\"title\">Theorem</p>"))
    (is (str/includes? en "<div style=\"text-align:right\">"))
    (is (str/includes? en "<div style=\"text-align:center\">"))
    (is (str/includes? en "<p class=\"custom-block-title\">Inner</p>") "nested")
    (is (str/includes? en "<li><p>An item</p><div class=\"custom-block note\">") "inside a list item")
    (is (str/includes? en "::: tip\nThis is literal text, not a container.\n:::\n</code>") "immune inside a code fence")
    (is (str/includes? en "<a href=\"#heading-inside-a-container\">Heading inside a container</a>")
        "a heading inside a container is in the TOC")
    (is (str/includes? zh "<p class=\"custom-block-title\">提示</p>") "zh-Hans default title")
    (is (str/includes? zh "<p class=\"title\">定理</p>"))
    (is (str/includes? zh "<summary>详情</summary>"))
    (is (not (str/includes? zh ">TIP<")))))

(deftest card-lists-render-with-rewritten-links
  (let [en (slurp-out "pages" "ca4d51" "index.html")]
    (is (str/includes? en "<div class=\"card-list row-2\">"))
    (is (str/includes? en "href=\"/pages/643259/\""))
    (is (str/includes? en "href=\"https://github.com/EchoJustus/clogem-press\" target=\"_blank\""))
    (is (str/includes? en "style=\"background-color:#3eaf7c;color:#ffffff\""))
    (is (str/includes? en "<div class=\"card-img-list row-3\">"))
    (is (str/includes? en "src=\"/assets/demo.png\""))
    (is (str/includes? en "style=\"height:80px\""))))

(deftest a-bad-card-list-is-a-doctor-warning-naming-the-file
  (let [dir (fs/create-temp-dir {:prefix "clogem-badcards"})]
    (try
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (fs/copy "examples/demo-site/doctor-cases/01.bad-cards.md.disabled"
               (fs/path dir "content" "01.Guide" "01.bad-cards.md"))
      (let [{:keys [warnings errors]}
            (binding [diag/*sink* (atom [])]
              (cli/doctor {:site-dir (str dir) :no-write true}))]
        (is (empty? errors) "malformed card YAML is a warning, not a build error")
        (is (some #(and (re-find #"could not parse the YAML" (:message %))
                        (re-find #"01\.bad-cards\.md" (str (:path %))))
                  warnings)
            (pr-str warnings)))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; Phase 2 — Unicode category/tag values and the tag union (D-P2-2, D-P2-3)

(deftest cjk-and-tamil-category-and-tag-values
  (testing "keys are kept verbatim as the directory name and the URL; hrefs are percent-encoded"
    (is (exists? "categories" "中文笔记" "index.html"))
    (is (exists? "tags" "中文" "index.html"))
    (is (exists? "categories" "தமிழ்" "index.html"))
    (is (exists? "tags" "தமிழ்" "index.html"))
    (let [html (slurp-out "categories" "index.html")]
      (is (str/includes? html "href=\"/categories/%E4%B8%AD%E6%96%87%E7%AC%94%E8%AE%B0/\">中文笔记</a>"))
      (is (str/includes? html "href=\"/categories/%E0%AE%A4%E0%AE%AE%E0%AE%BF%E0%AE%B4%E0%AF%8D/\">தமிழ்</a>")))
    (is (str/includes? (slurp-out "pages" "cjk001" "index.html") "lang=\"zh-Hans\"")
        "a zh-Hans-only article serves zh-Hans at its bare URL")))

(deftest tags-index-includes-a-tag-only-a-non-primary-variant-carries
  (testing "§6.2: tags are the union across variants"
    (let [g (group-titled "Containers")]
      (is (= #{"markdown" "容器"} (set (:tags g))))
      (is (= [(:permalink g)] (get-in *model* [:tags "容器"])))
      (is (exists? "tags" "容器" "index.html"))
      (is (str/includes? (slurp-out "tags" "index.html") ">容器<")))
    (is (= 2 (count (get-in *model* [:tags "தமிழ்"]))) "the Tamil tag is on the tree article AND the post")))

;; ---------------------------------------------------------------------------
;; The four usage modes (§1, §8 Phase 2 exit criterion)

(defn- temp-tree
  "Materialize {rel-path → content} under a temp content/ tree; return [model uris]."
  [files & [cfg-overrides]]
  (let [dir (fs/create-temp-dir {:prefix "clogem-mode"})]
    (try
      (doseq [[rel content] files
              :let [f (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent f))
        (spit (fs/file f) content))
      (let [cfg (first (diag/collecting
                        (config/load-config (str dir) nil
                                            (merge {:content {:write-front-matter false}} cfg-overrides))))
            [m _] (diag/collecting (cli/analyse cfg))
            ;; render everything NOW — the thunks read files under `dir`,
            ;; which is deleted before the caller looks at the result
            [rendered ds] (diag/collecting
                           (into {} (map (fn [[u f]] [u (str (hiccup2.core/html (f)))]))
                                 (render/page-map m)))]
        (is (empty? (diag/errors ds)))
        [m (set (keys rendered)) (fn [uri] (get rendered uri)) ds])
      (finally (fs/delete-tree dir)))))

(def ^:private a-post "---\ntitle: P\ndate: \"2026-01-01 00:00:00\"\npermalink: /pages/p00001/\ntags: [t]\n---\n\n# P\n\nbody\n")
(def ^:private a-tree "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n# T\n\n## H2\n\nbody\n")

(deftest blog-only-mode
  (testing "only _posts/: empty sidebar, populated posts and indexes"
    (let [[m uris] (temp-tree {"_posts/2026-01-01-p.md" a-post})]
      (is (empty? (:sidebar m)))
      (is (= ["/pages/p00001/"] (:posts m)))
      (is (= {"Notes" ["/pages/p00001/"]} (:categories m)) ":content :category-text")
      (is (= {"t" ["/pages/p00001/"]} (:tags m)))
      (is (contains? uris "/categories/notes/"))
      (is (contains? uris "/tags/t/"))
      (is (contains? uris "/archives/"))
      (is (contains? uris "/pages/p00001/")))))

(deftest kb-only-mode
  (testing "no _posts/: posts still populated from tree articles (D-P2-4)"
    (let [[m uris] (temp-tree {"01.Guide/10.Basics/01.t.md" a-tree})]
      (is (= ["01.Guide"] (keys (:sidebar m))))
      (is (= ["/pages/t00001/"] (:posts m)))
      (is (= {"Guide" ["/pages/t00001/"] "Basics" ["/pages/t00001/"]} (:categories m)))
      (is (contains? uris "/categories/basics/"))
      (is (contains? uris "/")))))

(deftest docs-mode
  (testing ":content {:category false :tag false :archive false} → no index URIs at all"
    (let [[m uris] (temp-tree {"01.Guide/10.Basics/01.t.md" a-tree
                               "_posts/2026-01-01-p.md" a-post}
                              {:content {:category false :tag false :archive false}})]
      (is (seq (:sidebar m)) "the tree still renders")
      (is (not-any? #(re-find #"/(categories|tags|archives)/" %) uris))
      (is (contains? uris "/pages/t00001/"))
      (is (contains? uris "/")))))

(deftest kb-plus-blog-mode
  (testing "the demo itself: tree + posts, every index, every language"
    (is (seq (:sidebar *model*)))
    (is (some #(= :post (get-in *model* [:articles % :kind])) (:posts *model*)))
    (is (some #(= :tree (get-in *model* [:articles % :kind])) (:posts *model*)))
    (is (exists? "categories" "index.html"))
    (is (exists? "ta" "archives" "index.html"))))


;; ---------------------------------------------------------------------------
;; Review pass — findings pinned

(deftest a-user-authored-pages-body-renders-above-the-list-on-its-own-language
  (testing "D-P2-6 + §6.4 rule 2: the body of @pages/categoriesPage.md renders
            above the bar on the language that owns the file, and nowhere else"
    (let [[_ _ html] (temp-tree {"01.Guide/10.Basics/01.t.md" a-tree
                                 "@pages/categoriesPage.md" "---\ncategoriesPage: true\ntitle: Cats\npermalink: /categories/\narticle: false\n---\n\nUSER BODY HERE\n"
                                 "@pages/categoriesPage.zh-Hans.md" "---\ncategoriesPage: true\ntitle: 分类页\narticle: false\n---\n\n用户正文\n"})
          en (html "/categories/")
          zh (html "/zh-Hans/categories/")
          ms (html "/ms/categories/")]
      (is (re-find #"USER BODY HERE.*clogem-bar" en) "above the generated list")
      (is (str/includes? en "<h1>Cats</h1>"))
      (is (str/includes? zh "用户正文") "zh-Hans owns a file of its own")
      (is (str/includes? zh "<h1>分类页</h1>"))
      (is (not (str/includes? ms "USER BODY HERE")) "ms has no file: no English body leaks in")
      (is (str/includes? ms "<h1>Kategori</h1>")))))

(deftest prefix-default-true-keeps-index-pages-bare-and-stubs-only-articles
  (testing "D-P2-3: homes and index pages follow the site-default rule in
            BOTH modes; under :prefix-default? true only articles get stubs"
    (let [pm (first (diag/collecting (render/page-map (assoc-in *model* [:cfg :i18n :prefix-default?] true))))
          uris (set (keys pm))
          html (fn [uri] (str (hiccup2.core/html ((get pm uri)))))]
      (is (contains? uris "/categories/"))
      (is (not (contains? uris "/en/categories/")))
      (is (contains? uris "/"))
      (is (not (contains? uris "/en/")))
      (is (contains? uris "/en/pages/643259/") "every variant prefixed")
      (is (str/includes? (html "/pages/643259/") "http-equiv=\"refresh\"") "the bare URL is a stub")
      (is (str/includes? (html "/categories/basics/") "href=\"/en/pages/643259/\"") "rows link the prefixed article")
      (is (str/includes? (html "/en/pages/643259/") "href=\"/zh-Hans/pages/643259/\" hreflang=\"zh-Hans\"")))))

(deftest sidebar-depth-front-matter-governs-the-toc
  (let [body (fn [depth] (str "---\ntitle: T\npermalink: /pages/t00001/\nsidebarDepth: " depth "\n---\n\n# T\n\n## H2\n\n### H3\n\n#### H4\n"))]
    (doseq [[depth expected] [[0 []] [1 ["H2"]] [3 ["H2" "H3" "H4"]]]]
      (let [[_ _ html] (temp-tree {"01.Guide/01.t.md" (body depth)})
            page (html "/pages/t00001/")
            items (map second (re-seq #"<li class=\"level-\d\"><a href=\"#[^\"]*\">([^<]*)</a>" page))]
        (is (= expected items) (str "sidebarDepth " depth))
        (when (empty? expected)
          (is (not (str/includes? page "clogem-toc")) "sidebarDepth: 0 = no TOC at all"))))))

(deftest a-fourth-level-directory-renders-anyway
  (testing "D-P2-1: deeper than level 3 renders; doctor warns (model_test covers the warning)"
    (let [[m uris html] (temp-tree {"01.A/10.B/20.C/30.D/01.deep.md" a-tree})]
      (is (contains? uris "/pages/t00001/"))
      (is (str/includes? (html "/pages/t00001/") "<summary>D</summary>"))
      (is (= 4 (count (model/tree-dirs (:tree m))))))))

(deftest archives-span-two-years
  (let [html (slurp-out "archives" "index.html")]
    (is (str/includes? html "<h2>2025</h2>"))
    (is (< (str/index-of html "<h2>2026</h2>") (str/index-of html "<h2>2025</h2>")) "newest year first")
    (is (str/includes? html "<h3>2025-11</h3>"))
    (is (re-find #"<h3>2025-11</h3>.*?/pages/y2025a/" html))))

(deftest neighbours-are-not-skipped-for-being-non-articles
  (testing "D-P2-7: in the tree, the Guide catalogue's next is the Deep
            catalogue — a non-article leaf"
    (is (str/includes? (slurp-out "pages" "559f0f" "index.html") "href=\"/pages/c4d33p/\" lang=\"en\" rel=\"next\"")))
  (testing "…and among posts too"
    (let [post (fn [d pl & [extra]] (str "---\ntitle: P" pl "\ndate: \"" d "\"\npermalink: /pages/" pl "/\n" (or extra "") "---\n\nbody\n"))
          [_ _ html] (temp-tree {"_posts/2026-03-01-a.md" (post "2026-03-01 00:00:00" "aaaaaa")
                                 "_posts/2026-02-01-b.md" (post "2026-02-01 00:00:00" "bbbbbb" "article: false\n")
                                 "_posts/2026-01-01-c.md" (post "2026-01-01 00:00:00" "cccccc")})]
      (is (str/includes? (html "/pages/aaaaaa/") "href=\"/pages/bbbbbb/\" lang=\"en\" rel=\"next\"")
          "the article: false post is still the neighbour")
      (is (str/includes? (html "/pages/bbbbbb/") "href=\"/pages/aaaaaa/\" lang=\"en\" rel=\"prev\""))
      (is (str/includes? (html "/pages/bbbbbb/") "href=\"/pages/cccccc/\" lang=\"en\" rel=\"next\"")))))

(deftest excerpt-paths-and-the-fallback-notice-toggle
  (testing "the content before <!-- more -->"
    (is (str/includes? (slurp-out "index.html") "This article exists in English only")))
  (testing "the L variant's excerpt when it exists, else the primary's"
    ;; one big page, so the conventions article is on it in every language
    (let [big #(assoc-in % [:theme :per-page] 100)]
      (is (str/includes? (page-html big "/zh-Hant/") "這三個檔案共用") "zh-Hant home, zh-Hant variant")
      (is (str/includes? (page-html big "/ms/") "Three files share this number") "ms home, primary (en) variant")))
  (testing ":show-fallback-notice false removes every marker"
    (let [html (page-html #(assoc-in % [:i18n :show-fallback-notice] false) "/zh-Hans/categories/guide/")]
      (is (str/includes? html "clogem-row"))
      (is (not (str/includes? html "clogem-fallback"))))))

(deftest html-lang-matches-on-filtered-and-paginated-pages
  (doseq [[path lang] [[["zh-Hans" "categories" "basics" "index.html"] "zh-Hans"]
                       [["ta" "tags" "meta" "index.html"] "ta"]
                       [["ms" "page" "2" "index.html"] "ms"]
                       [["zh-Hant" "categories" "guide" "page" "2" "index.html"] "zh-Hant"]
                       [["zh-Hant" "pages" "643259" "index.html"] "zh-Hant"]]]
    (is (str/includes? (apply slurp-out path) (str "<html dir=\"ltr\" lang=\"" lang "\">")) (pr-str path))))

(deftest an-article-page-carries-exactly-one-h1
  (testing "the theme renders the title; the body's leading `# Title` is dropped (D-P2-9)"
    (let [html (slurp-out "pages" "643259" "index.html")]
      (is (= 1 (count (re-seq #"<h1[ >]" html))))
      (is (str/includes? html "<h1>conventions</h1>"))
      (is (not (str/includes? html "<h1 id=\"conventions\"")))
      (is (str/includes? html "<h2 id=\"numbered-directories\"") "the rest of the body is intact"))))

(deftest a-one-language-site-has-no-switcher
  (let [[m uris html] (temp-tree {"01.Guide/01.t.md" a-tree}
                                 {:langs {:locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}})]
    (is (= [:en] (config/lang-keys (:cfg m))))
    (is (not-any? #(re-find #"^/(zh-Hans|zh-Hant|ms|ta)/" %) uris))
    (is (not (str/includes? (html "/pages/t00001/") "clogem-langs")))))

(deftest a-dangling-nav-permalink-is-base-only-and-a-doctor-warning
  (let [[m _ html ds] (temp-tree {"01.Guide/01.t.md" a-tree}
                                 {:nav [{:text "Gone" :link "/pages/nope00/"}]})
        [_ dds] (diag/collecting (model/doctor-checks! m))]
    (is (str/includes? (html "/zh-Hans/") "<a href=\"/pages/nope00/\">Gone</a>")
        "no language prefix is invented for a permalink that names nothing")
    (is (some #(re-find #":nav link /pages/nope00/ names no article" (:message %)) (diag/warnings dds)))
    (is (empty? (diag/warnings ds)) "…and rendering itself stays quiet")))

;; ---------------------------------------------------------------------------
;; Fix round 0.1.1

(defn- with-cli-site
  "Write {rel-path → content} under a temp site's content/ (plus an optional
  site.edn) and call (f dir out)."
  [files f & [site-edn]]
  (let [dir (fs/create-temp-dir {:prefix "clogem-cli"})]
    (try
      (when site-edn (spit (fs/file dir "site.edn") (pr-str site-edn)))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (binding [diag/*sink* (atom [])]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- build-fails
  "Run `build` (optionally --no-write) and return the ExceptionInfo it raises."
  [dir out no-write]
  (try (cli/build {:site-dir (str dir) :out (str out) :no-write no-write}) nil
       (catch clojure.lang.ExceptionInfo e e)))

(deftest a-content-error-under-no-write-leaves-no-dist
  (testing "fix 2: --no-write skips pass 1, so analyse errors must gate render"
    (doseq [[what files] [["malformed YAML" {"01.Guide/01.a.md" "---\ntitle: [unclosed\n---\n\nbody\n"}]
                          ["duplicate permalink" {"01.Guide/01.a.md" a-tree
                                                  "01.Guide/02.b.md" a-tree}]]]
      (with-cli-site files
        (fn [dir out]
          (let [e (build-fails dir out true)]
            (is (some? e) what)
            (is (= 1 (:babashka/exit (ex-data e))) what)
            (is (not (fs/exists? out)) (str what ": no dist/ directory at all"))
            (is (not (fs/exists? (fs/path dir "permalinks.edn"))) (str what ": no ledger"))))))))

(deftest slug-collisions-fail-the-build
  (testing "fix 12: `_posts/notes/` beside the default category \"Notes\""
    (with-cli-site {"_posts/2026-01-01-p.md" a-post
                    "_posts/notes/2026-01-02-q.md" (str/replace a-post "p00001" "q00001")}
      (fn [dir out]
        (doseq [no-write [true false]]
          (let [e (build-fails dir out no-write)]
            (is (some? e))
            (is (re-find #"category names share the URL slug `notes`" (str (ex-message e))))
            (is (not (fs/exists? out))))))))
  (testing "…and two tags that differ only by case"
    (with-cli-site {"_posts/2026-01-01-p.md" a-post
                    "_posts/2026-01-02-q.md" (-> a-post (str/replace "p00001" "q00001") (str/replace "[t]" "[T]"))}
      (fn [dir out]
        (let [e (build-fails dir out true)]
          (is (re-find #"tag names share the URL slug `t`" (str (ex-message e))))
          (is (not (fs/exists? out))))
        (let [e (try (cli/doctor {:site-dir (str dir)}) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= 1 (:babashka/exit (ex-data e))) "doctor exits 1 too"))
        (let [e (try (cli/fm-fix {:site-dir (str dir)}) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= 1 (:babashka/exit (ex-data e))) "…and fm-fix"))))))

(deftest a-bad-front-matter-sidebar-depth-warns-with-the-path
  (doseq [bad ["auto" "9" "-1" "1.5"]]
    (let [[_ _ html ds] (temp-tree {"01.Guide/01.t.md" (str "---\ntitle: T\npermalink: /pages/t00001/\nsidebarDepth: " bad "\n---\n\n# T\n\n## H2\n\n### H3\n")})]
      (is (some #(and (= "01.Guide/01.t.md" (:path %)) (re-find #"sidebarDepth" (:message %)))
                (diag/warnings ds))
          bad)
      (is (str/includes? (html "/pages/t00001/") ">H3<") "falls back to the default depth 2")))
  (let [[_ _ html ds] (temp-tree {"01.Guide/01.t.md" "---\ntitle: T\npermalink: /pages/t00001/\nsidebarDepth: \"1\"\n---\n\n# T\n\n## H2\n\n### H3\n"})]
    (is (empty? (diag/warnings ds)) "a digit string is accepted")
    (is (not (re-find #"level-3" (html "/pages/t00001/"))))))

(deftest emoji-categories-and-headings-link-to-real-targets
  (testing "fix 1, end to end: no `%3F%3F` href, and the href decodes to the directory"
    (let [[_ uris html] (temp-tree {"01.😀Fun/01.t.md" (str/replace a-tree "## H2" "## Hello 😀 world")})
          page (html "/pages/t00001/")]
      (is (contains? uris "/categories/😀fun/"))
      (is (str/includes? page "href=\"/categories/%F0%9F%98%80fun/\""))
      (is (str/includes? page "href=\"#hello-%F0%9F%98%80-world\""))
      (is (str/includes? page "id=\"hello-😀-world\""))
      (is (not (str/includes? page "%3F%3F"))))))

(deftest excerpt-diagnostics-are-reported-once
  (testing "fix 4: a dead link before the marker warns once — from the article
            page — not once per language home as well"
    (let [[_ _ _ ds] (temp-tree {"_posts/2026-01-01-p.md"
                                 (str/replace a-post "body\n" "See [x](missing-page.md).\n\n<!-- more -->\n\nRest.\n")})
          dead (filter #(re-find #"missing-page" (str (:message %) (:hint %))) (diag/warnings ds))]
      (is (= 1 (count dead)) (pr-str (map :message dead)))))
  (testing "a marker inside `::: tip` is not an unclosed container"
    (let [[_ _ html ds] (temp-tree {"_posts/2026-01-01-p.md"
                                    (str/replace a-post "body\n" "::: tip\nBefore.\n\n<!-- more -->\n\nAfter.\n:::\n")})]
      (is (not-any? #(re-find #"(?i)unclosed" (:message %)) (diag/warnings ds)))
      (is (str/includes? (html "/") "clogem-post-card__excerpt")))))

(deftest no-marker-means-no-excerpt
  (testing "fix 15: VuePress/vdoing take an excerpt only from <!-- more -->"
    (let [[_ _ html] (temp-tree {"_posts/2026-01-01-p.md" a-post})]
      (doseq [home ["/" "/zh-Hans/" "/ta/"]]
        (is (str/includes? (html home) "clogem-post-card") home)
        (is (not (str/includes? (html home) "clogem-post-card__excerpt")) home)
        (is (not (str/includes? (html home) "clogem-post-card__more")) "nothing to read more of")))))

(deftest homes-without-tags-have-no-empty-tags-card
  (testing "fix 11: `tags: []` everywhere → no Tags box on any home"
    (let [[m _ html] (temp-tree {"01.Notes/01.t.md" a-tree})]
      (is (empty? (:tags m)))
      (doseq [home ["/" "/zh-Hans/" "/zh-Hant/" "/ms/" "/ta/"]]
        (is (not (str/includes? (html home) "clogem-home-tags")) home)
        (is (str/includes? (html home) "clogem-home-cats") "categories exist, so that card stays")))))

(deftest every-demo-page-has-exactly-one-h1
  (testing "fix 18: homes without a body of their own get the site title as h1"
    (doseq [f (fs/glob *out-dir* "**/index.html")
            :let [html (slurp (fs/file f))]]
      (is (= 1 (count (re-seq #"<h1[ >]" html))) (str (fs/relativize *out-dir* f))))))

(deftest overview-pages-list-every-article
  (testing "fix 11: no tags at all → no \"All 0\" bar, but the posts are listed"
    (let [[_ uris html] (temp-tree {"01.Notes/01.t.md" a-tree
                                    "_posts/2026-01-01-p.md" (str/replace a-post "tags: [t]\n" "tags: []\n")})]
      (doseq [l ["" "/zh-Hans" "/ta"]
              :let [tags (html (str l "/tags/"))
                    cats (html (str l "/categories/"))]]
        (is (not (str/includes? tags "clogem-bar")) "no bar over an empty index")
        (is (str/includes? tags "/pages/t00001/") "the post list is shown anyway")
        (is (str/includes? tags "/pages/p00001/"))
        (is (str/includes? cats "clogem-bar"))
        (is (str/includes? cats "/pages/t00001/") "/categories/ lists every article")
        (is (str/includes? cats "/pages/p00001/")))
      (is (not (contains? uris "/tags/page/2/")) "one page at :per-page 10")))
  (testing "an empty site shows the empty notice"
    (let [[_ _ html] (temp-tree {"01.Notes/01.cat.md" "---\ntitle: C\npermalink: /pages/c00001/\narticle: false\n---\n\nx\n"})]
      (is (str/includes? (html "/tags/") "clogem-empty"))))
  (testing "the overview paginates at :per-page"
    (let [[_ uris html] (temp-tree {"01.Notes/01.t.md" a-tree
                                    "_posts/2026-01-01-p.md" a-post}
                                   {:theme {:per-page 1}})]
      (is (contains? uris "/categories/page/2/"))
      (is (contains? uris "/zh-Hans/tags/page/2/"))
      (is (str/includes? (html "/categories/") "href=\"/categories/page/2/\" rel=\"next\""))
      (is (str/includes? (html "/categories/page/2/") "hreflang=\"ms\"")))))

(deftest filtered-index-pages-have-their-own-title
  (testing "fix 13: <title> follows the <h1>, in every language"
    (let [[_ _ html] (temp-tree {"_posts/2026-01-01-p.md" a-post
                                 "_posts/2026-01-02-q.md" (str/replace a-post "p00001" "q00001")}
                                {:theme {:per-page 1}})]
      (doseq [[l cat tag] [["" "Category: " "Tag: "] ["/zh-Hans" "分类：" "标签："]
                           ["/zh-Hant" "分類：" "標籤："] ["/ms" "Kategori: " "Tag: "]
                           ["/ta" "பிரிவு: " "குறிச்சொல்: "]]]
        (is (str/includes? (html (str l "/categories/notes/")) (str "<title>" cat "Notes")) l)
        (is (str/includes? (html (str l "/tags/t/page/2/")) (str "<title>" tag "t")) l)))))

(deftest zh-hant-archives-reads-gui-dang
  (testing "fix 18: 封存 means \"sealed\"; the archive page is 歸檔"
    (let [html (slurp-out "zh-Hant" "archives" "index.html")]
      (is (str/includes? html "<h1>歸檔</h1>"))
      (is (not (str/includes? html "封存"))))))

(deftest the-switcher-marks-an-untranslated-target
  (testing "fix 14: on an English-only article the ta entry lands on /ta/ and says why"
    (let [html (slurp-out "pages" "3ce486" "index.html")]
      (is (re-find #"<a class=\"is-untranslated\" href=\"/ta/\" hreflang=\"ta\" lang=\"ta\" title=\"Shown in English — not yet translated\">தமிழ்<span class=\"clogem-visually-hidden\" lang=\"en\"> \(Shown in English — not yet translated\)</span></a>" html))
      (is (= 4 (count (re-seq #"class=\"is-untranslated\"" html))) "every language the article lacks, not the current one")))
  (testing "a translated target is not marked"
    (let [html (slurp-out "pages" "643259" "index.html")]
      (is (re-find #"<a href=\"/zh-Hans/pages/643259/\" hreflang=\"zh-Hans\"" html))
      (is (re-find #"class=\"is-untranslated\" href=\"/ms/\"" html))))
  (testing "index pages have no untranslated entries"
    (is (not (str/includes? (slurp-out "categories" "index.html") "is-untranslated"))))
  (testing ":show-fallback-notice false drops the text but keeps the class"
    (let [html (page-html #(assoc-in % [:i18n :show-fallback-notice] false) "/pages/3ce486/")]
      (is (str/includes? html "class=\"is-untranslated\" href=\"/ta/\""))
      (is (not (str/includes? html "clogem-visually-hidden"))))))

(deftest category-labels-apply-to-sidebar-groups-and-catalogue-headings
  (testing "fix 3: a string label goes through category-label's string branch in every language"
    (let [cfg-f #(assoc-in % [:i18n :category-labels "Basics"] "BasicsLabel")]
      (is (str/includes? (page-html cfg-f "/pages/559f0f/") "<h3>BasicsLabel") "catalogue card heading")
      (doseq [l ["" "/zh-Hans" "/zh-Hant"]]
        (is (str/includes? (page-html cfg-f (str l "/pages/643259/")) "<summary>BasicsLabel</summary>") (str l " sidebar")))))
  (testing "the demo's map label localizes the sidebar group on zh-Hans"
    (is (str/includes? (slurp-out "zh-Hans" "pages" "643259" "index.html") "<summary>基础</summary>"))
    (is (str/includes? (slurp-out "pages" "643259" "index.html") "<summary>Basics</summary>"))))

;; ---------------------------------------------------------------------------
;; Fix round D.2.1

(def ^:private bad-yaml "---\npostList: [bad\n---\n\nbody\n")

(defn- doctor-errors
  "Run `doctor` over `dir`; return its error diagnostics whether or not it raised."
  [dir]
  (try (:errors (cli/doctor {:site-dir (str dir)}))
       (catch clojure.lang.ExceptionInfo e (:clogem/errors (ex-data e)))))

(deftest bad-yaml-outside-the-tree-fails-the-build-before-dist
  (testing "fix A: index*.md and @pages/* are parsed during analyse, so a YAML
            error there stops build (both modes) before dist/ exists, and
            doctor reports it exactly once — not once per language home"
    (doseq [rel ["index.md" "index.zh-Hans.md" "@pages/tagsPage.md" "@pages/tagsPage.MS.md"]]
      (with-cli-site {"01.Guide/01.t.md" a-tree rel bad-yaml}
        (fn [dir out]
          (doseq [no-write [true false]]
            (let [e (build-fails dir out no-write)]
              (is (some? e) (str rel " no-write=" no-write))
              (is (= 1 (:babashka/exit (ex-data e))) rel)
              (is (re-find #"malformed YAML" (str (ex-message e))) rel)
              (is (not (fs/exists? out)) (str rel " no-write=" no-write ": no dist/ at all"))))
          (let [errs (doctor-errors dir)]
            (is (= 1 (count errs)) (str rel ": " (pr-str (map :message errs))))
            (is (re-find #"malformed YAML" (str (:message (first errs)))) rel)
            (is (str/ends-with? (str (:path (first errs))) (last (str/split rel #"/"))) rel)))))))

(deftest two-spellings-of-one-language-are-an-error
  (testing "fix C: index.zh-Hant.md beside index.ZH-HANT.md is an analyse error naming both"
    (with-cli-site {"01.Guide/01.t.md" a-tree
                    "index.zh-Hant.md" "---\n---\n\nCANONICAL\n"
                    "index.ZH-HANT.md" "---\n---\n\nUPPER\n"}
      (fn [dir out]
        (let [errs (doctor-errors dir)]
          (is (= 1 (count errs)) (pr-str (map :message errs)))
          (is (re-find #"two files claim to be the zh-Hant version of index\.md: index\.zh-Hant\.md and index\.ZH-HANT\.md"
                       (str (:message (first errs))))))
        (let [e (build-fails dir out true)]
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (not (fs/exists? out)))))))
  (testing "…the same for @pages/"
    (with-cli-site {"01.Guide/01.t.md" a-tree
                    "@pages/tagsPage.ms.md" "---\ntitle: A\n---\n"
                    "@pages/tagsPage.MS.md" "---\ntitle: B\n---\n"}
      (fn [dir _]
        (let [errs (doctor-errors dir)]
          (is (= 1 (count errs)))
          (is (re-find #"@pages/tagsPage\.ms\.md and @pages/tagsPage\.MS\.md" (str (:message (first errs))))))))))

(deftest the-all-count-equals-the-rows-it-lists
  (testing "fix D: 5 articles, 2 tagged — \"All\" counts the 5 rows the overview lists"
    (let [post (fn [n tags] (-> a-post (str/replace "p00001" (str "p0000" n))
                                (str/replace "2026-01-01" (str "2026-01-0" n))
                                (str/replace "tags: [t]" (str "tags: " tags))))
          [_ uris html] (temp-tree (into {} (for [n (range 1 6)]
                                              [(str "_posts/2026-01-0" n "-p" n ".md")
                                               (post n (if (<= n 2) "[t]" "[]"))]))
                                   {:theme {:per-page 2}})
          all-count (fn [page] (some->> (re-find #"is-active\"><a [^>]*>[^<]*</a><span class=\"clogem-bar__count\">(\d+)<" page)
                                        second parse-long))]
      (doseq [root ["/tags/" "/zh-Hans/tags/" "/categories/"]
              :let [pages (cons root (filter #(str/starts-with? % (str root "page/")) (sort uris)))
                    rows  (reduce + (map #(count (re-seq #"<li class=\"clogem-row" (html %))) pages))]]
        (is (= 3 (count pages)) root)
        (is (= 5 rows) root)
        (is (= rows (all-count (html root))) root)))))
