package com.opensource.tremorwatch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager

/**
 * Calibration screen for establishing personalized tremor baseline.
 * 
 * Shows a 30-second countdown with progress indicator.
 * User should keep their arm still and relaxed during calibration.
 */
@Composable
fun CalibrationScreen(
    isCalibrating: Boolean,
    progress: Float,
    secondsRemaining: Int,
    samplesCollected: Int,
    calibrationComplete: Boolean,
    baselineMagnitude: Float,
    onStartCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onBack: () -> Unit
) {
    // Auto-dismiss after calibration complete
    LaunchedEffect(calibrationComplete) {
        if (calibrationComplete) {
            delay(3000)  // Show success message for 3 seconds
            onBack()
        }
    }
    
    // Keep screen on during calibration
    val view = LocalView.current
    DisposableEffect(isCalibrating) {
        if (isCalibrating) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            calibrationComplete -> {
                // Success state
                Text(
                    "✓ Calibration Complete!",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF4CAF50)  // Green
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    "Your personal baseline:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.secondary
                )
                
                Text(
                    String.format("%.4f", baselineMagnitude),
                    fontSize = 18.sp,
                    color = MaterialTheme.colors.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "Tremor detection is now\npersonalized to you",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.secondary
                )
            }
            
            isCalibrating -> {
                // Calibrating state
                Text(
                    "Calibrating...",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Circular progress indicator
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(60.dp),
                    strokeWidth = 6.dp,
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = MaterialTheme.colors.surface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "${secondsRemaining}s",
                    fontSize = 24.sp,
                    color = MaterialTheme.colors.primary
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    "Keep arm still & relaxed",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.secondary
                )
                
                Text(
                    "Samples: $samplesCollected",
                    fontSize = 10.sp,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Cancel button
                CompactButton(
                    onClick = onCancelCalibration,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    )
                ) {
                    Text("Cancel", fontSize = 10.sp)
                }
            }
            
            else -> {
                // Initial state - ready to calibrate
                Text(
                    "Baseline Calibration",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "Establish your personal\ntremor baseline",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.secondary
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Instructions
                Column(
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    InstructionItem("⚠ Do when tremor is lightest")
                    InstructionItem("1. Rest arm on surface")
                    InstructionItem("2. Be as still as possible")
                    InstructionItem("3. Hold for 30 seconds")
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Start button
                Button(
                    onClick = onStartCalibration,
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text("Start Calibration")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Back button
                CompactButton(
                    onClick = onBack,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Back", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun InstructionItem(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = MaterialTheme.colors.onSurface
    )
    Spacer(modifier = Modifier.height(2.dp))
}

/**
 * Compact calibration status indicator for main screen
 */
@Composable
fun CalibrationStatusIndicator(
    hasCalibrated: Boolean,
    hoursSinceCalibration: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        !hasCalibrated -> Color(0xFFFF9800)  // Orange - needs calibration
        hoursSinceCalibration > 168 -> Color(0xFFFF9800)  // Orange - over 1 week old
        hoursSinceCalibration > 24 -> MaterialTheme.colors.secondary  // Gray - over 1 day
        else -> Color(0xFF4CAF50)  // Green - recent calibration
    }
    
    val statusText = when {
        !hasCalibrated -> "Not calibrated"
        hoursSinceCalibration < 1 -> "Calibrated recently"
        hoursSinceCalibration < 24 -> "Calibrated ${hoursSinceCalibration}h ago"
        hoursSinceCalibration < 168 -> "Calibrated ${hoursSinceCalibration / 24}d ago"
        else -> "Recalibration recommended"
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
                text = if (hasCalibrated) "⚡" else "⚠",
                fontSize = 10.sp
            )
        },
        colors = ChipDefaults.chipColors(
            backgroundColor = statusColor.copy(alpha = 0.2f),
            contentColor = statusColor
        ),
        modifier = modifier
    )
}


