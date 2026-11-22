#!/usr/bin/env python3
"""Generate the PocketPd NES demo files: demo_nes.vgm (2A03 register
log for vgm~ + nes~), demo_nes.nsf and demo_nes.nsfe (a tiny 6502
engine for nsf~).  Same tune everywhere so the three paths can be A/B'd
against each other.  Pure 2A03: 2 pulses + triangle + noise; the NSF
engine also triggers a small DPCM kick (sample data can't ride VGM
register writes, so the .vgm has no DPCM - documented in the example).

NSF layout per nesdev.org/wiki/NSF: header 0x00-0x7F with the text
fields at $00E/$02E/$04E, play speeds in MICROSECONDS at $06E (NTSC)
and $078 (PAL), bankswitch init values at $070-$077, PAL/NTSC bits at
$07A (bit0 PAL, bit1 dual), expansion bits at $07B (VRC6/VRC7/FDS/
MMC5/N163/5B), data at $080.
"""
import math, struct, os

CLOCK = 1789773
OUT = "C:/Projects/26PDAndroid/app/src/main/assets/examples/pocketpd"

def midi_f(m):
    return 440.0 * 2 ** ((m - 69) / 12)

def pulse_t(m):
    if m == 0:
        return None
    t = round(CLOCK / (16 * midi_f(m))) - 1
    return max(0, min(0x7ff, t))

def tri_t(m):
    if m == 0:
        return None
    t = round(CLOCK / (32 * midi_f(m))) - 1
    return max(0, min(0x7ff, t))

# ------------------------------------------------------------- the tune
# 64 frames (1 frame = 1/60 s), pulse/tri note lengths 5/15 frames.
MELODY = [
    (0, 76, 12), (6, 74, 11), (12, 72, 12), (18, 67, 10),
    (24, 72, 12), (30, 76, 11), (36, 79, 12), (42, 76, 10),
    (48, 74, 12), (54, 72, 11), (60, 74, 12),
]
HARMONY = [(f, m - 5, max(4, v - 3)) for (f, m, v) in MELODY]
BASS = [
    (0, 43, 15), (16, 43, 15), (32, 41, 15), (48, 41, 15),
]
HAT = [f for f in range(0, 64, 4)]          # noise, every 4 frames
KICK = [f for f in range(0, 64, 8)]         # DPCM kick, every 8 frames
LENGTH = 64

# ----------------------------------------------------------- demo_nes.vgm
def make_vgm():
    cmds = bytearray()
    # power-up: disables + frame counter (inhibit, 4-step)
    cmds += bytes([0xBC, 0x15, 0x00])
    cmds += bytes([0xBC, 0x17, 0x40])
    for f in range(LENGTH):
        notes1 = [x for x in MELODY if x[0] == f]
        notes2 = [x for x in HARMONY if x[0] == f]
        bass = [x for x in BASS if x[0] == f]
        hat = f in HAT
        if notes1:
            _, m, v = notes1[0]
            t = pulse_t(m)
            vol = max(1, round(v * 15 / 12))
            cmds += bytes([0xBC, 0x00, 0x80 | 0x20 | 0x10 | vol])
            cmds += bytes([0xBC, 0x02, t & 0xff])
            cmds += bytes([0xBC, 0x03, 0x80 | (t >> 8)])
            cmds += bytes([0xBC, 0x15, 0x07])   # pulse1+2+tri on
        if notes2:
            _, m, v = notes2[0]
            t = pulse_t(m)
            vol = max(1, round(v * 15 / 12))
            cmds += bytes([0xBC, 0x04, 0x80 | 0x20 | 0x10 | vol])
            cmds += bytes([0xBC, 0x06, t & 0xff])
            cmds += bytes([0xBC, 0x07, 0x80 | (t >> 8)])
        if bass:
            _, m, v = bass[0]
            t = tri_t(m)
            cmds += bytes([0xBC, 0x08, 0xff])   # linear counter halt
            cmds += bytes([0xBC, 0x0a, t & 0xff])
            cmds += bytes([0xBC, 0x0b, 0x80 | (t >> 8)])
        if hat:
            cmds += bytes([0xBC, 0x0c, 0x3f])   # halt+const, vol 15
            cmds += bytes([0xBC, 0x0e, 0x08])   # long mode, period idx 8
            cmds += bytes([0xBC, 0x0f, 0x80])   # length load
        # note-offs land 5 frames after each note starts (15 for bass)
        for (fr, m, v) in MELODY:
            if f == fr + 5:
                cmds += bytes([0xBC, 0x15, 0x06])   # pulse1 off
        for (fr, m, v) in HARMONY:
            if f == fr + 5:
                cmds += bytes([0xBC, 0x15, 0x05])   # pulse2 off
        for (fr, m, v) in BASS:
            if f == fr + 15:
                cmds += bytes([0xBC, 0x15, 0x03])   # tri off
        cmds += bytes([0x62])                   # one NTSC frame (735)
    cmds += bytes([0xBC, 0x15, 0x00])
    cmds += bytes([0x62, 0x62])
    cmds += bytes([0x66])

    total = LENGTH + 2
    hdr = bytearray(0x80)
    hdr[0:4] = b"Vgm "
    hdr[0x04:0x08] = struct.pack("<I", 0x80 + len(cmds) - 4)
    hdr[0x08:0x0c] = struct.pack("<I", 0x151)
    hdr[0x18:0x1c] = struct.pack("<I", total * 735)
    hdr[0x1c:0x20] = struct.pack("<I", 0x34)    # loop offset rel 0x1c
    hdr[0x20:0x24] = struct.pack("<I", total * 735)
    hdr[0x34:0x38] = struct.pack("<I", 0x0c)
    hdr[0x80:0x84] = struct.pack("<I", CLOCK)   # NES APU clock
    out = bytes(hdr) + bytes(cmds)
    with open(os.path.join(OUT, "demo_nes.vgm"), "wb") as fp:
        fp.write(out)
    print("demo_nes.vgm: %d bytes, %d frames" % (len(out), LENGTH))

# ------------------------------------------------------------ 6502 engine
# zero page: $F0 = step counter.  Tables in ROM: mel1/mel2/bass = 4
# bytes per entry (frame, t-lo, t-hi, vol), 0xFF end; the engine walks
# the entries with X (stepping 4) every frame: a frame hit re-keys the
# channel (period + length load + envelope restart), a hit at
# frame+5 (bass: +15) clears the channel's enable bit.

OPS = {
    ("LDA", "imm"): 0xA9, ("LDA", "zp"): 0xA5, ("LDA", "abs"): 0xAD,
    ("LDA", "absx"): 0xBD,
    ("LDX", "imm"): 0xA2,
    ("STA", "zp"): 0x85, ("STA", "abs"): 0x8D, ("STA", "absx"): 0x9D,
    ("AND", "imm"): 0x29, ("AND", "abs"): 0x2D, ("ORA", "imm"): 0x09,
    ("CMP", "imm"): 0xC9, ("CMP", "zp"): 0xC5, ("CMP", "abs"): 0xCD,
    ("ADC", "imm"): 0x69,
    ("INC", "zp"): 0xE6, ("INX", "imp"): 0xE8,
    ("TAY", "imp"): 0xA8,
    ("SEI", "imp"): 0x78, ("CLD", "imp"): 0xD8, ("CLC", "imp"): 0x18,
    ("JSR", "abs"): 0x20, ("RTS", "imp"): 0x60, ("JMP", "abs"): 0x4C,
    ("BNE", "rel"): 0xD0, ("BEQ", "rel"): 0xF0,
}

class Asm:
    """single-segment two-pass assembler; label refs may carry an
    offset: 'mel1+1'.  self.pc tracks the emission address so label()
    and the $C000 padding loop work."""
    SIZES = {"imp": 1, "rel": 2, "imm": 2, "zp": 2, "abs": 3,
             "absx": 3, "absy": 3}

    def __init__(self, org):
        self.org = org
        self.labels = {}
        self.items = []
        self.pc = org

    def label(self, name):
        self.labels[name] = self.pc

    def _add(self, size, item):
        self.items.append(item)
        self.pc += size

    def db(self, *bs):
        for b in bs:
            self._add(1, ("b", b))

    def op(self, name, addr=None, mode="imp"):
        size = self.SIZES[mode]
        self._add(1, ("b", OPS[(name, mode)]))
        if size > 1:
            t = {"rel": "rel", "imm": "z", "zp": "z",
                 "abs": "w", "absx": "w", "absy": "w"}[mode]
            self._add(size - 1, (t, addr))

    @staticmethod
    def _ref(ref):
        if isinstance(ref, str):
            if "+" in ref:
                base, off = ref.split("+")
                return base, int(off)
            if "-" in ref:
                base, off = ref.split("-")
                return base, -int(off)
            return ref, 0
        return None, ref

    def resolve(self):
        out = bytearray()
        for _ in (1, 2):
            out = bytearray()
            pc = self.org
            for t, v in self.items:
                if t == "b":
                    out.append(v & 0xff); pc += 1
                elif t == "z":
                    if isinstance(v, str):
                        base, off = self._ref(v)
                        a = self.labels[base] + off
                    else:
                        a = v
                    out.append(a & 0xff); pc += 1
                elif t == "w":
                    if isinstance(v, str):
                        base, off = self._ref(v)
                        a = self.labels[base] + off
                    else:
                        a = v
                    out += bytes([a & 0xff, (a >> 8) & 0xff]); pc += 2
                elif t == "rel":
                    base, off = self._ref(v)
                    a = self.labels[base] + off
                    delta = a - (pc + 1)
                    assert -128 <= delta <= 127, "branch out of range"
                    out.append(delta & 0xff); pc += 1
            if _ == 1:
                assert pc == self.pc,                     "emission pc (%x) != resolve pc (%x)" % (self.pc, pc)
        return bytes(out)

def make_nsf_blob():
    a = Asm(0x8000)
    STEP = 0xF0

    a.label("init")
    a.op("SEI"); a.op("CLD")
    a.op("LDA", 0x40, "imm"); a.op("STA", 0x4017, "abs")
    a.op("LDA", 0x00, "imm"); a.op("STA", 0x4015, "abs")
    a.op("LDA", 0x00, "imm"); a.op("STA", STEP, "zp")
    a.op("LDA", 0xFF, "imm"); a.op("STA", 0x4008, "abs")
    a.op("RTS")

    a.label("play")
    a.op("INC", STEP, "zp")
    a.op("LDA", STEP, "zp")
    a.op("CMP", 64, "imm")
    a.op("BNE", "nwrap", "rel")
    a.op("LDA", 0x00, "imm"); a.op("STA", STEP, "zp")
    a.label("nwrap")

    # --- pulse 1 melody scan (X = byte cursor over mel1, step 4) ---
    a.op("LDX", 0x00, "imm")
    a.label("p1loop")
    a.op("LDA", "mel1", "absx")
    a.op("CMP", 0xFF, "imm")
    a.op("BEQ", "p1done", "rel")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "p1hit", "rel")
    a.op("LDA", "mel1", "absx")     # reload: PLA would clobber Z
    a.op("CLC")
    a.op("ADC", 5, "imm")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "p1off", "rel")
    a.label("p1next")
    a.op("INX"); a.op("INX"); a.op("INX"); a.op("INX")
    a.op("JMP", "p1loop", "abs")
    a.label("p1hit")
    a.op("LDA", "mel1+1", "absx"); a.op("STA", 0x4002, "abs")
    a.op("LDA", "mel1+2", "absx"); a.op("ORA", 0x80, "imm")
    a.op("STA", 0x4003, "abs")
    a.op("LDA", "mel1+3", "absx"); a.op("ORA", 0xB0, "imm")
    a.op("STA", 0x4000, "abs")
    a.op("LDA", 0x0F, "imm"); a.op("STA", 0x4015, "abs")
    a.op("JMP", "p2scan", "abs")
    a.label("p1off")
    a.op("LDA", 0x4015, "abs"); a.op("AND", 0xFE, "imm")
    a.op("STA", 0x4015, "abs")
    a.op("JMP", "p1next", "abs")

    # --- pulse 2 harmony scan ---
    a.label("p1done")
    a.label("p2scan")
    a.op("LDX", 0x00, "imm")
    a.label("p2loop")
    a.op("LDA", "mel2", "absx")
    a.op("CMP", 0xFF, "imm")
    a.op("BEQ", "p2done", "rel")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "p2hit", "rel")
    a.op("LDA", "mel2", "absx")
    a.op("CLC")
    a.op("ADC", 5, "imm")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "p2off", "rel")
    a.label("p2next")
    a.op("INX"); a.op("INX"); a.op("INX"); a.op("INX")
    a.op("JMP", "p2loop", "abs")
    a.label("p2hit")
    a.op("LDA", "mel2+1", "absx"); a.op("STA", 0x4006, "abs")
    a.op("LDA", "mel2+2", "absx"); a.op("ORA", 0x80, "imm")
    a.op("STA", 0x4007, "abs")
    a.op("LDA", "mel2+3", "absx"); a.op("ORA", 0xB0, "imm")
    a.op("STA", 0x4004, "abs")
    a.op("LDA", 0x0F, "imm"); a.op("STA", 0x4015, "abs")
    a.op("JMP", "bassscan", "abs")
    a.label("p2off")
    a.op("LDA", 0x4015, "abs"); a.op("AND", 0xFD, "imm")
    a.op("STA", 0x4015, "abs")
    a.op("JMP", "p2next", "abs")

    # --- triangle bass scan ---
    a.label("p2done")
    a.label("bassscan")
    a.op("LDX", 0x00, "imm")
    a.label("bassloop")
    a.op("LDA", "bass", "absx")
    a.op("CMP", 0xFF, "imm")
    a.op("BEQ", "bassdone", "rel")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "basshit", "rel")
    a.op("LDA", "bass", "absx")
    a.op("CLC")
    a.op("ADC", 15, "imm")
    a.op("CMP", STEP, "zp")
    a.op("BEQ", "bassoff", "rel")
    a.label("bassnext")
    a.op("INX"); a.op("INX"); a.op("INX"); a.op("INX")
    a.op("JMP", "bassloop", "abs")
    a.label("basshit")
    a.op("LDA", "bass+1", "absx"); a.op("STA", 0x400A, "abs")
    a.op("LDA", "bass+2", "absx"); a.op("ORA", 0x80, "imm")
    a.op("STA", 0x400B, "abs")
    a.op("LDA", 0x0F, "imm"); a.op("STA", 0x4015, "abs")
    a.op("JMP", "hatscan", "abs")
    a.label("bassoff")
    a.op("LDA", 0x4015, "abs"); a.op("AND", 0xFB, "imm")
    a.op("STA", 0x4015, "abs")
    a.op("JMP", "bassnext", "abs")

    # --- noise hat every 4 frames ---
    a.label("bassdone")
    a.label("hatscan")
    a.op("LDA", STEP, "zp"); a.op("AND", 0x03, "imm")
    a.op("BNE", "hatdone", "rel")
    a.op("LDA", 0x3F, "imm"); a.op("STA", 0x400C, "abs")
    a.op("LDA", 0x08, "imm"); a.op("STA", 0x400E, "abs")
    a.op("LDA", 0x80, "imm"); a.op("STA", 0x400F, "abs")
    a.op("LDA", 0x4015, "abs"); a.op("ORA", 0x08, "imm")
    a.op("STA", 0x4015, "abs")
    a.label("hatdone")

    # --- DPCM kick every 8 frames (sample at $C000, A=0) ---
    a.label("kickscan")
    a.op("LDA", STEP, "zp"); a.op("AND", 0x07, "imm")
    a.op("BNE", "kickdone", "rel")
    a.op("LDA", 0x0F, "imm"); a.op("STA", 0x4010, "abs")
    a.op("LDA", 0x00, "imm"); a.op("STA", 0x4012, "abs")
    a.op("LDA", 0x03, "imm"); a.op("STA", 0x4013, "abs")
    a.op("LDA", 0x4015, "abs"); a.op("ORA", 0x10, "imm")
    a.op("STA", 0x4015, "abs")
    a.label("kickdone")
    a.op("RTS")

    # --- tables ---
    a.label("mel1")
    for (f, m, v) in MELODY:
        t = pulse_t(m)
        a.db(f, t & 0xff, t >> 8, max(1, round(v * 15 / 12)))
    a.db(0xFF)
    a.label("mel2")
    for (f, m, v) in HARMONY:
        t = pulse_t(m)
        a.db(f, t & 0xff, t >> 8, max(1, round(v * 15 / 12)))
    a.db(0xFF)
    a.label("bass")
    for (f, m, v) in BASS:
        t = tri_t(m)
        a.db(f, t & 0xff, t >> 8, 0)
    a.db(0xFF)

    # DPCM kick sample at $C000: 49 bytes, fall then rise (plain delta
    # patterns - the kick is a level sweep, exactly what a raw $4012/
    # $4013 test wants)
    while a.pc < 0xC000:
        a.db(0xEA)                  # NOP fill up to the $4012 window
    assert a.pc == 0xC000
    a.label("dpcm")
    for i in range(49):
        a.db(0x00 if i < 20 else (0xFF if i < 30 else 0x00))

    blob = a.resolve()
    print("engine: %d bytes, init=0x8000 play=0x%x tables mel1=0x%x "
          "dpcm=0x%x" % (len(blob), a.labels["play"], a.labels["mel1"],
                         a.labels["dpcm"]))
    return blob, a.labels

def make_nsf():
    blob, labels = make_nsf_blob()
    assert labels["dpcm"] >= 0xC000 and \
        (labels["dpcm"] - 0xC000) % 64 == 0, \
        "DPCM sample must sit on a 64-byte boundary in $C000+"
    hdr = bytearray(0x80)
    hdr[0:5] = b"NESM\x1a"
    hdr[5] = 1
    hdr[6] = 1
    hdr[7] = 1
    hdr[8:10] = struct.pack("<H", 0x8000)
    hdr[10:12] = struct.pack("<H", 0x8000)
    hdr[12:14] = struct.pack("<H", labels["play"])
    hdr[0x0E:0x2E] = b"PocketPd NES demo".ljust(32, b"\0")
    hdr[0x2E:0x4E] = b"PocketPd".ljust(32, b"\0")
    hdr[0x4E:0x6E] = b"CC0".ljust(32, b"\0")
    hdr[0x6E:0x70] = struct.pack("<H", 16639)  # NTSC play speed, us
    hdr[0x78:0x7A] = struct.pack("<H", 19997)  # PAL play speed, us
    hdr[0x7A] = 0
    hdr[0x7B] = 0
    out = bytes(hdr) + blob
    with open(os.path.join(OUT, "demo_nes.nsf"), "wb") as fp:
        fp.write(out)
    print("demo_nes.nsf: %d bytes" % len(out))

def make_nsfe():
    blob, labels = make_nsf_blob()
    def chunk(tag, data):
        return tag + struct.pack("<I", len(data)) + data
    info = bytes([1, 1, 1]) + struct.pack("<HHH", 0x8000, 0x8000, 0x8000) \
        + bytes([0]) + struct.pack("<H", 0)
    body = chunk(b"INFO", info) + chunk(b"DATA", blob) \
        + chunk(b"RATE", struct.pack("<HH", 16639, 19997)) \
        + chunk(b"time", struct.pack("<I", 1100)) \
        + chunk(b"auth", b"PocketPd NES demo\x00PocketPd\x00CC0\x00") \
        + chunk(b"NEND", b"")
    out = b"NSFE" + body
    with open(os.path.join(OUT, "demo_nes.nsfe"), "wb") as fp:
        fp.write(out)
    print("demo_nes.nsfe: %d bytes" % len(out))

if __name__ == "__main__":
    make_vgm()
    make_nsf()
    make_nsfe()
