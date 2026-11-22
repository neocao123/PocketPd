#!/usr/bin/env python3
"""Inspect a WOPN v2 bank: bank names, instrument names, and the parameter
distribution we care about for the ym2612~ gm conversion."""
import struct
import sys
import numpy as np

P = r"C:\Projects\26PDAndroid\archive\xg.wopn"
d = open(P, "rb").read()
assert d[:11] == b"WOPN2-B2NK\x00", d[:11]
ver = struct.unpack_from("<H", d, 11)[0]
nmel, nperc = struct.unpack_from(">HH", d, 13)
flags = d[17]
off = 18
print("version=%d melodic=%d perc=%d flags=%02x" % (ver, nmel, nperc, flags))

banks = []
for i in range(nmel + nperc):
    name = d[off:off + 32].split(b"\x00")[0].decode("ascii", "replace")
    lsb, msb = d[off + 32], d[off + 33]
    off += 34
    banks.append((name, lsb, msb))
print("banks:", [(n, m) for n, _, m in banks[:nmel]])

INST = 69
dt_signed = {0: 0, 1: 1, 2: 2, 3: 3, 4: -3, 5: -2, 6: -1, 7: -3}
stats = dict(blank=0, dt_nonzero=0, dt2_nonzero=0, am=0, rs=0, ssgeg=0,
             noteoff=0, fb_hist=np.zeros(8, int), alg_hist=np.zeros(8, int),
             tl_mod=[], tl_car=[], mul_hist=np.zeros(16, int))
rows = []
for bi in range(nmel):
    for n in range(128):
        b = off + (bi * 128 + n) * INST
        name = d[b:b + 32].split(b"\x00")[0].decode("ascii", "replace")
        noteoff = struct.unpack_from(">h", d, b + 32)[0]
        perckey, fbalg, lfosens = d[b + 34], d[b + 35], d[b + 36]
        ops = []
        for l in range(4):
            o = b + 37 + l * 7
            dtmul, tl, rsatk, amdr, dt2sr, susrel, ssgeg = d[o:o + 7]
            ops.append(dict(dt=(dtmul >> 4) & 7, mul=dtmul & 15, tl=tl,
                            rs=(rsatk >> 6) & 3, ar=rsatk & 31,
                            am=(amdr >> 7) & 1, dr=amdr & 31,
                            dt2=(dt2sr >> 6) & 3, sr=dt2sr & 31,
                            sl=(susrel >> 4) & 15, rr=susrel & 15,
                            ssgeg=ssgeg))
        delay_on, delay_off = struct.unpack_from(">HH", d, b + 65)
        blank = (delay_on == 0 and delay_off == 0)
        fb, alg = (fbalg >> 3) & 7, fbalg & 7
        rows.append(dict(gm=bi * 128 + n + 1, name=name, alg=alg, fb=fb,
                         ops=ops, blank=blank, noteoff=noteoff))
        if blank:
            stats["blank"] += 1
        for l, o in enumerate(ops):
            if dt_signed[o["dt"]] != 0:
                stats["dt_nonzero"] += 1
            if o["dt2"]:
                stats["dt2_nonzero"] += 1
            if o["am"]:
                stats["am"] += 1
            if o["rs"]:
                stats["rs"] += 1
            if o["ssgeg"]:
                stats["ssgeg"] += 1
            stats["mul_hist"][o["mul"]] += 1
            if l < 3:
                stats["tl_mod"].append(o["tl"])
            else:
                stats["tl_car"].append(o["tl"])
        stats["fb_hist"][fb] += 1
        stats["alg_hist"][alg] += 1
        if noteoff:
            stats["noteoff"] += 1

print("blanks=%d dt!=0 ops=%d dt2!=0=%d am=%d rs=%d ssgeg=%d noteoff!=0=%d"
      % (stats["blank"], stats["dt_nonzero"], stats["dt2_nonzero"],
         stats["am"], stats["rs"], stats["ssgeg"], stats["noteoff"]))
print("fb hist:", list(stats["fb_hist"]))
print("alg hist:", list(stats["alg_hist"]))
print("mul hist:", list(stats["mul_hist"]))
tm = np.array(stats["tl_mod"]); tc = np.array(stats["tl_car"])
print("tl modulators: p5=%d p50=%d p95=%d" % tuple(np.percentile(tm, [5, 50, 95]).astype(int)))
print("tl carriers:   p5=%d p50=%d p95=%d" % tuple(np.percentile(tc, [5, 50, 95]).astype(int)))
print("first 6 + last 2 names:", [r["name"][:16] for r in rows[:6]], [r["name"][:16] for r in rows[-2:]])
print("sample rows:")
for g in (1, 33, 39, 41, 49, 61, 128):
    r = rows[g - 1]
    print("  gm%-3d %-18s alg%d fb%d ops:" % (r["gm"], r["name"][:18], r["alg"], r["fb"]),
          [(o["mul"], dt_signed[o["dt"]], o["tl"], o["ar"], o["dr"], o["sr"], o["rr"], o["sl"]) for o in r["ops"]])
blanks = [r["gm"] for r in rows if r["blank"]]
print("blank GM numbers:", blanks)
no = [(r["gm"], r["noteoff"]) for r in rows if r["noteoff"]]
print("note offsets:", no[:12])
