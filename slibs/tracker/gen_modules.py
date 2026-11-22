#!/usr/bin/env python3
"""Generates the bundled tracker demo material:
   app/src/main/assets/examples/slibs/tracker/
     song.xm  - FastTracker II XM, 3 instruments / 2 patterns
     song.mod - ProTracker MOD (M.K.), same tune
     ins1.wav / ins2.wav / ins3.wav - 16-bit mono wavs with smpl chunks
     (base note + loop) matching the module samples, for [smp~].
The bytes mirror exactly what slibs/tracker/src/modseq.c parses.
"""
import math, os, struct

OUT = os.path.join(os.path.dirname(__file__),
                   "..", "..", "app", "src", "main", "assets",
                   "examples", "slibs")
SR = 8363

# ----------------------------------------------------------------- voices

def bass(n):     # C3 square, loops over its tail
    return 0.5 * math.copysign(1.0, math.sin(2 * math.pi * 130.81 * n / SR))

def lead(n):     # C4 sine
    return 0.7 * math.sin(2 * math.pi * 261.63 * n / SR)

def pluck(n):    # C5 decayed dual sine
    t = n / SR
    return (0.6 * math.exp(-t * 6) * math.sin(2 * math.pi * 523.25 * t) +
            0.25 * math.exp(-t * 9) * math.sin(2 * math.pi * 1046.5 * t))

INS = [  # (name, fn, frames, loopstart, looplen, vol, relnote)
    ("bass",  bass,  3000, 1000, 2000, 64, 12),   # base 48
    ("lead",  lead,  4000,    0,    0, 64,  0),   # base 60
    ("pluck", pluck, 4000,    0,    0, 64,  0),   # base 60
]

# ------------------------------------------------------------------ score
# (row, ch, ins, midinote, fx, fxp)
P0 = [
    (0,  0, 1, 48, 0, 0), (0,  1, 2, 64, 0, 0), (0,  2, 3, 76, 0, 0),
    (8,  0, 1, 48, 0, 0), (8,  1, 2, 67, 0, 0), (10, 2, 3, 79, 0, 0),
    (16, 0, 1, 48, 0, 0), (16, 1, 2, 69, 0, 0), (18, 2, 3, 81, 0, 0),
    (20, 1, 0, 0, 0xa, 0x11),                    # lead: volume slide up
    (24, 0, 1, 48, 0, 0), (24, 1, 2, 72, 0, 0), (26, 2, 3, 84, 0, 0),
    (28, 1, 0, 0, 0xc, 40),                      # lead: set volume 40
    (32, 0, 1, 43, 0, 0), (32, 1, 2, 71, 0, 0), (34, 2, 3, 79, 0, 0),
    (40, 0, 1, 43, 0, 0), (40, 1, 2, 67, 0, 0), (42, 2, 3, 76, 0, 0),
    (48, 0, 1, 45, 0, 0), (48, 1, 2, 64, 0, 0), (50, 2, 3, 76, 0, 0),
    (56, 0, 1, 45, 0, 0), (56, 1, 2, 62, 0, 0),
    (60, 1, 0, 0, 0, 0),                         # key-off row in the XM
]
P1 = [
    (0,  0, 1, 43, 0, 0), (0,  1, 2, 65, 0, 0), (0,  2, 3, 77, 0, 0),
    (8,  0, 1, 43, 0, 0), (8,  1, 2, 69, 0, 0), (10, 2, 3, 81, 0, 0),
    (16, 0, 1, 43, 0, 0), (16, 1, 2, 72, 0, 0), (18, 2, 3, 84, 0, 0),
    (24, 0, 1, 48, 0, 0), (24, 1, 2, 74, 0, 0), (26, 2, 3, 86, 0, 0),
    (32, 0, 1, 48, 0, 0), (32, 1, 2, 76, 0, 0), (34, 2, 3, 88, 0, 0),
    (40, 0, 1, 48, 0, 0), (40, 1, 2, 72, 0, 0), (42, 2, 3, 84, 0, 0),
    (48, 0, 1, 45, 0, 0), (48, 1, 2, 71, 0, 0), (50, 2, 3, 79, 0, 0),
    (56, 0, 1, 45, 0, 0), (56, 1, 2, 67, 0, 0), (58, 2, 3, 76, 0, 0),
    (62, 1, 0, 0, 0xc, 20),                      # lead fade-down at song end
]
ORDERS = [0, 1, 0, 1]
NCH = 4
ROWS = 64

# -------------------------------------------------------------- XM writer

def xm_sample_bytes(fn, frames):
    s16 = [max(-32768, min(32767, int(fn(i) * 32767))) for i in range(frames)]
    s8 = [v >> 8 for v in s16]
    delta = bytearray(frames)
    prev = 0
    for i, v in enumerate(s8):
        delta[i] = (v - prev) & 0xff
        prev = v
    return bytes(delta), s16

def xm_event(note=0, ins=0, vol=0, fx=0, fxp=0, keyoff=False):
    if keyoff:
        return bytes([0x80 | 0x01, 254])          # note field = key-off
    flags = 0
    body = bytearray()
    if note:
        flags |= 0x01
        body.append(note)
    if ins:
        flags |= 0x02
        body.append(ins)
    if vol:
        flags |= 0x04
        body.append(vol)
    if fx or fxp:
        flags |= 0x08 | 0x10
        body.append(fx)
        body.append(fxp)
    if not flags:
        return b"\x00"
    return bytes([0x80 | flags]) + bytes(body)

def xm_pattern(events, keyoff_rows=()):
    rows = []
    grid = {}
    for (r, c, ins, note, fx, fxp) in events:
        if (r, c) in grid:
            raise SystemExit(f"duplicate row event at {r},{c}")
        grid[(r, c)] = (ins, note, fx, fxp)
    for r in range(ROWS):
        for c in range(NCH):
            if (r, c) not in grid:
                rows.append(b"\x00")
                continue
            ins, note, fx, fxp = grid[(r, c)]
            if c == 1 and r in keyoff_rows:
                rows.append(xm_event(keyoff=True))
            elif note:
                rows.append(xm_event(note=note - 11, ins=ins, fx=fx, fxp=fxp))
            else:
                rows.append(xm_event(ins=ins, fx=fx, fxp=fxp))
    packed = b"".join(rows)
    return struct.pack("<IBHH", 9, 0, ROWS, len(packed)) + packed

def write_xm(path, keyoff_rows=()):
    out = bytearray()
    out += b"Extended Module: "
    out += b"\x1a"
    out += b"PocketPd tracker    ".ljust(20, b" ")
    out += struct.pack("<H", 0x0104)
    out += struct.pack("<I", 276)          # header size
    out += struct.pack("<HHHHHHHHH",
                       len(ORDERS), 0, 256, NCH, 2, len(INS), 0, 6, 125)
    out += bytes(ORDERS) + bytes(256 - len(ORDERS))
    out += xm_pattern(P0, keyoff_rows)
    out += xm_pattern(P1, keyoff_rows)
    for (name, fn, frames, lstart, llen, vol, rel) in INS:
        data, _ = xm_sample_bytes(fn, frames)
        out += struct.pack("<I", 33)               # instrument header size
        out += name.encode().ljust(22, b"\x00")
        out += struct.pack("<BH", 0, 1)            # type, nsamples
        out += struct.pack("<I", 40)               # sample header size
        stype = 1 if llen else 0
        out += struct.pack("<III", frames, lstart, llen)
        out += struct.pack("<BBBBbB", vol, 0, stype, 128, rel, 0)
        out += name.encode().ljust(22, b"\x00")
        out += data
    with open(path, "wb") as f:
        f.write(bytes(out))
    print("wrote", path, len(out), "bytes")

# ------------------------------------------------------------- MOD writer

def mod_period(note):
    p = round(428 * 2 ** ((48 - note) / 12))
    return max(14, min(8000, p))

def write_mod(path):
    out = bytearray()
    out += b"pocketpd mod demo  ".ljust(20, b"\x00")
    for (name, fn, frames, lstart, llen, vol, rel) in INS:
        out += name.encode().ljust(22, b"\x00")
        out += struct.pack(">H", frames)
        out += struct.pack("<BB", 0, vol)          # finetune (LE nibble), vol
        out += struct.pack(">HH", lstart, llen)
    out += bytes(30 * (31 - len(INS)))             # unused instrument slots
    out += struct.pack("<BB", len(ORDERS), 0x7f)
    out += bytes(ORDERS) + bytes(128 - len(ORDERS))
    out += b"M.K."
    for pat in (P0, P1):
        for r in range(ROWS):
            for c in range(NCH):
                ev = [e for e in pat if e[0] == r and e[1] == c]
                if not ev or ev[0][3] == 0:
                    out += b"\x00\x00\x00\x00"
                    continue
                (_, _, ins, note, fx, fxp) = ev[0]
                if note:
                    p = mod_period(note)
                    out += bytes([(p >> 8) & 0x0f, p & 0xff,
                                  ((ins << 4) | fx) & 0xff, fxp & 0xff])
                else:
                    # effect-only row (slides/volumes): no period, no ins
                    out += bytes([0, 0, fx & 0x0f, fxp & 0xff])
    for (name, fn, frames, lstart, llen, vol, rel) in INS:
        _, s16 = xm_sample_bytes(fn, frames)
        out += bytes((v >> 8) & 0xff for v in s16)
    with open(path, "wb") as f:
        f.write(bytes(out))
    print("wrote", path, len(out), "bytes")

# ------------------------------------------------------------- wav writer

def write_wav(path, fn, frames, base, loop, lstart, llen):
    _, s16 = xm_sample_bytes(fn, frames)
    data = b"".join(struct.pack("<h", v) for v in s16)
    smpl = struct.pack("<IIIIIIII", 0, 0, SR * 2, base, 0, 0, 0,
                       1 if loop else 0) + struct.pack("<I", 0)
    if loop:
        smpl += struct.pack("<IIIIII", 0, 0, lstart, lstart + llen - 1, 0, 0)
    riff = 4 + (8 + 16) + (8 + len(data)) + (8 + len(smpl))
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", riff) + b"WAVE")
        f.write(b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16))
        f.write(b"data" + struct.pack("<I", len(data)) + data)
        f.write(b"smpl" + struct.pack("<I", len(smpl)) + smpl)
    print("wrote", path)

# -------------------------------------------------------------------- main

os.makedirs(OUT, exist_ok=True)
# rows where the lead gets a K00 key-off in the XM (end of the phrase)
KEYOFF = (58,)
write_xm(os.path.join(OUT, "song.xm"), KEYOFF)
write_mod(os.path.join(OUT, "song.mod"))
for i, (name, fn, frames, lstart, llen, vol, rel) in enumerate(INS):
    write_wav(os.path.join(OUT, f"ins{i + 1}.wav"), fn, frames,
              60 - rel, 1 if llen else 0, lstart, llen)
