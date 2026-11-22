/* sidcore.cc - C ABI shim over reSID (see sidcore.h).  Non-reSID code;
 * the vendored engine itself stays byte-identical. */

#include "sidcore.h"
#include "sid.h"
#include <stdio.h>
#include <math.h>

using reSID::SID;
using reSID::MOS6581;
using reSID::MOS8580;
using reSID::SAMPLE_RESAMPLE;

namespace {

/* Per-voice full scale = the positive swing of a full-envelope waveform
 * over wave_zero (voice.cc): the MOS6581's waveform DAC rides at 0x380
 * (896) and tops at 4095, the MOS8580 sits at 0x800 (2048).  A max voice
 * lands at 1.0, three sum to ~3 with NO clip - the same headroom
 * philosophy as ym2612~ (the patch owns the gain). */
const double VFULL_6581  = (4095.0 - 0x380) * 255.0;
const double VFULL_8580  = (4095.0 - 0x800) * 255.0;

/* chip-mix full scale: reSID's resampler normalizes the AUDIO OUT to a
 * short's range (filter_scale 0.97), so a full 3-voice chip mix lands
 * at ~3.0 under 1/32768 - matching the per-voice outlets. */
const double OUT_FULL = 32768.0;

/* the per-voice outputs keep the silicon's DC pedestal (a full pulse
 * rides +0.56 of full scale) - like the real chip, each channel goes
 * through its own output capacitor before this object's outlet: a
 * one-pole highpass at the extfilt's own 16 Hz corner, so scopes and
 * dac~ patches stay DC-free (documented deviation, same choice
 * sn76489~ made for its noise channel). */
const double HP_HZ = 16.0;

class SidCore {
public:
    SidCore() : clock_hz(985248.0), rate_hz(44100.0),
                acc(0.0), cps(0.0), oscale(1.0 / OUT_FULL),
                vfull(VFULL_6581), hpa(0.0), last(0.0f) {
        for (int i = 0; i < 3; i++) {
            vy[i] = 0.0f;
            pv[i] = 0.0f;
        }
        sid.enable_filter(true);
        sid.enable_external_filter(true);
        apply();
    }
    void apply() {
        sid.set_sampling_parameters(clock_hz, SAMPLE_RESAMPLE, rate_hz);
        cps = clock_hz / rate_hz;
        hpa = (float)(1.0 - exp(-2.0 * 3.141592653589793 * HP_HZ / rate_hz));
    }
    SID sid;
    double clock_hz;     /* chip clock (PAL/NTSC) */
    double rate_hz;      /* output (pd) sample rate */
    double acc;          /* chip cycles owed to the next block (fractional) */
    double cps;          /* chip cycles per output sample */
    double oscale;       /* chip-mix normalization */
    double vfull;        /* per-model voice full scale */
    float  hpa;          /* per-voice DC-blocker coefficient */
    float  last;         /* latest chip-mix sample (reSID short -> float) */
    float  vy[3];        /* DC-blocker slow tracking (the "capacitor") */
    float  pv[3];        /* previous block-end voice values (ramp source) */
    short  buf[1024];    /* block render buffer (pd blocks are 64) */
};

} // namespace


void *sidcore_new(void) {
    return new SidCore();
}

void sidcore_free(void *s) {
    delete (SidCore *)s;
}

void sidcore_reset(void *s) {
    ((SidCore *)s)->sid.reset();
}

void sidcore_model(void *s, int model) {
    SidCore *c = (SidCore *)s;
    c->sid.set_chip_model(model ? MOS8580 : MOS6581);
    c->vfull = model ? VFULL_8580 : VFULL_6581;
}

void sidcore_clock(void *s, double hz) {
    SidCore *c = (SidCore *)s;
    if (hz < 250000.0) hz = 985248.0;
    c->clock_hz = hz;
    c->apply();
}

int sidcore_sampling(void *s, double rate) {
    SidCore *c = (SidCore *)s;
    if (rate < 8000.0) return 0;
    c->rate_hz = rate;
    c->apply();
    return 1;
}

void sidcore_write(void *s, int reg, int val) {

    ((SidCore *)s)->sid.write(reg & 0x1f, val & 0xff);

}

int sidcore_read(void *s, int reg) {
    return ((SidCore *)s)->sid.read(reg & 0x1f);
}

/* advance the chip for one BLOCK of output samples: out gets the chip
 * mix (AUDIO OUT incl. filter + external filter), va/vb/vc the three
 * per-voice outputs scaled to +-1.
 *
 * The whole block is handed to reSID's clock() in ONE call - the
 * upstream-documented usage.  The previous per-sample loop (clock(dt,
 * &buf, 1) each sample) broke clock_resample's fixpoint phase
 * accumulator: its delta_t-exhausted path subtracted the consumed
 * cycles from a sample_offset that could then go NEGATIVE, and the
 * next call emitted a sample while consuming zero cycles - duplicated
 * stale FIR output at irregular spots, ~600 single-sample spikes a
 * second on dense tunes (the reported buzz). */
void sidcore_block(void *s, float *out, float *va, float *vb, float *vc,
                   int n) {
    SidCore *c = (SidCore *)s;
    if (n > 1024) n = 1024;
    c->acc += c->cps * n;
    int dt = (int)c->acc;
    c->acc -= dt;
    if (dt > 0) {
        int got = c->sid.clock(dt, c->buf, n);
        c->acc += dt;                 /* unconsumed cycles stay owed */
        for (int i = 0; i < got; i++) {
            c->last = (float)c->buf[i];
            out[i] = c->last * (float)c->oscale;
        }
        for (int i = got; i < n; i++) /* sample-hold (defensive: rare) */
            out[i] = c->last * (float)c->oscale;
        /* per-voice targets at the block end, DC-blocked */
        int v[3];
        float tv[3];
        c->sid.voice_outputs(v);
        for (int i = 0; i < 3; i++) {
            float x = (float)(v[i] / c->vfull);
            c->vy[i] += (x - c->vy[i]) * c->hpa;
            tv[i] = x - c->vy[i];
        }
        /* linear ramp from the previous block-end value: smooth scopes,
         * and the voice outlets stay usable as audio */
        for (int i = 0; i < n; i++) {
            float f = (n > 1) ? (float)i / (float)(n - 1) : 1.0f;
            va[i] = c->pv[0] + (tv[0] - c->pv[0]) * f;
            vb[i] = c->pv[1] + (tv[1] - c->pv[1]) * f;
            vc[i] = c->pv[2] + (tv[2] - c->pv[2]) * f;
        }
        c->pv[0] = tv[0];
        c->pv[1] = tv[1];
        c->pv[2] = tv[2];
    } else {
        for (int i = 0; i < n; i++) {
            out[i] = c->last * (float)c->oscale;
            va[i] = c->pv[0];
            vb[i] = c->pv[1];
            vc[i] = c->pv[2];
        }
    }
}
