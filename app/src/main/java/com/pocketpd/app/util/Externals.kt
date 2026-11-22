package com.pocketpd.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketpd.app.patch.AbstractionPorts
import com.pocketpd.app.patch.Abstractions
import com.pocketpd.app.patch.PdObjectMeta
import com.pocketpd.app.runtime.PdEngine
import org.puredata.core.PdBase
import java.io.File

/**
 * The app's externals convention, mirroring desktop Pd's user path: .pd files
 * (abstractions with agreed inlets/outlets) placed in these directories can be
 * instantiated by name from ANY patch.
 *
 *  - [internalDir] `files/externals` is the "Import" target, always writable.
 *  - [externalDir] `Android/data/<pkg>/files/externals` is reachable from a PC
 *    over adb/MTP for dropping files without the picker.
 *
 * Both are added to libpd's search path at startup (in that order), and their
 * contents are scanned into [AbstractionPorts] so the canvas draws the
 * abstractions' real ports.
 */
object Externals {

    /**
     * User-imported abstractions: the shared-storage working directory's
     * externals folder (/sdcard/pocketPD/externals, WorkStore). 0.17.145
     * replaced the old filesDir + Android/data twins - imported .pd files
     * are user work and must survive app uninstall (data-loss incident).
     */
    fun internalDir(context: Context): File = WorkStore.externalsDir(context)

    /** Single externals location since 0.17.145 (was an app-storage twin). */
    fun externalDir(context: Context): File? = null

    /**
     * Directories abstraction/help lookups consult, in pd's resolution order:
     * the opened patch's directory (when present) beats everything, then the
     * search-path registration order from [registerSearchPaths]. The official
     * example abstractions rank above the bundled libraries so `[output~]`
     * resolves to the interactive GOP box of the doc examples (the only name
     * the two sets share); the iemlib twin stays reachable as
     * `[iemlib/output~]`.
     */
    fun searchDirs(context: Context, patchDir: File? = null): List<File?> = listOf(
        patchDir,
        internalDir(context),
        externalDir(context),
        Examples.abstractionDir(context),
    ) + Slibs.libDirs(context) + listOf(
        Slibs.dir(context),
        BuiltinExts.dir(context),
    )

    /** Registers all externals directories in libpd's abstraction search path. */
    fun registerSearchPaths(context: Context) {
        val dirs = searchDirs(context).filterNotNull()
        synchronized(PdBase::class.java) {
            PdBase.clearSearchPath()
            for (dir in dirs) PdBase.addToSearchPath(dir.absolutePath)
        }
        // Java-side resolvers (AbstractionPorts, Abstractions) follow the same order.
        Abstractions.setSearchDirs(dirs)
    }

    /** Drops cached abstraction parses (call after a source file was saved). */
    fun invalidateAbstractions() = Abstractions.clearCache()

    /** All abstraction files currently available, internal directory first. */
    fun list(context: Context): List<File> {
        val out = (internalDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".pd") }
            ?: emptyArray()).toMutableList()
        val ext = externalDir(context)
            ?.listFiles { f -> f.isFile && f.name.endsWith(".pd") }
            ?: emptyArray()
        for (f in ext) if (out.none { it.name == f.name }) out.add(f)
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Copies picked documents into the internal directory. A file whose name
     * lacks the .pd suffix gets one appended (pd resolves `<name>.pd` only).
     */
    fun importFrom(context: Context, uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val dir = internalDir(context)
        var ok = 0
        for (uri in uris) {
            runCatching {
                val base = queryDisplayName(context, uri) ?: return@runCatching
                val name = base.replace(File.separatorChar, '_').let {
                    if (it.endsWith(".pd", true)) it else "$it.pd"
                }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching
                ok++
                PdEngine.log("externals: imported $name")
            }.onFailure { PdEngine.log("externals: import failed: ${it.message}") }
        }
        if (ok > 0) refreshIndex(context)
        return ok
    }

    fun delete(file: File): Boolean {
        val ok = file.delete()
        PdEngine.log(if (ok) "externals: removed ${file.name}" else "externals: remove failed: ${file.name}")
        return ok
    }

    /**
     * Rebuilds the port index from the opened patch's directory (optional)
     * plus both externals directories. With a patch dir, reports the index
     * size on the console so users can check their abstractions were found.
     */
    fun refreshIndex(context: Context, patchDir: File? = null) {
        val dirs = searchDirs(context, patchDir)
        AbstractionPorts.scan(dirs)
        // the GOP resolver must see the very same dirs (the opened patch's
        // siblings first) — otherwise patch-dir abstractions never resolve,
        // never draw as GOP boxes and never inline into RUN sessions
        Abstractions.setSearchDirs(dirs)
        PdObjectMeta.knownClasses.addAll(AbstractionPorts.classNames())
        if (patchDir != null) {
            PdEngine.log("externals: ${AbstractionPorts.count()} abstraction port definition(s) indexed")
        }
    }

    /**
     * Background variant of [refreshIndex] for the every-resume editor
     * refresh: the scan reads hundreds of files, which must not run against
     * the input-focus budget of a just-resumed window. The index swap is
     * atomic (single map assignment); [onReady] runs on the main thread
     * after the new index is visible.
     */
    fun refreshIndexAsync(
        context: Context,
        patchDir: File? = null,
        onReady: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val dirs = searchDirs(app, patchDir)
        Thread {
            AbstractionPorts.scan(dirs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Abstractions.setSearchDirs(dirs)
                PdObjectMeta.knownClasses.addAll(AbstractionPorts.classNames())
                onReady?.invoke()
            }
        }.start()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
