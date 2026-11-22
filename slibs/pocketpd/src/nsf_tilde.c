/* nsf~ - NSF/NSFE (NES Sound Format) player.  The playback/generation
 * split follows vgm~ exactly: nsf~ owns the file, the 6502 CPU and the
 * timing, and streams every APU register write the engine makes as
 * `nes <reg> <val>` messages (outlet 0, the vgm~ 0xBC vocabulary) so a
 * nes~ renders the sound.  The one exception is the same one vgm~ has:
 * sample data cannot ride register messages, so the DPCM channel is
 * rendered HERE on its own signal outlet (outlet 1) - DPCM samples live
 * in the CPU's memory map and only this object sees them.
 *
 *   outlet 0     `nes <reg> <val>` writes, $4000-$401F verbatim except
 *                $4014 (OAM DMA is a CPU stall here, not a sound write);
 *                connect straight to nes~ (a sn76489~ on the same wire
 *                drops them silently, like every vgm~ sibling selector)
 *   outlet 1     signal: the DPCM delta counter (the chip's 7-bit DAC,
 *                bipolarized and AC-coupled, +-0.25 at full swing) - the
 *                level register is always live (nesdev: sent to the
 *                mixer whether the channel is enabled or not), so a
 *                held level (idle unit, ended sample) fades out through
 *                the coupling highpass instead of pedestal-stepping at
 *                kick/enable boundaries; silent when the tune uses no
 *                DPCM samples
 *   outlet 2     bang: end of play time reached without looping (NSFE
 *                files with per-track play lengths only - a plain NSF
 *                plays forever)
 *
 * Messages (all through the left inlet, the vgm~ set):
 *   load <file.nsf|nsfe>  open (pd search path + patch dir), track =
 *                         the header's start song
 *   play                  start (or resume after pause: the track is
 *                         re-initialized - the engine owns its register
 *                         state, so a paused stream cannot resume behind
 *                         the silencing sweep)
 *   pause                 halt the CPU + `nes 15 0` (all channels off,
 *                         the vgm~ pause keyoff sweep)
 *   stop                  halt + rewind the current track
 *   goto <n>              switch to track n (1..tracks, 1-based like the
 *                         header's start song), re-initialize
 *   loop <0/1>            single mode only: wrap at the play-length end
 *                         (default 1; loop 0 = end bang + stop)
 *   mode <m>              what happens when a track's play length runs
 *                         out: single (default, the loop message owns
 *                         it) / seqloop (wrap 1..n) / seqstop (stop
 *                         after the last) / randloop / randstop (random
 *                         unplayed tracks until all played).  Advance
 *                         modes need a finite per-track length: NSFE
 *                         time chunks, else `songtime`
 *   songtime <sec>        default per-track play length for tracks with
 *                         no NSFE time (default 120 s, 0 = forever)
 *   info                  post header/details to the console
 *
 * Emulation scope, by design: pure 2A03 files (expansion mask 0) play
 * fully.  Files with expansion chips (VRC6, FDS, MMC5, Namco 163,
 * Sunsoft 5B, VRC7) still load and play their 2A03 channels; the load
 * line and `info` say so in plain words.  The CPU is cpu6502.c (written
 * from scratch, official + common illegal opcodes, cycle-counted); the
 * APU is nes_apu.c - the same core nes~ renders from, running here as
 * the CPU's register/readback model ($4015 status reads and DPCM sample
 * fetches go through this object's memory map).  IRQs (frame counter
 * and DMC) are injected when the engines arm them.  Documented silicon
 * deviations: OAM DMA ($4014) costs a flat 513-cycle stall (no odd/even
 * adjustment), DPCM fetches steal no CPU cycles, $4016/$4017 reads
 * return 0x40 (open bus bit 6).
 *
 * Timing: the NSF play routine is called once per frame (NTSC 60.0988 /
 * PAL 50.0069 Hz per the header's video byte; the NSFE RATE chunk
 * overrides, interpreted in tenths of Hz); the CPU runs at 1789773 /
 * 1662607 Hz and the frame budget is cpu/rate cycles.  Everything runs
 * in perform at CPU-cycle granularity, so the DPCM outlet averages the
 * chip's DAC across each pd sample window (same downsample as nes~).
 */

#include "m_pd.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include "nes_apu.h"
#include "cpu6502.h"

/* file lookup shared with vgm~/ym2612~/opl3~ (search path, no canvas
 * deref) */
int ympp_findfile(const char *name, char *full, unsigned int n);

/* NSF header expansion chip bits (nesdev NSF spec) */
static const struct { int bit; const char *name; } nsf_exp[] = {
    { 0x01, "VRC6" }, { 0x02, "FDS" }, { 0x04, "MMC5" },
    { 0x08, "Namco 163" }, { 0x10, "Sunsoft 5B" }, { 0x20, "VRC7" },
};

typedef struct _nsf {
    t_object  obj;
    t_outlet *x_nesout;
    t_outlet *x_dpcmout;
    t_outlet *x_end;
    float     x_sr;
    /* file image */
    unsigned char *x_data;      /* ROM data (NSF 0x80.. / NSFE DATA) */
    unsigned long x_datalen;
    int       x_is_nsfe;
    int       x_ver, x_tracks, x_start;
    unsigned  x_load, x_init, x_play;
    int       x_banksw;         /* bankswitching (size field / BANK) */
    unsigned char x_bankseed[8];
    int       x_video;          /* 0 NTSC, 1 PAL, 2 dual */
    int       x_expansion;      /* chip mask */
    double    x_rate_ntsc, x_rate_pal;   /* NSFE RATE chunk (0 = default) */
    double   *x_times;          /* NSFE per-track play lengths, ms
                                  * (0 = that track plays forever) */
    long      x_ntimes;
    double    x_track_ms;       /* play length of the current track,
                                  * 0 = play forever */
    double    x_songtime;       /* default per-track play length in
                                  * seconds when the file has no NSFE
                                  * time for the track (advance modes
                                  * need a finite length; 120 s like the
                                  * desktop players) */
    char      x_title[64], x_artist[64], x_copyright[64];
    /* runtime state */
    unsigned char x_ram[0x800];
    unsigned char x_wram[0x2000];
    unsigned char x_bankreg[8];
    nes_apu   x_apu;
    cpu6502   x_cpu;
    double    x_cpu_clock, x_rate;   /* current region clock / frame rate */
    double    x_cpf;                 /* cycles per play frame */
    double    x_cpi, x_cacc;         /* CPU cycles per pd sample */
    long long x_framecyc;            /* cycles into the current frame */
    int       x_cyc_left;            /* cycles left of the instruction */
    int       x_playing, x_paused, x_in_call;
    long long x_callcyc;             /* cycles since the call started */
    int       x_track;               /* 1-based */
    int       x_loop;
    unsigned  x_rseed;               /* LCG state for the random modes */
    unsigned  x_played;              /* tracks auto-played this pass
                                      * (randstop bookkeeping) */
    int       x_mode;                /* playback advance mode NSF_M_* */
    double    x_ms_played;
    int       x_dma_pending;         /* $4014 OAM DMA stall */
    int       x_note;                /* one-shot console notes */
    /* outlet-1 AC coupling (same law as nes~ ch5): the DPCM DAC level is
     * always live, so the bipolarized level rides a one-pole ~1.5 Hz
     * highpass - a held level (idle unit, ended sample) fades out
     * instead of pedestal-stepping at kick/enable boundaries */
    float     x_hp, x_hp_x, x_hp_r;
    int       x_hp_prime;
    float     x_d5;                  /* last bipolarized level (frozen when
                                     * not playing - keep decaying it so a
                                     * stop/pause fades instead of clicking) */
} t_nsf;

/* playback advance modes (`mode <name>`): what happens when a track's
 * play length runs out.  `single` (default) = the old behavior: the
 * current track loops while `loop 1`, or stops with the end bang on
 * `loop 0`.  The other four advance tracks automatically - 顺序循环 /
 * 随机循环 / 播完全部就停 / 随机播完全部就停.  Advance modes need a
 * finite per-track length: NSFE time chunks when the file has them,
 * `songtime <sec>` otherwise (default 120 s). */
#define NSF_M_SINGLE   0
#define NSF_M_SEQLOOP  1
#define NSF_M_SEQSTOP  2
#define NSF_M_RANDLOOP 3
#define NSF_M_RANDSTOP 4
static const char *nsf_mode_names[5] = {
    "single", "seqloop", "seqstop", "randloop", "randstop"
};

static t_class *nsf_class;

/* ------------------------------------------------------- memory map */

static unsigned char nsf_rd(void *user, unsigned addr) {
    t_nsf *x = (t_nsf *)user;
    unsigned off;
    addr &= 0xffff;
    if (addr < 0x2000) return x->x_ram[addr & 0x7ff];
    if (addr < 0x4000) return 0;              /* PPU: none in an NSF */
    if (addr < 0x6000) {
        if (addr < 0x4020) {
            switch (addr & 0x1f) {
            case 0x15: return (unsigned char)nes_apu_read(&x->x_apu);
            case 0x16: case 0x17: return 0x40;   /* controllers / open bus */
            }
        }
        return 0;                              /* expansion regs: none */
    }
    if (addr < 0x8000) return x->x_wram[addr - 0x6000];
    off = addr - x->x_load;                    /* $8000-$FFFF: ROM */
    if (x->x_load < 0x8000) {                  /* FDS-style RAM image */
        if (addr >= x->x_load && off < x->x_datalen)
            return x->x_data[off];
        return 0xff;
    }
    if (!x->x_banksw) {
        if (addr >= x->x_load && off < x->x_datalen)
            return x->x_data[off];
        return 0xff;
    }
    {
        unsigned pg = ((addr >> 12) & 7);      /* 4 KB pages at $8000+ */
        unsigned long idx = (unsigned long)x->x_bankreg[pg] * 0x1000UL
                            + (addr & 0xfff);
        if (idx < x->x_datalen) return x->x_data[idx];
        return 0xff;                           /* unmapped: 0xFF fill */
    }
}

/* one APU register write out on the nes stream (verbatim 0xBC bytes) */
static void nsf_snoop(t_nsf *x, int reg, int val) {
    t_atom a[2];
    SETFLOAT(a, (t_float)reg);
    SETFLOAT(a + 1, (t_float)(val & 0xff));
    outlet_anything(x->x_nesout, gensym("nes"), 2, a);
}

static void nsf_wr(void *user, unsigned addr, unsigned char v) {
    t_nsf *x = (t_nsf *)user;
    addr &= 0xffff;
    if (addr < 0x2000) { x->x_ram[addr & 0x7ff] = v; return; }
    if (addr < 0x4000) {
        if (addr == 0x4014) x->x_dma_pending = 1;   /* OAM DMA stall */
        return;
    }
    if (addr < 0x4020) {
        if (addr == 0x4014) { x->x_dma_pending = 1; return; }
        if ((addr & 0x1f) != 0x16) {           /* controller strobe stays
                                                * local; the rest is sound */
            nes_apu_write(&x->x_apu, addr & 0x1f, v);
            nsf_snoop(x, addr & 0x1f, v);
        }
        return;
    }
    if (addr >= 0x5ff8 && addr <= 0x5fff) {
        x->x_bankreg[addr - 0x5ff8] = v;       /* NSF bankswitching */
        return;
    }
    if (addr < 0x8000) {
        x->x_wram[addr - 0x6000] = v;          /* $6000-$7FFF WRAM */
        return;
    }
    /* ROM: writes ignored */
}

/* ------------------------------------------------------------ load */

/* per-track cold start: RAM, banks, APU, CPU region (blargg's Nsf_Emu
 * start_track model) */
static void nsf_start_track(t_nsf *x, int track) {
    int i;
    if (track < 1) track = 1;
    if (track > x->x_tracks) track = x->x_tracks;
    x->x_track = track;
    memset(x->x_ram, 0, sizeof(x->x_ram));
    memset(x->x_wram, 0, sizeof(x->x_wram));
    if (x->x_banksw)
        memcpy(x->x_bankreg, x->x_bankseed, 8);
    else
        for (i = 0; i < 8; i++) x->x_bankreg[i] = (unsigned char)i;
    /* play length of THIS track: the NSFE time chunk when the file has
     * one, else `songtime` for the advance modes (they need a finite
     * length; single mode without either plays forever).  0.17.119 and
     * before zeroed this here and the NSFE times set at load never
     * reached the runtime - the end bang could not fire at all. */
    x->x_track_ms = (track <= x->x_ntimes) ? x->x_times[track - 1] : 0.0;
    if (x->x_track_ms <= 0.0 && x->x_mode != NSF_M_SINGLE)
        x->x_track_ms = x->x_songtime * 1000.0;
    x->x_ms_played = 0.0;

    x->x_in_call = 0;
    x->x_framecyc = 0;
    x->x_cyc_left = 0;
    x->x_dma_pending = 0;
    nes_apu_init(&x->x_apu, nsf_rd, x);
    /* region: video byte 0 = NTSC, 1 = PAL, 2 = dual (NTSC chosen) */
    x->x_cpu_clock = (x->x_video == 1) ? NES_CPU_PAL : NES_CPU_NTSC;
    x->x_rate = (x->x_video == 1)
        ? (x->x_rate_pal > 0 ? x->x_rate_pal : NES_RATE_PAL)
        : (x->x_rate_ntsc > 0 ? x->x_rate_ntsc : NES_RATE_NTSC);
    if (x->x_rate < 10.0 || x->x_rate > 100.0)
        x->x_rate = (x->x_video == 1) ? NES_RATE_PAL : NES_RATE_NTSC;
    x->x_cpf = x->x_cpu_clock / x->x_rate;
    nes_apu_set_clock(&x->x_apu, x->x_cpu_clock);
    if (x->x_sr > 1.0f) x->x_cpi = x->x_cpu_clock / (double)x->x_sr;
    /* the nesdev NSF init procedure's player-side writes, through the
     * snoop stream so a connected nes~ matches: $4000-$4013 = 0,
     * $4015 = 0 then $4015 = $0F (Battletoads/Castlevania/Gremlins 2
     * require it), $4017 = $40 (4-step, IRQ inhibit) */
    for (i = 0; i < 0x14; i++) {
        nes_apu_write(&x->x_apu, i, 0);
        nsf_snoop(x, i, 0);
    }
    nes_apu_write(&x->x_apu, 0x15, 0);
    nsf_snoop(x, 0x15, 0);
    nes_apu_write(&x->x_apu, 0x15, 0x0f);
    nsf_snoop(x, 0x15, 0x0f);
    nes_apu_write(&x->x_apu, 0x17, 0x40);
    nsf_snoop(x, 0x17, 0x40);
}

static void nsf_free(t_nsf *x) {
    if (x->x_data) { free(x->x_data); x->x_data = 0; }
    if (x->x_times) { free(x->x_times); x->x_times = 0; }
    x->x_ntimes = 0;
}

static void nsf_load(t_nsf *x, t_symbol *s) {
    char full[MAXPDSTRING];
    FILE *f;
    unsigned char *buf = 0;
    long len, got;
    int i;
    if (!s || !s->s_name || !s->s_name[0]) {
        pd_error(x, "nsf~: load expects a file name");
        return;
    }
    if (!ympp_findfile(s->s_name, full, MAXPDSTRING)) {
        pd_error(x, "nsf~: '%s' not found (pd search path)", s->s_name);
        return;
    }
    f = fopen(full, "rb");
    if (!f) {
        pd_error(x, "nsf~: cannot open '%s'", full);
        return;
    }
    fseek(f, 0, SEEK_END);
    len = ftell(f);
    fseek(f, 0, SEEK_SET);
    buf = (unsigned char *)malloc(len ? (size_t)len : 1);
    got = buf && len ? (long)fread(buf, 1, (size_t)len, f) : 0;
    fclose(f);
    if (!buf || got != len) {
        free(buf);
        pd_error(x, "nsf~: read failed on '%s'", s->s_name);
        return;
    }
    /* try NSF (NESM), then NSFE (chunked) */
    if (len >= 0x80 && !memcmp(buf, "NESM\x1a", 5)) {
        const unsigned char *h = buf;
        static const char *vid[] = { "NTSC", "PAL", "dual" };
        x->x_is_nsfe = 0;
        if (x->x_times) { free(x->x_times); x->x_times = 0; }
        x->x_ntimes = 0;
        x->x_ver = h[5];
        x->x_tracks = h[6];
        x->x_start = h[7];
        x->x_load = (unsigned)(h[8] | (h[9] << 8));
        x->x_init = (unsigned)(h[10] | (h[11] << 8));
        x->x_play = (unsigned)(h[12] | (h[13] << 8));
        /* bankswitching = any of the 8 bank-init bytes at $070-$077
         * nonzero (nesdev NSF layout; the text fields cover $00E-$06D) */
        x->x_banksw = 0;
        memset(x->x_bankseed, 0, sizeof(x->x_bankseed));
        memcpy(x->x_bankseed, h + 0x70, 8);
        for (i = 0; i < 8; i++)
            if (h[0x70 + i]) x->x_banksw = 1;
        x->x_video = (h[0x7a] & 3) <= 2 ? (h[0x7a] & 3) : 0;
        x->x_expansion = h[0x7b];
        x->x_rate_ntsc = x->x_rate_pal = 0.0;
        if (h[0x6e] | h[0x6f]) {
            double us = (double)(h[0x6e] | (h[0x6f] << 8));
            if (us > 0) x->x_rate_ntsc = 1000000.0 / us;
        }
        if (h[0x78] | h[0x79]) {
            double us = (double)(h[0x78] | (h[0x79] << 8));
            if (us > 0) x->x_rate_pal = 1000000.0 / us;
        }
        snprintf(x->x_title, sizeof(x->x_title), "%s", (const char *)h + 0x0e);
        snprintf(x->x_artist, sizeof(x->x_artist), "%s", (const char *)h + 0x2e);
        snprintf(x->x_copyright, sizeof(x->x_copyright), "%s", (const char *)h + 0x4e);
        {   /* NSF2 24-bit data length at $07D (0 = to EOF) */
            unsigned long dl = (unsigned long)h[0x7d]
                | ((unsigned long)h[0x7e] << 8)
                | ((unsigned long)h[0x7f] << 16);
            if (!dl || 0x80 + dl > (unsigned long)len)
                dl = (unsigned long)len - 0x80;
            x->x_datalen = dl;
        }
        free(x->x_data);
        x->x_data = (unsigned char *)malloc(x->x_datalen ? x->x_datalen : 1);
        if (!x->x_data) { free(buf); pd_error(x, "nsf~: out of memory"); return; }
        memcpy(x->x_data, buf + 0x80, x->x_datalen);
        post("nsf~: '%s': NSF v%d, %d song%s, start %d, load %04x init "
             "%04x play %04x, %s, %s%s", s->s_name, x->x_ver,
             x->x_tracks, x->x_tracks == 1 ? "" : "s", x->x_start,
             x->x_load, x->x_init, x->x_play,
             x->x_banksw ? "bankswitched" : "no bankswitch",
             vid[x->x_video],
             x->x_expansion ? ", expansion:" : "");
        for (i = 0; i < (int)(sizeof(nsf_exp) / sizeof(nsf_exp[0])); i++)
            if (x->x_expansion & nsf_exp[i].bit)
                post("nsf~:   expansion %s present - NOT emulated, only "
                     "the 2A03 channels will sound", nsf_exp[i].name);
    } else {
        /* NSFE: chunk stream - INFO required, DATA carries the ROM */
        unsigned long p = 0;
        int got_info = 0;
        double *times = 0;
        long ntimes = 0;
        while (p + 8 <= (unsigned long)len) {
            const unsigned char *id = buf + p;
            unsigned long sz = (unsigned long)buf[p + 4]
                | ((unsigned long)buf[p + 5] << 8)
                | ((unsigned long)buf[p + 6] << 16)
                | ((unsigned long)buf[p + 7] << 24);
            const unsigned char *d = buf + p + 8;
            if (p + 8 + sz > (unsigned long)len) break;
            p += 8 + sz;
            if (!memcmp(id, "INFO", 4) && sz >= 12) {
                x->x_is_nsfe = 1;
                x->x_ver = d[0];
                x->x_tracks = d[1];
                x->x_start = d[2];
                x->x_load = (unsigned)(d[3] | (d[4] << 8));
                x->x_init = (unsigned)(d[5] | (d[6] << 8));
                x->x_play = (unsigned)(d[7] | (d[8] << 8));
                x->x_video = d[9] <= 2 ? d[9] : 0;
                x->x_expansion = d[10] | (d[11] << 8);
                got_info = 1;
            } else if (!memcmp(id, "BANK", 4)) {
                x->x_banksw = 1;
                memset(x->x_bankseed, 0, sizeof(x->x_bankseed));
                for (i = 0; i < 8 && (unsigned long)i < sz; i++)
                    x->x_bankseed[i] = d[i];
            } else if (!memcmp(id, "DATA", 4)) {
                x->x_datalen = sz;
                free(x->x_data);
                x->x_data = (unsigned char *)malloc(sz ? sz : 1);
                if (!x->x_data) break;
                memcpy(x->x_data, d, sz);
            } else if (!memcmp(id, "RATE", 4) && sz >= 4) {
                x->x_rate_ntsc = (double)((unsigned)d[0] | (d[1] << 8))
                                 / 10.0;
                x->x_rate_pal = (double)((unsigned)d[2] | (d[3] << 8))
                                / 10.0;
            } else if (!memcmp(id, "time", 4)) {
                long nt = (long)(sz / 4);
                free(times);
                times = (double *)malloc((size_t)nt * sizeof(double));
                if (times) {
                    ntimes = nt;
                    for (i = 0; i < nt; i++)
                        times[i] = (double)((unsigned)d[i*4]
                            | ((unsigned)d[i*4+1] << 8)
                            | ((unsigned)d[i*4+2] << 16)
                            | ((unsigned)d[i*4+3] << 24));
                }
            } else if (!memcmp(id, "auth", 4)) {
                /* null-separated: title, artist, copyright, ripper */
                const char *q = (const char *)d, *e = (const char *)d + sz;
                const char *fields[4] = { 0, 0, 0, 0 };
                int nf = 0;
                while (q < e && nf < 4) {
                    fields[nf++] = q;
                    while (q < e && *q) q++;
                    q++;
                }
                if (nf > 0)
                    snprintf(x->x_title, sizeof(x->x_title), "%s", fields[0]);
                if (nf > 1)
                    snprintf(x->x_artist, sizeof(x->x_artist), "%s", fields[1]);
                if (nf > 2)
                    snprintf(x->x_copyright, sizeof(x->x_copyright), "%s",
                             fields[2]);
            } else if (!memcmp(id, "NEND", 4)) {
                break;
            }
            /* fade/plag/text/tlbl chunks: skipped */
        }
        free(buf);
        if (!got_info || !x->x_data || !x->x_datalen) {
            free(x->x_data);
            x->x_data = 0;
            free(times);
            pd_error(x, "nsf~: '%s' has no INFO/DATA chunks (not NSF/NSFE)",
                     s->s_name);
            return;
        }
        if (x->x_start < 1 || x->x_start > x->x_tracks) x->x_start = 1;
        /* per-track play lengths stay on the instance - nsf_start_track
         * picks THIS track's entry (and the advance modes ride them
         * across goto/auto-advance too) */
        if (x->x_times) { free(x->x_times); x->x_times = 0; }
        x->x_ntimes = 0;
        if (ntimes > 0 && times) {
            x->x_times = times;
            x->x_ntimes = ntimes;
        } else {
            free(times);
        }
        post("nsf~: '%s': NSFE v%d, %d song%s, start %d, load %04x init "
             "%04x play %04x, %s, %s%s", s->s_name, x->x_ver,
             x->x_tracks, x->x_tracks == 1 ? "" : "s", x->x_start,
             x->x_load, x->x_init, x->x_play,
             x->x_banksw ? "bankswitched" : "no bankswitch",
             x->x_video == 1 ? "PAL" : (x->x_video == 2 ? "dual" : "NTSC"),
             x->x_expansion ? ", expansion:" : "");
        for (i = 0; i < (int)(sizeof(nsf_exp) / sizeof(nsf_exp[0])); i++)
            if (x->x_expansion & nsf_exp[i].bit)
                post("nsf~:   expansion %s present - NOT emulated, only "
                     "the 2A03 channels will sound", nsf_exp[i].name);
    }
    free(buf);
    if (x->x_load < 0x8000 && !x->x_note) {
        post("nsf~: load address below $8000 (FDS-style image): the FDS "
             "chip is not emulated, the 2A03 channels still run");
        x->x_note = 1;
    }
    nsf_start_track(x, x->x_start);
}

/* --------------------------------------------------------- messages */

/* LCG for the random modes (instance state, no libc seeding) */
static unsigned nsf_rand32(t_nsf *x) {
    x->x_rseed = x->x_rseed * 1103515245u + 12345u;
    return x->x_rseed >> 8;
}

/* the lowest-set-bit k-th free track of mask (1-based), or -1 when the
 * mask has no free track below x_tracks */
static int nsf_rand_pick(t_nsf *x, unsigned mask) {
    int n = x->x_tracks, k, cnt = 0, pick;
    unsigned m = (n >= 32) ? mask : (mask & ((1u << n) - 1u));
    if (!m) return -1;
    for (k = 0; k < n; k++) if (m & (1u << k)) cnt++;
    pick = (int)(nsf_rand32(x) % (unsigned)cnt);
    for (k = 0; k < n; k++) {
        if (m & (1u << k)) {
            if (pick == 0) return k + 1;
            pick--;
        }
    }
    return 1;
}

/* mode-driven advance at the play-length end (running from nsf_run).
 * seqloop wraps, seqstop ends after the last track, randloop picks any
 * track, randstop picks unplayed tracks until all have played.  Stopping
 * = end bang (outlet 2), same as the single-mode loop-0 end. */
static void nsf_init_track(t_nsf *x);
static void nsf_advance(t_nsf *x) {
    int n = x->x_tracks;
    int next = -1;
    if (x->x_mode == NSF_M_SEQLOOP) {
        next = (x->x_track >= n) ? 1 : x->x_track + 1;
    } else if (x->x_mode == NSF_M_SEQSTOP) {
        if (x->x_track < n) next = x->x_track + 1;
    } else if (x->x_mode == NSF_M_RANDLOOP) {
        next = nsf_rand_pick(x, 0xffffffffu);
    } else if (x->x_mode == NSF_M_RANDSTOP) {
        unsigned played = x->x_played | (1u << (x->x_track - 1));
        x->x_played = played;
        next = nsf_rand_pick(x, ~played);
    }
    if (next < 0) {
        x->x_playing = 0;
        x->x_cyc_left = 0;
        outlet_bang(x->x_end);
        return;
    }
    nsf_start_track(x, next);
    if (x->x_mode == NSF_M_RANDSTOP)
        x->x_played |= 1u << (next - 1);
    nsf_init_track(x);
    x->x_cyc_left = 0;
}

/* the engine's init call, run synchronously at message time (A = track-1,
 * X = region, blargg's start_track model); the play calls then ride the
 * frame boundary inside perform */
static void nsf_init_track(t_nsf *x) {
    x->x_cpu.a = (unsigned char)(x->x_track - 1);
    x->x_cpu.x = (unsigned char)(x->x_video == 1 ? 1 : 0);
    x->x_cpu.y = x->x_cpu.x;
    x->x_cpu.p = CPU6502_I | CPU6502_U;
    x->x_cpu.s = 0xfd;
    cpu6502_call(&x->x_cpu, x->x_init);
    x->x_in_call = 1;
    x->x_callcyc = 0;
    while (x->x_in_call && x->x_cpu.pc != CPU6502_TRAP
           && x->x_callcyc < (long long)x->x_cpu_clock) {
        int cyc = cpu6502_step(&x->x_cpu);
        int k;
        x->x_callcyc += cyc;
        for (k = 0; k < cyc; k++) nes_apu_cycle(&x->x_apu);
        if (x->x_apu.fc_irq || x->x_apu.dpcm.irq) cpu6502_irq(&x->x_cpu);
    }
    if (x->x_in_call && x->x_cpu.pc != CPU6502_TRAP) {
        post("nsf~: init ran over 1 s without returning - broken file? "
             "dropping the call");
        x->x_in_call = 0;
        x->x_cpu.pc = CPU6502_TRAP;
    }
    x->x_in_call = 0;
}

static void nsf_play(t_nsf *x) {
    if (!x->x_data) {
        pd_error(x, "nsf~: nothing loaded");
        return;
    }
    if (x->x_playing) return;           /* already running: no-op (vgm~
                                         * play semantics) */
    x->x_paused = 0;
    /* resume and start are the same: re-init the track (the pause sweep
     * disabled channels; the engine owns its register state) */
    x->x_played = 1u << (x->x_track - 1);   /* fresh pass: only this one */
    nsf_start_track(x, x->x_track);
    nsf_init_track(x);
    x->x_playing = 1;
}

static void nsf_pause(t_nsf *x) {
    if (x->x_playing) {
        x->x_playing = 0;
        x->x_paused = 1;
        nsf_snoop(x, 0x15, 0);          /* the keyoff sweep: all channels
                                         * off (mirrors vgm~'s pause) */
    }
}

static void nsf_stop(t_nsf *x) {
    int was = x->x_playing || x->x_paused;
    x->x_playing = 0;
    x->x_paused = 0;
    if (was) {
        nsf_start_track(x, x->x_track);
        nsf_snoop(x, 0x15, 0);
    }
}

static void nsf_goto(t_nsf *x, t_symbol *s, int argc, t_atom *argv) {
    int t;
    (void)s;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "nsf~: goto expects a track number (1-%d)",
                 x->x_tracks);
        return;
    }
    t = (int)atom_getfloat(argv);
    if (!x->x_data) return;
    if (t < 1 || t > x->x_tracks) {
        pd_error(x, "nsf~: goto: track %d out of range (1-%d)", t,
                 x->x_tracks);
        return;
    }
    /* manual needle move: the played set restarts from the new track */
    x->x_played = 1u << (t - 1);
    nsf_start_track(x, t);
    if (x->x_playing) nsf_init_track(x);
}

static void nsf_mode(t_nsf *x, t_symbol *s, int argc, t_atom *argv) {
    const char *n;
    int m;
    (void)s;
    if (argc < 1 || argv[0].a_type != A_SYMBOL) {
        post("nsf~: mode %s (songtime %.1f s, loop %d)",
             nsf_mode_names[x->x_mode], x->x_songtime, x->x_loop);
        return;
    }
    n = atom_getsymbol(&argv[0])->s_name;
    for (m = 0; m < 5; m++)
        if (!strcmp(n, nsf_mode_names[m])) break;
    if (m >= 5) {
        pd_error(x, "nsf~: mode expects single/seqloop/seqstop/randloop/"
                 "randstop (got %s)", n);
        return;
    }
    x->x_mode = m;
    x->x_played = 1u << (x->x_track - 1);
    /* advance modes need a finite length: apply songtime to the current
     * track if it has no NSFE time of its own */
    if (m != NSF_M_SINGLE && x->x_track_ms <= 0.0
        && x->x_songtime > 0.0 && x->x_playing)
        x->x_track_ms = x->x_songtime * 1000.0;
    post("nsf~: mode %s", nsf_mode_names[m]);
}

static void nsf_songtime(t_nsf *x, t_symbol *s, int argc, t_atom *argv) {
    (void)s;
    if (argc >= 1 && argv[0].a_type == A_FLOAT) {
        float t = atom_getfloat(&argv[0]);
        x->x_songtime = t < 0.0f ? 0.0f : (double)t;
        /* a mid-play change re-arms the current track when it had no
         * NSFE time and an advance mode needs one */
        if (x->x_mode != NSF_M_SINGLE && x->x_playing
            && x->x_track_ms <= 0.0 && x->x_songtime > 0.0)
            x->x_track_ms = x->x_songtime * 1000.0;
        post("nsf~: songtime %.1f s", x->x_songtime);
    } else {
        post("nsf~: songtime %.1f s", x->x_songtime);
    }
}

static void nsf_loop(t_nsf *x, t_float f) { x->x_loop = (f != 0); }

static void nsf_info(t_nsf *x) {
    if (!x->x_data) { post("nsf~: nothing loaded"); return; }
    post("nsf~: %s, v%d, %d song%s, track %d, load %04x init %04x play "
         "%04x, %s, %s, %.2f Hz play rate, mode %s, songtime %.1f s, %s",
         x->x_is_nsfe ? "NSFE" : "NSF",
         x->x_ver, x->x_tracks, x->x_tracks == 1 ? "" : "s", x->x_track,
         x->x_load, x->x_init, x->x_play,
         x->x_banksw ? "bankswitched" : "no bankswitch",
         x->x_video == 1 ? "PAL" : (x->x_video == 2 ? "dual" : "NTSC"),
         x->x_rate, nsf_mode_names[x->x_mode], x->x_songtime,
         x->x_playing ? "playing" :
         (x->x_paused ? "paused" : "stopped"));
    if (x->x_title[0] || x->x_artist[0] || x->x_copyright[0])
        post("nsf~: \"%s\" by %s (%s)", x->x_title, x->x_artist,
             x->x_copyright);
    if (x->x_track_ms > 0)
        post("nsf~: track play length %.1f s, %.1f s played",
             x->x_track_ms / 1000.0, x->x_ms_played / 1000.0);
}

static void nsf_anything(t_nsf *x, t_symbol *s, int argc, t_atom *argv) {
    const char *n = s->s_name;
    if (!strcmp(n, "load")) {
        nsf_load(x, argc >= 1 ? atom_getsymbol(argv) : 0);
    } else if (!strcmp(n, "goto")) {
        nsf_goto(x, s, argc, argv);
    } else if (!strcmp(n, "play")) {
        nsf_play(x);
    } else if (!strcmp(n, "pause")) {
        nsf_pause(x);
    } else if (!strcmp(n, "stop")) {
        nsf_stop(x);
    } else if (!strcmp(n, "loop")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            nsf_loop(x, atom_getfloat(argv));
    } else if (!strcmp(n, "mode")) {
        nsf_mode(x, s, argc, argv);
    } else if (!strcmp(n, "songtime")) {
        nsf_songtime(x, s, argc, argv);
    } else if (!strcmp(n, "info")) {
        nsf_info(x);
    } else if (!strcmp(n, "psg") || !strcmp(n, "gg") || !strcmp(n, "ay")
               || !strcmp(n, "opl") || !strcmp(n, "nes")) {
        /* chip-selector chatter on a shared wire: dropped silently */
    } else {
        pd_error(x, "nsf~: unknown message %s", n);
    }
}

/* ------------------------------------------------------------- dsp */

/* run up to n CPU cycles; *dpcmsum accumulates the DPCM DAC value per
 * cycle for the mean-window outlet.  Instruction boundaries carry the
 * frame scheduling: play/init calls, OAM DMA stalls, IRQ injection. */
static int nsf_run(t_nsf *x, int n, double *dpcmsum) {
    int used = 0;
    while (used < n && x->x_playing) {
        if (x->x_cyc_left == 0) {
            if (x->x_cpu.pc == CPU6502_TRAP && x->x_in_call)
                x->x_in_call = 0;       /* the call returned */
            if (x->x_dma_pending) {
                x->x_dma_pending = 0;
                x->x_cyc_left = 513;    /* flat stall (deviation: no
                                         * odd/even adjustment) */
            } else if ((double)x->x_framecyc >= x->x_cpf) {
                x->x_framecyc -= (long long)x->x_cpf;
                x->x_ms_played += 1000.0 / x->x_rate;
                if (!x->x_in_call) {
                    cpu6502_call(&x->x_cpu, x->x_play);
                    x->x_in_call = 1;
                    x->x_callcyc = 0;
                    x->x_cyc_left = 6;  /* the JSR itself costs 6 */
                }
                /* an unfinished call just skips this frame's play (the
                 * 1 s guard below force-returns broken ones) */
                if (x->x_track_ms > 0
                    && x->x_ms_played >= x->x_track_ms) {
                    x->x_ms_played -= x->x_track_ms;
                    if (x->x_mode == NSF_M_SINGLE) {
                        if (x->x_loop) {
                            nsf_start_track(x, x->x_track);
                            nsf_init_track(x);
                            x->x_cyc_left = 0;
                        } else {
                            x->x_playing = 0;
                            x->x_cyc_left = 0;
                            outlet_bang(x->x_end);
                        }
                    } else {
                        nsf_advance(x);
                    }
                }
            } else if (x->x_cpu.pc == CPU6502_TRAP && !x->x_in_call) {
                x->x_cyc_left = 1;      /* idle between calls: the apu
                                         * keeps time */
            } else {
                x->x_cyc_left = cpu6502_step(&x->x_cpu);
                if (x->x_in_call) {
                    x->x_callcyc += x->x_cyc_left;
                    if (x->x_callcyc > (long long)x->x_cpu_clock
                        && x->x_cpu.pc != CPU6502_TRAP
                        && !(x->x_note & 2)) {
                        /* a call that never returns: force out after 1 s
                         * and say so - looping init code is a broken
                         * file, silence beats a hang */
                        post("nsf~: init/play call ran over 1 s without "
                             "returning - broken file? dropping the call");
                        x->x_note |= 2;
                        x->x_in_call = 0;
                        x->x_cpu.pc = CPU6502_TRAP;
                    }
                }
                /* IRQ lines: frame counter + DMC flags are levels */
                if (x->x_apu.fc_irq || x->x_apu.dpcm.irq)
                    cpu6502_irq(&x->x_cpu);
            }
        }
        {
            int adv = n - used;
            int k;
            if (adv > x->x_cyc_left) adv = x->x_cyc_left;
            x->x_cyc_left -= adv;
            x->x_framecyc += adv;
            for (k = 0; k < adv; k++) {
                nes_apu_cycle(&x->x_apu);
                *dpcmsum += x->x_apu.dpcm.val;
            }
            used += adv;
        }
    }
    return used;
}

static t_int *nsf_perform(t_int *w) {
    t_nsf *x = (t_nsf *)(w[1]);
    t_sample *out = (t_sample *)(w[2]);
    int n = (int)(w[3]);
    int i;
    for (i = 0; i < n; i++) {
        float d5;
        float y;
        if (x->x_playing) {
            int cyc;
            x->x_cacc += x->x_cpi;
            cyc = (int)x->x_cacc;
            x->x_cacc -= cyc;
            if (cyc < 1) cyc = 1;
            {
                double acc = 0.0;
                cyc = nsf_run(x, cyc, &acc);
                /* the DAC level bipolarized around its mid; no enable
                 * gate - the level is always live (nesdev APU DMC), the
                 * highpass below owns the idle decay (device test
                 * 0.17.119: the old $4015-enable gate left pedestals
                 * that hard-stepped at every kick/enable boundary) */
                x->x_d5 = (float)(acc / cyc - 63.5) / 63.5f;
            }
        }
        /* not playing: the level is frozen - keep decaying it so a
         * pause/stop fades instead of clicking */
        d5 = x->x_d5;
        if (!x->x_hp_prime) {
            /* prime for steady state (same law as nes~ ch5): constant
             * input outputs exactly 0 - no startup thump */
            x->x_hp = 0.0f;
            x->x_hp_x = d5;
            x->x_hp_prime = 1;
            y = 0.0f;
        } else {
            y = d5 - x->x_hp_x + x->x_hp_r * x->x_hp;
            x->x_hp_x = d5;
            x->x_hp = y;
        }
        out[i] = (t_sample)(y * 0.25f);
    }
    return w + 4;
}

static void nsf_dsp(t_nsf *x, t_signal **sp) {
    x->x_sr = sp[0]->s_sr > 1.0f ? sp[0]->s_sr : 48000.0f;
    x->x_cpi = x->x_cpu_clock / (double)x->x_sr;
    x->x_hp_r = expf(-2.0f * (float)M_PI * 1.5f / x->x_sr);
    dsp_add(nsf_perform, 3, x, sp[0]->s_vec, sp[0]->s_n);
}

/* ------------------------------------------------------------ setup */

static void *nsf_new(void) {
    t_nsf *x = (t_nsf *)pd_new(nsf_class);
    x->x_sr = 48000.0f;
    x->x_data = 0;
    x->x_datalen = 0;
    x->x_is_nsfe = 0;
    x->x_ver = 1;
    x->x_tracks = 1;
    x->x_start = 1;
    x->x_track = 1;
    x->x_load = x->x_init = x->x_play = 0;
    x->x_banksw = 0;
    memset(x->x_bankseed, 0, sizeof(x->x_bankseed));
    x->x_video = 0;
    x->x_expansion = 0;
    x->x_rate_ntsc = x->x_rate_pal = 0.0;
    x->x_times = 0;
    x->x_ntimes = 0;
    x->x_track_ms = 0.0;
    x->x_songtime = 120.0;      /* advance modes' default per-track
                                 * length (desktop players' 2 min) */
    x->x_mode = NSF_M_SINGLE;
    x->x_played = 0;
    x->x_rseed = 0x1234abcdu;
    x->x_cpu_clock = NES_CPU_NTSC;
    x->x_rate = NES_RATE_NTSC;
    x->x_cpf = x->x_cpu_clock / x->x_rate;
    x->x_cpi = x->x_cpu_clock / 48000.0;
    x->x_cacc = 0.0;
    x->x_framecyc = 0;
    x->x_cyc_left = 0;
    x->x_playing = x->x_paused = x->x_in_call = 0;

    x->x_callcyc = 0;
    x->x_loop = 1;
    x->x_ms_played = 0.0;
    x->x_dma_pending = 0;
    x->x_note = 0;
    x->x_hp = 0.0f;
    x->x_hp_x = 0.0f;
    x->x_hp_r = expf(-2.0f * (float)M_PI * 1.5f / 48000.0f);
    x->x_hp_prime = 0;
    x->x_d5 = -1.0f;    /* power-up DAC level 0, bipolarized */
    memset(x->x_ram, 0, sizeof(x->x_ram));
    memset(x->x_wram, 0, sizeof(x->x_wram));
    memset(x->x_bankreg, 0, sizeof(x->x_bankreg));
    x->x_title[0] = x->x_artist[0] = x->x_copyright[0] = 0;
    nes_apu_init(&x->x_apu, nsf_rd, x);
    cpu6502_init(&x->x_cpu, nsf_rd, nsf_wr, x);
    /* documented outlet order: 0 = nes reg stream, 1 = DPCM signal,
     * 2 = end bang */
    x->x_nesout = outlet_new(&x->obj, &s_anything);
    x->x_dpcmout = outlet_new(&x->obj, &s_signal);
    x->x_end = outlet_new(&x->obj, &s_bang);
    return (void *)x;
}

void nsf_tilde_setup(void) {
    nsf_class = class_new(gensym("nsf~"), (t_newmethod)nsf_new,
                          (t_method)nsf_free, sizeof(t_nsf), 0, A_NULL);
    class_addmethod(nsf_class, (t_method)nsf_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
    class_addanything(nsf_class, nsf_anything);
}
