package com.aegisnav.app.data.db

import android.content.Context
import com.aegisnav.app.util.AppLog
import androidx.room.Room
import com.aegisnav.app.correlation.CorrelationEngine
import com.aegisnav.app.security.DatabaseKeyManager
import com.aegisnav.app.security.KeystoreAuthRequiredException
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.dao.IgnoreListDao
import com.aegisnav.app.data.dao.ReportsDao
import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.dao.ThreatEventDao
import com.aegisnav.app.data.dao.SavedLocationDao
import com.aegisnav.app.data.model.IgnoreListEntry
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.ScanLogRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.data.repository.SavedLocationRepository
import com.aegisnav.app.flock.FlockDetector
import com.aegisnav.app.flock.FlockReportingCoordinator
import com.aegisnav.app.flock.FlockSightingDao
import com.aegisnav.app.police.OfficerUnitDao
import com.aegisnav.app.police.PoliceSightingDao
import com.aegisnav.app.data.dao.RedLightCameraDao
import com.aegisnav.app.data.dao.SpeedCameraDao
import com.aegisnav.app.p2p.P2PManager
import com.aegisnav.app.data.repository.AppPreferencesRepository
import com.aegisnav.app.geocoder.OfflineGeocoderRepository
import com.aegisnav.app.baseline.BaselineDao
import com.aegisnav.app.baseline.BaselineEngine
import com.aegisnav.app.data.dao.DebugLogDao
import com.aegisnav.app.signal.RssiDistanceEstimator
import com.aegisnav.app.signal.SignalTriangulator
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Named
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import com.aegisnav.app.di.ApplicationScope
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * True when the app started but device auth was required to unlock the Keystore key.
     * MainActivity observes this and prompts for credentials, then restarts the process.
     */
    @Volatile var authRequired: Boolean = false
        private set

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        val passphrase = try {
            DatabaseKeyManager.getOrCreatePassphrase(context)
        } catch (e: KeystoreAuthRequiredException) {
            AppLog.w("DatabaseModule", "Keystore auth required - using in-memory DB until authenticated")
            authRequired = true
            // Return a temporary in-memory DB so the process doesn't crash.
            // MainActivity will detect authRequired and prompt for credentials, then restart.
            return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .fallbackToDestructiveMigration()
                .build()
        }
        val factory = SupportOpenHelperFactory(passphrase)
        passphrase.fill(0)  // zero key material immediately - don't leave it on the heap
        val dbFile = context.getDatabasePath("aegisnav_database")

        // If an existing plaintext DB is present (upgrade from pre-SQLCipher build),
        // delete it so SQLCipher can create a fresh encrypted database.
        if (dbFile.exists()) {
            try {
                // Quick check: attempt to read the SQLite magic header bytes
                // A valid SQLite file starts with "SQLite format 3\000"
                // SQLCipher-encrypted files do NOT have this header - they look random
                val header = ByteArray(16)
                dbFile.inputStream().use { it.read(header) }
                val magic = "SQLite format 3\u0000"
                if (String(header) == magic) {
                    AppLog.w("DatabaseModule", "Plaintext DB detected - deleting for SQLCipher migration")
                    dbFile.delete()
                    context.deleteDatabase("aegisnav_database")
                }
            } catch (e: Exception) {
                AppLog.w("DatabaseModule", "Can't read DB header, deleting for safety: ${e.message}")
                context.deleteDatabase("aegisnav_database")
            }
        }

        return Room.databaseBuilder(context, AppDatabase::class.java, "aegisnav_database")
            .openHelperFactory(factory)
            .addMigrations(
                AppDatabase.MIGRATION_6_7,  AppDatabase.MIGRATION_7_8,  AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17,
                // Phase 2B
                AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19,
                // Phase 4 (CYT-NG Intelligence)
                AppDatabase.MIGRATION_19_20,
                // Phase 3 (Tracker Type ID + Beacon History)
                AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22,
                // Audit fix 4.1/4.2: encrypted debug log
                AppDatabase.MIGRATION_22_23,
                // Police alert consolidation (deviceCount/deviceMacs)
                AppDatabase.MIGRATION_23_24,
                // Officer units + sighting verdict/officerUnitId
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26,
                // Officer units: lastConfirmTimestamp for 1-hour confirm expiry
                AppDatabase.MIGRATION_26_27,
                // Watchlist: user-defined "always alert" MAC addresses
                AppDatabase.MIGRATION_27_28
            )
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
            .build()
    }

    // ── Phase 2B ──────────────────────────────────────────────────────────────
    @Provides @Singleton fun provideBaselineDao(db: AppDatabase): BaselineDao = db.baselineDao()

    // ── Phase 4 (CYT-NG Intelligence) ─────────────────────────────────────────
    @Provides @Singleton fun provideFollowingEventDao(db: AppDatabase): com.aegisnav.app.intelligence.FollowingEventDao = db.followingEventDao()

    // ── Phase 3 (Tracker Type ID + Beacon History) ────────────────────────────
    @Provides @Singleton
    fun provideBeaconSightingDao(db: AppDatabase): com.aegisnav.app.tracker.BeaconSightingDao =
        db.beaconSightingDao()

    @Provides @Singleton
    fun provideTrackerTypeClassifier(
        @ApplicationContext context: Context,
        secureLogger: com.aegisnav.app.util.SecureLogger
    ): com.aegisnav.app.tracker.TrackerTypeClassifier =
        com.aegisnav.app.tracker.TrackerTypeClassifier(context, secureLogger)

    @Provides @Singleton
    fun provideAlertDeduplicationManager(): com.aegisnav.app.tracker.AlertDeduplicationManager =
        com.aegisnav.app.tracker.AlertDeduplicationManager()

    @Provides @Singleton
    fun provideBaselineEngine(
        baselineDao: BaselineDao,
        @ApplicationScope scope: CoroutineScope
    ): BaselineEngine = BaselineEngine(baselineDao, scope)

    @Provides @Singleton
    fun provideRssiDistanceEstimator(): RssiDistanceEstimator = RssiDistanceEstimator()

    @Provides @Singleton
    fun provideSignalTriangulator(): SignalTriangulator = SignalTriangulator()

    // ── Audit fix 4.1/4.2: encrypted debug log ────────────────────────────────
    @Provides @Singleton fun provideDebugLogDao(db: AppDatabase): DebugLogDao = db.debugLogDao()

    // ── Officer unit tracking ──────────────────────────────────────────────────
    @Provides
    @Singleton
    fun provideOfficerUnitDao(db: AppDatabase): OfficerUnitDao = db.officerUnitDao()

    // ── Watchlist ─────────────────────────────────────────────────────────────
    @Provides
    @Singleton
    fun provideWatchlistDao(db: AppDatabase): com.aegisnav.app.data.dao.WatchlistDao = db.watchlistDao()

    @Provides
    @Singleton
    fun provideWatchlistAlertManager(
        watchlistDao: com.aegisnav.app.data.dao.WatchlistDao,
        threatEventRepository: ThreatEventRepository,
        alertTtsManager: com.aegisnav.app.util.AlertTtsManager
    ): com.aegisnav.app.watchlist.WatchlistAlertManager =
        com.aegisnav.app.watchlist.WatchlistAlertManager(watchlistDao, threatEventRepository, alertTtsManager)

    // ── Existing DAOs ─────────────────────────────────────────────────────────
    @Provides @Singleton fun provideReportsDao(db: AppDatabase): ReportsDao = db.reportsDao()
    @Provides @Singleton fun provideALPRBlocklistDao(db: AppDatabase): ALPRBlocklistDao = db.alprBlocklistDao()
    @Provides @Singleton fun provideScanLogDao(db: AppDatabase): ScanLogDao = db.scanLogDao()
    @Provides @Singleton fun provideIgnoreListDao(db: AppDatabase): IgnoreListDao = db.ignoreListDao()
    @Provides @Singleton fun provideThreatEventDao(db: AppDatabase): ThreatEventDao = db.threatEventDao()
    @Provides @Singleton fun provideFlockSightingDao(db: AppDatabase): FlockSightingDao = db.flockSightingDao()
    @Provides @Singleton fun providePoliceSightingDao(db: AppDatabase): PoliceSightingDao = db.policeSightingDao()
    @Provides @Singleton fun provideRedLightCameraDao(db: AppDatabase): RedLightCameraDao = db.redLightCameraDao()
    @Provides @Singleton fun provideSpeedCameraDao(db: AppDatabase): SpeedCameraDao = db.speedCameraDao()
    @Provides @Singleton fun provideSavedLocationDao(db: AppDatabase): SavedLocationDao = db.savedLocationDao()
    @Provides @Singleton fun provideThreatEventRepository(dao: ThreatEventDao): ThreatEventRepository = ThreatEventRepository(dao)
    @Provides @Singleton fun provideSavedLocationRepository(dao: SavedLocationDao): SavedLocationRepository = SavedLocationRepository(dao)
    @Provides @Singleton fun provideIgnoreListRepository(dao: IgnoreListDao): IgnoreListRepository = IgnoreListRepository(dao)

    @Provides @Singleton
    fun provideReportsRepository(dao: ReportsDao): ReportsRepository = ReportsRepository(dao)

    @Provides @Singleton
    fun provideScanLogRepository(dao: ScanLogDao): ScanLogRepository = ScanLogRepository(dao)

    @Provides @Singleton
    fun provideP2PManager(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): P2PManager = P2PManager(context, scope)

    @Provides @Singleton
    fun provideFlockDetector(
        @ApplicationContext context: Context,
        flockSightingDao: FlockSightingDao,
        @ApplicationScope scope: CoroutineScope
    ): FlockDetector = FlockDetector(context, flockSightingDao, scope)

    @Provides @Singleton
    fun provideFlockReportingCoordinator(
        @ApplicationContext context: Context,
        flockDetector: FlockDetector,
        reportsRepository: ReportsRepository,
        p2pManager: P2PManager,
        @ApplicationScope scope: CoroutineScope
    ): FlockReportingCoordinator = FlockReportingCoordinator(context, flockDetector, reportsRepository, p2pManager, scope)

    @Provides @Singleton
    fun provideAppPreferencesRepository(
        @ApplicationContext context: Context,
        @Named("app_prefs") appDataStore: DataStore<Preferences>,
        @Named("state_prefs") stateDataStore: DataStore<Preferences>
    ): AppPreferencesRepository =
        AppPreferencesRepository(context, appDataStore, stateDataStore)

    @Provides @Singleton
    fun provideOfflineGeocoderRepository(@ApplicationContext context: Context): OfflineGeocoderRepository =
        OfflineGeocoderRepository(context)

    @Provides @Singleton
    fun provideCorrelationEngine(
        scanLogRepository: ScanLogRepository,
        reportsRepository: ReportsRepository,
        p2pManager: P2PManager,
        @ApplicationScope scope: CoroutineScope
    ): CorrelationEngine = CorrelationEngine(
        scanLogRepository = scanLogRepository,
        reportsRepository = reportsRepository,
        p2pManager = p2pManager,
        scope = scope
    )
}
