// Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
//
// No page scrolls sideways on a small phone: at 320 and 360 CSS px wide,
// every HTML page of the site has
// document.documentElement.scrollWidth <= innerWidth. 0.2.0 passes this;
// it guards the mobile layout work of Phase 4 (B1, B2).

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
    }
  }
}
