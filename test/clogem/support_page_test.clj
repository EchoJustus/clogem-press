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
         :html   (into {} (for [l langs :let [f (fs/file root (path-of l))] :when (fs/exists? f)] [l (slurp f)]))})
      (finally (fs/delete-tree dir)))))

(defn- html "A built page, or \"\" if the build didn't write it." [lang] (get-in built [:html lang] ""))
(defn- style-of [html] (second (re-find #"(?s)<style>(.*?)</style>" html)))
(def ^:private style (style-of (html "en")))

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
      (testing "the CSP allows the one style by its hash, and data: images, nothing else"
        (is (= style (style-of h)) "one stylesheet, the same on every page")
        (let [content (some-> (re-find #"<meta content=\"([^\"]*)\" http-equiv=\"Content-Security-Policy\">" h) second unescape)]
          (is (= (str "default-src 'none'; style-src 'sha256-" (some-> style sha256-base64) "'; "
                      "img-src data:; base-uri 'none'; form-action 'none'")
                 content))))
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
      (is (not (re-find #"(?i)url\(\s*['\"]?(?!data:|#)" (str s))) "url( only to data: or #")
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
    (testing "no font is bundled"
      (is (not (re-find #"@font-face" style))))
    (testing "no letter-spacing on CJK: the eyebrow, the chips and the headings"
      (is (str/includes? style ":is(.eyebrow,.chip,h1,h2):lang(zh){letter-spacing:0}")))))

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
   :footer   [(set (keys (:footer page))) (fixed (get-in page [:footer :licence]))]
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
        (let [content (fn [h] (str/replace h (or (switcher h) "") ""))]
          (is (= (frequencies (hrefs-of (content (html "en")))) (frequencies (hrefs-of (content (html l))))))
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
    (is (not (re-find #"(?i)\b(?:20\d\d|q[1-4]|next (?:week|month)|coming soon)\b" text)) (str l ": no dates promised"))))

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
  (doseq [f (concat ["support/support_page.clj" "test/clogem/support_page_test.clj"]
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
                 "DeLaGuardo/setup-clojure@6d46099eae24853c33c0482f2bb54edf4f1a4c74"}
               (set uses)))))
    (testing "tests the page, then builds it"
      (is (some #(= "bb test --pattern support-page" (:run %)) (get-in wf [:jobs :publish :steps])))
      (is (some #(= "bb support-page" (:run %)) (get-in wf [:jobs :publish :steps]))))
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
        (is (= (into #{"index.html" "404.html" ".nojekyll" "wmark-pro/index.html"} (for [l langs] (str "clogem-press/" (path-of l))))
               (tree remote)))
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

(deftest without-the-secret-the-page-is-built-but-not-published
  (let [{:keys [remote] :as s} (stand-in!)
        before (git! remote "rev-parse" "gh-pages")
        r      (publish! s :key "")]
    (is (zero? (:exit r)) (str (:out r) (:err r)))
    (is (re-find #"::notice::[^\n]*built[^\n]*not published" (:out r)) (:out r))
    (is (= before (git! remote "rev-parse" "gh-pages")))))
