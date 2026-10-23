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

;; ---------------------------------------------------------------------------
;; 4. The menu toggle and the drawer

(def ^:private one-lang {:langs {:locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}})
(def ^:private a-tree "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n# T\n\n## H2\n\nbody\n")

(defn- toggles [page] (re-seq #"<details class=\"clogem-navbar__toggle\"[^>]*>" page))

(deftest every-page-with-a-nav-or-languages-has-one-toggle
  (let [ps (pages)]
    (doseq [[rel page] ps
            :let [nb (navbar-of page)]
            :when nb]
      (is (= ["<details class=\"clogem-navbar__toggle\">"] (toggles nb)) (str rel ": exactly one toggle, not hidden"))
      (is (re-find #"^<header class=\"clogem-navbar\"><details class=\"clogem-navbar__toggle\"><summary aria-controls=\"clogem-drawer\">" nb)
          (str rel ": first in the header, the summary controls the drawer"))
      (is (re-find #"#menu\"[^>]*>(?:</use>)?</svg><svg[^>]*class=\"clogem-icon is-close\"[^>]*><use href=\"[^\"]*#x\"" nb)
          (str rel ": the menu and x icons"))
      (is (= 1 (count (re-seq #"id=\"clogem-drawer\"" page))) (str rel ": one drawer"))
      (is (re-find #"<div class=\"clogem-navbar__drawer\" id=\"clogem-drawer\"><nav class=\"clogem-navbar__nav\">.*</ul></nav><nav aria-label=\"[^\"]+\" class=\"clogem-langs\">.*</ul></nav></div><div class=\"clogem-search\">" nb)
          (str rel ": the drawer holds the nav and the languages, then search follows")))
    (testing ":nav/menu in the page's language"
      (doseq [[p want] [[["index.html"] "Menu"] [["zh-Hans" "index.html"] "菜单"] [["zh-Hant" "index.html"] "選單"]
                        [["ms" "index.html"] "Menu"] [["ta" "index.html"] "மெனு"]]]
        (is (str/includes? (navbar-of (apply html p)) (str "<span class=\"clogem-visually-hidden\">" want "</span></summary>")) (str p))))
    (testing "the search box stays the header's direct child, its trigger first (search_test pins it)"
      (is (re-find #"<header class=\"clogem-navbar\">.*<div class=\"clogem-search\"><pagefind-modal-trigger placeholder=\"[^\"]+\"></pagefind-modal-trigger>" (html "index.html"))))
    (testing "no <details> or class on one says open"
      (is (not-any? #(re-find #"<details class=\"clogem-navbar__toggle\"[^>]*open" (val %)) ps)))))

(deftest a-one-language-site-without-a-nav
  (let [ps (build-temp (merge one-lang {:nav []})
                       {"index.md" "# Home\n" "01.Guide/01.t.md" a-tree})]
    (is (contains? ps "pages/t00001/index.html"))
    (testing "a sidebar page: the drawer, with the toggle hidden until js/nav.js moves the sidebar in"
      (let [nb (navbar-of (ps "pages/t00001/index.html"))]
        (is (= ["<details class=\"clogem-navbar__toggle\" hidden=\"hidden\">"] (toggles nb)))
        (is (str/includes? nb "<div class=\"clogem-navbar__drawer\" id=\"clogem-drawer\"><nav class=\"clogem-navbar__nav\"><ul></ul></nav></div>"))))
    (testing "every other page: neither, and the nav stays the header's child"
      (doseq [[rel page] ps
              :when (and (navbar-of page) (not= rel "pages/t00001/index.html"))]
        (is (not (str/includes? page "clogem-navbar__toggle")) rel)
        (is (not (str/includes? page "clogem-drawer")) rel)
        (is (str/includes? (navbar-of page) "</a><nav class=\"clogem-navbar__nav\"><ul></ul></nav><div class=\"clogem-mode\"") rel)))
    (testing "no clogem-sidebar, clogem-toc or clogem-langs where the page has none"
      (doseq [[rel page] ps :when (not= rel "pages/t00001/index.html")]
        (is (not (str/includes? page "clogem-sidebar")) rel))
      (is (not-any? #(str/includes? (val %) "clogem-langs") ps)))))

(deftest a-one-language-site-with-a-nav-gets-the-toggle
  (let [ps (build-temp (merge one-lang {:nav [{:text "Home" :link "/"}]}) {"index.md" "# Home\n"})]
    (is (= ["<details class=\"clogem-navbar__toggle\">"] (toggles (navbar-of (ps "index.html")))))
    (is (not (str/includes? (ps "index.html") "clogem-sidebar")))))

(deftest the-drawer-css
  (let [css (slurp theme-css)
        rules (cp/parse css)
        in (fn [q] (filter #(= [q] (:media %)) rules))
        js-q "(scripting: enabled) and (max-width: 50rem)"
        decls (fn [q sel] (into {} (for [r (in q) :when (some #{sel} (:selectors r)) [p v] (:decls r)] [p v])))]
    (testing "the drawer is hidden unless the toggle is open, so a drawer without a toggle never shows"
      (is (= "none" ((decls js-q ".clogem-navbar__drawer") "display")))
      (is (= "block" ((decls js-q ".clogem-navbar__toggle[open] ~ .clogem-navbar__drawer") "display")))
      (is (not (re-find #":not\(\[open\]\) ~" css))))
    (testing "absolute below the navbar, never fixed (the backdrop-filter trap)"
      (let [d (decls js-q ".clogem-navbar__toggle[open] ~ .clogem-navbar__drawer")]
        (is (= "absolute" (d "position")))
        (is (= "100%" (d "top")))
        (is (= "auto" (d "overflow-y")))
        (is (str/includes? css "max-height: calc(100vh - var(--navbarHeight)); max-height: calc(100dvh - var(--navbarHeight));")))
      (is (not (re-find #"position:\s*fixed" css))))
    (testing "a [hidden] toggle stays hidden whatever display it is given"
      (is (= "block" ((decls js-q ".clogem-navbar__toggle:not([hidden])") "display"))))
    (testing "the no-JS rules sit outside any scripting query: static, wrapping, no navbar offset"
      (let [d (decls "(max-width: 50rem)" ".clogem-navbar")]
        (is (= "static" (d "position")))
        (is (= "wrap" (d "flex-wrap"))))
      (is (= "0.75rem" ((decls "(max-width: 50rem)" "html") "scroll-padding-top")))
      (is (= "static" ((decls "(max-width: 50rem)" ".clogem-navbar__menu") "position")))
      (is (= "sticky" ((decls js-q ".clogem-navbar") "position")))
      (is (= "calc(var(--navbarHeight) + 0.75rem)" ((decls js-q "html") "scroll-padding-top"))))
    (testing "the drawer's own background is the card's, and the palette passes with it"
      (is (= "var(--mainBg)" ((decls js-q ".clogem-navbar__toggle[open] ~ .clogem-navbar__drawer") "background")))
      (is (= [] (cp/failures css))))))

;; ---------------------------------------------------------------------------
;; 6. js/nav.js

(deftest nav-js-is-linked-once-deferred-after-mode-js
  (doseq [[rel page] (pages)
          :when (str/includes? page "<header class=\"clogem-navbar\"")]
    (let [tags (re-seq #"<script[^>]*src=\"[^\"]*/js/nav\.js\?v=[^\"]+\"[^>]*></script>" page)
          i (str/index-of page "/clogem/js/nav.js")
          m (str/index-of page "/clogem/js/mode.js")]
      (is (= 1 (count tags)) rel)
      (is (str/includes? (first tags) " defer") rel)
      (is (and i m (< m i)) (str rel ": after mode.js"))
      (is (< i (str/index-of page "</head>")) rel)))
  (testing "it ships, and moves the aside itself rather than a copy"
    (is (fs/exists? (fs/path *out* "clogem" "js" "nav.js")))
    (let [js (slurp "src/clogem/theme/resources/js/nav.js")]
      (is (not (str/includes? js "cloneNode")))
      (is (not (str/includes? js "innerHTML")))
      (is (not (re-find #"\.style\b|setAttribute\(\"style" js)) "styles through the CSSOM, never a style attribute")
      (is (str/includes? js "window.clogem = window.clogem || {}") "extends window.clogem, never replaces it")
      (is (str/includes? js "e.defaultPrevented") "mode.js's own Escape wins"))))

;; ---------------------------------------------------------------------------
;; 8. One warning check for the page chrome's :theme keys; smoothScroll

(defn- load-site
  "Load `edn` as a site.edn: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-b2b-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(def ^:private chrome-keys
  "The page chrome's keys still :planned in `config/known-keys`: the tests
  below see them as items 9–13 make them, :ok. Each item takes its key
  out of this set when it flips it."
  #{:sidebar-collapsed :right-menu-bar :page-button :content-bg-style :back-to-top})

(defn- as-ok [known]
  (reduce #(assoc-in %1 [[:theme] %2] :ok) known chrome-keys))

(deftest the-chrome-keys-warn-and-repair
  (with-redefs [config/known-keys (as-ok config/known-keys)]
    (doseq [[k default bad good] [[:sidebar-collapsed false ["yes" 1 :on] [true false]]
                                  [:right-menu-bar true ["yes" 1 :on] [true false]]
                                  [:page-button true ["yes" 1 :on] [true false]]
                                  [:back-to-top true ["yes" 1 :on] [true false]]
                                  [:content-bg-style nil [0 7 1.5 "1" :grid true] [1 2 3 4 5 6]]]]
      (testing (str k)
        (doseq [v bad]
          (let [[cfg ds] (load-site {:theme {k v}})
                ws (diag/warnings ds)]
            (is (empty? (diag/errors ds)) (pr-str k v))
            (is (= 1 (count ws)) (pr-str k v (map :message ws)))
            (let [m (:message (first ws))]
              (is (str/starts-with? m (str ":theme " k " is " (pr-str v) ", but it must be ")) m)
              (is (str/ends-with? m (str "; using " (pr-str default) ".")) m))
            (is (= default (get-in cfg [:theme k])) (pr-str k v))))
        (testing "the message names what the value must be"
          (let [[_ ds] (load-site {:theme {k (first bad)}})]
            (is (str/includes? (:message (first (diag/warnings ds)))
                               (if (= k :content-bg-style) "nil or an integer from 1 to 6" "true or false")))))
        (doseq [v (conj good nil)]
          (let [[cfg ds] (load-site {:theme {k v}})]
            (is (= [] (map :message (diag/warnings ds))) (pr-str k v))
            (is (empty? (diag/errors ds)))
            (is (= (if (nil? v) default v) (get-in cfg [:theme k])) (pr-str k v))))))
    (testing "the defaults, with nothing set"
      (let [[cfg ds] (load-site {})]
        (is (= [false true true nil true]
               (map #(get-in cfg [:theme %]) [:sidebar-collapsed :right-menu-bar :page-button :content-bg-style :back-to-top])))
        (is (empty? (remove #(str/includes? (str (:message %)) "no site config") (diag/warnings ds))))))))

(deftest smooth-scroll-is-known-and-ignored
  (doseq [[edn want] [[{:theme {:smoothScroll true}}
                       ":theme :smoothScroll is not needed: clogem-press always scrolls smoothly unless the reader asks for reduced motion; it is ignored."]
                      [{:smoothScroll false}
                       ":smoothScroll is not needed: clogem-press always scrolls smoothly unless the reader asks for reduced motion; it is ignored."]]]
    (let [[cfg ds] (load-site edn)
          ws (diag/warnings ds)]
      (is (empty? (diag/errors ds)))
      (is (= [want] (map :message ws)))
      (is (= "Remove it. In-page jumps are smooth unless the reader's system asks for reduced motion." (:hint (first ws))))
      (is (not (contains? (:theme cfg) :smooth-scroll)) "there is no :smooth-scroll key")))
  (is (nil? (get-in config/known-keys [[:theme] :smooth-scroll]))))
