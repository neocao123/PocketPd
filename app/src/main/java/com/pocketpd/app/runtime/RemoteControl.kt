package com.pocketpd.app.runtime

import com.pocketpd.app.util.Settings
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * App-level OSC remote control over the shared UDP bridge (master-slave
 * ready): the reserved `/pocketpd/` namespace is intercepted BEFORE the
 * generic pd forward and dispatched here, so reserved addresses never
 * collide with patch `[r …]` receivers.
 *
 * Safety model:
 *  - master switch `settings.remoteCtlEnabled` (default OFF). When off,
 *    reserved addresses fall through to the generic pd forward exactly as
 *    before this class existed — zero regression;
 *  - the bridge itself must also be on (`oscInEnabled`, same socket);
 *  - every command carries the pairing code as its FIRST argument;
 *    comparison is timing-safe. Unauthenticated traffic is dropped
 *    silently — no reply, no oracle;
 *  - an empty stored code refuses to arm (double check here even though
 *    SettingsActivity validates it).
 *
 * Replies go back to the packet's source address, not to the configured
 * OSC out target. A patch can loop back reserved addresses through
 * `[r OSC]` (App intercepts outbound reserved addresses): the same code
 * path, same code gate — loopback messages get no reply (nothing sensible
 * to answer).
 *
 * Console auto-push is independent of the pairing code: one-way telemetry
 * (`/pocketpd/console <line>` to the OSC out target) toggled by
 * `settings.consoleOscEnabled`, wired via [PdEngine.remoteConsoleListener].
 */
object RemoteControl {

    const val PREFIX = "/pocketpd/"

    /** The foreground patch host answers mode/patch queries. Implemented by
     *  EditorActivity (registered onCreate, cleared onDestroy); methods run
     *  on the main thread (OSC inbound is main-posted). */
    interface PatchController {
        /** "edit" | "run" | "lock" */
        fun modeState(): String

        /** "edit" | "run" | "lock" | "unlock" (leave LOCK → EDIT). Returns
         *  null on success, an error text otherwise. */
        fun setMode(request: String): String?

        /** Display name of the open patch (or "unsaved"). */
        fun patchName(): String
    }

    @Volatile var bridge: OscBridge? = null
    @Volatile var settings: Settings? = null
    @Volatile var controller: PatchController? = null

    /**
     * Dispatches one inbound message. @return true when consumed (the
     * caller must NOT forward it to pd). Round 1 surface: hello + mode;
     * patch get/set and console/get land in the next round.
     */
    fun handle(msg: OscCodec.OscMessage, sender: InetSocketAddress?): Boolean {
        if (!msg.address.startsWith(PREFIX)) return false
        val s = settings
        if (s == null || !s.remoteCtlEnabled || !s.oscInEnabled) return false
        val expected = s.remoteCtlCode
        if (expected.isEmpty()) {
            PdEngine.log("remote: enabled but no pairing code set - ${msg.address} dropped")
            return true
        }
        val given = (msg.args.firstOrNull() as? String) ?: ""
        if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
            PdEngine.log("remote: bad code from ${sender?.address?.hostAddress} for ${msg.address}")
            return true
        }
        val cmd = msg.address.removePrefix(PREFIX)
        val args = msg.args.drop(1)
        when (cmd) {
            "hello" -> reply("/pocketpd/info", infoArgs(), sender)
            "mode" -> {
                val c = controller
                val req = (args.firstOrNull() as? String) ?: ""
                if (c == null) {
                    reply("/pocketpd/error", listOf("no editor open"), sender)
                } else {
                    val err = c.setMode(req)
                    if (err == null) reply("/pocketpd/ok", listOf("mode", c.modeState()), sender)
                    else reply("/pocketpd/error", listOf(err), sender)
                }
            }
            else -> reply("/pocketpd/error", listOf("unknown '$cmd'"), sender)
        }
        return true
    }

    private fun infoArgs(): List<String> {
        val c = controller
        return if (c == null) listOf("no-editor", "", "")
        else listOf(c.modeState(), c.patchName())
    }

    /** Replies go to the packet source; loopback (sender == null) is silent. */
    private fun reply(address: String, args: List<Any>, sender: InetSocketAddress?) {
        val b = bridge ?: return
        val s = sender ?: return
        b.send(OscCodec.OscMessage(address, args), s.address?.hostAddress ?: return, s.port)
    }

    /** Console auto-push to the OSC out target (settings-gated, no code). */
    fun onConsoleLine(line: String) {
        val s = settings ?: return
        if (!s.consoleOscEnabled) return
        val b = bridge ?: return
        b.send(OscCodec.OscMessage("$PREFIX/console", listOf(line)), s.oscOutHost, s.oscOutPort)
    }
}
