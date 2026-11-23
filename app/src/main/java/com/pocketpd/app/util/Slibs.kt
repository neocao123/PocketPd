package com.pocketpd.app.util

import android.content.Context
import android.os.Build
import com.pocketpd.app.runtime.PdEngine
import com.pocketpd.app.runtime.PdTweaks
import org.puredata.core.PdBase
import java.io.File

/**
 * Bundled pd-extended/purr-data lineage libraries (diy2, cyclone, zexy,
 * iemlib, fftease, bsaylor, creb, cxc, mrpeach osc/midifile/binfile), compiled for
 * Android as ONE core .so per library (their objects cross-reference each
 * other, so per-object files cannot resolve) under assets/slibs/<abi>/.
 * diy2 (plugdata's pure-abstraction library) ships as plain .pd files.
 *
 * At startup the cores are loaded through a generated [declare -lib] patch
 * in REVERSED priority order: pd's objectmaker keeps the most recently
 * registered class, so loading the FIRST priority entry last gives it the
 * bare object names, matching [PRIORITY] (diy2 outranks everything by
 * default). Same-named classes in two libraries are therefore resolved
 * deterministically and reported on the console; the other one is reachable
 * through the library's own namespaced spelling where it registers one
 * (cyclone/xxx, zexy/xxx), like desktop pd-extended.
 */
object Slibs {

    /** Bump to re-extract after an assets/slibs update. 140: 0.57 branch
     *  (0.18.8 revert) - RC15 tempo.pd restored - else/tempo.pd swapped to the plugdata-bundled ELSE
     *  revision (else/lb wrapper, -hz/-mul/-seed flags, unit select).
     *  138: 0.57 branch (0.18.4) - core_wrap setup-call list now accepts
     *  BOTH pd setup spellings: classes with non-identifier names
     *  (rand.f/rand.i/rand.u/rand.seq etc - dots hexmangle) use
     *  setup_-PREFIXED symbols (setup_rand0x2ef) that the old
     *  _setup-suffix filter silently dropped from the call list; else wrap
     *  289 setups (was ~150). */
    private const val VERSION = 140

    /** Bump to re-extract after an assets/ymbanks update. */
    private const val YMB_VERSION = 2

    /** Directory the bundled .wopn GM banks are extracted to (registered
     *  as a pd search path so `bank fmtowns.wopn` resolves anywhere). */
    fun banksDir(context: Context): File = File(context.filesDir, "ymbanks")

    /** Bare-name priority: first entry wins (loaded last). */
    val PRIORITY = listOf(
        "diy2",
        // ELSE-compat UI widgets (graph~/spectrograph~/keyboard) ship as
        // vanilla-only abstractions under the same object names
        "else",
        // incremental else classes (0.17.134): compiled from the vendored
        // upstream-master subset (slibs/else-src) that the hand-integrated
        // else_core.so never contained - mpe.in, ptouch.in/out, canvas.*,
        // multichannel signal classes, ... No bare-name clash with the
        // libraries above, so its spot in the order is only about load time
        "elsex",
        "cyclone", "zexy", "iemlib", "fftease", "bsaylor",
        "creb", "cxc", "osc", "midifile", "binfile", "tracker",
        "pocketpd",
    )

    fun dir(context: Context): File = File(context.filesDir, "slibs")

    fun libDir(context: Context, lib: String): File = File(dir(context), lib)

    /** Library directories in priority order (search-path order). */
    fun libDirs(context: Context): List<File> = PRIORITY.map { libDir(context, it) }

    fun ensureExtracted(context: Context) {
        val marker = File(context.filesDir, ".slibs-v$VERSION")
        if (marker.exists()) return
        val out = dir(context)
        out.deleteRecursively()
        out.mkdirs()
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        if (abi == null) return
        runCatching {
            copyAssetDir(context, "slibs/$abi", out)
            writeAutoload(out)
            for (lib in PRIORITY) symlinkCoreClasses(libDir(context, lib))
            marker.writeText("ok")
            PdEngine.log("slibs: extracted bundled libraries ($abi)")
        }.onFailure { PdEngine.log("slibs: extraction failed: ${it.message}") }
    }

    /** Extracts the bundled .wopn GM banks (assets/ymbanks) so the
     *  `bank <name.wopn>` message of [ym2612~] can load them through the
     *  pd search path. */
    fun ensureBanks(context: Context) {
        val marker = File(context.filesDir, ".ymbanks-v$YMB_VERSION")
        if (marker.exists()) return
        val out = banksDir(context)
        out.deleteRecursively()
        out.mkdirs()
        runCatching {
            copyAssetDir(context, "ymbanks", out)
            marker.writeText("ok")
            PdEngine.log("ymbanks: extracted bundled GM banks")
        }.onFailure { PdEngine.log("ymbanks: extraction failed: ${it.message}") }
    }

    /**
     * Symlinks every C class of a library core as <class>.so next to it, so
     * pd's binary loader can resolve lib-prefixed spellings like
     * [else/pack2] (it dlopens exactly <dir>/<class>.so and dlsyms the setup;
     * class_new then registers the qualified name as an alias). The class
     * list comes from core_classes.txt, written at build time by reverse-
     * munging the exported setup symbols. Best effort: when /system/bin/ln
     * is unavailable, bare spellings keep working and only the prefixed
     * forms of binary classes degrade.
     */
    private fun symlinkCoreClasses(dir: File) {
        val list = File(dir, "core_classes.txt")
        if (!list.isFile) return // abstraction-only libraries (diy2)
        val classes = runCatching {
            list.readLines().filter { it.isNotBlank() && it != "/" }
        }.getOrDefault(emptyList())
        if (classes.isEmpty()) return
        val core = "${dir.name}_core.so"
        try {
            // each class word single-quoted: some C class names ARE shell
            // metacharacters (cyclone !- <~ >~, zexy &&~ ||~ ., cxc << >>).
            // Unquoted, < > become redirections and && || command lists -
            // the whole sh -c died on a syntax error and the ENTIRE batch
            // of per-class symlinks was lost (cyclone/zexy/cxc shipped with
            // zero symlinks, so lib-prefixed spellings like [zexy/pack]
            // could not resolve on any device)
            val script = "cd '${dir.absolutePath}' && for n in ${classes.joinToString(" ") { "'$it.so'" }}; do ln -sfn '$core' \"\$n\"; done"
            val p = ProcessBuilder("/system/bin/sh", "-c", script).start()
            if (p.waitFor() != 0) {
                PdEngine.log("slibs: ln exit ${p.exitValue()} for ${dir.name}")
            }
        } catch (t: Throwable) {
            PdEngine.log("slibs: ln unavailable (${t.message}); lib/-prefixed spellings of ${dir.name} may not resolve")
        }
    }

    /**
     * Loads every library core directly through pd's sys_load_lib (via
     * [PdTweaks]), in REVERSED priority order so earlier [PRIORITY] entries
     * keep bare object names: pd keeps the most recently registered class.
     * Must run after the search path is registered and before the first user
     * patch opens. Falls back to a [declare -lib] patch when the native
     * helper is unavailable.
     */
    fun openAutoload(context: Context) {
        // register the bundled-bank folder so ym2612~/vgm~ resolve
        // `bank fmtowns.wopn` / `load song.vgm` by bare file name
        runCatching { PdBase.addToSearchPath(banksDir(context).absolutePath) }
        if (!PdTweaks.available) return declareAutoload(context)
        var ok = 0
        var total = 0
        for (lib in PRIORITY.reversed()) {
            val core = File(libDir(context, lib), "${lib}_core.so")
            if (!core.isFile) continue // abstraction-only libraries (diy2)
            total++
            if (PdTweaks.nativeLoadCore(core.absolutePath, "${lib}_core_setup")) ok++
            else PdEngine.log("slibs: loading ${core.name} failed")
        }
        PdEngine.log("slibs: $ok/$total library cores loaded")
        PdEngine.log("slibs: flushing autoload")
        // pd boots lazily on the first patch open; a patch auto-opened at
        // startup would race the deferred core setups and leave placeholder
        // boxes. Open the declare patch NOW to boot pd and flush every
        // setup before the first user patch can parse.
        val patch = File(dir(context), "autoload.pd")
        if (patch.exists()) runCatching {
            synchronized(PdBase::class.java) {
                val handle = PdBase.openPatch(patch)
                PdBase.closePatch(handle)
            }
        }
    }

    /** Fallback when the native helper is missing. */
    private fun declareAutoload(context: Context) {
        val patch = File(dir(context), "autoload.pd")
        if (!patch.exists()) return
        runCatching {
            synchronized(PdBase::class.java) {
                val handle = PdBase.openPatch(patch)
                PdEngine.log("slibs: autoload handle=$handle")
                PdBase.closePatch(handle)
            }
        }.onFailure { PdEngine.log("slibs: autoload failed: ${it.message}") }
    }

    /**
     * Reports cross-library object-name clashes on the console. Bare names go
     * to the library listed first; the other one stays reachable as lib/name.
     */
    fun logConflicts(context: Context) {
        val byClass = mutableMapOf<String, MutableList<String>>()
        for ((lib, cls) in readManifest(context)) {
            val libs = byClass.getOrPut(cls) { mutableListOf() }
            if (lib !in libs) libs.add(lib)
        }
        val clashes = byClass.filterValues { it.size > 1 }
            .entries.sortedBy { it.key.lowercase() }
        val total = byClass.size
        if (clashes.isEmpty()) {
            PdEngine.log("slibs: $total object classes available, no name conflicts")
            return
        }
        val rank = PRIORITY.withIndex().associate { (i, l) -> l to i }
        val details = clashes.take(12).joinToString("; ") { (cls, libs) ->
            val ordered = libs.sortedBy { rank[it] ?: 99 }
            "'$cls' -> ${ordered.first()} (else ${ordered.drop(1).joinToString("/") { l -> "$l/$cls" }})"
        }
        val more = if (clashes.size > 12) " (+${clashes.size - 12} more)" else ""
        PdEngine.log("slibs: $total object classes, ${clashes.size} name conflicts: $details$more")
    }

    /** Writes the autoload patch: reversed priority declare order. */
    private fun writeAutoload(out: File) {
        val sb = StringBuilder("#N canvas 0 0 100 100 10;\n")
        for (lib in PRIORITY.reversed()) {
            if (File(File(out, lib), "${lib}_core.so").exists()) {
                sb.append("#X declare -lib ${lib}_core;\n")
            }
        }
        File(out, "autoload.pd").writeText(sb.toString())
    }

    /** (lib, class) pairs from the build manifest, current ABI only. */
    private fun readManifest(context: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            context.assets.open("slibs/slibs_manifest.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size >= 3) out += p[0] to p[1]
                }
            }
        }
        return out
    }

    /**
     * Which bundled library owns each bare class name, following [PRIORITY]
     * (pd keeps the last-registered class, so the first-listed library wins).
     * Used by help lookup to route a class to its library's help folder.
     */
    fun classToLib(context: Context): Map<String, String> {
        val rank = PRIORITY.withIndex().associate { (i, l) -> l to i }
        val map = HashMap<String, String>()
        for ((lib, cls) in readManifest(context)) {
            val prev = map[cls]
            if (prev == null || (rank[lib] ?: 99) < (rank[prev] ?: 99)) map[cls] = lib
        }
        return map
    }

    /**
     * Every class the bundled libraries provide, in both bare and lib/name
     * spellings — feeds PdObjectMeta's known-class set so unknown-name
     * detection can mark genuinely broken boxes.
     */
    /** Bundled library classes keyed by their library (else/cyclone/…),
     *  straight from the manifest — feeds the name-bar autocomplete. */
    fun classNamesByLib(context: Context): Map<String, String> =
        readManifest(context).map { it.second to it.first }.toMap()

    fun knownClasses(context: Context): Set<String> {
        val out = HashSet<String>()
        for ((lib, cls) in readManifest(context)) {
            out.add(cls)
            out.add("$lib/$cls")
        }
        return out
    }

    /**
     * Bare names of every .pd abstraction shipped in the bundled slibs,
     * walked from the extracted directory (one entry per <lib>/<name>.pd).
     * The build manifest ALSO lists these — manifest membership therefore
     * proves nothing about how pd resolves a name: a manifest class with a
     * .pd file here may still be compiled (see the offline-verified
     * DUAL_COMPILE_PROTECT in PdObjectMeta), but one without is a pure
     * abstraction pd loads through the file loader, and the session builder
     * must inline it per instance.
     */
    fun pdFileNames(context: Context): Set<String> {
        val out = HashSet<String>()
        val root = dir(context)
        val libs = root.listFiles() ?: return out
        for (lib in libs) {
            if (!lib.isDirectory) continue
            for (f in lib.listFiles() ?: emptyArray()) {
                if (f.isFile && f.name.endsWith(".pd")) {
                    out.add(f.name.removeSuffix(".pd"))
                }
            }
        }
        return out
    }

    /**
     * Manifest classes pd resolves to a COMPILED class: manifest names with
     * no .pd file on the bundled path. Bare spellings only — lib-prefixed
     * spellings ("else/sort") are registered by no shipped .so, so pd
     * always reaches the file there and the session builder inlines it.
     * Feeds PdObjectMeta.pdClasses via addCompiledNative.
     */
    fun compiledNativeClasses(context: Context): Set<String> {
        val files = pdFileNames(context)
        val out = HashSet<String>()
        for ((_, cls) in readManifest(context)) {
            if (cls.contains('/') || cls in files) continue
            out.add(cls)
        }
        return out
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(target, child))
        }
    }
}
