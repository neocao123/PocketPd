#!/usr/bin/env python3
"""sim_inline.py - faithful Python port of the app's parse -> inline ->
toText pipeline (PatchModel.kt + PdEngine.kt), to inspect the exact pd
session text a user patch with [arp]/[superosc~] produces. Diagnostic
tool for the 0.17.144 round; run from the slibs/ directory."""
import re, os, sys

A = "C:/Projects/26PDAndroid/app/src/main/assets/slibs/arm64-v8a"
H = "C:/Projects/26PDAndroid/app/src/main/assets/help"
BS = chr(92)  # backslash


def split_statements(text):
    out, sb, esc = [], [], False
    for ch in text:
        if esc:
            sb.append(ch)
            esc = False
            continue
        if ch == BS:
            sb.append(ch)
            esc = True
            continue
        if ch == ";":
            out.append("".join(sb))
            sb = []
            continue
        sb.append(ch)
    if "".join(sb).strip():
        out.append("".join(sb))
    return out


def unescape_dollars(tok):
    return tok.replace(BS + "$", "$")


def tokenize(stmt):
    return [unescape_dollars(t) for t in re.split(r"\s+", stmt.strip()) if t]


class Obj:
    def __init__(self, oid, kind, args, subpatch_lines=None):
        self.id = oid
        self.kind = kind
        self.args = args
        self.sub = subpatch_lines


_obj_ids = iter(range(1000, 100000))


def oid():
    return next(_obj_ids)


class Doc:
    def __init__(self):
        self.items = []
        self.order = []
        self.conns = []


def parse(text):
    doc = Doc()
    doc.header = "#N canvas 0 0 450 300 10;"
    statements = split_statements(text)
    header_seen = False
    i = 0
    while i < len(statements):
        st = statements[i]
        tok = tokenize(st)
        if not tok:
            i += 1
            continue
        if not header_seen and tok[0] == "#N" and tok[1:2] == ["canvas"]:
            doc.header = st.strip() + ";"
            header_seen = True
            i += 1
            continue
        if tok[0] == "#N" and tok[1:2] == ["canvas"]:
            lines, depth, j = [], 0, i
            while j < len(statements):
                t2 = tokenize(statements[j])
                if t2[0:2] == ["#N", "canvas"]:
                    depth += 1
                if t2[0:2] == ["#X", "restore"]:
                    depth -= 1
                lines.append(statements[j].strip() + ";")
                j += 1
                if depth <= 0 and j > i + 1:
                    break
            name = "subpatch"
            for ln in lines:
                if ln.startswith("#X restore"):
                    tk = tokenize(ln)
                    if "pd" in tk:
                        k = tk.index("pd")
                        name = " ".join(tk[k + 1:]) or "subpatch"
                    break
            o = Obj(oid(), "OBJECT", ["pd", name], lines)
            doc.items.append(("obj", o))
            doc.order.append(o.id)
            i += (j - i)
            continue
        if tok[0] == "#X":
            kind = tok[1] if len(tok) > 1 else ""
            if kind in ("obj", "msg", "text", "floatatom", "symbolatom", "listbox"):
                kmap = {"obj": "OBJECT", "msg": "MSG", "text": "COMMENT",
                        "floatatom": "FLOATATOM", "symbolatom": "SYMBOLATOM",
                        "listbox": "LISTBOX"}
                inc = kind in ("floatatom", "symbolatom", "listbox")
                args = tok[4:]
                if inc:
                    args = [kind] + args
                o = Obj(oid(), kmap[kind], args)
                doc.items.append(("obj", o))
                doc.order.append(o.id)
                i += 1
                continue
            if kind == "connect":
                doc.items.append(("conn", tok))
                i += 1
                continue
        doc.items.append(("raw", st.strip() + ";"))
        i += 1
    conns = []
    for it in doc.items:
        if it[0] == "conn":
            tk = it[1]
            f, fo, t, ti = int(tk[2]), int(tk[3]), int(tk[4]), int(tk[5])
            fid = doc.order[f] if f < len(doc.order) else None
            tid = doc.order[t] if t < len(doc.order) else None
            if fid is None or tid is None:
                print("!! connect index out of range", tk)
            conns.append([fid, fo, tid, ti])
    doc.conns = conns
    return doc


def escape_tok(tok):
    return tok.replace("$", BS + "$") if "$" in tok else tok


def to_text(doc):
    sb = [doc.header]
    index = {}
    i = 0
    for it in doc.items:
        if it[0] == "obj":
            o = it[1]
            index[o.id] = i
            i += 1
            if o.sub is not None:
                sb.extend(o.sub)
                continue
            kind = {"OBJECT": "obj", "MSG": "msg", "COMMENT": "text",
                    "FLOATATOM": "floatatom", "SYMBOLATOM": "symbolatom",
                    "LISTBOX": "listbox"}[o.kind]
            args = o.args[1:] if o.kind in ("FLOATATOM", "SYMBOLATOM",
                                            "LISTBOX") else o.args
            line = "#X " + kind + " 0 0"
            for t in args:
                line += " " + escape_tok(t)
            sb.append(line + ";")
        elif it[0] == "raw":
            sb.append(it[1])
    for (f, fo, t, ti) in doc.conns:
        if f in index and t in index:
            sb.append("#X connect %d %d %d %d;" % (index[f], fo, index[t], ti))
    return "\n".join(sb) + "\n"


def locate(name):
    f = os.path.join(A, name + ".pd")
    if os.path.isfile(f):
        return f
    for lib in os.listdir(A):
        c = os.path.join(A, lib, name + ".pd")
        if os.path.isfile(c):
            return c
    f = os.path.join(H, name + ".pd")
    if os.path.isfile(f):
        return f
    return None


def resolve(name):
    if name in ("arp", "superosc~", "arpeggiator"):
        return locate(name) or locate("else/" + name)
    return locate(name)


def substitute_tokens(tokens, uid, args, literal):
    out = []
    in_send = False
    for tok0 in tokens:
        t = tok0.replace(BS + "$", "$")
        t = t.replace("$0", uid)
        t = re.sub(r"$[1-9](?=-)", uid, t)
        if literal:
            if t == BS + ";":
                in_send = True
            elif in_send:
                t = re.sub(r"$[1-9]", uid, t)
        else:
            for m in re.finditer(r"\$(\d)", tok0):
                n = int(m.group(1))
                rep = args[n - 1] if 0 < n <= len(args) else "0"
                t = t.replace(m.group(0), rep)
        out.append(t)
    return out


def substitute_line(line, uid, args):
    toks = re.split(r"\s+", line.rstrip(";").strip())
    literal = toks[0].startswith("#X msg") if toks else False
    return " ".join(substitute_tokens(toks, uid, args, literal)) + ";"


DELAY = {"delwrite~", "delread4~", "vd~", "delread~"}


def inline_abstraction(doc, box):
    path = resolve(box.args[0])
    if path is None:
        print("!! no abstraction file for", box.args[0])
        return
    uid = str(1000 + box.id)
    info = parse(open(path, encoding="utf-8", errors="replace").read())
    idmap = {}
    newitems = []
    for it in info.items:
        if it[0] != "obj":
            continue
        o = it[1]
        if o.kind == "COMMENT":
            continue
        if o.args and o.args[0] == "block~":
            continue
        if o.sub is not None:
            lines = [substitute_line(l, uid, box.args[1:]) for l in o.sub]
            copy = Obj(oid(), o.kind,
                       substitute_tokens(o.args, uid, box.args[1:], False),
                       lines)
        else:
            copy = Obj(oid(), o.kind,
                       substitute_tokens(o.args, uid, box.args[1:],
                                         o.kind == "MSG"))
            if (copy.kind == "OBJECT" and len(copy.args) > 1
                    and copy.args[0] in DELAY
                    and not copy.args[1].startswith(uid + "-")):
                copy.args[1] = uid + "-" + copy.args[1]
        idmap[o.id] = copy.id
        newitems.append(("obj", copy))
    conns = []
    for (f, fo, t, ti) in info.conns:
        if f in idmap and t in idmap:
            conns.append([idmap[f], fo, idmap[t], ti])
    feed = {}
    outs = {}
    objs_by_id = {it[1].id: it[1] for it in info.items if it[0] == "obj"}
    for (f, fo, t, ti) in info.conns:
        fo_obj = objs_by_id.get(f)
        to_obj = objs_by_id.get(t)
        if fo_obj and fo_obj.args[0] in ("inlet", "inlet~"):
            feed.setdefault((f, fo), []).append((t, ti))
        if to_obj and to_obj.args[0] in ("outlet", "outlet~"):
            outs.setdefault((t, ti), []).append((f, fo))
    inlet_objs = [o for o in objs_by_id.values()
                  if o.kind == "OBJECT" and o.args[0] in ("inlet", "inlet~")]
    outlet_objs = [o for o in objs_by_id.values()
                   if o.kind == "OBJECT" and o.args[0] in ("outlet", "outlet~")]
    spliced, rest = [], []
    for (f, fo, t, ti) in doc.conns:
        if f == box.id:
            tgt = outlet_objs[fo] if fo < len(outlet_objs) else None
            if tgt is not None:
                for (src, so) in outs.get((tgt.id, 0), []):
                    if src in idmap:
                        spliced.append([idmap[src], so, t, ti])
            continue
        if t == box.id:
            tgt = inlet_objs[ti] if ti < len(inlet_objs) else None
            if tgt is not None:
                for (dst, di) in feed.get((tgt.id, 0), []):
                    if dst in idmap:
                        spliced.append([f, fo, idmap[dst], di])
            continue
        rest.append([f, fo, t, ti])
    doc.conns = rest + spliced
    at = next(k for k, it in enumerate(doc.items)
              if it[0] == "obj" and it[1].id == box.id)
    doc.items[at:at + 1] = newitems


def build(user_text):
    doc = parse(user_text)
    for it in list(doc.items):
        if it[0] == "obj":
            o = it[1]
            if o.kind == "OBJECT" and o.args[0] in ("arp", "superosc~", "supersaw~", "arpeggiator"):
                inline_abstraction(doc, o)
    return to_text(doc)


if __name__ == "__main__":
    names = sys.argv[1:] or ["arp", "superosc~"]
    for n in names:
        doc_text = "#N canvas 0 0 450 300 10;" + chr(10) + \
            "#X obj 100 100 %s;" % n + chr(10)
        out = build(doc_text)
        out_path = "sim_%s_session.txt" % n.replace("~", "t")
        open(out_path, "w", encoding="utf-8", newline="\n").write(out)
        print("wrote", out_path, len(out.splitlines()), "lines")
