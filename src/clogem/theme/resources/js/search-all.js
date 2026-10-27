/* clogem-press — "Search all languages" (§6.7, §11.3 item 17, D-P4-17).
 * Off until the reader ticks the box; remembered as `clogem-search-all`.
 * While ticked, the rendered modal's four components are swapped for those
 * of a named instance that merges the other languages at indexWeight
 * 0.001; the default instance never merges. Pagefind 1.5.2: a merge the
 * primary's basePath starts with is skipped, so the URLs are absolute and
 * need `baseUrl`; `language` is the lowercased <html lang>, or findIndex
 * falls back to the largest index; a modal built in JS draws two close
 * buttons, so the rendered one is augmented; and one merge that fails to
 * load fails the instance's load (no "error" event), so the page then
 * falls back to the default instance, keeping the stored choice.
 * Deferred, after pagefind-component-ui.js and js/search.js.
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

  // swap in one instance's components, carrying the term over; focus in
  // the outgoing ones moves to the incoming input
  function show(on) {
    var from = on ? theirs : ours, to = on ? ours : theirs;
    var was = field(from[0]), term = was ? was.value : "", a = document.activeElement;
    var held = from.some(function (e) { return e && e.contains(a); });
    for (var i = 0; i < from.length; i++) {
      if (from[i] && from[i].parentNode) from[i].replaceWith(to[i]);
    }
    if (on) row.appendChild(drop);
    else if (drop.parentNode) drop.parentNode.removeChild(drop);
    var now = field(to[0]);
    if (now) {
      now.value = term;
      if (held) now.focus();
    }
    if (term) (on ? all : pf.getInstanceManager().getInstance("default")).triggerSearch(term);
  }

  // every search of ours awaits the instance's load, which rejects while a
  // merge cannot load (and is retried by the next search)
  all.on("loading", function () {
    all.triggerLoad().then(null, function () {
      if (box.checked) {
        box.checked = false;
        show(false);
      }
    });
  });

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
    show(box.checked);
    remember(box.checked);
    box.focus();
  });

  if (stored()) {
    box.checked = true;
    show(true);
  }
})();
