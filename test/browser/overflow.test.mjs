// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// No page scrolls sideways on a small phone: at 320 and 360 CSS px wide,
// every HTML page of the site has
// document.documentElement.scrollWidth <= innerWidth. 0.2.0 passes this;
// it guards the mobile layout work of Phase 4 (B1, B2). P4-B2b: where
// the menu toggle renders, the same holds with the drawer and everything
// in it open, and the drawer does not scroll sideways either.

const widths = [320, 360];

export default async function overflow(t) {
  for (const width of widths) {
    const page = await t.newPage({ viewport: { width, height: 640 } });
    for (const rel of t.pages) {
      const url = t.url(rel);
      const res = await page.goto(url, { waitUntil: 'load' });
      t.check(res && res.ok(), `${width}px ${url}: HTTP ${res && res.status()}`);
      const [scrollWidth, innerWidth] = await page.evaluate(
        () => [document.documentElement.scrollWidth, window.innerWidth]);
      t.check(scrollWidth <= innerWidth,
              `${width}px ${url}: scrollWidth ${scrollWidth} > innerWidth ${innerWidth}`);
      // P4-B2b: with the drawer open, and every <details> in it, neither
      // the page nor the drawer scrolls sideways
      const d = await page.evaluate(() => {
        const s = document.querySelector('.clogem-navbar__toggle > summary');
        const dr = document.getElementById('clogem-drawer');
        if (!s || !dr || s.getClientRects().length === 0) return null;
        s.parentElement.open = true;
        dr.querySelectorAll('details').forEach((x) => { x.open = true; });
        return { page: document.documentElement.scrollWidth, sw: dr.scrollWidth, cw: dr.clientWidth };
      });
      if (d) {
        t.check(d.page <= innerWidth, `${width}px ${url}, the drawer open: scrollWidth ${d.page} > innerWidth ${innerWidth}`);
        t.check(d.sw <= d.cw, `${width}px ${url}, the drawer open: the drawer's scrollWidth ${d.sw} > clientWidth ${d.cw}`);
      }
    }
  }
}
