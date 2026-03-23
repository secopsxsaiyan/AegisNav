# AegisNav OWASP Dependency Security Report
**Generated:** 2026-03-11  
**Tool:** OSV API (api.osv.dev) + NVD + Manual audit  
**Project:** AegisNav Android (`dev-AegisNav/app/build.gradle.kts`)  
**Method:** Direct OSV batch query + transitive dependency analysis via deps.dev

---

## Executive Summary

| Category | Count |
|----------|-------|
| Direct dependencies scanned | 33 |
| Direct vulnerabilities found | **0** |
| Transitive vulnerable packages | **4** |
| HIGH severity CVEs | **8** |
| MEDIUM severity CVEs | **3** |
| Deprecated/EOL libraries | **1** |

**Good news:** All 33 direct dependencies are clean — no known CVEs in OSV for any version currently used.

**Bad news:** GraphHopper 6.2 pulls in severely outdated transitive dependencies with multiple HIGH-severity DoS vulnerabilities. Additionally, the SQLCipher library is **officially deprecated** and must be migrated.

---

## HIGH Severity Vulnerabilities

### 1. `com.fasterxml.jackson.core:jackson-databind:2.10.5.1`
**Source:** Transitive via `com.graphhopper:graphhopper-core:6.2`

| CVE | CVSS | Description | Fixed In |
|-----|------|-------------|----------|
| CVE-2020-36518 | **7.5 HIGH** | Deeply nested JSON causes uncontrolled stack recursion → DoS | 2.12.6.1, 2.13.2.1 |
| CVE-2021-46877 | **7.5 HIGH** | JDK serialization of JsonNode → DoS | 2.12.6, 2.13.1 |
| CVE-2022-42003 | **7.5 HIGH** | Uncontrolled resource consumption via `UNWRAP_SINGLE_VALUE_ARRAYS` | 2.12.7.1, 2.13.4.2 |
| CVE-2022-42004 | **7.5 HIGH** | Uncontrolled resource consumption via `@JsonMerge` annotation | 2.12.7.1, 2.13.4 |

**Recommendation:** Update GraphHopper to a version that uses jackson-databind ≥ 2.14.x, **or** force-override via Gradle resolution strategy.

---

### 2. `com.google.protobuf:protobuf-java:3.11.4`
**Source:** Transitive via `com.graphhopper:graphhopper-core:6.2`

| CVE | CVSS | Description | Fixed In |
|-----|------|-------------|----------|
| CVE-2021-22569 | **7.5 HIGH** | Interleaving of unknown fields → excessive CPU → DoS | 3.16.1, 3.18.2, 3.19.2 |
| CVE-2022-3509 | **7.5 HIGH** | Text format parsing → DoS | 3.16.3, 3.19.6, 3.20.3, 3.21.7 |
| CVE-2022-3510 | **7.5 HIGH** | Message-type extension parsing → DoS | 3.16.3, 3.19.6, 3.20.3, 3.21.7 |
| CVE-2024-7254 | **7.5 HIGH** | Recursion limit bypass in nested messages → DoS (2024!) | 3.25.5 |

**Note:** CVE-2024-7254 is a 2024 vulnerability unfixed until 3.25.5 — this is a significant gap from 3.11.4.  
**Recommendation:** Force-override `protobuf-java` to `3.25.5` in Gradle resolution strategy.

---

## MEDIUM Severity Vulnerabilities

### 3. `com.fasterxml.woodstox:woodstox-core:6.2.1`
**Source:** Transitive via `com.graphhopper:graphhopper-core:6.2`

| CVE | CVSS | Description | Fixed In |
|-----|------|-------------|----------|
| CVE-2022-40152 | **6.5 MEDIUM** | XML parser crash via deeply nested structures (requires auth) | 6.4.0, 5.4.0 |

**Recommendation:** Force-override to `woodstox-core:6.4.0+`.

---

### 4. `commons-io:commons-io:2.7`
**Source:** Transitive via `com.graphhopper:graphhopper-core:6.2`

| CVE | CVSS | Description | Fixed In |
|-----|------|-------------|----------|
| CVE-2024-47554 | **4.3 MEDIUM** | Untrusted input to `XmlStreamReader` → infinite loop → DoS (2024!) | 2.14.0 |

**Recommendation:** Force-override to `commons-io:2.16.1` (latest stable).

---

## Deprecated Libraries (Action Required)

### `net.zetetic:android-database-sqlcipher:4.5.4`
**Status:** ⚠️ **OFFICIALLY DEPRECATED — NO LONGER MAINTAINED**

The `android-database-sqlcipher` artifact was deprecated in 2023. Zetetic now publishes under:
```
net.zetetic:sqlcipher-android:4.12.0@aar
androidx.sqlite:sqlite:2.2.0
```

**Why this matters:**
- Last release of `android-database-sqlcipher` was **April 2023** (4.5.4)
- Security patches (including OpenSSL CVEs) are only going into `sqlcipher-android`
- Google Play **requires 16KB page compatibility** in new submissions — only `sqlcipher-android` will receive this fix
- The bundled SQLite in 4.5.4 may be affected by CVE-2024-0232 (MEDIUM 4.7 — heap use-after-free in `jsonParseAddNodeArray()`)

**Migration:**
```kotlin
// Remove:
implementation("net.zetetic:android-database-sqlcipher:4.5.4")
implementation("androidx.sqlite:sqlite-framework:2.4.0")

// Add:
implementation("net.zetetic:sqlcipher-android:4.12.0@aar")
implementation("androidx.sqlite:sqlite:2.2.0")
```
API is mostly compatible but see [migration guide](https://github.com/sqlcipher/sqlcipher-android).

---

## All Vulnerable Dependencies Summary

| Package | Version | Severity | CVE Count | Via |
|---------|---------|----------|-----------|-----|
| jackson-databind | 2.10.5.1 | HIGH (×4) | CVE-2020-36518, CVE-2021-46877, CVE-2022-42003, CVE-2022-42004 | graphhopper-core:6.2 |
| protobuf-java | 3.11.4 | HIGH (×4) | CVE-2021-22569, CVE-2022-3509, CVE-2022-3510, CVE-2024-7254 | graphhopper-core:6.2 |
| woodstox-core | 6.2.1 | MEDIUM | CVE-2022-40152 | graphhopper-core:6.2 |
| commons-io | 2.7 | MEDIUM | CVE-2024-47554 | graphhopper-core:6.2 |
| android-database-sqlcipher | 4.5.4 | DEPRECATED | (potential CVE-2024-0232) | direct |

---

## Risk Context for Android

All confirmed CVEs are **Denial-of-Service only** — no Remote Code Execution or data exfiltration. However:

- **Realistic threat:** If AegisNav processes externally-sourced routing data (remote GraphHopper graph files, OSM data, protobuf-encoded routes), a malicious data source could crash the app.
- **Android sandbox mitigates** native exploitability but DoS crashes remain a reliability concern.
- **SQLCipher deprecation** is a higher operational risk due to missed security patches and Play Store 16KB page requirement.

---

## Recommended Actions

### Priority 1 — Critical (Do Now)
1. **Migrate SQLCipher** from `android-database-sqlcipher:4.5.4` to `sqlcipher-android:4.12.0`

### Priority 2 — High (Sprint)
2. **Update GraphHopper** from 6.2 to latest 7.x — this will bring newer transitive deps
3. **OR** apply Gradle resolution strategy overrides now (see below)

### Priority 3 — Medium (Backlog)
4. **Compose UI** upgrade: 1.5.4 → 1.8.x (security fixes + Kotlin 2.0 support)
5. **Activity Compose** upgrade: 1.8.2 → 1.10.x
6. **OkHttp** upgrade: 4.12.0 → 4.12.x (current 4.x patch) or evaluate 5.x API migration

---

## Gradle Fix: Force Transitive Dependency Overrides

Add to `app/build.gradle.kts` to immediately mitigate transitive CVEs without waiting for GraphHopper update:

```kotlin
configurations.all {
    resolutionStrategy {
        // Fix jackson-databind CVEs (4 HIGH)
        force("com.fasterxml.jackson.core:jackson-databind:2.15.4")
        force("com.fasterxml.jackson.core:jackson-core:2.15.4")
        force("com.fasterxml.jackson.core:jackson-annotations:2.15.4")
        force("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.15.4")
        // Fix protobuf-java CVEs (4 HIGH including 2024 CVE)
        force("com.google.protobuf:protobuf-java:3.25.5")
        // Fix woodstox-core CVE
        force("com.fasterxml.woodstox:woodstox-core:6.4.0")
        // Fix commons-io CVE
        force("commons-io:commons-io:2.16.1")
    }
}
```

⚠️ **Test carefully** after forcing versions — GraphHopper may not be compatible with all version jumps.

---

## Direct Dependencies (Clean — No CVEs)

All 33 direct runtime dependencies returned **zero OSV hits**:

| Dependency | Version | Status |
|------------|---------|--------|
| androidx.appcompat:appcompat | 1.6.1 | ✅ Clean |
| androidx.core:core-ktx | 1.13.1 | ✅ Clean |
| androidx.lifecycle:lifecycle-* | 2.8.3 | ✅ Clean |
| androidx.activity:activity-compose | 1.8.2 | ✅ Clean |
| androidx.compose.ui:ui | 1.5.4 | ✅ Clean |
| androidx.compose.material3:material3 | 1.1.2 | ✅ Clean |
| org.maplibre.gl:android-sdk | 12.3.1 | ✅ Clean |
| com.google.android.material:material | 1.12.0 | ✅ Clean |
| com.graphhopper:graphhopper-core | 6.2 | ✅ Clean (direct) |
| androidx.room:room-* | 2.6.1 | ✅ Clean |
| net.zetetic:android-database-sqlcipher | 4.5.4 | ⚠️ Deprecated |
| com.google.dagger:hilt-android | 2.51.1 | ✅ Clean |
| com.google.code.gson:gson | 2.10.1 | ✅ Clean |
| androidx.work:work-runtime-ktx | 2.9.0 | ✅ Clean |
| com.squareup.okhttp3:okhttp | 4.12.0 | ✅ Clean |
| com.github.luben:zstd-jni | 1.5.5-11 | ✅ Clean |
| junit:junit | 4.13.2 | ✅ Clean |
| org.json:json | 20231013 | ✅ Clean |
| org.robolectric:robolectric | 4.13 | ✅ Clean |

---

*Report methodology: OSV batch API queried all 33 direct deps; deps.dev used to enumerate transitive deps of GraphHopper 6.2; each transitive dep queried individually against OSV; NVD queried for CVSS score confirmation. OWASP Dependency-Check CLI was not available; analysis was performed via OSV/NVD APIs directly.*
