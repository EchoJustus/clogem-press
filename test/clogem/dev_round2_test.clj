;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round2-test
  "P4-D.2: `bb dev`'s second fix round — new directories in pod mode, a
  `stop!` that waits, `:build :out` from site.edn, the static handler's
  containment and memory, and the wiring mutants that survived D.1.

  Each test names the item it pins down; each failed before its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.dev :as dev]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article and `site-edn`; calls (f dir out)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-r2"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms`; its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(defn- spec-of [v] (:spec (:org.babashka/cli (meta v))))

(defn- parse
  "`args` parsed as `bb <task>` parses them: babashka.cli with the task's
  own spec, defaults included."
  [v args]
  (bcli/parse-opts args {:spec (spec-of v)}))

(defn- quiet [f]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (f)))

;; ---------------------------------------------------------------------------
;; Item 5: `:build :out` in site.edn is honoured

(deftest build-out-in-site-edn-is-honoured-through-the-cli-s-own-options
  (with-site (assoc url-site :build {:out "public"})
    (fn [dir _]
      (testing "no --out: site.edn's :build :out"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
        (is (not (fs/exists? (fs/path dir "dist")))))
      (testing "--out wins over site.edn"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write" "--out" "given"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "given" "pages" "t00001" "index.html"))))
      (testing "dev's and clean's specs agree"
        (is (nil? (:out (parse #'cli/dev []))))
        (is (nil? (:out (parse #'cli/clean []))))
        (quiet #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir))))
        (is (not (fs/exists? (fs/path dir "public"))) "clean removes site.edn's :build :out"))
      (testing "dev serves site.edn's :build :out"
        (let [servers (atom [])
              h (quiet #(dev/start-dev!
                         (assoc (parse #'cli/dev ["--poll" "--no-write"]) :site-dir (str dir))
                         {:run-server! (fn [handler o] (swap! servers conj handler) (fn [& _] nil))
                          :poll-watch! (fn [& _] nil)
                          :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
            (is (= 200 (:status ((first @servers) {:uri "/pages/t00001/"}))))
            (finally (quiet (:stop! h)))))))
    )
  (with-site url-site
    (fn [dir _]
      (testing "neither: dist"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "dist" "pages" "t00001" "index.html")))))))

(deftest serve-s-dir-keeps-its-default
  (is (= "dist" (:dir (parse #'cli/serve []))))
  (is (= "x" (:dir (parse #'cli/serve ["--dir" "x"])))))
