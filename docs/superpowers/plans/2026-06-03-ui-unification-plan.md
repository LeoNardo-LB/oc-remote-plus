# UI 统一实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 31 个弹窗统一为 BasicAlertDialog + Surface，统一 AMOLED 适配和按钮布局，统一 3 个服务器设置页列表样式

**Architecture:** 所有弹窗使用 BasicAlertDialog + Surface(amoledDialogParams) + Column(title+content+DialogButtons) 统一结构。新建 AmoledDialogParams/DialogButtons/DetailRow/ConfirmDialog 四个共享组件。

**Tech Stack:** Jetpack Compose, Material 3, Kotlin

**编译验证命令:** `.\gradlew :app:compileDevDebugKotlin`

**设计 Spec:** `docs/superpowers/specs/2026-06-03-ui-unification-design.md`

---

## File Structure

### 新建文件

| 文件 | 职责 |
|------|------|
| `ui/components/AmoledDialogParams.kt` | AmoledDialogParams 数据类 + amoledDialogParams() 工厂函数 |
| `ui/components/DialogButtons.kt` | DialogButtonRole 枚举 + DialogButtons Composable |
| `ui/components/DetailRow.kt` | 共享展示行（label 30% + value 70%） |
| `ui/components/ConfirmDialog.kt` | 通用确认弹窗，支持自定义标题/消息/按钮角色 |

### 删除文件

| 文件 | 前置条件 |
|------|---------|
| `ui/components/AppDialog.kt` | 第二组全部 13 个 AppDialog 弹窗迁移完成，无引用 |

### 修改文件（按 Task 编号）

| Task | 文件 | 改动概要 |
|------|------|---------|
| 1-4 | 新建组件 | 基础设施 |
| 5 | `ui/screens/home/components/ServerDialog.kt` | AMOLED 手动→amoledDialogParams()，按钮改用 DialogButtons |
| 6 | `ui/screens/server/ServerProvidersScreen.kt` | 3 个弹窗 AMOLED 统一 + 列表样式改 ListItem |
| 7 | `ui/screens/chat/dialog/ModelPickerDialog.kt` | AMOLED 统一 + 补 border/elevation |
| 8 | `ui/screens/chat/dialog/ImagePreviewDialog.kt` | AMOLED 统一 |
| 9 | `ui/screens/home/components/LocalLaunchOptionsDialog.kt` | 超时子弹窗 AMOLED 统一 |
| 10 | `ui/screens/chat/components/ChatTopBar.kt` | AlertDialog→BasicAlertDialog + AMOLED |
| 11 | `ui/screens/chat/dialog/ChatScreenDialogs.kt` | 3 个 AlertDialog→BasicAlertDialog |
| 12 | `ui/screens/chat/components/MessageCard.kt` | 撤回弹窗改用 ConfirmDialog |
| 13 | `ui/screens/chat/components/ChatMessageList.kt` | 撤回弹窗改用 ConfirmDialog |
| 14 | `ui/screens/settings/components/TerminalFontSizeDialog.kt` | AlertDialog→BasicAlertDialog |
| 15 | `ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt` | AlertDialog→BasicAlertDialog + 补滚动 |
| 16 | `ui/screens/sessions/SessionListScreen.kt` | 2 个 AppDialog→BasicAlertDialog |
| 17 | `ui/screens/sessions/components/SessionRow.kt` | 会话详情 AppDialog→BasicAlertDialog，删 private DetailRow |
| 18 | `ui/screens/sessions/components/DirectoryTreeNode.kt` | 目录详情 AppDialog→BasicAlertDialog，删 private DetailRow |
| 19 | `ui/screens/sessions/components/OpenProjectDialog.kt` | 创建文件夹 AppDialog→BasicAlertDialog |
| 20 | `ui/screens/settings/components/ThemePickerDialog.kt` | AppDialog→BasicAlertDialog |
| 21 | `ui/screens/settings/components/LanguagePickerDialog.kt` | AppDialog→BasicAlertDialog |
| 22 | `ui/screens/settings/components/FontSizePickerDialog.kt` | AppDialog→BasicAlertDialog |
| 23 | `ui/screens/settings/components/MessageCountPickerDialog.kt` | AppDialog→BasicAlertDialog |
| 24 | `ui/screens/settings/components/ReconnectModePickerDialog.kt` | AppDialog→BasicAlertDialog |
| 25 | `ui/screens/settings/components/ImageCompressionDialog.kt` | 2 个 AppDialog→BasicAlertDialog |
| 26 | `ui/screens/server/ServerSettingsScreen.kt` | AmoledCard→ListItem + HorizontalDivider |
| 27 | `ui/screens/server/ServerProvidersScreen.kt` | ProviderRow Card→ListItem（列表部分） |
| 28 | `ui/screens/server/ServerModelFilterScreen.kt` | AmoledCard 分组→ListItem + SectionHeader |
| 29 | `ui/screens/home/components/ServerCard.kt` | spacedBy(12→8dp)、spacedBy(8→6dp) |
| 30 | `ui/screens/settings/components/SettingsDisplayNames.kt` | 删除旧辅助函数 |
| 31 | `ui/components/AppDialog.kt` | 删除文件 |
| 32 | `ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt` | Bug 修复：补 verticalScroll |

---

## 阶段 1：基础设施（无弹窗改动）

---

## Task 1: 新建 AmoledDialogParams.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/AmoledDialogParams.kt`（新建）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 创建文件

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.LocalAmoledMode
import dev.minios.ocremote.ui.theme.ShapeTokens

/**
 * Parameters for a dialog Surface that adapts to AMOLED mode.
 *
 * AMOLED: Color.Black + 0dp elevation + 1dp outlineVariant/HIGH border.
 * Normal: specified color + specified elevation + no border.
 */
data class AmoledDialogParams(
    val containerColor: Color,
    val tonalElevation: Dp,
    val border: BorderStroke?,
    val shape: Shape,
)

/**
 * Creates [AmoledDialogParams] that automatically adapt to the current AMOLED theme state.
 *
 * @param normalColor      Surface color in non-AMOLED mode. Default: surfaceContainerHigh.
 * @param normalElevation  Tonal elevation in non-AMOLED mode. Default: 6.dp.
 * @param shape            Corner shape for the dialog surface. Default: ShapeTokens.extraLarge (28dp).
 */
@Composable
fun amoledDialogParams(
    normalColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    normalElevation: Dp = 6.dp,
    shape: Shape = ShapeTokens.extraLarge,
): AmoledDialogParams {
    val isAmoled = LocalAmoledMode.current
    return if (isAmoled) {
        AmoledDialogParams(
            containerColor = Color.Black,
            tonalElevation = 0.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
            ),
            shape = shape,
        )
    } else {
        AmoledDialogParams(
            containerColor = normalColor,
            tonalElevation = normalElevation,
            border = null,
            shape = shape,
        )
    }
}
```

- [ ] 编译验证

---

## Task 2: 新建 DialogButtons.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/DialogButtons.kt`（新建）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 创建文件

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Role of a button inside a dialog.
 *
 * - [Primary]:   Main action (confirm, save, create). Renders as FilledTonalButton.
 * - [Secondary]: Cancel / dismiss. Renders as TextButton with default color.
 * - [Danger]:    Destructive action (delete, revert). Renders as TextButton with error color.
 */
enum class DialogButtonRole {
    Primary,
    Secondary,
    Danger,
}

/**
 * Unified dialog button row.
 *
 * Layout rules:
 * - 1 button: single Row, right-aligned
 * - 2 buttons: Row, horizontal, right-aligned
 * - 3+ buttons: Column, vertical, full-width
 *
 * @param buttons List of (label, role, onClick) triples.
 */
@Composable
fun DialogButtons(
    buttons: List<Triple<String, DialogButtonRole, () -> Unit>>,
) {
    if (buttons.size <= 2) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buttons.forEach { (text, role, onClick) ->
                DialogActionButton(
                    text = text,
                    role = role,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DialogActionButton(
    text: String,
    role: DialogButtonRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (role) {
        DialogButtonRole.Primary -> {
            FilledTonalButton(onClick = onClick, modifier = modifier) {
                Text(text)
            }
        }
        DialogButtonRole.Secondary -> {
            TextButton(onClick = onClick, modifier = modifier) {
                Text(text)
            }
        }
        DialogButtonRole.Danger -> {
            TextButton(
                onClick = onClick,
                modifier = modifier,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
            ) {
                Text(text)
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 3: 新建 DetailRow.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/DetailRow.kt`（新建）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

> 提取自 `SessionRow.kt:241-262` 和 `DirectoryTreeNode.kt:157-178` 的 private 重复定义。

- [ ] 创建文件

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A two-column detail row: label (30%) + value (70%).
 * Used in session details and directory details dialogs.
 */
@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.7f),
        )
    }
}
```

- [ ] 编译验证

---

## Task 4: 新建 ConfirmDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/ConfirmDialog.kt`（新建）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

> 复用撤回确认（MessageCard + ChatMessageList）和删除确认等场景。

- [ ] 创建文件

```kotlin
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Generic confirmation dialog with title, message, and customizable buttons.
 *
 * Uses the unified BasicAlertDialog + Surface + DialogButtons pattern.
 *
 * @param title           Dialog title text.
 * @param message         Dialog body text.
 * @param confirmLabel    Label for the confirm button.
 * @param confirmRole     Role of the confirm button (default: Danger).
 * @param dismissLabel    Label for the dismiss button (default: "Cancel" from android.R.string.cancel).
 * @param onDismiss       Called when the dialog is dismissed (cancel or outside click).
 * @param onConfirm       Called when the confirm button is clicked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmRole: DialogButtonRole = DialogButtonRole.Danger,
    dismissLabel: String = stringResource(android.R.string.cancel),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(dismissLabel, DialogButtonRole.Secondary, onDismiss),
                        Triple(confirmLabel, confirmRole, onConfirm),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## 阶段 2：第三组 — BasicAlertDialog AMOLED 统一（7 个弹窗，风险最低）

> 这些弹窗已是 BasicAlertDialog，仅统一 AMOLED 处理（手动 if/else → amoledDialogParams()）和按钮组件（手动 Row+TextButton → DialogButtons）。

---

## Task 5: ServerDialog.kt — 编辑服务器弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/components/ServerDialog.kt`

**改动范围:** 行 99-257。将手动 AMOLED 判断替换为 amoledDialogParams()，按钮行替换为 DialogButtons。

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 替换 imports

在文件头部，删除不再需要的 import（如果有的话），添加新的 import。

**在现有 import 区域末尾添加:**

```kotlin
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 替换行 99-118（isAmoled 变量 + Surface 参数）

将:
```kotlin
    val isAmoled = LocalAmoledMode.current
    val switchColors = if (isAmoled) {
```

替换为（保留 switchColors，因为它仍然需要 isAmoled）:

```kotlin
    val isAmoled = LocalAmoledMode.current
    val params = amoledDialogParams(
        normalColor = MaterialTheme.colorScheme.surface,
        shape = ShapeTokens.largeMedium,
    )
    val switchColors = if (isAmoled) {
```

- [ ] 步骤 3: 替换行 114-121 的 Surface 参数

将:
```kotlin
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = ShapeTokens.largeMedium,
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) AmoledDefaultBorder else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = dialogMaxHeight)
```

替换为:

```kotlin
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = dialogMaxHeight)
```

- [ ] 步骤 4: 替换行 222-254 的按钮区

将:
```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.server_cancel))
                    }
                    TextButton(
                        onClick = {
                            val normalizedUrl = validateAndNormalizeUrl(url)
                            urlError = when {
                                url.isBlank() -> urlRequiredText
                                normalizedUrl == null -> urlInvalidText
                                else -> null
                            }

                            if (urlError == null && normalizedUrl != null) {
                                val finalName = name.trim().ifBlank {
                                    deriveServerNameFromUrl(normalizedUrl)
                                }
                                onSave(
                                    finalName,
                                    normalizedUrl,
                                    username.ifBlank { "opencode" },
                                    password,
                                    autoConnect
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.server_save))
                    }
                }
```

替换为:

```kotlin
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.server_cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.server_save), DialogButtonRole.Primary) {
                            val normalizedUrl = validateAndNormalizeUrl(url)
                            urlError = when {
                                url.isBlank() -> urlRequiredText
                                normalizedUrl == null -> urlInvalidText
                                else -> null
                            }

                            if (urlError == null && normalizedUrl != null) {
                                val finalName = name.trim().ifBlank {
                                    deriveServerNameFromUrl(normalizedUrl)
                                }
                                onSave(
                                    finalName,
                                    normalizedUrl,
                                    username.ifBlank { "opencode" },
                                    password,
                                    autoConnect
                                )
                            }
                        },
                    )
                )
```

- [ ] 编译验证

---

## Task 6: ServerProvidersScreen.kt — 3 个弹窗 AMOLED 统一

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/server/ServerProvidersScreen.kt`

**改动范围:** 行 145-393（连接方法弹窗、API Key 弹窗、OAuth 弹窗）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

在文件头部 import 区域添加:

```kotlin
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 在 `connectProvider?.let` 块（行 145-198）中替换 AMOLED 处理

将行 149-155:
```kotlin
        BasicAlertDialog(onDismissRequest = { connectProvider = null }) {
            Surface(
                shape = ShapeTokens.largeMedium,
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
```

替换为:

```kotlin
        val connectParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = { connectProvider = null }) {
            Surface(
                shape = connectParams.shape,
                color = connectParams.containerColor,
                border = connectParams.border,
                tonalElevation = connectParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
```

- [ ] 步骤 3: 替换连接方法弹窗的按钮区（行 192-194）

将:
```kotlin
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { connectProvider = null }) { Text(stringResource(R.string.cancel)) }
                    }
```

替换为:

```kotlin
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { connectProvider = null },
                        )
                    )
```

- [ ] 步骤 4: 替换 API Key 弹窗（行 200-248）的 AMOLED 处理

将行 201-210:
```kotlin
        BasicAlertDialog(onDismissRequest = {
            apiKeyProvider = null
            apiKey = ""
        }) {
            Surface(
                shape = ShapeTokens.largeMedium,
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
```

替换为:

```kotlin
        val apiKeyParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = {
            apiKeyProvider = null
            apiKey = ""
        }) {
            Surface(
                shape = apiKeyParams.shape,
                color = apiKeyParams.containerColor,
                border = apiKeyParams.border,
                tonalElevation = apiKeyParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
```

- [ ] 步骤 5: 替换 API Key 弹窗的标题样式和按钮区

将行 216:
```kotlin
                    Text(stringResource(R.string.server_settings_api_key_title, provider.providerName), style = MaterialTheme.typography.headlineSmall)
```
替换为:
```kotlin
                    Text(stringResource(R.string.server_settings_api_key_title, provider.providerName), style = MaterialTheme.typography.titleMedium)
```

将行 231-247 的按钮区（Row + TextButton × 2）:
```kotlin
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            apiKeyProvider = null
                            apiKey = ""
                        }) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            onClick = {
                                viewModel.connectProviderApi(provider.providerId, apiKey)
                                apiKeyProvider = null
```

替换为:
```kotlin
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                apiKeyProvider = null
                                apiKey = ""
                            },
                            Triple(stringResource(R.string.server_settings_connect), DialogButtonRole.Primary) {
                                viewModel.connectProviderApi(provider.providerId, apiKey)
                                apiKeyProvider = null
```

> 注意: 按钮区内 `apiKey = ""` 等后续逻辑保持不变，只是包裹在 `Triple` 中。需要确保闭合括号正确。具体替换需查看行 240-248 的完整代码。

- [ ] 步骤 6: 替换 OAuth 弹窗（行 250-393）的 AMOLED 处理

将行 254-263:
```kotlin
        BasicAlertDialog(onDismissRequest = {
            oauthCode = ""
            viewModel.cancelProviderOauth()
        }) {
            Surface(
                shape = ShapeTokens.largeMedium,
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
```

替换为:

```kotlin
        val oauthParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(onDismissRequest = {
            oauthCode = ""
            viewModel.cancelProviderOauth()
        }) {
            Surface(
                shape = oauthParams.shape,
                color = oauthParams.containerColor,
                border = oauthParams.border,
                tonalElevation = oauthParams.tonalElevation,
                modifier = Modifier.fillMaxWidth()
```

- [ ] 编译验证

---

## Task 7: ModelPickerDialog.kt — 模型选择弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ModelPickerDialog.kt`

**改动范围:** 行 65-73（Surface 参数）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import dev.minios.ocremote.ui.components.amoledDialogParams
```

- [ ] 步骤 2: 在函数体开头添加 params

在行 50 `val isAmoled = isAmoledTheme()` 之后添加:
```kotlin
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)
```

- [ ] 步骤 3: 替换行 69-74 的 Surface 参数

将:
```kotlin
        Surface(
            shape = ShapeTokens.largeMedium,
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
```

替换为:
```kotlin
        Surface(
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
            modifier = Modifier.fillMaxWidth()
```

- [ ] 编译验证

---

## Task 8: ImagePreviewDialog.kt — 图片预览弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ImagePreviewDialog.kt`

**改动范围:** 行 136-150（AMOLED 处理）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import dev.minios.ocremote.ui.components.amoledDialogParams
```

- [ ] 步骤 2: 替换行 136-150

将:
```kotlin
    val isAmoled = isAmoledTheme()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = ShapeTokens.largeMedium,
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = if (isAmoled) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH))
            } else {
                null
            },
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
```

替换为:
```kotlin
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
```

- [ ] 编译验证

---

## Task 9: LocalLaunchOptionsDialog.kt — 超时选择子弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/components/LocalLaunchOptionsDialog.kt`

**改动范围:** 行 312-394（超时选择弹窗）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.AppPickerList
```

- [ ] 步骤 2: 替换行 312-394（超时弹窗）

将:
```kotlin
    if (showTimeoutDialog) {
        BasicAlertDialog(onDismissRequest = { showTimeoutDialog = false }) {
            Surface(
                shape = ShapeTokens.largeMedium,
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) AmoledDefaultBorder else null,                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.home_local_startup_timeout_label),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(timeoutOptions) { option ->
                            val selected = option == startupTimeoutSec
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ShapeTokens.medium)
                                    .background(
                                        when {
                                            selected && isAmoled -> Color.Black
                                            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.MUTED)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .then(
                                        if (selected && isAmoled) {
                                            Modifier.border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                                                shape = ShapeTokens.medium,
                                            )
                                        } else Modifier
                                    )
                                    .clickable {
                                        onStartupTimeoutSecChange(option)
                                        showTimeoutDialog = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.home_local_startup_timeout_value, option),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showTimeoutDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
```

替换为（使用 AppPickerList 替代手动 LazyColumn）:

```kotlin
    if (showTimeoutDialog) {
        val timeoutParams = amoledDialogParams(
            normalColor = MaterialTheme.colorScheme.surface,
            shape = ShapeTokens.largeMedium,
        )
        BasicAlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = timeoutParams.shape,
                color = timeoutParams.containerColor,
                border = timeoutParams.border,
                tonalElevation = timeoutParams.tonalElevation,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 420.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.home_local_startup_timeout_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    AppPickerList(
                        options = timeoutOptions.map { it to stringResource(R.string.home_local_startup_timeout_value, it) },
                        selectedKey = startupTimeoutSec,
                        onSelect = { option ->
                            onStartupTimeoutSecChange(option)
                            showTimeoutDialog = false
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showTimeoutDialog = false },
                        )
                    )
                }
            }
        }
    }
```

> **注意**: 此替换引入了 `AppPickerList`，它会自动处理 AMOLED 主题、选中高亮和滚动。因此可以删除手动 LazyColumn 中的所有 AMOLED/选中逻辑。

需要在 import 中添加 `import androidx.compose.foundation.layout.Spacer` 和 `import androidx.compose.foundation.layout.height`（如果尚未存在）以及 `import androidx.compose.ui.window.DialogProperties`。

- [ ] 编译验证

---

## 阶段 3：第一组 — AlertDialog → BasicAlertDialog（8 个弹窗）

---

## Task 10: ChatTopBar.kt — Token 详情弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatTopBar.kt`

**改动范围:** 行 154-225（Token 详情 AlertDialog）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 替换行 157-225

将:
```kotlin
                    AlertDialog(
                        onDismissRequest = { showContextDialog = false },
                        title = {
                            Text(stringResource(R.string.chat_context_detail_title))
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.chat_context_detail_window,
                                        formatTokenCount(contextWindow)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(
                                        R.string.chat_context_detail_usage,
                                        pct,
                                        formatTokenCount(lastContextTokens)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    tokenRow(
                                        label = stringResource(R.string.chat_context_detail_input),
                                        value = formatTokenCount(totalInputTokens)
                                    )
                                    tokenRow(
                                        label = stringResource(R.string.chat_context_detail_output),
                                        value = formatTokenCount(totalOutputTokens)
                                    )
                                    tokenRow(
                                        label = stringResource(R.string.chat_context_detail_reasoning),
                                        value = formatTokenCount(totalReasoningTokens)
                                    )
                                    tokenRow(
                                        label = stringResource(R.string.chat_context_detail_cache_read),
                                        value = formatTokenCount(totalCacheReadTokens)
                                    )
                                    tokenRow(
                                        label = stringResource(R.string.chat_context_detail_cache_write),
                                        value = formatTokenCount(totalCacheWriteTokens)
                                    )
                                }
                                if (totalCost > 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.chat_context_detail_cost,
                                            String.format("%.4f", totalCost)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showContextDialog = false }) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    )
```

替换为:

```kotlin
                    val contextParams = amoledDialogParams()
                    BasicAlertDialog(
                        onDismissRequest = { showContextDialog = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(0.92f),
                            color = contextParams.containerColor,
                            tonalElevation = contextParams.tonalElevation,
                            border = contextParams.border,
                            shape = contextParams.shape,
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    text = stringResource(R.string.chat_context_detail_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(16.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.chat_context_detail_window,
                                            formatTokenCount(contextWindow)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.chat_context_detail_usage,
                                            pct,
                                            formatTokenCount(lastContextTokens)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Column(
                                        modifier = Modifier.padding(top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        tokenRow(
                                            label = stringResource(R.string.chat_context_detail_input),
                                            value = formatTokenCount(totalInputTokens)
                                        )
                                        tokenRow(
                                            label = stringResource(R.string.chat_context_detail_output),
                                            value = formatTokenCount(totalOutputTokens)
                                        )
                                        tokenRow(
                                            label = stringResource(R.string.chat_context_detail_reasoning),
                                            value = formatTokenCount(totalReasoningTokens)
                                        )
                                        tokenRow(
                                            label = stringResource(R.string.chat_context_detail_cache_read),
                                            value = formatTokenCount(totalCacheReadTokens)
                                        )
                                        tokenRow(
                                            label = stringResource(R.string.chat_context_detail_cache_write),
                                            value = formatTokenCount(totalCacheWriteTokens)
                                        )
                                    }
                                    if (totalCost > 0) {
                                        Text(
                                            text = stringResource(
                                                R.string.chat_context_detail_cost,
                                                String.format("%.4f", totalCost)
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                DialogButtons(
                                    buttons = listOf(
                                        Triple(stringResource(R.string.close), DialogButtonRole.Primary) { showContextDialog = false },
                                    )
                                )
                            }
                        }
                    }
```

需要确保 import 中有 `import androidx.compose.foundation.layout.Spacer` 和 `import androidx.compose.foundation.layout.height`。

- [ ] 编译验证

---

## Task 11: ChatScreenDialogs.kt — 3 个弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/dialog/ChatScreenDialogs.kt`

**改动范围:** 整个文件（119 行），3 个 AlertDialog 全部替换

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.chat.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

/**
 * Dialog for renaming the current session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameSessionDialog(
    initialTitle: String,
    onRename: (newTitle: String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameText by remember { mutableStateOf(initialTitle) }
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.session_rename),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.session_rename_button), DialogButtonRole.Primary) {
                            onRename(renameText)
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}

/**
 * Confirmation dialog shown before sending a message when "confirm before send" is enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_confirm_send_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_confirm_send_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.settings_send), DialogButtonRole.Primary) {
                            onConfirm()
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}

/**
 * Confirmation dialog for reverting a compaction-trigger message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RevertCompactionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.chat_revert_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.chat_revert_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.chat_revert), DialogButtonRole.Danger) {
                            onConfirm()
                            onDismiss()
                        },
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 12: MessageCard.kt — 撤回确认弹窗 → ConfirmDialog

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt`

**改动范围:** 行 300-319（撤回确认 AlertDialog）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import dev.minios.ocremote.ui.components.ConfirmDialog
```

- [ ] 步骤 2: 替换行 300-319

将:
```kotlin
        // 撤回确认对话框
        if (showRevertConfirmation && onRevert != null) {
            AlertDialog(
                onDismissRequest = { showRevertConfirmation = false },
                title = { Text(stringResource(R.string.chat_revert)) },
                text = { Text(stringResource(R.string.chat_revert_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showRevertConfirmation = false
                        onRevert()
                    }) {
                        Text(stringResource(R.string.chat_revert))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRevertConfirmation = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
```

替换为:

```kotlin
        // 撤回确认对话框
        if (showRevertConfirmation && onRevert != null) {
            ConfirmDialog(
                title = stringResource(R.string.chat_revert),
                message = stringResource(R.string.chat_revert_message),
                confirmLabel = stringResource(R.string.chat_revert),
                onDismiss = { showRevertConfirmation = false },
                onConfirm = {
                    showRevertConfirmation = false
                    onRevert()
                },
            )
        }
```

- [ ] 编译验证

---

## Task 13: ChatMessageList.kt — 撤回确认弹窗 → ConfirmDialog

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageList.kt`

**改动范围:** 行 249-276（撤回确认 AlertDialog）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import dev.minios.ocremote.ui.components.ConfirmDialog
```

- [ ] 步骤 2: 替换行 249-276

将:
```kotlin
                                    if (showRevertDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showRevertDialog = false },
                                            title = { Text(stringResource(R.string.chat_revert_title)) },
                                            text = { Text(stringResource(R.string.chat_revert_message)) },
                                            confirmButton = {
                                                TextButton(
                                                    onClick = {
                                                        showRevertDialog = false
                                                        viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(
                                                                    if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                                )
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Text(stringResource(R.string.chat_revert), color = MaterialTheme.colorScheme.error)
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showRevertDialog = false }) {
                                                    Text(stringResource(R.string.cancel))
                                                }
                                            }
                                        )
                                    }
```

替换为:

```kotlin
                                    if (showRevertDialog) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.chat_revert_title),
                                            message = stringResource(R.string.chat_revert_message),
                                            confirmLabel = stringResource(R.string.chat_revert),
                                            onDismiss = { showRevertDialog = false },
                                            onConfirm = {
                                                showRevertDialog = false
                                                viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
```

- [ ] 编译验证

---

## Task 14: TerminalFontSizeDialog.kt — 字号选择弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/TerminalFontSizeDialog.kt`

**改动范围:** 整个文件（63 行）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalFontSizeDialog(
    currentSize: Float,
    onSizeSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(currentSize) { mutableFloatStateOf(currentSize.coerceIn(6f, 20f)) }
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_terminal_font_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_terminal_font_size_value, selected.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    valueRange = 6f..20f,
                    steps = 13
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.ok), DialogButtonRole.Primary) {
                            onSizeSelected(selected.roundToInt().toFloat())
                        },
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 15: LocalServerLaunchOptionsDialog.kt — 主弹窗 AlertDialog→BasicAlertDialog

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt`

**改动范围:** 行 101-310（主弹窗 AlertDialog）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

> **注意**: 这是第一组中最复杂的迁移。主弹窗使用 AlertDialog，需要改为 BasicAlertDialog。同时这个文件也包含超时子弹窗（已在 Task 9 中处理）。

- [ ] 步骤 1: 添加/修改 imports

确保有:
```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 替换行 101-310 的 AlertDialog 为 BasicAlertDialog

将行 101:
```kotlin
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = amoledDialogModifier(),
        shape = ShapeTokens.extraLarge,
        title = {
            Text(
                text = stringResource(R.string.home_local_launch_options),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
```

替换为:
```kotlin
    val mainParams = amoledDialogParams()
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = mainParams.containerColor,
            tonalElevation = mainParams.tonalElevation,
            border = mainParams.border,
            shape = mainParams.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.home_local_launch_options),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
```

然后将 text 内容区的 Column 保持不变，但要移除最外层 Column 的 `verticalArrangement = Arrangement.spacedBy(12.dp)` 参数（因为现在由外部 Column 管理）。实际上保持不变也可以——将 `text = {` 后的整个 Column 内容搬入新的 Column 中。

- [ ] 步骤 3: 替换底部的 confirmButton/dismissButton

找到 `confirmButton = {` 和 `dismissButton = {` 部分（大约在文件末尾），替换为 DialogButtons。

将类似这样的代码:
```kotlin
        confirmButton = {
            TextButton(
                onClick = { ... },
                enabled = canSave
            ) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
```

替换为:
```kotlin
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                        Triple(stringResource(R.string.server_save), DialogButtonRole.Primary) {
                            onSave(
                                localEnabled,
                                trimmedProxyUrl,
                                trimmedNoProxy,
                                localAllowLanAccess,
                                localServerUsername,
                                localServerPassword,
                                localRunInBackground,
                                localAutoStart,
                                localStartupTimeoutSec,
                            )
                        },
                    )
                )
```

同时删除 `enabled = canSave` 逻辑或将其迁移到按钮内部。如果需要保留 disabled 状态，可以在 DialogButtons 的 Primary 按钮上添加 enabled 参数。由于 DialogButtons 是自定义组件，需要确认是否支持。如果不支持，可以直接在 onClick 中检查 canSave。

**建议**: 在 Primary 按钮的 onClick 中加 guard:
```kotlin
Triple(stringResource(R.string.server_save), DialogButtonRole.Primary) {
    if (!canSave) return@Triple
    onSave(...)
},
```

> **重要**: AlertDialog 的 text 内容中的 `Column` 闭合括号和 `}` 需要仔细调整。最外层结构变为:
> ```
> BasicAlertDialog { Surface { Column(padding=24dp) { Text(title) Spacer Column(原text内容) Spacer DialogButtons } } }
> ```

- [ ] 步骤 4: 补充 verticalScroll（Bug 修复 #7）

在 text 内容区的 Column 添加 `Modifier.verticalScroll(rememberScrollState())`:

将原 `text` 内容区（现在在 Surface 内部）的 Column 修改为:
```kotlin
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ... 原有内容 ...
                }
```

> 确保已 import `import androidx.compose.foundation.rememberScrollState` 和 `import androidx.compose.foundation.verticalScroll`。

- [ ] 编译验证

---

## 阶段 4：第二组 — AppDialog → BasicAlertDialog（13 个弹窗）

---

## Task 16: SessionListScreen.kt — 重命名 + 删除确认弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`

**改动范围:** 行 305-355（2 个 AppDialog 弹窗）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 替换行 305-330（重命名弹窗）

将:
```kotlin
    // Rename dialog
    if (showRenameDialog) {
        AppDialog(
            onDismiss = { showRenameDialog = false },
            title = stringResource(R.string.session_rename),
            content = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.session_rename_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            buttons = {
                AppDialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) { showRenameDialog = false },
                        Triple(stringResource(R.string.session_rename_button), ButtonStyle.Primary) {
                            viewModel.renameSession(renameSessionId, renameText)
                            showRenameDialog = false
                        }
                    )
                )
            }
        )
    }
```

替换为:

```kotlin
    // Rename dialog
    if (showRenameDialog) {
        val renameParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = renameParams.containerColor,
                tonalElevation = renameParams.tonalElevation,
                border = renameParams.border,
                shape = renameParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showRenameDialog = false },
                            Triple(stringResource(R.string.session_rename_button), DialogButtonRole.Primary) {
                                viewModel.renameSession(renameSessionId, renameText)
                                showRenameDialog = false
                            },
                        )
                    )
                }
            }
        }
    }
```

- [ ] 步骤 3: 替换行 332-355（删除确认弹窗）

将:
```kotlin
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AppDialog(
            onDismiss = { showDeleteDialog = false },
            title = stringResource(R.string.session_delete),
            content = {
                Text(
                    text = stringResource(R.string.session_delete_confirm, deleteSessionTitle),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                AppDialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) { showDeleteDialog = false },
                        Triple(stringResource(R.string.delete), ButtonStyle.Danger) {
                            viewModel.deleteSession(deleteSessionId)
                            showDeleteDialog = false
                        }
                    )
                )
            }
        )
    }
```

替换为:

```kotlin
    // Delete confirmation dialog
    if (showDeleteDialog) {
        val deleteParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = deleteParams.containerColor,
                tonalElevation = deleteParams.tonalElevation,
                border = deleteParams.border,
                shape = deleteParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.session_delete_confirm, deleteSessionTitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) { showDeleteDialog = false },
                            Triple(stringResource(R.string.delete), DialogButtonRole.Danger) {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                        )
                    )
                }
            }
        }
    }
```

- [ ] 编译验证

---

## Task 17: SessionRow.kt — 会话详情弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionRow.kt`

**改动范围:** 行 180-262（SessionDetailsDialog + private DetailRow）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.DetailRow
```

- [ ] 步骤 2: 替换行 180-239（SessionDetailsDialog）

将:
```kotlin
@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    isAmoled: Boolean,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.session_session_details),
        isAmoled = isAmoled,
        content = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow(
                        stringResource(R.string.session_details_name),
                        item.session.title ?: stringResource(R.string.session_untitled)
                    )
                    DetailRow(stringResource(R.string.session_details_id), item.session.id)
                    DetailRow(
                        stringResource(R.string.session_details_status),
                        when (item.status) {
                            is SessionStatus.Busy -> stringResource(R.string.session_status_busy)
                            is SessionStatus.Retry -> stringResource(R.string.session_status_retry)
                            else -> stringResource(R.string.session_status_idle)
                        }
                    )
                    DetailRow(
                        stringResource(R.string.session_details_created),
                        dateFormat.format(Date(item.session.time.created))
                    )
                    DetailRow(
                        stringResource(R.string.session_details_updated),
                        dateFormat.format(Date(item.session.time.updated))
                    )
                    val summary = item.session.summary
                    if (summary != null) {
                        DetailRow(
                            "Diff",
                            "+${summary.additions} -${summary.deletions} (${summary.files} files)"
                        )
                    }
                }
            }
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.menu_copy_session_id), ButtonStyle.Secondary, onCopyId),
                    Triple(stringResource(R.string.session_rename), ButtonStyle.Secondary) { onDismiss(); onRename() },
                    Triple(stringResource(R.string.session_delete), ButtonStyle.Danger) { onDismiss(); onDelete() },
                )
            )
        }
    )
}
```

替换为:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailsDialog(
    item: SessionItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyId: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isAmoled: Boolean,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.session_session_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow(
                            stringResource(R.string.session_details_name),
                            item.session.title ?: stringResource(R.string.session_untitled)
                        )
                        DetailRow(stringResource(R.string.session_details_id), item.session.id)
                        DetailRow(
                            stringResource(R.string.session_details_status),
                            when (item.status) {
                                is SessionStatus.Busy -> stringResource(R.string.session_status_busy)
                                is SessionStatus.Retry -> stringResource(R.string.session_status_retry)
                                else -> stringResource(R.string.session_status_idle)
                            }
                        )
                        DetailRow(
                            stringResource(R.string.session_details_created),
                            dateFormat.format(Date(item.session.time.created))
                        )
                        DetailRow(
                            stringResource(R.string.session_details_updated),
                            dateFormat.format(Date(item.session.time.updated))
                        )
                        val summary = item.session.summary
                        if (summary != null) {
                            DetailRow(
                                "Diff",
                                "+${summary.additions} -${summary.deletions} (${summary.files} files)"
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.menu_copy_session_id), DialogButtonRole.Secondary, onCopyId),
                        Triple(stringResource(R.string.session_rename), DialogButtonRole.Secondary) { onDismiss(); onRename() },
                        Triple(stringResource(R.string.session_delete), DialogButtonRole.Danger) { onDismiss(); onDelete() },
                    )
                )
            }
        }
    }
}
```

- [ ] 步骤 3: 删除行 241-262（private DetailRow）

删除整个 private DetailRow 函数（现在使用共享的 `dev.minios.ocremote.ui.components.DetailRow`）。

- [ ] 编译验证

---

## Task 18: DirectoryTreeNode.kt — 目录详情弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/DirectoryTreeNode.kt`

**改动范围:** 行 128-178（DirectoryDetailsDialog + private DetailRow）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
import dev.minios.ocremote.ui.components.DetailRow
```

- [ ] 步骤 2: 替换行 128-155（DirectoryDetailsDialog）

将:
```kotlin
@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
    isAmoled: Boolean,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.session_directory_details),
        isAmoled = isAmoled,
        content = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow(label = stringResource(R.string.session_path), value = node.path)
                    DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
                }
            }
        },
        buttons = {
            AppDialogButtons(
                buttons = listOf(
                    Triple(stringResource(R.string.session_copy_path), ButtonStyle.Secondary, onCopyPath),
                )
            )
        }
    )
}
```

替换为:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryDetailsDialog(
    node: TreeNode.Directory,
    onDismiss: () -> Unit,
    onCopyPath: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isAmoled: Boolean,
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.session_directory_details),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow(label = stringResource(R.string.session_path), value = node.path)
                        DetailRow(label = stringResource(R.string.session_count), value = node.sessionCount.toString())
                    }
                }
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.session_copy_path), DialogButtonRole.Secondary, onCopyPath),
                    )
                )
            }
        }
    }
}
```

- [ ] 步骤 3: 删除行 157-178（private DetailRow）

删除整个 private DetailRow 函数。

- [ ] 编译验证

---

## Task 19: OpenProjectDialog.kt — 创建文件夹弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/OpenProjectDialog.kt`

**改动范围:** 行 231-310（创建文件夹 AppDialog）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 添加 imports

```kotlin
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

- [ ] 步骤 2: 替换行 231-310（创建文件夹弹窗）

将:
```kotlin
    // Create folder dialog
    if (showCreateFolderDialog) {
        AppDialog(
            onDismiss = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            title = stringResource(R.string.sessions_create_folder_title),
            isAmoled = isAmoled,
            content = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = {
                        newFolderName = it
                        createFolderError = null
                    },
                    singleLine = true,
                    enabled = !isCreatingFolder,
                    label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                    placeholder = {
                        Text(stringResource(R.string.sessions_create_folder_name_placeholder))
                    },
                    isError = createFolderError != null,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (createFolderError != null) {
                    Text(
                        text = createFolderError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            buttons = {
                AppDialogButtons(
                    listOf(
                        Triple(stringResource(R.string.cancel), ButtonStyle.Secondary) {
                            showCreateFolderDialog = false
                        },
                        Triple(
                            stringResource(R.string.sessions_create_folder_create),
                            ButtonStyle.Primary,
                        ) {
                            val parent = currentDir ?: homeDir ?: "/"
                            val name = newFolderName.trim()
                            if (name.isBlank()) {
                                createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                                return@Triple
                            }

                            isCreatingFolder = true
                            scope.launch {
                                val result = viewModel.createDirectory(parent, name)
                                isCreatingFolder = false
                                result.onSuccess { createdPath ->
                                    showCreateFolderDialog = false
                                    newFolderName = ""
                                    createFolderError = null
                                    currentDir = parent
                                    directories = viewModel.listDirectories(parent)
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(
                                                R.string.sessions_create_folder_success,
                                                tildeReplace(createdPath)
                                            ),
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }.onFailure { error ->
                                    createFolderError = error.message
                                        ?: context.getString(R.string.sessions_create_folder_failed)
                                }
                            }
                        },
                    ),
                )
            },
        )
    }
```

替换为:

```kotlin
    // Create folder dialog
    if (showCreateFolderDialog) {
        val createFolderParams = amoledDialogParams()
        BasicAlertDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                color = createFolderParams.containerColor,
                tonalElevation = createFolderParams.tonalElevation,
                border = createFolderParams.border,
                shape = createFolderParams.shape,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.sessions_create_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                        placeholder = {
                            Text(stringResource(R.string.sessions_create_folder_name_placeholder))
                        },
                        isError = createFolderError != null,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createFolderError != null) {
                        Text(
                            text = createFolderError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    DialogButtons(
                        buttons = listOf(
                            Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
                                showCreateFolderDialog = false
                            },
                            Triple(
                                stringResource(R.string.sessions_create_folder_create),
                                DialogButtonRole.Primary,
                            ) {
                                val parent = currentDir ?: homeDir ?: "/"
                                val name = newFolderName.trim()
                                if (name.isBlank()) {
                                    createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                                    return@Triple
                                }

                                isCreatingFolder = true
                                scope.launch {
                                    val result = viewModel.createDirectory(parent, name)
                                    isCreatingFolder = false
                                    result.onSuccess { createdPath ->
                                        showCreateFolderDialog = false
                                        newFolderName = ""
                                        createFolderError = null
                                        currentDir = parent
                                        directories = viewModel.listDirectories(parent)
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(
                                                    R.string.sessions_create_folder_success,
                                                    tildeReplace(createdPath)
                                                ),
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }.onFailure { error ->
                                        createFolderError = error.message
                                            ?: context.getString(R.string.sessions_create_folder_failed)
                                    }
                                }
                            },
                        )
                    )
                }
            }
        }
    }
```

- [ ] 编译验证

---

## Task 20-25: 6 个 Picker 弹窗（AppDialog → BasicAlertDialog）

> 这些弹窗结构高度相似：AppDialog + AppPickerList + AppDialogButtons。统一模板如下。

### 通用模板

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun XxxPickerDialog(
    currentXxx: Xxx,
    onXxxSelected: (Xxx) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.xxx_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = ...,
                    selectedKey = currentXxx,
                    onSelect = onXxxSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

---

## Task 20: ThemePickerDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/ThemePickerDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemePickerDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 480.dp),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dialog_select_theme),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark)
                    ),
                    selectedKey = currentTheme,
                    onSelect = onThemeSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 21: LanguagePickerDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/LanguagePickerDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemDefault = stringResource(R.string.settings_language_system)
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dialog_select_language),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "" to systemDefault,
                        "en" to "English",
                        "ar" to "العربية",
                        "de" to "Deutsch",
                        "es" to "Español",
                        "fr" to "Français",
                        "id" to "Bahasa Indonesia",
                        "it" to "Italiano",
                        "ja" to "日本語",
                        "ko" to "한국어",
                        "pl" to "Polski",
                        "pt-BR" to "Português (Brasil)",
                        "ru" to "Русский",
                        "tr" to "Türkçe",
                        "uk" to "Українська",
                        "zh-CN" to "简体中文"
                    ),
                    selectedKey = currentLanguage,
                    onSelect = onLanguageSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 22: FontSizePickerDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/FontSizePickerDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizePickerDialog(
    currentSize: String,
    onSizeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_font_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "small" to stringResource(R.string.settings_font_size_small),
                        "medium" to stringResource(R.string.settings_font_size_medium),
                        "large" to stringResource(R.string.settings_font_size_large)
                    ),
                    selectedKey = currentSize,
                    onSelect = onSizeSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 23: MessageCountPickerDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/MessageCountPickerDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageCountPickerDialog(
    currentCount: Int,
    onCountSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_initial_messages),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(20, 50, 100, 200).map { it to "$it" },
                    selectedKey = currentCount,
                    onSelect = onCountSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 24: ReconnectModePickerDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/ReconnectModePickerDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReconnectModePickerDialog(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dialog_select_reconnect_mode),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = listOf(
                        "aggressive" to stringResource(R.string.settings_reconnect_aggressive),
                        "normal" to stringResource(R.string.settings_reconnect_normal),
                        "conservative" to stringResource(R.string.settings_reconnect_conservative)
                    ),
                    selectedKey = currentMode,
                    onSelect = onModeSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## Task 25: ImageCompressionDialog.kt — 2 个弹窗

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/ImageCompressionDialog.kt`

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 替换整个文件

```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.AppPickerList
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageCompressionMaxSideDialog(
    currentMaxSide: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(0, 720, 960, 1080, 1440, 1920, 2560)
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_compress_images_max_side),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = options.map { it to getImageMaxSideDisplayName(it) },
                    selectedKey = currentMaxSide,
                    onSelect = onSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageCompressionQualityDialog(
    currentQuality: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(40, 50, 60, 70, 80)
    val params = amoledDialogParams()

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.settings_compress_images_quality),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                AppPickerList(
                    options = options.map {
                        it to stringResource(R.string.settings_compress_images_quality_value, it)
                    },
                    selectedKey = currentQuality,
                    onSelect = onSelected,
                )
                Spacer(Modifier.height(16.dp))
                DialogButtons(
                    buttons = listOf(
                        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss),
                    )
                )
            }
        }
    }
}
```

- [ ] 编译验证

---

## 阶段 5：列表样式统一 + 间距优化

---

## Task 26: ServerSettingsScreen.kt — AmoledCard → ListItem

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/server/ServerSettingsScreen.kt`

**改动范围:** 行 54-130（列表区域）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 替换 imports

删除:
```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import dev.minios.ocremote.ui.components.AmoledCard
import dev.minios.ocremote.ui.theme.AlphaTokens
import dev.minios.ocremote.ui.theme.LocalAmoledMode
import dev.minios.ocremote.ui.theme.ShapeTokens
```

添加:
```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
```

> 注意: 精简 import 时请根据实际编译结果调整。

- [ ] 步骤 2: 替换行 54-130（Scaffold content）

将:
```kotlin
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AmoledCard(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.medium,
                normalContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProviders)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Hub, contentDescription = null)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.server_settings_providers),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.server_settings_providers_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            AmoledCard(
                isAmoledDark = isAmoled,
                shape = ShapeTokens.medium,
                normalContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModels)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.server_settings_models),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.server_settings_models_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
```

替换为:

```kotlin
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.server_settings_providers))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.server_settings_providers_desc))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Hub, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenProviders),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.server_settings_models))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.server_settings_models_desc))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Tune, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onOpenModels),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
```

> **注意**: 删除 `val isAmoled = LocalAmoledMode.current` 变量（行 42），因为不再需要。如果该变量仍被其他地方引用则保留。

- [ ] 编译验证

---

## Task 27: ServerProvidersScreen.kt — 列表部分 ProviderRow → ListItem

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/server/ServerProvidersScreen.kt`

**改动范围:** 行 395-469（列表区域）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

> **注意**: ProviderRow 是一个独立的 Composable（在 `ui/screens/server/components/ProviderRow.kt` 中）。此 Task 改为在列表中使用 ListItem 替代 ProviderRow，需要读取 ProviderRow 的实现来理解其字段映射。

- [ ] 步骤 1: 读取 ProviderRow 的实现了解其展示字段

需要先读取 `ui/screens/server/components/ProviderRow.kt` 了解 ProviderRow 展示了哪些信息（icon、name、status、chevron等）。

- [ ] 步骤 2: 替换行 407-469 的列表区域

将:
```kotlin
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
```

替换为:
```kotlin
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
```

将行 424-443 的 connected section header:
```kotlin
            if (connected.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.server_settings_providers_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                    )
                }
                items(connected, key = { it.providerId }) { provider ->
                    ProviderRow(...)
                }
            }
```

替换为:
```kotlin
            if (connected.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.server_settings_providers_connected))
                }
                items(connected, key = { it.providerId }) { provider ->
                    ProviderRow(
                        provider = provider,
                        onConnect = { viewModel.clearError(); connectProvider = provider },
                        onDisconnect = { viewModel.disconnectProvider(provider.providerId) },
                        showConnect = false,
                        canDisconnect = provider.source != "env",
                        isSaving = uiState.isSaving,
                        isAmoled = isAmoled,
                        showSource = true
                    )
                    HorizontalDivider()
                }
            }
```

> **注意**: SectionHeader 需要导入。如果 SectionHeader 是 `internal` 的（在 settings 包中），可能需要使用行内 Text 组件替代，或将 SectionHeader 改为 `public`。请根据编译结果调整。

同样处理 available section。

- [ ] 编译验证

---

## Task 28: ServerModelFilterScreen.kt — AmoledCard 分组 → ListItem + SectionHeader

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/server/ServerModelFilterScreen.kt`

**改动范围:** 行 137-198（列表区域）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 替换行 137-198

将:
```kotlin
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredGroups, key = { it.providerId }) { group ->
                            AmoledCard(
                                isAmoledDark = isAmoled,
                                shape = ShapeTokens.medium,
                                normalContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text(
                                        text = group.providerName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    group.models.forEach { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = model.modelName,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = model.modelId,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                                                )
                                            }
                                            Switch(
                                                checked = model.visible,
                                                onCheckedChange = { checked ->
                                                    viewModel.setModelVisible(group.providerId, model.modelId, checked)
                                                },
                                                colors = if (isAmoled) {
                                                    SwitchDefaults.colors(
                                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                        checkedTrackColor = Color.Black,
                                                        checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                                                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                                        uncheckedTrackColor = Color.Black,
                                                        uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
                                                    )
                                                } else {
                                                    SwitchDefaults.colors()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
```

替换为:

```kotlin
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredGroups, key = { it.providerId }) { group ->
                            item(key = "header_${group.providerId}") {
                                SectionHeader(group.providerName)
                            }
                            items(group.models, key = { "${group.providerId}_${it.modelId}" }) { model ->
                                ListItem(
                                    headlineContent = {
                                        Text(model.modelName)
                                    },
                                    supportingContent = {
                                        Text(
                                            text = model.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
                                        )
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = model.visible,
                                            onCheckedChange = { checked ->
                                                viewModel.setModelVisible(group.providerId, model.modelId, checked)
                                            },
                                            colors = if (isAmoled) {
                                                SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                                    checkedTrackColor = Color.Black,
                                                    checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH),
                                                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                                    uncheckedTrackColor = Color.Black,
                                                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH)
                                                )
                                            } else {
                                                SwitchDefaults.colors()
                                            }
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
```

> **注意**: `SectionHeader` 是 `internal` 的，在 `ui/screens/settings/components/SectionHeader.kt` 中。由于 `ServerModelFilterScreen` 在 `ui/screens/server/` 包中，可能无法访问。解决方案：
> 1. 将 SectionHeader 改为 public（推荐）
> 2. 在 server 包中创建相同的 SectionHeader
> 3. 直接使用 Text + labelMedium 样式内联

**推荐方案**: 将 SectionHeader.kt 中的 `internal fun SectionHeader` 改为 `fun SectionHeader`（去掉 internal），这样所有包都可以使用。

- [ ] 编译验证

---

## Task 29: ServerCard.kt — 间距优化

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/components/ServerCard.kt`

**改动范围:** 行 52（spacedBy(12.dp)→spacedBy(8.dp)）、行 139 和 168（spacedBy(8.dp)→spacedBy(6.dp)）

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 行 52 — 将 spacedBy(12.dp) 改为 spacedBy(8.dp)

```kotlin
// 行 52: 改前
verticalArrangement = Arrangement.spacedBy(12.dp)
// 行 52: 改后
verticalArrangement = Arrangement.spacedBy(8.dp)
```

- [ ] 步骤 2: 行 139 — 将 spacedBy(8.dp) 改为 spacedBy(6.dp)

```kotlin
// 行 139: 改前
horizontalArrangement = Arrangement.spacedBy(8.dp)
// 行 139: 改后
horizontalArrangement = Arrangement.spacedBy(6.dp)
```

- [ ] 步骤 3: 行 168 — 将 spacedBy(8.dp) 改为 spacedBy(6.dp)

```kotlin
// 行 168: 改前
horizontalArrangement = Arrangement.spacedBy(8.dp)
// 行 168: 改后
horizontalArrangement = Arrangement.spacedBy(6.dp)
```

- [ ] 编译验证

---

## 阶段 6：清理

---

## Task 30: SettingsDisplayNames.kt — 删除旧辅助函数

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/SettingsDisplayNames.kt`

**改动范围:** 删除行 79-97（amoledDialogModifier + amoledDialogContainerColor）

**前置条件:** Task 14（TerminalFontSizeDialog）和 Task 15（LocalServerLaunchOptionsDialog）完成迁移后，这两个函数不再被引用。

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 全局搜索确认无引用

```
grep -r "amoledDialogModifier\|amoledDialogContainerColor" --include="*.kt"
```

确认只有 SettingsDisplayNames.kt 中的定义和 TerminalFontSizeDialog.kt / LocalServerLaunchOptionsDialog.kt 中的引用。如果还有其他文件引用，需先迁移。

- [ ] 步骤 2: 删除行 79-97

删除:
```kotlin
@Composable
internal fun amoledDialogModifier(): Modifier {
    val isAmoledTheme = LocalAmoledMode.current
    return if (isAmoledTheme) {
        Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.HIGH),
            shape = ShapeTokens.extraLarge,
        )
    } else {
        Modifier
    }
}

@Composable
internal fun amoledDialogContainerColor(): Color {
    val isAmoledTheme = LocalAmoledMode.current
    return if (isAmoledTheme) Color.Black else AlertDialogDefaults.containerColor
}
```

- [ ] 步骤 3: 清理无用 import

删除不再需要的 import:
- `import androidx.compose.ui.Modifier`
- `import androidx.compose.ui.draw.clip`
- `import androidx.compose.foundation.border`
- `import androidx.compose.material3.AlertDialogDefaults`
- `import androidx.compose.ui.graphics.Color`
- `import dev.minios.ocremote.ui.theme.ShapeTokens`
- `import dev.minios.ocremote.ui.theme.LocalAmoledMode`

（仅删除在删除函数后不再被使用的 import。文件中其他函数可能仍在使用某些 import，需仔细检查。）

- [ ] 编译验证

---

## Task 31: 删除 AppDialog.kt

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt`

**前置条件:** 第二组全部 13 个 AppDialog 弹窗迁移完成（Task 16-25）。

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 全局搜索确认无 AppDialog 引用

```
grep -r "AppDialog\|AppDialogButtons\|ButtonStyle" --include="*.kt"
```

确认没有文件引用 `AppDialog`、`AppDialogButtons` 或 `ButtonStyle`。

- [ ] 步骤 2: 删除文件

删除 `app/src/main/kotlin/dev/minios/ocremote/ui/components/AppDialog.kt`

- [ ] 编译验证

---

## Task 32: Bug 修复 — LocalServerLaunchOptionsDialog 补滚动

**文件:** `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt`

**前置条件:** Task 15 已完成主弹窗迁移。

**验证:** `.\gradlew :app:compileDevDebugKotlin`

> **注意**: 如果 Task 15 中已经在迁移过程中补上了 `verticalScroll`，则此 Task 可跳过。

- [ ] 步骤 1: 确认 text 内容区 Column 是否有 verticalScroll

检查迁移后的代码中，text 内容区的 Column 是否有:
```kotlin
modifier = Modifier.verticalScroll(rememberScrollState())
```

如果没有，添加该 modifier。

- [ ] 步骤 2: 添加 heightIn 约束

确保 Column 同时有 heightIn 约束以防止超高弹窗:
```kotlin
Column(
    modifier = Modifier
        .verticalScroll(rememberScrollState())
        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.7f),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {
```

> 确保 import 了 `import androidx.compose.foundation.layout.heightIn`、`import androidx.compose.foundation.rememberScrollState`、`import androidx.compose.foundation.verticalScroll`、`import androidx.compose.ui.platform.LocalConfiguration`。

- [ ] 编译验证

---

## Task 33: 最终编译验证 + SectionHeader 可见性调整

**验证:** `.\gradlew :app:compileDevDebugKotlin`

- [ ] 步骤 1: 将 SectionHeader 改为 public（如果 Task 28 需要）

编辑 `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/components/SectionHeader.kt`:

将:
```kotlin
internal fun SectionHeader(title: String) {
```

改为:
```kotlin
fun SectionHeader(title: String) {
```

- [ ] 步骤 2: 全量编译验证

```
.\gradlew :app:compileDevDebugKotlin
```

确认 0 errors。

- [ ] 步骤 3: 全量构建验证

```
.\gradlew :app:assembleDevRelease
```

确认构建成功。

---

## 附录 A: 弹窗统一模板速查

所有使用 BasicAlertDialog + Surface 的弹窗遵循以下统一结构:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyDialog(
    /* params */,
    onDismiss: () -> Unit,
) {
    val params = amoledDialogParams()
    // 可选: 自定义 shape
    // val params = amoledDialogParams(shape = ShapeTokens.largeMedium)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = params.containerColor,
            tonalElevation = params.tonalElevation,
            border = params.border,
            shape = params.shape,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // 标题
                Text(
                    text = "标题",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                // 内容区
                // ...
                Spacer(Modifier.height(16.dp))
                // 按钮区
                DialogButtons(
                    buttons = listOf(
                        Triple("取消", DialogButtonRole.Secondary, onDismiss),
                        Triple("确认", DialogButtonRole.Primary) { /* action */ },
                    )
                )
            }
        }
    }
}
```

**关键 import 清单:**
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.minios.ocremote.ui.components.amoledDialogParams
import dev.minios.ocremote.ui.components.DialogButtons
import dev.minios.ocremote.ui.components.DialogButtonRole
```

---

## 附录 B: 第三组已有弹窗 shape 兼容性

第三组弹窗（ServerDialog、ServerProvidersScreen、ModelPickerDialog、ImagePreviewDialog、LocalLaunchOptionsDialog）原使用 `ShapeTokens.largeMedium`（20dp）。统一迁移后，这些弹窗的 `amoledDialogParams()` 调用需要显式传入 shape 参数:

```kotlin
val params = amoledDialogParams(shape = ShapeTokens.largeMedium)
```

以保持与原有圆角大小一致。如果后续希望统一为 28dp 圆角，可移除 shape 参数使用默认值。
