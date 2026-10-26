;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.search-all-test
  "Cross-language search (Phase 4 Task G, §11.3 item 17, D-P4-17): the
  `:search :cross-language` option, the merge data each page carries, and
  the two files that serve it. The built pages are asserted in
  `clogem.search-test`, beside the rest of the search markup."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; 1. Config (`:search :cross-language`)

(defn- load-site
  "Write a site.edn into a temp dir and load it; [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-xlang"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- warnings-of [edn]
  (let [[cfg ds] (load-site edn)]
    (is (empty? (diag/errors ds)) "never an error")
    [cfg (mapv :message (diag/warnings ds))]))

(defn- hints-of [edn]
  (mapv :hint (diag/warnings (second (load-site edn)))))

(deftest the-key-is-read-not-planned
  (testing "G flips it to :ok, so a site that sets it is no longer told it has no effect"
    (is (= :ok (get-in config/known-keys [[:search] :cross-language])))
    (let [[cfg ws] (warnings-of {:search {:provider :pagefind :cross-language true}})]
      (is (= [] ws) "no \"planned, not implemented\" text")
      (is (true? (config/cross-language? cfg))))))

(deftest nil-and-false-are-off-and-silent
  (doseq [edn [{:search {:provider :pagefind}}
               {:search {:provider :pagefind :cross-language false}}]]
    (let [[cfg ws] (warnings-of edn)]
      (is (= [] ws) (pr-str edn))
      (is (false? (config/cross-language? cfg)) (pr-str edn)))))

(deftest anything-else-is-one-warning-repaired-to-off
  (doseq [v ["yes" 1 :true]]
    (let [[cfg ws] (warnings-of {:search {:provider :pagefind :cross-language v}})]
      (is (= [(str ":search :cross-language is " (pr-str v)
                   ", but it must be true or false; cross-language search is off.")]
             ws)
          (pr-str v))
      (is (false? (config/cross-language? cfg)) (pr-str v))
      (is (false? (get-in cfg [:search :cross-language]))
          "repaired with assoc-in: no entry is added to config/defaults"))))

(deftest the-feature-is-off-without-pagefind
  (testing "--no-search sets :provider :none; `true` under it says nothing"
    (let [[cfg ws] (warnings-of {:search {:provider :none :cross-language true}})]
      (is (= [] ws))
      (is (false? (config/cross-language? cfg))))))

(deftest the-vdoing-hint-lists-the-key
  (testing "a misspelling is answered with the real key, and the options now name it"
    (let [[_ ws] (warnings-of {:search {:provider :pagefind :crossLanguage true}})
          h (first (hints-of {:search {:provider :pagefind :crossLanguage true}}))]
      (is (= [":search :crossLanguage is not a search option and is ignored."] ws))
      (is (str/includes? (str h) "Did you mean :cross-language?") h)
      (is (str/includes? (str h) "The options are :cross-language, :provider") h))))

(deftest the-example-and-the-demo-stay-quiet
  (doseq [f ["config.example.edn" "examples/demo-site/site.edn"]]
    (let [[_ ws] (warnings-of (-> (clojure.edn/read-string (slurp f))
                                  (assoc-in [:build :out] "dist")))]
      (is (= [] ws) f))))
