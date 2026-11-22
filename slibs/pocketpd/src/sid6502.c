/* sid6502.c - the 6502/6510 interpreter (see sid6502.h for the design
 * notes and why no third-party core is vendored). */

#include "sid6502.h"
#include <string.h>

#define RD(a) (c->rd(c->ud, (a) & 0xffff))
#define WR(a, v) (c->wr(c->ud, (a) & 0xffff, (v) & 0xff))

/* flags as a byte, the usual layout */
#define FZ_(v) ((v) ? 0 : S65_Z)

static void nz(sid6502 *c, int v) {
    v &= 0xff;
    c->p = (unsigned char)((c->p & ~(S65_N | S65_Z)) |
                           (v & S65_N) | FZ_(v));
}

static void pushb(sid6502 *c, int v) {
    WR(0x100 | c->sp, v);
    c->sp = (unsigned char)((c->sp - 1) & 0xff);
}

static int popb(sid6502 *c) {
    c->sp = (unsigned char)((c->sp + 1) & 0xff);
    return RD(0x100 | c->sp);
}

static int fetch16(sid6502 *c) {
    int lo = RD(c->pc++);
    int hi = RD(c->pc++);
    return lo | (hi << 8);
}

/* ------------------------------------------------------------- ALU ops */

static void adc_bin(sid6502 *c, int m) {
    int t = c->a + m + (c->p & S65_C ? 1 : 0);
    c->p = (unsigned char)((c->p & ~(S65_C | S65_V)) |
                           (t > 0xff ? S65_C : 0) |
                           (~(c->a ^ m) & (c->a ^ t) & 0x80 ? S65_V : 0));
    c->a = t & 0xff;
    nz(c, c->a);
}

/* NMOS decimal add: N/V/Z come from the binary result, C and the stored
 * byte from the BCD correction (like the 6502's documented behaviour) */
static void adc_dec(sid6502 *c, int m) {
    int lo = (c->a & 0x0f) + (m & 0x0f) + (c->p & S65_C ? 1 : 0);
    int hi, t;
    if (lo > 9) lo += 6;
    hi = (c->a >> 4) + (m >> 4) + (lo > 0x0f ? 1 : 0);
    t = c->a + m + (c->p & S65_C ? 1 : 0);
    nz(c, t);
    c->p = (unsigned char)((c->p & ~S65_V) |
                           (~(c->a ^ m) & (c->a ^ t) & 0x80 ? S65_V : 0));
    if (hi > 9) hi += 6;
    c->p = (unsigned char)((c->p & ~S65_C) | (hi > 0x0f ? S65_C : 0));
    c->a = ((hi << 4) | (lo & 0x0f)) & 0xff;
}

static void adc(sid6502 *c, int m) {
    if (c->p & S65_D) adc_dec(c, m); else adc_bin(c, m);
}

/* NMOS decimal subtract */
static void sbc(sid6502 *c, int m) {
    int cy = c->p & S65_C ? 0 : 1;
    int t = c->a - m - cy;
    if (c->p & S65_D) {
        int lo = (c->a & 0x0f) - (m & 0x0f) - cy;
        int hi = (c->a >> 4) - (m >> 4);
        int borrow = 0;
        if (lo < 0) { lo -= 6; borrow = 1; }
        if (hi < 0) hi -= 6;
        hi -= borrow;
        c->p = (unsigned char)((c->p & ~S65_V) |
                               ((c->a ^ m) & (c->a ^ t) & 0x80 ? S65_V : 0));
        c->p = (unsigned char)((c->p & ~S65_C) | (t >= 0 ? S65_C : 0));
        c->a = ((hi << 4) | (lo & 0x0f)) & 0xff;
        nz(c, c->a);
    } else {
        c->p = (unsigned char)((c->p & ~(S65_C | S65_V)) |
                               (t >= 0 ? S65_C : 0) |
                               ((c->a ^ m) & (c->a ^ t) & 0x80 ? S65_V : 0));
        c->a = t & 0xff;
        nz(c, c->a);
    }
}

static void cmpv(sid6502 *c, int r, int m) {
    int t = r - m;
    c->p = (unsigned char)((c->p & ~S65_C) | (t >= 0 ? S65_C : 0));
    nz(c, t);
}

static int bit_lsr(sid6502 *c, int t) {
    c->p = (unsigned char)((c->p & ~S65_C) | (t & 1));
    t >>= 1;
    nz(c, t);
    return t;
}

static int bit_asl(sid6502 *c, int t) {
    c->p = (unsigned char)((c->p & ~S65_C) | (t & 0x80));
    t = (t << 1) & 0xff;
    nz(c, t);
    return t;
}

static int bit_rol(sid6502 *c, int t) {
    int cin = c->p & S65_C ? 1 : 0;
    c->p = (unsigned char)((c->p & ~S65_C) | (t & 0x80));
    t = ((t << 1) | cin) & 0xff;
    nz(c, t);
    return t;
}

static int bit_ror(sid6502 *c, int t) {
    int cin = c->p & S65_C ? 1 : 0;
    c->p = (unsigned char)((c->p & ~S65_C) | (t & 1));
    t = (t >> 1) | (cin << 7);
    nz(c, t);
    return t;
}

/* undocumented RMW-combo cycle counts: zp 5, zpx 6, abs 6, absx/absy 7,
 * (zp,x) / (zp),y 8 (the low five bits are the same across all six
 * operation families) */
static int cyc_u(int op) {
    switch (op & 0x1F) {
    case 0x07: return 5;
    case 0x17: return 6;
    case 0x0F: return 6;
    case 0x1F: case 0x1B: return 7;
    case 0x03: case 0x13: return 8;
    }
    return 7;
}

/* -------------------------------------------------------------- decode */

void sid6502_init(sid6502 *c, unsigned char (*rd)(void *, int),
                  void (*wr)(void *, int, int), void *ud) {
    memset(c, 0, sizeof(*c));
    c->rd = rd;
    c->wr = wr;
    c->ud = ud;
    c->p = S65_U | S65_I;
    c->sp = 0xFD;
}

/* the switch below: operand fetch order is the hardware's (low byte
 * first, indexed zero pages wrap, indirect JMP carries the 6502 page
 * bug).  Cycle counts = official tables (page-cross penalty included
 * where the hardware adds one). */
int sid6502_step(sid6502 *c) {
    int addr, t, lo, hi, ptr, np;
    c->op = RD(c->pc++);
    switch (c->op) {

    /* ---- load / store ---- */
    case 0xA9: c->a = RD(c->pc++); nz(c, c->a); return 2;
    case 0xA5: addr = RD(c->pc++); c->a = RD(addr); nz(c, c->a); return 3;
    case 0xB5: addr = (RD(c->pc++) + c->x) & 0xff; c->a = RD(addr); nz(c, c->a); return 4;
    case 0xAD: addr = fetch16(c); c->a = RD(addr); nz(c, c->a); return 4;
    case 0xBD: addr = fetch16(c); np = addr; addr += c->x; c->a = RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xB9: addr = fetch16(c); np = addr; addr += c->y; c->a = RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xA1: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               c->a = RD(addr); nz(c, c->a); return 6;
    case 0xB1: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; c->a = RD(addr); nz(c, c->a);
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0xA2: c->x = RD(c->pc++); nz(c, c->x); return 2;
    case 0xA6: addr = RD(c->pc++); c->x = RD(addr); nz(c, c->x); return 3;
    case 0xB6: addr = (RD(c->pc++) + c->y) & 0xff; c->x = RD(addr); nz(c, c->x); return 4;
    case 0xAE: addr = fetch16(c); c->x = RD(addr); nz(c, c->x); return 4;
    case 0xBE: addr = fetch16(c); np = addr; addr += c->y; c->x = RD(addr);
               nz(c, c->x); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0xA0: c->y = RD(c->pc++); nz(c, c->y); return 2;
    case 0xA4: addr = RD(c->pc++); c->y = RD(addr); nz(c, c->y); return 3;
    case 0xB4: addr = (RD(c->pc++) + c->x) & 0xff; c->y = RD(addr); nz(c, c->y); return 4;
    case 0xAC: addr = fetch16(c); c->y = RD(addr); nz(c, c->y); return 4;
    case 0xBC: addr = fetch16(c); np = addr; addr += c->x; c->y = RD(addr);
               nz(c, c->y); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0x85: addr = RD(c->pc++); WR(addr, c->a); return 3;
    case 0x95: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, c->a); return 4;
    case 0x8D: addr = fetch16(c); WR(addr, c->a); return 4;
    case 0x9D: addr = fetch16(c); addr += c->x; WR(addr, c->a); return 5;
    case 0x99: addr = fetch16(c); addr += c->y; WR(addr, c->a); return 5;
    case 0x81: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               WR(addr, c->a); return 6;
    case 0x91: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8)) + c->y;
               WR(addr, c->a); return 6;

    case 0x86: addr = RD(c->pc++); WR(addr, c->x); return 3;
    case 0x96: addr = (RD(c->pc++) + c->y) & 0xff; WR(addr, c->x); return 4;
    case 0x8E: addr = fetch16(c); WR(addr, c->x); return 4;
    case 0x84: addr = RD(c->pc++); WR(addr, c->y); return 3;
    case 0x94: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, c->y); return 4;
    case 0x8C: addr = fetch16(c); WR(addr, c->y); return 4;

    /* ---- transfers ---- */
    case 0xAA: c->x = c->a; nz(c, c->x); return 2;
    case 0xA8: c->y = c->a; nz(c, c->y); return 2;
    case 0x8A: c->a = c->x; nz(c, c->a); return 2;
    case 0x98: c->a = c->y; nz(c, c->a); return 2;
    case 0xBA: c->x = c->sp; nz(c, c->x); return 2;
    case 0x9A: c->sp = c->x; return 2;

    /* ---- stack ---- */
    case 0x48: pushb(c, c->a); return 3;
    case 0x08: pushb(c, c->p | S65_B | S65_U); return 3;
    case 0x68: c->a = popb(c); nz(c, c->a); return 4;
    case 0x28: c->p = (unsigned char)((popb(c) & ~S65_B) | S65_U); return 4;

    /* ---- logic ---- */
    case 0x09: c->a |= RD(c->pc++); nz(c, c->a); return 2;
    case 0x05: addr = RD(c->pc++); c->a |= RD(addr); nz(c, c->a); return 3;
    case 0x15: addr = (RD(c->pc++) + c->x) & 0xff; c->a |= RD(addr); nz(c, c->a); return 4;
    case 0x0D: addr = fetch16(c); c->a |= RD(addr); nz(c, c->a); return 4;
    case 0x1D: addr = fetch16(c); np = addr; addr += c->x; c->a |= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x19: addr = fetch16(c); np = addr; addr += c->y; c->a |= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x01: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               c->a |= RD(addr); nz(c, c->a); return 6;
    case 0x11: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; c->a |= RD(addr); nz(c, c->a);
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0x29: c->a &= RD(c->pc++); nz(c, c->a); return 2;
    case 0x25: addr = RD(c->pc++); c->a &= RD(addr); nz(c, c->a); return 3;
    case 0x35: addr = (RD(c->pc++) + c->x) & 0xff; c->a &= RD(addr); nz(c, c->a); return 4;
    case 0x2D: addr = fetch16(c); c->a &= RD(addr); nz(c, c->a); return 4;
    case 0x3D: addr = fetch16(c); np = addr; addr += c->x; c->a &= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x39: addr = fetch16(c); np = addr; addr += c->y; c->a &= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x21: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               c->a &= RD(addr); nz(c, c->a); return 6;
    case 0x31: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; c->a &= RD(addr); nz(c, c->a);
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0x49: c->a ^= RD(c->pc++); nz(c, c->a); return 2;
    case 0x45: addr = RD(c->pc++); c->a ^= RD(addr); nz(c, c->a); return 3;
    case 0x55: addr = (RD(c->pc++) + c->x) & 0xff; c->a ^= RD(addr); nz(c, c->a); return 4;
    case 0x4D: addr = fetch16(c); c->a ^= RD(addr); nz(c, c->a); return 4;
    case 0x5D: addr = fetch16(c); np = addr; addr += c->x; c->a ^= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x59: addr = fetch16(c); np = addr; addr += c->y; c->a ^= RD(addr);
               nz(c, c->a); return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x41: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               c->a ^= RD(addr); nz(c, c->a); return 6;
    case 0x51: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; c->a ^= RD(addr); nz(c, c->a);
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    /* ---- arithmetic ---- */
    case 0x69: adc(c, RD(c->pc++)); return 2;
    case 0x65: addr = RD(c->pc++); adc(c, RD(addr)); return 3;
    case 0x75: addr = (RD(c->pc++) + c->x) & 0xff; adc(c, RD(addr)); return 4;
    case 0x6D: addr = fetch16(c); adc(c, RD(addr)); return 4;
    case 0x7D: addr = fetch16(c); np = addr; addr += c->x; adc(c, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x79: addr = fetch16(c); np = addr; addr += c->y; adc(c, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0x61: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               adc(c, RD(addr)); return 6;
    case 0x71: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; adc(c, RD(addr));
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0xE9: sbc(c, RD(c->pc++)); return 2;
    case 0xE5: addr = RD(c->pc++); sbc(c, RD(addr)); return 3;
    case 0xF5: addr = (RD(c->pc++) + c->x) & 0xff; sbc(c, RD(addr)); return 4;
    case 0xED: addr = fetch16(c); sbc(c, RD(addr)); return 4;
    case 0xFD: addr = fetch16(c); np = addr; addr += c->x; sbc(c, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xF9: addr = fetch16(c); np = addr; addr += c->y; sbc(c, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xE1: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               sbc(c, RD(addr)); return 6;
    case 0xF1: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; sbc(c, RD(addr));
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    /* ---- compares ---- */
    case 0xC9: cmpv(c, c->a, RD(c->pc++)); return 2;
    case 0xC5: addr = RD(c->pc++); cmpv(c, c->a, RD(addr)); return 3;
    case 0xD5: addr = (RD(c->pc++) + c->x) & 0xff; cmpv(c, c->a, RD(addr)); return 4;
    case 0xCD: addr = fetch16(c); cmpv(c, c->a, RD(addr)); return 4;
    case 0xDD: addr = fetch16(c); np = addr; addr += c->x; cmpv(c, c->a, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xD9: addr = fetch16(c); np = addr; addr += c->y; cmpv(c, c->a, RD(addr));
               return 4 + (((np ^ addr) & 0xff00) ? 1 : 0);
    case 0xC1: ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               cmpv(c, c->a, RD(addr)); return 6;
    case 0xD1: ptr = RD(c->pc++);
               addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8));
               np = addr; addr += c->y; cmpv(c, c->a, RD(addr));
               return 5 + (((np ^ addr) & 0xff00) ? 1 : 0);

    case 0xE0: cmpv(c, c->x, RD(c->pc++)); return 2;
    case 0xE4: addr = RD(c->pc++); cmpv(c, c->x, RD(addr)); return 3;
    case 0xEC: addr = fetch16(c); cmpv(c, c->x, RD(addr)); return 4;
    case 0xC0: cmpv(c, c->y, RD(c->pc++)); return 2;
    case 0xC4: addr = RD(c->pc++); cmpv(c, c->y, RD(addr)); return 3;
    case 0xCC: addr = fetch16(c); cmpv(c, c->y, RD(addr)); return 4;

    /* ---- bit test ---- */
    case 0x24: addr = RD(c->pc++); t = RD(addr);
               c->p = (unsigned char)((c->p & ~(S65_N | S65_V)) | (t & (S65_N | S65_V)));
               c->p = (unsigned char)((c->p & ~S65_Z) | FZ_(c->a & t));
               return 3;
    case 0x2C: addr = fetch16(c); t = RD(addr);
               c->p = (unsigned char)((c->p & ~(S65_N | S65_V)) | (t & (S65_N | S65_V)));
               c->p = (unsigned char)((c->p & ~S65_Z) | FZ_(c->a & t));
               return 4;

    /* ---- inc / dec ---- */
    case 0xE6: addr = RD(c->pc++); t = (RD(addr) + 1) & 0xff; WR(addr, t); nz(c, t); return 5;
    case 0xF6: addr = (RD(c->pc++) + c->x) & 0xff; t = (RD(addr) + 1) & 0xff; WR(addr, t); nz(c, t); return 6;
    case 0xEE: addr = fetch16(c); t = (RD(addr) + 1) & 0xff; WR(addr, t); nz(c, t); return 6;
    case 0xFE: addr = fetch16(c); addr += c->x; t = (RD(addr) + 1) & 0xff; WR(addr, t); nz(c, t); return 7;
    case 0xC6: addr = RD(c->pc++); t = (RD(addr) - 1) & 0xff; WR(addr, t); nz(c, t); return 5;
    case 0xD6: addr = (RD(c->pc++) + c->x) & 0xff; t = (RD(addr) - 1) & 0xff; WR(addr, t); nz(c, t); return 6;
    case 0xCE: addr = fetch16(c); t = (RD(addr) - 1) & 0xff; WR(addr, t); nz(c, t); return 6;
    case 0xDE: addr = fetch16(c); addr += c->x; t = (RD(addr) - 1) & 0xff; WR(addr, t); nz(c, t); return 7;
    case 0xE8: c->x = (unsigned char)((c->x + 1) & 0xff); nz(c, c->x); return 2;
    case 0xC8: c->y = (unsigned char)((c->y + 1) & 0xff); nz(c, c->y); return 2;
    case 0xCA: c->x = (unsigned char)((c->x - 1) & 0xff); nz(c, c->x); return 2;
    case 0x88: c->y = (unsigned char)((c->y - 1) & 0xff); nz(c, c->y); return 2;

    /* ---- shifts ---- */
    case 0x0A: c->a = bit_asl(c, c->a); return 2;
    case 0x06: addr = RD(c->pc++); WR(addr, bit_asl(c, RD(addr))); return 5;
    case 0x16: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, bit_asl(c, RD(addr))); return 6;
    case 0x0E: addr = fetch16(c); WR(addr, bit_asl(c, RD(addr))); return 6;
    case 0x1E: addr = fetch16(c); addr += c->x; WR(addr, bit_asl(c, RD(addr))); return 7;
    case 0x4A: c->a = bit_lsr(c, c->a); return 2;
    case 0x46: addr = RD(c->pc++); WR(addr, bit_lsr(c, RD(addr))); return 5;
    case 0x56: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, bit_lsr(c, RD(addr))); return 6;
    case 0x4E: addr = fetch16(c); WR(addr, bit_lsr(c, RD(addr))); return 6;
    case 0x5E: addr = fetch16(c); addr += c->x; WR(addr, bit_lsr(c, RD(addr))); return 7;
    case 0x2A: c->a = bit_rol(c, c->a); return 2;
    case 0x26: addr = RD(c->pc++); WR(addr, bit_rol(c, RD(addr))); return 5;
    case 0x36: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, bit_rol(c, RD(addr))); return 6;
    case 0x2E: addr = fetch16(c); WR(addr, bit_rol(c, RD(addr))); return 6;
    case 0x3E: addr = fetch16(c); addr += c->x; WR(addr, bit_rol(c, RD(addr))); return 7;
    case 0x6A: c->a = bit_ror(c, c->a); return 2;
    case 0x66: addr = RD(c->pc++); WR(addr, bit_ror(c, RD(addr))); return 5;
    case 0x76: addr = (RD(c->pc++) + c->x) & 0xff; WR(addr, bit_ror(c, RD(addr))); return 6;
    case 0x6E: addr = fetch16(c); WR(addr, bit_ror(c, RD(addr))); return 6;
    case 0x7E: addr = fetch16(c); addr += c->x; WR(addr, bit_ror(c, RD(addr))); return 7;

    /* ---- jumps / subroutines ---- */
    case 0x4C: c->pc = fetch16(c); return 3;
    case 0x6C: /* indirect, with the 6502 page-wrap bug */
        addr = fetch16(c);
        lo = RD(addr);
        hi = RD((addr & 0xff00) | ((addr + 1) & 0xff));
        c->pc = lo | (hi << 8);
        return 5;
    case 0x20: {   /* JSR: push the address of the operand's high byte */
        addr = fetch16(c);
        int r = (c->pc - 1) & 0xffff;
        pushb(c, r >> 8);
        pushb(c, r & 0xff);
        c->pc = addr;
        return 6;
    }
    case 0x60: c->pc = popb(c); c->pc |= popb(c) << 8; c->pc = (c->pc + 1) & 0xffff; return 6;
    case 0x40: c->p = (unsigned char)((popb(c) & ~S65_B) | S65_U);
               c->pc = popb(c); c->pc |= popb(c) << 8; return 6;
    case 0x00: /* BRK: skip the signature byte, go through the IRQ vector */
        c->pc = (c->pc + 1) & 0xffff;
        pushb(c, c->pc >> 8);
        pushb(c, c->pc & 0xff);
        pushb(c, c->p | S65_B | S65_U);
        c->p |= S65_I;
        c->pc = RD(0xFFFE) | (RD(0xFFFF) << 8);
        return 7;

    /* ---- branches ---- */
    case 0x10: np = (signed char)RD(c->pc++); if (!(c->p & S65_N)) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0x30: np = (signed char)RD(c->pc++); if (c->p & S65_N) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0x50: np = (signed char)RD(c->pc++); if (!(c->p & S65_V)) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0x70: np = (signed char)RD(c->pc++); if (c->p & S65_V) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0x90: np = (signed char)RD(c->pc++); if (!(c->p & S65_C)) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0xB0: np = (signed char)RD(c->pc++); if (c->p & S65_C) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0xD0: np = (signed char)RD(c->pc++); if (!(c->p & S65_Z)) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;
    case 0xF0: np = (signed char)RD(c->pc++); if (c->p & S65_Z) { t = c->pc + np; c->ticks += ((t ^ c->pc) & 0xff00) ? 2 : 1; c->pc = t & 0xffff; } return 2;

    /* ---- flag ops ---- */
    case 0x18: c->p &= ~S65_C; return 2;
    case 0x38: c->p |= S65_C; return 2;
    case 0x58: c->p &= ~S65_I; return 2;
    case 0x78: c->p |= S65_I; return 2;
    case 0xB8: c->p &= ~S65_V; return 2;
    case 0xD8: c->p &= ~S65_D; return 2;
    case 0xF8: c->p |= S65_D; return 2;

    /* ---- undocumented opcodes the HVSC repertoire actually executes ----
     * (RMW combos, LAX/SAX, the immediate-flag family).  These behave
     * exactly as on the NMOS 6510; the `illegal` counter stays for
     * opcodes with NO usable behaviour (JAM) and total unknowns. */
    case 0x07: case 0x17: case 0x0F: case 0x1F: case 0x1B: case 0x03: case 0x13: {
        /* SLO = ASL mem + ORA */
        if (c->op & 0x10) {                       /* abs, absx, absy */
            addr = fetch16(c);
            if (c->op == 0x1F) addr += c->x;
            else if (c->op == 0x1B) addr += c->y;
        } else if (c->op & 0x04) {                /* zp or zpx */
            addr = RD(c->pc++);
            if (c->op == 0x17) addr = (addr + c->x) & 0xff;
        } else {                                  /* (zp,x) or (zp),y */
            ptr = RD(c->pc++);
            if (c->op == 0x03) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0x13) addr += c->y;
        }
        t = bit_asl(c, RD(addr));
        WR(addr, t);
        c->a |= t; nz(c, c->a);
        return cyc_u(c->op);
    }
    case 0x27: case 0x37: case 0x2F: case 0x3F: case 0x3B: case 0x23: case 0x33: {
        /* RLA = ROL mem + AND */
        if (c->op & 0x10) {
            addr = fetch16(c);
            if (c->op == 0x3F) addr += c->x;
            else if (c->op == 0x3B) addr += c->y;
        } else if (c->op & 0x04) {
            addr = RD(c->pc++);
            if (c->op == 0x37) addr = (addr + c->x) & 0xff;
        } else {
            ptr = RD(c->pc++);
            if (c->op == 0x23) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0x33) addr += c->y;
        }
        t = bit_rol(c, RD(addr));
        WR(addr, t);
        c->a &= t; nz(c, c->a);
        return cyc_u(c->op);
    }
    case 0x47: case 0x57: case 0x4F: case 0x5F: case 0x5B: case 0x43: case 0x53: {
        /* SRE = LSR mem + EOR */
        if (c->op & 0x10) {
            addr = fetch16(c);
            if (c->op == 0x5F) addr += c->x;
            else if (c->op == 0x5B) addr += c->y;
        } else if (c->op & 0x04) {
            addr = RD(c->pc++);
            if (c->op == 0x57) addr = (addr + c->x) & 0xff;
        } else {
            ptr = RD(c->pc++);
            if (c->op == 0x43) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0x53) addr += c->y;
        }
        t = bit_lsr(c, RD(addr));
        WR(addr, t);
        c->a ^= t; nz(c, c->a);
        return cyc_u(c->op);
    }
    case 0x67: case 0x77: case 0x6F: case 0x7F: case 0x7B: case 0x63: case 0x73: {
        /* RRA = ROR mem + ADC */
        if (c->op & 0x10) {
            addr = fetch16(c);
            if (c->op == 0x7F) addr += c->x;
            else if (c->op == 0x7B) addr += c->y;
        } else if (c->op & 0x04) {
            addr = RD(c->pc++);
            if (c->op == 0x77) addr = (addr + c->x) & 0xff;
        } else {
            ptr = RD(c->pc++);
            if (c->op == 0x63) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0x73) addr += c->y;
        }
        t = bit_ror(c, RD(addr));
        WR(addr, t);
        adc(c, t);
        return cyc_u(c->op);
    }
    case 0xC7: case 0xD7: case 0xCF: case 0xDF: case 0xDB: case 0xC3: case 0xD3: {
        /* DCP = DEC mem + CMP A */
        if (c->op & 0x10) {
            addr = fetch16(c);
            if (c->op == 0xDF) addr += c->x;
            else if (c->op == 0xDB) addr += c->y;
        } else if (c->op & 0x04) {
            addr = RD(c->pc++);
            if (c->op == 0xD7) addr = (addr + c->x) & 0xff;
        } else {
            ptr = RD(c->pc++);
            if (c->op == 0xC3) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0xD3) addr += c->y;
        }
        t = (RD(addr) - 1) & 0xff;
        WR(addr, t);
        cmpv(c, c->a, t);
        return cyc_u(c->op);
    }
    case 0xE7: case 0xF7: case 0xEF: case 0xFF: case 0xFB: case 0xE3: case 0xF3: {
        /* ISC = INC mem + SBC */
        if (c->op & 0x10) {
            addr = fetch16(c);
            if (c->op == 0xFF) addr += c->x;
            else if (c->op == 0xFB) addr += c->y;
        } else if (c->op & 0x04) {
            addr = RD(c->pc++);
            if (c->op == 0xF7) addr = (addr + c->x) & 0xff;
        } else {
            ptr = RD(c->pc++);
            if (c->op == 0xE3) ptr = (ptr + c->x) & 0xff;
            addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
            if (c->op == 0xF3) addr += c->y;
        }
        t = (RD(addr) + 1) & 0xff;
        WR(addr, t);
        sbc(c, t);
        return cyc_u(c->op);
    }
    case 0x87: case 0x97: case 0x8F: case 0x83: {
        /* SAX = store A & X (no flags) */
        if (c->op == 0x8F) addr = fetch16(c);
        else if (c->op == 0x87) addr = RD(c->pc++);
        else if (c->op == 0x97) addr = (RD(c->pc++) + c->y) & 0xff;
        else { ptr = (RD(c->pc++) + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8); }
        WR(addr, c->a & c->x);
        return (c->op == 0x8F) ? 4 : (c->op == 0x87) ? 3 : 6;
    }
    case 0xA7: case 0xB7: case 0xAF: case 0xBF: case 0xA3: case 0xB3: {
        /* LAX = LDA + LDX */
        int cyc;
        if (c->op == 0xAF) { addr = fetch16(c); t = RD(addr); cyc = 4; }
        else if (c->op == 0xA7) { addr = RD(c->pc++); t = RD(addr); cyc = 3; }
        else if (c->op == 0xB7) { addr = (RD(c->pc++) + c->y) & 0xff; t = RD(addr); cyc = 4; }
        else if (c->op == 0xBF) { addr = fetch16(c) + c->y; t = RD(addr); cyc = 4; }
        else { ptr = RD(c->pc++);
               if (c->op == 0xA3) ptr = (ptr + c->x) & 0xff;
               addr = RD(ptr) | (RD((ptr + 1) & 0xff) << 8);
               if (c->op == 0xB3) addr += c->y;
               t = RD(addr); cyc = 6; }
        c->a = c->x = (unsigned char)t;
        nz(c, t);
        return cyc;
    }
    case 0xAB: {   /* LAX imm (unstable on silicon; A = X = imm is the
                    * approximation every replay engine uses) */
        t = RD(c->pc++);
        c->a = c->x = (unsigned char)t;
        nz(c, t);
        return 2;
    }
    case 0x0B: case 0x2B: {   /* ANC: AND imm, C = N */
        c->a &= RD(c->pc++);
        nz(c, c->a);
        c->p = (unsigned char)((c->p & ~S65_C) | (c->a & 0x80 ? S65_C : 0));
        return 2;
    }
    case 0x4B: {   /* ALR: AND imm, then LSR A */
        c->a &= RD(c->pc++);
        c->a = (unsigned char)bit_lsr(c, c->a);
        return 2;
    }
    case 0x6B: {   /* ARR: AND imm, then ROR A with the odd V/C semantics */
        c->a &= RD(c->pc++);
        c->a = (unsigned char)bit_ror(c, c->a);
        c->p = (unsigned char)((c->p & ~S65_V) |
                               (((c->a ^ (c->a << 1)) & 0x80) ? S65_V : 0));
        c->p = (unsigned char)((c->p & ~S65_C) | (c->a & 0x40 ? S65_C : 0));
        return 2;
    }
    case 0x8B: {   /* XAA (highly unstable; the A|0xEE approximation) */
        t = RD(c->pc++);
        c->a = (unsigned char)((c->a | 0xEE) & c->x & t);
        nz(c, c->a);
        return 2;
    }
    case 0xCB: {   /* SBX/AXS: X = (A & X) - imm */
        t = RD(c->pc++);
        int r = (c->a & c->x) - t;
        c->p = (unsigned char)((c->p & ~S65_C) | (r >= 0 ? S65_C : 0));
        c->x = (unsigned char)(r & 0xff);
        nz(c, c->x);
        return 2;
    }
    case 0x9C: {   /* SHY: store Y & (high byte + 1) */
        addr = fetch16(c);
        WR(addr, c->y & (((addr >> 8) + 1) & 0xff));
        return 5;
    }
    case 0x9E: {   /* SHX: store X & (high byte + 1) */
        addr = fetch16(c) + c->y;
        WR(addr, c->x & (((addr >> 8) + 1) & 0xff));
        return 5;
    }
    case 0x93: case 0x9F: {   /* AHX */
        if (c->op == 0x9F) {
            addr = fetch16(c) + c->y;
        } else {
            ptr = RD(c->pc++);
            addr = (RD(ptr) | (RD((ptr + 1) & 0xff) << 8)) + c->y;
        }
        WR(addr, c->a & c->x & (((addr >> 8) + 1) & 0xff));
        return 6;
    }
    case 0x9B: {   /* TAS: SP = A & X, then store A & X & (high + 1) */
        addr = fetch16(c) + c->y;
        c->sp = (unsigned char)(c->a & c->x);
        WR(addr, c->a & c->x & (((addr >> 8) + 1) & 0xff));
        return 5;
    }

    /* ---- undocumented NOPs (all stable, no side effects) ---- */
    case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA:
        return 2;
    case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2:
        c->pc++; return 2;
    case 0x04: case 0x44: case 0x64:
        c->pc++; return 3;
    case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4:
        c->pc++; return 4;
    case 0x0C:
        c->pc += 2; return 4;
    case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:
        c->pc += 2; return 4;

    /* ---- JAM family and anything else: 1-byte NOP so a malformed or
     * exotic tune can never hang the audio thread (counted, the player
     * reports it once) ---- */
    default:
        c->illegal++;
        return 2;
    }
}

long sid6502_call(sid6502 *c, int addr, long max_steps) {
    long steps = 0;
    /* sentinel return: the outermost RTS is recognised by the stack
     * pointer returning to its start level - the popped address itself is
     * never executed */
    c->sp = 0xFD;
    pushb(c, 0xff);
    pushb(c, 0xff);
    c->pc = (unsigned short)addr;
    while (steps < max_steps) {
        steps += sid6502_step(c);
        if (c->op == 0x60 && c->sp == 0xFD)
            return steps;
    }
    return -1;                  /* the tune never returned */
}
