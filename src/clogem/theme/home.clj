;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.home
  "The homepage (DESIGN.md §1.2 blog identity, §8 Phase 2 line 4, D-P2-5).

  Homepage front matter, read with `fm/read-file` from `index.md` (or
  `index.<lang>.md`), drives the list:

    postList: detailed | simple | none   (default detailed)
    simplePostListLength: N              (simple mode cap, default 10)
    hideRightBar: true                   (no right column)
    features: [{title details link imgUrl}]

  The list is sticky ++ (posts minus sticky), from the model's two id lists,
  so a pinned article never appears twice and the order is the same in every
  language. Detailed rows carry an excerpt — the content before `<!-- more -->`,
  none without the marker — taken from the reader's own variant when the
  article has one, else the primary's."
  (:require [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.theme.identity :as identity]
            [clogem.theme.layout :as layout]
            [clogem.theme.parity :as parity]
            [clogem.util :as u]))

(defn post-list-mode
  [home-fm]
  (let [m (u/lower (str (or (:postList home-fm) "detailed")))]
    (if (#{"detailed" "simple" "none"} m) (keyword m) :detailed)))

(defn home-ids
  "sticky ++ (posts minus sticky) — D-P2-4."
  [{:keys [sticky posts]}]
  (let [pinned (set sticky)]
    (vec (concat sticky (remove pinned posts)))))

(defn- features
  [ctx home-fm]
  (when-let [fs (seq (:features home-fm))]
    (into [:div.clogem-features]
          (for [f fs
                :let [title (i18n/resolve-str ctx (or (:title f) (get f "title")))
                      details (i18n/resolve-str ctx (or (:details f) (get f "details")))
                      link  (or (:link f) (get f "link"))
                      img   (or (:imgUrl f) (get f "imgUrl"))
                      href  (when link ((:rewrite-href ctx identity) link))]]
            [:div.clogem-feature
             (when img [:img {:src ((:rewrite-href ctx identity) img) :alt ""}])
             [:h3 (if href [:a {:href href} title] title)]
             (when details [:p details])]))))

(defn- detailed-row
  [{:keys [cfg model] :as ctx} pl]
  (let [g (get-in model [:articles pl])
        {:keys [href title lang fallback?]} (layout/article-link ctx g)
        v  (get-in g [:variants lang])]
    [:article.clogem-post-card {:class (when (some #{pl} (:sticky model)) "is-sticky")}
     [:h2.clogem-post-card__title
      (when (some #{pl} (:sticky model)) [:span.clogem-sticky (i18n/tr ctx :index/sticky)])
      [:a {:href href :lang (config/html-lang cfg lang)} title]
      (layout/title-tag ctx v)
      (when fallback? (layout/fallback-badge ctx lang))]
     ;; marker-only (D-P2-5 as amended): no `<!-- more -->`, no excerpt —
     ;; and then nothing to read "more" of, so no link either
     (let [x ((:excerpt ctx) g lang)]
       (list
        (when x [:div.clogem-post-card__excerpt {:lang (config/html-lang cfg lang)} x])
        (layout/article-info ctx g v)
        (when x [:p.clogem-post-card__more [:a {:href href} (i18n/tr ctx :index/read-more)]])))]))

(defn- post-list
  [{:keys [model] :as ctx} mode home-fm ids page total page-url]
  (case mode
    :none nil
    :simple (let [n (or (some-> (:simplePostListLength home-fm) str parse-long) 10)]
              (list
               (into [:ul.clogem-list.clogem-list--simple]
                     (map #(layout/article-row ctx (get-in model [:articles %])) (take n ids)))
               ;; Task E2: vdoing's simple list is an UpdateArticle, with
               ;; its "more" (Home.vue)
               (when (seq ids) (parity/more-article ctx))))
    (list
     (into [:div.clogem-post-list] (map #(detailed-row ctx %) ids))
     (layout/pagination ctx page total page-url))))

(defn- right-bar
  "vdoing's homepage sidebar (Home.vue), in D-P4-13's order: the blogger
  card (P4-E1a), the update bar (date-desc — \"recently updated\" is the
  date until git dates arrive; \"More\" goes where `:theme :update-bar
  :more-article` says, Task E2), categories and tags with counts, then
  E1b's `home-sidebar-b`. Under `postList: simple` the main column already
  is the recent list — vdoing's UpdateArticle there, with its own \"More\"
  — so there is no update bar."
  [{:keys [cfg lang model index-paths] :as ctx} mode]
  [:aside.clogem-home-right
   (identity/blogger-card ctx)
   (let [recent (when (not= :simple mode) (take 10 (:posts model)))]
     (when (seq recent)
       [:section.clogem-updates
        [:h2 (i18n/tr ctx :index/updates)]
        (into [:ul.clogem-list] (map #(layout/article-row ctx (get-in model [:articles %])) recent))
        (parity/more-article ctx)]))
   ;; a card only when there is something to list: a site whose articles
   ;; all carry `tags: []` gets no empty Tags box
   (when-let [root (and (seq (:categories model)) (:categories index-paths))]
     [:section.clogem-home-cats
      [:h2 [:a {:href (layout/href ctx (model/site-url cfg lang root))} (i18n/tr ctx :index/categories)]]
      (into [:ul.clogem-bar]
            (for [[c ids] (:categories model)]
              [:li [:a {:href (layout/href ctx (layout/index-href ctx :categories c))} (layout/category-label ctx c)]
               [:span.clogem-bar__count (count ids)]]))])
   (when-let [root (and (seq (:tags model)) (:tags index-paths))]
     [:section.clogem-home-tags
      [:h2 [:a {:href (layout/href ctx (model/site-url cfg lang root))} (i18n/tr ctx :index/tags)]]
      (into [:ul.clogem-bar]
            (for [[t ids] (:tags model)]
              [:li [:a {:href (layout/href ctx (layout/index-href ctx :tags t))} (str t)]
               [:span.clogem-bar__count (count ids)]]))])])

(defn home
  "`ctx` carries :home-fm, :body (hiccup or nil), :ids (this page's slice),
  :page, :total, :page-url, :excerpt (fn [group lang] → hiccup)."
  [{:keys [cfg home-fm body ids page total page-url] :as ctx}]
  (let [mode (post-list-mode home-fm)
        hide-right? (true? (:hideRightBar home-fm))
        ;; P4-E1a: one h1 per page — the banner's heroText is the h1 when
        ;; the main column has none, and then the fallback title goes
        ;; a raw-HTML `<h1` in the body counts too (P4-E1a.1 item 8c), but
        ;; not one inside an HTML comment (P4-E1a.2 item 3)
        body-h1? (some #(or (and (vector? %) (keyword? (first %))
                                 (re-matches #"h1(?:[.#].*)?" (name (first %))))
                            (and (= "hiccup.util.RawString" (some-> % class .getName))
                                 (re-find #"(?i)<h1[\s>/]" (str/replace (str %) #"(?s)<!--.*?-->" ""))))
                       (tree-seq sequential? seq body))
        hero-h1? (boolean (and (identity/hero-text ctx) (not body-h1?)))
        content  (or body
                     (when-not hero-h1?
                       (list [:h1 (i18n/resolve-str ctx (get-in cfg [:site :title]))]
                             [:p (i18n/resolve-str ctx (get-in cfg [:site :description]))])))
        ;; P4-B2a.1: post cards sit on the page, so their column is no card
        main [:main.clogem-main {:class (when (and (= :detailed mode) (seq ids)) "clogem-main--posts")}
              ;; a home without a body of its own still has a heading — the
              ;; body's `# Title` is the h1 when there is one
              (when content [:div.clogem-content content])
              (features ctx home-fm)
              (post-list ctx mode home-fm ids page total page-url)]]
    (layout/document
     (assoc ctx :title (i18n/resolve-str ctx (get-in cfg [:site :title]))
                ;; the body and the excerpts: the main column only
                :has-code? (layout/has-code? main))
     (layout/navbar ctx)
     ;; P4-E1a: vdoing's banner, on the first page only
     (identity/hero ctx hero-h1?)
     (into [:div.clogem-shell {:class (if hide-right? "clogem-shell--single" "clogem-shell--home")}
            main]
           (when-not hide-right? [(right-bar ctx mode)]))
     (layout/footer ctx))))
