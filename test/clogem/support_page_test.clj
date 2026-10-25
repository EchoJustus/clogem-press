;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.support-page-test
  "The support page (DESIGN.md §7.5): `bb support-page`
  (support/support_page.clj) from support/page.edn, held to the hub's rules
  for its pages (clogem-support's test/portal_test.clj): no script, nothing
  loaded from elsewhere, its CSP, the switcher, contrast, its sections and
  words, its size."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.css-palette :as cp]
            [support-page :as sp])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private page (sp/read-page))
(def ^:private svg (sp/icon-svg))
(def ^:private html (sp/page-html page svg))
(def ^:private style (second (re-find #"(?s)<style>(.*?)</style>" html)))

(defn- sha256-base64 [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))

(defn- hrefs-of
  "Every link's address, in order."
  [html]
  (map second (re-seq #"<a [^>]*\bhref=\"([^\"]*)\"" html)))

(defn- unescape [s] (-> s (str/replace "&apos;" "'") (str/replace "&quot;" "\"") (str/replace "&amp;" "&")))

(defn- body-text
  "What a reader sees: the body without its tags."
  [html]
  (-> (subs html (str/index-of html "<body>"))
      (str/replace #"(?s)<svg.*?</svg>" " ")
      (str/replace #"<[^>]+>" " ")
      unescape))

(def sections
  "The sections, in their order (DESIGN.md §7.5)."
  ["about" "status" "start" "help" "privacy" "source"])

;; ---------------------------------------------------------------------------
;; What the page may hold

(deftest the-page-runs-nothing
  (is (not (str/includes? (str/lower-case html) "<script")))
  (is (not (re-find #"(?i)\son[a-z]+\s*=" html)) "no event handlers")
  (is (not (re-find #"(?i)\sstyle\s*=" html)) "no style attributes: the CSP would block them")
  (is (= 1 (count (re-seq #"<style>" html))) "one inline style"))

(deftest the-head-is-the-hubs
  (testing "the CSP allows the one style by its hash, and data: images, nothing else"
    (is (some? style))
    (let [content (unescape (second (re-find #"<meta content=\"([^\"]*)\" http-equiv=\"Content-Security-Policy\">" html)))]
      (is (= (str "default-src 'none'; style-src 'sha256-" (sha256-base64 style) "'; "
                  "img-src data:; base-uri 'none'; form-action 'none'")
             content))))
  (doseq [tag ["<html lang=\"en\">"
               "<meta content=\"no-referrer\" name=\"referrer\">"
               "<meta content=\"light dark\" name=\"color-scheme\">"
               "<meta content=\"#F4F6F9\" media=\"(prefers-color-scheme: light)\" name=\"theme-color\">"
               "<meta content=\"#0D1016\" media=\"(prefers-color-scheme: dark)\" name=\"theme-color\">"
               "<link href=\"https://echojustus.github.io/clogem-support/clogem-press/\" rel=\"canonical\">"
               "<meta charset=\"utf-8\">"
               "<meta content=\"width=device-width, initial-scale=1\" name=\"viewport\">"]]
    (is (str/includes? html tag) tag))
  (is (re-find #"<meta content=\"[^\"]{50,}\" name=\"description\">" html) "a description")
  (is (re-find #"<title>[^<]+</title>" html)))

(deftest images-are-data-uris-with-alt-and-size
  (let [imgs (re-seq #"<img [^>]*>" html)]
    (is (seq imgs))
    (doseq [img imgs]
      (is (re-find #"\ssrc=\"data:image/(?:svg\+xml|webp|png);base64," img) img)
      (is (re-find #"\salt=\"[^\"]*\S[^\"]*\"" img) "meaningful alt text")
      (is (re-find #"\swidth=\"\d+\"" img))
      (is (re-find #"\sheight=\"\d+\"" img))))
  (is (every? #(str/starts-with? % "data:") (map second (re-seq #"\ssrc=\"([^\"]*)\"" html))))
  (testing "the favicon is the icon, as a data:image/svg+xml URI"
    (let [link (re-find #"<link\b[^>]*\brel=\"icon\"[^>]*>" html)]
      (is (str/includes? link (str "href=\"" (sp/data-uri svg) "\"")))
      (is (str/includes? link "type=\"image/svg+xml\""))
      ;; the hub's front page takes an app's icon from its favicon, with
      ;; this pattern (clogem-support's scripts/portal.clj, `app-icon`)
      (is (= (sp/data-uri svg)
             (second (re-find #"\bhref=\"(data:image/svg\+xml;base64,[A-Za-z0-9+/]+={0,2})\"" link))))))
  (testing "the header shows the icon"
    (is (re-find (re-pattern (str "<img alt=\"[^\"]+\" class=\"mark\"[^>]*src=\"" (java.util.regex.Pattern/quote (sp/data-uri svg)) "\"")) html))))

(deftest nothing-is-loaded-from-elsewhere
  (is (not (re-find #"<link [^>]*rel=\"stylesheet\"" html)))
  (is (= #{"canonical" "icon"} (set (map second (re-seq #"<link [^>]*rel=\"([^\"]+)\"" html)))))
  (is (not (str/includes? style "@import")))
  (is (not (re-find #"(?i)url\(\s*['\"]?(?!data:|#)" style)) "url( only to data: or #")
  (doseq [tag ["iframe" "embed" "object" "video" "audio" "source" "form" "base" "frame" "portal"]]
    (is (not (re-find (re-pattern (str "(?i)<" tag "\\b")) html)) tag))
  (testing "every outside address is a plain link: no other tag or attribute refers to one"
    (let [refs (for [[_ tag attrs] (re-seq #"<([a-z][a-z0-9]*)\b([^>]*)>" html)
                     [_ attr v] (re-seq #"\s([a-z-]+)=\"([^\"]*)\"" attrs)
                     :when (re-find #"(?i)^\s*(?:[a-z][a-z0-9+.-]*:|//)" v)
                     :when (not (str/starts-with? v "data:"))]
                 [tag attr v])]
      (is (seq refs))
      (is (every? (fn [[tag attr v]]
                    (or (and (= ["a" "href"] [tag attr]) (re-matches #"(?:https://|mailto:)\S+" v))
                        (= ["link" "href" "https://echojustus.github.io/clogem-support/clogem-press/"] [tag attr v])))
                  refs)
          (pr-str (remove (fn [[tag attr]] (= ["a" "href"] [tag attr])) refs)))))
  (testing "every link is https, mailto or a fragment of this page"
    (is (every? #(re-matches #"https://\S+|mailto:\S+|#[a-z-]+" %) (hrefs-of html)))))

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
;; The switcher (the hub's, test/portal_test.clj's checks)

(deftest the-theme-switcher-takes-no-script
  (let [radios (re-seq #"<input [^>]*name=\"theme\"[^>]*>" html)
        id     #(second (re-find #"id=\"([^\"]+)\"" %))]
    (is (= ["theme-system" "theme-light" "theme-dark"] (map id radios)))
    (is (every? #(str/includes? % "type=\"radio\"") radios))
    (is (= ["theme-system"] (keep #(when (re-find #"\schecked\b" %) (id %)) radios)) "Auto when the page opens")
    (doseq [i ["system" "light" "dark"]]
      (is (str/includes? html (str "<label for=\"theme-" i "\""))))
    (is (re-find #"<fieldset class=\"theme\"><legend>Colour theme</legend>" html) "named for screen readers")
    (is (re-find #">Auto</label>.*>Light</label>.*>Dark</label>" html))
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
    (testing "print: no switcher, no jump links, black on white"
      (let [print (second (re-find #"@media print\{(.*)\}$" style))]
        (is (some? print))
        (is (re-find #"\.jump,\.theme[^{]*\{display:none\}" print))
        (is (str/includes? print "--text:#000"))
        (is (str/includes? print "body{background:#fff;color:#000}"))))))

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
  (let [ids (map second (re-seq #"<section aria-labelledby=\"[^\"]+\" id=\"([^\"]+)\">" html))]
    (is (= sections ids))
    (is (= sections (map :id (:sections page))) "page.edn's order"))
  (testing "each opens with its own heading, which it is labelled by"
    (doseq [{:keys [id title]} (:sections page)]
      (is (str/includes? html (str "<section aria-labelledby=\"" id "-title\" id=\"" id "\"><h2 id=\"" id "-title\">"
                                   (-> title (str/replace "&" "&amp;") (str/replace "'" "&apos;")) "</h2>"))
          id)))
  (testing "no overline repeating a heading (the hub's rule, owner 2026-10-02)"
    (is (not (re-find #"kicker|overline" html))))
  (testing "the jump nav lists them, in the same order"
    (let [nav (second (re-find #"(?s)<nav aria-label=\"On this page\" class=\"jump\">(.*?)</nav>" html))]
      (is (some? nav))
      (is (= (map #(str "#" %) sections) (hrefs-of nav)))))
  (testing "one h1, and the levels step down one at a time"
    (let [levels (map #(parse-long (second %)) (re-seq #"<h([1-6])\b" html))]
      (is (= 1 (count (filter #{1} levels))))
      (is (= 1 (first levels)))
      (is (every? (fn [[a b]] (<= b (inc a))) (partition 2 1 levels)) (pr-str levels))
      (is (every? #(<= % 3) levels))))
  (testing "a header with the icon and the name, a main and a footer"
    (is (re-find #"(?s)<header class=\"hero\">.*<img [^>]*class=\"mark\".*<h1>clogem-press</h1>" html))
    (is (re-find #"(?s)</header><main>.*</main><footer>" html))))

(deftest the-footer-links-back-to-the-hub
  (let [footer (second (re-find #"(?s)<footer>(.*)</footer>" html))]
    (is (str/includes? footer "<a href=\"https://echojustus.github.io/clogem-support/\">"))
    (is (str/includes? footer "Eclipse Public License 2.0"))
    (is (str/includes? footer "This page sets no cookies and loads nothing from other sites."))))

;; ---------------------------------------------------------------------------
;; Words

(deftest no-commerce-and-one-address
  (let [text (body-text html)]
    (is (not (re-find #"(?i)\b(?:stores?|price[sd]?|pricing|trials?|purchas\w*|buy|upsell|subscri\w*|checkout|discount|licen[cs]e keys?)\b|[$€£¥]\s?\d" text))
        (re-find #"(?i).{0,40}\b(?:stores?|price|trial|purchas\w*|buy)\b.{0,40}" text))
    (is (not (re-find #"(?i)wmark pro|\bpro edition" text)) "no other product's name, no Pro edition"))
  (testing "no address but the hub's, on the page or in support/"
    (doseq [[what s] (into {"the page" html} (for [f (fs/glob "support" "*")] [(str f) (slurp (str f))]))]
      (is (= #{} (disj (set (re-seq #"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+" s)) "EchoJustus.Studio@outlook.com"))
          what))
    (is (str/includes? html "href=\"mailto:EchoJustus.Studio@outlook.com?subject=clogem-press\""))))

(deftest help-goes-where-it-belongs
  (let [hrefs (set (hrefs-of html))]
    (testing "code: issues and pull requests in this repository"
      (is (hrefs "https://github.com/EchoJustus/clogem-press/issues"))
      (is (hrefs "https://github.com/EchoJustus/clogem-press/pulls")))
    (testing "questions and ideas: the hub's Discussions"
      (is (hrefs "https://github.com/EchoJustus/clogem-support/discussions/new?category=q-a"))
      (is (hrefs "https://github.com/EchoJustus/clogem-support/discussions/new?category=ideas")))
    (testing "never this repository's Discussions, which are off"
      (is (not-any? #(str/includes? % "clogem-press/discussions") hrefs)))
    (testing "GitHub is linked to, never embedded; and the page says it is public"
      (is (every? #(re-matches #"https://\S+|mailto:\S+|#[a-z-]+" %) hrefs))
      (is (re-find #"(?i)public" (body-text html)))
      (is (re-find #"(?i)leave out personal details" (body-text html))))
    (testing "the privacy note links GitHub's privacy statement"
      (is (some #(str/includes? % "docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement") hrefs)))))

(deftest the-status-follows-the-changelog
  (let [text (body-text html)]
    (is (str/includes? text "0.2.0"))
    (is (re-find #"(?i)in progress" text))
    (is (re-find #"(?i)not released|unreleased" text))
    (is (not (re-find #"(?i)\b(?:20\d\d|q[1-4]|next (?:week|month)|coming soon)\b" text)) "no dates promised")
    (is (re-find #"(?i)five languages" text))))

(deftest the-privacy-note-is-marked-a-draft
  (let [src (slurp "support/page.edn")]
    (is (re-find #";; draft: the owner approves the wording before publication\n\s*\{:id\s+\"privacy\"" src)))
  (testing "it names every tool the generator downloads, and how they are checked"
    (let [text (body-text html)]
      (doseq [s ["Chroma" "Pagefind" "fswatcher" "bb fetch-tool" "SHA-256" "giscus" "analytics"]]
        (is (str/includes? text s) s)))))

(deftest words-are-text-never-markup
  (testing "page.edn is right as it stands"
    (is (empty? (sp/check-page page)) (pr-str (sp/check-page page))))
  (let [hostile (-> page
                    (assoc :lead "<script>alert(1)</script> & more")
                    (assoc-in [:sections 0 :blocks 0] [:p "<b>bold</b>" [:a "https://example.com/\"onmouseover=\"x" "a <i>link</i>"]]))
        out     (sp/page-html hostile svg)]
    (testing "text is escaped"
      (is (str/includes? out "&lt;script&gt;alert(1)&lt;/script&gt; &amp; more"))
      (is (str/includes? out "&lt;b&gt;bold&lt;/b&gt;"))
      (is (str/includes? out "a &lt;i&gt;link&lt;/i&gt;"))
      (is (not (str/includes? (str/lower-case out) "<script")))
      (is (not (re-find #"\sonmouseover=" out))))
    (testing "and what isn't one of the few pieces and blocks is refused"
      (doseq [[problem f] {"unknown piece :b"           #(assoc-in % [:sections 0 :blocks 0] [:p [:b "x"]])
                           "unknown piece :script"      #(assoc-in % [:sections 0 :blocks 0] [:p [:script "x"]])
                           "unknown block :div"         #(assoc-in % [:sections 0 :blocks 0] [:div "x"])
                           "https:// or mailto:"        #(assoc-in % [:sections 0 :blocks 0] [:p [:a "javascript:alert(1)" "x"]])
                           "its :id must be one"        #(assoc-in % [:sections 0 :id] "a b")
                           "their own :id"              #(update % :sections (fn [s] (conj s (first s))))
                           ":href and :action go"       #(update-in % [:sections 3 :blocks 1] (fn [b] (assoc-in b [1 :action] nil)))
                           ":url must be the https"     #(assoc % :url "http://echojustus.github.io/clogem-support/clogem-press/")
                           ":lead must be text"         #(assoc % :lead " ")}]
        (let [problems (sp/check-page (f page))]
          (is (some #(str/includes? % problem) problems) (str problem ": " (pr-str problems))))))))

;; ---------------------------------------------------------------------------
;; Size

(deftest the-page-keeps-its-budget
  (let [dir (str (fs/create-temp-dir {:prefix "support-page"}))]
    (try
      (let [{:keys [file bytes]} (sp/build! dir)]
        (is (= (str (fs/file dir "clogem-press" "index.html")) file))
        (is (= html (slurp file)) "the build writes the page these tests read")
        (is (<= bytes sp/budget) (str bytes " bytes"))
        (is (<= sp/budget (* 150 1024)) "the budget is at most 150 KB"))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; Licences

(deftest every-new-file-says-what-covers-it
  (doseq [f ["support/support_page.clj" "support/page.edn" "test/clogem/support_page_test.clj"]]
    (is (str/starts-with? (slurp f) ";; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).\n") f))
  (testing "the code adapted from the hub carries its MIT notice too"
    (let [src (slurp "support/support_page.clj")]
      (is (str/includes? src ";;   Copyright 2026 EchoJustus\n"))
      (is (str/includes? src "Permission is hereby granted, free of charge"))
      (is (str/includes? src "The above copyright notice and this permission notice shall be included"))
      (is (str/includes? src "OTHER DEALINGS IN THE SOFTWARE.")))))
