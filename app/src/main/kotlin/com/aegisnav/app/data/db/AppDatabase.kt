package com.aegisnav.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aegisnav.app.baseline.BaselineDao
import com.aegisnav.app.baseline.BaselineDevice
import com.aegisnav.app.intelligence.FollowingEvent
import com.aegisnav.app.intelligence.FollowingEventDao
import com.aegisnav.app.data.dao.ReportsDao
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.dao.IgnoreListDao
import com.aegisnav.app.data.dao.ThreatEventDao
import com.aegisnav.app.data.dao.SavedLocationDao
import com.aegisnav.app.data.model.Report
import com.aegisnav.app.data.model.ALPRBlocklist
import com.aegisnav.app.data.model.ScanLog
import com.aegisnav.app.data.model.IgnoreListEntry
import com.aegisnav.app.data.model.ThreatEvent
import com.aegisnav.app.data.model.SavedLocation
import com.aegisnav.app.flock.FlockSighting
import com.aegisnav.app.flock.FlockSightingDao
import com.aegisnav.app.police.OfficerUnit
import com.aegisnav.app.police.OfficerUnitDao
import com.aegisnav.app.police.PoliceSighting
import com.aegisnav.app.police.PoliceSightingDao
import com.aegisnav.app.data.model.RedLightCamera
import com.aegisnav.app.data.model.SpeedCamera
import com.aegisnav.app.data.dao.RedLightCameraDao
import com.aegisnav.app.data.dao.SpeedCameraDao
import com.aegisnav.app.tracker.BeaconSighting
import com.aegisnav.app.tracker.BeaconSightingDao
import com.aegisnav.app.data.model.DebugLogEntry
import com.aegisnav.app.data.dao.DebugLogDao
import com.aegisnav.app.data.model.WatchlistEntry
import com.aegisnav.app.data.dao.WatchlistDao
import com.aegisnav.app.data.model.SavedRoute
import com.aegisnav.app.data.dao.SavedRouteDao

@Database(
    entities = [
        Report::class,
        ALPRBlocklist::class,
        ScanLog::class,
        IgnoreListEntry::class,
        ThreatEvent::class,
        FlockSighting::class,
        SavedLocation::class,
        PoliceSighting::class,
        RedLightCamera::class,
        SpeedCamera::class,
        // Phase 2B new entities
        BaselineDevice::class,
        // Phase 4 (CYT-NG Intelligence)
        FollowingEvent::class,
        // Phase 3 (Tracker Type ID + Beacon History)
        BeaconSighting::class,
        // Audit fix 4.1/4.2: encrypted sensitive log storage
        DebugLogEntry::class,
        // Officer unit tracking
        OfficerUnit::class,
        // Watchlist: user-defined "always alert" MAC addresses
        WatchlistEntry::class,
        // Saved multi-stop routes
        SavedRoute::class
    ],
    version = 31,          // 17→18 (baseline) | 18→19 (police dist) | 19→20 (following_events) | 20→21 (trackerType, permanent) | 21→22 (beacon_sightings) | 22→23 (debug_log) | 23→24 (police deviceCount/deviceMacs consolidation) | 24→25 (officer_units, sighting verdict/officerUnitId) | 25→26 (officer_units verdict locking) | 26→27 (officer_units lastConfirmTimestamp) | 27→28 (watchlist) | 28→29 (following_events unique constraint) | 29→30 (saved_routes) | 30→31 (baseline_devices.frequency Int→Long, saved_routes index)
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reportsDao(): ReportsDao
    abstract fun alprBlocklistDao(): ALPRBlocklistDao
    abstract fun scanLogDao(): ScanLogDao
    abstract fun ignoreListDao(): IgnoreListDao
    abstract fun threatEventDao(): ThreatEventDao
    abstract fun flockSightingDao(): FlockSightingDao
    abstract fun savedLocationDao(): SavedLocationDao
    abstract fun policeSightingDao(): PoliceSightingDao
    abstract fun redLightCameraDao(): RedLightCameraDao
    abstract fun speedCameraDao(): SpeedCameraDao
    // Phase 2B
    abstract fun baselineDao(): BaselineDao
    // Phase 4 (CYT-NG Intelligence)
    abstract fun followingEventDao(): FollowingEventDao
    // Phase 3 (Beacon History)
    abstract fun beaconSightingDao(): BeaconSightingDao
    // Audit fix 4.1/4.2: encrypted sensitive log storage
    abstract fun debugLogDao(): DebugLogDao
    // Officer unit tracking
    abstract fun officerUnitDao(): OfficerUnitDao
    // Watchlist: user-defined "always alert" MAC addresses
    abstract fun watchlistDao(): WatchlistDao
    // Saved multi-stop routes
    abstract fun savedRouteDao(): SavedRouteDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE alpr_blocklist ADD COLUMN source TEXT NOT NULL DEFAULT 'OSM'")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS threat_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        mac TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        detailJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS flock_sightings (
                        id TEXT NOT NULL PRIMARY KEY,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        matchedSignals TEXT NOT NULL,
                        reported INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reports ADD COLUMN sub_option TEXT")
                database.execSQL("ALTER TABLE reports ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE scan_logs ADD COLUMN ssid TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        type TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE scan_logs ADD COLUMN serviceUuids TEXT")
                database.execSQL("ALTER TABLE scan_logs ADD COLUMN deviceName TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reports ADD COLUMN user_verdict TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE alpr_blocklist ADD COLUMN state TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE red_light_cameras ADD COLUMN state TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE speed_cameras ADD COLUMN state TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS red_light_cameras (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        desc TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'OSM',
                        verified INTEGER NOT NULL DEFAULT 1,
                        reported INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_redlight_lat_lon ON red_light_cameras(lat, lon)"
                )
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS speed_cameras (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        desc TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'OSM',
                        verified INTEGER NOT NULL DEFAULT 1,
                        reported INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_speed_lat_lon ON speed_cameras(lat, lon)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS police_sightings (
                        id TEXT PRIMARY KEY NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        matchedSignals TEXT NOT NULL,
                        detectionCategory TEXT NOT NULL,
                        reported INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // ── Phase 2B migrations ───────────────────────────────────────────────

        /**
         * v18: Add [baseline_devices] table for Baseline Environment Learning (2.4).
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS baseline_devices (
                        mac TEXT NOT NULL,
                        zoneId TEXT NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        frequency INTEGER NOT NULL DEFAULT 1,
                        typicalHoursJson TEXT NOT NULL DEFAULT '[]',
                        PRIMARY KEY(mac, zoneId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_baseline_zone ON baseline_devices(zoneId)"
                )
            }
        }

        /**
         * v19: Add [estimated_distance_meters] column to [police_sightings] (2.5).
         * Nullable REAL, defaults to NULL for existing rows.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE police_sightings ADD COLUMN estimated_distance_meters REAL"
                )
            }
        }

        /**
         * v20: Add [following_events] table for Phase 4.2 Multi-Location Following Detector.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS following_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        mac TEXT NOT NULL,
                        firstZoneId TEXT NOT NULL,
                        secondZoneId TEXT NOT NULL,
                        firstSeenAtA INTEGER NOT NULL,
                        lastSeenAtA INTEGER NOT NULL,
                        firstSeenAtB INTEGER NOT NULL,
                        distanceMeters REAL NOT NULL,
                        transitionMinutes REAL NOT NULL,
                        followingType TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_following_mac ON following_events(mac)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_following_timestamp ON following_events(timestamp)"
                )
            }
        }

        // ── Phase 3 migrations (v0.5) ─────────────────────────────────────────

        /**
         * v21: Add [trackerType] to scan_logs and [permanent] to ignore_list (Phase 3.1, 3.9).
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE scan_logs ADD COLUMN trackerType TEXT")
                database.execSQL(
                    "ALTER TABLE ignore_list ADD COLUMN permanent INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v22: Create [beacon_sightings] table for BeaconHistoryManager (Phase 3.6).
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS beacon_sightings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mac TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        rssi INTEGER NOT NULL,
                        lat REAL,
                        lng REAL,
                        trackerType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        riskScore REAL NOT NULL DEFAULT 0.0,
                        confirmedTracker INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_beacon_mac ON beacon_sightings(mac)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_beacon_timestamp ON beacon_sightings(timestamp)"
                )
            }
        }

        /**
         * v23: Create [debug_log] table for SecureLogger encrypted MAC/sensitive log storage (Audit 4.1/4.2).
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `debug_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL, `level` TEXT NOT NULL)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `idx_debug_log_timestamp` ON `debug_log` (`timestamp`)")
            }
        }

        /**
         * v24: Add [deviceCount] and [deviceMacs] columns to [police_sightings] for
         * per-intersection alert consolidation (reduces spam — one grouped alert per cell).
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE police_sightings ADD COLUMN deviceCount INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE police_sightings ADD COLUMN deviceMacs TEXT")
            }
        }

        /**
         * v25: Create [officer_units] table; add [userVerdict], [verdictDeadlineMs],
         * [officerUnitId] columns to [police_sightings].
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS officer_units (
                        unitId TEXT NOT NULL PRIMARY KEY,
                        macSet TEXT NOT NULL,
                        firstSeenMs INTEGER NOT NULL,
                        lastSeenMs INTEGER NOT NULL,
                        confirmCount INTEGER NOT NULL DEFAULT 0
                    )
                """)
                database.execSQL("ALTER TABLE police_sightings ADD COLUMN userVerdict TEXT")
                database.execSQL("ALTER TABLE police_sightings ADD COLUMN verdictDeadlineMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE police_sightings ADD COLUMN officerUnitId TEXT")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE officer_units ADD COLUMN userConfirmTapCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE officer_units ADD COLUMN userDismissTapCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE officer_units ADD COLUMN verdictLocked INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE officer_units ADD COLUMN lockedVerdict TEXT")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE officer_units ADD COLUMN lastConfirmTimestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS watchlist (
                        mac TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        label TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v29: Add unique constraint on following_events(mac, firstZoneId, secondZoneId)
         * to prevent unbounded duplicate inserts from periodic analysis runs.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_following_events_mac_firstZoneId_secondZoneId` ON following_events(mac, firstZoneId, secondZoneId)"
                )
            }
        }

        /**
         * v30: Add [saved_routes] table for user-saved multi-stop routes.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_routes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, stopsJson TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v31: Change baseline_devices.frequency from INTEGER (Int) to INTEGER (Long).
         * Also adds index on saved_routes.createdAt.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Recreate baseline_devices with frequency as INTEGER (Long-compatible)
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS baseline_devices_new (
                        mac TEXT NOT NULL,
                        zoneId TEXT NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        frequency INTEGER NOT NULL,
                        typicalHoursJson TEXT NOT NULL,
                        PRIMARY KEY(mac, zoneId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "INSERT INTO baseline_devices_new SELECT mac, zoneId, firstSeen, lastSeen, CAST(frequency AS INTEGER), typicalHoursJson FROM baseline_devices"
                )
                database.execSQL("DROP TABLE baseline_devices")
                database.execSQL("ALTER TABLE baseline_devices_new RENAME TO baseline_devices")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_baseline_zone ON baseline_devices(zoneId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_saved_routes_created ON saved_routes(createdAt)"
                )
            }
        }
    }
}
