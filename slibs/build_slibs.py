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
# cyclone 0.9-5 (0.18.13): porres/pd-cyclone tag 0.9-5 replaces the
# pd-extended 0.43.4 miXed snapshot. NOTE: the %TEMP%/pdaar extraction is a
# STALE libpd build - for runtime-symbol ground truth use the mavenLocal
# AAR's jni/<abi>/libpd.so (or the C:/Temp/slibs-audit-<abi> APK extraction
# audit_blobs.py reads): the real 0.57 libpd.so DOES export pdgui_vmess /
# pd_undo_set_objectstate / signal_setmultiout; the stale pdaar blob does
# not, and reading it sent one analysis pass chasing phantom dlopen fails.
CYC = SRCROOT + "/pd-cyclone-0.9-5"
# fftease 3.0.1 (0.18.14): ericlyon/pd-fftease tag v3.0.1 (repo moved from
# the 2.5.2 Debian snapshot; "ericlyon/fftease" 404s - it is pd-fftease)
FFTS = SRCROOT + "/fftease-3.0.1"
# zexy 2.4.4 master (0.18.15): git.iem.at/pd/zexy HEAD - the anti-bot wall
# blocks BROWSERS, not git clones; proxied clone works
ZEXY = SRCROOT + "/zexy-current"
# iemlib 1.17 (0.18.16): git.iem.at/pd/iemlib HEAD, same proxied clone
IEMLIB = SRCROOT + "/iemlib-current"
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
    # cyclone 0.9-5 (0.18.13): porres/pd-cyclone tag 0.9-5 replaces the
    # 0.43.4 miXed snapshot. Layout: cyclone_objects/binaries/{control,audio}
    # object sources + the 12 non-alphanumeric objects registered inside
    # cyclone_lib.c's cyclone_setup ([cyclone] + !- !-~ !/ !/~ ==~ !=~ <~ <=~
    # >~ >=~ %~ +=~; cyclone_setup also prints the version banner at core
    # load - kept, ELSE's banner boots the same way). shared/{common,control,
    # signal} replaces the miXed/shared quartet (unstable/ folded into
    # control/). Vanilla-clashing names were renamed upstream (cyclone/line~
    # + Line~, cyclone/pow~ + Pow~, cyclone/clip + Clip, ...); the bare
    # spellings the 0.43.4 baseline resolved to cyclone (slibs shadow
    # vanilla) are restored as compat creators for the objects vanilla does
    # NOT already own with different semantics (line~, pow~, clip).
    ("cyclone", dict(
        src=[CYC + "/cyclone_objects/binaries/control",
             CYC + "/cyclone_objects/binaries/audio"],
        src_files=[CYC + "/cyclone_objects/binaries/cyclone_lib.c"],
        support=[CYC + "/shared/common", CYC + "/shared/control",
                 CYC + "/shared/signal"],
        inc=[CYC + "/shared", CYC + "/shared/common",
             CYC + "/shared/control", CYC + "/shared/signal",
             CYC + "/cyclone_objects/binaries"],
        # atodb/dbtoa no longer excluded: 0.9-5 made the control variants'
        # convert() static and the signal variants don't define convert at
        # all, so all four objects (atodb atodb~ dbtoa dbtoa~) ship now.
        # scope_dialog.c is a pure-Tcl sys_gui() text included twice INSIDE
        # scope.c's functions - never a standalone TU.
        excl=["scope_dialog.c"],
        # buffer~.pd: 0.9-5 ships exactly one abstraction (the compiled
        # buffer~ of the 0.43.4 snapshot became a pd file); bare buffer~
        # resolves to ELSE's (higher PRIORITY), this serves cyclone/buffer~
        abstractions=[CYC + "/cyclone_objects/abstractions"],
        strip_boot_pderror=True,
        patches={
            # 0.57.0test1 renamed sys_geteventno -> sched_geteventno
            # (m_pd.h has no compat define; cyclone 0.9-5 targets 0.56-5)
            "cycle.c": [("sys_geteventno()", "sched_geteventno()")],
            # bare-name compat creators. Anchors include the unique
            # cyclone/xxx class_new line (each file has TWO identical-setup
            # bodies - bare + capitalized variants) and are idempotent:
            # the inserted text breaks the contiguous old anchor.
            "line.c": [('    line_class = class_new(gensym("cyclone/line~"),  // avoid name clash with vanilla\n        (t_newmethod)line_new, (t_method)line_free, sizeof(t_line), 0, A_DEFFLOAT, 0);\n    class_domainsignalin(line_class, -1); // bug?',
                        '    line_class = class_new(gensym("cyclone/line~"),  // avoid name clash with vanilla\n'
                        '        (t_newmethod)line_new, (t_method)line_free, sizeof(t_line), 0, A_DEFFLOAT, 0);\n'
                        '    /* Android compat: the 0.43.4 baseline resolved bare [line~] to\n'
                        '     * cyclone (slibs shadow vanilla) - restore the bare creator so\n'
                        '     * Max-semantics ramp patches keep working on 0.9-5 */\n'
                        '    class_addcreator((t_newmethod)line_new, gensym("line~"), A_DEFFLOAT, 0);\n'
                        '    class_domainsignalin(line_class, -1); // bug?')],
            "pow.c": [('    pow_class = class_new(gensym("cyclone/pow~"),\n        (t_newmethod)pow_new, 0, sizeof(t_pow), CLASS_DEFAULT, A_DEFFLOAT, 0);\n    class_addmethod(pow_class, nullfn, gensym("signal"), 0);',
                       '    pow_class = class_new(gensym("cyclone/pow~"),\n'
                       '        (t_newmethod)pow_new, 0, sizeof(t_pow), CLASS_DEFAULT, A_DEFFLOAT, 0);\n'
                       '    /* Android compat: vanilla has no [pow~] through 0.57 and the\n'
                       '     * 0.43.4 baseline resolved the bare name to cyclone - restore it */\n'
                       '    class_addcreator((t_newmethod)pow_new, gensym("pow~"), A_DEFFLOAT, 0);\n'
                       '    class_addmethod(pow_class, nullfn, gensym("signal"), 0);')],
            "clip.c": [('    clip_class = class_new(gensym("cyclone/clip"), (t_newmethod)clip_new,\n        (t_method)clip_free, sizeof(t_clip), 0, A_GIMME, 0);\n    class_addfloat(clip_class, clip_float);',
                        '    clip_class = class_new(gensym("cyclone/clip"), (t_newmethod)clip_new,\n'
                        '        (t_method)clip_free, sizeof(t_clip), 0, A_GIMME, 0);\n'
                        '    /* Android compat: vanilla has no control [clip]; the 0.43.4\n'
                        '     * baseline resolved the bare name to cyclone - restore it */\n'
                        '    class_addcreator((t_newmethod)clip_new, gensym("clip"), A_GIMME, 0);\n'
                        '    class_addfloat(clip_class, clip_float);')],
        },
    )),
    ("zexy", dict(
        # zexy 2.4.4 master (0.18.15): git.iem.at/pd/zexy HEAD 5521523 -
        # the anti-bot wall blocks browsers, not git clones; proxied clone
        # works. src/ is flat with hexmangled filenames (0x260x260x7e.c =
        # "&&~.c"); abs/ ships separately (53 abstractions, unshipped here -
        # the 2.4.4-era baseline shipped src only).
        src=[ZEXY + "/src"], support=[], inc=[ZEXY + "/src"],
        # zexy object files register via the zexy_new macro (no literal
        # class_new), so the collector files them under "supports" - with
        # support_setups their setup functions still get called from
        # core_wrap.
        support_setups=True,
        excl=["zexy.c", "z_zexy.c"],
        # lpt.c arms ioperm/iopl under __linux__ (Android defines it too);
        # passing -DZ_WANT_LPT=0 selects upstream's OWN stub branch
        # (sys_outb/sys_inb dummies, class registers as a no-op) - bionic
        # has neither syscall and dlopen would fail (0.17.81 lesson)
        extra_flags=["-DZ_WANT_LPT=0"],
    )),
    ("iemlib", dict(
        # iemlib 1.17 (0.18.16): git.iem.at/pd/iemlib HEAD (proxied clone,
        # the anti-bot wall is browser-only). src/ = the merged tree;
        # iem_pbank_csv's const bug is FIXED upstream (the 'a'-'A' cast
        # patch is gone), iemlib{1,2}.c loaders no longer exist.
        src=[IEMLIB + "/src"], support=[],
        inc=[IEMLIB + "/src", IEMLIB + "/include"],
        # aux is #included by iem_pbank_csv.c (needs its t_iem_pbank_csv);
        # a standalone support compile of it is meaningless and fails
        excl=["iem_pbank_csv_aux.c"],
        # upstream abs/ ships 165 abstractions + 134 *-help.pd (the shipper
        # drops -help.pd); help/ carries the canonical 81 help patches
        abstractions=[IEMLIB + "/abs"],
        # use pd's real t_widgetbehavior (the hand-rolled fallback struct
        # types w_visfn as t_method and mismatches pd)
        patches={"protect_against_open.c": [
            ("#ifdef HAVE_G_CANVAS_H", "#if 1 /* Android rebuild */")]},
    )),
    # fftease 3.0.1 (0.18.14): ericlyon/pd-fftease tag v3.0.1 replaces the
    # Debian 2.5.2 snapshot (repo is pd-fftease; "ericlyon/fftease" 404s).
    # FLAT layout: every source at top level; fftease_setup.c = library
    # loader (master-dropped); fftease-helpfiles/, sound/, pd-lib-builder/,
    # collect.pl, *.h filtered by the .c collector. loopsea~ does NOT exist
    # in 3.0.1 - the migration notes' 7-object 3.0-only wishlist was
    # aspirational; 6 of 7 ship (cross~/enrich~/pileup~/pvwarpb~/schmear~/
    # residency_buffer~). bare cross~ goes to cyclone per PRIORITY (cyclone
    # outranks fftease; different object: crossfade vs FFT-domain) - the
    # fftease one stays reachable as fftease/cross~. pd-lib-builder/ carries
    # the build system's own test stubs (multilibA/subdir/_template_...) -
    # the collector walks subdirs, so exclude the tree or its classes get
    # compiled and registered into the blob (0.18.14 first-pass bug).
    ("fftease", dict(
        src=[FFTS], support=[], inc=[FFTS],
        excl_dirs=["pd-lib-builder"],
        # dentist~/disarrain~/disarray~ each define non-static rand_index /
        # reset_shuffle (same C symbols, different types) -> duplicate
        # symbols in the one core blob; make them file-local. 3.0.1 spacing
        # is "reset_shuffle (t_xxx *x)" (space before paren) for ALL THREE
        # - the 2.5.2 anchors' paren-hugging/inside-space variants no longer
        # match. Leading-newline anchors keep the patches idempotent.
        patches={
            "dentist~.c": [("\nvoid reset_shuffle (t_dentist *x)",
                            "\nstatic void reset_shuffle (t_dentist *x)"),
                           ("\nint rand_index(int max)",
                            "\nstatic int rand_index(int max)")],
            "disarrain~.c": [("\nvoid reset_shuffle (t_disarrain *x)",
                              "\nstatic void reset_shuffle (t_disarrain *x)"),
                             ("\nint rand_index(int max)",
                              "\nstatic int rand_index(int max)")],
            "disarray~.c": [("\nvoid reset_shuffle (t_disarray *x)",
                             "\nstatic void reset_shuffle (t_disarray *x)"),
                            ("\nint rand_index(int max)",
                             "\nstatic int rand_index(int max)")],
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
    # mrpeach current (0.18.17): the library moved to github pd-externals;
    # the umbrella repo pd-externals/mrpeach carries osc/midifile/binfile
    # as SUBMODULES - the object sources live in pd-externals/{osc,binfile,
    # midifile} (cloned to mrpeach-*). Real upstream drift vs the 0.43.4
    # snapshot: packOSC 1593->1433 lines, unpackOSC 693->631, midifile
    # 1603->2192, binfile 364->361. The umbrella's other families (net =
    # tcpsend/tcpreceive..., cmos, life2x, flist2tab, op~, slip) are NOT
    # shipped - the 0.51 baseline never carried them (parity).
    ("osc", dict(
        src=[SRCROOT + "/mrpeach-osc"], support=[],
        inc=[SRCROOT + "/mrpeach-osc"],
    )),
    ("midifile", dict(
        src=[SRCROOT + "/mrpeach-midifile"], support=[],
        inc=[SRCROOT + "/mrpeach-midifile"],
    )),
    ("binfile", dict(
        src=[SRCROOT + "/mrpeach-binfile"], support=[],
        inc=[SRCROOT + "/mrpeach-binfile"],
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
        # widgets. knob COMPILED HERE since 0.18.18 (elsex retirement: the
        # 52-class elsex core duplicated the full RC15 else core; its only
        # live contribution was knob - identical source, color pins moved
        # into this entry's patches).
        patches={"else.c": [
                     # [else] master: pdlua sublibrary not vendored -> drop
                     # the lua_setup call, keep the [else] info class
                     ("\nextern void lua_setup();",
                      "/* Android rebuild: pdlua sublibrary not vendored */\n"
                      "/* extern void lua_setup(); */"),
                     ("\n    lua_setup();\n}",
                      "\n    /* lua_setup(): pdlua not vendored (Android) */\n}")],
                 # knob.c reads THISGUI->i_* - pd 0.56-dev GUI theme fields
                 # the headless runtime never fills. They only feed the Tcl
                 # widget colors, which libpd drops anyway: pin the constants
                 # (same values the retired elsex build shipped).
                 "knob.c": [("THISGUI->i_foregroundcolor",
                             'gensym("#000000")'),
                            ("THISGUI->i_backgroundcolor",
                             'gensym("#ffffff")'),
                            ("THISGUI->i_gopcolor",
                             'gensym("#ffffff")'),
                            ("THISGUI->i_selectcolor",
                             'gensym("#0000ff")')]},
        excl=["keyboard.c", "pad.c", "button.c", "popmenu.c",
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
    # elsex RETIRED (0.18.18, user-approved decision): its 52 classes
    # duplicated the full RC15 else core byte-for-byte (same source, else
    # wins the bare names by PRIORITY anyway); knob moved into the else
    # build above. The subset source tree slibs/else-src/{Control,Audio,
    # Shared} is archived to archive/elsex-subset-tree/.
]
# compat glue: IS_DENORMAL is a macro only under pd>=0.54 headers; on 0.51
# every creb module using it compiled an implicit function call instead.
EXTRA_C = {
    "creb": "int IS_DENORMAL(double x){ return x > -1.2e-38 && x < 1.2e-38; }\n",
    # cyclone 0.9-5 (0.18.13) needs NO compat C: the purr-data gui_*/
    # glist_grabx/canvas_apply_setundo shims addressed the 0.43.4 snapshot
    # where purr-data had hardcoded those calls into the vanilla paths -
    # 0.9-5 gates every such call behind #ifdef PDL2ORK (never defined
    # here), and pdgui_vmess/pd_undo_set_objectstate/signal_setmultiout in
    # the vanilla branches are exported by the real 0.57 libpd.so.
}
# else midi-OUT shim (carried over from the retired elsex, 0.18.1
# adjudication still valid): the 0.57 libpd.so now EXPORTS pdgui_vmess /
# pdgui_stub_vnew / pdgui_stub_deleteforkey / pd_undo_set_objectstate /
# signal_setmultiout (nm -D verified against the mavenLocal AAR - the
# %TEMP%/pdaar extraction is a STALE libpd build that lacks them; audit
# symbol questions against C:/Temp/slibs-audit-<abi> or the .m2 AAR, never
# pdaar). Still missing from libpd (0.51.3 AND 0.57): sys_putmidibyte -
# else's midi-OUT classes feed it; the shim drops bytes, while the app's
# own noteout/ctlin bridge is a separate listener path.
EXTRA_C["else"] = (
    "#include <m_pd.h>\n"
    "void sys_putmidibyte(int portno, int byte) {\n"
    "    (void)portno; (void)byte;\n"
    "}\n"
)


RE_CLASS = re.compile(r'class_new\s*\(\s*gensym\s*\(\s*"([^"]+)"')
RE_ADDCREATOR = re.compile(
    r'class_addcreator\s*\(\s*\(\s*t_newmethod\s*\)\s*\w+\s*,\s*gensym\s*\(\s*"([^"]+)"')
# ZEXY_SETUP (zexy.h) expands to NOTHING, so every zexy setup line is
# 'ZEXY_SETUP void xxx_setup(' - without this prefix in the pattern
# core_wrap called only 2 of ~102 zexy setups (pack/unpack, the two
# plain-void files) and the library registered 2 classes on device
# while all 107 setups merely sat exported-but-uncalled in the blob
# (0.17.81-0.17.83).  cyclone 0.9-5 prefixes its 190 setups with
# CYCLONE_OBJ_API (empty on non-MSVC but literally in the source text) -
# the suffix-only pattern would have missed all of them the same way.
# Accept any mixture of extern/ZEXY_SETUP/CYCLONE_OBJ_API/CYCLONE_API.
# The pattern must match DEFINITIONS only: cyclone 0.9-5 carries the
# prototype 'void setup_single_lib();' inside #if CYCLONE_SINGLE_LIBRARY
# (undefined here) - the old paren-only pattern collected it as a defined
# setup, core_wrap emitted a call, and the blob shipped an unresolved
# reference that dlopen would reject (audit_blobs caught it). A definition
# has a body: params free of ;/{ then ')' then whitespace then '{'.
RE_SETUP = re.compile(
    r'^\s*(?:(?:extern|ZEXY_SETUP|CYCLONE_OBJ_API|CYCLONE_API)\s+)*void\s+([A-Za-z_]\w*_setup)\s*\([^;{]*\)\s*\{', re.M)
# cyclone 0.9-5 setup-time deprecation nags: pd_error(<x>_class, "...deprecated...")
# / "...please use [a] instead of [A]..." run ONCE PER CLASS REGISTRATION,
# i.e. at every core load on our autoplay boot - ~30 console lines of nag
# per start. Stripped in-memory (instances still get their own errors).
RE_BOOTNAG = re.compile(
    r'\n?\s*pd_error\s*\(\s*[A-Za-z_]\w*_class\s*,\s*"[^"]*(?:deprecated|please use)[^"]*"\s*\)\s*;')
# setup_-PREFIXED definitions (hexmangled class names, setup_rand0x2ef):
# same definition-only rule as RE_SETUP - prototypes must not match.
RE_SETUP_SYMBOL = re.compile(r'^\s*void\s+(setup_[A-Za-z0-9_]*)\s*\([^;{]*\)\s*\{', re.M)
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
    # strip_boot_pderror: cyclone 0.9-5 carries ~30 setup-time deprecation
    # nags (pd_error at class registration = every core load, i.e. every
    # boot). The flag strips them in-memory - the written-back patch sites
    # stay minimal and instance-level pd_error calls are untouched.
    strip_nag = cfg.get("strip_boot_pderror", False)
    objects, supports = [], []

    def srcdir(d):
        return d if os.path.isabs(d) else EX + "/" + d

    def nagstrip(f, b):
        # compile feeds the FILE to the compiler, so the strip must be
        # written back (idempotent: stripped lines no longer match)
        if not strip_nag:
            return
        text = open(f, encoding="utf-8", errors="replace").read()
        stripped = RE_BOOTNAG.sub("", text)
        if stripped != text:
            open(f, "w", newline="").write(stripped)
            print("  boot-nag stripped: %s" % b)

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
            nagstrip(f, b)
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
    # src_files: single explicit files OUTSIDE the walked src dirs (cyclone
    # 0.9-5's cyclone_lib.c sits at binaries/ level - adding the whole dir
    # would re-collect control/+audio/ and duplicate every object TU)
    for f in cfg.get("src_files", []):
        b = os.path.basename(f)
        text = open(f, encoding="utf-8", errors="replace").read()
        for old, new in patches.get(b, []):
            if old in text:
                text = text.replace(old, new)
                open(f, "w", newline="").write(text)
                print("  patched: %s" % b)
        nagstrip(f, b)
        if "class_new" in text or RE_ADDCREATOR.search(text):
            objects.append(f)
        else:
            print("  src_files entry has no classes, skipped: %s" % b)
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
        # accept BOTH setup spellings: classes whose names contain
        # non-identifier characters (rand.f/rand.i/rand.u/rand.seq - dots
        # hexmangle to 0x2e) get pd's setup_-PREFIXED setup symbol
        # (setup_rand0x2ef), NOT a *_setup suffix - filtering on the suffix
        # alone left those classes registered in core_classes.txt but their
        # setup functions never called (0.18.2/0.18.3: [rand.f] &
        # [else/tempo] inside every arp session failed to create). The
        # support_setups branch below already accepted both (0.17.81 zexy).
        # Then keep only setups the OBJECT actually exports (obj_defined_syms:
        # text scans cannot see #ifdef - zexy 2.4.4 sort.c's gated
        # sort_setup, 0.18.15).
        syms = obj_defined_syms(o)
        core_setups.append(list(dict.fromkeys(
            s2 for s2 in defined
            if (s2.endswith("_setup") or s2.startswith("setup_"))
            and s2 in syms)))
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
            # exported-but-uncalled (0.17.81-0.17.83 zexy regression).
            # obj_defined_syms filter: same #ifdef blindness guard as the
            # objects loop (zexy 2.4.4 sort.c's gated sort_setup, 0.18.15).
            defined = set(RE_SETUP.findall(text)) | set(
                RE_SETUP_SYMBOL.findall(text))
            syms = obj_defined_syms(o)
            core_setups.append(list(dict.fromkeys(
                s2 for s2 in defined
                if (s2.endswith("_setup") or s2.startswith("setup_"))
                and s2 in syms)))
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

def obj_defined_syms(o):
    """Global symbols DEFINED by a compiled object. Setup collection scans
    SOURCE TEXT and cannot see the preprocessor: zexy 2.4.4's sort.c keeps
    a `void sort_setup(void)` definition inside #ifdef ZEXY_LIBRARY
    (undefined here) and cyclone 0.9-5 had the prototype twin of this bug -
    text-matched "definitions" that the object never exports leave
    core_wrap calling a symbol the blob does not carry (audit_blobs:
    UNRESOLVED). Filter every collected setup through the object's nm."""
    code, log = run([NDK + "/llvm-nm.exe", "-g", "--defined-only", o])
    out = set()
    for line in log.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[1] in ("T", "t", "W", "w"):
            out.add(parts[2])
    return out


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
                adir = ad if os.path.isabs(ad) else EX + "/" + ad
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
