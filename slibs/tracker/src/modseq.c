/* modseq - tracker-module sequencer (XM/MOD) streaming performance events.
 *
 * [modseq song.xm] parses a FastTracker II XM or ProTracker MOD file and
 * runs the pattern clock. It owns everything that lives in the SONG:
 * tempo, rows, note timing, and the pattern effects (portamento, vibrato,
 * arpeggio, slides, offsets, retrig, cut/delay, pattern loops). It renders
 * NO audio and knows nothing about how instruments sound — pair it with
 * [instr] voices (which own samples, keymaps and envelopes) or any synth.
 *
 *   outlet 0 (events, channel-scoped):
 *     note <ch> <instr> <midi> <gain>   a channel triggered a note
 *     off <ch> <instr>                  key-off / note cut / faded out
 *     pitch <ch> <instr> <semitones>    pitch offset from arp/vib/porta
 *     vol <ch> <instr> <gain>           channel volume changed
 *     pos <ch> <instr> <0..1>           sample offset (9xx)
 *   outlet 1 (meta, re-sent by the arrays message):
 *     ins <idx> <nsmp> <fadeout> <envon> <npts> <sus> <loopa> <loopb>
 *     smp <instr> <idx> <array> <base> <loop> <ls> <le>
 *     keymap <instr> <from> <to> <smp>       (run-length compressed)
 *     envpt <instr> <x> <y> / envcfg <instr> <envon> <sus> <la> <lb> <fade>
 *     loaded <ninstr> <nch> <orders>
 *     row <order> <row>  /  tempo <bpm>  /  bang at song end (loop 0)
 *   outlet 2: bang once per tick (drive [instr] envelope clocks)
 *
 * Every sample is published as a pd ARRAY named ins<idx>-<smp> (created
 * on demand in the hosting canvas) — plain pd table data any tabread4~
 * can use; nothing is ever written to disk.
 *
 * Effects: 0xy arpeggio, 1/2xx slide, 3/5xx tone portamento, 4/6xx
 * vibrato, 7xx tremolo, 9xx offset, Axy volume slide, B/C/D, Exx fine
 * slides / pattern loop / retrig / cut / delay, Fxx speed+tempo, XM
 * volume column and K00 key-off. 8xx (pan) is ignored (mono).
 *
 * Messages: open <file>, start, stop, play 0/1, goto <order>, loop 0/1,
 * arrays (publish every sample as a pd array + emit the meta messages),
 * info. Files resolve against the patch directory.
 *
 * Format details follow libxmp / milkyplay (open-source tracker cores).
 */

#include "m_pd.h"
#include "g_canvas.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define MAXORD 256
#define MAXCH 32
#define MAXPAT 256
#define MAXINS 128
#define MAXSMP 16
#define ROWS 64
#define MAXENV 32

typedef struct {
    int frames;
    int loopstart, looplen;
    int loop;
    int basenote;        /* MIDI note at rate 1.0 */
    int finetune;        /* 1/8 semitone units, signed */
    int defvol;
    int16_t *data;
    char name[23];
} t_smpdat;

typedef struct {
    int nsmp;
    int keymap[96];      /* XM note 1..96 -> sample (1-based) */
    int envon, envn, ensus, enloopen, enloopb;
    int envx[MAXENV], envy[MAXENV];
    int fadeout;
    t_smpdat smp[MAXSMP];
    char name[23];
} t_ins;

typedef struct {
    int note;    /* 0 none, 1..96 (XM) / fixed-point (MOD), 254 key-off */
    int ins;
    int vol;     /* -1 no volume column, else raw volume-column byte */
    int fx, fxp;
} t_ev;

typedef struct {
    int rows;
    t_ev *e;
} t_pat;

/* per-channel performance state */
typedef struct {
    float base;          /* note-on pitch (semitones) */
    float pitch;         /* currently emitted offset (arp/vib/porta) */
    float porta;         /* tone-portamento target */
    int porta_on, slide; /* 3/5xx active, 1/2xx units per tick */
    int arp[2], arp_on;
    int vib_phase, vib_speed, vib_depth;
    int trem_phase, trem_speed, trem_depth;
    int retrig;          /* E9x countdown */
    int retrig_period;   /* E9x interval in ticks */
    int cut;             /* ECx countdown */
    int delay;           /* EDx countdown */
    t_ev dly;            /* delayed note event */
    float offset;        /* 9xx, 0 = none */
    int loop_row, loop_count;   /* E6x */
    int ins;             /* instrument of the last note */
    float volout;        /* last emitted gain, -1 = none */
    float pitchout;
} t_chst;

typedef struct _modseq {
    t_object x_obj;
    t_clock *x_clock;
    t_outlet *x_out, *x_meta, *x_tickout;
    t_ins x_ins[MAXINS];
    int x_nins;
    t_pat *x_pat[MAXPAT];
    int x_npat;
    unsigned char x_order[MAXORD];
    int x_nord, x_nch;
    int x_speed, x_tempo;
    int x_orderi, x_row, x_tick;
    int x_playing, x_loop, x_is_mod;
    int x_jump, x_breakto, x_row_goto;
    t_chst x_ch[MAXCH];
    int x_chvol[MAXCH], x_chvolset[MAXCH], x_lastins[MAXCH];
    char x_dir[MAXPDSTRING];
    t_canvas *x_canvas;
    t_clock *x_startclock;    /* deferred self-start after open */
} t_modseq;

static t_class *modseq_class;

static void modseq_open(t_modseq *x, t_symbol *name);
static void modseq_arrays(t_modseq *x, int emitmeta);

/* ------------------------------------------------------------------ read */

typedef struct { FILE *fp; } t_rd;

static int rd8(t_rd *r, unsigned int *v) {
    int c = getc(r->fp);
    if (c == EOF) return 0;
    *v = (unsigned char)c;
    return 1;
}

static int rd16(t_rd *r, unsigned int *v) {
    unsigned int a, b;
    if (!rd8(r, &a) || !rd8(r, &b)) return 0;
    *v = a | (b << 8);
    return 1;
}

static int rd32(t_rd *r, unsigned int *v) {
    unsigned int a, b, c, d;
    if (!rd8(r, &a) || !rd8(r, &b) || !rd8(r, &c) || !rd8(r, &d)) return 0;
    *v = a | (b << 8) | (c << 16) | ((unsigned int)d << 24);
    return 1;
}

static int rdbuf(t_rd *r, void *p, int n) {
    return (int)fread(p, 1, (size_t)n, r->fp) == (size_t)n;
}

static void rdclose(t_rd *r) { if (r->fp) fclose(r->fp); r->fp = NULL; }

/* --------------------------------------------------- pd array publishing */

/* Creates (if needed) the pd array "ins<idx>-<smp>" in the hosting canvas
 * and fills it with the sample as float -1..1 — the pd-native data type,
 * usable by tabread4~ and friends. Nothing touches the filesystem. */
static t_garray *modseq_array(t_modseq *x, int idx, int smp, int frames) {
    char nm[MAXPDSTRING];
    snprintf(nm, MAXPDSTRING, "ins%d-%d", idx, smp);
    t_symbol *ns = gensym(nm);
    t_garray *a = (t_garray *)pd_findbyclass(ns, garray_class);
    if (!a) return NULL;
    if (garray_npoints(a) != frames)
        garray_resize_long(a, frames);
    return a;
}

static void modseq_fill_array(t_modseq *x, int idx, int smp, t_smpdat *sd) {
    t_garray *a = modseq_array(x, idx, smp, sd->frames);
    if (!a) {
        pd_error(x, "modseq: declare '#X array ins%d-%d %d float 2' (or via the demo generator)",
                 idx, smp, sd->frames);
        return;
    }
    int n;
    t_word *vec;
    /* pd 0.51 64-bit: garray_getfloatarray is broken, use words */
    if (!garray_getfloatwords(a, &n, &vec) || n < sd->frames) return;
    for (int k = 0; k < sd->frames; k++)
        vec[k].w_float = sd->data[k] / 32768.0f;
    garray_usedindsp(a);
    garray_redraw(a);
}

/* ------------------------------------------------------------ XM parse */

static const char *xm_parse(t_modseq *x, const char *path) {
    t_rd r; r.fp = fopen(path, "rb");
    if (!r.fp) return "cannot open file";
    char id[17];
    unsigned int ver, hsize, songlen, restart, nord, nch, npat, nins;
    unsigned int flags, speed, bpm;
    if (!rdbuf(&r, id, 17) || memcmp(id, "Extended Module: ", 17))
        { rdclose(&r); return "not an XM file"; }
    fseek(r.fp, 21, SEEK_CUR);                    /* 0x1a + tracker name */
    if (!rd16(&r, &ver)) { rdclose(&r); return "truncated"; }
    if (ver != 0x0104)
        { rdclose(&r); return "unsupported XM version (want 0x0104)"; }
    if (!rd32(&r, &hsize) || !rd16(&r, &songlen) || !rd16(&r, &restart) ||
        !rd16(&r, &nord) || !rd16(&r, &nch) || !rd16(&r, &npat) ||
        !rd16(&r, &nins) || !rd16(&r, &flags) || !rd16(&r, &speed) ||
        !rd16(&r, &bpm))
        { rdclose(&r); return "truncated header"; }
    (void)restart; (void)flags;
    if (nch > MAXCH || nord > MAXORD || npat > MAXPAT || nins > MAXINS)
        { rdclose(&r); return "module too large"; }
    if (songlen > MAXORD) songlen = MAXORD;
    if (!rdbuf(&r, x->x_order, (int)songlen)) { rdclose(&r); return "truncated orders"; }
    if ((int)hsize > 20 + (int)songlen)
        fseek(r.fp, (long)hsize - 20 - (long)songlen, SEEK_CUR);
    x->x_nord = (int)songlen;
    x->x_nch = (int)nch;
    x->x_speed = (int)speed;
    x->x_tempo = (int)bpm;
    x->x_npat = 0;
    for (unsigned int p = 0; p < npat; p++) {
        unsigned int phlen, plen, rows, pack;
        if (!rd32(&r, &phlen) || !rd8(&r, &pack) || !rd16(&r, &rows) ||
            !rd16(&r, &plen))
            { rdclose(&r); return "truncated pattern"; }
        if (phlen > 9) fseek(r.fp, (long)(phlen - 9), SEEK_CUR);
        t_pat *pat = NULL;
        if (rows > 0 && rows <= 256) {
            pat = (t_pat *)calloc(1, sizeof(t_pat));
            if (pat) {
                pat->rows = (int)rows;
                pat->e = (t_ev *)calloc((size_t)rows * nch, sizeof(t_ev));
                if (!pat->e) { free(pat); pat = NULL; }
            }
        }
        unsigned char *buf = (unsigned char *)malloc(plen ? plen : 1);
        if (!buf || !rdbuf(&r, buf, (int)plen))
            { free(buf); rdclose(&r); return "truncated pattern data"; }
        unsigned int ip = 0;
#define NEXT() (ip < plen ? buf[ip++] : 0)
        if (pat) {
            for (t_ev *e = pat->e; e < pat->e + rows * nch; e++) e->vol = -1;
            for (unsigned int ri = 0; ri < rows; ri++)
                for (unsigned int c = 0; c < nch; c++) {
                    t_ev *e = &pat->e[ri * nch + c];
                    unsigned char b = (unsigned char)NEXT();
                    if (!b) continue;
                    if (b & 0x80) {
                        if (b & 0x01) e->note = (unsigned char)NEXT();
                        if (b & 0x02) e->ins = (unsigned char)NEXT();
                        if (b & 0x04) e->vol = (unsigned char)NEXT();
                        if (b & 0x08) e->fx = (unsigned char)NEXT();
                        if (b & 0x10) e->fxp = (unsigned char)NEXT();
                    } else {
                        e->note = b;
                        e->ins = (unsigned char)NEXT();
                        e->vol = (unsigned char)NEXT();
                        e->fx = (unsigned char)NEXT();
                        e->fxp = (unsigned char)NEXT();
                    }
                }
            x->x_pat[p] = pat;
            x->x_npat = (int)p + 1;
        }
#undef NEXT
        free(buf);
    }
    x->x_nins = 0;
    for (unsigned int i = 0; i < nins; i++) {
        long ins_start = ftell(r.fp);
        unsigned int ihlen, nsmp = 0;
        unsigned char name[22], itype;
        t_ins *in = &x->x_ins[x->x_nins];
        memset(in, 0, sizeof(*in));
        if (!rd32(&r, &ihlen) || !rdbuf(&r, name, 22) || !rd8(&r, &itype) ||
            !rd16(&r, &nsmp))
            { rdclose(&r); return "truncated instrument"; }
        memcpy(in->name, name, 22); in->name[22] = 0; in->name[21] = 0;
        if (ihlen < 29 || nsmp > MAXSMP)
            { rdclose(&r); return "bad instrument header"; }
        if (nsmp > 0) {
            unsigned int shsize;
            if (!rd32(&r, &shsize)) { rdclose(&r); return "truncated instrument"; }
            if (nsmp > 1) {
                /* keymap: sample number per XM note 1..96 */
                unsigned char km[96];
                if (!rdbuf(&r, km, 96)) { rdclose(&r); return "truncated keymap"; }
                for (int k = 0; k < 96; k++)
                    in->keymap[k] = (km[k] >= 1 && km[k] <= (int)nsmp) ? km[k] : 1;
                for (int k = 1; k < 96; k++)
                    if (!in->keymap[k]) in->keymap[k] = 1;
            } else in->keymap[0] = 1;
            /* FT2 0x0104 second header (only when it fits in ihlen):
             *   volume env: npts, sustain, loopstart, loopend, type(2),
             *               12 points x (frame u16, value u8, pad u8)
             *   panning env: same 54 bytes
             *   vibrato: 4 bytes; fadeout: u16 */
            long need = 29 + 4 + (nsmp > 1 ? 96 : 0) + 54 + 54 + 4 + 2;
            if ((long)ihlen >= need) {
                unsigned int epts, esus, els, ele, etype, fo16;
                if (!rd8(&r, &epts) || !rd8(&r, &esus) || !rd8(&r, &els) ||
                    !rd8(&r, &ele) || !rd16(&r, &etype))
                    { rdclose(&r); return "truncated envelope"; }
                in->envon = (etype & 0x01) ? 1 : 0;
                in->envn = 0;
                for (unsigned int p2 = 0; p2 < epts && p2 < MAXENV; p2++) {
                    unsigned int ex, ey, pad;
                    if (!rd16(&r, &ex) || !rd8(&r, &ey) || !rd8(&r, &pad))
                        { rdclose(&r); return "truncated envelope"; }
                    in->envx[in->envn] = (int)ex;
                    in->envy[in->envn] = (int)(ey > 64 ? 64 : ey);
                    in->envn++;
                }
                fseek(r.fp, (long)(12 - epts * 4 > 0 ? 12 - epts * 4 : 0), SEEK_CUR);
                fseek(r.fp, 54, SEEK_CUR);      /* panning envelope */
                fseek(r.fp, 4, SEEK_CUR);       /* vibrato type/sweep/depth/rate */
                if (!rd16(&r, &fo16)) { rdclose(&r); return "truncated fadeout"; }
                in->fadeout = (int)(fo16 > 65535 ? 65535 : fo16);
                in->ensus = (int)esus;
                in->enloopen = (int)els;
                in->enloopb = (int)ele;
                if (in->envn) {
                    if (in->ensus >= in->envn) in->ensus = in->envn - 1;
                    if (in->enloopen >= in->envn) in->enloopen = 0;
                    if (in->enloopb >= in->envn) in->enloopb = in->envn - 1;
                }
            }
        }
        fseek(r.fp, ins_start + (long)ihlen, SEEK_SET);
        unsigned int shbits[MAXSMP];
        for (unsigned int smp = 0; smp < nsmp; smp++) {
            unsigned int slen, lstart, llen, vol, type, pan, res, ftu, relu;
            unsigned char sname[22];
            if (!rd32(&r, &slen) || !rd32(&r, &lstart) || !rd32(&r, &llen) ||
                !rd8(&r, &vol) || !rd8(&r, &ftu) ||
                !rd8(&r, &type) || !rd8(&r, &pan) ||
                !rd8(&r, &relu) || !rd8(&r, &res) ||
                !rdbuf(&r, sname, 22))
                { rdclose(&r); return "truncated sample header"; }
            t_smpdat *sd = &in->smp[smp];
            memcpy(sd->name, sname, 22); sd->name[22] = 0; sd->name[21] = 0;
            sd->frames = (int)slen;
            sd->loopstart = (int)lstart;
            sd->looplen = (int)llen;
            sd->loop = (type & 3) == 1 ? 1 : 0;
            sd->basenote = 60 - (int)(int8_t)(relu & 0xff);
            sd->finetune = (int)(ftu & 0xff) - ((ftu & 0x80) ? 256 : 0);
            sd->defvol = vol > 64 ? 64 : (int)vol;
            sd->data = NULL;
            shbits[smp] = (type & 0x10) ? 16 : 8;
        }
        for (unsigned int smp = 0; smp < nsmp; smp++) {
            t_smpdat *sd = &in->smp[smp];
            int n = sd->frames;
            if (n <= 0) { sd->frames = 0; continue; }
            sd->data = (int16_t *)malloc(sizeof(int16_t) * (size_t)n);
            if (!sd->data) { rdclose(&r); return "out of memory"; }
            int acc = 0;
            if (shbits[smp] == 16) {
                for (int k = 0; k < n; k++) {
                    unsigned int v;
                    if (!rd16(&r, &v)) { rdclose(&r); return "truncated sample"; }
                    acc += (int)(int16_t)v;
                    sd->data[k] = (int16_t)acc;
                }
            } else {
                for (int k = 0; k < n; k++) {
                    unsigned int v;
                    if (!rd8(&r, &v)) { rdclose(&r); return "truncated sample"; }
                    acc += (int)(int8_t)v;
                    sd->data[k] = (int16_t)(acc << 8);
                }
            }
        }
        in->nsmp = (int)nsmp;
        x->x_nins++;
    }
    rdclose(&r);
    return NULL;
}

/* ------------------------------------------------------------ MOD parse */

static int mod_period_note(int period, int finetune) {
    if (period < 14) period = 14;
    double n = 48.0 + 12.0 * log2(428.0 / (double)period);
    if (finetune) {
        int ft = finetune < 8 ? finetune : finetune - 16;
        n += ft / 8.0;
    }
    if (n < 0) n = 0;
    if (n > 127) n = 127;
    return (int)(n * 64.0 + 0.5);
}

static const char *mod_parse(t_modseq *x, const char *path) {
    t_rd r; r.fp = fopen(path, "rb");
    if (!r.fp) return "cannot open file";
    unsigned char ih[31 * 30];
    unsigned char ord[MAXORD], magic[4];
    unsigned int slenu, padu;
    unsigned char slen;
    int nins = 31, nch = 4;
    fseek(r.fp, 20, SEEK_SET);                    /* song title */
    if (!rdbuf(&r, ih, 31 * 30) || !rd8(&r, &slenu) ||
        !rd8(&r, &padu) || !rdbuf(&r, ord, 128) ||
        !rdbuf(&r, magic, 4))
        { rdclose(&r); return "not a MOD file"; }
    slen = (unsigned char)slenu;
    const char *m = (const char *)magic;
    if (!memcmp(m, "M.K.", 4) || !memcmp(m, "M!K!", 4) ||
        !memcmp(m, "M&K!", 4) || !memcmp(m, "FLT4", 4)) nch = 4;
    else if (!memcmp(m, "6CHN", 4)) nch = 6;
    else if (!memcmp(m, "8CHN", 4) || !memcmp(m, "FLT8", 4) ||
             !memcmp(m, "OCTA", 4) || !memcmp(m, "CD81", 4)) nch = 8;
    else if (m[0] >= '1' && m[0] <= '9' && m[1] == 'C' && m[2] == 'H' &&
             m[3] == 'N') nch = m[0] - '0';
    else if (m[0] == 'T' && m[1] == 'D' && m[2] == 'Z' && m[3] >= '1' &&
             m[3] <= '9') nch = m[3] - '0';
    else {
        nins = 15;
        fseek(r.fp, 20, SEEK_SET);
        if (!rdbuf(&r, ih, 15 * 30) || !rd8(&r, &slenu) ||
            !rd8(&r, &padu) || !rdbuf(&r, ord, 128))
            { rdclose(&r); return "not a MOD file"; }
        slen = (unsigned char)slenu;
    }
    if (slen > 128) slen = 128;
    x->x_nord = slen;
    memcpy(x->x_order, ord, slen);
    x->x_nch = nch;
    x->x_speed = 6;
    x->x_tempo = 125;
    x->x_is_mod = 1;
    int npat = 1;
    for (int i = 0; i < slen; i++) if (ord[i] + 1 > npat) npat = ord[i] + 1;
    if (npat > MAXPAT) npat = MAXPAT;
    x->x_npat = npat;
    size_t plen = (size_t)npat * ROWS * nch * 4;
    unsigned char *praw = (unsigned char *)malloc(plen);
    if (!praw) { rdclose(&r); return "out of memory"; }
    if (fread(praw, 1, plen, r.fp) != plen)
        { free(praw); rdclose(&r); return "truncated patterns"; }
    for (int p = 0; p < npat; p++) {
        t_pat *pat = (t_pat *)calloc(1, sizeof(t_pat));
        if (!pat || !(pat->e = (t_ev *)calloc(ROWS * nch, sizeof(t_ev))))
            { free(praw); rdclose(&r); return "out of memory"; }
        pat->rows = ROWS;
        x->x_pat[p] = pat;
        for (int ri = 0; ri < ROWS; ri++)
            for (int c = 0; c < nch; c++) {
                unsigned char *q = praw + (((size_t)p * ROWS + ri) * nch + c) * 4;
                t_ev *e = &pat->e[ri * nch + c];
                if (!q[0] && !q[1] && !q[2] && !q[3]) continue;
                int period = ((q[0] & 0x0f) << 8) | q[1];
                e->ins = q[2] >> 4;
                e->fx = q[2] & 0x0f;
                e->fxp = q[3];
                if (period) {
                    int ins = e->ins ? e->ins - 1 : 0;
                    if (ins >= nins) ins = nins - 1;
                    e->note = mod_period_note(period, ih[(size_t)ins * 30 + 24] & 0x0f);
                }
            }
    }
    free(praw);
    x->x_nins = 0;
    for (int i = 0; i < nins; i++) {
        unsigned char *h = ih + (size_t)i * 30;
        t_ins *in = &x->x_ins[x->x_nins];
        memset(in, 0, sizeof(*in));
        memcpy(in->name, h, 22); in->name[22] = 0; in->name[21] = 0;
        int frames = (h[22] << 8) | h[23];
        int ft = h[24] & 0x0f;
        int lstart = (h[26] << 8) | h[27];
        int llen = (h[28] << 8) | h[29];
        t_smpdat *sd = &in->smp[0];
        sd->frames = frames;
        sd->loopstart = lstart;
        sd->looplen = llen;
        sd->loop = llen > 1 ? 1 : 0;
        sd->basenote = 48 - ft / 8;
        sd->finetune = ft < 8 ? ft : ft - 16;
        sd->defvol = h[25] > 64 ? 64 : h[25];
        sd->data = frames ? (int16_t *)malloc(sizeof(int16_t) * (size_t)frames) : NULL;
        if (frames && !sd->data) { rdclose(&r); return "out of memory"; }
        for (int k = 0; k < frames; k++) {
            int c = getc(r.fp);
            if (c == EOF) { sd->frames = k; break; }
            sd->data[k] = (int16_t)((int)(int8_t)c << 8);
        }
        in->keymap[0] = 1;
        in->nsmp = frames ? 1 : 0;
        x->x_nins++;
    }
    rdclose(&r);
    return NULL;
}

/* ------------------------------------------------------------- sequencer */

static float vib_sine(int phase) {   /* phase 0..63 -> -1..1 */
    static const float tab[64] = {
        0, .098, .195, .290, .383, .471, .556, .634, .707, .773, .831,
        .882, .924, .957, .980, .995, 1, .995, .980, .957, .924, .882,
        .831, .773, .707, .634, .556, .471, .383, .290, .195, .098,
        0, -.098, -.195, -.290, -.383, -.471, -.556, -.634, -.707,
        -.773, -.831, -.882, -.924, -.957, -.980, -.995, -1, -.995,
        -.980, -.957, -.924, -.882, -.831, -.773, -.707, -.634, -.556,
        -.471, -.383, -.290, -.195, -.098,
    };
    return tab[phase & 63];
}

static void ch_reset(t_modseq *x, int c) {
    t_chst *s = &x->x_ch[c];
    memset(s, 0, sizeof(*s));
    s->volout = -1;
    s->ins = 1;
}

static void modseq_reset(t_modseq *x) {
    x->x_orderi = x->x_row = x->x_tick = 0;
    x->x_jump = x->x_breakto = x->x_row_goto = -1;
    for (int i = 0; i < MAXCH; i++) {
        x->x_chvol[i] = 64;
        x->x_chvolset[i] = 0;
        x->x_lastins[i] = 1;
        ch_reset(x, i);
    }
}

static void modseq_stop(t_modseq *x) {
    x->x_playing = 0;
    clock_unset(x->x_clock);
}

static void emit_note(t_modseq *x, int c, int ins, float note, float gain) {
    t_atom a[4];
    SETFLOAT(&a[0], (t_float)c);
    SETFLOAT(&a[1], (t_float)ins);
    SETFLOAT(&a[2], note);
    SETFLOAT(&a[3], gain);
    outlet_anything(x->x_out, gensym("note"), 4, a);
    t_chst *s = &x->x_ch[c];
    if (s->offset > 0) {
        t_atom p[3];
        SETFLOAT(&p[0], (t_float)c);
        SETFLOAT(&p[1], (t_float)ins);
        SETFLOAT(&p[2], s->offset);
        outlet_anything(x->x_out, gensym("pos"), 3, p);
        s->offset = 0;
    }
    s->pitchout = 0;   /* pitch reference resets to the new note */
    s->ins = ins;
    x->x_lastins[c] = ins;
}

static void emit_off(t_modseq *x, int c) {
    t_atom a[2];
    SETFLOAT(&a[0], (t_float)c);
    SETFLOAT(&a[1], (t_float)x->x_ch[c].ins);
    outlet_anything(x->x_out, gensym("off"), 2, a);
    x->x_ch[c].volout = -1;
}

static void emit_vol(t_modseq *x, int c, float gain) {
    t_chst *s = &x->x_ch[c];
    if (s->volout == gain) return;
    s->volout = gain;
    t_atom a[3];
    SETFLOAT(&a[0], (t_float)c);
    SETFLOAT(&a[1], (t_float)x->x_lastins[c]);
    SETFLOAT(&a[2], gain);
    outlet_anything(x->x_out, gensym("vol"), 3, a);
}

/* per-tick pitch offset computation: arpeggio + vibrato + portamento */
static void tick_pitch(t_modseq *x, int c) {
    t_chst *s = &x->x_ch[c];
    float off = 0;
    if (s->arp_on)
        off += (x->x_tick % 3 == 1) ? s->arp[0]
             : (x->x_tick % 3 == 2) ? s->arp[1] : 0;
    if (s->vib_depth)
        off += vib_sine(s->vib_phase) * (s->vib_depth / 15.0f) * 0.75f;
    if (s->porta_on && s->porta != s->base) {
        float step = 0.06f;   /* 1xx units per tick, ~semitones */
        if (s->base < s->porta) {
            s->base += step;
            if (s->base > s->porta) s->base = s->porta;
        } else {
            s->base -= step;
            if (s->base < s->porta) s->base = s->porta;
        }
    }
    if (s->slide) s->base += s->slide * 0.06f;
    float cur = s->base + off;
    if (cur != s->pitchout) {
        t_atom a[3];
        SETFLOAT(&a[0], (t_float)c);
        SETFLOAT(&a[1], (t_float)x->x_lastins[c]);
        SETFLOAT(&a[2], cur - s->pitchout);
        outlet_anything(x->x_out, gensym("pitch"), 3, a);
        s->pitchout = cur;
    }
    s->vib_phase = (s->vib_phase + s->vib_speed) & 63;
    s->trem_phase = (s->trem_phase + s->trem_speed) & 63;
}

/* per-tick volume: tremolo oscillation around the channel volume */
static void tick_volume(t_modseq *x, int c) {
    t_chst *s = &x->x_ch[c];
    if (!s->trem_depth) return;
    float vol64 = x->x_chvol[c] +
        vib_sine(s->trem_phase) * (s->trem_depth / 15.0f) * 16.0f;
    if (vol64 < 0) vol64 = 0;
    if (vol64 > 64) vol64 = 64;
    emit_vol(x, c, vol64 / 64.0f);
}

/* trigger the note part of an event (after delay/glide decisions) */
static void fire_note(t_modseq *x, int c, t_ev *e) {
    t_chst *s = &x->x_ch[c];
    int note = e->note;
    if (note == 254) { emit_off(x, c); return; }
    /* XM notes are 1..96; MOD notes are 1/64-semitone fixed point */
    if (note < 1 || (!x->x_is_mod && note > 96)) return;
    int ins = e->ins ? e->ins : x->x_lastins[c];
    if (ins < 1) ins = 1;
    if (ins > x->x_nins) ins = x->x_nins;
    float n = x->x_is_mod ? note / 64.0f : (float)(note + 11);
    s->base = n;
    int vol = x->x_chvol[c];
    if (x->x_is_mod && !x->x_chvolset[c])
        vol = x->x_ins[ins - 1].smp[0].defvol;
    emit_note(x, c, ins, n, vol / 64.0f);
    emit_vol(x, c, vol / 64.0f);
}

/* process one row event on one channel (tick 0) */
static void row_event(t_modseq *x, int c, t_ev *e) {
    t_chst *s = &x->x_ch[c];
    int note = e->note;
    /* XM volume column */
    if (e->vol >= 0x10 && e->vol <= 0x50) {
        x->x_chvol[c] = e->vol - 0x10;
        x->x_chvolset[c] = 1;
    } else if (e->vol >= 0xf0) s->slide = (int)(e->vol & 0x0f);
    else if (e->vol >= 0xe0) s->slide = -(int)(e->vol & 0x0f);
    else if (e->vol >= 0x60 && e->vol < 0x70) {
        x->x_chvol[c] += e->vol & 0x0f;
        if (x->x_chvol[c] > 64) x->x_chvol[c] = 64;
    } else if (e->vol >= 0x51 && e->vol < 0x60) {
        x->x_chvol[c] -= e->vol & 0x0f;
        if (x->x_chvol[c] < 0) x->x_chvol[c] = 0;
    }
    if (e->ins) x->x_lastins[c] = e->ins;

    int fx = e->fx, fxp = e->fxp;
    switch (fx) {
    case 0:
        if (fxp) { s->arp[0] = fxp >> 4; s->arp[1] = fxp & 0x0f; s->arp_on = 1; }
        else s->arp_on = 0;
        break;
    case 1: s->slide = (int)fxp; break;
    case 2: s->slide = -(int)fxp; break;
    case 3:
    case 5:
        if (fx == 5) s->slide = ((fxp >> 4) - (fxp & 0x0f));
        break;
    case 4:
    case 6:
        if (fx == 4 || !s->vib_depth) {
            s->vib_speed = fxp >> 4;
            s->vib_depth = fxp & 0x0f;
            s->vib_phase = 0;
        }
        if (fx == 6) s->slide = ((fxp >> 4) - (fxp & 0x0f));
        break;
    case 7:
        s->trem_speed = fxp >> 4;
        s->trem_depth = fxp & 0x0f;
        s->trem_phase = 0;
        break;
    case 8: break;   /* panning: mono output */
    case 9: {
        int ins = e->ins ? e->ins : x->x_lastins[c];
        if (ins >= 1 && ins <= x->x_nins) {
            t_smpdat *sd = &x->x_ins[ins - 1].smp[0];
            if (sd->frames > 0)
                s->offset = (float)(fxp * 256) / sd->frames;
            if (s->offset > 1) s->offset = 1;
        }
        break;
    }
    case 0xa: s->slide = (int)(fxp >> 4) - (int)(fxp & 0x0f); break;
    case 0xb: x->x_jump = fxp; break;
    case 0xc:
        x->x_chvol[c] = fxp > 64 ? 64 : fxp;
        x->x_chvolset[c] = 1;
        s->slide = 0;
        break;
    case 0xd: x->x_breakto = (fxp >> 4) * 10 + (fxp & 0x0f); break;
    case 0xe:
        switch (fxp >> 4) {
        case 1: s->slide = (int)(fxp & 0x0f); break;
        case 2: s->slide = -(int)(fxp & 0x0f); break;
        case 5: /* finetune override: rare, skipped */ break;
        case 6:
            if ((fxp & 0x0f) == 0) s->loop_row = x->x_row;
            else {
                if (s->loop_count == 0) s->loop_count = fxp & 0x0f;
                if (s->loop_count > 0) {
                    s->loop_count--;
                    if (s->loop_row <= x->x_row)
                        x->x_row_goto = s->loop_row;
                }
            }
            break;
        case 9:
            s->retrig_period = (fxp & 0x0f) ? (fxp & 0x0f) : 1;
            s->retrig = s->retrig_period;
            break;
        case 0xa:
            x->x_chvol[c] += fxp & 0x0f;
            if (x->x_chvol[c] > 64) x->x_chvol[c] = 64;
            break;
        case 0xb:
            x->x_chvol[c] -= fxp & 0x0f;
            if (x->x_chvol[c] < 0) x->x_chvol[c] = 0;
            break;
        case 0xc: s->cut = (fxp & 0x0f) ? (fxp & 0x0f) : 1; break;
        case 0xd: s->delay = (fxp & 0x0f) ? (fxp & 0x0f) : 1; break;
        }
        break;
    case 0xf:
        if (fxp <= 0x1f) { if (fxp) x->x_speed = fxp; }
        else {
            x->x_tempo = fxp;
            t_atom t[1];
            SETFLOAT(&t[0], (t_float)x->x_tempo);
            outlet_anything(x->x_meta, gensym("tempo"), 1, t);
        }
        break;
    }

    /* key-off */
    if (note == 254) { emit_off(x, c); return; }
    if (note >= 1) {
        if (s->delay > 0) {
            s->dly = *e;
            s->dly.fx = 0; s->dly.fxp = 0;
            return;
        }
        if (fx == 3 || fx == 5) {
            /* tone portamento: glide to the note instead of retrigger */
            s->porta = x->x_is_mod ? note / 64.0f : (float)(note + 11);
            s->porta_on = 1;
        } else {
            s->porta_on = 0;
            fire_note(x, c, e);
        }
    }
}

static void modseq_tick(t_modseq *x) {
    if (!x->x_playing) return;
    outlet_bang(x->x_tickout);   /* envelope clocks first */
    t_pat *pat = x->x_npat ? x->x_pat[x->x_order[x->x_orderi] % x->x_npat] : NULL;
    if (!pat) { modseq_stop(x); outlet_bang(x->x_meta); return; }
    int nch = x->x_nch;
    int row = x->x_row;
    if (row >= pat->rows) row = 0;
    if (x->x_tick == 0) {
        t_atom a[2];
        SETFLOAT(&a[0], (t_float)x->x_orderi);
        SETFLOAT(&a[1], (t_float)row);
        outlet_anything(x->x_meta, gensym("row"), 2, a);
        x->x_jump = x->x_breakto = -1;
        x->x_row_goto = -1;
        for (int c = 0; c < nch; c++)
            row_event(x, c, &pat->e[row * nch + c]);
    } else {
        for (int c = 0; c < nch; c++) {
            t_chst *s = &x->x_ch[c];
            if (s->delay > 0) {
                s->delay--;
                if (s->delay == 0 && s->dly.note >= 1) {
                    t_ev e = s->dly;
                    s->delay = 0;
                    row_event(x, c, &e);
                }
            }
            if (s->cut > 0) {
                s->cut--;
                if (s->cut == 0) emit_off(x, c);
            }
            if (s->retrig > 0) {
                s->retrig--;
                if (s->retrig == 0) {
                    s->retrig = s->retrig_period;
                    if (s->base > 0)
                        emit_note(x, c, x->x_lastins[c], s->base,
                                  x->x_chvol[c] / 64.0f);
                }
            }
        }
    }
    for (int c = 0; c < nch; c++) { tick_pitch(x, c); tick_volume(x, c); }
    x->x_tick++;
    if (x->x_tick >= x->x_speed) {
        x->x_tick = 0;
        if (x->x_row_goto >= 0) {
            x->x_row = x->x_row_goto;
        } else if (x->x_jump >= 0 && x->x_jump < x->x_nord) {
            x->x_orderi = x->x_jump;
            x->x_row = 0;
        } else if (x->x_breakto >= 0) {
            x->x_orderi++;
            x->x_row = x->x_breakto;
        } else {
            x->x_row++;
            if (x->x_row >= pat->rows) { x->x_row = 0; x->x_orderi++; }
        }
        if (x->x_orderi >= x->x_nord) {
            if (x->x_loop && x->x_nord > 0) x->x_orderi = 0;
            else {
                modseq_stop(x);
                outlet_bang(x->x_meta);
                return;
            }
        }
    }
    clock_delay(x->x_clock, 2500.0 / x->x_tempo);
}

/* ----------------------------------------------------------- load / dump */

static void modseq_freemod(t_modseq *x) {
    for (int p = 0; p < x->x_npat; p++) {
        if (x->x_pat[p]) { free(x->x_pat[p]->e); free(x->x_pat[p]); }
        x->x_pat[p] = NULL;
    }
    x->x_npat = 0;
    for (int i = 0; i < x->x_nins; i++)
        for (int s = 0; s < x->x_ins[i].nsmp; s++) {
            free(x->x_ins[i].smp[s].data);
            x->x_ins[i].smp[s].data = NULL;
        }
    x->x_nins = 0;
}

static void modseq_open(t_modseq *x, t_symbol *name) {
    char realname[MAXPDSTRING];
    const char *path = name->s_name;
    if (path[0] == '/' || strchr(path, ':')) {
        strncpy(realname, path, MAXPDSTRING - 1);
        realname[MAXPDSTRING - 1] = 0;
    } else if (x->x_dir[0])
        snprintf(realname, MAXPDSTRING, "%s/%s", x->x_dir, path);
    else {
        strncpy(realname, path, MAXPDSTRING - 1);
        realname[MAXPDSTRING - 1] = 0;
    }
    const char *ext = strrchr(realname, '.');
    const char *err;
    modseq_freemod(x);
    modseq_reset(x);
    x->x_is_mod = 0;
    if (ext && (!strcmp(ext, ".mod") || !strcmp(ext, ".MOD")))
        err = mod_parse(x, realname);
    else if (ext && (!strcmp(ext, ".xm") || !strcmp(ext, ".XM")))
        err = xm_parse(x, realname);
    else err = "need a .xm or .mod file";
    if (err) { pd_error(x, "modseq: %s: %s", name->s_name, err); return; }
    x->x_speed = x->x_speed ? x->x_speed : 6;
    x->x_tempo = x->x_tempo ? x->x_tempo : 125;
    /* NOTE: arrays are NOT published here — creating canvas objects while
     * the patch is still loading desynchronizes pd's #X connect indices.
     * Publish on the arrays/start messages, i.e. after the load. */
    post("modseq: %s: %d channels, %d instruments, %d orders, speed %d, tempo %d",
         name->s_name, x->x_nch, x->x_nins, x->x_nord, x->x_speed, x->x_tempo);
}

/* Publishes every sample as a pd array; when emitmeta is set, also sends
 * the full instrument description (smp / keymap / envelope) so [instr]
 * voices can configure themselves from the module. */
static void modseq_arrays(t_modseq *x, int emitmeta);

/* Called via the `arrays` message (or start): must run AFTER the patch
 * has finished loading — creating canvas objects during load desyncs pd's
 * connect indices. The demo patch therefore triggers it from a delay. */
static void modseq_arrays(t_modseq *x, int emitmeta) {
    if (!x->x_nins) { pd_error(x, "modseq: arrays: no module loaded"); return; }
    int n = 0;
    for (int i = 0; i < x->x_nins; i++) {
        t_ins *in = &x->x_ins[i];
        for (int s = 0; s < in->nsmp; s++) {
            t_smpdat *sd = &in->smp[s];
            if (!sd->data || sd->frames <= 0) continue;
            modseq_fill_array(x, i + 1, s + 1, sd);
            n++;
            if (!emitmeta) continue;
            char nm[MAXPDSTRING];
            snprintf(nm, MAXPDSTRING, "ins%d-%d", i + 1, s + 1);
            t_atom b[7];
            SETFLOAT(&b[0], (t_float)(i + 1));
            SETFLOAT(&b[1], (t_float)(s + 1));
            SETSYMBOL(&b[2], gensym(nm));
            SETFLOAT(&b[3], (t_float)sd->basenote);
            SETFLOAT(&b[4], (t_float)sd->loop);
            SETFLOAT(&b[5], (t_float)sd->loopstart);
            SETFLOAT(&b[6], (t_float)(sd->loop ? sd->loopstart + sd->looplen : 0));
            outlet_anything(x->x_meta, gensym("smp"), 7, b);
        }
        if (!emitmeta) continue;
        /* keymap, run-length compressed */
        if (in->nsmp > 1) {
            int run_start = 1;
            for (int k = 1; k <= 96; k++) {
                if (k < 96 && in->keymap[k] == in->keymap[run_start - 1]) continue;
                t_atom r[4];
                SETFLOAT(&r[0], (t_float)(i + 1));
                SETFLOAT(&r[1], (t_float)run_start);
                SETFLOAT(&r[2], (t_float)k);
                SETFLOAT(&r[3], (t_float)in->keymap[run_start - 1]);
                outlet_anything(x->x_meta, gensym("keymap"), 4, r);
                run_start = k + 1;
            }
        }
        /* envelope points + config */
        for (int p = 0; p < in->envn; p++) {
            t_atom e[3];
            SETFLOAT(&e[0], (t_float)(i + 1));
            SETFLOAT(&e[1], (t_float)in->envx[p]);
            SETFLOAT(&e[2], (t_float)in->envy[p]);
            outlet_anything(x->x_meta, gensym("envpt"), 3, e);
        }
        t_atom c[6];
        SETFLOAT(&c[0], (t_float)(i + 1));
        SETFLOAT(&c[1], (t_float)in->envon);
        SETFLOAT(&c[2], (t_float)in->ensus);
        SETFLOAT(&c[3], (t_float)in->enloopen);
        SETFLOAT(&c[4], (t_float)in->enloopb);
        SETFLOAT(&c[5], (t_float)in->fadeout);
        outlet_anything(x->x_meta, gensym("envcfg"), 6, c);
    }
    post("modseq: arrays: done (%d published)", n);
    if (emitmeta) {
        t_atom a[3];
        SETFLOAT(&a[0], (t_float)x->x_nins);
        SETFLOAT(&a[1], (t_float)x->x_nch);
        SETFLOAT(&a[2], (t_float)x->x_nord);
        outlet_anything(x->x_meta, gensym("loaded"), 3, a);
    }
}

static void modseq_arrays_msg(t_modseq *x) { modseq_arrays(x, 1); }

static void modseq_info(t_modseq *x) {
    post("modseq: %d orders, %d channels, %d instruments, speed %d, tempo %d, loop %d",
         x->x_nord, x->x_nch, x->x_nins, x->x_speed, x->x_tempo, x->x_loop);
    for (int i = 0; i < x->x_nins; i++) {
        t_ins *in = &x->x_ins[i];
        post("  ins %d \"%s\": %d sample(s), fadeout %d, env %s (%d pts)",
             i + 1, in->name, in->nsmp, in->fadeout,
             in->envon ? "on" : "off", in->envn);
    }
}

/* --------------------------------------------------------------- messages */

static void modseq_start(t_modseq *x) {
    if (!x->x_npat) { pd_error(x, "modseq: start: no module loaded"); return; }
    modseq_arrays(x, 1);   /* publish + emit metas so [instr]s configure */
    modseq_reset(x);
    x->x_playing = 1;
    clock_delay(x->x_clock, 0);
}

static void modseq_play(t_modseq *x, t_float f) {
    if (f != 0) {
        if (!x->x_playing) {
            if (!x->x_npat) { pd_error(x, "modseq: play: no module loaded"); return; }
            modseq_arrays(x, 0);
            modseq_reset(x);
            x->x_playing = 1;
            clock_delay(x->x_clock, 0);
        }
    } else modseq_stop(x);
}

static void modseq_goto(t_modseq *x, t_float f) {
    int o = (int)f;
    if (o < 0 || o >= x->x_nord) return;
    x->x_orderi = o;
    x->x_row = x->x_tick = 0;
    x->x_jump = x->x_breakto = -1;
}

static void modseq_loop(t_modseq *x, t_float f) { x->x_loop = f != 0; }

/* ---------------------------------------------------------------- object */

static void modseq_autostart(t_modseq *x) {
    modseq_start(x);
}

static void modseq_free(t_modseq *x) {
    modseq_stop(x);
    modseq_freemod(x);
    clock_free(x->x_clock);
    clock_free(x->x_startclock);
}

static void *modseq_new(t_symbol *s, int argc, t_atom *argv) {
    t_modseq *x = (t_modseq *)pd_new(modseq_class);
    (void)s;
    x->x_clock = clock_new(x, (t_method)modseq_tick);
    x->x_startclock = clock_new(x, (t_method)modseq_autostart);
    x->x_out = outlet_new(&x->x_obj, &s_list);
    x->x_meta = outlet_new(&x->x_obj, &s_list);
    x->x_tickout = outlet_new(&x->x_obj, &s_bang);
    x->x_loop = 1;
    x->x_speed = 6;
    x->x_tempo = 125;
    modseq_reset(x);
    t_canvas *canvas = canvas_getcurrent();
    x->x_dir[0] = 0;
    x->x_canvas = canvas;
    if (canvas) {
        t_symbol *d = canvas_getdir(canvas);
        strncpy(x->x_dir, d->s_name, MAXPDSTRING - 1);
        x->x_dir[MAXPDSTRING - 1] = 0;
    }
    if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
        modseq_open(x, atom_getsymbol(argv));
        if (x->x_npat) {
            /* self-start: fires 500ms after the DSP scheduler begins —
             * creating canvas objects during load desyncs pd's indices */
            clock_delay(x->x_startclock, 500);
        }
    }
    return (void *)x;
}

void modseq_setup(void) {
    modseq_class = class_new(gensym("modseq"), (t_newmethod)modseq_new,
        (t_method)modseq_free, sizeof(t_modseq), 0, A_GIMME, 0);
    class_addmethod(modseq_class, (t_method)modseq_open,
        gensym("open"), A_SYMBOL, 0);
    class_addmethod(modseq_class, (t_method)modseq_start,
        gensym("start"), 0);
    class_addmethod(modseq_class, (t_method)modseq_stop,
        gensym("stop"), 0);
    class_addmethod(modseq_class, (t_method)modseq_play,
        gensym("play"), A_FLOAT, 0);
    class_addmethod(modseq_class, (t_method)modseq_goto,
        gensym("goto"), A_FLOAT, 0);
    class_addmethod(modseq_class, (t_method)modseq_loop,
        gensym("loop"), A_FLOAT, 0);
    class_addmethod(modseq_class, (t_method)modseq_arrays_msg,
        gensym("arrays"), 0);
    class_addmethod(modseq_class, (t_method)modseq_info,
        gensym("info"), 0);
    class_addbang(modseq_class, (t_method)modseq_start);
}
