/* sid~ - PSID (.sid) player that drives a sid6581~ instance.
 *
 * Loads a PSID file (v1-v4; RSID and MUS data are rejected - they need a
 * full C64 environment), runs the tune's own 6502 code through the
 * in-house replay engine (sidreplay.c: memory map with VIC/CIA traps,
 * init/play scheduled at the tune's own rate - video frame for VBI
 * tunes, the tune's CIA timer latch for CIA tunes), and forwards every
 * SID register write to a sid6581~:
 *
 *   outlet 0     messages: `reg <addr> <data>` for every $D400-$D418
 *                write the tune makes; on load also `clock <Hz>` and
 *                `model <6581|8580>` so the connected chip configures
 *                itself (PAL/NTSC and the file's SID model).
 *
 * The tune controls its own tempo and register state - sid~ never
 * interprets the music, it just runs the player code (like vgm~, which
 * paces a LOG; sid~ runs the original PLAYER).
 *
 * Messages (all through the left inlet):
 *   load <file.sid>   open (pd search path + patch dir), stop, emit
 *                     clock/model, report name/author to the console
 *   play              start (or resume after pause)
 *   pause             halt the replay (registers keep their state)
 *   stop              halt + rewind (init runs again on next play)
 *   subtune <n>       1-based; (re)loads with that subtune selected
 *   info              post version/songs/addresses/rate to the console
 *
 * Deviations from a real C64, documented on purpose (the PSID basic
 * replay contract): no interrupt handlers are run - tunes that play
 * through their own IRQs (RSID, play-address 0) cannot work and are
 * rejected; ROM pages hold RTS stubs instead of the C64 ROMs; the CIA
 * countdown is not run (tunes polling the latch still terminate); the
 * VIC raster line advances with real time so raster-wait loops finish.
 */

#include "m_pd.h"
#include <stdio.h>
#include <string.h>
#include "sidreplay.h"

/* file lookup shared with ym2612_tilde.c (search path, no canvas deref) */
int ympp_findfile(const char *name, char *full, unsigned int n);

typedef struct _sidp {
    t_object    obj;
    t_outlet   *x_out;          /* reg/clock/model messages */
    sidreplay   x_sr;           /* the replay engine (owns the 6502) */
    unsigned char *x_buf;       /* whole file (kept for subtune reload) */
    unsigned long x_len;
    char        x_name[MAXPDSTRING];   /* current file name */
    double      x_dsr;                 /* pd sample rate (from dsp) */
    int         x_wasplaying;  /* pause/resume state */
    int         x_note;        /* one-shot console notes */
} t_sidp;

static t_class *sidp_class;

/* ------------------------------------------------------------- hooks */

/* runs on the audio thread inside sidreplay_advance - outlet_anything
 * from perform is the same pattern vgm~ uses for its psg outlet */
static void sidp_chipw(void *ud, int reg, int val) {
    t_sidp *x = (t_sidp *)ud;
    t_atom a[2];
    SETFLOAT(a, (t_float)reg);
    SETFLOAT(a + 1, (t_float)(val & 0xff));
    outlet_anything(x->x_out, gensym("reg"), 2, a);
}

static int sidp_chipr(void *ud, int reg) {
    t_sidp *x = (t_sidp *)ud;
    (void)x;
    /* $D419-$D41E reads (paddles, OSC3, ENV3): the engine's chip side is
     * the connected sid6581~'s reSID instance, which this object cannot
     * reach - OSC3-based random numbers return a cheap hash instead of
     * hanging or returning constants */
    (void)reg;
    return (int)((reg * 2654435761u) >> 24) ^ 0x37;
}

static void sidp_note(void *ud, const char *msg) {
    t_sidp *x = (t_sidp *)ud;
    post("%s", msg);
}

/* ---------------------------------------------------------- file load */

static void sidp_emit_setup(t_sidp *x) {
    t_atom a[1];
    char modelname[8];
    /* the connected sid6581~ configures itself from the file header
     * BEFORE any register write arrives */
    SETFLOAT(a, (t_float)x->x_sr.clock_hz);
    outlet_anything(x->x_out, gensym("clock"), 1, a);
    strcpy(modelname, x->x_sr.model ? "8580" : "6581");
    SETSYMBOL(a, gensym(modelname));
    outlet_anything(x->x_out, gensym("model"), 1, a);
}

static void sidp_load(t_sidp *x, t_symbol *s) {
    char full[MAXPDSTRING];
    FILE *f;
    unsigned char *buf;
    unsigned long cap = 0, got = 0, rn;
    int rc;
    if (!s || !s->s_name || !s->s_name[0]) {
        pd_error(x, "sid~: load expects a file name");
        return;
    }
    if (!ympp_findfile(s->s_name, full, MAXPDSTRING)) {
        pd_error(x, "sid~: '%s' not found (pd search path)", s->s_name);
        return;
    }
    f = fopen(full, "rb");
    if (!f) {
        pd_error(x, "sid~: cannot open '%s'", full);
        return;
    }
    /* slurp whole file */
    for (;;) {
        unsigned char *nb;
        cap = cap ? cap * 2 : 65536;
        nb = (unsigned char *)realloc(x->x_buf, cap);
        if (!nb) break;
        x->x_buf = nb;
        rn = (unsigned long)fread(x->x_buf + got, 1, cap - got, f);
        got += rn;
        if (!rn) break;
    }
    fclose(f);
    if (got < 0x7E) {
        pd_error(x, "sid~: '%s' is not a SID file", s->s_name);
        return;
    }
    buf = x->x_buf;
    x->x_len = got;
    rc = sidreplay_load(&x->x_sr, buf, got, 0);
    if (rc) {
        pd_error(x, "sid~: '%s': %s", s->s_name,
                 rc == -1 ? "not a PSID file (RSID needs a full C64 "
                            "environment)" :
                 rc == -2 ? "truncated" :
                 rc == -3 ? "no play address (IRQ-only tune; the basic "
                            "replay cannot run it)" :
                 rc == -4 ? "MUS data (Compute! player) unsupported" :
                 rc == -5 ? "does not fit in C64 memory" :
                 rc == -6 ? "PSID version > 4" : "bad file");
        x->x_sr.loaded = 0;
        return;
    }
    snprintf(x->x_name, sizeof(x->x_name), "%s", s->s_name);
    x->x_note = 0;
    sidp_emit_setup(x);
    post("sid~: '%s': \"%s\" by %s (%s), %d tune%s, %s clock, %s, "
         "play at %.2f Hz", s->s_name, x->x_sr.name, x->x_sr.author,
         x->x_sr.released, x->x_sr.songs,
         x->x_sr.songs > 1 ? "s" : "",
         x->x_sr.clock_hz == SREP_NTSC_CLOCK ? "NTSC" : "PAL",
         x->x_sr.model ? "MOS8580" : "MOS6581",
         (double)x->x_sr.clock_hz / x->x_sr.interval);
}

static void sidp_play(t_sidp *x) {
    if (!x->x_sr.loaded) {
        pd_error(x, "sid~: nothing loaded");
        return;
    }
    sidreplay_play(&x->x_sr);
}

static void sidp_pause(t_sidp *x) {
    sidreplay_pause(&x->x_sr);
}

static void sidp_stop(t_sidp *x) {
    sidreplay_stop(&x->x_sr);
}

static void sidp_subtune(t_sidp *x, t_float f) {
    int n = (int)f, was = x->x_sr.playing;
    if (!x->x_buf || !x->x_sr.loaded) {
        pd_error(x, "sid~: nothing loaded");
        return;
    }
    if (n < 1 || n > x->x_sr.songs) {
        pd_error(x, "sid~: subtune must be 1-%d", x->x_sr.songs);
        return;
    }
    if (sidreplay_load(&x->x_sr, x->x_buf, x->x_len, n)) {
        pd_error(x, "sid~: subtune reload failed");
        return;
    }
    if (was) sidreplay_play(&x->x_sr);
    post("sid~: subtune %d", n);
}

static void sidp_info(t_sidp *x) {
    if (!x->x_sr.loaded) {
        post("sid~: nothing loaded");
        return;
    }
    post("sid~: '%s' v%d, %d tune%s (current %d), init $%04X play $%04X, "
         "%s clock, %s, %s speed, play %.2f Hz, %s",
         x->x_name, x->x_sr.version, x->x_sr.songs,
         x->x_sr.songs > 1 ? "s" : "", x->x_sr.subtune,
         x->x_sr.init_addr, x->x_sr.play_addr,
         x->x_sr.clock_hz == SREP_NTSC_CLOCK ? "NTSC" : "PAL",
         x->x_sr.model ? "MOS8580" : "MOS6581",
         x->x_sr.speed_bit ? "CIA" : "VBI",
         (double)x->x_sr.clock_hz / x->x_sr.interval,
         x->x_sr.playing ? "playing" :
         (x->x_sr.paused ? "paused" : "stopped"));
}

static void sidp_anything(t_sidp *x, t_symbol *s, int argc, t_atom *argv) {
    const char *n = s->s_name;
    if (!strcmp(n, "load")) {
        sidp_load(x, argc >= 1 ? atom_getsymbol(argv) : 0);
    } else if (!strcmp(n, "play")) {
        sidp_play(x);
    } else if (!strcmp(n, "pause")) {
        sidp_pause(x);
    } else if (!strcmp(n, "stop")) {
        sidp_stop(x);
    } else if (!strcmp(n, "subtune")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            sidp_subtune(x, atom_getfloat(argv));
    } else if (!strcmp(n, "info")) {
        sidp_info(x);
    } else if (!strcmp(n, "list")) {
        if (argc >= 2 && argv[0].a_type == A_SYMBOL)
            sidp_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
    } else {
        pd_error(x, "sid~: unknown message %s", n);
    }
}

/* ------------------------------------------------------------- dsp */

static t_int *sidp_perform(t_int *w) {
    t_sidp *x = (t_sidp *)(w[1]);
    int n = (int)(w[2]);
    if (x->x_sr.playing && !x->x_sr.dead)
        /* n pd samples = n * clock_hz / sr chip cycles of tune time */
        sidreplay_advance(&x->x_sr,
                          (double)n * x->x_sr.clock_hz / x->x_dsr);
    return w + 3;
}

static void sidp_dsp(t_sidp *x, t_signal **sp) {
    /* sid~ has NO signal ports: pd's ugen_doit still hands the dsp
     * method a t_signal** allocated with getbytes(0), so sp[0] is an
     * out-of-bounds read (GM800 SIGSEGV, fault 0x10, inside the first
     * dsp compile - 0.17.88 shipped exactly that).  The block size and
     * rate come from the audio system instead. */
    (void)sp;
    x->x_dsr = sys_getsr();
    if (x->x_dsr < 1.0) x->x_dsr = 48000.0;
    dsp_add(sidp_perform, 2, x, sys_getblksize());
}

/* ------------------------------------------------------------ setup */

static void *sidp_new(void) {
    t_sidp *x = (t_sidp *)pd_new(sidp_class);
    sidreplay_hooks hk;
    hk.chip_write = sidp_chipw;
    hk.chip_read = sidp_chipr;
    hk.chip_ud = x;
    hk.log = sidp_note;
    hk.log_ud = x;
    sidreplay_init(&x->x_sr, &hk);
    x->x_buf = 0;
    x->x_len = 0;
    x->x_name[0] = 0;
    x->x_note = 0;
    x->x_dsr = 48000.0;
    x->x_out = outlet_new(&x->obj, &s_anything);
    return (void *)x;
}

static void sidp_free(t_sidp *x) {
    if (x->x_buf) free(x->x_buf);
}

void sid_tilde_setup(void) {
    sidp_class = class_new(gensym("sid~"), (t_newmethod)sidp_new,
                           (t_method)sidp_free, sizeof(t_sidp), 0, A_NULL);
    class_addanything(sidp_class, sidp_anything);
    class_addmethod(sidp_class, (t_method)sidp_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
}
