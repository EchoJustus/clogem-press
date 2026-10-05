;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme-modes-test
  "Phase 4 Task B1 (DESIGN.md §11.3 items 2–6 and 8): colour modes on
  `<html>` and the head script, the accessible palette, the toggle, Pagefind's
  variables, the icon sprite and its helper, and `overrides/custom.css` —
  asserted on the built demo and on theme.css itself. What needs a browser
  (the first frame, the keyboard, print) is in test/browser/modes.test.mjs."
  (:require [babashka.fs :as fs]
            [babashka.process]
            [cheshire.core]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as h]
            [clogem.assets :as assets]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.render :as render]
            [clogem.sprite :as sprite]
            [clogem.theme.icons :as icons]
            [clogem.theme.layout :as layout]))

(def demo "examples/demo-site")
(def theme-css "src/clogem/theme/resources/css/theme.css")
(def icons-dir "src/clogem/theme/resources/icons")

(def ^:dynamic *out* nil)

(defn- build-fixture
  [f]
  (let [out (fs/create-temp-dir {:prefix "clogem-modes"})
        cfg (first (diag/collecting
                    (config/load-config demo nil {:build {:out (str out)}
                                                  :content {:write-front-matter false}})))
        [model _] (diag/collecting (cli/analyse cfg))]
    (diag/collecting (render/build! cfg model))
    (binding [*out* out]
      (try (f) (finally (fs/delete-tree out))))))

(use-fixtures :once build-fixture)

(defn- pages
  "{rel-path → html} of every HTML page of the built demo."
  []
  (into (sorted-map)
        (for [p (fs/glob *out* "**.html")]
          [(str (fs/relativize *out* p)) (slurp (fs/file p))])))

(defn- out-file [& parts] (apply fs/path *out* parts))

;; ---------------------------------------------------------------------------
;; 1. Mode classes on <html> and the head script (D-P4-2)

(def pinned-hash
  "The CSP source of `layout/mode-script`. DESIGN.md §6.8 and the README
  quote it: a change to the script must update both, and this."
  "'sha256-aykGrfu05czJ6oIj+Xn+Qrjxa7JG8hF3RGl0W0liGmw='")

(deftest the-mode-script-is-byte-identical-and-precedes-every-stylesheet
  (let [ps (pages)
        tag (str "<script>" layout/mode-script "</script>")]
    (is (> (count ps) 200) "the whole demo")
    (doseq [[rel html] ps]
      (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote tag)) html)))
          (str rel ": exactly one copy of the mode script"))
      (let [i (str/index-of html tag)
            j (str/index-of html "rel=\"stylesheet\"")]
        (is (and i j (< i j)) (str rel ": the mode script comes before the first stylesheet"))))
    (testing "every inline script that reads the mode is the same bytes"
      (is (= #{layout/mode-script}
             (set (for [[_ html] ps
                        [_ body] (re-seq #"<script>(.*?)</script>" html)
                        :when (str/includes? body "clogem-mode")]
                    body)))))
    (testing "small, and its hash is the documented one"
      (is (< (count (.getBytes ^String layout/mode-script "UTF-8")) 600))
      (is (= pinned-hash (layout/mode-script-hash)))
      (is (str/includes? (slurp "README.md") pinned-hash) "README quotes it")
      (is (str/includes? (slurp "DESIGN.md") pinned-hash) "DESIGN.md §6.8 quotes it"))))

(deftest the-mode-script-chooses-and-applies-the-mode
  (testing "run under node with a stub document: storage beats the default;
            a missing, invalid or throwing store falls back to it; auto
            resolves from the media query; an OS change re-applies auto"
    (if-not (fs/which "node")
      (is (nil? (System/getenv "CI")) "node is required on CI")
      (let [harness (str "
var script = " (cheshire.core/generate-string layout/mode-script) ";
function run(stored, def, dark, throwing) {
  var attrs = {'data-default-mode': def}, listeners = [];
  var d = {className: 'theme-mode-' + def + ' theme-style-card',
           getAttribute: function (k) { return k in attrs ? attrs[k] : null; },
           setAttribute: function (k, v) { attrs[k] = String(v); }};
  var mq = {matches: dark, addEventListener: function (t, f) { listeners.push(f); }};
  var ls = {getItem: function () { if (throwing) throw new Error('blocked'); return stored; }};
  new Function('document', 'window', 'matchMedia', 'localStorage', script)(
    {documentElement: d}, {matchMedia: true}, function () { return mq; }, ls);
  var first = [attrs['data-mode'], d.className];
  mq.matches = !dark; listeners.forEach(function (f) { f(); });
  return first.concat([d.className]);
}
console.log(JSON.stringify([
  run('dark', 'light', false, false),
  run('read', 'auto', true, false),
  run(null, 'auto', true, false),
  run(null, 'auto', false, true),
  run('garbage', 'read', true, false),
  run('auto', 'dark', false, false),
  run(null, 'bogus', false, false)]));")
            f (fs/create-temp-file {:suffix ".js"})]
        (try
          (spit (fs/file f) harness)
          (let [r (babashka.process/shell {:out :string :err :string :continue true} "node" (str f))
                [a b c d e g k] (cheshire.core/parse-string (:out r))]
            (is (zero? (:exit r)) (:err r))
            (is (= ["dark" "theme-style-card theme-mode-dark" "theme-style-card theme-mode-dark"] a)
                "stored dark beats the light default, and ignores the OS")
            (is (= "read" (first b)))
            (is (str/ends-with? (second b) " theme-mode-read"))
            (is (= ["auto" "theme-style-card theme-mode-dark" "theme-style-card theme-mode-light"] c)
                "nothing stored: the default, auto → dark on a dark OS, then light when the OS changes")
            (is (= ["auto" "theme-style-card theme-mode-light"] (take 2 d)) "storage throws: the default follows the OS")
            (is (= "read" (first e)) "a garbage value: the default")
            (is (= ["auto" "theme-style-card theme-mode-light" "theme-style-card theme-mode-dark"] g)
                "stored auto beats the dark default, and follows the OS live")
            (is (= "auto" (first k)) "an invalid default: auto")
            (is (= 1 (count (re-seq #"theme-mode-" (nth c 2)))) "one mode class, never two"))
          (finally (fs/delete f)))))))

(deftest html-carries-the-classes-and-body-does-not
  (doseq [[rel html] (pages)]
    (is (re-find #"<html class=\"theme-mode-auto theme-style-card\" data-default-mode=\"auto\" " html) rel)
    (let [body (second (re-find #"<body class=\"([^\"]*)\"" html))]
      (is (some? body) rel)
      (is (not (str/includes? (str body) "theme-")) (str rel ": <body> keeps only lang-* and page-*"))
      (is (re-find #"(^| )lang-" (str body)) rel)))
  (testing "the configured mode and style are what <html> carries"
    (let [html (str (h/html (layout/document {:cfg (-> config/defaults
                                                       (assoc :clogem/site-dir demo)
                                                       (assoc-in [:theme :default-mode] :read)
                                                       (assoc-in [:theme :page-style] :line))
                                              :lang :en :title "T"})))]
      (is (str/includes? html "<html class=\"theme-mode-read theme-style-line\" data-default-mode=\"read\""))
      (is (str/includes? html "<meta content=\"light dark\" name=\"color-scheme\" />")))))

;; ---------------------------------------------------------------------------
;; 3. The palette (D-P4-4)

(def full-set
  "vdoing's variables, plus the accent and the muted text, and (Phase 4 C)
  the code block's line numbers and highlighted line — their contrast is
  checked in highlight_test.clj, against every token colour."
  #{"--bodyBg" "--mainBg" "--sidebarBg" "--blurBg" "--customBlockBg" "--textColor"
    "--textLightenColor" "--borderColor" "--codeBg" "--codeColor" "--accent" "--textColorSubtle"
    "--codeLineNumber" "--codeHlBg"})

(defn- block
  "The declarations of the first rule whose selector is exactly `sel`
  (searched from `from`, an index into `css`) as {\"--name\" \"value\"}."
  ([css sel] (block css sel 0))
  ([css sel from]
   (let [i (str/index-of css (str "\n" sel " {") from)
         _ (assert i (str "no rule " sel))
         s (str/index-of css "{" i)
         e (str/index-of css "}" s)]
     (into {} (for [[_ k v] (re-seq #"(--[A-Za-z0-9-]+|color-scheme)\s*:\s*([^;]+);" (subs css s e))]
                [k (str/trim v)])))))

(defn- mode-blocks []
  (let [css (slurp theme-css)
        media (str/index-of css "@media (prefers-color-scheme: dark) {")]
    {:light (block css ":root, .theme-mode-light")
     :read  (block css ".theme-mode-read")
     :dark  (block css ".theme-mode-dark")
     :auto  (block css "  .theme-mode-auto" media)}))

(defn- luminance [hex]
  (let [h (str/replace hex "#" "")
        h (if (= 3 (count h)) (apply str (mapcat #(repeat 2 %) h)) h)
        ch #(let [c (/ (Integer/parseInt (subs h % (+ % 2)) 16) 255.0)]
              (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4)))]
    (+ (* 0.2126 (ch 0)) (* 0.7152 (ch 2)) (* 0.0722 (ch 4)))))

(defn contrast [a b]
  (let [[x y] (sort > [(luminance a) (luminance b)])]
    (/ (+ x 0.05) (+ y 0.05))))

(deftest every-mode-block-defines-the-full-variable-set
  (let [bs (mode-blocks)]
    (doseq [[m vs] bs]
      (is (empty? (set/difference full-set (set (keys vs)))) (str m " lacks " (set/difference full-set (set (keys vs)))))
      (is (contains? vs "color-scheme") (str m " sets color-scheme")))
    (is (= (:dark bs) (:auto bs)) "the no-JS auto block is exactly the dark block")
    (is (= "dark" (get-in bs [:dark "color-scheme"])))
    (is (= "light" (get-in bs [:read "color-scheme"])))
    (testing "the brief's values"
      (is (= ["#f4f5f7" "#ffffff" "#5f6873" "#1a7350"] (map (:light bs) ["--bodyBg" "--mainBg" "--textColorSubtle" "--accent"])))
      (is (= ["#ece6d6" "#f5f2e9" "#5f5a50" "#1a7350"] (map (:read bs) ["--bodyBg" "--mainBg" "--textColorSubtle" "--accent"])))
      (is (= ["#9aa3ad" "#3eaf7c"] (map (:dark bs) ["--textColorSubtle" "--accent"])))
      (is (< (luminance (get-in bs [:read "--codeBg"])) 0.05) "read mode's code block is dark (vdoing parity)")
      ;; P4-C.1 item 14: B1's documented value; every token Chroma's
      ;; github-dark ships reaches 4.5:1 on it (`err` is not shipped)
      (is (= "#282c34" (get-in bs [:read "--codeBg"])) "read mode's code background is vdoing's"))))

(def surfaces ["--bodyBg" "--mainBg" "--sidebarBg" "--customBlockBg"])

(defn palette-minimums
  "{mode {fg [ratio surface]}} — the lowest contrast of each foreground over
  every surface, plus [:code] for --codeColor on --codeBg."
  []
  (into (sorted-map)
        (for [[m vs] (mode-blocks)]
          [m (assoc (into {} (for [fg ["--textColor" "--textColorSubtle" "--accent"]]
                               [fg (first (sort-by first (for [s surfaces] [(contrast (vs fg) (vs s)) s])))]))
                    :code [(contrast (vs "--codeColor") (vs "--codeBg")) "--codeBg"])])))

(deftest the-palette-reaches-wcag-aa-in-every-mode
  (testing "text, muted and accent on every surface, and code on its block,
            at 4.5:1 or better — the auto media block included"
    (doseq [[m fgs] (palette-minimums)
            [fg [ratio surface]] fgs]
      (is (>= ratio 4.5) (format "%s: %s on %s is %.2f:1" (name m) fg surface ratio)))
    (let [mins (palette-minimums)
          low  (fn [fg] (apply min (map #(first (get % fg)) (vals mins))))]
      (println (format "theme-modes: contrast minimums — text %.2f, muted %.2f, accent %.2f, code %.2f"
                       (low "--textColor") (low "--textColorSubtle") (low "--accent") (low :code))))))

(deftest prose-links-print-motion-and-fonts
  (let [css (slurp theme-css)]
    (testing "prose links are underlined (WCAG 1.4.1)"
      (is (re-find #"\.clogem-content a:not\(\.header-anchor\)[^{]*\{\s*text-decoration: underline" css)))
    (testing "print and reduced motion"
      (is (str/includes? css "@media print {"))
      (is (str/includes? css "@media (prefers-reduced-motion: reduce) {"))
      (let [print (subs css (str/index-of css "@media print {"))]
        (doseq [c [".clogem-navbar" ".clogem-sidebar" ".clogem-toc" ".clogem-mode" ".clogem-search"
                   ".clogem-comments" ".clogem-lang-banner"]]
          (is (str/includes? print c) (str c " is hidden in print")))
        (is (str/includes? print "--bodyBg: #ffffff") "print uses a light palette")))
    (testing "language font rules are zero-specificity, so code keeps its monospace stack"
      (is (str/includes? css ":where(:lang(ta))"))
      (is (str/includes? css ":where(:lang(zh-Hans))"))
      (is (str/includes? css ":where(:lang(zh-Hant))"))
      (is (empty? (re-seq #"(?m)^\s*:lang\(" css)) "no bare :lang() rule")
      (is (re-find #"code, pre, kbd, samp \{ font-family: ui-monospace" css)))
    (testing "the selectors are unqualified"
      (is (not (str/includes? css "body.theme-"))))))

;; ---------------------------------------------------------------------------
;; 4. Pagefind follows the mode (D-P4-5)

(deftest pagefind-variables-are-mapped-to-the-palette
  (let [css (slurp theme-css)
        b   (block css "html[class]")]
    (is (= "var(--mainBg)" (b "--pf-background")))
    (is (= "var(--textColor)" (b "--pf-text")))
    (is (= "var(--borderColor)" (b "--pf-border")))
    (is (every? #(str/starts-with? % "var(--") (vals b)) "every one follows the mode")))

;; ---------------------------------------------------------------------------
;; 2. The toggle and its strings (D-P4-3)

(deftest the-toggle-is-hidden-until-js-with-four-pressed-buttons
  (doseq [[rel html] (pages)
          :when (str/includes? html "<header class=\"clogem-navbar\"")]
    (let [box (re-find #"<div class=\"clogem-mode\"[^>]*>.*?</ul></div>" html)]
      (is box rel)
      (is (re-find #"^<div class=\"clogem-mode\" data-clogem-mode=\"\" hidden=\"hidden\">" (str box)) (str rel ": hidden"))
      (is (re-find #"aria-controls=\"clogem-mode-menu\" aria-expanded=\"false\"" (str box)))
      (is (re-find #"<ul class=\"clogem-mode__menu\" hidden=\"hidden\" id=\"clogem-mode-menu\">" (str box)))
      (is (= ["auto" "light" "dark" "read"]
             (map second (re-seq #"<button aria-pressed=\"false\" data-mode=\"([a-z]+)\"" (str box))))
          (str rel ": four aria-pressed buttons, in vdoing's order"))
      (is (str/includes? html "/clogem/js/mode.js?v=") rel))))

(deftest the-mode-strings-exist-in-all-five-languages
  (let [ks [:mode/label :mode/auto :mode/light :mode/dark :mode/read]]
    (doseq [l [:en :zh-Hans :zh-Hant :ms :ta]
            k ks]
      (is (not (str/blank? (get (i18n/theme-strings l) k))) (str l " " k)))
    (is (= ["Colour mode" "Follow system" "Light" "Dark" "Reading"] (map (i18n/theme-strings :en) ks)))
    (is (= ["主题模式" "跟随系统" "浅色模式" "深色模式" "阅读模式"] (map (i18n/theme-strings :zh-Hans) ks)))
    (is (= ["主題模式" "跟隨系統" "淺色模式" "深色模式" "閱讀模式"] (map (i18n/theme-strings :zh-Hant) ks)))
    (is (= ["Mod warna" "Ikut sistem" "Cerah" "Gelap" "Bacaan"] (map (i18n/theme-strings :ms) ks)))
    (is (= ["வண்ணப் பயன்முறை" "கணினியைப் பின்பற்று" "வெளிர்" "இருள்" "வாசிப்பு"] (map (i18n/theme-strings :ta) ks)))
    (testing "the page language labels the toggle"
      (is (str/includes? (slurp (fs/file (out-file "zh-Hant" "index.html"))) "<span>閱讀模式</span>")))))

(deftest page-style-is-validated-as-a-warning
  (let [load (fn [edn]
               (let [dir (fs/create-temp-dir {:prefix "clogem-style"})]
                 (spit (fs/file dir "site.edn") (pr-str edn))
                 (try (diag/collecting (config/load-config (str dir)))
                      (finally (fs/delete-tree dir)))))]
    (testing "0.2.0 accepted anything: an unknown value warns and falls back to :card"
      (let [[cfg ds] (load {:theme {:page-style :grid}})]
        (is (empty? (diag/errors ds)))
        (is (some #(str/includes? (:message %) ":theme :page-style is :grid, but it must be one of :card, :line; using :card.")
                  (diag/warnings ds)))
        (is (= :card (get-in cfg [:theme :page-style])))))
    (doseq [s [:card :line]]
      (let [[cfg ds] (load {:theme {:page-style s}})]
        (is (empty? (diag/warnings ds)) (str s))
        (is (= s (get-in cfg [:theme :page-style])))))))

;; ---------------------------------------------------------------------------
;; 5. Icons (D-P4-6)

(defn- sprite-ids [svg] (set (map second (re-seq #"<symbol id=\"([^\"]+)\"" svg))))

(deftest every-icon-the-demo-uses-is-in-the-sprite
  (let [svg (slurp (fs/file (out-file "clogem" "icons.svg")))
        ids (sprite-ids svg)
        v   (assets/fingerprint (fs/read-all-bytes (out-file "clogem" "icons.svg")))
        uses (for [[rel html] (pages)
                   [_ href] (re-seq #"<use href=\"([^\"]+)\"" html)]
               [rel href])]
    (is (seq uses))
    (doseq [[rel href] uses
            :let [[_ path q frag] (re-find #"^([^?#]*)(\?[^#]*)?#(.+)$" href)]]
      (is (= "/clogem/icons.svg" path) rel)
      (is (= (str "?v=" v) q) (str rel ": the sprite URL carries its fingerprint"))
      (is (contains? ids frag) (str rel ": #" frag " is in the sprite")))
    (testing "the default sprite: every UI icon, no brand, at most 12 KB"
      (is (= (set (map name (sprite/ui-names))) ids))
      (is (<= (count (.getBytes ^String svg "UTF-8")) 12288) (str (count svg) " bytes"))
      (println (format "theme-modes: icons.svg is %d bytes, %d symbols" (count (.getBytes ^String svg "UTF-8")) (count ids))))
    (testing "the licence notices travel with it"
      (let [comment (second (re-find #"(?s)<!--(.*?)-->" svg))]
        (is (str/includes? comment "Lucide 1.52.0"))
        (is (str/includes? comment "ISC License. Copyright (c) 2026 Lucide Icons and Contributors"))
        (is (str/includes? comment "Copyright (c) 2013-present Cole Bemis") "the Feather-derived icons' MIT notice")
        (is (not (str/includes? comment "Simple Icons")) "no brands, no brand notice")
        (is (not (str/includes? comment "--")) "a valid XML comment")))
    (testing "the vendored sources never ship"
      (is (not (fs/exists? (out-file "clogem" "icons" "MANIFEST.edn"))))
      (is (not (fs/exists? (out-file "clogem" "icons" "ui")))))))

(deftest only-the-brands-in-use-are-built
  (testing "the mechanism, with a fixture until :theme :social (Task E1)"
    (let [svg (sprite/sprite #{:github :linkedin})
          ids (sprite-ids svg)]
      (is (contains? ids "brand-github"))
      (is (contains? ids "brand-linkedin"))
      (is (not (contains? ids "brand-x")) "only the brands asked for")
      (is (contains? ids "x") "the UI x is still there")
      (is (str/includes? svg "Simple Icons 16.34.0: github."))
      (is (str/includes? svg "CC0 1.0"))
      (is (str/includes? svg "Tabler Icons 3.48.0 (@tabler/icons): linkedin."))
      (is (str/includes? svg "Copyright (c) 2020-2026 Paweł Kuna"))
      (is (re-find #"<symbol id=\"brand-github\" viewBox=\"0 0 24 24\" fill=\"currentColor\">" svg)
          "Simple Icons are filled with the text colour")))
  (testing "every curated brand builds, and an unknown one throws"
    (is (= (set (map #(str "brand-" (name %)) (sprite/brand-names)))
           (set (filter #(str/starts-with? % "brand-") (sprite-ids (sprite/sprite (sprite/brand-names)))))))
    (is (= 22 (count (sprite/brand-names))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown brand icon :myspace"
                          (sprite/sprite #{:myspace}))))
  (testing "no site uses a brand yet"
    (is (= #{} (sprite/brands-in-use config/defaults)))))

(deftest the-icon-helper
  (let [ctx {:cfg (assoc config/defaults :clogem/asset-versions {"icons.svg" "abcd1234"})}]
    (is (= [:svg {:class "clogem-icon" :width "1em" :height "1em" :aria-hidden "true" :focusable "false"}
            [:use {:href "/clogem/icons.svg?v=abcd1234#sun"}]]
           (icons/icon ctx :sun)))
    (is (= [:svg {:class "clogem-icon is-x" :width "1em" :height "1em" :role "img" :aria-label "Light"}
            [:use {:href "/clogem/icons.svg?v=abcd1234#sun"}]]
           (icons/icon ctx :sun {:title "Light" :class "is-x"}))
        "a :title makes it an image with that name")
    (is (= "/clogem/icons.svg?v=abcd1234#brand-github" (get-in (icons/icon ctx :brand/github) [2 1 :href])))
    (testing "an unknown icon is a build error naming the template and the icon"
      (let [e (try (icons/icon ctx (keyword "nope")) nil (catch clojure.lang.ExceptionInfo e e))]
        (is e)
        (is (re-find #"unknown icon :nope in template clogem\.theme-modes-test:\d+" (ex-message e)))
        (is (= :nope (:clogem/icon (ex-data e)))))
      (is (thrown? clojure.lang.ExceptionInfo (icons/icon ctx :brand/myspace))))))

(deftest the-vendored-sources-are-pinned-and-licensed
  (let [m @sprite/manifest]
    (doseq [[id {:keys [version sha256 license-file tarball]}] (:sources m)]
      (is (re-matches #"\d+\.\d+\.\d+" version) (str id))
      (is (re-matches #"[0-9a-f]{64}" sha256) (str id))
      (is (str/includes? tarball version) (str id))
      (is (fs/regular-file? (fs/path icons-dir license-file)) (str id " " license-file)))
    (doseq [[k {:keys [source]}] (:ui m)]
      (is (= :lucide source))
      (is (fs/regular-file? (fs/path icons-dir "ui" (str (name k) ".svg"))) (str k)))
    (doseq [[k _] (:brands m)]
      (is (fs/regular-file? (fs/path icons-dir "brands" (str (name k) ".svg"))) (str k)))
    (testing "each notice's copyright line is the licence file's"
      (is (str/includes? (slurp (fs/file icons-dir "LICENSES" "lucide-ISC-and-feather-MIT.txt"))
                         (sprite/copyright-lines :lucide)))
      (is (str/includes? (slurp (fs/file icons-dir "LICENSES" "lucide-ISC-and-feather-MIT.txt"))
                         (sprite/copyright-lines :feather)))
      (is (str/includes? (slurp (fs/file icons-dir "LICENSES" "tabler-icons-MIT.txt"))
                         (sprite/copyright-lines :tabler))))
    (testing "the ~28 UI icons of the brief"
      (is (set/subset? #{:sun :moon :monitor :book-open :search :menu :x :chevron-down :chevron-right
                         :chevron-left :arrow-up :external-link :pencil :copy :check :link :rss :mail
                         :calendar :clock :user :folder :tag :hash :globe :message-square}
                       (sprite/ui-names))))))

(deftest the-css-glyphs-are-icons-now
  (let [css (slurp theme-css)]
    (doseq [g ["▾" "▸" "›"]]
      (is (not (str/includes? css g)) (str g " is gone from theme.css"))))
  (let [html (slurp (fs/file (out-file "pages" "643259" "index.html")))]
    (is (str/includes? html "clogem-breadcrumbs__sep"))
    (is (str/includes? html "clogem-sidebar__caret"))
    (is (str/includes? html "clogem-navbar__caret"))
    (is (str/includes? html "class=\"header-anchor\" data-pagefind-ignore=\"\" href=\"#numbered-directories\">#</a>")
        "the heading anchor stays text")))

;; ---------------------------------------------------------------------------
;; 6. overrides/custom.css (D-P4-8)

(deftest custom-css-ships-fingerprinted-and-is-linked-last
  (let [f (out-file "clogem" "overrides" "custom.css")
        v (assets/fingerprint (fs/read-all-bytes f))]
    (is (fs/regular-file? f) "copied to dist/clogem/overrides/custom.css")
    (is (= (slurp (fs/file demo "overrides" "custom.css")) (slurp (fs/file f))) "as it is")
    (doseq [[rel html] (pages)
            :let [links (map second (re-seq #"<link href=\"([^\"]+)\" rel=\"stylesheet\"" html))]]
      (is (= (str "/clogem/overrides/custom.css?v=" v) (last links)) (str rel ": last, with ?v="))
      (is (some #(str/includes? % "pagefind-component-ui.css") links) (str rel ": after Pagefind's")))))

(deftest a-site-without-custom-css-links-none
  (let [dir (fs/create-temp-dir {:prefix "clogem-nocustom"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str {:site {:title "S"} :search {:provider :none}}))
      (fs/create-dirs (fs/path dir "content"))
      (spit (fs/file dir "content" "index.md") "# Home\n")
      (binding [diag/*sink* (atom [])]
        (with-out-str (cli/build {:site-dir (str dir) :no-write true})))
      (let [html (slurp (fs/file dir "dist" "index.html"))]
        (is (not (str/includes? html "custom.css")))
        (is (not (fs/exists? (fs/path dir "dist" "clogem" "overrides"))))
        (is (fs/regular-file? (fs/path dir "dist" "clogem" "icons.svg")) "the sprite always ships"))
      (finally (fs/delete-tree dir)))))
