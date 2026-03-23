# AegisNav Security Audit Report
**Date:** 2026-03-11
**Version:** v0.4.0-alpha
**Target:** dev-AegisNav (commit edb3502)

## Tools Used
| Tool | Version | Focus |
|------|---------|-------|
| MobSF (mobsfscan) | 0.4.5 | Android security patterns |
| Semgrep | 1.x | OWASP Top 10, Kotlin rules (1,063 rules) |
| Trivy | 0.x | Dependency CVEs, secrets, misconfigs |
| OWASP Dep-Check | OSV/NVD API | Dependency CVE database |
| Android Lint | AGP bundled | Android correctness + security |
| detekt | 1.23.4 | Kotlin code quality + complexity |

---

## Executive Summary

| Category | Critical | High | Medium | Low/Info |
|----------|----------|------|--------|----------|
| Dependency CVEs | 0 | **8** | 3 | 0 |
| Source Code Security | 0 | 0 | 0 | 4 (all FP) |
| Android Lint Errors | 0 | 0 | **13** | 64 |
| Code Quality (detekt) | — | — | — | 1,132 |

**Bottom line:** No critical vulnerabilities. 8 HIGH dependency CVEs (all DoS, all in GraphHopper transitive deps). 13 Android Lint errors (permissions + notifications). No secrets leaked, no SQL injection, no insecure crypto.

---

## 🔴 POTENTIAL SHOW-STOPPERS

### 1. SQLCipher EOL Migration (OWASP)
- **Library:** `android-database-sqlcipher:4.5.4` is **officially end-of-life**
- **Risk:** No more security patches, won't meet Google Play 16KB page alignment requirement
- **Fix:** Migrate to `net.zetetic:sqlcipher-android:4.12.0`
- **Effort:** Medium — API is mostly compatible, needs testing
- **Verdict: 🟡 SHOULD FIX before public release** — won't crash, but an EOL crypto library in a privacy app is a bad look

### 2. Missing POST_NOTIFICATIONS Permission Check (Android Lint)
- **Files:** AegisNavApplication.kt:158, FlockReportingCoordinator.kt:122, PoliceReportingCoordinator.kt:140, ScanService.kt:373, ScanService.kt:454
- **Risk:** On Android 13+ (targetSdk 35), posting notifications without checking/requesting POST_NOTIFICATIONS permission will silently fail — users won't see tracker/police/flock alerts
- **Fix:** Add permission check + request flow in PrivacyWizard
- **Verdict: 🔴 MUST FIX** — core alerting feature broken on Android 13+ without this

### 3. Missing BLE/WiFi Permission Runtime Checks (Android Lint)
- **Files:** PassiveBleScanner.kt:31/38/41, PassiveWifiScanner.kt:25/44, ScanService.kt:262/297
- **Risk:** Calling BLE/WiFi scan APIs without runtime permission checks can crash on API 31+
- **Fix:** Add `checkSelfPermission()` guards before scan calls
- **Verdict: 🟡 SHOULD FIX** — PrivacyWizard grants these, but defensive checks prevent crashes if permissions are revoked while running

### 4. Missing VIBRATE Permission (Android Lint)
- **File:** ScanService.kt:350
- **Risk:** `Vibrator.vibrate()` called without VIBRATE permission in manifest
- **Fix:** Add `<uses-permission android:name="android.permission.VIBRATE"/>` to AndroidManifest.xml
- **Verdict: 🟢 EASY FIX** — one line in manifest

---

## 🟡 SHOULD ADDRESS (Pre-Release)

### 5. GraphHopper Transitive Dependency CVEs (OWASP)
8 HIGH CVEs, all Denial of Service:
| CVE | Package | Version | CVSS | Fix Version |
|-----|---------|---------|------|-------------|
| CVE-2020-36518 | jackson-databind | 2.10.5.1 | 7.5 | 2.12.6.1+ |
| CVE-2021-46877 | jackson-databind | 2.10.5.1 | 7.5 | 2.12.6.1+ |
| CVE-2022-42003 | jackson-databind | 2.10.5.1 | 7.5 | 2.12.7.1+ |
| CVE-2022-42004 | jackson-databind | 2.10.5.1 | 7.5 | 2.12.7.1+ |
| CVE-2021-22569 | protobuf-java | 3.11.4 | 7.5 | 3.16.3+ |
| CVE-2022-3509 | protobuf-java | 3.11.4 | 7.5 | 3.16.3+ |
| CVE-2022-3510 | protobuf-java | 3.11.4 | 7.5 | 3.16.3+ |
| CVE-2024-7254 | protobuf-java | 3.11.4 | 7.5 | 3.25.5+ |

- **Risk:** DoS if an attacker can feed crafted data to these libraries. Low practical risk since GraphHopper processes local routing data, not untrusted network input.
- **Fix:** Add `resolutionStrategy { force(...) }` to build.gradle.kts:
  ```kotlin
  configurations.all {
      resolutionStrategy {
          force("com.fasterxml.jackson.core:jackson-databind:2.15.3")
          force("com.google.protobuf:protobuf-java:3.25.5")
          force("com.fasterxml.woodstox:woodstox-core:6.5.1")
          force("commons-io:commons-io:2.14.0")
      }
  }
  ```
- **Verdict: 🟡 LOW PRACTICAL RISK but easy fix** — force versions to close audit findings

### 6. Hardcoded /sdcard/ Paths (Android Lint)
- **Files:** RoutingRepository.kt:62/65, TileSourceResolver.kt:25/27
- **Risk:** `/sdcard/` is deprecated; scoped storage on Android 11+ may not resolve correctly
- **Fix:** Use `context.getExternalFilesDir()` or `Environment.getExternalStorageDirectory()`
- **Verdict: 🟡 SHOULD FIX** — may break on some devices

### 7. Dockerfile alpine:latest (Trivy)
- **File:** backend/Dockerfile lines 1, 15
- **Risk:** Unpinned base image, reproducibility concern
- **Fix:** Pin to `alpine:3.21`
- **Verdict: 🟢 NOT BLOCKING** — backend not part of Android release

---

## 🟢 INFORMATIONAL (No Action Required for Alpha)

### Semgrep Findings (4 — all false positives)
- `ws://` in code comment (actual code enforces `wss://`) ✓
- Exported MAIN/LAUNCHER activity (required by Android) ✓
- AES-GCM flagged but IV is properly random per call ✓

### MobSF Findings
- StrandHogg 2.0 — **FP** (targetSdk=35 mitigates) ✓
- "Hardcoded keys" — just map key names, not secrets ✓
- Raw SQL — parameterized via `arrayOf()`, no string interpolation ✓
- No root detection / SSL pinning / SafetyNet — appropriate for offline privacy tool ✓

### detekt Code Quality (1,132 findings)
| Category | Count | Severity |
|----------|-------|----------|
| MagicNumber | 753 | Style (not security) |
| WildcardImport | 59 | Style |
| MaxLineLength | 53 | Style |
| TooGenericExceptionCaught | 51 | Minor |
| FunctionNaming | 48 | Style (Compose conventions) |
| ReturnCount | 23 | Complexity |
| LongMethod | 23 | Complexity |
| CyclomaticComplexMethod | 22 | Complexity |
| Other | 100 | Various |

No security issues. Complexity hotspots: MainActivity.kt, MapLibreMapView.kt, OfflineGeocoderRepository.kt, PmTilesHttpInterceptor.kt. Normal for a Compose app of this size.

### Android Lint Warnings (64)
- 24 outdated dependency versions (GradleDependency) — cosmetic
- BatteryLife warning for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — intentional for scan reliability
- ApplySharedPref — use `apply()` instead of `commit()` (minor perf)
- ObsoleteLintCustomCheck — third-party lint rules outdated (not our code)

---

## Secrets Scan Results
| Scanner | Secrets Found |
|---------|---------------|
| Trivy | **0** |
| Semgrep | **0** |
| MobSF | **0** (false positives only) |

✅ No hardcoded secrets, tokens, API keys, or credentials in source code.

---

## Recommended Actions Before Public Release

### Must Fix (Show-Stoppers)
1. [ ] **POST_NOTIFICATIONS permission** — add runtime check + request in PrivacyWizard
2. [ ] **VIBRATE permission** — add to AndroidManifest.xml

### Should Fix (Strongly Recommended)
3. [ ] **SQLCipher migration** — `android-database-sqlcipher:4.5.4` → `sqlcipher-android:4.12.0`
4. [ ] **BLE/WiFi permission guards** — add `checkSelfPermission()` before scan API calls
5. [ ] **Force transitive dependency versions** — jackson-databind, protobuf-java, woodstox, commons-io
6. [ ] **Replace /sdcard/ paths** — use `context.getExternalFilesDir()` or similar

### Nice to Have (Post-Launch)
7. [ ] Reduce cyclomatic complexity in hotspot files
8. [ ] Extract magic numbers to named constants
9. [ ] Pin Dockerfile base images

---

## Conclusion

**The codebase is in good shape for an alpha release.** No critical vulnerabilities, no leaked secrets, no SQL injection, crypto is correctly implemented. The two must-fix items (#1 POST_NOTIFICATIONS, #2 VIBRATE) are straightforward permission additions. The SQLCipher EOL migration (#3) is the largest effort item but can ship as a fast-follow if needed.

**Estimated effort for must-fix items: 1-2 hours.**
