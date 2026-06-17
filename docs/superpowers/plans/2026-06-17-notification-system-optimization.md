# 通知系统优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化事件通知系统——前台抑制、进入取消、用户消息内容、MessagingStyle 聚合。

**Architecture:** 新增 SessionFocusHolder（Singleton）感知前台+当前会话；AppNotificationManager 的 TaskComplete 改用 MessagingStyle 显示用户消息；ChatScreen 进入/离开时控制通知生命周期；OpenCodeApp 注册 ProcessLifecycleOwner 感知前后台。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + JUnit4 + MockK + Turbine

**Spec:** `docs/superpowers/specs/2026-06-17-notification-system-optimization-design.md`

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `service/SessionFocusHolder.kt` | **新增** | 前台+当前会话感知状态 |
| `service/AppNotificationManager.kt` | **修改** | MessagingStyle + findLatestUserMessages + cancelSessionNotifications |
| `service/OpenCodeConnectionService.kt` | **修改** | 注入 SessionFocusHolder + processEvent suppress |
| `ui/screens/chat/ChatViewModel.kt` | **修改** | 暴露 serverId + onSessionFocused/onSessionUnfocused |
| `ui/screens/chat/ChatScreen.kt` | **修改** | LaunchedEffect/DisposableEffect 通知控制 |
| `OpenCodeApp.kt` | **修改** | 注册 ProcessLifecycleOwner |
| `app/build.gradle.kts` | **修改** | 新增 lifecycle-process 依赖 |
| `res/values/strings.xml` + 14 语言 | **修改** | 新增 notification_new_message |
| `test/.../SessionFocusHolderTest.kt` | **新增** | SessionFocusHolder 单元测试 |

源码根：`app/src/main/kotlin/dev/minios/ocremote/`
测试根：`app/src/test/kotlin/dev/minios/ocremote/`

---

### Task 1: 基础设施 — 依赖 + String Resource

**Files:**
- Modify: `app/build.gradle.kts` (dependencies 块，约行 112-113)
- Modify: `app/src/main/res/values/strings.xml` (约行 483 后)
- Modify: 14 个 `app/src/main/res/values-*/strings.xml`

- [ ] **Step 1: 新增 lifecycle-process 依赖**

在 `app/build.gradle.kts` 的 dependencies 块中，在现有 lifecycle 依赖（约行 112-113）下方添加：

```kotlin
implementation("androidx.lifecycle:lifecycle-process:2.10.0")
```

- [ ] **Step 2: 新增 notification_new_message string（主语言）**

在 `app/src/main/res/values/strings.xml` 的 `notification_new_session` 行（约行 483）后添加：

```xml
<string name="notification_new_message">New message</string>
```

- [ ] **Step 3: 新增 14 种语言翻译**

在每个 `values-*/strings.xml` 中添加对应翻译（在 `notification_new_session` 行后）：

| locale | 值 |
|--------|---|
| values-ar | `<string name="notification_new_message">رسالة جديدة</string>` |
| values-de | `<string name="notification_new_message">Neue Nachricht</string>` |
| values-es | `<string name="notification_new_message">Mensaje nuevo</string>` |
| values-fr | `<string name="notification_new_message">Nouveau message</string>` |
| values-id | `<string name="notification_new_message">Pesan baru</string>` |
| values-it | `<string name="notification_new_message">Nuovo messaggio</string>` |
| values-ja | `<string name="notification_new_message">新しいメッセージ</string>` |
| values-ko | `<string name="notification_new_message">새 메시지</string>` |
| values-pl | `<string name="notification_new_message">Nowa wiadomość</string>` |
| values-pt-rBR | `<string name="notification_new_message">Nova mensagem</string>` |
| values-ru | `<string name="notification_new_message">Новое сообщение</string>` |
| values-tr | `<string name="notification_new_message">Yeni mesaj</string>` |
| values-uk | `<string name="notification_new_message">Нове повідомлення</string>` |
| values-zh-rCN | `<string name="notification_new_message">新消息</string>` |

- [ ] **Step 4: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/values*/strings.xml
git commit -m "chore: add lifecycle-process dependency and notification_new_message string"
```

---

### Task 2: SessionFocusHolder（TDD）

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/service/SessionFocusHolder.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/service/SessionFocusHolderTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/kotlin/dev/minios/ocremote/service/SessionFocusHolderTest.kt`：

```kotlin
package dev.minios.ocremote.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionFocusHolderTest {

    private lateinit var holder: SessionFocusHolder

    @Before
    fun setup() {
        holder = SessionFocusHolder()
    }

    @Test
    fun `shouldSuppress returns false when app is in background`() {
        holder.setActiveFocus("server1", "session1")
        holder.setAppInForeground(false)
        assertFalse(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns false when no active focus`() {
        holder.setAppInForeground(true)
        assertFalse(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns true when foreground and same session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertTrue(holder.shouldSuppress("server1", "session1"))
    }

    @Test
    fun `shouldSuppress returns false when foreground but different session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppress("server1", "session2"))
    }

    @Test
    fun `shouldSuppress returns false when different server same session`() {
        holder.setAppInForeground(true)
        holder.setActiveFocus("server1", "session1")
        assertFalse(holder.shouldSuppress("server2", "session1"))
    }

    @Test
    fun `setActiveFocus null clears focus`() {
        holder.setActiveFocus("server1", "session1")
        holder.setActiveFocus(null, null)
        assertEquals(null, holder.activeFocus.value)
    }

    @Test
    fun `setActiveFocus with null serverId does not set focus`() {
        holder.setActiveFocus(null, "session1")
        assertEquals(null, holder.activeFocus.value)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.SessionFocusHolderTest" --rerun`
Expected: FAIL（SessionFocusHolder 类不存在）

- [ ] **Step 3: 实现 SessionFocusHolder**

创建 `app/src/main/kotlin/dev/minios/ocremote/service/SessionFocusHolder.kt`：

```kotlin
package dev.minios.ocremote.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SessionFocus(
    val serverId: String,
    val sessionId: String
)

/**
 * Tracks the app's foreground state and the currently-viewed session.
 * Used by [OpenCodeConnectionService] to suppress TaskComplete notifications
 * when the user is actively viewing that session.
 */
@Singleton
class SessionFocusHolder @Inject constructor() {

    val activeFocus: StateFlow<SessionFocus?> get() = _activeFocus
    private val _activeFocus = MutableStateFlow<SessionFocus?>(null)

    val isAppInForeground: StateFlow<Boolean> get() = _isAppInForeground
    private val _isAppInForeground = MutableStateFlow(false)

    fun setActiveFocus(serverId: String?, sessionId: String?) {
        _activeFocus.value = if (serverId != null && sessionId != null) {
            SessionFocus(serverId, sessionId)
        } else {
            null
        }
    }

    fun setAppInForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    /**
     * Returns true if TaskComplete notifications for this session should be suppressed
     * (app is in foreground AND user is viewing this exact session).
     */
    fun shouldSuppress(serverId: String, sessionId: String): Boolean {
        val focus = _activeFocus.value ?: return false
        return _isAppInForeground.value &&
                focus.serverId == serverId &&
                focus.sessionId == sessionId
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.SessionFocusHolderTest" --rerun`
Expected: PASS（7 个测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/SessionFocusHolder.kt app/src/test/kotlin/dev/minios/ocremote/service/SessionFocusHolderTest.kt
git commit -m "feat: add SessionFocusHolder for foreground/session tracking"
```

---

### Task 3: 用户消息提取 — findLatestUserMessages（TDD）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/service/FindUserMessagesTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/kotlin/dev/minios/ocremote/service/FindUserMessagesTest.kt`：

```kotlin
package dev.minios.ocremote.service

import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsDataStore
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FindUserMessagesTest {

    private lateinit var manager: AppNotificationManager
    private val eventDispatcher: EventDispatcher = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()

    @Before
    fun setup() {
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        manager = AppNotificationManager(eventDispatcher, settingsDataStore)
    }

    private fun userMessage(id: String, created: Long): Message.User {
        return Message.User(
            id = id,
            sessionId = "session1",
            role = "user",
            time = TimeInfo(created = created)
        )
    }

    private fun textPart(msgId: String, text: String, synthetic: Boolean? = null): Part.Text {
        return Part.Text(
            id = "part_$msgId",
            sessionId = "session1",
            messageId = msgId,
            text = text,
            synthetic = synthetic
        )
    }

    @Test
    fun `returns empty list when no messages`() {
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list when no user messages`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(
                Message.Assistant(
                    id = "a1", sessionId = "session1", role = "assistant",
                    time = TimeInfo(created = 100), parentId = "u1"
                )
            )
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extracts user message text`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", "Hello world"))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(1, result.size)
        assertEquals("Hello world", result[0].text)
        assertEquals(100L, result[0].timestamp)
    }

    @Test
    fun `filters synthetic messages`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100), userMessage("u2", 200))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", "Real message", synthetic = false)),
            "u2" to listOf(textPart("u2", "System injected", synthetic = true))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(1, result.size)
        assertEquals("Real message", result[0].text)
    }

    @Test
    fun `skips user messages with no text parts`() {
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to emptyList()
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `truncates long text to 100 chars`() {
        val longText = "x".repeat(200)
        every { eventDispatcher.messages.value } returns mapOf(
            "session1" to listOf(userMessage("u1", 100))
        )
        every { eventDispatcher.parts.value } returns mapOf(
            "u1" to listOf(textPart("u1", longText))
        )
        val result = manager.findLatestUserMessages("session1", 5)
        assertEquals(101, result[0].text.length) // 100 chars + "…"
        assertTrue(result[0].text.endsWith("…"))
    }

    @Test
    fun `returns at most limit messages, most recent last`() {
        val msgs = (1..10).map { userMessage("u$it", it.toLong()) }
        every { eventDispatcher.messages.value } returns mapOf("session1" to msgs)
        every { eventDispatcher.parts.value } returns (1..10).associate {
            "u$it" to listOf(textPart("u$it", "Message $it"))
        }
        val result = manager.findLatestUserMessages("session1", 3)
        assertEquals(3, result.size)
        assertEquals("Message 8", result[0].text)
        assertEquals("Message 10", result[2].text)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.FindUserMessagesTest" --rerun`
Expected: FAIL（findLatestUserMessages 方法不存在）

- [ ] **Step 3: 实现 findLatestUserMessages + UserMessagePreview**

在 `AppNotificationManager.kt` 中，在 `checkNewAssistantMessage` 方法之后（约行 357 后）添加以下内容。

首先在文件顶部（import 之后，class 之前）添加 data class：

```kotlin
/** A user message preview for notification display. */
data class UserMessagePreview(
    val text: String,
    val timestamp: Long
)
```

然后在 `AppNotificationManager` class 内部（`checkNewAssistantMessage` 之后）添加方法：

```kotlin
    /**
     * Extract the latest N user messages (non-synthetic) for MessagingStyle display.
     * Messages are ordered oldest-to-newest.
     */
    fun findLatestUserMessages(sessionId: String, limit: Int): List<UserMessagePreview> {
        val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return emptyList()
        val partsMap = eventDispatcher.parts.value

        val previews = sessionMessages
            .filterIsInstance<Message.User>()
            .mapNotNull { userMsg ->
                val parts = partsMap[userMsg.id] ?: return@mapNotNull null
                val text = parts
                    .filterIsInstance<Part.Text>()
                    .firstOrNull { it.synthetic != true && it.ignored != true && it.text.isNotBlank() }
                    ?.text
                    ?: return@mapNotNull null
                UserMessagePreview(
                    text = if (text.length > 100) text.take(100) + "…" else text,
                    timestamp = userMsg.time.created
                )
            }

        return previews.takeLast(limit)
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.FindUserMessagesTest" --rerun`
Expected: PASS（7 个测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt app/src/test/kotlin/dev/minios/ocremote/service/FindUserMessagesTest.kt
git commit -m "feat: add findLatestUserMessages for notification content extraction"
```

---

### Task 4: cancelSessionNotifications（TDD）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/service/CancelSessionNotificationsTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/kotlin/dev/minios/ocremote/service/CancelSessionNotificationsTest.kt`：

```kotlin
package dev.minios.ocremote.service

import android.app.NotificationManager
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test

class CancelSessionNotificationsTest {

    private lateinit var manager: AppNotificationManager
    private val eventDispatcher: EventDispatcher = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()
    private val notificationManager: NotificationManager = mockk(relaxed = true)

    @Before
    fun setup() {
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        manager = AppNotificationManager(eventDispatcher, settingsDataStore)
    }

    @Test
    fun `cancels all 4 type offsets for the session`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val baseId = ("server1" + "session1").hashCode()
        verify(exactly = 1) { notificationManager.cancel(baseId + 0) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 1000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 2000) }
        verify(exactly = 1) { notificationManager.cancel(baseId + 3000) }
    }

    @Test
    fun `does not cancel group summary`() {
        manager.cancelSessionNotifications(notificationManager, "server1", "session1")

        val summaryId = "server_summary_server1".hashCode()
        verify(exactly = 0) { notificationManager.cancel(summaryId) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.CancelSessionNotificationsTest" --rerun`
Expected: FAIL（cancelSessionNotifications 方法不存在）

- [ ] **Step 3: 实现 cancelSessionNotifications**

在 `AppNotificationManager.kt` 的 `findLatestUserMessages` 方法之后添加：

```kotlin
    /**
     * Cancel all event notifications for a specific session (TaskComplete/Permission/Question/Error).
     * Called when the user enters the session's ChatScreen.
     * Does NOT cancel the server group summary (other sessions may still have notifications).
     */
    fun cancelSessionNotifications(
        notificationManager: NotificationManager,
        serverId: String,
        sessionId: String
    ) {
        for (offset in intArrayOf(0, 1000, 2000, 3000)) {
            notificationManager.cancel(eventNotificationId(serverId, sessionId, offset))
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --tests "dev.minios.ocremote.service.CancelSessionNotificationsTest" --rerun`
Expected: PASS（2 个测试）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt app/src/test/kotlin/dev/minios/ocremote/service/CancelSessionNotificationsTest.kt
git commit -m "feat: add cancelSessionNotifications to clear session notifications on entry"
```

---

### Task 5: showTaskCompleteNotification MessagingStyle 重构

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt` (行 176-208)

**⚠️ 注意**：此 Task 修改通知构建逻辑，测试较复杂（需 mock NotificationCompat.Builder）。采用手动验证为主——编译通过 + 运行时通知行为验证。

- [ ] **Step 1: 重构 showTaskCompleteNotification**

在 `AppNotificationManager.kt` 中，替换 `showTaskCompleteNotification` 方法（行 176-208）的完整内容为：

```kotlin
    suspend fun showTaskCompleteNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String
    ) {
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val displayName = sessionTitle?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_new_session)

        // Type label prefix preserves "Response ready" semantic in MessagingStyle title
        val typeLabel = context.getString(R.string.notification_response_ready)
        val conversationTitle = "$typeLabel · $displayName"

        val userMessages = findLatestUserMessages(sessionId, 5)

        val style: NotificationCompat.Style = if (userMessages.isNotEmpty()) {
            NotificationCompat.MessagingStyle("你" as CharSequence).apply {
                this.conversationTitle = conversationTitle
                for (msg in userMessages) {
                    addMessage(msg.text, msg.timestamp, "你" as CharSequence)
                }
            }
        } else {
            // Fallback when no extractable user message (image-only, etc.)
            NotificationCompat.BigTextStyle()
                .setBigContentTitle(conversationTitle)
                .bigText(context.getString(R.string.notification_new_message))
        }

        val pendingIntent = createSessionPendingIntent(context, server, sessionId, sessionId.hashCode())
        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID
        val notifId = eventNotificationId(server.id, sessionId, 0)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(style)
            .setSubText(server.displayName)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup("server_${server.id}")

        if (!silent) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500))
        }

        notificationManager.notify(notifId, builder.build())
        showServerGroupSummary(context, notificationManager, server)
    }
```

关键变更：
- 移除 `setContentTitle` / `setContentText`（由 MessagingStyle/BigTextStyle 接管）
- 新增 `conversationTitle = "响应就绪 · 会话名"`
- 新增 `findLatestUserMessages` 构建 MessagingStyle
- 无用户消息时 fallback 到 BigTextStyle

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/AppNotificationManager.kt
git commit -m "feat: TaskComplete notification uses MessagingStyle with user messages"
```

---

### Task 6: OpenCodeConnectionService — 注入 + suppress

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt`

- [ ] **Step 1: 注入 SessionFocusHolder**

在 `OpenCodeConnectionService.kt` 的 field injection 区域（约行 64-80），在 `networkMonitor` 之后添加：

```kotlin
    @Inject lateinit var sessionFocusHolder: SessionFocusHolder
```

- [ ] **Step 2: processEvent SessionIdle 分支加 suppress 检查**

在 `processEvent` 方法（约行 306-377）中，找到 `SseEvent.SessionIdle` 分支（约行 329-331）。

当前代码结构（约行 312-332）：
```kotlin
is SseEvent.SessionIdle -> {
    if (appNotificationManager.isChildSession(event.sessionId)) return
    if (!notificationsEnabled) return
    delay(250)
    ...
}
```

在 `isChildSession` 检查**之前**（第一行）添加 suppress 检查：

```kotlin
            is SseEvent.SessionIdle -> {
                // Suppress if user is actively viewing this session
                if (sessionFocusHolder.shouldSuppress(server.id, event.sessionId)) return

                if (appNotificationManager.isChildSession(event.sessionId)) return
                if (!notificationsEnabled) return
                delay(250)
                // ... 原有 checkNewAssistantMessage + showTaskCompleteNotification 逻辑不变
```

**注意**：只在 SessionIdle 分支添加。PermissionAsked/QuestionAsked/SessionError 分支不变。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/service/OpenCodeConnectionService.kt
git commit -m "feat: suppress TaskComplete notification when viewing the session"
```

---

### Task 7: ChatViewModel — 暴露 serverId + 通知控制方法

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: 暴露 serverId**

在 `ChatViewModel.kt` 约 行 269-271，将 `serverId` 从 private 改为 public：

当前：
```kotlin
private val serverId: String = URLDecoder.decode(
    savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
)
```

改为：
```kotlin
val serverId: String = URLDecoder.decode(
    savedStateHandle.get<String>("serverId") ?: "", "UTF-8"
)
```

- [ ] **Step 2: 注入 SessionFocusHolder + AppNotificationManager**

在 `ChatViewModel` 构造函数（约行 210-232），在最后一个参数 `sessionStatusManager` 之后添加：

```kotlin
    private val sessionFocusHolder: SessionFocusHolder,
    private val appNotificationManager: AppNotificationManager,
```

**注意**：需要添加 import：
```kotlin
import android.app.NotificationManager
import android.content.Context
import dev.minios.ocremote.service.SessionFocusHolder
import dev.minios.ocremote.service.AppNotificationManager
```

- [ ] **Step 3: 添加 onSessionFocused / onSessionUnfocused 方法**

在 `ChatViewModel` class 内部（在 `serverId` 定义之后，约行 278 后）添加：

```kotlin
    /**
     * Called when ChatScreen enters composition.
     * Cancels existing notifications for this session and registers active focus
     * so future TaskComplete notifications are suppressed.
     */
    fun onSessionFocused(notificationManager: NotificationManager) {
        appNotificationManager.cancelSessionNotifications(notificationManager, serverId, sessionId)
        sessionFocusHolder.setActiveFocus(serverId, sessionId)
    }

    /**
     * Called when ChatScreen leaves composition.
     * Clears active focus so notifications resume.
     */
    fun onSessionUnfocused() {
        sessionFocusHolder.setActiveFocus(null, null)
    }
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "feat: ChatViewModel exposes serverId and notification control methods"
```

---

### Task 8: ChatScreen — LaunchedEffect/DisposableEffect 通知控制

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

**⚠️ ChatScreen Editing Protocol**：每次编辑前 Read，编辑后 compileDevDebugKotlin，失败则 git checkout 重来。参见 `docs/chatscreen-editing-protocol.md`。

- [ ] **Step 1: Read ChatScreen.kt 确认当前内容**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` 行 250-460，确认 `LaunchedEffect(viewModel.sessionId)`（约行 443）和 `DisposableEffect(lifecycleOwner)`（约行 450）的位置。

- [ ] **Step 2: 添加通知控制 effect**

在 `ChatScreen` composable 函数体内，在现有 `LaunchedEffect(viewModel.sessionId)`（约行 443）**之前**添加：

```kotlin
    // Notification lifecycle: cancel existing + set active focus on enter, clear on leave
    val context = LocalContext.current
    LaunchedEffect(viewModel.sessionId) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        viewModel.onSessionFocused(notificationManager)
    }
    DisposableEffect(viewModel.sessionId) {
        onDispose {
            viewModel.onSessionUnfocused()
        }
    }
```

**需要的 import**（在文件顶部添加）：
```kotlin
import android.app.NotificationManager
import android.content.Context
```

**⚠️ 注意**：如果 `LocalContext` 已被导入则不需要重复。检查现有 import。

**⚠️ 如果已有 `DisposableEffect(lifecycleOwner)`**（约行 450）：不要修改它——它是屏幕常亮逻辑，与新 effect 独立。新 DisposableEffect 用 `viewModel.sessionId` 作为 key。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

如果失败：`git checkout -- app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`，重新 Read，重试。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat: ChatScreen controls notification lifecycle on enter/leave"
```

---

### Task 9: OpenCodeApp — ProcessLifecycleOwner

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/OpenCodeApp.kt`

- [ ] **Step 1: 注册 ProcessLifecycleOwner 监听**

在 `OpenCodeApp.kt` 中：

1. 添加 import：
```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.minios.ocremote.service.SessionFocusHolder
```

2. 在 `onCreate()` 方法末尾（return 之前），添加：

```kotlin
        // Track app foreground/background for notification suppression
        val focusHolder = SessionFocusHolder::class.java.let {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                this,
                SessionFocusEntryPoint::class.java
            ).sessionFocusHolder()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                focusHolder.setAppInForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                focusHolder.setAppInForeground(false)
            }
        })
```

3. 在文件末尾（class 外部）添加 EntryPoint 接口：

```kotlin
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SessionFocusEntryPoint {
    fun sessionFocusHolder(): SessionFocusHolder
}
```

**说明**：App 类不是通过 Hilt 构造函数注入的（它是 `@HiltAndroidApp`，但 Application 对象由系统创建），所以使用 EntryPointAccessors 获取 Singleton。

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/OpenCodeApp.kt
git commit -m "feat: register ProcessLifecycleOwner to track app foreground state"
```

---

### Task 10: 全量编译 + 单元测试验证

**Files:** 无修改（验证 Task）

- [ ] **Step 1: Kotlin 编译验证**

Run: `.\gradlew.bat :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 全量单元测试**

Run: `.\gradlew.bat :app:testDevDebugUnitTest --rerun`
Expected: ALL PASS（包括新增的 SessionFocusHolderTest + FindUserMessagesTest + CancelSessionNotificationsTest + 现有测试）

- [ ] **Step 3: 如果有测试失败，修复后重新运行**

- [ ] **Step 4: 最终 commit（如有修复）**

```bash
git add -A
git commit -m "fix: resolve test failures from notification optimization"
```

---

## 自审检查表

### Spec 覆盖

| Spec 需求 | 实现任务 | 状态 |
|-----------|---------|------|
| ① 前台抑制 TaskComplete | Task 2 (SessionFocusHolder) + Task 6 (Service suppress) + Task 9 (ProcessLifecycle) | ✅ |
| ② 进入会话取消所有通知 | Task 4 (cancelSessionNotifications) + Task 7+8 (ChatScreen 调用) | ✅ |
| ③ 标题=会话名+类型前缀，内容=用户消息 | Task 3 (findLatestUserMessages) + Task 5 (MessagingStyle) | ✅ |
| ④ 同会话消息聚合 | Task 5 (MessagingStyle 最近5条) | ✅ |
| serverId 维度 | Task 2 (SessionFocus pair) + Task 7 (暴露 serverId) | ✅ |
| Permission/Question/Error 不变 | Task 6 (仅 SessionIdle suppress) | ✅ |

### 类型一致性

- `findLatestUserMessages(sessionId: String, limit: Int): List<UserMessagePreview>` — Task 3 定义，Task 5 调用 ✅
- `cancelSessionNotifications(notificationManager, serverId, sessionId)` — Task 4 定义，Task 7 调用 ✅
- `shouldSuppress(serverId, sessionId): Boolean` — Task 2 定义，Task 6 调用 ✅
- `setActiveFocus(serverId, sessionId)` / `setAppInForeground(Boolean)` — Task 2 定义，Task 7+9 调用 ✅
- `onSessionFocused(NotificationManager)` / `onSessionUnfocused()` — Task 7 定义，Task 8 调用 ✅
