;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.layout
  "Theme chrome shared by every page: document shell, navbar, language
  switcher, sidebar, article rows, pagination, footer.

  Rule 2 of §6.4 is enforced structurally: chrome language always equals the
  page's content language, and `<html lang>` matches. There is no client-side
  string swapping anywhere in this namespace.

  Every URL emitted here is built from `clogem.config/base-path` (through
  `clogem.model`'s URL helpers), so `render/uri->file` can strip the base
  again — the site's `:base` belongs in every link and in no part of the file
  layout."
  (:require [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; URLs

(defn asset-href
  "A FILE under the theme's exported assets (css/js). `clean-url` would append
  a slash to a file path, so the base is joined directly."
  [ctx path]
  (str/replace (str (config/base-path (:cfg ctx)) "/clogem/" path) #"/{2,}" "/"))

(defn href
  "A site URI made safe for an href: every path segment percent-encoded, so a
  CJK or Tamil category slug (kept verbatim in the URI and in the directory
  name — D-P2-3) is legal in the attribute."
  [_ctx uri]
  (u/url-encode-path uri))

;; ---------------------------------------------------------------------------
;; Labels

(defn category-label
  "D-12: the raw directory-derived name in every language unless
  `:i18n :category-labels` localizes it. Display only — the key and the URL
  slug stay raw."
  [{:keys [cfg lang]} name]
  (let [labels (get-in cfg [:i18n :category-labels (str name)])]
    (or (cond
          (string? labels) labels
          ;; the §6.5 chain WITHOUT resolve-str's first-available last resort:
          ;; an English page must show "Basics", not the first translation
          ;; that happens to exist
          (map? labels)    (some #(u/blank->nil (str (get labels %)))
                                 (distinct [lang (config/default-lang cfg) :en])))
        (str name))))

(defn article-link
  "§6.8: the reader's own variant when the article has one, else the primary,
  flagged as a fallback."
  [{:keys [cfg lang]} group]
  (let [vl (model/best-variant group lang)
        v  (get-in group [:variants vl])]
    {:href      (model/variant-url cfg group vl)
     :title     (:title v)
     :lang      vl
     :fallback? (not= vl lang)}))

(defn fallback-badge
  "The `:page/fallback-notice` marker on a row whose article has no variant in
  the page's language — gated by :show-fallback-notice."
  [{:keys [cfg] :as ctx} vl]
  (when (get-in cfg [:i18n :show-fallback-notice])
    [:span.clogem-fallback
     {:title (i18n/tr ctx :page/fallback-notice
                      {:lang (get-in cfg [:langs :locales vl :label])})}
     (name vl)]))

(defn article-row
  "One index row: title (linked per §6.8), fallback marker, ISO date."
  [{:keys [cfg] :as ctx} group]
  (let [{:keys [href title lang fallback?]} (article-link ctx group)]
    [:li.clogem-row
     [:a {:href href :lang (config/html-lang cfg lang)} title]
     (when fallback? (fallback-badge ctx lang))
     (when-let [d (u/iso-date (:date group))]
       [:time.clogem-meta__date {:datetime d} d])]))

;; ---------------------------------------------------------------------------
;; Chrome

(defn lang-switcher
  "Navbar language switcher. Every target is a real URL to a real document —
  §6.3's point that the per-article buttons only *look* like a client-side
  toggle. Languages the article does not have are not offered."
  [{:keys [cfg lang group] :as ctx}]
  (let [locales (get-in cfg [:langs :locales])
        available (if group
                    (filter #(contains? (:variants group) %) (config/lang-keys cfg))
                    (config/lang-keys cfg))]
    (when (> (count available) 1)
      [:nav.clogem-langs {:aria-label (i18n/tr ctx :nav/language)}
       [:span.clogem-langs__label (i18n/tr ctx :nav/language)]
       (into [:ul]
             (for [l available
                   :let [current? (= l lang)
                         href (if group
                                (model/variant-url cfg group l)
                                (model/home-url cfg l))]]
               [:li (if current?
                      [:span.is-current {:lang (config/html-lang cfg l)
                                         :aria-current "true"}
                       (get-in locales [l :label])]
                      [:a {:href href :lang (config/html-lang cfg l)
                           :hreflang (config/html-lang cfg l)}
                       (get-in locales [l :label])])]))])))

(defn navbar
  [{:keys [cfg lang] :as ctx}]
  [:header.clogem-navbar
   [:a.clogem-navbar__brand {:href (model/home-url cfg lang)}
    (i18n/resolve-str ctx (get-in cfg [:site :title]))]
   (into [:nav.clogem-navbar__nav]
         (for [{:keys [text link]} (:nav cfg)]
           [:a {:href (u/clean-url (str (config/base-path cfg) link))}
            (i18n/resolve-str ctx text)]))
   (lang-switcher ctx)])

(defn sidebar
  "Flat sidebar (tree rendering is Phase 2). One entry per identity group, with
  the title of the reader's own language variant when the article has one, else
  the primary's — marked with the fallback notice (§6.8)."
  [{:keys [cfg lang model group] :as ctx}]
  (let [current (:permalink group)]
    [:aside.clogem-sidebar
     (into [:nav {:aria-label (i18n/tr ctx :page/sidebar)}]
           (for [[top node] (:sidebar model)
                 :let [groups (map #(get-in model [:articles (:permalink %)])
                                   (model/tree-leaves node))]
                 :when (seq groups)]
             [:section.clogem-sidebar__group
              [:h2 (:title node)]
              (into [:ul]
                    (for [g groups
                          :let [{:keys [href title lang fallback?]} (article-link ctx g)]]
                      [:li {:class (when (= current (:permalink g)) "is-active")}
                       [:a {:href href :lang (config/html-lang cfg lang)} title]
                       (when fallback? (fallback-badge ctx lang))]))]))]))

(defn variant-bar
  "Per-article language buttons — plain links between separate documents."
  [{:keys [cfg lang group] :as ctx}]
  (let [others (->> (config/lang-keys cfg)
                    (filter #(and (not= % lang) (contains? (:variants group) %))))]
    (when (seq others)
      [:p.clogem-variants
       [:span (i18n/tr ctx :page/also-available
                       {:lang (str/join ", " (map #(get-in cfg [:langs :locales % :label]) others))})]
       (into [:span.clogem-variants__links]
             (for [l others]
               [:a {:href (model/variant-url cfg group l)
                    :lang (config/html-lang cfg l)
                    :hreflang (config/html-lang cfg l)}
                (get-in cfg [:langs :locales l :label])]))])))

(defn pagination
  "Previous / `Page n of total` / next. `url-for` maps a page number to its
  site URI; page 1 is the list's root."
  [ctx page total url-for]
  (when (> total 1)
    [:nav.clogem-pagination {:aria-label (i18n/tr ctx :index/page {:n page :total total})}
     (if (> page 1)
       [:a.clogem-pagination__prev {:href (href ctx (url-for (dec page))) :rel "prev"}
        (i18n/tr ctx :index/prev-page)]
       [:span.clogem-pagination__prev.is-disabled (i18n/tr ctx :index/prev-page)])
     [:span.clogem-pagination__page (i18n/tr ctx :index/page {:n page :total total})]
     (if (< page total)
       [:a.clogem-pagination__next {:href (href ctx (url-for (inc page))) :rel "next"}
        (i18n/tr ctx :index/next-page)]
       [:span.clogem-pagination__next.is-disabled (i18n/tr ctx :index/next-page)])]))

(defn footer
  [{:keys [cfg]}]
  [:footer.clogem-footer
   [:p (str "clogem-press " (:clogem/version cfg))]])

;; ---------------------------------------------------------------------------
;; Document

(defn document
  "Wrap body hiccup in a complete HTML document.

  `<html lang>` is the page's own language — which Pagefind reads to pick an
  index, so it is not decoration (§6.4 rule 2)."
  [{:keys [cfg lang title] :as ctx} & body]
  (let [loc (config/locale cfg lang)]
    [:html {:lang (config/html-lang cfg lang)
            :dir  (name (or (:dir loc) :ltr))}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:meta {:name "generator" :content (str "clogem-press " (:clogem/version cfg))}]
      [:title (str title
                   (when-let [st (i18n/resolve-str ctx (get-in cfg [:site :title]))]
                     (when (not= st title) (str " · " st))))]
      [:link {:rel "stylesheet" :href (asset-href ctx "css/theme.css")}]]
     (into [:body {:class (str "theme-mode-" (name (get-in cfg [:theme :default-mode] :auto))
                               " theme-style-" (name (get-in cfg [:theme :page-style] :card))
                               " lang-" (name lang)
                               (when-let [k (:page-kind ctx)] (str " page-" (name k))))}]
           body)]))

(defn page
  "A page with no sidebar tree — home, index, catalogue and paginated pages
  show none (D-P2-1): navbar, a single-column shell, footer."
  [ctx & main]
  (document ctx
            (navbar ctx)
            [:div.clogem-shell.clogem-shell--single
             (into [:main.clogem-main] main)]
            (footer ctx)))
