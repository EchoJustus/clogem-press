;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.page
  "Page templates: article, home, and the redirect stub."
  (:require [clojure.string :as str]
            [clogem.i18n :as i18n]
            [clogem.theme.layout :as layout]))

(defn article
  [{:keys [cfg lang group variant] :as ctx} content-hiccup]
  (let [fallback? (not= lang (:lang variant))]
    (layout/document
     (assoc ctx :title (:title variant))
     (layout/navbar ctx)
     (layout/shell
      ctx
      [:main.clogem-main
       (layout/breadcrumbs ctx group)
       [:article.clogem-article
        [:h1 (:title variant) (layout/title-tag variant)]
        (layout/article-info ctx group variant)
        (when (and fallback? (get-in cfg [:i18n :show-fallback-notice]))
          [:p.clogem-notice
           (i18n/tr ctx :page/fallback-notice
                    {:lang (get-in cfg [:langs :locales (:lang variant) :label])})])
        (layout/variant-bar ctx)
        [:div.clogem-content content-hiccup]]
       (layout/prev-next ctx)])
     (layout/footer ctx))))

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
