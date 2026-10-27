;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.identity
  "The blog identity (Phase 4 Task E1a, DESIGN.md §11.3 item 13, D-P4-13):
  the social links, the blogger card, the footer's lines, the home banner
  (hero), the title badge and the body background image.

  Every value read here was checked and repaired by `clogem.config`'s
  `check-identity!`, so a renderer never meets a bad one. This namespace
  does not require `clogem.theme.layout`: layout, home and the page
  templates call into it, and gain only call sites.

  What the owner writes is text, escaped as hiccup escapes every string —
  the blogger's name and slogan, a link's title — except where D-P4-13
  trusts the owner's raw HTML: the footer's `:copyright-info` (`raw-html`)
  and a `bannerBg` of the form `background: …`, which is the banner's
  `style` attribute. `raw-html` also serves the htmlModules slots and
  `:head-extra` (P4-E1b, `clogem.theme.modules`)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [clogem.assets :as assets]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.markdown :as markdown]
            [clogem.theme.icons :as icons]
            [clogem.util :as u]))

(def project-url
  "Where the footer's \"Powered by\" line points."
  "https://github.com/EchoJustus/clogem-press")

;; ---------------------------------------------------------------------------
;; URLs and owner text

(defn- with-base
  "A site path base-prefixed and percent-encoded, as every emitted URL is
  — its `?query`, `#fragment` and `%XX` escapes kept (`u/url-encode-href`,
  P4-E1a.1 item 1); a URL (`https:`, `data:`, `mailto:`) as it is —
  `markdown/with-base` prefixes any string under a non-root base, so a URL
  never reaches it."
  [{:keys [cfg]} v]
  (when v
    (if (config/site-path? v)
      (markdown/with-base cfg (u/url-encode-href v))
      v)))

(defn base-href
  "The site's base as `{{base}}` interpolates it: encoded, ending in `/`."
  [cfg]
  (str/replace (str (u/url-encode-path (config/base-path cfg)) "/") #"/+$" "/"))

(defn raw-html
  "The owner's trusted HTML for this page's language (D-P4-13), or nil: a
  string, or a per-language map resolved along the fallback chain by
  `contains?` — so `\"\"` suppresses it in that language, and there is no
  first-value fallback (unlike `i18n/resolve-str`) — then `{{base}}`
  replaced by the encoded base (any other `{{…}}` is left as it is), then
  `h/raw`."
  [{:keys [cfg lang]} v]
  (let [s (cond
            (string? v) v
            (map? v)    (some #(when (contains? v %) (str (get v %))) (config/fallback-chain cfg lang)))]
    (when-not (str/blank? s)
      (h/raw (i18n/interpolate s {:base (base-href cfg)})))))

;; ---------------------------------------------------------------------------
;; Item 3: social links (D-P4-6)

(defn social-list
  "`:theme :social :icons` as a list of links, each `rel=\"me\"`, named by
  its title, opening in the same tab; the icon is the sprite's, or the
  link's own `:svg` as an `<img>` (not recoloured). nil without links."
  [{:keys [cfg] :as ctx}]
  (when-let [links (seq (get-in cfg [:theme :social :icons]))]
    (into [:ul.clogem-social {:aria-label (i18n/tr ctx :social/label)}]
          (for [{:keys [icon title link svg]} links
                :let [t (i18n/resolve-str ctx title)]]
            [:li [:a {:href (with-base ctx link) :rel "me" :aria-label t :title t}
                  (if svg
                    [:img.clogem-icon {:src (with-base ctx svg) :alt ""}]
                    (icons/icon ctx icon))]]))))

;; ---------------------------------------------------------------------------
;; Item 4: the blogger card

(defn drawer?
  "Does this page's navbar render `#clogem-drawer`? `clogem.theme.layout/navbar`'s
  own condition: a `:nav`, more than one language, or a sidebar (`:sidebar?`)."
  [{:keys [cfg sidebar?]}]
  (boolean (or (seq (:nav cfg)) (> (count (config/lang-keys cfg)) 1) sidebar?)))

(defn- blogger-body
  "vdoing's BloggerBar, in its order: the avatar, the social links, then
  the name and the slogan. The avatar is decorative — the name is beside
  it."
  [ctx {:keys [avatar name slogan]}]
  (list
   (when avatar [:img.clogem-blogger__avatar {:src (with-base ctx avatar) :alt ""}])
   (social-list ctx)
   (when-let [n (u/blank->nil (i18n/resolve-str ctx name))] [:p.clogem-blogger__name n])
   (when-let [s (u/blank->nil (i18n/resolve-str ctx slogan))] [:p.clogem-blogger__slogan s])))

(defn blogger-card
  "The home right bar's first box, or nil without `:theme :blogger`. On a
  page that also renders the drawer's card it is `clogem-blogger--paired`,
  which identity.css hides where the drawer's shows."
  [{:keys [cfg] :as ctx}]
  (when-let [b (get-in cfg [:theme :blogger])]
    (into [:section.clogem-blogger (cond-> {:aria-label (i18n/tr ctx :blogger/label)}
                                     (drawer? ctx) (assoc :class "clogem-blogger--paired"))]
          (blogger-body ctx b))))

(defn drawer-card
  "The blogger card at the top of `#clogem-drawer` (vdoing's sidebar card
  at 719 px and below), or nil. Rendered `hidden`: identity.css shows it
  only with scripting at 50rem and below — never on a desktop, on a
  mobile page without JS, or when identity.css fails to load."
  [{:keys [cfg] :as ctx}]
  (when-let [b (get-in cfg [:theme :blogger])]
    (into [:div.clogem-blogger.clogem-blogger--drawer {:hidden true}] (blogger-body ctx b))))

;; ---------------------------------------------------------------------------
;; Item 5: the footer

(defn years
  "The copyright years: `:create-year` to `:end-year` — or, without one,
  the newest dated article's year — as `start–end` when start < end, else
  the one year; nil when there is none."
  [{:keys [cfg model]}]
  (let [{:keys [create-year end-year]} (get-in cfg [:theme :footer])
        end (or end-year (ffirst (:archives model)))]
    (cond
      (and create-year end (< create-year end)) (str create-year "–" end)
      create-year (str create-year)
      end         (str end))))

(defn footer
  "The footer's children (vdoing's Footer.vue, without its theme credit):
  the social links, the copyright line when `:create-year`, `:end-year`
  or `:copyright-info` is set, and \"Powered by clogem-press\" unless
  `:powered-by false`; nil when there is nothing to show. The version is
  in `<meta name=\"generator\">` only."
  [{:keys [cfg] :as ctx}]
  (let [f    (get-in cfg [:theme :footer])
        info (raw-html ctx (:copyright-info f))
        ys   (when (some #(contains? f %) [:create-year :end-year :copyright-info]) (years ctx))
        ;; {{name}} is kept by `tr` without vars: ta puts the name first
        [before after] (str/split (i18n/tr ctx :footer/powered-by) #"\{\{\s*name\s*\}\}" 2)
        body (remove nil?
                     [(social-list ctx)
                      (when (or ys info)
                        [:p.clogem-footer__copyright "© " ys (when (and ys info) " ") info])
                      (when-not (false? (:powered-by f))
                        [:p.clogem-footer__powered before [:a {:href project-url} "clogem-press"] after])])]
    (when (seq body) body)))

;; ---------------------------------------------------------------------------
;; Item 6: the home banner

(defn- fm-str
  [ctx v]
  (u/blank->nil (str/trim (str (i18n/resolve-str ctx v)))))

;; P4-E1a.2 item 1
(defn- map-lang
  "The language `i18n/resolve-str` takes a per-language map's value from:
  the first on the fallback chain with a value, else the map's first key."
  [{:keys [cfg lang]} m]
  (or (some #(when (get m %) %) (config/fallback-chain cfg lang))
      (first (keys m))))

(defn hero-text
  "The home's `heroText` on its first page, or nil."
  [{:keys [home-fm page] :as ctx}]
  (when (= 1 page) (fm-str ctx (:heroText home-fm))))

(defn- banner-kind
  "[kind value] for the banner: the home's `bannerBg`, else `:theme
  :banner-bg`, else auto. A bad front-matter value warns, naming the home
  file, and is auto."
  [{:keys [cfg home-fm home-path]}]
  (let [fm (:bannerBg home-fm)]
    (or (when (some? fm)
          (or (config/banner-bg fm)
              (do (when home-path
                    (diag/warn! home-path (str "front matter bannerBg is " (pr-str fm)
                                               ", but it must be auto, none, an image (an https: URL, a "
                                               "data:image/… URI or a site path) or CSS starting `background:`; "
                                               "using auto.")
                                "See README, Theme (DESIGN.md §11.3 item 13)."))
                  nil)))
        (config/banner-bg (get-in cfg [:theme :banner-bg]))
        [:auto])))

(defn hero
  "vdoing's home banner (Home.vue), between the navbar and the shell, on
  the home's first page only, and only when its front matter asks for it:
  `heroImage`, `heroText`, `tagline`, `actionText` with `actionLink`, or
  `bannerBg`. A `<div>`, not a `<header>`: the navbar is the banner
  landmark. `h1?`: the text is the page's `<h1>` (the main column has
  none), else a `<p>`. The background is the front matter's `bannerBg`,
  else `:theme :banner-bg`: auto (a grid on vdoing's #28282d, white text),
  none (the card's colours), an image behind the text, or the owner's
  `background: …` as a style attribute. Under `:body-bg-img` auto and none
  are transparent, as vdoing's are."
  [{:keys [cfg home-fm home-path home-fm-lang page rewrite-href] :as ctx} h1?]
  (when (= 1 page)
    (let [{:keys [heroImage heroAlt tagline actionText actionLink bannerBg]} home-fm
          ;; checked on the first page only, so a paginated home warns once
          ;; (P4-E1a.1 item 2), as `banner-kind` does
          img     (when (some? heroImage)
                    (or (config/image-value (str heroImage))
                        (do (when home-path
                              (diag/warn! home-path (str "front matter heroImage is " (pr-str heroImage)
                                                         ", which is not an https: URL, a data:image/… URI or a "
                                                         "site path; it is not shown.")))
                            nil)))
          text    (hero-text ctx)
          tagline (fm-str ctx tagline)
          action  (fm-str ctx actionText)
          link    (some-> actionLink str str/trim u/blank->nil)
          ;; a string inherited from another language's file is in that
          ;; language, and says so (P4-E1a.1 item 5); so is a per-language
          ;; map's value taken from another language (P4-E1a.2 item 1)
          lang    (fn [v] (when-let [l (if (map? v) (map-lang ctx v) home-fm-lang)]
                            (when (not= l (:lang ctx)) {:lang (config/html-lang cfg l)})))]
      (when (or img text tagline (and action link) (some? bannerBg))
        (let [[kind v] (banner-kind ctx)
              kind (if (and (#{:auto :none} kind) (config/body-bg-images cfg)) :clear kind)
              alt  (fm-str ctx heroAlt)]
          [:div {:class (str "clogem-hero clogem-hero--" (name kind))
                 :style (when (= :custom kind) v)}
           (when (= :image kind) [:img.clogem-hero__bg {:src (with-base ctx v) :alt ""}])
           (when img [:img.clogem-hero__img (merge {:src (with-base ctx img) :alt (or alt "")} (when alt (lang heroAlt)))])
           (when text (let [l (lang (:heroText home-fm))]
                        (if h1? [:h1.clogem-hero__title l text] [:p.clogem-hero__title l text])))
           (when tagline [:p.clogem-hero__tagline (lang (:tagline home-fm)) tagline])
           (when (and action link)
             [:a.clogem-hero__action (merge {:href ((or rewrite-href identity) link)} (lang actionText)) action])])))))

;; ---------------------------------------------------------------------------
;; Item 7: the title badge

(defn title-badge
  "vdoing's title badge, first in an article's or the archives page's
  `<h1>`, unless `:theme :title-badge false`. One of `:theme
  :title-badge-icons` (default: the sprite's book-open, pencil and hash),
  chosen by the CRC32 of `k` — the article group's identity permalink, or
  the page's path without base or language prefix — so every language
  variant and every build shows the same one. Decoration: an icon is
  `aria-hidden`, an image has `alt=\"\"`."
  [{:keys [cfg] :as ctx} k]
  (when (true? (get-in cfg [:theme :title-badge]))
    (let [icons (or (seq (get-in cfg [:theme :title-badge-icons])) config/default-badge-icons)
          crc   (doto (java.util.zip.CRC32.) (.update (.getBytes (str k) "UTF-8")))
          ic    (nth icons (mod (.getValue crc) (count icons)))]
      (if (string? ic)
        [:img.clogem-title-badge {:src (with-base ctx ic) :alt ""}]
        (icons/icon ctx ic {:class "clogem-title-badge"})))))

;; ---------------------------------------------------------------------------
;; Item 8: the body background image

(defn body-bg
  "vdoing's BodyBgImg: an `<img>`, fixed behind the page, before back to
  top. Its opacity is `data-o`, in tenths, which identity.css maps — no
  style attribute. Two or more images carry their base-prefixed URLs and
  the interval for js/bodybg.js (`body-bg-script`)."
  [{:keys [cfg] :as ctx}]
  (when-let [imgs (config/body-bg-images cfg)]
    (let [urls (mapv #(with-base ctx %) imgs)]
      [:img.clogem-body-bg
       (cond-> {:src (first urls) :alt "" :aria-hidden "true" :decoding "async" :fetchpriority "low"
                :data-o (str (Math/round (* 10.0 (double (get-in cfg [:theme :body-bg-img-opacity] 0.5)))))}
         (next urls) (assoc :data-clogem-bg (json/generate-string urls)
                            :data-interval (str (get-in cfg [:theme :body-bg-img-interval] 15))))])))

(defn rotates?
  "Does this site rotate its background image — two or more images? Only
  then js/bodybg.js ships (`clogem.assets`) and is linked."
  [cfg]
  (> (count (config/body-bg-images cfg)) 1))

(defn head
  "What E1a adds to `<head>`, right after theme.css: css/identity.css while
  `config/identity-css?` holds, and the deferred, same-origin js/bodybg.js
  while the background rotates."
  [{:keys [cfg]}]
  (list
   (when (config/identity-css? cfg)
     [:link {:rel "stylesheet" :href (assets/href cfg "css/identity.css")}])
   (when (rotates? cfg)
     [:script {:src (assets/href cfg "js/bodybg.js") :defer true}])))
