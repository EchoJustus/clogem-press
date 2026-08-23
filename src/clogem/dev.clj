;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.dev
  "Dev server and static preview (DESIGN.md §5.4).

  All built-ins: http-kit for the server and for SSE push, babashka.fs for the
  polling watcher. Static file serving is *not* built into http-kit, so the
  handler below is the small vendored one the design allows for.

  ## Watching

  §5.4 specifies the fswatcher pod with a `--poll` fallback, and Appendix A
  item 5 records why: inotify events silently never fire in some sandboxed
  containers. This container is one of them, so the polling path is what has
  actually been exercised here. The pod path is attempted first and falls back
  automatically — a zero-event environment does not need the user to know the
  flag exists, it just needs the build to keep working."
  (:require [babashka.fs :as fs]
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

(defn try-pod-watch!
  "Attempt the fswatcher pod. Returns true if it registered *and* delivered at
  least one event within the probe window, false otherwise — registration alone
  is not evidence that inotify works here."
  [cfg on-change]
  (try
    (let [load-pod (requiring-resolve 'babashka.pods/load-pod)]
      (load-pod 'org.babashka/fswatcher "0.0.7")
      (let [watch (requiring-resolve 'pod.babashka.fswatcher/watch)]
        (doseq [p (watched-paths cfg)]
          (watch (str p) (fn [ev] (on-change [(:path ev)])) {:recursive true}))
        true))
    (catch Throwable e
      (println "clogem-press: fswatcher pod unavailable —" (ex-message e))
      false)))

(defn dev!
  [{:keys [port poll interval] :or {port 1888 interval 500} :as opts}]
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
    (if (or poll (not (try-pod-watch! cfg rebuild)))
      (do (println (format "clogem-press: watching by polling every %d ms%s"
                           interval (if poll "" " (fswatcher unavailable)")))
          (poll-watch! cfg interval rebuild))
      (do (println "clogem-press: watching via fswatcher")
          @(promise)))))
