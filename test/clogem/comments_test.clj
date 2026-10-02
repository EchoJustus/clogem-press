;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.comments-test
  "giscus comments (DESIGN.md §6.8, D-P3-15): one thread per article
  identity, asserted on the emitted HTML and on config validation."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- variant [title & [fm]]
  (str "---\ntitle: " title "\n" fm "---\n\n" title " body.\n"))

(def ^:private corpus
  {"01.Guide/01.t.md"         "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\nBody.\n"
   "01.Guide/01.t.zh-Hans.md" (variant "简")
   "01.Guide/01.t.zh-Hant.md" (variant "繁")
   "01.Guide/01.t.ms.md"      (variant "Tajuk")
   "01.Guide/01.t.ta.md"      (variant "தலைப்பு")
   ;; the primary says no: no variant gets a widget
   "01.Guide/02.quiet.md"         "---\ntitle: Quiet\npermalink: /pages/q00001/\ncomment: false\n---\n\nQ.\n"
   "01.Guide/02.quiet.zh-Hans.md" (variant "静")
   ;; only a non-primary says no: the primary decides, so all get one
   "01.Guide/03.loud.md"         "---\ntitle: Loud\npermalink: /pages/l00001/\n---\n\nL.\n"
   "01.Guide/03.loud.zh-Hans.md" (variant "响" "comment: false\n")
   "_posts/2026-03-01-post.md"   "---\ntitle: A post\ndate: \"2026-03-01 00:00:00\"\npermalink: /pages/p00001/\n---\n\nP.\n"
   "00.Catalogue/01.Guide.md"
   (str "---\ntitle: Guide catalogue\npermalink: /pages/c00001/\n"
        "pageComponent:\n  name: Catalogue\n  data:\n    path: 01.Guide\n---\n\nbody\n")})

(def ^:private giscus
  {:provider :giscus :repo "o/r" :repo-id "R_1" :category "Announcements" :category-id "DIC_1"})

(def ^:private site {:site {:title "S" :url "https://s.example"} :comments giscus})

(defn- with-built
  [files site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-comments"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (binding [diag/*sink* (atom [])]
        (with-out-str (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist")) :no-write true}))
        (f (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- html [out uri]
  (let [rel (str/replace uri #"^/+|/+$" "")]
    (slurp (fs/file (if (str/blank? rel) (fs/path out "index.html") (fs/path out rel "index.html"))))))

(defn- giscus-tags [h]
  (re-seq #"<script [^>]*src=\"https://giscus\.app/client\.js\"[^>]*></script>" h))

(defn- attr [tag k]
  (second (re-find (re-pattern (str "\\s" k "=\"([^\"]*)\"")) tag)))

(defn- load-cfg [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-comments-cfg"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; The widget

(deftest every-variant-opens-the-same-thread
  (with-built corpus site
    (fn [out]
      (doseq [[uri lang] [["/pages/t00001/" "en"] ["/zh-Hans/pages/t00001/" "zh-CN"]
                          ["/zh-Hant/pages/t00001/" "zh-TW"] ["/ms/pages/t00001/" "en"]
                          ["/ta/pages/t00001/" "en"]]
              :let [h (html out uri) tags (giscus-tags h)]]
        (testing uri
          (is (= 1 (count tags)) "exactly one client script")
          (is (= "/pages/t00001/" (attr (first tags) "data-term"))
              "the identity — no language prefix, identical on every variant")
          (is (= lang (attr (first tags) "data-lang"))
              "the page locale's :giscus; ms and ta are en, which giscus routes")
          (is (str/includes? h "<section class=\"clogem-comments\"><h2>")
              "a container with a heading in the page's language")
          (is (str/includes? h "<div class=\"giscus\"></div><script "))))
      (is (str/includes? (html out "/zh-Hant/pages/t00001/") "<section class=\"clogem-comments\"><h2>留言</h2>")
          "the heading is :comments/title in the page's language"))))

(defn- attrs
  "Every attribute of a tag as {name value} — compared as a map, since the
  bundled hiccup's attribute order differs between babashka versions."
  [tag]
  (into {} (map (fn [[_ k v]] [k v])) (re-seq #"\s([a-z-]+)=\"([^\"]*)\"" tag)))

(deftest the-script-carries-exactly-the-d-p3-15-attributes
  (with-built corpus site
    (fn [out]
      (let [tag (first (giscus-tags (html out "/zh-Hans/pages/t00001/")))]
        (is (= {"src" "https://giscus.app/client.js"
                "data-repo" "o/r" "data-repo-id" "R_1"
                "data-category" "Announcements" "data-category-id" "DIC_1"
                "data-mapping" "specific" "data-term" "/pages/t00001/"
                "data-strict" "1" "data-reactions-enabled" "1" "data-emit-metadata" "0"
                "data-input-position" "bottom" "data-theme" "preferred_color_scheme"
                "data-lang" "zh-CN" "data-loading" "lazy" "crossorigin" "anonymous"
                "async" "async"}
               (attrs tag))
            "these and nothing else")
        (is (str/ends-with? tag "></script>") "an empty element: no inline HTML comments")))))

(deftest only-article-pages-get-the-widget
  (with-built corpus site
    (fn [out]
      (testing "tree articles and posts"
        (is (= 1 (count (giscus-tags (html out "/pages/p00001/")))) "a post"))
      (testing "never a catalogue, a home or an index page"
        (doseq [uri ["/pages/c00001/" "/" "/zh-Hans/" "/categories/" "/tags/" "/archives/"
                     "/zh-Hans/archives/" "/categories/guide/"]
                :let [h (html out uri)]]
          (is (empty? (giscus-tags h)) uri)
          (is (not (str/includes? h "comments.js")) uri))))))

(deftest the-primary-variant-decides-comment-false
  (with-built corpus site
    (fn [out]
      (testing "`comment: false` on the primary: no variant has a widget"
        (doseq [uri ["/pages/q00001/" "/zh-Hans/pages/q00001/"]
                :let [h (html out uri)]]
          (is (empty? (giscus-tags h)) uri)
          (is (not (str/includes? h "clogem-comments")) uri)
          (is (not (str/includes? h "comments.js")) uri)))
      (testing "on a non-primary variant only: every variant keeps it (doctor warns)"
        (doseq [uri ["/pages/l00001/" "/zh-Hans/pages/l00001/"]]
          (is (= 1 (count (giscus-tags (html out uri)))) uri))))))

(deftest the-term-carries-no-base
  (with-built corpus (assoc-in site [:site :base] "/b/")
    (fn [out]
      (doseq [uri ["/pages/t00001/" "/zh-Hans/pages/t00001/"]]
        (is (= "/pages/t00001/" (attr (first (giscus-tags (html out uri))) "data-term")) uri))
      (is (str/includes? (html out "/pages/t00001/") "<script defer=\"defer\" src=\"/b/clogem/js/comments.js\"></script>")))))

(deftest the-term-is-the-permalink-under-prefix-default
  (with-built corpus (assoc site :i18n {:prefix-default? true})
    (fn [out]
      (doseq [uri ["/en/pages/t00001/" "/zh-Hans/pages/t00001/"]]
        (is (= "/pages/t00001/" (attr (first (giscus-tags (html out uri))) "data-term")) uri))
      (is (empty? (giscus-tags (html out "/pages/t00001/"))) "the redirect stub has none"))))

(deftest data-theme-follows-the-default-mode
  (doseq [[mode theme] [[:auto "preferred_color_scheme"] [:light "light"] [:dark "dark"]]]
    (with-built (select-keys corpus ["01.Guide/01.t.md"]) (assoc site :theme {:default-mode mode})
      (fn [out]
        (is (= theme (attr (first (giscus-tags (html out "/pages/t00001/"))) "data-theme")) (str mode))))))

(deftest comments-js-ships-only-with-giscus
  (with-built corpus site
    (fn [out]
      (is (fs/exists? (fs/path out "clogem" "js" "comments.js")))
      (is (str/includes? (html out "/pages/t00001/") "<script defer=\"defer\" src=\"/clogem/js/comments.js\"></script>"))))
  (with-built corpus (dissoc site :comments)
    (fn [out]
      (is (not (fs/exists? (fs/path out "clogem" "js" "comments.js"))))
      (doseq [f (fs/glob out "**.html")]
        (is (not (re-find #"giscus|comments\.js|clogem-comments" (slurp (fs/file f)))) (str f))))))

(deftest the-theme-hook-posts-to-giscus-only
  (testing "window.clogem.setCommentsTheme posts {giscus: {setConfig: {theme}}}
            to the giscus iframe, targeting https://giscus.app (the behaviour
            was checked once in Chromium against a stub iframe)"
    (let [js (slurp (fs/file "src/clogem/theme/resources/js/comments.js"))]
      (is (str/includes? js "var ORIGIN = \"https://giscus.app\";"))
      (is (str/includes? js "ns.setCommentsTheme = function (theme)"))
      (is (str/includes? js "document.querySelector(\"iframe.giscus-frame\")"))
      (is (str/includes? js "f.contentWindow.postMessage({ giscus: { setConfig: { theme: theme } } }, ORIGIN);"))
      (is (not (re-find #"postMessage\([^)]*\"\*\"" js)) "never to any origin"))))

;; ---------------------------------------------------------------------------
;; Config

(deftest the-category-name-is-required
  (let [[_ ds] (load-cfg {:comments (dissoc giscus :category)})]
    (is (some #(re-find #":comments :category is required when :provider is :giscus" (:message %))
              (diag/errors ds))))
  (doseq [k [:repo :repo-id :category-id]]
    (let [[_ ds] (load-cfg {:comments (dissoc giscus k)})]
      (is (some #(str/includes? (:message %) (str ":comments " k " is required")) (diag/errors ds)) (str k))))
  (let [[_ ds] (load-cfg {:comments giscus})]
    (is (empty? (diag/errors ds)))))

(deftest an-unknown-provider-is-a-config-error
  (let [[cfg ds] (load-cfg {:comments {:provider :disqus}})]
    (is (some #(re-find #":comments :provider is :disqus, but it must be one of :giscus, :none" (:message %))
              (diag/errors ds)))
    (is (= :none (get-in cfg [:comments :provider])) "repaired to the default")))

(deftest giscus-languages-are-its-current-routable-set
  (testing "verified on 2026-10-01: availableLanguages plus gsw, zh-Hans and zh-Hant"
    (is (= #{"ar" "be" "bg" "ca" "cs" "da" "de" "en" "eo" "es" "eu" "fa" "fr" "gr" "hbs" "he"
             "hu" "id" "it" "ja" "kh" "ko" "nl" "pl" "pt" "ro" "ru" "th" "tr" "uk" "uz" "vi"
             "zh-CN" "zh-TW" "zh-HK" "gsw" "zh-Hans" "zh-Hant"}
           config/giscus-available-languages)))
  (testing "zh-Hans and zh-Hant are now accepted; ms and ta still 404"
    (let [[_ ds] (load-cfg {:comments giscus
                            :langs {:locales {:zh-Hans {:giscus "zh-Hans"} :zh-Hant {:giscus "zh-Hant"}}}})]
      (is (empty? (diag/errors ds))))
    (doseq [l ["ms" "ta"]]
      (let [[_ ds] (load-cfg {:langs {:locales {(keyword l) {:giscus l}}}})]
        (is (some #(re-find #"not one of giscus's availableLanguages" (:message %)) (diag/errors ds)) l))))
  (testing "the defaults stay zh-CN / zh-TW, which serve the same strings"
    (is (= "zh-CN" (get-in config/default-locales [:zh-Hans :giscus])))
    (is (= "zh-TW" (get-in config/default-locales [:zh-Hant :giscus])))))
