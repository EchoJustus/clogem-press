/* clogem-press: the htmlModules windows' close button (P4-E1b), hidden
 * until this runs; closed for this page load only. */
(function () {
  "use strict";
  Array.prototype.forEach.call(document.querySelectorAll(".clogem-window__close"), function (c) {
    c.hidden = false;
    c.addEventListener("click", function () {
      var b = document.body, had = document.activeElement === c;
      c.parentNode.hidden = true;
      // focus stays on <body>, the next Tab starting at the top
      if (had) { b.tabIndex = -1; b.focus({ preventScroll: true }); b.removeAttribute("tabindex"); }
    });
  });
})();
