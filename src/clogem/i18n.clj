;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.i18n
  "UI strings and the string-or-map config resolver (DESIGN.md §6.5).

  One rule, applied uniformly to theme chrome and to config values alike:

      requested lang → :langs :default → :en → the key itself

  The last step renders as ⟦:page/toc⟧ in dev (loud) and as the :en value in
  production (quiet). Interpolation is `{{var}}` only — no pluralization
  machinery, deliberately (§6.5)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.config :as config]
            [clogem.diag :as diag]
            [clogem.util :as u]))

(defn theme-strings
  [lang]
  (or (some-> (io/resource (str "clogem/theme/resources/i18n/" (name lang) ".edn"))
              slurp edn/read-string)
      {}))

(defn site-strings
  [cfg lang]
  (let [f (fs/path (config/strings-dir cfg) (str (name lang) ".edn"))]
    (if (fs/exists? f)
      (or (edn/read-string (slurp (fs/file f))) {})
      {})))

(defn load-strings
  "Theme defaults deep-merged with the site's overrides, per language."
  [cfg]
  (into {}
        (map (fn [lang] [lang (u/deep-merge (theme-strings lang) (site-strings cfg lang))]))
        (config/lang-keys cfg)))

(defn interpolate
  [s vars]
  (str/replace (str s) #"\{\{\s*([A-Za-z0-9_-]+)\s*\}\}"
               (fn [[whole k]]
                 (let [v (get vars (keyword k) (get vars k))]
                   (if (some? v) (str v) whole)))))

(defn tr
  "Resolve a UI string key for `lang`, applying the fallback chain."
  ([ctx k] (tr ctx k nil))
  ([{:keys [strings lang cfg dev?]} k vars]
   (let [chain (distinct [lang (config/default-lang cfg) :en])
         hit   (some (fn [l] (get-in strings [l k])) chain)]
     (cond
       hit (interpolate hit vars)
       dev? (do (when (= :warn (get-in cfg [:i18n :missing-key]))
                  (diag/warn! nil (str "missing i18n key " k " for language " lang)))
                (str "⟦" k "⟧"))
       :else (str (name k))))))

(defn resolve-str
  "Config values may be a plain string (same in all languages) or a map keyed by
  language code. One rule to learn (§5.6)."
  [{:keys [lang cfg]} v]
  (cond
    (nil? v)    nil
    (string? v) v
    (map? v)    (let [chain (distinct [lang (config/default-lang cfg) :en])]
                  (or (some #(get v %) chain)
                      (first (vals v))))
    :else       (str v)))
