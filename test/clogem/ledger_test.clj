;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.ledger-test
  "`permalinks.edn` round-tripping.

  The ledger is an auxiliary (§7.2 mechanism (b)), but one of its three jobs —
  the D-15 tombstone register — is the only record that a retired URL ever
  existed. Losing it is irreversible: nothing else in the repo remembers a
  permalink that no file declares any more. So the contract is a golden one: a
  build rewrites the ledger and every tombstone must come out the other side."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.model :as model]))

(def demo "examples/demo-site")

(def tombstones
  "A ledger the owner has been accumulating: a deleted article and one whose
  permalink was re-pointed."
  {"/pages/dead01/" {:retired "2026-03-04 10:00:00"
                     :reason  "article deleted"}
   "/pages/dead02/" {:retired "2026-05-06 11:00:00"
                     :reason  "permalink changed"
                     :redirect "/pages/643259/"}})

(defn- copy-demo []
  (let [dir (fs/create-temp-dir {:prefix "clogem-ledger"})
        dst (fs/path dir "site")]
    (fs/copy-tree demo dst)
    dst))

(defn- ledger-file [site] (fs/file (fs/path site "permalinks.edn")))

(defn- build! [site]
  (diag/collecting (cli/build {:site-dir (str site) :out (str (fs/path site "dist"))})))

(deftest tombstones-survive-a-build
  (testing "D-15: :tombstones is read from the ledger, carried through the model,
            and written back — not silently reset to {} on every build"
    (let [site (copy-demo)]
      (try
        ;; Seed the ledger through the real writer, so the "before" bytes are
        ;; exactly what clogem-press itself would have produced.
        (let [cfg    (first (diag/collecting (config/load-config (str site))))
              seeded (assoc (model/read-ledger cfg) :tombstones tombstones)]
          (model/write-ledger! cfg seeded))
        (let [before (slurp (ledger-file site))]
          (build! site)
          (let [after (slurp (ledger-file site))]
            (is (= tombstones (:tombstones (edn/read-string after)))
                "every tombstone survives")
            (is (= before after)
                "and the whole ledger is byte-identical — the demo tree is
                 already normalized, so a build introduces no legitimate new
                 entries either")))
        (finally (fs/delete-tree (fs/parent site)))))))

(deftest tombstones-survive-alongside-a-new-article
  (testing "…and a legitimately new entry is the ONLY difference"
    (let [site (copy-demo)]
      (try
        (let [cfg (first (diag/collecting (config/load-config (str site))))]
          (model/write-ledger! cfg (assoc (model/read-ledger cfg) :tombstones tombstones)))
        (let [before (edn/read-string (slurp (ledger-file site)))]
          (spit (fs/file (fs/path site "content" "01.Guide" "10.Basics" "40.brand-new.md"))
                "---\ntitle: Brand new\npermalink: /pages/brandnew/\n---\n\nbody\n")
          (build! site)
          (let [after (edn/read-string (slurp (ledger-file site)))]
            (is (= tombstones (:tombstones after)))
            (is (= (conj (set (keys (:permalinks before))) "/pages/brandnew/")
                   (set (keys (:permalinks after))))
                "exactly one new permalink, nothing dropped")))
        (finally (fs/delete-tree (fs/parent site)))))))

(deftest an-absent-ledger-still-writes-an-empty-tombstone-map
  (testing "the no-ledger case must not regress into writing nil"
    (let [site (copy-demo)]
      (try
        (fs/delete (ledger-file site))
        (build! site)
        (is (= {} (:tombstones (edn/read-string (slurp (ledger-file site))))))
        (finally (fs/delete-tree (fs/parent site)))))))

(deftest the-ledger-is-serialized-canonically
  (testing "permalinks.edn is committed, so its bytes must be a function of its
            content alone — a rewrite that changed nothing must land as no diff,
            whatever map ordering the caller happened to hand the writer"
    (let [site (copy-demo)]
      (try
        (let [cfg    (first (diag/collecting (config/load-config (str site))))
              ledger (assoc (model/read-ledger cfg) :tombstones tombstones)]
          (model/write-ledger! cfg ledger)
          (let [a (slurp (ledger-file site))]
            ;; shuffle the maps into a different iteration order and rewrite
            (model/write-ledger! cfg (-> ledger
                                         (update :permalinks #(into {} (reverse (seq %))))
                                         (update :tombstones #(into {} (reverse (seq %))))))
            (is (= a (slurp (ledger-file site))))))
        (finally (fs/delete-tree (fs/parent site)))))))
