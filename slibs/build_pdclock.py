#!/usr/bin/env python3
"""build_pdclock.py - compile slibs/pdclock/pdclock.c into libpdclock.so for
every ABI jniLibs carries, straight into app/src/main/jniLibs/<abi>/. The
library is a JNI helper (like libpdtweaks.so), NOT a pd class library: it
has no core_classes.txt entry and never touches assets/slibs.

Toolchain invocation mirrors build_slibs.py (NDK 27.2.12479018 clang
wrappers, android-21, no pd headers needed - pd symbols are dlsym'd at
runtime).
"""
import subprocess
import sys
from pathlib import Path

NDK = Path("C:/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin")
ROOT = Path(__file__).resolve().parent
SRC = ROOT / "pdclock" / "pdclock.c"
OUT_ROOT = ROOT.parent / "app" / "src" / "main" / "jniLibs"

ABIS = {
    "arm64-v8a": "aarch64-linux-android21-clang.cmd",
    "armeabi-v7a": "armv7a-linux-androideabi21-clang.cmd",
    "x86": "i686-linux-android21-clang.cmd",
    "x86_64": "x86_64-linux-android21-clang.cmd",
}


def main():
    failed = 0
    for abi, cc in ABIS.items():
        out = OUT_ROOT / abi / "libpdclock.so"
        out.parent.mkdir(parents=True, exist_ok=True)
        cmd = [str(NDK / cc), "-O2", "-fPIC", "-shared",
               "-Wall", "-Wextra", str(SRC), "-o", str(out),
               "-llog", "-ldl"]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"{abi}: FAILED\n{r.stdout}{r.stderr}")
            failed = 1
        else:
            print(f"{abi}: {out} ({out.stat().st_size} bytes)"
                  + (f"\n{r.stdout}{r.stderr}" if r.stderr.strip() else ""))
    return failed


if __name__ == "__main__":
    sys.exit(main())
