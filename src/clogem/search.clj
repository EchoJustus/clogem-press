;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.search
  "Search (DESIGN.md §5.2 step 6, §6.7, D-P3-8 … D-P3-11): fetch, verify and
  cache the Pagefind extended binary, then run it over the finished `dist/`.

  The §4 binary-tool policy is `clogem.tools`'s (Phase 4 Task A): a pinned
  version and a sha256 PER PLATFORM (`:tools :pagefind`), a download with
  babashka's built-in HTTP client, unpacked with `tar` into a cache OUTSIDE
  the site — `$XDG_CACHE_HOME/clogem-press/tools/pagefind/<version>/
  <platform>/` — so a content repo needs no .gitignore entry and the 58 MB
  binary can never land in `dist/`; `CLOGEM_PAGEFIND` or
  `:tools :pagefind :path` names a preinstalled binary and skips download
  and verification.

  Only the EXTENDED binary is fetched: the standard one does not segment
  Chinese, and a zh-Hans search for 简单 returns nothing with it (§6.7)."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.util :as u]
            [clogem.tools :as tools]))

;; ---------------------------------------------------------------------------
;; The Pagefind binary
;;
;; Fetching, verifying and caching moved to `clogem.tools` (Phase 4 Task A),
;; driven by `tools/pagefind`; the cache layout, error messages, the
;; CLOGEM_PAGEFIND variable and `:tools :pagefind` are unchanged, so existing
;; caches stay valid. The names below are 0.2.0's, kept for callers — CI's
;; "Pagefind pin" step reads `platform` and `known-sha256` — and for the
;; tests, which bind this namespace's dynamic vars.

(def known-sha256
  "sha256 of each `pagefind_extended` release asset, per version and platform,
  verified against the release's own `.sha256` files. A site pinning a version
  not listed here supplies its own `:tools :pagefind :sha256` map."
  (:known-sha256 tools/pagefind))

(def default-url
  "The release asset URL; `{{version}}` and `{{platform}}` are filled in.
  `:tools :pagefind :url` overrides it (a mirror — or a test's local server)."
  (:url tools/pagefind))

(def output-subdir
  "Where the bundle lands inside `dist/`; the theme's asset URLs assume it."
  "pagefind")

(def previous-prefix
  "The name prefix of the bundles `run-staged!` replaced and keeps, each as
  `<out>/.pagefind-prev-<pid>-<start>-<retired-ms>` (`work-dir-re`): a page
  that loaded an old `pagefind-entry.json` still fetches that bundle's
  content-hashed files, and dev's server answers a `pagefind/` file the
  live bundle no longer has from these, newest first (DESIGN.md §5.4).
  `bb dev` removes its own when it stops; `bb build` removes those of a
  process no longer running."
  ".pagefind-prev-")

(def ^:dynamic *env*
  "The environment variables this namespace reads, as a map, or nil for the
  process's own. Tests bind it so CI's `CLOGEM_PAGEFIND` cannot leak into a
  test that means to exercise the download path. Conveyed to
  `clogem.tools/*env*` by every wrapper below."
  nil)

(def ^:dynamic *timeouts*
  "Download timeouts in ms; see `clogem.tools/*timeouts*`."
  tools/*timeouts*)

(def ^:dynamic *tar*
  "The tar program. A test binds a name that does not exist."
  "tar")

(defn- pagefind-tool
  "`tools/pagefind`, with `known-sha256` as it is now — a test redefines it."
  []
  (assoc tools/pagefind :known-sha256 known-sha256))

(defmacro ^:private with-tool-env
  "Run body with this namespace's dynamic vars conveyed to clogem.tools."
  [& body]
  `(binding [tools/*env* *env* tools/*timeouts* *timeouts* tools/*tar* *tar*]
     ~@body))

(defn platform
  "The Pagefind release platform for this machine (or for `os-name`/`os-arch`
  as Java reports them), or nil when Pagefind ships no binary for it."
  ([] (tools/platform tools/pagefind))
  ([os-name os-arch] (tools/platform tools/pagefind os-name os-arch)))

(defn binary-name [platform] (tools/binary-name tools/pagefind platform))

(defn version [cfg] (str (get-in cfg [:tools :pagefind :version])))

(defn cache-root
  "The tools cache; see `clogem.tools/cache-root`."
  [cfg]
  (with-tool-env (tools/cache-root cfg)))

(defn tool-dir
  [cfg platform]
  (with-tool-env (tools/tool-dir tools/pagefind cfg platform)))

(defn expected-sha256
  "The pinned hash for this version and platform: the site's
  `:tools :pagefind :sha256` entry, else the built-in table."
  [cfg platform]
  (tools/expected-sha256 (pagefind-tool) cfg platform))

(defn asset-url
  [cfg platform]
  (tools/asset-url tools/pagefind cfg platform))

(def sha256-hex tools/sha256-hex)

(def stamp-name tools/stamp-name)

(defn- no-proxy?
  [host port]
  (with-tool-env (tools/no-proxy? host port)))

(defn- fail!
  [msg & [hint]]
  (tools/fail! tools/pagefind msg hint))

(defn ensure-binary!
  "The Pagefind binary to run, fetching and verifying it on first use.
  Returns its path as a string; raises (exit 1) when it cannot have one.
  See `clogem.tools/ensure-binary!`."
  [cfg]
  (with-tool-env (tools/ensure-binary! (pagefind-tool) cfg)))

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

(declare sweep-staging!)

(defn- delete-path!
  "Delete `p` — a directory tree, or a file or link — never through a link."
  [p]
  (when (fs/exists? p {:nofollow-links true})
    (try
      (if (fs/directory? p {:nofollow-links true}) (fs/delete-tree p) (fs/delete p))
      ;; gone already: dev's shutdown sweep and a stopped run's own cleanup
      ;; can race for the same staging directory
      (catch java.nio.file.NoSuchFileException _ nil))))

(defn run!
  "Index the built site: `<binary> --site <out> --output-subdir pagefind`,
  into a fresh `<out>/pagefind/`. Leftovers of dev's staged indexing —
  the staging directories and previous bundles of a `bb dev` no longer
  running — go first (`sweep-staging!`).
  A non-zero exit is a build error carrying Pagefind's own output. Returns
  {:binary :output}."
  [cfg]
  (let [bin (ensure-binary! cfg)
        out (config/out-dir cfg)
        _   (sweep-staging! out)
        ;; a bundle left by an earlier build into the same out dir would keep
        ;; every fragment of every page since deleted, and grow per rebuild
        _   (delete-path! (fs/path out output-subdir))
        {:keys [exit] :as r}
        (try (p/shell {:out :string :err :string :continue true}
                      bin "--site" (str out) "--output-subdir" output-subdir)
             (catch Exception e
               (fail! (str "could not run " bin ": " (ex-message e)))))
        output (str (:out r) (:err r))]
    (when-not (zero? exit)
      (fail! (str "Pagefind exited " exit ":\n" (str/trimr output))
             ;; the directory written, not always dist/ (P4-D.3 item 21)
             (str (fs/file-name out) "/ (" out ") is already written but has no search index; "
                  "fix the cause, or build with `--no-search`.")))
    {:binary bin :output output}))

(def ^:private work-dir-re
  "`run-staged!`'s directories in `<out>`: `.pagefind-staging-…` (an index
  being written), `.pagefind-old-…` (a bundle on its way out) and
  `.pagefind-prev-…` (a bundle kept for pages that loaded it), each
  `-<pid>-<start>-<n>`: the process's id, its start time in epoch ms — a
  pid alone is reused, and in a container `bb dev` is often pid 1 every
  time — and a number unique to that process. Group 1 is the kind."
  #"^\.pagefind-(staging|old|prev)-(\d+)-(\d+)-(\d+)$")

(def work-dir-prefix
  "What every name of `work-dir-re` and `legacy-work-dir-re` starts with:
  a top-level entry of `<out>` named so is the search step's, and a static
  file there would be swept or replaced (§11.3 item 15)."
  ".pagefind-")

(defn work-dir-name?
  "Could `s`, a top-level name in `<out>`, be one of the search step's own
  directories (`work-dir-prefix`)? Case-folded, for macOS and Windows."
  [s]
  (str/starts-with? (u/lower (str s)) work-dir-prefix))

(def ^:private legacy-work-dir-re
  "The names of this branch's earlier dev builds — `.pagefind-prev`, and
  `-<pid>-<nanos>` or `-<n>` tags without a start time — which no liveness
  check can vouch for: always swept."
  #"^\.pagefind-(?:prev|(?:staging|old|prev)-[0-9-]*)$")

(defn- start-ms
  "The start time of process `h` (a ProcessHandle) in epoch ms, or nil."
  [^java.lang.ProcessHandle h]
  (let [i (.startInstant (.info h))]
    (when (.isPresent i) (.toEpochMilli ^java.time.Instant (.get i)))))

(def ^:private me
  (delay (let [h (java.lang.ProcessHandle/current)]
           {:pid (.pid h) :start (or (start-ms h) 0)})))

(defn- alive?
  "Is the process `pid`, started at `start` (epoch ms), still running? A
  pid that is running but started at another time is someone else."
  [pid start]
  (let [h (java.lang.ProcessHandle/of pid)]
    (boolean
     (and (.isPresent h)
          (.isAlive ^java.lang.ProcessHandle (.get h))
          (let [s (start-ms (.get h))]
            ;; the clock behind startInstant is coarse on some systems
            (or (nil? s) (<= (Math/abs (long (- s start))) 1000)))))))

(def ^:private clock (atom 0))

(defn- tag
  "`<pid>-<start>-<n>` for this process: `n` is the time in epoch ms, made
  strictly increasing, so two names never collide and order is age."
  []
  (let [{:keys [pid start]} @me
        n (swap! clock #(max (inc %) (System/currentTimeMillis)))]
    (str pid "-" start "-" n)))

(defn- work-dirs
  "{:kind :pid :start :n :path} for each of `run-staged!`'s directories
  in `out`, and {:legacy? true :path} for each older-named one."
  [out]
  (when (fs/directory? out)
    (vec (for [p (fs/list-dir out)
               :let [n (str (fs/file-name p))
                     [_ kind pid start k] (re-matches work-dir-re n)]
               :when (or kind (re-matches legacy-work-dir-re n))]
           (if kind
             {:kind kind :pid (parse-long pid) :start (parse-long start) :n (parse-long k) :path p}
             {:legacy? true :path p})))))

(defn- mine? [{:keys [pid start]}]
  (and (= pid (:pid @me)) (= start (:start @me))))

(defn previous-dirs
  "The previous bundles in `out` (`previous-prefix`), newest first."
  [out]
  (->> (work-dirs out)
       (filter #(= "prev" (:kind %)))
       (sort-by :n >)
       (mapv :path)))

(defn sweep-staging!
  "Remove what `run-staged!` runs left in `out` that nothing can be using:
  every directory of a process no longer running, and any older-named one;
  this process's own staging and retiring directories (it runs one index
  at a time, so none is in use); and, with `:own-previous?` — `bb dev`
  stopping — this process's previous bundles too. Never through a link,
  and only in a directory clogem made (`config/out-owned?`, P4-D.3 item 1).
  Returns the paths deleted."
  [out & [{:keys [own-previous?]}]]
  (vec
   (for [{:keys [kind legacy? path pid start] :as d} (when (config/out-owned? out) (work-dirs out))
         :when (cond
                 legacy?   true
                 (mine? d) (or (not= "prev" kind) own-previous?)
                 :else     (not (alive? pid start)))]
     (do (delete-path! path)
         path))))

(def ^:private indexing
  "The Pagefind processes `run-staged!` has running, for `stop-indexing!`."
  (atom #{}))

(defn- destroy! [proc]
  (try (p/destroy-tree proc) (catch Throwable _ nil)))

(defn stop-indexing!
  "Stop every Pagefind `run-staged!` is waiting for — `bb dev` shutting
  down — and wait for each to exit (up to 5 s). Each such run then fails
  with `:clogem/pagefind-exit` set, and removes its staging directory. A
  run that registers its process after this has run kills it at once, when
  its `:cancelled?` says so (`run-staged!`)."
  []
  (let [procs (locking indexing (doall (map (fn [pr] (destroy! pr) pr) @indexing)))]
    (doseq [pr procs]
      (try (.waitFor ^Process (:proc pr) 5 java.util.concurrent.TimeUnit/SECONDS)
           (catch Throwable _ nil)))))

(def max-previous
  "At most this many previous bundles are kept, whatever `:keep-since`
  says: a session of stylesheet edits swaps the index with no page reload."
  8)

(defn- prune-previous!
  "Delete this process's previous bundles in `out` retired before
  `keep-since` (epoch ms), keeping the newest always, and at most
  `max-previous`."
  [out keep-since]
  (let [mine (->> (work-dirs out)
                  (filter #(and (= "prev" (:kind %)) (mine? %)))
                  (sort-by :n >))]
    (doseq [[i {:keys [n path]}] (map-indexed vector mine)
            :when (and (pos? i) (or (>= i max-previous) (< n keep-since)))]
      (delete-path! path))))

(defn run-staged!
  "`bb dev`'s indexer (DESIGN.md §5.4, §11.3 item 12): index into a fresh
  `<out>/.pagefind-staging-…/` (`--output-path`), then swap it in for
  `<out>/pagefind/`, keeping the bundle it replaces as a previous bundle
  (`previous-prefix`) — so a page that loaded it keeps searching it,
  through dev's server, rather than 404ing on a fragment the new one
  renamed. The swap is renames only: the live bundle becomes a previous
  one, the staged one goes live; while `pagefind/` is briefly missing
  between the two, the server answers from the previous ones.

  Which previous bundles are kept (P4-D.2 item 14): every one that was
  live at or after `:keep-since` (epoch ms) — dev passes the time of its
  last page reload, so a bundle any page could have loaded since stays
  servable however many index runs a burst of saves coalesces into — and
  always the newest, up to `max-previous`. Without `:keep-since`, only the
  newest. `:cancelled?` (a function) is asked once the Pagefind process
  exists: when it says so, the process is killed at once, which closes the
  window in which `stop-indexing!` could have missed it.

  The staging directory holds no HTML, so neither Pagefind nor the
  stale-HTML sweep sees it. `bb build` keeps `run!`. Returns
  {:binary :output}; a failure is an ex-info whose data carries
  `:clogem/pagefind-exit` when Pagefind ran and exited non-zero."
  [cfg & [{:keys [keep-since cancelled?]}]]
  (let [bin     (ensure-binary! cfg)
        out     (config/out-dir cfg)
        staging (fs/path out (str ".pagefind-staging-" (tag)))
        live    (fs/path out output-subdir)
        had-live? (fs/exists? live {:nofollow-links true})]
    (sweep-staging! out)
    (try
      (let [proc (try (p/process {:out :string :err :string}
                                 bin "--site" (str out) "--output-path" (str staging))
                      (catch Exception e
                        (fail! (str "could not run " bin ": " (ex-message e)))))
            _    (locking indexing
                   (if (and cancelled? (cancelled?))
                     (destroy! proc)
                     (swap! indexing conj proc)))
            {:keys [exit] :as r} (try @proc (finally (locking indexing (swap! indexing disj proc))))
            output (str (:out r) (:err r))]
        (when-not (zero? exit)
          ;; true only when there is one (P4-D.3 item 21): the first index
          ;; of a session has none to keep
          (try (fail! (str "Pagefind exited " exit ":\n" (str/trimr output))
                      (if had-live?
                        "The previous search index is still in place."
                        "There is no search index yet: search works once an index succeeds."))
               (catch clojure.lang.ExceptionInfo e
                 (throw (ex-info (ex-message e) (assoc (ex-data e) :clogem/pagefind-exit exit
                                                       :clogem/previous-index? had-live?))))))
        (let [retired (fs/path out (str previous-prefix (tag)))]
          (when (fs/exists? live {:nofollow-links true})
            (fs/move live retired {:atomic-move true}))
          (fs/move staging live {:atomic-move true})
          (prune-previous! out (or keep-since Long/MAX_VALUE)))
        {:binary bin :output output})
      (finally
        (delete-path! staging)))))

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
