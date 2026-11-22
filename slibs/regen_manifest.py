#!/usr/bin/env python3
"""Regenerate app/src/main/assets/slibs/slibs_manifest.txt from the
SURVIVING per-library assets (core_classes.txt + .pd abstractions).

The 2026-11-16 incident: build_slibs.py rewrites the manifest from the
classes it compiled; with the gutted srcrepo only tracker+pocketpd
contributed, so the manifest shrank to 12 lines and the editor marked
every cyclone/zexy/else/diy2 class as unknown (red boxes, autocomplete
loss). This script rebuilds the full manifest from assets that survived:
each library's core_classes.txt (compiled classes -> <lib>_core.so) and,
for abstraction-only libraries (diy2), the .pd file names themselves.

Re-run after any manual asset surgery; build_slibs.py regenerates the
same file properly once real sources are back.
"""
import os

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLIBS = os.path.join(PROJ, "app/src/main/assets/slibs")
ABI = "arm64-v8a"
# Slibs.PRIORITY order (else is a leftover lib dir kept for its intact blob)
PRIORITY = ["diy2", "else", "cyclone", "zexy", "iemlib", "fftease",
            "bsaylor", "creb", "cxc", "osc", "midifile", "binfile",
            "tracker", "pocketpd"]
# abstraction-only libraries: every <name>.pd IS a class (build_slibs
# appends these only when cfg src is empty)
ABSTRACTION_ONLY = {"diy2"}

lines = []
for lib in PRIORITY:
    d = os.path.join(SLIBS, ABI, lib)
    if not os.path.isdir(d):
        continue
    cc = os.path.join(d, "core_classes.txt")
    if os.path.isfile(cc):
        for cls in open(cc, encoding="utf-8", errors="replace").read().split():
            lines.append((lib, cls, "%s_core.so" % lib))
    if lib in ABSTRACTION_ONLY:
        for f in sorted(os.listdir(d)):
            if f.endswith(".pd"):
                lines.append((lib, f[:-3], f))

out = os.path.join(SLIBS, "slibs_manifest.txt")
seen = list(dict.fromkeys(lines))
with open(out, "w", newline="\n") as f:
    for lib, cls, fn in seen:
        f.write("%s\t%s\t%s\n" % (lib, cls, fn))
print("regen_manifest: %d entries -> %s" % (len(seen), out))
