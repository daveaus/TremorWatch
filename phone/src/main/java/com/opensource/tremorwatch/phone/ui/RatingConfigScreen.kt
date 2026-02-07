package com.opensource.tremorwatch.phone.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opensource.tremorwatch.phone.config.RatingConfigManager
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Configuration screen for subjective tremor rating settings.
 * Allows users to configure:
 * - Rating prompt frequency and timing
 * - Active hours for prompts
 * - Calibration data capture settings
 * - Daily prompt limits
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingConfigScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rating_config", Context.MODE_PRIVATE) }
    val configManager = remember { RatingConfigManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Rating prompt settings
    var promptsEnabled by remember { mutableStateOf(prefs.getBoolean("prompts_enabled", true)) }
    var dailyMaxPrompts by remember { mutableIntStateOf(prefs.getInt("daily_max_prompts", 5)) }
    var minIntervalMinutes by remember { mutableIntStateOf(prefs.getInt("min_interval_minutes", 60)) }
    var promptVibrationEnabled by remember { mutableStateOf(prefs.getBoolean("prompt_vibration_enabled", true)) }
    var promptVibrationStrong by remember { mutableStateOf(prefs.getBoolean("prompt_vibration_strong", false)) }
    var promptFollowupVibration by remember { mutableStateOf(prefs.getBoolean("prompt_followup_vibration", false)) }
    
    // Active hours
    var activeStartHour by remember { mutableIntStateOf(prefs.getInt("active_start_hour", 8)) }
    var activeEndHour by remember { mutableIntStateOf(prefs.getInt("active_end_hour", 22)) }
    
    // Smart triggers
    var smartTriggersEnabled by remember { mutableStateOf(prefs.getBoolean("smart_triggers_enabled", true)) }
    var triggerAfterTremor by remember { mutableStateOf(prefs.getBoolean("trigger_after_tremor", true)) }
    var tremorDurationThreshold by remember { mutableIntStateOf(prefs.getInt("tremor_duration_threshold", 30)) }
    
    // Calibration settings
    var calibrationEnabled by remember { mutableStateOf(prefs.getBoolean("calibration_enabled", false)) }
    var calibrationDurationSeconds by remember { mutableIntStateOf(prefs.getInt("calibration_duration_seconds", 10)) }
    var autoCalibrationMode by remember { mutableStateOf(prefs.getBoolean("auto_calibration_mode", false)) }
    
    // Display settings
    var showRatingsOnGraph by remember { mutableStateOf(prefs.getBoolean("show_ratings_on_graph", true)) }
    var includeRatingsInExport by remember { mutableStateOf(prefs.getBoolean("include_ratings_in_export", true)) }
    
    // Track if any setting has changed
    var hasChanges by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val status = configManager.syncToWatch()
        if (status != RatingConfigManager.SyncStatus.SYNCED) {
            Timber.w("Initial rating config sync failed")
        }
    }
    
    fun saveSettings() {
        prefs.edit().apply {
            putBoolean("prompts_enabled", promptsEnabled)
            putInt("daily_max_prompts", dailyMaxPrompts)
            putInt("min_interval_minutes", minIntervalMinutes)
            putBoolean("prompt_vibration_enabled", promptVibrationEnabled)
            putBoolean("prompt_vibration_strong", promptVibrationStrong)
            putBoolean("prompt_followup_vibration", promptFollowupVibration)
            putInt("active_start_hour", activeStartHour)
            putInt("active_end_hour", activeEndHour)
            putBoolean("smart_triggers_enabled", smartTriggersEnabled)
            putBoolean("trigger_after_tremor", triggerAfterTremor)
            putInt("tremor_duration_threshold", tremorDurationThreshold)
            putBoolean("calibration_enabled", calibrationEnabled)
            putInt("calibration_duration_seconds", calibrationDurationSeconds)
            putBoolean("auto_calibration_mode", autoCalibrationMode)
            putBoolean("show_ratings_on_graph", showRatingsOnGraph)
            putBoolean("include_ratings_in_export", includeRatingsInExport)
            apply()
        }
        hasChanges = false
        Timber.i("Rating configuration saved")
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Rating Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Rating Prompts Section
            item {
                RatingPromptSection(
                    enabled = promptsEnabled,
                    onEnabledChange = { promptsEnabled = it; hasChanges = true },
                    dailyMax = dailyMaxPrompts,
                    onDailyMaxChange = { dailyMaxPrompts = it; hasChanges = true },
                    minInterval = minIntervalMinutes,
                    onMinIntervalChange = { minIntervalMinutes = it; hasChanges = true },
                    promptVibrationEnabled = promptVibrationEnabled,
                    onPromptVibrationEnabledChange = {
                        promptVibrationEnabled = it
                        if (!it) {
                            promptVibrationStrong = false
                            promptFollowupVibration = false
                        }
                        hasChanges = true
                    },
                    promptVibrationStrong = promptVibrationStrong,
                    onPromptVibrationStrongChange = { promptVibrationStrong = it; hasChanges = true },
                    promptFollowupVibration = promptFollowupVibration,
                    onPromptFollowupVibrationChange = { promptFollowupVibration = it; hasChanges = true }
                )
            }
            
            // Active Hours Section
            item {
                ActiveHoursSection(
                    enabled = promptsEnabled,
                    startHour = activeStartHour,
                    onStartHourChange = { activeStartHour = it; hasChanges = true },
                    endHour = activeEndHour,
                    onEndHourChange = { activeEndHour = it; hasChanges = true }
                )
            }
            
            // Smart Triggers Section
            item {
                SmartTriggersSection(
                    enabled = smartTriggersEnabled && promptsEnabled,
                    parentEnabled = promptsEnabled,
                    onEnabledChange = { smartTriggersEnabled = it; hasChanges = true },
                    triggerAfterTremor = triggerAfterTremor,
                    onTriggerAfterTremorChange = { triggerAfterTremor = it; hasChanges = true },
                    durationThreshold = tremorDurationThreshold,
                    onDurationThresholdChange = { tremorDurationThreshold = it; hasChanges = true }
                )
            }
            
            // Calibration Section
            item {
                CalibrationSection(
                    enabled = calibrationEnabled,
                    onEnabledChange = { calibrationEnabled = it; hasChanges = true },
                    durationSeconds = calibrationDurationSeconds,
                    onDurationChange = { calibrationDurationSeconds = it; hasChanges = true },
                    autoMode = autoCalibrationMode,
                    onAutoModeChange = { autoCalibrationMode = it; hasChanges = true }
                )
            }
            
            // Display Section
            item {
                DisplaySection(
                    showOnGraph = showRatingsOnGraph,
                    onShowOnGraphChange = { showRatingsOnGraph = it; hasChanges = true },
                    includeInExport = includeRatingsInExport,
                    onIncludeInExportChange = { includeRatingsInExport = it; hasChanges = true }
                )
            }
            
            // Save Button
            item {
                Button(
                    onClick = {
                        saveSettings()
                        scope.launch {
                            val status = configManager.syncToWatch()
                            val message = if (status == RatingConfigManager.SyncStatus.SYNCED) {
                                "Settings saved and synced to watch"
                            } else {
                                "Settings saved locally (watch sync failed)"
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    },
                    enabled = hasChanges,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (hasChanges) "Save Settings" else "No Changes")
                }
            }
            
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun RatingPromptSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    dailyMax: Int,
    onDailyMaxChange: (Int) -> Unit,
    minInterval: Int,
    onMinIntervalChange: (Int) -> Unit,
    promptVibrationEnabled: Boolean,
    onPromptVibrationEnabledChange: (Boolean) -> Unit,
    promptVibrationStrong: Boolean,
    onPromptVibrationStrongChange: (Boolean) -> Unit,
    promptFollowupVibration: Boolean,
    onPromptFollowupVibrationChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rating Prompts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Automatically prompt for ratings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            
            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                
                IntSliderSetting(
                    label = "Maximum Daily Prompts",
                    value = dailyMax,
                    range = 1..30,
                    help = "Maximum prompts per day",
                    onValueChange = onDailyMaxChange
                )
                
                IntSliderSetting(
                    label = "Minimum Interval",
                    value = minInterval,
                    range = 15..180,
                    unit = " min",
                    help = "Minimum time between prompts",
                    onValueChange = onMinIntervalChange
                )

                SwitchSetting(
                    label = "Vibrate on Prompt",
                    help = "Vibrate when a prompt appears",
                    checked = promptVibrationEnabled,
                    onCheckedChange = onPromptVibrationEnabledChange
                )

                if (promptVibrationEnabled) {
                    SwitchSetting(
                        label = "Strong Vibration",
                        help = "Use a stronger vibration pattern",
                        checked = promptVibrationStrong,
                        onCheckedChange = onPromptVibrationStrongChange
                    )

                    SwitchSetting(
                        label = "5-Minute Follow-Up",
                        help = "Vibrate again 5 minutes later if missed",
                        checked = promptFollowupVibration,
                        onCheckedChange = onPromptFollowupVibrationChange
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveHoursSection(
    enabled: Boolean,
    startHour: Int,
    onStartHourChange: (Int) -> Unit,
    endHour: Int,
    onEndHourChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Active Hours", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only prompt between ${formatHour(startHour)} - ${formatHour(endHour)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = enabled
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        "Expand"
                    )
                }
            }
            
            if (expanded && enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                
                IntSliderSetting(
                    label = "Start Time",
                    value = startHour,
                    range = 0..23,
                    valueFormatter = { formatHour(it) },
                    help = "First prompt after this time",
                    onValueChange = onStartHourChange
                )
                
                IntSliderSetting(
                    label = "End Time",
                    value = endHour,
                    range = 0..23,
                    valueFormatter = { formatHour(it) },
                    help = "No prompts after this time",
                    onValueChange = onEndHourChange
                )
            }
        }
    }
}

@Composable
private fun SmartTriggersSection(
    enabled: Boolean,
    parentEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    triggerAfterTremor: Boolean,
    onTriggerAfterTremorChange: (Boolean) -> Unit,
    durationThreshold: Int,
    onDurationThresholdChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (parentEnabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Smart Triggers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Prompt based on tremor activity",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = parentEnabled
                )
            }
            
            if (enabled && parentEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                
                SwitchSetting(
                    label = "Prompt After Tremor Episode",
                    help = "Ask for rating when tremor is detected",
                    checked = triggerAfterTremor,
                    onCheckedChange = onTriggerAfterTremorChange
                )
                
                if (triggerAfterTremor) {
                    IntSliderSetting(
                        label = "Tremor Duration Threshold",
                        value = durationThreshold,
                        range = 10..120,
                        unit = " sec",
                        help = "Minimum tremor duration to trigger prompt",
                        onValueChange = onDurationThresholdChange
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    durationSeconds: Int,
    onDurationChange: (Int) -> Unit,
    autoMode: Boolean,
    onAutoModeChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Calibration Data Capture", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Capture detailed sensor data with ratings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            
            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                
                IntSliderSetting(
                    label = "Capture Duration",
                    value = durationSeconds,
                    range = 5..30,
                    unit = " sec",
                    help = "How long to capture sensor data",
                    onValueChange = onDurationChange
                )
                
                SwitchSetting(
                    label = "Auto-Capture Mode",
                    help = "Automatically start capture when rating is triggered",
                    checked = autoMode,
                    onCheckedChange = onAutoModeChange
                )
            }
        }
    }
}

@Composable
private fun DisplaySection(
    showOnGraph: Boolean,
    onShowOnGraphChange: (Boolean) -> Unit,
    includeInExport: Boolean,
    onIncludeInExportChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Display & Export", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(16.dp))
            
            SwitchSetting(
                label = "Show Ratings on Graph",
                help = "Display rating markers on tremor chart",
                checked = showOnGraph,
                onCheckedChange = onShowOnGraphChange
            )
            
            SwitchSetting(
                label = "Include in Data Export",
                help = "Add ratings to exported CSV files",
                checked = includeInExport,
                onCheckedChange = onIncludeInExportChange
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    help: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (help.isNotEmpty()) {
                Text(
                    help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IntSliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    unit: String = "",
    help: String = "",
    valueFormatter: ((Int) -> String)? = null,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (help.isNotEmpty()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                valueFormatter?.invoke(value) ?: "$value$unit",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

private fun formatHour(hour: Int): String {
    return when {
        hour == 0 -> "12:00 AM"
        hour < 12 -> "$hour:00 AM"
        hour == 12 -> "12:00 PM"
        else -> "${hour - 12}:00 PM"
    }
}
