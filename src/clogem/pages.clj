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
  generated list).

  Those files, and the homepage's `index.md` / `index.<lang>.md`, sit outside
  the numbered tree, so the scanner never reads them. `site-files` resolves
  and parses every one of them during ANALYSE, so a malformed YAML block or
  two files claiming the same language is a content error that stops `build`
  before `dist/` exists, reported once per file; render reads the cached
  front matter and body from the model."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.frontmatter :as fm]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.util :as u]))

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
  `localized-file` expects it."
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

;; ---------------------------------------------------------------------------
;; Files outside the numbered tree: index.md and @pages/

(defn- suffixed-files
  "Every `<rel>.<suffix>.md` whose suffix names `lang`, matched
  case-insensitively as the scanner matches suffixes (§6.1). The exact
  canonical spelling `<rel>.<lang>.md` sorts first; the rest follow by name."
  [cfg rel lang]
  (let [base   (fs/path (config/content-dir cfg) rel)
        parent (fs/parent base)
        prefix (str (fs/file-name base) ".")
        exact  (str prefix (name lang) ".md")]
    (when (fs/directory? parent)
      (->> (fs/list-dir parent)
           (filter (fn [p]
                     (let [fname (str (fs/file-name p))]
                       (and (str/starts-with? fname prefix)
                            (str/ends-with? (u/lower fname) ".md")
                            (> (count fname) (+ (count prefix) 3))
                            (fs/regular-file? p)
                            (= lang (config/lang-for-suffix
                                     cfg (subs fname (count prefix) (- (count fname) 3))))))))
           (sort-by (fn [p] (let [fname (str (fs/file-name p))]
                              [(if (= fname exact) 0 1) fname])))
           vec))))

(defn localized-file
  "The file a language reads for `content/<rel>.md`, the convention
  `index.md` / `index.zh-Hans.md` and `@pages/*.md` share:

    {:path <rel>.<suffix>.md :own? true}   when a file whose suffix names
                                           `lang` exists: the exact canonical
                                           `<rel>.<lang>.md` first, else a
                                           case-insensitive match, as the
                                           scanner matches suffixes (§6.1), so
                                           `index.zh-hant.md` counts for zh-Hant
    {:path <rel>.md :own? <default?>}      else the unsuffixed file, which is
                                           the language's OWN only on the site
                                           default language
    nil                                    when neither exists

  When more than one file names `lang` (`index.zh-Hant.md` beside
  `index.ZH-HANT.md`) the map also carries `:ambiguous`, every one of them;
  `site-files` reports that as an error, as the content tree does."
  [cfg rel lang]
  (let [base  (fs/path (config/content-dir cfg) (str rel ".md"))
        found (suffixed-files cfg rel lang)]
    (cond
      (seq found)        (cond-> {:path (first found) :own? true}
                           (next found) (assoc :ambiguous found))
      (fs/exists? base)  {:path base :own? (= lang (config/default-lang cfg))}
      :else              nil)))

(defn site-file-rels
  "The content-relative base names read outside the numbered tree: the
  homepage and every enabled index kind's `@pages/` file."
  [cfg]
  (into ["index"] (map file-rel) (enabled-kinds cfg)))

(defn site-files
  "{[rel lang] → {:path :own? :front-matter :body}} for every language and
  every `site-file-rels` entry that resolves to a file. Called from
  `clogem.cli/analyse`: each distinct file is parsed exactly ONCE, so its
  YAML error is one diagnostic however many languages fall back to it, and
  two files naming the same language are one error naming both. An
  `@pages/` file whose `permalink:` is unsafe (`u/unsafe-permalink?`) is an
  error naming it, as for an article, and the permalink is dropped so its
  index keeps its default path."
  [cfg]
  (let [content  (config/content-dir cfg)
        rel-name (fn [p] (str (fs/relativize content p)))
        resolved (for [rel  (site-file-rels cfg)
                       lang (config/lang-keys cfg)
                       :let [lf (localized-file cfg rel lang)]
                       :when lf]
                   [[rel lang] lf])
        _        (doseq [[[rel lang] {paths :ambiguous}] resolved
                         :when paths]
                   (diag/error! (rel-name (first paths))
                                (str "two files claim to be the " (name lang) " version of "
                                     rel ".md: " (str/join " and " (map rel-name paths)))
                                "Keep one spelling; until then the exact canonical suffix is the one read."))
        parsed   (reduce (fn [m p]
                           (let [k (str p)]
                             (if (contains? m k)
                               m
                               (let [parts (fm/read-file p)
                                     pl    (get-in parts [:front-matter :permalink])]
                                 (assoc m k
                                        (if (and (some? pl)
                                                 (str/starts-with? (rel-name p) "@pages")
                                                 (u/unsafe-permalink? pl))
                                          (do (model/unsafe-permalink-error! (rel-name p) pl)
                                              (update parts :front-matter dissoc :permalink))
                                          parts))))))
                         {} (map (comp :path second) resolved))]
    (into {}
          (map (fn [[k {:keys [path own?]}]]
                 (let [parts (get parsed (str path))]
                   [k {:path path :own? own?
                       :front-matter (or (:front-matter parts) {})
                       :body (:body parts)}])))
          resolved)))
