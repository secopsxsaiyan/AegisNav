# AegisNav Triad Security/Correctness Review
**Date:** 2026-03-12

## Critical Issues (3)

### CORO-CRASH-01 — FlockReportingCoordinator
- **File:** `flock/FlockReportingCoordinator.kt`
- **Issue:** CoroutineScope missing SupervisorJob. One uncaught exception in `handleSighting()` cancels the entire scope — flock detection goes permanently dark.
- **Fix:** `CoroutineScope(Dispatchers.IO + SupervisorJob())` + try/catch in collect block.

### CORO-CRASH-02 — PoliceReportingCoordinator
- **File:** `police/PoliceReportingCoordinator.kt`
- **Issue:** Same SupervisorJob omission. Also has NO `stop()` method — coroutine is never cancelled.
- **Fix:** Add SupervisorJob, add `stop()` method mirroring FlockReportingCoordinator.

### LEAK-SERVICE-03 — ScanService.onDestroy()
- **File:** `ScanService.kt`
- **Issue:** `onDestroy()` calls `flockReportingCoordinator.stop()` but never stops PoliceReportingCoordinator (method doesn't exist). Police coordinator leaks with live references to PoliceDetector, ReportsRepository, ThreatEventRepository, CorrelationEngine, P2PManager, AlertTtsManager.
- **Fix:** Add `stop()` to PoliceReportingCoordinator, call it in `onDestroy()`.

## Warnings (8)

| ID | File | Issue |
|---|---|---|
| P2P-NOGUARD-01 | P2PManager.kt | broadcast methods don't self-enforce offline mode check |
| P2P-NOGUARD-02 | CorrelationEngine.kt | correlate() calls broadcastReport() without offline guard |
| DB-MIGRATE-01 | AppDatabase.kt | No migrations for schema versions 1-5 |
| DB-SCHEMA-01 | AppDatabase.kt | exportSchema=false prevents migration tests |
| SQLCIPHER-KEY-01 | AegisNavApplication.kt | SQLCipher loaded but can't confirm SupportFactory used |
| FLAG-SECURE-01 | MainViewModel.kt | No FLAG_SECURE — app visible in switcher/screen capture |
| DB-INDEX-01 | AppDatabase.kt | Missing indices on timestamp/lat/lon for scan_logs, police_sightings, flock_sightings |
| CONCURRENCY-01 | PoliceReportingCoordinator.kt | lastPoliceTtsMs is plain HashMap with concurrent IO access (data race) |

## Clean Areas
- AppLog correctly gates all logs behind BuildConfig.DEBUG
- P2P offline checks correct at all current call sites
- ScanService permissions thorough with early-exit
- ScanService.onDestroy() cleans up scanScope, BLE, WiFi, LocationManager
- P2P node ID rotated every 24h
- CorrelationEngine ring buffer bounded at 500
- P2P offline queue bounded at 50
- Reconnect backoff capped at 60s
- Room migrations 6→17 complete
- UNIQUE indices on camera tables
