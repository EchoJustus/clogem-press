/* clogem-press — the analytics loader (DESIGN.md D-13, §11.3 item 14).
 *
 * Loads the one provider the site owner chose with :analytics :provider —
 * GA4, Plausible or Umami — from the attributes of its own <script> tag,
 * the last child of <head>. No inline script and no eval: the page's one
 * inline script stays the mode script, and 'self' covers this file.
 *
 * It does nothing at all:
 *
 *   - on any host but :site :url's (data-host). Both hosts go through
 *     new URL("https://" + h).hostname, which punycodes an IDN, so
 *     `bb serve`, localhost, a fork and a preview never load the provider;
 *   - unless data-respect-dnt="false", when the browser sends Do Not Track
 *     (navigator.doNotTrack, window.doNotTrack, navigator.msDoNotTrack) or
 *     Global Privacy Control (navigator.globalPrivacyControl).
 *
 * `bb dev` never emits the tag, and a site under :provider :none never
 * ships this file.
 */
(function () {
  "use strict";
  var me = document.currentScript;
  if (!me) return;
  var d = me.dataset;

  function hostname(h) {
    try { return new URL("https://" + h).hostname; } catch (e) { return null; }
  }
  var host = hostname(d.host || "");
  if (!host || hostname(location.hostname) !== host) return;

  var respect = d.respectDnt !== "false";
  if (respect && (navigator.doNotTrack === "1" || window.doNotTrack === "1" ||
                  navigator.msDoNotTrack === "1" || navigator.globalPrivacyControl === true)) return;

  function load(src, attrs, async) {
    var s = document.createElement("script");
    if (async) s.async = true; else s.defer = true;
    for (var k in attrs) s.setAttribute(k, attrs[k]);
    s.src = src;
    document.head.appendChild(s);
  }

  var src = d.src;
  switch (d.provider) {
    case "ga4":
      // Google's consent-mode order: the default before 'js' and 'config',
      // and gtag pushes the arguments object itself, as gtag.js expects
      window.dataLayer = window.dataLayer || [];
      window.gtag = function () { window.dataLayer.push(arguments); };
      if (d.consentDefault === "denied") {
        window.gtag("consent", "default", {
          ad_storage: "denied", ad_user_data: "denied",
          ad_personalization: "denied", analytics_storage: "denied"
        });
      }
      window.gtag("js", new Date());
      window.gtag("config", d.id);
      load(src + (src.indexOf("?") < 0 ? "?" : "&") + "id=" + encodeURIComponent(d.id), {}, true);
      break;
    case "plausible":
      if (d.domain) {
        // the legacy script reads data-domain from its own tag
        load(src, { "data-domain": d.domain }, false);
      } else {
        // Plausible's own stub, then init with :analytics :init
        window.plausible = window.plausible || function () {
          (window.plausible.q = window.plausible.q || []).push(arguments);
        };
        window.plausible.init = window.plausible.init || function (i) { window.plausible.o = i || {}; };
        var init = {};
        try { init = JSON.parse(d.init || "{}"); } catch (e) { /* the build wrote it */ }
        window.plausible.init(init);
        load(src, {}, true);
      }
      break;
    case "umami":
      var attrs = { "data-website-id": d.websiteId, "data-domains": host };
      if (respect) attrs["data-do-not-track"] = "true";
      load(src, attrs, false);
      break;
  }
})();
