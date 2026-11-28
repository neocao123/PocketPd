package com.pocketpd.app.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.pocketpd.app.runtime.SerialBridge
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.pocketpd.app.R
import com.pocketpd.app.pocketPd
import com.pocketpd.app.runtime.MidiBridge
import com.pocketpd.app.util.Externals
import com.pocketpd.app.util.Settings
import java.io.File

/** Audio / MIDI / serial / OSC configuration. Changes apply immediately where possible. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var sampleRateSpinner: Spinner
    private lateinit var bufferSpinner: Spinner
    private lateinit var inputSwitch: MaterialButton
    private lateinit var midiSwitch: MaterialButton

    /** Right-click must never navigate (app-wide rule): on devices that map
     *  a mouse's right button to BACK the key is swallowed here. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            MouseBack.isRightClick(event)
        ) return true
        return super.dispatchKeyEvent(event)
    }
    private lateinit var deviceSpinner: Spinner
    private lateinit var bleSpinner: Spinner
    private lateinit var serialSwitch: MaterialButton
    private lateinit var serialLinesSwitch: MaterialButton
    private lateinit var serialSpinner: Spinner
    private lateinit var serialStatus: TextView
    private lateinit var baudSpinner: Spinner
    private lateinit var oscInSwitch: MaterialButton
    private lateinit var oscInPort: EditText
    private lateinit var oscOutSwitch: MaterialButton
    private lateinit var oscHost: EditText
    private lateinit var oscOutPort: EditText
    private lateinit var remoteCtlSwitch: MaterialButton
    private lateinit var remoteCode: EditText
    private lateinit var consoleOscSwitch: MaterialButton
    private lateinit var consoleLinesEdit: EditText
    private lateinit var bloomSlider: com.pocketpd.app.ui.Pc98Slider
    private lateinit var bloomValue: TextView
    private lateinit var llmFormat: Spinner
    private lateinit var llmEndpoint: EditText
    private lateinit var llmKey: EditText
    private lateinit var llmModel: EditText

    /** Wire format from the spinner: "anthropic" (default) or "openai". */
    private fun selectedLlmFormat(): String =
        if (llmFormat.selectedItemPosition == 1) com.pocketpd.app.ai.LlmClient.FORMAT_OPENAI
        else com.pocketpd.app.ai.LlmClient.FORMAT_ANTHROPIC

    private var deviceIds: List<Int> = emptyList()
    private var bleAddresses: List<String> = emptyList()
    private var serialKeys: List<String?> = emptyList()
    private val bauds = intArrayOf(9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
    private lateinit var localIp: android.widget.TextView
    private lateinit var extInternal: TextView
    private lateinit var extExternal: TextView
    private lateinit var extList: LinearLayout
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var blePermissionLauncher: ActivityResultLauncher<Array<String>>
    private var pendingBleAction: (() -> Unit)? = null
    private var suppressSpinner = false

    override fun onResume() {
        super.onResume()
        refreshLocalIp()
        refreshExternals()
    }

    /** Shows the device IPv4 address so peers know where to send OSC. */
    private fun refreshLocalIp() {
        val ips = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrDefault(emptyList())
        localIp.text = if (ips.isEmpty()) {
            getString(R.string.local_ip_none)
        } else {
            getString(R.string.local_ip) + ": " + ips.joinToString(",  ")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings.get(this)

        importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            val picked = uris.orEmpty()
            if (picked.isNotEmpty()) importExternals(picked)
        }

        blePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val action = pendingBleAction
            pendingBleAction = null
            if (result.values.any { it } && action != null) action()
        }

        findViewById<android.view.View>(R.id.appbar).background =
            ScanlineDrawable.titleBar(this)
        findViewById<android.view.View>(R.id.scan_overlay).background =
            ScanlineDrawable.overlay(this)
        sampleRateSpinner = findViewById(R.id.spinner_sample_rate)
        bufferSpinner = findViewById(R.id.spinner_buffer)
        inputSwitch = findViewById(R.id.switch_audio_input)
        midiSwitch = findViewById(R.id.switch_midi)
        deviceSpinner = findViewById(R.id.spinner_midi_devices)
        bleSpinner = findViewById(R.id.spinner_midi_ble)
        serialSwitch = findViewById(R.id.switch_serial)
        serialLinesSwitch = findViewById(R.id.switch_serial_lines)
        serialSpinner = findViewById(R.id.spinner_serial_devices)
        serialStatus = findViewById(R.id.txt_serial_status)
        baudSpinner = findViewById(R.id.spinner_serial_baud)
        oscInSwitch = findViewById(R.id.switch_osc_in)
        oscInPort = findViewById(R.id.edit_osc_in_port)
        oscOutSwitch = findViewById(R.id.switch_osc_out)
        oscHost = findViewById(R.id.edit_osc_host)
        oscOutPort = findViewById(R.id.edit_osc_out_port)
        remoteCtlSwitch = findViewById(R.id.switch_remote_ctl)
        remoteCode = findViewById(R.id.edit_remote_code)
        consoleOscSwitch = findViewById(R.id.switch_console_osc)
        consoleLinesEdit = findViewById(R.id.edit_console_lines)
        consoleLinesEdit.setText(settings.consoleLines.toString())
        bloomSlider = findViewById(R.id.slider_bloom)
        bloomValue = findViewById(R.id.text_bloom_value)
        bloomSlider.setProgress(settings.bloomStrength)
        bloomValue.text = "${settings.bloomStrength}%"
        bloomSlider.onProgressChange = { v ->
            settings.bloomStrength = v
            com.pocketpd.app.ui.Bloom.strength = v / 100f
            bloomValue.text = "$v%"
        }

        // ---- AI assistant: fields load from settings; APPLY persists, TEST
        // probes with whatever is currently typed so it can run pre-apply
        llmFormat = findViewById(R.id.spinner_llm_format)
        llmEndpoint = findViewById(R.id.edit_llm_endpoint)
        llmKey = findViewById(R.id.edit_llm_key)
        llmModel = findViewById(R.id.edit_llm_model)
        val formats = listOf("Anthropic", "OpenAI")
        llmFormat.adapter = spinnerAdapter(formats)
        suppressSpinner = true
        llmFormat.setSelection(
            if (settings.llmFormat == com.pocketpd.app.ai.LlmClient.FORMAT_OPENAI) 1 else 0
        )
        suppressSpinner = false
        llmEndpoint.setText(settings.llmEndpoint)
        llmKey.setText(settings.llmKey)
        llmModel.setText(settings.llmModel)
        findViewById<View>(R.id.btn_llm_apply).setOnClickListener { saveLlm() }
        findViewById<TextView>(R.id.btn_llm_test).setOnClickListener { testLlm() }

        localIp = findViewById(R.id.text_local_ip)
        extInternal = findViewById(R.id.text_ext_internal)
        extExternal = findViewById(R.id.text_ext_external)
        extList = findViewById(R.id.list_externals)
        findViewById<View>(R.id.btn_ext_import).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }

        findViewById<TextView>(R.id.btn_midi_refresh).setOnClickListener {
            refreshDevices()
        }
        findViewById<TextView>(R.id.btn_midi_ble_scan).setOnClickListener {
            val bridge = application.pocketPd.midiBridge
            if (!bleOn()) {
                toast(R.string.midi_ble_off)
            } else if (bridge.isScanning()) {
                bridge.stopBleScan()
            } else {
                withBlePermission {
                    if (bridge.startBleScan()) toast(R.string.midi_ble_scanning)
                }
            }
        }
        findViewById<TextView>(R.id.btn_serial_refresh).setOnClickListener { refreshSerialSpinner() }

        // ---- audio
        suppressSpinner = true
        val rates = listOf("44100", "48000", "96000")
        sampleRateSpinner.adapter = spinnerAdapter(rates)
        sampleRateSpinner.setSelection(
            rates.indexOf(settings.sampleRate.toString()).coerceAtLeast(0)
        )
        sampleRateSpinner.onItemSelectedListener = spinnerListener { saveAudio() }
        val ticks = listOf("4", "8", "16", "32")
        bufferSpinner.adapter = spinnerAdapter(ticks)
        bufferSpinner.setSelection(
            ticks.indexOf(settings.ticksPerBuffer.toString()).coerceAtLeast(0)
        )
        bufferSpinner.onItemSelectedListener = spinnerListener { saveAudio() }
        bindChip(inputSwitch, settings.audioInput) { saveAudio() }

        // ---- about
        val pi = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        findViewById<TextView>(R.id.text_about_version).text = getString(
            R.string.about_version,
            getString(R.string.app_name),
            pi?.versionName ?: "?",
            pi?.longVersionCode ?: 0L,
        )
        findViewById<TextView>(R.id.text_about_engine).text = getString(
            R.string.about_engine,
            "0.51.3",
            com.pocketpd.app.util.Slibs.PRIORITY.size,
        )

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        // ---- midi
        bindChip(midiSwitch, settings.midiEnabled) { saveMidiAndApply() }
        deviceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                // the initial layout callback can land after suppressSpinner
                // clears on some devices; only a real change may apply
                val dev = if (position > 0) deviceIds.getOrNull(position - 1) ?: -1 else -1
                if (dev == settings.midiDeviceId) return
                settings.midiDeviceId = dev
                application.pocketPd.applyConnections()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---- bluetooth LE MIDI (independent of the USB channel above)
        val midiBridge = application.pocketPd.midiBridge
        midiBridge.bleListListener = MidiBridge.BleListListener {
            runOnUiThread { refreshBleSpinner() }
        }
        bleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                // race guard: the initial onItemSelected often fires AFTER
                // suppressSpinner clears — it used to fall straight into
                // withBlePermission, popping a system permission dialog the
                // moment Settings opened. No change → no side effect.
                val addr = if (position > 0) bleAddresses.getOrNull(position - 1)?.ifEmpty { null } else null
                if (addr == settings.midiBleAddress) return
                settings.midiBleAddress = addr
                withBlePermission { application.pocketPd.applyConnections() }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---- serial (OTG USB)
        bindChip(serialSwitch, settings.serialEnabled) { on ->
            settings.serialEnabled = on
            application.pocketPd.applyConnections()
            refreshSerialStatus()
        }
        bindChip(serialLinesSwitch, settings.serialLineMode) { on ->
            settings.serialLineMode = on
            application.pocketPd.applyConnections()
            refreshSerialStatus()
        }
        serialSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                // spinner position 0 is the "None" row; the key list has no
                // placeholder, so its index is position - 1 (the +1/-1 pair
                // used to be missing here — tapping a device read past the
                // end and stored None, tapping None connected the device)
                val dev = if (position > 0) serialKeys.getOrNull(position - 1) else null
                if (dev == settings.serialDevice) return
                settings.serialDevice = dev
                application.pocketPd.applyConnections()
                refreshSerialStatus()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        baudSpinner.adapter = spinnerAdapter(bauds.map { it.toString() })
        baudSpinner.setSelection(bauds.indexOf(settings.serialBaud).coerceAtLeast(0), false)
        baudSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                val b = bauds.getOrNull(position) ?: 115200
                if (b == settings.serialBaud) return
                settings.serialBaud = b
                if (settings.serialEnabled) application.pocketPd.applyConnections()
                refreshSerialStatus()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---- osc
        bindChip(oscInSwitch, settings.oscInEnabled) { }
        oscInPort.setText(settings.oscInPort.toString())
        bindChip(oscOutSwitch, settings.oscOutEnabled) { }
        oscHost.setText(settings.oscOutHost)
        oscOutPort.setText(settings.oscOutPort.toString())
        bindChip(remoteCtlSwitch, settings.remoteCtlEnabled) { }
        remoteCode.setText(settings.remoteCtlCode)
        bindChip(consoleOscSwitch, settings.consoleOscEnabled) { }
        findViewById<android.widget.Button>(R.id.btn_osc_apply).setOnClickListener { saveOscAndApply() }

        refreshDevices()
    }

    /** BIOS value chip (design 04): tap toggles OFF/ON, solid cyan when ON.
     *  A checkable MaterialButton flips itself in performClick(); here we
     *  only mirror the state to the label and the settings — flipping again
     *  would double-toggle straight back. */
    private fun bindChip(chip: MaterialButton, initial: Boolean, onChange: (Boolean) -> Unit) {
        chip.isCheckable = true
        chip.isChecked = initial
        chip.text = if (initial) "ON" else "OFF"
        chip.setOnClickListener { v ->
            val b = v as MaterialButton
            b.text = if (b.isChecked) "ON" else "OFF"
            onChange(b.isChecked)
        }
    }


    /** Dropdown adapters: pixel item in the value box, pixel rows in the popup.
     *  Custom layouts — framework spinner styles lose the custom font. */
    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, R.layout.spinner_item_pd98, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_pd98)
        }

    private fun spinnerListener(onChange: () -> Unit): android.widget.AdapterView.OnItemSelectedListener =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinner) return
                onChange()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

    private fun saveAudio() {
        settings.sampleRate = (sampleRateSpinner.selectedItem as? String)?.toIntOrNull() ?: 48000
        settings.ticksPerBuffer = (bufferSpinner.selectedItem as? String)?.toIntOrNull() ?: 8
        settings.audioInput = inputSwitch.isChecked
    }

    private fun saveMidiAndApply() {
        settings.midiEnabled = midiSwitch.isChecked
        application.pocketPd.applyConnections()
    }

    private fun saveOscAndApply() {
        settings.oscInEnabled = oscInSwitch.isChecked
        settings.oscInPort = oscInPort.text.toString().toIntOrNull() ?: 8000
        settings.oscOutEnabled = oscOutSwitch.isChecked
        settings.oscOutHost = oscHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        settings.oscOutPort = oscOutPort.text.toString().toIntOrNull() ?: 9000
        // a remote-control switch without a code would arm an unauthenticated
        // control surface: refuse and flip the chip back off
        val wantRemote = remoteCtlSwitch.isChecked
        val code = remoteCode.text.toString().trim()
        if (wantRemote && code.isEmpty()) {
            toast(R.string.remote_code_required)
            bindChip(remoteCtlSwitch, false) { }
        }
        settings.remoteCtlEnabled = remoteCtlSwitch.isChecked
        settings.remoteCtlCode = code
        settings.consoleOscEnabled = consoleOscSwitch.isChecked
        application.pocketPd.applyConnections()
    }

    private fun saveLlm() {
        settings.llmFormat = selectedLlmFormat()
        settings.llmEndpoint = llmEndpoint.text.toString()
        settings.llmKey = llmKey.text.toString()
        settings.llmModel = llmModel.text.toString()
        toast(R.string.llm_applied)
    }

    /** Probe with the currently typed values (works before APPLY). */
    private fun testLlm() {
        val endpoint = llmEndpoint.text.toString().trim()
        val key = llmKey.text.toString().trim()
        val model = llmModel.text.toString().trim()
        val format = selectedLlmFormat()
        if (endpoint.isEmpty() || model.isEmpty()) {
            toast(R.string.ai_not_configured)
            return
        }
        Toast.makeText(this, R.string.llm_testing, Toast.LENGTH_SHORT).show()
        com.pocketpd.app.ai.LlmClient.complete(
            endpoint, key, model, format, "Reply with the single word: OK"
        ) { _, err ->
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(
                        this, getString(R.string.llm_test_fail, err), Toast.LENGTH_LONG
                    ).show()
                } else {
                    toast(R.string.llm_test_ok)
                }
            }
        }
    }

    /** Clamps and stores the editor console height (lines). */
    private fun saveConsole() {
        val v = consoleLinesEdit.text.toString().toIntOrNull()
            ?.coerceIn(1, 30) ?: settings.consoleLines
        settings.consoleLines = v
        if (consoleLinesEdit.text.toString() != v.toString()) {
            consoleLinesEdit.setText(v.toString())
        }
    }

    private fun refreshDevices() {
        val bridge = application.pocketPd.midiBridge
        val devices = bridge.listDevices()
        deviceIds = devices.map { it.id }
        val names = ArrayList<String>()
        names.add(getString(R.string.midi_none))
        for (d in devices) names.add(bridge.deviceName(d))
        suppressSpinner = true
        deviceSpinner.adapter = spinnerAdapter(names)
        val current = deviceIds.indexOf(settings.midiDeviceId)
        deviceSpinner.setSelection(if (current >= 0) current + 1 else 0)
        deviceSpinner.post { suppressSpinner = false }
        refreshBleSpinner()
        refreshSerialSpinner()
    }

    private fun refreshBleSpinner() {
        val bridge = application.pocketPd.midiBridge
        val entries = bridge.bleDevices()
        bleAddresses = entries.map { it.address }
        val names = ArrayList<String>()
        names.add(getString(R.string.midi_ble_none))
        for (e in entries) names.add(bridge.bleDeviceName(e))
        suppressSpinner = true
        bleSpinner.adapter = spinnerAdapter(names)
        val current = bleAddresses.indexOf(settings.midiBleAddress)
        bleSpinner.setSelection(if (current >= 0) current + 1 else 0)
        bleSpinner.post { suppressSpinner = false }
    }

    private fun refreshSerialSpinner() {
        val bridge = application.pocketPd.serialBridge
        val devices = bridge.listDevices()
        serialKeys = devices.map { SerialBridge.keyOf(it) }
        val names = ArrayList<String>()
        names.add(getString(R.string.serial_none))
        for (d in devices) names.add(bridge.deviceName(d))
        suppressSpinner = true
        serialSpinner.adapter = spinnerAdapter(names)
        val current = serialKeys.indexOf(settings.serialDevice)
        serialSpinner.setSelection(if (current >= 0) current + 1 else 0)
        serialSpinner.post { suppressSpinner = false }
        refreshSerialStatus()
    }

    /** Live port state plus the exact pd objects a patch uses — the
     *  receiver names were only ever in a log line and a hint paragraph,
     *  so users could not tell what to type. The USB permission prompt
     *  makes connect() asynchronous: poll once more a moment later. */
    private fun refreshSerialStatus() {
        val bridge = application.pocketPd.serialBridge
        val open = bridge.connectedSummary()
        val mode = getString(if (settings.serialLineMode) R.string.serial_mode_lines else R.string.serial_mode_bytes)
        serialStatus.text = if (open != null) {
            getString(R.string.serial_status_open, open, settings.serialBaud,
                SerialBridge.PD_IN, mode, SerialBridge.PD_OUT)
        } else {
            getString(R.string.serial_status_closed, SerialBridge.PD_IN, mode, SerialBridge.PD_OUT)
        }
        if (open == null && settings.serialEnabled && settings.serialDevice != null) {
            serialStatus.postDelayed({ if (!isFinishing) refreshSerialStatusOnce() }, 1500)
        }
    }

    private fun refreshSerialStatusOnce() {
        val open = application.pocketPd.serialBridge.connectedSummary() ?: return
        val mode = getString(if (settings.serialLineMode) R.string.serial_mode_lines else R.string.serial_mode_bytes)
        serialStatus.text = getString(R.string.serial_status_open, open, settings.serialBaud,
            SerialBridge.PD_IN, mode, SerialBridge.PD_OUT)
    }

    /** True when a Bluetooth adapter is present and enabled. */
    private fun bleOn(): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.isEnabled == true
    }

    /** Runs [action] once Bluetooth permissions are granted (API-level aware). */
    private fun withBlePermission(action: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBleAction = action
            blePermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun toast(res: Int) =
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        saveConsole()
        application.pocketPd.midiBridge.stopBleScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        application.pocketPd.midiBridge.bleListListener = null
    }

    // ------------------------------------------------------- abstractions

    /** SAF import: copies the picked documents into the app externals folder. */
    private fun importExternals(uris: List<Uri>) {
        Thread {
            val n = Externals.importFrom(this, uris)
            runOnUiThread {
                refreshExternals()
                Toast.makeText(this, getString(R.string.externals_imported, n), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Path lines + one removable row per abstraction file. */
    /** F5.6 pixel font for programmatically built rows (null = system). */
    private fun dialogFont() = runCatching {
        androidx.core.content.res.ResourcesCompat.getFont(this, R.font.pd98_latin)
    }.getOrNull()

    private fun refreshExternals() {
        Externals.refreshIndex(this)
        extInternal.text = getString(R.string.externals_app_folder) + ": " +
            Externals.internalDir(this).absolutePath
        val ext = Externals.externalDir(this)
        if (ext == null) {
            extExternal.visibility = View.GONE
        } else {
            extExternal.visibility = View.VISIBLE
            extExternal.text = getString(R.string.externals_shared_folder) + ": " + ext.absolutePath
        }
        extList.removeAllViews()
        val files = Externals.list(this)
        if (files.isEmpty()) {
            extList.addView(TextView(this).apply {
                text = getString(R.string.externals_none)
                typeface = dialogFont()
                textSize = 10f
                setTextColor(0xFF6C6C8A.toInt())
            })
            return
        }
        val d = resources.displayMetrics.density
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = f.name
                typeface = dialogFont() // F5.6 — system default here read as a wrong font
                textSize = 10f
                setTextColor(0xFFFCFCFC.toInt())
                isSingleLine = true
                setPadding(0, (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            }
            val remove = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true
                )
                setBackgroundResource(tv.resourceId)
                contentDescription = getString(R.string.externals_remove)
                setOnClickListener { confirmRemove(f) }
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                remove,
                LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt())
            )
            extList.addView(row)
        }
    }

    private fun confirmRemove(file: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.externals_remove_confirm, file.name))
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Externals.delete(file)) {
                    Toast.makeText(this, getString(R.string.externals_removed, file.name), Toast.LENGTH_SHORT).show()
                }
                refreshExternals()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
