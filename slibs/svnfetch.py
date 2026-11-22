#!/usr/bin/env python3
"""Fetch pd-extended-era externals sources from pure-data SourceForge SVN."""
import os, re, sys, time, urllib.request

BASE = "https://svn.code.sf.net/p/pure-data/svn/trunk/externals/"
EXTS = (".c", ".h", ".txt", ".pd", "makefile")
HREF = re.compile(r'href="([^"?][^"]*)"')

targets = [
    ("zexy", "zexy"),
    ("iemlib", "iemlib"),
    ("cyclone", "cyclone"),
    ("creb", "creb"),
    ("cxc", "cxc"),
    ("bsaylor", "bsaylor"),
    ("fftease", "fftease"),
    ("mrpeach/osc", "osc"),
    ("mrpeach/midifile", "midifile"),
    ("mrpeach/binfile", "binfile"),
]

def get(url, binary=False, tries=3):
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                data = r.read()
            return data if binary else data.decode("utf-8", "replace")
        except Exception as e:
            if i == tries - 1:
                print(f"FAIL {url}: {e}", flush=True)
                return None
            time.sleep(1.0)

def crawl(url, out, depth=0):
    if depth > 6:
        return
    listing = get(url)
    if listing is None:
        return
    for name in HREF.findall(listing):
        if name.startswith(("../", "?")) or name in ("dirindex",):
            continue
        full = url + name
        if name.endswith("/"):
            crawl(full, out, depth + 1)
        else:
            fn = os.path.join(out, name)
            if os.path.exists(fn):
                continue
            low = name.lower()
            if not any(low == e or low.endswith(e) for e in EXTS):
                continue
            if low.endswith("-help.pd"):
                continue
            data = get(full, binary=True)
            if data is not None:
                with open(fn, "wb") as f:
                    f.write(data)
            time.sleep(0.12)

root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/srcrepo"
for svn_sub, local in targets:
    out = os.path.join(root, local)
    os.makedirs(out, exist_ok=True)
    print(f"=== {svn_sub} -> {out}", flush=True)
    crawl(BASE + svn_sub + "/", out)
print("ALL DONE", flush=True)
