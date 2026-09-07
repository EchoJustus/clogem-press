;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.pages
  "The `@pages/` index-page files (DESIGN.md §1.1, D-P2-6).

  vdoing auto-creates `categoriesPage.md`, `tagsPage.md` and `archivesPage.md`
  under `@pages/` on every build, each carrying a flag key, a title, a fixed
  permalink and `article: false`. clogem-press does the same, under the same
  rules as front-matter auto-fill: only when `:write-front-matter` is on,
  never overwriting, idempotent, and only for the index systems the
  `:content` toggles enable. The rendered index pages do not DEPEND on these
  files — a `--no-write` build renders the indexes from nothing — but a
  file, once present, contributes its `permalink:` (the index root), its
  `title:` (on its own language's page) and its body (rendered above the
  generated list)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]))

(def index-kinds
  "The three index systems. `:toggle` is the `:content` key that switches
  each off; `:default-path` is the vdoing permalink, which the `@pages/`
  file's own `permalink:` may override; `:title-key` is the theme string."
  [{:kind :categories :toggle :category :flag "categoriesPage" :default-path "/categories/" :title-key :index/categories}
   {:kind :tags       :toggle :tag      :flag "tagsPage"       :default-path "/tags/"       :title-key :index/tags}
   {:kind :archives   :toggle :archive  :flag "archivesPage"   :default-path "/archives/"   :title-key :index/archives}])

(defn enabled-kinds
  [cfg]
  (filter #(get-in cfg [:content (:toggle %)] true) index-kinds))

(defn file-rel
  "`@pages/<flag>` — the content-relative base name (no extension), as
  `clogem.render/localized-file` expects it."
  [{:keys [flag]}]
  (str "@pages/" flag))

(defn generated-content
  "The exact bytes of a freshly generated `@pages/` file: the flag key,
  `title:` in the site-default language, `permalink:` and `article: false` —
  vdoing's own four keys, in its order. Pinned by golden files."
  [cfg strings {:keys [flag default-path title-key]}]
  (let [ctx   {:cfg cfg :lang (config/default-lang cfg) :strings strings}
        title (i18n/tr ctx title-key)
        title (if (re-find #"[:#\[\]{}&*!|>'\"%@`,]|^[-?]|^\s|\s$" title)
                (str \" (str/replace title "\"" "\\\"") \")
                title)]
    (str "---\n"
         flag ": true\n"
         "title: " title "\n"
         "permalink: " default-path "\n"
         "article: false\n"
         "---\n")))

(defn ensure-files!
  "Create every missing enabled `@pages/` file. Returns the relative paths
  created (empty when everything already existed). Never overwrites, so a
  second call is a no-op — the same idempotence front-matter auto-fill relies
  on for §7.2's loop guard 3."
  [cfg]
  (when (config/write-front-matter? cfg)
    (let [strings (i18n/load-strings cfg)
          dir     (fs/path (config/content-dir cfg) "@pages")]
      (vec
       (for [{:keys [flag] :as kind} (enabled-kinds cfg)
             :let [f (fs/path dir (str flag ".md"))]
             :when (not (fs/exists? f))]
         (do (fs/create-dirs dir)
             (spit (fs/file f) (generated-content cfg strings kind))
             (str "@pages/" flag ".md")))))))
