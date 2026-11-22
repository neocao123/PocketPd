#!/usr/bin/env python3
"""check_pd_connects.py - verify that every #X connect in a .pd file points
at connectable objects. Models pd's index space: obj/msg/atom boxes AND
#X text comments each occupy one index; a #N canvas...#X restore block is
ONE index. A connect touching a #X text comment is the classic off-by-one
bug (pd rejects it as "text->X connection failed") - the 0.17.152/0.17.155
superosc~.pd incident twice over.

Usage: python check_pd_connects.py <file.pd> [more.pd ...]
"""
import sys


def split_statements(text):
    out, sb, esc = [], [], False
    for ch in text:
        if esc:
            sb.append(ch); esc = False; continue
        if ch == '\\':
            sb.append(ch); esc = True; continue
        if ch == ';':
            out.append("".join(sb)); sb = []; continue
        sb.append(ch)
    return out


def check(path):
    stmts = split_statements(open(path, encoding="utf-8").read())
    boxes = []          # (kind, summary) per index; kind in obj/msg/atom/text/canvas
    errors = []
    header_seen = False
    i = 0
    while i < len(stmts):
        st = stmts[i].strip()
        tok = st.split()
        if tok[0:2] == ["#N", "canvas"]:
            if not header_seen:
                # the doc's own root header - not an object, occupies no index
                header_seen = True
                i += 1
                continue
            depth, j = 1, i + 1
            while j < len(stmts) and depth > 0:
                t2 = stmts[j].split()
                if t2[0:2] == ["#N", "canvas"]:
                    depth += 1
                elif t2[0:2] == ["#X", "restore"]:
                    depth -= 1
                j += 1
            boxes.append(("canvas", " ".join(tok[2:8])))
            i = j
            continue
        if tok[0] == "#X":
            kind = tok[1] if len(tok) > 1 else ""
            if kind in ("obj", "msg", "floatatom", "symbolatom", "listbox"):
                boxes.append((kind, " ".join(tok[4:8])))
                i += 1
                continue
            if kind == "text":
                boxes.append(("text", " ".join(tok[4:10])))
                i += 1
                continue
            if kind == "connect":
                if len(tok) < 6:
                    errors.append(f"{path}: malformed connect: {st}")
                    i += 1
                    continue
                f, fo, t, ti = int(tok[2]), int(tok[3]), int(tok[4]), int(tok[5])
                for role, idx in (("from", f), ("to", t)):
                    if idx >= len(boxes):
                        errors.append(f"{path}: connect {f} {fo} {t} {ti}: "
                                      f"{role} index {idx} out of range ({len(boxes)} boxes)")
                    elif boxes[idx][0] == "text":
                        errors.append(f"{path}: connect {f} {fo} {t} {ti}: {role} is a "
                                      f"TEXT comment ({boxes[idx][1][:30]}...) - off-by-one bug")
                i += 1
                continue
        i += 1
    texts = sum(1 for k, _ in boxes if k == "text")
    return len(boxes), texts, errors


def main():
    bad = 0
    for path in sys.argv[1:]:
        n, texts, errors = check(path)
        status = "OK" if not errors else f"{len(errors)} ERROR(S)"
        print(f"{path}: {n} boxes ({texts} text), {status}")
        for e in errors:
            print("  !!", e)
            bad = 1
    return bad


if __name__ == "__main__":
    sys.exit(main())
