# Phase 1: Domain 层 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Domain 层骨架——定义新增 domain model、repository 接口、UseCase（严格 TDD），以及 DI Module 绑定 UseCase。

**Architecture:** 先定义 domain model 类型 → 定义 repository 接口 → 对每个 UseCase 执行 RED→GREEN→REFACTOR（失败测试→最小实现→重构） → 创建 DI Module 提供 UseCase → 编译验证 + 全量测试通过。

**Tech Stack:** Kotlin 2.x, Hilt 2.59.2, kotlinx-serialization 1.11.0, kotlinx-coroutines 1.11.0, JUnit 4, MockK 1.14.9, Turbine 1.2.1

**Prerequisites:** Phase 0（测试基础设施修复 + Characterization Tests 全绿）已完成

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
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCase.kt` | 消息流观察 UseCase (Phase 2) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCase.kt` | 会话管理 UseCase (Phase 2) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCase.kt` | 权限回复 UseCase (Phase 2) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCase.kt` | 问题回复 UseCase (Phase 2) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCase.kt` | 连接服务器 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCase.kt` | 断开服务器 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCase.kt` | 本地服务器管理 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCase.kt` | Provider 管理 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCase.kt` | 会话列表 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCase.kt` | 设置流 UseCase (Phase 4) |
| 新建 | `app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt` | DI Module 占位（Phase 3 添加 @Binds） |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/SendMessageUseCaseTest.kt` | SendMessageUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/CreateSessionUseCaseTest.kt` | CreateSessionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DeleteSessionUseCaseTest.kt` | DeleteSessionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetServerListUseCaseTest.kt` | GetServerListUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/UpdateSettingsUseCaseTest.kt` | UpdateSettingsUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCaseTest.kt` | GetMessagesUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCaseTest.kt` | ManageSessionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCaseTest.kt` | ManagePermissionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCaseTest.kt` | ManageQuestionUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCaseTest.kt` | ConnectServerUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCaseTest.kt` | DisconnectServerUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCaseTest.kt` | ManageLocalServerUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCaseTest.kt` | ManageServerProvidersUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCaseTest.kt` | GetSessionListUseCase 测试 |
| 新建 | `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCaseTest.kt` | GetSettingsFlowUseCase 测试 |

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

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 4.5: 新增 Domain Model — ServerHealth, LocalServerState, ProviderInfo

> **D5 FIX:** 这些 model 是扩展后的 ServerRepository 接口（Task 7）和 Phase 4 UseCase 所必需的。

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/ServerHealth.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/LocalServerState.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/model/ProviderInfo.kt`

- [ ] **Step 1: 创建 ServerHealth model**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/ServerHealth.kt
package dev.minios.ocremote.domain.model

/**
 * Server health check result.
 * Mirrors the API response from GET /health.
 */
data class ServerHealth(
    val healthy: Boolean = false,
    val version: String? = null,
    val uptime: Long? = null
)
```

- [ ] **Step 2: 创建 LocalServerState model**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/LocalServerState.kt
package dev.minios.ocremote.domain.model

/**
 * Domain model for local (Termux-based) server status.
 * Used by ManageLocalServerUseCase (Phase 4).
 */
data class LocalServerState(
    val status: String = "unavailable",
    val message: String? = null,
    val fixCommand: String? = null,
    val requiresOverlaySettings: Boolean = false
)
```

- [ ] **Step 3: 创建 ProviderInfo model**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/model/ProviderInfo.kt
package dev.minios.ocremote.domain.model

/**
 * Domain model for a server provider (e.g., OpenRouter, Anthropic).
 * Used by ManageServerProvidersUseCase (Phase 4).
 */
data class ProviderInfo(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val models: List<ModelInfo> = emptyList()
)

data class ModelInfo(
    val id: String,
    val name: String,
    val visible: Boolean = true
)
```

- [ ] **Step 4: 验证编译通过**

Run: `.\gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 5: 定义 Repository 接口 — ChatRepository

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/ChatRepository.kt`

- [ ] **Step 1: 创建 ChatRepository 接口**

> **P0-1 FIX:** 接口签名完全对齐 spec §4.1.1。移除 `getMessageStream`（改为 `getMessagesFlow`）、`getSessionEvents`（暴露 `SseEvent` 违反 DIP，domain 层不应依赖 data 层 DTO）、`abortMessage`（spec 无此方法）。新增 `getPermissionsFlow`、`getQuestionsFlow`、`replyPermission`、`replyQuestion`、`getToolExpandedStates`。

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/ChatRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.model.QuestionState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for chat operations.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface ChatRepository {

    /**
     * Observe the list of messages (with parts) for a session.
     * Phase 3 impl: delegates to EventReducer.messagesFlow, maps to domain Message.
     */
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    /**
     * Observe the list of pending permission requests for a session.
     */
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>>

    /**
     * Observe the list of pending questions for a session.
     */
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>>

    /**
     * Send a message (list of parts) to the given session.
     * Returns the resulting [Message] on success, or an exception on failure.
     */
    suspend fun sendMessage(sessionId: String, parts: List<Part>): Result<Message>

    /**
     * Reply to a permission request.
     */
    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean>

    /**
     * Reply to a question.
     */
    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean>

    /**
     * Get the mutable map of tool expanded states for the current session.
     * Used by UI to track which tool cards are expanded.
     */
    fun getToolExpandedStates(): MutableMap<String, Boolean>
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

> **P0-1 FIX:** 对齐 spec §4.1.1 — `createSession` 增加 `serverId` 参数；新增 `switchSession`；移除无 `serverId` 的 `getSessions()`（统一使用 `getSessionsFlow(serverId)`）。

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/SessionRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.CreateSessionOpts
import dev.minios.ocremote.domain.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for session management.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SessionRepository {

    /**
     * Observe sessions for a specific server connection.
     * Phase 3 impl: delegates to EventReducer.sessionsFlow filtered by serverId.
     */
    fun getSessionsFlow(serverId: String): Flow<List<Session>>

    /**
     * Create a new session on the specified server with the given options.
     * Returns the created [Session] on success.
     */
    suspend fun createSession(serverId: String, opts: CreateSessionOpts): Result<Session>

    /**
     * Delete a session by its ID.
     */
    suspend fun deleteSession(sessionId: String): Result<Unit>

    /**
     * Switch the active session.
     * Phase 3 impl: delegates to OpenCodeApi or connection service.
     */
    suspend fun switchSession(sessionId: String): Result<Unit>
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

> **P0-1 FIX:** 方法名对齐 spec §4.1.1 — `getServers()` → `getServersFlow()`；`checkHealth` → `testConnection`（spec 使用 `Result<Boolean>`）；Provider 管理方法签名统一使用 `serverId` 而非裸 URL/凭据。保留 Phase 4 所需的 14+ 方法。

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/ServerRepository.kt
package dev.minios.ocremote.domain.repository

import android.content.Context
import dev.minios.ocremote.domain.model.LocalServerState
import dev.minios.ocremote.domain.model.ProviderInfo
import dev.minios.ocremote.domain.model.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for server management.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 *
 * Phase 4 UseCase 需要 connect/disconnect/testConnection/local-server/providers 等方法。
 * Phase 3 impl: connect/disconnect → OpenCodeConnectionService,
 *               local server → LocalServerManager,
 *               providers → OpenCodeApi.
 */
interface ServerRepository {

    // ── Server CRUD ──

    /**
     * Observe the list of configured servers.
     */
    fun getServersFlow(): Flow<List<ServerConfig>>

    /**
     * Add a new server configuration.
     */
    suspend fun addServer(config: ServerConfig): Result<Unit>

    /**
     * Remove a server configuration by its ID.
     */
    suspend fun removeServer(id: String): Result<Unit>

    /**
     * Update an existing server configuration.
     */
    suspend fun updateServer(server: ServerConfig): Result<Unit>

    /**
     * Get a server by its ID. Returns null if not found.
     */
    suspend fun getServer(id: String): ServerConfig?

    // ── Connection lifecycle ──
    // Phase 3 impl delegates to OpenCodeConnectionService

    /**
     * Establish a connection to the given server.
     * Phase 3 impl: delegates to OpenCodeConnectionService.connect(server).
     */
    suspend fun connect(server: ServerConfig): Result<Unit>

    /**
     * Disconnect from the server identified by serverId.
     * Phase 3 impl: delegates to OpenCodeConnectionService.disconnect(serverId).
     */
    suspend fun disconnect(serverId: String): Result<Unit>

    /**
     * Test connectivity to a server.
     * Returns true if the server is reachable and healthy.
     */
    suspend fun testConnection(server: ServerConfig): Result<Boolean>

    // ── Local server management ──
    // Phase 3 impl delegates to LocalServerManager

    /**
     * Get the shell command used to set up the local server (Termux).
     */
    fun getLocalSetupCommand(): String

    /**
     * Run the local server setup process.
     */
    suspend fun setupLocalServer(context: Context): Result<Unit>

    /**
     * Start the local server process.
     */
    suspend fun startLocalServer(context: Context): Result<Unit>

    /**
     * Stop the local server process.
     */
    suspend fun stopLocalServer(context: Context): Result<Unit>

    /**
     * Query the current local server status.
     */
    suspend fun getLocalServerState(): Result<LocalServerState>

    // ── Provider management ──
    // Phase 3 impl delegates to OpenCodeApi

    /**
     * Load available providers for a connected server.
     */
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>>

    /**
     * Enable or disable a specific provider.
     */
    suspend fun setProviderEnabled(serverId: String, providerId: String, enabled: Boolean): Result<Unit>

    /**
     * Connect a provider API key.
     */
    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit>

    /**
     * Disconnect a provider.
     */
    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit>

    /**
     * Set model visibility within a provider.
     */
    suspend fun setModelVisible(serverId: String, providerId: String, modelId: String, visible: Boolean): Result<Unit>

    /**
     * Persist the server's current configuration.
     */
    suspend fun saveServerConfig(serverId: String): Result<Unit>
}
```

> **P0-1 FIX 变更摘要：**
> - `getServers()` → `getServersFlow()` 对齐 spec 命名约定
> - `checkHealth(ServerConfig): Result<ServerHealth>` → `testConnection(ServerConfig): Result<Boolean>` 对齐 spec §4.1.1
> - Provider 方法签名统一使用 `serverId: String`（Phase 3 impl 内部解析为 URL/凭据），移除裸 `serverUrl/username/password` 参数
> - 移除 `ServerHealth` import（不再需要），改为 `ProviderInfo` import

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 8: 定义 Repository 接口 — SettingsRepository (domain 层)

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/repository/SettingsRepository.kt`

**注意：** 现有 `data.repository.SettingsRepository` 是具体实现（Data 层）。这里定义的是 domain 层的抽象接口，Phase 3 会将 data 层实现适配到这个接口。

- [ ] **Step 1: 创建 SettingsRepository 接口**

> **P0-1 FIX:** `getSettings()` → `getSettingsFlow()` 对齐 spec §4.1.1 命名约定（所有返回 Flow 的方法统一 `xxxFlow` 后缀）。

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/domain/repository/SettingsRepository.kt
package dev.minios.ocremote.domain.repository

import dev.minios.ocremote.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer interface for application settings.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SettingsRepository {

    /**
     * Observe the aggregated application settings.
     */
    fun getSettingsFlow(): Flow<AppSettings>

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
        val serverId = "server-1"
        val opts = CreateSessionOpts(title = "New Chat", directory = "/home/user/project")
        val expectedSession = Session(
            id = "s1",
            title = "New Chat",
            directory = "/home/user/project",
            time = Session.Time(created = 1000L, updated = 1000L)
        )
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.success(expectedSession)

        val result = useCase(serverId, opts)

        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrNull()?.id)
        assertEquals("New Chat", result.getOrNull()?.title)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        val serverId = "server-1"
        val opts = CreateSessionOpts()
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.failure(
            RuntimeException("Server unreachable")
        )

        val result = useCase(serverId, opts)

        assertTrue(result.isFailure)
        assertEquals("Server unreachable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with parentId creates child session`() = runTest {
        val serverId = "server-1"
        val opts = CreateSessionOpts(parentId = "parent-1")
        val expectedSession = Session(
            id = "child-1",
            parentId = "parent-1",
            time = Session.Time(created = 2000L, updated = 2000L)
        )
        coEvery { sessionRepository.createSession(serverId, opts) } returns Result.success(expectedSession)

        val result = useCase(serverId, opts)

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
 * Use case: create a new session on a server.
 * Delegates to [SessionRepository.createSession].
 */
class CreateSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(serverId: String, opts: CreateSessionOpts): Result<Session> {
        return sessionRepository.createSession(serverId, opts)
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
        every { serverRepository.getServersFlow() } returns flowOf(servers)

        useCase().test {
            assertEquals(servers, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list when no servers configured`() = runTest {
        every { serverRepository.getServersFlow() } returns flowOf(emptyList())

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
        every { serverRepository.getServersFlow() } returns kotlinx.coroutines.flow.flow {
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
        return serverRepository.getServersFlow()
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
import org.junit.Assert.assertEquals
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

Phase 1 的 DomainModule 仅作为占位模块。由于所有 UseCase 都使用 `@Inject constructor` 构造函数注入，Hilt 可以自动推导依赖图，**不需要显式 `@Provides` 方法**。

> **P1 FIX (DomainModule 死代码):** 原 DomainModule 为每个 UseCase 写了 `@Provides @Singleton` 方法，但这些方法只是调用构造函数（等价于 Hilt 自动注入），属于冗余代码。改为更简洁的空 Module + 注释说明。仅当 UseCase 需要额外配置（非构造函数参数）时才需要 `@Provides`。Repository 的 `@Binds` 绑定在 Phase 3 Data 层实现后添加。

- [ ] **Step 1: 创建 DomainModule**

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt
package dev.minios.ocremote.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the Domain layer.
 *
 * All UseCases use `@Inject constructor` — Hilt resolves them automatically
 * once their Repository dependencies are bound. No explicit @Provides needed
 * for simple delegation UseCases.
 *
 * Phase 3 will add a RepositoryModule with @Binds to map:
 *   domain.repository.ChatRepository → data.repository.impl.ChatRepositoryImpl
 *   domain.repository.SessionRepository → data.repository.impl.SessionRepositoryImpl
 *   domain.repository.ServerRepository → data.repository.impl.ServerRepositoryImpl
 *   domain.repository.SettingsRepository → data.repository.impl.SettingsRepositoryImpl
 *
 * Only add @Provides here if a UseCase requires non-trivial construction
 * (e.g., factory pattern, runtime configuration, multiple repository orchestration).
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule
```

- [ ] **Step 2: 验证编译通过**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**注意：** 由于 domain 层的 Repository 接口没有具体实现绑定，Hilt graph 不完整。这在 Phase 1 是预期行为——单元测试通过 MockK 直接 mock 接口，不需要 Hilt graph。Phase 3 实现 Data 层后会添加 `RepositoryModule`（含 `@Binds`）来解决这个问题。

---

### Task 15-22: Phase 2/4 预留 UseCase 定义（TDD 模板）

> **P0-2 FIX:** Phase 2 需要 9 个 UseCase（当前仅 SendMessageUseCase 匹配），Phase 4 需要 6 个额外 UseCase。以下在 Phase 1 中补充定义所有后续 Phase 需要的 UseCase 接口和测试骨架，确保 Phase 2/4 可以直接实现而无需回溯修改 domain 层。
>
> 每个 UseCase 按简化 TDD 模式：定义接口 + 测试（成功/失败 2 个测试）+ 最小实现。

#### Task 15: ManageSessionUseCase — 会话管理

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageSessionUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = ManageSessionUseCase(sessionRepository)

    @Test
    fun `getSessionsFlow emits session list`() = runTest {
        val sessions = listOf(Session(id = "s1", title = "Chat", time = Session.Time(created = 1000L)))
        every { sessionRepository.getSessionsFlow("server-1") } returns flowOf(sessions)

        useCase.getSessionsFlow("server-1").test {
            assertEquals(sessions, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `switchSession returns success`() = runTest {
        coEvery { sessionRepository.switchSession("s1") } returns Result.success(Unit)

        val result = useCase.switchSession("s1")

        assertTrue(result.isSuccess)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageSessionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage sessions (switch, observe).
 * Used by Phase 2 ChatViewModel.
 */
class ManageSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    fun getSessionsFlow(serverId: String): Flow<List<Session>> =
        sessionRepository.getSessionsFlow(serverId)

    suspend fun switchSession(sessionId: String): Result<Unit> =
        sessionRepository.switchSession(sessionId)
}
```

---

#### Task 16: ManagePermissionUseCase — 权限回复

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagePermissionUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = ManagePermissionUseCase(chatRepository)

    @Test
    fun `replyPermission returns true on success`() = runTest {
        coEvery { chatRepository.replyPermission("p1", "allow") } returns Result.success(true)

        val result = useCase.replyPermission("p1", "allow")

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
    }

    @Test
    fun `replyPermission returns failure on error`() = runTest {
        coEvery { chatRepository.replyPermission("p1", "deny") } returns Result.failure(
            RuntimeException("Connection lost")
        )

        val result = useCase.replyPermission("p1", "deny")

        assertTrue(result.isFailure)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManagePermissionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.PermissionState
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage permission requests (observe + reply).
 * Used by Phase 2 ChatViewModel.
 */
class ManagePermissionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun getPermissionsFlow(sessionId: String): Flow<List<PermissionState>> =
        chatRepository.getPermissionsFlow(sessionId)

    suspend fun replyPermission(permissionId: String, reply: String): Result<Boolean> =
        chatRepository.replyPermission(permissionId, reply)
}
```

---

#### Task 17: ManageQuestionUseCase — 问题回复

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.QuestionState
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageQuestionUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = ManageQuestionUseCase(chatRepository)

    @Test
    fun `replyQuestion returns true on success`() = runTest {
        coEvery { chatRepository.replyQuestion("q1", "yes") } returns Result.success(true)

        val result = useCase.replyQuestion("q1", "yes")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == true)
    }

    @Test
    fun `replyQuestion returns failure on error`() = runTest {
        coEvery { chatRepository.replyQuestion("q1", "no") } returns Result.failure(
            RuntimeException("Timeout")
        )

        val result = useCase.replyQuestion("q1", "no")

        assertTrue(result.isFailure)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageQuestionUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.QuestionState
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: manage questions (observe + reply).
 * Used by Phase 2 ChatViewModel.
 */
class ManageQuestionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun getQuestionsFlow(sessionId: String): Flow<List<QuestionState>> =
        chatRepository.getQuestionsFlow(sessionId)

    suspend fun replyQuestion(questionId: String, answer: String): Result<Boolean> =
        chatRepository.replyQuestion(questionId, answer)
}
```

---

#### Task 18: GetMessagesUseCase — 消息流观察

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.repository.ChatRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetMessagesUseCaseTest {

    private val chatRepository: ChatRepository = mockk()
    private val useCase = GetMessagesUseCase(chatRepository)

    @Test
    fun `invoke emits messages from repository`() = runTest {
        val messages = listOf<Message>(mockk())
        every { chatRepository.getMessagesFlow("s1") } returns flowOf(messages)

        useCase("s1").test {
            assertEquals(messages, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list`() = runTest {
        every { chatRepository.getMessagesFlow("s1") } returns flowOf(emptyList())

        useCase("s1").test {
            assertEquals(emptyList<Message>(), awaitItem())
            awaitComplete()
        }
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetMessagesUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe messages for a session.
 * Used by Phase 2 ChatViewModel.
 */
class GetMessagesUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(sessionId: String): Flow<List<Message>> =
        chatRepository.getMessagesFlow(sessionId)
}
```

---

#### Task 19: ConnectServerUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ConnectServerUseCase(serverRepository)

    @Test
    fun `invoke returns success on connect`() = runTest {
        val config = ServerConfig(id = "srv1", url = "http://host:4096")
        coEvery { serverRepository.connect(config) } returns Result.success(Unit)

        val result = useCase(config)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure on connect error`() = runTest {
        val config = ServerConfig(id = "srv1", url = "http://host:4096")
        coEvery { serverRepository.connect(config) } returns Result.failure(
            java.io.IOException("Connection refused")
        )

        val result = useCase(config)

        assertTrue(result.isFailure)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ConnectServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: connect to a server.
 * Used by Phase 4 HomeViewModel.
 */
class ConnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(server: ServerConfig): Result<Unit> =
        serverRepository.connect(server)
}
```

---

#### Task 20: DisconnectServerUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = DisconnectServerUseCase(serverRepository)

    @Test
    fun `invoke returns success on disconnect`() = runTest {
        coEvery { serverRepository.disconnect("srv1") } returns Result.success(Unit)

        val result = useCase("srv1")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure on disconnect error`() = runTest {
        coEvery { serverRepository.disconnect("srv1") } returns Result.failure(
            RuntimeException("Not connected")
        )

        val result = useCase("srv1")

        assertTrue(result.isFailure)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/DisconnectServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: disconnect from a server.
 * Used by Phase 4 HomeViewModel.
 */
class DisconnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(serverId: String): Result<Unit> =
        serverRepository.disconnect(serverId)
}
```

---

#### Task 21: ManageLocalServerUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.LocalServerState
import dev.minios.ocremote.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageLocalServerUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ManageLocalServerUseCase(serverRepository)

    @Test
    fun `getState returns local server state`() = runTest {
        val state = LocalServerState(status = "running")
        coEvery { serverRepository.getLocalServerState() } returns Result.success(state)

        val result = useCase.getState()

        assertTrue(result.isSuccess)
        assertEquals("running", result.getOrNull()?.status)
    }

    @Test
    fun `getSetupCommand returns command`() {
        every { serverRepository.getLocalSetupCommand() } returns "curl -fsSL https://example.com/setup.sh | bash"

        val cmd = useCase.getSetupCommand()

        assertTrue(cmd.contains("setup"))
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageLocalServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import android.content.Context
import dev.minios.ocremote.domain.model.LocalServerState
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage local server (start/stop/status/setup).
 * Used by Phase 4 HomeViewModel / LocalRuntimeCard.
 */
class ManageLocalServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    fun getSetupCommand(): String = serverRepository.getLocalSetupCommand()

    suspend fun setup(context: Context): Result<Unit> = serverRepository.setupLocalServer(context)

    suspend fun start(context: Context): Result<Unit> = serverRepository.startLocalServer(context)

    suspend fun stop(context: Context): Result<Unit> = serverRepository.stopLocalServer(context)

    suspend fun getState(): Result<LocalServerState> = serverRepository.getLocalServerState()
}
```

---

#### Task 22: ManageServerProvidersUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ProviderInfo
import dev.minios.ocremote.domain.repository.ServerRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageServerProvidersUseCaseTest {

    private val serverRepository: ServerRepository = mockk()
    private val useCase = ManageServerProvidersUseCase(serverRepository)

    @Test
    fun `loadProviders returns provider list`() = runTest {
        val providers = listOf(ProviderInfo(id = "openrouter", name = "OpenRouter", enabled = true))
        coEvery { serverRepository.loadProviders("srv1") } returns Result.success(providers)

        val result = useCase.loadProviders("srv1")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
    }

    @Test
    fun `loadProviders returns failure on error`() = runTest {
        coEvery { serverRepository.loadProviders("srv1") } returns Result.failure(
            RuntimeException("Server not connected")
        )

        val result = useCase.loadProviders("srv1")

        assertTrue(result.isFailure)
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/ManageServerProvidersUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ProviderInfo
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage server providers (load/enable/disable/connect/disconnect/setModelVisible/save).
 * Used by Phase 4 ServerProvidersScreen / ServerModelFilterScreen.
 */
class ManageServerProvidersUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>> =
        serverRepository.loadProviders(serverId)

    suspend fun setProviderEnabled(serverId: String, providerId: String, enabled: Boolean): Result<Unit> =
        serverRepository.setProviderEnabled(serverId, providerId, enabled)

    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit> =
        serverRepository.connectProviderApi(serverId, providerId, apiKey)

    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit> =
        serverRepository.disconnectProvider(serverId, providerId)

    suspend fun setModelVisible(serverId: String, providerId: String, modelId: String, visible: Boolean): Result<Unit> =
        serverRepository.setModelVisible(serverId, providerId, modelId, visible)

    suspend fun saveServerConfig(serverId: String): Result<Unit> =
        serverRepository.saveServerConfig(serverId)
}
```

---

#### Task 23: GetSessionListUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetSessionListUseCaseTest {

    private val sessionRepository: SessionRepository = mockk()
    private val useCase = GetSessionListUseCase(sessionRepository)

    @Test
    fun `invoke emits sessions for server`() = runTest {
        val sessions = listOf(Session(id = "s1", title = "Chat", time = Session.Time(created = 1000L)))
        every { sessionRepository.getSessionsFlow("srv1") } returns flowOf(sessions)

        useCase("srv1").test {
            assertEquals(sessions, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits empty list`() = runTest {
        every { sessionRepository.getSessionsFlow("srv1") } returns flowOf(emptyList())

        useCase("srv1").test {
            assertEquals(emptyList<Session>(), awaitItem())
            awaitComplete()
        }
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSessionListUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe session list for a specific server.
 * Used by Phase 4 SessionListViewModel.
 */
class GetSessionListUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(serverId: String): Flow<List<Session>> =
        sessionRepository.getSessionsFlow(serverId)
}
```

---

#### Task 24: GetSettingsFlowUseCase

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCase.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCaseTest.kt`

- [ ] **Step 1: 编写测试 + 实现**

```kotlin
// app/src/test/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCaseTest.kt
package dev.minios.ocremote.domain.usecase

import app.cash.turbine.test
import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetSettingsFlowUseCaseTest {

    private val settingsRepository: SettingsRepository = mockk()
    private val useCase = GetSettingsFlowUseCase(settingsRepository)

    @Test
    fun `invoke emits current settings`() = runTest {
        val settings = AppSettings(appTheme = "dark")
        every { settingsRepository.getSettingsFlow() } returns flowOf(settings)

        useCase().test {
            assertEquals(settings, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `invoke emits default settings`() = runTest {
        every { settingsRepository.getSettingsFlow() } returns flowOf(AppSettings())

        useCase().test {
            assertEquals(AppSettings(), awaitItem())
            awaitComplete()
        }
    }
}

// app/src/main/kotlin/dev/minios/ocremote/domain/usecase/GetSettingsFlowUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: observe application settings.
 * Used by Phase 4 SettingsViewModel.
 */
class GetSettingsFlowUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> =
        settingsRepository.getSettingsFlow()
}
```

---

### Task 25: 最终验证 — 全量测试 + 编译

- [ ] **Step 1: 运行 Domain 层所有测试**

Run: `./gradlew test --tests "dev.minios.ocremote.domain.usecase.*"`
Expected: 所有 UseCase 测试 PASSED（Phase 1 核心 5 个 UseCase × 3 测试 + Phase 2/4 预留 8 个 UseCase × 2 测试 = ~31 tests）

- [ ] **Step 2: 运行全量单元测试确保无回归**

Run: `./gradlew test`
Expected: ALL TESTS PASSED（包括既有测试 + 新增 UseCase 测试）

- [ ] **Step 3: 验证 Debug 编译通过**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交代码**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/domain/ \
        app/src/main/kotlin/dev/minios/ocremote/di/DomainModule.kt \
        app/src/test/kotlin/dev/minios/ocremote/domain/
git commit -m "feat(domain): Phase 1 — domain models, repository interfaces, use cases with tests

- Add domain models: PermissionState, QuestionState, CreateSessionOpts, AppSettings, ServerHealth, LocalServerState, ProviderInfo
- Define repository interfaces: ChatRepository, SessionRepository, ServerRepository, SettingsRepository
- Core use cases (TDD): SendMessage, CreateSession, DeleteSession, GetServerList, UpdateSettings
- Phase 2/4 pre-allocated use cases: ManageSession, ManagePermission, ManageQuestion, GetMessages, ConnectServer, DisconnectServer, ManageLocalServer, ManageServerProviders, GetSessionList, GetSettingsFlow
- Add DomainModule placeholder (Hilt auto-injects @Inject constructor UseCases)
- All use case tests passing"
```

---

## Self-Review Checklist

### 1. Spec Coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 定义 domain/repository/ 接口 | Task 5, 6, 7, 8 |
| 新增 Domain Model 类型 | Task 1, 2, 3, 4, 4.5 |
| 编写 UseCase 接口 + 失败的单元测试 (RED) | Task 9-13, 15-24 各 Step 1-2 |
| 实现 UseCase 使测试通过 (GREEN) | Task 9-13, 15-24 各 Step 3-4 |
| 重构优化 (REFACTOR) | Task 9-13 (已说明无需重构) |
| 创建 DI Module 绑定 | Task 14 |
| 验证编译通过 + 测试全绿 | Task 25 |

### 2. Placeholder Scan

无 TODO/TBD/placeholder。所有代码步骤包含完整实现。

### 3. Type Consistency

- `ChatRepository.getMessagesFlow` 返回 `Flow<List<Message>>` → `GetMessagesUseCase` 返回 `Flow<List<Message>>` ✓
- `ChatRepository.sendMessage` 返回 `Result<Message>` → `SendMessageUseCase` 返回 `Result<Message>` ✓
- `ChatRepository.replyPermission` 返回 `Result<Boolean>` → `ManagePermissionUseCase.replyPermission` 返回 `Result<Boolean>` ✓
- `ChatRepository.replyQuestion` 返回 `Result<Boolean>` → `ManageQuestionUseCase.replyQuestion` 返回 `Result<Boolean>` ✓
- `SessionRepository.getSessionsFlow(serverId)` → `ManageSessionUseCase.getSessionsFlow` 和 `GetSessionListUseCase` 一致 ✓
- `SessionRepository.createSession(serverId, opts)` → `CreateSessionUseCase(serverId, opts)` ✓
- `SessionRepository.switchSession(sessionId)` → `ManageSessionUseCase.switchSession` ✓
- `SessionRepository.deleteSession(sessionId)` → `DeleteSessionUseCase` 接受 `String` ✓
- `ServerRepository.getServersFlow()` 返回 `Flow<List<ServerConfig>>` → `GetServerListUseCase` 返回 `Flow<List<ServerConfig>>` ✓
- `ServerRepository.connect/disconnect` → `ConnectServerUseCase/DisconnectServerUseCase` ✓
- `ServerRepository.testConnection` 返回 `Result<Boolean>` 对齐 spec §4.1.1 ✓
- `SettingsRepository.getSettingsFlow()` 返回 `Flow<AppSettings>` → `GetSettingsFlowUseCase` 和 `UpdateSettingsUseCase` ✓
- `PermissionState` 字段与 `SseEvent.PermissionAsked` 一一对应 ✓
- `QuestionState` 字段与 `SseEvent.QuestionAsked` 一一对应 ✓
- `AppSettings` 31 个字段与 `data.repository.SettingsRepository` 的 Flow 属性一一对应 ✓
- `DomainModule` 为空 Module，所有 UseCase 使用 `@Inject constructor` 自动注入 ✓

### 4. Phase 2/4 UseCase 覆盖

| Phase | UseCase | Task | 依赖 Repository |
|-------|---------|------|-----------------|
| Phase 2 | GetMessagesUseCase | Task 18 | ChatRepository |
| Phase 2 | ManageSessionUseCase | Task 15 | SessionRepository |
| Phase 2 | ManagePermissionUseCase | Task 16 | ChatRepository |
| Phase 2 | ManageQuestionUseCase | Task 17 | ChatRepository |
| Phase 4 | ConnectServerUseCase | Task 19 | ServerRepository |
| Phase 4 | DisconnectServerUseCase | Task 20 | ServerRepository |
| Phase 4 | ManageLocalServerUseCase | Task 21 | ServerRepository |
| Phase 4 | ManageServerProvidersUseCase | Task 22 | ServerRepository |
| Phase 4 | GetSessionListUseCase | Task 23 | SessionRepository |
| Phase 4 | GetSettingsFlowUseCase | Task 24 | SettingsRepository |

> **注:** Phase 2 中的 SelectModelUseCase、ManageAgentUseCase、ManageTerminalUseCase、DraftUseCase、ShareExportUseCase、UndoRedoUseCase 涉及 ChatScreen 内部状态管理，Phase 1 仅定义 domain 骨架。这些 UseCase 在 Phase 2 Chat 模块重构时根据实际 ViewModel 提取结果再定义接口。
