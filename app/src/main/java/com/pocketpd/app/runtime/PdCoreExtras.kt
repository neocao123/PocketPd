package com.pocketpd.app.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * The vanilla "extra" objects libpd ships as separate .so files inside the
 * pd-core AAR (sigmund~, fiddle~, bonk~, bob~, loop~, lrshift~, pique,
 * choice, stdout). pd's binary loader never finds them — and neither does a
 * direct dlopen: with extractNativeLibs=false (AGP default) the app's
 * nativeLibraryDir is an empty stub and the only real copies live inside the
 * base APK, whose names (lib<class>_tilde.so) match none of pd's loader
 * variants anyway. Every one of these boxes printed "couldn't create"
 * (sigmund~ patches were dead on arrival).
 *
 * Loads each one the way Slibs loads its cores: extract the .so from the
 * APK into a private directory, then run its setup through
 * [PdTweaks.nativeLoadCore] (dlopen + dlsym + call). The extras link
 * against libpd.so, which is already loaded in the process, so the soname
 * resolves. Must run after libpd is initialized (class_new needs pd).
 */
object PdCoreExtras {

    /** pd-core .so file name (between lib and .so) → exported setup symbol. */
    private val EXTRAS = listOf(
        "sigmund_tilde" to "sigmund_tilde_setup",
        "fiddle_tilde" to "fiddle_tilde_setup",
        "bonk_tilde" to "bonk_tilde_setup",
        "bob_tilde" to "bob_tilde_setup",
        "loop_tilde" to "loop_tilde_setup",
        "lrshift_tilde" to "lrshift_tilde_setup",
        "pique" to "pique_setup",
        "choice" to "choice_setup",
        "stdout" to "stdout_setup",
    )

    fun load(context: Context) {
        if (!PdTweaks.available) {
            PdEngine.log("pd-core extras: native loader unavailable, skipped")
            return
        }
        var ok = 0
        runCatching {
            val outDir = File(context.filesDir, "pdcore").apply { mkdirs() }
            ZipFile(File(context.applicationInfo.sourceDir)).use { zip ->
                for ((name, setup) in EXTRAS) {
                    val entry = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                        zip.getEntry("lib/$abi/lib$name.so")
                    }
                    if (entry == null) {
                        PdEngine.log("pd-core extras: lib$name.so not packaged")
                        continue
                    }
                    val out = File(outDir, "lib$name.so")
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (PdTweaks.nativeLoadCore(out.absolutePath, setup)) ok++
                    else PdEngine.log("pd-core extras: $name setup failed")
                }
            }
        }.onFailure {
            PdEngine.log("pd-core extras: extraction failed: ${it.message}")
        }
        PdEngine.log("pd-core extras: $ok/${EXTRAS.size} vanilla externals loaded")
    }
}
