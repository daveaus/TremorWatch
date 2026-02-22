package com.opensource.tremorwatch.ui

import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.opensource.tremorwatch.shared.models.RatingSource
import com.opensource.tremorwatch.shared.models.SubjectiveRating
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rating screen for subjective tremor rating on the watch.
 *
 * Uses ScalingLazyColumn for better accessibility on circular watch faces.
 * Provides haptic feedback on rating selection for confirmation.
 * Order is reversed (5 at top) for easier access during severe tremors.
 */
@Composable
fun RatingScreen(
    source: RatingSource = RatingSource.MANUAL,
    calibrationModeEnabled: Boolean = false,
    calibrationDurationSeconds: Int = 60,
    onRatingSubmit: (rating: Int, dontAskToday: Boolean) -> Unit,
    onUndo: (() -> Unit)? = null,
    onCancel: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    var selectedRating by remember { mutableIntStateOf(-1) }
    var showResult by remember { mutableStateOf(false) }
    var ratingSubmitted by remember { mutableStateOf(false) }
    var dontAskToday by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()

    // Keep screen on during rating
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    // Rating options - reversed order (5 at top for easier access during bad tremors).
    // Negative values are special context labels (not clinical scores): -1 sleeping, -2 unsure.
    // These help label nocturnal detection episodes and uncertain recall.
    val ratingOptions = remember {
        listOf(
            RatingOption(5, "5 - Very Severe", "Hard to use this hand", "😣"),
            RatingOption(4, "4 - Significant", "Interfering with tasks", "😟"),
            RatingOption(3, "3 - Moderate", "Annoying but manageable", "😐"),
            RatingOption(2, "2 - Mild", "Noticeable but not annoying", "🙂"),
            RatingOption(1, "1 - Minimal", "Not bothering me", "😊"),
            RatingOption(0, "0 - No Tremor", "Feeling great!", "🎆"),
            RatingOption(-1, "Was Sleeping", "Not relevant right now", "😴"),
            RatingOption(-2, "Not Sure", "Can't remember", "🤷")
        )
    }

    if (showResult && selectedRating >= 0) {
        // Full-screen result with undo
        RatingResultScreen(
            rating = selectedRating,
            onUndo = {
                // Haptic feedback for undo
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                showResult = false
                selectedRating = -1
                ratingSubmitted = false
                onUndo?.invoke()
            },
            onTimeout = {
                onRatingSubmit(selectedRating, dontAskToday)
            }
        )
    } else {
        // Rating selection screen
        Scaffold(
            timeText = {
                TimeText(
                    modifier = Modifier.padding(top = 8.dp),
                    timeTextStyle = TimeTextDefaults.timeTextStyle(
                        fontSize = 10.sp
                    )
                )
            }
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 32.dp,
                    bottom = 32.dp,
                    start = 8.dp,
                    end = 8.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = when (source) {
                                RatingSource.MANUAL -> "Rate Your Tremor"
                                RatingSource.PROMPTED -> "Quick Check"
                                RatingSource.TREMOR_CHANGE -> "Tremor Changed?"
                            },
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "How is your tremor right now?",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.secondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Rating options
                items(ratingOptions) { option ->
                    RatingChip(
                        option = option,
                        isSelected = selectedRating == option.rating,
                        onClick = {
                            selectedRating = option.rating
                            // Haptic feedback on selection
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            vibrator?.vibrate(
                                VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                            )
                            // Submit immediately and show result
                            ratingSubmitted = true
                            showResult = true
                        }
                    )
                }

                // Cancel button - full width to match rating buttons
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Chip(
                        onClick = onCancel,
                        label = {
                            Text(
                                "Cancel",
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual rating option chip
 */
@Composable
private fun RatingChip(
    option: RatingOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when (option.rating) {
        -2, -1 -> Color(0xFF9E9E9E).copy(alpha = if (isSelected) 0.3f else 0.1f) // Grey for special states
        0 -> Color(0xFF2196F3).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Blue for no tremor
        1 -> Color(0xFF4CAF50).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Green
        2 -> Color(0xFF8BC34A).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Light Green
        3 -> Color(0xFFFFEB3B).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Yellow
        4 -> Color(0xFFFF9800).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Orange
        5 -> Color(0xFFF44336).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Red
        else -> MaterialTheme.colors.surface
    }

    val textColor = when (option.rating) {
        -2, -1 -> Color(0xFF9E9E9E)  // Grey for special states
        0 -> Color(0xFF2196F3)
        1 -> Color(0xFF4CAF50)
        2 -> Color(0xFF8BC34A)
        3 -> Color(0xFFFFC107)
        4 -> Color(0xFFFF9800)
        5 -> Color(0xFFF44336)
        else -> MaterialTheme.colors.onSurface
    }

    Chip(
        onClick = onClick,
        label = {
            Column(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.emoji,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = option.label,
                        fontSize = 14.sp,
                        color = if (isSelected) textColor else MaterialTheme.colors.onSurface
                    )
                }
                Text(
                    text = option.description,
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.secondary,
                    modifier = Modifier.padding(start = 24.dp)
                )
            }
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = backgroundColor,
            contentColor = MaterialTheme.colors.onSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}

/**
 * Full-screen result display shown after rating selection.
 * Auto-closes after 5 seconds with a big undo button.
 */
@Composable
private fun RatingResultScreen(
    rating: Int,
    onUndo: () -> Unit,
    onTimeout: () -> Unit
) {
    val view = LocalView.current
    val vibrator = LocalContext.current.getSystemService(Vibrator::class.java)
    var timeRemaining by remember { mutableIntStateOf(5) }

    // Countdown timer
    LaunchedEffect(Unit) {
        // Strong confirmation haptic when showing result
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        while (timeRemaining > 0) {
            delay(1000)
            timeRemaining--
        }
        onTimeout()
    }

    val ratingEmoji = when (rating) {
        -2 -> "🤷"
        -1 -> "😴"
        0 -> "🎆"
        1 -> "😊"
        2 -> "🙂"
        3 -> "😐"
        4 -> "😟"
        5 -> "😣"
        else -> "❓"
    }

    val ratingLabel = SubjectiveRating.RATING_LABELS[rating] ?: "Unknown"

    val backgroundColor = when (rating) {
        -2, -1 -> Color(0xFF9E9E9E).copy(alpha = 0.2f)
        0 -> Color(0xFF2196F3).copy(alpha = 0.2f)
        1 -> Color(0xFF4CAF50).copy(alpha = 0.2f)
        2 -> Color(0xFF8BC34A).copy(alpha = 0.2f)
        3 -> Color(0xFFFFEB3B).copy(alpha = 0.2f)
        4 -> Color(0xFFFF9800).copy(alpha = 0.2f)
        5 -> Color(0xFFF44336).copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        // Firework animation for "No Tremor" celebration (not for special context states)
        if (rating == 0) {
            FireworkAnimation()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Large emoji
            Text(
                text = ratingEmoji,
                fontSize = 48.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Rating number
            Text(
                text = when (rating) {
                    -2 -> "Noted!"
                    -1 -> "Sleep logged!"
                    0 -> "No Tremor!"
                    else -> "Rating: $rating"
                },
                style = MaterialTheme.typography.title1,
                textAlign = TextAlign.Center
            )

            // Rating description
            Text(
                text = ratingLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Countdown indicator
            Text(
                text = "Saving in ${timeRemaining}s...",
                fontSize = 10.sp,
                color = MaterialTheme.colors.secondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Big undo button
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

/**
 * Simple firework animation for the "No Tremor" celebration
 */
@Composable
private fun FireworkAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "firework")

    // Multiple particles with different animations
    val particles = remember {
        List(12) { index ->
            val angle = (index * 30f) * (Math.PI / 180f)
            Pair(cos(angle).toFloat(), sin(angle).toFloat())
        }
    }

    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fireworkProgress"
    )

    val colors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFF4ECDC4),
        Color(0xFFFFE66D),
        Color(0xFF95E1D3),
        Color(0xFFF38181),
        Color(0xFFAA96DA)
    )

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val centerX = size.width / 2
        val centerY = size.height / 3
        val maxRadius = size.minDimension / 3

        particles.forEachIndexed { index, (dirX, dirY) ->
            val particleProgress = ((progress + index * 0.08f) % 1f)
            val radius = maxRadius * particleProgress
            val alpha = (1f - particleProgress).coerceIn(0f, 1f)
            val particleSize = 6f * (1f - particleProgress * 0.5f)

            drawCircle(
                color = colors[index % colors.size].copy(alpha = alpha),
                radius = particleSize,
                center = Offset(
                    centerX + dirX * radius,
                    centerY + dirY * radius
                )
            )
        }
    }
}

/**
 * Data class for rating options
 */
private data class RatingOption(
    val rating: Int,
    val label: String,
    val description: String,
    val emoji: String
)

/**
 * Compact indicator for main screen showing last rating
 */
@Composable
fun RatingStatusIndicator(
    lastRating: Int?,
    minutesSinceLastRating: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when {
        lastRating == null -> "Rate tremor"
        minutesSinceLastRating == null -> "Rated: $lastRating"
        minutesSinceLastRating < 60 -> "Rated ${minutesSinceLastRating}m ago"
        minutesSinceLastRating < 1440 -> "Rated ${minutesSinceLastRating / 60}h ago"
        else -> "Rate tremor"
    }

    val emoji = when (lastRating) {
        -2 -> "🤷"
        -1 -> "😴"
        0 -> "🎆"
        1 -> "😊"
        2 -> "🙂"
        3 -> "😐"
        4 -> "😟"
        5 -> "😣"
        else -> "📝"
    }

    CompactChip(
        onClick = onClick,
        label = {
            Text(
                text = statusText,
                fontSize = 9.sp,
                maxLines = 1
            )
        },
        icon = {
            Text(
                text = emoji,
                fontSize = 10.sp
            )
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier
    )
}
