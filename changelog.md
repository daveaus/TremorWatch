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
