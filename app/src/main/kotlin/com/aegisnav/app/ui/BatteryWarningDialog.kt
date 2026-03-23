package com.aegisnav.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

// ── Battery drain warning for Maximum scan intensity (once per session) ──
@Composable
fun BatteryWarningDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSwitchToBalanced: () -> Unit
) {
    if (!show) return
    Dialog(onDismissRequest = { onDismiss() }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "⚠️ High Scan Intensity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE65100)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Battery will drain faster. Maximum scan intensity is active. Switch to Balanced in Settings to save battery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF333333)
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { onSwitchToBalanced() }) {
                        Text("Switch to Balanced", color = Color(0xFF00BCD4))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onDismiss() }) {
                        Text("Keep Maximum", color = Color(0xFF888888))
                    }
                }
            }
        }
    }
}
