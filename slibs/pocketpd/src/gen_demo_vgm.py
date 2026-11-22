#!/usr/bin/env python3
"""Regenerates assets/examples/pocketpd/demo.vgm - a 4-voice arpeggio on
YM2612 channel 0 (port 0 only) as a minimal working example for [vgm~].

History note (0.17.76): the first version of this file shipped FNUMs 16x
too high (E3 came out as ~2.6 kHz - the generator shifted the block
wrong) and omitted the 0xB0 (FB/ALG) and 0xB4 (L/R) writes entirely, so
it played shrill default-ALG0 blips.  FNUM formula verified against the
silicon: f = clock/(144*2^19) * FNUM * 2^BLK, clock = 7670454.
"""
import struct

CLOCK = 7670454
# datasheet: f = FNUM * 2^BLK * clock / (144 * 2^21)   (0.025399)
# (the first version of this generator used 2^19 here -> all notes 4x
# too low; verified against the engine by Goertzel: FNUM 1082 blk 4
# renders exactly 440.0 Hz at 48k AND 96k)
BASE = CLOCK / (144.0 * (1 << 21))

def fnum_blk(f):
    """Pick BLK so FNUM lands in [400, 1000]."""
    for blk in range(0, 8):
        fn = int(round(f / BASE / (1 << blk)))
        if 400 <= fn <= 1023:
            return fn, blk
    raise ValueError("frequency out of range: %f" % f)

notes = [52, 56, 59, 64, 59, 56]          # E3 G#3 B3 E4 B3 G#3
out = bytearray()

# header v1.51: 0x40 bytes + data
hdr = bytearray(0x40)
hdr[0:4] = b"Vgm "
struct.pack_into("<I", hdr, 4, 0x40)       # patched below (rel EOF)
struct.pack_into("<I", hdr, 8, 0x151)      # version 1.51
struct.pack_into("<I", hdr, 0x0c, 3579545) # SN76489 clock (unused here)
struct.pack_into("<I", hdr, 0x2c, CLOCK)   # YM2612 clock
struct.pack_into("<I", hdr, 0x34, 0x0c)    # data offset: 0x34+0x0c = 0x40
hdr[0x27] = 0x01                           # YM2612 present

def w(a, d=None):
    out.append(0x52)
    out.append(a)
    if d is not None:
        out.append(d)

w(0x22, 0x00)
w(0x27, 0x00)
w(0x2b, 0x00)
# ch0 op pages 0x30/0x34/0x38/0x3c; ALG 4 (op4 carrier), moderate levels
w(0x30, 0x01); w(0x34, 0x01); w(0x38, 0x01); w(0x3c, 0x01)
w(0x40, 0x2d); w(0x44, 0x3f); w(0x48, 0x3f); w(0x4c, 0x08)
w(0x50, 0x1f); w(0x54, 0x1f); w(0x58, 0x1f); w(0x5c, 0x1f)
w(0x60, 0x0c); w(0x64, 0x00); w(0x68, 0x00); w(0x6c, 0x10)
w(0x70, 0x00); w(0x74, 0x00); w(0x78, 0x00); w(0x7c, 0x00)
w(0x80, 0x87); w(0x84, 0x0f); w(0x88, 0x0f); w(0x8c, 0x0c)
w(0x90, 0x00); w(0x94, 0x00); w(0x98, 0x00); w(0x9c, 0x00)
w(0xb0, 0x40)                              # FB 0 / ALG 4 (clean-ish tone)
w(0xb4, 0xc0)                              # L + R out
w(0x28, 0x00)                              # all keys off

NOTE = 735                                 # 1/60 s per arpeggio step
for n in notes:
    f = 440.0 * (2 ** ((n - 69) / 12.0))
    fn, blk = fnum_blk(f)
    w(0x28, 0x00)                          # key off (retrigger clean)
    w(0xa4, (blk << 3) | (fn >> 8))        # blk + fnum hi (ONE data byte)
    w(0xa0, fn & 0xff)
    w(0x28, 0xf0)                          # key on ch0, all slots
    out.append(0x62)                       # wait 735
    out.append(0x62)

w(0x28, 0x00)
out.append(0x66)                           # end of sound data

struct.pack_into("<I", hdr, 4, 0x40 + len(out) - 4)   # rel EOF size
with open("app/src/main/assets/examples/pocketpd/demo.vgm", "wb") as f:
    f.write(bytes(hdr) + bytes(out))
print("demo.vgm written:", 0x40 + len(out), "bytes")
for n in notes:
    f = 440.0 * (2 ** ((n - 69) / 12.0))
    fn, blk = fnum_blk(f)
    print("  note %d: %.1f Hz -> FNUM %d blk %d (check %.1f Hz)"
          % (n, f, fn, blk, BASE * fn * (1 << blk)))
