;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.lang-test
  "The stored language preference and the \"also available\" banner
  (DESIGN.md §6.4 rules 3 and 4, D-11, D-P3-13, D-P3-14).

  These assert on the emitted HTML and config: which pages carry
  `clogem-lang-data`, what it says, the inline `:redirect` script, and where
  js/lang.js ships. What the script then does in a browser was checked once
  with Playwright (DESIGN.md §11.2), which the project does not run."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.assets :as assets]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- variant [title]
  (str "---\ntitle: " title "\n---\n\n" title " body.\n"))

(def ^:private corpus
  {"01.Guide/01.t.md"         "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\nBody.\n"
   "01.Guide/01.t.zh-Hans.md" (variant "简单的标题")
   "01.Guide/01.t.zh-Hant.md" (variant "繁體標題")
   "01.Guide/01.t.ta.md"      (variant "எளிய தலைப்பு")
   ;; one language only: nothing to offer
   "01.Guide/02.only.ta.md"   "---\ntitle: தனி\ndate: \"2026-02-02 00:00:00\"\npermalink: /pages/o00001/\n---\n\nஉரை\n"
   "01.Guide/03.u.md"         "---\ntitle: U\ndate: \"2026-02-03 00:00:00\"\npermalink: /pages/u00001/\ntags: [x]\n---\n\nU.\n"
   "01.Guide/04.v.md"         "---\ntitle: V\ndate: \"2026-02-04 00:00:00\"\npermalink: /pages/v00001/\n---\n\nV.\n"
   "00.Catalogue/01.Guide.md"
   (str "---\ntitle: Guide catalogue\npermalink: /pages/c00001/\n"
        "pageComponent:\n  name: Catalogue\n  data:\n    path: 01.Guide\n---\n\nbody\n")
   "00.Catalogue/01.Guide.zh-Hans.md" (variant "指南目录")})

(def ^:private url-site {:site {:title "S" :url "https://s.example"}
                         :theme {:per-page 2}})

(defn- with-built
  "Build `files` under a temp site with `site-edn` (and site i18n overrides
  `strings` {lang → map}), then call (f out). Diagnostics are discarded."
  [files site-edn f & {:keys [strings]}]
  (let [dir (fs/create-temp-dir {:prefix "clogem-lang"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (doseq [[l m] strings]
        (fs/create-dirs (fs/path dir "i18n"))
        (spit (fs/file dir "i18n" (str (name l) ".edn")) (pr-str m)))
      (binding [diag/*sink* (atom [])]
        (with-out-str (cli/build {:site-dir (str dir) :out (str (fs/path dir "dist")) :no-write true}))
        (f (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- html [out uri]
  (let [rel (str/replace uri #"^/+|/+$" "")]
    (slurp (fs/file (if (str/blank? rel) (fs/path out "index.html") (fs/path out rel "index.html"))))))

(defn- lang-data
  "The page's clogem-lang-data, parsed, or nil."
  [h]
  (some-> (re-find #"<script id=\"clogem-lang-data\" type=\"application/json\">(.*?)</script>" h)
          second
          (json/parse-string false)))

(def ^:private lang-js
  "The lang.js tag with its `?v=` (D-P4-7) stripped: tests compare
  `(unv html)`, so a fingerprint change does not churn them."
  "<script defer=\"defer\" src=\"/clogem/js/lang.js\"></script>")

(defn- unv [s] (assets/strip-versions s))

;; ---------------------------------------------------------------------------
;; D-P3-13: the preference and its writer

(deftest the-switcher-links-carry-their-language-code
  (with-built corpus url-site
    (fn [out]
      (let [h (html out "/pages/t00001/")]
        (testing "every switcher link names the code js/lang.js stores"
          (doseq [l ["zh-Hans" "zh-Hant" "ms" "ta"]]
            (is (re-find (re-pattern (str "<a (?:class=\"is-untranslated\" )?data-clogem-lang=\"" l "\" href=")) h) l)))
        (testing "the current language is not a link and stores nothing"
          (is (str/includes? h "<span aria-current=\"true\" class=\"is-current\" lang=\"en\">English</span>"))
          (is (not (str/includes? h "data-clogem-lang=\"en\""))))
        (testing "the switcher's links are not rewritten: a translated target is
                  its variant, an untranslated one the language's home"
          (is (str/includes? h "data-clogem-lang=\"zh-Hans\" href=\"/zh-Hans/pages/t00001/\""))
          (is (str/includes? h "data-clogem-lang=\"ms\" href=\"/ms/\"")))))))

(deftest lang-js-ships-to-every-page-of-a-multilingual-site
  (with-built corpus url-site
    (fn [out]
      (doseq [uri ["/" "/zh-Hans/" "/pages/t00001/" "/zh-Hans/pages/t00001/" "/pages/c00001/"
                   "/categories/" "/ta/tags/x/" "/archives/" "/page/2/"]]
        (is (str/includes? (unv (html out uri)) lang-js) uri))
      (is (fs/exists? (fs/path out "clogem" "js" "lang.js"))))))

(deftest a-single-language-site-ships-nothing
  (with-built {"01.Guide/01.t.md" "---\ntitle: T\npermalink: /pages/t00001/\n---\n\nBody.\n"}
    (merge url-site {:langs {:locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}})
    (fn [out]
      (doseq [uri ["/" "/pages/t00001/" "/categories/"]
              :let [h (html out uri)]]
        (is (not (str/includes? h "lang.js")) uri)
        (is (not (str/includes? h "clogem-lang-data")) uri)
        (is (not (str/includes? h "data-clogem-lang")) uri))
      (is (not (fs/exists? (fs/path out "clogem" "js" "lang.js"))))
      (is (fs/exists? (fs/path out "clogem" "js" "toc.js")) "the other scripts still ship"))))

(deftest lang-js-never-seeds-or-rewrites
  (testing "the switcher is the only writer, nothing reads navigator.languages,
            and no link is rewritten (DESIGN §11.2: the rule-3 narrowing)"
    (let [js (-> (slurp (fs/file "src/clogem/theme/resources/js/lang.js"))
                 ;; the code, not the header comment that names what it avoids
                 (str/replace #"(?s)/\*.*?\*/" ""))]
      (is (= 1 (count (re-seq #"write\(PREF" js))))
      (is (not (re-find #"navigator\s*\.\s*language" js)))
      (is (not (re-find #"\.href\s*=" (str/replace js "link.href = t.url" ""))))
      (is (str/includes? js "\"clogem-lang\""))
      (is (str/includes? js "\"clogem-banner-dismissed\""))
      (is (str/includes? js "var KEEP = 100"))
      (testing "every storage access is guarded"
        (is (= 2 (count (re-seq #"window\.localStorage" js))))
        (is (= 2 (count (re-seq #"try \{ (?:return )?window\.localStorage\.(?:get|set)Item" js))))))))

;; ---------------------------------------------------------------------------
;; D-P3-14: the banner data

(deftest the-banner-data-is-on-bare-urls-only
  (with-built corpus url-site
    (fn [out]
      (testing "an article at its identity URL offers its other languages"
        (let [d (lang-data (html out "/pages/t00001/"))]
          (is (= {"id" "/pages/t00001/" "lang" "en" "mode" "banner"}
                 (dissoc d "alternates")))
          (is (= ["ta" "zh-Hans" "zh-Hant"] (sort (keys (get d "alternates"))))
              "the page's hreflang set minus its own language: no ms, which it lacks")))
      (testing "a catalogue at its identity URL, a home and an index overview at their bare paths"
        (is (= ["zh-Hans"] (keys (get (lang-data (html out "/pages/c00001/")) "alternates"))))
        (is (= "/pages/c00001/" (get (lang-data (html out "/pages/c00001/")) "id")))
        (doseq [uri ["/" "/categories/" "/tags/" "/archives/"]
                :let [d (lang-data (html out uri))]]
          (is (= ["ms" "ta" "zh-Hans" "zh-Hant"] (sort (keys (get d "alternates")))) uri)
          (is (= uri (get d "id")) uri)))
      (testing "never on a prefixed URL, so a :redirect cannot loop"
        (doseq [uri ["/zh-Hans/pages/t00001/" "/ta/pages/t00001/" "/zh-Hans/" "/ta/"
                     "/zh-Hans/categories/" "/zh-Hans/pages/c00001/"]]
          (is (nil? (lang-data (html out uri))) uri)))
      (testing "nor on a filtered list or a page past the first"
        (doseq [uri ["/tags/x/" "/categories/guide/" "/page/2/" "/categories/page/2/"]]
          (is (nil? (lang-data (html out uri))) uri)))
      (testing "nor where there is nothing to offer"
        (is (nil? (lang-data (html out "/pages/o00001/"))) "the Tamil-only article")
        (is (nil? (lang-data (html out "/pages/u00001/"))) "an English-only article")))))

(deftest the-banner-speaks-the-readers-chosen-language
  (testing "each entry is written in ITS language, {{lang}} being its own label —
            the one deliberate exception to §6.4 rule 2"
    (with-built corpus url-site
      (fn [out]
        (let [a (get (lang-data (html out "/pages/t00001/")) "alternates")]
          (is (= {"url" "/zh-Hans/pages/t00001/" "lang" "zh-Hans" "dir" "ltr"
                  "available" "本页也有简体中文版本。" "read" "阅读简体中文版 →" "dismiss" "关闭"}
                 (get a "zh-Hans")))
          (is (= {"url" "/zh-Hant/pages/t00001/" "lang" "zh-Hant" "dir" "ltr"
                  "available" "本頁也有繁體中文版本。" "read" "閱讀繁體中文版 →" "dismiss" "關閉"}
                 (get a "zh-Hant")))
          (is (= "இந்தப் பக்கம் தமிழ் மொழியிலும் உள்ளது." (get-in a ["ta" "available"])))
          (is (= "/ta/pages/t00001/" (get-in a ["ta" "url"]))))
        (is (= "Halaman ini juga tersedia dalam Bahasa Melayu."
               (get-in (lang-data (html out "/")) ["alternates" "ms" "available"])))))))

(deftest the-html-lang-and-a-custom-label-are-used
  (with-built corpus (assoc-in url-site [:langs :locales :zh-Hant] {:label "正體" :html-lang "zh-TW" :giscus "zh-TW"})
    (fn [out]
      (let [e (get-in (lang-data (html out "/pages/t00001/")) ["alternates" "zh-Hant"])]
        (is (= "zh-TW" (get e "lang")) "the note's lang= is the locale's :html-lang")
        (is (= "本頁也有正體版本。" (get e "available")))
        (is (= "/zh-Hant/pages/t00001/" (get e "url")) "the URL keeps the language code")))))

(deftest a-site-override-cannot-end-the-data-script
  (let [evil "</script><script>alert(1)</script><!-- & \u2028"]
    (with-built corpus url-site
      (fn [out]
        (let [h (html out "/pages/t00001/")
              body (second (re-find #"<script id=\"clogem-lang-data\" type=\"application/json\">(.*?)</script>" h))]
          (is (not (str/includes? h "<script>alert(1)")))
          (is (not (str/includes? body "<")))
          (is (not (str/includes? body "&")))
          (is (str/includes? body "\\u003c/script\\u003e"))
          (is (= (str evil "!") (get-in (json/parse-string body false) ["alternates" "zh-Hans" "available"]))
              "escaped, not altered: JSON.parse gives the override back")))
      :strings {:zh-Hans {:banner/available (str evil "!")}})))

(deftest the-banner-data-follows-the-base
  (with-built corpus (assoc-in url-site [:site :base] "/b/")
    (fn [out]
      (let [d (lang-data (html out "/pages/t00001/"))]
        (is (= "/pages/t00001/" (get d "id")) "an article's key is its permalink")
        (is (= "/b/zh-Hans/pages/t00001/" (get-in d ["alternates" "zh-Hans" "url"]))))
      (is (= "/b/" (get (lang-data (html out "/")) "id")))
      (is (= "/b/ms/" (get-in (lang-data (html out "/")) ["alternates" "ms" "url"])))
      (is (re-find #"<script defer=\"defer\" src=\"/b/clogem/js/lang\.js\?v=[0-9a-f]{8}\"></script>" (html out "/"))))))

(deftest the-banner-data-is-percent-encoded
  (with-built {"01.Guide/01.t.md" "---\ntitle: T\npermalink: /pages/中文/\n---\n\nBody.\n"
               "01.Guide/01.t.zh-Hans.md" (variant "中")}
    url-site
    (fn [out]
      (let [d (lang-data (html out "/pages/中文/"))]
        (is (= "/pages/中文/" (get d "id")))
        (is (= "/zh-Hans/pages/%E4%B8%AD%E6%96%87/" (get-in d ["alternates" "zh-Hans" "url"])))))))

(deftest prefix-default-pages-are-never-bare
  (with-built corpus (assoc url-site :i18n {:prefix-default? true})
    (fn [out]
      (testing "every article variant is prefixed, and the bare URL is a stub"
        (doseq [uri ["/en/pages/t00001/" "/zh-Hans/pages/t00001/"]]
          (is (nil? (lang-data (html out uri))) uri))
        (is (not (str/includes? (html out "/pages/t00001/") "clogem-lang"))))
      (testing "homes and index overviews stay bare for the default language"
        (is (some? (lang-data (html out "/"))))))))

;; ---------------------------------------------------------------------------
;; :redirect and :ignore

(deftest preference-redirect-is-an-inline-head-script-on-bare-urls
  (with-built corpus (assoc url-site :i18n {:preference :redirect})
    (fn [out]
      (let [h (html out "/pages/t00001/")
            d (lang-data h)
            head (second (re-find #"(?s)<head>(.*)</head>" h))]
        (is (= "redirect" (get d "mode")))
        (is (= {"url" "/zh-Hans/pages/t00001/" "lang" "zh-Hans"} (get-in d ["alternates" "zh-Hans"]))
            "no banner strings: nothing is shown")
        (is (re-find #"<script id=\"clogem-lang-data\"[^>]*>.*?</script><script>\(function\(\)\{try\{var p=localStorage.getItem\(\"clogem-lang\"\)" head)
            "inline, in <head>, right after the data it reads")
        (is (str/includes? head "Object.prototype.hasOwnProperty.call(a,p))location.replace(a[p].url)")
            "only to a language in the page's set; never `__proto__`")
        (is (str/includes? head "p!==d.lang") "a preference equal to the page's language stays")
        (is (str/includes? head "catch(e){}") "blocked storage leaves the page alone")
        (is (str/includes? (unv head) lang-js) "the switcher still stores the choice"))
      (testing "a prefixed URL never redirects"
        (doseq [uri ["/zh-Hans/pages/t00001/" "/ta/" "/zh-Hans/categories/" "/tags/x/"]]
          (is (not (str/includes? (html out uri) "location.replace")) uri))))))

(deftest preference-ignore-shows-nothing-but-still-stores
  (with-built corpus (assoc url-site :i18n {:preference :ignore})
    (fn [out]
      (doseq [uri ["/" "/pages/t00001/" "/categories/"]
              :let [h (html out uri)]]
        (is (not (str/includes? h "clogem-lang-data")) uri)
        (is (not (str/includes? h "location.replace")) uri)
        (is (str/includes? (unv h) lang-js) uri)
        (is (str/includes? h "data-clogem-lang=\"zh-Hans\"") uri)))))

(deftest the-preference-is-validated-at-config-load
  (let [dir (fs/create-temp-dir {:prefix "clogem-lang-cfg"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str {:i18n {:preference :popup}}))
      (let [[cfg ds] (diag/collecting (config/load-config (str dir) nil nil))]
        (is (some #(re-find #":i18n :preference is :popup, but it must be one of :banner, :ignore, :redirect" (:message %))
                  (diag/errors ds)))
        (is (= :banner (get-in cfg [:i18n :preference])) "repaired to the default"))
      (doseq [v [:banner :redirect :ignore]]
        (spit (fs/file dir "site.edn") (pr-str {:i18n {:preference v}}))
        (let [[cfg ds] (diag/collecting (config/load-config (str dir) nil nil))]
          (is (empty? (diag/errors ds)) (str v))
          (is (= v (get-in cfg [:i18n :preference])))))
      (spit (fs/file dir "site.edn") (pr-str {}))
      (is (= :banner (get-in (first (diag/collecting (config/load-config (str dir) nil nil))) [:i18n :preference]))
          "D-11: :banner is the default")
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; P3-C.1 follow-ups

(defn- variant-bar [h]
  (re-find #"(?s)<p class=\"clogem-variants\".*?</p>" h))

(deftest variant-bar-links-are-a-language-choice
  (testing "§11.2 item 47: every variant-bar link carries data-clogem-lang, so a
            click stores the choice — under :redirect the link to the bare
            primary used to bounce straight back to the stored language"
    (with-built corpus (assoc url-site :i18n {:preference :redirect})
      (fn [out]
        (let [zh (variant-bar (html out "/zh-Hans/pages/t00001/"))]
          (is (re-find #"<a data-clogem-lang=\"en\" href=\"/pages/t00001/\" hreflang=\"en\" lang=\"en\">English</a>" zh)
              "the link back to the bare primary")
          (is (str/includes? zh "data-clogem-lang=\"ta\" href=\"/ta/pages/t00001/\"")))
        (let [en (variant-bar (html out "/pages/t00001/"))]
          (doseq [l ["zh-Hans" "zh-Hant" "ta"]]
            (is (str/includes? en (str "data-clogem-lang=\"" l "\"")) l))))))
  (testing "lang.js stores a click on either kind of link"
    (let [js (slurp (fs/file "src/clogem/theme/resources/js/lang.js"))]
      (is (str/includes? js "t.closest(\".clogem-langs a[data-clogem-lang], .clogem-variants a[data-clogem-lang]\")")))))

(deftest the-redirect-runs-before-any-stylesheet
  (testing "a parser-blocking inline script waits for the stylesheets above it,
            so the data and the redirect come first in <head>"
    (with-built corpus (assoc url-site :i18n {:preference :redirect})
      (fn [out]
        (let [h    (html out "/pages/t00001/")
              data (str/index-of h "id=\"clogem-lang-data\"")
              redir (str/index-of h "location.replace")
              css  (str/index-of h "rel=\"stylesheet\"")]
          (is (and data redir css))
          (is (< data redir css)))))))

(deftest the-banner-carries-its-dir
  (with-built corpus (assoc-in url-site [:langs :locales :ta] {:dir :rtl})
    (fn [out]
      (let [a (get (lang-data (html out "/pages/t00001/")) "alternates")]
        (is (= "rtl" (get-in a ["ta" "dir"])) "the target locale's :dir")
        (is (= "ltr" (get-in a ["zh-Hans" "dir"]))))))
  (testing "lang.js sets it on the note"
    (is (str/includes? (slurp (fs/file "src/clogem/theme/resources/js/lang.js"))
                       "if (t.dir) note.setAttribute(\"dir\", t.dir);"))))

(def ^:private sixth-lang
  (-> url-site
      (assoc-in [:langs :locales :xx] {:label "Xxish" :html-lang "x-xx" :dir :rtl})))

(deftest a-language-without-theme-strings-is-labelled-by-the-text-shown
  (testing "a site-added language with no banner strings gets them along the
            fallback chain — in English here — and the note says so with
            lang/dir of English, not of the target"
    (with-built (assoc corpus "01.Guide/01.t.xx.md" (variant "Xx title")) sixth-lang
      (fn [out]
        (let [e (get-in (lang-data (html out "/pages/t00001/")) ["alternates" "xx"])]
          (is (= "/xx/pages/t00001/" (get e "url")))
          (is (= "en" (get e "lang")))
          (is (= "ltr" (get e "dir")))
          (is (= "This page is also available in Xxish." (get e "available")))))))
  (testing "with site strings of its own, the note is in that language"
    (with-built (assoc corpus "01.Guide/01.t.xx.md" (variant "Xx title")) sixth-lang
      (fn [out]
        (let [e (get-in (lang-data (html out "/pages/t00001/")) ["alternates" "xx"])]
          (is (= "x-xx" (get e "lang")))
          (is (= "rtl" (get e "dir")))
          (is (= "Xx {{lang}}!" (str/replace (get e "available") "Xxish" "{{lang}}")))))
      :strings {:xx {:banner/available "Xx {{lang}}!" :banner/read "R" :banner/dismiss "D"}})))

(deftest dismissing-the-banner-keeps-focus-in-the-page
  (testing "focus moves to the main column before the note is removed, so a
            keyboard reader is not dropped back to <body>"
    (let [js  (slurp (fs/file "src/clogem/theme/resources/js/lang.js"))
          css (slurp (fs/file "src/clogem/theme/resources/css/theme.css"))
          focus  (str/index-of js "main.focus();")
          remove (str/index-of js "note.parentNode.removeChild(note)")]
      (is (str/includes? js "main.setAttribute(\"tabindex\", \"-1\")"))
      (is (and focus remove (< focus remove)))
      (is (str/includes? css ".clogem-main[tabindex=\"-1\"]:focus { outline: none; }")))))

(deftest a-long-label-wraps-inside-the-banner
  (testing "the text may shrink and its link may break: \"Bahasa Melayu
            (Malaysia, Singapura)\" overflowed a 360 px viewport by 55 px"
    (let [css (slurp (fs/file "src/clogem/theme/resources/css/theme.css"))]
      (is (str/includes? css ".clogem-lang-banner__text { flex: 1; min-width: 0; }"))
      (is (str/includes? css ".clogem-lang-banner__text a { overflow-wrap: anywhere; }"))
      (is (not (re-find #"clogem-lang-banner[^{]*\{[^}]*nowrap" css)))
      (testing "and the navbar switcher listing the same label wraps too"
        (is (re-find #"\.clogem-langs ul \{[^}]*flex-wrap: wrap" css))
        (is (re-find #"\.clogem-langs \{[^}]*min-width: 0" css))))))
