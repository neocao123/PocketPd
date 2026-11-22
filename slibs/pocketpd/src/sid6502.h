/* sid6502.h - a 6502/6510 interpreter for the PocketPd sid~ PSID player.
 *
 * Written IN-HOUSE (no third-party CPU core is vendored): TinySID's player
 * carries a "spreading of the source code is FORBIDDEN" notice, and the
 * public-domain fake6502 keeps its registers in TU-static globals, which
 * would break the moment two sid~ instances run in one pd instance.  This
 * core carries all state in a struct, drives memory exclusively through
 * caller-supplied hooks (the PSID replay owns the 64K array and the IO
 * traps), and implements the full official opcode set plus the common
 * undocumented opcodes - HVSC tunes were written against real silicon and
 * some DO execute RMW combos (SLO/RLA/SRE/RRA/DCP/ISC), LAX/SAX and the
 * immediate-flag family (ANC/ALR/ARR/SBX).
 *
 * Cycle counts are the official per-opcode tables with page-cross
 * penalties; exact sub-cycle accuracy does not matter to a basic PSID
 * replay (the play routine is invoked synchronously), the counts only pace
 * the fake raster line / CIA timers the player exposes.
 */
#ifndef POCKETPD_SID6502_H
#define POCKETPD_SID6502_H

#define S65_C  0x01             /* carry */
#define S65_Z  0x02             /* zero */
#define S65_I  0x04             /* irq disable */
#define S65_D  0x08             /* decimal */
#define S65_B  0x10             /* break (pushed state only) */
#define S65_U  0x20             /* unused, reads as 1 */
#define S65_V  0x40             /* overflow */
#define S65_N  0x80             /* negative */

typedef struct {
    unsigned char (*rd)(void *ud, int addr);
    void (*wr)(void *ud, int addr, int val);
    void      *ud;
    unsigned short pc;
    unsigned char a, x, y, sp, p;
    unsigned char op;        /* opcode of the last executed instruction */
    long       ticks;       /* approximate cycles since reset */
    int        illegal;     /* undocumented/jam opcodes executed */
} sid6502;

/* pc/a/x/y/sp/p/ticks/illegal zeroed, hooks installed */
void sid6502_init(sid6502 *c, unsigned char (*rd)(void *, int),
                  void (*wr)(void *, int, int), void *ud);
/* one instruction; returns the cycles it took */
int  sid6502_step(sid6502 *c);
/* call a subroutine at addr and run until the stack pointer is back at its
 * start level (the outermost RTS); returns steps executed or -1 when the
 * step guard trips (the tune never returned).  Interrupts are never
 * injected: a basic PSID replay drives init/play synchronously. */
long sid6502_call(sid6502 *c, int addr, long max_steps);

#endif /* POCKETPD_SID6502_H */
