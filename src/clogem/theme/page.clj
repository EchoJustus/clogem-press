;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.page
  "Page templates: article, home, and the redirect stub."
  (:require [clogem.config :as config]
            [clogem.theme.layout :as layout]))

(defn article
  [{:keys [group variant] :as ctx} content-hiccup]
  ;; No in-page fallback notice: every article page IS the variant in its own
  ;; language, so there was never a case to show one (the old `fallback?`
  ;; was always false). An untranslated article is flagged where the reader
  ;; meets it — on the switcher entry (layout/lang-switcher) and on index
  ;; rows (layout/fallback-badge).
  (layout/document
     (assoc ctx :title (:title variant))
     (layout/navbar ctx)
     (layout/shell
      ctx
      [:main.clogem-main
       (layout/breadcrumbs ctx group)
       ;; D-P3-9: only an article's own body is indexed; once any page
       ;; carries data-pagefind-body, Pagefind skips every page that does
       ;; not, so homes, index and pagination pages drop out by themselves
       [:article.clogem-article
        (layout/pagefind ctx :data-pagefind-body)
        [:h1 (layout/result-title ctx (:title variant)) (layout/title-tag ctx variant)]
        (layout/article-info ctx group variant)
        (layout/variant-bar ctx)
        [:div.clogem-content (layout/search-terms ctx group) content-hiccup]]
       (layout/prev-next ctx)
       (layout/comments ctx)])
     (layout/footer ctx)))

(defn redirect-stub
  "D-10 / D-15: a retired or de-canonicalized URL redirects rather than 404s.
  `<meta http-equiv=\"refresh\">` is all a static host can offer — GitHub Pages
  does not allow custom headers.

  No `rel=canonical` (D-P3-2): the stub is `noindex`, and a canonical beside
  noindex is the conflicting signal Google's guidance says not to send; the
  refresh is the redirect. A stub is never in an hreflang set or the sitemap,
  though under `:prefix-default? true` it IS the target of every set's
  `x-default`, which is the redirector role Google describes for it.
  `<html lang>` is the site default language's, not a hard-coded `en`."
  [cfg target]
  [:html {:lang (config/html-lang cfg (config/default-lang cfg))}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:http-equiv "refresh" :content (str "0; url=" target)}]
    [:meta {:name "robots" :content "noindex"}]
    [:title "Redirecting…"]]
   [:body [:p "Redirecting to " [:a {:href target} target] "…"]]])
