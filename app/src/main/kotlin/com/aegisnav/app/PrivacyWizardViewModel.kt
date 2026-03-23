package com.aegisnav.app

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisnav.app.data.model.IgnoreListEntry
import com.aegisnav.app.data.repository.IgnoreListRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrivacyWizardViewModel @Inject constructor(
    private val ignoreListRepository: IgnoreListRepository
) : ViewModel() {

    val detectedDevices = mutableStateListOf<DetectedDevice>()
    val selectedAddresses = mutableStateListOf<String>()

    fun addDetected(address: String, label: String, type: String) {
        if (detectedDevices.none { it.address == address }) {
            detectedDevices.add(DetectedDevice(address, label, type))
        }
    }

    fun toggleSelection(address: String) {
        if (address in selectedAddresses) selectedAddresses.remove(address)
        else selectedAddresses.add(address)
    }

    fun addAll() {
        detectedDevices.forEach { device ->
            if (device.address !in selectedAddresses) selectedAddresses.add(device.address)
        }
    }

    fun saveIgnoreList() {
        viewModelScope.launch {
            selectedAddresses.forEach { addr ->
                val device = detectedDevices.firstOrNull { it.address == addr } ?: return@forEach
                ignoreListRepository.addAddress(
                    IgnoreListEntry(address = addr, type = device.type, label = device.label)
                )
            }
        }
    }
}

data class DetectedDevice(
    val address: String,
    val label: String,
    val type: String,
    var selected: Boolean = false
)
