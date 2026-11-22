package com.pocketpd.app.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Patch storage: the shared-storage working directory /sdcard/pocketPD
 * (WorkStore, user directive after the 2026-11-21 data-loss incident -
 * app-scoped storage dies with the app, user patches must not). Seeded
 * from bundled samples; legacy pre-0.17.145 locations migrate on launch.
 */
object PatchStore {

    fun patchesDir(context: Context): File = WorkStore.pdRoot(context)

    fun list(context: Context): List<File> =
        patchesDir(context).listFiles { f -> f.isFile && f.name.endsWith(".pd") && !f.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun create(context: Context, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        // only an extension-LESS name is a patch name — append .pd there; a
        // name that already carries one keeps it verbatim: a .vgz import
        // must stay song.vgz or vgm~'s `load song.vgz` cannot resolve it
        val fileName = if (safe.contains('.')) safe else "$safe.pd"
        return File(patchesDir(context), fileName)
    }

    /** Copies a .pd chosen via SAF into the patches dir; returns null on failure. */
    fun import(context: Context, uri: Uri): File? = runCatching {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "imported.pd"
        val target = create(context, displayName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target
    }.getOrNull()

    /** Depth-first collect of every document URI inside a SAF tree. */
    private fun walkTree(context: Context, treeUri: Uri, docId: String, out: MutableList<Uri>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val mime = c.getString(1)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(context, treeUri, id, out)
                } else {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                }
            }
        }
    }

    /**
     * Copies every file of a picked folder (recursively, subfolders flattened)
     * into the patches dir; returns the number of successfully imported files.
     */
    fun importTree(context: Context, treeUri: Uri): Int = runCatching {
        val files = mutableListOf<Uri>()
        walkTree(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), files)
        files.count { import(context, it) != null }
    }.getOrDefault(0)

    /** Copies bundled sample patches on first launch. */
    fun extractSamples(context: Context) {
        val marker = File(context.filesDir, ".samples-v1")
        if (marker.exists()) return
        runCatching {
            val assets = context.assets.list("patches") ?: emptyArray()
            for (name in assets) {
                if (!name.endsWith(".pd")) continue
                val target = File(patchesDir(context), name)
                if (!target.exists()) {
                    context.assets.open("patches/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            marker.createNewFile()
        }
    }
}
