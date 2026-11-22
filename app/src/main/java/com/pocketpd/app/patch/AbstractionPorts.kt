package com.pocketpd.app.patch

import java.io.File

/**
 * Port counts for abstractions (.pd files instantiated as objects). Pd resolves
 * an object name to `<name>.pd` in the opened patch's directory first, then the
 * global search path; this index mirrors that lookup so the canvas draws the
 * same ports pd will create. Rebuilt by [com.pocketpd.app.util.Externals]
 * whenever the patch or the externals folders change.
 */
object AbstractionPorts {

    /** Port layout of one abstraction; signal sets hold port indices. */
    data class Ports(
        val inlets: Int,
        val outlets: Int,
        val signalInlets: Set<Int>,
        val signalOutlets: Set<Int>,
    )

    @Volatile
    private var index: Map<String, Ports> = emptyMap()

    /**
     * Rebuilds the index from the given directories in pd's resolution order:
     * earlier directories win a name conflict, mirroring "canvas dir beats
     * search path". Directories may be null/missing and are skipped.
     *
     * Files inside a bundled library directory (files/slibs/<lib>/) are also
     * indexed under their namespaced spelling (`<lib>/<name>`), so
     * `[diy2/st-buffery]`-style boxes draw the same ports as their bare
     * `[st-buffery]` twins.
     */
    fun scan(dirs: List<File?>) {
        val map = HashMap<String, Ports>()
        for (dir in dirs) {
            if (dir == null || !dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pd") } ?: continue
            val nsPrefix = dir.parentFile?.takeIf { it.name == "slibs" }?.let { "${dir.name}/" } ?: ""
            for (f in files) {
                val ports = parse(f) ?: continue
                val name = f.name.removeSuffix(".pd")
                map.putIfAbsent(name, ports)
                if (nsPrefix.isNotEmpty()) map.putIfAbsent(nsPrefix + name, ports)
            }
        }
        index = map
    }

    fun lookup(name: String): Ports? = index[name]

    /** All indexed names, including lib/name spellings. */
    fun classNames(): Set<String> = index.keys

    fun count(): Int = index.size

    /** One-line listing of the index, for the console: "hgain:2/1 sig[0]/[0]". */
    fun summary(): String = index.entries.sortedBy { it.key }
        .filter { (name, _) -> !name.contains('/') } // namespaced twins stay out of the log
        .joinToString(" ") { (name, p) ->
            val sig = if (p.signalInlets.isEmpty() && p.signalOutlets.isEmpty()) ""
            else " sig${p.signalInlets}/${p.signalOutlets}"
            "$name:${p.inlets}/${p.outlets}$sig"
        }

    /**
     * Counts the abstraction's top-level [inlet]/[inlet~]/[outlet]/[outlet~]
     * objects. Only canvas depth 0 counts — inlets inside a subpatch belong to
     * that subpatch, not to the abstraction's interface.
     *
     * Runs on the UI thread over hundreds of files on every resume, so this
     * deliberately avoids regex/allocations: plain prefix checks and a plain
     * character split (pd writes single-space separated tokens).
     */
    private fun parse(file: File): Ports? = runCatching {
        var depth = 0
        var inlets = 0
        var outlets = 0
        val sigIn = mutableListOf<Int>()
        val sigOut = mutableListOf<Int>()
        for (line in file.readLines()) {
            when {
                line.startsWith("#N canvas") -> depth++
                line.startsWith("#X restore") -> depth--
                line.startsWith("#X obj") && depth <= 1 -> {
                    // "#X obj x y <class> ..." — class token is index 4, and pd
                    // writes the terminating ";" glued to the last token.
                    when (line.trim().split(' ').filter { it.isNotEmpty() }
                        .getOrNull(4)?.trimEnd(';')) {
                        "inlet" -> inlets++
                        "inlet~" -> { sigIn.add(inlets); inlets++ }
                        "outlet" -> outlets++
                        "outlet~" -> { sigOut.add(outlets); outlets++ }
                    }
                }
            }
        }
        if (inlets == 0 && outlets == 0) null else Ports(inlets, outlets, sigIn.toSet(), sigOut.toSet())
    }.getOrNull()
}
