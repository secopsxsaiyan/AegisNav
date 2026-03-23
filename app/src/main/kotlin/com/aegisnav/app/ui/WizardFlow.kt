package com.aegisnav.app.ui

import androidx.compose.runtime.Composable
import com.aegisnav.app.AegisNavTheme
import com.aegisnav.app.MapTileManifest
import com.aegisnav.app.StateSelectorDialog
import com.aegisnav.app.TileManifest

@Composable
fun WizardFlow(
    showStateSelector: Boolean,
    showWizardDownload: Boolean,
    tileManifest: TileManifest,
    onStateSelected: (String) -> Unit,
    onWizardBack: () -> Unit,
    onWizardSkip: () -> Unit,
    onDataChanged: () -> Unit
) {
    if (showStateSelector && !showWizardDownload) {
        // Wrap in AegisNavTheme(isDark=false): dialog renders before the main WtwTheme block
        // so it would otherwise inherit Android system dark mode.
        AegisNavTheme(isDark = false) {
            StateSelectorDialog(
                manifest = tileManifest,
                onStateSelected = onStateSelected
            )
        }
    }
    // Wizard step 2: Download Map Data for selected state
    if (showWizardDownload) {
        DataDownloadScreen(
            onBack = onWizardBack,
            onSkip = onWizardSkip,
            onDataChanged = onDataChanged
        )
    }
}
