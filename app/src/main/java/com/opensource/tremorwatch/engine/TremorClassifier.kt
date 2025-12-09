package com.opensource.tremorwatch.engine

import timber.log.Timber

/**
 * Classifies detected tremors into clinical categories based on frequency,
 * activity state, and signal characteristics.
 * 
 * Tremor Types:
 * - RESTING: Present at rest, diminishes with movement (4-6 Hz, classic resting)
 * - POSTURAL: Present when holding position against gravity (4-12 Hz)
 * - KINETIC: Present during voluntary movement (variable frequency)
 * - ESSENTIAL: Action tremor, often postural/kinetic (4-12 Hz, typically 5-8 Hz)
 * - PHYSIOLOGICAL: Normal tremor, usually 8-12 Hz, low amplitude
 * - UNKNOWN: Cannot determine type from available data
 * 
 * Clinical significance:
 * - resting tremor: 4-6 Hz "pill-rolling", asymmetric, improves with action
 * - action tremor: 4-12 Hz, bilateral, worsens with action, family history common
 * - Physiological tremor: 8-12 Hz, enhanced by anxiety/caffeine/fatigue
 */
object TremorClassifier {
    
    /**
     * Tremor type classification result
     */
    enum class TremorType(val displayName: String, val description: String) {
        RESTING("Resting", "Present at rest, typical of resting tremor (4-6 Hz)"),
        POSTURAL("Postural", "Present when holding position (4-12 Hz)"),
        KINETIC("Kinetic", "Present during movement"),
        ESSENTIAL("Essential", "Action tremor, often bilateral (5-8 Hz)"),
        PHYSIOLOGICAL("Physiological", "Normal enhanced tremor (8-12 Hz)"),
        MIXED("Mixed", "Features of multiple tremor types"),
        UNKNOWN("Unknown", "Insufficient data for classification")
    }
    
    /**
     * Detailed classification result with confidence and reasoning
     */
    data class ClassificationResult(
        val primaryType: TremorType,
        val confidence: Float,           // 0-1 confidence in classification
        val secondaryType: TremorType?,  // Possible alternative classification
        val frequencyHz: Float,
        val isResting: Boolean,
        val reasoning: String            // Human-readable explanation
    )
    
    // Frequency band definitions (Hz)
    private const val resting_LOW = 4.0f
    private const val resting_HIGH = 6.0f
    private const val ESSENTIAL_TYPICAL_LOW = 5.0f
    private const val ESSENTIAL_TYPICAL_HIGH = 8.0f
    private const val POSTURAL_LOW = 4.0f
    private const val POSTURAL_HIGH = 12.0f
    private const val PHYSIOLOGICAL_LOW = 8.0f
    private const val PHYSIOLOGICAL_HIGH = 12.0f
    
    // Activity state thresholds
    private const val RESTING_POWER_THRESHOLD = 10.0f
    private const val HIGH_ACTIVITY_THRESHOLD = 50.0f
    
    // Classification thresholds
    private const val HIGH_CONFIDENCE_THRESHOLD = 0.7f
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.5f
    
    /**
     * Classify a detected tremor based on frequency and activity characteristics.
     * 
     * @param dominantFrequency Dominant frequency from FFT analysis (Hz)
     * @param totalPower Total power from FFT (indicates activity level)
     * @param bandRatio Ratio of tremor band power to total power
     * @param confidence Detection confidence from TremorFFT
     * @param accelMagnitude Accelerometer magnitude (for activity detection)
     * @return ClassificationResult with type and confidence
     */
    fun classify(
        dominantFrequency: Float,
        totalPower: Float,
        bandRatio: Float,
        confidence: Float,
        accelMagnitude: Float = 0f
    ): ClassificationResult {
        
        // Determine activity state
        val isResting = totalPower < RESTING_POWER_THRESHOLD
        val isHighActivity = totalPower > HIGH_ACTIVITY_THRESHOLD || accelMagnitude > 2.0f
        
        // Invalid frequency - can't classify
        if (dominantFrequency < 3.0f || dominantFrequency > 15.0f) {
            return ClassificationResult(
                primaryType = TremorType.UNKNOWN,
                confidence = 0f,
                secondaryType = null,
                frequencyHz = dominantFrequency,
                isResting = isResting,
                reasoning = "Frequency ${dominantFrequency}Hz outside tremor range (3-15 Hz)"
            )
        }
        
        // Classify based on frequency and activity state
        return when {
            // Classic resting tremor pattern
            isResting && dominantFrequency in resting_LOW..resting_HIGH -> {
                val RESTINGsConfidence = calculaterestingConfidence(
                    dominantFrequency, bandRatio, isResting, confidence
                )
                ClassificationResult(
                    primaryType = TremorType.RESTING,
                    confidence = RESTINGsConfidence,
                    secondaryType = if (RESTINGsConfidence < HIGH_CONFIDENCE_THRESHOLD) TremorType.ESSENTIAL else null,
                    frequencyHz = dominantFrequency,
                    isResting = true,
                    reasoning = "Resting tremor at ${String.format("%.1f", dominantFrequency)}Hz - consistent with resting pattern"
                )
            }
            
            // Physiological tremor (high frequency, often during activity)
            dominantFrequency in PHYSIOLOGICAL_LOW..PHYSIOLOGICAL_HIGH && bandRatio < 0.15f -> {
                ClassificationResult(
                    primaryType = TremorType.PHYSIOLOGICAL,
                    confidence = 0.6f,
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = isResting,
                    reasoning = "High frequency (${String.format("%.1f", dominantFrequency)}Hz) with low band ratio - likely physiological"
                )
            }
            
            // action tremor pattern (action tremor, 5-8 Hz typical)
            !isResting && dominantFrequency in ESSENTIAL_TYPICAL_LOW..ESSENTIAL_TYPICAL_HIGH -> {
                val essentialConfidence = calculateEssentialConfidence(
                    dominantFrequency, bandRatio, isResting, confidence
                )
                ClassificationResult(
                    primaryType = TremorType.ESSENTIAL,
                    confidence = essentialConfidence,
                    secondaryType = if (dominantFrequency <= 6f) TremorType.POSTURAL else null,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Action tremor at ${String.format("%.1f", dominantFrequency)}Hz - consistent with action tremor"
                )
            }
            
            // Postural tremor (holding position, broad frequency range)
            !isResting && !isHighActivity && dominantFrequency in POSTURAL_LOW..POSTURAL_HIGH -> {
                ClassificationResult(
                    primaryType = TremorType.POSTURAL,
                    confidence = 0.5f + (bandRatio * 0.3f),
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Tremor during postural hold at ${String.format("%.1f", dominantFrequency)}Hz"
                )
            }
            
            // Kinetic tremor (during high activity/movement)
            isHighActivity && dominantFrequency in 3f..12f -> {
                ClassificationResult(
                    primaryType = TremorType.KINETIC,
                    confidence = 0.4f + (confidence * 0.3f),
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = false,
                    reasoning = "Tremor during active movement at ${String.format("%.1f", dominantFrequency)}Hz"
                )
            }
            
            // Resting but higher frequency - could be mixed or essential at rest
            isResting && dominantFrequency in 6f..12f -> {
                ClassificationResult(
                    primaryType = TremorType.MIXED,
                    confidence = 0.4f,
                    secondaryType = TremorType.ESSENTIAL,
                    frequencyHz = dominantFrequency,
                    isResting = true,
                    reasoning = "Resting tremor at ${String.format("%.1f", dominantFrequency)}Hz - atypical frequency for pure resting"
                )
            }
            
            // Default case
            else -> {
                ClassificationResult(
                    primaryType = TremorType.UNKNOWN,
                    confidence = 0.2f,
                    secondaryType = null,
                    frequencyHz = dominantFrequency,
                    isResting = isResting,
                    reasoning = "Unable to classify: freq=${String.format("%.1f", dominantFrequency)}Hz, resting=$isResting"
                )
            }
        }
    }
    
    /**
     * Calculate confidence for resting tremor classification
     */
    private fun calculaterestingConfidence(
        frequency: Float,
        bandRatio: Float,
        isResting: Boolean,
        detectionConfidence: Float
    ): Float {
        var confidence = 0f
        
        // Frequency: 4-6 Hz is classic resting, 4.5-5.5 Hz is ideal
        confidence += when {
            frequency in 4.5f..5.5f -> 0.35f  // Ideal range
            frequency in 4.0f..6.0f -> 0.25f  // Acceptable range
            else -> 0.1f
        }
        
        // Resting state is essential for resting tremor
        if (isResting) confidence += 0.25f
        
        // High band ratio suggests clean tremor signal
        confidence += (bandRatio * 0.2f).coerceAtMost(0.2f)
        
        // Factor in detection confidence
        confidence += (detectionConfidence * 0.2f)
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Calculate confidence for action tremor classification
     */
    private fun calculateEssentialConfidence(
        frequency: Float,
        bandRatio: Float,
        isResting: Boolean,
        detectionConfidence: Float
    ): Float {
        var confidence = 0f
        
        // Frequency: 5-8 Hz is typical action tremor
        confidence += when {
            frequency in 5.0f..8.0f -> 0.3f   // Typical range
            frequency in 4.0f..12.0f -> 0.2f  // Broader acceptable range
            else -> 0.1f
        }
        
        // action tremor is typically action tremor (NOT at rest)
        if (!isResting) confidence += 0.25f
        
        // Band ratio contribution
        confidence += (bandRatio * 0.25f).coerceAtMost(0.25f)
        
        // Detection confidence
        confidence += (detectionConfidence * 0.2f)
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * Get a simple string label for the tremor type (for UI display)
     */
    fun getTypeLabel(result: ClassificationResult): String {
        return if (result.confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            result.primaryType.displayName
        } else {
            "${result.primaryType.displayName}?"
        }
    }
    
    /**
     * Get clinical interpretation text
     */
    fun getClinicalInterpretation(result: ClassificationResult): String {
        return when (result.primaryType) {
            TremorType.RESTING -> 
                "Resting tremor at ${String.format("%.1f", result.frequencyHz)}Hz. " +
                "This pattern is characteristic of resting tremor. " +
                "Track if it diminishes during voluntary movement."
            
            TremorType.ESSENTIAL ->
                "Action tremor at ${String.format("%.1f", result.frequencyHz)}Hz. " +
                "This pattern is consistent with action tremor. " +
                "Often bilateral and may worsen with stress or caffeine."
            
            TremorType.POSTURAL ->
                "Postural tremor detected while holding position. " +
                "Common in action tremor and enhanced physiological tremor."
            
            TremorType.KINETIC ->
                "Tremor detected during movement. " +
                "May indicate cerebellar involvement or action tremor component."
            
            TremorType.PHYSIOLOGICAL ->
                "High-frequency tremor (${String.format("%.1f", result.frequencyHz)}Hz) with low intensity. " +
                "Likely enhanced physiological tremor. Often related to fatigue, anxiety, or caffeine."
            
            TremorType.MIXED ->
                "Tremor with mixed characteristics. " +
                "Pattern doesn't fit a single tremor type clearly."
            
            TremorType.UNKNOWN ->
                "Unable to classify this tremor pattern. " +
                "May need more data or clearer signal."
        }
    }
}


