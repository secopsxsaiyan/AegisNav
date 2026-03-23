# AegisNav Security Scan — Trivy Report
**Date:** 2026-03-11  
**Trivy Version:** 0.69.3  
**Scan Target:** `/home/autopilot4131/.openclaw/workspace/dev-AegisNav`  
**Scanners:** vuln, secret, misconfig  

---

## 📊 Summary

| Severity    | Count |
|-------------|-------|
| CRITICAL    | 0     |
| HIGH        | 0     |
| MEDIUM      | 2     |
| LOW         | 0     |
| Secrets     | 0     |

**Overall risk: LOW** — No critical or high vulnerabilities detected. Two medium-severity Docker misconfigurations found.

---

## 🔍 Scan Coverage

| Target | Type | Vulns | Secrets | Misconfigs |
|---|---|---|---|---|
| `app/build/tmp/.cache/.../org.jacoco.agent/pom.xml` | pom | 0 | — | — |
| `backend/go.mod` | gomod | 0 | — | — |
| `backend/Dockerfile` | dockerfile | — | — | 2 MEDIUM |

> ⚠️ **Coverage gap:** Trivy does not natively parse Kotlin DSL (`build.gradle.kts`) files. Android dependencies were NOT scanned by Trivy. See the "Android Dependencies" section below for manual review notes.

---

## 🚨 CRITICAL Vulnerabilities
_None found._

---

## ⚠️ HIGH Vulnerabilities
_None found._

---

## 🟡 MEDIUM Vulnerabilities
_None found in dependency packages._

---

## 🐳 Misconfigurations (MEDIUM — Dockerfile)

### 1. DS-0001 — `FROM alpine:latest` (Line 1, builder stage)
- **Severity:** MEDIUM
- **Rule:** Specify a tag in the `FROM` statement for image `alpine`
- **Detail:** Using `alpine:latest` as the builder image allows uncontrolled behavior when the image is silently updated upstream — breaking reproducibility and potentially introducing supply-chain changes.
- **Fix:** Pin to a specific version, e.g. `FROM alpine:3.21`
- **Reference:** https://avd.aquasec.com/misconfig/ds-0001

### 2. DS-0001 — `FROM alpine:latest` (Line 15, runtime stage)
- **Severity:** MEDIUM
- **Rule:** Specify a tag in the `FROM` statement for image `alpine`
- **Detail:** Same issue in the final runtime stage — `alpine:latest` is unpinned.
- **Fix:** Pin to a specific version, e.g. `FROM alpine:3.21`
- **Reference:** https://avd.aquasec.com/misconfig/ds-0001

**Recommended fix in `backend/Dockerfile`:**
```dockerfile
FROM alpine:3.21 AS builder
# ...
FROM alpine:3.21
```

---

## 🔐 Secrets Detected
_None found._

> Note: Trivy warned that `app/src/main/assets/us_alpr_cameras.geojson` (17 MB) was too large to scan for secrets efficiently. Recommend manually verifying this file contains no embedded credentials or API keys.

---

## 📦 Android Dependencies (Not Scanned by Trivy — Manual Review Notes)

Trivy does not support Kotlin DSL Gradle files. The following dependencies from `app/build.gradle.kts` should be reviewed manually or with a dedicated tool (e.g., OWASP Dependency-Check, Snyk):

| Library | Version | Notes |
|---|---|---|
| `androidx.appcompat:appcompat` | 1.6.1 | Stable, current |
| `androidx.core:core-ktx` | 1.13.1 | Stable, current |
| `org.maplibre.gl:android-sdk` | 12.3.1 | Check for CVEs |
| `com.graphhopper:graphhopper-core` | 6.2 | Check for CVEs |
| `com.graphhopper:graphhopper-web-api` | 6.2 | Check for CVEs |
| `androidx.room:room-runtime` | 2.6.1 | Stable |
| `net.zetetic:android-database-sqlcipher` | 4.5.4 | Encryption lib — verify latest |
| `com.google.dagger:hilt-android` | 2.51.1 | Stable |
| `com.google.code.gson:gson` | 2.10.1 | Stable |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Stable, current |
| `com.github.luben:zstd-jni` | 1.5.5-11 | Compression — verify |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Stable |

**Recommended next step:** Run `./gradlew dependencyCheckAnalyze` (OWASP Dependency-Check Gradle plugin) for full CVE coverage of Android/JVM dependencies.

---

## 🐹 Go Backend Dependencies (Scanned — Clean)

| Module | Version | Status |
|---|---|---|
| `github.com/libp2p/go-libp2p` | v0.32.0 | ✅ No CVEs found |
| `github.com/libp2p/go-libp2p-pubsub` | v0.9.3 | ✅ No CVEs found |
| `github.com/libp2p/go-libp2p-noise` | v0.3.0 | ✅ No CVEs found |

---

## 🛠️ Recommendations

1. **Pin Docker base images** (MEDIUM, easy fix): Replace both `FROM alpine:latest` with `FROM alpine:3.21` in `backend/Dockerfile`.
2. **Scan Android dependencies separately**: Use OWASP Dependency-Check or Snyk for Gradle/JVM dependency CVE coverage — Trivy cannot parse `.kts` files.
3. **Review large GeoJSON asset**: `us_alpr_cameras.geojson` (17 MB) was skipped for secret scanning — manually confirm no API keys or credentials are embedded.
4. **Consider Docker image pinning in CI**: Use image digest pinning (`alpine@sha256:...`) for stronger supply chain guarantees in production deployments.
5. **Keep libp2p dependencies updated**: The libp2p ecosystem evolves quickly — monitor for new CVEs with each release cycle.

---

## 📁 Output Files
- `trivy_report.json` — Full JSON scan output
- `trivy_report.txt` — Human-readable table output
- `trivy_deps.txt` — Gradle deps scan attempt (N/A — `.kts` not supported)
- `security_trivy_summary.md` — This file
