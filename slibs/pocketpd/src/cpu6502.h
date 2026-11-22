/* cpu6502 - a 6502 core for NSF playback, written from scratch in C for
 * PocketPd.  Covers the full official instruction set plus the common
 * illegal opcodes NSF engines use (LAX/SAX/DCP/ISC/SLO/RLA/SRE/RRA/
 * ANC/ALR/ARR/AXS and the NOP family); the truly unstable opcodes are
 * implemented with their common emulator behavior.  Cycle counts follow
 * the standard per-addressing-mode table with page-cross penalties on
 * reads; branch/jump/interrupt counts are exact.
 *
 * The 2A03 (the NES's 6502) has NO decimal mode - ADC/SBC are always
 * binary here, matching the silicon the NSFs ran on.
 *
 * No third-party code vendored.  Memory access rides two callbacks so
 * nsf~ wires the CPU to its own memory map; nes~/vgm~ never use this
 * core.
 */

#ifndef cpu6502_h
#define cpu6502_h

/* status flags */
#define CPU6502_C 0x01
#define CPU6502_Z 0x02
#define CPU6502_I 0x04
#define CPU6502_D 0x08            /* stored/set by SED/CLD but never
                                   * affects ADC/SBC (2A03: no BCD) */
#define CPU6502_B 0x10
#define CPU6502_U 0x20            /* unused bit, always reads 1 */
#define CPU6502_V 0x40
#define CPU6502_N 0x80

#define CPU6502_TRAP 0xfff8       /* the fake-RTS return address nsf~
                                   * parks the PC on (see cpu6502.h) */

typedef struct cpu6502 {
    unsigned char a, x, y, s, p;
    unsigned pc;
    long long cycles;             /* total CPU cycles executed */
    int irq_pending;              /* level: serviced when I is clear */
    int nmi_pending;              /* edge: serviced at the next boundary */
    int jam;                      /* a KIL/jam opcode executed: the core
                                   * stops advancing */
    int illegal_seen;             /* count of unknown-opcode NOPs taken */
    unsigned char (*rd)(void *user, unsigned addr);
    void (*wr)(void *user, unsigned addr, unsigned char v);
    void *user;
} cpu6502;

void cpu6502_init(cpu6502 *c, unsigned char (*rd)(void *, unsigned),
                  void (*wr)(void *, unsigned, unsigned char), void *user);
void cpu6502_reset(cpu6502 *c);           /* 7-cycle reset, PC = $FFFC */
int  cpu6502_step(cpu6502 *c);            /* one instruction; returns its
                                           * cycle count (0 when jammed) */
void cpu6502_irq(cpu6502 *c);             /* assert the IRQ line */
void cpu6502_nmi(cpu6502 *c);             /* NMI edge */
/* fake JSR: push a return address pointing at CPU6502_TRAP and jump to
 * target - cpu6502_step stops (pc == CPU6502_TRAP) when the callee
 * returns, exactly the play/init call model nsf~ needs */
void cpu6502_call(cpu6502 *c, unsigned target);

#endif /* cpu6502_h */
