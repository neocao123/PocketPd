#!/usr/bin/env python3
"""dlopen pre-flight for the slibs blobs: every undefined dynamic symbol
of each <lib>_core.so must resolve somewhere at load time - the libpd
runtime (libpd.so + the AAR's other libs), bionic libc/libm/liblog/libdl,
or libz.  Anything else = "cannot locate symbol" at PdTweaks.nativeLoadCore
on device, exactly the 0.17.81/0.17.82 failure mode (cyclone gui_*/
glist_grabx/canvas_apply_setundo, zexy ioperm/iopl, creb operator-new/
personality) that the symbol-EXPORT acceptance check could not see.

Run after every build_slibs.py; exit code 1 = unusable blob set.
"""
import os
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SLIBS = os.path.join(PROJ, "app/src/main/assets/slibs")
NDK = ("C:/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/"
       "windows-x86_64")
NM = os.path.join(NDK, "bin", "llvm-nm.exe")
# symbols these libraries always provide at load time on device
ALWAYS = ("libc.so", "libm.so", "liblog.so", "libdl.so", "libz.so",
          "libandroid.so", "libEGL.so", "libGLESv2.so")
# ABI -> linker namespace triples
TRIPLE = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
API = "21"

# the app's own libs whose exports are loadable at pd-init time (they are
# loaded by the framework before PdTweaks.nativeLoadCore runs); pd symbols
# come from libpd.so, so any pd API the cores call must be exported there.
APK_LIBS = ("libpd.so", "libpdnative.so", "libpdnativeopensl.so",
            "libpdtweaks.so", "libbob_tilde.so", "libbonk_tilde.so",
            "libchoice.so", "libfiddle_tilde.so", "libloop_tilde.so",
            "liblrshift_tilde.so", "libpique.so", "libsigmund_tilde.so",
            "libstdout.so")


def defined_so(path):
    r = subprocess.run([NM, "-D", "--defined-only", path],
                       capture_output=True, text=True)
    out = set()
    for line in r.stdout.splitlines():
        parts = line.split()
        if parts and parts[0] not in ("U", "w", "v") and len(parts) >= 2:
            sym = parts[-1].split("@")[0].lstrip(".")
            if sym:
                out.add(sym)
    return out


def undef_syms(path):
    r = subprocess.run([NM, "-D", "--undefined-only", path],
                       capture_output=True, text=True)
    out = []
    for line in r.stdout.splitlines():
        parts = line.split()
        if parts and parts[0] == "U" and len(parts) >= 2:
            sym = parts[-1].split("@")[0].lstrip(".")
            if sym:
                out.append(sym)
    return out


def audit(abi):
    # the device runs a modern API level: use the NEWEST libc the sysroot
    # carries (API 21's libc lacks __register_atfork etc. that newer
    # bionic exports and the shipped blobs provably use on device)
    apidir = os.path.join(NDK, "sysroot", "usr", "lib", TRIPLE[abi])
    levels = [d for d in os.listdir(apidir) if d.isdigit()]
    newest = max(levels, key=int)
    sysroot = os.path.join(apidir, newest)
    apkdir = os.path.join("C:/Temp/slibs-audit-" + abi, "lib", abi)
    resolved = set()
    for name in APK_LIBS:
        p = os.path.join(apkdir, name)
        if os.path.isfile(p):
            resolved |= defined_so(p)
    for name in ALWAYS:
        p = os.path.join(sysroot, name)
        if os.path.isfile(p):
            resolved |= defined_so(p)
    if not resolved:
        print("audit: no resolvable set for %s - wrong paths?" % abi)
        return 1
    # symbols the already-shipped, device-verified blobs reference and that
    # resolve via the loader on the real devices (not in the API sysroot
    # export lists we check against)
    waived = {"stderr"}
    bad = 0
    d = os.path.join(SLIBS, abi)
    for lib in sorted(os.listdir(d)):
        blob = os.path.join(d, lib, "%s_core.so" % lib)
        if not os.path.isfile(blob):
            continue
        misses = [s for s in undef_syms(blob)
                  if s not in resolved and s not in waived]
        if misses:
            bad += 1
            print("%s/%s: %d UNRESOLVED -> %s" %
                  (abi, lib, len(misses), ", ".join(sorted(misses)[:12])))
        else:
            print("%s/%s: ok" % (abi, lib))
    return 1 if bad else 0


if __name__ == "__main__":
    rc = 0
    for abi in ("arm64-v8a", "x86_64"):
        print("== %s ==" % abi)
        rc |= audit(abi)
    sys.exit(rc)
