# Phase 1: Domain 层 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Domain 层骨架——定义新增 domain model、repository 接口、UseCase（严格 TDD），以及 DI Module 绑定 UseCase。

**Architecture:** 先定义 domain model 类型 → 定义 repository 接口 → 对每个 UseCase 执行 RED→GREEN→REFACTOR（失败测试→最小实现→重构） → 创建 DI Module 提供 UseCase → 编译验证 + 全量测试通过。

**Tech Stack:** Kotlin 2.x, Hilt 2.59.2, kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0, JUnit 4, MockK 1.14.9, Turbine 1.2.1

---

## File Structure

| 操作 | 文件路径 | 职责 |
|------|----------|------|
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/model/PermissionState.kt` | 权限状态 domain model |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/model/QuestionState.kt` | 问题状态 domain model |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/model/CreateSessionOpts.kt` | 创建会话参数 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/model/AppSettings.kt` | 应用设置聚合 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ChatRepository.kt` | 聊天仓库接口 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/repository/SessionRepository.kt` | 会话仓库接口 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ServerRepository.kt` | 服务器仓库接口 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/repository/SettingsRepository.kt` | 设置仓库接口 |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCase.kt` | 发送消息 UseCase |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCase.kt` | 创建会话 UseCase |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCase.kt` | 删除会话 UseCase |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCase.kt` | 获取服务器列表 UseCase |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCase.kt` | 更新设置 UseCase |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt` | DI Module 绑定 UseCase |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCaseTest.kt` | SendMessageUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCaseTest.kt` | CreateSessionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCaseTest.kt` | DeleteSessionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCaseTest.kt` | GetServerListUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCaseTest.kt` | UpdateSettingsUseCase 测试 |

**路径约定：**
- 源码根: `app/src/main/kotlin/dev/minios/ocremote/`
- 测试根: `app/src/test/kotlin/dev/minios/ocremote/`

---

### Task 1: 新增 Domain Model — PermissionState

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/PermissionState.kt`

- [ ] **Step 1: 创建 PermissionState 数据类**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/PermissionState.kt
package dev.minios.ocremote.domain.model

/**
 * Domain-level representation of a permission request.
 * Maps from [SseEvent.PermissionAsked].
 */
data class PermissionState(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, String>? = null,
    val always: Boolean = false,
    val tool: ToolRef? = null
)
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 2: 新增 Domain Model — QuestionState

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/QuestionState.kt`

- [ ] **Step 1: 创建 QuestionState 数据类**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/QuestionState.kt
package dev.minios.ocremote.domain.model

/**
 * Domain-level representation of a question asked by the server.
 * Maps from [SseEvent.QuestionAsked].
 */
data class QuestionState(
    val id: String,
    val sessionId: String,
    val questions: List<Question>,
    val tool: ToolRef? = null
) {
    data class Question(
        val header: String,
        val question: String,
        val multiple: Boolean = false,
        val custom: Boolean = true,
        val options: List<Option>
    )

    data class Option(
        val label: String,
        val description: String
    )
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 3: 新增 Domain Model — CreateSessionOpts

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/CreateSessionOpts.kt`

- [ ] **Step 1: 创建 CreateSessionOpts 数据类**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/CreateSessionOpts.kt
package dev.minios.ocremote.domain.model

/**
 * Options for creating a new session.
 */
data class CreateSessionOpts(
    val title: String? = null,
    val parentId: String? = null,
    val directory: String? = null
)
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 4: 新增 Domain Model — AppSettings

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/AppSettings.kt`

这个文件需要聚合 `data.repository.SettingsRepository` 中的 30+ 独立 Flow 属性为一个不可变数据类。根据对现有 SettingsRepository 的分析，涵盖以下设置项：

| 属性 | 类型 | 默认值 |
|------|------|--------|
| appLanguage | String | "" |
| appTheme | String | "system" |
| dynamicColor | Boolean | true |
| chatFontSize | String | "medium" |
| notificationsEnabled | Boolean | true |
| initialMessageCount | Int | 30 |
| codeWordWrap | Boolean | false |
| confirmBeforeSend | Boolean | false |
| amoledDark | Boolean | false |
| compactMessages | Boolean | false |
| collapseTools | Boolean | false |
| expandReasoning | Boolean | false |
| hapticFeedback | Boolean | true |
| reconnectMode | String | "normal" |
| keepScreenOn | Boolean | false |
| silentNotifications | Boolean | false |
| compressImageAttachments | Boolean | true |
| imageAttachmentMaxLongSide | Int | 1440 |
| imageAttachmentWebpQuality | Int | 60 |
| showLocalRuntime | Boolean | true |
| terminalFontSize | Float | 13f |
| localSetupCompleted | Boolean | false |
| localProxyEnabled | Boolean | false |
| localProxyUrl | String | "" |
| localProxyNoProxy | String | "" |
| localServerAllowLan | Boolean | false |
| localServerUsername | String | "" |
| localServerPassword | String | "" |
| localServerRunInBackground | Boolean | true |
| localServerAutoStart | Boolean | false |
| localServerStartupTimeoutSec | Int | 30 |

- [ ] **Step 1: 创建 AppSettings 数据类**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/AppSettings.kt
package dev.minios.ocremote.domain.model

/**
 * Aggregate of all application settings.
 * Each property corresponds to a key in the DataStore preferences
 * managed by [dev.minios.ocremote.data.repository.SettingsRepository].
 */
data class AppSettings(
    // --- Appearance ---
    val appLanguage: String = "",
    val appTheme: String = "system",
    val dynamicColor: Boolean = true,
    val amoledDark: Boolean = false,

    // --- Chat ---
    val chatFontSize: String = "medium",
    val initialMessageCount: Int = 30,
    val codeWordWrap: Boolean = false,
    val confirmBeforeSend: Boolean = false,
    val compactMessages: Boolean = false,
    val collapseTools: Boolean = false,
    val expandReasoning: Boolean = false,

    // --- Notifications ---
    val notificationsEnabled: Boolean = true,
    val silentNotifications: Boolean = false,

    // --- Behavior ---
    val hapticFeedback: Boolean = true,
    val reconnectMode: String = "normal",
    val keepScreenOn: Boolean = false,

    // --- Image Attachments ---
    val compressImageAttachments: Boolean = true,
    val imageAttachmentMaxLongSide: Int = 1440,
    val imageAttachmentWebpQuality: Int = 60,

    // --- Terminal ---
    val terminalFontSize: Float = 13f,

    // --- Local Runtime: UI ---
    val showLocalRuntime: Boolean = true,
    val localSetupCompleted: Boolean = false,

    // --- Local Runtime: Proxy ---
    val localProxyEnabled: Boolean = false,
    val localProxyUrl: String = "",
    val localProxyNoProxy: String = "",

    // --- Local Runtime: Server ---
    val localServerAllowLan: Boolean = false,
    val localServerUsername: String = "",
    val localServerPassword: String = "",
    val localServerRunInBackground: Boolean = true,
    val localServerAutoStart: Boolean = false,
    val localServerStartupTimeoutSec: Int = 30
)
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 5: 定义 Repository 接口 — ChatRepository

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ChatRepository.kt`

- [ ] **Step 1: 创建 ChatRepository 接口**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/ChatRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat operations.
 * Implemented by the Data layer in Phase 3.
 */
interface ChatRepository {

    /**
     * Send a message (list of parts) to the given session.
     * Returns the resulting [Message] on success, or an exception on failure.
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * Observe the stream of messages for a session.
     * Emits each [Message] as it is created or updated.
     */
    fun getMessageStream(sessionId: String): Flow<Message>

    /**
     * Observe the raw SSE event stream for a session.
     */
    fun getSessionEvents(sessionId: String): Flow<SseEvent>

    /**
     * Abort the in-progress assistant message for a session.
     */
    suspend fun abortMessage(sessionId: String): Result<Unit>
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 6: 定义 Repository 接口 — SessionRepository

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/SessionRepository.kt`

- [ ] **Step 1: 创建 SessionRepository 接口**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/SessionRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for session management.
 * Implemented by the Data layer in Phase 3.
 */
interface SessionRepository {

    /**
     * Observe the list of all sessions.
     */
    fun getSessions(): Flow<List<Session>>

    /**
     * Create a new session with the given options.
     * Returns the created [Session] on success.
     */
    suspend fun createSession(opts: CreateSessionOpts): Result<Session>

    /**
     * Delete a session by its ID.
     */
    suspend fun deleteSession(id: String): Result<Unit>
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 7: 定义 Repository 接口 — ServerRepository

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ServerRepository.kt`

- [ ] **Step 1: 创建 ServerRepository 接口**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/ServerRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for server management.
 * Implemented by the Data layer in Phase 3.
 */
interface ServerRepository {

    /**
     * Observe the list of configured servers.
     */
    fun getServers(): Flow<List<ServerConfig>>

    /**
     * Add a new server configuration.
     */
    suspend fun addServer(config: ServerConfig): Result<Unit>

    /**
     * Remove a server configuration by its ID.
     */
    suspend fun removeServer(id: String): Result<Unit>
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 8: 定义 Repository 接口 — SettingsRepository (domain 层)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/SettingsRepository.kt`

**注意：** 现有 `data.repository.SettingsRepository` 是具体实现（Data 层）。这里定义的是 domain 层的抽象接口，Phase 3 会将 data 层实现适配到这个接口。

- [ ] **Step 1: 创建 SettingsRepository 接口**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/SettingsRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer interface for application settings.
 * Implemented by the Data layer in Phase 3.
 */
interface SettingsRepository {

    /**
     * Observe the aggregated application settings.
     */
    fun getSettings(): Flow<AppSettings>

    /**
     * Update the application settings.
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit>
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 9: TDD — SendMessageUseCase (RED → GREEN → REFACTOR)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCaseTest.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCase.kt`

#### RED — 写失败测试

- [ ] **Step 1: 编写 SendMessageUseCase 测试（期望失败）**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendMessageUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = SendMessageUseCase(chatRepository)

    @Test
    fun `invoke returns message on success`() = runTest {
        val sessionId = "session-1"
        val parts = listOf(
            Part.Text(
                id = "p1",
                sessionId = sessionId,
                messageId = "m1",
                text = "Hello"
            )
        )
        val expectedMessage = Message.User(
            id = "m1",
            sessionId = sessionId,
            time = TimeInfo(created = 1000L)
        )
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.success(expectedMessage)

        val result = useCase(sessionId, parts)

        assertTrue(result.isSuccess)
        assertEquals(expectedMessage, result.getOrNull())
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val sessionId = "session-1"
        val parts = emptyList<Part>()
        val exception = RuntimeException("Network error")
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.failure(exception)

        val result = useCase(sessionId, parts)

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke propagates repository exception`() = runTest {
        val sessionId = "session-2"
        val parts = emptyList<Part>()
        coEvery { chatRepository.sendMessage(sessionId, parts) } returns Result.failure(
            IllegalStateException("Session not found")
        )

        val result = useCase(sessionId, parts)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.SendMessageUseCaseTest"`
Expected: FAIL — `Unresolved reference: SendMessageUseCase`

#### GREEN — 实现最小代码

- [ ] **Step 3: 实现 SendMessageUseCase**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use case: send a message to a session.
 * Delegates to [ChatRepository.sendMessage].
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(sessionId: String, parts: List<Part>): Result<Message> {
        return chatRepository.sendMessage(sessionId, parts)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.SendMessageUseCaseTest"`
Expected: 3 tests PASSED

#### REFACTOR

当前实现已经是最简形式（纯委托），无需重构。

---

### Task 10: TDD — CreateSessionUseCase (RED → GREEN → REFACTOR)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCaseTest.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCase.kt`

#### RED

- [ ] **Step 1: 编写 CreateSessionUseCase 测试（期望失败）**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = CreateSessionUseCase(sessionRepository)

    @Test
    fun `invoke returns session on success`() = runTest {
        val opts = CreateSessionOpts(title = "New Chat", directory = "/home/user/project")
        val expectedSession = Session(
            id = "s1",
            title = "New Chat",
            directory = "/home/user/project",
            time = Session.Time(created = 1000L, updated = 1000L)
        )
        coEvery { sessionRepository.createSession(opts) } returns Result.success(expectedSession)

        val result = useCase(opts)

        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrNull()?.id)
        assertEquals("New Chat", result.getOrNull()?.title)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val opts = CreateSessionOpts()
        coEvery { sessionRepository.createSession(opts) } returns Result.failure(
            RuntimeException("Server unreachable")
        )

        val result = useCase(opts)

        assertTrue(result.isFailure)
        assertEquals("Server unreachable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with parentId creates child session`() = runTest {
        val opts = CreateSessionOpts(parentId = "parent-1")
        val expectedSession = Session(
            id = "child-1",
            parentId = "parent-1",
            time = Session.Time(created = 2000L, updated = 2000L)
        )
        coEvery { sessionRepository.createSession(opts) } returns Result.success(expectedSession)

        val result = useCase(opts)

        assertTrue(result.isSuccess)
        assertEquals("parent-1", result.getOrNull()?.parentId)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.CreateSessionUseCaseTest"`
Expected: FAIL — `Unresolved reference: CreateSessionUseCase`

#### GREEN

- [ ] **Step 3: 实现 CreateSessionUseCase**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: create a new session.
 * Delegates to [SessionRepository.createSession].
 */
class CreateSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(opts: CreateSessionOpts): Result<Session> {
        return sessionRepository.createSession(opts)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.CreateSessionUseCaseTest"`
Expected: 3 tests PASSED

---

### Task 11: TDD — DeleteSessionUseCase (RED → GREEN → REFACTOR)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCaseTest.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCase.kt`

#### RED

- [ ] **Step 1: 编写 DeleteSessionUseCase 测试（期望失败）**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = DeleteSessionUseCase(sessionRepository)

    @Test
    fun `invoke returns success when repository succeeds`() = runTest {
        coEvery { sessionRepository.deleteSession("s1") } returns Result.success(Unit)

        val result = useCase("s1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { sessionRepository.deleteSession("nonexistent") } returns Result.failure(
            NoSuchElementException("Session not found")
        )

        val result = useCase("nonexistent")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `invoke returns failure on network error`() = runTest {
        coEvery { sessionRepository.deleteSession("s1") } returns Result.failure(
            java.io.IOException("Connection reset")
        )

        val result = useCase("s1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.DeleteSessionUseCaseTest"`
Expected: FAIL — `Unresolved reference: DeleteSessionUseCase`

#### GREEN

- [ ] **Step 3: 实现 DeleteSessionUseCase**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use case: delete a session by ID.
 * Delegates to [SessionRepository.deleteSession].
 */
class DeleteSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(id: String): Result<Unit> {
        return sessionRepository.deleteSession(id)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.DeleteSessionUseCaseTest"`
Expected: 3 tests PASSED

---

### Task 12: TDD — GetServerListUseCase (RED → GREEN → REFACTOR)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCaseTest.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCase.kt`

注意：这个 UseCase 返回 `Flow<List<ServerConfig>>`，需要用 Turbine 测试 Flow。

#### RED

- [ ] **Step 1: 编写 GetServerListUseCase 测试（期望失败）**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetServerListUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = GetServerListUseCase(serverRepository)

    @Test
    fun `invoke emits server list from repository`() = runTest {
        val servers = listOf(
            ServerConfig(id = "srv1", url = "http://192.168.1.100:4096", name = "Home Server"),
            ServerConfig(id = "srv2", url = "http://10.0.0.1:4096", name = "Work Server")
        )
        every { serverRepository.getServers() } returns flowOf(servers)

        useCase().test {
            assertEquals(servers, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list when no servers configured`() = runTest {
        every { serverRepository.getServers() } returns flowOf(emptyList())

        useCase().test {
            assertEquals(emptyList<ServerConfig>(), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke reflects server list changes`() = runTest {
        val first = listOf(ServerConfig(id = "srv1", url = "http://host1:4096"))
        val second = listOf(
            ServerConfig(id = "srv1", url = "http://host1:4096"),
            ServerConfig(id = "srv2", url = "http://host2:4096")
        )
        every { serverRepository.getServers() } returns kotlinx.coroutines.flow.flow {
            emit(first)
            emit(second)
        }

        useCase().test {
            assertEquals(first, awaitItem())
            assertEquals(second, awaitItem())
            awaitComplete()
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.GetServerListUseCaseTest"`
Expected: FAIL — `Unresolved reference: GetServerListUseCase`

#### GREEN

- [ ] **Step 3: 实现 GetServerListUseCase**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe the list of configured servers.
 * Delegates to [ServerRepository.getServers].
 */
class GetServerListUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    operator fun invoke(): Flow<List<ServerConfig>> {
        return serverRepository.getServers()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.GetServerListUseCaseTest"`
Expected: 3 tests PASSED

---

### Task 13: TDD — UpdateSettingsUseCase (RED → GREEN → REFACTOR)

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCaseTest.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCase.kt`

#### RED

- [ ] **Step 1: 编写 UpdateSettingsUseCase 测试（期望失败）**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSettingsUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val useCase = UpdateSettingsUseCase(settingsRepository)

    @Test
    fun `invoke returns success when repository succeeds`() = runTest {
        val settings = AppSettings(appTheme = "dark", dynamicColor = false)
        coEvery { settingsRepository.updateSettings(settings) } returns Result.success(Unit)

        val result = useCase(settings)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val settings = AppSettings()
        coEvery { settingsRepository.updateSettings(settings) } returns Result.failure(
            RuntimeException("Write failed")
        )

        val result = useCase(settings)

        assertTrue(result.isFailure)
        assertEquals("Write failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with partial settings change succeeds`() = runTest {
        val settings = AppSettings(chatFontSize = "large", codeWordWrap = true)
        coEvery { settingsRepository.updateSettings(settings) } returns Result.success(Unit)

        val result = useCase(settings)

        assertTrue(result.isSuccess)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.UpdateSettingsUseCaseTest"`
Expected: FAIL — `Unresolved reference: UpdateSettingsUseCase`

#### GREEN

- [ ] **Step 3: 实现 UpdateSettingsUseCase**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Use case: update application settings.
 * Delegates to [SettingsRepository.updateSettings].
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(settings: AppSettings): Result<Unit> {
        return settingsRepository.updateSettings(settings)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.UpdateSettingsUseCaseTest"`
Expected: 3 tests PASSED

---

### Task 14: 创建 DI Module — DomainModule

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt`

Phase 1 的 DomainModule 只负责 **Provide UseCase** 实例。Repository 接口的绑定（`@Binds`）在 Phase 3 Data 层实现后才会添加。

- [ ] **Step 1: 创建 DomainModule**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt
package dev.minios.ocremote.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.domain.repository.ChatRepository
import dev.minios.ocremote.domain.repository.ServerRepository
import dev.minios.ocremote.domain.repository.SessionRepository
import dev.minios.ocremote.domain.repository.SettingsRepository
import dev.minios.ocremote.domain.usecase.CreateSessionUseCase
import dev.minios.ocremote.domain.usecase.DeleteSessionUseCase
import dev.minios.ocremote.domain.usecase.GetServerListUseCase
import dev.minios.ocremote.domain.usecase.SendMessageUseCase
import dev.minios.ocremote.domain.usecase.UpdateSettingsUseCase
import javax.inject.Singleton

/**
 * Hilt module that provides Domain-layer UseCase instances.
 *
 * Repository bindings (@Binds) are added in Phase 3 when Data-layer
 * implementations are available. Until then, any test needing a real
 * repository must use @BindValue or a test-specific module.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideSendMessageUseCase(
        chatRepository: ChatRepository
    ): SendMessageUseCase = SendMessageUseCase(chatRepository)

    @Provides
    @Singleton
    fun provideCreateSessionUseCase(
        sessionRepository: SessionRepository
    ): CreateSessionUseCase = CreateSessionUseCase(sessionRepository)

    @Provides
    @Singleton
    fun provideDeleteSessionUseCase(
        sessionRepository: SessionRepository
    ): DeleteSessionUseCase = DeleteSessionUseCase(sessionRepository)

    @Provides
    @Singleton
    fun provideGetServerListUseCase(
        serverRepository: ServerRepository
    ): GetServerListUseCase = GetServerListUseCase(serverRepository)

    @Provides
    @Singleton
    fun provideUpdateSettingsUseCase(
        settingsRepository: SettingsRepository
    ): UpdateSettingsUseCase = UpdateSettingsUseCase(settingsRepository)
}
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**注意：** 由于 domain 层的 Repository 接口没有具体实现绑定，Hilt graph 不完整（`ChatRepository` 等 interface 无法被注入）。这在 Phase 1 是预期行为——单元测试通过 MockK 直接 mock 接口，不需要 Hilt graph。Phase 3 实现 Data 层后会添加 `@Binds` 绑定来解决这个问题。如果 `compileDebugKotlin` 因此失败，暂时将 `DomainModule` 中的 `@Provides` 方法注释掉，仅保留文件结构和注释，待 Phase 3 解除注释。

---

### Task 15: 最终验证 — 全量测试 + 编译

- [ ] **Step 1: 运行 Domain 层所有测试**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.*"`
Expected: 15 tests PASSED (每个 UseCase 3 个测试 × 5 个 UseCase)

- [ ] **Step 2: 运行全量单元测试确保无回归**

Run: `./gradlew test`
Expected: ALL TESTS PASSED（包括既有测试 + 新增 15 个 UseCase 测试）

- [ ] **Step 3: 验证 Debug 编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/domain/ \
        app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/
git commit -m "feat(domain): Phase 1 — domain models, repository interfaces, use cases with tests

- Add domain models: PermissionState, QuestionState, CreateSessionOpts, AppSettings
- Define repository interfaces: ChatRepository, SessionRepository, ServerRepository, SettingsRepository
- Implement use cases with TDD: SendMessage, CreateSession, DeleteSession, GetServerList, UpdateSettings
- Add DomainModule for Hilt DI bindings
- All 15 use case tests passing"
```

---

## Self-Review Checklist

### 1. Spec Coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 定义 domain/repository/ 接口 | Task 5, 6, 7, 8 |
| 新增 Domain Model 类型 | Task 1, 2, 3, 4 |
| 编写 UseCase 接口 + 失败的单元测试 (RED) | Task 9-13 各 Step 1-2 |
| 实现 UseCase 使测试通过 (GREEN) | Task 9-13 各 Step 3-4 |
| 重构优化 (REFACTOR) | Task 9-13 (已说明无需重构) |
| 创建 DI Module 绑定 | Task 14 |
| 验证编译通过 + 测试全绿 | Task 15 |

### 2. Placeholder Scan

无 TODO/TBD/placeholder。所有代码步骤包含完整实现。

### 3. Type Consistency

- `ChatRepository.sendMessage` 返回 `Result<Message>` → `SendMessageUseCase` 返回 `Result<Message>` ✓
- `SessionRepository.createSession` 接受 `CreateSessionOpts` → `CreateSessionUseCase` 接受 `CreateSessionOpts` ✓
- `SessionRepository.deleteSession` 接受 `String` → `DeleteSessionUseCase` 接受 `String` ✓
- `ServerRepository.getServers` 返回 `Flow<List<ServerConfig>>` → `GetServerListUseCase` 返回 `Flow<List<ServerConfig>>` ✓
- `SettingsRepository.updateSettings` 接受 `AppSettings` → `UpdateSettingsUseCase` 接受 `AppSettings` ✓
- `PermissionState` 字段与 `SseEvent.PermissionAsked` 一一对应 ✓
- `QuestionState` 字段与 `SseEvent.QuestionAsked` 一一对应 ✓
- `AppSettings` 31 个字段与 `data.repository.SettingsRepository` 的 Flow 属性一一对应 ✓
- `DomainModule` 引用的所有 UseCase 和 Repository 类型与定义一致 ✓
