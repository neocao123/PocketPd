package com.pocketpd.app.runtime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import org.puredata.core.PdBase
import java.io.IOException
import java.util.UUID

/**
 * Bridges Android MIDI (`android.media.midi`: USB / Bluetooth LE / virtual
 * devices) and libpd's `[notein]`/`[noteout]`... objects:
 *
 *  device -> parser -> PdBase.sendNoteOn/... (feeds `[notein]` etc.)
 *  `[noteout]` etc -> PdEngine.midiOutListener -> encode -> device input port
 *
 * USB and Bluetooth LE devices are two independent channels and can be open
 * at the same time (both feed the same parser). BLE devices are found by
 * scanning for the standard MIDI service UUID and opened through
 * `MidiManager.openBluetoothDevice`, which works on every supported API
 * level; the platform's own Bluetooth MIDI service performs the GATT work.
 *
 * No extra library is needed; `com.noisepages.nettoyeur:midi` bundled with
 * old pd-for-android predates the platform MIDI API.
 */
class MidiBridge(private val context: Context) {

    fun interface DeviceListListener {
        fun onDevicesChanged(devices: List<MidiDeviceInfo>)
    }

    fun interface BleListListener {
        fun onBleDevicesChanged(devices: List<BleDevice>)
    }

    /** A BLE MIDI device seen during a scan (name from the scan record). */
    data class BleDevice(val address: String, val name: String?)

    @Volatile
    var deviceListListener: DeviceListListener? = null

    @Volatile
    var bleListListener: BleListListener? = null

    private val main = Handler(Looper.getMainLooper())
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val btAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** MIDI-BLE service UUID from the Bluetooth MIDI specification. */
    private val midiServiceUuid: UUID =
        UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

    // ------------------------------------------------- USB / platform devices

    private var usbDevice: MidiDevice? = null
    private var usbIn: MidiInputPort? = null
    private var usbOut: MidiOutputPort? = null

    private var bleOpen: MidiDevice? = null
    private var bleIn: MidiInputPort? = null
    private var bleOut: MidiOutputPort? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo?) {
            notifyDevices()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo?) {
            if (device != null && device.id == usbDevice?.info?.id) closeUsb()
            notifyDevices()
        }
    }

    @Volatile
    var connectedId: Int = -1
        private set

    @Volatile
    var connectedBleAddress: String? = null
        private set

    /** Platform devices; BLE-MIDI ones are handled by the scan path instead. */
    fun listDevices(): List<MidiDeviceInfo> =
        midiManager?.devices?.filter { it.type != MidiDeviceInfo.TYPE_BLUETOOTH }?.toList()
            ?: emptyList()

    fun deviceName(info: MidiDeviceInfo): String {
        val props = info.properties
        val product = props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: props.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: "MIDI device"
        return "$product (#${info.id})"
    }

    fun registerForDevices() {
        midiManager?.registerDeviceCallback(deviceCallback, main)
    }

    fun unregisterForDevices() {
        runCatching { midiManager?.unregisterDeviceCallback(deviceCallback) }
    }

    private fun notifyDevices() {
        deviceListListener?.onDevicesChanged(listDevices())
    }

    /** Opens the USB device and wires both directions. Call from the main thread. */
    fun connect(info: MidiDeviceInfo) {
        closeUsb()
        val mgr = midiManager ?: return
        // A stale MidiDeviceInfo (device unplugged after the spinner was
        // filled) makes the system service itself NPE inside openDevice and
        // the exception is parceled back — it killed the app on device.
        // Re-validate against the live list and swallow whatever leaks.
        if (mgr.devices?.none { it.id == info.id } == true) {
            PdEngine.log("midi: device #${info.id} gone, skip open")
            return
        }
        runCatching {
            mgr.openDevice(info, { device ->
                if (device == null) {
                    PdEngine.log("midi: failed to open device #${info.id}")
                    return@openDevice
                }
                if (attach(device, input = usbIn, output = usbOut, tag = "usb #${info.id}")) {
                    usbDevice = device
                    connectedId = device.info.id
                    usbIn = openedIn
                    usbOut = openedOut
                }
            }, main)
        }.onFailure { PdEngine.log("midi: open device #${info.id} failed: ${it.message}") }
    }

    /** Closes both channels. */
    fun disconnect() {
        closeUsb()
        closeBle()
    }

    /** Closes only the USB channel; a BLE connection stays open. */
    fun disconnectUsb() = closeUsb()

    fun isConnected(): Boolean = usbDevice != null || bleOpen != null

    private fun closeUsb() {
        detach(usbIn, usbOut, usbDevice)
        usbIn = null
        usbOut = null
        usbDevice = null
        connectedId = -1
    }

    private fun closeBle() {
        detach(bleIn, bleOut, bleOpen)
        bleIn = null
        bleOut = null
        bleOpen = null
        connectedBleAddress = null
    }

    private var openedIn: MidiInputPort? = null
    private var openedOut: MidiOutputPort? = null

    /** Opens ports on a freshly opened MidiDevice; returns true on success. */
    private fun attach(
        device: MidiDevice,
        input: MidiInputPort?,
        output: MidiOutputPort?,
        tag: String,
    ): Boolean {
        openedIn = input
        openedOut = output
        if (device.info.inputPortCount > 0) {
            // Pd -> device (patches sending to [noteout] etc.)
            openedIn = runCatching { device.openInputPort(0) }.getOrNull()
        }
        if (device.info.outputPortCount > 0) {
            // device -> Pd
            openedOut = runCatching { device.openOutputPort(0) }.getOrNull()?.also { port ->
                port.connect(deviceToPd)
            }
        }
        PdEngine.log("midi connected: $tag")
        return true
    }

    private fun detach(
        input: MidiInputPort?,
        output: MidiOutputPort?,
        device: MidiDevice?,
    ) {
        runCatching { output?.disconnect(deviceToPd) }
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { device?.close() }
    }

    // ------------------------------------------------------------ BLE channel

    private val bleSeen = LinkedHashMap<String, BleDevice>()

    @Volatile
    private var scanning = false

    fun isBleAvailable(): Boolean = btAdapter != null

    fun bleDevices(): List<BleDevice> = synchronized(bleSeen) { bleSeen.values.toList() }

    fun isScanning(): Boolean = scanning

    fun startBleScan(): Boolean {
        val adapter = btAdapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        if (scanning) return true
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(midiServiceUuid)).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback,
            )
            scanning = true
            return true
        } catch (e: Exception) {
            PdEngine.log("ble midi scan failed: ${e.message}")
            return false
        }
    }

    fun stopBleScan() {
        if (!scanning) return
        scanning = false
        runCatching {
            btAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            val name = result.scanRecord?.deviceName ?: d.name
            synchronized(bleSeen) { bleSeen[d.address] = BleDevice(d.address, name) }
            bleListListener?.onBleDevicesChanged(bleDevices())
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            PdEngine.log("ble midi scan failed: error $errorCode")
        }
    }

    fun bleDeviceName(d: BleDevice): String {
        val name = d.name ?: runCatching {
            btAdapter?.getRemoteDevice(d.address)?.name
        }.getOrNull()
        return if (name.isNullOrEmpty()) d.address else "$name (${d.address})"
    }

    /** Opens a BLE MIDI device by MAC address; USB stays open. Call on main thread. */
    @SuppressLint("MissingPermission")
    fun connectBle(address: String) {
        closeBle()
        val mgr = midiManager ?: return
        // bleSeen proves the device advertises MIDI; getRemoteDevice also
        // resolves addresses typed in before any scan.
        val bt = runCatching { btAdapter?.getRemoteDevice(address) }.getOrNull()
        if (bt == null) {
            PdEngine.log("ble midi: unknown address $address")
            return
        }
        runCatching {
            mgr.openBluetoothDevice(bt, { device ->
                if (device == null) {
                    PdEngine.log("ble midi: failed to open $address")
                    return@openBluetoothDevice
                }
                if (attach(device, input = bleIn, output = bleOut, tag = "ble $address")) {
                    bleOpen = device
                    bleIn = openedIn
                    bleOut = openedOut
                    connectedBleAddress = address
                }
            }, main)
        }.onFailure {
            PdEngine.log("ble midi: open $address failed (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    fun disconnectBle() = closeBle()

    // ---------------------------------------------------------------- shared

    /** device -> Pd */
    private val deviceToPd = object : MidiReceiver() {
        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            if (msg != null && count > 0) parser.feed(msg, offset, count)
        }
    }

    private val parser = MidiParser(
        { status, d1, d2 ->
            val ch = status and 0x0F
            synchronized(PdBase::class.java) {
                if (status >= 0xF8) {
                    // system realtime (clock, start, stop, ...) - a separate
                    // receiver so channel-message parsers never see them
                    PdBase.sendList("midi-sys", status.toFloat())
                    return@synchronized
                }
                // raw stream for the pd-side parsers ([mpein] etc.) - complete
                // messages only, the semantic sends below stay unchanged
                PdBase.sendList(
                    "midi-raw",
                    status.toFloat(), d1.toFloat(), d2.toFloat(),
                )
                when (status and 0xF0) {
                    0x80 -> PdBase.sendNoteOn(ch, d1, 0)
                    0x90 -> PdBase.sendNoteOn(ch, d1, d2)
                    0xA0 -> PdBase.sendPolyAftertouch(ch, d1, d2)
                    0xB0 -> PdBase.sendControlChange(ch, d1, d2)
                    0xC0 -> PdBase.sendProgramChange(ch, d1)
                    0xD0 -> PdBase.sendAftertouch(ch, d1)
                    0xE0 -> PdBase.sendPitchBend(ch, (d2 shl 7 or d1) - 8192)
                }
            }
        },
        // every wire byte rides the pd MIDI byte stream: vanilla [midiin]
        // and every else class bound to "#midiin" (midi.in's raw-mode
        // ctl.in/note.in/..., mpe.in in -ext mode) eat THIS stream, and
        // before 0.17.135 nothing ever fed it
        { b -> PdBase.sendMidiByte(0, b) },
    )

    // ------------------------------------------------------- Pd -> all devices

    private fun send(vararg bytes: Int) {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        for (port in listOfNotNull(usbIn, bleIn)) {
            try {
                port.send(data, 0, data.size)
            } catch (e: IOException) {
                PdEngine.log("midi send failed: ${e.message}")
            }
        }
    }

    fun onNoteOn(channel: Int, pitch: Int, velocity: Int) {
        if (velocity == 0) send(0x80 or channel, pitch, 0)
        else send(0x90 or channel, pitch, velocity)
    }

    fun onControlChange(channel: Int, controller: Int, value: Int) =
        send(0xB0 or channel, controller, value)

    fun onProgramChange(channel: Int, value: Int) = send(0xC0 or channel, value)

    fun onPitchBend(channel: Int, value: Int) {
        val v = (value + 8192).coerceIn(0, 16383)
        send(0xE0 or channel, v and 0x7F, (v shr 7) and 0x7F)
    }

    fun onAftertouch(channel: Int, value: Int) = send(0xD0 or channel, value)

    fun onPolyAftertouch(channel: Int, pitch: Int, value: Int) =
        send(0xA0 or channel, pitch, value)

    /** Raw bytes from `[midiout]`: forwarded one by one (sysex-safe enough). */
    fun onMidiByte(port: Int, value: Int) = send(value and 0x7F)
}

/**
 * Running-status MIDI byte-stream parser: turns arbitrary chunks of wire
 * bytes into complete channel messages. System realtime bytes are dropped,
 * sysex is skipped.
 */
class MidiParser(
    private val sink: (status: Int, data1: Int, data2: Int) -> Unit,
    /** Every raw wire byte, before parsing: the pd MIDI byte stream that
     *  vanilla [midiin] and the else "#midiin"-bound classes eat. */
    private val byteSink: (Int) -> Unit = {},
) {
    private var status = 0
    private var needed = 0
    private var got = 0
    private val buf = IntArray(2)
    private var sysex = false

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            byteSink(b)
            when {
                // realtime byte: forwarded with no data, and unlike any
                // other system byte it does NOT cancel running status
                b >= 0xF8 -> sink(b, 0, 0)
                b >= 0xF0 -> {        // system common / sysex
                    sysex = b == 0xF0
                    status = 0; got = 0
                }
                sysex -> continue
                b >= 0x80 -> {
                    status = b
                    got = 0
                    needed = if ((b and 0xF0) == 0xC0 || (b and 0xF0) == 0xD0) 1 else 2
                }
                status == 0 -> continue // stray data byte
                else -> {
                    buf[got++] = b
                    if (got == needed) {
                        sink(status, buf[0], if (needed > 1) buf[1] else 0)
                        got = 0 // running status stays valid
                    }
                }
            }
        }
    }
}
