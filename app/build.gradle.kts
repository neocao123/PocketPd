plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketpd.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketpd.app"
        minSdk = 26
        targetSdk = 34
    versionCode = 315
    versionName = "0.18.11" // ---- 0.57 BRANCH round 3j: audio state is
    // mode-driven with icon truth (user bug report): patch entry loads
    // SILENT (loadPatch end: stopAudio + icon off; the early pre-parse
    // ensureAudioOn stays - boot-race slib-setup flush), RUN = dsp ON +
    // icon ON (updateAudioIcon added after ensureAudioOn), EDIT = dsp OFF
    // + icon OFF; the audio key inside RUN still toggles freely and never
    // influences the next mode transition (each transition forces state).
    // Supersedes the 0.17.x auto-DSP-on-entry request for this workflow.
    //
    // ---- 0.18.10 (0.57 history) round 3i: EDIT entry = dsp ---- 0.57 BRANCH round 3i: EDIT entry = dsp
    // off outright (user directive: "反正每次都重置所幸在edit状态就直接
    // dsp off吧") - toggleMode's EDIT branch now calls PdEngine.stopAudio +
    // icon update; RUN entry already re-arms via ensureAudioOn. Prior:
    // 0.18.9 deferred edit rebuilds (sessionDirty), 0.18.8 reverted the
    // plugdata tempo experiment + adjudicated the escape hypothesis.
    //
    // ---- 0.18.8 (0.57 history) round 3g: revert the ---- 0.57 BRANCH round 3h: EDIT mutations
    // defer the session rebuild (user directive: per-edit core restarts
    // popped and churned pd on every keystroke/drag). Edits mark
    // sessionDirty; the rebuild happens once at RUN entry (toggleMode's
    // reopenSessionForRun already ran unconditionally there and now
    // consumes the flag). The debounced sessionSync handler survives but
    // only marks dirty. ensureLiveSession's once-per-patch lazy open is
    // untouched. Prior rounds this session: 0.18.5 probes, 0.18.6 raw-
    // atoms args payload, 0.18.7 plugdata tempo experiment (reverted),
    // 0.18.8 revert + escape-hypothesis adjudication.
    //
    // ---- 0.18.8 (0.57 history) round 3g: revert the ---- 0.57 BRANCH round 3g: revert the
    // plugdata tempo.pd experiment (user verdict: library version
    // unsuitable) - content equals 0.18.6. Slibs.VERSION 139 -> 140
    // (re-extract restores the RC15 tempo.pd). Escape-hypothesis
    // adjudicated NEGATIVE with source receipts: app round-trip preserves
    // \, (splitStatements backslash guard + tokenizer keeps the escape +
    // toText re-writes it; device session dump shows the expr line intact)
    // and pd 0.57 m_binbuf.c:711-717 converts A_COMMA to a "," symbol
    // argument (no message split) when the target is pd_objectmaker.
    //
    // ---- 0.18.7 (0.57 history) round 3f: plugdata tempo experiment ---- 0.57 BRANCH round 3f: else/tempo.pd
    // swapped to the plugdata-bundled ELSE revision (user-provided file
    // from C:/Users/Public/Documents/plugdata/Abstractions/else/) - newer
    // upstream revision: else/lb wrapper (instead of loadbanger),
    // -hz/-mul/-seed flags, select 1 0 2 unit dispatch, else/-prefixed
    // inner deps (all provided: lb/args/swap2/rand.f/changed compiled or
    // abstraction). RC15 tempo.pd replaced in both ABI asset dirs;
    // tempo-help.pd KEPT (its -div instances degrade to default div - the
    // plugdata revision dropped -div; -on/-ms/bpm demos unaffected).
    // Slibs.VERSION 138 -> 139.
    //
    // ---- 0.18.6 (0.57 history) round 3e: subpatch args ---- 0.57 BRANCH round 3e: subpatch args
    // conversion payload fix (user-diagnosed: tempo still dead after start).
    // substituteLine's depth-2 [args] conversion emitted `initmess list
    // <atoms>` - but else's [pd args] blocks feed [break -]/[route] which
    // dispatch on the RAW atoms' selectors (-on/-ms/-div); the "list" first
    // atom rerouted dispatch into the list branch, $0-mode stayed unset and
    // every inlined else/tempo ran at a garbage period (tempo-help +
    // everything containing else/tempo, incl. arp). Payload is now the raw
    // creation atoms (`initmess 60 10 -on`); empty args keep the
    // `initmess list` bang-equivalent. Device evidence (0.18.4 console):
    // "bad arguments for message 'tempo' to object 'metro'" + 500ms
    // "select: no method for 'bang'" + "$1/$2 out of range" (the latter
    // from display.pd's [send $1] loading natively without a tag in
    // tempo-help - cosmetic, the widget gets no feed there).
    //
    // ---- 0.18.5 (0.57 history) round 3d: DIAGNOSTICS for the ---- 0.57 BRANCH round 3d: DIAGNOSTICS for the
    // else/-prefixed inline skip (user report: tempo-help still broken on
    // 0.18.4 - device console shows "bad arguments for message 'tempo' to
    // object 'metro'" + "select: no method for 'bang'" every tick, meaning
    // tempo.pd loaded NATIVELY (its [pd args] subpatch then hits the
    // 0.15.29 subpatch-loadbang gap and its config never applies) instead
    // of being inlined with args->initmess conversion. Two probes: (1)
    // Abstractions.parseInfo logs the file+exception instead of silently
    // nulling resolve(); (2) the inline gate logs every lib-prefixed box
    // it leaves native, with resolve/pdClass/gop/budget factors. Also
    // answers why swap2/makenote2 stayed native in the 0.18.3/0.18.4
    // session dumps.
    //
    // ---- 0.18.4 (0.57 history) round 3c: hexmangled setup ---- 0.57 BRANCH round 3c: hexmangled setup
    // names now called. USER DIAGNOSIS (they pinned it before I could):
    // rand.f/rand.i/rand.u fail to create, inside else/tempo (arp chain).
    // Root cause: pd hexmangles non-identifier class names - rand.f's
    // registration function is setup_rand0x2ef ("." -> 0x2e), which does
    // NOT end in "_setup". build_slibs.py's OBJECT loop collected setup
    // calls with an endswith("_setup") filter ONLY, so every setup_-
    // PREFIXED spelling was silently dropped from the core_wrap call list:
    // the classes were listed in core_classes.txt (and inlined fine by the
    // session builder) but their registration functions never ran ->
    // couldn't create at pd level. The support_setups branch (zexy,
    // 0.17.81) already accepted both spellings; the object loop now does
    // too. else wrap went from ~150 to 289 setups called. Everything else
    // in arp already worked (0.18.3's locate fix handled count/swap2/
    // makenote2 via inline; else/tempo's rand.f was the last blocker).
    // Slibs.VERSION 137 -> 138.
    //
    // ---- 0.18.3 (0.57 history) round 3b: else/ prefixed
    // abstraction refs resolve again. User smoke of 0.18.2: arp still
    // couldn't-create. Root cause (code-level): Abstractions.locate mapped
    // `lib/name` to <dir>/lib/name.pd, but for the else lib the search dir
    // IS <root>/else - so else/count.pd lives at <root>/else/count.pd and
    // the old mapping looked in <root>/else/else/. The 0.17.153 prefix-strip
    // used to paper over this by rewriting else/count -> bare count (resolved
    // as a vintage COMPILED class), but the RC15 core registers those as
    // abstractions-only now (count/swap2/makenote2 left core_classes.txt),
    // so the strip correctly refused and the box died. Fix: locate() also
    // tries the parent dir for slash-prefixed names; the 0.17.149 fixpoint
    // inline loop then expands else/count -> count.pd (and recursively
    // else/delete, else/replace, else/pick) inside the session, where
    // args->initmess conversion and namespace prefixing already apply.
    //
    // ---- 0.18.2 (0.57 history) round 3 (W2.2): vintage
    // else_core.so (sources lost, dead on 0.57 since 0.18.1) REPLACED by
    // the full ELSE RC15 build. Vendored porres/pd-else master snapshot
    // @082af31e (2026-11-21, RC-15) under slibs/else-src/full/ (subset tree
    // kept for elsex); new ("else",...) build_slibs entry compiles ~392
    // objects into else_core.so incl. plaits~/circuit~/sfont~(+SF3 via
    // vendored FluidLite/libogg/libvorbis)/pdlink(+opus)/beat~(+aubio).
    // Build-system additions: excl_dirs (vendor-tree exclusion by path
    // substring; Windows backslash paths!), excl_main (skip files defining
    // main() - CLI demos/tests vendored upstream; dead-code stripper for
    // #if 0 and _V_SELFTEST guards), extra_flags (opus standalone switches
    // -DOPUS_BUILD -DVAR_ARRAYS), .cpp accepted, include-wrapper skip now
    // size-guarded (fluid_defsfont.c), slibs compile target API 21->26
    // (bionic getifaddrs for link.cpp; app minSdk is 26), libc++
    // static-linked into the else blob (C++ TUs), sys_putmidibyte shim
    // shared with elsex, else.c master patched to drop the non-vendored
    // pdlua lua_setup call. Exclusions (justified, VENDORED.txt):
    // keyboard/pad/button/popmenu/knob (app-native widgets / elsex
    // provides), play.file~/sfload/sfinfo/streamout~/streamin~ (ffmpeg/
    // sfizz/vorbis external deps), opus arch SIMD + demo/test mains,
    // silk/fixed (fixed-point only). Coverage: vintage 217 else classes ->
    // 217/217 resolvable (216 compiled by the new core incl. the 27
    // addcreator-alias names, blip~ via its vanilla abstraction); manifest
    // 1436 -> 2039; audit_blobs ZERO unresolved both ABIs; user smoke of
    // 0.18.1 confirmed blip~/vsaw~ dead + superosc~ dependency chain -
    // vsaw~ now compiled, superosc~.pd's vsaw~ reference resolves.
    // Slibs.VERSION 136 -> 137 (re-extract).
    //
    // ---- 0.18.1 (0.57 history) round 2 (W2 slibs matrix):
    // ALL slib cores rebuilt against the pd 0.57.0test1 headers (the exact
    // tree the runtime libpd.so ships from - pure-data@43a5b8922a35),
    // per-object .so layout unchanged, Slibs.VERSION 135->136 forces
    // re-extract. build_slibs.py: PDSRC now points at the 0.57 tree; the
    // elsex pd55-header inc_first special case removed; the 0.17.137 mc
    // shims dropped where 0.57 libpd.so now natively exports the symbols
    // (pdgui_vmess/pdgui_stub_vnew/pdgui_stub_deleteforkey/
    // pd_undo_set_objectstate/signal_setmultiout - keeping them would
    // shadow the real mc/GUI implementations and pin elsex to degraded
    // mono); sys_putmidibyte shim KEPT (still un-exported in 0.57),
    // cyclone purr-data gui_*/glist_grabx/canvas_apply_setundo shims KEPT
    // (still un-exported). New drift patch: cyclone sic.c
    // (COSTABSIZE+cos_table left m_pd.h after 0.51; runtime still
    // allocates+exports the 512-pt OLDTABSIZE compat table lazily - declared
    // back, NULL-guarded by sic.c itself). Result: 28/28 lib-ABI rows OK,
    // zero compile failures; audit_blobs clean vs the 0.57 APK baseline
    // EXCEPT the vintage else_core.so (sources lost, PD_VERSIONS section 4.3):
    // 4 symbols no longer exported in 0.57 (glist_findrtext/rtext_height/
    // rtext_width/sys_get_audio_params) -> its dlopen fails on 0.57 AND its
    // t_signal offsets are stale - unfixable without sources, replacement =
    // full ELSE RC15 rebuild (next round, MIGRATION W2.2). KNOWN interim:
    // vintage else compiled classes (blip~/gran~/presets/output~/...) are
    // unavailable this round; all other libraries (incl. elsex with native
    // mc API) are live.
    //
    // ---- 0.18.0 (0.57 history) round 1: ENGINE SWAP.
    // Replaced libpd 1.2.1-rc6 (pd 0.51.3, Maven, last formal release 2021)
    // with a self-built AAR from pd-for-android master pinned at
    // libpd@e3cd52fd7517 -> pure-data@43a5b8922a35 = pd 0.57.0test1
    // (mavenLocal coordinate io.github.libpd.android:pd-core:1.4.4-SNAPSHOT).
    // Local build deviations, all documented (MIGRATION_057.md W0.1/W0.2):
    // minSdk 28->26 (user decision: keep Android 8.0+; bionic glob exists
    // only since API 28 and pd 0.57 x_file.c needs glob -> shipped a
    // glob_compat shim compiled into the pd module for API<28 builds), NDK
    // 29->27.2 (installed toolchain), AGP-9 javadoc sdkDirectory workaround.
    // W0.3 static checks on the new libpd.so: banner Pd-0.57.0, clone
    // "usage: clone [flags]", multichannel strings present, ALL 10 key
    // exports present (sys_noloadbang/sched_tick/clock_getlogicaltime/
    // signal_setmultiout/pdgui_vmess/sys_gui/sys_vgui/...). Runtime layer
    // is now NATIVE oboe audio (PdBaseLoader loads pdnativeoboe;
    // implementsAudio()=true -> PdAudio drives start/pause natively and
    // pd messages flow via the app's own 5ms poller, which predates this
    // switch). KNOWN INTERIM LIMITS accepted in this round: slibs are still
    // the 0.51-4-header builds (phase 2 = 0.18.1 rebuilds them against 0.57;
    // mc-era signal classes may misbehave until then - vanilla is the smoke
    // target). 0.51 line frozen at tag freeze-0.51-base (=0.17.161, vc303).
    //
    // ---- 0.17.161 (0.51 history) [/] and [/~] drew ONE inlet (user report:
    // "奇怪的/ obj 只有一个 inlet"). PdObjectMeta.resolveObject's lib-prefix
    // strip ("zexy/lister" -> "lister") ran substringAfter('/') on ANY name
    // containing a slash - but vanilla's division objects ARE named "/" and
    // "/~", so the strip produced "" / "~", both defs lookups missed, and
    // the box fell through to the 1-inlet unknown-class fallback (pd itself
    // created them fine - drawing-only bug). Fix: strip only when a real
    // prefix exists (indexOf('/') > 0).
    //
    // ---- 0.17.160 (history) FULL REVERT to the 0.17.157 code content per
    // user order ("回到改timer之前") after the 0.17.158/159 clock-watchdog
    // experiment broke pipe/delay pacing and more (user: "大量东西都被你改
    // 失效了，你现在的行为太危险了"). REMOVED entirely: libpdclock.so (4
    // ABIs), PdClock.kt, all PdEngine hooks (init/startAudio/stopAudio,
    // incl. the stopAudio computeAudio(false) change), slibs/pdclock source.
    // Artifacts archived under archive/pdclock-watchdog-017158-159/ with a
    // MANIFEST; the broken tree is preserved in git as tag
    // snapshot-0.17.159-broken. versionCode keeps incrementing per the
    // numbering discipline; versionName advances (0.17.160) even though the
    // CONTENT equals 0.17.157 - the CHANGELOG entry records that mapping.
    // From this round on: git commit per validated round + 高危区改动先审
    // (see AGENTS.md 版本机制).
    //
    // ---- 0.17.159 (history) clock watchdog REWRITTEN - 0.17.158's version
    // was a regression (user report: "pipe和delay被你改坏了"): its self-gate
    // ("clock did not advance since the previous visit -> tick") cannot
    // distinguish "audio thread dead" from "audio thread between blocks",
    // and with the visit cadence ~equal to the audio block duration a slight
    // phase offset made it tick BETWEEN the render thread's blocks while
    // audio ran - logical time advanced at ~1.5-2x real rate and every
    // delay/pipe/metro fired early. Rewrite: (1) EXPLICIT gating only - the
    // watchdog runs solely while audio is stopped (PdEngine.init arms it;
    // stopAudio re-arms; startAudio retires it AFTER a successful start so a
    // failed audio init keeps the clock alive); (2) advance by REAL elapsed
    // wall time (desktop pd's polling-scheduler behavior) instead of a fixed
    // cadence - lag = (real elapsed) - (logical elapsed) vs an anchor,
    // catch-up bursts capped at 256 blocks, and a clock-AHEAD-of-wall anchor
    // drop makes double-advance impossible even if both gates fail.
    //
    // ---- 0.17.158 (history) logical-clock watchdog introduced (libpdclock.so,
    // 4 ABIs, in-tree source slibs/pdclock/pdclock.c): pd's control objects
    // ([timer]/[delay]/[metro]) measure LOGICAL time, which on libpd advances
    // ONLY inside the audio render callback - desktop pd keeps it alive
    // without audio (m_sched.c m_callbackscheduler watchdog), libpd never runs
    // m_mainloop, so with the dsp key off, a failed audio init or during the
    // lazy boot window the clock froze and [timer] reported 0 forever (user
    // report: "timer触发了但是不计时间"). Its clock-advance self-gate turned out
    // to rush pipe/delay - rewritten in 0.17.159. PdEngine.stopAudio also turns
    // pd's dsp switch off now (the graph must not be re-run at block rate by
    // the watchdog; startAudio re-arms it). Superosc~ wiring unchanged - that
    // fix stays as 0.17.157 shipped it.
    //
    // ---- 0.17.157 (history) superosc~ eleventh stage: the freq-sum connects
    // targeted the PAN2~s (objects 20-26) instead of the VSAW~s (6-12) - the
    // oscillators' freq inlets were completely unconnected (user-spotted:
    // "第一个vsaw~第一个io都没有连"), the freq control signal polluted the
    // pans' audio inlets, hence silence. Retargeted to 6-12 and mechanically
    // verified: all 7 vsaw~ inlet-0 feeds = (42,0), zero freq->pan leftovers.
    // To be crystal clear on provenance: this superosc~.pd is OUR OWN mono
    // replacement (0.17.152+), NOT porres's upstream file - the upstream
    // multichannel architecture cannot run on the pd 0.51.3 runtime.
    // Slibs.VERSION 134->135.
    //
    // ---- 0.17.156 (history) superosc~ tenth stage: the 0.17.155 rewrite put the
    // #X text comment back at the TOP of the file - THE SAME off-by-one I
    // fixed in 0.17.153 (pd's connect indices count text boxes) - every
    // internal connect rejected again, hence "内部连线各种错误" + silence.
    // Text moved to file end AGAIN, and this time shipped with a mechanical
    // guard: slibs/check_pd_connects.py models pd's index space (texts count,
    // canvas blocks = one index) and fails on any connect touching a text
    // box; superosc~/f2s~/insert/group and the help all verify clean.
    // Slibs.VERSION 133->134.
    //
    // ---- 0.17.155 (history) superosc~ ninth stage: SILENCE root-caused from the
    // session dump - the user's superosc~ box carries its frequency ONLY in
    // the creation args (`superosc~ 110 0 25`) and NOTHING is wired into the
    // freq inlet (session trace: zero cords into the guts' inlet 0; their
    // loadbang/msg60/mtof chain is itself unwired), and the 0.17.152 guts
    // ignored args entirely -> 0 Hz -> silence. Fix: args 1/3 now drive
    // [sig~ $1]/[sig~ $3] constants SUMMED with the freq/detune inlet
    // signals - the box sounds at its arg frequency with nothing wired, and
    // a wired signal adds on top (documented on the box text: set the arg to
    // 0 when feeding the inlet). Slibs.VERSION 132->133.
    //
    // ---- 0.17.154 (history) superosc~ eighth stage: the 0.17.153 .pd fixes never
    // reached the device - I bumped the app version but NOT Slibs.VERSION
    // (still 131), so the extracted slibs kept the 0.17.152 files with the
    // text-at-start connect shift (device log 01:47: the exact old failure
    // signature "text->outlet"/"text->vsaw~" again). Slibs.VERSION 131->132
    // forces re-extraction; this is precisely the failure mode AGENTS.md's
    // slibs-bump rule exists for. The remaining vca2~.pd native-load noise
    // comes from the user's own patch-dir module copies (upstream files,
    // patch dir wins resolution by design) - their content is theirs.
    //
    // ---- 0.17.153 (history) superosc~ seventh stage: (1) MY OWN 0.17.152 files
    // had the leading #X text comment BEFORE the objects - pd's connect
    // indices COUNT text boxes, so every written connect was off by one and
    // pd rejected them all ("text->outlet"/"text->vsaw~" connection failed,
    // device log 01:41). Text comments moved to file end, lint clean.
    // (2) else/-prefixed compiled classes: upstream files say `else/knob`
    // while our cores register bare `knob` - the session builder now strips
    // the prefix when neither else/<name> nor <name> resolves as an
    // abstraction and <name> is in the extracted core_classes lists (knob
    // creates; visual operation depends on canvas widget support).
    // The help demo's `rampnoise~ -ch 2 1` / `tempo~ -mul 4 60` still fail:
    // those classes exist in our 0.51-era else core but without the
    // upstream 1.0-9 flag syntax - left untouched (upgrading them means
    // recompiling slibs, a separate decision).
    //
    // ---- 0.17.152 (history) superosc~ sixth stage - RUNTIME ARCHITECTURE MISMATCH
    // resolved by replacing the shipped superosc~/f2s~ abstractions with mono
    // editions: the 0.17.151 log showed every creation error GONE but the
    // multichannel layer failing ([mix~]: channel sizes mismatch, insert 'all'
    // syntax, routeall message connects). Root truth: the runtime core is
    // pd 0.51.3 (no multichannel signals, no clone -do/-x), while upstream
    // superosc~ (ELSE 1.0-9, 2025) is built ENTIRELY on mc (clone -do +
    // runtime resize + nchs~ + mc mix~ + f2s~) - it cannot work here as
    // written. superosc~.pd now ships the mono 7-voice supersaw (the proven
    // 0.17.144 architecture: per-voice bl.vsaw~ with width inlet 1 and
    // detune-cents inlet 2 via [*~ coef] spread, fixed equal-power pan2~,
    // 1/7 gain; 4 inlets/2 outlets preserved, args accepted and unused).
    // f2s~.pd ships a mono float->vline~ ramp (no clone/resize; args ignored).
    // Both ABIs, lint clean, Slibs.VERSION 130->131 forces re-extract.
    // Known limitations vs upstream: no wave/square/sine modes, no sync, no
    // multichannel in/out, insert.pd 'all' syntax still unsupported.
    //
    // ---- 0.17.151 (history) superosc~ fifth stage: the f2s~ clone chain finally
    // inlined but the log showed "file 'else/float2sig.unit,' not found" - the
    // TRAILING COMMA: f2s~.pd writes its fold marker glued to the file name
    // (`clone -do -x 1 else/float2sig.unit, f 52;`); pd's tokenizer splits
    // mid-token commas, ours splits on whitespace only, so the clone's file
    // name kept the comma and resolved to nothing. parseCloneArgs now strips
    // the glue from the count/file tokens. The remaining "$1]" console lines
    // are fire-time dollar substitution in porres's expr/msg templates
    // (same on desktop pd) - noise, not failures.
    //
    // ---- 0.17.150 (history) superosc~ fourth stage: the fixpoint loop (0.17.149)
    // exposed the NEXT layers, both fixed here: (1) inlineBudget 256 was too
    // small - the user's patch tree (their own module library + ELSE copies in
    // the patch dir) exhausted it BEFORE the recursion reached else/f2s~'s
    // inner `clone -do -x 1 else/float2sig.unit`, so that clone stayed native
    // and errored (device log 01:16). Budget now 4096 with an exhaustion log.
    // (2) the shipped superosc.unit.pd carries upstream-1.0-9-only
    // `initmess verbose 0, midi 0` boxes: our vendored else core (0.51-era,
    // initmess has no verbose/midi flags) forwarded those selectors into the
    // unit's osc system and the old sine~ has no such methods ("sine~: no
    // method for 'verbose'/'midi'"). The message's meaning is "midi mode = 0
    // (hz)" which IS the default, so both boxes are swapped 1:1 to inert
    // `r \$0-initmuteN` receivers (connects untouched, lint clean,
    // Slibs.VERSION 129->130 forces re-extract). sine~ was never "broken
    // before" - the unit never loaded at all while the clone was dead.
    //
    // ---- 0.17.149 (history) superosc~ third stage: the 0.17.148 clone fix NEVER
    // RAN - the inline loop iterated session.objects().toList(), a snapshot
    // taken before the loop; the [clone -d ...] box is created BY the
    // superosc~ inlining, so the loop never saw it (device log 2026-11-22
    // 01:06, fresh process: the clone box still in the session dump at row
    // 0038, no "kept native" log, clone usage error again). Fix: the inline
    // pass now runs to a FIXPOINT - repeat over newly added objects until
    // none remain, guarded by inlineBudget=256 against self-referential
    // abstractions (pd's own guard is pd_setloadingabstraction). Nested
    // inlines keep per-instance uids, so $0/delay/send namespaces stay
    // separated. Also banner-proven this round: the runtime core is pd
    // 0.51.3 (startup banner + clone lacking -d), not the 0.55系 the project
    // believed since 0.17.70 - the pd55-ref sources remain indicative but
    // clone/version-sensitive checks must use 0.51 semantics.
    //
    // ---- 0.17.148 (history) superosc~ second stage FIXED - the [clone]: the
    // crash was gone in 0.17.147 but the runtime's clone (libpd 1.2.1-rc6's
    // pd predates clone's -d/-x/-di/-do flag parsing; llvm-strings on the
    // shipped arm64 libpd.so shows only the -s usage string) rejected
    // `clone -d else/superosc.unit 1` with its usage error -> "couldn't
    // create" -> no voices, superosc~ dead (device log 2026-11-22 00:53).
    // Fix: single-voice clones with a resolvable abstraction file inline
    // directly (parseCloneArgs: -s/-x/-d/-di/-do, <n> <name> either order);
    // with ONE voice -d distribution is the identity, so inlining is exactly
    // equivalent - the unit file's guts expand through the existing
    // inlineAbstraction machinery with $1 = start voice, fresh $0, port
    // splicing intact. Multi-voice clones and unknown syntax stay native.
    // The "$1: argument number out of range" console lines are ELSE's own
    // msg templates firing at load without args (same on desktop) - noise,
    // not failures.
    //
    // ---- 0.17.147 (history) superosc~ ROOT-CAUSED AND FIXED: injectBlockRelays'
    // nested-block scan ended a [pd] block at its INNER subpatch's #X restore
    // (no depth counting), so superosc~'s depth-4 nesting leaked each block's
    // tail to the parent level and injected TWO stray "#X restore" lines per
    // level - the emitted session had 13 restores for 12 canvas opens; the
    // stray restores popped the root canvas (pd canvas_restore routes to the
    // current #X binding), args-init was then created with owner=NULL, and the
    // first "#X obj" inside it (initmess list) hit canvas_objtext's
    // unconditional canvas_getargs -> canvas_getenv(NULL) -> bug() -> SIGSEGV
    // 0xe0 (device-proven: GM800 2026-11-22 00:39:35, session dump tagged
    // PocketPd.Session landed 54ms before the crash). arp only has depth-1
    // blocks, which is why it was unaffected. Fix: shared depth-aware
    // subpatchBlockRestore() replaces the first-restore scan in ALL of
    // injectBlockRelays / rewriteBlockEuclids / levelMsgOffset /
    // prefixBlockDelayNames / walkSubpatchGuis (sim_inline.py mirrors it);
    // verified line-for-line lossless on the real depth-4 route block
    // (slibs/verify_scan_fix.py). Ride-along correctness: (a) pmsg ordinals
    // now assigned in document order (pre-order), matching the doc-side tap
    // path exactly - previously tapping a root msg positioned before a
    // msg-bearing subpatch fired the subpatch's msg instead; (b)
    // emitSubpatchBlock only rewrites the block's OWN restore coords - nested
    // restores were clobbered to the outer box's coords, stacking every
    // nested subpatch box at one spot.
    //
    // ---- 0.17.146 (history) superosc~ hunt round: (1) versionName duplicate-assignment
    // bug from 0.17.145's bump removed - Kotlin last-assignment-wins made every
    // debug APK report 0.17.144 while carrying 0.17.145 code (user spotted it on
    // phone+GM800); from now on a bump REPLACES the old versionName line, never
    // appends a second one. (2) superosc~ SEGV hunt continued per AGENTS.md
    // (emulator now BANNED - user closed the one I started): pd 0.55 sources
    // fetched into slibs/pd55-ref and every vanilla mechanism audited
    // (canvas_objtext's unconditional canvas_getargs, abstraction loader
    // setargs/env, clone_makeone, pd_pushsym/pd_popsym guards, binbuf_text
    // tokenizer, canvas_f) - all safe; sim_inline.py rebuilt against current
    // Kotlin (old file was heredoc-truncated), generated session verified
    // #N canvas/#X restore balanced (12/11 incl. root). The fault is inside
    // canvas_getenv's owner walk on the session the APP emits, so the real
    // text is required: debug builds now dump the exact session text to the
    // "PocketPd.Session" logcat tag right before PdBase.openPatch (diagnostic
    // switch per AGENTS.md, no connected-test infra). (3) USER DIRECTIVE: the
    // bundled example patches moved from filesDir to <pdRoot>/examples
    // (/sdcard/pocketPD once the grant lands) - shared storage, survive
    // uninstall, visible in any file manager; extraction is per-file
    // copy-if-missing (never deletes/overwrites user edits), legacy internal
    // tree migrates once.
    // ---- 0.17.145 (history) storage migration + superosc~ crash fix + popmenu widget:
    // (1) USER DIRECTIVE after the 2026-11-21 data-loss incident: every pd
    // file now lives in /sdcard/pocketPD (WorkStore) - patches, imported
    // externals, seeded samples - NEVER in filesDir; the app-external dir is
    // only the pre-grant fallback. MANAGE_EXTERNAL_STORAGE (+ legacy WRITE
    // for API<=29 + requestLegacyExternalStorage), a launch dialog walks the
    // user to All-Files-Access, legacy locations migrate on first launch.
    // Derived caches (slibs/ymbanks/help/examples extraction, session
    // files) stay app-side on purpose: rebuilt from assets, no user work.
    // (2) superosc~/arp crash root-caused via the (now-removed) device
    // probe: the REAL inlined session opened fine natively but pd SEGVed at
    // canvas_getargs - else's [args] walks canvas_getenv up to the session
    // root whose gl_env is NULL for libpd-opened files (bug() falls through
    // into a NULL deref, fault addr 0xe0 = offsetof(t_canvas, gl_env), both
    // devices). Fix: the inliner resolves every [args] to initmess boxes
    // carrying the same load-time output (empty->bang, numeric list,
    // [args <sep>] flag breaking as parallel initmess groups with duplicated
    // outlet cords; subpatch-line args swap 1:1, no connect renumbering).
    // No canvas_getargs caller remains in any session.
    // (3) substituteLine's msg-$N literal check was always-false ("#X msg"
    // split into "#X"+"msg"), binding every inlined msg box's $N to
    // creation args (superosc~'s `resize $1` fired as `resize <freq>`);
    // now reads the first two tokens and keeps message $N fire-time.
    // (4) popmenu implemented as an app-only dropdown widget (upstream is a
    // Tcl GUI object, never compiled here; session drops the box, cords
    // ride relays like button): full upstream 21-token property block,
    // complete Properties dialog (width/height/fontsize/output
    // Index-Item-Both/initial selection/loadbang/label/items one-per-line),
    // duplicate regenerates relay names, palette entry "pmnu".
    // (5) androidTest probe infrastructure removed entirely (AGENTS.md:
    // connected tests are banned on user devices after the uninstall
    // incident); androidTest deps + runner + assets + probe deleted.
    // (6) BUMP BUG caught 2026-11-22: the 0.17.145 bump left this round's
    // own "versionName = 0.17.144" assignment below in place; Kotlin's
    // last-assignment-wins made every debug APK report 0.17.144 while
    // actually carrying 0.17.145 code (user spotted it on phone+GM800).
    // Only ONE versionName assignment may exist per defaultConfig from
    // now on - each bump REPLACES the old line, never appends.
    //
    // ---- 0.17.144 round (kept for history) ---- arp/superosc~ report round: the full session chain was verified
    // clean end-to-end for both (slibs/dep_check.py closure over vanilla-0.55
    // + all compiled cores + shipped .pd files: ZERO missing classes, no
    // PRIORITY shadowing; sim_inline.py (parse -> inlineAbstraction ->
    // toText port) emits lint_pd-clean session text for arp and superosc~;
    // llvm-nm confirms router/voices/loadbanger/args/loop/initmess ARE
    // exported by else_core.so). So the broken-box report points at device
    // slibs state, and Slibs.VERSION 128 -> 129 forces a full re-extract.
    // Real fixes shipped: (1) superosc~-help/crush~-help/level~-help/
    // vca~-help referenced MERDA GUI modules (else/superosc.m~, crush.m~,
    // level.m~, vca.m~, vca2.m~) that are not shipped (they need the
    // knob/button/popmenu host-GUI bridge that libpd does not export) and
    // errored on open - swapped for the core objects they wrap (else/
    // superosc~ / crush~ / level~ / vca~ / vca2~; the references all sit in
    // cord-free "see also" subpatches, pure renames); (2) supersaw~ ADDED -
    // upstream ELSE has no such release object (GitHub master verified,
    // porres's Patreon prototype became superosc~): our own abstraction -
    // 7x compiled [vsaw~] voices, per-voice detune ratios expr->sig~->[*~]
    // (signal domain), equal-power pan2~ spread, 1/7 gain compensation;
    // args via [f $1]/[f $2] -> [select 0] -> msg fallback so bare use
    // lands on 220/25 and both the inlined path and native pd honor args
    // (unescaped $1/$2 = A_DOLLSYM; no $0 state, instances independent);
    // PdObjectMeta row Def(2,2,{0},{0,1}) + help/else/supersaw~-help.pd +
    // OBJECT_PORTS.md note (incl. the beta19-21 vintage-claim correction
    // for else_core's newer classes).
    // 0.17.143 else/elsex audit round: the else agent pinned the
    // shipped else_core vintage to ~1.0-beta19..21 (help-patch evidence),
    // verified every constructor against beta21 sources + bundled help
    // markers, and flagged: (1) 52 signal-mark rewrites - else's
    // oscillator/filter param inlets are SIGNAL inlets in this vintage, so
    // nearly every Def row for 2+-inlet ~ classes missed sigin entries
    // (add~/eq~/pluck~/pulse~/impulse2~/vsaw~/fbsine~/comb.filt~/
    // allpass.rev~/decay~/ffdelay~/lfnoise~/quantizer~/glide2~/pan4~/
    // svfilter-family...), plus dcblock~ lost its sigout mark; (2) 16 rows
    // added (balance~/cmul~/comb.rev~/flipflop~/imp~/imp2~/rms~ FLOAT-out/
    // rotate~/s2f~ FLOAT-out/susloop~/wt~/xfade~/message 2in/pgm.out 2in/
    // pic 1in-0out/note graphical-no-outlets/openfile/click+properties
    // NOINLET/loop per shipped-help 1in-2out); (3) five variadic rules
    // corrected against this vintage (select~ inlet 0 IS signal; xgate~ ONE
    // sig inlet; xselect~ n sig inlets; xselect2~ ONE sig outlet; unmerge
    // clamps to 3 bare outlets) and 14 new rules for bare defaults the
    // heuristic missed (autofade~/hot/match~/merge/rec~/routeall/t2/
    // trigger2/spread~/xgate2~/chance/format/limit/route2+spread).
    // touch.in/touch.out stay heuristic (source vs help conflict); master's
    // counts are NOT used for the ~25 classes upstream redesigned since.
    // Tooling: slibs/apply_else_audit.py (balanced-paren row rewriter - its
    // first run left one extra ")" on all 52 rows; repaired same round). // else/elsex audit round (269 classes): the else agent pinned the
    // tail (symbol name); boxes with a symbol never update; display is
    // one-feed-per-instance - why share?; time/date wrong means other zexy
    // classes may be wrong, and the similar imported libs were never
    // audited - you were asked to audit and did not"):
    // (1) Library-wide io audit DELIVERED (640 C classes): zexy 107 (agent),
    // cyclone 230, iemlib 100, creb 47, cxc 33, fftease 41, bsaylor 4,
    // osc 4, midifile 1, binfile 1, tracker 3 - every constructor read,
    // io creation calls position-tracked, signal sets computed from
    // CLASS_MAINSIGNALIN / "signal" methods / miXed sic_setup + sic_newinlet
    // (one SIGNAL inlet per call), loop-driven variable io excluded to
    // reg() rules, bare names owned by a higher-priority lib skipped
    // (slibs pre-load in REVERSED priority order, so cyclone outranks zexy
    // etc. and slibs shadow vanilla). ~230 defs rows added/fixed, incl.
    // creb envelope generators having NO signal inlet and zexy's five
    // control-in/signal-out + five signal-in/float-out classes.
    // (2) Variable-IO rules: zexy mux/multiplex/demux/demultiplex (+~ forms),
    // packel, multiline~, limiter~, sfplay, sfrecord now derive from
    // creation args per the sources (the old kt comment claiming a
    // "n <count> message" resize was wrong - args are one channel per token).
    // (3) route~ outlet 1 is a generic message outlet, NOT signal; wrap's
    // bare name is zexy's 2-inlet form (slibs shadow vanilla).
    // (4) OBJECT_PORTS.md rewritten per library (cxc/cyclone/zexy full sets,
    // creb/fftease/iemlib/bsaylor/osc/midifile/binfile new sections) plus a
    // 0.17.142 notes block: phantom classes (core_classes names with no
    // registration - zexy lp/mux_tilde~/tabread4_tilde~/zpack/zunpack,
    // creb cmath~, cxc binshift, fftease cross~/enrich~/loopsea~/pileup~/
    // pvwarpb~/schmear~/residency_buffer~, cyclone allhammers/allnettles/
    // allsickles aggregates) and internal proxies get no rows.
    // (5) else/elsex audit agent still running - its rows land next round. // USER verification round 6 ("the duplicated display now shows a
    // long tail (the symbol name) in the box - unreasonable, desktop pd
    // draws an empty-content shape; boxes with a symbol written behind do
    // not update their UI content at all, stuck showing display xxxxx;
    // display is one-feed-per-instance, why share at all?; time/date io
    // being wrong means other zexy classes may be wrong too, and the
    // similar imported libs were never audited - you were asked to audit
    // and did not"):
    // (1) The tagged-display silence was the session INLINER: display is
    // inlined per instance (inlineAbstraction), and substituteTokens mapped
    // $N to args.getOrNull(n) over box.args.drop(1) - off by one against
    // pd semantics ($1 = FIRST creation arg), so the abstraction's
    // [send $1] became [send 0] and no tagged box ever received anything.
    // Fixed to getOrNull(n-1) for every inlined abstraction (both call
    // sites pass drop(1); upstream else files are authored against real
    // pd, so the corrected mapping is what they always meant).
    // (2) The box no longer renders its args: the empty placeholder is the
    // bare class name (display/display~), in both objRect width sizing and
    // drawWidgetBody - the private receive tag stays in the saved text but
    // never draws (desktop-pd empty-content shape).
    // (3) No sharing, period: autoBindGui now guarantees every display box
    // (loaded, imported, any spelling) a unique display-rcv-<uid> tag and
    // force-regenerates collisions, so the shared pocketpd-display channel
    // has no listeners left in app-created patches (kept in the abstraction
    // only as dead fallback). Duplicate/paste regeneration from 0.17.140
    // stays. No asset change: Slibs.VERSION stays 128. // USER verification round 5 ("display duplicate reproduces the
    // symbol-sharing problem the other UI controls had; zexy time/date io
    // counts look wrong - consult and update the OBJECT_PORTS.md table;
    // multi-port rects: the first two / last two still have no gap between
    // them - the spacing math is wrong"):
    // (1) display/display~ now carry a per-instance PRIVATE receive tag:
    // the abstraction sends every message to [send $1] besides the shared
    // pocketpd-display fallback, addObject/editObject/duplicate/paste give
    // each box a unique display-rcv-<uid> tag (PdObjectMeta.setDisplayTag/
    // withDisplayTag), and bindGuiSymbols binds ONLY the tag for tagged
    // boxes - a duplicate no longer mirrors its source. A bare [display]
    // from an imported patch has no tag and mirrors the shared channel
    // (old behavior preserved). Slibs.VERSION 128 (abstractions changed).
    // (2) zexy time/date port counts, source-verified: time = 1 in / 4 out
    // (hour/min/sec/msec), date = 1 in / 6 out (year/month/day/weekday/
    // day-of-year/dst). Both were absent from PdObjectMeta.defs and from
    // OBJECT_PORTS.md, so they drew as the 1-in/1-out unknown-class
    // heuristic; added defs rows plus a zexy section to the table.
    // (3) Port-rect layout rewritten to the user's algorithm: inset the
    // edge rects so they stay inside the box FIRST, then distribute all n
    // rects evenly with a minimum gap between neighbours. The old code
    // distributed at the raw pitch and clamped afterwards, which pushed
    // each edge rect half a width into its neighbour (pitch - 1.5*w = 0
    // at the 2/3-pitch width). portRectPatch is now the single geometry
    // source for drawing (portRectScreen), cord anchors and portAt
    // hit-testing. // USER verification round 4 ("knob's Properties lists none of its key
    // parameters; knob now errors 'improper creation arguments' and cannot
    // be created - that did NOT happen two versions ago; display shows the
    // content now but the frame is still display-sized and does not enclose
    // it"):
    // (1) The knob creation error was OUR 0.17.138 setSymbols knob branch:
    // set(6, send) pads the holes 1-5 with the "empty" SYMBOL, and knob's
    // positional-numeric creation syntax then falls into pd's symbol-args
    // branch, which rejects the whole thing. The branch now ensures slots
    // 1-5 (size/min/max/exp/load) are numeric - filling only missing or
    // non-numeric ones, so hand-typed parameters survive.
    // (2) knob's Properties now lists size/min/max/exp/load via
    // objectParams + hasSymbolSlots(send/receive rows); the raw-args row is
    // exempted for it.
    // (3) display/display~ boxes grow with their runtimeText (the shown
    // message), like the number boxes grow with their value - the frame
    // encloses the content. Slibs.VERSION stays 127 (no asset change). // USER verification round 3 ("put knob and display (abbreviated dspl)
    // into the bottom add bar; display no longer errors but STILL shows
    // just the object name - solve this properly"):
    // (1) display's app-side chain was verified link by link (subscribe =
    // PdBase.subscribe, bindObjects timing, guiListener) - the remaining
    // suspect was pd's empty-creation-arg $1 expansion inside the
    // abstraction's [send pocketpd-display-$1]. Removed the dependence:
    // the abstraction now sends to the FIXED symbol pocketpd-display[~],
    // bindGuiSymbols binds that same fixed symbol, and every display box
    // in the patch mirrors the latest message (documented behavior).
    // (2) The add bar gains knob and dspl (display) drag keys after radio.
    // Slibs.VERSION 127 (the abstractions actually reach the devices). // USER verification round 2 ("ptouch.in still errors - pdgui_vmess
    // missing from elsex_core.so, check for similar problems; knob works;
    // display/display~ still show just the object name"):
    // (1) nm-diffed EVERY undefined blob symbol against libpd.so's exports:
    // besides the already-fixed support files, else's classes call a whole
    // pd-0.56/core surface libpd does not export - pdgui_vmess,
    // pdgui_stub_vnew/deleteforkey (17 classes: all the mc signal classes
    // plus knob), pd_undo_set_objectstate, signal_setmultiout and
    // sys_putmidibyte (ptouch.out). All five are now no-op shims compiled
    // into the blob (cyclone's gui_* shim precedent): headless GUI messages
    // dropped, undo hook idle, signal_setmultiout leaves t_signal at 1
    // channel so the multichannel classes degrade to mono safely (they all
    // loop over s_nchans), sys_putmidibyte drops bytes (else's midi-OUT
    // classes feed it; the app's own noteout bridge is a separate listener
    // path). elsex links libm explicitly for atan2/sin/pow.
    // (2) display bare-name reach: pd resolves a bare [display] only as
    // slibs/display.pd (per-directory, no recursion) - the abstraction
    // lived under slibs/else/ so a bare [display] could never load and the
    // box showed its plain object name forever. display.pd/display~.pd now
    // ship at the slibs root TOO, and the bind/render matching accepts both
    // spellings (display and else/display). Also fixed a real defect in the
    // abstraction itself: it shipped with two outlets (a stray second one).
    // Slibs.VERSION 126. // USER verification round on 0.17.135 ("display/display~ are still not
    // widgets, still printing to console; midi.in still errors - ptouch.in
    // cannot locate random_get_id in elsex_core.so; knob errors the same
    // way and is still not a widget"):
    // (1) ROOT CAUSE for BOTH the midi.in and knob errors: build_slibs'
    // support loop joined EX + "/" + d without honoring absolute paths, so
    // elsex's workspace support dir was silently skipped - buffer.c,
    // magic.c, random.c and mouse_gui.c were NEVER compiled, the blob
    // shipped with those calls undefined (shared libs allow that; dlopen
    // does not). ptouch.in died on random_get_id, knob on mouse_gui_*. The
    // loop now uses srcdir(); nm of the rebuilt blob shows the dynamic
    // symbol table clean of those undefineds. elsex ok=52 fail=0.
    // (2) The "display still prints" sighting was OUR OWN missed re-extract:
    // 0.17.135 rewrote display.pd but left Slibs.VERSION at 124, so the
    // devices kept running the 0.17.134 print build (AGENTS checklist rule
    // 3, tripped again). VERSION is now 125; the widget-echo abstraction is
    // actually on the devices this time.
    // (3) knob is now a REAL widget on the canvas: PdObjectMeta gains a gui
    // branch (args slots 6/7 = send/receive, size arg 1), creation auto-
    // assigns its symbols, the canvas draws a 270-degree dial with pointer
    // (drawKnob) and vertical drag sweeps the value through the existing
    // slider path (guiPress/guiDrag/lockDrag + sliderRange knob branch +
    // guiValue load slot 5).
    // Note: device log still not pullable (MCP adb bridge has no SDK path);
    // the support-path bug was proven by nm of the shipped blob. // USER follow-ups on 0.17.134 ("verify the io counts, watch UI-widget
    // usability, display is NOT supposed to print to the console - it must
    // show the info in the widget, no more work-dodging; midi.in still has
    // a bug, read the phone log"):
    // (1) display/display~ rebuilt AGAIN, for real this time: the
    // abstraction echoes every inbound message over the
    // pocketpd-display[~]-<arg> symbol and the app renders it INSIDE the
    // box (PdEngine.bindGuiSymbols binds the symbol to the display
    // instance; receiveList/Symbol/Float land in runtimeText/runtimeValue
    // through the existing guiBindings bridge; PatchCanvasView draws the
    // text in the box). Optional creation arg namespaces multiple
    // displays ([display mytag]); messages pass through untouched.
    // (2) midi.in root cause fixed at the bridge: the app NEVER fed the
    // pd MIDI byte stream, so vanilla [midiin] and every else class bound
    // to #midiin (midi.in's raw-mode ctl.in/note.in/ptouch.in/..., mpe.in
    // in -ext mode) had no input at all. MidiParser now calls
    // PdBase.sendMidiByte(0, byte) for every wire byte.
    // (3) io counts re-audited class by class against the vendored C
    // sources: tabreader~ really has a signal IN (nullfn signal) and
    // tabwriter~ three signal inlets (main + two relays) - both rows
    // corrected; all 45 other rows verified against the constructors
    // (two scanner regex bugs found and accounted: floatinlet_new matched
    // the bare inlet_new pattern, commented-out outlet_new lines counted).
    // (4) knob IS compilable after all: sys_gui/sys_vgui exist in the
    // shipped libpd.so (nm-verified), the 0.56-dev-only THISGUI theme
    // fields are pinned to constants via build patches. elsex is now
    // ok=52 fail=0 both ABIs (knob + its _handle proxy); mouse_gui.c
    // compiled into support. Port row added; the canvas has no dial
    // rendering yet so knob shows as a box with working logic.
    // Note: the MCP adb bridge lost its SDK path this session, so the
    // device log could not be pulled - the midi.in bug was root-caused
    // from the code instead. // NEW else integration, per the user's report ("else has midi.in and
    // mpe.in, they are missing here - check what else is missing; and
    // display crashes the app"). Findings and fixes:
    // (1) The bundled else library was a hand-integrated subset - 137 .pd
    // abstractions + a 217-class else_core.so whose source tree is gone.
    // The missing abstractions are SHIPPED now: 169 more .pd files copied
    // verbatim from upstream master (Control/Audio/Extra, Merda modules
    // and author-machine debris excluded), incl. midi.in/midi.out/
    // midi.clock/midi.learn and the presets/score/scala/scales families.
    // Abstraction references to else's compiled classes had to be
    // REWRITTEN bare-name (1391 refs in 264 files): pd resolves
    // "else/ctl.in" only as a FILE, and the C class registers bare, so
    // those refs could never have worked.
    // (2) NEW elsex incremental library (slibs/else-src, GPL-3 vendored
    // upstream subset): 50 classes the old else_core.so never had, built
    // with build_slibs.py - mpe.in, ptouch.in/out, retrieve, dcblock~,
    // canvas.*, osc.format/parse, tab*~, and 16 multichannel signal
    // classes that only compile against pd 0.55 headers (CLASS_MULTICHANNEL
    // / s_nchans). The runtime's own m_pd.h (else-src/pd55) is used for the
    // whole lib; t_signal offsets verified against ugen_connect in the
    // shipped libpd.so (s_nchans #0x18, s_sr #0x10). ok=50 fail=0 both
    // ABIs. Slibs.PRIORITY gains "elsex"; Slibs.VERSION 124.
    // (3) display/display~: the upstream abstractions rebuild their GUI via
    // namecanvas + canvas dynamic messages, which crash this pd 0.55
    // runtime - replaced with console-print builds (message/signal value
    // printed to the console strip, passthrough outlet kept). Port rows
    // for all 50 elsex classes measured from the C sources. else help
    // patches: 150 new files copied, 1915 same bare-name rewrites; lint of
    // everything ships clean (the only complaints are #A array-data rows,
    // upstream's own embedded lookup tables, false positives).  // NEW xm~ slide: the old "slider" is renamed to the cross-module
    // spelling and now does a REAL smooth sweep, ms-exact like opl3~'s
    // glide stepper - the previous one-shot fed the target note into an
    // XM 3xx tone-portamento slot, whose per-tick advance is tempo-coupled
    // and audibly stepped, and the slider arg was a raw 3xx speed rather
    // than a time. Now: chN glide <ms> arms a channel; chN note glides
    // from the previous pitch when armed and sounding (retrigger at sweep
    // start, opl3~/ym2612~'s law); chN slide <midi> [ms] [vel] sweeps the
    // sounding note over ms milliseconds with NO key event, riding a
    // per-block linear-period interpolation written through the new
    // libxm period API (xm_manual_get/note/set_period - the note-period
    // formula mirrors xm_tone_portamento_target so a slid note lands
    // exactly where a triggered one would; this also fixes the old
    // slider's missing +1 note offset). vel 0-127 rides the volume
    // column (omitted = the level stays), matching the cross-module
    // slide <pitch> <time> <velocity> form the user asked for - opl3~
    // and ym2612~ gained the same optional [vel] argument on slide.
    // Else audit per user request: [mpein] is a strict superset of
    // else's [mpe.in] (which has no zone/RPN/bendrange logic at all),
    // and the one thing [midi.in] has that we lacked - system realtime
    // messages - is now bridged to the [midi-sys] pd receiver (0xF8-0xFF,
    // running status preserved). Import: the home menu says "Import
    // files" (no .pd filter - any loadable file), multi-select via
    // OpenMultipleDocuments, plus a new Import folder entry
    // (OpenDocumentTree, recursive walk flattened into the patches dir).
    // About-screen license entries that were missing their newline separators
    // now wrap. Canvas: port rectangles on many-port objects no longer
    // squash together at the box edges - rects shrink to 2/3 of the port
    // pitch so neighbours (including the edge-clamped end ports) never
    // overlap and stay inside the box. Slibs.VERSION 123.  // NEW mpein - universal MPE v1.0 parser slib, per the user direction ("mpe support is not for these chiptune/tracker libs, needs a more universal parsing form"): the bridge now ALSO emits every complete channel message to the [midi-raw] pd receiver (raw bytes, semantic sends unchanged); the new [mpein] object eats that stream (list status,d1,d2) and does the full MPE aggregation pd-side - RPN 0,6/0,7 zone declarations tracked and echoed, per-channel voice bindings, CC74 = slide, CC2 + channel/poly aftertouch = press, per-channel pitch bend = continuous semitones (+/- bendrange, default 48), note down carries slide/press snapshots, non-dimension CC bytes ride a midi passthrough (ch*128+value). Single selector-ized outlet (note/pitch/slide/press/midi/zone) split with [route] - NOTHING knows about any synth, any patch decides what the voices do. Channels ungated (zoneless devices still work). mpein-help.pd hand-feeds test bytes (145 60 100 / 226 0 96 / 178 74 90 ...) and shows the route split; lint clean. PdObjectMeta d(1,1). Slibs.VERSION 122 manual-playing crash fix - 0.17.130 crashed the session right after the first short burst of sound: after xm_create_context()/xm_reset_context() every ch->current is NULL (upstream's own xm_dump_context sets them 0 and comments "force next generated samples to call xm_row() and refill ch->current") and normal playback always runs that first row, masking the nulls; manual mode freezes rows, so ONLY the played channel got repointed at its private slot while xm_tick() runs tick effects on EVERY channel - the second tick dereferenced NULL (toilet3 has 18 channels) and the session died. Fix: xm_manual_slot() repoints EVERY channel at its private manual slot on entering manual mode (zeroed slots are inert). Known cosmetic edge: goto during manual playing runs one row through the seek path (legal pointer, no crash). Slibs.VERSION 121
  // demo.xm re-tuned - the previous fixture used note bytes 57-69 with relnote 0, but tracker note bytes are SAMPLE PLAYBACK RATES (8363 Hz at byte 49 linear), not MIDI numbers: those bytes ran the 64-frame square at 13-26 kHz (fundamental 830-1656 Hz = the user's "音高非常高"). relnote -24 + bass bytes 40/45 now put the fundamentals at 78-415 Hz (A3 arpeggio + bass), wave amplitude +-60 (delta transitions +-120 stay in the signed byte; float samples normalize to +-0.47). toilet3.xm itself is a real module (pattern bytes 56-68 + relnote 0 = correct rates per the FT2 convention) and should already be pitch-correct on 0.17.125. No C changes
  // xm~ play-forever-silent fix: xm_reset_context() memsets the context tail from remaining_samples_in_tick to the struct end, ZEROING current_sample_rate (upstream xm.h says to re-set the rate "immediately after xm_create_context() or xm_reset_context()"). play() reset the context but never re-applied the rate, so the first play after load ran with rate 0: the tick recharge became 0, ckd_sub borrowed on EVERY sample = one tick per sample, the whole module raced past in ~0.1 s (the user's "phone-ring" instant chirp) and the loop counter / loop-off end bang then stopped it permanently - every later play was reset->rate0->instant pass->silence, on BOTH demo.xm and toilet3.xm. Fix: xm_apply_sr() helper called after every reset (load/dsp/play/stop), perform also guards on x_split (malloc-fail zero path). Slibs.VERSION 117
  // demo.xm replaced - the bundled file was libxm's arpeggio.xm state-test fixture whose samples are all ZERO LENGTH (silent by construction: the upstream tests assert effect state, not audio), so the help demo played silence; now a generated 987-byte module (4 ch, 2x32-row patterns, one instrument, a 64-frame looped +-32 square at 8363 Hz, forward loop, notes A2-E4-A4 arpeggio with bass, FT2-packed fully-explicit packets, delta encoding verified to decode cleanly). Also: toilet3.xm (186149 bytes) pushed to both devices' app Patches dir via adb per user instruction - NOT bundled in the APK
  // no code change - 0.17.122 shipped twice: once with the original xm~-help, once with the fixed connections/text; this bump makes the artifact with demo.xm in the help dir + escaped text + PdHelp.VERSION 23 identifiable
  // NEW xm~ - XM/MOD/S3M tracker module player on the vendored libxm core (github.com/Artefact2/libxm, WTFPL v2, per the user request "design an xm~ for tracker playback and other uses, use libxm, interface per vgm~, one object not split, 2 main + 32 channel outputs, libxm copyright in Settings"). ONE object does load+play+mix+split: outlets 0/1 = stereo mix (a single xm_generate_samples_unmixed pass per block feeds both the mix sum and the split - calling the mixed generator on top would run the player twice), outlets 2-33 = channels 1-32 in isolation (post volume/pan), outlet 34 = end bang. libxm facts verified from upstream source before vendoring: sample rate is runtime-settable (xm_set_sample_rate, pinned XM_SAMPLE_RATE=0), per-channel output is native (xm_generate_samples_unmixed), XM/MOD/S3M all load (non-XM best-effort), loop_count increments on any re-crossed row (not just POT wraps) - so loop 0 = play-once with upstream max_loop_count=1 semantics: the first re-crossed row (natural wrap OR backward Bxx jump) stops + bangs, documented in help. The mixdown is an unnormalized sum (verified: xm_sample accumulates all channels) - loud modules can exceed +-1, dac~ clips. Messages per vgm~: load/play/pause/stop/goto <order> [row]/loop/mute/minst/info/status (status = the nes~/opl3~ convention with per-active-channel inst/freq/vol/pan). Wrapper range-checks mute/minst/goto - libxm asserts are compiled out (NDEBUG pinned in the vendored sources so a malformed module can never abort the app). Vendoring patches (all documented in libxm_upstream_NOTICE.txt): C23 constructs lowered to C99 (constexpr x3, typeof x2, ~40 static_assert -> _Static_assert, [[maybe_unused]] stripped), NDEBUG pinned, build defaults block pinning the upstream CMake defaults (XM_VERBOSE 0 instead of 1: no stderr spam on Android), two shim headers (stdckdint.h/stdbit.h via clang overflow/popcount builtins) for the C23 headers the NDK sysroot lacks, xm.h generated from xm.h.in with XM_SAMPLE_TYPE=float. Settings About/licenses lists libxm. demo.xm example module bundled from the upstream test fixtures (4-channel arpeggio). Port row d(1, 35, 0..34) in PdObjectMeta. Slibs.VERSION 116, PdHelp.VERSION 21, Examples.VERSION 56
  // opl3~ pitch-write keyon fix - the real bug behind the user's "slide以后声音会自己消失" report (gm41, their opl3-key-test patch): opl_chip_fnum rebuilt the B0 byte from blk/fnum alone, dropping bit 5 = THE KEYON, so every pitch update through it wrote key-off on a sounding voice. Death paths, all in one line: slide <m> 0 and one-arg slide <m> without an armed glide (ms=0 instant re-pitch path), a slide to the channel's current pitch (glide_start straight-jump path), a slide on a gate-keyed voice (x_freq still 0 -> straight path), a plain float on the pitch inlet. ym2612~ was never affected - its $A4/$A0 writes cannot touch the key register $28, which is why the ym side of the same feature measured clean on-device while the user's opl3 test died. Fix: opl_chip_fnum preserves B0 bit 5 (voice_init's virgin flush keeps keyon 0; gate 0 and note's retrigger still write key-off explicitly before their key-on, so no other call site changes behavior), and the perform stepper drops its duplicated inline rebuild for a plain opl_chip_fnum call - that inline preserve existed only as a workaround for this very bug. Found by plain code audit of every opl_chip_fnum caller (voice_init/float/gate/pitch_update/note/stepper); lint clean. Slibs.VERSION 115  // 0.17.120: four items from the user: ym2612~/opl3~ portamento, nsf~ multi-track playback modes, the "no such object" console-garbage fix (unescaped semicolons), else-lib rand.i/rand.f red boxes. (1) GLIDE: both chips' voice layers gain `glide <ms>` (arm portamento; `note` then sweeps geometrically - midi-linear - from the previous pitch to the new one, retrigger still at sweep start) and `slide <midi> [ms]` (sweep WITHOUT a key event; ms omitted = the glide time), chN-scoped like every voice command; stepped once per perform block (one fnum write per block, the chip re-rates a running voice on each fnum write). ym2612~ also gains a `status` dump on the opl3~/nes~ convention (per-channel voiced/gate/pitch/fn/blk/glidepos) - it did not have one. (2) NSF MODES: `mode <single|seqloop|seqstop|randloop|randstop>` - single (default) = the old loop-message behavior, seqloop = wrap 1..n, seqstop = stop after the last track (end bang), randloop = random track each end, randstop = random unplayed until all played (then end bang); `songtime <sec>` = per-track length for tracks without NSFE time chunks (default 120 s); `loop` stays single-mode-only. Latent 0.17.118 bug found on the way: nsf_start_track zeroed x_track_ms AFTER load had set the NSFE per-track time, so NSFE play lengths NEVER reached the runtime and the end bang could not fire at all - times now live on the instance (x_times/x_ntimes, per-track picked in start_track) and goto/auto-advance ride them too. (3) PATCH TEXT: 19 unescaped mid-line semicolons across 7 examples/help patches split pd statements at load - the leftovers became canvas messages and the console printed garbage like "dense .../nsf~ ..." that looked like missing objects (nsf~ itself was always registered - manifest rows for both ABIs verified); all escaped, and lint_pd.py now accepts single FILES as arguments (passing file paths silently linted nothing before - that is why earlier runs reported clean) and prints the checked count. (4) ELSE LIB: rand.i/rand.f drew red because build_slibs.py never managed the else lib (integrated by hand in an earlier round, source tree gone) so slibs_manifest.txt had no else rows and the app's known-class set missed all 217 compiled else classes (pd itself always resolved them - setup_rand0x2ei etc. are exported); build_slibs.py now backfills manifest rows for every unmanaged assets/slibs/<lib> with a core_classes.txt. Help patches: glide/slide demos on ym2612~-help/opl3~-help, mode/songtime demos on nsf~-help; nsf-player example text lists the modes. Examples.VERSION 55, PdHelp.VERSION 19, manifest 1111 -> 1328 rows. Self-test caught two of my own mistakes before any device shipped: the glide position increment was 1000x too fast (an 800 ms glide finished inside one perform block - the new ym status glidepos metric caught it) and the first fixed APK still carried the old compiled .so because build_slibs.py was not re-run after the source fix (the Slibs.VERSION bump alone does not recompile assets); on the fixed build the probe reads glidepos=0.37 mid-sweep (262*2^0.37 = 339 Hz, exactly geometric) and settles at fn=1287 = midi 72  // nes~/nsf~ outlet-layer click fixes from the user's listening report (three complaints: nes~ ch5 DPCM hard clicks with no DPCM data; nsf~ outlet-1 occasional artifacts; nes~ ch4 noise extra junk). nesdev pages re-checked via the reader tool (plain WebFetch 403s): APU DMC - "the output level is sent to the mixer whether the channel is enabled or not", loaded 0 on power-up - so the core's val=delta was already silicon-exact and the clicks were the OUTLETS; APU Noise - "the period determines how many CPU cycles happen between shift register clocks" = rate CPU/n, the core's noise timer was already exact. nes~ ch5 + nsf~ outlet 1: the $4015-enable gate + fixed 63.5 pedestal parked an enabled-but-silent unit on the -0.25 rail (power-up level 0) and hard-stepped at every enable/sample-end boundary; now ungated (the level is always live) + one-pole ~1.5 Hz AC coupling (the console's own output stage is AC-coupled) - a held level fades out smoothly, $4011 direct-DAC writes (VGM-log drums) still pass; nes_apu.c with no memory reader (nes~'s empty sample RAM) the output unit stays silent per the nesdev buffer-empty rule instead of phantom-playing zero bytes. nes~ ch4: the fixed half-volume pedestal is wrong in short LFSR mode - bit0-clear density depends on the orbit entry point (measured 0.828 vs 0.484; mode 0 exact 0.5); the core simulates the orbit at every $400E write and exposes density, pedestal = volume x live density (old code left 0.16 DC on the 0.828 orbit = negative-rail parking), status prints dens=; plus a 3 ms fade on the noise gate edges (length expiry / key-off cut from a random level = a broadband click the console's analog stage would have softened). Self-test caught my own bug: the AC-coupling prime seeded the highpass state with the input level instead of zero - the first keyed sample thumped -0.25 and decayed ~0.7 s; fixed in both nes~ and nsf~. Verification: nes_apu 30-assertion + NSF replay 21-assertion harnesses re-run green; on-device probe (_nes_fix.pd, run-as deploy + logcat absolute metrics): ch5 after nes 15 31 ~ 0 (was the -0.25 rail), ch4 mode-1 status dens=0.484 with the pedestal tracking it, nsf~ outlet shows kick sweeps and fades to 1e-19 after stop with no step. Slibs.VERSION 111, Examples.VERSION 54 (outlet text in nes-player/nsf-player), PdHelp.VERSION 18  // previous round: NEW 2A03 (NES APU) module pair, following the vgm~+opl3~ playback/generation split. nes~ = the synth (nes_apu.c written from scratch against nesdev.org, every unit verified against the live wiki pages this round: DMC $4012=$C000+A*64/$4013=L*16+1, sweep mute as a continuous target-period condition, noise feedback bit0^bit1/bit6, output bit0 CLEAR = volume, delta 0..127, region noise/DPCM tables): inlet 0 = command stream (voice layer note/gate/duty/sweep/mode + `nes <reg> <val>`/`reg` raw writes + clock/gain/reset/status, chN message-scoped, comma-less chains per the 0.17.115 spec), inlet 1 = gate = ch1; outlets 0 = the chip's own non-linear DAC mix, 1..5 = pulse1/pulse2/triangle/noise/DPCM alone (pedestal removed, +-0.25). nsf~ = NSF/NSFE player: own 6502 core (cpu6502.c, official + common illegal opcodes, no decimal - the 2A03 has none) drives the same nes_apu as the CPU readback/DPCM model and streams `nes <reg> <val>` writes on outlet 0; outlet 1 = DPCM signal (sample data cannot ride registers - vgm~ DAC precedent); outlet 2 = end bang (NSFE time chunks, loop off); messages = the vgm~ set (load/play/pause/stop/goto <track>/loop/info); NSF layout per nesdev: text at $00E-$06D, play speeds in microseconds at $06E/$078, bankswitching = nonzero bank bytes $070-$077 (gme's header struct confirms - my old 0x12 field memory was wrong), NSF2 24-bit data length at $07D, non-returning PLAY supported (no force-kill, the 1 s guard is init-only). vgm~ parses VGM 0xBC -> `nes <reg> <val>` on outlet 1 (shadow for goto re-emission + `nes 15 0` pause keyoff sweep, census row at header 0x80); sn76489~/opl3~ ignore the new sibling selector silently. Verification (all absolute indicators): Klaus Dormann 6502 functional test reaches the decimal-mode section (D=1, the 2A03 has no BCD) - caught and fixed three real CPU bugs on the way (missing 0xBD LDA abs,x case; PLP/RTI kept the pulled B bit; ADC/SBC overflow tested bit 6 of the XOR instead of bit 7); nes_apu 28-assertion harness (pulse/tri frequencies 440 Hz, envelope decay 1 s, length 254 steps = 2.11 s at the 120 Hz half-frame clock - the "4.23 s" folklore and the wiki's 75-bpm paragraph are wrong, blargg's gme AND NSFPlay both clock lengths on half-frames; frame IRQ at 60 Hz; DPCM delta/IRQ/silence; mixer table spots); NSF replay harness 21 timeline assertions on demo_nes.nsf (init writes, melody/harmony/bass/hat/kick register writes, DPCM ROM fetches). demo_nes.vgm/.nsf/.nsfe generated by gen_demo_nes.py (same tune everywhere); nes-player.pd / nsf-player.pd examples + nes~-help / nsf~-help on the established layouts; PdObjectMeta port rows; Slibs.VERSION 110 (pedestal gate on nes~ channel outlets + nsf~ DPCM outlet; nesdev player-init writes through the nsf~ snoop), Examples.VERSION 53, PdHelp.VERSION 17
    }

    signingConfigs {
        create("eufon") {
            storeFile = file("eufon.keystore")
            storePassword = "eufon2026"
            keyAlias = "eufon"
            keyPassword = "eufon2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("eufon")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("eufon")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // libpd for Android: Pure Data runtime (audio + .pd interpreter, all ABIs).
    // 0.18.0 (0.57 branch): switched from Maven 1.2.1-rc6 (pd 0.51.3, last
    // formal release, 2021) to the SELF-BUILT AAR from pd-for-android master,
    // pinned libpd@e3cd52fd7517 -> pure-data@43a5b8922a35 = pd 0.57.0test1.
    // Built in C:/Projects/pd-for-android (local deviations: minSdk 26, NDK
    // 27.2, glob shim for API<28 - see MIGRATION_057.md W0.1/W0.2); consumed
    // via mavenLocal as io.github.libpd.android:pd-core:1.4.4-SNAPSHOT.
    implementation("io.github.libpd.android:pd-core:1.4.4-SNAPSHOT")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // diagnostic probe (androidTest): framework runner is gone from API 34's
    // android.jar (junit.framework / android.test.* removed), so the probe
    // rides androidx.test
    // OTG USB serial adapters (CDC-ACM / FTDI / CP210x / CH340 / PL2303 ...)
    // 3.7.3 is the newest build that compiles against SDK 34 (3.8+ needs 35)
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")

}
