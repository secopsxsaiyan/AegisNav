package com.aegisnav.app.flock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlockViewModel @Inject constructor(
    private val flockSightingDao: FlockSightingDao
) : ViewModel() {

    /** All flock sightings, newest first */
    val sightings: StateFlow<List<FlockSighting>> = flockSightingDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markReported(id: String) {
        viewModelScope.launch {
            flockSightingDao.markReported(id)
        }
    }
}
