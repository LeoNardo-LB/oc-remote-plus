# Lazy Session Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会话创建逻辑从"立即创建"改为"延迟创建"——点击新建只导航到空 ChatScreen，发送第一条消息时才调用 `POST /session`。

**Architecture:** ChatNav 路由新增 `directory` 参数；`sessionId` 空字符串表示新会话；ChatViewModel 新增 `ensureSession()` 互斥创建逻辑；`sendParts` 发送前自动创建会话。

**Tech Stack:** Kotlin, Jetpack Compose Navigation, Hilt, Coroutines (Mutex)

---

### Task 1: ChatNav.kt — 新增 directory 路由参数

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ChatNav.kt`

- [ ] **Step 1: 新增 PARAM_DIRECTORY 常量和 navArgument**

在 `ChatNav` object 中新增：
```kotlin
const val PARAM_DIRECTORY = "directory"
```

在 `navArguments` 列表中追加一个可选参数（默认空字符串）：
```kotlin
navArgument(PARAM_DIRECTORY) {
    type = NavType.StringType
    defaultValue = ""
}
```

- [ ] **Step 2: 更新 routePattern**

在 `routePattern` 的 get() 中追加 `&$PARAM_DIRECTORY={$PARAM_DIRECTORY}`。

- [ ] **Step 3: 更新 Params data class**

在 `Params` 中新增 `directory: String = ""` 字段（在 `openTerminal` 之后）。

- [ ] **Step 4: 更新 createRoute 方法**

新增参数 `directory: String = ""`。构建路由时追加：
```kotlin
val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
```
在 route 字符串末尾追加 `&$PARAM_DIRECTORY=$encodedDirectory`。

- [ ] **Step 5: 更新 fromEntry 方法**

新增解析：
```kotlin
val directory = URLDecoder.decode(entry.arguments?.getString(PARAM_DIRECTORY).orEmpty(), "UTF-8")
```
在返回的 `Params` 中传入 `directory = directory`。

- [ ] **Step 6: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A; git commit -m "feat: add directory parameter to ChatNav route"
```

---

### Task 2: ChatViewModel.kt — 核心改造（延迟创建 + ensureSession）

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

这是最关键的 Task，改动较多但每处都很小。

- [ ] **Step 1: 新增 import**

在文件顶部 import 区新增：
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

- [ ] **Step 2: sessionId 改为 var + 新增 directoryParam + Mutex**

将行 146-147：
```kotlin
val sessionId: String = URLDecoder.decode(
    savedStateHandle.get<String>("sessionId") ?: "", "UTF-8")
```
改为：
```kotlin
private val directoryParam: String = URLDecoder.decode(
    savedStateHandle.get<String>(ChatNav.PARAM_DIRECTORY) ?: "", "UTF-8")
var sessionId: String = URLDecoder.decode(
    savedStateHandle.get<String>("sessionId") ?: "", "UTF-8")
    private set
```

注意：`sessionId` 从 `val` 改为 `var`，新增 `private set` 使其可内部修改但外部只读。`directoryParam` 存储路由传入的目录。

- [ ] **Step 3: 新增 Mutex 字段**

在 `sessionDirectory` 字段（行 166）附近新增：
```kotlin
/** Mutex to prevent concurrent session creation */
private val sessionCreateMutex = Mutex()
```

- [ ] **Step 4: 新增 ensureSession 方法**

在 `sendParts` 方法附近新增：
```kotlin
/**
 * Ensures a session exists before sending messages.
 * If sessionId is empty (new session), creates one via API.
 * Thread-safe via Mutex to prevent duplicate creation.
 */
private suspend fun ensureSession(): String {
    if (sessionId.isNotEmpty()) return sessionId
    return sessionCreateMutex.withLock {
        // Double-check after acquiring lock
        if (sessionId.isNotEmpty()) return sessionId
        val dir = if (directoryParam.isNotEmpty()) directoryParam else sessionDirectory
        val session = manageSessionUseCase.createSession(conn, directory = dir)
        eventDispatcher.setSessions(serverId, listOf(session))
        sessionId = session.id
        sessionDirectory = session.directory.ifBlank { dir }
        sessionLoaded.complete(Unit)
        sessionId
    }
}
```

- [ ] **Step 5: 修改 init 块 — 新会话跳过加载**

在 init 块的开头（行 506，`val draft = ...` 之前）新增：
```kotlin
val isNewSession = sessionId.isEmpty()
```

然后将行 507 的草稿恢复包裹在条件中：
```kotlin
if (!isNewSession) {
    val draft = draftUseCase.getDraft(sessionId)
    // ... 现有的草稿恢复逻辑 ...
}
```

同样将行 523 的 model cache 恢复包裹：
```kotlin
if (!isNewSession) {
    sessionModelCache[sessionId]?.let { (providerId, modelId) ->
        _selectedProviderId.value = providerId
        _selectedModelId.value = modelId
        isModelExplicitlySelected = true
    }
}
```

将行 543-561 的加载逻辑包裹：
```kotlin
if (!isNewSession) {
    viewModelScope.launch {
        currentMessageLimit = settingsRepository.initialMessageCount.first()
        try { loadSession() } catch (_: Exception) {}
        try { loadMessages() } catch (_: Exception) {}
        try { loadPendingQuestions() } catch (_: Exception) {}
        try { loadPendingPermissions() } catch (_: Exception) {}
    }
} else {
    // New session: set directory from route param, skip loading
    if (directoryParam.isNotEmpty()) {
        sessionDirectory = directoryParam
    }
    _isLoading.value = false
    sessionLoaded.complete(Unit)
}
```

`loadProviders()`, `loadAgents()`, `loadCommands()` 保持不变（这些不依赖 sessionId）。

- [ ] **Step 6: 修改 sendParts — 发送前确保会话存在**

将行 1039-1067 的 `sendParts` 方法改为：
```kotlin
private fun sendParts(parts: List<PromptPart>) {
    viewModelScope.launch {
        _isSending.value = true
        try {
            val currentSessionId = ensureSession()
            val model = if (_selectedProviderId.value != null && _selectedModelId.value != null) {
                ModelSelection(
                    providerId = _selectedProviderId.value!!,
                    modelId = _selectedModelId.value!!
                )
            } else null

            sendMessageUseCase.sendPrompt(
                conn = conn,
                sessionId = currentSessionId,
                parts = parts,
                model = model,
                agent = uiState.value.selectedAgent,
                variant = _selectedVariant.value,
                directory = sessionDirectory
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _error.value = e.message ?: "Failed to send message"
        } finally {
            _isSending.value = false
        }
    }
}
```

唯一变化：新增 `val currentSessionId = ensureSession()`，然后用 `currentSessionId` 替代原来的 `sessionId`。

- [ ] **Step 7: 删除 createNewSession 方法**

删除行 1503-1516 的 `createNewSession(onResult)` 方法（不再需要）。

- [ ] **Step 8: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL（会有 ChatScreen 的编译错误因为删除了 createNewSession，Task 3 修复）

- [ ] **Step 9: Commit**

```bash
git add -A; git commit -m "feat: ChatViewModel lazy session creation with ensureSession()"
```

注意：此 commit 编译可能不通过（因为 ChatScreen 还在调用 createNewSession），Task 3 会修复。如果不希望中间态编译失败，可以将 Task 2 和 Task 3 合并为一个 commit。

---

### Task 3: ChatScreen.kt + NavGraph.kt — 适配新的导航逻辑

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`

- [ ] **Step 1: ChatScreen — 修改 onNewSession 回调**

将行 540-549 的 `onNewSession` 回调从：
```kotlin
onNewSession = {
    viewModel.createNewSession { session ->
        if (session != null) {
            onNavigateToSession(session.id)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
            }
        }
    }
},
```
改为：
```kotlin
onNewSession = {
    onNavigateToSession("")  // Empty sessionId = new session
},
```

- [ ] **Step 2: ChatScreen — 修改 /new 斜杠命令**

将行 828-837 的 `"new"` 分支从：
```kotlin
"new" -> {
    viewModel.createNewSession { session ->
        if (session != null) {
            onNavigateToSession(session.id)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_session_create_failed))
            }
        }
    }
}
```
改为：
```kotlin
"new" -> {
    onNavigateToSession("")  // Empty sessionId = new session
}
```

- [ ] **Step 3: NavGraph — ChatScreen onNavigateToSession 传递 directory**

修改行 380-394 的 `onNavigateToSession` lambda。当 `newSessionId` 为空时，传递当前会话的 directory：
```kotlin
onNavigateToSession = { newSessionId ->
    val route = ChatNav.createRoute(
        serverUrl = params.server.serverUrl,
        username = params.server.username,
        password = params.server.password,
        serverName = params.server.serverName,
        serverId = params.server.serverId,
        sessionId = newSessionId,
        directory = if (newSessionId.isEmpty()) params.directory else ""
    )
    navController.navigate(route) {
        popUpTo(SessionListNav.routePattern) {
            inclusive = false
        }
    }
},
```

- [ ] **Step 4: NavGraph — onNavigateToChildSession 保持不变**

`onNavigateToChildSession` 不需要改动（子会话始终有真实 sessionId）。

- [ ] **Step 5: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A; git commit -m "feat: ChatScreen + NavGraph adapt to lazy session creation"
```

---

### Task 4: SessionListScreen + SessionListViewModel — FAB 改造

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListRoute.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`

- [ ] **Step 1: SessionListScreen — 新增 navigateToNewChat 回调**

修改 `SessionListScreen` 函数签名，将 `onNavigateToChat` 改为新增一个 `onNavigateToNewChat` 回调：
```kotlin
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateToNewChat: (directory: String) -> Unit,
    onNavigateBack: () -> Unit
)
```

- [ ] **Step 2: SessionListScreen — OpenProjectDialog onSelect 改为导航**

将行 288-290 的 `onSelect` 从：
```kotlin
onSelect = { directory ->
    showOpenProject = false
    viewModel.createNewSession(directory = directory)
}
```
改为：
```kotlin
onSelect = { directory ->
    showOpenProject = false
    onNavigateToNewChat(directory)
}
```

- [ ] **Step 3: SessionListScreen — 删除 navigateToSession 监听**

删除行 93-98 的 `LaunchedEffect(viewModel)` 监听 `navigateToSession` 的代码块（不再需要，因为不再有"创建后导航"的事件）。

- [ ] **Step 4: SessionListViewModel — 删除 createNewSession 方法和相关字段**

删除 `createNewSession` 方法（行 276-286）。
删除 `_navigateToSession` 和 `navigateToSession` 字段（行 94-95）。

- [ ] **Step 5: SessionListRoute — 传递新回调**

在 `SessionListRoute.kt` 中新增 `onNavigateToNewChat` 参数并传递给 `SessionListScreen`。

- [ ] **Step 6: NavGraph — SessionListScreen 传递 onNavigateToNewChat**

修改行 335-360 的 `SessionListRoute` 调用，新增：
```kotlin
onNavigateToNewChat = { directory ->
    navController.navigate(
        ChatNav.createRoute(
            serverUrl = params.server.serverUrl,
            username = params.server.username,
            password = params.server.password,
            serverName = params.server.serverName,
            serverId = params.server.serverId,
            sessionId = "",
            directory = directory
        )
    )
},
```

- [ ] **Step 7: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add -A; git commit -m "feat: SessionList FAB navigates to empty ChatScreen instead of creating session"
```

---

### Task 5: 最终编译验证 + 清理

**Files:**
- 可能需要清理未使用的 import

- [ ] **Step 1: 完整编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL, 0 errors

- [ ] **Step 2: 检查未使用的引用**

检查以下文件中是否有因删除方法/字段导致的 unused import：
- `SessionListViewModel.kt` — 如果 `manageSessionUseCase` 不再被其他方法使用，可能有 unused import
- `ChatScreen.kt` — 如果 `createNewSession` 删除后 `BuildConfig` 不再需要
- `ChatViewModel.kt` — 确认无 unused import

- [ ] **Step 3: 修复编译警告（如有）**

- [ ] **Step 4: 最终 Commit**

```bash
git add -A; git commit -m "chore: cleanup unused imports from lazy session creation refactor"
```

---

## 自审 Checklist

| Spec 要求 | 对应 Task |
|-----------|----------|
| ChatNav 新增 directory 参数 | Task 1 |
| sessionId 空字符串 = 新会话 | Task 2 (init 块条件判断) |
| ensureSession 互斥创建 | Task 2 (Mutex + ensureSession) |
| sendParts 发送前自动创建 | Task 2 (ensureSession 调用) |
| 空界面无特殊展示 | Task 2 (_isLoading = false, 不加载消息) |
| 创建失败展示错误 | Task 2 (ensureSession 抛异常 → sendParts catch) |
| 菜单"新建会话"延迟创建 | Task 3 (onNewSession → onNavigateToSession("")) |
| /new 斜杠命令延迟创建 | Task 3 (slash command → onNavigateToSession("")) |
| FAB 先选目录再进空界面 | Task 4 (OpenProjectDialog → onNavigateToNewChat) |
| 删除 createNewSession 方法 | Task 2 (ChatVM) + Task 4 (SessionListVM) |
| 并发发送防重入 | Task 2 (Mutex) |
| SSE 初始化跳过 | Task 2 (isNewSession 条件) |
| 已有会话流程不变 | Task 2 (isNewSession = false 走原逻辑) |
