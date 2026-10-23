/* clogem-press — the drawer and the navbar's dropdowns (P4-B2b, D-P4-9).
 * The open state is the <details>' own; this adds: the sidebar moved (never
 * cloned) into #clogem-drawer while the CSS renders the menu summary, and
 * back at a placeholder when it does not; focus into the drawer on open;
 * Escape, a click outside, a followed link or a bfcache pageshow close it;
 * outside the drawer layout, Escape or a click outside closes a dropdown.
 * A toggle rendered hidden (no :nav, one language) is revealed here. */
(function () {
  "use strict";
  window.clogem = window.clogem || {};
  var toggle = document.querySelector(".clogem-navbar__toggle");
  var drawer = document.getElementById("clogem-drawer");
  var summary = toggle && toggle.querySelector("summary");
  var aside = document.querySelector("aside.clogem-sidebar");
  var mark = null, active = false;

  function rendered(e) { return !!e && e.getClientRects().length > 0; }

  function close(refocus) {
    if (!toggle.open) return;
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

  // the nav and language dropdowns outside the drawer layout
  function dropdowns() {
    return Array.prototype.slice.call(
      document.querySelectorAll(".clogem-navbar__item details[open], .clogem-langs__toggle[open]"));
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
      window.requestAnimationFrame(function () { ticking = false; sync(); });
    });
    // a hint only: what the CSS renders decides
    if (window.matchMedia) {
      var mq = window.matchMedia("(max-width: 50rem)");
      if (mq.addEventListener) mq.addEventListener("change", sync);
      else if (mq.addListener) mq.addListener(sync);
    }
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

  document.addEventListener("click", function (e) {
    var t = e.target;
    if (active && toggle.open) {
      if (toggle.contains(t)) return;   // the summary toggles it itself
      if (!drawer.contains(t) || (t.closest && t.closest("a[href]"))) close(false);
      return;
    }
    dropdowns().forEach(function (d) { if (!box(d).contains(t)) d.open = false; });
  });
})();
