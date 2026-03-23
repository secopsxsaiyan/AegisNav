package com.aegisnav.app.ui

import androidx.compose.runtime.Composable
import com.aegisnav.app.FirstLaunchIgnoreDialog
import com.aegisnav.app.data.model.ScanLog

@Composable
fun IgnoreWizardHost(
    show: Boolean,
    sessionDevicesSeen: List<ScanLog>,
    onIgnoreAll: (List<String>) -> Unit,
    onIgnoreSelected: (List<String>) -> Unit,
    onSkip: () -> Unit
) {
    if (!show) return
    FirstLaunchIgnoreDialog(
        deviceLogs = sessionDevicesSeen,
        onIgnoreAll = onIgnoreAll,
        onIgnoreSelected = onIgnoreSelected,
        onSkip = onSkip
    )
}
