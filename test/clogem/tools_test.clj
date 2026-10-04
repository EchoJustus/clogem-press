;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.tools-test
  "The generic tool fetcher (Phase 4 Task A): every descriptor — Pagefind,
  Chroma and the fswatcher pod — fetched, verified, stamped, locked and
  overridden through the same code, offline, against a local server and
  fake archives (`clogem.fake-tools`). 0.2.0's Pagefind tests in
  search-test run unchanged through `clogem.search`'s wrappers."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.cli :as cli]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.fake-tools :as fake]
            [clogem.tools :as tools]
            [org.httpkit.server :as hk]))

(defn- with-dir
  "A temp directory, with `tools/*env*` bound to `env` ({} by default, so
  CI's CLOGEM_PAGEFIND never reaches these tests)."
  [f & {:keys [env] :or {env {}}}]
  (let [dir (fs/create-temp-dir {:prefix "clogem-tools"})]
    (try (binding [tools/*env* env
                   diag/*sink* (atom [])
                   *err* (java.io.StringWriter.)]
           (f dir))
         (finally (fs/delete-tree dir)))))

(defn- serve
  "Serve `archive` at any path on a random local port, answering after
  `delay-ms`; call (f url-template hits)."
  [archive f & {:keys [delay-ms] :or {delay-ms 0}}]
  (let [hits   (atom 0)
        server (hk/run-server (fn [_]
                                (swap! hits inc)
                                (when (pos? delay-ms) (Thread/sleep (long delay-ms)))
                                {:status 200 :body (fs/file archive)})
                              {:port 0 :legacy-return-value? false})]
    (try (f (str "http://127.0.0.1:" (hk/server-port server) "/v{{version}}/{{platform}}") hits)
         (finally (hk/server-stop! server)))))

(defn- cfg-for
  [dir tool url sha & [more]]
  (first (diag/collecting
          (config/load-config (str dir) nil
                              (merge-with merge
                                          {:tools {:cache-dir "cache"
                                                   (:id tool) {:url url :sha256 {(tools/platform tool) sha}}}}
                                          more)))))

(defn- ran [bin] (str/trim (:out (p/shell {:out :string} bin))))

(def ^:private all-tools [tools/pagefind tools/chroma tools/fswatcher])

(defn- for-each-tool
  "(f tool) for every descriptor with a release for this machine."
  [f]
  (doseq [tool all-tools]
    (if (tools/platform tool)
      (testing (:name tool) (f tool))
      (println (str "tools-test: " (:name tool) " has no release for this platform; skipped")))))

;; ---------------------------------------------------------------------------
;; Descriptors

(deftest platforms-map-every-release-asset
  (testing "Chroma: {linux,darwin,windows}-{amd64,arm64}, plus linux-386 and windows-386"
    (is (= "linux-amd64"   (tools/platform tools/chroma "Linux" "amd64")))
    (is (= "linux-arm64"   (tools/platform tools/chroma "Linux" "aarch64")))
    (is (= "linux-386"     (tools/platform tools/chroma "Linux" "i386")))
    (is (= "darwin-amd64"  (tools/platform tools/chroma "Mac OS X" "x86_64")))
    (is (= "darwin-arm64"  (tools/platform tools/chroma "Mac OS X" "aarch64")))
    (is (nil? (tools/platform tools/chroma "Mac OS X" "x86")) "no darwin-386")
    (is (= "windows-amd64" (tools/platform tools/chroma "Windows 11" "amd64")))
    (is (= "windows-arm64" (tools/platform tools/chroma "Windows 11" "aarch64")))
    (is (= "windows-386"   (tools/platform tools/chroma "Windows 10" "x86")))
    (is (nil? (tools/platform tools/chroma "Linux" "riscv64")))
    (is (= "chroma.exe" (tools/binary-name tools/chroma "windows-amd64")))
    (is (= "chroma" (tools/binary-name tools/chroma "linux-amd64")))
    (is (= (set (keys (get-in tools/chroma [:known-sha256 "2.27.0"])))
           #{"darwin-amd64" "darwin-arm64" "linux-386" "linux-amd64" "linux-arm64"
             "windows-386" "windows-amd64" "windows-arm64"})))
  (testing "the fswatcher pod: five assets, no windows-aarch64"
    (is (= "linux-amd64"   (tools/platform tools/fswatcher "Linux" "amd64")))
    (is (= "linux-aarch64" (tools/platform tools/fswatcher "Linux" "aarch64")))
    (is (= "macos-amd64"   (tools/platform tools/fswatcher "Mac OS X" "x86_64")))
    (is (= "macos-aarch64" (tools/platform tools/fswatcher "Mac OS X" "aarch64")))
    (is (= "windows-amd64" (tools/platform tools/fswatcher "Windows 11" "amd64")))
    (is (nil? (tools/platform tools/fswatcher "Windows 11" "aarch64")))
    (is (nil? (tools/platform tools/fswatcher "Linux" "i386")))
    (is (= "pod-babashka-fswatcher.exe" (tools/binary-name tools/fswatcher "windows-amd64")))
    (is (= (set (keys (get-in tools/fswatcher [:known-sha256 "0.0.7"])))
           #{"linux-amd64" "linux-aarch64" "macos-aarch64" "macos-amd64" "windows-amd64"})))
  (testing "every pinned hash is 64 hex characters, for every default version"
    (doseq [tool all-tools
            :let [v (get-in config/defaults [:tools (:id tool) :version])]]
      (is (seq (get-in tool [:known-sha256 v])) (:name tool))
      (is (every? #(re-matches #"[0-9a-f]{64}" %) (vals (get-in tool [:known-sha256 v]))) (:name tool)))))

(deftest asset-urls-follow-the-release-layout
  (let [cfg {:tools {:chroma {:version "2.27.0"} :fswatcher {:version "0.0.7"}}}]
    (is (= "https://github.com/alecthomas/chroma/releases/download/v2.27.0/chroma-2.27.0-linux-amd64.tar.gz"
           (tools/asset-url tools/chroma cfg "linux-amd64")))
    (is (= "https://github.com/babashka/pod-babashka-fswatcher/releases/download/v0.0.7/pod-babashka-fswatcher-0.0.7-macos-aarch64.zip"
           (tools/asset-url tools/fswatcher cfg "macos-aarch64")))
    (is (= "2.27.0" (tools/version tools/chroma {})) "the built-in default when config has none")))

;; ---------------------------------------------------------------------------
;; Fetch, verify, stamp, lock, override — for every descriptor

(deftest a-download-is-verified-unpacked-stamped-and-cached
  (for-each-tool
   (fn [tool]
     (with-dir
       (fn [dir]
         (let [plat    (tools/platform tool)
               archive (fake/fake-tool-archive! dir tool plat :output (str (:name tool) " ran"))
               sha     (tools/sha256-hex archive)]
           (serve archive
                  (fn [url hits]
                    (let [cfg (cfg-for dir tool url sha)
                          bin (tools/ensure-binary! tool cfg)]
                      (is (= (str (fs/path dir "cache" (name (:id tool)) (tools/version tool cfg) plat
                                           (tools/binary-name tool plat)))
                             bin)
                          "<cache>/<id>/<version>/<platform>/<binary> — 0.2.0's Pagefind layout")
                      (is (fs/executable? bin))
                      (is (= (str (:name tool) " ran") (ran bin)))
                      (is (fs/exists? (fs/path (fs/parent bin) tools/stamp-name)))
                      (is (= 1 @hits))
                      (is (= bin (tools/ensure-binary! tool cfg)))
                      (is (= 1 @hits) "a verified entry is used as it is")
                      (is (empty? (filter #(re-find #"\.(download|partial)-" (str (fs/file-name %)))
                                          (fs/list-dir (fs/parent (fs/parent bin)))))
                          "no archive and no staging directory left behind"))))))))))

(deftest a-hash-mismatch-is-refused-and-nothing-is-kept
  (for-each-tool
   (fn [tool]
     (with-dir
       (fn [dir]
         (let [plat    (tools/platform tool)
               archive (fake/fake-tool-archive! dir tool plat)]
           (serve archive
                  (fn [url _]
                    (let [cfg (cfg-for dir tool url (apply str (repeat 64 "0")))
                          e   (try (tools/ensure-binary! tool cfg) nil
                                   (catch clojure.lang.ExceptionInfo e e))]
                      (is (some? e))
                      (is (re-find #"sha256 mismatch for http://127\.0\.0\.1:\d+/" (str (ex-message e))))
                      (is (str/starts-with? (ex-message e) (str "clogem-press: " (:error-prefix tool) ": ")))
                      (is (= 1 (:babashka/exit (ex-data e))))
                      (is (not (fs/exists? (tools/tool-dir tool cfg plat))) "nothing installed")
                      (is (empty? (filter #(re-find #"\.download-" (str (fs/file-name %)))
                                          (fs/list-dir (fs/parent (tools/tool-dir tool cfg plat)))))
                          "the download was deleted"))))))))))

(deftest a-tampered-or-unstamped-entry-is-fetched-again
  (for-each-tool
   (fn [tool]
     (with-dir
       (fn [dir]
         (let [plat    (tools/platform tool)
               archive (fake/fake-tool-archive! dir tool plat)
               sha     (tools/sha256-hex archive)]
           (serve archive
                  (fn [url hits]
                    (let [cfg (cfg-for dir tool url sha)
                          bin (tools/ensure-binary! tool cfg)]
                      (spit (fs/file bin) "#!/bin/sh\necho tampered\n")
                      (is (= bin (tools/ensure-binary! tool cfg)))
                      (is (= 2 @hits) "a binary that no longer hashes to its stamp is replaced")
                      (is (= "fake tool ran" (ran bin)))
                      (fs/delete (fs/path (fs/parent bin) tools/stamp-name))
                      (tools/ensure-binary! tool cfg)
                      (is (= 3 @hits) "an unstamped entry is never trusted")
                      (testing "another pin's entry is not this pin's"
                        (let [other (cfg-for dir tool url (apply str (repeat 64 "1")))]
                          (is (thrown? clojure.lang.ExceptionInfo (tools/ensure-binary! tool other)))
                          (is (= 4 @hits)))))))))))))

(deftest concurrent-cold-cache-fetches-fetch-once
  (for-each-tool
   (fn [tool]
     (with-dir
       (fn [dir]
         (let [plat    (tools/platform tool)
               archive (fake/fake-tool-archive! dir tool plat)
               sha     (tools/sha256-hex archive)]
           (serve archive
                  (fn [url hits]
                    (let [cfg (cfg-for dir tool url sha)
                          env tools/*env*
                          results (->> (range 4)
                                       (mapv (fn [_] (future (binding [tools/*env* env
                                                                        *err* (java.io.StringWriter.)]
                                                               (try (tools/ensure-binary! tool cfg)
                                                                    (catch Throwable e e))))))
                                       (mapv deref))]
                      (is (every? string? results) (pr-str results))
                      (is (apply = results))
                      (is (= 1 @hits) "one fetch; the others waited on the lock and used it")
                      (is (fs/exists? (fs/path (fs/parent (tools/tool-dir tool cfg plat)) (str plat ".lock")))
                          "the lock file sits beside the entry")))
                  :delay-ms 100)))))))

(deftest a-preinstalled-binary-overrides-the-download
  (for-each-tool
   (fn [tool]
     (with-dir
       (fn [dir]
         (let [bin (fs/path dir "bin" "my-tool")]
           (fs/create-dirs (fs/parent bin))
           (spit (fs/file bin) "#!/bin/sh\necho mine\n")
           (testing "the environment variable, relative to the working directory"
             (let [rel (str (fs/relativize (fs/cwd) bin))]
               (is (not (fs/absolute? rel)) rel)
               (binding [tools/*env* {(:env tool) rel}]
                 (is (= (str bin)
                        (str (fs/normalize (fs/absolutize (tools/ensure-binary! tool (cfg-for dir tool "http://127.0.0.1:1/x" nil))))))))))
           (testing ":tools <id> :path, relative to the site"
             (let [cfg (first (diag/collecting
                               (config/load-config (str dir) nil {:tools {(:id tool) {:path "bin/my-tool"}}})))]
               (is (= (str bin) (tools/ensure-binary! tool cfg)))))
           (testing "a directory or a missing file is an error naming what was checked"
             (binding [tools/*env* {(:env tool) (str (fs/parent bin))}]
               (is (re-find #"which is a directory"
                            (str (ex-message (try (tools/ensure-binary! tool {:clogem/site-dir (str dir)}) nil
                                                  (catch clojure.lang.ExceptionInfo e e)))))))
             (binding [tools/*env* {(:env tool) (str (fs/path dir "nope"))}]
               (let [m (str (ex-message (try (tools/ensure-binary! tool {:clogem/site-dir (str dir)}) nil
                                             (catch clojure.lang.ExceptionInfo e e))))]
                 (is (re-find #"does not exist: checked " m) m)
                 (is (str/includes? m (str "(" (:env tool) ")")) m))))))))))

;; ---------------------------------------------------------------------------
;; Zip archives (the fswatcher pod)

(deftest a-zip-is-read-with-java-util-zip-and-only-its-member-is-taken
  (with-dir
    (fn [dir]
      (let [tool    tools/fswatcher
            plat    (or (tools/platform tool) "linux-amd64")
            archive (fake/fake-tool-archive! dir tool plat
                                             :extra-zip-entries {"../../escape.txt" "no"
                                                                 "README.md" "not wanted"})
            dest    (fs/path dir "cache" "x")]
        (fs/create-dirs (fs/parent dest))
        (#'tools/extract! tool archive dest (tools/binary-name tool plat) "abc")
        (is (= #{(tools/binary-name tool plat) tools/stamp-name}
               (set (map (comp str fs/file-name) (fs/list-dir dest)))))
        (is (fs/executable? (fs/path dest (tools/binary-name tool plat))) "the executable bit is set")
        (is (not (fs/exists? (fs/path dir "escape.txt"))) "an entry's path is never used")
        (testing "a zip without the member is refused"
          (let [bad (fs/path dir "bad.zip")]
            (with-open [z (java.util.zip.ZipOutputStream. (clojure.java.io/output-stream (fs/file bad)))]
              (.putNextEntry z (java.util.zip.ZipEntry. "other"))
              (.closeEntry z))
            (is (re-find #"has no pod-babashka-fswatcher"
                         (str (ex-message (try (#'tools/extract! tool bad (fs/path dir "cache" "y")
                                                                 (tools/binary-name tool plat) "abc")
                                               nil
                                               (catch clojure.lang.ExceptionInfo e e))))))))))))

;; ---------------------------------------------------------------------------
;; Config and the CLI

(deftest an-unused-tool-pin-warns-and-keeps-the-built-in-one
  (testing "0.2.0 accepted any :tools :chroma / :fswatcher pin; nothing runs
            chroma yet and only `bb dev` runs the pod, falling back to polling,
            so a bad one is a warning and the config is repaired
            (Pagefind's stay errors: search-config-is-validated)"
    (doseq [[edn re check] [[{:tools {:chroma {:version "latest"}}} #":tools :chroma :version is \"latest\""
                             #(= "2.27.0" (get-in % [:tools :chroma :version]))]
                            [{:tools {:chroma {:sha256 "abc"}}} #":tools :chroma :sha256 must map each platform"
                             #(not (contains? (get-in % [:tools :chroma]) :sha256))]
                            [{:tools {:fswatcher {:version 7}}} #":tools :fswatcher :version is 7"
                             #(= "0.0.7" (get-in % [:tools :fswatcher :version]))]
                            [{:tools {:fswatcher {:sha256 {"linux-amd64" "zz"}}}} #":tools :fswatcher :sha256 must map"
                             #(not (contains? (get-in % [:tools :fswatcher]) :sha256))]]]
      (with-dir
        (fn [dir]
          (spit (fs/file dir "site.edn") (pr-str edn))
          (let [[cfg ds] (diag/collecting (config/load-config (str dir)))
                ws (diag/warnings ds)]
            (is (empty? (diag/errors ds)) (pr-str edn))
            (is (= 1 (count ws)) (pr-str edn (map :message ws)))
            (is (re-find re (str (:message (first ws)))) (pr-str edn))
            (is (re-find (if (contains? (:tools edn) :fswatcher)
                           #"Only `bb dev` runs the fswatcher pod, and it falls back to polling when it cannot, so the built-in pin is used\."
                           #"It has no effect in 0\.2\.0 \(nothing runs chroma yet\), so the built-in pin is used\.")
                         (str (:hint (first ws))))
                (:hint (first ws)))
            (is (check cfg) (pr-str edn (:tools cfg))))))))
  (testing "0.2.0's own DESIGN §5.6 sketch, verbatim, no longer fails doctor"
    (with-dir
      (fn [dir]
        (spit (fs/file dir "site.edn")
              "{:tools {:chroma {:version \"2.27.0\" :sha256 \"…\" :style \"github\" :dark-style \"github-dark\"}}}")
        (let [[cfg ds] (diag/collecting (config/load-config (str dir)))
              ms (map :message (diag/warnings ds))]
          (is (empty? (diag/errors ds)))
          (is (= 1 (count (filter #(re-find #":tools :chroma :sha256 must map" %) ms))) (pr-str ms))
          (is (some #(str/starts-with? % ":tools :chroma :style belongs under :highlight :style") ms) (pr-str ms))
          (is (some #(str/starts-with? % ":tools :chroma :dark-style belongs under :highlight :dark-style") ms) (pr-str ms))
          (is (= 3 (count ms)) (pr-str ms))
          (is (= {:version "2.27.0" :style "github" :dark-style "github-dark"} (get-in cfg [:tools :chroma]))))
        (fs/create-dirs (fs/path dir "content" "01.Guide"))
        (spit (fs/file dir "content" "01.Guide" "01.a.md") "---\ntitle: A\npermalink: /pages/aaaaaa/\n---\n\nA.\n")
        (let [r (with-out-str (cli/doctor {:site-dir (str dir)}))]
          (is (re-find #"0 error" r) r))))))

(deftest a-half-bad-unused-tool-pin-is-reset-as-one-unit
  ;; a custom version kept with its hashes dropped has no hash to verify
  ;; against, so the hint's "the built-in pin is used" was false and
  ;; `fetch-tool --tool chroma` failed
  (doseq [[extra n-warnings] [[{:url "https://example.invalid/{{version}}/{{platform}}"} 1]
                              [{:style "github"} 2]]]
    (with-dir
      (fn [dir]
        (let [tool  tools/chroma
              plat  (or (tools/platform tool) "linux-amd64")
              chroma (merge {:version "2.26.0" :sha256 "abc"} extra)]
          (spit (fs/file dir "site.edn") (pr-str {:tools {:cache-dir "cache" :chroma chroma}}))
          (let [[cfg ds] (diag/collecting (config/load-config (str dir)))
                ws (diag/warnings ds)]
            (is (empty? (diag/errors ds)))
            (is (= n-warnings (count ws)) (pr-str (map :message ws)))
            (is (= 1 (count (filter #(re-find #"^:tools :chroma :sha256 must map" (:message %)) ws))))
            (is (= (merge {:version "2.27.0"} extra) (get-in cfg [:tools :chroma]))
                "the built-in version, no :sha256, every other key kept")
            (when (tools/platform tool)
              (testing "fetch-tool resolves the built-in pin (a preinstalled, stamped cache entry)"
                (let [entry (tools/tool-dir tool cfg plat)
                      bin   (fs/path entry (tools/binary-name tool plat))]
                  (is (= "2.27.0" (str (fs/file-name (fs/parent entry)))))
                  (fs/create-dirs entry)
                  (spit (fs/file bin) "#!/bin/sh\necho chroma\n")
                  (spit (fs/file entry tools/stamp-name)
                        (pr-str {:archive-sha256 (tools/expected-sha256 tool cfg plat)
                                 :binary-sha256  (tools/sha256-hex bin)}))
                  (is (= (str bin "\n")
                         (with-out-str (cli/fetch-tool {:site-dir (str dir) :tool "chroma"})))))))))))))

(deftest fetch-tool-takes-a-tool
  (with-dir
    (fn [dir]
      (let [bin (fs/path dir "bin" "chroma")]
        (fs/create-dirs (fs/parent bin))
        (spit (fs/file bin) "#!/bin/sh\n")
        (spit (fs/file dir "site.edn") (pr-str {:tools {:chroma {:path "bin/chroma"}}}))
        (is (= (str bin "\n") (with-out-str (cli/fetch-tool {:site-dir (str dir) :tool "chroma"}))))
        (let [e (try (cli/fetch-tool {:site-dir (str dir) :tool "nope"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= "clogem-press: fetch-tool: unknown tool \"nope\"; the tools are chroma, fswatcher, pagefind."
                 (ex-message e)))
          (is (= 1 (:babashka/exit (ex-data e)))))
        (is (= "pagefind" (get-in (meta #'cli/fetch-tool) [:org.babashka/cli :spec :tool :default]))
            "pagefind stays the default, so CI's `bb fetch-tool` is unchanged")))))
