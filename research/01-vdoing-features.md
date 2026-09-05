# vuepress-theme-vdoing — Feature Inventory (verified Aug 2026)

Repo: https://github.com/xugaoyi/vuepress-theme-vdoing (~4.9k stars, ~1.2k forks, MIT, 601 commits on master). Docs: https://doc.xugaoyi.com/. npm package `vuepress-theme-vdoing`, latest **v1.12.9**.

## 1. Core philosophy

Self-described as "a useful knowledge-management tool for programmers" built on three knowledge forms (三种知识管理形态):

1. **Structured (结构化)** — auto-generated structured sidebar, catalogue (directory) pages, and breadcrumb navigation turn a folder tree of Markdown into a book-like knowledge base ("让你的知识海洋像书一样清晰易读").
2. **Fragmented (碎片化)** — a blog layer for one-off posts: `_posts` folder, post lists, categories/tags/archive, comments, recent-updates bar.
3. **Systematic (体系化)** — multi-dimensional indexing (category page, tag page, archive page, catalogue pages, search) so any knowledge point is reachable several ways.

Guiding principles: Markdown-centric, "convention over configuration" (folder naming drives sidebar/catalogue/breadcrumb generation), automation tools (auto front matter, batch front-matter editor), minimal config.

## 2. Four usage modes (from README, each with an official demo)

1. **Knowledge base + blog (hybrid)** — structured directories plus `_posts` blog; demo: xugaoyi.com.
2. **Blog only** — demo: xugaoyi.github.io/vdoing-demo-blog/.
3. **Knowledge base only (pure repository)** — demo: xugaoyi.github.io/vdoing-demo-repository/.
4. **Documentation site** — demo: doc.xugaoyi.com (the theme's own docs).

Modes are not a switch — they emerge from which conventions you use (whether you have `_posts`, whether homepage uses `postList`, whether category/tag/archive are enabled).

## 3. Directory-structure conventions (the generator's core input contract)

- `docs/` root. Excluded from data generation: `.vuepress`, `@pages`, `_posts`, `index.md`/`README.md`.
- **Numeric prefixes required** for ordering: `01.folder/`, `02.file.md` (positive integer + dot). Gaps (10, 20, 30) recommended for later insertion. Level 1 prefix optional; levels 2–3 required, folders and .md files can mix; level 4 (since v1.6.0) is .md files only.
- `themeConfig.sidebar: 'structuring'` (or `{ mode: 'structuring', collapsable: Boolean }`) activates auto sidebar generation from this tree; dev build prints "tip: add sidebar data. 侧边栏数据添加成功。" on success, yellow warnings for badly numbered files.
- `_posts/` = fragmented blog posts (no numbering needed, sorted by date, default category from `categoryText`).
- `@pages/` = auto-created index pages: `archivesPage.md`, `categoriesPage.md`, `tagsPage.md`.

## 4. Auto-generated front matter

On `npm run dev`/`build` the theme writes missing fields into each .md:
- `title` — from filename (prefix stripped)
- `date` — file creation time, `YYYY-MM-DD HH:MM:SS`
- `permalink` — `/pages/` + 6-char random alphanumeric (e.g. `/pages/d8cae9`) so URLs survive file moves/renames
- `categories` — derived from parent folder names (a 3rd-level file gets its level-2 and level-1 folder names); `_posts` files get `categoryText` default (默认 `随笔`), nested `_posts` use parent folder name
- `tags` — empty array placeholder
- `themeConfig.extendFrontmatter` injects arbitrary extra fields (e.g. default `author`) into every file missing them
- Manually set values are never overwritten. Disable via `category: false` / `tag: false`.

Full front-matter vocabulary: `title`, `date`, `permalink`, `categories`, `tags`, `titleTag` (badge next to title in lists, e.g. 原创/转载/推荐), `sticky: 1|2|3…` (pin to top of homepage list, ranked), `article: false` (exclude page from archive/recent-updates/lists), `comment: false`, `editLink: false`, `sidebar: false|auto`, `author: {name, link}` or string, `home: true` (homepage), `pageComponent` (catalogue pages), `archivesPage: true` / `categoriesPage: true` / `tagsPage: true` (index pages).

## 5. themeConfig options (complete)

**Classification system**
- `category: Boolean = true` — enable category system + category index page
- `tag: Boolean = true` — enable tags + tag index page
- `archive: Boolean = true` — enable archive page
- `categoryText: String = '随笔'` — default category for `_posts`

**Appearance / layout**
- `pageStyle: 'card' | 'line' = 'card'` — card-style vs line-style page/list rendering (v1.12.0+)
- `defaultMode: 'auto' | 'light' | 'dark' | 'read' = 'auto'` — initial color mode; four modes: follow-system, light, dark, and **read mode** (immersive reading); only applies until user manually picks a mode (persisted via `good-storage`)
- `bodyBgImg: String | Array` — one URL or several (rotating) full-body background images
- `bodyBgImgOpacity: Number = 0.5` — 0–1
- `bodyBgImgInterval: Number = 15` — rotation seconds (v1.12.0+)
- `contentBgStyle: 1..6` — content-area background patterns (grid, lines, diagonals, dots)
- `titleBadge: Boolean = true` — animated icon before article titles; `titleBadgeIcons: Array` for custom icon URLs
- `rightMenuBar: Boolean = true` — right-hand article outline (TOC) bar
- `sidebarOpen: Boolean = true` — initial open/closed state of left sidebar
- `pageButton: Boolean = true` — prev/next article arrow buttons
- `sidebar: 'structuring' | {mode:'structuring', collapsable} | 'auto' | false`
- homepage front matter extras: `bannerBg` (`auto`|`none`|image URL|`background: <css>`), `features` cards (`title/details/link/imgUrl`), `hideRightBar` (v1.11.2+)

**Blog identity**
- `author: {name, link} | String` — global default author
- `blogger: {avatar, name, slogan}` — blogger profile card shown in homepage sidebar (BloggerBar.vue)
- `social: {iconfontCssFile?, icons: [{iconClass, title, link}]}` — social icons (built-in icon classes for GitHub, Gitee, WeChat, Weibo, Zhihu, email, music, ~15+ more; custom via iconfont CSS)
- `footer: {createYear, copyrightInfo}` — copyright line (supports HTML, e.g. ICP/备案 links)
- `updateBar: {showToArticle: Boolean = true, moreArticle: String = '/archives/'}` — "recent updates" bar (UpdateArticle.vue)

**Extension slots**
- `htmlModules: {homeSidebarB, sidebarT, sidebarB, pageT, pageB, pageTshowMode: 'article'|'custom', pageBshowMode, windowLB, windowRB}` — raw HTML injected into 9 named regions (homepage sidebar bottom, all sidebars top/bottom, page top/bottom with article-only vs all-pages display modes, fixed window lower-left/lower-right). Window slots max 200×400px, hidden under 960px viewport width. Used for ads, notices, widgets.

**Inherited default-theme options still used**: `nav` (with dropdowns), `logo`, `repo`, `docsDir`, `editLinks`, `editLinkText`, `lastUpdated`, `sidebarDepth`, `searchMaxSuggestions`, `algolia`.

## 6. Blog features

- **Homepage post list** — `postList: 'detailed' | 'simple' | 'none'` in homepage front matter. `detailed` = full cards (title, titleTag badge, excerpt, author, date, categories, tags) with **pagination** (Pagination.vue); `simple` = title+date list, capped by `simplePostListLength` (default 10, no pagination); `none` = docs-style homepage.
- **Sticky posts** — `sticky: N` front matter pins with rank order.
- **Recent updates** — updateBar on homepage listing recently updated articles, "more" links to `/archives/`.
- **Category page** (`/categories/`, `categoriesPage: true` front matter, CategoriesPage.vue + CategoriesBar.vue) — category list with counts + filtered paginated article list.
- **Tag page** (`/tags/`, TagsPage.vue + TagsBar.vue) — tag cloud/bar + filtered paginated list.
- **Archive page** (`/archives/`, front matter verbatim: `archivesPage: true / title: 归档 / permalink: /archives/ / article: false`, ArchivesPage.vue) — all articles grouped chronologically by year/month.
- **Catalogue pages** — front matter `pageComponent: {name: Catalogue, data: {path: '01.学习笔记/01.前端', imgUrl, description}}`; renders a card-grid directory page for one folder subtree (requires structured sidebar data); typically combined with `article: false, comment: false, sidebar: false`.
- **Breadcrumbs**, ArticleInfo (author/date/category/tag line on each article).

## 7. Search, comments, code blocks, TOC, permalinks

**Search**
- Built-in: theme bundles `@vuepress/plugin-search` (title-based suggestion search, `searchMaxSuggestions`).
- Algolia DocSearch: first-class — theme ships `AlgoliaSearchBox.vue` and depends on `docsearch.js ^2.5.2`; configured via `themeConfig.algolia`.
- Community full-text: `vuepress-plugin-fulltext-search` (docs-recommended) for offline full-text search.
- `vuepress-plugin-thirdparty-search` — augments the search dropdown with external engines (Baidu, Bing, MDN, Runoob, Vue API…); used on the official site.

**Comments** — not built into the theme; integrated via plugins, toggled per page with `comment: false` front matter. Officially recommended (docs "评论栏" page): `vuepress-plugin-comment` (choose `gitalk` or `valine`), `vuepress-plugin-vssue` / `vuepress-plugin-vssue-global` (Vssue: GitHub/GitLab/Gitee/Bitbucket issues), and Twikoo. Community plugins extend to Waline/Artalk: `vuepress-plugin-comment-plus` (Waline recommended, Gitalk, Valine) and `terwer/vuepress-plugin-vdoing-comment` (Gitalk, Valine, Artalk). The official site itself uses `vuepress-plugin-comment` with Gitalk (issues stored in xugaoyi/blog-gitalk-comment).

**Code blocks** — VuePress 1.x stack: Prism-based syntax highlighting; line numbers via `markdown: { lineNumbers: true }` in config.js; copy button via `vuepress-plugin-one-click-copy`; live demos via `vuepress-plugin-demo-block`; image zoom via `vuepress-plugin-zooming`; language label/theme styling from the theme's stylus.

**TOC** — right-side outline bar (RightMenu.vue, `rightMenuBar` option) generated from headings with active-heading highlight (`@vuepress/plugin-active-header-links`), smooth scroll (`vuepress-plugin-smooth-scroll`); `[[toc]]` inline Markdown TOC from VuePress core; heading depth via `sidebarDepth`.

**Permalinks** — per-page `permalink` front matter, auto-generated as `/pages/<6-char-hash>`; decouples URLs from file paths (safe renames/moves). Index pages use fixed `/archives/`, `/categories/`, `/tags/`.

**Other bundled/recommended plugins**: sitemap (`vuepress-plugin-sitemap`), Baidu SEO push (`vuepress-plugin-baidu-autopush`, `baidu-tongji` analytics, one-click Baidu URL push npm script), `vuepress-plugin-flowchart`, `vuepress-plugin-mathjax`, `vuepress-plugin-tabs`, nprogress + container plugins (theme deps). Theme also ships Markdown containers (tip/warning/danger/detail/theorem + right/center align) and Vue-components-in-Markdown (Badge etc.).

**Tooling** — npm scripts in the template: `dev`, `build`, `editFm` (batch front-matter editor utility), `baiduPush`; deploy via `deploy.sh` to GitHub Pages.

## 8. Maintenance status (as of Aug 2026)

- **VuePress 1.x based** (webpack 4 era). Requires `NODE_OPTIONS=--openssl-legacy-provider` on modern Node; docs now say Node >= 18 with that flag. Theme deps: `@vuepress/plugin-search ^1.2.0`, stylus, docsearch.js 2.x, better-scroll 2.0 beta.
- **Last feature release: v1.12.9 (Aug 2023)** — Node 18 compatibility + style fixes. Release cadence before that: v1.12.x through 2022–2023 (pageStyle, defaultMode, bodyBgImgInterval, Gitee edit links, auto-category rules).
- **Last commit: June 12, 2025**, but 2024–2025 commits are content-only (friend links, articles, ICP filing) — no core development since 2023. ~87 open issues. No deprecation notice, but effectively **maintenance/frozen mode**; no official VuePress 2 port exists (community discussion only). VuePress 1.x itself is end-of-life upstream, which is a key motivation for a from-scratch reimplementation like clogem-press: replicate the conventions (numbered-folder structuring, auto front matter, /pages/hash permalinks, @pages index pages, four usage modes) without the dead Vue 2/webpack 4 runtime.

## Key implications for clogem-press

The feature surface to replicate decomposes into: (a) a **content pipeline** (folder-convention scanner, front-matter auto-writer with stable random permalinks, category/tag/archive/catalogue index generation); (b) a **theme layer** (card/line page styles, 4 color modes incl. read mode, blogger card, social icons, footer, htmlModules-style HTML injection slots, bodyBgImg); (c) **blog machinery** (paginated detailed/simple post lists, sticky, updateBar); (d) **pluggable client-side features** (search: title-index vs full-text vs Algolia; comments: Gitalk/Valine/Waline/Twikoo/Artalk/Giscus embeds are all plain JS snippets easily injected into static HTML; code copy buttons; right-side TOC with scroll-spy).

## Sources
- https://github.com/xugaoyi/vuepress-theme-vdoing
- https://doc.xugaoyi.com/
- https://doc.xugaoyi.com/pages/a2f161/
- https://doc.xugaoyi.com/pages/a20ce8/
- https://doc.xugaoyi.com/pages/33d574/
- https://doc.xugaoyi.com/pages/088c16/
- https://doc.xugaoyi.com/pages/3216b0/
- https://doc.xugaoyi.com/pages/54651a/
- https://doc.xugaoyi.com/pages/f14bdb/
- https://doc.xugaoyi.com/pages/ce175c/
- https://doc.xugaoyi.com/pages/db78e2/
- https://doc.xugaoyi.com/pages/793dcb/
- https://github.com/xugaoyi/vuepress-theme-vdoing/commits/master
- https://github.com/xugaoyi/vuepress-theme-vdoing/releases
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/docs/@pages/archivesPage.md
- https://github.com/xugaoyi/vuepress-theme-vdoing/tree/master/vdoing/components
- https://raw.githubusercontent.com/xugaoyi/vuepress-theme-vdoing/master/docs/.vuepress/config.ts
- https://registry.npmjs.org/vuepress-theme-vdoing/latest
- https://github.com/SivanLaai/vuepress-plugin-comment-plus
- https://github.com/terwer/vuepress-plugin-vdoing-comment
- https://github.com/xugaoyi/blog-gitalk-comment