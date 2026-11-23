#!/usr/bin/env python3
"""audit_zexy.py - macro-aware io diff for zexy (zexy_new registers classes,
so the generic audit_io.py CLASS_NEW scan is blind to the whole library -
0.17.81). For every <file>.c in a zexy src/ tree it pairs each
zexy_new("<name>", <ctor>, ...) with the <ctor> body and counts
inlet_new/floatinlet_new/symbolinlet_new/pointerinlet_new/outlet_new calls
(+ CLASS_MAINSIGNALIN flag + loop-driven io flag). Run against two trees to
get the per-class drift:

    python audit_zexy.py <old_src_dir> <new_src_dir>
"""
import os, re, sys

IO_IN = ("inlet_new", "floatinlet_new", "symbolinlet_new",
         "pointerinlet_new", "listinlet_new")
ZEXY_NEW = re.compile(r'zexy_new\s*\(\s*"([^"]+)"\s*,\s*(\w+)\s*,')
FN = re.compile(r'\b(?:static\s+)?(?:void\s*\*|t_\w+\s*\*?|void|int)\s*(\w+)\s*\([^;{]*\)\s*\{')


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    return text


def body_of(text, start):
    i = text.index('{', start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == '{':
            depth += 1
        elif text[j] == '}':
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    return None


def ctor_body(text, name):
    for m in FN.finditer(text):
        if m.group(1) == name:
            return body_of(text, m.start())
    return None


def scan(srcdir):
    out = {}
    for f in sorted(os.listdir(srcdir)):
        if not f.endswith(".c"):
            continue
        text = strip_comments(open(os.path.join(srcdir, f), encoding="utf-8",
                                   errors="replace").read())
        for m in ZEXY_NEW.finditer(text):
            name, ctor = m.group(1), m.group(2)
            body = ctor_body(text, ctor)
            if body is None:
                out[name] = ("?", 0, False, f)
                continue
            nin = 1 + sum(len(re.findall(r'\b%s\s*\(' % c, body))
                          for c in IO_IN)
            nout = len(re.findall(r'\boutlet_new\s*\(', body))
            loop = bool(re.search(r'\b(for|while)\s*\(', body)
                        and re.search(r'\b(inlet_new|outlet_new|'
                                      r'floatinlet_new|symbolinlet_new)'
                                      r'\s*\(', body))
            out[name] = (nin, nout, loop, f)
    return out


def main():
    old, new = scan(sys.argv[1]), scan(sys.argv[2])
    print("== classes only in NEW tree:")
    for k in sorted(set(new) - set(old)):
        print("  + %s = %s in / %s out %s" % (k, new[k][0], new[k][1],
                                              "(VARIABLE)" if new[k][2] else ""))
    print("== classes only in OLD tree (dropped upstream):")
    for k in sorted(set(old) - set(new)):
        print("  - %s" % k)
    print("== io drift (old -> new):")
    for k in sorted(set(old) & set(new)):
        o, n = old[k], new[k]
        if (o[0], o[1], o[2]) != (n[0], n[1], n[2]):
            print("  ~ %-16s %s/%s%s -> %s/%s%s   [%s]" % (
                k, o[0], o[1], "V" if o[2] else "", n[0], n[1],
                "V" if n[2] else "", n[3]))


if __name__ == "__main__":
    main()
