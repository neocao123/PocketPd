/* Vendored libxm C23 shim: the one stdbit.h function the player uses,
 * via the clang builtin. Only reachable when the toolchain lacks the
 * real header. */
#ifndef POCKETPD_STDBIT_SHIM
#define POCKETPD_STDBIT_SHIM
#define stdc_count_ones(x) __builtin_popcountl((unsigned long)(x))
#endif
