;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.highlight-test
  "Phase 4 Task C (DESIGN.md §11.3 item 10): Chroma highlighting.

  Network-free: every build here runs the fake `chroma`
  (`clogem.fake-tools/fake-chroma!`), which serves the real 2.27.0
  `--list` and style output captured under test/fixtures/highlight/. The
  one exception is `the-real-chroma-highlights-the-demo`, which runs the
  real binary when `CLOGEM_CHROMA` names one — CI always sets it, and the
  test fails there when it does not.

  The test runner makes `:provider :none` the default for every OTHER
  fixture (`clogem.test-runner`), so each site here asks for :chroma."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.highlight :as hl]
            [clogem.markdown :as markdown]
            [clogem.render]
            [clogem.tools :as tools]
            [org.httpkit.server]))

(def fixtures "test/fixtures/highlight")
(def theme-css "src/clogem/theme/resources/css/theme.css")
(def demo "examples/demo-site")

;; ---------------------------------------------------------------------------
;; Helpers

(defn- reset-dev-failures! [] (reset! @#'hl/dev-failures #{}))

(declare bb-exe)

(defn- with-site
  "A temp site: site.edn from `site-edn` (highlighting on, the fake chroma
  at `bin` as :tools :chroma :path) and {rel → content} under content/.
  Calls (f dir out bin) with the fake's path, the cache cleared and the
  process counts reset; `tools/*env*` holds only CLOGEM_CHROMA=bin, so a
  CI runner's real binary never stands in for the fake."
  [files f & [{:keys [site-edn fake-opts]}]]
  (let [dir (fs/create-temp-dir {:prefix "clogem-hl"})
        bin (apply fake/fake-chroma! (fs/path dir ".fake-bin") (mapcat identity fake-opts))
        ;; a binary of its own: the lexer list, the styles and the
        ;; fragments are cached per binary sha256 for the whole process
        _   (spit (fs/file bin) (str "# " (random-uuid) "\n") :append true)]
    (try
      (spit (fs/file dir "site.edn")
            (pr-str (merge-with (fn [a b] (if (map? a) (merge a b) b))
                                {:site      {:title "Code" :url "https://code.example"}
                                 :langs     {:default :en :priority [:en :zh-Hans]
                                             :locales {:en {:label "English" :html-lang "en"}
                                                       :zh-Hans {:label "简体中文" :html-lang "zh-Hans"}}}
                                 :content   {:write-front-matter false}
                                 :highlight {:provider :chroma}}
                                site-edn)))
      (doseq [[rel content] files
              :let [p (fs/path dir "content" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) content))
      (hl/clear-cache!)
      (hl/reset-stats!)
      (reset-dev-failures!)
      (binding [tools/*env* {"CLOGEM_CHROMA" bin}]
        (f dir (fs/path dir "dist") bin))
      (finally (fs/delete-tree dir)))))

(defn- build!
  "`bb build` in-process: {:result :out :err} or {:error ex :err}."
  [dir out & [opts]]
  (let [err (java.io.StringWriter.)
        r   (binding [*err* err]
              (try (let [o (with-out-str (cli/build (merge {:site-dir (str dir) :out (str out)} opts)))]
                     {:out o})
                   (catch clojure.lang.ExceptionInfo e {:error e})))]
    (assoc r :err (str err))))

(defn- page [out & parts] (slurp (apply fs/file out (concat parts ["index.html"]))))

(defn- article
  [title permalink body & [fm]]
  (str "---\ntitle: " title "\ndate: \"2026-09-01 10:00:00\"\npermalink: " permalink "\n" fm "---\n\n" body))

(def mixed-body
  (str "# Code\n\n"
       "```clojure\n(defn f [x]\n  ;; 注释 தமிழ்\n  (inc x))\n```\n\n"
       "```js{1,3-5}\nconst a = 1;\nconst b = 2;\nconst c = 3;\nconst d = 4;\nconst e = 5;\nconst f = 6;\n```\n\n"
       "```bash:no-line-numbers\necho hi\n```\n\n"
       "```json\n{\"a\": 1}\n```\n\n"
       "```nosuchlang\nmystery\n```\n\n"
       "```NoSuchLang:no-line-numbers\nmystery two\n```\n\n"
       "```\nno language\n```\n\n"
       "```text\nplain text\n```\n\n"
       "```js\nlet s = \"</code></pre><script>alert(1)</script>\";\n```\n\n"
       "```nosuchlang\n</code></pre><script>alert(2)</script>\n```\n\n"
       "```clojure title=\"x {9}\" {1}\n(defn g [] 1)\n(g)\n```\n\n"
       "```js\n```\n\n"
       "    indented code\n"))

(def mixed-site
  {"01.Guide/01.code.md"         (article "Code" "/pages/c0de01/" mixed-body)
   "01.Guide/01.code.zh-Hans.md" (article "代码" "/pages/c0de01/" "# 代码\n\n```clojure\n(defn f [x]\n  ;; 注释 தமிழ்\n  (inc x))\n```\n")
   "01.Guide/02.prose.md"        (article "Prose" "/pages/c0de02/" "# Prose\n\nNo code here.\n")})

(defn- class-attrs [html] (map second (re-seq #"class=\"([^\"]*)\"" html)))

(defn- pres
  "Each `<pre class=\"clogem-code…\">…</pre>` of `html`: [pre-open-tag inner]."
  [html]
  (map (fn [[_ open inner]] [open inner])
       (re-seq #"(?s)(<pre class=\"clogem-code[^>]*>)(.*?)</pre>" html)))

(defn- unescape [s]
  (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&#34;" "\"")
      (str/replace "&quot;" "\"") (str/replace "&#39;" "'") (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn- code-text
  "The text a reader sees (and `code.textContent` copies) of a `<pre>`'s
  inner HTML: tags stripped, entities decoded."
  [inner]
  (unescape (str/replace inner #"<[^>]+>" "")))

;; ---------------------------------------------------------------------------
;; 2. Fence info

(deftest fence-info-is-parsed-from-the-raw-info-string
  (doseq [[info expected]
          [["js{1,3-5}"                  {:lang "js" :hl [[1 1] [3 5]] :line-numbers nil}]
           ["js {2}"                     {:lang "js" :hl [[2 2]] :line-numbers nil}]
           ["js:line-numbers"            {:lang "js" :hl [] :line-numbers true}]
           ["js:no-line-numbers"         {:lang "js" :hl [] :line-numbers false}]
           ["js{2}:no-line-numbers"      {:lang "js" :hl [[2 2]] :line-numbers false}]
           ["ts:line-numbers {1}"        {:lang "ts" :hl [[1 1]] :line-numbers true}]
           ["clojure title=\"x\" {1}"    {:lang "clojure" :hl [[1 1]] :line-numbers nil}]
           ["clojure title=\"a {9}\""    {:lang "clojure" :hl [] :line-numbers nil}]
           ["clojure title=':no-line-numbers'" {:lang "clojure" :hl [] :line-numbers nil}]
           ["c++"                        {:lang "c++" :hl [] :line-numbers nil}]
           ["{3}"                        {:lang nil :hl [[3 3]] :line-numbers nil}]
           [""                           {:lang nil :hl [] :line-numbers nil}]
           [nil                          {:lang nil :hl [] :line-numbers nil}]
           ["js{5-3}"                    {:lang "js" :hl [[3 5]] :line-numbers nil}]
           ["js{a,0,2}"                  {:lang "js" :hl [] :line-numbers nil}]
           ["js{0,2}"                    {:lang "js" :hl [[2 2]] :line-numbers nil}]]]
    (is (= expected (hl/parse-info info)) (pr-str info)))
  (testing "a huge range is clipped, not materialized"
    (is (= [[1 100001]] (:hl (hl/parse-info "js{1-999999999}"))))))

(defn- render-block
  "One block rendered with highlighting off, without markdown's wrapper."
  [src]
  (str/replace (str (h/html (markdown/render src {}))) #"^<div>|</div>$" ""))

(deftest a-fence-info-never-leaks-into-a-class
  (testing "0.2.0 emitted class=\"language-js{1,3-5}\" and
            \"language-js:no-line-numbers\"; with highlighting off the markup
            is otherwise 0.2.0's, plus data-lang for the CSS label"
    (doseq [[info lang] [["js{1,3-5}" "js"] ["js {2}" "js"] ["js:line-numbers" "js"]
                         ["js:no-line-numbers" "js"] ["clojure title=\"x\" {1}" "clojure"]
                         ;; P4-C.1 item 10: `}` ends the language too
                         ["js}" "js"] ["js}{1}" "js"] ["js:}" "js"] ["js}:line-numbers" "js"]]]
      (let [html (render-block (str "```" info "\nx\n```\n"))]
        (is (= (str "<pre class=\"clogem-code language-" lang "\" data-lang=\"" lang "\">"
                    "<code class=\"language-" lang "\">x\n</code></pre>")
               html)
            info)))
    (testing "an empty info string and an indented block carry no language"
      (is (= "<pre class=\"clogem-code\"><code>x\n</code></pre>"
             (render-block "```\nx\n```\n")))
      (is (= "<pre class=\"clogem-code\"><code>x\n</code></pre>"
             (render-block "    x\n"))))))

;; ---------------------------------------------------------------------------
;; 3. The allow-list, line structure and escaping

(deftest the-lexer-list-is-an-allow-list-of-safe-arguments
  (let [{:keys [lexers styles] n :count} (hl/parse-list (slurp (fs/file fixtures "chroma-2.27.0-list.txt")))]
    (is (= 297 n) "the pre-flight's count for 2.27.0")
    (testing "names, aliases and *.ext patterns, case-insensitively"
      (is (= "JavaScript" (lexers "js") (lexers "javascript") (lexers "mjs")))
      (is (= "Clojure" (lexers "clojure") (lexers "clj") (lexers "edn")))
      (is (= "Bash" (lexers "bash") (lexers "sh") (lexers "shell")))
      (is (= "JSON" (lexers "json")))
      (is (= "plaintext" (lexers "text") (lexers "plaintext"))))
    (testing "a name that looks like a path is never passed: its safe alias is"
      (is (= "django" (lexers "django/jinja") (lexers "django")))
      (is (= "plpgsql" (lexers "pl/pgsql"))))
    (testing "every argument is safe — no /, \\ or ., no leading -"
      (is (every? hl/safe-arg? (vals lexers)))
      (doseq [bad ["../x" "a/b" "x.xml" "-l" "C:\\x" ""]]
        (is (not (hl/safe-arg? bad)) bad)
        (is (nil? (lexers (str/lower-case bad))) bad)))
    (is (set/subset? #{"github" "github-dark" "monokai"} styles))))

(deftest plain-lines-match-chromas-line-structure
  (is (= (str "<span class=\"line\"><span class=\"cl\">a &lt;b&gt; &amp; &#34;c&#34; &#39;d&#39;\n</span></span>"
              "<span class=\"line\"><span class=\"cl\">\n</span></span>"
              "<span class=\"line\"><span class=\"cl\">e</span></span>")
         (hl/plain-lines "a <b> & \"c\" 'd'\n\ne")))
  (is (= "" (hl/plain-lines "")))
  (is (= (hl/plain-lines "a\nb\n") (hl/plain-lines "a\r\nb\r\n")) "CRLF is normalized")
  (testing "decorate adds `hl` to exactly the highlighted lines"
    (let [[html n] (hl/decorate (hl/plain-lines "1\n2\n3\n4\n5\n6\n") [[1 1] [3 5]])]
      (is (= 6 n))
      (is (= [true false true true true false]
             (map #(= "line hl" %) (re-seq #"(?<=<span class=\")line(?: hl)?(?=\")" html))))
      (is (= (code-text html) "1\n2\n3\n4\n5\n6\n") "the text is untouched"))))

;; ---------------------------------------------------------------------------
;; 4. highlight.css: the transform, golden, and contrast

(defn- golden-css [] (slurp (fs/file fixtures "highlight.css")))

(defn- generated-css []
  (hl/css-from-rules {:light (hl/style-rules (slurp (fs/file fixtures "github.css")))
                      :dark  (hl/style-rules (slurp (fs/file fixtures "github-dark.css")))
                      :light-name "github" :dark-name "github-dark" :version "2.27.0"}))

(deftest the-style-transform-matches-its-golden-file
  (let [css (generated-css)]
    (is (= (golden-css) css)
        "regenerate test/fixtures/highlight/highlight.css only on purpose (see its header)")
    (testing "Chroma's structural rules are dropped; the theme owns them"
      (doseq [re [#"(?m)^\.bg" #"\.chroma \{" #"\.chroma \.(?:ln|lnt|lntd|lntable|lnlinks|hl|line|cl|w|err) "]]
        (is (not (re-find re css)) (str re))))
    (testing "a property one style lacks is `initial`, never `inherit`"
      (is (not (str/includes? css "inherit")))
      (is (str/includes? css "--hl-c-fs: initial;") "github has no italic comments; github-dark does"))
    (testing "every variable a token rule reads is set in every mode block"
      (let [used   (set (map second (re-seq #"var\((--hl-[A-Za-z0-9_-]+)\)" css)))
            blocks (re-seq #"(?s)(:root, \.theme-mode-light|\.theme-mode-dark, \.theme-mode-read|\.theme-mode-auto|html\[class\]) \{(.*?)\}" css)]
        (is (= 4 (count blocks)))
        (doseq [[_ sel body] blocks]
          (is (= used (set (map second (re-seq #"(--hl-[A-Za-z0-9_-]+):" body)))) sel))))
    (testing "the auto media block is exactly the dark block"
      (let [vars (fn [sel] (second (re-find (re-pattern (str "(?s)" (java.util.regex.Pattern/quote sel) " \\{(.*?)\\}")) css)))]
        (is (= (str/replace (vars ".theme-mode-dark, .theme-mode-read") #"\s+" " ")
               (str/replace (vars "  .theme-mode-auto") #"\s+" " ")))))))

(defn- luminance [hex]
  (let [h  (str/replace hex "#" "")
        ch #(let [c (/ (Integer/parseInt (subs h % (+ % 2)) 16) 255.0)]
              (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4)))]
    (+ (* 0.2126 (ch 0)) (* 0.7152 (ch 2)) (* 0.0722 (ch 4)))))

(defn contrast [a b]
  (let [[x y] (sort > [(luminance a) (luminance b)])]
    (/ (+ x 0.05) (+ y 0.05))))

(defn- css-block
  "{\"--name\" value} of the first rule whose selector is exactly `sel`."
  [css sel]
  (let [i (str/index-of css (str sel " {"))
        _ (assert i (str "no rule " sel))
        s (str/index-of css "{" i)
        e (str/index-of css "}" s)]
    (into {} (for [[_ k v] (re-seq #"(--[A-Za-z0-9-]+)\s*:\s*([^;]+);" (subs css s e))]
               [k (str/trim v)]))))

(defn token-minimums
  "{mode [ratio what]} — the lowest contrast in each mode of every token
  colour of highlight.css (its own background when the style gives it one,
  else --codeBg and --codeHlBg), --codeColor and --codeLineNumber, on
  theme.css's code surfaces for that mode."
  [hl-css]
  (let [theme (slurp theme-css)
        modes {:light [(css-block theme ":root, .theme-mode-light") (css-block hl-css ":root, .theme-mode-light")]
               :read  [(css-block theme ".theme-mode-read") (css-block hl-css ".theme-mode-dark, .theme-mode-read")]
               :dark  [(css-block theme ".theme-mode-dark") (css-block hl-css ".theme-mode-dark, .theme-mode-read")]
               :auto  [(css-block theme "  .theme-mode-auto") (css-block hl-css "  .theme-mode-auto")]
               :print [(css-block theme "  html[class]") (css-block hl-css "  html[class]")]}]
    (into (sorted-map)
          (for [[m [t v]] modes]
            [m (first
                (sort-by first
                         (concat
                          (for [[k fg] v
                                :when (re-matches #"--hl-[a-z0-9]+" k)
                                :let [fg (if (= "initial" fg) (t "--codeColor") fg)
                                      own-bg (get v (str k "-bg"))
                                      bgs (if (and own-bg (not= "initial" own-bg))
                                            [[own-bg (str k "-bg")]]
                                            [[(t "--codeBg") "--codeBg"] [(t "--codeHlBg") "--codeHlBg"]])]
                                [bg bg-name] bgs]
                            [(contrast fg bg) (str k " " fg " on " bg-name " " bg)])
                          (for [fg ["--codeColor" "--codeLineNumber"]
                                bg ["--codeBg" "--codeHlBg"]]
                            [(contrast (t fg) (t bg)) (str fg " on " bg)]))))]))))

(deftest every-token-reaches-wcag-aa-in-every-mode
  (testing "every token colour on its mode's --codeBg and on a highlighted
            line's --codeHlBg, plus the code text and the line numbers, at
            4.5:1 or better — read mode's code block is dark, so it takes the
            dark style"
    (let [mins (token-minimums (golden-css))]
      (doseq [[m [ratio what]] mins]
        (is (>= ratio 4.5) (format "%s: %s is %.2f:1" (name m) what ratio)))
      (println (str "highlight: contrast minimums — "
                    (str/join ", " (for [[m [r what]] mins] (format "%s %.2f (%s)" (name m) r what))))))))

;; ---------------------------------------------------------------------------
;; A build with the fake chroma

(deftest a-highlighted-build
  (with-site mixed-site
    (fn [dir out bin]
      (let [{:keys [error err]} (build! dir out)
            en   (page out "pages" "c0de01")
            zh   (page out "zh-Hans" "pages" "c0de01")
            home (page out)
            runs (fake/chroma-runs bin)]
        (is (nil? error) (str error))
        (testing "highlight.css: the golden bytes, linked right after theme.css, fingerprinted"
          (is (= (golden-css) (slurp (fs/file out "clogem" "css" "highlight.css"))))
          (is (re-find #"<link href=\"/clogem/css/theme\.css\?v=[0-9a-f]{8}\" rel=\"stylesheet\" /><link href=\"/clogem/css/highlight\.css\?v=[0-9a-f]{8}\" rel=\"stylesheet\" />" en)))
        (testing "one Chroma process per lexer, files named relatively, in a temp dir since deleted"
          (is (= #{"--lexer=Bash" "--lexer=Clojure" "--lexer=JSON" "--lexer=JavaScript"}
                 (set (map #(first (str/split % #" ")) runs))))
          (is (= 4 (count runs)) (pr-str runs))
          (is (every? #(re-find #"--html --html-only( \d{4}\.txt)+$" %) runs) (pr-str runs))
          (let [cwds (str/split-lines (slurp (fs/file (fs/parent bin) "cwd.txt")))]
            (is (every? #(str/includes? % "clogem-chroma") cwds) (pr-str cwds))
            (is (not-any? fs/exists? cwds) "every temp directory is removed"))
          (is (= {:list 1 :styles 2 :runs 4} (select-keys (hl/process-stats) [:list :styles :runs]))))
        (testing "no class ever holds {, } or :, and there is never a class=\"ln\""
          (doseq [html [en zh home] c (class-attrs html)]
            (is (not (re-find #"[{}:]" c)) c))
          (is (not (str/includes? en "class=\"ln\""))))
        (testing "code cannot escape its block"
          (is (not (str/includes? en "<script>alert")))
          (is (= 2 (count (re-seq #"&lt;/code&gt;&lt;/pre&gt;&lt;script&gt;alert\([12]\)" en)))))
        (let [ps (vec (pres en))
              open (fn [i] (first (nth ps i)))
              lines (fn [i] (re-seq #"<span class=\"line(?: hl)?\">" (second (nth ps i))))]
          (is (= 13 (count ps)))
          (testing "Chroma's tokens (the fake marks `defn`)"
            (is (str/includes? (second (nth ps 0)) "<span class=\"k\">defn</span>")))
          (testing "line numbers by default, per-fence overrides, and the digit width"
            (is (= "<pre class=\"clogem-code chroma language-clojure line-numbers ln-w1\" data-lang=\"clojure\">" (open 0)))
            (is (= "<pre class=\"clogem-code chroma language-bash\" data-lang=\"bash\">" (open 2)))
            (is (= "<pre class=\"clogem-code chroma language-NoSuchLang\" data-lang=\"NoSuchLang\">" (open 5))))
          (testing "{1,3-5} highlights lines 1, 3, 4 and 5"
            (is (= ["<span class=\"line hl\">" "<span class=\"line\">" "<span class=\"line hl\">"
                    "<span class=\"line hl\">" "<span class=\"line hl\">" "<span class=\"line\">"]
                   (lines 1))))
          (testing "extra attributes are ignored; their {9} is not a range"
            (is (= ["<span class=\"line hl\">" "<span class=\"line\">"] (lines 10))))
          (testing "unknown, missing and plaintext languages: the same line structure, no process"
            (doseq [i [4 6 7 12]]
              (is (= 1 (count (lines i))) (open i))))
          (testing "every block's text is exactly its source"
            (is (= ["(defn f [x]\n  ;; 注释 தமிழ்\n  (inc x))\n"
                    "const a = 1;\nconst b = 2;\nconst c = 3;\nconst d = 4;\nconst e = 5;\nconst f = 6;\n"
                    "echo hi\n" "{\"a\": 1}\n" "mystery\n" "mystery two\n" "no language\n" "plain text\n"
                    "let s = \"</code></pre><script>alert(1)</script>\";\n"
                    "</code></pre><script>alert(2)</script>\n" "(defn g [] 1)\n(g)\n" "" "indented code\n"]
                   (map (comp code-text second) ps)))))
        (testing "an unknown language warns once per build, naming it and the file"
          (is (= 1 (count (re-seq #"unknown code language" err))) err)
          (is (re-find #"01\.Guide/01\.code\.md: unknown code language \"nosuchlang\"" err) err))
        (testing "the copy button: js/code.js and its strings, on pages with code only"
          (is (fs/exists? (fs/path out "clogem" "js" "code.js")))
          (is (re-find #"<script defer=\"defer\" src=\"/clogem/js/code\.js\?v=[0-9a-f]{8}\"></script>" en))
          (is (str/includes? en "{\"copy\":\"Copy\",\"copied\":\"Copied\",\"icons\":{\"copy\":\"/clogem/icons.svg?v="))
          (is (str/includes? zh "{\"copy\":\"复制\",\"copied\":\"已复制\""))
          (is (not (str/includes? (page out "pages" "c0de02") "code.js")))
          (is (not (str/includes? home "code.js")))
          (is (not (str/includes? en "clogem-copy")) "no button in the HTML: JS adds it"))
        (testing "a second build in the same process (a dev rebuild) runs no highlighting process"
          (let [before (count (fake/chroma-runs bin))]
            (is (nil? (:error (build! dir out))))
            (is (= before (count (fake/chroma-runs bin))))
            (is (= (golden-css) (slurp (fs/file out "clogem" "css" "highlight.css"))))))))))

(deftest a-new-binary-misses-the-cache
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(defn a [])\n```\n")}
    (fn [dir out bin]
      (is (nil? (:error (build! dir out))))
      (is (= 1 (count (fake/chroma-runs bin))))
      (spit (fs/file bin) "\n# another binary\n" :append true)
      (is (nil? (:error (build! dir out))))
      (is (= 2 (count (fake/chroma-runs bin))) "the binary's sha256 is part of the key"))))

(deftest many-blocks-are-batched-at-150-files-per-process
  (with-site {"01.Guide/01.a.md"
              (article "A" "/pages/aaaaa1/"
                       (str/join "\n" (for [i (range 320)] (str "```clojure\n(def x" i " " i ")\n```\n"))))}
    (fn [dir out bin]
      (is (nil? (:error (build! dir out))))
      (let [runs (fake/chroma-runs bin)]
        (is (= [150 150 20] (sort > (map #(count (re-seq #"\d{4}\.txt" %)) runs))))
        (is (= 320 (count (pres (page out "pages" "aaaaa1")))))))))

(deftest a-site-without-code-never-runs-chroma
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "# A\n\nProse only, `inline` code.\n")}
    (fn [dir out bin]
      (is (nil? (:error (build! dir out))))
      (is (= [] (fake/chroma-calls bin)))
      (is (not (fs/exists? (fs/path out "clogem" "css" "highlight.css"))))
      (is (not (str/includes? (page out "pages" "aaaaa1") "highlight.css"))))))

(deftest quoted-code-is-code
  (testing "P4-C.1 item 2: a site whose only code is inside a blockquote is highlighted"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "# A\n\n> Run:\n>\n> ```bash\n> echo hi\n> ```\n")}
      (fn [dir out bin]
        (is (nil? (:error (build! dir out))))
        (is (= 1 (count (fake/chroma-runs bin))))
        (is (fs/exists? (fs/path out "clogem" "css" "highlight.css")))
        (is (str/includes? (page out "pages" "aaaaa1") "<pre class=\"clogem-code chroma language-bash"))))))

(deftest nested-lists-are-not-code
  (testing "P4-C.1 item 7: four-space nested lists and list continuation
            paragraphs are not code: no Chroma, no highlight.css — even when
            no Chroma could be had at all (an offline build)"
    (with-site {"01.Guide/01.a.md"
                (article "A" "/pages/aaaaa1/"
                         (str "# A\n\n- Fruit\n    - apples\n    - pears\n\n"
                              "1. Step one\n\n    More about step one.\n\n"
                              "2. Step two\n        deeper text\n"))}
      (fn [dir out bin]
        (is (nil? (:error (build! dir out))))
        (is (= [] (fake/chroma-calls bin)))
        (is (not (fs/exists? (fs/path out "clogem" "css" "highlight.css"))))
        (is (not (str/includes? (page out "pages" "aaaaa1") "highlight.css")))
        (is (not (str/includes? (page out "pages" "aaaaa1") "<pre")))
        (binding [tools/*env* {"CLOGEM_CHROMA" (str bin "-missing")}]
          (let [{:keys [error]} (build! dir out)]
            (is (nil? error) "nothing to highlight, so nothing to fetch")))))))

(deftest code-only-in-site-files-is-highlighted-and-warned-deterministically
  (testing "P4-C.1 item 5: the pre-pass covers index*.md and @pages/*; an
            unknown language is warned once, naming the first file in path
            order, whatever order the pages render in"
    (with-site {"index.md" "---\nhome: true\n---\n\n# Home\n\n```clojure\n(home)\n```\n\n```zzlang\nh\n```\n"
                "@pages/archivesPage.md" (str "---\narchivesPage: true\ntitle: Archives\npermalink: /archives/\narticle: false\n---\n\n"
                                              "```zzlang\na\n```\n")
                "01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "# A\n\nProse.\n")}
      (fn [dir out bin]
        (doseq [n [1 8]]
          (hl/clear-cache!)
          (let [{:keys [error err]} (with-redefs [clogem.render/jobs (constantly n)] (build! dir out))
                warned (re-seq #"warning: (\S+): unknown code language \"zzlang\"" err)]
            (is (nil? error) (str error))
            (is (= [["@pages/archivesPage.md"]] (map rest warned)) (str "jobs " n ": " err))
            (is (str/includes? (page out) "<pre class=\"clogem-code chroma language-clojure"))))
        (testing "the site file's block was highlighted in the batched pre-pass: one run per build"
          (is (= 2 (count (filter #(str/starts-with? % "--lexer=Clojure") (fake/chroma-runs bin)))))))))
  (testing "warn-unknown! is atomic: of many threads, one warns"
    (let [session {:lexers {} :warned (atom #{}) :version "x" :lexer-count 0}
          [_ ds] (diag/collecting
                  (let [start (promise)
                        fs    (doall (for [i (range 16)]
                                       (future @start (hl/warn-unknown! session (str "f" i) "zzlang"))))]
                    (deliver start true)
                    (run! deref fs)))]
      (is (= 1 (count ds))))))

(deftest a-parser-error-names-the-page
  (testing "P4-C.1 item 3: a StackOverflowError while parsing (an Error, not
            an Exception) fails the build naming the page, as Task A requires"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "# A\n\nBOOM\n\n```clojure\n(a)\n```\n")
                "01.Guide/02.b.md" (article "B" "/pages/bbbbb2/" "# B\n\n```clojure\n(b)\n```\n")}
      (fn [dir out _]
        (let [parse markdown/parse
              {:keys [error]} (with-redefs [markdown/parse (fn [src & more]
                                                             (if (str/includes? (str src) "BOOM")
                                                               ;; a real one: bb cannot construct it
                                                               ((fn deep [n] (inc (deep n))) 0)
                                                               (apply parse src more)))]
                                (build! dir out))]
          (is (instance? clojure.lang.ExceptionInfo error) (str (class error)))
          (is (re-find #"could not render /pages/aaaaa1/: java.lang.StackOverflowError" (str (ex-message error)))
              (str (ex-message error)))
          (is (= 1 (:babashka/exit (ex-data error))))
          (is (not (fs/exists? out))))))))

(deftest a-bad-clogem-jobs-is-warned-once
  (testing "P4-C.1 item 4: an invalid CLOGEM_JOBS is one warning per build, highlighting on"
    (with-site mixed-site
      (fn [dir _ bin]
        (let [{:keys [err exit]} (p/shell {:dir (str dir) :out :string :err :string :continue true
                                           :extra-env {"CLOGEM_CHROMA" bin "CLOGEM_JOBS" "lots"}}
                                          (bb-exe) "--config" (str (fs/absolutize "bb.edn"))
                                          "build" "--no-write" "--no-search" "--out" (str (fs/path dir "dist-jobs")))]
          (is (zero? exit) err)
          (is (fs/exists? (fs/path dir "dist-jobs" "clogem" "css" "highlight.css")))
          (is (= 1 (count (re-seq #"CLOGEM_JOBS is \"lots\"" err))) err))))))

(deftest feed-summaries-decode-what-chroma-escapes
  (testing "P4-C.1 item 6: an excerpt whose code holds \" and ' gives the Atom
            summary main gives, highlighted or not"
    (with-site {"01.Guide/01.a.md"
                (article "A" "/pages/aaaaa1/"
                         "# A\n\nIntro.\n\n```js\nconst msg = \"hi\";\nconst c = 'x';\n```\n\n<!-- more -->\n\nRest.\n")}
      (fn [dir out _]
        (let [summary (fn [opts]
                        (is (nil? (:error (build! dir out opts))))
                        (re-find #"<summary[^>]*>[^<]*</summary>" (slurp (fs/file out "feed.xml"))))
              ;; main (4d795e8) emits exactly this
              main "<summary type=\"text\">Intro. const msg = \"hi\"; const c = 'x';</summary>"]
          (is (= main (summary nil)) "highlighted")
          (is (= main (summary {:no-highlight true})) "--no-highlight")))))
  (testing "numeric character references decode; an escaped one stays text"
    (is (= "\"a' ' é 😀 &#34; &#0; &#xD800;"
           (#'clogem.render/strip-tags "<code>&#34;a&#39; &#x27; &#233; &#x1F600; &amp;#34; &#0; &#xD800;</code>")))))

(deftest the-copy-button-flag-never-walks-the-sidebar
  (testing "P4-C.1 item 8: whether a page has code comes from its own content
            (an article's AST, an index page's main column), never from a
            walk of the whole page and its sidebar; the script still lands on
            exactly the pages with code"
    (with-site (merge mixed-site
                      {"01.Guide/03.quoted.md" (article "Q" "/pages/c0de03/" "# Q\n\n> ```js\n> q()\n> ```\n")
                       "@pages/archivesPage.md" (str "---\narchivesPage: true\ntitle: Archives\npermalink: /archives/\narticle: false\n---\n\n"
                                                     "```js\narchived()\n```\n")})
      (fn [dir out _]
        (let [seen (atom [])
              has-code? @#'clogem.theme.layout/has-code?
              sidebar? (fn [x] (some #(and (vector? %) (str/starts-with? (str (first %)) ":aside.clogem-sidebar"))
                                     (tree-seq sequential? seq x)))]
          (with-redefs [clogem.theme.layout/has-code? (fn [x] (swap! seen conj (boolean (sidebar? x))) (has-code? x))]
            (doseq [opts [nil {:no-highlight true}]]
              (reset! seen [])
              (is (nil? (:error (build! dir out opts))))
              (is (seq @seen))
              (is (not-any? true? @seen) (str (pr-str opts) ": a whole page with its sidebar was walked"))
              (doseq [[parts code?] [[["pages" "c0de01"] true] [["zh-Hans" "pages" "c0de01"] true]
                                     [["pages" "c0de02"] false] [["pages" "c0de03"] true]
                                     [["archives"] true] [["tags"] false] [[] false]]]
                (is (= code? (str/includes? (apply page out parts) "js/code.js"))
                    (str (pr-str opts) " " parts))))))))))

(defn- doctor!
  "`bb doctor` in-process: {:result :error :err}."
  [dir]
  (let [err (java.io.StringWriter.)
        r   (binding [*err* err]
              (try (let [o (atom nil)]
                     (with-out-str (reset! o (cli/doctor {:site-dir (str dir) :config-file "site.edn"})))
                     {:result @o})
                   (catch clojure.lang.ExceptionInfo e {:error e})))]
    (assoc r :err (str err))))

(deftest doctor-checks-code-languages-with-a-chroma-it-already-has
  (testing "P4-C.1 item 16"
    (with-site mixed-site
      (fn [dir _ bin]
        (testing "with a binary: the unknown languages build warns about, at the same file, once each"
          (let [{:keys [result err]} (doctor! dir)
                unknown (filter #(str/includes? (:message %) "unknown code language") (:warnings result))]
            (is (= [["01.Guide/01.code.md" "unknown code language \"nosuchlang\"; the block is shown as plain text."]]
                   (map (juxt :path :message) unknown))
                err)
            (is (not (str/includes? err "not checked")))))
        (testing "no binary anywhere: said once, as info, and nothing is downloaded"
          (let [cache (fs/create-temp-dir {:prefix "clogem-empty-cache"})]
            (try
              (spit (fs/file dir "site.edn")
                    (pr-str (assoc-in (read-string (slurp (fs/file dir "site.edn")))
                                      [:tools :chroma :url] "http://127.0.0.1:9/{{version}}/{{platform}}")))
              (binding [tools/*env* {"CLOGEM_TOOLS_DIR" (str cache)}]
                (let [{:keys [result error err]} (doctor! dir)]
                  (is (nil? error) (str error))
                  (is (= 1 (count (re-seq #"info: code languages not checked" err))) err)
                  (is (not-any? #(str/includes? (:message %) "code language") (:warnings result)))
                  (is (not (str/includes? err "fetching")) err)
                  (is (empty? (fs/list-dir cache)) "nothing fetched into the cache")))
              (finally (fs/delete-tree cache)))))
        (testing "an unusable CLOGEM_CHROMA is the error build fails with"
          (let [noexec (fs/path dir ".fake-bin" "chroma-noexec")]
            (fs/copy bin noexec)
            ;; a binary of its own: the listing is cached per sha256
            (spit (fs/file noexec) "\n# noexec\n" :append true)
            (fs/set-posix-file-permissions noexec "rw-r--r--")
            (binding [tools/*env* {"CLOGEM_CHROMA" (str noexec)}]
              (let [{:keys [error err]} (doctor! dir)]
                (is (= 1 (:babashka/exit (ex-data error))))
                (is (re-find #"error: highlight: could not run .*chroma-noexec" err) err)))
            (binding [tools/*env* {"CLOGEM_CHROMA" (str bin "-missing")}]
              (let [{:keys [error err]} (doctor! dir)]
                (is (some? error))
                (is (re-find #"error: highlight: Chroma binary .* does not exist" err) err)))))))))

(deftest no-highlight-renders-0-2-0-markup-and-runs-nothing
  (doseq [[how opts site-edn] [["--no-highlight" {:no-highlight true} nil]
                               [":provider :none" nil {:highlight {:provider :none}}]]]
    (with-site mixed-site
      (fn [dir out bin]
        (let [{:keys [error err]} (build! dir out opts)
              en (page out "pages" "c0de01")]
          (is (nil? error) how)
          (is (= [] (fake/chroma-calls bin)) how)
          (is (not (fs/exists? (fs/path out "clogem" "css" "highlight.css"))) how)
          (is (not (str/includes? en "highlight.css")) how)
          (is (not (str/includes? en "class=\"line")) how)
          (is (str/includes? en "<pre class=\"clogem-code language-js\" data-lang=\"js\"><code class=\"language-js\">const a = 1;") how)
          (is (not (str/includes? err "unknown code language")) how)
          (testing "the copy button does not depend on the provider"
            (is (str/includes? en "js/code.js") how))))
      {:site-edn site-edn})))

(deftest copy-button-false-ships-no-script
  (with-site mixed-site
    (fn [dir out _]
      (is (nil? (:error (build! dir out))))
      (is (not (fs/exists? (fs/path out "clogem" "js" "code.js"))))
      (is (not (str/includes? (page out "pages" "c0de01") "code.js")))
      (is (not (str/includes? (page out "pages" "c0de01") "clogem-code-data"))))
    {:site-edn {:highlight {:copy-button false}}}))

(deftest line-numbers-false-is-the-site-default-a-fence-can-override
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n\n```clojure:line-numbers\n(b)\n```\n")}
    (fn [dir out _]
      (is (nil? (:error (build! dir out))))
      (let [[a b] (map first (pres (page out "pages" "aaaaa1")))]
        (is (not (str/includes? a "line-numbers")))
        (is (str/includes? b "line-numbers ln-w1"))))
    {:site-edn {:highlight {:line-numbers false}}}))

;; ---------------------------------------------------------------------------
;; 1. Failure modes

(deftest a-failed-chroma-run-fails-the-build-and-writes-nothing
  (doseq [[what fake-opts re] [["a failing run" {:exit 3} #"chroma --lexer=Clojure failed \(exit 3\): fake chroma: boom"]
                               ["a run whose output does not split" {:split-wrong true}
                                #"does not split into 1 code blocks"]]]
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
      (fn [dir out _]
        (let [{:keys [error]} (build! dir out)
              msg (str (ex-message error))]
          (is (= 1 (:babashka/exit (ex-data error))) what)
          (is (re-find re msg) msg)
          (is (str/includes? msg "--no-highlight") msg)
          (is (str/includes? msg ":highlight {:provider :none}") msg)
          (is (not (fs/exists? out)) (str what ": nothing is written"))))
      {:fake-opts fake-opts})))

(deftest a-failed-style-run-fails-the-build-and-only-warns-in-dev
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
    (fn [dir out _]
      (let [{:keys [error]} (build! dir out)
            msg (str (ex-message error))]
        (is (= 1 (:babashka/exit (ex-data error))))
        (is (re-find #"--style=github failed \(exit 4\): fake chroma: no styles" msg) msg)
        (is (str/includes? msg "--no-highlight") msg)
        (is (not (fs/exists? out))))
      (let [{:keys [error err]} (build! dir out {:clogem/dev-loop? true})]
        (is (nil? error) (str error))
        (is (= 1 (count (re-seq #"fake chroma: no styles" err))) err)
        (is (str/includes? (page out "pages" "aaaaa1") "<code class=\"language-clojure\">(a)\n</code>"))))
    {:fake-opts {:styles-exit 4}}))

(deftest a-missing-binary-fails-the-build-naming-no-highlight
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
    (fn [dir out bin]
      (binding [tools/*env* {"CLOGEM_CHROMA" (str bin "-missing")}]
        (let [{:keys [error]} (build! dir out)
              msg (str (ex-message error))]
          (is (= 1 (:babashka/exit (ex-data error))))
          (is (re-find #"clogem-press: highlight: Chroma binary .* does not exist" msg) msg)
          (is (str/includes? msg "--no-highlight") msg)
          (is (not (fs/exists? out))))))))

(deftest no-class-or-data-lang-carries-a-brace-or-colon
  (testing "P4-C.1 item 10: `{`, `}` and `:` never reach an attribute, highlighted or not"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/"
                                            "```js}\na\n```\n\n```js{1}\nb\n```\n\n```js:\nc\n```\n\n```js}:{2}\nd\n```\n")}
      (fn [dir out _]
        (doseq [opts [nil {:no-highlight true}]]
          (is (nil? (:error (build! dir out opts))))
          (let [html (page out "pages" "aaaaa1")]
            (is (= 4 (count (pres html))) (pr-str opts))
            (is (not-any? #(re-find #"[{}:]" %) (class-attrs html)) (pr-str opts))
            (is (not-any? #(re-find #"[{}:]" %) (map second (re-seq #"data-lang=\"([^\"]*)\"" html))) (pr-str opts))))))))

(deftest an-unusable-binary-is-a-chroma-failure-not-a-stack-trace
  (testing "P4-C.1 item 1: a CLOGEM_CHROMA without the exec bit makes the process
            API throw an IOException; build names --no-highlight, dev warns once"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
      (fn [dir out bin]
        (let [noexec (fs/path dir ".fake-bin" "chroma-noexec")]
          (fs/copy bin noexec)
          (fs/set-posix-file-permissions noexec "rw-r--r--")
          (binding [tools/*env* {"CLOGEM_CHROMA" (str noexec)}]
            (let [{:keys [error]} (build! dir out)
                  msg (str (ex-message error))]
              (is (instance? clojure.lang.ExceptionInfo error) "not a raw IOException")
              (is (= 1 (:babashka/exit (ex-data error))))
              (is (re-find #"clogem-press: highlight: could not run .*chroma-noexec" msg) msg)
              (is (str/includes? msg "--no-highlight") msg)
              (is (not (fs/exists? out))))
            (let [r1 (build! dir out {:clogem/dev-loop? true})
                  r2 (build! dir out {:clogem/dev-loop? true})]
              (is (nil? (:error r1)) (str (:error r1)))
              (is (nil? (:error r2)) (str (:error r2)))
              (is (= 1 (count (re-seq #"could not run" (:err r1)))) (:err r1))
              (is (not (str/includes? (:err r2) "could not run")) "warned once")
              (is (str/includes? (page out "pages" "aaaaa1")
                                 "<code class=\"language-clojure\">(a)\n</code>")))))))))

(deftest a-dev-rebuild-warns-once-and-renders-plain-code
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
    (fn [dir out bin]
      (testing "a missing binary"
        (binding [tools/*env* {"CLOGEM_CHROMA" (str bin "-missing")}]
          (let [r1 (build! dir out {:clogem/dev-loop? true})
                r2 (build! dir out {:clogem/dev-loop? true})
                html (page out "pages" "aaaaa1")]
            (is (nil? (:error r1)) (str (:error r1)))
            (is (= 1 (count (re-seq #"does not exist" (:err r1)))) (:err r1))
            (is (re-find #"Code renders without highlighting until `bb dev` is restarted" (:err r1)))
            (is (not (str/includes? (:err r2) "does not exist")) "warned once, not on every rebuild")
            (is (str/includes? html "<pre class=\"clogem-code language-clojure\" data-lang=\"clojure\"><code class=\"language-clojure\">(a)\n</code></pre>"))
            (is (not (fs/exists? (fs/path out "clogem" "css" "highlight.css")))))))))
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
    (fn [dir out _]
      (testing "a failing run"
        (let [r1 (build! dir out {:clogem/dev-loop? true})]
          (is (nil? (:error r1)) (str (:error r1)))
          (is (= 1 (count (re-seq #"fake chroma: boom" (:err r1)))) (:err r1))
          (is (str/includes? (page out "pages" "aaaaa1") "<code class=\"language-clojure\">(a)\n</code>")))))
    {:fake-opts {:exit 2}}))

(deftest a-failed-dev-run-is-not-retried-on-the-next-rebuild
  (testing "P4-C.1 item 9: --list and the styles succeed, a highlighting run
            fails; later rebuilds run nothing until `bb dev` is restarted"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
      (fn [dir out bin]
        (let [r1    (build! dir out {:clogem/dev-loop? true})
              calls (count (fake/chroma-calls bin))
              runs  (count (fake/chroma-runs bin))
              r2    (build! dir out {:clogem/dev-loop? true})
              r3    (build! dir out {:clogem/dev-loop? true})]
          (is (pos? runs))
          (is (every? (comp nil? :error) [r1 r2 r3]))
          (is (= 1 (count (re-seq #"fake chroma: boom" (str (:err r1) (:err r2) (:err r3))))))
          (is (= runs (count (fake/chroma-runs bin))) "the failing run is not retried")
          (is (= calls (count (fake/chroma-calls bin))) "nor is anything else")
          (is (str/includes? (page out "pages" "aaaaa1") "<code class=\"language-clojure\">(a)\n</code>"))
          (testing "a restart (the failure record cleared) tries again"
            (reset-dev-failures!)
            (build! dir out {:clogem/dev-loop? true})
            (is (< runs (count (fake/chroma-runs bin)))))))
      {:fake-opts {:exit 2}})))

(deftest what-dev-passes-to-build-makes-a-chroma-failure-a-warning
  (testing "P4-C.1 item 11: the options `dev!` really passes to `build` (captured,
            then built with a missing binary) are soft; either dev flag is"
    (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
      (fn [dir out bin]
        (let [seen  (atom [])
              build cli/build
              dev!  clogem.dev/dev!]
          (binding [tools/*env* {"CLOGEM_CHROMA" (str bin "-missing")}]
            (with-redefs [cli/build (fn [o] (swap! seen conj o) (build o))
                          clogem.dev/poll-watch! (fn [& _] nil)
                          org.httpkit.server/run-server (fn [& _] (fn [& _] nil))]
              (let [err (java.io.StringWriter.)
                    o   (binding [*err* err]
                          (with-out-str (dev! {:site-dir (str dir) :config-file "site.edn"
                                               :out (str out) :poll true :port 0})))]
                (is (= 1 (count @seen)))
                (is (not (str/includes? o "build failed")) o)
                (is (str/includes? o "rebuilt in") o)
                (is (str/includes? (str err) "does not exist") (str err))))
            (reset-dev-failures!)
            (let [{:keys [error err]} (build! dir out (dissoc (first @seen) :site-dir :out))]
              (is (nil? error) (str error))
              (is (str/includes? err "Code renders without highlighting")))
            (doseq [flag [:clogem/dev? :clogem/dev-loop?]]
              (reset-dev-failures!)
              (is (nil? (:error (build! dir out {flag true}))) (str flag))
              (is (hl/soft? {flag true}) (str flag)))
            (is (not (hl/soft? {})))))))))

;; ---------------------------------------------------------------------------
;; Config

(deftest highlight-config-is-validated-as-warnings
  (is (= {:provider :chroma :line-numbers true :copy-button true :style "github" :dark-style "github-dark"}
         config/highlight-defaults)
      "on by default (the test runner turns the default off for other fixtures)")
  (doseq [[edn re path expected]
          ;; P4-C.1 item 12: the real shipped default, not `config/defaults`,
          ;; which the test runner switches to :none
          [[{:highlight {:provider :prism}} #":highlight :provider is :prism, but it must be :chroma or :none; using :chroma\." [:highlight :provider] :chroma]
           [{:highlight {:line-numbers "yes"}} #":highlight :line-numbers is \"yes\", but it must be true or false" [:highlight :line-numbers] true]
           [{:highlight {:copy-button 1}} #":highlight :copy-button is 1" [:highlight :copy-button] true]
           [{:highlight {:style "../evil.xml"}} #":highlight :style is \"../evil.xml\", which is not a Chroma style name" [:highlight :style] "github"]
           [{:highlight {:dark-style :dracula}} #":highlight :dark-style is :dracula" [:highlight :dark-style] "github-dark"]
           [{:highlight "github"} #":highlight is \"github\", but it must be a map" [:highlight :style] "github"]]]
    (let [dir (fs/create-temp-dir {:prefix "clogem-hlcfg"})]
      (try
        (spit (fs/file dir "site.edn") (pr-str edn))
        (let [[cfg ds] (diag/collecting (config/load-config (str dir)))]
          (is (empty? (diag/errors ds)) (pr-str edn (map :message ds)))
          (is (= 1 (count (diag/warnings ds))) (pr-str edn (map :message ds)))
          (is (re-find re (str (:message (first (diag/warnings ds))))) (pr-str edn))
          (is (= expected (get-in cfg path)) (pr-str edn)))
        (finally (fs/delete-tree dir)))))
  (testing "every :highlight key is known: no unknown-key warning"
    (let [dir (fs/create-temp-dir {:prefix "clogem-hlcfg"})]
      (try
        (spit (fs/file dir "site.edn") (pr-str {:highlight config/highlight-defaults}))
        (let [[_ ds] (diag/collecting (config/load-config (str dir)))]
          (is (empty? ds) (pr-str (map :message ds))))
        (finally (fs/delete-tree dir))))))

(deftest a-style-chroma-lacks-warns-and-falls-back
  (with-site {"01.Guide/01.a.md" (article "A" "/pages/aaaaa1/" "```clojure\n(a)\n```\n")}
    (fn [dir out bin]
      (let [{:keys [error err]} (build! dir out)]
        (is (nil? error))
        (is (re-find #":highlight :style \"nosuch\" is not a Chroma style; using \"github\"" err) err)
        (is (some #(= "--html --html-styles --style=github" %) (fake/chroma-calls bin)))
        (is (not-any? #(str/includes? % "nosuch") (fake/chroma-calls bin)))
        (is (= (golden-css) (slurp (fs/file out "clogem" "css" "highlight.css"))))))
    {:site-edn {:highlight {:style "nosuch"}}}))

;; ---------------------------------------------------------------------------
;; Determinism across the render pool (D-P4-11)

(defn- bb-exe [] (or (-> (java.lang.ProcessHandle/current) .info .command (.orElse nil)) "bb"))

(defn- tree-bytes [out]
  (into (sorted-map)
        (for [p (fs/glob out "**") :when (fs/regular-file? p)]
          [(str (fs/relativize out p)) (vec (fs/read-all-bytes p))])))

(deftest one-job-and-many-give-identical-output
  (with-site mixed-site
    (fn [dir _ bin]
      (let [run (fn [out jobs]
                  (p/shell {:dir (str dir) :out :string :err :string
                            :extra-env (cond-> {"CLOGEM_CHROMA" bin} jobs (assoc "CLOGEM_JOBS" jobs))}
                           (bb-exe) "--config" (str (fs/absolutize "bb.edn"))
                           "build" "--no-write" "--out" (str out)))
            a (fs/path dir "dist-1") b (fs/path dir "dist-n")]
        (run a "1")
        (run b nil)
        (is (= (keys (tree-bytes a)) (keys (tree-bytes b))))
        (is (= (tree-bytes a) (tree-bytes b)) "byte-identical")
        (is (fs/exists? (fs/path a "clogem" "css" "highlight.css")) "highlighting was on (a subprocess default)")))))

;; ---------------------------------------------------------------------------
;; The real binary (CI sets CLOGEM_CHROMA)

(defn- demo-fences
  "Every code block's source in the demo's article variants."
  [model]
  (set (for [[_ g] (:articles model) [_ v] (:variants g)
             node (hl/code-nodes (markdown/parse (:body v) {}))]
         (hl/node-text node))))

(deftest the-real-chroma-highlights-the-demo
  (if-let [real (System/getenv "CLOGEM_CHROMA")]
    (let [out (fs/create-temp-dir {:prefix "clogem-real-chroma"})]
      (try
        (hl/clear-cache!)
        (hl/reset-stats!)
        (let [[cfg _] (diag/collecting
                       (config/load-config demo nil {:build {:out (str out)}
                                                     :content {:write-front-matter false}
                                                     :search {:provider :none}
                                                     :highlight {:provider :chroma}}))
              [model _] (diag/collecting (cli/analyse cfg))
              t0 (System/nanoTime)
              [_ ds] (binding [tools/*env* {"CLOGEM_CHROMA" real}]
                       (diag/collecting ((requiring-resolve 'clogem.render/build!) cfg model)))
              ms (/ (- (System/nanoTime) t0) 1e6)
              fences (demo-fences model)
              htmls  (for [p (fs/glob out "**.html")] (slurp (fs/file p)))
              blocks (mapcat pres htmls)
              stats  (hl/process-stats)
              session (binding [tools/*env* {"CLOGEM_CHROMA" real}] (hl/session (assoc cfg :clogem/dev-loop? false) true))
              by-lexer (frequencies (keep #(hl/lexer-for session (:lang (hl/parse-info (:info %))))
                                          (for [[_ g] (:articles model) [_ v] (:variants g)
                                                n (hl/code-nodes (markdown/parse (:body v) {}))] n)))
              chunks (reduce + (map #(long (Math/ceil (/ % 150.0))) (vals by-lexer)))]
          (is (empty? (diag/errors ds)))
          (is (seq blocks))
          (testing "every block's stripped, unescaped text is a fence's source, and every fence is shown"
            (doseq [[open inner] blocks]
              (is (contains? fences (code-text inner)) open))
            (is (= fences (set (map (comp code-text second) blocks)))))
          (testing "Clojure blocks carry Chroma's keyword tokens"
            (doseq [[open inner] blocks :when (str/includes? open "language-clojure")]
              (is (str/includes? inner "class=\"k") open)))
          (testing "no Chroma line number is ever text"
            (doseq [html htmls] (is (not (str/includes? html "class=\"ln\"")))))
          (testing "processes: at most one per lexer per chunk (here, one per lexer)"
            (is (<= (:runs stats) (+ (count by-lexer) chunks)) (pr-str stats by-lexer))
            (is (= (count by-lexer) (:runs stats)) (pr-str stats by-lexer)))
          (testing "the real styles give exactly the golden highlight.css"
            (is (= (golden-css) (slurp (fs/file out "clogem" "css" "highlight.css")))))
          (println (format "the-real-chroma-highlights-the-demo: %d blocks, %d lexers, %d runs, built in %.0f ms"
                           (count blocks) (count by-lexer) (:runs stats) ms)))
        (finally (fs/delete-tree out))))
    (do (is (nil? (System/getenv "GITHUB_ACTIONS"))
            "CI must set CLOGEM_CHROMA, so this test always runs there")
        (println "the-real-chroma-highlights-the-demo: skipped — CLOGEM_CHROMA is not set"))))
