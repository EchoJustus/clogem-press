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
    (testing "P4-B2b.1 item 1: a scroll margin on every element outside the navbar, not the root's padding"
      (is (not (re-find #"scroll-padding[-a-z]*\s*:" css)) "the root's padding covered the navbar's own controls")
      (is (= "var(--jumpOffset)" ((cp/rule rules ":where(body *)") "scroll-margin-top"))
          "every element: an id, an <a name>, a focus, scrollIntoView()")
      (is (= "0" ((cp/rule rules ".clogem-navbar *") "scroll-margin-top")))
      (let [off #(get (cp/computed rules {:classes #{}} %) "--jumpOffset")
            sticky "calc(3.6rem + 0.75rem)"]
        (is (= sticky (off {:width 1280})) "the navbar's measured height (js/nav.js), --navbarHeight without it")
        (is (= sticky (off {:width 360})) "the sticky one-row navbar")
        (is (= "0.75rem" (off {:width 360 :scripting? false})) "the static no-JS navbar needs no offset")))
    (testing "the navbar is --navbarHeight tall, the TOC sticks below it (below a wrapped one too)"
      (is (= "var(--navbarHeight)" ((cp/rule rules ".clogem-navbar") "min-height")))
      (is (= "0 1.25rem" ((cp/rule rules ".clogem-navbar") "padding")))
      (is (= "calc(var(--navbarMeasured, var(--navbarHeight)) + 1rem)" ((cp/rule rules ".clogem-toc") "top")))
      (is (= "calc(100vh - var(--navbarMeasured, var(--navbarHeight)) - 6.2rem)" ((cp/rule rules ".clogem-toc") "max-height"))))
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
      ;; P4-E1a (deliberate): the demo's blogger card, hidden, may come first
      (is (re-find #"<div class=\"clogem-navbar__drawer\" id=\"clogem-drawer\">(?:<div class=\"clogem-blogger clogem-blogger--drawer\" hidden=\"hidden\">.*?</div>)?<nav class=\"clogem-navbar__nav\">.*</ul></nav><nav aria-label=\"[^\"]+\" class=\"clogem-langs\">.*</ul></nav></div><div class=\"clogem-search\">" nb)
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
      ;; items 11 and 13 are fixed, outside the navbar; nothing in it is
      (let [fixed (mapcat :selectors (filter #(some (fn [[p v]] (and (= "position" p) (= "fixed" v))) (:decls %)) rules))]
        (is (every? #(re-find #"^\.clogem-(page-button|back-to-top)$" %) fixed) (pr-str fixed))
        (is (not-any? #(re-find #"navbar|drawer" %) fixed))))
    (testing "a [hidden] toggle stays hidden whatever display it is given"
      (is (= "block" ((decls js-q ".clogem-navbar__toggle:not([hidden])") "display"))))
    (testing "the no-JS rules sit outside any scripting query: static, wrapping, no navbar offset"
      (let [d (decls "(max-width: 50rem)" ".clogem-navbar")]
        (is (= "static" (d "position")))
        (is (= "wrap" (d "flex-wrap"))))
      (is (= "0.75rem" ((decls "(max-width: 50rem)" ":root") "--jumpOffset")))
      (is (= "static" ((decls "(max-width: 50rem)" ".clogem-navbar__menu") "position")))
      (is (= "sticky" ((decls js-q ".clogem-navbar") "position")))
      (is (= "calc(var(--navbarMeasured, var(--navbarHeight)) + 0.75rem)" ((decls js-q ":root") "--jumpOffset"))))
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

(deftest the-chrome-keys-warn-and-repair
  (testing "items 9–13 made all five :ok in known-keys"
    (is (every? #(= :ok (get-in config/known-keys [[:theme] %]))
                [:sidebar-collapsed :right-menu-bar :page-button :content-bg-style :back-to-top])))
  (do
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

;; ---------------------------------------------------------------------------
;; 9. :theme :right-menu-bar

(def ^:private a-toc-article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n# T\n\n## Alpha\n\nbody\n\n### Beta\n\nmore\n")

(def ^:private a-post
  "---\ntitle: P\ndate: \"2026-02-02 00:00:00\"\npermalink: /pages/p00001/\nsidebar: auto\n---\n\n# P\n\n## Gamma\n\nbody\n")

(deftest right-menu-bar-false-drops-the-toc
  (let [files {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article "_posts/p.md" a-post}
        on  (build-temp one-lang files)
        off (build-temp (assoc one-lang :theme {:right-menu-bar false}) files)]
    (testing "the default: the tree article and the post have a TOC"
      (doseq [rel ["pages/t00001/index.html" "pages/p00001/index.html"]]
        (is (str/includes? (on rel) "<aside class=\"clogem-toc\">") rel)
        (is (str/includes? (on rel) "clogem-shell--toc") rel)
        (is (str/includes? (on rel) "/js/toc.js") rel)))
    (testing "false: no page has the aside, the class, the script or any clogem-toc"
      (doseq [[rel page] off]
        (is (not (str/includes? page "clogem-toc")) rel)
        (is (not (str/includes? page "clogem-shell--toc")) rel)
        (is (not (str/includes? page "js/toc.js")) rel)))
    (testing "headings keep their ids and anchors"
      (let [page (off "pages/t00001/index.html")]
        (is (re-find #"<h2 id=\"alpha\"" page))
        (is (re-find #"<h3 id=\"beta\"" page))
        (is (str/includes? page "class=\"header-anchor\""))
        (is (str/includes? (off "pages/p00001/index.html") "<h2 id=\"gamma\""))))
    (testing "the home's right bar is not the TOC: it stays"
      (is (str/includes? (off "index.html") "clogem-home-right"))
      (is (str/includes? (off "index.html") "clogem-shell--home")))
    (testing "the sidebar is untouched"
      (is (str/includes? (off "pages/t00001/index.html") "<aside class=\"clogem-sidebar\"")))
    (testing "P4-B2b.1 item 4: a sidebar page whose TOC false dropped gets the wide shell; nothing else does"
      (is (str/includes? (off "pages/t00001/index.html") "<div class=\"clogem-shell clogem-shell--wide\""))
      (doseq [[rel page] on]
        (is (not (str/includes? page "clogem-shell--wide")) rel))
      (doseq [[rel page] off
              :when (str/includes? page "clogem-shell--wide")]
        (is (str/includes? page "<aside class=\"clogem-sidebar\"") rel)))))

(deftest right-menu-bar-is-a-known-key
  (is (= :ok (get-in config/known-keys [[:theme] :right-menu-bar])))
  (let [[_ ds] (load-site {:theme {:right-menu-bar false}})]
    (is (empty? (diag/warnings ds))))
  (let [[cfg ds] (load-site {:theme {:rightMenuBar false}})]
    (is (= [(str ":theme :rightMenuBar is vdoing's spelling; clogem-press reads :theme :right-menu-bar. It is ignored.")]
           (map :message (diag/warnings ds))))
    (is (true? (get-in cfg [:theme :right-menu-bar])))))

;; ---------------------------------------------------------------------------
;; 10. Sidebar collapse: :theme :sidebar-collapsed

(def ^:private sidebar-toggle-re
  #"<button aria-controls=\"clogem-sidebar\" aria-expanded=\"true\" aria-label=\"([^\"]+)\" class=\"clogem-sidebar-toggle\" hidden=\"hidden\" title=\"\1\" type=\"button\"><svg[^>]*class=\"clogem-icon is-expanded\"[^>]*><use href=\"[^\"]*#chevron-left\"(?:/>|></use>)</svg><svg[^>]*class=\"clogem-icon is-collapsed\"[^>]*><use href=\"[^\"]*#chevron-right\"(?:/>|></use>)</svg></button>")

(deftest the-sidebar-toggle-is-on-sidebar-pages-only
  (let [ps (pages)
        sidebar-pages (filter #(str/includes? (val %) "<aside class=\"clogem-sidebar\" id=\"clogem-sidebar\">") ps)]
    (is (>= (count sidebar-pages) 10))
    (doseq [[rel page] ps]
      (let [sb? (str/includes? page "<aside class=\"clogem-sidebar\"")]
        (is (= (if sb? 1 0) (count (re-seq #"clogem-sidebar-toggle" page))) rel)
        (is (= (if sb? 1 0) (count (re-seq #"aria-controls=\"clogem-sidebar\"" page))) rel)
        (is (= (if sb? 1 0) (count (re-seq #"id=\"clogem-sidebar\"" page))) rel)
        (when sb?
          (testing (str rel ": hidden, named, both chevrons, the main's first child, outside the article")
            (is (re-find sidebar-toggle-re page) rel)
            (is (re-find #"<main class=\"clogem-main\"><button aria-controls=\"clogem-sidebar\"" page) rel)
            (is (< (str/index-of page "clogem-sidebar-toggle") (str/index-of page "<article")) rel)
            (is (not (re-find #"data-clogem-collapsed" page)) (str rel ": the default emits no marker"))))))
    (testing "none on the home, a category page, the catalogue and a post"
      (doseq [p [["index.html"] ["categories" "guide" "index.html"] ["pages" "559f0f" "index.html"] ["pages" "284c67" "index.html"]]]
        (is (not (str/includes? (apply html p) "clogem-sidebar")) (str p))))
    (testing "named in the page's language (:page/sidebar)"
      (is (= "Sidebar" (second (re-find sidebar-toggle-re (html "pages" "c0nta1" "index.html")))))
      (is (= "侧边栏" (second (re-find sidebar-toggle-re (html "zh-Hans" "pages" "c0nta1" "index.html"))))))))

(deftest sidebar-collapsed-true-marks-the-shell
  (let [files {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article "_posts/p.md" a-post}
        on (build-temp (assoc one-lang :theme {:sidebar-collapsed true}) files)
        off (build-temp one-lang files)]
    (is (re-find #"<div class=\"clogem-shell clogem-shell--toc\" data-clogem-collapsed=\"\">" (on "pages/t00001/index.html")))
    (testing "on the sidebar page only"
      (doseq [[rel page] on :when (not= rel "pages/t00001/index.html")]
        (is (not (str/includes? page "data-clogem-collapsed")) rel)))
    (testing "the default emits none"
      (is (not-any? #(str/includes? (val %) "data-clogem-collapsed") off)))
    (testing "no CSS rule reads the server's marker; nav.js does"
      (is (not (str/includes? (slurp theme-css) "collapsed]")))
      (is (not (str/includes? (slurp theme-css) "data-clogem-collapsed")))
      (is (str/includes? (slurp "src/clogem/theme/resources/js/nav.js") "hasAttribute(\"data-clogem-collapsed\")")))
    (testing "the key is known; vdoing's sidebarOpen names it, with no 'planned'"
      (is (= :ok (get-in config/known-keys [[:theme] :sidebar-collapsed])))
      (let [[_ ds] (load-site {:theme {:sidebarOpen false}})]
        (is (= [(str ":theme :sidebarOpen is vdoing's spelling; clogem-press reads :theme :sidebar-collapsed "
                     "(with the opposite sense: sidebarOpen false is :sidebar-collapsed true). It is ignored.")]
               (map :message (diag/warnings ds))))))))

(deftest the-collapse-css-is-above-50rem-only
  (let [rules (cp/parse (slurp theme-css))
        collapsing (filter #(some (fn [sel] (str/includes? sel "is-sidebar-collapsed")) (:selectors %)) rules)]
    (is (seq collapsing))
    (is (every? #(some #{"not all and (max-width: 50rem)" "(min-width: 80rem)"} (:media %)) collapsing)
        (pr-str (map :selectors collapsing)))
    (is (some #(= ["(max-width: 50rem)"] (:media %))
              (filter #(some #{".clogem-sidebar-toggle"} (:selectors %)) rules))
        "at 50rem and below the button is not drawn")))

;; ---------------------------------------------------------------------------
;; 11. Page buttons: :theme :page-button

(defn- visible-prev-next
  "{:prev [href lang title] :next …} from the page's `.clogem-prev-next`."
  [page]
  (into {}
        (for [[_ k href lang title] (re-seq #"<a class=\"clogem-prev-next__(prev|next)\" href=\"([^\"]+)\" lang=\"([^\"]+)\" rel=\"(?:prev|next)\"><span>[^<]*</span> (.*?)</a>" page)]
          [(keyword k) [href lang title]])))

(defn- page-buttons-of
  "{:prev [href lang title] :next …} from the page's page buttons, which must
  be exactly as rendered: aria-hidden, out of the Tab order, no rel, a
  chevron and no text."
  [page]
  (into {}
        (for [[_ k href lang title ic] (re-seq #"<a aria-hidden=\"true\" class=\"clogem-page-button is-(prev|next)\" href=\"([^\"]+)\" lang=\"([^\"]+)\" tabindex=\"-1\" title=\"([^\"]*)\"><svg[^>]*class=\"clogem-icon\"[^>]*><use href=\"[^\"]*#(chevron-left|chevron-right)\"(?:/>|></use>)</svg></a>" page)]
          (do (is (= ic (if (= k "prev") "chevron-left" "chevron-right")))
              [(keyword k) [href lang title]]))))

(deftest page-buttons-repeat-prev-next
  (let [ps (pages)
        with (filter #(str/includes? (val %) "clogem-prev-next") ps)]
    (is (>= (count with) 20))
    (doseq [[rel page] ps]
      (let [v (visible-prev-next page)
            b (page-buttons-of page)]
        (is (= (count (re-seq #"clogem-page-button" page)) (count b)) (str rel ": every button has the exact markup"))
        (is (= (keys v) (keys b)) rel)
        (doseq [k (keys v)]
          (is (= (take 2 (v k)) (take 2 (b k))) (str rel " " k ": the same href and lang"))
          (is (= (str/replace (nth (v k) 2) "\"" "&quot;") (nth (b k) 2)) (str rel " " k ": the target's title")))
        (when (seq b)
          (is (< (str/index-of page "</article>") (str/index-of page "clogem-page-button")) (str rel ": outside the article"))
          (is (not (re-find #"<a [^>]*class=\"clogem-page-button[^>]*rel=" page)) (str rel ": no rel")))))
    (testing "c0nta1: one rel=prev and one rel=next, both buttons"
      (let [page (html "pages" "c0nta1" "index.html")]
        (is (= 1 (count (re-seq #"rel=\"prev\"" page))))
        (is (= 1 (count (re-seq #"rel=\"next\"" page))))
        (is (= {:prev ["/pages/22deb7/" "en"] :next ["/pages/171a98/" "ta"]}
               (update-vals (page-buttons-of page) #(vec (take 2 %)))))))
    (testing "the catalogue has a next only; the first leaf has no prev"
      (is (= [:next] (keys (page-buttons-of (html "pages" "559f0f" "index.html")))))
      (is (= "/pages/c4d33p/" (first (:next (page-buttons-of (html "pages" "559f0f" "index.html"))))))
      (is (nil? (:prev (page-buttons-of (html "pages" "3ce486" "index.html"))))))
    (testing "no page buttons on the home or an index page"
      (is (not (str/includes? (html "index.html") "clogem-page-button")))
      (is (not (str/includes? (html "categories" "guide" "index.html") "clogem-page-button"))))))

(deftest page-button-false-omits-them
  (let [files {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article
               "01.Guide/02.u.md" (str/replace a-toc-article "t00001" "t00002")}
        on (build-temp one-lang files)
        off (build-temp (assoc one-lang :theme {:page-button false}) files)
        alone (build-temp one-lang {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article})]
    (is (= [:next] (keys (page-buttons-of (on "pages/t00001/index.html")))))
    (is (= [:prev] (keys (page-buttons-of (on "pages/t00002/index.html")))))
    (is (not-any? #(str/includes? (val %) "clogem-page-button") off))
    (is (str/includes? (off "pages/t00001/index.html") "rel=\"next\"") "the visible links stay")
    (testing "a page without prev-next has neither"
      (is (not (str/includes? (alone "pages/t00001/index.html") "clogem-prev-next")))
      (is (not (str/includes? (alone "pages/t00001/index.html") "clogem-page-button"))))
    (is (= :ok (get-in config/known-keys [[:theme] :page-button])))))

(deftest page-buttons-show-from-84rem
  (let [rules (cp/parse (slurp theme-css))
        of (fn [q sel] (into {} (for [r rules :when (and (= q (:media r)) (some #{sel} (:selectors r))) [p v] (:decls r)] [p v])))]
    (is (= "none" ((of [] ".clogem-page-button") "display")))
    (let [d (of ["(min-width: 84rem)"] ".clogem-page-button")]
      (is (= ["fixed" "flex" "50px" "70px" "0.55" "5"] (map d ["position" "display" "width" "height" "opacity" "z-index"]))))
    (is (= "1" ((of ["(min-width: 84rem)"] ".clogem-page-button:hover") "opacity")))
    (is (= "80px" ((of ["(min-width: 88rem)"] ".clogem-page-button") "width")))))

;; ---------------------------------------------------------------------------
;; 12. Background patterns: :theme :content-bg-style

(def ^:private bg-files
  "A site with an article, a catalogue, two tagged posts (so /page/2/ at one
  per page), category, tag and archive pages."
  {"index.md" "# Home\n"
   "01.Guide/01.t.md" (str/replace a-toc-article "title: T\n" "title: T\ntags:\n  - x\n")
   "00.Catalogue/01.c.md" (str "---\ntitle: C\narticle: false\nsidebar: false\npageComponent:\n  name: Catalogue\n"
                               "  data:\n    path: 01.Guide\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/c00001/\n---\n\nC\n")
   "_posts/p.md" (str/replace a-post "title: P\n" "title: P\ntags:\n  - x\n")
   "_posts/q.md" (-> a-post (str/replace "title: P\n" "title: Q\ntags:\n  - x\n") (str/replace "p00001" "q00001")
                     (str/replace "2026-02-02" "2026-02-03"))})

(defn- main-tag [page] (re-find #"<main[^>]*>" page))

(deftest content-bg-style-marks-article-and-catalogue-mains
  (doseq [n (range 1 7)]
    (let [ps (build-temp (assoc one-lang :theme {:content-bg-style n :per-page 1}) bg-files)
          want (str "<main class=\"clogem-main clogem-bg-style-" n "\">")]
      (testing (str n)
        (doseq [rel ["pages/t00001/index.html" "pages/p00001/index.html" "pages/c00001/index.html"]]
          (is (= want (main-tag (ps rel))) (str n " " rel)))
        (is (contains? ps "page/2/index.html"))
        (doseq [[rel page] ps
                :when (not (re-find #"^pages/" rel))]
          (is (not (str/includes? page "clogem-bg-style")) (str n " " rel)))
        (is (some #(re-find #"^(categories|tags|archives)/" (key %)) ps)))))
  (testing "the default, and every bad value, put none (and the bad ones warn)"
    (let [ps (build-temp (assoc one-lang :theme {:per-page 1}) bg-files)]
      (is (= "<main class=\"clogem-main\">" (main-tag (ps "pages/t00001/index.html"))))
      (is (not-any? #(str/includes? (val %) "clogem-bg-style") ps)))
    (doseq [v [0 7 1.5 "1" :grid true]]
      (let [ps (build-temp (assoc one-lang :theme {:content-bg-style v}) bg-files)]
        (is (not-any? #(str/includes? (val %) "clogem-bg-style") ps) (pr-str v)))
      (let [[cfg ds] (load-site {:theme {:content-bg-style v}})]
        (is (= 1 (count (diag/warnings ds))) (pr-str v))
        (is (nil? (get-in cfg [:theme :content-bg-style])))))))

(deftest the-patterns-css
  (let [css (slurp theme-css)
        rules (cp/parse css)
        decl (fn [sel p] ((cp/rule rules sel) p))]
    (testing "vdoing's six patterns, in longhands only"
      (is (= 2 (count (re-seq #"linear-gradient\(" (decl ".clogem-main.clogem-bg-style-1" "background-image")))))
      (is (= "center center" (decl ".clogem-main.clogem-bg-style-1" "background-position")))
      (doseq [[n deg size] [[2 "0deg" "30px 30px"] [3 "90deg" "30px 30px"] [4 "-45deg" "20px 20px"] [5 "45deg" "20px 20px"]]]
        (is (str/starts-with? (decl (str ".clogem-main.clogem-bg-style-" n) "background-image")
                              (str "repeating-linear-gradient(" deg ", var(--linesColor) 0")) (str n))
        (is (= size (decl (str ".clogem-main.clogem-bg-style-" n) "background-size")) (str n)))
      (is (str/starts-with? (decl ".clogem-main.clogem-bg-style-6" "background-image") "radial-gradient(var(--linesColor) 1px"))
      (doseq [r rules :when (some #(str/includes? % "clogem-bg-style") (:selectors r))]
        (is (not-any? #(= "background" (first %)) (:decls r)) (pr-str (:selectors r)))))
    (testing "--linesColor in every mode: vdoing's light value in light, its dark one in dark and auto, read's lighter"
      (is (= "rgba(50, 0, 0, 0.05)" ((cp/palette rules :light) "--linesColor")))
      (is (= "rgba(50, 0, 0, 0.03)" ((cp/palette rules :read) "--linesColor")) "P4-B2b.1 item 5: 0.05 twice took the accent to 4.22:1")
      (is (= "rgba(125, 125, 125, 0.05)" ((cp/palette rules :dark) "--linesColor") ((cp/palette rules :auto) "--linesColor"))))
    (testing "print draws no pattern"
      (is (= "none" ((into {} (for [r rules :when (= ["print"] (:media r)) :when (some #{"html[class] .clogem-main"} (:selectors r))
                                     [p v] (:decls r)] [p v])) "background-image"))))
    (testing "the palette checks the lines on the card: the real values pass, strong lines fail"
      (is (some #(= "--linesColor over --mainBg" (first %)) (cp/surfaces (cp/palette rules :light))))
      (is (= [] (cp/failures css)))
      (let [strong (str/replace-first css "  --linesColor: rgba(50, 0, 0, 0.05);" "  --linesColor: rgba(50, 0, 0, 0.05);\n  --linesColor: rgba(0,0,0,0.6);")]
        (is (not= css strong))
        (is (seq (cp/failures strong)))
        (is (some #(str/includes? % "--linesColor over --mainBg") (cp/failures strong)))))
    (testing "P4-B2b.1 item 5: pattern 1's crossings, --linesColor twice over the card; read's 0.05 fails there"
      (is (some #(= "--linesColor twice over --mainBg" (first %)) (cp/surfaces (cp/palette rules :read))))
      (let [old (str/replace-first css "  --linesColor: rgba(50, 0, 0, 0.03);" "  --linesColor: rgba(50, 0, 0, 0.05);")]
        (is (not= css old))
        (is (= ["read/card: --accent on --linesColor twice over --mainBg is 4.22:1"
                "read/line: --accent on --linesColor twice over --mainBg is 4.22:1"] (cp/failures old)))))))

;; ---------------------------------------------------------------------------
;; 13. Back to top: :theme :back-to-top

(def ^:private back-to-top-re
  #"<button aria-label=\"([^\"]+)\" class=\"clogem-back-to-top\" hidden=\"hidden\" title=\"\1\" type=\"button\"><svg[^>]*class=\"clogem-icon\"[^>]*><use href=\"[^\"]*#arrow-up\"(?:/>|></use>)</svg></button></body>")

(deftest back-to-top-ends-every-page-with-a-navbar
  (let [ps (pages)]
    (doseq [[rel page] ps
            :when (str/includes? page "<header class=\"clogem-navbar\"")]
      (is (= 1 (count (re-seq #"clogem-back-to-top" page))) rel)
      (is (re-find back-to-top-re page) (str rel ": hidden, named, last in <body>")))
    (testing "an article, a catalogue, the home, a category page and the archive"
      (doseq [p [["pages" "c0nta1" "index.html"] ["pages" "559f0f" "index.html"] ["index.html"]
                 ["categories" "guide" "index.html"] ["archives" "index.html"]]]
        (is (= "Back to top" (second (re-find back-to-top-re (apply html p)))) (str p))))
    (testing "named in the page's language"
      (is (= "返回顶部" (second (re-find back-to-top-re (html "zh-Hans" "index.html")))))
      (is (= "返回頂部" (second (re-find back-to-top-re (html "zh-Hant" "index.html")))))
      (is (= "Kembali ke atas" (second (re-find back-to-top-re (html "ms" "index.html")))))
      (is (= "மேலே செல்" (second (re-find back-to-top-re (html "ta" "index.html"))))))
    (testing "no page without the navbar has one (redirect stubs)"
      (doseq [[rel page] ps :when (not (str/includes? page "<header class=\"clogem-navbar\""))]
        (is (not (str/includes? page "clogem-back-to-top")) rel)))))

(deftest back-to-top-false-removes-it
  (let [files {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article}
        off (build-temp (assoc one-lang :theme {:back-to-top false}) files)
        on (build-temp one-lang files)]
    (is (seq off))
    (is (not-any? #(str/includes? (val %) "clogem-back-to-top") off))
    (is (every? #(re-find back-to-top-re (val %)) (filter #(str/includes? (val %) "clogem-navbar") on)))
    (is (= :ok (get-in config/known-keys [[:theme] :back-to-top])))))

(deftest the-back-to-top-css
  (let [rules (cp/parse (slurp theme-css))
        of (fn [q sel] (into {} (for [r rules :when (and (= q (:media r)) (some #{sel} (:selectors r))) [p v] (:decls r)] [p v])))
        d (of [] ".clogem-back-to-top")]
    (is (= ["fixed" "2rem" "2.5rem" "2.2rem" "2.2rem" "var(--blurBg)" "5"]
           (map d ["position" "right" "bottom" "width" "height" "background" "z-index"])))
    (is (= "none" ((of [] ".clogem-back-to-top[hidden]") "display")))
    (is (= ["1rem" "1.5rem"] (map (of ["(max-width: 50rem)"] ".clogem-back-to-top") ["right" "bottom"])))
    (testing "nav.js scrolls to the top (its 100 px threshold is checked in the browser, layout.test.mjs item 13)"
      (let [js (slurp "src/clogem/theme/resources/js/nav.js")]
        (is (str/includes? js "window.scrollTo({ top: 0 })"))))))
