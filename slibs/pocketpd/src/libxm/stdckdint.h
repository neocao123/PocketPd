/* Vendored libxm C23 shim: checked arithmetic via clang overflow
 * builtins (type-generic, same wrap-and-report semantics as C23
 * stdckdint.h). Only reachable when the toolchain lacks the real header. */
#ifndef POCKETPD_STDCKDINT_SHIM
#define POCKETPD_STDCKDINT_SHIM
#define ckd_add(r, a, b) __builtin_add_overflow((a), (b), (r))
#define ckd_sub(r, a, b) __builtin_sub_overflow((a), (b), (r))
#define ckd_mul(r, a, b) __builtin_mul_overflow((a), (b), (r))
#endif
