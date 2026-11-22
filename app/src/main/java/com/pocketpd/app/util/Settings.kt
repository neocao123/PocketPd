package com.pocketpd.app.util

import android.content.Context
import android.content.SharedPreferences

/** Simple SharedPreferences wrapper for audio / MIDI / OSC configuration. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pocketpd", Context.MODE_PRIVATE)

    // Audio
    var sampleRate: Int
        get() = prefs.getInt(KEY_SR, 48000)
        set(v) = prefs.edit().putInt(KEY_SR, v).apply()

    var ticksPerBuffer: Int
        get() = prefs.getInt(KEY_TICKS, 8)
        set(v) = prefs.edit().putInt(KEY_TICKS, v).apply()

    var audioInput: Boolean
        get() = prefs.getBoolean(KEY_INPUT, false)
        set(v) = prefs.edit().putBoolean(KEY_INPUT, v).apply()

    // MIDI
    var midiEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIDI_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_MIDI_ON, v).apply()

    var midiDeviceId: Int
        get() = prefs.getInt(KEY_MIDI_DEV, -1)
        set(v) = prefs.edit().putInt(KEY_MIDI_DEV, v).apply()

    /** BLE MIDI device MAC, or null when none is selected. */
    var midiBleAddress: String?
        get() = prefs.getString(KEY_MIDI_BLE, null)
        set(v) = prefs.edit().putString(KEY_MIDI_BLE, v).apply()

    // Serial (OTG USB, pure byte transport)
    var serialEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERIAL_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_SERIAL_ON, v).apply()

    /** UsbDevice.deviceName of the selected adapter, or null. */
    var serialDevice: String?
        get() = prefs.getString(KEY_SERIAL_DEV, null)
        set(v) = prefs.edit().putString(KEY_SERIAL_DEV, v).apply()

    var serialBaud: Int
        get() = prefs.getInt(KEY_SERIAL_BAUD, 115200)
        set(v) = prefs.edit().putInt(KEY_SERIAL_BAUD, v).apply()

    /** Inbound framing: true = newline-delimited lines parsed to atoms,
     *  false = raw byte lists per USB read. */
    var serialLineMode: Boolean
        get() = prefs.getBoolean(KEY_SERIAL_LINES, true)
        set(v) = prefs.edit().putBoolean(KEY_SERIAL_LINES, v).apply()

    // OSC
    var oscInEnabled: Boolean
        get() = prefs.getBoolean(KEY_OSC_IN_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_OSC_IN_ON, v).apply()

    var oscInPort: Int
        get() = prefs.getInt(KEY_OSC_IN_PORT, 8000)
        set(v) = prefs.edit().putInt(KEY_OSC_IN_PORT, v).apply()

    var oscOutEnabled: Boolean
        get() = prefs.getBoolean(KEY_OSC_OUT_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_OSC_OUT_ON, v).apply()

    var oscOutHost: String
        get() = prefs.getString(KEY_OSC_OUT_HOST, "127.0.0.1")!!
        set(v) = prefs.edit().putString(KEY_OSC_OUT_HOST, v).apply()

    var oscOutPort: Int
        get() = prefs.getInt(KEY_OSC_OUT_PORT, 9000)
        set(v) = prefs.edit().putInt(KEY_OSC_OUT_PORT, v).apply()

    // Console
    var consoleLines: Int
        get() = prefs.getInt(KEY_CONSOLE_LINES, 5)
        set(v) = prefs.edit().putInt(KEY_CONSOLE_LINES, v).apply()

    var consoleVisible: Boolean
        get() = prefs.getBoolean(KEY_CONSOLE_VISIBLE, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSOLE_VISIBLE, v).apply()

    // CRT look
    /** Full-frame bloom overlay strength 0..100 (see ui/Bloom.kt); 0 = off. */
    var bloomStrength: Int
        get() = prefs.getInt(KEY_BLOOM, 70)
        set(v) = prefs.edit().putInt(KEY_BLOOM, v.coerceIn(0, 100)).apply()

    // AI assistant (chat completions, see ai/LlmClient.kt). Two wire
    // formats: "anthropic" (Messages API, default) and "openai".
    var llmFormat: String
        get() = prefs.getString(KEY_LLM_FORMAT, "anthropic")!!
        set(v) = prefs.edit().putString(KEY_LLM_FORMAT, v).apply()

    var llmEndpoint: String
        get() = prefs.getString(KEY_LLM_ENDPOINT, "https://api.anthropic.com")!!
        set(v) = prefs.edit().putString(KEY_LLM_ENDPOINT, v.trim()).apply()

    var llmKey: String
        get() = prefs.getString(KEY_LLM_KEY, "")!!
        set(v) = prefs.edit().putString(KEY_LLM_KEY, v.trim()).apply()

    var llmModel: String
        get() = prefs.getString(KEY_LLM_MODEL, "claude-sonnet-4-5")!!
        set(v) = prefs.edit().putString(KEY_LLM_MODEL, v.trim()).apply()

    companion object {
        private const val KEY_SR = "audio.sampleRate"
        private const val KEY_TICKS = "audio.ticks"
        private const val KEY_INPUT = "audio.input"
        private const val KEY_MIDI_ON = "midi.enabled"
        private const val KEY_MIDI_DEV = "midi.deviceId"
        private const val KEY_MIDI_BLE = "midi.ble.address"
        private const val KEY_SERIAL_ON = "serial.enabled"
        private const val KEY_SERIAL_DEV = "serial.device"
        private const val KEY_SERIAL_BAUD = "serial.baud"
        private const val KEY_SERIAL_LINES = "serial.lines"
        private const val KEY_CONSOLE_LINES = "console.lines"
        private const val KEY_CONSOLE_VISIBLE = "console.visible"
        private const val KEY_BLOOM = "crt.bloom.strength"
        private const val KEY_OSC_IN_ON = "osc.in.enabled"
        private const val KEY_OSC_IN_PORT = "osc.in.port"
        private const val KEY_OSC_OUT_ON = "osc.out.enabled"
        private const val KEY_OSC_OUT_HOST = "osc.out.host"
        private const val KEY_OSC_OUT_PORT = "osc.out.port"
        private const val KEY_LLM_FORMAT = "llm.format"
        private const val KEY_LLM_ENDPOINT = "llm.endpoint"
        private const val KEY_LLM_KEY = "llm.key"
        private const val KEY_LLM_MODEL = "llm.model"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context.applicationContext).also { instance = it }
            }
    }
}
