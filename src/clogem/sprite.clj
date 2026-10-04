;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.sprite
  "The icon sprite, `dist/clogem/icons.svg` (DESIGN.md §11.3 item 6, D-P4-6).

  The sources are vendored SVG files under `theme/resources/icons/`, indexed
  by `icons/MANIFEST.edn` (upstream package, exact version, tarball sha256,
  licence). The sprite holds one `<symbol>` per UI icon, always, plus only
  the brand icons a site uses (`brands-in-use`), and opens with the licence
  notices of every source it draws from as an XML comment.

  A UI icon's id is its name (`sun`); a brand's is `brand-<name>`
  (`brand-github`) — Lucide and Simple Icons both have an `x`. The helper
  templates call is `clogem.theme.icons/icon`; this namespace only builds the
  file, so `clogem.assets` can ship it without a dependency cycle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-root "clogem/theme/resources/icons/")

(def manifest
  "The parsed `icons/MANIFEST.edn`: {:sources {id {…}} :ui {name {…}}
  :brands {name {…}}}."
  (delay (edn/read-string (slurp (io/resource (str resource-root "MANIFEST.edn"))))))

(defn ui-names
  "Every UI icon name, a sorted set of keywords."
  []
  (into (sorted-set) (keys (:ui @manifest))))

(defn brand-names
  "Every vendored brand name, a sorted set of keywords."
  []
  (into (sorted-set) (keys (:brands @manifest))))

(defn symbol-id
  "The `<symbol id>` of an icon: `:sun` → \"sun\", `:brand/github` →
  \"brand-github\"; nil for a name the manifest does not have."
  [k]
  (when (keyword? k)
    (case (namespace k)
      nil     (when (contains? (:ui @manifest) k) (name k))
      "brand" (when (contains? (:brands @manifest) (keyword (name k))) (str "brand-" (name k)))
      nil)))

(defn brands-in-use
  "The brands this site's pages render, as a set of brand keywords
  (`#{:github}`). None yet: `:theme :social` arrives in Phase 4 Task E1,
  which extends this function; until then the sprite carries UI icons only."
  [_cfg]
  #{})

;; ---------------------------------------------------------------------------
;; Licence notices — the text of each source's licence that must travel with
;; the icons. A test checks every copyright line against `icons/LICENSES/`.

(def ^:private isc-permission
  (str "Permission to use, copy, modify, and/or distribute this software for any purpose with or "
       "without fee is hereby granted, provided that the above copyright notice and this permission "
       "notice appear in all copies. THE SOFTWARE IS PROVIDED \"AS IS\" AND THE AUTHOR DISCLAIMS ALL "
       "WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND "
       "FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR "
       "CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, "
       "WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN "
       "CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE."))

(def ^:private mit-permission
  (str "Permission is hereby granted, free of charge, to any person obtaining a copy of this software "
       "and associated documentation files (the \"Software\"), to deal in the Software without "
       "restriction, including without limitation the rights to use, copy, modify, merge, publish, "
       "distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the "
       "Software is furnished to do so, subject to the following conditions: The above copyright notice "
       "and this permission notice shall be included in all copies or substantial portions of the "
       "Software. THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, "
       "INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE "
       "AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, "
       "DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT "
       "OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."))

(def copyright-lines
  "The copyright line each notice carries, by source (`:feather` is the MIT
  half of Lucide's licence)."
  {:lucide  "Copyright (c) 2026 Lucide Icons and Contributors"
   :feather "Copyright (c) 2013-present Cole Bemis"
   :tabler  "Copyright (c) 2020-2026 Paweł Kuna"})

(defn- notice
  "The notice paragraph for `source`, given the icon names drawn from it
  (`feather` — the Feather-derived subset of the Lucide ones)."
  [source {:keys [version name]} names feather]
  (case source
    :lucide
    (str name " " version " (lucide-static): " (str/join " " (map clojure.core/name names)) ".\n"
         "ISC License. " (copyright-lines :lucide) ". " isc-permission
         (when (seq feather)
           (str "\n\nDerived from Feather (" (str/join " " (map clojure.core/name feather)) "): "
                "The MIT License (MIT). " (copyright-lines :feather) ". " mit-permission)))
    :simple-icons
    (str name " " version ": " (str/join " " (map clojure.core/name names)) ".\n"
         "CC0 1.0 Universal (public domain dedication). The brand marks remain the trademarks of "
         "their owners; showing one implies no endorsement. See the Simple Icons disclaimer: "
         "https://github.com/simple-icons/simple-icons/blob/develop/DISCLAIMER.md")
    :tabler
    (str name " " version " (@tabler/icons): " (str/join " " (map clojure.core/name names)) ".\n"
         "MIT License. " (copyright-lines :tabler) ". " mit-permission
         "\nThe brand marks remain the trademarks of their owners.")))

;; ---------------------------------------------------------------------------
;; Building

(def ^:private kept-attrs
  "Root `<svg>` attributes a `<symbol>` keeps: presentation attributes on a
  symbol are inherited by its content through `<use>`."
  ["viewBox" "fill" "stroke" "stroke-width" "stroke-linecap" "stroke-linejoin"])

(defn- source-file [rel]
  (or (io/resource (str resource-root rel))
      (throw (ex-info (str "icon source missing: icons/" rel) {}))))

(defn- parse-svg
  "{:attrs {name value} :body \"…\"} of one vendored SVG file. The body drops
  `<title>`s, comments and Tabler's invisible 24×24 bounding-box path, and
  its whitespace is collapsed — the path data itself is upstream's, as is."
  [s]
  (let [[_ attr-str body] (or (re-find #"(?s)<svg\b([^>]*)>(.*)</svg>" s)
                              (throw (ex-info "not an SVG document" {})))
        attrs (into {} (map (fn [[_ k v]] [k v])) (re-seq #"([A-Za-z:-]+)=\"([^\"]*)\"" attr-str))
        body  (-> body
                  (str/replace #"(?s)<!--.*?-->" "")
                  (str/replace #"(?s)<title>.*?</title>" "")
                  (str/replace #"<path stroke=\"none\" d=\"M0 0h24v24H0z\" fill=\"none\"\s*/>" "")
                  (str/replace #">\s+<" "><")
                  (str/replace #"\s+" " ")
                  (str/replace #"\s*/>" "/>")
                  str/trim)]
    {:attrs attrs :body body}))

(def ^:private parsed
  "Memoized `parse-svg` of a vendored file, by its path under `icons/`."
  (memoize (fn [rel] (parse-svg (slurp (source-file rel))))))

(defn- symbol-xml
  [id rel]
  (let [{:keys [attrs body]} (parsed rel)
        ;; Simple Icons' files carry no fill: they are meant to be filled
        attrs (cond-> attrs (not (contains? attrs "fill")) (assoc "fill" "currentColor"))]
    (str "<symbol id=\"" id "\""
         (apply str (for [k kept-attrs :let [v (get attrs k)] :when v] (str " " k "=\"" v "\"")))
         ">" body "</symbol>")))

(defn- entries
  "[[id dir-rel source-id name] …] for the sprite: every UI icon, then the
  given brands, each in name order."
  [brands]
  (let [{:keys [ui] :as m} @manifest]
    (concat
     (for [[k {:keys [source upstream feather]}] (sort-by key ui)]
       {:id (name k) :rel (str "ui/" (name k) ".svg") :source source :name k :feather feather})
     (for [b (sort brands)
           :let [{:keys [source]} (or (get-in m [:brands b])
                                      (throw (ex-info (str "unknown brand icon " (pr-str b)
                                                           "; the vendored brands are "
                                                           (str/join " " (map name (brand-names))))
                                                      {:brand b})))]]
       {:id (str "brand-" (name b)) :rel (str "brands/" (name b) ".svg") :source source :name b}))))

(defn- notices
  [es]
  (let [srcs (:sources @manifest)]
    (str/join "\n\n"
              (for [s [:lucide :simple-icons :tabler]
                    :let [mine (filter #(= s (:source %)) es)]
                    :when (seq mine)]
                (notice s (get srcs s) (map :name mine) (map :name (filter :feather mine)))))))

(defn sprite
  "The sprite document, a string: the licence notices as a comment, then
  one `<symbol>` per UI icon and per brand in `brands` (brand keywords
  without a namespace, `#{:github}`). An unknown brand throws."
  ([] (sprite #{}))
  ([brands]
   (let [es (entries brands)]
     (str "<svg xmlns=\"http://www.w3.org/2000/svg\">"
          "<!--\nclogem-press icon sprite, built from vendored icon sources "
          "(src/clogem/theme/resources/icons/MANIFEST.edn).\n\n"
          ;; a comment may not contain "--"; none of the texts does
          (str/replace (notices es) "--" "- -")
          "\n-->"
          (apply str (map #(symbol-xml (:id %) (:rel %)) es))
          "</svg>\n"))))
