;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev-round2-test
  "P4-D.2: `bb dev`'s second fix round — new directories in pod mode, a
  `stop!` that waits, `:build :out` from site.edn, the static handler's
  containment and memory, and the wiring mutants that survived D.1.

  Each test names the item it pins down; each failed before its fix."
  (:require [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.dev :as dev]
            [clogem.search :as search]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private article
  "---\ntitle: T\ndate: \"2026-02-01 00:00:00\"\npermalink: /pages/t00001/\n---\n\n## Heading two\n\nPlain body text.\n")

(def ^:private url-site {:site {:title "S" :url "https://s.example"} :content {:write-front-matter false}})

(defn- with-site
  "A temp site with one article and `site-edn`; calls (f dir out)."
  [site-edn f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-r2"})]
    (try
      (spit (fs/file dir "site.edn") (pr-str site-edn))
      (fs/create-dirs (fs/path dir "content" "01.Guide"))
      (spit (fs/file dir "content" "01.Guide" "01.t.md") article)
      (binding [search/*env* {}]
        (f dir (fs/path dir "dist")))
      (finally (fs/delete-tree dir)))))

(defn- wait-until
  "Poll `pred` every 10 ms for up to `ms`; its last value."
  [pred & [ms]]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (or (pred)
          (if (> (- (System/currentTimeMillis) t0) (or ms 5000))
            (pred)
            (do (Thread/sleep 10) (recur)))))))

(defn- spec-of [v] (:spec (:org.babashka/cli (meta v))))

(defn- parse
  "`args` parsed as `bb <task>` parses them: babashka.cli with the task's
  own spec, defaults included."
  [v args]
  (bcli/parse-opts args {:spec (spec-of v)}))

(def ^:private big-size
  "Over `dev/stream-threshold` (1 MB)."
  (+ 10 (* 1024 1024)))

(defmacro ^:private late
  "A clogem.dev var resolved when the test runs, so a test of something new
  compiles — and fails — against the code before it."
  [sym]
  `(fn [& args#] (apply @(or (resolve '~(symbol "clogem.dev" (name sym)))
                             (throw (ex-info ~(str "no clogem.dev/" sym) {})))
                        args#)))

(defn- quiet [f]
  (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (f)))

;; ---------------------------------------------------------------------------
;; Item 5: `:build :out` in site.edn is honoured

(deftest build-out-in-site-edn-is-honoured-through-the-cli-s-own-options
  (with-site (assoc url-site :build {:out "public"})
    (fn [dir _]
      (testing "no --out: site.edn's :build :out"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
        (is (not (fs/exists? (fs/path dir "dist")))))
      (testing "--out wins over site.edn"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write" "--out" "given"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "given" "pages" "t00001" "index.html"))))
      (testing "dev's and clean's specs agree"
        (is (nil? (:out (parse #'cli/dev []))))
        (is (nil? (:out (parse #'cli/clean []))))
        (quiet #(cli/clean (assoc (parse #'cli/clean []) :site-dir (str dir))))
        (is (not (fs/exists? (fs/path dir "public"))) "clean removes site.edn's :build :out"))
      (testing "dev serves site.edn's :build :out"
        (let [servers (atom [])
              h (quiet #(dev/start-dev!
                         (assoc (parse #'cli/dev ["--poll" "--no-write"]) :site-dir (str dir))
                         {:run-server! (fn [handler o] (swap! servers conj handler) (fn [& _] nil))
                          :poll-watch! (fn [& _] nil)
                          :notify! (fn [_]) :notify-error! (fn [_])}))]
          (try
            (is (fs/exists? (fs/path dir "public" "pages" "t00001" "index.html")))
            (is (= 200 (:status ((first @servers) {:uri "/pages/t00001/"}))))
            (finally (quiet (:stop! h)))))))
    )
  (with-site url-site
    (fn [dir _]
      (testing "neither: dist"
        (quiet #(cli/build (assoc (parse #'cli/build ["--no-write"]) :site-dir (str dir))))
        (is (fs/exists? (fs/path dir "dist" "pages" "t00001" "index.html")))))))

(deftest serve-s-dir-keeps-its-default
  (is (= "dist" (:dir (parse #'cli/serve []))))
  (is (= "x" (:dir (parse #'cli/serve ["--dir" "x"])))))

;; ---------------------------------------------------------------------------
;; Items 6, 7, 8, 16, 17: the static handler

(defn- with-root [f]
  (let [root (fs/create-temp-dir {:prefix "clogem-r2root"})]
    (try (f root) (finally (fs/delete-tree root)))))

(deftest a-big-file-is-mapped-not-read-into-the-heap
  (testing "item 6: an InputStream body made http-kit read the whole file
            before its first byte"
    (with-root
      (fn [root]
        (spit (fs/file root "small.txt") "small")
        (with-open [o (java.io.FileOutputStream. (fs/file root "big.bin"))]
          (.write o (byte-array big-size (byte 7))))
        (let [h (dev/make-handler root {})]
          (is (bytes? (:body (h {:uri "/small.txt"}))) "small: read whole, from one open")
          (is (= "small" (String. ^bytes (:body (h {:uri "/small.txt"})) "UTF-8")))
          (is (instance? java.nio.ByteBuffer (:body (h {:uri "/big.bin"}))) "big: a mapping")
          (is (not-any? #(instance? java.io.File (:body (h {:uri %}))) ["/small.txt" "/big.bin"]) "never a File")
          (testing "and http-kit sends both, whole"
            (let [srv (dev/run-server! h {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
              (try
                (let [port (org.httpkit.server/server-port srv)
                      get! (fn [u] (with-open [in (.openStream (java.net.URL. (str "http://127.0.0.1:" port u)))]
                                     (alength (.readAllBytes in))))]
                  (is (= 5 (get! "/small.txt")))
                  (is (= big-size (get! "/big.bin"))))
                (finally (org.httpkit.server/server-stop! srv))))))))))

(deftest a-vanished-file-never-puts-a-path-in-a-response
  (testing "item 6 keeps finding 1: big or small, a file gone between the
            check and the open is a 404, and anything thrown a pathless 500"
    (with-root
      (fn [root]
        (let [h (dev/make-handler root {:inject-reload? true :state (atom {:ever-ok? true})})]
          (doseq [ghost [(fs/path root "gone.bin") (fs/path root "gone.html")]]
            (with-redefs [dev/resolve-path (fn [& _] ghost)]
              (let [r (h {:uri "/x"})]
                (is (= 404 (:status r)))
                (is (not (str/includes? (str (:body r)) (str root))))))))))))

(deftest an-index-html-link-cannot-escape-the-root
  (testing "item 7: only the directory was checked, never its index.html"
    (with-root
      (fn [root]
        (let [dist    (fs/path root "dist")
              outside (fs/path root "outside")]
          (fs/create-dirs (fs/path dist "idxdir"))
          (fs/create-dirs (fs/path dist ".pagefind-prev-1-1-1"))
          (fs/create-dirs outside)
          (spit (fs/file outside "secret.html") "<html><body>SECRET</body></html>")
          (fs/create-sym-link (fs/path dist "idxdir" "index.html") (fs/path outside "secret.html"))
          (fs/create-sym-link (fs/path dist "index.html") (fs/path outside "secret.html"))
          (fs/create-dirs (fs/path dist "pagefind"))
          (fs/create-sym-link (fs/path dist ".pagefind-prev-1-1-1" "x.pf_meta") (fs/path outside "secret.html"))
          (let [h (dev/make-handler dist {:inject-reload? true :state (atom {:ever-ok? true})})]
            (doseq [u ["/idxdir/" "/idxdir" "/idxdir/index.html" "/" "/pagefind/x.pf_meta"]]
              (let [r (h {:uri u})]
                (is (= 404 (:status r)) u)
                (is (not (str/includes? (str (:body r)) "SECRET")) u))))
          (testing "a link that stays inside is still served"
            (spit (fs/file dist "real.html") "<html><body>inside</body></html>")
            (fs/create-dirs (fs/path dist "linked"))
            (fs/create-sym-link (fs/path dist "linked" "index.html") (fs/path dist "real.html"))
            (is (= 200 (:status ((dev/make-handler dist {}) {:uri "/linked/"}))))))))))

(deftest a-request-path-is-refused-before-the-filesystem-sees-it
  (testing "item 8: `\\\\host\\share` reached fs/exists? before the
            containment check (an SMB stat on Windows)"
    (with-root
      (fn [root]
        (spit (fs/file root "ok.txt") "ok")
        (let [h     (dev/make-handler root {})
              calls (atom [])
              orig-exists? fs/exists?
              orig-canon   fs/canonicalize]
          (with-redefs [fs/exists?       (fn [p & r] (swap! calls conj (str p)) (apply orig-exists? p r))
                        fs/canonicalize  (fn [p & r] (swap! calls conj (str p)) (apply orig-canon p r))
                        fs/directory?    (fn [p & r] (swap! calls conj (str p)) false)
                        fs/regular-file? (fn [p & r] (swap! calls conj (str p)) false)]
            (doseq [u ["/%5C%5Cattacker.example%5Cshare%5Cx" "/a%5Cb" "/C:/Windows/win.ini" "/c%3A%5Cx"
                       "/x%3Astream" "/%00" "/../../etc/passwd" "/a/%2E%2E/%2E%2E/b"]]
              (reset! calls [])
              (is (#{400 404} (:status (h {:uri u}))) u)
              (is (not-any? #(re-find #"attacker|share|Windows|passwd|stream|\\\\" %) @calls)
                  (str u " touched " (pr-str @calls))))))
        (is (= 200 (:status ((dev/make-handler root {}) {:uri "/a/../ok.txt"}))) "a `..` that stays inside")))))

(deftest the-404-escapes-the-base-as-an-attribute
  (testing "item 16: escape-html left `\"` in an href"
    (with-root
      (fn [root]
        (let [r (:body ((dev/make-handler root {:base "/a\"onmouseover=\"x/"}) {:uri "/nope"}))]
          (is (str/includes? r "href=\"/a&quot;onmouseover=&quot;x/\"") r)
          (is (not (str/includes? r "a\"onmouseover")) r))))))

(deftest a-loopback-server-answers-only-loopback-names
  (testing "item 17: DNS rebinding — any Host was answered"
    (with-root
      (fn [root]
        (spit (fs/file root "index.html") "<html><body>x</body></html>")
        (let [h (dev/make-handler root {:check-host? true})]
          (doseq [host ["localhost" "localhost:1888" "127.0.0.1:1888" "127.0.0.1" "[::1]:1888" "[::1]" "LOCALHOST:9" nil]]
            (is (= 200 (:status (h {:uri "/" :headers (if host {"host" host} {})}))) (str host)))
          (doseq [host ["evil.example" "evil.example:1888" "localhost.evil.example" "127.0.0.1.nip.io:1888" "[::2]:1"]]
            (let [r (h {:uri "/" :headers {"host" host}})]
              (is (= 403 (:status r)) host)
              (is (str/starts-with? (:body r) "403 Forbidden")))))
        (testing "another --host: no check"
          (is (= 200 (:status ((dev/make-handler root {}) {:uri "/" :headers {"host" "evil.example"}})))))
        (testing "which binds count as loopback"
          (is (every? (late loopback-ip?) ["127.0.0.1" "::1" "localhost" "127.0.0.2"]))
          (is (not-any? (late loopback-ip?) ["0.0.0.0" "::" "192.168.1.2" "example.com"])))
        (testing "both servers pass it"
          (with-site url-site
            (fn [dir _]
              (let [servers (atom [])
                    h (quiet #(dev/start-dev! {:site-dir (str dir) :poll true :no-write true}
                                              {:run-server! (fn [handler o] (swap! servers conj handler) (fn [& _] nil))
                                               :poll-watch! (fn [& _] nil)
                                               :notify! (fn [_]) :notify-error! (fn [_])}))]
                (try
                  (is (= 403 (:status ((first @servers) {:uri "/" :headers {"host" "evil.example"}}))))
                  (finally (quiet (:stop! h)))))))
          (with-redefs [dev/run-server! (fn [handler _] (fn [& _] handler))]
            (let [srv (quiet #(dev/start-serve! {:dir (str root)}))]
              (is (= 403 (:status ((srv) {:uri "/" :headers {"host" "evil.example"}})))))))))))
