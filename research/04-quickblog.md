# Deep-dive: quickblog (borkdude/quickblog)

Verified against the repo cloned at depth 50 on 2026-08-16 (local clone: `/tmp/claude-0/-home-user/4a670205-d376-5fec-8b1f-1c5c54b2eaf6/scratchpad/quickblog`). All source claims below were read from actual source files, not memory.

## 1. What it is, size, status

- "Light-weight static **blog** engine for Clojure and babashka" — MIT license, by Michiel Borkent (borkdude), with Josh Glover (jmglov) as the most active co-maintainer.
- **Small codebase**: `src/quickblog/api.clj` (727 lines), `src/quickblog/internal.clj` (564 lines), `src/quickblog/cli.clj` (145 lines), `src/quickblog/internal/frontmatter.clj` (~95 lines). Plus 8 default HTML/CSS templates and favicon assets under `resources/quickblog/`. Test suite: 718-line `test/quickblog/api_test.clj` with CI.
- **Community**: 218 stars, 37 forks, ~19 blogs listed in the README's "Blogs using quickblog" section (including borkdude's own blog).

### Maintenance status (2026)
Actively maintained but low-velocity. Recent commits on `main`:
- 2026-08-10: feed `<subtitle>` from `:blog-description` (#122) — latest commit
- 2026-03-17: `page-type` template variable (#121)
- 2026-03-14: fix `:serve false` opt to `api/watch` (#119)
- 2025-12-25: bump http-server to 0.1.14
- 2025-11-29: alternate content root support for `api/watch` (#111)

**Latest tagged release: v0.4.7 (2025-06-12)** — its headline change: switched markdown rendering from markdown-clj to **Nextjournal Markdown**, raising the minimum babashka to **v1.12.201** (which bundles nextjournal/markdown). There are meaningful unreleased changes on main since v0.4.7 (page-type var, alternate content root, feed subtitle fix, `:blog-image-alt` CLI fix) — consumers typically depend on it via `:git/sha`, so the "release" concept is soft.

## 2. Architecture

Single-pass, options-map-driven pipeline. There is no site model or plugin system; one big `opts` map flows through everything and is progressively enriched.

- `quickblog.api/render` is the whole engine: `opts -> apply-default-opts -> lib/refresh-cache -> (gen-posts, gen-tags, spit-archive, spit-about, spit-index, spit-feeds) -> lib/write-cache!` and returns the enriched opts.
- `lib/refresh-cache` (internal.clj) is the heart: loads `cache.edn` (previous run's post metadata), globs and loads posts, computes `:cached-posts`, `:posts`, `:modified-metadata` (via `clojure.data/diff`), `:modified-drafts`, `:deleted-posts`, `:modified-posts`, `:modified-tags`, and optionally wires `:prev`/`:next` post links (`assoc-prev-next`, gated by `:link-prev-next-posts`).
- Generated page set is **hardwired**: per-post pages, `index.html` (last `:num-index-posts` with preview-cut via `<!-- end-of-preview -->` marker), `archive.html` (grouped by year), `tags/index.html` + one page per tag (sorted by post count), optional `about.html` (only if an `about.html` template exists), `atom.xml`, and `planetclojure.xml` (a second Atom feed filtered to posts tagged clojure/clojurescript). Also per-post "legacy" redirect pages (meta-refresh HTML at old `/{yyyy}/{mm}/{dd}/{slug}/index.html` URLs when a post has `legacy` metadata).

### Options/config
Defined once as `:org.babashka/cli` **metadata on the `quickblog.api` namespace** (a `:spec` map with `:desc`, `:default`, `:require`, `:group` per option) — this single spec serves as (a) CLI parsing spec, (b) help-text generator, (c) source of programmatic defaults (`apply-default-opts` reads the ns metadata). Clever dual-use pattern worth copying. Option groups: blog-metadata (`:blog-title` `:blog-author` `:blog-description` `:blog-root`), optional metadata (`:about-link` `:discuss-link` `:twitter-handle` `:page-suffix`), post config (`:default-metadata` `:num-index-posts`), input dirs (`:posts-dir` `:templates-dir` `:assets-dir`), output dirs (`:out-dir` default `public`, `:assets-out-dir`, `:tags-dir`), caching (`:force-render` `:cache-dir` `:rendering-system-files`), social (`:blog-image` `:blog-image-alt`), favicon (`:favicon` `:favicon-dir` `:favicon-out-dir`).

### Public API (quickblog.api)
`render` (alias `quickblog`), `new` (scaffold a post; supports `--date`, `--tags`, `--preview`, and a custom Selmer `--template-file` for the scaffold), `clean`, `migrate` (from legacy `posts.edn` to in-file metadata), `refresh-templates`, `serve` (returns a `stop-server!` fn when non-blocking), `watch` (returns watcher handles when `:block false`), `unwatch`. API.md is auto-generated with quickdoc.

## 3. Post/page model

- **Posts only** — there is no generic "page" abstraction. Content = flat directory of `*.md` files: `(fs/glob posts-dir "*.md")` in `load-posts` is **non-recursive**; nested content directories are invisible.
- A post is a plain map: frontmatter keys + `:file` (filename) + `:html` (a **delay** — markdown is parsed lazily only if the post actually needs re-rendering) + optional `:prev`/`:next` filenames. Required metadata: `:date`, `:title` (missing → post skipped with an error map `{:quickblog/error ...}`, not a crash). Recognized optional keys: `:tags` (comma-separated → set), `:preview` (draft: excluded from index/tags/feeds but still rendered), `:description`, `:image`, `:image-alt`, `:twitter-handle` (per-post sharing overrides), `:legacy`.
- **Frontmatter** (`quickblog.internal.frontmatter`) accepts three formats, auto-detected by first line: MultiMarkdown/"wiki" style (`Title: Foo`), YAML (`---` fenced, via clj-commons/clj-yaml), or EDN (line starting `{`). Nice touch for a Clojure SSG: EDN frontmatter is first-class.
- Sort order: descending by date, tiebreak title then filename (deterministic ordering was an explicit 0.3.3 fix).
- Markdown rendering (`internal/markdown->html`): nextjournal.markdown `->hiccup` with custom renderers passing `:html-inline`/`:html-block` through as raw hiccup, then `hiccup2.core/html` → string. A pre/post-process hack (`^RET^` sentinel) works around multiline link titles. Code blocks get `language-xxx` classes; actual syntax highlighting is **client-side Prism.js from a CDN** in the default `base.html`.

## 4. Babashka-first design

- **Runs as a library under bb**, not a binary: users add `io.github.borkdude/quickblog {:git/sha "..."}` to `bb.edn` `:deps` and define tasks that call `(quickblog.cli/dispatch opts)`. The repo's own `bb.edn` is the reference setup: tasks `quickblog`, `new`, `render`, `watch`, `clean`, `migrate`, `refresh-templates`, plus a `publish` task that's just rsync. Also installable via **bbin** (`:bbin/bin {quickblog {:ns-default quickblog.api}}`) and runnable from JVM Clojure via `clj -M:quickblog` (`babashka.cli.exec`).
- **babashka.cli usage** (`quickblog.cli`): builds a `cli/dispatch` table; global opts come from the ns-metadata spec, per-subcommand opts from `:org.babashka/cli` metadata on each fn var (e.g. `api/new`, `api/serve`, `api/watch`). Help text is generated with `cli/format-opts`, grouped by the `:group` key. Errors are converted to `ex-info` with `:babashka/exit 1`. `dispatch` takes a `default-opts` map so bb.edn task config merges under CLI args.
- Only bb-compatible deps: babashka/fs, org.babashka/cli, org.babashka/http-server, selmer, clojure.data.xml, clj-yaml, nextjournal/markdown (bundled in bb ≥ 1.12.201).

### Watch mode / hot reload (api/watch)
- HTTP server: **babashka/http-server** (0.1.14), a simple static file server on port 1888 (default), started non-blocking via `requiring-resolve`.
- File watching: the **org.babashka/fswatcher pod (0.0.7)** loaded at runtime with `babashka.pods/load-pod` (Go fsnotify-based). Three watchers: `posts-dir` (on create/remove/rename/write/chmod of `*.md`, skipping Emacs `.#` backup files, it re-loads just that post, updates an in-memory `posts-cache` atom, and calls `render` again with `:posts`/`:cached-posts` pre-seeded so incremental logic applies), `templates-dir` (any change → drop caches, full re-render), `assets-dir` (copy modified, delete removed).
- Browser reload: **live.js from CDN** (`https://livejs.com/live.js`). In watch mode, `opts` gets `:watch` = a `<script>` tag string; `base.html` includes `{{watch | safe}}`. live.js polls the local server for changed responses and reloads the page — there is no websocket/SSE push; it's the simplest possible mechanism.
- Caching is split per mode: `:cache-dir` (default `.work`) becomes `.work/dev` under watch and `.work/prod` under render, so switching modes doesn't poison the cache (a 0.3.5 fix).

## 5. Caching (their hardest-won subsystem)

Three cooperating mechanisms, all mtime-based (no content hashing):
1. **Per-post pre-template HTML cache**: markdown is rendered once to `<cache-dir>/<file>.md.pre-template.html`; `:html` is a delay that either re-parses (if stale) or slurps the cache. Staleness = post file or any `:rendering-system-files` (default `bb.edn`, `deps.edn`, plus the whole templates dir) newer than the cached file, or `:force-render`.
2. **`cache.edn` metadata snapshot**: post maps minus `:html` written after each render; on the next run `clojure.data/diff` between cached and current metadata yields `:modified-metadata`, which drives conditional regeneration of index/archive/tags/about pages and tag-page deletion for removed tags. Deleted posts are detected as key-set difference and their outputs removed.
3. **mtime comparisons on outputs** (`modified-since`, patterned after babashka's own file util): assets are copied only if newer (`copy-tree-modified`); post pages regenerate if the output is older than post source or rendering-system files.

Changelog is candid: 0.3.4–0.3.6 are all caching fixes, one titled "Fix caching (this is hard)". Their notes are a warning that incremental SSG caching is the bug farm; for clogem-press it's worth deciding early whether to copy this design or do content-hash-based invalidation instead.

## 6. Templating and theme-override story

- **Selmer end to end** (Jinja-like `{{var}}`/`{% if %}`/filters). Hiccup appears only internally (markdown→hiccup→HTML); templates the user touches are all Selmer HTML. A custom Selmer filter `escape-tag` is registered for tag URLs.
- Composition is manual two-level: a post is rendered with `post.html` producing a body string, then wrapped by `base.html` (`render-page`). No template inheritance/blocks; `{% include %}` isn't used.
- **Theme override = copy-on-first-use**: defaults live in the JAR/git-dep at `resources/quickblog/templates/{base,post,index,archive,tags,post-links,favicon}.html` + `style.css`. `ensure-template` copies a default into the user's `:templates-dir` **only if not already present**; from then on the user's copy wins and is never touched. `refresh-templates` deliberately overwrites known template names with the latest defaults (documented as destructive; skips filenames it doesn't recognize as "custom templates"). Any `*.css`/`*.svg` in templates-dir is copied to out-dir.
- Template variables: the whole opts map merged with page vars — `page-type` (`:index` `:post` `:archive` `:tags` `:tag` `:about`, added post-0.4.7), `title`, `body`, `sharing` (description/author/twitter-handle/image/image-alt/url), `relative-path` (crude manual `../` prefix for pages in subdirs — no real URL resolver), `favicon-tags`, `watch`, `page-suffix`, and for posts: date/tags/prev/next/discuss.

## 7. Feature set vs. what a vdoing-class knowledge base needs

**Has:** tags (pages per tag + tag index sorted by count), year-grouped archive, Atom feed + secondary filtered feed (Planet Clojure), drafts (`:preview`), Open Graph/Twitter social meta (blog- and post-level image/description overrides), full favicon asset set + `favicon.html` partial, prev/next post links (opt-in), legacy-URL redirects, `:page-suffix` for extensionless links, about page, watch/serve with hot reload, incremental rendering, "blog inside a larger website" (alternate content root, unreleased), post scaffolding with custom templates.

**Lacks (all core vdoing features):**
- **No nested content tree**: non-recursive `*.md` glob over one flat posts dir; no notion of sections/collections.
- **No sidebar/navigation**: no auto-generated (or any) hierarchical sidebar, no per-directory nav config, no ordering metadata (vdoing's numbered-directory convention has no counterpart).
- **No categories** (vdoing separates categories from tags; quickblog has only tags — `migrate` even folds legacy `categories` into tags).
- **No search** — no built-in client-side index, no lunr/flexsearch equivalent, nothing.
- **No per-page TOC**, no heading anchor/permalink generation.
- **No structured "docs mode"**, no permalink system, no breadcrumbs.
- **No plugin/hook system**; page types are hardcoded in `api/render`.
- Also: build-time syntax highlighting absent (CDN Prism at runtime), no sitemap.xml, no dark mode in default theme, no GitHub Pages deployment story in the README (users bring their own `publish` task; GH Pages from `docs/` is just "set `:out-dir docs`" + a `.nojekyll` you add yourself).

## 8. Honest assessment: fork/extend vs. design reference

**Recommendation: use quickblog as a design reference (and dependency-list donor), not a fork base.** Reasoning:

Against forking:
- The gap to vdoing is the *content model itself*, not missing features around the edges. quickblog's core assumption — one flat directory of dated posts feeding a fixed set of page types — is baked into `load-posts`, `refresh-cache`, `modified-*`, and `render`. A knowledge base needs a recursive content tree, nav/sidebar derivation, categories, TOC, and search; retrofitting that means rewriting `internal.clj` (the majority of the code) while inheriting its blog-shaped cache-diff logic. You'd keep maybe the CLI ns and templates dir handling.
- The cache system is mtime-diff based and famously fragile even at blog scale ("Fix caching (this is hard)"); extending it to invalidate sidebars/TOC/search indexes when any file in a tree moves would multiply that fragility.
- Upstream is a personal-blog tool with deliberate scope; a knowledge-base fork would diverge immediately and get nothing from upstream merges.

What to steal as design patterns (all verified working under bb):
1. **The ns-metadata `:org.babashka/cli` spec pattern** — one spec = CLI parsing + grouped help text + programmatic defaults; `cli/dispatch` table with per-var subcommand specs; `:babashka/exit` error handling; the same code serving bb tasks, bbin, and `clj -M`.
2. **The bb runtime stack**: babashka/http-server for serve, org.babashka/fswatcher pod for watching, live.js (or an SSE upgrade) for reload, selmer + nextjournal/markdown (built into bb ≥1.12.201, so zero markdown dep), clj-yaml + EDN frontmatter auto-detection (frontmatter.clj is ~95 lines and directly liftable).
3. **Copy-on-first-use template override** (`ensure-template`/`refresh-templates`) — a simple, user-friendly theme-override story that fits clogem-press.
4. **Lazy `:html` delays** so unchanged pages never pay markdown-parse cost.
5. Their caching war stories as a cautionary tale: for a knowledge base with cross-page artifacts (sidebar, search index, backlinks), prefer either always-full-render (bb + nextjournal/markdown is fast enough for hundreds of pages) or content-hash invalidation with an explicit dependency graph, rather than quickblog-style mtime diffing.

If clogem-press were only a blog, depending on quickblog as a library and overriding templates would be the right call. For replicating vdoing, build from scratch on the same stack.


## Sources
- https://github.com/borkdude/quickblog
- https://github.com/borkdude/quickblog/releases
- https://raw.githubusercontent.com/borkdude/quickblog/main/CHANGELOG.md
- https://github.com/borkdude/quickblog (git clone, commit 0637fab 2026-08-10: src/quickblog/api.clj, src/quickblog/internal.clj, src/quickblog/cli.clj, src/quickblog/internal/frontmatter.clj, bb.edn, deps.edn, resources/quickblog/templates/)