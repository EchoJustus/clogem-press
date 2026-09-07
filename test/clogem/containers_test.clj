;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.containers-test
  "vdoing containers as a source-line pre-pass (D-P2-10, approach A)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nextjournal.markdown :as md]
            [clogem.config :as config]
            [clogem.containers :as c]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.markdown :as markdown]))

(def cfg (first (diag/collecting (config/load-config "test/fixtures/__no_such_site__"))))

(defn- zh-ctx []
  {:cfg cfg :lang :zh-Hans :strings {:zh-Hans (i18n/theme-strings :zh-Hans)} :from-path "t.md"})

(defn- expand [src & [ctx]]
  (first (diag/collecting (c/expand src (or ctx {:from-path "t.md"})))))

(defn- html [src & [ctx]]
  (str (h/html (markdown/render src (or ctx {:from-path "t.md"})))))

(deftest default-title-follows-the-page-language
  (testing "§6.4 rule 2: `::: tip` renders 提示 on a zh-Hans page, TIP without a context"
    (is (str/includes? (expand "::: tip\nx\n:::\n" (zh-ctx)) "<p class=\"custom-block-title\">提示</p>"))
    (is (str/includes? (expand "::: tip\nx\n:::\n") "<p class=\"custom-block-title\">TIP</p>"))
    (is (str/includes? (expand "::: theorem\nx\n:::\n" (zh-ctx)) "<p class=\"title\">定理</p>")
        ":container/theorem exists in every string file")))

(deftest custom-titles-are-html-escaped
  (is (str/includes? (expand "::: tip My title\nx\n:::\n") "<p class=\"custom-block-title\">My title</p>"))
  (is (str/includes? (expand "::: tip <b>x</b>\nx\n:::\n") "&lt;b&gt;x&lt;/b&gt;")))

(deftest all-eight-names-emit-their-wrapper
  (doseq [[nm needle] [["note" "<div class=\"custom-block note\">"]
                       ["tip" "<div class=\"custom-block tip\">"]
                       ["warning" "<div class=\"custom-block warning\">"]
                       ["danger" "<div class=\"custom-block danger\">"]
                       ["details" "<details class=\"custom-block details\"><summary>Details</summary>"]
                       ["theorem" "<div class=\"custom-block theorem\"><p class=\"title\">Theorem</p>"]
                       ["right" "<div style=\"text-align:right\">"]
                       ["center" "<div style=\"text-align:center\">"]]]
    (let [out (expand (str "::: " nm "\nbody\n:::\n"))]
      (is (str/includes? out needle) nm)
      (is (str/includes? out (if (= nm "details") "</details>" "</div>")) nm)))
  (is (not (str/includes? (expand "::: right\nx\n:::\n") "custom-block-title")) "right/center carry no title"))

(deftest nesting-with-longer-outer-markers
  (let [out (html ":::: warning Outer\nouter\n::: danger\ninner\n:::\nafter\n::::\n")]
    (is (str/includes? out "<div class=\"custom-block warning\">"))
    (is (re-find #"(?s)custom-block danger.*inner.*</div>.*after.*</div>" out) "inner closes first, outer last")))

(deftest code-fence-immunity
  (doseq [fence ["```" "~~~"]]
    (let [src (str fence "\n::: tip\nliteral\n:::\n" fence "\n")]
      (is (= src (expand src)) (str fence " — nothing inside a code fence is rewritten"))))
  (is (str/includes? (html "```\n::: tip\n:::\n```\n") "::: tip\n:::")))

(deftest a-container-inside-a-list-item-stays-inside-it
  (let [src "- item\n\n  ::: note\n  nested\n  :::\n"
        out (expand src)
        ast (md/parse out)]
    (is (str/includes? out "  <div class=\"custom-block note\">") "the fence's indent is kept")
    (is (str/includes? out "  </div>"))
    (let [li (-> ast :content first :content first)]
      (is (= :list-item (:type li)))
      (is (some #(= :html-block (:type %)) (:content li)) "the html block sits inside the list item"))))

(deftest a-heading-inside-a-container-reaches-the-toc
  (let [src "# T\n\n::: tip\n\n## Inside\n\ntext\n:::\n"
        ast (markdown/parse src {})
        es  (markdown/toc-entries ast)
        out (str (h/html (markdown/->hiccup ast {})))]
    (is (= ["T" "Inside"] (map :text es)))
    (is (str/includes? out "id=\"inside\"") "the TOC id exists as a heading id")
    (is (re-find #"(?s)custom-block tip.*<h2 id=\"inside\"" out) "…and the heading is inside the block")))

(deftest four-space-indent-is-a-code-block-not-a-fence
  (let [src "    ::: tip\n    x\n    :::\n"]
    (is (= src (expand src)))
    (is (str/includes? (html src) "<pre"))))

(deftest unknown-names-warn-and-are-left-alone
  (let [[out ds] (diag/collecting (c/expand "::: bogus\nx\n:::\n" {:from-path "t.md"}))]
    (is (= "::: bogus\nx\n:::\n" out))
    (is (= 1 (count (diag/warnings ds))))
    (is (re-find #"bogus" (:message (first (diag/warnings ds)))))
    (is (= "t.md" (:path (first (diag/warnings ds)))))))

(deftest unclosed-containers-warn-and-auto-close
  (let [[out ds] (diag/collecting (c/expand "::: tip\nopen" {:from-path "t.md"}))]
    (is (str/ends-with? (str/trimr out) "</div>"))
    (is (some #(re-find #"unclosed container `tip`" (:message %)) (diag/warnings ds)))))

(deftest card-list-parses-yaml-and-rewrites-links
  (let [src "::: cardList 2\n```yaml\n- name: A\n  desc: d\n  link: /pages/aaa/\n  avatar: /assets/a.png\n- name: B\n```\n:::\n"
        out (expand src {:from-path "t.md" :rewrite-href #(str "/base" %)})]
    (is (str/includes? out "<div class=\"card-list row-2\">"))
    (is (str/includes? out "href=\"/base/pages/aaa/\"") "every link goes through :rewrite-href")
    (is (str/includes? out "src=\"/base/assets/a.png\"") "…and so do images")
    (is (str/includes? out "<div class=\"name\">A</div><div class=\"desc\">d</div>"))
    (is (str/includes? out "<div class=\"card-item\"><div class=\"name\">B</div></div>") "no link → no anchor")
    (is (not (re-find #"\n\s*\n.*card-list|card-list.*\n\s*\n.*</div>\n\n</div>" out)) "one html block, no blank line inside")))

(deftest card-img-list-and-config-map
  (let [src "::: cardImgList\n```yaml\nconfig:\n  target: _self\n  imgHeight: 120px\n  lineClamp: 2\ndata:\n  - name: N\n    img: /i.png\n    link: /l/\n    author: me\n    avatar: /av.png\n```\n:::\n"
        out (expand src {:from-path "t.md"})]
    (is (str/includes? out "<div class=\"card-img-list row-3\">") "default row count 3")
    (is (str/includes? out "target=\"_self\""))
    (is (str/includes? out "style=\"height:120px\""))
    (is (str/includes? out "-webkit-line-clamp:2"))
    (is (str/includes? out "<div class=\"author\"><img alt=\"\" class=\"avatar\" src=\"/av.png\" /><span>me</span></div>"))))

(deftest malformed-yaml-is-a-structured-warning-and-a-visible-block
  (let [[out ds] (diag/collecting (c/expand "::: cardList\n```yaml\n- name: [a\n  desc: b\n```\n:::\n" {:from-path "bad.md"}))
        ws (diag/warnings ds)]
    (is (= 1 (count ws)))
    (is (= "bad.md" (:path (first ws))))
    (is (re-find #"could not parse the YAML" (:message (first ws))))
    (is (str/includes? out "<div class=\"custom-block danger\">"))
    (is (str/includes? out "invalid YAML"))))

(deftest bad-row-counts-fall-back-to-three
  (let [[out ds] (diag/collecting (c/expand "::: cardList 9\n- name: A\n:::\n" {:from-path "t.md"}))]
    (is (str/includes? out "row-3"))
    (is (some #(re-find #"row count 9" (:message %)) (diag/warnings ds)))))

(deftest text-is-escaped
  (is (str/includes? (expand "::: cardList\n- name: <script>x</script>\n:::\n") "&lt;script&gt;x&lt;/script&gt;")))

(deftest lines-outside-containers-are-byte-identical
  (let [src "# T\n\nplain  \n\n- a\n- b\n\n```clj\n(+ 1 2)\n```\n\ntrailing"]
    (is (= src (expand src)))))

(deftest a-card-list-inside-a-list-item-is-dedented-before-parsing
  (let [src "- item\n\n  ::: cardList\n  ```yaml\n  - name: A\n    link: /l/\n  ```\n  :::\n"
        [out ds] (diag/collecting (c/expand src {:from-path "t.md"}))]
    (is (empty? (diag/warnings ds)) (pr-str (map :message ds)))
    (is (str/includes? out "  <div class=\"card-list row-3\">") "emitted with the item's indent")
    (is (str/includes? out "<div class=\"name\">A</div>"))))
