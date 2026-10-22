;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.static-test
  "Phase 4 Task F, §11.3 item 15: the static directory — `:content
  :static-dir`, copied as it is to the root of the output directory — its
  checks, its collisions, the sweeps around it, and `bb dev` watching it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.dev :as dev]
            [clogem.diag :as diag]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading\n\nBody.\n")

(def ^:private site-edn
  {:site {:title "S" :url "https://s.example"} :content {:static-dir "public" :write-front-matter false}})

(def ^:private static-files
  "What a site owner puts in public/: a custom domain, a favicon, both
  search consoles' files, an IndexNow key, Jekyll's opt-out and a
  .well-known file, plus a nested one."
  {"CNAME"                       "docs.example\n"
   "favicon.ico"                 "\u0000\u0001ico"
   "google0123456789abcdef.html" "google-site-verification: google0123456789abcdef.html"
   "BingSiteAuth.xml"            "<?xml version=\"1.0\"?><users><user>B</user></users>"
   "0123456789abcdef0123456789abcdef.txt" "0123456789abcdef0123456789abcdef"
   ".nojekyll"                   ""
   ".well-known/security.txt"    "Contact: mailto:security@s.example\n"
   "sub/x.txt"                   "x"})

(defn- with-site
  "A temp site with one article, `edn` as its site.edn and `files`
  ({rel → content}) in public/; calls (f dir out)."
  [edn files f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-static"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (doseq [[rel s] files
              :let [p (fs/path dir "public" rel)]]
        (fs/create-dirs (fs/parent p))
        (spit (fs/file p) s))
      (f dir (fs/path dir "dist"))
      (finally (fs/delete-tree dir)))))

(defn- build!
  "Build `dir` into `out`: [what it printed, its diagnostics]."
  [dir out & [opts]]
  (let [sink (atom [])]
    (binding [diag/*sink* sink *err* (java.io.StringWriter.)]
      [(with-out-str (cli/build (merge {:site-dir (str dir) :out (str out) :no-write true :no-search true} opts)))
       @sink])))

(defn- build-error
  "The ExceptionInfo `build` raises, or nil."
  [dir out & [opts]]
  (try (build! dir out opts) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- doctor-errors
  "The error messages `doctor` reports for `dir`."
  [dir]
  (binding [*err* (java.io.StringWriter.)]
    (try (with-out-str (cli/doctor {:site-dir (str dir)})) []
         (catch clojure.lang.ExceptionInfo e (mapv :message (:clogem/errors (ex-data e)))))))

(defn- tree
  "Every file under `dir` → [its bytes, its mtime], dotfiles included."
  [dir]
  (into (sorted-map)
        (for [p (fs/glob dir "**" {:hidden true})
              :when (fs/regular-file? p)]
          [(str/replace (str (fs/relativize dir p)) "\\" "/")
           [(vec (fs/read-all-bytes p)) (fs/file-time->millis (fs/last-modified-time p))]])))

(defn- bytes-of [dir rel] (vec (fs/read-all-bytes (fs/path dir rel))))

;; ---------------------------------------------------------------------------
;; The config check (item 1)

(defn- load-site [dir & [overrides]]
  (diag/collecting (config/load-config (str dir) nil overrides)))

(deftest a-bad-static-dir-warns-and-is-off
  (with-site site-edn {"CNAME" "c"}
    (fn [dir out]
      (doseq [[v re] [["content" #"is the content directory"]
                      ["assets" #"is the assets directory"]
                      ["i18n" #"is the i18n directory"]
                      ["overrides" #"is the overrides directory"]
                      [".git" #"is inside a \.git directory"]
                      ["content/static" #"is inside the content directory"]
                      ["../x" #"outside the site directory"]
                      ["/abs" #"is absolute"]
                      ["." #"is the site directory itself"]
                      ["" #"must be a directory name"]
                      [7 #"must be a directory name"]
                      ["dist" #"is the output directory"]
                      ["dist/public" #"is inside the output directory"]]]
        (spit (fs/file dir "site.edn") (pr-str (assoc-in site-edn [:content :static-dir] v)))
        (let [[cfg ds] (load-site dir)]
          (is (empty? (diag/errors ds)) (pr-str v))
          (is (= 1 (count (diag/warnings ds))) (pr-str v (diag/warnings ds)))
          (is (re-find re (:message (first (diag/warnings ds)))) (pr-str v))
          (is (str/ends-with? (:message (first (diag/warnings ds))) "; nothing is copied from it.") (pr-str v))
          (is (nil? (config/static-dir cfg)) (pr-str v)))
        (testing "and the build copies nothing"
          (when (string? v)
            (build! dir out)
            (is (not (fs/exists? (fs/path out "CNAME"))) (pr-str v)))))
      (testing "a directory that would hold the output directory"
        (spit (fs/file dir "site.edn") (pr-str (assoc-in site-edn [:content :static-dir] "web")))
        (let [[cfg ds] (load-site dir {:build {:out "web/dist"}})]
          (is (re-find #"contains the output directory" (:message (first (diag/warnings ds)))))
          (is (nil? (config/static-dir cfg)))))
      (testing "a static directory that is a file"
        (spit (fs/file dir "notadir") "x")
        (spit (fs/file dir "site.edn") (pr-str (assoc-in site-edn [:content :static-dir] "notadir")))
        (is (re-find #"which is not a directory" (:message (first (diag/warnings (second (load-site dir)))))))))))

(deftest a-good-static-dir-is-silent-and-need-not-exist-yet
  (with-site site-edn {}
    (fn [dir _]
      (let [[cfg ds] (load-site dir)]
        (is (empty? ds) (pr-str ds))
        (is (= (str (fs/path dir "public")) (str (config/static-dir cfg))))))))

(deftest the-same-directory-as-out-warns-and-builds-as-before
  (with-site site-edn {"CNAME" "c"}
    (fn [dir _]
      (let [out (fs/path dir "public")
            [_ ds] (load-site dir {:build {:out "public"}})]
        (is (= [":content :static-dir (\"public\") is the output directory; nothing is copied from it."]
               (mapv :message (diag/warnings ds))))
        (is (empty? (diag/errors ds)))
        (let [[printed] (build! dir out)]
          (is (re-find #"→ .*public" printed))
          (is (fs/exists? (fs/path out "pages" "t00001" "index.html")))
          (is (= "c" (slurp (fs/file out "CNAME"))) "a file already there stays, as on main"))))))

(deftest without-static-dir-public-is-ignored
  (with-site (update site-edn :content dissoc :static-dir) static-files
    (fn [dir out]
      (build! dir out)
      (is (fs/exists? (fs/path out "index.html")))
      (doseq [rel (keys static-files)]
        (is (not (fs/exists? (fs/path out rel))) rel)))))

;; ---------------------------------------------------------------------------
;; The copy (item 4)

(deftest static-files-land-byte-identical-at-the-root
  (doseq [base [nil "/my docs/"]]
    (testing (str "base " (or base "/"))
      (with-site site-edn static-files
        (fn [dir out]
          (let [[printed ds] (build! dir out (when base {:base base}))]
            (is (empty? (diag/errors ds)))
            (is (re-find #"\(\d+ written, 0 unchanged\)" printed) printed)
            (doseq [rel (keys static-files)]
              (is (= (bytes-of (fs/path dir "public") rel) (bytes-of out rel)) rel)))
          (testing "a second build writes none of them, and they survive"
            (let [before (tree out)
                  [printed] (build! dir out (when base {:base base}))]
              (is (re-find #"\(0 written, \d+ unchanged\)" printed) printed)
              (is (= before (tree out)))))
          (when base
            (testing "bb serve --base serves them below the base"
              (let [h (dev/make-handler out {:base base})
                    r (h {:uri "/my%20docs/CNAME" :request-method :get :headers {"host" "localhost"}})]
                (is (= 200 (:status r)))
                (is (= "docs.example\n" (slurp (:body r))))
                (is (= 200 (:status (h {:uri "/my%20docs/.well-known/security.txt" :request-method :get
                                         :headers {"host" "localhost"}}))))))))))))

(deftest a-deleted-static-html-file-is-swept-and-a-cname-stays
  (with-site site-edn static-files
    (fn [dir out]
      (build! dir out)
      (is (fs/exists? (fs/path out "google0123456789abcdef.html")))
      (fs/delete (fs/path dir "public" "google0123456789abcdef.html"))
      (fs/delete (fs/path dir "public" "CNAME"))
      (let [[printed] (build! dir out)]
        (is (re-find #"removed 1 stale page" printed) printed))
      (is (not (fs/exists? (fs/path out "google0123456789abcdef.html"))) "an .html file the build no longer writes")
      (is (= "docs.example\n" (slurp (fs/file out "CNAME"))) "any other file stays until `bb clean`"))))

(deftest static-files-are-never-swept-while-they-are-there
  (with-site site-edn static-files
    (fn [dir out]
      (build! dir out)
      (build! dir out)
      (build! dir out)
      (is (fs/exists? (fs/path out "google0123456789abcdef.html")))
      (is (fs/exists? (fs/path out ".nojekyll"))))))

(deftest git-and-linked-directories-are-not-copied
  (with-site site-edn {"CNAME" "c" ".git/HEAD" "ref: refs/heads/main" "sub/.git" "gitdir: x"}
    (fn [dir out]
      (let [outside (fs/create-temp-dir {:prefix "clogem-outside"})]
        (try
          (spit (fs/file outside "secret.txt") "secret")
          (fs/create-sym-link (fs/path dir "public" "linked") outside)
          (build! dir out)
          (is (fs/exists? (fs/path out "CNAME")))
          (is (not (fs/exists? (fs/path out ".git"))))
          (is (not (fs/exists? (fs/path out "sub" ".git"))))
          (is (not (fs/exists? (fs/path out "linked"))) "a linked directory is not descended")
          (finally (fs/delete-tree outside)))))))

;; ---------------------------------------------------------------------------
;; Collisions (item 4)

(def ^:private collisions
  "[public/ files, what the error must say]: one per collision kind."
  [[{"robots.txt" "User-agent: *"}
    #"the static file public/robots.txt and the generated /robots.txt would both be written to dist/robots.txt\. To ship your own robots.txt, put it in the assets directory: assets/robots.txt already replaces the generated one\."]
   [{"index.html" "<p>home</p>"}
    #"the static file public/index.html and the page / would both be written to dist/index.html\. Rename or move public/index.html\."]
   [{"Pages/T00001/index.html" "x"}
    #"the static file public/Pages/T00001/index.html and the page /pages/t00001/ \(from content/01.Guide/01.t.md\) would both be written"]
   [{"feed.xml" "x"}
    #"the static file public/feed.xml and the Atom feed /feed.xml would both be written to dist/feed.xml"]
   [{"sitemap.xml" "x"}
    #"the static file public/sitemap.xml and the generated /sitemap.xml would both be written"]
   [{"clogem/js/mode.js" "x"}
    #"the static file public/clogem/js/mode.js would be written to dist/clogem/js/mode.js, inside clogem/, which holds the theme's own files\. Rename or move public/clogem/js/mode.js\."]
   [{"assets/logo.png" "x"}
    #"the static file public/assets/logo.png would be written to dist/assets/logo.png, inside assets/, which holds the site's assets\. Put it in the assets directory \(:content :assets-dir\) instead\."]
   [{"pagefind/x.js" "x"}
    #"would be written to dist/pagefind/x.js, inside pagefind/, which the search step replaces whole"]
   [{".clogem-output" "x"}
    #"the static file public/\.clogem-output would be written to dist/\.clogem-output, on \.clogem-output, the file that marks the output directory as the build's"]
   [{"deep/a.clogem-tmp-1-2" "x"}
    #"on a name containing \.clogem-tmp-, which the build gives its temp files and deletes"]
   [{".pagefind-prev-1-2-3/x" "x"}
    #"on a \.pagefind-… name, which the search step uses for its own directories"]
   [{"categories" "x"}
    #"/categories/ would put a page inside the static file public/categories: dist/categories cannot be both a file and a directory\. Rename or move public/categories\."]])

(deftest every-collision-is-an-error-before-anything-is-written
  (doseq [[files re] collisions]
    (testing (pr-str (keys files))
      (with-site site-edn {}
        (fn [dir out]
          (build! dir out)
          (doseq [[rel s] files
                  :let [p (fs/path dir "public" rel)]]
            (fs/create-dirs (fs/parent p))
            (spit (fs/file p) s))
          (let [before (tree out)
                e      (build-error dir out)]
            (is (some? e))
            (is (= 1 (:babashka/exit (ex-data e))))
            (is (re-find re (ex-message e)) (ex-message e))
            (is (= before (tree out)) "dist/ is untouched"))
          (is (some #(re-find re %) (doctor-errors dir)) "doctor reports it too"))))))

(deftest the-asset-check-holds-without-search
  (testing "pagefind/ is the search step's even with search off"
    (with-site (assoc site-edn :search {:provider :none}) {"pagefind/x" "x"}
      (fn [dir out]
        (is (some? (build-error dir out)))))))

(deftest case-folding-only-applies-to-pairs-with-a-static-file
  (testing "two site assets that differ only in case build as on main"
    (with-site site-edn {}
      (fn [dir out]
        (fs/create-dirs (fs/path dir "assets"))
        (spit (fs/file dir "assets" "Logo.png") "a")
        (spit (fs/file dir "assets" "logo.png") "b")
        (let [[_ ds] (build! dir out)]
          (is (empty? (diag/errors ds))))
        (is (empty? (doctor-errors dir))))))
  (testing "a static file that differs from a page only in case is a collision"
    (with-site site-edn {"INDEX.HTML" "x"}
      (fn [dir out]
        (is (re-find #"public/INDEX.HTML and the page / would both be written" (ex-message (build-error dir out))))))))

(deftest a-static-file-beside-generated-ones-is-fine
  (with-site site-edn {"pages/t00001/extra.txt" "x" "categories.txt" "x" "Feed.xml.bak" "x"}
    (fn [dir out]
      (let [[_ ds] (build! dir out)]
        (is (empty? (diag/errors ds)))
        (is (= "x" (slurp (fs/file out "pages" "t00001" "extra.txt"))))))))

;; ---------------------------------------------------------------------------
;; bb dev watches it (item 5)

(defn- spec-for [specs p]
  (some #(when (= (str (fs/normalize (fs/absolutize p))) (:path %)) %) specs))

(deftest dev-watches-the-static-dir-only-when-it-is-on
  (with-site site-edn {}
    (fn [dir _]
      (let [cfg (first (load-site dir))]
        (is (= {:path (str (fs/normalize (fs/absolutize (fs/path dir "public")))) :recursive? true}
               (spec-for (dev/watch-specs cfg) (fs/path dir "public"))))
        (fs/delete-tree (fs/path dir "public"))
        (is (some? (spec-for (dev/watch-specs cfg) (fs/path dir "public"))) "before it exists, as overrides/ is")
        (is (not-any? #{(str (fs/path dir "public"))} (map str (dev/watched-paths cfg)))
            "polling skips what does not exist yet")
        (fs/create-dirs (fs/path dir "public"))
        (is (some #{(str (fs/path dir "public"))} (map str (dev/watched-paths cfg))) "and polls it once it does"))
      (doseq [edn [(update site-edn :content dissoc :static-dir)
                   (assoc-in site-edn [:content :static-dir] "content")]]
        (spit (fs/file dir "site.edn") (pr-str edn))
        (let [cfg (first (load-site dir))]
          (is (nil? (spec-for (dev/watch-specs cfg) (fs/path dir "public"))))
          (is (not-any? #{(str (fs/path dir "public"))} (map str (dev/watched-paths cfg)))))))))

(defn- wait-until
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(deftest a-static-edit-reaches-dist-and-reloads
  (testing "the fake pod: an event in public/ rebuilds, copies and reloads"
    (with-site site-edn {"CNAME" "one"}
      (fn [dir out]
        (let [cb (atom nil) specs (atom nil) reloads (atom []) log (java.io.StringWriter.)
              pod (fn [_cfg on-change {:keys [accept? on-ready] :as o}]
                    (reset! specs (:specs o))
                    (reset! cb {:on-change on-change :accept? accept?})
                    (on-ready (fn [spec] {:path (:path spec)}) {:unwatch! (fn [_]) :watchers {}})
                    true)
              event! (fn [p]
                       (let [{:keys [on-change accept?]} @cb]
                         (when (accept? (str p)) (on-change (with-meta [(str p)] {:type :write})))))
              h (binding [*out* log *err* log]
                  (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true}
                                  {:run-server! (fn [& _] (fn [& _] nil))
                                   :try-pod-watch! pod
                                   :poll-watch! (fn [& _] nil)
                                   :follow-ms 50
                                   :notify! #(swap! reloads conj %) :notify-error! (fn [_])}))]
          (try
            (is (wait-until #(= "one" (try (slurp (fs/file out "CNAME")) (catch Exception _ nil)))) (str log))
            (is (some? (spec-for @specs (fs/path dir "public"))) "the pod is asked to watch it")
            (let [n0 (count @reloads)]
              (spit (fs/file dir "public" "CNAME") "two")
              (spit (fs/file dir "public" "new.txt") "new")
              (event! (fs/path dir "public" "CNAME"))
              (event! (fs/path dir "public" "new.txt"))
              (is (wait-until #(and (= "two" (slurp (fs/file out "CNAME"))) (fs/exists? (fs/path out "new.txt")))) (str log))
              (is (wait-until #(> (count @reloads) n0)) "and the browser is told to reload"))
            (finally (binding [*out* log *err* log] ((:stop! h)))))))))
  (testing "the real pod, when CLOGEM_FSWATCHER names one"
    (if-not (System/getenv "CLOGEM_FSWATCHER")
      (println "a-static-edit-reaches-dist-and-reloads: CLOGEM_FSWATCHER is not set; the real pod is skipped")
      (with-site site-edn {"CNAME" "one"}
        (fn [dir out]
          (let [reloads (atom []) log (java.io.StringWriter.)
                h (binding [*out* log *err* log]
                    (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true}
                                    {:run-server! (fn [& _] (fn [& _] nil))
                                     :notify! #(swap! reloads conj %) :notify-error! (fn [_])}))]
            (try
              (is (wait-until #(str/includes? (str log) "watching via fswatcher") 30000) (str log))
              (let [n0 (count @reloads)]
                (spit (fs/file dir "public" "CNAME") "two")
                (spit (fs/file dir "public" "new.txt") "new")
                (is (wait-until #(and (= "two" (slurp (fs/file out "CNAME"))) (fs/exists? (fs/path out "new.txt"))) 10000)
                    (str log))
                (is (wait-until #(> (count @reloads) n0)) "and the browser is told to reload"))
              (finally (binding [*out* log *err* log] ((:stop! h)))))))))))

(deftest polling-sees-the-static-dir-s-dotfiles
  (testing "P4-F.1 item 1: the poller's snapshot globbed without
            {:hidden true}, so an edit to .nojekyll or .well-known/ never
            rebuilt"
    (with-site site-edn {"CNAME" "c" ".nojekyll" "" ".well-known/x" "one"}
      (fn [dir out]
        (let [log (java.io.StringWriter.)
              h   (binding [*out* log *err* log]
                    (dev/start-dev! {:site-dir (str dir) :port 1888 :no-write true :no-search true
                                     :poll true :interval 50}
                                    {:run-server! (fn [& _] (fn [& _] nil))
                                     :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (wait-until #(str/includes? (str log) "watching by polling") 10000) (str log))
            (is (wait-until #(= "one" (try (slurp (fs/file out ".well-known" "x")) (catch Exception _ nil)))) (str log))
            ;; past the first snapshot, and a new mtime
            (Thread/sleep 300)
            (spit (fs/file dir "public" ".nojekyll") "jekyll")
            (is (wait-until #(= "jekyll" (slurp (fs/file out ".nojekyll"))) 10000) (str log))
            (spit (fs/file dir "public" ".well-known" "x") "two")
            (is (wait-until #(= "two" (slurp (fs/file out ".well-known" "x"))) 10000) (str log))
            (finally (binding [*out* log *err* log] ((:stop! h))))))))))

(deftest a-new-directory-s-batch-holds-its-dotfiles-but-not-git
  (with-site site-edn {"d/.well-known/x" "x" "d/.nojekyll" "" "d/a~" "" "d/.git/HEAD" "h"}
    (fn [dir _]
      (let [d (fs/path dir "public" "d")]
        (is (= #{"" ".well-known/x" ".nojekyll"}
               (set (map #(str/replace (str (fs/relativize d %)) "\\" "/") (dev/new-dir-batch d)))))))))
