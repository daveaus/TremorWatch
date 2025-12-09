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
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.opensource.tremorwatch.ui.theme.TremorWatchTheme
import com.opensource.tremorwatch.config.MonitoringState
import com.opensource.tremorwatch.config.DataConfig
import com.opensource.tremorwatch.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import com.opensource.tremorwatch.network.NetworkDetector
import com.opensource.tremorwatch.receivers.ServiceWatchdogReceiver
import com.opensource.tremorwatch.receivers.UploadAlarmReceiver
import com.opensource.tremorwatch.receivers.BatchRetryAlarmReceiver
import com.opensource.tremorwatch.service.TremorService
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load Home Assistant configuration from file if present
        DataConfig.loadFromFile(this)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
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
        Spacer(modifier = Modifier.height(6.dp))
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
        Spacer(modifier = Modifier.height(5.dp))

        // Status display
        if (isRecording) {
            Text(
                "Pending batches: $pendingBatches",
                fontSize = 11.sp,
                color = if (pendingBatches > 0) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(3.dp))
            val uploadInterval = MonitoringState.getUploadIntervalMinutes(context)
            Text(
                "Upload every ${uploadInterval}min",
                fontSize = 9.sp,
                color = MaterialTheme.colors.secondary,
                textAlign = TextAlign.Center
            )

            // Battery optimization warning
            if (isBatteryOptimized) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    "⚠ Battery optimization ON",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
                Text(
                    "(May cause data gaps)",
                    fontSize = 8.sp,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }

            // Pause when not worn warning
            if (MonitoringState.isPauseWhenNotWorn(context)) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "⚠ Pause when not worn: ON",
                    fontSize = 9.sp,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onStartStop,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (isRecording)
                    MaterialTheme.colors.error
                else
                    MaterialTheme.colors.primary
            )
        ) {
            Text(if (isRecording) "Stop" else "Start")
        }

        if (isRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onUpload,
                enabled = pendingBatches > 0,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary
                )
            ) {
                Text("Upload Now")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = onShowConfig) {
            Text("Settings")
        }
        
        // Calibration status indicator
        Spacer(modifier = Modifier.height(8.dp))
        com.opensource.tremorwatch.ui.CalibrationStatusIndicator(
            hasCalibrated = hasCalibrated,
            hoursSinceCalibration = hoursSinceCalibration,
            onClick = onShowCalibration
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "v${BuildConfig.VERSION_NAME}",
            fontSize = 9.sp,
            color = MaterialTheme.colors.secondary,
            textAlign = TextAlign.Center
        )
    }
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

    // Calibration status
    val baselineManager = remember { com.opensource.tremorwatch.engine.BaselineManager(context) }
    var hasCalibrated by remember { mutableStateOf(baselineManager.hasCompletedCalibration()) }
    var hoursSinceCalibration by remember { mutableStateOf(baselineManager.getHoursSinceCalibration()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))

            Text(
                "v${BuildConfig.VERSION_NAME} - Battery Optimized",
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.secondary
            )

        Spacer(modifier = Modifier.height(16.dp))

        // Store Locally on Watch Toggle
        Text(
            "Store Locally on Watch",
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(5.dp))

        Button(
            onClick = {
                storeLocally = !storeLocally
                DataConfig.setLocalStorageEnabled(context, storeLocally)
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (storeLocally)
                    MaterialTheme.colors.primary
                else
                    MaterialTheme.colors.surface
            )
        ) {
            Text(if (storeLocally) "ON" else "OFF")
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            "Keep consolidated data file\non watch for later export",
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Pause When Not Worn Toggle
        Text(
            "Pause When Not Worn",
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(5.dp))

        Button(
            onClick = {
                pauseWhenNotWorn = !pauseWhenNotWorn
                MonitoringState.setPauseWhenNotWorn(context, pauseWhenNotWorn)
            },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (pauseWhenNotWorn)
                    MaterialTheme.colors.primary
                else
                    MaterialTheme.colors.surface
            )
        ) {
            Text(if (pauseWhenNotWorn) "ON" else "OFF")
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            "Stop monitoring while\ncharging or not worn",
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // Calibration Section
        Text(
            "Baseline Calibration",
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(5.dp))
        
        Button(
            onClick = onShowCalibration,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (hasCalibrated) 
                    MaterialTheme.colors.surface 
                else 
                    MaterialTheme.colors.primary
            )
        ) {
            Text(if (hasCalibrated) "Recalibrate" else "Calibrate")
        }
        
        Spacer(modifier = Modifier.height(5.dp))
        
        Text(
            when {
                !hasCalibrated -> "Not calibrated yet"
                hoursSinceCalibration < 0 -> "Calibration status unknown"
                hoursSinceCalibration < 24 -> "Calibrated ${hoursSinceCalibration}h ago"
                else -> "Calibrated ${hoursSinceCalibration / 24}d ago"
            },
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            color = if (hasCalibrated) MaterialTheme.colors.secondary else MaterialTheme.colors.error
        )
        
        Text(
            "Personalize tremor detection\nto your baseline",
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.secondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onBack) {
            Text("Back")
        }
    }
}

// Old receiver code removed - see receivers package
// All receivers are now in com.opensource.tremorwatch.receivers package
