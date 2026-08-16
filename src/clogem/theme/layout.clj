;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.layout
  "The minimal Phase 1 theme: navbar, flat sidebar, article page.

  Phase 2 replaces the flat sidebar with a collapsible tree, and Phase 4 brings
  the full CSS theme (four colour modes, card/line styles). What is here is
  deliberately the smallest thing that renders a navigable five-language site,
  because §8's Phase 1 exit criterion is navigability, not polish.

  Rule 2 of §6.4 is enforced structurally: chrome language always equals the
  page's content language, and `<html lang>` matches. There is no client-side
  string swapping anywhere in this namespace."
  (:require [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.util :as u]))

(defn- asset [ctx path]
  (u/clean-url (str (config/base-path (:cfg ctx)) "/clogem/" path)))

(defn- css-href [ctx path]
  ;; clean-url would add a trailing slash to a file path, so build it directly
  (str/replace (str (config/base-path (:cfg ctx)) "/clogem/" path) #"/{2,}" "/"))

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
                                (u/clean-url (str (config/base-path cfg)
                                                  (when-not (= l (config/default-lang cfg))
                                                    (str "/" (name l))))))]]
               [:li (if current?
                      [:span.is-current {:lang (config/html-lang cfg l)
                                         :aria-current "true"}
                       (get-in locales [l :label])]
                      [:a {:href href :lang (config/html-lang cfg l)
                           :hreflang (config/html-lang cfg l)}
                       (get-in locales [l :label])])]))])))

(defn navbar
  [{:keys [cfg lang] :as ctx}]
  (let [home (u/clean-url (str (config/base-path cfg)
                               (when-not (= lang (config/default-lang cfg))
                                 (str "/" (name lang)))))]
    [:header.clogem-navbar
     [:a.clogem-navbar__brand {:href home}
      (i18n/resolve-str ctx (get-in cfg [:site :title]))]
     (into [:nav.clogem-navbar__nav]
           (for [{:keys [text link]} (:nav cfg)]
             [:a {:href (u/clean-url (str (config/base-path cfg) link))}
              (i18n/resolve-str ctx text)]))
     (lang-switcher ctx)]))

(defn sidebar
  "Flat sidebar (tree rendering is Phase 2). One entry per identity group, with
  the title of the reader's own language variant when the article has one, else
  the primary's — marked with the fallback notice (§6.8)."
  [{:keys [cfg lang model group] :as ctx}]
  (let [current (:permalink group)]
    [:aside.clogem-sidebar
     (into [:nav {:aria-label "Sidebar"}]
           (for [[top groups] (:sidebar model)
                 :when (seq groups)]
             [:section.clogem-sidebar__group
              [:h2 (-> (str top) (str/replace #"^\d+\." ""))]
              (into [:ul]
                    (for [g groups
                          :let [v-lang (model/best-variant g lang)
                                v      (get-in g [:variants v-lang])
                                fallback? (not= v-lang lang)]]
                      [:li {:class (when (= current (:permalink g)) "is-active")}
                       [:a {:href (model/variant-url cfg g v-lang)
                            :lang (config/html-lang cfg v-lang)}
                        (:title v)]
                       (when (and fallback? (get-in cfg [:i18n :show-fallback-notice]))
                         [:span.clogem-fallback
                          {:title (i18n/tr ctx :page/fallback-notice
                                           {:lang (get-in cfg [:langs :locales v-lang :label])})}
                          (name v-lang)])]))]))]))

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
      [:link {:rel "stylesheet" :href (css-href ctx "css/theme.css")}]]
     (into [:body {:class (str "theme-mode-" (name (get-in cfg [:theme :default-mode] :auto))
                               " theme-style-" (name (get-in cfg [:theme :page-style] :card))
                               " lang-" (name lang))}]
           body)]))
