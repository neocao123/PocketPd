#!/usr/bin/env python3
"""Lint pd patch files for the classic hand-editing mistakes:
 - a raw (unescaped) ';' in the middle of a statement (pd ends the
   statement there; the rest becomes garbage - 0.17.84 lesson)
 - statements that do not start with a known directive (the garbage
   left behind by the above)
 - #X connect indices pointing at objects that do not exist (object
   index = order of object-creating statements; inserting an object
   without renumbering shifts them all - 0.17.84 lesson)
 - raw (unescaped) ',' inside #X msg (message separator)
Exit code 1 on any error.
"""
import glob
import os
import sys

OBJECT_STMTS = ("#X obj", "#X msg", "#X text", "#X floatatom",
                "#X symbolatom", "#X listbox", "#X bng", "#X tgl",
                "#X cnv", "#X vradio", "#X hradio", "#X number",
                "#X scalar", "#X restore", "#X pointer")
KNOWN_PREFIXES = ("#N", "#X", "#C")


def statements(text):
    """Split on unescaped ';' - '; ' never separates."""
    out, cur = [], []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == ";" and (i == 0 or text[i - 1] != chr(92)):
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    if cur:
        out.append("".join(cur))
    return out


def lint(path):
    errs = []
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    nobj = 0
    seen_canvas = False
    for st in statements(text):
        st = st.strip(chr(10) + " ").strip()
        if not st:
            continue
        first = st.split()[0] if st.split() else ""
        if first == "#N":
            seen_canvas = True
            continue
        if not seen_canvas:
            continue  # leading comments etc.
        if not any(first == p or (p == "#X" and first.startswith("#X"))
                   for p in KNOWN_PREFIXES):
            errs.append("non-directive statement (leftover garbage?): %.80s"
                        % st.replace(chr(10), " / "))
            continue
        if first in OBJECT_STMTS:
            nobj += 1
        if first == "#X text":
            # an embedded raw ';' cannot exist by construction of
            # statements(); what to check here is nothing extra
            pass
        if first == "#X msg":
            # raw comma inside a msg = message separator
            if ", " in st or st.endswith(","):
                body = st[len("#X msg "):]
                if "," in body:
                    errs.append("raw comma in #X msg: %.60s" % st)
        if first == "#X connect":
            t = st.split()
            # #X connect from outlet to inlet
            try:
                a, c = int(t[2]), int(t[4])
            except (IndexError, ValueError):
                errs.append("malformed connect: %.60s" % st)
                continue
            if a >= nobj or c >= nobj:
                errs.append("connect out of range (%d->%d, %d objects): %s"
                            % (a, c, nobj, st))
    return errs


def main():
    roots = sys.argv[1:] or [
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "app/src/main/assets")]
    bad = 0
    nfiles = 0
    for root in roots:
        # accept both directories and single files - a file path used to
        # fall through the ** glob silently and lint NOTHING (0.17.120
        # lesson: "lint clean" on four file paths was an empty scan)
        if os.path.isfile(root):
            files = [root]
        else:
            files = sorted(glob.glob(os.path.join(root, "**", "*.pd"),
                                     recursive=True))
        for f in files:
            nfiles += 1
            errs = lint(f)
            if errs:
                bad += 1
                print("== %s" % f)
                for e in errs:
                    print("   -", e)
    print("lint: %d file(s) checked, %d with errors" % (nfiles, bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
