;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.test-runner
  "Minimal clojure.test runner for `bb test`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(def test-namespaces
  '[clogem.util-test
    clogem.config-test
    clogem.scan-test
    clogem.frontmatter-test
    clogem.pages-test
    clogem.markdown-test
    clogem.containers-test
    clogem.model-test
    clogem.render-test
    clogem.ledger-test
    clogem.dev-test
    clogem.seo-test
    clogem.build-test
    clogem.search-test])

(defn run
  [{:keys [pattern]}]
  (let [nses (cond->> test-namespaces
               pattern (filter #(str/includes? (str %) pattern)))]
    (doseq [n nses] (require n))
    (let [{:keys [fail error] :as summary} (apply t/run-tests nses)]
      (println)
      (if (pos? (+ (or fail 0) (or error 0)))
        (do (println "FAILED") (System/exit 1))
        (println "All tests passed."))
      summary)))
