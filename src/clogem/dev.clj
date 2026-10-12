;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev
  "Dev server and static preview (DESIGN.md §5.4).

  All built-ins: http-kit for the server and for SSE push, babashka.fs for the
  polling watcher. Static file serving is *not* built into http-kit, so the
  handler below is the small vendored one the design allows for.

  ## Watching

  §5.4 specifies the fswatcher pod with a `--poll` fallback, and Appendix A
  item 5 records why: inotify events silently never fire in some sandboxed
  containers. The pod path is attempted first and falls back automatically — a
  zero-event environment does not need the user to know the flag exists, it
  just needs the build to keep working.

  \"Automatically\" is the load-bearing word, and it is why `probe-watch!`
  exists. Whether events fire is a property of the *container*, not of the pod,
  so it cannot be decided by loading the pod or by reading its return value: the
  only way to know is to cause an event and see whether it arrives. Registration
  reported as success is the worst outcome available, because the dev server
  then comes up, announces that it is watching, and silently never rebuilds.

  ## The loop (§11.3 item 12)

  Every watcher — the pod's callback, or the poll loop — only *submits* the
  paths it saw to one queue, after dropping editor temp files
  (`editor-temp?`). One thread drains it (`drain-loop!`): it collects paths
  until 100 ms pass with none arriving, then runs one full rebuild for the
  lot. So a burst of writes is one rebuild, and two rebuilds never run at
  once. A rebuild that fails, `Throwable` included, keeps the loop alive and
  is pushed to the browser as an SSE `build-error`, which the injected script
  shows as an overlay; the next good build clears it. Search is indexed after
  the reload has been sent, in the background (`background-runner`)."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clogem.assets :as assets]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.i18n :as i18n]
            [clogem.search :as search]
            [clogem.tools :as tools]
            [clogem.util :as u]
            [org.httpkit.server :as http])
  (:import [java.io File]
           [java.net URLDecoder]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Static files

(def mime-types
  {"html" "text/html; charset=utf-8"   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8" "json" "application/json; charset=utf-8"
   "svg"  "image/svg+xml"              "png"  "image/png"
   "jpg"  "image/jpeg"                 "jpeg" "image/jpeg"
   "gif"  "image/gif"                  "webp" "image/webp"
   "ico"  "image/x-icon"               "woff2" "font/woff2"
   "woff" "font/woff"                  "txt"  "text/plain; charset=utf-8"
   "xml"  "application/xml; charset=utf-8"})

(defn- u-ext [p]
  (let [n (str (fs/file-name p))
        i (str/last-index-of n ".")]
    (when i (str/lower-case (subs n (inc i))))))

(defn- content-type [p]
  (get mime-types (u-ext p) "application/octet-stream"))

(defn- under?
  "Is the canonical path `p` the root itself or something inside it?

  The separator is the whole point. Comparing canonical paths as bare strings
  makes containment a prefix test on names, so `/site/dist-readonly` sits
  \"inside\" a root of `/site/dist` — and `dist-readonly` is not hypothetical:
  CI builds one, `build --out dist-readonly`, right beside the served `dist/`."
  [^String base ^String p]
  (or (= p base)
      (str/starts-with? p (str base File/separator))))

(defn decode-path
  "The request path of `uri` (its query dropped), percent-decoded exactly
  once — `%2520` is the three characters `%20` — or nil when it is not
  valid percent-encoding. A `+` is a plus: this is a path, not a form."
  [uri]
  (try
    (URLDecoder/decode (str/replace (str/replace (str uri) #"\?.*$" "") "+" "%2B") "UTF-8")
    (catch IllegalArgumentException _ nil)))

(defn- lexical-segments
  "The decoded request path as its plain segments — `.` and empty ones
  dropped, `..` applied — or nil when it is not a path under the root on
  every platform: a backslash (a Windows separator; `\\\\host\\share` is an
  SMB path), a `:` (a drive letter, an NTFS stream), a NUL, or a `..` past
  the root. Purely lexical (P4-D.2 item 8): a refused path never reaches
  the filesystem, so a page cannot make dev stat a network share."
  [decoded]
  (let [s (str decoded)]
    (when-not (re-find #"[\\:\x00]" s)
      (reduce (fn [acc seg]
                (case seg
                  ("" ".") acc
                  ".."     (if (seq acc) (pop acc) (reduced nil))
                  (conj acc seg)))
              [] (str/split s #"/")))))

(defn resolve-path
  "Map an already-decoded request path to a file under `root`, refusing
  anything that escapes it: lexically first (`lexical-segments`), before
  any filesystem call, then by the canonical path of the FILE served —
  a directory's `index.html` included, which a link can point outside
  the root as well as the directory can (P4-D.2 item 7). Returns the
  canonical path, so what is opened is what was checked."
  [root decoded]
  (try
    (when-let [segs (lexical-segments decoded)]
      (let [base      (fs/canonicalize root)
            base-s    (str base)
            target    (apply fs/path base segs)
            contained (fn [p]
                        (let [c (fs/canonicalize p)]
                          (when (and (under? base-s (str c)) (fs/regular-file? c)) c)))]
        (cond
          (fs/directory? target)    (contained (fs/path target "index.html"))
          (fs/regular-file? target) (contained target)
          :else nil)))
    ;; an InvalidPathException, or a file renamed away mid-check
    (catch Exception _ nil)))

(defn resolve-file
  "Map a request URI to a file under `root`, refusing anything that escapes it."
  [root uri]
  (some->> (decode-path uri) (resolve-path root)))

(defn strip-base
  "`path` — a DECODED request path — with the site base `base` taken off its
  front, as `/…`, or nil when it is not under `base`. `dist/` is the deploy
  root and a project site is served at `https://host/<base>/`
  (`render/uri->file`), so the server mounts `dist/` at the base for its
  links to resolve (§11.3 item 12). The base is compared decoded, as the
  config spells it: a browser asks for `/%E6%96%87%E6%A1%A3/` when the base
  is `/文档/`."
  [base path]
  (let [path (str path)]
    (cond
      (= "/" base)                 path
      (str/starts-with? path base) (subs path (dec (count base)))
      :else                        nil)))

;; ---------------------------------------------------------------------------
;; SSE live reload

(def ^:private clients (atom #{}))

(def reload-script
  "The client half of the reload channel, spliced in before `</body>` by the
  handler (so `dist/` stays what production serves). `data: css` re-fetches
  every stylesheet link, keeping its `?v=`; any other message reloads; a
  `build-error` event shows an overlay, which a CSS swap removes and a reload
  discards. The overlay is self-contained — inline styles only, so a broken
  theme stylesheet cannot hide it — and the script defines no global (it
  must not touch `window.clogem`, the theme's own object)."
  (str
   "<script>(function(){var s=new EventSource('/__reload'),id='clogem-dev-overlay';"
   "function clear(){var o=document.getElementById(id);if(o&&o.parentNode)o.parentNode.removeChild(o);}"
   "s.onmessage=function(e){if(e.data==='css'){clear();document.querySelectorAll('link[rel=stylesheet]').forEach(function(l){"
   "var u=new URL(l.href);u.searchParams.set('t',Date.now());l.href=u.toString();});}else{location.reload();}};"
   "s.addEventListener('build-error',function(e){var d;try{d=JSON.parse(e.data);}catch(x){d={message:String(e.data)};}"
   "clear();var o=document.createElement('div');o.id=id;o.setAttribute('role','alert');"
   "o.style.cssText='position:fixed;top:0;right:0;bottom:0;left:0;z-index:2147483647;overflow:auto;margin:0;padding:24px;"
   "box-sizing:border-box;background:rgba(24,24,27,.95);color:#f4f4f5;font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
   "white-space:pre-wrap;text-align:left;direction:ltr';"
   "var h=document.createElement('div');h.style.cssText='font-weight:700;font-size:16px;color:#fca5a5;margin:0 0 12px';"
   "h.textContent='clogem-press: the build failed'+(d.file?' \\u2014 '+d.file:'');"
   "var m=document.createElement('div');m.textContent=d.message||'';"
   "var f=document.createElement('div');f.style.cssText='margin-top:16px;color:#a1a1aa';"
   "f.textContent='Showing the last good build. Fix it and save: the page reloads when a build succeeds. Esc hides this.';"
   "o.appendChild(h);o.appendChild(m);o.appendChild(f);(document.body||document.documentElement).appendChild(o);});"
   "document.addEventListener('keydown',function(e){if(e.key==='Escape')clear();});})();</script>"))

(defn sse-frame
  "One SSE frame: an optional `event:` line, then `data:` (one line — JSON
  payloads are single-line)."
  [event data]
  (str (when event (str "event: " event "\n")) "data: " data "\n\n"))

(defn- error-frame [err] (sse-frame "build-error" (json/generate-string err)))

(defn- sse-handler
  [req state]
  (http/as-channel
   req
   {:on-open (fn [ch]
               (swap! clients conj ch)
               (http/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}
                               :body (str "retry: 500\n\n"
                                          ;; a page loaded while the build is
                                          ;; broken shows the error at once
                                          (when-let [err (some-> state deref :error)]
                                            (error-frame err)))}
                           false))
    :on-close (fn [ch _] (swap! clients disj ch))}))

(defn send-frame!
  [frame]
  (doseq [ch @clients]
    (try (http/send! ch frame false)
         (catch Exception _ (swap! clients disj ch)))))

(defn notify-clients!
  "Send `data: <payload>` — `reload` or `css` — to every client."
  [payload]
  (send-frame! (sse-frame nil payload)))

(defn notify-error!
  "Send `event: build-error` with `err` ({:message :file}) as JSON."
  [err]
  (send-frame! (error-frame err)))

;; ---------------------------------------------------------------------------
;; Handler

(defn- html-request?
  "Would `uri` be answered with a page: a directory, or a `.html` file?"
  [uri]
  (let [last-seg (peek (str/split (str uri) #"/" -1))]
    (or (str/ends-with? (str uri) "/")
        (str/ends-with? (str/lower-case (str uri)) ".html")
        (not (str/includes? (str last-seg) ".")))))

(defn- escape-html
  "Text and attribute escaping both: `\"` and `'` too, since `not-found`
  puts the base inside an `href=\"…\"` (P4-D.2 item 16)."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))

(defn error-page
  "What dev serves for a page while no build has succeeded yet: the error,
  plainly, plus the reload script, so the first good build replaces it."
  [{:keys [message file]}]
  (str "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Build failed — clogem-press</title>"
       "<body style=\"margin:0;padding:24px;font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace\">"
       "<h1 style=\"font-size:18px\">clogem-press: the build failed" (when file (str " — " (escape-html file))) "</h1>"
       "<pre style=\"white-space:pre-wrap\">" (escape-html message) "</pre>"
       "<p>No build has succeeded since <code>bb dev</code> started. Fix it and save: this page reloads when a build succeeds.</p>"
       reload-script "</body></html>"))

(defn- not-found
  [base path]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!doctype html><meta charset=utf-8><h1>404</h1><p>Not found."
              (when (and (not= "/" base) (nil? (strip-base base path)))
                (str " This site is served at <a href=\"" (escape-html base) "\">" (escape-html base) "</a>."))
              "</p>")})

(def ^:private server-error
  "What any failure inside the handler answers: never the exception, whose
  message can carry a filesystem path."
  {:status 500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "500 Internal Server Error\n"})

(defn- pagefind-fallback
  "For a request under `pagefind/` the live bundle cannot answer, the same
  file in a previous generation (`search/previous-dirs`, newest first): a
  page that loaded an older `pagefind-entry.json` before dev's background
  index swapped in a new one still fetches the old bundle's content-hashed
  files, until its next reload (§5.4)."
  [root rel]
  (when (str/starts-with? rel "/pagefind/")
    (some #(resolve-path root (str "/" (fs/file-name %) (subs rel (count "/pagefind"))))
          (search/previous-dirs root))))

(def stream-threshold
  "Files at least this big are streamed in `chunk-bytes` chunks
  (`stream-file!`), smaller ones read whole into the heap (P4-D.2 item 6,
  P4-D.3 item 3). 1 MB covers every Pagefind file, which are what change
  under a running dev."
  (* 1024 1024))

(def chunk-bytes
  "The size of each chunk `stream-file!` sends, and so the memory one
  download of a large file holds."
  (* 64 1024))

(defn- open-read
  "A read-only `FileChannel` on `f`, opened once: a file gone by now
  throws an `IOException` here, before any response exists."
  ^java.nio.channels.FileChannel [f]
  (java.nio.channels.FileChannel/open
   (fs/path f) (into-array java.nio.file.StandardOpenOption [java.nio.file.StandardOpenOption/READ])))

(defn- read-all
  "Every byte of the open channel `ch` of `n` bytes, as a `byte[]`."
  [^java.nio.channels.FileChannel ch n]
  (let [buf (java.nio.ByteBuffer/allocate (int n))]
    (while (and (.hasRemaining buf) (pos? (.read ch buf))))
    (java.util.Arrays/copyOf (.array buf) (.position buf))))

(defn stream-file!
  "Send the open channel `fc` to the http-kit channel `ch` in chunks of
  `chunk-bytes`, then close both; runs on its own daemon thread. Never
  more than one chunk is in memory, however slow the client: each chunk
  is a `ByteBuffer` http-kit writes from as it is, so the next is read
  only once its position shows the last was written out (P4-D.3 item 3).
  `closed?` — the client went — ends it, as does a read that fails (a
  truncated download, never a 500). No mapping: the file is closed when
  the download ends, so on Windows a rebuild can replace it at once, and
  a file of 2 GiB or more is served like any other."
  [ch ^java.nio.channels.FileChannel fc closed?]
  (doto (Thread.
         ^Runnable
         (fn []
           (try
             (let [buf (java.nio.ByteBuffer/allocate (int chunk-bytes))]
               (loop []
                 (.clear buf)
                 (let [r (.read fc buf)]
                   (if (neg? r)
                     (http/close ch)
                     (do (.flip buf)
                         (when (http/send! ch buf false)
                           ;; wait for http-kit to have written it all
                           (loop [ms 1]
                             (when (and (.hasRemaining buf) (not @closed?))
                               (Thread/sleep (long ms))
                               (recur (min 50 (* 2 ms)))))
                           (when-not @closed? (recur))))))))
             (catch Throwable _ (try (http/close ch) (catch Throwable _ nil)))
             (finally (try (.close fc) (catch Throwable _ nil)))))
         "clogem-file-stream")
    (.setDaemon true)
    (.start)))

(defn- streamed
  "The response for a large file, already open as `fc`: through http-kit's
  async channel when `req` came from http-kit, streamed by
  `stream-file!`; else (a direct call, as the tests make) the channel as
  an `InputStream` body."
  [req fc headers]
  (if (:async-channel req)
    (let [closed? (atom false)]
      (http/as-channel req
                       {:on-open  (fn [ch]
                                    (http/send! ch {:status 200 :headers headers} false)
                                    (stream-file! ch fc closed?))
                        :on-close (fn [_ _] (reset! closed? true))}))
    {:status 200 :headers headers
     :body (java.nio.channels.Channels/newInputStream fc)}))

(defn- file-response
  "Serve `f`, opened here, once: a file renamed or deleted since it was
  resolved is nil (the caller tries elsewhere), never a 500 naming its
  path. Under `stream-threshold` the bytes are read whole; a larger file
  is streamed (`streamed`)."
  [req f inject-reload?]
  (try
    (let [html?   (= "html" (u-ext f))
          headers (cond-> {"Content-Type" (content-type f)}
                    inject-reload? (assoc "Cache-Control" "no-cache"))]
      (if (and html? inject-reload?)
        {:status 200 :headers headers
         :body (str/replace (slurp (fs/file f)) "</body>" (str reload-script "</body>"))}
        (let [fc (open-read f)
              n  (try (.size fc) (catch Throwable t (.close fc) (throw t)))]
          (if (< n stream-threshold)
            (with-open [fc fc] {:status 200 :headers headers :body (read-all fc n)})
            (streamed req fc headers)))))
    (catch java.io.IOException _ nil)))

(defn- value [x] (if (fn? x) (x) x))

(defn- bare-ip
  "`ip` (`--host`) lower-cased, without the brackets of `[::1]`."
  [ip]
  (let [ip (str/lower-case (str/trim (str ip)))]
    (if (and (str/starts-with? ip "[") (str/ends-with? ip "]")) (subs ip 1 (dec (count ip))) ip)))

(defn loopback-ip?
  "Does `ip` (`--host`) bind loopback only — decided on the address it
  resolves to, however it is spelled (`localhost`, `127.1`, `::1`,
  `0:0:0:0:0:0:0:1`, `127.0.0.2`; P4-D.3 item 9)? False when it does not
  resolve: the bind then fails on its own."
  [ip]
  (boolean
   ;; the address read from `toString` (`name/address`): babashka's image
   ;; allows no InetAddress method on Inet4Address or Inet6Address
   (try (let [a (last (str/split (str (java.net.InetAddress/getByName (bare-ip ip))) #"/"))]
          (or (str/starts-with? a "127.")
              (= a "0:0:0:0:0:0:0:1")))
        (catch Exception _ false))))

(defn- host-name
  "The name part of a `Host` header, lower-cased: `[::1]` kept bracketed,
  a port dropped."
  [host]
  (let [h (str/lower-case (str/trim (str host)))]
    (if (str/starts-with? h "[")
      (subs h 0 (inc (or (str/index-of h "]") (dec (count h)))))
      (str/replace h #":\d*$" ""))))

(def ^:private loopback-names #{"localhost" "127.0.0.1" "[::1]"})

(defn allowed-host-names
  "The `Host` names a server bound to `ip` answers: `localhost`,
  `127.0.0.1` and `[::1]`, and the bound address itself as given —
  `127.0.0.2`, `127.1`, `[::1]` for `::1` — so the URL it prints is never
  refused (P4-D.3 item 9)."
  [ip]
  (let [b (bare-ip ip)]
    (conj loopback-names (if (str/includes? b ":") (str "[" b "]") b))))

(defn local-host-header?
  "Is `host`, a request's `Host` header, one of `names` (default
  `localhost`, `127.0.0.1` and `[::1]`), with any port or none? A request
  with no `Host` (HTTP/1.0) is let through: a browser always sends one,
  and DNS rebinding needs it to be the attacker's name."
  ([host] (local-host-header? host loopback-names))
  ([host names]
   (or (nil? host) (contains? names (host-name host)))))

(def ^:private wrong-host
  {:status 403
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "403 Forbidden: this server listens on loopback and answers only for "
              "localhost, 127.0.0.1 and [::1].\n")})

(defn make-handler
  "The static handler over `root`, mounted at `:base` (default `/`): `/`
  redirects to the base, and nothing outside it is served. `root` and
  `:base` may be functions, read on every request — dev's follow `site.edn`.
  With `:inject-reload?` (dev), `/__reload` is the SSE channel, HTML gets
  the reload script, and — when `:state` says no build has succeeded yet —
  a page request gets `error-page`. A `pagefind/` file the live bundle no
  longer has is served from the previous one (`pagefind-fallback`). With
  `:check-host?` (a loopback bind), a request whose `Host` is not a
  loopback name, or one of `:host-names`, is a 403 (`local-host-header?`): a DNS-rebinding page
  cannot read the site, or the build errors, through a name of its own
  (P4-D.2 item 17)."
  [root {:keys [inject-reload? base state check-host? host-names] :or {base "/"}}]
  (fn [{:keys [uri headers] :as req}]
    (try
      (let [uri  (str uri)
            base (value base)
            root (value root)
            path (decode-path uri)]
        (cond
          (and check-host? (not (local-host-header? (get headers "host") (or host-names loopback-names))))
          wrong-host

          (and inject-reload? (= uri "/__reload"))
          (sse-handler req state)

          (nil? path)
          {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "400 Bad Request\n"}

          (and (not= "/" base) (or (= path "/") (= (str path "/") base)))
          {:status 302 :headers {"Location" (.toASCIIString (java.net.URI. nil nil base nil))} :body ""}

          (and inject-reload? state (not (:ever-ok? @state)) (:error @state) (html-request? uri))
          {:status 500
           :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
           :body (error-page (:error @state))}

          :else
          (let [rel (strip-base base path)]
            (or (when rel
                  (or (when-let [f (resolve-path root rel)] (file-response req f inject-reload?))
                      (when-let [f (pagefind-fallback root rel)] (file-response req f inject-reload?))))
                (not-found base path)))))
      (catch Throwable _ server-error))))

;; ---------------------------------------------------------------------------
;; Servers

(def default-host
  "Both servers listen on loopback unless told otherwise (`--host`): `bb dev`
  serves a working tree's output, and 0.2.0's every-interface default put it
  on the local network."
  "127.0.0.1")

(defn server-options
  "http-kit's `run-server` options for `--host` and `--port`."
  [{:keys [host port]}]
  {:ip (or (some-> host str str/trim not-empty) default-host)
   :port (or port 1888)})

(defn- listen-error
  "The `ex-info` for a server that could not listen on `ip`:`port`."
  [{:keys [ip port]} ^Throwable e]
  (let [cls (.getName (class e))
        why (cond
              (= "java.nio.channels.UnresolvedAddressException" cls)
              (str "the host " (pr-str ip) " does not resolve")
              (= "java.nio.channels.UnsupportedAddressTypeException" cls)
              (str "this machine cannot listen on an "
                   (if (str/includes? (str ip) ":") "IPv6" "IPv4") " address")
              (not (<= 0 (long (or port 0)) 65535))
              (str "a port is a number from 0 to 65535")
              (re-find #"(?i)in use" (str (ex-message e)))
              (str "port " port " is already in use")
              (re-find #"(?i)not available|assign" (str (ex-message e)))
              (str ip " is not an address of this machine")
              (re-find #"(?i)protocol family|address family" (str (ex-message e)))
              (str "this machine cannot listen on " ip)
              :else (or (ex-message e) cls))]
    (ex-info (str "clogem-press: cannot listen on " (if (str/includes? (str ip) ":") (str "[" (bare-ip ip) "]") ip) ":" port
                  " — " why ".\n  hint: Choose another port with `--port`, or listen on loopback with `--host 127.0.0.1`.")
             {:babashka/exit 1})))

(def ^:private listen-failures
  "The exceptions a bind can fail with, by class name: babashka exposes
  neither nio class."
  #{"java.nio.channels.UnresolvedAddressException"
    "java.nio.channels.UnsupportedAddressTypeException"})

(defn run-server!
  "`http/run-server`, as a var the tests can redefine. A host that does not
  resolve, is not this machine's or has no address family here (`::1` on
  an IPv4-only host), a port out of range or in use: each is an `ex-info`
  (exit 1) naming the host and port, not a stack trace."
  [handler {:keys [port] :as opts}]
  (when-not (and (integer? port) (<= 0 port 65535))
    (throw (listen-error opts (IllegalArgumentException. "port out of range"))))
  (try
    (http/run-server handler opts)
    (catch Exception e
      (if (or (instance? java.io.IOException e)
              (instance? IllegalArgumentException e)
              (listen-failures (.getName (class e))))
        (throw (listen-error opts e))
        (throw e)))))

(def ^:private wildcard-ips #{"0.0.0.0" "::" "0:0:0:0:0:0:0:0" "[::]"})

(defn bound-port
  "The port the server `run-server!` returned listens on — `--port 0`
  asks the system for one — else `port`."
  [server port]
  (or (:local-port (meta server))
      (try (http/server-port server) (catch Throwable _ nil))
      port))

(defn url-for
  "The URL to print for a server listening with `sopts`, at `base`. On a
  loopback or every-interface address it is `http://localhost:PORT<base>`,
  as 0.2.0 printed: an origin that is `localhost` is what tools allow-list
  — giscus's `originsRegex` of `http://localhost:[0-9]+` refuses
  `http://127.0.0.1:…`, and `http://0.0.0.0:…` too (P4-D.2 item 21)."
  [{:keys [ip port]} base]
  (str "http://"
       (cond (or (= ip default-host) (= ip "localhost") (wildcard-ips ip)) "localhost"
             ;; `[::1]` as given is bracketed once, not twice (P4-D.3 item 20)
             (str/includes? ip ":")     (str "[" (bare-ip ip) "]")
             :else                      ip)
       ":" port
       (.toASCIIString (java.net.URI. nil nil (str base) nil))))

(defn- listening-note
  "The line after the URL when the server listens on every interface."
  [{:keys [ip]}]
  (when (wildcard-ips ip)
    (str "clogem-press: listening on every interface (--host " ip "): other machines on the network can reach it")))

(defn- clean-base [b] (config/base-path {:site {:base b}}))

(defn- serve-cfg
  "The site's config for `serve`, or nil: `--site-dir` holds no config
  file, or it cannot be read — one warning, and `serve` falls back, as
  0.2.0 served regardless. Diagnostics are not printed: `serve` builds
  nothing, and a refused `:build :out` is already repaired to the default."
  [{:keys [site-dir config-file]}]
  (let [f (fs/path (or site-dir ".") (or config-file "site.edn"))]
    (when (fs/regular-file? f)
      (try
        (first (diag/collecting (config/load-config (str (or site-dir ".")) config-file nil)))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "warning: " f ": could not read it (" (first (str/split-lines (str (ex-message e))))
                          "), so the site is served at / from dist/."))
            (println "  hint: Fix it, or give the base with `--base` and the directory with `--dir`."))
          nil)))))

(defn serve-settings
  "`serve`'s {:base :dir} (P4-D.3 item 2). The base: `--base`, else the
  site's `:base`, else `/`. The directory: `--dir` (from the working
  directory), else `--out`, else the site's `:build :out` (both from
  `--site-dir`, as `build` reads them), else `dist`. The config is read
  only when one of them needs it."
  [{:keys [base dir out site-dir] :as opts}]
  (let [cfg (delay (serve-cfg opts))
        sd  (or site-dir ".")]
    {:base (if base (clean-base base) (if-let [c @cfg] (config/base-path c) "/"))
     :dir  (fs/absolutize
            (cond dir  dir
                  out  (fs/path sd out)
                  :else (if-let [c @cfg] (config/out-dir c) (fs/path sd "dist"))))}))

(defn serve-base
  "`serve`'s base (`serve-settings`)."
  [opts]
  (:base (serve-settings (assoc opts :dir "."))))

(defn start-serve!
  "Start `serve`'s server and return what `run-server!` returned."
  [opts]
  (let [{root :dir base :base} (serve-settings opts)
        sopts (server-options opts)]
    (when-not (fs/directory? root)
      (throw (ex-info (str "nothing to serve: " root " does not exist (run `bb build` first)")
                      {:babashka/exit 1})))
    (let [srv   (run-server! (make-handler root {:inject-reload? false :base base
                                                   :check-host? (loopback-ip? (:ip sopts))
                                                   :host-names (allowed-host-names (:ip sopts))}) sopts)
          sopts (assoc sopts :port (bound-port srv (:port sopts)))]
      (println (format "clogem-press: serving %s at %s" root (url-for sopts base)))
      (some-> (listening-note sopts) println)
      srv)))

(defn serve!
  [opts]
  (start-serve! opts)
  @(promise))

;; ---------------------------------------------------------------------------
;; The queue

(defn editor-temp?
  "Is `p` a file an editor or this program writes on the way to a save —
  never a change to rebuild for? Emacs lock and backup files (`.#x`, `x~`),
  vim's swap files and its `4913` write probe, `*.tmp`, the build's own
  atomic-write temp files (`.clogem-tmp-…`) and the watcher's probe."
  [p]
  (let [n (str (fs/file-name (str p)))]
    (boolean
     (or (str/starts-with? n ".#")
         (str/ends-with? n "~")
         (= n "4913")
         (re-find #"(?i)\.(swp|swo|swx|tmp)$" n)
         (str/includes? n ".clogem-tmp-")
         (str/starts-with? n ".clogem-watch-probe-")))))

(def quiet-ms
  "§5.4's debounce: a batch is drained once this long passes with no event."
  100)

(defn drain-loop!
  "Collect submitted path batches until `quiet-ms` pass with none, then call
  `on-batch` once with every distinct path, sorted; repeat. `take!` is
  `(take! timeout-ms)` → a batch (a seq of paths), nil on timeout, or
  `::stop`; a nil timeout means block until something arrives. Nothing is
  run while a batch is still arriving, and `on-batch` runs on this thread,
  so batches never overlap: events during a rebuild wait for the next one.
  A `Throwable` from `on-batch` is reported and the loop goes on."
  [{:keys [take! on-batch quiet-ms] :or {quiet-ms quiet-ms}}]
  (loop [pending (sorted-set)]
    (let [ev (take! (when (seq pending) quiet-ms))]
      (cond
        (= ::stop ev) nil
        (some? ev)    (recur (into pending (map str) ev))
        (seq pending) (do (try (on-batch (vec pending))
                               (catch Throwable t
                                 (println "clogem-press: the rebuild loop caught"
                                          (str (.getName (class t)) ":") (or (ex-message t) ""))))
                          (recur (sorted-set)))
        :else         (recur pending)))))

(defn queue-take
  "`drain-loop!`'s `take!` over a blocking queue."
  [^LinkedBlockingQueue q]
  (fn [timeout]
    (if timeout
      (.poll q (long timeout) TimeUnit/MILLISECONDS)
      (.take q))))

(defn submitter
  "The function every watcher calls with the paths it saw: editor temp files
  dropped, the rest queued for `drain-loop!`."
  [^LinkedBlockingQueue q]
  (fn [paths]
    (when-let [ps (seq (remove editor-temp? paths))]
      (.put q (vec ps)))))

(defn background-runner
  "A function that asks for `f` to run on its own thread: one run at a time,
  and any number of requests during a run coalesce into one more run after
  it. `spawn` starts a thread (default `future-call`). A `Throwable` from `f`
  is reported and does not stop later runs. Once `cancelled?` says so, no
  run starts — a request queued behind the running one included (P4-D.2
  item 2). The function's metadata carries `:await`, `(await ms)`: wait up
  to `ms` for the running run to end; true when none is running."
  ([f] (background-runner f future-call))
  ([f spawn] (background-runner f spawn (constantly false)))
  ([f spawn cancelled?]
   (let [st (atom {:running? false :again? false})]
     (with-meta
       (fn request! []
         (when-not (cancelled?)
           (let [[old _] (swap-vals! st (fn [s] (if (:running? s)
                                                 (assoc s :again? true)
                                                 (assoc s :running? true :again? false))))]
             (when-not (:running? old)
               (spawn (fn []
                        (loop []
                          (when-not (cancelled?)
                            (try (f)
                                 (catch Throwable t
                                   (println "clogem-press: search index failed —" (or (ex-message t) (str t))))))
                          (let [stop? (cancelled?)
                                [o _] (swap-vals! st (fn [s] (if (and (:again? s) (not stop?))
                                                               (assoc s :again? false)
                                                               (assoc s :running? false :again? false))))]
                            (when (and (:again? o) (not stop?)) (recur)))))))))
         nil)
       {:await (fn [ms]
                 (let [deadline (+ (System/currentTimeMillis) ms)]
                   (loop []
                     (cond (not (:running? @st))                     true
                           (> (System/currentTimeMillis) deadline)  false
                           :else (do (Thread/sleep 10) (recur))))))}))))

;; ---------------------------------------------------------------------------
;; Watching

(defn theme-dir
  "The theme directory to watch when it is on disk (a generator checkout, not
  a jar): its `resources/` — CSS, JS, fonts, UI strings, all read fresh on
  every build — or, with `reload-code?`, the whole `clogem/theme/` directory,
  `.clj` included. Nil otherwise."
  [reload-code?]
  (when-let [res (try (assets/theme-resource-dir) (catch Throwable _ nil))]
    (when (fs/directory? res)
      (if reload-code? (fs/parent res) res))))

(defn watched-paths
  [cfg & [{:keys [reload-code?]}]]
  (->> [(config/content-dir cfg) (config/assets-dir cfg) (config/strings-dir cfg)
        (fs/path (config/site-dir cfg) "overrides")
        (fs/path (:clogem/config-file cfg))
        (theme-dir reload-code?)]
       (remove nil?)
       (filter fs/exists?)
       vec))

(defn- norm [p] (str (fs/normalize (fs/absolutize (str p)))))

(defn- merge-specs
  "One spec per path: recursive if any is, and then no `:only`; else the
  union of the `:only` sets."
  [specs]
  (->> (group-by :path specs)
       vals
       (map (fn [ss]
              (if (some :recursive? ss)
                {:path (:path (first ss)) :recursive? true}
                {:path (:path (first ss)) :recursive? false :only (into #{} (mapcat :only) ss)})))
       (sort-by :path)
       vec))

(defn watch-specs
  "Every path `bb dev` watches with the fswatcher pod, whether it exists yet
  or not, as {:path :recursive? :only}: the content, assets, i18n and
  overrides directories and the theme's (recursively), and the config
  file's DIRECTORY, not recursively, `:only` that file. A watch on the
  file itself stays on its inode, so a save that replaces it by rename —
  `sed -i`, vim, emacs, any atomic-save editor — left every later edit
  unseen; a watch on its directory sees the rename. Only the config file's
  own events count (`watched-event?`): `permalinks.edn` and `dist/` beside
  it are not changes. A config file that is a symbolic link is watched at
  its target too — that directory, `:only` the target — since an edit of
  the target never touches the link's directory (P4-D.2 item 4); the
  specs are recomputed from every build's config, so a link pointed
  elsewhere is followed."
  [cfg & [{:keys [reload-code?]}]]
  (let [cf     (some-> (:clogem/config-file cfg) norm)
        target (when (and cf (fs/sym-link? cf))
                 (try (norm (fs/real-path cf)) (catch Exception _ nil)))]
    (merge-specs
     (concat
      (for [p [(config/content-dir cfg) (config/assets-dir cfg) (config/strings-dir cfg)
               (fs/path (config/site-dir cfg) "overrides")]]
        {:path (norm p) :recursive? true})
      (when cf [{:path (norm (fs/parent cf)) :recursive? false :only #{cf}}])
      (when target [{:path (norm (fs/parent target)) :recursive? false :only #{target}}])
      (when-let [t (theme-dir reload-code?)] [{:path (norm t) :recursive? true}])))))

(defn- in-dir? [dir p]
  (or (= p dir) (str/starts-with? p (str dir File/separator))))

(defn watched-event?
  "Is `p`, a path the pod reported, a change `specs` watch for: inside a
  recursive spec's directory, or one of a spec's `:only` files?"
  [specs p]
  (let [p (norm p)]
    (boolean
     (some (fn [{:keys [path only recursive?]}]
             (or (contains? only p)
                 (and recursive? (in-dir? path p))
                 ;; the watched directory itself, seen from its parent's watch
                 (= p path)))
           specs))))

(defn dir-to-watch?
  "Does `p` — an accepted event's path — name a directory the pod must be
  told about: one that exists now, at or under a recursive spec's
  directory? The pod's recursive watch covers only the subdirectories
  that existed when it was registered (P4-D.2 item 1), and a watched
  directory replaced is a new inode its old watch never sees (item 3)."
  [specs p]
  (let [p (norm p)]
    (boolean
     (and (some #(and (:recursive? %) (in-dir? (:path %) p)) specs)
          (fs/directory? p {:nofollow-links true})))))

(defn- snapshot
  [paths]
  (into {}
        (for [p paths
              f (if (fs/directory? p) (fs/glob p "**") [p])
              ;; a file deleted between the listing and the stat is simply
              ;; not in this snapshot
              :let [t (try (when (fs/regular-file? f)
                             (fs/file-time->millis (fs/last-modified-time f)))
                           (catch Exception _ nil))]
              :when t]
          [(str f) t])))

(defn poll-watch!
  "The `--poll` fallback of §5.4: a `babashka.fs`-based modified-since loop.

  Required in practice — a container where fsnotify registers but never fires is
  reproducible, and is the reason this is designed in rather than bolted on.
  `paths-fn` is called on every pass, so a `site.edn` change that moves
  `:content :dir` is followed without a restart. A `Throwable` in one pass
  is reported and the loop goes on: an `Error` used to end the polling
  thread silently, and with it every rebuild."
  [paths-fn interval on-change & [{:keys [stop?] :or {stop? (constantly false)}}]]
  (loop [prev (snapshot (paths-fn))]
    (Thread/sleep (long interval))
    (when-not (stop?)
      (let [now (try (snapshot (paths-fn))
                     (catch Throwable t
                       (println "clogem-press: polling failed —" (or (ex-message t) (str t)))
                       prev))]
        (when (not= prev now)
          (let [changed (->> (concat (keys now) (keys prev))
                             distinct
                             (remove #(= (get prev %) (get now %)))
                             sort vec)]
            (try (on-change changed)
                 (catch Throwable t
                   (println "clogem-press: polling failed —" (or (ex-message t) (str t)))))))
        (recur now)))))

(def signal-grace-ms
  "How long a failure that a stop may have caused — a Pagefind stopped by
  SIGTERM, a pod that died loading or registering — waits for dev's own
  `stopping` before it is reported: the process group's signal and the
  JVM's shutdown hook are not ordered."
  1000)

(defn- soon?
  "Does `(pred)` become true within `ms`?"
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- stop-caused?
  "Is dev stopping, or does it start to within `signal-grace-ms`?
  (`stopping?` nil: never.)"
  [stopping?]
  (boolean (and stopping? (soon? stopping? signal-grace-ms))))

(def watch-delay-ms
  "The fswatcher pod's event-coalescing window, in ms.

  Measured, not guessed: with the option left at its default this container
  delivered every event **exactly 2002 ms after the write**, invariant to how
  long the watcher had been registered (gaps of 200/900/1800 ms all gave
  2002 ms). That rules out a poll clock — a poller is anchored to its own
  interval, not to the write — and identifies it as the pod's debounce.
  Setting it explicitly tracks it one-for-one: `:delay-ms 100` → 101 ms,
  `:delay-ms 500` → 501 ms.

  100 ms is not an arbitrary choice: §5.4 already specifies a
  drain-until-100ms-quiet debounce for the dev loop, so this is that same
  number, applied where the coalescing actually happens."
  100)

(def registration-gap-ms
  "The pause between two `watch` calls (§11.3 item 12). Measured in this
  project's container: back-to-back registrations of `bb dev`'s paths hung
  the pod 55–70% of the time (5 of 6 in a re-run), and with a gap of 10 ms or
  more 50 of 50 succeeded; through `try-pod-watch!` the pod attached 3 times
  in 10 without a gap and 10 in 10 with 20 ms."
  20)

(def default-probe-ms
  "Total budget for proving the watcher works, in ms.

  Delivery is the cheap half — ~101 ms with `watch-delay-ms` set. The budget is
  sized for the expensive half: `watch` is a synchronous call into a subprocess
  and intermittently never returns (reproduced with the bare pod and no
  clogem-press code, so it is the pod or the container, not this program). Three
  seconds is long enough that a healthy-but-loaded registration is not written
  off, and short enough to be tolerable at startup on the runs where it is spent.
  `--probe-ms` tunes it; it is a *shared* deadline across registration and
  delivery, so it is the worst case, not half of one."
  3000)

(def ^:private live-probes
  "The probe files `probe-watch!` has on disk now. Its `finally` removes
  each, but at Ctrl-C the JVM exits once the shutdown hook is done, with
  that `finally` never run: `stop!` removes them (`remove-probes!`)."
  (atom #{}))

(defn remove-probes!
  "Delete every probe file a `probe-watch!` still has on disk (P4-D.3
  item 12)."
  []
  (doseq [p @live-probes]
    (try (fs/delete-if-exists p) (catch Throwable _ nil))
    (swap! live-probes disj p)))

(defn probe-watch!
  "Register `watch` over every watched path, then **prove** the watcher delivers.

  Registration is not evidence. §5.4 and Appendix A item 5 both record the
  environment this exists for: the fswatcher pod loads, `watch` returns a
  watcher id, and no event ever arrives. Reporting success there is the worst
  outcome available — the dev server comes up, announces that it is watching,
  and then silently never rebuilds, which reads to the user as a caching bug
  somewhere else entirely.

  So: touch a temp file inside a watched directory and wait for its event. On
  silence, unwatch everything, say so, and let the caller fall back to polling.
  The probe's own event is swallowed rather than forwarded, or starting
  `bb dev` would rebuild twice.

  **Registration is on the clock too.** `watch` is a synchronous call into a
  subprocess, and it was observed here to block indefinitely — which hung
  `bb dev` before it had printed anything at all, a strictly worse failure than
  the dead-watcher one this function exists to prevent. It therefore runs on
  its own thread against the same budget; if it overruns we abandon it (there
  is nothing to unwatch — the call never returned) and poll. The callback
  checks `abandoned?` so a late registration cannot start driving rebuilds
  behind the poll loop's back. The calls are `gap-ms` apart
  (`registration-gap-ms`): back-to-back ones are what hangs.

  `watch`/`unwatch` (and `sleep`, for the gap) are injected so every outcome
  is testable without the pod — and so the zero-event case, which by
  definition cannot be reproduced where events do fire, is covered anyway.

  `specs` (default: the `watch-specs` of `cfg` that exist) are registered,
  each `:recursive?` or not; an event reaches `on-change` only when
  `accept?` (default: every path) says so. On success, `on-ready` is called
  with a `register!` of one more spec on the same callback — what dev's
  follower uses for a directory created later (§5.4) — and a map of
  `:unwatch!` (the same spacing) and `:watchers`, the startup
  registrations by path, so a replaced directory's old watch can go."
  [cfg on-change {:keys [watch unwatch timeout-ms gap-ms sleep specs accept? on-ready stopping?]
                  :or   {timeout-ms default-probe-ms
                         gap-ms     registration-gap-ms
                         sleep      #(Thread/sleep (long %))
                         accept?    (constantly true)}}]
  (let [specs (or specs (filterv #(fs/exists? (:path %)) (watch-specs cfg)))
        ;; dev stopping — Ctrl-C reached the pod too: a failure here is
        ;; the stop, not news (P4-D.3 item 14)
        say!      (fn [& xs] (when-not (stop-caused? stopping?) (apply println xs)))
        dirs  (filterv #(and (:recursive? %) (fs/directory? (:path %))) specs)]
    (if (empty? dirs)
      (do (println "clogem-press: no watchable directory to probe; using polling")
          false)
      (let [probe     (fs/path (:path (first dirs)) (str ".clogem-watch-probe-" (System/nanoTime)))
            probe-s   (norm probe)
            seen      (promise)
            abandoned? (atom false)
            callback  (fn [ev]
                        (let [p (str (:path ev))]
                          (cond
                            (= (norm p) probe-s) (deliver seen true)
                            @abandoned?          nil
                            (not (accept? p))    nil
                            ;; the event's type rides along as metadata:
                            ;; whether a directory is new decides its
                            ;; registration (P4-D.3 item 5)
                            :else          (try (on-change (with-meta [p] {:type (:type ev)}))
                                                (catch Throwable t
                                                  (println "clogem-press: watcher callback failed —"
                                                           (or (ex-message t) (str t))))))))
            watch-1   (fn [{:keys [path recursive?]}]
                        (watch (str path) callback {:recursive (boolean recursive?)
                                                    :delay-ms watch-delay-ms}))
            reg       (promise)
            ;; One deadline shared by registration and delivery. Giving each its
            ;; own `timeout-ms` made the advertised window the *half* of a worst
            ;; case that was silently twice as long.
            deadline  (+ (System/currentTimeMillis) timeout-ms)
            remaining #(max 0 (- deadline (System/currentTimeMillis)))]
        (future
          (deliver reg (try {:watchers (vec (map-indexed
                                             (fn [i spec]
                                               (when (pos? i) (sleep gap-ms))
                                               (watch-1 spec))
                                             specs))}
                            (catch Throwable e {:error (or (ex-message e) (str e))}))))
        (let [{:keys [watchers error] :as r} (deref reg (remaining) ::timeout)]
          (cond
            (= ::timeout r)
            (do (reset! abandoned? true)
                (say! (format (str "clogem-press: the fswatcher pod did not finish registering "
                                      "in %d ms; falling back to polling.")
                                 timeout-ms))
                false)

            error
            (do (reset! abandoned? true)
                (say! "clogem-press: fswatcher could not watch —" error)
                false)

            :else
            (try
              (let [t0 (System/currentTimeMillis)]
                (swap! live-probes conj probe)
                (spit (fs/file probe) "clogem-press watch probe\n")
                (if (deref seen (remaining) false)
                  (do (println (format "clogem-press: fswatcher delivered a probe event in %d ms"
                                       (- (System/currentTimeMillis) t0)))
                      (when on-ready
                        (on-ready
                         ;; the same spacing, and a hung call is given up
                         ;; on after the probe budget rather than blocking
                         ;; its caller for ever
                         (fn register! [spec]
                           (locking watch-1
                             (sleep gap-ms)
                             (let [r (deref (future (try (watch-1 spec) (catch Throwable e e)))
                                            timeout-ms ::timeout)]
                               (cond
                                 (= ::timeout r)        (throw (ex-info "the fswatcher pod did not answer" {}))
                                 (instance? Throwable r) (throw r)
                                 :else                  r))))
                         {:unwatch!  (fn [w] (locking watch-1 (sleep gap-ms) (unwatch w)))
                          :watchers  (zipmap (map :path specs) watchers)}))
                      true)
                  (do (reset! abandoned? true)
                      (say!
                       (format (str "clogem-press: fswatcher registered but delivered no event "
                                    "within the %d ms probe budget — this environment does not "
                                    "deliver filesystem events (DESIGN.md Appendix A item 5). "
                                    "Falling back to polling.")
                               timeout-ms))
                      (doseq [w watchers] (try (unwatch w) (catch Throwable _ nil)))
                      false)))
              (finally
                (try (fs/delete-if-exists probe) (catch Throwable _ nil))
                (swap! live-probes disj probe)))))))))

(defn file-key
  "The identity of the file at `p` — device and inode on POSIX — as a
  string, or nil (gone, or a filesystem without one). A directory deleted
  and created again is the same path and another key."
  [p]
  (try (some-> (java.nio.file.Files/readAttributes
                (fs/path p) java.nio.file.attribute.BasicFileAttributes
                ^"[Ljava.nio.file.LinkOption;" (make-array java.nio.file.LinkOption 0))
               .fileKey str)
       (catch Exception _ nil)))

(defn covered-dirs
  "Every directory at or under `dir` now, as path → `file-key`: what a
  recursive registration of `dir` made now covers. Walked BEFORE the
  registration, so a directory created in between is merely registered
  twice, never missed."
  [dir]
  (into {}
        (map (fn [p] (let [p (norm p)] [p (file-key p)])))
        (cons (fs/path dir)
              (try (filter #(fs/directory? % {:nofollow-links true}) (fs/glob dir "**" {:hidden true}))
                   (catch Exception _ nil)))))

(defn covered?
  "Does a registration in `registered` already cover the directory `p`
  (`covered-dirs`, under the same file key: a directory replaced is a
  new inode no old watch sees)? The pod's recursive watch covers the
  subdirectories that existed when it was made (P4-D.3 item 5)."
  [registered p]
  (let [p (norm p) k (file-key p)]
    (boolean
     (some (fn [{:keys [covers watcher]}]
             (and watcher (contains? covers p) (= k (get covers p))))
           (vals registered)))))

(defn exhausted?
  "Is `e`, a failed `watch`, the system's inotify limit — instances
  (EMFILE: too many open files) or watches (ENOSPC: no space left on
  device)? Retrying cannot help that one."
  [^Throwable e]
  (boolean (re-find #"(?i)too many open files|no space left on device|EMFILE|ENOSPC|inotify_init|user limit"
                    (str (ex-message e) " " (some-> (ex-cause e) ex-message)))))

(defn register-dir!
  "Register `spec` with the pod now, on behalf of `registered` — an atom of
  path → {:key :watcher :spec :covers} — then unwatch the registration it
  replaces (P4-D.2 item 3: each re-registration used to leak one). The new
  watch is made first, so nothing is missed between the two; when it
  fails, the old one stays and the path is retried on the follower's next
  pass, reported once (P4-D.3 item 5 — the old watch used to go first,
  and nothing retried). The system's inotify limit (`exhausted?`) is
  rethrown, as `::exhausted`, for the caller to give up on the pod. True
  when registered."
  [registered {:keys [path] :as spec} register! unwatch!]
  (let [k      (file-key path)
        old    (get @registered path)
        covers (when (:recursive? spec) (covered-dirs path))]
    (try
      (let [w (register! spec)]
        (swap! registered assoc path {:key k :watcher w :spec spec :covers covers})
        (when-let [w (:watcher old)]
          (try (unwatch! w) (catch Throwable _ nil)))
        true)
      (catch Throwable e
        (when (exhausted? e)
          (throw (ex-info (or (ex-message e) (str e)) {::exhausted true} e)))
        (swap! registered assoc path (assoc (or old {:spec spec}) :retry? true :reported? true))
        (when-not (:reported? old)
          (println (str "clogem-press: cannot watch " path " ("
                        (or (ex-message e) (str e))
                        "); retrying, or restart `bb dev`, or run it with `--poll`.")))
        false))))

(defn release-gone!
  "Unwatch, and forget, every registration in `registered` whose directory
  is gone — run on every pass of the follower, so a stream of events can
  never starve it (P4-D.3 item 5: a mkdir/rm loop leaked one instance per
  cycle). Returns the paths released."
  [registered unwatch!]
  (vec (for [[p {:keys [watcher]}] @registered
             :when (not (fs/exists? p))]
         (do (when watcher (try (unwatch! watcher) (catch Throwable _ nil)))
             (swap! registered dissoc p)
             p))))

(def ^:private fresh-types
  "The pod's event types after which a directory is not the one any walk
  saw: made, removed (an `rm -rf` and `mkdir` within the pod's window is
  one `:remove` for a directory that exists again, often under the same
  inode), or renamed."
  #{:create :remove :rename})

(defn uncover!
  "Drop `p` and everything under it from every registration's `:covers`."
  [registered p]
  (let [p (norm p)]
    (swap! registered
           (fn [m] (into {} (for [[k r] m]
                              [k (cond-> r (:covers r)
                                   (update :covers #(into {} (remove (fn [[d _]] (in-dir? p d))) %)))]))))))

(defn register-new-dirs!
  "Register the directories `events` name — [path type] pairs the pod's
  watches sent, the type nil when unknown — shallowest first, so a parent
  registered in this batch covers its children. A `fresh-types` event
  first drops the path from every registration's cover (`uncover!`); then
  a directory registers only when no registration covers it (`covered?`,
  P4-D.3 item 5: `chmod -R` over existing subdirectories took one inotify
  instance each). An event of unknown type registers its directory
  regardless, as D.2 did. Returns the paths registered."
  [registered events register! unwatch!]
  (let [evs (into {} (map (fn [[p t]] [(norm p) t])) events)]
    (doseq [[p t] evs :when (fresh-types t)] (uncover! registered p))
    (vec (for [[d t] (sort-by #(count (key %)) evs)
               :when (and (fs/directory? d {:nofollow-links true})
                          (or (nil? t) (not (covered? @registered d)))
                          (register-dir! registered {:path d :recursive? true} register! unwatch!))]
           d))))

(defn follow-new-paths!
  "One pass of dev's follower, with the pod (§5.4), over `registered` (an
  atom of path → {:key :watcher :spec :covers}, `register-dir!`): forget —
  and unwatch — every registration whose directory is gone
  (`release-gone!`); register every spec of `specs`, and every
  registration already made, whose directory exists and is not
  registered, is registered under another file key, or failed and is due
  a retry — `overrides/` created while `bb dev` runs, the directory a
  `site.edn` edit moved `:content :dir` to, or a watched directory replaced
  between two passes, whose old watch died with its inode (P4-D.2 item 3).
  The key is a backstop: ext4 hands a directory deleted and made again the
  same inode at once, so the event naming the new directory — from its
  parent's watch, or its own dying one — is what re-registers it
  (`dir-to-watch?`). Returns the paths it registered, for a rebuild."
  [specs registered register! & [unwatch!]]
  (let [unwatch! (or unwatch! (fn [_]))]
    (release-gone! registered unwatch!)
    (let [todo (vals (into {} (map (juxt :path identity))
                           (concat (keep :spec (vals @registered)) specs)))]
      (vec (keep (fn [{:keys [path] :as spec}]
                   (when (fs/exists? path)
                     (let [old (get @registered path)
                           k   (file-key path)]
                       (when (and (or (nil? old) (:retry? old) (and k (not= k (:key old))))
                                  (register-dir! registered spec register! unwatch!))
                         path))))
                 (sort-by :path todo))))))

(defn new-dir-batch
  "What a directory `register-dir!` has just registered changes: itself and
  every file under it now. Files created between the directory and its
  registration were seen by no watch, so they are rescanned."
  [dir]
  (into [(str dir)]
        (comp (filter #(fs/regular-file? % {:nofollow-links true})) (map str))
        (try (fs/glob dir "**") (catch Exception _ nil))))

(defn one-line
  "An error message as one line for dev's terminal: without the
  `clogem-press: <area>: ` prefix the line already carries, and with a
  `hint:` line kept: after a semicolon, or a space after a full stop or a
  colon."
  [msg]
  (->> (str/split-lines (str msg))
       (map str/trim)
       (remove str/blank?)
       (map #(str/replace % #"^clogem-press: (?:[a-z]+: )?" ""))
       (map #(str/replace % #"^hint: " ""))
       (map #(str/replace % #"\s{2,}" " "))
       (reduce (fn [acc line]
                 (cond (empty? acc)                   line
                       (re-find #"[.!?:]$" acc)       (str acc " " line)
                       :else                          (str acc "; " line)))
               "")))

(defn tool-output
  "A tool's failure for dev's terminal (P4-D.2 item 27): its first line
  without the `clogem-press: <area>: ` prefix, the tool's own output
  below it, indented, then the hint — not `one-line`'s run-on line, which
  turned Pagefind's report into `exited 3:; Running Pagefind …; …`."
  [msg]
  (let [[head & more] (->> (str/split-lines (str msg)) (map str/trimr) (remove str/blank?))
        hint?          #(re-find #"^\s*hint: " %)]
    (str/join "\n"
              (concat [(str/replace (str head) #"^clogem-press: (?:[a-z]+: )?" "")]
                      (map #(str "    " (str/trim %)) (remove hint? more))
                      (map #(str "  " (str/trim %)) (filter hint? more))))))

(defn- gated-writer
  "A Writer onto `w` that drops what is written while `(quiet?)` says so.
  `quiet?` may take its time (`stop-caused?`), so it is asked once per
  burst — a `println` is several writes — and the answer kept 2 s."
  [^java.io.Writer w quiet?]
  (let [decided (atom nil)
        quiet!  (fn []
                  (let [now (System/currentTimeMillis)
                        {:keys [until q]} @decided]
                    (if (and until (< now until))
                      q
                      (let [q (boolean (quiet?))]
                        (reset! decided {:until (+ (System/currentTimeMillis) 2000) :q q})
                        q))))]
    (proxy [java.io.Writer] []
      (write
        ([x] (when-not (quiet!) (if (string? x) (.write w ^String x) (.write w (int x)))))
        ([x off len] (when-not (quiet!) (if (string? x) (.write w ^String x (int off) (int len)) (.write w ^chars x (int off) (int len))))))
      (flush [] (.flush w))
      (close [] nil))))

(defn load-pod!
  "Fetch the fswatcher pod through `clogem.tools` — the pinned, per-platform
  sha256, cached under XDG, or `CLOGEM_FSWATCHER` / `:tools :fswatcher
  :path` — and load it from that path. Never through the pod registry, which
  runs whatever it downloads unverified. Returns {:watch :unwatch :pod-id}
  (`unload-pod!` takes the id), or nil
  after one line saying why (offline on the first run, no asset for this
  platform, a hash that does not match): the caller polls."
  [cfg & [stopping?]]
  (try
    (let [bin (tools/ensure-binary! tools/fswatcher cfg)]
      ;; `babashka.pods` is required in the ns form on purpose. Resolving it
      ;; lazily — `(requiring-resolve 'babashka.pods/load-pod)` — deadlocks:
      ;; by the time `dev!` reaches this point the http-kit server threads are
      ;; running and `clogem.cli` has itself been pulled in by `requiring-resolve`,
      ;; and the load never returns. Silently: no watcher, no fallback, no message.
      ;; Reproduced deterministically and fixed by loading it up front, which costs
      ;; nothing — it is a babashka built-in (Appendix A item 1).
      ;;
      ;; `pod.babashka.fswatcher` still has to be resolved late: it does not exist
      ;; until load-pod has run.
      ;; babashka's pod client prints `ERROR: Pod quit unexpectedly` to the
      ;; `*err*` of this call when the pod's process ends — which the
      ;; Ctrl-C that stops dev does: dropped then (P4-D.3 item 14)
      ;; Only the pod client writes there, and only when something failed,
      ;; so waiting for the hook's flag (`stop-caused?`) costs nothing.
      (let [{:pod/keys [id]} (binding [*err* (gated-writer *err* #(stop-caused? stopping?))]
                               (pods/load-pod (str bin)))]
        {:watch   (requiring-resolve 'pod.babashka.fswatcher/watch)
         :unwatch (requiring-resolve 'pod.babashka.fswatcher/unwatch)
         :pod-id  id}))
    (catch Throwable e
      (when-not (stop-caused? stopping?)
        (println (str "clogem-press: the fswatcher pod is unavailable, so dev watches by polling: "
                      (one-line (or (ex-message e) (str e))))))
      nil)))

(defn unload-pod!
  "Stop the pod process `id` names, giving up after a second: at Ctrl-C it
  has had the SIGINT too, and may be gone."
  [id]
  (when id
    (deref (future (try (pods/unload-pod id) (catch Throwable _ nil))) 1000 nil)))

(defn try-pod-watch!
  "Load the fswatcher pod and probe it. Returns {:pod-id} only if it
  registered *and* delivered an event within the probe window; otherwise
  false, with the pod unloaded."
  [cfg on-change & [opts]]
  (if-let [fns (load-pod! cfg (:stopping? opts))]
    (let [ok? (try
                (probe-watch! cfg on-change (merge (dissoc fns :pod-id) (into {} (remove (comp nil? val)) opts)))
                (catch Throwable e
                  (when-not (stop-caused? (:stopping? opts))
                    (println "clogem-press: fswatcher pod unavailable —" (ex-message e)))
                  false))]
      (if ok?
        {:pod-id (:pod-id fns)}
        (do (unload-pod! (:pod-id fns)) false)))
    false))

;; ---------------------------------------------------------------------------
;; The rebuild

(defn error-info
  "{:message :file} for the overlay, from a failed build's exception: the
  file of its first diagnostic error, or the one its ex-data names."
  [^Throwable e]
  (let [d (ex-data e)]
    {:message (or (ex-message e) (str e))
     :file    (or (some-> (:clogem/file d) str)
                  (some-> d :clogem/errors first :path str))}))

(defn linked-stylesheets
  "The stylesheets under `<out>/clogem/` — the theme's and the site's
  `overrides/custom.css`. A rebuild that adds or removes one must reload the
  page: the client's CSS swap only re-fetches the links a page already has."
  [out]
  (let [d (fs/path out "clogem")]
    (if (fs/directory? d)
      (into (sorted-set)
            (comp (filter #(fs/regular-file? % {:nofollow-links true}))
                  (map #(str/replace (str (fs/relativize d %)) "\\" "/")))
            (fs/glob d "**.css"))
      (sorted-set))))

(defn gone-temp?
  "Is `p`, a changed path, the temp file of a rename-style save — gone by
  now, and named as editors and tools name them: an `editor-temp?` name, a
  name with no extension (`sed -i`'s `sedAb12Cd`), or JetBrains' and
  GNOME's (`x___jb_tmp___`, `.goutputstream-…`)? A deleted page has an
  extension and is never one."
  [p]
  (let [n (str (fs/file-name (str p)))]
    (boolean
     (and (not (fs/exists? (str p) {:nofollow-links true}))
          (or (editor-temp? p)
              (nil? (u-ext p))
              (re-find #"___jb_(?:tmp|old)___$|^\.goutputstream-" n))))))

(defn reload-kind
  "`css` when every changed file is a stylesheet, the set of stylesheets is
  what it was, and the last build succeeded; else `reload`. The temp file
  of a rename-style save is not a change (`gone-temp?`): `sed -i` on
  custom.css reloaded the page instead of swapping it (P4-D.2 item 23)."
  [changed sheets-before sheets-after last-ok?]
  (let [changed (remove gone-temp? changed)]
    (if (and (seq changed)
             last-ok?
             (= sheets-before sheets-after)
             (every? #(str/ends-with? (str/lower-case (str %)) ".css") changed))
      "css"
      "reload")))

(defn theme-namespaces
  "The theme namespaces the changed `.clj` files under `dir` (the theme
  directory, `clogem/theme/`) define, for `--reload-code`."
  [dir changed]
  (let [root (some-> dir fs/parent fs/parent)]
    (vec (for [p changed
               :let [p (fs/normalize (fs/absolutize p))]
               :when (and root (str/ends-with? (str p) ".clj")
                          (str/starts-with? (str p) (str (fs/normalize (fs/absolutize dir)) File/separator)))]
           (-> (str (fs/relativize (fs/normalize (fs/absolutize root)) p))
               (str/replace #"\.clj$" "")
               (str/replace File/separator ".")
               (str/replace "/" ".")
               (str/replace "_" "-")
               symbol)))))

(defn make-rebuild
  "The dev loop's one rebuild, as a function of the changed paths.

  - `build` — `clogem.cli/build`, already given dev's options;
  - `out` — the output directory (or a function returning it), for the
    stylesheet comparison;
  - `state` — an atom {:ever-ok? :error :last-ok?} the handler reads;
  - `index!` — asks for a background search index (`background-runner`);
  - `reload-code` — the theme directory whose changed `.clj` to reload;
  - `on-config` — called with the config each successful build used;
  - `on-reload` — called just before a `reload` is sent.

  On success: one line, then `reload` or `css` to every client, then the
  index request — so the page is back before Pagefind starts. On failure,
  `Throwable` included: one line, and `build-error` to every client."
  [{:keys [build out state index! reload-code on-config notify! notify-error! on-reload]
    :or {notify! notify-clients! notify-error! notify-error!}}]
  (fn rebuild! [changed]
    (let [t0     (System/currentTimeMillis)
          before (linked-stylesheets (value out))]
      (try
        (when reload-code
          (doseq [ns-sym (theme-namespaces reload-code changed)]
            (require ns-sym :reload)
            (println "clogem-press: reloaded" ns-sym)))
        (let [result  (build)
              after   (linked-stylesheets (value out))
              last-ok? (:last-ok? @state true)
              kind    (reload-kind changed before after last-ok?)]
          (swap! state assoc :ever-ok? true :last-ok? true :error nil)
          (println (format "clogem-press: rebuilt in %d ms (%s)"
                           (- (System/currentTimeMillis) t0)
                           (cond
                             (empty? changed)      "initial"
                             (<= (count changed) 3) (str/join ", " (map #(str (fs/file-name %)) changed))
                             :else                 (str (count changed) " files changed"))))
          ;; before the reload is sent: from here on, pages load the
          ;; bundle live now (`indexer`'s keep-since)
          (when (and on-reload (= "reload" kind)) (on-reload))
          (notify! kind)
          (when-let [cfg (:clogem/cfg result)]
            (when on-config (on-config cfg)))
          (when (and index! (= :deferred (:search result)))
            (index! (:clogem/cfg result)))
          result)
        ;; Throwable: a StackOverflowError or an AssertionError in a render
        ;; must not kill the loop
        (catch Throwable e
          ;; dev is stopping: nothing written, nothing to report (P4-D.3 item 13)
          (when-not (u/cancelled-ex? e)
            (let [err (error-info e)]
              (swap! state assoc :last-ok? false :error err)
              (println "clogem-press: build failed —" (:message err))
              (notify-error! err)))
          nil)))))

(def ^:private sigint-exit
  "Pagefind's exit code when SIGINT stopped it: Ctrl-C reaches the whole
  process group, Pagefind included, before dev's shutdown has begun, so
  this one is never reported."
  130)

(def ^:private sigterm-exit
  "Pagefind's exit code when SIGTERM stopped it."
  143)

(defn- exit-reason
  "How a Pagefind exit code reads in dev's terminal: a signal by name
  (128 + n), else the code."
  [exit]
  (case exit
    137 "Pagefind was killed (SIGKILL — the out-of-memory killer, or `kill -9`)"
    143 "Pagefind was terminated (SIGTERM)"
    nil))

(defn- indexer
  "dev's search indexing: `search/run-staged!` on a `background-runner`,
  over the config of the build that asked last, keeping every previous
  bundle live since `(keep-since)` — the last page reload (P4-D.2 item
  14). While dev stops (`stopping?`) no run starts and a failure prints
  nothing; otherwise only a SIGINT exit is silent: a Pagefind killed by
  anything else — the OOM killer — is an index failure (item 11). `spawn`
  is the runner's (a test's runs synchronously). The function's metadata
  carries the runner's `:await`."
  [stopping? & [spawn keep-since]]
  (let [pending  (atom nil)
        request! (background-runner
                 (fn []
                   (let [cfg @pending t0 (System/currentTimeMillis)]
                     (try
                       (search/run-staged! cfg {:cancelled? stopping?
                                                :keep-since (when keep-since (keep-since))})
                       (println (format "clogem-press: search index updated in %d ms"
                                        (- (System/currentTimeMillis) t0)))
                       (catch Throwable t
                         (let [exit (:clogem/pagefind-exit (ex-data t))]
                           (when-not (or (stopping?) (= sigint-exit exit)
                                         ;; a SIGTERM to the process group reaches
                                         ;; Pagefind before dev's shutdown hook sets
                                         ;; `stopping`: given a moment (P4-D.3 item 11)
                                         (and (= sigterm-exit exit) (soon? stopping? signal-grace-ms)))
                             (println "clogem-press: search index failed —"
                                      (if-let [why (exit-reason exit)]
                                        (str why "; the previous search index is still in place.")
                                        (tool-output (or (ex-message t) (str t)))))))))))
                 (or spawn future-call)
                 stopping?)]
    (with-meta (fn [cfg] (reset! pending cfg) (request!))
      (meta request!))))

(defn build-opts
  "The options every dev rebuild passes to `clogem.cli/build`: the CLI's
  own (--out, --base, --no-search, --no-highlight, --no-write …) plus the
  two flags only `bb dev` sets — `:clogem/dev?` (⟦key⟧ for a missing UI
  string, search left to the background indexer, §11.3 item 12) and
  `:clogem/dev-loop?` (a Chroma failure warns once and code renders plain,
  §11.3 item 10)."
  [opts]
  (assoc opts :site-dir (or (:site-dir opts) ".") :clogem/dev? true :clogem/dev-loop? true))

(defn- config-moves
  "The lines to print when a rebuild's config moved what dev serves: the
  base is mounted, and the output directory served, from the new config
  at once — this only says so."
  [before after url]
  (let [b0 (config/base-path before) b1 (config/base-path after)
        o0 (str (config/out-dir before)) o1 (str (config/out-dir after))]
    (cond-> []
      (not= b0 b1) (conj (str "clogem-press: the site base changed from " b0 " to " b1
                              "; dev now serves it at " (url b1)))
      (not= o0 o1) (conj (str "clogem-press: the output directory changed from " o0 " to " o1
                              "; dev now serves " o1)))))

(def own-write-ms
  "For this long after a build, an event for a source file the build wrote
  that still holds exactly what it wrote is the build's own write."
  3000)

(defn own-writes
  "The paths of `changed` that are a build's own source writes (`writes`:
  canonical path → {:content :at}, from `u/*source-writes*`): written less
  than `own-write-ms` ago and still holding exactly that content. Those
  never rebuild (P4-D.2 item 9): front-matter auto-fill and
  `permalinks.edn` used to trigger a second build, and with any filter
  missing, an endless loop."
  [writes changed now]
  (set (filter (fn [p]
                 (let [c (try (str (fs/canonicalize p)) (catch Exception _ (str p)))]
                   (when-let [{:keys [content at]} (get writes c)]
                     (and (< (- now at) own-write-ms)
                          ;; a directory the build made: still a directory
                          ;; (`u/create-source-dirs!`, P4-D.3 item 15)
                          (if (= u/dir-mark content)
                            (fs/directory? c)
                            (= content (try (slurp (fs/file c)) (catch Exception _ ::gone))))))))
               changed)))

(defn- canonical [p] (try (str (fs/canonicalize p)) (catch Exception _ (str p))))

(defn take-own-writes!
  "`own-writes` of `changed` against the atom `writes`, each mark used up:
  the first event for a path the build wrote is the build's own at most
  once, and every later one is the user's, whatever its content (P4-D.3
  item 4 — an undo back to the bytes the build wrote, within
  `own-write-ms`, was taken for the build's write and never rebuilt)."
  [writes changed now]
  (let [own (own-writes @writes changed now)]
    (swap! writes #(apply dissoc % (map canonical changed)))
    own))

(def stop-wait-ms
  "How long `stop!` waits for the build running, and then for the index
  run, to end (P4-D.2 item 2)."
  10000)

(defn start-dev!
  "Wire `bb dev` together and start it; returns
  {:stop! :state :cfg-ref :submit! :server :pod? :url}.

  In order: the config (a config error is fatal, D-P2-12); the server,
  bound BEFORE the first build, so a host or port it cannot have writes
  nothing to `dist/`; the first build; the queue's drain loop; then the
  fswatcher pod — with its follower, which registers a watched directory
  created later — or polling. The handler reads the base and the output
  directory from the config of the last build, so a `site.edn` edit that
  changes `:site :base` or `:build :out` is simply served.

  Everything that reaches outside is injectable through `env` — `:build`,
  `:load-cfg!`, `:run-server!`, `:try-pod-watch!`, `:poll-watch!`, `:index!`,
  `:notify!`, `:notify-error!`, `:spawn` (run a function on its own thread;
  default `future-call`) and `:follow-ms` — so the wiring is tested without
  a port, a pod or a clock (dev_loop_test)."
  [{:keys [poll interval probe-ms reload-code]
    :or {interval 500 probe-ms default-probe-ms} :as opts}
   & [env]]
  (let [build*    (or (:build env) (requiring-resolve 'clogem.cli/build))
        serve*    (or (:run-server! env) run-server!)
        pod*      (or (:try-pod-watch! env) try-pod-watch!)
        poll*     (or (:poll-watch! env) poll-watch!)
        spawn     (or (:spawn env) future-call)
        follow-ms (or (:follow-ms env) 1000)
        stopping  (atom false)
        reloaded  (atom 0)
        index*    (or (:index! env) (indexer #(deref stopping) nil #(deref reloaded)))
        ;; `load-cfg!` applies --out, --base, --no-search and --no-highlight
        ;; as `build` does
        cfg       (assoc ((or (:load-cfg! env) (requiring-resolve 'clogem.cli/load-cfg!)) opts)
                         :clogem/dev? true)
        cfg-ref   (atom cfg)
        ;; what is watched follows the config even when its build fails:
        ;; a `:content :dir` moved to a directory not created yet fails
        ;; the build, and must still be followed (P4-D.2 item 24)
        watch-cfg (atom cfg)
        theme     (theme-dir reload-code)
        paths     #(watched-paths @watch-cfg {:reload-code? reload-code})
        specs     (atom (watch-specs cfg {:reload-code? reload-code}))
        state     (atom {:ever-ok? false})
        session   (atom #{})
        sopts     (server-options opts)
        out-fn    #(fs/absolutize (config/out-dir @cfg-ref))
        ;; every output directory this session built into: a `:build :out`
        ;; changed mid-session leaves previous bundles in the old one, which
        ;; `stop!` sweeps too (P4-D.3 item 2)
        outs      (atom #{(str (out-fn))})
        base-fn   #(config/base-path @cfg-ref)
        server    (serve* (make-handler out-fn {:inject-reload? true :base base-fn :state state
                                                :check-host? (loopback-ip? (:ip sopts))
                                                   :host-names (allowed-host-names (:ip sopts))}) sopts)
        sopts     (assoc sopts :port (bound-port server (:port sopts)))
        url       #(url-for sopts %)
        writes    (atom {})
        rebuild   (make-rebuild
                   {:build         #(let [w (atom {})]
                                      (try (binding [i18n/*session-keys* session
                                                     u/*source-writes*   w
                                                     u/*cancelled?*      (fn [] @stopping)]
                                             (build* (build-opts opts)))
                                           (finally
                                             (let [now (System/currentTimeMillis)]
                                               (swap! writes into (for [[p c] @w] [p {:content c :at now}]))))))
                    :out           out-fn
                    :state         state
                    :index!        (fn [c] (when-not @stopping (index* c)))
                    :reload-code   (when reload-code theme)
                    :notify!       (or (:notify! env) notify-clients!)
                    :notify-error! (or (:notify-error! env) notify-error!)
                    :on-reload     #(reset! reloaded (System/currentTimeMillis))
                    :on-config     (fn [c]
                                     (let [before @cfg-ref]
                                       (reset! cfg-ref c)
                                       (swap! outs conj (str (out-fn)))
                                       (reset! watch-cfg c)
                                       (reset! specs (watch-specs c {:reload-code? reload-code}))
                                       (run! println (config-moves before c url))))})
        ;; after a failed build: the config alone, quietly — a config
        ;; error keeps watching what was watched
        follow-cfg! (fn []
                      (try
                        (let [c (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
                                  ((or (:load-cfg! env) (requiring-resolve 'clogem.cli/load-cfg!)) opts))]
                          (reset! watch-cfg c)
                          (reset! specs (watch-specs c {:reload-code? reload-code})))
                        (catch Throwable _ nil)))
        queue     (LinkedBlockingQueue.)
        submit!   (submitter queue)
        register  (atom nil)
        registered (atom {})
        dirq      (LinkedBlockingQueue.)
        pod-id    (atom nil)
        ;; every build holds it, the first one included, so `stop!` can
        ;; wait for the one running and none starts after it
        build-lock (java.util.concurrent.locks.ReentrantLock.)
        rebuild!  (fn [changed]
                    (.lock build-lock)
                    (try (when-not @stopping
                           (let [own     (take-own-writes! writes changed (System/currentTimeMillis))
                                 changed (vec (remove own changed))]
                             ;; a batch of nothing but the last build's own
                             ;; writes is no change at all
                             (when (or (empty? own) (seq changed))
                               (or (rebuild changed) (follow-cfg!)))))
                         (finally (.unlock build-lock))))
        stop!     (fn stop! []
                    (when (compare-and-set! stopping false true)
                      (try (when (fn? server) (server)) (catch Throwable _ nil))
                      (.put queue ::stop)
                      ;; queued index requests are cancelled by `stopping`;
                      ;; the running Pagefind is killed and waited for
                      (search/stop-indexing!)
                      (when (.tryLock build-lock stop-wait-ms TimeUnit/MILLISECONDS)
                        (.unlock build-lock))
                      (when-let [await! (:await (meta index*))] (await! stop-wait-ms))
                      ;; the pod process and its watches go with dev (P4-D.2 item 15)
                      (unload-pod! @pod-id)
                      ;; and a probe still waiting for its event (P4-D.3 item 12)
                      (remove-probes!)
                      (doseq [o @outs]
                        (try (search/sweep-staging! o {:own-previous? true}) (catch Throwable _ nil)))
                      nil))]
    (when-let [f (:on-stop! env)] (f stop!))
    (rebuild! [])
    (println (format "clogem-press: dev server at %s" (url (base-fn))))
    (some-> (listening-note sopts) println)
    (spawn #(drain-loop! {:take! (queue-take queue) :on-batch rebuild!}))
    (let [existing (filterv #(fs/exists? (:path %)) @specs)
          ;; walked before the pod registers them: what each covers
          startup-covers (into {} (for [{:keys [path recursive?]} existing :when recursive?]
                                    [path (covered-dirs path)]))
          start-polling! (fn []
                           (when-not @stopping
                             (println (format "clogem-press: watching by polling every %d ms" interval))
                             (spawn #(poll* paths interval submit! {:stop? (fn [] @stopping)}))))
          ;; the system's inotify limit, reached mid-session: polling from
          ;; here on, with the pod's watches all released (P4-D.3 item 5)
          fall-back-to-polling! (fn [why]
                                  (when-not @stopping
                                    (println (str "clogem-press: the system's inotify limit is reached (" why
                                                  "); dev polls every " interval " ms for the rest of this session."))
                                    (reset! registered {})
                                    (unload-pod! @pod-id)
                                    (spawn #(poll* paths interval submit! {:stop? (fn [] @stopping)}))))
          ;; pod events: a directory the pod must be told about goes to
          ;; the follower (pod calls never run on the pod's callback
          ;; thread), which registers it and submits it with its files
          on-pod   (fn [ps]
                     (let [type (:type (meta ps))
                           dirs (filterv #(dir-to-watch? @specs %) ps)
                           ;; a directory removed or renamed: the follower
                           ;; drops it from what is covered, and releases a
                           ;; registration of it now (P4-D.3 item 5)
                           gone (filterv #(and (not (fs/exists? %))
                                               (or (contains? @registered (norm %)) (fresh-types type)))
                                         ps)]
                       (when-let [rest (seq (remove (set dirs) ps))] (submit! rest))
                       (doseq [d (concat dirs gone)] (.put dirq [(norm d) type]))))
          pod?     (and (not poll)
                        (not @stopping)
                        (pod* @cfg-ref on-pod {:timeout-ms probe-ms
                                               :stopping? (fn [] @stopping)
                                               :specs     existing
                                               :accept?   #(watched-event? @specs %)
                                               :on-ready  (fn [reg & [{:keys [unwatch! watchers]}]]
                                                            (reset! register {:register! reg
                                                                              :unwatch!  (or unwatch! (fn [_]))
                                                                              :watchers  watchers}))}))]
      (when (map? pod?) (reset! pod-id (:pod-id pod?)))
      (if pod?
        (do (reset! registered (into {} (for [{:keys [path] :as spec} existing]
                                          [path {:key (file-key path) :spec spec
                                                 :covers (get startup-covers path)
                                                 :watcher (get-in @register [:watchers path])}])))
            (println "clogem-press: watching via fswatcher")
            (when @register
              (spawn (fn []
                       (let [{:keys [register! unwatch!]} @register]
                         (loop [last-pass (System/currentTimeMillis)]
                           (let [d     (.poll dirq (long follow-ms) TimeUnit/MILLISECONDS)
                                 now   (System/currentTimeMillis)
                                 ;; the full pass runs at least every
                                 ;; `follow-ms`, events or not (P4-D.3 item 5)
                                 pass? (or (nil? d) (>= (- now last-pass) follow-ms))]
                             (when-not @stopping
                               (let [gave-up?
                                     (try
                                       (release-gone! registered unwatch!)
                                       (let [new (into (if d
                                                         (register-new-dirs! registered
                                                                             (remove nil? (cons d (repeatedly (.size dirq) #(.poll dirq))))
                                                                             register! unwatch!)
                                                         [])
                                                       (when pass? (follow-new-paths! @specs registered register! unwatch!)))]
                                         ;; a recursive registration's files are rescanned;
                                         ;; a single-file watch's directory — a site.edn
                                         ;; link's new target — is not (P4-D.3 item 16)
                                         (when-let [new (seq (filter #(get-in @registered [% :spec :recursive?]) (distinct new)))]
                                           (submit! (vec (mapcat new-dir-batch new)))))
                                       false
                                       (catch Throwable t
                                         (if (::exhausted (ex-data t))
                                           (do (fall-back-to-polling! (or (ex-message t) (str t))) true)
                                           (do (println "clogem-press: watching failed —" (or (ex-message t) (str t)))
                                               false))))]
                                 (when-not gave-up? (recur (if pass? now last-pass))))))))))))
        (start-polling!))
      {:stop!   stop!
       :state   state
       :cfg-ref cfg-ref
       :submit! submit!
       :server  server
       :pod?    (boolean pod?)
       :url     (url (base-fn))})))

(defn add-shutdown-hook!
  "Run `f` when the JVM shuts down — Ctrl-C. A var, for the tests."
  [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable f)))

(defn dev!
  [opts]
  ;; Ctrl-C: stop the server, the loop and any Pagefind run, wait for the
  ;; build running, remove the staging and previous bundles, and say so
  ;; once. Installed first (P4-D.2 item 12): Ctrl-C during the first build
  ;; or the pod's probe used to skip all of it. `stopped` is said only when
  ;; dev got as far as handing out its `stop!` — after the server is bound,
  ;; before the first build — never after a failed start (a listen error, a
  ;; config error), which said `stopped` under its error (P4-D.3 item 10).
  (let [stop (atom nil)]
    (add-shutdown-hook! (fn []
                          (when-let [f @stop]
                            (f)
                            (println "clogem-press: stopped"))))
    (start-dev! opts {:on-stop! #(reset! stop %)})
    @(promise)))
