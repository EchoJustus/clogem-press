;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.frontmatter-test
  "Golden-file tests for the surgical writer.

  Front-matter write-back is the one irreversible thing clogem-press does to a
  user's files, and the design's risk table names \"write-back corrupts user
  files\" as a medium risk mitigated by exactly these tests. So the assertions
  are byte-for-byte against checked-in expected files, not shape assertions:
  a change in quoting, key order, indentation, or line endings must fail."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.frontmatter :as fm]))

(def fixtures "test/fixtures/frontmatter")

(def standard-additions
  "What auto-fill would add for a fresh article in Guide/Basics."
  [[:title "Hello"]
   [:date "2026-01-02 03:04:05"]
   [:permalink "/pages/abc123/"]
   [:categories ["Guide" "Basics"]]
   [:tags []]])

(defn- additions-for
  "The same set, minus anything the file already declares — which is what
  `compute-additions` computes, expressed here without needing a model."
  [raw all]
  (let [existing (set (keys (fm/parse-fm (:fm-text (fm/split-file raw)) "test")))]
    (vec (remove (comp existing first) all))))

(defn- golden [name additions]
  (let [in  (slurp (fs/file fixtures (str name ".md")))
        exp (slurp (fs/file fixtures (str name ".expected.md")))]
    [(fm/insert-keys in additions) exp]))

(deftest golden-no-front-matter
  (testing "a file with no block gets a fresh one, and the body is untouched"
    (let [[got exp] (golden "no-fm" standard-additions)]
      (is (= exp got)))))

(deftest golden-partial-front-matter
  (testing "only missing keys are appended; every existing byte survives"
    (let [raw (slurp (fs/file fixtures "partial-fm.md"))
          [got exp] (golden "partial-fm" (additions-for raw standard-additions))]
      (is (= exp got))
      (is (str/includes? got "# a comment the writer must not eat")
          "a YAML comment is exactly what a reserializing writer would eat")
      (is (str/includes? got "sticky:   3")
          "odd-but-legal spacing is preserved because we never reparse-and-dump")
      (is (str/includes? got "custom: {a: 1, b: [2, 3]}")
          "flow-style mappings survive; vdoing's json2yaml round-trip mangles these"))))

(deftest golden-idempotence
  (testing "a normalized file produces NO diff on a second pass"
    ;; This is loop guard 3 from §7.2: `git diff --cached --quiet` short-circuits
    ;; and the CI normalization commit is never made.
    (let [raw (slurp (fs/file fixtures "complete-fm.md"))
          adds (additions-for raw standard-additions)]
      (is (empty? adds) "nothing is missing, so nothing is added")
      (is (= raw (fm/insert-keys raw adds))))))

(deftest golden-edn-front-matter
  (testing "EDN front matter gets pairs inserted before the closing brace"
    (let [raw (slurp (fs/file fixtures "edn-fm.md"))
          [got exp] (golden "edn-fm" (additions-for raw standard-additions))]
      (is (= exp got)))))

(deftest golden-crlf-preserved
  (testing "CRLF files stay CRLF — a mixed-ending file is a diff full of noise"
    (let [raw (slurp (fs/file fixtures "crlf-fm.md"))
          [got exp] (golden "crlf-fm" (additions-for raw standard-additions))]
      (is (= exp got))
      (is (not (re-find #"(?<!\r)\n" got)) "no bare LF was introduced"))))

;; ---------------------------------------------------------------------------
;; Splitting and parsing

(deftest split-round-trips
  (testing "body is preserved byte-for-byte"
    (doseq [raw ["---\ntitle: a\n---\n\nbody\n"
                 "no front matter at all\n"
                 "---\n---\nempty block\n"
                 "---\ntitle: a\n---\nno blank line after fence\n"]]
      (let [{:keys [body has-fm?]} (fm/split-file raw)]
        (is (str/includes? raw body))
        (is (boolean? has-fm?))))))

(deftest a-horizontal-rule-is-not-a-fence
  (testing "`---` further down the file must not be mistaken for front matter"
    (let [raw "# Title\n\nSome text.\n\n---\n\nMore text.\n"
          {:keys [has-fm? body]} (fm/split-file raw)]
      (is (false? has-fm?) "the block must start at byte 0")
      (is (= raw body)))))

(deftest first-closing-fence-wins
  (testing "a `---` inside the body does not extend the block"
    (let [raw "---\ntitle: a\n---\n\ntext\n\n---\n\nmore\n"
          {:keys [fm-text body]} (fm/split-file raw)]
      (is (= "title: a" fm-text))
      (is (str/includes? body "more")))))

(deftest parses-both-formats
  (is (= {:title "y"} (fm/parse-fm "title: y" "t")))
  (is (= {:title "y"} (fm/parse-fm "{:title \"y\"}" "t")))
  (is (= {} (fm/parse-fm "" "t")))
  (is (= {} (fm/parse-fm nil "t"))))

(deftest malformed-front-matter-is-an-error-not-a-crash
  (let [[result ds] (diag/collecting (fm/parse-fm "{:unclosed \"map\"" "bad.md"))]
    (is (nil? result))
    (is (= 1 (count (diag/errors ds))))))

(deftest vdoing-empty-tags-placeholder-is-readable
  (testing "vdoing writes `tags:` with an empty list item; we must read it"
    ;; We *write* `tags: []`, which is cleaner, but an imported vdoing tree is
    ;; full of the other form and must parse without complaint.
    (is (contains? (fm/parse-fm "title: x\ntags:\n  - \n" "t") :tags))))

(deftest quoting-rules
  (testing "values that YAML would otherwise reinterpret are quoted"
    (let [out (fm/insert-keys "# x\n" [[:date "2026-01-02 03:04:05"]])]
      (is (str/includes? out "date: \"2026-01-02 03:04:05\"")
          "unquoted, YAML resolves this to a timestamp and the value stops being a string"))
    (let [out (fm/insert-keys "# x\n" [[:title "Ratio: 3:1"]])]
      (is (str/includes? out "title: \"Ratio: 3:1\"")))
    (let [out (fm/insert-keys "# x\n" [[:title "Plain Title"]])]
      (is (str/includes? out "title: Plain Title")
          "ordinary titles are NOT quoted — a normalization diff should be boring"))
    (let [out (fm/insert-keys "# x\n" [[:title "true"]])]
      (is (str/includes? out "title: \"true\"")
          "a title that looks like a boolean must survive as a string"))))

(deftest compute-additions-never-overwrites
  (testing "rule 1: a manual value is never touched"
    (let [entry {:front-matter {:title "Mine" :permalink "/pages/mine/"}
                 :path "test/fixtures/frontmatter/no-fm.md"}
          adds (fm/compute-additions entry
                                     {:title "Derived" :permalink "/pages/other/"
                                      :categories ["A"]}
                                     {})]
      (is (nil? (some #{:title} (map first adds))))
      (is (nil? (some #{:permalink} (map first adds))))
      (is (some #{:categories} (map first adds))))))

(deftest compute-additions-writes-falsey-extends
  (testing "a `false` extend-frontmatter default is a value, not an absence"
    (let [entry {:front-matter {} :path "test/fixtures/frontmatter/no-fm.md"}
          adds (fm/compute-additions entry {:title "T"} {:comment false})]
      (is (= false (second (first (filter #(= :comment (first %)) adds))))))))

(deftest fill-order-matches-vdoing
  (let [entry {:front-matter {} :path "test/fixtures/frontmatter/no-fm.md"}
        adds (fm/compute-additions entry
                                   {:title "T" :permalink "/pages/x/" :categories ["A"]}
                                   {})]
    (is (= [:title :date :permalink :categories :tags] (mapv first adds))
        "matching vdoing's key order keeps a migrated tree's first diff minimal")))

;; ---------------------------------------------------------------------------
;; Non-map front matter (all three YAML shapes)

(deftest yaml-front-matter-must-be-a-mapping
  (testing "the EDN branch guarded on map?; the YAML branch did not, and YAML
            has two other document shapes that reach it"

    (testing "a scalar block — `contains?` on a String is an IllegalArgumentException,
              which used to surface deep inside compute-additions"
      (let [[result ds] (diag/collecting (fm/parse-fm "just a note" "scalar.md"))]
        (is (nil? result))
        (is (= 1 (count (diag/errors ds))))
        (is (re-find #"not a mapping" (:message (first (diag/errors ds)))))))

    (testing "a list block — parses to a LazySeq, which `contains?` also refuses"
      (let [[result ds] (diag/collecting (fm/parse-fm "- one\n- two\n" "list.md"))]
        (is (nil? result))
        (is (= 1 (count (diag/errors ds))))
        (is (re-find #"a list" (:message (first (diag/errors ds)))))))

    (testing "a SET — the genuinely dangerous shape, and the reason this guard
              cannot just be `(map? m)` mirrored from the EDN branch. An
              OrderedSet is `contains?`-able and answers false for every key, so
              it never crashed: it reached the writer, which appended mapping
              lines into a non-mapping block and corrupted the source file while
              the build printed success and exited 0."
      (let [[result ds] (diag/collecting (fm/parse-fm "!!set\n? a\n? b\n" "set.md"))]
        (is (nil? result))
        (is (= 1 (count (diag/errors ds))))
        (is (re-find #"a set" (:message (first (diag/errors ds)))))))

    (testing "a mapping — unchanged"
      (is (= {:title "y"} (fm/parse-fm "title: y" "map.md"))))))

(deftest non-map-front-matter-reads-as-an-empty-map-and-never-crashes
  (testing "read-file must hand downstream code a map whatever the file says, so
            the diagnostic is what stops the build rather than a stack trace"
    (let [dir (fs/create-temp-dir {:prefix "clogem-fm"})]
      (try
        (doseq [[name content] {"scalar.md" "---\njust a note\n---\n\nbody\n"
                                "list.md"   "---\n- one\n- two\n---\n\nbody\n"
                                "set.md"    "---\n!!set\n? a\n? b\n---\n\nbody\n"}]
          (let [f (fs/path dir name)]
            (spit (fs/file f) content)
            (let [[file ds] (diag/collecting (fm/read-file f))]
              (is (map? (:front-matter file)) (str name " → a map"))
              (is (seq (diag/errors ds)) (str name " → an error"))
              ;; the actual Phase 1 crash site: `contains?` on a String threw
              ;; IllegalArgumentException from deep inside the fill plan
              (is (= [:date :tags]
                     (mapv first (fm/compute-additions
                                  {:front-matter (:front-matter file) :path (str f)}
                                  {} {})))
                  (str name " → compute-additions runs instead of throwing")))))
        (finally (fs/delete-tree dir))))))

(deftest a-file-with-non-map-front-matter-is-never-written-back
  (testing "write-back is the one irreversible thing this program does, so the
            guarantee is structural: an :error means the pipeline raises before
            apply-fill! is reached, and the bytes on disk are untouched"
    (let [dir (fs/create-temp-dir {:prefix "clogem-fmsite"})
          f   (fs/path dir "content" "01.Guide" "01.notes.md")
          raw "---\n- one\n- two\n---\n\nbody\n"]
      (try
        (fs/create-dirs (fs/parent f))
        (spit (fs/file f) raw)
        (binding [diag/*sink* (atom [])]      ; keep config's own notes off stderr
          (is (thrown? clojure.lang.ExceptionInfo
                       (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist"))}))
              "the build refuses to run"))
        (is (= raw (slurp (fs/file f)))
            "and the source file is byte-identical — write-back happens only
             after throw-on-errors!, which is what makes this structural")
        (finally (fs/delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; :date normalization

(deftest date-is-always-a-string-after-parsing
  (testing "clj-yaml resolves an UNQUOTED YAML timestamp to java.util.Date and a
            quoted one to a String, and vdoing's own writer emits the unquoted
            form — so a migrated tree and a freshly auto-filled one put both
            types in one model. Anything that sorts by :date then throws
            ClassCastException on a perfectly legal tree."
    (doseq [[block expected]
            [["date: 2026-08-01"                "2026-08-01 00:00:00"]
             ["date: 2026-08-01 09:30:00"       "2026-08-01 09:30:00"]
             ["date: 2026-08-01T09:30:00Z"      "2026-08-01 09:30:00"]
             ["date: \"2026-08-01 09:30:00\""   "2026-08-01 09:30:00"]
             ["{:date #inst \"2026-08-01T09:30:00Z\"}" "2026-08-01 09:30:00"]]]
      (let [d (:date (fm/parse-fm block "t"))]
        (is (string? d) (str block " → a string"))
        (is (= expected d) block)))))

(deftest date-normalization-preserves-what-the-author-wrote
  (testing "a String date is never rewritten — auto-fill's own canonical form
            round-trips, and an unrecognized one is left alone rather than
            reinterpreted"
    (is (= "yesterday" (:date (fm/parse-fm "date: yesterday" "t"))))
    (is (= "2026-08-01 09:30:00" (:date (fm/parse-fm "date: \"2026-08-01 09:30:00\"" "t"))))
    (is (nil? (:date (fm/parse-fm "title: x" "t"))))))

(deftest a-non-date-date-still-becomes-a-string
  (testing "the invariant is the type, not the shape: YAML resolves `20260801`
            to a number, which would mix just as badly"
    (is (= "20260801" (:date (fm/parse-fm "date: 20260801" "t"))))))
