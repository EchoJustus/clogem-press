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
            [clogem.render :as render]
            [hiccup2.core]))

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
  (let [;; hrefs percent-encode path segments (D-P2-3); the directory on
        ;; disk is the unencoded slug
        rel (str/replace (java.net.URLDecoder/decode (str href) "UTF-8") #"^/+" "")]
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

(deftest the-scroll-spy-script-is-referenced-with-the-base
  (testing "D-P2-9: js/ is exported like css/, and the <script src> carries the
            site base (the css-href pattern, not layout/asset's trailing slash)"
    (let [out (build-with-base "/project/")]
      (try
        (is (fs/regular-file? (fs/path out "clogem" "js" "toc.js")))
        (is (str/includes? (slurp (fs/file (fs/path out "pages" "643259" "index.html")))
                           "src=\"/project/clogem/js/toc.js\""))
        (finally (fs/delete-tree out))))))

;; ---------------------------------------------------------------------------
;; localized-file (fix 10)

(deftest localized-file-returns-a-map-and-matches-suffixes-case-insensitively
  (let [dir (fs/create-temp-dir {:prefix "clogem-lf"})
        put (fn [rel s] (let [p (fs/path dir "content" rel)]
                          (fs/create-dirs (fs/parent p)) (spit (fs/file p) s)))]
    (try
      (put "index.md" "---\ntitle: Home\n---\n\nEnglish body\n")
      (put "index.zh-hant.md" "---\ntitle: 首頁\n---\n\n繁體正文\n")
      (put "@pages/tagsPage.MS.md" "---\ntitle: Tag\n---\n")
      (put "01.Guide/01.a.md" "---\ntitle: A\npermalink: /pages/aaaaaa/\n---\n\nbody\n")
      (let [cfg (first (diag/collecting (config/load-config (str dir) nil {:content {:write-front-matter false}})))
            lf  (fn [rel lang] (some-> (render/localized-file cfg rel lang) (update :path #(str (fs/file-name %)))))]
        (testing "own, default and missing shapes"
          (is (= {:path "index.md" :own? true} (lf "index" :en)) "the default file is the default language's own")
          (is (= {:path "index.md" :own? false} (lf "index" :ta)) "…and only a fallback elsewhere")
          (is (nil? (lf "@pages/categoriesPage" :en))))
        (testing "a mis-cased suffix is the language's own file, as the scanner would read it"
          (is (= {:path "index.zh-hant.md" :own? true} (lf "index" :zh-Hant)))
          (is (= {:path "tagsPage.MS.md" :own? true} (lf "@pages/tagsPage" :ms))))
        (testing "fix C: the exact canonical spelling wins over a case-insensitive match"
          (put "index.zh-Hant.md" "---\n---\n\nCANONICAL\n")
          (let [r (render/localized-file cfg "index" :zh-Hant)]
            (is (= "index.zh-Hant.md" (str (fs/file-name (:path r)))))
            (is (= ["index.zh-Hant.md" "index.zh-hant.md"] (mapv #(str (fs/file-name %)) (:ambiguous r)))))
          (fs/delete (fs/path dir "content" "index.zh-Hant.md")))
        (testing "end to end: the zh-Hant home renders the mis-cased file's body"
          (let [[m _] (diag/collecting (cli/analyse cfg))
                pm   (first (diag/collecting (render/page-map m)))
                html (fn [u] (str (hiccup2.core/html ((get pm u)))))]
            (is (str/includes? (html "/zh-Hant/") "繁體正文"))
            (is (not (str/includes? (html "/zh-Hans/") "繁體正文"))))))
      (finally (fs/delete-tree dir)))))
