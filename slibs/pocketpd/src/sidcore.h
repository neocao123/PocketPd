/* sidcore.h - thin C ABI over the vendored reSID engine (GPLv2+,
 * (c) 1998-2022 Dag Lem).  The sid6581~ wrapper is C and this blob is
 * one mixed C/C++ .so, so the C++ side lives behind six plain functions.
 * Every caller drives ONE output sample per tick call. */
#ifndef POCKETPD_SIDCORE_H
#define POCKETPD_SIDCORE_H

#ifdef __cplusplus
extern "C" {
#endif

void *sidcore_new(void);
void  sidcore_free(void *s);
void  sidcore_reset(void *s);
void  sidcore_model(void *s, int model);        /* 0 = MOS6581, 1 = MOS8580 */
void  sidcore_clock(void *s, double hz);        /* chip clock, e.g. 985248 */
int   sidcore_sampling(void *s, double rate);   /* output rate, 1 = ok */
void  sidcore_write(void *s, int reg, int val); /* register write 0-0x18 */
int   sidcore_read(void *s, int reg);           /* 0x19/1a/1b/1c meaningful */

/* advance the chip for one BLOCK of output samples: out[0..n-1] gets
 * the chip mix (the real AUDIO OUT incl. filter + external filter),
 * va/vb/vc the three per-voice amplitude-modulated outputs scaled to
 * +-1 (full-scale voice = 1.0), linearly ramped across the block from
 * the previous block-end values.  The block is clocked in ONE reSID
 * call (the upstream-documented pattern; per-sample calls corrupt its
 * fixpoint phase).  Writes take effect at the next block. */
void  sidcore_block(void *s, float *out, float *va, float *vb, float *vc,
                    int n);

#ifdef __cplusplus
}
#endif
#endif /* POCKETPD_SIDCORE_H */
