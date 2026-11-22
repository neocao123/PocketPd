/* xm~ - XM/MOD/S3M tracker module player (libxm core).
 *
 * One object, everything inside: file loading, playback, mixing, and the
 * per-channel split.  Interface conventions follow vgm~.
 *
 *   outlet 0      signal: the module mix, LEFT (sum of all channel
 *                 frames, exactly what xm_generate_samples computes - a
 *                 single xm_generate_samples_unmixed pass feeds both the
 *                 mix and the split, the player never runs twice)
 *   outlet 1      signal: the module mix, RIGHT
 *   outlets 2-33  signal: channels 1-32 in isolation (post volume,
 *                 post panning, per-frame; a module with fewer channels
 *                 leaves the extra outlets silent)
 *   outlet 34     bang: module end reached without looping (see `loop`)
 *
 * The libxm mixer keeps every channel frame within +-1, but the MIX is
 * an unnormalized sum of up to 32 such frames - a loud full-bandwidth
 * module can exceed +-1 on the mix outlets; dac~ clips as usual.
 *
 * Messages (all through the left inlet):
 *   load <file>           open a module from the pd search path / patch
 *                         dir (.xm native; .mod/.s3m load too, playback
 *                         accuracy of non-XM is upstream best-effort)
 *   play                  start (rewinds to the top), or resume after
 *                         pause; also exits manual playing
 *   pause                 halt, keeping the position
 *   stop                  halt + reset the player to the top
 *   goto <pot> [row]      seek (upstream xm_seek: positions only, does
 *                         NOT reset envelopes/effects - "seeking modules
 *                         is broken by design")
 *   loop <0/1>            wrap at the module end (default on).  With 0,
 *                         the object stops + bangs when libxm's loop
 *                         counter reaches 1: the first time ANY pattern
 *                         row is crossed again - the natural POT wrap,
 *                         or a backward Bxx jump (a musical loop the
 *                         composer wrote in).  Sequencing effects that
 *                         repeat rows therefore end the "one-pass" run
 *                         there too - upstream play-once semantics.
 *   mute <ch> <0/1>       mute/unmute a channel (1..32)
 *   minst <inst> <0/1>    mute/unmute an instrument (1..instruments)
 *   info                  module/state summary to the console
 *   status                info + a line per ACTIVE channel (instrument,
 *                         frequency, volume, panning) - the sync getters
 *                         on the nes~/opl3~ status convention
 *
 * Manual playing (the sequencer freezes; `play` resumes it):
 *   chN instr <x>         select instrument x (1..instruments) for
 *                         channel N; later note/slide on that channel
 *                         use it
 *   chN note <midi> [vel] play that instrument on channel N (MIDI 0-127,
 *                         C-4 = 60; vel 0-127, default = the sample's own
 *                         volume). The sample is picked by the
 *                         instrument's own keymap.  With `glide` armed
 *                         and the channel already sounding, the new note
 *                         keys at sweep start and glides from where the
 *                         old one was (opl3~/ym2612~'s law)
 *   chN off               release the channel (envelope release)
 *   chN glide <ms>        portamento time (0 = off, default); arms the
 *                         channel for `note` and is the default ms for
 *                         `slide`
 *   chN slide <midi> [ms] [vel]
 *                         sweep the sounding note to midi WITHOUT a key
 *                         event, over ms milliseconds (omitted = the
 *                         glide time; 0 = hard switch).  ms-exact linear
 *                         period interpolation, like opl3~'s glide.  vel
 *                         0-127 rides the volume column (omitted = the
 *                         level stays)
 *   chN fx <hex>          actuate an XM effect on the channel (1-3 hex
 *                         digits: C20 volume slide, 404 vibrato, 045
 *                         arpeggio...) - the native effect engine runs it
 *                         at the module's own tick rate
 * Bare spellings act on ch1; `chN cmd ... cmd ...` chains, like opl3~.
 */

#include "m_pd.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include "xm.h"

#define XM_NCH 32               /* XM's hard channel ceiling */

/* file lookup shared with ym2612_tilde.c (search path, no canvas deref) */
int ympp_findfile(const char *name, char *full, unsigned int n);

typedef struct _xm {
    t_object  obj;
    t_outlet *x_l;              /* mix left */
    t_outlet *x_r;              /* mix right */
    t_outlet *x_ch[XM_NCH];     /* channels 1..32 in isolation */
    t_outlet *x_end;            /* bang at the (non-looping) module end */
    xm_context_t *x_ctx;        /* libxm context == the malloc pool */
    float      *x_split;        /* 2 * x_n2 * XM_NCH unmixed frames */
    float      *x_up;           /* x_d > 1: 2 * x_n * XM_NCH upsampled frames */
    int         x_n;            /* block size the buffers were sized for */
    t_float     x_sr;           /* pd sample rate (from dsp) */
    int         x_d;            /* clock decimation: pd_sr = x_d * libxm rate
                                   (libxm's clock field is 16-bit; the 96000 Hz
                                   setting overflows it, so the player runs at
                                   pd_sr/2 and the block is upsampled back) */
    int         x_playing;
    int         x_paused;
    int         x_loop;         /* user wrap flag (default on) */
    int         x_manual;       /* manual playing (frozen sequencer) */
    int         x_vi[XM_NCH];   /* per-channel instrument (0 = none yet) */
    int         x_noteon[XM_NCH]; /* per-channel a manual note is sounding */
    /* manual glide, opl3~'s stepper at libxm-period level: `glide <ms>`
     * arms a channel, `slide <midi> [ms]` sweeps a sounding note to the
     * pitch without a key event.  The interpolation lives in the
     * perform stepper (per-block linear period, ms-exact). */
    float       x_glide[XM_NCH];  /* glide time ms (0 = off, default) */
    int         x_sl_on[XM_NCH];  /* a sweep is running on this channel */
    float       x_sl_from[XM_NCH];/* sweep start period */
    float       x_sl_to[XM_NCH];  /* sweep target period */
    float       x_sl_pos[XM_NCH]; /* ms elapsed in the sweep */
    float       x_sl_ms[XM_NCH];  /* sweep length ms (0 = no sweep) */
} t_xm;

static t_class *xm_class;

static void xm_apply_sr(t_xm *x);

/* reset every channel's glide memory and stop any running sweep (load,
 * play restart, stop) */
static void xm_glide_clear(t_xm *x) {
    int v;
    for (v = 0; v < XM_NCH; v++) {
        x->x_glide[v] = 0.0f;
        x->x_sl_on[v] = 0;
        x->x_sl_from[v] = 0.0f;
        x->x_sl_to[v] = 0.0f;
        x->x_sl_pos[v] = 0.0f;
        x->x_sl_ms[v] = 0.0f;
    }
}

/* ---------------------------------------------------------- file load */

/* slurp a whole module file (XM/MOD/S3M are never gzip-compressed) */
static unsigned char *xm_slurp(FILE *f, unsigned long *lenp) {
    unsigned char *raw = 0;
    unsigned long cap = 0, got = 0, rn;
    for (;;) {
        unsigned char *nb;
        cap = cap ? cap * 2 : 65536;
        nb = (unsigned char *)realloc(raw, cap);
        if (!nb) { free(raw); fclose(f); return 0; }
        raw = nb;
        rn = (unsigned long)fread(raw + got, 1, cap - got, f);
        if (!rn) break;
        got += rn;
    }
    fclose(f);
    *lenp = got;
    return raw;
}

static void xm_dumpinfo(t_xm *x, const char *fname) {
    uint8_t bpm, tempo;
    xm_get_playing_speed(x->x_ctx, &bpm, &tempo);
    post("xm~: '%s': \"%s\" by %s, %u ch, %u patterns, %u instruments, "
         "%u samples, %u orders, %.1f bpm/%u ticks, libxm clock %u Hz "
         "(pd %.0f Hz, /%d)", fname,
         xm_get_module_name(x->x_ctx), xm_get_tracker_name(x->x_ctx),
         xm_get_number_of_channels(x->x_ctx),
         xm_get_number_of_patterns(x->x_ctx),
         xm_get_number_of_instruments(x->x_ctx),
         xm_get_number_of_samples(x->x_ctx),
         xm_get_module_length(x->x_ctx), (double)bpm, tempo,
         xm_get_sample_rate(x->x_ctx), (double)x->x_sr, x->x_d);
}

static void xm_load(t_xm *x, t_symbol *s) {
    char full[MAXPDSTRING];
    FILE *f;
    unsigned char *buf;
    unsigned long len;
    xm_prescan_data_t *p;
    uint32_t sz;
    char *pool;
    xm_context_t *ctx;
    if (!s || !s->s_name || !s->s_name[0]) {
        pd_error(x, "xm~: load expects a file name");
        return;
    }
    if (!ympp_findfile(s->s_name, full, MAXPDSTRING)) {
        pd_error(x, "xm~: '%s' not found (pd search path)", s->s_name);
        return;
    }
    f = fopen(full, "rb");
    if (!f) {
        pd_error(x, "xm~: cannot open '%s'", full);
        return;
    }
    buf = xm_slurp(f, &len);
    if (!buf || len < 0x20) {
        free(buf);
        pd_error(x, "xm~: '%s' is empty", s->s_name);
        return;
    }
    p = (xm_prescan_data_t *)__builtin_alloca(XM_PRESCAN_DATA_SIZE);
    if (!xm_prescan_module((const char *)buf, (uint32_t)len, p)) {
        free(buf);
        pd_error(x, "xm~: '%s' is not an XM/MOD/S3M module (or too "
                 "corrupt to prescan)", s->s_name);
        return;
    }
    sz = xm_size_for_context(p);
    pool = (char *)malloc(sz);
    if (!pool) {
        free(buf);
        pd_error(x, "xm~: out of memory for '%s' (%u bytes)", s->s_name, sz);
        return;
    }
    /* libxm copies everything it needs into the pool - the file buffer
     * dies right after; a failed context would never return per the
     * upstream contract (returns_nonnull), so keep the old one on any
     * doubt by building first, swapping second */
    ctx = xm_create_context(pool, p, (const char *)buf, (uint32_t)len);
    free(buf);
    if (x->x_ctx) free(x->x_ctx);
    x->x_ctx = ctx;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_manual = 0;                    /* fresh context, sequencer armed */
    xm_glide_clear(x);                  /* instrument numbering changed */
    for (int v = 0; v < XM_NCH; v++) {
        x->x_vi[v] = 0;
        x->x_noteon[v] = 0;
    }
    xm_apply_sr(x);
    xm_set_max_loop_count(ctx, x->x_loop ? 0 : 1);
    xm_dumpinfo(x, s->s_name);
}

/* ------------------------------------------------------------- dsp */

static void xm_zeroall(t_xm *x, t_sample *l, t_sample *r, t_sample **ch,
                       int n) {
    int i, k;
    for (i = 0; i < n; i++) { l[i] = 0; r[i] = 0; }
    for (k = 0; k < XM_NCH; k++)
        for (i = 0; i < n; i++) ch[k][i] = 0;
}

/* per-block glide stepper: ms-exact linear-period sweep on manual
 * channels.  libxm recomputes each channel's playback step from
 * ch->period inside xm_tick(), so one interpolated write per block
 * makes the sounding sample walk toward the target pitch at the pd
 * block cadence (no retrigger, no dependency on the module's tempo). */
static void xm_glide_step(t_xm *x, int n2) {
    float ms;
    int c;
    if (x->x_sr <= 0.0f || x->x_d < 1) return;
    ms = 1000.0f * (float)n2 / ((float)x->x_sr / (float)x->x_d);
    for (c = 0; c < XM_NCH; c++) {
        float t;
        if (!x->x_sl_on[c] || x->x_sl_ms[c] <= 0.0f) continue;
        x->x_sl_pos[c] += ms;
        t = x->x_sl_pos[c] / x->x_sl_ms[c];
        if (t >= 1.0f) {
            t = 1.0f;
            x->x_sl_on[c] = 0;
        }
        xm_manual_set_period(x->x_ctx, (uint8_t)(c + 1),
                             x->x_sl_from[c] +
                             (x->x_sl_to[c] - x->x_sl_from[c]) * t);
    }
}

static t_int *xm_perform(t_int *w) {
    t_xm *x = (t_xm *)(w[1]);
    t_sample *l = (t_sample *)(w[2]);
    t_sample *r = (t_sample *)(w[3]);
    int n = (int)(w[4]);
    t_sample **ch = (t_sample **)(w + 5);
    int i, k;
    if (!x->x_ctx || !x->x_split || !x->x_up ||
        (!x->x_playing && !x->x_manual)) {
        xm_zeroall(x, l, r, ch, n);
        return w + 5 + XM_NCH;
    }
    {
        /* ONE generation pass: unmixed frames feed the mix sum and the
         * split outlets - calling xm_generate_samples on top would run
         * the player twice per block */
        uint8_t nch = xm_get_number_of_channels(x->x_ctx);
        int n2 = n / x->x_d;
        int s2 = (int)nch * 2;
        const float *fr0 = (x->x_d > 1) ? x->x_up : x->x_split;
        const float *fr = fr0;
        int kk;
        /* clock re-arm EVERY block: the libxm clock lives in the context
         * tail that xm_reset_context() zeroes - with it at 0 every sample
         * borrows a tick and the module races at thousands of ticks/s
         * (the "too fast, too high" failure). apply_sr is a plain field
         * write and also re-derives the 16-bit-safe decimated rate
         * (pd_sr / x_d), so an unconditional call per block fixes both
         * the re-arm and the decimation in one move. */
        xm_apply_sr(x);
        xm_glide_step(x, n2);
        xm_generate_samples_unmixed(x->x_ctx, x->x_split, (uint16_t)n2);
        if (x->x_d > 1) {
            /* the player runs at pd_sr/x_d so its clock fits libxm's
             * 16-bit field (the 96000 Hz setting wraps to 30464 otherwise);
             * linearly upsample the n2 frames back to the full pd block -
             * the physical timeline stays exact, XM content stops below
             * half the decimated rate so nothing is lost */
            for (i = 0; i < n; i++) {
                int idx = i / x->x_d;
                const float *a = x->x_split + idx * s2;
                const float *b = (idx + 1 < n2) ? a + s2 : a;
                float f = (float)(i % x->x_d) / (float)x->x_d;
                float *o = x->x_up + i * s2;
                for (kk = 0; kk < s2; kk++)
                    o[kk] = a[kk] + (b[kk] - a[kk]) * f;
            }
        }
        for (i = 0; i < n; i++) {
            float ls = 0, rs = 0;
            for (k = 0; k < nch; k++) {
                ls += fr[2 * k];
                rs += fr[2 * k + 1];
            }
            l[i] = (t_sample)ls;
            r[i] = (t_sample)rs;
            fr += s2;
        }
        for (k = 0; k < XM_NCH; k++) {
            t_sample *o = ch[k];
            if (k < (int)nch) {
                const float *fc = fr0 + 2 * k;
                for (i = 0; i < n; i++, fc += s2) {
                    o[i] = (t_sample)fc[0];
                }
            } else {
                for (i = 0; i < n; i++) o[i] = 0;
            }
        }
        if (!x->x_loop && xm_get_loop_count(x->x_ctx) > 0) {
            /* first re-crossed row with looping disabled: the module's
             * natural end (see the `loop` docstring) - libxm already
             * silenced this block, so stopping now leaks nothing */
            x->x_playing = 0;
            outlet_bang(x->x_end);
        }
    }
    return w + 5 + XM_NCH;
}

static void xm_dsp(t_xm *x, t_signal **sp) {
    int n = sp[0]->s_n;
    int i;
    x->x_sr = sp[0]->s_sr > 1 ? sp[0]->s_sr : 48000;
    if (x->x_ctx)
        xm_apply_sr(x);
    /* the decimated player feeds n/x_d frames per block - keep x_d within
     * the block size so the player always gets at least one frame */
    if (x->x_d > n) {
        x->x_d = n;
        if (x->x_ctx)
            xm_apply_sr(x);
    }
    if (!x->x_split || x->x_n != n) {
        if (x->x_split) free(x->x_split);
        if (x->x_up) free(x->x_up);
        /* worst case: 32 channels, 2 frames per channel; x_split holds the
         * decimated player frames, x_up the upsampled full block */
        x->x_split = (float *)malloc(sizeof(float) * 2 * (size_t)n * XM_NCH);
        x->x_up = (float *)malloc(sizeof(float) * 2 * (size_t)n * XM_NCH);
        x->x_n = (x->x_split && x->x_up) ? n : 0;
    }
    {
        t_sample *chv[XM_NCH];
        t_int *argv;
        for (i = 0; i < XM_NCH; i++)
            chv[i] = sp[2 + i]->s_vec;
        argv = (t_int *)getbytes(sizeof(t_int) * (5 + XM_NCH));
        argv[0] = (t_int)x;
        argv[1] = (t_int)sp[0]->s_vec;
        argv[2] = (t_int)sp[1]->s_vec;
        argv[3] = (t_int)n;
        for (i = 0; i < XM_NCH; i++)
            argv[4 + i] = (t_int)chv[i];
        dsp_addv(xm_perform, 4 + XM_NCH, argv);
        freebytes(argv, sizeof(t_int) * (5 + XM_NCH));
    }
}

/* --------------------------------------------------------- messages */

/* re-apply the pd sample rate, and re-derive the decimation.  Two reasons
 * this runs per-block from perform():
 *
 * 1. (0.17.125) xm_reset_context() memsets the context tail -
 *    remaining_samples_in_tick through the struct end - which ZEROES
 *    current_sample_rate.  With rate 0 the tick recharge is 0, ckd_sub
 *    borrows on EVERY sample, and the module races at ~1 tick per sample;
 *    event-point re-arming can be missed by message ordering, so perform()
 *    now calls this unconditionally - it is a plain field write.
 *
 * 2. (0.17.129) libxm's clock field is 16-BIT: the app's 96000 Hz setting
 *    handed to it as (uint16_t)96000 wrapped to 30464 = the player ran
 *    3.16x fast/high (the user's "bpm 快的要命 + 刮玻璃").  The fix
 *    decimates in powers of two (96000/2 = 48000) and upsamples the
 *    player's frames back to the pd block in perform() - the value in the
 *    field stays exact and proportional to the pd timeline. */
static void xm_apply_sr(t_xm *x) {
    int d = 1;
    while (x->x_sr / d > 65535.0f && d < 64)
        d <<= 1;
    x->x_d = d;
    xm_set_sample_rate(x->x_ctx, (uint16_t)((int)(x->x_sr / d + 0.5f)));
}

static void xm_play(t_xm *x) {
    if (!x->x_ctx) {
        pd_error(x, "xm~: nothing loaded");
        return;
    }
    if (x->x_paused) {                  /* resume in place */
        x->x_playing = 1;
        x->x_paused = 0;
        return;
    }
    if (!x->x_playing) {                /* (re)start from the top */
        xm_reset_context(x->x_ctx);
        xm_apply_sr(x);                 /* reset zeroed the rate (see above) */
        x->x_manual = 0;                /* reset cleared the player flag too */
        xm_glide_clear(x);              /* fresh context, sweeps invalid */
        x->x_playing = 1;
    }
}

static void xm_pause(t_xm *x) {
    if (x->x_playing) {
        x->x_playing = 0;
        x->x_paused = 1;
    }
}

static void xm_stop(t_xm *x) {
    int was = x->x_playing || x->x_paused;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_manual = 0;
    xm_glide_clear(x);
    if (x->x_ctx && was) {
        xm_reset_context(x->x_ctx);     /* rewind to the top */
        xm_apply_sr(x);                 /* reset zeroed the rate (see above) */
    }
}

static void xm_loop(t_xm *x, t_float f) {
    x->x_loop = (f != 0);
    if (x->x_ctx)
        xm_set_max_loop_count(x->x_ctx, x->x_loop ? 0 : 1);
}

/* goto <pot> [row]: upstream xm_seek moves the position only - channel
 * envelopes/effects carry over, which is why upstream calls module
 * seeking "broken by design".  pot is order-index range-checked here;
 * row is clamped - the player itself folds an out-of-range row into the
 * next pattern on the following row tick. */
static void xm_goto(t_xm *x, t_symbol *s, int argc, t_atom *argv) {
    int pot, row = 0;
    if (!x->x_ctx) return;
    (void)s;
    if (argc < 1 || argv[0].a_type != A_FLOAT) {
        pd_error(x, "xm~: goto expects <order> [row]");
        return;
    }
    pot = (int)atom_getfloat(argv);
    if (argc >= 2 && argv[1].a_type == A_FLOAT)
        row = (int)atom_getfloat(argv + 1);
    if (pot < 0) pot = 0;
    if (row < 0) row = 0;
    if (pot >= (int)xm_get_module_length(x->x_ctx)) {
        pd_error(x, "xm~: goto %d: module has %u orders", pot,
                 xm_get_module_length(x->x_ctx));
        return;
    }
    if (row > 255) row = 255;
    xm_seek(x->x_ctx, (uint8_t)pot, (uint8_t)row, 0);
}

static void xm_mute(t_xm *x, t_symbol *s, int argc, t_atom *argv) {
    int ch, m;
    if (!x->x_ctx) return;
    (void)s;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "xm~: mute expects <channel 1-32> <0/1>");
        return;
    }
    ch = (int)atom_getfloat(argv);
    m = atom_getfloat(argv + 1) != 0;
    if (ch < 1 || ch > (int)xm_get_number_of_channels(x->x_ctx)) {
        pd_error(x, "xm~: mute %d: module has %u channels", ch,
                 xm_get_number_of_channels(x->x_ctx));
        return;
    }
    xm_mute_channel(x->x_ctx, (uint8_t)ch, m != 0);
}

static void xm_minst(t_xm *x, t_symbol *s, int argc, t_atom *argv) {
    int in, m;
    if (!x->x_ctx) return;
    (void)s;
    if (argc < 2 || argv[0].a_type != A_FLOAT || argv[1].a_type != A_FLOAT) {
        pd_error(x, "xm~: minst expects <instrument> <0/1>");
        return;
    }
    in = (int)atom_getfloat(argv);
    m = atom_getfloat(argv + 1) != 0;
    if (in < 1 || in > (int)xm_get_number_of_instruments(x->x_ctx)) {
        pd_error(x, "xm~: minst %d: module has %u instruments", in,
                 xm_get_number_of_instruments(x->x_ctx));
        return;
    }
    xm_mute_instrument(x->x_ctx, (uint8_t)in, m != 0);
}

static void xm_info(t_xm *x) {
    uint8_t pi, pat, row;
    uint8_t bpm, tempo;
    uint32_t samples;
    if (!x->x_ctx) {
        post("xm~: nothing loaded");
        return;
    }
    xm_get_playing_speed(x->x_ctx, &bpm, &tempo);
    xm_get_position(x->x_ctx, &pi, &pat, &row, &samples);
    post("xm~: \"%s\" by %s | order %u (pattern %u row %u) | "
         "%.1f bpm, %u ticks/row | loop %s, %u wrap(s) so far | %s",
         xm_get_module_name(x->x_ctx), xm_get_tracker_name(x->x_ctx),
         pi, pat, row, (double)bpm, tempo,
         x->x_loop ? "on" : "off", xm_get_loop_count(x->x_ctx),
         x->x_playing ? "playing" : (x->x_paused ? "paused" : "stopped"));
}

static void xm_status(t_xm *x) {
    uint8_t nch, k;
    if (!x->x_ctx) {
        post("xm~: nothing loaded");
        return;
    }
    xm_info(x);
    nch = xm_get_number_of_channels(x->x_ctx);
    for (k = 1; k <= nch; k++) {
        if (!xm_is_channel_active(x->x_ctx, k)) continue;
        post("xm~: ch%u: inst %u freq %.1f Hz vol %.2f pan %.2f", k,
             xm_get_instrument_of_channel(x->x_ctx, k),
             (double)xm_get_frequency_of_channel(x->x_ctx, k),
             (double)xm_get_volume_of_channel(x->x_ctx, k),
             (double)xm_get_panning_of_channel(x->x_ctx, k));
    }
}

/* ---------------------------------------------------- manual playing */

/* "chN" (N = 1..32) scope token: 0-based channel, or -1 when the word is
 * not a scope */
static int xm_scope(const char *name) {
    int n;
    if (name[0] != 'c' || name[1] != 'h') return -1;
    if (name[2] < '1' || name[2] > '9') return -1;
    n = name[2] - '0';
    if (name[3]) {
        if (name[4] || name[3] < '0' || name[3] > '9') return -1;
        n = n * 10 + (name[3] - '0');
    }
    return (n >= 1 && n <= 32) ? n - 1 : -1;
}

/* 1-3 hex digits: one = effect type, two = type+param, three = the XM
 * "type param param" spelling (C20 = volume slide 20 down).  Returns 0 on
 * a malformed string. */
static int xm_hex3(const char *p, int *type, int *param) {
    int v = 0, n = 0;
    while (*p && n < 3) {
        int c = *p;
        if (c >= '0' && c <= '9') c -= '0';
        else if (c >= 'a' && c <= 'f') c -= 'a' - 10;
        else if (c >= 'A' && c <= 'F') c -= 'A' - 10;
        else return 0;
        v = v * 16 + c;
        n++;
        p++;
    }
    if (*p || !n) return 0;
    if (n == 1) { *type = v; *param = 0; }
    else if (n == 2) { *type = v >> 4; *param = v & 0xF; }
    else { *type = (v >> 8) & 0xF; *param = v & 0xFF; }
    return 1;
}

/* freeze the sequencer and play/actuate manually (shared by every manual
 * command) */
static void xm_manual_arm(t_xm *x) {
    x->x_manual = 1;
    x->x_playing = 0;
    x->x_paused = 0;
}

/* one command consumed from a message's token stream; ch = the scope
 * channel (0 = bare, ch1).  Returns the number of argument atoms
 * consumed, or -1 when the word is not a command.  Malformed arguments
 * consume the whole rest (one clear error, no cascading). */
static int xm_command(t_xm *x, int ch, t_symbol *s, int argc, t_atom *argv) {
    const char *name = s->s_name;
    if (!x->x_ctx) {
        pd_error(x, "xm~: nothing loaded");
        return argc;
    }
    if (!strcmp(name, "instr")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            int in = (int)atom_getfloat(argv);
            if (in < 1 || in > (int)xm_get_number_of_instruments(x->x_ctx)) {
                pd_error(x, "xm~: ch%d instr %d: module has %u instruments",
                         ch + 1, in, xm_get_number_of_instruments(x->x_ctx));
                return argc;
            }
            x->x_vi[ch] = in;
            return 1;
        }
        pd_error(x, "xm~: instr expects <instrument>");
        return argc;
    }
    if (!strcmp(name, "note")) {
        int na = 0;
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            na = 1;
            if (argc >= 2 && argv[1].a_type == A_FLOAT) na = 2;
        }
        if (!na) {
            pd_error(x, "xm~: note expects <midi 0-127> [vel 0-127]");
            return argc;
        }
        if (!x->x_vi[ch]) {
            pd_error(x, "xm~: ch%d: select an instrument first (instr <n>)",
                     ch + 1);
            return argc;
        }
        {
            int midi = (int)atom_getfloat(argv);
            int note = midi - 12;           /* MIDI C0 = C-0 (internal 0) */
            int vc = 0;
            if (note < 0) note = 0;
            if (note > 95) note = 95;
            if (na == 2) {
                int v = (int)atom_getfloat(argv + 1);
                if (v < 0) v = 0;
                if (v > 127) v = 127;
                vc = 0x10 + v * 64 / 127;   /* volume column 0x10..0x50 */
            }
            xm_manual_arm(x);
            {
                /* glide law (opl3~/ym2612~): the key events fire at sweep
                 * start, so a sounding channel rekeys and glides from
                 * where it was; a silent one just plays straight */
                float oldp = (x->x_glide[ch] > 0.0f && x->x_noteon[ch])
                                 ? xm_manual_get_period(x->x_ctx,
                                                        (uint8_t)(ch + 1))
                                 : 0.0f;
                x->x_noteon[ch] = 1;
                x->x_sl_on[ch] = 0;
                if (!xm_manual_slot(x->x_ctx, (uint8_t)(ch + 1),
                                    (uint8_t)(note + 1), (uint8_t)x->x_vi[ch],
                                    (uint8_t)vc, 0, 0))
                    pd_error(x, "xm~: ch%d: module has %u channels", ch + 1,
                             xm_get_number_of_channels(x->x_ctx));
                if (oldp > 0.0f) {
                    float to = xm_manual_note_period(x->x_ctx,
                                                     (uint8_t)(ch + 1),
                                                     (uint8_t)(note + 1));
                    if (to > 0.0f && to != oldp) {
                        x->x_sl_on[ch] = 1;
                        x->x_sl_from[ch] = oldp;
                        x->x_sl_to[ch] = to;
                        x->x_sl_pos[ch] = 0.0f;
                        x->x_sl_ms[ch] = x->x_glide[ch];
                        /* the rekey set the target period - pull the
                         * channel back to the sweep's start pitch */
                        xm_manual_set_period(x->x_ctx, (uint8_t)(ch + 1),
                                             oldp);
                    }
                }
            }
            return na;
        }
    }
    if (!strcmp(name, "off")) {
        xm_manual_arm(x);
        x->x_noteon[ch] = 0;
        x->x_sl_on[ch] = 0;
        if (!xm_manual_slot(x->x_ctx, (uint8_t)(ch + 1),
                            128, 0, 0, 0, 0))    /* NOTE_KEY_OFF */
            pd_error(x, "xm~: ch%d: module has %u channels", ch + 1,
                     xm_get_number_of_channels(x->x_ctx));
        return 0;
    }
    if (!strcmp(name, "glide")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            float ms = atom_getfloat(argv);
            if (ms < 0.0f) ms = 0.0f;
            if (ms > 5000.0f) ms = 5000.0f;
            x->x_glide[ch] = ms;
            return 1;
        }
        pd_error(x, "xm~: glide expects <ms>");
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
            pd_error(x, "xm~: slide expects <midi> [ms] [vel]");
            return argc;
        }
        if (!x->x_noteon[ch]) {
            pd_error(x, "xm~: ch%d: slide slides a sounding note "
                     "(note first)", ch + 1);
            return argc;
        }
        {
            int midi = (int)atom_getfloat(argv);
            int note = midi - 12;
            float ms = x->x_glide[ch];
            float from, to;
            if (na >= 2) ms = atom_getfloat(argv + 1);
            if (note < 1) note = 1;         /* slot notes are 1-based */
            if (note > 96) note = 96;
            if (ms < 0.0f) ms = 0.0f;
            if (ms > 5000.0f) ms = 5000.0f;
            xm_manual_arm(x);
            if (na == 3) {
                /* optional velocity: ride the volume column (0x10..0x50
                   = level 0..64); the note keeps ringing at the new
                   level, the gate stays untouched */
                int v = (int)atom_getfloat(argv + 2);
                if (v < 0) v = 0;
                if (v > 127) v = 127;
                if (!xm_manual_slot(x->x_ctx, (uint8_t)(ch + 1),
                                    0, 0, (uint8_t)(0x10 + v * 64 / 127),
                                    0, 0))
                    pd_error(x, "xm~: ch%d: module has %u channels", ch + 1,
                             xm_get_number_of_channels(x->x_ctx));
            }
            /* ms-exact linear-period sweep, opl3~'s stepper at period
               level: no key event, the sounding note just walks to the
               target pitch over <ms> milliseconds (0 = hard switch) */
            from = xm_manual_get_period(x->x_ctx, (uint8_t)(ch + 1));
            to = xm_manual_note_period(x->x_ctx, (uint8_t)(ch + 1),
                                       (uint8_t)(note + 1));
            if (ms < 1.0f || from <= 0.0f || to <= 0.0f || to == from) {
                x->x_sl_on[ch] = 0;
                if (to > 0.0f)
                    xm_manual_set_period(x->x_ctx, (uint8_t)(ch + 1), to);
                return na;
            }
            x->x_sl_on[ch] = 1;
            x->x_sl_from[ch] = from;
            x->x_sl_to[ch] = to;
            x->x_sl_pos[ch] = 0.0f;
            x->x_sl_ms[ch] = ms;
            return na;
        }
    }
    if (!strcmp(name, "fx")) {
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            int type, param;
            if (!xm_hex3(atom_getsymbol(argv)->s_name, &type, &param)) {
                pd_error(x, "xm~: fx expects 1-3 hex digits (e.g. C20)");
                return argc;
            }
            xm_manual_arm(x);
            if (!xm_manual_slot(x->x_ctx, (uint8_t)(ch + 1),
                                0, 0, 0, (uint8_t)type, (uint8_t)param))
                pd_error(x, "xm~: ch%d: module has %u channels", ch + 1,
                         xm_get_number_of_channels(x->x_ctx));
            return 1;
        }
        pd_error(x, "xm~: fx expects <effect hex>");
        return argc;
    }
    return -1;
}

static void xm_anything(t_xm *x, t_symbol *s, int argc, t_atom *argv) {
    const char *n = s->s_name;
    int ch = 0, i = 0;
    t_symbol *cs = s;
    if (!strcmp(n, "load")) {
        xm_load(x, argc >= 1 ? atom_getsymbol(argv) : 0);
        return;
    }
    if (!strcmp(n, "play")) { xm_play(x); return; }
    if (!strcmp(n, "pause")) { xm_pause(x); return; }
    if (!strcmp(n, "stop")) { xm_stop(x); return; }
    if (!strcmp(n, "goto")) { xm_goto(x, s, argc, argv); return; }
    if (!strcmp(n, "loop")) {
        if (argc >= 1 && argv[0].a_type == A_FLOAT)
            xm_loop(x, atom_getfloat(argv));
        return;
    }
    if (!strcmp(n, "mute")) { xm_mute(x, s, argc, argv); return; }
    if (!strcmp(n, "minst")) { xm_minst(x, s, argc, argv); return; }
    if (!strcmp(n, "info")) { xm_info(x); return; }
    if (!strcmp(n, "status")) { xm_status(x); return; }
    if (!strcmp(n, "list")) {
        /* OSC bridges deliver "ch1 note 60" / "note 60 100" as lists:
           symbol-first lists re-enter the command stream, plain numbers
           are note <midi> [vel] on ch1 */
        if (argc >= 1 && argv[0].a_type == A_SYMBOL) {
            xm_anything(x, atom_getsymbol(&argv[0]), argc - 1, argv + 1);
            return;
        }
        if (argc >= 1 && argv[0].a_type == A_FLOAT) {
            t_atom nv[3];
            int na = (argc >= 2 && argv[1].a_type == A_FLOAT) ? 2 : 1;
            SETSYMBOL(&nv[0], gensym("note"));
            SETFLOAT(&nv[1], atom_getfloat(&argv[0]));
            if (na == 2) SETFLOAT(&nv[2], atom_getfloat(&argv[1]));
            xm_anything(x, gensym("ch1"), na + 1, nv);
        }
        return;
    }
    /* manual command stream: chN scopes + bare commands (bare = ch1) */
    for (;;) {
        const char *cn = cs->s_name;
        int sc = xm_scope(cn);
        if (sc >= 0) {
            ch = sc;
            if (i >= argc) return;    /* bare chN: scope set, nothing to run */
            if (argv[i].a_type != A_SYMBOL) {
                pd_error(x, "xm~: ch%d: expected a command", ch + 1);
                return;
            }
            cs = atom_getsymbol(&argv[i]);
            i++;
            continue;
        }
        {
            int used = xm_command(x, ch, cs, argc - i, argv + i);
            if (used < 0) {
                pd_error(x, "xm~: unknown message %s", cn);
                return;
            }
            i += used;
            if (i >= argc) return;
            if (argv[i].a_type != A_SYMBOL) {
                pd_error(x, "xm~: expected a command, got a number");
                return;
            }
            cs = atom_getsymbol(&argv[i]);
            i++;
        }
    }
}

/* ------------------------------------------------------------- setup */

static void *xm_new(void) {
    t_xm *x = (t_xm *)pd_new(xm_class);
    int i;
    x->x_l = outlet_new(&x->obj, &s_signal);
    x->x_r = outlet_new(&x->obj, &s_signal);
    for (i = 0; i < XM_NCH; i++) {
        x->x_ch[i] = outlet_new(&x->obj, &s_signal);
        x->x_vi[i] = 0;
        x->x_noteon[i] = 0;
    }
    x->x_end = outlet_new(&x->obj, &s_bang);
    x->x_ctx = 0;
    x->x_split = 0;
    x->x_up = 0;
    x->x_n = 0;
    x->x_sr = 48000;
    x->x_d = 1;
    x->x_playing = 0;
    x->x_paused = 0;
    x->x_loop = 1;
    x->x_manual = 0;
    return (void *)x;
}

static void xm_free(t_xm *x) {
    if (x->x_ctx) free(x->x_ctx);       /* the context IS the pool */
    if (x->x_split) free(x->x_split);
    if (x->x_up) free(x->x_up);
}

void xm_tilde_setup(void) {
    xm_class = class_new(gensym("xm~"), (t_newmethod)xm_new,
                         (t_method)xm_free, sizeof(t_xm), 0, A_NULL);
    class_addmethod(xm_class, (t_method)xm_dsp, gensym("dsp"), A_CANT,
                    A_NULL);
    class_addanything(xm_class, xm_anything);
}
