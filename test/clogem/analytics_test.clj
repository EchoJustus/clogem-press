;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.analytics-test
  "Phase 4 Task F, §11.3 item 14 (analytics) and the verification half of
  item 15: the `:analytics` and `:seo :verification` checks, the loader tag
  and the file it loads. The loader's behaviour in a browser — the host
  and DNT gates, GA4's consent order — is test/browser/analytics.test.mjs."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.theme.layout :as layout]))

(def demo "examples/demo-site")

(def ^:private url {:site {:url "https://s.example"}})

(def ^:private uuid "94db1cb1-74f4-4a40-ad6c-962362670409")

(defn- load-site
  "Load `edn` as a temp site's site.edn: [cfg diagnostics]."
  [edn & [overrides]]
  (let [dir (fs/create-temp-dir {:prefix "clogem-analytics"})]
    (spit (fs/file dir "site.edn") (pr-str edn))
    (try (diag/collecting (config/load-config (str dir) nil overrides))
         (finally (fs/delete-tree dir)))))

(defn- warnings
  "[cfg [warning message …]] for `edn`, asserting there is no error."
  [edn]
  (let [[cfg ds] (load-site edn)]
    (is (empty? (diag/errors ds)) (pr-str (diag/errors ds)))
    [cfg (mapv :message (diag/warnings ds))]))

(defn- off-with
  "Assert `edn` switches analytics off with exactly one warning matching
  `re`, and no error."
  [edn re]
  (let [[cfg ws] (warnings edn)]
    (is (= :none (get-in cfg [:analytics :provider])) (pr-str edn))
    (is (nil? (config/analytics cfg)))
    (is (= 1 (count ws)) (pr-str ws))
    (is (some #(re-find re %) ws) (pr-str ws))))

;; ---------------------------------------------------------------------------
;; Config (item 1)

(deftest none-is-silent-whatever-else-the-map-holds
  (doseq [a [nil {} {:provider :none}
             {:provider :none :id "UA-1" :src "http://x" :init 4 :consent-default :maybe
              :respect-dnt "no" :website-id 1 :domain 2}]]
    (let [[cfg ws] (warnings (cond-> url a (assoc :analytics a)))]
      (is (empty? ws) (pr-str a ws))
      (is (nil? (config/analytics cfg))))))

(deftest the-new-keys-are-known
  (let [[_ ws] (warnings (assoc url :analytics
                                {:provider :plausible :src "https://plausible.io/js/pa-x.js"
                                 :respect-dnt false
                                 :init {:endpoint "https://s.example/api/event" :hash-based-routing true
                                        :outbound-links true :file-downloads {:file-extensions ["pdf"]}
                                        :form-submissions true :auto-capture-pageviews false
                                        :logging false
                                        ;; user data: not walked
                                        :custom-properties {:anything-at-all 1 "x" 2}}}))]
    (is (empty? ws) (pr-str ws)))
  (let [[_ ws] (warnings (assoc url :analytics {:provider :ga4 :id "G-ABC" :consent-default :denied}))]
    (is (empty? ws) (pr-str ws)))
  (testing "an unknown :init option still warns"
    (let [[_ ws] (warnings (assoc url :analytics {:provider :plausible :src "https://p.example/js/pa-x.js"
                                                  :init {:hash-routing true}}))]
      (is (= [":analytics :init :hash-routing is not an analytics init option and is ignored."] ws)))))

(deftest a-bad-provider-is-a-warning-and-off
  (doseq [p ["ga4" :bogus 1]]
    (off-with (assoc url :analytics {:provider p :id "G-ABC"}) #"must be one of :ga4, :plausible, :umami or :none")))

(deftest analytics-needs-the-site-url
  (off-with {:analytics {:provider :ga4 :id "G-ABC"}} #":site :url: the loader runs only on the site's own host")
  (off-with {:site {:url "  "} :analytics {:provider :umami :website-id uuid}} #"needs :site :url"))

(deftest ga4-needs-a-measurement-id
  (let [cfg (first (warnings (assoc url :analytics {:provider :ga4 :id "G-ABC123"})))]
    (is (= {:provider :ga4 :id "G-ABC123" :src "https://www.googletagmanager.com/gtag/js" :respect-dnt true}
           (config/analytics cfg))))
  (off-with (assoc url :analytics {:provider :ga4 :id "UA-12345-1"})
            #"a Universal Analytics id: Universal Analytics no longer collects data")
  (doseq [id [nil "" "g-abc" "G-" "G-abc" "AW-123" 123]]
    (off-with (assoc url :analytics {:provider :ga4 :id id}) #"GA4 needs a measurement id")))

(deftest plausible-legacy-or-new-is-decided-by-domain
  (testing "with :domain: the legacy script, at plausible.io unless :src says otherwise"
    (let [cfg (first (warnings (assoc url :analytics {:provider :plausible :domain "s.example"})))]
      (is (= "https://plausible.io/js/script.js" (:src (config/analytics cfg)))))
    (let [cfg (first (warnings (assoc url :analytics {:provider :plausible :domain "s.example"
                                                      :src "https://s.example/js/script.js"})))]
      (is (= "https://s.example/js/script.js" (:src (config/analytics cfg))))))
  (testing ":init beside :domain is ignored, with a warning — analytics stays on"
    (let [[cfg ws] (warnings (assoc url :analytics {:provider :plausible :domain "s.example"
                                                    :init {:logging true}}))]
      (is (= :plausible (:provider (config/analytics cfg))))
      (is (= [(str ":analytics :init is read only by Plausible's new script, and :domain selects the "
                   "legacy one; it is ignored.")] ws))))
  (testing "without :domain: the new script, whose :src is required — a proxied pa- script too"
    (let [cfg (first (warnings (assoc url :analytics {:provider :plausible
                                                      :src "https://s.example/stats/pa-XYZ.js"})))]
      (is (= :plausible (:provider (config/analytics cfg)))))
    (off-with (assoc url :analytics {:provider :plausible}) #"needs either :src")
    (off-with (assoc url :analytics {:provider :plausible :src "https://p.example/pa.js" :init [1]})
              #":init is \[1\], but it must be a map"))
  (off-with (assoc url :analytics {:provider :plausible :domain ""}) #":domain is \"\""))

(deftest umami-needs-a-website-uuid
  (let [cfg (first (warnings (assoc url :analytics {:provider :umami :website-id uuid})))]
    (is (= "https://cloud.umami.is/script.js" (:src (config/analytics cfg)))))
  (doseq [id [nil "abc" (subs uuid 1) 5]]
    (off-with (assoc url :analytics {:provider :umami :website-id id}) #"Umami needs the website's id, a UUID")))

(deftest src-must-be-https
  (doseq [src ["http://plausible.io/js/script.js" "//plausible.io/js/script.js" "/js/script.js" "https://" 7]]
    (off-with (assoc url :analytics {:provider :plausible :domain "s.example" :src src})
              #"is not an https:// URL")))

(deftest consent-default-is-ga4s
  (doseq [[v want] [[:granted :granted] [:denied :denied]]]
    (let [[cfg ws] (warnings (assoc url :analytics {:provider :ga4 :id "G-A" :consent-default v}))]
      (is (empty? ws))
      (is (= want (:consent-default (config/analytics cfg))))))
  (testing "a bad value warns and counts as :denied"
    (let [[cfg ws] (warnings (assoc url :analytics {:provider :ga4 :id "G-A" :consent-default :maybe}))]
      (is (= [":analytics :consent-default is :maybe, but it must be :granted or :denied; using :denied."] ws))
      (is (= :denied (:consent-default (config/analytics cfg))))))
  (testing "for another provider it is ignored, with a warning"
    (let [[cfg ws] (warnings (assoc url :analytics {:provider :umami :website-id uuid :consent-default :denied}))]
      (is (= [":analytics :consent-default is read only by :ga4, not :umami; it is ignored."] ws))
      (is (nil? (:consent-default (config/analytics cfg)))))))

(deftest respect-dnt-is-a-boolean-defaulting-to-true
  (let [dnt (fn [a] (:respect-dnt (config/analytics (first (warnings (assoc url :analytics a))))))]
    (is (true? (dnt {:provider :ga4 :id "G-A"})))
    (is (true? (dnt {:provider :ga4 :id "G-A" :respect-dnt true})))
    (is (false? (dnt {:provider :ga4 :id "G-A" :respect-dnt false}))))
  (off-with (assoc url :analytics {:provider :ga4 :id "G-A" :respect-dnt "false"}) #"must be true or false"))

(deftest the-host-is-the-site-urls
  (doseq [[u host] [["https://S.Example" "s.example"]
                    ["https://user:pw@s.example:8443/" "s.example"]
                    ["https://例え.テスト" "例え.テスト"]
                    ["https://xn--r8jz45g.xn--zckzah/" "xn--r8jz45g.xn--zckzah"]]]
    (is (= host (config/site-host (first (load-site {:site {:url u}})))) u)))

;; ---------------------------------------------------------------------------
;; Verification (item 3, config half)

(deftest verification-takes-strings-or-vectors
  (let [[cfg ws] (warnings {:seo {:verification {:google "g1" :bing ["b1" " b2 " "b1"]}}})]
    (is (empty? ws))
    (is (= {:google ["g1"] :bing ["b1" "b2"]} (get-in cfg [:seo :verification]))))
  (testing "anything else warns and is ignored — never an error, as on 0.2.0"
    (let [[cfg ws] (warnings {:seo {:verification "abc"}})]
      (is (= [(str ":seo :verification is \"abc\", but it must be a map like {:google \"…\" :bing \"…\"}; "
                   "it is ignored.")] ws))
      (is (nil? (get-in cfg [:seo :verification]))))
    (doseq [bad ["" "  " [] [""] ["a" 1] 5 {:x 1}]]
      (let [[cfg ws] (warnings {:seo {:verification {:google bad :bing "ok"}}})]
        (is (= 1 (count ws)) (pr-str bad ws))
        (is (re-find #":seo :verification :google is .*, but it must be a non-blank string" (first ws)))
        (is (= {:bing ["ok"]} (get-in cfg [:seo :verification])))))
    (let [[_ ws] (warnings {:seo {:verification {:yandex "y"}}})]
      (is (= [":seo :verification :yandex is not a seo verification option and is ignored."] ws)))))

;; ---------------------------------------------------------------------------
;; The loader tag (item 2) and the verification tags (item 3)

(defn- demo-copy
  "A temp copy of the demo site with `extra` merged into its site.edn."
  [extra]
  (let [dir (fs/create-temp-dir {:prefix "clogem-analytics-demo"})]
    (fs/copy-tree demo dir)
    (fs/delete-tree (fs/path dir "dist"))
    (spit (fs/file dir "site.edn")
          (pr-str (merge-with merge (config/read-edn-file (fs/path demo "site.edn")) extra)))
    dir))

(defn- build!
  [dir out & [opts]]
  (binding [diag/*sink* (atom []) *err* (java.io.StringWriter.)]
    (with-out-str (cli/build (merge {:site-dir (str dir) :out (str out) :no-write true :no-search true}
                                    opts)))))

(defn- pages [out] (sort (map str (fs/glob out "**.html"))))

(defn- head [html] (subs html 0 (str/index-of html "</head>")))

(defn- loader-tags [html] (re-seq #"<script [^>]*clogem/js/analytics\.js[^>]*></script>" html))

(defn- inline-scripts
  "The page's inline executable scripts: no src, not a JSON data block."
  [html]
  (remove #(or (str/includes? % "src=") (str/includes? % "application/json"))
          (re-seq #"<script\b[^>]*>" html)))

(defn- attrs [tag]
  (into {} (for [[_ k v] (re-seq #"\s(data-[a-z-]+|src|defer)=\"([^\"]*)\"" tag)]
             [k (str/replace v "&quot;" "\"")])))

(def ^:private providers
  {:ga4              [{:provider :ga4 :id "G-ABC123" :consent-default :denied}
                      {"data-provider" "ga4" "data-id" "G-ABC123" "data-consent-default" "denied"
                       "data-src" "https://www.googletagmanager.com/gtag/js"}]
   :plausible        [{:provider :plausible :src "https://plausible.io/js/pa-XyZ.js"
                       :init {:hash-based-routing true :file-downloads {:file-extensions ["pdf" "zip"]}
                              :custom-properties {:my-key "v" "Other_Key" 1}}}
                      {"data-provider" "plausible" "data-src" "https://plausible.io/js/pa-XyZ.js"
                       "data-init" (str "{\"customProperties\":{\"my-key\":\"v\",\"Other_Key\":1},"
                                        "\"fileDownloads\":{\"fileExtensions\":[\"pdf\",\"zip\"]},"
                                        "\"hashBasedRouting\":true}")}]
   :plausible-legacy [{:provider :plausible :domain "clogem-demo.example"}
                      {"data-provider" "plausible" "data-domain" "clogem-demo.example"
                       "data-src" "https://plausible.io/js/script.js"}]
   :umami            [{:provider :umami :website-id uuid :respect-dnt false}
                      {"data-provider" "umami" "data-website-id" uuid "data-respect-dnt" "false"
                       "data-src" "https://cloud.umami.is/script.js"}]})

(deftest every-provider-gets-one-loader-last-in-head
  (doseq [[k [a want]] providers
          base ["/" "/my docs/"]]
    (testing (str k " under base " base)
      (let [dir (demo-copy {:analytics a})
            out (fs/path dir "dist")]
        (try
          (build! dir out {:base base})
          (is (fs/regular-file? (fs/path out "clogem" "js" "analytics.js")))
          (let [ps (pages out)]
            (is (< 100 (count ps)))
            (doseq [p ps
                    :let [html (slurp p)
                          tags (loader-tags html)]]
              (is (= 1 (count tags)) p)
              (is (str/ends-with? (head html) (first tags)) (str p ": the loader is the last child of <head>"))
              (is (= 1 (count (inline-scripts html))) (str p ": the mode script only"))
              (let [at (attrs (first tags))]
                (is (= (merge {"data-host" "clogem-demo.example" "data-respect-dnt" "true" "defer" "defer"} want)
                       (dissoc at "src"))
                    p)
                (is (re-matches (re-pattern (str "\\Q" (if (= "/" base) "/" "/my%20docs/")
                                                 "clogem/js/analytics.js?v=\\E[0-9a-f]{8}"))
                                (get at "src"))
                    "base-prefixed and encoded, and fingerprinted"))))
          (finally (fs/delete-tree dir)))))))

(deftest an-idn-site-url-is-the-data-host
  (let [dir (demo-copy {:site {:url "https://例え.テスト"} :analytics {:provider :umami :website-id uuid}})
        out (fs/path dir "dist")]
    (try
      (build! dir out)
      (is (= "例え.テスト" (get (attrs (first (loader-tags (slurp (fs/file out "index.html"))))) "data-host")))
      (finally (fs/delete-tree dir)))))

(deftest none-ships-no-tag-and-no-file
  (let [dir (demo-copy {})
        out (fs/path dir "dist")
        out2 (fs/path dir "dist2")]
    (try
      (build! dir out)
      (is (not (fs/exists? (fs/path out "clogem" "js" "analytics.js"))))
      (is (not-any? #(str/includes? (slurp %) "analytics") (pages out)))
      (testing ":none with every other key set builds the same bytes"
        (spit (fs/file dir "site.edn")
              (pr-str (assoc (config/read-edn-file (fs/path dir "site.edn"))
                             :analytics {:provider :none :id "G-A" :website-id uuid :init {:logging true}})))
        (build! dir out2)
        (let [files #(into {} (for [f (fs/glob % "**" {:hidden true}) :when (fs/regular-file? f)]
                                [(str (fs/relativize % f)) (vec (fs/read-all-bytes f))]))]
          (is (= (files out) (files out2)))))
      (finally (fs/delete-tree dir)))))

(deftest dev-never-loads-analytics
  (let [dir (demo-copy {:analytics {:provider :ga4 :id "G-ABC123"}})
        out (fs/path dir "dist")]
    (try
      (build! dir out {:clogem/dev? true})
      (is (not (fs/exists? (fs/path out "clogem" "js" "analytics.js"))))
      (is (not-any? #(seq (loader-tags (slurp %))) (pages out)))
      (finally (fs/delete-tree dir)))))

(deftest redirect-stubs-get-neither-tag
  (let [dir (demo-copy {:i18n {:prefix-default? true}
                        :analytics {:provider :ga4 :id "G-ABC123"}
                        :seo {:verification {:google "g-token" :bing "b-token"}}})
        out (fs/path dir "dist")]
    (try
      (build! dir out)
      (let [{stubs true docs false} (group-by #(str/includes? % "http-equiv=\"refresh\"") (map slurp (pages out)))]
        (is (seq stubs))
        (is (seq docs))
        (doseq [s stubs]
          (is (empty? (loader-tags s)))
          (is (not (str/includes? s "verification")))
          (is (not (str/includes? s "msvalidate"))))
        (doseq [d docs]
          (is (= 1 (count (loader-tags d))))))
      (finally (fs/delete-tree dir)))))

(deftest verification-tags-on-every-document-page
  (let [dir (demo-copy {:seo {:verification {:google ["g1" "g\"<2>"] :bing "b&1"}}})
        out (fs/path dir "dist")]
    (try
      (build! dir out {:clogem/dev? true})
      (doseq [p (pages out)
              :let [h (head (slurp p))]]
        (is (= ["<meta content=\"g1\" name=\"google-site-verification\" />"
                "<meta content=\"g&quot;&lt;2&gt;\" name=\"google-site-verification\" />"
                "<meta content=\"b&amp;1\" name=\"msvalidate.01\" />"]
               (re-seq #"<meta [^>]*(?:google-site-verification|msvalidate\.01)[^>]*>" h))
            p)
        (testing "right after the description meta"
          (is (re-find #"<meta content=\"[^\"]*\" name=\"description\" /><meta content=\"g1\"" h) p)))
      (finally (fs/delete-tree dir)))))

(deftest plausible-init-camel-cases-option-names-only
  (is (= {"endpoint" "/e" "hashBasedRouting" true "fileDownloads" {"fileExtensions" ["pdf"]}
          "autoCapturePageviews" false "customProperties" {:my-key 1 "A_b" 2}}
         (layout/plausible-init {:endpoint "/e" :hash-based-routing true
                                 :file-downloads {:file-extensions ["pdf"]}
                                 :auto-capture-pageviews false
                                 :custom-properties {:my-key 1 "A_b" 2}})))
  (is (= "{\"customProperties\":{\"my-key\":1},\"fileDownloads\":true}"
         (json/generate-string (layout/plausible-init {:file-downloads true :custom-properties {:my-key 1}})))))
