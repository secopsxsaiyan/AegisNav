package com.aegisnav.app.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module that provides named [DataStore]<[Preferences]> singletons for every
 * SharedPreferences file used across the app.
 *
 * Consumers inject with: @Named("app_prefs") val ds: DataStore<Preferences>
 *
 * Covered prefs files (all 10 discovered in the codebase):
 *   app_prefs, an_prefs, state_prefs, popup_prefs, tracker_engine_prefs,
 *   alpr_prefs, tile_prefs, nav_prefs, redlight_prefs, speed_prefs
 */
@Module
@InstallIn(SingletonComponent::class)
object SecureDataStoreModule {

    // ── Core app / feature prefs (Part A — AppPreferencesRepository, MainViewModel) ──

    @Provides @Singleton @Named("app_prefs")
    fun provideAppPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "app_prefs")

    @Provides @Singleton @Named("an_prefs")
    fun provideAnPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "an_prefs")

    @Provides @Singleton @Named("state_prefs")
    fun provideStatePrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "state_prefs")

    @Provides @Singleton @Named("popup_prefs")
    fun providePopupPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "popup_prefs")

    // ── Tracker engine prefs (used by MainViewModel + TrackerDetectionEngine, Part C) ──

    @Provides @Singleton @Named("tracker_engine_prefs")
    fun provideTrackerEnginePrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "tracker_engine_prefs")

    // ── ALPR / surveillance prefs (Part B: PrivacyWizardScreen, SettingsScreen) ──

    @Provides @Singleton @Named("alpr_prefs")
    fun provideAlprPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "alpr_prefs")

    // ── Tile cache prefs (Part B: MainActivity) ──────────────────────────────

    @Provides @Singleton @Named("tile_prefs")
    fun provideTilePrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "tile_prefs")

    // ── Navigation prefs (Part C: NavigationViewModel) ───────────────────────

    @Provides @Singleton @Named("nav_prefs")
    fun provideNavPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "nav_prefs")

    // ── Red light / speed camera sync prefs (Part C: sync workers) ───────────

    @Provides @Singleton @Named("redlight_prefs")
    fun provideRedlightPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "redlight_prefs")

    @Provides @Singleton @Named("speed_prefs")
    fun provideSpeedPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SecureDataStore.get(context, "speed_prefs")
}
