;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.tools
  "Fetch, verify and cache a pinned binary tool (DESIGN.md §4's binary-tool
  policy, D-P3-8; generalized in Phase 4 Task A).

  One code path for every tool, driven by a DESCRIPTOR map (`descriptors`):

    :id            keyword — the `:tools <id>` config section and the cache
                   directory's name
    :name          what messages call it (\"Pagefind\")
    :error-prefix  `clogem-press: <prefix>: …` on every failure
    :ex-data       merged into every failure's ex-data
    :platform      (fn [os-name os-arch]) → the release platform, or nil
    :url           the release asset URL; `{{version}}` and `{{platform}}`
                   are filled in; `:tools <id> :url` overrides it
    :archive       :tar.gz (unpacked with `tar`) or :zip (java.util.zip)
    :member        (fn [platform]) → the binary's name at the archive root
    :env           the environment variable naming a preinstalled binary
    :known-sha256  {version {platform hex}} — the archive's sha256
    :hints         {:offline (fn [dir]) :no-platform :no-sha (fn [plat])
                    :preinstalled :unpack} — the hint text of each failure

  The policy, the same for all of them:

    - a pinned version and a sha256 PER PLATFORM (`:tools <id>`), because
      one hash cannot cover several release assets;
    - downloaded with babashka's built-in HTTP client (no curl), honouring
      HTTPS_PROXY / HTTP_PROXY / NO_PROXY and user:pass@ in the proxy URL,
      with connect, response and idle timeouts;
    - unpacked into a cache OUTSIDE the site —
      `<cache-root>/<id>/<version>/<platform>/` — so a binary can never land
      in `dist/`; the entry is stamped with the hashes it was verified
      against and re-checked on every use;
    - fetched under a lock beside the entry, so concurrent cold-cache builds
      fetch once;
    - `<ENV>` or `:tools <id> :path` names a preinstalled binary and skips
      download and verification.

  Pagefind's layout and messages are exactly 0.2.0's, so existing caches
  stay valid; `clogem.search` keeps its public names as thin wrappers."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.util :as u]))

;; ---------------------------------------------------------------------------
;; Environment

(def ^:dynamic *env*
  "The environment variables this namespace reads, as a map, or nil for the
  process's own. Tests bind it so CI's `CLOGEM_PAGEFIND` cannot leak into a
  test that means to exercise the download path. (`clogem.search` binds it
  from its own `*env*`, which 0.2.0's tests bind.)"
  nil)

(defn getenv [k]
  (u/blank->nil (str (if *env* (get *env* k) (System/getenv k)))))

(def ^:dynamic *timeouts*
  "Download timeouts in ms: connecting, waiting for the response headers,
  and the longest stretch the body may go without a byte. Without them a
  server that accepts and never answers hangs the build forever. Tests bind
  them short."
  {:connect-ms 30000 :request-ms 300000 :idle-ms 300000})

(def ^:dynamic *tar*
  "The tar program. A test binds a name that does not exist."
  "tar")

;; ---------------------------------------------------------------------------
;; Descriptors

(defn- arch-of
  "Java's os.arch, normalized: :x86_64, :aarch64, :x86 or nil."
  [os-arch]
  (case (str/lower-case (str os-arch))
    ("amd64" "x86_64" "x64")              :x86_64
    ("aarch64" "arm64")                   :aarch64
    ("x86" "i386" "i486" "i586" "i686")   :x86
    nil))

(defn- os-of
  "Java's os.name, normalized: :linux, :darwin, :windows or nil."
  [os-name]
  (let [os (str/lower-case (str os-name))]
    (cond
      (str/includes? os "linux")                                 :linux
      (or (str/includes? os "mac") (str/includes? os "darwin"))  :darwin
      (str/includes? os "windows")                               :windows
      :else nil)))

(defn pagefind-platform
  "The Pagefind release platform for `os-name`/`os-arch`, or nil."
  [os-name os-arch]
  (let [arch (arch-of os-arch)]
    (when (#{:x86_64 :aarch64} arch)
      (case (os-of os-name)
        :linux   (str (name arch) "-unknown-linux-musl")
        :darwin  (str (name arch) "-apple-darwin")
        :windows (str (name arch) "-pc-windows-msvc")
        nil))))

(defn chroma-platform
  "The Chroma release platform for `os-name`/`os-arch`, or nil:
  {linux,darwin,windows}-{amd64,arm64}, plus linux-386 and windows-386."
  [os-name os-arch]
  (let [os   (os-of os-name)
        arch ({:x86_64 "amd64" :aarch64 "arm64" :x86 "386"} (arch-of os-arch))]
    (when (and os arch (or (not= "386" arch) (#{:linux :windows} os)))
      (str (name os) "-" arch))))

(defn fswatcher-platform
  "The fswatcher pod's release platform for `os-name`/`os-arch`, or nil:
  linux-amd64, linux-aarch64, macos-amd64, macos-aarch64, windows-amd64.
  There is no windows-aarch64 asset."
  [os-name os-arch]
  (let [os   ({:linux "linux" :darwin "macos" :windows "windows"} (os-of os-name))
        arch ({:x86_64 "amd64" :aarch64 "aarch64"} (arch-of os-arch))]
    (when (and os arch (not= ["windows" "aarch64"] [os arch]))
      (str os "-" arch))))

(defn- windows? [platform] (str/includes? (str platform) "windows"))

(def pagefind
  "Pagefind's extended binary (D-P3-8): the standard one does not segment
  Chinese, and a zh-Hans search for 简单 returns nothing with it (§6.7)."
  {:id           :pagefind
   :name         "Pagefind"
   :error-prefix "search"
   :ex-data      {:clogem/search-error true}
   :platform     pagefind-platform
   :url          "https://github.com/Pagefind/pagefind/releases/download/v{{version}}/pagefind_extended-v{{version}}-{{platform}}.tar.gz"
   :archive      :tar.gz
   :member       #(if (windows? %) "pagefind_extended.exe" "pagefind_extended")
   :env          "CLOGEM_PAGEFIND"
   ;; verified against the release's own `.sha256` files
   :known-sha256 {"1.5.2" {"x86_64-unknown-linux-musl"  "aeb135927856e7a49816cf2c9c2e53167f87afa1f61f7288b7d66c234a950d38"
                           "aarch64-unknown-linux-musl" "3f667f37fb6c03006212cea2a28cd26c0b47eb797c1113cad716dfff712ff19d"
                           "x86_64-apple-darwin"        "35239dfab0bd319d4344c0a4fb0f9228481bab52241021fd2e5f1b2b2b8ce42d"
                           "aarch64-apple-darwin"       "99c4b882c81c3c0f046ef85d188daeb8f6f4b598344e885cca7224c7670faec4"
                           "x86_64-pc-windows-msvc"     "7ba298054764dfb3c5b982a1eb92607bea8ecaec20a923fc8a4dfd68272ae8a2"
                           "aarch64-pc-windows-msvc"    "4fd44a27ecc7ac517e1293e8d9e31073d1cf16d457f3126c31374c69e836df43"}}
   :hints        {:offline      (fn [dir]
                                  (str "No cached binary at " dir ". Build without search with `--no-search`, or set "
                                       ":search {:provider :none}; or point CLOGEM_PAGEFIND (or :tools :pagefind :path) "
                                       "at an installed pagefind_extended."))
                  :no-platform  "Install pagefind_extended yourself and set CLOGEM_PAGEFIND, or use --no-search."
                  :no-sha       (fn [plat]
                                  (str "Add it from the release's .sha256 file: :tools {:pagefind {:sha256 {\""
                                       plat "\" \"<hex>\"}}}."))
                  :preinstalled "CLOGEM_PAGEFIND and :tools :pagefind :path name an installed pagefind_extended."
                  :unpack       "Install tar (any POSIX tar or bsdtar), or point CLOGEM_PAGEFIND at an installed pagefind_extended."}})

(def chroma
  "Chroma, the syntax highlighter (MIT; static binaries, CGO_ENABLED=0).
  Every archive, Windows included, is a .tar.gz whose root holds COPYING,
  README.md and `chroma` (`chroma.exe`). The build runs it when
  `:highlight :provider` is :chroma, the default (`clogem.highlight`,
  §11.3 item 10), so every hint names the way to build without it."
  {:id           :chroma
   :name         "Chroma"
   :error-prefix "highlight"
   :ex-data      {:clogem/tool-error :chroma}
   :platform     chroma-platform
   :url          "https://github.com/alecthomas/chroma/releases/download/v{{version}}/chroma-{{version}}-{{platform}}.tar.gz"
   :archive      :tar.gz
   :member       #(if (windows? %) "chroma.exe" "chroma")
   :env          "CLOGEM_CHROMA"
   ;; from upstream chroma-2.27.0-checksums.txt; linux-amd64 and
   ;; windows-amd64 re-verified by download (Phase 4 Task A)
   :known-sha256 {"2.27.0" {"darwin-amd64"  "e0a3ef2da6df186ce1c0015f40541dc126096d35b2c2c56b083f763d0fd1004a"
                            "darwin-arm64"  "92f418f36538714acfed3e950f1a0b34370aaacbbfa45fa016d3e2045f4a5115"
                            "linux-386"     "6d606b3b708c691d346866a105661cef1e2c28834a69829914095ff50a631dee"
                            "linux-amd64"   "91e1cd175006ff8a19cdd45ad12f9dd8c4eeeca4ec85703ebcc09cd31122f802"
                            "linux-arm64"   "c69e5eb7235978ef5a06d49b990d98c67613ec207523f889cd29b1c08086c69d"
                            "windows-386"   "d809ab1d4fe3d6559c31b9297369b619e635e1f2b596b364f942ac5801011d40"
                            "windows-amd64" "de93529e44c17490b9f0ce23099d22fd5688ae1c2054d41fcb2d56f9bf92009f"
                            "windows-arm64" "21e156e2ce06ebf58daf9648e93cdc9c2763aec380a353f9d4af19fef96737d9"}}
   :hints        {:offline      (fn [dir]
                                  (str "No cached binary at " dir ". Build without highlighting with `--no-highlight`, "
                                       "or set :highlight {:provider :none}; or point CLOGEM_CHROMA (or :tools :chroma :path) "
                                       "at an installed chroma."))
                  :no-platform  "Install chroma yourself and set CLOGEM_CHROMA, or use --no-highlight (:highlight {:provider :none})."
                  :no-sha       (fn [plat]
                                  (str "Add it from the release's checksums.txt: :tools {:chroma {:sha256 {\""
                                       plat "\" \"<hex>\"}}}."))
                  :preinstalled "CLOGEM_CHROMA and :tools :chroma :path name an installed chroma; --no-highlight (:highlight {:provider :none}) builds without it."
                  :unpack       "Install tar (any POSIX tar or bsdtar), or point CLOGEM_CHROMA at an installed chroma; or use --no-highlight (:highlight {:provider :none})."}})

(def fswatcher
  "The babashka filesystem-watcher pod, org.babashka/fswatcher. Each release
  asset is a zip whose single member is the pod binary. Upstream publishes
  no checksums: these were computed from the release zips on 2026-10-02
  (trust on first use, DESIGN.md §11.3). `bb dev` fetches it through
  `ensure-binary!` and loads it from the verified path (`dev/load-pod!`,
  Task D); `bb fetch-tool --tool fswatcher` fills the cache, as CI does."
  {:id           :fswatcher
   :name         "fswatcher pod"
   :error-prefix "dev"
   :ex-data      {:clogem/tool-error :fswatcher}
   :platform     fswatcher-platform
   :url          "https://github.com/babashka/pod-babashka-fswatcher/releases/download/v{{version}}/pod-babashka-fswatcher-{{version}}-{{platform}}.zip"
   :archive      :zip
   :member       #(if (windows? %) "pod-babashka-fswatcher.exe" "pod-babashka-fswatcher")
   :env          "CLOGEM_FSWATCHER"
   :known-sha256 {"0.0.7" {"linux-amd64"   "f94c466369be21b821ca5d13758c5fc8e6fe01a791f2abfa808ec425499c6f3b"
                           "linux-aarch64" "fa93a90eaa577e622bd26e5d71e5f572fdcefd9c9701da6126ab9b97dc2ef705"
                           "macos-aarch64" "bc434c3c79038e57165ae57929d116d9f85ae82fbbbb67dcea1e004a3afcf139"
                           "macos-amd64"   "5ac14634f02d24bfbbbca860e91018f56f5ad44353122309233e050c40951d6d"
                           "windows-amd64" "0e0f86f4ab8c455f740cd2b4f798912f37a86d8d5b4b7270903e9a48549f2165"}}
   :hints        {:offline      (fn [dir]
                                  (str "No cached binary at " dir ". Run `bb dev --poll`, or point CLOGEM_FSWATCHER "
                                       "(or :tools :fswatcher :path) at an installed pod-babashka-fswatcher."))
                  :no-platform  "Use `bb dev --poll`, or install the pod yourself and set CLOGEM_FSWATCHER."
                  :no-sha       (fn [plat]
                                  (str "Pin the zip's sha256 yourself: :tools {:fswatcher {:sha256 {\""
                                       plat "\" \"<hex>\"}}}."))
                  :preinstalled "CLOGEM_FSWATCHER and :tools :fswatcher :path name an installed pod-babashka-fswatcher."
                  :unpack       "Point CLOGEM_FSWATCHER at an installed pod-babashka-fswatcher."}})

(def descriptors
  "Every tool `bb fetch-tool --tool` can fetch, by id."
  {:pagefind pagefind :chroma chroma :fswatcher fswatcher})

(defn descriptor
  "The descriptor for `id` (a keyword or string), or nil."
  [id]
  (get descriptors (keyword (name (or id :pagefind)))))

;; ---------------------------------------------------------------------------
;; Pins and paths

(defn platform
  "The release platform of `tool` on this machine (or for `os-name` and
  `os-arch` as Java reports them), or nil when it ships no binary for it."
  ([tool] (platform tool (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([tool os-name os-arch] ((:platform tool) os-name os-arch)))

(defn binary-name [tool platform] ((:member tool) platform))

(defn version
  "The pinned version: `:tools <id> :version`, else the built-in default."
  [tool cfg]
  (str (or (get-in cfg [:tools (:id tool) :version])
           (get-in config/defaults [:tools (:id tool) :version]))))

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
  "`<cache-root>/<id>/<version>/<platform>/` — 0.2.0's Pagefind layout,
  shared by every tool."
  [tool cfg platform]
  (fs/path (cache-root cfg) (name (:id tool)) (version tool cfg) platform))

(defn expected-sha256
  "The pinned hash for this version and platform: the site's
  `:tools <id> :sha256` entry, else the built-in table."
  [tool cfg platform]
  (some-> (or (get-in cfg [:tools (:id tool) :sha256 platform])
              (get-in (:known-sha256 tool) [(version tool cfg) platform]))
          str str/lower-case))

(defn asset-url
  [tool cfg platform]
  (-> (str (or (u/blank->nil (str (get-in cfg [:tools (:id tool) :url]))) (:url tool)))
      (str/replace "{{version}}" (version tool cfg))
      (str/replace "{{platform}}" platform)))

;; ---------------------------------------------------------------------------
;; Fetch + verify

(defn fail!
  [tool msg & [hint]]
  (throw (ex-info (str "clogem-press: " (:error-prefix tool) ": " msg (when hint (str "\n  hint: " hint)))
                  (merge {:babashka/exit 1} (:ex-data tool)))))

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

(defn no-proxy?
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
  [tool url dest dir]
  (let [uri    (java.net.URI. url)
        oops   (fn [what]
                 (fail! tool (str "could not download " (:name tool) " from " url ": " what)
                        ((get-in tool [:hints :offline]) dir)))
        reason (fn [^Exception e]
                 (if (instance? java.net.http.HttpTimeoutException e)
                   (str "timed out (" (or (ex-message e) "no response") ")")
                   (or (ex-message e) (.getName (class e)))))
        resp   (try
                 (http/get url {:as :stream :throw false
                                :client (http-client uri)
                                :timeout (:request-ms *timeouts*)
                                ;; an archive is already compressed; asking
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

(defn cached-binary
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

(defn with-cache-lock
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

(defn sweep-stale-downloads!
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

(defn- untar!
  "Unpack a .tar.gz into `staging`. The archive is fed on stdin
  (`tar -xzf -`), so a Windows path such as `C:\\…` is never parsed as
  `host:path`."
  [tool archive staging]
  (let [{:keys [exit err]}
        (try (p/shell {:in (fs/file archive) :out :string :err :string :continue true}
                      *tar* "--no-same-owner" "-xzf" "-" "-C" (str staging))
             (catch java.io.IOException e
               (fail! tool (str "could not run `" *tar* "` to unpack " (:name tool) ": " (ex-message e))
                      (get-in tool [:hints :unpack]))))]
    (when-not (zero? exit)
      (fail! tool (str "could not unpack " archive " with tar: " (str/trim (str err)))))))

(defn- unzip-member!
  "Extract the one member named `bin-name` from the zip `archive` into
  `staging`, with java.util.zip — no external `unzip`. Only that member is
  read, at the archive root; a path is never taken from the archive, so an
  entry such as `../../x` cannot write outside `staging`."
  [tool archive staging bin-name]
  (let [found? (try
                 (with-open [zin (java.util.zip.ZipInputStream. (io/input-stream (fs/file archive)))]
                   (loop []
                     (if-let [e (.getNextEntry zin)]
                       (if (and (not (.isDirectory e)) (= bin-name (.getName e)))
                         (do (with-open [out (io/output-stream (fs/file staging bin-name))]
                               (io/copy zin out))
                             true)
                         (recur))
                       false)))
                 (catch java.io.IOException e
                   (fail! tool (str "could not unpack " archive " as a zip: " (ex-message e)))))]
    (when-not found?
      (fail! tool (str "the " (:name tool) " archive has no " bin-name " at its root.")))))

(defn extract!
  "Unpack `archive` into a fresh staging sibling of `dir`, check the binary,
  write the stamp, then move the staging directory into place. The caller
  holds the cache lock."
  [tool archive dir bin-name archive-sha]
  (let [staging (fs/path (fs/parent dir) (str (fs/file-name dir) ".partial-" (System/nanoTime)))]
    (fs/create-dirs staging)
    (try
      (case (:archive tool)
        :zip (unzip-member! tool archive staging bin-name)
        (untar! tool archive staging))
      (let [bin (fs/path staging bin-name)]
        ;; checked WITHOUT following links: a link could point anywhere, and
        ;; chmod would follow it to its target
        (when (fs/sym-link? bin)
          (fail! tool (str "the " (:name tool) " archive's " bin-name " is a symbolic link; refusing to install it.")))
        (when-not (fs/regular-file? bin {:nofollow-links true})
          (fail! tool (str "the " (:name tool) " archive has no " bin-name " at its root.")))
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

(defn preinstalled-binary
  "D-P3-8's escape hatch. `<ENV>` is a shell variable, so a relative value is
  relative to the process's working directory; `:tools <id> :path` is site
  config, so relative to the site."
  [tool cfg]
  (let [env-var (:env tool)
        env  (getenv env-var)
        conf (u/blank->nil (str (get-in cfg [:tools (:id tool) :path])))]
    (when-let [given (or env conf)]
      (let [f    (fs/normalize (fs/absolutize (if env
                                                 (fs/path (System/getProperty "user.dir") given)
                                                 (fs/path (config/site-dir cfg) given))))
            from (if env env-var (str ":tools " (:id tool) " :path"))
            hint (get-in tool [:hints :preinstalled])]
        (cond
          (fs/directory? f)
          (fail! tool (str from " is " (pr-str given) ", which is a directory (" f "), not the binary.") hint)
          (not (fs/exists? f))
          (fail! tool (str (:name tool) " binary " (pr-str given) " (" from ") does not exist: checked " f ".") hint)
          (not (fs/regular-file? f))
          (fail! tool (str (:name tool) " binary " f " (" from ") is not a regular file.") hint))
        (str f)))))

(defn available-binary
  "The binary of `tool` this machine already has — the environment
  variable, `:tools <id> :path`, or a verified cache entry for the pin in
  effect — or nil. NEVER fetches (`doctor` uses it). Raises, as
  `ensure-binary!` would, when the variable or the path names something
  unusable."
  [tool cfg]
  (or (preinstalled-binary tool cfg)
      (when-let [plat (platform tool)]
        (let [dir (tool-dir tool cfg plat)]
          (when-let [want (expected-sha256 tool cfg plat)]
            (some-> (cached-binary dir (fs/path dir (binary-name tool plat)) want) str))))))

(defn ensure-binary!
  "The binary of `tool` to run, fetching and verifying it on first use.
  Returns its path as a string; raises (exit 1) when it cannot have one.

  A cache entry is trusted only with a stamp naming the pin in effect and a
  binary that still hashes to what the stamp says (`cached-binary`); anything
  else is deleted and fetched again. The fetch holds a lock beside the entry,
  so concurrent cold-cache builds fetch once."
  [tool cfg]
  (or
   (preinstalled-binary tool cfg)
   (let [plat (or (platform tool)
                  (fail! tool (str (:name tool) " publishes no binary for " (System/getProperty "os.name")
                                   "/" (System/getProperty "os.arch") ".")
                         (get-in tool [:hints :no-platform])))
         dir  (tool-dir tool cfg plat)
         bin-name (binary-name tool plat)
         bin  (fs/path dir bin-name)
         want (or (expected-sha256 tool cfg plat)
                  (fail! tool (str "no sha256 is pinned for " (:name tool) " " (version tool cfg) " on " plat ".")
                         ((get-in tool [:hints :no-sha]) plat)))]
     (or
      (some-> (cached-binary dir bin want) str)
      (with-cache-lock
        (fs/path (fs/parent dir) (str (fs/file-name dir) ".lock"))
        (fn []
          ;; a sibling may have installed it while this one waited
          (or
           (some-> (cached-binary dir bin want) str)
           (let [url (asset-url tool cfg plat)
                 ext (if (= :zip (:archive tool)) ".zip" ".tar.gz")
                 archive (fs/path (fs/parent dir) (str (fs/file-name dir) ".download-" (System/nanoTime) ext))]
             (sweep-stale-downloads! dir)
             ;; an entry that failed the check — another pin's binary, a
             ;; tampered or unstamped one — is never run, nor left behind
             (when (fs/exists? dir {:nofollow-links true})
               (if (fs/directory? dir {:nofollow-links true}) (fs/delete-tree dir) (fs/delete dir)))
             (binding [*out* *err*]
               (println (str "clogem-press: fetching " (:name tool) " " (version tool cfg) " (" plat ") → " dir)))
             (try
               (download! tool url archive dir)
               (let [got (sha256-hex archive)]
                 (when-not (= want got)
                   (fs/delete-if-exists archive)
                   (fail! tool (str "sha256 mismatch for " url ": expected " want ", got " got
                                    ". The download was deleted.")
                          "A wrong pin or a tampered download; never run an unverified binary.")))
               (extract! tool archive dir bin-name want)
               (str bin)
               (finally (fs/delete-if-exists archive)))))))))))
