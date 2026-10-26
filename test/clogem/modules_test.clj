;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.modules-test
  "Phase 4 Task E1b (DESIGN.md §11.3 item 13, D-P4-13): htmlModules — the
  seven slots, the two show-modes and `:head-extra` — from config to
  markup, css/identity.css and js/window.js. What needs a browser is in
  test/browser/modules.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]))

(defn- load-site
  "Load `edn` as a site.edn from an empty temp dir: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1b"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- modules-of
  "[the repaired :html-modules, its warnings as [message hint]], asserting
  no error."
  [hm]
  (let [[cfg ds] (load-site {:theme {:html-modules hm}})]
    (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
    [(get-in cfg [:theme :html-modules]) (mapv (juxt :message :hint) (diag/warnings ds))]))

(def ^:private hint "See config.example.edn (vdoing's htmlModules).")

;; ---------------------------------------------------------------------------
;; Item 1: keys, checks and accessors

(deftest nil-and-empty-are-off-and-quiet
  (doseq [v [nil {}]]
    (let [[hm ws] (modules-of v)]
      (is (empty? hm) (pr-str v))
      (is (= [] ws) (pr-str v)))))

(deftest a-value-that-is-not-a-map-warns-once-and-is-removed
  (doseq [v [false :none true 0 "<p>x</p>" [:page-t]]]
    (let [[cfg ds] (load-site {:theme {:html-modules v}})]
      (is (empty? (diag/errors ds)))
      (is (= [[(str ":theme :html-modules is " (pr-str v) ", but it must be a map of slots such as "
                    "{:page-t \"<p>…</p>\"}; nothing is injected.") hint]]
             (mapv (juxt :message :hint) (diag/warnings ds)))
          (pr-str v))
      (is (not (contains? (:theme cfg) :html-modules)) (pr-str v)))))

(deftest every-slot-and-show-mode-is-quiet
  (let [hm {:head-extra "<meta name=\"a\" content=\"b\">"
            :home-sidebar-b "<p>h</p>" :sidebar-t {:en "<p>t</p>" :zh-Hans ""}
            :sidebar-b "" :page-t {:en "<b>x</b>"} :page-b {}
            :page-t-show-mode "article" :page-b-show-mode :custom
            :window-lb "<p>l</p>" :window-rb {:zh-Hans "<p>r</p>"}}
        [out ws] (modules-of hm)]
    (is (= [] ws))
    (is (= (assoc hm :page-t-show-mode :article) out) "a show-mode string is stored as a keyword"))
  (doseq [[v m] [["all" :all] [:all :all] ["custom" :custom] [:article :article] [" Article " :article]]]
    (let [[out ws] (modules-of {:page-b-show-mode v})]
      (is (= [] ws) (pr-str v))
      (is (= m (:page-b-show-mode out)) (pr-str v)))))

(deftest a-bad-slot-warns-and-is-removed
  (doseq [v [42 true :p ["<p>"] {:en 1} {"en" "<p>x</p>"} {:en nil}]]
    (let [[out ws] (modules-of {:page-t v :page-b "<p>kept</p>"})]
      (is (= [[(str ":theme :html-modules :page-t is " (pr-str v) ", but it must be a string of HTML or "
                    "a map of language to string, such as {:en \"<p>…</p>\"}; it is ignored.") hint]]
             ws)
          (pr-str v))
      (is (= {:page-b "<p>kept</p>"} out) (pr-str v)))))

(deftest a-bad-show-mode-warns-and-means-all
  (doseq [v ["both" :never 1 true]]
    (let [[out ws] (modules-of {:page-t-show-mode v})]
      (is (= [[(str ":theme :html-modules :page-t-show-mode is " (pr-str v) ", but it must be :all, "
                    ":article or :custom (vdoing's \"article\" or \"custom\"); using :all.") hint]]
             ws)
          (pr-str v))
      (is (= :all (:page-t-show-mode out))))))

(deftest head-extra-is-head-content-only
  (testing "links, metas, scripts, styles, noscript, templates and comments pass, in any case"
    (doseq [v ["<link rel=\"stylesheet\" href=\"{{base}}x.css\">\n<META name=a content=b>"
               "<script>document.write('<div>')</script><style>p{}</style>"
               "<SCRIPT src=\"/a.js\"></SCRIPT><noscript><img src=x></noscript>"
               "<!-- <div> --><template><div></div></template>"
               "" "   "]]
      (let [[out ws] (modules-of {:head-extra v})]
        (is (= [] ws) (pr-str v))
        (is (= v (:head-extra out))))))
  (testing "anything else warns, naming the first bad tag, and is removed"
    (doseq [[v bad] [["<meta name=a><div>x</div>" "<div>"]
                     ["<img src=x.png>" "<img>"]
                     ["<TITLE>Two</TITLE>" "<title>"]
                     ["<base href=\"/x/\">" "<base>"]
                     ["hello <meta name=a>" "text \"hello\""]]]
      (let [[out ws] (modules-of {:head-extra v :page-t "<p>kept</p>"})]
        (is (= [[(str ":theme :html-modules :head-extra has " bad ", but it must be head content only — "
                      "<link>, <meta>, <script>, <style>, <noscript>, <template> or a comment; it is ignored.")
                 hint]]
               ws)
            (pr-str v))
        (is (= {:page-t "<p>kept</p>"} out)))))
  (testing "in a per-language map, only that language's value is dropped: it becomes \"\""
    (let [[out ws] (modules-of {:head-extra {:en "<div>" :zh-Hans "<meta name=a>"}})]
      (is (= {:head-extra {:en "" :zh-Hans "<meta name=a>"}} out))
      (is (= 1 (count ws)))
      (is (str/starts-with? (ffirst ws) ":theme :html-modules :head-extra :en has <div>,")))))

(deftest vdoing-slot-names-warn-with-the-kebab-spelling
  (let [[_ ds] (load-site {:theme {:html-modules {:homeSidebarB "x"} :pageT "y"}})
        ms (mapv :message (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (= #{(str ":theme :html-modules :homeSidebarB is vdoing's spelling; clogem-press reads "
                  ":theme :html-modules :home-sidebar-b. It is ignored.")
             ":theme :pageT is vdoing's spelling; clogem-press reads :theme :html-modules :page-t. It is ignored."}
           (set ms))
        (pr-str ms)))
  (doseq [[v k] [["sidebarT" :sidebar-t] ["sidebarB" :sidebar-b] ["pageB" :page-b]
                 ["pageTshowMode" :page-t-show-mode] ["pageBshowMode" :page-b-show-mode]
                 ["windowLB" :window-lb] ["windowRB" :window-rb] ["htmlModules" nil]]]
    (is (= (if k [[:theme :html-modules k] :ok] [[:theme :html-modules] :ok])
           (get config/vdoing-keys v))
        v)))

(deftest unknown-keys-warn-and-suggest
  (let [[_ ds] (load-site {:theme {:html-modules {:bogus 1 :sidebar-tt "x"}}})
        by (into {} (map (juxt :message :hint)) (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (contains? by ":theme :html-modules :bogus is not a theme html-modules option and is ignored."))
    (is (str/starts-with? (by ":theme :html-modules :sidebar-tt is not a theme html-modules option and is ignored.")
                          "Did you mean :sidebar-t?"))
    (is (= 2 (count by)) (pr-str by))))

(deftest the-accessors
  (let [[cfg _] (load-site {:theme {:html-modules {:page-t {:en "" :zh-Hans " "} :page-b {:ms "<p>x</p>"}
                                                   :sidebar-t "  " :window-rb "<p>r</p>"
                                                   :page-b-show-mode "custom"}}})]
    (is (= [:head-extra :home-sidebar-b :sidebar-t :sidebar-b :page-t :page-b :window-lb :window-rb]
           config/module-slots))
    (is (= {:ms "<p>x</p>"} (config/html-module cfg :page-b)))
    (is (= :all (config/show-mode cfg :page-t)))
    (is (= :custom (config/show-mode cfg :page-b)))
    (is (false? (config/module-set? cfg :page-t)) "only blank values")
    (is (false? (config/module-set? cfg :sidebar-t)))
    (is (true? (config/module-set? cfg :page-b)))
    (is (false? (config/module-set? cfg :head-extra)))
    (is (true? (boolean (config/windows? cfg))))
    (is (false? (boolean (config/windows? (first (load-site {:theme {:html-modules {:page-t "x"}}}))))))))
