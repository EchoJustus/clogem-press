;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.page-style-test
  "Phase 4 Task B2a: the contrast tests read the merged cascade (item 1),
  Pagefind's remaining variables (item 2), the mode toggle's name (item 3),
  the card and line page styles (item 5), focus rings (item 6) and the
  stylesheet's budget (item 8) — on theme.css's text and on temp sites.
  What needs a browser is in test/browser/modes.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [clogem.config :as config]
            [clogem.css-palette :as cp]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.theme.layout :as layout]))

(defn- with-site
  "Load a temp site whose site.edn is `edn`; call (f cfg diagnostics)."
  [edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-b2a"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str edn))
      (let [[cfg ds] (diag/collecting (config/load-config (str dir)))]
        (f cfg ds))
      (finally (fs/delete-tree dir)))))

(def two-langs
  {:site {:title "S"}
   :langs {:default :en :priority [:en :zh-Hans]
           :locales {:en {:label "English" :html-lang "en"}
                     :zh-Hans {:label "简体中文" :html-lang "zh-Hans"}}}})

(def theme-css "src/clogem/theme/resources/css/theme.css")

;; ---------------------------------------------------------------------------
;; 1. The contrast test reads every rule

(deftest the-reader-merges-every-rule-in-cascade-order
  (let [css ":root, .theme-mode-light { --a: #111111; --b: #222222; }
.theme-mode-dark { --a: #333333; }
html[class] { --c: var(--a); }
.theme-mode-dark { --b: #444444; }
@media print { html[class] { --a: #555555; } }
@media (prefers-color-scheme: dark) { .theme-mode-auto { --a: #666666; } }
.theme-style-line { --d: var(--missing); --e: var(--missing, #777777); }
html.theme-mode-dark { --f: 1; }
.theme-mode-dark { --f: 2; }"]
    (testing "a later rule with the same selector is not lost"
      (is (= "#444444" ((cp/palette css :dark) "--b"))))
    (testing "var() resolves on <html>, in the mode"
      (is (= "#333333" ((cp/palette css :dark) "--c")))
      (is (= "#111111" ((cp/palette css :light) "--c"))))
    (testing "print's html[class] (0,1,1) beats .theme-mode-dark (0,1,0), wherever it is"
      (is (= "#555555" ((cp/palette css :print) "--a"))))
    (testing "auto inside its dark media block"
      (is (= "#666666" ((cp/palette css :auto) "--a"))))
    (testing "specificity before source order"
      (is (= "1" ((cp/palette css :dark) "--f"))))
    (testing "a compound selector on another class: only for that style"
      (is (= "#777777" ((cp/palette css :light :line) "--e")))
      (is (nil? ((cp/palette css :light :card) "--e")))
      (is (not (contains? (cp/palette css :light :line) "--d")) "an unresolvable var() is invalid")))
  (testing "selectors that are not <html> match nothing"
    (is (= {} (cp/palette "body .theme-mode-dark { --a: #000; } .theme-mode-dark a { --b: #000; } :root:not(.x) { --c: #000; }" :dark)))))

(deftest colours-and-compositing
  (is (= [255 255 255 1.0] (cp/rgba "#fff")))
  (is (= [255 0 0 1.0] (cp/rgba "#ff0000")))
  (is (< (Math/abs (- (/ 230 255.0) (nth (cp/rgba "#ffffffe6") 3))) 1e-9) "#rrggbbaa is translucent")
  (is (= [0.0 0.0 0.0 0.5] (cp/rgba "rgba(0, 0, 0, 0.5)")))
  (is (= [0.0 0.0 0.0 0.7] (cp/rgba "rgb(0 0 0 / 70%)")))
  (is (= "#7f7f7f" (cp/hex (cp/over "#00000080" "#ffffff"))) "composited over what lies under it")
  (is (< (Math/abs (- 21.0 (cp/contrast "#000" "#fff"))) 1e-9))
  (is (thrown? clojure.lang.ExceptionInfo (cp/contrast "#000" "#ffffff80")) "the background must be opaque"))

(deftest the-palette-test-sees-what-b1-missed
  (let [css (slurp theme-css)]
    (testing "the real theme.css passes in every mode and style"
      (is (= [] (cp/failures css))))
    (testing "a later .theme-mode-dark rule fails it (B1's reader never saw it)"
      (let [fs (cp/failures (str css "\n.theme-mode-dark { --textColorSubtle: #555555; }\n"))]
        (is (seq fs))
        (is (some #(str/starts-with? % "dark/card: --textColorSubtle") fs) (pr-str fs))
        (is (not-any? #(str/starts-with? % "light/") fs) "only dark is touched")))
    (testing "a translucent --blurBg fails it (B1 read #00000080 as #000000, opaque)"
      (let [light (cp/rule css ":root, .theme-mode-light")
            fs (cp/failures (str/replace-first css (str "--blurBg: " (light "--blurBg") ";") "--blurBg: #00000080;"))]
        (is (some #(re-find #"^light/card: --textColor on --blurBg over --bodyBg" %) fs) (pr-str fs))))
    (testing "--blurBg is a surface"
      (is (some #(str/starts-with? (first %) "--blurBg") (cp/surfaces (cp/palette css :light)))))))

;; ---------------------------------------------------------------------------
;; 2. Pagefind's remaining variables

(def pagefind-b2a
  "What Pagefind 1.5.2's pagefind-component-ui.css declares on :root beyond
  the 11 B1 mapped; its dark values are only under [data-pf-theme=\"dark\"]."
  ["--pf-error-bg" "--pf-error-border" "--pf-error-text" "--pf-error-text-secondary"
   "--pf-shadow-sm" "--pf-shadow-md" "--pf-shadow-lg" "--pf-scroll-shadow" "--pf-modal-backdrop"])

(def pagefind-dark
  {"--pf-shadow-sm" "0 2px 8px rgba(0, 0, 0, 0.3)"
   "--pf-shadow-md" "0 4px 12px rgba(0, 0, 0, 0.4)"
   "--pf-shadow-lg" "0 16px 48px rgba(0, 0, 0, 0.5)"
   "--pf-scroll-shadow" "rgba(255, 255, 255, 0.1)"
   "--pf-modal-backdrop" "rgba(0, 0, 0, 0.7)"})

(def pagefind-light
  {"--pf-shadow-sm" "0 2px 8px rgba(0, 0, 0, 0.06)"
   "--pf-shadow-md" "0 4px 12px rgba(0, 0, 0, 0.1)"
   "--pf-shadow-lg" "0 16px 48px rgba(0, 0, 0, 0.2)"
   "--pf-scroll-shadow" "rgba(0, 0, 0, 0.08)"
   "--pf-modal-backdrop" "rgba(0, 0, 0, 0.5)"})

(deftest pagefinds-remaining-variables-follow-the-mode
  (let [rules (cp/parse (slurp theme-css))]
    (doseq [m (keys cp/modes) s cp/styles
            :let [p (cp/palette rules m s)
                  what (str (name m) "/" (name s))]]
      (testing (str what ": every one is set, at (0,1,1) or above (Pagefind's :root is linked later)")
        (doseq [v pagefind-b2a]
          (is (some? (p v)) (str what " " v))))
      (testing (str what ": the error text reaches 4.5:1 on the error background")
        (doseq [fg ["--pf-error-text" "--pf-error-text-secondary"]]
          (let [r (cp/contrast (p fg) (cp/over (p "--pf-error-bg") cp/white))]
            (is (>= r 4.5) (format "%s %s %s on %s is %.2f:1" what fg (p fg) (p "--pf-error-bg") r))))))
    (testing "dark (and auto under a dark OS) gets Pagefind's dark shadows and backdrop; light and read its light ones"
      (doseq [m [:dark :auto]]
        (is (= pagefind-dark (select-keys (cp/palette rules m) (keys pagefind-dark))) (str m)))
      (doseq [m [:light :read]]
        (is (= pagefind-light (select-keys (cp/palette rules m) (keys pagefind-light))) (str m))))
    (testing "the error colours are the palette's, per mode, in each mode's own block"
      (let [css (slurp theme-css)]
        (doseq [[what b] {"light" (cp/rule css ":root, .theme-mode-light")
                          "read"  (cp/rule css ".theme-mode-read")
                          "dark"  (cp/rule css ".theme-mode-dark")
                          "auto"  (cp/rule css ".theme-mode-auto" ["(prefers-color-scheme: dark)"])
                          "print" (cp/rule css "html[class]" ["print"])}]
          (doseq [v ["--errorBg" "--errorBorder" "--errorText"]]
            (is (contains? b v) (str what " " v))))
        (is (not= ((cp/palette rules :light) "--pf-error-bg") ((cp/palette rules :dark) "--pf-error-bg"))
            "dark's error box is not the pale #fef2f2")))))

;; ---------------------------------------------------------------------------
;; 3. The toggle names the current mode

(deftest the-toggle-is-named-for-the-mode-in-the-pages-language
  (with-site (assoc two-langs :theme {:default-mode :dark})
    (fn [cfg _]
      (let [cfg (assoc cfg :clogem/asset-versions {"icons.svg" "abcd1234"})
            toggle (fn [lang] (str (h/html (layout/mode-toggle {:cfg cfg :lang lang :strings (i18n/load-strings cfg)}))))
            en (toggle :en)
            zh (toggle :zh-Hans)]
        (testing "the server names the default mode; js/mode.js renames it on every change"
          (is (str/includes? en "aria-label=\"Colour mode: Dark\""))
          (is (str/includes? en "title=\"Colour mode: Dark\""))
          (is (str/includes? zh "aria-label=\"主题模式：深色模式\"")))
        (testing "the page language's pattern travels with the button, {{mode}} left in"
          (is (str/includes? en "data-label=\"Colour mode: {{mode}}\""))
          (is (str/includes? zh "data-label=\"主题模式：{{mode}}\"")))
        (testing "B1's pinned markup still holds"
          (is (re-find #"^<div class=\"clogem-mode\" data-clogem-mode=\"\" hidden=\"hidden\">" en))
          (is (re-find #"aria-controls=\"clogem-mode-menu\" aria-expanded=\"false\"" en))))))
  (testing "the string, in all five languages"
    (is (= {:en "Colour mode: {{mode}}" :zh-Hans "主题模式：{{mode}}" :zh-Hant "主題模式：{{mode}}"
            :ms "Mod warna: {{mode}}" :ta "வண்ணப் பயன்முறை: {{mode}}"}
           (into {} (for [l [:en :zh-Hans :zh-Hant :ms :ta]] [l (:mode/current (i18n/theme-strings l))]))))
    (doseq [l [:ms :ta]]
      (is (re-find #"(?m)^ ;; Awaiting native review.*B2a.*\n :mode/current"
                   (slurp (str "src/clogem/theme/resources/i18n/" (name l) ".edn")))
          (str l ": marked for native review"))))
  (testing "mode.js re-applies the stored mode when the head script did not run"
    (let [js (slurp "src/clogem/theme/resources/js/mode.js")]
      (is (str/includes? js "if (!valid(root.getAttribute(\"data-mode\")))"))
      (is (str/includes? js "button.setAttribute(\"aria-label\", name)")))))

;; ---------------------------------------------------------------------------
;; 5. Card and line page styles

(def boxes
  "The boxes a page style draws: the main column of every page kind, the
  sidebar, the home right-bar's sections and the home's post cards."
  [".clogem-main" ".clogem-sidebar" ".clogem-home-right > section" ".clogem-post-card"])

(defn- style-decls
  "{property value} that every rule naming `box` under `.theme-style-<style>`
  declares, merged in source order (for the shadow and the border)."
  [rules style box]
  (into {} (for [{:keys [selectors decls media]} rules
                 :when (empty? media)
                 sel selectors
                 :when (and (str/starts-with? sel (str ".theme-style-" (name style) " "))
                            (str/includes? sel box)
                            (not (str/includes? sel ":hover")))
                 [p v] decls]
             [p v])))

(deftest card-and-line-differ-in-every-mode
  (let [css (slurp theme-css)
        rules (cp/parse css)]
    (testing "the palette passes for both styles in every mode (item 1's test)"
      (is (= [] (cp/failures css))))
    (testing "card: the page and the card are distinct colours in every mode — dark too (the owner's decision)"
      (doseq [m [:light :read :dark :auto]
              :let [p (cp/palette rules m :card)]]
        (is (not= (cp/hex (p "--bodyBg")) (cp/hex (p "--mainBg"))) (str m))))
    (testing "dark's page is lighter than its card, as vdoing's (rgb(39,39,43) on rgb(30,30,34))"
      (let [p (cp/palette rules :dark :card)]
        (is (> (cp/luminance (p "--bodyBg")) (cp/luminance (p "--mainBg"))))
        (is (= "#16181d" (p "--mainBg")) "the card keeps B1's accepted value")))
    (testing "line: the page is the card's colour in every mode"
      (doseq [m (keys cp/modes)
              :let [p (cp/palette rules m :line)]]
        (is (= (p "--mainBg") (p "--bodyBg")) (str m))))
    (testing "card: every box has a shadow; line: a 1px border and none"
      (doseq [b boxes]
        (let [card (style-decls rules :card b)
              line (style-decls rules :line b)]
          (is (re-find #"var\(--shadowColor\)" (str (card "box-shadow"))) (str "card " b ": " (pr-str card)))
          (is (= "none" (line "box-shadow")) (str "line " b ": " (pr-str line)))
          (is (= "1px solid var(--borderColor)" (line "border")) (str "line " b)))))
    (testing "print: no shadow anywhere"
      (is (some (fn [{:keys [media selectors decls]}]
                  (and (= ["print"] media) (some #{"*"} selectors)
                       (some #(= ["box-shadow" "none" true] %) decls)))
                rules)))))

(deftest page-style-reaches-every-page
  (doseq [style [:card :line]]
    (let [dir (fs/create-temp-dir {:prefix "clogem-style"})]
      (try
        (spit (fs/file dir "site.edn") (pr-str {:site {:title "S"} :search {:provider :none}
                                                :theme {:page-style style}}))
        (fs/create-dirs (fs/path dir "content" "01.Guide"))
        (spit (fs/file dir "content" "index.md") "# Home\n")
        (spit (fs/file dir "content" "01.Guide" "01.one.md")
              "---\ntitle: One\ndate: 2026-02-01\npermalink: /pages/b2a001/\n---\nBody.\n")
        (let [[_ ds] (binding [*out* (java.io.StringWriter.)]
                       (diag/collecting ((requiring-resolve 'clogem.cli/build) {:site-dir (str dir) :no-write true})))
              htmls (for [p (fs/glob (fs/path dir "dist") "**.html")] [(str p) (slurp (fs/file p))])]
          (is (empty? (diag/warnings ds)) (pr-str (map :message (diag/warnings ds))))
          (is (> (count htmls) 3))
          (doseq [[p html] htmls
                  :when (str/includes? html "clogem-navbar")]
            (is (str/includes? html (str "<html class=\"theme-mode-auto theme-style-" (name style) "\"")) p)
            (is (str/includes? html "<main class=\"clogem-main\"") (str p ": the box the style draws"))))
        (finally (fs/delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; 6. Focus rings (D-P4-9)

(deftest one-focus-ring-reaches-3-to-1-everywhere
  (let [css (slurp theme-css)
        rules (cp/parse css)]
    (testing "one :focus-visible rule for everything: 2px solid, 2px offset, the ring colour"
      (is (= {"outline" "2px solid var(--focusRing)" "outline-offset" "2px"} (cp/rule rules ":focus-visible")))
      (is (= {"outline-color" "var(--codeFocusRing)"} (cp/rule rules "pre.clogem-code :focus-visible"))
          "inside a code block, the code ring colour"))
    (testing "no per-control ring left to drift (B1 drew 2px/1px on four controls, Chromium's auto 1px elsewhere)"
      (is (= 1 (count (filter #(some (fn [sel] (re-find #"focus-visible" sel)) (:selectors %))
                              (filter #(some (fn [[p]] (= "outline" p)) (:decls %)) rules))))))
    (testing "the banner's main column keeps drawing no ring (lang_test pins it)"
      (is (str/includes? css ".clogem-main[tabindex=\"-1\"]:focus { outline: none; }")))
    (testing "the ring reaches 3:1 (WCAG 1.4.11) on every surface — --blurBg included — and the code ring on --codeBg, in every mode and style"
      (doseq [m (keys cp/modes) s cp/styles
              :let [p (cp/palette rules m s)
                    what (str (name m) "/" (name s))
                    ss (cp/surfaces p)
                    main (second (first (filter #(= "--mainBg" (first %)) ss)))
                    code (cp/over (p "--codeBg") main)]]
        (is (some? (p "--focusRing")) what)
        (doseq [[sn bg] ss]
          (let [r (cp/contrast (p "--focusRing") bg)]
            (is (>= r 3.0) (format "%s: the ring %s on %s is %.2f:1" what (p "--focusRing") sn r))))
        (let [r (cp/contrast (p "--codeFocusRing") code)]
          (is (>= r 3.0) (format "%s: the code ring %s on --codeBg %s is %.2f:1" what (p "--codeFocusRing") (cp/hex code) r)))))
    (testing "B1's ring in read mode's code block was 2.41:1"
      (let [p (cp/palette rules :read)]
        (is (< (cp/contrast (p "--accent") (p "--codeBg")) 3.0))))))
