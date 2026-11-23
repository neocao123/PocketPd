#!/usr/bin/env python3
"""audit_io.py - mechanical inlet/outlet count scan for the C externals
shipped in assets/slibs, compared against PdObjectMeta.kt (the app's
drawing table) and the heuristic fallback.

For every class in a lib's core_classes.txt it locates the class_new call,
extracts the referenced *_new constructor body, and counts
inlet_new/floatinlet_new/symbolinlet_new/pointerinlet_new/outlet_new
calls (pd_new itself always provides ONE default inlet). CLASS_MAINSIGNALIN
in the enclosing setup function adds one signal inlet; signal outlets are
outlet_new(..., &s_signal / gensym("signal")). Constructors that build io
inside a loop are flagged VARIABLE (counts come from creation args).

Output: markdown table per lib. verdicts:
  OK        - source count matches what the app draws
  FIX       - literal count mismatch (in=<n> out=<n> sigin=.. sigout=..)
  VARIABLE  - loop-driven io, needs a reg() rule (or already has one)
  PARSE?    - constructor body could not be extracted reliably
"""
import os, re, sys

SRCE = r"C:/Users/Administrator/AppData/Local/Temp/srcrepo/pd-extended/externals"
ASSETS = r"C:/Projects/26PDAndroid/app/src/main/assets/slibs/arm64-v8a"
META = r"C:/Projects/26PDAndroid/app/src/main/java/com/pocketpd/app/patch/PdObjectMeta.kt"

LIBS = {
    "iemlib": ["iemlib/src"],
    "creb": ["creb/modules", "creb/modules++"],
    "cxc": ["cxc"],
    "fftease": ["../../fftease"],
    "bsaylor": ["bsaylor"],
    "osc": ["mrpeach/osc"],
    "midifile": ["mrpeach/midifile"],
    "binfile": ["mrpeach/binfile"],
    "tracker": [r"C:/Projects/26PDAndroid/slibs/tracker/src"],
    # cyclone 0.9-5 (0.18.13 audit redo): binaries/ carries cyclone_lib.c at
    # its top level; cfiles() yields plain file paths directly
    "cyclone": [r"C:/Users/Administrator/AppData/Local/Temp/srcrepo/pd-cyclone-0.9-5/cyclone_objects/binaries"],
    # fftease 3.0.1 (0.18.14 audit): flat layout, excl pd-lib-builder tests
    "fftease": [r"C:/Users/Administrator/AppData/Local/Temp/srcrepo/fftease-3.0.1"],
    # zexy 2.4.4 master (0.18.15 audit): flat src/
    "zexy": [r"C:/Users/Administrator/AppData/Local/Temp/srcrepo/zexy-current/src"],
}

IO_CALLS = ("inlet_new", "floatinlet_new", "symbolinlet_new", "pointerinlet_new",
            "outlet_new")
CLASS_NEW = re.compile(
    r'class_new\s*\(\s*(?:gensym\s*\(\s*)?"([^"]+)"\s*\)?\s*,\s*'
    r'(?:\(t_newmethod\)\s*)?(\w+)\s*,')
SIG_IN = re.compile(r'CLASS_MAINSIGNALIN')
SIG_OUT = re.compile(r'outlet_new\s*\([^,]+,\s*(&s_signal|gensym\s*\(\s*"signal")')

def lib_dirs(lib):
    out = []
    for d in LIBS[lib]:
        p = d if os.path.isabs(d) else os.path.join(SRCE, d)
        out.append(os.path.normpath(p))
    return out

def cfiles(dirs):
    for d in dirs:
        if os.path.isfile(d):
            yield d
            continue
        for root, _, files in os.walk(d):
            for f in sorted(files):
                if f.endswith((".c", ".cc", ".cpp")):
                    yield os.path.join(root, f)

def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    return text

def function_body(text, name):
    """Body (brace-balanced) of the function whose definition line has
    `name(` at the start-ish of a signature."""
    m = re.search(r'\b(?:static\s+)?(?:void\s*\*|t_\w+\s*\*?|void|int)\s*'
                  + re.escape(name) + r'\s*\([^;{]*\)\s*\{', text)
    if not m:
        return None
    i = text.index('{', m.start())
    depth = 0
    for j in range(i, len(text)):
        if text[j] == '{':
            depth += 1
        elif text[j] == '}':
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    return None

def enclosing_function(text, pos):
    """Name of the function containing offset pos (scan back for a def)."""
    best = None
    for m in re.finditer(r'\b(?:static\s+)?(?:void\s*\*|t_\w+\s*\*?|void|int)\s*'
                         r'(\w+)\s*\([^;{]*\)\s*\{', text):
        if m.start() < pos:
            best = m.group(1)
        else:
            break
    return best

def loop_has_io(body):
    """True when a for/while block in the body creates io (crude)."""
    for m in re.finditer(r'\b(for|while)\s*\([^)]*\)\s*\{', body):
        i = body.index('{', m.start())
        depth = 0
        for j in range(i, len(body)):
            if body[j] == '{':
                depth += 1
            elif body[j] == '}':
                depth -= 1
                if depth == 0:
                    blk = body[i:j + 1]
                    if any(re.search(r'\b%s\s*\(' % c, blk) for c in IO_CALLS):
                        return True
                    break
    return False

def scan_lib(lib):
    classes = [l.strip() for l in open(os.path.join(ASSETS, lib, "core_classes.txt"),
                                       encoding="utf-8", errors="replace")
               if l.strip()]
    files = [strip_comments(open(p, encoding="utf-8", errors="replace").read())
             for p in cfiles(lib_dirs(lib))]
    rows = []
    for cls in classes:
        found = None
        for text in files:
            m = CLASS_NEW.search(text)
            # find the class_new for THIS class anywhere in the file
            for cm in CLASS_NEW.finditer(text):
                if cm.group(1) == cls:
                    found = (text, cm)
                    break
            if found:
                break
        if not found:
            rows.append((cls, None, None, None, "PARSE? (no class_new in source)"))
            continue
        text, cm = found
        ctor = cm.group(2)
        body = function_body(text, ctor)
        if body is None:
            rows.append((cls, None, None, None, "PARSE? (ctor %s not found)" % ctor))
            continue
        setup = enclosing_function(text, cm.start())
        setup_body = function_body(text, setup) if setup else None
        n_extra_in = sum(len(re.findall(r'\b%s\s*\(' % c, body))
                         for c in IO_CALLS if c != "outlet_new")
        outlets = re.findall(r'outlet_new\s*\(', body)
        n_out = len(outlets)
        n_sig_out = len(SIG_OUT.findall(body))
        sig_in = bool(SIG_IN.search(setup_body or "")) or bool(SIG_IN.search(body))
        variable = loop_has_io(body)
        rows.append((cls, n_extra_in + 1, n_out, (sig_in, n_sig_out), variable))
    return rows

def meta_draw(cls):
    """What the app currently draws for a bare class name."""
    src = open(META, encoding="utf-8").read()
    # static defs: put("name", d(in, out[...])) / Def(in, out, ...)
    m = re.search(r'put\("%s",\s*(d|Def)\(([^)]*)\)\)' % re.escape(cls), src)
    if m:
        nums = [x.strip() for x in m.group(2).split(",")]
        try:
            inn, out = int(nums[0]), int(nums[1])
        except (ValueError, IndexError):
            return "DEF?(%s)" % m.group(2), True
        sig_in = "setOf" in m.group(2) and "0" in (nums[2] if len(nums) > 2 else "")
        return "def %d/%d" % (inn, out), True
    if re.search(r'reg\("[^"]*",\s*"%s"' % re.escape(cls), src) or \
       re.search(r'reg\("%s"' % re.escape(cls), src):
        return "reg rule", True
    if cls.endswith("~"):
        return "heuristic 1 sig-in/1 sig-out", False
    return "heuristic 1/1", False

def fmt(v):
    if v is None:
        return "?"
    if isinstance(v, bool):
        return str(v)
    sig_in, n_sig_out = v
    parts = []
    parts.append("sig-in" if sig_in else "ctl-in")
    parts.append("%d sig-out" % n_sig_out if n_sig_out else "%d ctl-out" % 0)
    return "/".join(parts)

def main(libs):
    for lib in libs:
        rows = scan_lib(lib)
        print("## %s (%d classes)" % (lib, len(rows)))
        print("| class | src in | src out | signal | variable | app draws | verdict |")
        print("|---|---|---|---|---|---|---|")
        for cls, nin, nout, sig, var in rows:
            if isinstance(var, str):  # PARSE? row
                print("| `%s` | - | - | - | - | - | %s |" % (cls, var))
                continue
            draw, known = meta_draw(cls)
            # parse the drawn in/out numbers for comparison
            m = re.match(r"def (\d+)/(\d+)", draw)
            if var:
                verdict = "VARIABLE (%s)" % ("has rule" if known else "NEEDS rule")
            elif m:
                din, dout = int(m.group(1)), int(m.group(2))
                sin, sout = (sig if sig else (False, 0))
                # heuristic: only the counts matter here; signal flags reported
                if din == nin and dout == nout:
                    verdict = "OK"
                else:
                    verdict = "FIX in=%d out=%d" % (nin, nout)
                verdict += " [sig-in=%s sig-out=%s]" % (sig[0] if sig else "?", sig[1] if sig else "?")
            else:
                din, dout = (1, 1)
                verdict = ("OK" if (nin == din and nout == dout)
                           else "FIX in=%d out=%d" % (nin, nout))
                if sig and (sig[0] or sig[1]):
                    verdict += " [sig-in=%s sig-out=%s]" % (sig[0], sig[1])
            print("| `%s` | %s | %s | %s | %s | %s | %s |" % (
                cls, nin, nout, fmt(sig), "yes" if var else "no", draw, verdict))
        print()

if __name__ == "__main__":
    main(sys.argv[1:] or list(LIBS))
