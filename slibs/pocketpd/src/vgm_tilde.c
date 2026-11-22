/* vgm~ - VGM/VGZ (gzip) player that drives ym2612~ instances.
 *
 * Loads a VGM file (v1.50-1.71, gzip supported through zlib), paces the
 * command stream at the VGM's own 44100 Hz timebase, and splits the
 * YM2612 register writes PER CHANNEL so a patch can wire one ym2612~
 * per chip channel:
 *
 *   outlet 0     ALL YM2612 register streams as `ch<k> reg <addr> <data>`
 *                messages (k = 1..6; ch1-3 = chip port A, ch4-6 = port
 *                B, register bytes verbatim).  One ym2612~ instance owns
 *                the whole chip (its Nuked core IS the chip) and plays
 *                all six channels - connect this outlet straight to it.
 *   outlet 1     non-YM chips of the log, as messages: `psg <dd>`
 *                (SN76489), `gg <dd>` (Game Gear stereo), `ay <aa> <dd>`
 *                (AY8910 - dropped there), `opl <reg> <data>` (YMF262/
 *                OPL3, reg = port<<8|addr), `nes <reg> <data>` (NES APU/
 *                2A03, VGM command 0xBC, reg = $4000-$401F index) - feed
 *                a sn76489~ and/or an opl3~ and/or a nes~; each object
 *                picks its own selector.
 *   outlet 2     signal: the YM2612 port-0 DAC stream (register 0x2A and
 *                the 0x8n/0xE0 data-bank path), 8-bit unsigned, centered.
 *                The byte stream is reconstructed with linear
 *                interpolation at the exact VGM write times (a 44100 Hz
 *                sample stream resampled to the pd rate) instead of
 *                holding each byte to the nearest pd sample; silent
 *                while paused/stopped.
 *   outlet 3     bang: end of sound data reached without looping.
 *
 * Multi-channel handling: the VGM addresses two YM2612 ports x 3
 * channels through the low bits of each register address (plus the
 * channel bits of key-on 0x28).  vgm~ decodes those bits and labels
 * each write with its channel; register bytes pass verbatim - the one
 * chip instance resolves port/channel internally.  Chip-global
 * registers (0x22 LFO, 0x27 mode, 0x2B DAC enable, the 3-channel-mode
 * fnum regs 0xA8-0xAF, test regs) are reported as ch1.
 *
 * Limitations, by design: DAC Stream Control (0x90-0x95) is parsed and
 * skipped (the common 0x8n DAC path IS played); other chips' writes are
 * skipped (the load line reports every chip clock the file declares, so
 * an unplayed chip never comes as a surprise); compressed data blocks
 * (size bit 31) abort the stream; FM3 special mode fnum regs are
 * forwarded but each ym2612~ runs its chip in normal mode.
 *
 * Messages (all through the left inlet):
 *   load <file.vgm|vgz>   open (pd search path + patch dir), reset
 *   play                  start (or resume after pause)
 *   pause                 halt the stream, key off all YM channels
 *   stop                  halt + rewind to the data start
 *   goto <n> | goto loop  seek to VGM sample n (or the loop point): the
 *                         stream is rescanned silently with a register
 *                         shadow and the shadow's non-zero registers are
 *                         re-emitted on arrival (DAC bank position and
 *                         DAC Stream Control state do not survive seeks)
 *   loop <0/1>            wrap at the loop point (default on when the
 *                         file has one)
 *   info                  post version/duration/loop to the console
 *
 * Timing: the VGM timebase is 44100 Hz; vgm~ converts to the pd rate on
 * the fly, so register writes land within one pd block (<= 1.5 ms) of
 * their logged time and each ym2612~'s internal write queue paces them
 * onto the chip bus.
 */

#include "m_pd.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#ifndef VGM_NO_ZLIB
#include <zlib.h>
#endif

/* file lookup shared with ym2612_tilde.c (search path, no canvas deref) */
int ympp_findfile(const char *name, char *full, unsigned int n);

typedef struct _vgm {
    t_object  obj;
    t_outlet *x_ym;         /* ch<k> reg <a> <d>: one outlet, one ym2612~
                             * (the Nuked core IS the whole chip - all six
                             * channels live in the one instance) */
    t_outlet *x_psg;        /* psg/gg/ay/nes messages */
    t_outlet *x_end;        /* bang at end of (non-looping) data */
    t_outlet *x_dacout;     /* signal: port-0 DAC */
    unsigned char *x_buf;   /* whole (inflated) file */
    unsigned long x_len;
    unsigned long x_start;  /* first command byte */
    unsigned long x_pos;    /* cursor */
    unsigned long x_loopabs;/* absolute loop offset, 0 = none */
    double    x_due;        /* VGM-sample time of the next command */
    double    x_vgmpos;     /* VGM-sample time at the current pd sample */
    double    x_sr;         /* pd sample rate (from dsp) */
    int       x_playing;
    int       x_paused;
    int       x_loop;       /* user wrap flag */
    int       x_dacval;     /* last port-0 DAC byte (0-255) */
    int       x_dacen;      /* 0x2B DAC enable */
    double    x_dacdue;     /* VGM time x_dacval was written at */
    int       x_dacn;       /* last integer VGM position with a known byte */
    int       x_dacnval;    /* the byte in effect at x_dacn */
    int       x_dacnprev;   /* the byte in effect at x_dacn - 1 */
    long      x_seek;       /* data-bank read position for 0x8n */
    unsigned char *x_bank;  /* PCM data block (type 0) for 0x8n */
    unsigned long x_banklen;
    unsigned long x_total;  /* header total samples */
    int       x_note;       /* one-shot console notes */
    unsigned char x_oplshadow[2][256]; /* OPL3 register shadow: goto
                                        * re-emission + key-off sweep */
    int       x_oplseen;    /* the log carried any OPL3 write */
    unsigned char x_neshadow[0x20];    /* NES APU register shadow: goto
                                        * re-emission (VGM 0xBC) */
    int       x_nesseen;    /* the log carried any NES write */
} t_vgm;

static t_class *vgm_class;

/* ---------------------------------------------------------- file load */

static unsigned long vgm_le32(const unsigned char *p) {
    return (unsigned long)p[0] | ((unsigned long)p[1] << 8) |
           ((unsigned long)p[2] << 16) | ((unsigned long)p[3] << 24);
}

/* slurp a whole file; inflates when the content is gzip (ISIZE tail) */
static unsigned char *vgm_slurp(FILE *f, unsigned long *lenp) {
    unsigned char *raw = 0;
    unsigned long cap = 0, got = 0, rn;
    for (;;) {
        unsigned char *nb;
        cap = cap ? cap * 2 : 65536;
        nb = (unsigned char *)realloc(raw, cap);
        if (!nb) { free(raw); fclose(f); return 0; }
        raw = nb;
        rn = (unsigned long)fread(raw + got, 1, cap - got, f);
        if (!rn) break;
        got += rn;
    }
    fclose(f);
#ifndef VGM_NO_ZLIB
    if (got >= 2 && raw[0] == 0x1f && raw[1] == 0x8b) {
        /* gzip: inflate with windowBits 15+32 (auto zlib/gzip detect). The
         * old uncompress(raw + 2, got - 2) was doubly wrong — uncompress()
         * is zlib-format ONLY (a gzip stream fails Z_DATA_ERROR), and it
         * skipped the magic bytes of a 10+-byte header — not one .vgz ever
         * loaded (user report). ISIZE (last 4 bytes, LE) seeds the output
         * capacity (uncompressed size mod 2^32); the buffer still grows if
         * the stream demands it. */
        z_stream zs;
        unsigned char *inf;
        uLong dcap, dgot = 0;
        int zr;
        memset(&zs, 0, sizeof(zs));
        dcap = got >= 4 ? (uLong)vgm_le32(raw + got - 4) : 0;
        if (dcap < got * 2) dcap = (uLong)got * 2;
        dcap += 65536;
        inf = (unsigned char *)malloc(dcap);
        if (!inf) { free(raw); return 0; }
        zs.next_in = raw;
        zs.avail_in = (uInt)got;
        if (inflateInit2(&zs, 15 + 32) != Z_OK) {
            free(inf); free(raw); return 0;
        }
        for (;;) {
            zs.next_out = inf + dgot;
            zs.avail_out = (uInt)(dcap - dgot);
            zr = inflate(&zs, Z_NO_FLUSH);
            dgot = dcap - (uLong)zs.avail_out;
            if (zr == Z_STREAM_END) break;
            if (zr != Z_OK) {
                inflateEnd(&zs); free(inf); free(raw); return 0;
            }
            if (zs.avail_out == 0) {
                unsigned char *ni;
                dcap *= 2;
                ni = (unsigned char *)realloc(inf, dcap);
                if (!ni) {
                    inflateEnd(&zs); free(inf); free(raw); return 0;
                }
                inf = ni;
            }
        }
        inflateEnd(&zs);
        free(raw);
        *lenp = dgot;
        return inf;
    }
#else
    if (got >= 2 && raw[0] == 0x1f && raw[1] == 0x8b) {
        free(raw);                      /* host test builds: no gzip */
        return 0;
    }
#endif
    *lenp = got;
    return raw;
}

static void vgm_load(t_vgm *x, t_symbol *s) {
    char full[MAXPDSTRING];
    FILE *f;
    unsigned char *buf;
    unsigned long len, off, l32, ver;
    if (!s || !s->s_name || !s->s_name[0]) {
        pd_error(x, "vgm~: load expects a file name");
        return;
    }
    if (!ympp_findfile(s->s_name, full, MAXPDSTRING)) {
        pd_error(x, "vgm~: '%s' not found (pd search path)", s->s_name);
        return;
    }
    f = fopen(full, "rb");
    if (!f) {
        pd_error(x, "vgm~: cannot open '%s'", full);
        return;
    }
    buf = vgm_slurp(f, &len);
    if (!buf || len < 0x40 || memcmp(buf, "Vgm ", 4)) {
        free(buf);
        pd_error(x, "vgm~: '%s' is not a VGM file", s->s_name);
        return;
    }
    ver = vgm_le32(buf + 8);
    /* data offset: v1.50+ carries it at 0x34 (relative to 0x34); older
     * files have 0 there and the stream starts at 0x40 */
    off = vgm_le32(buf + 0x34);
    off = off ? 0x34 + off : 0x40;
    l32 = vgm_le32(buf + 0x1c);
    if (off >= len) {
        free(buf);
        pd_error(x, "vgm~: '%s' has a bad data offset", s->s_name);
        return;
    }
    if (x->x_buf) free(x->x_buf);
    if (x->x_bank) { free(x->x_bank); x->x_bank = 0; x->x_banklen = 0; }
    memset(x->x_oplshadow, 0, sizeof(x->x_oplshadow));
    x->x_oplseen = 0;
    memset(x->x_neshadow, 0, sizeof(x->x_neshadow));
    x->x_nesseen = 0;
    x->x_buf = buf;
    x->x_len = len;
    x->x_start = off;
    x->x_pos = off;
    x->x_due = 0.0;
    x->x_vgmpos = 0.0;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_dacval = 128;
    x->x_dacen = 0;
    x->x_dacdue = 0.0;
    x->x_dacn = 0;
    x->x_dacnval = x->x_dacnprev = 128;
    x->x_seek = 0;
    x->x_note = 0;
    x->x_total = vgm_le32(buf + 0x18);
    x->x_loopabs = (l32 && 0x1c + l32 < len) ? 0x1c + l32 : 0;
    /* chip census from the header clocks - the honest successor of the
     * old unconditional "ym2612 clock %u Hz", which printed 0 Hz for an
     * OPL3 log and read like a fault (user report on funky_stars.vgz).
     * Zero clocks stay silent: a file declares only the chips it has. */
    {
        static const struct { int off; const char *name; const char *ride; } ct[] = {
            { 0x0c, "sn76489",  "outlet 1 (psg)" },
            { 0x10, "ym2413",   "not played" },
            { 0x2c, "ym2612",   "outlet 0 + dac" },
            { 0x30, "ym2151",   "not played" },
            { 0x40, "rf5c68",   "not played" },
            { 0x44, "ym2203",   "not played" },
            { 0x48, "ym2608",   "not played" },
            { 0x4c, "ym2610",   "not played" },
            { 0x50, "ym3812",   "not played" },
            { 0x54, "ym3526",   "not played" },
            { 0x58, "y8950",    "not played" },
            { 0x5c, "opl3",     "outlet 1 (opl)" },
            { 0x60, "ymf278b",  "not played" },
            { 0x64, "ymf271",   "not played" },
            { 0x68, "ymz280b",  "not played" },
            { 0x6c, "rf5c164",  "not played" },
            { 0x70, "pwm",      "not played" },
            { 0x74, "ay8910",   "outlet 1 (ay, dropped)" },
            { 0x80, "nes",      "outlet 1 (nes)" },
        };
        char line[512];
        unsigned long ck;
        int k, off = 0, o;
        for (k = 0; k < (int)(sizeof(ct) / sizeof(ct[0])); k++) {
            ck = vgm_le32(buf + ct[k].off);
            if (!ck) continue;
            o = snprintf(line + off, sizeof(line) - (size_t)off,
                         "%s%s %lu Hz (%s)", off ? ", " : "", ct[k].name,
                         ck, ct[k].ride);
            if (o < 0 || o >= (int)sizeof(line) - off) {
                off = (int)sizeof(line) - 1;
                line[off] = 0;
                break;
            }
            off += o;
        }
        post("vgm~: '%s': v%u.%02x, %.1f s%s%s%s", s->s_name,
             (ver >> 8) & 0xff, ver & 0xff, (double)x->x_total / 44100.0,
             x->x_loopabs ? " (loop)" : "", off ? ", chips: " : "",
             off ? line : "");
    }
}

/* ------------------------------------------------------- stream engine */

/* emit one channel-addressed register write on the single ym outlet:
 * `chN reg <addr> <data>` with the register bytes VERBATIM from the log
 * (the one Nuked chip resolves port/channel internally) */
static void vgm_emit(t_vgm *x, int ch, int addr, int data) {
    t_atom a[3];
    char sel[8];
    if (ch < 1 || ch > 6) return;
    sprintf(sel, "ch%d", ch);
    SETSYMBOL(a, gensym("reg"));
    SETFLOAT(a + 1, (t_float)addr);
    SETFLOAT(a + 2, (t_float)data);
    outlet_anything(x->x_ym, gensym(sel), 3, a);
}

/* route one YMF262 (OPL3) write (VGM 0x5E/0x5F port 0/1) to the shared
 * non-YM outlet as `opl <reg> <data>`; reg = port<<8|addr, so an opl3~
 * takes the bytes verbatim.  A persistent shadow feeds goto re-emission
 * and the key-off sweep. */
static void vgm_opl(t_vgm *x, int port, int addr, int data) {
    t_atom a[2];
    if (port < 0 || port > 1) return;
    x->x_oplshadow[port][addr & 0xff] = (unsigned char)(data & 0xff);
    x->x_oplseen = 1;
    SETFLOAT(a, (t_float)((port << 8) | (addr & 0xff)));
    SETFLOAT(a + 1, (t_float)(data & 0xff));
    outlet_anything(x->x_psg, gensym("opl"), 2, a);
}

/* route one NES APU write (VGM 0xBC, reg = $4000-$401F index) to the
 * shared non-YM outlet as `nes <reg> <data>`; a persistent shadow feeds
 * goto re-emission (ascending - the $4015 enable write lands after the
 * $4003-type length loads it depends on, exactly like the log) */
static void vgm_nes(t_vgm *x, int reg, int data) {
    t_atom a[2];
    if (reg < 0 || reg > 0x1f) return;
    x->x_neshadow[reg] = (unsigned char)(data & 0xff);
    x->x_nesseen = 1;
    SETFLOAT(a, (t_float)(reg & 0x1f));
    SETFLOAT(a + 1, (t_float)(data & 0xff));
    outlet_anything(x->x_psg, gensym("nes"), 2, a);
}

/* one DAC byte written (register 0x2A or the 0x8n bank path): just
 * record it + its VGM time - the reconstruction walks an integer-sample
 * staircase in perform (the byte in effect at each 44100 Hz tick, then
 * linear interpolation to the pd rate), so the old nearest-pd-sample
 * ZOH quantization is gone without ever needing a future byte. */
static void vgm_dacput(t_vgm *x, int v) {
    x->x_dacval = v;
    x->x_dacdue = x->x_due;
}

/* route one YM2612 write to its ch1..ch6 label.  ch1-3 = chip port A
 * channels, ch4-6 = port B; register bytes stay verbatim (they already
 * carry the channel bits - the per-voice re-addressing layer is gone
 * with the six-instance topology). */
static void vgm_route(t_vgm *x, int port, int addr, int data) {
    int ch;
    if (addr == 0x2a) {                 /* DAC sample: port 0 feeds the
                                         * DAC signal outlet */
        if (port == 0) vgm_dacput(x, data & 0xff);
        return;
    }
    if (addr == 0x28) {                 /* key on/off: bits 0-1 channel,
                                         * bit 2 port (VGM rips carry the
                                         * port in the data or in the
                                         * command byte - either counts) */
        port |= (data >> 2) & 1;
        ch = (data & 3) + 1 + port * 3;
        if ((data & 3) < 3) vgm_emit(x, ch, addr, data);
        return;
    }
    if (addr >= 0x30 && addr <= 0x9f) {
        ch = (addr & 3) + 1 + port * 3;
        if ((addr & 3) < 3) vgm_emit(x, ch, addr, data);
        return;
    }
    if ((addr >= 0xa0 && addr <= 0xa2) || (addr >= 0xa4 && addr <= 0xa6) ||
        (addr >= 0xb0 && addr <= 0xb2) || (addr >= 0xb4 && addr <= 0xb6)) {
        ch = (addr & 3) + 1 + port * 3;
        if ((addr & 3) < 3) vgm_emit(x, ch, addr, data);
        return;
    }
    /* chip globals (0x2b DAC enable, 0x22 LFO, 0x27 mode/timer, 3ch fnum
     * regs 0xa8-0xaf, test regs): port A, reported as ch1 */
    if (addr == 0x2b) x->x_dacen = (data >> 7) & 1;
    vgm_emit(x, 1, addr, data);
}

static void vgm_keyoff(t_vgm *x) {
    int i;
    for (i = 0; i < 6; i++) {
        t_atom a[3];
        char sel[8];
        sprintf(sel, "ch%d", i + 1);
        SETSYMBOL(a, gensym("reg"));
        SETFLOAT(a + 1, 0x28);
        SETFLOAT(a + 2, (i < 3) ? i : 0x04 | (i - 3));  /* slot bits 0 =
                                          * key off; bit 2 = port for 4-6 */
        outlet_anything(x->x_ym, gensym(sel), 3, a);
    }
    if (x->x_oplseen) {
        /* OPL3: clear key-on bit 5 of every shadowed channel register
         * (blk/FNUM bits kept) and the 0xBD drum key-ons - same
         * semantics as the YM sweep above */
        int b, r, d;
        for (b = 0; b < 2; b++) {
            for (r = 0xb0; r <= 0xb8; r++) {
                d = x->x_oplshadow[b][r] & ~0x20;
                if (d != x->x_oplshadow[b][r])
                    vgm_opl(x, b, r, d);
            }
            d = x->x_oplshadow[b][0xbd] & ~0x1f;
            if (d != x->x_oplshadow[b][0xbd])
                vgm_opl(x, b, 0xbd, d);
        }
    }
    if (x->x_nesseen) {
        /* NES APU: one enable write with all bits clear - clearing an
         * enable bit zeroes that channel's length counter instantly,
         * the silicon's own way of keying everything off */
        vgm_nes(x, 0x15, 0);
    }
}

/* total command length in bytes (command byte included); 0 = unknown */
static int vgm_cmdlen(int c) {
    if (c >= 0x70 && c <= 0x7f) return 1;
    if (c >= 0x80 && c <= 0x8f) return 1;
    switch (c) {
    case 0x4f: case 0x50: return 2;
    case 0x61: return 3;
    case 0x62: case 0x63: case 0x66: return 1;
    case 0x68: return 12;
    case 0x90: case 0x91: return 5;
    case 0x92: return 6;
    case 0x93: return 11;
    case 0x94: return 2;
    case 0x95: return 5;
    case 0xe0: return 5;
    }
    if (c >= 0x30 && c <= 0x3f) return 2;
    if (c >= 0x40 && c <= 0x4e) return 3;
    if (c >= 0x51 && c <= 0x5f) return 3;
    if (c >= 0xa0 && c <= 0xaf) return 3;
    if (c >= 0xb0 && c <= 0xbf) return 3;
    if (c >= 0xc0 && c <= 0xdf) return 4;
    if (c >= 0xe1) return 5;
    return 0;
}

/* process ONE command at x->x_pos; returns 1 if the stream continues */
static int vgm_step(t_vgm *x) {
    unsigned char *b = x->x_buf;
    unsigned long len = x->x_len, pos = x->x_pos;
    int c;
    if (pos >= len) return 0;
    c = b[pos];
    switch (c) {
    case 0x61:                          /* long wait */
        x->x_due += b[pos+1] | (b[pos+2] << 8);
        x->x_pos = pos + 3;
        return 1;
    case 0x62: x->x_due += 735; x->x_pos = pos + 1; return 1;
    case 0x63: x->x_due += 882; x->x_pos = pos + 1; return 1;
    case 0x66:                          /* end of sound data */
        if (x->x_loop && x->x_loopabs) {
            x->x_pos = x->x_loopabs;
            return 1;
        }
        x->x_playing = 0;
        x->x_pos = pos + 1;
        outlet_bang(x->x_end);
        return 0;
    case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75:
    case 0x76: case 0x77: case 0x78: case 0x79: case 0x7a: case 0x7b:
    case 0x7c: case 0x7d: case 0x7e: case 0x7f:
        x->x_due += (c & 15) + 1;
        x->x_pos = pos + 1;
        return 1;
    case 0x80: case 0x81: case 0x82: case 0x83: case 0x84: case 0x85:
    case 0x86: case 0x87: case 0x88: case 0x89: case 0x8a: case 0x8b:
    case 0x8c: case 0x8d: case 0x8e: case 0x8f:
        /* YM2612 port-0 DAC write from the data bank + wait n */
        if (x->x_bank && x->x_seek >= 0 &&
            (unsigned long)x->x_seek < x->x_banklen)
            vgm_dacput(x, x->x_bank[x->x_seek]);
        else
            vgm_dacput(x, x->x_dacval);     /* bank exhausted: re-stamp the
                                             * held byte so the interp pair
                                             * keeps the true write time */
        x->x_seek++;
        x->x_due += c & 15;
        x->x_pos = pos + 1;
        return 1;
    case 0x4f:                          /* game gear stereo */
    {
        t_atom a[1];
        SETFLOAT(a, b[pos+1]);
        outlet_anything(x->x_psg, gensym("gg"), 1, a);
        x->x_pos = pos + 2;
        return 1;
    }
    case 0x50:                          /* SN76489 */
    {
        t_atom a[1];
        SETFLOAT(a, b[pos+1]);
        outlet_anything(x->x_psg, gensym("psg"), 1, a);
        x->x_pos = pos + 2;
        return 1;
    }
    case 0xa0:                          /* AY8910 */
    {
        t_atom a[2];
        SETFLOAT(a, b[pos+1]);
        SETFLOAT(a + 1, b[pos+2]);
        outlet_anything(x->x_psg, gensym("ay"), 2, a);
        x->x_pos = pos + 3;
        return 1;
    }
    case 0x52: case 0x53:               /* YM2612 port 0 / 1 */
        vgm_route(x, c - 0x52, b[pos+1], b[pos+2]);
        x->x_pos = pos + 3;
        return 1;
    case 0x5e: case 0x5f:               /* YMF262 (OPL3) port 0 / 1 */
        vgm_opl(x, c - 0x5e, b[pos+1], b[pos+2]);
        x->x_pos = pos + 3;
        return 1;
    case 0xbc:                          /* NES APU (2A03) */
        vgm_nes(x, b[pos+1] & 0x1f, b[pos+2]);
        x->x_pos = pos + 3;
        return 1;
    case 0x67:                          /* PCM data block */
    {
        unsigned long p = pos + 1, sz, dstart;
        int tt, compressed = 0;
        if (p < len && b[p] == 0x66) p++;   /* compat marker */
        if (p + 5 > len) return 0;
        tt = b[p];
        sz = vgm_le32(b + p + 1);
        dstart = p + 5;
        if (sz & 0x80000000UL) { compressed = 1; sz &= 0x7fffffffUL; }
        if (compressed && !x->x_note) {
            post("vgm~: compressed data block - cannot skip reliably, "
                 "stopping stream");
            x->x_note = 1;
            x->x_playing = 0;
            return 0;
        }
        if (dstart + sz > len) return 0;
        if (tt == 0x00 && !x->x_bank && sz) {
            x->x_bank = (unsigned char *)malloc(sz);
            if (x->x_bank) {
                memcpy(x->x_bank, b + dstart, sz);
                x->x_banklen = sz;
            }
        }
        x->x_pos = dstart + sz;
        return 1;
    }
    default:
    {
        int l = vgm_cmdlen(c);
        if (!l) {
            if (!x->x_note) {
                post("vgm~: unknown command 0x%02x at %lu - skipping 1 byte",
                     c, pos);
                x->x_note = 1;
            }
            x->x_pos = pos + 1;
            return 1;
        }
        if (c >= 0x90 && c <= 0x95 && !x->x_note) {
            post("vgm~: DAC Stream Control (0x90-0x95) is not played - "
                 "skipped");
            x->x_note = 1;
        }
        x->x_pos = pos + l;
        return x->x_pos <= len;
    }
    }
}

/* ------------------------------------------------------------- dsp */

static t_int *vgm_perform(t_int *w) {
    t_vgm *x = (t_vgm *)(w[1]);
    t_sample *out = (t_sample *)(w[2]);
    int n = (int)(w[3]);
    int i;
    double step = 44100.0 / x->x_sr;
    for (i = 0; i < n; i++) {
        if (x->x_playing) {
            x->x_vgmpos += step;
            while (x->x_playing && x->x_due <= x->x_vgmpos)
                if (!vgm_step(x)) break;
            /* DAC reconstruction: the byte stream is a 44100 Hz sample
             * stream - walk the integer-sample staircase (the byte in
             * effect at each VGM tick) one tick behind the cursor and
             * linear-interpolate to the pd rate.  One tick of latency
             * (23 us) buys the knowledge of the NEXT staircase value,
             * which the old nearest-pd-sample ZOH threw away. */
            if (x->x_dacen) {
                double p = x->x_vgmpos - step;
                int n = p > 0 ? (int)p : 0;
                while (x->x_dacn < n) {
                    x->x_dacn++;
                    x->x_dacnprev = x->x_dacnval;
                    if (x->x_dacdue <= (double)x->x_dacn)
                        x->x_dacnval = x->x_dacval;
                }
                if (x->x_dacn > n) {    /* seek/reset jumped backwards */
                    x->x_dacn = n;
                    x->x_dacnval = x->x_dacnprev = 128;
                }
                double f = (double)p - n;
                if (f < 0) f = 0;
                out[i] = (t_sample)((x->x_dacnprev
                                     + (x->x_dacnval - x->x_dacnprev) * f
                                     - 128) / 128.0);
            } else {
                out[i] = 0;
            }
        } else {
            /* paused/stopped: silence - the old code held the last DAC
             * byte, freezing outlet 2 on a full-amplitude DC line */
            out[i] = 0;
        }
    }
    return w + 4;
}

static void vgm_dsp(t_vgm *x, t_signal **sp) {
    x->x_sr = sp[0]->s_sr > 1 ? sp[0]->s_sr : 48000;
    dsp_add(vgm_perform, 3, x, sp[0]->s_vec, sp[0]->s_n);
}

/* --------------------------------------------------------- messages */

static void vgm_play(t_vgm *x) {
    if (!x->x_buf) {
        pd_error(x, "vgm~: nothing loaded");
        return;
    }
    if (x->x_paused) {                  /* resume in place */
        x->x_playing = 1;
        x->x_paused = 0;
        return;
    }
    if (!x->x_playing) {                /* (re)start from the top */
        x->x_pos = x->x_start;
        x->x_due = 0.0;
        x->x_vgmpos = 0.0;
        x->x_playing = 1;
    }
}

static void vgm_pause(t_vgm *x) {
    if (x->x_playing) {
        x->x_playing = 0;
        x->x_paused = 1;
        vgm_keyoff(x);
    }
}

static void vgm_stop(t_vgm *x) {
    int was = x->x_playing || x->x_paused;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_pos = x->x_start;
    x->x_due = 0.0;
    x->x_vgmpos = 0.0;
    x->x_dacval = 128;
    x->x_dacdue = 0.0;
    x->x_dacn = 0;
    x->x_dacnval = x->x_dacnprev = 128;
    if (was) vgm_keyoff(x);
}

static void vgm_loop(t_vgm *x, t_float f) { x->x_loop = (f != 0); }

/* seek: rescan from the data start with a per-port register shadow,
 * then re-emit the shadow's non-zero registers on arrival.  Waits are
 * consumed silently; the time base continues at the target. */
static void vgm_goto(t_vgm *x, t_symbol *s, int argc, t_atom *argv) {
    double target = 0.0;
    int bytime = 1;
    unsigned char shadow[2][256];
    unsigned long pos, p;
    double due = 0.0;
    int wasplaying = x->x_playing;
    int guard = 0, i, j;
    if (!x->x_buf) return;
    if (argc >= 1 && argv[0].a_type == A_SYMBOL &&
        !strcmp(atom_getsymbol(argv)->s_name, "loop")) {
        if (!x->x_loopabs) {
            pd_error(x, "vgm~: goto loop: file has no loop point");
            return;
        }
        bytime = 0;                     /* scan to the loop offset */
    } else if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        target = atom_getfloat(argv);
        if (target < 0) target = 0;
    } else {
        pd_error(x, "vgm~: goto expects a sample number or 'loop'");
        return;
    }
    (void)s;
    x->x_playing = 0;                   /* silent scan */
    memset(shadow, 0, sizeof(shadow));
    memset(x->x_oplshadow, 0, sizeof(x->x_oplshadow));
    x->x_oplseen = 0;
    memset(x->x_neshadow, 0, sizeof(x->x_neshadow));
    x->x_nesseen = 0;
    pos = x->x_start;
    while (pos < x->x_len) {
        int c = x->x_buf[pos];
        if (c == 0x66) {
            if (x->x_loopabs && guard++ < 65536) { pos = x->x_loopabs; continue; }
            break;
        }
        if (bytime ? (due >= target) : (pos >= x->x_loopabs)) break;
        if (c == 0x52 || c == 0x53) {   /* record YM writes in the shadow */
            int port = c - 0x52, a = x->x_buf[pos+1];
            if (a != 0x2a) shadow[port][a] = x->x_buf[pos+2];
        }
        if (c == 0x5e || c == 0x5f) {   /* record OPL3 writes likewise */
            x->x_oplshadow[c - 0x5e][x->x_buf[pos+1]] = x->x_buf[pos+2];
            x->x_oplseen = 1;
        }
        if (c == 0xbc) {                /* record NES writes likewise */
            x->x_neshadow[x->x_buf[pos+1] & 0x1f] = x->x_buf[pos+2];
            x->x_nesseen = 1;
        }
        if (c == 0x67) {                /* skip data block */
            unsigned long q = pos + 1, sz;
            if (q < x->x_len && x->x_buf[q] == 0x66) q++;
            if (q + 5 > x->x_len) break;
            sz = vgm_le32(x->x_buf + q + 1) & 0x7fffffffUL;
            pos = q + 5 + sz;
            continue;
        }
        {
            int l = vgm_cmdlen(c);
            if (!l) { pos += 1; continue; }
            if (c == 0x61) due += x->x_buf[pos+1] | (x->x_buf[pos+2] << 8);
            else if (c == 0x62) due += 735;
            else if (c == 0x63) due += 882;
            else if (c >= 0x70 && c <= 0x7f) due += (c & 15) + 1;
            else if (c >= 0x80 && c <= 0x8f) due += c & 15;
            pos += l;
        }
        if (bytime ? (due >= target) : (pos >= x->x_loopabs)) break;
    }
    /* emit the shadow: every non-zero register through the normal
     * router (which re-addresses per channel), DAC regs excepted */
    for (i = 0; i < 2; i++)
        for (j = 0; j < 256; j++)
            if (shadow[i][j] && j != 0x2a)
                vgm_route(x, i, j, shadow[i][j]);
    /* OPL3 shadow: bank 0 then bank 1, ascending - bank-0 reg 0x105
     * (OPL3 mode) precedes every bank-1 register, which bank-1
     * instrument writes depend on */
    for (i = 0; i < 2; i++)
        for (j = 0; j < 256; j++)
            if (x->x_oplshadow[i][j])
                vgm_opl(x, i, j, x->x_oplshadow[i][j]);
    /* NES shadow: ascending 0x00-0x1F - the $4015 enable write lands
     * after the $4003-type length loads it depends on, and the $4017
     * frame-counter write last, like the log's own order */
    for (j = 0; j < 0x20; j++)
        if (x->x_neshadow[j])
            vgm_nes(x, j, x->x_neshadow[j]);
    x->x_pos = pos;
    x->x_due = due;
    x->x_vgmpos = due;
    /* DAC staircase restarts at the arrival time, register centered
     * (the data-bank read position and DAC Stream Control state do not
     * survive seeks - documented since 0.17.77) */
    x->x_dacval = 128;
    x->x_dacdue = due;
    x->x_dacn = (int)due;
    x->x_dacnval = x->x_dacnprev = 128;
    x->x_playing = wasplaying;
}

static void vgm_info(t_vgm *x) {
    if (!x->x_buf) { post("vgm~: nothing loaded"); return; }
    post("vgm~: %lu bytes, data @0x%lx, duration %.1f s (%lu samples), "
         "loop %s, %s", x->x_len, x->x_start,
         (double)x->x_total / 44100.0, x->x_total,
         x->x_loopabs ? "yes" : "no", x->x_playing ? "playing" :
         (x->x_paused ? "paused" : "stopped"));
}

static void vgm_anything(t_vgm *x, t_symbol *s, int argc, t_atom *argv) {
    const char *n = s->s_name;
    if (!strcmp(n, "load")) {
        vgm_load(x, argc >= 1 ? atom_getsymbol(argv) : 0);
    } else if (!strcmp(n, "goto")) {
        vgm_goto(x, s, argc, argv);
    } else if (!strcmp(n, "play")) {
        vgm_play(x);
    } else if (!strcmp(n, "pause")) {
        vgm_pause(x);
    } else if (!strcmp(n, "stop")) {
        vgm_stop(x);
    } else if (!strcmp(n, "loop")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            vgm_loop(x, atom_getfloat(argv));
    } else if (!strcmp(n, "info")) {
        vgm_info(x);
    } else {
        pd_error(x, "vgm~: unknown message %s", n);
    }
}

/* ------------------------------------------------------------- setup */

static void *vgm_new(void) {
    t_vgm *x = (t_vgm *)pd_new(vgm_class);
    /* documented outlet order (0.17.77 single-chip topology):
     * 0 = ch<k> reg writes for the ONE ym2612~, 1 = psg/gg/ay/nes,
     * 2 = DAC signal, 3 = end bang */
    x->x_ym = outlet_new(&x->obj, &s_anything);
    x->x_psg = outlet_new(&x->obj, &s_anything);
    x->x_dacout = outlet_new(&x->obj, &s_signal);
    x->x_end = outlet_new(&x->obj, &s_bang);
    x->x_buf = 0;
    x->x_len = 0;
    x->x_bank = 0;
    x->x_banklen = 0;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_loop = 1;
    x->x_dacval = 128;
    x->x_dacen = 0;
    x->x_dacdue = 0.0;
    x->x_dacn = 0;
    x->x_dacnval = x->x_dacnprev = 128;
    x->x_seek = 0;
    x->x_due = 0.0;
    x->x_vgmpos = 0.0;
    x->x_sr = 48000.0;
    x->x_note = 0;
    memset(x->x_oplshadow, 0, sizeof(x->x_oplshadow));
    x->x_oplseen = 0;
    memset(x->x_neshadow, 0, sizeof(x->x_neshadow));
    x->x_nesseen = 0;
    return (void *)x;
}

static void vgm_free(t_vgm *x) {
    if (x->x_buf) free(x->x_buf);
    if (x->x_bank) free(x->x_bank);
}

void vgm_tilde_setup(void) {
    vgm_class = class_new(gensym("vgm~"), (t_newmethod)vgm_new,
                          (t_method)vgm_free, sizeof(t_vgm), 0, A_NULL);
    class_addmethod(vgm_class, (t_method)vgm_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
    class_addanything(vgm_class, vgm_anything);
}
