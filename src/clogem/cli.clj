;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.cli
  "babashka.cli entry points, one per bb task.

  Option specs live in each var's :org.babashka/cli metadata (quickblog's
  ns-metadata spec pattern), so `bb <task> --help`, argument coercion and
  programmatic defaults all come from one definition."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.frontmatter :as fm]
            [clogem.model :as model]
            [clogem.pages :as pages]
            [clogem.render :as render]
            [clogem.scan :as scan]))

;; ---------------------------------------------------------------------------
;; Shared option spec

(def common-spec
  {:site-dir    {:desc "Directory holding site.edn and content/." :default "." :alias :d :ref "<dir>"}
   :config-file {:desc "Config filename inside --site-dir." :default "site.edn" :ref "<file>"}})

(defn- load-cfg*
  "Load config under a diagnostic sink. Returns [cfg diagnostics]."
  [{:keys [site-dir config-file out no-write base]}]
  (diag/collecting
   (config/load-config site-dir config-file
                       (cond-> {}
                         out      (assoc-in [:build :out] out)
                         base     (assoc-in [:site :base] base)
                         no-write (assoc-in [:content :write-front-matter] false)))))

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
  "Everything up to and including the site model. Pure apart from reads."
  [cfg]
  (let [entries (scan/scan cfg)
        loaded  (model/load-entries cfg entries)
        ledger  (model/read-ledger cfg)]
    (model/build-model cfg loaded ledger)))

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

(defn ^{:org.babashka/cli
        {:spec (merge common-spec
                      {:out      {:desc "Output directory." :default "dist" :alias :o :ref "<dir>"}
                       :base     {:desc "Site base path, e.g. /project/." :ref "<path>"}
                       :no-write {:desc "Read-only build: never touch source files." :coerce :boolean}})}}
  build
  [opts]
  (let [cfg (load-cfg! opts)]
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
    (let [[result ds]
          (diag/collecting
           (let [m (analyse cfg)]
             (when (config/write-front-matter? cfg)
               (model/write-ledger! cfg (model/ledger-from-model m)))
             (render/build! cfg m)))]
      (report! ds)
      (println (format "clogem-press: %d pages (%d articles, %d variants) → %s"
                       (:pages result) (:articles result) (:variants result) (:out result)))
      result)))

(defn ^{:org.babashka/cli {:spec (assoc common-spec
                                        :dry-run {:desc "Report what would be written, write nothing."
                                                  :coerce :boolean})}}
  fm-fix
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

(defn ^{:org.babashka/cli {:spec common-spec}}
  doctor
  [opts]
  ;; Config diagnostics are part of the report, not a gate in front of it: a
  ;; bad :langs :default is repaired by validate! precisely so the content
  ;; findings below are still produced. The exit status counts them all the
  ;; same — D-P2-12: a config error is an error.
  (let [[cfg cds] (load-cfg* opts)
        [m ds] (diag/collecting
                (let [m (analyse cfg)]
                  ;; findings that must not fire inside analyse (undated
                  ;; articles, disagreeing variants, over-deep directories,
                  ;; unresolved catalogue paths, slug collisions) …
                  (model/doctor-checks! m)
                  ;; … and the render-time ones (dead links, unknown
                  ;; containers, bad card-list YAML), by rendering every page
                  ;; in memory and discarding it
                  (when (seq (:articles m)) (render/check-pages! m))
                  m))
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
                      {:clogem/errors errs :babashka/exit 1})))
    {:warnings warns :errors errs}))

(defn ^{:org.babashka/cli {:spec (assoc common-spec
                                        :out {:desc "Output directory." :default "dist" :alias :o})}}
  clean
  [opts]
  (let [cfg (load-cfg! opts)
        out (config/out-dir cfg)]
    (when (fs/exists? out) (fs/delete-tree out))
    (println "clogem-press: removed" (str out))))

(defn ^{:org.babashka/cli
        {:spec {:dir  {:desc "Directory to serve." :default "dist" :ref "<dir>"}
                :port {:desc "Port." :default 1888 :coerce :long :alias :p}}}}
  serve
  [opts]
  ((requiring-resolve 'clogem.dev/serve!) opts))

(defn ^{:org.babashka/cli
        {:spec (merge common-spec
                      {:out      {:desc "Output directory." :default "dist" :alias :o}
                       :port     {:desc "Port." :default 1888 :coerce :long :alias :p}
                       :poll     {:desc "Poll the filesystem instead of using inotify."
                                  :coerce :boolean}
                       :interval {:desc "Poll interval in ms." :default 500 :coerce :long}
                       :probe-ms {:desc "How long to wait for the watcher to prove it delivers events."
                                  :default 3000 :coerce :long}})}}
  dev
  [opts]
  ((requiring-resolve 'clogem.dev/dev!) opts))

(defn ^{:org.babashka/cli {:spec {:pattern {:desc "Only run test namespaces matching this substring."
                                            :ref "<str>"}}}}
  test-cmd
  [opts]
  ((requiring-resolve 'clogem.test-runner/run) opts))
