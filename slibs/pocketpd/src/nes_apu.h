/* nes_apu - RP2A03 (NES) audio processing unit, from scratch in C.
 *
 * Written for PocketPd against the nesdev.org APU documentation (frame
 * counter, envelope, sweep, length counter, triangle, noise LFSR, DPCM)
 * - no third-party code vendored.  The core runs at CPU-cycle
 * granularity (1 cycle = 1 run(1) call) so both consumers share one
 * implementation:
 *   nes~    renders every channel from its own register state (its DPCM
 *           sample RAM is empty - VGM logs cannot upload sample data)
 *   nsf~    runs the same core as the CPU's register/readback model
 *           ($4015 status reads, DPCM fetching through the CPU memory
 *           callback, DPCM delta rendered on its own signal outlet)
 *
 * Deviations from raw silicon (documented on purpose):
 *   - $4017 writes reset the frame counter immediately (silicon takes
 *     3-4 CPU cycles to sync; inaudible)
 *   - DPCM byte fetches do not steal CPU cycles (silicon stalls the CPU
 *     4 cycles per fetch; only matters for engines that self-time
 *     against DPCM bus contention - none seen in NSF files)
 *   - the frame counter's half-cycle step offsets (29828.5 etc.) are
 *     rounded to whole CPU cycles
 *
 * Register space: indices 0x00-0x1F = CPU addresses $4000-$401F (exactly
 * what VGM command 0xBC carries and what nsf~ snoops).
 */

#ifndef nes_apu_h
#define nes_apu_h

/* CPU clock constants (the wrappers' frequency law + table selection) */
#define NES_CPU_NTSC 1789773.0
#define NES_CPU_PAL  1662607.0
#define NES_RATE_NTSC 60.0988    /* frame counter / play-call rate, Hz */
#define NES_RATE_PAL  50.0069

/* channels, in the order the voice layer and the outlets use them */
#define NES_CH_P1 0              /* ch1 pulse 1   */
#define NES_CH_P2 1              /* ch2 pulse 2   */
#define NES_CH_TR 2              /* ch3 triangle  */
#define NES_CH_NZ 3              /* ch4 noise     */
#define NES_CH_DP 4              /* ch5 DPCM      */
#define NES_NCH 5

typedef struct nes_apu_pulse {
    int duty;                    /* $4000 bits 7-6 */
    int vol;                     /* $4000 bits 3-0 */
    int halt;                    /* $4000 bit 5 (== envelope loop) */
    int constant;                /* $4000 bit 4 */
    int sweep_en;                /* $4001 bit 7 */
    int sweep_period;            /* $4001 bits 6-4 */
    int sweep_neg;               /* $4001 bit 3 */
    int sweep_shift;             /* $4001 bits 2-0 */
    int sweep_div, sweep_reload, sweep_mute;
    int env_start, env_div, env_level;
    int length;                  /* length counter (0-255) */
    int period;                  /* 11-bit timer register */
    int timer;                   /* CPU-cycle countdown */
    int seq;                     /* 8-step duty position */
    int enabled;                 /* $4015 bit */
    int val;                     /* current output, 0..15 (unipolar) */
} nes_apu_pulse;

typedef struct nes_apu_tri {
    int control;                 /* $4008 bit 7 (linear counter halt) */
    int linear_reload;           /* $4008 bits 6-0 */
    int linear, linear_flag;
    int length, enabled;
    int period;                  /* 11-bit timer register */
    int timer;                   /* CPU-cycle countdown (2 CPU cycles/step) */
    int seq;                     /* 32-step position */
    int val;
} nes_apu_tri;

typedef struct nes_apu_noise {
    int vol, halt, constant;
    int env_start, env_div, env_level;
    int mode;                    /* $400E bit 7: 1 = short ("periodic") LFSR */
    int period_idx;              /* $400E bits 3-0 */
    int length, enabled;
    int timer;
    unsigned lfsr;               /* 15-bit, seeded 1 */
    double density;              /* bit0-clear density of the CURRENT LFSR
                                  * orbit: exactly 0.5 in mode 0 (the orbit
                                  * covers all 32767 nonzero states), but in
                                  * mode 1 it depends on the entry point
                                  * (measured 0.828 / 0.484 on different
                                  * orbits) - the wrappers subtract it as the
                                  * channel's DC pedestal; recomputed at every
                                  * $400E write (the orbit never changes while
                                  * the mode flag holds - writes never reset
                                  * the LFSR) */
    int val;
} nes_apu_noise;

typedef struct nes_apu_dpcm {
    int rate;                    /* $4010 bits 3-0 */
    int loop;                    /* $4010 bit 6 */
    int irq_en;                  /* $4010 bit 7 */
    int addr_reg, len_reg;       /* $4012 / $4013 raw */
    int enabled;                 /* $4015 bit 4 */
    int cur_addr, bytes_left;    /* sample cursor */
    int timer; int bits;         /* bit clock + bits left in bytebuf */
    unsigned char bytebuf;
    int silence;                 /* sample ended, no loop */
    int delta;                   /* 7-bit DAC counter, 0..127 */
    int irq;                     /* $4015 read bit 7 */
    int val;
    /* sample fetches ride the CPU memory map (nsf~); nes~ passes 0 =
     * empty RAM, every fetch reads 0 */
    unsigned char (*memread)(void *user, unsigned int addr);
    void *user;
} nes_apu_dpcm;

typedef struct nes_apu {
    nes_apu_pulse pulse[2];
    nes_apu_tri tri;
    nes_apu_noise noise;
    nes_apu_dpcm dpcm;
    int enable;                  /* $4015 write shadow */
    int fc_mode, fc_inhibit;     /* $4017 bits 7/6 */
    int fc_irq;                  /* frame interrupt flag ($4015 read bit 6) */
    int fc_cnt;                  /* CPU cycles since the last (re)start */
    double fc_qnext;             /* next quarter-frame boundary, CPU cycles */
    int nq;                      /* quarter steps taken within this frame */
    unsigned char regs[0x20];    /* write shadows, index = reg 0x00-0x1F */
    double clock;                /* CPU clock Hz (NTSC default) */
} nes_apu;

void nes_apu_init(nes_apu *a, unsigned char (*memread)(void *, unsigned int),
                  void *user);
void nes_apu_reset(nes_apu *a);
void nes_apu_set_clock(nes_apu *a, double hz);
void nes_apu_write(nes_apu *a, int reg, int val);
int  nes_apu_read(nes_apu *a);            /* $4015 status; clears IRQ flags */
void nes_apu_run(nes_apu *a, int cycles); /* advance N CPU cycles */
void nes_apu_cycle(nes_apu *a);           /* advance exactly 1 CPU cycle */

/* the 2A03's non-linear mixer (nesdev APU Mixer lookup-table form):
 * out = pulse_table[p1+p2] + tnd_table[3*tri + 2*noise + dpcm], the
 * chip's own full-scale mix (~1.0); indices pre-clamped by the callers */
double nes_apu_pulse_mix(int p1p2);
double nes_apu_tnd_mix(int tnd);

/* duty pattern density in eighths (1/2/4/6) - the wrappers use it to
 * remove the duty-dependent DC pedestal from per-channel outlets */
int nes_apu_duty_density(int duty);

/* live sweep/period mute state of pulse ch (0/1): period < 8 or the
 * continuously-computed sweep target overflows $7FF (nesdev APU Sweep
 * "muting" section) */
int nes_apu_pulse_muted(nes_apu *a, int ch);

#endif /* nes_apu_h */
