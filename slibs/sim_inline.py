#!/usr/bin/env python3
"""sim_inline.py - faithful Python port of the app's parse -> inline ->
toText pipeline (PatchModel.kt + PdEngine.kt), to inspect the exact pd
session text a user patch with [arp]/[superosc~] produces - statically,
without touching any device. Rebuilt 0.17.146 after the file was found
truncated (the old splice section died mid-write and main() was gone).

Usage: python sim_inline.py <patch.pd> [> out.txt]
"""
import re, os, sys

A = "C:/Projects/26PDAndroid/app/src/main/assets/slibs/arm64-v8a"
H = "C:/Projects/26PDAndroid/app/src/main/assets/help"


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
    if "".join(sb).strip():
        out.append("".join(sb))
    return out


def tokenize(stmt):
    return re.split(r"\s+", stmt.strip()) if stmt.strip() else []


OBJ_IDS = iter(range(1000, 100000))
def oid(): return next(OBJ_IDS)


class Obj:
    def __init__(self, oid_, kind, args, sub=None, x=0, y=0):
        self.id = oid_; self.kind = kind; self.args = list(args); self.sub = sub
        self.x = x; self.y = y


class Doc:
    def __init__(self):
        self.header = "#N canvas 0 0 450 300 10;"
        self.items = []   # ("obj", Obj) | ("conn", tokens) | ("raw", line)
        self.order = []   # object ids in creation order
        self.conns = []


def parse(text):
    doc = Doc()
    statements = split_statements(text)
    header_seen = False
    i = 0
    while i < len(statements):
        st = statements[i]
        tok = tokenize(st)
        if not tok: i += 1; continue
        if not header_seen and tok[0] == "#N" and tok[1:2] == ["canvas"]:
            doc.header = st.strip() + ";"
            header_seen = True; i += 1; continue
        if tok[0] == "#N" and tok[1:2] == ["canvas"]:
            lines, depth, j = [], 0, i
            while j < len(statements):
                t2 = tokenize(statements[j])
                if t2[0:2] == ["#N", "canvas"]: depth += 1
                if t2[0:2] == ["#X", "restore"]: depth -= 1
                lines.append(statements[j].strip() + ";")
                j += 1
                if depth <= 0 and j > i + 1: break
            name = "subpatch"
            for ln in lines:
                if ln.startswith("#X restore"):
                    tk = tokenize(ln)
                    if "pd" in tk:
                        k = tk.index("pd")
                        name = " ".join(tk[k+1:]) or "subpatch"
                    break
            o = Obj(oid(), "OBJECT", ["pd", name], lines)
            doc.items.append(("obj", o)); doc.order.append(o.id)
            i += (j - i); continue
        if tok[0] == "#X":
            kind = tok[1] if len(tok) > 1 else ""
            if kind in ("obj", "msg", "text", "floatatom", "symbolatom", "listbox"):
                x = int(tok[2]) if len(tok) > 2 and tok[2].lstrip("-").isdigit() else 0
                y = int(tok[3]) if len(tok) > 3 and tok[3].lstrip("-").isdigit() else 0
                kmap = {"obj": "OBJECT", "msg": "MSG", "text": "COMMENT",
                        "floatatom": "FLOATATOM", "symbolatom": "SYMBOLATOM",
                        "listbox": "LISTBOX"}
                inc = kind in ("floatatom", "symbolatom", "listbox")
                args = tok[4:]
                if inc: args = [kind] + args
                o = Obj(oid(), kmap[kind], args)
                o.x, o.y = x, y
                doc.items.append(("obj", o)); doc.order.append(o.id)
                i += 1; continue
            if kind == "connect":
                doc.items.append(("conn", tok))
                i += 1; continue
        doc.items.append(("raw", st.strip() + ";"))
        i += 1
    conns = []
    for it in doc.items:
        if it[0] == "conn":
            tk = it[1]
            f = int(tk[2]); fo = int(tk[3]); t = int(tk[4]); ti = int(tk[5])
            fid = doc.order[f] if f < len(doc.order) else None
            tid = doc.order[t] if t < len(doc.order) else None
            if fid is None or tid is None:
                print("!! connect index out of range", tk)
            conns.append([fid, fo, tid, ti])
    doc.conns = conns
    return doc


def locate(name):
    f = os.path.join(A, name + ".pd")
    if os.path.isfile(f): return f
    for lib in sorted(os.listdir(A)):
        c = os.path.join(A, lib, name + ".pd")
        if os.path.isfile(c): return c
    f = os.path.join(H, name + ".pd")
    if os.path.isfile(f): return f
    return None


def resolve(name):
    return locate(name) or locate("else/" + name) if not os.path.isfile(
        os.path.join(A, name + ".pd")) else os.path.join(A, name + ".pd")


def substitute_tokens(tokens, uid, args, literal):
    out = []
    in_send = False
    for tok0 in tokens:
        t = tok0.replace("\\$", "$")
        t = t.replace("$0", uid)
        t = re.sub(r"\$[1-9](?=-)", uid, t)
        if literal:
            if t == "\\;": in_send = True
            elif t == "\\,": pass
            elif in_send:
                t = re.sub(r"\$[1-9]", uid, t)
        else:
            for m in re.finditer(r"\$(\d)", tok0):
                n = int(m.group(1))
                rep = args[n-1] if n-1 < len(args) else "0"
                t = t.replace(m.group(0), rep)
        out.append(t)
    return out


def arg_message_groups(tokens, sep):
    """Port of PdEngine.argMessageGroups: one [args] box -> initmess groups."""
    if not tokens: return [["list"]]
    if sep is None: return [["list"] + list(tokens)]
    groups, pre, i = [], [], 0
    while i < len(tokens):
        t = tokens[i]
        if t and t[0] == sep:
            if pre:
                groups.append(["list"] + pre); pre = []
            g = [t]; i += 1
            while i < len(tokens) and tokens[i] and tokens[i][0] != sep:
                g.append(tokens[i]); i += 1
            groups.append(g)
        else:
            pre.append(t); i += 1
    if pre: groups.append(["list"] + pre)
    if not groups: groups.append(["list"])
    return groups


ARGS_CLASSES = {"args", "else/args"}

def substitute_line(line, uid, args):
    toks = tokenize(line.rstrip(";"))
    if len(toks) >= 5 and toks[0] == "#X" and toks[1] == "obj" and \
            toks[4] in ARGS_CLASSES:
        resolved = substitute_tokens(args, uid, [], False)
        body = ["list"] if not resolved else ["list"] + resolved
        head = substitute_tokens(toks[:4], uid, [], False)
        return " ".join(head + ["initmess"] + body) + ";"
    literal = len(toks) >= 2 and toks[0] == "#X" and toks[1] == "msg"
    return " ".join(substitute_tokens(toks, uid, args, literal)) + ";"


DELAY = {"delwrite~", "delread4~", "vd~", "delread~"}


def subpatch_block_restore(lines, open_, until=None):
    """Index of the #X restore closing the #N canvas block opening at open_
    (depth-aware; -1 when never closed). Port of PdEngine.subpatchBlockRestore."""
    if until is None: until = len(lines)
    depth = 1
    for j in range(open_ + 1, until):
        t = lines[j].lstrip()
        if t.startswith("#N canvas"): depth += 1
        elif t.startswith("#X restore"):
            depth -= 1
            if depth == 0: return j
    return -1


def prefix_block_delay_names(lines, uid):
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        t = line.lstrip()
        if t.startswith("#N canvas"):
            close = subpatch_block_restore(lines, i)
            out.append(line)
            if close >= 0:
                out.extend(prefix_block_delay_names(lines[i+1:close], uid))
                out.append(lines[close])
                i = close + 1
            else:
                out.extend(prefix_block_delay_names(lines[i+1:], uid))
                i = len(lines)
            continue
        if t.startswith("#X obj"):
            toks = tokenize(t.rstrip(";"))
            if len(toks) > 5 and toks[4] in DELAY and \
                    not toks[5].startswith(uid + "-"):
                toks[5] = uid + "-" + toks[5]
                out.append(" ".join(toks) + ";")
                i += 1
                continue
        out.append(line)
        i += 1
    return out


def inline_abstraction(doc, box):
    path = resolve(box.args[0])
    if path is None:
        print("!! abstraction not found:", box.args[0]); return
    uid = str(1000 + box.id)
    info = parse(open(path, encoding="utf-8", errors="replace").read())
    idmap, newitems, argextras = {}, [], {}
    for it in info.items:
        if it[0] != "obj": continue
        o = it[1]
        if o.kind == "COMMENT": continue
        if o.args and o.args[0] == "block~": continue
        if o.sub is not None:
            lines = [substitute_line(l, uid, box.args[1:]) for l in o.sub]
            lines = prefix_block_delay_names(lines, uid)
            copy = Obj(oid(), o.kind, substitute_tokens(o.args, uid, box.args[1:], False), lines)
        else:
            copy = Obj(oid(), o.kind, substitute_tokens(o.args, uid, box.args[1:], o.kind == "MSG"))
            if copy.kind == "OBJECT" and len(copy.args) > 1 and copy.args[0] in DELAY \
               and not copy.args[1].startswith(uid + "-"):
                copy.args[1] = uid + "-" + copy.args[1]
        copy.x, copy.y = o.x, o.y
        # [args] -> initmess groups (0.17.145 fix)
        if copy.kind == "OBJECT" and copy.args and copy.args[0] in ARGS_CLASSES:
            sep = o.args[1][0] if len(o.args) > 1 and o.args[1] else None
            resolved = substitute_tokens(box.args[1:], uid, [], False)
            groups = arg_message_groups(resolved, sep)
            extras = []
            first = True
            for g in groups:
                if first:
                    copy.args = ["initmess"] + g
                    first = False
                else:
                    extra = Obj(oid(), "OBJECT", ["initmess"] + g)
                    extra.x, extra.y = o.x, o.y + 26 * len(extras)
                    newitems.append(("obj", extra))
                    extras.append(extra.id)
            if extras: argextras[o.id] = extras
        idmap[o.id] = copy.id
        newitems.append(("obj", copy))
    # internal cords, parallel initmess groups duplicate the args cords
    conns = []
    for (f, fo, t, ti) in info.conns:
        if f in idmap and t in idmap:
            conns.append([idmap[f], fo, idmap[t], ti])
            for ex in argextras.get(f, []):
                conns.append([ex, fo, idmap[t], ti])
    # splice parent cords through inlet/outlet port objects
    feed, outs = {}, {}
    def cls(oid_):
        for it in info.items:
            if it[0] == "obj" and it[1].id == oid_: return it[1].args[0] if it[1].args else ""
        return ""
    for (f, fo, t, ti) in info.conns:
        if cls(f) in ("inlet", "inlet~"):
            feed.setdefault((f, fo), []).append((t, ti))
        if cls(t) in ("outlet", "outlet~"):
            outs.setdefault((t, ti), []).append((f, fo))
    ports_in = {o.id: i for i, o in enumerate(
        [it[1] for it in info.items if it[0] == "obj" and it[1].args and it[1].args[0] in ("inlet", "inlet~")])}
    ports_out = {o.id: i for i, o in enumerate(
        [it[1] for it in info.items if it[0] == "obj" and it[1].args and it[1].args[0] in ("outlet", "outlet~")])}
    spliced = []
    for (f, fo, t, ti) in doc.conns:
        if f == box.id and fo in ports_in.values():
            inl = next(o for o, k in ports_in.items() if k == fo)
            conts = feed.get((inl, 0))
            if conts:
                for (t2, ti2) in conts:
                    if t2 in idmap: spliced.append([f, fo, idmap[t2], ti2])
            continue
        if t == box.id and ti in ports_out.values():
            outl = next(o for o, k in ports_out.items() if k == ti)
            srcs = outs.get((outl, ti))
            if srcs:
                for (f2, fo2) in srcs:
                    if f2 in idmap: spliced.append([idmap[f2], fo2, t, ti])
            continue
        spliced.append([f, fo, t, ti])
    doc.items = [it for it in doc.items if not (it[0] == "obj" and it[1].id == box.id)]
    doc.items.extend(newitems)
    doc.conns = conns + spliced
    doc.order = [it[1].id for it in doc.items if it[0] == "obj"]


def escape_tok(tok):
    return tok.replace("$", "\\$") if "$" in tok else tok


def to_text(doc):
    sb = [doc.header]
    index = {}
    i = 0
    for it in doc.items:
        if it[0] == "obj":
            o = it[1]
            index[o.id] = i; i += 1
            if o.sub is not None:
                for ln in o.sub:
                    tt = tokenize(ln.rstrip(";"))
                    if tt[0:2] == ["#X", "restore"] and len(tt) > 3:
                        tt[2] = str(o.x); tt[3] = str(o.y)
                        sb.append(" ".join(tt) + ";")
                    else:
                        sb.append(ln if ln.endswith(";") else ln + ";")
                continue
            kind = {"OBJECT": "obj", "MSG": "msg", "COMMENT": "text",
                    "FLOATATOM": "floatatom", "SYMBOLATOM": "symbolatom",
                    "LISTBOX": "listbox"}[o.kind]
            args = o.args[1:] if o.kind in ("FLOATATOM", "SYMBOLATOM", "LISTBOX") else o.args
            line = "#X " + kind + " " + str(o.x) + " " + str(o.y)
            for t in args: line += " " + escape_tok(t)
            sb.append(line + ";")
        elif it[0] == "raw":
            sb.append(it[1] if it[1].endswith(";") else it[1] + ";")
    for (f, fo, t, ti) in doc.conns:
        if f in index and t in index:
            sb.append("#X connect %d %d %d %d;" % (index[f], fo, index[t], ti))
    return "\n".join(sb) + "\n"


def main():
    if len(sys.argv) < 2:
        print(__doc__); return 1
    with open(sys.argv[1], encoding="utf-8", errors="replace") as fh:
        doc = parse(fh.read())
    for it in list(doc.items):
        if it[0] != "obj": continue
        box = it[1]
        if box.sub is not None: continue
        if box.args and resolve(box.args[0]):
            inline_abstraction(doc, box)
    sys.stdout.write(to_text(doc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
