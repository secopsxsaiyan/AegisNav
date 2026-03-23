# AegisNav Security Audit v3 — 2026-03-14

**Version**: 3.14.26 (versionCode 4) | **DB**: v26 | **HEAD**: `a79d2da`
**Target SDK**: 35 | **Min SDK**: 31

## Tools Run

| Tool | Version | Scope | Status |
|------|---------|-------|--------|
| mobsfscan | 0.4.5 | Static (Kotlin/Java insecure patterns) | ✅ |
| semgrep | 1.154.0 | SAST (Android security rules) | ✅ |
| Trivy | 0.69.3 | Dependency vulns + secrets + misconfig | ✅ |
| Android Lint | AGP 8.x | Android-specific lint checks | ✅ |
| detekt | 1.23.7 | Kotlin static analysis | ✅ |
| trufflehog3 | 3.0.10 | Secret scanning (git history + files) | ✅ |
| Drozer (adb equiv) | Manual | IPC attack surface enumeration | ✅ |

---

## Executive Summary

| Severity | Count | Change vs v2 |
|----------|-------|---------------|
| **CRITICAL** | **0** | — |
| **HIGH** | **0** | — |
| **MEDIUM** | **3** | ↓ from 5 |
| **LOW / INFO** | **8** | stable |

**No critical or high severity issues.** The 3 medium findings are all pre-existing and either accepted risks or only applicable to release builds (which are not yet shipped).

---

## MEDIUM Findings

### M1. Insecure WebSocket URL in P2PSetupScreen
- **Tools**: semgrep (ERROR), mobsfscan (indirect)
- **File**: `p2p/P2PSetupScreen.kt:20`
- **Issue**: WebSocket URL uses `ws://` instead of `wss://`
- **Risk**: MitM on P2P relay connection
- **Status**: ⚠️ P2P is **disabled for alpha** (connect() never called, UI hidden). The hardcoded URL is in a setup screen that is unreachable. Fix before enabling P2P.
- **Recommendation**: Switch to `wss://` when P2P relay goes live (Phase 9)

### M2. Raw SQL Queries in OfflineGeocoderRepository
- **Tools**: mobsfscan (CWE-78/WARNING), semgrep (indirect)
- **File**: `geocoder/OfflineGeocoderRepository.kt:4,189,234`
- **Issue**: Uses `rawQuery()` with string interpolation for FTS search
- **Risk**: SQL injection if user input is not sanitized
- **Actual risk**: **LOW** — the geocoder DB is read-only (shipped asset), and queries use parameterized `?` placeholders with `selectionArgs`. mobsfscan flags all `rawQuery()` usage regardless. The only non-parameterized token is the FTS `MATCH` keyword which is validated before construction.
- **Recommendation**: No action needed. FTS4 `MATCH` syntax is inherently limited. Accepted risk.

### M3. Trivy: Dockerfile uses `:latest` tag
- **Tools**: Trivy (MEDIUM misconfig)
- **File**: `backend/Dockerfile`
- **Issue**: Two `FROM` lines use `:latest` instead of pinned versions
- **Risk**: Non-reproducible builds
- **Status**: `backend/` is **excluded from release** (per MEMORY.md dev/release rules). Only used for local relay testing.
- **Recommendation**: Pin versions when relay goes to production

---

## LOW / INFO Findings

### L1. Hardcoded Strings Flagged as Credentials (FALSE POSITIVE)
- **Tool**: mobsfscan (CWE-798/WARNING)
- **Files**: `ScanService.kt:487`, `OfflineGeocoderRepository.kt:329`, `NavigationViewModel.kt:702`
- **Analysis**: These are OSRM API base URLs (`http://router.project-osrm.org`), geocoder DB path strings, and channel ID constants — not credentials. **False positive.**

### L2. No Certificate Transparency Enforcement
- **Tool**: mobsfscan (CWE-295/INFO)
- **Status**: App is offline-first. Network calls are limited to OSRM fallback routing and tile downloads. CT enforcement is not applicable for offline operation. Low priority.

### L3. No Root Detection
- **Tool**: mobsfscan (CWE-919/INFO)
- **Status**: Accepted risk for alpha. Root detection adds friction for power users who are the target demographic. Revisit for public release.

### L4. No SafetyNet/Play Integrity Attestation
- **Tool**: mobsfscan (CWE-353/INFO)
- **Status**: No backend to attest against. Accepted risk. Revisit when P2P relay launches.

### L5. No SSL Pinning
- **Tool**: mobsfscan (CWE-295/INFO)
- **Status**: Same as L2 — minimal network surface. The app's value is in offline detection. Revisit for release.

### L6. No Tapjacking Protection
- **Tool**: mobsfscan (CWE-200/INFO)
- **Status**: Pre-existing from v2 audit. `FLAG_SECURE` is enabled in release builds. `filterTouchesWhenObscured` not set but confirm/dismiss buttons have no destructive actions. Low risk.

### L7. GCM IV Reuse Check
- **Tool**: semgrep (INFO)
- **File**: `security/DatabaseKeyManager.kt:82,94`
- **Analysis**: The code generates a fresh random IV per encryption operation (`cipher.iv` stored alongside ciphertext). IV is **never reused**. False positive by pattern matching.

### L8. Logging in Release Builds
- **Tool**: mobsfscan (CWE-532/INFO)
- **Files**: `AegisNavApplication.kt:235,239,246`
- **Status**: All logging goes through `AppLog` which is gated on `FLAG_DEBUGGABLE`. Release builds produce zero log output. The 3 flagged lines are in the Application `onCreate` preload path — they use `AppLog` correctly. **No leak in release.**

---

## Drozer-Equivalent IPC Audit (Device: S21 R3CNC0B1ZGX)

### Exported Components

| Component | Type | Risk |
|-----------|------|------|
| `MainActivity` | Activity | LAUNCHER only — standard, no extras processed from external intents without validation |
| `BackgroundScanReceiver` | Receiver | Listens for `BLE_SCAN_RESULT` and `OPPORTUNISTIC_SCAN_RESULT` — **app-scoped PendingIntent actions**, not externally triggerable by other apps on Android 12+ (implicit broadcast restrictions) |
| `BluetoothStateMonitor` | Receiver | System broadcast only (`STATE_CHANGED`) — no risk |
| `SystemJobService` | Service | AndroidX WorkManager internal — not directly invocable |
| `ProfileInstallReceiver` | Receiver | AndroidX Baseline Profile — standard library component |
| `RescheduleReceiver` | Receiver | AndroidX WorkManager — system broadcasts only |

**Key findings:**
- ✅ **No exported Content Providers** — no data leakage surface
- ✅ **No exported Services** beyond WorkManager internals
- ✅ **No custom permissions defined** (except `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — Android 13+ standard)
- ✅ **No shared user ID** — proper app isolation
- ✅ `allowBackup="false"` — no ADB backup extraction
- ✅ `FLAG_SECURE` in release builds — no screenshot/screen recording
- ⚠️ `DEBUGGABLE` flag is set — **expected for debug APK**, release builds strip this

### Permission Surface
17 permissions requested. All are justified by app functionality:
- Location (fine/coarse/background) — core surveillance detection
- Bluetooth (scan/connect) — BLE tracker detection
- WiFi (state/change) — WiFi fingerprinting
- Internet — OSRM fallback, tile download
- Foreground service (location + connected device) — background scanning
- Vibrate — detection alerts
- Boot completed — scan restart after reboot
- Wake lock — continuous scanning

**No over-privileged permissions.** No camera, contacts, SMS, phone, or storage permissions.

---

## Android Lint Summary

- **Errors: 0** ✅
- **Warnings: 71** (all non-security)
  - 2× `ApplySharedPref` — `commit()` used intentionally before process restart
  - 1× `OldTargetApi` — targetSdk=35 is current; lint flags because 36 preview exists
  - 1× `StaticFieldLeak` — NavigationViewModel context ref (ViewModel scoped, acceptable)
  - 1× `DataExtractionRules` — `allowBackup=false` is deprecated syntax; should add `dataExtractionRules` XML. **No security risk** — backup is disabled either way.
  - 1× `GradleDependency` — security-crypto alpha vs stable. **Should upgrade** `1.1.0-alpha06` → `1.1.0`.
  - 65× style/deprecation warnings (unrelated to security)

---

## detekt Summary

- **Total issues: 1,839** (weighted)
- **Unique rules triggered: 443**
- **Breakdown**: Overwhelmingly `MagicNumber` (color hex, dp values, timing constants in UI code) and naming conventions. Zero security-relevant detekt findings.
- **1 dead code**: `computeCurrentThreatLevel()` — unused private function in MainActivity
- **1 return count**: `PassiveBleScanner.scan()` has 3 returns (limit 2) — acceptable for scan lifecycle

---

## trufflehog3 — Secret Scanning

**No secrets found in codebase or git history.** ✅

---

## Cross-Reference Matrix

| Finding | mobsfscan | semgrep | Trivy | Lint | detekt | trufflehog | drozer |
|---------|-----------|---------|-------|------|--------|------------|--------|
| M1 ws:// | — | ✅ ERROR | — | — | — | — | — |
| M2 rawQuery | ✅ WARN | — | — | — | — | — | — |
| M3 Dockerfile | — | — | ✅ MED | — | — | — | — |
| L1 hardcoded (FP) | ✅ WARN | — | — | — | — | ✅ clean | — |
| L7 GCM IV (FP) | — | ✅ INFO | — | — | — | — | — |
| L8 logging | ✅ INFO | — | — | — | — | — | — |
| IPC surface | — | — | — | — | — | — | ✅ clean |
| Exported comps | — | — | — | — | — | — | ✅ minimal |
| Permissions | — | — | — | — | — | — | ✅ justified |

---

## Recommendations (Priority Order)

1. **Before P2P launch**: Fix M1 (ws:// → wss://)
2. **Before public release**: Upgrade security-crypto to stable 1.1.0
3. **Before public release**: Add `dataExtractionRules` XML (replace deprecated `allowBackup`)
4. **Cleanup**: Remove dead `computeCurrentThreatLevel()` function
5. **Deferred**: Root detection, SSL pinning, CT enforcement, SafetyNet — revisit for v1.0

---

## Conclusion

AegisNav v3.14.26 has **0 critical, 0 high, and 3 medium findings** — all pre-existing and mitigated by current constraints (P2P disabled, backend not deployed, offline-first architecture). The IPC attack surface is minimal with no exported providers, no shared UIDs, and properly scoped permissions. No secrets leaked in code or git history. The app is **clear for continued alpha testing**.

*Audit conducted 2026-03-14 23:50 EDT against commit `a79d2da` on dev-AegisNav.*
