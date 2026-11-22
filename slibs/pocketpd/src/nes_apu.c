/* nes_apu - RP2A03 (NES) audio processing unit, written from scratch in
 * C for PocketPd against the nesdev.org APU documentation (APU Pulse /
 * Triangle / Noise / DMC / Envelope / Sweep / Frame Counter / Mixer and
 * Length Counter pages, fetched and followed verbatim during this
 * implementation).  No third-party code vendored.  See nes_apu.h for the
 * shared design (nes~ renders, nsf~ uses it as the CPU readback/DPCM
 * model) and the documented silicon deviations.
 */

#include "nes_apu.h"
#include <string.h>
#include <math.h>

/* ------------------------------------------------- hardware constants */

/* duty waveform, indexed by the 8 sequencer steps (nesdev APU Pulse) */
static const unsigned char duty_wave[4][8] = {
    {0, 1, 0, 0, 0, 0, 0, 0},   /* 12.5% */
    {0, 1, 1, 0, 0, 0, 0, 0},   /* 25%   */
    {0, 1, 1, 1, 1, 0, 0, 0},   /* 50%   */
    {1, 0, 0, 1, 1, 1, 1, 1},   /* 25% negated */
};

int nes_apu_duty_density(int duty) {
    static const int dens[4] = {1, 2, 4, 6};    /* eighths */
    return (duty < 0 || duty > 3) ? 2 : dens[duty];
}

/* length counter load table (nesdev APU Length Counter), indexed by the
 * top 5 bits of a $4003/$4007/$400B/$400F write */
static const int len_table[32] = {
    10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 320, 10, 60, 14, 24, 12,
    48, 30, 96, 24, 192, 48, 72, 16, 360, 56, 2, 64, 384, 100, 1, 128
};

/* noise timer periods in CPU cycles (nesdev APU Noise) */
static const int noise_ntsc[16] = {
    4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016,
    2034, 4068
};
static const int noise_pal[16] = {
    4, 8, 14, 30, 60, 88, 118, 148, 188, 236, 354, 472, 708, 944,
    1890, 3778
};

/* DPCM rate periods in CPU cycles per output-level change (nesdev APU
 * DMC pitch table) */
static const int dpcm_ntsc[16] = {
    428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106,
    84, 72, 54
};
static const int dpcm_pal[16] = {
    398, 354, 316, 298, 276, 236, 210, 198, 176, 148, 132, 118, 98,
    78, 66, 50
};

/* the triangle's 32-step sequence (nesdev APU Triangle) */
static const int tri_seq[32] = {
    15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
};

/* the non-linear mixer, lookup-table form (nesdev APU Mixer):
 * pulse_table[n] = 95.52 / (8128/n + 100), n = pulse1+pulse2 (0..30)
 * tnd_table[n]   = 163.67 / (24329/n + 100), n = 3*tri + 2*noise + dmc
 * (0..202, the numerators slightly adjusted to keep the range 0..1) */
static double pulse_mixtab[31];
static double tnd_mixtab[203];
static int mixtab_ready = 0;

static void mixtab_build(void) {
    int n;
    pulse_mixtab[0] = 0.0;
    for (n = 1; n <= 30; n++)
        pulse_mixtab[n] = 95.52 / (8128.0 / n + 100.0);
    tnd_mixtab[0] = 0.0;
    for (n = 1; n <= 202; n++)
        tnd_mixtab[n] = 163.67 / (24329.0 / n + 100.0);
    mixtab_ready = 1;
}

double nes_apu_pulse_mix(int p1p2) {
    if (!mixtab_ready) mixtab_build();
    if (p1p2 < 0) p1p2 = 0;
    if (p1p2 > 30) p1p2 = 30;
    return pulse_mixtab[p1p2];
}

double nes_apu_tnd_mix(int tnd) {
    if (!mixtab_ready) mixtab_build();
    if (tnd < 0) tnd = 0;
    if (tnd > 202) tnd = 202;
    return tnd_mixtab[tnd];
}

/* ------------------------------------------------------- frame counter */

/* quarter/half boundary: quarter = frame_cycles/4 (7457.4 NTSC / 8312
 * PAL cycles - the PAL frame counter runs at 200 Hz, not 240); the
 * nesdev integer table (7457/14913/22371/29829 NTSC) is within a cycle
 * of the cumulative schedule - inaudible, simpler per-region */
static void fc_restart(nes_apu *a) {
    double rate = (a->clock < 1700000.0) ? NES_RATE_PAL : NES_RATE_NTSC;
    a->fc_cnt = 0;
    a->nq = 0;
    a->fc_qnext = a->clock / (4.0 * rate);
}

/* --------------------------------------------------- channel event clocks */

static void env_clock(int *start, int *div, int *level, int loop,
                      int period) {
    if (*start) {
        *start = 0;
        *level = 15;
        *div = period + 1;      /* divider period = V + 1 quarter frames */
    } else if (*div) {
        (*div)--;
    } else {
        *div = period;
        if (*level) (*level)--;
        else if (loop) *level = 15;
    }
}

/* nesdev APU Sweep: on a half-frame clock
 *   1. if the divider is zero AND enabled AND shift != 0 AND not
 *      muting: period = target period
 *   2. if the divider is zero OR the reload flag: divider = P, reload
 *      flag cleared; otherwise decremented
 * Muting is a CONTINUOUS condition (period < 8, or target > $7FF),
 * evaluated in the output path - no sticky flag, matching the wiki's
 * "until the condition ends" semantics. */
static void sweep_clock(nes_apu *a, nes_apu_pulse *p, int ch) {
    if (p->sweep_div == 0 && p->sweep_en && p->sweep_shift) {
        int delta = p->period >> p->sweep_shift;
        int target = p->sweep_neg
            ? (p->period - delta - (ch == 0))   /* pulse 1: ones' compl. */
            : (p->period + delta);              /* pulse 2: two's compl. */
        if (target < 0) target = 0;
        /* the update is skipped while the channel is muted (either
         * continuous condition: period < 8 or target overflow) */
        if (p->period >= 8 && target <= 0x7ff)
            p->period = target;
    }
    if (p->sweep_div == 0 || p->sweep_reload) {
        p->sweep_div = p->sweep_period;
        p->sweep_reload = 0;
    } else {
        p->sweep_div--;
    }
}

/* live mute/target for one pulse (continuous conditions) */
static int pulse_muted(nes_apu *a, nes_apu_pulse *p, int ch) {
    int delta, target;
    if (p->period < 8) return 1;
    delta = p->period >> p->sweep_shift;
    target = p->sweep_neg
        ? (p->period - delta - (ch == 0))
        : (p->period + delta);
    if (target < 0) target = 0;
    return target > 0x7ff;
}

static void apu_quarter(nes_apu *a) {
    nes_apu_pulse *p;
    int i;
    for (i = 0; i < 2; i++) {
        p = &a->pulse[i];
        env_clock(&p->env_start, &p->env_div, &p->env_level, p->halt,
                  p->vol);
    }
    env_clock(&a->noise.env_start, &a->noise.env_div,
              &a->noise.env_level, a->noise.halt, a->noise.vol);
    /* triangle linear counter (nesdev APU Triangle, in order) */
    if (a->tri.linear_flag)
        a->tri.linear = a->tri.linear_reload;
    else if (a->tri.linear > 0)
        a->tri.linear--;
    if (!a->tri.control)
        a->tri.linear_flag = 0;
}

static void apu_half(nes_apu *a) {
    int i;
    for (i = 0; i < 2; i++)
        sweep_clock(a, &a->pulse[i], i);
    for (i = 0; i < 4; i++) {
        int *len, halt;
        switch (i) {
        case 0: len = &a->pulse[0].length; halt = a->pulse[0].halt; break;
        case 1: len = &a->pulse[1].length; halt = a->pulse[1].halt; break;
        case 2: len = &a->tri.length;      halt = a->tri.control;  break;
        default: len = &a->noise.length;   halt = a->noise.halt;   break;
        }
        if (!halt && *len > 0) (*len)--;
    }
}

/* bit0-clear density of the noise LFSR's current orbit (see the header
 * field comment).  Mode 0: the orbit is all 32767 nonzero states, half
 * of which have bit0 clear - exact 0.5.  Mode 1: the short cycle's
 * density depends on where the shift register was when the mode flag
 * was set (nesdev: "randomly 93 or 31 steps long ... depends on where
 * in the 32767-step sequence the shift register was"); simulate the
 * orbit from the live state once per mode write. */
static void noise_density(nes_apu_noise *n) {
    if (!n->mode) {
        n->density = 0.5;
        return;
    }
    {
        unsigned s = n->lfsr & 0x7fff;
        unsigned first = s;
        int zeros = 0, steps = 0;
        do {
            if (!(s & 1)) zeros++;
            s = ((s >> 1) | (((s & 1) ^ ((s >> 6) & 1)) << 14)) & 0x7fff;
            steps++;
        } while (s != first && steps < 32767);
        n->density = (steps > 0) ? (double)zeros / steps : 0.5;
    }
}

/* ------------------------------------------------------------ DPCM unit */

static void dpcm_restart(nes_apu *a) {
    nes_apu_dpcm *d = &a->dpcm;
    /* nesdev APU DMC: sample address = $C000 + (A * 64); length =
     * (L * 16) + 1 bytes; the counter wraps $FFFF -> $8000 */
    d->cur_addr = 0xc000 + (d->addr_reg << 6);
    d->bytes_left = (d->len_reg << 4) + 1;
    /* the silence flag is NOT cleared here - the output unit leaves
     * silence only at the end of an output cycle, once a byte has been
     * fetched into it (nesdev: "the DPCM unit can only transition from
     * silent to playing at the end of an output cycle") */
}

static void dpcm_timer(nes_apu *a) {
    nes_apu_dpcm *d = &a->dpcm;
    const int *tab = (a->clock < 1700000.0) ? dpcm_pal : dpcm_ntsc;
    d->timer = tab[d->rate & 15];
    /* output unit, nesdev order: level change per shift-register bit 0
     * (clamped into 0..127), shift, bits--; an output cycle that ends
     * reloads 8 bits from the buffer (silence when the buffer is
     * empty) - the unit can only leave silence at a cycle end */
    if (!d->silence) {
        if (d->bytebuf & 1) {
            if (d->delta <= 125) d->delta += 2;
        } else {
            if (d->delta >= 2) d->delta -= 2;
        }
        d->bytebuf >>= 1;
    }
    d->bits--;                      /* <= 0 also catches the power-up
                                     * zero: the first timer clock ends
                                     * that (empty) output cycle */
    if (d->bits <= 0) {
        d->bits = 8;
        /* nesdev output-unit rule at output-cycle end: "if the sample
         * buffer is empty, then the silence flag is set; otherwise ...
         * emptied into the shift register".  No memory reader (memread
         * NULL - nes~'s sample RAM is empty, VGM logs cannot upload
         * sample data) means the buffer can never fill: the unit stays
         * silent instead of phantom-playing zero bytes, so an enabled
         * channel produces no output-level movement and no outlet
         * pedestals.  $4011 direct loads still move the level (the
         * silicon's always-live DAC). */
        if (d->bytes_left > 0 && d->memread) {
            d->bytebuf = d->memread(d->user, d->cur_addr);
            d->cur_addr++;
            if (d->cur_addr > 0xffff) d->cur_addr = 0x8000;
            d->bytes_left--;
            d->silence = 0;
            if (d->bytes_left == 0) {
                if (d->loop) dpcm_restart(a);
                else if (d->irq_en) d->irq = 1;
            }
        } else {
            d->silence = 1;
        }
    }
}

/* ----------------------------------------------------------- one cycle */

void nes_apu_cycle(nes_apu *a) {
    int i;
    /* frame counter: quarter clocks at clock/240; quarters 2/4 are also
     * half clocks; mode 0's 4th step also arms the frame IRQ and wraps
     * (nesdev APU Frame Counter integer table, rounded to the cycle) */
    a->fc_cnt++;
    if ((double)a->fc_cnt >= a->fc_qnext) {
        double rate = (a->clock < 1700000.0) ? NES_RATE_PAL : NES_RATE_NTSC;
        a->nq++;
        a->fc_qnext += a->clock / (4.0 * rate);
        apu_quarter(a);
        if (a->nq == 2 || a->nq == 4) apu_half(a);
        if ((a->fc_mode && a->nq == 5) ||
            (!a->fc_mode && a->nq == 4)) {
            if (!a->fc_mode && !a->fc_inhibit) a->fc_irq = 1;
            fc_restart(a);
        }
    }
    /* timers: pulse timers tick every APU cycle (2 CPU cycles, period
     * t+1), triangle at the CPU rate (period t+1), noise/DPCM count
     * CPU cycles against their region tables */
    if (!(a->fc_cnt & 1)) {
        for (i = 0; i < 2; i++) {
            nes_apu_pulse *p = &a->pulse[i];
            /* the pulse timer ticks once per APU cycle and reloads with
             * t (period = t+1 APU cycles = 16*(t+1) CPU per 8 steps) */
            if (--p->timer < 0) {
                p->timer = p->period;
                p->seq = (p->seq + 1) & 7;
            }
        }
    }
    if (--a->tri.timer < 0) {
        a->tri.timer = a->tri.period;
        if (a->tri.linear > 0 && a->tri.length > 0)
            a->tri.seq = (a->tri.seq + 1) & 31;
    }
    if (--a->noise.timer <= 0) {
        const int *tab = (a->clock < 1700000.0) ? noise_pal : noise_ntsc;
        unsigned fb = (a->noise.lfsr & 1)
            ^ ((a->noise.mode ? (a->noise.lfsr >> 6)
                              : (a->noise.lfsr >> 1)) & 1);
        a->noise.timer = tab[a->noise.period_idx & 15];
        a->noise.lfsr = (a->noise.lfsr >> 1) | (fb << 14);
    }
    if (--a->dpcm.timer <= 0) dpcm_timer(a);
    /* output values (nesdev mixer gates) */
    for (i = 0; i < 2; i++) {
        nes_apu_pulse *p = &a->pulse[i];
        int lvl = p->constant ? p->vol : p->env_level;
        p->val = (p->length > 0 && !pulse_muted(a, p, i)
                  && duty_wave[p->duty & 3][p->seq]) ? lvl : 0;
    }
    a->tri.val = (a->tri.linear > 0 && a->tri.length > 0)
        ? tri_seq[a->tri.seq] : 0;
    a->noise.val = (a->noise.length > 0 && !(a->noise.lfsr & 1))
        ? (a->noise.constant ? a->noise.vol : a->noise.env_level) : 0;
    /* the DMC output level feeds the mixer whether enabled or not */
    a->dpcm.val = a->dpcm.delta;
}

int nes_apu_pulse_muted(nes_apu *a, int ch) {
    if (ch < 0 || ch > 1) return 1;
    return pulse_muted(a, &a->pulse[ch], ch);
}

void nes_apu_run(nes_apu *a, int cycles) {
    while (cycles-- > 0) nes_apu_cycle(a);
}

/* ---------------------------------------------------------- registers */

void nes_apu_write(nes_apu *a, int reg, int val) {
    nes_apu_pulse *p;
    if (reg < 0 || reg > 0x1f) return;
    val &= 0xff;
    a->regs[reg] = (unsigned char)val;
    switch (reg) {
    case 0x00: case 0x04:               /* $4000/$4004 */
        p = &a->pulse[(reg == 0x04)];
        p->duty = (val >> 6) & 3;
        p->halt = (val >> 5) & 1;
        p->constant = (val >> 4) & 1;
        p->vol = val & 15;
        break;
    case 0x01: case 0x05:               /* $4001/$4005: sweep, sets reload */
        p = &a->pulse[(reg == 0x05)];
        p->sweep_en = (val >> 7) & 1;
        p->sweep_period = (val >> 4) & 7;
        p->sweep_neg = (val >> 3) & 1;
        p->sweep_shift = val & 7;
        p->sweep_reload = 1;
        break;
    case 0x02: case 0x06:               /* $4002/$4006: timer low; the
                                         * period divider is NOT reset */
        p = &a->pulse[(reg == 0x06)];
        p->period = (p->period & 0x700) | val;
        break;
    case 0x03: case 0x07:               /* $4003/$4007: timer high + length
                                         * load; sequencer + envelope
                                         * restart, divider untouched */
        p = &a->pulse[(reg == 0x07)];
        p->period = (p->period & 0x0ff) | ((val & 7) << 8);
        p->length = len_table[(val >> 3) & 31];
        p->seq = 0;
        p->env_start = 1;
        break;
    case 0x08:                          /* $4008: linear counter setup */
        a->tri.control = (val >> 7) & 1;
        a->tri.linear_reload = val & 0x7f;
        a->tri.linear_flag = 1;
        break;
    case 0x0a:                          /* $400A: triangle timer low */
        a->tri.period = (a->tri.period & 0x700) | val;
        break;
    case 0x0b:                          /* $400B: timer high + length load;
                                         * sets the reload flag; the timer
                                         * is reloaded immediately unless
                                         * the sequencer sits at step 0 */
        a->tri.period = (a->tri.period & 0x0ff) | ((val & 7) << 8);
        a->tri.length = len_table[(val >> 3) & 31];
        a->tri.linear_flag = 1;
        if (a->tri.seq != 0) a->tri.timer = a->tri.period;
        break;
    case 0x0c:                          /* $400C */
        a->noise.halt = (val >> 5) & 1;
        a->noise.constant = (val >> 4) & 1;
        a->noise.vol = val & 15;
        break;
    case 0x0e:                          /* $400E: mode + period index (the
                                         * LFSR is NOT reset by writes) */
        a->noise.mode = (val >> 7) & 1;
        a->noise.period_idx = val & 15;
        noise_density(&a->noise);       /* orbit only changes with the mode
                                         * flag, but recompute on every write
                                         * - always correct, costs <= 32767
                                         * steps once per register write */
        break;
    case 0x0f:                          /* $400F: length load + envelope
                                         * restart */
        a->noise.length = len_table[(val >> 3) & 31];
        a->noise.env_start = 1;
        break;
    case 0x10:                          /* $4010: IRQ enable (clearing it
                                         * clears the interrupt flag),
                                         * loop, rate */
        a->dpcm.rate = val & 15;
        a->dpcm.loop = (val >> 6) & 1;
        a->dpcm.irq_en = (val >> 7) & 1;
        if (!a->dpcm.irq_en) a->dpcm.irq = 0;
        break;
    case 0x11:                          /* $4011: direct 7-bit load */
        a->dpcm.delta = val & 0x7f;
        break;
    case 0x12: a->dpcm.addr_reg = val; break;
    case 0x13: a->dpcm.len_reg = val; break;
    case 0x15: {                        /* $4015: enables; clearing a bit
                                         * zeroes the length counter,
                                         * setting bit 4 restarts DPCM */
        int i;
        a->enable = val & 0x1f;
        for (i = 0; i < 4; i++) {
            int en = (val >> i) & 1;
            if (i == 0) a->pulse[0].enabled = en;
            else if (i == 1) a->pulse[1].enabled = en;
            else if (i == 2) a->tri.enabled = en;
            else a->noise.enabled = en;
            if (!en) {
                if (i == 0) a->pulse[0].length = 0;
                else if (i == 1) a->pulse[1].length = 0;
                else if (i == 2) a->tri.length = 0;
                else a->noise.length = 0;
            }
        }
        if (val & 0x10) {
            a->dpcm.enabled = 1;
            dpcm_restart(a);
        } else {
            a->dpcm.enabled = 0;
            a->dpcm.bytes_left = 0;
        }
        break;
    }
    case 0x17:                          /* $4017: mode + IRQ inhibit; mode
                                         * 1 clocks a quarter + half
                                         * immediately (deviation: the
                                         * silicon's 3-4 cycle sync delay
                                         * is applied instantly) */
        a->fc_mode = (val >> 7) & 1;
        a->fc_inhibit = (val >> 6) & 1;
        if (a->fc_inhibit) a->fc_irq = 0;
        fc_restart(a);
        if (a->fc_mode) {
            apu_quarter(a);
            apu_half(a);
        }
        break;
    default:                            /* $4009/$400D/$4014/$4016...:
                                         * shadows only */
        break;
    }
}

int nes_apu_read(nes_apu *a) {
    /* $4015 status: length counters, DMC bytes remaining, IRQ flags;
     * reading clears the FRAME flag only (nesdev APU DMC) */
    int v = 0;
    if (a->pulse[0].length > 0) v |= 1;
    if (a->pulse[1].length > 0) v |= 2;
    if (a->tri.length > 0) v |= 4;
    if (a->noise.length > 0) v |= 8;
    if (a->dpcm.bytes_left > 0) v |= 0x10;
    if (a->fc_irq) v |= 0x40;
    if (a->dpcm.irq) v |= 0x80;
    a->fc_irq = 0;
    return v;
}

/* ------------------------------------------------------------ lifecycle */

void nes_apu_set_clock(nes_apu *a, double hz) {
    a->clock = (hz < 1000.0) ? NES_CPU_NTSC : hz;
    fc_restart(a);
}

void nes_apu_reset(nes_apu *a) {
    unsigned char (*mr)(void *, unsigned int) = a->dpcm.memread;
    void *user = a->dpcm.user;
    double clock = a->clock;
    memset(a, 0, sizeof(*a));
    a->dpcm.memread = mr;
    a->dpcm.user = user;
    a->clock = clock;
    a->noise.lfsr = 1;                  /* power-up value (nesdev APU
                                         * Noise); the LFSR is never
                                         * reset by register writes */
    a->noise.density = 0.5;             /* mode 0 orbit = exactly half */
    nes_apu_set_clock(a, clock);
}

void nes_apu_init(nes_apu *a, unsigned char (*memread)(void *, unsigned int),
                  void *user) {
    memset(a, 0, sizeof(*a));
    a->dpcm.memread = memread;
    a->dpcm.user = user;
    nes_apu_reset(a);
}
