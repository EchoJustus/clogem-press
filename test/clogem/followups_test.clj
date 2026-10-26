;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.followups-test
  "Phase 4's follow-ups before 0.3.0: Task D's known issues and smaller
  items, and what the E1a and E2 checks left. Each test names the item it
  pins down; each failed before its fix."
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

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article (and `site-edn`); calls (f dir)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-fu"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir))
      (finally (fs/delete-tree dir)))))

(defn- captured
  "Run `f`: [its value or the ex-info it threw, what it printed]."
  [f]
  (let [w (java.io.StringWriter.)]
    (binding [*out* w *err* w]
      (let [v (try (f) (catch clojure.lang.ExceptionInfo e e))]
        [v (str w)]))))

(defn- parse
  "`args` parsed as `bb <task>` parses them: with the task's spec and error-fn."
  [v args]
  (bcli/parse-opts args (:org.babashka/cli (meta v))))

(defn- run-task
  "Task `v` (`#'cli/build` …) over the site `dir` with `args`: [value-or-ex output]."
  [v dir & args]
  (captured #(v (assoc (parse v (vec args)) :site-dir (str dir)))))

(defn- ex-msg [v] (when (instance? Throwable v) (ex-message v)))

;; The static server

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-furoot"})]
    (try (f root) (finally (fs/delete-tree root)))))

(defn- sparse!
  "A sparse file of `n` bytes at `p`, its last byte 7."
  [p n]
  (with-open [f (java.io.RandomAccessFile. (fs/file p) "rw")]
    (.setLength f n)
    (.seek f (dec n))
    (.write f 7)))

(defn- with-port
  "Run `handler` on a real http-kit server; (f port)."
  [handler f]
  (let [srv (dev/run-server! handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (try (f (org.httpkit.server/server-port srv))
         (finally (org.httpkit.server/server-stop! srv)))))

(defn- read-head
  "A response's status line and headers from `in`: {:status :headers}
  (header names lower-cased), or nil at end of stream."
  [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (if (neg? b)
          nil
          (do (.append sb (char b))
              (if (str/ends-with? (str sb) "\r\n\r\n")
                (let [[status-line & lines] (str/split-lines (str/trim (str sb)))]
                  {:status  (parse-long (second (str/split status-line #" ")))
                   :headers (into {} (for [l lines
                                           :let [[k v] (str/split l #":\s*" 2)]]
                                       [(str/lower-case k) (str/trim (str v))]))})
                (recur))))))))

(defn- read-rest
  "Every byte left on `in`, to its end: [count, the first 4096 as text]."
  [^java.io.InputStream in]
  (let [buf  (byte-array 65536)
        head (java.io.ByteArrayOutputStream.)]
    (loop [n 0]
      (let [r (.read in buf)]
        (if (neg? r)
          [n (String. (.toByteArray head) "UTF-8")]
          (do (when (< (.size head) 4096) (.write head buf 0 (min r (- 4096 (.size head)))))
              (recur (+ n r))))))))

(defn- raw
  "One request on its own connection — `request-line` (`GET /x HTTP/1.1`)
  and a `Host` (default localhost) — read to the end of the connection:
  {:status :headers :body-bytes :body-text}."
  [port request-line & [host]]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 30000)
    (let [out (.getOutputStream s) in (java.io.BufferedInputStream. (.getInputStream s))]
      (.write out (.getBytes (str request-line "\r\nHost: " (or host "localhost") "\r\nConnection: close\r\n\r\n") "UTF-8"))
      (.flush out)
      (let [head (read-head in)
            [n text] (read-rest in)]
        (assoc head :body-bytes n :body-text text)))))

;; ---------------------------------------------------------------------------
;; Item 1: a large file deleted mid-request

(deftest a-large-file-deleted-mid-request-is-sent-whole-never-its-path
  (testing "item 1: a file of 1 MB or more was a `File` body, which http-kit
            opens only after the handler returns: deleted in between, it
            was http-kit's 500, whose body is the file's absolute path"
    (with-root
      (fn [root]
        (doseq [[file n] [["big.bin" (+ 10 (* 2 1024 1024))]
                          ;; http-kit maps a file of 20 MB or more
                          ["huge.bin" (+ 10 (* 24 1024 1024))]]]
          (testing file
            (let [p (fs/path root file)
                  h (dev/make-handler root {})]
              (sparse! p n)
              ;; the rebuild's delete lands after the handler answered,
              ;; before http-kit has read a byte
              (with-port (fn [req] (let [r (h req)] (fs/delete-if-exists p) r))
                (fn [port]
                  (let [{:keys [status body-bytes body-text]} (raw port (str "GET /" file " HTTP/1.1"))]
                    (is (not (str/includes? body-text (str root))) "never the path")
                    (is (= 200 status) "the file as it was when the request came")
                    (is (= n body-bytes) "whole")))))))))))

(deftest a-file-gone-before-it-is-opened-is-a-plain-404
  (testing "item 1: a file resolved, then gone before it is opened, big or
            small, is a 404 that names no path"
    (with-root
      (fn [root]
        (doseq [[file n] [["small.txt" 5] ["big.bin" (+ 10 (* 2 1024 1024))]]]
          (let [p (fs/path root file)]
            (sparse! p n)
            (let [h (dev/make-handler root {})
                  orig dev/resolve-path]
              (with-redefs [dev/resolve-path (fn [& args] (let [f (apply orig args)] (fs/delete-if-exists p) f))]
                (let [r (h {:uri (str "/" file) :request-method :get})]
                  (is (= 404 (:status r)) file)
                  (is (not (str/includes? (str (:body r)) (str root)))))))))))))

;; ---------------------------------------------------------------------------
;; Item 2: HEAD

(defn- head-drops-body?
  "Does this babashka's http-kit leave the body out of a HEAD response
  itself? http-kit 2.9.0-beta4, in babashka 1.13.220 and later, does;
  1.13.219's sends it."
  []
  (let [[a b c] (map #(or (parse-long %) 0)
                     (take 3 (str/split (str (System/getProperty "babashka.version")) #"[.-]")))]
    (not (neg? (compare [a b c] [1 13 220])))))

(deftest head-reports-the-length-and-sends-no-body
  (testing "item 2: HEAD said `Content-Length: 0` for every file, and a HEAD
            of a 404 or a 500 still sent its body (babashka 1.13.219)"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (+ 10 (* 2 1024 1024)))
        (spit (fs/file root "page.html") "<p>a page</p></body>")
        (with-port (dev/make-handler root {})
          (fn [port]
            (doseq [[uri n] [["/small.txt" 5] ["/big.bin" (+ 10 (* 2 1024 1024))]]]
              (testing uri
                (let [get  (raw port (str "GET " uri " HTTP/1.1"))
                      head (raw port (str "HEAD " uri " HTTP/1.1"))]
                  (is (= 200 (:status head)))
                  (is (= 0 (:body-bytes head)) "no body")
                  (is (= (get-in get [:headers "content-type"]) (get-in head [:headers "content-type"])))
                  (if (head-drops-body?)
                    (is (= (str n) (get-in head [:headers "content-length"])) "GET's length")
                    ;; http-kit 2.8 counts the body it sends: without one it
                    ;; can only say 0
                    (is (= "0" (get-in head [:headers "content-length"])))))))
            (testing "a 404, with no 404.html"
              (let [head (raw port "HEAD /nothing HTTP/1.1")]
                (is (= 404 (:status head)))
                (is (= 0 (:body-bytes head)) "no body")))
            (testing "the site's 404.html"
              (spit (fs/file root "404.html") "<p>lost</p>")
              (let [head (raw port "HEAD /nothing HTTP/1.1")]
                (is (= 404 (:status head)))
                (is (= 0 (:body-bytes head)) "no body")
                (when (head-drops-body?)
                  (is (= "11" (get-in head [:headers "content-length"]))))))
            (testing "a 500"
              (with-redefs [dev/resolve-path (fn [& _] (throw (Error. "boom")))]
                (let [head (raw port "HEAD /small.txt HTTP/1.1")]
                  (is (= 500 (:status head)))
                  (is (= 0 (:body-bytes head)) "no body"))))
            (testing "a 400"
              (let [head (raw port "HEAD /%zz HTTP/1.1")]
                (is (= 400 (:status head)))
                (is (= 0 (:body-bytes head)) "no body")))))
        (testing "the dev server's 403 for another Host, and its reload-injected page"
          (with-port (dev/make-handler root {:inject-reload? true :state (atom {:ever-ok? true})
                                             :check-host? true})
            (fn [port]
              (let [head (raw port "HEAD /small.txt HTTP/1.1" "evil.example")]
                (is (= 403 (:status head)))
                (is (= 0 (:body-bytes head)) "no body"))
              (let [get  (raw port "GET /page.html HTTP/1.1")
                    head (raw port "HEAD /page.html HTTP/1.1")]
                (is (= 200 (:status head)))
                (is (= 0 (:body-bytes head)) "no body")
                (when (head-drops-body?)
                  (is (= (get-in get [:headers "content-length"]) (get-in head [:headers "content-length"]))
                      "the length of the page as GET sends it, script included"))))))))))

(deftest head-reads-no-file
  (testing "follow-ups.1 (item 1): a HEAD of a file under 20 MB read it whole
            (`read-all`), to drop what it read — about 30 times slower than
            0.2.0 for a 19 MB file"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (sparse! (fs/path root "big.bin") (+ 10 (* 2 1024 1024)))
        (sparse! (fs/path root "huge.bin") (+ 10 (* 24 1024 1024)))
        (spit (fs/file root "page.html") "<p>a page</p></body>")
        (let [reads (atom [])
              read-all @#'dev/read-all]
          (with-redefs-fn {#'dev/read-all (fn [f] (swap! reads conj (str f)) (read-all f))}
            (fn []
              (let [h (dev/make-handler root {})]
                (doseq [[uri n] [["/small.txt" 5] ["/big.bin" (+ 10 (* 2 1024 1024))]
                                 ["/huge.bin" (+ 10 (* 24 1024 1024))] ["/page.html" 20]]]
                  (testing uri
                    (let [{:keys [status body]} (h {:uri uri :request-method :head})]
                      (is (= 200 status))
                      (if dev/http-kit-drops-head-bodies?
                        ;; a lazy mapping: http-kit reads its length, and
                        ;; never its bytes
                        (do (when-not (and (fs/windows?) (< n dev/whole-read-limit))
                              (is (instance? java.nio.MappedByteBuffer body) (str (type body))))
                            (is (= n (when (instance? java.nio.ByteBuffer body) (.remaining ^java.nio.ByteBuffer body)))
                                "GET's length"))
                        (is (nil? body) "no body to drop")))))
                ;; on Windows a file under 20 MB is read: a mapping would
                ;; hold it against the next rebuild's replace
                (is (= (if (and (fs/windows?) dev/http-kit-drops-head-bodies?)
                         (map #(str (fs/path root %)) ["small.txt" "big.bin" "page.html"])
                         [])
                       @reads)
                    "nothing read")
                (testing "GET still reads what it sends"
                  (is (bytes? (:body (h {:uri "/small.txt" :request-method :get}))))
                  (is (= (if (and (fs/windows?) dev/http-kit-drops-head-bodies?) 4 1) (count @reads))))))))))))

(deftest a-whole-read-holds-the-file-once
  (testing "follow-ups.1: `read-all` copied its buffer, so a read held the
            file twice; in a heap of 150 MB, a 100 MB file could not be read"
    (with-root
      (fn [root]
        (let [n  (* 100 1024 1024)
              p  (fs/path root "big.bin")
              bb (or (-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)) "bb")
              _  (sparse! p n)
              pr (-> (ProcessBuilder. [bb "-Xmx150m" "-cp" (str (fs/absolutize "src"))
                                       "-e" (str "(require 'clogem.dev) (println (count (#'clogem.dev/read-all "
                                                 (pr-str (str p)) ")))")])
                     (.directory (fs/file root))
                     (.redirectErrorStream true)
                     (.start))
              out (slurp (.getInputStream pr))]
          (is (zero? (.waitFor pr)) out)
          (is (= (str n) (str/trim out))))))))

;; ---------------------------------------------------------------------------
;; Item 4: the fswatcher pod dies while `bb dev` runs

(defn- pod-processes
  "The fswatcher pod processes below this one, as ProcessHandles."
  []
  (filter (fn [^java.lang.ProcessHandle h]
            (str/includes? (str (.orElse (.command (.info h)) "")) "fswatcher"))
          (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))))

(deftest a-pod-that-dies-falls-back-to-polling-with-one-line
  (testing "item 4: with the pod killed, dev stopped watching — no
            fallback to polling — and babashka's pod client printed
            `ERROR: Pod quit unexpectedly` once per watch"
    (if-not (System/getenv "CLOGEM_FSWATCHER")
      (println "a-pod-that-dies-falls-back-to-polling-with-one-line: CLOGEM_FSWATCHER is not set; skipped")
      (with-site url-site
        (fn [dir]
          (doseq [d ["assets" "i18n" "overrides"]] (fs/create-dirs (fs/path dir d)))
          (let [out    (java.io.StringWriter.)
                before (set (map #(.pid ^java.lang.ProcessHandle %) (pod-processes)))
                h      (binding [*out* out *err* out]
                         (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true :interval 100}
                                         {:run-server! (fn [& _] (fn [& _] nil))
                                          :follow-ms 100
                                          :notify! (fn [_]) :notify-error! (fn [_])}))
                builds #(count (re-seq #"rebuilt in" (str out)))]
            (try
              (is (:pod? h) (str "the pod watches: " out))
              (let [pods (remove #(before (.pid ^java.lang.ProcessHandle %)) (pod-processes))]
                (is (= 1 (count pods)) "this dev's pod")
                (run! #(.destroyForcibly ^java.lang.ProcessHandle %) pods))
              (is (wait-until #(re-find #"fswatcher pod quit" (str out)) 5000) (str "one line says so: " out))
              (is (re-find #"fswatcher pod quit; dev polls every 100 ms for the rest of this session" (str out)))
              (is (wait-until #(re-find #"(?s)fswatcher pod quit.*rebuilt in" (str out)) 5000)
                  (str "one rebuild once polling starts: " out))
              (let [b0 (builds)]
                (spit (fs/file dir "content" "01.Guide" "01.t.md") (str article "\nmore\n"))
                (is (wait-until #(> (builds) b0) 5000) (str "polling rebuilds: " out)))
              ;; the pod client's lines wait for the gate, which waits up
              ;; to `dev/signal-grace-ms` for dev's stop
              (Thread/sleep (+ 1500 dev/signal-grace-ms))
              (is (= 1 (count (re-seq #"fswatcher pod quit" (str out)))) (str "said once: " out))
              (is (not (str/includes? (str out) "Pod quit unexpectedly")) (str "nothing else about it: " out))
              (finally (binding [*out* out *err* out] ((:stop! h)))))))))))

(deftest a-pod-that-dies-at-ctrl-c-is-no-fallback
  (testing "follow-ups.1 (item 4): Ctrl-C signals the pod with dev, and the
            pod can die before dev's shutdown hook sets `stopping`; the
            follower read `stopping` once, so it could say `the fswatcher
            pod quit` and start polling as dev stopped. It waits
            `signal-grace-ms` for the stop, as the pod's stderr does"
    (with-site url-site
      (fn [dir]
        (doseq [d ["assets" "i18n" "overrides"]] (fs/create-dirs (fs/path dir d)))
        (let [out    (java.io.StringWriter.)
              alive? (atom true)
              pod (fn [_cfg _on-change {:keys [specs on-ready]}]
                    (on-ready (fn [spec] {:path (:path spec)})
                              {:unwatch! (fn [_]) :watchers (zipmap (map :path specs) (map :path specs))})
                    {:pod-id nil :alive? (fn [] @alive?)})
              h (binding [*out* out *err* out]
                  (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true :interval 100}
                                  {:run-server! (fn [& _] (fn [& _] nil))
                                   :try-pod-watch! pod
                                   :poll-watch! dev/poll-watch!
                                   :follow-ms 20
                                   :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (str/includes? (str out) "watching via fswatcher") (str out))
            (reset! alive? false)
            ;; the follower has seen it several times over before the stop
            (Thread/sleep 300)
            (binding [*out* out *err* out] ((:stop! h)))
            (Thread/sleep (+ 300 dev/signal-grace-ms))
            (is (not (str/includes? (str out) "fswatcher pod quit")) (str out))
            (is (not (re-find #"dev polls|rebuilt in \d+ ms \(full" (str out))) (str "no poller: " out))
            (finally (binding [*out* out *err* out] ((:stop! h))))))))
    (testing "a pod that dies with no stop to follow still falls back, after the grace"
      (with-site url-site
        (fn [dir]
          (doseq [d ["assets" "i18n" "overrides"]] (fs/create-dirs (fs/path dir d)))
          (let [out    (java.io.StringWriter.)
                alive? (atom true)
                pod (fn [_cfg _on-change {:keys [specs on-ready]}]
                      (on-ready (fn [spec] {:path (:path spec)})
                                {:unwatch! (fn [_]) :watchers (zipmap (map :path specs) (map :path specs))})
                      {:pod-id nil :alive? (fn [] @alive?)})
                h (binding [*out* out *err* out]
                    (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true :interval 100}
                                    {:run-server! (fn [& _] (fn [& _] nil))
                                     :try-pod-watch! pod
                                     :poll-watch! dev/poll-watch!
                                     :follow-ms 20
                                     :notify! (fn [_]) :notify-error! (fn [_])}))]
            (try
              (reset! alive? false)
              (is (wait-until #(re-find #"(?s)fswatcher pod quit.*rebuilt in \d+ ms \(full rebuild\)" (str out))
                              (+ 3000 dev/signal-grace-ms))
                  (str out))
              (is (= 1 (count (re-seq #"fswatcher pod quit" (str out)))) (str out))
              (finally (binding [*out* out *err* out] ((:stop! h)))))))))))

;; ---------------------------------------------------------------------------
;; Item 11: the inotify-limit fallback's first rebuild

(deftest the-fallback-s-first-rebuild-says-full-rebuild
  (testing "item 11: the rebuild once polling takes over from the pod was
            reported as `(6 files changed)` — the watched roots, submitted
            to force it"
    (with-site url-site
      (fn [dir]
        (doseq [d ["assets" "i18n" "overrides"]] (fs/create-dirs (fs/path dir d)))
        (let [out      (java.io.StringWriter.)
              cb       (atom nil)
              started? (atom false)
              register! (fn [spec]
                          (when @started? (throw (ex-info "inotify_init1: too many open files" {})))
                          {:path (:path spec)})
              pod (fn [_cfg on-change {:keys [specs accept? on-ready]}]
                    (reset! cb {:on-change on-change :accept? accept?})
                    (on-ready register! {:unwatch! (fn [_]) :watchers (zipmap (map :path specs) (map register! specs))})
                    true)
              seen (atom [])
              own-writes dev/own-writes
              gone-temp? dev/gone-temp?
              h (binding [*out* out *err* out]
                  (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true :interval 100}
                                  {:run-server! (fn [& _] (fn [& _] nil))
                                   :try-pod-watch! pod
                                   :poll-watch! dev/poll-watch!
                                   :follow-ms 50
                                   :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            ;; follow-ups.1: the marker reaches no path function
            (with-redefs [dev/own-writes (fn [w ps now] (swap! seen conj (vec ps)) (own-writes w ps now))
                          dev/gone-temp? (fn [p] (swap! seen conj [p]) (gone-temp? p))]
              (reset! started? true)
              (let [d (fs/path dir "content" "50.A")]
                (fs/create-dirs d)
                (let [{:keys [on-change accept?]} @cb]
                  (when (accept? (str d)) (on-change (with-meta [(str d)] {:type :create})))))
              (is (wait-until #(str/includes? (str out) "inotify limit is reached") 5000) (str out))
              (is (wait-until #(re-find #"rebuilt in \d+ ms \(full rebuild\)" (str out)) 5000)
                  (str "says what it is: " out)))
            (is (not (re-find #"files changed" (str out))) (str out))
            (is (not (str/includes? (str out) "build failed")) (str out))
            (is (seq @seen) "dev's own-writes check ran")
            (is (not-any? #(some #{dev/full-rebuild} %) @seen) "on paths only")
            (finally (binding [*out* out *err* out] ((:stop! h))))))))))

(deftest the-full-rebuild-marker-is-never-a-path
  (testing "follow-ups.1 (item 11): `\"<full rebuild>\"` is no valid Windows
            path, and the rebuild handed it to `gone-temp?` (`fs/file-name`,
            `fs/exists?`) and, under --reload-code, to `theme-namespaces`
            (`fs/absolutize`): a false `build failed` there"
    (testing "the marker is a path on no system: one that reached an `fs/`
              function would throw here too"
      (is (thrown? IllegalArgumentException (fs/path dev/full-rebuild)) "InvalidPathException"))
    (let [theme (dev/theme-dir true)
          out   (java.io.StringWriter.)
          calls (atom [])
          seen  (atom [])
          gone-temp? dev/gone-temp?
          theme-namespaces dev/theme-namespaces
          rebuild (dev/make-rebuild {:build (fn [] {})
                                     :out "/nonexistent"
                                     :state (atom {})
                                     :reload-code theme
                                     :notify! #(swap! calls conj %)
                                     :notify-error! #(swap! calls conj [:error %])})]
      (is (some? theme) "a checkout, so --reload-code has a theme directory")
      (with-redefs [dev/gone-temp?       (fn [p] (swap! seen conj p) (gone-temp? p))
                    dev/theme-namespaces (fn [d ps] (swap! seen into ps) (theme-namespaces d ps))]
        (testing "alone"
          (binding [*out* out *err* out] (rebuild [dev/full-rebuild]))
          (is (re-find #"rebuilt in \d+ ms \(full rebuild\)" (str out)) (str out))
          (is (not (str/includes? (str out) "build failed")) (str out))
          (is (= ["reload"] @calls)))
        (testing "with a path the poller saw in the same batch"
          (reset! calls [])
          (binding [*out* out *err* out]
            (rebuild [dev/full-rebuild (str (fs/path theme "resources" "css" "theme.css"))]))
          (is (= 2 (count (re-seq #"\(full rebuild\)" (str out)))) (str out))
          (is (not (str/includes? (str out) "build failed")) (str out))
          (is (= ["reload"] @calls) "a full rebuild reloads the page")))
      (is (seq @seen) "the path functions ran")
      (is (not-any? #{dev/full-rebuild} @seen) "on paths only"))))

;; ---------------------------------------------------------------------------
;; Item 5: `bb clean` and a .git below the output directory's top level

(defn- tree
  "Every file under `dir` → its content."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true})
              :when (fs/regular-file? p)]
          [(str (fs/relativize dir p)) (slurp (fs/file p))])))

(deftest clean-refuses-a-marked-directory-with-a-git-below-its-top
  (testing "item 5: the check that `bb clean` never deletes a marked
            directory holding a .git anywhere below it had no test"
    (with-site url-site
      (fn [dir]
        (let [dist (fs/path dir "dist")]
          (doseq [[label make!] [["a .git directory two levels down"
                                  #(do (fs/create-dirs (fs/path dist "deploy" "site" ".git"))
                                       (spit (fs/file dist "deploy" "site" ".git" "HEAD") "ref: refs/heads/gh-pages\n"))]
                                 ["a worktree's .git file one level down"
                                  #(do (fs/create-dirs (fs/path dist "deploy"))
                                       (spit (fs/file dist "deploy" ".git") "gitdir: ../../.git/worktrees/deploy\n"))]]]
            (testing label
              (fs/delete-tree dist)
              (let [[v out] (run-task #'cli/build dir)]
                (is (not (instance? Throwable v)) (str out (ex-msg v))))
              (is (fs/exists? (fs/path dist ".clogem-output")) "marked")
              (make!)
              (let [before (tree dist)
                    [v]    (run-task #'cli/clean dir)]
                (is (= 1 (:babashka/exit (ex-data v))) (str (ex-msg v)))
                (is (re-find #"holds a \.git" (str (ex-msg v))))
                (is (= before (tree dist)) "untouched")))))))))

;; ---------------------------------------------------------------------------
;; Item 6: the hint for a site.edn `:build :out` outside the site

(deftest an-out-outside-the-site-gets-a-hint-that-helps
  (testing "item 6: an explicit `:build {:out \"dist\"}` with `dist` a link
            out of the site is refused, and the hint recommended \"the
            default dist\" — the folder refused"
    (with-site url-site
      (fn [dir]
        (let [www (fs/create-temp-dir {:prefix "clogem-fuwww"})]
          (try
            (fs/create-sym-link (fs/path dir "dist") www)
            (doseq [out ["dist" (str www) "../elsewhere"]]
              (testing out
                (spit (fs/file dir "site.edn") (pr-str (assoc url-site :build {:out out})))
                (let [[v printed] (run-task #'cli/build dir)
                      msg (str (ex-msg v) printed)]
                  (is (re-find #"outside the site directory" msg) msg)
                  (is (not (re-find #"such as the default" msg)) (str "no advice to use what was refused: " msg))
                  (is (re-find #"remove :build :out from site\.edn" msg) msg)
                  (is (re-find #"--out" msg) msg))))
            (testing "the other refusals keep their hint"
              (spit (fs/file dir "site.edn") (pr-str (assoc url-site :build {:out "content"})))
              (let [[v printed] (run-task #'cli/build dir)]
                (is (re-find #"such as the default \"dist\"" (str (ex-msg v) printed)))))
            (finally (fs/delete-tree www))))))))

;; ---------------------------------------------------------------------------
;; Item 8: a pre-encoded :nav link ending in /

(deftest a-pre-encoded-nav-link-is-not-encoded-twice
  (testing "item 8: a `:nav` link ending in `/` (a site page, given the
            language prefix) was percent-encoded whole, so
            `/%E6%96%87%E6%A1%A3/` became `/%25E6%2596…/`; one without the
            slash gets the base only and is appended as written (P4-D.4
            item 4)"
    (doseq [base ["/" "/my docs/"]]
      (testing base
        (let [links [["/文档/" "%E6%96%87%E6%A1%A3/"]
                     ["/%E6%96%87%E6%A1%A3/" "%E6%96%87%E6%A1%A3/"]
                     ["/%e6%96%87%e6%a1%a3/" "%e6%96%87%e6%a1%a3/"]
                     ["/my docs/" "my%20docs/"]
                     ["/my%20docs/" "my%20docs/"]
                     ["/100%/" "100%25/"]
                     ;; follow-ups.1: `#`, `?` and the sub-delimiters are
                     ;; part of a site page's name, encoded as on main —
                     ;; the tag page of `C#` is /tags/c%23/
                     ["/tags/c#/" "tags/c%23/"]
                     ["/categories/a?b/" "categories/a%3Fb/"]
                     ["/a&b=c+d/" "a%26b%3Dc%2Bd/"]
                     ["/tags/c%23/" "tags/c%23/"]
                     ;; without the slash: as written
                     ["/文档" "文档"]
                     ["/%E6%96%87%E6%A1%A3" "%E6%96%87%E6%A1%A3"]
                     ["/my%20docs" "my%20docs"]]]
          (with-site (assoc url-site
                            :site {:title "S" :url "https://s.example" :base base}
                            :langs {:default :en :enabled [:en :zh-Hans]}
                            :nav (vec (map-indexed (fn [i [l]] {:text (str "N" i) :link l}) links)))
            (fn [dir]
              (let [[v out] (run-task #'cli/build dir "--no-write")]
                (is (not (instance? Throwable v)) (str out (ex-msg v))))
              (let [enc (clogem.util/url-encode-path base)]
                (doseq [[page prefix] [["index.html" ""] ["zh-Hans/index.html" "zh-Hans/"]]
                        :let [html (slurp (fs/file dir "dist" page))]
                        [i [link want]] (map-indexed vector links)
                        :let [want (str enc (when (str/ends-with? link "/") prefix) want)
                              got  (second (re-find (re-pattern (str "href=\"([^\"]*)\">N" i "<")) html))]]
                  (is (= want got) (str page ": " link)))))))))))

(deftest more-article-is-encoded-as-a-nav-link-is
  (testing "follow-ups.1 (item 8): `:theme :update-bar :more-article` goes
            through `nav-href` too, so `/tags/c#/` was sent as written — a
            link to /tags/c with the fragment `/`"
    (doseq [[more want] [["/tags/c#/" "/tags/c%23/"]
                         ["/categories/a?b/" "/categories/a%3Fb/"]
                         ["/%E6%96%87%E6%A1%A3/" "/%E6%96%87%E6%A1%A3/"]
                         ["/文档/" "/%E6%96%87%E6%A1%A3/"]]]
      (testing more
        (with-site (assoc url-site :theme {:update-bar {:more-article more}})
          (fn [dir]
            (spit (fs/file dir "content" "01.Guide" "02.u.md")
                  (str/replace article #"t00001|title: T" {"t00001" "u00002" "title: T" "title: U"}))
            (let [[v out] (run-task #'cli/build dir "--no-write")]
              (is (not (instance? Throwable v)) (str out (ex-msg v))))
            (let [html (slurp (fs/file dir "dist" "pages" "t00001" "index.html"))]
              (is (= want (second (re-find #"<p><a href=\"([^\"]*)\">More →</a></p></section>" html)))))))))))

;; ---------------------------------------------------------------------------
;; Item 9: --help without internal references

(def ^:private internal-reference
  "A fix round (`D.4`, `P4-D.4`, `E1a.1`, `round 3`), a round's item
  (`item 3`) or a bare DESIGN.md section (`§4`)."
  #"\b(?:P\d+-)?[A-F]\d*[a-z]?\.\d+\b|(?i)\bround \d|\bitem \d|§\s*\d")

(deftest no-task-s-help-names-an-internal-round
  (testing "item 9: `bb clean --help` said \"(P4-D.4 item 3)\" and
            `bb fetch-tool --help` \"(§4; …)\""
    (doseq [v [#'cli/build #'cli/dev #'cli/serve #'cli/doctor #'cli/clean #'cli/fm-fix
               #'cli/fetch-tool #'cli/test-cmd]]
      (let [help (cli/help-text v)]
        (is (not (re-find internal-reference help)) (str (:name (meta v)) ":\n" help))))
    (testing "the pattern finds what it should"
      (doseq [s ["(P4-D.4 item 3)" "D.4" "E1a.1" "round 3" "§4"]]
        (is (re-find internal-reference s) s)))))

;; ---------------------------------------------------------------------------
;; Item 10: unknown command-line options

(deftest an-unknown-option-is-a-warning-naming-it-and-the-task-s-options
  (testing "item 10: `bb build --prot 1` built as if nothing was wrong"
    (with-site url-site
      (fn [dir]
        (let [[v out] (run-task #'cli/build dir "--prot" "1" "-x" "--no-foo" "--no-write")]
          (is (not (instance? Throwable v)) (str "still runs: " out (ex-msg v)))
          (is (fs/exists? (fs/path dir "dist" "index.html")) "and builds")
          (doseq [o ["--prot" "-x" "--no-foo"]]
            (is (re-find (re-pattern (str "clogem-press: bb build: unknown option " o " \\(ignored\\); its options are "
                                          "--site-dir, .*--no-write, .*--help\\."))
                         out)
                (str o ": " out)))
          (is (= 3 (count (re-seq #"unknown option" out))) "one line each")
          (is (not (re-find #"unknown option --(?:site-dir|no-write|out)\b" out))))
        (testing "known options, aliases and --help say nothing"
          (let [[_ out] (run-task #'cli/build dir "-o" "dist" "--no-write" "--no-search")]
            (is (not (str/includes? out "unknown option")) out))
          (let [[_ out] (run-task #'cli/clean dir "-h")]
            (is (not (str/includes? out "unknown option")) out)))
        (testing "dev's own rebuilds, which pass dev's options to build, say nothing"
          (let [out (java.io.StringWriter.)
                h   (binding [*out* out *err* out]
                      (dev/start-dev! (assoc (parse #'cli/dev ["--poll" "--no-write" "--no-search" "--interval" "100"])
                                             :site-dir (str dir))
                                      {:run-server! (fn [& _] (fn [& _] nil))
                                       :poll-watch! (fn [& _] nil)
                                       :notify! (fn [_]) :notify-error! (fn [_])}))]
            (try
              (is (str/includes? (str out) "rebuilt in") (str out))
              (is (not (str/includes? (str out) "unknown option")) (str out))
              (finally (binding [*out* out *err* out] ((:stop! h)))))))
        (testing "through bb.edn, as a script runs it: a warning, and exit 0"
          (let [bb  (or (-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)) "bb")
                p   (-> (ProcessBuilder. [bb "--config" (str (fs/absolutize "bb.edn")) "clean" "--bogus"])
                        (.directory (fs/file dir))
                        (.redirectErrorStream true)
                        (.start))
                out (slurp (.getInputStream p))]
            (is (zero? (.waitFor p)) out)
            (is (str/includes? out "clogem-press: bb clean: unknown option --bogus (ignored)") out)))))))
