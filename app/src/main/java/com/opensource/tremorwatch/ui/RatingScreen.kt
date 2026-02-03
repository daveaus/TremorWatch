package com.opensource.tremorwatch.ui

import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Rating screen for subjective tremor rating on the watch.
 * 
 * Uses ScalingLazyColumn for better accessibility on circular watch faces.
 * Provides haptic feedback on rating selection for confirmation.
 */
@Composable
fun RatingScreen(
    source: RatingSource = RatingSource.MANUAL,
    calibrationModeEnabled: Boolean = false,
    calibrationDurationSeconds: Int = 60,
    onRatingSubmit: (rating: Int, dontAskToday: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    var selectedRating by remember { mutableIntStateOf(0) }
    var showConfirmation by remember { mutableStateOf(false) }
    var dontAskToday by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState()
    
    // Keep screen on during rating
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }
    
    // Rating options with functional labels
    val ratingOptions = remember {
        listOf(
            RatingOption(1, "1 - Minimal", "Not bothering me", "😊"),
            RatingOption(2, "2 - Mild", "Slightly noticeable", "🙂"),
            RatingOption(3, "3 - Moderate", "Noticeably affecting tasks", "😐"),
            RatingOption(4, "4 - Significant", "Limiting daily activities", "😟"),
            RatingOption(5, "5 - Severe", "Very disabling", "😣")
        )
    }
    
    if (showConfirmation) {
        // Confirmation screen with calibration capture info
        ConfirmationScreen(
            rating = selectedRating,
            calibrationModeEnabled = calibrationModeEnabled,
            calibrationDurationSeconds = calibrationDurationSeconds,
            dontAskToday = dontAskToday,
            onConfirm = { 
                onRatingSubmit(selectedRating, dontAskToday)
            },
            onChangeRating = { showConfirmation = false },
            onDontAskTodayChange = { dontAskToday = it }
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
                            showConfirmation = true
                        }
                    )
                }
                
                // Cancel button
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CompactButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("Cancel", fontSize = 10.sp)
                    }
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
        1 -> Color(0xFF4CAF50).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Green
        2 -> Color(0xFF8BC34A).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Light Green
        3 -> Color(0xFFFFEB3B).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Yellow
        4 -> Color(0xFFFF9800).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Orange
        5 -> Color(0xFFF44336).copy(alpha = if (isSelected) 0.3f else 0.1f)  // Red
        else -> MaterialTheme.colors.surface
    }
    
    val textColor = when (option.rating) {
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
 * Confirmation screen shown after selecting a rating
 */
@Composable
private fun ConfirmationScreen(
    rating: Int,
    calibrationModeEnabled: Boolean,
    calibrationDurationSeconds: Int,
    dontAskToday: Boolean,
    onConfirm: () -> Unit,
    onChangeRating: () -> Unit,
    onDontAskTodayChange: (Boolean) -> Unit
) {
    val view = LocalView.current
    val vibrator = LocalContext.current.getSystemService(Vibrator::class.java)
    
    val ratingEmoji = when (rating) {
        1 -> "😊"
        2 -> "🙂"
        3 -> "😐"
        4 -> "😟"
        5 -> "😣"
        else -> "❓"
    }
    
    val ratingLabel = SubjectiveRating.RATING_LABELS[rating] ?: "Unknown"
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = ratingEmoji,
            fontSize = 36.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Rating: $rating",
            style = MaterialTheme.typography.title2
        )
        
        Text(
            text = ratingLabel,
            fontSize = 11.sp,
            color = MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center
        )
        
        if (calibrationModeEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "📊 Calibration: ${calibrationDurationSeconds}s capture",
                fontSize = 10.sp,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Confirm button
        Button(
            onClick = {
                // Strong confirmation haptic
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                onConfirm()
            },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Text(if (calibrationModeEnabled) "Submit & Capture" else "Submit")
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Change rating button
        CompactButton(
            onClick = onChangeRating,
            colors = ButtonDefaults.secondaryButtonColors()
        ) {
            Text("Change", fontSize = 10.sp)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Don't ask today toggle (only for prompted ratings)
        ToggleChip(
            checked = dontAskToday,
            onCheckedChange = onDontAskTodayChange,
            label = {
                Text(
                    text = "Don't ask today",
                    fontSize = 10.sp
                )
            },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.switchIcon(dontAskToday),
                    contentDescription = if (dontAskToday) "Enabled" else "Disabled"
                )
            },
            modifier = Modifier.fillMaxWidth(0.9f)
        )
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
