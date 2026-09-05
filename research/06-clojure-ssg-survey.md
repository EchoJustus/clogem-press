# Survey: the rest of the Clojure static-site ecosystem (beyond Cryogen/quickblog/Eden/Fabricate)

All repo metadata verified against GitHub on 2026-08-16.

---

## 1. stasis (magnars/stasis) — the minimalist "no framework" toolkit

**Description.** Stasis is deliberately *not* a static site generator — it is "some Clojure functions for creating static websites." Its whole surface area is a handful of functions: `serve-pages` (turn a map of `{uri -> content-or-fn}` into a Ring handler for live local development), `export-pages` (write that same map to disk), plus helpers `slurp-directory`, `slurp-resources`, `merge-page-sources`, `report-differences`, and support for dependent pages. You bring everything else: templating, markdown, navigation, asset pipeline, config. The core abstraction — *"a site is a map from URL paths to page-producing functions"* — is exactly the right substrate for a custom generator like clogem-press.

- **Templating/markdown:** none prescribed; README explicitly points at Hiccup/Enlive/Selmer + any markdown lib.
- **Babashka compatibility:** yes, deliberately. The 2023.11.21 release notes updating `ring/ring-codec` specifically "to work with babashka." Export is plain file I/O; the dev server is a Ring handler you can mount on http-kit, which is *built into* babashka (`org.httpkit.server`). The library is a single small namespace, so even vendoring it is trivial.
- **Maintenance 2026:** last push 2024-05-30; latest version 2023.11.21. This is a "finished" library, stable by design since 2014 (author commits to no breaking changes); low churn is a feature, not abandonment. Magnar Sveen remains active on GitHub.
- **Adoption:** 360 stars, 27 forks; the foundation under Powerpack and many long-lived Clojure company/personal sites (kodemaker.no, parens-of-the-dead era sites, etc.).

**Verdict: viable — the strongest foundation candidate for a bb-native custom SSG.**

## 2. powerpack (cjohansen/powerpack) — batteries-included layer on stasis

**Description.** "A batteries-included static web site toolkit for Clojure" by Christian Johansen (magnars' long-time collaborator). It wires stasis together with an **in-memory Datomic database as the content model**: markdown/EDN files are ingested into Datomic, every page is an entity with a `:page/uri`, and your single `:powerpack/render-page` function receives (db, page) and returns hiccup (or strings/Ring responses/EDN/JSON). Included: Optimus (asset optimization/fingerprinting), Imagine (image transforms), Beholder (file-watch + live reload dev server), m1p (i18n), mapdown (key/value markdown), flexmark (markdown parsing). The Datomic content model is genuinely attractive for a vdoing-style knowledge base (categories, tags, archives, structure pages are just queries).

- **Templating/markdown:** hiccup render function; markdown via flexmark (`flexmark-all 0.64.8`); content-as-data via Datomic queries.
- **Babashka compatibility:** **no.** deps.edn pins `com.datomic/peer 1.0.7364`, core.async, Integrant, Ring 2.0 alpha — JVM-only, and Datomic peer can never run under bb/SCI. A clogem-press on powerpack means a JVM (deps.edn) build step; a bb CLI could only be a thin wrapper that shells out to `clojure`.
- **Maintenance 2026:** actively maintained — created Sept 2023, latest release 2025.10.22, last push 2026-01-05. Production-tested on a ~5,000-page site run for five years (per README). Step-by-step tutorial repo: `cjohansen/powerblog`.
- **Adoption:** 68 stars, 6 forks — young but credible (author's track record; real production use).

**Verdict: viable, actively maintained — the best "don't build the plumbing yourself" JVM option; incompatible with a pure-babashka CLI requirement.**

## 3. Toto — real repo is metasoarous/toto (not prestancedesign)

**Description.** The `prestancedesign/toto` URL in the brief does not exist — I enumerated all 21 prestancedesign repos; none is an SSG. The real project is **metasoarous/toto**: "Static site generation in Clojure (with live code reloading!)" — an experimental/alpha fork of Oz (same author) that strips out the Vega dataviz parts and keeps the site-generation toolkit: build-specs mapping source dirs → destination dirs with template functions, markdown with YAML frontmatter, hiccup embeddable in markdown code blocks, and Oz's signature live-reload-in-browser dev loop.
- **Templating/markdown:** hiccup template fns + markdown w/ frontmatter.
- **Babashka:** no (Oz-derived JVM stack, websockets/figwheel-ish machinery).
- **Maintenance 2026:** last push **2021-01-30**; self-described alpha; 3 open issues untouched. 112 stars.

**Verdict: dead (5.5 years idle, never left alpha). Ideas worth stealing (build-spec model, live reload), code not worth adopting.**

## 4. Perun (hashobject/perun) — Boot-based pipeline SSG

**Description.** "Programmable static site generator built with Clojure and Boot" — a composable pipeline of Boot tasks (markdown parsing, frontmatter, collections, pagination, sitemap, RSS/atom, etc.) where the site is metadata flowing through task middleware. Architecturally influential (the "SSG as data pipeline" idea), and its task-based decomposition is a useful design reference for clogem-press's build pipeline.
- **Maintenance 2026:** the repo description literally says "(HELP NEEDED!)". Recent `pushed_at` (2026-07-25) is misleading: the commit log shows the last *substantive* code commits in **Nov–Dec 2020**; everything since (Jan 2024, Jun 2026, Jul 2026) is README tweaks. 352 stars, 27 open issues.
- **Fatal coupling:** it is inseparable from **Boot**, which is itself moribund (boot-clj/boot last push 2021-04-22, last release 2.8.3 from 2019, 111 open issues). Babashka: no.

**Verdict: dead-end foundation. Mine it for pipeline-design ideas only.**

## 5. Hardly (kees-/hardly) — babashka suite for site generation

**Description.** A personal "Babashka suite for site generation": bb tasks (`clean`, `resources`, `index`, `build`, `deploy`, `changes`) that render **Selmer** templates from EDN content blocks and Markdown files (with a custom recursive `%include content-type path` directive), deploying via S3 sync. The README itself recommends quickblog for real use and admits "I don't have hot reload."
- **Maintenance/adoption 2026:** 0 stars, 0 forks, **8 commits total**; created Dec 2022, last touched May 2025. Personal-use only.

**Verdict: not a foundation — but a useful existence proof that a full bb+Selmer build/deploy pipeline fits in a handful of tasks.**

## 6. misaki, incise, oz

- **misaki (liquidz/misaki):** Jekyll-inspired SSG, 319 stars — repo is **archived**, last push **2014**. Dead.
- **incise (RyanMcG/incise):** "extensible static site generator," 33 stars, last push **Dec 2018**. Dead.
- **oz (metasoarous/oz):** 834 stars, primarily a Vega/Vega-lite dataviz tool for Clojure that grew static-site export + live-reload features (later forked into toto). Last push 2024-04-02 (docs-level), 66 open issues, substantive development stalled years ago; and its center of gravity is dataviz notebooks, not docs sites. **Not a relevant foundation** — the ecosystem has moved to Clerk for that niche anyway.

**Hiccup-based approaches generally:** the live pattern in 2026 is not any of these frameworks but *plain functions returning hiccup* over a content map — which is exactly the stasis and quickblog model, and what babashka now supports natively (hiccup and selmer are bundled into bb).

## 7. Babashka-based site generation "in the wild"

- **babashka.org** (`babashka/babashka.github.io`): the purest possible bb SSG — `bb generate` runs a single `generate.clj` that reads `data.edn`, builds the page as **hiccup**, and spits `index.html`; `bb watch` for dev. One page, ~25 commits. Demonstrates the floor of the "plain bb" path: hiccup-to-disk is a one-file program.
- **borkdude's blog** (`borkdude/blog`, blog.michielborkent.nl): migrated Octopress → bespoke babashka scripts (2022), then became the reference consumer of **quickblog** — bb.edn defines `new/render/watch/publish` tasks (publish = rsync), plus quickdoc for API docs. 1,200+ commits; actively maintained. This is the canonical "bb-native blog engine driven from bb.edn tasks" pattern clogem-press's CLI should imitate.
- **clojure-lsp docs** (clojure-lsp.io): built with **MkDocs** (`mkdocs.yml` at repo root) — i.e., a flagship Clojure project chose a *Python* docs generator to get sidebar-nav/search/theme polish. Same story as many Clojure projects (others use Cljdoc or asciidoctor). This is direct evidence of the gap clogem-press targets: there is **no Clojure-native equivalent of MkDocs-Material/VuePress-vdoing**.
- **Markdown in bb is now built-in:** babashka bundles `io.github.nextjournal/markdown` (bb 1.12.218 ships 0.7.225; added after issue #1825 "Add markdown lib to bb"), alongside bundled **selmer**, **hiccup**, and **http-kit server**. A markdown→hiccup→HTML pipeline with a dev server now needs *zero* pods and few or no extra deps under bb.

## 8. bootleg (retrogradeorbit/bootleg)

**Description.** A GraalVM native-image CLI ("a powerful, fast, clojure html templating solution") for turning templates into HTML: converts between hiccup/hickory/hiccup-seq/hickory-seq/HTML/XML, and processes mustache, **selmer**, **enlive**, markdown, YAML/JSON/EDN. Also usable as a **babashka pod** (`pod.retrogradeorbit.bootleg`, in the pod registry), exposing markdown/selmer/enlive/glob/utils namespaces inside bb.
- **Maintenance 2026:** last release **v0.1.9, 2020-05-27** (verified via releases atom feed; the HTML page's undated "May 27" is 2020); last push 2022-03-22; 12 open issues. 260 stars. Effectively dormant 6 years.
- **Relevance today:** largely obsoleted for our purpose — everything we'd want from the bootleg pod (selmer, hiccup, markdown, enlive-style HTML munging via hickory) is now either bundled in bb or available as a regular bb-compatible dep. Its enlive/hickory selector-transform idea is still the right tool if we need to post-process rendered HTML (e.g., heading anchors, TOC extraction) — but via the `hickory` library directly, not via bootleg.

**Verdict: dormant; do not build on it. Steal the hickory post-processing pattern.**

---

## Conclusions for clogem-press

**Dead / avoid as foundations:** misaki (archived 2014), incise (2018), Perun (+Boot, both effectively dead since 2019–2020), toto (alpha, idle since Jan 2021), bootleg (dormant since 2020, superseded by bb built-ins), Hardly (8-commit personal project), oz (wrong niche, stalled).

**Viable foundations (only two, plus the DIY path):**
1. **stasis** — stable, tiny, explicitly bb-compatible, "done" software. Zero lock-in risk because there is almost nothing to be locked into.
2. **powerpack** — actively maintained (release 2025.10.22, push Jan 2026), production-proven, and its Datomic content model maps beautifully onto vdoing's category/tag/archive/structured-sidebar features — but it is **JVM-only** (Datomic peer), which conflicts with a babashka CLI unless the CLI shells out to a JVM build.

**The "build from scratch on stasis / plain bb" path** is well-trodden and low-risk in 2026, and looks like this:
- `bb.edn` tasks (`new`, `render`, `watch`, `serve`, `publish`) — the borkdude/blog + quickblog pattern.
- Content pipeline: `stasis/slurp-directory` (or `babashka.fs` glob) → frontmatter parse → **bundled nextjournal/markdown** (markdown→hiccup data, so TOC/anchor/permalink rewriting is a plain tree transform) → hiccup layout functions (bundled) → `stasis/export-pages` into `docs/` for GitHub Pages.
- Dev loop: `stasis/serve-pages` on bb's bundled http-kit + `pod-babashka-fswatcher` (or beholder-on-JVM) for watch; babashka.org and quickblog both demonstrate the watch loop in bb.
- The only genuinely missing pieces vs. vdoing are the *theme-level* features (two-level sidebar from directory structure, category/tag index pages, client-side search index, prev/next links) — none of which any surveyed project provides, and all of which are pure data transforms over the page map that stasis's model makes easy. Search would be a generated JSON index + a small JS (or scittle) client.
- Total foundation risk: stasis is ~1 small namespace (vendorable), everything else is bb built-ins — meaning the "framework" we'd depend on is effectively the babashka distribution itself, the best-maintained artifact in this whole survey (babashka: 4,586 stars, pushed 2026-08-15).

**Recommendation:** treat it as a two-way door — design clogem-press's core as "pure functions producing a stasis-style page map," which runs unmodified on bb (plain-bb path) and could later be re-hosted inside powerpack if JVM features (image pipeline, Datomic queries, Optimus asset fingerprinting) become worth the JVM dependency.

## Sources
- https://github.com/magnars/stasis
- https://github.com/cjohansen/powerpack
- https://github.com/cjohansen/powerblog
- https://github.com/metasoarous/toto
- https://github.com/metasoarous/oz
- https://github.com/hashobject/perun
- https://github.com/hashobject/perun/commits/master
- https://github.com/boot-clj/boot
- https://github.com/kees-/hardly
- https://github.com/liquidz/misaki
- https://github.com/RyanMcG/incise
- https://github.com/retrogradeorbit/bootleg
- https://github.com/retrogradeorbit/bootleg/releases
- https://github.com/retrogradeorbit/bootleg/releases.atom
- https://github.com/babashka/babashka.github.io
- https://raw.githubusercontent.com/babashka/babashka.github.io/master/generate.clj
- https://github.com/borkdude/blog
- https://raw.githubusercontent.com/borkdude/blog/master/bb.edn
- https://github.com/clojure-lsp/clojure-lsp
- https://github.com/babashka/babashka/issues/1825
- https://github.com/babashka/babashka/releases
- https://clojars.org/babashka
- https://github.com/borkdude/quickblog