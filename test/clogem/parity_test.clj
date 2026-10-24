;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.parity-test
  "Phase 4 Task E2 (§11.3 item 16, D-P4-13, D-P4-16): article-page parity —
  the edit link, last-updated dates from git, the update bar under
  articles, `titleTag` in list rows, the navbar logo and repository link,
  front matter `pageClass` and the 404 page. What needs a browser is in
  test/browser/parity.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.render :as render]
            [clogem.theme.parity :as parity]))

(def demo "examples/demo-site")
(def theme-css "src/clogem/theme/resources/css/theme.css")

;; ---------------------------------------------------------------------------
;; The demo, built once

(def ^:dynamic *out* nil)

(defn- build-fixture
  [f]
  (let [out (fs/create-temp-dir {:prefix "clogem-e2"})
        cfg (first (diag/collecting
                    (config/load-config demo nil {:build {:out (str out)}
                                                  :content {:write-front-matter false}})))
        [model _] (diag/collecting (cli/analyse cfg))]
    (diag/collecting (render/build! cfg model))
    (binding [*out* out]
      (try (f) (finally (fs/delete-tree out))))))

(use-fixtures :once build-fixture)

(defn- html [& parts] (slurp (fs/file (apply fs/path *out* parts))))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- load-cfg
  "Write `edn` as a temp site.edn and load it: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e2-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- write-site!
  "Write a site into `dir`: `edn` over a minimal site.edn (no search, no
  highlighting) and `files` {path-under-content content}."
  [dir edn files]
  (spit (fs/file dir "site.edn")
        (pr-str (merge-with #(if (and (map? %1) (map? %2)) (merge %1 %2) %2)
                            {:site {:title "S"} :search {:provider :none} :highlight {:provider :none}}
                            edn)))
  (doseq [[rel content] files]
    (fs/create-dirs (fs/parent (fs/path dir "content" rel)))
    (spit (fs/file dir "content" rel) content)))

(defn- build-dir
  "Build the site in `dir` (read-only: no front matter written) into
  `dir`/dist: {:pages {rel-path html} :warnings [message …]}."
  [dir]
  (let [[_ ds] (diag/collecting
                (let [cfg (config/load-config (str dir) nil {:content {:write-front-matter false}})]
                  (render/build! cfg (cli/analyse cfg))))
        dist (fs/path dir "dist")]
    {:pages    (into (sorted-map)
                     (for [p (fs/glob dist "**.html")]
                       [(str/replace (str (fs/relativize dist p)) "\\" "/") (slurp (fs/file p))]))
     :warnings (mapv :message (diag/warnings ds))}))

(defn- build-temp
  "`write-site!` and `build-dir` in a temp directory, deleted afterwards."
  [edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
    (try (write-site! dir edn files)
         (build-dir dir)
         (finally (fs/delete-tree dir)))))

(defn- article
  "A minimal article's text with `fm` (a map of front-matter lines)."
  [title permalink & [fm body]]
  (str "---\ntitle: " title "\ndate: 2026-01-01 00:00:00\npermalink: " permalink "\n"
       (apply str (for [[k v] fm] (str (name k) ": " v "\n")))
       "---\n\n" (or body "Body text.") "\n"))

(defn- page-edit-of
  "The `<div class=\"clogem-page-edit\">` of `page`, or nil."
  [page]
  (re-find #"(?s)<div class=\"clogem-page-edit\">.*?</div>" (str page)))

(defn- cfg-warnings
  "[cfg [warning message …]] for `edn`, asserting it raised no error."
  [edn]
  (let [[cfg ds] (load-cfg edn)]
    (is (empty? (diag/errors ds)) (str "never an error: " (pr-str (map :message (diag/errors ds)))))
    [cfg (mapv :message (diag/warnings ds))]))

;; ---------------------------------------------------------------------------
;; Item 1: config keys, checks and accessors

(deftest edit-link-is-checked-and-normalized
  (testing "absent or nil is off, silently"
    (let [[cfg ws] (cfg-warnings {})]
      (is (= [] ws))
      (is (nil? (config/edit-link cfg))))
    (let [[cfg ws] (cfg-warnings {:content {:edit-link nil}})]
      (is (= [] ws))
      (is (nil? (config/edit-link cfg)))))
  (testing "a non-map is one warning, and the feature is off"
    (let [[cfg ws] (cfg-warnings {:content {:edit-link true}})]
      (is (= [":content :edit-link is true, but it must be a map {:repo :branch :dir}; the edit link is off."] ws))
      (is (nil? (config/edit-link cfg)))))
  (testing "owner/name is GitHub's; the defaults are \"main\" and \"\""
    (let [[cfg ws] (cfg-warnings {:content {:edit-link {:repo "EchoJustus/clogem-press"}}})]
      (is (= [] ws))
      (is (= {:url "https://github.com/EchoJustus/clogem-press" :branch "main" :dir ""}
             (config/edit-link cfg)))))
  (testing "an https:// URL keeps its host, drops a trailing /; :dir is trimmed of / and ."
    (let [[cfg ws] (cfg-warnings {:content {:edit-link {:repo "https://gitlab.com/o/n/" :branch " dev "
                                                        :dir "/docs/./site//"}}})]
      (is (= [] ws))
      (is (= {:url "https://gitlab.com/o/n" :branch "dev" :dir "docs/site"} (config/edit-link cfg)))))
  (testing "no :repo falls back to a valid :theme :repo"
    (let [[cfg ws] (cfg-warnings {:theme {:repo "o/n"} :content {:edit-link {:dir "site"}}})]
      (is (= [] ws))
      (is (= {:url "https://github.com/o/n" :branch "main" :dir "site"} (config/edit-link cfg)))))
  (testing "neither :repo nor :theme :repo: one warning, off"
    (let [[cfg ws] (cfg-warnings {:content {:edit-link {}}})]
      (is (= [":content :edit-link has no :repo, and :theme :repo names none; the edit link is off."] ws))
      (is (nil? (config/edit-link cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:repo "nope"} :content {:edit-link {}}})]
      (is (= [":theme :repo is \"nope\", but it must be \"owner/name\" or an https:// URL; there is no repository link."
              ":content :edit-link has no :repo, and :theme :repo names none; the edit link is off."] ws))
      (is (nil? (config/edit-link cfg)))))
  (testing "an invalid :repo warns and the feature is off — even with a valid :theme :repo"
    (doseq [bad ["not a repo" "http://github.com/o/n" "https://h/x?y" 42]]
      (let [[cfg ws] (cfg-warnings {:theme {:repo "o/n"} :content {:edit-link {:repo bad}}})]
        (is (= [(str ":content :edit-link :repo is " (pr-str bad) ", but it must be \"owner/name\" or an https:// URL; "
                     "the edit link is off.")] ws))
        (is (nil? (config/edit-link cfg)) (pr-str bad)))))
  (testing "a bad :branch or :dir warns and takes the default"
    (doseq [[k bad msg default]
            [[:branch "  " "a non-blank string; using \"main\"." "main"]
             [:branch :dev "a non-blank string; using \"main\"." "main"]
             [:dir "../up" "a relative path inside the repository, with no .. segment and no \\; using \"\"." ""]
             [:dir "a/../b" "a relative path inside the repository, with no .. segment and no \\; using \"\"." ""]
             [:dir "a\\b" "a relative path inside the repository, with no .. segment and no \\; using \"\"." ""]
             [:dir 3 "a relative path inside the repository, with no .. segment and no \\; using \"\"." ""]]]
      (let [[cfg ws] (cfg-warnings {:content {:edit-link {:repo "o/n" k bad}}})]
        (is (= [(str ":content :edit-link " k " is " (pr-str bad) ", but it must be " msg)] ws))
        (is (= default (get (config/edit-link cfg) k)) (pr-str bad))
        (is (= "https://github.com/o/n" (:url (config/edit-link cfg))) "the link stays on")))))

(deftest last-updated-update-bar-and-repo-are-checked
  (testing ":theme :last-updated is a boolean; nil is false"
    (let [[cfg ws] (cfg-warnings {})]
      (is (= [] ws))
      (is (false? (config/last-updated? cfg)))
      (is (false? (get-in cfg [:theme :last-updated]))))
    (let [[cfg ws] (cfg-warnings {:theme {:last-updated true}})]
      (is (= [] ws))
      (is (true? (config/last-updated? cfg))))
    (doseq [bad ["yes" 1 :true]]
      (let [[cfg ws] (cfg-warnings {:theme {:last-updated bad}})]
        (is (= [(str ":theme :last-updated is " (pr-str bad) ", but it must be true or false; using false.")] ws))
        (is (false? (config/last-updated? cfg)))
        (is (false? (get-in cfg [:theme :last-updated])) "repaired in the config, not only in the accessor"))))
  (testing ":theme :update-bar: on by default; a non-map warns and is on"
    (let [[cfg ws] (cfg-warnings {})]
      (is (= [] ws))
      (is (= {:show-to-article true} (config/update-bar cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:update-bar false}})]
      (is (= [":theme :update-bar is false, but it must be a map {:show-to-article :more-article}; using {:show-to-article true}."] ws))
      (is (= {:show-to-article true} (config/update-bar cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:update-bar {:show-to-article false :more-article " /tags/ "}}})]
      (is (= [] ws))
      (is (= {:show-to-article false :more-article "/tags/"} (config/update-bar cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:update-bar {:more-article "https://example.org/all"}}})]
      (is (= [] ws))
      (is (= {:show-to-article true :more-article "https://example.org/all"} (config/update-bar cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:update-bar {:show-to-article "no"}}})]
      (is (= [":theme :update-bar :show-to-article is \"no\", but it must be true or false; using true."] ws))
      (is (= {:show-to-article true} (config/update-bar cfg))))
    (doseq [bad ["tags/" "//evil.example/" "ftp://x/" "http://x/" 7]]
      (let [[cfg ws] (cfg-warnings {:theme {:update-bar {:more-article bad}}})]
        (is (= [(str ":theme :update-bar :more-article is " (pr-str bad) ", but it must be a site path starting "
                     "with / or an https:// URL; it is ignored.")] ws))
        (is (= {:show-to-article true} (config/update-bar cfg)) (pr-str bad)))))
  (testing ":theme :repo is owner/name or an https:// URL; anything else warns and is nil"
    (let [[cfg ws] (cfg-warnings {:theme {:repo "EchoJustus/clogem-press"}})]
      (is (= [] ws))
      (is (= "https://github.com/EchoJustus/clogem-press" (config/repo-url cfg))))
    (let [[cfg ws] (cfg-warnings {:theme {:repo "https://bitbucket.org/o/n/"}})]
      (is (= [] ws))
      (is (= "https://bitbucket.org/o/n" (config/repo-url cfg))))
    (doseq [bad ["o" "git@github.com:o/n.git" "http://github.com/o/n" true]]
      (let [[cfg ws] (cfg-warnings {:theme {:repo bad}})]
        (is (= [(str ":theme :repo is " (pr-str bad) ", but it must be \"owner/name\" or an https:// URL; "
                     "there is no repository link.")] ws))
        (is (nil? (config/repo-url cfg)))
        (is (nil? (get-in cfg [:theme :repo])))))))

(deftest every-warning-names-vdoing-and-none-says-planned
  (let [[_ ws] (cfg-warnings {:content {:edit-link 1}
                              :theme {:last-updated 2 :update-bar 3 :repo 4}})]
    (is (= 4 (count ws)) (pr-str ws))
    (is (not-any? #(str/includes? % "planned") ws)))
  (testing "each key, set validly, is quiet"
    (let [[_ ws] (cfg-warnings {:content {:edit-link {:repo "o/n" :branch "main" :dir "docs"}}
                                :theme {:last-updated true :repo "o/n"
                                        :update-bar {:show-to-article true :more-article "/archives/"}}})]
      (is (= [] ws))))
  (testing "the hint names config.example.edn and vdoing's key"
    (let [[_ ds] (load-cfg {:theme {:last-updated "x"}})]
      (is (= ["See config.example.edn (vdoing's lastUpdated)."] (map :hint (diag/warnings ds)))))))

(deftest vdoing-spellings-of-the-e2-keys
  (testing "updateBar's own keys are walked under :theme :update-bar"
    (let [[_ ws] (cfg-warnings {:theme {:update-bar {:showToArticle false :moreArticle "/x/"}}})]
      (is (= [(str ":theme :update-bar :moreArticle is vdoing's spelling; clogem-press reads "
                   ":theme :update-bar :more-article. It is ignored.")
              (str ":theme :update-bar :showToArticle is vdoing's spelling; clogem-press reads "
                   ":theme :update-bar :show-to-article. It is ignored.")]
             ws))))
  (testing "a misspelt key under :content :edit-link is named"
    (let [[_ ws] (cfg-warnings {:content {:edit-link {:repo "o/n" :brnch "x"}}})]
      (is (= [":content :edit-link :brnch is not a content edit-link option and is ignored."] ws))))
  (testing "vdoing's top-level spellings point at the kebab keys, none \"planned\""
    (let [[_ ws] (cfg-warnings {:theme {:updateBar {} :lastUpdated true :repo "o/n" :editLinks true
                                        :docsRepo "o/n" :docsDir "d" :docsBranch "b" :editLinkText "Edit"}})]
      (is (= #{(str ":theme :docsBranch is vdoing's spelling; clogem-press reads :content :edit-link :branch. It is ignored.")
               (str ":theme :docsDir is vdoing's spelling; clogem-press reads :content :edit-link :dir. It is ignored.")
               (str ":theme :docsRepo is vdoing's spelling; clogem-press reads :content :edit-link :repo. It is ignored.")
               (str ":theme :editLinkText is not needed: clogem-press labels the edit link with the i18n string "
                    ":page/edit; it is ignored.")
               (str ":theme :editLinks is vdoing's spelling; clogem-press reads :content :edit-link "
                    "(a map {:repo :branch :dir}; absent is off). It is ignored.")
               (str ":theme :lastUpdated is vdoing's spelling; clogem-press reads :theme :last-updated. It is ignored.")
               (str ":theme :updateBar is vdoing's spelling; clogem-press reads :theme :update-bar. It is ignored.")}
             (set ws)))
      (is (not-any? #(str/includes? % "planned") ws)))))

;; ---------------------------------------------------------------------------
;; Item 2: the edit link

(deftest the-demo-links-each-variant-to-its-own-file
  (testing "the exact href of the zh-Hans-only article, its CJK path encoded"
    (let [page (html "pages" "cjk001" "index.html")]
      (is (str/includes? page
                         (str "<div class=\"clogem-page-edit\"><a class=\"clogem-page-edit__link\" "
                              "href=\"https://github.com/EchoJustus/clogem-press/edit/main/examples/demo-site/content/"
                              "04.%E4%B8%AD%E6%96%87%E7%AC%94%E8%AE%B0/01.first.zh-Hans.md\">")))
      (testing "after </article>, before prev/next and comments, in the same tab"
        (let [i-art  (str/index-of page "</article>")
              i-edit (str/index-of page "<div class=\"clogem-page-edit\">")]
          (is (< i-art i-edit))
          (is (< i-edit (str/index-of page "<section class=\"clogem-comments\">")))
          (when-let [i-pn (str/index-of page "<nav class=\"clogem-prev-next\">")]
            (is (< i-edit i-pn))))
        (is (not (str/includes? (page-edit-of page) "target=")))
        (is (not (str/includes? (page-edit-of page) "data-pagefind")))
        (is (str/includes? (page-edit-of page) "#pencil\"></use></svg> 编辑此页</a>")
            "the pencil icon, then the page language's :page/edit"))))
  (testing "each variant links its own file"
    (is (str/includes? (page-edit-of (html "pages" "643259" "index.html"))
                       "/edit/main/examples/demo-site/content/01.Guide/10.Basics/02.conventions.md\""))
    (is (str/includes? (page-edit-of (html "zh-Hans" "pages" "643259" "index.html"))
                       "/edit/main/examples/demo-site/content/01.Guide/10.Basics/02.conventions.zh-Hans.md\""))
    (is (str/includes? (page-edit-of (html "zh-Hans" "pages" "643259" "index.html")) "> 编辑此页</a>")))
  (testing "a catalogue page has one, before its prev/next links"
    (let [page (html "pages" "559f0f" "index.html")]
      (is (str/includes? (page-edit-of page) "/edit/main/examples/demo-site/content/00.Catalogue/01.Guide.md\""))
      (is (< (str/index-of page "</article>") (str/index-of page "<div class=\"clogem-page-edit\">")))))
  (testing "never on a home, an index or a tag page"
    (doseq [p [["index.html"] ["zh-Hans" "index.html"] ["archives" "index.html"] ["tags" "index.html"]
               ["tags" "meta" "index.html"] ["categories" "index.html"]]]
      (is (not (str/includes? (apply html p) "clogem-page-edit")) (str/join "/" p))))
  (testing "every article and catalogue page has exactly one"
    (doseq [p (fs/glob *out* "**/pages/*/index.html")
            :let [page (slurp (fs/file p))]]
      (is (= 1 (count (re-seq #"<div class=\"clogem-page-edit\">" page))) (str p)))))

(deftest edit-hrefs-follow-vdoing-s-patterns
  (let [e (fn [url] {:url url :branch "main"})]
    (is (= "https://github.com/o/n/edit/main/content/a%20b.md"
           (parity/edit-href (e "https://github.com/o/n") "content/a b.md")))
    (is (= "https://gitlab.com/o/n/-/edit/main/docs/content/x.md"
           (parity/edit-href (e "https://gitlab.com/o/n") "docs/content/x.md")))
    (is (= (str "https://bitbucket.org/o/n/src/main/content/x.md"
                "?mode=edit&spa=0&at=main&fileviewer=file-view-default")
           (parity/edit-href (e "https://bitbucket.org/o/n") "content/x.md")))
    (is (= "https://git.example/o/n/edit/main/content/x.md"
           (parity/edit-href (e "https://git.example/o/n") "content/x.md"))
        "any other host gets GitHub's form")
    (is (= "https://gitee.com/o/n/edit/main/content/x.md"
           (parity/edit-href (e "https://gitee.com/o/n") "content/x.md"))
        "Gitee too")
    (is (= "https://github.com/o/n/edit/feat/x%3Fy/content/%23.md"
           (parity/edit-href {:url "https://github.com/o/n" :branch "feat/x?y"} "content/#.md"))
        "the branch and the path are encoded per segment; nothing can become a query or a fragment")
    (is (= (str "https://bitbucket.org/o/n/src/a/b/c.md?mode=edit&spa=0&at=a%2Fb&fileviewer=file-view-default")
           (parity/edit-href {:url "https://bitbucket.org/o/n" :branch "a/b"} "c.md"))
        "the at= parameter is one encoded value")))

(deftest edit-link-front-matter-and-fallbacks
  (let [files {"01.A/01.one.md"   (article "One" "/pages/one001/")
               "01.A/02.two.md"   (article "Two" "/pages/two002/" {:editLink "false"})
               "01.A/03.three.md" (article "Three" "/pages/thr003/" {:editLink 1})
               "01.A/04.four.md"  (article "Four" "/pages/fou004/" {:editLink "true"})}]
    (testing "GitLab, with :branch and :dir"
      (let [{:keys [pages warnings]}
            (build-temp {:content {:edit-link {:repo "https://gitlab.com/g/site" :branch "trunk" :dir "/web/"}}} files)]
        (is (str/includes? (page-edit-of (pages "pages/one001/index.html"))
                           "href=\"https://gitlab.com/g/site/-/edit/trunk/web/content/01.A/01.one.md\""))
        (testing "editLink: false hides it; true changes nothing"
          (is (nil? (page-edit-of (pages "pages/two002/index.html"))))
          (is (str/includes? (page-edit-of (pages "pages/fou004/index.html")) "/04.four.md\"")))
        (testing "a non-boolean editLink is one warning naming the file, and is ignored"
          (is (= ["front matter editLink: 1 is not true or false; it is ignored."]
                 (filterv #(str/includes? % "editLink") warnings)))
          (is (str/includes? (page-edit-of (pages "pages/thr003/index.html")) "/03.three.md\"")))))
    (testing "Bitbucket, from :theme :repo"
      (let [{:keys [pages]} (build-temp {:theme {:repo "https://bitbucket.org/b/site"} :content {:edit-link {}}} files)]
        (is (str/includes? (page-edit-of (pages "pages/one001/index.html"))
                           (str "href=\"https://bitbucket.org/b/site/src/main/content/01.A/01.one.md"
                                "?mode=edit&amp;spa=0&amp;at=main&amp;fileviewer=file-view-default\"")))))
    (testing "off without :edit-link — and editLink: 1 still warns"
      (let [{:keys [pages warnings]} (build-temp {} files)]
        (is (not-any? #(str/includes? % "clogem-page-edit") (vals pages)))
        (is (= 1 (count (filter #(str/includes? % "editLink") warnings))))))))
