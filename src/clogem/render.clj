;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.render
  "Page-map assembly and export (DESIGN.md §5.2 step 5).

  A site is a map from URL path to a thunk returning hiccup — stasis's shape,
  and the reason the whole pipeline stays testable as pure data. Export walks
  that map, renders each entry, and writes `<uri>index.html`.

  The exporter **copies** assets, never symlinks. v2.1 (V15) established that
  this is a house rule rather than a platform constraint —
  `actions/upload-pages-artifact` tars with `--dereference`, so a symlink would
  be materialized rather than rejected. It is still the right default: a
  dereferenced symlink ships its target's full bytes against the 1 GB published
  site cap, which is how a 58 MB tool binary ends up in a deploy by accident."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.frontmatter :as fm]
            [clogem.i18n :as i18n]
            [clogem.markdown :as markdown]
            [clogem.model :as model]
            [clogem.theme.page :as page]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Link context

(defn link-context
  "What `clogem.markdown/rewrite-href` needs to resolve a link: the article
  table, a path index for `.md` links, and the reader's language."
  [{:keys [cfg articles] :as _model} model lang from-path]
  {:cfg cfg
   :articles articles
   :lang lang
   :from-path from-path
   :by-rel-path (:by-rel-path model)
   :url-for (fn [group l]
              (model/variant-url cfg group (model/best-variant group l)))})

(defn- rel-path-index
  "Index every variant's path — both its full repo-relative path and its bare
  filename — so a relative `.md` link resolves the way an author expects."
  [model]
  (reduce
   (fn [idx [_pl g]]
     (reduce (fn [idx [_lang v]]
               (let [rel  (u/lower (str (:rel-path v)))
                     base (u/lower (str (fs/file-name (:path v))))]
                 (-> idx
                     (assoc rel g)
                     (assoc (str "./" base) g)
                     (update base #(or % g)))))
             idx (:variants g)))
   {} (:articles model)))

;; ---------------------------------------------------------------------------
;; Page map

(defn page-map
  "{uri → (fn [] hiccup)} for every emitted document.

  Which URIs exist follows §6.3 exactly:
    - the primary variant at the bare identity URL (or a redirect stub there
      when :prefix-default? is true — D-10, redirected not broken)
    - every non-primary variant under /<lang>/
    - one home page per language"
  [model]
  (let [{:keys [cfg articles]} model
        strings (i18n/load-strings cfg)
        model   (assoc model :by-rel-path (rel-path-index model))
        prefix-all? (get-in cfg [:i18n :prefix-default?])
        ctx-for (fn [lang group variant]
                  {:cfg cfg :lang lang :strings strings :model model
                   :group group :variant variant :dev? (:clogem/dev? cfg)})]
    (into
     {}
     (concat
      ;; articles
      (for [[_pl group] articles
            [lang variant] (:variants group)]
        [(model/variant-url cfg group lang)
         (fn []
           (let [ctx (ctx-for lang group (assoc variant :lang lang))
                 lc  (link-context model model lang (:rel-path variant))
                 body (markdown/render (:body variant) lc)]
             (page/article ctx body)))])

      ;; redirect stubs at the bare identity URL when every variant is prefixed
      (when prefix-all?
        (for [[_pl group] articles]
          [(model/identity-url cfg group)
           (fn [] (page/redirect-stub cfg (model/variant-url cfg group (:primary group))))]))

      ;; one home per language
      (for [lang (config/lang-keys cfg)]
        [(u/clean-url (str (config/base-path cfg)
                           (when-not (= lang (config/default-lang cfg)) (str "/" (name lang)))))
         (fn []
           (let [ctx (ctx-for lang nil nil)
                 index-md (fs/path (config/content-dir cfg)
                                   (if (= lang (config/default-lang cfg))
                                     "index.md"
                                     (str "index." (name lang) ".md")))
                 body (when (fs/exists? index-md)
                        (let [f (slurp (fs/file index-md))
                              parts (fm/split-file f)]
                          (markdown/render (:body parts)
                                           (link-context model model lang (str index-md)))))]
             (page/home ctx body)))])))))

;; ---------------------------------------------------------------------------
;; Export

(def doctype
  ;; hiccup.page is not one of babashka's built-in hiccup namespaces (only
  ;; hiccup2.core and hiccup.util are), so the doctype is a literal.
  "<!DOCTYPE html>\n")

(defn- uri->file
  [out uri]
  (let [rel (-> (str uri) (str/replace #"^/" "") (str/replace #"/$" ""))]
    (if (str/blank? rel)
      (fs/path out "index.html")
      (fs/path out rel "index.html"))))

(defn export-pages!
  [cfg pages]
  (let [out (config/out-dir cfg)]
    (doseq [[uri render-fn] (sort-by key pages)]
      (let [f (uri->file out uri)]
        (fs/create-dirs (fs/parent f))
        (spit (fs/file f) (str doctype (h/html (render-fn))))))
    (count pages)))

(defn copy-tree!
  "Copy a directory tree, never symlink (see the ns docstring)."
  [from to]
  (when (fs/directory? from)
    (fs/create-dirs to)
    (doseq [p (fs/glob from "**")
            :when (fs/regular-file? p)]
      (let [target (fs/path to (fs/relativize from p))]
        (fs/create-dirs (fs/parent target))
        (fs/copy p target {:replace-existing true})))
    true))

(defn theme-resource-dir
  "Locate the theme's static resources on the classpath, so they are found
  regardless of the working directory — which matters because the generator is
  normally invoked from the *site's* directory via `bb --config`."
  []
  (some-> (io/resource "clogem/theme/resources/css/theme.css")
          .toURI fs/path fs/parent fs/parent))

(defn export-assets!
  [cfg]
  (let [out (config/out-dir cfg)]
    (when-let [themed (theme-resource-dir)]
      ;; the i18n EDN maps are build-time inputs, not site output
      (doseq [sub ["css" "js" "icons" "fonts"]
              :let [from (fs/path themed sub)]
              :when (fs/directory? from)]
        (copy-tree! from (fs/path out "clogem" sub))))
    (let [user (config/assets-dir cfg)]
      (when (fs/directory? user)
        (copy-tree! user (fs/path out "assets"))))))

(defn build!
  "Render and export. Returns a summary map."
  [cfg model]
  (let [out (config/out-dir cfg)
        pages (page-map model)]
    (fs/create-dirs out)
    (let [n (export-pages! cfg pages)]
      (export-assets! cfg)
      {:pages n
       :articles (count (:articles model))
       :variants (reduce + (map #(count (:variants %)) (vals (:articles model))))
       :out (str out)})))
