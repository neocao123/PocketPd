//  ---------------------------------------------------------------------------
//  This file is part of reSID, a MOS6581 SID emulator engine.
//  Copyright (C) 1998 - 2022  Dag Lem <resid@nimrod.no>
//
//  This program is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 2 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//  ---------------------------------------------------------------------------

// PocketPd build configuration: the hand-filled counterpart of the
// upstream autoconf template (siddefs.h.in).  The values are configure's
// own no-C++20 fallbacks, so the vendored sources compile with the NDK's
// default C++ dialect and no special -std flag:
//   configure.ac: RESID_CONSTEXPR = "const",
//                 RESID_CONSTEVAL = ""     (no constexpr math: the
//                  extfilt coefficients use exp/log2, which are only
//                  constexpr-evaluable as a GCC/Clang extension -
//                  MSVC and older NDK clang reject them, so the
//                  ExternalFilterCoefficients stay runtime-initialized,
//                  exactly what configure does for pre-C++20),
//                 RESID_CONSTINIT = ""      (no constinit),
//                 RESID_INLINING  = 1, RESID_INLINE = inline,
//                 RESID_BRANCH_HINTS = 0, RESID_FPGA_CODE = 0 (default),
//                 HAVE_BUILTIN_EXPECT = 0.
//  RESID_CONSTEXPR is configure's literal "const" fallback (an NSDMI,
//  C++11); "static const" would need C++17 inline semantics, "static
//  constexpr" needs constexpr exp/log2 which only GCC/Clang provide.

#ifndef RESID_SIDDEFS_H
#define RESID_SIDDEFS_H

// Compilation configuration.
#define RESID_INLINING 1
#define RESID_INLINE inline
#define RESID_BRANCH_HINTS 0
#define RESID_FPGA_CODE 0

// Compiler specifics.
#define RESID_CONSTEVAL
#define RESID_CONSTEXPR const
#define RESID_CONSTINIT
#define HAVE_BUILTIN_EXPECT 0

// Branch prediction macros, lifted off the Linux kernel.
#if RESID_BRANCH_HINTS && HAVE_BUILTIN_EXPECT
#define likely(x)      __builtin_expect(!!(x), 1)
#define unlikely(x)    __builtin_expect(!!(x), 0)
#else
#define likely(x)      (x)
#define unlikely(x)    (x)
#endif

namespace reSID {

// We could have used the smallest possible data type for each SID register,
// however this would give a slower engine because of data type conversions.
// An int is assumed to be at least 32 bits (necessary in the types reg24
// and cycle_count). GNU does not support 16-bit machines
// (GNU Coding Standards: Portability between CPUs), so this should be
// a valid assumption.

typedef unsigned int reg4;
typedef unsigned int reg8;
typedef unsigned int reg12;
typedef unsigned int reg16;
typedef unsigned int reg24;

typedef int cycle_count;
typedef short short_point[2];
typedef double double_point[2];

enum chip_model { MOS6581, MOS8580 };

enum sampling_method { SAMPLE_FAST, SAMPLE_INTERPOLATE,
		       SAMPLE_RESAMPLE, SAMPLE_RESAMPLE_FASTMEM };

} // namespace reSID

// The upstream template also exposes resid_version_string, compiled only
// from version.cc against the autoconf VERSION macro.  version.cc is NOT
// vendored (nothing references it) and no stub is needed.

#endif // not RESID_SIDDEFS_H
