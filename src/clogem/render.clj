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
            [clogem.pages :as pages]
            [clogem.theme.catalogue :as catalogue]
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
   ;; the containers' default titles come from the page's language (§6.4 rule 2)
   :strings (:strings model)
   :dev? (:clogem/dev? cfg)
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
  "The file a language reads for `content/<rel>.md`, the convention
  `index.md` / `index.zh-Hans.md` and `@pages/*.md` share:

    {:path <rel>.<suffix>.md :own? true}   when a file whose suffix names
                                           `lang` exists — matched
                                           case-insensitively, as the scanner
                                           matches suffixes (§6.1), so
                                           `index.zh-hant.md` counts for zh-Hant
    {:path <rel>.md :own? <default?>}      else the unsuffixed file, which is
                                           the language's OWN only on the site
                                           default language
    nil                                    when neither exists"
  [cfg rel lang]
  (let [base   (fs/path (config/content-dir cfg) (str rel ".md"))
        parent (fs/parent base)
        prefix (str (fs/file-name (fs/path (config/content-dir cfg) rel)) ".")
        own    (when (fs/directory? parent)
                 (->> (fs/list-dir parent)
                      (sort-by str)
                      (some (fn [p]
                              (let [fname (str (fs/file-name p))]
                                (when (and (str/starts-with? fname prefix)
                                           (str/ends-with? (u/lower fname) ".md")
                                           (> (count fname) (+ (count prefix) 3))
                                           (fs/regular-file? p)
                                           (= lang (config/lang-for-suffix
                                                    cfg (subs fname (count prefix) (- (count fname) 3)))))
                                  p))))))]
    (cond
      own                {:path own :own? true}
      (fs/exists? base)  {:path base :own? (= lang (config/default-lang cfg))}
      :else              nil)))

(defn index-paths
  "{kind → site-relative root path} for every enabled index, read once per
  build: the `@pages/` file's `permalink:` when it has one, else the default."
  [cfg]
  (into {}
        (for [{:keys [kind default-path] :as k} (pages/enabled-kinds cfg)
              :let [f  (:path (localized-file cfg (pages/file-rel k) (config/default-lang cfg)))
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
        paths    (:index-paths model)
        per-page (max 1 (long (or (get-in cfg [:theme :per-page]) 10)))]
    (apply concat
           (for [lang (config/lang-keys cfg)
                 {:keys [kind title-key] :as k} (pages/enabled-kinds cfg)
                 :let [root  (get paths kind)
                       {file* :path own? :own?} (localized-file cfg (pages/file-rel k) lang)
                       ;; the page's own language decides the title (§6.4 rule 2):
                       ;; a user-written `title:` counts only from that language's
                       ;; own @pages file (or the site default's, on the default
                       ;; language); everywhere else the theme string is used
                       title (or (when own?
                                   (some-> file* fm/read-file :front-matter :title u/blank->nil))
                                 (i18n/tr (ctx-for lang {}) title-key))
                       ;; a user-authored body renders above the list (D-P2-6) —
                       ;; only from the language's OWN file (§6.4 rule 2)
                       body  (fn []
                               (when (and file* own?)
                                 (let [b (:body (fm/read-file file*))]
                                   (when-not (str/blank? b)
                                     (markdown/render b (link-context model model lang (str file*)))))))
                       base  {:kind kind :page-kind kind :title title
                              :root-uri (model/site-url cfg lang root)
                              :href-for (fn [k] (model/site-url cfg lang (str root (u/slug k) "/")))}
                       index (get model kind)]]
             (concat
              (if (= kind :archives)
                [[(model/site-url cfg lang root)
                  (fn []
                    (indexes/archives
                     (ctx-for lang (assoc base
                                          :alt-url (fn [l] (model/site-url cfg l root))
                                          :page-body (body)
                                          :archives index))))]]
                ;; the overview: the bar, then EVERY article paginated below
                ;; it (DESIGN.md §1, as vdoing's /categories/ and /tags/ do) —
                ;; a site whose articles carry no tags still lists them
                (let [pages (paginate (:posts model) per-page)
                      total (count pages)]
                  (for [[n page-ids] pages]
                    [(model/paged-url cfg lang root n)
                     (fn []
                       (indexes/overview
                        (ctx-for lang (assoc base
                                             :index index :ids page-ids
                                             :page n :total total
                                             :page-url (fn [n] (model/paged-url cfg lang root n))
                                             :alt-url (fn [l] (model/paged-url cfg l root n))
                                             :page-body (when (= 1 n) (body))))))])))
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
  (let [{f :path own? :own?} (localized-file cfg "index" lang)
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

(defn- resolve-target
  "A front matter `prev:`/`next:` value: a permalink or a relative `.md`
  path → the group it names, or nil (with a warning) when it names nothing."
  [model from-path v]
  (let [s (str v)]
    (or (get-in model [:articles (u/clean-url s)])
        (get-in model [:by-rel-path (u/lower s)])
        (get-in model [:by-rel-path (u/lower (str "./" s))])
        (do (diag/warn! from-path (str "front matter prev/next names an unknown page: " s))
            nil))))

(defn- neighbour
  "The prev or next link of an article page: front matter `false` hides it,
  a permalink or `.md` path overrides the model's order, else
  `model/neighbours`. The target is the reader's own variant when the
  neighbour has one (model/best-variant)."
  [model lang variant group k pl]
  (let [fm-v (get-in variant [:front-matter k])
        g    (cond
               (false? fm-v) nil
               (some? fm-v)  (resolve-target model (:rel-path variant) fm-v)
               :else         (get-in model [:articles pl]))]
    (when g
      (let [vl (model/best-variant g lang)]
        {:href  (model/variant-url (:cfg model) g vl)
         :title (get-in g [:variants vl :title])
         :lang  vl}))))

(defn- catalogue-node
  "The directory node a Catalogue page renders, or nil — with a warning —
  when the page names no directory or an unknown pageComponent, in which
  case the page falls back to its body (D-P2-8)."
  [model group from-path]
  (when-let [pc (:page-component group)]
    (let [nm (u/lower (str (:name pc)))]
      (cond
        (not= "catalogue" nm)
        (do (diag/warn! from-path (str "unknown pageComponent `" (:name pc)
                                       "`; rendering the markdown body instead.")
                        "Only `Catalogue` is supported (DESIGN.md §1.1).")
            nil)

        (nil? (model/catalogue-dir-key pc))
        (do (diag/warn! from-path "pageComponent Catalogue has no data.path; rendering the body instead.")
            nil)

        :else
        (or (model/find-dir (:tree model) (model/catalogue-dir-key pc))
            (do (diag/warn! from-path
                            (str "pageComponent Catalogue path `" (get-in pc [:data :path])
                                 "` is not a directory in the content tree; rendering the body instead.")
                            "Use the numbered directory names exactly, e.g. `01.Guide/10.Basics`.")
                nil))))))

(defn toc-depth
  "The TOC depth of an article page: front matter `sidebarDepth` when it is an
  integer 0–5 (or a digit string — YAML authors quote things), else
  `[:theme :sidebar-depth]`. Anything else is a warning naming the file, not
  a silent fallback."
  [cfg variant]
  (let [v       (get-in variant [:front-matter :sidebarDepth])
        default (get-in cfg [:theme :sidebar-depth])
        n       (cond
                  (integer? v) v
                  (and (string? v) (re-matches #"\s*\d+\s*" v)) (parse-long (str/trim v))
                  :else nil)]
    (cond
      (nil? v)          default
      (and n (<= 0 n 5)) n
      :else (do (diag/warn! (:rel-path variant)
                            (str "front matter sidebarDepth: " (pr-str v)
                                 " is not an integer from 0 to 5; using " default "."))
                default))))

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
        ;; read the @pages/ files ONCE per build, not once per rendered page
        paths   (index-paths cfg)
        model   (assoc model :by-rel-path (rel-path-index model) :strings strings :index-paths paths)
        prefix-all? (get-in cfg [:i18n :prefix-default?])
        ctx-for (fn [lang m]
                  (merge {:cfg cfg :lang lang :strings strings :model model
                          :dev? (:clogem/dev? cfg)
                          :index-paths paths}
                         m))]
    (into
     {}
     (concat
      ;; articles — and catalogue pages, which are articles whose primary
      ;; variant carries `pageComponent: {name: Catalogue}` (D-P2-8)
      (for [[_pl group] articles
            [lang variant] (:variants group)]
        [(model/variant-url cfg group lang)
         (fn []
           (let [[prev-pl next-pl] (model/neighbours model group)
                 lc  (link-context model model lang (:rel-path variant))
                 ctx (ctx-for lang {:group group :variant variant
                                    :page-kind :article
                                    :rewrite-href (fn [h] (markdown/rewrite-href lc h))
                                    :prev (neighbour model lang variant group :prev prev-pl)
                                    :next (neighbour model lang variant group :next next-pl)})
                 node (catalogue-node model group (:rel-path variant))]
             (if node
               (catalogue/catalogue (assoc ctx :page-kind :catalogue :node node
                                           :page-component (:page-component group)))
               ;; parse ONCE: the body hiccup and the TOC come from the same
               ;; AST, so TOC ids and heading anchors agree by construction
               (let [ast   (markdown/parse (:body variant) lc)
                     depth (toc-depth cfg variant)]
                 (page/article (assoc ctx :toc (markdown/toc ast depth))
                               ;; the theme renders the title; the body's own
                               ;; `# Title` would be a second <h1>
                               (markdown/->hiccup (markdown/drop-leading-h1 ast) lc))))))])

      ;; redirect stubs at the bare identity URL when every variant is prefixed
      (when prefix-all?
        (for [[_pl group] articles]
          [(model/identity-url cfg group)
           (fn [] (page/redirect-stub cfg (model/variant-url cfg group (:primary group))))]))

      ;; homes, paginated: /, /page/2/, … per language
      (home-pages model ctx-for)

      ;; index pages
      (index-pages model ctx-for)))))

(defn check-pages!
  "Render every page to hiccup and throw the result away — what `doctor`
  runs so that render-time findings (dead links, unresolved catalogue paths,
  unknown containers, bad card-list YAML) land in its report without a
  build. Returns the page count."
  [model]
  (let [pm (page-map model)]
    (doseq [[_ f] pm] (f))
    (count pm)))

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
