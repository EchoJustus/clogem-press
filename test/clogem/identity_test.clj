;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.identity-test
  "Phase 4 Task E1a (DESIGN.md §11.3 item 13, D-P4-13): the blog identity —
  `:theme :blogger`, `:social`, `:footer`, `:banner-bg`, `:body-bg-img*`
  and `:title-badge*` — from config to markup, css/identity.css and
  js/bodybg.js. What needs a browser is in test/browser/identity.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.sprite :as sprite]))

(defn- load-site
  "Load `edn` as a site.edn from an empty temp dir: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1a"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- theme-of
  "[the repaired :theme, its warning messages], asserting no error."
  [theme]
  (let [[cfg ds] (load-site {:theme theme})]
    (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
    [(:theme cfg) (mapv :message (diag/warnings ds))]))

;; ---------------------------------------------------------------------------
;; Item 1: config

(deftest the-0-2-0-shapes-warn-once-and-never-fail
  (doseq [[theme k] [[{:footer "x"} :footer] [{:social []} :social] [{:blogger "me"} :blogger]]]
    (let [[th ms] (theme-of theme)]
      (is (= 1 (count ms)) (pr-str ms))
      (is (str/starts-with? (first ms) (str ":theme " k " is ")) (first ms))
      (is (not (contains? th k)) "dropped, so nothing renders it"))))

(deftest the-identity-keys-are-known
  (doseq [k [:blogger :social :footer :banner-bg :body-bg-img :body-bg-img-opacity
             :body-bg-img-interval :title-badge :title-badge-icons]]
    (is (= :ok (get-in config/known-keys [[:theme] k])) (str k)))
  (testing "a good config is quiet"
    (let [[_ ms] (theme-of {:blogger {:name {:en "Jane" :zh-Hans "简"} :avatar "/assets/me.png" :slogan "Hi"}
                            :social {:icons [{:icon :github :title "GitHub" :link "https://github.com/me"}
                                             {:icon :mail :title {:en "Mail"} :link "mailto:me@example.com"}
                                             {:svg "assets/x.svg" :title "X" :link "/about/"}]}
                            :footer {:create-year 2019 :end-year 2025 :copyright-info "Jane"
                                     :powered-by false}
                            :banner-bg "none"
                            :body-bg-img ["https://img.example/a.jpg" "data:image/png;base64,AAAA"]
                            :body-bg-img-opacity 0.3 :body-bg-img-interval 5
                            :title-badge false :title-badge-icons [:sun "/assets/b.png"]})]
      (is (= [] ms))))
  (testing "unknown keys inside the maps, and in a social link, warn with the path"
    (let [[_ ms] (theme-of {:blogger {:name "J" :avatr "x.png"}
                            :footer {:createYear 2019}
                            :social {:icons [{:icon :github :link "https://g.example" :title "G" :iconClass "x"}]}})]
      (is (some #(= ":theme :blogger :avatr is not a theme blogger option and is ignored." %) ms) (pr-str ms))
      (is (some #(= (str ":theme :footer :createYear is vdoing's spelling; clogem-press reads "
                         ":theme :footer :create-year. It is ignored.") %) ms))
      (is (some #(str/starts-with? % ":theme :social :icons 0 :iconClass is vdoing's spelling; clogem-press reads :icon") ms))))
  (testing "a typo in a social link is a social link option"
    (let [[_ ms] (theme-of {:social {:icons [{:icon :github :link "https://g.example" :title "G" :lnk "x"}]}})]
      (is (= [":theme :social :icons 0 :lnk is not a social link option and is ignored."] ms))))
  (testing "vdoing's iconfontCssFile is not needed"
    (let [[_ ms] (theme-of {:social {:iconfontCssFile "https://at.alicdn.com/x.css"
                                     :icons [{:icon :github :link "https://g.example" :title "G"}]}})]
      (is (= [":theme :social :iconfontCssFile is not needed: clogem-press draws social icons from its own sprite; it is ignored."] ms)))))

(deftest the-blogger-card-needs-a-name-or-an-avatar
  (is (= {:name "J"} (:blogger (first (theme-of {:blogger {:name "J"}})))))
  (is (= {:avatar "/me.png"} (:blogger (first (theme-of {:blogger {:avatar "me.png"}}))))
      "a site path gets its leading /")
  (let [[th ms] (theme-of {:blogger {:slogan "x"}})]
    (is (nil? (:blogger th)))
    (is (= [":theme :blogger has neither a :name nor an :avatar; there is no blogger card."] ms)))
  (let [[th ms] (theme-of {:blogger {:name "  " :avatar "javascript:alert(1)"}})]
    (is (nil? (:blogger th)))
    (is (= 3 (count ms)) (pr-str ms))))

(deftest image-values
  (doseq [[v want] [["https://img.example/a.png" "https://img.example/a.png"]
                    [" data:image/svg+xml;base64,AAA " "data:image/svg+xml;base64,AAA"]
                    ["/assets/a.png" "/assets/a.png"]
                    ["assets/x.svg" "/assets/x.svg"]
                    ["http://img.example/a.png" nil]
                    ["//img.example/a.png" nil]
                    ["javascript:alert(1)" nil]
                    ["data:text/html,<b>" nil]
                    ["#ccc" nil] ["url(x.png)" nil] ["a b.png" nil] ["" nil] [:x nil]]]
    (is (= want (config/image-value v)) (pr-str v)))
  (testing "an http: image warns about mixed content and is dropped"
    (let [[th [m]] (theme-of {:blogger {:name "J" :avatar "http://img.example/a.png"}})]
      (is (= {:name "J"} (:blogger th)))
      (is (str/includes? m "mixed content") m))))

(deftest social-links
  (let [[th ms] (theme-of {:social {:icons [{:icon :github :title "GH" :link "https://github.com/me"}
                                            {:icon "x" :title "X" :link "https://x.com/me"}
                                            {:icon :yuque :title "Yuque" :link "https://yuque.example/me"}
                                            {:icon :mail :link "mailto:me@example.com"}
                                            {:icon :rss :link "feed.xml"}
                                            {:icon :github :title "bad" :link "javascript:alert(1)"}
                                            "not a map"]}})
        icons (get-in th [:social :icons])]
    (is (= [{:icon :brand/github :title "GH" :link "https://github.com/me"}
            {:icon :brand/x :title "X" :link "https://x.com/me"}
            {:icon :link :title "Yuque" :link "https://yuque.example/me"}
            {:icon :mail :title "me@example.com" :link "mailto:me@example.com"}
            {:icon :rss :title "/feed.xml" :link "/feed.xml"}]
           icons))
    (is (str/starts-with? (nth ms 0) ":theme :social :icons 2 :icon is :yuque, which names no icon; using :link.")
        "an unknown icon warns and falls back")
    (is (= ":theme :social :icons 3 has no :title; the link is named \"me@example.com\"." (nth ms 1)))
    (is (= ":theme :social :icons 4 has no :title; the link is named \"/feed.xml\"." (nth ms 2)))
    (is (str/starts-with? (nth ms 3) ":theme :social :icons 5 :link is \"javascript:alert(1)\""))
    (is (str/starts-with? (nth ms 4) ":theme :social :icons 6 is \"not a map\""))
    (is (= 5 (count ms)) (pr-str ms)))
  (testing "a host names an untitled link"
    (is (= "github.com" (get-in (first (theme-of {:social {:icons [{:icon :github :link "https://github.com/me"}]}}))
                                [:social :icons 0 :title]))))
  (testing "no valid link: no :social at all"
    (is (nil? (:social (first (theme-of {:social {:icons [{:icon :github :link "ftp://x"}]}})))))))

(deftest the-one-icon-resolver
  (is (= :brand/x (sprite/social-icon :x)) "brands win: :x is X, not the close icon")
  (is (= :brand/github (sprite/social-icon "GitHub")))
  (is (= :brand/github (sprite/social-icon "icon-github")) "vdoing's iconClass spelling")
  (is (= :brand/github (sprite/social-icon :brand/github)))
  (is (= :mail (sprite/social-icon :mail)))
  (is (= :rss (sprite/social-icon "rss")))
  (is (nil? (sprite/social-icon :yuque)))
  (is (nil? (sprite/social-icon :brand/mail)) "a UI icon is no brand")
  (is (nil? (sprite/social-icon nil)))
  (testing "brands-in-use reads the repaired config through it"
    (let [[cfg _] (load-site {:theme {:social {:icons [{:icon :github :title "G" :link "https://g.example"}
                                                       {:icon "x" :title "X" :link "https://x.example"}
                                                       {:icon :mail :title "M" :link "mailto:a@b.example"}
                                                       {:icon :mastodon :svg "/m.svg" :title "M" :link "https://m.example"}
                                                       {:icon :yuque :title "Y" :link "https://y.example"}]}}})]
      (is (= #{:github :x} (sprite/brands-in-use cfg)) "an :svg entry draws no brand"))
    (is (= #{} (sprite/brands-in-use config/defaults)))))

(deftest the-footer-keys
  (let [[th ms] (theme-of {:footer {:create-year 19 :end-year 2025.0 :copyright-info 5 :powered-by "no"}})]
    (is (= {} (:footer th)))
    (is (= 4 (count ms)) (pr-str ms)))
  (is (= {:create-year 2019 :copyright-info {:en "J" :zh-Hans "简"} :powered-by false}
         (:footer (first (theme-of {:footer {:create-year 2019 :copyright-info {:en "J" :zh-Hans "简"}
                                             :powered-by false}}))))))

(deftest banner-bg-values
  (doseq [[v want] [["auto" [:auto]] [:auto [:auto]] ["NONE" [:none]] [:none [:none]]
                    ["/assets/b.jpg" [:image "/assets/b.jpg"]]
                    ["https://img.example/b.jpg" [:image "https://img.example/b.jpg"]]
                    ["  background: linear-gradient(red, blue)" [:custom "background: linear-gradient(red, blue)"]]
                    ["#ccc" nil] ["http://img.example/b.jpg" nil] [42 nil] [nil nil]]]
    (is (= want (config/banner-bg v)) (pr-str v)))
  (let [[th [m & more]] (theme-of {:banner-bg "#ccc"})]
    (is (nil? (:banner-bg th)) "nil is auto")
    (is (empty? more))
    (is (str/includes? m "using \"auto\"") m))
  (is (= [:none] (config/banner-bg (:banner-bg (first (theme-of {:banner-bg :none}))))))
  (is (= [] (second (theme-of {:banner-bg "background: #222"})))))

(deftest body-bg-img-values
  (is (= ["/a.png"] (:body-bg-img (first (theme-of {:body-bg-img "a.png"})))) "one string is a list of one")
  (let [[th ms] (theme-of {:body-bg-img ["/a.png" "http://x.example/b.png" "/c.png"]})]
    (is (= ["/a.png" "/c.png"] (:body-bg-img th)))
    (is (= 1 (count ms))))
  (doseq [v [[] 42 ["//x.example/a.png"] {:a 1}]]
    (let [[th ms] (theme-of {:body-bg-img v})]
      (is (nil? (:body-bg-img th)) (pr-str v))
      (is (= 1 (count ms)) (pr-str v ms))))
  (testing "opacity: (0, 1], in vdoing's tenths; anything else is 0.5"
    (doseq [[v want] [[0.44 0.4] [0.46 0.5] [1 1.0] [0.01 0.1] [0.5 0.5]]]
      (let [[th ms] (theme-of {:body-bg-img-opacity v})]
        (is (= want (:body-bg-img-opacity th)) (pr-str v))
        (is (= [] ms))))
    (doseq [v [0 1.5 -1 "0.3" nil]]
      (let [[th ms] (theme-of {:body-bg-img-opacity v})]
        (is (= 0.5 (:body-bg-img-opacity th)) (pr-str v))
        (is (= (if (nil? v) 0 1) (count ms)) (pr-str v ms)))))
  (testing "interval: at least 1 s, else 15"
    (is (= 3 (:body-bg-img-interval (first (theme-of {:body-bg-img-interval 3})))))
    (doseq [v [0 0.5 "10" :x]]
      (let [[th ms] (theme-of {:body-bg-img-interval v})]
        (is (= 15 (:body-bg-img-interval th)) (pr-str v))
        (is (= 1 (count ms)) (pr-str v)))))
  (testing "a background image forces the card style, as vdoing's does"
    (let [[th ms] (theme-of {:page-style :line :body-bg-img "/a.png"})]
      (is (= :card (:page-style th)))
      (is (= 1 (count ms)))
      (is (str/includes? (first ms) "using :card")))
    (is (= :line (:page-style (first (theme-of {:page-style :line})))) "without one, :line stays"))
  (testing "config/body-bg-images"
    (is (= ["/a.png"] (config/body-bg-images (first (load-site {:theme {:body-bg-img "/a.png"}})))))
    (is (nil? (config/body-bg-images config/defaults)))))

(deftest title-badge-values
  (is (true? (:title-badge (first (theme-of {})))) "on by default")
  (is (false? (:title-badge (first (theme-of {:title-badge false})))))
  (let [[th ms] (theme-of {:title-badge "yes"})]
    (is (true? (:title-badge th)))
    (is (= 1 (count ms))))
  (let [[th ms] (theme-of {:title-badge-icons [:sun "assets/b.png" :nope :brand/github 42 "http://x.example/a.png"]})]
    (is (= [:sun "/assets/b.png"] (:title-badge-icons th)))
    (is (= 4 (count ms)) (pr-str ms)))
  (doseq [v [[] [:nope] "x.png"]]
    (is (nil? (:title-badge-icons (first (theme-of {:title-badge-icons v})))) (str (pr-str v) ": the built-ins"))))

(deftest identity-css-only-when-something-needs-it
  (is (false? (config/identity-css? (first (load-site {})))))
  (is (false? (config/identity-css? (first (load-site {:theme {:footer {:create-year 2020}
                                                               :title-badge true :banner-bg "none"}}))))
      "the footer, the badge and the banner are in theme.css")
  (doseq [theme [{:blogger {:name "J"}}
                 {:social {:icons [{:icon :github :title "G" :link "https://g.example"}]}}
                 {:body-bg-img "/a.png"}]]
    (is (true? (config/identity-css? (first (load-site {:theme theme})))) (pr-str theme)))
  (testing "a dropped value does not count"
    (is (false? (config/identity-css? (first (load-site {:theme {:blogger "me" :social []}})))))))
