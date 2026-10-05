;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.highlight
  "Syntax highlighting with Chroma (DESIGN.md §11.3 item 10, Phase 4 Task C).

  On by default: `:highlight {:provider :chroma}`. `:provider :none` or
  `--no-highlight` renders code exactly as 0.2.0 did (with the fence-info
  fix below). The binary comes from `clogem.tools` (pinned, sha256-verified,
  cached outside the site). In `build` a fetch, verification or run failure
  is an error naming `--no-highlight`; in a `dev` rebuild it is a warning,
  given once, and the code renders plain.

  ## Fence info

  `parse-info` reads the raw `:info` string, never nextjournal's
  `:language` (which turns `js{1,3-5}` into \"js13-5\"): `lang{1,3-5}`,
  `lang {2}`, `lang:line-numbers`, `lang:no-line-numbers`, extra attributes
  (`clojure title=\"x\" {1}`, ignored) and an empty string. The language is
  the leading run of characters that are not whitespace, `{`, `}` or `:`,
  so no class built from it can hold one.

  ## Markup

      <pre class=\"clogem-code chroma language-js line-numbers ln-w2\" data-lang=\"js\">
        <code class=\"language-js\"><span class=\"line\"><span class=\"cl\">…\\n</span></span>…</code></pre>

  One `<span class=\"line\">` per line, as Chroma emits it (it closes every
  span within its line, multi-line strings and comments included), with
  `hl` added to a highlighted line. Line numbers are CSS counters on
  `.line::before`, NEVER text: Chroma's own numbers were glued to the code
  in the Pagefind index (\"1import\"). `ln-wN` is the digit count of the
  last line number, so the counter column has a fixed width. An unknown
  or missing language gets the same structure built here, with no process.

  ## Processes

  `chroma --list` runs once per binary (cached by its sha256) and is the
  allow-list: a fence language is looked up among the lexers' names,
  aliases and `*.ext` filename patterns, and only the lexer's own name or a
  plain alias — never anything path-like, which Chroma would load as an XML
  lexer file — is passed to `--lexer`. The page map parses every article
  variant once (`clogem.render`), and `warm!` highlights every distinct
  (lexer, code) pair in one Chroma process per lexer and per `batch-size`
  files (`chroma --lexer=L --html --html-only f1 f2 …`, in a fresh temp
  directory holding only those files), splitting the output at
  `<pre class=\"chroma\">`. The renderer reads the cache; a miss (an excerpt
  cut mid-block, a home page body) costs one single-file run.

  ## Cache

  In memory, thread-safe (pages render in parallel), and a `defonce`, so
  it survives `dev` rebuilds: sha256 of [format-version chroma-version
  binary-sha256 lexer code] → the lines' HTML."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.tools :as tools]))

;; ---------------------------------------------------------------------------
;; Config

(def format-version
  "Bumped when the cached fragment's shape changes, so a long-running `dev`
  never serves a fragment an older build cached."
  1)

(defn enabled? [cfg] (= :chroma (get-in cfg [:highlight :provider])))

(defn copy-button? [cfg] (true? (get-in cfg [:highlight :copy-button])))

(def batch-size
  "Files per Chroma process: Windows caps a command line at 32 767
  characters, and 150 short relative names stay far below it."
  150)

;; ---------------------------------------------------------------------------
;; Fence info

(def ^:private max-highlight-span
  "A highlight range wider than this is clipped: `{1-99999999}` must not
  build a set of a hundred million lines."
  100000)

(defn- parse-ranges
  "`1,3-5` → [[1 1] [3 5]]; anything that is not a number or a range is
  dropped."
  [s]
  (vec (for [part (str/split s #",")
             :let [[_ a b] (re-matches #"\s*(\d{1,9})\s*(?:-\s*(\d{1,9}))?\s*" part)]
             :when a
             :let [a (parse-long a)
                   b (if b (parse-long b) a)
                   [lo hi] (sort [a b])]
             :when (pos? lo)]
         [lo (min hi (+ lo max-highlight-span))])))

(defn parse-info
  "The fence info string → {:lang :hl :line-numbers}: the language (nil when
  there is none), the highlighted line ranges ([[lo hi] …]) and the
  per-fence line-number override (true, false or nil for the site default).
  Quoted attribute values are dropped before the rest is read, so
  `title=\"a {2}\"` highlights nothing."
  [info]
  (let [info (str/trim (str info))
        [_ lang rest*] (re-matches #"(?s)([^\s{}:]*)(.*)" info)
        rest* (str/replace (str rest*) #"\"[^\"]*\"|'[^']*'" " ")
        braces (keep (fn [[_ inner]] (when (re-matches #"[\d,\s-]*" inner) inner))
                     (re-seq #"\{([^}]*)\}" rest*))
        flags (map second (re-seq #":((?:no-)?line-numbers)(?![\w-])" rest*))]
    {:lang (not-empty lang)
     :hl   (if-let [b (first braces)] (parse-ranges b) [])
     :line-numbers (case (last flags)
                     "line-numbers"    true
                     "no-line-numbers" false
                     nil)}))

(defn highlighted?
  [ranges n]
  (boolean (some (fn [[lo hi]] (<= lo n hi)) ranges)))

;; ---------------------------------------------------------------------------
;; Escaping and line structure

(defn escape-html
  "Text → HTML text and attribute-safe: `&`, `<`, `>`, `\"` and `'`."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&#34;" \' "&#39;"}))

(defn- normalize-newlines [s] (str/replace (str s) #"\r\n?" "\n"))

(defn plain-lines
  "The line structure Chroma emits, built here for code no lexer handles:
  one `<span class=\"line\"><span class=\"cl\">` per line, the newline
  inside it, the text escaped."
  [code]
  (let [code (normalize-newlines code)]
    (if (= "" code)
      ""
      (apply str (for [l (re-seq #"[^\n]*\n|[^\n]+$" code)]
                   (str "<span class=\"line\"><span class=\"cl\">" (escape-html l) "</span></span>"))))))

(def ^:private line-open "<span class=\"line\">")

(defn decorate
  "The lines' HTML with `hl` added to each highlighted line, and the line
  count: [html n]."
  [lines-html ranges]
  (let [parts (rest (str/split lines-html (re-pattern (java.util.regex.Pattern/quote line-open)) -1))
        n     (count parts)]
    [(if (seq ranges)
       (apply str (map-indexed (fn [i part]
                                 (str (if (highlighted? ranges (inc i)) "<span class=\"line hl\">" line-open)
                                      part))
                               parts))
       lines-html)
     n]))

;; ---------------------------------------------------------------------------
;; The binary, its lexers and styles

(defonce ^:private stats
  ;; process counts, for the tests and the integration check
  (atom {:list 0 :styles 0 :runs 0 :files 0}))

(defn process-stats [] @stats)
(defn reset-stats! [] (reset! stats {:list 0 :styles 0 :runs 0 :files 0}))

(defn safe-arg?
  "Is `s` safe to hand to `--lexer` / `--style`? Chroma loads a value that
  looks like a path as an XML definition file, so `/`, `\\` and `.` are
  never passed, and neither is a leading `-`."
  [s]
  (boolean (and (string? s) (re-matches #"[A-Za-z0-9][A-Za-z0-9 +#_-]*" s))))

(defn parse-list
  "`chroma --list` → {:lexers {lower-case key → arg} :styles #{name} :count n}.
  Keys are each lexer's name, aliases and simple `*.ext` filename
  extensions, in that order of precedence (a name beats another lexer's
  alias); the arg is the lexer's name when that is safe, else its first
  safe alias. A lexer with neither is left out — it renders as plain text."
  [s]
  (let [lines   (str/split-lines (str s))
        entries (loop [ls lines, cur nil, out []]
                  (if-let [l (first ls)]
                    (cond
                      (re-matches #"  \S.*" l)
                      (recur (rest ls) {:name (str/trim l) :aliases [] :exts []} (cond-> out cur (conj cur)))

                      (and cur (re-matches #"\s{4}aliases:.*" l))
                      (recur (rest ls) (assoc cur :aliases (str/split (str/trim (subs (str/trim l) 8)) #"\s+")) out)

                      (and cur (re-matches #"\s{4}filenames:.*" l))
                      (recur (rest ls)
                             (assoc cur :exts (keep #(second (re-matches #"\*\.([A-Za-z0-9+_-]+)" %))
                                                    (str/split (str/trim (subs (str/trim l) 10)) #"\s+")))
                             out)

                      (re-matches #"\S.*" l)          ; "styles: …", "formatters: …"
                      (recur (rest ls) nil (cond-> out cur (conj cur)))

                      :else (recur (rest ls) cur out))
                    (cond-> out cur (conj cur))))
        entries (keep (fn [{:keys [name aliases] :as e}]
                        (when-let [arg (first (filter safe-arg? (cons name aliases)))]
                          (assoc e :arg arg)))
                      entries)
        tier    (fn [ks-of]
                  (reduce (fn [m e]
                            (reduce (fn [m k] (let [k (str/lower-case k)]
                                                (if (or (str/blank? k) (contains? m k)) m (assoc m k (:arg e)))))
                                    m (ks-of e)))
                          {} entries))
        styles  (some (fn [l] (when-let [[_ s] (re-matches #"styles:\s*(.*)" l)]
                                (set (remove str/blank? (str/split s #"\s+")))))
                      lines)]
    {:lexers (merge (tier :exts) (tier :aliases) (tier (comp vector :name)))
     :styles (or styles #{})
     :count  (count entries)}))

(def ^:private hint
  "Build without highlighting with `--no-highlight`, or set :highlight {:provider :none} in site.edn.")

(defn fail!
  [msg & [extra-hint cause]]
  (throw (ex-info (str "clogem-press: highlight: " msg
                       (when extra-hint (str "\n  hint: " extra-hint))
                       "\n  hint: " hint)
                  {:babashka/exit 1 :clogem/tool-error :chroma}
                  cause)))

(defn- exec!
  "Run the binary; [exit stdout-string stderr-string]. Output is decoded as
  UTF-8 whatever the platform's default charset. A binary that cannot be
  started at all — no exec bit, a noexec mount, the wrong architecture —
  throws an `IOException` from the process API, not an exit status; that,
  and anything else the run throws, is a Chroma failure like any other
  (`fail!`), so `build` names `--no-highlight` and a dev rebuild warns."
  [bin dir args]
  (let [{:keys [exit out err]}
        (try (apply p/shell {:dir (str dir) :out :bytes :err :string :continue true :in ""}
                    bin args)
             (catch Throwable t
               (fail! (str "could not run " bin ": " (or (ex-message t) (.getName (class t))))
                      (str "Check that the file is an executable chroma for this platform, "
                           "on a filesystem that allows running programs (not mounted noexec).")
                      t)))]
    [exit (String. ^bytes (or out (byte-array 0)) "UTF-8") (str err)]))

(defonce ^:private binary-shas (java.util.concurrent.ConcurrentHashMap.))

(defn- binary-sha
  "The sha256 of the binary at `bin`, memoised on its path, size and
  modification time — part of every cache key, so a different binary
  never serves another's fragments."
  [bin]
  (let [k [(str bin) (fs/size bin) (str (fs/last-modified-time bin))]]
    (or (.get ^java.util.concurrent.ConcurrentHashMap binary-shas k)
        (let [s (tools/sha256-hex bin)]
          (.put ^java.util.concurrent.ConcurrentHashMap binary-shas k s)
          s))))

(defonce ^:private listings (java.util.concurrent.ConcurrentHashMap.))

(defn- temp-dir [] (fs/create-temp-dir {:prefix "clogem-chroma"}))

(defn- listing
  "`chroma --list`, parsed — once per binary."
  [bin bin-sha]
  (or (.get ^java.util.concurrent.ConcurrentHashMap listings bin-sha)
      (let [dir (temp-dir)
            [exit out err] (try (swap! stats update :list inc)
                                (exec! bin dir ["--list"])
                                (finally (fs/delete-tree dir)))
            parsed (parse-list out)]
        (when-not (and (zero? exit) (pos? (:count parsed)))
          (fail! (str "`" bin " --list` failed (exit " exit "): " (str/trim (str err (when (zero? exit) out))))))
        (.put ^java.util.concurrent.ConcurrentHashMap listings bin-sha parsed)
        parsed)))

(defn lexer-for
  "The `--lexer` argument for fence language `lang`, or nil when Chroma has
  no lexer for it (or there is no language)."
  [session lang]
  (when lang (get-in session [:lexers (str/lower-case lang)])))

;; ---------------------------------------------------------------------------
;; Sessions: one per build

(defonce ^:private dev-failures
  ;; the dev loop's failures, by pin: warned once, and not retried on every
  ;; rebuild (a download that timed out would otherwise stall each one)
  (atom #{}))

(defn soft?
  "Is this a `dev` rebuild, where a Chroma failure warns instead of failing?
  Either flag counts: `:clogem/dev-loop?` (what `clogem.cli/build` sets
  for a rebuild) or `:clogem/dev?` (the dev loop's own config flag), so the
  soft path survives a dev loop that passes either one."
  [cfg]
  (boolean (or (:clogem/dev-loop? cfg) (:clogem/dev? cfg))))

(defn- jobs
  "How many Chroma processes run at once: `CLOGEM_JOBS` when it is a
  positive integer, else the number of processors — the render pool's
  bound (`clogem.render/jobs`, which warns about a bad value)."
  []
  (let [n (some-> (System/getenv "CLOGEM_JOBS") str/trim parse-long)]
    (if (and n (pos? n)) n (.availableProcessors (Runtime/getRuntime)))))

(defn- with-hint
  "A `clogem.tools` failure, with the hint naming `--no-highlight`."
  [^Throwable e]
  (if (str/includes? (str (ex-message e)) "--no-highlight")
    e
    (ex-info (str (ex-message e) "\n  hint: " hint) (merge (ex-data e) {:babashka/exit 1}) e)))

(defn- dev-warn!
  [k ^Throwable e]
  (when-not (contains? @dev-failures k)
    (swap! dev-failures conj k)
    (diag/warn! nil (str (str/replace (str (ex-message e)) #"^clogem-press: " "")
                         "\n         Code renders without highlighting until `bb dev` is restarted."))))

(defn- check-style!
  "The configured style when the binary has it, else (with a warning) the
  built-in default."
  [styles k v]
  (let [default (get-in config/defaults [:highlight k])]
    (if (contains? styles v)
      v
      (do (diag/warn! nil (str ":highlight " k " " (pr-str v) " is not a Chroma style; using " (pr-str default) ".")
                      "`chroma --list` prints the styles (DESIGN.md §11.3 item 10).")
          default))))

(defn has-code?
  "Does any of these parsed documents hold a code block — a `:code` node
  anywhere in it, inside a blockquote, a list or a container included? Read
  from the AST, not the source: a nested list indented four spaces is not
  code, and a fence inside `> ` is (§11.3 item 10: a site without a code
  block never fetches Chroma)."
  [asts]
  (boolean (some #(some (fn [n] (= :code (:type n))) (tree-seq (fn [n] (seq (:content n))) :content %))
                 asts)))

(declare render-stylesheet)

(defn session
  "What a build highlights with, or nil when highlighting is off, the site
  has no code (`code?` false: `has-code?` over every parsed body, so a site
  without a code block never fetches Chroma), or, in a dev rebuild, Chroma
  is unavailable: the binary, its sha256, the lexer table, the styles, and
  a per-build set of the unknown languages already warned about, and the
  bytes of highlight.css. Fetches and verifies Chroma on first use: in
  `build` a failure raises (exit 1), in a dev rebuild it warns once and
  returns nil."
  [cfg code?]
  (when (and (enabled? cfg) code?)
    (let [pin [(tools/version tools/chroma cfg) (get-in cfg [:tools :chroma]) (tools/getenv "CLOGEM_CHROMA")]]
      (when-not (and (soft? cfg) (contains? @dev-failures pin))
        (try
          (let [bin     (tools/ensure-binary! tools/chroma cfg)
                bin-sha (binary-sha bin)
                {:keys [lexers styles] n :count} (listing bin bin-sha)]
            ;; the stylesheet is rendered here, so its Chroma runs fail (or,
            ;; in a dev rebuild, warn) like every other part of the session
            (as-> {:bin bin :bin-sha bin-sha
             :version (tools/version tools/chroma cfg)
             :lexers lexers :lexer-count n
             :style (check-style! styles :style (get-in cfg [:highlight :style]))
             :dark-style (check-style! styles :dark-style (get-in cfg [:highlight :dark-style]))
             :line-numbers (not (false? (get-in cfg [:highlight :line-numbers])))
             :soft? (soft? cfg)
             :pin pin
             :broken (atom false)
             :warned (atom #{})
             :jobs (jobs)} s
              (assoc s :css (render-stylesheet s))))
          (catch Exception e
            ;; an `IOException` hashing an unreadable binary is a Chroma
            ;; failure too, not a stack trace
            (let [e (if (instance? clojure.lang.ExceptionInfo e)
                      e
                      (ex-info (str "clogem-press: highlight: could not use Chroma: "
                                    (or (ex-message e) (.getName (class e))))
                               {:babashka/exit 1 :clogem/tool-error :chroma} e))]
              (if (soft? cfg)
                (do (dev-warn! pin e) nil)
                (throw (with-hint e))))))))))

;; ---------------------------------------------------------------------------
;; Highlighting

(defonce ^:private cache (java.util.concurrent.ConcurrentHashMap.))

(defn cache-size [] (.size ^java.util.concurrent.ConcurrentHashMap cache))
(defn clear-cache! [] (.clear ^java.util.concurrent.ConcurrentHashMap cache))

(defn- sha256-str
  [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes s "UTF-8"))))))

(defn- cache-key
  [session lexer code]
  (sha256-str (pr-str [format-version (:version session) (:bin-sha session) lexer code])))

(defn- split-output
  "Chroma's output for `n` files → the inner HTML of each `<code>`, or nil
  when the parts do not line up with the files."
  [out n]
  (let [parts (rest (str/split out #"<pre class=\"chroma\">" -1))]
    (when (= n (count parts))
      (let [inner (map #(second (re-matches #"(?s)<code>(.*)</code></pre>\s*" %)) parts)]
        (when (every? some? inner) (vec inner))))))

(defn- run-batch!
  "Highlight `codes` (strings) with `lexer` in ONE Chroma process: each code
  a file in a fresh temp directory that is also the working directory, so
  only short relative names reach the command line. Returns the fragments
  in order; raises on a failed run."
  [session lexer codes]
  (let [dir   (temp-dir)
        names (mapv #(format "%04d.txt" %) (range (count codes)))]
    (try
      (doseq [[nm code] (map vector names codes)]
        (spit (fs/file dir nm) code :encoding "UTF-8"))
      (swap! stats #(-> % (update :runs inc) (update :files + (count codes))))
      (let [[exit out err] (exec! (:bin session) dir (concat [(str "--lexer=" lexer) "--html" "--html-only"] names))]
        (when-not (zero? exit)
          (fail! (str "chroma --lexer=" lexer " failed (exit " exit "): " (str/trim err))))
        (or (split-output out (count codes))
            (fail! (str "chroma --lexer=" lexer " returned output that does not split into "
                        (count codes) " code blocks; refusing to guess."))))
      (finally (fs/delete-tree dir)))))

(defn- pmap-bounded
  "`(mapv f coll)`, at most `n` at once, with the caller's bindings. The
  first exception is rethrown."
  [n f coll]
  (let [items (vec coll)]
    (if (<= (min n (count items)) 1)
      (mapv f items)
      (let [results (object-array (count items))
            next-i  (java.util.concurrent.atomic.AtomicLong. 0)
            failure (atom nil)
            work    (bound-fn []
                      (loop []
                        (let [i (.getAndIncrement next-i)]
                          (when (and (< i (count items)) (nil? @failure))
                            (try (aset results i (f (nth items i)))
                                 (catch Throwable t (swap! failure #(or % t))))
                            (recur)))))
            workers (doall (repeatedly (min n (count items)) #(future (work))))]
        (doseq [w workers] @w)
        (when-let [t @failure] (throw t))
        (vec results)))))

(defn parallel-map
  "`pmap-bounded`, public: the page map parses article variants with it."
  [n f coll]
  (pmap-bounded n f coll))

(defn- broken!
  "A failed run: raise in `build`; in a dev rebuild, warn once, mark the
  session broken (the rest of the build renders plain) and return nil. The
  failure is recorded under the session's pin — the key `session` checks —
  so later rebuilds do not run the failing binary again until `bb dev` is
  restarted, as the warning says."
  [session ^Throwable e]
  (if (:soft? session)
    (do (when (compare-and-set! (:broken session) false true)
          (dev-warn! (:pin session) e))
        nil)
    (throw e)))

(defn- prepare-code [code] (normalize-newlines code))

(defn warm!
  "Highlight every (lexer, code) pair in `pairs` that is not cached yet:
  one process per lexer per `batch-size` files, run in parallel."
  [session pairs]
  (when (and session (not @(:broken session)))
    (let [todo   (->> pairs
                      (map (fn [[lexer code]] [lexer (prepare-code code)]))
                      (remove (fn [[lexer code]]
                                (or (= "" code) (= "plaintext" lexer)
                                    (.containsKey ^java.util.concurrent.ConcurrentHashMap cache
                                                  (cache-key session lexer code)))))
                      distinct)
          chunks (for [[lexer ps] (sort-by key (group-by first todo))
                       chunk (partition-all batch-size (map second ps))]
                   [lexer (vec chunk)])]
      (try
        (pmap-bounded (:jobs session)
                      (fn [[lexer codes]]
                        (doseq [[code frag] (map vector codes (run-batch! session lexer codes))]
                          (.put ^java.util.concurrent.ConcurrentHashMap cache (cache-key session lexer code) frag)))
                      chunks)
        (catch clojure.lang.ExceptionInfo e (broken! session e))))
    nil))

(defn lines-html
  "The lines' HTML for `code` in fence language `lang`: from the cache, else
  one Chroma run, else (unknown language, no language, plaintext, or a
  broken dev session) built here. nil when highlighting is not available
  at all, so the caller renders 0.2.0's markup."
  [session lang code]
  (when (and session (not @(:broken session)))
    (let [code  (prepare-code code)
          lexer (lexer-for session lang)]
      (if (or (nil? lexer) (= "plaintext" lexer) (= "" code))
        (plain-lines code)
        (let [k (cache-key session lexer code)]
          (or (.get ^java.util.concurrent.ConcurrentHashMap cache k)
              (try (let [frag (first (run-batch! session lexer [code]))]
                     (.put ^java.util.concurrent.ConcurrentHashMap cache k frag)
                     frag)
                   (catch clojure.lang.ExceptionInfo e
                     (broken! session e)
                     (plain-lines code)))))))))

(defn warn-unknown!
  "Warn about fence language `lang` once per build when Chroma has no lexer
  for it. Atomic (`swap-vals!`): of two pages rendering in parallel, exactly
  one warns. The build's sequential pre-pass (`clogem.render`) calls this
  for every source in sorted order first, so the file named is the first
  that uses the language, whatever order the pages render in."
  [session path lang]
  (when (and session lang (nil? (lexer-for session lang)))
    (let [k (str/lower-case lang)
          [before _] (swap-vals! (:warned session) conj k)]
      (when-not (contains? before k)
        (diag/warn! path (str "unknown code language " (pr-str lang) "; the block is shown as plain text.")
                    (str "Chroma " (:version session) " has " (:lexer-count session)
                         " lexers; `chroma --list` names them, with their aliases and file extensions."))))))

;; ---------------------------------------------------------------------------
;; The pre-pass over parsed documents

(defn code-nodes
  "Every fenced or indented code node in an AST, in document order."
  [ast]
  (filter #(= :code (:type %)) (tree-seq #(seq (:content %)) :content ast)))

(defn node-text [node] (apply str (map :text (:content node))))

(defn pairs
  "[lexer code] for every code node of `ast` Chroma will highlight."
  [session ast]
  (keep (fn [node]
          (let [lexer (lexer-for session (:lang (parse-info (:info node))))]
            (when lexer [lexer (node-text node)])))
        (code-nodes ast)))

;; ---------------------------------------------------------------------------
;; Rendering one block (the `:code` renderer of clogem.markdown)

(defn- digits [n] (count (str (max 1 n))))

(defn code-block
  "Hiccup for one code node. `ctx` is the markdown link context: its
  `:cfg` carries the build's session (`:clogem/highlight`) and its
  `:from-path` names the source for a warning."
  [ctx node]
  (let [{:keys [lang hl line-numbers]} (parse-info (:info node))
        text    (node-text node)
        session (get-in ctx [:cfg :clogem/highlight])
        lines   (lines-html session lang text)
        lang-cl (when lang (str "language-" lang))]
    (if-not lines
      ;; highlighting off: 0.2.0's markup, with a class that can no longer
      ;; carry `{`, `}` or `:` and the language for the CSS label
      [:pre (cond-> {:class (str "clogem-code" (when lang (str " " lang-cl)))}
              lang (assoc :data-lang lang))
       [:code (cond-> {} lang (assoc :class lang-cl)) text]]
      (let [_      (warn-unknown! session (:from-path ctx) lang)
            [html n] (decorate lines hl)
            ln?    (if (some? line-numbers) line-numbers (:line-numbers session))]
        [:pre (cond-> {:class (str "clogem-code chroma"
                                   (when lang (str " " lang-cl))
                                   (when ln? (str " line-numbers ln-w" (min 6 (digits n)))))}
                lang (assoc :data-lang lang))
         [:code (cond-> {} lang (assoc :class lang-cl))
          (h/raw html)]]))))

;; ---------------------------------------------------------------------------
;; highlight.css (§11.3 item 10): --hl-* variables per mode

(def structural-classes
  "Chroma's style rules that are dropped: layout, not token colour — the
  theme owns the code background, line numbers and highlighted lines —
  plus `w` (whitespace: no glyph to colour) and `err`. A lexer marks what
  it cannot tokenize as `err`, and that includes correct code: the Clojure
  lexer splits a Tamil symbol such as `தொலைபேசி` at every vowel sign, and
  `github` would paint each one white on red. Unstyled, it reads in the
  code colour."
  #{"bg" "chroma" "lnlinks" "lntd" "lntable" "hl" "lnt" "ln" "line" "cl" "w" "err"})

(def ^:private props
  "The token properties kept, and the suffix of each one's variable."
  [["color" ""] ["background-color" "-bg"] ["font-weight" "-fw"] ["font-style" "-fs"]
   ["text-decoration" "-td"]])

(defn style-rules
  "Chroma's `--html --html-styles` CSS → {token-class {property value}} for
  the token rules only."
  [css]
  (into (sorted-map)
        (for [[_ cls body] (re-seq #"\.chroma \.([A-Za-z0-9_-]+) \{([^}]*)\}" (str css))
              :when (not (contains? structural-classes cls))
              :let [decls (into {} (for [[_ k v] (re-seq #"([a-z-]+)\s*:\s*([^;]+?)\s*(?:;|$)" body)
                                         :when (some #(= k (first %)) props)]
                                     [k v]))]
              :when (seq decls)]
          [cls decls])))

(defn- var-name [cls suffix] (str "--hl-" cls suffix))

(defn css-from-rules
  "highlight.css from the light and dark styles' token rules: each mode's
  values as `--hl-*` variables, then one rule per token class reading them.
  A property only one style sets is `initial` in the other — not
  `inherit`, which on a custom property would carry the light value into
  dark mode — so the token falls back to the code block's own colour."
  [{:keys [light dark light-name dark-name version]}]
  (let [classes (sort (distinct (concat (keys light) (keys dark))))
        used    (fn [cls] (filter (fn [[p _]] (or (get-in light [cls p]) (get-in dark [cls p]))) props))
        vars    (fn [rules indent]
                  (str/join (for [cls classes
                                  [p suffix] (used cls)]
                              (str indent (var-name cls suffix) ": " (get-in rules [cls p] "initial") ";\n"))))]
    (str "/* clogem-press highlight.css — generated at build time from Chroma " version
         " styles \"" light-name "\" (light) and \"" dark-name "\" (dark, read).\n"
         " * Token colours only; the theme owns the code background, line numbers\n"
         " * and highlighted lines (theme.css). Do not edit: set :highlight :style\n"
         " * and :dark-style instead (DESIGN.md §11.3 item 10). */\n\n"
         ":root, .theme-mode-light {\n" (vars light "  ") "}\n\n"
         ".theme-mode-dark, .theme-mode-read {\n" (vars dark "  ") "}\n\n"
         "@media (prefers-color-scheme: dark) {\n  .theme-mode-auto {\n" (vars dark "    ") "  }\n}\n\n"
         "/* printing uses the light palette whatever the mode (theme.css) */\n"
         "@media print {\n  html[class] {\n" (vars light "    ") "  }\n}\n\n"
         (str/join (for [cls classes]
                     (str ".chroma ." cls " { "
                          (str/join "; " (for [[p suffix] (used cls)] (str p ": var(" (var-name cls suffix) ")")))
                          " }\n"))))))

(defonce ^:private style-css (java.util.concurrent.ConcurrentHashMap.))

(defn- style-css!
  "`chroma --html --html-styles --style=S`, once per binary and style.
  `--html` is required: `--html-prefix` (unused here) applies only with it."
  [session style]
  (let [k [(:bin-sha session) style]]
    (or (.get ^java.util.concurrent.ConcurrentHashMap style-css k)
        (let [dir (temp-dir)
              [exit out err] (try (swap! stats update :styles inc)
                                  (exec! (:bin session) dir ["--html" "--html-styles" (str "--style=" style)])
                                  (finally (fs/delete-tree dir)))]
          (when-not (zero? exit)
            (fail! (str "chroma --html-styles --style=" style " failed (exit " exit "): " (str/trim err))))
          (.put ^java.util.concurrent.ConcurrentHashMap style-css k out)
          out))))

(defn- render-stylesheet
  ^bytes [session]
  (.getBytes ^String (css-from-rules {:light      (style-rules (style-css! session (:style session)))
                                      :dark       (style-rules (style-css! session (:dark-style session)))
                                      :light-name (:style session)
                                      :dark-name  (:dark-style session)
                                      :version    (:version session)})
             "UTF-8"))

(defn stylesheet
  "The bytes of `css/highlight.css` for this build's session."
  ^bytes [session]
  (:css session))
