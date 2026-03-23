package com.aegisnav.app.data.db

import android.content.Context
import com.aegisnav.app.baseline.BaselineDao
import com.aegisnav.app.baseline.BaselineEngine
import com.aegisnav.app.correlation.CorrelationEngine
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.ScanLogRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.flock.FlockDetector
import com.aegisnav.app.flock.FlockReportingCoordinator
import com.aegisnav.app.flock.FlockSightingDao
import com.aegisnav.app.p2p.P2PManager
import com.aegisnav.app.signal.RssiDistanceEstimator
import com.aegisnav.app.signal.SignalTriangulator
import com.aegisnav.app.tracker.AlertDeduplicationManager
import com.aegisnav.app.tracker.TrackerTypeClassifier
import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.data.dao.WatchlistDao
import com.aegisnav.app.util.AlertTtsManager
import com.aegisnav.app.util.SecureLogger
import com.aegisnav.app.watchlist.WatchlistAlertManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DetectionModule {

    @Provides @Singleton
    fun provideFlockDetector(
        @ApplicationContext context: Context,
        flockSightingDao: FlockSightingDao,
        @ApplicationScope scope: CoroutineScope,
        crashReporter: CrashReporter
    ): FlockDetector = FlockDetector(context, flockSightingDao, scope, crashReporter)

    @Provides @Singleton
    fun provideFlockReportingCoordinator(
        @ApplicationContext context: Context,
        flockDetector: FlockDetector,
        reportsRepository: ReportsRepository,
        p2pManager: P2PManager,
        @ApplicationScope scope: CoroutineScope
    ): FlockReportingCoordinator =
        FlockReportingCoordinator(context, flockDetector, reportsRepository, p2pManager, scope)

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

    @Provides @Singleton
    fun provideBaselineEngine(
        baselineDao: BaselineDao,
        @ApplicationScope scope: CoroutineScope
    ): BaselineEngine = BaselineEngine(baselineDao, scope)

    @Provides @Singleton
    fun provideRssiDistanceEstimator(): RssiDistanceEstimator = RssiDistanceEstimator()

    @Provides @Singleton
    fun provideSignalTriangulator(): SignalTriangulator = SignalTriangulator()

    @Provides @Singleton
    fun provideTrackerTypeClassifier(
        @ApplicationContext context: Context,
        secureLogger: SecureLogger
    ): TrackerTypeClassifier = TrackerTypeClassifier(context, secureLogger)

    @Provides @Singleton
    fun provideAlertDeduplicationManager(): AlertDeduplicationManager = AlertDeduplicationManager()

    @Provides @Singleton
    fun provideWatchlistAlertManager(
        watchlistDao: WatchlistDao,
        threatEventRepository: ThreatEventRepository,
        alertTtsManager: AlertTtsManager,
        crashReporter: CrashReporter
    ): WatchlistAlertManager = WatchlistAlertManager(watchlistDao, threatEventRepository, alertTtsManager, crashReporter)
}
