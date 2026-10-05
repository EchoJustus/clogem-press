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
    clogem.dev-loop-test
    clogem.dev-wiring-test
    clogem.seo-test
    clogem.build-test
    clogem.write-test
    clogem.write-windows-test
    clogem.search-test
    clogem.tools-test
    clogem.lang-test
    clogem.comments-test
    clogem.theme-modes-test
    clogem.highlight-test])

(defn- highlighting-off-by-default!
  "§11.3 item 10 turns highlighting on by default, which would make every
  fixture site fetch Chroma. Tests must not need the network, so in a test
  run the DEFAULT provider is :none; a test that is about highlighting asks
  for `:highlight {:provider :chroma}` and runs a fake binary
  (`clogem.fake-tools/fake-chroma!`) or, when CLOGEM_CHROMA names one, the
  real one. `clogem.config/highlight-defaults` keeps the shipped default,
  and a `bb build` subprocess in a test is unaffected."
  []
  (require 'clogem.config)
  (alter-var-root (resolve 'clogem.config/defaults) assoc-in [:highlight :provider] :none))

(defn run
  [{:keys [pattern]}]
  (let [nses (cond->> test-namespaces
               pattern (filter #(str/includes? (str %) pattern)))]
    (highlighting-off-by-default!)
    (doseq [n nses] (require n))
    (let [{:keys [fail error] :as summary} (apply t/run-tests nses)]
      (println)
      (if (pos? (+ (or fail 0) (or error 0)))
        (do (println "FAILED") (System/exit 1))
        (println "All tests passed."))
      summary)))
