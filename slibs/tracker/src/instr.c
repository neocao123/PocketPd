/* instr - tracker instrument: pd arrays + keymap + volume envelope + fade.
 *
 * [instr <id>] owns everything about HOW an instrument sounds: a bank of
 * pd arrays (the samples — e.g. published by [modseq]'s arrays message,
 * or filled by soundfiler), a keymap selecting the sample per note, a
 * multi-point volume envelope with sustain and loop, the key-off fadeout,
 * and a polyphonic voice bank (mono per tracker channel, like FT2
 * channels) with a single signal output.
 *
 * The samples are referenced LIVE from pd arrays — the pd-native data
 * type — so any tool that fills arrays can drive this object and nothing
 * ever touches the filesystem.
 *
 * Tracker wiring ([modseq] events feed straight in; everything with an
 * <ins> field is filtered against this instrument's id):
 *   note <ch> <ins> <midi> <gain>
 *   off <ch> <ins> / pitch <ch> <ins> <semis> / vol <ch> <ins> <gain>
 *   pos <ch> <ins> <0..1>
 *   smp <ins> <idx> <array> <base> [loop] [ls] [le]
 *   keymap <ins> <from> <to> <smp>
 *   envpt <ins> <x> <y> / envcfg <ins> <envon> <sus> <la> <lb> <fade>
 *   bang — advance the envelope one song tick
 *
 * Standalone: float or "note <midi> [gain]" plays with ch = 0; "off"
 * releases everything; "smp <idx> <array>" and "key <from> <to> <idx>"
 * configure without an id.
 */

#include "m_pd.h"
#include "g_canvas.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define IN_MAXSMP 16
#define IN_MAXVOICES 16
#define IN_MAXCH 32
#define IN_MAXENV 32

typedef struct {
    t_symbol *sym;       /* pd array name */
    t_word *vec;         /* live array data (owned by pd) */
    long frames;
    float base;          /* MIDI note at rate 1.0 */
    int loop;
    long ls, le;         /* loop range, le exclusive */
} i_smp;

typedef struct {
    int on;
    int ch;
    int smp;             /* 1-based sample in use */
    float note, gain;
    float pitchoff;
    double phase;
    float rate;
    double envpos;       /* envelope x units (ticks) */
    int released, envdone;
    float fadevol;       /* 65536 -> 0 once past the envelope */
    float cur;
} i_voice;

typedef struct _instr {
    t_object x_obj;
    t_outlet *x_out;
    i_smp x_smp[IN_MAXSMP];
    int x_nsmp;
    int x_keymap[96];    /* XM note 1..96 -> sample (1-based) */
    int x_envon, x_envn, x_envsus, x_envloopa, x_envloopb;
    int x_envx[IN_MAXENV], x_envy[IN_MAXENV];
    float x_fadeout;
    i_voice x_v[IN_MAXVOICES];
    int x_chvoice[IN_MAXCH];
    int x_myid;
    float x_master;
    float x_tickms;
    double x_lasttick;
    double x_sr;
    int x_rr;
    char x_dir[MAXPDSTRING];
} t_instr;

static t_class *instr_class;

/* ------------------------------------------------------------ envelope */

static float instr_envval(t_instr *x, i_voice *v) {
    if (!x->x_envon || x->x_envn == 0) return 1.0f;
    double p = v->envpos;
    if (x->x_envn >= 2) {
        if (x->x_envloopb > x->x_envloopa && p >= x->x_envx[x->x_envloopb])
            p = x->x_envx[x->x_envloopa];
        if (!v->released && x->x_envsus < x->x_envn &&
            p >= x->x_envx[x->x_envsus])
            p = x->x_envx[x->x_envsus];
    }
    v->envpos = p;
    if (p <= x->x_envx[0]) return x->x_envy[0] / 64.0f;
    for (int i = 1; i < x->x_envn; i++) {
        if (p < x->x_envx[i]) {
            double t = (p - x->x_envx[i - 1]) /
                       (double)(x->x_envx[i] - x->x_envx[i - 1]);
            return (float)((x->x_envy[i - 1] +
                            t * (x->x_envy[i] - x->x_envy[i - 1])) / 64.0);
        }
    }
    v->envdone = 1;
    return x->x_envy[x->x_envn - 1] / 64.0f;
}

static int instr_advance(t_instr *x, i_voice *v, double dticks) {
    if (!v->on) return 0;
    v->envpos += dticks;
    float env = instr_envval(x, v);
    if (v->released && (v->envdone || !x->x_envon)) {
        v->fadevol -= x->x_fadeout * (float)dticks;
        if (v->fadevol <= 0) { v->on = 0; return 0; }
    }
    v->cur = env * (v->fadevol / 65536.0f) * v->gain;
    return 1;
}

/* ----------------------------------------------------------------- dsp */

static void instr_retune(t_instr *x, i_voice *v) {
    const i_smp *s = &x->x_smp[v->smp - 1];
    v->rate = (float)pow(2.0, (v->note + v->pitchoff - s->base) / 12.0);
}

static t_int *instr_perform(t_int *w) {
    t_instr *x = (t_instr *)(w[1]);
    t_sample *out = (t_sample *)(w[2]);
    int n = (int)(w[3]);
    memset(out, 0, sizeof(t_sample) * (size_t)n);
    double dticks = (x->x_tickms > 1)
        ? (1000.0 * n / x->x_sr) / x->x_tickms : 0;
    for (int vi = 0; vi < IN_MAXVOICES; vi++) {
        i_voice *v = &x->x_v[vi];
        if (!v->on) continue;
        if (!instr_advance(x, v, dticks)) continue;
        const i_smp *s = &x->x_smp[v->smp - 1];
        if (!s->vec || s->frames < 4) { v->on = 0; continue; }
        float g0 = v->cur;
        double save = v->envpos;
        float savefade = v->fadevol;
        instr_advance(x, v, dticks);
        float g1 = v->cur;
        v->envpos = save; v->fadevol = savefade;
        if (!v->on) { v->on = 1; v->cur = 0; continue; }
        long ls = s->ls, le = s->le;
        if (le > s->frames) le = s->frames;
        int looping = s->loop && le - ls > 4;
        double phase = v->phase;
        float rate = v->rate;
        long n1 = s->frames - 1;
        for (int i = 0; i < n; i++) {
            if (looping) {
                /* wrap 3 frames early: the hermite read needs ip+2 in range */
                if (phase >= le - 3) phase -= (le - ls);
            } else if (phase > n1 - 3) {
                v->on = 0;
                break;
            }
            long ip = (long)phase;
            float t = (float)(phase - ip);
            const t_word *b = s->vec + ip;
            t_sample a0 = ip > 0 ? b[-1].w_float : b[0].w_float;
            t_sample a1 = b[0].w_float, a2 = b[1].w_float, a3 = b[2].w_float;
            t_sample c1 = 0.5f * (a2 - a0);
            t_sample c2 = a0 - 2.5f * a1 + 2.f * a2 - 0.5f * a3;
            t_sample c3 = 0.5f * (a3 - a0) + 1.5f * (a1 - a2);
            float g = g0 + (g1 - g0) * (i / (float)n);
            out[i] += x->x_master * g *
                (((c3 * t + c2) * t + c1) * t + a1);
            phase += rate;
        }
        v->phase = phase;
    }
    return w + 4;
}

static void instr_dsp(t_instr *x, t_signal **sp) {
    x->x_sr = sp[0]->s_sr > 0 ? sp[0]->s_sr : 44100;
    for (int i = 0; i < IN_MAXVOICES; i++)
        if (x->x_v[i].on) instr_retune(x, &x->x_v[i]);
    dsp_add(instr_perform, 3, x, sp[0]->s_vec, (t_int)sp[0]->s_n);
}

/* ------------------------------------------------------------ messages */

static i_voice *instr_voice_for(t_instr *x, int ch) {
    if (ch >= 0 && ch < IN_MAXCH) {
        int vi = x->x_chvoice[ch];
        if (vi >= 0 && x->x_v[vi].on) return &x->x_v[vi];
    }
    return NULL;
}

static void instr_trigger(t_instr *x, int ch, int smp, float note,
                          float gain, float pos) {
    if (smp < 1 || smp > x->x_nsmp) smp = 1;
    const i_smp *s = &x->x_smp[smp - 1];
    if (!s->vec || s->frames < 4) {
        pd_error(x, "instr: note: sample %d not loaded", smp);
        return;
    }
    i_voice *v = instr_voice_for(x, ch);
    if (!v) {
        int found = -1;
        for (int i = 0; i < IN_MAXVOICES; i++)
            if (!x->x_v[i].on) { found = i; break; }
        if (found < 0) {
            found = x->x_rr;
            x->x_rr = (x->x_rr + 1) % IN_MAXVOICES;
        }
        v = &x->x_v[found];
        if (ch >= 0 && ch < IN_MAXCH) x->x_chvoice[ch] = found;
    }
    v->on = 1;
    v->ch = ch;
    v->smp = smp;
    v->note = note;
    v->gain = gain > 0 ? gain : 0;
    v->pitchoff = 0;
    v->phase = pos * (s->frames - 8);
    v->envpos = 0;
    v->released = 0;
    v->envdone = 0;
    v->fadevol = 65536;
    v->cur = 0;
    instr_retune(x, v);
}

static void instr_note(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 4) {                     /* tracker form: ch ins midi gain */
        int ch = (int)atom_getfloatarg(0, argc, argv);
        int ins = (int)atom_getfloatarg(1, argc, argv);
        if (x->x_myid && ins != x->x_myid) return;
        float midi = atom_getfloatarg(2, argc, argv);
        float gain = atom_getfloatarg(3, argc, argv);
        int xmnote = (int)midi - 11;
        int smp = (xmnote >= 1 && xmnote <= 96) ? x->x_keymap[xmnote - 1] : 1;
        instr_trigger(x, ch, smp, midi, gain, 0);
    } else {                             /* shorthand: midi [gain] */
        float midi = atom_getfloatarg(0, argc, argv);
        float gain = atom_getfloatarg(1, argc, argv);
        int xmnote = (int)midi - 11;
        int smp = (xmnote >= 1 && xmnote <= 96) ? x->x_keymap[xmnote - 1] : 1;
        instr_trigger(x, 0, smp, midi, argc > 1 ? gain : 1.0f, 0);
    }
}

static void instr_float(t_instr *x, t_float f) {
    int xmnote = (int)f - 11;
    int smp = (xmnote >= 1 && xmnote <= 96) ? x->x_keymap[xmnote - 1] : 1;
    instr_trigger(x, 0, smp, (float)f, 1.0f, 0);
}

static void instr_off(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 2) {
        int ch = (int)atom_getfloatarg(0, argc, argv);
        int ins = (int)atom_getfloatarg(1, argc, argv);
        if (x->x_myid && ins != x->x_myid) return;
        i_voice *v = instr_voice_for(x, ch);
        if (v) v->released = 1;
    } else {
        for (int i = 0; i < IN_MAXVOICES; i++)
            if (x->x_v[i].on) x->x_v[i].released = 1;
    }
}

static void instr_pitch(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 3) return;
    int ch = (int)atom_getfloatarg(0, argc, argv);
    int ins = (int)atom_getfloatarg(1, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    i_voice *v = instr_voice_for(x, ch);
    if (!v) return;
    v->pitchoff += atom_getfloatarg(2, argc, argv);
    instr_retune(x, v);
}

static void instr_vol(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 3) return;
    int ch = (int)atom_getfloatarg(0, argc, argv);
    int ins = (int)atom_getfloatarg(1, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    i_voice *v = instr_voice_for(x, ch);
    if (!v) return;
    float g = atom_getfloatarg(2, argc, argv);
    v->gain = g > 0 ? g : 0;
}

static void instr_pos(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 3) return;
    int ch = (int)atom_getfloatarg(0, argc, argv);
    int ins = (int)atom_getfloatarg(1, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    i_voice *v = instr_voice_for(x, ch);
    if (!v) return;
    float frac = atom_getfloatarg(2, argc, argv);
    const i_smp *smp = &x->x_smp[v->smp - 1];
    v->phase = frac * (smp->frames - 8);
}

/* bang = one song tick; measures the tick period for envelope speed */
static void instr_tick(t_instr *x) {
    double now = clock_getlogicaltime();
    if (x->x_lasttick > 0) {
        double ms = clock_gettimesince(x->x_lasttick);
        if (ms > 1 && ms < 2000)
            x->x_tickms = (float)(x->x_tickms > 0 ? (x->x_tickms + ms) / 2 : ms);
    }
    x->x_lasttick = now;
}

/* smp <ins> <idx> <array> [base] [loop] [ls] [le]
 * (or standalone: smp <idx> <array>) — bind to a live pd array */
static void instr_smp(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int idx, ins = 0;
    t_symbol *arr;
    float base = 60;
    int loop = 0;
    long ls = 0, le = 0;
    if (argc >= 3 && argv[0].a_type == A_FLOAT && argv[1].a_type == A_FLOAT &&
        argv[2].a_type == A_SYMBOL) {
        ins = (int)atom_getfloatarg(0, argc, argv);
        if (x->x_myid && ins != x->x_myid) return;
        idx = (int)atom_getfloatarg(1, argc, argv);
        arr = atom_getsymbolarg(2, argc, argv);
        base = atom_getfloatarg(3, argc, argv);
        loop = (int)atom_getfloatarg(4, argc, argv);
        ls = (long)atom_getfloatarg(5, argc, argv);
        le = (long)atom_getfloatarg(6, argc, argv);
    } else if (argc >= 2 && argv[0].a_type == A_FLOAT &&
               argv[1].a_type == A_SYMBOL) {
        idx = (int)atom_getfloatarg(0, argc, argv);
        arr = atom_getsymbolarg(1, argc, argv);
        base = atom_getfloatarg(2, argc, argv);
        loop = (int)atom_getfloatarg(3, argc, argv);
        ls = (long)atom_getfloatarg(4, argc, argv);
        le = (long)atom_getfloatarg(5, argc, argv);
    } else {
        pd_error(x, "instr: smp <idx> <array> [base] [loop] [ls] [le]");
        return;
    }
    if (idx < 1 || idx > IN_MAXSMP) {
        pd_error(x, "instr: smp: idx out of range");
        return;
    }
    t_garray *a = (t_garray *)pd_findbyclass(arr, garray_class);
    if (!a) { pd_error(x, "instr: smp %d: no array '%s'", idx, arr->s_name); return; }
    int n;
    t_word *vec;
    /* pd 0.51 64-bit: garray_getfloatarray is broken, use words */
    if (!garray_getfloatwords(a, &n, &vec)) {
        pd_error(x, "instr: smp %d: %s is not a float array", idx, arr->s_name);
        return;
    }
    i_smp *dst = &x->x_smp[idx - 1];
    dst->sym = arr;
    dst->vec = vec;
    dst->frames = n;
    if (base >= 12 && base <= 120) dst->base = base;
    dst->loop = loop != 0;
    dst->ls = ls;
    dst->le = le > 0 ? le : n;
    if (dst->le > n) dst->le = n;
    if (idx > x->x_nsmp) x->x_nsmp = idx;
    post("instr: smp %d: array '%s' (%ld frames, base %.0f, loop %d)",
         idx, arr->s_name, dst->frames, dst->base, dst->loop);
}

static void instr_keymap(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 4) return;
    int ins = (int)atom_getfloatarg(0, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    int from = (int)atom_getfloatarg(1, argc, argv);
    int to = (int)atom_getfloatarg(2, argc, argv);
    int smp = (int)atom_getfloatarg(3, argc, argv);
    if (from < 1) from = 1;
    if (to > 96) to = 96;
    for (int n = from; n <= to; n++) x->x_keymap[n - 1] = smp;
}

static void instr_key(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 3) return;
    int from = (int)atom_getfloatarg(0, argc, argv);
    int to = (int)atom_getfloatarg(1, argc, argv);
    int smp = (int)atom_getfloatarg(2, argc, argv);
    if (from < 1) from = 1;
    if (to > 96) to = 96;
    for (int n = from; n <= to; n++) x->x_keymap[n - 1] = smp;
}

static void instr_envpt(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 3) return;
    int ins = (int)atom_getfloatarg(0, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    if (x->x_envn >= IN_MAXENV) return;
    x->x_envx[x->x_envn] = (int)atom_getfloatarg(1, argc, argv);
    x->x_envy[x->x_envn] = (int)atom_getfloatarg(2, argc, argv);
    x->x_envn++;
}

/* envcfg <ins> <envon> <sus> <la> <lb> <fadeout> */
static void instr_envcfg(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 6) return;
    int ins = (int)atom_getfloatarg(0, argc, argv);
    if (x->x_myid && ins != x->x_myid) return;
    x->x_envon = (int)atom_getfloatarg(1, argc, argv);
    x->x_envsus = (int)atom_getfloatarg(2, argc, argv);
    x->x_envloopa = (int)atom_getfloatarg(3, argc, argv);
    x->x_envloopb = (int)atom_getfloatarg(4, argc, argv);
    float fo = atom_getfloatarg(5, argc, argv);
    x->x_fadeout = fo < 0 ? 0 : (fo > 65535 ? 65535 : fo);
}

static void instr_env(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    x->x_envn = 0;
    for (int i = 0; i + 1 < argc && x->x_envn < IN_MAXENV; i += 2) {
        x->x_envx[x->x_envn] = (int)atom_getfloatarg(i, argc, argv);
        x->x_envy[x->x_envn] = (int)atom_getfloatarg(i + 1, argc, argv);
        x->x_envn++;
    }
}

static void instr_envon(t_instr *x, t_float f) { x->x_envon = f != 0; }
static void instr_envsus(t_instr *x, t_float f) { x->x_envsus = (int)f; }
static void instr_envloop(t_instr *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    x->x_envloopa = (int)atom_getfloatarg(0, argc, argv);
    x->x_envloopb = (int)atom_getfloatarg(1, argc, argv);
}
static void instr_fadeout(t_instr *x, t_float f) {
    x->x_fadeout = f < 0 ? 0 : (f > 65535 ? 65535 : f);
}
static void instr_master(t_instr *x, t_float f) { x->x_master = f; }
static void instr_id(t_instr *x, t_float f) { x->x_myid = (int)f; }

static void instr_clear(t_instr *x) {
    for (int i = 0; i < IN_MAXVOICES; i++) x->x_v[i].on = 0;
    for (int i = 0; i < IN_MAXCH; i++) x->x_chvoice[i] = -1;
}

static void instr_info(t_instr *x) {
    post("instr id %d: %d sample(s), env %s (%d pts, sus %d), fadeout %.0f, tick %.1f ms",
         x->x_myid, x->x_nsmp, x->x_envon ? "on" : "off", x->x_envn,
         x->x_envsus, x->x_fadeout, x->x_tickms);
    for (int i = 0; i < x->x_nsmp; i++)
        post("  smp %d: array '%s' %ld frames base %.0f loop %d [%ld..%ld]",
             i + 1, x->x_smp[i].sym ? x->x_smp[i].sym->s_name : "-",
             x->x_smp[i].frames, x->x_smp[i].base, x->x_smp[i].loop,
             x->x_smp[i].ls, x->x_smp[i].le);
    for (int i = 0; i < IN_MAXVOICES; i++)
        if (x->x_v[i].on)
            post("  voice %d: ch %d smp %d note %.1f gain %.2f%s",
                 i, x->x_v[i].ch, x->x_v[i].smp, x->x_v[i].note,
                 x->x_v[i].cur, x->x_v[i].released ? " released" : "");
}

/* ---------------------------------------------------------------- object */

static void instr_free(t_instr *x) {
    (void)x;   /* arrays are owned by pd */
}

static void *instr_new(t_symbol *s, int argc, t_atom *argv) {
    t_instr *x = (t_instr *)pd_new(instr_class);
    (void)s;
    memset(x->x_keymap, 0, sizeof(x->x_keymap));
    for (int i = 0; i < 96; i++) x->x_keymap[i] = 1;
    for (int i = 0; i < IN_MAXCH; i++) x->x_chvoice[i] = -1;
    x->x_nsmp = 0;
    x->x_envon = 0;
    x->x_envn = 0;
    x->x_envsus = 0;
    x->x_fadeout = 0;
    x->x_master = 1;
    x->x_tickms = 120;         /* 125 BPM at speed 6 */
    x->x_lasttick = 0;
    x->x_sr = 0;
    x->x_myid = (argc >= 1 && argv[0].a_type == A_FLOAT)
        ? (int)atom_getfloat(argv) : 0;
    outlet_new(&x->x_obj, &s_signal);
    return (void *)x;
}

void instr_setup(void) {
    instr_class = class_new(gensym("instr"), (t_newmethod)instr_new,
        (t_method)instr_free, sizeof(t_instr), 0, A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_dsp,
        gensym("dsp"), A_CANT, 0);
    class_addmethod(instr_class, (t_method)instr_note,
        gensym("note"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_off,
        gensym("off"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_pitch,
        gensym("pitch"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_vol,
        gensym("vol"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_pos,
        gensym("pos"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_smp,
        gensym("smp"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_keymap,
        gensym("keymap"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_key,
        gensym("key"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_envpt,
        gensym("envpt"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_envcfg,
        gensym("envcfg"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_env,
        gensym("env"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_envon,
        gensym("envon"), A_FLOAT, 0);
    class_addmethod(instr_class, (t_method)instr_envsus,
        gensym("envsus"), A_FLOAT, 0);
    class_addmethod(instr_class, (t_method)instr_envloop,
        gensym("envloop"), A_GIMME, 0);
    class_addmethod(instr_class, (t_method)instr_fadeout,
        gensym("fadeout"), A_FLOAT, 0);
    class_addmethod(instr_class, (t_method)instr_master,
        gensym("master"), A_FLOAT, 0);
    class_addmethod(instr_class, (t_method)instr_id,
        gensym("id"), A_FLOAT, 0);
    class_addmethod(instr_class, (t_method)instr_clear,
        gensym("clear"), 0);
    class_addmethod(instr_class, (t_method)instr_info,
        gensym("info"), 0);
    class_addbang(instr_class, (t_method)instr_tick);
    class_addfloat(instr_class, (t_method)instr_float);
}
