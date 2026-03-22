package com.aegisnav.app.p2p

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// DEFAULT_RELAY_URL and DEFAULT_RELAY_URL_2 are defined in P2PConstants.kt (Finding 3.2)

/**
 * P2P node setup screen - shown as Step 2 of the first-run wizard.
 * Defaults to the public relay. Users may add up to 2 custom node URLs.
 * WSS (TLS) enforced - plain ws:// rejected with validation error.
 */
@Composable
fun P2PSetupScreen(
    onComplete: (defaultEnabled: Boolean, customNodes: List<String>) -> Unit
) {
    var useDefault by remember { mutableStateOf(true) }
    var customNode1 by remember { mutableStateOf("") }
    var customNode2 by remember { mutableStateOf("") }
    var node1Error by remember { mutableStateOf<String?>(null) }
    var node2Error by remember { mutableStateOf<String?>(null) }

    fun validateUrl(url: String): String? {
        if (url.isBlank()) return null
        if (!url.startsWith("wss://")) return "Must start with wss:// (secure WebSocket only)"
        if (!url.contains(".")) return "Invalid URL"
        return null
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Connect to the Network", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        Text(
            "AegisNav shares reports anonymously with nearby users through relay nodes. " +
            "Your node ID rotates every 24 hours - no account needed.",
            style = MaterialTheme.typography.bodyMedium, color = Color.Gray
        )

        // Default relay toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use Default Relay", fontWeight = FontWeight.Bold)
                    if (useDefault) {
                        Text("Sends anonymous reports to the AegisNav public relay.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    } else {
                        Text("Offline mode. Reports save locally only.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("Add custom nodes below to share with others.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF43A047))
                    }
                }
                Switch(
                    checked = useDefault,
                    onCheckedChange = { useDefault = it }
                )
            }
        }

        Text("Custom Nodes (optional, max 2)",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text("Run your own node or connect to a trusted friend's. Reports broadcast to all connected relays simultaneously.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        // Custom node 1
        OutlinedTextField(
            value = customNode1,
            onValueChange = { customNode1 = it; node1Error = validateUrl(it) },
            label = { Text("Custom Node 1") },
            placeholder = { Text("wss://your-node.example.com/socket.io/...") },
            isError = node1Error != null,
            supportingText = node1Error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true
        )

        // Custom node 2
        OutlinedTextField(
            value = customNode2,
            onValueChange = { customNode2 = it; node2Error = validateUrl(it) },
            label = { Text("Custom Node 2") },
            placeholder = { Text("wss://your-node.example.com/socket.io/...") },
            isError = node2Error != null,
            supportingText = node2Error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true
        )

        // Privacy note
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🔒 What relay nodes see:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("• Report type, location, timestamp", fontSize = 12.sp)
                Text("• Your rotating anonymous node ID", fontSize = 12.sp)
                Text("🚫 What they never see:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("• Your identity, name, or account", fontSize = 12.sp)
                Text("• Devices on your ignore list", fontSize = 12.sp)
                Text("• Raw BLE/WiFi scan data", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        val canContinue = node1Error == null && node2Error == null && (useDefault || customNode1.isNotBlank() || customNode2.isNotBlank())

        Button(
            onClick = {
                val customs = listOf(customNode1, customNode2).filter { it.isNotBlank() && validateUrl(it) == null }
                onComplete(useDefault, customs)
            },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Continue", fontWeight = FontWeight.Bold) }

        TextButton(
            onClick = { onComplete(false, emptyList()) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Skip - stay offline for now") }
    }
}
