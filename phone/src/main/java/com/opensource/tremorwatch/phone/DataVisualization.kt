package com.opensource.tremorwatch.phone

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.opensource.tremorwatch.shared.models.TremorBatch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data point for chart visualization
 */
data class ChartData(
    val timestamp: Long,
    val severity: Double,
    val tremorCount: Int,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Local storage statistics
 */
data class StorageStats(
    val fileExists: Boolean,
    val fileSizeKB: Double,
    val totalBatches: Int,
    val totalSamples: Int
)

/**
 * Chart data with metadata
 */
data class ChartDataSet(
    val rawCount: Int,
    val aggregatedData: List<ChartData>
)

/**
 * Gap types for visualization
 */
enum class GapType {
    OFF_WRIST,
    CHARGING,
    NO_DATA
}

/**
 * Represents a time gap in data
 */
data class GapEvent(
    val startTime: Long,
    val endTime: Long,
    val type: GapType
)

/**
 * Tremor rating calculation
 */
data class TremorRating(
    val timestamp: Long,
    val rating: Int // 0-5 scale
)

/**
 * Calculate sticky time range that aligns to hour boundaries
 */
fun calculateStickyTimeRange(hoursBack: Int, offsetPeriods: Int = 0): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.timeInMillis = now

    // Round up to next hour for end time
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.HOUR_OF_DAY, 1) // Next hour

    // Apply offset periods if any
    if (offsetPeriods > 0) {
        cal.add(Calendar.HOUR_OF_DAY, -offsetPeriods * hoursBack)
    }

    val endTime = cal.timeInMillis

    // Calculate start time by going back hoursBack hours
    cal.add(Calendar.HOUR_OF_DAY, -hoursBack)
    val startTime = cal.timeInMillis

    return Pair(startTime, endTime)
}

/**
 * Create smooth path from points
 */
private fun createSmoothPath(points: List<Offset>): Path {
    if (points.isEmpty()) return Path()
    if (points.size == 1) {
        return Path().apply { moveTo(points[0].x, points[0].y) }
    }
    
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    
    for (i in 1 until points.size) {
        if (i == 1) {
            path.lineTo(points[i].x, points[i].y)
        } else {
            val prev = points[i - 1]
            val curr = points[i]
            val midX = (prev.x + curr.x) / 2f
            val midY = (prev.y + curr.y) / 2f
            path.quadraticBezierTo(prev.x, prev.y, midX, midY)
        }
    }
    
    return path
}

/**
 * Format a timestamp as 12-hour format (e.g., 12am, 6am, 12pm, 6pm)
 */
private fun formatTime(timestamp: Long): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        0 -> "12am"
        12 -> "12pm"
        in 1..11 -> "${hour}am"
        else -> "${hour - 12}pm"
    }
}

/**
 * Format a timestamp with minutes for shorter time ranges (e.g., 6:15am, 6:30am)
 */
private fun formatTimeWithMinutes(timestamp: Long): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    
    val hourStr = when (hour) {
        0 -> "12"
        in 1..12 -> "$hour"
        else -> "${hour - 12}"
    }
    val amPm = if (hour < 12) "am" else "pm"
    
    return if (minute == 0) {
        "${hourStr}${amPm}"
    } else {
        "${hourStr}:${String.format("%02d", minute)}${amPm}"
    }
}

/**
 * Format time range label
 */
fun formatTimeRangeLabel(startTime: Long, endTime: Long): String {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    
    val startDate = Date(startTime)
    val endDate = Date(endTime)
    val today = Calendar.getInstance()
    val startCal = Calendar.getInstance().apply { time = startDate }
    
    val dayLabel = if (startCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                       startCal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
        "Today"
    } else {
        dayFormat.format(startDate)
    }
    
    return "$dayLabel • ${dateFormat.format(startDate)} - ${dateFormat.format(endDate)}"
}

/**
 * Get local storage statistics
 */
suspend fun getStorageStats(context: Context): StorageStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val storageFile = File(context.filesDir, "consolidated_tremor_data.jsonl")
    if (!storageFile.exists()) {
        return@withContext StorageStats(false, 0.0, 0, 0)
    }

    val fileSizeKB = storageFile.length() / 1024.0
    
    // For very large files, just estimate without parsing to avoid OOM
    // Estimate: average batch is ~10KB, average 50 samples per batch
    val estimatedBatches = (fileSizeKB / 10).toInt().coerceAtLeast(1)
    val estimatedSamples = estimatedBatches * 50
    
    // Only try to parse if file is reasonably small (< 1MB)
    var totalBatches = 0
    var totalSamples = 0
    
    if (fileSizeKB < 1000) {
        try {
            storageFile.bufferedReader().use { reader ->
                var line: String?
                var lineCount = 0
                val maxLines = 20 // Very conservative limit
                
                while (reader.readLine().also { line = it } != null) {
                    if (lineCount >= maxLines) break
                    if (line.isNullOrBlank()) continue
                    
                    // Skip very long lines
                    if (line.length > 10000) continue
                    
                    lineCount++
                    try {
                        val batch = TremorBatch.fromJsonString(line!!)
                        totalBatches++
                        totalSamples += batch.samples.size
                    } catch (e: OutOfMemoryError) {
                        Log.w("DataVisualization", "OOM parsing stats, using estimates")
                        break
                    } catch (e: Exception) {
                        // Skip parse errors
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.w("DataVisualization", "OOM getting storage stats, using estimates")
        } catch (e: Exception) {
            Log.e("DataVisualization", "Failed to get storage stats: ${e.message}")
        }
    }
    
    // Use parsed values if available, otherwise use estimates
    val finalBatches = if (totalBatches > 0) totalBatches else estimatedBatches
    val finalSamples = if (totalSamples > 0) totalSamples else estimatedSamples
    
    return@withContext StorageStats(true, fileSizeKB, finalBatches, finalSamples)
}

/**
 * Load tremor data from local storage using repository with caching.
 * Migrated from direct file I/O to use TremorDataRepository for better performance and consistency.
 */
suspend fun loadLocalData(context: Context, hoursBack: Int): List<ChartData> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val repository = com.opensource.tremorwatch.phone.data.TremorDataRepository(context)
        val (data, _) = repository.loadTremorData(hoursBack)
        Log.i("DataVisualization", "Loaded ${data.size} data points via repository (with caching)")
        data
    } catch (e: OutOfMemoryError) {
        Log.e("DataVisualization", "Out of memory loading data: ${e.message}")
        return@withContext emptyList()
    } catch (e: Exception) {
        Log.e("DataVisualization", "Failed to load local data: ${e.message}", e)
        return@withContext emptyList()
    }
}

/**
 * Aggregate data points into time buckets
 */
fun aggregateData(data: List<ChartData>, bucketMinutes: Int): List<ChartData> {
    if (data.isEmpty()) return emptyList()

    val bucketMillis = bucketMinutes * 60 * 1000L
    val buckets = mutableMapOf<Long, MutableList<ChartData>>()

    data.forEach { point ->
        val bucketKey = (point.timestamp / bucketMillis) * bucketMillis
        buckets.getOrPut(bucketKey) { mutableListOf() }.add(point)
    }

    return buckets.map { (bucketTime, points) ->
        ChartData(
            timestamp = bucketTime,
            severity = points.map { it.severity }.average(),
            tremorCount = points.sumOf { it.tremorCount },
            metadata = points.lastOrNull()?.metadata ?: emptyMap()
        )
    }.sortedBy { it.timestamp }
}

/**
 * Calculate tremor ratings from chart data
 */
fun calculateTremorRatings(data: List<ChartData>): List<TremorRating> {
    return data.map { point ->
        val rating = when {
            point.severity < 0.2 -> 0
            point.severity < 0.4 -> 1
            point.severity < 0.6 -> 2
            point.severity < 0.8 -> 3
            point.severity < 1.0 -> 4
            else -> 5
        }
        TremorRating(point.timestamp, rating)
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Time range selector dropdown
 * Uses -1 as special value for "Today" (midnight to now)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeSelector(
    selectedHours: Int,
    onHoursSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // -1 = Today, other values are hours
    val options = listOf(-1, 1, 6, 12, 24, 48)
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.menuAnchor(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(if (selectedHours == -1) "Day" else "${selectedHours}h")
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { hours ->
                DropdownMenuItem(
                    text = { Text(if (hours == -1) "Day" else "${hours}h") },
                    onClick = {
                        onHoursSelected(hours)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Day navigation with arrows
 */
@Composable
fun DayNavigator(
    startTime: Long,
    endTime: Long,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "Previous",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Text(
            text = formatTimeRangeLabel(startTime, endTime),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        IconButton(
            onClick = onNext,
            enabled = canGoForward
        ) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "Next",
                tint = if (canGoForward) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

/**
 * Gap legend
 */
@Composable
fun GapLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem("No data", Color.Gray)
        Spacer(modifier = Modifier.width(16.dp))
        LegendItem("Off wrist", Color(0xFFFF9800))
        Spacer(modifier = Modifier.width(16.dp))
        LegendItem("Charging", Color(0xFF4CAF50))
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Charts section with time range selector and navigation
 */
@Composable
fun ChartsSection(
    data: List<ChartData>,
    gapEvents: List<GapEvent>,
    hoursBack: Int,
    modifier: Modifier = Modifier
) {
    // Default to Today (-1)
    var selectedHours by remember { mutableStateOf(-1) }
    var offsetPeriods by remember { mutableStateOf(0) }
    
    // Find the actual time range of the data
    val dataTimeRange = remember(data) {
        if (data.isEmpty()) {
            Pair(System.currentTimeMillis() - (12 * 60 * 60 * 1000L), System.currentTimeMillis())
        } else {
            val minTime = data.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            val maxTime = data.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            Pair(minTime, maxTime)
        }
    }
    
    // Calculate display time range based on selection
    // For "Today" (-1), show midnight to now
    val (startTime, endTime) = remember(selectedHours, offsetPeriods) {
        if (selectedHours == -1) {
            // Day view: always show full 24 hours (midnight to midnight)
            // Data only fills in up to "now"
            val cal = Calendar.getInstance()
            
            // Apply offset for day navigation
            if (offsetPeriods > 0) {
                cal.add(Calendar.DAY_OF_YEAR, -offsetPeriods)
            }
            
            // End at midnight of next day (23:59:59.999)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val endOfDay = cal.timeInMillis
            
            // Start at midnight
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            
            Pair(startOfDay, endOfDay)
        } else {
            // Use sticky time range for hour-based selections
            calculateStickyTimeRange(selectedHours, offsetPeriods)
        }
    }
    
    // Don't filter data - pass all data to charts, they'll handle time range display
    // Filter gaps to current time range for display
    val filteredGaps = remember(gapEvents, startTime, endTime) {
        gapEvents.filter { it.startTime < endTime && it.endTime > startTime }
    }
    
    Column(modifier = modifier) {
        // Header with title and time selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Charts & Visualizations",
                style = MaterialTheme.typography.titleMedium
            )
            TimeRangeSelector(
                selectedHours = selectedHours,
                onHoursSelected = { 
                    selectedHours = it
                    offsetPeriods = 0 // Reset to current period when changing range
                }
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Day navigation - allow forward navigation to reach current time
        val now = System.currentTimeMillis()
        // Can go forward if we've gone back (offsetPeriods > 0) or if endTime is before now
        val canGoForward = offsetPeriods > 0 || (endTime + (selectedHours * 60 * 60 * 1000L)) < now
        DayNavigator(
            startTime = startTime,
            endTime = endTime,
            canGoForward = canGoForward,
            onPrevious = { offsetPeriods++ },
            onNext = { 
                if (offsetPeriods > 0) {
                    offsetPeriods--
                } else if (endTime < now) {
                    // Allow going forward even if offsetPeriods is 0, as long as we're not at current time
                    offsetPeriods = -1
                }
            }
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Gap legend
        GapLegend()
        
        Spacer(modifier = Modifier.height(12.dp))

        // Activity Overview Chart (first chart - 15-minute blocks with color coding)
        TremorActivityChart(
            data = data,
            startTime = startTime,
            endTime = endTime,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tremor Events Chart
        TremorEventsChart(
            data = data,
            startTime = startTime,
            endTime = endTime,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tremor Severity Chart (third chart)
        TremorSeverityChart(
            data = data,
            gapEvents = filteredGaps,
            startTime = startTime,
            endTime = endTime,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Unified Tremor Chart - handles both severity (line) and tremor events (hourly aggregated bars)
 * Based on the working implementation from readinfull.md
 */
@Composable
fun UnifiedTremorChart(
    data: List<ChartData>,
    gapEvents: List<GapEvent>,
    startTime: Long,
    endTime: Long,
    showTremorCount: Boolean = false,
    title: String = if (showTremorCount) "Tremor Events" else "Tremor Severity",
    modifier: Modifier = Modifier
) {
    val ChartTeal = Color(0xFF26D9B0)
    val ChartTealLight = Color(0xFF4DD9C4)
    val ChartBackgroundGradientStart = Color(0xFF1E2836)
    val ChartBackgroundGradientEnd = Color(0xFF1A2530)
    val ChartGridColor = Color(0xFF4A5A6A)
    val ChartVerticalGridColor = Color(0xFF7A8A9A)
    val ChartLabelColor = Color(0xFF8A9AAA)
    val HighIntensityColor = Color(0xFFFF5722)
    val OffWristColor = Color(0xFFFF9800)
    val ChargingColor = Color(0xFF4CAF50)
    val GapIndicatorColor = Color.Gray
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Filter data to visible time range
            val filteredData = remember(data, startTime, endTime) {
                data.filter { 
                    it.timestamp >= startTime && it.timestamp <= endTime
                }
            }
            
            // State for selected data point
            var selectedTimestamp by remember { mutableStateOf<Long?>(null) }
            
            // Calculate hour interval for grid lines and labels (needed outside Canvas)
            val timeRange = endTime - startTime
            val hoursInRange = remember(timeRange) { (timeRange / (60 * 60 * 1000)).toInt().coerceAtLeast(1) }
            // Limit to ~4 labels maximum for better readability
            val hourInterval = remember(hoursInRange) {
                when {
                    hoursInRange <= 4 -> 1
                    hoursInRange <= 8 -> 2
                    hoursInRange <= 16 -> 4
                    hoursInRange <= 24 -> 6
                    else -> 12
                }
            }
            
            // Pre-calculate hourly buckets for tremor events
            val hourlyBuckets = remember(filteredData, startTime, endTime) {
                if (showTremorCount) {
                    val buckets = mutableMapOf<Long, Int>()
                    filteredData.forEach { point ->
                        val calendar = Calendar.getInstance()
                        calendar.timeInMillis = point.timestamp
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        val hourTimestamp = calendar.timeInMillis
                        if (hourTimestamp >= startTime && hourTimestamp <= endTime) {
                            buckets[hourTimestamp] = (buckets[hourTimestamp] ?: 0) + point.tremorCount
                        }
                    }
                    buckets.toMap()
                } else {
                    emptyMap()
                }
            }
            
            val maxHourlyCount = remember(hourlyBuckets) {
                hourlyBuckets.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            }
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            
            // Show selected data info or default subtitle
            val selectedHourData = selectedTimestamp?.let { hourlyBuckets[it] }
            if (showTremorCount && selectedHourData != null && selectedTimestamp != null) {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                val startStr = timeFormat.format(Date(selectedTimestamp!!))
                val endStr = timeFormat.format(Date(selectedTimestamp!! + 60 * 60 * 1000))
                Text(
                    text = "$startStr - $endStr • $selectedHourData tremor events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (!showTremorCount && selectedTimestamp != null) {
                // Find nearest severity point
                val nearestPoint = filteredData.minByOrNull { kotlin.math.abs(it.timestamp - selectedTimestamp!!) }
                if (nearestPoint != null) {
                    val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                    val timeStr = timeFormat.format(Date(nearestPoint.timestamp))
                    Text(
                        text = "$timeStr • Severity: ${String.format("%.2f", nearestPoint.severity)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "${filteredData.size} data points • Tap for details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "${filteredData.size} data points • Tap for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Chart Canvas
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(ChartBackgroundGradientStart, ChartBackgroundGradientEnd)
                        )
                    )
                    .pointerInput(hourlyBuckets, filteredData, startTime, endTime, timeRange, showTremorCount) {
                        detectTapGestures { offset ->
                            val chartPadding = 16.dp.toPx()
                            val chartWidth = size.width - (2 * chartPadding)
                            val adjustedX = offset.x - chartPadding
                            
                            if (adjustedX >= 0 && adjustedX <= chartWidth) {
                                val tapTimeOffset = (adjustedX / chartWidth) * timeRange
                                val tapTime = startTime + tapTimeOffset.toLong()
                                
                                if (showTremorCount) {
                                    // For tremor events: snap to hourly bucket
                                    val calendar = Calendar.getInstance()
                                    calendar.timeInMillis = tapTime
                                    calendar.set(Calendar.MINUTE, 0)
                                    calendar.set(Calendar.SECOND, 0)
                                    calendar.set(Calendar.MILLISECOND, 0)
                                    val hourTimestamp = calendar.timeInMillis
                                    
                                    selectedTimestamp = if (selectedTimestamp == hourTimestamp && hourlyBuckets.containsKey(hourTimestamp)) {
                                        null
                                    } else if (hourlyBuckets.containsKey(hourTimestamp)) {
                                        hourTimestamp
                                    } else {
                                        null
                                    }
                                } else {
                                    // For severity: find nearest data point
                                    val nearestPoint = filteredData.minByOrNull { kotlin.math.abs(it.timestamp - tapTime) }
                                    selectedTimestamp = if (nearestPoint != null && selectedTimestamp == nearestPoint.timestamp) {
                                        null
                                    } else {
                                        nearestPoint?.timestamp
                                    }
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val width = size.width
                    val height = size.height
                    
                    if (timeRange <= 0) return@Canvas
                    
                    // Draw vertical grid lines at hour intervals - visible
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = startTime
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    if (calendar.timeInMillis < startTime) {
                        calendar.add(Calendar.HOUR_OF_DAY, 1)
                    }
                    
                    while (calendar.timeInMillis <= endTime) {
                        val hour = calendar.get(Calendar.HOUR_OF_DAY)
                        if (hour % hourInterval == 0) {
                            val x = ((calendar.timeInMillis - startTime).toFloat() / timeRange) * width
                            // Draw a more visible vertical line
                            drawLine(
                                color = ChartVerticalGridColor.copy(alpha = 0.8f),
                                start = Offset(x, 0f),
                                end = Offset(x, height),
                                strokeWidth = 1.5f
                            )
                            // Small tick mark at bottom
                            drawLine(
                                color = ChartVerticalGridColor.copy(alpha = 0.8f),
                                start = Offset(x, height - 4f),
                                end = Offset(x, height),
                                strokeWidth = 2f
                            )
                        }
                        calendar.add(Calendar.HOUR_OF_DAY, 1)
                    }
                    
                    // Draw horizontal grid lines
                    for (i in 0..3) {
                        val y = height * i / 3
                        drawLine(
                            color = ChartGridColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                        )
                    }
                    
                    // Draw gap indicators
                    gapEvents.forEach { gap ->
                        if (gap.endTime > startTime && gap.startTime < endTime) {
                            val gapStart = ((gap.startTime.coerceAtLeast(startTime) - startTime).toFloat() / timeRange) * width
                            val gapEnd = ((gap.endTime.coerceAtMost(endTime) - startTime).toFloat() / timeRange) * width
                            val gapWidth = (gapEnd - gapStart).coerceAtLeast(4f)
                            
                            val gapColor = when (gap.type) {
                                GapType.OFF_WRIST -> OffWristColor
                                GapType.CHARGING -> ChargingColor
                                else -> GapIndicatorColor
                            }
                            
                            // Draw prominent gap indicator bar at bottom (within canvas bounds)
                            drawRect(
                                color = gapColor.copy(alpha = 0.9f),
                                topLeft = Offset(gapStart, height - 12f),
                                size = Size(gapWidth, 12f)
                            )
                        }
                    }
                    
                    if (filteredData.isNotEmpty()) {
                        if (showTremorCount) {
                            // Draw bars for pre-calculated hourly buckets
                            hourlyBuckets.forEach { (hourTimestamp, count) ->
                                if (count > 0) {
                                    val x = ((hourTimestamp - startTime).toFloat() / timeRange) * width
                                    val normalizedHeight = count.toFloat() / maxHourlyCount.toFloat()
                                    val barHeight = (height * normalizedHeight * 0.9f).coerceAtLeast(4f)
                                    val y = height - barHeight
                                    
                                    val barWidth = (width / hoursInRange.coerceAtLeast(1).toFloat() * 0.6f).coerceIn(12f, 50f)
                                    val isSelected = selectedTimestamp == hourTimestamp
                                    val barColor = if (isSelected) ChartTeal else ChartTeal.copy(alpha = 0.8f)
                                    
                                    drawRect(
                                        color = barColor,
                                        topLeft = Offset((x - barWidth/2).coerceIn(0f, width - barWidth), y),
                                        size = Size(barWidth, barHeight)
                                    )
                                    
                                    // Draw selection border
                                    if (isSelected) {
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset((x - barWidth/2).coerceIn(0f, width - barWidth), y),
                                            size = Size(barWidth, barHeight),
                                            style = Stroke(width = 2f)
                                        )
                                    }
                                }
                            }
                        } else {
                            // LINE CHART: For severity, use segment-based line/area chart that breaks at gaps
                            val maxValue = filteredData.maxOfOrNull { it.severity }?.coerceAtLeast(0.1) ?: 0.1

                            // Calculate points with time-based X positioning
                            val pointsWithTimestamps = filteredData.sortedBy { it.timestamp }.map { point ->
                                val x = ((point.timestamp - startTime).toFloat() / timeRange) * width
                                val normalizedValue = (point.severity / maxValue).toFloat().coerceIn(0f, 1f)
                                val y = height - (height * normalizedValue)
                                Triple(Offset(x.coerceIn(0f, width), y), point.timestamp, point)
                            }

                            // Helper function to check if there's a gap between two timestamps
                            fun isGapBetween(t1: Long, t2: Long): Boolean {
                                return gapEvents.any { gap ->
                                    // Check if gap overlaps with the interval between t1 and t2
                                    gap.startTime < t2 && gap.endTime > t1
                                }
                            }

                            // Split points into continuous segments (break at gaps)
                            val segments = mutableListOf<List<Offset>>()
                            var currentSegment = mutableListOf<Offset>()

                            pointsWithTimestamps.forEachIndexed { index, (point, timestamp, _) ->
                                if (index > 0) {
                                    val prevTimestamp = pointsWithTimestamps[index - 1].second
                                    if (isGapBetween(prevTimestamp, timestamp)) {
                                        // Gap detected - save current segment and start new one
                                        if (currentSegment.size > 1) {
                                            segments.add(currentSegment)
                                        }
                                        currentSegment = mutableListOf()
                                    }
                                }
                                currentSegment.add(point)
                            }
                            // Add final segment
                            if (currentSegment.size > 1) {
                                segments.add(currentSegment)
                            }

                            // Draw filled area for each segment
                            segments.forEach { segmentPoints ->
                                if (segmentPoints.size > 1) {
                                    val areaPath = Path().apply {
                                        moveTo(segmentPoints[0].x, height)
                                        segmentPoints.forEach { lineTo(it.x, it.y) }
                                        lineTo(segmentPoints.last().x, height)
                                        close()
                                    }
                                    drawPath(
                                        path = areaPath,
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                ChartTeal.copy(alpha = 0.4f),
                                                ChartTeal.copy(alpha = 0.05f)
                                            )
                                        )
                                    )
                                }
                            }

                            // Draw line for each segment
                            segments.forEach { segmentPoints ->
                                if (segmentPoints.size > 1) {
                                    val linePath = createSmoothPath(segmentPoints)
                                    drawPath(
                                        path = linePath,
                                        color = ChartTeal.copy(alpha = 0.3f),
                                        style = Stroke(width = 8f)
                                    )
                                    drawPath(
                                        path = linePath,
                                        color = ChartTealLight,
                                        style = Stroke(width = 3f)
                                    )
                                }
                            }

                            // Draw points for sparse data and selection indicator
                            pointsWithTimestamps.forEachIndexed { index, (point, timestamp, chartData) ->
                                val isSelected = selectedTimestamp == timestamp
                                
                                // Draw all points if sparse, or just selected point
                                if (pointsWithTimestamps.size < 50 || isSelected) {
                                    if (isSelected) {
                                        // Draw selection crosshair
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.5f),
                                            start = Offset(point.x, 0f),
                                            end = Offset(point.x, height),
                                            strokeWidth = 1f,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                                        )
                                        // Larger highlighted point
                                        drawCircle(
                                            color = ChartTeal,
                                            radius = 8f,
                                            center = point
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = 8f,
                                            center = point,
                                            style = Stroke(width = 2f)
                                        )
                                    } else {
                                        drawCircle(
                                            color = ChartTeal,
                                            radius = 4f,
                                            center = point
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Draw "now" indicator if in range
                        val now = System.currentTimeMillis()
                        if (now in startTime..endTime) {
                            val nowX = ((now - startTime).toFloat() / timeRange) * width
                            drawLine(
                                color = HighIntensityColor.copy(alpha = 0.7f),
                                start = Offset(nowX, 0f),
                                end = Offset(nowX, height),
                                strokeWidth = 2f
                            )
                        }
                    }
                }
            }
            
            // Time axis labels - aligned with hour grid lines
            Spacer(modifier = Modifier.height(8.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 16.dp)) {
                val containerWidth = maxWidth
                val timeLabels = remember(startTime, endTime, timeRange) {
                    val labels = mutableListOf<Pair<String, Float>>()
                    val hoursInRange = (timeRange / (60 * 60 * 1000)).toInt()
                    
                    if (hoursInRange <= 1) {
                        // For 1hr view: show 3 labels with minutes (start, middle, end)
                        val positions = listOf(0f, 0.5f, 1f)
                        positions.forEach { pos ->
                            val timestamp = startTime + (timeRange * pos).toLong()
                            labels.add(Pair(formatTimeWithMinutes(timestamp), pos))
                        }
                    } else {
                        // For longer views: show 5 evenly-spaced labels at 0%, 25%, 50%, 75%, 100%
                        val positions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                        positions.forEach { pos ->
                            val timestamp = startTime + (timeRange * pos).toLong()
                            labels.add(Pair(formatTime(timestamp), pos))
                        }
                    }
                    labels
                }
                
                timeLabels.forEach { (label, xPos) ->
                    // Calculate the actual x position based on the container width
                    val xOffset = (containerWidth * xPos) - 20.dp
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ChartLabelColor,
                        modifier = Modifier
                            .offset(x = xOffset)
                            .width(50.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Statistics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val statsDataInRange = filteredData
                if (showTremorCount) {
                    // For tremor events, show hourly aggregated stats
                    val hoursInRange = ((endTime - startTime) / (60 * 60 * 1000)).toInt().coerceAtLeast(1)
                    val hourlyBuckets = mutableMapOf<Int, Int>()
                    statsDataInRange.forEach { point ->
                        val hourIndex = ((point.timestamp - startTime) / (60 * 60 * 1000)).toInt()
                            .coerceIn(0, hoursInRange - 1)
                        hourlyBuckets[hourIndex] = (hourlyBuckets[hourIndex] ?: 0) + point.tremorCount
                    }
                    val totalEvents = statsDataInRange.sumOf { it.tremorCount }
                    val maxHourlyCount = hourlyBuckets.values.maxOrNull() ?: 0
                    val avgPerHour = if (hoursInRange > 0) totalEvents.toFloat() / hoursInRange else 0f
                    
                    StatBox(label = "Total", value = totalEvents.toString())
                    StatBox(label = "Peak/hr", value = maxHourlyCount.toString())
                    StatBox(label = "Avg/hr", value = String.format("%.1f", avgPerHour))
                } else {
                    StatBox(label = "Max", value = String.format("%.2f", statsDataInRange.maxOfOrNull { it.severity } ?: 0.0))
                    StatBox(label = "Avg", value = String.format("%.2f", if (statsDataInRange.isNotEmpty()) statsDataInRange.map { it.severity }.average() else 0.0))
                    StatBox(label = "Min", value = String.format("%.2f", statsDataInRange.minOfOrNull { it.severity } ?: 0.0))
                }
            }
        }
    }
}

/**
 * Tremor Severity Chart with dark theme (uses UnifiedTremorChart)
 */
@Composable
fun TremorSeverityChart(
    data: List<ChartData>,
    gapEvents: List<GapEvent>,
    startTime: Long,
    endTime: Long,
    modifier: Modifier = Modifier
) {
    UnifiedTremorChart(
        data = data,
        gapEvents = gapEvents,
        startTime = startTime,
        endTime = endTime,
        showTremorCount = false,
        title = "Tremor Severity",
        modifier = modifier
    )
}

/**
 * Tremor Events Chart (uses UnifiedTremorChart)
 */
@Composable
fun TremorEventsChart(
    data: List<ChartData>,
    startTime: Long,
    endTime: Long,
    modifier: Modifier = Modifier
) {
    UnifiedTremorChart(
        data = data,
        gapEvents = emptyList(), // Events chart doesn't need gap indicators
        startTime = startTime,
        endTime = endTime,
        showTremorCount = true,
        title = "Tremor Events",
        modifier = modifier
    )
}

/**
 * Activity Overview - Tremor intensity by 15-minute blocks with stacked bar chart
 * Shows Low/Medium/High intensity distribution over time
 */
@Composable
fun TremorActivityChart(
    data: List<ChartData>,
    startTime: Long,
    endTime: Long,
    modifier: Modifier = Modifier
) {
    val ChartBackgroundGradientStart = Color(0xFF1E2836)
    val ChartBackgroundGradientEnd = Color(0xFF1A2530)
    val ChartGridColor = Color(0xFF4A5A6A)
    val ChartVerticalGridColor = Color(0xFF7A8A9A)
    val ChartLabelColor = Color(0xFF8A9AAA)
    val lowColor = Color(0xFF4CAF50) // Green
    val mediumColor = Color(0xFFFFEB3B) // Yellow
    val highColor = Color(0xFFFF5252) // Red

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Filter data to visible time range (inclusive boundaries)
            val visibleData = remember(data, startTime, endTime) {
                data.filter {
                    it.timestamp >= startTime && it.timestamp <= endTime
                }
            }
            
            // State for selected bar
            var selectedBlockTime by remember { mutableStateOf<Long?>(null) }
            
            // Pre-calculate blocks data for both drawing and hit testing
            val blockSizeMs = 15 * 60 * 1000L // 15 minutes
            val blocks = remember(visibleData, startTime, endTime) {
                val result = mutableMapOf<Long, Pair<Int, Int>>() // Tremor count, sample count
                visibleData.forEach { point ->
                    val blockTime = (point.timestamp / blockSizeMs) * blockSizeMs
                    if (blockTime >= startTime && blockTime <= endTime) {
                        val current = result.getOrDefault(blockTime, Pair(0, 0))
                        result[blockTime] = Pair(
                            current.first + point.tremorCount,
                            current.second + 1
                        )
                    }
                }
                result.toMap()
            }
            
            val maxTremorCount = remember(blocks) { 
                blocks.values.maxOfOrNull { it.first }?.coerceAtLeast(1) ?: 1 
            }

            Text(
                text = "Activity Overview",
                style = MaterialTheme.typography.titleSmall
            )
            
            // Show selected bar info or default subtitle
            val selectedBlock = selectedBlockTime?.let { blocks[it] }
            if (selectedBlock != null && selectedBlockTime != null) {
                val blockEndTime = selectedBlockTime!! + blockSizeMs
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                val startStr = timeFormat.format(Date(selectedBlockTime!!))
                val endStr = timeFormat.format(Date(blockEndTime))
                val intensity = when {
                    selectedBlock.first <= maxTremorCount / 3 -> "Low"
                    selectedBlock.first <= (maxTremorCount * 2) / 3 -> "Medium"
                    else -> "High"
                }
                Text(
                    text = "$startStr - $endStr • ${selectedBlock.first} tremors ($intensity)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "Tremor intensity by 15-minute blocks • Tap bar for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Calculate hour interval for grid lines and labels
            val timeRange = endTime - startTime
            val hoursInRange = remember(timeRange) { (timeRange / (60 * 60 * 1000)).toInt().coerceAtLeast(1) }
            // Limit to ~4 labels maximum for better readability
            val hourInterval = remember(hoursInRange) {
                when {
                    hoursInRange <= 4 -> 1
                    hoursInRange <= 8 -> 2
                    hoursInRange <= 16 -> 4
                    hoursInRange <= 24 -> 6
                    else -> 12
                }
            }
            
            // Chart Canvas - same height as other charts (180.dp)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(ChartBackgroundGradientStart, ChartBackgroundGradientEnd)
                        )
                    )
                    .pointerInput(blocks, startTime, endTime, timeRange) {
                        detectTapGestures { offset ->
                            // Calculate which block was tapped based on x position
                            val chartPadding = 16.dp.toPx()
                            val chartWidth = size.width - (2 * chartPadding)
                            val adjustedX = offset.x - chartPadding
                            
                            if (adjustedX >= 0 && adjustedX <= chartWidth) {
                                val tapTimeOffset = (adjustedX / chartWidth) * timeRange
                                val tapTime = startTime + tapTimeOffset.toLong()
                                val blockTime = (tapTime / blockSizeMs) * blockSizeMs
                                
                                // Toggle selection - tap same bar to deselect
                                selectedBlockTime = if (selectedBlockTime == blockTime && blocks.containsKey(blockTime)) {
                                    null
                                } else if (blocks.containsKey(blockTime)) {
                                    blockTime
                                } else {
                                    null
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val width = size.width
                    val height = size.height
                    
                    if (timeRange <= 0 || visibleData.isEmpty()) return@Canvas
                    
                    // Draw vertical grid lines at hour intervals
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = startTime
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    if (calendar.timeInMillis < startTime) {
                        calendar.add(Calendar.HOUR_OF_DAY, 1)
                    }
                    
                    while (calendar.timeInMillis <= endTime) {
                        val hour = calendar.get(Calendar.HOUR_OF_DAY)
                        if (hour % hourInterval == 0) {
                            val x = ((calendar.timeInMillis - startTime).toFloat() / timeRange) * width
                            drawLine(
                                color = ChartVerticalGridColor.copy(alpha = 0.8f),
                                start = Offset(x, 0f),
                                end = Offset(x, height),
                                strokeWidth = 1.5f
                            )
                            // Small tick mark at bottom
                            drawLine(
                                color = ChartVerticalGridColor.copy(alpha = 0.8f),
                                start = Offset(x, height - 4f),
                                end = Offset(x, height),
                                strokeWidth = 2f
                            )
                        }
                        calendar.add(Calendar.HOUR_OF_DAY, 1)
                    }
                    
                    // Draw horizontal grid lines
                    for (i in 0..3) {
                        val y = height * i / 3
                        drawLine(
                            color = ChartGridColor,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                        )
                    }
                    
                    // Calculate height-based color thresholds
                    val lowThreshold = maxTremorCount / 3
                    val mediumThreshold = (maxTremorCount * 2) / 3

                    // Draw single-colored bars for each 15-minute block
                    blocks.forEach { (blockTime, data) ->
                        val (totalTremorCount, sampleCount) = data
                        if (sampleCount > 0 && totalTremorCount > 0) {
                            val blockCenter = blockTime + (blockSizeMs / 2)
                            val x = ((blockCenter - startTime).toFloat() / timeRange) * width

                            // Bar width
                            val barWidth = ((blockSizeMs.toFloat() / timeRange) * width * 0.8f).coerceIn(8f, 50f)

                            // Bar height (normalized by tremor count)
                            val normalizedHeight = (totalTremorCount.toFloat() / maxTremorCount)
                            val barHeight = (height * normalizedHeight).coerceAtLeast(8f)

                            // Determine bar color based on tremor count magnitude (height)
                            val barColor = when {
                                totalTremorCount <= lowThreshold -> lowColor
                                totalTremorCount <= mediumThreshold -> mediumColor
                                else -> highColor
                            }
                            
                            // Highlight selected bar
                            val isSelected = selectedBlockTime == blockTime
                            val displayColor = if (isSelected) barColor.copy(alpha = 1f) else barColor.copy(alpha = 0.8f)

                            // Draw single-colored bar
                            drawRoundRect(
                                color = displayColor,
                                topLeft = Offset(
                                    (x - barWidth / 2).coerceIn(0f, width - barWidth),
                                    height - barHeight
                                ),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(2f, 2f)
                            )
                            
                            // Draw selection indicator (border) for selected bar
                            if (isSelected) {
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(
                                        (x - barWidth / 2).coerceIn(0f, width - barWidth),
                                        height - barHeight
                                    ),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(2f, 2f),
                                    style = Stroke(width = 2f)
                                )
                            }
                        }
                    }
                    
                    // Draw "now" indicator if in range
                    val now = System.currentTimeMillis()
                    if (now in startTime..endTime) {
                        val nowX = ((now - startTime).toFloat() / timeRange) * width
                        drawLine(
                            color = highColor.copy(alpha = 0.7f),
                            start = Offset(nowX, 0f),
                            end = Offset(nowX, height),
                            strokeWidth = 2f
                        )
                    }
                }
            }
            
            // Time axis labels - aligned with hour grid lines (same as other charts)
            Spacer(modifier = Modifier.height(8.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(20.dp).padding(horizontal = 16.dp)) {
                val containerWidth = maxWidth
                val timeLabels = remember(startTime, endTime, timeRange) {
                    val labels = mutableListOf<Pair<String, Float>>()
                    val hoursInRange = (timeRange / (60 * 60 * 1000)).toInt()
                    
                    if (hoursInRange <= 1) {
                        // For 1hr view: show 3 labels with minutes (start, middle, end)
                        val positions = listOf(0f, 0.5f, 1f)
                        positions.forEach { pos ->
                            val timestamp = startTime + (timeRange * pos).toLong()
                            labels.add(Pair(formatTimeWithMinutes(timestamp), pos))
                        }
                    } else {
                        // For longer views: show 5 evenly-spaced labels at 0%, 25%, 50%, 75%, 100%
                        val positions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                        positions.forEach { pos ->
                            val timestamp = startTime + (timeRange * pos).toLong()
                            labels.add(Pair(formatTime(timestamp), pos))
                        }
                    }
                    labels
                }
                
                timeLabels.forEach { (label, xPos) ->
                    // Calculate the actual x position based on the container width
                    val xOffset = (containerWidth * xPos) - 20.dp
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = ChartLabelColor,
                        modifier = Modifier
                            .offset(x = xOffset)
                            .width(50.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Legend row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Low
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(lowColor, shape = RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Low",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Medium
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(mediumColor, shape = RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Medium",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(16.dp))

                // High
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(highColor, shape = RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "High",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stats row - calculate activity overview stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Aggregate data into 15-minute blocks for stats
                val blockSizeMs = 15 * 60 * 1000L
                val blocks = mutableMapOf<Long, Triple<Int, Int, Int>>()

                visibleData.forEach { point ->
                    val blockTime = (point.timestamp / blockSizeMs) * blockSizeMs
                    if (blockTime >= startTime && blockTime <= endTime) {
                        val current = blocks.getOrDefault(blockTime, Triple(0, 0, 0))
                        val updated = when {
                            point.severity < 0.3 -> Triple(current.first + 1, current.second, current.third)
                            point.severity < 0.6 -> Triple(current.first, current.second + 1, current.third)
                            else -> Triple(current.first, current.second, current.third + 1)
                        }
                        blocks[blockTime] = updated
                    }
                }

                val totalEvents = visibleData.size
                val activePeriods = blocks.size
                val totalPeriods = ((endTime - startTime) / blockSizeMs).toInt()

                // Find peak time (block with most events)
                val peakBlock = blocks.maxByOrNull { it.value.first + it.value.second + it.value.third }
                val peakTimeStr = if (peakBlock != null) {
                    val peakCal = Calendar.getInstance().apply { timeInMillis = peakBlock.key }
                    String.format("%02d:%02d", peakCal.get(Calendar.HOUR_OF_DAY), peakCal.get(Calendar.MINUTE))
                } else {
                    "--:--"
                }

                StatBox(label = "Total Events", value = totalEvents.toString())
                StatBox(label = "Peak Time", value = peakTimeStr)
                StatBox(label = "Active Periods", value = "$activePeriods/$totalPeriods")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = "Each bar represents a 15-minute period. Color indicates tremor intensity relative to peak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

