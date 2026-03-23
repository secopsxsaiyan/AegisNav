package com.aegisnav.app.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.aegisnav.app.data.dao.IgnoreListDao
import com.aegisnav.app.data.dao.ReportsDao
import com.aegisnav.app.data.dao.SavedLocationDao
import com.aegisnav.app.data.dao.ScanLogDao
import com.aegisnav.app.data.dao.ThreatEventDao
import com.aegisnav.app.data.repository.AppPreferencesRepository
import com.aegisnav.app.data.repository.IgnoreListRepository
import com.aegisnav.app.data.repository.ReportsRepository
import com.aegisnav.app.data.repository.SavedLocationRepository
import com.aegisnav.app.data.repository.ScanLogRepository
import com.aegisnav.app.data.repository.ThreatEventRepository
import com.aegisnav.app.geocoder.OfflineGeocoderRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides @Singleton
    fun provideReportsRepository(dao: ReportsDao): ReportsRepository = ReportsRepository(dao)

    @Provides @Singleton
    fun provideScanLogRepository(dao: ScanLogDao): ScanLogRepository = ScanLogRepository(dao)

    @Provides @Singleton
    fun provideThreatEventRepository(dao: ThreatEventDao): ThreatEventRepository = ThreatEventRepository(dao)

    @Provides @Singleton
    fun provideSavedLocationRepository(dao: SavedLocationDao): SavedLocationRepository = SavedLocationRepository(dao)

    @Provides @Singleton
    fun provideIgnoreListRepository(dao: IgnoreListDao): IgnoreListRepository = IgnoreListRepository(dao)

    @Provides @Singleton
    fun provideAppPreferencesRepository(
        @ApplicationContext context: Context,
        @Named("app_prefs") appDataStore: DataStore<Preferences>,
        @Named("state_prefs") stateDataStore: DataStore<Preferences>
    ): AppPreferencesRepository =
        AppPreferencesRepository(context, appDataStore, stateDataStore)

    @Provides @Singleton
    fun provideOfflineGeocoderRepository(
        @ApplicationContext context: Context
    ): OfflineGeocoderRepository = OfflineGeocoderRepository(context)
}
