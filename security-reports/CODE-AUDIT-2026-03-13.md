I now have a comprehensive picture. Let me compile the final audit report.

---

# 🔍 AegisNav Android Codebase Audit Report

---

## CATEGORY 1: Unused Variables & Dead Code

---

**Finding 1.1**
- **File**: `app/src/main/kotlin/com/aegisnav/app/ScanService.kt`
- **Line(s)**: 968–995
- **Category**: Unused Variables & Dead Code
- **Severity**: MEDIUM
- **Description**: `private fun sendAlert(msg: String)` is declared but **never called** anywhere in the codebase. It builds a generic notification with no specialised alert routing. All real alerts are dispatched via `onTrackerAlert()`, `onMacLeakAlert()`, `onConvoyAlert()`, etc.
- **Recommendation**: Delete `sendAlert()`. All notification dispatch paths are already covered by the more specific private functions. If a generic fallback is intentional, wire it up or document why it exists.

---

**Finding 1.2**
- **File**: `app/src/main/kotlin/com/aegisnav/app/MacPersistenceScorer.kt`
- **Line(s)**: 1–end
- **Category**: Unused Variables & Dead Code
- **Severity**: LOW
- **Description**: `MacPersistenceScorer` (object, legacy) is entirely replaced by `EnhancedPersistenceScorer`. It is only referenced by two **test** files and by a KDoc comment in `EnhancedPersistenceScorer.kt`. No main source code calls it.
- **Recommendation**: Migrate the two test files (`MacPersistenceScorerTest.kt`, `MacPersistenceScoringFixTest.kt`) to test `EnhancedPersistenceScorer` instead, then delete `MacPersistenceScorer.kt`. Keeping dead production code invites confusion and accidental future use.

---

**Finding 1.3**
- **File**: `app/src/main/kotlin/com/aegisnav/app/MainViewModel.kt`
- **Line(s)**: 77–79
- **Category**: Unused Variables & Dead Code
- **Severity**: LOW
- **Description**: `fun currentTriangulationResults()` is declared `fun` (not `override`) and is never called from any Composable, Activity, or Fragment. No caller exists in the codebase.
- **Recommendation**: If triangulation results are no longer surfaced in the UI, delete the function. If planned for a future screen, add a `// TODO` comment.

---

**Finding 1.4**
- **File**: `app/src/main/kotlin/com/aegisnav/app/MainViewModel.kt`
- **Line(s)**: 339, 343
- **Category**: Unused Variables & Dead Code
- **Severity**: LOW
- **Description**: `connectP2P()` and `disconnectP2P()` are declared but never invoked — `MainActivity.kt:793` explicitly comments "P2P disabled for alpha — connectP2P() not called". The P2P UI (`P2PStatusDot`) is also commented out.
- **Recommendation**: Either delete these functions and the related P2P UI dead code, or document them clearly as "alpha-gated" with a feature flag. Dead functions that touch networking are misleading.

---

**Finding 1.5**
- **File**: `app/src/main/kotlin/com/aegisnav/app/routing/RoutingRepository.kt`
- **Line(s)**: 342–344, 525–527
- **Category**: Unused Variables & Dead Code
- **Severity**: LOW
- **Description**: The OSRM base URL `"https://router.project-osrm.org/route/v1/driving/"` is **copy-pasted verbatim** in two functions: `calculateOnlineRoute()` and `calculateOnlineAlternatives()`. There is no constant defined for it.
- **Recommendation**: Extract to a `companion object` constant, e.g. `private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1/driving/"`.

---

## CATEGORY 2: Dangling / Unconnected Functions

*(Covered above in Category 1 — `sendAlert`, `connectP2P/disconnectP2P`, `currentTriangulationResults` are dangling/unconnected)*

---

**Finding 2.1**
- **File**: `app/src/main/kotlin/com/aegisnav/app/MainViewModel.kt`
- **Line(s)**: 339, 343
- **Category**: Dangling / Unconnected Functions
- **Severity**: MEDIUM
- **Description**: `connectP2P()` and `disconnectP2P()` are the only public entry points to control the P2P relay connection lifecycle, but they are never called. This means the P2P relay is **silently never connected** in the alpha build. If a user enables P2P in settings, nothing connects.
- **Recommendation**: Wire these up via a `LaunchedEffect` in `MainActivity` when P2P is re-enabled for release, or clearly disable the toggle in UI settings.

---

## CATEGORY 3: Secrets & Credential Leaks

---

**Finding 3.1**
- **File**: `app/src/main/kotlin/com/aegisnav/app/data/DataDownloadManager.kt`
- **Line(s)**: 11
- **Category**: Secrets & Credential Leaks
- **Severity**: MEDIUM
- **Description**: The `DOWNLOAD_BASE_URL` is hardcoded as `"https://github.com/secopsxsaiyan/AegisNav/releases/download/data-v1/"`, embedding the developer's personal GitHub username in the production binary. While not a secret/key, this permanently ties the app to a personal account and leaks developer identity to users inspecting the APK.
- **Recommendation**: Either move to an organisation-owned repo, use an indirection via a `BuildConfig.DOWNLOAD_BASE_URL` field set at build time, or use a vanity domain that doesn't expose the username.

---

**Finding 3.2**
- **File**: `app/src/main/kotlin/com/aegisnav/app/p2p/P2PSetupScreen.kt`
- **Line(s)**: 15–16
- **Category**: Secrets & Credential Leaks
- **Severity**: LOW
- **Description**: `DEFAULT_RELAY_URL` and `DEFAULT_RELAY_URL_2` are declared as top-level `const val` in a **UI screen file** (`P2PSetupScreen.kt`). These production relay endpoints are architectural constants that should live in a `NetworkConstants.kt` or as `BuildConfig` fields, not in a Composable screen file (which makes them harder to manage or swap per build variant).
- **Recommendation**: Move these constants to a dedicated `app/src/main/kotlin/com/aegisnav/app/p2p/P2PConstants.kt` or inject via `BuildConfig`.

---

**No hardcoded API keys, auth tokens, or passwords were found** in any Kotlin source, XML resource, manifest, or Gradle build file. The `DatabaseKeyManager` uses Android Keystore-backed AES-256-GCM correctly.

---

## CATEGORY 4: Data Leaks & Privacy Issues

---

**Finding 4.1**
- **File**: `app/src/main/kotlin/com/aegisnav/app/tracker/TrackerTypeClassifier.kt`
- **Line(s)**: 241, 246, 253, 260, 269
- **Category**: Data Leaks & Privacy Issues
- **Severity**: HIGH
- **Description**: Inside `readGattInfo()`, the GATT callbacks log the raw **device MAC address** via `AppLog.d()`:
  ```kotlin
  AppLog.d(TAG, "GATT connected to ${device.address}, discovering services")
  AppLog.d(TAG, "GATT disconnected from ${device.address}")
  AppLog.d(TAG, "GATT readInfo ${device.address}: $result")
  ```
  While `AppLog` is gated behind `BuildConfig.DEBUG`, these `device.address` values are BLE MAC addresses — persistent device identifiers and sensitive PII. **If someone ever wraps `AppLog.d` to forward logs to a remote service (e.g., Crashlytics custom logging)**, or if `BuildConfig.DEBUG = true` slips into a sideloaded build, raw MACs go to logcat and potentially off-device.
- **Recommendation**: Hash or redact the MAC in log messages: `device.address.take(8) + "XX:XX:XX"` or use a consistent pseudonym. Follow the same pattern as tracker alerts that avoid raw MACs in high-verbosity logs.

---

**Finding 4.2**
- **File**: `app/src/main/kotlin/com/aegisnav/app/ScanService.kt`
- **Line(s)**: 654
- **Category**: Data Leaks & Privacy Issues
- **Severity**: HIGH
- **Description**: 
  ```kotlin
  AppLog.i("ScanService", "TTS: Possible high risk tracker (mac=${alert.mac})")
  ```
  This logs a raw tracker MAC address at **INFO** level (not just DEBUG). `AppLog.i()` is still gated behind `BuildConfig.DEBUG`, but INFO-level logs are more likely to be forwarded to remote logging systems than DEBUG. The MAC here is the victim's detected tracker — a persistent identifier.
- **Recommendation**: Either gate this specific log at DEBUG level, or redact the MAC: `mac=${alert.mac.take(8)}…`.

---

**Finding 4.3**
- **File**: `app/src/main/kotlin/com/aegisnav/app/police/PoliceReportingCoordinator.kt`
- **Line(s)**: 148–150
- **Category**: Data Leaks & Privacy Issues
- **Severity**: MEDIUM
- **Description**: A `PendingIntent` for `MainActivity` is built with raw GPS coordinates embedded:
  ```kotlin
  putExtra("police_lat", sighting.lat)
  putExtra("police_lon", sighting.lon)
  ```
  While the Intent targets `MainActivity` explicitly (not an implicit broadcast), if this PendingIntent were ever leaked or inspected via `PendingIntent.getIntent()` on rooted devices, precise GPS coordinates become accessible.
- **Recommendation**: Consider passing only a `sighting_id` and resolving coordinates from the DB at open time. Similarly applies to `FlockReportingCoordinator.kt:113–115`.

---

**Finding 4.4**
- **File**: `app/src/main/kotlin/com/aegisnav/app/scan/BackgroundScanWorker.kt`
- **Line(s)**: 215
- **Category**: Data Leaks & Privacy Issues
- **Severity**: MEDIUM
- **Description**: 
  ```kotlin
  putExtra("tracker_mac", alert.mac)
  ```
  Raw MAC address embedded in a `PendingIntent` payload targeting `MainActivity`. Same concern as 4.3 — explicit Intent to a known component, but the MAC is a sensitive hardware identifier.
- **Recommendation**: Pass only `alert.alertId` and look up MAC from DB on the receiving end.

---

**Finding 4.5 — SharedPreferences storing P2P node ID in plaintext**
- **File**: `app/src/main/kotlin/com/aegisnav/app/p2p/P2PManager.kt`
- **Line(s)**: 28, 307–315
- **Category**: Data Leaks & Privacy Issues
- **Severity**: LOW
- **Description**: The P2P node ID (a persistent pseudonymous device identifier used in relay communications) is stored in plaintext `SharedPreferences` under `"p2p_prefs"`. While the node ID rotates every 24 hours (`NODE_ID_ROTATION_MS`), it is nonetheless a persistent device identifier at rest. On non-FDE (full-disk encryption) devices or with ADB root access, this file is readable.
- **Recommendation**: Store the node ID in `EncryptedSharedPreferences` (Jetpack Security) or regenerate it purely in memory without persistence (if persistence across restarts is not required).

---

**No raw `android.util.Log.d/i/v/w/e()` calls exist in main source** — all logging goes through `AppLog`, which is correctly gated behind `BuildConfig.DEBUG`. ✅

---

## CATEGORY 5: Misconfigurations

---

**Finding 5.1**
- **File**: `app/src/main/kotlin/com/aegisnav/app/data/db/AppDatabase.kt`
- **Line(s)**: 54
- **Category**: Misconfigurations
- **Severity**: MEDIUM
- **Description**: `exportSchema = false` disables Room's schema export. This means **no schema JSON files are generated**, so migration tests (`MigrationTestHelper`) cannot verify that the actual schema matches the expected one. With 16 non-destructive migrations (versions 6–22), a subtle migration bug would only surface at runtime on user devices.
- **Recommendation**: Set `exportSchema = true` and add `schemaLocation` to the KSP arguments in `build.gradle.kts`:
  ```kotlin
  ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```
  Then add the generated schema files to version control and write `MigrationTestHelper`-based migration tests.

---

**Finding 5.2**
- **File**: `app/build.gradle.kts`
- **Line(s)**: 52–60 (dependencies block)
- **Category**: Misconfigurations
- **Severity**: MEDIUM
- **Description**: Several Compose dependencies are **pinned to outdated versions** while `compileSdk = 35` (Android 15):
  - `androidx.compose.ui:ui:1.5.4` → current stable is 1.7.x
  - `androidx.compose.material3:material3:1.1.2` → current stable is 1.3.x
  - `androidx.activity:activity-compose:1.8.2` → current stable is 1.9.x
  - `lifecycle-runtime-ktx:2.8.3` appears while other lifecycle deps also at 2.8.3 but `lifecycle-viewmodel-compose` is at the same — these are OK, but some are slightly behind.
  These old Compose versions have known bugs and are incompatible with some Android 15 predictive back gesture features.
- **Recommendation**: Run `./gradlew dependencyUpdates` (Gradle versions plugin) and update Compose BOM to the latest stable. Align all `androidx.compose.*` versions via the Compose BOM rather than specifying each individually.

---

**Finding 5.3**
- **File**: `app/build.gradle.kts`
- **Line(s)**: 53
- **Category**: Misconfigurations
- **Severity**: LOW
- **Description**: `androidx.appcompat:appcompat:1.6.1` is included as a dependency, but the project is 100% Jetpack Compose with no XML layouts and `minSdk = 31`. AppCompat is not needed (Compose Activity handles all theming), and it brings in a large transitive dependency chain unnecessarily.
- **Recommendation**: Remove `implementation("androidx.appcompat:appcompat:1.6.1")` unless a specific AppCompat feature is being used (check usages — none found in source).

---

**Finding 5.4**
- **File**: `app/proguard-rules.pro`
- **Line(s)**: All `-keep class com.graphhopper.** { *; }` rules
- **Category**: Misconfigurations
- **Severity**: LOW
- **Description**: The ProGuard rules keep **all** GraphHopper classes with wildcards including `{ *; }` (all members). This prevents R8 from optimising or shrinking any GraphHopper code, which significantly inflates the release APK. GraphHopper is a large library.
- **Recommendation**: Profile which GraphHopper classes are actually used at runtime, then narrow the `-keep` rules to only those classes. At minimum, replace `{ *; }` with more targeted member retention for entry points only.

---

**Finding 5.5**
- **File**: `app/src/main/AndroidManifest.xml`
- **Line(s)**: 48–49
- **Category**: Misconfigurations
- **Severity**: LOW
- **Description**: `RECEIVE_BOOT_COMPLETED` is declared, but **no `<receiver>` for `BOOT_COMPLETED` is registered in the manifest**. WorkManager does include its own BootReceiver (merged via its library manifest), so this permission is not wrong — but it is worth noting that no explicit app-level boot handling exists, meaning if WorkManager's enqueued work needs to re-register after a boot, it relies entirely on WorkManager's internal `RescheduleReceiver`. This is normally fine, but worth confirming that `BackgroundScanWorker` periodic work survives reboot properly.
- **Recommendation**: Add a comment in the manifest explaining the permission is used by WorkManager's merged BootReceiver, to avoid future confusion about why there's no matching `<receiver>` element.

---

**No cleartext HTTP traffic is permitted** — `network_security_config.xml` correctly sets `cleartextTrafficPermitted="false"` globally with system CA trust anchors only. ✅  
**No `android:debuggable="true"` flag in manifest** — Android build tools manage this correctly. ✅  
**No exported components without permission guards** — `ScanService exported="false"`, `BackgroundScanReceiver exported="false"`, `BluetoothStateMonitor exported="false"`. ✅  
**No missing `android:exported`** on declared components — all components have explicit `exported` attributes. ✅

---

## CATEGORY 6: Other Code Quality Issues

---

**Finding 6.1 — Haversine function copy-pasted 11 times**
- **Files**: `ScanService.kt`, `TrackerDetectionEngine.kt`, `BeaconHistoryManager.kt`, `EnhancedPersistenceScorer.kt`, `FollowingDetector.kt`, `ConvoyDetector.kt`, `MacCorrelationEngine.kt`, `CorrelationEngine.kt`, `NavigationUi.kt`, `NavigationViewModel.kt`, `MacPersistenceScorer.kt`
- **Line(s)**: Various (~10 lines each)
- **Category**: Other Code Quality Issues
- **Severity**: MEDIUM
- **Description**: The Haversine distance formula is copy-pasted verbatim into **11 different files** across the codebase. This is a DRY violation — if the formula ever needed correction (e.g., an Earth radius constant change), 11 files would need updating.
- **Recommendation**: Extract to a single utility function in `app/src/main/kotlin/com/aegisnav/app/util/GeoUtils.kt`:
  ```kotlin
  fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double { ... }
  ```
  Replace all 11 call sites.

---

**Finding 6.2 — P2PManager `updateState()` has a read-modify-write race condition**
- **File**: `app/src/main/kotlin/com/aegisnav/app/p2p/P2PManager.kt`
- **Line(s)**: 292–300
- **Category**: Other Code Quality Issues
- **Severity**: HIGH
- **Description**: `updateState()` is called from WebSocket callbacks (which fire on OkHttp's background thread pool). The pattern:
  ```kotlin
  val newMap = _states.value.toMutableMap().also { it[url] = state }
  _states.value = newMap
  ```
  is a non-atomic **read-modify-write** on `MutableStateFlow`. Two simultaneous WebSocket callbacks (e.g., both relay1 and relay2 connecting at the same time) can each read the same stale `_states.value`, and one update will **silently overwrite** the other.
- **Recommendation**: Use `MutableStateFlow.update { it.toMutableMap().also { m -> m[url] = state } }` which is atomic, or wrap calls to `updateState()` with a `@GuardedBy` mutex:
  ```kotlin
  private val stateLock = Mutex()
  private suspend fun updateState(url: String, state: ConnectionState) {
      stateLock.withLock {
          _states.update { it.toMutableMap().also { m -> m[url] = state } }
          ...
      }
  }
  ```

---

**Finding 6.3 — TrackerDetectionEngine `engineJob`/`scope` vars are not `@Volatile`**
- **File**: `app/src/main/kotlin/com/aegisnav/app/tracker/TrackerDetectionEngine.kt`
- **Line(s)**: 75–76, 338–341
- **Category**: Other Code Quality Issues
- **Severity**: MEDIUM
- **Description**: The `engineJob` and `scope` fields are mutable `var` in a `@Singleton`. `ensureScopeActive()` reads and conditionally reassigns both from whatever thread calls it (could be the UI thread, IO thread, or WorkManager thread). Without `@Volatile`, a thread reassigning `scope` may not be visible to another thread reading `scope.launch { ... }`.
- **Recommendation**: Annotate both fields `@Volatile`, or better — protect the recreation with a `synchronized(this)` block:
  ```kotlin
  @Volatile private var engineJob = SupervisorJob()
  @Volatile private var scope = CoroutineScope(Dispatchers.IO + engineJob)
  ```

---

**Finding 6.4 — `exportSchema = false` blocks migration testing (see 5.1 above)**

---

**Finding 6.5 — SharedPreferences key strings scattered as magic strings across 12+ files**
- **Files**: `ScanService.kt`, `MainActivity.kt`, `MainViewModel.kt`, `P2PManager.kt`, `AegisNavApplication.kt`, `PrivacyWizardScreen.kt`, `SettingsScreen.kt`, `NavigationViewModel.kt`, `AppPreferencesRepository.kt`, `BluetoothStateMonitor.kt`, `USStates.kt`, workers, etc.
- **Category**: Other Code Quality Issues
- **Severity**: LOW
- **Description**: Prefs file names (`"an_prefs"`, `"app_prefs"`, `"alpr_prefs"`, `"tile_prefs"`, `"state_prefs"`, `"nav_prefs"`, `"p2p_prefs"`, `"tracker_engine_prefs"`, `"redlight_prefs"`, `"speed_prefs"`, `"an_secure"`) are repeated as inline magic strings across 12+ source files. A typo in any one of them creates a silent second prefs file with the wrong name.
- **Recommendation**: Centralise all prefs file names and key constants in a single `PrefsKeys.kt` object. Also note that `P2PManager.connect()` at line 96 opens `"app_prefs"` directly instead of using `AppPreferencesRepository`, bypassing the repository layer.

---

**Finding 6.6 — DEFAULT_RELAY_URL constants declared in a UI Composable file**
- **File**: `app/src/main/kotlin/com/aegisnav/app/p2p/P2PSetupScreen.kt`
- **Line(s)**: 15–16
- **Category**: Other Code Quality Issues
- **Severity**: LOW
- **Description**: `DEFAULT_RELAY_URL` and `DEFAULT_RELAY_URL_2` are top-level package-level constants sitting inside a Composable screen file. `P2PManager.kt` imports them from this UI file, which inverts the dependency direction (data layer depending on UI layer).
- **Recommendation**: Move these constants to `P2PManager.kt` companion object or a new `P2PConstants.kt`, and have `P2PSetupScreen.kt` import from there.

---

**Finding 6.7 — `onSkip!!()` unsafe dereferencing in DataDownloadScreen**
- **File**: `app/src/main/kotlin/com/aegisnav/app/ui/DataDownloadScreen.kt`
- **Line(s)**: 91, 253
- **Category**: Other Code Quality Issues
- **Severity**: MEDIUM
- **Description**:
  ```kotlin
  TextButton(onClick = { onSkip!!() }) { Text("Skip") }
  ```
  `onSkip` is a nullable lambda parameter (`onSkip: (() -> Unit)?`). The `!!` operator will throw `NullPointerException` if the button is shown while `onSkip` is null. This is a crash risk.
- **Recommendation**: Replace with a safe call pattern:
  ```kotlin
  TextButton(onClick = { onSkip?.invoke() }, enabled = onSkip != null)
  ```

---

**Finding 6.8 — `longPressLatLng!!` unsafe dereference in MainActivity**
- **File**: `app/src/main/kotlin/com/aegisnav/app/MainActivity.kt`
- **Line(s)**: 798
- **Category**: Other Code Quality Issues
- **Severity**: MEDIUM
- **Description**: `val ll = longPressLatLng!!` — `longPressLatLng` is a `remember { mutableStateOf<LatLng?>(null) }`. If the UI recomposes and reaches this code path with `null`, the app crashes. Compose state can be null during recomposition if the state was cleared between the user action and the handler execution.
- **Recommendation**: Use a safe `val ll = longPressLatLng ?: return` guard.

---

**Finding 6.9 — `FollowingDetector` coroutine scope missing lifecycle tie**
- **File**: `app/src/main/kotlin/com/aegisnav/app/intelligence/FollowingDetector.kt`
- **Line(s)**: 55–56
- **Category**: Other Code Quality Issues
- **Severity**: LOW
- **Description**:
  ```kotlin
  private val job = SupervisorJob()
  private val scope = CoroutineScope(Dispatchers.IO + job)
  ```
  `FollowingDetector` is a `@Singleton`, which is correct. However, if `ScanService` is destroyed and never restarted (device reboot handled by WorkManager), the `scope` in this singleton has no cancel mechanism — but this is intentionally the same pattern as `TrackerDetectionEngine`. The risk is low but worth documenting.
- **Recommendation**: Document that `FollowingDetector`'s scope is intentionally long-lived as a singleton. Add a `fun shutdown()` method mirroring `TrackerDetectionEngine`, even if it's only used in tests.

---

## 📊 Summary Count

| Category | CRITICAL | HIGH | MEDIUM | LOW | Total |
|---|---|---|---|---|---|
| 1. Unused Variables & Dead Code | 0 | 0 | 1 | 4 | **5** |
| 2. Dangling / Unconnected Functions | 0 | 0 | 1 | 0 | **1** |
| 3. Secrets & Credential Leaks | 0 | 0 | 1 | 1 | **2** |
| 4. Data Leaks & Privacy Issues | 0 | 2 | 2 | 1 | **5** |
| 5. Misconfigurations | 0 | 0 | 2 | 3 | **5** |
| 6. Other Code Quality Issues | 0 | 1 | 4 | 3 | **8** |
| **TOTAL** | **0** | **3** | **11** | **12** | **26** |

---

### 🔴 HIGH severity items to address first:
1. **4.1** — MAC addresses logged in GATT callbacks (even if debug-gated, sets bad precedent and risks inadvertent leak via log forwarding)
2. **4.2** — Raw tracker MAC in `AppLog.i()` call (INFO-level, higher log forwarding risk)
3. **6.2** — P2PManager `updateState()` race condition (read-modify-write on StateFlow from multiple WebSocket threads — can cause silent state loss under concurrent relay connections)

### 🟡 MEDIUM severity items:
- `sendAlert()` dead code (6→Category 1.1)
- P2P connectP2P/disconnectP2P unconnected (2.1)
- GitHub username in download URL (3.1)
- PendingIntents carrying GPS coordinates (4.3, 4.4)
- `exportSchema = false` blocking migration testing (5.1)
- Stale Compose dependency versions (5.2)
- `engineJob`/`scope` not `@Volatile` (6.3)
- `onSkip!!()` crash risk (6.7)
- `longPressLatLng!!` crash risk (6.8)