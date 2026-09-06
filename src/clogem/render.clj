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
            [clogem.theme.home :as home]
            [clogem.theme.indexes :as indexes]
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
;; Files outside the numbered tree: index.md and @pages/

(defn localized-file
  "`content/<rel>.<lang>.md` when it exists, else `content/<rel>.md`, else nil —
  the convention `index.md` / `index.zh-Hans.md` and `@pages/*.md` share.
  The four-arity form returns [path own?], where `own?` says the file is the
  language's OWN (or the site default's, on the default language) rather than
  the fallback."
  ([cfg rel lang]
   (first (localized-file cfg rel lang true)))
  ([cfg rel lang _with-flag]
   (let [dir (config/content-dir cfg)
         l   (fs/path dir (str rel "." (name lang) ".md"))
         d   (fs/path dir (str rel ".md"))]
     (cond (fs/exists? l) [l true]
           (fs/exists? d) [d (= lang (config/default-lang cfg))]
           :else nil))))

(def index-kinds
  "The three `@pages/` index systems (§1.1). `:toggle` is the `:content` key
  that switches each off; `:default-path` is the vdoing permalink, which the
  `@pages/` file's own `permalink:` may override."
  [{:kind :categories :toggle :category :flag :categoriesPage :file "@pages/categoriesPage" :default-path "/categories/"}
   {:kind :tags       :toggle :tag      :flag :tagsPage       :file "@pages/tagsPage"       :default-path "/tags/"}
   {:kind :archives   :toggle :archive  :flag :archivesPage   :file "@pages/archivesPage"   :default-path "/archives/"}])

(defn enabled-index-kinds
  [cfg]
  (filter #(get-in cfg [:content (:toggle %)] true) index-kinds))

(defn index-paths
  "{kind → site-relative root path} for every enabled index, read once per
  build: the `@pages/` file's `permalink:` when it has one, else the default."
  [cfg]
  (into {}
        (for [{:keys [kind file default-path]} (enabled-index-kinds cfg)
              :let [f  (localized-file cfg file (config/default-lang cfg))
                    pl (when f (get-in (fm/read-file f) [:front-matter :permalink]))]]
          [kind (u/clean-url (or (u/blank->nil (str pl)) default-path))])))

;; ---------------------------------------------------------------------------
;; Page map

(defn- paginate
  "[[page-number ids] …] over `ids`, `per-page` at a time; at least one page."
  [ids per-page]
  (map-indexed (fn [i chunk] [(inc i) chunk])
               (or (seq (partition-all per-page ids)) [[]])))

(defn- index-pages
  "Every `/categories/…`, `/tags/…` and `/archives/` URI, per language, for the
  enabled kinds (D-P2-3). Slugs are the raw keys lower-cased with whitespace
  hyphenated; the URI keeps them verbatim (that is the directory written),
  hrefs percent-encode them."
  [model ctx-for]
  (let [{:keys [cfg]} model
        paths    (index-paths cfg)
        per-page (max 1 (long (or (get-in cfg [:theme :per-page]) 10)))
        title-key {:categories :index/categories :tags :index/tags :archives :index/archives}]
    (apply concat
           (for [lang (config/lang-keys cfg)
                 {:keys [kind file]} (enabled-index-kinds cfg)
                 :let [root  (get paths kind)
                       [file* own?] (localized-file cfg file lang true)
                       ;; the page's own language decides the title (§6.4 rule 2):
                       ;; a user-written `title:` counts only from that language's
                       ;; own @pages file (or the site default's, on the default
                       ;; language); everywhere else the theme string is used
                       title (or (when own?
                                   (some-> file* fm/read-file :front-matter :title u/blank->nil))
                                 (i18n/tr (ctx-for lang {}) (title-key kind)))
                       body  (fn []
                               (when file*
                                 (let [b (:body (fm/read-file file*))]
                                   (when-not (str/blank? b)
                                     (markdown/render b (link-context model model lang (str file*)))))))
                       base  {:kind kind :page-kind kind :title title
                              :root-uri (model/site-url cfg lang root)
                              :href-for (fn [k] (model/site-url cfg lang (str root (u/slug k) "/")))}
                       index (get model kind)]]
             (concat
              ;; the overview page
              [[(model/site-url cfg lang root)
                (fn []
                  (let [ctx (ctx-for lang (assoc base
                                                 :alt-url (fn [l] (model/site-url cfg l root))
                                                 :page-body (body)))]
                    (if (= kind :archives)
                      (indexes/archives (assoc ctx :archives index))
                      (indexes/overview (assoc ctx :index index)))))]]
              ;; one filtered list per category/tag, paginated
              (when (not= kind :archives)
                (for [[k ids] index
                      :let [sub   (str root (u/slug k) "/")
                            pages (paginate ids per-page)
                            total (count pages)]
                      [n page-ids] pages]
                  [(model/paged-url cfg lang sub n)
                   (fn []
                     (indexes/filtered
                      (ctx-for lang (assoc base
                                           :index index :current k :ids page-ids
                                           :page n :total total
                                           :page-url (fn [n] (model/paged-url cfg lang sub n))
                                           :alt-url (fn [l] (model/paged-url cfg l sub n))))))])))))))

(defn- home-front-matter
  "The homepage options for `lang`: its own `index.<lang>.md` when it has
  one, else the site-default `index.md` — list options are site-wide unless
  a language overrides them. The BODY, by contrast, comes only from the
  language's own file (§6.4 rule 2: chrome language ≡ content language)."
  [cfg lang]
  (let [[f own?] (localized-file cfg "index" lang true)
        parts (when f (fm/read-file f))]
    {:fm   (or (:front-matter parts) {})
     :body (when (and own? parts (not (str/blank? (:body parts)))) (:body parts))
     :path (some-> f str)}))

(defn- home-pages
  "Each language's home and its /page/N/ continuations (D-P2-5): the list is
  sticky ++ (posts minus sticky); `postList: detailed` paginates by
  [:theme :per-page], `simple` caps at `simplePostListLength`, `none` shows
  the body only."
  [model ctx-for]
  (let [{:keys [cfg]} model
        per-page (max 1 (long (or (get-in cfg [:theme :per-page]) 10)))
        ids      (home/home-ids model)
        plan     (into {}
                       (for [lang (config/lang-keys cfg)
                             :let [{:keys [fm body path]} (home-front-matter cfg lang)
                                   mode (home/post-list-mode fm)
                                   pages (if (= mode :detailed) (paginate ids per-page) [[1 ids]])]]
                         [lang {:fm fm :body body :path path :pages pages}]))
        page-uri (fn [l n]
                   ;; the same page under `l` when it has one, else `l`'s home
                   (if (<= n (count (get-in plan [l :pages])))
                     (model/paged-url cfg l "/" n)
                     (model/home-url cfg l)))]
    (for [lang (config/lang-keys cfg)
          :let [{:keys [fm body path pages]} (get plan lang)
                total (count pages)]
          [n page-ids] pages]
      [(model/paged-url cfg lang "/" n)
       (fn []
         (let [lc  (link-context model model lang path)
               ctx (ctx-for lang {:page-kind :home
                                  :home-fm fm
                                  :body (when body (markdown/render body lc))
                                  :ids page-ids :page n :total total
                                  :page-url (fn [n] (model/paged-url cfg lang "/" n))
                                  :alt-url (fn [l] (page-uri l n))
                                  :rewrite-href (fn [h] (markdown/rewrite-href lc h))
                                  :excerpt (fn [group vl]
                                             (let [v (get-in group [:variants vl])]
                                               (markdown/excerpt
                                                (:body v)
                                                (link-context model model lang (:rel-path v)))))})]
           (home/home ctx)))])))

(defn page-map
  "{uri → (fn [] hiccup)} for every emitted document.

  Which URIs exist follows §6.3 and D-P2-3:
    - the primary variant at the bare identity URL (or a redirect stub there
      when :prefix-default? is true — D-10, redirected not broken)
    - every non-primary variant under /<lang>/
    - one home per language
    - the enabled index pages per language, bare for the site-default
      language and under /<lang>/ otherwise, in BOTH :prefix-default? modes"
  [model]
  (let [{:keys [cfg articles]} model
        strings (i18n/load-strings cfg)
        model   (assoc model :by-rel-path (rel-path-index model))
        prefix-all? (get-in cfg [:i18n :prefix-default?])
        ctx-for (fn [lang m]
                  (merge {:cfg cfg :lang lang :strings strings :model model
                          :dev? (:clogem/dev? cfg)
                          :index-paths (index-paths cfg)}
                         m))]
    (into
     {}
     (concat
      ;; articles
      (for [[_pl group] articles
            [lang variant] (:variants group)]
        [(model/variant-url cfg group lang)
         (fn []
           (let [ctx (ctx-for lang {:group group :variant (assoc variant :lang lang)
                                    :page-kind :article})
                 lc  (link-context model model lang (:rel-path variant))
                 body (markdown/render (:body variant) lc)]
             (page/article ctx body)))])

      ;; redirect stubs at the bare identity URL when every variant is prefixed
      (when prefix-all?
        (for [[_pl group] articles]
          [(model/identity-url cfg group)
           (fn [] (page/redirect-stub cfg (model/variant-url cfg group (:primary group))))]))

      ;; homes, paginated: /, /page/2/, … per language
      (home-pages model ctx-for)

      ;; index pages
      (index-pages model ctx-for)))))

;; ---------------------------------------------------------------------------
;; Export

(def doctype
  ;; hiccup.page is not one of babashka's built-in hiccup namespaces (only
  ;; hiccup2.core and hiccup.util are), so the doctype is a literal.
  "<!DOCTYPE html>\n")

(defn uri->file
  "Map an emitted URI to the file that must hold it, **stripping the site base**.

  `dist/` *is* the deploy root: a project site's output directory is served at
  `https://host/<base>/`. So `:base` belongs in every emitted *link* — that is
  what makes the deployed HTML correct — and in no part of the *file layout*.
  Baking it in doubles it in the served URL (`/project/project/pages/…`) and
  leaves the site root a 404.

  The asymmetry is what hid this: assets are copied to `dist/clogem/…` by
  *path*, so they were already laid out correctly, while pages were laid out by
  *URI*. And the only base ever exercised was `/`, which is exactly the base
  under which stripping and not stripping produce identical output."
  [base out uri]
  (let [b     (u/clean-url (or base "/"))
        b-bare (str/replace b #"/$" "")          ; "" for "/", "/project" for "/project/"
        s     (str uri)
        rel   (cond
                (str/starts-with? s b) (subs s (count b))
                (= s b-bare)           ""
                :else
                (do (diag/warn!
                     s (str "emitted URI does not begin with the site base " b
                            "; writing it at the output root.")
                     "Every URL in the page map should be built from clogem.config/base-path.")
                    s))
        rel   (-> rel (str/replace #"^/+" "") (str/replace #"/+$" ""))]
    (if (str/blank? rel)
      (fs/path out "index.html")
      (fs/path out rel "index.html"))))

(defn export-pages!
  [cfg pages]
  (let [out  (config/out-dir cfg)
        base (config/base-path cfg)]
    (doseq [[uri render-fn] (sort-by key pages)]
      (let [f (uri->file base out uri)]
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
