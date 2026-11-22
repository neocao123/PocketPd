/* sidreplay.h - PSID basic replay for sid~: PSID header parse, C64 memory
 * map with the IO traps a basic replay needs (VIC raster, CIA timers,
 * SID bus), and the init/play scheduling at the tune's own rate.
 *
 * Facts pinned against libsidplay2/libsidplayfp (both GPL, referenced for
 * semantics only, no code copied):
 *   - PSID header fields are BIG-endian; the @0x12 speed word carries one
 *     bit per subtune (bit 0 = first tune): 1 = CIA timer, 0 = VBlank.
 *   - init is called with A = X = Y = subtune - 1, $00 = 0x2F, $01 = 0x37
 *     (BASIC + KERNAL + IO visible), flags I set.
 *   - VBI tunes play at the video frame rate (PAL 19656 chip cycles =
 *     50.1 Hz, NTSC 17095 = 59.8 Hz); CIA tunes program their own timer -
 *     a write to the CIA1 timer latches re-derives the play rate
 *     (multispeed tunes ride the same path).
 *   - RSID tunes play through their own interrupt handlers, which a
 *     basic replay cannot run: rejected with a clear error instead.
 */
#ifndef POCKETPD_SIDREPLAY_H
#define POCKETPD_SIDREPLAY_H

#include "sid6502.h"

#define SREP_PAL_CLOCK  985248
#define SREP_NTSC_CLOCK 1022730
#define SREP_PAL_FRAME  19656.0    /* 312 lines * 63 cycles */
#define SREP_NTSC_FRAME 17095.0    /* 263 lines * 65 cycles */

typedef struct {
    void (*chip_write)(void *ud, int reg, int val);  /* $D400-$D41C */
    int  (*chip_read)(void *ud, int reg);            /* $D400-$D41E  */
    void *chip_ud;
    void (*log)(void *ud, const char *msg);          /* one-shot notes */
    void *log_ud;
} sidreplay_hooks;

typedef struct {
    sid6502 cpu;
    unsigned char mem[65536];
    unsigned char sid_regs[0x19]; /* SID register shadow: writes MUST read
                                   * back (tunes read-modify-write gate
                                   * bits); reSID's databus TTL ages to
                                   * zero within a few ms, which would
                                   * corrupt an RMW a frame later */
    sidreplay_hooks h;

    /* parsed header */
    int version;                 /* 1..4 */
    int songs, default_song;
    int init_addr, play_addr;
    int clock_hz;                /* SREP_PAL_CLOCK / SREP_NTSC_CLOCK */
    int model;                   /* 0 = MOS6581, 1 = MOS8580 */
    int speed_bit;               /* selected subtune: 1 = CIA, 0 = VBI */
    char name[33], author[33], released[33];

    /* replay state */
    int loaded, playing, paused, inited, dead;
    int subtune;
    double interval;             /* chip cycles between play calls */
    double due;                  /* cycles until the next play call */
    double pos;                  /* chip cycles since (re)start */
    long frames;                 /* play calls since (re)start */

    /* fake hardware */
    unsigned char vic[0x40];     /* VIC shadow (reads of written regs) */
    unsigned char cia[16];       /* CIA1 latches + control shadow */
    long rticks;                 /* fake raster clock (chip cycles) */
    int raster;                  /* 0..line count */
    int idle_note;               /* one-shot notes already emitted */
} sidreplay;

/* hooks must be set before load; chip hooks are mandatory, log optional */
void sidreplay_init(sidreplay *sr, const sidreplay_hooks *h);

/* parse + load + select a subtune (1-based); returns 0 on success or a
 * negative error id (the caller prints its own message):
 *  -1 not a PSID / bad version   -2 truncated
 *  -3 no play address (IRQ-only / RSID)   -4 MUS data unsupported
 *  -5 does not fit in C64 memory          -6 unknown version */
int sidreplay_load(sidreplay *sr, const unsigned char *d, unsigned long len,
                   int subtune);

void sidreplay_play(sidreplay *sr);   /* start or resume */
void sidreplay_pause(sidreplay *sr);
void sidreplay_stop(sidreplay *sr);   /* halt + rewind + re-arm init */

/* advance the replay by dt chip cycles (SID clock domain); register
 * writes ride the chip_write hook as they happen */
void sidreplay_advance(sidreplay *sr, double dt);

#endif /* POCKETPD_SIDREPLAY_H */
