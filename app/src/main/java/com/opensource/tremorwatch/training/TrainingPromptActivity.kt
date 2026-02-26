package com.opensource.tremorwatch.training

import android.os.Bundle
import android.os.CountDownTimer
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
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
import kotlinx.coroutines.delay
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
    // [P6] AtomicBoolean guard - prevents double-fire from timer vs. button race.
    // Both respond() and onFinish() use compareAndSet to ensure only one executes.
    private val handled = AtomicBoolean(false)
    private var detectedMovementText = "Detected movement"
    private var pendingSelection: FeedbackLabel? by mutableStateOf(null)
    private var confirmationToken by mutableIntStateOf(0)
    private var resumeTimeoutMs: Long = TrainingManager.PROMPT_TIMEOUT_MS
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
                    pendingSelection = pendingSelection,
                    confirmationToken = confirmationToken,
                    onYes = { beginSelection(FeedbackLabel.YES_TREMOR) },
                    onNo = { beginSelection(FeedbackLabel.NO_ACTIVE) },
                    onIgnore = { respond(FeedbackLabel.IGNORE) },
                    onUndoSelection = { undoSelection() },
                    onConfirmSelection = { commitSelection() }
                )
            }
        }

        startTimeout()
    }

    private fun beginSelection(label: FeedbackLabel) {
        if (handled.get()) return

        resumeTimeoutMs = (secondsRemaining * 1000L).coerceAtLeast(1000L)
        timer?.cancel()
        timer = null
        pendingSelection = label
        confirmationToken += 1
    }

    private fun undoSelection() {
        if (handled.get()) return

        pendingSelection = null
        startTimeout(resumeTimeoutMs)
    }

    private fun commitSelection() {
        val label = pendingSelection ?: return
        pendingSelection = null
        respond(label)
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

    private fun startTimeout(timeoutMs: Long = TrainingManager.PROMPT_TIMEOUT_MS) {
        timer?.cancel()
        timer = null
        secondsRemaining = ((timeoutMs + 999L) / 1000L).toInt()

        timer = object : CountDownTimer(timeoutMs, 1000L) {
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
    pendingSelection: FeedbackLabel?,
    confirmationToken: Int,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onIgnore: () -> Unit,
    onUndoSelection: () -> Unit,
    onConfirmSelection: () -> Unit
) {
    if (pendingSelection != null) {
        TrainingPromptResultScreen(
            selection = pendingSelection,
            confirmationToken = confirmationToken,
            onUndo = onUndoSelection,
            onTimeout = onConfirmSelection
        )
        return
    }

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
                    title = "\uD83D\uDC4B Yes",
                    subtitle = "Tremor was present",
                    onClick = onYes,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF1B5E20).copy(alpha = 0.35f)
                    )
                )
            }

            item {
                PromptActionChip(
                    title = "\uD83D\uDC4D No",
                    subtitle = "Movement only, no tremor",
                    onClick = onNo,
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Color(0xFF0D47A1).copy(alpha = 0.35f)
                    )
                )
            }

            item {
                PromptActionChip(
                    title = "Unsure / Ignore",
                    subtitle = "Not clear enough to label",
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

private data class SelectionUi(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val backgroundColor: Color
)

@Composable
private fun TrainingPromptResultScreen(
    selection: FeedbackLabel,
    confirmationToken: Int,
    onUndo: () -> Unit,
    onTimeout: () -> Unit
) {
    var timeRemaining by remember(confirmationToken) { mutableIntStateOf(5) }

    LaunchedEffect(confirmationToken) {
        while (timeRemaining > 0) {
            delay(1000L)
            timeRemaining--
        }
        onTimeout()
    }

    val ui = when (selection) {
        FeedbackLabel.YES_TREMOR -> SelectionUi(
            emoji = "\uD83D\uDC4B",
            title = "Marked as Tremor",
            subtitle = "Tap UNDO if this was accidental",
            backgroundColor = Color(0xFF1B5E20).copy(alpha = 0.2f)
        )
        FeedbackLabel.NO_ACTIVE -> SelectionUi(
            emoji = "\uD83D\uDC4D",
            title = "Marked as No Tremor",
            subtitle = "Tap UNDO if this was accidental",
            backgroundColor = Color(0xFF0D47A1).copy(alpha = 0.2f)
        )
        else -> SelectionUi(
            emoji = "\uD83E\uDD14",
            title = "Marked Unsure",
            subtitle = "Sample skipped",
            backgroundColor = Color(0xFF424242).copy(alpha = 0.2f)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = ui.emoji,
                fontSize = 46.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ui.title,
                style = MaterialTheme.typography.title1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ui.subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Saving in ${timeRemaining}s...",
                fontSize = 10.sp,
                color = MaterialTheme.colors.secondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onUndo,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFFF5722)
                ),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(48.dp)
            ) {
                Text(
                    text = "UNDO",
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}
