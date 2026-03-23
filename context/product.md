# product.md — AegisNav

## One-Liner
Privacy-first Android navigation and surveillance-awareness tool — fully offline-capable.

## Problem Statement
Surveillance infrastructure (ALPR cameras, BLE trackers, police equipment) is pervasive and invisible to most people. No existing navigation app combines offline routing with real-time surveillance detection and privacy-by-design architecture.

## Solution
An Android app that provides:
- Offline turn-by-turn navigation with ALPR-avoidance routing
- Passive BLE + WiFi scanning to detect trackers, police equipment, and Flock/LPR cameras
- A national database of 78,460+ ALPR cameras across all 50 US states
- All data stays on-device — no telemetry, no cloud, no tracking

## Target Users
- **Privacy-conscious drivers** — people who want awareness of surveillance infrastructure
- **Civil liberties advocates** — researchers and activists monitoring public surveillance expansion
- **Security researchers** — technical users studying BLE/WiFi surveillance patterns

## Core Features (v2026.03.19)
- Offline map tiles (PMTiles, per-state, all 50 states)
- Offline turn-by-turn routing (GraphHopper, 3 profiles: fastest / shortest / avoid-ALPR)
- Offline geocoder (FTS4, 1.99M entries)
- ALPR / red light / speed camera proximity alerts
- BLE scanning: police equipment detection, Flock/LPR camera detection
- Tracker detection (AirTag, Tile, SmartTag, unknown BLE)
- Following/tailing detection (persistence scoring)
- Convoy detection (aligned velocity vectors)
- Coordinated surveillance detection (multi-device spatial correlation)
- Signal triangulation (BLE RSSI-based position estimation)
- P2P encrypted sync (Noise protocol, opt-in)
- SQLCipher + Tink encryption at rest
- Factory reset (wipes all user data)
- In-app state data download with progress tracking

## Success Metrics
- Zero network calls during normal operation (offline mode)
- All 50 US states with maps + routing + geocoder data
- 0 critical / 0 high security findings in release builds
- All unit tests passing before every commit

## Product Roadmap (High-Level)

### Current — GitHub Public Launch
- `gh auth login`, create public repo, push clean release
- Upload data assets, APK signing + keystore creation

### Phase 5
- Noise P2P encryption hardening
- Ignore list UI improvements
- Battery optimization exemption prompt

### Phase 6
- ArcGIS cross-reference for camera data
- State filtering for all camera types
- User ALPR submission

### Phase 9
- P2P backend sync infrastructure
- Public WSS relay deployment

### Phase 10
- F-Droid release
- CI/CD pipeline

### Deferred
- GraphHopper CH preparation (performance)
- Relay deployment
- Signature OTA updates
- Companion display app (Honda Civic head unit — WebSocket mirror)
- AGP 10+ migration

### Never
- Cell tower scanning — permanently rejected
- OSRM online fallback — removed (privacy violation)

## Legal / Identity
- **Package**: com.aegisnav.app
- **License**: MIT
- **Contact**: admin@aegisnav.com
- **GitHub**: secopsxsaiyan (pending public launch)
