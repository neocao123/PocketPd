/* opl3~ - Yamaha YMF262 (OPL3, the Sound Blaster 16 FM chip), in C.
 *
 * The DSP core is the cycle-accurate Nuked-OPL3 emulator (nukeykt, v1.8,
 * LGPL-2.1 (c) Nuke.YKT, vendored next to this file as opl3.c/opl3.h -
 * the same core VGMPlay uses for OPL3 logs).  Two layers, exactly like
 * ym2612~:
 *
 *   RAW REGISTER LAYER  `reg <reg> <data>` (alias `opl` = vgm~'s stream
 *      selector), reg 0..0x1ff (bank 1 = 0x100|addr, verbatim VGM port
 *      0/1 bytes).  Writes are applied INSTANTLY in the message handler
 *      (OPL registers have no bus protocol to pace - OPL3_WriteReg is an
 *      atomic state change, and libpd runs messages and perform on one
 *      thread, so ordering is guaranteed).  A raw write implies raw mode:
 *      gate and velocity pin open, the log's own registers own the level.
 *
 *   VOICE LAYER        one voice per chip channel (18 melodic channels;
 *      bare spellings = ch1; a message is a command stream - every word
 *      is a command consuming its own args, so `gm 1 note 60 100` runs
 *      both in one message, comma forms still fine; `chN` at the start
 *      (or again mid-message) scopes every following voice command of
 *      THAT message to channel N - it never keys off another channel,
 *      never moves the sound over, and the gate inlet stays ch1):
 *        glide <ms>           portamento time (0 = off, default); `note`
 *                             then sweeps from the previous pitch
 *        slide <midi> [ms] [vel]
 *                             sweep to the pitch WITHOUT a key event (ms
 *                             omitted = the glide time; vel 0-127 = the
 *                             channel's level, gate untouched, omitted
 *                             = the level stays)
 *        note <midi> [vel]    key on; vel 0 = key off (release); velocity
 *                             scales the channel's output (wrapper-side
 *                             per-channel peak, exactly ym2612~'s model)
 *        gate <0/1>           key hold; inlet 2 float = gate of ch1
 *                             (per-channel release = `chN gate 0`)
 *        alg <0-1>            2-op connection: 0 = FM (serial), 1 = AM
 *                             (modulator parallel).  On a 4-op pair
 *                             (0x104): bits 0/1 = connection of the
 *                             addressed / pair channel (alg 0-3)
 *        fb <0-7>             operator-1 feedback (C0 bits 1-3)
 *        pan <0-3>            C0 output bits: 0 off, 1 left, 2 right,
 *                             3 both (default)
 *        op <1-2> <param> <v> per-operator: mul tl ksl ar dr sl rr egt
 *                             ksr am vib ws  (OPL ranges: tl 0-63 =
 *                             -0.75 dB/step, rates 0-15, ws 0-7)
 *        gm <1-128>           GM Level 1 preset from the current bank
 *                             (compiled Mobilnik-v2 default or a runtime
 *                             .wopl, see 'bank'); raw register dump like
 *                             ym2612~'s gm
 *        bank <file.wopl|default>   swap the whole GM table (WOPL v1/v2/
 *                             v3, melodic bank 0; resolves via the pd
 *                             search path)
 *        mono <0/1>           1 = ignore the chip's C0 pan masks, every
 *                             channel into BOTH sum outlets (wiring-
 *                             proof mono monitoring; default 0 = the
 *                             chip's own stereo placement)
 *
 * Outlets (all signal): 0 = LEFT sum, 1 = RIGHT sum (the chip's own
 * stereo mix: C0 pan bits, active with OPL3 mode on), 2..19 = channels
 * 1..18 alone.  The per-channel outlets are the channels' own operator
 * accumulators (the exact sum the core mixes), pan-INDEPENDENT - a 4-op
 * pair's second channel reads 0 there (its operators sound in the first
 * channel's outlet, as on silicon).
 *
 * Integration details:
 *   - the chip runs at its native 49716 Hz output rate (clock
 *     14318181/288, hardcoded by the core); the wrapper runs the core's
 *     own resampler loop (Generate4Ch while samplecnt >= rateratio, then
 *     linear interpolation at the core's fraction) so per-channel values
 *     can be captured at the chip's own sample instants.
 *   - per-channel value = *out[0]+*out[1]+*out[2]+*out[3] - the exact
 *     expression OPL3_Generate4Ch mixes (line 1125 of the core).
 *   - level: a full-scale carrier (TL 0, envelope max, MUL 1) peaks at
 *     exprom[0]<<1 = 4084; OPL_SCALE maps that to ~1.0.  There is NO
 *     output clip anywhere: the silicon has none (the core's int16 mix
 *     clamp is the emulated DAC domain's own saturation), and the sum of
 *     18 channels belongs to the patch downstream - attenuate before
 *     dac~ (a dense mix can reach ~8 over +-1; same convention as
 *     ym2612~ since 0.17.82).
 *   - idle skip: before the first key-on reaches the chip (regs
 *     0xB0-0xB8 bit 5, or the 0xBD rhythm bits, or a voice note), the
 *     reset chip is provably silent and perform outputs zeros without
 *     running the core (the 0.17.75 xrun lesson).
 *   - bank-1 writes (0x100-0x1ff, VGM port 1) reach the core regardless
 *     of the OPL3-mode enable (reg 0x105 bit 0): the emulator is more
 *     permissive than silicon.  Real logs write 0x105 first anyway.
 *
 * Register map cheat sheet (same as every OPL2/OPL3 doc):
 *   0x20+n AM/VIB/EGT/KSR/MULT (per operator)      0x40+n KSL/TL
 *   0x60+n AR/DR   0x80+n SL/RR   0xE0+n waveform  (n = operator offset;
 *   the carrier of channel c sits 3 offsets after its modulator - see
 *   ad_slot in the core)   0xA0+i FNUM low   0xB0+i keyon/blk/FNUM-high
 *   0xBD rhythm/depths   0xC0+i feedback/CON/L-R   0x104 4-op masks
 *   0x105 OPL3 mode (NEW)
 */

#include "m_pd.h"
#include <math.h>
#include <string.h>
#include <stdio.h>
#include "opl3.h"               /* Nuked-OPL3 v1.8, LGPL-2.1 (c) Nuke.YKT */
#include "opl_gm_bank.h"        /* Mobilnik-v2 GM set, MIT (c) Wohlstand */

#ifndef OPL_SCALE
/* a full-scale carrier (TL 0, eg_out 0) peaks at EnvelopeCalcExp(0) =
 * exprom[0]<<1 = 4084 - the chip's own maximum for one sinusoid */
#define OPL_SCALE (1.0 / 4084.0)
#endif

#define OPL_NATIVE  49716.0     /* core's hardcoded native rate (clock/288) */
/* opl3.c's private RSM_FRAC (line 52) = 10: Reset computes rateratio =
 * (samplerate << 10) / 49716.  The vendored core is frozen (ym3438.c
 * precedent), so dsp() refreshes the ratio with the same formula
 * instead of a full OPL3_Reset, which would wipe all registers on every
 * dsp edge (the app rebuilds graphs while running). */
#define OPL_RSM_FRAC 10

#define OPL_NCH 18              /* melodic channels (2 x 9 bank 0/1) */

/* pitch base: the chip's phase law at MULT 1 - f = FNUM * 2^BLK *
 * 49716 / 2^20 (the mt table makes MUL 1 the x1 multiplier).  The voice
 * layer writes the FNUM that means "this midi note at MULT 1"; an
 * instrument whose carrier MULT differs scales the pitch by the chip's
 * own multiplier, exactly how the FNUM pins behave. */
#define OPL_PITCHBASE (OPL_NATIVE / (1 << 20))

typedef struct _opl {
    t_object  obj;
    t_outlet *x_out[OPL_NCH + 2];   /* 0 = L sum, 1 = R sum, 2.. = ch 1..18 */
    t_sample *x_outv[OPL_NCH + 2];  /* dsp vectors, refreshed by dsp() */
    t_inlet  *x_gatein;
    float     x_sr;
    opl3_chip x_chip;
    double    x_rsacc;
    float     x_rs_old[OPL_NCH], x_rs_cur[OPL_NCH]; /* per-channel resampler */
    int       x_keyed;      /* any key-on ever reached the chip */
    int       x_mono;       /* ignore pan masks, both sums get everything */
    /* per-channel voice state (bare messages = ch1) */
    float     x_pitch[OPL_NCH];
    float     x_freq[OPL_NCH];
    float     x_tr[OPL_NCH];    /* gm preset transpose */
    float     x_peak[OPL_NCH];  /* velocity 0..1 */
    int       x_gate[OPL_NCH];
    float     x_ggain[OPL_NCH]; /* smoothed gate 0..1 (20 ms) */
    int       x_fnum[OPL_NCH], x_blk[OPL_NCH];
    int       x_b0[OPL_NCH];    /* keyon register byte of the voice layer */
    int       x_con[OPL_NCH], x_fb[OPL_NCH], x_pan[OPL_NCH];
    int       x_voiced[OPL_NCH];
    unsigned char x_opr[OPL_NCH][2][5]; /* [ch][op 0=car 1=mod]{20 40 60 80 e0} */
    int       x_rawmode;    /* reg/opl stream mode: pin gates and peaks */
    /* voice-layer portamento: `glide <ms>` arms a channel, `note` then
     * sweeps from the previous pitch to the new one (retrigger still at
     * sweep start), `slide <midi> [ms] [vel]` sweeps without a key event.
     * Geometric (midi-linear) interpolation stepped once per perform
     * block - one A0/B0 write pair per block. */
    float     x_glide[OPL_NCH]; /* glide time ms (0 = off, default) */
    float     x_glf[OPL_NCH];   /* sweep source Hz */
    float     x_glt[OPL_NCH];   /* sweep target Hz */
    float     x_glpos[OPL_NCH]; /* 0..1 position */
    float     x_glinv[OPL_NCH]; /* position increment per millisecond (0=idle) */
    /* gm bank: compiled default or a runtime-loaded .wopl */
    const opl_gmpatch *x_bank;
    opl_gmpatch *x_bankheap;
} t_opl;

static t_class *opl_class;

/* ---------------------------------------------------------------- util */

static float mnote_hz(float m) {     /* midi note -> Hz (pd mtof) */
    return 8.1757989156f * powf(2.0f, m / 12.0f);
}

/* register address of channel ch's operator (0 = carrier, 1 = modulator;
 * the WOPL/bank order - op 1 is the carrier): ch 0-8 = bank 0, ch 9-17 =
 * bank 1; slots per channel = ch_slot[c] (0,1,2,6,7,8,12,13,14 bank-
 * local), the carrier sits 3 slots after the modulator; slot ->
 * register offset per ad_slot (offsets 0-5, 8-13, 16-21) */
static int opl_opreg(int ch, int which) {
    static const int ch_slot[9] = {0, 1, 2, 6, 7, 8, 12, 13, 14};
    int c = ch % 9;
    int s = ch_slot[c] + (which ? 0 : 3);
    int off = (s < 6) ? s : (s < 12 ? s + 2 : s + 4);
    return (ch >= 9 ? 0x100 : 0) | (0x20 + off);
}

/* channel-addressed registers live at bank-local channel index c */
static int opl_chreg(int ch, int base) {
    return (ch >= 9 ? 0x100 : 0) | (base + (ch % 9));
}

static void opl_write(t_opl *x, int reg, int data) {
    int a = reg & 0xff;
    if (a >= 0xb0 && a <= 0xb8 && (data & 0x20)) x->x_keyed = 1;
    if (a == 0xbd && (data & 0x1f)) x->x_keyed = 1;
    /* OPL3_WriteRegBuffered, NOT WriteReg: the core only advances its
     * envelope state machines while GENERATING samples, and the retrigger
     * rule resets the envelope only from the release state.  A log's note
     * boundary is an ATOMIC burst - keyoff, TL reset, pan off, fnum, keyon,
     * pan on, all at one VGM instant - so instant writes collapsed it into
     * one chip instant: the envelope never observed key=0, stayed in decay,
     * and EVERY later key-on found eg_gen != release = no re-attack.  The
     * melody channel then faded away monotonically over ~10 s while the log
     * kept keying it (funkystars ch1-3: 2889 -> 1 within 7 s; the online
     * player keeps the melody - it drives the same core through the
     * buffered bus).  Buffered writes land OPL_WRITEBUF_DELAY (2) chip
     * samples apart and the drain runs per generated sample, so the
     * envelope sees the release and the next key-on re-attacks - verified
     * by the clean-room A/B (ch1 stays 2889 through the song).  Same fix
     * applies to the voice layer's keyoff->keyon retrig.  OPL3_Reset (in
     * opl_reset) memsets the chip, dropping any pending buffered writes -
     * correct for a reset.  libpd runs messages and perform on one thread,
     * so queueing here and draining in opl_perform cannot race. */
    OPL3_WriteRegBuffered(&x->x_chip, (uint16_t)(reg & 0x1ff),
                          (uint8_t)data);
}

/* idx 0..4 map to register deltas from the operator's 0x20 base:
 * 0x20 0x40 0x60 0x80 0xE0 */
static const int opl_opdelta[5] = {0x00, 0x20, 0x40, 0x60, 0xc0};

/* one operator-register write from the voice layer (keeps the shadow) */
static void opl_voice_reg(t_opl *x, int ch, int which, int idx, int data) {
    x->x_opr[ch][which][idx] = (unsigned char)data;
    opl_write(x, opl_opreg(ch, which) + opl_opdelta[idx], data);
}

/* pitch -> chip fnum/block (blk chosen so the 10-bit fnum lands in the
 * top half of its range, exactly how the FNUM pins are used) */
static void opl_pitch_fc(t_opl *x, int ch) {
    int fc = (int)(x->x_freq[ch] / OPL_PITCHBASE + 0.5);
    int blk = 0, fn, t = fc;
    if (fc < 1) fc = 1;
    while (t >>= 1) blk++;        /* blk = bitlen(fc) - 1 */
    blk -= 9;
    if (blk < 0) blk = 0;
    if (blk > 7) blk = 7;
    fn = fc >> blk;
    if (fn > 1023) fn = 1023;
    x->x_fnum[ch] = fn;
    x->x_blk[ch] = blk;
}

/* write current fnum/block + key-on byte of one channel (A0 first, B0
 * commits - the register write order of every OPL log).  B0 bit 5 is
 * THE KEYON: every pitch write must keep it, or the envelope jumps to
 * release and a sounding voice dies.  This rebuilt the byte from
 * blk/fnum alone, so on opl3~ any pitch update through here keyed the
 * voice off - `slide <m> 0`, one-arg `slide <m>` (no glide armed), a
 * slide to the current pitch, a float on the pitch inlet, a gate-keyed
 * voice's first slide (x_freq 0 = straight path) all silenced the
 * sound the instant they re-pitched it.  ym2612~ was immune because
 * its $A4/$A0 writes never touch the key register $28. */
static void opl_chip_fnum(t_opl *x, int ch) {
    x->x_b0[ch] = (x->x_b0[ch] & 0x20)
        | ((x->x_blk[ch] & 7) << 2) | ((x->x_fnum[ch] >> 8) & 3);
    opl_write(x, opl_chreg(ch, 0xa0), x->x_fnum[ch] & 0xff);
    opl_write(x, opl_chreg(ch, 0xb0), x->x_b0[ch]);
}

static void opl_pitch_update(t_opl *x, int ch) {
    x->x_freq[ch] = mnote_hz(x->x_pitch[ch] + x->x_tr[ch]);
    opl_pitch_fc(x, ch);
    opl_chip_fnum(x, ch);
}

/* C0 of channel ch: feedback/connection bits + pan bits, pan from
 * x_pan (0 off, 1 left, 2 right, 3 both = bits 4/5 of the register) */
static void opl_write_c0(t_opl *x, int ch) {
    int v = (x->x_fb[ch] << 1) | (x->x_con[ch] & 1)
            | ((x->x_pan[ch] & 1) << 4) | ((x->x_pan[ch] & 2) << 4);
    opl_write(x, opl_chreg(ch, 0xc0), v);
}

/* raw mode: the log's registers own the level - pin gate and peak on
 * every channel (the 20 ms ggain fade still opens the voice smoothly) */
static void opl_rawmode(t_opl *x) {
    int c;
    if (x->x_rawmode) return;
    x->x_rawmode = 1;
    for (c = 0; c < OPL_NCH; c++) {
        x->x_gate[c] = 1;
        x->x_peak[c] = 1.0f;
    }
}

/* first voice use of a channel: flush its instrument shadows to the
 * chip.  The chip powers up with ALL registers zero - AR 0 = envelopes
 * can never start, so a virgin channel is mute even when keyed; every
 * channel MUST receive an instrument before a note on it can sound.
 * Only voice messages trigger this - raw `reg` writes never do, keeping
 * the reset values raw patches depend on. */
static void opl_voice_init(t_opl *x, int ch) {
    int i, w;
    if (x->x_voiced[ch]) return;
    for (w = 0; w < 2; w++)
        for (i = 0; i < 5; i++)
            opl_write(x, opl_opreg(ch, w) + opl_opdelta[i],
                      x->x_opr[ch][w][i]);
    opl_write_c0(x, ch);
    opl_chip_fnum(x, ch);
    x->x_voiced[ch] = 1;
}

/* ------------------------------------------------------------- control */

static void opl_float(t_opl *x, t_float f) {   /* inlet 1: pitch = ch1 */
    x->x_pitch[0] = f;
    opl_voice_init(x, 0);
    opl_pitch_update(x, 0);
}

static void opl_gate_ch(t_opl *x, int ch, t_float f) {
    int g = (f != 0);
    if (g == x->x_gate[ch]) return;
    x->x_gate[ch] = g;
    if (g) {
        opl_voice_init(x, ch);
        x->x_keyed = 1;
        opl_chip_fnum(x, ch);   /* commit blk/fnum with the key-on byte */
    }
    /* key on/off: bit 5 of 0xB0+c, blk/FNUM-high bits kept */
    x->x_b0[ch] = (x->x_b0[ch] & ~0x20) | (g ? 0x20 : 0);
    opl_write(x, opl_chreg(ch, 0xb0), x->x_b0[ch]);
}

static void opl_ft1(t_opl *x, t_float f) {     /* inlet 2: gate = ch1 - the
                             * gate inlet is a bare spelling, and bare
                             * spellings always mean channel 1 (per-channel
                             * release = `chN gate 0` / `chN note 0`) */
    opl_gate_ch(x, 0, f);
}

static void opl_alg_ch(t_opl *x, int ch, t_float f) {
    int v = (int)f;
    opl_voice_init(x, ch);
    if (v < 0) v = 0;
    /* 2-op: CON = alg & 1 (0 = FM serial, 1 = AM parallel).  On a 4-op
     * pair: bit 0 = the addressed (first) channel's connection, bit 1 =
     * the pair channel's (alg 0-3; higher bits ignored). */
    x->x_con[ch] = v & 1;
    opl_write_c0(x, ch);
    if (x->x_chip.channel[ch].chtype == 1 /* ch_4op */ && x->x_chip.channel[ch].pair) {
        int p = (int)(x->x_chip.channel[ch].pair - x->x_chip.channel);
        x->x_con[p] = (v >> 1) & 1;
        opl_write_c0(x, p);
    }
}

static void opl_fb_ch(t_opl *x, int ch, t_float f) {
    int v = (int)f;
    opl_voice_init(x, ch);
    if (v < 0) v = 0;
    if (v > 7) v = 7;
    x->x_fb[ch] = v;
    opl_write_c0(x, ch);
}

static void opl_pan_ch(t_opl *x, int ch, t_float f) {
    int v = (int)f;
    opl_voice_init(x, ch);
    if (v < 0) v = 0;
    if (v > 3) v = 3;
    x->x_pan[ch] = v;
    opl_write_c0(x, ch);
}

/* op <1-2> <param> <value> - raw chip register values on the addressed
 * channel's two operators (1 = carrier, 2 = modulator).  OPL ranges:
 * tl 0-63, rates 0-15, mul 0-15, ksl 0-3, ws 0-7, booleans 0/1. */
static void opl_op_ch(t_opl *x, int ch, int argc, t_atom *argv) {
    int k, i;
    t_symbol *p;
    float v;
    unsigned char *r;
    opl_voice_init(x, ch);
    if (argc < 3 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_SYMBOL ||
        argv[2].a_type != A_FLOAT) {
        pd_error(x, "opl3~: op expects <1-2> <param> <value>");
        return;
    }
    k = (int)atom_getfloat(&argv[0]) - 1;
    if (k < 0 || k > 1) {
        pd_error(x, "opl3~: operator index must be 1-2 (1 = carrier, "
                    "2 = modulator)");
        return;
    }
    p = atom_getsymbol(&argv[1]);
    v = atom_getfloat(&argv[2]);
    i = (int)v;
    r = x->x_opr[ch][k];
    if (!strcmp(p->s_name, "mul")) {
        if (i < 0) i = 0;
        if (i > 15) i = 15;
        r[0] = (unsigned char)((r[0] & 0xf0) | i);
        opl_voice_reg(x, ch, k, 0, r[0]);
    } else if (!strcmp(p->s_name, "am") || !strcmp(p->s_name, "vib") ||
               !strcmp(p->s_name, "egt") || !strcmp(p->s_name, "ksr")) {
        int bit = !strcmp(p->s_name, "am") ? 7 :
                  (!strcmp(p->s_name, "vib") ? 6 :
                  (!strcmp(p->s_name, "egt") ? 5 : 4));
        r[0] = (unsigned char)((r[0] & ~(1 << bit)) | ((i != 0) << bit));
        opl_voice_reg(x, ch, k, 0, r[0]);
    } else if (!strcmp(p->s_name, "tl")) {
        if (i < 0) i = 0;
        if (i > 63) i = 63;
        r[1] = (unsigned char)((r[1] & 0xc0) | i);
        opl_voice_reg(x, ch, k, 1, r[1]);
    } else if (!strcmp(p->s_name, "ksl")) {
        if (i < 0) i = 0;
        if (i > 3) i = 3;
        r[1] = (unsigned char)((i << 6) | (r[1] & 0x3f));
        opl_voice_reg(x, ch, k, 1, r[1]);
    } else if (!strcmp(p->s_name, "ar") || !strcmp(p->s_name, "dr")) {
        if (i < 0) i = 0;
        if (i > 15) i = 15;
        if (!strcmp(p->s_name, "ar"))
            r[2] = (unsigned char)((i << 4) | (r[2] & 0x0f));
        else
            r[2] = (unsigned char)((r[2] & 0xf0) | i);
        opl_voice_reg(x, ch, k, 2, r[2]);
    } else if (!strcmp(p->s_name, "sl") || !strcmp(p->s_name, "rr")) {
        if (i < 0) i = 0;
        if (i > 15) i = 15;
        if (!strcmp(p->s_name, "sl"))
            r[3] = (unsigned char)((i << 4) | (r[3] & 0x0f));
        else
            r[3] = (unsigned char)((r[3] & 0xf0) | i);
        opl_voice_reg(x, ch, k, 3, r[3]);
    } else if (!strcmp(p->s_name, "ws")) {
        if (i < 0) i = 0;
        if (i > 7) i = 7;
        r[4] = (unsigned char)i;
        opl_voice_reg(x, ch, k, 4, r[4]);
    } else {
        pd_error(x, "opl3~: unknown op param %s (mul am vib egt ksr tl "
                    "ksl ar dr sl rr ws)", p->s_name);
    }
}

/* gm <1-128>: dump the GM preset's raw register bytes into the addressed
 * channel (carrier op1 + modulator op2; the bank is 100% 2-op).  The
 * bank's C0 byte carries feedback/connection - pan bits come from the
 * channel's own x_pan (default L+R). */
static void opl_gm_ch(t_opl *x, int ch, t_float f) {
    int n = (int)f;
    int i;
    const opl_gmpatch *g;
    if (n < 1 || n > 128) {
        pd_error(x, "opl3~: gm expects 1-128 (GM instrument number)");
        return;
    }
    opl_voice_init(x, ch);
    g = &x->x_bank[n - 1];
    x->x_tr[ch] = (float)g->tr;
    for (i = 0; i < 5; i++) {
        opl_voice_reg(x, ch, 0, i, g->op[0][i]);   /* carrier (bank op1) */
        opl_voice_reg(x, ch, 1, i, g->op[1][i]);   /* modulator (bank op2) */
    }
    x->x_fb[ch] = (g->fbconn >> 1) & 7;
    x->x_con[ch] = g->fbconn & 1;
    opl_write_c0(x, ch);
    opl_pitch_update(x, ch);   /* transpose may have changed */
}

/* bare `gm` = channel 1 (the note/GM voice) */

/* voice-layer portamento, ym2612~'s law.  `glide <ms>` arms the
 * channel; the sweep itself lives in the perform stepper (per-block
 * geometric interpolation, one A0/B0 pair per block).  Key semantics
 * stay where they were: note retriggers at sweep start, slide never
 * keys. */
static void opl_glide_ch(t_opl *x, int ch, t_float f) {
    x->x_glide[ch] = f < 0.0f ? 0.0f : (f > 5000.0f ? 5000.0f : (float)f);
}

/* one sweep start: from the channel's CURRENT effective frequency to
 * the target (Hz); x_freq keeps the source value here - the note path's
 * key events fire at sweep start, the first fnum write of the sweep
 * lands in the next perform block */
static void opl_glide_start(t_opl *x, int ch, float ms, float target_hz) {
    if (ms < 1.0f) ms = 1.0f;
    if (target_hz <= 0.0f) return;
    if (x->x_freq[ch] <= 0.0f || x->x_freq[ch] == target_hz) {
        x->x_freq[ch] = target_hz;      /* no usable source: straight */
        x->x_glinv[ch] = 0.0f;
        return;
    }
    x->x_glf[ch] = x->x_freq[ch];
    x->x_glt[ch] = target_hz;
    x->x_glpos[ch] = 0.0f;
    x->x_glinv[ch] = 1.0f / ms;      /* pos/ms - an 800 ms glide
                                     * walks 0.00125 per ms */
}

/* slide <midi> [ms] [vel]: one-shot glide to the pitch WITHOUT a key
 * event - a sounding channel sweeps, a silent one just moves its pitch.
 * ms omitted = the channel's `glide` time.  vel 0-127 rides the
 * channel's level (x_peak, the same scalar the note path uses) without
 * touching the gate; omitted = the level stays. */
static void opl_slide_ch(t_opl *x, int ch, int argc, t_atom *argv) {
    float p, ms;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "opl3~: slide expects <midi> [ms] [vel]");
        return;
    }
    p = atom_getfloat(&argv[0]);
    ms = x->x_glide[ch];
    if (argc >= 2 && argv[1].a_type == A_FLOAT) ms = atom_getfloat(&argv[1]);
    if (argc >= 3 && argv[2].a_type == A_FLOAT) {
        float v = atom_getfloat(&argv[2]);
        if (v < 0.0f) v = 0.0f;
        if (v > 127.0f) v = 127.0f;
        x->x_peak[ch] = v / 127.0f;
    }
    opl_voice_init(x, ch);   /* an untouched channel must flush its
                              * instrument shadows first */
    x->x_pitch[ch] = p;
    if (ms <= 0.0f) {
        opl_pitch_update(x, ch);        /* instant re-pitch */
        return;
    }
    opl_glide_start(x, ch, ms, mnote_hz(p + x->x_tr[ch]));
    if (x->x_glinv[ch] <= 0.0f) opl_pitch_update(x, ch);
}

static void opl_note_ch(t_opl *x, int ch, int argc, t_atom *argv) {
    float p, v = 127.0f;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "opl3~: note expects <midi> [vel]");
        return;
    }
    p = atom_getfloat(&argv[0]);
    if (argc >= 2 && argv[1].a_type == A_FLOAT) v = atom_getfloat(&argv[1]);
    if (v < 0) v = 0;
    if (v > 127) v = 127;
    if (v <= 0) {
        /* MIDI note-off: release through the gate fade + the chip's rr
         * stage; pitch and peak stay untouched so the tail rings at the
         * note's own level (ym2612~'s note-off semantics) */
        opl_gate_ch(x, ch, 0);
        return;
    }
    opl_voice_init(x, ch);   /* first voice use of a channel flushes the
                 * instrument shadows - without this a virgin channel's
                 * chip registers are all zero (AR 0 = the envelope can
                 * never attack) and the note is silent (0.17.115
                 * regression caught by the status dump: state was
                 * gate=1 b0=2f but voiced=0) */
    x->x_peak[ch] = v / 127.0f;
    x->x_gate[ch] = 1;
    x->x_pitch[ch] = p;
    if (x->x_glide[ch] > 0.0f) {
        /* portamento: sweep from the previous pitch (x_freq holds it
         * until the first perform step); the key events below still
         * fire at sweep START - the note sounds while it slides */
        opl_glide_start(x, ch, x->x_glide[ch], mnote_hz(p + x->x_tr[ch]));
        if (x->x_glinv[ch] <= 0.0f) opl_pitch_update(x, ch);
    } else {
        opl_pitch_update(x, ch);
    }
    /* key off first: a note always retriggers, even while the gate is
     * already open (the core resets the envelope only on a key-on that
     * follows a release) */
    x->x_b0[ch] &= ~0x20;
    opl_write(x, opl_chreg(ch, 0xb0), x->x_b0[ch]);
    if (x->x_glinv[ch] <= 0.0f) opl_chip_fnum(x, ch);
    x->x_b0[ch] |= 0x20;
    opl_write(x, opl_chreg(ch, 0xb0), x->x_b0[ch]);
    x->x_keyed = 1;
}

/* ------------------------------------------------------- bank loading */

static void opl_bank_clear(t_opl *x) {
    if (x->x_bankheap) {
        freebytes(x->x_bankheap, 128 * sizeof(opl_gmpatch));
        x->x_bankheap = 0;
    }
    x->x_bank = (const opl_gmpatch *)opl_gm_bank;
}

/* parse a WOPL image into 128 fresh rows; returns 1 on success (rows in
 * *bkp, heap-owned) or 0 with a console error.  Layout per the spec
 * (Specifications/WOPL-and-OPLI-Specification.txt): magic
 * "WOPL3-BANK\0", LE16 version, BE16 melodic bank count, BE16
 * percussion count, flags, volume model; v2+ adds 34 metadata bytes per
 * bank; entries are 62 bytes (v1/v2) or 66 (v3, +2+2 delay bytes).
 * Only the melodic bank 0 is taken; this bank set is 100% 2-op, so the
 * first voice (carrier op1 + modulator op2) covers every preset. */
static int opl_bank_parse(t_opl *x, const unsigned char *d, unsigned long len) {
    int ver, nmel, nperc, ent, base;
    unsigned long need;
    opl_gmpatch *bk;
    int n, i;
    if (len < 19 || memcmp(d, "WOPL3-BANK\x00", 11)) {
        pd_error(x, "opl3~: bank: not a WOPL file (magic mismatch)");
        return 0;
    }
    ver = d[11] | (d[12] << 8);
    nmel = (d[13] << 8) | d[14];
    nperc = (d[15] << 8) | d[16];
    ent = ver >= 3 ? 66 : 62;
    base = ver >= 2 ? 19 + 34 * (nmel + nperc) : 19;
    need = (unsigned long)base + 128UL * ent;
    if (nmel < 1 || len < need) {
        pd_error(x, "opl3~: bank: truncated or has no melodic bank "
                    "(version %d, nmel %d, %lu bytes, need %lu)",
                 ver, nmel, len, need);
        return 0;
    }
    bk = (opl_gmpatch *)getbytes(128 * sizeof(opl_gmpatch));
    if (!bk) {
        pd_error(x, "opl3~: bank: out of memory");
        return 0;
    }
    for (n = 0; n < 128; n++) {
        const unsigned char *e = d + base + (unsigned long)n * ent;
        int off = (e[32] << 8) | e[33];
        bk[n].fbconn = e[40];
        bk[n].tr = (signed char)(off > 32767 ? off - 65536 : off);
        /* fields: 32 name + 2+2 note offsets + 1 vel + 1 detune + 1 perc
         * key + 1 flags + 1 fbconn + 1 fbconn2 = 42, then the operators */
        for (i = 0; i < 5; i++) {
            bk[n].op[0][i] = e[42 + i];        /* carrier (bank op1) */
            bk[n].op[1][i] = e[47 + i];        /* modulator (bank op2) */
        }
    }
    opl_bank_clear(x);
    x->x_bankheap = bk;
    x->x_bank = bk;
    return 1;
}

/* bank file lookup shared with vgm~/ym2612~ (search path, no canvas
 * deref - see ym2612_tilde.c for the 0.17.69 story) */
int ympp_findfile(const char *name, char *full, unsigned int n);

/* bank <name.wopl|default>: swap the whole GM table at message rate. */
static void opl_bank(t_opl *x, t_symbol *s) {
    char full[MAXPDSTRING];
    int rc;
    FILE *f;
    unsigned char *buf = 0;
    unsigned long len, got;
    if (!s || !s->s_name || !s->s_name[0]) {
        post("opl3~: bank is '%s' (%s)",
             x->x_bankheap ? "runtime file" : "compiled Mobilnik-v2 default",
             x->x_bankheap ? "use 'bank default' to go back" : "loaded");
        return;
    }
    if (!strcmp(s->s_name, "default")) {
        opl_bank_clear(x);
        post("opl3~: bank: compiled Mobilnik-v2 default restored");
        return;
    }
    rc = ympp_findfile(s->s_name, full, MAXPDSTRING);
    if (!rc) {
        pd_error(x, "opl3~: bank: '%s' not found (pd search path)",
                 s->s_name);
        return;
    }
    f = fopen(full, "rb");
    if (!f) {
        pd_error(x, "opl3~: bank: cannot open '%s'", full);
        return;
    }
    fseek(f, 0, SEEK_END);
    len = (unsigned long)ftell(f);
    fseek(f, 0, SEEK_SET);
    buf = (unsigned char *)getbytes(len ? len : 1);
    got = buf && len ? fread(buf, 1, len, f) : 0;
    fclose(f);
    if (!buf || got != len) {
        if (buf) freebytes(buf, len ? len : 1);
        pd_error(x, "opl3~: bank: read failed on '%s'", s->s_name);
        return;
    }
    if (opl_bank_parse(x, buf, len))
        post("opl3~: bank '%s' loaded: 128 GM presets, "
             "raw OPL register bytes verbatim", s->s_name);
    freebytes(buf, len ? len : 1);
}

static void opl_mono(t_opl *x, t_float f) {
    x->x_mono = (f != 0);
}

/* reg <reg> <data>: one raw chip register write, verbatim (implies raw
 * mode - the log's own registers own the level from here). */
static void opl_reg(t_opl *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 2) {
        pd_error(x, "opl3~: reg expects <reg> <data>");
        return;
    }
    opl_rawmode(x);
    opl_write(x, (int)atom_getfloat(&argv[0]),
              (int)atom_getfloat(&argv[1]));
}

/* the factory voice = the demo tone (verified 440 Hz round-trip on
 * device, 0.17.113): clarinet-ish, fast attack, sustained carrier */
static const unsigned char opl_factory[2][5] = {
    {0x21, 0x00, 0xf0, 0x08, 0x00},   /* carrier: EGT 1, MUL 1, TL 0 */
    {0x01, 0x10, 0xf0, 0x08, 0x00},   /* modulator: MUL 1, TL 16 */
};

static void opl_reset(t_opl *x) {
    int c, i;
    OPL3_Reset(&x->x_chip, (uint32_t)x->x_sr);
    x->x_keyed = 0;
    x->x_rawmode = 0;
    x->x_rsacc = 0.0;
    for (c = 0; c < OPL_NCH; c++) {
        x->x_pitch[c] = 0.0f;
        x->x_freq[c] = 0.0f;
        x->x_tr[c] = 0.0f;
        x->x_peak[c] = 1.0f;
        x->x_gate[c] = 0;
        x->x_ggain[c] = 0.0f;
        x->x_fnum[c] = 0;
        x->x_blk[c] = 0;
        x->x_b0[c] = 0;
        x->x_glide[c] = 0.0f;
        x->x_glf[c] = x->x_glt[c] = x->x_glpos[c] = x->x_glinv[c] = 0.0f;
        x->x_con[c] = 0;
        x->x_fb[c] = 0;
        x->x_pan[c] = 3;
        x->x_voiced[c] = 0;
        x->x_rs_old[c] = x->x_rs_cur[c] = 0.0f;
        /* restore the factory instrument shadows - a memset here left
         * AR 0 after every reset, so the next voice note could never
         * attack (measured silent) */
        for (i = 0; i < 5; i++) {
            x->x_opr[c][0][i] = opl_factory[0][i];
            x->x_opr[c][1][i] = opl_factory[1][i];
        }
    }
}

/* "chN" (N = 1..18) scope token: returns the channel (0-based) or -1
 * when the word is not a scope */
static int opl_scope(const char *name) {
    if (name[0] == 'c' && name[1] == 'h' && name[2] >= '1' && name[2] <= '9'
        && (name[3] == 0 || (name[2] == '1' && name[3] >= '0'
                             && name[3] <= '8' && name[4] == 0)))
        return (name[3] ? 10 + (name[3] - '0') : name[2] - '0') - 1;
    return -1;
}

/* one command consumed from a message's token stream; ch = the scope
 * channel the message set (0 = bare, always ch1).  Returns the number
 * of argument atoms consumed, or -1 when the word is not a command.
 * Malformed arguments consume the whole rest (one clear error, no
 * cascading) - the chain stops there. */
static int opl_command(t_opl *x, int ch, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    if (!strcmp(name, "note")) {
        int na = 0;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            na = 1;
            if (argc >= 2 && argv[1].a_type == A_FLOAT) na = 2;
        }
        if (!na) {
            pd_error(x, "opl3~: note expects <midi> [vel]");
            return argc;
        }
        opl_note_ch(x, ch, na, argv);
        return na;
    }
    if (!strcmp(name, "gate")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            opl_gate_ch(x, ch, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "opl3~: gate expects 0/1");
        return argc;
    }
    if (!strcmp(name, "glide")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            opl_glide_ch(x, ch, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "opl3~: glide expects <ms>");
        return argc;
    }
    if (!strcmp(name, "slide")) {
        int na = 0;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            na = 1;
            if (argc >= 2 && argv[1].a_type == A_FLOAT) {
                na = 2;
                if (argc >= 3 && argv[2].a_type == A_FLOAT) na = 3;
            }
        }
        if (!na) {
            pd_error(x, "opl3~: slide expects <midi> [ms] [vel]");
            return argc;
        }
        opl_slide_ch(x, ch, na, argv);
        return na;
    }
    if (!strcmp(name, "alg") || !strcmp(name, "fb") || !strcmp(name, "pan")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            if (name[0] == 'a') opl_alg_ch(x, ch, atom_getfloat(argv));
            else if (name[0] == 'f') opl_fb_ch(x, ch, atom_getfloat(argv));
            else opl_pan_ch(x, ch, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "opl3~: %s expects a number", name);
        return argc;
    }
    if (!strcmp(name, "op")) {
        if (argc >= 3 && argv[0].a_type == A_FLOAT &&
            argv[1].a_type == A_SYMBOL && argv[2].a_type == A_FLOAT) {
            opl_op_ch(x, ch, 3, argv);
            return 3;
        }
        pd_error(x, "opl3~: op expects <1-2> <param> <value>");
        return argc;
    }
    if (!strcmp(name, "gm")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            opl_gm_ch(x, ch, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "opl3~: gm expects 1-128 (GM instrument number)");
        return argc;
    }
    if (!strcmp(name, "bank")) {
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            opl_bank(x, atom_getsymbol(argv));
            return 1;
        }
        opl_bank(x, 0);      /* bare bank = post the current table */
        return 0;
    }
    if (!strcmp(name, "mono")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            opl_mono(x, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "opl3~: mono expects 0/1");
        return argc;
    }
    if (!strcmp(name, "reg") || !strcmp(name, "opl")) {
        if (argc >= 2 && argv[0].a_type == A_FLOAT &&
            argv[1].a_type == A_FLOAT) {
            opl_reg(x, s, 2, argv);   /* raw write: register addresses are
                       * absolute, a chN scope cannot redirect them */
            return 2;
        }
        pd_error(x, "opl3~: reg expects <reg> <data>");
        return argc;
    }
    if (!strcmp(name, "reset")) {
        opl_reset(x);
        return 0;
    }
    if (!strcmp(name, "status")) {
        int c, w;
        post("opl3~ status: keyed=%d rawmode=%d sr=%.0f rateratio=%d",
             x->x_keyed, x->x_rawmode, (double)x->x_sr,
             (int)x->x_chip.rateratio);
        for (c = 0; c < OPL_NCH; c++) {
            post("  ch%02d voiced=%d gate=%d ggain=%.3f peak=%.2f "
                 "pitch=%.1f fn=%d blk=%d b0=%02x con=%d fb=%d pan=%d",
                 c + 1, x->x_voiced[c], x->x_gate[c], (double)x->x_ggain[c],
                 (double)x->x_peak[c], (double)x->x_pitch[c],
                 x->x_fnum[c], x->x_blk[c], x->x_b0[c],
                 x->x_con[c], x->x_fb[c], x->x_pan[c]);
        }
        for (w = 0; w < 2; w++)
            post("  ch1 op%d: %02x %02x %02x %02x %02x   ch3 op%d: "
                 "%02x %02x %02x %02x %02x",
                 w + 1, x->x_opr[0][w][0], x->x_opr[0][w][1],
                 x->x_opr[0][w][2], x->x_opr[0][w][3], x->x_opr[0][w][4],
                 w + 1, x->x_opr[2][w][0], x->x_opr[2][w][1],
                 x->x_opr[2][w][2], x->x_opr[2][w][3], x->x_opr[2][w][4]);
        {
            /* chip-level truth for ch1/ch3: slots 0/3 = ch1 mod/car,
             * slots 2/5 = ch3 mod/car; key/envelope/registers as the
             * core holds them right now */
            int sl[4] = {0, 3, 2, 5};
            int k;
            for (k = 0; k < 4; k++) {
                opl3_slot *s = &x->x_chip.slot[sl[k]];
                post("  chip slot%d: key=%d eg_gen=%d eg_rout=%03x ar=%02x "
                     "tl=%02x out=%d", sl[k], s->key, s->eg_gen, s->eg_rout,
                     s->reg_ar, s->reg_tl, s->out);
            }
            post("  chip ch3: f_num=%d blk=%d con=%d fb=%d cha=%d chb=%d "
                 "chtype=%d", x->x_chip.channel[2].f_num,
                 x->x_chip.channel[2].block_reg, x->x_chip.channel[2].con,
                 x->x_chip.channel[2].fb, x->x_chip.channel[2].cha,
                 x->x_chip.channel[2].chb, x->x_chip.channel[2].chtype);
            post("  chip ch1: f_num=%d blk=%d con=%d fb=%d",
                 x->x_chip.channel[0].f_num, x->x_chip.channel[0].block_reg,
                 x->x_chip.channel[0].con, x->x_chip.channel[0].fb);
        }
        return 0;
    }
    return -1;
}

/* anything on inlet 1.  A message is a COMMAND STREAM: every word is a
 * command that consumes its own arguments, so `gm 1 note 60 100` = load
 * preset 1 then play the note, all in one message - the `a \, b` comma
 * forms still work (a comma just starts a new message).  `chN` at the
 * start (or again mid-message) scopes every following voice command of
 * THIS message to chip channel N - per the msg spec that only says
 * "these commands go to channel N": it never keys off another channel,
 * never moves the sound over, and the gate inlet stays channel 1
 * (per-channel release = `chN gate 0` / `chN note <midi> 0`).  Bare
 * spellings always mean channel 1; mono/bank/reg/opl/reset are object-
 * global (a scope prefix is accepted but has no per-channel meaning for
 * them).  Sibling-chip selectors a shared vgm~ outlet also carries
 * (psg/gg/ay/nes) are ignored silently - error spam would flood playback. */
static void opl_anything(t_opl *x, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    int ch = 0, i = 0;
    t_symbol *cs = s;
    if (!strcmp(name, "list")) {
        /* OSC bridges deliver "gm 1 note 60 100" / "note 60 100" as
         * lists: symbol-first lists re-enter the command stream, plain
         * numbers are note <midi> [vel] on ch1 */
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            opl_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
            return;
        }
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            int na = (argc >= 2 && argv[1].a_type == A_FLOAT) ? 2 : 1;
            opl_note_ch(x, 0, na, argv);
        }
        return;
    }
    if (!strcmp(name, "psg") || !strcmp(name, "gg") || !strcmp(name, "ay")
        || !strcmp(name, "nes")) {
        return;   /* sn76489~/AY8910/NES APU bytes on the same vgm~ outlet */
    }
    for (;;) {
        const char *cn = cs->s_name;
        int sc = opl_scope(cn);
        if (sc >= 0) {
            ch = sc;
            if (i >= argc) return;    /* bare chN: scope set, nothing to run */
            if (argv[i].a_type != A_SYMBOL) {
                pd_error(x, "opl3~: ch%d: expected a command", ch + 1);
                return;
            }
            cs = atom_getsymbol(&argv[i]);
            i++;
            continue;
        }
        int used = opl_command(x, ch, cs, argc - i, argv + i);
        if (used < 0) {
            pd_error(x, "opl3~: unknown message %s", cn);
            return;
        }
        i += used;
        if (i >= argc) return;
        if (argv[i].a_type != A_SYMBOL) {
            pd_error(x, "opl3~: expected a command, got a number");
            return;
        }
        cs = atom_getsymbol(&argv[i]);
        i++;
    }
}

/* ---------------------------------------------------------------- dsp */

/* the wrapper runs the core's own resampler loop (opl3.c's
 * OPL3_Generate4ChResampled, lines 1250-1269) so per-channel values can
 * be captured at the chip's own sample instants: one native generation
 * per rateratio crossing, then linear interpolation at the core's
 * fraction (samplecnt/rateratio after the loop).  Per channel the value
 * read is *out[0]+*out[1]+*out[2]+*out[3] - the exact sum
 * OPL3_Generate4Ch mixes into mixbuff (line 1125). */
static t_int *opl_perform(t_int *w) {
    t_opl *x = (t_opl *)(w[1]);
    t_sample **outv = (t_sample **)w[2];
    t_sample *out[OPL_NCH + 2];
    int n = (int)(w[3]);
    int k, s, c;
    float gstep[OPL_NCH];
    for (k = 0; k < OPL_NCH + 2; k++) out[k] = outv[k];
    for (k = 0; k < OPL_NCH; k++)
        gstep[k] = (x->x_gate[k] ? 1.0f : -1.0f) / (0.02f * x->x_sr + 1.0f);
    /* portamento stepper, once per block: geometric interpolation
     * toward the target (midi-linear), one A0/B0 pair per block;
     * opl_chip_fnum keeps the keyon bit */
    {
        float dms = (float)n / x->x_sr * 1000.0f;   /* block time in ms */
        for (c = 0; c < OPL_NCH; c++) {
            if (x->x_glinv[c] <= 0.0f) continue;
            x->x_glpos[c] += x->x_glinv[c] * dms;
            if (x->x_glpos[c] >= 1.0f) {
                x->x_glpos[c] = 1.0f;
                x->x_glinv[c] = 0.0f;
                x->x_freq[c] = x->x_glt[c];
            } else {
                x->x_freq[c] = x->x_glf[c]
                    * powf(x->x_glt[c] / x->x_glf[c], x->x_glpos[c]);
            }
            opl_pitch_fc(x, c);
            opl_chip_fnum(x, c);
        }
    }
    for (s = 0; s < n; s++) {
        float frac, L = 0.0f, R = 0.0f;
        if (!x->x_keyed) {
            /* No key-on has ever reached this object: the chip sits at
             * reset (all envelopes at the bottom), so rendering would
             * produce exactly zero.  Skip the core entirely - idle vgm
             * tunes with an opl3~ attached cost nothing. */
            for (k = 0; k < OPL_NCH + 2; k++) out[k][s] = 0;
            continue;
        }
        for (c = 0; c < OPL_NCH; c++) {
            x->x_ggain[c] += gstep[c];
            if (x->x_ggain[c] < 0) x->x_ggain[c] = 0;
            if (x->x_ggain[c] > 1) x->x_ggain[c] = 1;
        }
        x->x_rsacc += 1 << OPL_RSM_FRAC;   /* the core's own per-call
                         * increment (samplecnt += 1<<RSM_FRAC in
                         * OPL3_Generate4ChResampled); a plain 1.0 here
                         * ran the chip 1024x slow, aliasing every pitch
                         * into sub-audio garbage (caught by the 440 Hz
                         * tone's sigmund readback wandering over octave
                         * stacks instead of holding ~69) */
        while (x->x_rsacc >= x->x_chip.rateratio) {
            x->x_rsacc -= x->x_chip.rateratio;
            for (c = 0; c < OPL_NCH; c++) x->x_rs_old[c] = x->x_rs_cur[c];
            OPL3_Generate4Ch(&x->x_chip, x->x_chip.samples);
            for (c = 0; c < OPL_NCH; c++) {
                opl3_channel *ch = &x->x_chip.channel[c];
                x->x_rs_cur[c] = (float)(ch->out[0][0] + ch->out[1][0]
                                      + ch->out[2][0] + ch->out[3][0]);
            }
        }
        frac = (float)(x->x_rsacc / x->x_chip.rateratio);
        for (c = 0; c < OPL_NCH; c++) {
            float v = x->x_rs_old[c] + (x->x_rs_cur[c] - x->x_rs_old[c]) * frac;
            v = (float)(v * OPL_SCALE) * x->x_peak[c] * x->x_ggain[c];
            out[c + 2][s] = v;
            if (x->x_mono || x->x_chip.channel[c].cha) L += v;
            if (x->x_mono || x->x_chip.channel[c].chb) R += v;
        }
        /* outlets 0/1 = the chip's own stereo mix (C0 pan bits), no
         * +-1 clip: the silicon has none, the sum belongs to the patch
         * downstream (ym2612~ convention since 0.17.82) */
        out[0][s] = L;
        out[1][s] = R;
    }
    return (w + 4);
}

static void opl_dsp(t_opl *x, t_signal **sp) {
    float sr = sp[0]->s_sr > 1.0f ? sp[0]->s_sr : 48000.0f;
    int k;
    if ((uint32_t)sr != (uint32_t)x->x_sr) {
        /* rate change: refresh the core's ratio only - a full Reset
         * would wipe every register on a dsp edge (see header) */
        x->x_sr = sr;
        x->x_chip.rateratio =
            (int32_t)((uint32_t)sr << OPL_RSM_FRAC) / (int32_t)OPL_NATIVE;
    }
    for (k = 0; k < OPL_NCH + 2; k++) x->x_outv[k] = sp[k]->s_vec;
    dsp_add(opl_perform, 3, x, x->x_outv, sp[0]->s_n);
}

/* ------------------------------------------------------------ lifecycle */

static void *opl_new(void) {
    t_opl *x = (t_opl *)pd_new(opl_class);
    int c, i;
    x->x_sr = 48000.0f;
    x->x_keyed = 0;
    x->x_mono = 0;
    x->x_rawmode = 0;
    x->x_rsacc = 0.0;
    x->x_bank = (const opl_gmpatch *)opl_gm_bank;
    x->x_bankheap = 0;
    for (c = 0; c < OPL_NCH; c++) {
        x->x_pitch[c] = 0.0f;
        x->x_freq[c] = 0.0f;
        x->x_tr[c] = 0.0f;
        x->x_peak[c] = 1.0f;
        x->x_gate[c] = 0;
        x->x_ggain[c] = 0.0f;
        x->x_fnum[c] = 0;
        x->x_blk[c] = 0;
        x->x_b0[c] = 0;
        x->x_glide[c] = 0.0f;
        x->x_glf[c] = x->x_glt[c] = x->x_glpos[c] = x->x_glinv[c] = 0.0f;
        x->x_con[c] = 0;
        x->x_fb[c] = 0;
        x->x_pan[c] = 3;
        x->x_voiced[c] = 0;
        x->x_rs_old[c] = x->x_rs_cur[c] = 0.0f;
        for (i = 0; i < 5; i++) {
            x->x_opr[c][0][i] = opl_factory[0][i];   /* carrier */
            x->x_opr[c][1][i] = opl_factory[1][i];   /* modulator */
        }
    }
    memset(&x->x_chip, 0, sizeof(x->x_chip));
    OPL3_Reset(&x->x_chip, 48000);
    {   /* outlet 0 = L sum, 1 = R sum, 2..19 = channels 1-18 */
        int k;
        for (k = 0; k < OPL_NCH + 2; k++)
            x->x_out[k] = outlet_new(&x->obj, &s_signal);
    }
    x->x_gatein = inlet_new(&x->obj, &x->obj.ob_pd, &s_float,
                            gensym("ft1"));
    return (void *)x;
}

static void opl_free(t_opl *x) {
    opl_bank_clear(x);
}

void opl3_tilde_setup(void) {
    opl_class = class_new(gensym("opl3~"), (t_newmethod)opl_new,
                          (t_method)opl_free, sizeof(t_opl), 0, A_NULL);
    class_addfloat(opl_class, opl_float);
    class_addmethod(opl_class, (t_method)opl_ft1, gensym("ft1"), A_FLOAT,
                    A_NULL);
    class_addanything(opl_class, opl_anything);
    class_addmethod(opl_class, (t_method)opl_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
}
