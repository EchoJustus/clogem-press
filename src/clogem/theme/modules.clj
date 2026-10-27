;; Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
(ns clogem.theme.modules
  "htmlModules (Phase 4 Task E1b, DESIGN.md §11.3 item 13, D-P4-13): the
  owner's raw HTML in vdoing's seven slots — `home-sidebar-b`, `sidebar-t`,
  `sidebar-b`, `page-t`, `page-b`, `window-lb` and `window-rb` — plus
  clogem-press's own `:head-extra` (§5.3).

  Every value read here was checked and repaired by `clogem.config`'s
  `check-html-modules!`, so a renderer never meets a bad one. What the
  owner writes is TRUSTED raw HTML, injected as written and never escaped
  (D-P4-13); `clogem.theme.identity/raw-html` resolves it for the page's
  language and interpolates `{{base}}`.

  This namespace must NOT require `clogem.theme.layout`: layout requires
  it, and home, page and catalogue gain only call sites."
  (:require [clogem.assets :as assets]
            [clogem.config :as config]
            [clogem.i18n :as i18n]
            [clogem.search :as search]
            [clogem.theme.icons :as icons]
            [clogem.theme.identity :as identity]))

(defn module
  "Slot `slot` wrapped for this page's language, or nil when it has no HTML
  here. The wrapper is `tag` (`:div` by default) with the slot's own class;
  while search is on it carries `data-pagefind-ignore=\"all\"`, so the
  owner's HTML never lands in a search result."
  ([ctx slot] (module ctx slot :div))
  ([{:keys [cfg] :as ctx} slot tag]
   (when-let [raw (identity/raw-html ctx (config/html-module cfg slot))]
     [tag (cond-> {:class (str "clogem-module clogem-module--" (name slot))}
            ;; `layout/pagefind` gives "", which Pagefind reads as the
            ;; element only; "all" ignores its children too
            (search/enabled? cfg) (assoc :data-pagefind-ignore "all"))
      raw])))

(defn page-slot
  "`:page-t` or `:page-b` on an article or catalogue page, honouring its
  show-mode: :all everywhere, :article where the page is an article
  (`:article?`, vdoing's `article !== false`), :custom on the others."
  [{:keys [cfg group] :as ctx} slot]
  (let [mode (config/show-mode cfg slot)]
    (when (case mode
            :article (boolean (:article? group))
            :custom  (not (:article? group))
            true)
      (module ctx slot))))

(def ^:private window-slots
  "The two windows, in document order: the class suffix and the slot."
  [["lb" :window-lb] ["rb" :window-rb]])

(defn windows
  "vdoing's windowLB and windowRB: at most two boxes fixed at the bottom of
  the viewport, each with a close button that js/window.js reveals (without
  JS there is nothing to click, and the window stays). Never on the 404
  page, which no reader navigates away from."
  [{:keys [cfg] :as ctx}]
  (when-not (= :not-found (:page-kind ctx))
    (keep (fn [[side slot]]
            (when-let [body (module ctx slot)]
              (let [label (i18n/tr ctx :window/close)]
                [:div {:class (str "clogem-window clogem-window--" side)}
                 [:button.clogem-window__close
                  {:type "button" :hidden true :aria-label label :title label}
                  (icons/icon ctx :x)]
                 body])))
          window-slots)))

(defn script?
  "Does this page link js/window.js — does it render a window?"
  [ctx]
  (boolean (seq (windows ctx))))

(defn head
  "What E1b adds to `<head>`: `:head-extra` as written — unwrapped, with no
  Pagefind attribute — and, on a page that renders a window, the deferred,
  same-origin js/window.js that reveals the close buttons."
  [{:keys [cfg] :as ctx}]
  (list
   (identity/raw-html ctx (config/html-module cfg :head-extra))
   (when (script? ctx)
     [:script {:src (assets/href cfg "js/window.js") :defer true}])))
