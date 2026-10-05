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

(def previous-subdir
  "Where `run-staged!` keeps the bundle it replaced, until its next swap: a
  page that loaded the old `pagefind-entry.json` still fetches that bundle's
  content-hashed files, and dev's server answers a `pagefind/` file the live
  bundle no longer has from here (DESIGN.md §5.4). `bb build` removes it."
  ".pagefind-prev")

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
  `.pagefind-staging-*` and `.pagefind-old-*` of a `bb dev` no longer
  running, and the previous bundle — go first (`sweep-staging!`).
  A non-zero exit is a build error carrying Pagefind's own output. Returns
  {:binary :output}."
  [cfg]
  (let [bin (ensure-binary! cfg)
        out (config/out-dir cfg)
        _   (sweep-staging! out)
        _   (delete-path! (fs/path out previous-subdir))
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
             (str "dist/ is already written but has no search index; "
                  "fix the cause, or build with `--no-search`.")))
    {:binary bin :output output}))

(def ^:private staging-re
  "`<out>/.pagefind-staging-<pid>-<nanos>` and `.pagefind-old-<pid>-<nanos>`:
  `run-staged!`'s working directories."
  #"^\.pagefind-(?:staging|old)-(\d+)-\d+$")

(defn sweep-staging!
  "Remove the staging and retired bundles `run-staged!` runs that were
  killed left in `out`: those of a process no longer alive, and this
  process's own (it runs one index at a time, so none of them is in use).
  Never through a link. Returns the paths deleted."
  [out]
  (let [me (.pid (java.lang.ProcessHandle/current))]
    (when (fs/directory? out)
      (vec
       (for [p (fs/list-dir out)
             :let [[_ pid] (re-matches staging-re (str (fs/file-name p)))]
             :when (and pid
                        (let [pid (parse-long pid)]
                          (or (= me pid) (not (.isPresent (java.lang.ProcessHandle/of pid))))))]
         (do (delete-path! p)
             p))))))

(def ^:private indexing
  "The Pagefind processes `run-staged!` has running, for `stop-indexing!`."
  (atom #{}))

(defn stop-indexing!
  "Stop every Pagefind `run-staged!` is waiting for — `bb dev` shutting
  down. Each such run then fails with `:clogem/pagefind-exit` set, and
  removes its staging directory."
  []
  (doseq [proc @indexing]
    (try (p/destroy-tree proc)
         (.waitFor ^Process (:proc proc) 2 java.util.concurrent.TimeUnit/SECONDS)
         (catch Throwable _ nil))))

(defn run-staged!
  "`bb dev`'s indexer (DESIGN.md §5.4, §11.3 item 12): index into a fresh
  `<out>/.pagefind-staging-…/` (`--output-path`), then swap it in for
  `<out>/pagefind/`, keeping the bundle it replaces as `previous-subdir`
  until the next swap — so a page that loaded the previous bundle keeps
  searching it, through dev's server, rather than 404ing on a fragment the
  new one renamed. The swap is renames only: the generation before the
  previous one moves aside (and is deleted), the live bundle becomes the
  previous one, the staged one goes live. While `pagefind/` is briefly
  missing between the last two, the server answers from the previous one.
  The staging directory holds no HTML, so neither Pagefind nor the
  stale-HTML sweep sees it. `bb build` keeps `run!`. Returns
  {:binary :output}; a failure is an ex-info whose data carries
  `:clogem/pagefind-exit` when Pagefind ran and exited non-zero."
  [cfg]
  (let [bin     (ensure-binary! cfg)
        out     (config/out-dir cfg)
        tag     (str (.pid (java.lang.ProcessHandle/current)) "-" (System/nanoTime))
        staging (fs/path out (str ".pagefind-staging-" tag))
        retired (fs/path out (str ".pagefind-old-" tag))
        prev    (fs/path out previous-subdir)
        live    (fs/path out output-subdir)]
    (sweep-staging! out)
    (try
      (let [proc (try (p/process {:out :string :err :string}
                                 bin "--site" (str out) "--output-path" (str staging))
                      (catch Exception e
                        (fail! (str "could not run " bin ": " (ex-message e)))))
            _    (swap! indexing conj proc)
            {:keys [exit] :as r} (try @proc (finally (swap! indexing disj proc)))
            output (str (:out r) (:err r))]
        (when-not (zero? exit)
          (try (fail! (str "Pagefind exited " exit ":\n" (str/trimr output))
                      "The previous search index is still in place.")
               (catch clojure.lang.ExceptionInfo e
                 (throw (ex-info (ex-message e) (assoc (ex-data e) :clogem/pagefind-exit exit))))))
        (when (fs/exists? prev {:nofollow-links true})
          (fs/move prev retired {:atomic-move true}))
        (when (fs/exists? live {:nofollow-links true})
          (fs/move live prev {:atomic-move true}))
        (fs/move staging live {:atomic-move true})
        {:binary bin :output output})
      (finally
        (doseq [d [staging retired]] (delete-path! d))))))

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
