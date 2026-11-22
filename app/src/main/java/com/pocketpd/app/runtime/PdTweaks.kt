package com.pocketpd.app.runtime

/**
 * Small native tweaks to the bundled libpd runtime, kept out of libpd itself.
 * Backed by jniLibs/<abi>/libpdtweaks.so; fails soft when the library is
 * unavailable so the app keeps running without the tweaks.
 */
object PdTweaks {

    /** Loads a bundled library core: dlopens the .so and runs its setup
     *  function (same as pd's external loader). Returns true on success. */
    @JvmStatic
    external fun nativeLoadCore(path: String, setupSymbol: String): Boolean

    @JvmStatic
    external fun setLoadbangEnabled(on: Boolean)

    @JvmStatic
    external fun getNoloadbang(): Int

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("pdtweaks"); true }.getOrDefault(false)
    }
}
