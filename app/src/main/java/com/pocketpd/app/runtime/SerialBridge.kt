package com.pocketpd.app.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.puredata.core.PdBase
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OTG USB serial bridge (usb-serial-for-android: CDC-ACM, FTDI, CP210x,
 * CH340, PL2303, ...). A pure byte transport - unrelated to MIDI and OSC.
 *
 * Convention mirrors the OSC bridge's receiver style:
 *
 *  wire -> `[r tty0]`:   LINE mode (default): each newline-terminated line
 *                        parsed into atoms — a println'd number is one
 *                        float, "a 1 2" a list, a word a symbol.
 *                        BYTE mode: every USB read chunk as a list of
 *                        floats in 0..255 (one float per byte), unframed.
 *  `[s TTY]` -> wire:    a list (or single float) sent to `TTY` is written
 *                        to the port, values taken mod 256
 *
 * Reading runs on a daemon thread; writes go through a single-thread
 * executor so pd (audio thread) never blocks on USB I/O. The TTY receiver
 * rides the app's global pd dispatcher ([PdEngine.ttyOutListener]) - libpd
 * has no per-symbol bind.
 */
class SerialBridge(private val context: Context) {

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null
    @Volatile
    private var reading = AtomicBoolean(false)
    private var readThread: Thread? = null
    private var deviceKey: String? = null
    private var bound = false

    private val writer: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "serial-write").apply { isDaemon = true }
        }

    fun listDevices(): List<UsbDevice> {
        val prober = UsbSerialProber.getDefaultProber()
        return usbManager.deviceList.values.filter { prober.probeDevice(it) != null }
    }

    fun deviceName(d: UsbDevice): String {
        val kind = UsbSerialProber.getDefaultProber().probeDevice(d)
            ?.javaClass?.simpleName?.removeSuffix("SerialDriver") ?: "serial"
        val name = d.productName ?: d.deviceName
        return "$name ($kind, ${d.vendorId.toString(16)}:${d.productId.toString(16)})"
    }

    fun isConnected(): Boolean = port != null

    fun connectedDevice(): String? = deviceKey

    /** Human-readable summary of the open port, or null when closed. */
    fun connectedSummary(): String? {
        val key = deviceKey ?: return null
        val d = usbManager.deviceList.values.firstOrNull { it.deviceName == key }
        return if (d != null) deviceName(d) else key
    }

    /**
     * Opens a serial port. Asks for the Android USB permission first when
     * needed; [baud] is 8N1. Repeated calls for the same device are no-ops.
     */
    fun connect(device: UsbDevice, baud: Int) {
        if (device.deviceName == deviceKey && port != null) return
        disconnect()
        if (usbManager.hasPermission(device)) {
            open(device, baud)
            return
        }
        PdEngine.log("serial: requesting USB permission for ${device.deviceName}")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val d: UsbDevice? = i.getParcelableCompat(UsbManager.EXTRA_DEVICE)
                if (granted && d != null) {
                    open(d, baud)
                } else {
                    PdEngine.log("serial: USB permission denied")
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pi)
    }

    @Suppress("DEPRECATION")
    private fun <T> Intent.getParcelableCompat(name: String): T? =
        getParcelableExtra(name)

    private fun open(device: UsbDevice, baud: Int) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            PdEngine.log("serial: no driver for ${device.deviceName}")
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            PdEngine.log("serial: cannot open the USB device")
            return
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            PdEngine.log("serial: ${device.deviceName} has no serial port")
            conn.close()
            return
        }
        try {
            p.open(conn)
            p.setParameters(baud, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE)
            // Arduino-family boards only start sketch output with DTR asserted
            runCatching { p.setDTR(true) }
            runCatching { p.setRTS(true) }
        } catch (e: Exception) {
            runCatching { p.close() }
            conn.close()
            PdEngine.log("serial: open failed: ${e.message}")
            return
        }
        connection = conn
        port = p
        deviceKey = device.deviceName
        if (!bound) {
            synchronized(PdBase::class.java) { PdBase.subscribe("TTY") }
            PdEngine.ttyOutListener = ::onPdBytes
            bound = true
        }
        startReadThread(p)
        PdEngine.log("serial open: ${deviceName(device)} @ $baud 8N1 (in [r tty0] / out [r TTY])")
    }

    /**
     * Framing of the inbound stream. ON (default): bytes accumulate until a
     * newline (\n or \r — Arduino println sends both) and the completed
     * line is parsed into pd atoms — "123456" arrives as ONE float, "12 34"
     * as a two-float list, "ok" as a symbol — because a USB read returns
     * whatever the chip has buffered, so a println'd number used to land as
     * "12" / "3456\r" / "\n" fragments. OFF: every read chunk as a raw byte
     * list (binary protocols frame themselves in the patch).
     */
    @Volatile
    var lineMode: Boolean = true

    private fun startReadThread(p: UsbSerialPort) {
        reading.set(true)
        val t = Thread({
            // One USB packet per request, through the ASYNC UsbRequest path
            // (timeout 0): the request completes the instant a packet lands —
            // short or full — so latency is one packet and nothing is
            // dropped. The old read(buf, 100) went through bulkTransfer,
            // which the library itself documents as "data loss with short
            // timeout + high baud rates + continuous transfer" — exactly the
            // "cannot keep up once it gets fast" report. No polling, no
            // sleeping: the thread parks inside the kernel until data exists;
            // disconnect() closes the port, which fails the pending request
            // and releases the thread.
            val packet = runCatching { p.readEndpoint.maxPacketSize }
                .getOrDefault(64).coerceIn(32, 512)
            val buf = ByteArray(packet)
            val line = java.io.ByteArrayOutputStream(256)
            var errors = 0
            while (reading.get()) {
                val n = try {
                    p.read(buf, 0)
                } catch (e: IOException) {
                    // with timeout 0 an IOException means the port is gone
                    // (or a rare spurious request failure): retry at once a
                    // few times, then stop — never sleep in the data path
                    if (!reading.get()) break
                    if (++errors > MAX_READ_ERRORS) {
                        PdEngine.log("serial: read failed (${e.message}) - reader stopped")
                        break
                    }
                    continue
                } catch (e: Exception) {
                    if (reading.get()) PdEngine.log("serial: read failed: ${e.message}")
                    break
                }
                if (n <= 0) continue
                errors = 0
                if (lineMode) {
                    for (i in 0 until n) {
                        val b = buf[i].toInt() and 0xFF
                        if (b == 0x0A || b == 0x0D) {
                            emitLine(line)
                        } else {
                            line.write(b)
                            // runaway guard: a stream with no newline at all
                            // still flushes instead of growing without bound
                            if (line.size() >= MAX_LINE) emitLine(line)
                        }
                    }
                } else {
                    val atoms: Array<Any> = Array<Any>(n) { i ->
                        (buf[i].toInt() and 0xFF).toFloat()
                    }
                    synchronized(PdBase::class.java) { PdBase.sendList("tty0", *atoms) }
                }
            }
        }, "serial-read")
        t.isDaemon = true
        t.start()
        readThread = t
    }

    /** One completed text line → pd atoms on `tty0`: numeric tokens become
     *  floats, the rest symbols; a lone float/symbol goes out as that atom
     *  (not a one-element list), like pd's own [fudiparse]. */
    private fun emitLine(line: java.io.ByteArrayOutputStream) {
        if (line.size() == 0) return
        val text = String(line.toByteArray(), Charsets.ISO_8859_1).trim()
        line.reset()
        if (text.isEmpty()) return
        val atoms: List<Any> = text.split(WS).map { tok ->
            tok.toFloatOrNull()?.takeIf { it.isFinite() } ?: tok
        }
        synchronized(PdBase::class.java) {
            val only = atoms.singleOrNull()
            when (only) {
                is Float -> PdBase.sendFloat("tty0", only)
                is String -> PdBase.sendSymbol("tty0", only)
                else -> PdBase.sendList("tty0", *atoms.toTypedArray())
            }
        }
    }

    fun disconnect() {
        if (bound) {
            PdEngine.ttyOutListener = null
            runCatching { synchronized(PdBase::class.java) { PdBase.unsubscribe("TTY") } }
            bound = false
        }
        reading.set(false)
        readThread = null
        val p = port
        port = null
        deviceKey = null
        val conn = connection
        connection = null
        runCatching { p?.close() }
        runCatching { conn?.close() }
    }

    // ----------------------------------------------------------- Pd -> wire

    /** Called from the pd thread for anything patches send to `TTY`. */
    private fun onPdBytes(args: Array<out Any>) {
        val p = port ?: return
        val data = ByteArray(args.size)
        var k = 0
        for (a in args) {
            if (a is Float && k < data.size) data[k++] = a.toInt().toByte()
        }
        if (k == 0) return
        val out = if (k == data.size) data else data.copyOf(k)
        writer.execute {
            try {
                p.write(out, WRITE_TIMEOUT_MS)
            } catch (e: IOException) {
                PdEngine.log("serial: write failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.pocketpd.app.USB_SERIAL_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 100
        /** immediate retries on a failed async read before the reader quits
         *  (timeout-0 reads only fail when the port is closed/unplugged) */
        private const val MAX_READ_ERRORS = 3
        /** longest newline-less run kept before a forced flush */
        private const val MAX_LINE = 4096
        private val WS = Regex("\\s+")

        /** Settings key for a device: vendor:product in hex. Stable across
         *  re-plugs, unlike UsbDevice.deviceName (/dev/bus/usb/001/00N —
         *  the trailing number is reassigned on every enumeration, so a
         *  saved deviceName silently stopped matching after one unplug). */
        fun keyOf(d: UsbDevice): String =
            "${d.vendorId.toString(16)}:${d.productId.toString(16)}"

        /** pd-side objects a patch uses for this bridge. */
        const val PD_IN = "[r tty0]"
        const val PD_OUT = "[s TTY]"
    }
}
