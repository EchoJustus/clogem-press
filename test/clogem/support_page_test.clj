;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
;;
;; Parts of this file (`git!`, `stand-in!` and the hook that refuses one push)
;; are adapted from test/portal_test.clj of EchoJustus/clogem-support, under
;; the MIT License:
;;
;;   Copyright 2026 EchoJustus
;;
;;   Permission is hereby granted, free of charge, to any person obtaining a
;;   copy of this software and associated documentation files (the
;;   "Software"), to deal in the Software without restriction, including
;;   without limitation the rights to use, copy, modify, merge, publish,
;;   distribute, sublicense, and/or sell copies of the Software, and to
;;   permit persons to whom the Software is furnished to do so, subject to
;;   the following conditions:
;;
;;   The above copyright notice and this permission notice shall be included
;;   in all copies or substantial portions of the Software.
;;
;;   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
;;   OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
;;   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
;;   IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
;;   CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
;;   TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
;;   SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
(ns clogem.support-page-test
  "The support page (DESIGN.md §7.5): `bb support-page`
  (support/support_page.clj) from support/i18n/*.edn, five pages in five
  languages, each held to the hub's rules for its pages (clogem-support's
  test/portal_test.clj): no script, nothing loaded from elsewhere, its CSP,
  the switchers, contrast, its sections and words, its size; the languages
  kept in step (the same sections, links and commands as English); and
  .github/workflows/support-page.yml, whose publishing step is run here
  against a local stand-in for clogem-support's gh-pages."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [clogem.css-palette :as cp]
            [support-page :as sp])
  (:import (java.net URI)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def langs
  "The page's languages, in the switcher's order (DESIGN.md §7.5): English
  first, the default; Traditional Chinese before Simplified (the owner's
  order, SP.6)."
  ["en" "zh-Hant" "zh-Hans" "pt-BR" "es-ES"])

(def base "https://echojustus.github.io/clogem-support/clogem-press/")
(defn- url-of [lang] (if (= lang "en") base (str base lang "/")))
(defn- path-of [lang] (if (= lang "en") "index.html" (str lang "/index.html")))
(def ^:private translations (rest langs))

(def ^:private wenkai-langs
  "The pages set in LXGW WenKai (the owner, 2026-10-26); pt-BR and es-ES
  keep the system's fonts and load none."
  #{"en" "zh-Hant" "zh-Hans"})
(def ^:private system-langs ["pt-BR" "es-ES"])

(def ^:private words
  "Each language's words, read here, not through the generator."
  (into {} (for [l langs] [l (edn/read-string (slurp (str "support/i18n/" l ".edn")))])))

(def ^:private en (words "en"))
(def ^:private svg (sp/icon-svg))

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "support-page"})))

(def ^:private built
  "What `sp/build!` writes: every file under clogem-press/, and each page."
  (let [dir (temp-dir)]
    (try
      (let [result (sp/build! dir)
            root   (fs/path dir "clogem-press")]
        {:result result
         :files  (set (for [f (fs/glob root "**") :when (fs/regular-file? f)]
                        (str/replace (str (fs/relativize root f)) "\\" "/")))
         :html   (into {} (for [l langs :let [f (fs/file root (path-of l))] :when (fs/exists? f)] [l (slurp f)]))
         :glyphs (into {} (for [f (fs/glob (fs/path dir "glyphs") "*.txt")]
                            [(str/replace (str (fs/file-name f)) #"\.txt$" "") (slurp (fs/file f))]))})
      (finally (fs/delete-tree dir)))))

(defn- html "A built page, or \"\" if the build didn't write it." [lang] (get-in built [:html lang] ""))
(defn- style-of [html] (second (re-find #"(?s)<style>(.*?)</style>" html)))
(defn- without-faces [style] (str/replace (str style) #"@font-face\{[^}]*\}\n?" ""))
(def ^:private style (style-of (html "en")))
(def ^:private system-style (style-of (html "pt-BR")))

(defn- sha256-base64 [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))

(defn- hrefs-of
  "Every link's address, in order."
  [html]
  (map second (re-seq #"<a [^>]*\bhref=\"([^\"]*)\"" html)))

(defn- unescape [s] (-> s (str/replace "&apos;" "'") (str/replace "&quot;" "\"") (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&amp;" "&")))
(defn- esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;") (str/replace "'" "&apos;")))

(defn- switcher
  "The language switcher's markup, its <nav>, or nil."
  [html]
  (re-find #"(?s)<nav aria-label=\"[^\"]*\"><details class=\"langs\">.*?</details></nav>" html))

(defn- body-text
  "What a reader sees: the body without its tags."
  [html]
  (-> (subs html (or (str/index-of html "<body>") 0))
      (str/replace #"(?s)<svg.*?</svg>" " ")
      (str/replace #"<[^>]+>" " ")
      unescape))

(defn- section-html [html id]
  (re-find (re-pattern (str "(?s)<section aria-labelledby=\"" id "-title\" id=\"" id "\">.*?</section>")) html))

(def sections
  "The sections, in their order (DESIGN.md §7.5)."
  ["about" "status" "start" "help" "privacy" "source"])

(def ^:private english-only
  "Links to pages in English only (DESIGN.md §7.5): marked hreflang=\"en\",
  with a note, on the other pages."
  #{"https://github.com/EchoJustus/clogem-press#readme"
    "https://github.com/EchoJustus/clogem-press/blob/main/CHANGELOG.md"
    "https://github.com/EchoJustus/clogem-press/blob/main/DESIGN.md"
    "https://github.com/EchoJustus/clogem-press/tree/main/examples/demo-site"
    "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"})

;; ---------------------------------------------------------------------------
;; The five files

(deftest the-build-writes-five-pages-and-nothing-else
  (is (= #{"index.html" "zh-Hans/index.html" "zh-Hant/index.html" "pt-BR/index.html" "es-ES/index.html"}
         (:files built)))
  (testing "and each is the page these tests read"
    (doseq [l langs]
      (is (= (html l) (sp/page-html words l svg)) l))))

;; ---------------------------------------------------------------------------
;; What every page may hold

(deftest no-page-runs-anything
  (doseq [l langs :let [h (html l)]]
    (testing l
      (is (str/starts-with? h "<!doctype html>"))
      (is (not (str/includes? (str/lower-case h) "<script")))
      (is (not (re-find #"(?i)\son[a-z]+\s*=" h)) "no event handlers")
      (is (not (re-find #"(?i)\sstyle\s*=" h)) "no style attributes: the CSP would block them")
      (is (= 1 (count (re-seq #"<style>" h))) "one inline style"))))

(deftest every-head-is-the-hubs
  (doseq [l langs :let [h (html l) {:keys [title description]} (words l)]]
    (testing l
      (testing "the CSP allows the one style by its hash, data: images and, on a WenKai page, the site's own fonts, nothing else"
        (is (= (without-faces (if (wenkai-langs l) style system-style)) (without-faces (style-of h)))
            "one stylesheet per kind of page; only its @font-face rules, the page's fonts and their paths, differ")
        (let [content (some-> (re-find #"<meta content=\"([^\"]*)\" http-equiv=\"Content-Security-Policy\">" h) second unescape)]
          (is (= (str "default-src 'none'; style-src 'sha256-" (some-> (style-of h) sha256-base64) "'; "
                      "img-src data:; " (when (wenkai-langs l) "font-src 'self'; ") "base-uri 'none'; form-action 'none'")
                 content))
          (is (not (str/includes? (str content) "script-src")))
          (is (not (re-find #"https?:|\*|//" (str content))) "no other origin")
          (if (wenkai-langs l)
            (is (= ["font-src 'self'"] (re-seq #"font-src[^;]*" (str content))) "fonts from the site alone, never data:")
            (is (not (str/includes? (str content) "font-src")) "the hub's own policy: this page loads no font"))))
      (doseq [tag [(str "<html lang=\"" l "\">")
                   "<meta content=\"no-referrer\" name=\"referrer\">"
                   "<meta content=\"light dark\" name=\"color-scheme\">"
                   "<meta content=\"#F4F6F9\" media=\"(prefers-color-scheme: light)\" name=\"theme-color\">"
                   "<meta content=\"#0D1016\" media=\"(prefers-color-scheme: dark)\" name=\"theme-color\">"
                   (str "<link href=\"" (url-of l) "\" rel=\"canonical\">")
                   "<meta charset=\"utf-8\">"
                   "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"
                   (str "<title>" (esc title) "</title>")
                   (str "<meta content=\"" (esc description) "\" name=\"description\">")]]
        (is (str/includes? h tag) tag))
      (is (>= (count description) 50) "a description")
      (testing "the alternates: all five, and pt, es and x-default, absolute, each a built page"
        (let [alts (for [[_ href lang] (re-seq #"<link href=\"([^\"]*)\" hreflang=\"([^\"]*)\" rel=\"alternate\">" h)] [lang href])]
          (is (= [["en" (url-of "en")] ["zh-Hant" (url-of "zh-Hant")] ["zh-Hans" (url-of "zh-Hans")]
                  ["pt-BR" (url-of "pt-BR")] ["pt" (url-of "pt-BR")] ["es-ES" (url-of "es-ES")] ["es" (url-of "es-ES")]
                  ["x-default" (url-of "en")]]
                 alts))
          (doseq [[_ href] alts]
            (is ((:files built) (str (subs href (count base)) "index.html")) href)))))))

(deftest images-are-data-uris-with-alt-and-size
  (doseq [l langs :let [h (html l)]]
    (testing l
      (let [imgs (re-seq #"<img [^>]*>" h)]
        (is (seq imgs))
        (doseq [img imgs]
          (is (re-find #"\ssrc=\"data:image/(?:svg\+xml|webp|png);base64," img) img)
          (is (re-find #"\swidth=\"\d+\"" img))
          (is (re-find #"\sheight=\"\d+\"" img))))
      (is (every? #(str/starts-with? % "data:") (map second (re-seq #"\ssrc=\"([^\"]*)\"" h))))
      (testing "the header shows the icon, with its alt text in the page's language"
        (is (re-find (re-pattern (str "<img alt=\"" (java.util.regex.Pattern/quote (esc (:icon-alt (words l))))
                                      "\" class=\"mark\"[^>]*src=\"" (java.util.regex.Pattern/quote (sp/data-uri svg)) "\""))
                     h)))
      (testing "the favicon is the icon, as a data:image/svg+xml URI"
        (let [link (re-find #"<link\b[^>]*\brel=\"icon\"[^>]*>" h)]
          (is (some-> link (str/includes? (str "href=\"" (sp/data-uri svg) "\""))))
          (is (some-> link (str/includes? "type=\"image/svg+xml\"")))
          ;; the hub's front page takes an app's icon from its favicon, with
          ;; this pattern (clogem-support's scripts/portal.clj, `app-icon`)
          (is (= (sp/data-uri svg)
                 (some->> link (re-find #"\bhref=\"(data:image/svg\+xml;base64,[A-Za-z0-9+/]+={0,2})\"") second))))))))

(deftest nothing-is-loaded-from-elsewhere
  (doseq [l langs :let [h (html l) s (style-of h)]]
    (testing l
      (is (not (re-find #"<link [^>]*rel=\"stylesheet\"" h)))
      (is (= #{"canonical" "icon" "alternate"} (set (map second (re-seq #"<link [^>]*rel=\"([^\"]+)\"" h)))))
      (is (not (str/includes? (str s) "@import")))
      (is (not (re-find #"(?i)url\(\s*['\"]?(?!data:|#|(?:\.\./)?fonts/[a-z0-9-]+\.woff2\))" (str s)))
          "url( only to data:, # or the site's own fonts/")
      (doseq [tag ["iframe" "embed" "object" "video" "audio" "source" "form" "base" "frame" "portal"]]
        (is (not (re-find (re-pattern (str "(?i)<" tag "\\b")) h)) tag))
      (testing "every outside address is a plain link, or a <link> to one of the five pages"
        (let [pages (set (map url-of langs))
              refs  (for [[_ tag attrs] (re-seq #"<([a-z][a-z0-9]*)\b([^>]*)>" h)
                          [_ attr v] (re-seq #"\s([a-z-]+)=\"([^\"]*)\"" attrs)
                          :when (re-find #"(?i)^\s*(?:[a-z][a-z0-9+.-]*:|//)" v)
                          :when (not (str/starts-with? v "data:"))]
                      [tag attr v])]
          (is (seq refs))
          (is (every? (fn [[tag attr v]]
                        (or (and (= ["a" "href"] [tag attr]) (re-matches #"(?:https://|mailto:)\S+" v))
                            (and (= ["link" "href"] [tag attr]) (pages v))))
                      refs)
              (pr-str (remove (fn [[tag attr]] (= ["a" "href"] [tag attr])) refs)))))
      (testing "every link is https, mailto, a fragment of this page, or (the switcher) another version"
        (is (every? #(re-matches #"https://\S+|mailto:\S+|#[a-z-]+|\./|\.\./|(?:\.\./)?[A-Za-z]{2}-[A-Za-z]{2,4}/" %) (hrefs-of h)))))))

;; ---------------------------------------------------------------------------
;; The icon

(deftest the-icon-is-a-plain-drawing
  (testing "the hub's checks pass on it"
    (is (nil? (sp/icon-problem svg))))
  (testing "it is the v3 master, with its licence as its first line"
    (is (str/starts-with? svg "<!-- Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE). -->\n<!-- clogem-press icon, 48 master (v3) -->\n<svg "))
    (is (str/includes? svg "viewBox=\"0 0 48 48\"")))
  (testing "what makes an icon unsafe (the hub's cases)"
    (doseq [[what s] {"a script"        "<svg><script>alert(1)</script></svg>"
                      "a handler"       "<svg onload=\"alert(1)\"></svg>"
                      "a link out"      "<svg><use href=\"https://elsewhere.example/a.svg#x\"/></svg>"
                      "an image"        "<svg><image href=\"#x\"/></svg>"
                      "foreign content" "<svg><foreignObject/></svg>"
                      "a url() outside" "<svg><rect fill=\"url(https://elsewhere.example/p)\"/></svg>"
                      "not an SVG"      "<html></html>"}]
      (is (some? (sp/icon-problem s)) what)))
  (testing "references inside the drawing are fine"
    (is (nil? (sp/icon-problem "<svg><rect fill=\"url(#g)\" filter=\"url(#s)\"/><use href=\"#a\"/></svg>")))))

;; ---------------------------------------------------------------------------
;; The theme switcher (the hub's, test/portal_test.clj's checks)

(deftest the-theme-switcher-takes-no-script
  (doseq [l langs :let [h (html l) {:keys [theme]} (words l)]]
    (testing l
      (let [radios (re-seq #"<input [^>]*name=\"theme\"[^>]*>" h)
            id     #(second (re-find #"id=\"([^\"]+)\"" %))]
        (is (= ["theme-system" "theme-light" "theme-dark"] (map id radios)))
        (is (every? #(str/includes? % "type=\"radio\"") radios))
        (is (= ["theme-system"] (keep #(when (re-find #"\schecked\b" %) (id %)) radios)) "Auto when the page opens")
        (is (str/includes? h (str "<fieldset class=\"theme\"><legend>" (esc (:legend theme)) "</legend>")) "named for screen readers")
        (doseq [k [:system :light :dark] :let [[label title] (theme k)]]
          (is (re-find (re-pattern (str "<label for=\"theme-" (name k) "\" title=\"" (java.util.regex.Pattern/quote (esc title))
                                        "\"><svg[^>]*>.*?</svg>" (java.util.regex.Pattern/quote (esc label)) "</label>"))
                       h)
              (str k))))))
  (testing "the stylesheet follows the system unless Light or Dark is chosen"
    (is (str/includes? style "@media (prefers-color-scheme:dark){:root:not(:has(#theme-light:checked)){color-scheme:dark;--bg:#0D1016"))
    (is (str/includes? style ":root:has(#theme-dark:checked){color-scheme:dark;--bg:#0D1016"))
    (is (str/includes? style ":root:has(#theme-light:checked){color-scheme:light}"))
    (is (str/includes? style ":root{color-scheme:light dark;--bg:#F4F6F9")))
  (testing "colours fade, unless the reader asked for less motion"
    (is (str/includes? style "@property --bg{syntax:\"<color>\""))
    (is (re-find #":root\{color-scheme:light dark;[^}]*transition:--bg \.35s ease" style))
    (is (re-find #"prefers-reduced-motion:reduce\)\{:root," style)))
  (is (str/includes? style "@supports not selector(:has(*)){.theme{display:none}}")
      "hidden where the browser can't apply it")
  (testing "visible focus on the switcher and every link"
    (is (str/includes? style ".theme input:focus-visible+label{outline:3px solid var(--focus)"))
    (is (str/includes? style "a:focus-visible{outline:3px solid var(--focus)")))
  (testing "print: no switchers, no jump links, black on white"
    (let [print (second (re-find #"@media print\{(.*)\}$" style))]
      (is (some? print))
      (is (re-find #"\.jump,\.theme,\.langs[^{]*\{display:none\}" (str print)))
      (is (str/includes? (str print) "--text:#000"))
      (is (re-find #":root:has\(#theme-system:checked,#theme-light:checked,#theme-dark:checked\)\{color-scheme:light;--bg:#fff;[^}]*--text:#000" (str print))
          "as specific as the dark rules, so a dark theme prints black on white too")
      (is (str/includes? (str print) "body{background:#fff;color:#000}")))))

;; ---------------------------------------------------------------------------
;; The language switcher

(defn- attrs [s] (into {} (for [[_ k v] (re-seq #"([a-z-]+)=\"([^\"]*)\"" s)] [k v])))

(deftest the-language-switcher
  (doseq [l langs :let [h (html l) nav (switcher h) {:keys [lang-name lang-label langs-label]} (words l)]]
    (testing l
      (is (some? nav) "a <nav> holding a <details class=\"langs\">")
      (is (= 1 (count (re-seq #"<details[^>]*class=\"langs\"" h))) "exactly one language dropdown")
      (is (some->> nav (re-find #"aria-label=\"([^\"]*)\"") second (= (esc langs-label)))
          "the <nav> named in the page's language")
      (is (some->> nav (re-find #"<details([^>]*)>") second attrs (#(contains? % "open")) not)
          "closed when the page loads")
      (is (re-find #"(?s)<header class=\"hero\">(?:(?!</header>).)*<div class=\"top\">(?:(?!</header>).)*<nav aria-label=\"[^\"]*\"><details class=\"langs\">" h)
          "in the header's top row, beside the theme switcher")
      (is (not (re-find #"(?s)<header class=\"hero\">(?:(?!</header>).)*<nav aria-label=\"[^\"]*\" class=\"langs\">" h))
          "no flat row of language links left")
      (let [summary (second (re-find #"(?s)<summary>(.*?)</summary>" (str nav)))]
        (testing "the summary: a hidden word for Language, the current language in itself, a globe and a chevron"
          (is (some? summary))
          (is (str/includes? (str summary) (str "<span class=\"vh\">" (esc lang-label) " </span>")))
          (is (str/includes? (str summary) (str "<span>" (esc lang-name) "</span>")))
          (let [svgs (re-seq #"<svg[^>]*>" (str summary))]
            (is (= 2 (count svgs)) "a globe and a chevron")
            (is (every? #(and (str/includes? % "aria-hidden=\"true\"") (str/includes? % "focusable=\"false\"")) svgs)))
          (is (not (re-find #"<a[ >]" (str summary))))))
      (let [links (for [[_ a text] (re-seq #"<a ([^>]*)>([^<]*)</a>" (str nav))] (assoc (attrs a) :text (unescape text)))]
        (testing "the owner's order (SP.6): English, Traditional Chinese, Simplified Chinese, Portuguese, Spanish"
          (is (= ["en" "zh-Hant" "zh-Hans" "pt-BR" "es-ES"] (map #(get % "hreflang") links))))
        (testing "all five, in order, each named in its own language and marked as such"
          (is (= langs (map #(get % "hreflang") links)))
          (is (= langs (map #(get % "lang") links)))
          (is (= (map (comp :lang-name words) langs) (map :text links))))
        (testing "the current one marked aria-current=\"page\", and only it"
          (is (= [l] (keep #(when (= "page" (get % "aria-current")) (get % "hreflang")) links))))
        (testing "each relative href leads from this page's folder to the right page"
          (doseq [{target "hreflang" href "href"} links]
            (is (not (re-find #"^(?:[a-z]+:|/)" (str href))) "relative")
            (let [resolved (str (.resolve (URI. (url-of l)) (str href)))]
              (is (= (url-of target) resolved) (str href))
              (is ((:files built) (str (subs resolved (count base)) "index.html"))))))))
    (testing "plain links: no script and no redirect"
      (is (not (re-find #"(?i)http-equiv=\"refresh\"" (html l))))))
  (testing "the browser's own marker hidden"
    (is (re-find #"\.langs summary\{[^}]*list-style:none" style))
    (is (str/includes? style ".langs summary::-webkit-details-marker{display:none}")))
  (testing "the summary looks like the theme switcher: its border, radius, surface and shadow"
    (doseq [d ["border:1px solid var(--control)" "border-radius:999px" "background:var(--surface)" "box-shadow:var(--shadow)"]]
      (is (re-find (re-pattern (str "\\.langs summary\\{[^}]*" (java.util.regex.Pattern/quote d))) style) d)))
  ;; SP.5 item 2: it hung from the switchers' end edge, under the theme
  ;; switcher; test/browser/support-page.test.mjs checks the boxes
  (testing "the panel: below its own button, which is last in the row, at the content box's end edge; above the content, never wider than the screen"
    (is (re-find #"\.langs ul\{[^}]*position:absolute;[^}]*inset-inline-end:0;[^}]*max-width:calc\(100vw - 2rem\)" style))
    (is (str/includes? style ".langs{position:relative}") "it hangs from the language switcher")
    (is (not (re-find #"\.tools\{[^}]*position:relative" style)) "not from the switchers' row")
    (is (re-find #"\.tools\{[^}]*flex-wrap:wrap;[^}]*justify-content:flex-end" style) "wrapped, the language's line is the lower one, at the end edge")
    (doseq [l langs]
      (is (re-find #"(?s)<div class=\"tools\"><fieldset class=\"theme\">.*</fieldset><nav [^>]*><details class=\"langs\">.*</details></nav></div>" (html l))
          (str l ": the language switcher last")))
    (is (re-find #"\.langs ul\{[^}]*z-index:" style)))
  (testing "the chevron turns when open, unless the reader asked for less motion"
    (is (re-find #"\.langs\[open\] \.chev\{transform:rotate\(180deg\)\}" style))
    (is (re-find #"prefers-reduced-motion:reduce\)\{[^}]*\.chev[^}]*\{transition:none" style)))
  (testing "the current one is styled as current; focus is visible"
    (is (re-find #"\.langs a\[aria-current=page\]\{[^}]*background:var\(--chip\)" style))
    (is (str/includes? style ".langs summary:focus-visible{outline:3px solid var(--focus)"))
    (is (str/includes? style "a:focus-visible{outline:3px solid var(--focus)")))
  (testing "print hides the dropdown"
    (is (re-find #"@media print\{.*\.langs[,{][^}]*display:none" style))))

;; ---------------------------------------------------------------------------
;; Type: system CJK fonts, and no letter-spacing on CJK

(deftest the-header-fits-a-narrow-screen
  ;; At 360 px the pt-BR and es-ES theme switchers were wider than the
  ;; content box, and the grid's auto column grew with them: the page
  ;; scrolled sideways at 320 (SP.4 item 1). Checked by hand in Chromium at
  ;; 320, 360 and 1280 px, every language, light and dark.
  (testing "the hero's one column may shrink below its content's width"
    (is (re-find #"\.hero\{[^}]*grid-template-columns:minmax\(0,1fr\)" style)))
  (testing "the switchers never outgrow the content box"
    (is (re-find #"\.tools\{[^}]*min-width:0;" style))
    (is (re-find #"\.theme\{[^}]*min-width:0;max-width:100%" style)))
  (testing "a narrow screen puts the switchers on their own row, wraps the theme switcher, drops its icons and tightens its labels"
    (is (re-find #"@media \(max-width:30rem\)\{\.tools\{flex-basis:100%\}\.theme\{flex-wrap:wrap\}\.theme svg\{display:none\}\.theme label\{padding:[^}]*\}\}" style))))

(deftest the-name-keeps-one-line
  ;; At 768-1440 px the brand shrank beside the switchers and the name broke
  ;; after its hyphen, "clogem-" / "press" (SP.5 item 1).
  ;; test/browser/support-page.test.mjs counts the h1's lines at 320-1440 px.
  (testing "from 480 px up the name never wraps; its text stays as it is"
    (is (str/includes? style "@media (min-width:30rem){h1{white-space:nowrap}}"))
    (doseq [l langs] (is (str/includes? (html l) "<h1>clogem-press</h1>") l)))
  (testing "the brand's basis is the least it needs, the whole name, so the switchers wrap below it rather than squeeze it"
    (is (re-find #"\.brand\{flex:1 1 min-content;" style))
    (is (not (re-find #"\.brand\{[^}]*flex:[^;}]*rem" style)) "no fixed basis it can shrink to"))
  (testing "beside the brand the switchers take the width they want first, and no more"
    (is (re-find #"\.tools\{flex:999 1 min-content;[^}]*max-width:max-content" style))))

(deftest short-code-keeps-one-line
  ;; `bb dev` broke into `bb` / `dev`, each half its own chip (SP.4 item 8)
  (is (str/includes? style ".nw{white-space:nowrap}"))
  (is (re-find #"p code,li code\{[^}]*overflow-wrap:anywhere" style) "long code may still break")
  (doseq [l langs
          [_ attrs code] (re-seq #"<code( class=\"nw\")?>([^<]*)</code>" (str/replace (html l) #"(?s)<pre>.*?</pre>" ""))
          :let [n (count (unescape code))]]
    (if (<= n sp/short-code)
      (is attrs (str l ": " code " keeps one line"))
      (is (nil? attrs) (str l ": " code " may break")))))

(deftest cjk-text-gets-cjk-fonts
  (let [sc "\"PingFang SC\",\"Microsoft YaHei\",\"Noto Sans CJK SC\",\"Source Han Sans SC\""
        tc "\"PingFang TC\",\"Microsoft JhengHei\",\"Noto Sans CJK TC\",\"Source Han Sans TC\""]
    (testing "by :lang(), ahead of the generic family, in both stacks"
      (is (re-find (re-pattern (str ":lang\\(zh-Hans\\)\\{--text-font:[^;}]*," (java.util.regex.Pattern/quote sc) ",sans-serif;--heading-font:[^;}]*,"
                                    (java.util.regex.Pattern/quote sc) ",sans-serif\\}"))
                   style))
      (is (re-find (re-pattern (str ":lang\\(zh-Hant\\)\\{--text-font:[^;}]*," (java.util.regex.Pattern/quote tc) ",sans-serif;--heading-font:[^;}]*,"
                                    (java.util.regex.Pattern/quote tc) ",sans-serif\\}"))
                   style))
      (is (str/includes? style "font:400 1rem/1.65 var(--text-font)"))
      (is (str/includes? style "h1,h2,h3{font-family:var(--heading-font)"))
      (is (re-find #"\.langs a\{[^}]*font-family:var\(--text-font\)" style) "a switcher label in another script gets its own fonts"))
    (testing "WenKai ahead of them in the text stack, never in the headings"
      (is (re-find #":lang\(zh-Hans\)\{--text-font:\"LXGW WenKai\"," style))
      (is (re-find #":lang\(zh-Hant\)\{--text-font:\"LXGW WenKai\"," style))
      (is (not (re-find #"--heading-font:[^;}]*WenKai" style))))
    (testing "no letter-spacing on CJK: the eyebrow, the chips and the headings"
      (is (str/includes? style ":is(.eyebrow,.chip,h1,h2):lang(zh){letter-spacing:0}")))))

;; ---------------------------------------------------------------------------
;; The fonts (SP.6, SP.7): LXGW WenKai for everything but headings and code
;; on the English and Chinese pages, zh-Hant included (the main WenKai, no TC
;; edition); pt-BR and es-ES, and those languages' words anywhere, in the
;; system's fonts; the plan (support/fonts.edn) and the character lists the
;; build writes for support/fonts.sh

(def ^:private plan (edn/read-string (slurp "support/fonts.edn")))

(deftest the-font-plan
  (testing "one font, pinned: a tag, its release's TTFs on github.com/lxgw/, 64-hex SHA-256s"
    (is (= #{:wenkai} (set (keys (:fonts plan)))))
    (let [{:keys [family repo tag faces licence]} (get-in plan [:fonts :wenkai])]
      (is (= "LXGW WenKai" family))
      (is (= "https://github.com/lxgw/LxgwWenKai" repo))
      (is (re-matches #"v\d+\.\d+" tag))
      (is (= #{400 500} (set (keys faces))) "Regular and Medium only")
      (doseq [[w {:keys [url sha256]}] faces]
        (is (re-matches (re-pattern (str (java.util.regex.Pattern/quote (str repo "/releases/download/" tag "/")) "[A-Za-z-]+\\.ttf")) url) w)
        (is (re-matches #"[0-9a-f]{64}" sha256) w))
      (testing "its licence, from the same tag"
        (is (= (str "https://raw.githubusercontent.com/lxgw/LxgwWenKai/" tag "/OFL.txt") (:url licence)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 licence)))
        (is (= "OFL-LXGW-WenKai.txt" (:as licence))))))
  (testing "the pages set in it: English and both Chinese; not pt-BR or es-ES"
    (is (= wenkai-langs (set (:pages plan))))
    (is (not (contains? plan :zh-Hant)) "zh-Hant takes the main WenKai: there is no switch"))
  (testing "no TC edition anywhere: the plan, the generator, the font step"
    (doseq [f ["support/fonts.edn" "support/support_page.clj" "support/fonts.sh"]]
      (is (not (re-find #"(?i)wenkai[ -]?tc|LxgwWenkaiTC" (slurp f))) f)))
  (testing "every subset is cut from a pinned face and has a cap"
    (doseq [[id {:keys [font weight cap]}] (:subsets plan)]
      (is (re-matches #"wenkai-(?:latin|sc|hant|names)-[45]00" id))
      (is (get-in plan [:fonts font :faces weight]) id)
      (is (pos-int? cap) id)))
  (testing "the generator reads this plan"
    (is (= plan (sp/font-plan))))
  (testing "`bb support-fonts` prints it as lines for support/fonts.sh"
    (let [lines (sp/font-plan-lines)
          by    (group-by first (map #(str/split % #" ") lines))]
      (is (= 2 (count (by "font"))))
      (is (every? (fn [[_ n url sha]] (and (re-matches #"LxgwWenKai-[A-Za-z0-9.-]+\.ttf" n) (str/starts-with? url "https://github.com/lxgw/LxgwWenKai/") (re-matches #"[0-9a-f]{64}" sha)))
                  (by "font")))
      (is (= ["OFL-LXGW-WenKai.txt"] (map #(nth % 4) (by "licence"))))
      (is (= (set (keys (:subsets plan))) (set (map second (by "subset")))))
      (is (every? (fn [[_ _ src cap]] (and ((set (map second (by "font"))) src) (parse-long cap))) (by "subset")))
      (is (every? #{"font" "licence" "subset"} (keys by))))))

(def ^:private void-tags #{"meta" "link" "img" "input" "br" "hr" "source"})

(defn- text-runs
  "What each run of a page's visible text is set in, read from the HTML
  (not through the generator): [lang medium? text], for every text in the
  body outside headings, code and drawings. Medium: <strong>, the chips,
  the buttons, the eyebrow, the theme switcher's labels, the language
  summary and links, and the jump links."
  [html]
  (let [body (-> (subs html (str/index-of html "<body>"))
                 (str/replace #"(?s)<svg.*?</svg>" ""))
        root {:tag "html" :class #{} :lang (second (re-find #"<html lang=\"([^\"]+)\"" html))}]
    (loop [[[tok close tag as text] & more] (re-seq #"<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)" body)
           stack [root]
           out   []]
      (cond
        (nil? tok) out
        text (let [in?    (fn [pred] (some pred stack))
                   tags   (set (map :tag stack))
                   has    (fn [tag cls] (in? #(and (= tag (:tag %)) ((:class %) cls))))
                   medium (boolean (or (tags "strong")
                                       (in? #(some (:class %) ["chip" "button" "eyebrow"]))
                                       (and (tags "label") (has "fieldset" "theme"))
                                       (and (tags "summary") (has "details" "langs"))
                                       (and (tags "a") (or (has "details" "langs") (has "nav" "jump")))))]
               (recur more stack
                      (if (some tags ["h1" "h2" "h3" "code" "pre"])
                        out
                        (let [t (unescape text)
                              ;; the chips and the eyebrow are uppercased by the stylesheet
                              t (if (in? #(some (:class %) ["chip" "eyebrow"])) (str t (str/upper-case t)) t)]
                          (conj out [(some :lang (rseq stack)) medium t])))))
        (seq close) (recur more (pop stack) out)
        (void-tags tag) (recur more stack out)
        :else (let [a (attrs as)]
                (recur more (conj stack {:tag tag :class (set (str/split (get a "class" "") #" ")) :lang (get a "lang")}) out))))))

(defn- subset-of
  "The subset a character of a page's text must be in, by the page, the
  run's language and weight, or nil for the system's font: a pt-BR or
  es-ES page, Portuguese or Spanish words anywhere, and English's …."
  [page lang medium? cp]
  (let [w (if medium? 500 400)]
    (cond (not (wenkai-langs page))                       nil
          (re-find #"^(?:pt|es)(?:-|$)" lang)             nil
          (and (= cp 0x2026) (not (str/starts-with? lang "zh"))) nil
          (and (= cp 0x2026) (= page "en"))               nil
          (and (< cp 0x2E80) (not= cp 0x2026))            (str "wenkai-latin-" w)
          (= page "zh-Hans")                              (str "wenkai-sc-" w)
          (= page "zh-Hant")                              (str "wenkai-hant-" w)
          :else                                           (str "wenkai-names-" w))))

(defn- cps [s] (set (.toArray (.codePoints ^String s))))
(def ^:private ascii (set (range 0x20 0x7F)))

(defn- used-subsets
  "{subset #{code points}} that page `l`'s text sets, read from its HTML."
  [l]
  (apply merge-with into {}
         (for [[lang medium? text] (text-runs (html l)) cp (cps text) :when (>= cp 0x20)
               :let [id (subset-of l lang medium? cp)] :when id]
           {id #{cp}})))

(deftest the-character-lists
  (let [lists (update-vals (:glyphs built) cps)]
    (testing "written beside clogem-press/, never inside it, one per subset the pages use, each in the plan"
      (is (seq lists))
      (is (= (set (keys (:subsets plan))) (set (keys lists))) (pr-str (keys lists)))
      (is (not-any? #(str/includes? % "glyphs") (:files built))))
    (testing "every character of each WenKai page's text is in the list for its font and weight"
      (doseq [l wenkai-langs
              [lang medium? text] (text-runs (html l))
              cp (cps text)
              :when (>= cp 0x20)
              :let [id (subset-of l lang medium? cp)]
              :when id]
        (is (contains? (get lists id) cp) (str l ": " (pr-str (str (char cp))) " (" (if medium? "Medium" "Regular") ", " lang ") in " id))))
    (testing "and nothing else: no list holds a character no page sets in it, beyond ASCII"
      (let [used (apply merge-with into (map used-subsets langs))]
        (doseq [[id cs] lists]
          (is (empty? (remove (into ascii (used id)) cs)) id))))
    (testing "pt-BR and es-ES set nothing in WenKai, and no list holds their words"
      (doseq [l system-langs] (is (empty? (used-subsets l)) l))
      (let [all (reduce into #{} (vals lists))]
        (doseq [c "ãçéêíñóõúáâôà¿¡ÁÉÍÓÚÑÇ"]
          (is (not (contains? all (int c))) (str c)))
        ;; what only Portuguese and Spanish text has (their accents, ¿ ¡), beyond the ASCII every Latin list carries
        (let [text-of (fn [pred] (set (for [l langs, [lang _ text] (text-runs (html l)) :when (pred lang), cp (cps text)] cp)))
              theirs  (remove (into ascii (text-of #(re-find #"^(?:en|zh)" %))) (text-of #(re-find #"^(?:pt|es)" %)))]
          (is (seq theirs))
          (is (not-any? all theirs) (apply str (map char (filter all theirs)))))))
    (testing "the dropdown's Portuguese and Spanish names carry their lang, on every page"
      (doseq [l langs :let [sw (str (switcher (html l)))]]
        (is (str/includes? sw "lang=\"pt-BR\">Português (Brasil)</a>") l)
        (is (str/includes? sw "lang=\"es-ES\">Español (España)</a>") l)))
    (testing "the Latin lists hold no … (English leaves it to the system's font), and always hold printable ASCII"
      (doseq [id ["wenkai-latin-400" "wenkai-latin-500"]]
        (is (not (contains? (get lists id) 0x2026)) id)
        (is (every? (get lists id #{}) ascii) id)))
    (testing "the Medium lists are small: only what is set in Medium"
      (is (< (count (lists "wenkai-sc-500")) (/ (count (lists "wenkai-sc-400")) 2)))
      (is (< (count (lists "wenkai-hant-500")) (/ (count (lists "wenkai-hant-400")) 2))))
    (testing "Latin is in the Latin lists; the Chinese lists are Chinese"
      (is (every? #(< % 0x2E80) (lists "wenkai-latin-400")))
      (doseq [id ["wenkai-sc-400" "wenkai-hant-400" "wenkai-names-500"]]
        (is (every? #(or (>= % 0x2E80) (= % 0x2026)) (lists id)) id)))
    (testing "the English page's 简体中文 and 繁體中文 are a small list of their own"
      (is (= (cps "简体中文繁體中文") (lists "wenkai-names-500")))))
  (testing "a new character lands in its list; one in a heading, code, or Portuguese doesn't"
    (let [pages (-> words
                    (update-in ["en" :lead] str " ŧ…")
                    (update-in ["zh-Hans" :sections 0 :blocks] conj [:p [:strong "龘"]])
                    (update-in ["zh-Hant" :sections 0 :blocks] conj [:p "鬱……"])
                    (update-in ["en" :sections 0 :blocks] conj [:p [:code "Ѫ"]])
                    (update-in ["pt-BR" :sections 0 :blocks] conj [:p "ŉ"])
                    (update-in ["pt-BR" :sections] conj {:id "extra" :title "x" :blocks [[:cards {:chip "c" :title "Ж" :text ["y"]}]]}))
          lists (update-vals (sp/glyph-lists pages svg) cps)
          cp    #(.codePointAt ^String % 0)]
      (is (contains? (lists "wenkai-latin-400") (cp "ŧ")))
      (is (not (contains? (cps (get-in built [:glyphs "wenkai-latin-400"] "")) (cp "ŧ"))) "not there before")
      (is (not-any? #(contains? % 0x2026) [(lists "wenkai-latin-400") (lists "wenkai-latin-500")]) "English's … stays the system's")
      (is (contains? (lists "wenkai-hant-400") 0x2026) "Chinese text keeps WenKai's …")
      (is (contains? (lists "wenkai-hant-400") (cp "鬱")))
      (is (contains? (lists "wenkai-sc-500") (cp "龘")))
      (is (not-any? #(contains? % (cp "Ѫ")) (vals lists)) "code")
      (is (not-any? #(contains? % (cp "ŉ")) (vals lists)) "the pt-BR page")
      (is (not-any? #(contains? % (cp "Ж")) (vals lists)) "a card's title, an <h3>")))
  (testing "the chips and the eyebrow are shown in capitals (text-transform), so their lists hold the capitals too"
    (is (re-find #"\.chip\{[^}]*text-transform:uppercase" style))
    (let [pages (assoc-in words ["en" :sections 0 :blocks 0] [:cards {:chip "ŋ ā" :title "t" :text ["y"]}])
          lists (update-vals (sp/glyph-lists (update pages "en" assoc :eyebrow "ħ") svg) cps)]
      (doseq [c ["Ŋ" "Ā" "Ħ"]]
        (is (contains? (lists "wenkai-latin-500") (.codePointAt ^String c 0)) c)))))

(defn- faces
  "A style's @font-face rules, as maps of their descriptors."
  [style]
  (for [[_ body] (re-seq #"@font-face\{([^}]*)\}" (str style))]
    (into {} (for [d (str/split body #";(?=[a-z-]+:)")
                   :let [[k v] (str/split d #":" 2)]]
               [k v]))))

(deftest every-page-declares-its-fonts
  (doseq [l wenkai-langs
          :let [h (html l) fs (faces (style-of h)) prefix (if (= l "en") "" "../")]]
    (testing l
      (testing "an @font-face for each subset the page's text uses, and no other"
        (is (= (set (keys (used-subsets l))) (set (for [f fs] (second (re-find #"fonts/([a-z0-9-]+)\.woff2" (str (f "src")))))))))
      (doseq [f fs
              :let [id (second (re-find #"fonts/([a-z0-9-]+)\.woff2" (str (f "src"))))
                    {:keys [weight]} (get-in plan [:subsets id])]]
        (testing id
          (is (= (str "url(" prefix "fonts/" id ".woff2) format(\"woff2\")") (f "src")) "relative to the page's folder")
          (is (= "\"LXGW WenKai\"" (f "font-family")) "the main WenKai, on zh-Hant too")
          (is (= (str weight) (f "font-weight")))
          (is (= "normal" (f "font-style")))
          (is (= "swap" (f "font-display")))
          (is (= (cond (str/includes? id "latin") "U+0-2025,U+2027-2E7F"
                       (str/includes? id "names") "U+2E80-10FFFF"
                       :else                      "U+2026,U+2E80-10FFFF")
                 (f "unicode-range")))))))
  (testing "pt-BR and es-ES declare no font and load no font file"
    (doseq [l system-langs :let [h (html l) s (style-of h)]]
      (is (not (str/includes? s "@font-face")) l)
      (is (not (re-find #"url\(" (str/replace s #"url\(data:[^)]*\)" ""))) l)
      (is (not (re-find #"(?i)\.woff2|fonts/" h)) l)
      (is (not (re-find #"WenKai" s)) (str l ": WenKai in no stack"))
      (is (not (re-find #"WenKai" (str/replace h (re-pattern (str "(?s)" (java.util.regex.Pattern/quote (str (switcher h))))) ""))) (str l ": nor anywhere on the page")))))

(defn- rule
  "The declarations of `style`'s (by default the English page's) rule for exactly `selector`."
  ([selector] (rule style selector))
  ([style selector]
   (some->> (re-find (re-pattern (str "(?:^|[}\n])" (java.util.regex.Pattern/quote selector) "\\{([^}]*)\\}")) style) second)))

(def ^:private system-text
  "The hub's text stack (clogem-support's portal.clj), the pages' before SP.6."
  "\"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial")

(def ^:private system-heading
  "\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto")

(deftest text-is-wenkai-headings-and-code-are-not
  (testing "on the WenKai pages, the text stack starts with WenKai (zh-Hant's too); the system stacks stay after it"
    (doseq [l wenkai-langs :let [s (style-of (html l))]]
      (is (str/includes? s (str ":root{--text-font:\"LXGW WenKai\"," system-text ",sans-serif;")) l)
      (is (re-find #":lang\(zh-Hant\)\{--text-font:\"LXGW WenKai\",\"Segoe UI Variable Text\"[^;]*\"PingFang TC\"" s) l)
      (is (re-find #":lang\(zh-Hans\)\{--text-font:\"LXGW WenKai\",\"Segoe UI Variable Text\"[^;]*\"PingFang SC\"" s) l))
    (is (str/includes? (rule "body") "font:400 1rem/1.65 var(--text-font)")))
  (testing "Portuguese and Spanish words on them keep the system's stack, by :lang(pt) and :lang(es)"
    (is (= (str "--text-font:" system-text ",sans-serif;--heading-font:" system-heading ",sans-serif") (rule ":lang(pt),:lang(es)")))
    (is (< (str/index-of style ":lang(en){") (str/index-of style ":lang(pt),:lang(es){")) "after the others, so it wins"))
  (testing "pt-BR and es-ES: the hub's text and heading stacks, no WenKai"
    (doseq [l system-langs :let [s (style-of (html l))]]
      (is (str/includes? s (str ":root{--text-font:" system-text ",sans-serif;--heading-font:" system-heading ",sans-serif}")) l)
      (is (str/includes? s "font:400 1rem/1.65 var(--text-font)") l)))
  (testing "body text, the chips, the buttons, the switchers, the jump links and the footer inherit it"
    (doseq [sel [".chip" ".button" ".eyebrow" ".theme label" ".langs summary" ".jump a" "footer" "strong" ".lead" ".sub"]]
      (is (not (re-find #"font-family|font:" (str (rule sel)))) sel))
    (is (str/includes? (rule ".langs a") "font-family:var(--text-font)") "a name in another language takes its own language's font")
    ;; without it, on the zh-Hant page "Português (Brasil)" inherited the
    ;; zh-Hant stack and its ê came from the system (seen in Chromium, SP.6)
    (is (str/includes? style (str ":lang(en){--text-font:\"LXGW WenKai\"," system-text ",sans-serif;"))
        "English, wherever it sits, in its own stack"))
  (testing "on the WenKai pages emphasis is Medium, which WenKai has; nothing asks for a weight without a face (no faux bold)"
    (doseq [sel ["strong" ".chip" ".button" ".eyebrow" ".theme label" ".langs summary" ".langs a" ".jump a"]]
      (is (str/includes? (str (rule sel)) "font-weight:500") sel))
    (let [weights (for [[_ sel decls] (re-seq #"([^{}]+)\{([^{}]*font(?:-weight)?:[^{}]*)\}" style)
                        :when (not (re-matches #"h1|h2|h3|h1,h2,h3|section>h3|\.card h3" (str/trim sel)))
                        [_ w] (re-seq #"(?:font-weight:|font:)(\d+)" decls)]
                    [sel w])]
      (is (seq weights))
      (is (every? #{"400" "500"} (map second weights)) (pr-str weights))))
  (testing "pt-BR and es-ES take the system fonts' weights back (500 is Regular in most of them)"
    (doseq [[sel w] {"strong" "700" ".chip" "700" ".eyebrow" "700" ".button" "650" ".theme label" "650"
                     ".langs summary" "650" ".langs a" "600" ".jump a" "600"}]
      (is (str/includes? (str (rule system-style sel)) (str "font-weight:" w)) sel)))
  (testing "the headings keep the system's heading stack"
    (is (str/includes? style "h1,h2,h3{font-family:var(--heading-font)"))
    (is (str/includes? style (str ";--heading-font:" system-heading ",sans-serif}"))))
  (testing "code and pre stay monospace, on every page"
    (doseq [l langs]
      (is (= "font-family:ui-monospace,\"Cascadia Code\",\"SF Mono\",Menlo,Consolas,monospace;font-size:.9em" (rule (style-of (html l)) "code,pre")) l))))

(deftest no-lone-character-ends-the-chinese-lead
  (testing "text-wrap:pretty, and a Chinese measure for a browser without it (checked in Chromium at 1024, 1280 and 1440 px)"
    (is (str/includes? (rule ".lead") "text-wrap:pretty"))
    (is (= "max-width:40rem" (rule ".lead:lang(zh)")))))

;; ---------------------------------------------------------------------------
;; Contrast: WCAG AA in both themes

(defn- pairs
  "[what fg bg minimum] for a theme's tokens: text 4.5:1 on every ground it
  sits on, the focus ring and control borders 3:1."
  [t]
  (let [chip-on (fn [ground] (cp/over (t "--chip") (t ground)))
        grounds ["--bg" "--surface" "--surface-2"]]
    (concat
     (for [fg ["--text" "--heading" "--muted" "--link"] g grounds] [(str fg " on " g) (t fg) (t g) 4.5])
     (for [g ["--surface" "--bg"]] [(str "--chip-ink on --chip on " g) (t "--chip-ink") (chip-on g) 4.5])
     [["--surface on --chip-ink (a hovered button)" (t "--surface") (t "--chip-ink") 4.5]]
     (for [fg ["--focus" "--control"] g grounds] [(str fg " on " g) (t fg) (t g) 3.0])
     (for [fg ["--focus" "--control"]] [(str fg " on --chip on --surface") (t fg) (chip-on "--surface") 3.0]))))

(deftest every-colour-pair-is-aa
  (doseq [[theme tokens] {"light" sp/light "dark" sp/dark}
          [what fg bg minimum] (pairs tokens)]
    (let [c (cp/contrast fg (if (string? bg) (cp/rgba bg) bg))]
      (is (>= c minimum) (format "%s: %s is %.2f:1, under %.1f:1" theme what c minimum))))
  (testing "the stylesheet sets exactly these tokens"
    (doseq [[k v] sp/light] (is (re-find (re-pattern (str ":root\\{[^}]*" k ":" (java.util.regex.Pattern/quote v) "[;}]")) style) k))
    (doseq [[k v] sp/dark] (is (str/includes? style (str k ":" v)) k)))
  (testing "jade is the accent, darker in light mode and lighter in dark"
    (is (< (cp/contrast "#23A982" "#FFFFFF") 4.5) "why #23A982 can't be text on white")
    (is (< (cp/luminance (sp/light "--link")) (cp/luminance "#23A982") (cp/luminance (sp/dark "--link"))))))

;; ---------------------------------------------------------------------------
;; Structure

(deftest the-sections-in-their-order
  (doseq [l langs :let [h (html l) page (words l)]]
    (testing l
      (let [ids (map second (re-seq #"<section aria-labelledby=\"[^\"]+\" id=\"([^\"]+)\">" h))]
        (is (= sections ids))
        (is (= sections (map :id (:sections page))) "the words' order"))
      (testing "each opens with its own heading, which it is labelled by"
        (doseq [{:keys [id title]} (:sections page)]
          (is (str/includes? h (str "<section aria-labelledby=\"" id "-title\" id=\"" id "\"><h2 id=\"" id "-title\">" (esc title) "</h2>"))
              id)))
      (testing "no overline repeating a heading (the hub's rule, owner 2026-10-02)"
        (is (not (re-find #"kicker|overline" h))))
      (testing "the jump nav lists them, in the same order, named in the page's language"
        (let [nav (second (re-find (re-pattern (str "(?s)<nav aria-label=\"" (java.util.regex.Pattern/quote (esc (:jump-label page))) "\" class=\"jump\">(.*?)</nav>")) h))]
          (is (some? nav))
          (is (= (map #(str "#" %) sections) (hrefs-of (str nav))))))
      (testing "one h1, and the levels step down one at a time"
        (let [levels (map #(parse-long (second %)) (re-seq #"<h([1-6])\b" h))]
          (is (= 1 (count (filter #{1} levels))))
          (is (= 1 (first levels)))
          (is (every? (fn [[a b]] (<= b (inc a))) (partition 2 1 levels)) (pr-str levels))
          (is (every? #(<= % 3) levels))))
      (testing "a header with the icon and the name, a main and a footer"
        (is (re-find #"(?s)<header class=\"hero\">.*<img [^>]*class=\"mark\".*<h1>clogem-press</h1>" h))
        (is (re-find #"(?s)</header><main>.*</main><footer>" h))))))

(deftest the-footer-links-back-to-the-hub
  (doseq [l langs :let [h (html l) footer (second (re-find #"(?s)<footer>(.*)</footer>" h))]]
    (testing l
      (is (re-find #"<a href=\"https://echojustus.github.io/clogem-support/\"[^>]*>Clogem Support</a>" (str footer)))
      (is (str/includes? (str footer) "Eclipse Public License 2.0"))
      (is (str/includes? (str footer) (esc (get-in words [l :footer :note])))))))

(def ^:private copyright-notice
  "The footer's copyright notice, word for word: the publisher in full, then
  the product (the owner's rule for every Clogem support page, 2026-10-26,
  in clogem-support's CLAUDE.md, \"Footer copyright notices\")."
  "© 2026 EchoJustus (Jiapeng Ji). clogem-press.")

(deftest every-footer-names-the-publisher-in-full
  (is (= "EchoJustus (Jiapeng Ji)" sp/copyright-holder))
  (doseq [l langs
          :let [h       (html l)
                footer  (str (second (re-find #"(?s)<footer>(.*)</footer>" h)))
                visible (str/replace footer #"<[^>]*>" "")]]
    (testing l
      (testing "the footer opens with the notice, the same characters in every language"
        (is (= copyright-notice (some-> (re-find #"^<p>(.*?)</p>" footer) second (str/replace #"<[^>]*>" "")))))
      (testing "once on the page"
        (is (= 1 (count (re-seq #"©" h)))))
      (testing "every EchoJustus a reader sees there carries the rest of the name"
        (is (seq (re-seq #"EchoJustus" visible)))
        (is (every? #{" (Jiapeng Ji)"} (map second (re-seq #"EchoJustus(.{0,13})" visible))) visible))
      (testing "on one line: the publisher and the product are each one element that doesn't wrap, their text unchanged"
        (is (str/starts-with? footer "<p>© 2026 <span class=\"nw\">EchoJustus (Jiapeng Ji)</span>. <span class=\"nw\">clogem-press</span>.</p>")
            "the paragraph as built, so nothing else (a break, a hidden span) is in it")
        (is (str/includes? (style-of h) ".nw{white-space:nowrap}")))))
  (testing "no translation can change it: the words hold none of it"
    (doseq [l langs]
      (is (not (re-find #"©|Jiapeng" (slurp (str "support/i18n/" l ".edn")))) l)))
  (testing "the rule is written down for the next maintainer"
    (is (str/includes? (slurp "DESIGN.md") (str "`" copyright-notice "`")))))

(deftest the-footer-credits-the-font
  (doseq [l langs
          :let [footer (str (second (re-find #"(?s)<footer>(.*)</footer>" (html l))))
                name   ({"en" "LXGW WenKai" "zh-Hans" "霞鹜文楷" "zh-Hant" "霞鶩文楷"} l)]]
    (testing l
      (if name
        (do (is (str/includes? footer (str "<a href=\"https://github.com/lxgw/LxgwWenKai\">" name "</a>")) "the font's name, linking its repository")
            (is (str/includes? footer "SIL Open Font License 1.1")))
        (is (not (re-find #"lxgw|WenKai|Open Font License" footer)) "a page in the system's fonts credits none"))))
  (testing "the build refuses a credit that doesn't link the page's font, or one on a page without it"
    (is (some #(str/includes? % ":footer :font") (sp/check-page (update en :footer dissoc :font) "en")))
    (is (some #(str/includes? % ":footer :font") (sp/check-page (assoc-in (words "zh-Hant") [:footer :font 1 1] "https://github.com/lxgw/LxgwWenkaiTC") "zh-Hant")))
    (is (some #(str/includes? % ":footer :font") (sp/check-page (assoc-in (words "pt-BR") [:footer :font] (get-in en [:footer :font])) "pt-BR")))))

;; ---------------------------------------------------------------------------
;; The languages, in step

(defn- fixed
  "What a translation keeps of some pieces: its links' targets and its code,
  in any order (word order differs between languages)."
  [pieces]
  (sort (map pr-str (for [x pieces :when (and (vector? x) (#{:a :code} (first x)))]
                      (if (= :a (first x)) [:a (second x)] x)))))

(defn- shape
  "A language's words with everything translatable taken out."
  [page]
  {:keys     (set (keys (dissoc page :en-note)))
   :theme    (set (keys (:theme page)))
   :footer   [(set (keys (dissoc (:footer page) :font))) (fixed (get-in page [:footer :licence]))]
   :fixed    (select-keys page [:hub :name])
   :sections (for [{:keys [id blocks] :as s} (:sections page)]
               [id (set (keys s))
                (for [[tag & args] blocks]
                  (case tag
                    :p     [:p (fixed args)]
                    :ul    [:ul (map fixed args)]
                    :pre   [:pre (first args)]
                    :h3    [:h3]
                    :cards [:cards (for [c args] [(set (keys c)) (:href c) (fixed (:text c))])]))])})

(deftest every-language-has-englishs-shape
  (doseq [l translations]
    (testing l
      (is (= (shape en) (shape (words l))) "the same sections, blocks, link targets, code and commands")
      (is (empty? (sp/parity-problems words)) (pr-str (sp/parity-problems words)))
      (is (= (url-of l) (:url (words l))))
      (testing "no string is empty"
        (let [strings (atom [])]
          (walk/postwalk #(do (when (string? %) (swap! strings conj %)) %) (words l))
          (is (seq @strings))
          (is (every? (complement str/blank?) @strings))))
      (testing "on the page: the same links and the same commands as English's"
        ;; pt-BR and es-ES, in the system's fonts, credit no font (SP.7)
        (let [content (fn [h] (str/replace h (or (switcher h) "") ""))
              font    #(if (wenkai-langs l) % (remove #{"https://github.com/lxgw/LxgwWenKai"} %))]
          (is (= (frequencies (font (hrefs-of (content (html "en"))))) (frequencies (hrefs-of (content (html l))))))
          (is (= (re-seq #"(?s)<pre>.*?</pre>" (html "en")) (re-seq #"(?s)<pre>.*?</pre>" (html l))))))))
  (testing "the build refuses a translation that drifts"
    (doseq [[what f] {"a link's target" #(assoc-in % ["pt-BR" :sections 0 :blocks 1 2 1] "https://babashka.org/elsewhere")
                      "a command"       #(assoc-in % ["zh-Hans" :sections 2 :blocks 1 1] "bb build")
                      "a section"       #(update-in % ["es-ES" :sections] pop)
                      "a key"           #(update % "zh-Hant" dissoc :jump-label)}]
      (is (seq (sp/parity-problems (f words))) what))))

(deftest every-translation-is-translated
  (doseq [l translations :let [page (words l)]]
    (testing l
      (doseq [k [:title :description :lead :subtitle :eyebrow :icon-alt :jump-label :langs-label :lang-label :lang-name]]
        (is (not= (en k) (page k)) (str k)))
      (doseq [[{a :title} {b :title}] (map vector (:sections en) (:sections page))]
        (is (not= a b) a))
      (is (not= (get-in en [:theme :legend]) (get-in page [:theme :legend])))
      (doseq [k [:system :light :dark]]
        (is (not= (first (get-in en [:theme k])) (first (get-in page [:theme k])))) (str k))
      (is (not= (get-in en [:footer :note]) (get-in page [:footer :note]))))))

(deftest english-only-links-say-so
  (doseq [l translations :let [h (html l) note (:en-note (words l))]]
    (testing l
      (let [links (for [[_ a] (re-seq #"<a ([^>]*)>" h)] (attrs a))
            ours  (filter #(english-only (get % "href")) links)]
        (is (= (count english-only) (count (distinct (map #(get % "href") ours)))) "the README, the CHANGELOG, DESIGN.md, the demo and GitHub's privacy statement")
        (is (every? #(= "en" (get % "hreflang")) ours) (pr-str (remove #(= "en" (get % "hreflang")) ours))))
      (testing "each followed by the page's one note"
        (is (some? note))
        (doseq [href english-only]
          (is (re-find (re-pattern (str "href=\"" (java.util.regex.Pattern/quote href) "\" hreflang=\"en\">[^<]*</a><span class=\"en-note\">"
                                        (java.util.regex.Pattern/quote (esc note)) "</span>"))
                       h)
              href))
        (is (= #{(esc note)} (set (map second (re-seq #"<span class=\"en-note\">([^<]*)</span>" h)))) "one form"))))
  (testing "English needs no note"
    (is (not (str/includes? (html "en") "class=\"en-note\"")))
    (is (not (str/includes? (html "en") "hreflang=\"en\">")) "nor hreflang on its own links")))

(deftest anyone-may-write-in-these-languages
  (doseq [l langs]
    (is (re-find (case l
                   "en"      #"any of the languages this page is offered in; replies may come in English"
                   "zh-Hans" #"本页提供的任何一种语言.*回复可能是英文"
                   "zh-Hant" #"本頁提供的任何一種語言.*回覆可能會使用英文"
                   "pt-BR"   #"qualquer um dos idiomas desta página; as respostas podem vir em inglês"
                   "es-ES"   #"cualquiera de los idiomas de esta página; es posible que las respuestas lleguen en inglés")
                 (body-text (section-html (html l) "help")))
        l)))

(deftest the-wording-is-each-languages-own
  ;; smoke checks, not proof: a native reviewer reads the words
  (let [hant (slurp "support/i18n/zh-Hant.edn")
        hans (slurp "support/i18n/zh-Hans.edn")]
    (testing "zh-Hant: Taiwan usage"
      (doseq [w ["软件" "軟件" "程序代碼" "網絡"]]
        (is (not (str/includes? hant w)) w))
      (doseq [w ["軟體" "程式碼" "資料夾" "網路" "連結" "預設" "設定" "原始碼" "授權" "搜尋" "資訊"]]
        (is (str/includes? hant w) w))
      (is (not (re-find #"[一-鿿][,.:;!?]|[,.:;!?][一-鿿]" hant)) "full-width punctuation beside Chinese"))
    (testing "zh-Hans: no Traditional characters"
      (doseq [c ["軟" "體" "網" "連" "結" "設" "預"]]
        (is (not (str/includes? hans c)) c))
      (is (not (re-find #"[一-鿿][,.:;!?]|[,.:;!?][一-鿿]" hans)) "full-width punctuation beside Chinese"))
    (testing "pt-BR: Brazilian, not European"
      (let [pt (str/lower-case (body-text (html "pt-BR")))]
        (doseq [w ["você" "arquivo" "baixa"]] (is (str/includes? pt w) w))
        (doseq [w [#"ficheiro" #"ecrã" #"utilizador" #"\bpartilh" #"\bequipa" #"contacto" #"está a "]] (is (not (re-find w pt)) (str w)))))
    (testing "es-ES: Spain, tú"
      (let [es (str/lower-case (body-text (html "es-ES")))]
        (doseq [w ["ordenador" "archivo" "carpeta" "enlace" "descarga" "correo electrónico" "necesitas"]] (is (str/includes? es w) w))
        (doseq [w ["computadora" "usted" "vosotros" "descargue"]] (is (not (str/includes? es w)) w))))))

(deftest no-page-claims-more-content-languages
  (let [names {"en"      ["English" "Simplified" "Traditional Chinese" "Malay" "Tamil"]
               "zh-Hans" ["英语" "简体中文" "繁体中文" "马来语" "泰米尔语"]
               "zh-Hant" ["英語" "簡體中文" "繁體中文" "馬來語" "泰米爾語"]
               "pt-BR"   ["inglês" "chinês simplificado" "chinês tradicional" "malaio" "tâmil"]
               "es-ES"   ["inglés" "chino simplificado" "chino tradicional" "malayo" "tamil"]}]
    (doseq [l langs :let [h (html l)]]
      (testing l
        (testing "the site generator's five content languages, named in the page's own words"
          (let [about (body-text (str (section-html h "about")))]
            (doseq [n (names l)] (is (str/includes? about n) n))))
        (testing "and never Portuguese or Spanish, outside the language switcher"
          (let [text (body-text (str/replace h (or (switcher h) "") ""))]
            (is (not (re-find #"(?i)portugu|spanish|español|espanhol|castellano|葡萄牙|西班牙" text))
                (re-find #"(?i).{0,40}(?:portugu|spanish|español|espanhol|castellano|葡萄牙|西班牙).{0,40}" text))))))))

;; ---------------------------------------------------------------------------
;; Words

(deftest no-commerce-and-one-address
  (let [text (body-text (html "en"))]
    (is (not (re-find #"(?i)\b(?:stores?|price[sd]?|pricing|trials?|purchas\w*|buy|upsell|subscri\w*|checkout|discount|licen[cs]e keys?)\b|[$€£¥]\s?\d" text))
        (re-find #"(?i).{0,40}\b(?:stores?|price|trial|purchas\w*|buy)\b.{0,40}" text)))
  (doseq [l langs :let [text (body-text (html l))]]
    (is (not (re-find #"(?i)wmark pro|\bpro edition|[$€£¥]\s?\d|价格|價格|购买|購買|preço|precio|comprar|assinatura|suscripción" text)) l))
  (testing "no address but the hub's, on the pages or in support/"
    (doseq [[what s] (merge (into {} (for [l langs] [l (html l)]))
                            (into {} (for [f (fs/glob "support" "**") :when (fs/regular-file? f)] [(str f) (slurp (str f))])))]
      (is (= #{} (disj (set (re-seq #"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+" s)) "EchoJustus.Studio@outlook.com"))
          what))
    (doseq [l langs]
      (is (str/includes? (html l) "href=\"mailto:EchoJustus.Studio@outlook.com?subject=clogem-press\"") l))))

(deftest help-goes-where-it-belongs
  (doseq [l langs :let [hrefs (set (hrefs-of (html l)))]]
    (testing l
      (testing "code: issues and pull requests in this repository"
        (is (hrefs "https://github.com/EchoJustus/clogem-press/issues"))
        (is (hrefs "https://github.com/EchoJustus/clogem-press/pulls")))
      (testing "questions and ideas: the hub's Discussions"
        (is (hrefs "https://github.com/EchoJustus/clogem-support/discussions/new?category=q-a"))
        (is (hrefs "https://github.com/EchoJustus/clogem-support/discussions/new?category=ideas")))
      (testing "never this repository's Discussions, which are off"
        (is (not-any? #(str/includes? % "clogem-press/discussions") hrefs)))
      (testing "the privacy note links GitHub's privacy statement"
        (is (some #(str/includes? % "docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement") hrefs)))))
  (testing "the page says it is public"
    (is (re-find #"(?i)public" (body-text (html "en"))))
    (is (re-find #"(?i)leave out personal details" (body-text (html "en"))))))

(deftest the-status-follows-the-changelog
  (let [text (body-text (html "en"))]
    (is (re-find #"(?i)in progress" text))
    (is (re-find #"(?i)not released|unreleased" text))
    (is (re-find #"(?i)five languages" text))
    (testing "live reload shipped in 0.1.0; Phase 4 made bb dev quicker (SP.4 item 6)"
      (is (not (re-find #"(?i)live-reload" text)))
      (is (str/includes? text "one rebuild per burst of saves"))))
  (doseq [l langs :let [text (body-text (html l))]]
    (is (str/includes? text "0.2.0") l)
    ;; the footer's copyright year is the page's, not a promise
    (is (not (re-find #"(?i)\b(?:20\d\d|q[1-4]|next (?:week|month)|coming soon)\b" (str/replace text "© 2026 " "© ")))
        (str l ": no dates promised"))))

(def ^:private storage-words
  "How the privacy note says each key a built site keeps in its reader's
  browser, in each language: a key the theme's code uses and this map
  doesn't name fails `the-privacy-note-names-every-stored-key`."
  {"clogem-mode"             {"en" "the colour mode they chose" "zh-Hans" "读者选择的配色模式" "zh-Hant" "讀者選擇的色彩模式"
                              "pt-BR" "o modo de cor escolhido" "es-ES" "el modo de color elegido"}
   "clogem-lang"             {"en" "the language they chose" "zh-Hans" "选择的语言" "zh-Hant" "選擇的語言"
                              "pt-BR" "o idioma escolhido" "es-ES" "el idioma elegido"}
   "clogem-banner-dismissed" {"en" "language banners they dismissed" "zh-Hans" "关闭了哪些" "zh-Hant" "關閉了哪些"
                              "pt-BR" "o leitor fechou" "es-ES" "ha cerrado el lector"}})

(defn- theme-storage-keys
  "Every localStorage key the theme names: written as
  localStorage[\"k\"] or localStorage \"k\" in its comments, as a literal
  in a getItem or setItem call, or in a variable that one reads."
  []
  (set (for [f (fs/glob "src/clogem/theme" "**.{js,clj}")
             :let [src (slurp (str f))]
             k (concat (map second (re-seq #"localStorage\W{1,3}(clogem-[\w-]+)" src))
                       (map second (re-seq #"localStorage\.\w+Item\(\\?[\"'](clogem-[\w-]+)" src))
                       (for [[_ v k] (re-seq #"var (\w+) = [\"'](clogem-[\w-]+)[\"']" src)
                             :when (re-find (re-pattern (str "(?:localStorage\\.\\w+Item|\\b(?:read|write))\\(" v "\\b")) src)]
                         k))]
         k)))

(deftest the-privacy-note-names-every-stored-key
  ;; it said the mode and the language, not the dismissed banners (SP.4 item 4)
  (is (= #{"clogem-mode" "clogem-lang" "clogem-banner-dismissed"} (theme-storage-keys))
      "the scan finds the keys known today")
  (doseq [k (theme-storage-keys)]
    (is (contains? storage-words k) (str k ": a key the privacy note doesn't cover yet; say it in all five, then here")))
  (doseq [l langs
          :let [text (body-text (str (section-html (html l) "privacy")))]
          [k by-lang] storage-words]
    (is (str/includes? text (by-lang l)) (str l ": " k))))

(deftest tool-downloads-are-the-default
  ;; a site's :tools config can name its own URL, hash or binary (SP.4 item 5);
  ;; said once each, "by default" no more than once a sentence (SP.5 item 5)
  (let [text (-> (body-text (str (section-html (html "en") "privacy")))
                 (str/replace #"\s+" " ") (str/replace #" ([),.])" "$1"))]
    (is (str/includes? text "by default from that tool's own releases on GitHub"))
    (is (str/includes? text "Each download is checked against a SHA-256 hash pinned in clogem-press and kept in a cache outside your site (by default ~/.cache/clogem-press/tools), so it happens once."))
    (is (str/includes? text "A site's config can instead name its own source and hash for a tool."))
    (is (str/includes? text "A copy already installed, named in the site's config or in CLOGEM_CHROMA, CLOGEM_PAGEFIND or CLOGEM_FSWATCHER, is never downloaded."))
    (is (= 1 (count (re-seq #"(?i)installed" text))) "the installed copy, said once")
    (doseq [s (str/split text #"(?<=[.:;])\s+")]
      (is (<= (count (re-seq #"(?i)by default" s)) 1) s)))
  (doseq [[l w] {"zh-Hans" "默认" "zh-Hant" "預設" "pt-BR" "por padrão" "es-ES" "de forma predeterminada"}
          :let [text (body-text (str (section-html (html l) "privacy")))]]
    (is (= 3 (count (re-seq (re-pattern (str "(?i)" w)) text)))
        (str l ": the source and the cache, each once, and the built site's requests (English's \"out of the box\")"))))

(deftest the-privacy-note-says-where-the-fonts-come-from
  ;; since SP.6 the WenKai pages fetch their fonts from this same site (SP.7 item 8)
  (doseq [[l clause] [["en" "its styles and its icon are inside this one file, and its fonts come from this same site."]
                      ["zh-Hans" "它的样式和图标都在这一个文件里，字体也来自本站。"]
                      ["zh-Hant" "它的樣式與圖示都在這一個檔案裡，字型也來自本站。"]
                      ["pt-BR" "os seus estilos e o seu ícone estão dentro deste único arquivo."]
                      ["es-ES" "sus estilos y su icono están dentro de este único archivo."]]
          :let [text (body-text (str (section-html (html l) "privacy")))]]
    (is (str/includes? text clause) l)
    (when-not (wenkai-langs l)
      (is (not (re-find #"(?i)fonte|fuente|tipograf" text)) (str l ": it loads no font, so says nothing of one")))))

(deftest the-privacy-note-keeps-to-what-clogem-press-stores
  ;; it said a site keeps three things "and only there", but giscus and GA4,
  ;; when turned on, store their own (SP.5 item 3)
  (doseq [[l own others] [["en" "Of its own, a site keeps three things in its reader's browser"
                           "giscus comments and analytics, when its owner turns them on, keep their own data under their own terms"]
                          ["zh-Hans" "网站本身会在读者的浏览器中保存三样东西" "giscus 评论和统计分析如果由网站所有者开启，会按照各自的条款保存它们自己的数据"]
                          ["zh-Hant" "網站本身會在讀者的瀏覽器中儲存三樣東西" "giscus 留言和流量分析如果由網站擁有者開啟，會依照各自的條款儲存它們自己的資料"]
                          ["pt-BR" "Por conta própria, um site guarda três coisas no navegador do leitor"
                           "os comentários do giscus e a análise, quando o dono os ativa, guardam os próprios dados, sob os próprios termos"]
                          ["es-ES" "Por sí mismo, un sitio guarda tres cosas en el navegador del lector"
                           "los comentarios de giscus y la analítica, cuando su propietario los activa, guardan sus propios datos según sus propias condiciones"]]
          :let [text (body-text (str (section-html (html l) "privacy")))]]
    (is (str/includes? text own) l)
    (is (str/includes? text others) l)
    (is (not (re-find #"(?i)only there|只在读者|只在讀者|e só ali|y solo allí" text)) (str l ": no claim that it is all"))))

(deftest buttons-say-where-they-lead
  ;; pt-BR's and es-ES's question buttons repeated their cards' titles (SP.5 item 4)
  (doseq [l langs
          {:keys [blocks]} (:sections (words l))
          [tag & cards] blocks :when (= tag :cards)
          {:keys [title action href]} cards :when action]
    (is (not= (str/lower-case title) (str/lower-case action)) (str l ": " action))
    (when (str/includes? href "/discussions")
      (is (re-find #"GitHub|Discussions" action) (str l ": " action " names where it leads")))))

(deftest trademarks-are-trademarks
  ;; 标识 / 標識 are "signs", not trademarks (SP.4 item 7)
  (let [zs (body-text (str (section-html (html "zh-Hans") "source")))
        zt (body-text (str (section-html (html "zh-Hant") "source")))]
    (is (str/includes? zs "商标")) (is (not (str/includes? zs "标识")))
    (is (str/includes? zt "商標")) (is (not (str/includes? zt "標識")))))

(deftest pill-labels-are-short
  ;; long translated labels wrapped inside their pills (SP.4 item 9)
  (doseq [l langs
          {:keys [sections]} [(words l)]
          {:keys [blocks]} sections
          [tag & cards] blocks :when (= tag :cards)
          {:keys [chip action]} cards]
    (is (<= (count chip) 24) (str l ": chip " chip))
    (when action (is (<= (count action) 21) (str l ": " action)))))

(deftest the-privacy-note-is-marked-a-draft
  (doseq [l langs]
    (is (re-find #";; draft: the owner approves the wording before publication\n\s*\{:id\s+\"privacy\"" (slurp (str "support/i18n/" l ".edn"))) l))
  (testing "it names every tool the generator downloads, and how they are checked"
    (doseq [l langs :let [text (body-text (str (section-html (html l) "privacy")))]]
      (doseq [s ["Chroma" "Pagefind" "fswatcher" "bb fetch-tool" "bb dev" "SHA-256" "~/.cache/clogem-press/tools"
                 "CLOGEM_CHROMA" "CLOGEM_PAGEFIND" "CLOGEM_FSWATCHER" "giscus" "Google Analytics 4" "Plausible" "Umami"]]
        (is (str/includes? text s) (str l ": " s))))))

(deftest words-are-text-never-markup
  (testing "every language's words are right as they stand"
    (doseq [l langs]
      (is (empty? (sp/check-page (words l) l)) (str l ": " (pr-str (sp/check-page (words l) l))))))
  (let [hostile (-> en
                    (assoc :lead "<script>alert(1)</script> & more")
                    (assoc-in [:sections 0 :blocks 0] [:p "<b>bold</b>" [:a "https://example.com/\"onmouseover=\"x" "a <i>link</i>"]]))
        out     (sp/page-html (assoc words "en" hostile) "en" svg)]
    (testing "text is escaped"
      (is (str/includes? out "&lt;script&gt;alert(1)&lt;/script&gt; &amp; more"))
      (is (str/includes? out "&lt;b&gt;bold&lt;/b&gt;"))
      (is (str/includes? out "a &lt;i&gt;link&lt;/i&gt;"))
      (is (not (str/includes? (str/lower-case out) "<script")))
      (is (not (re-find #"\sonmouseover=" out))))
    (testing "and what isn't one of the few pieces and blocks is refused"
      (doseq [[problem f l] [["unknown piece :b"           #(assoc-in % [:sections 0 :blocks 0] [:p [:b "x"]]) "en"]
                             ["unknown piece :script"      #(assoc-in % [:sections 0 :blocks 0] [:p [:script "x"]]) "en"]
                             ["unknown block :div"         #(assoc-in % [:sections 0 :blocks 0] [:div "x"]) "en"]
                             ["https:// or mailto:"        #(assoc-in % [:sections 0 :blocks 0] [:p [:a "javascript:alert(1)" "x"]]) "en"]
                             ["its :id must be one"        #(assoc-in % [:sections 0 :id] "a b") "en"]
                             ["their own :id"              #(update % :sections (fn [s] (conj s (first s)))) "en"]
                             [":href and :action go"       #(update-in % [:sections 3 :blocks 1] (fn [b] (assoc-in b [1 :action] nil))) "en"]
                             [":url must be"               #(assoc % :url "http://echojustus.github.io/clogem-support/clogem-press/") "en"]
                             [":url must be"               identity "zh-Hans"]
                             [":lead must be text"         #(assoc % :lead " ") "en"]
                             [":en-note must be text"      #(dissoc % :en-note) "pt-BR"]
                             [":lang-name must be text"    #(dissoc % :lang-name) "en"]]]
        (let [problems (sp/check-page (f (if (= l "zh-Hans") en (words l))) l)]
          (is (some #(str/includes? % problem) problems) (str problem ": " (pr-str problems))))))))

;; ---------------------------------------------------------------------------
;; Size

(deftest every-page-keeps-its-budget
  (let [sizes (into {} (for [l langs] [l (alength (.getBytes ^String (html l) StandardCharsets/UTF_8))]))
        fmt   #(String/format java.util.Locale/ROOT "%,d" (object-array [%]))
        total (reduce + (vals sizes))]
    (is (<= sp/budget (* 150 1024)) "the budget is at most 150 KB")
    (doseq [l langs]
      (is (< 10000 (sizes l) sp/budget) (str l ": " (sizes l) " bytes"))
      (is (str/includes? (slurp "README.md") (str "| `clogem-press/" (path-of l) "` | " (fmt (sizes l)) " bytes |"))
          (str "the README states " l "'s actual size, " (fmt (sizes l)))))
    (is (= (sort (map :bytes (:result built))) (sort (vals sizes))) "the build reports what it wrote")
    (doseq [f ["README.md" "CHANGELOG.md"]]
      (is (str/includes? (slurp f) (str "**" (fmt total) " bytes** in all")) (str f " states the folder's total, " (fmt total))))))

;; ---------------------------------------------------------------------------
;; Licences

(deftest every-new-file-says-what-covers-it
  (doseq [f (concat ["support/support_page.clj" "test/clogem/support_page_test.clj" "support/fonts.edn"]
                    (for [l langs] (str "support/i18n/" l ".edn")))]
    (is (str/starts-with? (slurp f) ";; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).\n") f))
  (is (str/starts-with? (slurp ".github/workflows/support-page.yml") "# Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).\n"))
  (is (not (fs/exists? "support/page.edn")) "English lives in support/i18n/en.edn")
  (testing "the code adapted from the hub carries its MIT notice too: the generator, this file's
           `git!` and `stand-in!` (portal_test.clj's), the workflow's `publish` (portal.yml's)"
    (doseq [[f c] [["support/support_page.clj" ";;"] ["test/clogem/support_page_test.clj" ";;"]
                   [".github/workflows/support-page.yml" "#"]]
            :let [src (slurp f)]]
      (is (str/includes? src (str c "   Copyright 2026 EchoJustus\n")) f)
      (is (str/includes? src "Permission is hereby granted, free of charge") f)
      (is (str/includes? src "The above copyright notice and this permission notice shall be included") f)
      (is (str/includes? src "OTHER DEALINGS IN THE SOFTWARE.") f))))

;; ---------------------------------------------------------------------------
;; The workflow (.github/workflows/support-page.yml): its text, and its
;; publishing step run against a local stand-in for clogem-support's
;; gh-pages (portal_test.clj's `stand-in!` and `publish!`)

(def ^:private workflow-file ".github/workflows/support-page.yml")
(defn- workflow-text [] (slurp workflow-file))
(defn- workflow [] (yaml/parse-string (workflow-text)))
(defn- step [id] (some #(when (= id (:id %)) %) (get-in (workflow) [:jobs :publish :steps])))

(deftest the-workflow-text
  (let [text (workflow-text)
        wf   (workflow)
        on   (get wf (keyword "on") (get wf true))
        run  (:run (step "publish"))]
    (testing "on its sources on main, and by hand; never on a pull request (it holds a secret)"
      (is (not (re-find #"pull_request" text)))
      (is (= #{:push :workflow_dispatch} (set (keys on))))
      (is (= ["main"] (get-in on [:push :branches])))
      (is (= #{"support/**" "test/clogem/support_page_test.clj" ".github/workflows/support-page.yml" "bb.edn"}
             (set (get-in on [:push :paths])))))
    (testing "reads only; the push goes over SSH with the deploy key"
      (is (= {:contents "read"} (:permissions wf)))
      (is (every? nil? (map :permissions (vals (:jobs wf))))))
    (testing "one publication at a time, never cancelled"
      (is (= {:group "support-page" :cancel-in-progress false} (:concurrency wf))))
    (testing "every action pinned by commit"
      (let [uses (map second (re-seq #"uses:\s*(\S+)" text))]
        (is (= #{"actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
                 "DeLaGuardo/setup-clojure@6d46099eae24853c33c0482f2bb54edf4f1a4c74"
                 "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9"
                 "actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9"
                 "actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333"}
               (set uses)))))
    (testing "two jobs: build, with no secret, then publish (SP.6)"
      (is (= #{:build :publish} (set (keys (:jobs wf)))))
      (is (= "build" (get-in wf [:jobs :publish :needs])))
      (is (not (str/includes? (yaml/generate-string (get-in wf [:jobs :build])) "secrets.")) "build holds no secret")
      (is (false? (get-in (first (get-in wf [:jobs :build :steps])) [:with :persist-credentials])) "nor the checkout's token"))
    (testing "build tests the page, builds it, cuts its fonts and hands on the folder"
      (let [runs (keep :run (get-in wf [:jobs :build :steps]))]
        (is (= ["bb test --pattern support-page" "bb support-page" "support/fonts.sh"] runs)))
      (let [up (some #(when (str/starts-with? (str (:uses %)) "actions/upload-artifact@") %) (get-in wf [:jobs :build :steps]))
            down (some #(when (str/starts-with? (str (:uses %)) "actions/download-artifact@") %) (get-in wf [:jobs :publish :steps]))]
        (is (= "dist-support/clogem-press/" (get-in up [:with :path])))
        (is (= "error" (get-in up [:with :if-no-files-found])))
        (is (= (get-in up [:with :name]) (get-in down [:with :name])))
        (is (= "dist-support/clogem-press" (get-in down [:with :path])))))
    (testing "the fonts' downloads are cached by their pins"
      (let [c (some #(when (str/starts-with? (str (:uses %)) "actions/cache@") %) (get-in wf [:jobs :build :steps]))]
        (is (= "~/.cache/clogem-press/fonts" (get-in c [:with :path])))
        (is (str/includes? (str (get-in c [:with :key])) "hashFiles('support/fonts.edn')"))))
    (testing "publish runs no third-party code: no checkout, no pip, no font download, no pyftsubset"
      (let [pub (yaml/generate-string (get-in wf [:jobs :publish]))]
        (doseq [x [#"\bpip3?\b" #"pyftsubset" #"(?m)^\s*(?:run:\s*)?(?:\./)?support/fonts\.sh" #"lxgw" #"python" #"actions/checkout" #"setup-clojure" #"\bbb "]]
          (is (not (re-find x pub)) (str x)))
        (is (= #{"actions/download-artifact"} (set (keep #(some-> (:uses %) (str/split #"@") first) (get-in wf [:jobs :publish :steps])))))))
    (testing "publishes the fonts with the pages, and refuses a folder without them"
      (is (re-find #"ls dist-support/clogem-press/fonts/\*\.woff2[^\n]*\|\|\s*\{[^\n]*::error::[^\n]*exit 1" run)))
    (testing "replaces clogem-press/ with the whole built folder"
      (is (re-find #"rm -rf pages/clogem-press\n\s*mkdir -p pages/clogem-press\n\s*cp -R dist-support/clogem-press/\. pages/clogem-press/ \|\| exit 1\n" run)))
    (testing "stages clogem-press/ alone and never forces"
      (is (str/includes? run "git -C pages add -- clogem-press"))
      (is (not (re-find #"add\s+(?:[^\n]*\s)?(?:--all|-A|\.)(?:\s|$)" run)))
      (is (not (re-find #"git[^\n]*(?:--force|\s-f\b|\s\+[A-Za-z]|:\+|push[^\n]*\+)" run)))
      (is (str/includes? run "for attempt in 1 2 3")))
    (testing "the live-page wait: about 10 minutes, and only ever a warning (SP.4 item 10)"
      (let [live  (step "live")
            r     (:run live)
            limit (some-> (re-find #"\$SECONDS\" -lt (\d+)" r) second parse-long)
            try-s (some-> (re-find #"--max-time (\d+)" r) second parse-long)
            pause (some-> (re-find #"sleep (\d+)" r) second parse-long)]
        (is (= "steps.publish.outputs.published == 'true'" (:if live)))
        (is (<= (:timeout-minutes live) 12))
        (is (true? (:continue-on-error live)))
        (is (and limit try-s pause (<= (+ limit try-s pause) 600)) (str "worst case " limit "+" try-s "+" pause " s"))
        (is (not (re-find #"exit [1-9]|::error" r)))
        (is (str/includes? r "::warning::"))))
    (testing "commits as github-actions[bot]"
      (is (str/includes? run "user.name \"github-actions[bot]\""))
      (is (str/includes? run "user.email \"41898282+github-actions[bot]@users.noreply.github.com\"")))
    (testing "the key: from env only, never echoed, pinned host keys"
      (is (not (re-find #"set -x|set -[a-wyz]*x|xtrace" text)))
      (is (= ["${{ secrets.CLOGEM_SUPPORT_DEPLOY_KEY }}"] (re-seq #"\$\{\{\s*secrets\.[^}]*\}\}" text)))
      (is (= 1 (count (re-seq #"secrets\." text))) "secrets. once, in publish's step")
      (is (= "${{ secrets.CLOGEM_SUPPORT_DEPLOY_KEY }}" (get-in (step "publish") [:env :DEPLOY_KEY])))
      (is (not (re-find #"(?:echo|printf)[^\n]*\$\{?DEPLOY_KEY" (str/replace run #"printf '%s\\n' \"\$DEPLOY_KEY\" > \"\$key\"" ""))))
      (is (str/includes? run "printf '%s\\n' \"$DEPLOY_KEY\" > \"$key\"") "written to the key file only")
      (is (str/includes? run "chmod 600"))
      (is (not (str/includes? text "ssh-keyscan")))
      (doseq [o ["-o IdentitiesOnly=yes" "-o StrictHostKeyChecking=yes" "-o UserKnownHostsFile="]]
        (is (str/includes? run o) o))
      (is (= "git@github.com:EchoJustus/clogem-support.git" (get-in (step "publish") [:env :REMOTE])))
      (testing "GitHub's published host keys (docs: GitHub's SSH key fingerprints)"
        (let [lines (str/split-lines (str/trim (get-in (step "publish") [:env :KNOWN_HOSTS])))
              fp    (fn [l] (let [k (.decode (Base64/getDecoder) ^String (nth (str/split l #" ") 2))]
                              (str/replace (str "SHA256:" (.encodeToString (Base64/getEncoder) (.digest (MessageDigest/getInstance "SHA-256") k))) #"=+$" "")))]
          (is (= {"ssh-ed25519"         "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU"
                  "ecdsa-sha2-nistp256" "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM"
                  "ssh-rsa"             "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"}
                 (into {} (for [l lines] [(second (str/split l #" ")) (fp l)]))))
          (is (every? #(str/starts-with? % "github.com ") lines)))))))

(deftest the-font-step
  (let [sh (slurp "support/fonts.sh")]
    (testing "bash, strict, with the notice"
      (is (str/starts-with? sh "#!/usr/bin/env bash\n# Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).\n"))
      (is (re-find #"(?m)^set -euo pipefail$" sh))
      (is (fs/executable? "support/fonts.sh")))
    (testing "the plan from the generator; downloads over https only, each checked against its SHA-256"
      (is (str/includes? sh "support-fonts)"))
      (is (str/includes? sh "curl --fail --location --proto '=https'"))
      (is (str/includes? sh "sha256sum -c")))
    (testing "fontTools and brotli pinned by version and hash, from a here-document, into a venv"
      (is (str/includes? sh "--require-hashes --no-deps"))
      (is (str/includes? sh "-r /dev/stdin << 'EOF'"))
      (is (re-find #"fonttools==\d+\.\d+\.\d+ \\\n(?:\s+--hash=sha256:[0-9a-f]{64}(?: \\)?\n)+" sh))
      (is (re-find #"brotli==\d+\.\d+\.\d+ \\\n(?:\s+--hash=sha256:[0-9a-f]{64}(?: \\)?\n)+" sh))
      (is (str/includes? sh "python3 -m venv")))
    (testing "pyftsubset per list, as WOFF2, keeping layout features and names"
      (doseq [o ["--text-file=" "--flavor=woff2" "--layout-features='*'" "--desubroutinize" "--name-IDs='*'"]]
        (is (str/includes? sh o) o)))
    (testing "Python 3.12 or 3.13, checked before pip (whose hash error would read as tampering)"
      (is (str/includes? sh "fail \"the font step needs Python 3.12 or 3.13; found $pyver\""))
      (is (< (str/index-of sh "3.12 | 3.13) ;;") (str/index-of sh "python3 -m venv"))))
    (testing "a coverage check, the licences, the size caps"
      (is (re-find #"getBestCmap\(\)" sh))
      (is (str/includes? sh "does not cover its list"))
      (is (str/includes? sh "cp \"$cache/$name\" \"$fonts/$as\""))
      (is (str/includes? sh "over its cap")))))

(deftest no-python-in-the-repository
  (let [files (str/split-lines (:out (p/shell {:out :string} "git" "ls-files")))]
    (is (seq files))
    ;; the site's two CI checkers predate the support page and its fonts
    (is (= #{".github/scripts/check_giscus.py" ".github/scripts/check_seo.py"} (set (filter #(str/ends-with? % ".py") files)))
        "no .py file for the fonts: pyftsubset and the coverage check run from support/fonts.sh")
    (is (not-any? #(str/starts-with? % "support/") (filter #(re-find #"\.py[a-z]?$" %) files)))
    (is (not-any? #(re-find #"(?:^|/)requirements[^/]*\.txt$" %) files) "no requirements file")))

(deftest ci-cuts-the-fonts-on-every-push
  (let [ci   (yaml/parse-string (slurp ".github/workflows/ci.yml"))
        job  (get-in ci [:jobs :support-fonts])
        text (yaml/generate-string job)]
    (is (some? job))
    (is (= ["bb support-page" "support/fonts.sh"] (keep :run (:steps job))))
    (is (not (str/includes? text "secrets.")) "no secret")
    (is (false? (get-in (first (:steps job)) [:with :persist-credentials])))
    (is (every? #(re-matches #"[A-Za-z-]+/[A-Za-z-]+@[0-9a-f]{40}" %) (keep :uses (:steps job))) "every action pinned by commit")
    (let [up (some #(when (str/starts-with? (str (:uses %)) "actions/upload-artifact@") %) (:steps job))]
      (is (= "dist-support/clogem-press/" (get-in up [:with :path])) "the built folder, pages and fonts, as the run's artifact"))))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                 "git" "-c" "user.name=Test" "-c" "user.email=test@example.invalid" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) r)))
    (str/trim (:out r))))

(defn- stand-in!
  "A bare repository standing in for clogem-support's gh-pages, holding the
  front page's root files and Wmark Pro's folder; and the workflow's
  working folder, with the page built in dist-support/."
  []
  (let [root   (temp-dir)
        remote (str (fs/path root "remote.git"))
        seed   (str (fs/path root "seed"))
        work   (str (fs/path root "work"))]
    (git! root "init" "--quiet" "--bare" "--initial-branch" "gh-pages" remote)
    (git! root "init" "--quiet" "--initial-branch" "gh-pages" seed)
    (spit (fs/file seed "index.html") "<!doctype html>\n<title>Clogem Support</title>\n")
    (spit (fs/file seed "404.html") "<!doctype html>\n<title>Page not found</title>\n")
    (spit (fs/file seed ".nojekyll") "")
    (fs/create-dirs (fs/file seed "wmark-pro"))
    (spit (fs/file seed "wmark-pro" "index.html") "<!doctype html>\n<title>Wmark Pro</title>\n")
    (git! seed "add" "--all")
    (git! seed "commit" "--quiet" "-m" "The front page and Wmark Pro")
    (git! seed "push" "--quiet" remote "gh-pages")
    (sp/build! (str (fs/path work "dist-support")))
    ;; what support/fonts.sh adds; the real subsetting needs Python, so a
    ;; stand-in: a few bytes with WOFF2's signature, and the licences
    (let [fonts (fs/file work "dist-support" "clogem-press" "fonts")]
      (fs/create-dirs fonts)
      (spit (fs/file fonts "wenkai-latin-400.woff2") "wOF2 a stand-in")
      (spit (fs/file fonts "OFL-LXGW-WenKai.txt") "Copyright LXGW. SIL Open Font License 1.1\n"))
    {:root root :remote remote :seed seed :work work}))

(defn- publish!
  "The publishing step's script, run in `work` as the runner would, with the
  remote pointed at the stand-in. `key` stands in for the secret: no SSH is
  used for a local path, and no real key is ever made."
  [{:keys [root remote work]} & {:keys [key] :or {key "a stand-in, not a key"}}]
  (let [{:keys [env run]} (step "publish")
        tmp (str (fs/create-dirs (fs/path root "runner-temp")))]
    (p/shell {:dir work :out :string :err :string :continue true
              :extra-env (merge (into {} (for [[k v] env] [(name k) (str v)]))
                                {"REMOTE" remote "DEPLOY_KEY" key "RUNNER_TEMP" tmp "GITHUB_OUTPUT" (str (fs/path tmp "output"))
                                 "GIT_CONFIG_GLOBAL" "/dev/null" "GIT_CONFIG_NOSYSTEM" "1"})}
             "bash" "-e" "-c" run)))

(defn- tree [remote] (set (str/split-lines (git! remote "ls-tree" "-r" "--name-only" "gh-pages"))))
(defn- show [remote path] (git! remote "show" (str "gh-pages:" path)))

(deftest the-workflow-publishes-its-folder-alone
  (let [{:keys [remote seed work] :as s} (stand-in!)
        before (git! remote "rev-parse" "gh-pages")
        others (fn [] (into {} (for [f ["index.html" "404.html" ".nojekyll" "wmark-pro/index.html"]] [f (show remote f)])))
        theirs (others)]
    (testing "the first publication adds the five pages, and nothing else changes"
      (let [r (publish! s)]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (= (into #{"index.html" "404.html" ".nojekyll" "wmark-pro/index.html"
                       "clogem-press/fonts/wenkai-latin-400.woff2" "clogem-press/fonts/OFL-LXGW-WenKai.txt"}
                     (for [l langs] (str "clogem-press/" (path-of l))))
               (tree remote)))
        (is (= "wOF2 a stand-in" (show remote "clogem-press/fonts/wenkai-latin-400.woff2")) "the fonts, beside the pages")
        (is (= theirs (others)) "the root files and wmark-pro/ byte for byte")
        (is (= before (git! remote "rev-parse" "gh-pages~1")) "one commit, on top of the branch")
        (doseq [l langs]
          (is (= (str/trim (slurp (fs/file work "dist-support" "clogem-press" (path-of l))))
                 (show remote (str "clogem-press/" (path-of l))))
              l))
        (is (= "github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>"
               (git! remote "log" "-1" "--format=%an <%ae>" "gh-pages")))))
    (testing "a rerun with nothing new pushes nothing"
      (let [tip (git! remote "rev-parse" "gh-pages")
            r   (publish! s)]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (str/includes? (:out r) "unchanged"))
        (is (= tip (git! remote "rev-parse" "gh-pages")))))
    (testing "another writer's commit in between is kept, and a refused push is tried again, never by force"
      (git! seed "pull" "--quiet" remote "gh-pages")
      (spit (fs/file seed "wmark-pro" "index.html") "<!doctype html>\n<title>Wmark Pro, newer</title>\n")
      (git! seed "commit" "--quiet" "-am" "Publish the wmark-pro support page")
      (git! seed "push" "--quiet" remote "gh-pages")
      (spit (fs/file work "dist-support" "clogem-press" "index.html") "<!doctype html>\n<title>newer</title>\n")
      (let [hook  (fs/file remote "hooks" "pre-receive")
            newer (git! remote "rev-parse" "gh-pages")
            wmark (show remote "wmark-pro/index.html")]
        (spit hook "#!/bin/sh\nif [ ! -f refused-once ]; then touch refused-once; echo 'refused for the test' >&2; exit 1; fi\n")
        (fs/set-posix-file-permissions hook "rwxr-xr-x")
        (let [r (publish! s)]
          (is (zero? (:exit r)) (str (:out r) (:err r)))
          (is (str/includes? (:out r) "attempt 1 of 3"))
          (is (fs/exists? (fs/file remote "refused-once")))
          (is (= newer (git! remote "rev-parse" "gh-pages~1")) "on top of the other writer's commit")
          (is (= wmark (show remote "wmark-pro/index.html")))
          (is (str/includes? (show remote "clogem-press/index.html") "newer")))))
    (testing "the key file is gone afterwards"
      (is (empty? (fs/glob (fs/path (:root s) "runner-temp") "*key*"))))))

(deftest a-folder-without-its-fonts-is-not-published
  (let [{:keys [remote work] :as s} (stand-in!)
        before (git! remote "rev-parse" "gh-pages")]
    (fs/delete-tree (fs/file work "dist-support" "clogem-press" "fonts"))
    (let [r (publish! s)]
      (is (not (zero? (:exit r))))
      (is (re-find #"::error::[^\n]*fonts" (:out r)) (:out r))
      (is (= before (git! remote "rev-parse" "gh-pages"))))))

(deftest without-the-secret-the-page-is-built-but-not-published
  (let [{:keys [remote] :as s} (stand-in!)
        before (git! remote "rev-parse" "gh-pages")
        r      (publish! s :key "")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (re-find #"::notice::[^\n]*built[^\n]*not published" (:out r)) (:out r))
    (is (= before (git! remote "rev-parse" "gh-pages")))))
