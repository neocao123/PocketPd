#!/usr/bin/env python3
"""dep_check.py - which classes do the shipped else abstractions (arp,
arpeggiator, superosc~, superosc.unit, supersaw~...) reference that the
runtime does NOT provide (vanilla 0.55 + all slib compiled classes +
all shipped .pd abstractions)?"""
import re, os, sys

A = "C:/Projects/26PDAndroid/app/src/main/assets/slibs/arm64-v8a"
META = "C:/Projects/26PDAndroid/app/src/main/java/com/pocketpd/app/patch/PdObjectMeta.kt"

# 1. vanilla classes: quoted strings inside the knownClasses init blocks
meta = open(META, encoding="utf-8").read()
van = set(re.findall(r"\S+", open("C:/Projects/26PDAndroid/slibs/van055.txt", encoding="utf-8").read()))
van |= {"pd", "clone", "array", "text", "scalar", "struct", "floatatom", "symbolatom",
        "listbox", "bng", "tgl", "hslider", "vslider", "hradio", "vradio", "canvas",
        "cnv", "vu", "gatom", "graph", "pd", "msg"}

# 2. compiled slib classes
compiled = set()
for lib in os.listdir(A):
    cc = os.path.join(A, lib, "core_classes.txt")
    if os.path.isfile(cc):
        compiled |= set(open(cc, encoding="utf-8").read().split())

# 3. shipped .pd abstractions (any lib dir + root)
pdfiles = set()
for root, dirs, files in os.walk(A):
    for f in files:
        if f.endswith(".pd"):
            pdfiles.add(f[:-3])

have = van | compiled | pdfiles

# 4. scan target files recursively (follow else/... abstractions we ship)
seen, missing, deps = set(), {}, {}
def scan(path, top):
    if path in seen:
        return
    seen.add(path)
    txt = open(path, encoding="utf-8", errors="replace").read()
    names = set()
    for line in txt.splitlines():
        if line.startswith("#X obj ") or line.startswith("#X msg ") or line.startswith("#X floatatom") or line.startswith("#X symbolatom") or line.startswith("#X listbox") or line.startswith("#X text "):
            continue
        if line.startswith("#X obj "):
            pass
    for m in re.finditer(r'^#X obj (\d+) (\d+) (.+?)(?: \d+ \d+ \d+ \d+ \d+ \d+)?;(?: .*)?$', txt, re.M):
        pass
    # simpler: every "#X obj x y <name> <args>;" line -> first token
    for m in re.finditer(r'^#X obj \d+ \d+ ([^;\n]+)', txt, re.M):
        toks = m.group(1).strip().split()
        if not toks:
            continue
        names.add(toks[0])
    for n in sorted(names):
        bare = n.split("/")[-1] if "/" in n else n
        pref = n.split("/")[0] if "/" in n else ""
        ok = False
        nxt = None
        if "/" in n:
            # prefixed spelling: resolves via the slibs ROOT dir file layout
            if os.path.isfile(os.path.join(A, n + ".pd")): ok = True
        else:
            ok = bare in have
        if not ok:
            missing.setdefault(top, set()).add(n)
    # recurse into referenced abstractions that we ship
    for n in names:
        if "/" in n:
            f = os.path.join(A, n + ".pd")
        else:
            f = os.path.join(A, n + ".pd")
            if not os.path.isfile(f):
                for lib in os.listdir(A):
                    c = os.path.join(A, lib, n + ".pd")
                    if os.path.isfile(c):
                        f = c
                        break
        if os.path.isfile(f):
            scan(f, top)
            deps.setdefault(top, set()).add(os.path.relpath(f, A))

targets = sys.argv[1:] or ["else/arp.pd", "else/arpeggiator.pd", "else/superosc~.pd", "else/superosc.unit.pd"]
for t in targets:
    top = t
    scan(os.path.join(A, t), top)
    print("=====", t)
    print("  files touched:", sorted(deps.get(top, [])))
    print("  MISSING:", sorted(missing.get(top, [])))
