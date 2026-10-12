;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round4-test
  "P4-D.4: the follow-up to Task D — its known issues, fixed mainly by
  simplifying. Each test names the item it pins down; each failed before
  its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.dev :as dev]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms` (default 5000); its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

;; Sites

(def ^:private marker ".clogem-output")

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article (and `site-edn`); calls (f dir)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-r4"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir))
      (finally (fs/delete-tree dir)))))

(defn- with-temp-dir [prefix f]
  (let [d (fs/create-temp-dir {:prefix prefix})]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- quiet [f]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (f)))

(defn- captured
  "Run `f`: [its value or the ex-info it threw, what it printed]."
  [f]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w *err* w]
      (let [v (try (f) (catch clojure.lang.ExceptionInfo e e))]
        [v (str w)]))))

(defn- parse [v args] (bcli/parse-opts args {:spec (:spec (:org.babashka/cli (meta v)))}))

(defn- run-task
  "Task `v` (`#'cli/build` …) over the site `dir` with `args`: [value-or-ex output]."
  [v dir & args]
  (captured #(v (assoc (parse v (vec args)) :site-dir (str dir)))))

(defn- ex-msg [v] (when (instance? Throwable v) (ex-message v)))

(defn- doctor-errors [dir]
  (let [[v] (run-task #'cli/doctor dir)]
    (if (instance? Throwable v) (:clogem/errors (ex-data v)) (:errors v))))

(defn- tree
  "Every file under `dir` → its content."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true})
              :when (fs/regular-file? p)]
          [(str (fs/relativize dir p)) (slurp (fs/file p))])))

;; ---------------------------------------------------------------------------
;; Item 1: a large file is a plain `File` body again

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-r4root"})]
    (try (f root) (finally (fs/delete-tree root)))))

(defn- sparse!
  "A sparse file of `n` bytes at `p`, its last byte 7."
  [p n]
  (with-open [f (java.io.RandomAccessFile. (fs/file p) "rw")]
    (.setLength f n)
    (.seek f (dec n))
    (.write f 7)))

(defn- with-port
  "Run the static handler over `root` on a real http-kit server; (f port)."
  [root f]
  (let [srv (dev/run-server! (dev/make-handler root {}) {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try (f (org.httpkit.server/server-port srv))
         (finally (org.httpkit.server/server-stop! srv)))))

(defn- read-head
  "Read a response's status line and headers from `in`:
  {:status :headers} (header names lower-cased), or nil at end of stream."
  [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) nil
          :else (do (.append sb (char b))
                    (if (str/ends-with? (str sb) "\r\n\r\n")
                      (let [[status-line & lines] (str/split-lines (str/trim (str sb)))]
                        {:status  (parse-long (second (str/split status-line #" ")))
                         :headers (into {} (for [l lines
                                                 :let [[k v] (str/split l #":\s*" 2)]]
                                             [(str/lower-case k) (str/trim (str v))]))})
                      (recur))))))))

(defn- skip!
  "Read and count `n` bytes from `in`, or to its end: [count last-byte]."
  [^java.io.InputStream in n]
  (let [buf (byte-array 65536)]
    (loop [got 0 last -1]
      (let [want (if n (min (alength buf) (- n got)) (alength buf))]
        (if (and n (zero? want))
          [got last]
          (let [r (.read in buf 0 (int want))]
            (if (neg? r)
              [got last]
              (recur (+ got r) (if (pos? r) (aget buf (dec r)) last)))))))))

(defn- raw-get
  "One request on its own connection, written as `request-line` (`GET /x
  HTTP/1.0`), read to the end of the connection: {:status :headers
  :body-bytes :last-byte}."
  [port request-line]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 30000)
    (let [out (.getOutputStream s) in (java.io.BufferedInputStream. (.getInputStream s))]
      (.write out (.getBytes (str request-line "\r\nHost: localhost\r\nConnection: close\r\n\r\n") "UTF-8"))
      (.flush out)
      (let [head (read-head in)
            [n last] (skip! in nil)]
        (assoc head :body-bytes n :last-byte last)))))

(defn- fds-on
  "This process's open file descriptors on `p` (Linux: /proc), or nil."
  [p]
  (when (fs/exists? "/proc/self/fd")
    (let [s (str (fs/canonicalize p))]
      (count (filter #(= s (try (str (fs/read-link %)) (catch Exception _ nil)))
                     (fs/list-dir "/proc/self/fd"))))))

(deftest a-large-file-has-its-body-and-content-length-in-http-1-0-and-1-1
  (testing "item 1: D.3's chunked stream gave HTTP/1.0 a 200 with
            content-length 0 and no body (babashka 1.13.219), and a large
            file lost its Content-Length"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (+ 10 (* 2 1024 1024)))
        (with-port root
          (fn [port]
            (doseq [[uri n last] [["/small.txt" 5 (int \l)]
                                  ["/big.bin" (+ 10 (* 2 1024 1024)) 7]]
                    version ["HTTP/1.0" "HTTP/1.1"]]
              (testing (str uri " " version)
                (let [{:keys [status headers body-bytes last-byte]} (raw-get port (str "GET " uri " " version))]
                  (is (= 200 status))
                  (is (= (str n) (get headers "content-length")) "its Content-Length")
                  (is (nil? (get headers "transfer-encoding")) "not chunked")
                  (is (= [n last] [body-bytes last-byte]) "the whole body"))))))))))

(deftest a-file-of-2-gib-or-more-is-refused-cleanly
  (testing "item 1: http-kit sends a File body as one buffer, so a file of 2
            GiB or more cannot be sent (0.2.0: a stack trace and a 500); it
            is a clean 500 naming no path, and the server goes on"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "huge.bin") (+ 1024 (* 2 1024 1024 1024)))
        (let [err (java.io.StringWriter.)]
          (binding [*err* err]
            (with-port root
              (fn [port]
                (let [{:keys [status headers body-bytes]} (raw-get port "GET /huge.bin HTTP/1.1")]
                  (is (= 500 status))
                  (is (< body-bytes 1000))
                  (is (str/starts-with? (get headers "content-type") "text/plain")))
                (is (= 5 (:body-bytes (raw-get port "GET /small.txt HTTP/1.1"))) "and serves on"))))
          (is (not (str/includes? (str err) "Exception")) (str err)))
        (let [r ((dev/make-handler root {}) {:uri "/huge.bin"})]
          (is (= 500 (:status r)))
          (is (not (str/includes? (str (:body r)) (str root))) "no path"))))))

(deftest a-dropped-download-on-a-keep-alive-connection-leaves-nothing-behind
  (testing "item 1: D.3's stream thread, and its open file, waited for ever
            for a client that went away on a reused keep-alive connection"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (* 64 1024 1024))
        (with-port root
          (fn [port]
            ;; warm the server's own threads up first
            (raw-get port "GET /small.txt HTTP/1.1")
            (let [threads #(set (remove (fn [n] (str/starts-with? n "ForkJoinPool"))   ; shared pools, started lazily
                                        (map (fn [^Thread t] (.getName t)) (keys (Thread/getAllStackTraces)))))
                  before  (threads)]
              (dotimes [_ 3]
                (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
                  (.setSoTimeout s 10000)
                  (let [out (.getOutputStream s) in (java.io.BufferedInputStream. (.getInputStream s))]
                    (.write out (.getBytes "GET /small.txt HTTP/1.1\r\nHost: localhost\r\n\r\n" "UTF-8"))
                    (.flush out)
                    (let [{:keys [headers]} (read-head in)]
                      (skip! in (parse-long (get headers "content-length"))))
                    ;; the same connection, reused: a large file, dropped
                    (.write out (.getBytes "GET /big.bin HTTP/1.1\r\nHost: localhost\r\n\r\n" "UTF-8"))
                    (.flush out)
                    (read-head in)
                    (skip! in 1000))))
              (is (wait-until #(empty? (remove before (threads))) 5000)
                  (str "no thread left behind: " (vec (remove before (threads)))))
              (when-let [n (fds-on (fs/path root "big.bin"))]
                (is (wait-until #(zero? (fds-on (fs/path root "big.bin"))) 5000)
                    (str "no descriptor left on the file: " n))))))))))

(deftest head-sends-no-body
  (testing "item 1: 0.2.0 sent the whole file to HEAD (the http-kit of
            babashka 1.13.219 does not drop it), which corrupts the next
            response on a keep-alive connection"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (+ 10 (* 2 1024 1024)))
        (with-port root
          (fn [port]
            (doseq [uri ["/small.txt" "/big.bin"]]
              (testing uri
                (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
                  (let [out (.getOutputStream s) in (java.io.BufferedInputStream. (.getInputStream s))
                        send! #(do (.write out (.getBytes ^String % "UTF-8")) (.flush out))]
                    (.setSoTimeout s 10000)
                    (send! (str "HEAD " uri " HTTP/1.1\r\nHost: localhost\r\n\r\n"))
                    (is (= 200 (:status (read-head in))) "HEAD answered")
                    ;; one request at a time: babashka 1.13.219's http-kit
                    ;; answers pipelined requests out of order
                    (.setSoTimeout s 500)
                    (is (= ::none (try (.read in) (catch java.net.SocketTimeoutException _ ::none)))
                        "no body follows the headers")
                    (.setSoTimeout s 10000)
                    (send! "GET /small.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    (let [next-head (read-head in)]
                      (is (= 200 (:status next-head)) "the connection serves on")
                      (is (= "5" (get-in next-head [:headers "content-length"]))))
                    (is (= [5 (int \l)] (skip! in nil)) "the next response is the small file, whole")))))))))))

;; ---------------------------------------------------------------------------
;; Item 3: output directories — containment kept, adoption and pre-seed
;; refusal gone

(deftest a-symlinked-default-dist-is-built-into
  (testing "item 3: `dist -> /var/www/site`, which DESIGN supports, was
            refused by build, dev, doctor and clean as \"outside the site
            directory\": the default merged in from `defaults` was taken for
            a site.edn value"
    (with-site url-site
      (fn [dir]
        (with-temp-dir "clogem-r4www"
          (fn [www]
            (fs/create-sym-link (fs/path dir "dist") www)
            (is (empty? (doctor-errors dir)) "doctor: no error")
            (let [[v out] (run-task #'cli/build dir)]
              (is (not (instance? Throwable v)) (str out (ex-msg v))))
            (is (fs/exists? (fs/path www "pages" "t00001" "index.html")) "built through the link")
            (is (fs/exists? (fs/path www marker)))
            (testing "dev"
              (let [h (quiet #(dev/start-dev! {:site-dir (str dir) :poll true :no-write true :no-search true}
                                              {:run-server! (fn [& _] (fn [& _] nil))
                                               :poll-watch! (fn [& _] nil)
                                               :notify! (fn [_]) :notify-error! (fn [_])}))]
                (try (is (:ever-ok? @(:state h)) "dev's first build succeeds")
                     (finally (quiet (:stop! h))))))
            (testing "--out through a link outside the site, too"
              (fs/create-sym-link (fs/path dir "out-link") www)
              (let [[v out] (run-task #'cli/build dir "--out" "out-link")]
                (is (not (instance? Throwable v)) (str out (ex-msg v)))))
            (testing "but a site.edn value is still kept inside the site"
              (spit (fs/file dir "site.edn") (pr-str (assoc url-site :build {:out "dist"})))
              (let [[v] (run-task #'cli/build dir)]
                (is (re-find #"outside the site directory" (str (ex-msg v)))))
              (spit (fs/file dir "site.edn") (pr-str url-site)))
            (testing "clean removes the link, and leaves its target, as 0.2.0 did"
              (let [[v out] (run-task #'cli/clean dir)]
                (is (not (instance? Throwable v)) (str out (ex-msg v))))
              (is (not (fs/exists? (fs/path dir "dist") {:nofollow-links true})))
              (is (fs/exists? (fs/path www "index.html"))))))))))

(deftest a-dangling-dist-link-is-a-clear-error
  (testing "item 3: a dist symlink to nothing was a raw FileAlreadyExistsException"
    (with-site url-site
      (fn [dir]
        (fs/create-sym-link (fs/path dir "dist") (fs/path dir "nowhere"))
        (doseq [v [#'cli/build #'cli/clean]]
          (let [[r out] (run-task v dir)]
            (is (re-find #"is a symbolic link to .*nowhere, which does not exist" (str (ex-msg r) out))
                (str v ": " (ex-msg r) out))
            (is (not (str/includes? (str (ex-msg r) out) "FileAlreadyExists")))))
        (is (some #(re-find #"which does not exist" (:message %)) (doctor-errors dir)))
        (is (not (fs/exists? (fs/path dir "nowhere"))) "nothing created")))))

(deftest a-pre-seeded-dist-builds-and-is-swept-from-its-second-build
  (testing "item 3: a dist/ holding only CNAME, .nojekyll, .gitkeep or a
            deploy worktree's .git file failed build and doctor; 0.2.0
            built into it"
    (with-site url-site
      (fn [dir]
        (let [dist (fs/path dir "dist")]
          (fs/create-dirs dist)
          (spit (fs/file dist "CNAME") "docs.example\n")
          (spit (fs/file dist ".nojekyll") "")
          (spit (fs/file dist ".gitkeep") "")
          (spit (fs/file dist ".git") "gitdir: ../.git/worktrees/dist\n")
          (spit (fs/file dist "notes.html") "<p>mine</p>")
          (let [seed (tree dist)]
            (is (empty? (doctor-errors dir)) "doctor: no error")
            (let [[v out] (run-task #'cli/build dir)]
              (is (not (instance? Throwable v)) (str out (ex-msg v))))
            (is (= seed (select-keys (tree dist) (keys seed))) "the first build sweeps nothing")
            (is (fs/exists? (fs/path dist marker)))
            (testing "the second sweeps the stale .html, and only that"
              (spit (fs/file dir "content" "01.Guide" "02.u.md")
                    (str/replace article "t00001" "u00002"))
              (run-task #'cli/build dir)
              (is (fs/exists? (fs/path dist "pages" "u00002" "index.html")))
              (fs/delete (fs/path dir "content" "01.Guide" "02.u.md"))
              (let [[v out] (run-task #'cli/build dir)]
                (is (not (instance? Throwable v)) (str out (ex-msg v))))
              (is (not (fs/exists? (fs/path dist "pages" "u00002" "index.html"))) "a deleted article's page")
              (is (= (dissoc seed "notes.html") (select-keys (tree dist) (keys (dissoc seed "notes.html"))))
                  "CNAME, .nojekyll, .gitkeep and .git stay"))))))))

(deftest a-dist-an-earlier-version-built-is-marked-then-swept
  (testing "item 3: a 0.2.0 dist/ (no marker) is simply marked by its first
            build and swept from the second; no adoption"
    (with-site url-site
      (fn [dir]
        (let [dist (fs/path dir "dist")]
          (fs/create-dirs (fs/path dist "clogem" "css"))
          (spit (fs/file dist "clogem" "css" "dropped.css") "old theme file")
          (spit (fs/file dist "index.html") "<p>old home</p>")
          (fs/create-dirs (fs/path dist "pages" "gone00"))
          (spit (fs/file dist "pages" "gone00" "index.html") "<p>old page</p>")
          (run-task #'cli/build dir)
          (is (fs/exists? (fs/path dist "pages" "gone00" "index.html")) "first build: nothing swept")
          (is (fs/exists? (fs/path dist "clogem" "css" "dropped.css")))
          (is (fs/exists? (fs/path dist marker)) "but marked")
          (let [[v out] (run-task #'cli/build dir)]
            (is (not (instance? Throwable v)) (str out (ex-msg v)))
            (is (re-find #"removed 1 stale page" out) out))
          (is (not (fs/exists? (fs/path dist "pages" "gone00" "index.html"))) "second build: swept")
          (is (not (fs/exists? (fs/path dist "clogem" "css" "dropped.css")))))))))

(deftest clean-deletes-only-a-marked-directory-without-a-git
  (testing "item 3: D.3 adopted any directory with a clogem/ and an
            index.html, so `bb clean --out` on a git deploy clone deleted
            its .git"
    (with-site url-site
      (fn [dir]
        (let [dist (fs/path dir "dist")
              clean! #(apply run-task #'cli/clean dir %&)]
          (testing "nothing there"
            (let [[v] (clean!)] (is (not (instance? Throwable v)))))
          (testing "a marked one"
            (run-task #'cli/build dir)
            (let [[v] (clean!)] (is (not (instance? Throwable v))))
            (is (not (fs/exists? dist))))
          (testing "an unmarked one, the deploy clone D.3 adopted"
            (fs/create-dirs (fs/path dist "clogem"))
            (spit (fs/file dist "index.html") "<p>home</p>")
            (fs/create-dirs (fs/path dist ".git"))
            (spit (fs/file dist ".git" "HEAD") "ref: refs/heads/gh-pages\n")
            (let [before (tree dist)
                  [v]    (clean!)]
              (is (= 1 (:babashka/exit (ex-data v))))
              (is (re-find #"has no \.clogem-output" (str (ex-msg v))))
              (is (re-find #"hint: .*rm -r" (str (ex-msg v))) "says how to clean it")
              (is (= before (tree dist)) "untouched")))
          (testing "a marked one holding a .git — a directory, or a worktree's file"
            (doseq [git-file? [false true]]
              (fs/delete-tree dist)
              (run-task #'cli/build dir)
              (if git-file?
                (spit (fs/file dist ".git") "gitdir: ../.git/worktrees/dist\n")
                (do (fs/create-dirs (fs/path dist ".git"))
                    (spit (fs/file dist ".git" "HEAD") "ref: refs/heads/gh-pages\n")))
              (let [before (tree dist)
                    [v]    (clean!)]
                (is (re-find #"holds a \.git" (str (ex-msg v))))
                (is (= before (tree dist)) "untouched")))))))))

(deftest doctor-reports-only-a-refused-output-directory
  (testing "item 3: doctor reported an unmarked, non-empty dist/ as an
            error, and after a refusal a second, spurious one for the
            default it repaired to"
    (with-site (assoc url-site :build {:out "content"})
      (fn [dir]
        (fs/create-dirs (fs/path dir "dist"))
        (spit (fs/file dir "dist" "CNAME") "x\n")
        (let [errs (doctor-errors dir)]
          (is (= 1 (count errs)) (pr-str (map :message errs)))
          (is (re-find #"is the content directory" (:message (first errs)))))
        (spit (fs/file dir "site.edn") (pr-str url-site))
        (is (empty? (doctor-errors dir)))))))

;; ---------------------------------------------------------------------------
;; Item 4: a :nav link gets the base, percent-encoded, and is kept as written

(deftest nav-links-keep-their-fragment-query-and-encoding-under-any-base
  (testing "item 4: D.3 percent-encoded the base and the link together, so
            `#x`, `?q=1` and `%20` became part of the path"
    (doseq [base ["/" "/my docs/" "/文档/"]]
      (testing base
        (with-site (assoc url-site
                          :site {:title "S" :url "https://s.example" :base base}
                          :nav [{:text "Frag"  :link "/assets/demo.txt#x"}
                                {:text "Query" :link "/assets/demo.txt?q=1"}
                                {:text "Enc"   :link "/my%20file.pdf"}
                                {:text "CJK"   :link "/文件/说明.pdf"}
                                {:text "CJKdir" :link "/分类/"}
                                {:text "Gone"  :link "/pages/zzzzzz/#sec"}
                                {:text "Abs"   :link "https://example.com/a%20b?x=1#y"}])
          (fn [dir]
            (fs/create-dirs (fs/path dir "assets"))
            (spit (fs/file dir "assets" "demo.txt") "demo")
            (run-task #'cli/build dir "--no-write")
            (let [enc  (clogem.util/url-encode-path base)
                  home (slurp (fs/file dir "dist" "index.html"))]
              (doseq [[label want] [["Frag"   (str enc "assets/demo.txt#x")]
                                    ["Query"  (str enc "assets/demo.txt?q=1")]
                                    ["Enc"    (str enc "my%20file.pdf")]
                                    ["CJK"    (str enc "文件/说明.pdf")]
                                    ["CJKdir" (str enc "%E5%88%86%E7%B1%BB/")]
                                    ["Gone"   (str enc "pages/zzzzzz/#sec")]
                                    ["Abs"    "https://example.com/a%20b?x=1#y"]]]
                (is (str/includes? home (str "href=\"" want "\"")) (str label " → " want))))))))))

(deftest a-redirect-stub-and-the-dev-404-page-encode-the-base
  (testing "item 4: D.3 missed these two: the stub's refresh URL and link,
            and the 404 page's link to the base, carried `/my docs/` raw"
    (with-site (assoc url-site
                      :site {:title "S" :url "https://s.example" :base "/my docs/"}
                      :i18n {:prefix-default? true})
      (fn [dir]
        (run-task #'cli/build dir "--no-write")
        (let [stub (slurp (fs/file dir "dist" "pages" "t00001" "index.html"))]
          (is (str/includes? stub "url=/my%20docs/en/pages/t00001/") stub)
          (is (str/includes? stub "href=\"/my%20docs/en/pages/t00001/\"") stub)
          (is (not (re-find #"(?:url=|href=\")/my docs" stub)) "never raw in a URL"))
        (let [r ((dev/make-handler (fs/path dir "dist") {:base "/my docs/"}) {:uri "/elsewhere"})]
          (is (= 404 (:status r)))
          (is (str/includes? (:body r) "href=\"/my%20docs/\"") (:body r)))))))
