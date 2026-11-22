package com.pocketpd.app.runtime

/**
 * Keeps pd's logical clock alive while the audio engine is stopped (0.17.158;
 * rewritten 0.17.159 after the first version rushed every [delay]/[pipe]).
 * pd's control objects measure logical time (pd_systime), which advances only
 * inside sched_tick - on libpd exclusively from the audio render callback.
 * Desktop pd keeps the clock alive without audio (m_callbackscheduler
 * watchdog + the real-time polling scheduler); libpd never runs m_mainloop.
 *
 * Hard rules of the native watchdog (slibs/pdclock/pdclock.c):
 *  - runs ONLY while audio is stopped: [start]/[stop] mirror the audio
 *    engine lifecycle (PdEngine.init / stopAudio / startAudio);
 *  - advances logical time by REAL elapsed wall time (desktop pd's polling
 *    scheduler behavior), never on a fixed cadence, so it can never run the
 *    clock faster than wall time even if the gating is bypassed.
 * Fails soft without the native library.
 */
object PdClock {

    @JvmStatic
    external fun startNative(): Boolean

    @JvmStatic
    external fun stopNative()

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("pdclock"); true }.getOrDefault(false)
    }

    /** Arms the watchdog (audio-off state). Idempotent. */
    fun start() {
        if (!available) return
        runCatching { startNative() }
            .onFailure { PdEngine.log("clock watchdog start failed: ${it.message}") }
    }

    /** Retires the watchdog (audio-on state). Idempotent. */
    fun stop() {
        if (!available) return
        runCatching { stopNative() }
            .onFailure { PdEngine.log("clock watchdog stop failed: ${it.message}") }
    }
}
