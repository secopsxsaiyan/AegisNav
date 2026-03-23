# AegisNav Security Audit Report v2
**Date:** 2026-03-11 16:05 EDT
**Version:** v0.4.0-alpha
**Target:** dev-AegisNav (commit 38e0d08)
**Trigger:** Post-feature security re-scan after P2P fixes, tile download, police/report/threat changes

## Tools Used
| Tool | Version | Focus |
|------|---------|-------|
| MobSF (mobsfscan) | 0.4.5 | Android security patterns |
| Semgrep | 1.x | OWASP Top 10, Kotlin rules |
| Trivy | 0.x | Dependency CVEs, secrets, misconfigs |
| OWASP OSV API | api.osv.dev | Dependency CVE database |
| Android Lint | AGP 8.2.2 | Android correctness + security |

---

## Executive Summary

| Category | Critical | High | Medium | Low/Info |
|----------|----------|------|--------|----------|
| Dependency CVEs (OSV) | 0 | 0 | 0 | 0 |
| Source Code (Semgrep) | 0 | 0 | 1 | 2 |
| Source Code (MobSF) | 0 | 0 | 2 | 6 |
| Trivy (secrets/misconfig) | 0 | 0 | 2 | 0 |
| Android Lint Errors | 0 | 0 | 4 | 53 |

**Bottom line:** No critical or high vulnerabilities. Significant improvement from v1 audit (8 HIGH dep CVEs → 0). 4 Lint errors (same permission checks as v1). No new vulnerabilities introduced by today's changes.

---

## Comparison: v1 vs v2 Audit

| Finding | v1 (edb3502) | v2 (38e0d08) | Status |
|---------|-------------|-------------|--------|
| Dependency CVEs (HIGH) | 8 (GH transitive) | **0** | ✅ RESOLVED (OSV now clean) |
| SQLCipher EOL | Present | Present | 🟡 Still pending migration |
| Missing POST_NOTIFICATIONS | Present | Present | 🔴 Still needs fix |
| Missing BLE/WiFi permission checks | 4 errors | 4 errors | 🟡 Same (from v1) |
| Insecure WebSocket in P2P setup example | Present | Present | ℹ️ UI hint only, actual P2P uses WSS |
| GCM IV reuse concern | Present | Present | ℹ️ False positive (fresh IV each encrypt) |
| Hardcoded values (MobSF) | Present | Present | ℹ️ FP — DatabaseKeyManager constants, TrackerDetectionEngine thresholds |
| Raw SQL queries | Present | Present | ℹ️ FP — geocoder uses parameterized FTS queries |
| Dockerfile :latest tag | N/A | 2 MEDIUM | 🟡 New — backend Dockerfile should pin alpine version |

### NEW since v1:
- **No new vulnerabilities from today's code changes** (P2P fixes, tile download, police reporting, report verdicts)
- DataDownloadManager, DataDownloadScreen — clean scan, no findings
- PoliceDetectionItem, ThreatHistory grouping — clean scan, no findings
- P2P offline enforcement — clean scan, no findings

---

## Detailed Findings

### Semgrep (3 findings)

1. **[ERROR] Insecure WebSocket detected** — `P2PSetupScreen.kt:21`
   - `ws://` in example/placeholder text field
   - **Actual risk: NONE** — this is the default hint text in the relay URL input. Real connections use WSS. The hint should still show `wss://` to guide users correctly.

2. **[INFO] GCM detection** — `DatabaseKeyManager.kt:82, :94`
   - AES-GCM encryption/decryption for database key
   - **Actual risk: NONE** — IV is generated fresh with `SecureRandom` on each `encrypt()` call; `decrypt()` reads IV from ciphertext prefix. No reuse.

### MobSF (8 rules triggered)

| Severity | Rule | Files | Risk |
|----------|------|-------|------|
| WARNING | Hardcoded sensitive info | DatabaseKeyManager.kt, TrackerDetectionEngine.kt | FP — constants, thresholds |
| WARNING | Raw SQL query | OfflineGeocoderRepository.kt (×3) | FP — parameterized FTS |
| INFO | Certificate transparency | — | App-wide, low priority |
| INFO | Logging | AegisNavApplication.kt | Debug-only (FLAG_DEBUGGABLE gated) |
| INFO | No root detection | — | By design (privacy app, no anti-tampering needed) |
| INFO | No SafetyNet | — | By design (no Google dependencies) |
| INFO | No SSL pinning | — | Low priority (P2P relay, not banking) |
| INFO | No tapjacking prevention | — | Low priority |

### Trivy (2 misconfigs)

| Severity | Target | Finding |
|----------|--------|---------|
| MEDIUM | backend/Dockerfile | `:latest` tag on alpine — should pin version |
| MEDIUM | backend/Dockerfile | `:latest` tag on alpine (duplicate, build stage) |

### OWASP OSV (0 vulnerabilities)
All 41 direct dependencies scanned against OSV database — **zero known CVEs**.

### Android Lint (4 errors, 53 warnings)

**Errors (same as v1):**
1. PassiveBleScanner.kt — Missing BLE permission runtime check
2. PassiveWifiScanner.kt — Missing WiFi permission runtime check
3. ScanService.kt (×2) — Missing BLE/WiFi permission runtime checks

**Key warnings:**
- 53 total (ObsoleteLintCustomCheck, MissingPermission checks, etc.)
- Mostly Compose lint compatibility warnings (old API versions)

---

## Recommendations (Priority Order)

### 🔴 MUST FIX (Pre-Release)
1. **POST_NOTIFICATIONS permission** — Add runtime check + request in PrivacyWizard (Android 13+ notifications silently fail without this)
2. **BLE/WiFi permission guards** — Add `checkSelfPermission()` before scan API calls (defensive, prevents crash if permission revoked)
3. **VIBRATE permission** — Add to AndroidManifest.xml (one line)

### 🟡 SHOULD FIX
4. **P2PSetupScreen hint** — Change `ws://` → `wss://` in default relay URL hint text
5. **Dockerfile alpine tag** — Pin to specific version (e.g., `alpine:3.19`)
6. **SQLCipher migration** — `android-database-sqlcipher:4.5.4` → `net.zetetic:sqlcipher-android:4.12.0`

### ✅ NO ACTION NEEDED
- GCM IV reuse — false positive
- Hardcoded values — false positive (constants/thresholds)
- Raw SQL — false positive (parameterized FTS)
- Root detection — by design
- SSL pinning — low priority for P2P relay
- Certificate transparency — low priority

---

## Scan Artifacts
- `security-reports/mobsf_report_v2.json`
- `security-reports/semgrep_report_v2.json`
- `security-reports/trivy_report_v2.json`
- `security-reports/owasp_osv_report_v2.json`
- `security-reports/lint_report_v2.xml`
- `security-reports/lint_report_v2.html`
