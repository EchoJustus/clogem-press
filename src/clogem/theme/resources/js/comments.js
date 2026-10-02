/* clogem-press — the giscus theme hook (DESIGN.md §6.8, D-P3-15).
 *
 * window.clogem.setCommentsTheme(theme) is for the Phase 4 colour-mode
 * toggle. It posts {giscus: {setConfig: {theme}}} to the giscus iframe,
 * targeting https://giscus.app only, and returns whether there was an iframe
 * to post to. A message sent before the iframe has loaded is lost, which is
 * why the page's initial data-theme (from :theme :default-mode) matters; the
 * script element's data-theme is updated too, so a client.js that has not
 * run yet starts in the new theme.
 *
 * Shipped only to a site with :comments {:provider :giscus}, and loaded only
 * on a page that carries the widget.
 */
(function () {
  "use strict";
  var ORIGIN = "https://giscus.app";
  var ns = (window.clogem = window.clogem || {});
  ns.setCommentsTheme = function (theme) {
    var s = document.querySelector('script[src="' + ORIGIN + '/client.js"]');
    if (s) s.setAttribute("data-theme", theme);
    var f = document.querySelector("iframe.giscus-frame");
    if (!f || !f.contentWindow) return false;
    f.contentWindow.postMessage({ giscus: { setConfig: { theme: theme } } }, ORIGIN);
    return true;
  };
})();
