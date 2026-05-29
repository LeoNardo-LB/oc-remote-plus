# Phase 3: 基础设施层 实施计划

> **For agentic workers:** 按顺序执行 Task 1→8。每个 Task 结束后运行 `./gradlew assembleDebug` 编译验证（Task 3/5/7 额外要求 `./gradlew test` 全绿）。不要跳步、不要 placeholder。

**Goal:** 拆分数据层巨石文件（OpenCodeApi.kt 1289行、EventReducer.kt 540行、OpenCodeConnectionService.kt 906行），实现 DTO/Mapper/Repository Impl 层次，建立 Domain↔Data 双向映射，引入策略模式拆分事件处理。

**Architecture:** 从 OpenCodeApi.kt 提取 DTO → 实现 Mapper → 瘦身 API → EventReducer 策略模式拆分 → Service 拆分 → Repository Impl

**Tech Stack:** Kotlin, Ktor Client 3.5.0, Kotlinx Serialization, Hilt 2.59.2, JUnit 4, MockK, Turbine

**Prerequisites:** Phase 0（Domain Model 定义）+ Phase 1（Domain Repository 接口）已完成。假设存在以下 domain 接口：
- `domain.repository.ChatRepository`
- `domain.repository.SessionRepository`
- `domain.repository.SettingsRepository`（已存在具体实现，后续改造为 Impl）
- `domain.repository.ServerRepository`（已存在具体实现，后续改造为 Impl）

---

## File Structure

```
app/src/main/kotlin/dev/minios/ocremote/
├── data/
│   ├── api/
│   │   ├── OpenCodeApi.kt              ← 瘦身，仅保留 HTTP 调用 + import dto
│   │   ├── SseClient.kt               ← 不动（已是独立模块）
│   │   └── ServerConnection.kt        ← 从 OpenCodeApi.kt 提取
│   ├── dto/
│   │   ├── request/
│   │   │   ├── ChatRequests.kt        ← PromptRequest, PromptPart
│   │   │   ├── ShellRequests.kt       ← ShellRequest
│   │   │   ├── PtyRequests.kt         ← PtyCreateRequest, PtyUpdateRequest, PtySize
│   │   │   ├── QuestionRequests.kt    ← QuestionReplyBody
│   │   │   └── ConfigRequests.kt      ← ServerConfigPatch
│   │   ├── response/
│   │   │   ├── PtyResponse.kt         ← PtyInfo
│   │   │   ├── ProviderResponses.kt   ← ProvidersResponse, ProviderCatalogResponse,
│   │   │   │                             ProviderInfo, ProviderModel, ModelCapabilities,
│   │   │   │                             ModelCost, ModelLimit, ProviderAuthMethod,
│   │   │   │                             ProviderOauthAuthorization
│   │   │   ├── ConfigResponses.kt     ← ServerConfigResponse
│   │   │   ├── PermissionResponses.kt ← PermissionRequest, QuestionRequest,
│   │   │   │                             QuestionInfo, QuestionOption
│   │   │   ├── ToolResponses.kt       ← AgentInfo, CommandInfo, SkillInfo
│   │   │   ├── FileResponses.kt       ← SearchMatch, FileContent, FileNode,
│   │   │   │                             ServerPaths
│   │   │   └── V2Responses.kt         ← TodoItem, SessionStatusInfo, ShellInfo,
│   │   │                               SymbolInfo, FileStatusInfo
│   │   └── common/
│   │       └── ApiModels.kt           ← ModelSelection, OutputFormat, PtySocket
│   ├── mapper/
│   │   ├── PermissionMapper.kt        ← PermissionRequest ↔ SseEvent.PermissionAsked
│   │   ├── QuestionMapper.kt          ← QuestionRequest ↔ SseEvent.QuestionAsked
│   │   ├── ConfigMapper.kt            ← ServerConfigResponse ↔ Domain
│   │   └── ProviderMapper.kt          ← ProviderInfo/Model → Domain
│   ├── repository/
│   │   ├── EventDispatcher.kt         ← 策略调度器
│   │   ├── handler/
│   │   │   ├── SseEventHandler.kt     ← 接口
│   │   │   ├── SessionEventHandler.kt
│   │   │   ├── MessageEventHandler.kt
│   │   │   ├── PermissionEventHandler.kt
│   │   │   ├── QuestionEventHandler.kt
│   │   │   └── MiscEventHandler.kt
│   │   ├── ChatRepositoryImpl.kt
│   │   ├── SessionRepositoryImpl.kt
│   │   ├── ServerRepositoryImpl.kt    ← 重构现有
│   │   ├── SettingsRepositoryImpl.kt  ← 重构现有
│   │   ├── DraftRepository.kt         ← 不动
│   │   └── LocalServerManager.kt      ← 不动
│   └── di/
│       └── HandlerModule.kt           ← Hilt 多绑定
├── service/
│   ├── OpenCodeConnectionService.kt   ← 瘦身
│   ├── SseConnectionManager.kt        ← SSE 连接生命周期
│   └── AppNotificationManager.kt      ← 通知管理
└── domain/
    └── (Phase 0/1 已完成，不修改)

app/src/test/kotlin/dev/minios/ocremote/
├── data/
│   ├── mapper/
│   │   ├── PermissionMapperTest.kt
│   │   ├── QuestionMapperTest.kt
│   │   ├── ConfigMapperTest.kt
│   │   └── ProviderMapperTest.kt
│   └── repository/
│       ├── handler/
│       │   ├── SessionEventHandlerTest.kt
│       │   ├── MessageEventHandlerTest.kt
│       │   ├── PermissionEventHandlerTest.kt
│       │   ├── QuestionEventHandlerTest.kt
│       │   └── MiscEventHandlerTest.kt
│       ├── EventDispatcherTest.kt     ← 替换现有 EventReducerTest.kt
│       ├── ChatRepositoryImplTest.kt
│       └── SessionRepositoryImplTest.kt
```

---

## Task 1: 提取 DTO

**Goal:** 将 OpenCodeApi.kt 底部的所有 `@Serializable` 数据类（L978-1289）提取到 `data/dto/` 目录，保持包名和类名不变（仅移动文件位置）。

**步骤：**

### 1.1 创建目录结构

```bash
mkdir -p data/dto/request data/dto/response data/dto/common
```

### 1.2 创建 `data/dto/request/ChatRequests.kt`

```kotlin
package dev.minios.ocremote.data.dto.request

import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.dto.common.OutputFormat
import kotlinx.serialization.Serializable

@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val format: OutputFormat? = null,
    val system: String? = null,
    val noReply: Boolean? = null
)

@Serializable
data class PromptPart(
    val type: String,
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null
)
```

### 1.3 创建 `data/dto/request/ShellRequests.kt`

```kotlin
package dev.minios.ocremote.data.dto.request

import dev.minios.ocremote.data.dto.common.ModelSelection
import kotlinx.serialization.Serializable

@Serializable
data class ShellRequest(
    val agent: String,
    val model: ModelSelection? = null,
    val command: String
)
```

### 1.4 创建 `data/dto/request/PtyRequests.kt`

```kotlin
package dev.minios.ocremote.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class PtyCreateRequest(
    val title: String? = null,
    val cwd: String? = null
)

@Serializable
data class PtyUpdateRequest(
    val title: String? = null,
    val size: PtySize? = null
)

@Serializable
data class PtySize(
    val rows: Int,
    val cols: Int
)
```

### 1.5 创建 `data/dto/request/QuestionRequests.kt`

```kotlin
package dev.minios.ocremote.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)
```

### 1.6 创建 `data/dto/request/ConfigRequests.kt`

```kotlin
package dev.minios.ocremote.data.dto.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfigPatch(
    @SerialName("disabled_providers") val disabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null
)
```

### 1.7 创建 `data/dto/response/PtyResponse.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class PtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val status: String,
    val pid: Int
)
```

### 1.8 创建 `data/dto/response/ProviderResponses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProvidersResponse(
    val providers: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap()
)

@Serializable
data class ProviderCatalogResponse(
    val all: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val source: String = "",
    val env: List<String> = emptyList(),
    val key: String? = null,
    val options: Map<String, JsonElement> = emptyMap(),
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class ProviderModel(
    val id: String,
    @SerialName("providerID") val providerId: String = "",
    val name: String,
    val family: String? = null,
    val status: String = "active",
    val capabilities: ModelCapabilities? = null,
    val cost: ModelCost? = null,
    val limit: ModelLimit? = null,
    val variants: Map<String, JsonElement>? = null
)

@Serializable
data class ModelCapabilities(
    val temperature: Boolean = false,
    val reasoning: Boolean = false,
    val attachment: Boolean = false,
    val toolcall: Boolean = false
)

@Serializable
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cache: CacheCost? = null
) {
    @Serializable
    data class CacheCost(
        val read: Double = 0.0,
        val write: Double = 0.0
    )
}

@Serializable
data class ModelLimit(
    val context: Int = 0,
    val input: Int? = null,
    val output: Int = 0
)

@Serializable
data class ProviderAuthMethod(
    val type: String,
    val label: String
)

@Serializable
data class ProviderOauthAuthorization(
    val url: String = "",
    val method: String = "none",
    val instructions: String = ""
)
```

### 1.9 创建 `data/dto/response/ConfigResponses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfigResponse(
    @SerialName("disabled_providers") val disabledProviders: List<String> = emptyList(),
    @SerialName("enabled_providers") val enabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null
)
```

### 1.10 创建 `data/dto/response/PermissionResponses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import dev.minios.ocremote.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement>? = null,
    val always: List<String> = emptyList(),
    val tool: ToolRef? = null
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String
)
```

### 1.11 创建 `data/dto/response/ToolResponses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
    val color: String? = null
)

@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val hints: List<String> = emptyList()
)

@Serializable
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val location: String = "",
    val content: String = ""
)
```

### 1.12 创建 `data/dto/response/FileResponses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class SearchMatch(
    val path: String,
    val lines: String,
    val lineNumber: Int,
    val absoluteOffset: Int
)

@Serializable
data class FileContent(
    val type: String,
    val content: String
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val type: String,
    val absolute: String? = null,
    val ignored: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
)

@Serializable
data class ServerPaths(
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
    val directory: String = ""
)
```

### 1.13 创建 `data/dto/response/V2Responses.kt`

```kotlin
package dev.minios.ocremote.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    val id: String = "",
    val content: String,
    val status: String = "pending",
    val priority: String = "medium"
)

@Serializable
data class SessionStatusInfo(
    val id: String = "",
    val status: Map<String, String> = emptyMap()
)

@Serializable
data class ShellInfo(
    val path: String,
    val name: String,
    val acceptable: Boolean = true
)

@Serializable
data class SymbolInfo(
    val name: String,
    val kind: String = "",
    val path: String = "",
    val line: Int? = null,
    val language: String? = null
)

@Serializable
data class FileStatusInfo(
    val path: String,
    val status: String,
    val staged: Boolean = false
)
```

### 1.14 创建 `data/dto/common/ApiModels.kt`

```kotlin
package dev.minios.ocremote.data.dto.common

import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelSelection(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String
)

@Serializable
data class OutputFormat(
    val type: String,
    val schema: String? = null
)

class PtySocket(
    private val session: ClientWebSocketSession
) {
    suspend fun send(input: String) {
        session.send(input)
    }

    suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
    }

    suspend fun readLoop(onText: suspend (String) -> Unit) {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> onText(frame.readText())
                is Frame.Binary -> {
                    val data = frame.data
                    if (data.isNotEmpty() && data[0].toInt() == 0) continue
                    onText(data.toString(Charsets.UTF_8))
                }
                else -> { /* ignore */ }
            }
        }
    }
}
```

### 1.15 提取 `data/api/ServerConnection.kt`

```kotlin
package dev.minios.ocremote.data.api

import java.util.Base64

data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?
) {
    companion object {
        fun from(url: String, username: String = "opencode", password: String? = null): ServerConnection {
            val base = url.trimEnd('/')
            val auth = if (password != null) {
                val credentials = "$username:$password"
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
            } else {
                null
            }
            return ServerConnection(base, auth)
        }
    }
}
```

### 1.16 更新 OpenCodeApi.kt 的 import

在 OpenCodeApi.kt 中：
- **删除**所有 `@Serializable data class` 定义（L978-1289）
- **删除** `ServerConnection` 类定义（L36-52）
- **删除** `PtySocket` 类定义（L951-976）
- **添加**以下 import：

```kotlin
import dev.minios.ocremote.data.dto.request.*
import dev.minios.ocremote.data.dto.response.*
import dev.minios.ocremote.data.dto.common.*
```

### 1.17 编译验证

```bash
./gradlew assembleDebug
```

修复所有 import 错误。重点关注 `SseClient.kt` 中无直接 DTO 引用（它使用 domain model），所以无需改动。

---

## Task 2: 提取 Serializer（如有）

**Goal:** 检查并提取自定义序列化器。

**分析：** 当前自定义序列化器位于 domain/model 中：
- `MessageSerializer`（在 `Message.kt`）— 多态分发
- `PartSerializer`（在 `Part.kt`）— 多态分发
- `ToolStateSerializer`（在 `ToolState.kt`）— 多态分发

**决策：** 这些序列化器与 Domain 类型强耦合（sealed class 的伴生序列化器），移到 `data/serializer/` 会引入循环依赖或降低内聚性。**保持不动。**

**如果未来需要 API 层专用序列化器（如自定义日期格式），创建：**

### 2.1 创建 `data/serializer/CustomSerializers.kt`（预留）

```kotlin
package dev.minios.ocremote.data.serializer

/**
 * API-specific custom serializers.
 * Place future API-layer serializers here (e.g., custom date formats, enum fallbacks).
 *
 * Domain-layer polymorphic serializers (MessageSerializer, PartSerializer, ToolStateSerializer)
 * remain in domain/model/ as they are tightly coupled to their sealed classes.
 */
```

### 2.2 编译验证

```bash
./gradlew assembleDebug
```

---

## Task 3: 实现 Mapper（含测试）

**Goal:** 实现 API DTO ↔ Domain Model 双向映射。

### 3.1 创建 `data/mapper/PermissionMapper.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.data.dto.response.QuestionInfo
import dev.minios.ocremote.data.dto.response.QuestionOption
import dev.minios.ocremote.data.dto.response.QuestionRequest
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps between API DTO (PermissionRequest) and Domain (SseEvent.PermissionAsked).
 *
 * Key differences:
 * - API: always is List<String>;  Domain: always is Boolean
 * - API: metadata is Map<String, JsonElement>;  Domain: metadata is Map<String, String>
 */
object PermissionMapper {

    /** API DTO → Domain */
    fun toDomain(dto: PermissionRequest): SseEvent.PermissionAsked {
        val alwaysBoolean = dto.always.isNotEmpty()
        val metadataStrings = dto.metadata?.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull ?: v.toString()
        }
        return SseEvent.PermissionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            permission = dto.permission,
            patterns = dto.patterns,
            metadata = metadataStrings,
            always = alwaysBoolean,
            tool = dto.tool
        )
    }

    /** Domain → API DTO */
    fun toDto(domain: SseEvent.PermissionAsked): PermissionRequest {
        val metadataElements = domain.metadata?.mapValues { (_, v) ->
            JsonPrimitive(v) as JsonElement
        }
        val alwaysList = if (domain.always) listOf("*") else emptyList()
        return PermissionRequest(
            id = domain.id,
            sessionId = domain.sessionId,
            permission = domain.permission,
            patterns = domain.patterns,
            metadata = metadataElements,
            always = alwaysList,
            tool = domain.tool
        )
    }
}
```

### 3.2 创建 `data/mapper/QuestionMapper.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.QuestionInfo
import dev.minios.ocremote.data.dto.response.QuestionOption
import dev.minios.ocremote.data.dto.response.QuestionRequest
import dev.minios.ocremote.domain.model.SseEvent

/**
 * Maps between API DTO (QuestionRequest) and Domain (SseEvent.QuestionAsked).
 *
 * Key differences:
 * - API uses QuestionInfo/QuestionOption; Domain uses QuestionAsked.Question/Option
 * - Field names are identical but types are in different packages
 */
object QuestionMapper {

    /** API DTO → Domain */
    fun toDomain(dto: QuestionRequest): SseEvent.QuestionAsked {
        return SseEvent.QuestionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            questions = dto.questions.map { it.toDomain() },
            tool = dto.tool
        )
    }

    /** Domain → API DTO */
    fun toDto(domain: SseEvent.QuestionAsked): QuestionRequest {
        return QuestionRequest(
            id = domain.id,
            sessionId = domain.sessionId,
            questions = domain.questions.map { it.toDto() },
            tool = domain.tool
        )
    }

    private fun QuestionInfo.toDomain(): SseEvent.QuestionAsked.Question {
        return SseEvent.QuestionAsked.Question(
            header = header,
            question = question,
            multiple = multiple,
            custom = custom,
            options = options.map { SseEvent.QuestionAsked.Option(it.label, it.description) }
        )
    }

    private fun SseEvent.QuestionAsked.Question.toDto(): QuestionInfo {
        return QuestionInfo(
            question = question,
            header = header,
            options = options.map { QuestionOption(it.label, it.description) },
            multiple = multiple,
            custom = custom
        )
    }
}
```

### 3.3 创建 `data/mapper/ConfigMapper.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.ServerConfigResponse
import dev.minios.ocremote.data.dto.request.ServerConfigPatch

/**
 * Maps between API Config DTOs and Domain-layer config representations.
 *
 * ServerConfigResponse and ServerConfigPatch are currently used directly
 * in the API layer. This mapper exists for cases where the domain needs
 * a simplified view of server configuration.
 */
object ConfigMapper {

    /**
     * Extract disabled provider list from response.
     * Domain uses simple string list; no dedicated domain type yet.
     */
    fun toDisabledProviders(response: ServerConfigResponse): List<String> {
        return response.disabledProviders
    }

    /**
     * Build a patch from individual field updates.
     */
    fun toPatch(
        disabledProviders: List<String>? = null,
        model: String? = null,
        smallModel: String? = null,
        defaultAgent: String? = null
    ): ServerConfigPatch {
        return ServerConfigPatch(
            disabledProviders = disabledProviders,
            model = model,
            smallModel = smallModel,
            defaultAgent = defaultAgent
        )
    }
}
```

### 3.4 创建 `data/mapper/ProviderMapper.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.dto.response.ProviderInfo
import dev.minios.ocremote.data.dto.response.ProviderModel
import dev.minios.ocremote.data.dto.response.ProvidersResponse
import dev.minios.ocremote.data.dto.response.ProviderCatalogResponse

/**
 * Maps provider-related API responses to simplified domain representations.
 *
 * Currently the provider DTOs are consumed directly by ViewModels.
 * This mapper provides conversion for cases where domain layer needs
 * provider information without API-layer serialization annotations.
 */
object ProviderMapper {

    /** Extract provider ID → display name map for UI selection. */
    fun toProviderNameMap(response: ProvidersResponse): Map<String, String> {
        return response.providers.associate { it.id to it.name }
    }

    /** Extract all model IDs grouped by provider. */
    fun toModelsByProvider(response: ProvidersResponse): Map<String, List<ProviderModel>> {
        return response.providers.associate { it.id to it.models.values.toList() }
    }

    /** Extract connected provider IDs from catalog. */
    fun toConnectedProviderIds(response: ProviderCatalogResponse): Set<String> {
        return response.connected.toSet()
    }

    /** Convert provider+model pair to ModelSelection for API requests. */
    fun toModelSelection(providerId: String, modelId: String): ModelSelection {
        return ModelSelection(providerId = providerId, modelId = modelId)
    }
}
```

### 3.5 创建 Mapper 测试

#### `data/mapper/PermissionMapperTest.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.PermissionRequest
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolRef
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PermissionMapperTest {

    @Test
    fun `toDomain maps all fields correctly`() {
        val dto = PermissionRequest(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home"),
            metadata = mapOf("key" to JsonPrimitive("value")),
            always = listOf("*"),
            tool = ToolRef(messageId = "m1", callId = "c1")
        )

        val domain = PermissionMapper.toDomain(dto)

        assertEquals("p1", domain.id)
        assertEquals("s1", domain.sessionId)
        assertEquals("bash", domain.permission)
        assertEquals(listOf("/home"), domain.patterns)
        assertEquals(mapOf("key" to "value"), domain.metadata)
        assertTrue(domain.always)
        assertEquals(ToolRef("m1", "c1"), domain.tool)
    }

    @Test
    fun `toDomain maps empty always to false`() {
        val dto = PermissionRequest(id = "p1", sessionId = "s1", permission = "read")

        val domain = PermissionMapper.toDomain(dto)

        assertFalse(domain.always)
    }

    @Test
    fun `toDto maps all fields correctly`() {
        val domain = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "bash",
            patterns = listOf("/home"),
            metadata = mapOf("key" to "value"),
            always = true,
            tool = ToolRef(messageId = "m1", callId = "c1")
        )

        val dto = PermissionMapper.toDto(domain)

        assertEquals("p1", dto.id)
        assertEquals("s1", dto.sessionId)
        assertEquals("bash", dto.permission)
        assertEquals(listOf("/home"), dto.patterns)
        assertNotNull(dto.metadata)
        assertEquals("value", dto.metadata!!["key"]!!.jsonPrimitive.content)
        assertTrue(dto.always.isNotEmpty())
        assertEquals(ToolRef("m1", "c1"), dto.tool)
    }

    @Test
    fun `round-trip toDomain then toDto preserves semantic meaning`() {
        val original = SseEvent.PermissionAsked(
            id = "p1",
            sessionId = "s1",
            permission = "write",
            patterns = listOf("/home/user/project"),
            metadata = mapOf("file" to "test.kt"),
            always = false,
            tool = null
        )

        val dto = PermissionMapper.toDto(original)
        val roundTripped = PermissionMapper.toDomain(dto)

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.sessionId, roundTripped.sessionId)
        assertEquals(original.permission, roundTripped.permission)
        assertEquals(original.patterns, roundTripped.patterns)
        assertEquals(original.metadata, roundTripped.metadata)
        assertEquals(original.always, roundTripped.always)
        assertEquals(original.tool, roundTripped.tool)
    }
}
```

#### `data/mapper/QuestionMapperTest.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.QuestionRequest
import dev.minios.ocremote.data.dto.response.QuestionInfo
import dev.minios.ocremote.data.dto.response.QuestionOption
import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Test

class QuestionMapperTest {

    @Test
    fun `toDomain maps all fields correctly`() {
        val dto = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Which model?",
                    header = "Model Selection",
                    options = listOf(
                        QuestionOption("GPT-4", "Most capable"),
                        QuestionOption("GPT-3.5", "Faster")
                    ),
                    multiple = false,
                    custom = true
                )
            )
        )

        val domain = QuestionMapper.toDomain(dto)

        assertEquals("q1", domain.id)
        assertEquals("s1", domain.sessionId)
        assertEquals(1, domain.questions.size)
        val q = domain.questions[0]
        assertEquals("Which model?", q.question)
        assertEquals("Model Selection", q.header)
        assertEquals(2, q.options.size)
        assertEquals("GPT-4", q.options[0].label)
        assertFalse(q.multiple)
        assertTrue(q.custom)
    }

    @Test
    fun `toDto maps all fields correctly`() {
        val domain = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    question = "Confirm?",
                    header = "Action",
                    options = listOf(SseEvent.QuestionAsked.Option("Yes", "Proceed")),
                    multiple = false,
                    custom = false
                )
            )
        )

        val dto = QuestionMapper.toDto(domain)

        assertEquals("q1", dto.id)
        assertEquals("s1", dto.sessionId)
        assertEquals(1, dto.questions.size)
        assertEquals("Confirm?", dto.questions[0].question)
        assertFalse(dto.questions[0].custom)
    }

    @Test
    fun `round-trip preserves all data`() {
        val original = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    question = "Pick tools",
                    header = "Tools",
                    options = listOf(
                        SseEvent.QuestionAsked.Option("Tool A", "Desc A"),
                        SseEvent.QuestionAsked.Option("Tool B", "Desc B")
                    ),
                    multiple = true,
                    custom = true
                )
            )
        )

        val roundTripped = QuestionMapper.toDomain(QuestionMapper.toDto(original))

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.questions.size, roundTripped.questions.size)
        assertEquals(original.questions[0].options.size, roundTripped.questions[0].options.size)
        assertEquals(original.questions[0].multiple, roundTripped.questions[0].multiple)
        assertEquals(original.questions[0].custom, roundTripped.questions[0].custom)
    }

    @Test
    fun `empty questions list maps correctly`() {
        val dto = QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        val domain = QuestionMapper.toDomain(dto)
        assertTrue(domain.questions.isEmpty())
    }
}
```

#### `data/mapper/ConfigMapperTest.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.ServerConfigResponse
import org.junit.Assert.*
import org.junit.Test

class ConfigMapperTest {

    @Test
    fun `toDisabledProviders extracts list`() {
        val response = ServerConfigResponse(
            disabledProviders = listOf("provider-a", "provider-b"),
            model = "gpt-4"
        )
        val result = ConfigMapper.toDisabledProviders(response)
        assertEquals(listOf("provider-a", "provider-b"), result)
    }

    @Test
    fun `toPatch builds correct patch`() {
        val patch = ConfigMapper.toPatch(
            disabledProviders = listOf("x"),
            model = "gpt-4",
            smallModel = "gpt-3.5",
            defaultAgent = "code"
        )
        assertEquals(listOf("x"), patch.disabledProviders)
        assertEquals("gpt-4", patch.model)
        assertEquals("gpt-3.5", patch.smallModel)
        assertEquals("code", patch.defaultAgent)
    }

    @Test
    fun `toPatch with nulls preserves defaults`() {
        val patch = ConfigMapper.toPatch()
        assertNull(patch.disabledProviders)
        assertNull(patch.model)
    }
}
```

#### `data/mapper/ProviderMapperTest.kt`

```kotlin
package dev.minios.ocremote.data.mapper

import dev.minios.ocremote.data.dto.response.*
import org.junit.Assert.*
import org.junit.Test

class ProviderMapperTest {

    @Test
    fun `toProviderNameMap creates id-name mapping`() {
        val response = ProvidersResponse(
            providers = listOf(
                ProviderInfo(id = "openai", name = "OpenAI"),
                ProviderInfo(id = "anthropic", name = "Anthropic")
            )
        )
        val map = ProviderMapper.toProviderNameMap(response)
        assertEquals(mapOf("openai" to "OpenAI", "anthropic" to "Anthropic"), map)
    }

    @Test
    fun `toConnectedProviderIds extracts connected set`() {
        val response = ProviderCatalogResponse(
            all = emptyList(),
            connected = listOf("openai", "anthropic")
        )
        val ids = ProviderMapper.toConnectedProviderIds(response)
        assertEquals(setOf("openai", "anthropic"), ids)
    }

    @Test
    fun `toModelSelection creates correct selection`() {
        val sel = ProviderMapper.toModelSelection("openai", "gpt-4")
        assertEquals("openai", sel.providerId)
        assertEquals("gpt-4", sel.modelId)
    }
}
```

### 3.6 编译 + 测试验证

```bash
./gradlew assembleDebug
./gradlew test
```

---

## Task 4: 瘦身 OpenCodeApi.kt

**Goal:** OpenCodeApi.kt 仅保留 HTTP 接口定义 + 最小化工具方法。

### 4.1 瘦身后的 OpenCodeApi.kt 结构

```kotlin
package dev.minios.ocremote.data.api

import dev.minios.ocremote.data.dto.request.*
import dev.minios.ocremote.data.dto.response.*
import dev.minios.ocremote.data.dto.common.*
import dev.minios.ocremote.domain.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCodeApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: kotlinx.serialization.json.Json
) {
    companion object {
        private const val TAG = "OpenCodeApi"
    }

    // ============ Global ============
    suspend fun getHealth(conn: ServerConnection): ServerHealth { ... }
    suspend fun getServerPaths(conn: ServerConnection): ServerPaths { ... }

    // ============ Project ============
    suspend fun listProjects(conn: ServerConnection): List<Project> { ... }
    suspend fun getCurrentProject(conn: ServerConnection): Project { ... }

    // ============ Agents ============
    suspend fun listAgents(conn: ServerConnection): List<AgentInfo> { ... }

    // ============ Session ============
    suspend fun listSessions(conn: ServerConnection, directory: String? = null): List<Session> { ... }
    suspend fun getSession(conn: ServerConnection, sessionId: String): Session { ... }
    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String { ... }
    suspend fun createSession(conn: ServerConnection, title: String? = null, parentId: String? = null, directory: String? = null): Session { ... }
    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean { ... }
    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session { ... }
    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean { ... }
    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> { ... }
    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session { ... }
    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session { ... }
    suspend fun summarizeSession(conn: ServerConnection, sessionId: String, providerId: String, modelId: String): Boolean { ... }
    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session { ... }
    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session { ... }
    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session { ... }
    suspend fun executeCommand(...): Boolean { ... }
    suspend fun runShellCommand(...): Boolean { ... }

    // ============ PTY ============
    suspend fun createPty(...): PtyInfo { ... }
    suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean { ... }
    suspend fun updatePtySize(...): Boolean { ... }
    suspend fun openPtySocket(...): PtySocket { ... }

    // ============ Messages ============
    suspend fun listMessages(...): List<MessageWithParts> { ... }
    suspend fun listMessagesRaw(...): String { ... }
    suspend fun exportSessionToStream(...): Unit { ... }
    suspend fun getMessage(...): MessageWithParts { ... }
    suspend fun promptAsync(...): Unit { ... }

    // ============ Permissions ============
    suspend fun replyToPermission(...): Boolean { ... }
    suspend fun listPendingPermissions(...): List<PermissionRequest> { ... }

    // ============ Questions ============
    suspend fun replyToQuestion(...): Boolean { ... }
    suspend fun rejectQuestion(...): Boolean { ... }
    suspend fun listPendingQuestions(...): List<QuestionRequest> { ... }

    // ============ Config / Providers ============
    suspend fun getProviders(...): ProvidersResponse { ... }
    suspend fun listProviderCatalog(...): ProviderCatalogResponse { ... }
    suspend fun getProviderAuthMethods(...): Map<String, List<ProviderAuthMethod>> { ... }
    suspend fun authorizeProviderOauth(...): ProviderOauthAuthorization? { ... }
    suspend fun completeProviderOauth(...): Boolean { ... }
    suspend fun setProviderApiKey(...): Boolean { ... }
    suspend fun removeProviderAuth(...): Boolean { ... }
    suspend fun getConfig(...): ServerConfigResponse { ... }
    suspend fun getGlobalConfig(...): ServerConfigResponse { ... }
    suspend fun updateConfig(...): ServerConfigResponse { ... }
    suspend fun updateGlobalConfig(...): ServerConfigResponse { ... }
    suspend fun disposeGlobal(...): Boolean { ... }

    // ============ Commands ============
    suspend fun listCommands(...): List<CommandInfo> { ... }

    // ============ V2 Endpoints ============
    suspend fun listSkills(...): List<SkillInfo> { ... }
    suspend fun listSessionChildren(...): List<Session> { ... }
    suspend fun getSessionTodos(...): List<TodoItem> { ... }
    suspend fun listSessionStatus(...): Map<String, SessionStatusInfo> { ... }
    suspend fun listPtyShells(...): List<ShellInfo> { ... }
    suspend fun findSymbols(...): List<SymbolInfo> { ... }
    suspend fun getFileStatus(...): List<FileStatusInfo> { ... }
    suspend fun disposeInstance(...): Boolean { ... }

    // ============ Files ============
    suspend fun searchText(...): List<SearchMatch> { ... }
    suspend fun findFiles(...): List<String> { ... }
    suspend fun readFile(...): FileContent { ... }
    suspend fun listDirectory(...): List<FileNode> { ... }

    // ============ Private Helpers ============
    private fun parsePtyInfoFromCreateResponse(...): PtyInfo { ... }
    private fun extractPtyIdFromResponse(...): String? { ... }
    private fun findPtyId(...): String? { ... }
}
```

**关键变更：**
- 所有方法体保持不变
- 删除底部所有 DTO 类定义
- 删除 ServerConnection 类定义（移到 `data/api/ServerConnection.kt`）
- 删除 PtySocket 类定义（移到 `data/dto/common/ApiModels.kt`）
- 更新 import 指向 `data.dto.*`

### 4.2 更新其他文件的 import

全局搜索并替换所有引用旧 DTO 位置的文件：
- `ServerRepository.kt` — 已使用 `data.api.ServerConnection`（现在独立文件，import 不变）
- `OpenCodeConnectionService.kt` — 同上
- 其他 ViewModel/Screen 引用 DTO 的地方

### 4.3 编译验证

```bash
./gradlew assembleDebug
```

---

## Task 5: EventReducer → EventDispatcher + Handler（策略模式）

**Goal:** 将 EventReducer.kt（540行）拆分为 EventDispatcher + 5个 Handler，使用 Hilt 多绑定。

### 5.1 创建 `data/repository/handler/SseEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent

/**
 * Strategy interface for handling SSE events by category.
 * Each handler processes a subset of SseEvent types and updates shared state.
 */
interface SseEventHandler {
    /**
     * @return true if this handler processed the event
     */
    fun handle(event: SseEvent, serverId: String): Boolean
}
```

### 5.2 创建 `data/repository/handler/SessionEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles session lifecycle events: created, updated, deleted, status, idle, diff, error, compacted.
 * Manages: sessions, sessionStatuses, serverSessions, sessionDiffs, vcsBranch, projectInfo
 */
@Singleton
class SessionEventHandler @Inject constructor() : SseEventHandler {

    private val TAG = "SessionEventHandler"

    val serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessionStatuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
    val vcsBranch = MutableStateFlow<String?>(null)
    val projectInfo = MutableStateFlow<Project?>(null)

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.ServerConnected -> { if (BuildConfig.DEBUG) Log.d(TAG, "Server connected"); true }
            is SseEvent.ServerHeartbeat -> true
            is SseEvent.ServerInstanceDisposed -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}"); true
            }
            is SseEvent.SessionCreated -> { handleSessionCreated(event, serverId); true }
            is SseEvent.SessionUpdated -> { handleSessionUpdated(event, serverId); true }
            is SseEvent.SessionDeleted -> { handleSessionDeleted(event); true }
            is SseEvent.SessionStatus -> { handleSessionStatus(event); true }
            is SseEvent.SessionIdle -> { handleSessionIdle(event); true }
            is SseEvent.SessionDiff -> { handleSessionDiff(event); true }
            is SseEvent.SessionError -> { handleSessionError(event); true }
            is SseEvent.SessionCompacted -> {
                Log.i(TAG, "Session ${event.sessionId} compacted"); true
            }
            is SseEvent.VcsBranchUpdated -> { vcsBranch.value = event.branch; true }
            is SseEvent.ProjectUpdated -> { projectInfo.value = event.info; true }
            else -> false
        }
    }

    private fun trackSession(serverId: String, sessionId: String) {
        serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionId))
        }
    }

    private fun handleSessionCreated(event: SseEvent.SessionCreated, serverId: String) {
        trackSession(serverId, event.info.id)
        sessions.update { (it + event.info).sortedByDescending { s -> s.time.updated } }
        sessionStatuses.update { it + (event.info.id to SessionStatus.Idle) }
    }

    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        trackSession(serverId, event.info.id)
        sessions.update { current ->
            val idx = current.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                current.toMutableList().apply { set(idx, event.info) }
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.info.id} not found, upserting")
                (current + event.info).sortedByDescending { s -> s.time.updated }
            }
        }
    }

    private fun handleSessionDeleted(event: SseEvent.SessionDeleted) {
        val sessionId = event.info.id
        sessions.update { it.filter { s -> s.id != sessionId } }
        sessionStatuses.update { it - sessionId }
        sessionDiffs.update { it - sessionId }
    }

    private fun handleSessionStatus(event: SseEvent.SessionStatus) {
        sessionStatuses.update { it + (event.sessionId to event.status) }
    }

    private fun handleSessionIdle(event: SseEvent.SessionIdle) {
        sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
    }

    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        sessionDiffs.update { it + (event.sessionId to event.diff) }
    }

    private fun handleSessionError(event: SseEvent.SessionError) {
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error}")
    }

    // ============ Batch Operations ============

    fun setSessions(serverId: String, newSessions: List<Session>) {
        val sessionIds = newSessions.map { it.id }.toSet()
        serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        sessions.update { current ->
            val updated = current.toMutableList()
            for (session in newSessions) {
                val idx = updated.indexOfFirst { it.id == session.id }
                if (idx >= 0) updated[idx] = session else updated.add(session)
            }
            updated.sortedByDescending { it.time.updated }
        }
    }

    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        sessionStatuses.update { it + (sessionId to status) }
    }

    fun clearForServer(serverId: String) {
        val sessionIds = serverSessions.value[serverId] ?: emptySet()
        if (sessionIds.isEmpty()) {
            serverSessions.update { it - serverId }
            return
        }
        serverSessions.update { it - serverId }
        sessions.update { it.filter { s -> s.id !in sessionIds } }
        sessionStatuses.update { it - sessionIds }
        sessionDiffs.update { it - sessionIds }
    }

    fun clearAll() {
        serverSessions.value = emptyMap()
        sessions.value = emptyList()
        sessionStatuses.value = emptyMap()
        sessionDiffs.value = emptyMap()
        vcsBranch.value = null
        projectInfo.value = null
    }
}
```

### 5.3 创建 `data/repository/handler/MessageEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles message and part events: updated, removed, part updated/delta/removed.
 * Manages: messages, parts
 */
@Singleton
class MessageEventHandler @Inject constructor() : SseEventHandler {

    private val TAG = "MessageEventHandler"

    val messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap())

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.MessageUpdated -> { handleMessageUpdated(event); true }
            is SseEvent.MessageRemoved -> { handleMessageRemoved(event); true }
            is SseEvent.MessagePartUpdated -> { handleMessagePartUpdated(event); true }
            is SseEvent.MessagePartDelta -> { handleMessagePartDelta(event); true }
            is SseEvent.MessagePartRemoved -> { handleMessagePartRemoved(event); true }
            else -> false
        }
    }

    private fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        messages.update { current ->
            val sessionMessages = current[sessionId]?.toMutableList() ?: mutableListOf()
            val idx = sessionMessages.indexOfFirst { it.id == event.info.id }
            if (idx >= 0) {
                sessionMessages[idx] = event.info
            } else {
                sessionMessages.add(event.info)
                sessionMessages.sortByDescending { it.time.created }
            }
            current + (sessionId to sessionMessages)
        }
    }

    private fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) current + (event.sessionId to sessionMessages) else current
        }
        parts.update { it - event.messageId }
    }

    private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        val messageId = event.part.messageId
        parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val idx = messageParts.indexOfFirst { it.id == event.part.id }
            if (idx >= 0) messageParts[idx] = event.part else messageParts.add(event.part)
            current + (messageId to messageParts)
        }
    }

    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        parts.update { current ->
            val messageParts = current[event.messageId]?.toMutableList() ?: return@update current
            val idx = messageParts.indexOfFirst { it.id == event.partId }
            if (idx < 0) return@update current
            val part = messageParts[idx]
            val updated = when (part) {
                is Part.Text -> part.copy(text = part.text + event.delta)
                is Part.Reasoning -> part.copy(text = part.text + event.delta)
                else -> part
            }
            messageParts[idx] = updated
            current + (event.messageId to messageParts)
        }
    }

    private fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) current + (event.messageId to messageParts) else current
        }
    }

    // ============ Batch Operations ============

    fun setMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        messages.update { it + (sessionId to newMessages.map { m -> m.info }.sortedByDescending { m -> m.time.created }) }
        val partsMap = newMessages.associate { it.info.id to it.parts }
        parts.update { it + partsMap }
    }

    fun mergeMessages(sessionId: String, newMessages: List<MessageWithParts>) {
        val incoming = newMessages.map { it.info }.sortedByDescending { m -> m.time.created }
        messages.update { current ->
            val existing = current[sessionId] ?: emptyList()
            val existingById = existing.associateBy { it.id }
            current + (sessionId to incoming.map { newMsg -> existingById[newMsg.id] ?: newMsg })
        }
        parts.update { currentParts ->
            val existingKeys = currentParts.keys
            val newParts = newMessages
                .filter { it.info.id !in existingKeys }
                .associate { it.info.id to it.parts }
            currentParts + newParts
        }
    }

    fun clearForServer(sessionIds: Set<String>) {
        val messageIds = messages.value
            .filterKeys { it in sessionIds }.values.flatten()
            .map { it.id }.toSet()
        messages.update { it - sessionIds }
        parts.update { it - messageIds }
    }

    fun clearAll() {
        messages.value = emptyMap()
        parts.value = emptyMap()
    }
}
```

### 5.4 创建 `data/repository/handler/PermissionEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles permission events: asked, replied.
 * Manages: permissions
 */
@Singleton
class PermissionEventHandler @Inject constructor() : SseEventHandler {

    val permissions = MutableStateFlow<Map<String, List<SseEvent.PermissionAsked>>>(emptyMap())

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.PermissionAsked -> { handlePermissionAsked(event); true }
            is SseEvent.PermissionReplied -> { handlePermissionReplied(event); true }
            else -> false
        }
    }

    private fun handlePermissionAsked(event: SseEvent.PermissionAsked) {
        permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionPerms.add(event)
            current + (event.sessionId to sessionPerms)
        }
    }

    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        permissions.update { current ->
            val sessionPerms = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionPerms != null) current + (event.sessionId to sessionPerms) else current
        }
    }

    fun removePermission(permissionId: String) {
        permissions.update { current ->
            current.mapValues { (_, perms) -> perms.filter { it.id != permissionId } }
        }
    }

    fun setPermissions(sessionId: String, perms: List<SseEvent.PermissionAsked>) {
        permissions.update { current ->
            if (perms.isEmpty()) current - sessionId else current + (sessionId to perms)
        }
    }

    fun clearForServer(sessionIds: Set<String>) {
        permissions.update { it - sessionIds }
    }

    fun clearAll() {
        permissions.value = emptyMap()
    }
}
```

### 5.5 创建 `data/repository/handler/QuestionEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles question events: asked, replied, rejected.
 * Manages: questions
 */
@Singleton
class QuestionEventHandler @Inject constructor() : SseEventHandler {

    val questions = MutableStateFlow<Map<String, List<SseEvent.QuestionAsked>>>(emptyMap())

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.QuestionAsked -> { handleQuestionAsked(event); true }
            is SseEvent.QuestionReplied -> { handleQuestionReplied(event); true }
            is SseEvent.QuestionRejected -> { handleQuestionRejected(event); true }
            else -> false
        }
    }

    private fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
        questions.update { current ->
            val sessionQs = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionQs.add(event)
            current + (event.sessionId to sessionQs)
        }
    }

    private fun handleQuestionReplied(event: SseEvent.QuestionReplied) {
        questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    private fun handleQuestionRejected(event: SseEvent.QuestionRejected) {
        questions.update { current ->
            val sessionQs = current[event.sessionId]?.filter { it.id != event.requestId }
            if (sessionQs != null) current + (event.sessionId to sessionQs) else current
        }
    }

    fun removeQuestion(questionId: String) {
        questions.update { current ->
            current.mapValues { (_, qs) -> qs.filter { it.id != questionId } }
        }
    }

    fun setQuestions(sessionId: String, qs: List<SseEvent.QuestionAsked>) {
        questions.update { current ->
            if (qs.isEmpty()) current - sessionId else current + (sessionId to qs)
        }
    }

    fun clearForServer(sessionIds: Set<String>) {
        questions.update { it - sessionIds }
    }

    fun clearAll() {
        questions.value = emptyMap()
    }
}
```

### 5.6 创建 `data/repository/handler/MiscEventHandler.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles miscellaneous events: todos, PTY, workspace, file, MCP, command, installation, worktree.
 * Manages: todos
 */
@Singleton
class MiscEventHandler @Inject constructor() : SseEventHandler {

    private val TAG = "MiscEventHandler"

    val todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())

    override fun handle(event: SseEvent, serverId: String): Boolean {
        return when (event) {
            is SseEvent.TodoUpdated -> { todos.update { it + (event.sessionId to event.todos) }; true }
            is SseEvent.PtyCreated -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY created: ${event.id}"); true }
            is SseEvent.PtyUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY updated: ${event.id}"); true }
            is SseEvent.PtyDeleted -> { if (BuildConfig.DEBUG) Log.d(TAG, "PTY deleted: ${event.id}"); true }
            is SseEvent.WorkspaceReady -> { if (BuildConfig.DEBUG) Log.d(TAG, "Workspace ready: ${event.workspaceId}"); true }
            is SseEvent.WorkspaceFailed -> { Log.w(TAG, "Workspace failed: ${event.workspaceId}"); true }
            is SseEvent.FileEdited -> { if (BuildConfig.DEBUG) Log.d(TAG, "File edited: ${event.path}"); true }
            is SseEvent.McpToolsChanged -> { if (BuildConfig.DEBUG) Log.d(TAG, "MCP tools changed: ${event.server}"); true }
            is SseEvent.CommandExecuted -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Command executed: ${event.name}")
                true
            }
            is SseEvent.FileWatcherUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "File watcher updated: ${event.path}"); true }
            is SseEvent.InstallationUpdated -> { if (BuildConfig.DEBUG) Log.d(TAG, "Installation updated: ${event.version}"); true }
            is SseEvent.InstallationUpdateAvailable -> { Log.i(TAG, "Update available: ${event.version}"); true }
            is SseEvent.WorktreeReady -> { if (BuildConfig.DEBUG) Log.d(TAG, "Worktree ready: ${event.path}"); true }
            is SseEvent.WorktreeFailed -> { Log.w(TAG, "Worktree failed: ${event.path}"); true }
            else -> false
        }
    }

    fun clearForServer(sessionIds: Set<String>) {
        todos.update { it - sessionIds }
    }

    fun clearAll() {
        todos.value = emptyMap()
    }
}
```

### 5.7 创建 `data/repository/EventDispatcher.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event Dispatcher - replaces the monolithic EventReducer.
 *
 * Delegates SSE events to registered [SseEventHandler] instances via Hilt multibinding.
 * Exposes read-only StateFlows aggregated from handlers.
 */
@Singleton
class EventDispatcher @Inject constructor(
    private val handlers: Set<@JvmSuppressWildcards SseEventHandler>,
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val miscHandler: MiscEventHandler
) {
    // ============ Public State (read-only) ============

    val serverSessions: StateFlow<Map<String, Set<String>>> get() = sessionHandler.serverSessions.asStateFlow()
    val sessions: StateFlow<List<Session>> get() = sessionHandler.sessions.asStateFlow()
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> get() = sessionHandler.sessionStatuses.asStateFlow()
    val messages: StateFlow<Map<String, List<Message>>> get() = messageHandler.messages.asStateFlow()
    val parts: StateFlow<Map<String, List<Part>>> get() = messageHandler.parts.asStateFlow()
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> get() = sessionHandler.sessionDiffs.asStateFlow()
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> get() = permissionHandler.permissions.asStateFlow()
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> get() = questionHandler.questions.asStateFlow()
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> get() = miscHandler.todos.asStateFlow()
    val vcsBranch: StateFlow<String?> get() = sessionHandler.vcsBranch.asStateFlow()
    val projectInfo: StateFlow<Project?> get() = sessionHandler.projectInfo.asStateFlow()

    // ============ Event Processing ============

    /**
     * Process an SSE event by dispatching to all handlers.
     * Each handler returns true if it handled the event.
     */
    fun processEvent(event: SseEvent, serverId: String) {
        for (handler in handlers) {
            handler.handle(event, serverId)
        }
    }

    // ============ Delegated Operations ============

    fun setSessions(serverId: String, sessions: List<Session>) =
        sessionHandler.setSessions(serverId, sessions)

    fun updateSessionStatus(sessionId: String, status: SessionStatus) =
        sessionHandler.updateSessionStatus(sessionId, status)

    fun setMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.setMessages(sessionId, messages)

    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) =
        messageHandler.mergeMessages(sessionId, messages)

    fun removePermission(permissionId: String) =
        permissionHandler.removePermission(permissionId)

    fun setPermissions(sessionId: String, permissions: List<SseEvent.PermissionAsked>) =
        permissionHandler.setPermissions(sessionId, permissions)

    fun removeQuestion(questionId: String) =
        questionHandler.removeQuestion(questionId)

    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) =
        questionHandler.setQuestions(sessionId, questions)

    fun clearAll() {
        sessionHandler.clearAll()
        messageHandler.clearAll()
        permissionHandler.clearAll()
        questionHandler.clearAll()
        miscHandler.clearAll()
    }

    fun clearForServer(serverId: String) {
        val sessionIds = sessionHandler.serverSessions.value[serverId] ?: emptySet()
        sessionHandler.clearForServer(serverId)
        messageHandler.clearForServer(sessionIds)
        permissionHandler.clearForServer(sessionIds)
        questionHandler.clearForServer(sessionIds)
        miscHandler.clearForServer(sessionIds)
    }
}
```

### 5.8 创建 `data/di/HandlerModule.kt`

```kotlin
package dev.minios.ocremote.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.handler.*

@Module
@InstallIn(SingletonComponent::class)
abstract class HandlerModule {
    @Binds @IntoSet
    abstract fun bindSessionHandler(impl: SessionEventHandler): SseEventHandler

    @Binds @IntoSet
    abstract fun bindMessageHandler(impl: MessageEventHandler): SseEventHandler

    @Binds @IntoSet
    abstract fun bindPermissionHandler(impl: PermissionEventHandler): SseEventHandler

    @Binds @IntoSet
    abstract fun bindQuestionHandler(impl: QuestionEventHandler): SseEventHandler

    @Binds @IntoSet
    abstract fun bindMiscHandler(impl: MiscEventHandler): SseEventHandler
}
```

### 5.9 更新所有 EventReducer 引用为 EventDispatcher

全局搜索替换：
- `EventReducer` → `EventDispatcher`（在 import、变量声明、Hilt 注入中）
- 主要涉及文件：`OpenCodeConnectionService.kt`、所有 ViewModel

### 5.10 创建 Handler 测试

#### `data/repository/handler/SessionEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionEventHandlerTest {

    private lateinit var handler: SessionEventHandler

    @Before
    fun setup() {
        handler = SessionEventHandler()
    }

    private fun testSession(id: String) = Session(
        id = id,
        title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    @Test
    fun `handles SessionCreated`() {
        val session = testSession("s1")
        val event = SseEvent.SessionCreated(session)

        val handled = handler.handle(event, "server1")

        assertTrue(handled)
        assertEquals(listOf(session), handler.sessions.value)
        assertEquals(SessionStatus.Idle, handler.sessionStatuses.value["s1"])
    }

    @Test
    fun `handles SessionUpdated - update existing`() {
        val session = testSession("s1")
        handler.handle(SseEvent.SessionCreated(session), "server1")

        val updated = session.copy(title = "Updated")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionUpdated - upsert new`() {
        val updated = testSession("s1")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionDeleted`() {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        handler.handle(SseEvent.SessionDeleted(testSession("s1")), "server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `handles SessionStatus`() {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")

        assertEquals(SessionStatus.Busy, handler.sessionStatuses.value["s1"])
    }

    @Test
    fun `handles SessionIdle`() {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")

        handler.handle(SseEvent.SessionIdle("s1"), "server1")

        assertEquals(SessionStatus.Idle, handler.sessionStatuses.value["s1"])
    }

    @Test
    fun `handles SessionDiff`() {
        val diffs = listOf(FileDiff(file = "test.kt", status = "modified"))
        handler.handle(SseEvent.SessionDiff("s1", diffs), "server1")

        assertEquals(diffs, handler.sessionDiffs.value["s1"])
    }

    @Test
    fun `returns false for non-session events`() {
        val handled = handler.handle(SseEvent.MessageUpdated(
            info = Message.User(
                id = "m1", sessionId = "s1",
                time = TimeInfo(created = 1000L)
            )
        ), "server1")
        assertFalse(handled)
    }

    @Test
    fun `clearForServer removes only target server sessions`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server2")

        handler.clearForServer("server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `clearAll resets everything`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.VcsBranchUpdated("main"), "server1")

        handler.clearAll()

        assertTrue(handler.sessions.value.isEmpty())
        assertNull(handler.vcsBranch.value)
        assertNull(handler.projectInfo.value)
    }

    @Test
    fun `setSessions merges correctly`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        handler.setSessions("server1", listOf(testSession("s1").copy(title = "Updated"), testSession("s2")))

        assertEquals(2, handler.sessions.value.size)
        assertEquals("Updated", handler.sessions.value.find { it.id == "s1" }?.title)
    }

    @Test
    fun `trackSession registers serverId mapping`() {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        val serverSessionMap = handler.serverSessions.value
        assertEquals(setOf("s1", "s2"), serverSessionMap["server1"])
    }
}
```

#### `data/repository/handler/MessageEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageEventHandlerTest {

    private lateinit var handler: MessageEventHandler

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun testUserMessage(id: String, sessionId: String) = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = System.currentTimeMillis())
    )

    private fun testAssistantMessage(id: String, sessionId: String) = Message.Assistant(
        id = id,
        sessionId = sessionId,
        parentId = "parent-$id",
        time = TimeInfo(created = System.currentTimeMillis())
    )

    @Test
    fun `handles MessageUpdated - add new`() {
        val msg = testUserMessage("m1", "s1")
        handler.handle(SseEvent.MessageUpdated(msg), "server1")

        assertEquals(listOf(msg), handler.messages.value["s1"])
    }

    @Test
    fun `handles MessageUpdated - update existing`() {
        val msg = testUserMessage("m1", "s1")
        handler.handle(SseEvent.MessageUpdated(msg), "server1")

        val updated = msg.copy(time = TimeInfo(created = msg.time.created + 1000))
        handler.handle(SseEvent.MessageUpdated(updated), "server1")

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals(updated, handler.messages.value["s1"]!![0])
    }

    @Test
    fun `handles MessageRemoved`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m2", "s1")), "server1")

        handler.handle(SseEvent.MessageRemoved(sessionId = "s1", messageId = "m1"), "server1")

        assertEquals(1, handler.messages.value["s1"]!!.size)
        assertEquals("m2", handler.messages.value["s1"]!![0].id)
    }

    @Test
    fun `handles MessagePartUpdated - add new part`() {
        val part = Part.Text(
            id = "part1", sessionId = "s1", messageId = "m1",
            text = "Hello"
        )
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        assertEquals(listOf(part), handler.parts.value["m1"])
    }

    @Test
    fun `handles MessagePartDelta - appends text`() {
        val part = Part.Text(id = "part1", sessionId = "s1", messageId = "m1", text = "Hello")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " World"
        ), "server1")

        assertEquals("Hello World", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    @Test
    fun `handles MessagePartDelta - appends to reasoning`() {
        val part = Part.Reasoning(id = "part1", sessionId = "s1", messageId = "m1", text = "Thinking")
        handler.handle(SseEvent.MessagePartUpdated(part), "server1")

        handler.handle(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = "part1",
            field = "text", delta = " more"
        ), "server1")

        assertEquals("Thinking more", (handler.parts.value["m1"]!![0] as Part.Reasoning).text)
    }

    @Test
    fun `handles MessagePartRemoved`() {
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        val part2 = Part.Text(id = "p2", sessionId = "s1", messageId = "m1")
        handler.handle(SseEvent.MessagePartUpdated(part1), "server1")
        handler.handle(SseEvent.MessagePartUpdated(part2), "server1")

        handler.handle(SseEvent.MessagePartRemoved(sessionId = "s1", messageId = "m1", partId = "p1"), "server1")

        assertEquals(1, handler.parts.value["m1"]!!.size)
        assertEquals("p2", handler.parts.value["m1"]!![0].id)
    }

    @Test
    fun `returns false for non-message events`() {
        val handled = handler.handle(SseEvent.SessionCreated(
            testSession("s1")
        ), "server1")
        assertFalse(handled)
    }

    @Test
    fun `clearForServer removes messages for sessions`() {
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m1", "s1")), "server1")
        handler.handle(SseEvent.MessageUpdated(testUserMessage("m2", "s2")), "server1")

        handler.clearForServer(setOf("s1"))

        assertNull(handler.messages.value["s1"])
        assertNotNull(handler.messages.value["s2"])
    }

    @Test
    fun `setMessages replaces completely`() {
        val msg1 = testUserMessage("m1", "s1")
        val msg2 = testUserMessage("m2", "s1")
        val part1 = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")

        handler.setMessages("s1", listOf(
            MessageWithParts(info = msg1, parts = listOf(part1)),
            MessageWithParts(info = msg2, parts = emptyList())
        ))

        assertEquals(2, handler.messages.value["s1"]!!.size)
        assertEquals(listOf(part1), handler.parts.value["m1"])
    }

    @Test
    fun `mergeMessages preserves SSE-fresh parts`() {
        // SSE already set a part
        val existingPart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from SSE")
        handler.handle(SseEvent.MessagePartUpdated(existingPart), "server1")

        // REST load-older returns same message with stale part
        val msg = testUserMessage("m1", "s1")
        val stalePart = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "from REST")
        handler.mergeMessages("s1", listOf(MessageWithParts(msg, listOf(stalePart))))

        // SSE part should win
        assertEquals("from SSE", (handler.parts.value["m1"]!![0] as Part.Text).text)
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test", time = Session.Time(created = 1000L, updated = 2000L)
    )
}
```

#### `data/repository/handler/PermissionEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolRef
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PermissionEventHandlerTest {

    private lateinit var handler: PermissionEventHandler

    @Before
    fun setup() {
        handler = PermissionEventHandler()
    }

    private fun testPermission(id: String, sessionId: String) = SseEvent.PermissionAsked(
        id = id, sessionId = sessionId, permission = "bash"
    )

    @Test
    fun `handles PermissionAsked`() {
        val perm = testPermission("p1", "s1")
        assertTrue(handler.handle(perm, "server1"))
        assertEquals(listOf(perm), handler.permissions.value["s1"])
    }

    @Test
    fun `handles PermissionReplied`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.handle(testPermission("p2", "s1"), "server1")

        handler.handle(SseEvent.PermissionReplied(sessionId = "s1", requestId = "p1"), "server1")

        assertEquals(1, handler.permissions.value["s1"]!!.size)
        assertEquals("p2", handler.permissions.value["s1"]!![0].id)
    }

    @Test
    fun `removePermission removes across all sessions`() {
        handler.handle(testPermission("target", "s1"), "server1")
        handler.handle(testPermission("other", "s1"), "server1")
        handler.handle(testPermission("target", "s2"), "server1")

        handler.removePermission("target")

        assertEquals(listOf(handler.permissions.value["s1"]!![0]), handler.permissions.value["s1"])
        assertTrue(handler.permissions.value["s2"]!!.isEmpty())
    }

    @Test
    fun `setPermissions replaces existing`() {
        handler.handle(testPermission("old", "s1"), "server1")
        val newPerm = testPermission("new", "s1")

        handler.setPermissions("s1", listOf(newPerm))

        assertEquals(listOf(newPerm), handler.permissions.value["s1"])
    }

    @Test
    fun `returns false for non-permission events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }
}
```

#### `data/repository/handler/QuestionEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuestionEventHandlerTest {

    private lateinit var handler: QuestionEventHandler

    @Before
    fun setup() {
        handler = QuestionEventHandler()
    }

    private fun testQuestion(id: String, sessionId: String) = SseEvent.QuestionAsked(
        id = id, sessionId = sessionId,
        questions = listOf(SseEvent.QuestionAsked.Question(
            header = "Q", question = "Yes or No?",
            options = listOf(SseEvent.QuestionAsked.Option("Yes", "Proceed"))
        ))
    )

    @Test
    fun `handles QuestionAsked`() {
        val q = testQuestion("q1", "s1")
        assertTrue(handler.handle(q, "server1"))
        assertEquals(listOf(q), handler.questions.value["s1"])
    }

    @Test
    fun `handles QuestionReplied`() {
        handler.handle(testQuestion("q1", "s1"), "server1")

        handler.handle(SseEvent.QuestionReplied(sessionId = "s1", requestId = "q1"), "server1")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `handles QuestionRejected`() {
        handler.handle(testQuestion("q1", "s1"), "server1")

        handler.handle(SseEvent.QuestionRejected(sessionId = "s1", requestId = "q1"), "server1")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `removeQuestion removes across all sessions`() {
        handler.handle(testQuestion("target", "s1"), "server1")
        handler.handle(testQuestion("target", "s2"), "server1")

        handler.removeQuestion("target")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
        assertTrue(handler.questions.value["s2"]!!.isEmpty())
    }

    @Test
    fun `returns false for non-question events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }
}
```

#### `data/repository/handler/MiscEventHandlerTest.kt`

```kotlin
package dev.minios.ocremote.data.repository.handler

import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MiscEventHandlerTest {

    private lateinit var handler: MiscEventHandler

    @Before
    fun setup() {
        handler = MiscEventHandler()
    }

    @Test
    fun `handles TodoUpdated`() {
        val todos = listOf(SseEvent.TodoUpdated.Todo("Task 1", "pending", "high"))
        assertTrue(handler.handle(SseEvent.TodoUpdated("s1", todos), "server1"))
        assertEquals(todos, handler.todos.value["s1"])
    }

    @Test
    fun `handles PtyCreated`() {
        assertTrue(handler.handle(SseEvent.PtyCreated(id = "pty_1"), "server1"))
    }

    @Test
    fun `handles CommandExecuted`() {
        assertTrue(handler.handle(
            SseEvent.CommandExecuted(name = "build", sessionId = "s1"), "server1"
        ))
    }

    @Test
    fun `returns false for unhandled events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
        assertFalse(handler.handle(SseEvent.MessageUpdated(
            info = dev.minios.ocremote.domain.model.Message.User(
                id = "m1", sessionId = "s1",
                time = dev.minios.ocremote.domain.model.TimeInfo(created = 1000L)
            )
        ), "server1"))
    }

    @Test
    fun `clearForServer removes todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearForServer(setOf("s1"))

        assertNull(handler.todos.value["s1"])
    }

    @Test
    fun `clearAll resets todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearAll()

        assertTrue(handler.todos.value.isEmpty())
    }
}
```

### 5.11 创建 `data/repository/EventDispatcherTest.kt`（替代旧 EventReducerTest）

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventDispatcherTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var messageHandler: MessageEventHandler
    private lateinit var permissionHandler: PermissionEventHandler
    private lateinit var questionHandler: QuestionEventHandler
    private lateinit var miscHandler: MiscEventHandler

    @Before
    fun setup() {
        sessionHandler = SessionEventHandler()
        messageHandler = MessageEventHandler()
        permissionHandler = PermissionEventHandler()
        questionHandler = QuestionEventHandler()
        miscHandler = MiscEventHandler()

        val allHandlers: Set<SseEventHandler> = setOf(
            sessionHandler, messageHandler, permissionHandler, questionHandler, miscHandler
        )

        dispatcher = EventDispatcher(
            handlers = allHandlers,
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler
        )
    }

    @Test
    fun `processEvent dispatches to correct handler`() {
        val session = Session(id = "s1", title = "Test", time = Session.Time(1000L, 2000L))
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        assertEquals(listOf(session), dispatcher.sessions.value)

        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server1")
        assertEquals(listOf(msg), dispatcher.messages.value["s1"])

        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        dispatcher.processEvent(perm, "server1")
        assertEquals(listOf(perm), dispatcher.permissions.value["s1"])
    }

    @Test
    fun `clearAll resets all state`() {
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s1", time = Session.Time(1000L, 2000L))
        ), "server1")
        dispatcher.processEvent(SseEvent.VcsBranchUpdated("main"), "server1")

        dispatcher.clearAll()

        assertTrue(dispatcher.sessions.value.isEmpty())
        assertNull(dispatcher.vcsBranch.value)
    }

    @Test
    fun `clearForServer removes only target server data`() {
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s1", time = Session.Time(1000L, 2000L))
        ), "server1")
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s2", time = Session.Time(1000L, 2000L))
        ), "server2")

        dispatcher.clearForServer("server1")

        assertEquals(1, dispatcher.sessions.value.size)
        assertEquals("s2", dispatcher.sessions.value[0].id)
    }

    @Test
    fun `delegated setMessages works`() {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "hi")

        dispatcher.setMessages("s1", listOf(MessageWithParts(msg, listOf(part))))

        assertEquals(listOf(msg), dispatcher.messages.value["s1"])
        assertEquals(listOf(part), dispatcher.parts.value["m1"])
    }

    @Test
    fun `delegated removePermission works`() {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        dispatcher.processEvent(perm, "server1")

        dispatcher.removePermission("p1")

        assertTrue(dispatcher.permissions.value["s1"]!!.isEmpty())
    }
}
```

### 5.12 删除旧 `EventReducer.kt`

在确认所有测试通过后，删除 `data/repository/EventReducer.kt`。

### 5.13 编译 + 测试验证

```bash
./gradlew assembleDebug
./gradlew test
```

---

## Task 6: Service 拆分

**Goal:** 从 OpenCodeConnectionService.kt（906行）提取 SseConnectionManager + AppNotificationManager。

### 6.1 创建 `service/SseConnectionManager.kt`

```kotlin
package dev.minios.ocremote.service

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseConnManager"
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L
private const val RECONNECT_BACKOFF_FACTOR = 2.0

/**
 * Per-server connection state.
 */
data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false
)

/**
 * Manages SSE connections to multiple servers.
 * Handles connection lifecycle, auto-reconnect, and session pre-loading.
 */
@Singleton
class SseConnectionManager @Inject constructor(
    private val api: OpenCodeApi,
    private val sseClient: SseClient,
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val connections = mutableMapOf<String, ServerConnectionState>()

    val connectedServerIds: StateFlow<Set<String>>
        get() = _connectedServerIds.asStateFlow()
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())

    val connectingServerIds: StateFlow<Set<String>>
        get() = _connectingServerIds.asStateFlow()
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())

    fun startConnection(server: ServerConfig): Job {
        val conn = ServerConnection.from(server.url, server.username, server.password)
        val job = startSseConnection(server, conn)

        connections[server.id] = ServerConnectionState(
            config = server, conn = conn, sseJob = job, isConnected = false
        )
        _connectingServerIds.update { it + server.id }

        return job
    }

    fun stopConnection(serverId: String) {
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        eventDispatcher.clearForServer(serverId)
    }

    fun stopAllConnections() {
        for ((_, state) in connections) { state.sseJob.cancel() }
        val serverIds = connections.keys.toList()
        connections.clear()
        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()
        for (serverId in serverIds) {
            eventDispatcher.clearForServer(serverId)
        }
    }

    fun isConnected(serverId: String): Boolean {
        return connections[serverId]?.sseJob?.isActive == true
    }

    fun getConnection(serverId: String): ServerConnection? {
        return connections[serverId]?.conn
    }

    private fun startSseConnection(server: ServerConfig, conn: ServerConnection): Job {
        return scope.launch {
            var attempt = 0

            while (isActive) {
                attempt++
                Log.i(TAG, "[${server.displayName}] SSE attempt #$attempt")

                // Pre-load sessions
                try {
                    preLoadSessions(server, conn)
                } catch (e: Exception) {
                    Log.w(TAG, "[${server.displayName}] Pre-load failed: ${e.message}")
                }

                try {
                    sseClient.connectToGlobalEvents(conn)
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            throw error
                        }
                        .collect { event ->
                            if (connections[server.id]?.isConnected != true) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                            }
                            // Event processing is done by the caller (service)
                            // We just emit via a callback or flow
                        }

                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE failed: ${e.message}")
                    updateServerConnected(server.id, false)
                }

                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    /**
     * Connect to SSE and return the event flow for the caller to process.
     * This is the primary method used by the service.
     */
    fun connectAndStream(server: ServerConfig, conn: ServerConnection): kotlinx.coroutines.flow.Flow<SseEvent> {
        return sseClient.connectToGlobalEvents(conn)
    }

    private suspend fun preLoadSessions(server: ServerConfig, conn: ServerConnection) {
        val projects = api.listProjects(conn)
        if (projects.isEmpty()) {
            val sessions = api.listSessions(conn)
            eventDispatcher.setSessions(server.id, sessions)
            Log.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
        } else {
            var total = 0
            for (project in projects) {
                try {
                    val sessions = api.listSessions(conn, directory = project.worktree)
                    eventDispatcher.setSessions(server.id, sessions)
                    total += sessions.size
                } catch (e: Exception) {
                    Log.w(TAG, "[${server.displayName}] Failed for ${project.displayName}: ${e.message}")
                }
            }
            Log.i(TAG, "[${server.displayName}] Pre-loaded $total sessions across ${projects.size} projects")
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        val state = connections[serverId] ?: return
        connections[serverId] = state.copy(isConnected = connected)
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
        }
    }

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }

    fun cancelScope() {
        scope.cancel()
    }
}
```

### 6.2 创建 `service/AppNotificationManager.kt`

```kotlin
package dev.minios.ocremote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ServerConfig
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val NOTIFICATION_CHANNEL_ID = "opencode_connection"
private const val NOTIFICATION_CHANNEL_TASKS_ID = "opencode_tasks"
private const val NOTIFICATION_CHANNEL_TASKS_SILENT_ID = "opencode_tasks_silent"
private const val NOTIFICATION_CHANNEL_PERMISSIONS_ID = "opencode_permissions"
private const val PERSISTENT_NOTIFICATION_ID = 1001

/**
 * Manages all notification logic for the connection service.
 * Extracted from OpenCodeConnectionService for separation of concerns.
 */
@Singleton
class AppNotificationManager @Inject constructor(
    private val eventDispatcher: EventDispatcher,
    private val settingsRepository: SettingsRepository
) {
    private val TAG = "AppNotificationMgr"

    /** Dedup response-ready notifications per session by last assistant message ID. */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()

    fun createNotificationChannels(notificationManager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val connectionChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_connection_desc)
                setShowBadge(false)
            }

            val tasksChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_ID,
                context.getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_tasks_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            val tasksSilentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_SILENT_ID,
                context.getString(R.string.notification_channel_tasks_silent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_tasks_silent_desc)
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            val permissionsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PERMISSIONS_ID,
                context.getString(R.string.notification_channel_permissions),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_permissions_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannels(
                listOf(connectionChannel, tasksChannel, tasksSilentChannel, permissionsChannel)
            )
        }
    }

    fun createPersistentNotification(
        context: Context,
        connections: Map<String, ServerConnectionState>,
        isLocalServer: (ServerConfig) -> Boolean
    ): Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectAllIntent = Intent(context, OpenCodeConnectionService::class.java).apply {
            action = OpenCodeConnectionService.ACTION_DISCONNECT_ALL
        }
        val disconnectAllPendingIntent = PendingIntent.getService(
            context, 1, disconnectAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val visibleConnections = connections.values.filterNot { isLocalServer(it.config) }
        val serverCount = visibleConnections.size
        val connectedCount = visibleConnections.count { it.isConnected }

        val title = when {
            serverCount == 0 -> context.getString(R.string.app_name)
            serverCount == 1 -> {
                val server = visibleConnections.first()
                if (server.isConnected) context.getString(R.string.notification_connected, server.config.displayName)
                else context.getString(R.string.notification_connecting, server.config.displayName)
            }
            else -> context.getString(R.string.notification_connected_count, connectedCount, serverCount)
        }

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (serverCount > 0) {
            builder.addAction(
                R.mipmap.ic_launcher,
                context.getString(R.string.notification_disconnect_all),
                disconnectAllPendingIntent
            )
        }

        if (serverCount > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(context.getString(R.string.notification_inbox_title, connectedCount, serverCount))
            for (state in visibleConnections) {
                val status = if (state.isConnected) context.getString(R.string.notification_status_connected)
                             else context.getString(R.string.notification_status_connecting)
                inboxStyle.addLine("${state.config.displayName}: $status")
            }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    fun showTaskCompleteNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String
    ) {
        // Extract session info
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        val body = session?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notification_new_session)

        val pendingIntent = createSessionPendingIntent(context, server, sessionId, sessionId.hashCode())
        val silent = settingsRepository.silentNotifications.value
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID

        val notifId = eventNotificationId(server.id, sessionId, 0)
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notification_response_ready))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup("server_${server.id}")

        if (!silent) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500))
        }

        notificationManager.notify(notifId, builder.build())
        showServerGroupSummary(context, notificationManager, server)
    }

    fun showPermissionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        permission: String
    ) {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        val displayTitle = session?.title ?: context.getString(R.string.notification_new_session)
        val projectName = getProjectName(session?.directory)
        val body = if (projectName != null) {
            context.getString(R.string.notification_needs_permission_project, displayTitle, projectName)
        } else {
            context.getString(R.string.notification_needs_permission, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(context.getString(R.string.notification_permission_required))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    fun showQuestionNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String,
        questionText: String
    ) {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        val displayTitle = session?.title ?: context.getString(R.string.notification_new_session)
        val projectName = getProjectName(session?.directory)
        val body = if (projectName != null) {
            context.getString(R.string.notification_has_question_project, displayTitle, projectName)
        } else {
            context.getString(R.string.notification_has_question, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 2000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(context.getString(R.string.notification_question))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    fun showErrorNotification(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig,
        sessionId: String?,
        error: String
    ) {
        val body = if (sessionId != null) {
            val session = eventDispatcher.sessions.value.find { it.id == sessionId }
            session?.title ?: error.ifBlank { context.getString(R.string.error_unknown) }
        } else {
            error.ifBlank { context.getString(R.string.error_unknown) }
        }

        val notifId = eventNotificationId(server.id, sessionId ?: "error", 3000)
        val pendingIntent = createSessionPendingIntent(context, server, sessionId, notifId)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_ID)
            .setContentTitle(context.getString(R.string.notification_session_error))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup("server_${server.id}")
            .build()

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(context, notificationManager, server)
    }

    /**
     * Check if a session is a child session (should not trigger notifications).
     */
    fun isChildSession(sessionId: String): Boolean {
        val session = eventDispatcher.sessions.value.find { it.id == sessionId }
        return session?.parentId != null
    }

    /**
     * Check if there's a new notifiable assistant message for the session.
     * Returns the message ID if new, null otherwise.
     */
    fun checkNewAssistantMessage(sessionId: String): String? {
        val sessionMessages = eventDispatcher.messages.value[sessionId] ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        val parts = eventDispatcher.parts.value[latestAssistant.id] ?: return null
        val hasTextOutput = parts.any { part ->
            when (part) {
                is Part.Text -> part.text.isNotBlank()
                is Part.Reasoning -> part.text.isNotBlank()
                else -> false
            }
        }
        if (!hasTextOutput) return null

        val previousNotified = lastNotifiedAssistantMessageBySession[sessionId]
        if (previousNotified == latestAssistant.id) return null

        lastNotifiedAssistantMessageBySession[sessionId] = latestAssistant.id
        return latestAssistant.id
    }

    // ============ Private Helpers ============

    private fun showServerGroupSummary(
        context: Context,
        notificationManager: NotificationManager,
        server: ServerConfig
    ) {
        val summaryId = "server_summary_${server.id}".hashCode()
        val summary = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_TASKS_SILENT_ID)
            .setContentTitle(server.displayName)
            .setContentText(context.getString(R.string.notification_group_summary))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup("server_${server.id}")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(summaryId, summary)
    }

    private fun createSessionPendingIntent(
        context: Context,
        server: ServerConfig,
        sessionId: String?,
        requestCode: Int
    ): PendingIntent {
        val sessionPath = sessionId?.let {
            val session = eventDispatcher.sessions.value.find { s -> s.id == sessionId }
            session?.let { s ->
                val encodedDir = base64UrlEncode(s.directory)
                "/$encodedDir/session/${s.id}"
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = OpenCodeConnectionService.ACTION_OPEN_SESSION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_URL, server.url)
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_USERNAME, server.username)
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_PASSWORD, server.password ?: "")
            putExtra(OpenCodeConnectionService.EXTRA_SERVER_NAME, server.displayName)
            sessionPath?.let { putExtra(OpenCodeConnectionService.EXTRA_SESSION_PATH, it) }
            sessionId?.let { putExtra(OpenCodeConnectionService.EXTRA_SESSION_ID, it) }
        }

        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun base64UrlEncode(value: String): String {
        val encoded = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return encoded.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun getProjectName(directory: String?): String? {
        if (directory.isNullOrBlank()) return null
        return directory.trimEnd('/').substringAfterLast('/')
    }

    private fun eventNotificationId(serverId: String, sessionId: String, typeOffset: Int): Int {
        return (serverId + sessionId).hashCode() + typeOffset
    }

    companion object {
        const val PERSISTENT_NOTIFICATION_ID = 1001
    }
}
```

### 6.3 瘦身 OpenCodeConnectionService.kt

服务瘦身后保留：
- Service 生命周期（onCreate/onStartCommand/onBind/onDestroy）
- WakeLock 管理
- Intent 处理（connect/disconnect）
- 事件通知路由（从 processEvent 调用 AppNotificationManager）
- Foreground notification 管理
- 委托 SseConnectionManager 处理 SSE 生命周期

```kotlin
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    @Inject lateinit var connectionManager: SseConnectionManager
    @Inject lateinit var notificationManager: AppNotificationManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var serverRepository: ServerRepository

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var systemNotificationManager: NotificationManager
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationWatchdogJob: Job? = null

    // ... lifecycle methods delegate to connectionManager and notificationManager
    // ... processEvent now dispatches events AND routes to notificationManager

    // connect() → connectionManager.startConnection() + notificationManager.createPersistentNotification()
    // disconnect() → connectionManager.stopConnection() + notificationManager.updatePersistentNotification()
    // processEvent() → eventDispatcher.processEvent() + notificationManager.show*Notification()

    // ~200 lines remaining (from 906)
}
```

### 6.4 编译验证

```bash
./gradlew assembleDebug
```

---

## Task 7: 实现 Repository Impl

**Goal:** 创建 ChatRepositoryImpl 和 SessionRepositoryImpl，重构 ServerRepository 和 SettingsRepository 为 Impl 模式。

### 7.1 创建 `data/repository/ChatRepositoryImpl.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.dto.common.ModelSelection
import dev.minios.ocremote.data.dto.request.PromptPart
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ChatRepository.
 * Bridges domain interface with EventDispatcher (state) and OpenCodeApi (network).
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val eventDispatcher: EventDispatcher
) {
    // ============ State Observations ============

    fun getMessages(sessionId: String): Flow<List<Message>> =
        eventDispatcher.messages.map { it[sessionId] ?: emptyList() }

    fun getParts(messageId: String): Flow<List<Part>> =
        eventDispatcher.parts.map { it[messageId] ?: emptyList() }

    fun getDiffs(sessionId: String): Flow<List<FileDiff>> =
        eventDispatcher.sessionDiffs.map { it[sessionId] ?: emptyList() }

    // ============ Network Operations ============

    suspend fun sendMessage(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ) {
        api.promptAsync(conn, sessionId, parts, model, agent, variant, directory)
    }

    suspend fun loadMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null
    ): List<MessageWithParts> {
        val messages = api.listMessages(conn, sessionId, limit)
        eventDispatcher.setMessages(sessionId, messages)
        return messages
    }

    suspend fun loadOlderMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int = 50
    ): List<MessageWithParts> {
        val messages = api.listMessages(conn, sessionId, limit)
        eventDispatcher.mergeMessages(sessionId, messages)
        return messages
    }

    suspend fun getMessage(
        conn: ServerConnection,
        sessionId: String,
        messageId: String
    ): MessageWithParts {
        return api.getMessage(conn, sessionId, messageId)
    }

    suspend fun exportSessionRaw(
        conn: ServerConnection,
        sessionId: String
    ): String {
        return api.getSessionRaw(conn, sessionId)
    }

    suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        api.exportSessionToStream(conn, sessionId, outputStream, onProgress)
    }
}
```

### 7.2 创建 `data/repository/SessionRepositoryImpl.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SessionRepository.
 * Bridges domain interface with EventDispatcher (state) and OpenCodeApi (network).
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val eventDispatcher: EventDispatcher
) {
    // ============ State Observations ============

    fun getAllSessions(): Flow<List<Session>> = eventDispatcher.sessions

    fun getSessionStatus(sessionId: String): Flow<SessionStatus> =
        eventDispatcher.sessionStatuses.map { it[sessionId] ?: SessionStatus.Idle }

    fun getSessionDiffs(sessionId: String): Flow<List<FileDiff>> =
        eventDispatcher.sessionDiffs.map { it[sessionId] ?: emptyList() }

    fun getSession(sessionId: String): Session? =
        eventDispatcher.sessions.value.find { it.id == sessionId }

    // ============ Network Operations ============

    suspend fun createSession(
        conn: ServerConnection,
        title: String? = null,
        parentId: String? = null,
        directory: String? = null
    ): Session {
        return api.createSession(conn, title, parentId, directory)
    }

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        return api.deleteSession(conn, sessionId)
    }

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return api.updateSession(conn, sessionId, title)
    }

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean {
        val result = api.abortSession(conn, sessionId, directory)
        if (result) {
            eventDispatcher.updateSessionStatus(sessionId, SessionStatus.Idle)
        }
        return result
    }

    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return api.shareSession(conn, sessionId)
    }

    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return api.unshareSession(conn, sessionId)
    }

    suspend fun summarizeSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean {
        return api.summarizeSession(conn, sessionId, providerId, modelId)
    }

    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        return api.revertSession(conn, sessionId, messageId)
    }

    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return api.unrevertSession(conn, sessionId)
    }

    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session {
        return api.forkSession(conn, sessionId, messageId)
    }

    suspend fun getSessionChildren(conn: ServerConnection, sessionId: String): List<Session> {
        return api.listSessionChildren(conn, sessionId)
    }

    suspend fun getSessionTodos(conn: ServerConnection, sessionId: String): List<dev.minios.ocremote.data.dto.response.TodoItem> {
        return api.getSessionTodos(conn, sessionId)
    }

    suspend fun refreshSessions(conn: ServerConnection, directory: String? = null): List<Session> {
        val sessions = api.listSessions(conn, directory)
        // Note: serverId is needed for tracking, caller should use eventDispatcher.setSessions directly
        return sessions
    }
}
```

### 7.3 重构 ServerRepository → ServerRepositoryImpl

现有的 `ServerRepository` 已经是完整实现。重命名为 `ServerRepositoryImpl` 并让类实现 domain 接口（如果 Phase 1 定义了 `domain.repository.ServerRepository` 接口）。

```kotlin
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: OpenCodeApi,
    private val json: Json
) : domain.repository.ServerRepository {
    // ... 现有代码不变，只需更新类名和 implements 接口
}
```

### 7.4 重构 SettingsRepository → SettingsRepositoryImpl

```kotlin
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) : domain.repository.SettingsRepository {
    // ... 现有代码不变
    // companion object 中的 getStoredLanguage 保持不变
}
```

### 7.5 更新 Hilt Module 绑定

在 DI Module 中绑定 Impl 到接口：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): domain.repository.ServerRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): domain.repository.SettingsRepository
}
```

### 7.6 创建 Repository 测试

#### `data/repository/ChatRepositoryImplTest.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatRepositoryImplTest {

    private lateinit var repo: ChatRepositoryImpl
    private lateinit var api: OpenCodeApi
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var messageHandler: MessageEventHandler

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        sessionHandler = SessionEventHandler()
        messageHandler = MessageEventHandler()
        val permissionHandler = PermissionEventHandler()
        val questionHandler = QuestionEventHandler()
        val miscHandler = MiscEventHandler()

        val allHandlers: Set<SseEventHandler> = setOf(
            sessionHandler, messageHandler, permissionHandler, questionHandler, miscHandler
        )
        eventDispatcher = EventDispatcher(
            handlers = allHandlers,
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler
        )
        repo = ChatRepositoryImpl(api, eventDispatcher)
    }

    @Test
    fun `getMessages returns flow from dispatcher`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        messageHandler.setMessages("s1", listOf(MessageWithParts(msg, emptyList())))

        val messages = repo.getMessages("s1").first()
        assertEquals(1, messages.size)
        assertEquals("m1", messages[0].id)
    }

    @Test
    fun `getMessages returns empty for unknown session`() = runTest {
        val messages = repo.getMessages("unknown").first()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `getParts returns flow from dispatcher`() = runTest {
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "hello")
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        messageHandler.setMessages("s1", listOf(MessageWithParts(msg, listOf(part))))

        val parts = repo.getParts("m1").first()
        assertEquals(1, parts.size)
        assertEquals("hello", (parts[0] as Part.Text).text)
    }

    @Test
    fun `loadMessages calls API and updates dispatcher`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        coEvery { api.listMessages(any(), "s1", any()) } returns listOf(
            MessageWithParts(msg, listOf(part))
        )

        val conn = dev.minios.ocremote.data.api.ServerConnection("http://localhost:4096", null)
        val result = repo.loadMessages(conn, "s1")

        assertEquals(1, result.size)
        assertEquals("m1", result[0].info.id)
    }
}
```

#### `data/repository/SessionRepositoryImplTest.kt`

```kotlin
package dev.minios.ocremote.data.repository

import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.handler.*
import dev.minios.ocremote.domain.model.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionRepositoryImplTest {

    private lateinit var repo: SessionRepositoryImpl
    private lateinit var api: OpenCodeApi
    private lateinit var eventDispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        sessionHandler = SessionEventHandler()
        val messageHandler = MessageEventHandler()
        val permissionHandler = PermissionEventHandler()
        val questionHandler = QuestionEventHandler()
        val miscHandler = MiscEventHandler()

        eventDispatcher = EventDispatcher(
            handlers = setOf(sessionHandler, messageHandler, permissionHandler, questionHandler, miscHandler),
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler
        )
        repo = SessionRepositoryImpl(api, eventDispatcher)
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    @Test
    fun `getAllSessions returns dispatcher state`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1"), testSession("s2")))

        val sessions = repo.getAllSessions().first()
        assertEquals(2, sessions.size)
    }

    @Test
    fun `getSessionStatus returns current status`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1")))

        val status = repo.getSessionStatus("s1").first()
        assertEquals(SessionStatus.Idle, status)
    }

    @Test
    fun `getSession finds by id`() {
        sessionHandler.setSessions("server1", listOf(testSession("s1"), testSession("s2")))

        val session = repo.getSession("s2")
        assertNotNull(session)
        assertEquals("s2", session!!.id)
    }

    @Test
    fun `getSession returns null for unknown`() {
        val session = repo.getSession("unknown")
        assertNull(session)
    }

    @Test
    fun `createSession calls API`() = runTest {
        val newSession = testSession("new")
        coEvery { api.createSession(any(), "Title", any(), any()) } returns newSession

        val conn = ServerConnection("http://localhost:4096", null)
        val result = repo.createSession(conn, "Title")

        assertEquals("new", result.id)
    }

    @Test
    fun `abortSession updates status on success`() = runTest {
        sessionHandler.setSessions("server1", listOf(testSession("s1")))
        sessionHandler.updateSessionStatus("s1", SessionStatus.Busy)
        coEvery { api.abortSession(any(), "s1", any()) } returns true

        val conn = ServerConnection("http://localhost:4096", null)
        val result = repo.abortSession(conn, "s1")

        assertTrue(result)
        assertEquals(SessionStatus.Idle, eventDispatcher.sessionStatuses.value["s1"])
    }
}
```

### 7.7 编译 + 测试验证

```bash
./gradlew assembleDebug
./gradlew test
```

---

## Task 8: 全量回归

**Goal:** 确保所有改动编译通过、所有测试通过、功能无退化。

### 8.1 完整编译

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

### 8.2 全量单元测试

```bash
./gradlew test
```

### 8.3 检查测试覆盖率

确认以下测试文件存在并全部通过：
- `PermissionMapperTest` — 4 tests
- `QuestionMapperTest` — 4 tests
- `ConfigMapperTest` — 3 tests
- `ProviderMapperTest` — 3 tests
- `SessionEventHandlerTest` — 12 tests
- `MessageEventHandlerTest` — 11 tests
- `PermissionEventHandlerTest` — 5 tests
- `QuestionEventHandlerTest` — 5 tests
- `MiscEventHandlerTest` — 6 tests
- `EventDispatcherTest` — 5 tests
- `ChatRepositoryImplTest` — 4 tests
- `SessionRepositoryImplTest` — 6 tests

### 8.4 静态分析

```bash
./gradlew lintDebug
```

### 8.5 验证 Hilt 依赖图

确认无运行时 DI 错误：
- `HandlerModule` 正确绑定 5 个 Handler
- `RepositoryModule` 正确绑定 Impl 到接口
- `EventDispatcher` 正确注入所有依赖

### 8.6 文件清理清单

确认以下文件已删除：
- [ ] `data/repository/EventReducer.kt` — 替换为 EventDispatcher + Handlers
- [ ] `data/api/OpenCodeApi.kt` 底部的 DTO 定义 — 已移至 `data/dto/`
- [ ] `app/src/test/.../EventReducerTest.kt` — 替换为 Handler + Dispatcher 测试

确认以下新目录/文件已创建：
- [ ] `data/dto/request/` — 5 个文件
- [ ] `data/dto/response/` — 7 个文件
- [ ] `data/dto/common/` — 1 个文件
- [ ] `data/api/ServerConnection.kt` — 1 个文件
- [ ] `data/mapper/` — 4 个文件
- [ ] `data/repository/handler/` — 6 个文件
- [ ] `data/repository/EventDispatcher.kt`
- [ ] `data/repository/ChatRepositoryImpl.kt`
- [ ] `data/repository/SessionRepositoryImpl.kt`
- [ ] `data/di/HandlerModule.kt`
- [ ] `service/SseConnectionManager.kt`
- [ ] `service/AppNotificationManager.kt`
- [ ] 12 个测试文件

---

## Dependency Graph

```
Phase 3 任务依赖关系（严格顺序）:

Task 1: DTO 提取
  ↓
Task 2: Serializer（当前无操作）
  ↓
Task 3: Mapper + 测试
  ↓
Task 4: 瘦身 OpenCodeApi.kt（依赖 Task 1 的 DTO 文件）
  ↓
Task 5: EventDispatcher + Handler（独立于 Task 1-4）
  ↓
Task 6: Service 拆分（依赖 Task 5 的 EventDispatcher）
  ↓
Task 7: Repository Impl（依赖 Task 5 的 EventDispatcher + Task 1-4 的 API 层）
  ↓
Task 8: 全量回归
```

**注意：** Task 5 可以与 Task 1-4 并行开发（它不依赖 DTO 结构变更），但必须在 Task 6 之前完成。

---

## Risk Mitigation

| 风险 | 缓解措施 |
|------|----------|
| DTO 移动后 import 遗漏 | 全局搜索 `data.api.` 包下的 DTO 类名引用 |
| EventReducer 删除后运行时崩溃 | 先保留 EventReducer 作为 EventDispatcher 的代理，确认全绿后删除 |
| Handler 多绑定顺序不确定 | EventDispatcher 不依赖 handler 顺序，每个 handler 独立处理 |
| Service 拆分破坏 Android 生命周期 | SseConnectionManager 标记 `@Singleton`，由 Service 管理生命周期 |
| SettingsRepository 重命名破坏静态方法调用 | `getStoredLanguage` 是 companion object 方法，调用方通过 `SettingsRepository.getStoredLanguage()` 调用，需要全局替换为 `SettingsRepositoryImpl.getStoredLanguage()` 或保留原类名 |
