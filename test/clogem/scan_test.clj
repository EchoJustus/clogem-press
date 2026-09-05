;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.scan-test
  "Table tests for the §6.1 parsing algorithm.

  The first block reproduces DESIGN.md §6.1's worked-example table row for row —
  if the design table and this test ever disagree, one of them is wrong and the
  build says so."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.scan :as scan]))

(def cfg
  "Built-in defaults: all five languages, :en default. No site.edn is read."
  (first (diag/collecting (config/load-config "test/fixtures/__no_such_site__"))))

(defn- parse [fname] (scan/parse-filename cfg fname))

(deftest design-6-1-worked-examples
  (testing "DESIGN.md §6.1's worked-example table, row for row"
    (doseq [[fname order title lang]
          [["01.article.md"          1  "article"     nil]
           ["01.Vue.js 入门.md"       1  "Vue.js 入门"  nil]
           ["01.Vue.js.md"           1  "Vue.js"      nil]
           ["01.article.zh-Hans.md"  1  "article"     :zh-Hans]
           ["10.article.ZH-HANS.md"  10 "article"     :zh-Hans]
           ;; case-insensitive match, canonical storage — every spelling of the
           ;; tag resolves to the one configured keyword
           ["01.a.zh-hans.md"        1  "a"           :zh-Hans]
           ["01.a.ZH-hAnS.md"        1  "a"           :zh-Hans]
           ["01.a.ta.md"             1  "a"           :ta]
           ["01.a.TA.md"             1  "a"           :ta]
           ;; numbers need not be consecutive, and gaps are recommended
           ["30.deep.md"             30 "deep"        nil]
           ;; a dotted title survives intact
           ["05.a.b.c.md"            5  "a.b.c"       nil]]]
      (let [r (parse fname)]
        (is (= order (:order r)) (str fname " → order"))
        (is (= title (:title r)) (str fname " → title"))
        (is (= lang  (:lang r))  (str fname " → lang"))))))

(deftest skipped-with-a-warning
  (testing "vdoing's warn-and-skip cases, unchanged"
    (doseq [fname ["hello.md" "notes.md" "README.md"]]
      (is (:skip (parse fname)) (str fname " should be skipped")))
    (is (:skip (parse "01..md")) "number but no title")
    (is (:skip (parse "notes.txt")) "not markdown")))

(deftest near-miss-is-a-hard-error
  (testing "§6.1: a near-miss language tag errors with a suggestion"
    (let [r (parse "01.article.zh-Hanz.md")]
      (is (:error r))
      (is (re-find #"zh-Hanz" (:error r)))
      (is (re-find #"did you mean `zh-Hans`" (:hint r))
          "the suggestion is the whole point — a bare error would be worse than a warning")))

  (testing "the confusables set derived from :langs (no hyphen needed)"
    (is (:error (parse "01.article.zh.md"))
        "`zh` is ambiguous between the two configured Chinese scripts")
    (is (:error (parse "01.article.zh-CN.md")))
    (is (:error (parse "01.article.en-US.md"))))

  (testing "the hyphen requirement is what keeps dotted titles safe"
    (is (nil? (:error (parse "01.Vue.js.md")))
        "`js` has no hyphen and is not confusable, so it is part of the title")
    (is (nil? (:error (parse "01.Notes.v2.md"))))
    (is (nil? (:error (parse "01.config.yaml.md"))))))

(deftest configured-language-wins-over-near-miss
  (testing "a configured code is a match, never a near miss"
    (is (= :en (:lang (parse "01.a.en.md"))))
    (is (= :ms (:lang (parse "01.a.ms.md"))))))

(deftest single-segment-body-is-never-a-language
  (testing "count(body) ≥ 2 guard: a file named after a language alone is not a variant"
    ;; body = ["ta"], count 1 → the lang branch is skipped entirely, and
    ;; parseInt("ta") is NaN, so it skips with a warning like any unnumbered file
    (is (:skip (parse "ta.md")))))

(deftest directory-parsing-unchanged
  (testing "directories are never language-suffixed"
    (is (= {:order 1 :title "Guide" :numbered? true} (scan/parse-dirname "01.Guide")))
    (is (= {:order 25 :title "JavaScript" :numbered? true} (scan/parse-dirname "25.JavaScript")))
    (is (= {:order 1 :title "前端.进阶" :numbered? true} (scan/parse-dirname "01.前端.进阶"))
        "everything after the FIRST dot is the directory title")
    (is (= {:order nil :title "Guide" :numbered? false} (scan/parse-dirname "Guide")))))

(deftest parse-int-semantics
  (testing "vdoing parses the number with JS parseInt, and we reproduce it"
    (is (= 1 (scan/parse-order "01")))
    (is (= 1 (scan/parse-order "01abc")))
    (is (= 10 (scan/parse-order "10")))
    (is (nil? (scan/parse-order "abc")))
    (is (nil? (scan/parse-order "")))))

;; ---------------------------------------------------------------------------
;; M1 — the identity-scoped duplicate-number rule

(defn- dup-errors [entries]
  (let [[_ ds] (diag/collecting (scan/check-duplicate-numbers! entries))]
    (diag/errors ds)))

(defn- entry [dir order title & [lang]]
  {:kind :tree :dir-key dir :order order :base-title title :title title
   :lang lang :path (str dir "/" order "." title (when lang (str "." (name lang))) ".md")})

(deftest m1-variants-are-not-a-duplicate
  (testing "same number + same title = language variants of ONE article, which is legal"
    (is (empty? (dup-errors [(entry "/01.Guide" 2 "conventions")
                             (entry "/01.Guide" 2 "conventions" :zh-Hans)
                             (entry "/01.Guide" 2 "conventions" :zh-Hant)]))
        "applying vdoing's unscoped rule here would fail the build on every
         translated article — this is exactly what v2.1's M1 corrects")))

(deftest m1-different-identities-are-an-error
  (testing "same number + different titles = a genuine collision"
    (let [errs (dup-errors [(entry "/01.Guide" 1 "Setup")
                            (entry "/01.Guide" 1 "Teardown")])]
      (is (= 1 (count errs)))
      (is (re-find #"duplicate sidebar number 1" (:message (first errs))))
      (is (re-find #"Setup" (:message (first errs))))
      (is (re-find #"Teardown" (:message (first errs)))))))

(deftest m1-scoped-per-directory
  (testing "the same number in different directories is not a collision"
    (is (empty? (dup-errors [(entry "/01.Guide" 1 "Setup")
                             (entry "/02.Notes" 1 "Teardown")])))))

(deftest m1-mixed-case
  (testing "a variant group and a colliding article in one directory"
    (let [errs (dup-errors [(entry "/01.Guide" 2 "conventions")
                            (entry "/01.Guide" 2 "conventions" :ta)
                            (entry "/01.Guide" 2 "something-else")])]
      (is (= 1 (count errs))
          "one error for the directory, not one per file"))))

;; ---------------------------------------------------------------------------
;; Post identity (§6.2 mechanism 1, applied to `_posts/`)

(defn- temp-site
  "Materialize `files` ({rel-path → content}) under a temp content/ tree and
  return the site dir."
  [files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-posts"})]
    (doseq [[rel content] files
            :let [f (fs/path dir "content" rel)]]
      (fs/create-dirs (fs/parent f))
      (spit (fs/file f) content))
    dir))

(defn- analyse-temp
  [files]
  (let [dir (temp-site files)]
    (try
      (let [cfg (first (diag/collecting
                        (config/load-config (str dir) nil
                                            {:content {:write-front-matter false}})))]
        (diag/collecting (cli/analyse cfg)))
      (finally (fs/delete-tree dir)))))

(def ^:private post-body "---\ntitle: A post\n---\n\nbody\n")

(deftest posts-are-identified-by-their-full-stem-and-subfolder
  (testing "§6.2 mechanism 1 for `_posts/`: identity is (directory, order, base
            name), and for a post the base name is the FULL stem — the date is
            part of the filename, so it is part of the identity. Stripping it
            (and flattening every subfolder to \"_posts\") made any two posts
            sharing a slug one article, which then hard-errors as two files
            claiming the same language."
    (let [[model ds]
          (analyse-temp {"_posts/2026-08-01-hello.md"         post-body
                         "_posts/2026-09-15-hello.md"         post-body
                         "_posts/tech/2026-08-01-hello.md"    post-body})]
      (is (empty? (diag/errors ds))
          (str "unexpected errors: " (pr-str (map :message (diag/errors ds)))))
      (is (= 3 (count (:articles model)))
          "three distinct posts: two dates and two subfolders, one shared slug")
      (is (= 3 (count (distinct (map :implicit-key (:entries model)))))))))

(deftest post-language-variants-still-group
  (testing "the date is in the identity, but the LANGUAGE SUFFIX is not — a
            translated post must still join its sibling"
    (let [[model ds]
          (analyse-temp {"_posts/2026-08-01-hello.md"         post-body
                         "_posts/2026-08-01-hello.zh-Hans.md" post-body
                         "_posts/2026-09-15-hello.md"         post-body})]
      (is (empty? (diag/errors ds)))
      (is (= 2 (count (:articles model))))
      (let [g (first (filter #(= 2 (count (:variants %))) (vals (:articles model))))]
        (is (some? g) "the dated pair is one article with two variants")
        (is (= #{:en :zh-Hans} (set (keys (:variants g)))))))))

(deftest post-display-title-still-drops-the-date
  (testing "identity keeps the date; the *display* title does not — a
            `YYYY-MM-DD-slug` post still shows as `slug`"
    (let [[model _] (analyse-temp {"_posts/2026-08-01-hello.md" "---\n---\n\nbody\n"})
          v (first (vals (:variants (first (vals (:articles model))))))]
      (is (= "hello" (:title v)))
      (is (= "2026-08-01-hello" (:base-title v))))))

(deftest post-subfolder-is-part-of-the-directory-key
  (let [[model _] (analyse-temp {"_posts/2026-08-01-hello.md"      post-body
                                 "_posts/tech/2026-08-01-hello.md" post-body})]
    (is (= #{"_posts" "_posts/tech"}
           (set (map :dir-key (:entries model)))))))

;; ---------------------------------------------------------------------------
;; D-3 for directories

(defn- dir-entry [parent order title]
  {:kind :dir :dir-key parent :order order :base-title title :title title
   :path (str parent "/" order "." title)})

(deftest sibling-directories-sharing-a-number-are-an-error
  (testing "D-3 upgrades vdoing's warn-and-overwrite to an error, and §6.1 says
            directories are grouped on `order` alone since they are never
            language-suffixed. Only FILES were ever checked, so two sibling
            directories with the same number sorted by a string tie-break —
            vdoing's exact behaviour, in the one place D-3 said not to have it."
    (let [errs (dup-errors [(dir-entry "/01.Guide" 10 "Basics")
                            (dir-entry "/01.Guide" 10 "Advanced")])]
      (is (= 1 (count errs)))
      (is (re-find #"duplicate sidebar number 10" (:message (first errs))))
      (is (re-find #"10\.Basics" (:message (first errs))))
      (is (re-find #"10\.Advanced" (:message (first errs)))))))

(deftest directories-are-scoped-to-their-parent
  (is (empty? (dup-errors [(dir-entry "/01.Guide" 10 "Basics")
                           (dir-entry "/02.Notes" 10 "Local")]))))

(deftest unnumbered-directories-are-not-a-collision
  (testing "an unnumbered directory is legal at level 1 and simply sorts last"
    (is (empty? (dup-errors [(dir-entry "" nil "Guide")
                             (dir-entry "" nil "Notes")])))))

(deftest a-directory-and-a-file-sharing-a-number-are-not-a-collision
  (testing "they occupy different slots — vdoing renders directories and files
            as separate sidebar groups"
    (is (empty? (dup-errors [(dir-entry "/01.Guide" 10 "Basics")
                             (entry "/01.Guide" 10 "notes")])))))

(deftest duplicate-directory-numbers-fail-a-real-tree
  (let [[_ ds] (analyse-temp {"01.Guide/10.Alpha/01.a.md" post-body
                              "01.Guide/10.Beta/01.b.md"  post-body})
        errs (diag/errors ds)]
    (is (= 1 (count errs)) (pr-str (map :message errs)))
    (is (re-find #"duplicate sidebar number 10" (:message (first errs))))))

(deftest directory-markers-never-escape-the-scanner
  (testing "`scan-tree` emits directory entries so the duplicate rule can see
            them; `scan` must drop them, or load-entries would try to slurp a
            directory as Markdown"
    (let [dir (temp-site {"01.Guide/10.Basics/01.a.md" post-body})]
      (try
        (let [cfg (first (diag/collecting (config/load-config (str dir))))
              [entries ds] (diag/collecting (scan/scan cfg))]
          (is (empty? (filter #(= :dir (:kind %)) entries)))
          (is (= 1 (count entries)))
          (is (empty? (diag/errors ds))))
        (finally (fs/delete-tree dir))))))

(deftest leading-zeros-do-not-hide-a-directory-collision
  (testing "`010.Basics` and `10.Basics` both parse to order 10 and both collapse
            to the same category name — the case most likely to regress if the
            grouping key is ever changed from `order` to something name-derived"
    (let [[_ ds] (analyse-temp {"01.Guide/010.Basics/01.a.md" post-body
                                "01.Guide/10.Basics/01.b.md"  post-body})
          errs (diag/errors ds)]
      (is (= 1 (count errs)) (pr-str (map :message errs)))
      (is (re-find #"duplicate sidebar number 10" (:message (first errs)))))))

(deftest excluded-and-unnumbered-directories-are-exempt-in-a-real-tree
  (testing "the marker is consed after the cond that drops `_posts`/`@pages`/
            dot-dirs, and the rule skips `:order nil` — both are load-bearing
            orderings that nothing else pins"
    (let [[_ ds] (analyse-temp {"01.Guide/10.Basics/01.a.md"  post-body
                                "_posts/2026-01-01-p.md"      post-body
                                "@pages/categoriesPage.md"    post-body
                                ".hidden/01.x.md"             post-body})]
      (is (empty? (diag/errors ds)) (pr-str (map :message (diag/errors ds))))
      (is (empty? (diag/warnings ds)) (pr-str (map :message (diag/warnings ds)))))))
