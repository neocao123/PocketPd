/* sn76489~ - TI SN76489 PSG (the Mega Drive's second sound chip), in C.
 *
 * The 4-channel tone/noise generator that sits next to the YM2612 in
 * every Mega Drive / Master System / Game Gear: three square-wave tone
 * channels and one LFSR noise channel, each with a 4-bit attenuation
 * register (2 dB/step, 15 = silent).  No FM, no envelopes - the register
 * bytes own everything, so (like ym2612~'s raw mode) this object plays
 * exactly what it is told to play.
 *
 * Chip semantics follow the hardware documentation
 * (smspower.org/Development/SN76489, Maxim, hardware measurements by
 * Charles MacDonald) as implemented by VGMPlay (chips/sn76489.c) and
 * Genesis Plus GX (core/sound/psg.c) - the integrated ASIC variant used
 * in the Mega Drive / SMS / Game Gear:
 *   - write protocol: a byte with bit 7 set latches the register index
 *     (bits 4-6) AND carries 4 data bits; a byte with bit 7 clear writes
 *     the latched register.  Tone periods are 10-bit (4-bit low nibble
 *     on the latch byte, 6-bit high part on the data byte); attenuation
 *     registers are 4-bit and write from either byte form.
 *   - tone: f = clock/(32*N) (N = the 10-bit register value, half-wave
 *     in clock/16 units; 0x0fe = 440.4 Hz at 3579545 Hz).  Register
 *     values below 6 hold the output constant (VGMPlay's PSG_CUTOFF =
 *     the measured SMS2 audible limit; N <= 1 is the documented DC trick
 *     PSG sample playback rides on) - rendered as +amp following the
 *     volume register, like VGMPlay.  A period write does NOT reset the
 *     running flip-flop.
 *   - noise: 15-bit LFSR (feedback into bit 15), shifts on the positive
 *     edge of its flip-flop; white mode (ctrl bit 2) feeds back
 *     bit0^bit3 ($0009, verified on SMS/GG/MD by Charles MacDonald),
 *     "periodic" mode recirculates bit0 (1/16 duty pattern).  Shift
 *     clock = 0x10/0x20/0x40 M-cycles for ctrl bits 0-1 = 0/1/2, or
 *     tone channel 2's flip-flop for 3.  Any noise-control write resets
 *     the LFSR to 0x8000 (output forced low).
 *   - power-on (Sega integrated versions): registers zeroed, volume
 *     registers = 15 (SILENT), latch = tone 2 attenuation, LFSR =
 *     0x8000 - inaudible until the driver writes a volume.
 *
 * Deviations from raw silicon (documented on purpose):
 *   - the noise channel outputs +-amp instead of the silicon's 0..amp
 *     (the same choice VGMPlay made; removes the V/2 DC pedestal).
 *   - output is mono: the Game Gear stereo mask (gg message / VGM 0x4F)
 *     only gates channels (audible if enabled on either side).
 *   - stuck tone channels (< 6) render their DC at full level with no
 *     leakage decay (the console's capacitors sag that offset towards
 *     zero; the 2 dB ladder and volume modulation carry the audio).
 *
 * Chip clock: 3579545 Hz (NTSC SMS/MD: master/15), override with
 * `clock <Hz>`.  Writes are applied at message rate and take effect at
 * the next audio block (same granularity as the YM path).
 *
 *   inlet (float):    0-255, one raw PSG byte write.  Also messages:
 *                       psg <0-255>   one raw byte (vgm~ outlet-1 vocab;
 *                                     connect the two objects directly)
 *                       gg <0-255>    game gear stereo mask (bits 0-3 =
 *                                     ch1-4 right, 4-7 = left)
 *                       gain <f>      output gain (default 1)
 *                       clock <f>     chip clock in Hz
 *                       reset         power-on state (and silent again)
 *                       list ...      symbol-first lists re-dispatched as
 *                                     the named message; a numeric list
 *                                     writes its first float as one byte
 *   outlets (signal): 0 = sum of the four channels (full scale 1.0 =
 *                       four channels at attenuation 0), 1..4 = tone
 *                       channels 1-3 and the noise channel alone
 *                       (0.17.82; each channel outlet is bounded by its
 *                       own amplitude, the sum needs no clip at gain 1
 *                       since SN_FULL is 1/4 per channel - gain > 1 is
 *                       the user's explicit overdrive and is not clipped
 *                       either)
 */

#include "m_pd.h"
#include <string.h>

#define SN_CLOCK    3579545.0    /* NTSC PSG input clock (master/15) */
#define SN_FULL     0.25f        /* per-channel amplitude at attenuation 0:
                                  * four full channels sum to 1.0 */
#define SN_CUTOFF   6            /* tone periods below this hold the output
                                  * constant (VGMPlay PSG_CUTOFF; $006 =
                                  * 18643 Hz is the measured audible limit
                                  * on a real SMS2) */

/* 4-bit attenuation -> level, 2 dB per step, 15 = off (hardware
 * resistor-ladder values, per the GPGX/SMS-Power table) */
static const float sn_vol[16] = {
    1.0f, 0.794328234f, 0.630957344f, 0.501187233f,
    0.398107170f, 0.316227766f, 0.251188643f, 0.199526231f,
    0.158489319f, 0.125892541f, 0.1f, 0.079432823f,
    0.063095734f, 0.050118723f, 0.039810717f, 0.0f
};

typedef struct _sn {
    t_object  obj;
    t_outlet *x_out[5];  /* 0 = channel sum, 1..4 = tone 1-3 / noise */
    t_sample *x_outv[5]; /* dsp: outlet signal vectors, refreshed by dsp()
                          * - passed to dsp_add as ONE pointer (the old
                          * 3-arg shape) so the arg count can never drift
                          * from the perform routine again */
    float     x_sr;
    double    x_clock;     /* PSG input clock Hz */
    double    x_minc;      /* M-cycles (16-clock units) per output sample */
    double    x_gain;      /* output gain multiplier */
    int       x_latch;     /* latched register index */
    int       x_regs[8];   /* raw shadows: 0/2/4 = 10-bit tone periods,
                              * 6 = noise control byte */
    int       x_vol[4];    /* 4-bit attenuation per channel */
    float     x_amp[4];    /* final per-channel amplitude */
    int       x_per[4];    /* flip-flop toggle period, M-cycles */
    double    x_macc[4];   /* M-cycle phase accumulator per channel */
    int       x_pol[4];    /* flip-flop polarity, +-1 */
    unsigned short x_lfsr; /* 15-bit noise shift register */
    int       x_gg;        /* game gear stereo mask */
} t_sn;

static t_class *sn_class;

/* ------------------------------------------------------------- control */

/* recompute one channel's amplitude (attenuation x gain x gg mask) */
static void sn_amp(t_sn *x, int ch) {
    float a = SN_FULL * sn_vol[x->x_vol[ch] & 15] * (float)x->x_gain;
    if (!((((x->x_gg >> ch) & 1)) | ((x->x_gg >> (ch + 4)) & 1)))
        a = 0.0f;               /* gg mask: off on both sides = silent */
    x->x_amp[ch] = a;
}

static void sn_amp_all(t_sn *x) {
    int i;
    for (i = 0; i < 4; i++) sn_amp(x, i);
}

/* one raw PSG byte, exactly the GPGX/VGMPlay write protocol */
static void sn_byte(t_sn *x, int b) {
    int index, p;
    b &= 0xff;
    if (b & 0x80) x->x_latch = index = (b >> 4) & 7;
    else index = x->x_latch;
    switch (index) {
    case 0: case 2: case 4: {   /* tone period: latch = low nibble,
                                 * data = high 6 bits */
        if (b & 0x80) p = (x->x_regs[index] & 0x3f0) | (b & 0x0f);
        else          p = (x->x_regs[index] & 0x00f) | ((b & 0x3f) << 4);
        x->x_regs[index] = p;
        x->x_per[index >> 1] = p;    /* toggle period in M-cycles; the
                                      * <6 cutoff is handled at render */
        if (index == 4 && (x->x_regs[6] & 3) == 3)
            x->x_per[3] = x->x_per[2];       /* noise rides tone 2 */
        break;
    }
    case 6:                     /* noise control: bit2 white, bits0-1 rate
                                 * (3 bits wide: latch-byte type/channel
                                 * bits above bit 3 never reach it) */
        x->x_regs[6] = b & 7;
        if ((b & 3) == 3) {
            x->x_per[3] = x->x_per[2];
            x->x_macc[3] = x->x_macc[2];     /* phase-sync with tone 2 */
        } else {
            x->x_per[3] = 0x10 << (b & 3);
        }
        x->x_lfsr = 0x8000;     /* any ctrl write forces the output low */
        break;
    default:                    /* 1/3/5/7: 4-bit attenuations */
        x->x_vol[index >> 1] = b & 0x0f;
        sn_amp(x, index >> 1);
        break;
    }
}

/* ----------------------------------------------------------- messages */

static void sn_float(t_sn *x, t_float f) {   /* bare float = one byte */
    sn_byte(x, (int)f);
}

static void sn_psg(t_sn *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) sn_byte(x, (int)atom_getfloat(argv));
}

static void sn_gg(t_sn *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        x->x_gg = (int)atom_getfloat(argv) & 0xff;
        sn_amp_all(x);
    }
}

static void sn_gain(t_sn *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        float g = atom_getfloat(argv);
        if (g < 0) g = 0;
        x->x_gain = g;
        sn_amp_all(x);
    }
}

static void sn_clock(t_sn *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        double c = atom_getfloat(argv);
        if (c < 1000.0) c = SN_CLOCK;
        x->x_clock = c;
        if (x->x_sr > 1.0f) x->x_minc = c / (16.0 * x->x_sr);
    }
}

/* back to the power-on state of the Sega integrated versions: registers
 * zeroed, volumes silent (attenuation 15), latch on tone 2 attenuation */
static void sn_reset(t_sn *x) {
    int i;
    x->x_latch = 3;
    for (i = 0; i < 8; i++) x->x_regs[i] = 0;
    for (i = 0; i < 4; i++) {
        x->x_vol[i] = 15;       /* silent until a driver writes volume */
        /* tones: 0 = held constant (cutoff); noise: power-on shift rate
         * 0 (0x10 M-cycles) per GPGX/VGMPlay reset - a 0 here would hang
         * the shift loop */
        x->x_per[i] = (i < 3) ? 0 : 0x10;
        x->x_macc[i] = 0.0;
        x->x_pol[i] = 1;
    }
    x->x_lfsr = 0x8000;
    sn_amp_all(x);
}

static void sn_anything(t_sn *x, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    if (!strcmp(name, "psg")) {
        sn_psg(x, s, argc, argv);
    } else if (!strcmp(name, "gg")) {
        sn_gg(x, s, argc, argv);
    } else if (!strcmp(name, "gain")) {
        sn_gain(x, s, argc, argv);
    } else if (!strcmp(name, "clock")) {
        sn_clock(x, s, argc, argv);
    } else if (!strcmp(name, "reset")) {
        sn_reset(x);
    } else if (!strcmp(name, "list")) {
        /* OSC bridges deliver "psg 221" as a list; re-dispatch
         * symbol-first lists, write the first float of a numeric list */
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            sn_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
        } else if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            sn_byte(x, (int)atom_getfloat(argv));
        }
    } else if (!strcmp(name, "ay") || !strcmp(name, "opl")
               || !strcmp(name, "nes")) {
        /* AY8910 writes (VGM 0xa0), OPL3 register writes (VGM
         * 0x5e/0x5f, for opl3~) and NES APU writes (VGM 0xbc, for nes~)
         * ride the same vgm~ outlet; those are different chips - a
         * direct vgm~->sn76489~ connection drops them, silently (error
         * spam would flood playback) */
    } else {
        pd_error(x, "sn76489~: unknown message %s", name);
    }
}

/* ---------------------------------------------------------------- dsp */

/* one output sample: every channel runs an M-cycle phase accumulator
 * (one M-cycle = 16 input clocks); crossing the toggle period flips the
 * flip-flop polarity, and the noise LFSR shifts on the positive edge.
 * Tone flip-flops are NOT reset by period writes - the accumulator just
 * keeps running against the new period, exactly like the silicon. */
static t_int *sn_perform(t_int *w) {
    t_sn *x = (t_sn *)(w[1]);
    t_sample **outv = (t_sample **)w[2];
    t_sample *out[5];
    int n = (int)(w[3]);
    int k, s;
    double minc = x->x_minc;
    for (k = 0; k < 5; k++) out[k] = outv[k];
    for (s = 0; s < n; s++) {
        float ch[4];
        float sum = 0.0f;
        int i;
        for (i = 0; i < 3; i++) {
            if (x->x_per[i] < SN_CUTOFF || x->x_per[i] < minc) {
                /* register 0-5: output held constant (VGMPlay's
                 * PSG_CUTOFF; below-Nyquist safety for odd clocks) */
                ch[i] = x->x_amp[i];
                sum += ch[i];
                continue;
            }
            x->x_macc[i] += minc;
            while (x->x_macc[i] >= x->x_per[i]) {
                x->x_macc[i] -= x->x_per[i];
                x->x_pol[i] = -x->x_pol[i];
            }
            ch[i] = x->x_pol[i] * x->x_amp[i];
            sum += ch[i];
        }
        if (x->x_per[3] >= 1) {     /* 0 = tone-2 alias before any tone-2
                                     * write: hold silent, never divide */
            x->x_macc[3] += minc;
            while (x->x_macc[3] >= x->x_per[3]) {
                x->x_macc[3] -= x->x_per[3];
                x->x_pol[3] = -x->x_pol[3];
                if (x->x_pol[3] > 0) {   /* LFSR shifts on the positive edge */
                    if (x->x_regs[6] & 4) {
                        /* white: feedback = bit0 ^ bit3 (GPGX mask 0x9) */
                        int fb = (x->x_lfsr & 1) ^ ((x->x_lfsr >> 3) & 1);
                        x->x_lfsr = (unsigned short)((x->x_lfsr >> 1)
                                                     | (fb << 15));
                    } else {
                        /* periodic: recirculate the output bit */
                        int b0 = x->x_lfsr & 1;
                        x->x_lfsr = (unsigned short)((x->x_lfsr >> 1)
                                                     | (b0 << 15));
                    }
                }
            }
        }
        ch[3] = (x->x_lfsr & 1) ? x->x_amp[3] : -x->x_amp[3];
        sum += ch[3];
        /* outlets 1..4 = the channels alone, 0 = their sum (no clip:
         * each channel is bounded by its own amplitude and the four
         * SN_FULL quarters sum to full scale at gain 1) */
        for (i = 0; i < 4; i++) out[i + 1][s] = ch[i];
        out[0][s] = sum;
    }
    return (w + 4);
}

static void sn_dsp(t_sn *x, t_signal **sp) {
    int k;
    x->x_sr = sp[0]->s_sr > 1.0f ? sp[0]->s_sr : 48000.0f;
    x->x_minc = x->x_clock / (16.0 * x->x_sr);
    for (k = 0; k < 5; k++) x->x_outv[k] = sp[k]->s_vec;
    dsp_add(sn_perform, 3, x, x->x_outv, sp[0]->s_n);
}

/* ------------------------------------------------------------ lifecycle */

static void *sn_new(void) {
    t_sn *x = (t_sn *)pd_new(sn_class);
    x->x_sr = 48000.0f;
    x->x_clock = SN_CLOCK;
    x->x_minc = SN_CLOCK / (16.0 * 48000.0);
    x->x_gain = 1.0;
    x->x_gg = 0xff;             /* no gg write: every channel audible */
    sn_reset(x);
    {   /* outlet 0 first = leftmost: the channel sum; 1..4 = channels */
        int i;
        for (i = 0; i < 5; i++)
            x->x_out[i] = outlet_new(&x->obj, &s_signal);
    }
    return (void *)x;
}

void sn76489_tilde_setup(void) {
    sn_class = class_new(gensym("sn76489~"), (t_newmethod)sn_new,
                         0, sizeof(t_sn), 0, A_NULL);
    class_addfloat(sn_class, sn_float);
    class_addanything(sn_class, sn_anything);
    class_addmethod(sn_class, (t_method)sn_dsp, gensym("dsp"), A_CANT, A_NULL);
}
