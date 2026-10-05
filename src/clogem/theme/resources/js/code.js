/* clogem-press — the code block copy button (DESIGN.md §11.3 item 10).
 *
 * Vanilla, vendored, no dependencies (D-6). Shipped only when
 * :highlight :copy-button is on, and loaded only on a page with a code
 * block. A button is added to each `pre.clogem-code` ONLY when the
 * asynchronous clipboard API exists, so a page without JS (or without the
 * API, e.g. plain http on a LAN address) has no dead button, and Pagefind
 * and the feeds, which read the static HTML, never see one.
 *
 * It copies `code.textContent`, which is exactly the fenced source: line
 * numbers are CSS counters and the language label is CSS `content`, so
 * neither is text. The strings and the sprite URLs come from the
 * `#clogem-code-data` JSON the layout writes in the page's language; the
 * button shows the `copy` icon, then `check` for two seconds, and a polite
 * live region announces "Copied".
 */
(function () {
  "use strict";
  if (!navigator.clipboard || typeof navigator.clipboard.writeText !== "function") return;
  var el = document.getElementById("clogem-code-data");
  if (!el) return;
  var data;
  try { data = JSON.parse(el.textContent); } catch (e) { return; }
  var icons = data.icons || {};
  var SVG = "http://www.w3.org/2000/svg";

  function icon(href) {
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("class", "clogem-icon");
    svg.setAttribute("width", "1em");
    svg.setAttribute("height", "1em");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    var use = document.createElementNS(SVG, "use");
    use.setAttribute("href", href);
    svg.appendChild(use);
    return svg;
  }

  function show(btn, copied) {
    var label = copied ? data.copied : data.copy;
    while (btn.firstChild) btn.removeChild(btn.firstChild);
    btn.appendChild(icon(copied ? icons.check : icons.copy));
    btn.setAttribute("aria-label", label);
    btn.title = label;
    btn.classList.toggle("is-copied", copied);
  }

  var status = document.createElement("span");
  status.className = "clogem-visually-hidden clogem-code-status";
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");
  document.body.appendChild(status);

  Array.prototype.forEach.call(document.querySelectorAll("pre.clogem-code"), function (pre) {
    var code = pre.querySelector("code");
    if (!code || pre.querySelector(":scope > .clogem-copy")) return;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "clogem-copy";
    show(btn, false);
    var timer = null;
    btn.addEventListener("click", function () {
      navigator.clipboard.writeText(code.textContent).then(function () {
        show(btn, true);
        status.textContent = "";
        status.textContent = data.copied;
        clearTimeout(timer);
        timer = setTimeout(function () { show(btn, false); status.textContent = ""; }, 2000);
      }, function () { /* denied: leave the button as it is */ });
    });
    pre.appendChild(btn);
    pre.classList.add("has-copy");
  });
})();
