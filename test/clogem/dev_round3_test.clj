;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round3-test
  "P4-D.3: `bb dev`'s third fix round — mostly what D.2's own fixes
  introduced: `:build :out` guards, `serve`'s output directory, large
  files without a lingering mapping, a one-shot own-write filter, inotify
  use, a concurrent fetch, and the mutants that survived D.2.

  Each test names the item it pins down; each failed before its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [babashka.http-client :as http-client]
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

;; ---------------------------------------------------------------------------
;; Item 3: large files are streamed, never mapped

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-r3root"})]
    (try (f root) (finally (fs/delete-tree root)))))

(defn- sparse!
  "A sparse file of `n` bytes at `p`, its last byte 7."
  [p n]
  (with-open [f (java.io.RandomAccessFile. (fs/file p) "rw")]
    (.setLength f n)
    (.seek f (dec n))
    (.write f 7)))

(defn- with-server
  "Run the dev handler over `root` on a real http-kit server; (f get!)
  where (get! uri) opens a connection: {:status :headers :in}."
  [root f]
  (let [srv (dev/run-server! (dev/make-handler root {}) {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        port (org.httpkit.server/server-port srv)]
    (try
      (f (fn [uri]
           (let [r (http-client/get (str "http://127.0.0.1:" port uri)
                                    {:as :stream :throw false :timeout 20000})]
             {:status   (:status r)
              :chunked? (= "chunked" (get-in r [:headers "transfer-encoding"]))
              :in       (:body r)})))
      (finally (org.httpkit.server/server-stop! srv)))))

(defn- drain
  "Read `in` to its end: [byte count, last byte]."
  [^java.io.InputStream in]
  (with-open [in in]
    (let [buf (byte-array 65536)]
      (loop [n 0 last -1]
        (let [r (.read in buf)]
          (if (neg? r) [n last] (recur (+ n r) (if (pos? r) (aget buf (dec r)) last))))))))

(defn- maps-or-holds?
  "Does this process still map, or hold open, the file `p`? (Linux: /proc.)"
  [p]
  (let [s (str (fs/canonicalize p))]
    (boolean
     ;; `slurp` of a /proc file fails with EINVAL here; a stream reads it
     (or (str/includes? (with-open [in (java.io.FileInputStream. "/proc/self/maps")]
                          (String. (.readAllBytes in) "UTF-8"))
                        s)
         (some #(= s (try (str (fs/read-link %)) (catch Exception _ nil)))
               (fs/list-dir "/proc/self/fd"))))))

(defn- stream-threads []
  (filter #(= "clogem-file-stream" (.getName ^Thread %)) (keys (Thread/getAllStackTraces))))

(deftest a-large-file-is-streamed-and-released-when-the-response-ends
  (testing "item 3: a 1 MB+ file was a MappedByteBuffer that outlived the
            response — on Windows a rebuild could not replace it until a GC"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (+ 10 (* 2 1024 1024)))
        (let [h (dev/make-handler root {})]
          (is (bytes? (:body (h {:uri "/small.txt"}))) "small: read whole")
          (let [b (:body (h {:uri "/big.bin"}))]
            (is (not (instance? java.nio.ByteBuffer b)) "big: no mapping")
            (is (not (instance? java.io.File b)) "never a File")
            (when (instance? java.io.Closeable b) (.close ^java.io.Closeable b))))
        (with-server root
          (fn [get!]
            (let [{:keys [status chunked? in]} (get! "/big.bin")]
              (is (= 200 status))
              (is chunked? "streamed in chunks")
              (is (= [(+ 10 (* 2 1024 1024)) 7] (drain in)) "whole"))
            (is (wait-until #(empty? (stream-threads))) "its thread ends")
            (when (fs/exists? "/proc/self/maps")
              (is (wait-until #(not (maps-or-holds? (fs/path root "big.bin"))))
                  "nothing maps or holds the file once the response ends"))
            (testing "a client that goes away mid-download"
              (let [{:keys [in]} (get! "/big.bin")]
                (.read ^java.io.InputStream in)
                (.close ^java.io.InputStream in))
              (is (wait-until #(empty? (stream-threads)) 10000) "ends the stream")
              (when (fs/exists? "/proc/self/maps")
                (is (wait-until #(not (maps-or-holds? (fs/path root "big.bin"))) 10000))))))))))

(deftest a-file-of-2-gib-or-more-is-served
  (testing "item 3: FileChannel.map takes at most Integer.MAX_VALUE bytes,
            so a 2 GiB file was a 500"
    (with-root
      (fn [root]
        (let [n (+ 1024 (* 2 1024 1024 1024))]
          (sparse! (fs/path root "huge.bin") n)
          (with-server root
            (fn [get!]
              (let [t0 (System/currentTimeMillis)
                    {:keys [status in]} (get! "/huge.bin")]
                (is (= 200 status))
                (is (<= 0 (.read ^java.io.InputStream in)) "a first byte")
                (is (< (- (System/currentTimeMillis) t0) 2000) "at once, whatever the size")
                (is (= [(dec n) 7] (drain in)) "and the rest")))))))))

(deftest a-large-file-that-vanishes-is-a-404-without-a-path
  (testing "item 3 keeps finding 1: resolved, then gone before it is opened"
    (with-root
      (fn [root]
        (let [ghost (fs/path root "gone.bin")
              h     (dev/make-handler root {})]
          (with-redefs [dev/resolve-path (fn [& _] ghost)]
            (let [r (h {:uri "/gone.bin"})]
              (is (= 404 (:status r)))
              (is (not (str/includes? (str (:body r)) (str root))))))
          (testing "deleted mid-download: the open file is still sent whole"
            (sparse! (fs/path root "big.bin") (* 3 1024 1024))
            (with-server root
              (fn [get!]
                (let [{:keys [status in]} (get! "/big.bin")]
                  (is (= 200 status))
                  (fs/delete (fs/path root "big.bin"))
                  (is (= [(* 3 1024 1024) 7] (drain in))))))))))))
