;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.markdown
  "nextjournal/markdown wrapper (DESIGN.md §5.2 step 4).

  Four renderer overrides matter in Phase 1:

    :html-block / :html-inline  raw passthrough — REQUIRED. Without it the
                                default renderer emits a red \"Unknown type\"
                                box instead of the author's HTML.
    :heading                    anchor link built from the pre-computed slug
    :link                       permalink-aware rewriting with dead-link warnings
    :code                       plain fenced output (Chroma arrives in Phase 4)

  ## Heading slugs: what nextjournal/markdown actually does

  DESIGN.md called these \"GitHub-style\" and flagged CJK/Tamil behaviour as
  untested. Measured under bb 1.13.219 (nextjournal/markdown 0.7.225), the real
  algorithm is: strip inline markup to text, trim, lower-case (Unicode-aware),
  replace each space with `-`, replace `_` with `-`, and **leave everything else
  alone** — then de-duplicate within a document with `-2`, `-3` suffixes.

  Consequences, all verified:

    \"Hello World\"        → \"hello-world\"      (as expected)
    \"你好世界\"            → \"你好世界\"          CJK preserved verbatim
    \"வணக்கம் உலகம்\"      → \"வணக்கம்-உலகம்\"    Tamil preserved, space → hyphen
    \"Hello, World!\"      → \"hello,-world!\"    punctuation NOT stripped
    \"100% Done\"          → \"100%-done\"        — unlike GitHub, which strips it
    \"Hello  World\"       → \"hello--world\"     runs are not collapsed

  So the good news is that the scripts we care about survive intact — the risk
  the design flagged does not materialize. The bad news is the punctuation
  handling, which produces ids that are legal HTML but need percent-encoding to
  appear in an href, and one genuine defect: a tab inside a heading survives into
  the id, and whitespace in an `id` attribute is invalid HTML.

  `anchor-id` therefore applies one deterministic repair — collapse any residual
  Unicode whitespace to `-` — and is used by BOTH the heading renderer and the
  TOC, so the two can never disagree. `clogem.util/url-encode-fragment` handles
  the href side."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hiccup2.core :as h]
            [nextjournal.markdown :as md]
            [clogem.containers :as containers]
            [clogem.diag :as diag]
            [clogem.util :as u]))

(defn anchor-id
  "The pre-computed slug, repaired so it is a valid HTML id.

  The only repair is whitespace → `-`; everything else is passed through, so the
  id still matches what the AST's :toc carries for every heading that does not
  contain a tab."
  [slug]
  (when slug
    (-> (str slug)
        (str/replace #"\s+" "-")
        (str/replace #"^-+|-+$" ""))))

(defn- node-text
  "The raw text of an html-block / html-inline node. The parser stores the
  markup as :text children rather than on the node itself."
  [node]
  (or (:text node)
      (apply str (map :text (:content node)))))

(defn- children
  [ctx node]
  (map #(md/->hiccup ctx %) (:content node)))

;; ---------------------------------------------------------------------------
;; Link rewriting (§5.2 step 4, §6.3)

(defn- external? [href]
  (boolean (re-find #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//|#|mailto:|tel:)" (str href))))

(defn with-base
  "vdoing's `$withBase`: a root-relative site path gets the site's `:base`
  prefixed, so `/assets/x.png` and `/categories/` written by an author work
  on a project site served under `/project/`. Already-prefixed paths and a
  root base are left alone."
  [cfg href]
  (let [base (u/clean-url (or (get-in cfg [:site :base]) "/"))]
    (if (or (= "/" base) (str/starts-with? href base))
      href
      (str/replace (str base href) #"/{2,}" "/"))))

(defn rewrite-href
  "Resolve a link to its destination URL.

  Four cases are rewritten; everything else passes through untouched:

    `/pages/xxxxxx/`  → the identity URL, resolved to the reader's own language
                        variant when the article has one (§6.3)
    `other.md`        → the article that file belongs to, same resolution
    `#frag`           → percent-encoded, since heading ids may be non-ASCII
    `/anything/else`  → the site :base prefixed (`with-base`)

  A `.md` or `/pages/` target that resolves to nothing is a dead link and warns
  (the design's requirement); the original href is left in place so the page
  still renders."
  [{:keys [cfg articles by-rel-path lang from-path url-for]} href]
  (let [href (str href)]
    (cond
      (str/blank? href) href

      (str/starts-with? href "#")
      (str "#" (u/url-encode-fragment (subs href 1)))

      (external? href) href

      ;; a permalink, with or without a fragment
      (re-find #"^/pages/" href)
      (let [[path frag] (str/split href #"#" 2)
            group (get articles (u/clean-url path))]
        (if group
          (str (url-for group lang) (when frag (str "#" (u/url-encode-fragment frag))))
          (do (diag/warn! from-path (str "link to unknown permalink: " href))
              href)))

      ;; a relative .md link
      (re-find #"\.md(#.*)?$" href)
      (let [[path frag] (str/split href #"#" 2)
            group (get by-rel-path (u/lower path))]
        (if group
          (str (url-for group lang) (when frag (str "#" (u/url-encode-fragment frag))))
          (do (diag/warn! from-path (str "dead link: " href " does not resolve to a page"))
              href)))

      ;; a root-relative site path: assets, index pages, anything hand-written
      (str/starts-with? href "/")
      (with-base cfg href)

      :else href)))

;; ---------------------------------------------------------------------------
;; Renderers

(defn renderers
  [link-ctx]
  (assoc md/default-hiccup-renderers

         :html-block  (fn [_ctx node] (h/raw (node-text node)))
         :html-inline (fn [_ctx node] (h/raw (node-text node)))

         :heading
         (fn [ctx node]
           (let [id (anchor-id (get-in node [:attrs :id]))]
             (into [(keyword (str "h" (:heading-level node)))
                    (cond-> {} id (assoc :id id))
                    (when id
                      [:a.header-anchor {:href (str "#" (u/url-encode-fragment id))
                                         :aria-hidden "true"} "#"])]
                   (children ctx node))))

         :link
         (fn [ctx node]
           (let [href (rewrite-href link-ctx (get-in node [:attrs :href]))
                 ext? (external? href)]
             (into [:a (cond-> {:href href}
                         (:title (:attrs node)) (assoc :title (:title (:attrs node)))
                         (and ext? (str/starts-with? (str href) "http"))
                         (assoc :target "_blank" :rel "noopener noreferrer"))]
                   (children ctx node))))

         ;; images: the src is a link too — `/assets/x.png` needs the base
         :image
         (fn [_ctx node]
           (let [{:keys [src alt title]} (:attrs node)]
             [:img (cond-> {:src (rewrite-href link-ctx src)
                            :alt (or alt (md/node->text node))}
                     title (assoc :title title))]))

         ;; Phase 1 emits plain fenced code. Phase 4 swaps in Chroma behind the
         ;; same seam, which is why the class name already follows the
         ;; `language-x` convention highlighters expect.
         :code
         (fn [_ctx node]
           (let [lang (some-> (:info node) (str/split #"\s+") first u/blank->nil)
                 text (apply str (map :text (:content node)))]
             [:pre {:class (str "clogem-code" (when lang (str " language-" lang)))}
              [:code (cond-> {} lang (assoc :class (str "language-" lang)))
               text]]))))

;; ---------------------------------------------------------------------------
;; Public API

(defn parse
  "Source → AST, after the container pre-pass (`clogem.containers/expand`).
  `ctx` is the page context — `:cfg :lang :strings :dev?` for the containers'
  default titles in the page's language, plus the link context for card-list
  hrefs; the one-arity form is for ad-hoc parsing with English titles."
  ([source] (parse source {}))
  ([source ctx]
   (md/parse (containers/expand source (assoc ctx :rewrite-href #(rewrite-href ctx %))))))

(defn ->hiccup
  [ast link-ctx]
  (md/->hiccup (renderers link-ctx) ast))

(defn render
  "Markdown source → hiccup."
  [source ctx]
  (->hiccup (parse source ctx) ctx))

(def more-marker-re #"<!--\s*more\s*-->")

(defn drop-leading-h1
  "The body's leading `# Title` duplicates the title the theme already
  renders (D-P2-9): an excerpt starts after it, and the article body is
  rendered without it so a page carries one <h1>, not two."
  [ast]
  (let [c (:content ast)]
    (if (and (= :heading (:type (first c))) (= 1 (:heading-level (first c))))
      (assoc ast :content (vec (rest c)))
      ast)))

(defn excerpt
  "The homepage excerpt of an article (D-P2-5, as amended in 0.1.1):
  everything before `<!-- more -->`, with the leading h1 dropped — and nil
  when there is no marker, as in VuePress and vdoing, which take an excerpt
  only from the marker. (A first-paragraph fallback cut real cards off
  mid-sentence.)

  Rendered under `diag/quietly`: the article page is the authoritative render
  and reports every real problem once; the excerpt would repeat each one per
  language home, and the slice can cut a `::: tip` open and warn about a
  container the article closes. The hiccup is realized inside the binding."
  [source link-ctx]
  (let [src (str source)]
    (when (re-find more-marker-re src)
      (diag/quietly
       (let [ast (drop-leading-h1 (parse (first (str/split src more-marker-re 2)) link-ctx))]
         (when (seq (:content ast))
           (walk/postwalk identity (->hiccup ast link-ctx))))))))

(defn toc-entries
  "Flatten the AST's :toc into [{:level :id :text :href}] with repaired ids,
  so the right-hand TOC bar and the heading anchors agree by construction.
  `:href` is the percent-encoded fragment (`#` + `url-encode-fragment`), the
  same encoding the heading anchor uses; the scroll-spy decodes it again
  before `getElementById`, because ids are stored unencoded."
  [ast]
  (letfn [(walk [node]
            (concat
             (when-let [lvl (:heading-level node)]
               (let [id (anchor-id (get-in node [:attrs :id]))]
                 [{:level lvl
                   :id    id
                   :text  (md/node->text node)
                   :href  (str "#" (u/url-encode-fragment (str id)))}]))
             (mapcat walk (:children node))))]
    (vec (mapcat walk (:children (:toc ast))))))

(defn toc
  "The right-hand TOC of D-P2-9: heading levels 2 … (1 + `depth`) — so the
  vdoing default `sidebarDepth: 2` shows h2–h3 — with the body's leading h1
  and every other h1 dropped, since the theme already renders the title."
  [ast depth]
  (let [depth (long (or depth 2))]
    (if (< depth 1)
      []                                   ; vdoing: `sidebarDepth: 0` = no TOC
      (filterv #(<= 2 (:level %) (inc depth)) (toc-entries ast)))))
