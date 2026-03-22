## AegisNav Privacy Policy
Version: v2026.03.21 (Alpha – Offline Navigation)

Effective Date: March 15, 2026

App Package: com.aegisnav.app

## Overview
AegisNav is a privacy-first navigation and surveillance-awareness application. All routing and navigation runs fully offline using a locally-stored GraphHopper graph — the app makes no online routing calls. This policy explains precisely what data the app collects (all of which stays on your device), where it is stored, and what is never collected or transmitted.

AegisNav may connect to external networks in the following explicitly listed cases:
1. **Map/data downloads** — when you manually choose to download state maps, routing graphs, or related data files.
2. **Crash reporting (alpha builds only)** — anonymized crash reports are sent to a self-hosted GlitchTip instance at `glitchtip.pasubis.com` to help fix bugs during alpha development.
3. **P2P relay (optional)** — if you enable peer-to-peer features, the app connects to relay servers at `relay.aegisnav.com` / `relay.aegisnav.online` to facilitate encrypted device-to-device communication. This is opt-in and user-configurable.

No personal data, location data, or device identifiers are ever included by the app in download requests.
---

## Data Stored Locally On Your Device
AegisNav stores the following data locally on your device only. None of this data ever leaves your device.

## Map Downloads (Only Network Activity)

All maps, routing graphs, camera data, and related offline databases are downloaded manually by the user from public GitHub repositories.
The app itself sends no personal data, user-generated content, location data, sightings, reports, bookmarks, searches, or any device identifiers in the download requests.
However, as with any HTTPS connection to a web server, your IP address and standard HTTP headers (including a User-Agent string that identifies the app version and Android version) are transmitted to GitHub. This is unavoidable network metadata that GitHub receives and processes according to GitHub’s Privacy Policy.
AegisNav does not collect, store, or have access to any of this connection metadata.
Once downloaded, all files remain on your device and the app returns to fully offline operation.


## Data We Do Not Collect
AegisNav does not collect, transmit, or store any of the following:

Any user data on a remote server
Precise or coarse location data sent by the app
Device identifiers (IMEI, Android ID, advertising ID, MAC addresses, etc.)
Crash reports or analytics beyond what is described above (no Firebase, no Crashlytics, no advertising SDKs)
Usage statistics or telemetry
Personal information (name, email, phone number, etc.)
Contact lists, call logs, SMS, or any other sensitive device data
Photos, camera data, or microphone audio
Any data beyond what is explicitly described in the “Data Stored Locally” section above

Note on GitHub downloads: The only data that ever leaves the device is the standard, unavoidable HTTPS connection metadata described in the Map Downloads section. The app itself never sends or logs any personal or location information.

## Data Security

All locally stored threat, sighting, report, and location data is stored in encrypted Room databases using Android’s security libraries.
Saved locations and bookmarks are stored in an encrypted Room database.
The app uses Android’s FLAG_SECURE window flag to prevent screenshots of sensitive screens.
We recommend enabling Android’s full-disk encryption (enabled by default on modern Android devices).

## Third-Party Services
AegisNav contains no advertising SDKs, tracking libraries, or online routing services. All navigation routing is performed fully offline by a local GraphHopper engine.

External connections are limited to those described in the Overview:
- Public GitHub repositories / hosted files for user-initiated data downloads.
- Self-hosted GlitchTip (`glitchtip.pasubis.com`) for anonymized crash reports in alpha/dev builds.
- Optional P2P relay servers (`relay.aegisnav.com`, `relay.aegisnav.online`) when the user enables peer-to-peer features.

No commercial analytics or advertising services are used.

## Children’s Privacy
AegisNav is not directed at children under 13. We do not knowingly collect any personal information from children. If you believe a child has provided personal information through this app, please contact us and we will take steps to delete it.

## Changes to This Policy
We may update this Privacy Policy as the app’s features change. The version number and effective date at the top of this document will reflect the current version. We encourage you to review this policy with each significant app update.

## Contact
For privacy concerns, questions, or data deletion requests, please open an issue at:
https://github.com/secopsxsaiyan/AegisNav/issues

---

*AegisNav is open source software. You can review the full source code to verify the data practices described in this policy.*
