;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-test
  "Dev server: path containment and watcher liveness.

  Neither is decoration. `bb dev` serves the working tree's neighbourhood over
  HTTP, and a watcher that registers but never fires makes the whole loop look
  like a build cache bug."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.dev :as dev]))

;; ---------------------------------------------------------------------------
;; resolve-file containment

(defn- sandbox
  "A `dist/` to serve, a `dist-readonly/` sibling that must stay unreachable —
  CI creates exactly that pair — and a secret one directory up."
  []
  (let [root (fs/create-temp-dir {:prefix "clogem-dev"})]
    (fs/create-dirs (fs/path root "dist"))
    (fs/create-dirs (fs/path root "dist-readonly"))
    (spit (fs/file (fs/path root "dist" "index.html")) "<h1>ok</h1>")
    (spit (fs/file (fs/path root "dist" "sub" "index.html"))
          (do (fs/create-dirs (fs/path root "dist" "sub")) "<h1>sub</h1>"))
    (spit (fs/file (fs/path root "dist-readonly" "secret.txt")) "not yours")
    (spit (fs/file (fs/path root "secret.txt")) "not yours either")
    root))

(deftest resolve-file-serves-what-it-should
  (let [root (sandbox)
        dist (fs/path root "dist")]
    (try
      (is (some? (dev/resolve-file dist "/index.html")))
      (is (some? (dev/resolve-file dist "/")) "a directory resolves to its index.html")
      (is (some? (dev/resolve-file dist "/sub/")))
      (is (nil? (dev/resolve-file dist "/nope.html")))
      (finally (fs/delete-tree root)))))

(deftest resolve-file-refuses-a-sibling-whose-name-shares-the-root-prefix
  (testing "the containment check compared canonical paths as STRINGS with no
            trailing separator, so /site/dist-readonly passed a /site/dist root.
            CI creates that exact sibling — `build --out dist-readonly` — so the
            dev server would happily serve it."
    (let [root (sandbox)
          dist (fs/path root "dist")]
      (try
        (is (nil? (dev/resolve-file dist "/../dist-readonly/secret.txt"))
            "a sibling directory is not inside the root just because its name starts the same")
        (finally (fs/delete-tree root))))))

(deftest resolve-file-refuses-traversal
  (let [root (sandbox)
        dist (fs/path root "dist")]
    (try
      (doseq [uri ["/../secret.txt"
                   "/sub/../../secret.txt"
                   "/%2e%2e/secret.txt"
                   "/../../etc/passwd"]]
        (is (nil? (dev/resolve-file dist uri)) uri))
      (finally (fs/delete-tree root)))))

(deftest resolve-file-still-allows-a-path-that-only-looks-like-traversal
  (testing "the fix must not be a blunt `..` ban: a legitimate file may be named
            anything, including something with a doubled root prefix inside"
    (let [root (sandbox)
          dist (fs/path root "dist")]
      (try
        (fs/create-dirs (fs/path dist "dist-readonly"))
        (spit (fs/file (fs/path dist "dist-readonly" "page.html")) "mine")
        (is (some? (dev/resolve-file dist "/dist-readonly/page.html"))
            "this one really is under the root")
        (finally (fs/delete-tree root))))))
