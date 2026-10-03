;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.write-windows-test
  "Renaming over a file another process holds open (fix round P4-A.1, F5).

  On Windows `MoveFileEx(MOVEFILE_REPLACE_EXISTING)` is refused — an
  AccessDeniedException, or a bare FileSystemException for a sharing
  violation — while `bb dev`'s server or an antivirus has the target open
  without FILE_SHARE_DELETE. 0.2.0 wrote in place and never met it. No
  Windows machine runs this suite, so the refusal is simulated: the move is
  stubbed to throw, and since bb cannot construct either exception class, the
  class name the write path reads is stubbed alongside it."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.diag :as diag]
            [clogem.render :as render]))

(def ^:private access-denied "java.nio.file.AccessDeniedException")
(def ^:private fs-exception "java.nio.file.FileSystemException")

(defn- failure
  "An IOException the stubbed class-name lookup reports as `class-name`."
  [class-name]
  (java.io.IOException. (str "stub:" class-name)))

(def ^:private real-class-name @#'render/exception-class-name)

(defn- stub-class-name [e]
  (let [m (str (ex-message e))]
    (if (.startsWith m "stub:") (subs m 5) (real-class-name e))))

(defn- run-write
  "Write \"new\" over a file holding \"old\" with the move stubbed by
  `move-fn` (given the attempt number, 1-based). Returns {:result
  :pauses :moves :content :temps}."
  [windows? move-fn]
  (let [d      (fs/create-temp-dir {:prefix "clogem-win"})
        out    (fs/path d "out")
        f      (fs/path out "page" "index.html")
        moves  (atom 0)
        pauses (atom [])
        real-move @#'render/atomic-move!]
    (try
      (fs/create-dirs (fs/parent f))
      (spit (fs/file f) "old")
      (let [result (binding [render/*windows?* windows?
                             diag/*sink* (atom [])]
                     (with-redefs-fn {#'render/exception-class-name stub-class-name
                                      #'render/pause! (fn [ms] (swap! pauses conj ms))
                                      #'render/atomic-move! (fn [tmp target]
                                                              (if-let [e (move-fn (swap! moves inc))]
                                                                (throw e)
                                                                (real-move tmp target)))}
                       #(try (render/write-site! {:outputs [{:file f :bytes (.getBytes "new" "UTF-8")}]
                                                  :out (str out)})
                             (catch Throwable t t))))]
        {:result  result
         :pauses  @pauses
         :moves   @moves
         :content (slurp (fs/file f))
         :temps   (vec (for [p (fs/glob out "**" {:hidden true})
                             :when (.contains (str (fs/file-name p)) ".clogem-tmp-")]
                         (str p)))})
      (finally (fs/delete-tree d)))))

(def ^:private backoff [5 10 20 40 80 160 320 640 1280])

(deftest the-refusal-test-reads-class-names
  (binding [render/*windows?* false]
    (with-redefs [render/exception-class-name stub-class-name]
      (is (#'render/refused? (failure access-denied)) "AccessDenied anywhere")
      (is (not (#'render/refused? (failure fs-exception))) "a bare FileSystemException only on Windows")
      (is (not (#'render/refused? (java.io.IOException. "No space left on device"))))))
  (binding [render/*windows?* true]
    (with-redefs [render/exception-class-name stub-class-name]
      (is (#'render/refused? (failure fs-exception)))
      (is (not (#'render/refused? (java.nio.file.NoSuchFileException. "x")))))))

(deftest a-refused-rename-on-windows-is-retried-then-written-in-place
  (doseq [cls [access-denied fs-exception]]
    (testing cls
      (let [{:keys [result pauses moves content temps]} (run-write true (fn [_] (failure cls)))]
        (is (map? result) (pr-str result))
        (is (= backoff pauses) "every backoff step, ~2.5 s in all")
        (is (= (inc (count backoff)) moves))
        (is (= "new" content) "the 0.2.0 floor: written in place")
        (is (empty? temps))))))

(deftest a-rename-refused-briefly-succeeds-on-a-retry
  (let [{:keys [result pauses moves content temps]}
        (run-write true (fn [n] (when (< n 3) (failure access-denied))))]
    (is (map? result) (pr-str result))
    (is (= [5 10] pauses))
    (is (= 3 moves))
    (is (= "new" content))
    (is (empty? temps))))

(deftest elsewhere-a-refusal-falls-back-at-once
  (let [{:keys [result pauses moves content]} (run-write false (fn [_] (failure access-denied)))]
    (is (map? result) (pr-str result))
    (is (= [] pauses) "no retries off Windows")
    (is (= 1 moves))
    (is (= "new" content)))
  (testing "a bare FileSystemException is not a refusal off Windows"
    (let [{:keys [result pauses content temps]} (run-write false (fn [_] (failure fs-exception)))]
      (is (instance? java.io.IOException result) (pr-str result))
      (is (= [] pauses))
      (is (= "old" content))
      (is (empty? temps)))))

(deftest any-other-failure-is-rethrown-and-the-target-untouched
  (doseq [windows? [true false]]
    (let [{:keys [result pauses moves content temps]}
          (run-write windows? (fn [_] (java.io.IOException. "No space left on device")))]
      (is (instance? java.io.IOException result) (pr-str result))
      (is (= "No space left on device" (ex-message result)))
      (is (= [] pauses))
      (is (= 1 moves))
      (is (= "old" content))
      (is (empty? temps) "the cleanup ran, and did not mask the error"))))

(deftest a-real-access-denied-is-recognised
  ;; where the OS will hand us a genuine one (sysfs refuses a new file even
  ;; to root), check the class-name test against it, unstubbed
  (let [e (try (java.nio.file.Files/write (fs/path "/sys/clogem-probe") (.getBytes "x")
                                          ^"[Ljava.nio.file.OpenOption;"
                                          (into-array java.nio.file.OpenOption []))
               nil
               (catch java.io.IOException e e))]
    (if (= access-denied (some-> e class .getName))
      (binding [render/*windows?* false]
        (is (#'render/refused? e)))
      (println "a-real-access-denied-is-recognised: skipped — no AccessDeniedException to be had here"))))

(deftest a-refused-temp-file-falls-back-to-an-in-place-write
  (let [d    (fs/create-temp-dir {:prefix "clogem-win"})
        out  (fs/path d "out")
        f    (fs/path out "index.html")
        real @#'render/write-bytes!
        refused (atom 0)]
    (try
      (spit (fs/file (doto (fs/file out) (.mkdirs)) "index.html") "old")
      (binding [diag/*sink* (atom [])]
        (with-redefs-fn {#'render/exception-class-name stub-class-name
                         #'render/write-bytes! (fn [p b]
                                                 (if (.contains (str (fs/file-name p)) ".clogem-tmp-")
                                                   (do (swap! refused inc)
                                                       (throw (failure access-denied)))
                                                   (real p b)))}
          #(render/write-site! {:outputs [{:file f :bytes (.getBytes "new" "UTF-8")}] :out (str out)})))
      (is (pos? @refused) "the temp file was refused")
      (is (= "new" (slurp (fs/file f))))
      (finally (fs/delete-tree d)))))

(deftest an-unwritable-directory-with-a-writable-file-is-written-in-place
  ;; the real thing, where permissions bind (not as root): the directory
  ;; refuses a new temp file, the file itself is writable — 0.2.0's
  ;; in-place write succeeded, so this must too
  (if (= "root" (System/getProperty "user.name"))
    (println "an-unwritable-directory-with-a-writable-file-is-written-in-place: skipped — root ignores permissions")
    (let [d   (fs/create-temp-dir {:prefix "clogem-win"})
          out (fs/path d "out")
          dir (fs/path out "locked")
          f   (fs/path dir "index.html")]
      (try
        (fs/create-dirs dir)
        (spit (fs/file f) "old")
        (fs/set-posix-file-permissions dir "r-xr-xr-x")
        (binding [diag/*sink* (atom [])]
          (render/write-site! {:outputs [{:file f :bytes (.getBytes "new" "UTF-8")}] :out (str out)}))
        (is (= "new" (slurp (fs/file f))))
        (finally
          (fs/set-posix-file-permissions dir "rwxr-xr-x")
          (fs/delete-tree d))))))
