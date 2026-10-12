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
            [clogem.fake-tools :as fake]
            [clogem.highlight :as highlight]
            [clogem.search :as search]
            [clogem.tools :as tools]))

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
;; Item 3: large files (their tests are dev-round4-test's: P4-D.4 item 1
;; replaced D.3's stream with a plain `File` body)

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-r3root"})]
    (try (f root) (finally (fs/delete-tree root)))))

(deftest a-large-file-that-vanishes-is-a-404-without-a-path
  (testing "item 3 keeps finding 1: resolved, then gone before it is opened"
    (with-root
      (fn [root]
        (let [ghost (fs/path root "gone.bin")
              h     (dev/make-handler root {})]
          (with-redefs [dev/resolve-path (fn [& _] ghost)]
            (let [r (h {:uri "/gone.bin"})]
              (is (= 404 (:status r)))
              (is (not (str/includes? (str (:body r)) (str root)))))))))))

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
  [dir {:keys [fail? follow-ms opts] :or {follow-ms 50}} f]
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
            (dev/start-dev! (merge {:site-dir (str dir) :port 1888 :no-write true} opts)
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

;; ---------------------------------------------------------------------------
;; Item 20: listen-error cosmetics

(deftest a-bad-option-or-a-bracketed-host-is-one-clean-line
  (testing "item 20: `--port abc` dumped babashka.cli's whole spec"
    (doseq [v [#'cli/dev #'cli/serve]]
      (let [e (try (bcli/parse-opts ["--port" "abc"] (:org.babashka/cli (meta v)))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) (str v))
        (is (= 1 (:babashka/exit (ex-data e))) (str v))
        (is (re-matches #"clogem-press: Invalid value for option --port: [^\n]*" (str (ex-message e)))
            (ex-message e))
        (is (nil? (:spec (ex-data e))) "no spec to dump"))))
  (testing "item 20: `--host [::1]` printed `cannot listen on [[::1]]:PORT`"
    (let [e (try (dev/run-server! (fn [_]) {:ip "[::1]" :port 70000}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (str/includes? (str (ex-message e)) "cannot listen on [::1]:70000") (ex-message e)))
    (is (= "http://[::1]:1888/" (dev/url-for {:ip "[::1]" :port 1888} "/")))
    (is (= "http://[::1]:1888/" (dev/url-for {:ip "::1" :port 1888} "/")))))

;; ---------------------------------------------------------------------------
;; Item 10: `stopped` only after a start

(deftest a-failed-start-does-not-say-stopped
  (testing "item 10: every startup failure, a listen error included, ended
            with \"clogem-press: stopped\""
    (with-site url-site
      (fn [dir]
        (let [hook (atom nil)
              busy (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
          (try
            (with-redefs [dev/add-shutdown-hook! (fn [f] (reset! hook f))]
              (let [e (try (quiet #(dev/dev! (assoc (parse #'cli/dev ["--poll" "--port" (str (.getLocalPort busy))])
                                                    :site-dir (str dir))))
                           nil
                           (catch clojure.lang.ExceptionInfo e e))]
                (is (re-find #"already in use" (str (ex-message e))) (ex-message e))
                (is (not (str/includes? (with-out-str (@hook)) "stopped")) "a listen error: no `stopped`")))
            (finally (.close busy))))))))

;; ---------------------------------------------------------------------------
;; Item 11: SIGTERM to the process group during an index

(deftest a-sigterm-to-the-group-during-an-index-reports-nothing
  (testing "item 11: Pagefind got the group's SIGTERM before dev's hook set
            `stopping`, and its exit 143 was reported as an index failure"
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir]
        (let [bin (str (fs/path dir "bin" "pagefind_extended"))]
          (fs/create-dirs (fs/parent bin))
          (spit bin "#!/bin/sh\nexit 143\n")
          (fs/set-posix-file-permissions bin "rwxr-xr-x")
          (config/mark-out-dir! (fs/path dir "dist"))
          (let [cfg      (first (diag/collecting
                                 (config/load-config (str dir) nil {:tools {:pagefind {:path bin}}})))
                stopping (atom false)
                index!   (#'dev/indexer #(deref stopping) (fn [f] (f)))
                out      (with-out-str
                           ;; the hook's stop! runs a moment after the signal
                           (future (Thread/sleep 150) (reset! stopping true))
                           (index! cfg))]
            (is (not (str/includes? out "search index failed")) out))
          (testing "while a SIGTERM from elsewhere, dev not stopping, is still reported"
            (let [cfg   (first (diag/collecting
                                (config/load-config (str dir) nil {:tools {:pagefind {:path bin}}})))
                  index! (#'dev/indexer (constantly false) (fn [f] (f)))
                  out    (with-out-str (index! cfg))]
              (is (str/includes? out "Pagefind was terminated (SIGTERM)") out))))))))

;; ---------------------------------------------------------------------------
;; Item 12: Ctrl-C during the probe

(defn- probe-files
  "Probe files in the site `dir` or the theme's directory (on disk in a
  checkout, and the first watched directory when it sorts first)."
  [dir]
  (filter #(str/starts-with? (str (fs/file-name %)) ".clogem-watch-probe-")
          (mapcat #(when % (fs/list-dir %))
                  (concat [(dev/theme-dir false)]
                          (filter fs/directory? (fs/glob dir "**" {:hidden true}))))))

(deftest stop-during-the-probe-removes-its-file
  (testing "item 12: Ctrl-C while the probe waited left
            .clogem-watch-probe-<nanos> in the first watched directory"
    (with-site url-site
      (fn [dir]
        (let [stop  (promise)
              done  (future
                      (quiet (fn [] (dev/start-dev!
                               {:site-dir (str dir) :port 1888 :no-write true :probe-ms 4000}
                               {:run-server! (fn [& _] (fn [& _] nil))
                                :poll-watch! (fn [& _] nil)
                                :notify! (fn [_]) :notify-error! (fn [_])
                                :on-stop! #(deliver stop %)
                                ;; a pod that registers and never delivers
                                :try-pod-watch! (fn [cfg on-change opts]
                                                  (dev/probe-watch! cfg on-change
                                                                    (assoc opts :watch (fn [& _] :w) :unwatch (fn [_]))))}))))]
          (is (wait-until #(seq (probe-files dir)) 3000) "the probe is waiting")
          ;; what the shutdown hook runs at Ctrl-C; the JVM then exits
          ;; without running the probe's own `finally`
          (quiet @stop)
          (is (empty? (probe-files dir)) "stop! removed it")
          (deref done 6000 nil))))))

;; ---------------------------------------------------------------------------
;; Item 13: Ctrl-C while Chroma runs

(defn- stop-while-chroma-runs
  "Start dev on a site with a code block and a fake Chroma that, after
  `sleep` seconds, exits 130 as the SIGINT of a Ctrl-C makes it; call dev's
  stop! once Chroma is running. [out written?]."
  [sleep]
  (let [result (atom nil)]
    (with-site (assoc url-site :highlight {:provider :chroma})
      (fn [dir]
        (spit (fs/file dir "content" "01.Guide" "01.t.md")
              (str article "\n```clojure\n(defn f [] 1)\n```\n"))
        (let [real    (fake/fake-chroma! (fs/path dir "bin") :exit 130)
              wrapper (str (fs/path dir "bin" "slow-chroma"))]
          (spit wrapper (str "#!/bin/sh\ncase \"$*\" in *--lexer=*) sleep " sleep ";; esac\nexec " real " \"$@\"\n"))
          (fs/set-posix-file-permissions wrapper "rwxr-xr-x")
          (spit (fs/file dir "site.edn") (pr-str (assoc url-site :highlight {:provider :chroma}
                                                        :tools {:chroma {:path wrapper}})))
          (highlight/clear-cache!)
          (let [stop (promise)
                out  (java.io.StringWriter.)
                done (future
                       ;; CI's CLOGEM_CHROMA would win over :tools :chroma :path
                       (binding [*out* out *err* out tools/*env* {}]
                         (dev/start-dev! {:site-dir (str dir) :poll true :no-write true}
                                         {:run-server! (fn [& _] (fn [& _] nil))
                                          :poll-watch! (fn [& _] nil)
                                          :notify! (fn [_]) :notify-error! (fn [_])
                                          :on-stop! #(deliver stop %)})))]
            (is (wait-until #(and (fs/exists? (fs/path dir "bin" "calls.txt"))
                                  (str/includes? (slurp (fs/file dir "bin" "calls.txt")) "--lexer="))
                            5000)
                "Chroma is running")
            ;; what the shutdown hook runs, after the SIGINT reached Chroma
            (Thread/sleep 100)
            (binding [*out* out *err* out] (@stop))
            (deref done 10000 nil)
            (reset! result [(str out) (fs/exists? (fs/path dir "dist" "pages" "t00001" "index.html"))])))))
    @result))

(deftest stop-while-chroma-runs-writes-nothing-and-warns-nothing
  (testing "item 13: Ctrl-C killed Chroma (exit 130), the dev build warned
            that Chroma failed and wrote dist/ with plain code"
    (doseq [sleep ["0.8" "0"]]
      (testing (if (= "0" sleep) "Chroma died before dev's hook ran" "dev's hook ran before Chroma died")
        (let [[out written?] (stop-while-chroma-runs sleep)]
          (is (not written?) "nothing written")
          (is (not (re-find #"(?i)chroma" out)) (str "no Chroma warning: " out))
          (is (not (str/includes? out "build failed")) out))))))

;; ---------------------------------------------------------------------------
;; Item 14: the pod's shutdown noise

(deftest ctrl-c-reaching-the-pod-is-not-reported
  (testing "item 14: Ctrl-C reaches the pod too, and dev printed
            \"fswatcher could not watch — Stream Closed\""
    (with-site url-site
      (fn [dir]
        (let [cfg   (first (diag/collecting (config/load-config (str dir))))
              probe (fn [stopping?]
                      (with-out-str
                        (dev/probe-watch! cfg (fn [_])
                                          {:watch (fn [& _] (throw (java.io.IOException. "Stream closed")))
                                           :unwatch (fn [_]) :sleep (fn [_]) :timeout-ms 2000
                                           :stopping? (constantly stopping?)})))]
          (is (str/includes? (probe false) "fswatcher could not watch — Stream closed") "still said otherwise")
          (is (= "" (probe true)) "nothing while dev stops"))
        (testing "a pod that dies while it loads"
          (let [bin (str (fs/path dir "bin" "dead-pod"))]
            (fs/create-dirs (fs/parent bin))
            (spit bin "#!/bin/sh\nexit 1\n")
            (fs/set-posix-file-permissions bin "rwxr-xr-x")
            (binding [tools/*env* {"CLOGEM_FSWATCHER" bin}]
              (let [cfg (first (diag/collecting (config/load-config (str dir))))]
                (is (str/includes? (with-out-str (dev/load-pod! cfg (constantly false))) "fswatcher pod is unavailable"))
                (is (= "" (with-out-str (dev/load-pod! cfg (constantly true)))))))))))))

(deftest the-real-pod-quitting-at-ctrl-c-is-not-reported
  (testing "item 14: babashka's `ERROR: Pod quit unexpectedly` at Ctrl-C"
    (if-not (System/getenv "CLOGEM_FSWATCHER")
      (println "the-real-pod-quitting-at-ctrl-c-is-not-reported: CLOGEM_FSWATCHER is not set; skipped")
      (with-site url-site
        (fn [dir]
          (let [cfg (first (diag/collecting (config/load-config (str dir))))]
            (doseq [stopping? [false true]]
              (let [err (java.io.StringWriter.)
                    fns (binding [*err* err] (dev/load-pod! cfg (constantly stopping?)))
                    pid (some (fn [^java.lang.ProcessHandle h]
                                (when (str/includes? (str (.orElse (.command (.info h)) "")) "fswatcher") h))
                              (iterator-seq (.iterator (.children (java.lang.ProcessHandle/current)))))]
                (is (some? pid))
                ((:watch fns) (str dir) (fn [_]) {})
                ;; the SIGINT a Ctrl-C sends the whole process group
                (.destroy ^java.lang.ProcessHandle pid)
                (if stopping?
                  (do (Thread/sleep 1500)
                      (is (= "" (str err)) "nothing while dev stops"))
                  (is (wait-until #(str/includes? (str err) "Pod quit unexpectedly") 3000) "said otherwise"))
                (dev/unload-pod! (:pod-id fns))))))))))

;; ---------------------------------------------------------------------------
;; Item 15: a directory the build creates

(deftest a-directory-the-build-creates-rebuilds-nothing
  (testing "item 15: the build's content/@pages/ was one extra rebuild"
    (with-site (assoc-in url-site [:content :write-front-matter] true)
      (fn [dir]
        (let [pages (fs/path dir "content" "@pages")]
          (is (not (fs/exists? pages)))
          (pod-dev dir {:opts {:no-write false}}
                   (fn [{:keys [event! builds regs-of]}]
                     (is (fs/directory? pages) "the first build made it")
                     (is (= 1 @builds))
                     ;; what the content directory's watch reports: the
                     ;; directory; its files were made before any watch of
                     ;; it existed
                     (event! pages :create)
                     (is (wait-until #(= 1 (count (regs-of pages)))) "registered, to watch it")
                     (Thread/sleep 500)
                     (is (= 1 @builds) "and no rebuild")
                     (testing "while a directory the author makes is one"
                       (let [t (fs/path dir "content" "50.Mine")]
                         (fs/create-dirs t)
                         (event! t :create)
                         (is (wait-until #(= 2 @builds))))))))))))

;; ---------------------------------------------------------------------------
;; Item 16: a symlinked site.edn pointed elsewhere

(deftest re-pointing-a-symlinked-site-edn-is-one-rebuild
  (testing "item 16: two rebuilds, the second a rescan of the whole new
            target directory"
    (with-site url-site
      (fn [dir]
        (let [shared (fs/path dir "shared")
              other  (fs/path dir "elsewhere")
              link   (fs/path dir "site.edn")]
          (fs/create-dirs shared)
          (fs/move link (fs/path shared "site.edn"))
          (fs/create-sym-link link (fs/path shared "site.edn"))
          (fs/create-dirs (fs/path other "unrelated" "deep"))
          (spit (fs/file other "site.edn") (pr-str (assoc-in url-site [:site :title] "Other")))
          (spit (fs/file other "notes.txt") "not the site's")
          (spit (fs/file other "unrelated" "deep" "x.md") "nor this")
          (pod-dev dir {}
                   (fn [{:keys [event! builds regs-of out]}]
                     (is (= 1 @builds))
                     (fs/delete link)
                     (fs/create-sym-link link (fs/path other "site.edn"))
                     (event! link :create)
                     (is (wait-until #(= 1 (count (regs-of other)))) "the new target's directory is watched")
                     (Thread/sleep 600)
                     (is (= 2 @builds) (str "one rebuild: " out))
                     (is (not (str/includes? (str out) "notes.txt")) "no rescan of the target's directory")
                     (is (not (str/includes? (str out) "files changed")) (str out)))))))))

;; ---------------------------------------------------------------------------
;; Item 17: --poll and a :content :dir moved to a missing directory

(deftest polling-a-content-dir-moved-to-a-missing-one-is-one-failed-build
  (testing "item 17: the save failed one build, and the watched paths it
            moved made the next pass see every old file as changed — a
            second failed build"
    (with-site url-site
      (fn [dir]
        (let [builds (atom 0)
              out    (java.io.StringWriter.)
              h      (binding [*out* out *err* out]
                       (dev/start-dev! {:site-dir (str dir) :poll true :no-write true :interval 100}
                                       {:run-server! (fn [& _] (fn [& _] nil))
                                        :notify! (fn [_]) :notify-error! (fn [_])
                                        :build (fn [o] (swap! builds inc) (cli/build o))}))]
          (try
            (Thread/sleep 300)
            (spit (fs/file dir "site.edn") (pr-str (assoc url-site :content {:dir "missing" :write-front-matter false})))
            (is (wait-until #(= 2 @builds) 3000) "the save is one build")
            (Thread/sleep 1200)
            (is (= 2 @builds) (str "and only one: " out))
            (testing "and creating the directory later is followed"
              (fs/create-dirs (fs/path dir "missing" "01.Guide"))
              (spit (fs/file dir "missing" "01.Guide" "01.t.md") article)
              (is (wait-until #(= 3 @builds) 5000) "a rebuild")
              (is (wait-until #(= 2 (count (re-seq #"rebuilt in" (str out)))) 3000) (str "a good one: " out)))
            (finally (binding [*out* out *err* out] ((:stop! h))))))))))

;; ---------------------------------------------------------------------------
;; Item 18: rename-saves through temp names with a random "extension"

(deftest a-stylesheet-saved-through-a-random-suffixed-temp-swaps-the-css
  (testing "item 18: rsync's .custom.css.Xy12Zq and write-file-atomic's
            custom.css.<hash> made a full reload"
    (with-root
      (fn [root]
        (let [css    (fs/path root "custom.css")
              sheets #{"overrides/custom.css"}]
          (spit (fs/file css) "a{}")
          (doseq [tmp [".custom.css.Xy12Zq" "custom.css.3141592653" "custom.css.a1b2c3"]]
            (is (= "css" (dev/reload-kind [(str (fs/path root tmp)) (str css)] sheets sheets true)) tmp))
          (testing "but not a file that is still there, or names no stylesheet here"
            (spit (fs/file root "custom.css.bak") "kept")
            (is (= "reload" (dev/reload-kind [(str (fs/path root "custom.css.bak")) (str css)] sheets sheets true)))
            (is (= "reload" (dev/reload-kind [(str (fs/path root "other.css.Xy12Zq")) (str css)] sheets sheets true))
                "other.css is not here")
            (is (= "reload" (dev/reload-kind [(str (fs/path root "page.md.Xy12Zq"))] sheets sheets true))
                "not a stylesheet's")))))))

;; ---------------------------------------------------------------------------
;; Item 19: a base with a space or non-ASCII characters

(deftest every-emitted-url-carries-the-base-percent-encoded
  (testing "item 19: article links were encoded, but theme assets, the icon
            sprite, the home and language links and markdown's /assets/
            links carried the raw base"
    (doseq [base ["/my docs/" "/文档/"]]
      (testing base
        (with-site (assoc url-site :site {:title "S" :url "https://s.example" :base base}
                          :langs {:default :en :priority [:en :ta]
                                  :locales {:en {:label "English" :html-lang "en"}
                                            :ta {:label "தமிழ்" :html-lang "ta"}}}
                          :nav [{:text "Home" :link "/"} {:text "Cats" :link "/categories/"} {:text "File" :link "/assets/demo.txt"}])
          (fn [dir]
            (spit (fs/file dir "content" "01.Guide" "01.t.md")
                  (str article "\n![d](/assets/demo.txt) and [file](/assets/demo.txt)\n"))
            (quiet #(cli/build (assoc (parse #'cli/build []) :site-dir (str dir))))
            (let [enc   (clogem.util/url-encode-path base)
                  html  (for [f (fs/glob (fs/path dir "dist") "**.html")] (slurp (fs/file f)))
                  raw   (re-pattern (str "(?:href|src|action)=\"" (java.util.regex.Pattern/quote base)))
                  all   (apply str html)]
              (is (seq html))
              (is (not (re-find raw all)) (str "a raw base: " (re-find (re-pattern (str ".{40}" (java.util.regex.Pattern/quote base) ".{30}")) all)))
              (doseq [part ["clogem/css/theme.css" "clogem/js/lang.js" "clogem/icons.svg" "assets/demo.txt"]]
                (is (str/includes? all (str enc part)) part))
              (is (str/includes? all (str "href=\"" enc "\"")) "the home link")
              (is (str/includes? all (str "href=\"" enc "ta/\"")) "a language's home")
              (testing "and serve answers what the pages link"
                (let [h (serve-handler (fs/path dir "dist") ["--base" base])]
                  (doseq [u [(str enc "clogem/css/theme.css") (str enc "ta/") (str enc "assets/demo.txt")]]
                    (is (= 200 (:status (h {:uri u :headers {"host" "localhost"}}))) u)))))))))))

;; ---------------------------------------------------------------------------
;; Item 21: Pagefind failure hints

(deftest pagefind-failure-hints-say-what-is-true
  (testing "item 21: the first index's failure said the previous index was
            still in place, and `build --out X`'s named dist/"
    (with-site (assoc url-site :search {:provider :pagefind})
      (fn [dir]
        (let [bad (fake/fake-pagefind! (fs/path dir "bad") :exit 3)
              cfg (fn [out] (first (diag/collecting (config/load-config (str dir) nil {:build {:out out}
                                                                                     :tools {:pagefind {:path bad}}}))))
              out (fs/path dir "dist")
              msg (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
          (config/mark-out-dir! out)
          (is (re-find #"There is no search index yet" (str (msg #(search/run-staged! (cfg "dist")))))
              "the first index: none to keep")
          (fs/create-dirs (fs/path out "pagefind"))
          (is (re-find #"The previous search index is still in place" (str (msg #(search/run-staged! (cfg "dist"))))))
          (testing "build names the directory it wrote"
            (spit (fs/file dir "site.edn") (pr-str (assoc url-site :search {:provider :pagefind}
                                                          :tools {:pagefind {:path bad}})))
            (let [m (msg #(quiet (fn [] (cli/build (assoc (parse #'cli/build ["--out" "public"]) :site-dir (str dir))))))]
              (is (re-find #"public/ \(.*public\) is already written but has no search index" (str m)) m)
              (is (not (str/includes? (str m) "dist/")) m))))))))
