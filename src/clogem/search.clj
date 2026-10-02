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
            [clojure.edn :as edn]
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
            "x86_64-pc-windows-msvc"     "7ba298054764dfb3c5b982a1eb92607bea8ecaec20a923fc8a4dfd68272ae8a2"
            "aarch64-pc-windows-msvc"    "4fd44a27ecc7ac517e1293e8d9e31073d1cf16d457f3126c31374c69e836df43"}})

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
       (str/includes? os "windows") (str (name arch) "-pc-windows-msvc")
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

(def ^:dynamic *timeouts*
  "Download timeouts in ms: connecting, waiting for the response headers,
  and the longest stretch the body may go without a byte. Without them a
  server that accepts and never answers hangs the build forever. Tests bind
  them short."
  {:connect-ms 30000 :request-ms 300000 :idle-ms 300000})

(def ^:dynamic *tar*
  "The tar program. A test binds a name that does not exist."
  "tar")

(defn- no-proxy?
  "Does `NO_PROXY` / `no_proxy` exempt `host` (and `port`)? The curl/wget
  convention: a comma-separated list of hosts, `*` for all, an entry matching
  the host exactly or as a domain suffix (`example.com` and `.example.com`
  both cover `a.example.com`), optionally `:port`. No CIDR ranges."
  [host port]
  (let [host (str/lower-case (str host))]
    (some (fn [entry]
            (let [entry (str/lower-case (str/trim entry))
                  [_ h p] (re-matches #"\[?([^\]]*?)\]?(?::(\d+))?" entry)
                  h (str/replace (str h) #"^\*?\." "")]
              (and (seq entry)
                   (or (= "*" entry)
                       (and (or (nil? p) (= p (str port)))
                            (seq h)
                            (or (= host h) (str/ends-with? host (str "." h))))))))
          (str/split (str (or (getenv "NO_PROXY") (getenv "no_proxy"))) #","))))

(defn- proxy-for
  "The proxy URI for `url`, or nil: `HTTPS_PROXY` for an https URL; for an
  http one (a mirror, a test server) `HTTP_PROXY`, else `HTTPS_PROXY` as
  before; nil when `NO_PROXY` exempts the host."
  [^java.net.URI url]
  (let [https? (= "https" (str/lower-case (str (.getScheme url))))
        port   (if (pos? (.getPort url)) (.getPort url) (if https? 443 80))
        raw    (if https?
                 (or (getenv "HTTPS_PROXY") (getenv "https_proxy"))
                 (or (getenv "HTTP_PROXY") (getenv "http_proxy")
                     (getenv "HTTPS_PROXY") (getenv "https_proxy")))]
    (when (and raw (not (no-proxy? (.getHost url) port)))
      (java.net.URI. (if (str/includes? raw "://") raw (str "http://" raw))))))

(defn- proxy-credentials
  "[user password] from the proxy URI's user-info, percent-decoded, or nil."
  [^java.net.URI proxy]
  (when-let [ui (u/blank->nil (.getRawUserInfo proxy))]
    (let [[user pass] (str/split ui #":" 2)
          dec #(java.net.URLDecoder/decode (str %) "UTF-8")]
      [(dec user) (dec pass)])))

(defn- http-client
  "babashka's HTTP client (java.net.http) ignores the proxy variables, so
  honour them here — a CI runner behind a proxy is the common case where a
  download would otherwise fail with nothing to say why — with `NO_PROXY`,
  credentials from the proxy URI (`http://user:pass@host:port`) through an
  authenticator, and a connect timeout."
  [url]
  (let [proxy (proxy-for url)
        [user pass] (some-> proxy proxy-credentials)]
    (http/client
     (cond-> (assoc http/default-client-opts :connect-timeout (:connect-ms *timeouts*))
       proxy (assoc :proxy {:host (.getHost ^java.net.URI proxy)
                            :port (if (pos? (.getPort ^java.net.URI proxy)) (.getPort ^java.net.URI proxy) 80)})
       user  (assoc :authenticator {:user user :pass pass})))))

(defn- proxy-auth-header
  "A pre-emptive `Proxy-Authorization`, so a proxy that answers 407 without a
  Basic challenge, and an https CONNECT (for which the JDK will not answer a
  Basic challenge itself), still get the credentials."
  [url]
  (when-let [[user pass] (some-> (proxy-for url) proxy-credentials)]
    {"Proxy-Authorization"
     (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                    (.getBytes (str user ":" pass) "UTF-8")))}))

(defn- offline-hint
  [dir]
  (str "No cached binary at " dir ". Build without search with `--no-search`, or set "
       ":search {:provider :none}; or point CLOGEM_PAGEFIND (or :tools :pagefind :path) "
       "at an installed pagefind_extended."))

(defn- copy-with-idle-timeout!
  "Copy `in` to `dest`, closing `in` — which makes a blocked read throw —
  when no byte arrives for `idle-ms`."
  [^java.io.InputStream in dest idle-ms]
  (let [last-read (atom (System/currentTimeMillis))
        done      (atom false)
        timed-out (atom false)
        watchdog  (future
                    (loop []
                      (when-not @done
                        (if (> (- (System/currentTimeMillis) @last-read) idle-ms)
                          (do (reset! timed-out true) (.close in))
                          (do (Thread/sleep (long (max 10 (min 250 (quot idle-ms 4)))))
                              (recur))))))
        buf (byte-array 65536)]
    (try
      (with-open [out (io/output-stream (fs/file dest))]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (reset! last-read (System/currentTimeMillis))
              (.write out buf 0 n)
              (recur)))))
      (catch java.io.IOException e
        (throw (if @timed-out
                 (java.io.IOException. (str "no data for " (quot idle-ms 1000) " s; gave up"))
                 e)))
      (finally (reset! done true) (future-cancel watchdog)))))

(defn- download!
  "GET `url` into `dest`. Every failure — refused, timed out, a non-200, a
  connection dropped mid-body — is a `fail!` with the offline hint, never a
  stack trace."
  [url dest dir]
  (let [uri    (java.net.URI. url)
        oops   (fn [what]
                 (fail! (str "could not download Pagefind from " url ": " what) (offline-hint dir)))
        reason (fn [^Exception e]
                 (if (instance? java.net.http.HttpTimeoutException e)
                   (str "timed out (" (or (ex-message e) "no response") ")")
                   (or (ex-message e) (.getName (class e)))))
        resp   (try
                 (http/get url {:as :stream :throw false
                                :client (http-client uri)
                                :timeout (:request-ms *timeouts*)
                                ;; a tarball is already compressed; asking
                                ;; for gzip again invites a double decode
                                :headers (merge {:accept-encoding "identity"}
                                                (proxy-auth-header uri))})
                 (catch Exception e (oops (reason e))))]
    (if (= 200 (:status resp))
      (try
        (with-open [^java.io.InputStream in (:body resp)]
          (copy-with-idle-timeout! in dest (:idle-ms *timeouts*)))
        (catch Exception e
          (oops (str "the connection failed mid-download (" (reason e) ")"))))
      (do (some-> ^java.io.Closeable (:body resp) .close)
          (oops (str "HTTP " (:status resp)))))))

(def stamp-name
  "The file beside a cached binary that records what was verified: the
  archive's sha256 (the pin it satisfied) and the extracted binary's."
  ".clogem-verified.edn")

(defn- read-stamp
  [dir]
  (let [f (fs/path dir stamp-name)]
    (when (fs/regular-file? f)
      (try (let [m (edn/read-string (slurp (fs/file f)))] (when (map? m) m))
           (catch Exception _ nil)))))

(defn- cached-binary
  "`bin` when the cache entry at `dir` is one this build may run: a regular
  file (not a link), a stamp whose archive hash is the pin in effect, and a
  binary whose bytes still hash to what the stamp recorded. Else nil."
  [dir bin want]
  (let [{:keys [archive-sha256 binary-sha256]} (read-stamp dir)]
    (when (and (fs/regular-file? bin {:nofollow-links true})
               (= want archive-sha256)
               (= binary-sha256 (sha256-hex bin)))
      bin)))

(def ^:private jvm-locks
  "One monitor per lock file, because a FileLock is held per JVM: two
  threads of one process would get OverlappingFileLockException rather than
  waiting for each other."
  (java.util.concurrent.ConcurrentHashMap.))

(defn- with-cache-lock
  "Run `f` holding an exclusive lock on `lock-file`, across threads and
  processes, so two cold-cache builds fetch once rather than racing to
  install the same directory."
  [lock-file f]
  (fs/create-dirs (fs/parent lock-file))
  (let [k (str (fs/normalize (fs/absolutize lock-file)))
        monitor (.computeIfAbsent ^java.util.concurrent.ConcurrentHashMap jvm-locks k
                                  (reify java.util.function.Function (apply [_ _] (Object.))))]
    (locking monitor
      (with-open [ch (java.nio.channels.FileChannel/open
                      (fs/path lock-file)
                      (into-array java.nio.file.StandardOpenOption
                                  [java.nio.file.StandardOpenOption/CREATE
                                   java.nio.file.StandardOpenOption/WRITE]))]
        ;; closing the channel releases the lock
        (.lock ch)
        (f)))))

(defn- sweep-stale-downloads!
  "Remove `<plat>.download-*` and `<plat>.partial-*` siblings a killed run
  left behind (up to 52 MB each). Called holding the cache lock, so none of
  them belongs to a fetch in progress."
  [dir]
  (when (fs/directory? (fs/parent dir))
    (doseq [p (fs/list-dir (fs/parent dir))
            :let [n (str (fs/file-name p))]
            :when (or (str/starts-with? n (str (fs/file-name dir) ".download-"))
                      (str/starts-with? n (str (fs/file-name dir) ".partial-")))]
      (if (fs/directory? p {:nofollow-links true})
        (fs/delete-tree p)
        (fs/delete-if-exists p)))))

(defn- extract!
  "Unpack `tarball` into a fresh staging sibling of `dir`, check the binary,
  write the stamp, then move the staging directory into place. The archive
  is fed on stdin (`tar -xzf -`), so a Windows path such as `C:\\…` is never
  parsed as `host:path`. The caller holds the cache lock."
  [tarball dir bin-name archive-sha]
  (let [staging (fs/path (fs/parent dir) (str (fs/file-name dir) ".partial-" (System/nanoTime)))]
    (fs/create-dirs staging)
    (try
      (let [{:keys [exit err]}
            (try (p/shell {:in (fs/file tarball) :out :string :err :string :continue true}
                          *tar* "--no-same-owner" "-xzf" "-" "-C" (str staging))
                 (catch java.io.IOException e
                   (fail! (str "could not run `" *tar* "` to unpack Pagefind: " (ex-message e))
                          "Install tar (any POSIX tar or bsdtar), or point CLOGEM_PAGEFIND at an installed pagefind_extended.")))]
        (when-not (zero? exit)
          (fail! (str "could not unpack " tarball " with tar: " (str/trim (str err))))))
      (let [bin (fs/path staging bin-name)]
        ;; checked WITHOUT following links: a link could point anywhere, and
        ;; chmod would follow it to its target
        (when (fs/sym-link? bin)
          (fail! (str "the Pagefind archive's " bin-name " is a symbolic link; refusing to install it.")))
        (when-not (fs/regular-file? bin {:nofollow-links true})
          (fail! (str "the Pagefind archive has no " bin-name " at its root.")))
        (when-not (str/ends-with? bin-name ".exe")
          (fs/set-posix-file-permissions bin "rwxr-xr-x"))
        (spit (fs/file staging stamp-name)
              (pr-str {:archive-sha256 archive-sha :binary-sha256 (sha256-hex bin)})))
      ;; dir is not a verified entry for this pin (the caller checked, under
      ;; the lock), so replacing it loses nothing
      (when (fs/exists? dir {:nofollow-links true})
        (if (fs/directory? dir {:nofollow-links true}) (fs/delete-tree dir) (fs/delete dir)))
      (fs/move staging dir {:atomic-move true})
      (finally (when (fs/exists? staging) (fs/delete-tree staging))))))

(defn- preinstalled-binary
  "D-P3-8's escape hatch. `CLOGEM_PAGEFIND` is a shell variable, so a
  relative value is relative to the process's working directory;
  `:tools :pagefind :path` is site config, so relative to the site."
  [cfg]
  (let [env  (getenv "CLOGEM_PAGEFIND")
        conf (u/blank->nil (str (get-in cfg [:tools :pagefind :path])))]
    (when-let [given (or env conf)]
      (let [f    (fs/normalize (fs/absolutize (if env
                                                 (fs/path (System/getProperty "user.dir") given)
                                                 (fs/path (config/site-dir cfg) given))))
            from (if env "CLOGEM_PAGEFIND" ":tools :pagefind :path")
            hint "CLOGEM_PAGEFIND and :tools :pagefind :path name an installed pagefind_extended."]
        (cond
          (fs/directory? f)
          (fail! (str from " is " (pr-str given) ", which is a directory (" f "), not the binary.") hint)
          (not (fs/exists? f))
          (fail! (str "Pagefind binary " (pr-str given) " (" from ") does not exist: checked " f ".") hint)
          (not (fs/regular-file? f))
          (fail! (str "Pagefind binary " f " (" from ") is not a regular file.") hint))
        (str f)))))

(defn ensure-binary!
  "The Pagefind binary to run, fetching and verifying it on first use.
  Returns its path as a string; raises (exit 1) when it cannot have one.

  A cache entry is trusted only with a stamp naming the pin in effect and a
  binary that still hashes to what the stamp says (`cached-binary`); anything
  else is deleted and fetched again. The fetch holds a lock beside the entry,
  so concurrent cold-cache builds fetch once."
  [cfg]
  (or
   (preinstalled-binary cfg)
   (let [plat (or (platform)
                  (fail! (str "Pagefind publishes no binary for " (System/getProperty "os.name")
                              "/" (System/getProperty "os.arch") ".")
                         "Install pagefind_extended yourself and set CLOGEM_PAGEFIND, or use --no-search."))
         dir  (tool-dir cfg plat)
         bin  (fs/path dir (binary-name plat))
         want (or (expected-sha256 cfg plat)
                  (fail! (str "no sha256 is pinned for Pagefind " (version cfg) " on " plat ".")
                         (str "Add it from the release's .sha256 file: :tools {:pagefind {:sha256 {\""
                              plat "\" \"<hex>\"}}}.")))]
     (or
      (some-> (cached-binary dir bin want) str)
      (with-cache-lock
        (fs/path (fs/parent dir) (str (fs/file-name dir) ".lock"))
        (fn []
          ;; a sibling may have installed it while this one waited
          (or
           (some-> (cached-binary dir bin want) str)
           (let [url (asset-url cfg plat)
                 tgz (fs/path (fs/parent dir) (str (fs/file-name dir) ".download-" (System/nanoTime) ".tar.gz"))]
             (sweep-stale-downloads! dir)
             ;; an entry that failed the check — another pin's binary, a
             ;; tampered or unstamped one — is never run, nor left behind
             (when (fs/exists? dir {:nofollow-links true})
               (if (fs/directory? dir {:nofollow-links true}) (fs/delete-tree dir) (fs/delete dir)))
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
               (extract! tgz dir (binary-name plat) want)
               (str bin)
               (finally (fs/delete-if-exists tgz)))))))))))

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
  "Index the built site: `<binary> --site <out> --output-subdir pagefind`,
  into a fresh `<out>/pagefind/`.
  A non-zero exit is a build error carrying Pagefind's own output. Returns
  {:binary :output}."
  [cfg]
  (let [bin (ensure-binary! cfg)
        out (config/out-dir cfg)
        ;; a bundle left by an earlier build into the same out dir would keep
        ;; every fragment of every page since deleted, and grow per rebuild
        _   (let [old (fs/path out output-subdir)]
              (when (fs/exists? old {:nofollow-links true})
                (if (fs/directory? old {:nofollow-links true}) (fs/delete-tree old) (fs/delete old))))
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
