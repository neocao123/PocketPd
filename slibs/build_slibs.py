#!/usr/bin/env python3
"""Build pd-extended-era externals for Android (per-object .so files).

Output layout: app/src/main/assets/slibs/<abi>/<lib>/<class>.so
plus a class manifest app/src/main/assets/slibs/slibs_manifest.txt
(lines: lib<TAB>class<TAB>file) used by the app for name-conflict
reporting and for rename-on-extract of Windows-illegal file names.
"""
import json, os, re, subprocess, sys, shutil

SRCROOT = "C:/Users/Administrator/AppData/Local/Temp/srcrepo"
EX = SRCROOT + "/pd-extended/externals"
# 0.57 branch (0.18.1): slibs compile against the pd 0.57.0test1 headers -
# the EXACT tree the runtime libpd.so was built from (pd-for-android
# submodule pure-data@43a5b8922a35, vendored under the pd-core jni dir).
# Pre-0.18.1 this pointed at the pd-0.51-4 tree in %TEMP%/pdsrc; that path
# is kept below as a comment for the 0.51 line (freeze-0.51-base).
# PDSRC_051 = "C:/Users/Administrator/AppData/Local/Temp/pdsrc/pd-0.51-4/src"
PDSRC = "C:/Projects/pd-for-android/PdCore/pd-core/src/main/jni/libpd/pure-data/src"
AAR = "C:/Users/Administrator/AppData/Local/Temp/pdaar/jni"
PROJ = "C:/Projects/26PDAndroid"
NDK = "C:/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin"
NDKROOT = "C:/Android/Sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64"

ABIS = {
    "arm64-v8a": "aarch64-linux-android26-clang.cmd",
    "x86_64": "x86_64-linux-android26-clang.cmd",
}

# priority order = bare-name conflict resolution order (first wins)
LIBS = [
    # plugdata's DIY2 (ugur "doctor Wah" diy2): PURE-ABSTRACTION library —
    # no native core, every <name>.pd is an object. Listed FIRST here and in
    # Slibs.PRIORITY so its names win bare-name clashes by default.
    ("diy2", dict(
        src=[], support=[], inc=[],
        abstractions=["../../diy2"],
    )),
    ("cyclone", dict(
        src=["miXed/cyclone/hammer", "miXed/cyclone/sickle", "miXed/cyclone/shadow"],
        support=["miXed/shared/common", "miXed/shared/hammer",
                 "miXed/shared/sickle", "miXed/shared/unstable"],
        inc=["miXed/shared", "miXed/shared/common", "miXed/shared/hammer",
             "miXed/shared/sickle", "miXed/shared/unstable",
             "miXed/cyclone/hammer", "miXed/cyclone/sickle"],
        # hammer/ and sickle/ each ship atodb.c+dbtoa.c (control vs signal
        # variants) that clash on global atodb_new/dbtoa_new/convert - the
        # 10-03 baseline shipped NEITHER (not in the surviving 181-class
        # core_classes.txt), so keep excluding them
        excl=["cyclone.c", "standalone.c", "atodb.c", "dbtoa.c"],
        # Android rebuild (0.17.81, purr-data vintage): Max-SDK leftovers.
        # t_uint casts all feed %zx -> size_t is the exact type.  Scope's
        # cursor constants per porres pd-cyclone (1 = width locked,
        # 2 = height locked).
        patches={"Scope.c": [('#include "unstable/forky.h"',
                              '#include "unstable/forky.h"\n'
                              '/* Android rebuild: Max SDK cursor constants;\n'
                              ' * values per porres pd-cyclone (1 = width\n'
                              ' * locked, 2 = height locked) */\n'
                              '#define CURSOR_EDITMODE_RESIZE_Y 1\n'
                              '#define CURSOR_EDITMODE_RESIZE_X 2')],
                 # 0.57 rebuild (0.18.1): COSTABSIZE + cos_table left m_pd.h
                 # after 0.51 (d_osc.c internals since 0.55). The runtime
                 # libpd.so still allocates the 512-point back-compat table
                 # (OLDTABSIZE branch of cos_maketable, lazily on first
                 # cos~/osc~) and exports the symbol; sic.c guards NULL, so
                 # declaring it restores the 0.51 shared-table reuse. Sizes
                 # match: 0.57 OLDTABSIZE == sic.c's COSTABSIZE == 512.
                 "sic.c": [('#include "m_pd.h"',
                            '#include "m_pd.h"\n'
                            '/* Android 0.57 rebuild: pre-0.55 m_pd.h API\n'
                            ' * restored for the shared 512-pt cos table */\n'
                            '#ifndef COSTABSIZE\n'
                            '#define LOGCOSTABSIZE 9\n'
                            '#define COSTABSIZE (1<<LOGCOSTABSIZE)\n'
                            '#endif\n'
                            'extern float *cos_table;')],
                 "active.c": [("(t_uint)", "(size_t)")],
                 "comment.c": [("(t_uint)", "(size_t)")],
                 "pv.c": [("(t_uint)", "(size_t)")],
                 "file.c": [("(t_uint)", "(size_t)"),
                            ("sys_hostfontsize(glist_getfont(f->f_canvas))",
                             "sys_hostfontsize(glist_getfont(f->f_canvas), 0)")],
                 # atodb/dbtoa both export plain `convert` -> duplicate symbol
                 # in the one core blob; make them file-local (no-op change).
                # Leading-newline anchors keep the patches idempotent.
                 "atodb.c": [("\nt_float convert(t_float f);",
                              "\nstatic t_float convert(t_float f);"),
                             ("\nt_float convert(t_float f)\n",
                              "\nstatic t_float convert(t_float f)\n")],
                 "dbtoa.c": [("\nt_float convert(t_float f);",
                              "\nstatic t_float convert(t_float f);"),
                             ("\nt_float convert(t_float f)\n",
                              "\nstatic t_float convert(t_float f)\n")]},
    )),
    ("zexy", dict(
        src=["zexy/src"], support=[], inc=["zexy/src"],
        # zexy object files register via the zexy_new macro (no literal
        # class_new), so the collector files them under "supports" - with
        # support_setups their setup functions still get called from
        # core_wrap.
        support_setups=True,
        excl=["zexy.c", "z_zexy.c"],
        # lpt.c is a direct ANDROID STUB in srcrepo (see the file header):
        # bionic has no port I/O and no ppdev, the upstream text armed
        # Z_WANT_LPT and referenced ioperm/iopl which dlopen could not
        # resolve, and fresh upstream writes get emptied by the local AV
        # anyway - never hand-restore it.
    )),
    ("iemlib", dict(
        # upstream master merged iemlib1/iemlib2 sources into one top-level
        # src/ (old layout iemlib{1,2}/src kept only VC makefiles)
        src=["iemlib/src"], support=[],
        inc=["iemlib/src", "iemlib/include"],
        # aux is #included by iem_pbank_csv.c (needs its t_iem_pbank_csv);
        # a standalone support compile of it is meaningless and fails
        excl=["iemlib1.c", "iemlib2.c", "iem_pbank_csv_aux.c"],
        abstractions=["iemlib/iemabs"],
        patches={"iem_pbank_csv.c": [
            ("format->s_name[p] += 'a' - 'A';",
             "((char *)format->s_name)[p] += 'a' - 'A';")],
            # use pd's real t_widgetbehavior (the hand-rolled fallback
            # struct types w_visfn as t_method and mismatches 0.51)
            "protect_against_open.c": [
                ("#ifdef HAVE_G_CANVAS_H", "#if 1 /* Android rebuild */")]},
    )),
    ("fftease", dict(
        src=["../../fftease"], support=[], inc=["../../fftease"],
        # dentist~/disarrain~/disarray~ each define non-static rand_index /
        # reset_shuffle (same C symbols, different types) -> duplicate
        # symbols in the one core blob; make them file-local. Leading-newline
        # anchors keep the patches idempotent.
        patches={
            "dentist~.c": [("\nvoid reset_shuffle(t_dentist *x)",
                            "\nstatic void reset_shuffle(t_dentist *x)"),
                           ("\nint rand_index(int max)",
                            "\nstatic int rand_index(int max)")],
            "disarrain~.c": [("\nvoid reset_shuffle( t_disarrain *x )",
                              "\nstatic void reset_shuffle( t_disarrain *x )"),
                             ("\nvoid reset_shuffle (t_disarrain *x)",
                              "\nstatic void reset_shuffle (t_disarrain *x)"),
                             ("\nint rand_index(int max)",
                              "\nstatic int rand_index(int max)")],
            "disarray~.c": [("\nvoid reset_shuffle( t_disarray *x )",
                             "\nstatic void reset_shuffle( t_disarray *x )"),
                            ("\nvoid reset_shuffle (t_disarray *x)",
                             "\nstatic void reset_shuffle (t_disarray *x)"),
                            ("\nint rand_index( int max)",
                             "\nstatic int rand_index( int max)")],
        },
    )),
    ("bsaylor", dict(
        src=["bsaylor"], support=[], inc=["bsaylor"],
        # pvoc~ / partconv~ need FFTW3, a desktop dependency not bundled here.
        excl=["pvoc~.c", "partconv~.c"],
    )),
    ("creb", dict(
        # modules++ (the C++ complex-FFT family: bifft~/fht~/nfft~/dwt16~…)
        # survived the 2026-10-10 wipe - its sources are .cc, the cleaner
        # only removed .c files
        src=["creb/modules", "creb/modules++"], support=[], inc=["creb/modules"],
        abstractions=["creb/abs"],
    )),
    ("cxc", dict(
        src=["cxc"], support=[], inc=["cxc"],
    )),
    ("osc", dict(
        src=["mrpeach/osc"], support=[], inc=["mrpeach/osc"],
        excl=["osc-help.pd"],
    )),
    ("midifile", dict(
        src=["mrpeach/midifile"], support=[], inc=["mrpeach/midifile"],
    )),
    ("binfile", dict(
        src=["mrpeach/binfile"], support=[], inc=["mrpeach/binfile"],
    )),
    # PocketPd's own tracker library (XM/MOD sequencer + wav player);
    # sources live in the repo, not the pd-extended tree
    ("tracker", dict(
        src=["C:/Projects/26PDAndroid/slibs/tracker/src"], support=[], inc=[],
    )),
    # PocketPd's own coded objects (see slibs/pocketpd); the vendored
    # reSID engine (resid/, GPLv2+) needs its directory on the include
    # path for sidcore.cc's #include "sid.h"
    ("pocketpd", dict(
        src=["C:/Projects/26PDAndroid/slibs/pocketpd/src"], support=[],
        inc=["C:/Projects/26PDAndroid/slibs/pocketpd/src/resid",
             # vendored libxm (xm~ core): angle <xm.h> + the C23 shim
             # headers (stdckdint/stdbit) live here
             "C:/Projects/26PDAndroid/slibs/pocketpd/src/libxm"],
    )),
    # ELSE full core (0.18.2, W2.2): complete upstream RC15 set replacing
    # the vintage hand-integrated else_core.so (0.17.134-era, sources lost,
    # dead on 0.57 - see CHANGELOG 0.18.1 and slibs/else-src/full/
    # VENDORED.txt for provenance + exclusion justifications). Compiles
    # against the 0.57 headers natively (same as elsex below). Shared/
    # support files AND the vendored aubio sources under Shared/aubio/src
    # are pulled in by the recursive support walk (beat~ links aubio).
    ("else", dict(
        src=["C:/Projects/26PDAndroid/slibs/else-src/full/Control",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio"],
        support=["C:/Projects/26PDAndroid/slibs/else-src/full/Shared"],
        inc=[  # opus dirs FIRST: opus/celt carries its own kiss_fft.h, which
               # must win over Shared/kiss_fft for opus/src/analysis.c
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/opus/include",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/opus/celt",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/opus/silk",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/opus/silk/float",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/aubio/src",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/link",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/kiss_fft",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/libsamplerate",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Shared/fftease",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/plaits~",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/circuit~/Libraries",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/sfont~/FluidLite/include",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/sfont~/FluidLite/src",
             # SF3 (ogg-compressed soundfont) support chain headers
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/sfont~/FluidLite/libogg-1.3.2/include",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/sfont~/FluidLite/libvorbis-1.3.5/include",
             "C:/Projects/26PDAndroid/slibs/else-src/full/Audio/sfont~/FluidLite/libvorbis-1.3.5/lib"],
        # upstream GUI-bridge objects excluded (see VENDORED.txt): bare
        # names keyboard/pad/button/popmenu are PocketPd app-native touch
        # widgets; knob is already provided by elsex (identical RC15
        # source) and bare-name priority resolves it there.
        patches={"else.c": [
                     # [else] master: pdlua sublibrary not vendored -> drop
                     # the lua_setup call, keep the [else] info class
                     ("\nextern void lua_setup();",
                      "/* Android rebuild: pdlua sublibrary not vendored */\n"
                      "/* extern void lua_setup(); */"),
                     ("\n    lua_setup();\n}",
                      "\n    /* lua_setup(): pdlua not vendored (Android) */\n}")]},
        excl=["knob.c", "keyboard.c", "pad.c", "button.c", "popmenu.c",
              # external-dependency objects: play.file~ needs ffmpeg,
              # sfload/sfinfo need sfizz (sfz~'s vendored C++ engine),
              # streamout~/streamin~ need vorbis/ogg - none of those
              # vendor trees build (or exist) on Android here; upstream
              # ships them as external desktop libs.
              "play.file~.c", "sfload.c", "sfinfo.c",
              "streamout~.c", "streamin~.c",
              # stb_vorbis.c is #included INTO fluid_defsfont.c (single-file
              # decoder) - compiling it standalone would duplicate symbols
              "stb_vorbis.c"],
        # NOTE: Windows os.walk yields backslash paths - keep these as
        # bare substrings that match both slash styles where possible.
        excl_dirs=["ffmpeg", "sfz~",
                   "Shared\\vorbis", "Shared/vorbis",
                   "aubio\\examples", "aubio/examples",
                   "aubio\\build", "aubio/build",
                   # FluidLite's own build skips both: fluid_dsp_simple.c
                   # is an experiment fragment with code at file scope,
                   # fluidsynth.c is the CLI utility (defines main()).
                   "fluid_dsp_simple", "fluidsynth",
                   # opus arch-specific intrinsics need external libs
                   # (NE10 for arm NE10 paths) or x86 SIMD (SSE4.1), and
                   # dump_modes/analysis are dev tooling - the generic C
                   # paths cover Android builds.
                   "celt\\arm", "celt/arm", "silk\\arm", "silk/arm",
                   "silk\\x86", "silk/x86", "celt\\x86", "celt/x86",
                   "float\\x86", "float/x86",
                   # silk/fixed = FIXED-POINT build only (upstream CMake
                   # excludes it from float builds; its files reference
                   # fixed-only helpers like
                   # silk_insertion_sort_decreasing_int16)
                   "silk\\fixed", "silk/fixed",
                   "dump_modes",
                   # opus unit tests + cmake build helpers
                   "celt\\tests", "celt/tests", "silk\\tests",
                   "silk/tests", "src\\tests", "src/tests", "cmake"],
        # -DOPUS_BUILD + -DVAR_ARRAYS: standalone-build switches for the
        # vendored opus codec (pdlink/pdlink~ pull it via
        # Shared/opus_compression.h); only opus headers read them, harmless
        # everywhere else. VAR_ARRAYS selects VLA temporary allocation
        # (clang/Android supports VLAs).
        extra_flags=["-DOPUS_BUILD", "-DVAR_ARRAYS"],
        excl_main=True,
    )),
    # ELSE incremental classes (0.17.134): the hand-integrated else_core.so
    # predates upstream master, so a batch of classes referenced by the
    # newly shipped abstractions (mpe.in, ptouch.in/out, canvas.*,
    # osc.format/parse, tab*~, ...) never existed as compiled objects.
    # Vendored subset of porres/pd-else Source (GPL-3), see
    # slibs/else-src/NOTICE.txt. knob is excluded (needs the host GUI
    # bridge). Shared/ keeps only the three implementation files these
    # classes link against (buffer.c magic.c random.c); everything else
    # there is header-only for us.
    ("elsex", dict(
        src=["C:/Projects/26PDAndroid/slibs/else-src/Control",
             "C:/Projects/26PDAndroid/slibs/else-src/Audio"],
        support=["C:/Projects/26PDAndroid/slibs/else-src/Shared"],
        inc=["C:/Projects/26PDAndroid/slibs/else-src/Shared"],
        # 0.57 branch (0.18.1): inc_first=["else-src/pd55"] REMOVED - the
        # 0.57 headers in PDSRC carry the multichannel API natively
        # (s_nchans/s_length/CLASS_MULTICHANNEL/signal_setmultiout), so
        # elsex compiles against the same m_pd.h as the runtime libpd.so.
        # The 0.17.134-0.17.160-era pd55-header special case is dead on
        # this branch (the "offset-verified s_nchans@24" claim it was built
        # on is re-adjudicated in PD_VERSIONS.md 五A-1).
        # knob.c reads THISGUI->i_foregroundcolor - a pd 0.56-dev GUI theme
        # field the 0.55 runtime lacks. It only feeds the Tcl widget colors,
        # which headless libpd drops anyway: pin the color constant.
        patches={"knob.c": [("THISGUI->i_foregroundcolor",
                             'gensym("#000000")'),
                            ("THISGUI->i_backgroundcolor",
                             'gensym("#ffffff")'),
                            ("THISGUI->i_gopcolor",
                             'gensym("#ffffff")'),
                            ("THISGUI->i_selectcolor",
                             'gensym("#0000ff")')]},
    )),
]
# compat glue: IS_DENORMAL is a macro only under pd>=0.54 headers; on 0.51
# every creb module using it compiled an implicit function call instead.
EXTRA_C = {
    "creb": "int IS_DENORMAL(double x){ return x > -1.2e-38 && x < 1.2e-38; }\n",
    # purr-data cyclone calls the pd GUI-bridge (gui_* family) and two
    # purr-data pd-fork internals (glist_grabx, canvas_apply_setundo)
    # that the libpd runtime does not export - the 0.17.82 blobs
    # compiled fine (call sites have no prototypes and -w hides that)
    # but FAILED dlopen on device ("cannot locate symbol").  No-op
    # shims compiled into the blob resolve them locally: GUI messages
    # are dropped (headless anyway), comment.c's keyboard grab and
    # the undo hook do nothing.
    "cyclone": (
        "#include \"m_pd.h\"\n"
        "void gui_vmess(const char *cmd, const char *fmt, ...) "
        "{ (void)cmd; (void)fmt; }\n"
        "void gui_start_vmess(const char *cmd, const char *fmt, ...) "
        "{ (void)cmd; (void)fmt; }\n"
        "void gui_end_vmess(void) {}\n"
        "void gui_start_array(void) {}\n"
        "void gui_end_array(void) {}\n"
        "void gui_s(const char *s) { (void)s; }\n"
        "int glist_grabx(t_glist *g, t_gobj *gobj, void *motionfn, "
        "void *keyfn, int xpos, int ypos, int flag) "
        "{ (void)g; (void)gobj; (void)motionfn; (void)keyfn; "
        "(void)xpos; (void)ypos; (void)flag; return 0; }\n"
        "void canvas_apply_setundo(t_canvas *c) { (void)c; }\n"
    ),
}
# elsex shims, re-adjudicated for 0.57 (0.18.1): the 0.57 libpd.so now
# EXPORTS pdgui_vmess / pdgui_stub_vnew / pdgui_stub_deleteforkey /
# pd_undo_set_objectstate / signal_setmultiout (nm -D verified) - keeping
# the 0.17.137 no-op shims would SHADOW the real implementations and pin
# the mc classes to degraded mono forever, so they are dropped. Still
# missing from libpd (0.51.3 AND 0.57): sys_putmidibyte - else's midi-OUT
# classes feed it; the shim drops bytes, while the app's own
# noteout/ctlin bridge is a separate listener path. cyclone's gui_* /
# glist_grabx / canvas_apply_setundo purr-data internals remain
# un-exported in 0.57 too, so EXTRA_C["cyclone"] stays as-is.
EXTRA_C["elsex"] = (
    "#include <m_pd.h>\n"
    "void sys_putmidibyte(int portno, int byte) {\n"
    "    (void)portno; (void)byte;\n"
    "}\n"
)
# else full core (0.18.2): same midi-out drop as elsex - libpd (0.51.3 AND
# 0.57) does not export sys_putmidibyte; else's midi-OUT classes feed it,
# the app's own noteout/ctlin bridge is a separate listener path.
EXTRA_C["else"] = EXTRA_C["elsex"]


RE_CLASS = re.compile(r'class_new\s*\(\s*gensym\s*\(\s*"([^"]+)"')
RE_ADDCREATOR = re.compile(
    r'class_addcreator\s*\(\s*\(\s*t_newmethod\s*\)\s*\w+\s*,\s*gensym\s*\(\s*"([^"]+)"')
# ZEXY_SETUP (zexy.h) expands to NOTHING, so every zexy setup line is
# 'ZEXY_SETUP void xxx_setup(' - without this prefix in the pattern
# core_wrap called only 2 of ~102 zexy setups (pack/unpack, the two
# plain-void files) and the library registered 2 classes on device
# while all 107 setups merely sat exported-but-uncalled in the blob
# (0.17.81-0.17.83).  Accept any mixture of extern/ZEXY_SETUP prefixes.
RE_SETUP = re.compile(
    r'^\s*(?:(?:extern|ZEXY_SETUP)\s+)*void\s+([A-Za-z_]\w*_setup)\s*\(', re.M)
RE_SETUP_SYMBOL = re.compile(r'^\s*void\s+(setup_[A-Za-z0-9_]*)\s*\(', re.M)
RE_SETUP_CALLS = re.compile(r'\b([A-Za-z_]\w*_setup)\s*\(\s*\)\s*;')
RE_INCLUDE_C = re.compile(r'^\s*#include\s*"[^"]+\.c"', re.M)
ILLEGAL = set('\\/:*?"<>|')

def pd_symbols(cls):
    """Exact pd 0.51 setup-symbol derivation for a class name.
    For namespaced names pd only uses the part after the last '/'."""
    cls = cls.rsplit("/", 1)[-1]
    sym = ""
    hexm = False
    for i, c in enumerate(cls):
        if c.isalnum() or c == "_":
            sym += c
        elif c == "~" and i == len(cls) - 1:
            sym += "_tilde"
        else:
            sym += "0x%02x" % ord(c)
            hexm = True
    return ["setup_" + sym] if hexm else [sym + "_setup"]

def hexname(cls):
    return "".join(c if (c.isalnum() or c in "._-") else "0x%02x" % ord(c)
                   for c in cls) + ".so"

def win_safe(cls):
    return not (set(cls) & ILLEGAL)

CFLAGS = ["-c", "-DPD", "-Os", "-w", "-fPIC",
          "-Wno-implicit-function-declaration", "-Wno-implicit-int"]

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    so = r.stdout.decode(errors="replace") if isinstance(r.stdout, bytes) else (r.stdout or "")
    se = r.stderr.decode(errors="replace") if isinstance(r.stderr, bytes) else (r.stderr or "")
    return r.returncode, so + se

def collect(d, base):
    out = []
    for root, _, files in os.walk(d):
        for f in sorted(files):
            out.append(os.path.join(root, f))
    return out

def build_lib(name, cfg, abi, cc, outdir, workdir, manifest):
    if not cfg["src"] and not cfg.get("support"):
        # abstraction-only library: objects ship as plain .pd files (copied
        # in main), no native core is built or expected
        return 0, 0
    # inc_first dirs go BEFORE the 0.51 PDSRC include, overriding specific
    # headers (elsex: the runtime's own pd 0.55 m_pd.h for the multichannel
    # API - the offset-verified t_signal: s_n@0 s_vec@8 s_sr@16 s_nchans@24
    # matches ugen_connect in the shipped libpd.so, 0.17.134).
    inc = ["-I" + (p if os.path.isabs(p) else EX + "/" + p)
           for p in cfg.get("inc_first", [])]
    inc += ["-I" + PDSRC] + ["-I" + (p if os.path.isabs(p) else EX + "/" + p)
                             for p in cfg["inc"]]
    excl = set(cfg.get("excl", []))
    extra_flags = cfg.get("extra_flags", [])
    # excl_dirs: skip any source whose path contains one of these substrings
    # (vendor trees built as external libraries upstream - ELSE's Shared/
    # ffmpeg, sfz~'s sfizz/abseil, ogg-less vorbis dirs, test/example dirs).
    excl_dirs = cfg.get("excl_dirs", [])
    # excl_main: skip any source defining main() (CLI demos/tests vendored
    # inside third-party trees - pd objects never define main). Two mains
    # in one core blob = duplicate-symbol link failure.
    excl_main = cfg.get("excl_main", False)
    RE_MAIN_DEF = re.compile(r'\bmain\s*\(')
    # dead-code stripper for the main() check: upstream vendors test mains
    # behind #if 0 (opus celt/laplace.c) or self-test guards (_V_SELFTEST in
    # libogg/libvorbis framing.c/tone.c/sharedbook.c) - they never compile.
    # Over-stripping is safe here: a false "no main" only means the file is
    # compiled with its guarded main inert.
    RE_IFZERO = re.compile(
        r'#if\s+0\b.*?#endif|#ifdef\s+\w*SELFTEST\w*.*?#endif', re.S)
    patches = cfg.get("patches", {})
    objects, supports = [], []

    def srcdir(d):
        return d if os.path.isabs(d) else EX + "/" + d

    for d in cfg["src"]:
        for f in collect(srcdir(d), EX):
            b = os.path.basename(f)
            if not (b.endswith(".c") or b.endswith(".cc") or b.endswith(".cpp")) or b in excl or b.endswith(".inc.c"):
                continue
            if any(ed in f for ed in excl_dirs):
                continue
            text = open(f, encoding="utf-8", errors="replace").read()
            for old, new in patches.get(b, []):
                if old in text:
                    text = text.replace(old, new)
                    open(f, "w", newline="").write(text)
                    print("  patched: %s" % b)
            if excl_main and RE_MAIN_DEF.search(RE_IFZERO.sub("", text)):
                print("  main() skipped: %s" % b)
                continue
            if "class_new" in text or RE_ADDCREATOR.search(text):
                objects.append(f)
            elif RE_INCLUDE_C.search(text) and len(text) < 2000:
                # pure include-wrapper (e.g. cxc cxmean.c #include "mean~.c"):
                # its code lives in the included file, skip entirely. Size
                # guard: vendored consolidations (FluidLite's fluid_defsfont.c
                # pulls stb_vorbis.c into 1700+ lines of its own code) must
                # NOT be skipped - only tiny wrappers are.
                print("  include-wrapper skipped: %s" % b)
            else:
                supports.append(f)
    for d in cfg.get("support", []):
        # srcdir() honors absolute paths - elsex's support dir lives in the
        # workspace, and the old EX + "/" + d join silently produced a
        # nonexistent path: os.walk returned nothing, buffer/magic/random/
        # mouse_gui never linked, and every class calling them dlopen-failed
        # with "cannot locate symbol" (0.17.134-0.17.135 bug)
        for f in collect(srcdir(d), EX):
            b = os.path.basename(f)
            if any(ed in f for ed in excl_dirs):
                continue
            if (f.endswith(".c") or f.endswith(".cc") or f.endswith(".cpp")) and b not in excl and not b.endswith(".inc.c"):
                if excl_main and RE_MAIN_DEF.search(RE_IFZERO.sub("", open(f, encoding="utf-8", errors="replace").read())):
                    print("  main() skipped: %s" % b)
                    continue
                supports.append(f)
    # masters: call many foreign setups, define no class of their own -> drop
    kept = []
    for f in objects:
        text = open(f, encoding="utf-8", errors="replace").read()
        defined = set(RE_SETUP.findall(text))
        classes = set(RE_CLASS.findall(text))
        calls = set(RE_SETUP_CALLS.findall(text))
        if len(classes) == 0 and len(calls - defined) >= 3:
            print("  master dropped: %s" % os.path.basename(f))
            continue
        kept.append(f)
    objects = kept

    os.makedirs(workdir, exist_ok=True)

    ok, fail = 0, 0
    # one core .so per library: cross-referencing objects (cyclone shadow
    # compat -> Append_setup, fftease helpers) need every sibling present.
    core_setups, core_objs, core_sup = [], [], []
    c_classes = set()   # C classes of this lib -> core_classes.txt (symlinks)
    for f in objects:
        text = open(f, encoding="utf-8", errors="replace").read()
        for old_, new_ in patches.get(os.path.basename(f), []):
            if old_ in text:
                text = text.replace(old_, new_)
        classes = list(dict.fromkeys(
            RE_CLASS.findall(text) + RE_ADDCREATOR.findall(text)))
        defined = set(RE_SETUP.findall(text)) | set(RE_SETUP_SYMBOL.findall(text))
        if not classes and not defined:
            print("  no classes, skipped: %s" % os.path.basename(f))
            continue
        o = os.path.join(workdir, hashlib_name(f) + ".o")
        code, log = run([cc] + CFLAGS + extra_flags + inc + [f, "-o", o])
        if code:
            fail += 1
            print("  OBJ FAIL %s\\n%s" % (f, log[-800:]))
            continue
        if os.path.getsize(o) < 2048:
            # empty translation unit: the whole file is #if 0 / platform-gated
            print("  empty TU skipped: %s" % os.path.basename(f))
            continue
        uniquify_generic_symbols(o, cc)
        core_objs.append(o)
        core_setups.append(list(dict.fromkeys(
            s2 for s2 in defined if s2.endswith("_setup"))))
        for cls in classes:
            manifest.append((name, cls, "%s_core.so" % name))
            c_classes.add(cls)
            ok += 1
    for f in supports:
        text = open(f, encoding="utf-8", errors="replace").read()
        for old_, new_ in patches.get(os.path.basename(f), []):
            if old_ in text:
                text = text.replace(old_, new_)
                open(f, "w", newline="").write(text)
        o = os.path.join(workdir, hashlib_name(f) + ".o")
        code, log = run([cc] + CFLAGS + extra_flags + inc + [f, "-o", o])
        if code:
            fail += 1
            print("  SUPPORT FAIL %s\\n%s" % (f, log[-800:]))
            continue
        # macro-registering libraries (zexy: ~100 support-file objects with
        # setup_<name> exports and some bare `setup` helpers) need the same
        # per-object generic-symbol rename as the object loop, or the core
        # link dies on duplicate `setup`
        uniquify_generic_symbols(o, cc)
        core_sup.append(o)
        if cfg.get("support_setups"):
            # macro-registering libraries (zexy): the collected "supports"
            # ARE the object files - their setups must be called from
            # core_wrap just like the object loop's, or the classes stay
            # exported-but-uncalled (0.17.81-0.17.83 zexy regression)
            defined = set(RE_SETUP.findall(text)) | set(
                RE_SETUP_SYMBOL.findall(text))
            core_setups.append(list(dict.fromkeys(
                s2 for s2 in defined
                if s2.endswith("_setup") or s2.startswith("setup_"))))
            # their CLASS names go into the manifest + core_classes.txt too:
            # the objects loop's class_new scan never sees macro
            # registrations (zexy_new("<name>", ...)), so the manifest
            # shipped with 2 of ~101 zexy rows and the editor drew every
            # [zexy/...] object red (0.17.81-0.17.90)
            for m in re.finditer(r'zexy_new\s*\(\s*"([^"]+)"', text):
                clsname = m.group(1)
                if clsname not in c_classes:
                    manifest.append((name, clsname, "%s_core.so" % name))
                    c_classes.add(clsname)
                    ok += 1
    decl = "".join("extern void %s(void);\n" % s2
                   for lst in core_setups for s2 in lst)
    body = "".join("    %s();\n" % s2
                   for lst in core_setups for s2 in lst)
    extra = EXTRA_C.get(name, "")
    if extra:
        ec = os.path.join(workdir, "extra_compat.c")
        open(ec, "w").write(extra)
        eco = os.path.join(workdir, "extra_compat.c.o")
        code, log = run([cc] + CFLAGS + extra_flags + inc + [ec, "-o", eco])
        if code == 0:
            core_sup.append(eco)
        else:
            # a silently-missing compat object re-creates the 0.17.82
            # dlopen failure mode; the blob audit catches it too, but
            # fail loud here where the cause is visible
            print("  EXTRA_C COMPILE FAIL %s\\n%s" % (name, log[-800:]))
            fail += 1
    wsrc = decl + "void %s_core_setup(void){\n%s}\n" % (name, body or "    ;")
    print("  core_wrap calls %d setups (classes ok=%d)"
          % (body.count("_setup();"), ok))
    wc = os.path.join(workdir, "core_wrap.c")
    open(wc, "w").write(wsrc)
    wobj = wc + ".o"
    code, log = run([cc] + CFLAGS + extra_flags + inc + [wc, "-o", wobj])
    if code:
        print("  CORE WRAP FAIL %s: %s" % (name, log[-500:]))
        return ok, fail + 1
    if not core_objs and cfg["src"]:
        # GUARD (2026-11-16 incident): empty source collection used to link
        # an empty <lib>_core.so and silently OVERWRITE the last good blob -
        # the gutted srcrepo shipped dead libraries for weeks unnoticed.
        # Never link when the sources yielded nothing; the existing blob
        # on disk stays untouched.
        print("  *** GUARD: no objects compiled for '%s' - NOT linking, "
              "existing blob preserved. Fix the source tree." % name)
        return ok, fail + 1
    os.makedirs(outdir, exist_ok=True)
    blob = os.path.join(outdir, "%s_core.so" % name)
    # link to a temp name and move over the real blob only on success: a
    # failed link must never take the existing (possibly last-good) blob down
    tmplink = blob + ".tmp"
    rsp = os.path.join(workdir, "link.rsp")
    flat = [p.replace(chr(92), "/") for p in core_objs + core_sup + [wobj]]
    open(rsp, "w").write(" ".join('"%s"' % p for p in flat))
    extra_link = ["-lz"] if name == "pocketpd" else []
    if name in ("creb", "pocketpd", "else"):
        # C++ TUs (creb modules++, pocketpd's vendored reSID engine under
        # resid/) use operator new/delete and the exception personality;
        # the libpd runtime loads no C++ runtime, so static-link libc++
        # (NDK merged libc++abi) into the blob - dlopen resolves them
        # internally.
        triple = {"arm64-v8a": "aarch64-linux-android",
                  "x86_64": "x86_64-linux-android"}[abi]
        extra_link += [NDKROOT + "/sysroot/usr/lib/%s/21/libc++.a" % triple]
    code, log = run([cc, "-shared", "-DPD", "-o", tmplink, "@" + rsp,
                     "-L" + AAR + "/" + abi, "-lpd"] + extra_link)
    if code:
        fail += 1
        print("  CORE LINK FAIL %s\\n%s" % (name, log[-1200:]))
        if os.path.exists(tmplink):
            os.remove(tmplink)
        return ok, fail
    os.replace(tmplink, blob)
    run([NDK + "/llvm-strip.exe", blob])
    # core_classes.txt: the <class>.so symlink list pd's binary loader needs
    # (it dlopens exactly <dir>/<class>.so). Additive merge - never remove a
    # line, so hand-curated historical sets keep working; new classes land
    # here automatically (vgm~ was missed in 0.17.68 because this file was
    # hand-maintained - the "new object shows 1-in/1-out" bug class).
    if c_classes:
        cc_txt = os.path.join(outdir, "core_classes.txt")
        old = []
        if os.path.isfile(cc_txt):
            old = [l.strip() for l in open(cc_txt, encoding="utf-8",
                                           errors="replace").read().splitlines()]
        merged = list(dict.fromkeys(old + sorted(c_classes)))
        # manifest catch-all: core_classes.txt is the authoritative per-lib
        # class set - it also carries the surviving baseline entries and
        # classes registered through variables (zexy's lister =
        # zexy_new(name, ...), invisible to both the class_new scan and the
        # zexy_new literal scan). The design doc's own recipe: regenerate
        # the manifest from core_classes.txt + the .pd file lists.
        have = set(r[1] for r in manifest if r[0] == name)
        for clsname in merged:
            if clsname not in have:
                manifest.append((name, clsname, "%s_core.so" % name))
        if merged != old:
            open(cc_txt, "w", newline="\n").write(
                "".join(l + "\n" for l in merged))
            print("  core_classes.txt: %s" % ", ".join(merged))
    return ok, fail

def uniquify_generic_symbols(o, cc):
    """Some old sources define a non-static helper literally named `setup`;
    rename it per object so one library core can link every file together."""
    code, log = run([NDK + "/llvm-nm.exe", "-g", "--defined-only", o])
    needle = " T setup" + chr(10)
    if needle not in log and not log.endswith(" T setup"):
        return
    new = "setup_%s" % hashlib_name(o)
    code, log = run([NDK + "/llvm-objcopy.exe",
                     "--redefine-sym=setup=%s" % new, o])
    if code:
        print("  UNIQUIFY FAIL %s: %s" % (os.path.basename(o), log[-300:]))
    else:
        print("  uniquified setup -> %s" % new)

def hashlib_name(f):
    import hashlib
    return hashlib.sha1(f.encode()).hexdigest()[:16]

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    man_lines = []
    for abi, ccname in ABIS.items():
        cc = NDK + "/" + ccname
        for name, cfg in LIBS:
            if only and only != name:
                continue
            outdir = "%s/app/src/main/assets/slibs/%s/%s" % (PROJ, abi, name)
            workdir = "C:/Users/Administrator/AppData/Local/Temp/slbwork/%s/%s" % (abi, name)
            print("== %s / %s" % (abi, name), flush=True)
            ok, fail = build_lib(name, cfg, abi, cc, outdir, workdir, man_lines)
            # ship plain abstractions alongside compiled objects
            for ad in cfg.get("abstractions", []):
                adir = EX + "/" + ad
                if os.path.isdir(adir):
                    os.makedirs(outdir, exist_ok=True)
                    for f in collect(adir, EX):
                        b = os.path.basename(f)
                        if b.endswith(".pd") and not b.endswith("-help.pd"):
                            shutil.copyfile(f, os.path.join(outdir, b))
                            # abstraction-only libraries have no core .so to
                            # derive classes from; the .pd name IS the class
                            if not cfg["src"]:
                                man_lines.append((name, b[:-3], b))
            print("== %s / %s: ok=%d fail=%d" % (abi, name, ok, fail), flush=True)
    manifest_path = PROJ + "/app/src/main/assets/slibs/slibs_manifest.txt"
    if only:
        # single-lib build: keep other libraries' rows, replace this one's
        built = [l for l in man_lines if l[0] == only]
        try:
            with open(manifest_path) as f:
                kept = [tuple(line.split("\t"))
                        for line in f.read().splitlines() if line]
            kept = [l for l in kept if l[0] != only]
        except FileNotFoundError:
            kept = []
        man_lines = kept + built
    # backfill libraries shipped under assets/slibs/ that this script does
    # not manage (else: integrated by hand, source tree no longer present -
    # it cannot be rebuilt here).  Without manifest rows the app's
    # known-class set misses every compiled else class, so the editor drew
    # working objects (rand.i / rand.f / ...) red (0.17.120 lesson).  The
    # class list comes from each lib's own core_classes.txt, written at
    # ITS build time from the exported setup symbols.
    managed = set(n for n, _ in LIBS)
    for abi in ABIS:
        base = "%s/app/src/main/assets/slibs/%s" % (PROJ, abi)
        have = set(l for l, _, _ in man_lines)
        for d in sorted(os.listdir(base)):
            cc = os.path.join(base, d, "core_classes.txt")
            if d in managed or d in have or not os.path.isfile(cc):
                continue
            for cls in open(cc, encoding="utf-8", errors="replace"):
                cls = cls.strip()
                if cls:
                    man_lines.append((d, cls, "%s_core.so" % d))
            print("backfilled manifest rows for unmanaged lib '%s'" % d)
    with open(manifest_path, "w") as f:
        for lib, cls, fname in man_lines:
            f.write("%s\t%s\t%s\n" % (lib, cls, fname))
    print("manifest entries: %d" % len(man_lines))

if __name__ == "__main__":
    main()
