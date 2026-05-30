# 统一 MessageCard 组件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 合并 ChatMessageBubble 和 AssistantMessageCard 为统一的 MessageCard，Agent 回复加灰色气泡，同轮消息合并，并修复切回会话时 thinking 动画不停止的 bug。

**Architecture:** 新建 `MessageCard.kt` 统一处理 User/Assistant 两种角色的气泡渲染、Parts 显示和统计栏。ChatScreen 的 LazyColumn 从逐条渲染改为按 turn 分组。同时修复 `MessageEventHandler.markSessionIdle()` 未更新 `Part.Reasoning.time.end` 的 bug。

**Tech Stack:** Kotlin, Jetpack Compose, Material3

---

### Task 1: 修复切回会话 thinking 不停止的问题

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/sse/handler/MessageEventHandler.kt`

**背景：** 切回会话时 `syncSessionStatus()` → `markSessionIdle()` 只更新了 `Message.time.completed`，没有设置 `Part.Reasoning.time.end`，导致 `ReasoningBlock` 的 `durationMs` 始终为 null，脉冲动画不停止。

- [ ] **Step 1: 修改 markSessionIdle() 函数**

文件：`app/src/main/kotlin/dev/minios/ocremote/data/sse/handler/MessageEventHandler.kt`

找到 `markSessionIdle()` 函数（约 L140-151），在现有逻辑之后新增对 `Part.Reasoning.time.end` 的更新：

```kotlin
fun markSessionIdle(sessionId: String) {
    val current = _messages.value[sessionId] ?: return
    val now = System.currentTimeMillis()
    
    // 更新消息的 completed 时间
    val updated = current.map { msg ->
        if (msg is Message.Assistant && msg.time.completed == null) {
            msg.copy(time = msg.time.copy(completed = now))
        } else {
            msg
        }
    }
    _messages.value = _messages.value + (sessionId to updated)

    // 新增：标记所有未完成 Reasoning part 的 time.end
    val parts = _parts.value[sessionId]
    if (parts != null) {
        val updatedParts = parts.map { part ->
            if (part is Part.Reasoning && part.time?.end == null) {
                part.copy(time = Part.Reasoning.Time(
                    start = part.time?.start ?: now,
                    end = now
                ))
            } else {
                part
            }
        }
        _parts.value = _parts.value + (sessionId to updatedParts)
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/sse/handler/MessageEventHandler.kt
git commit -m "fix: mark Reasoning part time.end in markSessionIdle to stop thinking animation on resume"
```

---

### Task 2: 创建 MessageCard 统一组件

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt`
- Reference: `ChatMessageBubble.kt` (L60-298) — 用户消息逻辑
- Reference: `AssistantMessageCard.kt` (L46-255) — Agent 回复逻辑

**任务说明：** 创建新的 `MessageCard.kt`，合并两个旧组件的全部逻辑。组件根据 `role` 参数切换气泡颜色、对齐方式和统计栏内容。

- [ ] **Step 1: 创建 MessageCard.kt 文件**

在 `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/` 下创建 `MessageCard.kt`。

**完整组件代码：**

```kotlin
package dev.minios.ocremote.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minios.ocremote.domain.model.ChatMessage
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.ui.screens.chat.LocalCompactMessages
import dev.minios.ocremote.ui.screens.chat.theme.ChatColors
import dev.minios.ocremote.ui.screens.chat.util.formatDuration
import dev.minios.ocremote.ui.screens.chat.util.performHaptic
import dev.minios.ocremote.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turn 内某条消息的渲染数据。
 */
data class TurnMessageData(
    val message: ChatMessage,
    val parts: List<Part>,
    /** 该消息在 turn 中是第几条（0-based），用于判断是否显示 reasoning/tool headers */
    val indexInTurn: Int,
    /** 该 turn 的总消息数 */
    val totalInTurn: Int
)

/**
 * 统一的消息卡片组件 — 用户消息和 Agent 回复共用。
 *
 * @param role 消息角色
 * @param turnMessages Agent turn 中的所有消息（仅 Agent 有效，User 为 null）
 * @param currentMessage 当前正在渲染的这条消息（Agent 时为 turn 的 aggregator；User 时为自身）
 * @param isQueued 是否排队中（仅 User）
 * @param onViewSubSession 查看子会话回调
 * @param onRevert 撤回回调（仅 User 主会话）
 * @param onCopyText 复制回调
 * @param isAmoled AMOLED 模式
 */
@Composable
fun MessageCard(
    role: MessageCardRole,
    turnMessages: List<ChatMessage>? = null,
    currentMessage: ChatMessage,
    isQueued: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    isAmoled: Boolean = false
) {
    val compact = LocalCompactMessages.current
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val hapticOn = androidx.compose.foundation.text.LocalHapticFeedbackEnabled.current

    // 撤回确认对话框 state
    var showRevertConfirmation by remember { mutableStateOf(false) }

    // === 气泡样式计算 ===
    val (backgroundColor, border, alignment, shape) = when (role) {
        MessageCardRole.USER -> {
            val providerId = currentMessage.message.id // 使用现有 provider 着色逻辑
            val color = if (isAmoled) Color.Black
                else ChatColors.providerColor(providerId, MaterialTheme.colorScheme.primaryContainer)
            val bdr = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                else null
            Quadruple(
                color,
                bdr,
                Alignment.End,
                RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            )
        }
        MessageCardRole.ASSISTANT -> {
            Quadruple(
                MaterialTheme.colorScheme.surfaceVariant,
                null,
                Alignment.Start,
                RoundedCornerShape(12.dp)
            )
        }
    }

    // 外层 Column 控制对齐
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = shape,
            color = backgroundColor,
            border = border,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 16.dp,
                    vertical = if (compact) 8.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (compact) 4.dp else 8.dp
                )
            ) {
                when (role) {
                    MessageCardRole.ASSISTANT -> {
                        // 渲染 turn 内所有消息的 parts
                        val msgs = turnMessages ?: listOf(currentMessage)
                        msgs.forEachIndexed { msgIdx, chatMsg ->
                            val parts = chatMsg.parts.filter { part ->
                                // 过滤掉空文本和非渲染类型（但保留 reasoning 和 tool parts）
                                when (part) {
                                    is Part.Text -> part.text.isNotBlank()
                                    is Part.Reasoning -> part.text.isNotBlank()
                                    else -> true
                                }
                            }
                            parts.forEach { part ->
                                key(part.id) {
                                    PartContent(
                                        part = part,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        isUser = false,
                                        messageIndex = msgIdx,
                                        isInTurnGroup = msgs.size > 1
                                    )
                                }
                            }
                        }
                    }
                    MessageCardRole.USER -> {
                        // 用户消息：渲染 text parts + fallback
                        val parts = currentMessage.parts
                        val imageFiles = parts.filterIsInstance<Part.ImageFile>()
                        val otherParts = parts.filter { it !is Part.ImageFile }

                        if (imageFiles.isNotEmpty()) {
                            ImageThumbnailRow(
                                files = imageFiles,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        val renderableParts = otherParts.filter { part ->
                            when (part) {
                                is Part.Text -> part.text.isNotBlank()
                                else -> false
                            }
                        }

                        if (renderableParts.isNotEmpty()) {
                            renderableParts.forEach { part ->
                                key(part.id) {
                                    PartContent(
                                        part = part,
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                        isUser = true
                                    )
                                }
                            }
                        } else {
                            // Fallback: userCommandLabel
                            val userCommandLabel = currentMessage.parts
                                .filterIsInstance<Part.ToolCall>()
                                .firstOrNull()?.displayName
                            if (userCommandLabel != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                    Text(
                                        text = userCommandLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                            // Fallback: userFallbackText
                            val userFallbackText = currentMessage.parts
                                .filterIsInstance<Part.Text>()
                                .joinToString("\n") { it.text }
                                .ifBlank { currentMessage.parts.firstOrNull()?.toString() ?: "" }
                            if (userFallbackText.isNotBlank() && (renderableParts.isEmpty() && userCommandLabel == null)) {
                                Text(
                                    text = userFallbackText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // === FooterStatsRow — 统一统计栏 ===
                FooterStatsRow(
                    role = role,
                    turnMessages = turnMessages,
                    currentMessage = currentMessage,
                    hapticView = hapticView,
                    hapticOn = hapticOn,
                    isQueued = isQueued,
                    onRevert = onRevert,
                    onCopyText = onCopyText,
                    showRevertConfirmation = { showRevertConfirmation = true }
                )
            }
        } // end Surface
    } // end outer Column

    // === 撤回确认对话框 ===
    if (showRevertConfirmation && onRevert != null) {
        AlertDialog(
            onDismissRequest = { showRevertConfirmation = false },
            title = { Text("撤销") },
            text = { Text("确定要撤销这条消息吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showRevertConfirmation = false
                    onRevert()
                }) {
                    Text("撤销")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }
}

enum class MessageCardRole { USER, ASSISTANT }

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
```

> **⚠️ 重要**：以上代码为结构骨架。实现时需精确对照 `ChatMessageBubble.kt` 和 `AssistantMessageCard.kt` 的源码细节，确保所有逻辑完整迁移。关键要点：
> - Provider 着色逻辑需从 `ChatMessageBubble.kt` L67-85 精确复制
> - 用户消息的 `imageFiles` 过滤逻辑、`renderableParts` 过滤逻辑需精确复制
> - Agent 的 parts 遍历逻辑需与 `AssistantMessageCard.kt` L85-98 一致
> - `FooterStatsRow` 的统计栏元素顺序见 Task 2 Step 2
> - `stringResource(R.string.*)` 需替代硬编码中文

- [ ] **Step 2: 实现 FooterStatsRow 子组件**

在 `MessageCard.kt` 底部添加 `FooterStatsRow` composable：

```kotlin
@Composable
private fun FooterStatsRow(
    role: MessageCardRole,
    turnMessages: List<ChatMessage>?,
    currentMessage: ChatMessage,
    hapticView: android.view.View,
    hapticOn: Boolean,
    isQueued: Boolean = false,
    onRevert: (() -> Unit)? = null,
    onCopyText: (() -> Unit)? = null,
    showRevertConfirmation: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (role) {
            MessageCardRole.USER -> {
                // 时间
                Text(
                    text = rememberFormatTime(currentMessage.message.time.created),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
                // QUEUED badge
                if (isQueued) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ChatColors.QueuedBadgeColor,
                    ) {
                        Text(
                            text = "QUEUED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp,
                                color = ChatColors.QueuedBadgeTextColor
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // Undo
                if (onRevert != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "撤销",
                        modifier = Modifier.size(14.dp).clickable {
                            performHaptic(hapticView, hapticOn)
                            showRevertConfirmation()
                        },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
                // Copy
                if (onCopyText != null) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(14.dp).clickable {
                            performHaptic(hapticView, hapticOn)
                            onCopyText()
                        },
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            MessageCardRole.ASSISTANT -> {
                // 统计数据计算
                val stepFinishes = turnMessages?.flatMap {
                    it.parts.filterIsInstance<Part.StepFinish>()
                } ?: emptyList()
                val hasFooter = stepFinishes.isNotEmpty()

                if (hasFooter) {
                    // 时间
                    Text(
                        text = rememberFormatTime(currentMessage.message.time.created),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                    // Provider 图标
                    val assistantMsg = currentMessage.message as? Message.Assistant
                    if (assistantMsg?.providerId != null) {
                        ProviderIcon(
                            providerId = assistantMsg.providerId,
                            size = 10.dp,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                    // Model
                    val modelId = (turnMessages?.lastOrNull()?.message as? Message.Assistant)?.modelId
                    if (!modelId.isNullOrBlank()) {
                        Text(
                            text = modelId,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Tokens
                    val totalInput = stepFinishes.sumOf { it.tokens?.input ?: 0 }
                    val totalOutput = stepFinishes.sumOf { it.tokens?.output ?: 0 }
                    if (totalInput > 0 || totalOutput > 0) {
                        Text(
                            text = "↑$totalInput ↓$totalOutput",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                    // Cost
                    val totalCost = stepFinishes.sumOf { it.cost ?: 0.0 }
                    if (totalCost > 0.0 && totalCost.isFinite()) {
                        Text(
                            text = String.format("%.4f", totalCost),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                    // Duration
                    val firstMsg = turnMessages?.firstOrNull()?.message
                    val lastMsg = turnMessages?.lastOrNull()?.message
                    if (firstMsg != null && lastMsg != null) {
                        val durationMs = (lastMsg.time.completed ?: 0) - firstMsg.time.created
                        if (durationMs > 0) {
                            Text(
                                text = formatDuration(durationMs),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Copy
                    if (onCopyText != null) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(14.dp).clickable {
                                performHaptic(hapticView, hapticOn)
                                onCopyText()
                            },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                } else {
                    // Fallback：只有时间和 Copy
                    Text(
                        text = rememberFormatTime(currentMessage.message.time.created),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (onCopyText != null) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            modifier = Modifier.size(14.dp).clickable {
                                performHaptic(hapticView, hapticOn)
                                onCopyText()
                            },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 格式化时间的 helper composable
 */
@Composable
private fun rememberFormatTime(createdMs: Long): String = remember(createdMs) {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(createdMs))
}
```

> **⚠️ 重要**：以上 FooterStatsRow 为结构骨架。实现时需：
> - 精确复制 AssistantMessageCard.kt 的 `durationMs` 计算逻辑（L159-165）
> - 精确复制 ProviderIcon 的参数（size=10dp, tint=alpha 0.35f）
> - 确认 `TextOverflow` 的 import
> - 确认 `ChatColors.QueuedBadgeColor` 和 `QueuedBadgeTextColor` 路径

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 可能有编译错误（因为 ChatScreen 仍引用旧组件），先记录下来。

---

### Task 3: 更新 ChatScreen 的 LazyColumn 为 turn 分组渲染

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: 更新 import**

将 L240-241 的 import 替换为：
```kotlin
import dev.minios.ocremote.ui.screens.chat.components.MessageCard
import dev.minios.ocremote.ui.screens.chat.components.MessageCardRole
```

删除 `AssistantTurnBubble` import（如果存在且未使用）。

- [ ] **Step 2: 修改主会话 LazyColumn items**

主会话 itemsIndexed 约在 L1838-1965。改为：

```kotlin
itemsIndexed(rawMessages, key = { _, msg -> msg.message.id }, contentType = { _, msg -> msg.message.role }) { index, msg ->
    when {
        msg.isAssistant -> {
            // 非首条 assistant 消息跳过，在首条渲染整轮
            val prevIndex = index + 1  // newest-first 列表
            val prevMsg = rawMessages.getOrNull(prevIndex)
            if (prevMsg?.isAssistant == true) return@itemsIndexed

            val turnMessagesForMsg = turnGroups[index] ?: listOf(msg)
            val isTurnLast = index == 0 || rawMessages.getOrNull(index - 1)?.isAssistant != true

            MessageCard(
                role = MessageCardRole.ASSISTANT,
                turnMessages = turnMessagesForMsg,
                currentMessage = msg,
                onViewSubSession = navigateToChildSessionWithSave,
                isAmoled = isAmoled,
                onCopyText = if (isTurnLast) {
                    {
                        val messages = (turnMessagesForMsg).reversed()
                        val text = messages.flatMap { m ->
                            m.parts.filterIsInstance<Part.Text>().map { it.text }
                        }.joinToString("\n\n")
                        if (text.isNotBlank()) {
                            markdownPreviewText = text
                        }
                    }
                } else null
            )
        }
        msg.isUser -> {
            if (isCompactionTrigger(index, msg)) {
                CompactionDivider()
                return@itemsIndexed
            }
            MessageCard(
                role = MessageCardRole.USER,
                currentMessage = msg,
                isQueued = msg.message.id in uiState.queuedMessageIds,
                onViewSubSession = navigateToChildSessionWithSave,
                onRevert = if (uiState.sessionParentId == null) {{
                    coroutineScope.launch {
                        performMessageRevert(msg.message.id, (...))
                    }
                }} else null,
                onCopyText = {
                    val text = msg.parts
                        .filterIsInstance<Part.Text>()
                        .joinToString("\n") { it.text }
                    if (text.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(text))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                        }
                    }
                },
                isAmoled = isAmoled
            )
        }
    }
}
```

> **⚠️ 重要**：需精确复制现有的 `isCompactionTrigger` 逻辑、`performMessageRevert` 完整回调、`navigateToChildSessionWithSave` 引用。不要简化或遗漏。

- [ ] **Step 3: 修改子会话 LazyColumn items**

子会话 itemsIndexed 约在 L2119-2206。与主会话逻辑相同，但 `onRevert = null`。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`

- [ ] **Step 5: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: switch to MessageCard with turn-group rendering in ChatScreen"
```

---

### Task 4: 删除旧组件文件并清理

**Files:**
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageBubble.kt`
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt`

- [ ] **Step 1: 删除旧文件**

```bash
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageBubble.kt
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/AssistantMessageCard.kt
```

- [ ] **Step 2: 全局搜索残留引用**

搜索所有文件中是否还有 `ChatMessageBubble` 或 `AssistantMessageCard` 引用：
```bash
rg "ChatMessageBubble|AssistantMessageCard" --type=kotlin
```

如果仅 `MessageCard.kt` 中有注释引用，可忽略或替换。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git commit -m "chore: delete old ChatMessageBubble and AssistantMessageCard"
```

---

### Task 5: 编译完整验证 + APK 构建 + 发版

**Files:** 无改动

- [ ] **Step 1: 完整编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行测试**

Run: `.\gradlew :app:testDevDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 构建 Beta APK**

Run: `.\gradlew :app:assembleBetaRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 推送 + 打 tag + 发版**

```bash
git push fork master
git tag v2.0.0-beta.74
git push fork v2.0.0-beta.74
gh release create v2.0.0-beta.74 --repo LeoNardo-LB/oc-remote-v2 \
  --title "v2.0.0-beta.74 Unified MessageCard" \
  --notes "## 修复内容

- 统一 MessageCard 组件（替代 ChatMessageBubble + AssistantMessageCard）
- Agent 回复添加灰色气泡包裹（surfaceVariant）
- 同一 turn 的多条 Agent 消息合并为一个气泡
- 修复切回会话时 thinking 动画不停止的问题
- 删除 Agent 回复头部标题栏" \
  app\build\outputs\apk\beta\release\app-beta-release.apk
```

Expected: Release URL returned
