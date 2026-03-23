# tech-stack.md — AegisNav

## Platform
- **OS**: Android
- **minSdk**: 31 (Android 12)
- **targetSdk / compileSdk**: 36 (Android 16)
- **JVM target**: 17

## Languages
- **Kotlin** (primary) — 147 source files, 102 test files
- **Python** (tooling) — data pipeline scripts in `tools/`

## Build System
- **AGP**: 9.1.0
- **Gradle**: 9.4.0
- **Kotlin**: 2.3.20
- **KSP**: 2.3.6

## UI
- **Jetpack Compose**: 1.10.5
- **Material3**: 1.4.0
- **Compose compiler plugin**: 2.3.20
- **Material Icons Extended**: 1.7.8

### Compose Crash Notes (PERMANENT)
Old Compose 1.5.4 + Material3 1.1.2 workarounds still in place:
- `ModalBottomSheet` → `Dialog + Surface`
- `CircularProgressIndicator` / `LinearProgressIndicator` → static alternatives
- `imePadding()` → `WindowInsets.ime.getBottom()` static read

**DO NOT upgrade Compose without full device test pass.**

## Architecture
- **DI**: Hilt 2.59.2
- **Database**: Room 2.8.4 (DB version 28) + SQLCipher 4.14.0 (AES-256)
- **Encrypted preferences**: Tink 1.20.0 (AES-256-GCM, Android Keystore)
- **DataStore**: 1.2.1
- **Lifecycle**: 2.10.0 (runtime-ktx, process, viewmodel-compose, runtime-compose)
- **WorkManager**: 2.11.1 (background scanning)
- **sqlite-framework**: 2.6.2

## Maps & Navigation
- **MapLibre Native Android**: 13.0.1-opengl
- **GraphHopper**: 6.2 (last Android-compatible version)
  - 3 routing profiles: `car` (fastest), `car_shortest`, `car_avoid_alpr`
  - All profiles: `turn_costs=true`, CH preparation enabled
  - GH transitive deps hardened: netty/protobuf/woodstox/httpclient excluded; jackson/commons/bouncycastle force-upgraded
- **Map format**: PMTiles (zstd decompression via zstd-jni 1.5.7-7)
- **Geocoder**: FTS4, 5 columns, 292MB, 1.99M entries

### Routing Profile Config (PERMANENT — must match graph)
```
Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true)
Profile("car_shortest").setVehicle("car").setWeighting("shortest").setTurnCosts(true)
Profile("car_avoid_alpr").setVehicle("car").setWeighting("fastest").setTurnCosts(true)
```
All 50 state graphs rebuilt 2026-03-19 with turnCosts=true + CH preparation.
**NEVER revert turnCosts to false** — on-device graphs require true. Mismatch = routing breaks.
Changing ANY profile field without rebuilding graphs → version hash mismatch → graph refuses to load.

## Networking
- **OkHttp**: 5.3.2 (tile downloads only — no runtime API calls)
- **Gson**: 2.13.2

## Security
- SQLCipher encrypted DB
- Tink encrypted DataStore
- FLAG_SECURE on MainActivity
- Network security config (cleartext blocked)
- allowBackup disabled
- ProGuard/R8 hardened (QV 96/100)
- Zip bomb + zip slip + path traversal protection
- All logging → AppLog (debug-gated, no bare `Log.*()`)

## Crash Reporting (DEV ONLY)
- **Sentry SDK**: 8.36.0 (core-only, no NDK)
- **Must be stripped before release mirror** — no crash reporting in public builds

## Testing
- **JUnit**: 4.13.2
- **Robolectric**: 4.14
- **Coroutines test**: 1.10.2
- **AndroidX Test**: core 1.6.1, ext-junit 1.2.1, runner 1.6.2
- **Room testing**: 2.8.4
- **Compose UI test**: 1.10.5
- **Kotlin test**: 2.3.20

## Infrastructure
- **Dev repo**: `tea.pasubis.com/o4o793uys/dev-AegisNav` (Gitea)
- **Backup repo**: `bk-dev-AegisNav` on Gitea
- **Release repo**: `WatchTheWatcher` (never touched without approval)
- **GitHub**: pending (`secopsxsaiyan`)
- **16KB page alignment**: AGP 9 native (`useLegacyPackaging = false`)
- **Desugaring**: desugar_jdk_libs 2.1.5

## Dev Devices
- **Samsung S21**: R3CNC0B1ZGX (Android 15, API 35)
- **Pixel Fold**: 4C041FDKD000NJ (Android 16, API 36)
  - Requires Location ON: `adb shell settings put secure location_mode 3`

## Data Pipeline (tools/)
- `build_all_states.sh` — builds routing graphs for all 50 states
- `sync_surveillance_data.py` — downloads/merges ALPR data (OSM/EFF/ArcGIS)
- `build_geocoder.py` — builds FTS4 geocoder DB
- `build_routing_graph.sh` / `build_state_data.sh` — per-state build scripts

## Dependency Update Policy
- Force-upgrade vulnerable transitive deps (jackson, commons, bouncycastle, netty, protobuf, httpclient, kotlin-stdlib) via `resolutionStrategy.force()`
- Always run Snyk/security audit after dependency changes
