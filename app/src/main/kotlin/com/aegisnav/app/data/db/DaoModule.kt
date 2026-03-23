package com.aegisnav.app.data.db

import com.aegisnav.app.baseline.BaselineDao
import com.aegisnav.app.data.dao.ALPRBlocklistDao
import com.aegisnav.app.data.dao.DebugLogDao
import com.aegisnav.app.data.dao.IgnoreListDao
import com.aegisnav.app.data.dao.RedLightCameraDao
import com.aegisnav.app.data.dao.ReportsDao
import com.aegisnav.app.data.dao.SavedLocationDao
import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.dao.SpeedCameraDao
import com.aegisnav.app.data.dao.ThreatEventDao
import com.aegisnav.app.data.dao.WatchlistDao
import com.aegisnav.app.data.dao.SavedRouteDao
import com.aegisnav.app.flock.FlockSightingDao
import com.aegisnav.app.intelligence.FollowingEventDao
import com.aegisnav.app.police.OfficerUnitDao
import com.aegisnav.app.police.PoliceSightingDao
import com.aegisnav.app.tracker.BeaconSightingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {

    @Provides @Singleton
    fun provideReportsDao(db: AppDatabase): ReportsDao = db.reportsDao()

    @Provides @Singleton
    fun provideALPRBlocklistDao(db: AppDatabase): ALPRBlocklistDao = db.alprBlocklistDao()

    @Provides @Singleton
    fun provideScanLogDao(db: AppDatabase): ScanLogDao = db.scanLogDao()

    @Provides @Singleton
    fun provideIgnoreListDao(db: AppDatabase): IgnoreListDao = db.ignoreListDao()

    @Provides @Singleton
    fun provideThreatEventDao(db: AppDatabase): ThreatEventDao = db.threatEventDao()

    @Provides @Singleton
    fun provideFlockSightingDao(db: AppDatabase): FlockSightingDao = db.flockSightingDao()

    @Provides @Singleton
    fun providePoliceSightingDao(db: AppDatabase): PoliceSightingDao = db.policeSightingDao()

    @Provides @Singleton
    fun provideRedLightCameraDao(db: AppDatabase): RedLightCameraDao = db.redLightCameraDao()

    @Provides @Singleton
    fun provideSpeedCameraDao(db: AppDatabase): SpeedCameraDao = db.speedCameraDao()

    @Provides @Singleton
    fun provideSavedLocationDao(db: AppDatabase): SavedLocationDao = db.savedLocationDao()

    // ── Phase 2B ──────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideBaselineDao(db: AppDatabase): BaselineDao = db.baselineDao()

    // ── Phase 4 (CYT-NG Intelligence) ─────────────────────────────────────────
    @Provides @Singleton
    fun provideFollowingEventDao(db: AppDatabase): FollowingEventDao = db.followingEventDao()

    // ── Phase 3 (Tracker Type ID + Beacon History) ────────────────────────────
    @Provides @Singleton
    fun provideBeaconSightingDao(db: AppDatabase): BeaconSightingDao = db.beaconSightingDao()

    // ── Audit fix 4.1/4.2: encrypted debug log ────────────────────────────────
    @Provides @Singleton
    fun provideDebugLogDao(db: AppDatabase): DebugLogDao = db.debugLogDao()

    // ── Officer unit tracking ──────────────────────────────────────────────────
    @Provides @Singleton
    fun provideOfficerUnitDao(db: AppDatabase): OfficerUnitDao = db.officerUnitDao()

    // ── Watchlist ─────────────────────────────────────────────────────────────
    @Provides @Singleton
    fun provideWatchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()

    // ── Saved multi-stop routes ───────────────────────────────────────────────
    @Provides @Singleton
    fun provideSavedRouteDao(db: AppDatabase): SavedRouteDao = db.savedRouteDao()
}
