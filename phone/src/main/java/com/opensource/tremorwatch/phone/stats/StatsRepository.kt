package com.opensource.tremorwatch.phone.stats

import android.content.Context
import com.opensource.tremorwatch.phone.analytics.ConfidenceCalibration
import com.opensource.tremorwatch.phone.analytics.ConfidenceCalibrationModel
import com.opensource.tremorwatch.phone.analytics.MedicationDoseResponse
import com.opensource.tremorwatch.phone.analytics.MedicationResponseModelConfig
import com.opensource.tremorwatch.phone.analytics.MedicationStateSpaceModel
import com.opensource.tremorwatch.phone.database.TremorRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt

class StatsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = TremorRoomDatabase.getDatabase(appContext).tremorDao()

    private data class Pass1Result(
        val tremorLoad: TremorLoadResult?,
        val stability: StabilityResult?,
        val baselineRestSeverity: Double?
    )

    private companion object {
        private const val PAGE_SIZE = 5_000
        private const val BASELINE_BIN_WIDTH = 0.01
        private const val BASELINE_MIN_SAMPLES = 10L

        // Tremor-load (day-to-day comparability) settings
        private const val MIN_WORN_MIN_TO_SHOW_TREMOR_LOAD = 30.0
        private const val LIMITED_WORN_MINUTES = 120.0
        private const val MIN_BOUT_TREMOR_SAMPLES = 10  // ~= 10 seconds at 1 Hz
        private const val MAX_GAP_SAMPLES_IN_BOUT = 2   // allow brief gaps within a bout
        private const val MED_RESPONSE_PRELOAD_MS = 90L * 60_000L
        private const val MED_RESPONSE_POSTLOAD_MS = 240L * 60_000L
    }

    data class MedicationDoseResponseRecord(
        val ingestionId: String,
        val ingestionTimestamp: Long,
        val source: String,
        val watchId: String?,
        val response: MedicationDoseResponse
    )

    suspend fun computeTodayStats(
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DailyStatsResult? = withContext(Dispatchers.Default) {
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        computeDailyStatsStreaming(date = date, endMsInclusive = nowMs, zoneId = zoneId)
    }

    suspend fun computeDailyStats(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DailyStatsResult? {
        return computeDailyStatsStreaming(
            date = date,
            endMsInclusive = endOfDayInclusive(date, zoneId),
            zoneId = zoneId
        )
    }

    suspend fun computeRecentDailyStats(
        days: Int,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DailyStatsResult> = withContext(Dispatchers.Default) {
        val safeDays = days.coerceIn(1, 60)
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()

        val results = mutableListOf<DailyStatsResult>()
        for (offset in 0 until safeDays) {
            val date = today.minusDays(offset.toLong())
            val endMs = if (date == today) nowMs else endOfDayInclusive(date, zoneId)
            val day = computeDailyStatsStreaming(date = date, endMsInclusive = endMs, zoneId = zoneId)
            if (day != null) {
                results += day
            }
        }

        results.sortedByDescending { it.date }
    }

    suspend fun computeMedicationResponsesSince(
        hoursBack: Int = 72,
        modelConfig: MedicationResponseModelConfig = MedicationResponseModelConfig()
    ): List<MedicationDoseResponseRecord> = withContext(Dispatchers.Default) {
        val cutoff = System.currentTimeMillis() - hoursBack.coerceAtLeast(1) * 60L * 60_000L
        val events = withContext(Dispatchers.IO) {
            dao.getMedicationIngestionsSince(cutoff)
        }
        if (events.isEmpty()) return@withContext emptyList()

        events.map { event ->
            val start = (event.timestamp - MED_RESPONSE_PRELOAD_MS).coerceAtLeast(0L)
            val end = event.timestamp + MED_RESPONSE_POSTLOAD_MS
            val samples = loadStatsSamplesInRange(start, end)
            val response = MedicationStateSpaceModel.inferDoseResponse(
                allSamples = samples,
                ingestionTimestamp = event.timestamp,
                modelConfig = modelConfig
            )
            MedicationDoseResponseRecord(
                ingestionId = event.id,
                ingestionTimestamp = event.timestamp,
                source = event.source,
                watchId = event.watchId,
                response = response
            )
        }.sortedByDescending { it.ingestionTimestamp }
    }

    suspend fun fitConfidenceCalibrationFromRatings(
        daysBack: Int = 14
    ): ConfidenceCalibrationModel = withContext(Dispatchers.Default) {
        val cutoff = System.currentTimeMillis() - daysBack.coerceAtLeast(1) * 24L * 60 * 60_000L
        val ratings = withContext(Dispatchers.IO) {
            dao.getRatingsAfter(cutoff)
        }
        if (ratings.isEmpty()) return@withContext ConfidenceCalibrationModel.None

        val rawScores = mutableListOf<Double>()
        val labels = mutableListOf<Boolean>()

        for (rating in ratings) {
            val label = rating.rating >= 3
            val windowStart = (rating.timestamp - 5 * 60_000L).coerceAtLeast(0L)
            val windowEnd = rating.timestamp + 5 * 60_000L
            val rows = withContext(Dispatchers.IO) {
                dao.getStatsRowsInRangeAfter(
                    startTime = windowStart,
                    endTime = windowEnd,
                    afterTimestamp = windowStart - 1,
                    limit = 400
                )
            }
            rows.forEach { row ->
                val score = (row.metadataJson?.let { parseMetadataSafely(it)?.optDoubleOrNull("calibratedConfidence") }
                    ?: row.confidence)
                    ?.coerceIn(0.0, 1.0)
                    ?: return@forEach
                rawScores += score
                labels += label
            }
        }

        if (rawScores.size < 30) return@withContext ConfidenceCalibrationModel.None

        val platt = ConfidenceCalibration.fitPlatt(rawScores, labels)
        val isotonic = ConfidenceCalibration.fitIsotonic(rawScores, labels)
        val plattBrier = ConfidenceCalibration.brierScore(rawScores, labels, platt)
        val isoBrier = ConfidenceCalibration.brierScore(rawScores, labels, isotonic)

        if (isoBrier.isFinite() && isoBrier < plattBrier) isotonic else platt
    }

    private suspend fun computeDailyStatsStreaming(
        date: LocalDate,
        endMsInclusive: Long,
        zoneId: ZoneId
    ): DailyStatsResult? = withContext(Dispatchers.Default) {
        val startMs = startOfDayMs(date, zoneId)

        val pass1 = computePass1(startMs, endMsInclusive)
        val baseline = pass1.baselineRestSeverity
        val stableThreshold = when {
            baseline != null && baseline.isFinite() && baseline > 0.0 -> maxOf(2.0, baseline * 1.2)
            else -> 2.0
        }

        val stableMinutes = computeStableMinutesPass(startMs, endMsInclusive, stableThreshold)

        DailyStatsResult(
            date = date,
            tremorLoad = pass1.tremorLoad,
            stability = pass1.stability,
            stableMinutes = stableMinutes,
            stableThreshold = stableThreshold,
            baselineRestSeverity = baseline
        )
    }

    private suspend fun computePass1(
        startMs: Long,
        endMsInclusive: Long
    ): Pass1Result = withContext(Dispatchers.Default) {
        val bins = ((10.0 / BASELINE_BIN_WIDTH).toInt() + 1).coerceAtLeast(2)
        val hist = LongArray(bins)
        var baselineCount = 0L

        var prev: StatsSample? = null
        var wornTimeSec = 0.0
        var eligibleTimeSec = 0.0
        var severityTimeSum = 0.0
        var gaps = 0

        // Quality-gated tremor load (Confirmed + Probable tiers).
        var tremorTimeSec = 0.0
        var boutCount = 0
        var inBout = false
        var boutTremorSamples = 0
        var boutGapSamples = 0

        // Candidate tremor load (all tremorCount > 0 — old unfiltered behavior, for debug).
        var tremorTimeSecCandidate = 0.0
        var boutCountCandidate = 0
        var inBoutCandidate = false
        var boutTremorSamplesCandidate = 0
        var boutGapSamplesCandidate = 0

        fun flushBout() {
            if (inBout && boutTremorSamples >= MIN_BOUT_TREMOR_SAMPLES) {
                boutCount++
            }
            inBout = false
            boutTremorSamples = 0
            boutGapSamples = 0
        }

        fun flushBoutCandidate() {
            if (inBoutCandidate && boutTremorSamplesCandidate >= MIN_BOUT_TREMOR_SAMPLES) {
                boutCountCandidate++
            }
            inBoutCandidate = false
            boutTremorSamplesCandidate = 0
            boutGapSamplesCandidate = 0
        }

        fun processBoutSample(s: StatsSample) {
            val worn = s.isWorn == true && s.isCharging != true
            if (!worn) {
                flushBout()
                flushBoutCandidate()
                return
            }

            // Quality-gated bout tracking (Confirmed + Probable)
            val tier = StatsEngine.classifyTremorTier(s)
            val isQualityTremor = tier == StatsEngine.TremorTier.CONFIRMED ||
                tier == StatsEngine.TremorTier.PROBABLE
            if (isQualityTremor) {
                if (!inBout) {
                    inBout = true
                    boutTremorSamples = 1
                    boutGapSamples = 0
                } else {
                    boutTremorSamples++
                    boutGapSamples = 0
                }
            } else if (inBout) {
                boutGapSamples++
                if (boutGapSamples > MAX_GAP_SAMPLES_IN_BOUT) {
                    flushBout()
                }
            }

            // Candidate bout tracking (all tremorCount > 0)
            val isCandidateTremor = tier != null  // any tier means tremorCount > 0
            if (isCandidateTremor) {
                if (!inBoutCandidate) {
                    inBoutCandidate = true
                    boutTremorSamplesCandidate = 1
                    boutGapSamplesCandidate = 0
                } else {
                    boutTremorSamplesCandidate++
                    boutGapSamplesCandidate = 0
                }
            } else if (inBoutCandidate) {
                boutGapSamplesCandidate++
                if (boutGapSamplesCandidate > MAX_GAP_SAMPLES_IN_BOUT) {
                    flushBoutCandidate()
                }
            }
        }

        forEachStatsSampleInRange(startMs, endMsInclusive) { cur ->
            // Baseline cohort: clinical gates + still + resting.
            if (StatsEngine.isValidForClinicalStats(cur) &&
                cur.activityTypeCanonical() == "STILL" &&
                cur.isRestingState == true
            ) {
                val sev = (cur.activityAdjustedSeverity ?: cur.severityRaw).coerceIn(0.0, 10.0)
                val idx = ((sev / BASELINE_BIN_WIDTH).toInt()).coerceIn(0, hist.lastIndex)
                hist[idx]++
                baselineCount++
            }

            val p = prev
            prev = cur
            if (p == null) {
                // First sample of the day (no dt yet), but it can start a bout.
                processBoutSample(cur)
                return@forEachStatsSampleInRange
            }

            val dtSec = (cur.timestamp - p.timestamp) / 1000.0
            if (dtSec <= 0.0) {
                // Keep bout detection independent of stability gating.
                processBoutSample(cur)
                return@forEachStatsSampleInRange
            }

            val isMonitoringBreak = dtSec > StatsEngine.MAX_SAMPLE_GAP_SEC_DEFAULT
            if (isMonitoringBreak) {
                gaps++
                // Do not bridge bouts or time accounting across large gaps.
                flushBout()
                flushBoutCandidate()
                processBoutSample(cur)
                return@forEachStatsSampleInRange
            }

            if (p.isWorn == true && p.isCharging != true &&
                cur.isWorn == true && cur.isCharging != true
            ) {
                wornTimeSec += dtSec

                // Quality-gated tremor time (Confirmed + Probable).
                val pTier = StatsEngine.classifyTremorTier(p)
                val pIsQuality = pTier == StatsEngine.TremorTier.CONFIRMED ||
                    pTier == StatsEngine.TremorTier.PROBABLE
                if (pIsQuality) {
                    tremorTimeSec += dtSec
                }

                // Candidate tremor time (all tremorCount > 0, old behavior).
                if (p.tremorCount > 0) {
                    tremorTimeSecCandidate += dtSec
                }
            }

            // Tremor-load bout detection must run on all worn samples, regardless of stability gatekeepers.
            processBoutSample(cur)

            val pEligible = StatsEngine.isValidForUserStats(p)
            if (!pEligible) return@forEachStatsSampleInRange
            val curEligible = StatsEngine.isValidForUserStats(cur)

            val sev0 = (p.activityAdjustedSeverity ?: p.severityRaw).coerceIn(0.0, 10.0)
            val avgSeverity = if (curEligible) {
                val sev1 = (cur.activityAdjustedSeverity ?: cur.severityRaw).coerceIn(0.0, 10.0)
                (sev0 + sev1) / 2.0
            } else {
                sev0
            }

            severityTimeSum += avgSeverity * dtSec
            eligibleTimeSec += dtSec
        }

        flushBout()
        flushBoutCandidate()

        val wornMinutes = wornTimeSec / 60.0
        val eligibleMinutes = eligibleTimeSec / 60.0

        val stability = if (eligibleMinutes < 30.0 || eligibleTimeSec <= 0.0) {
            StabilityResult(
                score = null,
                message = "Not enough data yet",
                wornMinutes = wornMinutes,
                eligibleMinutes = eligibleMinutes,
                dataGaps = gaps
            )
        } else {
            val burden = (severityTimeSum / (eligibleTimeSec * 10.0)).coerceIn(0.0, 1.0)
            val rawScore = 100.0 * (1.0 - burden.pow(1.0))
            val finalScore = rawScore.coerceIn(0.0, 100.0).roundToInt()

            val message = when {
                eligibleMinutes < 120.0 -> "Limited data - wear longer for accuracy"
                gaps > 0 -> "Data gaps detected - score may be less accurate"
                else -> null
            }

            StabilityResult(
                score = finalScore,
                message = message,
                wornMinutes = wornMinutes,
                eligibleMinutes = eligibleMinutes,
                dataGaps = gaps
            )
        }

        val baseline = if (baselineCount >= BASELINE_MIN_SAMPLES) {
            medianFromHistogram(hist, BASELINE_BIN_WIDTH)
        } else {
            null
        }

        val tremorLoad = run {
            val wornHours = wornTimeSec / 3600.0
            val tremorMinutesQuality = tremorTimeSec / 60.0
            val tremorMinutesCand = tremorTimeSecCandidate / 60.0

            if (wornMinutes < MIN_WORN_MIN_TO_SHOW_TREMOR_LOAD || wornHours <= 0.0) {
                TremorLoadResult(
                    boutsPerHour = null,
                    tremorMinutesPerHour = null,
                    totalBouts = boutCount,
                    tremorMinutes = tremorMinutesQuality,
                    boutsPerHourCandidate = null,
                    tremorMinutesPerHourCandidate = null,
                    totalBoutsCandidate = boutCountCandidate,
                    tremorMinutesCandidate = tremorMinutesCand,
                    wornMinutes = wornMinutes,
                    message = "Not enough data yet"
                )
            } else {
                val boutsPerHour = boutCount / wornHours
                val tremorMinutesPerHour = tremorMinutesQuality / wornHours
                val boutsPerHourCand = boutCountCandidate / wornHours
                val tremorMinutesPerHourCand = tremorMinutesCand / wornHours
                val message = when {
                    wornMinutes < LIMITED_WORN_MINUTES -> "Limited data - wear longer for accuracy"
                    gaps > 0 -> "Data gaps detected - metrics may be less accurate"
                    else -> null
                }

                TremorLoadResult(
                    boutsPerHour = boutsPerHour,
                    tremorMinutesPerHour = tremorMinutesPerHour,
                    totalBouts = boutCount,
                    tremorMinutes = tremorMinutesQuality,
                    boutsPerHourCandidate = boutsPerHourCand,
                    tremorMinutesPerHourCandidate = tremorMinutesPerHourCand,
                    totalBoutsCandidate = boutCountCandidate,
                    tremorMinutesCandidate = tremorMinutesCand,
                    wornMinutes = wornMinutes,
                    message = message
                )
            }
        }

        Pass1Result(
            tremorLoad = tremorLoad,
            stability = stability,
            baselineRestSeverity = baseline
        )
    }

    private suspend fun computeStableMinutesPass(
        startMs: Long,
        endMsInclusive: Long,
        stableThreshold: Double
    ): Double = withContext(Dispatchers.Default) {
        var prev: StatsSample? = null
        var stableTimeSec = 0.0

        forEachStatsSampleInRange(startMs, endMsInclusive) { cur ->
            val p = prev
            prev = cur
            if (p == null) return@forEachStatsSampleInRange

            val dtSec = (cur.timestamp - p.timestamp) / 1000.0
            if (dtSec <= 0.0) return@forEachStatsSampleInRange
            if (dtSec > StatsEngine.MAX_SAMPLE_GAP_SEC_DEFAULT) return@forEachStatsSampleInRange

            val pEligible = StatsEngine.isValidForUserStats(p)
            if (!pEligible) return@forEachStatsSampleInRange
            val curEligible = StatsEngine.isValidForUserStats(cur)

            val sev0 = (p.activityAdjustedSeverity ?: p.severityRaw).coerceIn(0.0, 10.0)
            val avgSeverity = if (curEligible) {
                val sev1 = (cur.activityAdjustedSeverity ?: cur.severityRaw).coerceIn(0.0, 10.0)
                (sev0 + sev1) / 2.0
            } else {
                sev0
            }

            if (avgSeverity < stableThreshold) {
                stableTimeSec += dtSec
            }
        }

        stableTimeSec / 60.0
    }

    private suspend fun forEachStatsSampleInRange(
        startMs: Long,
        endMsInclusive: Long,
        pageSize: Int = PAGE_SIZE,
        onSample: (StatsSample) -> Unit
    ) {
        var afterTs = startMs - 1
        while (true) {
            val batch = withContext(Dispatchers.IO) {
                dao.getStatsRowsInRangeAfter(
                    startTime = startMs,
                    endTime = endMsInclusive,
                    afterTimestamp = afterTs,
                    limit = pageSize
                )
            }
            if (batch.isEmpty()) break

            for (row in batch) {
                afterTs = row.timestamp
                onSample(row.toStatsSample())
            }

            if (batch.size < pageSize) break
        }
    }

    private suspend fun loadStatsSamplesInRange(
        startMs: Long,
        endMsInclusive: Long
    ): List<StatsSample> = withContext(Dispatchers.Default) {
        val out = mutableListOf<StatsSample>()
        forEachStatsSampleInRange(startMs, endMsInclusive) { sample ->
            out += sample
        }
        out
    }

    private fun com.opensource.tremorwatch.phone.database.TremorDao.StatsSampleRow.toStatsSample(): StatsSample {
        val meta = metadataJson?.let { parseMetadataSafely(it) }

        return StatsSample(
            timestamp = timestamp,
            severityRaw = severity,
            tremorCount = tremorCount,
            dominantFrequency = null,
            bandRatio = null,
            peakProminence = null,
            isWorn = isWorn,
            isCharging = isCharging,
            confidence = confidence,
            calibratedConfidence = meta?.optDoubleOrNull("calibratedConfidence"),
            rerankerProbability = meta?.optDoubleOrNull("rerankerProbability"),
            activityType = meta?.optStringOrNull("activityType"),
            activityConfidence = meta?.optDoubleOrNull("activityConfidence"),
            activityAgeMs = meta?.optLongOrNull("activityAgeMs"),
            stepsPerMinute = meta?.optIntOrNull("stepsPerMinute"),
            activityAdjustedSeverity = meta?.optDoubleOrNull("activityAdjustedSeverity"),
            activityAdjustedConfidence = meta?.optDoubleOrNull("activityAdjustedConfidence"),
            reliabilityScore = meta?.optDoubleOrNull("reliabilityScore"),
            tremorTypeConfidence = meta?.optDoubleOrNull("tremorTypeConfidence"),
            isRestingState = meta?.optBooleanOrNull("isRestingState"),
            isReliableMeasurement = meta?.optBooleanOrNull("isReliableMeasurement"),
            excludeFromAnalysis = meta?.optBooleanOrNull("excludeFromAnalysis")
        )
    }

    private fun medianFromHistogram(counts: LongArray, binWidth: Double): Double? {
        var total = 0L
        for (c in counts) total += c
        if (total <= 0L) return null

        fun valueAtPos(pos0: Long): Double {
            var cum = 0L
            for (i in counts.indices) {
                cum += counts[i]
                if (cum > pos0) return i * binWidth
            }
            return (counts.lastIndex) * binWidth
        }

        val mid1 = (total - 1L) / 2L
        val mid2 = total / 2L
        return (valueAtPos(mid1) + valueAtPos(mid2)) / 2.0
    }

    private fun startOfDayMs(date: LocalDate, zoneId: ZoneId): Long {
        return date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun endOfDayInclusive(date: LocalDate, zoneId: ZoneId): Long {
        return date
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
            .minus(1)
    }

    private fun parseMetadataSafely(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is String -> v
            else -> v?.toString()
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            else -> null
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val v = opt(key)
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
}
