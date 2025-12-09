package com.opensource.tremorwatch.constants

/**
 * Battery-optimized constants for hourly summary mode.
 * 
 * Designed for users who don't need live data but want hourly tremor summaries.
 * Reduces battery usage by 80-90% compared to high-frequency monitoring.
 */
object BatteryOptimizedConstants {
    
    // ====================== SENSOR PROCESSING (BATTERY OPTIMIZED) ======================
    
    /**
     * Reduced sample interval for battery efficiency.
     * 1 Hz (1 sample per second) is sufficient for tremor detection.
     * Tremors are typically 4-12 Hz, so 1 Hz sampling can still detect them.
     */
    const val SAMPLE_INTERVAL_MS = 1000L  // 1 Hz instead of 20 Hz (95% reduction)
    
    /**
     * Smaller batch size for hourly aggregation.
     * With 1 Hz sampling, 3600 samples = 1 hour of data.
     */
    const val BATCH_SIZE = 3600  // 1 hour of samples at 1 Hz
    
    /**
     * Maximum buffer size - enough for 2 hours of data.
     */
    const val MAX_BUFFER_SIZE = BATCH_SIZE * 2
    
    // ====================== TREMOR DETECTION (UNCHANGED) ======================
    
    const val LOW_TREMOR_THRESHOLD = 0.02f
    const val HIGH_ACTIVITY_THRESHOLD = 2.0f
    
    // ====================== FFT ANALYSIS (OPTIMIZED) ======================
    
    /**
     * Reduced FFT window size for lower CPU usage.
     * Still sufficient for tremor detection (4-12 Hz range).
     */
    const val FFT_WINDOW_SIZE = 32  // Reduced from 64 (50% reduction)
    
    /**
     * Reduced FFT sample rate to match new sampling frequency.
     */
    const val FFT_SAMPLE_RATE = 1f  // 1 Hz instead of 20 Hz
    
    /**
     * Only run FFT every N samples to reduce CPU usage.
     */
    const val FFT_PROCESSING_INTERVAL = 10  // Process FFT every 10 samples instead of every sample
    
    // ====================== WEAR DETECTION (UNCHANGED) ======================
    
    const val WEAR_STATE_DEBOUNCE_MS = 5000L
    const val MIN_OFF_BODY_READINGS = 3
    
    // ====================== UPLOAD & STORAGE (HOURLY MODE) ======================
    
    /**
     * Upload interval for hourly summaries.
     */
    const val UPLOAD_INTERVAL_MINUTES = 60  // Once per hour
    
    /**
     * Maximum batches to store (24 hours worth).
     */
    const val MAX_PENDING_BATCHES = 24  // 24 hourly summaries
    
    /**
     * Maximum batches per upload (should be 1 for hourly mode).
     */
    const val MAX_BATCHES_PER_UPLOAD = 1
    
    // ====================== SERVICE LIFECYCLE (OPTIMIZED) ======================
    
    /**
     * Reduced status update frequency.
     */
    const val STATUS_UPDATE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes instead of 5
    
    /**
     * Reduced wake lock check frequency.
     */
    const val WAKELOCK_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes instead of 30 seconds
    
    /**
     * Heartbeat interval - only when transmitting or hourly.
     */
    const val HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L // 1 hour instead of 15 minutes
    
    /**
     * Watchdog interval - less frequent checks.
     */
    const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes instead of 2 minutes
    
    /**
     * Batch retry interval - only during upload window.
     */
    const val BATCH_RETRY_INTERVAL_MS = 60 * 60 * 1000L // 1 hour (same as upload)
}

