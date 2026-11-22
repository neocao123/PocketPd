#!/usr/bin/env python3
"""Regenerates assets/examples/pocketpd/demo_opl3.vgm - a 4-note arpeggio
on YMF262 (OPL3) channel 0 as a minimal working example for [opl3~].

Frequency derivation (from the vendored Nuked-OPL3 core, the ground
truth this build plays): phase increment per native sample =
((FNUM << BLK) * mt[MUL]) >> 2 with a 2^19-unit cycle (phase = pg_phase
>> 9, sin cycle 2^10 phase units), native rate 49716 Hz (clock
14318181/288).  Hence

    f = FNUM * 2^BLK * mt[MUL] * 49716 / 2^21

The mt table is {1,2,4,6,...}: MUL 0 = half-rate, MUL 1 = the x1
multiplier (mt = 2).  The first version of this generator used the
MUL-0 constant (49716/2^21) while writing MUL-1 registers - every demo
note came out exactly one octave sharp (caught on-device: sigmund~
reported 880-subharmonics for the intended 440 tone).  All demo tones
use MUL 1, so BASE is the x1 rate 49716/2^20 = the OPL2 datasheet
formula at the OPL3's 4x clock; logs composed for real hardware map
1:1.  Round-trip verified on-device (see CHANGELOG 0.17.113).
"""
import struct

CLOCK = 14318181
NATIVE = CLOCK / 288.0                     # 49716, hardcoded by the core
BASE = NATIVE / (1 << 20)                  # MUL 1 = the x1 multiplier

def fnum_blk(f):
    """Pick BLK so FNUM lands in [400, 1023]."""
    for blk in range(0, 8):
        fn = int(round(f / BASE / (1 << blk)))
        if 400 <= fn <= 1023:
            return fn, blk
    raise ValueError("frequency out of range: %f" % f)

notes = [52, 56, 59, 64, 59, 56]          # E3 G#3 B3 E4 B3 G#3
out = bytearray()

# header v1.60 (first version declaring the YMF262 clock field at 0x5c);
# the header extends to 0x80 and the data offset points past it
hdr = bytearray(0x80)
hdr[0:4] = b"Vgm "
struct.pack_into("<I", hdr, 4, 0x80)       # patched below (rel EOF)
struct.pack_into("<I", hdr, 8, 0x160)      # version 1.60
struct.pack_into("<I", hdr, 0x34, 0x4c)    # data offset: 0x34+0x4c = 0x80
struct.pack_into("<I", hdr, 0x5c, CLOCK)   # YMF262 (OPL3) clock
# no chip-flags bytes: v1.60 flag offsets vary and players default
# clock-declared chips to enabled when flags are unset; this app's
# census is clock-based anyway

def w(a, d=None, port=0):
    out.append(0x5e + port)
    out.append(a)
    if d is not None:
        out.append(d)

# OPL3 mode on FIRST (port 1 reg 5 - exactly how real logs, e.g. the
# OpenMPT exports, enable the NEW-mode bit; bank-1 instrument writes
# depend on it)
w(0x05, 0x01, port=1)
# channel 0, 2-op FM: MUL 1 both operators, full scale, fast attack,
# sustain held, moderate release - a clean clarinet-ish carrier
w(0x20, 0x01)                              # mod: AM/VIB/EGT/KSR off, MUL 1
w(0x23, 0x21)                              # car: EGT 1 (sustained), MUL 1
w(0x40, 0x10)                              # mod: TL 16 (tames the FM grind)
w(0x43, 0x00)                              # car: TL 0 (full scale)
w(0x60, 0xf0)                              # mod: AR 15 / DR 0
w(0x63, 0xf0)                              # car: AR 15 / DR 0
w(0x80, 0x08)                              # mod: SL 0 / RR 8
w(0x83, 0x08)                              # car: SL 0 / RR 8
w(0xe0, 0x00)                              # mod: sine
w(0xe3, 0x00)                              # car: sine
w(0xc0, 0x30)                              # CON 0 (FM), L + R out
w(0xbd, 0x00)                              # rhythm off, depths off

NOTE = 735                                 # 1/60 s per arpeggio step
struct.pack_into("<I", hdr, 0x18, len(notes) * 2 * NOTE)  # total samples
for n in notes:
    f = 440.0 * (2 ** ((n - 69) / 12.0))
    fn, blk = fnum_blk(f)
    w(0xb0, 0x00)                          # key off (retrigger clean)
    w(0xa0, fn & 0xff)                     # FNUM low 8
    w(0xb0, 0x20 | (blk << 2) | (fn >> 8))  # key on, blk, FNUM hi 2
    out.append(0x62)                       # wait 735
    out.append(0x62)

w(0xb0, 0x00)
out.append(0x66)                           # end of sound data

struct.pack_into("<I", hdr, 4, 0x80 + len(out) - 4)   # rel EOF size
with open("app/src/main/assets/examples/pocketpd/demo_opl3.vgm", "wb") as f:
    f.write(bytes(hdr) + bytes(out))
print("demo_opl3.vgm written:", 0x80 + len(out), "bytes")
for n in notes:
    f = 440.0 * (2 ** ((n - 69) / 12.0))
    fn, blk = fnum_blk(f)
    print("  note %d: %.1f Hz -> FNUM %d blk %d (check %.1f Hz)"
          % (n, f, fn, blk, BASE * fn * (1 << blk)))
