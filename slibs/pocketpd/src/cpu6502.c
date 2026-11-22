/* cpu6502 - see cpu6502.h.  One instruction per cpu6502_step call; the
 * addressing modes and cycle counts follow the standard 6502 tables
 * (page-cross penalties on reads only; STA abs,x / STA abs,y / STA (zp),y
 * and the RMW modes take their fixed counts).  ADC/SBC are always
 * binary - the 2A03 has no decimal mode.  Cycle bookkeeping: the opcode
 * fetch adds the base 2 at the top of the step, every case adds its own
 * EXTRA cycles, and the step returns base + extra. */

#include "cpu6502.h"
#include <string.h>

/* read/write helpers (little-endian words) */
static unsigned char rd(cpu6502 *c, unsigned a) {
    return c->rd(c->user, a & 0xffff);
}
static void wr(cpu6502 *c, unsigned a, unsigned char v) {
    c->wr(c->user, a & 0xffff, v);
}
static unsigned rd16(cpu6502 *c, unsigned a) {
    return (unsigned)rd(c, a) | ((unsigned)rd(c, a + 1) << 8);
}

static void push8(cpu6502 *c, unsigned char v) {
    wr(c, 0x100 | c->s, v);
    c->s = (unsigned char)(c->s - 1);
}
static unsigned char pull8(cpu6502 *c) {
    c->s = (unsigned char)(c->s + 1);
    return rd(c, 0x100 | c->s);
}
static void push16(cpu6502 *c, unsigned v) {
    push8(c, (unsigned char)(v >> 8));
    push8(c, (unsigned char)v);
}
static unsigned pull16(cpu6502 *c) {
    unsigned lo = pull8(c);
    return lo | ((unsigned)pull8(c) << 8);
}

static void setzn(cpu6502 *c, unsigned char v) {
    c->p = (unsigned char)((c->p & ~(CPU6502_Z | CPU6502_N))
                           | (v ? 0 : CPU6502_Z) | (v & CPU6502_N));
}

/* --------------------------------------------------- addressing modes
 * each fetches the operand bytes (pc advances) and returns the effective
 * address; pen gains 1 when a page boundary is crossed (callers pass
 * NULL where the cycle count is fixed, i.e. writes) */

static unsigned am_imm(cpu6502 *c)  { return c->pc++; }
static unsigned am_zp(cpu6502 *c)   { return rd(c, c->pc++); }
static unsigned am_zpx(cpu6502 *c)  { return (rd(c, c->pc++) + c->x) & 0xff; }
static unsigned am_zpy(cpu6502 *c)  { return (rd(c, c->pc++) + c->y) & 0xff; }
static unsigned am_abs(cpu6502 *c) {
    unsigned a = rd16(c, c->pc);
    c->pc += 2;
    return a;
}
static unsigned am_absx(cpu6502 *c, int *pen) {
    unsigned b = rd16(c, c->pc);
    unsigned a;
    c->pc += 2;
    a = (b + c->x) & 0xffff;
    if (pen && ((b ^ a) & 0xff00)) (*pen)++;
    return a;
}
static unsigned am_absy(cpu6502 *c, int *pen) {
    unsigned b = rd16(c, c->pc);
    unsigned a;
    c->pc += 2;
    a = (b + c->y) & 0xffff;
    if (pen && ((b ^ a) & 0xff00)) (*pen)++;
    return a;
}
static unsigned am_indx(cpu6502 *c) {
    unsigned z = (rd(c, c->pc++) + c->x) & 0xff;
    return (unsigned)rd(c, z) | ((unsigned)rd(c, (z + 1) & 0xff) << 8);
}
static unsigned am_indy(cpu6502 *c, int *pen) {
    unsigned z = rd(c, c->pc++);
    unsigned b = (unsigned)rd(c, z)
               | ((unsigned)rd(c, (z + 1) & 0xff) << 8);
    unsigned a = (b + c->y) & 0xffff;
    if (pen && ((b ^ a) & 0xff00)) (*pen)++;
    return a;
}

/* ---------------------------------------------------------- ALU pieces */

static void adc(cpu6502 *c, unsigned char v) {
    unsigned r = c->a + v + (c->p & CPU6502_C ? 1 : 0);
    c->p &= ~(CPU6502_C | CPU6502_V | CPU6502_Z | CPU6502_N);
    if (r > 0xff) c->p |= CPU6502_C;
    /* signed overflow: sign(a) != sign(result) AND sign(operand) !=
     * sign(result) - detected on bit 7, stored in P bit 6 (V) */
    if (((c->a ^ r) & (v ^ r)) & 0x80) c->p |= CPU6502_V;
    c->a = (unsigned char)r;
    setzn(c, c->a);
}
static void sbc(cpu6502 *c, unsigned char v) {
    unsigned r = c->a - v - (c->p & CPU6502_C ? 0 : 1);
    c->p &= ~(CPU6502_C | CPU6502_V | CPU6502_Z | CPU6502_N);
    if (r < 0x100) c->p |= CPU6502_C;
    if (((c->a ^ r) & ((unsigned char)~v ^ r)) & 0x80) c->p |= CPU6502_V;
    c->a = (unsigned char)r;
    setzn(c, c->a);
}
static void cmpv(cpu6502 *c, unsigned char a, unsigned char v) {
    unsigned r = a - v;
    c->p &= ~(CPU6502_C | CPU6502_Z | CPU6502_N);
    if (r < 0x100) c->p |= CPU6502_C;
    setzn(c, (unsigned char)r);
}
static unsigned char aslv(cpu6502 *c, unsigned char v) {
    c->p = (unsigned char)((c->p & ~CPU6502_C) | ((v >> 7) & 1));
    v = (unsigned char)(v << 1);
    setzn(c, v);
    return v;
}
static unsigned char lsrv(cpu6502 *c, unsigned char v) {
    c->p = (unsigned char)((c->p & ~CPU6502_C) | (v & 1));
    v = (unsigned char)(v >> 1);
    setzn(c, v);
    return v;
}
static unsigned char rolv(cpu6502 *c, unsigned char v) {
    unsigned char cin = (unsigned char)(c->p & CPU6502_C);
    c->p = (unsigned char)((c->p & ~CPU6502_C) | ((v >> 7) & 1));
    v = (unsigned char)((v << 1) | cin);
    setzn(c, v);
    return v;
}
static unsigned char rorv(cpu6502 *c, unsigned char v) {
    unsigned char cin = (unsigned char)((c->p & CPU6502_C) << 7);
    c->p = (unsigned char)((c->p & ~CPU6502_C) | (v & 1));
    v = (unsigned char)((v >> 1) | cin);
    setzn(c, v);
    return v;
}
static void bitv(cpu6502 *c, unsigned char v) {
    c->p &= ~(CPU6502_V | CPU6502_Z | CPU6502_N);
    c->p |= v & (CPU6502_V | CPU6502_N);
    if (!(c->a & v)) c->p |= CPU6502_Z;
}

/* branch helper: returns the EXTRA cycles (1 when taken, +1 on page
 * cross); the pc-relative offset was already fetched */
static int branch(cpu6502 *c, int cond) {
    signed char off = (signed char)rd(c, c->pc++);
    unsigned t;
    if (!cond) return 0;
    t = (unsigned)(c->pc + off);
    c->pc = t & 0xffff;
    return 1 + (((c->pc ^ (t - off)) & 0xff00) != 0);
}

/* interrupt sequence (IRQ/NMI/BRK share it); returns 7 */
static int do_interrupt(cpu6502 *c, unsigned vector, int brk) {
    push16(c, c->pc);
    push8(c, (unsigned char)(c->p | CPU6502_U | (brk ? CPU6502_B : 0)));
    c->p |= CPU6502_I;
    c->pc = rd16(c, vector);
    return 7;
}

void cpu6502_irq(cpu6502 *c) { c->irq_pending = 1; }
void cpu6502_nmi(cpu6502 *c) { c->nmi_pending = 1; }

void cpu6502_call(cpu6502 *c, unsigned target) {
    push16(c, CPU6502_TRAP - 1);   /* JSR pushes return-1; RTS lands on
                                    * CPU6502_TRAP and the run loop stops */
    c->pc = target;
}

void cpu6502_reset(cpu6502 *c) {
    c->s = 0xfd;
    c->p = CPU6502_I | CPU6502_U;
    c->pc = rd16(c, 0xfffc);
    c->irq_pending = 0;
    c->nmi_pending = 0;
    c->jam = 0;
    c->cycles += 7;
}

void cpu6502_init(cpu6502 *c, unsigned char (*r)(void *, unsigned),
                  void (*w)(void *, unsigned, unsigned char), void *user) {
    memset(c, 0, sizeof(*c));
    c->rd = r;
    c->wr = w;
    c->user = user;
    c->p = CPU6502_U;
    c->s = 0xfd;
}

/* ------------------------------------------------------- the core loop */

int cpu6502_step(cpu6502 *c) {
    int cyc = 0;
    unsigned addr, v;
    int pen;
    unsigned char op;

    if (c->jam) return 0;

    /* interrupt check at the instruction boundary (NMI wins) */
    if (c->nmi_pending) {
        c->nmi_pending = 0;
        c->cycles += 7;
        return do_interrupt(c, 0xfffa, 0);
    }
    if (c->irq_pending && !(c->p & CPU6502_I)) {
        c->irq_pending = 0;
        c->cycles += 7;
        return do_interrupt(c, 0xfffe, 0);
    }

    op = rd(c, c->pc++);
    pen = 0;
    addr = 0;
    v = 0;

    switch (op) {
    /* ---- loads/stores (base 2; loads +2 for zp/x/abs etc.) ---- */
    case 0xa9: c->a = rd(c, am_imm(c)); setzn(c, c->a); break;          /* LDA # */
    case 0xa5: c->a = rd(c, am_zp(c));  setzn(c, c->a); cyc += 1; break;
    case 0xb5: c->a = rd(c, am_zpx(c)); setzn(c, c->a); cyc += 2; break;
    case 0xad: c->a = rd(c, am_abs(c)); setzn(c, c->a); cyc += 2; break;
    case 0xb9: addr = am_absy(c, &pen); c->a = rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0xbd: addr = am_absx(c, &pen); c->a = rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0xa1: c->a = rd(c, am_indx(c)); setzn(c, c->a); cyc += 4; break;
    case 0xb1: addr = am_indy(c, &pen); c->a = rd(c, addr);
               setzn(c, c->a); cyc += 3 + pen; break;
    case 0xa2: c->x = rd(c, am_imm(c)); setzn(c, c->x); break;          /* LDX # */
    case 0xa6: c->x = rd(c, am_zp(c));  setzn(c, c->x); cyc += 1; break;
    case 0xb6: c->x = rd(c, am_zpy(c)); setzn(c, c->x); cyc += 2; break;
    case 0xae: c->x = rd(c, am_abs(c)); setzn(c, c->x); cyc += 2; break;
    case 0xbe: addr = am_absy(c, &pen); c->x = rd(c, addr);
               setzn(c, c->x); cyc += 2 + pen; break;
    case 0xa0: c->y = rd(c, am_imm(c)); setzn(c, c->y); break;          /* LDY # */
    case 0xa4: c->y = rd(c, am_zp(c));  setzn(c, c->y); cyc += 1; break;
    case 0xb4: c->y = rd(c, am_zpx(c)); setzn(c, c->y); cyc += 2; break;
    case 0xac: c->y = rd(c, am_abs(c)); setzn(c, c->y); cyc += 2; break;
    case 0xbc: addr = am_absx(c, &pen); c->y = rd(c, addr);
               setzn(c, c->y); cyc += 2 + pen; break;
    case 0x85: wr(c, am_zp(c), c->a);  cyc += 1; break;                 /* STA */
    case 0x95: wr(c, am_zpx(c), c->a); cyc += 2; break;
    case 0x8d: wr(c, am_abs(c), c->a); cyc += 2; break;
    case 0x9d: wr(c, am_absx(c, 0), c->a); cyc += 1; break;
    case 0x99: wr(c, am_absy(c, 0), c->a); cyc += 1; break;
    case 0x81: wr(c, am_indx(c), c->a); cyc += 2; break;
    case 0x91: wr(c, am_indy(c, 0), c->a); cyc += 2; break;
    case 0x86: wr(c, am_zp(c), c->x);  cyc += 1; break;                 /* STX */
    case 0x96: wr(c, am_zpy(c), c->x); cyc += 2; break;
    case 0x8e: wr(c, am_abs(c), c->x); cyc += 2; break;
    case 0x84: wr(c, am_zp(c), c->y);  cyc += 1; break;                 /* STY */
    case 0x94: wr(c, am_zpx(c), c->y); cyc += 2; break;
    case 0x8c: wr(c, am_abs(c), c->y); cyc += 2; break;

    /* ---- transfers / stack / flags ---- */
    case 0xaa: c->x = c->a; setzn(c, c->x); break;                      /* TAX */
    case 0xa8: c->y = c->a; setzn(c, c->y); break;                      /* TAY */
    case 0x8a: c->a = c->x; setzn(c, c->a); break;                      /* TXA */
    case 0x98: c->a = c->y; setzn(c, c->a); break;                      /* TYA */
    case 0xba: c->x = c->s; setzn(c, c->x); break;                      /* TSX */
    case 0x9a: c->s = c->x; break;                                      /* TXS */
    case 0x48: push8(c, c->a); cyc += 1; break;                         /* PHA */
    case 0x08: push8(c, (unsigned char)(c->p | CPU6502_B | CPU6502_U));
               cyc += 1; break;                                         /* PHP */
    case 0x68: c->a = pull8(c); setzn(c, c->a); cyc += 2; break;        /* PLA */
    case 0x28: c->p = (unsigned char)((pull8(c) & ~CPU6502_B)
                                      | CPU6502_U); cyc += 2; break;  /* PLP: the
               * B bit has no storage - the pulled bit 4 is ignored */
    case 0x18: c->p &= ~CPU6502_C; break;                               /* CLC */
    case 0x38: c->p |= CPU6502_C; break;                                /* SEC */
    case 0x58: c->p &= ~CPU6502_I; break;                               /* CLI */
    case 0x78: c->p |= CPU6502_I; break;                                /* SEI */
    case 0xb8: c->p &= ~CPU6502_V; break;                               /* CLV */
    case 0xd8: c->p &= ~CPU6502_D; break;                               /* CLD */
    case 0xf8: c->p |= CPU6502_D; break;                                /* SED */

    /* ---- ALU ---- */
    case 0x69: adc(c, rd(c, am_imm(c))); break;                         /* ADC */
    case 0x65: v = rd(c, am_zp(c));  cyc += 1; adc(c, v); break;
    case 0x75: v = rd(c, am_zpx(c)); cyc += 2; adc(c, v); break;
    case 0x6d: v = rd(c, am_abs(c)); cyc += 2; adc(c, v); break;
    case 0x7d: addr = am_absx(c, &pen); v = rd(c, addr);
               cyc += 2 + pen; adc(c, v); break;
    case 0x79: addr = am_absy(c, &pen); v = rd(c, addr);
               cyc += 2 + pen; adc(c, v); break;
    case 0x61: v = rd(c, am_indx(c)); cyc += 2; adc(c, v); break;
    case 0x71: addr = am_indy(c, &pen); v = rd(c, addr);
               cyc += 3 + pen; adc(c, v); break;
    case 0xe9: sbc(c, rd(c, am_imm(c))); break;                         /* SBC */
    case 0xe5: v = rd(c, am_zp(c));  cyc += 1; sbc(c, v); break;
    case 0xf5: v = rd(c, am_zpx(c)); cyc += 2; sbc(c, v); break;
    case 0xed: v = rd(c, am_abs(c)); cyc += 2; sbc(c, v); break;
    case 0xfd: addr = am_absx(c, &pen); v = rd(c, addr);
               cyc += 2 + pen; sbc(c, v); break;
    case 0xf9: addr = am_absy(c, &pen); v = rd(c, addr);
               cyc += 2 + pen; sbc(c, v); break;
    case 0xe1: v = rd(c, am_indx(c)); cyc += 2; sbc(c, v); break;
    case 0xf1: addr = am_indy(c, &pen); v = rd(c, addr);
               cyc += 3 + pen; sbc(c, v); break;
    case 0x29: c->a &= rd(c, am_imm(c)); setzn(c, c->a); break;         /* AND */
    case 0x25: c->a &= rd(c, am_zp(c));  cyc += 1; setzn(c, c->a); break;
    case 0x35: c->a &= rd(c, am_zpx(c)); cyc += 2; setzn(c, c->a); break;
    case 0x2d: c->a &= rd(c, am_abs(c)); cyc += 2; setzn(c, c->a); break;
    case 0x3d: addr = am_absx(c, &pen); c->a &= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x39: addr = am_absy(c, &pen); c->a &= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x21: c->a &= rd(c, am_indx(c)); setzn(c, c->a); cyc += 2; break;
    case 0x31: addr = am_indy(c, &pen); c->a &= rd(c, addr);
               setzn(c, c->a); cyc += 3 + pen; break;
    case 0x09: c->a |= rd(c, am_imm(c)); setzn(c, c->a); break;         /* ORA */
    case 0x05: c->a |= rd(c, am_zp(c));  cyc += 1; setzn(c, c->a); break;
    case 0x15: c->a |= rd(c, am_zpx(c)); cyc += 2; setzn(c, c->a); break;
    case 0x0d: c->a |= rd(c, am_abs(c)); cyc += 2; setzn(c, c->a); break;
    case 0x1d: addr = am_absx(c, &pen); c->a |= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x19: addr = am_absy(c, &pen); c->a |= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x01: c->a |= rd(c, am_indx(c)); setzn(c, c->a); cyc += 2; break;
    case 0x11: addr = am_indy(c, &pen); c->a |= rd(c, addr);
               setzn(c, c->a); cyc += 3 + pen; break;
    case 0x49: c->a ^= rd(c, am_imm(c)); setzn(c, c->a); break;         /* EOR */
    case 0x45: c->a ^= rd(c, am_zp(c));  cyc += 1; setzn(c, c->a); break;
    case 0x55: c->a ^= rd(c, am_zpx(c)); cyc += 2; setzn(c, c->a); break;
    case 0x4d: c->a ^= rd(c, am_abs(c)); cyc += 2; setzn(c, c->a); break;
    case 0x5d: addr = am_absx(c, &pen); c->a ^= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x59: addr = am_absy(c, &pen); c->a ^= rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x41: c->a ^= rd(c, am_indx(c)); setzn(c, c->a); cyc += 2; break;
    case 0x51: addr = am_indy(c, &pen); c->a ^= rd(c, addr);
               setzn(c, c->a); cyc += 3 + pen; break;
    case 0xc9: cmpv(c, c->a, rd(c, am_imm(c))); break;                  /* CMP */
    case 0xc5: cmpv(c, c->a, rd(c, am_zp(c)));  cyc += 1; break;
    case 0xd5: cmpv(c, c->a, rd(c, am_zpx(c))); cyc += 2; break;
    case 0xcd: cmpv(c, c->a, rd(c, am_abs(c))); cyc += 2; break;
    case 0xdd: addr = am_absx(c, &pen); cmpv(c, c->a, rd(c, addr));
               cyc += 2 + pen; break;
    case 0xd9: addr = am_absy(c, &pen); cmpv(c, c->a, rd(c, addr));
               cyc += 2 + pen; break;
    case 0xc1: cmpv(c, c->a, rd(c, am_indx(c))); cyc += 2; break;
    case 0xd1: addr = am_indy(c, &pen); cmpv(c, c->a, rd(c, addr));
               cyc += 3 + pen; break;
    case 0xe0: cmpv(c, c->x, rd(c, am_imm(c))); break;                  /* CPX */
    case 0xe4: cmpv(c, c->x, rd(c, am_zp(c)));  cyc += 1; break;
    case 0xec: cmpv(c, c->x, rd(c, am_abs(c))); cyc += 2; break;
    case 0xc0: cmpv(c, c->y, rd(c, am_imm(c))); break;                  /* CPY */
    case 0xc4: cmpv(c, c->y, rd(c, am_zp(c)));  cyc += 1; break;
    case 0xcc: cmpv(c, c->y, rd(c, am_abs(c))); cyc += 2; break;
    case 0x24: bitv(c, rd(c, am_zp(c)));  cyc += 1; break;              /* BIT */
    case 0x2c: bitv(c, rd(c, am_abs(c))); cyc += 2; break;

    /* ---- register inc/dec ---- */
    case 0xe8: c->x++; setzn(c, c->x); break;                           /* INX */
    case 0xca: c->x--; setzn(c, c->x); break;                           /* DEX */
    case 0xc8: c->y++; setzn(c, c->y); break;                           /* INY */
    case 0x88: c->y--; setzn(c, c->y); break;                           /* DEY */
    case 0xe6: addr = am_zp(c); v = rd(c, addr) + 1;                    /* INC */
               wr(c, addr, v); setzn(c, v); cyc += 3; break;
    case 0xf6: addr = am_zpx(c); v = rd(c, addr) + 1;
               wr(c, addr, v); setzn(c, v); cyc += 4; break;
    case 0xee: addr = am_abs(c); v = rd(c, addr) + 1;
               wr(c, addr, v); setzn(c, v); cyc += 4; break;
    case 0xfe: addr = am_absx(c, 0); v = rd(c, addr) + 1;
               wr(c, addr, v); setzn(c, v); cyc += 5; break;
    case 0xc6: addr = am_zp(c); v = rd(c, addr) - 1;                    /* DEC */
               wr(c, addr, v); setzn(c, v); cyc += 3; break;
    case 0xd6: addr = am_zpx(c); v = rd(c, addr) - 1;
               wr(c, addr, v); setzn(c, v); cyc += 4; break;
    case 0xce: addr = am_abs(c); v = rd(c, addr) - 1;
               wr(c, addr, v); setzn(c, v); cyc += 4; break;
    case 0xde: addr = am_absx(c, 0); v = rd(c, addr) - 1;
               wr(c, addr, v); setzn(c, v); cyc += 5; break;

    /* ---- accumulator shifts ---- */
    case 0x0a: c->a = aslv(c, c->a); break;                             /* ASL */
    case 0x4a: c->a = lsrv(c, c->a); break;                             /* LSR */
    case 0x2a: c->a = rolv(c, c->a); break;                             /* ROL */
    case 0x6a: c->a = rorv(c, c->a); break;                             /* ROR */

    /* ---- RMW shifts (zp 5 / zpx 6 / abs 6 / absx 7) ---- */
    case 0x06: addr = am_zp(c);  v = aslv(c, rd(c, addr));
               wr(c, addr, v); cyc += 3; break;
    case 0x16: addr = am_zpx(c); v = aslv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x0e: addr = am_abs(c); v = aslv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x1e: addr = am_absx(c, 0); v = aslv(c, rd(c, addr));
               wr(c, addr, v); cyc += 5; break;
    case 0x46: addr = am_zp(c);  v = lsrv(c, rd(c, addr));
               wr(c, addr, v); cyc += 3; break;
    case 0x56: addr = am_zpx(c); v = lsrv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x4e: addr = am_abs(c); v = lsrv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x5e: addr = am_absx(c, 0); v = lsrv(c, rd(c, addr));
               wr(c, addr, v); cyc += 5; break;
    case 0x26: addr = am_zp(c);  v = rolv(c, rd(c, addr));
               wr(c, addr, v); cyc += 3; break;
    case 0x36: addr = am_zpx(c); v = rolv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x2e: addr = am_abs(c); v = rolv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x3e: addr = am_absx(c, 0); v = rolv(c, rd(c, addr));
               wr(c, addr, v); cyc += 5; break;
    case 0x66: addr = am_zp(c);  v = rorv(c, rd(c, addr));
               wr(c, addr, v); cyc += 3; break;
    case 0x76: addr = am_zpx(c); v = rorv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x6e: addr = am_abs(c); v = rorv(c, rd(c, addr));
               wr(c, addr, v); cyc += 4; break;
    case 0x7e: addr = am_absx(c, 0); v = rorv(c, rd(c, addr));
               wr(c, addr, v); cyc += 5; break;

    /* ---- jumps / branches / stack / interrupts ---- */
    case 0x4c: c->pc = am_abs(c); cyc += 1; break;                      /* JMP */
    case 0x6c: {                                                        /* JMP (ind),
                 * with the 6502's page-wrap bug */
        unsigned p = rd16(c, c->pc); c->pc += 2;
        c->pc = (unsigned)rd(c, p)
              | ((unsigned)rd(c, (p & 0xff00) | ((p + 1) & 0xff)) << 8);
        cyc += 3;
        break;
    }
    case 0x20: {                                                        /* JSR */
        unsigned t = rd16(c, c->pc);    /* pc points at the last byte of
                                         * the JSR when pushed */
        push16(c, c->pc + 1);
        c->pc = t;
        cyc += 4;
        break;
    }
    case 0x60:                                                          /* RTS */
        c->pc = (pull16(c) + 1) & 0xffff; cyc += 4; break;
    case 0x40:                                                          /* RTI */
        c->p = (unsigned char)((pull8(c) & ~CPU6502_B) | CPU6502_U);
        c->pc = pull16(c); cyc += 4; break;
    case 0x00:                                                          /* BRK */
        c->pc = (c->pc + 1) & 0xffff;
        cyc += 5;
        do_interrupt(c, 0xfffe, 1);
        break;
    case 0x10: cyc += branch(c, !(c->p & CPU6502_N)); break;            /* BPL */
    case 0x30: cyc += branch(c,  (c->p & CPU6502_N)); break;            /* BMI */
    case 0x50: cyc += branch(c, !(c->p & CPU6502_V)); break;            /* BVC */
    case 0x70: cyc += branch(c,  (c->p & CPU6502_V)); break;            /* BVS */
    case 0x90: cyc += branch(c, !(c->p & CPU6502_C)); break;            /* BCC */
    case 0xb0: cyc += branch(c,  (c->p & CPU6502_C)); break;            /* BCS */
    case 0xd0: cyc += branch(c, !(c->p & CPU6502_Z)); break;            /* BNE */
    case 0xf0: cyc += branch(c,  (c->p & CPU6502_Z)); break;            /* BEQ */
    case 0xea: break;                                                   /* NOP */

    /* ---- illegal opcodes (the ones NSF engines use) ---- */
    case 0xa7: c->a = c->x = rd(c, am_zp(c)); setzn(c, c->a);           /* LAX */
               cyc += 1; break;
    case 0xaf: c->a = c->x = rd(c, am_abs(c)); setzn(c, c->a);
               cyc += 2; break;
    case 0xa3: c->a = c->x = rd(c, am_indx(c)); setzn(c, c->a);
               cyc += 4; break;
    case 0xb3: addr = am_indy(c, &pen); c->a = c->x = rd(c, addr);
               setzn(c, c->a); cyc += 3 + pen; break;
    case 0xb7: c->a = c->x = rd(c, am_zpy(c)); setzn(c, c->a);
               cyc += 2; break;
    case 0xbf: addr = am_absy(c, &pen); c->a = c->x = rd(c, addr);
               setzn(c, c->a); cyc += 2 + pen; break;
    case 0x87: wr(c, am_zp(c),  (unsigned char)(c->a & c->x));          /* SAX */
               cyc += 1; break;
    case 0x97: wr(c, am_zpy(c), (unsigned char)(c->a & c->x));
               cyc += 2; break;
    case 0x8f: wr(c, am_abs(c), (unsigned char)(c->a & c->x));
               cyc += 2; break;
    case 0x83: wr(c, am_indx(c), (unsigned char)(c->a & c->x));
               cyc += 2; break;
    case 0xc3: case 0xc7: case 0xcf: case 0xd3: case 0xd7: case 0xdb: {
        /* DCP: RMW M-1 then CMP A (indx 8 / zp 5 / zpx 6 / abs 6 /
         * absx 7 / indy 8) */
        switch (op) {
        case 0xc3: cyc += 6; addr = am_indx(c); break;
        case 0xc7: cyc += 3; addr = am_zp(c); break;
        case 0xd7: cyc += 4; addr = am_zpx(c); break;
        case 0xcf: cyc += 4; addr = am_abs(c); break;
        case 0xdb: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = (unsigned char)(rd(c, addr) - 1);
        wr(c, addr, v);
        cmpv(c, c->a, v);
        break;
    }
    case 0xe3: case 0xe7: case 0xef: case 0xf3: case 0xf7: case 0xff: {
        /* ISC: RMW M+1 then SBC (same cycle table as DCP) */
        switch (op) {
        case 0xe3: cyc += 6; addr = am_indx(c); break;
        case 0xe7: cyc += 3; addr = am_zp(c); break;
        case 0xf7: cyc += 4; addr = am_zpx(c); break;
        case 0xef: cyc += 4; addr = am_abs(c); break;
        case 0xff: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = (unsigned char)(rd(c, addr) + 1);
        wr(c, addr, v);
        sbc(c, v);
        break;
    }
    case 0x03: case 0x07: case 0x0f: case 0x13: case 0x17: case 0x1b: {
        /* SLO: RMW ASL then ORA */
        switch (op) {
        case 0x03: cyc += 6; addr = am_indx(c); break;
        case 0x07: cyc += 3; addr = am_zp(c); break;
        case 0x17: cyc += 4; addr = am_zpx(c); break;
        case 0x0f: cyc += 4; addr = am_abs(c); break;
        case 0x1b: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = aslv(c, rd(c, addr));
        wr(c, addr, v);
        c->a |= v;
        setzn(c, c->a);
        break;
    }
    case 0x23: case 0x27: case 0x2f: case 0x33: case 0x37: case 0x3b: {
        /* RLA: RMW ROL then AND */
        switch (op) {
        case 0x23: cyc += 6; addr = am_indx(c); break;
        case 0x27: cyc += 3; addr = am_zp(c); break;
        case 0x37: cyc += 4; addr = am_zpx(c); break;
        case 0x2f: cyc += 4; addr = am_abs(c); break;
        case 0x3b: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = rolv(c, rd(c, addr));
        wr(c, addr, v);
        c->a &= v;
        setzn(c, c->a);
        break;
    }
    case 0x43: case 0x47: case 0x4f: case 0x53: case 0x57: case 0x5b: {
        /* SRE: RMW LSR then EOR */
        switch (op) {
        case 0x43: cyc += 6; addr = am_indx(c); break;
        case 0x47: cyc += 3; addr = am_zp(c); break;
        case 0x57: cyc += 4; addr = am_zpx(c); break;
        case 0x4f: cyc += 4; addr = am_abs(c); break;
        case 0x5b: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = lsrv(c, rd(c, addr));
        wr(c, addr, v);
        c->a ^= v;
        setzn(c, c->a);
        break;
    }
    case 0x63: case 0x67: case 0x6f: case 0x73: case 0x77: case 0x7b: {
        /* RRA: RMW ROR then ADC */
        switch (op) {
        case 0x63: cyc += 6; addr = am_indx(c); break;
        case 0x67: cyc += 3; addr = am_zp(c); break;
        case 0x77: cyc += 4; addr = am_zpx(c); break;
        case 0x6f: cyc += 4; addr = am_abs(c); break;
        case 0x7b: cyc += 5; addr = am_absx(c, 0); break;
        default:   cyc += 6; addr = am_indy(c, 0); break;
        }
        v = rorv(c, rd(c, addr));
        wr(c, addr, v);
        adc(c, v);
        break;
    }
    case 0x0b: case 0x2b:                                               /* ANC */
        c->a &= rd(c, am_imm(c));
        setzn(c, c->a);
        c->p = (unsigned char)((c->p & ~CPU6502_C)
                               | (c->a & CPU6502_N));
        break;
    case 0x4b:                                                          /* ALR */
        c->a &= rd(c, am_imm(c));
        c->a = lsrv(c, c->a);
        break;
    case 0x6b: {                                                        /* ARR (one
                 * of the hardware-ambiguous behaviors; rare) */
        unsigned char m = rd(c, am_imm(c));
        unsigned char t = (unsigned char)(c->a & m);
        unsigned char cin = (unsigned char)((c->p & CPU6502_C) << 7);
        c->p = (unsigned char)((c->p & ~(CPU6502_C | CPU6502_V))
                               | (((t >> 6) & 1))
                               | ((((t >> 7) ^ (t >> 6)) & 1) << 6));
        c->a = (unsigned char)((t >> 1) | cin);
        setzn(c, c->a);
        break;
    }
    case 0xcb: {                                                        /* AXS/SBX */
        unsigned char m = rd(c, am_imm(c));
        unsigned r = (unsigned)(c->a & c->x) - m;
        c->p = (unsigned char)((c->p & ~CPU6502_C)
                               | (r < 0x100 ? CPU6502_C : 0));
        c->x = (unsigned char)r;
        setzn(c, c->x);
        break;
    }
    case 0x9c: {                                                        /* SHY */
        unsigned b = rd16(c, c->pc); c->pc += 2;
        wr(c, (b & 0xff) | ((((b >> 8) + 1) & 0xff) << 8),
           (unsigned char)(c->y & (((b >> 8) + 1) & 0xff)));
        cyc += 3;
        break;
    }
    case 0x9e: {                                                        /* SHX */
        unsigned b = rd16(c, c->pc); c->pc += 2;
        wr(c, (b & 0xff) | ((((b >> 8) + 1) & 0xff) << 8),
           (unsigned char)(c->x & (((b >> 8) + 1) & 0xff)));
        cyc += 3;
        break;
    }
    case 0x9f: {                                                        /* SHA/AHX */
        unsigned b = rd16(c, c->pc); c->pc += 2;
        wr(c, (b & 0xff) | ((((b >> 8) + 1) & 0xff) << 8),
           (unsigned char)(c->a & c->x & (((b >> 8) + 1) & 0xff)));
        cyc += 3;
        break;
    }
    case 0x93: {                                                        /* SHA (zp),y */
        unsigned b = am_indy(c, 0);
        wr(c, (b & 0xff) | ((((b >> 8) + 1) & 0xff) << 8),
           (unsigned char)(c->a & c->x & (((b >> 8) + 1) & 0xff)));
        cyc += 4;
        break;
    }
    case 0x9b: {                                                        /* TAS */
        unsigned b = rd16(c, c->pc); c->pc += 2;
        c->s = (unsigned char)(c->a & c->x);
        wr(c, (b & 0xff) | ((((b >> 8) + 1) & 0xff) << 8),
           (unsigned char)(c->a & c->x & (((b >> 8) + 1) & 0xff)));
        cyc += 3;
        break;
    }
    case 0xbb: {                                                        /* LAS */
        unsigned b = rd16(c, c->pc); c->pc += 2;
        unsigned a = (b + c->y) & 0xffff;
        c->a = c->x = c->s = (unsigned char)(rd(c, a) & c->s);
        setzn(c, c->a);
        cyc += 2 + (((b ^ a) & 0xff00) != 0);
        break;
    }

    /* ---- illegal NOP family ---- */
    case 0x80: case 0x82: case 0x89: case 0xc2: case 0xe2:              /* NOP # */
        c->pc += 1;
        break;
    case 0x04: case 0x44: case 0x64:                                    /* NOP zp */
        c->pc += 1; cyc += 1;
        break;
    case 0x0c:                                                          /* NOP abs */
        c->pc += 2; cyc += 2;
        break;
    case 0x14: case 0x34: case 0x54: case 0x74: case 0xd4: case 0xf4:
        c->pc += 1; cyc += 2; break;                                    /* NOP zp,x */
    case 0x1c: case 0x3c: case 0x5c: case 0x7c: case 0xdc: case 0xfc: {
        /* NOP abs,x (4 cycles + page-cross penalty) */
        unsigned b = rd16(c, c->pc);
        unsigned a = (b + c->x) & 0xffff;
        c->pc += 2;
        cyc += 2 + (((b ^ a) & 0xff00) != 0);
        break;
    }
    case 0x1a: case 0x3a: case 0x5a: case 0x7a: case 0xda: case 0xfa:
        break;                                                          /* NOP implied */

    default:
        /* unknown opcode: 2-cycle NOP, remembered; the KIL opcodes jam */
        c->illegal_seen++;
        if (op == 0x02 || op == 0x12 || op == 0x22 || op == 0x32
            || op == 0x42 || op == 0x52 || op == 0x62 || op == 0x72
            || op == 0x92 || op == 0xb2 || op == 0xd2 || op == 0xf2) {
            c->jam = 1;             /* KIL opcodes: the CPU is locked */
            c->pc--;                /* stay on the opcode */
        }
        break;
    }

    c->pc &= 0xffff;
    c->cycles += cyc;
    return cyc + 2;
}
