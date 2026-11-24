package com.pocketpd.app.runtime

import android.content.Context
import android.media.AudioManager
import com.pocketpd.app.patch.AbstractionPorts
import com.pocketpd.app.patch.Abstractions
import com.pocketpd.app.patch.Circle5
import com.pocketpd.app.patch.EuclidRhythm
import com.pocketpd.app.patch.ObjKind
import com.pocketpd.app.patch.ObjectItem
import com.pocketpd.app.patch.PatchConnection
import com.pocketpd.app.patch.PatchDocument
import com.pocketpd.app.patch.PatchObject
import com.pocketpd.app.patch.PdObjectMeta
import com.pocketpd.app.patch.newObjectId
import org.puredata.android.io.PdAudio
import org.puredata.core.PdBase
import org.puredata.core.PdMidiReceiver
import org.puredata.core.PdReceiver
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * App-wide Pure Data runtime. Wraps libpd's [PdAudio]/[PdBase]:
 *
 *  - audio lifecycle ([PdAudio] uses OpenSL ES under the hood),
 *  - patch open/close with gui widget binding,
 *  - one global [PdReceiver] fanning out to per-symbol subscribers,
 *  - console log of everything [print]-ed or that goes wrong,
 *  - MIDI-out from patches and OSC-in/out bridges.
 *
 * All callbacks from libpd arrive on the main thread (PdAudio polls the message
 * queue with a main-looper handler); sending into Pd is thread-safe because
 * libpd locks its instance.
 */
object PdEngine {

    /** Delay-line classes: their names live in one GLOBAL pd table, so every
     *  inlined abstraction instance must own a per-uid copy of the name
     *  (top-level rows and subpatch-block rows alike). */
    private val DELAY_NAME_CLASSES = setOf("delwrite~", "delread~", "vd~", "delread4~")

    data class AudioConfig(
        val sampleRate: Int = 48000,
        /** Ticks of 64 samples per buffer; 8 = 512 samples (~10.6 ms at 48 kHz). */
        val ticksPerBuffer: Int = 8,
        val inputEnabled: Boolean = false,
    )

    interface ConsoleListener {
        fun onConsoleLine(line: ConsoleLine)
    }

    /** Called (on the main thread) when a gui widget value changed from inside Pd. */
    interface GuiUpdateListener {
        fun onGuiChanged(objId: Long)
    }

    @Volatile
    var consoleListener: ConsoleListener? = null

    @Volatile
    var guiListener: GuiUpdateListener? = null

    /** Set to the app's [MidiBridge]; Pd MIDI output ([noteout]…) lands there. */
    @Volatile
    var midiOutListener: MidiBridge? = null

    @Volatile
    var oscOutListener: OscBridge.OscFromPdListener? = null

    /** Serial-out hook: bytes patches send to the receiver `TTY` (SerialBridge). */
    @Volatile
    var ttyOutListener: ((args: Array<out Any>) -> Unit)? = null

    /** VU hook: linear 0..1 levels (left, right) tapped from the patch's
     *  dac~ connections, or sent by the patch to `pocketpd-vu-l/-r/-vu`. */
    @Volatile
    var vuListener: ((Float, Float) -> Unit)? = null
    private var vuL = 0f
    private var vuR = 0f

    /** Current audio setup; the scope drawing reads the sample rate for the
     *  spectrograph's frequency axis. */
    var audioConfig = AudioConfig()
        private set
    private var patchHandle: Int = -1

    /** True while a pd patch is open (a brand-new patch has none until its
     *  first session build — interaction hooks use this to open lazily). */
    fun hasSession(): Boolean = patchHandle >= 0
    private var patchFile: File? = null

    /** Directories already registered on pd's search path (open_via_path
     *  consumers resolve sibling files against it; deduped per process). */
    private val addedSearchPaths = HashSet<String>()

    /**
     * Class names pd itself reported "couldn't create" for while opening the
     * current patch — the runtime counterpart of the static known-class
     * check (covers classes our tables miss). Canvas marks these boxes with
     * the warning style too.
     */
    @Volatile
    var failedClasses: Set<String> = emptySet()
        private set

    /** Invoked (main thread) when [failedClasses] gained a new name. */
    @Volatile
    var onFailedClassesChanged: (() -> Unit)? = null

    /** symbol -> objIds bound for receive updates. */
    private val guiBindings = HashMap<String, MutableSet<Long>>()
    private val subscribedSymbols = HashSet<String>()

    // ------------------------------------------------------------------ [euclid]

    /** Play state of one [euclid] widget, keyed by object id. */
    private class EuclidState(
        val hits: Int,
        val steps: Int,
        val rotation: Int,
    ) {
        val pattern = EuclidRhythm.pattern(hits, steps, rotation)
        /** Next step to fire. */
        var pos = 0
        /** Ordinal of the next hit within the current circle (right outlet). */
        var hitIdx = 0
    }

    private val euclidStates = HashMap<Long, EuclidState>()

    /**
     * App widgets (euclid clocks, circle-of-fifths dials) fire only in
     * run/lock mode: the pd session is alive from the moment a patch opens,
     * so a metro left running would keep stepping the clocks (and feeding
     * their outlets) while the user edits wiring that the live session
     * doesn't even reflect yet.
     */
    @Volatile
    var widgetsLive: Boolean = false

    /** in0 relay symbol -> object for the circle-of-fifths dial (single
     *  inlet; degrees, bangs and `chord`/`size` config all land here). */
    private val circle5In = HashMap<String, PatchObject>()

    /** [pad] config receivers: relay symbol → the pad it reconfigures. */
    private val padIn = HashMap<String, PatchObject>()

    /**
     * in0/in1 relay symbol -> (object, inlet index) for trigger/reset.
     * Holds the object reference directly: subpatch-block widgets bind out
     * of parsed row text as engine-side stand-ins that never appear in
     * [boundObjects], but their args carry the same relay symbols.
     */
    private val euclidIn = HashMap<String, Pair<PatchObject, Int>>()

    /**
     * Live state for [obj], rebuilding when hits/steps/rotation changed
     * (Properties, undo, AI edit): position survives (wrapped into the new
     * circle), the hit ordinal restarts, the pattern recomputes.
     */
    private fun euclidState(obj: PatchObject): EuclidState {
        val key = EuclidRhythm.clamp(
            obj.args.getOrNull(1)?.toIntOrNull() ?: 3,
            obj.args.getOrNull(2)?.toIntOrNull() ?: 8,
            obj.args.getOrNull(3)?.toIntOrNull() ?: 0,
        )
        val old = euclidStates[obj.id]
        if (old != null && old.hits == key.first && old.steps == key.second &&
            old.rotation == key.third
        ) return old
        val fresh = EuclidState(key.first, key.second, key.third)
        if (old != null) fresh.pos = old.pos % key.second
        euclidStates[obj.id] = fresh
        return fresh
    }

    /**
     * Relay symbol for an euclid inlet/outlet: the auto-assigned slot token
     * when present, else a deterministic fallback from the object id (only
     * reachable for objects that never went through [assignEuclidSymbols]).
     */
    private fun euclidRelaySym(obj: PatchObject, slot: Int, kind: String): String =
        obj.args.getOrNull(slot)?.takeIf { it.isNotBlank() && it != "empty" && it != "-" }
            ?: "auto-euclid-${obj.id.toString(36)}-$kind"

    private fun euclidInSym(obj: PatchObject, inlet: Int): String =
        euclidRelaySym(obj, 7 + inlet, "in$inlet")

    private fun euclidOutSym(obj: PatchObject, outlet: Int): String =
        euclidRelaySym(obj, 9 + outlet, "out$outlet")

    /**
     * Outbound/inbound symbol actually in effect: the user's send/receive
     * name when set, else the widget's internal relay name (button keeps
     * those in transient fields so Properties default to blank).
     */
    private fun effSnd(obj: PatchObject): String =
        obj.meta.sendSymbol.ifEmpty { obj.autoSend }

    private fun effRcv(obj: PatchObject): String =
        obj.meta.receiveSymbol.ifEmpty { obj.autoReceive }

    /** True while any of the four relay slots is still unassigned. */
    fun euclidSlotsUnassigned(obj: PatchObject): Boolean =
        listOf(7, 8, 9, 10).any {
            obj.args.getOrNull(it)?.takeIf { t -> t.isNotBlank() && t != "empty" && t != "-" } == null
        }

    /**
     * Fills the euclid relay slots (in0/in1/out0/out1 at args 7..10). Called
     * at drop time and from [autoBindGui]/[walkSubpatchGuis] so the same
     * names reach pd's session copy, the app bindings and the saved file.
     * With [force] the slots are overwritten unconditionally — that is the
     * collision repair for clones: duplicate/paste/AI rows copy the source's
     * args verbatim, and two clocks sharing a relay name steal each other's
     * triggers and outputs (the binding map keeps only the last twin).
     */
    fun assignEuclidSymbols(obj: PatchObject, uid: String, force: Boolean = false) {
        while (obj.args.size < 11) obj.args.add("-")
        fun put(slot: Int, name: String) {
            val cur = obj.args[slot]
            if (force || cur.isBlank() || cur == "empty" || cur == "-") obj.args[slot] = name
        }
        put(7, "auto-euclid-$uid-in0")
        put(8, "auto-euclid-$uid-in1")
        put(9, "auto-euclid-$uid-out0")
        put(10, "auto-euclid-$uid-out1")
        obj.meta = PdObjectMeta.resolve(obj)
    }

    // ------------------------------------------------------------------ [circle5]

    private fun circle5RelaySym(obj: PatchObject, slot: Int, kind: String): String =
        obj.args.getOrNull(slot)?.takeIf { it.isNotBlank() && it != "empty" && it != "-" }
            ?: "auto-circle5-${obj.id.toString(36)}-$kind"

    private fun circle5InSym(obj: PatchObject): String = circle5RelaySym(obj, 6, "in0")

    // ------------------------------------------------------------------ [pad]

    /**
     * [pad] relays: slots 5/6/7 of `pad w h gridx gridy in0 out0 out1`.
     * in0 carries live config messages (`gridx n`, `gridy n`, `size w h`)
     * from the patch into the app; out0 streams "x y e" finger lists and
     * out1 streams "col row e" grid-cell lists from the app into the patch.
     */
    private fun padRelaySym(obj: PatchObject, slot: Int, kind: String): String =
        obj.args.getOrNull(slot)?.takeIf { it.isNotBlank() && it != "empty" && it != "-" }
            ?: "auto-pad-${obj.id.toString(36)}-$kind"

    private fun padInSym(obj: PatchObject): String = padRelaySym(obj, 5, "in0")

    private fun padOutSym(obj: PatchObject, outlet: Int): String =
        padRelaySym(obj, 6 + outlet, "out$outlet")

    fun padSlotsUnassigned(obj: PatchObject): Boolean =
        listOf(5, 6, 7).any {
            obj.args.getOrNull(it)?.takeIf { t -> t.isNotBlank() && t != "empty" && t != "-" } == null
        }

    /**
     * Fills the pad relay slots (in0/out0/out1 at args 5..7), same contract
     * as [assignEuclidSymbols]: [force] repairs clones whose copied relay
     * names would cross-wire two pads.
     */
    fun assignPadSymbols(obj: PatchObject, uid: String, force: Boolean = false) {
        while (obj.args.size < 8) obj.args.add("-")
        fun put(slot: Int, name: String) {
            val cur = obj.args[slot]
            if (force || cur.isBlank() || cur == "empty" || cur == "-") obj.args[slot] = name
        }
        put(5, "auto-pad-$uid-in0")
        put(6, "auto-pad-$uid-out0")
        put(7, "auto-pad-$uid-out1")
        obj.meta = PdObjectMeta.resolve(obj)
    }

    /**
     * [pad] live configuration from its inlet relay: `gridx n`, `gridy n`,
     * `size w h` — keys chain in one message (`gridx 4 gridy 2`), unknown
     * keys are skipped. Writes the creation args, re-resolves meta (the
     * size follows immediately) and asks the UI to repaint.
     */
    private fun padConfigure(obj: PatchObject, args: List<Any>) {
        var i = 0
        var changed = false
        while (i < args.size) {
            val key = args.getOrNull(i) as? String
            val n0 = args.getOrNull(i + 1) as? Number
            val n1 = args.getOrNull(i + 2) as? Number
            when (key) {
                "gridx" -> if (n0 != null) {
                    while (obj.args.size < 4) obj.args.add("0")
                    obj.args[3] = n0.toInt().coerceIn(1, 64).toString()
                    changed = true; i += 2; continue
                }
                "gridy" -> if (n0 != null) {
                    while (obj.args.size < 5) obj.args.add("0")
                    obj.args[4] = n0.toInt().coerceIn(1, 64).toString()
                    changed = true; i += 2; continue
                }
                "size" -> if (n0 != null && n1 != null) {
                    while (obj.args.size < 3) obj.args.add("0")
                    obj.args[1] = n0.toInt().coerceIn(40, 4096).toString()
                    obj.args[2] = n1.toInt().coerceIn(40, 4096).toString()
                    changed = true; i += 3; continue
                }
            }
            i++
        }
        if (changed) {
            obj.meta = PdObjectMeta.resolve(obj)
            trace("pad cfg: gridx=${PdObjectMeta.padGridX(obj)} " +
                "gridy=${PdObjectMeta.padGridY(obj)} " +
                "size=${obj.meta.guiWidth}x${obj.meta.guiHeight}")
            guiListener?.onGuiChanged(obj.id)
        }
    }

    /** Widget list output — [pad] streams its touch events per outlet. */
    fun sendGuiList(obj: PatchObject, outlet: Int, args: List<Any>) {
        if (obj.className() != "pad") return
        sendList(padOutSym(obj, outlet), args)
    }

    /** App-only [popmenu]: the picked item leaves as a plain symbol message
     *  on the widget's outbound relay (mirrors upstream popmenu.c's
     *  outmode-1 outlet_symbol output). */
    fun guiSymbol(obj: PatchObject, symbol: String) {
        val snd = effSnd(obj).ifEmpty { effRcv(obj) }
        if (snd.isNotEmpty()) sendSymbol(snd, symbol)
    }


    private fun circle5OutSym(obj: PatchObject): String = circle5RelaySym(obj, 7, "out0")

    fun circle5SlotsUnassigned(obj: PatchObject): Boolean =
        listOf(6, 7).any {
            obj.args.getOrNull(it)?.takeIf { t -> t.isNotBlank() && t != "empty" && t != "-" } == null
        }

    /** Fills the circle5 relay slots (in0/out0 at args 6/7); [force] is the
     *  clone collision repair, same as [assignEuclidSymbols]. */
    fun assignCircle5Symbols(obj: PatchObject, uid: String, force: Boolean = false) {
        while (obj.args.size < 8) obj.args.add("-")
        fun put(slot: Int, name: String) {
            val cur = obj.args[slot]
            if (force || cur.isBlank() || cur == "empty" || cur == "-") obj.args[slot] = name
        }
        put(6, "auto-circle5-$uid-in0")
        put(7, "auto-circle5-$uid-out0")
        obj.meta = PdObjectMeta.resolve(obj)
    }

    private fun circle5Size(obj: PatchObject): Int {
        // 0/invalid tokens mean "default": coercing them would hand back a
        // single-note "chord" (the 0.17.3 one-note bug was exactly this)
        val v = obj.args.getOrNull(2)?.toIntOrNull() ?: 3
        return if (v in 1..Circle5.MAX_SIZE) v else 3
    }

    /** Signed key: +pos major, -pos relative minor (see Circle5). The arg
     *  keeps the sign; 0/garbage falls back to C major. */
    private fun circle5Key(obj: PatchObject): Int {
        val raw = obj.args.getOrNull(1)?.toIntOrNull() ?: 1
        if (raw == 0) return 1
        val pos = Circle5.keyPos(raw)
        return if (raw < 0) -pos else pos
    }

    /** Emits one dial chord: a single output — the ascending midi-note LIST
     *  (60 64 67 in one go) on the outlet; the dial's center shows the chord
     *  name with its quality (Dm, B°, plain for major). */
    private fun emitCircle5Chord(obj: PatchObject, ring: Int, pos: Int) {
        if (!widgetsLive) return
        val pc = Circle5.outerPc(pos)
        val rootPc = when (ring) { 1 -> (pc + 9) % 12; 2 -> (pc + 11) % 12; else -> pc }
        obj.runtimeText = Circle5.pcName(rootPc) + when (ring) { 1 -> "m"; 2 -> "°"; else -> "" }
        sendList(circle5OutSym(obj), Circle5.chord(ring, pos, circle5Size(obj)).toList())
        guiListener?.onGuiChanged(obj.id)
    }

    /** Inlet float/bang: emits the diatonic degree's chord (degree wraps
     *  into 1..7; the tonic is degree 1). */
    private fun emitCircle5Degree(obj: PatchObject, degree: Int) {
        if (!widgetsLive) { trace("circle5 degree $degree dropped: edit mode"); return }
        val cell = Circle5.degreeCell(circle5Key(obj), degree)
        trace("circle5 degree $degree key=${circle5Key(obj)} -> ring${cell[0]} pos${cell[1]}")
        emitCircle5Chord(obj, cell[0], cell[1])
    }

    /** Tap on a dial cell (value = ring*12 + pos0): emits that chord and
     *  sets the current key to the cell — outer ring = that MAJOR key,
     *  middle ring = that relative MINOR key (negative), so a tap on am
     *  re-numbers the degrees from a (i=am, III=C) and lights am as the
     *  tonic. The canvas never delivers inner-ring taps. */
    private fun circle5Tapped(obj: PatchObject, cell: Int) {
        if (!widgetsLive) return
        val ring = (cell / 12).coerceIn(0, 1)
        val pos = ((cell % 12) + 12) % 12 + 1
        while (obj.args.size < 2) obj.args.add("-")
        obj.args[1] = (if (ring == 1) -pos else pos).toString()
        emitCircle5Chord(obj, ring, pos)
    }

    /** Live configuration through the inlet: `chord c` / `chord am` /
     *  `chord 2` / `chord -1`, `size 4` — single pairs or chained. */
    private fun circle5Configure(obj: PatchObject, atoms: List<Any>) {
        trace("circle5 cfg: ${atoms.joinToString(" ")} (key was ${obj.args.getOrNull(1)})")
        var changed = false
        var i = 0
        while (i < atoms.size - 1) {
            val key = (atoms[i] as? String)?.lowercase()
            when (key) {
                "chord", "key" -> Circle5.parseKey(atoms.getOrNull(i + 1))?.let {
                    while (obj.args.size < 2) obj.args.add("-")
                    obj.args[1] = it.toString()
                    changed = true
                }
                "size" -> (atoms.getOrNull(i + 1) as? Number)?.let {
                    val v = it.toInt()
                    while (obj.args.size < 3) obj.args.add("-")
                    obj.args[2] = (if (v in 1..Circle5.MAX_SIZE) v else 3).toString()
                    changed = true
                }
                else -> Unit
            }
            i += 2
        }
        if (changed) guiListener?.onGuiChanged(obj.id)
    }

    /** Receivers that stay subscribed across patch reloads (app services). */
    private val permanentSymbols = setOf("pocketpd-vu-l", "pocketpd-vu-r", "pocketpd-vu")

    private val console = ArrayDeque<ConsoleLine>()

    // ------------------------------------------------------------------ setup

    /**
     * libpd delivers pd -> Java output ([print], subscribed receivers, MIDI
     * out) through a queue that someone must drain with
     * [PdBase.pollPdMessageQueue]. PdAudio only polls while DSP is running,
     * so we run our own poller for the whole time a patch is open — message
     * flow must not depend on the audio state.
     */
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunner = object : Runnable {
        override fun run() {
            runCatching {
                synchronized(PdBase::class.java) {
                    PdBase.pollPdMessageQueue()
                    PdBase.pollMidiQueue()
                }
            }
            pollHandler.postDelayed(this, 5)
        }
    }

    fun init() {
        processStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        synchronized(PdBase::class.java) {
            PdBase.setReceiver(receiver)
            PdBase.setMidiReceiver(midiReceiver)
        }
        subscribe("pocketpd-vu-l")
        subscribe("pocketpd-vu-r")
        subscribe("pocketpd-vu")
        // keep desktop loadbang semantics (sys_noloadbang = 0)
        if (PdTweaks.available) PdTweaks.setLoadbangEnabled(true)
        // Touch a trivial native call so the JNI library is loaded up front.
        synchronized(PdBase::class.java) { PdBase.clearSearchPath() }
    }

    // ------------------------------------------------------------------ audio

    @Throws(IOException::class)
    fun configureAudio(config: AudioConfig) {
        audioConfig = config
        PdAudio.initAudio(
            config.sampleRate,
            if (config.inputEnabled) 1 else 0,
            2,
            config.ticksPerBuffer,
            true,
        )
    }

    fun startAudio() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            log("WARNING: media volume is 0 - raise the volume to hear output")
        }
        PdAudio.startAudio(ctx)
        // pd's own DSP switch: PdAudio ticks the scheduler, but signal
        // objects stay idle until "compute audio" is on (desktop pd's DSP
        // toggle — without this every ~ object runs silent)
        synchronized(PdBase::class.java) { PdBase.computeAudio(true) }
    }

    fun stopAudio() = PdAudio.stopAudio()

    fun isAudioRunning(): Boolean = try { PdAudio.isRunning() } catch (t: Throwable) { false }

    // ------------------------------------------------------------------ patch

    /** Re-fires the app-side clocks after a patch open (see openPatch). */
    private val scopeGoRunner = Runnable { runCatching { PdBase.sendBang("pocketpd-scope-go") } }

    /** How openPatch treats the audio engine around the parse. */
    enum class AudioOpen {
        /** Patch entry: ALWAYS start audio after the parse — the measured
         *  2026-11-06 order (the start must follow the parse or fresh
         *  objects never compile; audio was still starting back then and
         *  the skipped cycle silenced ym_dsp forever). */
        START,
        /** Run entry: start audio when stopped; already running, cycle
         *  pd's dsp switch only. */
        RUN,
        /** Edit-mode session sync: NEVER touch the audio engine. Stopped
         *  stays stopped (silent editing); running gets a dsp-only cycle. */
        SYNC,
        /** Patch ENTRY (0.18.11/12): audio THREAD runs (ticks flush the
         *  queued slib setups ahead of the parse — the 0.17.45 boot-race
         *  wall) but the DSP SWITCH STAYS OFF the whole time: the freshly
         *  parsed graph never renders, so opening a patch is SILENT (the
         *  0.18.10 EDIT-dsp-off workflow made any load-time render an
         *  audible crackle). Objects compile at the next dsp-on edge — the
         *  RUN entry rebuild re-runs the whole open anyway. */
        LOAD,
    }

    /**
     * Debug self-check (AGENTS.md 设备级测试禁令: no connected-test infra —
     * verification runs through debug-variant diagnostics instead). Dumps the
     * exact pd session text to logcat so a device-side SIGSEGV during open can
     * be matched to the statement pd was evaluating (0.17.146 superosc~ hunt).
     * Debug builds only, dedicated tag, ~3 KB chunks under logcat's line cap;
     * the app console strip is never fed these machine lines.
     */
    private fun dumpSessionForLogcat(file: File) {
        // debug gate via the application flag (the project keeps AGP's
        // buildConfig feature disabled, so there is no BuildConfig class)
        val ctx = appContext ?: return
        val debuggable = runCatching {
            ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        }.getOrDefault(false)
        if (!debuggable) return
        runCatching {
            val chunk = StringBuilder()
            var n = 0
            for (line in file.readText().lines()) {
                val piece = "%04d %s".format(n++, line)
                if (chunk.length + piece.length + 1 > 3000) {
                    android.util.Log.i("PocketPd.Session", chunk.toString())
                    chunk.clear()
                }
                chunk.append(piece).append('\n')
            }
            if (chunk.isNotEmpty()) android.util.Log.i("PocketPd.Session", chunk.toString())
            android.util.Log.i("PocketPd.Session", "== end (${n} lines) ==")
        }
    }

    @Throws(IOException::class)
    fun openPatch(
        file: File,
        displayName: String = file.name,
        retry: Int = 0,
        audio: AudioOpen = AudioOpen.START,
    ) {
        val audioWasRunning = isAudioRunning()
        // pd recompiles the DSP graph ONLY on the dsp on/off edges, so the
        // off→parse→on cycle is what compiles the fresh session (measured
        // 2026-11-06). When audio is already running the cycle is done with
        // PdBase.computeAudio alone: AudioWrapper.start would additionally
        // re-spawn the audio thread and re-open the sink (its avoidClickHack
        // even plays a silent MediaPlayer clip) — that was the loud pop on
        // every obj creation, and it leaked the old thread each time.
        if (audio != AudioOpen.START && audioWasRunning) {
            synchronized(PdBase::class.java) { PdBase.computeAudio(false) }
        }
        closePatch()
        failedClasses = emptySet()
        vuL = 0f
        vuR = 0f
        // libpd opens patches with sys_noloadbang=1; the flag must be ON at
        // the moment pd evaluates the file — audio reinit (PdAudio) resets it
        // between app start and the first open, so re-assert it here (the
        // post-open re-set below cannot retroactively fire the swallowed
        // loadbangs; first-open loadbangs were dead until now)
        // keep desktop loadbang semantics (sys_noloadbang = 0): the canvas
        // loadbang feeds pd-side load-time classes (else/loadbanger,
        // [initmess]); the session carries no vanilla [loadbang] objects
        // (rewritten to [r pocketpd-loadbang] relays, 0.18.29) so nothing
        // app-visible depends on the open-time delivery any more
        PdTweaks.setLoadbangEnabled(true)
        dumpSessionForLogcat(file)
        patchHandle = synchronized(PdBase::class.java) { PdBase.openPatch(file.absolutePath) }
        patchFile = file
        when (audio) {
            AudioOpen.START -> startAudio()
            AudioOpen.RUN ->
                if (audioWasRunning) {
                    synchronized(PdBase::class.java) { PdBase.computeAudio(true) }
                } else {
                    startAudio()
                }
            AudioOpen.SYNC ->
                // with the user's dsp deliberately off the rebuilt session
                // stays frozen until they press the dsp key — forcing audio
                // here made every obj creation audibly flip the switch
                // (user report); signal objects parse fine while dsp is off
                // and the whole graph recompiles at the next dsp-on edge
                if (audioWasRunning) {
                    synchronized(PdBase::class.java) { PdBase.computeAudio(true) }
                } else {
                    log("session rebuilt ($displayName), audio stays off")
                }
            AudioOpen.LOAD ->
                // 0.18.11/12 patch ENTRY: the audio THREAD keeps ticking
                // (flushing the queued slib setups) but the DSP SWITCH stays
                // OFF the whole time — the freshly parsed graph never
                // renders, so opening a patch is silent even for patches
                // that make noise on loadbang. Objects compile at the next
                // dsp-on edge (the RUN entry rebuild re-opens with audio).
                log("patch entry: parsed with dsp off (silent load)")
        }
        if (patchHandle >= 0) {
            log("opened $displayName")
            // Externals that load sibling files (vgm~ demo.vgm, ym2612~ bank
            // *.wopn) resolve them via open_via_path, i.e. the string search
            // path - NOT canvas_open: canvas_getcurrent() in the pd 0.55
            // runtime is a "pd"-receiver binding lookup that goes stale under
            // open/close churn and crashed canvas_path_iterate (0.17.69).
            // Keep the patch's own directory on that path.
            file.parent?.let { dir ->
                if (addedSearchPaths.add(dir)) {
                    runCatching { PdBase.addToSearchPath(dir) }
                }
            }
            // libpd permanently swallows loadbangs for opened patches; the
            // app-side clocks (scope stub, VU taps) start through the
            // pocketpd-scope-go trigger instead. Sent from the poller, not
            // inline: a bang fired inside the open call raced the patch's
            // receiver bindings on some devices. Re-bangs are idempotent —
            // the clock is [r]→[metro], and a stopped metro restarts.
            PdTweaks.setLoadbangEnabled(true)
            pollHandler.removeCallbacks(scopeGoRunner)
            pollHandler.postDelayed(scopeGoRunner, 120)
            pollHandler.postDelayed(scopeGoRunner, 500)
            // pd 0.51.3 boots its scheduler lazily: the version banner and
            // the first DSP ticks land ~3.5 s AFTER openPatch returns, so
            // the 120/500 ms bangs race the boot — state messages sent in
            // that window (preset loads, gate opens) reach the objects but
            // some writes are lost to the booting audio thread (measured
            // 2026-11-06: gate survived, gm/note lost -> near-DC output).
            // These late re-bangs land after the scheduler is live; they
            // are idempotent — a stopped metro restarts, clocks re-sync.
            pollHandler.postDelayed(scopeGoRunner, 4000)
            pollHandler.postDelayed(scopeGoRunner, 6000)
            pollHandler.removeCallbacks(pollRunner)
            pollHandler.post(pollRunner)
            // PdTweaks.nativeLoadCore defers slib setup calls until pd's
            // audio thread is up (~6-8 s after process start); a patch that
            // parses BEFORE that leaves placeholder boxes ("couldn't
            // create") that never join the DSP chain — and the console pair
            // that records the failure can be dropped by the boot flood, so
            // failedClasses alone cannot be trusted to gate the recovery
            // (measured 2026-11-06: ym2612~ placeholder, no failure logged,
            // silent patch forever). Within the process-boot race window
            // (~10 s) re-open ONCE unconditionally, exactly like the
            // pre-0.17.36 settle; later opens (edit/run rebuilds) only
            // re-open on a recorded known-class failure, so run taps do
            // not double-open and freeze widgets (0.17.36's complaint).
            val elapsed = android.os.SystemClock.elapsedRealtime() - processStartElapsedMs
            log("boot-race check: elapsed=${elapsed}ms window=10000ms")
            if (retry == 0 &&
                android.os.SystemClock.elapsedRealtime() - processStartElapsedMs < 10000) {
                pollHandler.postDelayed({
                    if (patchFile == file) {
                        log("slibs settle: reopening $displayName")
                        openPatch(file, displayName, 1, audio)
                    }
                }, 4500)
            } else if (retry < 3 && failedClasses.any { it in PdObjectMeta.knownClasses }) {
                pollHandler.postDelayed({
                    if (patchFile == file && failedClasses.any { it in PdObjectMeta.knownClasses }) {
                        log("slibs settle: reopening $displayName (classes missing)")
                        openPatch(file, displayName, retry + 1, audio)
                    }
                }, 4500)
            }
        } else {
            log("open FAILED: $displayName")
        }
    }

    /** Remembers the class name from a pd "…; couldn't create" line. */
    /** The console line before the current one — pd prints the failing box
     *  text immediately before its "... couldn't create" error, and only
     *  that pairing yields the real class name. */
    private var lastConsoleLine: String? = null

    private fun recordCreateFailure(line: String) {
        val idx = line.indexOf("couldn't create")
        if (idx < 0) return
        // pd 0.51 canvas_objtext prints the failing box text (binbuf_print,
        // e.g. " c_hurdy_eufon" / " gate~ 2") IMMEDIATELY before this error
        // line — pair with it for the real class name. The tail of the error
        // line itself is always the literal "..." (never the name), so the
        // old tail-token parse recorded garbage and the retry gate could
        // never tell a missing user abstraction from a not-yet-loaded slib.
        val name = lastConsoleLine?.trim()
            ?.split(Regex("\\s+"))?.firstOrNull()
            ?: line.substring(0, idx)
                .trim().removePrefix("error:").trim()
                .trimEnd(';', ':', ',', ' ').split(' ', '\t').lastOrNull()?.trim()
        if (!name.isNullOrEmpty() && name !in failedClasses) {
            failedClasses = failedClasses + name
            onFailedClassesChanged?.invoke()
        }
    }

    /**
     * libpd cannot synthesize "clicking" a gui object (desktop Pd emits the
     * value from the object's outlet on click). Touching a widget therefore
     * only reaches the patch through its *receive* symbol. Patches imported
     * from desktop often declare neither send nor receive, leaving widgets
     * dead - so before opening we assign an `auto-*` receive symbol to every
     * gui widget that has neither (in memory only, via a session file).
     *
     * @return true when the document was modified and must be re-serialized
     *   for pd to see the new bindings.
     */
    fun autoBindGui(doc: PatchDocument): Boolean {
        var changed = false
        // every app-widget relay name in the doc, top level and subpatch
        // rows: two clocks/dials sharing a name cross-wire each other fatally
        val seenRelaySyms = HashSet<String>()
        for (obj in doc.objects()) {
            // display/display~: the private receive tag IS the instance
            // identity. A loaded/imported box without one gets its own here,
            // and a tag another display already owns is force-regenerated —
            // no two displays ever share a feed (user: "one display shows
            // one feed, why would they share?")
            val dBase = obj.className().substringAfterLast('/')
            if (dBase == "display" || dBase == "display~") {
                val cur = obj.args.getOrNull(1)
                val mine = cur?.takeIf { it.isNotBlank() && it != "empty" }
                if (mine == null || !seenRelaySyms.add(mine)) {
                    PdObjectMeta.setDisplayTag(obj, "display-rcv-${obj.id.toString(36)}")
                    seenRelaySyms.add(obj.args.getOrNull(1) ?: "")
                    changed = true
                }
                continue
            }
            if (!obj.meta.isGui) continue
            // scope widgets are display-only: no relays, no saved arg noise
            if (obj.className() == "graph~" || obj.className() == "spectrograph~") continue
            if (obj.className() == "euclid") {
                val mine = (7..10).mapNotNull { obj.args.getOrNull(it) }
                    .filter { it.isNotBlank() && it != "-" && it != "empty" }
                val collision = mine.any { !seenRelaySyms.add(it) }
                when {
                    euclidSlotsUnassigned(obj) -> {
                        assignEuclidSymbols(obj, obj.id.toString(36))
                        changed = true
                    }
                    // a clone (duplicate/paste/AI row) kept the source's
                    // relay names: rename from this object's own id
                    collision -> {
                        assignEuclidSymbols(obj, obj.id.toString(36), force = true)
                        changed = true
                    }
                }
                (7..10).forEach { s ->
                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() }?.let { seenRelaySyms.add(it) }
                }
            } else if (obj.className() == "circle5") {
                val mine = listOf(6, 7).mapNotNull { obj.args.getOrNull(it) }
                    .filter { it.isNotBlank() && it != "-" && it != "empty" }
                val collision = mine.any { !seenRelaySyms.add(it) }
                when {
                    circle5SlotsUnassigned(obj) -> {
                        assignCircle5Symbols(obj, obj.id.toString(36))
                        changed = true
                    }
                    collision -> {
                        assignCircle5Symbols(obj, obj.id.toString(36), force = true)
                        changed = true
                    }
                }
                listOf(6, 7).forEach { s ->
                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() }?.let { seenRelaySyms.add(it) }
                }
            } else if (obj.className() == "pad") {
                val mine = listOf(5, 6, 7).mapNotNull { obj.args.getOrNull(it) }
                    .filter { it.isNotBlank() && it != "-" && it != "empty" }
                val collision = mine.any { !seenRelaySyms.add(it) }
                when {
                    padSlotsUnassigned(obj) -> {
                        assignPadSymbols(obj, obj.id.toString(36))
                        changed = true
                    }
                    collision -> {
                        assignPadSymbols(obj, obj.id.toString(36), force = true)
                        changed = true
                    }
                }
                listOf(5, 6, 7).forEach { s ->
                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() && it != "-" }
                        ?.let { seenRelaySyms.add(it) }
                }
            } else if (obj.className() == "button" || obj.className() == "popmenu") {
                // button/popmenu relays are transient (Properties stay
                // blank); only user-typed meta symbols take precedence
                if (obj.meta.sendSymbol.isEmpty() && obj.meta.receiveSymbol.isEmpty() &&
                    obj.autoSend.isEmpty() && obj.autoReceive.isEmpty()
                ) {
                    val uid = obj.id.toString(36)
                    val cls = obj.className()
                    obj.autoSend = "$cls-send-$uid"
                    obj.autoReceive = "$cls-rcv-$uid"
                    changed = true
                }
                if (obj.className() == "popmenu") PdObjectMeta.ensurePopmenuLayout(obj)
            } else if (PdObjectMeta.hasSymbolSlots(obj) &&
                (obj.meta.receiveSymbol.isNotEmpty() || obj.meta.sendSymbol.isNotEmpty())
            ) {
                // named widget (atom/iemgui/keyboard): paste twins keep the
                // source's rcv/send names, so every value lands in all of
                // them. Only APP-GENERATED names get renamed — user-typed
                // duplicates may be intentional linking (desktop pd)
                val cls = obj.className()
                val snd = obj.meta.sendSymbol
                val rcv = obj.meta.receiveSymbol
                val dupSnd = snd.isNotEmpty() && !seenRelaySyms.add(snd) &&
                    PdObjectMeta.isAutoSymbol(cls, snd)
                val dupRcv = rcv.isNotEmpty() && rcv != snd && !seenRelaySyms.add(rcv) &&
                    PdObjectMeta.isAutoSymbol(cls, rcv)
                if (dupSnd || dupRcv) {
                    val uid = obj.id.toString(36)
                    PdObjectMeta.setSymbols(obj, "$cls-send-$uid", "$cls-rcv-$uid")
                    changed = true
                }
            } else if (obj.meta.receiveSymbol.isEmpty() && obj.meta.sendSymbol.isEmpty()) {
                val uid = obj.id.toString(36)
                PdObjectMeta.setSymbols(
                    obj,
                    "auto-${obj.className()}-$uid-send",
                    "auto-${obj.className()}-$uid-rcv",
                )
                changed = true
            }
            // every bound relay name feeds the same seen-set, so paste twins
            // of ANY widget class collide instead of cross-wiring silently
            listOf(obj.meta.sendSymbol, obj.meta.receiveSymbol, obj.autoSend, obj.autoReceive)
                .forEach { if (it.isNotBlank()) seenRelaySyms.add(it) }
            // subpatch widgets live in text lines: assign symbols in place so
            // pd's own objects carry the same names every load (ids replay
            // identically from the same text, keeping the assignment stable)
            obj.subpatchLines?.let { lines ->
                val rewritten = walkSubpatchGuis(lines, bind = false, seenRelaySyms = seenRelaySyms)
                if (rewritten != lines) {
                    obj.subpatchLines = rewritten
                    changed = true
                }
            }
        }
        return changed
    }

    /**
     * Expands graph-on-parent abstraction boxes ([output~] and friends) into
     * app-side display widgets: pd draws the abstraction's own gui objects on
     * the parent box, and so do we. Widget boxes keep original receive/send
     * symbols when the abstraction declares them; unbound ones get
     * deterministic `gop<ord>-<i>` symbols so the same widget binds to the
     * identical pd-side object after the session re-parse (the session is
     * built from `toText()`, which re-derives everything by name+order).
     */
    fun expandGop(doc: PatchDocument) {
        var ord = 0
        for (box in doc.objects()) {
            if (box.kind != ObjKind.OBJECT || box.gop != null) continue
            // graph~/spectrograph~ are app-drawn scope widgets (drawScope);
            // expanding their upstream GOP guts would bury the widget
            if (box.className() == "graph~" || box.className() == "spectrograph~") continue
            // never expand a name pd registers natively: search-path files
            // with builtin names (iemlib's bng/hradio/vradio.pd) are help
            // wrappers, and wrapping them onto the boxes replaces the real
            // widgets on the canvas too
            if (box.className() in PdObjectMeta.pdClasses) continue
            val info = Abstractions.resolve(box.className()) ?: continue
            val coords = info.coords ?: continue
            val widgets = info.gopObjects().mapIndexed { i, w ->
                // absolute parent-space position (pd: box origin + w - margin)
                val copy = PatchObject(
                    newObjectId(), box.x + w.x - info.mx, box.y + w.y - info.my,
                    w.kind, ArrayList(w.args),
                )
                copy.embedded = true
                copy.meta = PdObjectMeta.resolve(copy)
                if (copy.meta.isGui &&
                    copy.meta.receiveSymbol.isEmpty() && copy.meta.sendSymbol.isEmpty()
                ) {
                    if (copy.className() == "euclid") {
                        // deterministic names: the session re-parse re-derives
                        // every gop widget, so id-based fallbacks would drift
                        assignEuclidSymbols(copy, "gop$ord-${i}")
                    } else {
                        PdObjectMeta.setSymbols(copy, "gop$ord-${i}s", "gop$ord-${i}r")
                    }
                }
                copy
            }
            box.gop = com.pocketpd.app.patch.GopEmbed(coords.width, coords.height, info.mx, info.my, widgets)
            ord++
        }
    }

    /**
     * Index of the `#X restore` that closes the `#N canvas` block opening at
     * [open], counting depth so NESTED blocks inside are skipped (0.17.147:
     * the old first-restore scan ended a block at its inner subpatch's close,
     * which leaked the rest of the block to the parent level — injectBlockRelays
     * then scrambled superosc~'s depth-4 nesting into a session with stray
     * `#X restore` lines that popped the root canvas and SEGVed pd's
     * canvas_getargs on the next object). -1 when the block never closes;
     * [until] bounds the scan (exclusive).
     */
    private fun subpatchBlockRestore(lines: List<String>, open: Int, until: Int = lines.size): Int {
        var depth = 1
        for (j in open + 1 until until) {
            val t = lines[j].trimStart()
            if (t.startsWith("#N canvas")) depth++
            else if (t.startsWith("#X restore")) {
                depth--
                if (depth == 0) return j
            }
        }
        return -1
    }

    /** `$0`/`$1…` argument substitution as pd performs it when instantiating. */
    private fun substituteLine(line: String, uid: String, args: List<String>): String {
        val toks = line.trimEnd(';').trim().split(Regex("\\s+")).toMutableList()
        // [args] inside a subpatch block resolves the same way as depth-1
        // boxes (see inlineAbstraction) — one line swaps one line, so the
        // block's #X connect indices stay untouched. Single-group form: the
        // abstraction's creation args as one list message (porres's init
        // subpatches read numeric args; flag breaking stays a depth-1 pass
        // nicety and undocumented edge).
        // else/-prefix strip for COMPILED classes inside [pd] subpatch
        // lines — the same rule the box loop applies (0.17.153), which the
        // string path never had: rc14 superosc~'s [pd freq]/[pd select]/
        // [pd args-initialization] carry else/nchs~/else/select~/
        // else/default/else/route2/else/initmess, and pd knows none of those
        // spellings (no alias, no .pd) — every one of them failed creation
        // and broke the module (0.18.21). File-backed names (else/insert,
        // else/norm~) keep the prefix and inline through their .pd.
        if (toks.size >= 5 && toks[0] == "#X" && toks[1] == "obj" &&
            toks[4].startsWith("else/")
        ) {
            val bare = toks[4].removePrefix("else/")
            if (bare.isNotEmpty() &&
                Abstractions.resolve(toks[4]) == null &&
                Abstractions.resolve(bare) == null &&
                bare in compiledClasses()
            ) toks[4] = bare
        }
        if (toks.size >= 5 && toks[0] == "#X" && toks[1] == "obj" &&
            (toks[4] == "args" || toks[4] == "else/args")
        ) {
            val resolved = substituteTokens(args, uid, emptyList(), false)
            // 0.18.6: the payload is the RAW creation atoms, NOT a "list"-
            // prefixed list. else's [pd args] blocks feed [break -]/[route],
            // whose selector dispatch keys on the atoms themselves (else/
            // tempo: "-on"/"-ms"/"-div" flag atoms) - a "list" first atom
            // rerouted everything into the list branch, left $0-mode unset
            // and every tempo instance ran at a garbage period (0.18.2-0.18.5
            // tempo-help regression). Bare [args] with no creation args keeps
            // the `initmess list` bang-equivalent.
            val body = if (resolved.isEmpty()) listOf("list") else resolved
            val head = substituteTokens(toks.take(4), uid, emptyList(), false)
            return (head + listOf("initmess") + body).joinToString(" ") + ";"
        }
        // MSG lines on this verbatim path get a dedicated minimal handler:
        // upstream text arrives ESCAPED ("\$1" = pd's file encoding of an
        // A_DOLLAR atom bound at send time) and must leave EXACTLY as it
        // came — only $0 and ELSE's dashed-$0 spellings are bound to the
        // inline uid, and the backslash stays so the atom keeps its
        // send-time semantics. The generic substituteTokens strips the
        // escape unconditionally, which turned these into load-time dollars
        // against the env-less session root ("$1: argument number out of
        // range", 0.18.22/23) and broke every loadbang→msg→set-value chain
        // riding an inlined subpatch (0.18.24). Object lines keep the
        // generic path: their dollars resolve against creation args and the
        // escape must go once substituted.
        val literal = toks.getOrNull(0) == "#X" && toks.getOrNull(1) == "msg"
        if (literal) {
            val out = toks.map { tok ->
                var t = tok.replace("$0", uid)
                t = Regex("\\$[1-9](?=-)").replace(t, uid)
                t
            }.joinToString(" ")
            return out + ";"
        }
        return substituteTokens(toks, uid, args, literal).joinToString(" ") + ";"
    }

    /**
     * Mirrors else's args_break output for one [args] box: with no separator
     * argument the creation args are one list message; with a separator
     * character (e.g. the conventional `-`) every symbol starting with it
     * breaks the flow into a selector message (`-mul 2`), and the leading
     * run stays a list. Each group becomes one initmess box: initmess takes
     * the first creation token as its selector, so `initmess list 120 0.75`
     * re-emits the list and `initmess -mul 2` re-emits the selector message,
     * and a bare `initmess list` fires a bang exactly like an empty [args]
     * output reaching [default] via pd's empty-list-to-bang rule.
     */
    private fun argMessageGroups(tokens: List<String>, sep: Char?): List<List<String>> {
        if (tokens.isEmpty()) return listOf(mutableListOf("list"))
        if (sep == null) return listOf(mutableListOf("list") + tokens)
        val groups = ArrayList<MutableList<String>>()
        val pre = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0] == sep) {
                if (pre.isNotEmpty()) {
                    groups.add(mutableListOf("list").apply { addAll(pre) })
                    pre.clear()
                }
                val g = mutableListOf(t)
                i++
                while (i < tokens.size && tokens[i][0] != sep) {
                    g.add(tokens[i])
                    i++
                }
                groups.add(g)
            } else {
                pre.add(t)
                i++
            }
        }
        if (pre.isNotEmpty()) groups.add(mutableListOf("list").apply { addAll(pre) })
        if (groups.isEmpty()) groups.add(mutableListOf("list"))
        return groups
    }

    /**
     * Per-instance delay-line names INSIDE subpatch block lines. pd's delay
     * namespace is one global table: the top-level rows of an inlined
     * abstraction already get the uid prefix, but rows inside its [pd x]
     * blocks went through substituteLine only — two instances of the same
     * abstraction (hurdy2 × c_ks_string, 2026-11-04) then shared ONE delay
     * line (bare "s_delay" in every copy) and cross-wired / collided. A name
     * already carrying this instance's prefix — including one that
     * substituteLine just produced from $0 — is left alone, so each copy's
     * delwrite~/vd~ pair keeps matching within the instance.
     */
    private fun prefixBlockDelayNames(lines: List<String>, uid: String): MutableList<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("#N canvas")) {
                val close = subpatchBlockRestore(lines, i)
                // recurse on the INNER lines only — re-including this level's
                // own header would re-enter the branch forever (the 0.17.25
                // walkSubpatchGuis stack overflow, same shape)
                out.add(line) // header
                if (close >= 0) {
                    out.addAll(prefixBlockDelayNames(lines.subList(i + 1, close), uid))
                    out.add(lines[close]) // restore
                    i = close + 1
                } else {
                    out.addAll(prefixBlockDelayNames(lines.subList(i + 1, lines.size), uid))
                    i = lines.size
                }
                continue
            }
            if (t.startsWith("#X obj")) {
                val toks = t.removeSuffix(";").trim().split(Regex("\\s+"))
                if (toks.size > 5 && toks[4] in DELAY_NAME_CLASSES &&
                    !toks[5].startsWith("$uid-")
                ) {
                    val rewritten = toks.toMutableList()
                    rewritten[5] = "$uid-${toks[5]}"
                    out.add(rewritten.joinToString(" ") + ";")
                    i++
                    continue
                }
            }
            out.add(line)
            i++
        }
        return out
    }

    private fun substituteTokens(
        tokens: List<String>, uid: String, args: List<String>, literalDollars: Boolean,
    ): ArrayList<String> {
        // inside a ";"-message the $-args resolve from the canvas environment
        // at fire time; the inlined session canvas has none, so bind them to
        // the inline uid instead (ELSE spells $0 as $1 there)
        var inSend = false
        val out = ArrayList<String>(tokens.size)
        for (tok0 in tokens) {
            // upstream .pd files escape dollars ("\$0") for pd's file parser;
            // this path feeds BOTH the object serialization (where the
            // session writer escapes msg dollars at write time — escaping
            // here too produced "\\$1" and "bad arguments for message
            // 'size'" on 0.18.22) AND the [pd] subpatch string path via
            // substituteLine (which writes verbatim, so ITS escape handling
            // lives there: keep upstream escapes, re-escape bare dollars).
            var t = tok0.replace("\\$", "$")
            t = t.replace("$0", uid)
            // a dashed $N ("pd-$1-array-cnv", "$1-set bang") is ELSE's
            // spelling of the abstraction's $0 — bind it to the same uid so
            // senders and subcanvas names stay paired
            t = Regex("\\$[1-9](?=-)").replace(t, uid)
            if (literalDollars) {
                if (t == "\\;") inSend = true
                else if (t == "\\,") { /* same send message continues */ }
                else if (inSend) {
                    t = Regex("\\$[1-9]").replace(t, uid)
                }
            } else {
                Regex("\\$(\\d)").findAll(tok0).forEach { m ->
                    val n = m.groupValues[1].toInt()
                    // pd semantics: $1 is the FIRST creation argument — the
                    // args list here is box.args.drop(1), so the index is
                    // n-1. The old getOrNull(n) made a bare [send $1] inside
                    // display.pd resolve to "0" (one creation arg → index 1
                    // miss), which is why 0.17.140's tagged displays never
                    // received anything.
                    val replacement = args.getOrNull(n - 1) ?: "0"
                    t = t.replace(m.value, replacement)
                }
            }
            out.add(t)
        }
        return out
    }

    /**
     * Replaces one abstraction box in the pd-side session with the
     * abstraction's own objects (the pd equivalent of our on-box widgets):
     * arguments/$0 are substituted, the box's iolets become the inlined
     * inlet/outlet objects, and — for GOP boxes — the app-assigned widget
     * symbols ride along so touch input and pd-side updates stay connected.
     * Non-GOP abstractions inline the same way minus the widget overlay;
     * that is what keeps their delay lines per-instance. The saved patch
     * file keeps the plain `[output~]` box.
     */
    private fun inlineAbstraction(
        session: PatchDocument,
        box: PatchObject,
        resolvedInfo: Abstractions.Info? = null,
        creationArgs: List<String>? = null,
    ) {
        val info = resolvedInfo ?: Abstractions.resolve(box.className()) ?: return
        val gop = box.gop
        val uid = (inlineUid++).toString()
        val idMap = HashMap<Long, Long>()
        val newItems = ArrayList<ObjectItem>()
        // [args] boxes replaced by initmess groups: extra groups emit their
        // own box and need the original box's outlet cords duplicated
        val argExtras = HashMap<Long, List<Long>>()
        for (o in info.objects) {
            // block~ configures the enclosing DSP page — an abstraction's
            // own block~ must not leak into the parent canvas we inline into
            if (o.className() == "block~") continue
            if (o.kind == ObjKind.COMMENT) continue
            val copy = PatchObject(
                newObjectId(), o.x, o.y, o.kind,
                substituteTokens(o.args, uid, creationArgs ?: box.args.drop(1), o.kind == ObjKind.MSG),
            )
            // pd's delay-line namespace is one global table per instance:
            // two copies of the same module would cross-wire or collide on
            // a literal delwrite~/delread~/vd~ name ("multiply defined").
            // pd's own remedy is $0-name, which substituteTokens already
            // binds per instance; bare names get the same per-instance
            // prefix here so every inlined copy owns its delay lines.
            if (copy.kind == ObjKind.OBJECT &&
                copy.className() in DELAY_NAME_CLASSES &&
                copy.args.size > 1 && !copy.args[1].startsWith("$uid-")
            ) {
                copy.args[1] = "$uid-${copy.args[1]}"
            }
            copy.subpatchLines = o.subpatchLines?.map { line ->
                substituteLine(line, uid, creationArgs ?: box.args.drop(1))
            }?.let { prefixBlockDelayNames(it, uid) }
            copy.meta = PdObjectMeta.resolve(copy)
            // carry the app-side widget symbols into pd (GOP overlay only)
            val wi = if (gop != null) info.gopObjects().indexOf(o) else -1
            if (gop != null && wi >= 0 && wi < gop.widgets.size && copy.meta.isGui) {
                val dw = gop.widgets[wi]
                PdObjectMeta.setSymbols(copy, dw.meta.sendSymbol, dw.meta.receiveSymbol)
            }
            // [args] resolution (0.17.145): else's [args] is the only slib
            // class calling canvas_getargs, which SEGVs on the session root
            // canvas — libpd-opened files get gl_env == NULL there, and
            // canvas_getenv's bug() branch falls through into a NULL deref
            // (device-probed: fault addr 0xe0 = offsetof(t_canvas, gl_env),
            // stack canvas_obj -> args_new -> canvas_getargs). The parent
            // abstraction's creation args are fully known at inline time,
            // so emit initmess boxes carrying the same load-time output
            // instead — no canvas_getargs anywhere in a session.
            if (copy.kind == ObjKind.OBJECT &&
                (copy.className() == "args" || copy.className() == "else/args")
            ) {
                val sep = o.args.getOrNull(1)?.firstOrNull()
                val resolved = substituteTokens(creationArgs ?: box.args.drop(1), uid, emptyList(), false)
                val groups = argMessageGroups(resolved, sep)
                val extras = ArrayList<Long>()
                var first = true
                for (g in groups) {
                    if (first) {
                        // the FIRST group must also carry the initmess head:
                        // args[0] IS the class name, so replacing the args
                        // with the bare group turned the box into the group's
                        // first token - [args] with creation args "-size 10
                        // 0" emitted `[list -size 10 0]` (unknown function,
                        // creation failure; norm~.pd's bare [args] hit this
                        // via rc14 superosc~'s `else/norm~ -size 10 0`,
                        // 0.18.20). Extra groups below already had the head.
                        copy.args.clear()
                        copy.args.add("initmess")
                        copy.args.addAll(g)
                        copy.meta = PdObjectMeta.resolve(copy)
                        first = false
                    } else {
                        val extra = PatchObject(
                            newObjectId(), o.x, o.y + 26 * extras.size, ObjKind.OBJECT,
                            mutableListOf("initmess").apply { addAll(g) },
                        )
                        extra.meta = PdObjectMeta.resolve(extra)
                        newItems.add(ObjectItem(extra))
                        extras.add(extra.id)
                    }
                }
                if (extras.isNotEmpty()) argExtras[o.id] = extras
            }
            idMap[o.id] = copy.id
            newItems.add(ObjectItem(copy))
        }
        // internal cords, now between inlined copies; parallel initmess
        // groups duplicate the original [args] box's outlet cords
        for (c in info.connections) {
            val from = idMap[c.fromId] ?: continue
            val to = idMap[c.toId] ?: continue
            session.connections.add(PatchConnection(newObjectId(), from, c.outlet, to, c.inlet))
            for (extra in argExtras[c.fromId] ?: emptyList()) {
                session.connections.add(
                    PatchConnection(newObjectId(), extra, c.outlet, to, c.inlet)
                )
            }
        }
        // Parent cords splice THROUGH the inlined port objects: pd's
        // [inlet]/[outlet] objects have no connectable ports of their own
        // (cords into an [inlet~] are rejected outright), so a cord into the
        // box continues from whatever the port's inlet object feeds, and a
        // cord out of the box continues from whatever feeds the port's
        // outlet object.
        val feedTargets = HashMap<Pair<Long, Int>, MutableList<Pair<Long, Int>>>()
        val outSources = HashMap<Pair<Long, Int>, MutableList<Pair<Long, Int>>>()
        for (c in info.connections) {
            val fromObj = info.objects.firstOrNull { it.id == c.fromId } ?: continue
            val toObj = info.objects.firstOrNull { it.id == c.toId } ?: continue
            if (fromObj.className() == "inlet" || fromObj.className() == "inlet~") {
                feedTargets.getOrPut(c.fromId to c.outlet) { mutableListOf() }.add(c.toId to c.inlet)
            }
            if (toObj.className() == "outlet" || toObj.className() == "outlet~") {
                outSources.getOrPut(c.toId to c.inlet) { mutableListOf() }.add(c.fromId to c.outlet)
            }
        }
        val spliced = mutableListOf<PatchConnection>()
        for (c in session.connections.toList()) {
            if (c.fromId == box.id) {
                val srcs = info.outlets.getOrNull(c.outlet)?.id
                    ?.let { outSources[it to 0] }
                if (srcs != null) {
                    for ((srcId, srcOut) in srcs) {
                        idMap[srcId]?.let { copied ->
                            spliced.add(PatchConnection(newObjectId(), copied, srcOut, c.toId, c.inlet))
                        }
                    }
                }
                session.connections.remove(c)
                continue
            }
            if (c.toId == box.id) {
                val dsts = info.inlets.getOrNull(c.inlet)?.id
                    ?.let { feedTargets[it to 0] }
                if (dsts != null) {
                    for ((dstId, dstIn) in dsts) {
                        idMap[dstId]?.let { copied ->
                            spliced.add(PatchConnection(newObjectId(), c.fromId, c.outlet, copied, dstIn))
                        }
                    }
                }
                session.connections.remove(c)
                continue
            }
        }
        session.connections.addAll(spliced)
        // splice inlined objects where the box was (keeps file-order stable)
        val at = session.items.indexOfFirst { it is ObjectItem && it.obj.id == box.id }
        if (at >= 0) session.items.removeAt(at)
        for ((k, item) in newItems.withIndex()) session.items.add(at + k, item)
    }

    private var inlineUid = 1001

    /** Bare names of every compiled class the slibs cores register, read from
     *  the extracted core_classes.txt lists (0.17.153: powers the else/ prefix
     *  strip — upstream files say `else/knob`, our cores register `knob`). */
    private fun compiledClasses(): Set<String> {
        cachedCompiledClasses?.let { return it }
        val set = HashSet<String>()
        val ctx = appContext
        if (ctx != null) {
            val root = java.io.File(ctx.filesDir, "slibs")
            root.listFiles { f -> f.isDirectory }?.forEach { lib ->
                val list = java.io.File(lib, "core_classes.txt")
                runCatching {
                    list.readLines().forEach { line ->
                        line.trim().takeIf { it.isNotEmpty() }?.let { set.add(it) }
                    }
                }
            }
        }
        cachedCompiledClasses = set
        return set
    }

    private var cachedCompiledClasses: Set<String>? = null

    /** elapsedRealtime at [init] — the pd boot-race window is measured
     *  from here (slib setups defer until pd's audio thread is up). */
    private var processStartElapsedMs = 0L

    /**
     * ELSE's graph~/spectrograph~ render through fragile GOP machinery in pd
     * (runtime `graph`/`array` messages that misparse on pd 0.51 and end in
     * heap corruption at canvas_free). PocketPd never draws pd GOPs anyway —
     * the canvas widget is ours and reads a pd array straight from pd — so
     * the session gets a minimal stub instead of the upstream guts: signal
     * pass-through plus a continuously written array named after the doc
     * box id, which the app-side poller reads directly.
     */
    /** Spectrograph~ analysis window: the stub records this many waveform
     *  samples; the magnitudes are computed app-side with a hann window
     *  (libpd's block~ subpatches glitch at block boundaries in 1.2.1-rc6,
     *  and the un-windowed 64-block rfft~ leaks a sine's energy into every
     *  bin — both were observed on screen). */
    const val SCOPE_FFT_BLOCK = 1024

    private fun inlineScopeStub(session: PatchDocument, box: PatchObject, uid: String) {
        val isSpectrum = box.className() == "spectrograph~"
        // both scope kinds record the raw signal with a metro-retriggered
        // tabwrite~ (proven path); spectrograph~'s spectrum is computed
        // app-side from the waveform (hann window + FFT in PatchCanvasView)
        val size = if (isSpectrum) SCOPE_FFT_BLOCK
        else box.args.getOrNull(1)?.toIntOrNull()?.coerceIn(64, 2048) ?: 256
        val array = "$uid-wave"
        fun obj(y: Int, args: List<String>): PatchObject {
            val o = PatchObject(newObjectId(), -6000, y, ObjKind.OBJECT, mutableListOf(*args.toTypedArray()))
            session.items.add(ObjectItem(o))
            return o
        }
        val snd = obj(0, listOf("s~", "$uid-sig"))
        val rcv = obj(-100, listOf("r~", "$uid-sig"))
        obj(-150, listOf("table", array, "$size"))
        // the APP clocks the capture: it bangs <uid>-tw and reads the array
        // ~30 ms later (the 1024-sample fill spans 16 dsp blocks ≈ 21 ms).
        // A metro-retiggered tabwrite~ gets torn by the poller mid-refill —
        // old-window-tail + new-window-head reads splash broadband junk into
        // the spectrum on every unlucky frame.
        val tab = obj(-200, listOf("tabwrite~", array))
        val tw = obj(-250, listOf("r", "$uid-tw"))
        session.connections.add(PatchConnection(newObjectId(), rcv.id, 0, tab.id, 0))
        session.connections.add(PatchConnection(newObjectId(), tw.id, 0, tab.id, 0))
        // box cords move onto the pass-through pair
        for (c in session.connections.toList()) {
            if (c.toId == box.id) { c.toId = snd.id; c.inlet = 0 }
            if (c.fromId == box.id) { c.fromId = rcv.id; c.outlet = 0 }
        }
        session.items.removeAll { it is ObjectItem && it.obj.id == box.id }
    }

    /**
     * Builds the pd-side session document for interactive play: widgets in pd
     * do not output values received on their receive symbol, so direct
     * widget→object cords stay dead when the value comes from the touch UI.
     * Every widget connection is re-routed through hidden pass-through objects:
     *
     *     X → widget   becomes   X → [s widget-rcv]
     *     widget → Y   becomes   [r widget-snd] → Y
     *
     * The touch UI then sends values to the snd symbol (reaching Y) and the
     * rcv symbol (updating the widget), and pd-side updates flow back out
     * through the rcv symbol into the app. The on-disk patch is untouched.
     */
    fun buildInteractiveSession(doc: PatchDocument, errors: MutableList<String>): PatchDocument {
        val session = PatchDocument.parse(doc.toText(), errors)
        // app-only widgets (button) carry their relay names in transient
        // fields on the DOC objects; the parse renumbers ids, so stamp the
        // same names onto the session twins — doc and session objects
        // correspond 1:1 by file order right after a toText round trip
        run {
            val dObjs = doc.objects()
            val sObjs = session.objects()
            for (i in 0 until minOf(dObjs.size, sObjs.size)) {
                val d = dObjs[i]
                val s = sObjs[i]
                // alignment guard: a class mismatch means the 1:1 assumption
                // broke — skip stamping rather than cross-wire widgets
                if (d.args.firstOrNull() != s.args.firstOrNull()) break
                if (s.autoSend.isEmpty() && s.autoReceive.isEmpty()) {
                    s.autoSend = d.autoSend
                    s.autoReceive = d.autoReceive
                }
            }
        }
        expandGop(session)
        // scope stubs name their array after the ORIGINAL doc box id — the
        // session copy renumbers on parse, but the app-side poller reads the
        // document's ids
        val scopeBoxIds = doc.objects()
            .filter { it.className() == "graph~" || it.className() == "spectrograph~" }
            .map { it.id }
        var scopeIdx = 0
        // Inline pass to a FIXPOINT (0.17.149): the old single-snapshot pass
        // never examined anything the inliner itself created — superosc~'s
        // [clone -d else/superosc.unit 1] is added BY the superosc~ inlining,
        // so the loop never saw it, the box reached pd natively (whose clone
        // rejects -d) and superosc~ stayed voiceless. Repeat over newly added
        // objects; inlineBudget guards self-referential abstractions (pd has
        // the same guard via pd_setloadingabstraction). Each nested inline
        // keeps its own uid, so per-instance $0/delay/send namespaces stay
        // separated exactly as before.
        var inlineBudget = 256
        val processed = HashSet<Long>()
        var pass = session.objects().filter { it.id !in processed }
        while (pass.isNotEmpty()) {
            for (box in pass) {
                processed.add(box.id)
                if (box.className() == "graph~" || box.className() == "spectrograph~") {
                    // upstream GOP guts corrupt pd 0.51
                    val uid = "w" + scopeBoxIds.getOrElse(scopeIdx++) { box.id }
                    inlineScopeStub(session, box, uid)
                    continue
                }
                // ELSE-prefixed compiled classes (0.17.153): upstream files
                // reference `else/knob` etc. for classes our cores register
                // BARE. When neither `else/<name>` nor `<name>` resolves as an
                // abstraction file and <name> is a known compiled class, strip
                // the prefix so pd finds it (the knob GOP then creates; its
                // visual operation depends on the canvas widget support).
                if (box.className().startsWith("else/") && box.gop == null) {
                    val bare = box.className().removePrefix("else/")
                    if (bare.isNotEmpty() &&
                        Abstractions.resolve(box.className()) == null &&
                        Abstractions.resolve(bare) == null &&
                        bare in compiledClasses()
                    ) {
                        box.args[0] = bare
                        box.meta = PdObjectMeta.resolve(box)
                    }
                }
                // [clone] UPGRADED TO NATIVE (0.18.19, user-approved decision
                // 4): the voice==1 inline workaround (0.17.147) existed because
                // the pd 0.51.3 clone predates -d/-x/-di/-do flag parsing
                // (`clone -d <name> 1`, ELSE superosc~'s form, hit its usage
                // error). pd 0.57's clone parses every flag natively, so ALL
                // clone boxes now pass through untouched and pd instantiates
                // the file itself: file references resolve against the session
                // canvas dir (== the patch dir, the session is written as
                // .session-<name> next to the document) and library forms like
                // `else/superosc.unit` against the registered slib dirs;
                // [args] inside the clone file works through pd's own canvas
                // environment (no app-side initmess conversion needed). "clone"
                // sits in PdObjectMeta.pdClasses, so the generic inliner below
                // skips it.
                // every resolvable abstraction inlines per instance (GOP or
                // not): pd's delay-line and send/receive namespaces are global
                // per instance, so two native copies of the same module would
                // cross-wire a literal delwrite~/vd~ name. EXCEPT names pd
                // registers natively: [bng]/[hradio]/[vradio] collide with
                // iemlib's help-wrapper .pd files — inlining them replaced
                // every such widget with the help patch's guts
                if ((box.gop != null || Abstractions.resolve(box.className()) != null) &&
                    box.className() !in PdObjectMeta.pdClasses &&
                    inlineBudget > 0
                ) {
                    inlineBudget--
                    inlineAbstraction(session, box)
                } else if (box.className().startsWith("else/") ||
                    box.className().startsWith("cyclone/") ||
                    box.className().startsWith("zexy/")
                ) {
                    // 0.18.5 diagnostic: lib-prefixed boxes the pipeline left
                    // native. A lib-prefixed name either resolves as a pd
                    // binary class (symlink farm - fine) or falls to pd's file
                    // loader, where subpatch-level loadbang gaps (0.15.29)
                    // break [args]-carrying abstractions like else/tempo.
                    // Log which factor blocked the inline.
                    android.util.Log.w(
                        "PocketPd.Pd",
                        "inline skipped: ${box.className()} " +
                            "resolve=${Abstractions.resolve(box.className()) != null} " +
                            "pdClass=${box.className() in PdObjectMeta.pdClasses} " +
                            "gop=${box.gop != null} budget=$inlineBudget",
                    )
                }
            }
            pass = session.objects().filter { it.id !in processed }
        }
        // [euclid]/[circle5] are app-only (pd has no such class): every
        // control cord gets its own relay — euclid inlets 0/1 → [s
        // ..-in0/in1], outlets 0/1 → [r ..-out0/out1]; circle5's single
        // inlet/outlet → [s ..-in0]/[r ..-out0] — and the boxes themselves
        // are dropped further below. Cords touching a SIGNAL port cannot
        // cross into control relays; they are dropped with the box (an
        // audio-rate trigger into a step clock was never meaningful, and
        // pd would reject the rewrite).
        val appDeadCords = ArrayList<PatchConnection>()
        var appDropNote = false
        for (c in session.connections.toList()) {
            val from = session.objectById(c.fromId)
            val to = session.objectById(c.toId)
            val fromEuclid = from?.className() == "euclid"
            val toEuclid = to?.className() == "euclid"
            val fromC5 = from?.className() == "circle5"
            val toC5 = to?.className() == "circle5"
            val fromPad = from?.className() == "pad"
            val toPad = to?.className() == "pad"
            if (fromEuclid || toEuclid || fromC5 || toC5 || fromPad || toPad) {
                val fromSig = from != null && c.outlet in from.meta.signalOutlets
                val toSig = to != null && c.inlet in to.meta.signalInlets
                val badPort = (fromEuclid && c.outlet !in 0..1) ||
                    (toEuclid && c.inlet !in 0..1) ||
                    (fromC5 && c.outlet != 0) || (toC5 && c.inlet != 0) ||
                    (fromPad && c.outlet !in 0..1) || (toPad && c.inlet != 0)
                if (fromSig || toSig || badPort) {
                    appDeadCords.add(c)
                    if (!appDropNote) {
                        appDropNote = true
                        log("session: signal cord to an app widget dropped (control-domain)")
                    }
                    continue
                }
                if (fromC5) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("r", circle5OutSym(from!!)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.fromId = relay.id
                    c.outlet = 0
                }
                if (toC5) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("s", circle5InSym(to!!)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.toId = relay.id
                    c.inlet = 0
                }
                if (fromEuclid) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("r", euclidOutSym(from!!, c.outlet)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.fromId = relay.id
                    c.outlet = 0
                }
                if (fromPad) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("r", padOutSym(from!!, c.outlet)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.fromId = relay.id
                    c.outlet = 0
                }
                if (toPad) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("s", padInSym(to!!)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.toId = relay.id
                    c.inlet = 0
                }
                if (toEuclid) {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT,
                        mutableListOf("s", euclidInSym(to!!, c.inlet)),
                    )
                    session.items.add(ObjectItem(relay))
                    c.toId = relay.id
                    c.inlet = 0
                }
                // NO continue here: the endpoints are no longer app widgets,
                // so the generic pass below still rewrites the OTHER side — a
                // cord bng→euclid must become [r bng-send]→[s ..-in0] (the
                // tap bang travels via the bng's send symbol), and euclid→atom
                // needs the atom's [s rcv] echo leg for display sync.
                // Skipping that leg is how 0.16.0 dead-wired both directions.
            }
            // relays carry touch values, which are control-domain — a cord
            // attached to a signal port is audio and must keep its original
            // endpoints (rewriting it into [s]/[r] would make pd reject the
            // whole cord: "signal outlet connect to nonsignal inlet")
            val fromSnd = from?.let {
                effSnd(it).takeIf { s ->
                    s.isNotEmpty() && it.meta.isGui && c.outlet !in it.meta.signalOutlets
                }
            }
            val toRcv = to?.let {
                effRcv(it).takeIf { s ->
                    s.isNotEmpty() && it.meta.isGui && c.inlet !in it.meta.signalInlets
                }
            }
            if (fromSnd != null) {
                val relay = PatchObject(
                    newObjectId(), 0, 0, ObjKind.OBJECT, mutableListOf("r", fromSnd)
                )
                session.items.add(ObjectItem(relay))
                c.fromId = relay.id
                c.outlet = 0
            }
            if (toRcv != null) {
                val relay = PatchObject(
                    newObjectId(), 0, 0, ObjKind.OBJECT, mutableListOf("s", toRcv)
                )
                session.items.add(ObjectItem(relay))
                c.toId = relay.id
                c.inlet = 0
            }
        }
        if (appDeadCords.isNotEmpty()) {
            session.connections.removeAll(appDeadCords.toSet())
        }
        // message boxes: pd cannot be "clicked" remotely, so give every msg an
        // invisible [r pmsg-N] feeding its inlet; the touch UI injects the msg
        // content there and pd fires it with full message semantics. The
        // ordinal space is GLOBAL — document order, depth-first into subpatch
        // blocks — and shared with the tap path (EditorActivity.onMsgTriggered
        // via levelMsgOffset), so a relay for a msg inside a block is injected
        // into that block's own lines and numbers identically from both sides.
        var msgOrd = 0
        fun injectBlockRelays(lines: List<String>): MutableList<String> {
            if (lines.isEmpty()) return ArrayList(lines)
            var start = 0
            if (lines[0].trimStart().startsWith("#N canvas")) start = 1
            var end = lines.size
            if (end > start && lines[end - 1].trimStart().startsWith("#X restore")) end--
            val body = ArrayList<String>(end - start)
            val msgSlots = ArrayList<Pair<Int, Int>>() // object index -> pmsg ordinal
            var idx = 0 // pd file-order object index within this block
            var i = start
            while (i < end) {
                val line = lines[i]
                val t = line.trimStart()
                if (t.startsWith("#N canvas")) {
                    // nested block: recurse (its relays live inside it), and
                    // it occupies ONE object slot in this block's index space.
                    // The close is found DEPTH-AWARE (0.17.147): the old
                    // first-restore scan ended this block at its inner
                    // subpatch's close, leaking the rest of the block to the
                    // parent level and scrambling superosc~-deep nesting into
                    // a session with stray restores (root canvas popped,
                    // pd canvas_getargs SEGV on the next object).
                    val close = subpatchBlockRestore(lines, i, end)
                    if (close >= 0) {
                        body.addAll(injectBlockRelays(lines.subList(i, close + 1)))
                        idx++
                        i = close + 1
                        continue
                    }
                }
                body.add(line)
                val isObj = t.startsWith("#X obj") || t.startsWith("#X msg") ||
                    t.startsWith("#X text") || t.startsWith("#X floatatom") ||
                    t.startsWith("#X symbolatom") || t.startsWith("#X listbox")
                if (isObj) {
                    // ordinal assigned AT SCAN TIME so every level numbers in
                    // document order — the doc-side tap path (onMsgTriggered /
                    // levelMsgOffset) counts the same way and stays aligned
                    if (t.startsWith("#X msg")) msgSlots.add(idx to msgOrd++)
                    idx++
                }
                i++
            }
            val out = ArrayList<String>(lines.size + msgSlots.size * 2)
            out.addAll(lines.subList(0, start))
            out.addAll(body)
            var relayIdx = idx
            for ((m, ord) in msgSlots) {
                out.add("#X obj 0 0 r pmsg-$ord;")
                out.add("#X connect $relayIdx 0 $m 0;")
                relayIdx++
            }
            out.addAll(lines.subList(end, lines.size))
            return out
        }
        val deadButtons = ArrayList<Long>()
        val deadEuclids = ArrayList<Long>()
        val deadCircle5s = ArrayList<Long>()
        val deadPads = ArrayList<Long>()
        for (obj in session.objects()) {
            // app-only markers must never reach pd's iemgui parser
            obj.args.removeAll { it == PdObjectMeta.INT_MARKER }
            // [random] with a missing or <1 range outputs a constant 0 (pd
            // clamps the range to 1) and generated patches keep tripping
            // over it; give the session copy a usable range — the saved
            // file stays as written
            if (obj.kind == ObjKind.OBJECT && obj.className() == "random" &&
                (obj.args.size < 2 || (obj.args[1].toFloatOrNull() ?: 0f) < 1f)
            ) {
                while (obj.args.size < 2) obj.args.add("0")
                obj.args[1] = "128"
                log("session: [random] without a usable range defaulted to 128")
            }
            when {
                // [button] is an app-only widget (press sends 1, release
                // sends 0); its outlet cords were rerouted through [r <send>]
                // above, so pd never needs the box — and pd has no such
                // class anyway. [popmenu] rides the same app-widget relays.
                obj.className() == "button" || obj.className() == "popmenu" ->
                    deadButtons.add(obj.id)
                // [euclid]: cords already rerouted through relays above —
                // pd must never try to instantiate the class
                obj.className() == "euclid" -> deadEuclids.add(obj.id)
                obj.className() == "circle5" -> deadCircle5s.add(obj.id)
                // [pad]: in/outlet cords already rerouted through the three
                // relay symbols above — pd must never see the box
                obj.className() == "pad" -> deadPads.add(obj.id)
                obj.kind == ObjKind.MSG -> {
                    val relay = PatchObject(
                        newObjectId(), 0, 0, ObjKind.OBJECT, mutableListOf("r", "pmsg-$msgOrd")
                    )
                    session.items.add(ObjectItem(relay))
                    session.connections.add(
                        PatchConnection(newObjectId(), relay.id, 0, obj.id, 0)
                    )
                    msgOrd++
                }
                obj.subpatchLines != null ->
                    obj.subpatchLines = injectBlockRelays(
                        rewriteBlockEuclids(obj.subpatchLines!!)
                    )
            }
        }
        if (deadButtons.isNotEmpty()) {
            session.items.removeAll { it is ObjectItem && it.obj.id in deadButtons }
            session.connections.removeAll {
                it.fromId in deadButtons || it.toId in deadButtons
            }
        }
        if (deadEuclids.isNotEmpty()) {
            session.items.removeAll { it is ObjectItem && it.obj.id in deadEuclids }
            session.connections.removeAll {
                it.fromId in deadEuclids || it.toId in deadEuclids
            }
            log("session: ${deadEuclids.size} [euclid] widget(s) wired through app relays")
        }
        if (deadCircle5s.isNotEmpty()) {
            session.items.removeAll { it is ObjectItem && it.obj.id in deadCircle5s }
            session.connections.removeAll {
                it.fromId in deadCircle5s || it.toId in deadCircle5s
            }
            log("session: ${deadCircle5s.size} [circle5] dial(s) wired through app relays")
        }
        if (deadPads.isNotEmpty()) {
            session.items.removeAll { it is ObjectItem && it.obj.id in deadPads }
            session.connections.removeAll {
                it.fromId in deadPads || it.toId in deadPads
            }
            log("session: ${deadPads.size} [pad] widget(s) wired through app relays")
        }
        // VU metering: duplicate every dac~ feed into per-channel abs~ →
        // lop~ → snapshot~ taps so the VU strip reads real output without
        // any patch cooperation. (env~ is shadowed by cyclone in our lib and
        // emits 1/0 digital values — vanilla chain only.) A channel tap only
        // exists when that dac inlet is actually fed — an unfed tap would
        // stream zeros and fight the patch's own sends. Parked far
        // off-canvas; the on-disk patch is never touched.
        fun meterObj(y: Int, args: List<String>) = PatchObject(
            newObjectId(), -6000, y, ObjKind.OBJECT, mutableListOf(*args.toTypedArray())
        )
        // snapshot~ samples on bangs, so the tap carries its own clock
        // (loadbang fires via openPatch's canvas loadbang message)
        fun buildTap(y: Int, recv: String): List<PatchObject> = listOf(
            meterObj(y, listOf("abs~")),
            meterObj(y - 100, listOf("lop~", "300")),
            meterObj(y - 200, listOf("snapshot~")),
            meterObj(y - 300, listOf("*", "15")),
            meterObj(y - 400, listOf("clip", "0", "1")),
            meterObj(y - 500, listOf("s", recv)),
            meterObj(y - 600, listOf("r", "pocketpd-scope-go")),
            meterObj(y - 700, listOf("metro", "100")),
        )
        val dacFeeds = session.connections.filter { c ->
            val to = session.objectById(c.toId)
            to?.kind == ObjKind.OBJECT && to.className() == "dac~"
        }
        val tapL = if (dacFeeds.any { it.inlet % 2 == 0 })
            buildTap(-6000, "pocketpd-vu-l") else null
        val tapR = if (dacFeeds.any { it.inlet % 2 == 1 })
            buildTap(-5300, "pocketpd-vu-r") else null
        for (o in (tapL.orEmpty() + tapR.orEmpty())) session.items.add(ObjectItem(o))
        for (tap in listOfNotNull(tapL, tapR)) {
            for (i in 0 until 5) {
                session.connections.add(
                    PatchConnection(newObjectId(), tap[i].id, 0, tap[i + 1].id, 0)
                )
            }
            // loadbang starts the metro; the metro bangs the snapshot
            session.connections.add(
                PatchConnection(newObjectId(), tap[6].id, 0, tap[7].id, 0)
            )
            session.connections.add(
                PatchConnection(newObjectId(), tap[7].id, 0, tap[2].id, 0)
            )
        }
        for (c in dacFeeds) {
            val tap = if (c.inlet % 2 == 0) tapL else tapR
            if (tap != null) session.connections.add(
                PatchConnection(newObjectId(), c.fromId, c.outlet, tap.first().id, 0)
            )
        }
        // [loadbang] -> [r pocketpd-loadbang] (0.18.29): pd delivers the
        // canvas loadbang DURING openPatch — openPatch closes the previous
        // session first (closePatch unsubscribes every widget send symbol)
        // and the caller's post-open bindObjects re-arms them only after
        // openPatch returns, so loadbang-set widget values landed inside
        // the unarmed window and never reached the UI (0.18.28 forensics:
        // print fired at every reopen, the app trace never did; a 500ms
        // pipe made delivery survive, proving the instant was the only
        // problem). The session now carries [r pocketpd-loadbang]
        // receivers; the app bangs the symbol once AFTER the open and the
        // re-armed binds (see the callers), so the chains deliver exactly
        // once, fully sequenced. else/loadbanger and [initmess] keep their
        // pd-side canvas loadbang (sys_noloadbang stays 0) — their targets
        // are else widgets, invisible to the app.
        fun relayizeLoadbang(obj: PatchObject) {
            if (obj.className() == "loadbang") {
                obj.args.clear()
                obj.args.add("r")
                obj.args.add("pocketpd-loadbang")
                obj.meta = PdObjectMeta.resolve(obj)
            }
            for (gw in obj.gop?.widgets ?: emptyList()) relayizeLoadbang(gw)
            obj.subpatchLines?.let { lines ->
                for ((i, line) in lines.withIndex()) {
                    val toks = line.trimEnd(';').trim().split(Regex("\\s+")).toMutableList()
                    if (toks.size >= 5 && toks[0] == "#X" && toks[1] == "obj" && toks[4] == "loadbang") {
                        toks[4] = "r"
                        toks.add(5, "pocketpd-loadbang")
                        lines[i] = toks.joinToString(" ") + ";"
                    }
                }
            }
        }
        for (o in session.objects()) relayizeLoadbang(o)
        return session
    }

    /**
     * Rewrites the [euclid] boxes inside subpatch block text into control
     * relays: the class cannot enter pd, so every cord touching a euclid
     * row is re-pointed at [s]/[r] rows appended to the block and the euclid
     * rows themselves are removed (connect indices remapped). Walks exactly
     * like [injectBlockRelays]: same object index space, nested blocks
     * recurse and occupy one slot of the parent. Relay symbols come from the
     * row's assigned slots — [autoBindGui] ran first, so they are present;
     * the coord-based fallback keeps even unassigned rows deterministic.
     */
    private fun rewriteBlockEuclids(lines: List<String>): MutableList<String> {
        if (lines.none { it.trimStart().startsWith("#X obj") && it.contains(" euclid") }) {
            return ArrayList(lines)
        }
        var start = 0
        if (lines[0].trimStart().startsWith("#N canvas")) start = 1
        var end = lines.size
        if (end > start && lines[end - 1].trimStart().startsWith("#X restore")) end--
        data class Eu(val lineIdx: Int, val idx: Int, val syms: List<String>)
        val euclids = ArrayList<Eu>()
        val out = ArrayList<String>(lines.size)
        val connects = ArrayList<Pair<Int, String>>() // original line index -> text
        var idx = 0
        var i = start
        while (i < end) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("#N canvas")) {
                val close = subpatchBlockRestore(lines, i, end)
                if (close >= 0) {
                    out.addAll(rewriteBlockEuclids(lines.subList(i, close + 1)))
                    idx++
                    i = close + 1
                    continue
                }
            }
            val isObj = t.startsWith("#X obj") || t.startsWith("#X msg") ||
                t.startsWith("#X text") || t.startsWith("#X floatatom") ||
                t.startsWith("#X symbolatom") || t.startsWith("#X listbox")
            if (isObj) {
                if (t.startsWith("#X obj")) {
                    val toks = t.removeSuffix(";").trim().split(Regex("\\s+"))
                    if (toks.size >= 5 && toks[4] == "euclid") {
                        // slots at toks 11..14 (after x y euclid hits steps
                        // rotation width height interactive)
                        val uid = "c${toks[2]}_${toks[3]}"
                        val syms = (0..3).map { s ->
                            toks.getOrNull(11 + s)?.takeIf { it != "-" && it != "empty" }
                                ?: "auto-euclid-$uid-" +
                                (if (s < 2) "in$s" else "out${s - 2}")
                        }
                        euclids.add(Eu(i, idx, syms))
                        idx++
                        i++
                        continue
                    }
                }
                idx++
            } else if (t.startsWith("#X connect")) {
                connects.add(i to t)
                i++
                continue
            }
            out.add(line)
            i++
        }
        if (euclids.isEmpty()) return ArrayList(lines)
        val newCount = idx - euclids.size
        val euOld = euclids.map { it.idx }.toHashSet()
        fun remapPlain(old: Int): Int {
            var shift = 0
            for (e in euclids) if (e.idx < old) shift++
            return old - shift
        }
        fun relayIdx(eu: Eu, port: Int, source: Boolean): Int? {
            if (port !in 0..1) return null
            val slot = if (source) 2 + port else port
            if (eu.syms[slot].isBlank()) return null
            return newCount + euclids.indexOf(eu) * 4 + slot
        }
        val relays = ArrayList<String>(euclids.size * 4)
        for (eu in euclids) {
            relays.add("#X obj 0 0 s ${eu.syms[0]};")
            relays.add("#X obj 0 0 s ${eu.syms[1]};")
            relays.add("#X obj 0 0 r ${eu.syms[2]};")
            relays.add("#X obj 0 0 r ${eu.syms[3]};")
        }
        var dropped = 0
        val rewritten = ArrayList<String>(connects.size)
        for ((_, t) in connects) {
            val m = Regex("^#X connect (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+);?$").find(t)
            if (m == null) { rewritten.add(t); continue }
            val (aTok, pTok, bTok, qTok) = m.destructured
            val ai = aTok.toInt(); val bi = bTok.toInt()
            fun mapEndpoint(old: Int, port: Int, source: Boolean): Int? {
                val eu = euclids.firstOrNull { it.idx == old }
                return when {
                    eu != null -> relayIdx(eu, port, source)
                    old in 0 until idx && old !in euOld -> remapPlain(old)
                    else -> null
                }
            }
            val na = mapEndpoint(ai, pTok.toInt(), source = true)
            val nb = mapEndpoint(bi, qTok.toInt(), source = false)
            if (na == null || nb == null) { dropped++; continue }
            rewritten.add("#X connect $na $pTok $nb $qTok;")
        }
        if (dropped > 0) {
            log("session: $dropped cord(s) past [euclid]'s two in/outlets dropped")
        }
        out.addAll(relays)
        out.addAll(rewritten)
        out.addAll(lines.subList(end, lines.size))
        return out
    }

    /**
     * Number of msg boxes that precede the level opened at [anchorObjId] in
     * the global pmsg ordinal space (document order, depth-first into
     * subpatch blocks): msgs before the anchor in [doc] plus the full inner
     * msg count of every subpatch block positioned before it. 0 when the
     * anchor is not found (root level). Walks identically to the relay
     * assignment in [buildInteractiveSession], so tap ordinals stay aligned
     * with the pd-side relays.
     */
    fun levelMsgOffset(doc: PatchDocument, anchorObjId: Long): Int {
        var n = 0
        fun innerCount(lines: List<String>): Int {
            var c = 0
            var i = 0
            while (i < lines.size) {
                val t = lines[i].trimStart()
                if (t.startsWith("#N canvas")) {
                    val close = subpatchBlockRestore(lines, i)
                    if (close >= 0) {
                        c += innerCount(lines.subList(i + 1, close))
                        i = close + 1
                        continue
                    }
                }
                if (t.startsWith("#X msg")) c++
                i++
            }
            return c
        }
        for (o in doc.objects()) {
            if (o.id == anchorObjId) return n
            if (o.kind == ObjKind.MSG) n++
            o.subpatchLines?.let { n += innerCount(it) }
        }
        return 0
    }

    /** Live array op from the properties dialog: pd binds every garray's
     *  name as a receiver, so a resize reaches the running array directly
     *  ([; name resize N() — no run-mode round trip. A renamed table can't
     *  move live (pd has no rename); it refreshes on the next run entry. */
    fun resizeArray(name: String, size: Int) {
        runCatching { synchronized(PdBase::class.java) { PdBase.sendMessage(name, "resize", size.toFloat()) } }
            .onFailure { log("resize $name failed: ${it.message}") }
        log("resize $name -> $size")
    }

    /**
     * Fires a message box from the touch UI by injecting its content into the
     * pd-side [r pmsg-N] relay wired to that box's inlet.
     */
    fun triggerMsg(obj: PatchObject, ordinal: Int) {
        val receiver = "pmsg-$ordinal"
        val atoms: List<Any> = obj.args.map { tok ->
            val text = PatchObject.displayEscape(tok)
            text.toFloatOrNull() ?: text
        }
        val ok = if (atoms.isEmpty()) {
            sendBang(receiver)
        } else {
            sendList(receiver, atoms)
        }
        if (!ok) log("msg trigger dropped: ${obj.args.joinToString(" ")}")
        else log("msg trigger: ${atoms.joinToString(" ")} -> $receiver")
    }

    fun closePatch() {
        if (patchHandle < 0) return
        pollHandler.removeCallbacks(pollRunner)
        // app-reserved symbols (VU metering) must survive patch reloads —
        // entering run mode reopens the session, and the VU would go deaf
        for (sym in subscribedSymbols.toList()) {
            if (sym in permanentSymbols) continue
            synchronized(PdBase::class.java) { PdBase.unsubscribe(sym) }
            subscribedSymbols.remove(sym)
        }
        guiBindings.clear()
        euclidIn.clear()
        euclidStates.clear()
        circle5In.clear()
        padIn.clear()
        try {
            synchronized(PdBase::class.java) { PdBase.closePatch(patchHandle) }
        } catch (t: Throwable) {
            log("closePatch: ${t.message}")
        }
        patchHandle = -1
        patchFile = null
    }

    fun currentPatchFile(): File? = patchFile

    /** All interactive objects of a document: top level plus GOP embedded. */
    private fun allObjects(doc: PatchDocument): List<PatchObject> =
        doc.objects() + doc.objects().flatMap { it.gop?.widgets ?: emptyList() }

    /**
     * Binds all gui widgets of [doc] that declare receive symbols so that
     * messages from Pd update the on-screen widgets. Every widget also
     * listens on its send symbol: pd iemgui echoes each output — from a
     * touch, an inlet cord or its receive symbol — out there, so the echo
     * is what keeps the display in sync for widgets the patch only gave a
     * send name (a bng/tgl triggered from inlets flashed nothing before).
     * Subpatch blocks are text lines at this level: their widgets join the
     * bindings straight out of those lines ([bindSubpatchGuis]).
     */
    fun bindGui(doc: PatchDocument) {
        for (obj in allObjects(doc)) {
            // else/display and else/display~ are display widgets living in a
            // plain abstraction (not iemgui), so they need the same runtime
            // text bridge the gui widgets get - spelled bare or else/-prefixed
            val base = obj.className().substringAfterLast('/')
            if (!obj.meta.isGui && base != "display" && base != "display~") continue
            bindGuiSymbols(obj)
        }
        bindSubpatchGuis(doc, bind = true)
    }

    private fun bindGuiSymbols(obj: PatchObject) {
        // else/display and display~ (0.17.140): the abstraction sends every
        // inbound message BOTH to the shared "pocketpd-display[~]" channel
        // and to the instance's private tag (creation arg 1, [send $1]).
        // Boxes created/cloned in the app always carry a unique tag and bind
        // only it, so each display shows its own feed; a bare [display] from
        // an imported patch has no tag and mirrors the shared channel (the
        // old fixed-symbol-only scheme made every copy show the same text).
        val base = obj.className().substringAfterLast('/')
        if (base == "display" || base == "display~") {
            val tag = obj.args.getOrNull(1)
            val sym = when {
                !tag.isNullOrEmpty() && tag != "empty" -> tag
                base == "display~" -> "pocketpd-display~"
                else -> "pocketpd-display"
            }
            guiBindings.getOrPut(sym) { HashSet() }.add(obj.id)
            subscribe(sym)
            return
        }
        // euclid has no user send/rcv: its two inlets ride the dedicated
        // in0/in1 relay symbols (trigger, reset)
        if (obj.className() == "euclid") {
            for (inlet in 0..1) {
                val sym = euclidInSym(obj, inlet)
                euclidIn[sym] = obj to inlet
                subscribe(sym)
            }
            return
        }
        // circle5 dial: one inlet carries degrees, bangs and config
        if (obj.className() == "circle5") {
            val sym = circle5InSym(obj)
            circle5In[sym] = obj
            subscribe(sym)
            return
        }
        // pad: the single inlet carries live config (gridx/gridy/size);
        // touches flow OUT through the two [r] relay symbols
        if (obj.className() == "pad") {
            val sym = padInSym(obj)
            padIn[sym] = obj
            subscribe(sym)
            return
        }
        val rcv = obj.meta.receiveSymbol
        if (rcv.isNotEmpty()) {
            guiBindings.getOrPut(rcv) { HashSet() }.add(obj.id)
            subscribe(rcv)
        }
        val snd = obj.meta.sendSymbol
        if (snd.isNotEmpty() && snd != rcv) {
            guiBindings.getOrPut(snd) { HashSet() }.add(obj.id)
            subscribe(snd)
        }
        // internal relay names (button): same dual subscription as user
        // symbols — pd floats coming back light the pad, touches go out
        val aSnd = obj.autoSend
        val aRcv = obj.autoReceive
        if (aSnd.isNotEmpty() && aSnd != snd && aSnd != rcv) {
            guiBindings.getOrPut(aSnd) { HashSet() }.add(obj.id)
            subscribe(aSnd)
        }
        if (aRcv.isNotEmpty() && aRcv != aSnd && aRcv != snd && aRcv != rcv) {
            guiBindings.getOrPut(aRcv) { HashSet() }.add(obj.id)
            subscribe(aRcv)
        }
    }

    /**
     * Walks every `#X obj` row inside subpatch blocks (nested blocks
     * included) of [doc]'s objects. With bind=true the row's gui widgets
     * join the receiver bindings; with bind=false ([autoBindGui]) rows
     * whose widget has neither symbol get auto symbols and the rewritten
     * row comes back so pd's own widget carries the same names.
     */
    private fun walkSubpatchGuis(
        lines: List<String>,
        bind: Boolean,
        seenRelaySyms: MutableSet<String> = HashSet(),
    ): MutableList<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.startsWith("#N canvas")) {
                val close = subpatchBlockRestore(lines, i)
                // recurse on the INNER lines only: re-including this level's
                // own "#N canvas" header would re-enter the branch forever
                // (StackOverflowError, c_ks_string.pd reopen crash 2026-11-03)
                out.add(line) // header
                if (close >= 0) {
                    out.addAll(walkSubpatchGuis(lines.subList(i + 1, close), bind, seenRelaySyms))
                    out.add(lines[close]) // restore
                    i = close + 1
                } else {
                    out.addAll(walkSubpatchGuis(lines.subList(i + 1, lines.size), bind, seenRelaySyms))
                    i = lines.size
                }
                continue
            }
            if (t.startsWith("#X obj")) {
                val toks = t.removeSuffix(";").trim().split(Regex("\\s+"))
                if (toks.size >= 4) {
                    val obj = PatchObject(
                        newObjectId(), toks[2].toIntOrNull() ?: 0,
                        toks[3].toIntOrNull() ?: 0,
                        ObjKind.OBJECT, ArrayList(toks.drop(4)),
                    )
                    obj.meta = PdObjectMeta.resolve(obj)
                    // scope stubs are display-only (no relays, no saved args)
                    val isScope =
                        obj.className() == "graph~" || obj.className() == "spectrograph~"
                    if (obj.meta.isGui && !isScope) {
                        if (bind) {
                            bindGuiSymbols(obj)
                        } else if (obj.className() == "euclid") {
                            val mine = (7..10).mapNotNull { obj.args.getOrNull(it) }
                                .filter { it.isNotBlank() && it != "-" && it != "empty" }
                            val collision = mine.any { !seenRelaySyms.add(it) }
                            if (euclidSlotsUnassigned(obj) || collision) {
                                // a copied block keeps the source rows' relay
                                // names — rename from the row coordinates so
                                // the clone does not steal the twin's wires
                                assignEuclidSymbols(
                                    obj,
                                    if (collision) "s${toks[2]}_${toks[3]}_${seenRelaySyms.size}"
                                    else obj.id.toString(36),
                                    force = collision,
                                )
                                (7..10).forEach { s ->
                                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() }
                                        ?.let { seenRelaySyms.add(it) }
                                }
                                out.add(
                                    "#X obj ${toks[2]} ${toks[3]} " +
                                        obj.args.joinToString(" ") + ";"
                                )
                                i++
                                continue
                            }
                        } else if (obj.className() == "circle5") {
                            val mine = listOf(6, 7).mapNotNull { obj.args.getOrNull(it) }
                                .filter { it.isNotBlank() && it != "-" && it != "empty" }
                            val collision = mine.any { !seenRelaySyms.add(it) }
                            if (circle5SlotsUnassigned(obj) || collision) {
                                assignCircle5Symbols(
                                    obj,
                                    if (collision) "s${toks[2]}_${toks[3]}_${seenRelaySyms.size}"
                                    else obj.id.toString(36),
                                    force = collision,
                                )
                                listOf(6, 7).forEach { s ->
                                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() }
                                        ?.let { seenRelaySyms.add(it) }
                                }
                                out.add(
                                    "#X obj ${toks[2]} ${toks[3]} " +
                                        obj.args.joinToString(" ") + ";"
                                )
                                i++
                                continue
                            }
                        } else if (obj.className() == "pad") {
                            val mine = listOf(5, 6, 7).mapNotNull { obj.args.getOrNull(it) }
                                .filter { it.isNotBlank() && it != "-" && it != "empty" }
                            val collision = mine.any { !seenRelaySyms.add(it) }
                            if (padSlotsUnassigned(obj) || collision) {
                                assignPadSymbols(
                                    obj,
                                    if (collision) "s${toks[2]}_${toks[3]}_${seenRelaySyms.size}"
                                    else obj.id.toString(36),
                                    force = collision,
                                )
                                listOf(5, 6, 7).forEach { s ->
                                    obj.args.getOrNull(s)?.takeIf { it.isNotBlank() }
                                        ?.let { seenRelaySyms.add(it) }
                                }
                                out.add(
                                    "#X obj ${toks[2]} ${toks[3]} " +
                                        obj.args.joinToString(" ") + ";"
                                )
                                i++
                                continue
                            }
                        } else if (obj.meta.receiveSymbol.isNotEmpty() ||
                            obj.meta.sendSymbol.isNotEmpty()
                        ) {
                            // named row (atom/iemgui): a copied block keeps the
                            // source rows' names — rename only APP-GENERATED
                            // collisions, user-typed ones may be intentional
                            val cls = obj.className()
                            val snd = obj.meta.sendSymbol
                            val rcv = obj.meta.receiveSymbol
                            val dupSnd = snd.isNotEmpty() && !seenRelaySyms.add(snd) &&
                                PdObjectMeta.isAutoSymbol(cls, snd)
                            val dupRcv = rcv.isNotEmpty() && rcv != snd &&
                                !seenRelaySyms.add(rcv) && PdObjectMeta.isAutoSymbol(cls, rcv)
                            if (dupSnd || dupRcv) {
                                PdObjectMeta.setSymbols(
                                    obj,
                                    "${cls}-send-s${toks[2]}_${toks[3]}_${seenRelaySyms.size}",
                                    "${cls}-rcv-s${toks[2]}_${toks[3]}_${seenRelaySyms.size}",
                                )
                                out.add(
                                    "#X obj ${toks[2]} ${toks[3]} " +
                                        obj.args.joinToString(" ") + ";"
                                )
                                i++
                                continue
                            }
                        } else if (obj.meta.receiveSymbol.isEmpty() &&
                            obj.meta.sendSymbol.isEmpty()
                        ) {
                            val uid = obj.id.toString(36)
                            PdObjectMeta.setSymbols(
                                obj,
                                "auto-${obj.className()}-$uid-send",
                                "auto-${obj.className()}-$uid-rcv",
                            )
                            out.add(
                                "#X obj ${toks[2]} ${toks[3]} " +
                                    obj.args.joinToString(" ") + ";"
                            )
                            i++
                            continue
                        }
                    }
                }
            }
            out.add(line)
            i++
        }
        return out
    }

    private fun bindSubpatchGuis(doc: PatchDocument, bind: Boolean) {
        for (obj in doc.objects()) {
            obj.subpatchLines?.let { walkSubpatchGuis(it, bind) }
        }
    }

    private fun subscribe(symbol: String) {
        val fresh = subscribedSymbols.add(symbol)
        if (fresh) {
            synchronized(PdBase::class.java) { PdBase.subscribe(symbol) }
        }
    }

    // ------------------------------------------------------------------ sending

    // libpd is single-threaded at its core: every PdBase entry from a foreign
    // thread (UI taps, OSC executor, sensor listener, MIDI/serial bridges) and
    // the message poller must hold the PdBase class monitor — the same one the
    // library's own static synchronized methods (sendList/openPatch/subscribe)
    // take; the native sends (sendBang/sendFloat/sendSymbol/poll) are NOT
    // self-synchronized, and unlocked concurrent sends are silently eaten by
    // the audio thread's scheduler tick. This was the real killer behind "the
    // patch does not fire as wired": a widget's bang/float raced the sensor
    // bridge's continuous sendFloat stream and vanished.

    fun sendBang(recv: String): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.sendBang(recv) }
                .map { it == 0 }
                .onFailure { log("sendBang($recv) failed: ${it.message}") }
                .getOrDefault(false)
        }

    fun sendFloat(recv: String, value: Float): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.sendFloat(recv, value) }
                .map { it == 0 }
                .onFailure { log("sendFloat($recv) failed: ${it.message}") }
                .getOrDefault(false)
        }

    fun sendSymbol(recv: String, sym: String): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.sendSymbol(recv, sym) }
                .map { it == 0 }
                .onFailure { log("sendSymbol($recv) failed: ${it.message}") }
                .getOrDefault(false)
        }

    fun sendList(recv: String, args: List<Any>): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.sendList(recv, *args.toTypedArray()) }
                .map { it == 0 }
                .onFailure { log("sendList($recv) failed: ${it.message}") }
                .getOrDefault(false)
        }

    // ---------------------------------------------------------------- arrays

    /** Current size of a pd array; -1 when no such array exists. */
    fun arraySize(name: String): Int =
        synchronized(PdBase::class.java) { runCatching { PdBase.arraySize(name) }.getOrDefault(-1) }

    /** Reads n values of a pd array into out; success only when all read. */
    fun readArray(name: String, from: Int, out: FloatArray): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.readArray(out, 0, name, from, out.size) == 0 }.getOrDefault(false)
        }

    /** Writes one value into a pd array (graph touch-editing). */
    fun writeArrayValue(name: String, index: Int, value: Float): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.writeArray(name, index, floatArrayOf(value), 0, 1) == 0 }
                .getOrDefault(false)
        }

    /** Writes a contiguous range into a pd array — one JNI call per stroke
     *  event instead of one per cell (graph touch-editing sweep). */
    fun writeArrayRange(name: String, destIndex: Int, src: FloatArray, srcOffset: Int, n: Int): Boolean =
        synchronized(PdBase::class.java) {
            runCatching { PdBase.writeArray(name, destIndex, src, srcOffset, n) == 0 }
                .getOrDefault(false)
        }

    /**
     * Emulates touching a gui widget in desktop Pd. A message to the widget's
     * *receive* symbol updates it and emits from its outlet into the patch;
     * without one we fall back to the send symbol (external link only).
     */
    /** Verbose gui-touch logging for diagnosing wiring problems. */
    @JvmField
    var debugGui: Boolean = false

    /** ELSE-compat [keyboard]: a key press/release goes out as a
     *  "pitch vel" list on the widget's send path, like a widget touch. */
    /**
     * Fires the current step of an [euclid] widget: 1/0 on outlet 0, the
     * hit's ordinal within the circle on outlet 1 (hit steps only), then
     * advances the play position. Lands from a bang (or nonzero float) on
     * inlet 0 and from tapping an interactive widget. The pointer reads the
     * position back through the object's runtimeValue.
     */
    fun triggerEuclid(obj: PatchObject) {
        if (!widgetsLive) return // edit mode: the clock is inert (see widgetsLive)
        val st = euclidState(obj)
        fireEuclidAt(obj, st, st.pos.coerceIn(0, st.steps - 1))
    }

    /**
     * Emits the values of step [pos] (1/0 + hit ordinal on the outlets),
     * then advances the play position. The pointer shows the step that is
     * on the outlets right now (0.16.0 drew the NEXT step — readouts always
     * looked one ahead). Also the landing spot of plain floats (jump+fire).
     */
    private fun fireEuclidAt(obj: PatchObject, st: EuclidState, pos: Int) {
        if (!widgetsLive) return
        if (st.pattern[pos] == 1) {
            sendFloat(euclidOutSym(obj, 0), 1f)
            sendFloat(euclidOutSym(obj, 1), st.hitIdx.toFloat())
            st.hitIdx = if (st.hits > 0) (st.hitIdx + 1) % st.hits else 0
        } else {
            sendFloat(euclidOutSym(obj, 0), 0f)
        }
        st.pos = (pos + 1) % st.steps
        obj.runtimeValue = pos.toFloat()
        obj.flashUntil = System.currentTimeMillis() + 120
        guiListener?.onGuiChanged(obj.id)
    }

    /**
     * Live configuration through the left inlet: `hit 3`, `step 8`,
     * `rotation 1` — each key alone or chained in one message
     * (`hit 3 step 8 rotation 1`). Writes the widget's own args (dial,
     * Properties and the saved file all follow); the pointer keeps its
     * previous position, wrapped modulo the new steps so a smaller circle
     * cannot strand it, and the engine state rebuilds on the next fire
     * (position wrapped the same way, hit ordinal restarted).
     */
    private fun euclidConfigure(obj: PatchObject, atoms: List<Any>) {
        if (atoms.isEmpty()) return
        fun steps() = obj.args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, EuclidRhythm.MAX_STEPS) ?: 8
        fun put(idx: Int, v: Int) {
            while (obj.args.size <= idx) obj.args.add("-")
            obj.args[idx] = v.toString()
        }
        var changed = false
        var i = 0
        while (i < atoms.size - 1) {
            val key = (atoms[i] as? String)?.lowercase()
            val v = (atoms[i + 1] as? Number)?.toInt()
            if (key != null && v != null) {
                when (key) {
                    "hit", "hits" -> { put(1, v.coerceIn(0, EuclidRhythm.MAX_STEPS)); changed = true }
                    "step", "steps" -> { put(2, v.coerceIn(1, EuclidRhythm.MAX_STEPS)); changed = true }
                    "rotation", "rot" -> {
                        val st = steps()
                        put(3, ((v % st) + st) % st)
                        changed = true
                    }
                    else -> Unit
                }
            }
            i += 2
        }
        if (!changed) return
        // pointer: previous position, wrapped into the new circle; the ready
        // notch (negative sentinel) stays put
        val st = steps()
        if (obj.runtimeValue >= 0f) {
            obj.runtimeValue = (obj.runtimeValue.toInt() % st).toFloat()
        }
        obj.flashUntil = System.currentTimeMillis() + 120
        guiListener?.onGuiChanged(obj.id)
    }

    /** [euclid] right inlet: back to step 0, hit ordinal restarts. */
    fun resetEuclid(obj: PatchObject) {
        if (!widgetsLive) return
        val st = euclidState(obj)
        st.pos = 0
        st.hitIdx = 0
        // ready notch: half a tick counterclockwise of step 0, so the first
        // trigger is easy to spot (pointer jumps onto tick 0 when it fires)
        obj.runtimeValue = -0.5f
        obj.flashUntil = System.currentTimeMillis() + 120
        guiListener?.onGuiChanged(obj.id)
    }

    /**
     * Drops every euclid play state (edit/run mode switches): the next
     * trigger rebuilds from step 0 with the hit ordinal at 0, and widgets
     * redraw at the ready notch.
     */
    fun resetEuclidStates() {
        euclidStates.clear()
    }

    fun keyboardNote(obj: com.pocketpd.app.patch.PatchObject, pitch: Int, down: Boolean) {
        val snd = effSnd(obj).ifEmpty { effRcv(obj) }
        if (snd.isEmpty()) {
            trace("keyboard note dropped: no symbol bound")
            return
        }
        runCatching { PdBase.sendList(snd, pitch.toFloat(), if (down) 100f else 0f) }
            .onFailure { log("keyboard note failed: ${it.message}") }
    }

    fun guiTouched(obj: com.pocketpd.app.patch.PatchObject, value: Float, isBang: Boolean) {
        // a tap on an interactive euclid is one trigger; value is unused
        if (obj.className() == "euclid") {
            triggerEuclid(obj)
            return
        }
        // a circle5 tap carries the cell (ring*12 + pos0) as the value
        if (obj.className() == "circle5") {
            circle5Tapped(obj, value.toInt())
            return
        }
        val rcv = effRcv(obj)
        val snd = effSnd(obj)
        // Send ONE message: snd is the outbound path (the session rewires the
        // widget's cords through [r snd]); rcv is the inbound display path
        // (pd → app). Sending both would double-deliver — pd iemgui echoes
        // receive-symbol input back out to its snd symbol.
        val ok = when {
            snd.isNotEmpty() -> if (isBang) sendBang(snd) else sendFloat(snd, value)
            rcv.isNotEmpty() -> if (isBang) sendBang(rcv) else sendFloat(rcv, value)
            else -> false
        }
        if (!ok) trace("gui touch dropped: ${obj.className()} rcv='$rcv' snd='$snd'")
        else trace("gui touch: ${obj.className()} v=$value rcv='$rcv' snd='$snd'")
    }

    // ------------------------------------------------------------------ console

    /** One console line; [error] lines render in the warning color. */
    data class ConsoleLine(val text: String, val error: Boolean)

    /** pd/app failure lines the console paints in the warning color. */
    private fun isErrorLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("error:") ||
            t.contains("couldn't create") ||
            t.startsWith("WARNING:") ||
            t.contains("FAILED") ||
            t.startsWith("parse:")
    }

    /**
     * Per-message diagnostics, LOGCAT ONLY. The old dispatch traces
     * ("atom <- …", "gui touch: …", "float unbound: …" …) went through
     * [log] and flooded the console on every interaction — a slider drag
     * painted sixty console lines a second (user report 2026-11-04, the
     * "console spam"顽疾). They keep their forensic value here: grep
     * logcat for PocketPd.Trace.
     */
    private fun trace(line: String) {
        android.util.Log.i("PocketPd.Trace", line)
    }

    @Synchronized
    fun log(line: String, error: Boolean = isErrorLine(line)) {
        // The console is a human-readable strip: machine-generated multi-KB
        // lines (index dumps) make text wrapping pathological, so cap what is
        // kept. NULs (LLM streams pad with them, as control char or as the
        // literal "\u0000" text) are dropped from the strip only — the pd
        // message path is never touched, and logcat still receives the full
        // raw line.
        val stored = line.replace("\u0000", "").replace("\\u0000", "").let {
            if (it.length > 2000) it.take(2000) + " …" else it
        }
        console.addLast(ConsoleLine(stored, error))
        while (console.size > 500) console.removeFirst()
        consoleListener?.onConsoleLine(console.last())
        android.util.Log.i("PocketPd.Pd", line)
    }

    @Synchronized
    fun consoleSnapshot(): List<ConsoleLine> = console.toList()

    @Synchronized
    fun clearConsole() = console.clear()

    // ------------------------------------------------------------------ OSC bridge

    /**
     * OSC -> Pd convention: address "/foo/bar" with arguments becomes a message
     * to the receiver "foo/bar" (so patches use `[r foo/bar]`). No arguments
     * sends a bang. A diagnostic copy of every message goes to the console.
     */
    fun handleOscIn(msg: OscCodec.OscMessage) {
        val symbol = msg.address.removePrefix("/")
        if (symbol.isNotEmpty()) {
            if (msg.args.isEmpty()) {
                sendBang(symbol)
            } else {
                sendList(symbol, msg.args.map { toPdArg(it) })
            }
        }
        trace("osc in ${msg.address} ${msg.args.joinToString(" ")}")
    }

    /** libpd messages accept only Float/Integer/String. */
    private fun toPdArg(value: Any): Any = when (value) {
        is Boolean -> if (value) 1f else 0f
        is Long -> value.toFloat()
        is Double -> value.toFloat()
        else -> value
    }

    // ------------------------------------------------------------------ receivers

    private val receiver = object : PdReceiver {
        override fun print(s: String) {
            s.split('\n').filter { it.isNotBlank() }.forEach { line ->
                // pd's verbose chatter (per-class library banners) arrives in
                // floods and drowns real output; keep it in logcat only
                if (line.trimStart().startsWith("verbose(")) return@forEach
                log(line)
                lastConsoleLine = line
                recordCreateFailure(line)
            }
        }

        override fun receiveBang(source: String) {
            euclidIn[source]?.let { (obj, inlet) ->
                if (inlet == 0) triggerEuclid(obj) else resetEuclid(obj)
                return
            }
            // circle5: a bang re-emits the current key's tonic (degree 1)
            circle5In[source]?.let { obj ->
                emitCircle5Degree(obj, 1)
                return
            }
            val ids = guiBindings[source] ?: return
            for (id in ids) {
                val obj = boundObjects[id] ?: continue
                // pd flips a toggle on bang; with a send symbol the flipped
                // value echoes back as a float and drives the display, but a
                // receive-only toggle has no echo — mirror the flip here
                if (obj.className() == "tgl" && obj.meta.sendSymbol.isEmpty()) {
                    obj.runtimeValue = if (obj.runtimeValue > 0.5f) 0f else 1f
                }
                obj.flashUntil = System.currentTimeMillis() + 120
                guiListener?.onGuiChanged(id)
            }
        }

        override fun receiveFloat(source: String, x: Float) {
            if (source == "TTY") {
                ttyOutListener?.invoke(arrayOf<Any>(x))
                return
            }
            if (source == "pocketpd-vu-l") {
                vuL = x
                vuListener?.invoke(vuL, vuR)
                return
            }
            if (source == "pocketpd-vu-r") {
                vuR = x
                vuListener?.invoke(vuL, vuR)
                return
            }
            if (source == "pocketpd-vu") {
                vuL = x
                vuR = x
                vuListener?.invoke(x, x)
                return
            }
            // [euclid] inlets: a plain float on the left inlet jumps the
            // pointer to that step (wrapped modulo steps, so oversized
            // values cannot overflow) and fires it; nonzero floats on the
            // right inlet reset, 0 is ignored
            euclidIn[source]?.let { (obj, inlet) ->
                when {
                    inlet != 0 -> if (x != 0f) resetEuclid(obj)
                    x.isFinite() -> {
                        val st = euclidState(obj)
                        val pos = ((x.toInt() % st.steps) + st.steps) % st.steps
                        fireEuclidAt(obj, st, pos)
                    }
                }
                return
            }
            // circle5: a float selects the diatonic degree, wrapped pd-style
            // (8 -> 1, 0 -> 7, -1 -> 6); other values are ignored
            circle5In[source]?.let { obj ->
                if (widgetsLive && x.isFinite()) {
                    emitCircle5Degree(obj, x.toInt())
                }
                return
            }
            val ids = guiBindings[source]
            if (ids == null) {
                // pocketpd-* is the app's own sensor bridge chatter (continuous)
                if (!source.startsWith("pocketpd-")) trace("float unbound: '$source' v=$x")
                return
            }
            for (id in ids) {
                boundObjects[id]?.let {
                    it.runtimeValue = x
                    // a fresh single float supersedes any pending list text
                    it.runtimeText = ""
                    trace("atom <- $x rcv='$source' id=$id")
                    guiListener?.onGuiChanged(id)
                } ?: log("atom missing: id=$id rcv='$source'")
            }
        }

        /** Symbols arriving on a widget's receive symbol update its text
         *  display (symbolatom); desktop pd redraws the atom in Tcl, libpd
         *  has no GUI so the value must flow back through the binding. */
        override fun receiveSymbol(source: String, symbol: String) {
            val ids = guiBindings[source] ?: return
            for (id in ids) {
                boundObjects[id]?.let {
                    it.runtimeText = symbol
                    guiListener?.onGuiChanged(id)
                }
            }
        }

    /**
     * OSC-out convention: a list whose first element is an address symbol
     * (`list /foo 1 2`) sent to the receiver "OSC" becomes an OSC message.
     * Lists landing anywhere else update bound widgets' text display
     * (listbox).
     */
    override fun receiveList(source: String, args: Array<out Any>) {
        // [pad] inlet: live configuration (`gridx n`, `gridy n`, `size w h`,
        // chainable in one message)
        padIn[source]?.let { obj ->
            padConfigure(obj, args.toList())
            return
        }
        if (source == "TTY") {
            ttyOutListener?.invoke(args)
            return
        }
        if (source == "OSC") {
            val addr = args.firstOrNull() as? String
            if (addr != null && addr.startsWith("/")) {
                oscOutListener?.onOscMessage(OscCodec.OscMessage(addr, args.drop(1).toList()))
            }
            return
        }
        // [euclid] left inlet: live configuration — `hit 3`, `step 8`,
        // `rotation 1`, each key alone or chained in one message
        // (`hit 3 step 8 rotation 1`); the right inlet stays reset-only
        euclidIn[source]?.let { (obj, inlet) ->
            // a lone number list is a float (OSC one-arg messages): left
            // inlet jumps-and-fires, right inlet resets on nonzero
            val lone = args.singleOrNull() as? Number
            if (lone != null) {
                val x = lone.toFloat()
                when {
                    inlet != 0 -> if (x != 0f) resetEuclid(obj)
                    x.isFinite() -> {
                        val st = euclidState(obj)
                        val pos = ((x.toInt() % st.steps) + st.steps) % st.steps
                        fireEuclidAt(obj, st, pos)
                    }
                }
            } else if (inlet == 0) {
                euclidConfigure(obj, args.toList())
            }
            return
        }
        // [circle5] inlet: `chord c` / `chord am` / `chord 2` / `size 4`.
        // A single-number list (how a one-argument OSC message arrives) is
        // a degree, same as a bare float — pd would have delivered it as a
        // float had it come from a message box
        circle5In[source]?.let { obj ->
            val lone = args.singleOrNull() as? Number
            if (lone != null) {
                if (widgetsLive && lone.toFloat().isFinite()) emitCircle5Degree(obj, lone.toInt())
            } else {
                circle5Configure(obj, args.toList())
            }
            return
        }
        updateBoundText(source, args.joinToString(" ") { pdText(it) })
    }

    /** `[; OSC /foo 1 2(` arrives here as a typed message. */
    override fun receiveMessage(source: String, symbol: String, args: Array<out Any>) {
        if (source == "OSC" && symbol.startsWith("/")) {
            oscOutListener?.onOscMessage(OscCodec.OscMessage(symbol, args.toList()))
            return
        }
        // [euclid] typed config (`[; auto-euclid-..-in0 hit 3(`) — same
        // grammar as the list form, the leading key arrives as [symbol]
        euclidIn[source]?.let { (obj, inlet) ->
            if (inlet == 0) euclidConfigure(obj, listOf(symbol) + args.toList())
            return
        }
        circle5In[source]?.let { obj ->
            circle5Configure(obj, listOf(symbol) + args.toList())
            return
        }
        updateBoundText(source, (listOf(symbol) + args.map { pdText(it) }).joinToString(" "))
    }

    /** Pushes a text payload to every widget listening on [source]. */
    private fun updateBoundText(source: String, text: String) {
        val ids = guiBindings[source]
        if (ids == null) {
            trace("text unbound: '$source' '$text'")
            return
        }
        // one dispatch tick late: a pd gatom echoes the same inbound message
        // back within this very burst (a chord list's first float), and its
        // float would clobber the fuller list display — let the echo land
        // first, then write the text that was actually received
        pollHandler.post {
            for (id in ids) {
                boundObjects[id]?.let {
                    it.runtimeText = text
                    guiListener?.onGuiChanged(id)
                }
            }
        }
    }

    /** pd atom → display text; floats print without the trailing ".0". */
    private fun pdText(v: Any): String = when (v) {
        is Float -> if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
    }

    /** Populated by [bindGui]; receive-symbol messages only touch bound widgets. */
    @Volatile
    var boundObjects: Map<Long, com.pocketpd.app.patch.PatchObject> = emptyMap()

    fun bindObjects(doc: PatchDocument) {
        boundObjects = allObjects(doc).associateBy { it.id }
        bindGui(doc)
        subscribe("OSC") // OSC-out convention: patches send lists to [r OSC]
        val guis = allObjects(doc).filter { it.meta.isGui }
        if (guis.isNotEmpty()) {
            log("gui bind: " + guis.joinToString("; ") {
                "${it.className()} snd='${it.meta.sendSymbol}' rcv='${it.meta.receiveSymbol}'"
            } + " — ${subscribedSymbols.size} symbol(s) subscribed")
        }
    }

    // ------------------------------------------------------------------ MIDI out of Pd

    private val midiReceiver = object : PdMidiReceiver {
        override fun receiveNoteOn(channel: Int, pitch: Int, velocity: Int) {
            midiOutListener?.onNoteOn(channel, pitch, velocity)
        }

        override fun receiveControlChange(channel: Int, controller: Int, value: Int) {
            midiOutListener?.onControlChange(channel, controller, value)
        }

        override fun receiveProgramChange(channel: Int, value: Int) {
            midiOutListener?.onProgramChange(channel, value)
        }

        override fun receivePitchBend(channel: Int, value: Int) {
            midiOutListener?.onPitchBend(channel, value)
        }

        override fun receiveAftertouch(channel: Int, value: Int) {
            midiOutListener?.onAftertouch(channel, value)
        }

        override fun receivePolyAftertouch(channel: Int, pitch: Int, value: Int) {
            midiOutListener?.onPolyAftertouch(channel, pitch, value)
        }

        override fun receiveMidiByte(port: Int, value: Int) {
            midiOutListener?.onMidiByte(port, value)
        }

        override fun beginBlock(): Boolean = false
        override fun endBlock() {}
    }

    @Volatile
    var appContext: Context? = null
}
