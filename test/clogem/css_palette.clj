;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.css-palette
  "What theme.css computes on `<html>`, for the contrast tests (P4-B2a item
  1). Not a test namespace: theme_modes_test, highlight_test and
  page_style_test share it.

  B1's tests read the FIRST rule whose selector was exactly `sel`, so a
  later rule, or a compound one (`html.theme-mode-dark`,
  `.theme-style-line`), was never checked, and `#ffffffe6` counted as
  opaque. This parses CSS text into rules (inside `@media` too), keeps the
  ones that match `<html>` in a given state, and merges their declarations
  in cascade order — `!important`, then specificity, then source order —
  with `var()` resolved, as a browser would. Only the selectors theme.css
  puts on `<html>` are understood (one compound: `html`, `:root`, classes,
  `[attr]`, `[attr=\"v\"]`); anything else matches nothing, which is right
  for `<html>`, since every other rule has a combinator or names another
  element.

  Colours: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()` and a
  few keywords, as [r g b a] (0–255, alpha 0–1). A translucent colour is
  composited over what lies under it before its contrast is taken."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Parsing

(defn- strip-comments [css] (str/replace css #"(?s)/\*.*?\*/" ""))

(defn- scan-block
  "Index just past the `}` closing the block whose `{` is at `open`,
  skipping strings and nested blocks."
  [^String s open]
  (loop [i (inc open) depth 1 quote nil]
    (when (>= i (count s)) (throw (ex-info "unbalanced { in CSS" {:at open})))
    (let [c (.charAt s i)]
      (cond
        (and quote (= c \\)) (recur (+ i 2) depth quote)
        quote          (recur (inc i) depth (if (= c quote) nil quote))
        (#{\" \'} c)   (recur (inc i) depth c)
        (= c \{)       (recur (inc i) (inc depth) nil)
        (= c \})       (if (= 1 depth) (inc i) (recur (inc i) (dec depth) nil))
        :else          (recur (inc i) depth nil)))))

(defn- split-top
  "Split `s` on `sep` outside parentheses, brackets and strings."
  [s sep]
  (loop [cs (seq s) depth 0 quote nil cur (StringBuilder.) out []]
    (if-not cs
      (conj out (str cur))
      (let [c (first cs)]
        (cond
          quote (recur (next cs) depth (if (= c quote) nil quote) (.append cur c) out)
          (#{\" \'} c) (recur (next cs) depth c (.append cur c) out)
          (#{\( \[} c) (recur (next cs) (inc depth) nil (.append cur c) out)
          (#{\) \]} c) (recur (next cs) (dec depth) nil (.append cur c) out)
          (and (= c sep) (zero? depth)) (recur (next cs) depth nil (StringBuilder.) (conj out (str cur)))
          :else (recur (next cs) depth nil (.append cur c) out))))))

(defn- declarations [body]
  (vec (for [d (split-top body \;)
             :let [i (str/index-of d ":")]
             :when i
             :let [prop (str/trim (subs d 0 i))
                   v    (str/trim (subs d (inc i)))
                   imp? (boolean (re-find #"!\s*important\s*$" v))
                   v    (str/trim (str/replace v #"!\s*important\s*$" ""))]
             :when (seq prop)]
         [prop v imp?])))

(defn parse
  "CSS text → [{:media [query …] :selectors [sel …] :decls [[prop value
  important?] …]}] in source order. `:media` holds every enclosing
  `@media` prelude; other at-rules (`@font-face`, `@keyframes`, …) are
  skipped."
  ([css] (parse (strip-comments css) []))
  ([^String css media]
   (loop [i 0 out []]
     (let [open (str/index-of css "{" i)]
       (if-not open
         out
         (let [prelude (str/trim (subs css i open))
               end     (scan-block css open)
               body    (subs css (inc open) (dec end))]
           (cond
             (str/starts-with? prelude "@media")
             (recur end (into out (parse body (conj media (str/trim (subs prelude 6))))))

             (str/starts-with? prelude "@")
             (recur end out)

             :else
             (recur end (conj out {:media media
                                   :selectors (mapv str/trim (split-top prelude \,))
                                   :decls (declarations body)})))))))))

(defn rule
  "{property value} declared by the rules whose selector list is exactly
  `sels` (a string, `\", \"`-joined) inside exactly the `@media` preludes
  `media` (default: none), merged in source order — for checking what one
  block itself declares, not what applies."
  ([css sels] (rule css sels []))
  ([css sels media]
   (let [hits (filter #(and (= sels (str/join ", " (:selectors %))) (= media (:media %)))
                      (if (string? css) (parse css) css))]
     (assert (seq hits) (str "no rule " sels " in " (pr-str media)))
     (into {} (for [r hits [p v] (:decls r)] [p v])))))

;; ---------------------------------------------------------------------------
;; Matching <html>

(defn- compound
  "A selector as one compound's parts, [[:type t] [:class c] [:attr k v]
  [:root]], or nil when it is anything else (a combinator, another
  pseudo-class, …)."
  [sel]
  (loop [s sel parts []]
    (if (str/blank? s)
      (when (seq parts) parts)
      (if-let [[m t] (or (re-find #"^(html|\*)" s) nil)]
        (if (seq parts) nil (recur (subs s (count m)) (conj parts [:type t])))
        (if-let [[m c] (re-find #"^\.([A-Za-z0-9_-]+)" s)]
          (recur (subs s (count m)) (conj parts [:class c]))
          (if-let [[m k _ v1 v2 v3] (re-find #"^\[\s*([A-Za-z0-9_-]+)\s*(=\s*(?:\"([^\"]*)\"|'([^']*)'|([A-Za-z0-9_-]+)))?\s*\]" s)]
            (recur (subs s (count m)) (conj parts [:attr k (or v1 v2 v3)]))
            (if (re-find #"^:root(?![A-Za-z0-9_-])" s)
              (recur (subs s 5) (conj parts [:root]))
              nil)))))))

(defn- matches?
  "Does `sel` match `el` ({:classes #{…} :attrs {name value}}, an <html>)?"
  [el sel]
  (when-let [parts (compound sel)]
    (every? (fn [[k a b]]
              (case k
                :type  true
                :root  true
                :class (contains? (:classes el) a)
                :attr  (let [v (get (:attrs el) a)]
                         (and (some? v) (or (nil? b) (= b v))))))
            parts)))

(defn specificity [sel]
  (let [parts (compound sel)]
    [0
     (count (filter #(#{:class :attr :root} (first %)) parts))
     (count (filter #(and (= :type (first %)) (= "html" (second %))) parts))]))

(defn- length-px [s]
  (let [[_ n u] (re-matches #"([0-9.]+)(px|rem|em)" (str/trim s))]
    (when-not n (throw (ex-info (str "unknown length in a media query: " s) {})))
    (* (parse-double n) (if (= "px" u) 1 16))))

(defn- media-true?
  "One media query list against `env` {:print? :dark? :width :scripting?}.
  A feature this does not know throws, so a new query cannot be silently
  false. A query may start with `not`, which negates it whole (P4-B2b:
  `not all and (max-width: 50rem)`); `(scripting: enabled)` follows
  `:scripting?`, default true, and `initial-only` is false, as in
  Chromium."
  [env q]
  (let [term-true?
        (fn [term]
          (let [term (str/trim term)]
            (cond
              (= term "print")  (:print? env)
              (= term "screen") (not (:print? env))
              (= term "all")    true
              :else
              (let [[_ f v] (or (re-matches #"\(\s*([a-z-]+)\s*:\s*([^)]+?)\s*\)" term)
                                (throw (ex-info (str "unknown media query: " term) {})))]
                (case f
                  "prefers-color-scheme"   (= v (if (:dark? env) "dark" "light"))
                  "prefers-reduced-motion" (= v "no-preference")
                  "max-width"              (<= (:width env 1280) (length-px v))
                  "min-width"              (>= (:width env 1280) (length-px v))
                  "scripting"              (case v
                                             "enabled"      (:scripting? env true)
                                             "none"         (not (:scripting? env true))
                                             "initial-only" false
                                             (throw (ex-info (str "unknown scripting value: " v) {})))
                  (throw (ex-info (str "unknown media feature: " f) {})))))))]
    (boolean
     (some (fn [one]
             (let [[_ neg body] (re-matches #"(?s)\s*(not\s+)?(.*?)\s*" one)]
               (not= (boolean neg)
                     (every? term-true? (str/split body #"\s+and\s+")))))
           (split-top q \,)))))

;; ---------------------------------------------------------------------------
;; The cascade

(defn- resolve-vars
  "Substitute var() in `v` from `props`; nil when a reference is missing
  without a fallback, or cyclic."
  [props v seen]
  (if-let [[m n fb] (re-find #"var\(\s*(--[A-Za-z0-9_-]+)\s*(?:,\s*([^()]*?))?\s*\)" v)]
    (let [sub (if (and (contains? props n) (not (contains? seen n)))
                (resolve-vars props (get props n) (conj seen n))
                (some-> fb str/trim))]
      (when sub (recur props (str/replace-first v m sub) seen)))
    v))

(defn computed
  "{property value} on an <html> carrying `el`'s classes and attributes,
  under `env`, every var() resolved (a property whose value cannot be
  resolved is left out, as a browser makes it invalid)."
  [rules el env]
  (let [decls (for [[ri {:keys [media selectors decls]}] (map-indexed vector rules)
                    :when (every? #(media-true? env %) media)
                    :let [hits (filter #(matches? el %) selectors)]
                    :when (seq hits)
                    :let [spec (last (sort (map specificity hits)))]
                    [di [p v imp?]] (map-indexed vector decls)]
                [[(if imp? 1 0) spec ri di] p v])
        raw   (reduce (fn [m [_ p v]] (assoc m p v)) {} (sort-by first (fn [a b] (compare a b)) decls))]
    (into {} (keep (fn [[p v]] (when-let [r (resolve-vars raw v #{p})] [p r]))) raw)))

(def modes
  "The states the palette is checked in: every mode class, auto inside its
  dark media block, and print (its `html[class]` block, over dark — the
  mode whose values it must replace most)."
  {:light {:mode "light" :env {}}
   :read  {:mode "read"  :env {}}
   :dark  {:mode "dark"  :env {}}
   :auto  {:mode "auto"  :env {:dark? true}}
   :print {:mode "dark"  :env {:print? true}}})

(def styles [:card :line])

(defn html-state
  "The <html> of a page in `mode` and `style`, as the server renders it
  (no data-mode: no JS)."
  [mode style]
  (let [{m :mode} (get modes mode)
        cls #{(str "theme-mode-" m) (str "theme-style-" (name style))}]
    {:classes cls :attrs {"class" (str/join " " (sort cls)) "data-default-mode" "auto"}}))

(defn palette
  "{property value} that `css` (text) computes on <html> in `mode` (a key
  of `modes`) and `style` (:card or :line)."
  ([css mode] (palette css mode :card))
  ([css mode style]
   (computed (if (string? css) (parse css) css) (html-state mode style) (:env (get modes mode)))))

;; ---------------------------------------------------------------------------
;; Colours

(def ^:private named {"white" [255 255 255 1.0] "black" [0 0 0 1.0] "transparent" [0 0 0 0.0]})

(defn rgba
  "A colour string → [r g b a], or nil when it is not one this reads."
  [s]
  (let [s (str/lower-case (str/trim (str s)))]
    (or (named s)
        (when-let [[_ h] (re-matches #"#([0-9a-f]{3,8})" s)]
          (let [h (case (count h)
                    (3 4) (apply str (mapcat #(repeat 2 %) h))
                    (6 8) h
                    nil)]
            (when h
              (let [byte #(Integer/parseInt (subs h % (+ % 2)) 16)]
                [(byte 0) (byte 2) (byte 4) (if (= 8 (count h)) (/ (byte 6) 255.0) 1.0)]))))
        (when-let [[_ args] (re-matches #"rgba?\((.*)\)" s)]
          (let [[rgb a] (if (str/includes? args "/") (str/split args #"/") [args nil])
                xs (remove str/blank? (str/split rgb #"[\s,]+"))
                [r g b a'] (map str/trim xs)
                a (or a a')
                num (fn [x scale] (if (str/ends-with? x "%")
                                    (* scale (/ (parse-double (subs x 0 (dec (count x)))) 100.0))
                                    (parse-double x)))]
            (when (and r g b)
              [(num r 255) (num g 255) (num b 255) (if a (num (str/trim a) 1) 1.0)]))))))

(defn opaque? [c] (>= (nth (if (string? c) (rgba c) c) 3) 0.999))

(defn over
  "`fg` composited over `bg` (both colours; `bg` opaque) → an opaque [r g b 1.0]."
  [fg bg]
  (let [[r g b a] (if (string? fg) (rgba fg) fg)
        [R G B A] (if (string? bg) (rgba bg) bg)]
    (when-not (and r R) (throw (ex-info (str "not a colour: " (pr-str [fg bg])) {})))
    (when (< A 0.999) (throw (ex-info (str "the background is translucent: " (pr-str bg)) {})))
    [(+ (* a r) (* (- 1 a) R)) (+ (* a g) (* (- 1 a) G)) (+ (* a b) (* (- 1 a) B)) 1.0]))

(defn luminance [c]
  (let [[r g b] (if (string? c) (rgba c) c)
        ch #(let [c (/ % 255.0)]
              (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4)))]
    (+ (* 0.2126 (ch r)) (* 0.7152 (ch g)) (* 0.0722 (ch b)))))

(defn contrast
  "The WCAG contrast of `fg` on `bg`; a translucent `fg` is composited over
  `bg` first, and `bg` must be opaque (composite it with `over`)."
  [fg bg]
  (let [bg (if (string? bg) (rgba bg) bg)
        fg (over fg bg)
        [x y] (sort > [(luminance fg) (luminance bg)])]
    (/ (+ x 0.05) (+ y 0.05))))

(defn hex
  "An opaque colour as #rrggbb (for messages)."
  [c]
  (let [[r g b] (if (string? c) (rgba c) c)]
    (apply format "#%02x%02x%02x" (map #(Math/round (double %)) [r g b]))))

;; ---------------------------------------------------------------------------
;; The palette checks

(def white [255 255 255 1.0])

(defn surfaces
  "[[name opaque-colour] …] — every background text sits on, each
  composited over what lies under it: the page (over the canvas), the
  card and the sidebar over the page, a custom block over the card, and
  the navbar's translucent --blurBg over the page and over a card
  scrolling beneath it."
  [p]
  (let [body (over (p "--bodyBg") white)
        main (over (p "--mainBg") body)]
    [["--bodyBg" body]
     ["--mainBg" main]
     ["--sidebarBg" (over (p "--sidebarBg") body)]
     ["--customBlockBg" (over (p "--customBlockBg") main)]
     ["--blurBg over --bodyBg" (over (p "--blurBg") body)]
     ["--blurBg over --mainBg" (over (p "--blurBg") main)]]))

(def foregrounds
  "Text, its hover/link-lighten colour, muted text and the accent (links)."
  ["--textColor" "--textLightenColor" "--textColorSubtle" "--accent"])

(defn minimums
  "{fg [ratio surface]} — each foreground's lowest contrast over every
  surface of palette `p`, plus :code for --codeColor on --codeBg (over the
  card)."
  [p]
  (let [ss (surfaces p)
        main (second (first (filter #(= "--mainBg" (first %)) ss)))]
    (assoc (into {} (for [fg foregrounds]
                      [fg (first (sort-by first (for [[sn s] ss] [(contrast (p fg) s) sn])))]))
           :code [(contrast (p "--codeColor") (over (p "--codeBg") main)) "--codeBg"])))

(defn palette-minimums
  "{[mode style] {fg [ratio surface]}} over every mode and style of `css`."
  [css]
  (let [rules (parse css)]
    (into (sorted-map)
          (for [m (keys modes) s styles]
            [[m s] (minimums (palette rules m s))]))))

(defn failures
  "Every foreground/surface pair of `css` below `floor` (4.5), as strings;
  empty when the palette passes in every mode and style."
  ([css] (failures css 4.5))
  ([css floor]
   (vec (for [[[m s] fgs] (palette-minimums css)
              [fg [ratio surface]] fgs
              :when (< ratio floor)]
          (format "%s/%s: %s on %s is %.2f:1" (name m) (name s) fg surface ratio)))))
