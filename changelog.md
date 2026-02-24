# TremorWatch Changelog

All notable changes to TremorWatch are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/).

---

## [0.2.0-rc1] — 2026-02-24

Master Fix Plan execution: 8 modules of correctness, efficiency, and cleanup fixes.

### Fixed

#### Module 1 — Data Safety (`WatchDataListenerService.kt`)
- **MF-02**: Phone sends persistence ACK to watch after durable DB save; watch only deletes local batch on ACK receipt.
- **MF-03**: Safe decompression — returns null on failure instead of crashing.
- **ES-02**: Chunk assembly uses `ByteArrayOutputStream` instead of manual byte array concatenation.
- **ES-05**: Replaced `runBlocking` with `withContext(IO)` in service coroutine scope.

#### Module 2 — Upload Service (`UploadService.kt`)
- **MF-01**: Replaced 5-second poll loop with 5-minute fallback interval + `stopSelf()` on idle.
- **ES-10**: `AtomicBoolean` guard prevents concurrent upload execution.

#### Module 3 — TremorService Battery & Lifecycle
- **MF-07**: Wake lock CPU-sleep fix — wake lock acquired with 15-minute timeout, re-acquired by watchdog.
- **ES-07**: Heartbeat skipped when monitoring is paused (saves battery).
- **ES-08**: Static `isRunning` companion flag tracks service lifecycle.

#### Module 4 — SQL Aggregation (`TremorDao.kt`)
- **MF-05**: Replaced `MAX(isWorn)/MAX(isCharging)` correlated subqueries with O(n) self-JOIN — fixes chart loading hang on large datasets.

#### Module 5 — Batch Path Mismatch
- **MF-06**: Shared `PENDING_BATCHES_DIR` constant in `Constants.kt`; `WatchDataSender` and `BatchRetryAlarmReceiver` both use it.

#### Module 6 — FFT Correctness
- **ES-04**: FFT analysis gated by `isFullSensorRate` flag — skipped when gyro duty-cycles to 10Hz low-power mode.
- **ES-01**: `meetsDynamicBandRatioThreshold` wired into `isTremor` decision (activity-aware tremor gate).

#### Module 7 — Quick Wins
- **ES-06**: Export directory changed from `cacheDir` to `filesDir/exports/` with 24-hour auto-cleanup of stale exports.
- **ES-09**: Chart stats computation (hourly buckets, severity) memoized with `remember` — eliminates recomposition stutter.
- **ES-08**: `ServiceWatchdogReceiver` checks `TremorService.isRunning` before calling `startForegroundService` — prevents redundant restarts.

#### Module 8 — Cleanup
- **ES-03**: Removed broken WorkManager periodic upload scheduler and dead `startPersistentUploadService()` from `MainActivity.kt`.
- **IG-09**: Deleted zombie `BatteryOptimizedConstants.kt` (zero imports).
- **IG-04**: `/notification` WearOS bridge path handled silently in `WatchDataListenerService` — eliminates log noise.

### Added
- `features.md` — feature status tracker with `[Verified]` markers for all touched areas.
- `CLAUDE.md` — AI agent router file.
- `agents.md` — operating rules, build commands, risk focus.

---

## [0.2.0-rc2] — 2026-02-24

Post-deployment log analysis revealed upload queue accumulation when InfluxDB is disabled.

### Fixed
- **Upload queue gate**: `WatchDataListenerService.processBatchData()` now checks `PhoneDataConfig.isInfluxDbEnabled()` before writing to `upload_queue/` and triggering `TremorUploadWorker`. When InfluxDB is off, batches are saved to local DB only — no queue files, no WorkManager jobs.
- **Stale queue cleanup**: Purged accumulated upload queue files from phone via adb.

---

## [0.2.0-rc3] — 2026-02-24

Three-tier tremor metrics: fixes inflated Tremor m/hr caused by post-update detector sensitivity increase.

### Changed
- **Three-tier tremor classification** (`StatsEngine.kt`): New `TremorTier` enum (Confirmed/Probable/Candidate) and `classifyTremorTier()` function gate samples by confidence, reliability, and exclusion flags.
  - Confirmed: conf ≥ 0.50, adjConf ≥ 0.40, not excluded, not unreliable.
  - Probable: conf ≥ 0.30, adjConf ≥ 0.20, not excluded, not unreliable.
  - Candidate: everything else with tremorCount > 0.
- **Quality-gated primary metrics** (`StatsRepository.kt`): `computePass1` now accumulates both quality-gated (Confirmed+Probable) and candidate (unfiltered) tremor time and bout counts in parallel. Primary `boutsPerHour`/`tremorMinutesPerHour` fields are now quality-gated.
- **Expanded TremorLoadResult** (`StatsModels.kt`): Added `boutsPerHourCandidate`, `tremorMinutesPerHourCandidate`, `totalBoutsCandidate`, `tremorMinutesCandidate` fields for diagnostic display.
- **UI update** (`StatsScreen.kt`): Candidate metrics shown as secondary debug line below primary stats. "About These Numbers" text updated to explain quality gating.

### Why
- Post-update detector changes (Feb 22) caused headline Tremor m/hr to spike from ~5 to ~28 m/hr — dominated by excluded/low-reliability detections.
- Quality-gated metrics restore clinically interpretable values while preserving the full signal for diagnostics.
- No changes to watch detector or stored `tremorCount` semantics.

---

## [Docs] � 2026-02-24

### Added
- `workingfolder/codex/training_proposal.md`: comprehensive Active Learning Training Mode technical proposal and Kotlin code draft.
  - Defines watch experimental-trigger flow (`TremorMonitoringEngine` + `TremorService`), user feedback loop, and phone label persistence plan.
  - Recommends prioritized auto-tuning targets from current parameter inventory.
  - Proposes three new personalization parameters (`minFrequencyStability`, `minHarmonicSupportRatio`, `minCrossSensorSupport`).
  - Includes explicit wiring plan so tuned thresholds (including `minBandRatio`) are consumed in `TremorFFT`/engine decision logic.

### Changed
- `todo.md`: appended a dated follow-up task (2026-03-01) for Phase 1 implementation of Active Learning Training Mode, with key file references and validation checkpoints.

---

## [Feature] Active Learning Training Module - 2026-02-25

### Added
- `shared/models/TrainingModels.kt`: shared data models (FeedbackLabel, TrainingState, TrainingSample, FeedbackFeatureSnapshot).
- `app/training/ShadowDetector.kt`: parallel FFT with relaxed thresholds for borderline event detection.
- `app/training/TrainingManager.kt`: orchestrator with v2.0 hardening (P1, P3, P4, P13, P14).
- `app/training/VibrationPromptManager.kt`: haptic feedback with P8 (appContext leak fix).
- `app/training/TrainingPromptActivity.kt`: watch prompt UI with P5-P7, P9 lifecycle hardening.
- `phone/data/TrainingEntities.kt`: Room entity + DAO with P12 (@Transaction).
- `phone/training/TrainingParameterOptimizer.kt`: optimization with P16-P19 math hardening.
- `phone/training/NightlyAutoTuner.kt`: WorkManager nightly worker with P10, P15.
- `phone/training/TrainingWorkerFactory.kt`: custom WorkerFactory for dependency injection (P10).

### Changed
- `shared/models/TremorDetectionConfig.kt`: +3 active learning params, schema v4.
- `shared/Constants.kt`: +3 training message paths.
- `app/TremorWatchApplication.kt`: implements TrainingAwareApplication interface.
- `app/WatchDataSender.kt`: +sendTrainingSample with P1/P2 hardening.
- `app/engine/TremorMonitoringEngine.kt`: +onTrainingSample callback for shadow detection.
- `app/AndroidManifest.xml`: +TrainingPromptActivity entry.
- `phone/WatchDataListenerService.kt`: +handleTrainingLabel handler (P11).
- `phone/database/TremorRoomDatabase.kt`: v7, MIGRATION_6_7, +trainingLabelDao.
- `phone/TremorWatchPhoneApp.kt`: Configuration.Provider with TrainingWorkerFactory.
- `phone/AndroidManifest.xml`: disabled default WorkManager initializer.

### Why
- Implements Active Learning Training Module (v2.0 hardened blueprint) with 19 patches from Red Team analysis.
- Enables future one-week personalization learning period via user feedback on borderline tremor events.
- All 19 files compile successfully; installed on watch + phone.
