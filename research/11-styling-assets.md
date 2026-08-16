# Styling + Asset Pipeline Options for clogem-press (Clojure/babashka SSG replicating vdoing)

## 0. What vdoing's theme system actually is (verified from source)

Before picking tooling, it matters that vdoing's theme system is architecturally simple. Verified by reading `vdoing/styles/palette.styl` in `xugaoyi/vuepress-theme-vdoing`:

- **CSS custom properties do all the theming work.** Every mode defines the same set of variables: `--bodyBg`, `--mainBg`, `--sidebarBg`, `--blurBg`, `--customBlockBg`, `--textColor`, `--textLightenColor`, `--borderColor`, `--codeBg`, `--codeColor`.
- **Modes are body-level classes**: `.theme-mode-light`, `.theme-mode-dark`, `.theme-mode-read` (read mode is just a third variable palette — sepia-ish). "Auto" is not a class; it's JS choosing one of the three.
- **Page styles are also body-level classes**: `.theme-style-card` vs `.theme-style-line` (line style combines with mode classes, e.g. `.theme-style-line.theme-mode-dark`).
- The Stylus in vdoing (`palette.styl`, `config.styl`, `code-theme.styl`, etc.) is mostly nesting/organization convenience around what is fundamentally a CSS-variables design. **Nothing about vdoing's theming requires a preprocessor.** Stylus is a VuePress-1 legacy, not a design requirement.

Implication: any of the four options below can replicate vdoing; the decision is about maintainability and dependency weight, not capability.

---

## 1. Plain hand-written CSS with custom properties (zero build step)

- **Mechanism:** ship `theme.css` (+ optional `palette-*.css`) as static resources in the theme; the bb build just copies them to `docs/assets/css/`. Modern CSS now natively covers most of what Sass was used for: custom properties, `calc()`, and **native CSS nesting** (supported in all evergreen browsers since ~2023; fine for a docs site in 2026). No source maps, no compile step, no cache-busting complexity beyond a content-hash rename in bb if desired.
- **Theming for reusers:** document the variable contract (`--bodyBg`, `--textColor`, …). A user overrides the theme by dropping one extra CSS file with a `:root`/`.theme-mode-dark` block after the theme stylesheet — exactly how vdoing users override `palette.styl`, but with zero tooling.
- **Prior art in the babashka ecosystem:** borkdude's **quickblog** (the reference babashka SSG) ships a plain `style.css` template users override from `:templates-dir` — no preprocessor. This is the established pattern for bb SSGs.
- **Cons:** no mixins/loops; repetitive vendor-ish patterns must be written by hand; a large theme (vdoing's styles are several thousand lines of Stylus) becomes a few long CSS files that need discipline (suggest splitting: `base.css`, `layout.css`, `palette.css`, `code.css`, `markdown-containers.css`, concatenated by bb at build time — trivial string concat, still "no build tool").

**Verdict: strongest fit for the stated priorities (no Node, CSS-variable themeable, one-person maintainable).**

---

## 2. Sass/SCSS via dart-sass standalone binary

- **Status (verified):** dart-sass is very actively maintained — latest release **1.102.0, July 25, 2026**. Every release ships per-platform archives (`dart-sass-<ver>-linux-x64.tar.gz`, `-linux-arm64`, `-macos-*`, `-windows-*`, plus musl/android/riscv variants). The archive is **self-contained**: a `sass` wrapper script + bundled Dart runtime/snapshot (recent platforms get AOT-compiled executables). **No Node.js, no system dependencies.** Archive is small (single-digit MB).
- **Is there a bb pod? No.** Verified against `babashka/pod-registry`: 40+ pods (databases, AWS, instaparse, bootleg, etc.), **nothing for Sass/SCSS/CSS/Tailwind**. The integration path is simply `(babashka.process/shell "sass" "src/scss:docs/assets/css")` — bb shelling out to a downloaded binary. A bb task can auto-download the right platform archive on first run (same pattern people use for the Tailwind standalone binary).
- **Pros:** familiar to potential theme contributors; good for a large port if you literally transcribe vdoing's Stylus to SCSS; watch mode built in (`sass --watch`) for the dev server.
- **Cons:** now a per-platform binary your CLI must fetch/manage and pin; adds a compile step to every build and to end-users who want to tweak the theme *source* (though they can still override via plain CSS variables at the output level); one more toolchain surface for a solo maintainer; CI (GitHub Actions) needs the binary installed too.

**Verdict: reasonable middle option; only worth it if you expect the theme CSS to grow past what hand-maintained CSS + native nesting can handle.**

---

## 3. Tailwind standalone CLI (no Node)

- **Status (verified):** Tailwind v4 is current (latest release **v4.3.3, July 2026**); the **standalone CLI** is an officially supported first-class distribution — self-contained executables per platform (`tailwindcss-linux-x64`, `tailwindcss-macos-arm64`, `tailwindcss-windows-x64.exe`, …) attached to each release, built by bundling the CLI + the Rust "Oxide" engine with Bun. Genuinely **no Node/npm required**; invoked from bb exactly like dart-sass (`tailwindcss -i input.css -o out.css --content 'docs/**/*.html'`). Community wrappers (pytailwindcss, jekyll-tailwind-cli, mise plugins) show this pattern is well-trodden.
- **v4 specifics that matter here:** v4 is CSS-first — the config lives in CSS (`@theme { --color-primary: … }`) and **v4's `@theme` emits real CSS custom properties**, so it is not at odds with a CSS-variable theming contract. v4 raised the browser floor (Safari 16.4+, Chrome 111+) — acceptable for a docs tool but worth documenting.
- **Cons for a themeable SSG others reuse (the important part):**
  - Tailwind is a **whole-program compiler over your markup**: it scans templates/output HTML for class names. Since clogem-press generates HTML from Hiccup + user Markdown, every downstream site build must re-run the Tailwind binary over the generated HTML (or you pre-build a superset CSS, which defeats the purity of the approach and reintroduces "safelist" maintenance).
  - Theme customization by users happens in *your* utility-class vocabulary inside Hiccup templates, not in a small CSS-variable palette file — a worse story than vdoing's "override a few variables".
  - The standalone binary is large (tens of MB per platform; the Bun-bundled v4 binaries are notably bigger than v3's ~35 MB) and must be version-pinned and downloaded per platform by your CLI and in CI.
  - Dark mode/`.theme-mode-read`/card-vs-line body classes are perfectly doable (`@custom-variant` in v4), but you'd be fighting the framework to reproduce vdoing's semantic-CSS look rather than leaning on it.

**Verdict: works, but wrong shape for "replicate an existing semantic theme + let users retheme via variables". Best avoided here.**

---

## 4. CSS-from-Clojure-data (garden / girouette / helins)

### garden (the key finding of this research)
- **Upstream `noprompt/garden` is dormant:** last commit **Dec 18, 2023**; README explicitly says the author has limited time; ~1.4k stars, 42 open issues; last Clojars release years older still.
- **BUT there is an actively maintained, babashka-compatible fork: `lambdaisland/garden`** — Clojars coordinate **`com.lambdaisland/garden`, latest 1.9.606, released Dec 8, 2025** (commits Dec 2025; maintained by Arne Brasseur/plexus and contributors, with merged PRs upstream never released). Crucially it keeps the original `garden.*` namespaces (drop-in; just ensure only the fork is on the classpath).
- **Explicit babashka compatibility, with documented caveats** (from the fork's README): works under bb via `:bb` reader conditionals, except (a) `CSSSelector`/`CSSColor` instances can't be invoked as functions because bb can't extend `IFn` — use string selectors like `"*::before"` or call `s/css-selector` explicitly; (b) CSS **compression is unavailable** in bb (it delegates to YUI's `CssCompressor`, not compiled into bb) — keep `:pretty-print? true`. Neither caveat matters much for a docs SSG (gzip on GitHub Pages makes minification marginal).
- **Fit:** garden is "Hiccup for CSS" — EDN vectors/maps → CSS. It pairs naturally with a Hiccup-based SSG: the theme palette becomes an EDN map, modes become generated `.theme-mode-*` blocks, users could even override the palette in `config.edn` and the SSG regenerates CSS — a genuinely nicer theming story than raw CSS, with **zero external binaries** (pure Clojure lib fetched from Clojars via bb.edn deps).

### girouette
- `green-coder/girouette`: grammar-based generative CSS — parses Tailwind-style class names with **Instaparse** and emits **garden** data. Clever, but: last commit **Sep 22, 2023**, last release **v0.0.10 (July 2022)**, ~208 stars — **dormant**. Instaparse does not run natively in bb; it needs `pod-babashka-instaparse`/`instaparse-bb` (only a subset of the API), so girouette-in-bb is unsupported territory. **Not recommended** as a foundation for a project that must outlive its dependencies.

### helins
- Adam Helinski (`helins`) has **no extant CSS library** — a 2021 Clojurians announcement teased one, but no `css`-matching repo exists under the account today (searched their GitHub repositories directly). Treat as nonexistent/abandoned; do not plan around it.

**Verdict: `com.lambdaisland/garden` is the one credible "CSS in Clojure" option — active (Dec 2025 release), bb-tested, pure-JVM/bb dependency, no binaries.**

---

## 5. Dark/light/auto/read mode: the pure-CSS-vars + tiny-JS pattern (industry consensus)

How every modern docs generator (VitePress, Docusaurus, mkdocs-material) does it, and what clogem-press should copy:

1. **CSS:** define the full palette as custom properties per mode. Two workable shapes:
   - vdoing-style: `.theme-mode-light { --bodyBg: #f4f4f4; … }`, `.theme-mode-dark { --bodyBg: rgb(39,39,43); … }`, `.theme-mode-read { … }` on `<body>` (plus `.theme-style-card`/`.theme-style-line`), or
   - VitePress-style: `:root { … }` + `.dark { … }` / `[data-theme=dark]` on `<html>`. For four modes (light/dark/read/auto) the vdoing class-per-mode shape is the cleaner fit; `auto` resolves at runtime to light or dark.
2. **FOUC prevention (the critical detail):** a **synchronous inline `<script>` in `<head>`, before the stylesheet paints** — not an external JS file — that reads localStorage and stamps the class before first paint. VitePress does exactly this with its `vitepress-theme-appearance` localStorage key. Sketch to emit from Hiccup into every page head:
   ```html
   <script>
   (function () {
     var m = localStorage.getItem('clogem-theme-mode') || 'auto';
     if (m === 'auto') {
       m = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
     }
     document.documentElement.className = 'theme-mode-' + m;
   })();
   </script>
   ```
3. **The toggle** (a navbar dropdown with light/dark/read/auto, like vdoing's): ~20 lines of vanilla JS — set localStorage, swap the class, and add a `matchMedia(...).addEventListener('change', …)` listener so `auto` tracks the OS live. No framework, no bundler.
4. Also emit `<meta name="color-scheme" content="light dark">` and set `color-scheme` in CSS so native form controls/scrollbars follow the mode.

This entire subsystem is ~30 lines of hand-written JS + the variable palettes. **No pipeline implications at all.**

---

## 6. Icon strategy without a bundler

- **Inline SVG in Hiccup (recommended):** icons become Clojure data — a namespace of Hiccup vectors (or a `resources/icons/*.svg` dir slurped at build time and inlined). Inline SVG inherits `currentColor`, so icons automatically follow the CSS-variable theme in dark/read modes. Zero runtime requests, zero JS.
- **Where the SVGs come from — Iconify, offline, at *dev* time:**
  - `iconify/icon-sets` repo (auto-updated several times a week; 200+ icon sets, 200k+ icons) stores each set as one **IconifyJSON** file: each icon is `{"body": "<path …/>", "width": W, "height": H}`. A trivial bb helper (bb bundles cheshire/JSON) can extract an icon and wrap it as `<svg viewBox="0 0 W H">body</svg>` — no Node, no Iconify runtime.
  - Even simpler: fetch once from the public API — `https://api.iconify.design/mdi/github.svg` — and **vendor the SVG into the repo**. The Iconify *runtime* components load icons over the network (bad for offline/static), so explicitly do not use `iconify-icon` web components; only use Iconify as a dev-time icon *source*.
  - Alternative direct sources with repo-hosted raw SVGs and permissive licenses: **Tabler Icons** (MIT, ~5,900 icons), **Lucide** (ISC, ~1,600). Check per-set licenses in Iconify's `collections.md` (a few sets require attribution).
- **If icon count grows:** build an **SVG sprite** (`<symbol id="icon-x">…` once per page or as an external `.svg` referenced by `<use href="sprite.svg#icon-x">`) — generated by a bb task from the vendored SVGs. vdoing itself only needs ~a dozen UI icons (mode toggle, search, arrows, hamburger, category/tag/archive), so plain inlining is fine.

---

## 7. Recommendation

**Primary: plain hand-written CSS with custom properties, organized as a few source files concatenated by bb — no compile step, no binaries.** It exactly mirrors what vdoing's theme system actually is (body classes + CSS variables), gives reusers the simplest imaginable theming contract ("override these variables in your own CSS file"), matches babashka-ecosystem precedent (quickblog), and has zero maintenance surface for a solo author.

**Worth adopting alongside it: `com.lambdaisland/garden` (1.9.606, Dec 2025, bb-compatible) for the *palette layer only*** — define the mode palettes as EDN maps in the SSG config and generate the `.theme-mode-*` variable blocks with garden at build time, while keeping the bulky structural/layout CSS as static hand-written files. This gives users EDN-level theming (`:theme {:dark {:body-bg "#111"}}`) with one pure-Clojure dependency and no binaries, and it degrades gracefully — if garden ever dies, the generated CSS is checked-in plain CSS. (Caveats to note in the design doc: keep `:pretty-print? true` under bb; avoid calling CSSSelector/CSSColor records as functions.)

**Explicitly rejected:**
- **Tailwind standalone** — dependency-free but the wrong architecture for a semantic, variable-themeable ported theme; forces a scan-and-compile step onto every downstream site and a large per-platform binary. Keep it in mind only as an *optional* future integration for user content, not the theme.
- **dart-sass standalone** — fine fallback if theme CSS complexity explodes (active project, self-contained binaries, no bb pod exists so it's a `babashka.process/shell` call), but it adds binary management for little gain once native CSS nesting is assumed.
- **girouette** (dormant since 2023, needs an instaparse pod under bb) and **helins CSS** (no longer exists) — not viable foundations.

**Dark mode:** vdoing-style body classes (`theme-mode-light/dark/read`, `theme-style-card/line`) + CSS variables + a synchronous inline head script reading localStorage with `prefers-color-scheme` fallback (VitePress pattern) + a ~20-line vanilla JS toggle. **Icons:** vendored inline SVGs sourced from Iconify's icon-sets JSON or api.iconify.design at development time (or Tabler/Lucide raw SVGs), inheriting `currentColor` for automatic theme adaptation.

## Sources
- https://github.com/babashka/pod-registry
- https://github.com/noprompt/garden
- https://github.com/noprompt/garden/commits/master
- https://github.com/lambdaisland/garden
- https://github.com/lambdaisland/garden/commits/master
- https://github.com/green-coder/girouette
- https://github.com/green-coder/girouette/commits/master
- https://github.com/babashka/pod-babashka-instaparse
- https://github.com/babashka/instaparse-bb
- https://github.com/sass/dart-sass/releases
- https://gist.github.com/ststeiger/f3c11e67753eccc735d02aa58a722f15
- https://tailwindcss.com/blog/standalone-cli
- https://github.com/tailwindlabs/tailwindcss/releases/latest
- https://github.com/tailwindlabs/tailwindcss/discussions/15855
- https://pypi.org/project/pytailwindcss/
- https://raw.githubusercontent.com/xugaoyi/vuepress-theme-vdoing/master/vdoing/styles/palette.styl
- https://github.com/xugaoyi/vuepress-theme-vdoing/tree/master/vdoing/styles
- https://github.com/xugaoyi/vuepress-theme-vdoing/tree/master/vdoing
- https://vitepress.dev/reference/site-config
- https://dev.to/hasansarwer/a-practical-css-variable-setup-for-lightdark-mode-without-theme-flash-22c2
- https://github.com/tailwindlabs/tailwindcss/discussions/3904
- https://github.com/iconify/icon-sets
- https://iconify.design/docs/api/
- https://iconify.design/docs/icon-components/
- https://iconify.design/docs/icons/icon-data.html
- https://github.com/borkdude/quickblog
- https://cljdoc.org/d/io.github.brandoncorrea/garden/1.8.596/doc/readme
- https://clojurians-log.clojureverse.org/announcements/2021-04-09