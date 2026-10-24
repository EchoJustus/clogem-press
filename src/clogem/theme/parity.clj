;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.parity
  "Phase 4 Task E2 — article-page parity with vdoing (DESIGN.md §11.3 item
  16, D-P4-13, D-P4-16): the edit link and the last-updated date under an
  article or catalogue page, and the update bar under an article.

  This namespace requires `clogem.theme.layout`; layout never requires it,
  so the page templates call in here and layout stays the shared chrome."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.theme.icons :as icons]
            [clogem.theme.layout :as layout]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; The edit link (D-P4-16)

(defn- host-of
  "The lower-cased host of an `https://` URL, or \"\"."
  [url]
  (u/lower (or (second (re-find #"(?i)^https://([^/?#]+)" (str url))) "")))

(defn edit-href
  "The edit URL of the file at `path` (relative to the repository root, `/`
  separators) in the repository `url` on `branch`, as vdoing's
  `createEditLink` (PageEdit.vue) builds it:

    gitlab.com    → <url>/-/edit/<branch>/<path>
    bitbucket.org → <url>/src/<branch>/<path>?mode=edit&spa=0&at=<branch>&fileviewer=file-view-default
    anything else → <url>/edit/<branch>/<path>    (GitHub's form; Gitee and
                                                  other hosts get it too)

  Only the path and the branch are percent-encoded, segment by segment;
  the URL as configured is left alone."
  [{:keys [url branch]} path]
  (let [p (u/url-encode-path path)
        b (u/url-encode-path branch)]
    (case (str/replace (host-of url) #"^www\." "")
      "gitlab.com"    (str url "/-/edit/" b "/" p)
      "bitbucket.org" (str url "/src/" b "/" p "?mode=edit&spa=0&at=" (u/url-encode-segment branch)
                           "&fileviewer=file-view-default")
      (str url "/edit/" b "/" p))))

(defn- edit-link?
  "Front matter `editLink` on the variant: `false` hides its edit link,
  `true` changes nothing, and anything else is a warning naming the file,
  and is ignored (as vdoing's PageEdit reads it, per page)."
  [variant]
  (let [fm (:front-matter variant)
        v  (get fm :editLink)]
    (cond
      (nil? v)     true
      (boolean? v) v
      :else (do (diag/warn! (:rel-path variant)
                            (str "front matter editLink: " (pr-str v) " is not true or false; it is ignored."))
                true))))

(defn edit-url
  "The edit link of the page's variant, or nil: `:content :edit-link` is
  off, the variant says `editLink: false`, or its file is outside the site
  directory (no repository path to give it). The path is `:dir`, then the
  file relative to the site directory (`config/site-rel`)."
  [{:keys [cfg variant]}]
  ;; front matter first: a bad `editLink` is reported whether or not the
  ;; site has an edit link
  (let [shown? (edit-link? variant)
        e      (config/edit-link cfg)]
    (when (and shown? e (:path variant))
      (let [rel (config/site-rel cfg (:path variant))]
        (when-not (fs/absolute? rel)
          (edit-href e (str/join "/" (remove str/blank? [(:dir e) rel]))))))))

(defn last-updated
  "The variant file's last commit date, ISO 8601 (`clogem.gitlog`), or nil:
  `:theme :last-updated` is off, the site is not in git, or git has no
  commit for the file."
  [{:keys [cfg variant]}]
  (when-let [ds (:clogem/git-dates cfg)]
    (some->> (:path variant) (config/site-rel cfg) (get ds))))

(defn page-edit
  "vdoing's PageEdit, after the article (or the catalogue) and before the
  prev/next links: the edit link — the `pencil` icon and `:page/edit` —
  in the same tab, like every `:nav` link, then the last-updated date —
  the `clock` icon and `:page/last-updated` with the author's calendar
  date, `yyyy-mm-dd` in every language (the server has no other locales).
  Outside `<article data-pagefind-body>`, so search never indexes either.
  Nil when there is neither."
  [ctx]
  (let [href (edit-url ctx)
        iso  (last-updated ctx)]
    (when (or href iso)
      [:div.clogem-page-edit
       (when href
         [:a.clogem-page-edit__link {:href href} (icons/icon ctx :pencil) " " (i18n/tr ctx :page/edit)])
       (when iso
         [:span.clogem-page-edit__updated (icons/icon ctx :clock) " "
          [:time {:datetime iso} (i18n/tr ctx :page/last-updated {:date (subs iso 0 (min 10 (count iso)))})]])])))

;; ---------------------------------------------------------------------------
;; The update bar (D-P4-13)

(def update-bar-length
  "How many articles the update bar under an article lists: vdoing's
  `<UpdateArticle :length=\"3\">` (Page.vue), hard-coded there too."
  3)

(defn more-article-href
  "Where an update bar's \"More\" goes on a page in the page's language:
  `:theme :update-bar :more-article` through `layout/nav-href` (a site path
  gets the language's prefix, `/archives/` → `/zh-Hans/archives/`; a URL
  is left alone), else the archives in the page's language, else — the
  archives switched off — nowhere (nil)."
  [{:keys [cfg lang index-paths] :as ctx}]
  (if-let [m (:more-article (config/update-bar cfg))]
    (layout/nav-href ctx m)
    (when-let [root (:archives index-paths)]
      (layout/href ctx (model/site-url cfg lang root)))))

(defn more-article
  "The \"More →\" paragraph after a list of recent articles, or nil."
  [ctx]
  (when-let [h (more-article-href ctx)]
    [:p [:a {:href h} (i18n/tr ctx :index/more) " →"]]))

(defn update-bar
  "vdoing's UpdateArticle under an article (Page.vue): the
  `update-bar-length` newest articles — `(:posts model)`, newest first by
  front-matter date, as vdoing's `$sortPostsByDate` — other than this one,
  as index rows, then \"More →\". Only on an article page (`:article?`:
  never a catalogue or an `article: false` page), unless `:theme
  :update-bar :show-to-article` is false; nil when no other article exists."
  [{:keys [cfg model group] :as ctx}]
  (when (and (:article? group) (:show-to-article (config/update-bar cfg)))
    (let [ids (take update-bar-length (remove #{(:permalink group)} (:posts model)))]
      (when (seq ids)
        [:section.clogem-update-bar
         [:h2 (i18n/tr ctx :index/updates)]
         (into [:ul.clogem-list] (map #(layout/article-row ctx (get-in model [:articles %])) ids))
         (more-article ctx)]))))
