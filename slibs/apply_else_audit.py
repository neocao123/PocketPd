#!/usr/bin/env python3
"""apply_else_audit.py - apply the else/elsex 0.17.143 port audit to
PdObjectMeta.kt: rewrite signal-mark sets on existing rows, add missing
rows, correct the xgate~/xselect~/xselect2~/select~/unmerge rules and add
variable rules for classes whose bare default breaks the heuristic.
Every replacement is anchored on `put("<name>",` (balanced-paren scan) or
an exact literal, and the script fails loudly when an anchor is missing."""
import re, sys

META = r"C:/Projects/26PDAndroid/app/src/main/java/com/pocketpd/app/patch/PdObjectMeta.kt"
src = open(META, encoding="utf-8").read()
fails = []

def replace_row(name, new_row):
    """Replace the put("name", ...) row (balanced parens) wherever it appears."""
    global src
    key = 'put("%s",' % name
    i = src.find(key)
    if i < 0:
        fails.append("row not found: " + name)
        return
    j = src.index("(", i + len(key) - 1)
    depth = 0
    k = j
    while k < len(src):
        if src[k] == "(":
            depth += 1
        elif src[k] == ")":
            depth -= 1
            if depth == 0:
                break
        k += 1
    src = src[:i] + 'put("%s", %s)' % (name, new_row) + src[k + 1:]

def replace_literal(old, new, what):
    global src
    if old not in src:
        fails.append("literal missing: " + what)
        return
    src = src.replace(old, new, 1)

# ---- 1. signal-mark rewrites on existing rows -----------------------------
REWRITE = {
    "add~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "allpass.rev~": "Def(3, 1, setOf(0, 1, 2), setOf(0))",
    "blocksize~": "Def(1, 1, setOf(0), emptySet())",
    "ceil~": "Def(1, 1, setOf(0), setOf(0))",
    "cents2ratio~": "Def(1, 1, setOf(0), setOf(0))",
    "changed2~": "Def(1, 2, setOf(0), setOf(0, 1))",
    "comb.filt~": "Def(3, 1, setOf(0, 1, 2), setOf(0))",
    "crackle~": "Def(1, 1, setOf(0), setOf(0))",
    "dcblock~": "Def(1, 1, setOf(0), setOf(0))",
    "decay2~": "Def(3, 1, setOf(0, 1, 2), setOf(0))",
    "decay~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "dust2~": "Def(1, 1, setOf(0), setOf(0))",
    "dust~": "Def(1, 1, setOf(0), setOf(0))",
    "envgen~": "Def(2, 2, setOf(0), setOf(0))",
    "eq~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
    "fbdelay~": "Def(3, 1, setOf(0, 1, 2), setOf(0))",
    "fbsine~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
    "ffdelay~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "floor~": "Def(1, 1, setOf(0), setOf(0))",
    "function~": "Def(1, 1, setOf(0), setOf(0))",
    "giga.rev~": "Def(1, 2, setOf(0), setOf(0, 1))",
    "glide2~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "hz2rad~": "Def(1, 1, setOf(0), setOf(0))",
    "impseq~": "Def(1, 1, setOf(0), setOf(0))",
    "impulse2~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
    "keycode": "d(0, 2)",
    "lfnoise~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "median~": "Def(2, 1, setOf(0), setOf(0))",
    "mov.avg~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "mov.rms~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "nyquist~": "Def(1, 1, setOf(0), emptySet())",
    "pan4~": "Def(3, 4, setOf(0, 1, 2), setOf(0, 1, 2, 3))",
    "pluck~": "Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0))",
    "pulsecount~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "pulsediv~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "pulse~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
    "quantizer~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "rad2hz~": "Def(1, 1, setOf(0), setOf(0))",
    "randpulse2~": "Def(1, 1, setOf(0), setOf(0))",
    "ratio2cents~": "Def(1, 1, setOf(0), setOf(0))",
    "schmitt~": "Def(3, 1, setOf(0, 1, 2), setOf(0))",
    "sequencer~": "Def(1, 1, setOf(0), setOf(0))",
    "shaper~": "Def(1, 1, setOf(0), setOf(0))",
    "slew~": "Def(3, 1, setOf(0), setOf(0))",
    "sr~": "Def(1, 1, setOf(0), emptySet())",
    "status~": "Def(1, 2, setOf(0), setOf(0, 1))",
    "table~": "Def(1, 1, setOf(0), setOf(0))",
    "tempo~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
    "timed.gate~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "toggleff~": "Def(1, 1, setOf(0), setOf(0))",
    "trig.delay~": "Def(2, 1, setOf(0, 1), setOf(0))",
    "vsaw~": "Def(4, 1, setOf(0, 1, 2, 3), setOf(0))",
}
for name, new in REWRITE.items():
    replace_row(name, new)

# ---- 2. new rows appended at the end of the else compiled section ----------
anchor = '        put("xfade.mc~", Def(3, 1, setOf(0, 1, 2), setOf(0)))\n'
addition = anchor + '''        // 0.17.143 else audit additions: constructor counts of the shipped
        // else_core vintage (~1.0-beta19..21; where the bundled help declares
        // port markers, the help truth was kept - freq.shift~'s 2nd outlet is
        // a beta22+ change and stays off). Envelope/noise generators whose
        // params ride SIGNAL inlets get them marked; rms~/s2f~ output FLOATS.
        put("balance~", Def(3, 2, setOf(0, 1, 2), setOf(0, 1)))
        put("cmul~", Def(4, 2, setOf(0, 1, 2, 3), setOf(0, 1)))
        put("comb.rev~", Def(5, 1, setOf(0, 1, 2, 3, 4), setOf(0)))
        put("flipflop~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("imp~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("imp2~", Def(4, 1, setOf(0, 1, 2, 3), setOf(0)))
        put("rms~", Def(1, 1, setOf(0), emptySet()))
        put("rotate~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("s2f~", Def(2, 1, setOf(0), emptySet()))
        put("susloop~", Def(2, 1, setOf(0, 1), setOf(0)))
        put("wt~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("xfade~", Def(3, 1, setOf(0, 1, 2), setOf(0)))
        put("message", d(2, 1)); put("pgm.out", d(2, 1)); put("pic", d(1, 0))
        put("note", d(1, 0)); put("openfile", d(1, 0))
        put("click", d(0, 1)); put("properties", d(0, 1))
        put("loop", d(1, 2))
        put("_active_gui", d(0, 0)); put("_format_proxy", d(0, 0))
        put("_handle", d(0, 0))
'''
if anchor not in src:
    fails.append("else-section anchor missing")
else:
    src = src.replace(anchor, addition, 1)

# ---- 3. variable-rule corrections ------------------------------------------
replace_literal('''        reg("select~") { a ->
            val n = num(a, 0, 1)
            ds(n + 1, 1, 1..n, 0..0)
        }''',
'''        reg("select~") { a ->
            // 0.17.143 else audit: inlet 0 IS signal (CLASS_MAINSIGNALIN),
            // the selector float rides it - sig in = 0..n
            val n = num(a, 0, 1)
            ds(n + 1, 1, 0..n, 0..0)
        }''', "select~ rule")

replace_literal('        reg("unmerge") { a -> d(2, num(a, 0, 1) + 1) }',
'''        // unmerge clamps the split count to >=2 (beta21 unmerge.c:91):
        // bare [unmerge] has THREE outlets, not two
        reg("unmerge") { a -> d(2, num(a, 0, 1).coerceAtLeast(2) + 1) }''',
    "unmerge rule")

replace_literal('''        reg("xgate~") { a ->
            val n = num(a, 0, 1)
            ds(2, n, 1..1, 0 until n)
        }''',
'''        reg("xgate~") { a ->
            // ONE signal inlet in this vintage (beta21 xgate~.c:123): the
            // selector float rides the inlet's signalscalar
            val n = num(a, 0, 1)
            ds(1, n, 0..0, 0 until n)
        }''', "xgate~ rule")

replace_literal('''        reg("xselect~") { a ->
            val n = num(a, 0, 1)
            ds(n + 1, 1, 1..n, 0..0)
        }''',
'''        reg("xselect~") { a ->
            // n signal inlets, selector float rides inlet 0 (beta21
            // xselect~.c:114): bare [xselect~] is 1 sig in / 1 sig out
            val n = num(a, 0, 1).coerceAtLeast(1)
            ds(n, 1, 0 until n, 0..0)
        }''', "xselect~ rule")

replace_literal('''        reg("xselect2~") { a ->
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(n + 1, n, 1..n, 0 until n)
        }''',
'''        reg("xselect2~") { a ->
            // ONE signal outlet in this vintage (beta21 xselect2~.c:70):
            // 1+n signal inlets (n arg, min 2) -> 1 signal out
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(n + 1, 1, 0..n, 0..0)
        }''', "xselect2~ rule")

# ---- 4. new variable rules for bare defaults the heuristic misses ----------
insert_before = '''        // -- iemlib / mrpeach --------------------------------------------------'''
new_rules = '''        // 0.17.143 else audit: variable-io classes whose bare default does
        // NOT match the 1-in/1-out heuristic
        reg("autofade~", "autofade2~") { a ->
            val c = num(a, 1, 1).coerceAtLeast(1)
            Def(1 + c, c + 1, (0 until 1 + c).toSet(), (0 until c).toSet())
        }
        reg("hot") { a -> val n = num(a, 0, 2).coerceAtLeast(2); d(n, n) }
        reg("match~") { a ->
            val outs = if (a.isEmpty()) 2 else a.size + 1
            Def(1, outs, setOf(0), (0 until outs).toSet())
        }
        reg("merge") { a -> d(a.size.coerceAtLeast(2), 1) }
        reg("rec~") { a ->
            // channels of a named array are engine-side; bare [rec~] has no
            // array and zero outlets
            if (a.isEmpty()) Def(1, 0, setOf(0), emptySet())
            else Def(1, 1, setOf(0), setOf(0))
        }
        reg("routeall") { a -> d(1, a.size.coerceAtLeast(1) + 1) }
        reg("t2", "trigger2") { a -> d(1, a.size.coerceAtLeast(2)) }
        reg("spread~") { a ->
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(n, n, 0 until n, 0 until n)
        }
        reg("xgate2~") { a ->
            val n = num(a, 0, 2).coerceAtLeast(2)
            ds(1, n, 0..0, 0 until n)
        }
        reg("chance") { a ->
            if (a.size >= 2) d(1, a.size) else d(2, 2)
        }
        reg("format") { a ->
            // one inlet per %-slot, the first slot rides inlet 0
            val slots = a.sumOf { tok -> tok.count { c -> c == '%' } }
            d(slots.coerceAtLeast(1), 1)
        }
        reg("limit") { a ->
            if (num(a, 1, 0) > 0) d(2, 2) else d(2, 1)
        }
        reg("route2", "spread") { a ->
            if (a.isEmpty()) d(1, 2) else d(1, a.size + 1)
        }
''' + insert_before
if insert_before not in src:
    fails.append("variadic insertion anchor missing")
else:
    src = src.replace(insert_before, new_rules, 1)

open(META, "w", encoding="utf-8", newline="\n").write(src)
if fails:
    print("FAILURES:")
    for f in fails:
        print("  " + f)
    sys.exit(1)
print("applied: %d row rewrites, new rows + 5 rule fixes + 14 new rules" % len(REWRITE))
