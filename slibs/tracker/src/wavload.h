/* wavload - shared PCM wav loader for the tracker library ([smp~],
 * [instr]). Loads 8/16/24-bit mono/stereo wav files, downmixed to mono
 * float, with the smpl chunk fields (unity note, pitch fraction, loop)
 * auto-configuring base note and loop points. */

#ifndef __WAVLOAD_H
#define __WAVLOAD_H

#include "m_pd.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    t_sample *data;      /* mono float frames */
    long frames;
    long sr;             /* file sample rate */
    float base;          /* MIDI note at rate 1.0 */
    float basefrac;      /* fine part of base in semitones */
    int loop;
    long loopstart, loopend;   /* loopend exclusive */
} t_wav;

static long wl_l(const unsigned char *p) {
    return (long)((unsigned int)p[0] | ((unsigned int)p[1] << 8) |
                  ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24));
}

static void wl_free(t_wav *w) {
    free(w->data);
    w->data = NULL;
    w->frames = 0;
}

/* Loads path into w; returns 1 on success, 0 with a pd_error on failure. */
static int wavload(t_wav *w, const char *path, const char *name,
                   t_object *owner) {
    FILE *f = fopen(path, "rb");
    if (!f) { pd_error(owner, "cannot open %s", name); return 0; }
    unsigned char hdr[12];
    if (fread(hdr, 1, 12, f) != 12 || memcmp(hdr, "RIFF", 4) ||
        memcmp(hdr + 8, "WAVE", 4)) {
        fclose(f);
        pd_error(owner, "%s: not a wav file", name);
        return 0;
    }
    int fmt = 1, ch = 1, bits = 16, havesmpl = 0;
    long sr = 8363, datapos = -1, datalen = 0;
    int unity = 60, numloops = 0, lstart = 0, lend = 0;
    unsigned int frac = 0;
    for (;;) {
        unsigned char ch8[8];
        long n = (long)fread(ch8, 1, 8, f);
        if (n < 8) break;
        long csize = wl_l(ch8 + 4);
        if (!memcmp(ch8, "fmt ", 4) && csize >= 16) {
            unsigned char fh[16];
            if (fread(fh, 1, 16, f) != 16) break;
            fmt = fh[0] | (fh[1] << 8);
            ch = fh[2] | (fh[3] << 8);
            sr = wl_l(fh + 4);
            bits = fh[14] | (fh[15] << 8);
            csize -= 16;
        } else if (!memcmp(ch8, "data", 4)) {
            datapos = ftell(f);
            datalen = csize;
        } else if (!memcmp(ch8, "smpl", 4) && csize >= 36) {
            unsigned char sm[36];
            if (fread(sm, 1, 36, f) != 36) break;
            unity = (int)wl_l(sm + 12);
            frac = (unsigned int)wl_l(sm + 16);
            numloops = (int)wl_l(sm + 28);
            if (numloops > 0 && csize >= 60) {
                unsigned char lp[24];
                if (fread(lp, 1, 24, f) != 24) break;
                lstart = (int)wl_l(lp + 8);
                lend = (int)wl_l(lp + 12);        /* inclusive */
                havesmpl = 1;
                csize -= 24;
            }
            csize -= 36;
        }
        if (csize & 1) csize++;
        fseek(f, csize, SEEK_CUR);
    }
    if (datapos < 0 || datalen <= 0) {
        fclose(f);
        pd_error(owner, "%s: no data chunk", name);
        return 0;
    }
    if (fmt != 1 && fmt != 0xfffe) {
        fclose(f);
        pd_error(owner, "%s: only PCM wavs are supported", name);
        return 0;
    }
    if (bits != 8 && bits != 16 && bits != 24) {
        fclose(f);
        pd_error(owner, "%s: unsupported bit depth %d", name, bits);
        return 0;
    }
    int bytes = bits / 8;
    long frames = datalen / (bytes * (ch > 1 ? 2 : 1));
    if (frames < 4) {
        fclose(f);
        pd_error(owner, "%s: too short", name);
        return 0;
    }
    t_sample *buf = (t_sample *)malloc(sizeof(t_sample) * (size_t)frames);
    if (!buf) { fclose(f); pd_error(owner, "out of memory"); return 0; }
    fseek(f, datapos, SEEK_SET);
    unsigned char *raw = (unsigned char *)malloc((size_t)datalen);
    if (!raw || (long)fread(raw, 1, (size_t)datalen, f) != datalen) {
        free(raw); free(buf); fclose(f);
        pd_error(owner, "%s: read failed", name);
        return 0;
    }
    fclose(f);
    int stride = bytes * (ch > 1 ? 2 : 1);
    for (long i = 0; i < frames; i++) {
        long o = i * stride;
        float l, r = 0;
        if (bits == 8) {
            l = ((int)raw[o] - 128) / 128.0f;
            if (ch > 1) r = ((int)raw[o + 1] - 128) / 128.0f;
        } else if (bits == 16) {
            int v = (short)((unsigned int)raw[o] |
                            ((unsigned int)raw[o + 1] << 8));
            l = v / 32768.0f;
            if (ch > 1) {
                int v2 = (short)((unsigned int)raw[o + 2] |
                                 ((unsigned int)raw[o + 3] << 8));
                r = v2 / 32768.0f;
            }
        } else {
            long v = (long)((unsigned int)raw[o] |
                            ((unsigned int)raw[o + 1] << 8) |
                            ((unsigned int)raw[o + 2] << 16));
            if (v & 0x800000) v -= 0x1000000;
            l = v / 8388608.0f;
            if (ch > 1) {
                long v2 = (long)((unsigned int)raw[o + 3] |
                                 ((unsigned int)raw[o + 4] << 8) |
                                 ((unsigned int)raw[o + 5] << 16));
                if (v2 & 0x800000) v2 -= 0x1000000;
                r = v2 / 8388608.0f;
            }
        }
        buf[i] = ch > 1 ? (l + r) * 0.5f : l;
    }
    free(raw);
    wl_free(w);
    w->data = buf;
    w->frames = frames;
    w->sr = sr;
    w->loop = 0;
    w->loopstart = 0;
    w->loopend = frames;
    if (havesmpl && lend > lstart) {
        w->loop = 1;
        w->loopstart = lstart;
        w->loopend = (long)lend + 1;       /* smpl end is inclusive */
    }
    w->base = (unity >= 12 && unity <= 120) ? (float)unity : 60.0f;
    /* smpl pitch fraction: 32-bit fraction of a semitone (cents/100) */
    w->basefrac = (float)((double)frac * 100.0 / 4294967296.0);
    return 1;
}

#endif /* __WAVLOAD_H */
