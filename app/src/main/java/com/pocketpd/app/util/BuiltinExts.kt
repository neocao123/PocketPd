package com.pocketpd.app.util

import android.content.Context
import java.io.File

/**
 * Built-in abstractions that ship with the app, currently the phone-sensor
 * objects ([accel], [gyro], [magnet], [light], …). Extracted from assets into
 * internal storage and added to libpd's search path AFTER the user's externals
 * dirs, so a user file with the same name wins, pd-style.
 */
object BuiltinExts {

    /** Bump to re-extract after an asset update (wipes stale old files). */
    private const val VERSION = 2

    fun dir(context: Context): File = File(context.filesDir, "sensors")

    fun ensureExtracted(context: Context) {
        val marker = File(context.filesDir, ".sensors-v$VERSION")
        if (marker.exists()) return
        val out = dir(context)
        out.deleteRecursively()
        out.mkdirs()
        runCatching {
            for (name in context.assets.list("sensors") ?: emptyArray()) {
                if (!name.endsWith(".pd")) continue
                context.assets.open("sensors/$name").use { input ->
                    File(out, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            marker.writeText("ok")
        }
    }
}
