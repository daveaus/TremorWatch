package com.opensource.tremorwatch.phone

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.core.content.FileProvider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.opensource.tremorwatch.phone.ui.theme.TremorWatchPhoneTheme
import com.opensource.tremorwatch.phone.aggregateData
import com.opensource.tremorwatch.phone.calculateTremorRatings
import com.opensource.tremorwatch.phone.getStorageStats
import com.opensource.tremorwatch.phone.loadLocalData
import com.opensource.tremorwatch.phone.ChartDataSet
import com.opensource.tremorwatch.phone.StorageStats
import com.opensource.tremorwatch.phone.TremorRating
import com.opensource.tremorwatch.phone.StatBox
import com.opensource.tremorwatch.phone.ChartsSection
import com.opensource.tremorwatch.phone.GapEvent
import com.opensource.tremorwatch.phone.GapType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import com.opensource.tremorwatch.shared.models.TremorBatch
import com.opensource.tremorwatch.phone.ui.dialogs.ExportDialog
import com.opensource.tremorwatch.phone.ui.dialogs.LogViewerDialog
import com.opensource.tremorwatch.phone.ui.dialogs.DisclaimerDialog
import com.opensource.tremorwatch.phone.ui.dialogs.DisclaimerManager
import com.opensource.tremorwatch.phone.ui.TremorDetectionSettingsScreen

class MainActivity : AppCompatActivity() {
    companion object {
        private const val BATCH_RETRY_REQUEST_CODE = 1001
        private const val RETRY_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val INITIAL_DELAY_MS = 5 * 60 * 1000L // 5 minutes

        fun scheduleBatchRetryAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BatchRetryAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                BATCH_RETRY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val initialTriggerTime = System.currentTimeMillis() + INITIAL_DELAY_MS + Random.nextLong(0, 60000)

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                initialTriggerTime,
                RETRY_INTERVAL_MS,
                pendingIntent
            )
        }

        fun cancelBatchRetryAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BatchRetryAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                BATCH_RETRY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )

            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }

        @JvmStatic
        fun sendPendingBatches(context: Context) {
            val pendingDir = File(context.filesDir, "pending_batches")
            if (pendingDir.exists() && pendingDir.listFiles()?.isNotEmpty() == true) {
                android.util.Log.d("MainActivity", "Found pending batches, initiating retry")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set status bar to black/dark
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.isAppearanceLightStatusBars = false

        requestBatteryOptimizationExclusion()
        startPersistentUploadService()

        setContent {
            TremorWatchPhoneTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TremorWatchApp()
                }
            }
        }
    }

    private fun requestBatteryOptimizationExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    android.util.Log.i("MainActivity", "Requesting battery optimization exclusion")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to request battery optimization exclusion: ${e.message}", e)
                }
            } else {
                android.util.Log.d("MainActivity", "Battery optimization already disabled")
            }
        }
    }

    private fun startPersistentUploadService() {
        try {
            val intent = Intent(this, UploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start upload service: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        cancelBatchRetryAlarm(this)
        super.onDestroy()
    }
}

@Composable
fun TremorWatchApp() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf("main") }
    var showDisclaimer by remember { 
        mutableStateOf(DisclaimerManager.needsToShowDisclaimer(context)) 
    }
    
    // Define location permission launcher here so it can be passed to screens
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted/denied
    }
    
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Background location granted/denied
    }

    // Show disclaimer if needed (first launch or app update)
    if (showDisclaimer) {
        DisclaimerDialog(
            onAccept = {
                DisclaimerManager.recordAcceptance(context)
                showDisclaimer = false
            },
            onDecline = {
                // Close the app if user doesn't agree
                (context as? android.app.Activity)?.finish()
            }
        )
    } else {
        // Handle back button press
        BackHandler(enabled = currentScreen != "main") {
            currentScreen = "main"
        }

        when (currentScreen) {
            "main" -> MainScreen(
                onNavigateToSettings = { currentScreen = "settings" },
                locationPermissionLauncher = locationPermissionLauncher
            )
            "settings" -> SettingsScreen(
                onNavigateBack = { currentScreen = "main" },
                onNavigateToAlgorithmSettings = { currentScreen = "algorithm_settings" },
                locationPermissionLauncher = locationPermissionLauncher
            )
            "algorithm_settings" -> TremorDetectionSettingsScreen(
                onNavigateBack = { currentScreen = "settings" }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var batchesPending by remember { mutableStateOf(getPendingBatchCount(context)) }
    var batchesUploadedToday by remember { mutableStateOf(PhoneDataConfig.getBatchesUploadedToday(context)) }
    var lastUploadTime by remember { mutableStateOf(PhoneDataConfig.getLastUploadTime(context)) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    var isUploadButtonPressed by remember { mutableStateOf(false) }
    var uploadButtonPressedTime by remember { mutableStateOf(0L) }

    val uploadsPerMinute = UploadMetrics.uploadsPerMinute.collectAsState()
    val isCurrentlyUploading = UploadMetrics.isCurrentlyUploading.collectAsState()
    val lastUploadSpeed = UploadMetrics.lastUploadSpeed.collectAsState()
    val failedUploadsCount = UploadMetrics.failedUploadsCount.collectAsState()
    val uploadHistory = UploadMetrics.uploadHistory.collectAsState()
    val totalBytesTransferred = UploadMetrics.totalBytesTransferred.collectAsState()

    var lastHeartbeatTime by remember { mutableStateOf(0L) }
    var watchServiceUptime by remember { mutableStateOf(0L) }
    var watchMonitoringState by remember { mutableStateOf("unknown") }
    var lastDataReceivedTime by remember { mutableStateOf(0L) }
    var isBatteryOptimized by remember { mutableStateOf(false) }
    var isWatchBatteryOptimized by remember { mutableStateOf(false) }

    val lastUpload = remember(lastUploadTime, refreshTrigger) {
        if (lastUploadTime == 0L) {
            "Never"
        } else {
            val elapsed = System.currentTimeMillis() - lastUploadTime
            when {
                elapsed < 60 * 1000 -> "Just now"
                elapsed < 60 * 60 * 1000 -> "${elapsed / (60 * 1000)}m ago"
                elapsed < 24 * 60 * 60 * 1000 -> "${elapsed / (60 * 60 * 1000)}h ago"
                else -> "${elapsed / (24 * 60 * 60 * 1000)}d ago"
            }
        }
    }

    val lastHeartbeat = remember(lastHeartbeatTime, refreshTrigger) {
        if (lastHeartbeatTime == 0L) {
            "Never"
        } else {
            val elapsed = System.currentTimeMillis() - lastHeartbeatTime
            when {
                elapsed < 60 * 1000 -> "Just now"
                elapsed < 60 * 60 * 1000 -> "${elapsed / (60 * 1000)}m ago"
                elapsed < 24 * 60 * 60 * 1000 -> "${elapsed / (60 * 60 * 1000)}h ago"
                else -> "${elapsed / (24 * 60 * 60 * 1000)}d ago"
            }
        }
    }

    val watchStatus = remember(watchMonitoringState, refreshTrigger) {
        when (watchMonitoringState) {
            "active" -> "Active"
            "paused_charging" -> "Paused (charging)"
            "paused_not_worn" -> "Paused (not worn)"
            else -> "Unknown"
        }
    }

    val lastDataReceived = remember(lastDataReceivedTime, refreshTrigger) {
        if (lastDataReceivedTime == 0L) {
            "Never"
        } else {
            val elapsed = System.currentTimeMillis() - lastDataReceivedTime
            when {
                elapsed < 60 * 1000 -> "Just now"
                elapsed < 60 * 60 * 1000 -> "${elapsed / (60 * 1000)}m ago"
                elapsed < 24 * 60 * 60 * 1000 -> "${elapsed / (60 * 60 * 1000)}h ago"
                else -> "${elapsed / (24 * 60 * 60 * 1000)}d ago"
            }
        }
    }

    var retentionHours by remember { mutableStateOf(PhoneDataConfig.getLocalStorageRetentionHours(context)) }
    var localStorageEnabled by remember { mutableStateOf(PhoneDataConfig.isLocalStorageEnabled(context)) }

    // Initial load
    LaunchedEffect(Unit) {
        val heartbeatPrefs = context.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
        lastHeartbeatTime = heartbeatPrefs.getLong("last_heartbeat_time", 0)
        watchServiceUptime = heartbeatPrefs.getLong("watch_service_uptime", 0)
        watchMonitoringState = heartbeatPrefs.getString("watch_monitoring_state", "unknown") ?: "unknown"
        isWatchBatteryOptimized = heartbeatPrefs.getBoolean("watch_battery_optimized", false)
        lastDataReceivedTime = NotificationHelper.getLastReceivedTime(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000) // 2 seconds
            batchesPending = getPendingBatchCount(context)
            batchesUploadedToday = PhoneDataConfig.getBatchesUploadedToday(context)
            lastUploadTime = PhoneDataConfig.getLastUploadTime(context)
            retentionHours = PhoneDataConfig.getLocalStorageRetentionHours(context)
            localStorageEnabled = PhoneDataConfig.isLocalStorageEnabled(context)

            val heartbeatPrefs = context.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
            lastHeartbeatTime = heartbeatPrefs.getLong("last_heartbeat_time", 0)
            watchServiceUptime = heartbeatPrefs.getLong("watch_service_uptime", 0)
            watchMonitoringState = heartbeatPrefs.getString("watch_monitoring_state", "unknown") ?: "unknown"
            isWatchBatteryOptimized = heartbeatPrefs.getBoolean("watch_battery_optimized", false)

            // Check last data received time
            lastDataReceivedTime = NotificationHelper.getLastReceivedTime(context)

            // Check battery optimization status for phone
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }

            refreshTrigger++
        }
    }

    val storageStats by produceState(initialValue = StorageStats(false, 0.0, 0, 0), refreshTrigger) {
        value = withContext(Dispatchers.IO) { getStorageStats(context) }
    }

    // Load data with OOM protection - load 48h of data for the unified chart
    var allChartDataState by remember { mutableStateOf(emptyList<ChartData>()) }
    var gapEventsState by remember { mutableStateOf(emptyList<GapEvent>()) }
    var isDataLoading by remember { mutableStateOf(true) }  // Track loading state
    var dataLoadTrigger by remember { mutableStateOf(0) }
    
    // Trigger data reload every 60 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(60000)
            dataLoadTrigger++
        }
    }
    
    // Load 48h of data for the unified chart (supports scrolling back)
    LaunchedEffect(dataLoadTrigger, localStorageEnabled) {
        Log.d("MainActivity", "=== Chart Data Loading ===")
        Log.d("MainActivity", "LaunchedEffect triggered: trigger=$dataLoadTrigger, localStorage=$localStorageEnabled")
        
        if (!localStorageEnabled) {
            Log.d("MainActivity", "Local storage disabled, skipping data load")
            isDataLoading = false
            allChartDataState = emptyList()
            gapEventsState = emptyList()
            return@LaunchedEffect
        }
        
        // Quick database check to skip loading state if empty
        val dbHelper = com.opensource.tremorwatch.phone.database.TremorDatabaseHelper(context)
        val stats = withContext(Dispatchers.IO) { dbHelper.getStats() }
        if (stats.totalSamples == 0) {
            Log.d("MainActivity", "Database empty (0 samples), showing empty state immediately")
            isDataLoading = false
            allChartDataState = emptyList()
            gapEventsState = emptyList()
            return@LaunchedEffect
        }
        
        if (dataLoadTrigger == 0) {
            isDataLoading = true  // Only show loading on initial load
        }
        try {
            Log.d("MainActivity", "Starting data load (retention period)...")
            // Get retention period from settings (default 168 hours = 7 days)
            val retentionHours = PhoneDataConfig.getLocalStorageRetentionHours(context)
            Log.d("MainActivity", "Loading $retentionHours hours of data based on retention setting")
            // Use NonCancellable to ensure data loading completes even if composition leaves
            // This prevents LeftCompositionCancellationException when app is opened after being idle
            val rawData = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try {
                    loadLocalData(context, retentionHours)  // Load based on retention period
                } catch (e: OutOfMemoryError) {
                    Log.e("MainActivity", "OOM loading chart data: ${e.message}")
                    emptyList()
                }
            }
            Log.d("MainActivity", "Raw data loaded: ${rawData.size} points")
            allChartDataState = rawData
            
            // Detect gaps in the data with improved classification
            val gaps = mutableListOf<GapEvent>()
            if (rawData.size > 1) {
                val sorted = rawData.sortedBy { it.timestamp }
                for (i in 1 until sorted.size) {
                    val timeDiff = sorted[i].timestamp - sorted[i-1].timestamp

                    // Only consider it a gap if > 2 minutes (allows for normal batch intervals)
                    if (timeDiff > 2 * 60 * 1000) {
                        // Determine gap type from metadata
                        val prevPoint = sorted[i-1]
                        val nextPoint = sorted[i]

                        // Helper to safely get boolean from metadata
                        fun getBooleanMeta(data: ChartData, key: String): Boolean? {
                            return when (val value = data.metadata[key]) {
                                is Boolean -> value
                                is String -> value.toBoolean()
                                is Number -> value.toInt() != 0
                                else -> null
                            }
                        }

                        // Check the last known state (from previous point)
                        val wasWorn = getBooleanMeta(prevPoint, "isWorn") ?: true
                        val wasCharging = getBooleanMeta(prevPoint, "isCharging") ?: false

                        // Classify gap type based on state
                        val gapType = when {
                            wasCharging -> GapType.CHARGING
                            !wasWorn -> GapType.OFF_WRIST
                            else -> GapType.NO_DATA
                        }

                        gaps.add(GapEvent(
                            startTime = sorted[i-1].timestamp,
                            endTime = sorted[i].timestamp,
                            type = gapType
                        ))
                    }
                }
            }
            gapEventsState = gaps
            isDataLoading = false  // Done loading
            
            // Log data range for debugging
            if (rawData.isNotEmpty()) {
                val sorted = rawData.sortedBy { it.timestamp }
                val minTime = sorted.first().timestamp
                val maxTime = sorted.last().timestamp
                val minDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(minTime))
                val maxDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(maxTime))
                val now = System.currentTimeMillis()
                val daysAgo = (now - maxTime) / (24 * 60 * 60 * 1000L)
                Log.i("MainActivity", "Loaded ${rawData.size} data points, detected ${gaps.size} gaps")
                Log.i("MainActivity", "Data range: $minDate to $maxDate (most recent data is $daysAgo days ago)")
            } else {
                Log.w("MainActivity", "No data loaded - empty result from loadLocalData")
                Log.i("MainActivity", "Loaded ${rawData.size} data points, detected ${gaps.size} gaps")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading chart data: ${e.message}", e)
            isDataLoading = false
        }
    }
    
    val allChartData = allChartDataState
    val gapEvents = gapEventsState
    
    // Keep legacy data for backward compatibility (can be removed later)
    val data1Hour = ChartDataSet(0, emptyList<ChartData>())
    val data12Hours = ChartDataSet(0, emptyList<ChartData>())
    val data24Hours = ChartDataSet(0, emptyList<ChartData>())
    val tremorRatings = emptyList<TremorRating>()

    // Permission states - these need to be refreshed when app resumes
    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasBackgroundLocationPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(true) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    
    // Refresh permission states on each refreshTrigger (every 2 seconds)
    // This ensures permissions update when user returns from settings
    LaunchedEffect(refreshTrigger) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed on older versions
        }
        
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions.values.all { it }
        // After getting foreground permission, request background on Android 10+
        if (hasLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission) {
            showPermissionDialog = true
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBackgroundLocationPermission = granted
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }
    
    // Check location services enabled
    var isLocationEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTrigger) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        isLocationEnabled = locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                           locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
    }

    // Request notification permission on launch (location only when InfluxDB enabled)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    // Background location permission dialog
    if (showPermissionDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Background Location Needed") },
            text = { 
                Text("To detect your home WiFi network for automatic uploads, please select 'Allow all the time' on the next screen.\n\nThis allows the app to check WiFi even when running in the background.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Not Now")
                }
            }
        )
    }

    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing)

    SwipeRefresh(
        state = swipeRefreshState,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                
                // Refresh watch status
                val heartbeatPrefs = context.getSharedPreferences("heartbeat_prefs", Context.MODE_PRIVATE)
                lastHeartbeatTime = heartbeatPrefs.getLong("last_heartbeat_time", 0)
                watchServiceUptime = heartbeatPrefs.getLong("watch_service_uptime", 0)
                watchMonitoringState = heartbeatPrefs.getString("watch_monitoring_state", "unknown") ?: "unknown"
                isWatchBatteryOptimized = heartbeatPrefs.getBoolean("watch_battery_optimized", false)
                lastDataReceivedTime = NotificationHelper.getLastReceivedTime(context)
                
                // Refresh stats
                batchesPending = getPendingBatchCount(context)
                batchesUploadedToday = PhoneDataConfig.getBatchesUploadedToday(context)
                lastUploadTime = PhoneDataConfig.getLastUploadTime(context)
                
                // Trigger chart reload
                refreshTrigger++
                
                delay(500)
                isRefreshing = false
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Header with Title and Settings Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TremorWatch",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    // Experimental badge
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "EXPERIMENTAL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Permission Status Card (show if InfluxDB is enabled AND permissions missing)
        val influxDbEnabled = remember { mutableStateOf(PhoneDataConfig.isInfluxDbEnabled(context)) }
        LaunchedEffect(refreshTrigger) {
            influxDbEnabled.value = PhoneDataConfig.isInfluxDbEnabled(context)
        }
        
        val needsPermissionFix = influxDbEnabled.value && (!hasLocationPermission || !hasBackgroundLocationPermission || !isLocationEnabled)
        if (needsPermissionFix) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "⚠️ Permissions Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "WiFi detection requires location permissions to identify your home network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (hasLocationPermission) "✓" else "✗",
                                color = if (hasLocationPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = " Location Permission",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (hasBackgroundLocationPermission) "✓" else "✗",
                                    color = if (hasBackgroundLocationPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = " Background Location (Allow all the time)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isLocationEnabled) "✓" else "✗",
                                color = if (isLocationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = " Location Services Enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasLocationPermission || !hasBackgroundLocationPermission) {
                            Button(
                                onClick = {
                                    // Open app settings
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("App Settings")
                            }
                        }
                        if (!isLocationEnabled) {
                            Button(
                                onClick = {
                                    // Open location settings
                                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Enable Location")
                            }
                        }
                    }
                }
            }
        }

        // Watch Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Watch Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatBox(label = "Status", value = watchStatus)
                    StatBox(label = "Heartbeat", value = lastHeartbeat)
                    StatBox(label = "Last Received", value = lastDataReceived)
                }

                // Battery optimization warning - Phone
                if (isBatteryOptimized) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "⚠️ Phone Battery Optimization Enabled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "May affect background data reception",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to open battery settings: ${e.message}")
                                    }
                                }
                            ) {
                                Text("Fix")
                            }
                        }
                    }
                }

                // Battery optimization warning - Watch
                if (isWatchBatteryOptimized) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ Watch Battery Optimization Enabled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "May affect watch monitoring. Disable via adb:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text = "adb -s <watch_ip> shell dumpsys deviceidle whitelist +com.opensource.tremorwatch",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }


        // Charts Section - Unified with time range selector and navigation
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!localStorageEnabled) {
                    Text(
                        text = "Charts & Visualizations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Enable local storage in Settings to view charts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else if (isDataLoading && allChartData.isEmpty()) {
                    Text(
                        text = "Charts & Visualizations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Loading data...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else if (allChartData.isEmpty()) {
                    Text(
                        text = "Charts & Visualizations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "No data available yet. Charts will appear once tremor data is received and stored.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    // Use the full data we loaded (12 hours)
                    ChartsSection(
                        data = allChartData,
                        gapEvents = gapEvents,
                        hoursBack = 12
                    )
                }
            }
        }

        // Upload Metrics
        if (uploadsPerMinute.value > 0 || totalBytesTransferred.value > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Upload Metrics",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox(label = "Uploads/min", value = String.format("%.1f", uploadsPerMinute.value))
                        StatBox(label = "Data", value = String.format("%.1f MB", totalBytesTransferred.value / (1024.0 * 1024.0)))
                        if (failedUploadsCount.value > 0) {
                            StatBox(label = "Failed", value = failedUploadsCount.value.toString())
                        }
                    }
                }
            }
        }
    }
}
    }

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAlgorithmSettings: () -> Unit,
    locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // InfluxDB state
    val initialInfluxUrl = PhoneDataConfig.getInfluxDbUrl(context)
    val initialInfluxDatabase = PhoneDataConfig.getInfluxDbDatabase(context)
    val initialInfluxUsername = PhoneDataConfig.getInfluxDbUsername(context)
    val initialInfluxPassword = PhoneDataConfig.getInfluxDbPassword(context)
    
    var influxUrl by remember { mutableStateOf(initialInfluxUrl) }
    var influxDatabase by remember { mutableStateOf(initialInfluxDatabase) }
    var influxUsername by remember { mutableStateOf(initialInfluxUsername) }
    var influxPassword by remember { mutableStateOf(initialInfluxPassword) }
    
    // Track if InfluxDB fields have been edited
    var influxDbEdited by remember { mutableStateOf(false) }
    var influxDbSaving by remember { mutableStateOf(false) }
    var influxDbStatus by remember { mutableStateOf<String?>(null) }
    
    // WiFi state
    val initialHomeWifiSsids = PhoneDataConfig.getHomeWifiSsids(context).joinToString(", ")
    val initialHomeWifiBssids = PhoneDataConfig.getHomeWifiBssids(context).joinToString(", ")
    val initialAllowManualAnyNetwork = PhoneDataConfig.getAllowManualAnyNetwork(context)
    var homeWifiSsids by remember { mutableStateOf(initialHomeWifiSsids) }
    var homeWifiBssids by remember { mutableStateOf(initialHomeWifiBssids) }
    var allowManualAnyNetwork by remember { mutableStateOf(initialAllowManualAnyNetwork) }
    var wifiEdited by remember { mutableStateOf(false) }
    var currentWifiInfo by remember { mutableStateOf<String?>(null) }
    var currentBssid by remember { mutableStateOf<String?>(null) }
    
    // Local Storage state
    val initialLocalStorageEnabled = PhoneDataConfig.isLocalStorageEnabled(context)
    val initialRetentionHours = PhoneDataConfig.getLocalStorageRetentionHours(context).toString()
    var localStorageEnabled by remember { mutableStateOf(initialLocalStorageEnabled) }
    var retentionHours by remember { mutableStateOf(initialRetentionHours) }
    var localStorageEdited by remember { mutableStateOf(false) }
    
    // InfluxDB enabled toggle state (separate from configuration)
    val initialInfluxDbEnabled = PhoneDataConfig.isInfluxDbEnabled(context)
    var influxDbEnabled by remember { mutableStateOf(initialInfluxDbEnabled) }
    
    // Check if InfluxDB fields changed
    LaunchedEffect(influxUrl, influxDatabase, influxUsername, influxPassword) {
        influxDbEdited = influxUrl != initialInfluxUrl ||
                influxDatabase != initialInfluxDatabase ||
                influxUsername != initialInfluxUsername ||
                influxPassword != initialInfluxPassword
    }
    
    // Check if WiFi fields changed
    LaunchedEffect(homeWifiSsids, homeWifiBssids, allowManualAnyNetwork) {
        wifiEdited = homeWifiSsids != initialHomeWifiSsids ||
                homeWifiBssids != initialHomeWifiBssids ||
                allowManualAnyNetwork != initialAllowManualAnyNetwork
    }
    
    // Function to detect current WiFi info
    fun detectCurrentWifi() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            if (wifiInfo != null) {
                val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Not connected"
                val bssid = wifiInfo.bssid ?: "Not available"
                currentWifiInfo = "SSID: $ssid\nBSSID: $bssid"
                currentBssid = if (bssid != "Not available" && bssid.isNotEmpty()) bssid.lowercase() else null
            } else {
                currentWifiInfo = "WiFi not connected"
                currentBssid = null
            }
        } catch (e: Exception) {
            currentWifiInfo = "Error: ${e.message}"
            currentBssid = null
        }
    }
    
    // Function to copy BSSID to clipboard
    fun copyBssidToClipboard() {
        currentBssid?.let { bssid ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("BSSID", bssid)
            clipboard?.setPrimaryClip(clip)
        }
    }
    
    // Function to add BSSID to home BSSIDs list
    fun addBssidToHome() {
        currentBssid?.let { bssid ->
            val currentBssids = homeWifiBssids.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableList()
            if (!currentBssids.contains(bssid.lowercase())) {
                currentBssids.add(bssid.lowercase())
                homeWifiBssids = currentBssids.joinToString(", ")
            }
        }
    }
    
    // Check if Local Storage fields changed
    LaunchedEffect(localStorageEnabled, retentionHours) {
        localStorageEdited = localStorageEnabled != initialLocalStorageEnabled ||
                retentionHours != initialRetentionHours
    }
    
    val isConfigured = remember(influxUrl, influxDatabase) {
        influxUrl.isNotEmpty() && influxDatabase.isNotEmpty()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onNavigateBack) {
                Text("Back")
            }
        }

        // Upload & Export Actions Card
        var showExportDialog by remember { mutableStateOf(false) }
        var showPhoneLogsDialog by remember { mutableStateOf(false) }
        var showWatchLogsDialog by remember { mutableStateOf(false) }
        var phoneLogs by remember { mutableStateOf("") }
        var watchLogs by remember { mutableStateOf("") }
        var loadingPhoneLogs by remember { mutableStateOf(false) }
        var loadingWatchLogs by remember { mutableStateOf(false) }
        var batchesPending by remember { mutableStateOf(getPendingBatchCount(context)) }
        var batchesUploadedToday by remember { mutableStateOf(PhoneDataConfig.getBatchesUploadedToday(context)) }
        var lastUploadTime by remember { mutableStateOf(PhoneDataConfig.getLastUploadTime(context)) }
        val lastUpload = remember(lastUploadTime) {
            if (lastUploadTime == 0L) "Never" else {
                val now = System.currentTimeMillis()
                val diff = now - lastUploadTime
                when {
                    diff < 60_000 -> "Just now"
                    diff < 3600_000 -> "${diff / 60_000}m ago"
                    diff < 86400_000 -> "${diff / 3600_000}h ago"
                    else -> "${diff / 86400_000}d ago"
                }
            }
        }
        val isCurrentlyUploading = remember { mutableStateOf(false) }
        val storageStats by produceState(initialValue = StorageStats(false, 0.0, 0, 0)) {
            value = getStorageStats(context)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.titleMedium
                )

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val intent = Intent(context, UploadService::class.java).apply {
                                putExtra("process_now", true)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Upload Now")
                    }
                    Button(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export Data")
                    }
                }

                // View Logs Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                loadingPhoneLogs = true
                                phoneLogs = getPhoneLogs(context)
                                loadingPhoneLogs = false
                                showPhoneLogsDialog = true
                            }
                        },
                        enabled = !loadingPhoneLogs,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (loadingPhoneLogs) "Loading..." else "View Phone Logs")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                loadingWatchLogs = true
                                watchLogs = getWatchLogs(context)
                                loadingWatchLogs = false
                                showWatchLogsDialog = true
                            }
                        },
                        enabled = !loadingWatchLogs,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (loadingWatchLogs) "Loading..." else "View Watch Logs")
                    }
                }

                // Upload Statistics
                Text(
                    text = "Upload Statistics",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatBox(label = "Pending", value = batchesPending.toString())
                    StatBox(label = "Today", value = batchesUploadedToday.toString())
                    StatBox(label = "Last Upload", value = lastUpload)
                }
                if (isCurrentlyUploading.value) {
                    Text(
                        text = "Uploading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Storage Statistics
                if (storageStats.fileExists) {
                    Text(
                        text = "Local Storage",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox(label = "Size", value = String.format("%.1f KB", storageStats.fileSizeKB))
                        StatBox(label = "Batches", value = storageStats.totalBatches.toString())
                        StatBox(label = "Samples", value = storageStats.totalSamples.toString())
                    }
                }
            }
        }

        // Export Dialog
        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                context = context
            )
        }

        // Phone Logs Dialog
        if (showPhoneLogsDialog) {
            LogViewerDialog(
                logs = phoneLogs,
                title = "Phone Logs",
                onDismiss = { showPhoneLogsDialog = false },
                onShare = {
                    scope.launch {
                        shareLogsToFile(context, phoneLogs, "phone")
                    }
                }
            )
        }

        // Watch Logs Dialog
        if (showWatchLogsDialog) {
            LogViewerDialog(
                logs = watchLogs,
                title = "Watch Logs",
                onDismiss = { showWatchLogsDialog = false },
                onShare = {
                    scope.launch {
                        shareLogsToFile(context, watchLogs, "watch")
                    }
                }
            )
        }

        // Algorithm Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToAlgorithmSettings() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Algorithm Settings",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Customize tremor detection parameters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Configure",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Local Storage Settings Card - NOW FIRST after Data Management
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Local Storage",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable local storage")
                        Text(
                            text = "Store tremor data locally for charts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = localStorageEnabled,
                        onCheckedChange = { localStorageEnabled = it }
                    )
                }
                
                if (localStorageEnabled) {
                    OutlinedTextField(
                        value = retentionHours,
                        onValueChange = { 
                            if (it.all { char -> char.isDigit() }) {
                                retentionHours = it
                            }
                        },
                        label = { Text("Retention (hours)") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("How long to keep data (default: 168 = 7 days)") }
                    )
                }
                
                Button(
                    onClick = {
                        PhoneDataConfig.setLocalStorageEnabled(context, localStorageEnabled)
                        retentionHours.toIntOrNull()?.let {
                            PhoneDataConfig.setLocalStorageRetentionHours(context, it)
                        }
                        localStorageEdited = false // Reset edited state after save
                    },
                    enabled = localStorageEdited,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Storage Settings")
                }
            }
        }

        // InfluxDB Upload Section - with toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Title with toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "InfluxDB Upload",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Upload tremor data to InfluxDB server",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = influxDbEnabled,
                        onCheckedChange = { enabled ->
                            influxDbEnabled = enabled
                            PhoneDataConfig.setInfluxDbEnabled(context, enabled)
                            
                            // Request location permission when enabling InfluxDB
                            if (enabled) {
                                val hasLocationPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                
                                if (!hasLocationPermission) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            }
                        }
                    )
                }
                
                // Show InfluxDB configuration only when enabled
                if (influxDbEnabled) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "InfluxDB Configuration",
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    OutlinedTextField(
                        value = influxUrl,
                        onValueChange = { 
                            influxUrl = it
                            influxDbStatus = null
                        },
                        label = { Text("InfluxDB URL") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://10.0.0.10:8086") }
                    )
                    
                    OutlinedTextField(
                        value = influxDatabase,
                        onValueChange = { 
                            influxDatabase = it
                            influxDbStatus = null
                        },
                        label = { Text("Database") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("tremor") }
                    )
                    
                    OutlinedTextField(
                        value = influxUsername,
                        onValueChange = { 
                            influxUsername = it
                            influxDbStatus = null
                        },
                        label = { Text("Username (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = influxPassword,
                        onValueChange = { 
                            influxPassword = it
                            influxDbStatus = null
                        },
                        label = { Text("Password (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    
                    // Status message
                    var statusIsSuccess by remember { mutableStateOf(false) }
                    var statusIsError by remember { mutableStateOf(false) }
                    
                    if (influxDbStatus != null) {
                        val statusColor = when {
                            statusIsSuccess -> MaterialTheme.colorScheme.primary
                            statusIsError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = influxDbStatus!!,
                            color = statusColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isConfigured) "✓ Configured" else "Not configured",
                            color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    influxDbSaving = true
                                    influxDbStatus = "Testing connection..."
                                    statusIsSuccess = false
                                    statusIsError = false
                                    
                                    val result = InfluxDbConnectionTester.testConnectionAndCreateDatabase(
                                        url = influxUrl,
                                        database = influxDatabase,
                                        username = influxUsername,
                                        password = influxPassword
                                    )
                                    
                                    if (result.success) {
                                        // Save configuration
                                        PhoneDataConfig.setInfluxDbUrl(context, influxUrl)
                                        PhoneDataConfig.setInfluxDbDatabase(context, influxDatabase)
                                        PhoneDataConfig.setInfluxDbUsername(context, influxUsername)
                                        PhoneDataConfig.setInfluxDbPassword(context, influxPassword)
                                        
                                        influxDbStatus = result.message
                                        statusIsSuccess = true
                                        statusIsError = false
                                        influxDbEdited = false // Reset edited state after successful save
                                    } else {
                                        influxDbStatus = result.message
                                        statusIsSuccess = false
                                        statusIsError = true
                                    }
                                    
                                    influxDbSaving = false
                                }
                            },
                            enabled = influxDbEdited && !influxDbSaving && influxUrl.isNotEmpty() && influxDatabase.isNotEmpty()
                        ) {
                            Text(if (influxDbSaving) "Testing..." else "Save and Test")
                        }
                    }
                    
                    // WiFi Settings Section - only shown when InfluxDB is enabled
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Text(
                        text = "WiFi Settings",
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    Text(
                        text = "Configure which WiFi networks are considered 'home' for automatic uploads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    OutlinedTextField(
                        value = homeWifiSsids,
                        onValueChange = { homeWifiSsids = it },
                        label = { Text("Home WiFi SSIDs") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Comma-separated: HomeWiFi, OfficeWiFi") },
                        supportingText = { Text("WiFi network names (SSID)") }
                    )
                    
                    OutlinedTextField(
                        value = homeWifiBssids,
                        onValueChange = { homeWifiBssids = it },
                        label = { Text("Home WiFi BSSIDs (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Comma-separated: aa:bb:cc:dd:ee:ff") },
                        supportingText = { Text("Router MAC addresses - more reliable than SSID") }
                    )
                    
                    // Current WiFi info display
                    if (currentWifiInfo != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Current WiFi:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = currentWifiInfo!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                
                                // BSSID action buttons
                                if (currentBssid != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TextButton(
                                            onClick = { copyBssidToClipboard() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Copy BSSID")
                                        }
                                        TextButton(
                                            onClick = { addBssidToHome() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Add to Home BSSIDs")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Button(
                        onClick = { detectCurrentWifi() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Detect Current WiFi Info")
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow manual upload on any network")
                            Text(
                                text = "Upload Now works on any network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowManualAnyNetwork,
                            onCheckedChange = { allowManualAnyNetwork = it }
                        )
                    }
                    
                    Button(
                        onClick = {
                            val ssids = homeWifiSsids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val bssids = homeWifiBssids.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                            PhoneDataConfig.setHomeWifiSsids(context, ssids)
                            PhoneDataConfig.setHomeWifiBssids(context, bssids)
                            PhoneDataConfig.setAllowManualAnyNetwork(context, allowManualAnyNetwork)
                            wifiEdited = false // Reset edited state after save
                        },
                        enabled = wifiEdited,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save WiFi Settings")
                    }
                }
            }
        }
    }
}

fun getPendingBatchCount(context: Context): Int {
    val pendingDir = File(context.filesDir, "upload_queue")
    return pendingDir.listFiles()?.size ?: 0
}


/**
 * Get phone app logs
 */
suspend fun getPhoneLogs(context: Context): String {
    return withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "--pid=${android.os.Process.myPid()}"))
            val logs = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (logs.isBlank()) {
                "No logs available"
            } else {
                logs
            }
        } catch (e: Exception) {
            "Error getting logs: ${e.message}"
        }
    }
}

/**
 * Get watch app logs - requests logs directly from watch device
 */
suspend fun getWatchLogs(context: Context): String {
    return withContext(Dispatchers.IO) {
        try {
            // Connect to watch via Wearable Data Layer
            val nodeClient = com.google.android.gms.wearable.Wearable.getNodeClient(context)
            val nodes = suspendCancellableCoroutine<List<com.google.android.gms.wearable.Node>> { continuation ->
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes -> continuation.resume(nodes) }
                    .addOnFailureListener { e -> continuation.resumeWithException(e) }
            }

            if (nodes.isEmpty()) {
                return@withContext "No watch connected"
            }

            val watchNode = nodes.first()
            Log.d("MainActivity", "Requesting logs from watch: ${watchNode.displayName}")

            // Request logs from watch via MessageClient
            val messageClient = com.google.android.gms.wearable.Wearable.getMessageClient(context)
            val result = suspendCancellableCoroutine<Int> { continuation ->
                messageClient.sendMessage(watchNode.id, "/request_logs", byteArrayOf())
                    .addOnSuccessListener { requestId -> continuation.resume(requestId) }
                    .addOnFailureListener { e -> continuation.resumeWithException(e) }
            }

            Log.d("MainActivity", "Log request sent to watch (requestId: $result)")

            // Wait for response via DataClient
            // Note: This is a simplified approach - in production you'd want to use a proper
            // callback mechanism or DataClient listener
            kotlinx.coroutines.delay(2000)  // Give watch time to respond

            // Check for cached watch logs
            val watchLogsPrefs = context.getSharedPreferences("watch_logs", Context.MODE_PRIVATE)
            val cachedLogs = watchLogsPrefs.getString("last_logs", null)

            if (cachedLogs != null) {
                return@withContext cachedLogs
            }

            "Watch logs requested. If available, they will appear shortly.\n\n" +
            "Note: Watch must be connected and running TremorWatch app.\n" +
            "Connected to: ${watchNode.displayName}\n\n" +
            "For direct log access, use:\n" +
            "adb -s <watch_ip>:5555 logcat -s TremorService:* TremorFFT:* TremorMonitoringEngine:*"

        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting watch logs: ${e.message}", e)
            "Error getting watch logs: ${e.message}\n\n" +
            "Make sure:\n" +
            "1. Watch is connected via Bluetooth\n" +
            "2. TremorWatch is running on watch\n" +
            "3. Try direct adb access:\n" +
            "   adb -s <watch_ip>:5555 logcat -s TremorService:*"
        }
    }
}

/**
 * Share logs to file
 */
fun shareLogsToFile(context: Context, logs: String, type: String) {
    try {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "tremorwatch_${type.lowercase()}_logs_$timestamp.txt"
        val logsFile = File(context.cacheDir, fileName)
        logsFile.writeText(logs)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            logsFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share $type Logs"))
    } catch (e: Exception) {
        Log.e("MainActivity", "Error sharing logs: ${e.message}", e)
    }
}
