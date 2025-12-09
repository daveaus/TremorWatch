package com.opensource.tremorwatch.phone.ui.dialogs

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen Log Viewer with colored syntax highlighting.
 * Displays logs with color coding based on log level (Error, Warning, Info, Debug, Verbose).
 */
@Composable
fun LogViewerDialog(
    logs: String,
    title: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    // Parse log lines and colorize - REVERSED so newest logs appear at top
    val logLines = remember(logs) {
        Log.d("LogViewerDialog", "Parsing logs - length: ${logs.length}, lines: ${logs.lines().size}")
        logs.lines().reversed().map { line ->
            val color = when {
                line.contains(" E ") || line.contains("ERROR") || line.contains("✗") -> Color(0xFFFF5252) // Red
                line.contains(" W ") || line.contains("WARN") || line.contains("⚠") -> Color(0xFFFFAB00) // Orange
                line.contains(" I ") || line.contains("INFO") || line.contains("★") -> Color(0xFF64B5F6) // Blue
                line.contains(" D ") || line.contains("DEBUG") -> Color(0xFF81C784) // Green
                line.contains(" V ") || line.contains("VERBOSE") -> Color(0xFFB0BEC5) // Gray
                else -> Color.White
            }
            line to color
        }
    }

    Log.d("LogViewerDialog", "LogViewerDialog rendered - title: $title, logLines count: ${logLines.size}")

    // Use Dialog composable for proper overlay
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // Allow full-screen width
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF1E1E1E) // Dark background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onShare) {
                            Text("Share")
                        }
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }

                Divider(color = Color.Gray)

                // Log content - use weight to give it remaining space
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(logLines.size) { index ->
                        val (line, color) = logLines[index]
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = color,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
