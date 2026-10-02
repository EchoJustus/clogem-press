;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.seo
  "The machine-readable side of a site (DESIGN.md §6.6): Atom feeds, the
  sitemap and robots.txt (D-P3-3, D-P3-4). The `<head>` tags live in
  `clogem.theme.layout/seo-head`; what they and the sitemap say about each
  page is computed once, in `clogem.render/seo-info`.

  Everything here needs an absolute URL, so nothing here is emitted when
  `:site :url` is blank (D-P3-1) — analyse warns once instead.

  clojure.data.xml under bb 1.13 emits an unqualified `:xmlns` attribute as a
  prefixed declaration (`xmlns:b=…`) unless the tags themselves are
  namespace-qualified, so every tag is a keyword in an `alias-uri` namespace
  and the default namespace is declared on the root."
  (:require [clojure.data.xml :as x]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.util :as u]))

(x/alias-uri 'atom "http://www.w3.org/2005/Atom"
             'sm   "http://www.sitemaps.org/schemas/sitemap/0.9"
             'xh   "http://www.w3.org/1999/xhtml")

(def atom-ns "http://www.w3.org/2005/Atom")
(def sitemap-ns "http://www.sitemaps.org/schemas/sitemap/0.9")
(def xhtml-ns "http://www.w3.org/1999/xhtml")

(def feed-size
  "Entries per feed (D-P3-3)."
  20)

;; ---------------------------------------------------------------------------
;; Dates

(defn rfc3339
  "An RFC 3339 timestamp with an offset for a front-matter `date` (D-P3-3).

  A date that carries a zone (`Z`, `+08:00`) keeps it. A date without one —
  every date auto-fill writes, and every vdoing date — is read in the JVM's
  default zone, which is `TZ`: the site's CI sets `TZ=Asia/Singapore`, so
  `2026-08-01 09:30:00` becomes `2026-08-01T09:30:00+08:00`. nil when the
  value does not start with a date."
  [d]
  (when-let [[_ y mo dd h mi sec zone]
             (re-find #"^\s*(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T]+(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?(?:\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})?)?"
                      (str d))]
    (let [ldt (java.time.LocalDateTime/of (int (parse-long y)) (int (parse-long mo)) (int (parse-long dd))
                                          (int (or (some-> h parse-long) 0))
                                          (int (or (some-> mi parse-long) 0))
                                          (int (or (some-> sec parse-long) 0)))
          off (if zone
                (java.time.ZoneOffset/of (if (re-matches #"[+-]\d{4}" zone)
                                           (str (subs zone 0 3) ":" (subs zone 3))
                                           zone))
                (.getOffset (.getRules (java.time.ZoneId/systemDefault)) ldt))]
      (.format (java.time.OffsetDateTime/of ldt off)
               java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME))))

;; ---------------------------------------------------------------------------
;; Feeds (D-P3-3)

(defn feeds?
  [cfg]
  (boolean (and (get-in cfg [:seo :feeds] true) (config/site-url-root cfg))))

(defn feed-uri
  "`/feed.xml` for the site-default language, `/<lang>/feed.xml` otherwise —
  the prefix rule homes follow (model/lang-prefix), base included."
  [cfg lang]
  (str (str/replace (model/home-url cfg lang) #"/$" "") "/feed.xml"))

(defn feed-groups
  "The newest `feed-size` article groups that have a `lang` variant and a
  parseable date, in `newest-first` order. Undated articles are left out:
  an Atom entry must carry `updated`."
  [model lang]
  (->> (vals (:articles model))
       (filter #(and (:article? %) (contains? (:variants %) lang) (rfc3339 (:date %))))
       model/newest-first
       (take feed-size)))

(defn- newest-date
  "The newest dated article's RFC 3339 date over the whole site, or nil."
  [model]
  (some->> (vals (:articles model))
           (filter #(and (:article? %) (rfc3339 (:date %))))
           seq model/newest-first first :date rfc3339))

(defn feed-langs
  "The languages that get a feed: every configured one, when feeds are on,
  the site has a URL and at least one article is dated. A language with no
  articles yet gets an EMPTY feed rather than none — a reader can subscribe
  before the first translation lands, and the autodiscovery link on that
  language's pages names a real file."
  [model]
  (let [cfg (:cfg model)]
    (when (and (feeds? cfg) (newest-date model))
      (vec (config/lang-keys cfg)))))

(defn- author-name
  [ctx]
  (let [cfg (:cfg ctx)
        a   (get-in cfg [:site :author])
        a   (if (map? a) (or (:name a) (get a "name")) a)]
    (or (u/blank->nil (i18n/resolve-str ctx a))
        (i18n/resolve-str ctx (get-in cfg [:site :title])))))

(defn feed-xml
  "The Atom feed for `lang`. `summary-for` maps (group, lang) to the plain
  text of the variant's `<!-- more -->` excerpt, or nil."
  [model lang summary-for]
  (let [cfg    (:cfg model)
        ctx    {:cfg cfg :lang lang}
        abs    #(config/absolute-url cfg %)
        self   (abs (feed-uri cfg lang))
        groups (feed-groups model lang)]
    (x/element
     ::atom/feed {:xmlns atom-ns :xml/lang (config/html-lang cfg lang)}
     (x/element ::atom/id {} self)
     (x/element ::atom/title {} (str (i18n/resolve-str ctx (get-in cfg [:site :title]))))
     (x/element ::atom/link {:rel "self" :type "application/atom+xml" :href self})
     (x/element ::atom/link {:rel "alternate" :type "text/html" :href (abs (model/home-url cfg lang))})
     ;; the newest entry; an empty feed has none, so the site's newest date —
     ;; deterministic, unlike the build time
     (x/element ::atom/updated {} (or (rfc3339 (:date (first groups))) (newest-date model)))
     (x/element ::atom/author {} (x/element ::atom/name {} (str (author-name ctx))))
     (x/element ::atom/generator {:version (str (:clogem/version cfg))} "clogem-press")
     (for [g groups
           :let [v   (get-in g [:variants lang])
                 url (abs (model/variant-url cfg g lang))
                 d   (rfc3339 (:date g))
                 sum (summary-for g lang)]]
       (x/element
        ::atom/entry {}
        (x/element ::atom/id {} url)
        (x/element ::atom/title {} (str (:title v)))
        (x/element ::atom/link {:rel "alternate" :type "text/html" :href url})
        ;; RFC 4287 §4.2.7.4: hreflang on an alternate link names the language
        ;; of the resource it points to — one per other translation
        (for [l (config/lang-keys cfg)
              :when (and (not= l lang) (contains? (:variants g) l))]
          (x/element ::atom/link {:rel "alternate" :type "text/html"
                                  :hreflang (config/html-lang cfg l)
                                  :href (abs (model/variant-url cfg g l))}))
        (x/element ::atom/published {} d)
        (x/element ::atom/updated {} d)
        (when (u/blank->nil sum) (x/element ::atom/summary {:type "text"} sum))
        (for [t (distinct (concat (:categories g) (:tags g)))]
          (x/element ::atom/category {:term (str t)})))))))

;; ---------------------------------------------------------------------------
;; Sitemap and robots.txt (D-P3-4)

(defn sitemap-xml
  "One `<url>` per indexable page — `seo-infos` is every page's `seo-info`,
  stubs already excluded. A page with an equivalence set lists all of it as
  `xhtml:link`, itself and x-default included. No lastmod (there is no
  accurate one), no priority, no changefreq."
  [cfg seo-infos]
  (let [abs #(config/absolute-url cfg %)]
    (x/element
     ::sm/urlset {:xmlns sitemap-ns :xmlns/xhtml xhtml-ns}
     (for [{:keys [canonical alternates x-default]} (sort-by :canonical seo-infos)]
       (x/element
        ::sm/url {}
        (x/element ::sm/loc {} (abs canonical))
        (when (and (seq alternates) (get-in cfg [:seo :hreflang] true))
          (concat
           (for [[l u] alternates]
             (x/element ::xh/link {:rel "alternate" :hreflang (config/html-lang cfg l) :href (abs u)}))
           [(x/element ::xh/link {:rel "alternate" :hreflang "x-default" :href (abs x-default)})])))))))

(defn sitemap-uri [cfg] (str (config/base-path cfg) "sitemap.xml"))

(defn robots-txt
  [cfg]
  (str "User-agent: *\nAllow: /\n\nSitemap: " (config/absolute-url cfg (sitemap-uri cfg)) "\n"))

(defn emit-xml
  [el]
  (x/indent-str el))
