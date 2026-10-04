;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.icons
  "The icon helper templates call (DESIGN.md §11.3 item 6, D-P4-6):

      (icons/icon ctx :sun)
      (icons/icon ctx :sun {:title \"Light\" :class \"is-light\"})
      (icons/icon ctx :brand/github)

  renders an inline `<svg class=\"clogem-icon\">` whose `<use>` points into
  the built sprite, `<base>clogem/icons.svg?v=<fingerprint>#<id>` — the
  fingerprint is Task A's (`clogem.assets/href`), so a sprite change busts
  the cache like any other theme asset. Without a `:title` the icon is
  decoration (`aria-hidden`, never focusable) and its label is the text
  beside it; with one it is `role=\"img\"` with that `aria-label`.

  An unknown name is a programming error in a template, not a site-config
  error: rendering throws, naming the template (namespace and line, taken
  where `icon` is expanded) and the icon, and the build fails naming the
  page. The names are `clogem.sprite/ui-names`, and `:brand/<name>` for
  `clogem.sprite/brand-names`."
  (:require [clojure.string :as str]
            [clogem.assets :as assets]
            [clogem.sprite :as sprite]))

(def sprite-file
  "The sprite's path under `<out>/clogem/`."
  "icons.svg")

(defn href
  "The `<use href>` of icon `k`, or nil for a name the sprite does not have."
  [ctx k]
  (when-let [id (sprite/symbol-id k)]
    (str (assets/href (:cfg ctx) sprite-file) "#" id)))

(defn icon*
  "`icon`, with the caller's location (`clogem.theme.layout:123`) for the
  error message. Prefer the `icon` macro, which supplies it."
  [ctx k {:keys [title class]} where]
  (let [url (or (href ctx k)
                (throw (ex-info (str "unknown icon " (pr-str k) " in template " where
                                     " — the icons are " (str/join " " (map name (sprite/ui-names)))
                                     ", and :brand/<name> for "
                                     (str/join " " (map name (sprite/brand-names))))
                                {:clogem/icon k :clogem/template where})))
        title (some-> title str str/trim not-empty)]
    [:svg (merge {:class (str "clogem-icon" (when class (str " " class)))
                  :width "1em" :height "1em"}
                 (if title
                   {:role "img" :aria-label title}
                   {:aria-hidden "true" :focusable "false"}))
     [:use {:href url}]]))

(defmacro icon
  "Hiccup for icon `k` (see the namespace doc). `opts`: `:title` (makes the
  icon an image with that accessible name) and `:class` (added to
  `clogem-icon`)."
  ([ctx k] `(icon* ~ctx ~k nil ~(str (ns-name *ns*) (when-let [l (:line (meta &form))] (str ":" l)))))
  ([ctx k opts]
   `(icon* ~ctx ~k ~opts ~(str (ns-name *ns*) (when-let [l (:line (meta &form))] (str ":" l))))))
