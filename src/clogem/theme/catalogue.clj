;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.catalogue
  "Catalogue pages — vdoing's 目录页 (DESIGN.md §1.1, D-P2-8).

  Front matter `pageComponent: {name: Catalogue, data: {path, imgUrl,
  description}}` renders a card grid of the directory subtree at `data.path`
  INSTEAD of the markdown body: a header with the image and description, then
  one card per child directory listing its articles per §6.8, nested to the
  tree's depth. Articles directly under the target directory get a card of
  their own, titled with the directory. Every row is an identity group, so
  a three-variant article is one row."
  (:require [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.theme.layout :as layout]))

(defn- rows
  "The article rows of a directory's own leaves, in sidebar order."
  [{:keys [model] :as ctx} node]
  (into [:ul.clogem-catalogue__list]
        (for [c (:children node)
              :when (= :article (:kind c))]
          (layout/article-row ctx (get-in model [:articles (:permalink c)])))))

(defn- subtree
  "A directory inside a card: its heading, its rows, and its own
  subdirectories recursively."
  [ctx node]
  (into [:div.clogem-catalogue__sub
         [:h4 (:title node)]
         (rows ctx node)]
        (for [c (:children node) :when (= :dir (:kind c))]
          (subtree ctx c))))

(defn- card
  [{:keys [model] :as ctx} node]
  (let [n (count (model/tree-leaves node))]
    (into [:section.clogem-catalogue__card
           [:h3 (:title node)
            [:span.clogem-bar__count (i18n/tr ctx :index/count {:n n})]]
           (rows ctx node)]
          (for [c (:children node) :when (= :dir (:kind c))]
            (subtree ctx c)))))

(defn catalogue
  "`ctx` carries :node (the resolved directory node) and :page-component."
  [{:keys [cfg variant group node page-component rewrite-href] :as ctx}]
  (let [{:keys [imgUrl description]} (:data page-component)
        direct (filter #(= :article (:kind %)) (:children node))
        dirs   (filter #(= :dir (:kind %)) (:children node))]
    (layout/document
     (assoc ctx :title (:title variant))
     (layout/navbar ctx)
     [:div.clogem-shell.clogem-shell--single
      [:main.clogem-main
       (layout/breadcrumbs ctx group)
       [:article.clogem-article.clogem-catalogue
        [:header.clogem-catalogue__header
         (when-let [img (some-> imgUrl str not-empty)]
           [:img.clogem-catalogue__img {:src (rewrite-href img) :alt ""}])
         [:h1 (:title variant) (layout/title-tag variant)]
         (when-let [d (some-> description str not-empty)]
           [:p.clogem-catalogue__desc (i18n/resolve-str ctx d)])]
        (into [:div.clogem-catalogue__grid]
              (concat
               (when (seq direct)
                 [[:section.clogem-catalogue__card
                   [:h3 (:title node)]
                   (rows ctx node)]])
               (map #(card ctx %) dirs)))]
       ;; a catalogue page is a leaf of its directory's tree like any other,
       ;; so it has neighbours (D-P2-7: not skipped for being a non-article)
       (layout/prev-next ctx)]]
     (layout/footer ctx))))
