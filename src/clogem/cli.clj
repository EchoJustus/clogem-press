;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.cli
  "babashka.cli entry points, one per bb task.

  Option specs live in each var's :org.babashka/cli metadata (quickblog's
  ns-metadata spec pattern), so `bb <task> --help`, argument coercion and
  programmatic defaults all come from one definition."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.frontmatter :as fm]
            [clogem.i18n :as i18n]
            [clogem.model :as model]
            [clogem.pages :as pages]
            [clogem.render :as render]
            [clogem.scan :as scan]
            [clogem.search :as search]
            [clogem.tools :as tools]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Shared option spec

(def common-spec
  {:site-dir    {:desc "Directory holding site.edn and content/." :default "." :alias :d :ref "<dir>"}
   :config-file {:desc "Config filename inside --site-dir." :default "site.edn" :ref "<file>"}})

;; No `:default` on purpose (P4-D.2 item 5): a spec default is indistinguishable
;; from `--out dist` once parsed, and overrode `:build :out` in site.edn on
;; every run. The precedence is `--out`, then site.edn's `:build :out`, then
;; config's default, "dist".
(def ^:private out-desc "Output directory (default: site.edn's :build :out, else dist).")

(defn- load-cfg*
  "Load config under a diagnostic sink. Returns [cfg diagnostics]."
  [{:keys [site-dir config-file out no-write base no-search no-highlight]}]
  (diag/collecting
   (config/load-config site-dir config-file
                       (cond-> {}
                         out      (assoc-in [:build :out] out)
                         base     (assoc-in [:site :base] base)
                         no-write (assoc-in [:content :write-front-matter] false)
                         ;; --no-search is a render setting, not only "skip
                         ;; the indexer": pages that link a bundle the build
                         ;; never writes 404 on every load
                         no-search (assoc-in [:search :provider] :none)
                         ;; §11.3 item 10: no Chroma, no highlight.css
                         no-highlight (assoc-in [:highlight :provider] :none)))))

(defn load-cfg!
  "Load config; a config ERROR is fatal (D-P2-12).

  `config/validate!` reports through the same diagnostic sink as content
  problems and repairs the map so the rest of a `doctor` report stays readable —
  but repairing is not permission to proceed. It used to print \"the build will
  not run\" and then run anyway, exiting 0 with a `dist/` rendered under a
  language the site never configured. Now every task raises here, before a
  single file is read or written, so `build`, `doctor` and `fm-fix` all exit
  non-zero on a config error and `build` writes nothing.

  Warnings are printed and the config is returned. `doctor` uses `load-cfg*`
  instead so the config diagnostics land in its counted report."
  [opts]
  (let [[cfg ds] (load-cfg* opts)]
    (diag/print-all! ds)
    (diag/throw-on-errors! ds "config error")
    cfg))

;; ---------------------------------------------------------------------------
;; The pipeline
;;
;; scan → front matter → identity groups → (optional write-back) → model

(defn analyse
  "Everything up to and including the site model. Pure apart from reads.

  The files outside the numbered tree — `index*.md` and `@pages/*` — are
  resolved and parsed here too (`pages/site-files`), not at render time, so
  their errors gate `build` like every other content error."
  [cfg]
  (let [entries (scan/scan cfg)
        loaded  (model/load-entries cfg entries)
        ledger  (model/read-ledger cfg)]
    ;; D-P3-6: site string files are checked here, so build and doctor agree
    (i18n/check-site-strings! cfg)
    ;; D-P3-1: a site without a URL still builds; it just cannot say where it lives
    (when-not (config/site-url-root cfg)
      (diag/warn! (:clogem/config-file cfg)
                  (str ":site :url is blank, so no canonical or hreflang links, sitemap.xml, "
                       "Atom feeds or generated robots.txt are emitted.")
                  "Set it to the site's origin, e.g. \"https://example.github.io\" (DESIGN.md §6.6)."))
    (assoc (model/build-model cfg loaded ledger)
           :site-files (pages/site-files cfg))))

(defn- fill-plan
  "Which files auto-fill would touch, and with what. Computed from the model so
  that a variant's permalink is the group's, not a fresh mint (§6.2)."
  [cfg model]
  (let [extend-fm (get-in cfg [:content :extend-frontmatter])]
    (for [[_pl group] (:articles model)
          [lang variant] (:variants group)
          :let [resolved {:title      (:title variant)
                          :permalink  (:permalink group)
                          ;; M2: group facts come from the primary variant
                          :categories (:categories group)
                          :date       (when (= lang (:primary group)) (:date group))}
                additions (fm/compute-additions variant resolved extend-fm)]
          :when (seq additions)]
      {:path (:path variant) :rel-path (:rel-path variant) :additions additions})))

(defn- apply-fill!
  [plan]
  (let [changed (filterv (fn [{:keys [path additions]}] (fm/write-back! path additions)) plan)]
    (count changed)))

(defn- report!
  [ds]
  (diag/print-all! ds)
  (diag/throw-on-errors! ds)
  ds)

;; ---------------------------------------------------------------------------
;; Tasks

(defn option-error
  "babashka.cli's `:error-fn` for every task: a bad option — `--port abc`
  — is one line and exit 1, not babashka's dump of the whole spec
  (P4-D.3 item 20)."
  [{:keys [msg]}]
  (throw (ex-info (str "clogem-press: " msg " (`--help` lists the options).")
                  {:babashka/exit 1})))

(defn ^{:org.babashka/cli
        {:error-fn option-error
         :spec (merge common-spec
                      {:out      {:desc out-desc :alias :o :ref "<dir>"}
                       :base     {:desc "Site base path, e.g. /project/." :ref "<path>"}
                       :no-write {:desc "Read-only build: never touch source files." :coerce :boolean}
                       :no-search {:desc "Build without search (no index, no search UI) even when :search :provider is set."
                                   :coerce :boolean}
                       :no-highlight {:desc "Build without syntax highlighting (no Chroma) even when :highlight :provider is set."
                                      :coerce :boolean}})}}
  build
  "Render the site to the output directory (`dist/` by default)."
  [opts]
  ;; `:clogem/dev?` is set by `bb dev` (never on the command line): pages
  ;; render dev's ⟦key⟧ for a missing UI string, and search is left to dev's
  ;; background indexer (§11.3 item 12) — the result says `:search :deferred`
  ;; and carries the config the build used as `:clogem/cfg`. `:clogem/dev-loop?`
  ;; is set only by `bb dev` too, and makes a Chroma failure warn once and
  ;; render code plain instead of failing the build (§11.3 item 10).
  (let [dev? (boolean (:clogem/dev? opts))
        cfg  (cond-> (load-cfg! opts)
               dev? (assoc :clogem/dev? true)
               (or (:clogem/dev-loop? opts) (:clogem/dev? opts)) (assoc :clogem/dev-loop? true))]
    ;; Pass 1 — normalize front matter. Its diagnostics are discarded because
    ;; pass 2 re-derives them from the normalized tree and is the authoritative
    ;; report; but content ERRORS abort before anything is written, since
    ;; mutating source files on the strength of a tree we could not parse is the
    ;; one irreversible thing this program does.
    (when (config/write-front-matter? cfg)
      (let [[plan ds1] (diag/collecting (fill-plan cfg (analyse cfg)))]
        (diag/throw-on-errors! ds1)
        (let [n (apply-fill! plan)]
          (when (pos? n)
            (println (format "clogem-press: auto-filled front matter in %d file%s"
                             n (if (> n 1) "s" "")))))
        ;; D-P2-6: vdoing's three @pages/ files, created when missing, never
        ;; overwritten — the indexes render without them; the files exist so
        ;; the author has something to edit.
        (doseq [f (pages/ensure-files! cfg)]
          (println "clogem-press: created" f))))
    ;; Pass 2 — analyse, GATED before anything is written. `--no-write` skips
    ;; pass 1, so this is its only gate: a malformed YAML block or a
    ;; duplicate permalink used to be reported only after render/build! had
    ;; populated dist/. Every content ERROR — the numbered tree's, and
    ;; index*.md's and @pages/*'s, which analyse parses too — stops the build
    ;; here, before the ledger write and before dist/ exists. Render reports
    ;; only warnings.
    ;;
    ;; D-P4-11: the whole site is then rendered into memory — the site's
    ;; assets read, not just listed — BEFORE a single output file is written,
    ;; so an exception thrown during render (§11.2 item 2) or an unreadable
    ;; asset leaves dist/ exactly as it was, rather than a mix of this build's
    ;; pages and the last one's. The ledger is written only once dist/ has
    ;; been, so a failed write leaves permalinks.edn as it was too.
    ;; a UI string key no language defines is reported once per (key,
    ;; language) over analysis and render together, not once per page
    (let [missing    (atom #{})
          [m ads]    (diag/collecting
                      (binding [i18n/*missing-keys* missing]
                        (let [m (analyse cfg)]
                          ;; two outputs of this build that would collide —
                          ;; `/assets/demo.txt/` and the asset assets/demo.txt —
                          ;; are a content error, before anything is rendered
                          (render/check-output-collisions! m)
                          m)))
          _          (when (seq (diag/errors ads)) (report! ads))
          [result rds]
          (diag/collecting
           (let [written (binding [i18n/*missing-keys* missing]
                           (let [rendered (render/render-site cfg m)]
                             ;; `bb dev` stopping: nothing is written (P4-D.3 item 13)
                             (when (u/cancelled?) (throw (u/cancelled)))
                             (render/write-site! rendered)))]
             (i18n/report-missing-keys! cfg @missing)
             (when (config/write-front-matter? cfg)
               (model/write-ledger! cfg (model/ledger-from-model m)))
             written))
          ds         (into (vec ads) rds)]
      (report! ds)
      (println (format "clogem-press: %d pages (%d articles, %d variants) → %s (%d written, %d unchanged)"
                       (:pages result) (:articles result) (:variants result) (:out result)
                       (:written result) (:unchanged result)))
      (when (pos? (:stale result 0))
        (println (format "clogem-press: removed %d stale page%s from %s"
                         (:stale result) (if (= 1 (:stale result)) "" "s") (:out result))))
      (when (pos? (:stale-theme result 0))
        (println (format "clogem-press: removed %d stale file%s from %s/clogem"
                         (:stale-theme result) (if (= 1 (:stale-theme result)) "" "s") (:out result))))
      ;; D-P3-8: search runs last, over the finished dist/. A failure here is
      ;; a build error (exit 1) even though dist/ is already written — the
      ;; known limitation of §11.2, left for Phase 5: CI stops before
      ;; deploying it.
      ;; P4-D.2 item 20: what a `bb dev` no longer running left — staging
      ;; directories and previous bundles — goes with `--no-search` too
      (when-not dev? (search/sweep-staging! (config/out-dir cfg)))
      (cond
        (and dev? (search/enabled? cfg))
        (assoc result :search :deferred :clogem/cfg cfg)

        dev?
        (assoc result :clogem/cfg cfg)

        (search/enabled? cfg)
        (let [{:keys [languages pages]} (search/index! cfg)]
          (println (format "clogem-press: search index → %s/%s (%d language%s, %d pages)"
                           (:out result) search/output-subdir
                           languages (if (= 1 languages) "" "s") pages))
          (assoc result :search {:languages languages :pages pages}))

        :else
        result))))

(defn ^{:org.babashka/cli
        {:error-fn option-error
         :spec (assoc common-spec
                      :tool {:desc "The tool to fetch: pagefind, chroma or fswatcher."
                             :default "pagefind" :ref "<name>"})}}
  fetch-tool
  "Fetch, sha256-verify and cache a binary tool this site pins — Pagefind
  by default, or `--tool chroma` / `--tool fswatcher` — and print its path
  (§4; what CI exports as CLOGEM_PAGEFIND)."
  [opts]
  (let [id   (str/lower-case (str (or (:tool opts) "pagefind")))
        tool (get tools/descriptors (keyword id))
        _    (when-not tool
               (throw (ex-info (str "clogem-press: fetch-tool: unknown tool " (pr-str id) "; "
                                    "the tools are " (str/join ", " (map name (sort (keys tools/descriptors)))) ".")
                               {:babashka/exit 1})))
        cfg  (load-cfg! opts)
        bin  (if (= :pagefind (:id tool))
               (search/ensure-binary! cfg)
               (tools/ensure-binary! tool cfg))]
    (println bin)
    bin))

(defn ^{:org.babashka/cli {:error-fn option-error :spec (assoc common-spec
                                        :dry-run {:desc "Report what would be written, write nothing."
                                                  :coerce :boolean})}}
  fm-fix
  "Write missing front matter into source files."
  [opts]
  (let [cfg (load-cfg! opts)
        [plan ds] (diag/collecting (fill-plan cfg (analyse cfg)))]
    (report! ds)
    (if (:dry-run opts)
      (do (doseq [{:keys [rel-path additions]} plan]
            (println rel-path "→" (str/join ", " (map (comp name first) additions))))
          (println (format "clogem-press: %d file(s) would be normalized" (count plan))))
      (let [n (apply-fill! plan)
            created (pages/ensure-files! cfg)]
        (let [[m ds2] (diag/collecting (analyse cfg))]
          (diag/print-all! ds2)
          (model/write-ledger! cfg (model/ledger-from-model m)))
        (doseq [f created] (println "clogem-press: created" f))
        (println (format "clogem-press: normalized %d file(s)" (+ n (count created))))))
    plan))

(defn ^{:org.babashka/cli {:error-fn option-error :spec common-spec}}
  doctor
  "Report content problems without building."
  [opts]
  ;; Config diagnostics are part of the report, not a gate in front of it: a
  ;; bad :langs :default is repaired by validate! precisely so the content
  ;; findings below are still produced. The exit status counts them all the
  ;; same — D-P2-12: a config error is an error.
  (let [[cfg cds] (load-cfg* opts)
        [m ds] (diag/collecting
                (i18n/reporting-missing-keys
                 cfg
                 (let [m (analyse cfg)]
                   ;; findings that must not fire inside analyse (undated
                   ;; articles, disagreeing variants, over-deep directories,
                   ;; unresolved catalogue paths) …
                   (model/doctor-checks! m)
                   ;; … and the render-time ones (dead links, unknown
                   ;; containers, bad card-list YAML), by rendering every page
                   ;; in memory and discarding it
                   (when (seq (:articles m)) (render/check-pages! m))
                   ;; … and unknown code languages, with a Chroma already
                   ;; here (never downloaded), §11.3 item 10
                   (render/check-code-languages! m)
                   ;; … and outputs of one build that would collide
                   (render/check-output-collisions! m)
                   m)))
        ds    (into (vec cds) ds)
        errs  (diag/errors ds)
        warns (diag/warnings ds)]
    (diag/print-all! ds)
    (println (format "clogem-press doctor: %d article(s), %d variant(s), %d warning(s), %d error(s)"
                     (count (:articles m))
                     (reduce + (map #(count (:variants %)) (vals (:articles m))))
                     (count warns) (count errs)))
    (when (seq errs)
      ;; :babashka/exit makes bb exit with that status after printing the
      ;; message; raising (rather than System/exit) keeps `doctor` testable
      ;; in-process, and is the same mechanism `build` already uses.
      (throw (ex-info (format "clogem-press doctor: %d error(s)" (count errs))
                      {:clogem/errors errs :clogem/warnings warns :babashka/exit 1})))
    {:warnings warns :errors errs}))

(defn ^{:org.babashka/cli {:error-fn option-error :spec (assoc common-spec
                                        :out {:desc out-desc :alias :o :ref "<dir>"})}}
  clean
  "Remove the output directory: only one a build marked as its own
  (`.clogem-output`), and never one holding a `.git` (a deploy worktree);
  either is refused, saying how to clean it (P4-D.4 item 3)."
  [opts]
  (let [cfg  (load-cfg! opts)
        out  (config/out-dir cfg)
        git? #(or (fs/exists? (fs/path out ".git") {:nofollow-links true})
                  (some (fn [p] (= ".git" (str (fs/file-name p))))
                        (fs/glob out "**" {:hidden true})))
        refuse! (fn [why hint]
                  (throw (ex-info (str "clogem-press: " out " " why ", so `bb clean` will not delete it.\n"
                                       "  hint: " hint)
                                  {:babashka/exit 1})))]
    (cond
      (not (fs/exists? out))
      (println "clogem-press:" (str out) "does not exist; nothing to remove")

      (not (config/out-owned? out))
      (refuse! (str "has no " config/out-marker ", which every build writes there")
               (str "Build once to mark it, or delete it yourself (rm -r " out ")."))

      (git?)
      (refuse! "holds a .git (a deploy worktree?)"
               (str "Delete what you want gone yourself; `bb build` overwrites the pages it writes anyway."))

      :else
      (do (fs/delete-tree out)
          (println "clogem-press: removed" (str out))))))

(def server-spec
  "The options `serve` and `dev` share: where to listen (§11.3 item 12)."
  {:port {:desc "Port." :default 1888 :coerce :long :alias :p}
   :host {:desc "Address to listen on; 0.0.0.0 for every interface." :default "127.0.0.1" :ref "<addr>"}})

(defn ^{:org.babashka/cli
        {:error-fn option-error
         :spec (merge common-spec server-spec
                      ;; no :default (P4-D.3 item 2): it hid site.edn's :build :out
                      {:dir  {:desc "Directory to serve (default: --out, else site.edn's :build :out, else dist)." :ref "<dir>"}
                       :out  {:desc "Output directory to serve, from --site-dir, as `build --out` names it." :alias :o :ref "<dir>"}
                       :base {:desc "Serve it at this path, e.g. /project/ (default: the site's :base when --site-dir has a site.edn, else /)."
                              :ref "<path>"}})}}
  serve
  "Serve an already-built output directory."
  [opts]
  ((requiring-resolve 'clogem.dev/serve!) opts))

(defn ^{:org.babashka/cli
        {:error-fn option-error
         :spec (merge common-spec server-spec
                      {:out      {:desc out-desc :alias :o :ref "<dir>"}
                       :base     {:desc "Site base path, e.g. /project/; the site is served there." :ref "<path>"}
                       :poll     {:desc "Poll the filesystem instead of using inotify."
                                  :coerce :boolean}
                       :no-search {:desc "Build without search (no index, no search UI)."
                                   :coerce :boolean}
                       :no-write {:desc "Never write front matter or permalinks.edn back to the source tree."
                                  :coerce :boolean}
                       :reload-code {:desc "Also watch the theme's .clj code and reload it on change."
                                     :coerce :boolean}
                       :no-highlight {:desc "Render code without syntax highlighting (no Chroma)."
                                      :coerce :boolean}
                       :interval {:desc "Poll interval in ms." :default 500 :coerce :long}
                       :probe-ms {:desc "How long to wait for the watcher to prove it delivers events."
                                  :default 3000 :coerce :long}})}}
  dev
  "Build, serve, and rebuild on change."
  [opts]
  ((requiring-resolve 'clogem.dev/dev!) opts))

(defn ^{:org.babashka/cli {:error-fn option-error :spec {:pattern {:desc "Only run test namespaces matching this substring."
                                            :ref "<str>"}}}}
  test-cmd
  "Run the test suite."
  [opts]
  ((requiring-resolve 'clogem.test-runner/run) opts))

;; ---------------------------------------------------------------------------
;; --help (P4-D.4 item 5)

(defn help-text
  "What `bb <task> --help` prints for the task var `v`: its usage, the
  first paragraph of its docstring and its options, from the spec in its
  `:org.babashka/cli` metadata."
  [v]
  (let [m    (meta v)
        task (str/replace (str (:name m)) #"-cmd$" "")
        doc  (first (str/split (str (:doc m)) #"\n\s*\n"))]
    (str "Usage: bb " task " [options]\n"
         (when-not (str/blank? doc)
           (str "\n" (str/join "\n" (map str/trim (str/split-lines doc))) "\n"))
         "\nOptions:\n"
         (bcli/format-opts {:spec (get-in m [:org.babashka/cli :spec])})
         "\n")))

(defn with-help
  "Wrap the task function `f` of the var `v`: on `--help` or `-h` it prints
  `help-text` and returns nil, before anything else runs. No task read its
  options' `:help`, so `bb clean --help` deleted the output directory."
  [f v]
  (fn [opts]
    (if (or (:help opts) (:h opts))
      (print (help-text v))
      (f opts))))

(def ^:private tasks
  "Every task bb.edn runs, each wrapped once by `with-help`."
  [#'build #'dev #'serve #'doctor #'clean #'fm-fix #'fetch-tool #'test-cmd])

(doseq [v tasks]
  (when-not (::help? (meta @v))
    (alter-var-root v (fn [f] (with-meta (with-help f v) {::help? true})))))
