#!/usr/bin/env python3
"""dep_check2.py - scan HELP patches (help/<lib>/*.pd) for object
references that the runtime cannot resolve: vanilla 0.55 + compiled slib
classes + shipped .pd abstractions (slibs root & lib dirs & help dirs)."""
import re, os, sys

A = "C:/Projects/26PDAndroid/app/src/main/assets/slibs/arm64-v8a"
H = "C:/Projects/26PDAndroid/app/src/main/assets/help"

van = set(re.findall(r"\S+", open("C:/Projects/26PDAndroid/slibs/van055.txt", encoding="utf-8").read()))
van |= {"pd", "clone", "array", "text", "scalar", "struct", "floatatom", "symbolatom",
        "listbox", "bng", "tgl", "hslider", "vslider", "hradio", "vradio", "canvas",
        "cnv", "vu", "gatom", "graph", "msg", "/", "b,", "sel,"}

compiled = set()
for lib in os.listdir(A):
    cc = os.path.join(A, lib, "core_classes.txt")
    if os.path.isfile(cc):
        compiled |= set(w.strip("\r") for w in open(cc, encoding="utf-8").read().split())

pdfiles = set()
for root, dirs, files in os.walk(A):
    for f in files:
        if f.endswith(".pd"):
            pdfiles.add(f[:-3])
for root, dirs, files in os.walk(H):
    for f in files:
        if f.endswith(".pd"):
            pdfiles.add(f[:-3])

have = van | compiled | pdfiles

missing = {}
def scan(path, top, seen):
    if path in seen: return
    seen.add(path)
    txt = open(path, encoding="utf-8", errors="replace").read()
    for m in re.finditer(r'^#X obj \d+ \d+ ([^;\n]+)', txt, re.M):
        toks = m.group(1).strip().split()
        if not toks: continue
        n = toks[0]
        bare = n.split("/")[-1]
        ok = n in have or bare in have
        if not ok:
            missing.setdefault(top, set()).add(n)
    # recurse into prefixed abstractions shipped under slibs
    for m in re.finditer(r'^#X obj \d+ \d+ ([^;\n]+)', txt, re.M):
        n = m.group(1).strip().split()[0] if m.group(1).strip() else ""
        if "/" in n:
            f = os.path.join(A, n + ".pd")
            if os.path.isfile(f): scan(f, top, seen)

targets = [
    "else/arpeggiator-help.pd", "else/superosc~-help.pd",
]
for t in targets:
    p = os.path.join(H, t)
    seen = set()
    scan(p, t, seen)
    print("=====", t)
    print("  MISSING:", sorted(missing.get(t, [])))
