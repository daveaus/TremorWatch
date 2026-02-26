package com.opensource.tremorwatch.phone.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opensource.tremorwatch.phone.config.TremorConfigManager
import com.opensource.tremorwatch.phone.data.TrainingLabelEntity
import com.opensource.tremorwatch.phone.data.WatchTrainingStatePrefs
import com.opensource.tremorwatch.phone.data.WatchTrainingStateSnapshot
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import com.opensource.tremorwatch.phone.training.TrainingTuneOutcome
import com.opensource.tremorwatch.phone.training.TrainingTuneSnapshot
import com.opensource.tremorwatch.phone.training.TrainingTuneStateStore
import com.opensource.tremorwatch.shared.models.TremorDetectionConfig
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TremorDetectionSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val configManager = remember { TremorConfigManager(context) }
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(configManager.getActiveConfig()) }
    var originalConfig by remember { mutableStateOf(config) }
    var syncStatus by remember { mutableStateOf(configManager.getSyncStatus()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showSuccessSnackbar by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    val trainingDao = remember { TremorRoomDatabase.getDatabase(context).trainingLabelDao() }
    var trainingModeEnabled by remember { mutableStateOf(configManager.isTrainingModeEnabled()) }
    var isSyncingTrainingMode by remember { mutableStateOf(false) }
    val trainingUsableLabels by trainingDao.observeUsableLabelCount().collectAsState(initial = 0)
    val trainingPositiveLabels by trainingDao.observePositiveLabelCount().collectAsState(initial = 0)
    val trainingNegativeLabels by trainingDao.observeNegativeLabelCount().collectAsState(initial = 0)
    val trainingIgnoredLabels by trainingDao.observeIgnoredLabelCount().collectAsState(initial = 0)
    val trainingTotalLabels by trainingDao.observeTotalLabelCount().collectAsState(initial = 0)
    val recentTrainingLabels by trainingDao.observeRecentLabels(limit = 8)
        .collectAsState(initial = emptyList())
    var trainingStatusMessage by remember { mutableStateOf<String?>(null) }
    var showHowTrainingWorks by remember { mutableStateOf(false) }
    var isRunningAutoTune by remember { mutableStateOf(false) }
    var isRollingBackAutoTune by remember { mutableStateOf(false) }
    var showRollbackConfirmDialog by remember { mutableStateOf(false) }
    val watchTrainingStatePrefs = remember {
        context.getSharedPreferences(WatchTrainingStatePrefs.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var watchTrainingState by remember {
        mutableStateOf(WatchTrainingStatePrefs.read(watchTrainingStatePrefs))
    }
    val watchTrainingStateListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            watchTrainingState = WatchTrainingStatePrefs.read(prefs)
        }
    }
    val tunePrefs = remember {
        context.getSharedPreferences(TrainingTuneStateStore.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var tuneSnapshot by remember {
        mutableStateOf(TrainingTuneStateStore.read(tunePrefs))
    }
    val tuneStateListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            tuneSnapshot = TrainingTuneStateStore.read(prefs)
        }
    }
    val trainedPreset = remember(
        tuneSnapshot.lastAppliedTimeMs,
        tuneSnapshot.lastOutcome,
        tuneSnapshot.trainedProfileActive
    ) {
        configManager.loadProfile("Trained")
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // File export/import launchers
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val tempFile = File.createTempFile("config", ".json")
                configManager.exportToFile(config, tempFile)
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
                successMessage = "Configuration exported successfully"
                showSuccessSnackbar = true
            } catch (e: Exception) {
                errorMessage = "Failed to export: ${e.message}"
                showErrorDialog = true
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val tempFile = File.createTempFile("import", ".json")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                config = configManager.importFromFile(tempFile)
                tempFile.delete()
                successMessage = "Configuration imported: ${config.profileName}"
                showSuccessSnackbar = true
            } catch (e: Exception) {
                errorMessage = "Failed to import: ${e.message}"
                showErrorDialog = true
            }
        }
    }

    // Show success snackbar
    LaunchedEffect(showSuccessSnackbar) {
        if (showSuccessSnackbar) {
            snackbarHostState.showSnackbar(successMessage)
            showSuccessSnackbar = false
        }
    }

    DisposableEffect(watchTrainingStatePrefs, watchTrainingStateListener) {
        watchTrainingStatePrefs.registerOnSharedPreferenceChangeListener(watchTrainingStateListener)
        onDispose {
            watchTrainingStatePrefs.unregisterOnSharedPreferenceChangeListener(watchTrainingStateListener)
        }
    }

    DisposableEffect(tunePrefs, tuneStateListener) {
        tunePrefs.registerOnSharedPreferenceChangeListener(tuneStateListener)
        onDispose {
            tunePrefs.unregisterOnSharedPreferenceChangeListener(tuneStateListener)
        }
    }

    LaunchedEffect(Unit) {
        val requested = configManager.requestTrainingStateFromWatch()
        if (!requested && !watchTrainingState.hasData) {
            trainingStatusMessage = "Waiting for watch training status..."
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Algorithm & Training") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Sync status indicator - only show loading when actively syncing
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        when (syncStatus) {
                            TremorConfigManager.SyncStatus.SYNCED -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Synced",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            TremorConfigManager.SyncStatus.FAILED -> {
                                IconButton(onClick = {
                                    scope.launch {
                                        isSyncing = true
                                        syncStatus = configManager.forceSyncToWatch()
                                        isSyncing = false
                                    }
                                }) {
                                    Icon(Icons.Default.Warning, "Sync failed - tap to retry")
                                }
                            }
                            else -> {
                                // Don't show anything for PENDING status when not actively syncing
                            }
                        }
                    }

                    // Menu
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, "Menu")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Save Profile") },
                            onClick = {
                                showSaveDialog = true
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Load Profile") },
                            onClick = {
                                showLoadDialog = true
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.List, null) }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Export to File") },
                            onClick = {
                                val filename = "tremor_config_${config.profileName.replace(" ", "_")}_${
                                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                }.json"
                                exportLauncher.launch(filename)
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import from File") },
                            onClick = {
                                importLauncher.launch("application/json")
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Reset to Default") },
                            onClick = {
                                config = TremorDetectionConfig()
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) }
                        )
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
            // Current Profile Info
            item {
                ProfileInfoCard(config, syncStatus, hasChanges = config != originalConfig)
            }

            item {
                TrainingInsightsSection(
                    trainingModeEnabled = trainingModeEnabled,
                    isSyncingTrainingMode = isSyncingTrainingMode,
                    isRunningAutoTune = isRunningAutoTune,
                    isRollingBackAutoTune = isRollingBackAutoTune,
                    tuneSnapshot = tuneSnapshot,
                    usableLabels = trainingUsableLabels,
                    positiveLabels = trainingPositiveLabels,
                    negativeLabels = trainingNegativeLabels,
                    ignoredLabels = trainingIgnoredLabels,
                    totalLabels = trainingTotalLabels,
                    recentLabels = recentTrainingLabels,
                    watchTrainingState = watchTrainingState,
                    statusMessage = trainingStatusMessage,
                    onShowHowTrainingWorks = { showHowTrainingWorks = true },
                    onRunAutoTuneNow = {
                        scope.launch {
                            isRunningAutoTune = true
                            val enqueued = configManager.runTrainingAutoTuneNow("manual_user_request")
                            isRunningAutoTune = false
                            trainingStatusMessage = if (enqueued) {
                                "Auto-tune queued. This may take a few minutes."
                            } else {
                                "Auto-tune already queued or ran recently."
                            }
                            successMessage = if (enqueued) {
                                "Auto-tune queued"
                            } else {
                                "Auto-tune not queued"
                            }
                            showSuccessSnackbar = true
                        }
                    },
                    onRollbackAutoTune = {
                        showRollbackConfirmDialog = true
                    },
                    onToggleTraining = { enabled ->
                        trainingModeEnabled = enabled
                        scope.launch {
                            isSyncingTrainingMode = true
                            val synced = configManager.setTrainingModeEnabled(enabled)
                            isSyncingTrainingMode = false
                            trainingStatusMessage = if (synced) {
                                if (enabled && watchTrainingState.hasEnoughLabels) {
                                    "Training synced. Threshold already reached; auto-tune queued."
                                } else {
                                    "Training mode synced to watch"
                                }
                            } else {
                                if (enabled && watchTrainingState.hasEnoughLabels) {
                                    "Saved locally. Threshold reached; auto-tune queued (watch sync pending)."
                                } else {
                                    "Saved locally. Watch sync pending."
                                }
                            }
                            if (synced) {
                                configManager.requestTrainingStateFromWatch()
                            }
                            successMessage = if (enabled) {
                                "Training mode enabled"
                            } else {
                                "Training mode disabled"
                            }
                            showSuccessSnackbar = true
                        }
                    }
                )
            }

            // Presets
            item {
                PresetsSection(
                    config = config,
                    trainedPreset = trainedPreset
                ) { selectedPreset ->
                    config = selectedPreset
                    // Don't update originalConfig - let user apply changes
                }
            }

            // Frequency Settings
            item {
                FrequencySettingsSection(config) { newConfig ->
                    config = newConfig
                }
            }

            // Sensitivity Settings
            item {
                SensitivitySettingsSection(config) { newConfig ->
                    config = newConfig
                }
            }

            // Temporal Smoothing
            item {
                TemporalSettingsSection(config) { newConfig ->
                    config = newConfig
                }
            }

            // Advanced Settings
            item {
                AdvancedSettingsSection(config) { newConfig ->
                    config = newConfig
                }
            }

            // Apply Button
            item {
                ApplyButton(
                    config = config,
                    isSyncing = isSyncing,
                    hasChanges = config != originalConfig,
                    onApply = {
                        scope.launch {
                            isSyncing = true
                            try {
                                syncStatus = configManager.setActiveConfig(config)
                                originalConfig = config
                                successMessage = when (syncStatus) {
                                    TremorConfigManager.SyncStatus.SYNCED ->
                                        "Settings applied and synced to watch"
                                    TremorConfigManager.SyncStatus.FAILED ->
                                        "Settings saved but sync failed. Will retry."
                                    else -> "Settings saved"
                                }
                                showSuccessSnackbar = true
                            } catch (e: Exception) {
                                errorMessage = "Failed to save: ${e.message}"
                                showErrorDialog = true
                            } finally {
                                isSyncing = false
                            }
                        }
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Dialogs
    if (showSaveDialog) {
        SaveProfileDialog(
            currentName = config.profileName,
            onSave = { name, description ->
                val newConfig = config.copy(profileName = name, profileDescription = description)
                configManager.saveProfile(newConfig)
                config = newConfig
                showSaveDialog = false
                successMessage = "Profile saved: $name"
                showSuccessSnackbar = true
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    if (showLoadDialog) {
        LoadProfileDialog(
            profiles = configManager.getSavedProfileNames() + TremorDetectionConfig.PRESETS.keys,
            onLoad = { name ->
                configManager.loadProfile(name)?.let {
                    config = it
                    originalConfig = it
                    showLoadDialog = false
                    successMessage = "Profile loaded: $name"
                    showSuccessSnackbar = true
                }
            },
            onDelete = { name ->
                if (configManager.deleteProfile(name)) {
                    successMessage = "Profile deleted: $name"
                    showSuccessSnackbar = true
                } else {
                    errorMessage = "Cannot delete built-in preset"
                    showErrorDialog = true
                }
            },
            onDismiss = { showLoadDialog = false }
        )
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showRollbackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRollbackConfirmDialog = false },
            title = { Text("Rollback Personalized Config") },
            text = {
                Text("This will restore your previous pre-trained profile. Training labels are kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRollbackConfirmDialog = false
                        scope.launch {
                            isRollingBackAutoTune = true
                            val rollbackStatus = configManager.rollbackAutoTunedConfig()
                            isRollingBackAutoTune = false
                            config = configManager.getActiveConfig()
                            originalConfig = config
                            syncStatus = configManager.getSyncStatus()
                            trainingStatusMessage = when (rollbackStatus) {
                                TremorConfigManager.SyncStatus.SYNCED ->
                                    "Rollback applied and synced to watch."
                                TremorConfigManager.SyncStatus.PENDING ->
                                    "Rollback applied locally; watch sync pending."
                                TremorConfigManager.SyncStatus.NOT_CONNECTED ->
                                    "Rollback applied locally; watch not connected."
                                TremorConfigManager.SyncStatus.FAILED ->
                                    "Rollback failed. Check connectivity and try again."
                            }
                            if (rollbackStatus != TremorConfigManager.SyncStatus.FAILED) {
                                successMessage = "Rollback completed"
                                showSuccessSnackbar = true
                            } else {
                                errorMessage = "Rollback failed"
                                showErrorDialog = true
                            }
                        }
                    }
                ) {
                    Text("Rollback")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRollbackConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showHowTrainingWorks) {
        TrainingHowItWorksDialog(onDismiss = { showHowTrainingWorks = false })
    }
}

@Composable
private fun TrainingInsightsSection(
    trainingModeEnabled: Boolean,
    isSyncingTrainingMode: Boolean,
    isRunningAutoTune: Boolean,
    isRollingBackAutoTune: Boolean,
    tuneSnapshot: TrainingTuneSnapshot,
    usableLabels: Int,
    positiveLabels: Int,
    negativeLabels: Int,
    ignoredLabels: Int,
    totalLabels: Int,
    recentLabels: List<TrainingLabelEntity>,
    watchTrainingState: WatchTrainingStateSnapshot,
    statusMessage: String?,
    onShowHowTrainingWorks: () -> Unit,
    onRunAutoTuneNow: () -> Unit,
    onRollbackAutoTune: () -> Unit,
    onToggleTraining: (Boolean) -> Unit
) {
    val targetLabels = watchTrainingState.targetLabels.coerceAtLeast(1)
    val watchStateAvailable = watchTrainingState.hasData
    val toggleChecked = trainingModeEnabled
    val effectiveUsableLabels = if (watchStateAvailable) watchTrainingState.usableLabels else usableLabels
    val effectiveYesLabels = if (watchStateAvailable) watchTrainingState.yesLabels else positiveLabels
    val effectiveNoLabels = if (watchStateAvailable) watchTrainingState.noLabels else negativeLabels
    val effectiveIgnoredLabels = if (watchStateAvailable) watchTrainingState.ignoredLabels else ignoredLabels
    val effectiveTotalPrompts = if (watchStateAvailable) watchTrainingState.promptsTotal else totalLabels
    val hasMinimumLabels = effectiveUsableLabels >= targetLabels
    val stateLabel = when {
        watchStateAvailable -> watchTrainingState.uiState
        !trainingModeEnabled -> "OFF"
        hasMinimumLabels -> "PERSONALIZED"
        effectiveUsableLabels == 0 -> "WARMUP"
        else -> "ACTIVE"
    }
    val collectionStatus = when {
        !trainingModeEnabled -> "Off"
        !hasMinimumLabels -> "Collecting labels"
        else -> "Minimum labels reached"
    }
    val personalizedInUse =
        tuneSnapshot.lastOutcome == TrainingTuneOutcome.APPLIED && tuneSnapshot.trainedProfileActive
    val personalizationStatus = formatPersonalizationStatus(tuneSnapshot)
    val watchStatusLabel = formatWatchStatusMeta(watchTrainingState)
    val nowMs = System.currentTimeMillis()
    val autoApplyBlocked = tuneSnapshot.autoApplyBlockedUntilMs > nowMs
    val autoApplyBlockedUntilText = if (autoApplyBlocked) {
        formatAbsoluteTime(tuneSnapshot.autoApplyBlockedUntilMs)
    } else {
        null
    }
    val canRunAutoTune =
        trainingModeEnabled &&
            hasMinimumLabels &&
            !isRunningAutoTune &&
            !isRollingBackAutoTune &&
            !autoApplyBlocked
    val canRollback =
        tuneSnapshot.hasRollbackSnapshot &&
            !isRunningAutoTune &&
            !isRollingBackAutoTune

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Training", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Phone mode: ${if (trainingModeEnabled) "ON" else "OFF"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (watchStateAvailable) {
                            "Watch mode: ${if (watchTrainingState.enabled) "ON" else "OFF"} ($stateLabel)"
                        } else {
                            "Watch state: waiting for sync"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Collection: $collectionStatus",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (watchStatusLabel != null) {
                        Text(
                            watchStatusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = toggleChecked,
                    onCheckedChange = onToggleTraining,
                    enabled = !isSyncingTrainingMode
                )
            }

            if (watchStateAvailable && watchTrainingState.enabled != trainingModeEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Phone and watch training mode are out of sync. Use this toggle to align both devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Phone labels: $usableLabels/$targetLabels  (Yes: $positiveLabels, No: $negativeLabels)",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Ignored: $ignoredLabels  Total prompts: $totalLabels",
                style = MaterialTheme.typography.bodySmall
            )
            if (watchStateAvailable) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Watch labels: ${watchTrainingState.usableLabels}/${watchTrainingState.targetLabels}  " +
                        "(Yes: ${watchTrainingState.yesLabels}, No: ${watchTrainingState.noLabels}, " +
                        "Ignored: ${watchTrainingState.ignoredLabels})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Watch prompts: total ${watchTrainingState.promptsTotal}, today ${watchTrainingState.promptsToday}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            TrainingProgressSection(
                trainingModeEnabled = trainingModeEnabled,
                usableLabels = effectiveUsableLabels,
                targetLabels = targetLabels,
                yesLabels = effectiveYesLabels,
                noLabels = effectiveNoLabels,
                ignoredLabels = effectiveIgnoredLabels,
                totalPrompts = effectiveTotalPrompts,
                hasMinimumLabels = hasMinimumLabels
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (hasMinimumLabels) {
                    "Minimum labels reached. Personalization starts after auto-tune is applied."
                } else {
                    "${(targetLabels - effectiveUsableLabels).coerceAtLeast(0)} more usable labels needed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasMinimumLabels) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                "Using personalized profile: ${if (personalizedInUse) "YES" else "NO"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (personalizedInUse) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                "Personalization: $personalizationStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tuneSnapshot.lastRunTimeMs > 0L) {
                Text(
                    "Last personalization run: ${formatAbsoluteTime(tuneSnapshot.lastRunTimeMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tuneSnapshot.lastRunReason.isNotBlank()) {
                    Text(
                        "Last run reason: ${tuneSnapshot.lastRunReason.replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (tuneSnapshot.samplesUsed > 0) {
                Text(
                    "Last tune used ${tuneSnapshot.samplesUsed} labels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tune score: ${"%.3f".format(tuneSnapshot.beforeJ)} -> ${"%.3f".format(tuneSnapshot.afterJ)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (autoApplyBlockedUntilText != null) {
                Text(
                    "Auto-apply pause active until $autoApplyBlockedUntilText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRunAutoTuneNow,
                    enabled = canRunAutoTune,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isRunningAutoTune) "Queueing..." else "Run Auto-Tune Now"
                    )
                }
                OutlinedButton(
                    onClick = onRollbackAutoTune,
                    enabled = canRollback,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isRollingBackAutoTune) "Rolling back..." else "Rollback"
                    )
                }
            }
            Text(
                "Run Now requires training ON and minimum labels. Rollback requires a saved pre-trained snapshot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onShowHowTrainingWorks) {
                Text("How training works (clear guide)")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Recent labels", style = MaterialTheme.typography.titleSmall)
            if (recentLabels.isEmpty()) {
                Text(
                    "No training labels received yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recentLabels.take(6).forEach { label ->
                    Text(
                        formatTrainingLabelRow(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrainingProgressSection(
    trainingModeEnabled: Boolean,
    usableLabels: Int,
    targetLabels: Int,
    yesLabels: Int,
    noLabels: Int,
    ignoredLabels: Int,
    totalPrompts: Int,
    hasMinimumLabels: Boolean
) {
    val progress = (usableLabels.toFloat() / targetLabels.toFloat()).coerceIn(0f, 1f)
    val remaining = (targetLabels - usableLabels).coerceAtLeast(0)
    val safeTotal = maxOf(totalPrompts, yesLabels + noLabels + ignoredLabels, 1)

    Text(
        "Training progress",
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        "$usableLabels of $targetLabels usable labels",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
    Text(
        when {
            !trainingModeEnabled -> "Training is off. Turn it on to continue collecting labels."
            hasMinimumLabels -> "Minimum reached. Waiting for phone auto-tune to apply personalization."
            else -> "$remaining more usable labels needed before auto-tune can use your data."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Prompt label mix",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium
    )
    LabelMixBar(
        label = "Yes (Tremor)",
        value = yesLabels,
        total = safeTotal
    )
    LabelMixBar(
        label = "No (Not Tremor)",
        value = noLabels,
        total = safeTotal
    )
    LabelMixBar(
        label = "Unsure/Ignore",
        value = ignoredLabels,
        total = safeTotal
    )
}

@Composable
private fun LabelMixBar(
    label: String,
    value: Int,
    total: Int
) {
    val ratio = (value.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LinearProgressIndicator(
        progress = { ratio },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    )
}

private fun formatTrainingLabelRow(label: TrainingLabelEntity): String {
    val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(label.timestamp))
    val labelText = when (label.label) {
        "YES_TREMOR" -> "ðŸ‘‹ Tremor"
        "NO_ACTIVE" -> "ðŸ‘ No Tremor"
        "IGNORE" -> "\uD83E\uDD14 Unsure/Ignore"
        else -> label.label
    }
    return "$time - $labelText"
}

private fun formatPersonalizationStatus(snapshot: TrainingTuneSnapshot): String {
    return when (snapshot.lastOutcome) {
        TrainingTuneOutcome.NEVER -> "Not started"
        TrainingTuneOutcome.SKIPPED -> "Scheduled / skipped this run"
        TrainingTuneOutcome.REJECTED -> "Needs more data"
        TrainingTuneOutcome.APPLIED -> if (snapshot.trainedProfileActive) {
            "Active"
        } else {
            "Applied (not active)"
        }
        TrainingTuneOutcome.APPLY_FAILED -> "Failed (will retry)"
        TrainingTuneOutcome.ROLLED_BACK -> "Rolled back"
    }
}

private fun formatAbsoluteTime(timestampMs: Long): String {
    return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestampMs))
}

@Composable
private fun TrainingHowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Training Guide") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Turn training ON to collect labels from watch prompts.")
                Text("2. Usable labels are Yes + No. Unsure/Ignore is stored but not used for personalization.")
                Text("3. When the progress bar reaches the minimum target, your data is eligible for auto-tune.")
                Text("4. The phone runs auto-tune and only applies changes if quality checks pass.")
                Text("5. Personalization is active only when you see:")
                Text("   - Using personalized profile: YES")
                Text("   - Personalization: Active")
                Text("6. If status says Needs more data, keep labeling over different times of day.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
private fun formatWatchStatusMeta(state: WatchTrainingStateSnapshot): String? {
    if (!state.hasData || state.timestampMs <= 0L) return null
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(state.timestampMs))
    val ageMs = (System.currentTimeMillis() - state.timestampMs).coerceAtLeast(0L)
    val freshness = if (ageMs > 15 * 60 * 1000L) "stale" else "live"
    return "Watch update: $time ($freshness)"
}

@Composable
private fun ProfileInfoCard(config: TremorDetectionConfig, syncStatus: TremorConfigManager.SyncStatus, hasChanges: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        config.profileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        config.profileDescription,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Sync status badge - show "Unsaved Changes" if changes exist
                val (statusText, statusColor) = if (hasChanges) {
                    "Unsaved Changes" to MaterialTheme.colorScheme.tertiary
                } else {
                    when (syncStatus) {
                        TremorConfigManager.SyncStatus.SYNCED -> "Synced" to MaterialTheme.colorScheme.primary
                        TremorConfigManager.SyncStatus.PENDING -> "Not Synced" to MaterialTheme.colorScheme.secondary
                        TremorConfigManager.SyncStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
                        else -> "Unknown" to MaterialTheme.colorScheme.outline
                    }
                }
                Surface(
                    color = statusColor.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetsSection(
    config: TremorDetectionConfig,
    trainedPreset: TremorDetectionConfig?,
    onPresetSelected: (TremorDetectionConfig) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Presets", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            TremorDetectionConfig.PRESETS.keys.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { name ->
                        FilterChip(
                            selected = config.profileName == name,
                            onClick = {
                                TremorDetectionConfig.PRESETS[name]?.let { onPresetSelected(it) }
                            },
                            label = { Text(name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.profileName == "Trained",
                    onClick = {
                        trainedPreset?.let { onPresetSelected(it) }
                    },
                    enabled = trainedPreset != null,
                    label = {
                        Text(
                            if (trainedPreset != null) "Trained" else "Trained (not ready)"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FrequencySettingsSection(
    config: TremorDetectionConfig,
    onConfigChange: (TremorDetectionConfig) -> Unit
) {
    ExpandableSettingsCard(title = "Frequency Detection") {
        SliderSetting(
            label = "Resting Band Low",
            value = config.restingBandLowHz,
            range = 2f..6f,
            unit = "Hz",
            help = "Lower bound for resting tremor (Parkinsonian: 4-6Hz)",
            isModified = config.restingBandLowHz != TremorDetectionConfig().restingBandLowHz
        ) { onConfigChange(config.copy(restingBandLowHz = it)) }

        SliderSetting(
            label = "Resting Band High",
            value = config.restingBandHighHz,
            range = 4f..10f,
            unit = "Hz",
            help = "Upper bound for resting tremor",
            isModified = config.restingBandHighHz != TremorDetectionConfig().restingBandHighHz
        ) { onConfigChange(config.copy(restingBandHighHz = it)) }

        SliderSetting(
            label = "Active Band Low",
            value = config.activeBandLowHz,
            range = 2f..8f,
            unit = "Hz",
            help = "Lower bound for action tremor",
            isModified = config.activeBandLowHz != TremorDetectionConfig().activeBandLowHz
        ) { onConfigChange(config.copy(activeBandLowHz = it)) }

        SliderSetting(
            label = "Active Band High",
            value = config.activeBandHighHz,
            range = 8f..20f,
            unit = "Hz",
            help = "Upper bound for action tremor (Essential: 5-8Hz)",
            isModified = config.activeBandHighHz != TremorDetectionConfig().activeBandHighHz
        ) { onConfigChange(config.copy(activeBandHighHz = it)) }
    }
}

@Composable
private fun SensitivitySettingsSection(
    config: TremorDetectionConfig,
    onConfigChange: (TremorDetectionConfig) -> Unit
) {
    ExpandableSettingsCard(title = "Sensitivity") {
        SliderSetting(
            label = "Minimum Band Ratio",
            value = config.minBandRatio,
            range = 0.01f..0.20f,
            format = "%.3f",
            help = "Lower = more sensitive (may increase false positives)",
            isModified = config.minBandRatio != TremorDetectionConfig().minBandRatio
        ) { onConfigChange(config.copy(minBandRatio = it)) }

        SliderSetting(
            label = "Confidence Threshold",
            value = config.confidenceThreshold,
            range = 0.1f..0.8f,
            format = "%.2f",
            help = "Minimum confidence to detect tremor",
            isModified = config.confidenceThreshold != TremorDetectionConfig().confidenceThreshold
        ) { onConfigChange(config.copy(confidenceThreshold = it)) }

        SliderSetting(
            label = "Severity Floor",
            value = config.severityFloor,
            range = 0.001f..0.05f,
            format = "%.4f",
            help = "Minimum severity threshold",
            isModified = config.severityFloor != TremorDetectionConfig().severityFloor
        ) { onConfigChange(config.copy(severityFloor = it)) }
    }
}

@Composable
private fun TemporalSettingsSection(
    config: TremorDetectionConfig,
    onConfigChange: (TremorDetectionConfig) -> Unit
) {
    ExpandableSettingsCard(title = "Temporal Smoothing") {
        IntSliderSetting(
            label = "Min Episode Duration",
            value = config.minEpisodeDurationSamples,
            range = 1..10,
            help = "Consecutive samples needed to confirm tremor",
            isModified = config.minEpisodeDurationSamples != TremorDetectionConfig().minEpisodeDurationSamples
        ) { onConfigChange(config.copy(minEpisodeDurationSamples = it)) }

        IntSliderSetting(
            label = "Max Gap Samples",
            value = config.maxGapSamples,
            range = 0..5,
            help = "Allowed gap within tremor episode",
            isModified = config.maxGapSamples != TremorDetectionConfig().maxGapSamples
        ) { onConfigChange(config.copy(maxGapSamples = it)) }
    }
}

@Composable
private fun AdvancedSettingsSection(
    config: TremorDetectionConfig,
    onConfigChange: (TremorDetectionConfig) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Advanced Settings", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        "Expand"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                SliderSetting(
                    label = "Band Ratio Weight",
                    value = config.bandRatioWeight,
                    range = 0.1f..0.5f,
                    format = "%.2f",
                    help = "Weight in confidence calculation",
                    isModified = config.bandRatioWeight != TremorDetectionConfig().bandRatioWeight
                ) { onConfigChange(config.copy(bandRatioWeight = it)) }

                SliderSetting(
                    label = "Peak Prominence Weight",
                    value = config.peakProminenceWeight,
                    range = 0.05f..0.3f,
                    format = "%.2f",
                    help = "Weight in confidence calculation",
                    isModified = config.peakProminenceWeight != TremorDetectionConfig().peakProminenceWeight
                ) { onConfigChange(config.copy(peakProminenceWeight = it)) }

                SliderSetting(
                    label = "Frequency Validation Weight",
                    value = config.frequencyValidationWeight,
                    range = 0.1f..0.4f,
                    format = "%.2f",
                    help = "Weight in confidence calculation",
                    isModified = config.frequencyValidationWeight != TremorDetectionConfig().frequencyValidationWeight
                ) { onConfigChange(config.copy(frequencyValidationWeight = it)) }

                Spacer(modifier = Modifier.height(12.dp))
                Text("FFT Window Mode", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("fixed_64", "fixed_128", "ab_test").forEach { mode ->
                        FilterChip(
                            selected = config.fftWindowMode == mode,
                            onClick = { onConfigChange(config.copy(fftWindowMode = mode)) },
                            label = { Text(mode.replace('_', ' ')) }
                        )
                    }
                }

                IntSliderSetting(
                    label = "FFT Window Short",
                    value = config.fftWindowSizeShort,
                    range = 32..256,
                    help = "Short path window size (samples)",
                    isModified = config.fftWindowSizeShort != TremorDetectionConfig().fftWindowSizeShort
                ) { onConfigChange(config.copy(fftWindowSizeShort = it)) }

                IntSliderSetting(
                    label = "FFT Window Long",
                    value = config.fftWindowSizeLong,
                    range = 64..512,
                    help = "Long path window size (samples)",
                    isModified = config.fftWindowSizeLong != TremorDetectionConfig().fftWindowSizeLong
                ) { onConfigChange(config.copy(fftWindowSizeLong = it)) }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Spectrum Mode", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("classic", "welch", "hybrid").forEach { mode ->
                        FilterChip(
                            selected = config.fftSpectrumMode == mode,
                            onClick = { onConfigChange(config.copy(fftSpectrumMode = mode)) },
                            label = { Text(mode) }
                        )
                    }
                }

                SliderSetting(
                    label = "Welch Overlap",
                    value = config.fftWelchOverlap,
                    range = 0.0f..0.9f,
                    format = "%.2f",
                    help = "Welch segment overlap",
                    isModified = config.fftWelchOverlap != TremorDetectionConfig().fftWelchOverlap
                ) { onConfigChange(config.copy(fftWelchOverlap = it)) }

                SliderSetting(
                    label = "Welch Blend",
                    value = config.fftWelchBlend,
                    range = 0.0f..1.0f,
                    format = "%.2f",
                    help = "Hybrid blend weight",
                    isModified = config.fftWelchBlend != TremorDetectionConfig().fftWelchBlend
                ) { onConfigChange(config.copy(fftWelchBlend = it)) }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Confidence Calibration", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none", "platt", "isotonic").forEach { mode ->
                        FilterChip(
                            selected = config.confidenceCalibrationMode == mode,
                            onClick = { onConfigChange(config.copy(confidenceCalibrationMode = mode)) },
                            label = { Text(mode) }
                        )
                    }
                }

                SliderSetting(
                    label = "Platt A",
                    value = config.confidencePlattA,
                    range = -8f..8f,
                    format = "%.2f",
                    help = "Platt scaling slope",
                    isModified = config.confidencePlattA != TremorDetectionConfig().confidencePlattA
                ) { onConfigChange(config.copy(confidencePlattA = it)) }

                SliderSetting(
                    label = "Platt B",
                    value = config.confidencePlattB,
                    range = -4f..4f,
                    format = "%.2f",
                    help = "Platt scaling bias",
                    isModified = config.confidencePlattB != TremorDetectionConfig().confidencePlattB
                ) { onConfigChange(config.copy(confidencePlattB = it)) }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Hybrid Reranker", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = config.hybridRerankerEnabled,
                        onCheckedChange = { enabled ->
                            onConfigChange(config.copy(hybridRerankerEnabled = enabled))
                        }
                    )
                }

                SliderSetting(
                    label = "Reranker Threshold",
                    value = config.hybridRerankerThreshold,
                    range = 0.1f..0.9f,
                    format = "%.2f",
                    help = "Probability threshold for reranker support",
                    isModified = config.hybridRerankerThreshold != TremorDetectionConfig().hybridRerankerThreshold
                ) { onConfigChange(config.copy(hybridRerankerThreshold = it)) }

                SliderSetting(
                    label = "Reranker Blend",
                    value = config.hybridRerankerBlend,
                    range = 0.0f..1.0f,
                    format = "%.2f",
                    help = "Blend raw confidence with reranker probability",
                    isModified = config.hybridRerankerBlend != TremorDetectionConfig().hybridRerankerBlend
                ) { onConfigChange(config.copy(hybridRerankerBlend = it)) }
            }
        }
    }
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        "Expand"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    format: String = "%.2f",
    help: String = "",
    isModified: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (isModified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Edit,
                            "Modified",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (help.isNotEmpty()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "${String.format(format, value)}$unit",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun IntSliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    help: String = "",
    isModified: Boolean = false,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (isModified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Edit,
                            "Modified",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (help.isNotEmpty()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ApplyButton(
    config: TremorDetectionConfig,
    isSyncing: Boolean,
    hasChanges: Boolean,
    onApply: () -> Unit
) {
    Button(
        onClick = onApply,
        enabled = !isSyncing && hasChanges,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Syncing to Watch...")
        } else {
            Icon(Icons.Default.Refresh, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (hasChanges) "Apply & Sync to Watch" else "No Changes")
        }
    }
}

@Composable
private fun SaveProfileDialog(
    currentName: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Profile") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LoadProfileDialog(
    profiles: List<String>,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load Profile") },
        text = {
            LazyColumn {
                items(profiles.size) { index ->
                    val profileName = profiles[index]
                    val isPreset = TremorDetectionConfig.PRESETS.containsKey(profileName)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onLoad(profileName) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(profileName)
                        }
                        if (!isPreset) {
                            IconButton(onClick = { onDelete(profileName) }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

