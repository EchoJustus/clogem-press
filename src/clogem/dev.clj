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

(defn resolve-path
  "Map an already-decoded request path to a file under `root`, refusing
  anything that escapes it (`under?`): a decoded `..`, `/` or NUL included."
  [root decoded]
  (try
    (let [rel    (str/replace (str decoded) #"^/+" "")
          base   (str (fs/canonicalize root))
          target (fs/path base rel)]
      (when (and (not (str/includes? rel "\u0000"))
                 (fs/exists? target)
                 (under? base (str (fs/canonicalize target))))
        (cond
          (fs/directory? target)    (let [idx (fs/path target "index.html")]
                                      (when (fs/regular-file? idx) idx))
          (fs/regular-file? target) target
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

(defn- escape-html [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

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
  file in the previous generation (`search/previous-subdir`): a page that
  loaded the old `pagefind-entry.json` before dev's background index swapped
  in a new one still fetches the old bundle's content-hashed files, until
  its next reload (§5.4)."
  [root rel]
  (when (str/starts-with? rel "/pagefind/")
    (resolve-path root (str "/" search/previous-subdir (subs rel (count "/pagefind"))))))

(defn- file-response
  "Serve `f`, opened here, once: a file renamed or deleted since it was
  resolved is nil (the caller tries elsewhere), never a 500 naming its path."
  [f inject-reload?]
  (try
    (let [html? (= "html" (u-ext f))
          body  (if (and html? inject-reload?)
                  (str/replace (slurp (fs/file f)) "</body>" (str reload-script "</body>"))
                  (java.io.FileInputStream. (fs/file f)))]
      {:status 200
       :headers (cond-> {"Content-Type" (content-type f)}
                  inject-reload? (assoc "Cache-Control" "no-cache"))
       :body body})
    (catch java.io.IOException _ nil)))

(defn- value [x] (if (fn? x) (x) x))

(defn make-handler
  "The static handler over `root`, mounted at `:base` (default `/`): `/`
  redirects to the base, and nothing outside it is served. `root` and
  `:base` may be functions, read on every request — dev's follow `site.edn`.
  With `:inject-reload?` (dev), `/__reload` is the SSE channel, HTML gets
  the reload script, and — when `:state` says no build has succeeded yet —
  a page request gets `error-page`. A `pagefind/` file the live bundle no
  longer has is served from the previous one (`pagefind-fallback`)."
  [root {:keys [inject-reload? base state] :or {base "/"}}]
  (fn [{:keys [uri] :as req}]
    (try
      (let [uri  (str uri)
            base (value base)
            root (value root)
            path (decode-path uri)]
        (cond
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
                  (or (some-> (resolve-path root rel) (file-response inject-reload?))
                      (some-> (pagefind-fallback root rel) (file-response inject-reload?))))
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
  (let [why (cond
              (= "java.nio.channels.UnresolvedAddressException" (.getName (class e)))
              (str "the host " (pr-str ip) " does not resolve")
              (re-find #"(?i)in use" (str (ex-message e)))
              (str "port " port " is already in use")
              (re-find #"(?i)not available|assign" (str (ex-message e)))
              (str ip " is not an address of this machine")
              :else (or (ex-message e) (.getName (class e))))]
    (ex-info (str "clogem-press: cannot listen on " (if (str/includes? (str ip) ":") (str "[" ip "]") ip) ":" port
                  " — " why ".\n  hint: Choose another port with `--port`, or listen on loopback with `--host 127.0.0.1`.")
             {:babashka/exit 1})))

(defn run-server!
  "`http/run-server`, as a var the tests can redefine. A host that does not
  resolve, is not this machine's, or a port in use is an `ex-info` (exit 1)
  naming the host and port, not a stack trace."
  [handler opts]
  (try
    (http/run-server handler opts)
    ;; UnresolvedAddressException is not an IOException (nor a class
    ;; babashka exposes): matched by name
    (catch Exception e
      (if (or (instance? java.io.IOException e)
              (= "java.nio.channels.UnresolvedAddressException" (.getName (class e))))
        (throw (listen-error opts e))
        (throw e)))))

(defn url-for
  "The URL to print for a server listening with `sopts`, at `base`. On the
  default loopback address it is `http://localhost:PORT<base>`, as 0.2.0
  printed: an origin that is `localhost` is what tools allow-list — giscus's
  `originsRegex` of `http://localhost:[0-9]+` refuses `http://127.0.0.1:…`."
  [{:keys [ip port]} base]
  (str "http://"
       (cond (= ip default-host)        "localhost"
             (str/includes? ip ":")     (str "[" ip "]")
             :else                      ip)
       ":" port
       (.toASCIIString (java.net.URI. nil nil (str base) nil))))

(defn- clean-base [b] (config/base-path {:site {:base b}}))

(defn serve-base
  "`serve`'s base: `--base`, else the site's `:base` when `--site-dir` holds
  its config file, else `/`. A config file that cannot be read is one
  warning and `/`: `serve` only needs the base, and 0.2.0 served regardless."
  [{:keys [base site-dir config-file]}]
  (if base
    (clean-base base)
    (let [f (fs/path (or site-dir ".") (or config-file "site.edn"))]
      (if (fs/regular-file? f)
        (try
          (config/base-path (first (diag/collecting (config/load-config (str (or site-dir ".")) config-file nil))))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "warning: " f ": could not read it (" (first (str/split-lines (str (ex-message e))))
                            "), so the site is served at /."))
              (println "  hint: Fix it, or give the base with `--base`."))
            "/"))
        "/"))))

(defn start-serve!
  "Start `serve`'s server and return what `run-server!` returned."
  [{:keys [dir] :or {dir "dist"} :as opts}]
  (let [root (fs/absolutize dir)
        base (serve-base opts)
        sopts (server-options opts)]
    (when-not (fs/directory? root)
      (throw (ex-info (str "nothing to serve: " root " does not exist (run `bb build` first)")
                      {:babashka/exit 1})))
    (let [srv (run-server! (make-handler root {:inject-reload? false :base base}) sopts)]
      (println (format "clogem-press: serving %s at %s" root (url-for sopts base)))
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
  is reported and does not stop later runs."
  ([f] (background-runner f future-call))
  ([f spawn]
   (let [st (atom {:running? false :again? false})]
     (fn request! []
       (let [[old _] (swap-vals! st (fn [s] (if (:running? s)
                                             (assoc s :again? true)
                                             (assoc s :running? true :again? false))))]
         (when-not (:running? old)
           (spawn (fn []
                    (loop []
                      (try (f)
                           (catch Throwable t
                             (println "clogem-press: search index failed —" (or (ex-message t) (str t)))))
                      (let [[o _] (swap-vals! st (fn [s] (if (:again? s)
                                                           (assoc s :again? false)
                                                           (assoc s :running? false))))]
                        (when (:again? o) (recur))))))))
         nil))))

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

(defn watch-specs
  "Every path `bb dev` watches with the fswatcher pod, whether it exists yet
  or not, as {:path :recursive? :only}: the content, assets, i18n and
  overrides directories and the theme's (recursively), and the config
  file's DIRECTORY, not recursively, `:only` for that file. A watch on the
  file itself stays on its inode, so a save that replaces it by rename —
  `sed -i`, vim, emacs, any atomic-save editor — left every later edit
  unseen; a watch on its directory sees the rename. Only the config file's
  own events count (`watched-event?`): `permalinks.edn` and `dist/` beside
  it are not changes."
  [cfg & [{:keys [reload-code?]}]]
  (let [cf (some-> (:clogem/config-file cfg) norm)]
    (vec (concat
          (for [p [(config/content-dir cfg) (config/assets-dir cfg) (config/strings-dir cfg)
                   (fs/path (config/site-dir cfg) "overrides")]]
            {:path (norm p) :recursive? true})
          (when cf [{:path (norm (fs/parent cf)) :recursive? false :only cf}])
          (when-let [t (theme-dir reload-code?)] [{:path (norm t) :recursive? true}])))))

(defn watched-event?
  "Is `p`, a path the pod reported, a change `specs` watch for: inside a
  recursive spec's directory, or a spec's `:only` file?"
  [specs p]
  (let [p (norm p)]
    (boolean
     (some (fn [{:keys [path only]}]
             (if only
               (= p only)
               (or (= p path) (str/starts-with? p (str path File/separator)))))
           specs))))

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
  follower uses for a directory created later (§5.4)."
  [cfg on-change {:keys [watch unwatch timeout-ms gap-ms sleep specs accept? on-ready]
                  :or   {timeout-ms default-probe-ms
                         gap-ms     registration-gap-ms
                         sleep      #(Thread/sleep (long %))
                         accept?    (constantly true)}}]
  (let [specs (or specs (filterv #(fs/exists? (:path %)) (watch-specs cfg)))
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
                            :else          (try (on-change [p])
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
                (println (format (str "clogem-press: the fswatcher pod did not finish registering "
                                      "in %d ms; falling back to polling.")
                                 timeout-ms))
                false)

            error
            (do (reset! abandoned? true)
                (println "clogem-press: fswatcher could not watch —" error)
                false)

            :else
            (try
              (let [t0 (System/currentTimeMillis)]
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
                           (sleep gap-ms)
                           (let [r (deref (future (try (watch-1 spec) (catch Throwable e e)))
                                          timeout-ms ::timeout)]
                             (cond
                               (= ::timeout r)        (throw (ex-info "the fswatcher pod did not answer" {}))
                               (instance? Throwable r) (throw r)
                               :else                  r)))))
                      true)
                  (do (reset! abandoned? true)
                      (println
                       (format (str "clogem-press: fswatcher registered but delivered no event "
                                    "within the %d ms probe budget — this environment does not "
                                    "deliver filesystem events (DESIGN.md Appendix A item 5). "
                                    "Falling back to polling.")
                               timeout-ms))
                      (doseq [w watchers] (try (unwatch w) (catch Throwable _ nil)))
                      false)))
              (finally
                (try (fs/delete-if-exists probe) (catch Throwable _ nil))))))))))

(defn follow-new-paths!
  "One pass of dev's follower, with the pod (§5.4): register every spec of
  `specs` that exists now and is not in `registered` (an atom of paths) —
  `overrides/` created while `bb dev` runs, or the directory a `site.edn`
  edit moved `:content :dir` to — and return the paths it registered, for
  a rebuild. A spec the pod will not take is reported once, with the
  restart that fixes it, and not tried again."
  [specs registered register!]
  ;; a watched directory deleted and created again is a new inode to watch
  (swap! registered (fn [r] (into #{} (filter fs/exists?) r)))
  (vec (keep (fn [{:keys [path] :as spec}]
               (when (and (not (contains? @registered path)) (fs/exists? path))
                 (swap! registered conj path)
                 (try (register! spec)
                      path
                      (catch Throwable e
                        (println (str "clogem-press: cannot watch " path " ("
                                      (or (ex-message e) (str e))
                                      "); restart `bb dev` to watch it, or run it with `--poll`."))
                        nil))))
             specs)))

(defn one-line
  "An error message as one line for dev's terminal: without the
  `clogem-press: <area>: ` prefix the line already carries, and with a
  `hint:` line kept: after a semicolon, or a space after a full stop."
  [msg]
  (->> (str/split-lines (str msg))
       (map str/trim)
       (remove str/blank?)
       (map #(str/replace % #"^clogem-press: (?:[a-z]+: )?" ""))
       (map #(str/replace % #"^hint: " ""))
       (reduce (fn [acc line]
                 (cond (empty? acc)                   line
                       (re-find #"[.!?]$" acc)        (str acc " " line)
                       :else                          (str acc "; " line)))
               "")))

(defn load-pod!
  "Fetch the fswatcher pod through `clogem.tools` — the pinned, per-platform
  sha256, cached under XDG, or `CLOGEM_FSWATCHER` / `:tools :fswatcher
  :path` — and load it from that path. Never through the pod registry, which
  runs whatever it downloads unverified. Returns {:watch :unwatch}, or nil
  after one line saying why (offline on the first run, no asset for this
  platform, a hash that does not match): the caller polls."
  [cfg]
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
      (pods/load-pod (str bin))
      {:watch   (requiring-resolve 'pod.babashka.fswatcher/watch)
       :unwatch (requiring-resolve 'pod.babashka.fswatcher/unwatch)})
    (catch Throwable e
      (println (str "clogem-press: the fswatcher pod is unavailable, so dev watches by polling: "
                    (one-line (or (ex-message e) (str e)))))
      nil)))

(defn try-pod-watch!
  "Load the fswatcher pod and probe it. Returns true only if it registered *and*
  delivered an event within the probe window."
  [cfg on-change & [opts]]
  (if-let [fns (load-pod! cfg)]
    (try
      (probe-watch! cfg on-change (merge fns (into {} (remove (comp nil? val)) opts)))
      (catch Throwable e
        (println "clogem-press: fswatcher pod unavailable —" (ex-message e))
        false))
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

(defn reload-kind
  "`css` when every changed file is a stylesheet, the set of stylesheets is
  what it was, and the last build succeeded; else `reload`."
  [changed sheets-before sheets-after last-ok?]
  (if (and (seq changed)
           last-ok?
           (= sheets-before sheets-after)
           (every? #(str/ends-with? (str/lower-case (str %)) ".css") changed))
    "css"
    "reload"))

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
  - `on-config` — called with the config each successful build used.

  On success: one line, then `reload` or `css` to every client, then the
  index request — so the page is back before Pagefind starts. On failure,
  `Throwable` included: one line, and `build-error` to every client."
  [{:keys [build out state index! reload-code on-config notify! notify-error!]
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
          (notify! kind)
          (when-let [cfg (:clogem/cfg result)]
            (when on-config (on-config cfg)))
          (when (and index! (= :deferred (:search result)))
            (index! (:clogem/cfg result)))
          result)
        ;; Throwable: a StackOverflowError or an AssertionError in a render
        ;; must not kill the loop
        (catch Throwable e
          (let [err (error-info e)]
            (swap! state assoc :last-ok? false :error err)
            (println "clogem-press: build failed —" (:message err))
            (notify-error! err)
            nil))))))

(def ^:private signal-exits
  "Pagefind's exit codes when a signal stopped it — Ctrl-C reaches the whole
  process group — or `search/stop-indexing!` did: not a failure to report."
  #{130 137 143})

(defn- indexer
  "dev's search indexing: `search/run-staged!` on a `background-runner`,
  over the config of the build that asked last. A run stopped by a signal,
  or while dev shuts down (`stopping?`), prints nothing. `spawn` is the
  runner's (a test's runs synchronously)."
  [stopping? & [spawn]]
  (let [pending  (atom nil)
        request! (background-runner
                 (fn []
                   (let [cfg @pending t0 (System/currentTimeMillis)]
                     (try
                       (search/run-staged! cfg)
                       (println (format "clogem-press: search index updated in %d ms"
                                        (- (System/currentTimeMillis) t0)))
                       (catch Throwable t
                         (when-not (or (stopping?)
                                       (signal-exits (:clogem/pagefind-exit (ex-data t))))
                           (println "clogem-press: search index failed —"
                                    (one-line (or (ex-message t) (str t)))))))))
                 (or spawn future-call))]
    (fn [cfg] (reset! pending cfg) (request!))))

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
        index*    (or (:index! env) (indexer #(deref stopping)))
        ;; `load-cfg!` applies --out, --base, --no-search and --no-highlight
        ;; as `build` does
        cfg       (assoc ((or (:load-cfg! env) (requiring-resolve 'clogem.cli/load-cfg!)) opts)
                         :clogem/dev? true)
        cfg-ref   (atom cfg)
        theme     (theme-dir reload-code)
        paths     #(watched-paths @cfg-ref {:reload-code? reload-code})
        specs     (atom (watch-specs cfg {:reload-code? reload-code}))
        state     (atom {:ever-ok? false})
        session   (atom #{})
        sopts     (server-options opts)
        out-fn    #(fs/absolutize (config/out-dir @cfg-ref))
        base-fn   #(config/base-path @cfg-ref)
        url       #(url-for sopts %)
        server    (serve* (make-handler out-fn {:inject-reload? true :base base-fn :state state}) sopts)
        rebuild   (make-rebuild
                   {:build         #(binding [i18n/*session-keys* session]
                                      (build* (build-opts opts)))
                    :out           out-fn
                    :state         state
                    :index!        (fn [c] (when-not @stopping (index* c)))
                    :reload-code   (when reload-code theme)
                    :notify!       (or (:notify! env) notify-clients!)
                    :notify-error! (or (:notify-error! env) notify-error!)
                    :on-config     (fn [c]
                                     (let [before @cfg-ref]
                                       (reset! cfg-ref c)
                                       (reset! specs (watch-specs c {:reload-code? reload-code}))
                                       (run! println (config-moves before c url))))})
        queue     (LinkedBlockingQueue.)
        submit!   (submitter queue)
        register  (atom nil)
        registered (atom #{})
        stop!     (fn stop! []
                    (when (compare-and-set! stopping false true)
                      (try (when (fn? server) (server)) (catch Throwable _ nil))
                      (.put queue ::stop)
                      (search/stop-indexing!)
                      (try (search/sweep-staging! (out-fn)) (catch Throwable _ nil))
                      nil))]
    (rebuild [])
    (println (format "clogem-press: dev server at %s" (url (base-fn))))
    (spawn #(drain-loop! {:take! (queue-take queue) :on-batch rebuild}))
    (let [existing (filterv #(fs/exists? (:path %)) @specs)
          pod?     (and (not poll)
                        (pod* @cfg-ref submit! {:timeout-ms probe-ms
                                                :specs     existing
                                                :accept?   #(watched-event? @specs %)
                                                :on-ready  #(reset! register %)}))]
      (if pod?
        (do (reset! registered (set (map :path existing)))
            (println "clogem-press: watching via fswatcher")
            (when @register
              (spawn (fn []
                       (loop []
                         (Thread/sleep (long follow-ms))
                         (when-not @stopping
                           (try (when-let [new (seq (follow-new-paths! @specs registered @register))]
                                  (submit! new))
                                (catch Throwable t
                                  (println "clogem-press: watching failed —" (or (ex-message t) (str t)))))
                           (recur)))))))
        (do (println (format "clogem-press: watching by polling every %d ms" interval))
            (spawn #(poll* paths interval submit! {:stop? (fn [] @stopping)}))))
      {:stop!   stop!
       :state   state
       :cfg-ref cfg-ref
       :submit! submit!
       :server  server
       :pod?    (boolean pod?)
       :url     (url (base-fn))})))

(defn dev!
  [opts]
  (let [{:keys [stop!]} (start-dev! opts)]
    ;; Ctrl-C: stop the server, the loop and any Pagefind run, remove its
    ;; staging directory, and say so once
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (stop!) (println "clogem-press: stopped"))))
    @(promise)))
