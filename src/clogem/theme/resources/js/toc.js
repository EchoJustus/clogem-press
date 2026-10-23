/* clogem-press — right-hand TOC scroll-spy (DESIGN.md §5.3, D-P2-9).
 *
 * Vanilla, vendored, no dependencies (D-6: no CDN of any kind). Marks the
 * `.clogem-toc` entry whose heading is the last one at or above the top of
 * the viewport with `is-active`. Progressive enhancement: without JS the
 * TOC is a plain list of anchor links.
 *
 * The one thing that must not be simplified away: hrefs are PERCENT-ENCODED
 * (`#%E4%BD%A0%E5%A5%BD`) while element ids are stored UNENCODED
 * (`id="你好"`), so the fragment is decoded before `getElementById`. On a
 * CJK or Tamil page every heading depends on it.
 */
(function () {
  "use strict";
  var links = Array.prototype.slice.call(
    document.querySelectorAll(".clogem-toc a[href^='#']")
  );
  if (!links.length) return;

  var entries = [];
  links.forEach(function (a) {
    var frag = a.getAttribute("href").slice(1);
    var id;
    try { id = decodeURIComponent(frag); } catch (e) { id = frag; }
    var el = document.getElementById(id);
    if (el) entries.push({ link: a, target: el });
  });
  if (!entries.length) return;

  var current = null;
  var navbar = document.querySelector(".clogem-navbar");

  function update() {
    var y = window.scrollY || window.pageYOffset;
    /* P4-B2b: a heading counts once it is at the navbar's bottom, which is
     * where a jump puts it (theme.css's scroll-padding-top, 0.75rem below
     * the navbar) — measured, not assumed: --navbarHeight can change */
    var offset = (navbar ? Math.max(0, navbar.getBoundingClientRect().bottom) : 0) + 16;
    var active = entries[0];
    for (var i = 0; i < entries.length; i++) {
      if (entries[i].target.getBoundingClientRect().top + y - offset <= y) {
        active = entries[i];
      } else {
        break;
      }
    }
    if (active === current) return;
    if (current) current.link.parentNode.classList.remove("is-active");
    active.link.parentNode.classList.add("is-active");
    current = active;
  }

  var ticking = false;
  function onScroll() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(function () { update(); ticking = false; });
  }

  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  update();
})();
