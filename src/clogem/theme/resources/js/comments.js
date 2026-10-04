/* clogem-press — the giscus theme hook (DESIGN.md §6.8, D-P3-15) and its
 * colour-mode sync (§11.3 item 3, D-P4-3).
 *
 * window.clogem.setCommentsTheme(theme) posts {giscus: {setConfig: {theme}}}
 * to the giscus iframe, targeting https://giscus.app only, and returns
 * whether there was an iframe to post to. It also updates the script
 * element's data-theme, so a client.js that has not run yet starts in it.
 *
 * The widget follows the colour mode — dark → "dark", light and read →
 * "light", auto → "preferred_color_scheme" (giscus then follows the OS
 * itself):
 *
 *   - before client.js runs. Both scripts are deferred, and deferred scripts
 *     run in document order, so this file (in <head>) sets data-theme from
 *     <html data-mode> — which the head script took from the stored mode —
 *     before client.js (in <body>) reads it, once;
 *   - on the iframe's first message. A message sent before the iframe has
 *     loaded is lost, so the current theme is sent again as soon as giscus
 *     first speaks;
 *   - on every "clogem:modechange" from the toggle (js/mode.js).
 *
 * Shipped only to a site with :comments {:provider :giscus}, and loaded only
 * on a page that carries the widget.
 */
(function () {
  "use strict";
  var ORIGIN = "https://giscus.app";
  var root = document.documentElement;
  var ns = (window.clogem = window.clogem || {});

  ns.setCommentsTheme = function (theme) {
    var s = document.querySelector('script[src="' + ORIGIN + '/client.js"]');
    if (s) s.setAttribute("data-theme", theme);
    var f = document.querySelector("iframe.giscus-frame");
    if (!f || !f.contentWindow) return false;
    f.contentWindow.postMessage({ giscus: { setConfig: { theme: theme } } }, ORIGIN);
    return true;
  };

  function giscusTheme(mode) {
    return mode === "dark" ? "dark" : mode === "auto" ? "preferred_color_scheme" : "light";
  }

  function currentMode() {
    return root.getAttribute("data-mode") || root.getAttribute("data-default-mode") || "auto";
  }

  ns.setCommentsTheme(giscusTheme(currentMode()));

  window.addEventListener("message", function first(e) {
    if (e.origin !== ORIGIN) return;
    window.removeEventListener("message", first);
    ns.setCommentsTheme(giscusTheme(currentMode()));
  });

  document.addEventListener("clogem:modechange", function (e) {
    ns.setCommentsTheme(giscusTheme(e.detail && e.detail.mode));
  });
})();
