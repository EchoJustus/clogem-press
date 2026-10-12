;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round4-test
  "P4-D.4: the follow-up to Task D — its known issues, fixed mainly by
  simplifying. Each test names the item it pins down; each failed before
  its fix."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.dev :as dev]))

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
        (sparse! (fs/path root "big.bin") (* 8 1024 1024))
        (with-port root
          (fn [port]
            ;; warm the server's own threads up first
            (raw-get port "GET /small.txt HTTP/1.1")
            (let [threads #(set (map (fn [^Thread t] (.getName t)) (keys (Thread/getAllStackTraces))))
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
