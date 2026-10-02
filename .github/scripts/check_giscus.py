#!/usr/bin/env python3
# Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
"""Assert one giscus thread per article identity (DESIGN.md §6.8, D-P3-15).

Usage: check_giscus.py <dist> <base> <lang>=<giscus-lang>...

  e.g. check_giscus.py dist / en=en zh-Hans=zh-CN zh-Hant=zh-TW ms=en ta=en

An identity group is every page whose hreflang `x-default` names the same
bare identity URL. For every group of ARTICLE pages (`<body class="…
page-article">`):

  - every variant carries exactly one giscus client script;
  - all of them carry the same `data-term`, which is the identity's
    permalink with no base and no language prefix (`/pages/xxxxxx/`);
  - `data-lang` is the `:giscus` value of the page's own language
    (`<html lang>`), as given on the command line;
  - `data-mapping="specific"` and `data-strict="1"`.

Terms are distinct across groups, and no other page (catalogue, home, index,
redirect stub) carries the script. With part A's hreflang checks and part B's
per-language index check this completes §8's Phase 3 exit criterion.

Standard library only, so it runs on a bare ubuntu-latest runner.
"""
import html.parser
import os
import sys
import urllib.parse

CLIENT = "https://giscus.app/client.js"
errors = []


def fail(msg):
    errors.append(msg)
    print(f"::error::{msg}")


class Page(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.html_lang, self.body_class, self.x_default, self.scripts = None, "", None, []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.html_lang = a.get("lang")
        elif tag == "body":
            self.body_class = a.get("class") or ""
        elif tag == "link" and a.get("rel") == "alternate" and a.get("hreflang") == "x-default":
            self.x_default = a.get("href")
        elif tag == "script" and a.get("src") == CLIENT:
            self.scripts.append(a)


def main(dist, base, pairs):
    base = "/" + base.strip("/") + "/" if base.strip("/") else "/"
    giscus_for = {}
    for p in pairs:
        lang, _, g = p.partition("=")
        if not g:
            sys.exit(f"bad argument {p!r}: expected <lang>=<giscus-lang>")
        giscus_for[lang] = g

    groups, articles, others = {}, 0, 0
    for root, _, files in os.walk(dist):
        if os.path.relpath(root, dist).split(os.sep)[0] == "pagefind":
            continue
        for f in files:
            if f != "index.html":
                continue
            path = os.path.join(root, f)
            pg = Page()
            with open(path, encoding="utf-8") as fh:
                pg.feed(fh.read())
            rel = os.path.relpath(path, dist)
            if "page-article" not in pg.body_class.split():
                others += 1
                if pg.scripts:
                    fail(f"{rel}: not an article page, but carries {len(pg.scripts)} giscus script(s)")
                continue
            articles += 1
            if not pg.x_default:
                fail(f"{rel}: article page has no hreflang x-default to group it by")
                continue
            groups.setdefault(pg.x_default, []).append((rel, pg))

    by_term = {}
    for xdef, members in sorted(groups.items()):
        # the identity: x-default's path with the base removed
        path = urllib.parse.unquote(urllib.parse.urlsplit(xdef).path)
        identity = "/" + path[len(base):] if path.startswith(base) else None
        if identity is None:
            fail(f"x-default {xdef} is not under the base {base}")
            continue
        terms = set()
        for rel, pg in members:
            if len(pg.scripts) != 1:
                fail(f"{rel}: {len(pg.scripts)} giscus scripts, expected exactly one")
                continue
            s = pg.scripts[0]
            terms.add(s.get("data-term"))
            want = giscus_for.get(pg.html_lang)
            if want is None:
                fail(f"{rel}: <html lang={pg.html_lang!r}> has no giscus mapping on the command line")
            elif s.get("data-lang") != want:
                fail(f"{rel}: data-lang={s.get('data-lang')!r}, expected {want!r} for {pg.html_lang}")
            if s.get("data-mapping") != "specific" or s.get("data-strict") != "1":
                fail(f"{rel}: data-mapping/data-strict are {s.get('data-mapping')!r}/{s.get('data-strict')!r}")
        if len(terms) > 1:
            fail(f"identity {identity}: variants disagree on data-term {sorted(terms)}")
        elif terms:
            term = terms.pop()
            if term != identity:
                fail(f"identity {identity}: data-term is {term!r}, not the bare permalink")
            if term in by_term:
                fail(f"data-term {term!r} is shared by identities {by_term[term]} and {identity}")
            by_term[term] = identity

    if errors:
        sys.exit(f"{len(errors)} giscus problem(s)")
    print(f"giscus OK: {articles} article pages in {len(groups)} identity groups, "
          f"{len(by_term)} distinct terms; {others} other pages carry none")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
