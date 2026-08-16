# GitHub Pages Actions artifact deployment + CI front-matter write-back (verified August 2026)

Verification notes for DESIGN.md **v2**, replacing the cross-repo `peaceiris` / deploy-key path in
[research/09](./09-comments-deployment.md) Part B. All claims below were checked against primary
sources in August 2026: GitHub's own docs source (`github/docs`), the action repositories'
`action.yml` / `README.md` / `CHANGELOG.md` on `raw.githubusercontent.com`, and
`actions/starter-workflows`. Where a version number is asserted, it was established by fetching the
tag's `action.yml` directly and confirming a 404 for the next major (raw.githubusercontent.com
returns a literal `404: Not Found` body for a nonexistent ref, which makes this a reliable probe).

---

## 1. Why the design changed

v1 deployed *from* `clogem-press` *into* `EchoJustus.github.io:main:/docs` using an SSH deploy key
and `peaceiris/actions-gh-pages@v4`, because `GITHUB_TOKEN` cannot reach external repositories.
Three facts make the "GitHub Actions" publishing source strictly better once the workflow moves
**into** the pages repo:

1. **No credential to manage.** The workflow runs in the repo it publishes; `GITHUB_TOKEN` with
   `pages: write` + `id-token: write` is enough. No deploy key, no PAT, no secret rotation.
2. **No built HTML is committed anywhere.** The site ships as an Actions artifact, so there is no
   `docs/` commit, therefore no "deploy commit triggers the build workflow" loop to defend against,
   and no publish-branch history growth.
3. **The Pages build rate limit stops applying.** Verified verbatim from GitHub's docs source
   (`content/pages/getting-started-with-github-pages/github-pages-limits.md`):

   > * {% data variables.product.prodname_pages %} sites have a _soft_ limit of 10 builds per hour.
   >   This limit does not apply if you build and publish your site with a custom
   >   {% data variables.product.prodname_actions %} workflow.

   (`{% data variables.product.prodname_pages %}` renders as "GitHub Pages".) Deploy-from-branch is
   subject to the 10/hour soft limit; the Actions flow is not.

The one thing the artifact flow **cannot** do is deploy across repositories — it deploys only to the
Pages site of the repo the workflow runs in. That is precisely why the repo split (D-8) and the
deployment change (D-8) are a single decision, not two.

---

## 2. Publishing source = "GitHub Actions"

From <https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site>:

- Settings → Pages → "Build and deployment" → Source → **GitHub Actions**.
- The flow is `actions/checkout` → `actions/upload-pages-artifact` → `actions/deploy-pages`.
- Workflows deploy through a deployment environment named **`github-pages`**; GitHub "recommend[s]
  that you add a deployment protection rule so that only the default branch can deploy to this
  environment."
- Switching the source away from a branch means the `docs/` folder stops being special. The existing
  `EchoJustus.github.io:main:/docs/index.html` "Hello, World" becomes dead weight and should be
  deleted in the same commit that adds the workflow.

**`.nojekyll` is not needed.** The Jekyll pipeline only runs for the deploy-from-branch source. The
artifact is served as-is. (The docs page mentions `.nojekyll` only in the context of external CI
committing to a branch.) Related gotcha, from `actions/upload-pages-artifact`'s `action.yml`:
`include-hidden-files` defaults to `false` and the action "Excludes `.git` and `.github`
regardless" — so if the generator ever *does* need to ship a dotfile (`.well-known/`, a
`.nojekyll` kept for belt-and-braces), that input must be flipped to `true`.

---

## 3. Current action versions (probed August 2026)

| Action | Newest major that exists | What its own README shows | What GitHub's starter workflow pins |
|---|---|---|---|
| `actions/checkout` | **v7** (CHANGELOG top entry: `v7.0.1`) | `@v7` throughout | `@v4` |
| `actions/configure-pages` | **v6** | (no version in usage section) | `@v5` |
| `actions/upload-pages-artifact` | **v5** | `@v3` | `@v3` |
| `actions/deploy-pages` | **v5** (`v5.0.0`, `runs: using: node24`) | `@v4` | `@v5` |
| `DeLaGuardo/setup-clojure` | **13** (no `14`) | — | — |

Probe method: `GET https://raw.githubusercontent.com/<repo>/<tag>/action.yml`; a body of
`404: Not Found` means the tag does not exist. Confirmed non-existent: `deploy-pages@v6`,
`upload-pages-artifact@v6`, `configure-pages@v7`, `checkout@v99`, `setup-clojure@14`.

**Note the lag:** the READMEs of `deploy-pages` and `upload-pages-artifact` still show `@v4` / `@v3`
in their usage examples even though `v5` exists for both, and GitHub's own starter workflow still
pins `checkout@v4` while checkout's README recommends `@v7`. This is not a contradiction to
resolve — it reflects that the starter workflows are updated conservatively and as a tested set.

**Pinning policy adopted in DESIGN.md v2:** pin the Pages triad to the combination GitHub's
`actions/starter-workflows` ships (that trio is tested together), and pin `actions/checkout` to the
version its own README recommends. Review both on each generator release.

### `actions/starter-workflows/pages/static.yml`, verbatim (August 2026)

```yaml
# Sets permissions of the GITHUB_TOKEN to allow deployment to GitHub Pages
permissions:
  contents: read
  pages: write
  id-token: write

# Allow only one concurrent deployment, skipping runs queued between the run in-progress and latest queued.
# However, do NOT cancel in-progress runs as we want to allow these production deployments to complete.
concurrency:
  group: "pages"
  cancel-in-progress: false

jobs:
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Setup Pages
        uses: actions/configure-pages@v5
      - name: Upload artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: '.'
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v5
```

### The permissions contract (from `actions/deploy-pages` README, verbatim)

> 2. The job that executes the deployment must at minimum have the following permissions:
>    - `pages: write`
>    - `id-token: write`

and, on why both are needed:

> The pages permission relates to the `GITHUB_TOKEN` by giving it the permissions to create pages
> deployments when calling the GitHub API. The id-token permission is necessary to request the OIDC
> JWT token.

> 3. The deployment should target the `github-pages` environment (you may use a different
> environment name if needed, but this is not recommended.)

`deploy-pages` inputs (from its README): `token` (default `${{ github.token }}`), `timeout`
(default `600000` ms = 10 min), `error_count` (10), `reporting_interval` (5000), `artifact_name`
(`github-pages`), `preview` (alpha, not public). Output: `page_url`.

### `actions/configure-pages` is optional here

Its outputs are `base_url`, `origin`, `host`, `base_path` — i.e. it exists so frameworks can learn
their base path (`/my-repo` for a project site, `""` for a user site) and, with `enablement: true`
plus a non-`GITHUB_TOKEN` credential, to turn Pages on. `EchoJustus.github.io` is a **user site
served at `/`**, and Pages will already be enabled by the manual settings change, so the step buys
nothing and is one more version to track. DESIGN.md v2 omits it. (clogem-press's *own* docs site is
a project site at `/clogem-press/` and is the place where a non-empty `base_path` actually matters —
there the step, or a hardcoded `:base`, is required.)

### Artifact constraints (from `actions/upload-pages-artifact` README, verbatim)

The uploaded artifact must "Be named `github-pages`" and "Be a single `gzip` archive containing a
single `tar` file". The tar must:

> - be under 10GB in size (we recommend under 1 GB!)
>   - :warning: The GitHub Pages [officially supported maximum size limit is 1GB], so the subsequent
>     deployment of larger tarballs are not guaranteed to succeed — often because they are more prone
>     to exceeding the maximum deployment timeout of 10 minutes.
> - not contain any symbolic or hard links
> - contain only files and directories

`retention-days` defaults to `1`.

Cross-checked against the Pages limits doc source: source repos have "a recommended limit of 1 GB",
"Published GitHub Pages sites may be no larger than 1 GB", "GitHub Pages deployments will timeout if
they take longer than 10 minutes", and there is "a _soft_ bandwidth limit of 100 GB per month".

**"No symbolic links" is a live constraint for us:** the build must copy assets into `dist/`, never
symlink them, and the Pagefind/Chroma tool cache must live outside the published directory.

---

## 4. Checking out the generator from the content repo

`EchoJustus/clogem-press` is public, so a plain second checkout needs **no credentials**. From
`actions/checkout`'s README, the private case is the only one that needs a token:

> - If your secondary repository is private or internal you will need to add the option noted in
>   [Checkout multiple repos (private)]

> - `${{ github.token }}` is scoped to the current repository, so if you want to checkout a different
>   repository that is private you will need to provide your own PAT.

The side-by-side pattern (README, verbatim, at `@v7`):

```yaml
- name: Checkout
  uses: actions/checkout@v7
  with:
    path: main

- name: Checkout tools repo
  uses: actions/checkout@v7
  with:
    repository: my-org/my-tools
    path: my-tools
```

`ref:` takes "The branch, tag or SHA to checkout" (from `action.yml`), which is the pinning seam:
`ref: v0.3.0` for a readable pin, `ref: <40-char sha>` if the owner wants tag-immutability
guarantees. This is decision **D-14** in DESIGN.md v2.

---

## 5. `GITHUB_TOKEN` pushes do not trigger workflows — verified

This is the load-bearing fact under the CI front-matter write-back design. From
<https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow>,
verbatim:

> When you use the repository's `GITHUB_TOKEN` to perform tasks, events triggered by the
> `GITHUB_TOKEN` will not create a new workflow run, with the following exceptions:
> `workflow_dispatch` and `repository_dispatch` events always create workflow runs.

> For all other events, this behavior prevents you from accidentally creating recursive workflow
> runs. For example, if a workflow run pushes code using the repository's `GITHUB_TOKEN`, a new
> workflow will not run even when the repository contains a workflow configured to run when `push`
> events occur.

> If you do want to trigger a workflow from within a workflow run, you can use a GitHub App
> installation access token or a personal access token instead of `GITHUB_TOKEN` to trigger events
> that require a token.

A further documented exception, not relevant to a push-triggered build but worth recording:

> `pull_request` events with the `opened`, `synchronize`, or `reopened` activity types: when a
> workflow using `GITHUB_TOKEN` creates or updates a pull request, the resulting `pull_request`
> event creates workflow runs in an **approval-required** state.

**Interpretation for the design.** A `push` triggered by a `GITHUB_TOKEN` push is exactly the
"all other events" case: it does not start a new run. The write-back job is therefore
loop-safe *by GitHub's documented contract*. DESIGN.md v2 still adds two independent guards, because
a behaviour we depend on but do not control deserves defence in depth (see §6).

---

## 6. Front-matter write-back in CI — the three mechanisms, compared

**Problem.** vdoing assigns `permalink`/`date`/`categories` by *mutating the source `.md` file* at
build time. The owner sometimes commits Markdown through the GitHub web UI, where no local tool runs,
so a freshly committed file arrives with no permalink.

**Acceptance criterion (vdoing's, non-negotiable):** moving or renaming a file must not change its
URL.

### (a) CI write-back with `GITHUB_TOKEN` — **chosen**

A first job runs the front-matter auto-fill against the checked-out content, and if the tree is
dirty, commits and pushes with the default token.

- Satisfies the criterion, because the permalink ends up **inside the file**. Identity travels with
  the content; `git mv` carries it automatically. This is the only property that makes vdoing's
  guarantee work, and no external mapping reproduces it without a second identity field.
- Loop safety: GitHub's documented `GITHUB_TOKEN` behaviour (§5), **plus** a commit-message marker
  checked in a job-level `if:`, **plus** the structural fact that auto-fill is idempotent — a second
  run finds nothing missing, produces no diff, and pushes nothing. Any one of the three is
  sufficient; all three are cheap.
- Costs and failure modes to document: the workflow needs `contents: write` on that job; a protected
  `main` will reject the bot push unless the token is allowed to bypass; two pushes in quick
  succession can make the second run's push non-fast-forward (mitigate with `git pull --rebase`
  before pushing, and treat a failed push as non-fatal — the next run fixes it); commits authored by
  `github-actions[bot]` appear in the content repo's history.

### (b) Committed `permalinks.edn` lockfile — **kept, but as an auxiliary, not as identity**

- As the *sole* identity store it **fails the criterion**: the file needs a key, and the only key
  available without touching the file is its path — so a `git mv` orphans the entry and the URL
  breaks. Keying by an author-written `uid` just relocates problem (a) without solving it.
- It is genuinely valuable for three other jobs, so v2 keeps it: (i) whole-site permalink collision
  detection without re-reading every file; (ii) a tombstone ledger of retired permalinks, so deleted
  or re-pointed URLs can emit redirect stubs instead of 404s; (iii) making `--no-write` builds
  (PR previews, local read-only runs) produce byte-identical URLs.

### (c) Deterministic permalinks from stable article identity — **used only as a read-only fallback**

- Derived from the *path*: fails the criterion outright (move the file, change the URL) — this is the
  exact failure vdoing invented random permalinks to avoid.
- Derived from *content*: worse; editing an article changes its URL.
- Derived from an author-assigned stable id: satisfies the criterion, but requires the author to
  write a field into the file — which is problem (a) again, minus the automation.
- Where it earns its place: a `--no-write` build still has to render *something* for a page with no
  permalink and no lockfile entry. A deterministic path-derived URL is reproducible within that
  build and across repeats of it, which is strictly better than a fresh random one. It is never
  persisted and never deployed as canonical.

**Conclusion:** (a) primary, (b) auxiliary, (c) fallback. The three are complementary rather than
alternatives, which is why v2 adopts all three in fixed roles.

---

## 7. The workflow as designed (EchoJustus.github.io)

```yaml
name: Publish site
on:
  push:
    branches: [main]
    paths:
      - 'content/**'
      - 'assets/**'
      - 'overrides/**'
      - 'site.edn'
      - 'permalinks.edn'
      - '.github/workflows/publish.yml'
  workflow_dispatch:

concurrency:
  group: "pages"
  cancel-in-progress: false

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    # Guard 2: never re-process our own normalization commit.
    if: "!contains(github.event.head_commit.message, '[clogem-normalize]')"
    permissions:
      contents: write          # only this job may write, and only to normalize front matter
    steps:
      - uses: actions/checkout@v7
        with: { path: site, fetch-depth: 0 }
      - uses: actions/checkout@v7
        with:
          repository: EchoJustus/clogem-press
          ref: v0.3.0          # D-14: tag or sha; public repo, no credentials needed
          path: generator
      - uses: DeLaGuardo/setup-clojure@13
        with: { bb: latest }

      - name: Normalize front matter (permalinks, dates, categories)
        working-directory: site
        run: bb --config ../generator/bb.edn fm-fix
      - name: Commit normalization if anything changed
        working-directory: site
        run: |
          git config user.name  "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add -A
          git diff --cached --quiet && exit 0
          git commit -m "chore: auto front matter [clogem-normalize]"
          git pull --rebase --autostash origin main || true
          git push || echo "::warning::normalization push failed; will retry next run"

      - name: Build
        working-directory: site
        run: bb --config ../generator/bb.edn build --out ../dist
      - uses: actions/upload-pages-artifact@v3
        with: { path: dist }

  deploy:
    needs: build
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - id: deployment
        uses: actions/deploy-pages@v5
```

Design points worth recording:

- **The build runs against the normalized working tree**, not against a re-fetch. The push and the
  build see the same bytes, so a failed push degrades to "the site is correct, the repo catches up
  next run" rather than "the site and the repo disagree".
- **Split permissions per job**: `contents: write` exists only in `build`; `pages`/`id-token` only in
  `deploy`. The top-level default stays `contents: read`.
- `fetch-depth: 0` is there so `git pull --rebase` has history to rebase onto.
- `concurrency.group: "pages"` with `cancel-in-progress: false` follows GitHub's starter comment
  ("do NOT cancel in-progress runs as we want to allow these production deployments to complete").

---

## 8. Removed from the v1 design

| v1 element | Status in v2 | Reason |
|---|---|---|
| `peaceiris/actions-gh-pages@v4` + `external_repository` + `destination_dir: docs` | removed | Not needed once the workflow lives in the pages repo. (Action itself is still maintained — this is a fit change, not a quality judgement.) |
| SSH deploy key + `ACTIONS_DEPLOY_KEY` secret | removed | `GITHUB_TOKEN` suffices in-repo. One less permanent credential. |
| `docs/` publishing folder + `docs/index.html` "Hello, World" | delete | Pages source becomes "GitHub Actions"; `docs/` is no longer special. |
| `.nojekyll` emission | removed | Jekyll only runs for the deploy-from-branch source. |
| `bb deploy` (clone target, sync `docs/`, push) | removed | There is no branch to push HTML to. Local equivalent is `bb build && bb serve dist`. |
| Baidu URL push (`baiduPush.js` + daily cron) | removed | See [research/12](./12-i18n-multilingual.md) §8 — replaced by sitemap + Search Console/Bing, optional IndexNow. |

Unchanged and still true from research/09: GitHub Pages responses carry `cache-control: max-age=600`
and headers are not configurable, so fingerprinted asset filenames remain recommended; and giscus is
still the comment recommendation.

---

## 9. Not verified / open

- **`page_url` behaviour for a user site with a custom domain** — not tested here; irrelevant while
  the site stays at `echojustus.github.io`.
- **Whether an org/repo ruleset on `main` would block the `github-actions[bot]` push.** Depends on
  settings this session cannot read. Documented as a setup step to check once.
- **Actual deployment wall-clock** for a site of this size — no measurement was possible; the
  10-minute deployment timeout is documented but the margin is unknown.
- I did **not** re-verify `peaceiris/actions-gh-pages`'s current state, since v2 drops it.
- GitHub's API (`api.github.com`) is not reachable from this session's proxy for arbitrary repos, so
  release *dates* for the action majors could not be confirmed; only tag existence and file contents
  were verified. A `github.com/.../releases` page read via a summarizing fetch gave conflicting years
  (2025 vs 2026) for `deploy-pages@v5.0.0`, so **no release date is asserted anywhere above**.

## Sources

- https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site
- https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow
- https://raw.githubusercontent.com/github/docs/main/content/pages/getting-started-with-github-pages/github-pages-limits.md
- https://raw.githubusercontent.com/actions/starter-workflows/main/pages/static.yml
- https://raw.githubusercontent.com/actions/starter-workflows/main/pages/jekyll-gh-pages.yml
- https://raw.githubusercontent.com/actions/deploy-pages/main/README.md
- https://raw.githubusercontent.com/actions/deploy-pages/v5/action.yml
- https://raw.githubusercontent.com/actions/upload-pages-artifact/main/README.md
- https://raw.githubusercontent.com/actions/upload-pages-artifact/v5/action.yml
- https://raw.githubusercontent.com/actions/configure-pages/main/README.md
- https://raw.githubusercontent.com/actions/configure-pages/main/action.yml
- https://raw.githubusercontent.com/actions/checkout/main/README.md
- https://raw.githubusercontent.com/actions/checkout/main/action.yml
- https://raw.githubusercontent.com/actions/checkout/main/CHANGELOG.md
- https://github.com/actions/deploy-pages/tags
- https://raw.githubusercontent.com/DeLaGuardo/setup-clojure/13/action.yml
- https://www.indexnow.org/documentation
