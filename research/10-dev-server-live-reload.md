# Dev Server + Live Reload for a Babashka SSG — Research Findings

## 1. Static file serving: `org.babashka/http-server`

- **Latest release: 0.1.14** on Clojars, pushed **2025-08-26** by borkdude. A **0.1.15 is in master but unreleased** (CHANGELOG: off-by-one fix in range requests, RFC 9110-correct `Content-Range`, suffix ranges `bytes=-N`). Repo: `babashka/http-server`, MIT, actively maintained by the babashka org.
- Works on both JVM Clojure and babashka; **implemented on `org.httpkit.server/run-server`** (which is built into bb — see §5). `serve` returns http-kit's stop function, so it is embeddable and stoppable in-process (quickblog relies on this).
- API: `(babashka.http-server/serve {:port 8090 :dir "dist" :headers {...} :not-found (fn [req] {:status 404 :body "..."})})`. Options verified in source:
  - `:dir` (default `"."`), `:port` (default 8090), `:headers` (map merged into every response — including directory index pages since 0.1.12), `:not-found` (fn of request → response map, added in 0.1.14).
  - Features from source/CHANGELOG: serves `index.html` for directories, generates a directory listing otherwise, **allows omitting `.html` in paths** (nice for pretty URLs in dev), Range requests (0.1.13+), decent MIME table (incl. `.wasm`).
- **Caveats found by reading `src/babashka/http_server.clj`:**
  - File responses carry only `Content-Type` (+ your `:headers`); **no `ETag` or `Last-Modified` is emitted** (http-kit auto-adds `Content-Length` for `File` bodies). This matters for Live.js-style polling reloaders (§3).
  - `file-router` is **`defn-` (private)** despite the 0.1.12 changelog saying it was moved to top level "for re-use". You can still reuse it via the var (`#'babashka.http-server/file-router`) — works in bb/sci — but it's technically private API; flag this in the design doc (or vendor a ~50-line static handler).

Canonical bb.edn usage (from README):
```clojure
{:deps {org.babashka/http-server {:mvn/version "0.1.14"}}
 :tasks {serve {:requires ([babashka.http-server :as server])
                :task (server/exec {:port 1337 :dir "dist"})}}}
```

## 2. File watching under babashka

### pod-babashka-fswatcher (the standard route)
- **Latest: v0.0.7, released 2025-07-06** (v0.0.7 fixed spurious "done" signals when watching files and bumped fsnotify). Last repo commit Dec 2025 (README touch-up). README still says "experimental", but it has been quickblog's watcher for years — de facto stable. Built on Go's **fsnotify**; binaries for linux/macos/windows; pods auto-download on first use.
- bb.edn declaration (pods are a first-class bb.edn key):
```clojure
{:pods {org.babashka/fswatcher {:version "0.0.7"}}}
```
  (quickblog instead loads it at runtime: `((requiring-resolve 'babashka.pods/load-pod) 'org.babashka/fswatcher "0.0.7")` — useful when you only want the pod in the `dev` task, not on every `bb` invocation.)
- API: `(pod.babashka.fswatcher/watch "content" (fn [event] ...) {:recursive true})` → watcher handle; `(fw/unwatch w)` to stop. Multiple concurrent watchers are fine.
- Caveats: **not recursive by default** — pass `{:recursive true}`; callbacks receive `{:path ... :type ...}` where `:type` ∈ `:create :write :remove :rename :chmod` **and combined forms like `:write|chmod`** (quickblog literally matches `#{:create :remove :rename :write :write|chmod :chmod}`); editors emit several events per save (write + chmod, temp/backup files like Emacs `.#foo.md`), so **debounce + filename filtering is required**.

### Polling alternatives (no pod)
- `babashka.fs/modified-since` (fs is built into bb) in a 200–500 ms loop: compare tree against an anchor file/timestamp, rebuild when non-empty. Zero extra deps, works where inotify/fsnotify is unreliable (Docker bind mounts, NFS); cost is latency + constant wakeups. Reasonable `--poll` fallback flag.
- **`java.nio.file.WatchService` is NOT available in bb** — I checked the babashka CHANGELOG/class additions; various NIO classes are exposed (channels, attributes, PathMatcher) but not WatchService. So it's pod or polling.

## 3. How quickblog implements watch mode (read from source, `borkdude/quickblog`, latest release 0.4.7, 2025-06-12)

`quickblog.api/watch` (src/quickblog/api.clj):
1. Renders the whole site once with `:watch` set to `(format "<script type=\"text/javascript\" src=\"%s\"></script>" lib/live-reload-script)` — the template splices this into pages **only in watch mode**.
2. Starts the file server: `serve` → `babashka.http-server/serve {:port 1888 :dir out-dir}` (default port **1888**), non-blocking.
3. Loads the fswatcher pod at runtime (`load-pod 'org.babashka/fswatcher "0.0.7"`) and registers **three watchers** (non-recursive): `posts-dir`, `templates-dir`, `assets-dir`.
4. **Incremental re-render via caches:** a `posts-cache` atom holds parsed posts. On a post `.md` change it re-parses *just that file* (`lib/load-post`), assoc/dissocs it in the cache (handles delete and parse-error cases), and calls `render` with `:cached-posts` so unchanged posts aren't re-parsed. On a **template** change it drops caches and re-renders everything. On an **asset** change it does `copy-tree-modified` (and deletes removed assets). `:block true` parks on `@(promise)`; otherwise returns watcher handles for `unwatch`.
5. **Browser refresh = Live.js:** `quickblog.internal/live-reload-script` is literally `"https://livejs.com/live.js"` — an external, CDN-hosted, 2011-era script. **No websocket, no SSE, no server cooperation**: live.js polls the current page and its linked CSS/JS with HEAD requests (~1/s) and compares `Last-Modified`/`ETag`/`Content-Length`. Since babashka/http-server emits neither `Last-Modified` nor `ETag` (§1), detection rides on http-kit's auto `Content-Length` — i.e. **an edit that keeps byte length identical is missed**, and **offline dev breaks** (CDN). CSS changes are hot-swapped by live.js without full reload; HTML changes trigger `location.reload()`.

Takeaway: quickblog proves the pod+http-server+cache-atom architecture works well in bb, but its reload transport is the weakest part — easy to beat with a self-hosted SSE endpoint.

## 4. How Eden does live reload (`anteoas/eden` — "Clojure SSG with EDN-first content, Hiccup templates, MCP"; created 2025-08, 2 stars)

- Eden is **JVM Clojure (tools.deps), not babashka** (`clj -X:watch`, `clj -Teden init`).
- `eden.core/watch`: initial build → file watching via **`io.github.anteoas/hawkeye` 2025.08.19** (their own JVM watcher lib) with a **10 ms debounce**, filtering out the output dir to avoid rebuild loops → then starts the dev server.
- The dev server is **not Clojure at all**: it shells out via `clojure.java.process` to **`npx browser-sync start --files "dist/**/*"`**, picking a free port in 3000–4000. browser-sync (npm) does the serving, watches the *output* directory, and injects its own websocket-based reload snippet into served pages. `docs/internals.md` admits this and says future versions "may switch to embedded Jetty".
- Takeaway for clogem-press: Eden outsources serve+reload to the npm ecosystem — exactly what a bb-first, zero-Node toolchain should avoid. Its one good idea to steal: **debounced watching that excludes the output directory**, and watching *sources* for rebuild while reload keys off *output*.

## 5. Browser auto-refresh techniques implementable in pure bb

**http-kit is built into babashka — no dependency needed.** Verified in `babashka/babashka`:
- `deps.edn` (master) bundles `http-kit/http-kit {:mvn/version "2.9.0-beta4"}`; current bb release **v1.13.219 (2026-07-27)**.
- `feature-httpkit-server/babashka/impl/httpkit_server.clj` exposes: `run-server`, `server-stop!`, `server-port`, `server-status`, **`as-channel`, `send!`, `with-channel`, `on-close`, `close`**, plus websocket handshake helpers (`sec-websocket-accept`, `websocket-handshake-check`, `send-websocket-handshake!`, `send-checked-websocket-handshake!`). So **both SSE (HTTP streaming) and real WebSockets work in stock bb**.
- Also built in: `org.httpkit.client`, `babashka.http-client`, `babashka.fs`, `org.babashka/cli` 0.12.86, selmer 1.12.70, hiccup 2.0.0-RC1, nextjournal/markdown 0.7.225 (all relevant to the wider SSG design).

Options ranked:
1. **SSE (recommended).** Client is ~4 lines of injected JS using `EventSource`, which **auto-reconnects natively** — the browser re-attaches by itself after you restart `bb dev`, something a raw WebSocket needs hand-rolled retry code for. Server side: `as-channel` + `send!` with `close-after-send? = false` (http-kit's first `send!` on an HTTP channel sets status/headers; **default closes the channel, so pass `false`**). One-directional is all a reloader needs.
2. **WebSocket.** Fully supported in bb; proven by **`keychera/panas.reload`** (33 stars, updated 2026-04): a bb hot-reload lib serving html+css/htmx over bb's http-kit using the htmx websocket extension; bb > 1.0.169 required; self-described early-stage ("expect a lot of changes"), Windows encoding issues noted. Good existence proof, not a dependency to adopt.
3. **Long-polling `/reload?since=N`.** Works everywhere, but more client JS, request churn, and tab-count × timeout connection pressure. No advantage over SSE given http-kit is built in.
4. **Live.js header-polling (quickblog's choice).** Zero server work but CDN-dependent, 1 Hz HEAD polling per resource, and unreliable against babashka/http-server's validator-free responses (Content-Length heuristic only).
5. **`<meta http-equiv=refresh>`.** Loses scroll position, reloads on a timer regardless of changes — reject.

**Injection technique:** don't rewrite HTML in middleware; render-time is cleaner — in dev mode the renderer appends the snippet before `</body>` (quickblog does exactly this via a `:watch` template variable that is empty in production builds).

## 6. Recommended architecture for `bb dev` (clogem-press)

Single bb process: one http-kit server handling both static files and an SSE endpoint; fswatcher pod watching sources; debounced incremental re-render; broadcast `reload` to all connected `EventSource` clients.

```
bb dev
 ├─ full render → docs/ (dev variant: inject reload <script>, Cache-Control: no-cache)
 ├─ org.httpkit.server/run-server (built-in, port 1888)
 │    ├─ GET /__reload  → SSE channel (as-channel; registered in clients atom)
 │    └─ *              → babashka/http-server file-router over docs/
 ├─ pod-babashka-fswatcher {:recursive true} on content/ templates/ assets/ config.edn
 │    └─ events → LinkedBlockingQueue → drain-until-quiet (~100 ms debounce)
 │         ├─ content .md change  → re-parse that file, update page cache,
 │         │    re-render page + affected structural pages (sidebar/nav/archives
 │         │    recompute from cached front matter — cheap)
 │         ├─ template/config change → drop render caches, full re-render
 │         └─ asset change → copy modified / delete removed
 └─ after each successful render pass → (send! ch "data: reload\n\n" false) to all clients
```

**bb.edn:**
```clojure
{:paths ["src"]
 :deps  {org.babashka/http-server {:mvn/version "0.1.14"}}
 :pods  {org.babashka/fswatcher  {:version "0.0.7"}}
 :tasks
 {render {:doc "Production build" :task (exec 'clogem.build/render)}
  dev    {:doc "Serve + watch + live reload"
          :task (exec 'clogem.dev/dev)}}}
```
(If pod download on every `bb` invocation is undesirable, move the pod to runtime `load-pod` inside the dev task, quickblog-style.)

**Dev namespace sketch (all verified-available APIs):**
```clojure
(ns clogem.dev
  (:require [org.httpkit.server :as srv]
            [babashka.http-server]            ; for its (private) file-router
            [pod.babashka.fswatcher :as fw]
            [clogem.build :as build]))

(defonce clients (atom #{}))

(def reload-js   ;; injected before </body> in dev renders only
  "<script>new EventSource('/__reload').onmessage=e=>{if(e.data==='reload')location.reload()};</script>")

(defn handler [out-dir]
  (let [static ((deref #'babashka.http-server/file-router)  ; private var — see caveat §1
                (babashka.fs/path out-dir)
                {:headers {"Cache-Control" "no-cache"}})]
    (fn [req]
      (if (= "/__reload" (:uri req))
        (srv/as-channel req
          {:on-open  (fn [ch]
                       (swap! clients conj ch)
                       (srv/send! ch {:status 200
                                      :headers {"Content-Type" "text/event-stream"
                                                "Cache-Control" "no-cache"}
                                      :body "retry: 500\n\n"}
                              false))                        ; false = keep streaming
           :on-close (fn [ch _] (swap! clients disj ch))})
        (static req)))))

(defn notify-reload! []
  (doseq [ch @clients] (srv/send! ch "data: reload\n\n" false)))

(defn dev [opts]
  (build/render (assoc opts :dev true))
  (srv/run-server (handler "docs") {:port 1888})
  (let [q (java.util.concurrent.LinkedBlockingQueue.)]
    (doseq [dir ["content" "templates" "assets"]]
      (fw/watch dir #(.offer q %) {:recursive true}))
    (loop []                                   ; debounce: drain until 100ms quiet
      (let [ev (.take q)]
        (loop [] (when (.poll q 100 java.util.concurrent.TimeUnit/MILLISECONDS) (recur)))
        (build/rerender-for! ev)               ; incremental, cache-atom based (quickblog pattern)
        (notify-reload!))
      (recur))))
```

**Design notes / caveats to carry into the doc:**
- Filter events: only `#{:create :write :remove :rename :write|chmod :chmod}` on relevant extensions; skip editor temp files (`.#*`, `*~`, `.swp`) — quickblog got bitten by this and filters explicitly.
- `file-router` privacy: either accept the `#'`-deref (works in sci, low churn risk) or vendor a small static handler; alternatively serve `/__reload` from http-server's `:not-found` hook (it receives the full request map, so `as-channel` works there) — clever but fragile; the composed-handler shown above is the honest version.
- SSE keeps one open connection per tab; http-kit handles this trivially. Send a `retry: 500` line so browsers reconnect fast after `bb dev` restarts.
- Optional refinement: emit `data: css` when only CSS changed and swap `<link href>` querystrings client-side instead of full reload (live.js-style CSS hot-swap without the CDN).
- vdoing-specific: because the sidebar/nav is auto-generated from the content tree, a single content change can invalidate structural pages — keep front-matter/nav data in a cache atom (quickblog's `posts-cache` pattern scaled up) so "re-render changed page + structural pages" stays sub-100 ms rather than a full rebuild.
- Fallback `--poll` flag using `babashka.fs/modified-since` for environments where the fswatcher pod can't run (containers, NFS).

**Version pin summary (as of 2026-08-16):** babashka ≥ 1.12.x, current **v1.13.219** (2026-07-27, bundles http-kit 2.9.0-beta4 server+client built-in); **org.babashka/http-server 0.1.14** (Clojars 2025-08-26); **org.babashka/fswatcher pod 0.0.7** (2025-07-06); quickblog 0.4.7 (2025-06-12) as the reference implementation.


## Sources
- https://github.com/babashka/http-server
- https://raw.githubusercontent.com/babashka/http-server/master/CHANGELOG.md
- https://raw.githubusercontent.com/babashka/http-server/master/src/babashka/http_server.clj
- https://clojars.org/org.babashka/http-server
- https://github.com/babashka/pod-babashka-fswatcher
- https://github.com/babashka/pod-babashka-fswatcher/releases
- https://api.github.com/repos/babashka/pod-babashka-fswatcher/releases/latest
- https://github.com/borkdude/quickblog
- https://raw.githubusercontent.com/borkdude/quickblog/main/src/quickblog/api.clj
- https://raw.githubusercontent.com/borkdude/quickblog/main/src/quickblog/internal.clj
- https://github.com/borkdude/quickblog/blob/main/CHANGELOG.md
- https://github.com/anteoas/eden
- https://raw.githubusercontent.com/anteoas/eden/main/src/eden/core.clj
- https://raw.githubusercontent.com/anteoas/eden/main/deps.edn
- https://book.babashka.org/
- https://raw.githubusercontent.com/babashka/babashka/master/deps.edn
- https://raw.githubusercontent.com/babashka/babashka/master/feature-httpkit-server/babashka/impl/httpkit_server.clj
- https://raw.githubusercontent.com/babashka/babashka/master/CHANGELOG.md
- https://api.github.com/repos/babashka/babashka/releases/latest
- https://github.com/keychera/panas.reload
- https://github.com/babashka/babashka/issues/556
- https://www.http-kit.org/channel.html