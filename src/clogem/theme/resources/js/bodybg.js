/* clogem-press: the body background's rotation (P4-E1a), vdoing's
 * BodyBgImg. None under reduced motion or while the page is hidden. */
(function () {
  "use strict";
  var img = document.querySelector("img.clogem-body-bg[data-clogem-bg]"), urls, i = 0;
  if (!img || !window.matchMedia) return;
  try { urls = JSON.parse(img.getAttribute("data-clogem-bg")); } catch (e) { return; }
  var still = matchMedia("(prefers-reduced-motion: reduce)");
  function next() { return urls[(i + 1) % urls.length]; }
  if (!still.matches) new Image().src = next();
  setInterval(function () {
    if (still.matches || document.hidden) return;
    img.src = next();
    i = (i + 1) % urls.length;
    new Image().src = next();
  }, 1000 * (parseFloat(img.getAttribute("data-interval")) || 15));
})();
