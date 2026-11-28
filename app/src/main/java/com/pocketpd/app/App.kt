package com.pocketpd.app

import android.app.Application
import com.pocketpd.app.runtime.MidiBridge
import com.pocketpd.app.runtime.OscBridge
import com.pocketpd.app.runtime.PdEngine
import com.pocketpd.app.runtime.RemoteControl
import com.pocketpd.app.runtime.SerialBridge
import com.pocketpd.app.util.BuiltinExts
import com.pocketpd.app.util.Externals
import com.pocketpd.app.util.PdHelp
import com.pocketpd.app.util.Settings
import com.pocketpd.app.util.Slibs

/** Owns the process-wide Pd runtime, MIDI, serial and OSC bridges. */
class App : Application() {

    val midiBridge: MidiBridge by lazy { MidiBridge(this) }
    val oscBridge: OscBridge by lazy { OscBridge() }
    val serialBridge: SerialBridge by lazy { SerialBridge(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        PdEngine.appContext = this
        PdEngine.init()
        // Convention externals directories must be on the search path before
        // any patch opens, or their abstractions won't instantiate.
        BuiltinExts.ensureExtracted(this)
        Slibs.ensureExtracted(this)
        Slibs.ensureBanks(this)
        PdHelp.ensureExtracted(this)
        com.pocketpd.app.util.Examples.ensureExtracted(this)
        Externals.registerSearchPaths(this)
        Slibs.openAutoload(this)
        com.pocketpd.app.runtime.PdCoreExtras.load(this)
        Externals.refreshIndex(this)
        Slibs.logConflicts(this)
        com.pocketpd.app.patch.PdObjectMeta.knownClasses.addAll(Slibs.knownClasses(this))
        // the compiled-class inlining gate set: manifest names minus the
        // bundled .pd abstractions (must always inline per instance — bare
        // delay/send names cross-wire between two native copies)
        com.pocketpd.app.patch.PdObjectMeta.addCompiledNative(Slibs.compiledNativeClasses(this))
        PdEngine.log("PocketPd: libpd runtime ready (${PdHelp.count(this)} help patches)")

        val settings = Settings.get(this)
        com.pocketpd.app.ui.Bloom.strength = settings.bloomStrength / 100f
        PdEngine.midiOutListener = midiBridge
        // Remote control assembly: the dispatcher borrows the bridge and the
        // settings; the console auto-push rides the same log() hook. The
        // reserved /pocketpd/ namespace loops back through [r OSC] into the
        // local dispatcher (same pairing-code gate) and NEVER leaves the
        // device over the network.
        RemoteControl.bridge = oscBridge
        RemoteControl.settings = settings
        PdEngine.remoteConsoleListener = { line -> RemoteControl.onConsoleLine(line) }
        PdEngine.oscOutListener = OscBridge.OscFromPdListener { msg ->
            if (msg.address.startsWith(RemoteControl.PREFIX)) {
                RemoteControl.handle(msg, sender = null)
                return@OscFromPdListener
            }
            if (settings.oscOutEnabled) {
                oscBridge.send(msg, settings.oscOutHost, settings.oscOutPort)
            }
        }
        applyConnections()
    }

    /**
     * Writes the last uncaught exception to files/crash-last.txt (app
     * private, one file, overwritten each time) before the normal handler
     * runs. The editor sometimes dies mid-interaction on devices we cannot
     * attach logcat to in time; this preserves the stack for debugging.
     */
    private fun installCrashLogger() {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "crash-last.txt").writeText(
                    buildString {
                        append("time: ")
                        append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                            .format(java.util.Date()))
                        append("\nthread: ")
                        append(t.name)
                        append("\nversion: ")
                        append(runCatching {
                            val pi = packageManager.getPackageInfo(packageName, 0)
                            "${pi.versionName} (${pi.longVersionCode})"
                        }.getOrDefault("?"))
                        append("\n\n")
                        append(android.util.Log.getStackTraceString(e))
                    }
                )
            }
            systemHandler?.uncaughtException(t, e)
        }
    }

    /** Applies MIDI/serial/OSC configuration from settings (call after changes).
     *  Each channel is fail-isolated: device machinery has already thrown
     *  system-service NPEs on stale devices, and one bad channel must never
     *  take the app down (Settings crashed on device exactly this way). */
    fun applyConnections() {
        val settings = Settings.get(this)
        runCatching {
            if (settings.midiEnabled) {
                midiBridge.registerForDevices()
                val info = midiBridge.listDevices().firstOrNull { it.id == settings.midiDeviceId }
                if (info != null) {
                    midiBridge.connect(info)
                } else {
                    midiBridge.disconnectUsb()
                }
                val ble = settings.midiBleAddress
                if (ble != null) {
                    midiBridge.connectBle(ble)
                } else {
                    midiBridge.disconnectBle()
                }
            } else {
                midiBridge.unregisterForDevices()
                midiBridge.disconnect()
            }
        }.onFailure { PdEngine.log("midi channel: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            // framing is a live flag on the reader: switching it needs no
            // reconnect
            serialBridge.lineMode = settings.serialLineMode
            if (settings.serialEnabled) {
                val wanted = settings.serialDevice
                // match on the stable vendor:product key, not deviceName
                // (which is reassigned on every re-plug)
                val dev = serialBridge.listDevices()
                    .firstOrNull { SerialBridge.keyOf(it) == wanted }
                if (dev != null) {
                    serialBridge.connect(dev, settings.serialBaud)
                } else {
                    serialBridge.disconnect()
                    if (wanted != null) PdEngine.log("serial: device $wanted not present")
                }
            } else {
                serialBridge.disconnect()
            }
        }.onFailure { PdEngine.log("serial channel: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            if (settings.oscInEnabled) {
                oscBridge.start(settings.oscInPort, settings.oscOutHost, settings.oscOutPort)
            } else {
                oscBridge.stop()
            }
        }.onFailure { PdEngine.log("osc channel: ${it.javaClass.simpleName}: ${it.message}") }
    }
}

val Application.pocketPd: App
    get() = this as App
