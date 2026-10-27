;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.parity-test
  "Phase 4 Task E2 (§11.3 item 16, D-P4-13, D-P4-16): article-page parity —
  the edit link, last-updated dates from git, the update bar under
  articles, `titleTag` in list rows, the navbar logo and repository link,
  front matter `pageClass` and the 404 page. What needs a browser is in
  test/browser/parity.test.mjs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]
            [clogem.gitlog :as gitlog]
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
                (let [cfg (config/load-config (str dir) nil {:content {:write-front-matter false}})
                      m   (cli/analyse cfg)]
                  ;; as `cli/build` analyses
                  (render/check-output-collisions! m)
                  (render/build! cfg m)))
        dist (fs/path dir "dist")]
    {:pages    (into (sorted-map)
                     (for [p (fs/glob dist "**.html")]
                       [(str/replace (str (fs/relativize dist p)) "\\" "/") (slurp (fs/file p))]))
     :warnings (mapv :message (diag/warnings ds))
     :diags    (diag/warnings ds)}))

(defn- build-or-error
  "`build-dir`, or {:thrown message} when the build raises, so a test
  asserts the result rather than relying on the exception."
  [dir]
  (try (build-dir dir)
       (catch clojure.lang.ExceptionInfo e {:thrown (ex-message e)})))

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
  (str "---\ntitle: " title "\npermalink: " permalink "\n"
       (apply str (for [[k v] (merge {:date "2026-01-01 00:00:00"} fm)] (str (name k) ": " v "\n")))
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
  (testing "the hint names config.example.edn and vdoing's key, every one of them"
    (let [[_ ds] (load-cfg {:theme {:last-updated "x"}})]
      (is (= ["See config.example.edn (vdoing's lastUpdated)."] (map :hint (diag/warnings ds)))))
    (let [[_ ds] (load-cfg {:content {:edit-link {}}})]
      (is (= ["See config.example.edn (vdoing's docsRepo)."] (map :hint (diag/warnings ds)))))
    (let [[_ ds] (load-cfg {:content {:edit-link 1}
                            :theme {:last-updated 2 :update-bar {:show-to-article 1 :more-article 2}
                                    :repo 4 :logo 5 :repo-label 6}})]
      (is (every? #(re-matches #"See config\.example\.edn \(vdoing's [A-Za-z.]+\)\." %) (map :hint (diag/warnings ds)))
          (pr-str (map :hint (diag/warnings ds)))))))

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

;; ---------------------------------------------------------------------------
;; Item 3: last updated, from git

(defn- git!
  "Run git in `dir` as CI can (no identity of its own, no signing), at
  `date` when it commits, never finding a repository above `dir`'s parent
  (GIT_CEILING_DIRECTORIES). Returns git's stdout. Dates the tests compare
  carry a non-zero offset: CI's git 2.55 printed `%aI` at +00:00 as `Z`
  where 2.43 prints `+00:00`."
  [dir date & args]
  (let [r @(p/process {:cmd (into ["git" "-c" "user.name=t" "-c" "user.email=t@example.invalid"
                                   "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main"]
                                  args)
                       :dir (str dir) :out :string :err :string
                       :extra-env {"GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date
                                   "GIT_CEILING_DIRECTORIES" (str (fs/parent dir))}})]
    (is (zero? (:exit r)) (str "git " (str/join " " args) ": " (:err r)))
    (:out r)))

(defn- ceiling-git
  "`gitlog/run-git` that never finds a repository above `dir`'s parent."
  [dir]
  (fn [d args] (gitlog/run-git d args {"GIT_CEILING_DIRECTORIES" (str (fs/parent dir))})))

(defn- dates-of
  "[dates warnings] for the site in `site-dir` with :theme :last-updated
  true, git confined below `ceiling`'s parent."
  [site-dir ceiling]
  (let [[cfg] (diag/collecting (config/load-config (str site-dir)))
        [ds ws] (binding [gitlog/*git* (ceiling-git ceiling)]
                  (diag/collecting (gitlog/dates cfg)))]
    [ds (mapv :message (diag/warnings ws))]))

(defn- temp-repo
  "A temp directory holding a git repository whose site is `sub` (\"\" for
  the root), with `:theme :last-updated true`. Returns [root site]."
  [sub]
  (let [root (fs/create-temp-dir {:prefix "clogem-e2-git"})
        site (if (str/blank? sub) root (fs/path root sub))]
    (fs/create-dirs (fs/path site "content"))
    (write-site! site {:theme {:last-updated true}} {})
    (git! root "2026-01-01T00:00:00+00:00" "init" "-q")
    [root site]))

(deftest git-dates-come-from-one-log-pass
  (let [[root site] (temp-repo "")]
    (try
      (spit (fs/file site "content" "a.md") "one")
      (fs/create-dirs (fs/path site "content" "04.中文"))
      (spit (fs/file site "content" "04.中文" "x y\"z.md") "cjk")
      (spit (fs/file site "content" "old.md") "to be renamed")
      (git! root "2026-01-02T03:04:05+08:00" "add" "-A")
      (git! root "2026-01-02T03:04:05+08:00" "commit" "-q" "-m" "one")
      (spit (fs/file site "content" "a.md") "two")
      (git! root "2026-03-04T23:30:00-05:00" "commit" "-q" "-a" "-m" "two")
      (git! root "2026-05-06T07:08:09+01:00" "mv" "content/old.md" "content/new.md")
      (git! root "2026-05-06T07:08:09+01:00" "commit" "-q" "-m" "rename")
      (spit (fs/file site "content" "untracked.md") "never added")
      (let [[ds ws] (dates-of site root)]
        (is (= [] ws))
        (testing "two commits: the newer date, as the author wrote it"
          (is (= "2026-03-04T23:30:00-05:00" (ds "content/a.md"))))
        (testing "a CJK name with a space and a double quote, raw (-z)"
          (is (= "2026-01-02T03:04:05+08:00" (ds "content/04.中文/x y\"z.md"))))
        (testing "a renamed file: the rename's date, under its new name only"
          (is (= "2026-05-06T07:08:09+01:00" (ds "content/new.md")))
          (is (= "2026-01-02T03:04:05+08:00" (ds "content/old.md"))
              "the rename commit does not list the old name; only the commit that added it does"))
        (testing "an untracked file has no date"
          (is (not (contains? ds "content/untracked.md"))))
        (is (= #{"content/a.md" "content/04.中文/x y\"z.md" "content/old.md" "content/new.md"} (set (keys ds)))
            "site.edn is outside the content directory"))
      (finally (fs/delete-tree root)))))

(deftest git-dates-for-a-site-in-a-subdirectory
  (let [[root site] (temp-repo "docs/site")]
    (try
      (spit (fs/file site "content" "a.md") "one")
      (spit (fs/file root "README.md") "outside the site")
      (git! root "2026-02-03T04:05:06+01:00" "add" "-A")
      (git! root "2026-02-03T04:05:06+01:00" "commit" "-q" "-m" "one")
      (let [[ds ws] (dates-of site root)]
        (is (= [] ws))
        (is (= {"content/a.md" "2026-02-03T04:05:06+01:00"} ds) "relative to the site, not the repository"))
      (finally (fs/delete-tree root)))))

(deftest outside-git-shallow-and-failing-git
  (testing "not a repository: no dates, no warning"
    (let [dir (fs/create-temp-dir {:prefix "clogem-e2-nogit"})]
      (try
        (write-site! dir {:theme {:last-updated true}} {"a.md" "x"})
        (let [[ds ws] (dates-of dir dir)]
          (is (nil? ds))
          (is (= [] ws)))
        (finally (fs/delete-tree dir)))))
  (testing "git runs under LC_ALL=C, so a translated git's \"not a git repository\" is recognized"
    (let [seen (atom nil)]
      (with-redefs [p/process (fn [opts] (reset! seen (:extra-env opts)) (delay {:exit 0 :out "false\n" :err ""}))]
        (gitlog/run-git "." ["rev-parse"] {"X" "1"}))
      (is (= {"LC_ALL" "C" "X" "1"} @seen))))
  (testing "git cannot be started: no dates, no warning"
    (let [[cfg] (diag/collecting (config/load-config demo nil {:theme {:last-updated true}}))
          [ds ws] (binding [gitlog/*git* (fn [_ _] nil)] (diag/collecting (gitlog/dates cfg)))]
      (is (nil? ds))
      (is (= [] ws))))
  (testing "a shallow clone: one warning, no dates"
    (let [[root site] (temp-repo "")
          clone (fs/path (fs/parent root) (str (fs/file-name root) "-shallow"))]
      (try
        (spit (fs/file site "content" "a.md") "one")
        (git! root "2026-01-02T00:00:00+00:00" "add" "-A")
        (git! root "2026-01-02T00:00:00+00:00" "commit" "-q" "-m" "one")
        (spit (fs/file site "content" "a.md") "two")
        (git! root "2026-01-03T00:00:00+00:00" "commit" "-q" "-a" "-m" "two")
        (git! (fs/parent root) "2026-01-03T00:00:00+00:00" "clone" "-q" "--depth" "1"
              (str "file://" root) (str clone))
        (let [[ds ws] (dates-of clone clone)]
          (is (nil? ds))
          (is (= [(str ":theme :last-updated: the site is in a shallow clone, which dates every file to its "
                       "newest commit; no last-updated dates are shown.")] ws)))
        (finally (fs/delete-tree root) (fs/delete-tree clone)))))
  (testing "any other failure: one warning quoting git's first stderr line, no dates"
    (let [[cfg] (diag/collecting (config/load-config demo nil {:theme {:last-updated true}}))
          fake  (fn [_ args]
                  (if (= "rev-parse" (first args))
                    {:exit 0 :out "false\n" :err ""}
                    {:exit 128 :out ""
                     :err "fatal: detected dubious ownership in repository at '/x'\nTo add an exception …\n"}))
          [ds ws] (binding [gitlog/*git* fake] (diag/collecting (gitlog/dates cfg)))]
      (is (nil? ds))
      (is (= [(str ":theme :last-updated: git failed (exit 128): fatal: detected dubious ownership in "
                   "repository at '/x'; no last-updated dates are shown.")]
             (mapv :message (diag/warnings ws)))))
    (let [[cfg] (diag/collecting (config/load-config demo nil {:theme {:last-updated true}}))
          [ds ws] (binding [gitlog/*git* (fn [_ _] {:exit 1 :out "" :err ""})] (diag/collecting (gitlog/dates cfg)))]
      (is (nil? ds))
      (is (= 1 (count (diag/warnings ws))))))
  (testing "with the key off, git never runs — in a build or in doctor"
    (let [calls (atom 0)]
      (binding [gitlog/*git* (fn [& _] (swap! calls inc) nil)]
        (let [[cfg] (diag/collecting (config/load-config demo))]
          (is (nil? (gitlog/dates cfg))))
        (build-temp {} {"01.A/01.a.md" (article "A" "/pages/aaaaaa/")}))
      (is (= 0 @calls)))
    (testing "doctor does not run git even with it on"
      (let [calls (atom 0)
            dir   (fs/create-temp-dir {:prefix "clogem-e2-doctor"})]
        (try
          (write-site! dir {:theme {:last-updated true}} {"01.A/01.a.md" (article "A" "/pages/aaaaaa/")})
          (binding [gitlog/*git* (fn [& _] (swap! calls inc) nil)
                    *out* (java.io.StringWriter.)]
            (diag/collecting (try (cli/doctor {:site-dir (str dir)}) (catch Exception _ nil))))
          (is (= 0 @calls))
          (finally (fs/delete-tree dir)))))))

(deftest the-date-renders-beside-the-edit-link
  (let [[root site] (temp-repo "")]
    (try
      (write-site! site {:theme {:last-updated true} :content {:edit-link {:repo "o/n"}}}
                   {"01.A/01.a.md" (article "A" "/pages/aaaaaa/")
                    "01.A/02.b.md" (article "B" "/pages/bbbbbb/" {:editLink "false"})
                    "01.A/03.c.md" (article "C" "/pages/cccccc/")})
      (git! root "2026-07-08T23:59:59+08:00" "add" "content/01.A/01.a.md" "content/01.A/02.b.md")
      (git! root "2026-07-08T23:59:59+08:00" "commit" "-q" "-m" "a")
      (let [{:keys [pages warnings]} (binding [gitlog/*git* (ceiling-git root)] (build-dir site))]
        (is (= [] (filterv #(str/includes? % "git") warnings)))
        (testing "the author's calendar date, inside the localized sentence"
          (is (= (str "<div class=\"clogem-page-edit\"><a class=\"clogem-page-edit__link\" "
                      "href=\"https://github.com/o/n/edit/main/content/01.A/01.a.md\">"
                      "<svg aria-hidden=\"true\" class=\"clogem-icon\" focusable=\"false\" height=\"1em\" width=\"1em\">"
                      "<use href=\"/clogem/icons.svg?v=" (second (re-find #"icons\.svg\?v=([^#]+)#" (pages "pages/aaaaaa/index.html")))
                      "#pencil\"></use></svg> Edit this page</a>"
                      "<span class=\"clogem-page-edit__updated\">"
                      "<svg aria-hidden=\"true\" class=\"clogem-icon\" focusable=\"false\" height=\"1em\" width=\"1em\">"
                      "<use href=\"/clogem/icons.svg?v=" (second (re-find #"icons\.svg\?v=([^#]+)#" (pages "pages/aaaaaa/index.html")))
                      "#clock\"></use></svg> <time datetime=\"2026-07-08T23:59:59+08:00\">Last updated 2026-07-08</time>"
                      "</span></div>")
                 (page-edit-of (pages "pages/aaaaaa/index.html")))))
        (testing "the date alone when the link is hidden"
          (is (re-find #"<div class=\"clogem-page-edit\"><span class=\"clogem-page-edit__updated\">.*Last updated 2026-07-08</time></span></div>"
                       (str (page-edit-of (pages "pages/bbbbbb/index.html"))))))
        (testing "an uncommitted file has no date"
          (is (not (str/includes? (page-edit-of (pages "pages/cccccc/index.html")) "Last updated")))))
      (finally (fs/delete-tree root)))))

;; P4-E2.1 item 2: on POSIX a backslash is a file name's own character, not
;; a separator
(deftest a-backslash-in-a-posix-file-name-is-kept
  (when (= "/" java.io.File/separator)
    (let [[root site] (temp-repo "")]
      (try
        (write-site! site {:theme {:last-updated true} :content {:edit-link {:repo "o/n"}}}
                     {"01.G/02.back\\slash.md" (article "B" "/pages/b5b5b5/")})
        (git! root "2026-07-08T23:59:59+08:00" "add" "-A")
        (git! root "2026-07-08T23:59:59+08:00" "commit" "-q" "-m" "b")
        (let [{:keys [pages]} (binding [gitlog/*git* (ceiling-git root)] (build-dir site))
              edit (page-edit-of (pages "pages/b5b5b5/index.html"))]
          (is (str/includes? edit "href=\"https://github.com/o/n/edit/main/content/01.G/02.back%5Cslash.md\"") edit)
          (is (str/includes? edit "<time datetime=\"2026-07-08T23:59:59+08:00\">") edit))
        (finally (fs/delete-tree root))))))

;; ---------------------------------------------------------------------------
;; Item 4: the update bar and moreArticle

(defn- update-bar-of
  "The `<section class=\"clogem-update-bar\">` of `page`, or nil."
  [page]
  (re-find #"(?s)<section class=\"clogem-update-bar\">.*?</section>" (str page)))

(defn- row-hrefs [s] (mapv second (re-seq #"<li class=\"clogem-row\"><a href=\"([^\"]+)\"" (str s))))

(deftest the-update-bar-lists-three-other-articles
  (testing "the 3 newest articles but this one, newest first, then More → this language's archives"
    (let [bar (update-bar-of (html "zh-Hans" "pages" "643259" "index.html"))]
      (is (= ["/pages/cjk001/" "/pages/ca4d51/" "/zh-Hans/pages/c0nta1/"] (row-hrefs bar)))
      (is (str/starts-with? bar "<section class=\"clogem-update-bar\"><h2>最近更新</h2><ul class=\"clogem-list\">"))
      (is (str/ends-with? bar "</ul><p><a href=\"/zh-Hans/archives/\">更多 →</a></p></section>"))
      (testing "an untranslated row carries the fallback badge"
        (is (re-find #"<a href=\"/pages/ca4d51/\" lang=\"en\">Card lists</a><span class=\"clogem-fallback\"" bar)))
      (testing "after the comments, inside <main>"
        (let [page (html "zh-Hans" "pages" "643259" "index.html")]
          (is (< (str/index-of page "<section class=\"clogem-comments\">")
                 (str/index-of page "<section class=\"clogem-update-bar\">")
                 (str/index-of page "</main>")))))))
  (testing "never the current page: the newest article's own bar starts at the second"
    (let [bar (update-bar-of (html "pages" "cjk001" "index.html"))]
      (is (= 3 (count (row-hrefs bar))))
      (is (not (some #{"/pages/cjk001/"} (row-hrefs bar))))
      (is (= "/pages/ca4d51/" (first (row-hrefs bar))))))
  (testing "every article page has exactly one, of 3 rows, none its own"
    (doseq [p (fs/glob *out* "**/pages/*/index.html")
            :let [page (slurp (fs/file p))
                  rel  (str/replace (str (fs/relativize *out* p)) "\\" "/")]
            :when (str/includes? page "<article class=\"clogem-article\"")]
      (is (= 1 (count (re-seq #"<section class=\"clogem-update-bar\">" page))) rel)
      (is (= 3 (count (row-hrefs (update-bar-of page)))) rel)
      (is (not (some #(= (str "/" (str/replace rel #"index\.html$" "")) %) (row-hrefs (update-bar-of page)))) rel)))
  (testing "none on a catalogue, a home or an index page"
    (doseq [p [["pages" "559f0f" "index.html"] ["pages" "c4d33p" "index.html"] ["index.html"]
               ["zh-Hans" "index.html"] ["archives" "index.html"] ["tags" "meta" "index.html"]]]
      (is (not (str/includes? (apply html p) "clogem-update-bar")) (str/join "/" p)))))

(def ^:private three-articles
  {"01.A/01.one.md"   (article "One" "/pages/one001/" {:date "2026-01-01"})
   "01.A/01.one.zh-Hans.md" (article "一" "/pages/one001/" {:date "2026-01-01"})
   "01.A/02.two.md"   (article "Two" "/pages/two002/" {:date "2026-01-02"})
   "01.A/03.off.md"   (article "Off" "/pages/off003/" {:article "false"})
   "01.A/04.four.md"  (article "Four" "/pages/fou004/" {:date "2026-01-04"})})

(deftest the-home-s-more-follows-more-article
  (testing "the simple list gets vdoing's More, to the archives, and a simple home's right bar has no update bar"
    (let [zh (html "zh-Hans" "index.html")
          simple (second (re-find #"(?s)clogem-list--simple\">(.*?</ul>(?:<p>.*?</p>)?)" zh))]
      (is (str/ends-with? simple "</ul><p><a href=\"/zh-Hans/archives/\">更多 →</a></p>"))
      (is (= 3 (count (row-hrefs (first (str/split simple #"</ul>" 2))))) "simplePostListLength: 3 still")
      ;; merged with Task E1a: under `postList: simple` the right bar has no
      ;; update bar (vdoing's Home.vue) — the simple list is the recent list
      (is (not (str/includes? zh "<section class=\"clogem-updates\">"))
          "no right-bar update bar under postList: simple (P4-E1a)")))
  (testing "a detailed home's right bar: More → the archives, else :more-article"
    (let [home "---\nhome: true\npostList: detailed\n---\n\n# Home\n"
          bar  (fn [edn]
                 (re-find #"(?s)<section class=\"clogem-updates\">.*?</section>"
                          ((:pages (build-temp edn (assoc three-articles "index.md" home))) "index.html")))]
      (is (str/ends-with? (bar {}) "<p><a href=\"/archives/\">More →</a></p></section>"))
      (is (= ["/pages/fou004/" "/pages/two002/" "/pages/one001/"] (row-hrefs (bar {}))))
      (is (str/ends-with? (bar {:theme {:update-bar {:more-article "/tags/"}}})
                          "<p><a href=\"/tags/\">More →</a></p></section>")))))

(deftest update-bar-options
  (testing ":more-article \"/tags/\" goes to the page language's tags; an article: false page has no bar"
    (let [{:keys [pages warnings]} (build-temp {:langs {:priority [:en :zh-Hans]
                                                        :locales {:zh-Hant nil :ms nil :ta nil}}
                                                :theme {:update-bar {:more-article "/tags/"}}}
                                               three-articles)]
      (is (= [] (filterv #(re-find #"update-bar|more-article" %) warnings)))
      (is (str/ends-with? (update-bar-of (pages "zh-Hans/pages/one001/index.html"))
                          "<p><a href=\"/zh-Hans/tags/\">更多 →</a></p></section>"))
      (is (str/ends-with? (update-bar-of (pages "pages/one001/index.html"))
                          "<p><a href=\"/tags/\">More →</a></p></section>"))
      (is (= ["/pages/fou004/" "/pages/two002/"] (row-hrefs (update-bar-of (pages "pages/one001/index.html"))))
          "an article: false page is never a row either")
      (is (nil? (update-bar-of (pages "pages/off003/index.html"))))))
  (testing "an https:// :more-article is left alone"
    (let [{:keys [pages]} (build-temp {:theme {:update-bar {:more-article "https://example.org/all"}}} three-articles)]
      (is (str/includes? (update-bar-of (pages "pages/one001/index.html")) "<a href=\"https://example.org/all\">"))))
  (testing ":show-to-article false: no bar"
    (let [{:keys [pages]} (build-temp {:theme {:update-bar {:show-to-article false}}} three-articles)]
      (is (not-any? #(str/includes? % "clogem-update-bar") (vals pages)))))
  (testing "archives off and no :more-article: no More, on the bar or the home"
    (let [{:keys [pages]} (build-temp {:content {:archive false}} (assoc three-articles "index.md" "---\npostList: simple\n---\n"))]
      (is (= 2 (count (row-hrefs (update-bar-of (pages "pages/one001/index.html"))))))
      (is (not (str/includes? (update-bar-of (pages "pages/one001/index.html")) "<p>")))
      (is (not (str/includes? (pages "index.html") "More →")))))
  (testing "a one-article site has no bar"
    (let [{:keys [pages]} (build-temp {} {"01.A/01.one.md" (article "One" "/pages/one001/")})]
      (is (nil? (update-bar-of (pages "pages/one001/index.html")))))))

;; ---------------------------------------------------------------------------
;; Item 5: titleTag in list rows

(deftest title-tags-show-in-every-row
  (let [tagged (str "<a href=\"/pages/y2025a/\" lang=\"en\">A post from the year before</a>"
                    "<span class=\"clogem-title-tag\" data-pagefind-ignore=\"\">原创</span>")]
    (testing "right after the link, on index pages"
      (doseq [p [["archives" "index.html"] ["tags" "meta" "index.html"]]]
        (is (str/includes? (apply html p) (str "<li class=\"clogem-row\">" tagged "<time ")) (str/join "/" p))))
    (testing "and before the fallback badge, in the zh-Hans simple list"
      (let [simple (second (re-find #"(?s)clogem-list--simple\">(.*?)</ul>" (html "zh-Hans" "index.html")))]
        (is (str/includes? simple (str "<li class=\"clogem-row\">" tagged "<span class=\"clogem-fallback\"")))))
    (testing "every row of the demo with a tag carries it exactly so"
      (doseq [p (fs/glob *out* "**.html")
              :let [page (slurp (fs/file p))]
              row (re-seq #"<li class=\"clogem-row\">.*?</li>" page)
              :when (str/includes? row "/pages/y2025a/\"")]
        (is (str/includes? row tagged) (str p)))))
  (testing "under :search :none the badge has no attribute"
    (let [{:keys [pages]} (build-temp {} {"01.A/01.one.md" (article "One" "/pages/one001/" {:titleTag "转载"})
                                          "01.A/02.two.md" (article "Two" "/pages/two002/")})]
      (is (str/includes? (pages "archives/index.html")
                         "<a href=\"/pages/one001/\" lang=\"en\">One</a><span class=\"clogem-title-tag\">转载</span><time "))
      (is (str/includes? (update-bar-of (pages "pages/two002/index.html"))
                         "One</a><span class=\"clogem-title-tag\">转载</span>")
          "in the update bar too")))
  (testing "the date is pushed to the row's end, the badges kept by the title"
    (is (re-find #"\.clogem-row time \{[^}]*margin-inline-start: auto;" (slurp theme-css)))))

;; ---------------------------------------------------------------------------
;; Item 6: pageClass

(defn- body-class [page] (second (re-find #"<body class=\"([^\"]*)\">" (str page))))

(deftest page-class-goes-on-body-after-page-kind
  (let [{:keys [pages warnings diags]}
        (build-temp {:langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}}
                    {"index.md"            "---\npageClass: home-x\n---\n"
                     "@pages/archivesPage.md" "---\ntitle: Archives\npageClass: arch-x\n---\n"
                     "@pages/tagsPage.md"  "---\ntitle: Tags\npageClass: tags-x\n---\n"
                     "01.A/01.one.md"      (article "One" "/pages/one001/" {:pageClass "wide  _my-page" :tags "[t]"})
                     "01.A/01.one.zh-Hans.md" (article "一" "/pages/one001/" {:pageClass "[zh-a, zh-b]"})
                     "01.A/02.two.md"      (article "Two" "/pages/two002/" {:pageClass "ok a{b 9bad"})
                     "01.A/03.three.md"    (article "Three" "/pages/thr003/")
                     "01.A/04.four.md"     (article "Four" "/pages/fou004/" {:pageClass "5"})
                     "00.Cat/01.cat.md"    (str "---\ntitle: Cat\npermalink: /pages/cat001/\ndate: 2026-01-01\n"
                                                "pageClass: cat-x\npageComponent:\n  name: Catalogue\n  data:\n"
                                                "    path: 01.A\n---\n")})]
    (testing "a token on each kind of page, after lang- and page-<kind>"
      (is (= "lang-en page-article wide _my-page" (body-class (pages "pages/one001/index.html"))))
      (is (= "lang-zh-Hans page-article zh-a zh-b" (body-class (pages "zh-Hans/pages/one001/index.html")))
          "a list, from the variant's own front matter")
      (is (= "lang-en page-catalogue cat-x" (body-class (pages "pages/cat001/index.html"))))
      (is (= "lang-en page-home home-x" (body-class (pages "index.html"))))
      (is (= "lang-zh-Hans page-home home-x" (body-class (pages "zh-Hans/index.html")))
          "a language without its own index file reads the site default's, as the list options")
      (is (= "lang-en page-archives arch-x" (body-class (pages "archives/index.html"))))
      (is (= "lang-en page-tags tags-x" (body-class (pages "tags/index.html"))))
      (is (= "lang-en page-tags tags-x" (body-class (pages "tags/t/index.html"))) "a tag's own page too"))
    (testing "a bad token is dropped with one warning naming the file and the token"
      (is (= "lang-en page-article ok" (body-class (pages "pages/two002/index.html"))))
      (is (= #{["front matter pageClass: \"a{b\" is not a class name (a letter or _, then letters, digits, - or _); it is dropped."]
               ["front matter pageClass: \"9bad\" is not a class name (a letter or _, then letters, digits, - or _); it is dropped."]}
             (set (map vector (filter #(and (str/includes? % "pageClass") (str/includes? % "dropped")) warnings)))))
      (is (= 2 (count (filter #(str/includes? % "dropped") warnings))) "once each, not once per page")
      (is (= #{"01.A/02.two.md"} (set (keep #(when (str/includes? (:message %) "dropped") (:path %)) diags)))))
    (testing "a value that is not a string: one warning, ignored"
      (is (= "lang-en page-article" (body-class (pages "pages/fou004/index.html"))))
      (is (= ["front matter pageClass: 5 is not a string or a list of strings; it is ignored."]
             (filterv #(str/includes? % "ignored.") (filterv #(str/includes? % "pageClass") warnings)))))
    (testing "no pageClass: the body is as before"
      (is (= "lang-en page-article" (body-class (pages "pages/thr003/index.html"))))
      (is (= "lang-en page-categories" (body-class (pages "categories/index.html"))))))
  (testing "the demo, which sets none, is unchanged"
    (is (= "lang-zh-Hans page-article" (body-class (html "pages" "cjk001" "index.html"))))
    (is (= "lang-zh-Hans page-home" (body-class (html "zh-Hans" "index.html"))))))

(deftest a-site-file-s-page-class-warns-once
  (let [{:keys [pages diags]}
        (build-temp {:theme {:per-page 1}}
                    {"index.md"              "---\npageClass: \"h a{b\"\n---\n"
                     "@pages/categoriesPage.md" "---\ntitle: C\npageClass: \"c x}y\"\n---\n"
                     "01.A/01.one.md"        (article "One" "/pages/one001/")
                     "01.A/02.two.md"        (article "Two" "/pages/two002/")})
        bad (filter #(str/includes? (:message %) "pageClass") diags)]
    (is (= "lang-en page-home h" (body-class (pages "page/2/index.html"))) "every page of a paginated home")
    (is (= "lang-en page-categories c" (body-class (pages "categories/page/2/index.html"))))
    (is (= #{["index.md" "front matter pageClass: \"a{b\" is not a class name (a letter or _, then letters, digits, - or _); it is dropped."]
             ["@pages/categoriesPage.md" "front matter pageClass: \"x}y\" is not a class name (a letter or _, then letters, digits, - or _); it is dropped."]}
           (set (map (juxt :path :message) bad))))
    (is (= 2 (count bad)) "once per file, from its first page")))

;; ---------------------------------------------------------------------------
;; Item 7: the navbar logo and the repository link

(defn- navbar-of [page] (re-find #"(?s)<header class=\"clogem-navbar\">.*?</header>" (str page)))

(deftest logo-and-repo-label-are-checked
  (doseq [ok ["/assets/logo.svg" "https://cdn.example/logo.png" "/assets/my logo.png"]]
    (let [[cfg ws] (cfg-warnings {:theme {:logo ok}})]
      (is (= [] ws))
      (is (= ok (get-in cfg [:theme :logo])))))
  (doseq [bad ["assets/logo.svg" "//cdn.example/x.png" "http://x/y.png" " /a.png" 3]]
    (let [[cfg ws] (cfg-warnings {:theme {:logo bad}})]
      (is (= [(str ":theme :logo is " (pr-str bad) ", but it must be a site path starting with / (such as "
                   "\"/assets/logo.svg\") or an https:// URL; there is no logo.")] ws))
      (is (nil? (get-in cfg [:theme :logo])))))
  (doseq [ok ["Code" {:en "Code" :zh-Hans "代码"}]]
    (let [[cfg ws] (cfg-warnings {:theme {:repo-label ok}})]
      (is (= [] ws) "a per-language map is user data, not walked for keys")
      (is (= ok (get-in cfg [:theme :repo-label])))))
  (doseq [bad ["  " {} {:en ""} :code]]
    (let [[cfg ws] (cfg-warnings {:theme {:repo-label bad}})]
      (is (= [(str ":theme :repo-label is " (pr-str bad) ", but it must be a non-blank string or a map of them "
                   "by language; the label follows the host.")] ws))
      (is (nil? (get-in cfg [:theme :repo-label])))))
  (let [[_ ws] (cfg-warnings {:theme {:repoLabel "x"}})]
    (is (= [":theme :repoLabel is vdoing's spelling; clogem-press reads :theme :repo-label. It is ignored."] ws))))

(deftest the-demo-navbar-has-a-logo-and-a-repo-link
  (let [nb (navbar-of (html "zh-Hans" "index.html"))]
    (is (str/includes? nb (str "<a class=\"clogem-navbar__brand has-logo\" href=\"/zh-Hans/\">"
                               "<img alt=\"\" class=\"clogem-navbar__logo\" src=\"/assets/logo.svg\" />"
                               "<span class=\"clogem-navbar__title\">clogem-press 演示</span></a>")))
    (testing "the repo link is the nav's last item, labelled by its host"
      (is (re-find #"<li class=\"clogem-navbar__item\"><a class=\"clogem-navbar__repo\" href=\"https://github.com/EchoJustus/clogem-press\"><span class=\"clogem-navbar__repo-label\">GitHub</span> <svg [^>]*><use href=\"[^\"]*#external-link\"></use></svg></a></li></ul></nav><nav aria-label=\"[^\"]+\" class=\"clogem-langs\">"
                   nb))
      (is (not (re-find #"clogem-navbar__repo\"[^>]*target" nb)) "the same tab")))
  (testing "on every page with a navbar"
    (doseq [p (fs/glob *out* "**.html")
            :let [page (slurp (fs/file p))]
            :when (str/includes? page "<header class=\"clogem-navbar\">")]
      (is (= 1 (count (re-seq #"class=\"clogem-navbar__repo\"" page))) (str p))
      (is (= 1 (count (re-seq #"class=\"clogem-navbar__logo\"" page))) (str p))))
  (testing "the logo is a real image the build writes"
    (is (fs/regular-file? (fs/path *out* "assets" "logo.svg")))
    (is (str/starts-with? (slurp (fs/file *out* "assets" "logo.svg")) "<svg"))))

(deftest navbar-variants
  (let [one-lang {:langs {:priority [:en] :locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}}
        files    {"01.A/01.one.md" (article "One" "/pages/one001/")}
        brand-re #"<a class=\"clogem-navbar__brand[^\"]*\" href=\"[^\"]*\">.*?</a>"]
    (testing "no :logo: the brand is as before"
      (let [{:keys [pages]} (build-temp one-lang files)]
        (is (= "<a class=\"clogem-navbar__brand\" href=\"/\">S</a>" (re-find brand-re (pages "index.html"))))
        (testing "and a one-language site with no :nav and no :repo has no menu toggle off a sidebar page"
          (is (not (str/includes? (pages "index.html") "clogem-navbar__toggle"))))))
    (testing "only :repo: the drawer and a shown toggle on a one-language site with no :nav"
      (let [{:keys [pages]} (build-temp (assoc one-lang :theme {:repo "https://gitlab.com/g/s"}) files)
            nb (navbar-of (pages "index.html"))]
        (is (str/includes? nb "<details class=\"clogem-navbar__toggle\"><summary"))
        (is (not (str/includes? nb "clogem-navbar__toggle\" hidden")))
        (is (str/includes? nb (str "<div class=\"clogem-navbar__drawer\" id=\"clogem-drawer\"><nav class=\"clogem-navbar__nav\">"
                                   "<ul><li class=\"clogem-navbar__item\"><a class=\"clogem-navbar__repo\" href=\"https://gitlab.com/g/s\">"
                                   "<span class=\"clogem-navbar__repo-label\">GitLab</span> ")))))
    (testing "labels: Bitbucket by host, Source otherwise (in the page's language), :repo-label first"
      (let [label #(second (re-find #"clogem-navbar__repo-label\">([^<]*)<" (str %)))]
        (is (= "Bitbucket" (label ((:pages (build-temp (assoc one-lang :theme {:repo "https://bitbucket.org/b/s"}) files)) "index.html"))))
        (let [{:keys [pages]} (build-temp {:langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}
                                           :theme {:repo "https://git.example/o/n"}} files)]
          (is (= "Source" (label (pages "index.html"))))
          (is (= "源代码" (label (pages "zh-Hans/index.html")))))
        (let [{:keys [pages]} (build-temp {:langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}
                                           :theme {:repo "o/n" :repo-label {:en "Code" :zh-Hans "代码"}}} files)]
          (is (= "Code" (label (pages "index.html"))))
          (is (= "代码" (label (pages "zh-Hans/index.html")))))))
    (testing "a logo under --base gets the base; an https:// logo is left alone and not checked"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
        (try
          (write-site! dir (assoc one-lang :site {:title "S" :base "/proj/"} :theme {:logo "/assets/l.svg"})
                       files)
          (fs/create-dirs (fs/path dir "assets"))
          (spit (fs/file dir "assets" "l.svg") "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
          (let [{:keys [pages warnings]} (build-dir dir)]
            (is (str/includes? (pages "index.html") "<img alt=\"\" class=\"clogem-navbar__logo\" src=\"/proj/assets/l.svg\" />"))
            (is (not-any? #(str/includes? % ":logo") warnings)))
          (finally (fs/delete-tree dir))))
      (let [{:keys [pages warnings]} (build-temp (assoc one-lang :theme {:logo "https://cdn.example/l.png"}) files)]
        (is (str/includes? (pages "index.html") "src=\"https://cdn.example/l.png\""))
        (is (not-any? #(str/includes? % ":logo") warnings))))
    (testing "a site-path logo the build does not write: one warning, still linked — in build and doctor"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
        (try
          (write-site! dir (assoc one-lang :theme {:logo "/assets/missing.svg"}) files)
          (let [{:keys [pages warnings]} (build-dir dir)]
            (is (str/includes? (pages "index.html") "src=\"/assets/missing.svg\""))
            (is (= [(str ":theme :logo \"/assets/missing.svg\" names no file the build writes; "
                         "the navbar links it all the same.")]
                   (filterv #(str/includes? % ":logo") warnings))))
          (let [r (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                    (cli/doctor {:site-dir (str dir)}))]
            (is (= 1 (count (filter #(str/includes? (:message %) ":logo") (:warnings r))))))
          (finally (fs/delete-tree dir)))))))

;; ---------------------------------------------------------------------------
;; Item 8: the 404 page

(deftest the-demo-writes-one-404-page
  (let [page (html "404.html")]
    (testing "at the output root, in the default language, the layout's shell"
      (is (str/starts-with? page "<!DOCTYPE html>\n<html "))
      (is (re-find #"<html [^>]*lang=\"en\"" page))
      (is (str/includes? page "<title>Page not found · clogem-press demo</title>"))
      (is (str/includes? page "<body class=\"lang-en page-not-found\"><pagefind-config ")
          "page-not-found after lang-, and <pagefind-config> first in <body>")
      (is (str/includes? page "<header class=\"clogem-navbar\">"))
      (is (str/includes? page "<main class=\"clogem-main\"><div class=\"clogem-content\"><h1>404</h1>"))
      (is (str/includes? page "<footer class=\"clogem-footer\">")))
    (testing "every language's home, in priority order, each in its own words"
      (is (str/includes? page (str "<ul><li lang=\"en\"><a href=\"/\">Go to the home page</a></li>"
                                   "<li lang=\"zh-Hans\"><a href=\"/zh-Hans/\">返回首页</a></li>"
                                   "<li lang=\"zh-Hant\"><a href=\"/zh-Hant/\">返回首頁</a></li>"
                                   "<li lang=\"ms\"><a href=\"/ms/\">Pergi ke laman utama</a></li>"
                                   "<li lang=\"ta\"><a href=\"/ta/\">முகப்புப் பக்கத்துக்குச் செல்</a></li></ul>"))))
    (testing "noindex, and none of the tags a page with :seo gets"
      (is (str/includes? page "<meta content=\"noindex\" name=\"robots\" />"))
      (is (< (str/index-of page "name=\"robots\"") (str/index-of page "<link href=\"/clogem/css/theme.css")))
      (is (not (re-find #"rel=\"canonical\"|hreflang=\"x-default\"|rel=\"alternate\"|og:locale|clogem-lang-data" page)))
      (is (not (str/includes? page "data-pagefind-body")))
      (is (not (str/includes? page "<script>(function(){try{var p=localStorage")) "no redirect script")
      (is (= 1 (count (re-seq #"<script>" page))) "one inline script, the mode script"))
    (testing "not in the sitemap; nothing else named 404"
      (is (not (str/includes? (slurp (fs/file *out* "sitemap.xml")) "404")))
      (is (= ["404.html"] (mapv #(str (fs/relativize *out* %)) (fs/glob *out* "**404*")))))
    (testing "the switcher sends each language to its home"
      (is (str/includes? page "<a data-clogem-lang=\"zh-Hans\" href=\"/zh-Hans/\" hreflang=\"zh-Hans\" lang=\"zh-Hans\">")))
    (testing "the default language's page is the only noindex page but the stubs"
      (doseq [p (fs/glob *out* "**.html")
              :let [h (slurp (fs/file p))]
              :when (str/includes? h "name=\"robots\"")]
        (is (or (= "404.html" (str (fs/relativize *out* p))) (str/includes? h "http-equiv=\"refresh\"")) (str p))))))

(deftest the-404-page-under-a-base-a-static-override-and-the-sweep
  (let [site {:site {:title "S" :url "https://s.example" :base "/clogem-demo/"}
              :langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}}
        files {"01.A/01.one.md" (article "One" "/pages/one001/")}]
    (testing "--base /clogem-demo/: still dist/404.html, every link base-prefixed"
      (let [{:keys [pages]} (build-temp site files)
            page (pages "404.html")]
        (is (some? page))
        (is (str/includes? page "<li lang=\"en\"><a href=\"/clogem-demo/\">Go to the home page</a></li>"))
        (is (str/includes? page "<li lang=\"zh-Hans\"><a href=\"/clogem-demo/zh-Hans/\">返回首页</a></li>"))
        (is (str/includes? page "href=\"/clogem-demo/clogem/css/theme.css?v="))
        (is (not (contains? pages "clogem-demo/404.html")))
        (is (every? #(or (str/starts-with? % "/clogem-demo/") (re-find #"^[a-z]+:" %))
                    (map second (re-seq #"(?:href|src)=\"([^\"#]+)" page)))
            "every root-relative href and src resolves under the base")))
    (testing "a static 404.html replaces it, with no collision error; neither is swept"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
        (try
          (write-site! dir (assoc site :content {:static-dir "public"}) files)
          (fs/create-dirs (fs/path dir "public"))
          (spit (fs/file dir "public" "404.html") "<!doctype html><title>mine</title><p>my own 404")
          (let [{:keys [pages warnings thrown]} (build-or-error dir)]
            (is (nil? thrown) "the build succeeds")
            (is (= "<!doctype html><title>mine</title><p>my own 404" (get pages "404.html")))
            (is (not-any? #(str/includes? % "<h1>404</h1>") (vals pages)) "no generated 404 page anywhere")
            (is (not-any? #(str/includes? % "404") warnings) "an exact 404.html: no warning"))
          (let [{:keys [pages]} (build-dir dir)]
            (is (= "<!doctype html><title>mine</title><p>my own 404" (pages "404.html")) "a rebuild keeps it"))
          (fs/delete (fs/path dir "public" "404.html"))
          (let [{:keys [pages]} (build-dir dir)]
            (is (str/includes? (pages "404.html") "<h1>404</h1>") "the generated page is back"))
          (let [{:keys [pages]} (build-dir dir)]
            (is (str/includes? (pages "404.html") "<h1>404</h1>") "and is not swept on the next build"))
          (testing "doctor, too, sees no collision"
            (let [r (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                      (spit (fs/file dir "public" "404.html") "x")
                      (cli/doctor {:site-dir (str dir)}))]
              (is (empty? (:errors r)))))
          (finally (fs/delete-tree dir)))))
    (testing "P4-E2.1 item 3: a 404.HTML still replaces it, with one warning and no error"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
        (try
          (write-site! dir (assoc site :content {:static-dir "public"}) files)
          (fs/create-dirs (fs/path dir "public"))
          (spit (fs/file dir "public" "404.HTML") "mine")
          (let [{:keys [pages diags thrown]} (build-or-error dir)
                ws (filterv #(str/includes? (:message %) "404") diags)]
            (is (nil? thrown) "the build succeeds")
            (is (not (contains? pages "404.html")) "the generated page is not written")
            (is (= "mine" (slurp (fs/file dir "dist" "404.HTML"))))
            (is (= 1 (count ws)) (pr-str ws))
            (is (re-find #"public/404\.HTML.*hosts.*`404\.html`.*[Rr]ename" (str (:message (first ws)) " " (:hint (first ws))))
                (pr-str ws)))
          (finally (fs/delete-tree dir)))))
    (testing "P4-E2.2 item 3: both 404.html and 404.HTML: the collision error, no case warning"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2"})]
        (try
          (write-site! dir (assoc site :content {:static-dir "public"}) files)
          (fs/create-dirs (fs/path dir "public"))
          (spit (fs/file dir "public" "404.html") "mine")
          (spit (fs/file dir "public" "404.HTML") "mine too")
          (let [[_ ds] (diag/collecting
                        (render/check-output-collisions!
                         (cli/analyse (config/load-config (str dir) nil {:content {:write-front-matter false}}))))]
            (is (some #(re-find #"(?i)404\.html" (:message %)) (diag/errors ds)) (pr-str ds))
            (is (not-any? #(str/includes? (:message %) "404") (diag/warnings ds)) (pr-str (diag/warnings ds))))
          (finally (fs/delete-tree dir)))))))

(deftest dev-and-serve-answer-a-miss-with-the-404-page
  (let [root *out*]
    (testing "serve: 404, the page itself"
      (let [r ((dev/make-handler root {}) {:uri "/nope/"})]
        (is (= 404 (:status r)))
        (is (str/includes? (let [b (:body r)] (if (bytes? b) (String. ^bytes b "UTF-8") (str b))) "<h1>404</h1>"))
        (is (str/starts-with? (get-in r [:headers "Content-Type"]) "text/html"))))
    (testing "dev: 404, the page with the reload script"
      (let [r ((dev/make-handler root {:inject-reload? true}) {:uri "/pages/zzzzzz/"})]
        (is (= 404 (:status r)))
        (is (str/includes? (:body r) "<h1>404</h1>"))
        (is (str/includes? (:body r) dev/reload-script))))
    (testing "a file that exists is still 200"
      (is (= 200 (:status ((dev/make-handler root {}) {:uri "/404.html"}))))
      (is (= 200 (:status ((dev/make-handler root {}) {:uri "/"})))))
    (testing "outside the base: the stub, which names the base"
      (let [r ((dev/make-handler root {:base "/proj/"}) {:uri "/elsewhere"})]
        (is (= 404 (:status r)))
        (is (str/includes? (:body r) "This site is served at"))))
    (testing "a dist/ with no 404.html: the stub"
      (let [dir (fs/create-temp-dir {:prefix "clogem-e2-no404"})]
        (try
          (spit (fs/file dir "index.html") "<p>home")
          (let [r ((dev/make-handler dir {}) {:uri "/nope/"})]
            (is (= 404 (:status r)))
            (is (str/includes? (:body r) "<p>Not found.")))
          (finally (fs/delete-tree dir)))))))

(deftest a-404-link-is-marked-with-the-language-of-its-words
  (let [{:keys [pages]} (build-temp {:langs {:priority [:en :ja]
                                             :locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil
                                                       :ja {:label "日本語" :html-lang "ja"}}}
                                     :i18n {:missing-key :silent}}
                                    {"01.A/01.one.md" (article "One" "/pages/one001/")})]
    (is (str/includes? (pages "404.html") "<li lang=\"en\"><a href=\"/ja/\">Go to the home page</a></li>")
        "ja has no :not-found/home: the English words are marked en")))
