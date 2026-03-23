package com.aegisnav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Initialization popup - shown once ever (gated by init_popup_shown_v1 prefs flag)
@Composable
fun InitPopup(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("⚙️ Device Initializing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scanners are warming up. Please wait approximately 45 seconds before acting on scan results.")
                Text(
                    "After 30 seconds of scanning you will be prompted to review and ignore any known safe devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) { Text("Got it") }
        }
    )
}
