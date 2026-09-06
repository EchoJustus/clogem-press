;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.markdown-test
  "Markdown pipeline tests, including the **characterization tests** for
  CJK/Tamil heading slugs.

  DESIGN.md's risk table lists \"CJK + Tamil edge cases (heading slugs, search,
  line wrapping)\" as Medium and says explicitly: *heading slugs come from
  nextjournal/markdown (GitHub-style; CJK and Tamil slug behaviour still
  untested — covered by the five-language fixture corpus from Phase 1)*.
  Appendix A repeats it in the not-verified list.

  These tests are that coverage. They are *characterization* tests: they record
  what the library actually does under bb 1.13.219 rather than what we hoped it
  would do, so that a future babashka bump that changes the bundled
  nextjournal/markdown version breaks a test with a readable diff instead of
  silently changing every anchor URL on the site.

  ## What was found

  Good news, and it retires the risk: **CJK and Tamil are preserved verbatim.**
  No transliteration, no stripping, no mangling. `你好世界` stays `你好世界`;
  `வணக்கம் உலகம்` becomes `வணக்கம்-உலகம்`. Tamil combining marks survive intact.

  Bad news, and it is a real divergence from the design's wording: the algorithm
  is **not** GitHub-style. GitHub strips punctuation; nextjournal does not. So
  `Hello, World!` → `hello,-world!` and `100% Done` → `100%-done`, where GitHub
  would give `hello-world` and `100-done`. The ids are legal HTML but need
  percent-encoding in an href, which `clogem.util/url-encode-fragment` does.

  One genuine defect: a **tab** inside a heading survives into the id, and
  whitespace is not permitted in an HTML `id`. `clogem.markdown/anchor-id`
  repairs exactly that, and is used by both the heading renderer and the TOC so
  the two cannot disagree."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nextjournal.markdown :as md]
            [clogem.diag :as diag]
            [clogem.markdown :as markdown]
            [clogem.util :as u]))

(defn raw-slug
  "The id nextjournal/markdown computes, before our repair."
  [heading-text]
  (->> (tree-seq :content :content (md/parse (str "# " heading-text)))
       (filter #(= :heading (:type %)))
       first :attrs :id))

(defn slug [heading-text] (markdown/anchor-id (raw-slug heading-text)))

;; ---------------------------------------------------------------------------
;; Characterization: the answer to the design's open question

(deftest cjk-slugs-are-preserved-verbatim
  (testing "Simplified and Traditional Chinese headings keep their characters"
    (is (= "你好世界" (slug "你好世界")))
    (is (= "繁體中文標題" (slug "繁體中文標題")))
    (is (= "你好-world-123" (slug "你好 World 123"))
        "mixed scripts: ASCII lower-cased, spaces hyphenated, CJK untouched")
    (is (= "中文-with-dash-空格" (slug "中文-with-dash 空格")))))

(deftest tamil-slugs-are-preserved-verbatim
  (testing "Tamil headings keep their characters and combining marks"
    (is (= "வணக்கம்" (slug "வணக்கம்")))
    (is (= "வணக்கம்-உலகம்" (slug "வணக்கம் உலகம்")))
    (is (= "தமிழ்-மட்டும்" (slug "தமிழ் மட்டும்")))
    (testing "grapheme clusters are not split — the risk that mattered"
      (is (= (count "வணக்கம்") (count (slug "வணக்கம்")))))))

(deftest malay-slugs-are-ordinary-latin
  (is (= "bahasa-melayu" (slug "Bahasa Melayu")))
  (is (= "panduan-pusat-penjaja" (slug "Panduan pusat penjaja"))))

(deftest slugs-are-NOT-github-style
  (testing "punctuation is preserved, unlike GitHub's algorithm"
    ;; Recorded, not asserted-as-desirable. DESIGN.md called these
    ;; \"GitHub-style\"; they are not, and the docstring above says so.
    (is (= "hello,-world!" (slug "Hello, World!"))  "GitHub would give hello-world")
    (is (= "100%-done"     (slug "100% Done"))      "GitHub would give 100-done")
    (is (= "c++-/-c#"      (slug "C++ / C#")))
    (is (= "a/b"           (slug "a/b")))
    (is (= "你好，世界"     (slug "你好，世界"))     "a fullwidth comma also survives")))

(deftest slug-mechanics
  (testing "lower-casing is Unicode-aware; underscores become hyphens"
    (is (= "ünïcode" (slug "Ünïcode")))
    (is (= "åäö" (slug "ÅÄÖ")))
    (is (= "a-b-c.d" (slug "a_b-c.d")) "underscore → hyphen, dot preserved"))
  (testing "inline markup is reduced to its text"
    (is (= "bold" (slug "**bold**")))
    (is (= "code" (slug "`code`")))
    (is (= "link" (slug "[link](x)"))))
  (testing "space runs are NOT collapsed"
    (is (= "hello--world" (raw-slug "Hello  World")))))

(deftest anchor-id-repairs-whitespace
  (testing "a tab in a heading yields an id with whitespace — invalid HTML"
    (is (str/includes? (raw-slug "tab\there") "\t")
        "this is the library's behaviour, and it is the defect we repair")
    (is (= "tab-here" (slug "tab\there"))
        "anchor-id is the single repair, applied by heading AND toc rendering")))

(deftest duplicate-headings-are-numbered
  (let [ids (->> (tree-seq :content :content (md/parse "# Dup\n\n# Dup\n\n# Dup\n"))
                 (filter #(= :heading (:type %)))
                 (map #(get-in % [:attrs :id])))]
    (is (= ["dup" "dup-2" "dup-3"] ids)
        "the library de-duplicates within a document, so we need not")))

(deftest fragments-are-percent-encoded-for-hrefs
  (testing "non-ASCII ids are legal in an id attribute but must be encoded in a URL"
    (is (= "%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C" (u/url-encode-fragment "你好世界")))
    (is (= "hello-world" (u/url-encode-fragment "hello-world"))
        "ASCII anchors stay readable"))
  (testing "the rendered heading pairs an unencoded id with an encoded href"
    (let [html (str (h/html (markdown/render "## 你好世界\n" {})))]
      (is (str/includes? html "id=\"你好世界\""))
      (is (str/includes? html "href=\"#%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C\"")))))

;; ---------------------------------------------------------------------------
;; Renderer overrides

(deftest html-passthrough-is-raw
  (testing "§5.2 step 4: without this override the default renderer emits a red
            'Unknown type' box instead of the author's HTML"
    (let [html (str (h/html (markdown/render "<div class=\"x\">raw</div>\n" {})))]
      (is (str/includes? html "<div class=\"x\">raw</div>"))
      (is (not (str/includes? html "Unknown type"))))
    (let [html (str (h/html (markdown/render "text <b>bold</b> more\n" {})))]
      (is (str/includes? html "<b>bold</b>"))
      (is (not (str/includes? html "Unknown type"))))))

(deftest code-blocks-carry-a-language-class
  (let [html (str (h/html (markdown/render "```clojure\n(+ 1 2)\n```\n" {})))]
    (is (str/includes? html "language-clojure"))
    (is (str/includes? html "(+ 1 2)"))))

(deftest gfm-tables-render
  (let [html (str (h/html (markdown/render "| a | b |\n|---|---|\n| 1 | 2 |\n" {})))]
    (is (str/includes? html "<table>"))
    (is (str/includes? html "<th>a</th>"))))

;; ---------------------------------------------------------------------------
;; Link rewriting

(def link-ctx
  (let [group {:permalink "/pages/aaa111/" :primary :en
               :variants {:en {:title "A"} :zh-Hans {:title "甲"}}}]
    {:cfg {}
     :articles {"/pages/aaa111/" group}
     :by-rel-path {"guide/02.other.md" group "02.other.md" group "./02.other.md" group}
     :lang :en
     :from-path "guide/01.here.md"
     :url-for (fn [g l] (if (= l :en)
                          (:permalink g)
                          (str "/" (name l) (:permalink g))))}))

(deftest rewrites-relative-md-links
  (is (= "/pages/aaa111/" (markdown/rewrite-href link-ctx "02.other.md")))
  (is (= "/pages/aaa111/#frag" (markdown/rewrite-href link-ctx "02.other.md#frag"))))

(deftest rewrites-permalinks-to-the-readers-language
  (is (= "/pages/aaa111/" (markdown/rewrite-href link-ctx "/pages/aaa111/")))
  (is (= "/zh-Hans/pages/aaa111/"
         (markdown/rewrite-href (assoc link-ctx :lang :zh-Hans) "/pages/aaa111/"))
      "§6.3: a link resolves to the same-language variant when one exists"))

(deftest leaves-external-links-alone
  (doseq [href ["https://example.com/" "//cdn.example.com/x" "mailto:a@b.c" "tel:+65"]]
    (is (= href (markdown/rewrite-href link-ctx href)))))

(deftest dead-links-warn-but-still-render
  (let [[got ds] (diag/collecting (markdown/rewrite-href link-ctx "99.missing.md"))]
    (is (= "99.missing.md" got) "the original href survives so the page still renders")
    (is (= 1 (count (diag/warnings ds))))
    (is (re-find #"dead link" (:message (first (diag/warnings ds)))))))

(deftest unknown-permalinks-warn
  (let [[_ ds] (diag/collecting (markdown/rewrite-href link-ctx "/pages/nope00/"))]
    (is (re-find #"unknown permalink" (:message (first (diag/warnings ds)))))))

(deftest toc-ids-match-heading-ids
  (testing "the TOC and the anchors are generated by the same repair function"
    (let [src "# T\n\n## 你好世界\n\n## tab\there\n"
          ast (markdown/parse src)
          toc-ids (set (map :id (markdown/toc-entries ast)))
          html (str (h/html (markdown/->hiccup ast {})))]
      (doseq [id toc-ids]
        (is (str/includes? html (str "id=\"" id "\""))
            (str "TOC id " (pr-str id) " must exist as a heading id"))))))

;; ---------------------------------------------------------------------------
;; The TOC bar (D-P2-9)

(deftest toc-entries-carry-encoded-hrefs
  (let [ast (markdown/parse "# T\n\n## 你好世界\n\n### Hello, World!\n")
        es  (markdown/toc-entries ast)]
    (is (= ["#t" "#%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C" "#hello,-world!"] (map :href es))
        "the fragment is percent-encoded; the id stays raw (the scroll-spy decodes)")
    (is (= ["t" "你好世界" "hello,-world!"] (map :id es)))))

(deftest toc-depth-and-the-leading-h1
  (let [ast (markdown/parse "# Title\n\n## A\n\n### A.1\n\n#### A.1.a\n\n## B\n\n# Another h1\n")]
    (is (= ["A" "A.1" "B"] (map :text (markdown/toc ast 2))) "sidebarDepth 2 (the default): h2–h3, no h1")
    (is (= ["A" "B"] (map :text (markdown/toc ast 1))) "sidebarDepth 1: h2 only")
    (is (= ["A" "A.1" "A.1.a" "B"] (map :text (markdown/toc ast 3))) "sidebarDepth 3: h2–h4")
    (is (= ["A" "A.1" "B"] (map :text (markdown/toc ast nil))) "nil → the default")
    (is (= [] (markdown/toc (markdown/parse "# only a title\n\ntext\n") 2)) "nothing to list")))
