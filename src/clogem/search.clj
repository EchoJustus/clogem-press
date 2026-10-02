;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.search
  "Search (DESIGN.md §5.2 step 6, §6.7, D-P3-8 … D-P3-11): fetch, verify and
  cache the Pagefind extended binary, then run it over the finished `dist/`.

  The §4 binary-tool policy, concretely:

    - a pinned version and a sha256 PER PLATFORM (`:tools :pagefind`), because
      one hash cannot cover five release assets;
    - downloaded with babashka's built-in HTTP client (no curl) and unpacked
      with `tar`, into a cache OUTSIDE the site — `$XDG_CACHE_HOME/clogem-press/
      tools/pagefind/<version>/<platform>/` — so a content repo needs no
      .gitignore entry and the 58 MB binary can never land in `dist/`;
    - `CLOGEM_PAGEFIND` or `:tools :pagefind :path` names a preinstalled
      binary and skips download and verification.

  Only the EXTENDED binary is fetched: the standard one does not segment
  Chinese, and a zh-Hans search for 简单 returns nothing with it (§6.7)."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Pins

(def known-sha256
  "sha256 of each `pagefind_extended` release asset, per version and platform,
  verified against the release's own `.sha256` files. A site pinning a version
  not listed here supplies its own `:tools :pagefind :sha256` map."
  {"1.5.2" {"x86_64-unknown-linux-musl"  "aeb135927856e7a49816cf2c9c2e53167f87afa1f61f7288b7d66c234a950d38"
            "aarch64-unknown-linux-musl" "3f667f37fb6c03006212cea2a28cd26c0b47eb797c1113cad716dfff712ff19d"
            "x86_64-apple-darwin"        "35239dfab0bd319d4344c0a4fb0f9228481bab52241021fd2e5f1b2b2b8ce42d"
            "aarch64-apple-darwin"       "99c4b882c81c3c0f046ef85d188daeb8f6f4b598344e885cca7224c7670faec4"
            "x86_64-pc-windows-msvc"     "7ba298054764dfb3c5b982a1eb92607bea8ecaec20a923fc8a4dfd68272ae8a2"}})

(def default-url
  "The release asset URL; `{{version}}` and `{{platform}}` are filled in.
  `:tools :pagefind :url` overrides it (a mirror — or a test's local server)."
  "https://github.com/Pagefind/pagefind/releases/download/v{{version}}/pagefind_extended-v{{version}}-{{platform}}.tar.gz")

(def output-subdir
  "Where the bundle lands inside `dist/`; the theme's asset URLs assume it."
  "pagefind")

;; ---------------------------------------------------------------------------
;; Environment

(def ^:dynamic *env*
  "The environment variables this namespace reads, as a map, or nil for the
  process's own. Tests bind it so CI's `CLOGEM_PAGEFIND` cannot leak into a
  test that means to exercise the download path."
  nil)

(defn- getenv [k]
  (u/blank->nil (str (if *env* (get *env* k) (System/getenv k)))))

(defn platform
  "The Pagefind release platform for this machine (or for `os-name`/`os-arch`
  as Java reports them), or nil when Pagefind ships no binary for it."
  ([] (platform (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name os-arch]
   (let [os   (str/lower-case (str os-name))
         arch (case (str/lower-case (str os-arch))
                ("amd64" "x86_64" "x64") :x86_64
                ("aarch64" "arm64")      :aarch64
                nil)]
     (cond
       (nil? arch) nil
       (str/includes? os "linux") (str (name arch) "-unknown-linux-musl")
       (or (str/includes? os "mac") (str/includes? os "darwin")) (str (name arch) "-apple-darwin")
       (and (str/includes? os "windows") (= :x86_64 arch)) "x86_64-pc-windows-msvc"
       :else nil))))

(defn binary-name
  [platform]
  (if (str/includes? (str platform) "windows") "pagefind_extended.exe" "pagefind_extended"))

(defn version [cfg] (str (get-in cfg [:tools :pagefind :version])))

(defn cache-root
  "The tools cache: `CLOGEM_TOOLS_DIR`, else `:tools :cache-dir` (relative to
  the site directory), else `$XDG_CACHE_HOME/clogem-press/tools`, else
  `~/.cache/clogem-press/tools`. Never inside the site's output."
  [cfg]
  (let [configured (u/blank->nil (str (get-in cfg [:tools :cache-dir])))]
    (fs/normalize
     (fs/absolutize
      (cond
        (getenv "CLOGEM_TOOLS_DIR") (fs/path (getenv "CLOGEM_TOOLS_DIR"))
        configured                  (fs/path (config/site-dir cfg) configured)
        (getenv "XDG_CACHE_HOME")   (fs/path (getenv "XDG_CACHE_HOME") "clogem-press" "tools")
        :else (fs/path (System/getProperty "user.home") ".cache" "clogem-press" "tools"))))))

(defn tool-dir
  [cfg platform]
  (fs/path (cache-root cfg) "pagefind" (version cfg) platform))

(defn expected-sha256
  "The pinned hash for this version and platform: the site's
  `:tools :pagefind :sha256` entry, else the built-in table."
  [cfg platform]
  (some-> (or (get-in cfg [:tools :pagefind :sha256 platform])
              (get-in known-sha256 [(version cfg) platform]))
          str str/lower-case))

(defn asset-url
  [cfg platform]
  (-> (str (or (u/blank->nil (str (get-in cfg [:tools :pagefind :url]))) default-url))
      (str/replace "{{version}}" (version cfg))
      (str/replace "{{platform}}" platform)))

;; ---------------------------------------------------------------------------
;; Fetch + verify

(defn- fail!
  [msg & [hint]]
  (throw (ex-info (str "clogem-press: search: " msg (when hint (str "\n  hint: " hint)))
                  {:babashka/exit 1 :clogem/search-error true})))

(defn sha256-hex
  [f]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file f))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- http-client
  "babashka's HTTP client (java.net.http) ignores HTTPS_PROXY, so honour it
  here: a CI runner behind a proxy is the common case where a download would
  otherwise fail with nothing to say why."
  []
  (if-let [proxy (or (getenv "HTTPS_PROXY") (getenv "https_proxy"))]
    (let [uri (java.net.URI. (if (str/includes? proxy "://") proxy (str "http://" proxy)))]
      (http/client (assoc http/default-client-opts
                          :proxy {:host (.getHost uri)
                                  :port (if (pos? (.getPort uri)) (.getPort uri) 80)})))
    nil))

(defn- offline-hint
  [dir]
  (str "No cached binary at " dir ". Build without search with `--no-search`, or set "
       ":search {:provider :none}; or point CLOGEM_PAGEFIND (or :tools :pagefind :path) "
       "at an installed pagefind_extended."))

(defn- download!
  [url dest dir]
  (let [resp (try
               (http/get url (cond-> {:as :stream :throw false
                                      ;; a tarball is already compressed; asking
                                      ;; for gzip again invites a double decode
                                      :headers {:accept-encoding "identity"}}
                               (http-client) (assoc :client (http-client))))
               (catch Exception e
                 (fail! (str "could not download Pagefind from " url ": "
                             (or (ex-message e) (.getName (class e))))
                        (offline-hint dir))))]
    (if (= 200 (:status resp))
      (with-open [in (:body resp)]
        (io/copy in (fs/file dest)))
      (do (some-> ^java.io.Closeable (:body resp) .close)
          (fail! (str "could not download Pagefind from " url ": HTTP " (:status resp))
                 (offline-hint dir))))))

(defn- extract!
  "Unpack `tarball` into a fresh sibling of `dir`, then move it into place,
  so an interrupted fetch never leaves a half-written binary that a later
  build would trust."
  [tarball dir bin-name]
  (let [staging (fs/path (fs/parent dir) (str (fs/file-name dir) ".partial-" (System/nanoTime)))]
    (fs/create-dirs staging)
    (try
      (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                        "tar" "-xzf" (str tarball) "-C" (str staging))]
        (when-not (zero? exit)
          (fail! (str "could not unpack " tarball " with tar: " (str/trim (str err))))))
      (let [bin (fs/path staging bin-name)]
        (when-not (fs/regular-file? bin)
          (fail! (str "the Pagefind archive has no " bin-name " at its root.")))
        (when-not (str/ends-with? bin-name ".exe")
          (fs/set-posix-file-permissions bin "rwxr-xr-x")))
      (when (fs/exists? dir) (fs/delete-tree dir))
      (fs/move staging dir)
      (finally (when (fs/exists? staging) (fs/delete-tree staging))))))

(defn ensure-binary!
  "The Pagefind binary to run, fetching and verifying it on first use.
  Returns its path as a string; raises (exit 1) when it cannot have one."
  [cfg]
  (let [preinstalled (or (getenv "CLOGEM_PAGEFIND")
                         (u/blank->nil (str (get-in cfg [:tools :pagefind :path]))))]
    (if preinstalled
      (let [f (fs/absolutize (fs/path (config/site-dir cfg) preinstalled))]
        (when-not (fs/regular-file? f)
          (fail! (str "Pagefind binary " preinstalled " does not exist.")
                 "CLOGEM_PAGEFIND and :tools :pagefind :path name an installed pagefind_extended."))
        (str f))
      (let [plat (or (platform)
                     (fail! (str "Pagefind publishes no binary for " (System/getProperty "os.name")
                                 "/" (System/getProperty "os.arch") ".")
                            "Install pagefind_extended yourself and set CLOGEM_PAGEFIND, or use --no-search."))
            dir  (tool-dir cfg plat)
            bin  (fs/path dir (binary-name plat))]
        (if (fs/regular-file? bin)
          (str bin)
          (let [want (or (expected-sha256 cfg plat)
                         (fail! (str "no sha256 is pinned for Pagefind " (version cfg) " on " plat ".")
                                (str "Add it from the release's .sha256 file: :tools {:pagefind {:sha256 {\""
                                     plat "\" \"<hex>\"}}}.")))
                url  (asset-url cfg plat)
                _    (fs/create-dirs (fs/parent dir))
                tgz  (fs/path (fs/parent dir) (str (fs/file-name dir) ".download-" (System/nanoTime) ".tar.gz"))]
            (binding [*out* *err*]
              (println (str "clogem-press: fetching Pagefind " (version cfg) " (" plat ") → " dir)))
            (try
              (download! url tgz dir)
              (let [got (sha256-hex tgz)]
                (when-not (= want got)
                  (fs/delete-if-exists tgz)
                  (fail! (str "sha256 mismatch for " url ": expected " want ", got " got
                              ". The download was deleted.")
                         "A wrong pin or a tampered download; never run an unverified binary.")))
              (extract! tgz dir (binary-name plat))
              (str bin)
              (finally (fs/delete-if-exists tgz)))))))))

;; ---------------------------------------------------------------------------
;; The Component UI's language (D-P3-10)

(def builtin-ui-langs
  "The UI translations bundled in Pagefind 1.5.2's `pagefind-component-ui.js`
  (its `translations/*.json` list). No `ms`."
  #{"af" "ar" "bn" "ca" "cs" "da" "de" "el" "en" "es" "eu" "fa" "fi" "fr" "gl"
    "he" "hi" "hr" "hu" "id" "it" "ja" "ko" "mi" "my" "nb" "nl" "nn" "no" "pl"
    "pt" "ro" "ru" "sr" "sv" "sw" "ta" "th" "tr" "uk" "vi" "zh" "zh-cn" "zh-tw"})

(defn ui-lang
  "The `lang` the page hands `<pagefind-config>`: its own `<html lang>`, except
  Traditional Chinese, which becomes `zh-TW`. Pagefind looks UI strings up
  by language-script-region, language-region, language — so `zh-Hant` would
  reach `zh.json`, which is Simplified, while `zh-TW` reaches `zh-tw.json`.
  This attribute changes UI strings only, never which index is searched."
  [cfg lang]
  (let [hl (config/html-lang cfg lang)
        [l & more] (str/split (str/lower-case hl) #"-")]
    ;; any Traditional tag: Pagefind has no zh-hk or zh-hant entry, so
    ;; zh-Hant-HK would fall through to Simplified `zh` as well
    (if (and (= "zh" l) (some #{"hant"} more)) "zh-TW" hl)))

(defn builtin-ui?
  "Does Pagefind have UI strings of its own for `ui-lang`? Mirrors its lookup:
  language-script-region, then language-region, then language, else English."
  [ui-lang]
  (let [[l & more] (str/split (str/lower-case (str ui-lang)) #"-")
        script (first (filter #(re-matches #"[a-z]{4}" %) more))
        region (first (filter #(re-matches #"[a-z]{2}|\d{3}" %) more))]
    (boolean (some builtin-ui-langs
                   (remove nil? [(when (and script region) (str l "-" script "-" region))
                                 (when region (str l "-" region))
                                 l])))))

(def ui-string-keys
  "The theme's `:search/…` keys, one per string of Pagefind's `en.json`; the
  key's name with `-` → `_` is Pagefind's own key."
  [:search/placeholder :search/clear-search :search/load-more :search/search-label
   :search/filters-label :search/zero-results :search/many-results :search/one-result
   :search/total-zero-results :search/total-one-result :search/total-many-results
   :search/alt-search :search/search-suggestion :search/searching :search/results-label
   :search/keyboard-navigate :search/keyboard-select :search/keyboard-clear
   :search/keyboard-close :search/keyboard-search :search/error-search
   :search/filter-selected-one :search/filter-selected-many :search/input-hint
   :search/loading])

(defn- overridden?
  "Does the site's own `i18n/<lang>.edn` set any `:search/…` key?"
  [cfg lang]
  (some #(contains? (i18n/site-strings cfg lang) %) ui-string-keys))

(defn ui-translations
  "The strings to hand the Component UI's `setTranslations` on a `lang` page,
  as {pagefind-key string}, or nil when Pagefind's own are used: only a
  language Pagefind has no strings for (`ms`), or one whose `:search/…`
  strings the site overrides, gets them. Pagefind's placeholders —
  `[SEARCH_TERM]`, `[COUNT]`, `[DIFFERENT_TERM]` — pass through untouched;
  they are not `{{…}}`, so `i18n/interpolate` leaves them alone."
  [{:keys [cfg lang] :as ctx}]
  (when (or (not (builtin-ui? (ui-lang cfg lang))) (overridden? cfg lang))
    (into (sorted-map)
          (for [k ui-string-keys]
            [(str/replace (name k) "-" "_") (i18n/tr ctx k)]))))

;; ---------------------------------------------------------------------------
;; Run

(defn enabled? [cfg] (= :pagefind (get-in cfg [:search :provider])))

(defn run!
  "Index the built site: `<binary> --site <out> --output-subdir pagefind`.
  A non-zero exit is a build error carrying Pagefind's own output. Returns
  {:binary :output}."
  [cfg]
  (let [bin (ensure-binary! cfg)
        out (config/out-dir cfg)
        {:keys [exit] :as r}
        (try (p/shell {:out :string :err :string :continue true}
                      bin "--site" (str out) "--output-subdir" output-subdir)
             (catch Exception e
               (fail! (str "could not run " bin ": " (ex-message e)))))
        output (str (:out r) (:err r))]
    (when-not (zero? exit)
      (fail! (str "Pagefind exited " exit ":\n" (str/trimr output))
             (str "dist/ is already written but has no search index; "
                  "fix the cause, or build with `--no-search`.")))
    {:binary bin :output output}))

(defn index!
  "`run!`, then read back what Pagefind wrote: {:languages n :pages n}, from
  `pagefind-entry.json`."
  [cfg]
  (run! cfg)
  (let [entry (fs/path (config/out-dir cfg) output-subdir "pagefind-entry.json")
        langs (when (fs/exists? entry)
                (:languages (json/parse-string (slurp (fs/file entry)) true)))]
    {:languages (count langs)
     :pages     (reduce + 0 (keep :page_count (vals langs)))}))
