# TremorWatch Changelog

All notable changes to TremorWatch are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/).

---

## [Unreleased] — 2026-02-27 (log review hardening)

### Fixed — Log-review-driven improvements across watch and phone modules

- **L1 — Capability declaration bug** (`phone/res/values/wear.xml`, `phone/AndroidManifest.xml`):
  The phone app declared `tremor_watch_receiver` capability via `<meta-data>` in AndroidManifest,
  but the Wearable Data Layer API requires it in `res/values/wear.xml` as a `<string-array>`
  named `android_wear_capabilities`. Created the resource file and removed the ineffective
  meta-data tag. This was causing every `CapabilityClient` lookup to return zero capable nodes,
  forcing all data sends through the generic connected-nodes fallback path.

- **L2 — TrainingManager suppression log spam (~1/sec)** (`TrainingManager.kt`):
  Added per-reason throttling that batches suppression log lines to at most one per reason per
  60 seconds with an accumulated count. Previously each guardrail rejection logged individually,
  producing ~3,600 lines/hr of near-identical debug output. Added `suppressionCounts`,
  `lastSuppressionLogMs` maps and `logThrottledSuppression()` method.

- **L3 — Episode boundary oscillation (micro-episodes)** (`TremorDetectionConfig.kt`,
  `TremorMonitoringEngine.kt`):
  Added `minEpisodeDurationMs: Long = 5_000L` to `TremorDetectionConfig`. Engine now defers
  the START log until an episode survives past the minimum duration, and downgrades END logs for
  micro-episodes (< threshold) to debug level. Prevents rapid start/stop cycling near the
  detection threshold from inflating episode counts and log noise. Added `episodeStartLogged`
  deferred-log flag.

- **L4 — Training prompt fires with screen off** (`TrainingManager.kt`):
  `launchPromptActivity()` now checks `PowerManager.isInteractive` before setting
  `setFullScreenIntent`. When screen is off, posts a notification-only prompt (user taps when
  ready) instead of waking the screen with a full-screen activity — reducing battery drain and
  avoiding unexpected screen-on during sleep/pocket carry.

- **L5 — Watch manifest missing `<attribution>` tags** (`app/AndroidManifest.xml`,
  `app/res/values/strings.xml`):
  Added `<attribution>` declarations for `tremor_sensor` and `tremor_training` tags with
  string-resource labels. Suppresses `attributionTag-not-declared` warnings from AppOps
  on API 31+ devices.

### No Change Required

- **L6 — Consolidated storage rotation**: Verified cleanup exists at `TremorService:837-905`
  with configurable retention (48h watch-side, 168h phone-side). ~38MB/day growth with 48h
  retention yields ~76MB max — within budget. No change needed.

**Files changed**: `phone/src/main/AndroidManifest.xml`,
`phone/src/main/res/values/wear.xml` (new),
`app/src/main/AndroidManifest.xml`,
`app/src/main/res/values/strings.xml`,
`app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`,
`app/src/main/java/com/opensource/tremorwatch/engine/TremorMonitoringEngine.kt`,
`shared/src/main/java/com/opensource/tremorwatch/shared/models/TremorDetectionConfig.kt`

---

## [Unreleased] — 2026-02-27 (detection quality pass)

### Fixed — Additional detection and optimizer quality improvements

- **F1 — Dead optimizer features** (`TrainingParameterOptimizer.kt`):
  Removed `minFrequencyStability` and `minCrossSensorSupport` from `thresholdPriorityKeys`
  and from the `findOptimalThreshold()` call sites in `optimizeThresholds()`. Both features
  are always `0f` in labels collected via `onEngineSample()` (cross-sensor and multi-window
  metrics unavailable in the single-FFT path). Zero-variance inputs triggered the early-exit
  on every run — wasted cycles — and any non-zero starting threshold would silently block
  all samples from the simulator. Both config fields are preserved and left at their current
  values; they will be re-enabled once the label collection path can supply real values.

- **F2 — Near-boundary frequency probe hardcoded to 4.0Hz** (`TrainingManager.kt`):
  The `nearBoundary` check now derives its frequency floor from `activeConfig?.minFrequencyHz`
  (fallback 4.0f) instead of a hardcoded literal. After the optimizer raises `minFrequencyHz`,
  the probe zone was previously still capturing 4.0-to-new-floor samples that the production
  detector would always reject — biasing subsequent training cycles.

- **F3 — High-energy movement filter `totalPower > 50f` not configurable**
  (`TremorDetectionConfig.kt`, `TremorFFT.kt`):
  Added `highEnergyTotalPowerThreshold: Float = 50f` to `TremorDetectionConfig`. `TremorFFT`
  now reads `config.highEnergyTotalPowerThreshold` instead of the literal. Patients with high
  baseline muscle tone (dystonia, Parkinson's rigidity) can raise this value in their profile
  to prevent valid tremor detections being suppressed by the high-energy movement filter.
  Default 50f preserves existing behaviour; no migration required (JSON field with default).

- **F5 — Selection bias: optimizer never sees true-baseline negatives** (`TrainingManager.kt`):
  Added sparse baseline negative sampling to `onEngineSample()`. Every `BASELINE_SAMPLE_RATE`
  (50) calls where the signal is clearly non-tremor (`confidence < 25%` of threshold), the
  sample is eligible for a prompt. All existing guardrails (10-min cooldown, 4/hr, 20/day,
  quiet hours) still apply — this only widens the eligibility gate for that one sample in 50.
  Gives the optimizer genuine true-negatives from quiet periods rather than only ambiguous
  boundary-zone negatives, reducing systematic specificity overestimation. Added
  `baselineSampleCounter: AtomicInteger` and `BASELINE_SAMPLE_RATE = 50` constant.

**Files changed**: `phone/.../training/TrainingParameterOptimizer.kt`,
`app/.../training/TrainingManager.kt`,
`app/.../TremorFFT.kt`,
`shared/.../models/TremorDetectionConfig.kt`

---

## [Unreleased] — 2026-02-27

### Fixed — Auto-tune pipeline hardening (Reviewer #2 adjudication)

- **H1 — crossValidate() non-deterministic shuffle** (`TrainingParameterOptimizer.kt`):
  `optimizeBandGeometry()` now shuffles labels once before the band-search loop and
  passes the stable list to every `crossValidate()` call. Removed the per-call `.shuffled()`
  inside `crossValidate()`. Previously each of the ~200–300 candidate evaluations used a
  different random fold partition, adding ±0.05–0.10 J noise that swamped the 1e-4
  improvement threshold and made the search statistically meaningless.

- **H2 — simulateDetection() missing isTremor gates** (`TrainingParameterOptimizer.kt`):
  Added three gates that the real watch `TremorFFT.isTremor` path applies but the phone-side
  simulation was omitting: `entropyCompatible` (spectralEntropy ≤ 0.85 OR harmonicRatio ≥ 0.20),
  `dominantFrequency >= bandLow`, and `!isHighEnergyLow`. Ensures the optimizer's J score
  matches real detection behaviour so band and threshold tuning optimise the right objective.

- **H3 — Sub-2Hz power bins leaking into 2–4Hz bucket** (`TremorFFT.kt`):
  Already fixed in the main repo prior to this session (guard `if (freq >= 2f)` present at
  line 181). Confirmed and documented here for traceability.

- **H4 — TrainingManager.state not restored after process restart** (`TrainingManager.kt`):
  `hydratePersistedInsight()` now reads `KEY_ENGINE_STATE` from SharedPreferences and
  restores `state` to `TrainingState.WARMUP` when the persisted value was WARMUP or ACTIVE.
  Previously the live `state` field was always reset to OFF after process death, silently
  killing training prompt delivery until the user manually restarted.

- **H5 — Near-boundary thresholds hardcoded to defaults post-personalization** (`TrainingManager.kt`):
  `onEngineSample()` now derives `relaxedConfidenceThreshold` and `relaxedBandRatio` from the
  current `experimentalConfig` (falling back to defaults if null) instead of always using the
  hardcoded values `0.35f * 0.70f` and `0.04f * 0.70f`. After optimizer personalization, the
  boundary probe now tracks the tuned thresholds.

- **H6 — MIGRATION_7_8 unconditional DROP TABLE** (`TremorRoomDatabase.kt`):
  Already fixed in the main repo prior to this session (copy-on-rename path present for
  tables with existing rows). Confirmed and documented here for traceability.

- **M1 — buildStatusSnapshot() mutation side effects** (`TrainingManager.kt`):
  `buildStatusSnapshot()` is now a pure builder with no side effects. The threshold-crossing
  logic (setting `trainingCompletedTimeMs`, calling `appendLog`) has been moved to
  `refreshStatusSnapshot()`. Prevents `trainingCompletedTimeMs` from being incorrectly reset
  on every snapshot build when label count briefly dips below threshold.

- **M2 — onUserFeedback() unprotected SharedPreferences writes** (`TrainingManager.kt`):
  Added `@Synchronized` to `onUserFeedback()` so its counter increments, map removes, and
  SharedPreferences batch writes are serialised with `requestUserFeedback()` on the sensor thread.

- **M4 — Tasks.await() without timeout in TremorConfigManager** (`TremorConfigManager.kt`):
  All five `Tasks.await()` calls in `requestTrainingStateFromWatch()`,
  `sendTrainingModeToWatch()`, and the data sync path now pass a 10-second timeout.
  `TimeoutException` is caught and logged separately from generic exceptions. Added
  `java.util.concurrent.TimeUnit` and `TimeoutException` imports. Prevents indefinite
  coroutine blocking on BT stack hangs (non-trivial on WearOS).

- **M5 — evictStalePending() runs I/O inside @Synchronized sensor-thread block** (`TrainingManager.kt`):
  `evictStalePending()` now only collects stale sample IDs from the `ConcurrentHashMap`
  (fast, safe inside the lock), then posts the `onUserFeedback(IGNORE)` calls to the main
  looper via `Handler.post()`. Disk I/O (`saveLocalBackup`) and IPC (`dataSender`) no longer
  block the sensor thread during eviction.

**Files changed**: `phone/.../training/TrainingParameterOptimizer.kt`,
`app/.../training/TrainingManager.kt`,
`phone/.../config/TremorConfigManager.kt`

---

## [Unreleased] — 2026-02-25

### Fixed
- **Watchdog continuity gap after ES-08 guard**: ServiceWatchdogReceiver now always re-arms its next alarm when monitoring is enabled (5-minute health checks while running, 2-minute retry when service is down/timeouts/restart-throttled), so watchdog coverage does not silently stop after one ping.
- **Watchdog cadence hardening in TremorService**: replaced fixed 30-minute watchdog scheduling with state-aware intervals (5 minutes active, 15 minutes paused) and corrected logging to match real intervals. This reduces dead-time after LMK am_kill events while keeping battery impact bounded.
- **Stop-trigger hardening follow-up (2026-02-26):** pinpointed outage risk where a one-shot watchdog could stop re-arming after a healthy ping, and patched watch lifecycle recovery so LMK am_kill events are retried quickly without requiring manual app relaunch.`r`n- **Room migration crash on startup**: `MIGRATION_6_7` created `training_labels` columns with SQL `DEFAULT 0`/`DEFAULT ''` clauses, but `TrainingLabelEntity` had no `@ColumnInfo(defaultValue=...)` annotations, so Room expected `defaultValue='undefined'` on all columns — causing an `IllegalStateException: Migration didn't properly handle training_labels` on every app open.
- Added `MIGRATION_7_8` (DB v8) that drops and recreates `training_labels` without DEFAULT clauses, fixing all existing devices at v7.
- Fixed `MIGRATION_6_7` (for future fresh installs from v6) to also omit DEFAULT clauses.
- Added `@Entity(indices = [Index("timestamp"), Index("label")])` to `TrainingLabelEntity` to match indexes created by the migration, resolving the secondary index mismatch.

**Files changed**: `phone/.../data/TrainingEntities.kt`, `phone/.../database/TremorRoomDatabase.kt`

### Added
- **Training Insight UX (watch + phone)**:
  - Watch `TrainingManager` now persists lightweight training snapshots and recent prompt/label log entries, exposing event-driven status updates without background polling.
  - Watch home screen now shows a training progress card (when training mode is enabled) with state (`WARMUP`/`ACTIVE`/`PERSONALIZED`) and usable-label threshold progress (`YES_TREMOR + NO_ACTIVE`).
  - Watch settings now includes a training status section and recent training log lines.
  - Phone "Detection Settings" screen renamed to **"Algorithm & Training"**, with a new training section for:
    - enabling/disabling training mode from phone,
    - training progress counts and threshold status,
    - recent training labels log.
- **Phone-to-watch training toggle sync**:
  - Added training mode message send in `TremorConfigManager` using `MESSAGE_PATH_TRAINING_STATE`.
  - Added watch-side handling in `WatchMessageListenerService` to apply incoming training mode changes via `MonitoringState`.
- **Training DAO insight queries**:
  - Added ignored-label and total-label count queries for phone training insight UI.

**Files changed**:
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
- `app/src/main/java/MainActivity.kt`
- `app/src/main/java/com/opensource/tremorwatch/service/WatchMessageListenerService.kt`
- `phone/src/main/java/com/opensource/tremorwatch/phone/config/TremorConfigManager.kt`
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
- `phone/src/main/java/com/opensource/tremorwatch/phone/data/TrainingEntities.kt`

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

---

## [Fix] Active Learning Prompt Delivery (BAL-safe) - 2026-02-25

### Changed
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Replaced direct background `startActivity()` prompt launch with high-priority notification + `PendingIntent` (`setFullScreenIntent`) to satisfy Android 14/15 BAL restrictions.
  - Added prompt notification channel creation and per-sample notification IDs.
  - Added explicit guardrail suppression reason logging (`cooldown_active`, `quiet_hours`, `hourly_cap`, `daily_cap`).
  - Added prompt notification cancellation on user feedback/timeout path.
- `app/src/main/java/com/opensource/tremorwatch/service/TremorService.kt`
  - Added persisted training-mode restoration on service startup via `MonitoringState.isTrainingMode(...)`.
  - Added handling for `com.opensource.tremorwatch.TRAINING_MODE_CHANGED` broadcast in settings receiver.
  - Centralized training manager lifecycle in service (`syncTrainingModeState`), including config refresh and clean teardown in `onDestroy`.
  - Propagates latest phone config updates to training manager so shadow thresholds stay aligned.
- `app/src/main/java/MainActivity.kt`
  - Simplified Training Mode toggle to preference + broadcast only; removed UI-owned manager construction/destruction to avoid lifecycle drift.
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingPromptActivity.kt`
  - Updated `TrainingAwareApplication` contract to mutable `var trainingManager` so service can own runtime lifecycle safely.

### Why
- Watch logs showed repeated `Background activity launch blocked ... BAL_BLOCK` for `TrainingPromptActivity`, which prevented prompts from appearing even when training triggers fired.
- Service-owned training manager restores behavior after service/process restart and keeps toggle behavior consistent with persisted preference.

### Verification
- Build: `:app:assembleDebug` passed after changes.
- Runtime validation pending watch reconnection to ADB for post-install BAL-block regression check.

## [Fix] Training Prompt Watch UI Layout - 2026-02-25

### Changed
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingPromptActivity.kt`
  - Migrated prompt UI from a legacy `LinearLayout` row of small `Button`s to Wear Compose (`Scaffold` + `ScalingLazyColumn`) so controls follow the same interaction style as rating prompts.
  - Replaced cramped horizontal actions with large, full-width vertical `Chip`s for `Yes`, `No`, and `Ignore`.
  - Added a visible timeout indicator (`Auto-ignore in Ns`) bound to the existing prompt timer.
  - Removed corrupted button label characters and standardized prompt text.

### Why
- On round watch screens, the `Ignore` action was clipped at the right edge and difficult to tap.
- Larger full-width targets improve usability and reduce missed selections during quick responses.
- Matching the existing watch prompt style keeps interaction consistent across tremor workflows.

### Verification
- Build: `:app:assembleDebug` passed after UI changes.

## [Enhancement] Training Prompt Includes Detection Time - 2026-02-25

### Changed
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Prompt launch now passes event timestamp to `TrainingPromptActivity` (`EVENT_TIMESTAMP_MS`).
  - Training prompt notification body now includes the detected movement time (for example: `Detected movement at 10:48 AM`).
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingPromptActivity.kt`
  - Prompt UI now displays `Detected movement at <time>` above the yes/no/ignore actions.
  - Added local 12/24-hour time formatting based on system preference.

### Why
- Users may open the prompt minutes after the trigger; showing event time removes ambiguity about which movement episode is being labeled.

### Verification
- Build: `:app:assembleDebug` passed after timestamp context update.

## [Enhancement] Training Prompt Emoji + Undo Confirmation - 2026-02-25

### Changed
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingPromptActivity.kt`
  - Added emoji labels on action chips to match rating prompt style:
    - `?? Yes` for tremor present.
    - `?? No` for not tremor.
  - Added a rating-style confirmation/undo screen for `Yes` and `No` selections with a 5-second auto-save countdown.
  - Added `UNDO` action so accidental taps can be reversed before feedback is committed.
  - Preserved timeout semantics by pausing prompt timeout during confirmation and resuming it when user taps `UNDO`.

### Why
- Improves readability and consistency with watch rating UX.
- Reduces false labels from accidental taps during hand shake/tremor conditions.

### Verification
- Build: `:app:assembleDebug` passed after undo/emoji changes.

## [Enhancement] Training Insight Realtime Status (Watch Echo + Reactive Phone UI) - 2026-02-25

### Changed
- `app/src/main/java/WatchDataSender.kt`
  - Added `sendTrainingStateUpdate(...)` to publish lightweight watch runtime training snapshots (`/training/state`) to phone.
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Added throttled/deduplicated watch->phone status sync in `refreshStatusSnapshot()` using a fingerprint + minimum sync interval.
  - Synced fields include engine/UI state, usable/target labels, yes/no/ignored counts, prompt counters, and enough-labels flag.
- `phone/src/main/java/com/opensource/tremorwatch/phone/data/TrainingEntities.kt`
  - Added reactive DAO `Flow` queries for training counts and recent labels.
  - Added shared preference contract + snapshot model for watch-reported training state (`WatchTrainingStatePrefs`, `WatchTrainingStateSnapshot`).
- `phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt`
  - Added `/training/state` message handling and persistence of watch status snapshots to `training_state` preferences.
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Switched training counts/recent labels from manual refresh calls to reactive Room `Flow` collection.
  - Added watch-status listener via SharedPreferences change callbacks (no polling) and surfaced watch-confirmed state/update freshness in the Training card.
  - Removed manual training-refresh dependency from the UI flow.

### Why
- Users needed phone-side training progress to update live without pressing refresh.
- Phone UI needed a watch-confirmed runtime state source (not only inferred local preference + label counts).
- Implementation keeps battery-safe behavior by reusing existing data-layer events and reactive observers rather than introducing polling loops.

### Verification
- Build: `.\gradlew.bat :app:assembleDebug :phone:assembleDebug` passed.

## [Fix] Training State Mismatch (Phone showed OFF while Watch training active) - 2026-02-25

### Changed
- `shared/src/main/java/com/opensource/tremorwatch/shared/Constants.kt`
  - Added `MESSAGE_PATH_TRAINING_STATE_REQUEST` for explicit status pull.
- `phone/src/main/java/com/opensource/tremorwatch/phone/config/TremorConfigManager.kt`
  - Added `requestTrainingStateFromWatch()` to request runtime status on demand.
  - Updated training-mode sync to send config on the new path (`/training/config_update`) and legacy path (`/training/state`) for mixed-version compatibility.
- `app/src/main/java/com/opensource/tremorwatch/service/WatchMessageListenerService.kt`
  - Added handling for training state request messages and response with current runtime/persisted training snapshot.
  - Added support for both new and legacy training config update paths.
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Requests training state from watch when the screen opens and after successful training-mode sync.
  - Uses watch-confirmed `enabled` state for switch rendering when available.
  - Uses `SYNCING` placeholder state until watch status is received instead of prematurely showing `OFF`.

### Why
- Users could see `State: OFF` on phone even while watch training was active and prompting.
- Root cause was timing/fallback behavior: phone UI could render local preference before a watch runtime status snapshot arrived.

### Verification
- Build: `.\gradlew.bat :app:assembleDebug :phone:assembleDebug` passed.

## [Enhancement] Watch Home Training Card Always Visible (Start / In-Progress / Complete) - 2026-02-25

### Changed
- `app/src/main/java/MainActivity.kt`
  - Removed the old conditional training chip from the top section of `MainScreen`.
  - Added an always-visible `TrainingHomeCard` at the bottom of the main screen (last card block).
  - Added state-aware card behavior:
    - Not started: intro copy + `Start Training` action.
    - In progress: runtime duration, label progress, remaining labels, latest prompt/label line.
    - Complete: completion time plus summary results (`YES/NO` counts and total prompts).
    - Paused: current state + latest log line + resume action.
  - Added helper formatters for elapsed runtime and completion time display.

### Why
- Users need persistent visibility of training status from the watch front screen.
- The card now acts as a single entry point into training controls while communicating clear progress and completion outcomes.

### Verification
- Build: `./gradlew.bat :app:assembleDebug` passed.

Implementation note:
- In this pass the legacy top-of-screen training chip block is disabled (`if (false)`) and functionally replaced by the new always-visible bottom training card. Plan a cleanup pass to remove the dead block once file-encoding-safe refactor is done.

## [Enhancement] Phone Home Training Card + Watch Runtime Timestamps - 2026-02-25

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/MainActivity.kt`
  - Added an always-visible `TrainingHomeCard` to the phone main dashboard (bottom/last card).
  - Added state-aware card content:
    - Not started: intro + `Start Training` CTA.
    - In progress: runtime duration, label progress, remaining labels, and latest feedback line.
    - Complete: completion time and summary results.
  - Wired card CTA directly to `Algorithm & Training` screen.
  - Reused existing reactive data flow (Room label `Flow`s + watch-state SharedPreferences listener) and requested watch state once on screen load (no periodic training polling loop).
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Added persisted `trainingCompletedTimeMs` tracking when usable labels first reach threshold.
  - Included completion/start/last-prompt/last-feedback fields in runtime status snapshots and sync fingerprinting.
- `app/src/main/java/WatchDataSender.kt`
  - Extended `/training/state` payload with runtime timing metadata (`trainingStartTimeMs`, `trainingCompletedTimeMs`, `lastPromptTimeMs`, `lastFeedbackTimeMs`, `lastFeedbackLabel`).
- `app/src/main/java/com/opensource/tremorwatch/service/WatchMessageListenerService.kt`
  - Extended state-request response payload to include the same runtime timing metadata.
- `phone/src/main/java/com/opensource/tremorwatch/phone/data/TrainingEntities.kt`
  - Extended `WatchTrainingStateSnapshot` + preference schema with runtime timing metadata fields.
- `phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt`
  - Parsed and persisted extended runtime timing metadata from `/training/state` messages.

### Why
- Users needed training visibility directly on the phone front screen, not only inside settings.
- Users needed explicit progress/completion context (including runtime/completion timestamps) to know when training is "enough".
- Implementation preserves battery constraints by reusing existing data paths and avoiding new background polling.

### Verification
- Build: `./gradlew.bat :app:assembleDebug :phone:assembleDebug` passed.
- Install:
  - Phone: `adb -s 58151FDCQ006K7 install --user 0 -r phone\\build\\outputs\\apk\\debug\\phone-debug.apk` (Success)
  - Watch: `adb -s 10.58.43.73:41417 install -r app\\build\\outputs\\apk\\debug\\app-debug.apk` (Success)

## [Fix] Watch State Request Fallback Avoids OFF Flicker - 2026-02-25

### Changed
- `app/src/main/java/com/opensource/tremorwatch/service/WatchMessageListenerService.kt`
  - Hardened `/training/state_request` response path when runtime `trainingManager` snapshot is unavailable.
  - If `MonitoringState` reports training enabled but persisted snapshot is `OFF`, now infer non-OFF state (`WARMUP`/`ACTIVE`/`PERSONALIZED`) from persisted counters before replying.

### Why
- Prevents transient phone-side `State: OFF` regressions caused by stale persisted fallback snapshots during request/response races.

### Verification
- Build: `./gradlew.bat :app:assembleDebug :phone:assembleDebug` passed.
- Install retry pending device reconnect (adb currently has no connected devices).

## [Docs] Local TrainingInstructions Draft + Local Ignore Rule - 2026-02-25

### Changed
- `.gitignore`
  - Added `/TrainingInstructions.md` so the training instruction draft remains local-only for review.
- `TrainingInstructions.md` (local, untracked)
  - Added detailed user guide covering:
    - how training prompts work,
    - how to answer labels,
    - progress states and thresholds,
    - current limitations (no production auto-apply of tuned detector config yet),
    - recommendation for future `Trained` preset workflow.

### Why
- Provide a clear operator-facing explanation of training behavior and expectations before publishing.
- Ensure draft instructions are not pushed to GitHub until reviewed.

### Verification
- Confirmed `/TrainingInstructions.md` is ignored by git status.

## [Docs] Training Production-Readiness Handoff Pack - 2026-02-25

### Changed
- `workingfolder/activetraining/training_final_tasks.md`
  - Added a full external-review handoff document containing:
    - current Active Training context and implemented status,
    - concrete remaining production blockers,
    - proposed production-ready feature set,
    - full proposed code blocks for required file additions/changes (worker scheduling, config bridge wiring, trained profile apply/rollback flow, tune-state persistence, and phone UI status surfacing).

### Why
- User requested a single standalone file to send to external AIs that do not have repository access.
- The handoff must include both design context and copy-reviewable code proposals.

### Verification
- Verified file exists and is populated:
  - `workingfolder/activetraining/training_final_tasks.md`
  - Size: ~40 KB, includes context + code sections + acceptance criteria.

## [V2 Hardening] Active Training Backend Safety + Auto-Tune Orchestration - 2026-02-25

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/training/TrainingTuneStateStore.kt` (new)
  - Added persistent auto-tune status store with atomic lock-guarded `update(...)` path.
  - Added tune outcomes (`NEVER`, `SKIPPED`, `REJECTED`, `APPLIED`, `APPLY_FAILED`, `ROLLED_BACK`).
  - Added metadata fields for immediate enqueue debounce and rollback auto-apply cooldown.

- `phone/src/main/java/com/opensource/tremorwatch/phone/training/NightlyAutoTuner.kt`
  - Replaced old bridge with `TrainingConfigBridge` + `ApplyConfigResult` to use real config manager integration.
  - Split periodic/immediate work names and added explicit constraints/backoff.
  - Added immediate enqueue debounce and switched immediate policy to `ExistingWorkPolicy.KEEP`.
  - Added in-worker mutex serialization, deterministic-vs-transient failure handling, and meaningful J-delta gate before apply.
  - Switched minimum label threshold to shared constant (`TrainingThresholds.MIN_USABLE_LABELS_FOR_PERSONALIZATION`).

- `phone/src/main/java/com/opensource/tremorwatch/phone/training/TrainingWorkerFactory.kt`
  - Updated worker factory signature to inject `TrainingConfigBridge`.

- `phone/src/main/java/com/opensource/tremorwatch/phone/TremorWatchPhoneApp.kt`
  - Removed no-op training config placeholder.
  - Added lazy singleton-style initialization for DB, config manager, bridge, and WorkManager config.
  - Wired worker bridge to real `TremorConfigManager.applyAutoTunedConfig(...)` path.
  - Improved release logging to include stack traces for warn/error logs.

- `phone/src/main/java/com/opensource/tremorwatch/phone/config/TremorConfigManager.kt`
  - `setTrainingModeEnabled(...)` now schedules/cancels auto-tuner and mirrors mode to tune-state store.
  - Added `runTrainingAutoTuneNow(...)` helper to enqueue immediate run.
  - Added `applyAutoTunedConfig(...)` transactional path with rollback snapshot capture + sync status return.
  - Added rollback snapshot helpers and non-throwing `rollbackAutoTunedConfig(...)`.
  - Added rollback cooldown (`autoApplyBlockedUntil`) and explicit `ROLLED_BACK` tune-state recording.

- `phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt`
  - Hardened training-state payload parsing:
    - minimal schema check,
    - stale timestamp rejection,
    - non-negative coercion,
    - effective `hasEnoughLabels` consistency check from counts.
  - Added threshold-cross immediate auto-tune enqueue path with debounce/cooldown gating.

- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
- `phone/src/main/java/com/opensource/tremorwatch/phone/MainActivity.kt`
  - Hardened SharedPreferences listeners in Compose using remembered strong references to prevent listener GC/drop.

- `shared/src/main/java/com/opensource/tremorwatch/shared/models/TrainingModels.kt`
  - Added shared threshold constant object (`TrainingThresholds`) used by watch + phone to avoid drift.

- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Aligned minimum usable-label threshold with shared constant.

### Why
- Implements approved V2 backend safety priorities from 7-review synthesis:
  - race-condition hardening,
  - reliable one-time immediate runs,
  - rollback safety and anti-yo-yo guardrails,
  - production-safe worker retry behavior,
  - elimination of no-op tuning bridge.

### Verification
- Build passed:
  - `./gradlew.bat :app:assembleDebug :phone:assembleDebug`

## [Fix] Phone Training Toggle State Mismatch + Immediate Tune Queue on Enable - 2026-02-26

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Training toggle now reflects **phone runtime training mode** (source of truth for auto-tuner), not watch mirror state.
  - Added explicit phone/watch mode display to prevent false "ON" interpretation.
  - Added out-of-sync warning when phone and watch training modes differ.
  - Corrected local fallback state label from `SYNCING` to `OFF` when training is disabled.
  - Improved status text to indicate when threshold is already reached and auto-tune is queued.

- `phone/src/main/java/com/opensource/tremorwatch/phone/config/TremorConfigManager.kt`
  - When enabling training on phone, if watch snapshot already has enough labels, enqueue immediate auto-tune (`phone_training_enabled_threshold_ready`) so users do not wait for nightly run.

### Why
- User-facing issue: Algorithm & Training showed training as ON due to watch state while phone-side flag remained OFF, so no auto-tune work was scheduled/applied.
- This caused "training complete" with no `Trained` profile appearing.

### Verification
- Build passed:
  - `.\gradlew.bat :phone:assembleDebug`
- Phone install passed:
  - `adb -s 58151FDCQ006K7 install --user 0 -r phone\build\outputs\apk\debug\phone-debug.apk`
- On-device diagnosis before fix confirmed no tuner chain existed:
  - WorkManager `workname` had no `nightly_auto_tuner_*` rows.

## [Docs] Training UX Transparency Proposal + Full UI Patch Draft - 2026-02-26

### Changed
- `workingfolder/activetraining/ui_improvements.md` (new)
  - Added a full proposal for training UX transparency improvements across phone + watch.
  - Included complete patch-style code blocks for:
    - watch prompt wording (`Unsure / Ignore`),
    - watch home training card terminology (`Minimum labels reached` vs `complete`),
    - phone `Algorithm & Training` transparency UI (stages, auto-tune status, in-use indicator, help dialog),
    - phone home training card auto-tune visibility.

### Why
- User requested a single handoff document for external AI review that clearly defines UX changes and contains concrete code change proposals.
- Current wording can imply personalization is active before auto-tune is actually applied.

### Verification
- Confirmed file written to:
  - `workingfolder/activetraining/ui_improvements.md`

## [Docs] Training UX Transparency V2 (Reviewer-Triaged) - 2026-02-26

### Changed
- `workingfolder/activetraining/ui_improvements_v2.md` (new)
  - Added decision-triage matrix for two external reviews (GLM5 + Opus 4.6):
    - `Yes - Do it`
    - `Yes - Do it with modifications`
    - `No - Don't do it`
  - Included rationale for each decision and final V2 UX direction.
  - Added updated patch plan with concrete code diffs, including:
    - removal of numeric stage model,
    - status wording simplification,
    - `Unsure / Ignore` prompt copy,
    - `trainedProfileActive` truth-sync on manual config changes,
    - safer J-value persistence handling.

### Why
- User requested a V2 proposal that explicitly adjudicates conflicting reviewer feedback before implementation.
- Needed a shareable artifact for additional AI review without repository access.

### Verification
- Confirmed file written to:
  - `workingfolder/activetraining/ui_improvements_v2.md`

## [UX V2 Implemented] Training Transparency + Prompt Wording - 2026-02-26

### Changed
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingPromptActivity.kt`
  - Updated prompt third action copy from `Ignore` to `Unsure / Ignore`.
  - Updated selection confirmation copy for ignored responses (`Marked Unsure`, `Sample skipped`).

- `app/src/main/java/MainActivity.kt`
  - Updated watch home training card terminology:
    - `Training: Complete` -> `Training: Minimum Reached`
    - `Results ready for personalized tracking` -> `Ready for phone personalization`
    - Added explicit note that personalization happens on phone.

- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Added tune-state-backed transparency section:
    - `Collection: ...`
    - `Using personalized profile: YES/NO`
    - `Personalization: ...` status derived from tune outcomes
    - `Last personalization run` and `Last tune used ... labels`
  - Added `How training works` dialog with plain-language flow.
  - Updated label row copy for ignored samples to `Unsure/Ignore`.
  - Removed user-facing J-score line from primary UI.

- `phone/src/main/java/com/opensource/tremorwatch/phone/MainActivity.kt`
  - Wired home training card to `TrainingTuneStateStore`.
  - Added clear personalization status + explicit `Using personalized profile: YES/NO`.
  - Updated card wording to `Minimum labels reached`.

- `phone/src/main/java/com/opensource/tremorwatch/phone/config/TremorConfigManager.kt`
  - `setActiveConfig(...)` now keeps `trainedProfileActive` in tune-state store in sync with currently active profile name.

- `phone/src/main/java/com/opensource/tremorwatch/phone/training/TrainingTuneStateStore.kt`
  - Replaced `beforeJ/afterJ` clamping `[-1,1]` with finite-value guards to avoid silent distortion.

### Why
- Implements approved `ui_improvements_v2.md` direction:
  - clearer separation between label collection and personalization activation,
  - removal of misleading completion wording,
  - user-understandable statuses without leaking optimization internals.

### Verification
- Build passed:
  - `.\gradlew.bat :app:assembleDebug :phone:assembleDebug`
- Install passed:
  - `adb -s 58151FDCQ006K7 install --user 0 -r phone\build\outputs\apk\debug\phone-debug.apk`
  - `adb -s 10.58.43.73:43811 install -r app\build\outputs\apk\debug\app-debug.apk`

## [UX V2.1] Training Progress Chart + Clear Guide Copy - 2026-02-26

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Added a visual training progress section in `Algorithm & Training`:
    - overall usable-label progress bar (`usable / target`),
    - explicit remaining-label guidance,
    - prompt label mix bars for `Yes`, `No`, and `Unsure/Ignore`.
  - Updated help CTA copy to `How training works (clear guide)`.
  - Expanded help dialog to explicitly explain:
    - what counts as usable labels,
    - when auto-tune starts,
    - exact conditions that mean personalization is active,
    - what to do if status remains `Needs more data`.

### Why
- User feedback requested a clearer explanation of training progress and when data is actually used.
- Existing text-only status was correct but not visually obvious enough for non-technical users.

### Verification
- Build passed:
  - `.\gradlew.bat :phone:assembleDebug`
- Install passed:
  - `adb -s 58151FDCQ006K7 install --user 0 -r phone\build\outputs\apk\debug\phone-debug.apk`

## [Training TODO Completion] Manual Tune Controls + Trained Preset Surface - 2026-02-26

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Added manual training actions in `Algorithm & Training`:
    - `Run Auto-Tune Now` (guarded by mode/label/cooldown state)
    - `Rollback` with confirmation dialog
  - Added richer tune transparency metadata:
    - last run reason,
    - tune score before/after,
    - rollback cooldown visibility (`Auto-apply pause active until ...`).
  - Added `Trained` quick preset chip (enabled when trained profile is available).

- `phone/src/main/java/com/opensource/tremorwatch/phone/MainActivity.kt`
  - Phone home training card now surfaces:
    - last auto-tune run timestamp,
    - rollback pause status when active.

- `app/src/main/java/MainActivity.kt`
  - Removed disabled legacy `if (false)` training block from watch `MainScreen` (dead UI path cleanup).

### Why
- Completes remaining functional training-system TODO scope:
  - expose actionable manual controls,
  - make rollback state explicit,
  - surface trained preset directly in quick presets,
  - remove legacy dead training UI block.

### Verification
- Build passed:
  - `.\gradlew.bat :app:assembleDebug :phone:assembleDebug`

## [Training TODO Completion 2] Manual Controls + Legacy Cleanup Shipped - 2026-02-26

### Changed
- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Added training action controls in `Algorithm & Training`:
    - `Run Auto-Tune Now` (guarded by mode/minimum-label/cooldown state)
    - `Rollback` with confirmation dialog
  - Added advanced tune-state visibility:
    - last run reason,
    - J score before/after,
    - rollback cooldown message when auto-apply is temporarily blocked.
  - Added `Trained` quick-preset chip (enabled when trained profile exists).

- `phone/src/main/java/com/opensource/tremorwatch/phone/MainActivity.kt`
  - Phone home training card now shows:
    - last auto-tune run timestamp,
    - rollback pause status when active.

- `app/src/main/java/MainActivity.kt`
  - Removed the disabled legacy `if (false)` training block from watch main screen.

### Why
- Completes the remaining training-system TODO code work by exposing actionable controls and removing dead UI paths.

### Verification
- Build passed:
  - `.\gradlew.bat :app:assembleDebug :phone:assembleDebug`
- Install passed:
  - `adb -s 58151FDCQ006K7 install --user 0 -r phone\build\outputs\apk\debug\phone-debug.apk`
  - `adb -s 10.58.43.73:43811 install -r app\build\outputs\apk\debug\app-debug.apk`

## [Planning] Expanded Auto-Tune Design Draft (Bands + Additional Params) - 2026-02-26

### Changed
- `workingfolder/activetraining/expladedautotune.md`
  - Added a review draft plan for expanded auto-tune scope.
  - Documented current behavior and why trained profile currently does not auto-adjust all algorithm sliders.
  - Proposed phased architecture and concrete code-change snippets across:
    - shared training models,
    - watch FFT/training sample propagation,
    - phone Room schema + migration,
    - optimizer expansion,
    - auto-tune transparency UI.

### Why
- User requested a clear, review-ready plan before implementation to evaluate how auto-tune can safely expand to tune additional settings (including frequency band sliders).

## [Planning] Expanded Auto-Tune V2 (Opus-Incorporated) - 2026-02-26

### Changed
- `workingfolder/activetraining/expladedautotune_v2.md`
  - Added V2 planning document with explicit triage of Opus feedback:
    - `YES / YES with modification / NO for now` decisions per finding.
  - Replaced vague band-tuning concept with explicit algorithm design:
    - proportional bucket overlap weighting,
    - discrete constrained band search,
    - sequential tuning phases (bands first, thresholds next),
    - max 3 parameter changes per run with priority ordering.
  - Updated migration strategy from table recreation to additive `ALTER TABLE ADD COLUMN` approach.
  - Deferred `severityFloor` tuning and model-composition refactor as intentional non-goals for this cycle.

### Why
- User requested a V2 plan that incorporates external review feedback and clearly explains which recommendations are accepted, modified, or intentionally deferred.

## [Training] Expanded Auto-Tune V2 Implementation - 2026-02-26

### Changed
- `shared/src/main/java/com/opensource/tremorwatch/shared/models/TrainingModels.kt`
  - Added fixed-band power fields (`2-4` through `12-14 Hz`) to training snapshot/sample payloads.

- `app/src/main/java/com/opensource/tremorwatch/TremorFFT.kt`
  - Extended `FFTResult` with fixed-band power fields.
  - Added single-pass fixed-band accumulation over spectrum bins (battery/CPU-friendly).

- `app/src/main/java/com/opensource/tremorwatch/training/ShadowDetector.kt`
- `app/src/main/java/com/opensource/tremorwatch/training/TrainingManager.kt`
  - Propagated fixed-band powers into watch-side training feature snapshots and labeled samples.

- `phone/src/main/java/com/opensource/tremorwatch/phone/data/TrainingEntities.kt`
  - Added fixed-band power columns to `TrainingLabelEntity` with Room default values.

- `phone/src/main/java/com/opensource/tremorwatch/phone/database/TremorRoomDatabase.kt`
  - Bumped DB version `8 -> 9`.
  - Added additive `MIGRATION_8_9` using `ALTER TABLE ADD COLUMN` (no destructive table recreation).

- `phone/src/main/java/com/opensource/tremorwatch/phone/WatchDataListenerService.kt`
  - Persisted fixed-band fields from incoming watch `TrainingSample` into Room.

- `shared/src/main/java/com/opensource/tremorwatch/shared/models/TremorDetectionConfig.kt`
  - Added invariant checks:
    - `minFrequencyHz <= restingBandLowHz`
    - `minFrequencyHz <= activeBandLowHz`

- `phone/src/main/java/com/opensource/tremorwatch/phone/training/TrainingParameterOptimizer.kt`
  - Replaced optimizer with V2 flow:
    - proportional bucket-overlap power reconstruction,
    - explicit band-geometry search,
    - sequential phase strategy (`BANDS_ONLY`, `THRESHOLDS_ONLY`),
    - class/activity balance gates,
    - max 3 parameter changes per run,
    - deterministic changed-parameter diff output.

- `phone/src/main/java/com/opensource/tremorwatch/phone/training/TrainingTuneStateStore.kt`
- `phone/src/main/java/com/opensource/tremorwatch/phone/training/NightlyAutoTuner.kt`
  - Added persisted tune metadata:
    - last changed-params summary,
    - last tune phase.
  - Persisted phase + change summary for applied/rejected/skipped outcomes.

- `phone/src/main/java/com/opensource/tremorwatch/phone/ui/TremorDetectionSettingsScreen.kt`
  - Surfaced `Last run phase` and `Last auto-tune changes`.
  - Guarded frequency slider updates so lowering band lows also lowers `minFrequencyHz` to preserve config invariants.

### Why
- Implements the approved V2 expanded auto-tune direction with Opus-critical fixes:
  - simulation fidelity at band edges,
  - non-destructive migration strategy,
  - clearer tuning transparency,
  - safer coupled-parameter optimization behavior.

### Verification
- Build passed:
  - `.\gradlew.bat :app:assembleDebug :phone:assembleDebug`



## [Unreleased] - 2026-02-27 (watchdog restart-limit hotfix)

### Fixed
- ServiceWatchdogReceiver now records restart-window attempts only when recovering a stopped service or when foreground-service ping fails.
- Healthy-running keepalive pings no longer increment restart counters.
- Watchdog re-arm cadence now stays at 5 minutes for healthy service pings and 2 minutes for recovery/failure paths.
- Prevents false repeated "Tap to restart Monitoring - Service restart limit reached" notifications while monitoring is active.

**Files changed**:
- app/src/main/java/com/opensource/tremorwatch/receivers/ServiceWatchdogReceiver.kt
