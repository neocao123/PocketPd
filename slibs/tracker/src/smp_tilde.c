/* smp~ - wav sample player from device storage, pitch-shifted by note.
 *
 * [smp~ sound.wav] loads a PCM wav (8/16/24-bit, mono or stereo -> mono)
 * relative to the patch directory and plays it at any pitch. Pairs with
 * [modseq]: one instance per instrument, note events drive it — but it is
 * an independent voice usable with keyboards, [qlist], or anything else.
 *
 *   inlet  (messages): open <file>          load a wav
 *                      note <midi> [gain]   (also: float, or list) start
 *                      bang                 retrigger the last note
 *                      off                  stop (alias: stop, noteoff)
 *                      base <midi>          note played at rate 1.0 (def 60)
 *                      gain <g>             output scale
 *                      loop <0/1>           enable looping
 *                      looppts <s> <e>      loop range in frames
 *                      info                 post the current state
 *   outlet 1: signal
 *   outlet 2: bang when a one-shot (non-looping) note reaches the end
 *
 * The base note and loop points are auto-configured from the wav's smpl
 * chunk when present (as written by [modseq]'s dump). Interpolation is
 * 4-point cubic Hermite, so extreme pitches stay clean.
 */

#include "m_pd.h"
#include "g_canvas.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

typedef struct _smp {
    t_object x_obj;
    t_outlet *x_done;
    t_sample *x_buf;
    long x_frames;
    long x_loopstart, x_loopend;   /* frames; loopend exclusive */
    int x_loop;
    int x_filesr;
    float x_base;
    float x_gain;
    float x_note;
    float x_rate;                  /* buffer frames per output frame */
    int x_playing;
    double x_phase;
    char x_dir[MAXPDSTRING];
} t_smp;

static t_class *smp_class;

/* ---------------------------------------------------------------- wav in */

static long rd_l(const unsigned char *p) {
    return (long)((unsigned int)p[0] | ((unsigned int)p[1] << 8) |
                  ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24));
}

static int wav_load(t_smp *x, const char *path, const char *name) {
    FILE *f = fopen(path, "rb");
    if (!f) { pd_error(x, "smp~: cannot open %s", name); return 0; }
    unsigned char hdr[12];
    if (fread(hdr, 1, 12, f) != 12 || memcmp(hdr, "RIFF", 4) ||
        memcmp(hdr + 8, "WAVE", 4)) {
        fclose(f);
        pd_error(x, "smp~: %s: not a wav file", name);
        return 0;
    }
    int fmt = 1, ch = 1, bits = 16, havesmpl = 0;
    long sr = 8363, datapos = -1, datalen = 0;
    int unity = 60, numloops = 0, lstart = 0, lend = 0;
    for (;;) {
        unsigned char ch8[8];
        long n = (long)fread(ch8, 1, 8, f);
        if (n < 8) break;
        long csize = rd_l(ch8 + 4);
        if (!memcmp(ch8, "fmt ", 4) && csize >= 16) {
            unsigned char fh[16];
            if (fread(fh, 1, 16, f) != 16) break;
            fmt = fh[0] | (fh[1] << 8);
            ch = fh[2] | (fh[3] << 8);
            sr = rd_l(fh + 4);
            bits = fh[14] | (fh[15] << 8);
            csize -= 16;
        } else if (!memcmp(ch8, "data", 4)) {
            datapos = ftell(f);
            datalen = csize;
        } else if (!memcmp(ch8, "smpl", 4) && csize >= 60) {
            unsigned char sm[60];
            if (fread(sm, 1, 60, f) != 60) break;
            unity = (int)rd_l(sm + 12);
            numloops = (int)rd_l(sm + 28);
            if (numloops > 0) {
                lstart = (int)rd_l(sm + 36 + 8);
                lend = (int)rd_l(sm + 36 + 12);   /* inclusive */
                havesmpl = 1;
            }
            csize -= 60;
        }
        if (csize & 1) csize++;          /* chunks are word-aligned */
        fseek(f, csize, SEEK_CUR);
    }
    if (datapos < 0 || datalen <= 0) {
        fclose(f);
        pd_error(x, "smp~: %s: no data chunk", name);
        return 0;
    }
    if (fmt != 1 && fmt != 0xfffe) {
        fclose(f);
        pd_error(x, "smp~: %s: only PCM wavs are supported", name);
        return 0;
    }
    if (bits != 8 && bits != 16 && bits != 24) {
        fclose(f);
        pd_error(x, "smp~: %s: unsupported bit depth %d", name, bits);
        return 0;
    }
    int bytes = bits / 8;
    long frames = datalen / (bytes * (ch > 1 ? 2 : 1));
    if (frames < 4) {
        fclose(f);
        pd_error(x, "smp~: %s: too short", name);
        return 0;
    }
    t_sample *buf = (t_sample *)malloc(sizeof(t_sample) * (size_t)frames);
    if (!buf) {
        fclose(f);
        pd_error(x, "smp~: out of memory");
        return 0;
    }
    fseek(f, datapos, SEEK_SET);
    unsigned char *raw = (unsigned char *)malloc((size_t)datalen);
    if (!raw || (long)fread(raw, 1, (size_t)datalen, f) != datalen) {
        free(raw);
        free(buf);
        fclose(f);
        pd_error(x, "smp~: %s: read failed", name);
        return 0;
    }
    fclose(f);
    int stride = bytes * (ch > 1 ? 2 : 1);
    for (long i = 0; i < frames; i++) {
        long o = i * stride;
        float l, r = 0;
        if (bits == 8) {
            l = ((int)raw[o] - 128) / 128.0f;
            if (ch > 1) r = ((int)raw[o + 1] - 128) / 128.0f;
        } else if (bits == 16) {
            int v = (short)((unsigned int)raw[o] |
                            ((unsigned int)raw[o + 1] << 8));
            l = v / 32768.0f;
            if (ch > 1) {
                int v2 = (short)((unsigned int)raw[o + 2] |
                                 ((unsigned int)raw[o + 3] << 8));
                r = v2 / 32768.0f;
            }
        } else {
            long v = (long)((unsigned int)raw[o] |
                            ((unsigned int)raw[o + 1] << 8) |
                            ((unsigned int)raw[o + 2] << 16));
            if (v & 0x800000) v -= 0x1000000;
            l = v / 8388608.0f;
            if (ch > 1) {
                long v2 = (long)((unsigned int)raw[o + 3] |
                                 ((unsigned int)raw[o + 4] << 8) |
                                 ((unsigned int)raw[o + 5] << 16));
                if (v2 & 0x800000) v2 -= 0x1000000;
                r = v2 / 8388608.0f;
            }
        }
        buf[i] = ch > 1 ? (l + r) * 0.5f : l;
    }
    free(raw);
    free(x->x_buf);
    x->x_buf = buf;
    x->x_frames = frames;
    x->x_filesr = (int)sr;
    x->x_loopstart = 0;
    x->x_loopend = frames;
    x->x_loop = 0;
    if (havesmpl && lend > lstart) {
        x->x_loop = 1;
        x->x_loopstart = lstart;
        x->x_loopend = (long)lend + 1;     /* smpl end is inclusive */
    }
    if (unity >= 12 && unity <= 120) x->x_base = (float)unity;
    x->x_phase = 0;
    x->x_playing = 0;
    post("smp~: %s: %ld frames, %ld Hz, %d bit, base %.0f, loop %d",
         name, x->x_frames, sr, bits, x->x_base, x->x_loop);
    return 1;
}

/* ------------------------------------------------------------------- dsp */

static void smp_recalc(t_smp *x) {
    double sr = sys_getsr();
    if (sr <= 0) sr = 44100;
    x->x_rate = (float)(pow(2.0, (x->x_note - x->x_base) / 12.0)
                        * x->x_filesr / sr);
}

static t_int *smp_perform(t_int *w) {
    t_smp *x = (t_smp *)(w[1]);
    t_sample *out = (t_sample *)(w[2]);
    int n = (int)(w[3]);
    int ended = 0;
    if (!x->x_playing || !x->x_buf || x->x_frames < 4) {
        for (int i = 0; i < n; i++) out[i] = 0;
        return w + 4;
    }
    double phase = x->x_phase;
    float rate = x->x_rate;
    float gain = x->x_gain;
    long n1 = x->x_frames - 1;
    long ls = x->x_loopstart, le = x->x_loopend;
    if (le > n1) le = n1 + 1;
    int looping = x->x_loop && le - ls > 4;
    for (int i = 0; i < n; i++) {
        if (looping) {
            /* wrap 3 frames early: hermite read needs ip+2 in range */
            if (phase >= le - 3) phase -= (le - ls);
        } else if (phase > n1 - 3) {          /* reached the end */
            x->x_playing = 0;
            for (; i < n; i++) out[i] = 0;
            ended = 1;
            break;
        }
        long ip = (long)phase;
        float t = (float)(phase - ip);
        const t_sample *b = x->x_buf + ip;
        t_sample a0 = ip > 0 ? b[-1] : b[0];
        t_sample a1 = b[0], a2 = b[1], a3 = b[2];
        t_sample c1 = 0.5f * (a2 - a0);
        t_sample c2 = a0 - 2.5f * a1 + 2.f * a2 - 0.5f * a3;
        t_sample c3 = 0.5f * (a3 - a0) + 1.5f * (a1 - a2);
        out[i] = gain * (((c3 * t + c2) * t + c1) * t + a1);
        phase += rate;
    }
    x->x_phase = phase;
    if (ended) outlet_bang(x->x_done);
    return w + 4;
}

static void smp_dsp(t_smp *x, t_signal **sp) {
    smp_recalc(x);
    dsp_add(smp_perform, 3, x, sp[0]->s_vec, (t_int)sp[0]->s_n);
}

/* -------------------------------------------------------------- messages */

static void smp_open(t_smp *x, t_symbol *name) {
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
    wav_load(x, realname, name->s_name);
}

static void smp_play(t_smp *x, t_float note, t_float gain, int hasgain) {
    if (!x->x_buf || x->x_frames < 4) {
        pd_error(x, "smp~: note: no sample loaded");
        return;
    }
    x->x_note = note;
    if (hasgain) x->x_gain = gain > 0 ? gain : 0;
    smp_recalc(x);
    x->x_phase = 0;
    x->x_playing = 1;
}

static void smp_note(t_smp *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    t_float note = atom_getfloatarg(0, argc, argv);
    t_float gain = atom_getfloatarg(1, argc, argv);
    smp_play(x, note, gain, argc > 1);
}

static void smp_float(t_smp *x, t_float f) { smp_play(x, f, 0, 0); }

static void smp_list(t_smp *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    t_float note = atom_getfloatarg(0, argc, argv);
    t_float gain = atom_getfloatarg(1, argc, argv);
    smp_play(x, note, gain, argc > 1);
}

static void smp_off(t_smp *x) { x->x_playing = 0; }

static void smp_bang(t_smp *x) { smp_play(x, x->x_note, 0, 0); }

static void smp_base(t_smp *x, t_float f) {
    x->x_base = f;
    smp_recalc(x);
}

static void smp_gain(t_smp *x, t_float f) {
    x->x_gain = f > 0 ? f : 0;
}

static void smp_loop(t_smp *x, t_float f) { x->x_loop = f != 0; }

static void smp_looppts(t_smp *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    long a = (long)atom_getfloatarg(0, argc, argv);
    long b = (long)atom_getfloatarg(1, argc, argv);
    if (a >= 0 && a < x->x_frames) x->x_loopstart = a;
    if (b > x->x_loopstart && b <= x->x_frames) x->x_loopend = b;
}

static void smp_info(t_smp *x) {
    post("smp~: %ld frames, filesr %d, base %.1f, gain %.2f, note %.1f, loop %d [%ld..%ld], playing %d",
         x->x_frames, x->x_filesr, x->x_base, x->x_gain, x->x_note,
         x->x_loop, x->x_loopstart, x->x_loopend, x->x_playing);
}

/* ---------------------------------------------------------------- object */

static void smp_free(t_smp *x) {
    free(x->x_buf);
}

static void *smp_new(t_symbol *s, int argc, t_atom *argv) {
    t_smp *x = (t_smp *)pd_new(smp_class);
    (void)s;
    x->x_buf = NULL;
    x->x_frames = 0;
    x->x_loopstart = 0;
    x->x_loopend = 0;
    x->x_loop = 0;
    x->x_filesr = 8363;
    x->x_base = 60;
    x->x_gain = 1;
    x->x_note = 60;
    x->x_rate = 1;
    x->x_playing = 0;
    x->x_phase = 0;
    x->x_dir[0] = 0;
    outlet_new(&x->x_obj, &s_signal);              /* outlet 0: signal */
    x->x_done = outlet_new(&x->x_obj, &s_bang);    /* outlet 1: end bang */
    t_canvas *canvas = canvas_getcurrent();
    if (canvas) {
        t_symbol *d = canvas_getdir(canvas);
        strncpy(x->x_dir, d->s_name, MAXPDSTRING - 1);
        x->x_dir[MAXPDSTRING - 1] = 0;
    }
    if (argc >= 1 && argv[0].a_type == A_SYMBOL)
        smp_open(x, atom_getsymbol(argv));
    return (void *)x;
}

void smp_tilde_setup(void) {
    smp_class = class_new(gensym("smp~"), (t_newmethod)smp_new,
        (t_method)smp_free, sizeof(t_smp), 0, A_GIMME, 0);
    class_addmethod(smp_class, (t_method)smp_dsp,
        gensym("dsp"), A_CANT, 0);
    class_addmethod(smp_class, (t_method)smp_open,
        gensym("open"), A_SYMBOL, 0);
    class_addmethod(smp_class, (t_method)smp_note,
        gensym("note"), A_GIMME, 0);
    class_addmethod(smp_class, (t_method)smp_base,
        gensym("base"), A_FLOAT, 0);
    class_addmethod(smp_class, (t_method)smp_gain,
        gensym("gain"), A_FLOAT, 0);
    class_addmethod(smp_class, (t_method)smp_loop,
        gensym("loop"), A_FLOAT, 0);
    class_addmethod(smp_class, (t_method)smp_looppts,
        gensym("looppts"), A_GIMME, 0);
    class_addmethod(smp_class, (t_method)smp_info,
        gensym("info"), 0);
    class_addbang(smp_class, smp_bang);
    class_addfloat(smp_class, (t_method)smp_float);
    class_addlist(smp_class, (t_method)smp_list);
}
