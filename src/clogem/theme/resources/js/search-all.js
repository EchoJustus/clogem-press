/* clogem-press — "Search all languages" (§6.7, §11.3 item 17, D-P4-17).
 * Opt-in per site (`:search {:cross-language true}`) and per reader: off
 * until the box is ticked, remembered as `clogem-search-all`. A SECOND,
 * named instance merges the other languages at indexWeight 0.001, and the
 * rendered modal's four components are swapped for its own while the box
 * is ticked; the default instance never merges, so a reader who never
 * ticks it downloads no other index. Three facts of Pagefind 1.5.2 shape
 * this: a merge the primary's basePath starts with is skipped silently,
 * so the URLs are absolute — and then `baseUrl` is needed, or merged
 * hrefs come back absolute too; `language` must be Pagefind's own key,
 * the LOWERCASED <html lang>, or findIndex falls back to the largest
 * index; and a modal subtree built in JS and then connected initialises
 * its header twice (two close buttons at <= 640px), so this augments the
 * modal the page rendered and never builds one. Deferred, after
 * pagefind-component-ui.js and js/search.js.
 */
(function () {
  "use strict";
  var cfg = document.querySelector("pagefind-config[data-clogem-merge]");
  var pf = window.PagefindComponents;
  var modal = document.querySelector(".clogem-search pagefind-modal");
  if (!cfg || !modal || !pf || typeof pf.configureInstance !== "function") return;
  var data;
  try {
    data = JSON.parse(cfg.getAttribute("data-clogem-merge"));
  } catch (e) { return; }
  if (!data || !data.langs || !data.langs.length) return;

  var KEY = "clogem-search-all", NAME = "clogem-all", F = "clogem-language";
  var bundle = cfg.getAttribute("bundle-path");
  var own = {};
  own[F] = data.own;
  pf.configureInstance(NAME, {
    bundlePath: bundle,
    mergeFilter: own,
    mergeIndex: data.langs.map(function (l) {
      var mf = {};
      mf[F] = l.label;
      return {
        bundlePath: new URL(bundle, location.href).href,
        baseUrl: data.base,
        language: l.key,
        indexWeight: 0.001,
        mergeFilter: mf
      };
    })
  });
  var all = pf.getInstanceManager().getInstance(NAME);
  // before any of its components connect
  all.setLanguage(cfg.getAttribute("lang"));
  var strings = cfg.getAttribute("data-clogem-translations");
  if (strings) {
    try { all.setTranslations(JSON.parse(strings)); } catch (e) { /* keep Pagefind's */ }
  }

  function mine(name) {
    var e = document.createElement(name);
    e.setAttribute("instance", NAME);
    return e;
  }
  var names = ["pagefind-input", "pagefind-summary", "pagefind-results", "pagefind-keyboard-hints"];
  var theirs = names.map(function (n) { return modal.querySelector(n); });
  var ours = names.map(mine);
  var drop = mine("pagefind-filter-dropdown");
  drop.setAttribute("filter", F);
  drop.setAttribute("label", data.filter);

  var body = modal.querySelector("pagefind-modal-body");
  if (!body) return;
  var row = document.createElement("div");
  row.className = "clogem-search-all";
  var label = document.createElement("label");
  var box = document.createElement("input");
  box.type = "checkbox";
  label.appendChild(box);
  label.appendChild(document.createTextNode(" " + data.label));
  row.appendChild(label);
  body.insertBefore(row, body.firstChild);

  function field(el) { return el && el.querySelector("input"); }

  function show(on) {
    var from = on ? theirs : ours, to = on ? ours : theirs;
    for (var i = 0; i < from.length; i++) {
      if (from[i] && from[i].parentNode) from[i].replaceWith(to[i]);
    }
    if (on) row.appendChild(drop);
    else if (drop.parentNode) drop.parentNode.removeChild(drop);
  }

  function stored() {
    try { return localStorage.getItem(KEY) === "1"; } catch (e) { return false; }
  }
  function remember(on) {
    try {
      if (on) localStorage.setItem(KEY, "1");
      else localStorage.removeItem(KEY);
    } catch (e) { /* blocked storage: this page only */ }
  }

  box.addEventListener("change", function () {
    var on = box.checked;
    var was = field(on ? theirs[0] : ours[0]);
    var term = was ? was.value : "";
    show(on);
    var now = field(on ? ours[0] : theirs[0]);
    if (now) now.value = term;
    if (term) {
      (on ? all : pf.getInstanceManager().getInstance("default")).triggerSearch(term);
    }
    remember(on);
    box.focus();
  });

  if (stored()) {
    box.checked = true;
    show(true);
  }
})();
