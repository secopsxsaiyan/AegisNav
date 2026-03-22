# AegisNav

**v2026.03.21** — Privacy-first navigation and surveillance-awareness for Android.

This is the official open-source repository for AegisNav. The application runs in fully offline mode during the alpha release.

---

## ⚠️ Important Disclaimers

|                                 |                                                                                                                                                                                                                                                                                                                                                                                                                                       |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ⚠️ **PURPOSE**                  | This app is provided for educational, research, and personal privacy awareness purposes only. It is not intended, and should not be used, to assist in evading law enforcement, violating traffic laws, committing crimes, or interfering with any surveillance or public safety systems. The developer does not condone or encourage any unlawful use and is not responsible for how users apply the information or alerts provided. | 
| ⚠️ **ALPHA SOFTWARE**           | This is an early alpha. Expect bugs, missing features, and breaking changes. Not reliable in an emergency.                                                                                                                                                                                                                                                                                                                                                          |
| ⚠️ **NOT A SECURITY GUARANTEE** | AegisNav is an awareness tool, educational tool, not a security system. It does not guarantee detection of all surveillance equipment.                                                                                                                                                                                                                                                                                                |
| ⚠️ **FALSE POSITIVES**          | Detection is heuristic-based. False positives are common.                                                                                                                                                                                                                                                                                                                                                                             |
| ⚠️ **ROUTING ACCURACY**         | Navigation uses OSM data via GraphHopper. Routes may be inaccurate or outdated.                                                                                                                                                                                                                                                                                                                                                       |
| ⚠️ **CAMERA DATA**              | ALPR, red light, and speed camera data may be incomplete, outdated, or inaccurate.                                                                                                                                                                                                                                                                                                                                                    |
| ⚠️ **LEGAL**                    | Laws regarding surveillance detection, electronic device use in vehicles, or traffic enforcement aids vary by jurisdiction. You are solely responsible for ensuring your use complies with all applicable laws (federal, state, and local); the developer assumes no liability for any legal consequences.                                                                                                                            |
| ⚠️ **NO WARRANTY**              | Provided as-is. See LICENSE.                                                                                                                                                                                                                                                                                                                                                                                                          |
| ⚠️ **BATTERY**                  | Background BLE/WiFi scanning increases battery consumption.                                                                                                                                                                                                                                                                                                                                                                           |
| ⚠️ **LOCATION DATA**            | Stored locally only. No data leaves the device — AegisNav is purely offline.                                                                                                                                                                                                                                                                                                                                                          |
| ⚠️ **OFFLINE MODE**             | All maps, routing, search, and detection features operate completely offline after initial data download.                                                                                                                                                                                                                                                                                                                             |
| ⚠️ **INDEPENDENT**              | Open-source project with no affiliation to any law enforcement agency, camera vendor, or government entity.                                                                                                                                                                                                                                                                                                                           |
| ⚠️ **WIRELESS SCANNING**        | This app performs only passive, receive-only scanning of public Bluetooth and WiFi signals; it does not jam, interfere with, spoof, or actively disrupt any devices or networks, which would be prohibited under federal law (e.g., 47 U.S.C. § 302a).                                                                                                                                                                                |

---

## Features (v2026.03.21)

🗺️ Maps & Navigation

• Offline map tiles (PMTiles format, per-state)
• Multi-state support (all 50 US states)
• Active state selector (switch between downloaded states)
• In-app state data download (tiles + geocoder + routing)
• Manifest-driven download URLs with progress banner + notification
• Offline turn-by-turn routing 
• Multiple route profiles (fastest + shortest + avoid ALPR)
• Automatic rerouting
• Offline geocoder (address search with interpolation)
• Saved locations
• Multi-stop routing (1 waypoint max)
• Saved multi-stop routes
• Text-to-speech navigation
• Theme (auto + light + dark)
• Speed-based ETA from rolling average

📡 Scanning & Detection

• Init scan wizard offers to add found devices to ignore list
• BLE scanning (passive + active)
• WiFi scanning (passive)
• Scan intensity settings (low / aggressive)
• Scan intensity warning when aggressive is set
• MAC rotation grouping
• Infrastructure OUI devices tracked with LOW confidence
• Police equipment detection
• ALPR camera detection (BLE + WiFi patterns)
• ALPR camera proximity alerts (78,460+ cameras, all 50 states)
• Red light camera alerts (some not reliable at this time)
• Speed camera alerts (some not reliable at this time)
• Tracker detection (AirTag, Tile, SmartTag, unknown BLE trackers)
• Following/tailing detection (persistence scoring over time)
• Convoy detection (multiple vehicles with aligned velocity vectors)
• Coordinated surveillance detection (multi-device spatial correlation)
• Dual-path tracker detection (cluster-based and continous following)
• Signal triangulation (estimate device position from multiple BLE readings)

🔔 Alerts & UI

• Detection popup notifications (confirm / dismiss / auto-expire)
• Auto-expire after 15 seconds (unconfirmed state, no impact on 3-tap lock)
• 3-consecutive confirmation/dismissal locks officer unit verdicts
• Alert deduplication
• Text-to-speech alerts 
• Music duck to 10% for alerts and navigation
• Haptic feedback
• Threat history screen (full log of all detections)
• Report history screen
• Tracker detail screen
• ALPR sighting screen
• New report popup

🛡️ Privacy & Security

• Fully offline-capable (no network required)
• SQLCipher encrypted database (AES-256)
• Tink encrypted DataStore (AES-256-GCM, Android Keystore)
• FLAG_SECURE on MainActivity (no screenshots)
• MD5 checksum verification for all downloaded files (stored on github)
• allowBackup disabled
• No world-readable files
• Zip bomb + zip slip protection
• Path traversal sanitization
• Disk space pre-check and reserve before downloads
• All logging gated behind BuildConfig.DEBUG
• ProGuard/R8 hardened release builds


🔧 Settings & Configuration

• Privacy wizard (ignore list setup)
• Scan toggle (on/off)
• Scan intensity selector
• Active map state selector
• Data download manager
• Ignore list (dismissed devices)
• Watchlist (manually flagged devices)
• Offline mode toggle
• Factory reset (clear all data + re-trigger wizard)
• Battery optimization warning

📊 Data & History

• Beacon history (MAC tracking over time)
• Scan logs
• Threat events
• Cross-correlation engine (BLE + WiFi device linking)
• Coordinated surveillance detection (capped at 1000 device history)
• User-submitted ALPR cameras (added to local DB)
• All map tiles (`.pmtiles`), geocoder databases, and routing graphs for every state are downloadable directly inside the app from this GitHub repository. No manual sideloading or external tools are required.

---

## License & Attribution

AegisNav is open source under the **MIT License**. See [LICENSE](LICENSE).

No GPL, LGPL, or AGPL code is used. All third-party libraries use permissive licenses (MIT, Apache 2.0, BSD, etc.).

### Third-Party Libraries

* | Library                                                                                                         | License      |
* |-----------------------------------------------------------------------------------------------------------------|--------------|
* | [MapLibre GL Android](https://github.com/maplibre/maplibre-gl-native)                                           | BSD 2-Clause |
* | [GraphHopper 6.2](https://github.com/graphhopper/graphhopper)                                                   | Apache 2.0   |
* | [JTS Topology Suite](https://github.com/locationtech/jts) (via GraphHopper)                                     | BSD 3-Clause |
* | [Android Jetpack](https://developer.android.com/jetpack) (Room, Compose, Lifecycle, WorkManager)                | Apache 2.0   |
* | [Dagger / Hilt](https://github.com/google/dagger)                                                               | Apache 2.0   |
* | [Kotlin](https://kotlinlang.org)                                                                                | Apache 2.0   |
* | [SQLCipher for Android](https://www.zetetic.net/sqlcipher)                                                      | BSD-style    |
* | [OkHttp](https://square.github.io/okhttp)                                                                       | Apache 2.0   |
* | [Gson](https://github.com/google/gson)                                                                          | Apache 2.0   |
* | [zstd-jni](https://github.com/luben/zstd-jni)                                                                   | BSD 2-Clause |
* | [PMTiles](https://github.com/protomaps/PMTiles)                                                                 | BSD 3-Clause |
* | Material Components for Android                                                                                 | Apache 2.0   |
* | [Google Tink](https://github.com/tink-crypto/tink-java)                                                         | Apache 2.0   |
* | [Jackson](https://github.com/FasterXML/jackson) (via GraphHopper)                                               | Apache 2.0   |
* | [Apache Commons IO/Lang3](https://commons.apache.org) (via GraphHopper)                                         | Apache 2.0   |
* | [Bouncy Castle](https://www.bouncycastle.org) (via Tink)                                                        | MIT          |
*
### Data Sources

* | Source | License | Usage |
* |---|---|---|
* | [OpenStreetMap](https://www.openstreetmap.org/copyright) | ODbL | Map tiles, address data, routing graphs, red light & speed camera locations |
* | [EFF Atlas of Surveillance](https://atlasofsurveillance.org) | CC BY 4.0 | ALPR camera location cross-reference |

---

## Privacy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

---

## Contributing / Issues

Bug reports and feature requests: **https://github.com/secopsxsaiyan/AegisNav/issues**
