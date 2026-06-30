package com.example.mutterboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlin.math.sqrt

/**
 * Detects a phone "shake" from the accelerometer and invokes [onShake].
 *
 * Each axis reading is combined into a net g-force (so gravity at rest reads
 * ~1g). A peak is counted when that force crosses [SHAKE_G_FORCE] and must dip
 * back below [SHAKE_G_FORCE_RESET] before the next peak counts (hysteresis, so
 * a single sustained jolt isn't double-counted). [REQUIRED_PEAKS] peaks within
 * [WINDOW_MS] trigger [onShake]. Tuned light here: a quick flick is enough.
 *
 * Stateless when stopped — [start] resets the peak tracking each time, so it's
 * safe to start/stop around each recording.
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null

    private var aboveThreshold = false
    private var peakCount = 0
    private var firstPeakMs = 0L

    /**
     * Begins listening on [context]'s accelerometer, delivering callbacks on
     * [handler]'s thread. Returns false (and does nothing) if the device has no
     * accelerometer, so the caller can fall back to tap-only.
     */
    fun start(context: Context, handler: Handler): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        peakCount = 0
        aboveThreshold = false
        sensorManager = sm
        return sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > SHAKE_G_FORCE) {
            if (!aboveThreshold) {
                aboveThreshold = true
                val now = System.currentTimeMillis()
                if (peakCount == 0 || now - firstPeakMs > WINDOW_MS) {
                    firstPeakMs = now
                    peakCount = 1
                } else {
                    peakCount++
                }
                if (peakCount >= REQUIRED_PEAKS) {
                    peakCount = 0
                    onShake()
                }
            }
        } else if (gForce < SHAKE_G_FORCE_RESET) {
            aboveThreshold = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        // Still a flick, not a vigorous shake, but tightened so walking/footstep
        // jostling doesn't trip it: footsteps rarely clear ~1.9g, and the shorter
        // window means the two peaks must come in quick succession (a flick's
        // out-and-back) rather than at a walking step cadence (~0.5s apart).
        const val SHAKE_G_FORCE = 1.9f
        const val SHAKE_G_FORCE_RESET = 1.5f
        const val REQUIRED_PEAKS = 2
        const val WINDOW_MS = 500L
    }
}
