# P2P & Network Data Exfiltration Review — AegisNav
**Date:** 2026-03-11  
**Reviewer:** zerg (automated security subagent)  
**Source tree:** `app/src/main/kotlin/com/aegisnav/app/`

---

## Summary: FAIL — 4 issues found (2 HIGH, 1 MEDIUM, 1 LOW)

The app's offline-mode architecture is mostly sound with a privacy-first default (offline ON by default). All three weekly data sync workers are fully offline. No analytics, telemetry, or phone-home code was found. However, **two high-severity offline-mode enforcement gaps** allow location data to escape when offline mode is ON under specific conditions, and a **medium-severity** OSRM route-coordinate exposure exists in online mode.

---

## Network Call Inventory

### 1. P2P WebSocket — `P2PManager.kt`

| Field | Detail |
|-------|--------|
| **Destination** | `wss://relay.aegisnav.com/...` and `wss://relay.aegisnav.online/...` (or custom relay) |
| **Transport** | OkHttp WebSocket (persistent connection) |
| **Data on HELLO** | `{"type":"HELLO","nodeId":"<UUID16>","version":"1"}` |
| **Data on REPORT** | nodeId, report type/subtype, **lat/lon**, description, threatLevel, correlatedCount, hasTracker |
| **Data on flock_sighting** | nodeId, **lat/lon**, confidence, matchedSignals |
| **Data on police_sighting** | nodeId, **lat/lon**, confidence, matchedSignals, detectionCategory |
| **Gate mechanism** | `appPrefs.offlineMode` → `MainViewModel.setOfflineMode()` controls `p2pManager.connect()/disconnect()` |
| **Can fire automatically?** | YES — auto-reconnects in background via `CoroutineScope(Dispatchers.IO)` |
| **Risk level** | HIGH (location sent; see findings) |

**Note on nodeId:** Rotates every 24h via UUID.randomUUID(). Pseudonymous but allows relay-side correlation of all events within a 24-hour window.

---

### 2. OSRM Online Routing — `RoutingRepository.kt:calculateOnlineRoute()` (line ~290)

| Field | Detail |
|-------|--------|
| **Destination** | `https://router.project-osrm.org/route/v1/driving/<lon>,<lat>;<lon>,<lat>?...` |
| **Transport** | OkHttp HTTP GET |
| **Data sent** | Exact start + end coordinates (typically GPS location + user destination) |
| **User location included?** | YES — start point is typically current device GPS position |
| **Gate mechanism** | `appPrefs.offlineMode` checked in `NavigationViewModel.requestRoute()`. When offline=ON, uses local GraphHopper only. **BYPASS EXISTS — see Finding #1.** |
| **Can fire automatically?** | No — user must tap "Get Route" |
| **Risk level** | HIGH (due to ART-bypass; MEDIUM in normal use) |

---

### 3. Android System Geocoder — `NavigationUi.kt` (line ~172)

| Field | Detail |
|-------|--------|
| **Destination** | Google location services (system Geocoder, OS-managed) |
| **Transport** | Android `android.location.Geocoder` API |
| **Data sent** | Address search query string |
| **User location included?** | Implicitly — results filtered by proximity to current GPS; query string may imply location |
| **Gate mechanism** | `if (!offlineMode && android.location.Geocoder.isPresent())` — properly gated ✓ |
| **Can fire automatically?** | No — fires on user keypress (as-you-type, 350ms debounce, min 3 chars) |
| **Risk level** | LOW (properly gated; only fires in explicit online mode) |

---

### 4. TileDownloadWorker (formerly TilePreCacheWorker) — `TileDownloadWorker.kt`

| Field | Detail |
|-------|--------|
| **Destination** | User-configured HTTPS URL (tile server, geocoder DB, routing graph) |
| **Transport** | OkHttp HTTP GET |
| **Data sent** | No user data — plain GET requests for static file downloads |
| **User location included?** | NO |
| **Gate mechanism** | `NetworkType.CONNECTED` constraint; manually triggered by user in Settings. `enqueueTilePreCache()` in `AegisNavApplication` is now a no-op. |
| **Can fire automatically?** | YES once enqueued — WorkManager fires when connected. But enqueue is user-triggered. |
| **Risk level** | LOW |

---

### 5. MapTileManifest Remote Fetch — `MapTileManifest.kt`

| Field | Detail |
|-------|--------|
| **Destination** | `MapTileManifest.REMOTE_MANIFEST_URL` (currently `""` — empty) |
| **Transport** | OkHttp HTTP GET |
| **Data sent** | No user data — fetches JSON manifest file |
| **Gate mechanism** | `if (REMOTE_MANIFEST_URL.isNotBlank())` — currently safe as the URL is empty |
| **Can fire automatically?** | YES — called in `StateSelectionScreen` without any offline-mode check |
| **Risk level** | LOW (currently inert; future risk if URL is set without adding offline gate) |

---

### 6. Workers: AlprDataSyncWorker, RedLightDataSyncWorker, SpeedDataSyncWorker

| Field | Detail |
|-------|--------|
| **Destination** | None — reads bundled asset files only |
| **Transport** | None |
| **Data sent** | ZERO — fully offline, reads from `assets/*.geojson` |
| **Gate mechanism** | `NetworkType.NOT_REQUIRED` constraint explicitly set |
| **Can fire automatically?** | YES (weekly periodic WorkManager) — but makes no network calls |
| **Risk level** | NONE ✓ |

---

## P2P Data Flow

### What P2PManager.kt sends

**On connect (HELLO handshake, always):**
```json
{"type": "HELLO", "nodeId": "abc123def456ghi7", "version": "1"}
```
- `nodeId` is a randomly generated 16-char UUID prefix, persisted in SharedPreferences and rotated every 24 hours.
- No device info, no MAC address, no location data in the handshake itself.

**On `broadcastReport()` (user-submitted reports + auto-police reports):**
```json
{
  "type": "REPORT",
  "nodeId": "...",
  "hops": 0, "maxHops": 3,
  "timestamp": <ms>,
  "report": {
    "type": "POLICE|ALPR|TRACKER|...",
    "subtype": "...",
    "lat": <GPS_LATITUDE>,
    "lon": <GPS_LONGITUDE>,
    "description": "...",
    "threatLevel": "LOW|MEDIUM|HIGH"
  },
  "correlatedCount": <int>,
  "hasTracker": <bool>
}
```
**Contains user GPS location.** The `correlatedCount` and `hasTracker` fields also reveal BLE scan context at the time of the report.

**On `broadcastFlockSighting()` (auto-detected Flock Safety cameras):**
```json
{
  "type": "flock_sighting",
  "nodeId": "...",
  "hops": 0, "maxHops": 3,
  "timestamp": <ms>,
  "sighting": {
    "id": "...",
    "lat": <GPS_LATITUDE>,
    "lon": <GPS_LONGITUDE>,
    "confidence": 0.87,
    "matchedSignals": [...]
  }
}
```
**Contains user GPS location.** Auto-triggered by `FlockDetector`, no user confirmation required.

**On `broadcastPoliceSighting()` (auto-detected police equipment):**
```json
{
  "type": "police_sighting",
  "nodeId": "...",
  ...
  "sighting": {
    "lat": <GPS_LATITUDE>,
    "lon": <GPS_LONGITUDE>,
    "confidence": 0.73,
    "matchedSignals": [...],
    "detectionCategory": "AXON|CRADLEPOINT|..."
  }
}
```
**Contains user GPS location.** Auto-triggered by `PoliceDetector`.

### Offline queue behavior
- Reports queued in-memory (`ConcurrentLinkedQueue`, max 50 entries) when no relay is connected.
- Flushed on next successful connect via `flushQueue()`.
- Queue is NOT persisted to disk — cleared on app restart.
- **Implication:** If offline mode is toggled OFF after reports are queued, those queued messages will be transmitted. This is expected behavior in online mode, but could surprise a user who was in offline mode, queued reports (if the bugs below exist), then switched online.

---

## Offline Mode Enforcement

### Default state
`AppPreferencesRepository` defaults `offline_mode = true` — **privacy-first default. ✓**

### What offline mode should block (per documentation in `AppPreferencesRepository.kt`):
1. P2P relay connection — no report broadcasting or receiving
2. Navigation uses local GraphHopper only — no OSRM API calls
3. Map glyph/font requests fail silently

### Verified controls:
| Control | Works Correctly? |
|---------|-----------------|
| `MainViewModel.setOfflineMode(true)` calls `p2pManager.disconnect()` | ✓ YES |
| `MainViewModel.setOfflineMode(false)` calls `p2pManager.connect()` | ✓ YES |
| `MainViewModel.submitReport()` checks `appPrefs.offlineMode.value` before calling `correlationEngine.correlate()` | ✓ YES |
| `NavigationViewModel.requestRoute()` skips OSRM when `offlineMode == true` | ✓ YES (but **bypassed** — see Finding #1) |
| Android Geocoder skipped when `offlineMode == true` | ✓ YES |
| `AlprDataSyncWorker` never uses network | ✓ YES |
| `RedLightDataSyncWorker` never uses network | ✓ YES |
| `SpeedDataSyncWorker` never uses network | ✓ YES |
| `FlockReportingCoordinator` checks offline mode before P2P broadcast | ✗ **NO — see Finding #2** |
| `PoliceReportingCoordinator` checks offline mode before P2P broadcast | ✗ **NO — see Finding #3** |
| `CorrelationEngine.correlate()` checks offline mode internally | ✗ NO (but gated upstream in MainViewModel for user reports) |

---

## Findings

### 🔴 FINDING #1 — HIGH: ART-Incompatibility Bypass Sends GPS to OSRM Even in Offline Mode
**File:** `NavigationViewModel.kt` lines ~213–219  
**Severity:** HIGH

**Description:**  
When `routingRepository.offlineRoutingUnsupported == true` (set when GraphHopper throws `NoSuchMethodError` or `UnsatisfiedLinkError` due to device ART incompatibility), the routing logic unconditionally falls through to `calculateOnlineRoute()` **before** the offline mode check:

```kotlin
val result = when {
    forceOnline -> {  // ← fires FIRST, before offline mode check
        AppLog.i("NavigationViewModel", "Offline routing unsupported on this device - using OSRM")
        routingRepository.calculateOnlineRoute(from, to)  // ← sends GPS to OSRM!
    }
    !appPrefs.offlineMode.value -> {
        routingRepository.calculateOnlineRoute(from, to)
        ?: routingRepository.calculateRoute(from, to, profile)
    }
    else -> routingRepository.calculateRoute(from, to, profile)  // offline path
}
```

The `forceOnline` branch executes even when `appPrefs.offlineMode.value == true`. On affected devices, a user who set offline mode ON would unknowingly leak their route coordinates (start GPS + destination) to `router.project-osrm.org`.

**Impact:** User GPS location + destination transmitted to a third-party server against user's explicit offline-mode preference.

**Fix:**
```kotlin
val result = when {
    appPrefs.offlineMode.value && !forceOnline -> {
        // Strictly offline — use local graph only
        routingRepository.calculateRoute(from, to, profile)
    }
    forceOnline || !appPrefs.offlineMode.value -> {
        routingRepository.calculateOnlineRoute(from, to)
            ?: routingRepository.calculateRoute(from, to, profile)
    }
    else -> routingRepository.calculateRoute(from, to, profile)
}
```
Or more simply: add `if (appPrefs.offlineMode.value && forceOnline) { _errorMessage.value = "Offline routing not supported on this device"; return@launch }` before the when block.

---

### 🔴 FINDING #2 — HIGH: PoliceReportingCoordinator Ignores Offline Mode — Auto-Broadcasts Location to P2P
**File:** `PoliceReportingCoordinator.kt` lines ~104–113  
**Severity:** HIGH

**Description:**  
When a police/law enforcement device is auto-detected (confidence ≥ 0.5), `PoliceReportingCoordinator.handleSighting()` does two P2P-broadcasting actions without checking offline mode:

1. **Line ~105:** Calls `correlationEngine.correlate(report)` — which internally calls `p2pManager.broadcastReport()` unconditionally.
2. **Line ~111–113:** Calls `p2pManager.broadcastPoliceSighting(sighting)` gated only by `isP2PEnabled()` (relay URLs configured), **not** offline mode.

This means: if a user has relay URLs configured but offline mode ON, and a police BLE signature is detected nearby, **the app will silently broadcast the user's GPS location to the relay server** against their explicit offline preference.

This is triggered automatically by the background `ScanService` / `PoliceDetector` — no user action required.

```kotlin
// PoliceReportingCoordinator.kt ~L105 — NO offline mode check:
correlationEngine.correlate(report)  // → p2pManager.broadcastReport() inside

// ~L111 — only checks isP2PEnabled(), NOT offlineMode:
if (p2pManager.isP2PEnabled()) {
    try { p2pManager.broadcastPoliceSighting(sighting) }
```

**Impact:** User GPS location auto-transmitted in background without consent when offline mode is ON.

**Fix:**  
Inject `AppPreferencesRepository` into `PoliceReportingCoordinator` and add gate:
```kotlin
@Inject private val appPrefs: AppPreferencesRepository

private suspend fun handleSighting(sighting: PoliceSighting) {
    val isOffline = appPrefs.offlineMode.value

    // ... ThreatEvent insertion (local DB only — always fine) ...

    // Auto-report: only feed CorrelationEngine (which P2P-broadcasts) if online
    if (!isOffline) {
        correlationEngine.correlate(report)
    } else {
        // Offline: persist locally without P2P broadcast
        reportsRepository.insert(report)
    }

    // P2P sighting broadcast: gate on both offline mode AND relay config
    if (!isOffline && p2pManager.isP2PEnabled()) {
        p2pManager.broadcastPoliceSighting(sighting)
    }
}
```

---

### 🟡 FINDING #3 — MEDIUM: FlockReportingCoordinator Ignores Offline Mode — Auto-Broadcasts Location
**File:** `FlockReportingCoordinator.kt` line ~102  
**Severity:** MEDIUM

**Description:**  
Same pattern as Finding #2 but for Flock Safety camera auto-detection. The P2P broadcast is gated only on `isP2PEnabled()` (relay URLs set), not offline mode:

```kotlin
// Only checks relay URL presence, NOT offline mode:
if (p2pManager.isP2PEnabled()) {
    p2pManager.broadcastFlockSighting(sighting)  // sends GPS coordinates!
}
```

The auto-report to local DB (line ~91) is fine — that's local-only. But the P2P broadcast at line ~103 can fire in offline mode.

**Note:** FlockReportingCoordinator does NOT call `correlationEngine.correlate()`, so this is a single-vector issue (only the explicit `broadcastFlockSighting` call), making it slightly less severe than Finding #2.

**Impact:** User GPS location auto-transmitted in background when offline mode is ON and relay URLs are configured.

**Fix:** Same pattern — inject `AppPreferencesRepository` and add offline mode check:
```kotlin
if (!appPrefs.offlineMode.value && p2pManager.isP2PEnabled()) {
    p2pManager.broadcastFlockSighting(sighting)
}
```

---

### 🟢 FINDING #4 — LOW: MapTileManifest Has No Offline Mode Gate (Currently Inert)
**File:** `MapTileManifest.kt` line ~34  
**Severity:** LOW (currently inert — `REMOTE_MANIFEST_URL = ""`)

**Description:**  
`MapTileManifest.load()` will make an outbound HTTP GET request to `REMOTE_MANIFEST_URL` if that constant is non-empty. This method appears to be called from `StateSelectionScreen` (tile download UI) without any offline mode check.

Currently `REMOTE_MANIFEST_URL = ""` so no request fires. But as written, if this URL is populated for production, it would make a background network call without respecting offline mode.

**Impact:** Low — currently no call is made. Future risk if URL is set.

**Fix:** Add offline mode check at the call site in `StateSelectionScreen`, or modify `MapTileManifest.load()` to accept an `isOffline: Boolean` parameter and skip the remote fetch when true.

---

## Recommendations

### Critical (fix before release):

**R1.** Fix `NavigationViewModel.requestRoute()` to respect offline mode even when `offlineRoutingUnsupported == true`. When offline mode is ON and the local graph is unavailable (whether due to ART incompatibility or missing files), show an error message — do NOT silently fall through to OSRM. User should be explicitly informed that offline routing is not available on their device.

**R2.** Inject `AppPreferencesRepository` into `PoliceReportingCoordinator` and gate all P2P actions (both the `correlationEngine.correlate()` call and the explicit `broadcastPoliceSighting()` call) behind an `!appPrefs.offlineMode.value` check.

**R3.** Inject `AppPreferencesRepository` into `FlockReportingCoordinator` and gate the `broadcastFlockSighting()` call behind `!appPrefs.offlineMode.value`.

### Defense-in-depth (recommended):

**R4.** Add an offline mode check inside `P2PManager.broadcastReport()`, `broadcastFlockSighting()`, and `broadcastPoliceSighting()` as a last-resort safety net. Even though the primary gates should be upstream, a check here prevents future callers from accidentally bypassing the gate. Consider passing `AppPreferencesRepository` to P2PManager or checking connection state as a proxy.

**R5.** Add an offline mode check inside `CorrelationEngine.correlate()` before calling `p2pManager.broadcastReport()`. Currently it relies entirely on callers to not call `correlate()` in offline mode, which broke for `PoliceReportingCoordinator`.

**R6.** For `MapTileManifest.load()`, add offline mode awareness before populating `REMOTE_MANIFEST_URL` or at the call site.

**R7.** Consider reducing nodeId rotation period from 24h to a shorter window (e.g., 4–6 hours) to limit relay-side correlation of events from the same physical user.

**R8.** Consider adding a user-visible consent step before first P2P connection, clearly explaining that GPS coordinates of sightings and reports will be shared with relay servers and peers.

---

## No Issues Found (Clean)

- **AlprDataSyncWorker** — fully offline, no network calls ✓
- **RedLightDataSyncWorker** — fully offline, no network calls ✓
- **SpeedDataSyncWorker** — fully offline, no network calls ✓
- **TileDownloadWorker** — user-initiated only, no location data in requests ✓
- **PmTilesHttpInterceptor** — local interceptor, serves tiles from assets/files ✓
- **OfflineGeocoderRepository** — local SQLite only, zero network calls ✓
- **Android Geocoder (NavigationUi)** — properly gated behind `!offlineMode` ✓
- **AppPreferencesRepository** — default is `offline_mode = true` (privacy-first) ✓
- **MainViewModel.submitReport()** — correctly gates P2P via offline mode check ✓
- **No analytics, telemetry, or crash reporting** — No Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, or any third-party data collection SDK found ✓
- **No MAC addresses transmitted** — P2P payloads contain lat/lon and metadata but NOT raw MAC addresses from scanned devices ✓
- **No device identifiers** — no Android ID, IMEI, or hardware identifiers transmitted ✓

---

*Report generated: 2026-03-11 | Scope: full Kotlin source tree*
