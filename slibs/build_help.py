#!/usr/bin/env python3
"""Assemble app/src/main/assets/help/<lib>/*.pd from the cached pd sources.

Sources (see build_slibs.py for the same trees):
  pdsrc/pd-0.51-4/doc/5.reference          -> help/vanilla
  pd-extended/externals/miXed/doc/help/cyclone -> help/cyclone
  pd-extended/externals/zexy/{reference,abs}   -> help/zexy
  pd-extended/externals/iemlib/iemabs      -> help/iemlib   (*-help.pd only)
  pd-extended/externals/bsaylor            -> help/bsaylor  (*-help.pd only)
  pd-extended/externals/creb/doc           -> help/creb     (*-help.pd only)
  pd-extended/externals/cxc                -> help/cxc      (*-help.pd only)
  pd-extended/externals/mrpeach/{osc,midifile,binfile} -> help/<lib>
Only *.pd files are copied (the app opens .pd patches; texts stay behind).
"""
import os, re, shutil, sys

SRC = "C:/Users/Administrator/AppData/Local/Temp/srcrepo"
EX = SRC + "/pd-extended/externals"
PD = "C:/Users/Administrator/AppData/Local/Temp/pdsrc/pd-0.51-4/doc/5.reference"
PROJ = "C:/Projects/26PDAndroid/app/src/main/assets/help"

HELP_RE = re.compile(r"(-help\.pd$)|(^(help|doc)-.*\.pd$)", re.I)

# (destination lib, source dir, help-only)
TREES = [
    ("vanilla", PD, False),
    ("cyclone", EX + "/miXed/doc/help/cyclone", False),
    ("zexy", EX + "/zexy/reference", False),
    ("zexy", EX + "/zexy/abs", True),
    ("iemlib", EX + "/iemlib/iemabs", True),
    ("bsaylor", EX + "/bsaylor", True),
    ("creb", EX + "/creb/doc", True),
    ("cxc", EX + "/cxc", True),
    ("osc", EX + "/mrpeach/osc", True),
    ("midifile", EX + "/mrpeach/midifile", True),
    ("binfile", EX + "/mrpeach/binfile", True),
    ("diy2", SRC + "/diy2", True),
]

def main():
    if os.path.isdir(PROJ):
        shutil.rmtree(PROJ)
    total = 0
    for lib, src, helponly in TREES:
        if not os.path.isdir(src):
            print("MISSING source: %s" % src)
            continue
        dst = os.path.join(PROJ, lib)
        os.makedirs(dst, exist_ok=True)
        n = 0
        for f in sorted(os.listdir(src)):
            if not f.lower().endswith(".pd"):
                continue
            if helponly and not HELP_RE.search(f):
                continue
            shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
            n += 1
        total += n
        print("%-9s %4d patches" % (lib, n))
    print("total: %d" % total)

if __name__ == "__main__":
    main()
