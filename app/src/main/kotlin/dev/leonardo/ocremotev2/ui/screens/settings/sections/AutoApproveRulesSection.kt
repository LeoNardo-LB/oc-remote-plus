package dev.leonardo.ocremotev2.ui.screens.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.ui.screens.settings.SettingsViewModel
import dev.leonardo.ocremotev2.ui.screens.settings.components.PermissionRulesSection
import dev.leonardo.ocremotev2.ui.screens.settings.components.SectionHeader

@Composable
fun AutoApproveRulesSection(viewModel: SettingsViewModel) {
    val autoApproveRules by viewModel.autoApproveRules.collectAsStateWithLifecycle()

    SectionHeader(stringResource(R.string.settings_auto_approve_rules))
    PermissionRulesSection(
        rules = autoApproveRules,
        onDeleteRule = { rule -> viewModel.deletePermissionRule(rule) }
    )
}
