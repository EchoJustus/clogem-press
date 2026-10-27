;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.modules-test
  "Phase 4 Task E1b (DESIGN.md §11.3 item 13, D-P4-13): htmlModules — the
  seven slots, the two show-modes and `:head-extra` — from config to
  markup, css/identity.css and js/window.js. What needs a browser is in
  test/browser/modules.test.mjs."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.search]))

(defn- load-site
  "Load `edn` as a site.edn from an empty temp dir: [cfg diagnostics]."
  [edn]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1b"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir)))
         (finally (fs/delete-tree dir)))))

(defn- modules-of
  "[the repaired :html-modules, its warnings as [message hint]], asserting
  no error."
  [hm]
  (let [[cfg ds] (load-site {:theme {:html-modules hm}})]
    (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
    [(get-in cfg [:theme :html-modules]) (mapv (juxt :message :hint) (diag/warnings ds))]))

(def ^:private hint "See config.example.edn (vdoing's htmlModules).")

;; ---------------------------------------------------------------------------
;; Item 1: keys, checks and accessors

(deftest nil-and-empty-are-off-and-quiet
  (doseq [v [nil {}]]
    (let [[hm ws] (modules-of v)]
      (is (empty? hm) (pr-str v))
      (is (= [] ws) (pr-str v)))))

(deftest a-value-that-is-not-a-map-warns-once-and-is-removed
  (doseq [v [false :none true 0 "<p>x</p>" [:page-t]]]
    (let [[cfg ds] (load-site {:theme {:html-modules v}})]
      (is (empty? (diag/errors ds)))
      (is (= [[(str ":theme :html-modules is " (pr-str v) ", but it must be a map of slots such as "
                    "{:page-t \"<p>…</p>\"}; nothing is injected.") hint]]
             (mapv (juxt :message :hint) (diag/warnings ds)))
          (pr-str v))
      (is (not (contains? (:theme cfg) :html-modules)) (pr-str v)))))

(deftest every-slot-and-show-mode-is-quiet
  (let [hm {:head-extra "<meta name=\"a\" content=\"b\">"
            :home-sidebar-b "<p>h</p>" :sidebar-t {:en "<p>t</p>" :zh-Hans ""}
            :sidebar-b "" :page-t {:en "<b>x</b>"} :page-b {}
            :page-t-show-mode "article" :page-b-show-mode :custom
            :window-lb "<p>l</p>" :window-rb {:zh-Hans "<p>r</p>"}}
        [out ws] (modules-of hm)]
    (is (= [] ws))
    (is (= (assoc hm :page-t-show-mode :article) out) "a show-mode string is stored as a keyword"))
  (doseq [[v m] [["all" :all] [:all :all] ["custom" :custom] [:article :article] [" Article " :article]]]
    (let [[out ws] (modules-of {:page-b-show-mode v})]
      (is (= [] ws) (pr-str v))
      (is (= m (:page-b-show-mode out)) (pr-str v)))))

(deftest a-bad-slot-warns-and-is-removed
  (doseq [v [42 true :p ["<p>"] {:en 1} {"en" "<p>x</p>"} {:en nil}]]
    (let [[out ws] (modules-of {:page-t v :page-b "<p>kept</p>"})]
      (is (= [[(str ":theme :html-modules :page-t is " (pr-str v) ", but it must be a string of HTML or "
                    "a map of language to string, such as {:en \"<p>…</p>\"}; it is ignored.") hint]]
             ws)
          (pr-str v))
      (is (= {:page-b "<p>kept</p>"} out) (pr-str v)))))

(deftest a-bad-show-mode-warns-and-means-all
  (doseq [v ["both" :never 1 true]]
    (let [[out ws] (modules-of {:page-t-show-mode v})]
      (is (= [[(str ":theme :html-modules :page-t-show-mode is " (pr-str v) ", but it must be :all, "
                    ":article or :custom (vdoing's \"article\" or \"custom\"); using :all.") hint]]
             ws)
          (pr-str v))
      (is (= :all (:page-t-show-mode out))))))

(defn- head-rejects
  "Assert that the :head-extra string `v` warns once, naming `bad`, and is
  removed while the other slots stay."
  [v bad]
  (let [[out ws] (modules-of {:head-extra v :page-t "<p>kept</p>"})]
    (is (= [[(str ":theme :html-modules :head-extra has " bad ", but it must be head content only — "
                  "<link>, <meta>, <script>, <style>, <noscript>, <template> or a comment; it is ignored.")
             hint]]
           ws)
        (pr-str v))
    (is (= {:page-t "<p>kept</p>"} out) (pr-str v))))

(deftest head-extra-is-head-content-only
  (testing "links, metas, scripts, styles, noscript, templates and comments pass, in any case"
    (doseq [v ["<link rel=\"stylesheet\" href=\"{{base}}x.css\">\n<META name=a content=b>"
               "<LINK rel=a><META name=b>" "<link rel=a /><meta name=b/>"
               "<script>document.write('<div>')</script><style>p{}</style>"
               "<script>var x = '</div>';</script>" "<Script>x</sCrIpT >"
               "<SCRIPT src=\"/a.js\"></SCRIPT><noscript><img src=x></noscript>"
               "<noscript><link></noscript>" "<template><body></template>"
               "<!-- <div> --><template><div></div></template>"
               "" "   "]]
      (let [[out ws] (modules-of {:head-extra v})]
        (is (= [] ws) (pr-str v))
        (is (= v (:head-extra out))))))
  (testing "anything else warns, naming the first bad tag as written, and is removed"
    (doseq [[v bad] [["<meta name=a><div>x</div>" "<div>"]
                     ["<img src=x.png>" "<img>"]
                     ["<TITLE>Two</TITLE>" "<TITLE>"]
                     ["<base href=\"/x/\">" "<base>"]
                     ["hello <meta name=a>" "text \"hello\""]]]
      (head-rejects v bad)))
  (testing "in a per-language map, only that language's value is dropped: it becomes \"\""
    (let [[out ws] (modules-of {:head-extra {:en "<div>" :zh-Hans "<meta name=a>"}})]
      (is (= {:head-extra {:en "" :zh-Hans "<meta name=a>"}} out))
      (is (= 1 (count ws)))
      (is (str/starts-with? (ffirst ws) ":theme :html-modules :head-extra :en has <div>,")))))

;; E1b.1: the value is scanned in order, one piece of head content at a time

(deftest a-quoted-gt-stays-inside-its-tag
  ;; item 1: `[^>]*` ended a tag at its first `>`, quoted or not, and blamed
  ;; the text after it
  (doseq [v ["<meta name=\"description\" content=\"a > b\">"
             "<link rel=\"preload\" href=\"/a.css\" title=\"a>b\">"
             "<META content='x>y' name=z/>"
             "<script src=\"/a.js?a=>\"></script>"]]
    (let [[out ws] (modules-of {:head-extra v})]
      (is (= [] ws) (pr-str v))
      (is (= v (:head-extra out)) (pr-str v))))
  (testing "a quote or a tag that never closes would swallow the rest of <head>"
    (head-rejects "<meta name=\"a><link rel=b>" "an unclosed <meta>")
    (head-rejects "<link rel=a" "an unclosed <link>")))

(deftest a-comment-opener-in-a-script-hides-nothing
  ;; item 2: comments were stripped before scripts, so a `<!--` in a string
  ;; swallowed everything after it
  (head-rejects "<script>var s = '<!--';</script><div>leak</div>" "<div>")
  (head-rejects "<style>a::after { content: '<!--' }</style><img src=x>" "<img>")
  (testing "an unclosed comment or block would swallow the rest of <head>"
    (head-rejects "<meta name=a><!-- <link rel=b>" "an unclosed <!--")
    (head-rejects "<script>var x = 1;" "an unclosed <script>")
    (head-rejects "<STYLE>p {}</style" "an unclosed <STYLE>")
    (head-rejects "<template><meta name=a>" "an unclosed <template>")))

(deftest a-meta-or-link-custom-element-is-not-head-content
  ;; item 3: `\b` matched before the `-`
  (head-rejects "<meta-data foo>" "<meta-data>")
  (head-rejects "<link-preview href=x></link-preview>" "<link-preview>")
  (head-rejects "<metadata>" "<metadata>"))

(deftest a-closing-tag-is-named-with-its-slash
  ;; item 4: "has <link>, but it must be head content only — <link>, …"
  (head-rejects "<link rel=a></link>" "</link>")
  (head-rejects "<meta name=a></META>" "</META>")
  (head-rejects "</head><p>x</p>" "</head>"))

;; E1b.2: the scan ends every tag and element where a browser does, at any
;; length

(def ^:private head-bad @#'config/head-bad)

(defn- times [^String s k] (.repeat s (int k)))

(deftest a-long-value-never-overflows-the-stack
  ;; item 1: the text pattern, `(?:[^<]|<(?!…))*`, recursed once per
  ;; character, so ~8,000 characters without a `<` threw StackOverflowError
  ;; and took the config check down with it
  (doseq [n [20000 1000000]]
    (testing (str n " characters")
      (is (= "text \"aaaaaaaaaaaaaaaaaaaa…\"" (head-bad (times "a" n))))
      (is (= "text \"Lorem ipsum dolor si…\"" (head-bad (times "Lorem ipsum dolor sit amet. " (quot n 28)))))
      (is (= "text \"< < < < < < < < < <…\"" (head-bad (times "< " (quot n 2)))))
      (is (nil? (head-bad (times "<meta name=\"a\" content=\"b\">" (quot n 27)))))
      (is (= "<div>" (head-bad (str (times "<meta name=a>" (quot n 13)) "<div>"))))
      (is (nil? (head-bad (times "<!---->" (quot n 7)))))
      (is (nil? (head-bad (str "<!--" (times "-" n) "-->"))))
      (is (nil? (head-bad (str "<meta content=\"" (times ">" n) "\">"))))
      (is (nil? (head-bad (str "<meta " (times "a=b " (quot n 4)) ">"))))
      (is (= "an unclosed <meta>" (head-bad (str "<meta content=\"" (times "x" n)))))
      (is (nil? (head-bad (str "<script></script " (times "a=\">\" " (quot n 6)) ">"))))
      (is (nil? (head-bad (str "<script><!--" (times "a-<" (quot n 3)) "</script>"))))
      (is (nil? (head-bad (str "<script><!--<script>" (times "x-<" (quot n 3)) "--></script>"))))
      (is (nil? (head-bad (str "<style>" (times "a<" (quot n 2)) "</style>"))))
      (is (nil? (head-bad (str "<template>" (times "<p a=\"</template>\">x</p>" (quot n 24)) "</template>"))))))
  (testing "the config check warns, as for a short value"
    (head-rejects (times "a" 20000) "text \"aaaaaaaaaaaaaaaaaaaa…\"")))

(deftest an-equals-sign-where-a-name-may-start-begins-a-name
  ;; item 2: `=` starts a value only straight after an attribute name;
  ;; after the tag name, a value or a `/`, it is the first character of a
  ;; new name, so the quote after it opens nothing and the `>` ends the tag
  (doseq [v ["<meta =\"><div>\">" "<meta name=\"x\" =\"><div>\">" "<meta a=\"b\"=\"><div>\">"
             "<meta a=b =\"><div>\">" "<meta/=\"><div>\">" "<meta a/=\"><div>\">"]]
    (head-rejects v "<div>"))
  (testing "after a name, with or without whitespace, `=` starts a value that may hold a `>`"
    (doseq [v ["<meta a =\"><div>\">" "<meta a = \"x>y\">" "<meta a=\"b\"/c=\"><div>\">"
               "<meta/a=\"><div>\">" "<meta =a>"]]
      (is (nil? (head-bad v)) (pr-str v))))
  (testing "an end tag's attributes are read the same way"
    (is (nil? (head-bad "<script></script x=\"><div>\">")))
    (is (= "an unclosed <script>" (head-bad "<script></script x=\"><meta>")))))

(deftest a-double-escaped-script-never-ends-at-its-end-tag
  ;; item 3: in a script, `<!--` then `<script` double-escapes it: a
  ;; `</script>` then ends that inner script only, and the outer one goes
  ;; on until a `-->`
  (head-rejects "<script><!-- x=\"<script>\"; </script>"
                (str "an unclosed <script> (after \"<!--\" and \"<script\", HTML does not end it at "
                     "\"</script>\")"))
  (is (= "an unclosed <script> (after \"<!--\" and \"<script\", HTML does not end it at \"</script>\")"
         (head-bad "<script><!--<script></script>-->")))
  (testing "a `-->` ends the escape, and escaped, a `</script>` still ends the script"
    (doseq [v ["<script><!--<script>--></script>"
               "<script><!-- document.write(\"<script></script>\"); --></script>"
               "<script><!-- <script> </script> </script>" "<script><!-- <scripts> </script>"]]
      (is (nil? (head-bad v)) (pr-str v)))
    (is (= "<div>" (head-bad "<script><!--</script><div>")))))

(deftest a-template-holds-html-but-not-a-template
  ;; item 4: a <template> was read as raw text to its first `</template>`,
  ;; but its content is HTML: a nested <template> takes that end tag, and a
  ;; comment hides it
  (head-rejects "<template><template></template><meta x>" "<template> inside a <template>")
  (head-rejects "<template><!--</template><!-- -->" "an unclosed <template>")
  (is (= "<SCRIPT> inside a <template>" (head-bad "<template><SCRIPT>\"</template>\"</SCRIPT></template>")))
  (is (= "text \"<?x\" inside a <template>" (head-bad "<template><?x</template>-><meta>")))
  (testing "its comments and quoted values may hold `</template>`"
    (doseq [v ["<template><div>x</div></template>" "<template><!-- </template> --></template>"
               "<template><div title=\"</template>\"></div></template>"
               "<template><a </template>></template>"]]
      (is (nil? (head-bad v)) (pr-str v)))))

(deftest text-that-does-not-show-is-named-escaped
  ;; item 5: `str/trim` removed a U+000B, naming it `text ""`
  (doseq [[v bad] [["\u000b" "text \"\\u000b\""] ["\u00a0" "text \"\\u00a0\""]
                   ["\ufeff<meta>" "text \"\\ufeff\""] ["<meta>a\u0000" "text \"a\\u0000\""]
                   ["<meta>tab\tthen\nline" "text \"tab\\tthen\\nline\""]]]
    (is (= bad (head-bad v)) (pr-str v)))
  (head-rejects "\u000b" "text \"\\u000b\""))

(def ^:private probe-cases
  "The E1b.1 probe's cases, and E1b.2's, with what parse5 7.1.2 (an HTML
  parser to the spec) made of each between a <title> and a <link> in a
  <head>: whether that <link> stayed in the <head>, the <head> held head
  content only and the <body> came out whole — with scripting on, then off."
  [
   ["<meta content=\"a > b\">" true true]
   ["<meta content=\"a>b" false false]
   ["<meta content='a>b" false false]
   ["<meta content=\"x\"y\">" true true]
   ["<meta a=>" true true]
   ["<meta a= >" true true]
   ["<meta a=" false false]
   ["<meta a = \"x>y\">" true true]
   ["<link href=/a/b.css rel=stylesheet>" true true]
   ["<link href=/a/>" true true]
   ["<meta data-x='\"'>" true true]
   ["<meta data-x=\"'\">" true true]
   ["<meta a\"b=\"c>d\">" true true]
   ["<meta a\"b>c\">" false false]
   ["<meta a\"b><div>\">" false false]
   ["<meta =\"><div>\">" false false]
   ["<meta name=\"x\" =\"><div>\">" false false]
   ["<meta a=\"b\"=\"><div>\">" false false]
   ["<meta a=b =\"><div>\">" false false]
   ["<meta/=\"><div>\">" false false]
   ["<meta a=b\"c>" true true]
   ["<meta <div>>" false false]
   ["<meta a=`x`>" true true]
   ["<style>/*<script>*/ a{} </style>" true true]
   ["<style><script></script></style>" true true]
   ["<script>var s=\"</script>\";</script>" false false]
   ["<script>var s=\"<\\/script>\";</script>" true true]
   ["<script><!-- x=\"<script>\"; </script>" false false]
   ["<script><!--<script></script>-->" false false]
   ["<script><!--<script>--></script>" true true]
   ["<script><!-- document.write(\"<script></script>\"); --></script>" true true]
   ["<script></script x=\"><div>\">" true true]
   ["<script></script/>" true true]
   ["<script></script >" true true]
   ["<script></scripty></script>" true true]
   ["<script src=x />" false false]
   ["<SCRIPT>a</SCRIPT>" true true]
   ["<ScRiPt>a</sCrIpT>" true true]
   ["<STYLE>a{}</style>" true true]
   ["<LINK REL=stylesheet HREF=x>" true true]
   ["<Meta Charset=utf-8>" true true]
   ["<noscript><div>x</div></noscript>" true false]
   ["<noscript><link rel=stylesheet href=x></noscript>" true true]
   ["<template><div>x</div></template>" true true]
   ["<template><template></template></template>" true true]
   ["<template><template></template><meta x>" false false]
   ["<template><!--</template><!-- -->" false false]
   ["<template><script>\"</template>\"</script></template>" true true]
   ["<!---->" true true]
   ["<!-->" true true]
   ["<!--->" true true]
   ["<!-- a --!>" true true]
   ["<!-- -- > <div> -->" true true]
   ["<!-- unclosed" false false]
   ["<![CDATA[x]]>" true true]
   ["<!DOCTYPE html>" true true]
   ["<?xml x?>" true true]
   ["<meta-data>" false false]
   ["<link rel=a></link>" true true]
   ["<title>x</title>" false false]
   ["<base href=/>" false false]
   ["<noframes>x</noframes>" false false]
   ["</br>" false false]
   ["</meta>" true true]
   ["hi" false false]
   ["   " true true]
   ["\t\n\f\r " true true]
   ["\u00a0" false false]
   ["\u000b" false false]
   ["\ufeff<meta>" false false]
   ["" true true]
   ["<" false false]
   ["<meta>&amp;" false false]
   ["<meta>\u0000" false false]
   ["<link rel=x><div>" false false]
   ["<meta><!--a--><meta>text" false false]
   ["<script></script x=\"><meta>" false false]
   ["<style></style x=\"><div>\">" true true]
   ["<script></script x=\">" false false]
   ["<script><!-- <script> </script> --> </script>" true true]
   ["<script><!-- <script> </script> </script>" true true]
   ["<script><!--</script><div>" false false]
   ["<script><!-- <scripts> </script>" true true]
   ["<script><!-- <SCRIPT/> </script> --></script>" true true]
   ["<script><!--> <script> </script>" true true]
   ["<script><!---> <script> </script>" true true]
   ["<script><!-- --> <script> </script>" true true]
   ["<script><!-- --!> <script> </script>" false false]
   ["<script><!-- <script --> </script>" true true]
   ["<script><!-<script></script>" true true]
   ["<script><!-- <script> --> <!-- <script> </script>" false false]
   ["<script><!-- <script> ---> </script>" true true]
   ["<script><!-- <script> -- > </script>" false false]
   ["<script><!-- <script> </script --> </script>" true true]
   ["<script><!-- <script> </scripty> </script>" false false]
   ["<script><!-- <script> </script> <script> </script>" false false]
   ["<script><!--<!-- <script> </script>" false false]
   ["<script>a<!--b<script>c</script>d-->e</script>" true true]
   ["<style><!-- <style> </style>" true true]
   ["<template><!-- </template> --></template>" true true]
   ["<template><div title=\"</template>\"></div></template>" true true]
   ["<template><div title=\"</template><div>\"></template>" true true]
   ["<template></template x=\"><div>\">" true true]
   ["<template><style></style></template>" true true]
   ["<template><?x</template>-><meta>" false false]
   ["<template><a </template>></template>" true true]
   ["<template><TEMPLATE></TEMPLATE></template>" true true]
   ["<template><svg><![CDATA[</template><div>" false false]
   ["<template><p>a<b>c</p></template>" true true]
   ["<template><table><tr><td>x</table></template>" true true]
   ["<template></div></template>" true true]
   ["<template><body><html></template>" true true]
   ["<template><a href=\"x>y\">z</a></template><meta>" true true]
   ["<template><div</template>" false false]
   ["<template><div x=\"</template>" false false]
   ["<template></template" false false]
   ["<template><!DOCTYPE html></template>" true true]
   ["<meta a='b'c>" true true]
   ["<meta a = = b>" true true]
   ["<meta a= =\">\"<div>\">" false false]
   ["<meta =a>" true true]
   ["<meta a=b/>" true true]
   ["<link rel=x/ >" true true]
   ["<meta a=\"b\"/c=\"><div>\">" true true]
   ["<meta/a=\"><div>\">" true true]
   ["<meta a/=\"><div>\">" false false]
   ["<meta a =\"><div>\">" true true]
   ["<meta a\f=\"x>y\">" true true]
   ["<meta a\u000b=\"x>y\">" true true]
   ["<meta" false false]
   ["<link" false false]
   ["<meta a=\"b\"" false false]
   ["<meta a=b" false false]
   ["<meta / >" true true]
   ["<meta a / = \"><div>\">" false false]
   ["a\u000b" false false]
   ["<meta>hello\u000bworld" false false]
   ])

(def ^:private rejected-though-safe
  "Cases a browser reads safely that `head-bad` rejects, erring toward
  caution: an end tag, a `<!…>` or `<?…>`, and a <template> holding a
  <template>, a <script> or a <style>."
  #{"</meta>" "<link rel=a></link>" "<![CDATA[x]]>" "<!DOCTYPE html>" "<?xml x?>"
    "<template><template></template></template>" "<template><TEMPLATE></TEMPLATE></template>"
    "<template><script>\"</template>\"</script></template>" "<template><style></style></template>"
    "<template><!DOCTYPE html></template>"})

(deftest the-probe-cases-agree-with-a-browser
  ;; `head-bad` reads a <noscript> as a browser with scripting on does
  ;; (README): `<noscript><div>x</div></noscript>` passes, though with
  ;; scripting off its <div> would end the <head>
  (doseq [[v on _off] probe-cases]
    (is (= (and on (not (rejected-though-safe v))) (nil? (head-bad v))) (pr-str v))))

(deftest vdoing-slot-names-warn-with-the-kebab-spelling
  (let [[_ ds] (load-site {:theme {:html-modules {:homeSidebarB "x"} :pageT "y"}})
        ms (mapv :message (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (= #{(str ":theme :html-modules :homeSidebarB is vdoing's spelling; clogem-press reads "
                  ":theme :html-modules :home-sidebar-b. It is ignored.")
             ":theme :pageT is vdoing's spelling; clogem-press reads :theme :html-modules :page-t. It is ignored."}
           (set ms))
        (pr-str ms)))
  (doseq [[v k] [["sidebarT" :sidebar-t] ["sidebarB" :sidebar-b] ["pageB" :page-b]
                 ["pageTshowMode" :page-t-show-mode] ["pageBshowMode" :page-b-show-mode]
                 ["windowLB" :window-lb] ["windowRB" :window-rb] ["htmlModules" nil]]]
    (is (= (if k [[:theme :html-modules k] :ok] [[:theme :html-modules] :ok])
           (get config/vdoing-keys v))
        v)))

(deftest unknown-keys-warn-and-suggest
  (let [[_ ds] (load-site {:theme {:html-modules {:bogus 1 :sidebar-tt "x"}}})
        by (into {} (map (juxt :message :hint)) (diag/warnings ds))]
    (is (empty? (diag/errors ds)))
    (is (contains? by ":theme :html-modules :bogus is not a theme html-modules option and is ignored."))
    (is (str/starts-with? (by ":theme :html-modules :sidebar-tt is not a theme html-modules option and is ignored.")
                          "Did you mean :sidebar-t?"))
    (is (= 2 (count by)) (pr-str by))))

(deftest the-accessors
  (let [[cfg _] (load-site {:theme {:html-modules {:page-t {:en "" :zh-Hans " "} :page-b {:ms "<p>x</p>"}
                                                   :sidebar-t "  " :window-rb "<p>r</p>"
                                                   :page-b-show-mode "custom"}}})]
    (is (= [:head-extra :home-sidebar-b :sidebar-t :sidebar-b :page-t :page-b :window-lb :window-rb]
           config/module-slots))
    (is (= {:ms "<p>x</p>"} (config/html-module cfg :page-b)))
    (is (= :all (config/show-mode cfg :page-t)))
    (is (= :custom (config/show-mode cfg :page-b)))
    (is (false? (config/module-set? cfg :page-t)) "only blank values")
    (is (false? (config/module-set? cfg :sidebar-t)))
    (is (true? (config/module-set? cfg :page-b)))
    (is (false? (config/module-set? cfg :head-extra)))
    (is (true? (boolean (config/windows? cfg))))
    (is (false? (boolean (config/windows? (first (load-site {:theme {:html-modules {:page-t "x"}}}))))))))

;; ---------------------------------------------------------------------------
;; Temp sites

(def ^:private one-lang {:langs {:locales {:zh-Hans nil :zh-Hant nil :ms nil :ta nil}}})

(def ^:private a-toc-article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n# T\n\n## Alpha\n\nbody\n")

(def ^:private a-post
  "---\ntitle: P\ndate: \"2026-02-02 00:00:00\"\npermalink: /pages/p00001/\nsidebar: auto\n---\n\n# P\n\nbody\n")

(def ^:private a-catalogue
  (str "---\ntitle: C\narticle: false\nsidebar: false\npageComponent:\n  name: Catalogue\n"
       "  data:\n    path: 01.Guide\ndate: \"2026-02-03 00:00:00\"\npermalink: /pages/c00001/\n---\n\nC\n"))

(def ^:private a-custom-page
  (str "---\ntitle: X\narticle: false\ndate: \"2026-02-04 00:00:00\"\npermalink: /pages/x00001/\n"
       "---\n\n# X\n\nbody\n"))

(def ^:private files
  "A home, an article with a tree, a post without one, a catalogue, an
  `article: false` page, and tags that give category, tag and archive pages."
  {"index.md" "# Home\n"
   "01.Guide/01.t.md" (str/replace a-toc-article "title: T\n" "title: T\ntags:\n  - x\n")
   "01.Guide/02.x.md" a-custom-page
   "00.Catalogue/01.c.md" a-catalogue
   "_posts/p.md" a-post})

(defn- build-temp
  "Build a temp site from `edn` and `files`; {rel-path → html}, plus
  `:clogem/dist` (every file) and `:clogem/warnings`."
  [edn files]
  (let [dir (fs/create-temp-dir {:prefix "clogem-e1b"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str (merge {:site {:title "S"} :search {:provider :none}} edn)))
      (doseq [[rel content] files]
        (fs/create-dirs (fs/parent (fs/path dir "content" rel)))
        (spit (fs/file dir "content" rel) content))
      (let [err (java.io.StringWriter.)
            [_ ds] (binding [*out* (java.io.StringWriter.) *err* err]
                     (diag/collecting (cli/build {:site-dir (str dir) :no-write true})))
            dist (fs/path dir "dist")]
        (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
        (assoc (into {}
                     (for [p (fs/glob dist "**.html")]
                       [(str (fs/relativize dist p)) (slurp (fs/file p))]))
               :clogem/dist (set (map #(str (fs/relativize dist %)) (fs/glob dist "**")))
               :clogem/warnings (into (mapv :message (diag/warnings ds))
                                      (map second (re-seq #"(?m)^warning: (.*)$" (str err))))))
      (finally (fs/delete-tree dir)))))

(defn- html-pages
  "Only the pages of `build-temp`'s result."
  [ps]
  (into (sorted-map) (filter #(string? (key %))) ps))

(def ^:private every-slot
  "A marker per slot, so one build shows every placement."
  (into {} (for [s config/module-slots]
             [s (if (= :head-extra s)
                  "<meta name=\"m-head-extra\" content=\"1\">"
                  (str "<p>m-" (name s) "</p>"))])))

;; ---------------------------------------------------------------------------
;; Item 2: where each slot goes

(defn- wrapper-re [slot]
  (re-pattern (str "<div class=\"clogem-module clogem-module--" (name slot) "\"><p>m-" (name slot) "</p></div>")))

(deftest the-home-sidebar-b-is-the-right-bars-last-box
  (let [ps (build-temp (assoc one-lang :theme {:html-modules every-slot :per-page 1}) files)
        home (ps "index.html")]
    (is (re-find #"<section class=\"clogem-module clogem-module--home-sidebar-b\"><p>m-home-sidebar-b</p></section></aside>"
                 home)
        "a section, so the box rules apply, and the aside's last child")
    (is (= 1 (count (re-seq #"clogem-module--home-sidebar-b" home))))
    (testing "on /page/2/ too — it renders through `home`"
      (is (str/includes? (ps "page/2/index.html")
                         "<section class=\"clogem-module clogem-module--home-sidebar-b\"><p>m-home-sidebar-b</p></section></aside>")))
    (testing "hideRightBar: true — no right bar, so no box"
      (let [hidden (build-temp (assoc one-lang :theme {:html-modules every-slot})
                               (assoc files "index.md" "---\nhideRightBar: true\n---\n\n# Home\n"))]
        (is (not (str/includes? (hidden "index.html") "clogem-module--home-sidebar-b")))))
    (testing "the home gets no page slot, and no other page gets the home's box"
      (is (not (str/includes? home "clogem-module--page-t")))
      (is (not (str/includes? home "clogem-module--page-b")))
      (doseq [[rel page] (html-pages ps) :when (not (#{"index.html" "page/2/index.html"} rel))]
        (is (not (str/includes? page "clogem-module--home-sidebar-b")) rel)))))

(deftest the-sidebar-slots-sit-around-the-tree
  (let [ps (build-temp (assoc one-lang :theme {:html-modules every-slot}) files)
        article (ps "pages/t00001/index.html")]
    (is (re-find #"<aside class=\"clogem-sidebar\" id=\"clogem-sidebar\"><div class=\"clogem-module clogem-module--sidebar-t\"><p>m-sidebar-t</p></div><nav"
                 article)
        "before the <nav>")
    (is (re-find #"</nav><div class=\"clogem-module clogem-module--sidebar-b\"><p>m-sidebar-b</p></div></aside>" article)
        "after it")
    (testing "only where a tree renders: not on the post, the catalogue, the home or the index pages
              (the `article: false` page under 01.Guide has a tree, so it has them)"
      (is (str/includes? (ps "pages/x00001/index.html") "clogem-module--sidebar-t"))
      (doseq [[rel page] (html-pages ps)
              :when (not (#{"pages/t00001/index.html" "pages/x00001/index.html"} rel))]
        (is (not (str/includes? page "clogem-module--sidebar-")) rel)))
    (testing "no new class contains the clogem-sidebar substring a treeless page is checked for"
      (is (not (str/includes? (ps "pages/p00001/index.html") "clogem-sidebar"))))))

(deftest the-page-slots-sit-around-the-content
  (let [ps (build-temp (assoc one-lang :theme {:html-modules every-slot}) files)]
    (testing "an article: page-t after the variant bar and before .clogem-content, page-b after </article>"
      (let [a (ps "pages/t00001/index.html")]
        (is (re-find #"<div class=\"clogem-module clogem-module--page-t\"><p>m-page-t</p></div><div class=\"clogem-content\"" a))
        (is (re-find #"</article><div class=\"clogem-module clogem-module--page-b\"><p>m-page-b</p></div>" a))))
    (testing "a post without a tree has them too"
      (let [p (ps "pages/p00001/index.html")]
        (is (re-find (wrapper-re :page-t) p))
        (is (re-find (wrapper-re :page-b) p))))
    (testing "a catalogue: page-t after the grid, inside the article; page-b after it"
      (let [c (ps "pages/c00001/index.html")]
        (is (re-find #"</div><div class=\"clogem-module clogem-module--page-t\"><p>m-page-t</p></div></article>" c))
        (is (re-find #"</article><div class=\"clogem-module clogem-module--page-b\"><p>m-page-b</p></div>" c))))
    (testing "an `article: false` page has them (its show-mode is :all)"
      (is (re-find (wrapper-re :page-t) (ps "pages/x00001/index.html"))))
    (testing "never on the home, the index pages, the archives or 404.html"
      (doseq [[rel page] (html-pages ps)
              :when (not (re-find #"^pages/" rel))]
        (is (not (str/includes? page "clogem-module--page-")) rel))
      (is (some #(re-find #"^(categories|tags|archives)/" %) (keys (html-pages ps))))
      (is (contains? (html-pages ps) "404.html")))))

(deftest the-show-mode-matrix
  (doseq [[mode article? catalogue? custom?] [[nil true true true]
                                              [:all true true true] ["all" true true true]
                                              [:article true false false] ["article" true false false]
                                              [:custom false true true] ["custom" false true true]]]
    (let [ps (build-temp (assoc one-lang :theme {:html-modules (cond-> {:page-t "<p>m-page-t</p>"
                                                                        :page-b "<p>m-page-b</p>"}
                                                                 mode (assoc :page-t-show-mode mode
                                                                             :page-b-show-mode mode))})
                         files)
          shown? (fn [rel] (boolean (re-find (wrapper-re :page-t) (ps rel))))
          shown-b? (fn [rel] (boolean (re-find (wrapper-re :page-b) (ps rel))))]
      (testing (pr-str mode)
        (is (= article? (shown? "pages/t00001/index.html")) "an article")
        (is (= catalogue? (shown? "pages/c00001/index.html")) "a catalogue (article: false)")
        (is (= custom? (shown? "pages/x00001/index.html")) "an `article: false` page")
        (is (= article? (shown-b? "pages/t00001/index.html")) "page-b follows its own mode")))))

(deftest languages-and-the-base
  (let [ps (build-temp {:site {:title "S" :base "/my docs/"}
                        :langs {:priority [:en :zh-Hans :zh-Hant] :locales {:ms nil :ta nil}}
                        :search {:provider :none}
                        :theme {:html-modules {:page-t {:en "<a href=\"{{base}}a/\">en</a>" :zh-Hans ""}}}}
                       {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article
                        "01.Guide/01.t.zh-Hans.md" (str/replace a-toc-article "title: T" "title: T 简")
                        "01.Guide/01.t.zh-Hant.md" (str/replace a-toc-article "title: T" "title: T 繁")})]
    (testing "{{base}} becomes the encoded base"
      (is (str/includes? (ps "pages/t00001/index.html") "<a href=\"/my%20docs/a/\">en</a>")))
    (testing "\"\" suppresses it in its own language only"
      (is (not (str/includes? (ps "zh-Hans/pages/t00001/index.html") "clogem-module"))))
    (testing "a language the map does not name follows the chain (zh-Hant → en, never zh-Hans)"
      (is (str/includes? (ps "zh-Hant/pages/t00001/index.html") "<a href=\"/my%20docs/a/\">en</a>")))))

(deftest a-language-off-the-chain-gets-nothing
  (let [ps (build-temp (assoc one-lang :langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}
                              :theme {:html-modules {:page-t {:zh-Hans "<p>m-page-t</p>"}}})
                       {"index.md" "# Home\n" "01.Guide/01.t.md" a-toc-article
                        "01.Guide/01.t.zh-Hans.md" (str/replace a-toc-article "title: T" "title: T 简")})]
    (is (re-find (wrapper-re :page-t) (ps "zh-Hans/pages/t00001/index.html")))
    (is (not (str/includes? (ps "pages/t00001/index.html") "clogem-module"))
        "en is not on zh-Hans's map, and the chain does not reach it")))

(deftest no-search-means-no-pagefind-attribute
  (let [ps (build-temp (assoc one-lang :theme {:html-modules every-slot}) files)]
    (is (some #(str/includes? (val %) "clogem-module") (html-pages ps)))
    (doseq [[rel page] (html-pages ps)]
      (is (not (str/includes? page "data-pagefind-")) rel))))

(deftest identity-css-ships-with-any-slot-but-head-extra
  (let [with-slot (build-temp (assoc one-lang :theme {:html-modules {:page-b "<p>x</p>"}}) files)
        head-only (build-temp (assoc one-lang :theme {:html-modules {:head-extra "<meta name=a content=b>"}}) files)]
    (is (contains? (:clogem/dist with-slot) "clogem/css/identity.css"))
    (is (str/includes? (with-slot "index.html") "css/identity.css"))
    (is (not (contains? (:clogem/dist head-only) "clogem/css/identity.css")))
    (is (not (str/includes? (head-only "index.html") "identity.css")))))

;; ---------------------------------------------------------------------------
;; The demo, rendered with search on (`clogem.render/build!` writes the
;; pages without running Pagefind)

(defn- build-demo
  "{:pages {rel → html} :files #{rel}} of the demo, built with `overrides`."
  [overrides]
  (let [out (fs/create-temp-dir {:prefix "clogem-e1b-demo"})]
    (try
      (let [cfg (first (diag/collecting
                        (config/load-config "examples/demo-site" nil
                                            (merge-with merge {:build {:out (str out)}
                                                               :content {:write-front-matter false}}
                                                        overrides))))
            [model _] (diag/collecting (cli/analyse cfg))]
        (diag/collecting ((requiring-resolve 'clogem.render/build!) cfg model))
        {:pages (into (sorted-map)
                      (for [p (fs/glob out "**.html")]
                        [(str (fs/relativize out p)) (slurp (fs/file p))]))
         :files (set (map #(str (fs/relativize out %)) (fs/glob out "**")))})
      (finally (fs/delete-tree out)))))

(deftest with-search-on-every-wrapper-is-ignored-by-pagefind
  (let [{:keys [pages]} (build-demo {:theme {:html-modules every-slot}})]
    (is (< 100 (count pages)))
    (doseq [[rel html] pages
            :let [wrappers (re-seq #"<(?:div|section) class=\"clogem-module[^\"]*\"[^>]*>" html)]
            :when (seq wrappers)]
      (doseq [w wrappers]
        (is (str/includes? w "data-pagefind-ignore=\"all\"") (str rel ": " w))))
    (is (some #(str/includes? (val %) "clogem-module--page-t") pages))
    (testing ":head-extra is raw, with no wrapper and no attribute"
      (is (str/includes? (pages "index.html") "<meta name=\"m-head-extra\" content=\"1\">")))))

(deftest pagefind-indexes-no-module
  (if-let [real (System/getenv "CLOGEM_PAGEFIND")]
    (let [site (fs/create-temp-dir {:prefix "clogem-e1b-pf"})]
      (try
        (fs/copy-tree "examples/demo-site" site)
        (spit (fs/file site "site.edn")
              (pr-str (assoc-in (clojure.edn/read-string (slurp (fs/file site "site.edn")))
                                [:theme :html-modules]
                                {:page-t "<p>zygotic</p>" :page-b "<p>zygotic</p>"})))
        (binding [diag/*sink* (atom [])
                  clogem.search/*env* {"CLOGEM_PAGEFIND" real}]
          (let [out (fs/path site "dist")
                _ (with-out-str (cli/build {:site-dir (str site) :out (str out) :no-write true}))
                frags (fake/read-fragments out)
                html-of (fn [url] (slurp (fs/file (fs/path out (str (subs url 1) "index.html")))))
                catalogue? (fn [url] (str/includes? (html-of url) "clogem-catalogue\""))
                ;; page-t is the slot inside the indexed article
                marked (filter #(re-find #"clogem-module--page-t\"[^>]*><p>zygotic</p>" (html-of %))
                               (map :url frags))]
            (is (< 20 (count frags)))
            (testing "page-t carries the marker on indexed pages, an article and a catalogue among them"
              (is (some (complement catalogue?) marked) (pr-str marked))
              (is (some catalogue? marked) (pr-str marked)))
            (doseq [{:keys [url content]} frags]
              (is (not (str/includes? (str content) "zygotic")) (str url ": the module is not indexed")))
            (is (some #(str/includes? (str (:content %)) "Pagefind") frags)
                "the articles' own words still are")))
        (finally (fs/delete-tree site))))
    (do (is (nil? (System/getenv "GITHUB_ACTIONS")) "CI sets CLOGEM_PAGEFIND, so this always runs there")
        (println "pagefind-indexes-no-module: skipped — CLOGEM_PAGEFIND is not set"))))

;; ---------------------------------------------------------------------------
;; Item 3: :head-extra

(defn- head-of [html] (subs html 0 (str/index-of html "</head>")))

(deftest head-extra-goes-after-pagefind-and-before-custom-css
  (let [{:keys [pages]} (build-demo {:analytics {:provider :plausible :domain "clogem-demo.example"}
                                     :theme {:html-modules
                                             {:head-extra {:en (str "<meta name=\"x-e1b\" content=\"1\">"
                                                                    "<link rel=\"stylesheet\" href=\"{{base}}extra.css\">")
                                                           :zh-Hans ""}}}})
        ;; the page's own language, not its path: the demo has a zh-Hans
        ;; article at an unprefixed URL. Only zh-Hans sets ""; every other
        ;; language follows the chain to :en, zh-Hant included (it never
        ;; passes through zh-Hans)
        en? (fn [html] (not= "zh-Hans" (second (re-find #"<html [^>]*lang=\"([^\"]+)\"" html))))]
    (is (< 100 (count pages)))
    (doseq [[rel html] pages
            :when (str/includes? html "<header class=\"clogem-navbar\"")
            :let [head (head-of html)
                  sheets (map second (re-seq #"<link href=\"([^\"]+)\" rel=\"stylesheet\"" head))]]
      (if (en? html)
        (testing rel
          (is (str/includes? head "<meta name=\"x-e1b\" content=\"1\">"))
          (is (str/includes? head "<link rel=\"stylesheet\" href=\"/extra.css\">") "{{base}} is replaced")
          ;; the last of Pagefind's tags: its component script, or
          ;; js/search.js where the UI strings need it
          (is (< (max (str/last-index-of head "pagefind-component-ui")
                      (or (str/last-index-of head "clogem/js/search.js") -1))
                 (str/index-of head "x-e1b"))
              "after Pagefind's tags")
          (is (< (str/index-of head "x-e1b") (str/index-of head "overrides/custom.css"))
              "before the site's own stylesheet"))
        (testing (str rel ": \"\" suppresses it")
          (is (not (str/includes? head "x-e1b")))
          (is (not (str/includes? head "extra.css")))))
      (is (str/includes? (last sheets) "overrides/custom.css") (str rel ": custom.css is still last"))
      (is (str/ends-with? head "</script>") (str rel ": the analytics loader is still last in <head>"))
      (is (re-find #"clogem/js/analytics\.js[^>]*></script>$" head) rel))
    (testing "the 404 page has it"
      (is (str/includes? (pages "404.html") "x-e1b")))))

(deftest a-redirect-stub-has-no-head-extra-module-or-window
  ;; the demo makes no stub (E1b.1 item 5: this was a loop over none);
  ;; `:i18n :prefix-default? true` makes one per article, at its bare URL
  (let [{:keys [pages]} (build-demo {:i18n {:prefix-default? true} :theme {:html-modules every-slot}})
        stubs (filter #(str/includes? (val %) "http-equiv=\"refresh\"") pages)]
    (is (< 10 (count stubs)) (str (count stubs) " redirect stubs"))
    (doseq [[rel html] stubs
            :let [target (second (re-find #"url=/([^\"]*)\"" html))
                  to (pages (str target "index.html"))]]
      (is (not (str/includes? html "<header class=\"clogem-navbar\"")) rel)
      (doseq [s ["m-head-extra" "clogem-module" "clogem-window" "window.js"]]
        (is (not (str/includes? html s)) (str rel ": " s)))
      (testing "while the article it redirects to has them"
        (is (some? to) (str rel " → " target))
        (is (str/includes? (str to) "<meta name=\"m-head-extra\" content=\"1\">") target)
        (is (str/includes? (str to) "clogem-module--page-t") target)
        (is (str/includes? (str to) "/clogem/js/window.js") target)))))

(deftest head-extra-with-body-content-warns-and-is-dropped
  (let [ps (build-temp (assoc one-lang :theme {:html-modules {:head-extra "<meta name=a><div>x</div>"}}) files)]
    (is (some #(str/includes? % ":theme :html-modules :head-extra has <div>") (:clogem/warnings ps))
        (pr-str (:clogem/warnings ps)))
    (doseq [[rel page] (html-pages ps)]
      (is (not (str/includes? page "name=a")) rel))))

;; ---------------------------------------------------------------------------
;; Item 4: the windows

(def ^:private close-re
  #"<button aria-label=\"([^\"]+)\" class=\"clogem-window__close\" hidden=\"hidden\" title=\"\1\" type=\"button\"><svg[^>]*><use href=\"[^\"]*#x\"(?:/>|></use>)</svg></button>")

(deftest the-windows-come-just-before-back-to-top
  (let [ps (build-temp (assoc one-lang :theme {:html-modules every-slot}) files)]
    (doseq [[rel page] (html-pages ps)
            :when (str/includes? page "<header class=\"clogem-navbar\"")
            :when (not= rel "404.html")]
      (is (re-find #"<div class=\"clogem-window clogem-window--lb\">.*<div class=\"clogem-window clogem-window--rb\">.*<button aria-label=\"Back to top\"" page)
          (str rel ": lb, rb, then back to top"))
      (is (str/includes? page "<p>m-window-lb</p></div></div><div class=\"clogem-window clogem-window--rb\">")
          (str rel ": rb right after lb"))
      (is (str/includes? page "<p>m-window-rb</p></div></div><button aria-label=\"Back to top\"")
          (str rel ": back to top right after rb"))
      (is (str/ends-with? page "#arrow-up\"></use></svg></button></body></html>")
          (str rel ": back to top is still the last child of <body>"))
      (is (= 2 (count (re-seq close-re page))) rel)
      (is (str/includes? page "/clogem/js/window.js") rel))
    (testing "no window and no script on the 404 page"
      (is (not (str/includes? (ps "404.html") "clogem-window")))
      (is (not (str/includes? (ps "404.html") "window.js"))))
    (testing "the close button is named in the page's language"
      (let [zh (build-temp (assoc one-lang :langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}
                                  :theme {:html-modules {:window-lb "<p>w</p>"}})
                           {"index.md" "# Home\n" "index.zh-Hans.md" "# 首页\n"})]
        (is (= "关闭" (second (re-find close-re (zh "zh-Hans/index.html")))))
        (is (= "Close" (second (re-find close-re (zh "index.html")))))))))

(deftest with-back-to-top-off-the-windows-end-the-body
  (let [ps (build-temp (assoc one-lang :theme {:back-to-top false :html-modules {:window-rb "<p>w</p>"}}) files)]
    (is (str/ends-with? (ps "index.html")
                        "<div class=\"clogem-module clogem-module--window-rb\"><p>w</p></div></div></body></html>"))))

(deftest window-js-ships-and-is-linked-only-where-a-window-renders
  (let [per-lang (build-temp (assoc one-lang :langs {:priority [:en :zh-Hans] :locales {:zh-Hant nil :ms nil :ta nil}}
                                    :theme {:html-modules {:window-lb {:en "<p>w</p>" :zh-Hans ""}}})
                             {"index.md" "# Home\n" "index.zh-Hans.md" "# 首页\n"
                              "01.Guide/01.t.md" a-toc-article})]
    (is (contains? (:clogem/dist per-lang) "clogem/js/window.js"))
    (doseq [[rel page] (html-pages per-lang)
            :when (and (str/includes? page "<header class=\"clogem-navbar\"")
                       ;; the 404 page never has a window (and so no script)
                       (not= rel "404.html"))]
      (is (= (not (str/starts-with? rel "zh-Hans/"))
             (str/includes? page "js/window.js"))
          (str rel ": linked only where a window renders")))
    (testing "a module that is not a window ships none"
      (let [no (build-temp (assoc one-lang :theme {:html-modules {:page-t "<p>x</p>"}}) files)]
        (is (not (contains? (:clogem/dist no) "clogem/js/window.js")))
        (is (not-any? #(str/includes? (val %) "window.js") (html-pages no)))))))

(deftest the-window-js-budget
  (let [f (fs/path "src" "clogem" "theme" "resources" "js" "window.js")
        bs (fs/read-all-bytes f)
        out (java.io.ByteArrayOutputStream.)
        _ (with-open [g (java.util.zip.GZIPOutputStream. out)] (.write g bs))
        gz (count (.toByteArray out))]
    (is (<= gz 400) (str "window.js: " gz " bytes gzipped"))
    (println (format "modules: js/window.js is %d bytes, %d gzipped (budget 400)" (count bs) gz))))
