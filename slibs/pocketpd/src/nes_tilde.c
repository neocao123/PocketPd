/* nes~ - RP2A03 (the NES / Famicom APU: 2 pulses + triangle + noise +
 * DPCM), in C.  The DSP core is nes_apu.c (written from scratch against
 * nesdev.org, see there for the per-unit sources) - no third-party code
 * vendored.
 *
 * Two layers, exactly like opl3~:
 *
 *   RAW REGISTER LAYER  `nes <reg> <val>` (reg 0x00-0x1F = CPU addresses
 *      $4000-$401F, verbatim VGM 0xBC bytes; `reg` is the alias).  The
 *      chip's registers own everything from here (raw mode); a direct
 *      vgm~ outlet-1 -> nes~ connection plays the log.
 *
 *   VOICE LAYER        one voice per channel (ch1 pulse1, ch2 pulse2,
 *      ch3 triangle, ch4 noise, ch5 DPCM; bare spellings = ch1; a
 *      message is a command stream - every word is a command consuming
 *      its own args, so `note 60 100 duty 2` runs both in one message,
 *      comma forms still fine; `chN` at the start (or again mid-message)
 *      scopes every following voice command of THAT message to channel
 *      N - it never keys off another channel, never moves the sound
 *      over, and the gate inlet stays ch1):
 *        note <midi> [vel]    key on; vel 0 = key off (the channel's
 *                             enable bit cleared - the 2A03 has no
 *                             release stage, silence is instantaneous).
 *                             vel scales the channel's volume REGISTER
 *                             (0-15) - the voice layer drives constant
 *                             volume, silicon-faithful: the sums and the
 *                             per-channel outlets both follow it.  ch5
 *                             has no pitched voice: note restarts the
 *                             DPCM sample ($4015 bit 4)
 *        gate <0/1>           key hold; inlet 2 float = gate of ch1
 *                             (per-channel release = `chN gate 0`);
 *                             gate 1 re-keys the channel with its
 *                             current registers (length reload +
 *                             sequencer restart)
 *        duty <0-3>           pulse duty 12.5/25/50/75 %
 *        sweep <rate> <shift> [neg]   pulse sweep: rate 0 disables;
 *                             negate 1 = up (pulse 1 uses the silicon's
 *                             ones'-complement adder, pulse 2 two's)
 *        mode <0/1>           noise LFSR mode: 0 = long (32767 steps),
 *                             1 = short (93/31 steps)
 *        reg <r> <v>          raw register write, verbatim (implies raw
 *                             mode)
 *        clock <Hz>           CPU clock (1789773 NTSC default,
 *                             1662607 PAL - swaps the noise/DPCM rate
 *                             tables and every pitch conversion)
 *        gain <f>             output gain (default 1)
 *        reset                power-on state (silent again)
 *        status               per-channel state dump to the console
 *
 *   A bare float on inlet 1 = `note <midi>` on ch1 (the 2A03 cannot
 *   slide pitch without re-keying, so unlike opl3~ this retriggers).
 *
 * Outlets (all signal): 0 = the chip's own mix (the nesdev non-linear
 * DAC equation, lookup-table form - NOT a linear sum of the channels),
 * 1..5 = channels 1-5 alone.  The chip's DACs are unipolar (0..15,
 * DPCM 0..127); the per-channel outlets have the DC pedestal removed
 * and peak at +-0.25, so the five channels sum to the mix's full
 * scale.  Pedestal details: pulses by duty density, triangle by its
 * 7.5 mean, noise by the LIVE LFSR orbit's bit0-clear density (exact
 * 0.5 in long mode; in short mode it depends on the entry point -
 * measured 0.828 / 0.484 - so the core computes it at every $400E
 * write; the old fixed half-volume pedestal parked short-mode output
 * on the negative rail), plus a 3 ms fade on the noise outlet's gate
 * edges (length expiry / key-off cut from a random level = a broadband
 * click the console's analog stage would have softened).  DPCM is
 * AC-coupled (one-pole ~1.5 Hz highpass - the console's own output
 * stage is AC-coupled too): the DAC level feeds through regardless of
 * the $4015 enable bit (nesdev: "the output level is sent to the mixer
 * whether the channel is enabled or not", power-up level 0), and a
 * held level (idle unit, ended sample) fades out instead of
 * pedestal-stepping - no enable-boundary clicks.  NO output clip
 * anywhere: the silicon has none, attenuate before dac~ (same
 * convention as ym2612~ since 0.17.82).
 *
 * Integration details:
 *   - the core runs at CPU-cycle granularity: each output sample
 *     advances clock/sr cycles and averages the channel values across
 *     that window (the ideal Nyquist-safe downsample of the chip's
 *     step waveforms).
 *   - idle skip: before the first register write (or voice note) the
 *     reset APU is provably silent and perform outputs zeros without
 *     running the core (the 0.17.75 xrun lesson).
 *   - sibling-chip selectors a shared vgm~ outlet also carries
 *     (psg/gg/ay/opl) are ignored silently - error spam would flood
 *     playback.
 *
 * Register map cheat sheet (indices = $4000-$401F minus $4000):
 *   0x00/0x04 pulse duty+volume   0x01/0x05 sweep
 *   0x02/0x06 timer low           0x03/0x07 timer high + length load
 *   0x08 tri linear ctrl          0x0a/0x0b tri timer
 *   0x0c noise volume             0x0e noise mode+period
 *   0x0f noise length load        0x10 DPCM rate/loop/irq
 *   0x11 DPCM direct DAC          0x12/0x13 DPCM addr/length
 *   0x15 enables (bit 4 restarts DPCM)   0x17 frame counter
 */

#include "m_pd.h"
#include <math.h>
#include <string.h>
#include <stdio.h>
#include "nes_apu.h"

#define NES_NOUT 6              /* 0 = mix, 1..5 = channels */

/* per-channel outlet scale: five bipolarized channels sum to the mix's
 * full scale (~1.0), so each peaks at 1/4 */
#define NES_FULL 0.25f

/* ch5 AC-coupling: one-pole highpass corner.  Low enough to keep DPCM
 * kick sweeps intact (their energy sits at a few Hz), fast enough that
 * a held DAC level fades away instead of pedestal-stepping. */
#define NES_DPCM_HP_HZ 1.5

/* ch4 gate de-click: fade length in seconds */
#define NES_NOISE_FADE_S 0.003f

typedef struct _nes {
    t_object  obj;
    t_outlet *x_out[NES_NOUT];
    t_sample *x_outv[NES_NOUT]; /* dsp: outlet signal vectors, refreshed
                                 * by dsp() - passed to dsp_add as ONE
                                 * pointer (the old 3-arg shape) so the
                                 * arg count can never drift from the
                                 * perform routine again */
    float     x_sr;
    double    x_clock;          /* CPU clock Hz (1789773 NTSC) */
    double    x_cpi;            /* CPU cycles per output sample */
    double    x_cacc;           /* cycle phase accumulator */
    float     x_gain;
    int       x_keyed;          /* any register write / voice note seen:
                                 * idle-skip gate */
    int       x_rawmode;        /* reg/nes stream owns the level (status
                                 * flag; the chip's registers own
                                 * everything either way) */
    int       x_gate[NES_NCH];  /* voice-layer gate shadow per channel */
    int       x_voiced[NES_NCH];/* voice_init ran on the channel */
    float     x_hp, x_hp_x;     /* ch5 AC-coupling one-pole state */
    int       x_hp_prime;       /* first-sample prime (no startup thump) */
    float     x_hp_r;           /* highpass coefficient (sr-dependent) */
    float     x_nzfade;         /* ch4 gate fade 0..1 (de-click) */
    nes_apu   x_apu;
} t_nes;

static t_class *nes_class;

/* ------------------------------------------------------------- control */

static void nes_write(t_nes *x, int reg, int val) {
    nes_apu_write(&x->x_apu, reg, val);
    x->x_keyed = 1;
}

/* first voice use of a channel: flush a sane default instrument.  The
 * chip powers up with all volumes/enables zero - a virgin channel is
 * mute even when keyed; only voice messages trigger this, raw `nes`
 * writes never do, keeping the reset values raw patches depend on
 * (opl3~'s 0.17.115 voice_init lesson, 2A03 edition). */
static void nes_voice_init(t_nes *x, int ch) {
    int en;
    if (x->x_voiced[ch]) return;
    x->x_voiced[ch] = 1;
    switch (ch) {
    case NES_CH_P1: case NES_CH_P2:
        nes_write(x, ch * 4 + 0, (x->x_apu.regs[ch * 4 + 0] & 0xc0)
                  | 0x3f);            /* duty kept, halt+constant, vol 15 */
        nes_write(x, ch * 4 + 1, 0);  /* sweep off */
        break;
    case NES_CH_TR:
        nes_write(x, 0x08, 0xff);     /* halt linear counter (sustain) */
        break;
    case NES_CH_NZ:
        nes_write(x, 0x0c, 0x3f);     /* halt+constant, vol 15 */
        nes_write(x, 0x0e, (x->x_apu.regs[0x0e] & 0x80) | 4);
        break;
    default:                          /* DPCM: nothing pitched to init */
        break;
    }
    en = x->x_apu.enable | (1 << ch);
    nes_write(x, 0x15, en);
}

/* midi -> Hz -> 11-bit timer value for one channel; clamps at the chip's
 * own ends (returns via *tp) */
static void nes_midi_period(t_nes *x, int ch, float midi, int *tp) {
    double f = 440.0 * pow(2.0, ((double)midi - 69.0) / 12.0);
    double t;
    if (f < 1.0) f = 1.0;
    t = x->x_clock / ((ch == NES_CH_TR ? 32.0 : 16.0) * f) - 1.0;
    if (t < 0) t = 0;
    if (t > 0x7ff) t = 0x7ff;
    *tp = (int)(t + 0.5);
}

/* one voice note (or ch5 DPCM restart); vel 0 = key off */
static void nes_gate_ch(t_nes *x, int ch, t_float f);
static void nes_note_ch(t_nes *x, int ch, int argc, t_atom *argv) {
    float p, v = 127.0f;
    int vol, en;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "nes~: note expects <midi> [vel]");
        return;
    }
    p = atom_getfloat(&argv[0]);
    if (argc >= 2 && argv[1].a_type == A_FLOAT) v = atom_getfloat(&argv[1]);
    if (v < 0) v = 0;
    if (v > 127) v = 127;
    if (v <= 0) {                   /* note-off: enable bit cleared - the
                                     * 2A03 has no release stage */
        nes_gate_ch(x, ch, 0);      /* fwd decl below */
        return;
    }
    vol = (int)(v * 15.0f / 127.0f + 0.5f);
    if (vol < 1) vol = 1;
    if (vol > 15) vol = 15;
    nes_voice_init(x, ch);
    en = x->x_apu.enable;
    switch (ch) {
    case NES_CH_P1: case NES_CH_P2: {
        int t, r = ch * 4;
        nes_midi_period(x, ch, p, &t);
        nes_write(x, r + 0, (x->x_apu.regs[r + 0] & 0xc0) | 0x30 | vol);
        nes_write(x, r + 2, t & 0xff);
        nes_write(x, r + 3, 0x80 | (t >> 8));   /* length load + sequencer
                                                 * + envelope restart */
        en |= 1 << ch;
        break;
    }
    case NES_CH_TR: {
        int t;
        nes_midi_period(x, ch, p, &t);
        nes_write(x, 0x0a, t & 0xff);
        nes_write(x, 0x0b, 0x80 | (t >> 8));
        en |= 0x04;
        break;
    }
    case NES_CH_NZ: {
        int idx = ((int)p - 24) / 7;    /* midi -> the 16 noise period
                                         * entries (about a fifth apart) */
        if (idx < 0) idx = 0;
        if (idx > 15) idx = 15;
        nes_write(x, 0x0c, (x->x_apu.regs[0x0c] & 0xe0) | 0x30 | vol);
        nes_write(x, 0x0e, (x->x_apu.regs[0x0e] & 0x80) | idx);
        nes_write(x, 0x0f, 0x80);       /* length load + env restart */
        en |= 0x08;
        break;
    }
    default:                            /* ch5: no pitched voice - the
                                         * note restarts the sample */
        en |= 0x10;
        break;
    }
    nes_write(x, 0x15, en);
    x->x_gate[ch] = 1;
}

static void nes_gate_ch(t_nes *x, int ch, t_float f) {
    int g = (f != 0);
    int en = x->x_apu.enable;
    if (g == x->x_gate[ch] && !g) return;   /* repeated gate 0: no-op */
    if (g) nes_voice_init(x, ch);
    if (g) en |= (1 << ch);
    else    en &= ~(1 << ch);
    nes_write(x, 0x15, en);
    if (g) {
        /* re-key: reload the channel's length via its own key register
         * (pulses: sequencer + envelope restart too) */
        switch (ch) {
        case NES_CH_P1: case NES_CH_P2:
            nes_write(x, ch * 4 + 3, x->x_apu.regs[ch * 4 + 3] | 0x80);
            break;
        case NES_CH_TR:
            nes_write(x, 0x0b, x->x_apu.regs[0x0b] | 0x80);
            break;
        case NES_CH_NZ:
            nes_write(x, 0x0f, x->x_apu.regs[0x0f] | 0x80);
            break;
        default:
            nes_write(x, 0x15, en | 0x10);  /* DPCM: restart the sample */
            break;
        }
    }
    x->x_gate[ch] = g;
}

/* reg <r> <v> / nes <r> <v>: one raw register write, verbatim (implies
 * raw mode - the stream's own registers own the level from here) */
static void nes_reg(t_nes *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc < 2 || argv[0].a_type != A_FLOAT
        || argv[1].a_type != A_FLOAT) {
        pd_error(x, "nes~: %s expects <reg 0-31> <value 0-255>",
                 s ? s->s_name : "reg");
        return;
    }
    x->x_rawmode = 1;
    nes_write(x, (int)atom_getfloat(&argv[0]),
              (int)atom_getfloat(&argv[1]));
}

static void nes_gain(t_nes *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        float g = atom_getfloat(argv);
        x->x_gain = g < 0 ? 0 : g;
    }
}

static void nes_clock(t_nes *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        double c = atom_getfloat(argv);
        if (c < 1000.0) c = NES_CPU_NTSC;
        x->x_clock = c;
        nes_apu_set_clock(&x->x_apu, c);
        if (x->x_sr > 1.0f) x->x_cpi = c / (double)x->x_sr;
    }
}

/* power-on state: silent again, voice state cleared */
static void nes_reset(t_nes *x) {
    int c;
    nes_apu_reset(&x->x_apu);
    x->x_keyed = 0;
    x->x_rawmode = 0;
    x->x_hp_prime = 0;              /* re-prime the ch5 highpass */
    x->x_nzfade = 0;
    for (c = 0; c < NES_NCH; c++) {
        x->x_gate[c] = 0;
        x->x_voiced[c] = 0;
    }
}

/* ------------------------------------------------------------- status */

static void nes_status(t_nes *x) {
    int c;
    double hz;
    post("nes~ status: keyed=%d rawmode=%d clock=%.0f sr=%.0f gain=%.3f "
         "enable=%02x", x->x_keyed, x->x_rawmode, x->x_clock,
         (double)x->x_sr, (double)x->x_gain, x->x_apu.enable);
    for (c = 0; c < 2; c++) {
        nes_apu_pulse *p = &x->x_apu.pulse[c];
        hz = x->x_clock / (16.0 * (p->period + 1));
        post("  ch%d pulse: gate=%d en=%d len=%d duty=%d vol=%d env=%d "
             "const=%d t=%d f=%.1f sweep en=%d P=%d neg=%d shift=%d "
             "div=%d", c + 1, x->x_gate[c], p->enabled, p->length,
             p->duty, p->vol, p->env_level, p->constant, p->period, hz,
             p->sweep_en, p->sweep_period, p->sweep_neg, p->sweep_shift,
             p->sweep_div);
    }
    hz = x->x_clock / (32.0 * (x->x_apu.tri.period + 1));
    post("  ch3 tri:   gate=%d en=%d len=%d ctrl=%d lin=%d t=%d f=%.1f",
         x->x_gate[NES_CH_TR], x->x_apu.tri.enabled, x->x_apu.tri.length,
         x->x_apu.tri.control, x->x_apu.tri.linear, x->x_apu.tri.period,
         hz);
    post("  ch4 noise: gate=%d en=%d len=%d mode=%d idx=%d vol=%d env=%d "
         "dens=%.3f",
         x->x_gate[NES_CH_NZ], x->x_apu.noise.enabled,
         x->x_apu.noise.length, x->x_apu.noise.mode,
         x->x_apu.noise.period_idx, x->x_apu.noise.vol,
         x->x_apu.noise.env_level, x->x_apu.noise.density);
    post("  ch5 dpcm:  gate=%d en=%d rate=%d addr=%02x len=%02x "
         "left=%d cur=%04x delta=%d loop=%d silence=%d",
         x->x_gate[NES_CH_DP], x->x_apu.dpcm.enabled,
         x->x_apu.dpcm.rate, x->x_apu.dpcm.addr_reg,
         x->x_apu.dpcm.len_reg, x->x_apu.dpcm.bytes_left,
         x->x_apu.dpcm.cur_addr, x->x_apu.dpcm.delta,
         x->x_apu.dpcm.loop, x->x_apu.dpcm.silence);
    post("  frame: mode=%d inhibit=%d nq=%d cnt=%d irq=%d",
         x->x_apu.fc_mode, x->x_apu.fc_inhibit, x->x_apu.nq,
         x->x_apu.fc_cnt, x->x_apu.fc_irq);
}

/* ------------------------------------------------- command stream spec */

/* "chN" (N = 1..5) scope token: returns the channel (0-based) or -1
 * when the word is not a scope */
static int nes_scope(const char *name) {
    if (name[0] == 'c' && name[1] == 'h' && name[2] >= '1'
        && name[2] <= '5' && name[3] == 0)
        return name[2] - '1';
    return -1;
}

/* one command consumed from a message's token stream; ch = the scope
 * channel the message set (0 = bare, always ch1).  Returns the number
 * of argument atoms consumed, or -1 when the word is not a command.
 * Malformed arguments consume the whole rest (one clear error, no
 * cascading) - the chain stops there. */
static int nes_command(t_nes *x, int ch, t_symbol *s, int argc,
                       t_atom *argv) {
    const char *name = s->s_name;
    if (!strcmp(name, "note")) {
        int na = 0;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            na = 1;
            if (argc >= 2 && argv[1].a_type == A_FLOAT) na = 2;
        }
        if (!na) {
            pd_error(x, "nes~: note expects <midi> [vel]");
            return argc;
        }
        nes_note_ch(x, ch, na, argv);
        return na;
    }
    if (!strcmp(name, "gate")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            nes_gate_ch(x, ch, atom_getfloat(argv));
            return 1;
        }
        pd_error(x, "nes~: gate expects 0/1");
        return argc;
    }
    if (!strcmp(name, "duty")) {
        int v;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            v = (int)atom_getfloat(argv);
            if (v < 0) v = 0;
            if (v > 3) v = 3;
            if (ch <= NES_CH_P2) {
                nes_voice_init(x, ch);
                nes_write(x, ch * 4 + 0,
                          (x->x_apu.regs[ch * 4 + 0] & 0x3f) | (v << 6));
            } else {
                pd_error(x, "nes~: duty applies to the pulse channels");
            }
            return 1;
        }
        pd_error(x, "nes~: duty expects 0-3");
        return argc;
    }
    if (!strcmp(name, "sweep")) {
        int rate = 0, shift = 0, neg = 0;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            rate = (int)atom_getfloat(&argv[0]) & 7;
            if (argc >= 2 && argv[1].a_type == A_FLOAT) {
                shift = (int)atom_getfloat(&argv[1]) & 7;
                if (argc >= 3 && argv[2].a_type == A_FLOAT)
                    neg = atom_getfloat(&argv[2]) != 0;
            }
            if (ch <= NES_CH_P2) {
                nes_voice_init(x, ch);
                nes_write(x, ch * 4 + 1, (rate ? 0x80 : 0) | (rate << 4)
                          | (neg << 3) | shift);
            } else {
                pd_error(x, "nes~: sweep applies to the pulse channels");
            }
            return (argc >= 3 && argv[2].a_type == A_FLOAT) ? 3
                 : ((argc >= 2 && argv[1].a_type == A_FLOAT) ? 2 : 1);
        }
        pd_error(x, "nes~: sweep expects <rate 0-7> <shift 0-7> [neg 0/1]");
        return argc;
    }
    if (!strcmp(name, "mode")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            if (ch == NES_CH_NZ) {
                nes_voice_init(x, ch);
                nes_write(x, 0x0e, (atom_getfloat(argv) != 0 ? 0x80 : 0)
                          | (x->x_apu.regs[0x0e] & 0x0f));
            } else {
                pd_error(x, "nes~: mode applies to the noise channel");
            }
            return 1;
        }
        pd_error(x, "nes~: mode expects 0/1");
        return argc;
    }
    if (!strcmp(name, "reg") || !strcmp(name, "nes")) {
        if (argc >= 2 && argv[0].a_type == A_FLOAT &&
            argv[1].a_type == A_FLOAT) {
            nes_reg(x, s, 2, argv);   /* raw write: register addresses are
                       * absolute, a chN scope cannot redirect them */
            return 2;
        }
        pd_error(x, "nes~: %s expects <reg 0-31> <value 0-255>", name);
        return argc;
    }
    if (!strcmp(name, "clock")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            nes_clock(x, s, 1, argv);
            return 1;
        }
        pd_error(x, "nes~: clock expects <Hz>");
        return argc;
    }
    if (!strcmp(name, "gain")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            nes_gain(x, s, 1, argv);
            return 1;
        }
        pd_error(x, "nes~: gain expects a number");
        return argc;
    }
    if (!strcmp(name, "reset")) {
        nes_reset(x);
        return 0;
    }
    if (!strcmp(name, "status")) {
        nes_status(x);
        return 0;
    }
    return -1;
}

/* anything on inlet 1.  A message is a COMMAND STREAM: every word is a
 * command that consumes its own arguments, so `note 60 100 duty 2` =
 * play the note then set the duty, all in one message - the `a \, b`
 * comma forms still work (a comma just starts a new message).  `chN` at
 * the start (or again mid-message) scopes every following voice command
 * of THIS message to channel N - per the msg spec that only says "these
 * commands go to channel N": it never keys off another channel, never
 * moves the sound over, and the gate inlet stays channel 1 (per-channel
 * release = `chN gate 0` / `chN note <midi> 0`).  Bare spellings always
 * mean channel 1; clock/gain/reg/nes/reset are object-global (a scope
 * prefix is accepted but has no per-channel meaning for them). */
static void nes_anything(t_nes *x, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    int ch = 0, i = 0;
    t_symbol *cs = s;
    if (!strcmp(name, "list")) {
        /* OSC bridges deliver "note 60 100" as a list; re-dispatch
         * symbol-first lists, plain numbers are note <midi> on ch1 */
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            nes_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
            return;
        }
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            t_atom av[1];
            SETFLOAT(av, atom_getfloat(&argv[0]));
            nes_note_ch(x, 0, 1, av);
        }
        return;
    }
    if (!strcmp(name, "psg") || !strcmp(name, "gg") || !strcmp(name, "ay")
        || !strcmp(name, "opl")) {
        return;   /* sn76489~/AY8910/OPL3 bytes on the same vgm~ outlet */
    }
    for (;;) {
        const char *cn = cs->s_name;
        int sc = nes_scope(cn);
        if (sc >= 0) {
            ch = sc;
            if (i >= argc) return;    /* bare chN: scope set, nothing to run */
            if (argv[i].a_type != A_SYMBOL) {
                pd_error(x, "nes~: ch%d: expected a command", ch + 1);
                return;
            }
            cs = atom_getsymbol(&argv[i]);
            i++;
            continue;
        }
        {
            int used = nes_command(x, ch, cs, argc - i, argv + i);
            if (used < 0) {
                pd_error(x, "nes~: unknown message %s", cn);
                return;
            }
            i += used;
            if (i >= argc) return;
            if (argv[i].a_type != A_SYMBOL) {
                pd_error(x, "nes~: expected a command, got a number");
                return;
            }
            cs = atom_getsymbol(&argv[i]);
            i++;
        }
    }
}

static void nes_float(t_nes *x, t_float f) {   /* inlet 1: note on ch1
                             * (the 2A03 cannot slide pitch without
                             * re-keying) */
    t_atom av[1];
    SETFLOAT(av, f);
    nes_note_ch(x, 0, 1, av);
}

static void nes_ft1(t_nes *x, t_float f) {     /* inlet 2: gate = ch1 - the
                             * gate inlet is a bare spelling, and bare
                             * spellings always mean channel 1 (per-channel
                             * release = `chN gate 0` / `chN note 0`) */
    nes_gate_ch(x, 0, f);
}

/* ---------------------------------------------------------------- dsp */

/* one output sample: advance clock/sr CPU cycles, averaging every
 * channel's value across the window (the ideal Nyquist-safe downsample
 * of the chip's step waveforms).  Outlet 0 = the nesdev non-linear mix
 * of the chip's own DAC values; outlets 1..5 = the channels alone with
 * their DC pedestal removed. */
static t_int *nes_perform(t_int *w) {
    t_nes *x = (t_nes *)(w[1]);
    t_sample **outv = (t_sample **)w[2];
    t_sample *out[NES_NOUT];
    int n = (int)(w[3]);
    int k, s, c;
    for (k = 0; k < NES_NOUT; k++) out[k] = outv[k];
    for (s = 0; s < n; s++) {
        double acc[NES_NCH];
        double mixacc = 0.0;
        int cyc;
        for (c = 0; c < NES_NCH; c++) acc[c] = 0.0;
        if (!x->x_keyed) {
            /* No register write has ever reached this object: the APU
             * sits at reset (all volumes/enables zero), so rendering
             * would produce exactly zero.  Skip the core entirely -
             * idle vgm tunes with a nes~ attached cost nothing. */
            for (k = 0; k < NES_NOUT; k++) out[k][s] = 0;
            continue;
        }
        x->x_cacc += x->x_cpi;
        cyc = (int)x->x_cacc;
        x->x_cacc -= cyc;
        if (cyc < 1) cyc = 1;
        for (k = 0; k < cyc; k++) {
            nes_apu_cycle(&x->x_apu);
            acc[0] += x->x_apu.pulse[0].val;
            acc[1] += x->x_apu.pulse[1].val;
            acc[2] += x->x_apu.tri.val;
            acc[3] += x->x_apu.noise.val;
            acc[4] += x->x_apu.dpcm.val;
            mixacc += nes_apu_pulse_mix(x->x_apu.pulse[0].val
                                        + x->x_apu.pulse[1].val)
                    + nes_apu_tnd_mix(3 * x->x_apu.tri.val
                                      + 2 * x->x_apu.noise.val
                                      + x->x_apu.dpcm.val);
        }
        {
            double inv = 1.0 / cyc;
            float g = x->x_gain;
            /* per-channel bipolarization: remove the DAC pedestal (the
             * nesdev DACs are unipolar 0..15 / 0..127) and gate by the
             * channel's own gate conditions, so a silenced channel
             * reads 0 instead of the negative rail (device test 0.17.
             * 118: a reset idle triangle sat at -0.25 without this) */
            float dcp[NES_NCH];
            int act[NES_NCH];
            for (c = 0; c < 2; c++) {
                nes_apu_pulse *p = &x->x_apu.pulse[c];
                float lvl = p->constant ? (float)p->vol
                          : (float)p->env_level;
                dcp[c] = lvl * nes_apu_duty_density(p->duty) / 8.0f;
                act[c] = (p->length > 0 && !nes_apu_pulse_muted(&x->x_apu, c));
            }
            dcp[2] = 7.5f;
            act[2] = (x->x_apu.tri.linear > 0 && x->x_apu.tri.length > 0);
            /* noise pedestal = volume x the LIVE LFSR orbit's bit0-clear
             * density (device test 0.17.119: the fixed half-volume
             * pedestal parked short-mode output ~83% of the time on the
             * negative rail - the short orbit's density depends on its
             * entry point, the core recomputes it at every $400E write) */
            dcp[3] = (float)((x->x_apu.noise.constant
                              ? (float)x->x_apu.noise.vol
                              : (float)x->x_apu.noise.env_level)
                             * x->x_apu.noise.density);
            act[3] = 1;             /* the gate rides the de-click fade
                                     * (fgt reaches exactly 0) */
            act[4] = 1;             /* the DPCM level is always live - the
                                     * AC coupling below owns the idle */
            for (c = 0; c < NES_NCH; c++) {
                float v;
                if (c <= 1) v = (float)(acc[c] * inv - dcp[c]) / 15.0f;
                else if (c == 2)
                    v = (float)(acc[c] * inv - dcp[c]) / 7.5f;
                else if (c == 3) {
                    /* gate edges fade 3 ms: length expiry / key-off cut
                     * the LFSR from a random level - a hard cut is a
                     * broadband click the console's analog stage would
                     * have softened */
                    float fgt = x->x_nzfade;
                    if (x->x_apu.noise.length > 0) {
                        fgt += 1.0f / (NES_NOISE_FADE_S * x->x_sr);
                        if (fgt > 1.0f) fgt = 1.0f;
                    } else {
                        fgt -= 1.0f / (NES_NOISE_FADE_S * x->x_sr);
                        if (fgt < 0.0f) fgt = 0.0f;
                    }
                    x->x_nzfade = fgt;
                    v = ((float)(acc[c] * inv - dcp[c]) / 7.5f) * fgt;
                } else {
                    /* ch5: the DPCM DAC level is always live (nesdev:
                     * "the output level is sent to the mixer whether
                     * the channel is enabled or not", power-up 0) - the
                     * old $4015-enable gate parked an enabled-but-silent
                     * unit on the -0.25 rail and hard-clicked at every
                     * enable boundary (device test 0.17.119).  AC-couple
                     * instead: one-pole ~1.5 Hz highpass - the console's
                     * own output stage is AC-coupled too - so a held
                     * level (idle unit, ended sample) fades out instead
                     * of pedestal-stepping, and $4011 direct loads
                     * (VGM-log drums) still show. */
                    float d5 = (float)(acc[4] * inv - 63.5f) / 63.5f;
                    float y;
                    if (!x->x_hp_prime) {
                        /* prime for steady state: constant input must
                         * output exactly 0, so the highpass state starts
                         * at zero, not at the input level (0.17.119
                         * self-test: seeding it with the input made the
                         * first keyed sample thump -0.25 and decay) */
                        x->x_hp = 0.0f;
                        x->x_hp_x = d5;
                        x->x_hp_prime = 1;
                        y = 0.0f;
                    } else {
                        y = d5 - x->x_hp_x + x->x_hp_r * x->x_hp;
                        x->x_hp_x = d5;
                        x->x_hp = y;
                    }
                    v = y;
                }
                out[c + 1][s] = act[c] ? v * NES_FULL * g
                                       : (t_sample)0;
            }
            out[0][s] = (t_sample)(mixacc * inv * (double)g);
        }
    }
    return (w + 4);
}

static void nes_dsp(t_nes *x, t_signal **sp) {
    int k;
    x->x_sr = sp[0]->s_sr > 1.0f ? sp[0]->s_sr : 48000.0f;
    x->x_cpi = x->x_clock / (double)x->x_sr;
    x->x_hp_r = expf(-2.0f * (float)M_PI * NES_DPCM_HP_HZ / x->x_sr);
    for (k = 0; k < NES_NOUT; k++) x->x_outv[k] = sp[k]->s_vec;
    dsp_add(nes_perform, 3, x, x->x_outv, sp[0]->s_n);
}

/* ------------------------------------------------------------ lifecycle */

static void *nes_new(void) {
    t_nes *x = (t_nes *)pd_new(nes_class);
    int c;
    x->x_sr = 48000.0f;
    x->x_clock = NES_CPU_NTSC;
    x->x_cpi = NES_CPU_NTSC / 48000.0;
    x->x_cacc = 0.0;
    x->x_gain = 1.0f;
    x->x_keyed = 0;
    x->x_rawmode = 0;
    x->x_hp = 0.0f;
    x->x_hp_x = 0.0f;
    x->x_hp_prime = 0;
    x->x_hp_r = expf(-2.0f * (float)M_PI * NES_DPCM_HP_HZ / 48000.0f);
    x->x_nzfade = 0.0f;
    for (c = 0; c < NES_NCH; c++) {
        x->x_gate[c] = 0;
        x->x_voiced[c] = 0;
    }
    nes_apu_init(&x->x_apu, 0, 0);  /* no memory callback: nes~'s DPCM
                                     * sample RAM is empty (VGM logs
                                     * cannot upload sample data) */
    {   /* outlet 0 first = leftmost: the chip mix; 1..5 = channels */
        int i;
        for (i = 0; i < NES_NOUT; i++)
            x->x_out[i] = outlet_new(&x->obj, &s_signal);
    }
    inlet_new(&x->obj, &x->obj.ob_pd, &s_float, gensym("ft1"));
    return (void *)x;
}

void nes_tilde_setup(void) {
    nes_class = class_new(gensym("nes~"), (t_newmethod)nes_new,
                          0, sizeof(t_nes), 0, A_NULL);
    class_addfloat(nes_class, nes_float);
    class_addmethod(nes_class, (t_method)nes_ft1, gensym("ft1"), A_FLOAT,
                    A_NULL);
    class_addanything(nes_class, nes_anything);
    class_addmethod(nes_class, (t_method)nes_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
}
