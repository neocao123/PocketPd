package com.pocketpd.app.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.puredata.core.PdBase

/**
 * Phone sensors -> pd receivers. Each present sensor streams its values at
 * game rate (~50 Hz) to fixed receiver symbols; the matching built-in
 * abstractions ([accel], [gyro], [magnet], [light], …) tap them with [r]
 * objects, so sensor objects are ordinary pd abstractions with real outlets.
 *
 * Compatibility: none of these sensor types needs a runtime permission
 * (they are all "normal" sensors; the HIGH_SAMPLING_RATE_SENSORS permission
 * would only matter above 200 Hz). A sensor a device doesn't have is simply
 * never registered — its abstraction loads but stays silent.
 */
object SensorBridge : SensorEventListener {

    private var manager: SensorManager? = null
    private val registered = ArrayList<Sensor>()
    private var lastStatus: String? = null

    /** sensor type -> receiver names, in x/y/z outlet order. */
    private val receivers = mapOf(
        Sensor.TYPE_ACCELEROMETER to
            listOf("pocketpd-accel-x", "pocketpd-accel-y", "pocketpd-accel-z"),
        Sensor.TYPE_GYROSCOPE to
            listOf("pocketpd-gyro-x", "pocketpd-gyro-y", "pocketpd-gyro-z"),
        Sensor.TYPE_MAGNETIC_FIELD to
            listOf("pocketpd-mag-x", "pocketpd-mag-y", "pocketpd-mag-z"),
        Sensor.TYPE_LIGHT to listOf("pocketpd-light"),
        Sensor.TYPE_PRESSURE to listOf("pocketpd-pressure"),
        Sensor.TYPE_RELATIVE_HUMIDITY to listOf("pocketpd-humidity"),
    )

    private val labels = mapOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        Sensor.TYPE_LIGHT to "light",
        Sensor.TYPE_PRESSURE to "pressure",
        Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
    )

    /** Registers every present sensor; safe to call repeatedly. */
    fun start(context: Context) {
        if (manager != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        manager = sm
        val present = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((type, names) in receivers) {
            val label = labels.getValue(type)
            val sensor = sm.getDefaultSensor(type)
            if (sensor != null) {
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                registered.add(sensor)
                present.add(label)
            } else {
                missing.add(label)
            }
            // Bind from Java too: pd delivers to every object bound to a
            // symbol, so the [r] abstractions still hear everything while a
            // bound symbol guarantees sends never error on empty receivers.
            synchronized(PdBase::class.java) { for (n in names) PdBase.subscribe(n) }
        }
        val status = "active: ${present.joinToString(", ").ifEmpty { "none" }}; " +
            "missing: ${missing.joinToString(", ").ifEmpty { "none" }}"
        if (status != lastStatus) {
            lastStatus = status
            PdEngine.log("sensors: $status")
        }
    }

    /** Unregisters everything (editor paused / no patch on screen). */
    fun stop() {
        manager?.let { sm -> for (s in registered) sm.unregisterListener(this) }
        registered.clear()
        for (names in receivers.values) for (n in names) {
            synchronized(PdBase::class.java) { PdBase.unsubscribe(n) }
        }
        manager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val names = receivers[event.sensor.type] ?: return
        // one lock per event: this fires continuously from a binder thread and
        // would otherwise race every UI/OSC send into pd (messages vanish)
        synchronized(PdBase::class.java) {
            for (i in names.indices) {
                if (i < event.values.size) PdBase.sendFloat(names[i], event.values[i])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
