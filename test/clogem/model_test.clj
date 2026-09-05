;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.model-test
  "Identity-group resolution — the rule the whole i18n design rests on."
  (:require [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.model :as model]
            [clogem.util :as u]))

(def cfg (first (diag/collecting (config/load-config "test/fixtures/__no_such_site__"))))

(defn- entry
  [{:keys [dir order base title lang permalink kind]
    :or {dir "/01.Guide/10.Basics" order 2 base "conventions" kind :tree}}]
  {:kind kind :dir-key dir :order order
   :base-title base
   :title (or title base)
   :lang (or lang (config/default-lang cfg))
   :declared-permalink permalink
   :categories ["Guide" "Basics"]
   :front-matter {}
   :rel-path (str (subs dir 1) "/" order "." base
                  (when lang (str "." (name lang))) ".md")
   :path (str "/tmp" dir "/" order "." base ".md")})

(defn- build [entries]
  (diag/collecting
   (let [[es _] (model/resolve-permalinks cfg entries model/empty-ledger)]
     (model/build-groups cfg es))))

;; ---------------------------------------------------------------------------
;; Mechanism 1 — implicit identity

(deftest variants-share-one-minted-permalink
  (testing "§6.2: a new variant does NOT mint; it inherits the group's permalink"
    (let [[groups _] (build [(entry {})
                             (entry {:lang :zh-Hans :title "约定"})
                             (entry {:lang :zh-Hant :title "慣例"})])]
      (is (= 1 (count groups)) "three files, one article")
      (let [g (first (vals groups))]
        (is (= #{:en :zh-Hans :zh-Hant} (set (keys (:variants g)))))
        (is (= :en (:primary g)))))))

(deftest identity-is-the-filename-not-the-display-title
  (testing "a translated `title:` must not fork the identity"
    ;; This is the bug the first demo-site build caught: keying on the display
    ;; title gave every translated file its own permalink, silently defeating
    ;; the entire i18n design.
    (let [[groups _] (build [(entry {})
                             (entry {:lang :zh-Hans :title "完全不同的标题"})])]
      (is (= 1 (count groups))))))

(deftest different-titles-are-different-articles
  (let [[groups _] (build [(entry {:base "setup" :order 1})
                           (entry {:base "teardown" :order 3})])]
    (is (= 2 (count groups)))))

(deftest different-directories-are-different-articles
  (let [[groups _] (build [(entry {:dir "/01.Guide/10.Basics"})
                           (entry {:dir "/02.Notes/10.Local"})])]
    (is (= 2 (count groups)))))

;; ---------------------------------------------------------------------------
;; Mechanism 2 — explicit permalink

(deftest explicit-permalink-joins-across-directories
  (testing "§6.2 mechanism 2: a variant may live elsewhere in the tree"
    (let [[groups ds] (build [(entry {:dir "/03.Deep/10.L2" :base "relocated"
                                      :permalink "/pages/shared/"})
                              (entry {:dir "/02.Notes/10.Local" :base "different-name"
                                      :lang :zh-Hans :permalink "/pages/shared/"})])]
      (is (= 1 (count groups)))
      (is (= #{:en :zh-Hans} (set (keys (:variants (get groups "/pages/shared/")))))))))

(deftest m2-group-facts-come-from-the-primary-variant
  (testing "v2.1 M2: categories and the sidebar slot come from the PRIMARY
            variant's path, not from 'the directory' — which mechanism 2 makes
            ambiguous"
    (let [en (assoc (entry {:dir "/03.Deep/10.L2" :base "relocated"
                            :permalink "/pages/shared/"})
                    :categories ["Deep" "L2"] :order 7)
          zh (assoc (entry {:dir "/02.Notes/10.Local" :base "other" :lang :zh-Hans
                            :permalink "/pages/shared/"})
                    :categories ["Notes" "Local"] :order 1)
          [groups _] (build [en zh])
          g (get groups "/pages/shared/")]
      (is (= :en (:primary g)))
      (is (= ["Deep" "L2"] (:categories g)) "categories from the primary")
      (is (= "/03.Deep/10.L2" (:dir-key g)) "sidebar slot from the primary")
      (is (= 7 (:order g)) "…and they agree with each other by construction"))))

(deftest m2-path-disagreement-warns
  (testing "a doctor warning records the split, because a deliberate relocation
            and a copy-pasted permalink look identical from here"
    (let [[_ ds] (build [(entry {:dir "/03.Deep/10.L2" :base "a" :permalink "/pages/shared/"})
                         (entry {:dir "/02.Notes/10.Local" :base "b" :lang :zh-Hans
                                 :permalink "/pages/shared/"})])
          warns (diag/warnings ds)]
      (is (seq warns))
      (is (some #(re-find #"different directories" (:message %)) warns)))))

(deftest agreeing-permalinks-in-one-implicit-group-merge
  (testing "the merged path: members that declare the SAME permalink are one
            article, and members that declare nothing inherit it"
    (let [[groups ds] (build [(entry {:permalink "/pages/aaa/"})
                              (entry {:lang :zh-Hans :title "约定" :permalink "/pages/aaa/"})
                              (entry {:lang :ta :title "மரபுகள்"})])]
      (is (= 1 (count groups)))
      (is (= #{:en :zh-Hans :ta} (set (keys (:variants (get groups "/pages/aaa/"))))))
      (is (empty? (diag/errors ds)))
      (is (empty? (filter #(re-find #"different permalinks" (:message %)) (diag/warnings ds)))))))

(deftest conflicting-permalinks-in-one-implicit-group-are-an-error
  (testing "§6.2 as amended: identity IS the permalink, so members of one
            implicit group that declare DIFFERENT permalinks are claiming to be
            two articles occupying one directory, number and base name — which
            is the collision D-3 already makes a hard error, reached by another
            route. It is an error, not a warning: the previous behaviour warned
            that the highest-priority variant's permalink was being used and
            then gave every declaring member its own, so the group split in two
            and the file on disk never converged with the site."
    (let [[groups ds] (build [(entry {:permalink "/pages/aaa/"})
                              (entry {:lang :zh-Hans :title "约定" :permalink "/pages/bbb/"})])
          errs (diag/errors ds)]
      (is (= 1 (count errs)))
      (is (re-find #"declare different permalinks" (:message (first errs))))
      (is (re-find #"/pages/aaa/" (:message (first errs))))
      (is (re-find #"/pages/bbb/" (:message (first errs))))
      (is (= 1 (count groups))
          "resolved deterministically anyway, so the rest of the doctor report is readable")
      (is (= #{:en :zh-Hans} (set (keys (:variants (get groups "/pages/aaa/"))))))
      (is (nil? (get groups "/pages/bbb/"))
          "the group does not split — that split is the bug the warning denied"))))

(deftest the-conflict-winner-is-the-highest-priority-variants
  (testing "…and which one wins is stated by the error, so the author can see it"
    (let [[groups ds] (build [(entry {:lang :ta   :title "த" :permalink "/pages/ttt/"})
                              (entry {:lang :zh-Hans :title "约定" :permalink "/pages/zzz/"})])]
      (is (= ["/pages/zzz/"] (keys groups)) ":zh-Hans outranks :ta in :priority")
      (is (re-find #"Using /pages/zzz/" (:hint (first (diag/errors ds))))))))

(deftest a-conflict-does-not-need-every-member-to-declare
  (testing "two declarations plus a silent sibling is still one article"
    (let [[groups ds] (build [(entry {:permalink "/pages/aaa/"})
                              (entry {:lang :zh-Hans :title "约定" :permalink "/pages/bbb/"})
                              (entry {:lang :ta :title "மரபுகள்"})])]
      (is (= 1 (count (diag/errors ds))))
      (is (= 1 (count groups)))
      (is (= 3 (count (:variants (get groups "/pages/aaa/"))))
          "the non-declaring sibling joins the winner too"))))

(deftest two-files-claiming-one-language-is-an-error
  (let [[_ ds] (build [(entry {:base "a" :permalink "/pages/x/"})
                       (entry {:base "b" :permalink "/pages/x/"})])]
    (is (some #(re-find #"claim to be the en version" (:message %)) (diag/errors ds)))))

;; ---------------------------------------------------------------------------
;; Primary selection and URLs (§6.3)

(deftest primary-is-the-first-available-priority-language
  (doseq [[langs expected] [[[:en :zh-Hans] :en]
                            [[:zh-Hans :zh-Hant] :zh-Hans]
                            [[:ta] :ta]
                            [[:ms :ta] :ms]]]
    (let [[groups _] (build (map #(entry {:lang % :title (name %)}) langs))
          g (first (vals groups))]
      (is (= expected (:primary g)) (str langs " → primary")))))

(deftest tamil-only-article-lives-at-the-bare-identity-url
  (testing "§6.3's per-article twist: the unprefixed URL is not 'the site
            default language', it is 'this article's primary variant'"
    (let [[groups _] (build [(entry {:lang :ta :base "tamil-only"})])
          g (first (vals groups))]
      (is (= :ta (:primary g)))
      (is (= (:permalink g) (model/variant-url cfg g :ta))
          "no /ta/ prefix — the article has no higher-priority variant"))))

(deftest non-primary-variants-are-prefixed
  (let [[groups _] (build [(entry {}) (entry {:lang :zh-Hans :title "约定"})])
        g (first (vals groups))]
    (is (= (:permalink g) (model/variant-url cfg g :en)))
    (is (= (str "/zh-Hans" (:permalink g)) (model/variant-url cfg g :zh-Hans)))))

(deftest prefix-default-true-prefixes-everything
  (testing "D-10: with :prefix-default? true the bare URL becomes a redirect stub"
    (let [cfg' (assoc-in cfg [:i18n :prefix-default?] true)
          [groups _] (diag/collecting
                      (let [[es _] (model/resolve-permalinks cfg' [(entry {})] model/empty-ledger)]
                        (model/build-groups cfg' es)))
          g (first (vals groups))]
      (is (= (str "/en" (:permalink g)) (model/variant-url cfg' g :en)))
      (is (= (:permalink g) (model/identity-url cfg' g))
          "the bare URL still exists — as a stub, so old links are redirected not broken"))))

(deftest best-variant-falls-back-to-primary
  (let [[groups _] (build [(entry {}) (entry {:lang :zh-Hans :title "约定"})])
        g (first (vals groups))]
    (is (= :zh-Hans (model/best-variant g :zh-Hans)))
    (is (= :en (model/best-variant g :ta)) "§6.8: fall back to the primary")))

;; ---------------------------------------------------------------------------
;; Group facts

(deftest tags-are-the-union-across-variants
  (testing "§6.2: a tag added on one version must not hide the article from that
            tag's index"
    (let [en (assoc-in (entry {}) [:front-matter :tags] ["a" "b"])
          zh (-> (entry {:lang :zh-Hans :title "约定"})
                 (assoc-in [:front-matter :tags] ["b" "c"]))
          [groups _] (build [en zh])
          g (first (vals groups))]
      (is (= #{"a" "b" "c"} (set (:tags g)))))))

(deftest date-and-sticky-come-from-the-primary
  (testing "so every language's index sorts identically"
    (let [en (-> (entry {}) (assoc-in [:front-matter :date] "2026-01-01 00:00:00")
                 (assoc-in [:front-matter :sticky] 3))
          zh (-> (entry {:lang :zh-Hans :title "约定"})
                 (assoc-in [:front-matter :date] "2020-01-01 00:00:00"))
          [groups _] (build [en zh])
          g (first (vals groups))]
      (is (= "2026-01-01 00:00:00" (:date g)))
      (is (= 3 (:sticky g))))))

(deftest article-predicate
  (testing "not (pageComponent || article == false || home == true)"
    (let [check (fn [fm] (-> (build [(update (entry {}) :front-matter merge fm)])
                             first vals first :article?))]
      (is (true?  (check {})))
      (is (false? (check {:article false})))
      (is (false? (check {:home true})))
      (is (false? (check {:pageComponent {:name "Catalogue"}}))))))

;; ---------------------------------------------------------------------------
;; Read-only builds (§7.2 mechanism (c))

(deftest read-only-builds-are-deterministic
  (testing "--no-write must produce identical URLs across runs"
    (let [cfg' (assoc-in cfg [:content :write-front-matter] false)
          run  (fn [] (first (diag/collecting
                              (first (model/resolve-permalinks
                                      cfg' [(entry {})] model/empty-ledger)))))
          a (run) b (run)]
      (is (= (map :permalink a) (map :permalink b))
          "derived from the identity key, so reproducible within and across builds")
      (is (re-matches #"/pages/[0-9a-f]{6}/" (:permalink (first a)))))))

(deftest the-ledger-supplies-permalinks-for-read-only-builds
  (let [cfg' (assoc-in cfg [:content :write-front-matter] false)
        e (entry {})
        ledger {:version 1
                :permalinks {"/pages/fromledger/" {:key (model/implicit-key e)}}
                :tombstones {}}
        [[entries _taken] _ds] (diag/collecting
                                (model/resolve-permalinks cfg' [e] ledger))]
    (is (= "/pages/fromledger/" (:permalink (first entries))))))

;; ---------------------------------------------------------------------------
;; Permalink collision avoidance (§7.2)

(deftest a-fresh-mint-never-collides-with-a-ledger-permalink
  (testing "`taken` was seeded from declared permalinks and tombstones only, so
            a mint could land on a permalink the LEDGER assigns to a different
            article in the same build — two articles, one URL, silently"
    (let [e      (entry {:base "brand-new"})
          ledger {:version 1
                  :permalinks {"/pages/aaaaaa/" {:key "/02.Notes/10.Local|9|something-else"}}
                  :tombstones {}}
          hexes  (atom ["aaaaaa" "bbbbbb"])]
      (with-redefs [u/random-hex (fn [_] (let [h (first @hexes)] (swap! hexes rest) h))]
        (let [[[entries taken] _] (diag/collecting (model/resolve-permalinks cfg [e] ledger))]
          (is (= "/pages/bbbbbb/" (:permalink (first entries)))
              "the RNG offered the ledger's permalink first and was refused")
          (is (contains? taken "/pages/aaaaaa/"))
          (is (contains? taken "/pages/bbbbbb/")))))))

(deftest a-fresh-mint-never-collides-with-a-tombstone
  (testing "a retired URL must not be handed to a new article — that is the
            whole point of keeping the ledger (D-15)"
    (let [e      (entry {:base "brand-new"})
          ledger {:version 1 :permalinks {} :tombstones {"/pages/aaaaaa/" {:reason "deleted"}}}
          hexes  (atom ["aaaaaa" "cccccc"])]
      (with-redefs [u/random-hex (fn [_] (let [h (first @hexes)] (swap! hexes rest) h))]
        (let [[[entries _] _] (diag/collecting (model/resolve-permalinks cfg [e] ledger))]
          (is (= "/pages/cccccc/" (:permalink (first entries)))))))))

(deftest the-read-only-fallback-is-checked-against-taken-too
  (testing "mechanism (c) hashes the identity key, and nothing checked the
            result against anything — so a --no-write build could serve two
            articles at one URL while the real build would not"
    (let [cfg'   (assoc-in cfg [:content :write-front-matter] false)
          e      (entry {:base "brand-new"})
          ikey   (model/implicit-key (assoc e :base-title "brand-new"))
          clash  (u/clean-url (str "/pages/" (subs (u/sha256-hex ikey) 0 6)))
          ledger {:version 1
                  :permalinks {clash {:key "/02.Notes/10.Local|9|something-else"}}
                  :tombstones {}}
          run    #(first (diag/collecting
                          (first (model/resolve-permalinks cfg' [e] ledger))))
          a (run) b (run)]
      (is (not= clash (:permalink (first a)))
          "the hash landed on a permalink the ledger already owns")
      (is (re-matches #"/pages/[0-9a-f]{6}/" (:permalink (first a))))
      (is (= (map :permalink a) (map :permalink b))
          "and the escape is itself deterministic — §7.2 mechanism (c) is only
           worth anything if it reproduces"))))

(deftest permalink-assignment-does-not-depend-on-group-iteration-order
  (testing "a 6-hex space and a few hundred articles is a real birthday risk, so
            which of two colliding groups gets the base hash must not depend on
            the order a hash map happened to yield"
    (let [cfg'    (assoc-in cfg [:content :write-front-matter] false)
          entries [(entry {:base "alpha" :order 1})
                   (entry {:base "beta"  :order 2})
                   (entry {:base "gamma" :order 3})]
          run     (fn [es] (into {} (map (juxt :implicit-key :permalink))
                                 (first (first (diag/collecting
                                                (model/resolve-permalinks cfg' es model/empty-ledger))))))]
      (is (= (run entries) (run (reverse entries)) (run (shuffle entries)))))))
