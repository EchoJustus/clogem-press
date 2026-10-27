;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.release-test
  "Release 0.3.0 (DESIGN.md §8 Phase 4, §11.3 item 22).

  First, the last vdoing keys the owner chose to implement, defer or mark
  implicit (2026-10-27), each pinned by a test that failed before its
  change. Then Phase 4's exit criterion, asserted: every key of vdoing
  1.12.9's themeConfig (`vdoing-theme-keys`) and every front-matter key it
  reads (`vdoing-front-matter-keys`) — each cited by vdoing file:line, at
  xugaoyi/vuepress-theme-vdoing d77b420 — is implemented, deferred or
  removed, and `doctor` names every deferred, implicit and removed one."
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

;; ---------------------------------------------------------------------------
;; Item 1 — the Phase 4 exit criterion (DESIGN.md §8): "every key of
;; vdoing's `themeConfig`, and every front-matter key vdoing reads, is
;; implemented, deferred (below) or removed (below); for a `site.edn` key,
;; `doctor` already says which"

(def vdoing-theme-keys
  "vdoing 1.12.9's themeConfig keys — xugaoyi/vuepress-theme-vdoing at
  d77b420, under `vdoing/` — as vdoing spells them, each as [key what-
  clogem-press-does where-vdoing-has-it]. What clogem-press does is

    a path      implemented: the key is known (`:ok`) at that path in
                `config/known-keys` (`:*` is any key; `:site :author`'s
                keys are `config/author-keys`), and `config/vdoing-keys`,
                when it lists the key, maps it there (a suffix of the path);
    :implicit, :deferred, :dropped
                the key's status in `config/vdoing-keys`: covered by what
                clogem-press always does, deferred past v1, or removed
                (DESIGN.md §8) — `doctor` names it either way;
    [:in k]     a key inside the value of `k`, which `doctor` does not
                walk: `k`'s own status covers it;
    :unread     declared, but never read by vdoing, and not classified
                (none is left: the owner dropped `target` and `rel`).

  vdoing's `VdoingThemeConfig` (types/index.ts l.9) extends the default
  theme's `DefaultThemeConfig` minus `sidebar` (l.3), from @vuepress/types
  1.9.5 (src/theme-default.ts, cited as theme-default.ts), so both are
  listed; then the keys vdoing reads that neither declares."
  [;; -- types/index.ts: VdoingThemeConfig's own keys (l.14-174)
   ["category"          [:content :category]                  "types/index.ts:14"]
   ["tag"               [:content :tag]                       "types/index.ts:20"]
   ["archive"           [:content :archive]                   "types/index.ts:26"]
   ["categoryText"      [:content :category-text]             "types/index.ts:32"]
   ["pageStyle"         [:theme :page-style]                  "types/index.ts:38"]
   ["bodyBgImg"         [:theme :body-bg-img]                 "types/index.ts:44"]
   ["bodyBgImgOpacity"  [:theme :body-bg-img-opacity]         "types/index.ts:50"]
   ["bodyBgImgInterval" [:theme :body-bg-img-interval]        "types/index.ts:57"]
   ["titleBadge"        [:theme :title-badge]                 "types/index.ts:63"]
   ["titleBadgeIcons"   [:theme :title-badge-icons]           "types/index.ts:69"]
   ["contentBgStyle"    [:theme :content-bg-style]            "types/index.ts:75"]
   ["updateBar"         [:theme :update-bar]                  "types/index.ts:81"]
   ["showToArticle"     [:theme :update-bar :show-to-article] "types/index.ts:82"]
   ["moreArticle"       [:theme :update-bar :more-article]    "types/index.ts:83"]
   ["rightMenuBar"      [:theme :right-menu-bar]              "types/index.ts:90"]
   ["sidebarOpen"       [:theme :sidebar-collapsed]           "types/index.ts:96"]
   ["pageButton"        [:theme :page-button]                 "types/index.ts:102"]
   ["defaultMode"       [:theme :default-mode]                "types/index.ts:108"]
   ["sidebar"           :implicit                             "types/index.ts:113"]
   ["mode"              [:in "sidebar"]                       "types/index.ts:115"]
   ["collapsable"       [:in "sidebar"]                       "types/index.ts:115"]
   ["author"            [:site :author]                       "types/index.ts:121"]
   ["name"              [:site :author :name]                 "types/index.ts:121"]
   ["link"              [:site :author :link]                 "types/index.ts:121"]
   ["blogger"           [:theme :blogger]                     "types/index.ts:126"]
   ["avatar"            [:theme :blogger :avatar]             "types/index.ts:127"]
   ["name"              [:theme :blogger :name]               "types/index.ts:128"]
   ["slogan"            [:theme :blogger :slogan]             "types/index.ts:129"]
   ["social"            [:theme :social]                      "types/index.ts:136"]
   ["iconfontCssFile"   :implicit                             "types/index.ts:137"]
   ["icons"             [:theme :social :icons]               "types/index.ts:138"]
   ["iconClass"         [:theme :social :icons :* :icon]      "types/index.ts:139"]
   ["title"             [:theme :social :icons :* :title]     "types/index.ts:140"]
   ["link"              [:theme :social :icons :* :link]      "types/index.ts:141"]
   ["footer"            [:theme :footer]                      "types/index.ts:148"]
   ["createYear"        [:theme :footer :create-year]         "types/index.ts:149"]
   ["copyrightInfo"     [:theme :footer :copyright-info]      "types/index.ts:150"]
   ["extendFrontmatter" [:content :extend-frontmatter]        "types/index.ts:157"]
   ["htmlModules"       [:theme :html-modules]                "types/index.ts:165"]
   ["homeSidebarB"      [:theme :html-modules :home-sidebar-b] "types/index.ts:166"]
   ["sidebarT"          [:theme :html-modules :sidebar-t]     "types/index.ts:167"]
   ["sidebarB"          [:theme :html-modules :sidebar-b]     "types/index.ts:168"]
   ["pageT"             [:theme :html-modules :page-t]        "types/index.ts:169"]
   ["pageB"             [:theme :html-modules :page-b]        "types/index.ts:170"]
   ["pageTshowMode"     [:theme :html-modules :page-t-show-mode] "types/index.ts:171"]
   ["pageBshowMode"     [:theme :html-modules :page-b-show-mode] "types/index.ts:172"]
   ["windowLB"          [:theme :html-modules :window-lb]     "types/index.ts:173"]
   ["windowRB"          [:theme :html-modules :window-rb]     "types/index.ts:174"]
   ;; -- theme-default.ts: DefaultThemeConfig (l.111-263)
   ["logo"              [:theme :logo]                        "theme-default.ts:117"]
   ["nav"               [:nav]                                "theme-default.ts:123"]
   ["navbar"            :deferred                             "theme-default.ts:127"]
   ["search"            [:search :provider]                   "theme-default.ts:141"]
   ["searchMaxSuggestions" :dropped                           "theme-default.ts:145"]
   ["searchPlaceholder" :implicit                             "theme-default.ts:149"]
   ["algolia"           :deferred                             "theme-default.ts:155"]
   ["apiKey"            [:in "algolia"]                       "theme-default.ts:159"]
   ["indexName"         [:in "algolia"]                       "theme-default.ts:163"]
   ["appId"             [:in "algolia"]                       "theme-default.ts:167"]
   ["algoliaOptions"    [:in "algolia"]                       "theme-default.ts:173"]
   ["lastUpdated"       [:theme :last-updated]                "theme-default.ts:181"]
   ["nextLinks"         [:theme :next-links]                  "theme-default.ts:187"]
   ["prevLinks"         [:theme :prev-links]                  "theme-default.ts:193"]
   ["repo"              [:theme :repo]                        "theme-default.ts:199"]
   ["repoLabel"         [:theme :repo-label]                  "theme-default.ts:205"]
   ["docsRepo"          [:content :edit-link :repo]           "theme-default.ts:216"]
   ["docsDir"           [:content :edit-link :dir]            "theme-default.ts:222"]
   ["docsBranch"        [:content :edit-link :branch]         "theme-default.ts:228"]
   ["editLinks"         [:content :edit-link]                 "theme-default.ts:234"]
   ["editLinkText"      :implicit                             "theme-default.ts:240"]
   ["smoothScroll"      :implicit                             "theme-default.ts:246"]
   ["locales"           :implicit                             "theme-default.ts:250"]
   ["label"             :implicit                             "theme-default.ts:254"]
   ["selectText"        :implicit                             "theme-default.ts:258"]
   ["activeHeaderLinks" :implicit                             "theme-default.ts:262"]
   ;; -- theme-default.ts: a `nav` item, NavItem (l.6-31). vdoing reads
   ;; text, link and items (NavLinks.vue, NavLink.vue) and ariaLabel
   ;; (DropdownLink.vue:76). It never reads target or rel: its NavLink.vue
   ;; (l.9-19) sets both itself, for external links only. The owner dropped
   ;; both (DESIGN.md §8); a site.edn that sets one on a nav item gets the
   ;; dropped warning, which names it.
   ["text"              [:nav :* :text]                       "theme-default.ts:10"]
   ["link"              [:nav :* :link]                       "theme-default.ts:14"]
   ["ariaLabel"         [:nav :* :aria-label]                 "theme-default.ts:18"]
   ["target"            :dropped                              "theme-default.ts:22"]
   ["rel"               :dropped                              "theme-default.ts:26"]
   ["items"             [:nav :* :items]                      "theme-default.ts:30"]
   ;; -- read by vdoing, declared in neither
   ["sidebarDepth"      [:theme :sidebar-depth]               "components/SidebarLink.vue:38"]
   ["displayAllHeaders" :dropped                              "components/SidebarLink.vue:43"]
   ["sidebarHoverTriggerOpen" :dropped                        "layouts/Layout.vue:13"]])

(def vdoing-front-matter-keys
  "Every front-matter key vdoing 1.12.9 reads (grep `frontmatter.` and
  `$frontmatter.` in vdoing/, d77b420), as [key what-clogem-press-does
  where-vdoing-reads-it]. What clogem-press does is

    [:read file snippet]  its front-matter handling reads the key: `snippet`
                          is the reading code in src/clogem/<file>;
    [:deferred s] / [:removed s]
                          DESIGN.md §8's deferred or removed list names it,
                          as `s`.

  `categories` is written by auto-fill, from the primary variant's
  directory path, which is what the indexes use (DESIGN.md §6.2), as vdoing
  writes it from the path (node_utils/setFrontmatter.js:61)."
  [["title"           [:read "model.clj" "(:title front)"]                   "components/Catalogue.vue:94"]
   ["date"            [:read "model.clj" "[:front-matter :date]"]            "components/ArticleInfo.vue:115"]
   ["permalink"       [:read "model.clj" "(:permalink front)"]               "node_utils/setFrontmatter.js:83"]
   ["categories"      [:read "frontmatter.clj" "[:title :date :permalink :categories :tags]"]
                                                                             "components/PostList.vue:45"]
   ["tags"            [:read "model.clj" "[:front-matter :tags]"]            "components/PostList.vue:60"]
   ["sticky"          [:read "model.clj" "[:front-matter :sticky]"]          "util/postData.js:21"]
   ["article"         [:read "model.clj" "[:front-matter :article]"]         "components/Page.vue:110"]
   ["home"            [:read "model.clj" "[:front-matter :home]"]            "layouts/Layout.vue:39"]
   ["titleTag"        [:read "theme/layout.clj" "[:front-matter :titleTag]"] "components/Page.vue:21"]
   ["author"          [:read "theme/layout.clj" "[:front-matter :author]"]   "components/ArticleInfo.vue:114"]
   ["comment"         [:read "theme/layout.clj" ":front-matter :comment]"]   "components/Buttons.vue:156"]
   ["editLink"        [:read "theme/parity.clj" "(get fm :editLink"]         "components/PageEdit.vue:52"]
   ["pageClass"       [:read "theme/layout.clj" "(get fm :pageClass)"]       "layouts/Layout.vue:195"]
   ["sidebar"         [:read "theme/layout.clj" "(:sidebar fm)"]             "layouts/Layout.vue:179"]
   ["showSidebar"     [:read "theme/layout.clj" "(:showSidebar fm)"]         "layouts/Layout.vue:181"]
   ["sidebarDepth"    [:read "render.clj" "[:front-matter :sidebarDepth]"]   "components/SidebarLink.vue:36"]
   ["prev"            [:read "render.clj" "(neighbour model lang variant group :prev prev-pl)"]
                                                                             "components/PageNav.vue:117"]
   ["next"            [:read "render.clj" "(neighbour model lang variant group :next next-pl)"]
                                                                             "components/PageNav.vue:112"]
   ["pageComponent"   [:read "model.clj" "[:front-matter :pageComponent]"]   "components/Page.vue:88"]
   ["pageComponent.name" [:read "model.clj" "(get pc :name)"]                "components/Page.vue:88"]
   ["pageComponent.data.path" [:read "model.clj" "[:data :path]"]            "components/Catalogue.vue:103"]
   ["pageComponent.data.key"  [:read "model.clj" "[:data :key]"]             "components/Catalogue.vue:103"]
   ["pageComponent.data.imgUrl" [:read "theme/catalogue.clj" "{:keys [imgUrl description]}"]
                                                                             "components/Catalogue.vue:4"]
   ["pageComponent.data.description" [:read "theme/catalogue.clj" "{:keys [imgUrl description]}"]
                                                                             "components/Catalogue.vue:7"]
   ["categoriesPage"  [:read "pages.clj" "\"categoriesPage\""]               "layouts/Layout.vue:42"]
   ["tagsPage"        [:read "pages.clj" "\"tagsPage\""]                     "layouts/Layout.vue:45"]
   ["archivesPage"    [:read "pages.clj" "\"archivesPage\""]                 "layouts/Layout.vue:48"]
   ["heroImage"       [:read "theme/identity.clj" "{:keys [heroImage heroAlt tagline actionText actionLink bannerBg]} home-fm"]
                                                                             "components/Home.vue:17"]
   ["heroAlt"         [:read "theme/identity.clj" "{:keys [heroImage heroAlt"] "components/Home.vue:19"]
   ["heroText"        [:read "theme/identity.clj" "(:heroText home-fm)"]     "components/Home.vue:21"]
   ["tagline"         [:read "theme/identity.clj" "heroAlt tagline actionText"] "components/Home.vue:24"]
   ["actionText"      [:read "theme/identity.clj" "tagline actionText actionLink"] "components/Home.vue:27"]
   ["actionLink"      [:read "theme/identity.clj" "actionText actionLink bannerBg]"] "components/Home.vue:27"]
   ["features"        [:read "theme/home.clj" "(:features home-fm)"]         "components/Home.vue:36"]
   ["features[].link"    [:read "theme/home.clj" "(:link f)"]                "components/Home.vue:39"]
   ["features[].imgUrl"  [:read "theme/home.clj" "(:imgUrl f)"]              "components/Home.vue:42"]
   ["features[].title"   [:read "theme/home.clj" "(:title f)"]               "components/Home.vue:46"]
   ["features[].details" [:read "theme/home.clj" "(:details f)"]             "components/Home.vue:47"]
   ["postList"        [:read "theme/home.clj" "(:postList home-fm)"]         "components/Home.vue:117"]
   ["simplePostListLength" [:read "theme/home.clj" "(:simplePostListLength home-fm)"] "components/Home.vue:118"]
   ["hideRightBar"    [:read "theme/home.clj" "(:hideRightBar home-fm)"]     "components/Home.vue:141"]
   ["bannerBg"        [:read "theme/identity.clj" "(:bannerBg home-fm)"]     "components/Home.vue:217"]
   ["navbar"          [:deferred "`navbar: false`"]                          "layouts/Layout.vue:162"]
   ["search"          [:deferred "`search: false`"]                          "components/Navbar.vue:34"]
   ;; a value, not a key: vdoing's `sidebar: 'auto'` (util/index.js:132-137)
   ["sidebar: 'auto'" [:removed "`sidebar: 'auto'`"]                         "util/index.js:132"]])

(defn- design-section
  "DESIGN.md §8's text, between its heading and §9's."
  []
  (let [d (slurp "DESIGN.md")]
    (subs d (str/index-of d "## 8. Implementation plan") (str/index-of d "## 9. Decision points"))))

(defn- known-at?
  "Is the kebab key at the end of `path` known (`:ok`) at its parent?"
  [path]
  (let [parent (vec (butlast path)) k (last path)]
    (if (= [:site :author] parent)
      (contains? config/author-keys k)
      (= :ok (get (#'config/entry-for parent) k)))))

(deftest every-vdoing-theme-key-is-implemented-deferred-or-removed
  (doseq [[k what cite] vdoing-theme-keys
          :let [[target status] (get config/vdoing-keys k)
                at (str k " (" cite ")")]]
    (cond
      (vector? what)
      (if (= :in (first what))
        (is (#{:implicit :deferred :dropped}
             (some (fn [[k2 w]] (when (= k2 (second what)) w)) vdoing-theme-keys))
            (str at ": inside a key that is classified"))
        (do (is (known-at? what) (str at " is known at " (pr-str what)))
            (when status
              (is (= :ok status) (str at ": vdoing-keys says " (pr-str status)))
              (is (= target (vec (take-last (count target) what)))
                  (str at ": vdoing-keys maps it to " (pr-str target))))))

      (= :unread what)
      (is (nil? status) (str at ": unread by vdoing, and not classified"))

      :else
      (is (= what status) (str at ": vdoing-keys says " (pr-str status)))))
  (testing "nothing is :planned any more, anywhere"
    (is (not-any? #(= :planned (second %)) (vals config/vdoing-keys)))
    (is (= [] (for [[p m] config/known-keys [k st] m :when (not= :ok st)] [p k st]))))
  (testing "every :deferred or :dropped key is in DESIGN.md §8's deferred or removed list"
    (let [s8 (str/lower-case (design-section))]
      (doseq [[k what] vdoing-theme-keys
              :when (#{:deferred :dropped} what)]
        (is (str/includes? s8 (str/lower-case k)) k)))))

(deftest every-vdoing-front-matter-key-is-read-deferred-or-removed
  (let [s8 (design-section)]
    (doseq [[k [how a b] cite] vdoing-front-matter-keys
            :let [at (str k " (" cite ")")]]
      (case how
        :read     (is (str/includes? (slurp (str "src/clogem/" a)) b)
                      (str at ": src/clogem/" a " reads it with " b))
        (:deferred :removed)
        (is (str/includes? s8 a) (str at ": DESIGN.md §8 lists " a))))))

(defn- doctor-warnings
  "`bb doctor` on a one-article site whose site.edn is `edn`: its warnings,
  as {message hint}."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-rel-doctor"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str (merge {:site {:title "S" :url "https://s.example"}} edn)))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.a.md") (article "A" "aaaaa9"))
      (let [r (atom nil)
            w (java.io.StringWriter.)]
        (binding [*out* w *err* w]
          (reset! r (cli/doctor {:site-dir (str dir)})))
        (is (empty? (:errors @r)))
        (into {} (map (juxt :message :hint)) (:warnings @r)))
      (finally (fs/delete-tree dir)))))

(deftest doctor-names-every-deferred-implicit-and-removed-key
  (let [statused (for [[k what] vdoing-theme-keys
                       :when (#{:implicit :deferred :dropped} what)]
                   [k what])
        children (group-by (comp second second)
                           (filter (fn [[_ w]] (and (vector? w) (= :in (first w)))) vdoing-theme-keys))
        ;; each key at the top level, where a pasted themeConfig would put
        ;; it; a parent holds its covered children
        edn      (into {} (for [[k] statused]
                            [(keyword k) (if-let [cs (get children k)]
                                           (into {} (for [[c] cs] [(keyword c) "x"]))
                                           "x")]))
        ws       (doctor-warnings edn)
        says     {:implicit " is not needed: clogem-press "
                  :deferred " is a vdoing option clogem-press defers past v1 (DESIGN.md §8)"
                  :dropped  " is a vdoing option clogem-press does not have (dropped in Phase 4, DESIGN.md §8)"}]
    (is (= 16 (count statused)) (pr-str statused))
    (doseq [[k what] statused]
      (is (= 1 (count (filter #(str/starts-with? % (str ":" k (says what))) (keys ws))))
          (str k " " what)))
    (testing "a covered key is not walked: its parent's warning is the only one"
      (doseq [[_ cs] children [c] cs]
        (is (not-any? #(str/includes? % (str ":" c " ")) (keys ws)) c)))
    (testing "no other warning"
      (is (= (count statused) (count ws)) (pr-str (keys ws)))))
  (testing "NavItem's target and rel, which vdoing never reads: doctor names them as dropped"
    (let [ws (doctor-warnings {:nav [{:text "A" :link "/a/" :target "_blank" :rel "noopener"}]})]
      (is (= #{":nav 0 :target is a vdoing option clogem-press does not have (dropped in Phase 4, DESIGN.md §8); it is ignored."
               ":nav 0 :rel is a vdoing option clogem-press does not have (dropped in Phase 4, DESIGN.md §8); it is ignored."}
             (set (keys ws)))))))

;; ---------------------------------------------------------------------------
;; Item 2 — the version

(deftest the-version-is-0-3-0
  (testing "version.edn, and what a build says it was made with"
    (is (= "0.3.0" (config/generator-version)))
    (let [dir (fs/create-temp-dir {:prefix "clogem-rel-ver"})]
      (try
        (spit (fs/file dir "site.edn") (pr-str {:site {:title "S" :url "https://s.example"}
                                                :search {:provider :none} :highlight {:provider :none}}))
        (fs/create-dirs (fs/path dir "content" "01.Guide"))
        (spit (fs/file dir "content" "01.Guide" "01.a.md") (article "A" "aaaaa8"))
        (diag/collecting
         (let [cfg (config/load-config (str dir) nil {:content {:write-front-matter false}})]
           (render/build! cfg (cli/analyse cfg))))
        (is (str/includes? (slurp (fs/file dir "dist" "pages" "aaaaa8" "index.html"))
                           "<meta content=\"clogem-press 0.3.0\" name=\"generator\" />"))
        (is (str/includes? (slurp (fs/file dir "dist" "feed.xml"))
                           "<generator version=\"0.3.0\">clogem-press</generator>"))
        (finally (fs/delete-tree dir))))))
