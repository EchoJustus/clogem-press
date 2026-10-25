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
  https://echojustus.github.io/clogem-support/clogem-press/ (DESIGN.md §7.5):

    bb support-page                 writes dist-support/clogem-press/index.html
    bb support-page --out DIR       writes DIR/clogem-press/index.html

  The words are support/page.edn's, the icon support/clogem-press.svg; this
  file adds markup only. The page is built as the hub's own pages are
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
            [hiccup2.core :as h])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private here
  "This file's folder: page.edn and the icon sit beside it, wherever bb runs."
  (str (fs/parent (fs/absolutize *file*))))

(def folder "The page's folder on gh-pages." "clogem-press")

(def budget
  "The built page's byte budget (at most 150 KB, the task's limit); the
  build and `bb test` both hold it."
  65536)

(defn- fail! [& msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit 1))

(defn read-page
  "page.edn, read."
  ([] (read-page (str (fs/path here "page.edn"))))
  ([file] (edn/read-string (slurp file))))

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
  "page.edn's problems, in words."
  [{:keys [url hub sections theme footer] :as page}]
  (concat
   (for [k [:url :hub :title :description :eyebrow :name :subtitle :icon-alt :lead :jump-label]
         :when (not (text? (get page k)))]
     (str k " must be text"))
   (when-not (and (string? url) (str/ends-with? url (str "/" folder "/")) (str/starts-with? url "https://"))
     [(str ":url must be the https address of " folder "/")])
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
           sections)))

(defn- piece [x]
  (if (string? x)
    x
    (let [[tag & args] x]
      (case tag
        :a      [:a {:href (first args)} (second args)]
        :code   [:code (first args)]
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
  "\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif")

(def ^:private text-font
  "\"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial,sans-serif")

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
       "*,*::before{box-sizing:border-box}"
       "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}"
       (str "body{margin:0;min-height:100vh;color:var(--text);background:"
            "radial-gradient(1100px 560px at 8% -12%,var(--glow-1),transparent 62%),"
            "radial-gradient(900px 520px at 100% -4%,var(--glow-2),transparent 58%),var(--bg);"
            "font:400 1rem/1.65 " text-font ";-webkit-font-smoothing:antialiased;overflow-wrap:break-word}")
       ".page{max-width:62rem;margin:0 auto;padding:clamp(2.5rem,7vw,5.5rem) 1rem 2.5rem}"
       "a{color:var(--link);text-underline-offset:.18em}"
       "a:focus-visible{outline:3px solid var(--focus);outline-offset:3px;border-radius:.45rem}"
       (str "h1,h2,h3{font-family:" heading-font ";color:var(--heading)}")
       "code,pre{font-family:ui-monospace,\"Cascadia Code\",\"SF Mono\",Menlo,Consolas,monospace;font-size:.9em}"
       ;; the header
       ".hero{display:grid;gap:1.4rem}"
       ".top{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:1rem 1.5rem}"
       ".brand{display:flex;align-items:center;gap:clamp(.9rem,2.5vw,1.25rem);min-width:0}"
       ".mark{flex:none;display:block;width:clamp(3.75rem,10vw,5.5rem);height:auto}"
       ".eyebrow{margin:0 0 .2rem;font-size:.78rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--chip-ink)}"
       "h1{margin:0;font-size:clamp(2.1rem,5.2vw,3.3rem);line-height:1.05;letter-spacing:-.025em;font-weight:700;overflow-wrap:anywhere}"
       ".sub{margin:.35rem 0 0;font-size:1.08rem;color:var(--muted)}"
       (str ".rule{width:min(11rem,40%);height:3px;border-radius:3px;background:" rule "}")
       ".lead{margin:0;max-width:42rem;font-size:1.15rem;color:var(--muted)}"
       ;; the theme switcher: radios as a segmented control
       (str ".theme{display:inline-flex;gap:2px;min-width:0;margin:0;padding:3px;border:1px solid var(--control);"
            "border-radius:999px;background:var(--surface);box-shadow:var(--shadow)}")
       ".theme legend{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}"
       ".theme input{position:absolute;width:1px;height:1px;margin:0;opacity:0}"
       (str ".theme label{display:inline-flex;align-items:center;gap:.4rem;padding:.42rem .85rem;border-radius:999px;"
            "font-size:.86rem;font-weight:650;color:var(--muted);cursor:pointer;transition:background-color .2s ease,color .2s ease}")
       ".theme label:hover{color:var(--text)}"
       ".theme input:checked+label{background:var(--chip);color:var(--chip-ink)}"
       ".theme input:focus-visible+label{outline:3px solid var(--focus);outline-offset:2px}"
       ".theme svg{flex:none;width:1rem;height:1rem}"
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
       "@media (prefers-reduced-motion:reduce){:root,.theme label,.button span{transition:none}.button:hover span{transform:none}}"
       (str "@media print{:root{--bg:#fff;--surface:#fff;--surface-2:#fff;--text:#000;--muted:#000;--heading:#000;--link:#000;"
            "--chip:#fff;--chip-ink:#000;--border:#999;--control:#999;--shadow:none;transition:none}"
            "body{background:#fff;color:#000}.jump,.theme,.card::before,.button span{display:none}"
            "h2::after,.rule{background:#000}.card,pre{break-inside:avoid}}")]))))

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

(defn page-html
  "The page, as one string."
  [page svg]
  (let [style (css)
        icon  (data-uri svg)
        {:keys [sections theme footer]} page]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang "en"}
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
        [:link {:rel "canonical" :href (:url page)}]
        [:link {:rel "icon" :type "image/svg+xml" :href icon}]
        [:style (h/raw style)]]
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
           (theme-switcher theme)]
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
          [:p [:a {:href (:hub page)} (:hub footer)] " · " (pieces (:licence footer))]
          [:p (:note footer)]]]]]))))

(defn build!
  "Writes `out`/clogem-press/index.html; returns its path and size."
  [out]
  (let [page (read-page)
        svg  (icon-svg)]
    (when-let [problems (seq (check-page page))]
      (fail! (str "support/page.edn isn't right:\n  " (str/join "\n  " problems))))
    (some->> (icon-problem svg) (fail! "support/clogem-press.svg:"))
    (let [html  (page-html page svg)
          bytes (alength (.getBytes ^String html StandardCharsets/UTF_8))
          file  (fs/file out folder "index.html")]
      (when (> bytes budget)
        (fail! (str "The page is " bytes " bytes, over its budget of " budget ".")))
      (fs/create-dirs (fs/parent file))
      (spit file html)
      {:file (str file) :bytes bytes})))

(defn -main [& args]
  (let [out (loop [[a & more] args, out "dist-support"]
              (cond (nil? a)        out
                    (= a "--out")   (if (seq more) (recur (rest more) (first more)) (fail! "--out needs a folder"))
                    :else           (fail! "bb support-page [--out DIR], not" a)))
        {:keys [file bytes]} (build! out)]
    (println (str "Wrote " file " (" bytes " bytes, budget " budget ")"))))
