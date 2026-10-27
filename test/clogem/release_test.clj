;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.release-test
  "Release 0.3.0 (DESIGN.md §8 Phase 4, §11.3 item 22): the last vdoing
  keys the owner chose to implement, defer or mark implicit, each pinned
  by a test that failed before its change."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.model :as model]
            [clogem.render :as render]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- render-tree
  "Materialize {rel-path → content} under a temp content/ tree with
  `site-edn` as its site.edn, analyse and render every page in memory:
  {:html (fn [uri] html) :warnings [message …] :errors [message …]}."
  [site-edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-rel"})]
    (try
      (spit (fs/file dir "site.edn")
            (pr-str (merge {:site {:title "S" :url "https://s.example"} :search {:provider :none} :highlight {:provider :none}}
                           site-edn)))
      (doseq [[rel content] files
              :let [f (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent f))
        (spit (fs/file f) content))
      (let [[rendered ds]
            (diag/collecting
             (let [cfg (config/load-config (str dir) nil {:content {:write-front-matter false}})
                   m   (cli/analyse cfg)]
               ;; render NOW: the thunks read files under `dir`
               (into {} (map (fn [[u f]] [u (str (hiccup2.core/html (f)))]))
                     (render/page-map m))))]
        {:html     (fn [uri] (get rendered uri))
         :warnings (mapv :message (diag/warnings ds))
         :errors   (mapv :message (diag/errors ds))})
      (finally (fs/delete-tree dir)))))

(defn- warnings-of
  "Load `edn` as a site.edn: {message hint} of every warning (and no errors)."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-rel-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try
      (let [[_ ds] (diag/collecting (config/load-config (str dir)))]
        (is (empty? (diag/errors ds)) "never an error")
        (into {} (map (juxt :message :hint)) (diag/warnings ds)))
      (finally (fs/delete-tree dir)))))

(defn- article
  "An article's source: `title`, permalink /pages/<pl>/, a date, then the
  extra front matter lines `fm` and a body with one h2."
  [title pl & [fm]]
  (str "---\ntitle: " title "\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/" pl "/\n"
       (or fm "") "---\n\n# " title "\n\n## Section\n\nbody\n"))

(defn- has-link? [html rel pl]
  (boolean (re-find (re-pattern (str "href=\"/pages/" pl "/\"[^>]* rel=\"" rel "\"")) (str html))))

(defn- has-button? [html cls]
  (str/includes? (str html) (str "class=\"clogem-page-button " cls "\"")))

;; ---------------------------------------------------------------------------
;; 1a.1 — themeConfig prevLinks / nextLinks (vdoing components/PageNav.vue
;; l.108-143: the page's `prev`/`next` wins when it is not nil, else the
;; theme's; `false` hides the link; a string names a page)

(def ^:private guide
  {"01.Guide/01.a.md" (article "A" "aaaaa1")
   "01.Guide/02.b.md" (article "B" "bbbbb1")
   ;; vdoing: `prev: true` is not nil, so it overrides the theme's false
   ;; and resolves the sidebar neighbour
   "01.Guide/03.c.md" (article "C" "ccccc1" "prev: true\nnext: true\n")
   "01.Guide/04.d.md" (article "D" "ddddd1" "prev: /pages/aaaaa1/\n")})

(deftest prev-links-and-next-links-are-site-wide-switches
  (testing "default: both links (and both page buttons) on every page"
    (let [{:keys [html warnings]} (render-tree {} guide)
          b (html "/pages/bbbbb1/")]
      (is (= [] warnings))
      (is (has-link? b "prev" "aaaaa1"))
      (is (has-link? b "next" "ccccc1"))
      (is (has-button? b "is-prev"))
      (is (has-button? b "is-next"))
      (testing "front matter `prev: true` / `next: true` is the default neighbour"
        (is (has-link? (html "/pages/ccccc1/") "prev" "bbbbb1"))
        (is (has-link? (html "/pages/ccccc1/") "next" "ddddd1")))))
  (testing ":prev-links false hides every previous link and its page button"
    (let [{:keys [html warnings]} (render-tree {:theme {:prev-links false}} guide)
          b (html "/pages/bbbbb1/")]
      (is (= [] warnings))
      (is (not (str/includes? b "rel=\"prev\"")))
      (is (not (has-button? b "is-prev")))
      (is (has-link? b "next" "ccccc1") "the next link stays")
      (is (has-button? b "is-next"))
      (is (not (str/includes? (html "/pages/aaaaa1/") "rel=\"prev\"")))
      (testing "…except where the page's own `prev` says otherwise"
        (is (has-link? (html "/pages/ccccc1/") "prev" "bbbbb1") "prev: true")
        (is (has-link? (html "/pages/ddddd1/") "prev" "aaaaa1") "prev: /pages/aaaaa1/"))))
  (testing ":next-links false hides every next link and its page button"
    (let [{:keys [html]} (render-tree {:theme {:next-links false}} guide)
          b (html "/pages/bbbbb1/")]
      (is (not (str/includes? b "rel=\"next\"")))
      (is (not (has-button? b "is-next")))
      (is (has-link? b "prev" "aaaaa1"))
      (is (has-link? (html "/pages/ccccc1/") "next" "ddddd1") "next: true")))
  (testing "both false: no prev-next nav at all"
    (let [{:keys [html]} (render-tree {:theme {:prev-links false :next-links false}} guide)]
      (is (not (str/includes? (html "/pages/bbbbb1/") "clogem-prev-next"))))))

(deftest prev-links-and-next-links-are-checked
  (is (= {} (warnings-of {:theme {:prev-links false :next-links true}})))
  (let [ws (warnings-of {:theme {:prev-links "no" :next-links 0}})]
    (is (= #{":theme :prev-links is \"no\", but it must be true or false; using true."
             ":theme :next-links is 0, but it must be true or false; using true."}
           (set (keys ws))))
    (is (= "See config.example.edn (vdoing's prevLinks)."
           (ws ":theme :prev-links is \"no\", but it must be true or false; using true."))))
  (is (= {":theme :prevLinks is vdoing's spelling; clogem-press reads :theme :prev-links. It is ignored."
          "Write :theme :prev-links instead (DESIGN.md §5.6)."
          ":theme :nextLinks is vdoing's spelling; clogem-press reads :theme :next-links. It is ignored."
          "Write :theme :next-links instead (DESIGN.md §5.6)."}
         (warnings-of {:theme {:prevLinks false :nextLinks false}}))))

;; ---------------------------------------------------------------------------
;; 1a.2 — front matter showSidebar (vdoing layouts/Layout.vue l.175-183:
;; `frontmatter.showSidebar !== false`, beside `frontmatter.sidebar !==
;; false`, decides the `no-sidebar` class; unlike `sidebar: false`
;; (l.155) it leaves the right menu alone — as clogem's `sidebar: false`
;; already does)

(deftest show-sidebar-false-hides-the-sidebar-as-sidebar-false-does
  (let [{:keys [html warnings]}
        (render-tree {} {"01.Guide/01.a.md" (article "A" "aaaaa2")
                         "01.Guide/02.b.md" (article "B" "bbbbb2" "showSidebar: false\n")
                         "01.Guide/03.c.md" (article "C" "ccccc2" "sidebar: false\n")
                         "01.Guide/04.d.md" (article "D" "ddddd2" "showSidebar: true\n")})
        shows? #(str/includes? (str %) "id=\"clogem-sidebar\"")]
    (is (= [] warnings))
    (is (shows? (html "/pages/aaaaa2/")))
    (is (shows? (html "/pages/ddddd2/")) "only false hides it")
    (doseq [[pl why] [["bbbbb2" "showSidebar: false"] ["ccccc2" "sidebar: false"]]
            :let [p (html (str "/pages/" pl "/"))]]
      (is (not (shows? p)) why)
      (is (not (str/includes? p "clogem-sidebar-toggle")) (str why ": no collapse button"))
      (is (str/includes? p "clogem-shell--single") (str why ": the single-column shell"))
      (is (str/includes? p "class=\"clogem-toc\"") (str why ": the TOC stays")))))

;; ---------------------------------------------------------------------------
;; 1a.3 — pageComponent.data.key (vdoing components/Catalogue.vue l.102-103:
;; `const key = data.path || data.key`)

(deftest catalogue-data-key-is-an-alias-of-data-path
  (is (= "/01.Guide" (model/catalogue-dir-key {:data {:key "01.Guide"}})))
  (is (= "/01.Guide/10.Sub" (model/catalogue-dir-key {:data {:path "01.Guide/10.Sub" :key "01.Guide"}}))
      "path wins when both are set")
  (is (= "/01.Guide" (model/catalogue-dir-key {:data {:path "" :key "01.Guide"}}))
      "a blank path is no path (JavaScript's `||`)")
  (is (nil? (model/catalogue-dir-key {:data {}})))
  (let [cat (fn [pl data] (str "---\ntitle: Cat " pl "\npermalink: /pages/" pl "/\narticle: false\n"
                               "pageComponent:\n  name: Catalogue\n  data:\n" data "---\n\nthe body\n"))
        {:keys [html warnings]}
        (render-tree {} {"01.Guide/01.a.md"        (article "A" "aaaaa3")
                         "01.Guide/10.Sub/01.x.md" (article "X" "xxxxx3")
                         "00.Catalogue/01.key.md"  (cat "kkkkk3" "    key: 01.Guide\n")
                         "00.Catalogue/02.both.md" (cat "bbbbb3" "    path: 01.Guide/10.Sub\n    key: 01.Guide\n")})
        by-key (html "/pages/kkkkk3/")
        both   (html "/pages/bbbbb3/")]
    (is (= [] warnings))
    (is (str/includes? by-key "clogem-catalogue__grid") "data.key renders the catalogue")
    (is (not (str/includes? by-key "the body")))
    (is (str/includes? by-key "/pages/aaaaa3/") "…of 01.Guide")
    (is (str/includes? both "/pages/xxxxx3/"))
    (is (not (str/includes? both "/pages/aaaaa3/")) "data.path wins: 01.Guide/10.Sub only")))

;; ---------------------------------------------------------------------------
;; 1a.4 — a nav item's ariaLabel (vdoing components/DropdownLink.vue l.6,
;; l.74-77: the dropdown button's `aria-label`, `item.ariaLabel ||
;; item.text`; NavLink.vue reads none, clogem-press labels a link too)

(deftest a-nav-item-aria-label-labels-its-link-or-dropdown
  (let [{:keys [html warnings]}
        (render-tree {:nav [{:text "Home" :link "/" :aria-label "Go to the home page"}
                            {:text "More" :aria-label {:en "More pages" :zh-Hans "更多页面"}
                             :items [{:text "A" :link "/pages/aaaaa4/" :aria-label "Article A"}]}
                            {:text "Plain" :link "/pages/aaaaa4/"}]}
                     {"01.Guide/01.a.md" (article "A" "aaaaa4")})
        home (html "/")]
    (is (= [] warnings))
    (is (re-find #"<a aria-label=\"Go to the home page\" href=\"/\">Home</a>" home))
    (is (re-find #"<summary aria-label=\"More pages\"><span>More</span>" home)
        "a dropdown's label goes on its <summary>, the control vdoing's button is")
    (is (re-find #"<a aria-label=\"Article A\" href=\"/pages/aaaaa4/\">A</a>" home))
    (is (re-find #"<a href=\"/pages/aaaaa4/\">Plain</a>" home) "no aria-label without one")
    (is (re-find #"<summary aria-label=\"更多页面\">" (html "/zh-Hans/")) "a per-language map, like :text"))
  (is (= {} (warnings-of {:nav [{:text "A" :link "/a/" :aria-label "a"
                                 :items [{:text "b" :link "/b/" :aria-label {:en "b"}}]}]})))
  (is (= {":nav 0 :ariaLabel is vdoing's spelling; clogem-press reads :aria-label (a :nav item's label for screen readers). It is ignored."
          "Write :aria-label instead (DESIGN.md §5.6)."}
         (warnings-of {:nav [{:text "A" :link "/a/" :ariaLabel "a"}]}))))

;; ---------------------------------------------------------------------------
;; 1a.5 — themeConfig navbar is deferred, as front matter `navbar: false`
;; already is (vdoing layouts/Layout.vue l.158-173)

(deftest the-navbar-switch-is-deferred
  (doseq [m [{:navbar false} {:theme {:navbar false}}]
          :let [p (if (:theme m) ":theme :navbar" ":navbar")]]
    (is (= {(str p " is a vdoing option clogem-press defers past v1 (DESIGN.md §8); "
                 "every page shows the navbar.") nil}
           (warnings-of m)))))

;; ---------------------------------------------------------------------------
;; 1a.6 — :implicit: what clogem-press always does

(deftest the-last-implicit-keys-say-what-clogem-does
  (let [ws (warnings-of {:theme {:searchPlaceholder "Search…" :selectText "Languages"
                                 :activeHeaderLinks false}
                         :locales {"/" {:lang "en-US"}}
                         :label "English"})
        msg (fn [p note] (str p " is not needed: clogem-press " note "; it is ignored."))]
    (is (= 5 (count ws)) (pr-str (keys ws)))
    (doseq [[p note] [[":theme :searchPlaceholder"
                       "takes the search box's placeholder from the i18n string :search/placeholder"]
                      [":theme :selectText"
                       "labels the language menu with the i18n string :nav/language"]
                      [":theme :activeHeaderLinks"
                       "always marks the heading being read in the table of contents"]
                      [":locales" "sets each language under :langs :locales, and its words in i18n/<lang>.edn"]
                      [":label" "names each language with :langs :locales <lang> :label"]]]
      (is (contains? ws (msg p note)) (str p " " (pr-str (keys ws))))
      (is (some? (get ws (msg p note))) (str p ": a hint says what to do")))
    (testing ":label is still a locale option, and :locales a :langs one"
      (is (= {} (warnings-of {:langs {:locales {:en {:label "English"}}}}))))))
