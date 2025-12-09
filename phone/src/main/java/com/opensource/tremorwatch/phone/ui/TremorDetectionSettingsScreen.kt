package com.opensource.tremorwatch.phone.ui

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Detection Settings") },
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

            // Presets
            item {
                PresetsSection(config) { selectedPreset ->
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
