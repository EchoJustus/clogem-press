# Deep-dive: Eden and Fabricate as candidate foundations for clogem-press

Preliminary note on identity: the "Eden" SSG is **not** `lukebowers/eden` (doesn't exist) or `benzap/eden` (an embedded scripting language). The real project is **`anteoas/eden`** — "Clojure static site generator with EDN-first content, Hiccup templates, and AI-assisted editing via MCP", published by Anteo AS (a Norwegian software company) in August 2025.

---

## 1. Eden (github.com/anteoas/eden)

### Vital statistics (verified against GitHub, 2026-08-16)
- **Created:** 2025-08-19. **Last commit:** 2025-09-22 (author `handerpeder`) — **dormant for ~11 months** as of today.
- **Stars: 2, forks: 0, open issues: 1, contributors: 1.** License: MIT.
- **One release ever:** `v2025.08.19`. Docs inconsistency: `docs/getting-started.md` tells you to install `:git/tag "v1.0.0"`, a tag that doesn't exist — README uses the real `v2025.08.19`. Small thing, but symptomatic of a young single-user project.
- Effectively an internal company tool made public (its stated roadmap item was MCP-based AI editing, "coming soon", never shipped as far as the repo shows).

### Content model (EDN-first)
- Content lives in language-scoped directories: `content/en/...`. Two formats: **pure `.edn` files** and **`.md` with EDN frontmatter** (markdown is converted to HTML at load time via `markdown-clj`).
- Files are indexed by path-derived content keys: `content/en/blog/post-1.md` → `:blog.post-1` (directories become dot-separated namespace segments). Templates address pages by these keys.

### Templates (Hiccup + declarative directives, no arbitrary eval)
Templates are Hiccup data (`.edn` files in `templates/`) processed by directive expansion + multimethod dispatch, with final HTML string generation via **Replicant** (`replicant/render`). Directive set (from `docs/reference.md`):
`:eden/get`, `:eden/get-in` (context access with defaults), `:eden/each` (iteration with filtering/sorting/grouping), `:eden/if`, `:eden/link` (smart cross-page links with automatic URL generation, injects `:link/href`/`:link/title`), `:eden/render` (component + data), `:eden/t` (translation lookup with `{{var}}` interpolation), `:eden/with` (merge data into context), `:eden/body` (wrapper/layout insertion point), `:eden/include`.
The rendering context carries `:data`, `:lang`, `:path`, `:pages`, `:sections`, `:strings`, `:templates`, site config, and `:build-constants`. Notably, **templates are pure data — no embedded Clojure eval**; the only user code is optional `:url-strategy` / `:page-url-strategy` functions, which may be external or **inline fns evaluated via SCI** at build time (SCI 0.10.47 is a dependency).

### Multi-language support
First-class: `site.edn` `:lang {:en {:name "English" :default true} ...}` (URL prefixes per language supported); per-language content dirs; per-language `content/<lang>/strings.edn` translation maps (nested keys, defaults, interpolation); each language gets its own full render pass with `:lang` in context. This is one of the most complete i18n stories of any Clojure SSG.

### Dependency tracking / build model
From `docs/internals.md`: build starts from `:render-roots` (required config), expands templates to find `:eden/link` directives, builds a page link graph, and renders the **transitive closure of pages reachable from the roots** ("only builds pages that are linked"). Smart rebuilds only re-render pages whose content changed. **Important caveat for a knowledge base:** reachability-based rendering is a marketing-site default — orphan notes that nothing links to are silently *not built*. A vdoing-style KB ("every file appears in an auto-generated sidebar/archive") inverts this assumption; you'd have to funnel everything through `:eden/each` index pages.

### Asset pipeline and images
- **esbuild** (shelled out, requires Node) bundles CSS/JS from `assets/css`, `assets/js`; minification in production, sourcemaps in dev; **graceful fallback to plain file copy if esbuild is absent**.
- **Image processing** via `imgscalr-lib 4.2` (Java AWT-based): templates reference images with query params (`?size=800x600`, `?size=800x` keeps aspect ratio); results cached in `.temp/images/`; missing images render labeled placeholders.

### Dev workflow / live reload
`clj -Teden init` scaffolds a site (`site.edn`, `templates/`, `content/en/`, `assets/`, `dist/`) plus npm scripts. `npm run watch` (or `clj -Teden watch`) = file watching (their own `hawkeye` lib) + dev server (**ring-jetty**) + browser sync/hot reload; `build`, `serve`, `clean` also provided. Warnings collected without failing in production mode; dev mode renders inline error placeholders.

### Babashka compatibility
**None, and not plausible without a substantial port.** Zero mentions of bb in README/docs; no `bb.edn`. Distributed as a Clojure CLI Tool (`clj -Ttools install` / `clj -Teden ...`) with JVM flag `--enable-native-access=ALL-UNNAMED`. Hard JVM dependencies: `ring-jetty-adapter` (dev server), `imgscalr-lib` (java.awt.image — absent from bb), `nrepl`, `clojure-mcp`. The parts that *would* port well are exactly the interesting parts: the directive interpreter is pure data transformation (no eval), it already depends on `babashka/fs` (as a JVM library) and SCI, markdown-clj works on bb, and Jetty could be swapped for bb's built-in http-kit. But that's a fork, not a dependency.

### Fitness as foundation for a vdoing-like KB + blog
- **Feature overlap is the best of any small Clojure SSG examined:** i18n, layouts/wrappers, md+frontmatter, keyed cross-page links, collection iteration with sort/group (enough to build blog indexes and sidebars), asset pipeline, image resizing, live reload, incremental builds.
- **Missing everything vdoing-specific:** no tags/categories/archive taxonomy pages, no auto-generated structured sidebar from directory numbering, no permalink scheme, no search index generation, no theme. All of that would be built on top regardless.
- **Structural mismatch:** reachability-from-roots rendering vs. KB "publish everything, auto-index everything".
- **Risk of building on it: HIGH.** Bus factor 1, 2 stars, no community, dormant since 2025-09, single release, JVM-only (conflicts with the clogem-press bb CLI goal). Adopting Eden = forking Eden.
- **Recommended role: design reference, not dependency.** Its declarative `:eden/*` directive vocabulary, content-key addressing, i18n strings model, and link-graph incremental build are excellent, directly-stealable designs — and because templates are data (not eval), the design is bb-portable even though the implementation isn't.

---

## 2. Fabricate (github.com/fabricate-site/fabricate)

### Vital statistics (verified 2026-08-16)
- **Stars: 64, forks: 2, watchers: 4, open issues: 11, open PRs: 0.** License detected as **EPL-1.0**.
- **Actively maintained by exactly one person** (`respatialized`): last commit **2026-04-19** (schema-simplification and eval-fix work, some commits marked `[WIP]`); tags: `2022.07.04`, `2022.10.10`, `2026.04.13` — i.e., a 3.5-year gap between releases, then a spring-2026 burst. ~5 years of slow solo development; pre-1.0 calver.
- Stability contract is explicit: **`site.fabricate.api` is declared stable**; anything under `site.fabricate.prototype.*` is experimental and subject to change. One of its own deps (`site.fabricate/adorn`, code-display) is a git-tag alpha of the author's other library.

### Evaluation model — Clojure *is* the template language
Fabricate's premise: "the power to evaluate Clojure code to generate the contents of a page." Two source types today: **native `.clj` files** and **fabricate templates** — prose files with embedded Clojure delimited by Unicode glyphs **`✳` ... `🔚`** (parsed with instaparse), with modifiers:
- `✳ (expr) 🔚` — run for side effects (ns decls, defs), no output
- `✳= (expr) 🔚` — evaluate and **insert result** (Hiccup results merge into the page tree)
- `✳+ ...🔚` — show the code, not the result; `✳+=` — show code *and* result (literate-programming style).
Expressions can appear anywhere in prose (inline, not just blocks). Evaluation is **real JVM `clojure.core/eval`** (no SCI in the dependency tree); results can flow through the scicloj **Kindly** protocol (`eval-form` / `render-form` respect `:kindly/hide-code` / `:kindly/hide-value`), aligning it with the Clerk/Kindly notebook ecosystem.

### Page model and pipeline (the architecturally interesting part)
Stable API = 3 functions over a site map (`:site.fabricate.api/entries`, `:site.fabricate.api/options`):
1. `plan!` — run setup tasks, then `collect` entries from each source;
2. `assemble` — call `build` on each entry (source → document), then run tasks;
3. `construct!` — call `produce!` on every page (document → output artifact), then run tasks.
An **entry** is a namespaced-key map tracking a page through the pipeline: required `:site.fabricate.api/source`, `:site.fabricate.source/location`, `:site.fabricate.source/format`; optional `:site.fabricate.page/title`, `/author`, `/published-time`, `/uri`, `/permalink`, etc. Extensibility is via three multimethods: `collect` (dispatch on source), `build` (dispatch on `[source-format document-format]`), `produce!` (dispatch on `[document-format page-format]`), with pass-through defaults — a genuinely clean, data-first extension seam for adding new markup formats.

### Extensibility to new formats — in theory yes, in practice DIY
The multimethod design exists precisely so users can add formats, **but Markdown support is still only "planned"** (README; no markdown library in `deps.edn`), as is Kindly-notebook input. For a docs/blog generator whose authors write Markdown, the flagship input format would be yours to implement.

### What Fabricate does NOT provide
No dev server, file watcher, or live reload in the stable API (deps.edn has no HTTP/watch dependency; the docs' how-to guides for "server syncing" and "deployment" are marked "🏗️ To be published"). No theme system, no navigation/sidebar/taxonomy primitives, no asset pipeline, no image handling, no i18n. Dependencies: hiccup 2.0.0-RC2, instaparse, malli 0.17, rewrite-clj, data.finger-tree, kindly, babashka/fs, adorn.

### Docs quality
`fabricate.site` follows a Diátaxis-style structure (tutorials / how-tos / reference / background / auto-generated namespace docs) and the API reference with malli schemas is unusually rigorous for a 64-star project — but several tutorials and most how-to guides are unpublished placeholders. Good reference, thin on-ramp.

### Babashka compatibility
**Not compatible and philosophically misaligned.** No bb.edn, no bb claim. Blockers: `instaparse` (JVM; bb needs the separate `babashka/instaparse.bb` fork), `data.finger-tree` (deftype-heavy), JVM `eval` at the core of the model, alpha `adorn`. Malli, hiccup, rewrite-clj, babashka/fs would be fine, but the eval-anything-in-prose model fundamentally assumes a full Clojure runtime (bb's SCI could evaluate a subset, but that's a reimplementation, not reuse).

### Fitness as foundation for a vdoing-like KB + blog
- Its authoring model is the **inverse of vdoing's**: vdoing users write plain Markdown with YAML frontmatter and get structure for free; Fabricate users write Clojure-in-prose. For a knowledge base with many small notes, `✳`/`🔚` templates are the wrong ergonomic.
- What it would actually contribute — the `plan!/assemble/construct!` orchestration and entry map — is maybe 10% of an SSG and the easiest 10% to write; the missing 90% (markdown, themes, nav/taxonomy, search, i18n, watch/reload, assets) is exactly what clogem-press needs.
- **Risk: moderate-to-high.** Solo maintainer (though active in 2026 and disciplined about API stability), 64 stars, pre-1.0, WIP-flagged mainline commits, alpha sub-dependency, EPL-1.0.
- **Recommended role: architecture reference.** The entry map (namespaced keys carrying a page from source → document → product) and the `[source-format document-format]` multimethod dispatch are patterns worth adopting in clogem-press's own pipeline; the project itself should not be a dependency.

---

## Bottom line for the design document
Neither project is a viable *foundation* for a babashka-first vdoing replica: **Eden** is feature-adjacent but dormant, adoption-free (2 stars), JVM-bound (Jetty, imgscalr, Clojure Tools packaging), and built around link-reachability rendering that inverts KB assumptions; **Fabricate** is alive and architecturally elegant but is an eval-centric library that lacks Markdown, themes, navigation, i18n, and live reload, and cannot run on bb. Both are first-rate *design references*: steal Eden's declarative `:eden/*` directive vocabulary, content-key addressing, i18n strings model, and link-graph incremental build (all data-oriented and bb-portable); steal Fabricate's entry/site data model and multimethod pipeline seams. Building clogem-press's core in-house on bb-compatible libraries, informed by both designs, carries less risk than forking either 1-contributor project.

## Sources
- https://github.com/anteoas/eden
- https://raw.githubusercontent.com/anteoas/eden/main/README.md
- https://github.com/anteoas/eden/commits/main
- https://raw.githubusercontent.com/anteoas/eden/main/deps.edn
- https://raw.githubusercontent.com/anteoas/eden/main/docs/internals.md
- https://raw.githubusercontent.com/anteoas/eden/main/docs/reference.md
- https://raw.githubusercontent.com/anteoas/eden/main/docs/getting-started.md
- https://github.com/anteoas/eden/tree/main/docs
- https://github.com/fabricate-site/fabricate
- https://raw.githubusercontent.com/fabricate-site/fabricate/main/README.md
- https://raw.githubusercontent.com/fabricate-site/fabricate/main/deps.edn
- https://github.com/fabricate-site/fabricate/commits/main
- https://github.com/fabricate-site/fabricate/tags
- https://github.com/fabricate-site/fabricate/tree/main/src/site/fabricate
- https://raw.githubusercontent.com/fabricate-site/fabricate/main/src/site/fabricate/api.clj
- https://fabricate.site/
- https://fabricate.site/reference/template-structure.html