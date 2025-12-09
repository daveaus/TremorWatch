package com.opensource.tremorwatch.phone.ui.dialogs

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensource.tremorwatch.phone.BuildConfig

/**
 * Disclaimer dialog shown on first launch or after app update.
 * User must agree to the terms before using the app.
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var isAgreed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* Don't allow dismiss by clicking outside */ },
        title = {
            Text(
                text = "⚠️ Important Disclaimer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "EXPERIMENTAL SOFTWARE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "This application is an experimental tool for monitoring tremor activity. " +
                           "It is NOT a medical device and has not been approved by any regulatory body.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Please understand that:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val points = listOf(
                    "• Results should NOT be used for medical diagnosis or treatment decisions",
                    "• Tremor detection accuracy has not been clinically validated",
                    "• Always consult a qualified healthcare professional for medical advice",
                    "• This app is for personal tracking and research purposes only",
                    "• The developers are not responsible for any decisions made based on this data"
                )
                
                points.forEach { point ->
                    Text(
                        text = point,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Before using this app, please do your own research about tremor monitoring " +
                           "and consult with your healthcare provider.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Agreement checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isAgreed,
                        onCheckedChange = { isAgreed = it }
                    )
                    Text(
                        text = "I understand and agree to these terms",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = isAgreed
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) {
                Text("Close")
            }
        }
    )
}

/**
 * Helper object to manage disclaimer acceptance state
 */
object DisclaimerManager {
    private const val PREFS_NAME = "disclaimer_prefs"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    
    /**
     * Check if disclaimer needs to be shown.
     * Returns true if never accepted or accepted version differs from current version.
     */
    fun needsToShowDisclaimer(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val acceptedVersion = prefs.getString(KEY_ACCEPTED_VERSION, null)
        val currentVersion = BuildConfig.VERSION_NAME
        
        return acceptedVersion != currentVersion
    }
    
    /**
     * Record that user accepted disclaimer for current version.
     */
    fun recordAcceptance(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCEPTED_VERSION, BuildConfig.VERSION_NAME)
            .apply()
    }
}
