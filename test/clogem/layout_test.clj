;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.layout-test
  "Phase 4 Task B2b: the navbar, the mobile drawer and the page chrome —
  config → markup, on the demo and on temp sites, and theme.css's text.
  What needs a browser is in test/browser/layout.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.css-palette :as cp]
            [clogem.diag :as diag]
            [clogem.render :as render]))

(def demo "examples/demo-site")
(def theme-css "src/clogem/theme/resources/css/theme.css")

(def ^:dynamic *out* nil)

(defn- build-fixture
  [f]
  (let [out (fs/create-temp-dir {:prefix "clogem-b2b"})
        cfg (first (diag/collecting
                    (config/load-config demo nil {:build {:out (str out)}
                                                  :content {:write-front-matter false}})))
        [model _] (diag/collecting (cli/analyse cfg))]
    (diag/collecting (render/build! cfg model))
    (binding [*out* out]
      (try (f) (finally (fs/delete-tree out))))))

(use-fixtures :once build-fixture)

(defn- pages
  "{rel-path → html} of every HTML page of the built demo."
  []
  (into (sorted-map)
        (for [p (fs/glob *out* "**.html")]
          [(str (fs/relativize *out* p)) (slurp (fs/file p))])))

(defn- html [& parts] (slurp (fs/file (apply fs/path *out* parts))))

(defn- navbar-of
  "The `<header class=\"clogem-navbar\">` element of `page`."
  [page]
  (re-find #"(?s)<header class=\"clogem-navbar\">.*?</header>" page))

(defn- build-temp
  "Build a temp site from `edn` and `files` {rel content}; {rel-url → html}."
  [edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-b2b"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str (merge {:site {:title "S"} :search {:provider :none}} edn)))
      (doseq [[rel content] files]
        (fs/create-dirs (fs/parent (fs/path dir "content" rel)))
        (spit (fs/file dir "content" rel) content))
      (binding [*out* (java.io.StringWriter.)]
        (diag/collecting (cli/build {:site-dir (str dir) :no-write true})))
      (into (sorted-map)
            (for [p (fs/glob (fs/path dir "dist") "**.html")]
              [(str (fs/relativize (fs/path dir "dist") p)) (slurp (fs/file p))]))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; The contrast parser's media queries (items 1, 2, 4)

(deftest the-palette-reader-knows-not-and-scripting
  (let [t #(#'cp/media-true? %1 %2)]
    (testing "a leading `not` negates the whole query"
      (is (true? (t {:width 1280} "not all and (max-width: 50rem)")))
      (is (false? (t {:width 800} "not all and (max-width: 50rem)")) "800 px is 50rem: the query is false there")
      (is (true? (t {:width 801} "not all and (max-width: 50rem)")))
      (is (false? (t {:width 1279} "(min-width: 80rem)")))
      (is (true? (t {:width 1279} "not all and (min-width: 80rem)")))
      (is (true? (t {:width 360} "print, not all and (min-width: 80rem)")) "per query of a list"))
    (testing "(scripting: …) follows :scripting?, default true; initial-only is false"
      (is (true? (t {} "(scripting: enabled)")))
      (is (false? (t {:scripting? false} "(scripting: enabled)")))
      (is (true? (t {:scripting? false} "(scripting: none)")))
      (is (false? (t {} "(scripting: initial-only)")))
      (is (true? (t {:width 360} "(scripting: enabled) and (max-width: 50rem)")))
      (is (false? (t {:width 1280} "(scripting: enabled) and (max-width: 50rem)")))
      (is (false? (t {:width 360 :scripting? false} "(scripting: enabled) and (max-width: 50rem)"))))
    (testing "what it does not know still throws"
      (is (thrown? clojure.lang.ExceptionInfo (t {} "(width > 50rem)")))
      (is (thrown? clojure.lang.ExceptionInfo (t {} "(scripting: maybe)")))
      (is (thrown? clojure.lang.ExceptionInfo (t {} "(hover: hover)")))))
  (testing "and the cascade uses it"
    (let [css ":root { --a: #111111; }
@media not all and (max-width: 50rem) { :root { --a: #222222; } }
@media (scripting: enabled) and (max-width: 50rem) { :root { --b: #333333; } }"]
      (is (= "#222222" ((cp/computed (cp/parse css) {:classes #{}} {:width 1280}) "--a")))
      (is (= "#111111" ((cp/computed (cp/parse css) {:classes #{}} {:width 800}) "--a")))
      (is (= "#333333" ((cp/computed (cp/parse css) {:classes #{}} {:width 360}) "--b")))
      (is (nil? ((cp/computed (cp/parse css) {:classes #{}} {:width 360 :scripting? false}) "--b")))))
  (testing "the real theme.css still parses and passes"
    (is (= [] (cp/failures (slurp theme-css))))))

;; ---------------------------------------------------------------------------
;; 1. --navbarHeight, anchors below the navbar, smooth scrolling

(deftest anchors-land-below-the-navbar
  (let [css (slurp theme-css)
        rules (cp/parse css)]
    (is (= "3.6rem" ((cp/computed rules {:classes #{}} {}) "--navbarHeight")) "vdoing's value")
    (testing "the root's scroll padding covers every target, headings or not"
      (is (= "calc(var(--navbarHeight) + 0.75rem)" ((cp/rule rules "html") "scroll-padding-top"))))
    (testing "no scroll-margin-top left to add up with it"
      (is (not (str/includes? css "scroll-margin-top"))))
    (testing "the navbar is --navbarHeight tall, the TOC sticks below it"
      (is (= "var(--navbarHeight)" ((cp/rule rules ".clogem-navbar") "min-height")))
      (is (= "0 1.25rem" ((cp/rule rules ".clogem-navbar") "padding")))
      (is (str/includes? css ".clogem-toc { position: sticky; top: calc(var(--navbarHeight) + 1rem);")))
    (testing "toc.js measures the navbar instead of assuming 80 px"
      (let [js (slurp "src/clogem/theme/resources/js/toc.js")]
        (is (not (str/includes? js "offset = 80")))
        (is (str/includes? js "navbar.getBoundingClientRect().bottom"))))))

;; ---------------------------------------------------------------------------
;; 2. The language dropdown and the navbar's order

(deftest the-language-switcher-is-a-dropdown-before-its-list
  (let [ps (pages)]
    (doseq [[rel page] ps
            :let [nb (navbar-of page)]
            :when nb]
      (let [langs (re-find #"(?s)<nav aria-label=\"[^\"]+\" class=\"clogem-langs\">.*?</ul></nav>" nb)]
        (is langs rel)
        (testing (str rel ": the toggle, then the same list as its next sibling")
          (is (re-find #"</span><details class=\"clogem-langs__toggle\"><summary><svg[^>]*><use href=\"[^\"]*#languages\"" (str langs)))
          (is (re-find #"#chevron-down\"[^>]*>(?:</use>)?</svg></summary></details><ul>" (str langs))))
        (testing (str rel ": brand, nav, languages, search, mode — in that order")
          (let [at #(or (str/index-of nb %) -1)]
            (is (< -1 (at "clogem-navbar__brand") (at "clogem-navbar__nav") (at "clogem-langs")
                   (at "clogem-search") (at "clogem-mode")))))))
    (testing "the summary is the page language's :nav/language"
      (is (re-find #"<details class=\"clogem-langs__toggle\"><summary>.*?<span>Language</span>" (navbar-of (html "index.html"))))
      (is (re-find #"<details class=\"clogem-langs__toggle\"><summary>.*?<span>語言</span>" (navbar-of (html "zh-Hant" "index.html"))))
      (is (re-find #"<details class=\"clogem-langs__toggle\"><summary>.*?<span>Bahasa</span>" (navbar-of (html "ms" "index.html")))))
    (testing "the pins: aria-current, lang.js's selector, the wrapping rules"
      (is (str/includes? (html "index.html") "<span aria-current=\"true\" class=\"is-current\" lang=\"en\">English</span>"))
      (is (str/includes? (slurp "src/clogem/theme/resources/js/lang.js") ".clogem-langs a[data-clogem-lang]"))
      (let [css (slurp theme-css)]
        (is (re-find #"\.clogem-langs ul \{[^}]*flex-wrap: wrap" css))
        (is (re-find #"\.clogem-langs \{[^}]*min-width: 0" css))))
    (testing "the new <details> render closed"
      (is (not-any? #(re-find #"<details class=\"clogem-langs__toggle\"[^>]*open" (val %)) ps)))))

(deftest the-desktop-navbar-css
  (let [css (slurp theme-css)
        rules (cp/parse css)
        desk (filter #(= ["not all and (max-width: 50rem)"] (:media %)) rules)]
    (testing "above 50rem the closed toggle hides its list, and the label (the summary says it)"
      (is (some #(some #{".clogem-langs__toggle:not([open]) + ul"} (:selectors %)) desk))
      (is (some #(some #{".clogem-langs__label"} (:selectors %)) desk)))
    (testing "the icon-only trigger is scoped to the trigger: the dialog has a .pf-trigger-shortcut too"
      (let [r (filter #(= ["(max-width: 64rem)"] (:media %)) rules)
            sels (set (mapcat :selectors r))]
        (is (contains? sels "pagefind-modal-trigger :is(.pf-trigger-text, .pf-trigger-shortcut)"))
        (is (not-any? #(re-find #"^\.pf-trigger-shortcut" %) (mapcat :selectors rules)))))
    (testing "the gap"
      (is (= "1rem" ((cp/rule rules ".clogem-navbar") "gap"))))))
