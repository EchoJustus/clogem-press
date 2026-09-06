;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.indexes
  "The three index systems — `/categories/`, `/tags/`, `/archives/` — rendered
  per language (DESIGN.md §5.2 step 3, §6.8, D-P2-2, D-P2-3).

  Every list here is built from article ids in the model, so an article appears
  exactly once per index by construction (§6.8: dedupe by identity is
  structural, not cosmetic). Row titles and targets follow §6.8: the reader's
  own language variant when the article has one, else the primary with the
  fallback notice. Row ORDER comes from the model and is identical in every
  language.

  vdoing computes categories/tags client-side in Vue; these are plain HTML —
  one static page per category, tag and pagination step, no query strings."
  (:require [clojure.string :as str]
            [clogem.i18n :as i18n]
            [clogem.theme.layout :as layout]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Bars

(defn- bar
  "The filter bar of vdoing's CategoriesBar/TagsBar: every name with its count,
  the current one marked. `href-for` maps a raw name to a site URI."
  [ctx index href-for current label-for]
  (into [:ul.clogem-bar]
        (cons
         [:li {:class (when (nil? current) "is-active")}
          [:a {:href (layout/href ctx (:root-uri ctx))} (i18n/tr ctx :index/all)]
          [:span.clogem-bar__count (count (distinct (mapcat val index)))]]
         (for [[k ids] index]
           [:li {:class (when (= k current) "is-active")}
            [:a {:href (layout/href ctx (href-for k))} (label-for k)]
            [:span.clogem-bar__count (count ids)]]))))

(defn- listing
  "Rows for `ids`, or the empty notice."
  [ctx ids]
  (if (seq ids)
    (into [:ul.clogem-list] (map #(layout/article-row ctx (get-in ctx [:model :articles %])) ids))
    [:p.clogem-empty (i18n/tr ctx :index/empty)]))

(defn- user-body
  "A user-authored `@pages/*.md` body renders above the generated list (D-P2-6)."
  [ctx]
  (when-let [b (:page-body ctx)]
    [:div.clogem-content b]))

;; ---------------------------------------------------------------------------
;; Pages

(defn overview
  "`/categories/` or `/tags/`: bars with counts (D-P2-3)."
  [{:keys [index kind] :as ctx}]
  (let [label-for (if (= kind :categories) #(layout/category-label ctx %) str)]
    (layout/page ctx
                 [:section.clogem-index {:class (str "clogem-index--" (name kind))}
                  [:h1 (:title ctx)]
                  (user-body ctx)
                  (bar ctx index (:href-for ctx) nil label-for)])))

(defn filtered
  "`/categories/<slug>/`, `/tags/<slug>/` and their `/page/N/` continuations."
  [{:keys [index kind current ids page total] :as ctx}]
  (let [label-for (if (= kind :categories) #(layout/category-label ctx %) str)
        heading   (i18n/tr ctx (if (= kind :categories) :index/category-title :index/tag-title)
                           {:name (label-for current)})]
    (layout/page ctx
                 [:section.clogem-index {:class (str "clogem-index--" (name kind))}
                  [:h1 heading]
                  (bar ctx index (:href-for ctx) current label-for)
                  (listing ctx ids)
                  (layout/pagination ctx page total (:page-url ctx))])))

(defn archives
  "`/archives/`: year → month → rows, newest first, no pagination (D-P2-3)."
  [{:keys [archives] :as ctx}]
  (layout/page ctx
               (into [:section.clogem-index.clogem-index--archives
                      [:h1 (:title ctx)]
                      (user-body ctx)]
                     (if (seq archives)
                       (for [[year months] archives]
                         (into [:section.clogem-archive-year [:h2 (str year)]]
                               (for [[month ids] months]
                                 [:section.clogem-archive-month
                                  [:h3 (format "%d-%02d" year month)]
                                  (listing ctx ids)])))
                       [[:p.clogem-empty (i18n/tr ctx :index/empty)]]))))
