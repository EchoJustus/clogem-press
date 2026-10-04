/* clogem-press — colour modes and the mode toggle (DESIGN.md §11.3 items
 * 2–3, D-P4-2, D-P4-3).
 *
 * The inline head script has already chosen the mode before the first paint
 * (the stored one, else <html data-default-mode>) and set <html data-mode>
 * plus the RESOLVED class theme-mode-light|dark|read; it also follows an OS
 * change while the mode is auto. This file adds:
 *
 *   window.clogem.getMode()   → "auto" | "light" | "dark" | "read"
 *   window.clogem.setMode(m)  → stores m (localStorage "clogem-mode", which
 *                               may be blocked), applies it, and dispatches
 *                               "clogem:modechange" on document with
 *                               detail {mode, resolved}; false for anything
 *                               but the four modes
 *
 * and the navbar toggle: a disclosure button that opens four aria-pressed
 * buttons. Enter or Space opens it and focuses the current mode; the arrow
 * keys, Home and End move between the choices; Escape closes it and returns
 * focus to the button, as choosing a mode does; a click outside, or focus
 * leaving it, closes it. The toggle is rendered hidden and revealed here, so
 * it is never a dead control.
 *
 * window.clogem is extended, never replaced: js/comments.js defines
 * setCommentsTheme on it.
 */
(function () {
  "use strict";
  var KEY = "clogem-mode";
  var MODES = ["auto", "light", "dark", "read"];
  var root = document.documentElement;
  var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  var ns = (window.clogem = window.clogem || {});

  function valid(m) { return MODES.indexOf(m) >= 0; }
  function resolve(m) { return m === "auto" ? (mq && mq.matches ? "dark" : "light") : m; }

  function apply(m) {
    root.setAttribute("data-mode", m);
    root.className = (root.className.replace(/(^|\s)theme-mode-\S+/g, "") + " theme-mode-" + resolve(m)).trim();
  }

  function announce(m) {
    var detail = { mode: m, resolved: resolve(m) };
    var ev;
    try {
      ev = new CustomEvent("clogem:modechange", { detail: detail });
    } catch (e) {
      ev = document.createEvent("CustomEvent");
      ev.initCustomEvent("clogem:modechange", false, false, detail);
    }
    document.dispatchEvent(ev);
  }

  var items = [];
  function sync() {
    var m = ns.getMode();
    for (var i = 0; i < items.length; i++) {
      items[i].setAttribute("aria-pressed", String(items[i].getAttribute("data-mode") === m));
    }
  }

  ns.getMode = function () {
    var m = root.getAttribute("data-mode");
    if (valid(m)) return m;
    m = root.getAttribute("data-default-mode");
    return valid(m) ? m : "auto";
  };

  ns.setMode = function (m) {
    if (!valid(m)) return false;
    try { localStorage.setItem(KEY, m); } catch (e) { /* blocked: this page only */ }
    apply(m);
    sync();
    announce(m);
    return true;
  };

  // another tab chose a mode
  window.addEventListener("storage", function (e) {
    if (e.key === KEY && valid(e.newValue) && e.newValue !== ns.getMode()) {
      apply(e.newValue);
      sync();
      announce(e.newValue);
    }
  });

  var box = document.querySelector("[data-clogem-mode]");
  if (!box) return;
  var button = box.querySelector(".clogem-mode__button");
  var menu = button && document.getElementById(button.getAttribute("aria-controls"));
  if (!menu) return;
  items = Array.prototype.slice.call(menu.querySelectorAll("button[data-mode]"));

  function isOpen() { return !menu.hidden; }

  function open() {
    menu.hidden = false;
    button.setAttribute("aria-expanded", "true");
    var current = menu.querySelector('button[aria-pressed="true"]') || items[0];
    if (current) current.focus();
  }

  function close(refocus) {
    if (!isOpen()) return;
    menu.hidden = true;
    button.setAttribute("aria-expanded", "false");
    if (refocus) button.focus();
  }

  button.addEventListener("click", function () {
    if (isOpen()) close(false); else open();
  });

  menu.addEventListener("click", function (e) {
    var b = e.target.closest ? e.target.closest("button[data-mode]") : null;
    if (!b) return;
    ns.setMode(b.getAttribute("data-mode"));
    close(true);
  });

  box.addEventListener("keydown", function (e) {
    if (e.key === "Escape" || e.key === "Esc") {
      if (isOpen()) { e.preventDefault(); close(true); }
      return;
    }
    if (!isOpen()) return;
    var i = items.indexOf(document.activeElement);
    var next = -1;
    if (e.key === "ArrowDown" || e.key === "ArrowRight") next = i < 0 ? 0 : (i + 1) % items.length;
    else if (e.key === "ArrowUp" || e.key === "ArrowLeft") next = i <= 0 ? items.length - 1 : i - 1;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = items.length - 1;
    if (next >= 0) { e.preventDefault(); items[next].focus(); }
  });

  document.addEventListener("click", function (e) {
    if (!box.contains(e.target)) close(false);
  });

  box.addEventListener("focusout", function (e) {
    if (e.relatedTarget && !box.contains(e.relatedTarget)) close(false);
  });

  sync();
  box.hidden = false;
})();
