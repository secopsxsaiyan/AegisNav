# Medium-TODO — Deferred to Next Session

Items from Code Review Round 2 (2026-03-20). All 🟡 Medium severity.

| # | File | Issue | Notes |
|---|------|-------|-------|
| 15 | MacCorrelationEngine | `macToGroup`/`groups` maps grow unbounded — no TTL eviction during long scan sessions | Add periodic pruning by lastSeen |
| 16 | MacCorrelationEngine | Non-atomic read-then-replace on `groups` map in `handleGlobalMacLeak()` | Use `compute()` or synchronized |
| 17 | SignalTriangulator | `currentResults` entries never pruned unless `pruneExpired()` is explicitly called | Wire to periodic timer |
| 18 | SettingsScreen | 1,153-line composable with inline business logic (DataStore reads, file deletion, WorkManager) | Extract to SettingsViewModel |
| 19 | ThreatHistoryScreen | 1,042-line composable doing JSON parsing inline during recomposition | Extract to UI model (same pattern as ThreatEventUiModel) |
| 20 | NavigationUi | 975-line composable — turn instruction rendering, HUD, input sheet all in one file | Split into focused composables |
| 21 | OfflineGeocoderRepository | Raw SQL string concatenation in FTS4 queries (not exploitable since offline, but fragile) | Use parameterized queries |
| 22 | DataDownloadManager | `readBlocking` calls on main thread | Convert to async (same pattern as ScanService fix) |
| 23 | CrossCorrelationEngine | `bleHistory`/`wifiHistory` maps grow unbounded with no eviction | Add TTL pruning |
