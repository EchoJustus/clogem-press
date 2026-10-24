;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.parity-test
  "Phase 4 Task E2 (§11.3 item 16, D-P4-13, D-P4-16): article-page parity —
  the edit link, last-updated dates from git, the update bar under
  articles, `titleTag` in list rows, the navbar logo and repository link,
  front matter `pageClass` and the 404 page. What needs a browser is in
  test/browser/parity.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- load-cfg
  "Write `edn` as a temp site.edn and load it: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e2-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

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
