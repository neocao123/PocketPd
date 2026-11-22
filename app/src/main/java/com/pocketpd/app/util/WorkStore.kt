package com.pocketpd.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.pocketpd.app.runtime.PdEngine
import java.io.File

/**
 * User pd-file storage (0.17.145, after the 2026-11-21 data-loss incident:
 * a connected test uninstalled the app and Android deleted the whole
 * app-scoped directory - user patches were stored inside it and died with
 * it). USER DIRECTIVE: every pd file the user can write lives OUTSIDE app
 * storage, in a shared-storage working directory, NEVER in filesDir.
 *
 * Primary root: /sdcard/pocketPD (shared storage, survives uninstall,
 * visible in any file manager). Writing there needs All-Files-Access on
 * Android 11+ (MANAGE_EXTERNAL_STORAGE, granted from system settings) and
 * WRITE_EXTERNAL_STORAGE on 10 and below (plus requestLegacyExternalStorage).
 *
 * Until the grant lands the app falls back to getExternalFilesDir/pocketPD
 * - still external and user-visible over USB, still wiped by uninstall, so
 * the settings dialog keeps nagging until the grant is in. filesDir is
 * never used for pd files.
 *
 * Derived caches (slibs/ymbanks/help/examples extraction, session files)
 * stay in app storage on purpose: they are rebuilt from assets on every
 * version bump, hold no user work, and thousands of small FUSE files on
 * shared storage would slow the audio thread's library loading.
 */
object WorkStore {

    /** Working-directory name on shared storage (user directive). */
    const val DIR_NAME = "pocketPD"

    fun workRoot(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    fun externalsDir(context: Context): File =
        File(pdRoot(context), "externals").apply { mkdirs() }

    /** Where pd files live right now: the shared-storage root once the
     *  grant is in, the app-specific external dir before that. */
    fun pdRoot(context: Context): File =
        (if (storageGranted(context)) workRoot() else legacyFallback(context))
            .apply { mkdirs() }

    /** Last-resort pd location while All-Files-Access is not granted:
     *  app-specific EXTERNAL storage (never filesDir - user directive).
     *  It is wiped by uninstall, which is exactly why the MainActivity
     *  dialog keeps asking for the grant. */
    private fun legacyFallback(context: Context): File =
        File(context.getExternalFilesDir(null), DIR_NAME)

    fun storageGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * One-time migration of pd files written by pre-0.17.145 builds into
     * the new working directory. Copies without overwriting; runs cheaply
     * on every launch (a marker file short-circuits the scan).
     */
    fun migrateLegacy(context: Context) {
        val root = pdRoot(context)
        val marker = File(root, ".migrated-0.17.145")
        if (marker.exists()) return
        runCatching {
            val legacyPatchDirs = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, "Patches") },
                File(context.filesDir, "Patches"),
            )
            val legacyExternalDirs = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, "externals") },
                File(context.filesDir, "externals"),
            )
            var moved = 0
            for (dir in legacyPatchDirs) {
                val files = dir.listFiles { f -> f.isFile } ?: continue
                for (f in files) {
                    val target = File(root, f.name)
                    if (!target.exists() && f.renameTo(target)) moved++
                }
            }
            val extTarget = File(root, "externals").apply { mkdirs() }
            for (dir in legacyExternalDirs) {
                val files = dir.listFiles { f -> f.isFile } ?: continue
                for (f in files) {
                    val target = File(extTarget, f.name)
                    if (!target.exists() && f.renameTo(target)) moved++
                }
            }
            marker.writeText("ok")
            if (moved > 0) PdEngine.log("workstore: migrated $moved pd file(s) to ${root.path}")
        }
    }
}
