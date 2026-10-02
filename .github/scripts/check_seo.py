#!/usr/bin/env python3
# Copyright (c) 2026 clogem-press contributors. EPL-2.0 (see LICENSE).
"""Assert the SEO output of a root-base build (DESIGN.md §6.6, D-P3-1…4).

Usage: check_seo.py <dist> <site-url> <lang>...

  - every page's canonical is absolute, on <site-url>, and names the page itself
    (redirect stubs — `noindex` — carry none);
  - hreflang reciprocity: every page named in a set lists the same set back;
  - feed.xml exists for every language and parses as Atom;
  - sitemap.xml exists, parses, and lists exactly the indexable pages;
  - robots.txt is present and names the sitemap.

Standard library only, so it runs on a bare ubuntu-latest runner.
"""
import html.parser
import os
import sys
import urllib.parse
import xml.etree.ElementTree as ET

ATOM = "{http://www.w3.org/2005/Atom}"
SM = "{http://www.sitemaps.org/schemas/sitemap/0.9}"
XH = "{http://www.w3.org/1999/xhtml}"

errors = []


def fail(msg):
    errors.append(msg)
    print(f"::error::{msg}")


class Head(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.links, self.metas, self.html_lang, self.in_head = [], [], None, False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "html":
            self.html_lang = a.get("lang")
        elif tag == "head":
            self.in_head = True
        elif tag == "link" and self.in_head:
            self.links.append(a)
        elif tag == "meta" and self.in_head:
            self.metas.append(a)

    def handle_endtag(self, tag):
        if tag == "head":
            self.in_head = False


def url_to_file(dist, site, url):
    if not url.startswith(site + "/"):
        return None
    rel = urllib.parse.unquote(url[len(site) + 1:])
    return os.path.normpath(os.path.join(dist, rel, "index.html"))


def main(dist, site, langs):
    site = site.rstrip("/")
    pages, sets = {}, {}
    for root, _, files in os.walk(dist):
        for f in files:
            if f != "index.html":
                continue
            path = os.path.normpath(os.path.join(root, f))
            h = Head()
            with open(path, encoding="utf-8") as fh:
                h.feed(fh.read())
            pages[path] = h

    indexable = set()
    for path, h in pages.items():
        stub = any(m.get("name") == "robots" and "noindex" in m.get("content", "") for m in h.metas)
        canon = [l["href"] for l in h.links if l.get("rel") == "canonical"]
        if stub:
            if canon:
                fail(f"{path}: a redirect stub carries a canonical")
            continue
        indexable.add(path)
        if len(canon) != 1:
            fail(f"{path}: {len(canon)} canonical links")
            continue
        if url_to_file(dist, site, canon[0]) != path:
            fail(f"{path}: canonical {canon[0]} is not absolute on {site} or not this page")
        alts = {l["hreflang"]: l["href"] for l in h.links
                if l.get("rel") == "alternate" and l.get("hreflang") and not l.get("type")}
        if alts:
            sets[path] = alts

    for path, alts in sets.items():
        if "x-default" not in alts:
            fail(f"{path}: hreflang set has no x-default")
        for lang, href in alts.items():
            if lang == "x-default":
                continue
            other = url_to_file(dist, site, href)
            if other not in sets:
                fail(f"{path}: hreflang {lang} → {href}, which has no hreflang set")
            elif sets[other] != alts:
                fail(f"{path}: hreflang {lang} → {href}, which lists a different set")
    if not sets:
        fail("no page carries an hreflang set")

    total = 0
    for lang in langs:
        rel = "feed.xml" if lang == langs[0] else os.path.join(lang, "feed.xml")
        f = os.path.join(dist, rel)
        try:
            feed = ET.parse(f).getroot()
        except (OSError, ET.ParseError) as e:
            fail(f"{rel}: {e}")
            continue
        if feed.tag != ATOM + "feed":
            fail(f"{rel}: root is {feed.tag}, not an Atom feed")
        for req in ["id", "title", "updated", "author"]:
            if feed.find(ATOM + req) is None:
                fail(f"{rel}: no <{req}>")
        entries = feed.findall(ATOM + "entry")
        total += len(entries)  # a language with no articles yet has an empty feed
        for e in entries:
            for req in ["id", "title", "updated", "link"]:
                if e.find(ATOM + req) is None:
                    fail(f"{rel}: an entry has no <{req}>")

    if not total:
        fail("every feed is empty")

    try:
        sm = ET.parse(os.path.join(dist, "sitemap.xml")).getroot()
        locs = {url_to_file(dist, site, u.findtext(SM + "loc")) for u in sm.findall(SM + "url")}
        if locs != indexable:
            fail(f"sitemap.xml: {len(locs ^ indexable)} page(s) differ from the indexable pages")
    except (OSError, ET.ParseError) as e:
        fail(f"sitemap.xml: {e}")

    try:
        with open(os.path.join(dist, "robots.txt"), encoding="utf-8") as fh:
            if f"Sitemap: {site}/sitemap.xml" not in fh.read():
                fail("robots.txt does not name the sitemap")
    except OSError as e:
        fail(f"robots.txt: {e}")

    print(f"SEO OK: {len(indexable)} indexable pages, {len(sets)} hreflang sets, "
          f"{len(langs)} feeds" if not errors else f"{len(errors)} SEO error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
