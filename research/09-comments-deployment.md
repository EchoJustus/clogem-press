# Research: Comment Systems + GitHub Pages Cross-Repo Deployment (verified August 2026)

## Part A — Comment systems for static sites

### Maintenance status (verified against GitHub commit histories / npm registry, Aug 16 2026)

| System | Backend model | Last real activity (verified) | Status | Dark mode | China viability | Embed complexity |
|---|---|---|---|---|---|---|
| **giscus** | GitHub Discussions (no server; hosted at giscus.app, self-hostable) | Commit **May 26, 2026**; 12.0k stars, active issues/PRs | **Actively maintained** | Yes — many themes incl. `preferred_color_scheme`, runtime-switchable | Weak (GitHub + giscus.app/Vercel unreliable behind GFW; self-host mitigates) | One `<script>` tag |
| utterances | GitHub Issues (hosted bot at utteranc.es) | Last commit **Feb 12, 2022**; 9.7k stars | **Dormant** (service still runs, code frozen 4.5 yrs) | Yes (dark themes) | Weak | One `<script>` tag |
| gitalk | GitHub Issues (client-side OAuth) | Last commit **Jul 30, 2024** (README only); last code ~2022 | **Dormant**; known design flaw: OAuth App `clientSecret` shipped in frontend + needs a CORS proxy; admin must manually initialize an issue per page | Via CSS | Weak | Script + init object + OAuth app |
| **vssue** | GitHub/GitLab/Bitbucket/Gitee Issues (what vdoing used) | npm latest **1.4.8, published Apr 23, 2021**; `master` last touched Jul 2023 (docs link fix); `main` contains a single abandoned "v2 initial commit" from **May 30, 2024**, nothing since | **Effectively dead.** Do not adopt. It is also a Vue 2 component, not a plain script embed — wrong shape for a non-Vue SSG anyway | Limited | Weak | Vue component (not SSG-friendly) |
| waline | Self/serverless-hosted server + DB (Vercel/LeanCloud/etc.) | Commit **Aug 10, 2026** (renovate, active repo, 3800+ PRs) | **Very active** | Yes | **Strong** (China-native project, deployable on China-reachable infra) | Script/CSS embed + deployed server |
| twikoo | Serverless backend (Tencent CloudBase / Vercel / self-host) | Commit **Aug 13, 2026** | **Very active** | Yes | **Strong** (Tencent CloudBase first-class) | Script embed + deployed backend |
| artalk | Self-hosted Go server + DB | Commit **Jul 24, 2026** | **Active** | Yes | **Strong** (self-hosted anywhere) | Script/CSS embed + server |
| Disqus | Proprietary SaaS | n/a (commercial) | Alive but user-hostile: ads on free tier ($10+/mo to remove), ~1MB+ JS / 70+ requests, heavy tracking; blocked in China | Yes | **None** (blocked) | Script tag |

### Key verified details — giscus (recommended)

Prerequisites: the target repo must be **public**, the **giscus GitHub App installed**, and **Discussions enabled**. Comments live in GitHub Discussions (reactions, replies, threading — richer than utterances' issue-based model). Official embed (from giscus.app):

```html
<script src="https://giscus.app/client.js"
        data-repo="[owner/repo]"
        data-repo-id="[REPO_ID]"
        data-category="[CATEGORY]"
        data-category-id="[CATEGORY_ID]"
        data-mapping="pathname"
        data-strict="0"
        data-reactions-enabled="1"
        data-emit-metadata="0"
        data-input-position="bottom"
        data-theme="preferred_color_scheme"
        data-lang="en"
        crossorigin="anonymous"
        async>
</script>
```

- Mapping options: `pathname` (right choice for an SSG — stable per-URL), URL, `<title>`, `og:title`, custom term, or explicit discussion number. `data-strict="1"` avoids fuzzy-match collisions.
- Theming: built-in light/dark/high-contrast/Catppuccin/Gruvbox + `preferred_color_scheme` (auto media-query). For a vdoing-style manual dark-mode toggle, the theme can be switched at runtime by posting to the iframe: `iframe.contentWindow.postMessage({ giscus: { setConfig: { theme: 'dark' } } }, 'https://giscus.app')` — the SSG's theme-toggle JS should include this hook.
- Lazy loading supported (`data-loading="lazy"`, uses iframe `loading` attribute) — keeps page weight near zero until scrolled.
- Self-hosting is officially documented (SELF-HOSTING.md; Next.js app + Cloudflare Workers variant), relevant for the China case.

### Recommendation

**Primary: giscus.** It is the only GitHub-based option that is actively maintained in 2026 (utterances frozen since 2022, gitalk dormant + insecure-by-design, vssue dead since 2021). It matches vdoing's "comments backed by the GitHub repo" spirit while upgrading from Issues to Discussions, needs zero backend, is a single script tag (trivially templatable in a Selmer/Hiccup layout), and has first-class dark-mode + runtime theme switching.

**Architecture note:** make the comment block a pluggable site-config key (e.g. `:comments {:provider :giscus :repo ... :repo-id ...}`) rendered into the page template, with `:provider :none` default. That keeps the door open for **twikoo or waline** if a mainland-China audience becomes the priority — those are the actively-maintained China-viable options, at the cost of running a backend. Caveat to record in the design doc: the site itself is on `*.github.io`, which is also unreliable from mainland China, so giscus's China weakness is not the deciding constraint unless the site is mirrored onto China-reachable hosting.

---

## Part B — GitHub Pages deployment (clogem-press → EchoJustus.github.io, `docs/` on `main`)

### Ground rules confirmed from GitHub docs

- Deploy-from-branch supports exactly two folders: `/` (root) or `/docs` on any chosen branch. This branch/folder picker applies to the repo's Pages settings generally (EchoJustus.github.io is a *user site*, so it publishes at `https://echojustus.github.io/` from whatever branch/folder is configured — `main` + `/docs` is a supported configuration).
- Deploy-from-branch runs GitHub's **Jekyll** pipeline unless a **`.nojekyll`** file exists at the publishing-source root. Without it, files/dirs starting with `_` (and some others) are dropped and builds are slower. **The generator must always emit `docs/.nojekyll`.**
- The alternative "GitHub Actions" publishing mode (`actions/upload-pages-artifact` + `actions/deploy-pages`) deploys **only to the Pages site of the repo the workflow runs in** — there is no cross-repo target option, and switching to it abandons the `docs/`-folder requirement (Pages source becomes "GitHub Actions"). Not compatible with the stated setup unless the workflow moves into EchoJustus.github.io *and* the docs-folder constraint is dropped.

### Cross-repo auth options (verified)

1. **`GITHUB_TOKEN` — cannot do this.** It is scoped to the repository running the workflow; peaceiris docs state it explicitly: GITHUB_TOKEN "has no permission to access external repositories." (It also has a known quirk of not triggering downstream workflows/first Pages build even same-repo.)
2. **Deploy key (recommended).** Repo-scoped SSH keypair: public key → *EchoJustus.github.io* → Settings → Deploy keys, **with write access**; private key → *clogem-press* → Actions secret `ACTIONS_DEPLOY_KEY`. Never expires, grants access to exactly one repo, free on all plans. Generate: `ssh-keygen -t ed25519 -C "clogem-press-deploy" -f gh-pages -N ""` (peaceiris README shows the rsa-4096 variant; ed25519 also works). Pushes made with a deploy key **do** trigger GitHub's automatic `pages-build-deployment` on the target repo (unlike GITHUB_TOKEN pushes).
3. **Fine-grained PAT.** Scopeable to just EchoJustus.github.io with `Contents: read/write`; workable but expires (max 1 year) and is tied to a user account — operationally worse than a deploy key for a permanent pipeline. Classic PATs (full `repo` scope) are over-privileged; avoid.

### The deployment action

**peaceiris/actions-gh-pages@v4** — verified actively maintained (last commit **Jul 16, 2026**; 5.4k stars). Relevant verified semantics:

- `external_repository: EchoJustus/EchoJustus.github.io` + `deploy_key` (or `personal_token`) — the documented cross-repo path.
- `publish_branch: main`, `publish_dir: ./dist` (build output), `destination_dir: docs` — deploys into a subdirectory of the branch. **Deletion scope is confined to `destination_dir`**: "existing files in the publish branch (or only in destination_dir if given) will be removed" — so a README, LICENSE, etc. at the target repo root survive each deploy. Do **not** set `force_orphan: true` here (it rewrites the entire branch to a single commit, nuking root files; it also conflicts with keep/destination options). `keep_files: false` (default) is what you want so deleted pages disappear.
- `cname: <domain>` writes a CNAME file — only needed if a custom domain is added later.

Alternatives considered: building *in* the pages repo (workflow in EchoJustus.github.io cloning clogem-press) just reverses the credential problem (checkout of the source repo and/or `repository_dispatch` triggering needs a PAT) and splits CI away from where content edits happen — rejected. `cpina/github-action-push-to-another-repository` duplicates what peaceiris already does with fewer options. Raw `git push` from a script is what the local `bb deploy` task does anyway (below), so CI uses the battle-tested action and local use gets the bb task.

### CNAME / custom domain (conditional — only if a custom domain is ever configured)

- Setting a custom domain in the Pages settings UI "creates a commit that adds a `CNAME` file directly to the root of your source branch." Because every deploy rewrites `docs/`, the domain setting will be **silently reset if the deployed output lacks the CNAME file** — the standard failure mode. Fix: emit `CNAME` from the generator (site config key) into the output root, or set `cname:` on the peaceiris step. With `/docs` publishing, verify the file lands where GitHub's settings UI put it (docs vary; UI commits to the source-branch root — keep whichever location the UI created, and make the pipeline preserve it).
- DNS: apex → 4 A records (or ALIAS/ANAME); `www` subdomain → CNAME record to `echojustus.github.io`. "Enforce HTTPS" available after cert provisioning (up to 24 h).
- With the default `echojustus.github.io` domain (a user site serves at the domain root), **no CNAME is needed** — and note base-path implications: a user site is served at `/`, so the SSG's base URL is simply `/` (project sites would need `/repo-name/` prefixing).

### Cache behavior (verified live against GitHub Pages, Aug 16 2026)

Response headers observed: `cache-control: max-age=600`, `etag: ...`, `via: 1.1 varnish`, `x-served-by: cache-...` (Fastly CDN). **Headers are not configurable on GitHub Pages.** Consequences for the generator design:
- HTML pages: at most 10 minutes stale after deploy — acceptable, no action needed.
- CSS/JS/assets also get only `max-age=600` (no immutable/long-cache) — so content-hashed asset filenames are *not* required for correctness, but fingerprinting (e.g. `app.3fa2b1.css`) is still recommended so HTML and assets can never be served as a mismatched pair across the 10-minute cache window.

### Recommended concrete pipeline

**One-time setup:** generate keypair; public key → deploy key (write) on EchoJustus.github.io; private key → secret `ACTIONS_DEPLOY_KEY` on clogem-press; Pages source on EchoJustus.github.io = `main` / `docs/`.

**bb.edn tasks (clogem-press):**

```clojure
{:tasks
 {build  {:doc "Render site into dist/ (emits .nojekyll, optional CNAME)"
          :task (exec 'clogem-press.core/build)}   ; must write dist/.nojekyll
  deploy {:doc "Manual local deploy: push dist/ into EchoJustus.github.io:docs/"
          :depends [build]
          :task (shell "bash" "-c"
                 "set -e
                  rm -rf .deploy && git clone --depth 1 git@github.com:EchoJustus/EchoJustus.github.io .deploy
                  rm -rf .deploy/docs && mkdir .deploy/docs
                  cp -r dist/. .deploy/docs/
                  cd .deploy && git add -A
                  git diff --cached --quiet || (git commit -m \"deploy: $(date -u +%FT%TZ)\" && git push)")}}}
```

**.github/workflows/deploy.yml (clogem-press):**

```yaml
name: Deploy site
on:
  push: { branches: [main] }
  workflow_dispatch:
permissions: { contents: read }
concurrency: { group: deploy, cancel-in-progress: true }
jobs:
  build-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: DeLaGuardo/setup-clojure@13     # installs babashka via `bb:` input; actively maintained
        with: { bb: latest }
      - run: bb build                          # writes dist/ incl. .nojekyll
      - uses: peaceiris/actions-gh-pages@v4    # last commit Jul 2026, maintained
        with:
          deploy_key: ${{ secrets.ACTIONS_DEPLOY_KEY }}
          external_repository: EchoJustus/EchoJustus.github.io
          publish_branch: main
          publish_dir: ./dist
          destination_dir: docs
          # cname: example.com                 # only if a custom domain is adopted
          commit_message: "deploy: ${{ github.event.head_commit.message }}"
```

The push lands in `docs/` on `main` of the pages repo; GitHub's automatic `pages-build-deployment` picks it up (skipping Jekyll thanks to `.nojekyll`) and the site is live within ~1–2 minutes + up to 10 min CDN cache. The same result is reproducible locally with `bb deploy` (SSH key of the developer) — CI and local paths converge on identical repo state.

### Risks / notes for the design doc

- `destination_dir` is flagged "beta" in the peaceiris README, but has been stable for years and its deletion-scoping behavior is documented and widely used; keep an eye on it after action upgrades.
- Deploy keys don't expire but are account-independent secrets — rotate if a maintainer leaves.
- If requirements ever relax to "any publishing mode," the `actions/deploy-pages` artifact flow inside EchoJustus.github.io is the GitHub-native modern path (no publish-branch history growth, no `.nojekyll` needed) — but it cannot be driven cross-repo from clogem-press.
- giscus integration point in templates: single script tag per page + one postMessage hook in the dark-mode toggle; no build-time dependency, no impact on the SSG pipeline.

## Sources
- https://github.com/giscus/giscus
- https://github.com/giscus/giscus/commits/main
- https://giscus.app/
- https://github.com/giscus/giscus/blob/main/SELF-HOSTING.md
- https://github.com/utterance/utterances
- https://github.com/utterance/utterances/commits/master
- https://github.com/gitalk/gitalk/commits/master
- https://github.com/meteorlxy/vssue
- https://github.com/meteorlxy/vssue/commits/master
- https://github.com/meteorlxy/vssue/commits/main
- https://registry.npmjs.org/vssue
- https://github.com/walinejs/waline/commits/main
- https://github.com/twikoojs/twikoo/commits/main
- https://github.com/ArtalkJS/Artalk/commits/master
- https://kinsta.com/blog/disqus-ads/
- https://markosaric.com/remove-disqus/
- https://areknawo.com/top-6-disqus-alternatives-for-technical-blogging/
- https://eastondev.com/blog/en/posts/dev/20251204-astro-comment-systems-guide/
- https://ecosystem.vuejs.press/plugins/blog/comment/giscus/
- https://github.com/peaceiris/actions-gh-pages
- https://github.com/peaceiris/actions-gh-pages/commits/main
- https://github.com/peaceiris/actions-gh-pages/blob/main/README.md
- https://github.com/peaceiris/actions-gh-pages/issues/324
- https://github.com/peaceiris/actions-gh-pages/discussions/1118
- https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site
- https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site
- https://github.com/DeLaGuardo/setup-clojure
- https://pages.github.com/ (live header inspection: cache-control max-age=600, Fastly/Varnish)
- https://en.wikipedia.org/wiki/Censorship_of_GitHub