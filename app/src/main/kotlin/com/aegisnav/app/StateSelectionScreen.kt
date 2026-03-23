package com.aegisnav.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * State selection screen - shown as Step 2 in the first-run wizard.
 * User selects their home state(s); ALPR data is filtered to these states on load.
 * Default pre-selected: Florida (MVP focus).
 */
@Composable
fun StateSelectionScreen(onComplete: (selectedAbbrs: Set<String>) -> Unit) {
    val context = LocalContext.current
    var existing by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        existing = loadSelectedStates(context)
        selected.addAll(existing)
    }
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) USStates.all
        else USStates.all.filter {
            it.name.contains(query, ignoreCase = true) || it.abbr.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Select Your State(s)", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text(
            "ALPR camera data will be preloaded for your selected states. " +
            "You can add more states later in Settings.",
            style = MaterialTheme.typography.bodyMedium
        )

        // Search box
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search states") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Text("${selected.size} state(s) selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.abbr }) { state ->
                val isSelected = selected.contains(state.abbr)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isSelected) selected.remove(state.abbr)
                            else selected.add(state.abbr)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = {
                            if (it) selected.add(state.abbr) else selected.remove(state.abbr)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.name, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(state.abbr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }

        Button(
            onClick = { onComplete(selected.toSet()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Continue →", fontWeight = FontWeight.Bold) }
    }
}
