/* mpein - MIDI Polyphonic Expression (MPE v1.0) message parser.
 *
 * Universal and object-agnostic: eats a raw MIDI message stream and emits
 * semantic per-note events on one selector-ized outlet. Nothing in here
 * knows about any synth - the patch decides what the voices do.
 *
 * Inlet:
 *   list <status> <d1> [<d2>]   one complete channel message (the app's
 *                               [midi-raw] bridge receiver delivers these,
 *                               bytes already re-assembled by the bridge)
 *   clear                       forget the zone declaration and all voices
 *   bendrange <semitones>       pitch dimension span (default 48, MPE)
 *   status                      dump zone + voice table to the console
 *
 * Outlet (selector-ized lists, split with [route]):
 *   note <ch> <pitch> <vel> down|up
 *   pitch <ch> <semitones>      continuous, +/- bendrange
 *   slide <ch> <0..1>
 *   press <ch> <0..1>
 *   midi <ch> <num> <val>       passthrough of CC bytes that are not MPE
 *                               dimensions (sustain, mod wheel, ...)
 *   zone <master> <members>     device declaration via RPN 0,6 / 0,7
 *                               (master 1 = lower zone, 16 = upper)
 *
 * Semantics:
 *   - channels reported 1..16, no zone gating: devices that never declare
 *     a zone still work, per-channel dimension routing IS MPE
 *   - CC74 = slide, CC2 = pressure (MPE v1.0 defaults); channel and poly
 *     aftertouch also map to press
 *   - a note down is followed by slide/press snapshots of that channel so
 *     patches can seed per-voice dimensions without bookkeeping
 *   - program changes ride the midi passthrough; system/sysex bytes never
 *     reach here from [midi-raw] (the bridge only forwards complete
 *     channel messages)
 */

#include "m_pd.h"
#include <string.h>
#include <stdio.h>

#define MPE_CHANS 16
#define MPE_MASTER_LOWER 1
#define MPE_MASTER_UPPER 16
#define MPE_RPN_NULL 127

/* CC numbers of the MPE dimensions */
#define MPE_CC_SLIDE 74
#define MPE_CC_PRESS 2
#define MPE_CC_RPN_HI 101
#define MPE_CC_RPN_LO 100
#define MPE_CC_DATA 6

typedef struct {
    int down;       /* a note is sounding on this channel */
    int pitch;      /* its note number 0..127 */
    float slide;    /* 0..1 */
    float press;    /* 0..1 */
    int bend;       /* raw pitch bend, -8192..8191 */
} t_mpevoice;

typedef struct _mpe {
    t_object  obj;
    t_outlet *out;
    int       bendrange;   /* semitones, MPE default 48 */
    int       rpn_hi;      /* last CC101 value, -1 = none */
    int       rpn_lo;      /* last CC100 value, -1 = none */
    int       zone_down;   /* lower-zone member count, -1 = undeclared */
    int       zone_up;     /* upper-zone member count, -1 = undeclared */
    t_mpevoice v[MPE_CHANS];
} t_mpe;

static t_class *mpein_class;

static void mpe_out(t_mpe *x, const char *sel, int argc, t_atom *argv) {
    outlet_anything(x->out, gensym(sel), argc, argv);
}

static void mpe_out1(t_mpe *x, const char *sel, t_float a) {
    t_atom at[1];
    SETFLOAT(&at[0], a);
    mpe_out(x, sel, 1, at);
}

static void mpe_out2(t_mpe *x, const char *sel, t_float a, t_float b) {
    t_atom at[2];
    SETFLOAT(&at[0], a);
    SETFLOAT(&at[1], b);
    mpe_out(x, sel, 2, at);
}

/* ------------------------------------------------------------- clear */

static void mpe_clear(t_mpe *x) {
    memset(x->v, 0, sizeof(x->v));
    x->rpn_hi = -1;
    x->rpn_lo = -1;
    x->zone_down = -1;
    x->zone_up = -1;
}

/* --------------------------------------------------- RPN declaration */

/* MPE devices declare their zones through an RPN sequence:
 *   CC101=0, CC100=6|7, CC6=<member count>, (CC101=127, CC100=127 ends it)
 * The bridge never reorders, so loose tracking of the last CC101/CC100
 * plus the next CC6 value is sufficient in practice. */
static void mpe_rpn_cc(t_mpe *x, int num, int val) {
    if (num == MPE_CC_RPN_HI) {
        x->rpn_hi = val;
        if (x->rpn_hi == MPE_RPN_NULL && x->rpn_lo == MPE_RPN_NULL) {
            x->rpn_hi = -1;
            x->rpn_lo = -1;
        }
        return;
    }
    if (num == MPE_CC_RPN_LO) {
        x->rpn_lo = val;
        return;
    }
    if (num == MPE_CC_DATA && x->rpn_hi == 0) {
        if (x->rpn_lo == 6) {
            x->zone_down = val;
            mpe_out2(x, "zone", MPE_MASTER_LOWER, (t_float)val);
        } else if (x->rpn_lo == 7) {
            x->zone_up = val;
            mpe_out2(x, "zone", MPE_MASTER_UPPER, (t_float)val);
        }
    }
}

/* ------------------------------------------------------- message list */

static void mpe_list(t_mpe *x, t_symbol *s, int argc, t_atom *argv) {
    int status, type, ch, d1, d2;
    t_mpevoice *v;
    (void)s;
    if (argc < 2 || argv[0].a_type != A_FLOAT) return;
    status = (int)atom_getfloat(argv) & 0xFF;
    type = status & 0xF0;
    ch = (status & 0x0F) + 1;
    d1 = (int)atom_getfloat(argv + 1) & 0x7F;
    d2 = (argc >= 3 && argv[2].a_type == A_FLOAT)
             ? (int)atom_getfloat(argv + 2) & 0x7F : 0;
    if (status < 0x80) return;   /* stray data byte, not a message */
    v = &x->v[ch - 1];
    switch (type) {
    case 0x80:                     /* note off */
        if (v->down) {
            t_atom at[5];
            v->down = 0;
            SETSYMBOL(&at[0], gensym("up"));
            SETFLOAT(&at[1], (t_float)ch);
            SETFLOAT(&at[2], (t_float)v->pitch);
            SETFLOAT(&at[3], 0);
            SETFLOAT(&at[4], 0);
            mpe_out(x, "note", 5, at);
        }
        break;
    case 0x90:                     /* note on (vel 0 = off) */
        if (d2 == 0) {
            if (v->down) {
                t_atom at[5];
                v->down = 0;
                SETSYMBOL(&at[0], gensym("up"));
                SETFLOAT(&at[1], (t_float)ch);
                SETFLOAT(&at[2], (t_float)v->pitch);
                SETFLOAT(&at[3], 0);
                SETFLOAT(&at[4], 0);
                mpe_out(x, "note", 5, at);
            }
            break;
        }
        v->down = 1;
        v->pitch = d1;
        {
            t_atom at[5];
            SETSYMBOL(&at[0], gensym("down"));
            SETFLOAT(&at[1], (t_float)ch);
            SETFLOAT(&at[2], (t_float)d1);
            SETFLOAT(&at[3], (t_float)d2);
            SETFLOAT(&at[4], 0);
            mpe_out(x, "note", 5, at);
            /* seed the per-voice dimensions so patches need no
               bookkeeping: dimensions are the channel's last values */
            mpe_out2(x, "slide", (t_float)ch, v->slide);
            mpe_out2(x, "press", (t_float)ch, v->press);
        }
        break;
    case 0xA0:                     /* poly aftertouch -> pressure */
        v->press = (float)d2 / 127.f;
        mpe_out2(x, "press", (t_float)ch, v->press);
        break;
    case 0xB0:                     /* control change */
        if (d1 == MPE_CC_SLIDE) {
            v->slide = (float)d2 / 127.f;
            mpe_out2(x, "slide", (t_float)ch, v->slide);
        } else if (d1 == MPE_CC_PRESS) {
            v->press = (float)d2 / 127.f;
            mpe_out2(x, "press", (t_float)ch, v->press);
        } else if (d1 == MPE_CC_RPN_HI || d1 == MPE_CC_RPN_LO ||
                   d1 == MPE_CC_DATA) {
            mpe_rpn_cc(x, d1, d2);
        } else {
            /* every non-dimension CC rides the passthrough (sustain,
               mod wheel, volume ...) - the patch decides what they mean */
            mpe_out2(x, "midi", (t_float)ch, (t_float)d1 * 128.f + d2);
        }
        break;
    case 0xD0:                     /* channel aftertouch -> pressure */
        v->press = (float)d1 / 127.f;
        mpe_out2(x, "press", (t_float)ch, v->press);
        break;
    case 0xE0:                     /* pitch bend -> continuous pitch */
        v->bend = (d2 << 7 | d1) - 8192;
        {
            t_float semis = (t_float)v->bend / 8192.f * (t_float)x->bendrange;
            t_atom at[2];
            SETFLOAT(&at[0], (t_float)ch);
            SETFLOAT(&at[1], semis);
            mpe_out(x, "pitch", 2, at);
        }
        break;
    case 0xC0:                     /* program change -> passthrough */
        mpe_out2(x, "midi", (t_float)ch, (t_float)(128 * 128 + d1));
        break;
    default:                       /* system / undefined channel modes */
        return;
    }
}

/* --------------------------------------------------- control messages */

static void mpe_clear_msg(t_mpe *x) { mpe_clear(x); }

static void mpe_bendrange(t_mpe *x, t_float f) {
    int n = (int)f;
    if (n < 1) n = 1;
    if (n > 96) n = 96;
    x->bendrange = n;
}

static void mpe_status(t_mpe *x) {
    int i;
    post("mpein: bendrange %d semitones", x->bendrange);
    post("mpein: lower zone (master %d) members: %s",
         MPE_MASTER_LOWER, x->zone_down < 0 ? "undeclared" : "declared");
    if (x->zone_down >= 0) post("mpein:   members = %d", x->zone_down);
    post("mpein: upper zone (master %d) members: %s",
         MPE_MASTER_UPPER, x->zone_up < 0 ? "undeclared" : "declared");
    if (x->zone_up >= 0) post("mpein:   members = %d", x->zone_up);
    for (i = 0; i < MPE_CHANS; i++) {
        const t_mpevoice *v = &x->v[i];
        if (v->down || v->slide != 0.f || v->press != 0.f || v->bend != 0)
            post("mpein: ch%d %s pitch %d bend %d slide %.2f press %.2f",
                 i + 1, v->down ? "DOWN" : "up", v->pitch, v->bend,
                 (double)v->slide, (double)v->press);
    }
}

/* ------------------------------------------------------------- setup */

static void *mpein_new(void) {
    t_mpe *x = (t_mpe *)pd_new(mpein_class);
    x->out = outlet_new(&x->obj, &s_anything);
    x->bendrange = 48;
    x->rpn_hi = -1;
    x->rpn_lo = -1;
    x->zone_down = -1;
    x->zone_up = -1;
    memset(x->v, 0, sizeof(x->v));
    return (void *)x;
}

void mpein_setup(void) {
    mpein_class = class_new(gensym("mpein"), (t_newmethod)mpein_new, 0,
                            sizeof(t_mpe), 0, A_NULL);
    class_addmethod(mpein_class, (t_method)mpe_bendrange,
                    gensym("bendrange"), A_FLOAT, A_NULL);
    class_addmethod(mpein_class, (t_method)mpe_clear_msg,
                    gensym("clear"), A_NULL);
    class_addmethod(mpein_class, (t_method)mpe_status,
                    gensym("status"), A_NULL);
    class_addlist(mpein_class, mpe_list);
}
