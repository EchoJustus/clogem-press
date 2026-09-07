;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.pages-test
  "`@pages/` auto-creation (D-P2-6): golden bytes, never overwrite, idempotent,
  skipped under --no-write, gated by the :content toggles."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.pages :as pages]))

(def fixtures "test/fixtures/pages")

(defn- cfg-for [overrides]
  (first (diag/collecting (config/load-config "test/fixtures/__no_such_site__" nil overrides))))

(deftest generated-files-are-byte-pinned
  (testing "vdoing's four keys, in vdoing's order, title in the site-default language"
    (let [cfg (cfg-for {})
          strings (i18n/load-strings cfg)]
      (doseq [{:keys [flag] :as kind} pages/index-kinds]
        (is (= (slurp (fs/file fixtures (str flag ".expected.md")))
               (pages/generated-content cfg strings kind))
            flag)))))

(deftest the-title-follows-the-site-default-language
  (let [cfg (cfg-for {:langs {:default :zh-Hans}})
        strings (i18n/load-strings cfg)]
    (is (str/includes? (pages/generated-content cfg strings (first pages/index-kinds)) "title: 分类"))))

(defn- temp-site []
  (let [dir (fs/create-temp-dir {:prefix "clogem-pages"})]
    (fs/create-dirs (fs/path dir "content" "01.Guide"))
    (spit (fs/file (fs/path dir "content" "01.Guide" "01.a.md")) "---\ntitle: A\npermalink: /pages/aaaaaa/\n---\n\nbody\n")
    dir))

(defn- pages-dir [site] (fs/path site "content" "@pages"))

(deftest ensure-files-creates-never-overwrites-and-is-idempotent
  (let [site (temp-site)
        cfg  (first (diag/collecting (config/load-config (str site))))]
    (try
      (fs/create-dirs (pages-dir site))
      (spit (fs/file (pages-dir site) "categoriesPage.md") "---\ncategoriesPage: true\ntitle: Mine\n---\n\nmy body\n")
      (is (= ["@pages/tagsPage.md" "@pages/archivesPage.md"] (pages/ensure-files! cfg))
          "only the missing two are created")
      (is (= "---\ncategoriesPage: true\ntitle: Mine\n---\n\nmy body\n"
             (slurp (fs/file (pages-dir site) "categoriesPage.md")))
          "the user's file is byte-identical")
      (is (= (slurp (fs/file fixtures "tagsPage.expected.md"))
             (slurp (fs/file (pages-dir site) "tagsPage.md"))))
      (is (= [] (pages/ensure-files! cfg)) "a second pass creates nothing — loop guard 3")
      (finally (fs/delete-tree site)))))

(deftest no-write-skips-creation-and-the-indexes-still-render
  (let [site (temp-site)
        cfg  (first (diag/collecting (config/load-config (str site) nil {:content {:write-front-matter false}})))]
    (try
      (is (nil? (pages/ensure-files! cfg)))
      (is (not (fs/exists? (pages-dir site))))
      (binding [diag/*sink* (atom [])]
        (cli/build {:site-dir (str site) :out (str (fs/path site "dist")) :no-write true}))
      (is (not (fs/exists? (pages-dir site))) "a read-only build touches nothing")
      (is (fs/exists? (fs/path site "dist" "categories" "index.html")) "…and still renders /categories/")
      (is (fs/exists? (fs/path site "dist" "tags" "index.html")))
      (is (fs/exists? (fs/path site "dist" "archives" "index.html")))
      (finally (fs/delete-tree site)))))

(deftest content-toggles-gate-creation
  (let [site (temp-site)
        cfg  (first (diag/collecting (config/load-config (str site) nil {:content {:tag false :archive false}})))]
    (try
      (is (= ["@pages/categoriesPage.md"] (pages/ensure-files! cfg)))
      (finally (fs/delete-tree site)))))

(deftest a-normal-build-creates-them-and-fm-fix-too
  (let [site (temp-site)]
    (try
      (binding [diag/*sink* (atom [])]
        (cli/build {:site-dir (str site) :out (str (fs/path site "dist"))}))
      (is (= #{"categoriesPage.md" "tagsPage.md" "archivesPage.md"}
             (set (map fs/file-name (fs/list-dir (pages-dir site))))))
      (let [before (slurp (fs/file (pages-dir site) "tagsPage.md"))]
        (binding [diag/*sink* (atom [])]
          (cli/fm-fix {:site-dir (str site)}))
        (is (= before (slurp (fs/file (pages-dir site) "tagsPage.md"))) "fm-fix never rewrites them"))
      (finally (fs/delete-tree site)))))
