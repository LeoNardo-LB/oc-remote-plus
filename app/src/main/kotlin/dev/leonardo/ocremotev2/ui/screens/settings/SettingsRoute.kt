package dev.leonardo.ocremotev2.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    SettingsScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}
