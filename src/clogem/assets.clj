;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.assets
  "The theme's static files as the build writes them, and their cache-busting
  fingerprints (D-P4-7).

  GitHub Pages serves everything with a 10-minute cache, so a release that
  changes `theme.css` used to render new HTML against the old stylesheet for
  up to ten minutes. Every theme asset URL the layout emits therefore carries
  `?v=<first 8 hex of the sha256 of the file bytes as written to dist/>` —
  a query, not a renamed file, so the file layout (and every link a site
  owner may have hard-coded) is exactly 0.2.0's.

  `files` is the single source of truth for those bytes: the export writes
  what it returns, and `version` hashes the same thing, so a fingerprint can
  never name bytes other than the ones on disk. Our own CSS is rewritten on
  the way out: a relative `url(…)` inside it (the Tamil font files) gets the
  `?v=` of the file it names, so a font change busts the font too.

  Two files are not copied from the theme's resources (Phase 4 Task B1):
  `icons.svg`, the sprite `clogem.sprite` builds from the vendored icon
  sources (which never ship themselves), and `overrides/custom.css`, the
  site's own `overrides/custom.css` when it has one (D-P4-8), copied as it
  is. Phase 4 Task C adds a third, `css/highlight.css`, generated from the
  Chroma styles when the build highlights (§11.3 item 10)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.highlight :as highlight]
            [clogem.sprite :as sprite]))

(defn theme-resource-dir
  "Locate the theme's static resources on the classpath, so they are found
  regardless of the working directory — which matters because the generator is
  normally invoked from the *site's* directory via `bb --config`."
  []
  (some-> (io/resource "clogem/theme/resources/css/theme.css")
          .toURI fs/path fs/parent fs/parent))

(def ^:private version-length 8)

(defn fingerprint
  "The first 8 hex characters of the sha256 of `bytes`."
  [^bytes bytes]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bytes)))
          0 version-length)))

(def ^:private css-url-re
  "A CSS `url(…)` with an optional quote. Group 1 is the quote, 2 the URL."
  #"url\(\s*(['\"]?)([^'\")\s]+)\1\s*\)")

(defn- relative-ref?
  "Is a `url(…)` value a file beside the stylesheet — not a data: URI, an
  absolute or scheme URL, or a fragment — and not already versioned?"
  [s]
  (not (or (re-find #"^(?i)(?:[a-z][a-z0-9+.-]*:|/|#)" s)
           (str/includes? s "?"))))

(defn- raw-bytes [f] (fs/read-all-bytes f))

(defn- unix-rel
  "Path `p` as a `/`-separated string: the form `files`' keys take, whatever
  separator the OS stringifies a path with (`\\` on Windows)."
  [p]
  (str/replace (str p) "\\" "/"))

(defn- rewrite-css
  "`css` (a string) with every relative `url(…)` given the `?v=` of the file
  it names, looked up with `version-of` (rel-path → version or nil). A
  reference to a file that is not exported is left as it is."
  [css dir version-of]
  (str/replace css css-url-re
               (fn [[whole q url]]
                 (let [[path frag] (str/split url #"#" 2)]
                   (if-let [v (and (relative-ref? url)
                                   (version-of (unix-rel (fs/normalize (fs/path dir path)))))]
                     (str "url(" q path "?v=" v (when frag (str "#" frag)) q ")")
                     whole)))))

(defn- exported-subdirs
  "The resource directories copied to `<out>/clogem/`. `icons/` is not one:
  its sources are built into `icons.svg` (`clogem.sprite`)."
  [cfg]
  (cond-> ["css" "js"]
    (= :self-hosted (get-in cfg [:theme :fonts :tamil])) (conj "fonts")))

(defn- skip?
  "A theme file this site does not ship (rel is `sub/name`): a script ships
  only to a site that loads it — search.js serves the Pagefind UI (D-P3-10),
  lang.js the language switcher's preference (D-P3-13), comments.js the
  giscus widget (D-P3-15)."
  [cfg rel]
  (case rel
    "js/search.js"   (not= :pagefind (get-in cfg [:search :provider]))
    "js/lang.js"     (<= (count (config/lang-keys cfg)) 1)
    "js/comments.js" (not= :giscus (get-in cfg [:comments :provider]))
    ;; §11.3 item 10: the copy button, unless :highlight :copy-button false
    "js/code.js"     (not (highlight/copy-button? cfg))
    false))

(def custom-css
  "D-P4-8: where the site's own stylesheet ships, under `<out>/clogem/`."
  "overrides/custom.css")

(defn custom-css-source
  "The site's `overrides/custom.css`, or nil when it has none."
  [cfg]
  (when-let [dir (:clogem/site-dir cfg)]
    (let [f (fs/path dir "overrides" "custom.css")]
      (when (fs/regular-file? f) f))))

(def highlight-css
  "§11.3 item 10: the token colours, generated from the Chroma styles."
  "css/highlight.css")

(defn- generated
  "The files built rather than copied: the icon sprite, the site's custom
  stylesheet when it has one (its bytes as they are — its `url(…)`s are the
  site's, relative to `overrides/`), and `css/highlight.css` when the build
  highlights (a Chroma session in `:clogem/highlight`, set by
  `clogem.render/render-site`)."
  [cfg]
  (cond-> {"icons.svg" (.getBytes ^String (sprite/sprite (sprite/brands-in-use cfg)) "UTF-8")}
    (custom-css-source cfg) (assoc custom-css (raw-bytes (custom-css-source cfg)))
    (:clogem/highlight cfg) (assoc highlight-css (highlight/stylesheet (:clogem/highlight cfg)))))

(defn files
  "{rel-path → bytes} for every theme file this site ships under
  `<out>/clogem/`, in the bytes the build writes: the i18n EDN maps are
  build-time inputs and never shipped; fonts ship only to a site that asked
  for them (D-P3-12); our CSS has its relative `url(…)`s versioned; the
  sprite and the site's custom stylesheet are `generated`. A sorted map, so
  the export order is stable."
  [cfg]
  (if-let [root (theme-resource-dir)]
    (let [raw (into (sorted-map)
                    (for [sub  (exported-subdirs cfg)
                          :let [from (fs/path root sub)]
                          :when (fs/directory? from)
                          p    (fs/glob from "**")
                          :when (fs/regular-file? p)
                          :let [rel (unix-rel (fs/relativize root p))]
                          :when (not (skip? cfg rel))]
                      [rel p]))
          ;; non-CSS files first: a stylesheet's url()s name them
          plain (into (sorted-map)
                      (for [[rel p] raw :when (not (str/ends-with? rel ".css"))]
                        [rel (raw-bytes p)]))
          v-of  (fn [rel] (some-> (get plain rel) fingerprint))]
      (-> plain
          (into (for [[rel p] raw :when (str/ends-with? rel ".css")]
                  [rel (.getBytes ^String (rewrite-css (String. ^bytes (raw-bytes p) "UTF-8")
                                                       (str (or (fs/parent rel) ""))
                                                       v-of)
                                  "UTF-8")]))
          (into (generated cfg))))
    (sorted-map)))

(defn versions
  "{rel-path → fingerprint} over a `files` map."
  [files]
  (into (sorted-map) (map (fn [[rel b]] [rel (fingerprint b)])) files))

(defn version
  "The fingerprint of theme file `rel` (`css/theme.css`): from the build's
  snapshot in `cfg` (`:clogem/asset-versions`, set by `clogem.render`),
  else computed from the files — a page rendered outside a build — else nil
  for a file the site does not ship."
  [cfg rel]
  (if-let [vs (:clogem/asset-versions cfg)]
    (get vs rel)
    (get (versions (files cfg)) rel)))

(defn href
  "The base-inclusive URL of theme file `rel`, with `?v=<fingerprint>`
  (D-P4-7). `clean-url` would append a slash to a file path, so the base is
  joined directly. Every theme asset URL goes through here, so an asset a
  later task adds is versioned for free."
  [cfg rel]
  (let [url (str/replace (str (config/base-path cfg) "/clogem/" rel) #"/{2,}" "/")]
    (if-let [v (version cfg rel)]
      (str url "?v=" v)
      url)))

(defn strip-versions
  "`s` with every `?v=…` this build adds removed — the inverse the tests use
  to compare a build against 0.2.0's bytes."
  [s]
  (str/replace (str s) #"\?v=[0-9A-Za-z._-]+" ""))
