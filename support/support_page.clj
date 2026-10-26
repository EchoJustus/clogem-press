;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
;;
;; Parts of this file (the stylesheet's structure and the light and dark
;; switching, the theme switcher, `csp`, `data-uri`, `icon-problem` and the
;; page's <head>) are adapted from scripts/portal.clj of EchoJustus/clogem-support,
;; under the MIT License:
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
(ns support-page
  "clogem-press's page on the Clogem support site,
  https://echojustus.github.io/clogem-support/clogem-press/ (DESIGN.md §7.5),
  in five languages: English there, the others in folders inside it named
  by their BCP 47 tags (zh-Hant/, zh-Hans/, pt-BR/, es-ES/).

    bb support-page                 writes dist-support/clogem-press/
    bb support-page --out DIR       writes DIR/clogem-press/

  The words are support/i18n/<tag>.edn's, the icon support/clogem-press.svg;
  this file adds markup only. Each page is built as the hub's own pages are
  (clogem-support's scripts/portal.clj): one self-contained file, no
  JavaScript, nothing loaded from another site, a Content-Security-Policy
  that allows only its own style (by its SHA-256) and data: images, light
  and dark after the system or the header's switcher. It is not part of
  the site generator (src/clogem/) and shares no code with it.
  .github/workflows/support-page.yml publishes it to clogem-support's
  gh-pages, in clogem-press/ alone."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [hiccup2.core :as h])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private here
  "This file's folder: i18n/ and the icon sit beside it, wherever bb runs."
  (str (fs/parent (fs/absolutize *file*))))

(def folder "The page's folder on gh-pages." "clogem-press")

(def base-url
  "The English page's address; the others are folders inside it."
  "https://echojustus.github.io/clogem-support/clogem-press/")

(def languages
  "The page's languages, in the switcher's order: English, the default,
  first, then Traditional Chinese before Simplified (the owner's order,
  SP.6). :also is a wider tag the page is offered for too (hreflang)."
  [{:tag "en"} {:tag "zh-Hant"} {:tag "zh-Hans"} {:tag "pt-BR" :also "pt"} {:tag "es-ES" :also "es"}])

(def tags (mapv :tag languages))

(defn page-url [tag] (if (= tag "en") base-url (str base-url tag "/")))

(defn page-path
  "Where a language's page sits in clogem-press/."
  [tag]
  (if (= tag "en") "index.html" (str tag "/index.html")))

(defn- relative-href
  "The link from `from`'s page to `to`'s: each sits in its own folder, the
  others' inside English's."
  [from to]
  (let [up (if (= from "en") "" "../")]
    (cond (= from to)   "./"
          (= to "en")   up
          :else         (str up to "/"))))

(def budget
  "Each built page's byte budget (at most 150 KB, the task's limit); the
  build and `bb test` both hold it."
  65536)

(defn- fail! [& msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit 1))

(defn read-page
  "A language's words, read: support/i18n/<tag>.edn."
  [tag]
  (edn/read-string (slurp (str (fs/path here "i18n" (str tag ".edn"))))))

(defn read-pages "Every language's words, by tag." [] (into {} (for [t tags] [t (read-page t)])))

(defn icon-svg [] (slurp (str (fs/path here "clogem-press.svg"))))

;; ---------------------------------------------------------------------------
;; The words: checked, then turned into markup (text is always escaped)

(defn- text? [v] (and (string? v) (not (str/blank? v))))

(defn- href? [v]
  (and (string? v) (re-matches #"(?:https://|mailto:)[^\s\"<>]+" v)))

(defn- piece-problem
  "Why `x` can't be a piece of a paragraph, or nil."
  [x]
  (cond (string? x) nil
        (not (vector? x)) (str "a piece must be text or a vector, not " (pr-str x))
        :else (let [[tag & args] x]
                (case tag
                  :a                (when-not (and (= 2 (count args)) (href? (first args)) (text? (second args)))
                                      (str "a link is [:a \"https://…\" \"words\"] (https:// or mailto:), not " (pr-str x)))
                  (:code :strong)   (when-not (and (= 1 (count args)) (text? (first args)))
                                      (str "[" tag " \"words\"], not " (pr-str x)))
                  (str "unknown piece " (pr-str tag) " (only :a, :code, :strong)")))))

(defn- pieces-problems [where pieces]
  (if-not (and (sequential? pieces) (seq pieces))
    [(str where ": needs some words")]
    (for [p pieces :let [problem (piece-problem p)] :when problem] (str where ": " problem))))

(defn- block-problems [where [tag & args :as block]]
  (case tag
    :p     (pieces-problems where args)
    :ul    (if (empty? args) [(str where ": an empty list")]
               (mapcat #(pieces-problems (str where ", a list item") %) args))
    :pre   (when-not (and (= 1 (count args)) (text? (first args))) [(str where ": [:pre \"commands\"]")])
    :h3    (when-not (and (= 1 (count args)) (text? (first args))) [(str where ": [:h3 \"words\"]")])
    :cards (if (empty? args) [(str where ": no cards")]
               (mapcat (fn [{:keys [chip title text href action] :as card}]
                         (let [w (str where ", card " (pr-str (or title card)))]
                           (concat (when-not (and (map? card) (text? chip) (text? title))
                                     [(str w ": needs :chip and :title")])
                                   (pieces-problems w text)
                                   (when-not (= (some? href) (some? action))
                                     [(str w ": :href and :action go together")])
                                   (when (and href (not (and (href? href) (text? action))))
                                     [(str w ": :href must be https:// or mailto:, with an :action")]))))
                       args))
    [(str where ": unknown block " (pr-str tag) " (:p, :ul, :pre, :h3 or :cards)")]))

(defn check-page
  "A language's words' problems, in words."
  ([page] (check-page page "en"))
  ([{:keys [url hub sections theme footer] :as page} tag]
  (concat
   (for [k (cond-> [:url :hub :title :description :eyebrow :name :subtitle :icon-alt :lead :jump-label
                    :lang-name :lang-label :langs-label]
             (not= tag "en") (conj :en-note))
         :when (not (text? (get page k)))]
     (str k " must be text"))
   (when-not (= url (page-url tag))
     [(str ":url must be " (page-url tag) ", its page's address")])
   (when-not (and (string? hub) (str/starts-with? hub "https://") (str/ends-with? hub "/"))
     [":hub must be https and end with /"])
   (when-not (and (text? (:legend theme))
                  (every? #(and (vector? (get theme %)) (= 2 (count (get theme %))) (every? text? (get theme %)))
                          [:system :light :dark]))
     [":theme needs :legend, and :system, :light and :dark as [label title]"])
   (when-not (and (text? (:hub footer)) (text? (:note footer))) [":footer needs :hub and :note"])
   (pieces-problems ":footer :licence" (:licence footer))
   (when-not (seq sections) [":sections must list at least one section"])
   (when (and (seq sections) (not (apply distinct? (map :id sections))))
     [":sections must each have their own :id"])
   (mapcat (fn [{:keys [id title blocks] :as s}]
             (let [where (str "section " (pr-str (or id s)))]
               (concat (when-not (and (string? id) (re-matches #"[a-z][a-z0-9-]*" id))
                         [(str where ": its :id must be one lowercase name")])
                       (when-not (text? title) [(str where ": needs a :title")])
                       (if (seq blocks)
                         (mapcat #(block-problems where %) blocks)
                         [(str where ": needs :blocks")]))))
           sections))))

;; ---------------------------------------------------------------------------
;; The languages, in step: a translation changes words, never what they
;; point at or the commands they show

(defn- fixed
  "What a translation keeps of some pieces: its links' targets and its
  code, in any order (word order differs between languages)."
  [pieces]
  (sort (map pr-str (for [x pieces :when (and (vector? x) (#{:a :code} (first x)))]
                      (if (= :a (first x)) [:a (second x)] x)))))

(defn- block-shape [[tag & args]]
  (case tag
    :p     [:p (fixed args)]
    :ul    [:ul (map fixed args)]
    :pre   [:pre (first args)]
    :h3    [:h3]
    :cards [:cards (for [c args] [(set (keys c)) (:href c) (fixed (:text c))])]
    [tag]))

(defn- shape
  "Everything a translation shares with English, by part."
  [page]
  (into {"its keys"     (set (keys (dissoc page :en-note :url)))
         ":theme's keys" (set (keys (:theme page)))
         ":footer"       [(set (keys (:footer page))) (fixed (get-in page [:footer :licence]))]
         ":hub and :name" (select-keys page [:hub :name])
         "its sections"  (mapv :id (:sections page))}
        (for [{:keys [id blocks] :as s} (:sections page)]
          [(str "section " (pr-str id)) [(set (keys s)) (mapv block-shape blocks)]])))

(defn parity-problems
  "Where a translation's sections, blocks, link targets, code or commands
  differ from English's, in words; `pages` is {tag words}."
  [pages]
  (let [en (shape (get pages "en"))]
    (for [tag (rest tags)
          :let [page (get pages tag)]
          problem (if-not (map? page)
                    [(str tag ": missing")]
                    (let [it (shape page)]
                      (for [[part v] en :when (not= v (get it part))]
                        (str tag ": " part " differ from English's"))))]
      problem)))

;; ---------------------------------------------------------------------------
;; Links to pages in English only

(def english-only
  "Pages that are in English only, whatever the reader's language: on the
  other pages, their links carry hreflang=\"en\" and the page's :en-note."
  #{"https://github.com/EchoJustus/clogem-press#readme"
    "https://github.com/EchoJustus/clogem-press/blob/main/CHANGELOG.md"
    "https://github.com/EchoJustus/clogem-press/blob/main/DESIGN.md"
    "https://github.com/EchoJustus/clogem-press/blob/main/LICENSE"
    "https://github.com/EchoJustus/clogem-press/tree/main/examples/demo-site"
    "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"
    "https://babashka.org/"
    "https://echojustus.github.io/clogem-support/"})

(def ^:private ^:dynamic *en-note*
  "The note after a link to a page in English, on a page that isn't; nil on
  the English page."
  nil)

(defn- link [href words]
  (if (and *en-note* (english-only href))
    (list [:a {:href href :hreflang "en"} words] [:span.en-note *en-note*])
    [:a {:href href} words]))

(def short-code
  "Inline code this long or shorter keeps one line (`bb dev`, an environment
  variable); longer code, a path, may break, so nothing overflows at 320 px."
  24)

(defn- piece [x]
  (if (string? x)
    x
    (let [[tag & args] x]
      (case tag
        :a      (link (first args) (second args))
        :code   (let [c (first args)] (if (<= (count c) short-code) [:code.nw c] [:code c]))
        :strong [:strong (first args)]))))

(defn- pieces [xs] (map piece xs))

(defn- block [[tag & args]]
  (case tag
    :p     [:p (pieces args)]
    :ul    [:ul (for [item args] [:li (pieces item)])]
    :pre   [:pre [:code (first args)]]
    :h3    [:h3 (first args)]
    :cards [:div.cards
            (for [{:keys [chip title text href action]} args]
              [:article.card
               [:span.chip chip]
               [:h3 title]
               [:p (pieces text)]
               (when href
                 [:a.button {:href href} action [:span {:aria-hidden "true"} "→"]])])]))

;; ---------------------------------------------------------------------------
;; The icon

(defn icon-problem
  "Why the icon can't go on the page, or nil: it must be a plain SVG
  drawing, with nothing that runs or reaches outside it (portal.clj's
  `icon-problem`, the hub's check)."
  [svg]
  (cond (not (re-find #"<svg\b" svg))                     "it isn't an SVG"
        (re-find #"(?i)<script|<foreignObject|<image\b" svg) "it has a script, foreign content or an image in it"
        (re-find #"(?i)\son[a-z]+\s*=" svg)                 "it has an event handler"
        (re-find #"(?i)href\s*=\s*[\"'](?!#)" svg)          "it links outside itself"
        (re-find #"(?i)url\(\s*[\"']?(?!#)" svg)            "it refers to something outside itself"))

(defn data-uri
  "An SVG as a base64 data: URI: the form the hub's front page takes an
  app's icon from (portal.clj's `app-icon` reads the favicon's)."
  [svg]
  (str "data:image/svg+xml;base64,"
       (.encodeToString (Base64/getEncoder) (.getBytes ^String svg StandardCharsets/UTF_8))))

;; ---------------------------------------------------------------------------
;; The fonts: LXGW WenKai for all text but headings and code (support/fonts.edn,
;; the plan), in subsets cut by support/fonts.sh from the character lists
;; the build writes

(defn font-plan
  "support/fonts.edn: the fonts' pins, the zh-Hant page's font and the
  subsets."
  []
  (edn/read-string (slurp (str (fs/path here "fonts.edn")))))

(defn- source-name
  "A pinned file's name in fonts.sh's cache: its repository, tag and file."
  [{:keys [repo tag]} url]
  (str (last (str/split repo #"/")) "-" tag "-" (last (str/split url #"/"))))

(defn font-plan-lines
  "The plan as lines for support/fonts.sh (`bb support-fonts`):
    font <cached name> <url> <sha256>
    licence <cached name> <url> <sha256> <published name>
    subset <id> <cached name of its face> <cap in bytes>"
  ([] (font-plan-lines (font-plan)))
  ([{:keys [fonts subsets]}]
   (concat
    (for [[_ f] (sort fonts), [_ {:keys [url sha256]}] (sort (:faces f))]
      (str/join " " ["font" (source-name f url) url sha256]))
    (for [[_ {{:keys [url sha256 as]} :licence :as f}] (sort fonts)]
      (str/join " " ["licence" (source-name f url) url sha256 as]))
    (for [[id {:keys [font weight cap]}] (sort subsets)
          :let [f (get fonts font)]]
      (str/join " " ["subset" id (source-name f (get-in f [:faces weight :url])) cap])))))

(defn print-font-plan [] (run! println (font-plan-lines)))

(defn font-of
  "The font text in language `lang` is set in: the plan's :zh-Hant for
  Traditional Chinese, LXGW WenKai for the rest."
  [plan lang]
  (if (= lang "zh-Hant") (:zh-Hant plan) :wenkai))

(def cjk-from
  "Code points from here up are a font's Chinese subset, below it its
  Latin (with punctuation, arrows and the like); the unicode-ranges split there."
  0x2E80)

(defn subset-id
  "The subset that sets code point `cp` of a run in language `lang`, at
  `weight`, on `page`'s page. A page's own language's text is in its own
  subsets; another language's, which is only the switcher's name for it,
  in a small list of its own, so a reader downloads a few glyphs for it,
  not that language's page."
  [plan page lang weight cp]
  (let [font (font-of plan lang)]
    (cond (= font :wenkai-tc)                      (str "wenkai-tc-" (when-not (= page "zh-Hant") "names-") weight)
          (< cp cjk-from)                          (str "wenkai-latin-" weight)
          (= page "zh-Hans")                       (str "wenkai-sc-" weight)
          (and (= page "zh-Hant") (= lang page))   (str "wenkai-hant-" weight)
          :else                                    (str "wenkai-names-" weight))))

(defn- tag-classes
  "A hiccup tag's element name and classes: :p.eyebrow is [\"p\" #{\"eyebrow\"}]."
  [k]
  (let [[t & cs] (str/split (name k) #"\.")] [t (set cs)]))

(def ^:private not-in-wenkai
  "Elements whose text keeps the system's fonts (headings, code), or isn't
  text a reader sees."
  #{"h1" "h2" "h3" "code" "pre" "head" "title" "style" "svg"})

(defn- runs
  "[lang weight text] for each run of `node`'s text set in WenKai, as the
  stylesheet sets it: Medium (500) for <strong>, the chips, the buttons,
  the eyebrow, the theme switcher's labels, the language summary and its
  links and the jump links; Regular (400) for the rest."
  [node {:keys [lang medium? in] :as ctx}]
  (cond
    (string? node) [[lang (if medium? 500 400) node]]
    (vector? node) (let [[t & more] node
                         [tag cls]  (tag-classes t)
                         attrs      (when (map? (first more)) (first more))
                         kids       (if attrs (rest more) more)
                         in'        (cond-> in
                                      (cls "theme") (conj :theme)
                                      (cls "langs") (conj :langs)
                                      (cls "jump")  (conj :jump))
                         ctx'       {:lang    (or (:lang attrs) lang)
                                     :in      in'
                                     :medium? (boolean (or medium? (= tag "strong") (some cls ["chip" "button" "eyebrow"])
                                                           (and (= tag "label") (in :theme))
                                                           (and (= tag "summary") (in :langs))
                                                           (and (= tag "a") (or (in :langs) (in :jump)))))}]
                     (when-not (not-in-wenkai tag) (mapcat #(runs % ctx') kids)))
    (seq? node)    (mapcat #(runs % ctx) node)
    :else          nil))

(defn- code-points [^String s] (.toArray (.codePoints s)))

(def ^:private always
  "Printable ASCII: always in the Latin subsets and the zh-Hant page's, so
  a small change of wording doesn't drop a glyph."
  (set (range 0x20 0x7F)))

(defn- always-ascii? [id] (re-matches #"wenkai-(?:latin|tc|hant)-\d+" id))

(defn page-glyphs
  "{subset id #{code points}} for a page's body: what it sets in which
  subset (without the ASCII every Latin subset carries)."
  [plan page body]
  (apply merge-with into {}
         (for [[lang weight text] (runs body {:lang page :in #{} :medium? false})
               cp (code-points text)
               :when (>= cp 0x20)]
           {(subset-id plan page lang weight cp) #{cp}})))

;; ---------------------------------------------------------------------------
;; The look: the hub's quiet ground, with clogem-press's jade as the accent

(def jade
  "clogem-press's hue in the clogem family, and its icon's two ends (the
  hub's card for clogem-press)."
  {:hue "#23A982" :light "#6BDDB7" :deep "#0A7C5B"})

(def light
  "Light tokens. #23A982 itself is under 4.5:1 on white, so text and
  controls take a darker jade; the pairs are tested (support_page_test)."
  {"--bg" "#F4F6F9" "--glow-1" "rgba(35,169,130,.14)" "--glow-2" "rgba(79,128,227,.10)"
   "--surface" "#FFFFFF" "--surface-2" "#EDF3F1" "--border" "rgba(20,45,38,.12)"
   "--text" "#141922" "--muted" "#4D5868" "--heading" "#0D1220" "--link" "#0A6B4F"
   "--chip" "#E0F3EB" "--chip-ink" "#075C44" "--focus" "#0C8A65" "--control" "#6B8A80"})

(def dark
  "Dark tokens: a lighter jade for text on the dark ground."
  {"--bg" "#0D1016" "--glow-1" "rgba(35,169,130,.16)" "--glow-2" "rgba(79,128,227,.12)"
   "--surface" "#151A23" "--surface-2" "#1A202B" "--border" "rgba(160,190,180,.15)"
   "--text" "#E9EDF4" "--muted" "#A2ABBC" "--heading" "#F5F7FB" "--link" "#5FD8B0"
   "--chip" "rgba(95,216,176,.13)" "--chip-ink" "#86E5C6" "--focus" "#3FCB9C" "--control" "#64847A"})

(def colour-tokens
  "The tokens, in order; registered as colours so they fade when the theme
  changes."
  ["--bg" "--glow-1" "--glow-2" "--surface" "--surface-2" "--border" "--text" "--muted"
   "--heading" "--link" "--chip" "--chip-ink" "--focus" "--control"])

(defn- decls [tokens] (str/join ";" (for [t colour-tokens] (str t ":" (tokens t)))))

(def ^:private shadow-light "--shadow:0 1px 2px rgba(13,18,32,.05),0 18px 40px -24px rgba(10,124,91,.30)")
(def ^:private shadow-dark "--shadow:0 1px 2px rgba(0,0,0,.35),0 24px 48px -24px rgba(0,0,0,.65)")

(def ^:private heading-font
  "\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto")

(def ^:private text-font
  "\"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial")

(def ^:private cjk-fonts
  "The systems' own CJK fonts (no font is bundled), after the Latin ones and
  ahead of the generic family, by :lang()."
  {"zh-Hans" "\"PingFang SC\",\"Microsoft YaHei\",\"Noto Sans CJK SC\",\"Source Han Sans SC\""
   "zh-Hant" "\"PingFang TC\",\"Microsoft JhengHei\",\"Noto Sans CJK TC\",\"Source Han Sans TC\""})

(defn- font-stacks
  "--text-font and --heading-font, with `cjk` (or nothing) before sans-serif."
  [cjk]
  (let [with (fn [latin] (str latin (when cjk (str "," cjk)) ",sans-serif"))]
    (str "--text-font:" (with text-font) ";--heading-font:" (with heading-font))))

(defn css
  "The page's one stylesheet. The theme follows the system, or the switcher:
  three radio buttons read by :has() on the root, so it takes no script;
  without a script or a cookie the choice isn't remembered, and the page
  opens on Auto."
  []
  (let [dark-decls (str "color-scheme:dark;" (decls dark) ";" shadow-dark)
        rule       (str "linear-gradient(90deg," (:light jade) "," (:hue jade) " 55%," (:deep jade) ")")]
    (str/join
     "\n"
     (concat
      (for [t colour-tokens]
        (str "@property " t "{syntax:\"<color>\";inherits:true;initial-value:transparent}"))
      [(str ":root{color-scheme:light dark;" (decls light) ";" shadow-light ";"
            "transition:" (str/join "," (map #(str % " .35s ease") colour-tokens)) "}")
       (str "@media (prefers-color-scheme:dark){:root:not(:has(#theme-light:checked)){" dark-decls "}}")
       (str ":root:has(#theme-dark:checked){" dark-decls "}")
       ":root:has(#theme-light:checked){color-scheme:light}"
       (str ":root{" (font-stacks nil) "}")
       ;; on any element, so a switcher label in another script gets its fonts
       (str/join (for [[tag cjk] cjk-fonts] (str ":lang(" tag "){" (font-stacks cjk) "}")))
       "*,*::before{box-sizing:border-box}"
       "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}"
       (str "body{margin:0;min-height:100vh;color:var(--text);background:"
            "radial-gradient(1100px 560px at 8% -12%,var(--glow-1),transparent 62%),"
            "radial-gradient(900px 520px at 100% -4%,var(--glow-2),transparent 58%),var(--bg);"
            "font:400 1rem/1.65 var(--text-font);-webkit-font-smoothing:antialiased;overflow-wrap:break-word}")
       ".page{max-width:62rem;margin:0 auto;padding:clamp(2.5rem,7vw,5.5rem) 1rem 2.5rem}"
       "a{color:var(--link);text-underline-offset:.18em}"
       "a:focus-visible{outline:3px solid var(--focus);outline-offset:3px;border-radius:.45rem}"
       "h1,h2,h3{font-family:var(--heading-font);color:var(--heading)}"
       ;; CJK: no letter-spacing (it breaks up the characters), a little more line height
       ":is(.eyebrow,.chip,h1,h2):lang(zh){letter-spacing:0}"
       "body:lang(zh){line-height:1.8}"
       ".en-note{color:var(--muted);font-size:.9em}"
       "code,pre{font-family:ui-monospace,\"Cascadia Code\",\"SF Mono\",Menlo,Consolas,monospace;font-size:.9em}"
       ;; the header
       ;; one column that may shrink below its content's width, so nothing in
       ;; the header can widen the page past its gutter
       ".hero{display:grid;grid-template-columns:minmax(0,1fr);gap:1.4rem}"
       ".top{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:1rem 1.5rem}"
       ;; the brand's basis is the least it needs, its name whole (below), so
       ;; it never shrinks under the name: when the brand and the switchers
       ;; don't both fit, the switchers wrap below it. Beside it they take the
       ;; room they want first (their larger grow factor, up to their width),
       ;; and the brand's subtitle the rest.
       ".brand{flex:1 1 min-content;display:flex;align-items:center;gap:clamp(.9rem,2.5vw,1.25rem);min-width:0}"
       ".mark{flex:none;display:block;width:clamp(3.75rem,10vw,5.5rem);height:auto}"
       ".eyebrow{margin:0 0 .2rem;font-size:.78rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--chip-ink)}"
       "h1{margin:0;font-size:clamp(1.85rem,5.2vw,3.3rem);line-height:1.05;letter-spacing:-.025em;font-weight:700;overflow-wrap:anywhere}"
       ;; the name keeps one line, never broken after its hyphen, from 480 px
       ;; up; below, it may break rather than widen the page
       "@media (min-width:30rem){h1{white-space:nowrap}}"
       ".sub{margin:.35rem 0 0;font-size:1.08rem;color:var(--muted)}"
       (str ".rule{width:min(11rem,40%);height:3px;border-radius:3px;background:" rule "}")
       ".lead{margin:0;max-width:42rem;font-size:1.15rem;color:var(--muted)}"
       ;; the switchers, at the end of the top row (and of the screen, when it
       ;; wraps), the language last: at the end edge, which is the content
       ;; box's, so its dropdown hangs from it under its own button and never
       ;; runs off the left of a narrow screen; when they wrap, the language's
       ;; line is the lower one, just above the dropdown.
       ".tools{flex:999 1 min-content;min-width:0;max-width:max-content;display:flex;flex-wrap:wrap;justify-content:flex-end;align-items:center;gap:.6rem;margin-inline-start:auto}"
       ;; the language switcher: a <details> dropdown of plain links, the current one marked
       (str ".langs summary{display:inline-flex;align-items:center;gap:.4rem;padding:calc(.42rem + 3px) .95rem;border:1px solid var(--control);"
            "border-radius:999px;background:var(--surface);box-shadow:var(--shadow);font-size:.86rem;font-weight:650;cursor:pointer;list-style:none}")
       ".langs{position:relative}"
       ".langs summary::-webkit-details-marker{display:none}"
       ".langs summary:hover{border-color:var(--focus)}"
       ".langs summary:focus-visible{outline:3px solid var(--focus);outline-offset:2px}"
       ".langs svg{flex:none;width:1rem;height:1rem}"
       ".chev{transition:transform .2s ease}"
       ".langs[open] .chev{transform:rotate(180deg)}"
       (str ".langs ul{position:absolute;z-index:2;top:calc(100% + .4rem);inset-inline-end:0;min-width:12rem;max-width:calc(100vw - 2rem);"
            "margin:0;padding:.35rem;list-style:none;background:var(--surface);border:1px solid var(--control);border-radius:1rem;box-shadow:var(--shadow)}")
       (str ".langs a{display:block;padding:.45rem .8rem;border-radius:.65rem;"
            "font-family:var(--text-font);font-size:.9rem;font-weight:600;color:var(--text);text-decoration:none}")
       ".langs a:hover{color:var(--link);text-decoration:underline}"
       ".langs a[aria-current=page]{background:var(--chip);color:var(--chip-ink)}"
       ;; the theme switcher: radios as a segmented control
       (str ".theme{display:inline-flex;gap:2px;min-width:0;max-width:100%;margin:0;padding:3px;border:1px solid var(--control);"
            "border-radius:999px;background:var(--surface);box-shadow:var(--shadow)}")
       ".vh,.theme legend{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}"
       ".theme input{position:absolute;width:1px;height:1px;margin:0;opacity:0}"
       (str ".theme label{display:inline-flex;align-items:center;gap:.4rem;padding:.42rem .85rem;border-radius:999px;"
            "font-size:.86rem;font-weight:650;color:var(--muted);cursor:pointer;transition:background-color .2s ease,color .2s ease}")
       ".theme label:hover{color:var(--text)}"
       ".theme input:checked+label{background:var(--chip);color:var(--chip-ink)}"
       ".theme input:focus-visible+label{outline:3px solid var(--focus);outline-offset:2px}"
       ".theme svg{flex:none;width:1rem;height:1rem}"
       ;; a narrow screen: the switchers on a row of their own, below the
       ;; brand, and the labels without their icons, closer together, so the
       ;; longest (pt-BR's, es-ES's) keep one line within the gutter
       "@media (max-width:30rem){.tools{flex-basis:100%}.theme{flex-wrap:wrap}.theme svg{display:none}.theme label{padding:.42rem .62rem}}"
       "@supports not selector(:has(*)){.theme{display:none}}"
       ;; the jump links
       ".jump ul{display:flex;flex-wrap:wrap;gap:.6rem;margin:0;padding:0;list-style:none}"
       (str ".jump a{display:inline-block;padding:.5rem 1.05rem;border-radius:999px;border:1px solid var(--control);"
            "background:var(--surface);color:var(--text);font-weight:600;font-size:.92rem;text-decoration:none;box-shadow:var(--shadow)}")
       ".jump a:hover{border-color:var(--focus);color:var(--link)}"
       ;; the sections
       "main{display:grid;gap:clamp(3.25rem,8vw,5rem);margin-top:clamp(3rem,7vw,4.5rem)}"
       "section{scroll-margin-top:1.5rem;min-width:0}"
       "section>p,section>ul,section>h3{max-width:44rem}"
       "h2{margin:0 0 1.1rem;font-size:clamp(1.45rem,3.2vw,1.8rem);letter-spacing:-.015em}"
       (str "h2::after{content:\"\";display:block;width:2.6rem;height:3px;margin-top:.65rem;border-radius:3px;background:" rule "}")
       "section>h3{margin:1.6rem 0 .4rem;font-size:1.1rem}"
       "section>p{margin:0 0 1rem}"
       "section>ul{margin:0 0 1rem;padding-left:1.3rem}"
       "section>ul li{margin:.3rem 0}"
       "section>ul li::marker{color:var(--chip-ink)}"
       "p code,li code{padding:.08rem .38rem;border-radius:.4rem;background:var(--chip);color:var(--chip-ink);overflow-wrap:anywhere}"
       ;; short code (a command, a variable) keeps one line; a long path may break
       ".nw{white-space:nowrap}"
       (str "pre{margin:0 0 1.25rem;padding:1.1rem 1.25rem;overflow-x:auto;background:var(--surface);border:1px solid var(--border);"
            "border-radius:1rem;box-shadow:var(--shadow);line-height:1.6}")
       "pre:focus-visible{outline:3px solid var(--focus);outline-offset:3px}"
       ;; the cards
       ".cards{display:grid;gap:1.25rem;margin:.4rem 0 1.4rem;grid-template-columns:repeat(auto-fit,minmax(min(100%,15.5rem),1fr))}"
       (str ".card{position:relative;overflow:hidden;display:flex;flex-direction:column;gap:.5rem;padding:1.6rem 1.45rem 1.4rem;"
            "background:var(--surface);border:1px solid var(--border);border-radius:1.2rem;box-shadow:var(--shadow)}")
       (str ".card::before{content:\"\";position:absolute;inset:0 0 auto 0;height:4px;background:" rule "}")
       (str ".chip{align-self:flex-start;padding:.26rem .66rem;border-radius:999px;background:var(--chip);color:var(--chip-ink);"
            "font-size:.72rem;font-weight:700;letter-spacing:.11em;text-transform:uppercase}")
       ".card h3{margin:.15rem 0 0;font-size:1.1rem;line-height:1.3}"
       ".card p{margin:0 0 .5rem;color:var(--muted)}"
       (str ".button{align-self:flex-start;margin-top:auto;display:inline-flex;align-items:center;gap:.45rem;padding:.5rem 1rem;"
            "border:1px solid var(--control);border-radius:999px;background:var(--chip);color:var(--chip-ink);font-weight:650;font-size:.95rem;text-decoration:none}")
       ".button:hover{background:var(--chip-ink);border-color:var(--chip-ink);color:var(--surface)}"
       ".button span{transition:transform .2s ease}"
       ".button:hover span{transform:translateX(3px)}"
       ;; the footer
       (str "footer{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.4rem 1.5rem;margin-top:clamp(3.5rem,9vw,5.5rem);"
            "padding-top:1.4rem;border-top:1px solid var(--border);font-size:.88rem;color:var(--muted)}")
       "footer p{margin:0}"
       "@media (prefers-reduced-motion:reduce){:root,.theme label,.chev,.button span{transition:none}.button:hover span{transform:none}}"
       ;; print: black on white, whatever the theme. The second rule is as
       ;; specific as the dark ones above (an id and a :checked inside
       ;; :has(), one of the three always true), and later, so it beats them;
       ;; the first covers a browser without :has().
       (let [ink (str "{color-scheme:light;--bg:#fff;--surface:#fff;--surface-2:#fff;--text:#000;--muted:#000;--heading:#000;--link:#000;"
                      "--chip:#fff;--chip-ink:#000;--border:#999;--control:#999;--shadow:none;transition:none}")]
         (str "@media print{:root" ink ":root:has(#theme-system:checked,#theme-light:checked,#theme-dark:checked)" ink
              "body{background:#fff;color:#000}.jump,.theme,.langs,.card::before,.button span{display:none}"
              "h2::after,.rule{background:#000}.card,pre{break-inside:avoid}}"))]))))

(defn csp
  "Nothing but the page's own style (by the SHA-256 of `style`) and data:
  images (the icon and the favicon)."
  [style]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String style StandardCharsets/UTF_8))]
    (str "default-src 'none'; style-src 'sha256-" (.encodeToString (Base64/getEncoder) digest) "'; "
         "img-src data:; base-uri 'none'; form-action 'none'")))

;; ---------------------------------------------------------------------------
;; The page

(def ^:private theme-icons
  "The switcher's icons (the hub's drawings), in the text's colour."
  {:system "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"6.25\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"/><path d=\"M8 1.75a6.25 6.25 0 0 1 0 12.5Z\" fill=\"currentColor\"/></svg>"
   :light  "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"3\" fill=\"currentColor\"/><path d=\"M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3.05 3.05l1.13 1.13M11.82 11.82l1.13 1.13M3.05 12.95l1.13-1.13M11.82 4.18l1.13-1.13\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\"/></svg>"
   :dark   "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M13.5 10.3A6 6 0 0 1 5.7 2.5a6 6 0 1 0 7.8 7.8Z\" fill=\"currentColor\"/></svg>"})

(defn- theme-switcher
  "Auto, Light or Dark, as radio buttons the stylesheet reads (no script)."
  [theme]
  [:fieldset.theme
   [:legend (:legend theme)]
   (for [k [:system :light :dark]
         :let [id (name k) [label title] (get theme k)]]
     (list [:input (cond-> {:type "radio" :name "theme" :id (str "theme-" id) :value id}
                     (= k :system) (assoc :checked true))]
           [:label {:for (str "theme-" id) :title title} (h/raw (theme-icons k)) label]))])

(def ^:private globe
  "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.3\"><circle cx=\"8\" cy=\"8\" r=\"6.25\"/><ellipse cx=\"8\" cy=\"8\" rx=\"2.6\" ry=\"6.25\"/><path d=\"M1.75 8h12.5M2.7 4.75h10.6M2.7 11.25h10.6\"/></g></svg>")

(def ^:private chevron
  "<svg class=\"chev\" viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><path d=\"m4 6 4 4 4-4\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")

(defn- language-switcher
  "The five versions as plain links, each named in its own language, in a
  <details> dropdown: the browser opens and closes it, so no script, and
  no redirect by the browser's language (DESIGN.md §7.5)."
  [pages tag]
  [:nav {:aria-label (get-in pages [tag :langs-label])}
   [:details.langs
    [:summary (h/raw globe) [:span.vh (str (get-in pages [tag :lang-label]) " ")]
     [:span (get-in pages [tag :lang-name])] (h/raw chevron)]
    [:ul (for [t tags]
           [:li [:a (cond-> {:href (relative-href tag t) :hreflang t :lang t}
                      (= t tag) (assoc :aria-current "page"))
                 (get-in pages [t :lang-name])]])]]])

(defn- alternates
  "Every version's address, for each tag it is offered for, and English as
  the default."
  []
  (concat (for [{:keys [tag also]} languages
                t (cond-> [tag] also (conj also))]
            [:link {:rel "alternate" :hreflang t :href (page-url tag)}])
          [[:link {:rel "alternate" :hreflang "x-default" :href (page-url "en")}]]))

(defn- page-body
  "A language's <body>, as hiccup, fully realised (its English-only links
  read *en-note* as they are made)."
  [pages tag svg]
  (let [icon (data-uri svg)
        page (get pages tag)
        {:keys [sections theme footer]} page]
    (binding [*en-note* (when (not= tag "en") (:en-note page))]
      (walk/postwalk
       identity
       [:body
        [:div.page
         [:header.hero
          [:div.top
           [:div.brand
            [:img.mark {:src icon :alt (:icon-alt page) :width "48" :height "48"}]
            [:div
             [:p.eyebrow (:eyebrow page)]
             [:h1 (:name page)]
             [:p.sub (:subtitle page)]]]
           [:div.tools (theme-switcher theme) (language-switcher pages tag)]]
          [:div.rule]
          [:p.lead (:lead page)]
          [:nav.jump {:aria-label (:jump-label page)}
           [:ul (for [{:keys [id title]} sections] [:li [:a {:href (str "#" id)} title]])]]]
         [:main
          (for [{:keys [id title blocks]} sections]
            [:section {:id id :aria-labelledby (str id "-title")}
             [:h2 {:id (str id "-title")} title]
             (map block blocks)])]
         [:footer
          [:p (link (:hub page) (:hub footer)) " · " (pieces (:licence footer))]
          [:p (:note footer)]]]]))))

(defn page-html
  "A language's page, as one string; `pages` is {tag words}, for the
  switcher."
  [pages tag svg]
  (let [style (css)
        icon  (data-uri svg)
        page  (get pages tag)]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang tag}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:meta {:http-equiv "Content-Security-Policy" :content (csp style)}]
        [:meta {:name "referrer" :content "no-referrer"}]
        [:meta {:name "color-scheme" :content "light dark"}]
        [:meta {:name "theme-color" :media "(prefers-color-scheme: light)" :content "#F4F6F9"}]
        [:meta {:name "theme-color" :media "(prefers-color-scheme: dark)" :content "#0D1016"}]
        [:title (:title page)]
        [:meta {:name "description" :content (:description page)}]
        [:link {:rel "canonical" :href (page-url tag)}]
        (alternates)
        [:link {:rel "icon" :type "image/svg+xml" :href icon}]
        [:style (h/raw style)]]
       (page-body pages tag svg)]))))

(defn glyph-lists
  "{subset id text}: every character each subset must hold, over the five
  pages, in code-point order; the Latin subsets and the zh-Hant page's
  with printable ASCII too. support/fonts.sh cuts each subset from these."
  ([pages svg] (glyph-lists (font-plan) pages svg))
  ([plan pages svg]
   (into (sorted-map)
         (for [[id cps] (apply merge-with into {} (for [t tags] (page-glyphs plan t (page-body pages t svg))))]
           [id (let [sb (StringBuilder.)]
                 (doseq [cp (sort (cond-> cps (always-ascii? id) (into always)))] (.appendCodePoint sb (int cp)))
                 (str sb))]))))

(defn build!
  "Writes `out`/clogem-press/: the five pages and nothing else (the folder
  is emptied first, so a page no longer built isn't left to be published).
  Returns each page's tag, path and size."
  [out]
  (let [pages (read-pages)
        svg   (icon-svg)]
    (when-let [problems (seq (concat (for [t tags, p (check-page (get pages t) t)] (str "support/i18n/" t ".edn: " p))
                                     (parity-problems pages)))]
      (fail! (str "The support page's words aren't right:\n  " (str/join "\n  " problems))))
    (some->> (icon-problem svg) (fail! "support/clogem-press.svg:"))
    (let [dir   (fs/file out folder)
          built (vec (for [t tags
                           :let [html  (page-html pages t svg)
                                 bytes (alength (.getBytes ^String html StandardCharsets/UTF_8))]]
                       (if (> bytes budget)
                         (fail! (str "The " t " page is " bytes " bytes, over its budget of " budget "."))
                         {:tag t :html html :bytes bytes :file (str (fs/file dir (page-path t)))})))]
      (fs/delete-tree dir)
      (doseq [{:keys [file html]} built]
        (fs/create-dirs (fs/parent file))
        (spit file html))
      ;; the character lists, beside clogem-press/ so they're never published
      (let [gdir (fs/file out "glyphs")]
        (fs/delete-tree gdir)
        (fs/create-dirs gdir)
        (doseq [[id text] (glyph-lists pages svg)]
          (spit (fs/file gdir (str id ".txt")) text)))
      (mapv #(dissoc % :html) built))))

(defn -main [& args]
  (let [out (loop [[a & more] args, out "dist-support"]
              (cond (nil? a)        out
                    (= a "--out")   (if (seq more) (recur (rest more) (first more)) (fail! "--out needs a folder"))
                    :else           (fail! "bb support-page [--out DIR], not" a)))
        built (build! out)]
    (doseq [{:keys [file bytes]} built]
      (println (str "Wrote " file " (" bytes " bytes, budget " budget ")")))
    (println (str "Wrote the fonts' character lists to " (fs/file out "glyphs") "/ (support/fonts.sh cuts the subsets from them)"))
    (println (str (count built) " pages, " (reduce + (map :bytes built)) " bytes in all"))))
