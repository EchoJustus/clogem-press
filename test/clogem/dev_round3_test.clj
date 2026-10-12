;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round3-test
  "P4-D.3: `bb dev`'s third fix round — mostly what D.2's own fixes
  introduced: `:build :out` guards, `serve`'s output directory, large
  files without a lingering mapping, a one-shot own-write filter, inotify
  use, a concurrent fetch, and the mutants that survived D.2.

  Each test names the item it pins down; each failed before its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private marker
  "`config/out-marker`, spelled out so this namespace loads, and fails,
  against the code before it."
  ".clogem-output")

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article, an asset, a strings file and an override,
  and `site-edn`; calls (f dir)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-r3"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (fs/create-dirs (fs/path dir "assets"))
      (spit (fs/file dir "assets" "mine.html") "<p>mine</p>")
      (spit (fs/file dir "assets" "demo.txt") "demo")
      (fs/create-dirs (fs/path dir "i18n"))
      (spit (fs/file dir "i18n" "en.edn") "{}")
      (fs/create-dirs (fs/path dir "overrides"))
      (spit (fs/file dir "overrides" "custom.css") "body{}")
      (fs/create-dirs (fs/path dir ".git"))
      (spit (fs/file dir ".git" "HEAD") "ref: refs/heads/main\n")
      (binding [search/*env* {}]
        (f dir))
      (finally (fs/delete-tree dir)))))

(defn- quiet [f]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (f)))

(defn- spec-of [v] (:spec (:org.babashka/cli (meta v))))

(defn- parse [v args] (bcli/parse-opts args {:spec (spec-of v)}))

(defn- tree
  "Every file under `dir` → its content, for an unchanged-tree check."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true})
              :when (fs/regular-file? p)]
          [(str (fs/relativize dir p)) (slurp (fs/file p))])))

(defn- refusal
  "Run `f`; the message of the ExceptionInfo it throws, or nil."
  [f]
  (try (quiet f) nil
       (catch clojure.lang.ExceptionInfo e
         (is (= 1 (:babashka/exit (ex-data e))))
         (str (ex-message e)))))

(defn- doctor-errors [dir]
  (try (:errors (quiet #(cli/doctor {:site-dir (str dir)})))
       (catch clojure.lang.ExceptionInfo e (:clogem/errors (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Item 1: `:build :out` can no longer name a source directory

(deftest an-output-directory-in-site-edn-can-never-be-a-source-directory
  (testing "item 1: D.2 made site.edn's :build :out take effect behind
            guards written for --out: \"assets\" passed doctor, the build
            swept assets/mine.html as a stale page, and clean deleted assets/"
    (doseq [[out re] [["assets"       #"is the assets directory"]
                      ["assets/sub"   #"is inside the assets directory"]
                      ["i18n"         #"is the i18n directory"]
                      ["content"      #"is the content directory"]
                      ["content/x"    #"is inside the content directory"]
                      ["overrides"    #"is the overrides directory"]
                      [".git"         #"is the site's .git"]
                      ["."            #"is the site directory itself"]
                      [".."           #"which contains the site directory"]
                      ["../elsewhere" #"outside the site directory"]
                      ["/tmp/clogem-r3-absolute" #"outside the site directory"]
                      ["link"         #"outside the site directory"]]]
      (testing (pr-str out)
        (with-site (assoc url-site :build {:out out})
          (fn [dir]
            (let [elsewhere (fs/create-temp-dir {:prefix "clogem-r3-link"})]
              (try
                (fs/create-sym-link (fs/path dir "link") elsewhere)
                (let [before (tree dir)]
                  (doseq [[task f] [["build" #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir)))]
                                    ["clean" #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir)))]]]
                    (let [msg (refusal f)]
                      (is (some? msg) (str task " refuses it"))
                      (is (re-find re (str msg)) (str task ": " msg))))
                  (is (some #(re-find re (:message %)) (doctor-errors dir)) "doctor: an error")
                  (is (= before (tree dir)) "nothing written or deleted"))
                (finally (fs/delete-tree elsewhere))))))))))

(deftest an-output-directory-given-with-out-may-not-be-a-source-directory-either
  (testing "item 1: --out is refused for the same directories, but may point
            outside the site (CI's --out ../dist)"
    (with-site url-site
      (fn [dir]
        (let [before (tree dir)]
          (doseq [[out re] [["assets" #"is the assets directory"]
                            ["i18n"   #"is the i18n directory"]
                            ["content/x" #"is inside the content directory"]
                            [".."     #"which contains the site directory"]]]
            (let [msg (refusal #(cli/build (assoc (parse #'cli/build ["--out" out]) :site-dir (str dir))))]
              (is (re-find re (str msg)) (str out ": " msg)))
            (let [msg (refusal #(cli/clean (assoc (parse #'cli/clean ["--out" out]) :site-dir (str dir))))]
              (is (re-find re (str msg)) (str "clean " out ": " msg))))
          (is (= before (tree dir))))
        (let [sibling (fs/path (fs/parent dir) (str (fs/file-name dir) "-dist"))]
          (try
            (quiet #(cli/build (assoc (parse #'cli/build ["--out" (str "../" (fs/file-name sibling))]) :site-dir (str dir))))
            (is (fs/exists? (fs/path sibling "pages" "t00001" "index.html")) "--out ../x builds")
            (is (fs/exists? (fs/path sibling marker)) "and is marked")
            (quiet #(cli/clean (assoc (parse #'cli/clean ["--out" (str "../" (fs/file-name sibling))]) :site-dir (str dir))))
            (is (not (fs/exists? sibling)) "clean removes what the build made")
            (finally (fs/delete-tree sibling))))))))

(deftest only-a-directory-clogem-made-is-written-swept-or-cleaned
  (testing "item 1: an unrelated directory as the output is never swept or deleted"
    (with-site url-site
      (fn [dir]
        (let [mine (fs/path dir "dist")]
          (fs/create-dirs mine)
          (spit (fs/file mine "notes.html") "<p>my notes</p>")
          (let [before (tree mine)]
            (doseq [[task f] [["build" #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir)))]
                              ["clean" #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir)))]]]
              (let [msg (refusal f)]
                (is (re-find #"was not made by clogem-press" (str msg)) (str task ": " msg))))
            (is (some #(re-find #"was not made by clogem-press" (:message %)) (doctor-errors dir)))
            (is (= before (tree mine)) "untouched"))
          (fs/delete-tree mine))
        (testing "an empty one is fine, and marked"
          (fs/create-dirs (fs/path dir "dist"))
          (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
          (is (fs/exists? (fs/path dir "dist" marker))))
        (testing "a build's stale pages are still swept, and clean removes it"
          (spit (fs/file dir "dist" "stale.html") "<p>old</p>")
          (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
          (is (not (fs/exists? (fs/path dir "dist" "stale.html"))))
          (quiet #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir))))
          (is (not (fs/exists? (fs/path dir "dist")))))
        (testing "a 0.2.0 dist/ — clogem/ and index.html, no marker — is adopted"
          (fs/create-dirs (fs/path dir "dist" "clogem"))
          (spit (fs/file dir "dist" "index.html") "<p>old home</p>")
          (spit (fs/file dir "dist" "gone.html") "<p>old</p>")
          (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
          (is (not (fs/exists? (fs/path dir "dist" "gone.html"))))
          (is (fs/exists? (fs/path dir "dist" marker))))
        (testing "search's sweep keeps out of an unmarked directory"
          (let [d (fs/path dir "plain")]
            (fs/create-dirs (fs/path d ".pagefind-prev"))
            (is (empty? (search/sweep-staging! d)))
            (is (fs/exists? (fs/path d ".pagefind-prev")))))))))

;; ---------------------------------------------------------------------------
;; Item 2: serve follows `:build :out`; dev sweeps every output directory

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms`; its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(defn- served-root
  "The directory `bb serve <args>` would serve in `dir`, by its handler: the
  path of the file it answers `/probe.txt` with, read back."
  [dir args]
  (let [got (atom nil)]
    (with-redefs [dev/run-server! (fn [h _] (reset! got h) :server)]
      (quiet #((requiring-resolve 'clogem.dev/start-serve!)
               (assoc (parse #'cli/serve (into ["--port" "1"] args)) :site-dir (str dir)))))
    (some-> (:body (@got {:uri "/probe.txt" :headers {"host" "localhost"}})) (String. "UTF-8"))))

(deftest serve-serves-the-output-directory-the-build-wrote
  (testing "item 2: with :build :out \"public\", bb serve looked for dist/"
    (with-site (assoc url-site :build {:out "public"})
      (fn [dir]
        (doseq [d ["public" "dist" "given" "elsewhere"]]
          (fs/create-dirs (fs/path dir d))
          (spit (fs/file dir d "probe.txt") d))
        (is (nil? (:dir (parse #'cli/serve []))) "no spec default to hide site.edn's")
        (is (= "public" (served-root dir [])) "site.edn's :build :out")
        (is (= "given" (served-root dir ["--out" "given"])) "--out wins")
        (is (= "elsewhere" (served-root dir ["--out" "given" "--dir" (str (fs/path dir "elsewhere"))]))
            "--dir wins over both")
        (testing "an unreadable site.edn warns once and serves dist/"
          (spit (fs/file dir "site.edn") "{:build {:out \"public\"")
          (let [err (java.io.StringWriter.)]
            (binding [*err* err]
              (let [got (atom nil)]
                (with-redefs [dev/run-server! (fn [h _] (reset! got h) :server)]
                  (binding [*out* (java.io.StringWriter.)]
                    (dev/start-serve! (assoc (parse #'cli/serve ["--port" "1"]) :site-dir (str dir)))))
                (is (= "dist" (String. ^bytes (:body (@got {:uri "/probe.txt"})) "UTF-8")))))
            (is (= 1 (count (re-seq #"(?m)^warning: " (str err)))) (str err))))))))

(deftest dev-sweeps-every-output-directory-it-built-into
  (testing "item 2: after a mid-session :build :out change, stop! swept only
            the new one, and dev's previous bundles stayed in the old"
    (with-site (assoc url-site :build {:out "one"})
      (fn [dir]
        (let [me  (java.lang.ProcessHandle/current)
              st  (.toEpochMilli ^java.time.Instant (.get (.startInstant (.info me))))
              old (fs/path dir "one" (str ".pagefind-prev-" (.pid me) "-" st "-9"))
              h   (quiet #(dev/start-dev! {:site-dir (str dir) :poll true :no-write true}
                                          {:run-server! (fn [& _] (fn [& _] nil))
                                           :poll-watch! (fn [& _] nil)
                                           :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (fs/exists? (fs/path dir "one" "index.html")))
            (fs/create-dirs old)
            (spit (fs/file dir "site.edn") (pr-str (assoc url-site :build {:out "two"})))
            ((:submit! h) [(str (fs/path dir "site.edn"))])
            (is (wait-until #(fs/exists? (fs/path dir "two" "index.html"))))
            (is (wait-until #(= "two" (str (fs/file-name (config/out-dir @(:cfg-ref h)))))))
            (finally (quiet (:stop! h))))
          (is (not (fs/exists? old)) "the old directory's previous bundle went too"))))))
