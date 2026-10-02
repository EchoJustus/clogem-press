/* clogem-press — the stored language preference and the "also available"
 * banner (DESIGN.md §6.4 rules 3 and 4, D-P3-13, D-P3-14).
 *
 * 1. The navbar language switcher is the ONLY writer of
 *    localStorage['clogem-lang']: a click on one of its links stores that
 *    link's language code. Nothing seeds it from navigator.languages, so a
 *    preference exists only when the reader made a choice. The switcher's
 *    links are never rewritten: a link labelled in the page's language goes
 *    where it says.
 *
 * 2. Under :i18n {:preference :banner}, a page served at its bare URL carries
 *    <script type="application/json" id="clogem-lang-data">: its language, its
 *    identity key, and the other languages of its hreflang set with their
 *    URLs and the banner strings in each. When the stored preference L is one
 *    of them, a dismissible note is inserted at the top of the main column —
 *    in L, with lang="<L>", linking to the L page. Dismissing it stores the
 *    page's identity key in localStorage['clogem-banner-dismissed'] (a JSON
 *    array, the 100 most recent), and the banner stays away for that page.
 *
 * Every storage access is guarded: with storage blocked this behaves as if
 * no preference were set. Shipped only to a site with more than one
 * language; :redirect is a separate inline script in <head>.
 */
(function () {
  "use strict";
  var PREF = "clogem-lang";
  var DISMISSED = "clogem-banner-dismissed";
  var KEEP = 100;

  function read(k) {
    try { return window.localStorage.getItem(k); } catch (e) { return null; }
  }
  function write(k, v) {
    try { window.localStorage.setItem(k, v); } catch (e) { /* storage blocked */ }
  }
  function has(o, k) {
    return !!o && Object.prototype.hasOwnProperty.call(o, k);
  }

  document.addEventListener("click", function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest(".clogem-langs a[data-clogem-lang]") : null;
    if (a) write(PREF, a.getAttribute("data-clogem-lang"));
  });

  function dismissed() {
    try {
      var v = JSON.parse(read(DISMISSED) || "[]");
      return Array.isArray(v) ? v.filter(function (x) { return typeof x === "string"; }) : [];
    } catch (e) { return []; }
  }
  function dismiss(id) {
    var ids = dismissed().filter(function (x) { return x !== id; });
    ids.push(id);
    write(DISMISSED, JSON.stringify(ids.slice(-KEEP)));
  }

  var el = document.getElementById("clogem-lang-data");
  if (!el) return;
  var d;
  try { d = JSON.parse(el.textContent); } catch (e) { return; }
  if (!d || d.mode !== "banner") return;

  var pref = read(PREF);
  if (!pref || pref === d.lang || !has(d.alternates, pref)) return;
  if (dismissed().indexOf(d.id) !== -1) return;
  var main = document.querySelector("main.clogem-main");
  if (!main) return;

  var t = d.alternates[pref];
  var note = document.createElement("div");
  note.className = "clogem-lang-banner";
  note.setAttribute("role", "note");
  note.setAttribute("lang", t.lang);

  var text = document.createElement("span");
  text.className = "clogem-lang-banner__text";
  text.textContent = t.available + " ";
  var link = document.createElement("a");
  link.href = t.url;
  link.setAttribute("hreflang", t.lang);
  link.textContent = t.read;
  text.appendChild(link);

  var close = document.createElement("button");
  close.type = "button";
  close.className = "clogem-lang-banner__dismiss";
  close.setAttribute("aria-label", t.dismiss);
  close.textContent = "×";
  close.addEventListener("click", function () {
    dismiss(d.id);
    if (note.parentNode) note.parentNode.removeChild(note);
  });

  note.appendChild(text);
  note.appendChild(close);
  // before the title, never over it: the note pushes the page down by its
  // own height and nothing else moves
  main.insertBefore(note, main.firstChild);
})();
