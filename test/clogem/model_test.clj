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

;; ---------------------------------------------------------------------------
;; Phase 2 — indexes, sticky, archives, the sidebar tree (D-P2-1, D-P2-2, D-P2-4)

(defn- full-model
  "The whole model over `entries`, diagnostics discarded."
  [entries]
  (first (diag/collecting (model/build-model cfg entries model/empty-ledger))))

(defn- dated
  "An entry with a canonical date string and any other front matter."
  [opts date & [fm]]
  (-> (entry opts)
      (update :front-matter merge (cond-> (or fm {}) date (assoc :date date)))))

(deftest indexes-hold-article-ids-and-a-group-files-under-every-category
  (testing "D-P2-2 / vdoing semantics: categories [\"Guide\" \"Basics\"] index
            the article under BOTH, and the values are permalinks, not pages"
    (let [m (full-model [(dated {:base "a" :order 1} "2026-08-01 00:00:00")])
          pl (first (:posts m))]
      (is (= [pl] (get-in m [:categories "Guide"])))
      (is (= [pl] (get-in m [:categories "Basics"])))
      (is (string? pl)))))

(deftest tags-index-is-the-union-across-variants
  (testing "a tag present only on a non-primary variant still indexes the article"
    (let [en (-> (dated {} "2026-08-01 00:00:00") (assoc-in [:front-matter :tags] ["a"]))
          zh (-> (dated {:lang :zh-Hans :title "约定"} nil) (assoc-in [:front-matter :tags] ["b" "c"]))
          m  (full-model [en zh])
          pl (first (:posts m))]
      (is (= 1 (count (:articles m))))
      (is (= {"a" [pl] "b" [pl] "c" [pl]} (:tags m))))))

(deftest sticky-is-normalized-and-ordered
  (testing "D-P2-4: `true` means rank 1, numbers are ranks, ascending; anything
            else is not sticky; a non-article can never be sticky"
    (is (= 1 (model/sticky-rank true)))
    (is (= 3 (model/sticky-rank 3)))
    (is (= 2 (model/sticky-rank "2")))
    (is (nil? (model/sticky-rank false)))
    (is (nil? (model/sticky-rank nil)))
    (is (nil? (model/sticky-rank "soon")))
    (let [m (full-model [(dated {:base "three" :order 1} "2026-01-01 00:00:00" {:sticky 3})
                         (dated {:base "one"   :order 2} "2026-01-02 00:00:00" {:sticky true})
                         (dated {:base "two"   :order 3} "2026-01-03 00:00:00" {:sticky 2})
                         (dated {:base "plain" :order 4} "2026-01-04 00:00:00")
                         (dated {:base "cat"   :order 5} "2026-01-05 00:00:00"
                                {:sticky 1 :pageComponent {:name "Catalogue" :data {:path "01.Guide"}}})])
          by-title (into {} (map (fn [[pl g]] [(get-in g [:variants :en :base-title]) pl])) (:articles m))]
      (is (= [(by-title "one") (by-title "two") (by-title "three")] (:sticky m)))
      (is (= 4 (count (:posts m))) "the catalogue page is not an article")
      (is (= (by-title "plain") (first (:posts m))) ":posts stays date-desc; sticky is a separate list"))))

(deftest archives-group-by-year-and-month-newest-first-and-skip-undated
  (let [m (full-model [(dated {:base "a" :order 1} "2026-08-01 09:00:00")
                       (dated {:base "b" :order 2} "2026-07-20 08:00:00")
                       (dated {:base "c" :order 3} "2025-12-31 23:59:59")
                       (dated {:base "d" :order 4} "2026-8-1")      ; hand-written, still files
                       (dated {:base "e" :order 5} nil)])          ; skipped
        archives (:archives m)
        pl (fn [t] (some (fn [[pl g]] (when (= t (get-in g [:variants :en :base-title])) pl)) (:articles m)))]
    (is (= [2026 2025] (keys archives)) "years descending")
    (is (= [8 7] (keys (get archives 2026))) "months descending")
    (is (= #{(pl "a") (pl "d")} (set (get-in archives [2026 8]))))
    (is (= [(pl "b")] (get-in archives [2026 7])))
    (is (= [(pl "c")] (get-in archives [2025 12])))
    (is (not (some #{(pl "e")} (mapcat (fn [[_ ms]] (mapcat val ms)) archives)))
        "no date, no archive slot — reported by doctor, not by analyse")
    (is (= (pl "e") (last (:posts m))) "…but it is still an article, sorted last")
    (is (nil? (model/archive-key nil)))
    (is (= [2026 8] (model/archive-key "2026-08-01 09:00:00")))))

(deftest tree-articles-are-posts-too
  (testing "D-P2-4: :posts is EVERY article group — tree AND post kinds — newest first"
    (let [m (full-model [(dated {:base "tree" :order 1 :kind :tree} "2026-01-01 00:00:00")
                         (dated {:base "2026-02-01-post" :dir "_posts" :order nil :kind :post} "2026-02-01 00:00:00")])
          kinds (map #(get-in m [:articles % :kind]) (:posts m))]
      (is (= [:post :tree] kinds)))))

(deftest a-page-component-group-is-in-no-index-but-is-a-leaf-and-a-catalogue
  (let [m (full-model [(dated {:base "Guide" :order 1 :dir "/00.Catalogue"} "2026-01-01 00:00:00"
                              {:pageComponent {:name "Catalogue"
                                               :data {:path "01.Guide/10.Basics" :description "x"}}
                               :tags ["t"]})
                       (dated {:base "a" :order 1} "2026-01-02 00:00:00")])
        cat (first (filter #(false? (:article? %)) (vals (:articles m))))]
    (is (some? cat))
    (is (not (some #{(:permalink cat)} (:posts m))))
    (is (not (some #{(:permalink cat)} (mapcat val (:categories m)))))
    (is (empty? (:tags m)) "its tag never reached the tag index")
    (is (not (some #{(:permalink cat)} (mapcat (fn [[_ ms]] (mapcat val ms)) (:archives m)))))
    (is (= {"/01.Guide/10.Basics" (:permalink cat)} (:catalogue m))
        "D-P2-8: :catalogue is keyed by the numbered dir-key the page covers")
    (is (= [(:permalink cat)]
           (map :permalink (model/tree-leaves (get-in m [:sidebar "00.Catalogue"]))))
        "D-P2-1: vdoing lists every file — the article predicate governs indexes, not the tree")))

(deftest the-sidebar-is-a-nested-tree-with-one-leaf-per-identity
  (testing "01.Guide/10.Basics nests; children sort by (order, title); the
            three-variant group is ONE leaf (M1)"
    (let [m (full-model [(entry {:base "conventions" :order 2})
                         (entry {:base "conventions" :order 2 :lang :zh-Hans :title "约定"})
                         (entry {:base "conventions" :order 2 :lang :zh-Hant :title "慣例"})
                         (entry {:base "getting-started" :order 1})
                         (entry {:base "Vue.js" :order 3})
                         (entry {:base "adv" :order 1 :dir "/01.Guide/20.Advanced"})
                         (entry {:base "zzz" :order 5 :dir "/01.Guide"})])
          guide (get-in m [:sidebar "01.Guide"])
          basics (second (:children guide))]
      (is (= ["01.Guide"] (keys (:sidebar m))))
      (is (= {:kind :dir :order 1 :title "Guide" :name "01.Guide" :dir-key "/01.Guide"}
             (select-keys guide [:kind :order :title :name :dir-key]))
          "(order, title) re-derived from the directory name")
      (is (= [[:article 5 "zzz"] [:dir 10 "Basics"] [:dir 20 "Advanced"]]
             (map (juxt :kind :order :title) (:children guide)))
          "files and subdirectories interleave by number, as in vdoing")
      (is (= [[1 "getting-started"] [2 "conventions"] [3 "Vue.js"]]
             (map (juxt :order :title) (:children basics))))
      (is (= 1 (count (filter #(= "conventions" (:title %)) (:children basics))))
          "three files, one leaf")
      (is (= ["zzz" "getting-started" "conventions" "Vue.js" "adv"]
             (map :title (model/tree-leaves guide)))
          "tree-leaves is the prev/next order for :tree articles"))))

(deftest a-mechanism-2-variant-gets-no-second-slot
  (testing "v2.1 M2: the slot is the PRIMARY's dir-key; the relocated variant's
            directory does not even appear in the tree if nothing else lives there"
    (let [m (full-model [(entry {:dir "/03.Deep/10.L2" :base "relocated" :order 1 :permalink "/pages/shared/"})
                         (entry {:dir "/02.Notes/10.Local" :base "other" :order 1 :lang :zh-Hans
                                 :permalink "/pages/shared/"})])]
      (is (= ["03.Deep"] (keys (:sidebar m))))
      (is (= ["/pages/shared/"] (map :permalink (model/tree-leaves (get-in m [:sidebar "03.Deep"])))))
      (is (= "/03.Deep/10.L2" (:dir-key (model/find-dir (:tree m) "/03.Deep/10.L2")))))))

(deftest posts-have-no-tree-slot
  (let [m (full-model [(entry {:base "2026-01-01-p" :dir "_posts" :order nil :kind :post})])]
    (is (empty? (:sidebar m)))
    (is (nil? (model/top-dir (first (vals (:articles m))))))))

(deftest doctor-checks-report-what-analyse-must-not
  (testing "undated articles, disagreeing variants, over-deep directories and
            unresolved catalogue paths are doctor warnings — never analyse
            warnings, so build_test's only-the-expected-warning still holds"
    (let [m (full-model [(dated {:base "undated" :order 1} nil)
                         (dated {:base "dis" :order 2} "2026-01-01 00:00:00" {:article true})
                         (dated {:base "dis" :order 2 :lang :ta :title "த"} nil {:article false})
                         (dated {:base "deep" :order 1 :dir "/01.A/10.B/20.C/30.D"} "2026-01-01 00:00:00")
                         (dated {:base "Cat" :order 1 :dir "/00.Catalogue"} "2026-01-01 00:00:00"
                                {:pageComponent {:name "Catalogue" :data {:path "09.Nowhere"}}})])
          [_ ds] (diag/collecting (model/doctor-checks! m))
          msgs (map :message (diag/warnings ds))]
      (is (empty? (diag/errors ds)))
      (is (some #(re-find #"no parseable `date:`" %) msgs))
      (is (some #(re-find #"disagree on `article`" %) msgs))
      (is (some #(re-find #"4 levels deep" %) msgs))
      (is (some #(re-find #"09\.Nowhere" %) msgs))
      (is (= 4 (count msgs)) (pr-str msgs)))))

(deftest newest-first-is-total
  (let [gs [{:permalink "/pages/b/" :date "2026-01-01 00:00:00"}
            {:permalink "/pages/a/" :date "2026-01-01 00:00:00"}
            {:permalink "/pages/c/" :date nil}
            {:permalink "/pages/d/" :date "2026-06-01 00:00:00"}]]
    (is (= ["/pages/d/" "/pages/a/" "/pages/b/" "/pages/c/"]
           (map :permalink (model/newest-first gs)))
        "date desc, permalink tie-break, undated last")
    (is (= (model/newest-first gs) (model/newest-first (reverse gs))))))
