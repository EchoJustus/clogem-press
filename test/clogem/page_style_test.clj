;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.page-style-test
  "Phase 4 Task B2a: the contrast tests read the merged cascade (item 1),
  Pagefind's remaining variables (item 2), the mode toggle's name (item 3),
  the card and line page styles (item 5), focus rings (item 6) and the
  stylesheet's budget (item 8) — on theme.css's text and on temp sites.
  What needs a browser is in test/browser/modes.test.mjs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.css-palette :as cp]))

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
