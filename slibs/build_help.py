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
# cyclone 0.9-5 (0.18.13): porres moved help into documentation/help_files
CYC = SRC + "/pd-cyclone-0.9-5"
PROJ = "C:/Projects/26PDAndroid/app/src/main/assets/help"

HELP_RE = re.compile(r"(-help\.pd$)|(^(help|doc)-.*\.pd$)", re.I)

# (destination lib, source dir, help-only)
TREES = [
    ("vanilla", PD, False),
    ("cyclone", CYC + "/documentation/help_files", False),
    # zexy 2.4.4 master (0.18.15): reference/ = the -help set (76 patches);
    # abs/ NOT shipped (the build ships src only, parity with the old
    # baseline), so its help would document unavailable objects
    ("zexy", SRC + "/zexy-current/reference", False),
    # iemlib 1.17 (0.18.16): canonical help/ dir (81 *-help.pd; the old
    # pd-extended iemabs mixed help into the abs tree)
    ("iemlib", SRC + "/iemlib-current/help", True),
    ("bsaylor", EX + "/bsaylor", True),
    ("creb", EX + "/creb/doc", True),
    ("cxc", EX + "/cxc", True),
    ("osc", EX + "/mrpeach/osc", True),
    ("midifile", EX + "/mrpeach/midifile", True),
    ("binfile", EX + "/mrpeach/binfile", True),
    # fftease 3.0.1 (0.18.14): upstream helpfiles ship with the sources;
    # pvgrain~-help.pd references Piano.aif (copied from sound/ after this
    # tree runs - see the MEDIA post-pass below, only help dir is on the pd
    # search path at open time)
    ("fftease", SRC + "/fftease-3.0.1/fftease-helpfiles", True),
    ("diy2", SRC + "/diy2", True),
]

# (lib dir under PROJ, file relative to a source tree root, source tree) -
# media referenced by help patches must live IN the help dir (AGENTS rule)
HELP_MEDIA = [
    ("fftease", "Piano.aif", SRC + "/fftease-3.0.1/sound/Piano.aif"),
]

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    if not only and os.path.isdir(PROJ):
        shutil.rmtree(PROJ)
    total = 0
    for lib, src, helponly in TREES:
        if only and only != lib:
            continue
        if not os.path.isdir(src):
            print("MISSING source: %s" % src)
            continue
        dst = os.path.join(PROJ, lib)
        if os.path.isdir(dst):
            shutil.rmtree(dst)  # only-mode refresh: stale help must go too
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
    for lib, fname, srcpath in HELP_MEDIA:
        if only and only != lib:
            continue
        dst = os.path.join(PROJ, lib, fname)
        if os.path.isfile(srcpath):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(srcpath, dst)
            print("media: %s/%s" % (lib, fname))
    print("total: %d" % total)

if __name__ == "__main__":
    main()
