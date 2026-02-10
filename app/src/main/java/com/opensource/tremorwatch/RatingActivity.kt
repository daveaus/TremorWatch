package com.opensource.tremorwatch

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.opensource.tremorwatch.service.TremorService
import com.opensource.tremorwatch.shared.models.RatingSource
import com.opensource.tremorwatch.ui.RatingScreen
import com.opensource.tremorwatch.ui.theme.TremorWatchTheme
import timber.log.Timber

/**
 * Standalone Activity for rating prompts.
 *
 * Launched by RatingPromptReceiver when a scheduled rating prompt fires.
 * This allows rating prompts to appear even when the main activity is not in foreground.
 */
class RatingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cancel any pending follow-up vibration now that the prompt is opened
        getSharedPreferences("rating_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("prompt_followup_pending", false)
            .apply()

        // Cancel the rating prompt notification so it can't be re-tapped to reopen
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(3)

        val sourceString = intent.getStringExtra("source") ?: "PROMPTED"
        val source = try {
            RatingSource.valueOf(sourceString)
        } catch (e: Exception) {
            RatingSource.PROMPTED
        }

        // Read calibration config from SharedPreferences (stored by TremorService.applyRatingConfig)
        val ratingPrefs = getSharedPreferences("rating_prefs", MODE_PRIVATE)
        val calibrationEnabled = ratingPrefs.getBoolean("calibration_enabled", false)
        val calibrationDuration = ratingPrefs.getInt("calibration_duration_seconds", 60)

        val watchId = try {
            android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) { "unknown" }

        Timber.i("RatingActivity launched with source: $source, calibration: $calibrationEnabled")

        setContent {
            TremorWatchTheme {
                RatingScreen(
                    source = source,
                    calibrationModeEnabled = calibrationEnabled,
                    onRatingSubmit = { rating, dontAskToday ->
                        Timber.i("Rating submitted: $rating, dontAskToday: $dontAskToday")

                        val ratingId = java.util.UUID.randomUUID().toString()

                        // Send rating to phone with detection state and calibration flags
                        WatchDataSender(this@RatingActivity).sendSubjectiveRating(
                            ratingId = ratingId,
                            rating = rating,
                            source = source.name,
                            watchId = watchId,
                            calibrationModeEnabled = calibrationEnabled,
                            calibrationDurationSeconds = calibrationDuration
                        ) { success ->
                            Timber.i("Rating sent to phone: $success")
                        }

                        // Start calibration capture if enabled
                        if (calibrationEnabled) {
                            val calibIntent = Intent(this@RatingActivity, TremorService::class.java).apply {
                                action = TremorService.ACTION_START_CALIBRATION
                                putExtra(TremorService.EXTRA_RATING_ID, ratingId)
                                putExtra(TremorService.EXTRA_CALIBRATION_DURATION, calibrationDuration)
                            }
                            startService(calibIntent)
                            Timber.i("Calibration capture intent sent for ratingId=$ratingId")
                        }

                        // Handle "don't ask today"
                        if (dontAskToday) {
                            com.opensource.tremorwatch.receivers.RatingPromptReceiver()
                                .setDontAskToday(this@RatingActivity)
                        }

                        goToHome()
                    },
                    onUndo = {
                        Timber.d("Rating undone by user")
                        // No action needed - just goes back to selection screen
                    },
                    onCancel = {
                        Timber.d("Rating cancelled")
                        goToHome()
                    }
                )
            }
        }
    }

    /**
     * Navigate to the home screen (watchface) instead of returning to TremorWatch app.
     */
    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
        finishAndRemoveTask()
    }
}
