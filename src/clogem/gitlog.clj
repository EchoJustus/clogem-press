;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.gitlog
  "Last-updated dates from git (Phase 4 Task E2, §11.3 item 16, D-P4-16):
  ONE `git log` pass over the content directory, run by `render-site` when
  `:theme :last-updated` is true — never by `doctor`, never when it is
  false.

  The result is {site-relative path → ISO 8601 author date}, each path's
  NEWEST commit: `git log` lists commits newest first, so the first time a
  path appears wins. A rename lists only the new name, a merge commit no
  files, and an untracked file is absent — it gets no date. Outside git
  (git cannot be started, or the site is not in a repository) there are no
  dates and nothing is said; a shallow clone, which would date every file
  to its newest commit, and any other git failure are one warning each, and
  no dates."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]))

(defn run-git
  "Run `git <args>` in `dir`: {:exit :out :err}, or nil when git cannot be
  started (not installed, not on PATH). Under `LC_ALL=C`, so a translated
  git still says \"not a git repository\" (`not-a-repo?`) and a warning
  quotes it in English; `-z` keeps paths raw either way. `env` is added to
  the inherited environment."
  ([dir args] (run-git dir args nil))
  ([dir args env]
   (try
     (let [r @(p/process {:cmd (into ["git"] args) :dir (str dir)
                          :out :string :err :string :extra-env (merge {"LC_ALL" "C"} env)})]
       {:exit (:exit r) :out (:out r) :err (:err r)})
     (catch java.io.IOException _ nil))))

(def ^:dynamic *git*
  "The git runner, (fn [dir args] → {:exit :out :err} or nil): `run-git`.
  A test binds it to watch or fake git."
  run-git)

(def log-args
  "The one `git log`: per commit, `\\x01` and the author date (ISO 8601),
  then the paths it touched, NUL-terminated and never quoted (`-z`),
  relative to the site directory (`--relative` with `-C <site>`)."
  ["log" "--format=%x01%aI" "--name-only" "-z" "--relative"])

(defn parse-log
  "`git log` output (`log-args`) → {path → ISO date}: each record is
  `\\x01<ISO>\\0\\n` then the paths, each ending in `\\0`. The first date a
  path appears with — its newest commit — wins."
  [out]
  (reduce (fn [m rec]
            (let [[iso & paths] (str/split rec #"\x00")
                  iso (str/trim iso)]
              (reduce (fn [m [i path]]
                        ;; only the first path follows the header's newline
                        (let [path (if (zero? i) (str/replace path #"^\n" "") path)]
                          (if (or (str/blank? path) (contains? m path)) m (assoc m path iso))))
                      m (map-indexed vector paths))))
          {} (rest (str/split (str out) #"\x01"))))

(defn- not-a-repo?
  [{:keys [err]}]
  (boolean (re-find #"(?i)not a git repository" (str err))))

(defn- failed!
  "One warning for a git failure other than \"not a git repository\",
  quoting git's first stderr line. Returns nil: no dates."
  [{:keys [exit err]}]
  (let [line (some->> (str err) str/split-lines (map str/trim) (remove str/blank?) first)]
    (diag/warn! nil (str ":theme :last-updated: git failed (exit " exit ")"
                         (when line (str ": " line)) "; no last-updated dates are shown.")
                "The build goes on without them. Run the build where `git log` works in the site directory, or set :theme :last-updated false.")
    nil))

(defn- git-out
  "`*git*` in the site directory; nil (silently) when git cannot be started
  or the site is not in a repository, nil with a warning on any other
  failure, else git's output."
  [site args]
  (let [r (*git* site args)]
    (cond
      (nil? r)              nil
      (zero? (:exit r))     (:out r)
      (not-a-repo? r)       nil
      :else                 (failed! r))))

(defn dates
  "{site-relative path → ISO 8601 author date} for every file under the
  content directory that git has a commit for, when `:theme :last-updated`
  is true; else nil, without running git. See the namespace doc."
  [cfg]
  (when (config/last-updated? cfg)
    (let [site (config/site-dir cfg)]
      (when-let [shallow (git-out site ["rev-parse" "--is-shallow-repository"])]
        (if (= "true" (str/trim shallow))
          (do (diag/warn! nil (str ":theme :last-updated: the site is in a shallow clone, which dates every file "
                                   "to its newest commit; no last-updated dates are shown.")
                          "Check out with `fetch-depth: 0` (actions/checkout), or run `git fetch --unshallow`.")
              nil)
          (some-> (git-out site (conj log-args "--" (config/site-rel cfg (config/content-dir cfg))))
                  parse-log))))))
