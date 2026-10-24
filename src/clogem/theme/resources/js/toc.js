/* clogem-press — right-hand TOC scroll-spy (DESIGN.md §5.3, D-P2-9).
 *
 * Vanilla, vendored, no dependencies (D-6: no CDN of any kind). Marks the
 * `.clogem-toc` entry whose heading is the last one at or above the top of
 * the viewport with `is-active` — or, scrolled to the end of the page, where
 * the last headings can never reach the top, the entry of the fragment the
 * reader just followed, else the last heading whose top is in view
 * (P4-B2b.1). Progressive enhancement: without JS the TOC is a plain list of
 * anchor links.
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

  function byFragment(frag) {
    var id;
    try { id = decodeURIComponent(frag); } catch (e) { id = frag; }
    return document.getElementById(id);
  }

  var entries = [];
  links.forEach(function (a) {
    var el = byFragment(a.getAttribute("href").slice(1));
    if (el) entries.push({ link: a, target: el });
  });
  if (!entries.length) return;

  var current = null;
  var navbar = document.querySelector(".clogem-navbar");
  // the target of the fragment last followed (on load, or a hashchange)
  var followed = null;
  function follow() { followed = location.hash ? byFragment(location.hash.slice(1)) : null; }
  follow();

  function update() {
    var y = window.scrollY || window.pageYOffset;
    /* P4-B2b: a heading counts once it is at the navbar's bottom, which is
     * where a jump puts it (theme.css's scroll-margin-top, 0.75rem below
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
    // at the end the last headings never reach the line: the followed one, if
    // it is still where its jump left it (in view, not under the navbar), else
    // the last whose top is in view
    if (y >= document.documentElement.scrollHeight - window.innerHeight - 1) {
      var last = null;
      for (var j = 0; j < entries.length; j++) {
        var top = entries[j].target.getBoundingClientRect().top;
        if (top >= window.innerHeight) break;
        last = entries[j];
        if (entries[j].target === followed && top >= offset - 17) break;
      }
      if (last) active = last;
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
  // already at the end, a followed fragment scrolls nothing
  window.addEventListener("hashchange", function () { follow(); onScroll(); });
  window.addEventListener("resize", onScroll);
  update();
})();
