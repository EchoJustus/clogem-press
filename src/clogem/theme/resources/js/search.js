/* clogem-press — search UI strings for Pagefind's Component UI
 * (DESIGN.md §6.7, D-P3-10).
 *
 * Pagefind 1.5.2 ships no Malay strings, so an `ms` page would show its
 * search dialog in English. The page's `<pagefind-config>` carries the
 * theme's `:search/…` strings for its language as JSON in
 * `data-clogem-translations`; this hands them to the default instance's
 * `setTranslations`, which re-renders every component. Loaded only on a
 * page that has such strings, and deferred after pagefind-component-ui.js,
 * so `window.PagefindComponents` exists when it runs. Pagefind's own
 * placeholders — [SEARCH_TERM], [COUNT], [DIFFERENT_TERM] — are left in.
 */
(function () {
  "use strict";
  var el = document.querySelector("pagefind-config[data-clogem-translations]");
  var pf = window.PagefindComponents;
  if (!el || !pf || typeof pf.getInstanceManager !== "function") return;
  try {
    var strings = JSON.parse(el.getAttribute("data-clogem-translations"));
    pf.getInstanceManager().getInstance("default").setTranslations(strings);
  } catch (e) {
    if (window.console) console.error("clogem-press: search strings not applied", e);
  }
})();
