# tracks.md — AegisNav

## Active Tracks

| Track | Type | Priority | Status | Notes |
|-------|------|----------|--------|-------|
| GitHub Public Launch | release | HIGH | blocked | Pending `gh auth login` (username: secopsxsaiyan), repo creation, clean push, data asset upload, APK signing + keystore |

## Completed Tracks

| Track | Completed | Notes |
|-------|-----------|-------|
| Code Review Round 2 Fixes (2026-03-20) | 2026-03-20 | 14-item deep audit — detection engines, UI, data, security. See breakdown below |
| Code Review Round 1 Fixes (2026-03-20) | 2026-03-20 | 14-item architectural audit — all addressed |
| Context-Driven Development Setup | 2026-03-20 | Established `context/` directory |
| AGP 9 + Gradle 9.4 Migration | 2026-03-18 | Full build stack upgrade |
| Security Audit v4 | 2026-03-18 | 0 critical, 0 high, 0 medium |
| All 50 States Data Build | 2026-03-18 | Maps + routing + geocoder for all states |
| Factory Reset Implementation | 2026-03-18 | 16 DB tables, 7 DataStores, 2 log files |
| Compose 1.10.5 + Material3 1.4.0 Upgrade | 2026-03-18 | Full device test pass |

### Code Review Round 2 Breakdown (2026-03-20)

| # | Item | Result |
|---|------|--------|
| 1 | FollowingDetector negative transition | ✅ Guard changed to `firstAtB <= lastAtA`, prevents false mobile-following alerts |
| 2 | OfficerCorrelationEngine deadlock | ✅ `synchronized` → `Mutex` + double-check pattern |
| 3 | PmTilesHttpInterceptor Int overflow | ✅ Long validation before Int cast, 2GB limit documented |
| 4 | TrackerDetectionEngine scope race | ✅ `@Volatile` + `@Synchronized` on `ensureScopeActive()` |
| 5 | TrackerDetectionEngine editBlocking | ✅ Replaced with suspend `edit` form |
| 6 | PoliceDetector CellState thread safety | ✅ `synchronized(state)` wrapping all mutations |
| 7 | PoliceDetector non-atomic pruning | ✅ Timer-based 60s pruning replaces inline size check |
| 8 | BeaconHistoryManager threshold skip | ✅ `>=` threshold + `gattRequestedMacs` ConcurrentHashSet guard |
| 9 | RssiDistanceEstimator volatile race | ✅ `AtomicReference<Double>` with CAS loop |
| 10 | ConvoyDetector user speed bug | ✅ Per-device speed from haversine(dist)/dt, user speed only for stationary gate |
| 11 | NavigationViewModel camera DB spam | ✅ Per-state cache in `_cachedCameras`, refreshed on state change + nav start. Wired to wizard/download/state selector |
| 12 | P2PManager runBlocking | ✅ Cached `_offlineMode` StateFlow, no more runBlocking |
| 13 | BackgroundScanWorker leaked scope | ✅ Structured `coroutineScope { }` replaces unstructured CoroutineScope |
| 14 | FollowingDetector unbounded inserts | ✅ Unique constraint `(mac, firstZoneId, secondZoneId)` + `INSERT OR IGNORE` + DB migration 28→29 |

### Deferred to Next Session (Medium-TODO)
See `context/medium-todo.md` — 9 items (unbounded caches, large composables, readBlocking stragglers)
