package com.example.core.security

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.example.data.model.SecureVaultSettings
import kotlin.math.sqrt

/**
 * Foreground emergency-hide gesture detector.
 *
 * Android 9+ intentionally stops continuous sensor delivery to background apps, so this controller
 * is scoped to the visible Vault UI. It does not pretend to be an always-on detector.
 */
class EmergencyMotionController(
    context: Context,
    private val onEmergencyHide: () -> Unit
) : SensorEventListener {

    companion object {
        private const val SHAKE_WINDOW_MS = 650L
        private const val SHAKE_COOLDOWN_MS = 1_500L
        private const val FACE_DOWN_RESET_Z = -5.0f
        private const val FACE_DOWN_TRIGGER_Z = -7.4f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var settings = SecureVaultSettings()
    private var started = false

    private var shakeWindowStartedAt = 0L
    private var shakePeakCount = 0
    private var lastTriggerAt = 0L

    private var faceDownStartedAt = 0L
    private var faceDownTriggered = false

    fun start(currentSettings: SecureVaultSettings) {
        settings = currentSettings
        if (!currentSettings.shakeToHideEnabled && !currentSettings.faceDownToHideEnabled) {
            stop()
            return
        }
        if (started || accelerometer == null) return
        started = sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    fun updateSettings(currentSettings: SecureVaultSettings) {
        settings = currentSettings
        if (!currentSettings.shakeToHideEnabled && !currentSettings.faceDownToHideEnabled) {
            stop()
        } else if (!started) {
            start(currentSettings)
        }
    }

    fun stop() {
        if (started) {
            sensorManager.unregisterListener(this)
        }
        started = false
        resetMotionState()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < SHAKE_COOLDOWN_MS) return

        if (settings.shakeToHideEnabled) {
            processShake(event.values[0], event.values[1], event.values[2], now)
        }

        if (settings.faceDownToHideEnabled) {
            processFaceDown(event.values[2], now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun processShake(x: Float, y: Float, z: Float, now: Long) {
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val threshold = when (settings.shakeSensitivity.coerceIn(1, 3)) {
            1 -> 2.75f
            2 -> 2.35f
            else -> 1.95f
        }

        if (gForce < threshold) return

        if (shakeWindowStartedAt == 0L || now - shakeWindowStartedAt > SHAKE_WINDOW_MS) {
            shakeWindowStartedAt = now
            shakePeakCount = 1
            return
        }

        shakePeakCount++
        if (shakePeakCount >= 2) {
            triggerEmergencyHide(now)
        }
    }

    private fun processFaceDown(z: Float, now: Long) {
        if (z <= FACE_DOWN_TRIGGER_Z) {
            if (faceDownStartedAt == 0L) {
                faceDownStartedAt = now
                faceDownTriggered = false
            }

            if (!faceDownTriggered &&
                now - faceDownStartedAt >= settings.faceDownDelayMillis
            ) {
                faceDownTriggered = true
                triggerEmergencyHide(now)
            }
        } else if (z >= FACE_DOWN_RESET_Z) {
            faceDownStartedAt = 0L
            faceDownTriggered = false
        }
    }

    private fun triggerEmergencyHide(now: Long) {
        lastTriggerAt = now
        resetMotionState(preserveCooldown = true)
        onEmergencyHide()
    }

    private fun resetMotionState(preserveCooldown: Boolean = false) {
        shakeWindowStartedAt = 0L
        shakePeakCount = 0
        faceDownStartedAt = 0L
        faceDownTriggered = false
        if (!preserveCooldown) {
            lastTriggerAt = 0L
        }
    }
}
