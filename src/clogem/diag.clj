;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.diag
  "Diagnostics collection.

  Every phase of the pipeline reports problems through here rather than printing
  directly, so that (a) `doctor` and `build` share one implementation, (b) errors
  accumulate and are reported together instead of failing on the first one, and
  (c) tests can assert on structured diagnostics instead of scraping stderr.

  Three levels:

    :info   — worth saying once, never actionable
    :warn   — vdoing's warn-and-skip behaviour, plus every `doctor` finding
    :error  — the build must not produce output; collected, then raised in bulk

  DESIGN.md §6.1 specifies a hard error for near-miss language suffixes. Failing
  at the *end of the scan* with every offender listed, rather than at the first,
  is a deliberate refinement: an author who renamed five files wants five
  suggestions, not five build cycles."
  (:require [clojure.string :as str]))

(def ^:dynamic *sink*
  "Atom holding a vector of diagnostic maps, or nil to print immediately."
  nil)

(defn format-diagnostic
  [{:keys [level path message hint]}]
  (str (case level :info "info" :warn "warning" :error "error") ": "
       (when path (str path ": "))
       message
       (when hint (str "\n         " hint))))

(defn emit!
  [level path message & [hint]]
  (let [d (cond-> {:level level :message message}
            path (assoc :path (str path))
            hint (assoc :hint hint))]
    (if *sink*
      (swap! *sink* conj d)
      (binding [*out* *err*] (println (format-diagnostic d))))
    d))

(defn info!  [path msg & [hint]] (emit! :info  path msg hint))
(defn warn!  [path msg & [hint]] (emit! :warn  path msg hint))
(defn error! [path msg & [hint]] (emit! :error path msg hint))

(defn errors   [ds] (filterv #(= :error (:level %)) ds))
(defn warnings [ds] (filterv #(= :warn  (:level %)) ds))

(defn print-all!
  "Print diagnostics to stderr, most severe last so the tail of the log is the
  part that matters. Returns the diagnostics unchanged."
  [ds]
  (binding [*out* *err*]
    (doseq [level [:info :warn :error]
            d     (filter #(= level (:level %)) ds)]
      (println (format-diagnostic d))))
  ds)

(defn throw-on-errors!
  "Raise if any diagnostic is an :error, with every error in the message.
  `what` names the kind of error in the summary line (\"content error\" by
  default; `clogem.cli/load-cfg!` passes \"config error\")."
  ([ds] (throw-on-errors! ds "content error"))
  ([ds what]
   (let [es (errors ds)]
     (when (seq es)
       (throw (ex-info (str (count es) " " what (when (> (count es) 1) "s") ":\n"
                            (str/join "\n" (map #(str "  - " (format-diagnostic %)) es)))
                       {:clogem/errors es
                        :babashka/exit  1}))))
   ds))

(defmacro collecting
  "Run body with a fresh diagnostic sink. Returns [result diagnostics]."
  [& body]
  `(let [sink# (atom [])]
     (binding [*sink* sink#]
       (let [r# (do ~@body)]
         [r# @sink#]))))
