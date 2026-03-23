package com.aegisnav.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisnav.app.data.DataDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * ViewModel for [SettingsScreen].
 *
 * Owns:
 * - [selectedStates] — DataStore-backed set of selected ALPR state abbreviations
 * - [tileManifest] — loaded off-thread from [MapTileManifest]
 * - [installedStateCount] — number of downloaded state tile sets
 *
 * All DataStore/IO operations are run on [viewModelScope] so the composable
 * stays pure UI.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("state_prefs") private val stateDataStore: DataStore<Preferences>,
    @Named("alpr_prefs")  private val alprDataStore: DataStore<Preferences>
) : ViewModel() {

    private val SELECTED_STATES_KEY = stringSetPreferencesKey(PREF_SELECTED_STATES)
    private val ALPR_PRELOADED_KEY  = booleanPreferencesKey("alpr_preloaded_v5")

    // ── Selected ALPR states ──────────────────────────────────────────────────

    private val _selectedStates = MutableStateFlow<Set<String>>(emptySet())
    val selectedStates: StateFlow<Set<String>> = _selectedStates.asStateFlow()

    // ── Tile manifest ─────────────────────────────────────────────────────────

    private val _tileManifest = MutableStateFlow(TileManifest())
    val tileManifest: StateFlow<TileManifest> = _tileManifest.asStateFlow()

    // ── Installed state count ─────────────────────────────────────────────────

    private val _installedStateCount = MutableStateFlow(0)
    val installedStateCount: StateFlow<Int> = _installedStateCount.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            _selectedStates.value =
                stateDataStore.data.first()[SELECTED_STATES_KEY] ?: emptySet()
        }
        viewModelScope.launch(Dispatchers.IO) {
            _tileManifest.value = MapTileManifest.load(context)
        }
        refreshInstalledCount()
    }

    // ── Public operations ──────────────────────────────────────────────────────

    /**
     * Persist [abbrs] to DataStore and clear the ALPR preload flag so the new
     * states are loaded on the next launch.
     */
    fun saveSelectedStatesAndResetPreload(abbrs: Set<String>) {
        viewModelScope.launch {
            stateDataStore.edit { it[SELECTED_STATES_KEY] = abbrs }
            alprDataStore.edit { it.remove(ALPR_PRELOADED_KEY) }
            _selectedStates.value = abbrs
        }
    }

    /**
     * Re-count installed tile states. Call whenever the download sheet is
     * dismissed so the card subtitle stays accurate.
     */
    fun refreshInstalledCount() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedStateCount.value = DataDownloadManager.ALL_STATES.count {
                DataDownloadManager.isTilesInstalled(context, it.code)
            }
        }
    }
}
