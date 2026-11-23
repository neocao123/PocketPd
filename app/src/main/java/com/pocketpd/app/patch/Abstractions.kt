package com.pocketpd.app.patch

import java.io.File

/**
 * Resolves abstraction names to their .pd source files and extracts what the
 * editor needs beyond ports: the graph-on-parent window (for drawing the
 * abstraction's own widgets on the parent box) and the full depth-1 object
 * list (for inlining GOP abstractions into interactive pd sessions).
 *
 * Mirrors pd's lookup: the opened patch's directory first, then the search
 * path in registration order; `lib/name` resolves to `<dir>/lib/name.pd`.
 * Results are cached per name; [setSearchDirs] is called whenever the patch
 * or the extraction folders change (see Externals.refreshIndex).
 */
object Abstractions {

    /** Everything the editor knows about one abstraction file. */
    class Info(
        @JvmField val file: File,
        /** `#X coords` with gop != 0, or null when not graph-on-parent. */
        @JvmField val coords: GraphCoords?,
        /** GOP visible-window origin in the abstraction's canvas coordinates. */
        @JvmField val mx: Int,
        @JvmField val my: Int,
        /** Depth-1 objects in creation order (inlet/outlet objects included). */
        @JvmField val objects: List<PatchObject>,
        /** Depth-1 connections (ids refer to [objects]). */
        @JvmField val connections: List<PatchConnection>,
    ) {
        val inlets: List<PatchObject> =
            objects.filter { it.kind == ObjKind.OBJECT && it.className() in setOf("inlet", "inlet~") }
        val outlets: List<PatchObject> =
            objects.filter { it.kind == ObjKind.OBJECT && it.className() in setOf("outlet", "outlet~") }

        /** Depth-1 comment/atom/gui objects pd draws inside the GOP window. */
        fun gopObjects(): List<PatchObject> = objects.filter { o ->
            o.kind == ObjKind.COMMENT ||
                o.kind == ObjKind.FLOATATOM || o.kind == ObjKind.SYMBOLATOM ||
                o.kind == ObjKind.LISTBOX ||
                (o.kind == ObjKind.OBJECT && PdObjectMeta.isIemGui(o.className()))
        }
    }

    @Volatile
    private var searchDirs: List<File?> = emptyList()

    private val cache = HashMap<String, Info?>()

    fun setSearchDirs(dirs: List<File?>) {
        searchDirs = dirs
        cache.clear()
    }

    /** Drops cached parses (after an abstraction source was edited + saved). */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * Resolves [name] (bare or `lib/name`) to abstraction info, or null when
     * no such file is on the path (compiled classes return null here).
     */
    fun resolve(name: String): Info? {
        if (name.isEmpty() || name.startsWith("\$")) return null
        if (!name.matches(Regex("[A-Za-z0-9_/.+\\-!=<>~]+"))) return null
        synchronized(cache) {
            if (cache.containsKey(name)) return cache[name]
            val info = locate(name)?.let { parseInfo(it) }
            cache[name] = info
            return info
        }
    }

    private fun locate(name: String): File? {
        val rel = "$name.pd"
        for (dir in searchDirs) {
            if (dir == null || !dir.isDirectory) continue
            val f = File(dir, rel)
            if (f.isFile) return f
            // 0.18.2: prefixed names (`else/count`) live next to the lib dir,
            // not inside it — else/count.pd sits at <root>/else/count.pd while
            // the search dir IS <root>/else. Try the parent for `lib/name`
            // spellings so abstractions referencing `else/xxx` (arp.pd's
            // else/count, else/swap2, else/makenote2, else/tempo) inline
            // correctly now that the RC15 core stopped registering those as
            // compiled classes (the 0.17.153 prefix-strip no longer fires
            // for them: the bare names left the new core_classes.txt).
            if (rel.contains('/')) {
                val p = dir.parentFile?.let { pf -> File(pf, rel) }
                if (p != null && p.isFile) return p
            }
        }
        return null
    }

    private fun parseInfo(file: File): Info? = runCatching {
        val errors = mutableListOf<String>()
        val doc = PatchDocument.parse(file.readText(), errors)
        val coords = topLevelCoords(file)
        // GOP window origin = pd's gl_xmargin/gl_ymargin (trailing coords args)
        val (mx, my) = gopMargin(file)
        Info(
            file = file,
            coords = coords?.takeIf { it.gop != 0 },
            mx = mx,
            my = my,
            objects = doc.objects(),
            connections = doc.connections,
        )
    }.onFailure {
        // 0.18.5 diagnostic: a parse throw here silently nulls resolve(),
        // which the inline gate reads as "not an abstraction" - the box
        // then stays native and pd's own file lookup loads it (0.18.3's
        // else/tempo mystery). Name the file and the exception.
        android.util.Log.w(
            "PocketPd.Pd",
            "abstraction parse FAILED: $file: ${it.message}",
        )
    }.getOrNull()

    private fun topLevelCoords(file: File): GraphCoords? {
        var depth = 0
        for (line in file.readLines()) {
            val t = line.trimEnd(';').trim().split(Regex("\\s+"))
            when {
                t.getOrNull(0) == "#N" && t.getOrNull(1) == "canvas" -> depth++
                t.getOrNull(0) == "#X" && t.getOrNull(1) == "restore" -> depth--
                t.getOrNull(0) == "#X" && t.getOrNull(1) == "coords" && depth <= 1 -> {
                    fun f(i: Int) = t.getOrNull(i)?.toFloatOrNull()
                    return GraphCoords(
                        xmin = f(2) ?: 0f,
                        ymax = f(3) ?: 1f,
                        xmax = f(4) ?: 1f,
                        ymin = f(5) ?: 0f,
                        width = (f(6) ?: 200f).toInt().coerceAtLeast(1),
                        height = (f(7) ?: 100f).toInt().coerceAtLeast(1),
                        gop = (f(8) ?: 1f).toInt(),
                    )
                }
            }
        }
        return null
    }

    /** GOP margin (pd gl_xmargin/gl_ymargin) from the raw coords line args 9/10. */
    private fun gopMargin(file: File): Pair<Int, Int> {
        var depth = 0
        for (line in file.readLines()) {
            val t = line.trimEnd(';').trim().split(Regex("\\s+"))
            when {
                t.getOrNull(0) == "#N" && t.getOrNull(1) == "canvas" -> depth++
                t.getOrNull(0) == "#X" && t.getOrNull(1) == "restore" -> depth--
                t.getOrNull(0) == "#X" && t.getOrNull(1) == "coords" && depth <= 1 ->
                    return ((t.getOrNull(9)?.toFloatOrNull() ?: 0f).toInt()) to
                        ((t.getOrNull(10)?.toFloatOrNull() ?: 0f).toInt())
            }
        }
        return 0 to 0
    }
}
