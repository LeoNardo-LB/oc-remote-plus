# V2 SSE 复用 V1 连接 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 V2 SSE 重复连接，让 V2 EventParser 消费 V1 已建立的 SSE 连接中的原始 JSON 数据，同时保留 V2 解析逻辑为未来 API 做准备。

**Architecture:** 在 V1 `SseClient.connectToGlobalEvents()` 中新增一个 `SharedFlow<String>` 并行 emit 原始 JSON 字符串。V2 `SseConnectionManager` 不再自己建立 HTTP 连接，而是接收这个 `Flow<String>` 作为数据源。V2 SDK 的消费者（ChatViewModel）从 V1 SSE 连接获取 raw flow 并传给 V2。这样只有一个 HTTP 连接，两个解析器各自独立工作。

**Tech Stack:** Kotlin, kotlinx.coroutines (SharedFlow), Ktor SSE, kotlinx.serialization

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `data/api/SseClient.kt` | 修改 | 新增 `rawSseEvents: MutableSharedFlow<String>`，在 `connectToGlobalEvents()` 中 emit 原始 JSON |
| `data/v2/SseConnectionManager.kt` | 修改 | 不再自己建立 HTTP 连接，改为接收 `Flow<String>` 数据源 |
| `data/v2/OpenCodeV2Sdk.kt` | 修改 | `OpenCodeV2SdkImpl` 不再创建 `SseConnectionManager`，改为接收 `Flow<String>` |
| `service/SseConnectionManager.kt` | 修改 | 将 `SseClient` 的 raw flow 暴露给外部 |
| `ui/screens/chat/ChatViewModel.kt` | 修改 | 将 V1 的 raw events flow 传给 V2 SDK |

---

### Task 1: SseClient — 新增 rawSseEvents SharedFlow 并在连接中 emit

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt`

**原理：** `SseClient` 已经在 `connectToGlobalEvents()` 的 L138 拿到了原始 JSON 字符串 `data`。我们只需要在 emit 解析后的 `SseEvent` 的同时，也 emit 原始 JSON 字符串到一个 `SharedFlow`。

- [ ] **Step 1: 在 SseClient 类中添加 rawSseEvents SharedFlow**

在 `SseClient` 类的 `parsers` 字段后面（约 L79 后），添加：

```kotlin
/**
 * Raw SSE JSON strings from the active global event connection.
 * V2 pipeline consumes this to avoid a duplicate HTTP connection.
 * Emitted before V1 parsing — consumers see every non-heartbeat data frame.
 */
val rawSseEvents: MutableSharedFlow<String> = MutableSharedFlow(
    replay = 0,
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

需要在文件顶部添加 import：

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
```

- [ ] **Step 2: 在 connectToGlobalEvents() 中 emit 原始 JSON**

在 `connectToGlobalEvents()` 方法中，找到 L139 `if (data.isNotEmpty()) {` 块内部，在 `val event = parseEvent(data)` 调用之前（约 L140 行），添加 raw emit：

```kotlin
// Emit raw JSON for V2 pipeline (before V1 parsing)
rawSseEvents.tryEmit(data)
```

注意：这里使用 `tryEmit` 而非 `emit`，因为我们在 `statement.execute` 回调中（不在 flow builder 的 suspend 上下文中），且 `SharedFlow` 配置了 `extraBufferCapacity = 64` 和 `DROP_OLDEST`，`tryEmit` 不会阻塞也不会丢失关键数据。

- [ ] **Step 3: 添加 public 只读访问器**

在 `rawSseEvents` 字段后面添加：

```kotlin
/** Read-only access for external consumers (V2 pipeline). */
val rawSseEventFlow: SharedFlow<String> = rawSseEvents.asSharedFlow()
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt
git commit -m "feat(sse): add rawSseEvents SharedFlow for V2 pipeline"
```

---

### Task 2: SseConnectionManager (V2) — 改为消费 Flow<String> 数据源

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SseConnectionManager.kt`

**原理：** 当前 `SseConnectionManager` 自己建立 HTTP 连接到 `/global/event`。修改为接收一个 `Flow<String>` 参数，从中读取原始 JSON 并用 `EventParser` 解析。保留去重和错误恢复逻辑。

- [ ] **Step 1: 重写 SseConnectionManager**

完整替换文件内容为：

```kotlin
package dev.minios.ocremote.data.v2

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "V2-SseConn"

/**
 * Consumes raw SSE JSON strings (from V1's SharedFlow) and parses them
 * into [SseEventV2] using [EventParser].
 *
 * No longer establishes its own HTTP connection — reuses V1's connection.
 * Retains event deduplication.
 */
class SseConnectionManager(
    private val rawEventsFlow: Flow<String>,
    private val parser: EventParser = EventParser,
    private val deduplicator: EventDeduplicator = EventDeduplicator(),
) {
    /**
     * Connect to the raw SSE event stream and parse events.
     * The [rawEventsFlow] is expected to be V1's [SseClient.rawSseEventFlow].
     */
    fun connect(): Flow<SseEventV2> = flow {
        rawEventsFlow.collect { data ->
            try {
                val event = parser.parse(data)
                if (event != null && !deduplicator.isDuplicate(event)) {
                    emit(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Parse error: ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/v2/SseConnectionManager.kt
git commit -m "refactor(v2): SseConnectionManager consumes Flow<String> instead of HTTP"
```

---

### Task 3: OpenCodeV2Sdk — 适配新的 SseConnectionManager 接口

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/v2/OpenCodeV2Sdk.kt`

**原理：** `OpenCodeV2SdkImpl` 当前自己创建 `SseConnectionManager(httpClient, serverUrl, authHeader)`。需要改为接收 `Flow<String>` 并传给新的 `SseConnectionManager`。

- [ ] **Step 1: 修改 OpenCodeV2SdkImpl 构造函数**

将 `OpenCodeV2SdkImpl` 的构造函数从：

```kotlin
class OpenCodeV2SdkImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val connectionManager: SseConnectionManager,
    private val authHeader: String? = null,
) : OpenCodeV2Sdk {
```

改为：

```kotlin
class OpenCodeV2SdkImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    rawSseEvents: Flow<String>,
    private val authHeader: String? = null,
) : OpenCodeV2Sdk {
    private val connectionManager = SseConnectionManager(rawSseEvents)
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（注意：ChatViewModel 还没改，这里会编译失败，因为 lazy 块中传参不匹配。先不提交，等 Task 4 一起验证）

---

### Task 4: ChatViewModel — 将 V1 raw events flow 传给 V2 SDK

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**原理：** ChatViewModel 的 `v2Sdk` lazy 块当前自己创建 `SseConnectionManager(httpClient, serverUrl, authHeader)`。需要改为使用 V1 `SseClient` 的 `rawSseEventFlow`。

**关键问题：** ChatViewModel 当前**未注入** `SseClient`（已确认构造函数参数列表中没有）。需要添加 `SseClient` 注入。`SseClient` 已标记 `@Singleton`，Hilt 可以直接注入。

- [ ] **Step 1: 在 ChatViewModel 构造函数中添加 SseClient 注入**

在构造函数的 `private val httpClient: HttpClient,` 后面添加：

```kotlin
private val sseClient: SseClient,
```

需要添加 import：

```kotlin
import dev.minios.ocremote.data.api.SseClient
```

- [ ] **Step 2: 修改 v2Sdk lazy 块**

将 L288-295 的 lazy 块从：

```kotlin
private val v2Sdk: OpenCodeV2Sdk by lazy {
    OpenCodeV2SdkImpl(
        httpClient = httpClient,
        baseUrl = serverUrl,
        connectionManager = SseConnectionManager(httpClient, serverUrl, authHeader),
        authHeader = authHeader,
    )
}
```

改为：

```kotlin
private val v2Sdk: OpenCodeV2Sdk by lazy {
    OpenCodeV2SdkImpl(
        httpClient = httpClient,
        baseUrl = serverUrl,
        rawSseEvents = sseClient.rawSseEventFlow,
        authHeader = authHeader,
    )
}
```

同时移除不再需要的 import：`import dev.minios.ocremote.data.v2.SseConnectionManager`（如果不再被其他代码引用）。

- [ ] **Step 2: 编译验证（Task 3 + Task 4 一起）**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit（Task 3 + Task 4 一起）**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/data/v2/OpenCodeV2Sdk.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt
git commit -m "refactor(v2): V2 SDK consumes V1 raw SSE flow, eliminating duplicate connection"
```

---

### Task 5: 清理 — 移除 V2 SseConnectionManager 中不再需要的 import 和依赖

**Files:**
- Verify: `app/src/main/kotlin/dev/minios/ocremote/data/v2/SseConnectionManager.kt`
- Verify: `app/src/main/kotlin/dev/minios/ocremote/data/v2/OpenCodeV2Sdk.kt`

- [ ] **Step 1: 检查 V2 SseConnectionManager 不再 import Ktor HTTP 相关类**

V2 `SseConnectionManager.kt` 不再需要：
- `io.ktor.client.HttpClient`
- `io.ktor.client.plugins.timeout`
- `io.ktor.client.request.prepareGet`
- `io.ktor.client.request.headers`
- `io.ktor.client.statement.bodyAsChannel`
- `io.ktor.utils.io.readLine`
- `kotlinx.coroutines.delay`（不再需要指数退避）
- `kotlinx.coroutines.currentCoroutineContext`
- `kotlinx.coroutines.isActive`

确认 Task 2 的代码已移除这些 import。

- [ ] **Step 2: 检查 OpenCodeV2Sdk.kt 中 connectionManager 参数已移除**

确认 `OpenCodeV2SdkImpl` 不再有 `connectionManager: SseConnectionManager` 参数，改为 `rawSseEvents: Flow<String>`。

- [ ] **Step 3: 最终编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit（如有清理改动）**

```bash
git add -A
git commit -m "chore: clean up unused imports after V2 SSE refactor"
```

---

### Task 6: 单元测试验证

**Files:**
- Run existing tests

- [ ] **Step 1: 运行现有单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All tests pass

- [ ] **Step 2: 检查测试中是否有直接创建 SseConnectionManager 或 OpenCodeV2SdkImpl 的代码**

搜索测试文件中对 `SseConnectionManager` 和 `OpenCodeV2SdkImpl` 的引用。如果有，需要更新构造参数以匹配新接口。

- [ ] **Step 3: 修复任何失败的测试并 Commit**

```bash
git add -A
git commit -m "test: update tests for V2 SSE refactor"
```

---

## Self-Review

### Spec Coverage
- ✅ 消除重复 HTTP 连接 → Task 2 移除 V2 的 HTTP 连接代码
- ✅ V2 消费 V1 SSE 流 → Task 1 在 SseClient 中暴露 raw flow, Task 4 将其传给 V2 SDK
- ✅ V2 EventParser 保持不变 → 没有修改 EventParser.kt
- ✅ 为未来 V2 API 做准备 → EventParser 的 31 种 session.next.* 类型完全保留

### Placeholder Scan
- Task 4 Step 1 有条件分支（方案 A/B），需要执行时确认 SseClient 注入方式 → 实现时探查确定

### Type Consistency
- `MutableSharedFlow<String>` → `asSharedFlow()` → `SharedFlow<String>` → 传给 `SseConnectionManager(rawEventsFlow: Flow<String>)` → 传给 `OpenCodeV2SdkImpl(rawSseEvents: Flow<String>)` ✅
- `rawSseEvents.tryEmit(data)` 中 `data` 类型是 `String` ✅
