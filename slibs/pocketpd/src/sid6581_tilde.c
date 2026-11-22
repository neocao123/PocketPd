/* sid6581~ - Commodore MOS 6581/8580 SID voice, in C.
 *
 * The C64's Sound Interface Device: three oscillator/envelope voices,
 * a multi-mode filter and the output stage.  Since 0.17.88 the DSP core
 * is the cycle-based reSID engine (github.com/daglem/reSID, GPLv2+
 * (c) 1998-2022 Dag Lem, vendored under resid/), clocked at the chip's
 * 985248 Hz PAL clock and resampled by reSID itself (SAMPLE_RESAMPLE)
 * to the pd rate - waveform DAC tables, filter op-amp distortion and
 * the external filter are all inside the engine, like the Nuked core
 * for ym2612~.
 *
 * Everything funnels into the chip's own registers: notes compute the
 * 16-bit frequency register (f = F * clock / 2^24), wave/adsr/filter
 * messages poke their register bytes, `reg <a> <d>` writes raw bytes
 * (the sid~ outlet-0 vocabulary - connect the two objects directly).
 * Register writes take effect at the next audio tick, same granularity
 * as ym2612~'s queued bus writes.
 *
 *   inlet (float):    midi pitch for voice 1 (key on; note 1 <f> 0 or
 *                     gate 1 0 releases); also messages:
 *                       note <voice 1-3> <midi> [vel]
 *                                            key on (vel 0 = release; the
 *                                            pitch lands in the voice's
 *                                            frequency register)
 *                       gate <voice> <0/1>   envelope gate bit
 *                       wave <voice> <off|pulse|saw|tri|noise|combo 0-15>
 *                                            wave select bits (trough of
 *                                            the control register)
 *                       pw <voice> <0-4095>  pulse width
 *                       sync <voice> <0/1>   oscillator sync
 *                       ring <voice> <0/1>   ring modulation
 *                       adsr <voice> <a> <d> <s> <r>  (each 0-15)
 *                       test <voice> <0/1>   oscillator test bit
 *                       ctrl <voice> <0-31>  raw control register byte
 *                       cutoff <0-2047>      filter cutoff
 *                       res <0-15>           filter resonance
 *                       filter <voice> <0/1> route a voice into the filter
 *                       mode <lp|bp|hp|off>  (additive words or 0-7)
 *                       vol <0-15>           chip volume register
 *                       reg <0-0x18> <0-255> one raw register write
 *                       clock <Hz>           chip clock (985248 PAL /
 *                                            1022730 NTSC)
 *                       model <6581|8580>    chip model (default 6581)
 *                       gain <f>             output gain (default 1)
 *                       reset                all registers to zero
 *                       list ...             symbol-first lists
 *                                            re-dispatched as the named
 *                                            message; numeric lists are
 *                                            ignored
 *   outlets (signal): 0 = the chip's own AUDIO OUT (filter + external
 *                       filter applied - the real chip mix), 1..3 =
 *                       voices 1..3 alone (amplitude-modulated waveform
 *                       output, DC-blocked through a one-pole 16 Hz
 *                       highpass per voice - the chip's output
 *                       capacitor treatment, applied per channel; the
 *                       raw silicon value rides a DC pedestal).
 *                       NO output clip: one full voice lands at ~1.0,
 *                       the three-voice sum can reach ~3 (6581 combined
 *                       waveforms overshoot further) - the patch owns
 *                       the headroom, like on ym2612~/sn76489~.
 */

#include "m_pd.h"
#include <math.h>
#include <string.h>
#include "sidcore.h"

#define SID_CLOCK_PAL 985248.0

typedef struct _sid {
    t_object  obj;
    t_outlet *x_out[4];  /* 0 = chip mix (AUDIO OUT), 1..3 = voices */
    t_sample *x_outv[4]; /* dsp: outlet signal vectors, refreshed by dsp()
                          * - passed to dsp_add as ONE pointer (the old
                          * 3-arg shape) so the arg count can never drift
                          * from the perform routine again */
    float     x_sr;
    double    x_clock;   /* chip clock Hz */
    float     x_gain;
    int       x_model;   /* 0 = 6581, 1 = 8580 */
    unsigned char x_regs[0x19]; /* register shadow: reads of write-only
                                 * registers must NOT go through reSID's
                                 * bus-value emulation (it ages to zero
                                 * after a few ms - a read-modify-write
                                 * hours later would clear the wave
                                 * bits along with the gate) */
    void     *x_core;    /* reSID engine behind the C ABI shim */
} t_sid;

static t_class *sid_class;

/* every register write goes through here: shadow + engine */
static void sid_wr(t_sid *x, int a, int d) {
    if (a < 0 || a > 0x18) return;
    x->x_regs[a] = (unsigned char)(d & 0xff);
    sidcore_write(x->x_core, a, d & 0xff);
}

static int sid_rd(t_sid *x, int a) {
    if (a < 0 || a > 0x18) return 0;
    return x->x_regs[a];
}

/* ------------------------------------------------------- register map */

/* the frequency register value for a midi note at the current clock:
 * f = F * clock / 2^24 */
static int sid_note_f(t_sid *x, float midi) {
    double f = 8.1757989156 * pow(2.0, midi / 12.0);
    double F = f * 16777216.0 / x->x_clock;
    int v = (int)(F + 0.5);
    if (v < 0) v = 0;
    if (v > 0xffff) v = 0xffff;
    return v;
}

/* ------------------------------------------------------------- control */

static void sid_gain(t_sid *x, float g) {
    if (g < 0) g = 0;
    x->x_gain = g;
}

static void sid_clock_msg(t_sid *x, double hz) {
    if (hz < 250000.0) hz = SID_CLOCK_PAL;
    x->x_clock = hz;
    sidcore_clock(x->x_core, x->x_clock);
}

static void sid_model_msg(t_sid *x, int m) {
    if (m != 0 && m != 1) m = 0;
    x->x_model = m;
    sidcore_model(x->x_core, m);
}

static void sid_reset(t_sid *x) {
    int r;
    for (r = 0x18; r >= 0; r--)     /* volume last: silence, not blast */
        sid_wr(x, r, 0);
}

/* ----------------------------------------------------------- messages */

/* voice index 1-3 -> 0-2, or -1 on garbage */
static int sid_voice(t_sid *x, t_atom *a) {
    int v = (int)atom_getfloat(a) - 1;
    (void)x;
    if (v < 0 || v > 2) return -1;
    return v;
}

static void sid_note(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v;
    float p;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: note expects <voice 1-3> <midi> [vel]");
        return;
    }
    v = sid_voice(x, &argv[0]);
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    p = atom_getfloat(&argv[1]);
    if (argc >= 3 && argv[2].a_type == A_FLOAT &&
        atom_getfloat(&argv[2]) <= 0) {
        /* note-off: release through the chip's own envelope */
        sid_wr(x, v * 7 + 4,
                      sid_rd(x, v * 7 + 4) & 0xFE);
        return;
    }
    {
        int f = sid_note_f(x, p);
        sid_wr(x, v * 7 + 0, f & 0xff);
        sid_wr(x, v * 7 + 1, (f >> 8) & 0xff);
        /* a note is a key-on: retrigger by clearing the gate first, as
         * on silicon (gate 1->0->1 within one frame still resets the
         * envelope's rate counter) */
        sid_wr(x, v * 7 + 4,
                      sid_rd(x, v * 7 + 4) & 0xFE);
        sid_wr(x, v * 7 + 4,
                      sid_rd(x, v * 7 + 4) | 0x01);
    }
}

static void sid_gate(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v, g;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: gate expects <voice 1-3> <0/1>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    g = atom_getfloat(&argv[1]) != 0;
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    sid_wr(x, v * 7 + 4,
                  (sid_rd(x, v * 7 + 4) & 0xFE) | g);
}

static void sid_wave(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v, w, ctl;
    if (argc < 2 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: wave expects <voice 1-3> "
                    "<off|pulse|saw|tri|noise|0-15>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    if (argc >= 2 && argv[1].a_type == A_SYMBOL) {
        const char *n = atom_getsymbol(&argv[1])->s_name;
        if (!strcmp(n, "off")) w = 0;
        else if (!strcmp(n, "pulse")) w = 1;
        else if (!strcmp(n, "saw")) w = 2;
        else if (!strcmp(n, "tri")) w = 4;
        else if (!strcmp(n, "noise")) w = 8;
        else {
            pd_error(x, "sid6581~: unknown wave %s", n);
            return;
        }
    } else {
        w = (int)atom_getfloat(&argv[1]) & 15;
    }
    ctl = sid_rd(x, v * 7 + 4) & 0x01;   /* keep the gate */
    ctl |= w << 4;
    sid_wr(x, v * 7 + 4, ctl);
}

static void sid_pw(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v, w;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: pw expects <voice 1-3> <0-4095>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    w = (int)atom_getfloat(&argv[1]);
    if (w < 0) w = 0;
    if (w > 4095) w = 4095;
    sid_wr(x, v * 7 + 2, w & 0xff);
    sid_wr(x, v * 7 + 3, (w >> 8) & 0x0f);
}

static void sid_bit(t_sid *x, t_symbol *s, int argc, t_atom *argv,
                    int bit) {
    (void)s;
    int v, g;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: sync/ring expect <voice 1-3> <0/1>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    g = atom_getfloat(&argv[1]) != 0;
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    sid_wr(x, v * 7 + 4,
                  (sid_rd(x, v * 7 + 4) & ~bit) | (g ? bit : 0));
}

static void sid_adsr(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v, a, d, su, r;
    if (argc < 5 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: adsr expects <voice 1-3> <a> <d> <s> <r> "
                    "(each 0-15)");
        return;
    }
    v = sid_voice(x, &argv[0]);
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    a = (int)atom_getfloat(&argv[1]) & 15;
    d = (int)atom_getfloat(&argv[2]) & 15;
    su = (int)atom_getfloat(&argv[3]) & 15;
    r = (int)atom_getfloat(&argv[4]) & 15;
    sid_wr(x, v * 7 + 5, (a << 4) | d);
    sid_wr(x, v * 7 + 6, (su << 4) | r);
}

static void sid_ctrl(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: ctrl expects <voice 1-3> <0-31>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    sid_wr(x, v * 7 + 4,
                  (int)atom_getfloat(&argv[1]) & 31);
}

static void sid_cutoff(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int w;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: cutoff expects <0-2047>");
        return;
    }
    w = (int)atom_getfloat(&argv[0]);
    if (w < 0) w = 0;
    if (w > 2047) w = 2047;
    sid_wr(x, 0x15, w & 0xff);
    sid_wr(x, 0x16, (w >> 8) & 0x07);
}

static void sid_res(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int q;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: res expects <0-15>");
        return;
    }
    q = (int)atom_getfloat(&argv[0]) & 15;
    sid_wr(x, 0x17,
                  (sid_rd(x, 0x17) & 0x0f) | (q << 4));
}

static void sid_filter(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v, on;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: filter expects <voice 1-3> <0/1>");
        return;
    }
    v = sid_voice(x, &argv[0]);
    on = atom_getfloat(&argv[1]) != 0;
    if (v < 0) {
        pd_error(x, "sid6581~: voice index must be 1-3");
        return;
    }
    sid_wr(x, 0x17,
                  (sid_rd(x, 0x17) & ~(1 << v)) | (on << v));
}

static void sid_mode(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int m = 0, i;
    if (argc < 1) {
        pd_error(x, "sid6581~: mode expects <lp|bp|hp|off> or <0-7>");
        return;
    }
    if (argv[0].a_type == A_SYMBOL) {
        for (i = 0; i < argc; i++) {
            const char *n = atom_getsymbol(&argv[i])->s_name;
            if (!strcmp(n, "lp")) m |= 0x10;
            else if (!strcmp(n, "bp")) m |= 0x20;
            else if (!strcmp(n, "hp")) m |= 0x40;
            else if (!strcmp(n, "off")) m = 0;
            else {
                pd_error(x, "sid6581~: unknown filter mode %s", n);
                return;
            }
        }
    } else {
        m = ((int)atom_getfloat(&argv[0]) & 7) << 4;
    }
    sid_wr(x, 0x18,
                  (sid_rd(x, 0x18) & 0x0f) | m);
}

static void sid_vol(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int v;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "sid6581~: vol expects <0-15>");
        return;
    }
    v = (int)atom_getfloat(&argv[0]) & 15;
    sid_wr(x, 0x18,
                  (sid_rd(x, 0x18) & 0xf0) | v);
}

/* reg <addr> <data>: one raw register write, verbatim (the sid~
 * outlet-0 vocabulary - a sid~ plugs straight in). */
static void sid_reg(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    int a, d;
    if (argc < 2) return;
    a = (int)atom_getfloat(&argv[0]);
    d = (int)atom_getfloat(&argv[1]);
    if (a < 0 || a > 0x18) return;
    sid_wr(x, a, d & 0xff);
}

static void sid_anything(t_sid *x, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    if (!strcmp(name, "note")) {
        sid_note(x, s, argc, argv);
    } else if (!strcmp(name, "gate")) {
        sid_gate(x, s, argc, argv);
    } else if (!strcmp(name, "wave")) {
        sid_wave(x, s, argc, argv);
    } else if (!strcmp(name, "pw")) {
        sid_pw(x, s, argc, argv);
    } else if (!strcmp(name, "sync")) {
        sid_bit(x, s, argc, argv, 0x02);
    } else if (!strcmp(name, "ring")) {
        sid_bit(x, s, argc, argv, 0x04);
    } else if (!strcmp(name, "test")) {
        sid_bit(x, s, argc, argv, 0x08);
    } else if (!strcmp(name, "ctrl")) {
        sid_ctrl(x, s, argc, argv);
    } else if (!strcmp(name, "adsr")) {
        sid_adsr(x, s, argc, argv);
    } else if (!strcmp(name, "cutoff")) {
        sid_cutoff(x, s, argc, argv);
    } else if (!strcmp(name, "res")) {
        sid_res(x, s, argc, argv);
    } else if (!strcmp(name, "filter")) {
        sid_filter(x, s, argc, argv);
    } else if (!strcmp(name, "mode")) {
        sid_mode(x, s, argc, argv);
    } else if (!strcmp(name, "vol")) {
        sid_vol(x, s, argc, argv);
    } else if (!strcmp(name, "reg")) {
        sid_reg(x, s, argc, argv);
    } else if (!strcmp(name, "clock")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            sid_clock_msg(x, atom_getfloat(argv));
    } else if (!strcmp(name, "model")) {
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            const char *n = atom_getsymbol(argv)->s_name;
            sid_model_msg(x, !strcmp(n, "8580") ? 1 : 0);
        } else if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            sid_model_msg(x, (int)atom_getfloat(argv));
        }
    } else if (!strcmp(name, "gain")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            sid_gain(x, atom_getfloat(argv));
    } else if (!strcmp(name, "reset")) {
        sid_reset(x);
    } else if (!strcmp(name, "list")) {
        /* OSC bridges deliver "note 1 60" ... as lists; re-dispatch
         * symbol-first lists as the named message */
        if (argc >= 2 && argv[0].a_type == A_SYMBOL) {
            sid_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
        }
    } else {
        pd_error(x, "sid6581~: unknown message %s", name);
    }
}

/* ---------------------------------------------------------------- dsp */

static t_int *sid_perform(t_int *w) {
    t_sid *x = (t_sid *)(w[1]);
    t_sample **outv = (t_sample **)w[2];
    t_sample *out[4];
    int n = (int)(w[3]);
    int k, s;
    /* block render: the whole block goes to reSID in ONE clock() call
     * (the upstream-documented pattern; per-sample calls corrupt its
     * fixpoint phase accumulator = irregular duplicated samples =
     * buzz).  64-sample pd blocks; hard cap for safety. */
    float mix[1024], v[3][1024];
    float g = x->x_gain;
    for (k = 0; k < 4; k++) out[k] = outv[k];
    if (n > 1024) n = 1024;
    sidcore_block(x->x_core, mix, v[0], v[1], v[2], n);
    for (s = 0; s < n; s++) {
        out[0][s] = mix[s] * g;
        for (k = 0; k < 3; k++)
            out[k + 1][s] = v[k][s] * g;
    }
    return (w + 4);
}

static void sid_dsp(t_sid *x, t_signal **sp) {
    int k;
    x->x_sr = sp[0]->s_sr > 1.0f ? sp[0]->s_sr : 48000.0f;
    sidcore_sampling(x->x_core, x->x_sr);
    for (k = 0; k < 4; k++) x->x_outv[k] = sp[k]->s_vec;
    dsp_add(sid_perform, 3, x, x->x_outv, sp[0]->s_n);
}

/* ------------------------------------------------------------ lifecycle */

static void sid_free(t_sid *x) {
    if (x->x_core) sidcore_free(x->x_core);
}

static void *sid_new(void) {
    t_sid *x = (t_sid *)pd_new(sid_class);
    x->x_sr = 48000.0f;
    x->x_clock = SID_CLOCK_PAL;
    x->x_gain = 1.0f;
    x->x_model = 0;
    x->x_core = sidcore_new();
    {   /* outlet 0 first = leftmost: the chip mix; 1..3 = voices 1..3 */
        int i;
        for (i = 0; i < 4; i++)
            x->x_out[i] = outlet_new(&x->obj, &s_signal);
    }
    return (void *)x;
}

static void sid_float(t_sid *x, t_float f) {  /* voice 1 pitch, key on */
    t_atom a[2];
    SETFLOAT(a, 1);
    SETFLOAT(a + 1, f);
    sid_note(x, gensym("note"), 2, a);
}

void sid6581_tilde_setup(void) {
    sid_class = class_new(gensym("sid6581~"), (t_newmethod)sid_new,
                          (t_method)sid_free, sizeof(t_sid), 0, A_NULL);
    class_addfloat(sid_class, sid_float);
    class_addanything(sid_class, sid_anything);
    class_addmethod(sid_class, (t_method)sid_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
}
