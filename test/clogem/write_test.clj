;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.write-test
  "Build robustness and speed (Phase 4 Task A, D-P4-11), end to end over a
  copy of `examples/demo-site`.

  The robustness case is the pre-flight's reproduction: a good build, then an
  edited article, a deleted zh-Hant variant, a new article and a renamed site
  title, with one page in the middle of the render throwing. 0.2.0 left a
  mixed dist/ — 150 new pages and 86 old, the new title on some pages only,
  the deleted variant still served, the old feed and sitemap — and exited
  non-zero. Now dist/ (and the ledger) must be byte-for-byte what the good
  build left."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.render :as render]
            [clogem.search :as search]
            [clogem.theme.page :as page]))

(def demo "examples/demo-site")

(defn- with-demo-copy
  "Copy the demo site to a temp directory and call (f dir out) with
  `search/*env*` empty (so CI's CLOGEM_PAGEFIND is not used) and search off."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-write"})]
    (try
      (fs/copy-tree demo dir)
      (binding [diag/*sink* (atom [])
                search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- build!
  "`cli/build` with search off; returns what it printed."
  [dir & [extra]]
  (with-out-str (cli/build (merge {:site-dir (str dir) :no-search true} extra))))

(defn- tree-hash
  "{relative path → sha256} of every regular file under `dir` (links are
  recorded as links), so two snapshots compare equal iff the trees do."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true :follow-links false})
              :when (or (fs/regular-file? p {:nofollow-links true}) (fs/sym-link? p))]
          [(str (fs/relativize dir p))
           (if (fs/sym-link? p)
             (str "link → " (fs/read-link p))
             (search/sha256-hex p))])))

(defn- written-counts
  "[written unchanged] from a build's summary line."
  [printed]
  (when-let [[_ w u] (re-find #"→ \S+ \((\d+) written, (\d+) unchanged\)" printed)]
    [(parse-long w) (parse-long u)]))

(deftest a-render-failure-leaves-dist-and-the-ledger-untouched
  (with-demo-copy
    (fn [dir out]
      (build! dir)
      (spit (fs/file out "CNAME") "docs.example")
      (spit (fs/file out ".nojekyll") "")
      (let [before        (tree-hash out)
            ledger        (fs/path dir "permalinks.edn")
            ledger-before (slurp (fs/file ledger))
            content       (fs/path dir "content")]
        (is (< 200 (count (filter #(str/ends-with? % "index.html") (keys before)))))
        ;; the pre-flight's edits: one edited article, one deleted zh-Hant
        ;; variant, one new article, a renamed site title
        (spit (fs/file content "01.Guide" "10.Basics" "01.getting-started.md") "\n\nAn edit.\n" :append true)
        (fs/delete (fs/path content "01.Guide" "10.Basics" "02.conventions.zh-Hant.md"))
        (spit (fs/file content "01.Guide" "10.Basics" "05.brand-new.md")
              "---\ntitle: Brand new\n---\n\nNew.\n")
        (spit (fs/file dir "site.edn")
              (str/replace (slurp (fs/file dir "site.edn")) "\"clogem-press demo\"" "\"Renamed demo\""))
        ;; … and the article rendered somewhere in the middle throws
        (let [calls (atom 0)
              real  page/article
              e     (with-redefs [page/article (fn [& args]
                                                 (if (= 6 (swap! calls inc))
                                                   (throw (StackOverflowError. "boom"))
                                                   (apply real args)))]
                      (try (build! dir) nil
                           (catch clojure.lang.ExceptionInfo e e)))]
          (is (some? e) "the build fails")
          (is (= 1 (:babashka/exit (ex-data e))) "non-zero exit, even for an Error rather than an Exception")
          (is (re-find #"could not render /\S*: boom" (str (ex-message e))) (ex-message e))
          (is (<= 6 @calls) "the sixth article page threw"))
        (is (= before (tree-hash out)) "dist/ is exactly what the good build left")
        (is (= ledger-before (slurp (fs/file ledger))) "and so is the ledger")
        (testing "with the fault gone, the same edits build, and the deleted variant's page goes"
          (let [printed (build! dir)]
            (is (re-find #"removed 1 stale page from" printed) printed)
            (is (not (fs/exists? (fs/path out "zh-Hant" "pages" "643259" "index.html"))))
            (is (str/includes? (slurp (fs/file out "index.html")) "Renamed demo"))
            (is (= "docs.example" (slurp (fs/file out "CNAME"))) "a file the build did not write stays")
            (is (fs/exists? (fs/path out ".nojekyll")))))))))

(deftest a-rebuild-with-no-change-writes-nothing
  (with-demo-copy
    (fn [dir out]
      (let [first-run (build! dir {:no-write true})
            [w u]     (written-counts first-run)]
        (is (pos? w) first-run)
        (is (zero? u) first-run)
        (spit (fs/file out "CNAME") "docs.example")
        (spit (fs/file out ".nojekyll") "")
        (let [mtimes   (into {} (for [p (fs/glob out "**")] [(str p) (fs/last-modified-time p)]))
              second-run (build! dir {:no-write true})]
          (is (re-find #"^clogem-press: \d+ pages \(\d+ articles, \d+ variants\) → \S+ \(0 written, \d+ unchanged\)$"
                       (last (str/split-lines second-run)))
              "the summary line keeps its 0.2.0 start and adds the counts")
          (is (= [0 w] (written-counts second-run)) second-run)
          (is (= mtimes (into {} (for [p (fs/glob out "**")] [(str p) (fs/last-modified-time p)])))
              "not a single file was touched")
          (is (= "docs.example" (slurp (fs/file out "CNAME"))))
          (is (fs/exists? (fs/path out ".nojekyll"))))
        (testing "a temp file a killed build left behind is cleared; a changed file is rewritten"
          (spit (fs/file out "index.html.clogem-tmp-123") "half")
          (spit (fs/file out ".index.html.clogem-tmp-456") "half")
          (spit (fs/file out "index.html") "tampered")
          (let [[w2 _] (written-counts (build! dir {:no-write true}))]
            (is (= 1 w2))
            (is (str/starts-with? (slurp (fs/file out "index.html")) "<!DOCTYPE html>"))
            (is (not (fs/exists? (fs/path out "index.html.clogem-tmp-123"))))
            (is (not (fs/exists? (fs/path out ".index.html.clogem-tmp-456"))))))))))

(defn- render-with-jobs
  "The site at `dir` rendered in memory with `n` render workers:
  [{file → bytes} ds]."
  [dir n]
  (let [cfg (first (diag/collecting
                    (config/load-config (str dir) nil {:content {:write-front-matter false}})))
        [m _] (diag/collecting (cli/analyse cfg))
        [r ds] (with-redefs [render/jobs (constantly n)]
                 (diag/collecting (render/render-site cfg m)))]
    [(into (sorted-map) (for [{:keys [file bytes source]} (:outputs r)]
                          [(str file) (vec (or bytes (fs/read-all-bytes source)))]))
     ds]))

(deftest one-worker-and-many-render-the-same-bytes-and-diagnostics
  (with-demo-copy
    (fn [dir _]
      ;; render-time findings in several files, so the order is tested
      (doseq [[f body] [["01.Guide/10.Basics/07.dead.md" "Links to [nowhere](./missing.md).\n\n::: nonsense\nx\n:::\n"]
                        ["01.Guide/20.Advanced/08.dead.md" "See [gone](../nope.md) and [also](./nope2.md).\n"]
                        ["_posts/2026-09-01-dead.md" "::: unknownbox\ny\n:::\n"]]]
        (spit (fs/file dir "content" f)
              (str "---\ntitle: " (fs/file-name f) "\npermalink: /pages/" (format "%06x" (bit-and (hash f) 0xffffff)) "/\n---\n\n" body)))
      (let [[one ds1]  (render-with-jobs dir 1)
            [many dsn] (render-with-jobs dir 4)]
        (is (< 200 (count one)))
        (is (= (keys one) (keys many)))
        (is (= [] (remove #(= (get one %) (get many %)) (keys one))) "byte-identical")
        (is (<= 3 (count ds1)) (pr-str ds1))
        (is (= ds1 dsn) "the same diagnostics, in the same order")
        (is (= ds1 (diag/sorted ds1)) "sorted: severity, file, line, message")))))

(deftest jobs-defaults-and-overrides
  (when-not (System/getenv render/jobs-env)
    (is (= (.availableProcessors (Runtime/getRuntime)) (render/jobs))))
  (testing "render-pages: an exception names its page, and nothing else is lost"
    (let [pages {"/a/" (fn [] [:p "a"]) "/b/" (fn [] (throw (ex-info "nope" {:x 1}))) "/c/" (fn [] [:p "c"])}]
      (doseq [n [1 3]]
        (let [e (try (render/render-pages pages n) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= "could not render /b/: nope" (ex-message e)) n)
          (is (= {:x 1 :babashka/exit 1 :clogem/uri "/b/"} (ex-data e)) n)))))
  (testing "pages come back sorted by URI with their diagnostics"
    (let [pages (into {} (for [i (range 20)]
                           [(format "/p%02d/" i) (fn [] (diag/warn! (format "f%02d.md" (- 19 i)) "w") [:p i])]))
          [rs ds] (render/render-pages pages 4)]
      (is (= (sort (keys pages)) (map first rs)))
      (is (= (map #(format "f%02d.md" %) (range 20)) (map :path ds))))))
