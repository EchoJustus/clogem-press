/* clogem-press — the navbar's drawer and dropdowns, the sidebar's collapse
 * button and back to top (P4-B2b, D-P4-9; DESIGN.md §11.3 item 9). The
 * sidebar is moved, never copied, into #clogem-drawer. */
(function () {
  "use strict";
  window.clogem = window.clogem || {};
  var toggle = document.querySelector(".clogem-navbar__toggle");
  var drawer = document.getElementById("clogem-drawer");
  var summary = toggle && toggle.querySelector("summary");
  var aside = document.querySelector("aside.clogem-sidebar");
  var mark = null, active = false;

  // theme.css: smooth scrolling once loaded (not the jump to #id) or clicked
  var root = document.documentElement, raf = requestAnimationFrame;
  var ready = function () { root.classList.remove("clogem-loading"); };
  root.classList.add("clogem-loading");
  window.addEventListener("load", function () { raf(function () { raf(ready); }); });
  document.addEventListener("click", ready, true);

  // theme.css's jump offset follows a wrapped navbar
  var nb = document.querySelector(".clogem-navbar"), sheet, measure;
  if (nb && window.ResizeObserver && document.adoptedStyleSheets) {
    sheet = new CSSStyleSheet();
    document.adoptedStyleSheets = document.adoptedStyleSheets.concat(sheet);
    measure = function () { sheet.replaceSync(":root{--navbarMeasured:" + nb.getBoundingClientRect().height + "px}"); };
    measure();
    new ResizeObserver(measure).observe(nb);
  }

  // a keyboard focus under the sticky navbar moves out to its scroll-margin
  // (SC 2.4.11): Chromium 141 does not scroll a focus already in the viewport
  if (nb) document.addEventListener("focusin", function (e) {
    var t = e.target, y = t.getBoundingClientRect().top;
    if (!nb.contains(t) && t.matches(":focus-visible") && y < nb.getBoundingClientRect().bottom)
      scrollBy(0, y - parseFloat(getComputedStyle(t).scrollMarginTop));
  });

  function rendered(e) { return e.getClientRects().length > 0; }

  function close(refocus) {
    toggle.open = false;
    if (refocus) summary.focus();
  }

  function sync() {
    var on = rendered(summary);
    if (on === active) return;
    active = on;
    if (on) {
      if (aside) {
        mark = document.createComment("");
        aside.parentNode.insertBefore(mark, aside);
        drawer.appendChild(aside);
      }
    } else {
      close(false);
      if (mark) {
        mark.parentNode.replaceChild(aside, mark);
        mark = null;
      }
    }
  }

  var sbt = document.querySelector(".clogem-sidebar-toggle");
  var shell = document.querySelector(".clogem-shell");
  if (sbt && shell) {
    var collapse = function (c) {
      shell.classList.toggle("is-sidebar-collapsed", c);
      sbt.setAttribute("aria-expanded", !c + "");
    };
    collapse(shell.hasAttribute("data-clogem-collapsed"));
    sbt.hidden = false;
    sbt.addEventListener("click", function () {
      collapse(!shell.classList.contains("is-sidebar-collapsed"));
    });
  }

  var up = document.querySelector(".clogem-back-to-top");
  if (up) {
    // going: up after a click, until the top or a scroll down
    var going = false, last = 0, queued = false;
    var place = function () {
      queued = false;
      var y = window.scrollY;
      if (going && (y <= 0 || y > last)) going = false;
      last = y;
      up.hidden = going || y <= 100;
    };
    window.addEventListener("scroll", function () {
      if (!queued) { queued = true; raf(place); }
    }, { passive: true });
    place();
    up.addEventListener("click", function () {
      var b = document.body, had = document.activeElement === up;
      going = true;
      up.hidden = true;
      // focus stays on <body>, the next Tab starting at the top
      if (had) { b.tabIndex = -1; b.focus({ preventScroll: true }); b.removeAttribute("tabindex"); }
      window.scrollTo({ top: 0 });
    });
  }

  // the dropdowns outside the drawer layout
  function dropdowns() {
    return [].slice.call(document.querySelectorAll(".clogem-navbar__item details[open], .clogem-langs__toggle[open]"));
  }
  function box(d) { return d.classList.contains("clogem-langs__toggle") ? d.parentNode : d; }

  if (toggle && drawer && summary) {
    if (toggle.hidden && aside) toggle.hidden = false;
    sync();

    toggle.addEventListener("toggle", function () {
      if (!toggle.open || !active) return;
      var all = drawer.querySelectorAll("a[href], summary");
      for (var i = 0; i < all.length; i++) {
        if (rendered(all[i])) { all[i].focus(); break; }
      }
    });

    var ticking = false;
    window.addEventListener("resize", function () {
      if (ticking) return;
      ticking = true;
      raf(function () { ticking = false; sync(); });
    });
    window.addEventListener("pageshow", function (e) { if (e.persisted) close(false); });
  }

  document.addEventListener("keydown", function (e) {
    if (e.defaultPrevented || (e.key !== "Escape" && e.key !== "Esc")) return;
    if (active && toggle.open) { close(true); return; }
    dropdowns().forEach(function (d) {
      var focused = box(d).contains(document.activeElement);
      d.open = false;
      if (focused || document.activeElement === document.body) d.querySelector("summary").focus();
    });
  });

  function shut(t, from) {
    dropdowns().forEach(function (d) { var b = box(d); if (b.contains(from || b) && !b.contains(t)) d.open = false; });
  }
  document.addEventListener("focusout", function (e) { if (!active && e.relatedTarget) shut(e.relatedTarget, e.target); });
  window.addEventListener("blur", function () { if (!active) shut(document.activeElement); });

  document.addEventListener("click", function (e) {
    var t = e.target;
    if (active && toggle.open) {
      if (toggle.contains(t)) return;   // the summary toggles it itself
      if (!drawer.contains(t) || t.closest("a[href]")) close(false);
      return;
    }
    shut(t);
  });
})();
