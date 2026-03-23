# MobSF Static Analysis Report — AegisNav

**Tool:** mobsfscan v0.4.5
**Date:** 2026-03-11
**Target:** app/src/main/

## Summary

| Severity | Count | Real Issues |
|----------|-------|-------------|
| ERROR | 1 | 0 (false positive) |
| WARNING | 2 | 1 (SQL raw query — mitigated) |
| INFO | 6 | Informational only |

## Findings

### ERROR: StrandHogg 2.0 Task Hijacking (android_task_hijacking2)
- **File:** AndroidManifest.xml
- **Assessment: FALSE POSITIVE** — targetSdk=35 in build.gradle.kts (well above 29). MobSF parsed manifest without build.gradle context. StrandHogg 2.0 is patched at platform level for targetSdk≥29.

### WARNING: Hardcoded Sensitive Information (android_kotlin_hardcoded)
- **File:** DatabaseKeyManager.kt:23 — `pass = "db_pass_enc"` (SharedPreferences key name, not an actual password)
- **File:** TrackerDetectionEngine.kt:178 — `key = "companion_$mac"` (map key construction, not a credential)
- **Assessment: FALSE POSITIVE** — both are key names/identifiers, not secrets.

### WARNING: Raw SQL Query (android_kotlin_sql_raw_query)
- **Files:** OfflineGeocoderRepository.kt (5 instances)
- **Assessment: LOW RISK** — the geocoder uses `rawQuery()` with FTS4 match syntax. Query parameters are passed via `arrayOf()` parameterization (not string concatenation). User input flows through `parseAddressComponents()` which strips to alphanumeric tokens before FTS matching. No direct user string interpolation into SQL.

### INFO: Logging (android_kotlin_logging)
- 156 Log.* calls detected
- **Assessment: RESOLVED** — all replaced with AppLog wrapper (no-op in release builds)

### INFO: No root detection, no SafetyNet, no SSL pinning, no tapjacking protection, no certificate transparency
- Expected for an alpha privacy tool that operates primarily offline
- SSL pinning not applicable (no proprietary API servers)
- Root detection would be counter to the app's privacy-first philosophy
