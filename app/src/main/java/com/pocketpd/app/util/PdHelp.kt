package com.pocketpd.app.util

import android.content.Context
import com.pocketpd.app.patch.PatchObject
import java.io.File

/**
 * Object "Open" and "Help" lookups, desktop-pd style — two distinct things:
 *
 *  - [findSource] ("Open") resolves an abstraction to its own source file
 *    ([output~] → output~.pd). Only abstractions have one; compiled classes
 *    like [osc~] return null and the menu item grays out.
 *  - [find] ("Help") only matches help-named patches — `<name>-help.pd` /
 *    `help-<name>.pd` — from the bundled help folders (vanilla 5.reference
 *    plus one folder per pd-extended library, assembled by slibs/
 *    build_help.py). When a class maps to a library (Slibs manifest), that
 *    library's folder wins, mirroring which library actually provides the
 *    bare name at runtime. No help patch → null → gray item; there is
 *    deliberately NO fallback to the abstraction source (that is "Open").
 */
object PdHelp {

    /** Bump to re-extract the bundled help tree after an asset update. */
    private const val VERSION = 26

    fun dir(context: Context): File = File(context.filesDir, "help")

    /** True when [file] lives inside the extracted help tree (read-only). */
    fun isHelpPath(file: File, context: Context): Boolean {
        val root = dir(context).canonicalPath + File.separator
        return file.canonicalPath.startsWith(root)
    }

    fun ensureExtracted(context: Context) {
        val marker = File(context.filesDir, ".help-v$VERSION")
        if (marker.exists()) return
        val out = dir(context)
        out.deleteRecursively()
        out.mkdirs()
        runCatching {
            copyAssetDir(context, "help", out)
            marker.writeText("ok")
        }.onFailure { out.deleteRecursively() }
    }

    /** Number of help patches available (for the console line). */
    fun count(context: Context): Int =
        dir(context).listFiles { f -> f.isDirectory }
            ?.sumOf { d -> d.listFiles { g -> g.isFile && g.name.endsWith(".pd") }?.size ?: 0 } ?: 0

    /**
     * The abstraction source file behind an object ([output~] → output~.pd,
     * `[cyclone/foo]` → cyclone/foo.pd), or null when the class is compiled
     * / built-in and thus has nothing to open.
     */
    fun findSource(obj: PatchObject): File? {
        if (obj.kind != com.pocketpd.app.patch.ObjKind.OBJECT) return null
        val name = obj.className()
        if (name.isEmpty() || name == "pd") return null // subpatches are inline blocks
        return com.pocketpd.app.patch.Abstractions.resolve(name)?.file
    }

    /**
     * Finds the help patch for an object, or null when there is none (the
     * caller grays the menu item out).
     */
    fun find(context: Context, obj: PatchObject): File? {
        val full = obj.className()
        if (full.isEmpty() || obj.kind !in setOf(
                com.pocketpd.app.patch.ObjKind.OBJECT,
                com.pocketpd.app.patch.ObjKind.MSG,
            )
        ) return null
        val lib = full.substringBefore('/', "").takeIf { full.contains('/') }
        val bare = full.substringAfterLast('/')

        // help folders: the owning library first, then vanilla, then the rest
        val folders = mutableListOf<File>()
        val root = dir(context)
        lib?.let { folders.add(File(root, it)) }
        Slibs.classToLib(context)[bare]?.let { folders.add(File(root, it)) }
        folders.add(File(root, "vanilla"))
        for (l in Slibs.PRIORITY) folders.add(File(root, l))
        for (folder in folders) {
            findIn(folder, bare)?.let { return it }
        }
        return null
    }

    /** `<name>-help.pd`, `help-<name>.pd`, case-insensitive on the stem. */
    private fun findIn(dir: File, name: String): File? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".pd") } ?: return null
        for (suffix in listOf("-help.pd", ".pd")) {
            files.firstOrNull { it.name.equals("$name$suffix", true) }?.let { return it }
        }
        files.firstOrNull { it.name.equals("help-$name.pd", true) }?.let { return it }
        return null
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
