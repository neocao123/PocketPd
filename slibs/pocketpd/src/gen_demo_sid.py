#!/usr/bin/env python3
"""Regenerates assets/examples/pocketpd/demo.sid - a three-voice tune
(pulse lead + saw bass + noise drums) as a hand-assembled 6502 PSID for
[sid~] and [sid6581~].

The file is a self-contained CIA-timed player (it programs CIA 1 timer A
itself, like a real C64 tune), which also exercises sid~'s CIA-latch
play-rate path on the pocketpd side.  Music: a 4-bar A-minor loop,
written note-by-note here so the shipped tune stays license-clean.

SID frequency register: f = F * clock / 2^24, clock = 985248 Hz (PAL).

Verification (0.17.88): harness29 runs the file through the shipped
sidreplay+reSID path; the register log must show FREQLO/FREQHI writes
landing per pattern step and the CIA latch re-deriving a 50.125 Hz play
interval.
"""
import struct
import sys

CLOCK = 985248
LOAD = 0x1000
SID = 0xD400
CIA = 0xDC00

# zero page scratch
Z_CNT  = 0xFB      # frame counter, drives drums + lead/bass steps
Z_LIDX = 0xFC      # lead pattern index
Z_BIDX = 0xFD      # bass pattern index
Z_TMP  = 0xFE      # scratch (note index)

# ---------------------------------------------------------------- tune
def sidf(midi):
    """midi note -> 16-bit SID frequency word"""
    f = 440.0 * 2 ** ((midi - 69) / 12.0)
    return round(f * (1 << 24) / CLOCK)

# 16-entry note table; patterns use 255 = gate-off frame
NOTES = [45, 48, 52, 55, 57, 60, 64, 67,     # A2 C3 E3 G3 A3 C4 E4 G4
         69, 72, 76, 79, 81, 84, 88, 91]     # A4 C5 E5 G5 A5 C6 E6 G6

# lead: 64 frames (4 bars x 16); two two-bar phrases, the second answers.
# Pattern encoding: note-table index = start that note (freq + retrig),
# 254 = hold the current note, 255 = gate off (rest).
HOLD = 254
REST = 255
LEAD = [
      8,HOLD,HOLD,HOLD, 10,HOLD,HOLD,HOLD, 11,HOLD,HOLD,HOLD,
     13,HOLD,HOLD,HOLD, 13,HOLD,HOLD,HOLD, 11,HOLD,HOLD,HOLD,
     10,HOLD,HOLD,HOLD,  8,HOLD,HOLD,HOLD,
     10,HOLD,HOLD,HOLD, 12,HOLD,HOLD,HOLD, 13,HOLD,HOLD,HOLD,
     14,HOLD,HOLD,HOLD, 14,HOLD,HOLD,HOLD, 13,HOLD,HOLD,HOLD,
     12,HOLD,HOLD,HOLD, 10,HOLD,HOLD,HOLD,
]
# bass: 32 frames, half notes: A2 - A2 - E3 - G3
BASS = [0] + [HOLD]*7 + [0] + [HOLD]*7 + [2] + [HOLD]*7 + [3] + [HOLD]*7
# drums: 16-frame loop - 1 = kick (high pitch noise), 2 = snare, 0 = off
DRUM = [1,0,0,0, 2,0,0,0, 1,0,1,0, 2,0,2,0]

assert len(LEAD) == 64 and len(BASS) == 32 and len(DRUM) == 16
for v in LEAD + BASS:
    assert v in (HOLD, REST) or 0 <= v < 16
for v in DRUM:
    assert v in (0, 1, 2)

# ------------------------------------------------------- mini assembler
OPS = {
    ('lda', '#'): 0xA9, ('lda', 'zp'): 0xA5, ('lda', 'abs'): 0xAD,
    ('lda', 'absx'): 0xBD,
    ('ldx', '#'): 0xA2, ('ldx', 'zp'): 0xA6,
    ('sta', 'zp'): 0x85, ('sta', 'abs'): 0x8D, ('sta', 'absx'): 0x9D,
    ('ora', '#'): 0x09, ('and', '#'): 0x29,
    ('inc', 'zp'): 0xE6,
    ('cmp', '#'): 0xC9,
    ('tax', ''): 0xAA,
    ('bne', 'rel'): 0xD0, ('beq', 'rel'): 0xF0,
    ('jmp', 'abs'): 0x4C,
    ('rts', ''): 0x60,
}
MODE_LEN = {'': 0, '#': 1, 'zp': 1, 'rel': 1, 'abs': 2, 'absx': 2}

class Asm:
    def __init__(self, org):
        self.org = org
        self.items = []      # ('label', name) / ('ins', (mn, mode, args))
        self.labels = {}

    def label(self, name):
        self.items.append(('label', name))

    def emit(self, mn, mode, *args):
        assert (mn, mode) in OPS, (mn, mode)
        self.items.append(('ins', (mn, mode, args)))

    def bytes(self):
        # pass 1: addresses
        addr = self.org
        for kind, p in self.items:
            if kind == 'label':
                self.labels[p] = addr
            else:
                mn, mode, args = p
                addr += 1 + MODE_LEN[mode]
        # pass 2: encode (unknown labels encode as $0000 placeholders -
        # used for the size probe before the table addresses are known;
        # the final encode runs with every label assigned)
        out = bytearray()
        for kind, p in self.items:
            if kind == 'label':
                continue
            mn, mode, args = p
            out.append(OPS[(mn, mode)])
            if mode == '':
                continue
            if mode == 'rel':
                tgt = args[0]
                if isinstance(tgt, str):
                    tgt = self.labels.get(tgt, self.org)
                rel = tgt - (self.org + len(out) + 1)
                assert -128 <= rel <= 127, (mn, rel)
                out.append(rel & 0xFF)
            else:
                a = args[0]
                if isinstance(a, str):
                    a = self.labels.get(a, 0)
                assert 0 <= a <= 0xFFFF, (mn, a)
                out.append(a & 0xFF)
                if MODE_LEN[mode] == 2:
                    out.append((a >> 8) & 0xFF)
        return bytes(out)

# ---------------------------------------------------------------- init
init = Asm(LOAD)
P = init.emit
# silence everything, then the instrument palette
P('lda', '#', 0x00)
for r in range(0x00, 0x19):
    P('sta', 'abs', SID + r)             # $D400..$D418 = 0
P('lda', '#', 0x0F)
P('sta', 'abs', SID + 0x18)             # volume 15
P('lda', '#', 0x40)                     # v1: pulse, gate off
P('sta', 'abs', SID + 0x04)
P('lda', '#', 0x20)                     # v2: saw, gate off
P('sta', 'abs', SID + 0x0B)
P('lda', '#', 0x80)                     # v3: noise, gate off
P('sta', 'abs', SID + 0x12)
# envelopes: lead pluck (a11 d7 / s1 r9), bass thump (a2 d9 / s6 r6),
# drum click (a0 d2 / s0 r8)
P('lda', '#', 0xB7)
P('sta', 'abs', SID + 0x05)
P('lda', '#', 0x19)
P('sta', 'abs', SID + 0x06)
P('lda', '#', 0x29)
P('sta', 'abs', SID + 0x0C)
P('lda', '#', 0x66)
P('sta', 'abs', SID + 0x0D)
P('lda', '#', 0x02)
P('sta', 'abs', SID + 0x15)
P('lda', '#', 0x08)
P('sta', 'abs', SID + 0x16)
# pulse width ~50%
P('lda', '#', 0x00)
P('sta', 'abs', SID + 0x02)
P('lda', '#', 0x08)
P('sta', 'abs', SID + 0x03)
# CIA 1 timer A = the classic 50 Hz PAL latch ($4CC7), start it
P('lda', '#', 0xC7)
P('sta', 'abs', CIA + 0x04)
P('lda', '#', 0x4C)
P('sta', 'abs', CIA + 0x05)
P('lda', '#', 0x01)
P('sta', 'abs', CIA + 0x0E)             # start timer A (one-shot? no:
                                        # 0x01 = continuous)
P('lda', '#', 0x00)
P('sta', 'zp', Z_LIDX)
P('sta', 'zp', Z_BIDX)
P('sta', 'zp', Z_CNT)
P('rts', '')

# ---------------------------------------------------------- play labels
# the play routine is first built with label NAMES; its real address is
# known only after init's size, so it is encoded twice (label pass + final)
play = Asm(0)                           # org patched before encoding
A = play.emit
A('inc', 'zp', Z_CNT)                   # one play call = one 50 Hz frame
# ---- lead (voice 1): every frame walks the 64-frame pattern
A('ldx', 'zp', Z_LIDX)
A('lda', 'absx', 'LEAD')
A('cmp', '#', HOLD)
A('beq', 'rel', 'Lb')
A('cmp', '#', REST)
A('beq', 'rel', 'Loff')
A('sta', 'zp', Z_TMP)                   # note index: freq + fresh retrig
A('tax', '')
A('lda', 'absx', 'FREQLO')
A('sta', 'abs', SID + 0x00)
A('ldx', 'zp', Z_TMP)
A('lda', 'absx', 'FREQHI')
A('sta', 'abs', SID + 0x01)
A('lda', 'abs', SID + 0x04)
A('and', '#', 0xFE)                     # gate off first: every note retrigs
A('sta', 'abs', SID + 0x04)
A('ora', '#', 0x01)
A('sta', 'abs', SID + 0x04)
A('jmp', 'abs', 'Lb')
play.label('Loff')
A('lda', 'abs', SID + 0x04)
A('and', '#', 0xFE)
A('sta', 'abs', SID + 0x04)
play.label('Lb')
# ---- bass (voice 2): every frame walks the 32-frame pattern
A('ldx', 'zp', Z_BIDX)
A('lda', 'absx', 'BASS')
A('cmp', '#', HOLD)
A('beq', 'rel', 'Dk')
A('cmp', '#', REST)
A('beq', 'rel', 'Boff')
A('sta', 'zp', Z_TMP)
A('tax', '')
A('lda', 'absx', 'FREQLO')
A('sta', 'abs', SID + 0x07)
A('ldx', 'zp', Z_TMP)
A('lda', 'absx', 'FREQHI')
A('sta', 'abs', SID + 0x08)
A('lda', 'abs', SID + 0x0B)
A('and', '#', 0xFE)
A('sta', 'abs', SID + 0x0B)
A('ora', '#', 0x01)
A('sta', 'abs', SID + 0x0B)
A('jmp', 'abs', 'Dk')
play.label('Boff')
A('lda', 'abs', SID + 0x0B)
A('and', '#', 0xFE)
A('sta', 'abs', SID + 0x0B)
play.label('Dk')
# ---- drums (voice 3 noise): every frame, DRUM byte drives pitch+gate
A('lda', 'zp', Z_CNT)
A('and', '#', 0x0F)                     # drum table is 16 entries
A('tax', '')
A('lda', 'absx', 'DRUM')
A('cmp', '#', 0x01)
A('bne', 'rel', 'Ds')
A('lda', '#', 0x22)                     # kick: high pitch noise burst
A('sta', 'abs', SID + 0x0F)
A('jmp', 'abs', 'Don')
play.label('Ds')
A('cmp', '#', 0x02)
A('bne', 'rel', 'Doff')
A('lda', '#', 0x88)                     # snare: low pitch noise
A('sta', 'abs', SID + 0x0F)
play.label('Don')
A('lda', 'abs', SID + 0x12)
A('and', '#', 0xFE)                     # gate off first: fresh burst
A('sta', 'abs', SID + 0x12)
A('ora', '#', 0x01)
A('sta', 'abs', SID + 0x12)
A('jmp', 'abs', 'Ix')
play.label('Doff')
A('lda', 'abs', SID + 0x12)
A('and', '#', 0xFE)
A('sta', 'abs', SID + 0x12)
play.label('Ix')
# ---- advance pattern indices (lead wraps at 64, bass at 32)
A('inc', 'zp', Z_LIDX)
A('lda', 'zp', Z_LIDX)
A('and', '#', 0x3F)
A('sta', 'zp', Z_LIDX)
A('inc', 'zp', Z_BIDX)
A('lda', 'zp', Z_BIDX)
A('and', '#', 0x1F)
A('sta', 'zp', Z_BIDX)
A('rts', '')

# ------------------------------------------------------------ layout
INIT_ADDR = LOAD
init_bytes = init.bytes()
PLAY_ADDR = INIT_ADDR + len(init_bytes)
play.org = PLAY_ADDR
play_bytes = play.bytes()

def align(a):
    return (a + 15) & ~15

TBL = align(PLAY_ADDR + len(play_bytes))
TBL_LEAD   = TBL
TBL_BASS   = TBL_LEAD + len(LEAD)
TBL_DRUM   = TBL_BASS + len(BASS)
TBL_FREQLO = TBL_DRUM + len(DRUM)
TBL_FREQHI = TBL_FREQLO + 16

for t in (init, play):
    t.labels['LEAD'] = TBL_LEAD
    t.labels['BASS'] = TBL_BASS
    t.labels['DRUM'] = TBL_DRUM
    t.labels['FREQLO'] = TBL_FREQLO
    t.labels['FREQHI'] = TBL_FREQHI
init_bytes = init.bytes()
play_bytes = play.bytes()

end = TBL_FREQHI + 16
assert end < 0x10000, "player does not fit in C64 memory"
image = bytearray(end - LOAD)
image[INIT_ADDR - LOAD:INIT_ADDR - LOAD + len(init_bytes)] = init_bytes
image[PLAY_ADDR - LOAD:PLAY_ADDR - LOAD + len(play_bytes)] = play_bytes
image[TBL_LEAD - LOAD:TBL_LEAD - LOAD + len(LEAD)] = bytes(LEAD)
image[TBL_BASS - LOAD:TBL_BASS - LOAD + len(BASS)] = bytes(BASS)
image[TBL_DRUM - LOAD:TBL_DRUM - LOAD + len(DRUM)] = bytes(DRUM)
for i, m in enumerate(NOTES):
    w = sidf(m)
    image[TBL_FREQLO - LOAD + i] = w & 0xFF
    image[TBL_FREQHI - LOAD + i] = (w >> 8) & 0xFF

# ------------------------------------------------------------- header
name = "A minor pocket"
author = "PocketPd demo"
released = "2026 PocketPd (CC0)"

hdr = bytearray(0x7C)
hdr[0:4] = b"PSID"
struct.pack_into(">H", hdr, 4, 2)            # version 2 (v2NG)
struct.pack_into(">H", hdr, 6, 0x7C)         # data offset
struct.pack_into(">H", hdr, 8, LOAD)
struct.pack_into(">H", hdr, 10, INIT_ADDR)
struct.pack_into(">H", hdr, 12, PLAY_ADDR)
struct.pack_into(">H", hdr, 14, 1)           # songs
struct.pack_into(">H", hdr, 16, 1)           # start song
struct.pack_into(">I", hdr, 18, 0x00000001)  # speed: tune 1 = CIA timer
hdr[22:54] = name.encode()[:32].ljust(32, b"\x00")
hdr[54:86] = author.encode()[:32].ljust(32, b"\x00")
hdr[86:118] = released.encode()[:32].ljust(32, b"\x00")
# flags: bits 2-3 video standard (1 = PAL), bits 4-5 SID model (1 = 6581)
struct.pack_into(">H", hdr, 118, (1 << 2) | (1 << 4))
hdr[120] = 0                                 # reloc start page: none
hdr[121] = 0                                 # reloc pages: none

out = bytes(hdr) + bytes(image)
dest = sys.argv[1] if len(sys.argv) > 1 else "demo.sid"
open(dest, "wb").write(out)
print("%s: %d bytes, init $%04X play $%04X, code %d+%d bytes, tables "
      "at $%04X" % (dest, len(out), INIT_ADDR, PLAY_ADDR,
                    len(init_bytes), len(play_bytes), TBL))
print("freq table:", " ".join("%04x" % sidf(m) for m in NOTES))
