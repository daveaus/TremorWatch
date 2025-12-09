package com.opensource.tremorwatch.constants

/**
 * Constants for tremor monitoring and data collection.
 *
 * These constants control sensor sampling, data batching, and processing behavior.
 * Grouped by functionality for better organization and maintainability.
 */
object MonitoringConstants {
    
    // ====================== SENSOR PROCESSING ======================
    
    /**
     * Sample interval in milliseconds.
     * Battery-optimized: 1 Hz (1000ms) instead of 20 Hz (50ms) for 95% battery savings.
     * Still sufficient for tremor detection (tremors are 4-12 Hz).
     */
    const val SAMPLE_INTERVAL_MS = 1000L  // 1 Hz - battery optimized
    
    /**
     * Number of samples per batch.
     * Battery-optimized: 600 samples = 10 minutes at 1 Hz sampling.
     * Creates batches every 10 minutes for reasonable data visibility while maintaining battery efficiency.
     */
    const val BATCH_SIZE = 600  // 10 minutes of data at 1 Hz (was 3600 = 1 hour, too long for user feedback)
    
    /**
     * Maximum buffer size before overflow protection kicks in.
     * When buffer exceeds this size, oldest samples are dropped.
     */
    const val MAX_BUFFER_SIZE = BATCH_SIZE * 2
    
    // ====================== TREMOR DETECTION ======================
    
    /**
     * Minimum gyroscope magnitude threshold for tremor detection (rad/s).
     * Values below this are considered noise and ignored.
     */
    const val LOW_TREMOR_THRESHOLD = 0.02f
    
    /**
     * Maximum gyroscope magnitude threshold for tremor detection (rad/s).
     * Values above this are considered intentional movement, not tremor.
     */
    const val HIGH_ACTIVITY_THRESHOLD = 2.0f
    
    // ====================== FFT ANALYSIS ======================
    
    /**
     * FFT window size (number of samples).
     * Phase 4: Increased to 64 for better frequency resolution (opus45 review).
     * 64 samples at 50 Hz = 1.28 seconds, giving 0.78 Hz resolution.
     * This allows distinguishing 4 Hz vs 5 Hz tremors (resting vs Essential).
     */
    const val FFT_WINDOW_SIZE = 64  // Increased from 32 for better frequency resolution
    
    /**
     * FFT sample rate in Hz.
     * Uses actual sensor rate (~50 Hz from SENSOR_DELAY_GAME) for FFT window.
     * This allows detection of tremor frequencies (4-12 Hz) - Nyquist limit at 50 Hz is 25 Hz,
     * which fully covers all tremor ranges (4-6 Hz resting, 5-8 Hz Essential, 8-12 Hz Physiological).
     * Note: We save samples at 1 Hz for battery, but FFT window fills at sensor rate (~50 Hz).
     */
    const val FFT_SAMPLE_RATE = 50f  // ~50 Hz - matches SENSOR_DELAY_GAME rate
    
    /**
     * Only run FFT every N samples to reduce CPU usage.
     * Phase 4: Reduced to 5 for more responsive tremor detection (opus45 review).
     * At 50 Hz sensor rate, this means FFT every 100ms instead of every 200ms.
     */
    const val FFT_PROCESSING_INTERVAL = 5  // Reduced from 10 for better responsiveness
    
    // ====================== WEAR DETECTION ======================
    
    /**
     * Debounce time for wear state changes (milliseconds).
     * Requires sustained off-body detection for this duration before pausing.
     */
    const val WEAR_STATE_DEBOUNCE_MS = 5000L
    
    /**
     * Minimum number of consecutive off-body readings required before starting debounce timer.
     * Prevents false triggers from noisy sensor readings.
     */
    const val MIN_OFF_BODY_READINGS = 3
    
    // ====================== UPLOAD & STORAGE ======================
    
    /**
     * Maximum number of batches to process per upload operation.
     * Prevents ANR (Application Not Responding) errors during large uploads.
     */
    const val MAX_BATCHES_PER_UPLOAD = 20
    
    /**
     * Maximum number of pending batches to store offline.
     * Battery-optimized: 144 batches = 24 hours of 10-minute batches.
     */
    const val MAX_PENDING_BATCHES = 144  // 24 hours of 10-minute batches (144 batches/day)
    
    // ====================== SERVICE LIFECYCLE ======================
    
    /**
     * Interval for periodic status updates (milliseconds).
     * Battery-optimized: Reduced frequency (30 minutes).
     */
    const val STATUS_UPDATE_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes - battery optimized
    
    /**
     * Interval for wake lock monitoring (milliseconds).
     * Battery-optimized: Less frequent checks (10 minutes) - only check if disrupted.
     */
    const val WAKELOCK_CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes - battery optimized
    
    /**
     * Window for tracking wake lock disruptions (milliseconds).
     * Used to detect if app is on Samsung's sleeping apps list.
     */
    const val DISRUPTION_CHECK_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    
    /**
     * Threshold for wake lock disruption count.
     * If wakelock is disabled this many times in the check window, app is likely on sleeping list.
     */
    const val DISRUPTION_THRESHOLD = 5
}

