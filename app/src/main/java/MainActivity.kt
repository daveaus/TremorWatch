package com.opensource.tremorwatch

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.opensource.tremorwatch.shared.Constants
import com.opensource.tremorwatch.shared.models.RatingSource
import com.opensource.tremorwatch.shared.models.TrainingState
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.opensource.tremorwatch.ui.RatingScreen
import com.opensource.tremorwatch.ui.theme.TremorWatchTheme
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.config.DataConfig
import com.opensource.tremorwatch.data.PreferencesRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import com.opensource.tremorwatch.network.NetworkDetector
import com.opensource.tremorwatch.receivers.ServiceWatchdogReceiver
import com.opensource.tremorwatch.receivers.UploadAlarmReceiver
import com.opensource.tremorwatch.receivers.BatchRetryAlarmReceiver
import com.opensource.tremorwatch.service.TremorService
import com.opensource.tremorwatch.training.TrainingAwareApplication
import com.opensource.tremorwatch.training.TrainingLogEntry
import com.opensource.tremorwatch.training.TrainingManager
import com.opensource.tremorwatch.training.TrainingStatusSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

// MonitoringState, DataConfig, and NetworkDetector have been moved to separate files:
// - com.opensource.tremorwatch.config.MonitoringState
// - com.opensource.tremorwatch.config.DataConfig
// - com.opensource.tremorwatch.network.NetworkDetector
// TremorService has been moved to:
// - com.opensource.tremorwatch.service.TremorService

// ====================== MAIN ACTIVITY ======================
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load Home Assistant configuration from file if present
        DataConfig.loadFromFile(this)

        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.BODY_SENSORS)
        }

        // Request ACTIVITY_RECOGNITION for Android 10+ (API 29)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        // Request POST_NOTIFICATIONS for Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        // Request SCHEDULE_EXACT_ALARM permission for Android 12+ (needed for watchdog)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }



        // Request unrestricted battery access for 24/7 monitoring reliability
        // This is critical for continuous tremor monitoring on Wear OS
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isOptimized = !powerManager.isIgnoringBatteryOptimizations(packageName)
            Log.w(TAG, "★★★ Battery optimization status: ${if (isOptimized) "ENABLED (may cause gaps)" else "DISABLED (good)"}")

            if (isOptimized) {
                Log.e(TAG, "★★★ WARNING: Battery optimization is enabled - this WILL cause data gaps!")
                Log.e(TAG, "★★★ Please disable battery optimization for continuous monitoring")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // If the specific action fails, fall back to general battery settings
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }
            }
        }

        // Auto-start monitoring if it was previously running
        if (MonitoringState.isMonitoring(this)) {
            startRecording()
        }

        setContent {
            TremorMonitorApp(
                initialIsRecording = MonitoringState.isMonitoring(this),
                onStart = { startRecording() },
                onStop = { stopRecording() },
                onUpload = { triggerManualUpload() }
            )
        }
    }

    private fun startRecording() {
        MonitoringState.setMonitoring(this, true)
        val intent = Intent(this, TremorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRecording() {
        MonitoringState.setMonitoring(this, false)
        stopService(Intent(this, TremorService::class.java))
    }

    private fun triggerManualUpload() {
        Log.i(TAG, "Manual upload button pressed")
        // Use explicit broadcast to ensure it reaches the service
        val intent = Intent("com.opensource.tremorwatch.TRIGGER_UPLOAD")
        intent.putExtra("manual", true) // Flag for manual uploads
        intent.setPackage(packageName) // Make it explicit
        sendBroadcast(intent)
        Log.i(TAG, "Upload broadcast sent")
    }
}

// ====================== FOREGROUND SERVICE ======================
// TremorService has been moved to com.opensource.tremorwatch.service.TremorService

// ====================== RECEIVERS ======================
// All receivers have been moved to com.opensource.tremorwatch.receivers package:
// - ServiceWatchdogReceiver
// - UploadAlarmReceiver  
// - BootReceiver
// - BatchRetryAlarmReceiver
// Old implementations removed - see receivers package for current code

// ====================== DISCLAIMER MANAGER ======================
object WatchDisclaimerManager {
    private const val PREFS_NAME = "disclaimer_prefs"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"
    
    fun needsToShowDisclaimer(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val acceptedVersion = prefs.getString(KEY_ACCEPTED_VERSION, null)
        val currentVersion = BuildConfig.VERSION_NAME
        return acceptedVersion != currentVersion
    }
    
    fun recordAcceptance(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCEPTED_VERSION, BuildConfig.VERSION_NAME)
            .apply()
    }
}

// ====================== UI ======================
@Composable
fun TremorMonitorApp(
    initialIsRecording: Boolean = false,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUpload: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRecording by remember { mutableStateOf(initialIsRecording) }
    var showConfig by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showRating by remember { mutableStateOf(false) }
    var showDisclaimer by remember { 
        mutableStateOf(WatchDisclaimerManager.needsToShowDisclaimer(context)) 
    }

    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            when {
                showDisclaimer -> {
                    DisclaimerScreen(
                        onAccept = {
                            WatchDisclaimerManager.recordAcceptance(context)
                            showDisclaimer = false
                        },
                        onDecline = {
                            // Close the app if user doesn't agree
                            (context as? android.app.Activity)?.finish()
                        }
                    )
                }
                showRating -> {
                    val ratingPrefs = context.getSharedPreferences("rating_prefs", Context.MODE_PRIVATE)
                    val calibrationEnabled = ratingPrefs.getBoolean("calibration_enabled", false)
                    val calibrationDuration = ratingPrefs.getInt("calibration_duration_seconds", 60)
                    val watchId = try {
                        android.provider.Settings.Secure.getString(
                            context.contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID
                        ) ?: "unknown"
                    } catch (e: Exception) { "unknown" }

                    RatingScreen(
                        source = RatingSource.MANUAL,
                        calibrationModeEnabled = calibrationEnabled,
                        onRatingSubmit = { rating, dontAskToday ->
                            val ratingId = java.util.UUID.randomUUID().toString()

                            // Get watch-side objective context from service buffer (opus46 Issue 3d)
                            val watchContext = com.opensource.tremorwatch.service.TremorService.getWatchObjectiveContext()

                            WatchDataSender(context).sendSubjectiveRating(
                                ratingId = ratingId,
                                rating = rating,
                                source = "MANUAL",
                                watchId = watchId,
                                calibrationModeEnabled = calibrationEnabled,
                                calibrationDurationSeconds = calibrationDuration,
                                watchObjectiveContext = watchContext
                            ) { success ->
                                android.util.Log.i("MainActivity", "Rating sent: $success")
                            }

                            // Start calibration capture if enabled
                            if (calibrationEnabled) {
                                val calibIntent = Intent(context, com.opensource.tremorwatch.service.TremorService::class.java).apply {
                                    action = com.opensource.tremorwatch.service.TremorService.ACTION_START_CALIBRATION
                                    putExtra(com.opensource.tremorwatch.service.TremorService.EXTRA_RATING_ID, ratingId)
                                    putExtra(com.opensource.tremorwatch.service.TremorService.EXTRA_CALIBRATION_DURATION, calibrationDuration)
                                }
                                context.startService(calibIntent)
                            }

                            showRating = false
                        },
                        onUndo = {
                            // No action needed - just goes back to selection screen
                        },
                        onCancel = { showRating = false }
                    )
                }
                showCalibration -> {
                    CalibrationScreenWrapper(
                        onBack = { showCalibration = false }
                    )
                }
                showConfig -> {
                    ConfigScreen(
                        onBack = { showConfig = false },
                        onShowCalibration = { 
                            showConfig = false
                            showCalibration = true
                        }
                    )
                }
                else -> {
                    MainScreen(
                        isRecording = isRecording,
                        onStartStop = {
                            if (isRecording) onStop() else onStart()
                            isRecording = !isRecording
                        },
                        onShowConfig = { showConfig = true },
                        onShowCalibration = { showCalibration = true },
                        onShowRating = { showRating = true },
                        onUpload = onUpload
                    )
                }
            }
        }
    }
}

@Composable
fun DisclaimerScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var isAgreed by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "⚠️ EXPERIMENTAL",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "This is experimental software.",
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            "NOT for medical use.\nResults are not validated.\nConsult a healthcare professional.",
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Agreement toggle using Wear OS ToggleChip
        ToggleChip(
            checked = isAgreed,
            onCheckedChange = { isAgreed = it },
            label = { Text("I understand", fontSize = 11.sp) },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.checkboxIcon(checked = isAgreed),
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = onAccept,
            enabled = isAgreed,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.primary
            )
        ) {
            Text("OK")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onDecline,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.error
            )
        ) {
            Text("Close")
        }
    }
}

@Composable
fun MainScreen(
    isRecording: Boolean,
    onStartStop: () -> Unit,
    onShowConfig: () -> Unit,
    onShowCalibration: () -> Unit,
    onShowRating: () -> Unit,
    onUpload: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Get pending batch count by checking files directory
    // Use mutableStateOf to make it reactive and update periodically
    var pendingBatches by remember { mutableStateOf(0) }

    // Get actual monitoring state (paused/active)
    var isMonitoringPaused by remember { mutableStateOf(false) }
    var pauseReason by remember { mutableStateOf("") }

    // Calibration status
    var hasCalibrated by remember { mutableStateOf(false) }
    var hoursSinceCalibration by remember { mutableStateOf(-1) }

    // Battery optimization status
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var medicationLogStatus by remember { mutableStateOf<String?>(null) }
    val trainingModeEnabled = MonitoringState.isTrainingMode(context)
    val trainingManager = (context.applicationContext as? TrainingAwareApplication)?.trainingManager
    var trainingStatus by remember { mutableStateOf(TrainingManager.getPersistedStatusSnapshot(context)) }
    var trainingLog by remember { mutableStateOf(TrainingManager.getPersistedRecentLog(context, 5)) }

    // Check calibration status
    LaunchedEffect(Unit) {
        val baselineManager = com.opensource.tremorwatch.engine.BaselineManager(context)
        hasCalibrated = baselineManager.hasCompletedCalibration()
        hoursSinceCalibration = baselineManager.getHoursSinceCalibration()
    }

    // Initial read using PreferencesRepository
    LaunchedEffect(Unit) {
        val prefsRepo = PreferencesRepository(context)
        isMonitoringPaused = prefsRepo.isMonitoringPaused.first()
        pauseReason = prefsRepo.monitoringPauseReason.first()
        android.util.Log.d("MainScreen", "Initial read: paused=$isMonitoringPaused, reason=$pauseReason")
    }

    // Update monitoring state every 2 seconds using PreferencesRepository
    LaunchedEffect(Unit) {
        val prefsRepo = PreferencesRepository(context)
        while (true) {
            delay(2000) // Update every 2 seconds
            val newPaused = prefsRepo.isMonitoringPaused.first()
            val newReason = prefsRepo.monitoringPauseReason.first()
            if (newPaused != isMonitoringPaused || newReason != pauseReason) {
                android.util.Log.d("MainScreen", "State changed: paused=$newPaused, reason=$newReason")
            }
            isMonitoringPaused = newPaused
            pauseReason = newReason
        }
    }

    // Update batch count every 5 seconds when recording
    LaunchedEffect(isRecording) {
        while (isRecording) {
            pendingBatches = try {
                context.filesDir.listFiles { file ->
                    file.name.startsWith("tremor_batch_") && file.name.endsWith(".json")
                }?.size ?: 0
            } catch (e: Exception) {
                0
            }
            delay(5000) // Update every 5 seconds
        }
    }

    // Check battery optimization status every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
            delay(5000) // Check every 5 seconds
        }
    }

    LaunchedEffect(trainingManager, trainingModeEnabled) {
        if (!trainingModeEnabled || trainingManager == null) {
            trainingStatus = TrainingManager.getPersistedStatusSnapshot(context)
            trainingLog = TrainingManager.getPersistedRecentLog(context, 5)
            return@LaunchedEffect
        }

        trainingManager.statusUpdates().collect { snapshot ->
            trainingStatus = snapshot
            trainingLog = trainingManager.getRecentLogEntries(5)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Tremor Monitor",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Text(
            "EXPERIMENTAL",
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            when {
                !isRecording -> "Stopped"
                isMonitoringPaused -> when {
                    pauseReason.contains("charging") -> "Paused (charging)"
                    pauseReason.contains("not worn") -> "Paused (not worn)"
                    else -> "Paused"
                }
                else -> "Recording..."
            },
            fontSize = 14.sp,
            color = when {
                !isRecording -> MaterialTheme.colors.secondary
                isMonitoringPaused -> MaterialTheme.colors.error
                else -> MaterialTheme.colors.primary
            }
        )

        // Status display
        if (isRecording) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Batches: $pendingBatches | Upload: ${MonitoringState.getUploadIntervalMinutes(context)}min",
                fontSize = 9.sp,
                color = if (pendingBatches > 0) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )

            // Battery optimization warning
            if (isBatteryOptimized) {
                Text(
                    "⚠ Battery optimization ON",
                    fontSize = 8.sp,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }

            // Pause when not worn warning
            if (MonitoringState.isPauseWhenNotWorn(context)) {
                Text(
                    "⚠ Pause when not worn: ON",
                    fontSize = 8.sp,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (false) {
            Spacer(modifier = Modifier.height(8.dp))
            Chip(
                onClick = onShowConfig,
                label = {
                    Text(
                        "Training ${formatTrainingUiState(trainingStatus.uiState)}",
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                secondaryLabel = {
                    Text(
                        "Labels ${trainingStatus.usableLabelCount}/${trainingStatus.targetUsableLabelCount}  Y:${trainingStatus.yesLabelCount} N:${trainingStatus.noLabelCount}",
                        fontSize = 9.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                icon = { Text("🧠", fontSize = 16.sp) },
                colors = if (trainingStatus.hasEnoughLabels) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
            Text(
                if (trainingStatus.hasEnoughLabels) {
                    "Enough labels collected for personalization"
                } else {
                    "${(trainingStatus.targetUsableLabelCount - trainingStatus.usableLabelCount).coerceAtLeast(0)} more usable labels needed"
                },
                fontSize = 9.sp,
                color = if (trainingStatus.hasEnoughLabels) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            trainingLog.firstOrNull()?.let { latest ->
                Text(
                    text = "Latest: ${formatTrainingLogLine(context, latest)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Rate Tremor button - full width chip, topmost action
        Chip(
            onClick = onShowRating,
            label = {
                Text(
                    "Rate Tremor",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            icon = { Text("📝", fontSize = 16.sp) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Medication ingestion logger - explicit user-confirmed timestamp.
        Chip(
            onClick = {
                val watchId = try {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: "unknown"
                } catch (_: Exception) {
                    "unknown"
                }

                val ingestionId = java.util.UUID.randomUUID().toString()
                WatchDataSender(context).sendDiagnosticEvent(
                    eventType = "medication_ingestion",
                    eventData = mapOf(
                        "id" to ingestionId,
                        "source" to "WATCH_TAKEN_NOW",
                        "watchId" to watchId,
                        "ingestionTimestamp" to System.currentTimeMillis()
                    )
                ) { success ->
                    medicationLogStatus = if (success) "Dose logged" else "Dose log failed"
                }
            },
            label = {
                Text(
                    "Taken Now",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            icon = { Text("💊", fontSize = 16.sp) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        medicationLogStatus?.let { status ->
            Text(
                text = status,
                fontSize = 9.sp,
                color = if (status.contains("failed", ignoreCase = true)) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.primary
                },
                textAlign = TextAlign.Center
            )
        }

        // Start/Stop button - full width chip
        Chip(
            onClick = onStartStop,
            label = {
                Text(
                    if (isRecording) "Stop Monitoring" else "Start Monitoring",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            icon = { Text(if (isRecording) "⏹" else "▶", fontSize = 16.sp) },
            colors = if (isRecording)
                ChipDefaults.chipColors(
                    backgroundColor = MaterialTheme.colors.error
                )
            else
                ChipDefaults.primaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Upload Now button - only when recording and has batches
        if (isRecording && pendingBatches > 0) {
            Chip(
                onClick = onUpload,
                label = {
                    Text(
                        "Upload Now ($pendingBatches)",
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                icon = { Text("📤", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        // Settings button - full width chip
        Chip(
            onClick = onShowConfig,
            label = {
                Text(
                    "Settings",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            icon = { Text("⚙", fontSize = 16.sp) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Calibration button - full width chip
        Chip(
            onClick = onShowCalibration,
            label = {
                Text(
                    if (hasCalibrated) "Recalibrate" else "Calibrate",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            secondaryLabel = {
                Text(
                    when {
                        !hasCalibrated -> "Not calibrated"
                        hoursSinceCalibration < 24 -> "${hoursSinceCalibration}h ago"
                        else -> "${hoursSinceCalibration / 24}d ago"
                    },
                    fontSize = 10.sp
                )
            },
            icon = { Text("📊", fontSize = 16.sp) },
            colors = if (hasCalibrated)
                ChipDefaults.secondaryChipColors()
            else
                ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Always-visible training card on home screen (kept as the last card).
        Spacer(modifier = Modifier.height(8.dp))
        TrainingHomeCard(
            context = context,
            trainingModeEnabled = trainingModeEnabled,
            trainingStatus = trainingStatus,
            trainingLog = trainingLog,
            onOpenTraining = onShowConfig
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            fontSize = 9.sp,
            color = MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TrainingHomeCard(
    context: Context,
    trainingModeEnabled: Boolean,
    trainingStatus: TrainingStatusSnapshot,
    trainingLog: List<TrainingLogEntry>,
    onOpenTraining: () -> Unit
) {
    val hasAnyTrainingData = trainingStatus.promptsTotal > 0 || trainingStatus.usableLabelCount > 0
    val isComplete = trainingStatus.hasEnoughLabels
    val isInProgress = trainingModeEnabled && !isComplete
    val isNotStarted = !trainingModeEnabled && !hasAnyTrainingData

    val title = when {
        isNotStarted -> "Training: Not Started"
        isComplete -> "Training: Complete"
        isInProgress -> "Training: In Progress"
        else -> "Training: Paused"
    }

    val subtitle = when {
        isNotStarted -> "Personalize tremor detection with quick labels"
        isComplete -> "Results ready for personalized tracking"
        isInProgress -> "Running ${formatElapsedSince(trainingStatus.trainingStartTimeMs)}"
        else -> "Resume training to keep improving"
    }

    val actionText = when {
        isNotStarted -> "Start Training"
        isComplete -> "View Results"
        isInProgress -> "Open Training"
        else -> "Resume Training"
    }

    Chip(
        onClick = onOpenTraining,
        label = {
            Text(
                title,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        secondaryLabel = {
            Text(
                subtitle,
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        icon = { Text("T", fontSize = 14.sp) },
        colors = if (isComplete) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )

    when {
        isNotStarted -> {
            Text(
                text = "Goal: ${trainingStatus.targetUsableLabelCount} usable labels",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
        }
        isInProgress -> {
            Text(
                text = "Labels ${trainingStatus.usableLabelCount}/${trainingStatus.targetUsableLabelCount}  " +
                    "Y:${trainingStatus.yesLabelCount} N:${trainingStatus.noLabelCount} I:${trainingStatus.ignoredLabelCount}",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "${(trainingStatus.targetUsableLabelCount - trainingStatus.usableLabelCount).coerceAtLeast(0)} more usable labels needed",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            trainingLog.firstOrNull()?.let { latest ->
                Text(
                    text = "Latest: ${formatTrainingLogLine(context, latest)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
        isComplete -> {
            Text(
                text = "Completed ${formatCompletedAt(context, trainingStatus)}",
                fontSize = 9.sp,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Results: Y:${trainingStatus.yesLabelCount} N:${trainingStatus.noLabelCount}  " +
                    "Prompts:${trainingStatus.promptsTotal}",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
        }
        else -> {
            Text(
                text = "Current: ${formatTrainingUiState(trainingStatus.uiState)}  " +
                    "Labels ${trainingStatus.usableLabelCount}/${trainingStatus.targetUsableLabelCount}",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            trainingLog.firstOrNull()?.let { latest ->
                Text(
                    text = "Latest: ${formatTrainingLogLine(context, latest)}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    Chip(
        onClick = onOpenTraining,
        label = {
            Text(
                actionText,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        icon = { Text("Go", fontSize = 10.sp) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}

private fun formatTrainingUiState(state: TrainingState): String {
    return when (state) {
        TrainingState.WARMUP -> "WARMUP"
        TrainingState.ACTIVE -> "ACTIVE"
        TrainingState.PERSONALIZED -> "PERSONALIZED"
        TrainingState.READY_TO_FINALIZE -> "READY"
        TrainingState.OFF -> "OFF"
    }
}

private fun formatTrainingLogLine(context: Context, entry: TrainingLogEntry): String {
    val time = formatWatchClockTime(context, entry.timestampMs)
    val emoji = when (entry.label) {
        com.opensource.tremorwatch.shared.models.FeedbackLabel.YES_TREMOR -> "👋"
        com.opensource.tremorwatch.shared.models.FeedbackLabel.NO_ACTIVE -> "👍"
        com.opensource.tremorwatch.shared.models.FeedbackLabel.IGNORE -> "⏭"
        null -> "•"
    }
    return "$time  $emoji ${entry.detail}"
}

private fun formatElapsedSince(startTimestampMs: Long?): String {
    if (startTimestampMs == null || startTimestampMs <= 0L) return "not started"
    val elapsedMs = (System.currentTimeMillis() - startTimestampMs).coerceAtLeast(0L)
    val totalHours = elapsedMs / (60L * 60L * 1000L)
    val days = totalHours / 24L
    val hours = totalHours % 24L
    return if (days > 0L) "${days}d ${hours}h" else "${hours}h"
}

private fun formatCompletedAt(context: Context, status: TrainingStatusSnapshot): String {
    val completionTimestamp =
        status.lastFeedbackTimeMs ?: status.lastPromptTimeMs ?: status.trainingStartTimeMs
    return completionTimestamp?.let { formatWatchClockTime(context, it) } ?: "recently"
}

private fun formatWatchClockTime(context: Context, timestampMs: Long): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
}

/**
 * Wrapper for CalibrationScreen that manages calibration state with BaselineManager
 */
@Composable
fun CalibrationScreenWrapper(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val baselineManager = remember { com.opensource.tremorwatch.engine.BaselineManager(context) }
    
    var isCalibrating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var secondsRemaining by remember { mutableStateOf(30) }
    var samplesCollected by remember { mutableStateOf(0) }
    var calibrationComplete by remember { mutableStateOf(false) }
    var baselineMagnitude by remember { mutableStateOf(0f) }
    
    // Create calibration listener
    val calibrationListener = remember {
        object : com.opensource.tremorwatch.engine.BaselineManager.CalibrationListener {
            override fun onCalibrationProgress(progress: Float, samplesCollected: Int, secondsRemaining: Int) {
                // These will be updated via the LaunchedEffect below
            }
            
            override fun onCalibrationComplete(success: Boolean, baseline: Float, variance: Float) {
                calibrationComplete = success
                baselineMagnitude = baseline
                isCalibrating = false
            }
        }
    }
    
    // Start sensor collection when calibrating
    LaunchedEffect(isCalibrating) {
        if (isCalibrating) {
            // Collect samples from a simulated sensor (in real app, this would come from TremorService)
            val startTime = System.currentTimeMillis()
            val sampleInterval = 100L  // 10 Hz for calibration
            var sampleCount = 0
            
            while (isCalibrating && !calibrationComplete) {
                delay(sampleInterval)
                
                val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                secondsRemaining = (30 - elapsedSeconds).coerceAtLeast(0)
                progress = (elapsedSeconds.toFloat() / 30f).coerceIn(0f, 1f)
                
                // Simulate magnitude sample (in production, this comes from real sensor)
                // For now, we'll just use progress as a proxy to avoid adding sensor complexity
                sampleCount++
                samplesCollected = sampleCount
                
                // Process with actual sensor magnitude if available from service
                // This is a simplified version - the real implementation would get data from TremorService
                val magnitude = 0.15f + (Math.random().toFloat() * 0.05f)  // Simulated resting magnitude
                if (baselineManager.processCalibrationSample(magnitude)) {
                    calibrationComplete = true
                }
            }
        }
    }
    
    com.opensource.tremorwatch.ui.CalibrationScreen(
        isCalibrating = isCalibrating,
        progress = progress,
        secondsRemaining = secondsRemaining,
        samplesCollected = samplesCollected,
        calibrationComplete = calibrationComplete,
        baselineMagnitude = baselineMagnitude,
        onStartCalibration = {
            isCalibrating = true
            calibrationComplete = false
            progress = 0f
            secondsRemaining = 30
            samplesCollected = 0
            baselineManager.startCalibration(calibrationListener)
        },
        onCancelCalibration = {
            isCalibrating = false
            baselineManager.cancelCalibration()
        },
        onBack = onBack
    )
}

@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    onShowCalibration: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pauseWhenNotWorn by remember { mutableStateOf(MonitoringState.isPauseWhenNotWorn(context)) }
    var storeLocally by remember { mutableStateOf(DataConfig.isLocalStorageEnabled(context)) }
    var trainingMode by remember { mutableStateOf(MonitoringState.isTrainingMode(context)) }
    val trainingManager = (context.applicationContext as? TrainingAwareApplication)?.trainingManager
    var trainingStatus by remember { mutableStateOf(TrainingManager.getPersistedStatusSnapshot(context)) }
    var trainingLog by remember { mutableStateOf(TrainingManager.getPersistedRecentLog(context, 8)) }

    // Calibration status
    val baselineManager = remember { com.opensource.tremorwatch.engine.BaselineManager(context) }
    var hasCalibrated by remember { mutableStateOf(baselineManager.hasCompletedCalibration()) }
    var hoursSinceCalibration by remember { mutableStateOf(baselineManager.getHoursSinceCalibration()) }

    LaunchedEffect(trainingManager, trainingMode) {
        if (!trainingMode || trainingManager == null) {
            trainingStatus = TrainingManager.getPersistedStatusSnapshot(context)
            trainingLog = TrainingManager.getPersistedRecentLog(context, 8)
            return@LaunchedEffect
        }

        trainingManager.statusUpdates().collect { snapshot ->
            trainingStatus = snapshot
            trainingLog = trainingManager.getRecentLogEntries(8)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Settings",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )

        Text(
            "v${BuildConfig.VERSION_NAME}",
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Store Locally on Watch Toggle - full width
        ToggleChip(
            checked = storeLocally,
            onCheckedChange = {
                storeLocally = it
                DataConfig.setLocalStorageEnabled(context, it)
            },
            label = {
                Text("Store Locally", fontSize = 14.sp)
            },
            secondaryLabel = {
                Text("Keep data on watch", fontSize = 10.sp)
            },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.switchIcon(storeLocally),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Pause When Not Worn Toggle - full width
        ToggleChip(
            checked = pauseWhenNotWorn,
            onCheckedChange = {
                pauseWhenNotWorn = it
                MonitoringState.setPauseWhenNotWorn(context, it)
            },
            label = {
                Text("Pause When Not Worn", fontSize = 14.sp)
            },
            secondaryLabel = {
                Text("Stop when charging/off wrist", fontSize = 10.sp)
            },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.switchIcon(pauseWhenNotWorn),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        // Training Mode Toggle - Active Learning
        ToggleChip(
            checked = trainingMode,
            onCheckedChange = {
                trainingMode = it
                MonitoringState.setTrainingMode(context, it)
            },
            label = {
                Text("Training Mode", fontSize = 14.sp)
            },
            secondaryLabel = {
                Text(
                    if (trainingMode) "Learning your tremor…"
                    else "Personalize detection",
                    fontSize = 10.sp
                )
            },
            toggleControl = {
                Icon(
                    imageVector = ToggleChipDefaults.switchIcon(trainingMode),
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        if (trainingMode) {
            Chip(
                onClick = {},
                label = {
                    Text(
                        "State: ${formatTrainingUiState(trainingStatus.uiState)}",
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                secondaryLabel = {
                    Text(
                        "Usable ${trainingStatus.usableLabelCount}/${trainingStatus.targetUsableLabelCount}  Y:${trainingStatus.yesLabelCount} N:${trainingStatus.noLabelCount}  I:${trainingStatus.ignoredLabelCount}",
                        fontSize = 9.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                icon = { Text("🧠", fontSize = 15.sp) },
                colors = if (trainingStatus.hasEnoughLabels) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )

            Text(
                text = if (trainingStatus.hasEnoughLabels) {
                    "Enough labels collected"
                } else {
                    "${(trainingStatus.targetUsableLabelCount - trainingStatus.usableLabelCount).coerceAtLeast(0)} usable labels remaining"
                },
                fontSize = 9.sp,
                color = if (trainingStatus.hasEnoughLabels) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )

            if (trainingLog.isEmpty()) {
                Text(
                    text = "No training prompts logged yet",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.secondary,
                    textAlign = TextAlign.Center
                )
            } else {
                trainingLog.take(5).forEach { entry ->
                    Text(
                        text = formatTrainingLogLine(context, entry),
                        fontSize = 9.sp,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Calibration button - full width chip
        Chip(
            onClick = onShowCalibration,
            label = {
                Text(
                    if (hasCalibrated) "Recalibrate Baseline" else "Calibrate Baseline",
                    fontSize = 14.sp
                )
            },
            secondaryLabel = {
                Text(
                    when {
                        !hasCalibrated -> "Not calibrated yet"
                        hoursSinceCalibration < 0 -> "Status unknown"
                        hoursSinceCalibration < 24 -> "Done ${hoursSinceCalibration}h ago"
                        else -> "Done ${hoursSinceCalibration / 24}d ago"
                    },
                    fontSize = 10.sp
                )
            },
            icon = { Text("📊", fontSize = 16.sp) },
            colors = if (hasCalibrated)
                ChipDefaults.secondaryChipColors()
            else
                ChipDefaults.chipColors(backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Back button - full width chip
        Chip(
            onClick = onBack,
            label = {
                Text(
                    "Back to Home",
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            icon = { Text("←", fontSize = 16.sp) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
    }
}

// Old receiver code removed - see receivers package
// All receivers are now in com.opensource.tremorwatch.receivers package
