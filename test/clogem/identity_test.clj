;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.identity-test
  "Phase 4 Task E1a (DESIGN.md §11.3 item 13, D-P4-13): the blog identity —
  `:theme :blogger`, `:social`, `:footer`, `:banner-bg`, `:body-bg-img*`
  and `:title-badge*` — from config to markup, css/identity.css and
  js/bodybg.js. What needs a browser is in test/browser/identity.test.mjs."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.css-palette :as cp]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.search :as search]
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

(deftest a-bare-word-is-no-image
  (testing "P4-E1a.1 item 8b: neither / nor . — a warning, and the value dropped"
    (doseq [v ["dark-blue" "transparent" "star"]]
      (is (nil? (config/image-value v)) v))
    (let [[th ms] (theme-of {:banner-bg "dark-blue"})]
      (is (nil? (:banner-bg th)) "auto")
      (is (= 1 (count ms)) (pr-str ms)))
    (let [[th ms] (theme-of {:body-bg-img "transparent"})]
      (is (nil? (:body-bg-img th)))
      (is (= 1 (count ms)))
      (is (str/includes? (first ms) "a bare word") (first ms)))
    (let [[th ms] (theme-of {:title-badge-icons ["star" :sun]})]
      (is (= [:sun] (:title-badge-icons th)))
      (is (= 1 (count ms)))
      (is (str/includes? (first ms) "write it as a keyword, such as :star") (first ms)))))

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

;; ---------------------------------------------------------------------------
;; Temp sites

(def ^:private one-lang {:langs {:locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}})
(def ^:private two-langs {:langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}})

(defn- article
  ([permalink date] (article permalink date "T"))
  ([permalink date title]
   (str "---\ntitle: " title "\n" (when date (str "date: \"" date "\"\n")) "permalink: " permalink "\n---\n\nbody\n")))

(defn- build
  "Build a temp site from `edn` and content `files` {rel text} (or, for a
  rel starting `/`, a file of the site's own); returns
  {:pages {rel-path html} :dist #{rel-path} :sprite text :warnings [msg]}."
  [edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1a"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str (merge {:site {:title "S"} :search {:provider :none}} edn)))
      ;; a key starting `/` is a file of the site's own (`/assets/x.png`)
      (doseq [[rel content] files
              :let [p (if (str/starts-with? rel "/") (fs/path dir (subs rel 1)) (fs/path dir "content" rel))]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (let [err  (java.io.StringWriter.)
            [_ ds] (binding [*out* (java.io.StringWriter.) *err* err]
                     (diag/collecting (cli/build {:site-dir (str dir) :no-write true})))
            dist (fs/path dir "dist")]
        (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
        (is (not (str/includes? (str err) "error:")) (str err))
        {:pages (into (sorted-map)
                      (for [p (fs/glob dist "**.html")]
                        [(str (fs/relativize dist p)) (slurp (fs/file p))]))
         :dist (set (map #(str (fs/relativize dist %)) (fs/glob dist "**")))
         :sprite (slurp (fs/file dist "clogem" "icons.svg"))
         :css (when (fs/exists? (fs/path dist "clogem" "css" "identity.css"))
                (slurp (fs/file dist "clogem" "css" "identity.css")))
         ;; what the build printed (it reports its own diagnostics)
         :warnings (into (mapv :message (diag/warnings ds))
                         (map second (re-seq #"(?m)^warning: (.*)$" (str err))))})
      (finally (fs/delete-tree dir)))))

(defn- gzip-size [^bytes bs]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [g (java.util.zip.GZIPOutputStream. out)] (.write g bs))
    (.size out)))

(def ^:private identity-css "src/clogem/theme/resources/css/identity.css")
(def ^:private theme-css "src/clogem/theme/resources/css/theme.css")
(def ^:private bodybg-js "src/clogem/theme/resources/js/bodybg.js")

(def social
  {:icons [{:icon :github :title "GitHub" :link "https://github.com/me"}
           {:icon :x :title "X" :link "https://x.com/me"}
           {:icon :mail :title "Mail" :link "mailto:me@example.com"}
           {:icon :yuque :title "Yuque" :link "https://yuque.example/me"}]})

(defn- section-order [html]
  (map second (re-seq #"<section(?: aria-label=\"[^\"]*\")? class=\"(clogem-[a-z-]+)" html)))

(defn- aside-of [html] (re-find #"(?s)<aside class=\"clogem-home-right\">.*?</aside>" html))
(defn- footer-of [html] (re-find #"(?s)<footer class=\"clogem-footer\">.*?</footer>" html))

;; ---------------------------------------------------------------------------
;; Item 2: where the CSS goes

(deftest identity-css-ships-and-is-linked-only-when-used
  (let [plain (build one-lang {"index.md" "# Home\n" "01.Guide/01.t.md" (article "/pages/t00001/" "2025-01-01")})
        used  (build (assoc one-lang :theme {:blogger {:name "J"}})
                     {"index.md" "# Home\n" "01.Guide/01.t.md" (article "/pages/t00001/" "2025-01-01")})]
    (testing "no opt-in feature: no link, no file"
      (is (not-any? #(str/includes? (val %) "identity.css") (:pages plain)))
      (is (not (contains? (:dist plain) "clogem/css/identity.css"))))
    (testing "a blogger card: on every page, right after theme.css, before highlight.css"
      (is (contains? (:dist used) "clogem/css/identity.css"))
      (doseq [[rel html] (:pages used)
              :when (str/includes? html "<header class=\"clogem-navbar\"")
              :let [links (map second (re-seq #"<link href=\"([^\"]+)\" rel=\"stylesheet\"" html))]]
        (is (re-find #"^/clogem/css/theme\.css\?v=" (first links)) rel)
        (is (re-find #"^/clogem/css/identity\.css\?v=[0-9a-f]+$" (second links)) rel)))))

(deftest the-css-budgets
  (let [bs (fs/read-all-bytes identity-css)
        gz (gzip-size bs)]
    (is (<= gz 2048) (str "identity.css: " gz " bytes gzipped"))
    (println (format "identity: css/identity.css is %d bytes, %d gzipped (budget 2048)" (count bs) gz)))
  (let [bs (fs/read-all-bytes theme-css)
        gz (gzip-size bs)]
    (is (<= gz 12288))
    (println (format "identity: theme.css is %d bytes, %d gzipped (budget 12288; 11354 before E1a)" (count bs) gz)))
  (let [bs (fs/read-all-bytes bodybg-js)
        gz (gzip-size bs)]
    (is (<= gz 600) (str "bodybg.js: " gz " bytes gzipped"))
    (println (format "identity: js/bodybg.js is %d bytes, %d gzipped (budget 600)" (count bs) gz))))

(deftest identity-css-adds-no-fixed-rule-to-theme-css
  (let [rules (cp/parse (slurp theme-css))
        fixed (mapcat :selectors (filter #(some (fn [[p v]] (and (= "position" p) (= "fixed" v))) (:decls %)) rules))]
    (is (every? #(re-find #"^\.clogem-(page-button|back-to-top)$" %) fixed) (pr-str fixed)))
  (testing "identity.css draws from the palette only, and prints none of its chrome"
    (let [css (slurp identity-css)]
      (is (not (re-find #"#[0-9a-fA-F]{3,8}\b" (str/replace css #"(?s)/\*.*?\*/" ""))) "no colour literal")
      (let [print-rules (filter #(= ["print"] (:media %)) (cp/parse css))
            hidden (set (mapcat :selectors (filter #(some (fn [[p v]] (and (= "display" p) (str/starts-with? v "none"))) (:decls %)) print-rules)))]
        (is (= #{".clogem-blogger" ".clogem-social" ".clogem-body-bg"} hidden))))))

(deftest the-hero-colour-pairs
  (let [css (slurp theme-css)
        rules (cp/parse css)
        hero (into {} (for [r rules :when (and (empty? (:media r)) (some #{".clogem-hero"} (:selectors r))) [p v] (:decls r)] [p v]))]
    (is (= "#fff" (hero "color")))
    (is (str/ends-with? (hero "background") "#28282d"))
    (is (<= 4.5 (cp/contrast "#ffffff" "#28282d")) "white on vdoing's banner grey")
    (doseq [[mode _] cp/modes
            style cp/styles
            :let [pal (cp/palette css mode style)
                  c (cp/contrast (pal "--mainBg") (pal "--accent"))]]
      (is (<= 4.5 c) (format "the action, --mainBg on --accent, %s %s: %.2f" mode style c)))
    (testing "the action is never white text"
      (let [a (into {} (for [r rules :when (some #{".clogem-hero__action"} (:selectors r)) [p v] (:decls r)] [p v]))]
        (is (= "var(--mainBg)" (a "color")))
        (is (= "var(--accent)" (a "background")))))
    (testing "print hides the banner and the badge"
      (is (re-find #"\.clogem-hero, \.clogem-title-badge \{ display: none !important; \}" css)))))

;; ---------------------------------------------------------------------------
;; Item 3: brands and the social links

(deftest the-sprite-holds-exactly-the-brands-in-use
  (let [{:keys [pages sprite]} (build (assoc one-lang :theme {:social social})
                                      {"index.md" "# Home\n" "01.Guide/01.t.md" (article "/pages/t00001/" "2025-01-01")})
        ids (set (map second (re-seq #"<symbol id=\"([^\"]+)\"" sprite)))]
    (is (= (into (set (map name (sprite/ui-names))) ["brand-github" "brand-x"]) ids))
    (is (str/includes? sprite "Simple Icons") "the brand notice travels with them")
    (testing "every <use> on every page resolves"
      (doseq [[rel html] pages
              [_ frag] (re-seq #"<use href=\"[^\"#]*#([^\"]+)\"" html)]
        (is (contains? ids frag) (str rel ": #" frag))))
    (testing "rel=me, named, same tab; the unknown icon fell back to the link icon"
      (let [ul (re-find #"(?s)<ul aria-label=\"Social links\" class=\"clogem-social\">.*?</ul>" (pages "index.html"))
            as (re-seq #"<a [^>]*>" ul)]
        (is (= 4 (count as)))
        (doseq [a as]
          (is (str/includes? a "rel=\"me\"") a)
          (is (re-find #"aria-label=\"[^\"]+\"" a) a)
          (is (not (str/includes? a "target")) a))
        (is (re-find #"aria-label=\"Yuque\" href=\"https://yuque.example/me\" rel=\"me\" title=\"Yuque\"><svg[^>]*><use href=\"[^\"]*#link\"" ul))))))

(deftest social-links-under-a-base
  (let [{:keys [pages]} (build (assoc one-lang :site {:title "S" :base "/my docs/"}
                                      :theme {:social {:icons [{:icon :rss :title "RSS" :link "/feed.xml"}
                                                               {:svg "assets/s.svg" :title "S" :link "https://s.example/"}]}})
                               {"index.md" "# Home\n"})
        f (footer-of (pages "index.html"))]
    (is (str/includes? f "href=\"/my%20docs/feed.xml\""))
    (is (str/includes? f "<a aria-label=\"S\" href=\"https://s.example/\" rel=\"me\" title=\"S\"><img alt=\"\" class=\"clogem-icon\" src=\"/my%20docs/assets/s.svg\" /></a>")
        "an :svg is an <img>, base-prefixed; a URL is never prefixed")))

;; P4-E1a.1 item 1: a site path keeps its ?query, #fragment and %XX escapes

(defn- resolves?
  "Does the emitted URL `l`, under the encoded base `enc`, name a file in
  `dist` (a set of rel paths) — its query and fragment dropped, decoded?"
  [dist enc l]
  (and (str/starts-with? l enc)
       (let [rel (java.net.URLDecoder/decode (str/replace (subs l (count enc)) #"[?#].*$" "") "UTF-8")]
         (or (contains? dist rel) (contains? dist (str rel "index.html"))))))

(deftest identity-urls-keep-their-query-fragment-and-escapes
  (doseq [[base enc] [["/" "/"] ["/my docs/" "/my%20docs/"]]]
    (let [{:keys [pages dist]}
          (build (assoc one-lang :site {:title "S" :base base}
                        :theme {:blogger {:name "J" :avatar "/assets/my%20pic.png"}
                                :social {:icons [{:icon :mail :title "Contact" :link "/about/#contact"}
                                                 {:svg "/assets/a.png?v=2" :title "A" :link "/about/"}]}
                                :title-badge-icons ["/assets/图.png"]})
                 {"index.md" "# Home\n"
                  "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01")
                  "01.Guide/02.about.md" (article "/about/" "2025-01-01")
                  "/assets/my pic.png" "p" "/assets/a.png" "a" "/assets/图.png" "t"})
          home  (pages "index.html")
          found {:link   (second (re-find #"<a aria-label=\"Contact\" href=\"([^\"]+)\"" home))
                 :svg    (second (re-find #"class=\"clogem-icon\" src=\"([^\"]+)\"" home))
                 :avatar (second (re-find #"class=\"clogem-blogger__avatar\" src=\"([^\"]+)\"" home))
                 :badge  (second (re-find #"class=\"clogem-title-badge\" src=\"([^\"]+)\""
                                          (pages "pages/a00001/index.html")))}]
      (is (= {:link   (str enc "about/#contact")
              :svg    (str enc "assets/a.png?v=2")
              :avatar (str enc "assets/my%20pic.png")
              :badge  (str enc "assets/%E5%9B%BE.png")}
             found)
          base)
      (doseq [[k l] found]
        (is (resolves? dist enc l) (str base " " k ": " l " names a file in dist/"))))))

;; ---------------------------------------------------------------------------
;; Item 4: the blogger card

(def ^:private blogger {:name "Jane <b>&" :avatar "/assets/我.png" :slogan "Hi"})

(deftest the-home-right-bar-order
  (let [files {"index.md" "---\nhome: true\npostList: detailed\n---\n\n# Home\n"
               "01.Guide/01.t.md" (str "---\ntitle: T\ndate: \"2025-01-01\"\npermalink: /pages/t00001/\ncategories: [Guide]\ntags: [x]\n---\n\nbody\n")}
        detailed (build (assoc one-lang :theme {:blogger {:name "J"}}) files)
        simple   (build (assoc one-lang :theme {:blogger {:name "J"}})
                        (assoc files "index.md" "---\nhome: true\npostList: simple\n---\n\n# Home\n"))]
    (is (= ["clogem-blogger" "clogem-updates" "clogem-home-cats" "clogem-home-tags"]
           (section-order (aside-of ((:pages detailed) "index.html")))))
    (is (= ["clogem-blogger" "clogem-home-cats" "clogem-home-tags"]
           (section-order (aside-of ((:pages simple) "index.html"))))
        "no update bar under postList: simple")))

(deftest the-blogger-card-markup
  (let [{:keys [pages]} (build (assoc one-lang :site {:title "S" :base "/my docs/"} :theme {:blogger blogger :social social})
                               {"index.md" "# Home\n"})
        aside (aside-of (pages "index.html"))]
    (testing "a one-language site with no :nav: no drawer, so the card is not paired"
      (is (str/includes? aside "<section aria-label=\"About the author\" class=\"clogem-blogger\"><img alt=\"\" class=\"clogem-blogger__avatar\" src=\"/my%20docs/assets/%E6%88%91.png\" /><ul aria-label=\"Social links\" class=\"clogem-social\">"))
      (is (not (str/includes? (pages "index.html") "clogem-drawer"))))
    (testing "the name and the slogan are escaped text, after the links"
      (is (str/includes? aside "</ul><p class=\"clogem-blogger__name\">Jane &lt;b&gt;&amp;</p><p class=\"clogem-blogger__slogan\">Hi</p></section>")))))

(deftest the-drawer-card
  (let [{:keys [pages]} (build (assoc two-langs :theme {:blogger blogger :social social})
                               {"index.md" "---\nhome: true\n---\n\n# Home\n"
                                "01.Guide/01.t.md" (article "/pages/t00001/" "2025-01-01")})]
    (doseq [[rel html] pages
            :when (str/includes? html "id=\"clogem-drawer\"")]
      (is (re-find #"id=\"clogem-drawer\"><div class=\"clogem-blogger clogem-blogger--drawer\" hidden=\"hidden\"><img alt=\"\" class=\"clogem-blogger__avatar\"" html)
          (str rel ": first in the drawer, hidden")))
    (is (< 3 (count (filter #(str/includes? (val %) "clogem-blogger--drawer") pages))))
    (testing "on the home, the right bar's card is paired with it"
      (is (str/includes? (pages "index.html") "<section aria-label=\"About the author\" class=\"clogem-blogger clogem-blogger--paired\">")))
    (testing "identity.css shows it with scripting at 50rem and below only, and hides the paired card there"
      (let [rules (cp/parse (slurp identity-css))
            q ["(scripting: enabled) and (max-width: 50rem)"]
            of (fn [sel] (into {} (for [r rules :when (and (= q (:media r)) (some #{sel} (:selectors r))) [p v] (:decls r)] [p v])))]
        (is (= "block" ((of "#clogem-drawer > .clogem-blogger[hidden]") "display")))
        (is (= "none" ((of ".clogem-blogger--paired") "display")))
        (is (not-any? #(and (empty? (:media %)) (some (fn [s] (str/includes? s "[hidden]")) (:selectors %))) rules)
            "nowhere else is the hidden card given a display")))))

(deftest no-card-without-a-blogger
  (let [{:keys [pages]} (build (assoc two-langs :theme {:social social}) {"index.md" "# Home\n"})]
    (is (not-any? #(str/includes? (val %) "clogem-blogger") pages))
    (is (str/includes? (footer-of (pages "index.html")) "clogem-social") "the links are still in the footer")))

;; ---------------------------------------------------------------------------
;; Item 5: the footer

(deftest footer-years
  (let [files {"index.md" "# Home\n"
               "01.Guide/01.a.md" (article "/pages/a00001/" "2025-03-01")
               "01.Guide/02.b.md" (article "/pages/b00001/" "2021-03-01")}
        copy (fn [footer files]
               (second (re-find #"<p class=\"clogem-footer__copyright\">(.*?)</p>"
                                ((:pages (build (assoc one-lang :theme {:footer footer}) files)) "index.html"))))]
    (is (= "© 2019–2025 Jane" (copy {:create-year 2019 :copyright-info "Jane"} files))
        "the newest dated article's year, with an en dash")
    (is (= "© 2025" (copy {:create-year 2025} files)) "equal years: one")
    (is (= "© 2019–2030" (copy {:create-year 2019 :end-year 2030} files)) ":end-year wins")
    (is (= "© 2019" (copy {:create-year 2019} {"index.md" "# Home\n" "01.Guide/01.a.md" (article "/pages/a00001/" nil)}))
        "no dated article: only the create year")
    (is (= "© 2025 Jane" (copy {:copyright-info "Jane"} files)) "the info alone still gets the year")
    (is (nil? (copy {:powered-by true} files)) "no copyright line without a year or info key")))

(deftest footer-copyright-info-per-language-and-base
  (let [{:keys [pages]} (build (assoc two-langs :site {:title "S" :base "/my docs/"}
                                      :theme {:footer {:create-year 2020
                                                       :copyright-info {:en "<a href=\"{{base}}about/\">Jane</a> {{other}}"
                                                                        :zh-Hans ""}}})
                               {"index.md" "# Home\n"})]
    (is (str/includes? (footer-of (pages "index.html"))
                       "<p class=\"clogem-footer__copyright\">© 2020 <a href=\"/my%20docs/about/\">Jane</a> {{other}}</p>")
        "trusted HTML, {{base}} replaced, other {{…}} left alone")
    (is (str/includes? (footer-of (pages "zh-Hans/index.html")) "<p class=\"clogem-footer__copyright\">© 2020</p>")
        "\"\" suppresses it in that language only")))

(deftest footer-powered-by
  (let [{:keys [pages]} (build {} {"index.md" "# Home\n"})]
    (doseq [[rel want] [["index.html" "Powered by <a href=\"https://github.com/EchoJustus/clogem-press\">clogem-press</a>"]
                        ["zh-Hans/index.html" "由 <a href=\"https://github.com/EchoJustus/clogem-press\">clogem-press</a> 驱动"]
                        ["zh-Hant/index.html" "由 <a href=\"https://github.com/EchoJustus/clogem-press\">clogem-press</a> 驅動"]
                        ["ms/index.html" "Dikuasakan oleh <a href=\"https://github.com/EchoJustus/clogem-press\">clogem-press</a>"]
                        ["ta/index.html" "<a href=\"https://github.com/EchoJustus/clogem-press\">clogem-press</a> மூலம் இயக்கப்படுகிறது"]]]
      (is (str/includes? (footer-of (pages rel)) (str "<p class=\"clogem-footer__powered\">" want "</p>")) rel)
      (is (not (str/includes? (footer-of (pages rel)) "0.2.0")) "the version leaves the footer")
      (is (str/includes? (pages rel) "<meta content=\"clogem-press 0.2.0\" name=\"generator\" />") "…and stays in the head")))
  (testing ":powered-by false and nothing else: no footer at all"
    (let [{:keys [pages]} (build (assoc one-lang :theme {:footer {:powered-by false}}) {"index.md" "# Home\n"})]
      (is (not (str/includes? (pages "index.html") "clogem-footer")))
      (is (re-find #"</div><button aria-label=\"Back to top\" class=\"clogem-back-to-top\"" (pages "index.html"))))))

;; ---------------------------------------------------------------------------
;; Item 6: the home banner

(defn- hero-of [html] (re-find #"(?s)<div class=\"clogem-hero[^\"]*\"[^>]*>.*?</div>" html))

(deftest the-hero
  (let [posts (into {} (for [i (range 1 5)]
                         [(format "_posts/p%d.md" i) (article (format "/pages/p0000%d/" i) (format "2025-01-0%d" i))]))
        fm (fn [& kvs] (str "---\nhome: true\npostList: detailed\n" (apply str kvs) "---\n"))
        b (fn [edn index] (build (merge one-lang {:theme {:per-page 2}} edn) (assoc posts "index.md" index)))]
    (testing "heroText is the h1 of a body-less home; one h1; only on page 1"
      (let [{:keys [pages]} (b {} (fm "heroText: Hello\ntagline: Tag <i>\nactionText: Go\nactionLink: /pages/p00001/\nheroImage: assets/h.png\n"))
            home (pages "index.html")]
        (is (str/includes? home "<div class=\"clogem-hero clogem-hero--auto\"><img alt=\"\" class=\"clogem-hero__img\" src=\"/assets/h.png\" /><h1 class=\"clogem-hero__title\">Hello</h1><p class=\"clogem-hero__tagline\">Tag &lt;i&gt;</p><a class=\"clogem-hero__action\" href=\"/pages/p00001/\">Go</a></div><div class=\"clogem-shell"))
        (is (= 1 (count (re-seq #"<h1[ >]" home))))
        (is (not (str/includes? home "<div class=\"clogem-content\">")) "the fallback title and description are gone")
        (is (contains? pages "page/2/index.html"))
        (doseq [[rel html] pages :when (str/starts-with? rel "page/")]
          (is (not (str/includes? html "clogem-hero")) rel))))
    (testing "a body with its own h1 keeps it; the hero text is a <p>"
      (let [home ((:pages (b {} (str (fm "heroText: Hello\n") "\n# Mine\n"))) "index.html")]
        (is (str/includes? home "<p class=\"clogem-hero__title\">Hello</p>"))
        (is (= 1 (count (re-seq #"<h1[ >]" home))))))
    (testing "no hero keys, no hero"
      (is (not (str/includes? ((:pages (b {} (fm "postList: detailed\n"))) "index.html") "clogem-hero"))))
    (testing "base-prefixed image and action"
      (let [home ((:pages (b {:site {:title "S" :base "/my docs/"}}
                             (fm "heroImage: /assets/h.png\nactionText: Go\nactionLink: /pages/p00001/\n"))) "index.html")]
        (is (str/includes? home "src=\"/my%20docs/assets/h.png\""))
        (is (str/includes? home "<a class=\"clogem-hero__action\" href=\"/my%20docs/pages/p00001/\">Go</a>"))))
    (testing "each bannerBg kind"
      (doseq [[v want] [["auto" "<div class=\"clogem-hero clogem-hero--auto\"><p"]
                        ["none" "<div class=\"clogem-hero clogem-hero--none\"><p"]
                        ["/assets/b.jpg" "<div class=\"clogem-hero clogem-hero--image\"><img alt=\"\" class=\"clogem-hero__bg\" src=\"/assets/b.jpg\" /><p"]
                        ["\"background: linear-gradient(red, blue)\"" "<div class=\"clogem-hero clogem-hero--custom\" style=\"background: linear-gradient(red, blue)\"><p"]]]
        (is (str/includes? ((:pages (b {} (fm "tagline: T\nbannerBg: " v "\n"))) "index.html") want) v)))
    (testing ":theme :banner-bg is the default; the front matter wins"
      (is (str/includes? ((:pages (b {:theme {:per-page 2 :banner-bg "none"}} (fm "tagline: T\n"))) "index.html")
                         "clogem-hero--none"))
      (is (str/includes? ((:pages (b {:theme {:per-page 2 :banner-bg "none"}} (fm "tagline: T\nbannerBg: auto\n"))) "index.html")
                         "clogem-hero--auto")))
    (testing "a bad bannerBg is auto, with one warning naming the home file"
      (let [{:keys [pages warnings]} (b {} (fm "tagline: T\nbannerBg: \"#ccc\"\n"))]
        (is (str/includes? (pages "index.html") "clogem-hero--auto"))
        (is (= 1 (count (filter #(str/includes? % "front matter bannerBg is \"#ccc\"") warnings))) (pr-str warnings))))
    (testing "under a background image, auto and none are transparent"
      (is (str/includes? ((:pages (b {:theme {:per-page 2 :body-bg-img "/a.png"}} (fm "tagline: T\n"))) "index.html")
                         "clogem-hero--clear")))))

(defn- doctor-warnings
  "The warning messages `bb doctor` reports for a temp site, asserting no error."
  [edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1a-doc"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str (merge {:site {:title "S"} :search {:provider :none}} edn)))
      (doseq [[rel content] files]
        (fs/create-dirs (fs/parent (fs/path dir "content" rel)))
        (spit (fs/file dir "content" rel) content))
      (let [{:keys [warnings errors]} (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                                        (cli/doctor {:site-dir (str dir) :no-write true}))]
        (is (empty? errors) (pr-str errors))
        (mapv :message warnings))
      (finally (fs/delete-tree dir)))))

(deftest a-rejected-hero-image
  (let [posts (into {} (for [i (range 1 4)]
                         [(format "_posts/p%d.md" i) (article (format "/pages/p0000%d/" i) (format "2025-01-0%d" i))]))
        fm    (fn [v] (str "---\nhome: true\npostList: detailed\nheroImage: \"" v "\"\n---\n"))
        hero-warnings (fn [ws] (filterv #(str/includes? % "front matter heroImage") ws))]
    (testing "http:, //host and a bare word: one warning each, no <img>, no error (the build helper asserts it)"
      (doseq [v ["http://insecure.example/x.png" "//host/x.png" "word"]
              :let [{:keys [pages warnings]} (build (assoc one-lang :theme {:per-page 10})
                                                    (assoc posts "index.md" (str (fm v) "\n# Home\n")))]]
        (is (= 1 (count (hero-warnings warnings))) (str v " " (pr-str warnings)))
        (is (not-any? #(str/includes? (val %) "clogem-hero__img") pages) v)
        (is (not-any? #(str/includes? (val %) (str/replace v #"^/+" "")) pages) (str v ": not written anywhere"))
        (is (not-any? #(str/includes? (val %) "clogem-hero") pages)
            (str v ": nothing else asked for a banner, so there is none"))))
    (testing "a paginated home (three pages) warns once, in build and in doctor"
      (let [edn (assoc one-lang :theme {:per-page 1})
            files (assoc posts "index.md" (fm "http://insecure.example/x.png"))
            {:keys [pages warnings]} (build edn files)]
        (is (contains? pages "page/3/index.html"))
        (is (= 1 (count (hero-warnings warnings))) (pr-str warnings))
        (is (= 1 (count (hero-warnings (doctor-warnings edn files)))))))))

(deftest action-text-alone-is-no-banner
  (let [b (fn [fm] ((:pages (build one-lang {"index.md" (str "---\nhome: true\n" fm "---\n\n# Home\n")})) "index.html"))]
    (is (not (str/includes? (b "actionText: Go\n") "clogem-hero")) "actionText without actionLink")
    (is (not (str/includes? (b "actionLink: /x/\n") "clogem-hero")) "actionLink without actionText")
    (is (str/includes? (b "actionText: Go\nactionLink: /x/\n") "<a class=\"clogem-hero__action\"") "both: a banner")))

(deftest an-inherited-hero-names-its-language
  (let [b (build two-langs {"index.md" "---\nhome: true\nheroText: Hello\ntagline: Tag\nheroImage: /h.png\nheroAlt: A cat\nactionText: Go\nactionLink: /x/\n---\n"})
        en (hero-of ((:pages b) "index.html"))
        zh (hero-of ((:pages b) "zh-Hans/index.html"))]
    (is (not (str/includes? en "lang=")) "its own language: unmarked")
    (is (str/includes? zh "<h1 class=\"clogem-hero__title\" lang=\"en\">Hello</h1>") zh)
    (is (str/includes? zh "<p class=\"clogem-hero__tagline\" lang=\"en\">Tag</p>"))
    (is (re-find #"<a class=\"clogem-hero__action\" href=\"[^\"]+\" lang=\"en\">Go</a>" zh))
    (is (str/includes? zh "<img alt=\"A cat\" class=\"clogem-hero__img\" lang=\"en\""))))

;; ---------------------------------------------------------------------------
;; Item 7: the title badge

(defn- crc [s] (.getValue (doto (java.util.zip.CRC32.) (.update (.getBytes ^String s "UTF-8")))))

(defn- badge-of [html]
  (some->> (re-find #"<h1>(<svg[^>]*clogem-title-badge[^>]*><use href=\"[^\"#]*#([^\"]+)\"|<img[^>]*clogem-title-badge[^>]*>)" html)
           ((fn [[_ whole id]] (or id whole)))))

(deftest the-title-badge
  (let [files {"index.md" "# Home\n"
               "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01" "Alpha")
               "01.Guide/01.a.zh-Hans.md" (article "/pages/a00001/" "2025-01-01" "甲")
               "01.Guide/02.b.md" (article "/pages/b00002/" "2025-01-02" "Beta")
               "01.Guide/03.c.md" (article "/pages/c00003/" "2025-01-03" "Gamma")}
        one (:pages (build two-langs files))
        two (:pages (build two-langs files))]
    (testing "chosen by the CRC32 of the identity permalink, among book-open, pencil and hash"
      (doseq [pl ["/pages/a00001/" "/pages/b00002/" "/pages/c00003/"]
              :let [rel (str (subs pl 1) "index.html")]]
        (is (= (nth ["book-open" "pencil" "hash"] (mod (crc pl) 3)) (badge-of (one rel))) rel)
        (is (= (badge-of (one rel)) (badge-of (two rel))) (str rel ": the same in every build"))))
    (testing "every language variant shows the same one"
      (is (= (badge-of (one "pages/a00001/index.html")) (badge-of (one "zh-Hans/pages/a00001/index.html")))))
    (testing "the archives page: hashed on /archives/, in every language"
      (is (= (nth ["book-open" "pencil" "hash"] (mod (crc "/archives/") 3)) (badge-of (one "archives/index.html"))))
      (is (= (badge-of (one "archives/index.html")) (badge-of (one "zh-Hans/archives/index.html")))))
    (testing "the h1's text is unchanged, and the badge is decoration"
      (is (re-find #"<h1><svg aria-hidden=\"true\" class=\"clogem-icon clogem-title-badge\" focusable=\"false\" height=\"1em\" width=\"1em\"><use href=\"[^\"]+\"></use></svg>Alpha</h1>"
                   (one "pages/a00001/index.html"))))
    (testing "no badge on catalogues, categories, tags or the home"
      (doseq [rel ["index.html" "categories/index.html" "tags/index.html"]]
        (is (not (str/includes? (one rel) "clogem-title-badge")) rel))))
  (testing ":title-badge false: exactly the old h1"
    (let [pages (:pages (build (assoc one-lang :theme {:title-badge false})
                               {"index.md" "# Home\n" "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01" "Alpha")}))]
      (is (str/includes? (pages "pages/a00001/index.html") "<h1>Alpha</h1>"))
      (is (str/includes? (pages "archives/index.html") "<h1>Archive</h1>"))
      (is (not-any? #(str/includes? (val %) "clogem-title-badge") pages))))
  (testing "custom icons: an image and a sprite icon"
    (let [pages (:pages (build (assoc one-lang :site {:title "S" :base "/b/"} :theme {:title-badge-icons ["assets/t.png"]})
                               {"index.md" "# Home\n" "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01" "Alpha")}))]
      (is (str/includes? (pages "pages/a00001/index.html") "<h1><img alt=\"\" class=\"clogem-title-badge\" src=\"/b/assets/t.png\" />Alpha</h1>")))
    (let [pages (:pages (build (assoc one-lang :theme {:title-badge-icons [:sun]})
                               {"index.md" "# Home\n" "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01" "Alpha")}))]
      (is (= "sun" (badge-of (pages "pages/a00001/index.html")))))))

;; ---------------------------------------------------------------------------
;; Item 8: the body background image

(defn- inline-scripts [html]
  (map second (re-seq #"(?s)<script>(.*?)</script>" html)))

(deftest the-body-background
  (let [files {"index.md" "# Home\n" "01.Guide/01.a.md" (article "/pages/a00001/" "2025-01-01")}
        none (build one-lang files)
        one  (build (assoc one-lang :theme {:body-bg-img "/assets/bg.png" :body-bg-img-opacity 0.3}) files)
        two  (build (assoc one-lang :site {:title "S" :base "/my docs/"}
                           :theme {:body-bg-img ["/assets/a.png" "https://img.example/b.png"] :body-bg-img-interval 2})
                    files)]
    (testing "one image: an <img> before back to top, no script, no bodybg.js"
      (doseq [[rel html] (:pages one) :when (str/includes? html "clogem-navbar")]
        (is (str/includes? html "<img alt=\"\" aria-hidden=\"true\" class=\"clogem-body-bg\" data-o=\"3\" decoding=\"async\" fetchpriority=\"low\" src=\"/assets/bg.png\" /><button aria-label=\"Back to top\" class=\"clogem-back-to-top\"")
            rel)
        (is (not (str/includes? html "bodybg.js")) rel))
      (is (not (contains? (:dist one) "clogem/js/bodybg.js")))
      (is (contains? (:dist one) "clogem/css/identity.css")))
    (testing "two images: the URLs, base-prefixed, and the script on every page"
      (is (contains? (:dist two) "clogem/js/bodybg.js"))
      (doseq [[rel html] (:pages two) :when (str/includes? html "clogem-navbar")
              :let [[_ attrs] (re-find #"<img ([^>]*class=\"clogem-body-bg\"[^>]*)>" html)]]
        (is (re-find #"<script defer=\"defer\" src=\"/my%20docs/clogem/js/bodybg\.js\?v=[0-9a-f]+\"></script>" html) rel)
        (is (= ["/my%20docs/assets/a.png" "https://img.example/b.png"]
               (json/parse-string (str/replace (second (re-find #"data-clogem-bg=\"([^\"]+)\"" attrs)) "&quot;" "\"")))
            rel)
        (is (str/includes? attrs "data-interval=\"2\"") rel)
        (is (str/includes? attrs "src=\"/my%20docs/assets/a.png\"") rel)))
    (testing "no inline script is added"
      (is (= (map inline-scripts (vals (:pages none))) (map inline-scripts (vals (:pages one))))))
    (testing "bodybg.js honours reduced motion and a hidden page"
      (let [js (slurp bodybg-js)]
        (is (str/includes? js "matchMedia(\"(prefers-reduced-motion: reduce)\")"))
        (is (str/includes? js "if (still.matches || document.hidden) return;"))))))

;; ---------------------------------------------------------------------------
;; Item 12: the demo

(defn- build-demo
  "{rel-path → html} of the demo, built (no front-matter writes) with `overrides`."
  [overrides]
  (let [out (fs/create-temp-dir {:prefix "clogem-e1a-demo"})]
    (try
      (let [cfg (first (diag/collecting
                        (config/load-config "examples/demo-site" nil
                                            (merge-with merge {:build {:out (str out)}
                                                               :content {:write-front-matter false}}
                                                        overrides))))
            [model _] (diag/collecting (cli/analyse cfg))]
        (diag/collecting ((requiring-resolve 'clogem.render/build!) cfg model))
        {:pages (into (sorted-map)
                      (for [p (fs/glob out "**.html")]
                        [(str (fs/relativize out p)) (slurp (fs/file p))]))
         :files (set (map #(str (fs/relativize out %)) (fs/glob out "**")))})
      (finally (fs/delete-tree out)))))

(deftest the-demo-shows-the-identity
  (let [{:keys [pages]} (build-demo {})
        zh (pages "zh-Hans/index.html")]
    (testing "the en home's banner on / only: no heroText, so the body's h1 stays the one h1"
      (is (str/includes? (pages "index.html") "<div class=\"clogem-hero clogem-hero--auto\"><img alt=\"\" class=\"clogem-hero__img\" src=\"/assets/demo.png\" /><p class=\"clogem-hero__tagline\">"))
      (is (str/includes? (pages "index.html") "<a class=\"clogem-hero__action\" href=\"/pages/559f0f/\">Read the guide</a>"))
      (is (re-find #"<h1 id=\"clogem-press-demo-site\">" (pages "index.html")))
      (is (contains? pages "page/2/index.html"))
      (doseq [[rel html] pages :when (re-find #"(^|/)page/\d+/index\.html$" rel)]
        (is (not (str/includes? html "clogem-hero")) rel))
      (doseq [[rel html] pages :when (str/includes? html "clogem-navbar")]
        (is (= 1 (count (re-seq #"<h1[ >]" html))) rel)))
    (testing "P4-E1a.1 item 5: /ms/ inherits index.md's hero, marked lang=\"en\"; zh-Hans has its own file and no hero keys"
      (let [ms (hero-of (pages "ms/index.html"))]
        (is (str/includes? ms "<p class=\"clogem-hero__tagline\" lang=\"en\">A five-language fixture") ms)
        (is (re-find #"<a class=\"clogem-hero__action\" href=\"[^\"]+\" lang=\"en\">Read the guide</a>" ms))
        (is (not (str/includes? (hero-of (pages "index.html")) "lang=")))
        (is (not (str/includes? zh "clogem-hero")) "zh-Hans: no banner, so nothing to mark")))
    (testing "the zh-Hans home's right bar: blogger, categories, tags (postList: simple)"
      (is (= ["clogem-blogger" "clogem-home-cats" "clogem-home-tags"] (section-order (aside-of zh))))
      (is (str/includes? (aside-of zh) "<p class=\"clogem-blogger__name\">clogem-press 演示</p>")))
    (testing "no card in the en home's main; a hidden one in every drawer"
      (is (not (str/includes? (re-find #"(?s)<main.*?</main>" (pages "index.html")) "clogem-blogger")))
      (doseq [[rel html] pages :when (str/includes? html "id=\"clogem-drawer\"")]
        (is (str/includes? html "id=\"clogem-drawer\"><div class=\"clogem-blogger clogem-blogger--drawer\" hidden=\"hidden\">") rel)))
    (testing "the footer: links, © 2024–<newest>, powered by; still a <p> for custom.css"
      (let [f (footer-of (pages "index.html"))]
        (is (re-find #"<p class=\"clogem-footer__copyright\">© 2024–20\d\d <a href=\"/pages/559f0f/\">clogem-press contributors</a></p>" f) f)
        (is (str/includes? f "#brand-github"))
        (is (str/includes? f "#brand-mastodon"))
        (is (str/includes? f "href=\"/feed.xml\" rel=\"me\""))))
    (testing "every title badge is the sprite's"
      (is (every? #(re-find #"#(book-open|pencil|hash)\"" %)
                  (keep #(re-find #"<svg[^>]*clogem-title-badge[^>]*><use href=\"[^\"]+\"" (val %)) pages)))))
  (testing "under CI's --base /clogem-demo/, every new site-path link resolves"
    (let [{:keys [pages files]} (build-demo {:site {:base "/clogem-demo/"}})
          home (pages "index.html")
          found (for [re [#"class=\"clogem-hero__img\" src=\"([^\"]+)\""
                          #"class=\"clogem-hero__action\" href=\"([^\"]+)\""
                          #"class=\"clogem-blogger__avatar\" src=\"([^\"]+)\""
                          #"<a [^>]*href=\"(/[^\"]+)\" rel=\"me\""
                          #"clogem-footer__copyright\">© [^<]*<a href=\"([^\"]+)\""]]
                  (map second (re-seq re home)))
          links (apply concat found)]
      (is (every? seq found) "the hero image and action, the avatar, the feed link and {{base}}")
      (doseq [l links
              :let [rel (subs l (count "/clogem-demo/"))]]
        (is (str/starts-with? l "/clogem-demo/") l)
        (is (or (contains? files rel) (contains? files (str rel "index.html"))) l)))))

(deftest result-titles-stay-badge-free-under-real-pagefind
  (if-let [real (System/getenv "CLOGEM_PAGEFIND")]
    (let [site (fs/create-temp-dir {:prefix "clogem-e1a-pf"})]
      (try
        (fs/copy-tree "examples/demo-site" site)
        (binding [diag/*sink* (atom [])
                  search/*env* {"CLOGEM_PAGEFIND" real}]
          (let [out (fs/path site "dist")
                _ (with-out-str (cli/build {:site-dir (str site) :out (str out) :no-write true}))
                frags (fake/read-fragments out)
                h1-text (fn [url]
                          (let [f (fs/file (fs/path out (str (subs url 1) "index.html")))
                                html (slurp f)
                                h1 (re-find #"<h1>.*?</h1>" html)]
                            ;; a catalogue's h1 is clogem's own: no badge, as in vdoing
                            [(= (boolean (re-find #"clogem-title-badge" h1))
                                (not (str/includes? html "clogem-catalogue\"")))
                             (-> h1 (str/replace #"<span class=\"clogem-title-tag\".*?</span>" "")
                                 (str/replace #"<[^>]+>" "") (str/replace "&amp;" "&"))]))]
            (is (< 20 (count frags)))
            (doseq [{:keys [url meta]} frags
                    :let [[badge? text] (h1-text url)]]
              (is badge? (str url ": an article carries a badge, a catalogue none"))
              (is (= text (:title meta)) (str url ": the result title is the h1's text alone")))))
        (finally (fs/delete-tree site))))
    (do (is (nil? (System/getenv "GITHUB_ACTIONS")) "CI sets CLOGEM_PAGEFIND, so this always runs there")
        (println "result-titles-stay-badge-free-under-real-pagefind: skipped — CLOGEM_PAGEFIND is not set"))))
