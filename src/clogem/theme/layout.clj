;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.layout
  "Theme chrome shared by every page: document shell, navbar, language
  switcher, sidebar, article rows, pagination, footer.

  Rule 2 of §6.4 is enforced structurally: chrome language always equals the
  page's content language, and `<html lang>` matches. There is no client-side
  string swapping anywhere in this namespace.

  Every URL emitted here is built from `clogem.config/base-path` (through
  `clogem.model`'s URL helpers), so `render/uri->file` can strip the base
  again — the site's `:base` belongs in every link and in no part of the file
  layout."
  (:require [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.seo :as seo]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; URLs

(defn asset-href
  "A FILE under the theme's exported assets (css/js). `clean-url` would append
  a slash to a file path, so the base is joined directly."
  [ctx path]
  (str/replace (str (config/base-path (:cfg ctx)) "/clogem/" path) #"/{2,}" "/"))

(defn href
  "A site URI made safe for an href: every path segment percent-encoded, so a
  CJK or Tamil category slug (kept verbatim in the URI and in the directory
  name — D-P2-3) is legal in the attribute."
  [_ctx uri]
  (u/url-encode-path uri))

;; ---------------------------------------------------------------------------
;; Labels

(defn category-label
  "D-12: the raw directory-derived name in every language unless
  `:i18n :category-labels` localizes it. Display only — the key and the URL
  slug stay raw."
  [{:keys [cfg lang]} name]
  (let [labels (get-in cfg [:i18n :category-labels (str name)])]
    (or (cond
          (string? labels) labels
          ;; the §6.5 chain WITHOUT resolve-str's first-available last resort:
          ;; an English page must show "Basics", not the first translation
          ;; that happens to exist
          (map? labels)    (some #(u/blank->nil (str (get labels %)))
                                 (config/fallback-chain cfg lang)))
        (str name))))

(defn article-link
  "§6.8: the reader's own variant when the article has one, else the primary,
  flagged as a fallback."
  [{:keys [cfg lang] :as ctx} group]
  (let [vl (model/best-variant group lang)
        v  (get-in group [:variants vl])]
    ;; encoded like every other href: a front-matter permalink may hold
    ;; CJK, Tamil or a space (0.1.1 emitted those raw)
    {:href      (href ctx (model/variant-url cfg group vl))
     :title     (:title v)
     :lang      vl
     :fallback? (not= vl lang)}))

(defn fallback-badge
  "The `:page/fallback-notice` marker on a row whose article has no variant in
  the page's language — gated by :show-fallback-notice."
  [{:keys [cfg] :as ctx} vl]
  (when (get-in cfg [:i18n :show-fallback-notice])
    [:span.clogem-fallback
     {:title (i18n/tr ctx :page/fallback-notice
                      {:lang (get-in cfg [:langs :locales vl :label])})}
     (name vl)]))

(defn index-href
  "The site URI of a category or tag page in the page's language, or nil
  when that index system is switched off — or when no ARTICLE carries the
  value (a category that only a catalogue page sits in has no index page,
  so a link to it would dangle)."
  [{:keys [cfg lang index-paths model]} kind k]
  (when-let [root (get index-paths kind)]
    (when (contains? (get model kind) (str k))
      (model/site-url cfg lang (str root (u/slug k) "/")))))

(defn- author-of
  "Front matter `author:` else `:site :author`, per `i18n/resolve-author`."
  [{:keys [cfg] :as ctx} variant]
  (i18n/resolve-author ctx (or (get-in variant [:front-matter :author]) (get-in cfg [:site :author]))))

(defn title-tag
  "vdoing's `titleTag:` badge beside a title (原创 / 转载 / …). The plain
  badge lands in Phase 2; the animated title-badge is Phase 4."
  [variant]
  (when-let [t (u/blank->nil (str (get-in variant [:front-matter :titleTag])))]
    [:span.clogem-title-tag t]))

(defn article-info
  "vdoing's ArticleInfo line (D-P2-7): author, ISO date, categories and tags
  linked to their index pages. `variant` supplies the author; `group` the
  language-invariant facts."
  [{:keys [cfg] :as ctx} group variant]
  (let [{:keys [name link]} (author-of ctx variant)
        cats (seq (:categories group))
        tags (seq (:tags group))]
    [:p.clogem-meta
     (when name
       [:span.clogem-meta__author {:title (i18n/tr ctx :page/author)}
        (if link [:a {:href link} name] name)])
     (when-let [d (u/iso-date (:date group))]
       [:time.clogem-meta__date {:datetime d :title (i18n/tr ctx :page/date)} d])
     (when cats
       (into [:span.clogem-meta__cats {:title (i18n/tr ctx :page/categories)}]
             (interpose " / "
                        (for [c cats]
                          (if-let [h (index-href ctx :categories c)]
                            [:a {:href (href ctx h)} (category-label ctx c)]
                            (category-label ctx c))))))
     (when tags
       (into [:span.clogem-meta__tags {:title (i18n/tr ctx :page/tags)}]
             (for [t tags]
               (if-let [h (index-href ctx :tags t)]
                 [:a.clogem-tag {:href (href ctx h)} (str t)]
                 [:span.clogem-tag (str t)]))))]))

(defn breadcrumbs
  "vdoing's breadcrumb line (D-P2-7): the primary's category path, each crumb
  linking to the Catalogue page that covers that directory when one exists
  (`:catalogue`, keyed by dir-key), else to the category's index page. Posts
  have no tree slot, so their crumbs derive from the category alone."
  [{:keys [cfg lang model] :as ctx} group]
  (let [segs (when (= :tree (:kind group))
               (vec (remove str/blank? (str/split (str (:dir-key group)) #"/"))))
        cats (:categories group)]
    (when (seq cats)
      [:nav.clogem-breadcrumbs {:aria-label (i18n/tr ctx :page/breadcrumbs)}
       (into [:ol
              [:li [:a {:href (model/home-url cfg lang)} (i18n/tr ctx :nav/home)]]]
             (map-indexed
              (fn [i c]
                (let [dir-key (when (and segs (< i (count segs)))
                                (str "/" (str/join "/" (take (inc i) segs))))
                      cat-pl  (when dir-key (get-in model [:catalogue dir-key]))
                      target  (if-let [g (and cat-pl (get-in model [:articles cat-pl]))]
                                ;; the URI, encoded once below
                                (model/variant-url cfg g (model/best-variant g lang))
                                (index-href ctx :categories c))]
                  [:li (if target
                         [:a {:href (href ctx target)} (category-label ctx c)]
                         (category-label ctx c))]))
              cats))])))

(defn prev-next
  "The prev/next buttons. `prev`/`next` are {:href :title :lang} or nil."
  [{:keys [cfg prev next] :as ctx}]
  (when (or prev next)
    [:nav.clogem-prev-next
     (when prev
       [:a.clogem-prev-next__prev {:href (href ctx (:href prev)) :rel "prev" :lang (config/html-lang cfg (:lang prev))}
        [:span (i18n/tr ctx :page/prev)] " " (:title prev)])
     (when next
       [:a.clogem-prev-next__next {:href (href ctx (:href next)) :rel "next" :lang (config/html-lang cfg (:lang next))}
        [:span (i18n/tr ctx :page/next)] " " (:title next)])]))

(defn article-row
  "One index row: title (linked per §6.8), fallback marker, ISO date."
  [{:keys [cfg] :as ctx} group]
  (let [{:keys [href title lang fallback?]} (article-link ctx group)]
    [:li.clogem-row
     [:a {:href href :lang (config/html-lang cfg lang)} title]
     (when fallback? (fallback-badge ctx lang))
     (when-let [d (u/iso-date (:date group))]
       [:time.clogem-meta__date {:datetime d} d])]))

;; ---------------------------------------------------------------------------
;; Chrome

(defn switch-target
  "Where the language switcher sends a reader of this page who picks `l`
  (§6.4 rule 3, D-P2-13):

    - an article that has an `l` variant → that variant's URL;
    - an article without one            → `l`'s home. Nothing on arrival
                                          says why, so `lang-switcher` marks
                                          that entry (`untranslated?`);
    - an index / catalogue / paginated
      page                              → the same page under `l` (:alt-url);
    - anything else                     → `l`'s home."
  [{:keys [cfg group alt-url]} l]
  (cond
    (and group (contains? (:variants group) l)) (model/variant-url cfg group l)
    group    (model/home-url cfg l)
    alt-url  (alt-url l)
    :else    (model/home-url cfg l)))

(defn untranslated?
  "Does the switcher entry for `l` land on `l`'s home only because this
  article has no `l` variant?"
  [{:keys [group]} l]
  (boolean (and group (not (contains? (:variants group) l)))))

(defn lang-switcher
  "Navbar language switcher — site-wide, on every page, listing every
  configured language (§6.4 rule 3, D-P2-13). Every target is a real URL to a
  real document: §6.3's point that the buttons only *look* like a client-side
  toggle. The current language is marked with aria-current.

  Phase 1 narrowed this to the article's own variants; the design specifies
  the site-wide switcher, with the per-article `variant-bar` below the title
  as the place that lists only what the article has."
  [{:keys [cfg lang] :as ctx}]
  (let [locales (get-in cfg [:langs :locales])
        langs   (config/lang-keys cfg)]
    (when (> (count langs) 1)
      [:nav.clogem-langs {:aria-label (i18n/tr ctx :nav/language)}
       [:span.clogem-langs__label (i18n/tr ctx :nav/language)]
       (into [:ul]
             (for [l langs
                   :let [current? (= l lang)]]
               [:li (if current?
                      [:span.is-current {:lang (config/html-lang cfg l)
                                         :aria-current "true"}
                       (get-in locales [l :label])]
                      (let [fallback? (untranslated? ctx l)
                            notice    (when (and fallback? (get-in cfg [:i18n :show-fallback-notice]))
                                        (i18n/tr ctx :page/fallback-notice
                                                 {:lang (get-in locales [lang :label])}))]
                        [:a (cond-> {:href (href ctx (switch-target ctx l))
                                     :lang (config/html-lang cfg l)
                                     :hreflang (config/html-lang cfg l)}
                              fallback? (assoc :class "is-untranslated"))
                         (get-in locales [l :label])
                         ;; the notice is in the PAGE's language (§6.4 rule 2),
                         ;; and said ONCE: a `title` as well made screen readers
                         ;; announce it twice, from an element whose `lang` is
                         ;; the target language's rather than the notice's
                         (when notice
                           [:span.clogem-visually-hidden {:lang (config/html-lang cfg lang)}
                            (str " (" notice ")")])]))]))])))

(defn nav-href
  "Where a `:nav` link goes on a page in `lang` (D-P2-11):

    external                → untouched
    /pages/xxxxxx/          → that article, in the reader's language when it
                              has it (catalogue permalinks are what nav
                              conventionally points to)
    a site page (`/…/`)     → the same page under the language's prefix
                              (`/` → `/zh-Hans/`, `/categories/` →
                              `/zh-Hans/categories/`)
    a file (`/x.pdf`)       → the base only"
  [{:keys [cfg lang model] :as ctx} link]
  (let [link (str link)]
    (cond
      (str/blank? link) (model/home-url cfg lang)
      (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#)" link) link
      (get-in model [:articles (u/clean-url link)])
      (:href (article-link ctx (get-in model [:articles (u/clean-url link)])))
      ;; a permalink that names nothing: the base only, never a language
      ;; prefix that would invent a URL — doctor reports it
      (re-find #"^/pages/" link) (str/replace (str (config/base-path cfg) "/" link) #"/{2,}" "/")
      (str/ends-with? link "/") (href ctx (model/site-url cfg lang link))
      :else (str/replace (str (config/base-path cfg) "/" link) #"/{2,}" "/"))))

(defn- nav-item
  [ctx {:keys [text link items]}]
  (let [label (i18n/resolve-str ctx text)
        a     (if link [:a {:href (nav-href ctx link)} label] [:span label])]
    (if (seq items)
      [:li.clogem-navbar__item.has-items
       [:details [:summary a]
        (into [:ul.clogem-navbar__menu] (map #(nav-item ctx %) items))]]
      [:li.clogem-navbar__item a])))

(defn navbar
  [{:keys [cfg lang] :as ctx}]
  [:header.clogem-navbar
   [:a.clogem-navbar__brand {:href (model/home-url cfg lang)}
    (i18n/resolve-str ctx (get-in cfg [:site :title]))]
   [:nav.clogem-navbar__nav
    (into [:ul] (map #(nav-item ctx %) (:nav cfg)))]
   (lang-switcher ctx)])

(defn- sidebar-node
  "One directory as <details>/<summary> — collapsible without JS — with its
  children in sidebar order. `open-all?` is [:theme :sidebar-open]; otherwise
  only the active trail (every ancestor of the current article) starts open."
  [{:keys [cfg model group] :as ctx} node open-all? trail]
  (let [current (:permalink group)]
    (into [:details.clogem-sidebar__dir
           (cond-> {:class (when (contains? trail (:dir-key node)) "is-active-trail")}
             (or open-all? (contains? trail (:dir-key node))) (assoc :open true))
           ;; a directory title IS a category name: D-12 labels apply
           [:summary (category-label ctx (:title node))]]
          [(into [:ul]
                 (for [c (:children node)]
                   (if (= :dir (:kind c))
                     [:li (sidebar-node ctx c open-all? trail)]
                     (let [g (get-in model [:articles (:permalink c)])
                           {:keys [href title lang fallback?]} (article-link ctx g)]
                       [:li {:class (when (= current (:permalink g)) "is-active")}
                        [:a {:href href :lang (config/html-lang cfg lang)
                             :aria-current (when (= current (:permalink g)) "page")}
                         title]
                        (when fallback? (fallback-badge ctx lang))]))))])))

(defn- ancestors-of
  "Every dir-key on the path to `dir-key`: \"/01.Guide/10.Basics\" →
  #{\"/01.Guide\" \"/01.Guide/10.Basics\"}."
  [dir-key]
  (let [segs (remove str/blank? (str/split (str dir-key) #"/"))]
    (set (map #(str "/" (str/join "/" (take % segs))) (range 1 (inc (count segs)))))))

(defn sidebar
  "The left sidebar tree of D-P2-1, or nil when the page has none:

    - an article page shows ONLY its own top-level directory's tree;
    - a post (`sidebar: auto`) has no structured position and gets no tree;
    - `sidebar: false` on the primary variant hides the panel;
    - home, index and catalogue pages show none (they use `page`).

  Every directory is a <details> group; [:theme :sidebar-open true] opens all
  of them, false only the active trail. The leaf AND every ancestor of the
  current article are marked."
  [{:keys [cfg model group] :as ctx}]
  (when-let [top (and group
                      (not (false? (get-in group [:variants (:primary group) :front-matter :sidebar])))
                      (model/top-dir group))]
    (when-let [node (get-in model [:sidebar top])]
      [:aside.clogem-sidebar
       [:nav {:aria-label (i18n/tr ctx :page/sidebar)}
        (sidebar-node ctx node
                      (not (false? (get-in cfg [:theme :sidebar-open])))
                      (ancestors-of (:dir-key group)))]])))

(defn toc
  "The right-hand TOC bar (D-P2-9): one link per entry of ctx :toc, levelled
  by class, hidden when there is nothing to list. The scroll-spy in
  js/toc.js marks the current entry; without JS it is a plain list of links."
  [{:keys [toc] :as ctx}]
  (when (seq toc)
    [:aside.clogem-toc
     [:nav {:aria-label (i18n/tr ctx :page/toc)}
      (into [:ul]
            (for [{:keys [level href text]} toc]
              [:li {:class (str "level-" level)}
               [:a {:href href} text]]))]]))

(defn shell
  "The page body between navbar and footer: the sidebar tree when the page
  has one, the main column, and the TOC bar when the page has headings."
  [ctx main]
  (let [sb (sidebar ctx)
        tc (toc ctx)]
    [:div.clogem-shell {:class (str/join " " (remove nil? [(when-not sb "clogem-shell--single")
                                                          (when tc "clogem-shell--toc")]))}
     sb
     main
     tc]))

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
               [:a {:href (href ctx (model/variant-url cfg group l))
                    :lang (config/html-lang cfg l)
                    :hreflang (config/html-lang cfg l)}
                (get-in cfg [:langs :locales l :label])]))])))

(defn pagination
  "Previous / `Page n of total` / next. `url-for` maps a page number to its
  site URI; page 1 is the list's root."
  [ctx page total url-for]
  (when (> total 1)
    [:nav.clogem-pagination {:aria-label (i18n/tr ctx :index/page {:n page :total total})}
     (if (> page 1)
       [:a.clogem-pagination__prev {:href (href ctx (url-for (dec page))) :rel "prev"}
        (i18n/tr ctx :index/prev-page)]
       [:span.clogem-pagination__prev.is-disabled (i18n/tr ctx :index/prev-page)])
     [:span.clogem-pagination__page (i18n/tr ctx :index/page {:n page :total total})]
     (if (< page total)
       [:a.clogem-pagination__next {:href (href ctx (url-for (inc page))) :rel "next"}
        (i18n/tr ctx :index/next-page)]
       [:span.clogem-pagination__next.is-disabled (i18n/tr ctx :index/next-page)])]))

(defn footer
  [{:keys [cfg]}]
  [:footer.clogem-footer
   [:p (str "clogem-press " (:clogem/version cfg))]])

;; ---------------------------------------------------------------------------
;; Document

(defn seo-head
  "The `<head>` tags of D-P3-2 and D-P3-3, from the page's `:seo`
  (`clogem.render/seo-info`), or nothing when the page has none:

    - `rel=canonical`, self-referencing;
    - one `rel=alternate hreflang` per member of the page's equivalence set,
      itself included, plus `x-default` → the bare URL — only on a page that
      has a set (pagination past page 1 has none);
    - `og:locale` from the language's `:og`, and one `og:locale:alternate`
      per other language in the set;
    - Atom autodiscovery for the page's own language, when that feed exists.

  The links need an absolute URL, so a site with no `:site :url` gets none
  of them (D-P3-1); `og:locale` names a language, not a URL, and is emitted
  either way."
  [{:keys [cfg lang seo model]}]
  (when seo
    (let [url? (config/site-url-root cfg)
          abs  #(config/absolute-url cfg %)
          alts (when (get-in cfg [:seo :hreflang] true) (:alternates seo))
          og   (config/og-locale cfg lang)]
      (concat
       (when url?
         (concat
          [[:link {:rel "canonical" :href (abs (:canonical seo))}]]
          (for [[l u] alts]
            [:link {:rel "alternate" :hreflang (config/html-lang cfg l) :href (abs u)}])
          (when (seq alts)
            [[:link {:rel "alternate" :hreflang "x-default" :href (abs (:x-default seo))}]])))
       (when og
         (cons [:meta {:property "og:locale" :content og}]
               (for [o (distinct (keep (fn [[l _]] (config/og-locale cfg l)) (:alternates seo)))
                     :when (not= o og)]
                 [:meta {:property "og:locale:alternate" :content o}])))
       (when (contains? (:feed-langs model) lang)
         [[:link {:rel "alternate" :type "application/atom+xml"
                  :hreflang (config/html-lang cfg lang)
                  :title (str (i18n/resolve-str {:cfg cfg :lang lang} (get-in cfg [:site :title])))
                  :href (u/url-encode-path (seo/feed-uri cfg lang))}]])))))

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
      (when-let [d (u/blank->nil (i18n/resolve-str ctx (get-in cfg [:site :description])))]
        [:meta {:name "description" :content d}])
      (seo-head ctx)
      [:link {:rel "stylesheet" :href (asset-href ctx "css/theme.css")}]
      ;; vendored vanilla scroll-spy (D-P2-9); no CDN, no deps — deferred,
      ;; and only on a page that renders a TOC for it to spy on
      (when (seq (:toc ctx))
        [:script {:src (asset-href ctx "js/toc.js") :defer true}])]
     (into [:body {:class (str "theme-mode-" (name (get-in cfg [:theme :default-mode] :auto))
                               " theme-style-" (name (get-in cfg [:theme :page-style] :card))
                               " lang-" (name lang)
                               (when-let [k (:page-kind ctx)] (str " page-" (name k))))}]
           body)]))

(defn page
  "A page with no sidebar tree — home, index, catalogue and paginated pages
  show none (D-P2-1): navbar, a single-column shell, footer."
  [ctx & main]
  (document ctx
            (navbar ctx)
            [:div.clogem-shell.clogem-shell--single
             (into [:main.clogem-main] main)]
            (footer ctx)))
