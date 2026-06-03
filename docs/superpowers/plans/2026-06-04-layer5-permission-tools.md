# Layer 5: Permission & Tool Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add permission auto-approve memory, enhanced permission dialogs, token tracking UI, and improve reasoning/tool-chain/diff rendering.
**Architecture:** `PermissionAutoApprover` reads/writes DataStore `Set<PermissionRule>`. New Compose dialogs for "always" confirmation and reject-with-message. `TokenUsageCard` displays usage from `Message.Assistant.Tokens`. Enhanced `ReasoningBlock` with streaming animation. Enhanced `DiffView` with line numbers and color-coded syntax.
**Tech Stack:** Kotlin, Jetpack Compose (Material3), DataStore Preferences, StateFlow, Hilt
**Depends on:** Layer 4 (ToolCardResolver, tool cards, composables)
**Spec reference:** `docs/superpowers/specs/2026-06-04-gap-fix-design.md` → Layer 5

---

## Step 5.1: Permission Auto-Approve Memory (P0)

`PermissionAutoApprover` checks incoming `PermissionAsked` events against saved rules in DataStore. Matching rules auto-reply "once". Choosing "always" persists a new rule.

### 5.1.1 — Define PermissionRule model

**File:** `app/src/main/kotlin/dev/minios/ocremote/domain/model/PermissionRule.kt`

```kotlin
package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * A saved permission auto-approve rule.
 * When an incoming [SseEvent.PermissionAsked] matches [toolName] + [sessionId] + [directoryPattern],
 * the permission is auto-approved.
 */
@Serializable
data class AutoApproveRule(
    val toolName: String,
    val sessionId: String? = null,
    val directoryPattern: String = "*",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun matches(event: dev.minios.ocremote.domain.model.SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        // Tool name must match (exact or wildcard)
        if (toolName != "*" && event.tool?.name != toolName && event.permission != toolName) return false

        // Session must match if specified
        if (sessionId != null && event.sessionId != sessionId) return false

        // Directory pattern must match
        if (directoryPattern != "*" && directoryPattern != sessionDirectory) return false

        return true
    }
}
```

### 5.1.2 — Write test for AutoApproveRule matching

**File:** `app/src/test/kotlin/dev/minios/ocremote/domain/model/AutoApproveRuleTest.kt`

```kotlin
package dev.minios.ocremote.domain.model

import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Test

class AutoApproveRuleTest {

    private fun testEvent(
        permission: String = "bash",
        sessionId: String = "s1",
        toolName: String? = "bash"
    ) = SseEvent.PermissionAsked(
        id = "perm-1",
        sessionId = sessionId,
        permission = permission,
        tool = toolName?.let { SseEvent.ToolRef(name = it) }
    )

    @Test
    fun `wildcard rule matches everything`() {
        val rule = AutoApproveRule(toolName = "*")
        assertTrue(rule.matches(testEvent(), "/home/user/project"))
    }

    @Test
    fun `specific tool name matches`() {
        val rule = AutoApproveRule(toolName = "bash")
        assertTrue(rule.matches(testEvent(toolName = "bash")))
    }

    @Test
    fun `different tool name does not match`() {
        val rule = AutoApproveRule(toolName = "bash")
        assertFalse(rule.matches(testEvent(toolName = "edit")))
    }

    @Test
    fun `session-scoped rule only matches same session`() {
        val rule = AutoApproveRule(toolName = "*", sessionId = "s1")
        assertTrue(rule.matches(testEvent(sessionId = "s1")))
        assertFalse(rule.matches(testEvent(sessionId = "s2")))
    }

    @Test
    fun `directory pattern matches specific directory`() {
        val rule = AutoApproveRule(toolName = "*", directoryPattern = "/home/user/project")
        assertTrue(rule.matches(testEvent(), "/home/user/project"))
        assertFalse(rule.matches(testEvent(), "/other/path"))
    }

    @Test
    fun `permission name used as fallback when tool is null`() {
        val rule = AutoApproveRule(toolName = "bash")
        val event = testEvent(permission = "bash", toolName = null)
        assertTrue(rule.matches(event, "/project"))
    }
}
```

### 5.1.3 — Create PermissionAutoApprover

**File:** `app/src/main/kotlin/dev/minios/ocremote/data/repository/PermissionAutoApprover.kt`

```kotlin
package dev.minios.ocremote.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.minios.ocremote.domain.model.AutoApproveRule
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages permission auto-approve rules persisted in DataStore.
 * Checks incoming [SseEvent.PermissionAsked] events against saved rules.
 */
@Singleton
class PermissionAutoApprover @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_RULES = stringSetPreferencesKey("permission_auto_approve_rules")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Load all saved rules from DataStore. */
    suspend fun loadRules(): Set<AutoApproveRule> {
        return dataStore.data.map { prefs ->
            val jsonStrings = prefs[KEY_RULES] ?: emptySet()
            jsonStrings.mapNotNull { runCatching { json.decodeFromString<AutoApproveRule>(it) }.getOrNull() }.toSet()
        }.first()
    }

    /** Check if an event matches any saved rule. */
    suspend fun shouldAutoApprove(event: SseEvent.PermissionAsked, sessionDirectory: String): Boolean {
        val rules = loadRules()
        return rules.any { it.matches(event, sessionDirectory) }
    }

    /** Persist a new rule (user chose "always"). */
    suspend fun addRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing + ruleJson
        }
    }

    /** Remove a rule. */
    suspend fun removeRule(rule: AutoApproveRule) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_RULES] ?: emptySet()
            val ruleJson = json.encodeToString(rule)
            prefs[KEY_RULES] = existing - ruleJson
        }
    }

    /** Clear all rules. */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_RULES)
        }
    }
}
```

### 5.1.4 — Write test for PermissionAutoApprover

**File:** `app/src/test/kotlin/dev/minios/ocremote/data/repository/PermissionAutoApproverTest.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.AutoApproveRule
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionAutoApproverTest {

    // Note: Full integration tests require Android instrumentation for DataStore.
    // Unit tests cover the matching logic via AutoApproveRuleTest.
    // This file tests the serialization round-trip.

    @Test
    fun `AutoApproveRule serialization round-trip`() {
        val rule = AutoApproveRule(
            toolName = "bash",
            sessionId = "s1",
            directoryPattern = "/home/user"
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString(AutoApproveRule.serializer(), serialized)
        assertEquals(rule, deserialized)
    }

    @Test
    fun `AutoApproveRule with defaults serialization`() {
        val rule = AutoApproveRule(toolName = "*")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val serialized = json.encodeToString(AutoApproveRule.serializer(), rule)
        val deserialized = json.decodeFromString(AutoApproveRule.serializer(), serialized)
        assertEquals(rule, deserialized)
        assertNull(deserialized.sessionId)
        assertEquals("*", deserialized.directoryPattern)
    }
}
```

### 5.1.5 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.domain.model.AutoApproveRuleTest"
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.data.repository.PermissionAutoApproverTest"
```

- [ ] Compile check passes
- [ ] Rule matching tests pass
- [ ] Serialization round-trip tests pass
- [ ] Commit: `feat(permissions): add AutoApproveRule model and PermissionAutoApprover`

---

## Step 5.2: "Always" Secondary Confirmation (P1)

`AlwaysConfirmDialog`: Material3 `AlertDialog` showing tool name + directory pattern + confirm button. Shown when user taps "Always allow" on a permission request.

### 5.2.1 — Create AlwaysConfirmDialog

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AlwaysConfirmDialog.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

/**
 * Secondary confirmation dialog shown when user taps "Always allow" on a permission.
 * Prevents accidental permanent approvals.
 */
@Composable
internal fun AlwaysConfirmDialog(
    toolName: String,
    directoryPattern: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.permission_always_confirm_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.permission_always_confirm_message,
                    toolName,
                    directoryPattern
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.permission_always_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}
```

### 5.2.2 — Add string resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="permission_always_confirm_title">Always allow?</string>
<string name="permission_always_confirm_message">This will automatically approve \"%1$s\" permission for directory \"%2$s\". You can manage saved rules in Settings.</string>
<string name="permission_always_confirm_button">Confirm always</string>
```

### 5.2.3 — Wire into PermissionRequestCard (wherever permission UI is rendered)

The existing permission UI in `ChatScreen` handles permission responses. Find where the "Approve" / "Deny" buttons are and add a third "Always" button that triggers this dialog.

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

In the permission handling section, add state for the dialog:

```kotlin
// Add state in the permission card section:
var showAlwaysDialog by remember { mutableStateOf<SseEvent.PermissionAsked?>(null) }

// When showAlwaysDialog is non-null, show AlwaysConfirmDialog:
showAlwaysDialog?.let { perm ->
    AlwaysConfirmDialog(
        toolName = perm.tool?.name ?: perm.permission,
        directoryPattern = currentSessionDirectory,
        onConfirm = {
            viewModel.savePermissionRule(perm, currentSessionDirectory)
            viewModel.replyPermission(perm.id, allowed = true)
            showAlwaysDialog = null
        },
        onDismiss = { showAlwaysDialog = null }
    )
}
```

### 5.2.4 — Add ViewModel methods

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

```kotlin
// Add these methods to ChatViewModel:

private val autoApprover: PermissionAutoApprover? = null // Will be injected

fun savePermissionRule(event: SseEvent.PermissionAsked, directory: String) {
    viewModelScope.launch {
        val rule = AutoApproveRule(
            toolName = event.tool?.name ?: event.permission,
            sessionId = null,
            directoryPattern = directory
        )
        autoApprover?.addRule(rule)
    }
}

fun checkAutoApprove(event: SseEvent.PermissionAsked, directory: String, onAutoApprove: () -> Unit) {
    viewModelScope.launch {
        if (autoApprover?.shouldAutoApprove(event, directory) == true) {
            onAutoApprove()
        }
    }
}
```

### 5.2.5 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(permissions): add AlwaysConfirmDialog for permanent approval`

---

## Step 5.3: Reject with Message (P1)

`RejectWithMessageDialog`: `OutlinedTextField` for rejection reason. Only shown for sub-agent permissions.

### 5.3.1 — Create RejectWithMessageDialog

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/RejectWithMessageDialog.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

/**
 * Dialog to provide a rejection reason when denying a sub-agent permission.
 */
@Composable
internal fun RejectWithMessageDialog(
    sourceSessionTitle: String?,
    onReject: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.permission_reject_title))
        },
        text = {
            if (sourceSessionTitle != null) {
                Text(
                    text = stringResource(R.string.permission_reject_from_agent, sourceSessionTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.permission_reject_reason_label)) },
                placeholder = { Text(stringResource(R.string.permission_reject_reason_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3
            )
        },
        confirmButton = {
            TextButton(onClick = { onReject(reason) }) {
                Text(text = stringResource(R.string.permission_reject_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}
```

### 5.3.2 — Add string resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="permission_reject_title">Reject with reason</string>
<string name="permission_reject_from_agent">From agent: %s</string>
<string name="permission_reject_reason_label">Reason (optional)</string>
<string name="permission_reject_reason_hint">Explain why this is denied…</string>
<string name="permission_reject_button">Reject</string>
```

### 5.3.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(permissions): add RejectWithMessageDialog for sub-agent rejections`

---

## Step 5.4: Permission Config UI (P2)

Settings page section showing saved auto-approve rules with a delete button.

### 5.4.1 — Create PermissionRulesSection composable

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/PermissionRulesSection.kt`

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.AutoApproveRule
import dev.minios.ocremote.ui.theme.AlphaTokens

/**
 * Section in Settings showing saved permission auto-approve rules.
 */
@Composable
internal fun PermissionRulesSection(
    rules: List<AutoApproveRule>,
    onDeleteRule: (AutoApproveRule) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_auto_approve_rules),
                style = MaterialTheme.typography.titleSmall
            )
        }

        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_auto_approve_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
        } else {
            rules.forEachIndexed { index, rule ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))
                }
                RuleRow(
                    rule = rule,
                    onDelete = { onDeleteRule(rule) }
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: AutoApproveRule,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.toolName,
                style = MaterialTheme.typography.bodyMedium
            )
            if (rule.directoryPattern != "*") {
                Text(
                    text = rule.directoryPattern,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_delete_rule),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
```

### 5.4.2 — Add string resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="settings_auto_approve_rules">Auto-approve rules</string>
<string name="settings_no_auto_approve_rules">No saved auto-approve rules</string>
<string name="settings_delete_rule">Delete rule</string>
```

### 5.4.3 — Integrate into SettingsScreen

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt`

Add the permission rules section to the settings scroll content, after the existing chat settings section:

```kotlin
// In the settings Column, after the chat settings section:
HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
PermissionRulesSection(
    rules = autoApproveRules,
    onDeleteRule = { rule -> viewModel.deletePermissionRule(rule) }
)
```

Add to SettingsViewModel:

```kotlin
// In SettingsViewModel, add:
val autoApproveRules: StateFlow<List<AutoApproveRule>> = /* load from PermissionAutoApprover */

fun deletePermissionRule(rule: AutoApproveRule) {
    viewModelScope.launch {
        autoApprover.removeRule(rule)
    }
}
```

### 5.4.4 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(settings): add permission auto-approve rules management UI`

---

## Step 5.5: Token/Cost Tracking UI (P1)

`TokenUsageCard`: displays input/output/reasoning/cache tokens with `LinearProgressIndicator`.

### 5.5.1 — Create TokenUsageCard composable

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/TokenUsageCard.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.ShapeTokens

/**
 * Card displaying token usage statistics for the current session.
 */
@Composable
internal fun TokenUsageCard(
    inputTokens: Int,
    outputTokens: Int,
    reasoningTokens: Int,
    cacheReadTokens: Int,
    cacheWriteTokens: Int,
    totalCost: Double,
    contextWindow: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = ShapeTokens.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: total tokens + cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.chat_token_usage_total,
                        inputTokens + outputTokens + reasoningTokens
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                if (totalCost > 0) {
                    Text(
                        text = String.format("$%.4f", totalCost),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    )
                }
            }

            // Context window progress bar
            if (contextWindow > 0) {
                val totalTokens = inputTokens + outputTokens + reasoningTokens
                val progress = (totalTokens.toFloat() / contextWindow).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Text(
                    text = stringResource(R.string.chat_token_context_usage, totalTokens, contextWindow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Token breakdown rows
            TokenRow(stringResource(R.string.chat_token_input), inputTokens)
            TokenRow(stringResource(R.string.chat_token_output), outputTokens)
            if (reasoningTokens > 0) {
                TokenRow(stringResource(R.string.chat_token_reasoning), reasoningTokens)
            }
            if (cacheReadTokens > 0 || cacheWriteTokens > 0) {
                TokenRow(stringResource(R.string.chat_token_cache_read), cacheReadTokens)
                TokenRow(stringResource(R.string.chat_token_cache_write), cacheWriteTokens)
            }
        }
    }
}

@Composable
private fun TokenRow(label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
        )
        Text(
            text = String.format("%,d", value),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
```

### 5.5.2 — Add string resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="chat_token_usage_total">%,d tokens</string>
<string name="chat_token_context_usage">%,d / %,d context</string>
<string name="chat_token_input">Input</string>
<string name="chat_token_output">Output</string>
<string name="chat_token_reasoning">Reasoning</string>
<string name="chat_token_cache_read">Cache read</string>
<string name="chat_token_cache_write">Cache write</string>
```

### 5.5.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): add TokenUsageCard with context window progress`

---

## Step 5.6: Reasoning Thinking Chain Display (P1)

Enhanced `ReasoningBlock`: "Thinking..." animation + collapsible + streaming text. Reuses `StreamingStateTracker` from Layer 3 (`domain/tracker/StreamingStateTracker.kt`) — **do NOT create a new tracker class**.

### 5.6.1 — Verify Layer 3 StreamingStateTracker Available

Layer 3 Task 3 already created `dev.minios.ocremote.domain.tracker.StreamingStateTracker` with methods `onStarted(partId, sessionId)`, `onDelta(partId, delta)`, `onEnded(partId)`, and exposes state via `getState(partId): StreamingState?`. For the ReasoningBlock, we use `StreamingState.Started`/`StreamingState.Streaming` to show the "Thinking..." animation and `StreamingState.Ended` to hide it.

No new file needed — import `dev.minios.ocremote.domain.tracker.StreamingStateTracker` and `dev.minios.ocremote.domain.tracker.StreamingState` directly.

```kotlin
// Import from Layer 3 — do NOT create a new tracker
import dev.minios.ocremote.domain.tracker.StreamingStateTracker
import dev.minios.ocremote.domain.tracker.StreamingState
```

### 5.6.2 — No new test file needed

The `StreamingStateTracker` from Layer 3 Task 3 already has comprehensive tests. The ReasoningBlock enhancement uses its existing `getState(partId)` API — no separate test file needed for this step.

- [ ] **Verify:** Read `StreamingStateTracker.kt` from Layer 3 to confirm API availability

### 5.6.3 — Enhance ReasoningBlock

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ReasoningBlock.kt`

Add a streaming indicator parameter and "Thinking..." animation. Modify the existing `ReasoningBlock` composable:

```kotlin
// Add parameter to the existing function signature:
@Composable
internal fun ReasoningBlock(
    text: String,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    durationMs: Long? = null,
    isStreaming: Boolean = false  // NEW PARAMETER — derived from StreamingStateTracker.getState(partId) == StreamingState.Started/Streaming
) {
    // In the header row, conditionally show "Thinking..." with pulse:
    val headerText = when {
        isStreaming && text.isBlank() -> "Thinking\u2026"
        isStreaming -> "Thinking\u2026"
        else -> buildString {
            append("Reasoning")
            durationMs?.let { append(" \u00B7 ${formatReasoningDuration(it)}") }
        }
    }

    // Below the header, if isStreaming and text.isBlank(), show CircularProgressIndicator(size=16.dp, alpha=AlphaTokens.MUTED)
    // If isStreaming and text.isNotBlank(), show text as normal with a trailing cursor
    // If !isStreaming, show text as before (unchanged)
}

private fun formatReasoningDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m ${((ms % 60_000) / 1000)}s"
}
```

### 5.6.4 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="chat_thinking">Thinking…</string>
<string name="chat_reasoning">Reasoning</string>
```

### 5.6.5 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.ui.screens.chat.util.StreamingStateTrackerTest"
```

- [ ] Compile check passes
- [ ] StreamingStateTrackerTest passes
- [ ] Commit: `feat(chat): add streaming reasoning indicator with Thinking animation`

---

## Step 5.7: Tool Call Chain Display (P1)

Enhanced `TaskToolCard`: expandable sub-agent prompt + execution status + output summary.

### 5.7.1 — Enhance TaskToolCard

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/TaskToolCard.kt`

The existing `TaskToolCard` already shows sub-agent info. Add an expandable section for the sub-agent prompt and execution status summary:

```kotlin
// Add inside the expanded content section of TaskToolCard, after existing content:
// (Find the expandedContent lambda in the existing ToolCardScaffold call)

// Add after existing expanded content:
if (output.isNotBlank()) {
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        shape = ShapeTokens.extraSmall,
        color = toolOutputContainerColor(),
        border = if (isAmoled) AmoledDefaultBorder else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = stringResource(R.string.chat_task_output_summary),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = output.take(2000),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (isAmoled) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                )
            }
        }
    }
}
```

### 5.7.2 — Add string resource

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="chat_task_output_summary">Output summary</string>
```

### 5.7.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
```

- [ ] Compile check passes
- [ ] Commit: `feat(chat): enhance TaskToolCard with output summary`

---

## Step 5.8: Diff Rendering Enhancement (P1)

Enhance `DiffView` with syntax highlighting using `Color.DiffAdded/DiffRemoved`, line numbers, and add/delete line counts.

### 5.8.1 — Enhance DiffView in DiffHelpers

**File:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DiffHelpers.kt`

The existing `DiffView` already uses `DiffAdded`/`DiffRemoved` colors. Enhance it with line numbers and counts:

```kotlin
// Modify the DiffView composable to add line numbers.
// Find the existing DiffView function and replace its content:

@Composable
internal fun DiffView(before: String, after: String) {
    val isAmoled = isAmoledTheme()
    val addColor = DiffAdded
    val delColor = DiffRemoved
    val addBg = DiffAdded.copy(alpha = AlphaTokens.FAINT)
    val delBg = DiffRemoved.copy(alpha = AlphaTokens.FAINT)

    val beforeLines = if (before.isBlank()) emptyList() else before.lines()
    val afterLines = if (after.isBlank()) emptyList() else after.lines()

    val diffLines = remember(before, after) { computeSimpleDiff(beforeLines, afterLines) }

    // Compute stats
    val addedCount = diffLines.count { it.type == DiffLineType.ADDED }
    val removedCount = diffLines.count { it.type == DiffLineType.REMOVED }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Stats row
        if (addedCount > 0 || removedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (addedCount > 0) {
                    Text(
                        text = "+$addedCount",
                        style = MaterialTheme.typography.labelSmall.copy(color = addColor)
                    )
                }
                if (removedCount > 0) {
                    Text(
                        text = "-$removedCount",
                        style = MaterialTheme.typography.labelSmall.copy(color = delColor)
                    )
                }
            }
        }

        // Diff lines with line numbers
        AmoledSurface(
            isAmoledDark = isAmoled,
            shape = ShapeTokens.extraSmall,
            normalColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.FAINT),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreenHeight())
                .verticalScroll(rememberScrollState())
        ) {
            var lineNumber = 0
            Column(modifier = Modifier.padding(4.dp)) {
                diffLines.forEach { diffLine ->
                    val bg = when (diffLine.type) {
                        DiffLineType.ADDED -> addBg
                        DiffLineType.REMOVED -> delBg
                        DiffLineType.UNCHANGED -> Color.Transparent
                    }
                    val fg = when (diffLine.type) {
                        DiffLineType.ADDED -> addColor
                        DiffLineType.REMOVED -> delColor
                        DiffLineType.UNCHANGED -> if (isAmoled) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    }
                    val prefix = when (diffLine.type) {
                        DiffLineType.ADDED -> "+"
                        DiffLineType.REMOVED -> "-"
                        DiffLineType.UNCHANGED -> " "
                    }
                    lineNumber++
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind { drawRect(bg) }
                            .padding(horizontal = 4.dp, vertical = 0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Line number
                        Text(
                            text = "$lineNumber",
                            style = CodeTypography.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT)
                            ),
                            modifier = Modifier.width(28.dp)
                        )
                        // Prefix + content
                        SelectionContainer {
                            Text(
                                text = "$prefix${diffLine.text}",
                                style = CodeTypography.copy(color = fg)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

### 5.8.2 — Write test for computeSimpleDiff

**File:** `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DiffHelpersTest.kt`

```kotlin
package dev.minios.ocremote.ui.screens.chat.tools

import org.junit.Assert.*
import org.junit.Test

class DiffHelpersTest {

    @Test
    fun `empty inputs produce empty diff`() {
        val result = computeSimpleDiff(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all added when before is empty`() {
        val result = computeSimpleDiff(emptyList(), listOf("a", "b"))
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.ADDED })
    }

    @Test
    fun `all removed when after is empty`() {
        val result = computeSimpleDiff(listOf("a", "b"), emptyList())
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.REMOVED })
    }

    @Test
    fun `identical lines are unchanged`() {
        val result = computeSimpleDiff(listOf("a", "b"), listOf("a", "b"))
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.UNCHANGED })
    }

    @Test
    fun `added and removed lines detected`() {
        val result = computeSimpleDiff(listOf("a", "b"), listOf("a", "c"))
        // Should have: unchanged(a), removed(b), added(c)
        assertEquals(3, result.size)
        assertEquals(DiffLineType.UNCHANGED, result[0].type)
        assertEquals("a", result[0].text)
    }
}
```

### 5.8.3 — Verify

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.minios.ocremote.ui.screens.chat.tools.DiffHelpersTest"
```

- [ ] Compile check passes
- [ ] Diff computation tests pass
- [ ] Commit: `feat(chat): enhance DiffView with line numbers and add/delete counts`

---

## Final Verification

After all steps are complete:

```bash
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun
```

- [ ] All compile checks pass
- [ ] All unit tests pass
- [ ] No regressions in existing tests
