#!/usr/bin/env python3
"""gen_supersaw.py - generate the supersaw~ abstraction (0.17.144).
Seven compiled [vsaw~] voices, per-voice detune ratios computed at load
from the detune argument (expr -> sig~ -> [*~]), equal-power pan2~ spread,
1/7 master gain for coherent-sum normalization. Frequency: signal or float
on inlet 0 (rides the [*~] left scalar); detune: creation arg 2 (default
25) or a live float on inlet 1. Creation arg 1 = frequency (default 220).
All objects are compiled classes or vanilla - no $0 state, so every
instance is fully independent."""
import os

COEFS = ["-1", "-0.6", "-0.3", "0", "0.3", "0.6", "1"]
PANS = ["-1", "-0.6", "-0.3", "0", "0.3", "0.6", "1"]

objs = []   # (x, y, "obj"/"msg"/"text", [tokens])
conns = []  # (from_idx, outlet, to_idx, inlet)


def obj(x, y, *toks):
    objs.append((x, y, "obj", list(toks)))
    return len(objs) - 1


def msg(x, y, *toks):
    objs.append((x, y, "msg", list(toks)))
    return len(objs) - 1


# -- io + load chain --------------------------------------------------------
I_FREQ = obj(60, 40, "inlet~")           # 0
I_DET = obj(340, 40, "inlet")            # 1
O_L = obj(60, 300, "outlet~")            # 2
O_R = obj(190, 300, "outlet~")           # 3
LB = obj(300, 90, "loadbang")            # 4
F_FREQ = obj(420, 90, "f", "$1")         # 5
F_DET = obj(500, 90, "f", "$2")          # 6
SEL_F = obj(420, 140, "select", "0")     # 7
SEL_D = obj(500, 140, "select", "0")     # 8
M_FREQ = msg(360, 190, "220")            # 9
M_DET = msg(460, 190, "25")              # 10

EXPR = []
for i, c in enumerate(COEFS):
    if c == "0":
        e = obj(60 + 45 * i, 90, "expr", "pow(2", "\\,", "$f1", "*", c,
                "/", "1200)")
    elif c.startswith("-"):
        e = obj(60 + 45 * i, 90, "expr", "pow(2", "\\,", "0", "-", "$f1",
                "*", c[1:], "/", "1200)")
    else:
        e = obj(60 + 45 * i, 90, "expr", "pow(2", "\\,", "$f1", "*", c,
                "/", "1200)")
    EXPR.append(e)                       # 11..17

SIG = [obj(60 + 45 * i, 150, "sig~") for i in range(7)]        # 18..24
MUL = [obj(60 + 45 * i, 200, "*~") for i in range(7)]          # 25..31
VSAW = [obj(60 + 45 * i, 245, "vsaw~") for i in range(7)]      # 32..38
PAN = [obj(60 + 45 * i, 290, "pan2~", p) for i, p in enumerate(PANS)]  # 39..45

# L/R summation chains: 6 adders each, then a 1/7 master gain
ADD_L = []
ADD_R = []
for k in range(6):
    ADD_L.append(obj(60, 335 + 0 * k, "+~"))
for k in range(6):
    ADD_R.append(obj(190, 335 + 0 * k, "+~"))
# give the adders distinct rows so the file stays readable
for k in range(6):
    objs[ADD_L[k]] = (60, 335 + 18 * k, "obj", ["+~"])
    objs[ADD_R[k]] = (190, 335 + 18 * k, "obj", ["+~"])
G_L = obj(60, 480, "*~", "0.14286")      # 58
G_R = obj(190, 480, "*~", "0.14286")     # 59


def wire(a, ao, b, bi):
    conns.append((a, ao, b, bi))


# load chain
wire(LB, 0, F_FREQ, 0)
wire(LB, 0, F_DET, 0)
wire(F_FREQ, 0, SEL_F, 0)
wire(F_DET, 0, SEL_D, 0)
wire(SEL_F, 0, M_FREQ, 0)      # zero -> default 220
wire(SEL_D, 0, M_DET, 0)       # zero -> default 25
for v in VSAW:
    wire(SEL_F, 1, v, 0)       # non-zero arg frequency scalar
    wire(M_FREQ, 0, v, 0)      # default frequency scalar
for e in EXPR:
    wire(SEL_D, 1, e, 0)       # non-zero arg detune
    wire(M_DET, 0, e, 0)       # default detune
    wire(I_DET, 0, e, 0)       # live detune override
# ratios -> sig~ -> *~ right inlet
for i in range(7):
    wire(EXPR[i], 0, SIG[i], 0)
    wire(SIG[i], 0, MUL[i], 1)
# frequency: arg scalars ride the *~ LEFT scalar (float), the user's
# signal overrides it; *~ -> vsaw~ -> pan2~
for i in range(7):
    wire(SEL_F, 1, MUL[i], 0)  # non-zero arg frequency scalar
    wire(M_FREQ, 0, MUL[i], 0)  # default frequency scalar
    wire(I_FREQ, 0, MUL[i], 0)
    wire(MUL[i], 0, VSAW[i], 0)
    wire(VSAW[i], 0, PAN[i], 0)
# summation chains
for ch, (pans_out, adds, gain, out) in enumerate([
        (0, ADD_L, G_L, O_L), (1, ADD_R, G_R, O_R)]):
    wire(PAN[0], pans_out, adds[0], 0)
    wire(PAN[1], pans_out, adds[0], 1)
    for k in range(1, 6):
        wire(adds[k - 1], 0, adds[k], 0)
        wire(PAN[k + 1], pans_out, adds[k], 1)
    wire(adds[5], 0, gain, 0)
    wire(gain, 0, out, 0)

# -- emit --------------------------------------------------------------------
# NOTE: $1/$2 are written UNESCAPED on purpose: pd's own writer stores
# A_DOLLSYM tokens unescaped (atom_string only quotes $ inside plain
# SYMBOL atoms), so native pd substitutes them at abstraction load. The
# app's inliner substitutes them per instance (substituteTokens), and
# $f1/$f2 are expr's own variables (pd never treats "$f" as a dollar
# atom - it is followed by a letter, not a digit).
lines = ["#N canvas 0 0 700 560 10;"]
for x, y, kind, toks in objs:
    lines.append("#X %s %d %d %s;" % (kind, x, y, " ".join(toks)))
for a, ao, b, bi in conns:
    lines.append("#X connect %d %d %d %d;" % (a, ao, b, bi))
text = "\n".join(lines) + "\n"

for abi in ("arm64-v8a", "x86_64"):
    p = "C:/Projects/26PDAndroid/app/src/main/assets/slibs/%s/else/supersaw~.pd" % abi
    open(p, "w", encoding="utf-8", newline="\n").write(text)
    print("wrote", p, len(objs), "objects", len(conns), "connections")
