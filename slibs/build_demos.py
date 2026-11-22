#!/usr/bin/env python3
"""Author the per-library demo patches (assets/examples/slibs) plus the tiny
demo.mid / demo.bin files they read. Patches stay audible through the bundled
[output~] example abstraction; each showcases long-press Help.

Connection indices are auto-assigned: obj/msg boxes get indices in creation
order (pd does not count #X text comments).
"""
import os, struct

OUT = "C:/Projects/26PDAndroid/app/src/main/assets/examples/slibs"

class Patch:
    def __init__(self, title, subtitle=None):
        os.makedirs(OUT, exist_ok=True)
        self.lines = ["#N canvas 0 0 760 560 10;"]
        self.n = 0           # object index (comments excluded, like pd)
        self.y = 20
        self.text(20, self.y, title); self.y += 24
        if subtitle:
            self.text(20, self.y, subtitle); self.y += 24

    def text(self, x, y, s):
        self.lines.append("#X text %d %d %s;" % (x, y, s))

    def obj(self, x, y, *a):
        self.lines.append("#X obj %d %d %s;" % (x, y, " ".join(a)))
        self.n += 1
        return self.n - 1

    def msg(self, x, y, *a):
        return self._msg(x, y, a)

    def _msg(self, x, y, a):
        self.lines.append("#X msg %d %d %s;" % (x, y, " ".join(a)))
        self.n += 1
        return self.n - 1

    def conn(self, *cords):
        for f, o, t, i in cords:
            self.lines.append("#X connect %d %d %d %d;" % (f, o, t, i))

    def dsp(self, x, y):
        """A tappable "; pd dsp 1" message."""
        return self._msg(x, y, ("\\;", "pd", "dsp", "1"))

    def save(self, name):
        open(os.path.join(OUT, name), "w").write("\n".join(self.lines) + "\n")

# ---------------------------------------------------------------- overview
p = Patch("slibs-overview - bundled pd-extended libraries")
for i, s in enumerate([
    "Ten libraries are compiled in and load at startup:",
    "cyclone zexy iemlib fftease bsaylor creb cxc osc midifile binfile",
    "",
    "Every object works under both spellings:",
    "  [counter]          (bare name)",
    "  [cyclone/counter]  (namespaced form)",
    "",
    "If two libraries ship the same name the console reports who wins:",
    "bare names go to cyclone > zexy > iemlib > ... the loser stays",
    "reachable as lib/name. Example: bare [svf~] is bsaylor's while",
    "[cyclone/svf~] reaches the cyclone one.",
    "",
    "One audible demo per library lives in this folder: turn DSP on",
    "with the speaker toolbar icon and play. Long-press any object",
    "and choose Help to open its help patch (gray when unavailable).",
]):
    p.text(20, 50 + i * 24, s)
p.save("0-slibs-overview.pd")

# ---------------------------------------------------------------- cyclone
p = Patch("cyclone-demo", "Max/MSP lineage. DSP on, then play - long-press boxes for Help.")
metro = p.obj(20, 80, "metro", "250")
b1    = p.msg(20, 50, "1")
cnt   = p.obj(100, 80, "counter", "0", "12")
oct   = p.obj(170, 80, "+", "60")
mtof  = p.obj(230, 80, "mtof")
osc   = p.obj(20, 160, "osc~")
p.text(180, 130, "counter 0..12, up an octave -> rising scale")
bkt   = p.obj(20, 230, "cyclone/bucket", "3")
b2    = p.msg(20, 260, "11")
pr    = p.obj(90, 260, "print", "BUCKET")
p.text(180, 230, "bucket 3: shifts floats through 3 slots")
lb    = p.obj(20, 320, "loadbang")
dsp   = p.dsp(20, 350)
svf   = p.obj(180, 350, "cyclone/svf~", "800", "3")
noi   = p.obj(180, 390, "noise~")
p.text(340, 350, "cyclone svf~ (bsaylor's owns the bare name)")
out   = p.obj(180, 430, "output~")
p.conn((lb, 0, b1, 0), (lb, 0, dsp, 0), (b1, 0, metro, 0), (metro, 0, cnt, 0),
       (cnt, 0, oct, 0), (oct, 0, mtof, 0), (mtof, 0, osc, 0), (osc, 0, out, 0),
       (b2, 0, bkt, 0), (bkt, 0, pr, 0), (noi, 0, svf, 0), (svf, 0, out, 0))
p.save("cyclone-demo.pd")

# ---------------------------------------------------------------- zexy
p = Patch("zexy-demo", "The swiss-army multipurpose library.")
m     = p.msg(20, 60, "1", "2", "3")
lst   = p.obj(20, 100, "lister")
mm    = p.obj(20, 130, "minmax")
s     = p.obj(20, 160, "sum")
pr    = p.obj(20, 190, "print", "SUM")
p.text(110, 130, "minmax -> min and max of the list")
p.text(110, 160, "sum adds all elements (zexy owns the bare name)")
o1    = p.obj(20, 250, "osc~", "330")
o2    = p.obj(20, 280, "osc~", "331")
lim   = p.obj(20, 320, "limiter~", "2")
p.text(110, 320, "limiter~ 2: two-channel look-ahead limiter")
out   = p.obj(20, 360, "output~")
lb    = p.obj(250, 60, "loadbang")
dsp   = p.dsp(250, 90)
p.conn((m, 0, lst, 0), (lst, 0, mm, 0), (mm, 0, s, 0), (s, 0, pr, 0),
       (o1, 0, lim, 0), (o2, 0, lim, 1), (lim, 0, out, 0), (lim, 1, out, 1),
       (lb, 0, dsp, 0))
p.save("zexy-demo.pd")

# ---------------------------------------------------------------- iemlib
p = Patch("iemlib-demo", "IEM Graz objects: filters note helpers tuning tools.")
mt    = p.obj(20, 70, "metro", "400")
rnd   = p.obj(100, 70, "random", "12")
add   = p.obj(180, 70, "+", "48")
f2n   = p.obj(250, 70, "f2note")
pr    = p.obj(250, 110, "print", "NOTE")
p.text(330, 70, "f2note: midi note number -> note name")
osc   = p.obj(20, 160, "osc~", "220")
lp    = p.obj(20, 200, "lp9_bess~", "1200")
p.text(140, 200, "lp9_bess~: 9th order Bessel lowpass")
out   = p.obj(20, 240, "output~")
lb    = p.obj(250, 160, "loadbang")
dsp   = p.dsp(250, 190)
p.conn((lb, 0, dsp, 0), (mt, 0, rnd, 0), (rnd, 0, add, 0), (add, 0, f2n, 0),
       (f2n, 0, pr, 0), (osc, 0, lp, 0), (lp, 0, out, 0))
p.save("iemlib-demo.pd")

# ---------------------------------------------------------------- bsaylor
p = Patch("bsaylor-demo", "svf~ - the state variable filter that owns the bare name.")
p.text(20, 66, "cutoff - drag me in run mode (send symbol feeds svf~ inlet 2)")
h     = p.obj(20, 90, "hsl", "128", "15", "100", "3000", "0", "empty", "empty", "empty", "0", "0", "0", "8", "-262144", "-1", "-1", "600", "1")
osc   = p.obj(20, 140, "osc~", "110")
svf   = p.obj(20, 180, "svf~")
out   = p.obj(20, 220, "output~")
lb    = p.obj(250, 90, "loadbang")
dsp   = p.dsp(250, 120)
p.conn((lb, 0, dsp, 0), (osc, 0, svf, 0), (h, 0, svf, 1), (svf, 0, out, 0))
p.save("bsaylor-demo.pd")

# ---------------------------------------------------------------- fftease
p = Patch("fftease-demo", "Eric Lyon's FFT toolkit - 30+ spectral objects.")
osc   = p.obj(20, 70, "osc~", "220")
pv    = p.obj(20, 110, "pvoc~")
p.text(110, 110, "pvoc~: phase vocoder - transpose and stretch")
p.text(20, 152, "transpose in semitones - drag me into pvoc~ inlet 2 in run mode")
h     = p.obj(20, 175, "hsl", "128", "15", "-24", "24", "0", "empty", "empty", "empty", "0", "0", "0", "8", "-262144", "-1", "-1", "0", "1")
out   = p.obj(20, 230, "output~")
lb    = p.obj(250, 70, "loadbang")
dsp   = p.dsp(250, 100)
p.conn((lb, 0, dsp, 0), (osc, 0, pv, 0), (h, 0, pv, 1), (pv, 0, out, 0))
p.save("fftease-demo.pd")

# ---------------------------------------------------------------- creb
p = Patch("creb-demo", "Chaotic and spectral toys by Tim Blechmann.")
mt    = p.obj(20, 70, "metro", "500")
ead   = p.obj(100, 70, "ead~", "5", "350")
p.text(180, 70, "ead~: exponential attack/decay - a drum envelope")
osc   = p.obj(100, 110, "osc~", "110")
mul   = p.obj(100, 150, "*~")
bfft  = p.obj(100, 190, "bfft~")
ibfft = p.obj(100, 220, "ibfft~")
p.text(180, 205, "bfft~ -> ibfft~: FFT round trip")
out   = p.obj(100, 260, "output~")
lb    = p.obj(320, 70, "loadbang")
dsp   = p.dsp(320, 100)
p.conn((lb, 0, dsp, 0), (mt, 0, ead, 0), (ead, 0, mul, 1), (osc, 0, mul, 0),
       (mul, 0, bfft, 0), (bfft, 0, ibfft, 0), (ibfft, 0, out, 0))
p.save("creb-demo.pd")

# ---------------------------------------------------------------- cxc
p = Patch("cxc-demo", "Small control utilities and the cx statistics family.")
mt    = p.obj(20, 70, "metro", "200")
rnd   = p.obj(100, 70, "random", "100")
bf    = p.obj(180, 70, "bfilt")
pr1   = p.obj(180, 110, "print", "BFILT")
p.text(250, 70, "bfilt: passes a value only when it CHANGES")
cx    = p.obj(180, 150, "cxmean")
pr2   = p.obj(180, 180, "print", "MEAN")
p.text(250, 150, "cxmean: running mean of the stream")
bf2   = p.obj(180, 220, "bfilt2")
pr3   = p.obj(180, 250, "print", "BFILT2")
p.text(250, 220, "bfilt2: bfilt with a delta threshold")
osc   = p.obj(20, 300, "osc~", "440")
out   = p.obj(20, 340, "output~")
lb    = p.obj(420, 70, "loadbang")
dsp   = p.dsp(420, 100)
p.conn((lb, 0, dsp, 0), (mt, 0, rnd, 0), (rnd, 0, bf, 0), (bf, 0, pr1, 0),
       (bf, 0, cx, 0), (cx, 0, pr2, 0), (bf, 0, bf2, 0), (bf2, 0, pr3, 0),
       (osc, 0, out, 0))
p.save("cxc-demo.pd")

# ---------------------------------------------------------------- osc
p = Patch("osc-demo", "mrpeach OSC - the original OpenSoundControl objects.")
m     = p.msg(20, 70, "/freq", "330")
pack  = p.obj(20, 110, "packOSC")
unp   = p.obj(140, 110, "unpackOSC")
rt    = p.obj(260, 110, "routeOSC", "/freq")
osc   = p.obj(260, 150, "osc~", "440")
p.text(340, 150, "routeOSC strips the address; the float tunes osc~")
pr    = p.obj(140, 150, "print", "OSC")
out   = p.obj(260, 190, "output~")
lb    = p.obj(20, 200, "loadbang")
dsp   = p.dsp(20, 230)
p.conn((lb, 0, dsp, 0), (m, 0, pack, 0), (pack, 0, unp, 0), (unp, 0, rt, 0),
       (rt, 0, osc, 0), (osc, 0, out, 0), (unp, 0, pr, 0))
p.save("osc-demo.pd")

# ---------------------------------------------------------------- midifile
p = Patch("midifile-demo", "mrpeach midifile - reads and writes standard MIDI files.")
r     = p.msg(20, 70, "read", "demo.mid")
st    = p.msg(120, 70, "start")
sp    = p.msg(200, 70, "stop")
mf    = p.obj(20, 110, "midifile")
pr    = p.obj(20, 150, "print", "MIDI")
p.text(110, 150, "outlet 1 streams raw midi bytes while playing")
p.text(20, 200, "Tap [read demo.mid( then [start(: noteon/noteoff bytes appear")
p.text(20, 222, "in the console. Decode them with [midiin]-style logic for a")
p.text(20, 244, "software player or route to [midiout] hardware.")
p.conn((r, 0, mf, 0), (st, 0, mf, 0), (sp, 0, mf, 0), (mf, 0, pr, 0))
p.save("midifile-demo.pd")

# ---------------------------------------------------------------- binfile
p = Patch("binfile-demo", "mrpeach binfile - arbitrary binary files as byte streams.")
r     = p.msg(20, 70, "read", "demo.bin")
rw    = p.msg(120, 70, "rewind")
b     = p.msg(200, 70, "bang")
bf    = p.obj(20, 110, "binfile")
pr    = p.obj(20, 150, "print", "BIN")
p.text(110, 150, "each bang outputs the next byte value")
p.text(20, 200, "Tap [read demo.bin( then bang a few times - file bytes print")
p.text(20, 222, "to the console. Long-press [binfile] and choose Help for the")
p.text(20, 244, "full message set: write seek flush info...")
p.conn((r, 0, bf, 0), (rw, 0, bf, 0), (b, 0, bf, 0), (bf, 0, pr, 0))
p.save("binfile-demo.pd")

# ---------------------------------------------------------------- demo.mid
def midi_file():
    def vlq(n):
        out = b""
        while True:
            v = n & 0x7F
            n >>= 7
            if n: out = bytes([v | 0x80]) + out
            else: return out + bytes([v])
    events = b""
    t = 0
    for note in (60, 64, 67, 72):
        events += vlq(t) + bytes([0x90, note, 100]); t = 120
        events += vlq(120) + bytes([0x80, note, 0]); t = 0
    track = b"MTrk" + struct.pack(">I", len(events)) + events
    head = b"MThd" + struct.pack(">IHHH", 6, 0, 1, 120)
    return head + track

# ---------------------------------------------------------------- demo.bin
def bin_file():
    return (b"PocketPd binfile demo - bytes 0..9: " + bytes(range(10)) + b"\n")

os.makedirs(OUT, exist_ok=True)
open(os.path.join(OUT, "demo.mid"), "wb").write(midi_file())
open(os.path.join(OUT, "demo.bin"), "wb").write(bin_file())
print("demos written to", OUT)
