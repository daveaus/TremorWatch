package com.opensource.tremorwatch.training

import android.os.Bundle
import android.os.CountDownTimer
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import com.opensource.tremorwatch.shared.models.FeedbackLabel
import com.opensource.tremorwatch.ui.theme.TremorWatchTheme
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch-side Activity that asks the user "Was that a tremor?"
 *
 * Lifecycle hardening (v2.0):
 * - [P5] Nullable vibrationManager (no lateinit crash)
 * - [P6] AtomicBoolean guard prevents double-fire from timer vs. button race
 * - [P7] onDestroy auto-timeout if user swiped away without responding
 * - [P9] Modern screen wake APIs (setTurnScreenOn/setShowWhenLocked)
 */
class TrainingPromptActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SAMPLE_ID = "SAMPLE_ID"
        const val EXTRA_EVENT_TIMESTAMP_MS = "EVENT_TIMESTAMP_MS"
    }

    private var sampleId: String? = null
    private var timer: CountDownTimer? = null
    // [P5] Nullable instead of lateinit - prevents UninitializedPropertyAccessException
    // if onCreate exits early (null SAMPLE_ID) and onDestroy calls cancel()
    private var vibrationManager: VibrationPromptManager? = null
    // [P6] AtomicBoolean guard - prevents double-fire from button tap racing timer finish.
    // Both respond() and onFinish() use compareAndSet to ensure only one executes.
    private val handled = AtomicBoolean(false)
    private var detectedMovementText = "Detected movement"
    private var secondsRemaining by mutableIntStateOf((TrainingManager.PROMPT_TIMEOUT_MS / 1000L).toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [P9] Modern screen wake APIs (API 27+, all supported Wear OS 3+ devices)
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sampleId = intent.getStringExtra(EXTRA_SAMPLE_ID)
        if (sampleId == null) {
            Timber.e("TrainingPromptActivity launched without $EXTRA_SAMPLE_ID")
            finish()
            return
        }
        val eventTimestampMs = intent.getLongExtra(EXTRA_EVENT_TIMESTAMP_MS, -1L)
        if (eventTimestampMs > 0L) {
            detectedMovementText = "Detected movement at ${formatMovementTime(eventTimestampMs)}"
        }

        secondsRemaining = (TrainingManager.PROMPT_TIMEOUT_MS / 1000L).toInt()
        vibrationManager = VibrationPromptManager(this).also { it.prompt() }

        setContent {
            TremorWatchTheme {
                TrainingPromptScreen(
                    detectedMovementText = detectedMovementText,
                    secondsRemaining = secondsRemaining,
                    onYes = { respond(FeedbackLabel.YES_TREMOR) },
                    onNo = { respond(FeedbackLabel.NO_ACTIVE) },
                    onIgnore = { respond(FeedbackLabel.IGNORE) }
                )
            }
        }

        startTimeout()
    }

    // [P6] AtomicBoolean ensures only one of respond() or onFinish() executes
    private fun respond(label: FeedbackLabel) {
        if (!handled.compareAndSet(false, true)) return
        timer?.cancel()
        timer = null
        (application as? TrainingAwareApplication)?.trainingManager
            ?.onUserFeedback(requireNotNull(sampleId), label)
        finish()
    }

    private fun startTimeout() {
        timer = object : CountDownTimer(TrainingManager.PROMPT_TIMEOUT_MS, 1000L) {
            override fun onTick(ms: Long) {
                secondsRemaining = ((ms + 999L) / 1000L).toInt()
            }

            override fun onFinish() {
                // [P6] Guard against post-onDestroy handler message delivery
                if (!handled.compareAndSet(false, true)) return
                secondsRemaining = 0
                (application as? TrainingAwareApplication)?.trainingManager
                    ?.onPromptTimeout(requireNotNull(sampleId))
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        // [P7] If user swiped away without responding, treat as timeout
        if (handled.compareAndSet(false, true) && sampleId != null) {
            (application as? TrainingAwareApplication)?.trainingManager
                ?.onPromptTimeout(sampleId!!)
        }
        timer?.cancel()
        timer = null
        vibrationManager?.cancel()
        vibrationManager = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun formatMovementTime(timestampMs: Long): String {
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
    }
}

/** Interface for Application class to provide TrainingManager access. */
interface TrainingAwareApplication {
    var trainingManager: TrainingManager?
}

@Composable
private fun TrainingPromptScreen(
    detectedMovementText: String,
    secondsRemaining: Int,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onIgnore: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = {
            TimeText(
                modifier = Modifier.padding(top = 8.dp),
                timeTextStyle = TimeTextDefaults.timeTextStyle(fontSize = 10.sp)
            )
        }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Tremor Check",
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = detectedMovementText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Was that a tremor episode?",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Auto-ignore in ${secondsRemaining}s",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                PromptActionChip(
                    title = "Yes",
                    subtitle = "Tremor was present",
                    onClick = onYes,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF1B5E20).copy(alpha = 0.35f)
                    )
                )
            }

            item {
                PromptActionChip(
                    title = "No",
                    subtitle = "Movement only, no tremor",
                    onClick = onNo,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF0D47A1).copy(alpha = 0.35f)
                    )
                )
            }

            item {
                PromptActionChip(
                    title = "Ignore",
                    subtitle = "Skip this prompt",
                    onClick = onIgnore,
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

@Composable
private fun PromptActionChip(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    colors: ChipColors
) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                text = title,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        secondaryLabel = {
            Text(
                text = subtitle,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        colors = colors,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}
