;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.page
  "Page templates: article, home, and the redirect stub."
  (:require [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.markdown :as markdown]
            [clogem.model :as model]
            [clogem.theme.layout :as layout]
            [clogem.util :as u]))

(defn article
  [{:keys [cfg lang group variant] :as ctx} content-hiccup]
  (let [fallback? (not= lang (:lang variant))]
    (layout/document
     (assoc ctx :title (:title variant))
     (layout/navbar ctx)
     [:div.clogem-shell
      (layout/sidebar ctx)
      [:main.clogem-main
       [:article.clogem-article
        [:h1 (:title variant)]
        [:p.clogem-meta
         (when-let [cats (seq (:categories group))]
           [:span.clogem-meta__cats (str/join " / " cats)])
         (when-let [d (:date group)] [:span.clogem-meta__date (str d)])
         (when-let [tags (seq (:tags group))]
           [:span.clogem-meta__tags (str/join ", " (map str tags))])]
        (when (and fallback? (get-in cfg [:i18n :show-fallback-notice]))
          [:p.clogem-notice
           (i18n/tr ctx :page/fallback-notice
                    {:lang (get-in cfg [:langs :locales (:lang variant) :label])})])
        (layout/variant-bar ctx)
        [:div.clogem-content content-hiccup]]]]
     [:footer.clogem-footer
      [:p (str "clogem-press " (:clogem/version cfg))]])))

(defn home
  [{:keys [cfg lang model] :as ctx} content-hiccup]
  (layout/page
   (assoc ctx :title (i18n/resolve-str ctx (get-in cfg [:site :title])))
   [:div.clogem-content (or content-hiccup
                            [:p (i18n/resolve-str ctx (get-in cfg [:site :description]))])]
   [:h2 (i18n/tr ctx :index/recent)]
   (into [:ul.clogem-list]
         (for [pl (take 10 (:posts model))
               :let [g (get-in model [:articles pl])]]
           (layout/article-row ctx g)))))

(defn redirect-stub
  "D-10 / D-15: a retired or de-canonicalized URL redirects rather than 404s.
  `<meta http-equiv=\"refresh\">` plus `rel=canonical`, which is all a static
  host can offer — GitHub Pages does not allow custom headers."
  [cfg target]
  (let [abs (str (get-in cfg [:site :url]) target)]
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:http-equiv "refresh" :content (str "0; url=" target)}]
      [:link {:rel "canonical" :href (if (str/blank? (get-in cfg [:site :url])) target abs)}]
      [:meta {:name "robots" :content "noindex"}]
      [:title "Redirecting…"]]
     [:body [:p "Redirecting to " [:a {:href target} target] "…"]]]))
