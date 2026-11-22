package com.pocketpd.app.util

import android.content.Context
import java.io.File

/**
 * The bundled official Pure Data example patches (doc/2.control.examples,
 * doc/3.audio.examples plus their abstraction/sound dependencies), shipped
 * as assets. 0.17.146 (USER DIRECTIVE, same round as the /sdcard/pocketPD
 * storage move): the example tree now lives in the shared working directory
 * - <pdRoot>/examples - like every other pd file, so the bundled cases are
 * visible in any file manager and survive app uninstall. pd resolves
 * sibling abstractions and relative sound paths against the opened file's
 * directory, so the whole tree must be materialized on disk.
 *
 * Extraction is NON-DESTRUCTIVE: per-file copy-if-missing. Re-extraction
 * (VERSION bump) adds new files and never deletes or overwrites - the
 * shared-storage tree is user-visible and may carry user edits. The old
 * internal filesDir/examples tree from pre-0.17.146 builds migrates once
 * into the working directory the same way.
 *
 * The editor still treats example paths as read-only: saving from the
 * editor goes through "Save as…" into the user's patch collection.
 */
object Examples {

    /** Bump to re-scan the bundled tree after an asset update (additive). */
    private const val VERSION = 60

    fun dir(context: Context): File = File(WorkStore.pdRoot(context), "examples")

    /**
     * The official example abstractions ([output~], [adsr], voices, filters…)
     * live in the audio examples folder and are on the search path, so any
     * patch can instantiate them by name like desktop pd's doc examples.
     */
    fun abstractionDir(context: Context): File = File(dir(context), "audio")

    fun isExamplePath(file: File, context: Context): Boolean {
        val root = dir(context).canonicalPath + File.separator
        return file.canonicalPath.startsWith(root)
    }

    /** Extracts the bundled tree (or after a VERSION bump: adds new files). */
    fun ensureExtracted(context: Context) {
        val out = dir(context)
        val marker = File(WorkStore.pdRoot(context), ".examples-v$VERSION")
        if (!marker.exists()) {
            // one-time migration of the pre-0.17.146 internal tree
            runCatching {
                copyMissing(File(context.filesDir, "examples"), out)
            }
        }
        runCatching {
            copyMissingAssets(context, "examples", out)
            marker.writeText("ok")
        }
    }

    /** Categories (subdirectories) that contain at least one patch. */
    fun categories(context: Context): List<String> =
        dir(context).listFiles { f -> f.isDirectory }
            ?.filter { d ->
                // "5.reference" ships as a dependency for ../5.reference
                // abstraction references; it is not a browsable category.
                d.name != "5.reference" &&
                    d.listFiles { f -> f.isFile && f.name.endsWith(".pd") }?.isNotEmpty() == true
            }
            ?.map { it.name }
            ?.sorted() ?: emptyList()

    fun patches(context: Context, category: String): List<File> =
        File(dir(context), category).listFiles { f -> f.isFile && f.name.endsWith(".pd") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Copies [src]'s regular files into [dst], never overwriting. */
    private fun copyMissing(src: File, dst: File) {
        if (!src.isDirectory) return
        dst.mkdirs()
        for (f in src.listFiles() ?: emptyArray()) {
            val target = File(dst, f.name)
            if (f.isDirectory) copyMissing(f, target)
            else if (!target.exists()) f.copyTo(target)
        }
    }

    /** Asset-tree equivalent of [copyMissing]: adds files that are absent. */
    private fun copyMissingAssets(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyMissingAssets(context, "$assetPath/$child", File(target, child))
        }
    }
}
