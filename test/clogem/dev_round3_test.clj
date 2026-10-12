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

;; ---------------------------------------------------------------------------
;; Item 4: a build's own write is filtered once

(deftest an-undo-to-the-bytes-the-build-wrote-rebuilds
  (testing "item 4: within 3 s of an auto-fill, a save restoring exactly what
            the build wrote was taken for the build's own write; dist/ kept
            the intermediate edit"
    (with-site (assoc-in url-site [:content :write-front-matter] true)
      (fn [dir]
        (let [a      (fs/path dir "content" "01.Guide" "02.new.md")
              builds (atom 0)
              _      (spit (fs/file a) "---\ntitle: New\ndate: \"2026-02-01 00:00:00\"\n---\n\nBody\n")
              h      (quiet #(dev/start-dev! {:site-dir (str dir) :poll true}
                                             {:run-server! (fn [& _] (fn [& _] nil))
                                              :poll-watch! (fn [& _] nil)
                                              :notify! (fn [_]) :notify-error! (fn [_])
                                              :build (fn [o] (swap! builds inc) (cli/build o))}))]
          (try
            (let [filled (slurp (fs/file a))
                  page   (fs/file dir "dist" "pages" (second (re-find #"permalink: /pages/([^/]+)/" filled)) "index.html")]
              (is (str/includes? filled "permalink:") "auto-filled")
              (quiet #((:submit! h) [(str a)]))
              (Thread/sleep 400)
              (is (= 1 @builds) "the auto-fill's own event rebuilds nothing")
              (spit (fs/file a) (str/replace filled "Body" "Edited"))
              ((:submit! h) [(str a)])
              (is (wait-until #(= 2 @builds)) "the edit rebuilds")
              (is (wait-until #(str/includes? (slurp page) "Edited")))
              (spit (fs/file a) filled)
              ((:submit! h) [(str a)])
              (is (wait-until #(= 3 @builds)) "the undo rebuilds too")
              (is (wait-until #(not (str/includes? (slurp page) "Edited"))) "and dist/ has the undo"))
            (finally (quiet (:stop! h)))))))))

;; ---------------------------------------------------------------------------
;; Item 5: inotify use

(defn- n [p] (str (fs/normalize (fs/absolutize (str p)))))

(defn- pod-dev
  "`start-dev!` over `dir` with a fake pod. Every registration is logged in
  `regs` and every unwatch in `unwatched`; `live` holds the watches not
  unwatched. `(event! path type)` delivers an event as the pod would. With
  `fail?`, a (fn [spec]) — a registration it says yes to throws its
  answer (a message). Calls (f ctx), then stops dev."
  [dir {:keys [fail? follow-ms] :or {follow-ms 50}} f]
  (let [regs (atom []) unwatched (atom []) builds (atom 0) polls (atom 0)
        out (java.io.StringWriter.) cb (atom nil) k (atom 0)
        register! (fn [spec]
                    (when-let [msg (and fail? (fail? spec))]
                      (throw (ex-info msg {})))
                    (let [w {:w (swap! k inc) :path (:path spec)}]
                      (swap! regs conj w)
                      w))
        pod (fn [_cfg on-change {:keys [specs accept? on-ready]}]
              (reset! cb {:on-change on-change :accept? accept?})
              (let [ws (mapv register! specs)]
                (on-ready register! {:unwatch! #(swap! unwatched conj %)
                                     :watchers (zipmap (map :path specs) ws)}))
              true)
        event! (fn [p & [type]]
                 (when-let [{:keys [on-change accept?]} @cb]
                   (when (accept? (str p)) (on-change (with-meta [(str p)] {:type type})))))
        h (binding [*out* out *err* out]
            (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true}
                            {:run-server! (fn [& _] (fn [& _] nil))
                             :try-pod-watch! pod
                             :poll-watch! (fn [& _] (swap! polls inc) nil)
                             :follow-ms follow-ms
                             :notify! (fn [_]) :notify-error! (fn [_])
                             :build (fn [o] (swap! builds inc) (cli/build o))}))]
    (try
      (f {:regs regs :unwatched unwatched :builds builds :polls polls :out out :event! event!
          :live (fn [] (remove (set @unwatched) @regs))
          :regs-of (fn [p] (filterv #(= (n p) (:path %)) @regs))})
      (finally (binding [*out* out *err* out] ((:stop! h)))))))

(deftest a-directory-a-registration-covers-gets-no-registration-of-its-own
  (testing "item 5: chmod -R over 14 existing subdirectories took 14 more
            inotify instances; the per-user limit is 128"
    (with-site url-site
      (fn [dir]
        (let [subs (for [i (range 14)] (fs/path dir "content" (format "%02d.Sub" (+ 10 i))))]
          (doseq [d subs] (fs/create-dirs (fs/path d "inner")))
          (pod-dev dir {}
                   (fn [{:keys [regs event! regs-of builds]}]
                     (let [n0 (count @regs)]
                       (doseq [d subs] (event! d :chmod) (event! (fs/path d "inner") :chmod))
                       (Thread/sleep 400)
                       (is (= n0 (count @regs)) "no registration for a covered directory")
                       (testing "a new directory is registered once, and covers its children"
                         (let [t (fs/path dir "content" "30.New")]
                           (fs/create-dirs (fs/path t "a" "b"))
                           (event! t :create)
                           (is (wait-until #(= 1 (count (regs-of t)))))
                           (event! (fs/path t "a") :chmod)
                           ;; a child made before the walk never has a :create
                           ;; of its own: the parent's watch did not exist yet
                           (event! (fs/path t "a" "b") :chmod)
                           (Thread/sleep 300)
                           (is (= (inc n0) (count @regs)) "its children are covered")))
                       (testing "rm -rf and mkdir, seen as one :remove: registered again"
                         (let [d (first subs)]
                           (fs/delete-tree d)
                           (fs/create-dirs d)
                           (event! d :remove)
                           (is (wait-until #(= 1 (count (regs-of d)))))))))))))))

(deftest a-failed-re-registration-keeps-the-working-watch-and-is-retried
  (testing "item 5: the old watch was unwatched first, and nothing retried"
    (with-site url-site
      (fn [dir]
        (let [ov    (fs/path dir "overrides")
              fails (atom 0)]
          (pod-dev dir {:fail? (fn [spec] (when (and (= (n ov) (:path spec)) (pos? @fails))
                                            (swap! fails dec)
                                            "the pod said no"))}
                   (fn [{:keys [unwatched event! regs-of out]}]
                     (is (= 1 (count (regs-of ov))))
                     (reset! fails 1)
                     (event! ov :remove)
                     (is (wait-until #(str/includes? (str out) "cannot watch")) (str out))
                     (is (empty? (filter #(= (n ov) (:path %)) @unwatched)) "the working watch stays")
                     (is (wait-until #(= 2 (count (regs-of ov)))) "retried on the next pass")
                     (is (wait-until #(= 1 (count (filter (fn [w] (= (n ov) (:path w))) @unwatched))))
                         "and only then is the old one released")
                     (is (= 1 (count (re-seq #"cannot watch" (str out)))) "reported once"))))))))

(deftest a-deleted-directory-s-watch-is-released-while-events-keep-coming
  (testing "item 5: a mkdir/rm loop leaked one instance per cycle, the
            follower's cleanup pass starved by the events"
    (with-site url-site
      (fn [dir]
        (pod-dev dir {:follow-ms 400}
                 (fn [{:keys [regs live event!]}]
                   (let [n0   (count @regs)
                         ;; another directory's event every 30 ms, the whole
                         ;; time: the follower is never quiet for 400 ms
                         pump (future (loop [] (event! (fs/path dir "content") :chmod)
                                        (Thread/sleep 30) (recur)))]
                     (try
                       (dotimes [i 12]
                         (let [c (fs/path dir "assets" (str ".cache-" i))]
                           (fs/create-dirs c)
                           (event! c :create)
                           (Thread/sleep 60)
                           (fs/delete-tree c)
                           (event! c :remove)
                           (Thread/sleep 30)))
                       (is (< n0 (count @regs)) "the churn was registered")
                       (is (wait-until #(= n0 (count (live))) 3000)
                           (str "every churned watch released while events still come: "
                                (count (live)) " live, " n0 " at start"))
                       (finally (future-cancel pump))))))))))

(deftest the-inotify-limit-switches-the-session-to-polling
  (testing "item 5: ENOSPC/EMFILE — one clear line, then polling"
    (with-site url-site
      (fn [dir]
        (pod-dev dir {:fail? (fn [spec] (when (str/ends-with? (:path spec) "40.Full")
                                          "inotify_init1: too many open files"))}
                 (fn [{:keys [event! polls out]}]
                   (let [t (fs/path dir "content" "40.Full")]
                     (fs/create-dirs t)
                     (event! t :create)
                     (is (wait-until #(pos? @polls)) "polling started")
                     (is (= 1 (count (re-seq #"inotify limit is reached" (str out)))) (str out))
                     (is (re-find #"polls every \d+ ms for the rest of this session" (str out)))
                     (is (not (str/includes? (str out) "cannot watch")) "not a per-directory retry"))))))))

(defn- unshare-ok?
  "Can this machine make an unprivileged user namespace and set its
  inotify cap there (Ubuntu's AppArmor refuses it on CI)?"
  []
  (try (zero? (:exit (babashka.process/shell {:out :string :err :string :continue true}
                                             "unshare" "-U" "-r" "sh" "-c"
                                             "echo 9 > /proc/sys/user/max_inotify_instances")))
       (catch Exception _ false)))

(defn- inotify-instances
  "How many inotify instances the process `h` (a ProcessHandle) holds."
  [^java.lang.ProcessHandle h]
  (count (for [fd (try (fs/list-dir (str "/proc/" (.pid h) "/fd")) (catch Exception _ nil))
               :when (= "anon_inode:inotify" (try (str (fs/read-link fd)) (catch Exception _ nil)))]
           fd)))

(deftest the-real-pod-under-a-low-inotify-cap
  (testing "item 5, with the real pod in a user namespace capped at 12
            instances: chmod -R and a mkdir/rm loop take none, and reaching
            the cap is one line, then polling that still rebuilds"
    (cond
      (not (System/getenv "CLOGEM_FSWATCHER"))
      (println "the-real-pod-under-a-low-inotify-cap: CLOGEM_FSWATCHER is not set; skipped")
      (not (unshare-ok?))
      (println "the-real-pod-under-a-low-inotify-cap: no unprivileged user namespace here; skipped")
      :else
      (with-site url-site
        (fn [dir]
          (doseq [i (range 14)] (fs/create-dirs (fs/path dir "content" (str "9" i ".Sub") "inner")))
          (let [log  (fs/file dir "dev.log")
                bb   (str (or (fs/which "bb") "bb"))
                proc (babashka.process/process
                      {:dir (str dir) :out log :err log}
                      "unshare" "-U" "-r" "sh" "-c"
                      (str "echo 12 > /proc/sys/user/max_inotify_instances && exec " bb
                           " --config " (fs/absolutize "bb.edn") " dev --port 0 --no-search --no-write"))
                out  #(try (slurp log) (catch Exception _ ""))
                pod  #(some (fn [^java.lang.ProcessHandle h]
                              (when (str/includes? (str (.orElse (.command (.info h)) "")) "fswatcher") h))
                            (iterator-seq (.iterator (.descendants (.toHandle ^Process (:proc proc))))))
                builds #(count (re-seq #"rebuilt in" (out)))]
            (try
              (is (wait-until #(str/includes? (out) "watching via fswatcher") 30000) (out))
              (let [n0 (inotify-instances (pod))]
                (is (pos? n0))
                (babashka.process/shell "chmod" "-R" "g+w" (str (fs/path dir "content")))
                (Thread/sleep 1500)
                (is (= n0 (inotify-instances (pod))) "chmod -R: no new instance")
                (dotimes [i 6]
                  (let [c (fs/path dir "assets" (str ".cache-" i))]
                    (fs/create-dirs c) (Thread/sleep 250) (fs/delete c) (Thread/sleep 250)))
                (is (wait-until #(= n0 (inotify-instances (pod))) 3000) "the loop leaks none")
                (dotimes [i 20]
                  (fs/create-dirs (fs/path dir "assets" "copy" (str "f" i)))
                  (Thread/sleep 150))
                (is (wait-until #(str/includes? (out) "inotify limit is reached") 10000) (out))
                (is (= 1 (count (re-seq #"inotify limit is reached" (out)))))
                (is (not (str/includes? (out) "cannot watch")) (out))
                (let [b0 (builds)]
                  (spit (fs/file dir "content" "01.Guide" "01.t.md") (str article "\nmore\n"))
                  (is (wait-until #(> (builds) b0) 5000) (str "polling rebuilds: " (out)))))
              (finally
                (babashka.process/destroy-tree proc)
                (deref proc 5000 nil)))))))))

;; ---------------------------------------------------------------------------
;; Item 8: the mutants that survived D.2

(defn- dev-handler
  "The handler `start-dev!` serves with `args` (as `bb dev` parses them)."
  [dir args]
  (let [servers (atom [])
        h (quiet #(dev/start-dev! (assoc (parse #'cli/dev (into ["--poll" "--no-write"] args)) :site-dir (str dir))
                                  {:run-server! (fn [handler _] (swap! servers conj handler) (fn [& _] nil))
                                   :poll-watch! (fn [& _] nil)
                                   :notify! (fn [_]) :notify-error! (fn [_])}))]
    (quiet (:stop! h))
    (first @servers)))

(defn- serve-handler
  "The handler `start-serve!` serves `root` with `args` (as `bb serve` parses them)."
  [root args]
  (let [got (atom nil)]
    (with-redefs [dev/run-server! (fn [handler _] (reset! got handler) :server)]
      (quiet #(dev/start-serve! (assoc (parse #'cli/serve (into ["--dir" (str root)] args)) :site-dir (str root)))))
    @got))

(deftest every-interface-binds-have-no-host-check
  (testing "i17e, i17f: `:check-host?` forced true in dev or serve survived —
            with --host 0.0.0.0 a LAN name must be answered"
    (with-site url-site
      (fn [dir]
        (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
        (doseq [host ["0.0.0.0" "::"]]
          (testing (str "dev --host " host)
            (let [h (dev-handler dir ["--host" host])]
              (is (= 200 (:status (h {:uri "/pages/t00001/" :headers {"host" "192.168.1.5:1888"}}))))
              (is (= 200 (:status (h {:uri "/pages/t00001/" :headers {"host" "my-laptop.local:1888"}}))))))
          (testing (str "serve --host " host)
            (let [h (serve-handler (fs/path dir "dist") ["--host" host])]
              (is (= 200 (:status (h {:uri "/pages/t00001/" :headers {"host" "192.168.1.5:1888"}}))))))
          (testing "and on loopback the check is on, in both"
            (is (= 403 (:status ((dev-handler dir []) {:uri "/pages/t00001/" :headers {"host" "192.168.1.5"}}))))
            (is (= 403 (:status ((serve-handler (fs/path dir "dist") []) {:uri "/pages/t00001/" :headers {"host" "192.168.1.5"}}))))))))))

(deftest follower-registrations-are-spaced
  (testing "i1c: the gap before each of the follower's `watch` calls was
            removable — back-to-back registrations hang the pod"
    (with-site url-site
      (fn [dir]
        (let [log   (atom [])
              ready (promise)
              cfg   (first (diag/collecting (config/load-config (str dir))))]
          (quiet
           #(dev/probe-watch! cfg (fn [_])
                              {:watch   (fn [path cb _]
                                          (swap! log conj [:watch path])
                                          ;; deliver the probe's own event
                                          (future (Thread/sleep 20)
                                                  (doseq [f (fs/list-dir path)
                                                          :when (str/includes? (str (fs/file-name f)) ".clogem-watch-probe-")]
                                                    (cb {:path (str f) :type :create})))
                                          {:w path})
                               :unwatch (fn [_])
                               :sleep   (fn [ms] (swap! log conj [:sleep ms]))
                               :timeout-ms 3000
                               :on-ready (fn [register! _] (deliver ready register!))}))
          (let [register! (deref ready 3000 nil)]
            (is (some? register!))
            (reset! log [])
            (register! {:path (str (fs/path dir "content")) :recursive? true})
            (register! {:path (str (fs/path dir "assets")) :recursive? true})
            (is (= [[:sleep dev/registration-gap-ms] [:watch (str (fs/path dir "content"))]
                    [:sleep dev/registration-gap-ms] [:watch (str (fs/path dir "assets"))]]
                   @log)
                "a gap before each, so two in a row are spaced")))))))

;; ---------------------------------------------------------------------------
;; Item 9: the Host check follows the bind, however --host is spelled

(deftest every-loopback-spelling-gets-the-host-check-and-answers-its-own-url
  (testing "item 9: `--host 127.1` turned the check off, and `--host
            127.0.0.2` printed a URL the check refused"
    (with-site url-site
      (fn [dir]
        (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
        (doseq [host ["localhost" "127.1" "::1" "0:0:0:0:0:0:0:1" "[::1]" "127.0.0.2"]]
          (testing host
            (is (dev/loopback-ip? host))
            (let [servers (atom [])
                  h (quiet #(dev/start-dev! (assoc (parse #'cli/dev ["--poll" "--no-write" "--host" host]) :site-dir (str dir))
                                            {:run-server! (fn [handler _] (swap! servers conj handler) (fn [& _] nil))
                                             :poll-watch! (fn [& _] nil)
                                             :notify! (fn [_]) :notify-error! (fn [_])}))
                  handler (first @servers)
                  printed (.getAuthority (java.net.URI. (:url h)))]
              (quiet (:stop! h))
              (is (= 403 (:status (handler {:uri "/pages/t00001/" :headers {"host" "evil.example"}}))) "checked")
              (doseq [ok [printed "localhost:1888" "127.0.0.1" "[::1]:1888"]]
                (is (= 200 (:status (handler {:uri "/pages/t00001/" :headers {"host" ok}}))) (str "answers " ok)))
              (is (= 200 (:status ((serve-handler (fs/path dir "dist") ["--host" host])
                                   {:uri "/pages/t00001/" :headers {"host" printed}})))
                  "serve too"))))
        (is (not-any? dev/loopback-ip? ["0.0.0.0" "::" "192.0.2.1" "no-such-host.invalid"]))))))
