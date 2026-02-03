package com.opensource.tremorwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        
        val sourceString = intent.getStringExtra("source") ?: "PROMPTED"
        val source = try {
            RatingSource.valueOf(sourceString)
        } catch (e: Exception) {
            RatingSource.PROMPTED
        }
        
        Timber.i("RatingActivity launched with source: $source")
        
        setContent {
            TremorWatchTheme {
                RatingScreen(
                    source = source,
                    calibrationModeEnabled = false,
                    onRatingSubmit = { rating, dontAskToday ->
                        Timber.i("Rating submitted: $rating, dontAskToday: $dontAskToday")
                        
                        // Send rating to phone
                        WatchDataSender(this@RatingActivity).sendSubjectiveRating(
                            rating = rating,
                            source = source.name
                        ) { success ->
                            Timber.i("Rating sent to phone: $success")
                        }
                        
                        // Handle "don't ask today"
                        if (dontAskToday) {
                            com.opensource.tremorwatch.receivers.RatingPromptReceiver()
                                .setDontAskToday(this@RatingActivity)
                        }
                        
                        finish()
                    },
                    onCancel = {
                        Timber.d("Rating cancelled")
                        finish()
                    }
                )
            }
        }
    }
}
