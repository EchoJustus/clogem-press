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
