package com.aegisnav.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aegisnav.app.routing.RoutePreference
import com.aegisnav.app.security.SecureDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton


@Singleton
class AppPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("app_prefs") private val appDataStore: DataStore<Preferences>,
    @Named("state_prefs") private val stateDataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Preference keys ───────────────────────────────────────────────────────

    companion object {
        val KEY_OFFLINE_MODE          = booleanPreferencesKey("offline_mode")
        val KEY_ROUTE_PREFERENCE      = stringPreferencesKey("route_preference")
        val KEY_TTS_MASTER            = booleanPreferencesKey("tts_master_enabled")
        val KEY_TTS_TRACKER           = booleanPreferencesKey("tts_tracker_enabled")
        val KEY_TTS_POLICE            = booleanPreferencesKey("tts_police_enabled")
        val KEY_TTS_SURVEILLANCE      = booleanPreferencesKey("tts_surveillance_enabled")
        val KEY_TTS_CONVOY            = booleanPreferencesKey("tts_convoy_enabled")
        val KEY_SELECTED_STATES       = stringSetPreferencesKey("selected_states")
    }

    // ── Blocking read helpers for init (before coroutine scope is warm) ──────

    // Called during class construction (field initializers) — must be synchronous.
    // Using runBlocking(Dispatchers.IO) ensures we never block the main thread;
    // Hilt creates @Singleton instances off the main thread during app init.
    private fun <T> readNow(key: Preferences.Key<T>, default: T): T =
        runBlocking(Dispatchers.IO) { appDataStore.data.first()[key] ?: default }

    // ── Offline Mode ──────────────────────────────────────────────────────────

    /**
     * Offline Mode - when true, the app sends and receives NO network data:
     *   - P2P relay disconnected; no report broadcasting or receiving
     *   - Navigation uses local GraphHopper graph only (no OSRM API)
     *   - Map glyph/font requests fail silently (no street labels)
     *
     * Default: true (offline - privacy-first; user opts in to online features)
     */
    private val _offlineMode = MutableStateFlow(readNow(KEY_OFFLINE_MODE, true))
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    fun setOfflineMode(enabled: Boolean) {
        _offlineMode.value = enabled
        scope.launch { appDataStore.edit { it[KEY_OFFLINE_MODE] = enabled } }
    }

    // ── Route preference (1.6 — Fastest vs Avoid ALPR) ───────────────────────

    /**
     * The user's selected route preference.
     * Default: FASTEST. Can be changed to AVOID_ALPR in the navigation sheet.
     */
    private val _routePreference = MutableStateFlow(
        RoutePreference.valueOf(
            readNow(KEY_ROUTE_PREFERENCE, RoutePreference.FASTEST.name)
        )
    )
    val routePreference: StateFlow<RoutePreference> = _routePreference.asStateFlow()

    fun setRoutePreference(pref: RoutePreference) {
        _routePreference.value = pref
        scope.launch { appDataStore.edit { it[KEY_ROUTE_PREFERENCE] = pref.name } }
    }

    // ── TTS preferences ────────────────────────────────────────────────────────

    private val _ttsMasterEnabled = MutableStateFlow(readNow(KEY_TTS_MASTER, true))
    val ttsMasterEnabled: StateFlow<Boolean> = _ttsMasterEnabled.asStateFlow()

    fun setTtsMasterEnabled(enabled: Boolean) {
        _ttsMasterEnabled.value = enabled
        scope.launch { appDataStore.edit { it[KEY_TTS_MASTER] = enabled } }
    }

    private val _ttsTrackerEnabled = MutableStateFlow(readNow(KEY_TTS_TRACKER, true))
    val ttsTrackerEnabled: StateFlow<Boolean> = _ttsTrackerEnabled.asStateFlow()

    fun setTtsTrackerEnabled(enabled: Boolean) {
        _ttsTrackerEnabled.value = enabled
        scope.launch { appDataStore.edit { it[KEY_TTS_TRACKER] = enabled } }
    }

    private val _ttsPoliceEnabled = MutableStateFlow(readNow(KEY_TTS_POLICE, true))
    val ttsPoliceEnabled: StateFlow<Boolean> = _ttsPoliceEnabled.asStateFlow()

    fun setTtsPoliceEnabled(enabled: Boolean) {
        _ttsPoliceEnabled.value = enabled
        scope.launch { appDataStore.edit { it[KEY_TTS_POLICE] = enabled } }
    }

    private val _ttsSurveillanceEnabled = MutableStateFlow(readNow(KEY_TTS_SURVEILLANCE, true))
    val ttsSurveillanceEnabled: StateFlow<Boolean> = _ttsSurveillanceEnabled.asStateFlow()

    fun setTtsSurveillanceEnabled(enabled: Boolean) {
        _ttsSurveillanceEnabled.value = enabled
        scope.launch { appDataStore.edit { it[KEY_TTS_SURVEILLANCE] = enabled } }
    }

    private val _ttsConvoyEnabled = MutableStateFlow(readNow(KEY_TTS_CONVOY, true))
    val ttsConvoyEnabled: StateFlow<Boolean> = _ttsConvoyEnabled.asStateFlow()

    fun setTtsConvoyEnabled(enabled: Boolean) {
        _ttsConvoyEnabled.value = enabled
        scope.launch { appDataStore.edit { it[KEY_TTS_CONVOY] = enabled } }
    }

    /**
     * Returns the user's selected US states as a reactive Flow<List<String>>.
     * States are stored as a StringSet in "state_prefs" / KEY_SELECTED_STATES.
     * Re-emits whenever the DataStore changes.
     */
    fun getSelectedStates(): Flow<List<String>> =
        stateDataStore.data.map { prefs ->
            (prefs[KEY_SELECTED_STATES] ?: emptySet()).toList()
        }

    /**
     * Persist a new set of selected US states.
     */
    fun setSelectedStates(states: Set<String>) {
        scope.launch { stateDataStore.edit { it[KEY_SELECTED_STATES] = states } }
    }
}
