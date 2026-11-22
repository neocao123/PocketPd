#!/usr/bin/env python3
"""Developer tool: generates the tracker demo patch skeleton for a MOD file.
Fully dynamic at runtime: [modseq <file>] parses the module and publishes
every sample as a pd array (its `arrays` message also re-sends the smp /
keymap / envelope meta); the [instr] voices bind to those arrays directly.
No samples are extracted and no files are written by this tool.

Usage: python mod2wavs.py <file.mod> <outdir>   (writes tracker-demo.pd)
"""
import os, sys

def parse_mod(path):
    d = open(path, 'rb').read()
    ih = d[20:20 + 30 * 31]
    slen = d[950]
    magic = d[1080:1084]
    m = magic
    if m[:4] in (b'M.K.', b'M!K!', b'M&K!', b'FLT4'): nch = 4
    elif m == b'6CHN': nch = 6
    elif m in (b'8CHN', b'FLT8', b'OCTA', b'CD81'): nch = 8
    elif m[1:4] == b'CHN': nch = m[0] - 48
    else: raise SystemExit('unsupported magic %r' % m)
    used = []
    for i in range(31):
        h = ih[i * 30:(i + 1) * 30]
        frames = (h[22] << 8) | h[23]
        if not frames: continue
        ft = h[24] & 0x0f
        fts = ft if ft < 8 else ft - 16
        base = 48 - int(fts / 8)
        loop = 1 if ((h[28] << 8) | h[29]) > 1 else 0
        used.append(dict(idx=i + 1, base=base, loop=loop, frames=frames))
    return dict(nch=nch, songlen=slen, used=used)

def write_demo(path, modname, used_ins):
    """Minimal demo — objects only (no msg boxes: the app's session rebuild
    reorders them and shifts connect indices). [modseq] self-starts 500ms
    after DSP begins: it publishes every sample as a pd array, emits the
    smp/keymap/env metas (the [route] drops transport metas), and plays.
    Each [instr] binds its array from the smp meta (filtered by its id)."""
    L = []
    idx = [0]

    def emit(line):
        L.append(line)
        if line.startswith('#X obj'):
            idx[0] += 1
        return idx[0] - 1

    conns = []
    L.append('#N canvas 0 0 900 1000 10;')
    for s in used_ins:
        L.append('#X array ins%d-1 %d float 2;' % (s['idx'], s['frames']))
    i_seq = emit('#X obj 200 60 modseq %s;' % modname)
    i_dac = emit('#X obj 200 950 dac~;')
    i_mf = emit('#X obj 700 60 route smp keymap envpt envcfg;')
    conns.append('#X connect %d 1 %d 0;' % (i_seq, i_mf))
    rows = (len(used_ins) + 1) // 2
    for k, s in enumerate(used_ins):
        col, row = divmod(k, rows)
        x, y = 60 + col * 430, 120 + row * 64
        i_ins = emit('#X obj %d %d instr %d;' % (x, y, s['idx']))
        i_gain = emit('#X obj %d %d *~ 0.35;' % (x, y + 340))
        conns += ['#X connect %d 0 %d 0;' % (i_seq, i_ins),     # events
                  '#X connect %d 2 %d 0;' % (i_seq, i_ins),     # tick
                  '#X connect %d 0 %d 0;' % (i_ins, i_gain),
                  '#X connect %d 0 %d 0;' % (i_gain, i_dac)]
        for outlet in range(4):
            conns.append('#X connect %d %d %d 0;' % (i_mf, outlet, i_ins))
    L.extend(conns)
    nl = chr(10)
    with open(path, 'w') as f:
        f.write(nl.join(L) + nl)
    print('wrote', path)

def main():
    src, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    mod = parse_mod(src)
    print('channels %d, songlen %d, instruments with samples: %s' %
          (mod['nch'], mod['songlen'], mod['used']))
    write_demo(os.path.join(outdir, 'tracker-demo.pd'),
               os.path.basename(src), mod['used'])

if __name__ == '__main__':
    main()
