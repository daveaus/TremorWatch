package com.opensource.tremorwatch.training

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import timber.log.Timber

/**
 * Manages haptic feedback for training prompts.
 * Uses two short pulses to alert without startle.
 *
 * [P8] Uses applicationContext to prevent Activity context leak via lazy delegate.
 */
class VibrationPromptManager(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Two short pulses: 100ms on, 200ms gap, 100ms on.
     * Gentle enough to not startle, distinct from notifications.
     */
    fun prompt() {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 100, 200, 100)  // off, on, off, on
                val amplitudes = intArrayOf(0, 120, 0, 120)
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 100, 200, 100), -1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Vibration failed")
        }
    }

    fun cancel() {
        vibrator?.cancel()
    }
}
