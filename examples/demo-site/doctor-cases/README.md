# Doctor cases

Filenames that are supposed to **fail**. They live here, outside `content/`, so
the demo site still builds — a near-miss language suffix is a hard error by
design (DESIGN.md §6.1), so a copy inside `content/` would break CI on every run
rather than demonstrating anything.

To see the error, copy one into `content/01.Guide/10.Basics/` (dropping the
`.disabled` suffix) and run `bb doctor`.

| File | Expected |
|---|---|
| `01.article.zh-Hanz.md.disabled` | error: unknown language `zh-Hanz` — did you mean `zh-Hans`? |
| `01.article.zh-CN.md.disabled` | error: `zh-CN` is in the confusables set derived from `:langs` |
| `01.Setup.md.disabled`, `01.Teardown.md.disabled` | error: duplicate sidebar number 1 for *different* articles (M1). `10.Basics/` already has `01.getting-started.md`, so **either one alone** collides; copy both to see three identities named in one error. |

The third row is the point of v2.1's M1 correction. Note the contrast with
`02.conventions.md` / `.zh-Hans.md` / `.zh-Hant.md`, which are checked into
`content/` and also share a number — and must **not** error, because same
number *and* same base name means one article with three language variants.
The rule keys on identity, not on the number alone; the unscoped vdoing rule
would fail the build on every translated article in the tree.

`test/clogem/scan_test.clj` asserts all of these, so they are checked on every
push rather than only when a human runs the copy-and-see recipe.
