;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.search-all-test
  "Cross-language search (Phase 4 Task G, §11.3 item 17, D-P4-17): the
  `:search :cross-language` option, the merge data each page carries, and
  the two files that serve it. The built pages are asserted in
  `clogem.search-test`, beside the rest of the search markup."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.assets :as assets]
            [clogem.config :as config]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; 1. Config (`:search :cross-language`)

(defn- load-site
  "Write a site.edn into a temp dir and load it; [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-xlang"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- warnings-of [edn]
  (let [[cfg ds] (load-site edn)]
    (is (empty? (diag/errors ds)) "never an error")
    [cfg (mapv :message (diag/warnings ds))]))

(defn- hints-of [edn]
  (mapv :hint (diag/warnings (second (load-site edn)))))

(deftest the-key-is-read-not-planned
  (testing "G flips it to :ok, so a site that sets it is no longer told it has no effect"
    (is (= :ok (get-in config/known-keys [[:search] :cross-language])))
    (let [[cfg ws] (warnings-of {:search {:provider :pagefind :cross-language true}})]
      (is (= [] ws) "no \"planned, not implemented\" text")
      (is (true? (config/cross-language? cfg))))))

(deftest nil-and-false-are-off-and-silent
  (doseq [edn [{:search {:provider :pagefind}}
               {:search {:provider :pagefind :cross-language false}}]]
    (let [[cfg ws] (warnings-of edn)]
      (is (= [] ws) (pr-str edn))
      (is (false? (config/cross-language? cfg)) (pr-str edn)))))

(deftest anything-else-is-one-warning-repaired-to-off
  (doseq [v ["yes" 1 :true]]
    (let [[cfg ws] (warnings-of {:search {:provider :pagefind :cross-language v}})]
      (is (= [(str ":search :cross-language is " (pr-str v)
                   ", but it must be true or false; cross-language search is off.")]
             ws)
          (pr-str v))
      (is (false? (config/cross-language? cfg)) (pr-str v))
      (is (false? (get-in cfg [:search :cross-language]))
          "repaired with assoc-in: no entry is added to config/defaults"))))

(deftest the-feature-is-off-without-pagefind
  (testing "--no-search sets :provider :none; `true` under it says nothing"
    (let [[cfg ws] (warnings-of {:search {:provider :none :cross-language true}})]
      (is (= [] ws))
      (is (false? (config/cross-language? cfg))))))

(deftest the-vdoing-hint-lists-the-key
  (testing "a misspelling is answered with the real key, and the options now name it"
    (let [[_ ws] (warnings-of {:search {:provider :pagefind :crossLanguage true}})
          h (first (hints-of {:search {:provider :pagefind :crossLanguage true}}))]
      (is (= [":search :crossLanguage is not a search option and is ignored."] ws))
      (is (str/includes? (str h) "Did you mean :cross-language?") h)
      (is (str/includes? (str h) "The options are :cross-language, :provider") h))))

(deftest the-example-and-the-demo-stay-quiet
  (doseq [f ["config.example.edn" "examples/demo-site/site.edn"]]
    (let [[_ ws] (warnings-of (-> (clojure.edn/read-string (slurp f))
                                  (assoc-in [:build :out] "dist")))]
      (is (= [] ws) f))))

(deftest the-example-says-when-the-files-ship
  (testing "G.1 item 3: js/search-all.js and css/search-all.css ship with the
            key on under :provider :pagefind only (assets/skip? asks
            config/cross-language?), and config.example.edn says so"
    (let [skip? @#'assets/skip?
          flat (str/replace (slurp "config.example.edn") #"\s*\n\s*;+\s*" " ")
          old-claim? (str/includes? flat "ship whenever this is true")
          says-pagefind? (str/includes? flat (str "js/search-all.js and css/search-all.css ship when this"
                                                  " is true and :provider is :pagefind (so not under --no-search)"))]
      (doseq [f ["js/search-all.js" "css/search-all.css"]]
        (is (false? (skip? {:search {:provider :pagefind :cross-language true}} f)) f)
        (is (true? (skip? {:search {:provider :none :cross-language true}} f)) f))
      (is (not old-claim?) "not \"whenever this is true\": :provider :none ships neither")
      (is says-pagefind? "the example names :provider :pagefind"))))

;; ---------------------------------------------------------------------------
;; 3 and 4. The two files G ships

(defn- gzip-size [^bytes bs]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [g (java.util.zip.GZIPOutputStream. out)] (.write g bs))
    (.size out)))

(def search-all-js "src/clogem/theme/resources/js/search-all.js")
(def search-all-css "src/clogem/theme/resources/css/search-all.css")

(def js-budget
  "Task G item 3: js/search-all.js, in gzipped bytes."
  2048)

(def css-budget
  "Task G item 4: css/search-all.css, in gzipped bytes."
  512)

(deftest search-all-js-stays-within-its-budget
  (let [bs (fs/read-all-bytes search-all-js)
        gz (gzip-size bs)]
    (is (= 2048 js-budget) "raising the budget is a decision, not a test edit")
    (is (<= gz js-budget) (str gz " bytes gzipped"))
    (println (format "search-all: js/search-all.js is %d bytes, %d gzipped (budget %d)"
                     (count bs) gz js-budget))))

(deftest search-all-css-stays-within-its-budget
  (let [bs (fs/read-all-bytes search-all-css)
        gz (gzip-size bs)]
    (is (= 512 css-budget) "raising the budget is a decision, not a test edit")
    (is (<= gz css-budget) (str gz " bytes gzipped"))
    (println (format "search-all: css/search-all.css is %d bytes, %d gzipped (budget %d)"
                     (count bs) gz css-budget))))

(deftest the-row-is-built-from-dom-nodes
  (let [js (slurp search-all-js)]
    (is (not (str/includes? js "innerHTML")) "no raw HTML: the row is elements and text nodes")
    (is (not (str/includes? js "insertAdjacentHTML")))
    (is (str/includes? js "createElement"))
    (is (str/includes? js "createTextNode"))
    (testing "the rendered modal is augmented, never rebuilt: a modal subtree
              built in JS and then connected draws two close buttons"
      (is (not (str/includes? js "createElement(\"pagefind-modal\"")))))
  (testing "no inline script, and the file is the theme's own (CSP: script-src 'self')"
    (is (not (str/includes? (slurp search-all-js) "eval(")))))

(deftest the-stored-key-is-read-the-way-the-privacy-note-is-checked
  (testing "`var KEY = \"clogem-search-all\"`, read by localStorage.getItem(KEY):
            what support_page_test's theme-storage-keys scan finds (item 7)"
    (let [js (slurp search-all-js)]
      (is (str/includes? js "var KEY = \"clogem-search-all\""))
      (is (str/includes? js "localStorage.getItem(KEY)"))
      (is (str/includes? js "localStorage.setItem(KEY"))
      (is (str/includes? js "localStorage.removeItem(KEY"))
      (testing "every storage access is wrapped: blocked storage means off, never a throw"
        (is (= 3 (count (re-seq #"localStorage\.\w+Item\(KEY" js)))
            "getItem, setItem and removeItem — all of them")
        (is (= 4 (count (re-seq #"try \{" js))) "the JSON parses and the two storage helpers")))))

(deftest the-css-keeps-the-dropdown-menu-inside-the-viewport
  (testing "Pagefind centres .pf-dropdown-menu on its inline-flex wrapper at
            <= 640px, with rules of (3,0,0) — hence the full-width !important"
    (let [css (slurp search-all-css)]
      (is (str/includes? css ".clogem-search-all .pf-dropdown-wrapper{width:100%!important}"))
      (is (str/includes? css ".clogem-search-all{display:flex"))
      (is (str/includes? css ".clogem-search-all pagefind-filter-dropdown{flex:1 1 12rem}"))
      (is (not (re-find #"(?m)^\s*(color|background)\s*:" css))
          "colours come from Pagefind's --pf-*, which theme.css maps"))))

(deftest print-hides-the-row-with-the-rest-of-the-search-chrome
  (testing "the row sits in the modal, which sits in .clogem-search, which
            theme.css hides in print (so css/search-all.css needs no rule)"
    (let [theme (slurp "src/clogem/theme/resources/css/theme.css")
          block (second (re-find #"(?s)@media print \{(.*)" theme))]
      (is (re-find #"\.clogem-search,[^}]*display: none !important" block) "print hides .clogem-search")
      (is (str/includes? (slurp search-all-js) "\".clogem-search pagefind-modal\"")
          "the row goes into the modal inside .clogem-search")
      (is (not (str/includes? (slurp search-all-css) "@media")) "no rule of its own for print"))))

(deftest the-strings-are-in-every-language
  (doseq [[l s] {"en" "Search all languages" "zh-Hans" "搜索所有语言" "zh-Hant" "搜尋所有語言"
                 "ms" "Cari dalam semua bahasa" "ta" "எல்லா மொழிகளிலும் தேடு"}]
    (let [m (clojure.edn/read-string (slurp (str "src/clogem/theme/resources/i18n/" l ".edn")))]
      (is (= s (:nav/search-all m)) l)
      (is (contains? m :nav/language) (str l ": the filter label reuses :nav/language")))))
