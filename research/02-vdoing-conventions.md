# vuepress-theme-vdoing: Content Conventions & Automation (verified against source)

Everything below is verified against the actual theme source at `github.com/xugaoyi/vuepress-theme-vdoing` (cloned locally to `/workspace/xugaoyi/vuepress-theme-vdoing`, master @ `d77b420`, last commit **2025-06-12**, theme npm version **1.12.9**, ~4.9k stars) and the official docs at doc.xugaoyi.com. The theme targets **VuePress 1.9.9** (Vue 2 era; requires `NODE_OPTIONS=--openssl-legacy-provider` on modern Node — a sign of its legacy stack and a good argument for the clogem-press rewrite).

The theme is a VuePress 1.x "theme as plugin": its entry `vdoing/index.js` receives `(options, ctx)` where `ctx = {sourceDir, themeConfig, siteConfig}`, and at **build startup** (before VuePress compiles pages) it: (1) runs `setFrontmatter(sourceDir, themeConfig)` which **mutates the user's .md source files on disk**, (2) if `themeConfig.sidebar === 'structuring'` (or `{mode:'structuring', collapsable}`), replaces `themeConfig.sidebar` with generated sidebar data, (3) creates/deletes `docs/@pages/{categoriesPage,tagsPage,archivesPage}.md`, and (4) registers markdown-container plugins. This "mutate sources at build time" behavior is a core design decision to replicate (or deliberately improve on) in clogem-press.

---

## 1. Numbered directory/file naming convention (`docs/`)

Format: `<number>.<name>` for directories, `<number>.<name>.md` for files. Examples from the live repo: `docs/01.前端/25.JavaScript文章/01.33个非常实用的JavaScript一行代码.md`, `docs/00.目录页/01.前端.md`.

### Rules (from docs page /pages/33d574/ + source `getSidebarData.js`)

- **Level 1 (top-level dirs under docs/)**: number prefix **optional** (e.g. `docs/《ES6 教程》笔记/` works). Top-level dirs are only used as sidebar *groups keys*; their order among each other is filesystem order (they become separate sidebar configs, one per top dir, so relative order of top dirs doesn't matter).
- **Levels 2 and 3**: number prefix **required**; folders and `.md` files may be freely mixed at the same level and share one number sequence.
- **Level 4**: files only (no further folders) — i.e. max nesting depth is 4 levels under docs/ (dir/dir/dir/file). (Docs rule; the code itself recurses without a hard limit, but the Catalogue component and sidebar UI only render 3 group levels.)
- Numbers do **not** need to be consecutive — `10, 20, 25, 99` is fine and is the recommended practice to leave insertion gaps.
- **Excluded from sidebar scanning**: `.vuepress/`, `@pages/`, `_posts/` (posts get no structured sidebar), `docs/index.md` / files directly under `docs/` root, and `.DS_Store`. Non-`.md` files produce a warning and are skipped.

### Exact filename parsing algorithm (`vdoing/node_utils/getSidebarData.js`)

For each entry `filename` in a scanned dir:
1. Split on `.`: if exactly 2 segments (`01.文件夹名` for a dir won't hit this since dirs with number have 2 segments; for files `name.md` with no number also has 2 segments) → `order = seg[0]`, `title = seg[1]`.
2. Otherwise (≥3 segments, i.e. numbered file or names containing dots): `order = substring before FIRST '.'`; for directories `title = everything after first '.'`; for files `title = between first '.' and LAST '.'`, `type = after last '.'` (must be `md`).
3. `order = parseInt(order, 10)`; if `NaN` or `< 0` → yellow warning `"序号出错"` and the entry is **skipped entirely** (this is why level-2+ numbering is "required").
4. `sidebar[order] = item` — the number is used **as a sparse-array index**, then `filter(item => item != null)` compacts it. Consequences: ordering is purely numeric ascending; duplicate numbers at the same level **overwrite** each other (with warning `"序号...重复出现，将会被覆盖"`); leading zeros are irrelevant (`01` == `1`).

### Name → sidebar title mapping

- Directory: sidebar group title = name after the number prefix (e.g. `25.JavaScript文章` → `JavaScript文章`). Groups become `{ title, collapsable, children }` (collapsable from `themeConfig.sidebar.collapsable`, default `true`).
- File: title defaults to filename-without-number-and-extension, **but is overridden by front matter `title`** if present (the generator reads each file's front matter with gray-matter).
- File items are emitted as a 3-or-4-tuple **array**: `[<relPath>, <title>, <permalink>, <titleTag?>]` where `relPath` is the path relative to the top-level dir including number prefixes (e.g. `10.基础/01.xxx.md`), `permalink` is the front matter permalink (or `''`), and `titleTag` (optional 4th) is the badge string. This tuple format is consumed by the theme's Sidebar and Catalogue components.
- The generated structure is keyed per top-level dir: `sidebarData['/<topDirName>/'] = [...]` — e.g. key `/01.前端/`. A special key `sidebarData.catalogue` maps `{<dirTitle>: <cataloguePermalink>}` collected from any page whose `pageComponent.name === 'Catalogue'` (used for breadcrumb links).
- Console feedback: green `tip: add sidebar data. 成功生成侧边栏数据。` on success; if a top dir yields an empty sidebar it's skipped with a warning; if generation wholly fails, sidebar falls back to `'auto'`.

### Enabling

```js
themeConfig: { sidebar: 'structuring' }               // or
themeConfig: { sidebar: { mode: 'structuring', collapsable: false } }
```
Catalogue pages **depend on** structuring mode (they read the generated sidebar data at runtime).

---

## 2. Front matter conventions — complete recognized-key inventory

Verified by grepping every `frontmatter.*` access in the theme's Vue components/mixins/utils.

### Auto-generated (by setFrontmatter, only when missing — never overwrites)
| Key | Semantics |
|---|---|
| `title` | Defaults to filename without number/extension |
| `date` | `YYYY-MM-DD HH:mm:ss`, from file birthtime (falls back to atime when birthtime year is 1970, i.e. FS doesn't support it) |
| `permalink` | `/pages/<6 hex chars>/` random (see §3) |
| `categories` | Array derived from directory path (see §3) |
| `tags` | `['']` (empty placeholder) |
| `sidebar: auto` | Added automatically **only** for files under `_posts/` |

### Article/page control keys (manual)
| Key | Type/values | Semantics |
|---|---|---|
| `article` | boolean | `false` = page is not an article: excluded from post lists/archive/category/tag indexes and "recent updates"; breadcrumb/metadata suppressed; setFrontmatter also skips adding categories/tags to it. Used for about/nav/catalogue pages. |
| `sticky` | number | Pin article to top of homepage post list; number = pin priority (sorted ascending: 1 before 2). Pinned items get a "置顶" icon. Sort logic: sticky items first (by sticky value, ties by date), then the rest by date desc. |
| `titleTag` | string | Badge rendered next to title in lists/sidebar/catalogue (e.g. `原创`, `转载`). Propagated into the sidebar tuple as 4th element. |
| `comment` | boolean | `false` hides the comment component on that page |
| `editLink` | boolean | `false` hides "edit this page" link |
| `author` | string \| `{name, link?}` | Per-page author, overrides `themeConfig.author` |
| `sidebar` | `false` \| `'auto'` | `false` hides sidebar; `'auto'` builds sidebar from the page's own headers (VuePress behavior; default for `_posts`) |
| `sidebarDepth` | 0–2 | Header depth in sidebar |
| `navbar` | boolean | `false` hides navbar |
| `pageClass` | string | Custom CSS class on the page |
| `prev` / `next` | path \| `false` | Override prev/next page links |
| `search` | boolean | `false` excludes page from built-in search suggestions |
| `keys` | array of strings | NOT a theme key — belongs to `vuepress-plugin-fulltext-search` (page-level search keywords); document as plugin-level, optional |
| `pageComponent` | object | Renders a named component instead of markdown body (see §5) |

### Homepage keys (`docs/index.md`)
`home: true`, `heroImage`, `heroText`, `tagline`, `actionText`, `actionLink`, `bannerBg` (`auto` | `none` | image URL | `background: <css>`), `features: [{title, details, link?, imgUrl?}]`, `postList` (`detailed` default | `simple` | `none`), `simplePostListLength` (default 10, only with `simple`), `hideRightBar: true`. Pages with `home: true` are excluded from article lists.

### Internal keys (written into auto-generated @pages files)
`categoriesPage: true`, `tagsPage: true`, `archivesPage: true` — flag the three index pages so layouts render CategoriesPage/TagsPage/ArchivesPage components.

### "Is this an article?" predicate (util/postData.js — load-bearing for lists)
```js
!(frontmatter.pageComponent || frontmatter.article === false || frontmatter.home === true)
```
Grouping: every non-empty string in `categories`/`tags` arrays becomes a bucket; empty strings are ignored (hence the `['']` placeholder is harmless).

### Full YAML example (real catalogue page from the repo, docs/00.目录页/01.前端.md)
```yaml
---
pageComponent:
  name: Catalogue
  data:
    path: 01.前端
    imgUrl: /img/web.png
    description: JavaScript、ES6、Vue框架等前端技术
title: 前端
date: 2020-03-11 21:50:53
permalink: /web/
sidebar: false
article: false
comment: false
editLink: false
author:
  name: xugaoyi
  link: https://github.com/xugaoyi
---
```

---

## 3. Auto front matter generation (`vdoing/node_utils/setFrontmatter.js`) — exact algorithm

Runs on every dev/build over **all** .md files under docs/ except: files directly in docs/ root (e.g. index.md), and anything inside `.vuepress/` or `@pages/`. Parsing via `gray-matter`; serialization via `json2yaml` with regex post-processing (strips double quotes and re-indents — a known source of formatting quirks).

1. **File has NO front matter** → write a fresh block: `title` (parsed name), `date` (birthtime, `dateFormat` = local `YYYY-MM-DD HH:mm:ss`), `permalink`, `sidebar: auto` iff path contains `_posts`, `categories:` (unless `themeConfig.category === false`), `tags:\n  - ` (unless `themeConfig.tag === false`), plus all `themeConfig.extendFrontmatter` keys.
2. **File HAS front matter** → only fill in missing keys, same set; also fills any missing `extendFrontmatter` keys; then rewrites the file only `if (hasChange)`. Existing values are **never overwritten** ("一旦手动设置某字段，该字段就不再自动生成"). If `pageComponent` present or `article: false`, categories/tags are not added.
3. **Permalink generation** (exact code):
```js
const PREFIX = '/pages/'
function getPermalink () {
  return `${PREFIX + (Math.random() + Math.random()).toString(16).slice(2, 8)}/`
}
```
i.e. `/pages/` + **6 lowercase hex chars** (fractional part of a random float rendered base-16) + trailing `/`. No collision check (collision probability low but nonzero: 16^6 ≈ 16.7M space — clogem-press should add a uniqueness check). Older theme versions generated 16 hex chars — the demo repo still contains links like `/pages/8143cc480faf9a11/`, so a replica must treat permalinks as opaque, not fixed-length.
4. **Categories derivation** — regular files: take the path segments strictly between `docs/` and the file, strip each segment's number prefix (`substring(indexOf('.') + 1)`), one category per directory level, in order (e.g. `docs/01.前端/25.JavaScript文章/x.md` → `categories: [前端, JavaScript文章]`). `_posts` files: segments between `_posts/` and the file (`match(/_posts\/(\S*)\//)`) become categories; if the file is directly in `_posts/`, the single category is `themeConfig.categoryText` (default `随笔` "essays").
5. **Date repair**: if gray-matter parsed `date` as a JS Date (YAML timestamp), it is re-serialized via `repairDate` using **UTC** fields — replicate carefully or dates shift across timezones.
6. Config knobs: `themeConfig.category: false` / `tag: false` (also disables the respective generated index pages, see §6), `categoryText`, `extendFrontmatter: {author: {...}, ...}` (any keys, added when missing).

---

## 4. Routes: file-path-as-route vs permalink

- VuePress 1.x derives `page.regularPath` from the file path (URL-encoded; `README.md`/`index.md` → `dir/`, others → `.../<name>.html`). With vdoing's naming that yields ugly encoded routes like `/01.%E5%89%8D%E7%AB%AF/25.JavaScript.../01.xxx.html` — which is exactly why vdoing auto-assigns permalinks.
- **Precedence**: front matter `permalink` > global `permalink` pattern in config (placeholders `:year :month :i_month :day :i_day :slug :regular`) > `regularPath`. `page.path` = the effective route; `regularPath` is always retained.
- **Critical subtlety**: sidebar *matching* is done against `regularPath`, not the permalink — `resolveMatchingConfig(regularPath, sidebarConfig)` matches `encodeURI(base)` prefix, so the generated sidebar key `/01.前端/` matches pages physically inside that dir regardless of their permalinks. Sidebar item tuples resolve their link target by looking up the page whose `regularPath` equals the tuple's relative path resolved against the group base, then linking to that page's `path` (= permalink). Replicate this two-path model: **physical path for structure, permalink for URLs**.
- Nav config (`themeConfig.nav`) links point at permalinks (e.g. `/web/`, `/pages/a61298/`) — always written with trailing slash.
- Because permalinks are stable random IDs, file renames/moves never break URLs — an explicit design goal, and prerequisite for comment-thread identity (gitalk id = `permalink.slice(-16)`) and Baidu push.

---

## 5. Catalogue pages (目录页, `pageComponent`)

- A catalogue page is a normal .md file whose front matter carries `pageComponent.name: Catalogue`; the theme's Page.vue then renders `<component :is="frontmatter.pageComponent.name">` **instead of the markdown body**. In theme v1 the only shipped page component is `Catalogue` (Page.vue imports only Catalogue as dynamic page component). Note: the "`pageComponent: web`" sometimes mentioned is a misreading — `web` was just the *permalink* (`/web/`) of the demo's front-end catalogue page; the component name must be `Catalogue`.
- Front matter contract: `pageComponent.name: Catalogue` (required); `pageComponent.data.path`: the **physical dir path relative to docs/, with number prefixes**, e.g. `01.前端` or multi-level `01.学习笔记/01.前端` (supported since ~v1.9; the component walks into subgroups by stripping number prefixes and matching titles); `pageComponent.data.imgUrl` (80×80 header image, optional since v1.9.4); `pageComponent.data.description` (supports inline HTML, e.g. `<a>` with single quotes). Conventionally combined with `title`, `permalink`, `sidebar: false`, `article: false`, `comment: false`, `editLink: false`.
- Runtime data source: the component reads `themeConfig.sidebar['/<first path segment>/']` (the generated structure from §1) and renders a numbered outline: level-1 entries `1.`, `2.` …, nested `1-1.`, `1-1-1.` …; leaf tuples link via `router-link :to="item[2]"` (the permalink); `titleTag` badges rendered. If sidebar isn't `structuring`, it errors: "目录页数据依赖于结构化的侧边栏数据".
- Placement convention: catalogue .md files live in a dedicated top-level dir (demo: `docs/00.目录页/01.前端.md` etc.). The catalogue permalinks are what nav items and homepage `features[].link` point to. Additionally `sidebarData.catalogue = {<dirTitle>: <permalink>}` powers breadcrumb links from articles back to their catalogue page.

---

## 6. Auto-generated index pages (`docs/@pages/`, `node_utils/handlePage.js`)

On every run, unless disabled, the theme ensures these files exist (created only if absent, so users can customize them; deleted + empty dir removed when the feature is turned off):
- `categoriesPage.md`: `---\ncategoriesPage: true\ntitle: 分类\npermalink: /categories/\narticle: false\n---` (created unless `themeConfig.category === false`)
- `tagsPage.md`: same shape, `tagsPage: true`, `title: 标签`, `/tags/` (unless `tag: false`)
- `archivesPage.md`: `archivesPage: true`, `title: 归档`, `/archives/` (unless `archive: false`)
These render as filterable category/tag bars + paginated post lists, and a year-grouped archive. Category/tag data is computed client-side from all pages' front matter (filter via the article predicate, group via `categories`/`tags` arrays).

## 7. `_posts/` (碎片化文章, fragment blog posts)

- Files in `docs/_posts/` (optionally one level of subdirs used as categories) are blog posts **outside** the knowledge-base structure: NO number prefixes (a dotted filename there triggers a warning), NO structured sidebar (they get `sidebar: auto` injected), still get auto permalink/date/title, categories = subdir names or `categoryText`. They appear in the homepage post list, archive, categories, tags like any article.

## 8. One-click utilities & npm scripts (root `package.json`)

- `predev`/`prebuild`: `node utils/check.js dev|build && vdoing` — check.js aborts with a hint on Windows if the `export`-style script is used (`dev:win`/`build:win` variants exist); `vdoing` bin = `vdoing/bin/checkVersion.js`, a semver check of Node ≥18.
- `editFm` → `utils/editFrontmatter.js`: **batch front matter editor** driven by `utils/config.yml`: `path:` array whose first member MUST be `docs` followed by subdir segments (target scope); `delete:` list of keys to remove; `data:` map of keys to add/overwrite (unlike auto-generation, this DOES overwrite). Interactive y/N confirm (inquirer) before running; rewrites files via gray-matter + json2yaml.
- `baiduPush` → `node utils/baiduPush.js https://xugaoyi.com && bash baiduPush.sh`: the JS walks all md files, collects `DOMAIN + permalink` for every page that has one into `urls.txt`; the sh curls `http://data.zz.baidu.com/urls?site=...&token=...` with `urls.txt` then deletes it. Also automated daily via `.github/workflows/baiduPush.yml` (cron `0 23 * * *` UTC + on push).
- `deploy` → `deploy.sh`: `npm run build` then `git init`-in-`docs/.vuepress/dist` and force-push to `gh-pages` branch of `origin`. CI variant `.github/workflows/ci.yml` (on push to master, Node 18, `TZ: Asia/Shanghai` env so "last updated" renders in CST, force-push dist to gh-pages). (The task's "arc" likely refers to `archive`/归档 — there is no script named arc.)
- (Demo-site convenience: `vuepress-plugin-baidu-autopush` does Baidu auto-push from the browser side as well.)

## 9. Plugin structure

**Bundled inside the theme** (vdoing/index.js `plugins:` + package.json deps): `@vuepress/plugin-active-header-links`, `@vuepress/plugin-search`, `@vuepress/plugin-nprogress`, `vuepress-plugin-smooth-scroll` (opt-in), and many `vuepress-plugin-container` instances: `note`, `tip`, `warning`, `danger`, `right`, `theorem`, `details`, `center`, plus two data-driven card containers `cardList` and `cardImgList` whose body is a ```yaml fenced block parsed with js-yaml and rendered to HTML cards (config: row count 1–4 via `::: cardList 2`, `target`, `imgHeight`, `objectFit`, `lineClamp`; items: `name, desc, link, img, avatar, author, bgColor, textColor`). Replicating these markdown containers matters for content compatibility.

**Demo-site level** (docs/.vuepress/config.ts): `sitemap` (hostname), `vuepress-plugin-baidu-autopush`, `vuepress-plugin-baidu-tongji`, `thirdparty-search` (or optional `fulltext-search`), `one-click-copy`, `demo-block`, `vuepress-plugin-zooming` (selector excludes `img.no-zoom`), `vuepress-plugin-comment` (gitalk; issue id = last 16 chars of permalink), plus a `dayjs`-formatted lastUpdated hook.

**Theme-level config surface** (types/index.ts `VdoingThemeConfig` extends default): `category/tag/archive` booleans, `categoryText`, `pageStyle: 'card'|'line'`, `bodyBgImg(+Opacity,+Interval)`, `titleBadge(+Icons)`, `contentBgStyle 1-6`, `updateBar {showToArticle, moreArticle}`, `rightMenuBar`, `sidebarOpen`, `pageButton`, `defaultMode: 'auto'|'light'|'dark'|'read'`, `sidebar` (above), `author`, `blogger {avatar,name,slogan}`, `social {iconfontCssFile, icons[]}`, `footer {createYear, copyrightInfo}`, `extendFrontmatter`, `htmlModules`, plus inherited `nav` (with vdoing extension: a parent nav item may carry both `link` (to a catalogue page) and `items`), `sidebarDepth`, `logo`, `repo`, `searchMaxSuggestions`, `lastUpdated`, `docsDir`, `docsBranch`, `editLinks`, `editLinkText`, `algolia`.

## 10. Replication gotchas for clogem-press

1. Order-as-array-index semantics (dup numbers overwrite; skipped invalid entries) — decide whether to replicate warnings-and-skip or fail hard.
2. Two-path model: keep both `regular-path` (from file location, used for sidebar scoping/edit links) and `path` (permalink) per page.
3. Front matter auto-fill must be idempotent and never overwrite; permalink randomness needs a collision check (vdoing has none) and should stay `/pages/xxxxxx/` for URL compatibility during migration.
4. The YAML rewrite pipeline (json2yaml + regex strip of quotes) mangles some values (strings needing quotes, multiline); a Clojure implementation should preserve original formatting where possible (edit-in-place of only missing keys).
5. Titles: sidebar title = front matter `title` if set, else parsed filename — parse rule uses first/last dot indexes, so dots inside names are legal.
6. `birthtime` fallback to `atime` when year==1970; date strings are local-time; batch tools re-serialize YAML dates via UTC.
7. gitalk comment identity depends on `permalink.slice(-16)` — permalink stability is a hard requirement.
8. GitHub Pages from a docs/ folder (clogem-press target) differs from vdoing's gh-pages-branch push; the baiduPush urls.txt generator is trivially portable (walk md, collect domain+permalink).


## Sources
- https://doc.xugaoyi.com/pages/33d574/
- https://doc.xugaoyi.com/pages/3216b0/
- https://doc.xugaoyi.com/pages/088c16/
- https://doc.xugaoyi.com/pages/54651a/
- https://doc.xugaoyi.com/pages/2f674a/
- https://github.com/xugaoyi/vuepress-theme-vdoing
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/node_utils/setFrontmatter.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/node_utils/getSidebarData.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/node_utils/handlePage.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/index.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/components/Catalogue.vue
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/util/postData.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/utils/editFrontmatter.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/utils/baiduPush.js
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/docs/.vuepress/config.ts
- https://github.com/xugaoyi/vuepress-theme-vdoing/blob/master/vdoing/types/index.ts
- https://v1.vuepress.vuejs.org/guide/permalinks.html