#!/usr/bin/env python3
"""gen_meta_rows.py - turn the mechanical io audit into PdObjectMeta defs
rows. Position-tracks io creation inside each constructor (pd_new's default
inlet is leftmost; every inlet_new/floatinlet_new/... appends rightward, same
for outlets), detects the main signal inlet (CLASS_MAINSIGNALIN in the setup
or sic_setup for miXed sickle classes), filters names owned by a
higher-priority library (bare-name conflict order), resolves alias
registrations by grepping the lib sources for the quoted alias string, and
emits Kotlin put() rows plus an OBJECT_PORTS.md table.
"""
import importlib.util, re, os

spec = importlib.util.spec_from_file_location("audit_io",
    r"C:/Projects/26PDAndroid/slibs/audit_io.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
mod.LIBS["cyclone"] = ["miXed/cyclone/hammer", "miXed/cyclone/sickle",
                       "miXed/cyclone/shadow"]

PRIORITY = ["diy2", "else", "elsex", "cyclone", "zexy", "iemlib", "fftease",
            "bsaylor", "creb", "cxc", "osc", "midifile", "binfile", "tracker"]
ASSETS = mod.ASSETS
LIBS = ["cyclone", "cxc", "creb", "fftease", "iemlib", "bsaylor", "osc",
        "midifile", "binfile", "tracker"]

IO_RE = re.compile(
    r'\b(inlet_new|floatinlet_new|symbolinlet_new|pointerinlet_new|outlet_new|'
    r'sic_newinlet)\s*\((.*?)\)\s*;', re.S)
SIG_IN = re.compile(r'CLASS_MAINSIGNALIN')
SIC = re.compile(r'sic_setup\s*\(')
DETAIL = re.compile(
    r'(\S+): in=(\d+) \(sig (-?\d+) ctl (-?\d+)\) out=(\d+) \(sig (-?\d+) ctl (-?\d+)\)')

def load_classes(lib):
    p = os.path.join(ASSETS, lib, "core_classes.txt")
    if not os.path.exists(p):
        return []
    return [l.strip() for l in open(p, encoding="utf-8", errors="replace") if l.strip()]

owner = {}
lib_classes = {}
for lib in PRIORITY:
    cs = load_classes(lib)
    lib_classes[lib] = cs
    for c in cs:
        owner.setdefault(c, lib)

def ctor_source(lib, cls):
    for p in mod.cfiles(mod.lib_dirs(lib)):
        t = mod.strip_comments(open(p, encoding="utf-8", errors="replace").read())
        for c in mod.CLASS_NEW.finditer(t):
            if c.group(1) == cls:
                return p, t, c
    return None, None, None

def alias_owner(lib, cls):
    """Alias registrations: the quoted alias string lives in the same file as
    its primary class_new (possibly inside a table). Returns (file, primary)."""
    for p in mod.cfiles(mod.lib_dirs(lib)):
        t = mod.strip_comments(open(p, encoding="utf-8", errors="replace").read())
        if '"%s"' % cls not in t and "'%s'" % cls not in t:
            continue
        names = [c.group(1) for c in mod.CLASS_NEW.finditer(t)]
        if names:
            return p, names[0]
    return None, None

def ports_for(lib, cls):
    """(total_in, sig_in_set, total_out, sig_out_set) or None."""
    p, t, cm = ctor_source(lib, cls)
    if t is None:
        ap, primary = alias_owner(lib, cls)
        if primary and primary != cls:
            return ports_for(lib, primary)
        return None
    body = mod.function_body(t, cm.group(2))
    if body is None:
        return None
    if mod.loop_has_io(body):
        return None  # loop-driven io: variable, needs a reg() rule
    setup = mod.enclosing_function(t, cm.start())
    setup_body = mod.function_body(t, setup) if setup else ""
    main_sig = bool(SIG_IN.search(setup_body)) or bool(SIC.search(setup_body))
    ins = ["sig" if main_sig else "ctl"]
    outs = []
    for m in IO_RE.finditer(body):
        kind, args = m.group(1), m.group(2)
        if kind == "outlet_new":
            outs.append("sig" if ('s_signal' in args or '"signal"' in args) else "ctl")
        elif kind == "sic_newinlet":
            # miXed sickle helper: inlet_new(..., &s_signal, &s_signal) per call
            ins.append("sig")
        else:
            ins.append("sig" if ('s_signal' in args or '"signal"' in args) else "ctl")
    sig_in = {i for i, k in enumerate(ins) if k == "sig"}
    sig_out = {i for i, k in enumerate(outs) if k == "sig"}
    return len(ins), sig_in, len(outs), sig_out

def kotlin_row(cls, ports):
    tin, sig_in, tout, sig_out = ports
    if not sig_in and not sig_out:
        return 'put("%s", d(%d, %d))' % (cls, tin, tout)
    si = "setOf(%s)" % ", ".join(str(i) for i in sorted(sig_in)) if sig_in else "emptySet()"
    so = "setOf(%s)" % ", ".join(str(i) for i in sorted(sig_out)) if sig_out else "emptySet()"
    return 'put("%s", Def(%d, %d, %s, %s))' % (cls, tin, tout, si, so)

# current meta rows (def form) to skip when counts already match
meta_src = open(mod.META, encoding="utf-8").read()
def_row = {}
for m in re.finditer(r'put\("([^"]+)",\s*(?:d|Def)\((\d+),\s*(\d+)', meta_src):
    def_row[m.group(1)] = (int(m.group(2)), int(m.group(3)))
reg_names = set()
for m in re.finditer(r'reg\(([^)]*)\)', meta_src):
    for tok in re.findall(r'"([^"]+)"', m.group(1)):
        reg_names.add(tok)

out_kotlin = {}
out_doc = {}
skipped = []
for lib in LIBS:
    rows = []
    for cls in lib_classes[lib]:
        if owner.get(cls) != lib:
            skipped.append((cls, lib, "bare name owned by %s" % owner.get(cls)))
            continue
        if cls in reg_names:
            skipped.append((cls, lib, "variable reg() rule exists"))
            continue
        ports = ports_for(lib, cls)
        if ports is None:
            skipped.append((cls, lib, "ctor not parseable (alias/odd ctor)"))
            continue
        tin, sig_in, tout, sig_out = ports
        old = def_row.get(cls)
        drawn = old if old else ((1, 1) if not cls.endswith("~") else (1, 1))
        if old == (tin, tout):
            continue  # counts already correct
        rows.append((cls, ports))
    if rows:
        out_kotlin[lib] = [kotlin_row(c, p) for c, p in rows]
        out_doc[lib] = [(c, p) for c, p in rows]

print("=== KOTLIN ROWS (paste into PdObjectMeta defs) ===")
for lib, rows in out_kotlin.items():
    print("        // -- %s (0.17.142 audit: source-verified ctor io)" % lib)
    line = "        "
    for r in rows:
        token = r + "; "
        if len(line) + len(token) > 96:
            print(line.rstrip())
            line = "        "
        line += token
    if line.strip():
        print(line.rstrip())
    print()
print("=== SKIPPED ===")
for cls, lib, why in skipped:
    print("  %s (%s): %s" % (cls, lib, why))
print("=== DOC TABLES ===")
for lib, rows in out_doc.items():
    print("### %s" % lib)
    print("| 对象 | 进 | 出 | 信号进 | 信号出 | 来源 |")
    print("|---|---|---|---|---|---|")
    for cls, (tin, si, tout, so) in rows:
        ssi = ", ".join(str(i) for i in sorted(si)) or "-"
        sso = ", ".join(str(i) for i in sorted(so)) or "-"
        print("| `%s` | %d | %d | %s | %s | 源码核对 |" % (cls, tin, tout, ssi, sso))
    print()
