;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.render-test
  "Output layout.

  `dist/` **is** the deploy root. A project site's output directory is served at
  `https://host/<base>/`, so the site's `:base` belongs in every emitted *link*
  and in no part of the *file layout*. Only base `/` was exercised before, and
  that is precisely the base under which stripping and not stripping produce
  identical output — so the whole class of bug was invisible."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.render :as render]))

(def demo "examples/demo-site")

;; ---------------------------------------------------------------------------
;; uri->file

(deftest uri-to-file-at-the-root-base
  (testing "base / is the identity case — leading and trailing slashes only"
    (doseq [[uri expected] [["/"                 "index.html"]
                            ["/pages/a1b2c3/"    "pages/a1b2c3/index.html"]
                            ["/zh-Hans/pages/x/" "zh-Hans/pages/x/index.html"]]]
      (is (= (str (fs/path "out" expected))
             (str (render/uri->file "/" (fs/path "out") uri)))
          uri))))

(deftest uri-to-file-strips-a-non-root-base
  (testing "the base is stripped: dist/ is the deploy root, not a directory holding one"
    (doseq [[uri expected] [["/project/"              "index.html"]
                            ["/project/pages/a1b2c3/" "pages/a1b2c3/index.html"]
                            ["/project/ta/"           "ta/index.html"]]]
      (is (= (str (fs/path "out" expected))
             (str (render/uri->file "/project/" (fs/path "out") uri)))
          uri))))

;; ---------------------------------------------------------------------------
;; End to end: every internal link must resolve to a file

(defn- build-with-base
  [base]
  (let [out (fs/create-temp-dir {:prefix "clogem-base"})
        cfg (first (diag/collecting
                    (config/load-config demo nil {:build   {:out (str out)}
                                                  :site    {:base base}
                                                  :content {:write-front-matter false}})))
        [model _] (diag/collecting (cli/analyse cfg))]
    (diag/collecting (render/build! cfg model))
    out))

(defn- internal-hrefs
  [out]
  (->> (fs/glob out "**.html")
       (mapcat #(re-seq #"(?:href|src)=\"(/[^\"]*)\"" (slurp (fs/file %))))
       (map second)
       distinct
       sort))

(defn- resolves?
  [out href]
  (let [rel (str/replace href #"^/+" "")]
    (or (and (str/blank? rel) (fs/regular-file? (fs/path out "index.html")))
        (fs/regular-file? (fs/path out rel))
        (fs/regular-file? (fs/path out (str/replace rel #"/+$" "") "index.html")))))

(deftest every-internal-link-resolves-under-a-non-root-base
  (testing "the invariant the base bug broke: href → file, once the base is stripped"
    (let [base "/project/"
          out  (build-with-base base)]
      (try
        (is (fs/regular-file? (fs/path out "index.html"))
            "the site root must not be a 404")
        (is (not (fs/exists? (fs/path out "project")))
            "the base must not appear in the output layout at all")
        (let [hrefs (internal-hrefs out)]
          (is (seq hrefs))
          (is (every? #(str/starts-with? % base) hrefs)
              (str "every emitted link carries the base: "
                   (pr-str (remove #(str/starts-with? % base) hrefs))))
          (let [dangling (remove #(resolves? out (subs % (count base))) hrefs)]
            (is (empty? dangling)
                (str "links with no file behind them: " (pr-str dangling)))))
        (finally (fs/delete-tree out))))))

(deftest the-root-base-still-works
  (testing "the regression guard must not itself regress base /"
    (let [out (build-with-base "/")]
      (try
        (is (fs/regular-file? (fs/path out "index.html")))
        (let [dangling (remove #(resolves? out %) (internal-hrefs out))]
          (is (empty? dangling) (str "dangling: " (pr-str dangling))))
        (finally (fs/delete-tree out))))))
