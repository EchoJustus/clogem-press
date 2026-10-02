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
            [clogem.assets :as assets]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.markdown :as markdown]
            [clogem.model :as model]
            [clogem.pages :as pages]
            [clogem.search :as search]
            [clogem.seo :as seo]
            [clogem.theme.catalogue :as catalogue]
            [clogem.theme.home :as home]
            [clogem.theme.indexes :as indexes]
            [clogem.theme.layout :as layout]
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
              (u/url-encode-path (model/variant-url cfg group (model/best-variant group l))))})

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
;;
;; Resolved and parsed during analyse (`pages/site-files`, D.2.1 fix A); a
;; model built some other way gets them resolved here, as a fallback.

(def localized-file
  "See `clogem.pages/localized-file`."
  pages/localized-file)

(defn- site-file
  "The cached {:path :own? :front-matter :body} `lang` reads for `rel`, or nil."
  [model rel lang]
  (get-in model [:site-files [rel lang]]))

(defn index-paths
  "{kind → site-relative root path} for every enabled index: the `@pages/`
  file's `permalink:` when it has one, else the default."
  [model]
  (let [cfg (:cfg model)]
    (into {}
          (for [{:keys [kind default-path] :as k} (pages/enabled-kinds cfg)
                :let [pl (get-in (site-file model (pages/file-rel k) (config/default-lang cfg))
                                 [:front-matter :permalink])]]
            [kind (u/clean-url (or (u/blank->nil (str pl)) default-path))]))))

;; ---------------------------------------------------------------------------
;; Page map

(defn seo-info
  "What a page's `<head>` and the sitemap say about it (D-P3-2): its own URI,
  its language and — on a page that has one — its equivalence set
  ({lang → uri} over every language copy, itself included) with the
  `x-default` URI, which `:seo :x-default :primary` makes `primary`.
  `alt-for` nil means no set: a pagination page past the first is
  self-canonical only, because page N of a list holds different articles in
  each language."
  [cfg lang self alt-for primary]
  (cond-> {:lang lang :canonical self}
    alt-for (assoc :alternates (into (array-map)
                                     (keep (fn [l] (when-let [u (alt-for l)] [l u])))
                                     (config/lang-keys cfg))
                   ;; `:seo :x-default`; :primary, the only value config
                   ;; accepts, is the bare identity URL `primary`
                   :x-default (case (get-in cfg [:seo :x-default] :primary)
                                :primary primary))))

(defn- with-seo
  "Attach the page's `seo-info` to its render thunk, where the sitemap reads
  it back (`page-seo`); a stub carries `:clogem/stub` instead."
  [f m]
  (with-meta f m))

(defn page-seo
  "The `seo-info` a page-map thunk carries, or nil (a redirect stub)."
  [f]
  (:clogem/seo (meta f)))

(defn- paginate
  "[[page-number ids] …] over `ids`, `per-page` at a time; at least one page."
  [ids per-page]
  (map-indexed (fn [i chunk] [(inc i) chunk])
               (or (seq (partition-all per-page ids)) [[]])))

(defn- index-pages
  "Every `/categories/…`, `/tags/…` and `/archives/` URI, per language, for the
  enabled kinds (D-P2-3). Slugs are `util/slug` of the raw key — lower-cased,
  whitespace and Windows-illegal characters collapsed to `-`, a reserved
  device name suffixed with `_` (§11.2 item 5); the URI keeps Unicode
  verbatim (that is the directory written) and hrefs percent-encode it."
  [model ctx-for]
  (let [{:keys [cfg]} model
        paths    (:index-paths model)
        per-page (max 1 (long (or (get-in cfg [:theme :per-page]) 10)))]
    (apply concat
           (for [lang (config/lang-keys cfg)
                 {:keys [kind title-key] :as k} (pages/enabled-kinds cfg)
                 :let [root  (get paths kind)
                       {file* :path own? :own? :as sf} (site-file model (pages/file-rel k) lang)
                       ;; the page's own language decides the title (§6.4 rule 2):
                       ;; a user-written `title:` counts only from that language's
                       ;; own @pages file (or the site default's, on the default
                       ;; language); everywhere else the theme string is used
                       title (or (when own?
                                   (some-> sf :front-matter :title u/blank->nil))
                                 (i18n/tr (ctx-for lang {}) title-key))
                       ;; a user-authored body renders above the list (D-P2-6) —
                       ;; only from the language's OWN file (§6.4 rule 2)
                       body  (fn []
                               (when (and file* own?)
                                 (let [b (:body sf)]
                                   (when-not (str/blank? b)
                                     (markdown/render b (link-context model model lang (str file*)))))))
                       base  {:kind kind :page-kind kind :title title
                              :root-uri (model/site-url cfg lang root)
                              :href-for (fn [k] (model/site-url cfg lang (str root (u/slug k) "/")))}
                       index (get model kind)]]
             (concat
              (if (= kind :archives)
                (let [seo (seo-info cfg lang (model/site-url cfg lang root)
                                    (fn [l] (model/site-url cfg l root))
                                    (model/site-url cfg (config/default-lang cfg) root))]
                  [[(model/site-url cfg lang root)
                    (with-seo
                      (fn []
                        (indexes/archives
                         (ctx-for lang (assoc base
                                              :seo seo
                                              :alt-url (fn [l] (model/site-url cfg l root))
                                              :page-body (body)
                                              :archives index))))
                      {:clogem/seo seo})]])
                ;; the overview: the bar, then EVERY article paginated below
                ;; it (DESIGN.md §1, as vdoing's /categories/ and /tags/ do) —
                ;; a site whose articles carry no tags still lists them
                (let [pages (paginate (:posts model) per-page)
                      total (count pages)]
                  (for [[n page-ids] pages
                        :let [seo (seo-info cfg lang (model/paged-url cfg lang root n)
                                            (when (= 1 n) #(model/paged-url cfg % root 1))
                                            (model/paged-url cfg (config/default-lang cfg) root 1))]]
                    [(model/paged-url cfg lang root n)
                     (with-seo
                     (fn []
                       (indexes/overview
                        (ctx-for lang (assoc base
                                             :seo seo
                                             :index index :ids page-ids
                                             :page n :total total
                                             :page-url (fn [n] (model/paged-url cfg lang root n))
                                             :alt-url (fn [l] (model/paged-url cfg l root n))
                                             :page-body (when (= 1 n) (body))))))
                     {:clogem/seo seo})])))
              ;; one filtered list per category/tag, paginated
              (when (not= kind :archives)
                (for [[k ids] index
                      :let [sub   (str root (u/slug k) "/")
                            pages (paginate ids per-page)
                            total (count pages)]
                      [n page-ids] pages
                      :let [seo (seo-info cfg lang (model/paged-url cfg lang sub n)
                                          (when (= 1 n) #(model/paged-url cfg % sub 1))
                                          (model/paged-url cfg (config/default-lang cfg) sub 1))]]
                  [(model/paged-url cfg lang sub n)
                   (with-seo
                   (fn []
                     (indexes/filtered
                      (ctx-for lang (assoc base
                                           :seo seo
                                           :index index :current k :ids page-ids
                                           :page n :total total
                                           :page-url (fn [n] (model/paged-url cfg lang sub n))
                                           :alt-url (fn [l] (model/paged-url cfg l sub n))))))
                   {:clogem/seo seo})])))))))

(defn- home-front-matter
  "The homepage options for `lang`: its own `index.<lang>.md` when it has
  one, else the site-default `index.md` — list options are site-wide unless
  a language overrides them. The BODY, by contrast, comes only from the
  language's own file (§6.4 rule 2: chrome language ≡ content language)."
  [model lang]
  (let [{f :path own? :own? :as sf} (site-file model "index" lang)]
    {:fm   (or (:front-matter sf) {})
     :body (when (and own? sf (not (str/blank? (:body sf)))) (:body sf))
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
                             :let [{:keys [fm body path]} (home-front-matter model lang)
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
          [n page-ids] pages
          :let [seo (seo-info cfg lang (model/paged-url cfg lang "/" n)
                              (when (= 1 n) #(model/home-url cfg %))
                              (model/home-url cfg (config/default-lang cfg)))]]
      [(model/paged-url cfg lang "/" n)
       (with-seo
       (fn []
         (let [lc  (link-context model model lang path)
               ctx (ctx-for lang {:page-kind :home
                                  :seo seo
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
           (home/home ctx)))
       {:clogem/seo seo})])))

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
  (let [;; the theme assets' `?v=` fingerprints (D-P4-7), computed once per
        ;; page map rather than once per asset URL; `render-site` passes the
        ;; snapshot of the bytes it writes, and a page map built another way
        ;; (`doctor`, a test) takes one here
        model   (cond-> model
                  (not (get-in model [:cfg :clogem/asset-versions]))
                  (assoc-in [:cfg :clogem/asset-versions] (assets/versions (assets/files (:cfg model)))))
        {:keys [cfg articles]} model
        strings (i18n/load-strings cfg)
        ;; index*.md and @pages/ are parsed by analyse; a model built without
        ;; it (a test calling model/build-model directly) resolves them here
        model   (cond-> model
                  (not (contains? model :site-files)) (assoc :site-files (pages/site-files cfg)))
        paths   (index-paths model)
        model   (assoc model :by-rel-path (rel-path-index model) :strings strings :index-paths paths
                       ;; which languages have an Atom feed, for autodiscovery (D-P3-3)
                       :feed-langs (set (seo/feed-langs model)))
        prefix-all? (get-in cfg [:i18n :prefix-default?])
        ;; D-P3-10: the search UI strings a language needs, worked out once
        ;; per language rather than once per page (it reads the site's i18n
        ;; file to see whether it overrides any)
        search-ui (into {}
                        (for [l (config/lang-keys cfg)]
                          [l (delay (when (search/enabled? cfg)
                                      (search/ui-translations
                                       {:cfg cfg :lang l :strings strings
                                        :dev? (:clogem/dev? cfg)})))]))
        ctx-for (fn [lang m]
                  (merge {:cfg cfg :lang lang :strings strings :model model
                          :dev? (:clogem/dev? cfg)
                          :index-paths paths
                          :search-ui (some-> (get search-ui lang) deref)}
                         m))]
    (into
     {}
     (concat
      ;; articles — and catalogue pages, which are articles whose primary
      ;; variant carries `pageComponent: {name: Catalogue}` (D-P2-8)
      (for [[_pl group] articles
            [lang variant] (:variants group)
            :let [seo (seo-info cfg lang (model/variant-url cfg group lang)
                                #(when (contains? (:variants group) %)
                                   (model/variant-url cfg group %))
                                (model/identity-url cfg group))]]
        [(model/variant-url cfg group lang)
         (with-seo
         (fn []
           (let [[prev-pl next-pl] (model/neighbours model group)
                 lc  (link-context model model lang (:rel-path variant))
                 ctx (ctx-for lang {:group group :variant variant
                                    :seo seo
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
                               (markdown/->hiccup (markdown/drop-leading-h1 ast) lc))))))
         {:clogem/seo seo})])

      ;; redirect stubs at the bare identity URL when every variant is prefixed
      (when prefix-all?
        (for [[_pl group] articles]
          [(model/identity-url cfg group)
           (with-seo
             (fn [] (page/redirect-stub cfg (model/variant-url cfg group (:primary group))))
             {:clogem/stub true})]))

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

(def jobs-env
  "The environment variable that bounds the render pool (D-P4-11)."
  "CLOGEM_JOBS")

(defn jobs
  "How many pages render at once: `CLOGEM_JOBS` when it is a positive
  integer, else the number of available processors. `CLOGEM_JOBS=1` renders
  on the calling thread, page by page, exactly as 0.2.0 did. A value that
  is not a positive integer is a warning, not an error."
  []
  (let [raw (u/blank->nil (str (System/getenv jobs-env)))
        n   (some-> raw str/trim parse-long)]
    (cond
      (nil? raw) (.availableProcessors (Runtime/getRuntime))
      (and n (pos? n)) n
      :else (do (diag/warn! nil (str jobs-env " is " (pr-str raw) ", which is not a positive integer; "
                                     "using the number of processors."))
                (.availableProcessors (Runtime/getRuntime))))))

(defn- render-page
  "One page's bytes, rendered under its own diagnostic sink: [bytes ds]."
  [render-fn]
  (diag/collecting
   (.getBytes (str doctype (h/html (render-fn))) "UTF-8")))

(defn- render-failure
  "An exception while rendering `uri`, as a build failure naming the page.
  The cause is kept; so is its ex-data, under a guaranteed exit status."
  [uri ^Throwable t]
  (ex-info (str "could not render " uri ": " (or (ex-message t) (.getName (class t))))
           (merge (ex-data t) {:babashka/exit 1 :clogem/uri uri})
           t))

(defn render-pages
  "Render every page of a page map to bytes, in memory: a vector of
  [uri bytes] sorted by URI, and the pages' diagnostics in the deterministic
  order `diag/sorted` gives them, whatever order the pages finished in.

  At most `n` pages (default `jobs`) render at once, on a bounded set of
  worker threads that take the next page from a shared counter. Each worker
  runs with the caller's dynamic bindings (`bound-fn`), and each page with
  its own diagnostic sink, so the sinks never race. The first page that
  throws — anything, `Throwable` included — stops the rest and is raised,
  naming its URI; nothing has been written by then.

  **Thread safety.** Everything a page render reads must be safe to share
  between threads: the model and config are immutable values, and the
  per-language search UI strings are `delay`s. Any cache a later change adds
  to the render path (Phase 4 adds a Chroma highlighting cache) must be a
  `ConcurrentHashMap`, an atom updated with pure functions, or otherwise
  thread-safe — or it must be filled before rendering starts."
  ([pages] (render-pages pages (jobs)))
  ([pages n]
   (let [entries (vec (sort-by key pages))
         total   (count entries)
         results (object-array total)
         n       (max 1 (min (long n) total))]
     (if (<= n 1)
       (dotimes [i total]
         (let [[uri f] (nth entries i)]
           (aset results i (try (render-page f)
                                (catch Throwable t (throw (render-failure uri t)))))))
       (let [next-i  (java.util.concurrent.atomic.AtomicLong. 0)
             failure (atom nil)
             work    (bound-fn []
                       (loop []
                         (let [i (.getAndIncrement next-i)]
                           (when (and (< i total) (nil? @failure))
                             (let [[uri f] (nth entries i)]
                               (try (aset results i (render-page f))
                                    (catch Throwable t
                                      (swap! failure #(or % (render-failure uri t))))))
                             (recur)))))
             workers (doall (repeatedly n #(future (work))))]
         (doseq [w workers] @w)
         (when-let [e @failure] (throw e))))
     [(mapv (fn [i] [(key (nth entries i)) (first (aget results i))]) (range total))
      (diag/sorted (mapcat #(second (aget results %)) (range total)))])))

(defn copy-tree!
  "Copy a directory tree, never symlink (see the ns docstring). `skip?`, given
  a path relative to `from`, leaves that file out. Returns the files written."
  ([from to] (copy-tree! from to (constantly false)))
  ([from to skip?]
   (when (fs/directory? from)
     (fs/create-dirs to)
     (vec
      (for [p (fs/glob from "**")
            :when (and (fs/regular-file? p) (not (skip? (str (fs/relativize from p)))))]
        (let [target (fs/path to (fs/relativize from p))]
          (fs/create-dirs (fs/parent target))
          (fs/copy p target {:replace-existing true})
          target))))))

(defn theme-resource-dir
  "See `clogem.assets/theme-resource-dir`."
  []
  (assets/theme-resource-dir))

(defn asset-outputs
  "The theme's and the site's assets as outputs ({:file :bytes} or
  {:file :source}): the theme's from `clogem.assets/files` — the exact bytes
  their `?v=` fingerprints hash — and the site's own `assets/` tree, copied
  (never linked) at write time."
  [cfg theme-files]
  (let [out (config/out-dir cfg)]
    (vec
     (concat
      (for [[rel bytes] theme-files]
        {:file (fs/path out "clogem" rel) :bytes bytes})
      (let [user (config/assets-dir cfg)]
        (when (fs/directory? user)
          (for [p (sort (map str (fs/glob user "**")))
                :when (fs/regular-file? p)]
            {:file (fs/path out "assets" (fs/relativize user p)) :source (fs/path p)})))))))

(defn- html-file? [p]
  (boolean (re-find #"(?i)\.html$" (str (fs/file-name p)))))

(defn sweep-stale-html!
  "Delete the `.html` files under `out` that this build did not write — the
  pages of articles since deleted or moved — so neither `bb serve` nor
  Pagefind sees them in a reused `dist/` (DESIGN.md §11.2 item 46). Only
  `.html` files, only inside `out`, never through a link: a symlinked
  directory is not descended and a symlink is never deleted. `<out>/pagefind/`
  is left to `search/run!`, which replaces it whole. Directories emptied by
  the sweep are removed. Returns the files deleted."
  [out written]
  (let [out     (fs/normalize (fs/absolutize out))
        keep?   (into #{} (map #(str (fs/normalize (fs/absolutize %)))) written)
        bundle  (fs/path out search/output-subdir)
        stale   (when (fs/directory? out {:nofollow-links true})
                  (->> (fs/glob out "**" {:follow-links false :hidden true})
                       (map #(fs/normalize (fs/absolutize %)))
                       (filter #(and (html-file? %)
                                     (fs/regular-file? % {:nofollow-links true})
                                     (str/starts-with? (str %) (str out java.io.File/separator))
                                     (not (str/starts-with? (str %) (str bundle java.io.File/separator)))
                                     (not (keep? (str %)))))
                       vec))]
    (doseq [f stale]
      (fs/delete f)
      (loop [d (fs/parent f)]
        (when (and d (not= (str d) (str out))
                   (str/starts-with? (str d) (str out java.io.File/separator))
                   (fs/directory? d {:nofollow-links true})
                   (empty? (fs/list-dir d)))
          (fs/delete d)
          (recur (fs/parent d)))))
    stale))

(defn- strip-tags
  "Plain text of an HTML string: aria-hidden elements dropped whole, block tags → a space, inline tags dropped, the five entities hiccup
  escapes decoded, whitespace collapsed (a feed `summary` is type=\"text\")."
  [html]
  (-> (str html)
      ;; an aria-hidden element is decoration — the heading anchor's `#` —
      ;; and says nothing a reader of the text would miss
      (str/replace #"(?is)<([a-z][a-z0-9]*)\b[^>]*\baria-hidden=\"true\"[^>]*>.*?</\1\s*>" "")
      ;; a block boundary is a word boundary; an inline tag is not — `<strong>`
      ;; inside a CJK sentence must not split it with a space
      (str/replace #"(?i)</?(?:p|div|li|ul|ol|h[1-6]|br|blockquote|pre|tr|td|th|table|dt|dd|hr)\b[^>]*>" " ")
      (str/replace #"<[^>]*>" "")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'") (str/replace "&apos;" "'") (str/replace "&amp;" "&")
      (str/replace #"\s+" " ")
      str/trim))

(defn feed-summary
  "The plain text of a variant's `<!-- more -->` excerpt, or nil (D-P3-3)."
  [model group lang]
  (let [v (get-in group [:variants lang])]
    (some-> (markdown/excerpt (:body v) (link-context model model lang (:rel-path v)))
            h/html str strip-tags u/blank->nil)))

(defn- file-for
  "The output file for a base-inclusive FILE uri (`/zh-Hans/feed.xml`)."
  [cfg uri]
  (let [b (config/base-path cfg)
        s (str uri)
        rel (if (str/starts-with? s b) (subs s (count b)) (str/replace s #"^/+" ""))]
    (fs/path (config/out-dir cfg) rel)))

(defn seo-outputs
  "Atom feeds, sitemap.xml and robots.txt (D-P3-3, D-P3-4), as outputs
  ({:file :bytes :uri}). Feeds and the sitemap need an absolute URL, so
  neither is produced when `:site :url` is blank (D-P3-1). robots.txt counts
  only at the host root, so it is produced only when the base is `/`: a
  site's own `<assets>/robots.txt` is copied there — URL or not, since it
  needs none — and otherwise one is generated when there is a URL."
  [cfg model pages]
  (let [url?  (config/site-url-root cfg)
        model (cond-> model
                (not (contains? model :site-files)) (assoc :site-files (pages/site-files cfg)))
        model (assoc model :by-rel-path (rel-path-index model) :strings (i18n/load-strings cfg))
        out   (fn [uri content]
                {:uri uri :file (file-for cfg uri) :bytes (.getBytes (str content) "UTF-8")})
        user-robots (fs/path (config/assets-dir cfg) "robots.txt")]
    (vec
     (concat
      (for [l (seo/feed-langs model)]
        (out (seo/feed-uri cfg l)
             (seo/emit-xml (seo/feed-xml model l #(feed-summary model %1 %2)))))
      (when (and url? (get-in cfg [:seo :sitemap] true))
        [(out (seo/sitemap-uri cfg)
              (seo/emit-xml (seo/sitemap-xml cfg (keep (comp page-seo val) pages))))])
      (when (= "/" (config/base-path cfg))
        (cond
          (fs/regular-file? user-robots) [(out "/robots.txt" (slurp (fs/file user-robots)))]
          url?                           [(out "/robots.txt" (seo/robots-txt cfg))]))))))

;; ---------------------------------------------------------------------------
;; Writing (D-P4-11)
;;
;; A build renders EVERYTHING into memory first — pages, theme assets, feeds,
;; the sitemap — and only then touches `dist/`. An exception half-way through
;; rendering used to leave a mixed tree: 150 new pages and 86 old ones, the
;; new site title on some, a deleted variant's page still served, and the
;; old feed.xml, sitemap.xml and pagefind/. Now it leaves `dist/` exactly as
;; it was. Each file is then written atomically (a temp file in the same
;; directory, renamed over the target) and only when its bytes changed, so a
;; rebuild with no source change writes nothing at all.

(def ^:private temp-marker ".clogem-tmp-")

(defn- same-bytes?
  "Does `f` already hold exactly `bytes`? A link is never \"the same\": the
  rename replaces the link itself rather than writing through it."
  [f ^bytes bytes]
  (and (fs/regular-file? f {:nofollow-links true})
       (= (alength bytes) (fs/size f))
       (java.util.Arrays/equals bytes ^bytes (fs/read-all-bytes f))))

(defn- write-atomically!
  "Write `bytes` to `f` through a temp file in the same directory and an
  atomic rename, so a reader (or a killed build) never sees half a file."
  [f ^bytes bytes]
  (let [dir (fs/parent f)
        tmp (fs/path dir (str "." (fs/file-name f) temp-marker (System/nanoTime)))]
    (fs/create-dirs dir)
    (try
      (java.nio.file.Files/write (fs/path tmp) bytes
                                 ^"[Ljava.nio.file.OpenOption;"
                                 (into-array java.nio.file.OpenOption []))
      (fs/move tmp f {:replace-existing true :atomic-move true})
      (finally (fs/delete-if-exists tmp)))))

(defn- output-bytes
  ^bytes [{:keys [bytes source]}]
  (or bytes (fs/read-all-bytes source)))

(defn- sweep-temp-files!
  "Remove temp files a killed earlier build left in the directories this one
  writes to — files only the build itself ever creates."
  [files]
  (doseq [d (distinct (map fs/parent files))
          :when (fs/directory? d {:nofollow-links true})
          p (fs/list-dir d)
          :when (and (str/includes? (str (fs/file-name p)) temp-marker)
                     (fs/regular-file? p {:nofollow-links true}))]
    (fs/delete-if-exists p)))

(defn write-outputs!
  "Write each output whose bytes differ from what is on disk, atomically.
  Returns {:written n :unchanged n :files [every output file]}."
  [outputs]
  (let [files (mapv :file outputs)]
    (sweep-temp-files! files)
    (reduce (fn [acc {:keys [file] :as o}]
              (let [b (output-bytes o)]
                (if (same-bytes? file b)
                  (update acc :unchanged inc)
                  (do (write-atomically! file b)
                      (update acc :written inc)))))
            {:written 0 :unchanged 0 :files files}
            outputs)))

(defn render-site
  "Render the whole site into memory: every page, the theme's and the site's
  assets, the feeds, the sitemap and robots.txt. Writes nothing. The pages'
  diagnostics are emitted (in `diag/sorted` order) to the caller's sink.
  Returns {:outputs [{:file :bytes|:source}] :pages n :articles n
  :variants n :out dir}."
  [cfg model]
  (let [out         (config/out-dir cfg)
        theme-files (assets/files cfg)
        ;; the fingerprints every page's asset URLs carry are those of the
        ;; very bytes asset-outputs writes (D-P4-7)
        cfg         (assoc cfg :clogem/asset-versions (assets/versions theme-files))
        model       (assoc model :cfg cfg)
        pages       (page-map model)
        base        (config/base-path cfg)
        [rendered ds] (render-pages pages)
        _           (diag/emit-all! ds)
        page-outs   (mapv (fn [[uri bytes]] {:file (uri->file base out uri) :bytes bytes}) rendered)]
    {:outputs  (vec (concat page-outs
                            (asset-outputs cfg theme-files)
                            (seo-outputs cfg model pages)))
     :pages    (count page-outs)
     :articles (count (:articles model))
     :variants (reduce + (map #(count (:variants %)) (vals (:articles model))))
     :out      (str out)}))

(defn write-site!
  "Write what `render-site` produced into the output directory, then sweep
  the `.html` files no longer produced (§11.2 item 46). Never deletes
  anything else — `CNAME`, `.nojekyll`, a verification file the site owner
  or a CI step put there all stay. Returns the render summary plus
  {:written :unchanged :stale}."
  [{:keys [outputs out] :as rendered}]
  (fs/create-dirs out)
  (let [{:keys [written unchanged files]} (write-outputs! outputs)
        stale (sweep-stale-html! out files)]
    (-> rendered
        (dissoc :outputs)
        (assoc :written written :unchanged unchanged :stale (count stale)))))

(defn build!
  "Render, then export: `render-site`, then `write-site!`. Returns a summary
  map. A render failure raises before a single file is written."
  [cfg model]
  (write-site! (render-site cfg model)))
