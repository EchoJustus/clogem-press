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

(defn resolve-file
  "Map a request URI to a file under `root`, refusing anything that escapes it."
  [root uri]
  (let [decoded (URLDecoder/decode (str/replace (str uri) #"\?.*$" "") "UTF-8")
        rel     (str/replace decoded #"^/" "")
        base    (str (fs/canonicalize root))
        target  (fs/path base rel)]
    (when (and (fs/exists? target)
               (under? base (str (fs/canonicalize target))))
      (cond
        (fs/directory? target)    (let [idx (fs/path target "index.html")]
                                    (when (fs/regular-file? idx) idx))
        (fs/regular-file? target) target
        :else nil))))

(defn strip-base
  "`uri` with the site base `base` taken off its front, as `/…`, or nil when
  `uri` is not under `base`. `dist/` is the deploy root and a project site is
  served at `https://host/<base>/` (`render/uri->file`), so the server mounts
  `dist/` at the base for its links to resolve (§11.3 item 12)."
  [base uri]
  (let [uri (str uri)]
    (cond
      (= "/" base)                 uri
      (str/starts-with? uri base)  (subs uri (dec (count base)))
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

(defn make-handler
  "The static handler over `root`, mounted at `:base` (default `/`): `/`
  redirects to the base, and nothing outside it is served. With
  `:inject-reload?` (dev), `/__reload` is the SSE channel, HTML gets the
  reload script, and — when `:state` says no build has succeeded yet — a
  page request gets `error-page`."
  [root {:keys [inject-reload? base state] :or {base "/"}}]
  (fn [{:keys [uri] :as req}]
    (let [uri (str uri)]
      (cond
        (and inject-reload? (= uri "/__reload"))
        (sse-handler req state)

        (and (not= "/" base) (or (= uri "/") (= (str uri "/") base)))
        {:status 302 :headers {"Location" base} :body ""}

        (and inject-reload? state (not (:ever-ok? @state)) (:error @state) (html-request? uri))
        {:status 500
         :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
         :body (error-page (:error @state))}

        :else
        (if-let [f (some->> (strip-base base uri) (resolve-file root))]
          (let [html? (= "html" (u-ext f))
                body  (if (and html? inject-reload?)
                        (str/replace (slurp (fs/file f)) "</body>" (str reload-script "</body>"))
                        (fs/file f))]
            {:status 200
             :headers (cond-> {"Content-Type" (content-type f)}
                        inject-reload? (assoc "Cache-Control" "no-cache"))
             :body body})
          {:status 404
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (str "<!doctype html><meta charset=utf-8><h1>404</h1><p>Not found."
                      (when (and (not= "/" base) (nil? (strip-base base uri)))
                        (str " This site is served at <a href=\"" base "\">" base "</a>."))
                      "</p>")})))))

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

(defn run-server!
  "`http/run-server`, as a var the tests can redefine."
  [handler opts]
  (http/run-server handler opts))

(defn- url-for [{:keys [ip port]} base]
  (str "http://" (if (str/includes? ip ":") (str "[" ip "]") ip) ":" port base))

(defn- clean-base [b] (config/base-path {:site {:base b}}))

(defn serve-base
  "`serve`'s base: `--base`, else the site's `:base` when `--site-dir` holds
  its config file, else `/`."
  [{:keys [base site-dir config-file]}]
  (if base
    (clean-base base)
    (let [f (fs/path (or site-dir ".") (or config-file "site.edn"))]
      (if (fs/regular-file? f)
        (config/base-path (first (diag/collecting (config/load-config (str (or site-dir ".")) config-file nil))))
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
  [paths-fn interval on-change]
  (loop [prev (snapshot (paths-fn))]
    (Thread/sleep (long interval))
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
      (recur now))))

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
  definition cannot be reproduced where events do fire, is covered anyway."
  [cfg on-change {:keys [watch unwatch timeout-ms gap-ms sleep paths]
                  :or   {timeout-ms default-probe-ms
                         gap-ms     registration-gap-ms
                         sleep      #(Thread/sleep (long %))}}]
  (let [paths (or paths (watched-paths cfg))
        dirs  (filterv fs/directory? paths)]
    (if (empty? dirs)
      (do (println "clogem-press: no watchable directory to probe; using polling")
          false)
      (let [probe     (fs/path (first dirs) (str ".clogem-watch-probe-" (System/nanoTime)))
            probe-s   (str probe)
            seen      (promise)
            abandoned? (atom false)
            callback  (fn [ev]
                        (let [p (str (:path ev))]
                          (cond
                            (= p probe-s)  (deliver seen true)
                            @abandoned?    nil
                            :else          (try (on-change [p])
                                                (catch Throwable t
                                                  (println "clogem-press: watcher callback failed —"
                                                           (or (ex-message t) (str t))))))))
            reg       (promise)
            ;; One deadline shared by registration and delivery. Giving each its
            ;; own `timeout-ms` made the advertised window the *half* of a worst
            ;; case that was silently twice as long.
            deadline  (+ (System/currentTimeMillis) timeout-ms)
            remaining #(max 0 (- deadline (System/currentTimeMillis)))]
        (future
          (deliver reg (try {:watchers (vec (map-indexed
                                             (fn [i p]
                                               (when (pos? i) (sleep gap-ms))
                                               (watch (str p) callback
                                                      {:recursive true
                                                       :delay-ms watch-delay-ms}))
                                             paths))}
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
      (println (str "clogem-press: the fswatcher pod is unavailable, so dev watches by polling — "
                    (first (str/split-lines (str (or (ex-message e) e))))))
      nil)))

(defn try-pod-watch!
  "Load the fswatcher pod and probe it. Returns true only if it registered *and*
  delivered an event within the probe window."
  [cfg on-change & [{:keys [timeout-ms paths]}]]
  (if-let [fns (load-pod! cfg)]
    (try
      (probe-watch! cfg on-change (cond-> fns
                                    timeout-ms (assoc :timeout-ms timeout-ms)
                                    paths      (assoc :paths paths)))
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
  - `out` — the output directory, for the stylesheet comparison;
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
          before (linked-stylesheets out)]
      (try
        (when reload-code
          (doseq [ns-sym (theme-namespaces reload-code changed)]
            (require ns-sym :reload)
            (println "clogem-press: reloaded" ns-sym)))
        (let [result  (build)
              after   (linked-stylesheets out)
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

(defn- indexer
  "dev's search indexing: `search/run-staged!` on a `background-runner`,
  over the config of the build that asked last."
  []
  (let [pending  (atom nil)
        request! (background-runner
                 (fn []
                   (let [cfg @pending t0 (System/currentTimeMillis)]
                     (search/run-staged! cfg)
                     (println (format "clogem-press: search index updated in %d ms"
                                      (- (System/currentTimeMillis) t0))))))]
    (fn [cfg] (reset! pending cfg) (request!))))

(defn dev!
  [{:keys [poll interval probe-ms reload-code]
    :or {interval 500 probe-ms default-probe-ms} :as opts}]
  (let [;; D-P2-12: a config error is fatal here too — the rebuild loop would
        ;; otherwise serve a site rendered under a repaired config for ever.
        ;; `load-cfg!` applies --out, --base and --no-search as `build` does.
        cfg     ((requiring-resolve 'clogem.cli/load-cfg!) opts)
        cfg     (assoc cfg :clogem/dev? true)
        cfg-ref (atom cfg)
        out     (config/out-dir cfg)
        base    (config/base-path cfg)
        theme   (theme-dir reload-code)
        paths   #(watched-paths @cfg-ref {:reload-code? reload-code})
        watched (atom (paths))
        pod?    (atom false)
        state   (atom {:ever-ok? false})
        build   (requiring-resolve 'clogem.cli/build)
        rebuild (make-rebuild
                 {:build       #(build (assoc opts :site-dir (or (:site-dir opts) ".") :clogem/dev? true))
                  :out         out
                  :state       state
                  :index!      (indexer)
                  :reload-code (when reload-code theme)
                  :on-config   (fn [c]
                                 (reset! cfg-ref c)
                                 (let [now (paths)]
                                   (when (and @pod? (not= now @watched))
                                     (println (str "clogem-press: the watched directories changed ("
                                                   (str/join ", " (map str now))
                                                   "); restart `bb dev` to watch them — the fswatcher pod "
                                                   "keeps the directories it registered at startup.")))
                                   (reset! watched now)))})
        queue   (LinkedBlockingQueue.)
        submit! (submitter queue)
        sopts   (server-options opts)]
    (rebuild [])
    (run-server! (make-handler (fs/absolutize out) {:inject-reload? true :base base :state state}) sopts)
    (println (format "clogem-press: dev server at %s" (url-for sopts base)))
    (future (drain-loop! {:take! (queue-take queue) :on-batch rebuild}))
    (if (or poll (not (try-pod-watch! cfg submit! {:timeout-ms probe-ms :paths @watched})))
      (do (println (format "clogem-press: watching by polling every %d ms" interval))
          (poll-watch! paths interval submit!))
      (do (reset! pod? true)
          (println "clogem-press: watching via fswatcher")
          @(promise)))))
