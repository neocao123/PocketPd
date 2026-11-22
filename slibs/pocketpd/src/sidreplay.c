/* sidreplay.c - the PSID basic replay (see sidreplay.h). */

#include "sidreplay.h"
#include <stdio.h>
#include <string.h>

/* init/play guards: init routines may unpack data or wait for frames
 * (both run to completion here); play routines are a few hundred cycles.
 * A trip means the tune never returned - stop it and say so once. */
#define SREP_INIT_STEPS  5000000L
#define SREP_PLAY_STEPS  100000L

static void srep_note(sidreplay *sr, const char *msg) {
    if (sr->h.log) sr->h.log(sr->h.log_ud, msg);
}

/* ---------------------------------------------------------- fake VIC */

static void srep_raster(sidreplay *sr) {
    int lines, ll;
    if (sr->clock_hz == SREP_NTSC_CLOCK) { lines = 263; ll = 65; }
    else                                 { lines = 312; ll = 63; }
    sr->raster = (int)((sr->rticks / ll) % lines);
}

/* raster ticks advance whenever memory is touched by the CPU (lazy
 * update from the instruction cycle count) and in advance() idle
 * slices (nothing is running - the tune waits for its interrupt) */
static void srep_rtick(sidreplay *sr, double dt) {
    sr->rticks += (long)dt;
    srep_raster(sr);
}

/* -------------------------------------------------------- memory map */

static unsigned char srep_rd(void *ud, int addr) {
    sidreplay *sr = (sidreplay *)ud;
    addr &= 0xffff;
    if (addr < 0xD000)
        return sr->mem[addr];
    if (addr < 0xD400) {                       /* VIC-II */
        int off = addr & 0x3f;
        srep_rtick(sr, 0);                     /* keep the line fresh */
        if (off == 0x12)
            return (unsigned char)(sr->raster & 0xff);
        if (off == 0x11)
            return (unsigned char)((sr->vic[0x11] & 0x7f) |
                                   ((sr->raster >> 8) & 1) << 7);
        return sr->vic[off];
    }
    if (addr < 0xD420) {                       /* SID: written registers
                                                * read back from the
                                                * shadow; paddles / OSC3 /
                                                * ENV3 (-) come
                                                * from the chip side */
        int off = addr & 0x1f;
        if (off <= 0x18) return sr->sid_regs[off];
        return (unsigned char)(sr->h.chip_read ?
                               sr->h.chip_read(sr->h.chip_ud, off) : 0);
    }
    if (addr < 0xDC00)                         /* SID mirrors / holes:
                                                * untrapped IO floats */
        return 0xff;
    if (addr < 0xDD00) {                       /* CIA1 */
        int off = addr & 0x0f;
        switch (off) {
        case 0x04: return sr->cia[4];          /* timer A latch (the
                                                * countdown is not run -
                                                * the LATCH satisfies the
                                                * busy-wait loops) */
        case 0x05: return sr->cia[5];
        case 0x06: return sr->cia[6];
        case 0x07: return sr->cia[7];
        case 0x0D: return 0;                   /* ICR: no pending IRQ */
        case 0x0E: return (unsigned char)(sr->cia[0x0E] | 0x01);
        case 0x0F: return (unsigned char)(sr->cia[0x0F] | 0x01);
        }
        return sr->cia[off];
    }
    if (addr < 0xDE00)                         /* CIA2 shadow */
        return 0xff;                           /* nobody reads it here */
    return 0xff;                               /* floating IO bus */
}

static void srep_cia_rate(sidreplay *sr) {
    /* the play rate follows the started timer's latch (a CIA tune's own
     * init programs it - classic tunes write CC7 = 50 Hz PAL); the
     * floor and the 16-bit range keep nonsense values out */
    double latch = 0;
    if (sr->cia[0x0E] & 0x01)
        latch = ((sr->cia[4] | (sr->cia[5] << 8)) + 1);
    else if (sr->cia[0x0F] & 0x01)
        latch = ((sr->cia[6] | (sr->cia[7] << 8)) + 1);
    /* the timer underflows once per latch+1 PHI2 cycles: the INTERVAL
     * in chip cycles is latch+1 directly (985248/19656 = 50.125 Hz PAL) */
    if (latch >= 50.0)
        sr->interval = latch;
}

static void srep_wr(void *ud, int addr, int v) {
    sidreplay *sr = (sidreplay *)ud;
    addr &= 0xffff;
    v &= 0xff;
    if (addr < 0xD000) {
        sr->mem[addr] = (unsigned char)v;
        return;
    }
    if (addr < 0xD400) {                       /* VIC-II */
        sr->vic[addr & 0x3f] = (unsigned char)v;
        return;
    }
    if (addr < 0xD420) {                       /* SID registers */
        int off = addr & 0x1f;
        if (off <= 0x18) {
            sr->sid_regs[off] = (unsigned char)v;
            if (sr->h.chip_write)
                sr->h.chip_write(sr->h.chip_ud, off, v);
        }
        return;
    }
    if (addr < 0xDC00)                         /* SID mirrors / holes */
        return;
    if (addr < 0xDD00) {                       /* CIA1 */
        int off = addr & 0x0f;
        if (off == 0x0D) return;               /* ICR is write-to-clear */
        sr->cia[off] = (unsigned char)v;
        if (off == 0x04 || off == 0x05 || off == 0x0E ||
            off == 0x06 || off == 0x07 || off == 0x0F)
            srep_cia_rate(sr);
        return;
    }
    /* CIA2 and the rest of the IO space: silently dropped */
}

/* --------------------------------------------------------------- load */

/* 32-byte header string -> printable NUL-terminated copy */
static void srep_str(char *out, const unsigned char *in) {
    int i, last = -1;
    for (i = 0; i < 32; i++) {
        unsigned char c = in[i];
        out[i] = (c >= 32 && c < 127) ? (char)c : '.';
        if (c >= 32 && c < 127) last = i;
    }
    out[last + 1] = 0;
    if (last < 0) out[0] = 0;
}

static unsigned long srep_be16(const unsigned char *p) {
    return ((unsigned long)p[0] << 8) | p[1];
}

static unsigned long srep_be32(const unsigned char *p) {
    return ((unsigned long)p[0] << 24) | ((unsigned long)p[1] << 16) |
           ((unsigned long)p[2] << 8) | p[3];
}

int sidreplay_load(sidreplay *sr, const unsigned char *d, unsigned long len,
                   int subtune) {
    unsigned long dataoff, datalen, load, init, play, songs, start, speed;
    int version, is_rsid, i;
    if (len < 0x7E + 2) return -2;
    if (memcmp(d, "PSID", 4) == 0) is_rsid = 0;
    else if (memcmp(d, "RSID", 4) == 0) is_rsid = 1;
    else return -1;
    version = (int)srep_be16(d + 4);
    if (version < 1 || version > 4) return (version > 4) ? -6 : -1;

    /* RSID tunes drive playback through their own interrupt handlers -
     * nothing a basic replay can run (it must not inject IRQs: the
     * entire PSID contract rests on init/play being plain subroutines) */
    if (is_rsid) return -3;

    dataoff = srep_be16(d + 6);
    if (dataoff < 0x7C || dataoff >= len) dataoff = 0x7C;
    load  = srep_be16(d + 8);
    init  = srep_be16(d + 10);
    play  = srep_be16(d + 12);
    songs = srep_be16(d + 14);
    start = srep_be16(d + 16);
    speed = srep_be32(d + 18);

    /* v1 header: name/author/released at 22/54/86, no flags field.
     * v2+ header: flags at 22 (0x16), type 24, model 25, and the three
     * strings shifted to 26/58/90.  The old code parsed EVERYTHING at
     * the v1 offsets and read "flags" from d+118 - the END of the v1
     * header (the first bytes of the chip data!) - so v2 tunes got
     * their clock/model decided by release-string bytes and could be
     * mis-rejected as MUS.
     *
     * REAL-WORLD QUIRK: plenty of writers stamp version=2 + dataoff
     * 0x7C but keep the V1 field layout (no flags/type/model; name
     * starts at 0x16 - the whole "A Lazy Year [8580]" collection does
     * this).  Distinguish by the type/model bytes: in a conformant v2
     * they are 0/1 and 0-3; anything else = v1 layout under a v2
     * label. */
    if (version >= 2 && d[24] <= 1 && d[25] <= 3) {
        unsigned long flags = srep_be16(d + 22);
        if (flags & 0x01) return -4;           /* MUS data: other player */
        switch ((flags >> 2) & 3) {
        case 2:  sr->clock_hz = SREP_NTSC_CLOCK; break;
        case 1:  sr->clock_hz = SREP_PAL_CLOCK; break;
        default: sr->clock_hz = SREP_PAL_CLOCK; break;   /* ANY/unknown */
        }
        switch ((flags >> 4) & 3) {
        case 2:  sr->model = 1; break;         /* MOS8580 */
        case 3:  sr->model = 1; break;         /* 8580 preferred when both */
        default: sr->model = 0; break;         /* 6581 / unknown */
        }
        srep_str(sr->name,     d + 26);
        srep_str(sr->author,   d + 58);
        srep_str(sr->released, d + 90);
    } else {
        sr->clock_hz = SREP_PAL_CLOCK;
        sr->model = 0;                         /* v1: not in the file */
        srep_str(sr->name,     d + 22);
        srep_str(sr->author,   d + 54);
        srep_str(sr->released, d + 86);
    }

    if (songs < 1) songs = 1;
    if (start < 1 || start > songs) start = 1;
    if (subtune < 1 || subtune > (int)songs) subtune = (int)start;
    sr->subtune = subtune;
    sr->songs = (int)songs;
    sr->default_song = (int)start;

    datalen = len - dataoff;
    if (load == 0) {
        /* v1 convention: the first two data bytes carry the load address
         * (a PRG-style self-describing file) */
        if (datalen < 2) return -2;
        load = (unsigned long)d[dataoff] | ((unsigned long)d[dataoff + 1] << 8);
        dataoff += 2;
        datalen -= 2;
    }
    if (init == 0) init = load;
    if (play == 0) return -3;                  /* IRQ-only tune */

    /* per-subtune speed bits: bit 0 = tune 1, tune 32+ share its bit */
    sr->speed_bit = (int)((speed >> ((subtune - 1) & 31)) & 1);

    if (load + datalen > 0x10000) return -5;

    /* memory: zeroed, the ROM pages under $01=0x37 replaced with RTS
     * stubs (no C64 ROM ships with the app; anything the tune JSRs into
     * ROM returns immediately - PSID tunes must not need it anyway) */
    memset(sr->mem, 0, sizeof(sr->mem));
    memset(sr->mem + 0xA000, 0x60, 0x2000);
    memset(sr->mem + 0xE000, 0x60, 0x2000);
    sr->mem[0] = 0x2F;                         /* the 6510 DDR default */
    sr->mem[1] = 0x37;                         /* bank select: BASIC +
                                                * KERNAL + IO visible */
    memcpy(sr->mem + load, d + dataoff, datalen);

    sr->init_addr = (int)init;
    sr->play_addr = (int)play;
    sr->version = version;
    sr->loaded = 1;
    sr->inited = 0;
    sr->dead = 0;
    sr->idle_note = 0;

    /* play interval: the frame rate; CIA tunes re-derive it from their
     * own timer-latch writes (srep_cia_rate) */
    sr->interval = (sr->clock_hz == SREP_NTSC_CLOCK) ? SREP_NTSC_FRAME
                                                     : SREP_PAL_FRAME;
    sr->due = sr->interval;
    sr->pos = 0.0;
    sr->frames = 0;
    sr->rticks = 0;
    srep_raster(sr);
    memset(sr->sid_regs, 0, sizeof(sr->sid_regs));
    memset(sr->vic, 0, sizeof(sr->vic));
    memset(sr->cia, 0, sizeof(sr->cia));
    sr->cia[4] = 0xC7;                         /* the classic 50 Hz PAL
                                                * latch ($4CC7) as the
                                                * power-on default */
    sr->cia[5] = 0x4C;
    return 0;
}

/* ------------------------------------------------------------ control */

/* run one subroutine entry with A = X = Y = song-1, I set; a guard trip
 * marks the tune dead (one console note) */
static void srep_call(sidreplay *sr, int addr, long max_steps,
                      const char *what) {
    char buf[128];
    long steps;
    sr->cpu.a = sr->cpu.x = sr->cpu.y =
        (unsigned char)(sr->subtune - 1);
    sr->cpu.p = (unsigned char)(S65_I | S65_U);
    steps = sid6502_call(&sr->cpu, addr, max_steps);
    if (steps < 0) {
        sr->dead = 1;
        sr->playing = 0;
        snprintf(buf, sizeof(buf),
                 "sid~: %s routine never returned (subtune %d) - stopped",
                 what, sr->subtune);
        srep_note(sr, buf);
    }
}

void sidreplay_play(sidreplay *sr) {
    if (!sr->loaded || sr->dead) return;
    if (sr->paused) { sr->paused = 0; sr->playing = 1; return; }
    if (!sr->playing) {
        sr->pos = 0.0;
        sr->frames = 0;
        sr->inited = 0;
        sr->due = sr->interval;
        sr->playing = 1;
    }
}

void sidreplay_pause(sidreplay *sr) {
    if (sr->playing) { sr->playing = 0; sr->paused = 1; }
}

void sidreplay_stop(sidreplay *sr) {
    sr->playing = 0;
    sr->paused = 0;
    if (sr->loaded) {
        sr->pos = 0.0;
        sr->frames = 0;
        sr->inited = 0;
        sr->due = sr->interval;
        sr->dead = 0;
    }
}

/* ------------------------------------------------------------- pacing */

void sidreplay_advance(sidreplay *sr, double dt) {
    double remaining = dt;
    if (!sr->loaded || !sr->playing || sr->dead) return;
    while (remaining > 0) {
        double slice;
        if (!sr->inited) {
            srep_call(sr, sr->init_addr, SREP_INIT_STEPS, "init");
            if (sr->dead) return;
            sr->inited = 1;
        }
        if (sr->due > remaining) {
            sr->due -= remaining;
            sr->pos += remaining;
            srep_rtick(sr, remaining);
            remaining = 0;
            break;
        }
        remaining -= sr->due;
        sr->pos += sr->due;
        srep_rtick(sr, sr->due);
        sr->due = sr->interval;
        srep_call(sr, sr->play_addr, SREP_PLAY_STEPS, "play");
        if (sr->dead) return;
        sr->frames++;
    }
}

void sidreplay_init(sidreplay *sr, const sidreplay_hooks *h) {
    memset(sr, 0, sizeof(*sr));
    sr->h = *h;
    sr->clock_hz = SREP_PAL_CLOCK;
    sid6502_init(&sr->cpu, srep_rd, srep_wr, sr);
}
