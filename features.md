# TremorWatch Feature Status
Audit snapshot: 2026-02-23 (post-Module 8 cleanup)

Status legend:
- `[Verified]` Implemented and functionally coherent in current code.
- `[Partial]` Works, but has correctness, reliability, or efficiency gaps.
- `[Broken/Unfinished]` Critical path is incomplete or unsafe for reliable operation.

## 1) Tremor Detection
Status: `[Verified]`

Data flow:
`SensorManager (gyro/accel/step/off-body)` -> `TremorService` -> `TremorMonitoringEngine` -> `TremorFFT` + `SeverityCalculator` -> `TremorData` -> local pending batch files

Fixes applied:
- [ES-01] `meetsDynamicBandRatioThreshold` now wired into `isTremor` decision (activity-aware gate).
- [ES-04] FFT analysis gated by `isFullSensorRate` flag — skipped when gyro duty-cycles to 10Hz.
- FIXME remains: validate band-ratio constants against clinical data.

## 2) Watch-Sync (Watch -> Phone)
Status: `[Verified]`

Data flow:
`Watch pending batch file` -> `WatchDataSender` (`DataClient` primary, chunked `MessageClient` fallback, ChannelClient path) -> `WatchDataListenerService` -> immediate local DB write + upload queue -> `TremorUploadWorker` (on-demand via WorkManager)

Fixes applied:
- [DS-01] Hardcoded batch path constant in shared Constants.kt (watch + phone aligned).
- [IG-04] `/notification` bridge path handled silently (no more log noise).
- [ES-03] Broken periodic WorkManager scheduling removed; uploads are event-driven.

Remaining:
- Chunk assembly persistence format is fragile (manual byte encoding/decoding with partial-read risk).

## 3) Graphing
Status: `[Verified]`

Data flow:
`Room/SQLite samples` -> `loadLocalData` / repository aggregation -> `MainActivity` state -> `DataVisualization` Compose charts

Fixes applied:
- [ES-09] Stats computation (hourly buckets + severity stats) wrapped in `remember` — eliminates recomposition stutter.
- Data quality gates applied in UI-layer load path.

## 4) Stats
Status: `[Partial]`

Data flow:
`Room DAO (streamed rows/minute aggregates)` -> `StatsRepository` / stats engine -> Compose cards and trend displays

Key gaps:
- Aggregated query semantics use `MAX(isWorn/isCharging)` as "last state"; this can misclassify bucket state.
- Multiple stats pathways exist (repository + visualization helpers), increasing drift risk between displayed metrics.

## 5) Exports
Status: `[Verified]`

Data flow:
`Room/SQLite query` -> `ExportDialog` streaming CSV writer -> `filesDir/exports/` file -> `FileProvider` URI -> Android share sheet

Fixes applied:
- [ES-06] Export directory changed from `cacheDir`/`externalFilesDir` to `filesDir/exports/` — immune to OS auto-deletion.
- [ES-06] 24-hour auto-cleanup of stale exports on each new export.
- [IG-09] Legacy `BatteryOptimizedConstants.kt` zombie file deleted.

## 6) Service Lifecycle
Status: `[Verified]`

Fixes applied:
- [BF-02] `TremorService.isRunning` companion flag tracks lifecycle (set in `onStartCommand`, cleared in `onDestroy`).
- [ES-08] `ServiceWatchdogReceiver` checks `isRunning` before calling `startForegroundService` — prevents redundant restarts.
- [BF-01] Sticky notification channel and foreground service lifecycle hardened.

## Cross-Cutting Findings
- Legacy zombie code removed (WorkManager scheduler, BatteryOptimizedConstants, duplicate UploadService starter).
- Battery-sensitive areas remain active by design; any new feature must prove listener/service stop conditions.
- Settings are Compose-driven; XML audit found no disconnected menu/preference entries.
