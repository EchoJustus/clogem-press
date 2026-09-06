;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.containers
  "vdoing's markdown containers (DESIGN.md §1.2, §5.2 step 4, D-P2-10):

      ::: tip Optional title          ::: cardList 2
      body, *markdown*                ```yaml
      :::                             - name: …
                                      ```
                                      :::

  Eight named containers — note / tip / warning / danger / details / theorem /
  right / center — plus the two YAML-driven card grids, cardList and
  cardImgList.

  ## Approach A: a source-line pre-pass

  The bundled nextjournal.markdown (bb 1.13) has NO block-level parser hook:
  its text tokenizers run per inline text node and never see a `:::` line
  (verified — `:::` parses as plain paragraph text). So containers are
  rewritten into HTML BEFORE `md/parse`, one line at a time, tracking
  fenced-code state so `:::` inside ``` or ~~~ is untouched, with a stack for
  nesting (a closer needs at least as many colons as its opener, so `::::`
  outer / `:::` inner works).

  The emitted HTML relies on two verified CommonMark facts:

    - an HTML block (type 6: `<div`, `</div>`, `<details`, …) ENDS AT THE
      FIRST BLANK LINE, and what follows is markdown again — which is why
      every wrapper is emitted WITH blank lines around it, and why a heading
      inside a container is still a real :heading node that reaches the
      AST's :toc;
    - a line indented by four or more spaces is an indented code block, so
      fences are recognized at 0–3 spaces of indentation only, and every
      emitted line keeps the fence's own indent — that is what keeps a
      container inside a list item inside the item.

  Class names mirror vdoing's (`custom-block <name>`, `custom-block-title`,
  `card-list`, `card-img-list`, `row-N`) so the Phase 4 stylesheet is a
  drop-in. Default titles come from the i18n strings in the PAGE's language
  (§6.4 rule 2): `::: tip` renders 提示 on a zh-Hans page."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Titles

(def titled-containers
  {"note" :container/note "tip" :container/tip "warning" :container/warning
   "danger" :container/danger "details" :container/details "theorem" :container/theorem})

(def known-containers
  (into #{"right" "center" "cardlist" "cardimglist"} (keys titled-containers)))

(defn- default-title
  "The container's default title in the page's language, via the §6.5 chain;
  a context without strings (tests, ad-hoc rendering) falls back to the
  theme's English strings so `::: tip` still reads TIP."
  [ctx k]
  (if (:strings ctx)
    (i18n/tr ctx k)
    (or (get (i18n/theme-strings :en) k) (name k))))

;; ---------------------------------------------------------------------------
;; Line grammar

(def ^:private code-open-re  #"^(\s{0,3})(`{3,}|~{3,})(.*)$")
(def ^:private open-re       #"^(\s{0,3})(:{3,})[ \t]*([A-Za-z][A-Za-z0-9_-]*)(?:[ \t]+(.*?))?[ \t]*$")
(def ^:private close-re      #"^(\s{0,3})(:{3,})[ \t]*$")
(def ^:private yaml-fence-re #"^\s*(`{3,}|~{3,})\s*(?:yaml|yml)?\s*$")

(defn- code-close?
  [line {:keys [char len]}]
  (when-let [[_ _ fence] (re-matches #"^(\s{0,3})(`{3,}|~{3,})\s*$" line)]
    (and (= char (first fence)) (>= (count fence) len))))

;; ---------------------------------------------------------------------------
;; Wrappers

(defn- opener-html
  "[lines…] that open a container — NO trailing blank; the caller adds it."
  [nm title]
  (case nm
    "details" [(str "<details class=\"custom-block details\"><summary>" title "</summary>")]
    "theorem" [(str "<div class=\"custom-block theorem\"><p class=\"title\">" title "</p>")]
    "right"   ["<div style=\"text-align:right\">"]
    "center"  ["<div style=\"text-align:center\">"]
    [(str "<div class=\"custom-block " nm "\">")
     (str "<p class=\"custom-block-title\">" title "</p>")]))

(defn- closer-html [nm]
  (if (= nm "details") "</details>" "</div>"))

;; ---------------------------------------------------------------------------
;; Card lists

(defn- css-value
  "A colour or length safe to put in a style attribute — the item's author
  is the site's author, but a stray `;` or `url(` has no business here."
  [v]
  (let [s (str v)]
    (when (re-matches #"[#A-Za-z0-9(),.%\s-]{1,40}" s) s)))

(defn- style-of
  [pairs]
  (u/blank->nil (str/join ";" (keep (fn [[k v]] (when-let [v (css-value v)] (str k ":" v))) pairs))))

(defn- text [v] (some-> v str u/blank->nil))

(defn- card-list
  [items config n rw]
  (let [target (or (text (:target config)) "_blank")]
    (into [:div {:class (str "card-list row-" n)}]
          (for [it items
                :let [link (text (:link it))
                      tag  (if link :a :div)
                      attrs (cond-> {:class "card-item"}
                              link (assoc :href (rw link) :target target)
                              (style-of [["background-color" (:bgColor it)] ["color" (:textColor it)]])
                              (assoc :style (style-of [["background-color" (:bgColor it)] ["color" (:textColor it)]])))]]
            [tag attrs
             (cond
               (text (:avatar it)) [:img.avatar {:src (rw (text (:avatar it))) :alt ""}]
               (text (:img it))    [:img.no-zoom {:src (rw (text (:img it))) :alt ""}])
             (when-let [nm (text (:name it))] [:div.name nm])
             (when-let [d (text (:desc it))] [:div.desc d])
             (when-let [a (text (:author it))] [:div.author a])]))))

(defn- card-img-list
  [items config n rw]
  (let [target (or (text (:target config)) "_blank")
        height (or (css-value (:imgHeight config)) "auto")
        fit    (or (css-value (:objectFit config)) "cover")
        clamp  (or (some-> (:lineClamp config) str parse-long) 1)]
    (into [:div {:class (str "card-img-list row-" n)}]
          (for [it items
                :let [link (text (:link it))
                      nm   (text (:name it))
                      body [(when-let [img (text (:img it))]
                              [:div.box-img {:style (str "height:" height)}
                               [:img {:src (rw img) :alt (or nm "") :style (str "object-fit:" fit)}]])
                            [:div.box-info
                             (when nm [:div.title {:style (str "-webkit-line-clamp:" clamp)} nm])
                             (when-let [d (text (:desc it))] [:div.desc d])
                             (when-let [a (text (:author it))]
                               [:div.author
                                (when-let [av (text (:avatar it))] [:img.avatar {:src (rw av) :alt ""}])
                                [:span a]])]]]]
            [:div.card-item
             (if link
               (into [:a {:href (rw link) :target target}] body)
               (into [:div] body))]))))

(defn- yaml-source
  "The YAML inside an optional ```yaml fence, dedented so a card list inside
  a list item parses."
  [lines]
  (let [lines (vec lines)
        open  (some (fn [[i l]] (when (re-matches yaml-fence-re l) i)) (map-indexed vector lines))
        close (when open
                (some (fn [i] (when (re-matches yaml-fence-re (nth lines i)) i))
                      (range (dec (count lines)) open -1)))
        body  (if (and open close (> close open)) (subvec lines (inc open) close) lines)
        indent (->> body (remove str/blank?) (map #(count (re-find #"^\s*" %))) (reduce min 0))]
    (str/join "\n" (map #(if (str/blank? %) "" (subs % (min indent (count %)))) body))))

(defn- parse-cards
  "[items config] from the YAML — a sequence of items, or a map with :config
  and :data. Throws on anything else, like a YAML syntax error does."
  [src]
  (let [y (yaml/parse-string src :keywords true)]
    (cond
      (sequential? y) [(vec y) {}]
      (and (map? y) (sequential? (:data y))) [(vec (:data y)) (or (:config y) {})]
      :else (throw (ex-info "expected a list of items, or a map with `config` and `data`" {})))))

(defn- render-cards
  [nm arg body-lines {:keys [from-path rewrite-href] :as _ctx}]
  (let [rw (or rewrite-href identity)
        n  (let [x (some-> arg str/trim u/blank->nil parse-long)]
             (if (and x (<= 1 x 4))
               x
               (do (when x (diag/warn! from-path (str nm ": row count " x " is not 1–4; using 3")))
                   3)))]
    (try
      (let [[items config] (parse-cards (yaml-source body-lines))]
        (str (h/html (if (= nm "cardimglist")
                       (card-img-list items config n rw)
                       (card-list items config n rw)))))
      (catch Exception e
        (let [msg (or (ex-message e) (str e))]
          (diag/warn! from-path (str nm ": could not parse the YAML card list — " msg))
          (str (h/html [:div.custom-block.danger
                        [:p.custom-block-title (default-title _ctx :container/danger)]
                        [:p (str nm ": invalid YAML — " (str/replace msg #"\s+" " "))]])))))))

;; ---------------------------------------------------------------------------
;; The pre-pass

(defn expand
  "Rewrite container fences in `source` into HTML blocks. `ctx` supplies
  `:cfg :lang :strings :dev?` for default titles, `:from-path` for
  diagnostics and `:rewrite-href` (fn href → href) for card-list links.
  Lines outside containers are returned unchanged."
  [source ctx]
  (let [eol   (if (str/includes? (str source) "\r\n") "\r\n" "\n")
        lines (str/split (str source) #"\r?\n" -1)
        n     (count lines)
        path  (:from-path ctx)]
    (loop [i 0 code nil stack [] out (transient [])]
      (if (>= i n)
        (let [out (if (seq stack)
                    (do (diag/warn! path (str "unclosed container `" (:name (peek stack))
                                              "` at end of file; closing it."))
                        (reduce (fn [o c] (-> o (conj! "") (conj! (str (:indent c) (closer-html (:name c)))) (conj! "")))
                                out (reverse stack)))
                    out)]
          (str/join eol (persistent! out)))
        (let [line (nth lines i)]
          (cond
            ;; inside a fenced code block: nothing is rewritten
            code
            (recur (inc i) (if (code-close? line code) nil code) stack (conj! out line))

            ;; a code fence opens
            (re-matches code-open-re line)
            (let [[_ _ fence] (re-matches code-open-re line)]
              (recur (inc i) {:char (first fence) :len (count fence)} stack (conj! out line)))

            ;; a container closes
            (and (seq stack)
                 (when-let [[_ _ markers] (re-matches close-re line)]
                   (>= (count markers) (:markers (peek stack)))))
            (let [c (peek stack)]
              (recur (inc i) nil (pop stack)
                     (-> out (conj! "") (conj! (str (:indent c) (closer-html (:name c)))) (conj! ""))))

            ;; a container opens
            (re-matches open-re line)
            (let [[_ indent markers nm arg] (re-matches open-re line)
                  lc (u/lower nm)]
              (cond
                (not (known-containers lc))
                (do (diag/warn! path (str "unknown container `" nm "`; leaving it as text."))
                    (recur (inc i) nil stack (conj! out line)))

                (#{"cardlist" "cardimglist"} lc)
                ;; consume the body up to the matching closer
                (let [want (count markers)
                      j    (loop [j (inc i)]
                             (cond (>= j n) nil
                                   (when-let [[_ _ markers] (re-matches close-re (nth lines j))]
                                     (>= (count markers) want)) j
                                   :else (recur (inc j))))
                      body (subvec lines (inc i) (or j n))
                      html (render-cards lc arg body ctx)]
                  (when-not j
                    (diag/warn! path (str "unclosed container `" nm "` at end of file; closing it.")))
                  (recur (if j (inc j) n) nil stack
                         (-> out (conj! "")
                             (conj! (str/join eol (map #(str indent %) (str/split-lines html))))
                             (conj! ""))))

                :else
                (let [title (if-let [t (u/blank->nil (str arg))]
                              (u/html-escape t)
                              (when-let [k (titled-containers lc)] (default-title ctx k)))]
                  (recur (inc i) nil
                         (conj stack {:name lc :indent indent :markers (count markers)})
                         (-> (reduce conj! out (map #(str indent %) (opener-html lc title)))
                             (conj! ""))))))

            :else
            (recur (inc i) nil stack (conj! out line))))))))
