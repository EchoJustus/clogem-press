;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
;;
;; CI assertions on a demo build's search output (D-P3-8 … D-P3-10, the Phase 3
;; exit criterion "per-language Pagefind indexes"). Run from the site directory:
;;
;;     bb ../../.github/scripts/check_search.clj <out-dir> <base> <lang>…
;;
;; - pagefind-entry.json lists exactly the configured languages, and each one's
;;   page_count equals the article and catalogue pages built in that language;
;; - every article / catalogue page carries data-pagefind-body, and no other
;;   page does (homes, index and pagination pages are left out);
;; - every page opens <body> with <pagefind-config bundle-path="<base>pagefind/">
;;   whose lang is the page's <html lang>, except zh-Hant → zh-TW.
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(let [[out base & langs] *command-line-args*
      errors (atom [])
      fail!  (fn [& msg] (swap! errors conj (apply str msg)))
      entry  (fs/path out "pagefind" "pagefind-entry.json")]
  (when-not (fs/regular-file? entry)
    (println (str "::error::no " entry))
    (System/exit 1))
  (let [indexed (update-vals (:languages (json/parse-string (slurp (fs/file entry)) true)) :page_count)
        want    (set (map (comp keyword str/lower-case) langs))
        pages   (for [f (fs/glob out "**.html")
                      :let [rel (str (fs/relativize out f))]
                      :when (not (str/starts-with? rel "pagefind"))
                      :let [h (slurp (fs/file f))
                            hl (second (re-find #"<html [^>]*\blang=\"([^\"]+)\"" h))
                            kind (second (re-find #"<body class=\"[^\"]*\bpage-([a-z]+)" h))]]
                  {:rel rel :html h :lang hl :kind kind
                   :article? (contains? #{"article" "catalogue"} kind)
                   :body? (str/includes? h "data-pagefind-body")})
        articles (frequencies (map (comp keyword str/lower-case :lang) (filter :article? pages)))]
    (when-not (= want (set (keys indexed)))
      (fail! "pagefind-entry.json lists " (sort (keys indexed)) ", not " (sort want)))
    (when-not (= articles indexed)
      (fail! "indexed page counts " (into (sorted-map) indexed)
             " do not match the article variants per language " (into (sorted-map) articles)))
    (doseq [{:keys [rel article? body? kind]} pages]
      (cond
        (and article? (not body?)) (fail! rel " is an " kind " page without data-pagefind-body")
        (and body? (not article?)) (fail! rel " (" kind ") carries data-pagefind-body"))
      (when (= "home" kind)
        (when body? (fail! rel " is a home page with data-pagefind-body"))))
    (doseq [{:keys [rel html lang]} pages
            :let [ui (if (str/starts-with? (str/lower-case (str lang)) "zh-hant") "zh-TW" lang)
                  re (re-pattern (str "<body[^>]*><pagefind-config bundle-path=\""
                                      (java.util.regex.Pattern/quote (str base "pagefind/"))
                                      "\"[^>]* lang=\"" (java.util.regex.Pattern/quote (str ui)) "\""))]]
      (when-not (re-find re html)
        (fail! rel ": <body> does not open with <pagefind-config bundle-path=\"" base
               "pagefind/\" … lang=\"" ui "\">")))
    (when (zero? (count (filter #(= "home" (:kind %)) pages)))
      (fail! "no home pages found — the home check is vacuous"))
    (if (seq @errors)
      (do (doseq [e (take 50 @errors)] (println (str "::error::" e)))
          (System/exit 1))
      (println (str "search OK: " (into (sorted-map) indexed) " — "
                    (count (filter :article? pages)) " article pages indexed, "
                    (count (remove :article? pages)) " other pages left out")))))
