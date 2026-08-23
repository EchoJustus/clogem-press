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
  then comes up, announces that it is watching, and silently never rebuilds."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [clojure.string :as str]
            [clogem.config :as config]
            [org.httpkit.server :as http])
  (:import [java.io File]
           [java.net URLDecoder]))

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

;; ---------------------------------------------------------------------------
;; SSE live reload

(def ^:private clients (atom #{}))

(def reload-script
  "<script>(function(){var s=new EventSource('/__reload');
s.onmessage=function(e){if(e.data==='css'){document.querySelectorAll('link[rel=stylesheet]').forEach(function(l){
var u=new URL(l.href);u.searchParams.set('t',Date.now());l.href=u.toString();});}else{location.reload();}};})();</script>")

(defn- sse-handler
  [req]
  (http/as-channel
   req
   {:on-open (fn [ch]
               (swap! clients conj ch)
               (http/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}
                               :body "retry: 500\n\n"}
                           false))
    :on-close (fn [ch _] (swap! clients disj ch))}))

(defn notify-clients!
  [payload]
  (doseq [ch @clients]
    (try (http/send! ch (str "data: " payload "\n\n") false)
         (catch Exception _ (swap! clients disj ch)))))

;; ---------------------------------------------------------------------------
;; Handler

(defn make-handler
  [root {:keys [inject-reload?]}]
  (fn [{:keys [uri] :as req}]
    (if (= uri "/__reload")
      (sse-handler req)
      (if-let [f (resolve-file root uri)]
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
         :body "<!doctype html><meta charset=utf-8><h1>404</h1><p>Not found.</p>"}))))

;; ---------------------------------------------------------------------------
;; Tasks

(defn serve!
  [{:keys [dir port] :or {dir "dist" port 1888}}]
  (let [root (fs/absolutize dir)]
    (when-not (fs/directory? root)
      (throw (ex-info (str "nothing to serve: " root " does not exist (run `bb build` first)")
                      {:babashka/exit 1})))
    (http/run-server (make-handler root {:inject-reload? false}) {:port port})
    (println (format "clogem-press: serving %s at http://localhost:%d/" root port))
    @(promise)))

;; --- watching ---------------------------------------------------------------

(defn- watched-paths
  [cfg]
  (->> [(config/content-dir cfg) (config/assets-dir cfg) (config/strings-dir cfg)
        (fs/path (config/site-dir cfg) "overrides")
        (fs/path (:clogem/config-file cfg))]
       (filter fs/exists?)
       vec))

(defn- snapshot
  [paths]
  (into {}
        (for [p paths
              f (if (fs/directory? p) (fs/glob p "**") [p])
              :when (fs/regular-file? f)]
          [(str f) (fs/file-time->millis (fs/last-modified-time f))])))

(defn poll-watch!
  "The `--poll` fallback of §5.4: a `babashka.fs`-based modified-since loop.

  Required in practice — a container where fsnotify registers but never fires is
  reproducible, and is the reason this is designed in rather than bolted on."
  [cfg interval on-change]
  (let [paths (watched-paths cfg)]
    (loop [prev (snapshot paths)]
      (Thread/sleep interval)
      (let [now (snapshot paths)]
        (when (not= prev now)
          (let [changed (->> (concat (keys now) (keys prev))
                             distinct
                             (remove #(= (get prev %) (get now %)))
                             sort vec)]
            (on-change changed)))
        (recur now)))))

(def default-probe-ms
  "How long to wait for the watcher to prove itself.

  Real inotify delivers in single-digit milliseconds, so this looks generous —
  but it is sized against a measurement, not against inotify. In the container
  this was developed in, the pod registers and events *do* arrive, consistently
  ~2000 ms later: notify has fallen back to its own `PollWatcher`, whose default
  interval is two seconds. A window at 2000 ms would sit exactly on that and
  answer a coin flip. Three seconds clears it, so the answer is stable, and the
  cost is paid only in an environment that is already degraded — where three
  seconds buys a correct decision. `--probe-ms` tunes it."
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
  behind the poll loop's back.

  `watch`/`unwatch` are injected so both outcomes are testable without the pod
  — and so the zero-event case, which by definition cannot be reproduced where
  events do fire, is covered anyway."
  [cfg on-change {:keys [watch unwatch timeout-ms]
                  :or   {timeout-ms default-probe-ms}}]
  (let [paths (watched-paths cfg)
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
                            :else          (on-change [p]))))
            reg       (promise)]
        (future
          (deliver reg (try {:watchers (mapv #(watch (str %) callback {:recursive true}) paths)}
                            (catch Throwable e {:error (or (ex-message e) (str e))}))))
        (let [{:keys [watchers error] :as r} (deref reg timeout-ms ::timeout)]
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
                (if (deref seen timeout-ms false)
                  (do (println (format "clogem-press: fswatcher delivered a probe event in %d ms"
                                       (- (System/currentTimeMillis) t0)))
                      true)
                  (do (reset! abandoned? true)
                      (println
                       (format (str "clogem-press: fswatcher registered but delivered no event in "
                                    "%d ms — this environment does not deliver filesystem events "
                                    "(DESIGN.md Appendix A item 5). Falling back to polling.")
                               timeout-ms))
                      (doseq [w watchers] (try (unwatch w) (catch Throwable _ nil)))
                      false)))
              (finally
                (try (fs/delete-if-exists probe) (catch Throwable _ nil))))))))))

(defn try-pod-watch!
  "Load the fswatcher pod and probe it. Returns true only if it registered *and*
  delivered an event within the probe window."
  [cfg on-change & [{:keys [timeout-ms]}]]
  (try
    ;; `babashka.pods` is required in the ns form on purpose. Resolving it
    ;; lazily here — `(requiring-resolve 'babashka.pods/load-pod)` — deadlocks:
    ;; by the time `dev!` reaches this point the http-kit server threads are
    ;; running and `clogem.cli` has itself been pulled in by `requiring-resolve`,
    ;; and the load never returns. Silently: no watcher, no fallback, no message.
    ;; Reproduced deterministically and fixed by loading it up front, which costs
    ;; nothing — it is a babashka built-in (Appendix A item 1).
    ;;
    ;; `pod.babashka.fswatcher` still has to be resolved late: it does not exist
    ;; until load-pod has run.
    (pods/load-pod 'org.babashka/fswatcher "0.0.7")
    (probe-watch! cfg on-change
                  (cond-> {:watch   (requiring-resolve 'pod.babashka.fswatcher/watch)
                           :unwatch (requiring-resolve 'pod.babashka.fswatcher/unwatch)}
                    timeout-ms (assoc :timeout-ms timeout-ms)))
    (catch Throwable e
      (println "clogem-press: fswatcher pod unavailable —" (ex-message e))
      false)))

(defn dev!
  [{:keys [port poll interval probe-ms]
    :or {port 1888 interval 500 probe-ms default-probe-ms} :as opts}]
  (let [cfg   (assoc (config/load-config (:site-dir opts) (:config-file opts)
                                         (cond-> {} (:out opts) (assoc-in [:build :out] (:out opts))))
                     :clogem/dev? true)
        out   (config/out-dir cfg)
        rebuild (fn [changed]
                  (let [t0 (System/currentTimeMillis)]
                    (try
                      ((requiring-resolve 'clogem.cli/build)
                       (assoc opts :site-dir (or (:site-dir opts) ".")))
                      (println (format "clogem-press: rebuilt in %d ms (%s)"
                                       (- (System/currentTimeMillis) t0)
                                       (if (seq changed)
                                         (str (count changed) " file(s) changed")
                                         "initial")))
                      (notify-clients! (if (every? #(str/ends-with? (str %) ".css") changed)
                                         "css" "reload"))
                      (catch Exception e
                        (println "clogem-press: build failed —" (ex-message e))))))]
    (rebuild [])
    (http/run-server (make-handler (fs/absolutize out) {:inject-reload? true}) {:port port})
    (println (format "clogem-press: dev server at http://localhost:%d/" port))
    (if (or poll (not (try-pod-watch! cfg rebuild {:timeout-ms probe-ms})))
      (do (println (format "clogem-press: watching by polling every %d ms" interval))
          (poll-watch! cfg interval rebuild))
      (do (println "clogem-press: watching via fswatcher")
          @(promise)))))
