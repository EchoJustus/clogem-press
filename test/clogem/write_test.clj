;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.write-test
  "Build robustness and speed (Phase 4 Task A, D-P4-11) and cache-busting
  (D-P4-7), end to end over a copy of `examples/demo-site`.

  The robustness case is the pre-flight's reproduction: a good build, then an
  edited article, a deleted zh-Hant variant, a new article and a renamed site
  title, with one page in the middle of the render throwing. 0.2.0 left a
  mixed dist/ — 150 new pages and 86 old, the new title on some pages only,
  the deleted variant still served, the old feed and sitemap — and exited
  non-zero. Now dist/ (and the ledger) must be byte-for-byte what the good
  build left."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.assets :as assets]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
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
      ;; a local `bb build` of the demo leaves dist/ (gitignored) behind;
      ;; the copy starts without any build output
      (doseq [d (fs/list-dir dir)
              :when (str/starts-with? (str (fs/file-name d)) "dist")]
        (fs/delete-tree d))
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

(defn- age!
  "Set `p`'s mtime an hour back: older than any live build's temp file."
  [p]
  (fs/set-last-modified-time p (java.nio.file.attribute.FileTime/fromMillis
                                (- (System/currentTimeMillis) (* 60 60 1000)))))

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
                                                   (throw (AssertionError. "boom"))
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
          ;; legacy names carry no PID: only their age marks them abandoned
          (doseq [f ["index.html.clogem-tmp-123" ".index.html.clogem-tmp-456"]]
            (age! (fs/path out f)))
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

;; ---------------------------------------------------------------------------
;; Cache-busting (D-P4-7)

(defn- version-refs
  "Every `<path>?v=<v>` in the HTML and CSS under `out`, as [file path v]:
  `href`/`src` attributes and CSS `url()`s alike."
  [out]
  (for [f (concat (fs/glob out "**.html") (fs/glob out "**.css"))
        :let [s (slurp (fs/file f))]
        [_ p1 v1 p2 v2] (re-seq #"(?:href|src)=\"([^\"?]+)\?v=([^\"&#]+)\"|url\(\"?([^\"?)]+)\?v=([^\")]+)\"?\)" s)
        :let [path (or p1 p2) v (or v1 v2)]
        :when path]
    [f path v]))

(defn- ref-file
  "The file under `out` that `path`, referenced from file `f`, names: an
  absolute path from the out root, a relative one from `f`'s directory."
  [out f path]
  (if (str/starts-with? path "/")
    (fs/path out (str/replace path #"^/" ""))
    (fs/normalize (fs/path (fs/parent f) path))))

(defn- check-fingerprints!
  "Every `?v=` under `out` names the bytes of its file; returns the refs."
  [out]
  (let [refs (version-refs out)]
    (doseq [[f path v] refs
            :let [file (ref-file out f path)
                  rel  (str/replace (str (fs/relativize out file)) "\\" "/")]]
      (if (str/starts-with? rel "pagefind/")
        (is (= "1.5.2" v) path)
        (is (= (subs (search/sha256-hex file) 0 8) v) (str f ": " path))))
    (is (every? (fn [[f path]]
                  (if (str/starts-with? path "/")
                    (re-find #"^/(clogem|pagefind)/" path)
                    (str/starts-with? (str/replace (str (fs/relativize out (ref-file out f path))) "\\" "/")
                                      "clogem/")))
                refs)
        "only theme and Pagefind URLs are versioned")
    refs))

(deftest every-fingerprint-names-the-bytes-of-its-file
  (with-demo-copy
    (fn [dir out]
      (binding [search/*env* {"CLOGEM_PAGEFIND" (fake/fake-pagefind! (fs/path dir "bin"))}]
        (with-out-str (cli/build {:site-dir (str dir) :no-write true})))
      ;; the Pagefind UI files are written by Pagefind, not by this build,
      ;; and carry its pinned version instead
      (let [refs (check-fingerprints! out)]
        (is (seq refs))
        (testing "and the theme files the head links all carry one"
          (let [h (slurp (fs/file out "pages" "643259" "index.html"))]
            (is (empty? (re-seq #"(?:href|src)=\"/clogem/[^\"?]+\"" h)))
            (is (str/includes? h "bundle-path=\"/pagefind/\"") "the bundle directory itself is not versioned")))
        (is (not-any? #(str/ends-with? (str (first %)) ".css") refs)
            "the demo ships no stylesheet with url()s: :fonts :system"))
      (testing "with the Tamil font self-hosted, the stylesheet's url()s are checked too"
        (let [f   (fs/file dir "site.edn")
              edn (clojure.edn/read-string (slurp f))]
          (spit f (pr-str (assoc-in edn [:theme :fonts :tamil] :self-hosted))))
        (with-out-str (cli/build {:site-dir (str dir) :no-write true :no-search true}))
        (let [css-refs (filter #(str/ends-with? (str (first %)) ".css") (check-fingerprints! out))]
          (is (= #{"noto-sans-tamil-400.woff2" "noto-sans-tamil-700.woff2"} (set (map second css-refs)))
              (pr-str css-refs)))))))

(deftest our-css-versions-its-own-url-references
  (let [cfg (first (diag/collecting (config/load-config demo nil {:theme {:fonts {:tamil :self-hosted}}})))
        fs* (assets/files cfg)
        css (String. ^bytes (get fs* "fonts/tamil.css") "UTF-8")]
    (doseq [w ["noto-sans-tamil-400.woff2" "noto-sans-tamil-700.woff2"]]
      (is (str/includes? css (str "url(\"" w "?v=" (assets/fingerprint (get fs* (str "fonts/" w))) "\")")) w))
    (is (= (assets/version (assoc cfg :clogem/asset-versions (assets/versions fs*)) "fonts/tamil.css")
           (assets/fingerprint (.getBytes css "UTF-8")))
        "the stylesheet's own fingerprint is of its rewritten bytes")
    (is (= "a{b:url(data:x)} c{d:url(\"/abs.png\")} e{f:url(x.png?v=1)}"
           (#'assets/rewrite-css "a{b:url(data:x)} c{d:url(\"/abs.png\")} e{f:url(x.png?v=1)}" "fonts" (constantly "zz")))
        "data:, absolute and already-versioned URLs are left alone")))

(deftest css-url-versions-do-not-depend-on-the-path-separator
  ;; Windows stringifies a path with `\`; the keys are `/`-separated (F6)
  (let [real fs/normalize
        vs   {"fonts/a.woff2" "11111111" "b.png" "22222222"}]
    (with-redefs [fs/normalize (fn [p] (str/replace (str (real p)) "/" "\\"))]
      (is (= "x{src:url(\"a.woff2?v=11111111\")} y{u:url(../b.png?v=22222222)}"
             (#'assets/rewrite-css "x{src:url(\"a.woff2\")} y{u:url(../b.png)}" "fonts" vs))))))

(deftest the-version-is-stripped-back-to-the-0-2-0-bytes
  (testing "assets/strip-versions undoes exactly what this build adds"
    (is (= "<link href=\"/clogem/css/theme.css\" /><script src=\"/pagefind/pagefind-component-ui.js\">"
           (assets/strip-versions
            "<link href=\"/clogem/css/theme.css?v=0123abcd\" /><script src=\"/pagefind/pagefind-component-ui.js?v=1.5.2\">")))))

;; ---------------------------------------------------------------------------
;; Byte-identity with 0.2.0, modulo `?v=` (opt-in)

(def ^:private reference-rev-env
  "Set to a git revision (e.g. 03249b7, release 0.2.0) to compare the demo's
  `dist/` against that revision's, with every `?v=` stripped. Opt-in: the
  comparison holds only until a later change alters the output on purpose,
  and CI's shallow checkout has no history to compare against."
  "CLOGEM_COMPARE_REV")

(defn- build-demo-at!
  "Build the demo with the generator at `gen-dir` into `out` (search off,
  read-only), as a subprocess so the two generators never share a JVM."
  [gen-dir out]
  (p/shell {:dir (str (fs/path gen-dir "examples" "demo-site")) :out :string :err :string}
           (str (or (some-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)) "bb"))
           "--config" (str (fs/path gen-dir "bb.edn"))
           "build" "--no-write" "--no-search" "--out" (str out)))

(deftest stripping-versions-gives-the-reference-build
  (if-let [rev (System/getenv reference-rev-env)]
    (let [tmp (fs/create-temp-dir {:prefix "clogem-ref"})
          wt  (fs/path tmp "ref")]
      (try
        (p/shell {:out :string :err :string} "git" "worktree" "add" "--detach" (str wt) rev)
        (build-demo-at! wt (fs/path tmp "ref-dist"))
        (build-demo-at! (fs/absolutize ".") (fs/path tmp "new-dist"))
        (let [a (fs/path tmp "ref-dist") b (fs/path tmp "new-dist")
              ra (set (map #(str (fs/relativize a %)) (filter fs/regular-file? (fs/glob a "**"))))
              rb (set (map #(str (fs/relativize b %)) (filter fs/regular-file? (fs/glob b "**"))))
              diffs (for [r (sort ra)
                          :let [x (fs/read-all-bytes (fs/path a r))
                                y (fs/read-all-bytes (fs/path b r))]
                          :when (not (if (re-find #"\.(html|css)$" r)
                                       (= (String. ^bytes x "UTF-8")
                                          (assets/strip-versions (String. ^bytes y "UTF-8")))
                                       (java.util.Arrays/equals ^bytes x ^bytes y)))]
                      r)]
          (println (format "stripping-versions-gives-the-reference-build: %d files compared against %s"
                           (count ra) rev))
          (is (= ra rb) "the same files")
          (is (empty? diffs) (pr-str (take 5 diffs))))
        (finally
          (p/shell {:out :string :err :string :continue true} "git" "worktree" "remove" "--force" (str wt))
          (fs/delete-tree tmp))))
    (println (str "stripping-versions-gives-the-reference-build: skipped — " reference-rev-env " is not set"))))

;; ---------------------------------------------------------------------------
;; Fix round P4-A.1: the write path

(defn- write!
  "Write `outputs` ({:file :bytes}) into `out` the way a build does."
  [out outputs]
  (render/write-site! {:outputs (vec outputs) :out (str out)}))

(defn- out-of [file s] {:file file :bytes (.getBytes ^String s "UTF-8")})

(defn- temp-files
  "Every file under `dir` whose name carries the temp marker."
  [dir]
  (for [p (fs/glob dir "**" {:hidden true :follow-links false})
        :when (str/includes? (str (fs/file-name p)) ".clogem-tmp-")]
    (str p)))

(defn- dead-pid
  "The PID of a process that has already exited."
  []
  (let [proc (p/process ["true"])]
    @proc
    (.pid ^Process (:proc proc))))

(defn- with-temp-dir [f]
  (let [d (fs/create-temp-dir {:prefix "clogem-wp"})]
    (try (binding [diag/*sink* (atom [])] (f d))
         (finally (fs/delete-tree d)))))

(deftest an-unreadable-site-asset-leaves-dist-untouched
  (with-demo-copy
    (fn [dir out]
      (build! dir)
      (let [before        (tree-hash out)
            ledger        (fs/path dir "permalinks.edn")
            ledger-before (slurp (fs/file ledger))
            boom          (fs/path dir "assets" "zz-boom.bin")
            real          fs/read-all-bytes]
        (spit (fs/file boom) "unreadable")
        (spit (fs/file dir "site.edn")
              (str/replace (slurp (fs/file dir "site.edn")) "\"clogem-press demo\"" "\"Renamed demo\""))
        ;; a new article: a build that got as far as the ledger would add it
        (spit (fs/file dir "content" "01.Guide" "10.Basics" "05.brand-new.md")
              "---\ntitle: Brand new\n---\n\nNew.\n")
        (let [e (with-redefs [fs/read-all-bytes
                              (fn [p & more]
                                (if (= "zz-boom.bin" (str (fs/file-name p)))
                                  (throw (java.io.IOException. "Permission denied"))
                                  (apply real p more)))]
                  (try (build! dir) nil (catch Exception e e)))]
          (is (some? e) "the build fails")
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (re-find #"zz-boom\.bin" (str (ex-message e))) (ex-message e)))
        (is (= before (tree-hash out)) "dist/ is exactly what the good build left")
        (is (= ledger-before (slurp (fs/file ledger))) "and so is the ledger")))))

(deftest long-file-names-write-and-leave-no-temp-file
  (with-temp-dir
    (fn [d]
      (let [out   (fs/path d "out")
            names (concat (for [n [230 240 255]]
                            (str (apply str (repeat (- n 4) "a")) ".png"))
                          [(str (apply str (repeat 76 "图")) ".png")])]
        (is (= [230 240 255 232] (map #(alength (.getBytes ^String % "UTF-8")) names)))
        (let [{:keys [written]} (write! out (for [n names] (out-of (fs/path out "assets" n) n)))]
          (is (= 4 written)))
        (doseq [n names]
          (is (= n (slurp (fs/file out "assets" n))) (str (count n) " chars")))
        (is (empty? (temp-files out)))))))

(deftest a-directory-where-a-file-now-belongs-fails-the-build
  (with-temp-dir
    (fn [d]
      (let [out    (fs/path d "out")
            blocker (fs/path out "assets" "docs")
            readme (fs/path blocker "readme.txt")]
        (fs/create-dirs blocker)
        (spit (fs/file readme) "kept")
        (dotimes [_ 2]
          (let [e (try (write! out [(out-of (fs/path out "a.txt") "a")
                                    (out-of blocker "now a file")])
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) "it fails rather than reporting the file written")
            (is (= 1 (:babashka/exit (ex-data e))))
            (is (str/includes? (str (ex-message e)) (str (fs/path "assets" "docs"))) (ex-message e))
            (is (re-find #"clean" (str (ex-message e))) (ex-message e))))
        (is (empty? (temp-files out)) "no temp file is left behind, however often it runs")
        (is (= "kept" (slurp (fs/file readme))))
        (is (= ["readme.txt"] (map #(str (fs/file-name %)) (fs/list-dir blocker))))
        (is (not (fs/exists? (fs/path out "a.txt"))) "checked before anything is written")))))

(deftest abandoned-temp-files-are-swept-live-ones-kept
  (with-temp-dir
    (fn [d]
      (let [out   (fs/path d "out")
            gone  (fs/path out "pages" "gone")
            dead  (dead-pid)
            live  (.pid (java.lang.ProcessHandle/current))
            plant (fn [p & [old?]]
                    (fs/create-dirs (fs/parent p))
                    (spit (fs/file p) "half")
                    (when old? (age! p))
                    p)]
        (write! out [(out-of (fs/path out "index.html") "home")
                     (out-of (fs/path gone "index.html") "a deleted article")])
        (let [abandoned-old  (plant (fs/path gone (str ".clogem-tmp-" dead "-1")) true)
              abandoned-dead (plant (fs/path out "assets" (str ".clogem-tmp-" dead "-2")))
              legacy-old     (plant (fs/path out ".index.html.clogem-tmp-99") true)
              live-fresh     (plant (fs/path out (str ".clogem-tmp-" live "-3")))
              legacy-fresh   (plant (fs/path out ".x.clogem-tmp-98"))
              elsewhere      (fs/path d "elsewhere")
              linked         (plant (fs/path elsewhere (str ".clogem-tmp-" dead "-4")) true)]
          (spit (fs/file out "CNAME") "docs.example")
          (spit (fs/file out ".nojekyll") "")
          (fs/create-sym-link (fs/path out "linked") elsewhere)
          ;; the next build no longer writes pages/gone/
          (write! out [(out-of (fs/path out "index.html") "home")])
          (is (not (fs/exists? abandoned-old)) "old, dead PID, in a directory no longer written")
          (is (not (fs/exists? gone)) "and that directory goes with the stale page")
          (is (not (fs/exists? abandoned-dead)) "fresh but its build is dead")
          (is (not (fs/exists? legacy-old)) "a legacy name, old")
          (is (fs/exists? live-fresh) "a live build's in-flight temp file stays")
          (is (fs/exists? legacy-fresh) "a fresh legacy name stays: it could still be in flight")
          (is (fs/exists? linked) "never through a link")
          (is (= "docs.example" (slurp (fs/file out "CNAME"))))
          (is (fs/exists? (fs/path out ".nojekyll"))))))))

(deftest concurrent-writes-into-one-out-dir-never-throw
  ;; `bb dev` and `bb build` into one dist/: each build's sweep used to
  ;; delete the other's in-flight temp files (NoSuchFileException). Two
  ;; writers, each writing 20 times over, keep their sweeps and writes
  ;; interleaved; three such runs.
  (dotimes [run 3]
    (with-temp-dir
      (fn [d]
        (let [out     (fs/path d "out")
              outputs (fn [tag r]
                        (for [i (range 40)]
                          (out-of (fs/path out (str "d" (mod i 4)) (str "f" i ".txt"))
                                  (str tag r (apply str (repeat 500 (str i)))))))
              worker  (fn [tag]
                        (future
                          (try (binding [diag/*sink* (atom [])]
                                 (dotimes [r 20] (write! out (outputs tag r))))
                               nil
                               (catch Throwable t t))))]
          (is (= [nil nil] (mapv deref [(worker "a") (worker "b")])) (str "run " run))
          (is (empty? (temp-files out))))))))

(deftest the-exact-name-of-a-directory-entry-decides-same-bytes
  (is (render/exact-entry? #{"logo.png"} (fs/path "x" "logo.png")))
  (is (not (render/exact-entry? #{"Logo.PNG"} (fs/path "x" "logo.png")))
      "a case-insensitive file system's match on Logo.PNG is not logo.png")
  (is (not (render/exact-entry? #{} (fs/path "x" "logo.png")))))

(defn- case-insensitive-fs?
  "Does the file system under `d` resolve a name in another case?"
  [d]
  (let [f (fs/path d "CaseProbe")]
    (spit (fs/file f) "")
    (try (fs/exists? (fs/path d "caseprobe"))
         (finally (fs/delete f)))))

(deftest a-case-only-rename-is-written-on-a-case-insensitive-fs
  (with-temp-dir
    (fn [d]
      (if-not (case-insensitive-fs? d)
        (println "a-case-only-rename-is-written-on-a-case-insensitive-fs: skipped — this file system is case-sensitive")
        (let [out (fs/path d "out")]
          (write! out [(out-of (fs/path out "assets" "Logo.PNG") "png")])
          (write! out [(out-of (fs/path out "assets" "logo.png") "png")])
          (is (= ["logo.png"] (map #(str (fs/file-name %)) (fs/list-dir (fs/path out "assets"))))))))))

(deftest an-output-outside-the-out-dir-is-refused
  (with-temp-dir
    (fn [d]
      (let [out     (fs/path d "site" "dist")
            outside (fs/path d "escaped" "index.html")
            planted (fs/path d "escaped" (str ".clogem-tmp-" (dead-pid) "-1"))]
        (fs/create-dirs (fs/parent planted))
        (spit (fs/file planted) "not ours")
        (age! planted)
        (let [e (try (write! out [(out-of (fs/path out "index.html") "home")
                                  (out-of (fs/path out ".." ".." "escaped" "index.html") "escaped")])
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e))
          (is (= 1 (:babashka/exit (ex-data e))))
          (is (re-find #"outside the output directory" (str (ex-message e))) (ex-message e)))
        (is (not (fs/exists? outside)))
        (is (not (fs/exists? (fs/path out "index.html"))) "refused before anything is written")
        (is (fs/exists? planted) "a temp-looking file outside out is never swept")))))

(deftest a-dot-dot-permalink-is-an-error-naming-its-file
  (with-demo-copy
    (fn [dir out]
      (let [escaped (fs/path dir ".." ".." "escaped")
            rel     "01.Guide/10.Basics/06.escape.md"]
        (when (fs/exists? escaped)
          (throw (ex-info (str escaped " exists before the test") {})))
        (try
          (spit (fs/file dir "content" rel)
                "---\ntitle: Escape\npermalink: /../../escaped/\n---\n\nOut.\n")
          (let [errs (try (:errors (cli/doctor {:site-dir (str dir)}))
                          (catch clojure.lang.ExceptionInfo e (:clogem/errors (ex-data e))))]
            (is (some #(and (re-find #"06\.escape\.md" (str (:path %)))
                            (re-find #"permalink" (str (:message %))))
                      errs)
                (pr-str (map (juxt :path :message) errs))))
          (doseq [no-write [true false]]
            (let [e (try (build! dir {:no-write no-write}) nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (some? e) (str "no-write=" no-write))
              (is (re-find #"06\.escape\.md" (str (ex-message e))) (ex-message e))))
          (is (not (fs/exists? escaped)) "nothing is written outside out")
          (finally (fs/delete-tree escaped)))))))
