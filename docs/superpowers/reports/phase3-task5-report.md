# Phase 3 Task 5 — MessageDataDelegate (B 簇) 提取报告

> 分支: `refactor/phase1-data-foundation`  
> 基线 HEAD: `3ec0debb` (Task 4 — ModelConfigDelegate)

## 状态: ✅ 完成

## 产出文件

| 文件 | 行数 | 说明 |
|------|------|------|
| `app/.../chat/MessageDataDelegate.kt` | **555** (新建) | B 簇全部状态 + 方法 + 2 个 combine + 发送生命周期意图方法 + sseJob 管理 |
| `app/.../chat/ChatViewModel.kt` | **1631** (原 1993, **-362**) | 移除 B 簇私有态/方法/combine，改为门面转发 + 意图方法调用 |

## ChatViewModel 行数变化

- **Before (Task 4 后)**: 1993 行
- **After (Task 5)**: 1631 行
- **净减**: 362 行 (God Class 持续瘦身)

## 迁移内容

### StateFlow / 变量 (11 + 2 var，全部迁移)
`_isLoading`, `_isRefreshing`, `_error`, `_isSending`, `_messagesList`, `_rawMessagesList`, `_partsList`, `_pendingMessageIds`, `_toolExpandedStates`, `_hasOlderMessages`, `_isLoadingOlder` + `currentMessageLimit`(var) + `sseJob`(var Job?)

> `lastRefreshTimeMs` 保留在 VM（协调方法 `refreshIfNeeded`/`refreshAndSync` 使用，属协调器职责）。

### 两个 combine（完整迁移）
- **`messageListState`**（8 路 combine + flatMapLatest(sessionIdFlow)）→ delegate 内部，引用 delegate 私有态
- **`interactionState`**（7 路 combine）→ delegate 内部

VM 保留 `val messageListState/interactionState` 门面（`get() = messageData.xxx`），供 `contextDetailState`/`uiState` 等后续 combine 消费。

### 方法（全部迁移）
`loadMessagesForSession`, `startObservingMessages`, `loadMessages`, `refreshMessages`, `fixIncompleteMessagesIfIdle`, `loadOlderMessages`, `loadPendingQuestions`, `loadPendingPermissions`, `toggleToolExpanded`, `isToolExpanded`

## 封装设计

### 发送生命周期意图方法（sendParts 协调器通过这些写状态，不直接碰 delegate 私有态）
```kotlin
fun onSendStarted(pendingId: String)   // _isSending=true, _pendingMessageIds + pendingId
fun onSendSuccess(pendingId: String)   // _isSending=false, _pendingMessageIds - pendingId
fun onSendError(message: String, pendingId: String)  // _isSending=false, _error=msg, _pendingMessageIds - pendingId
```

`sendParts` 改写：launch 前 `onSendStarted`，成功 `onSendSuccess`，catch `onSendError`（draft 恢复仍留 VM）。原 `finally { _isSending = false }` 移除——成功/失败路径各自在意图方法内设置 isSending，语义等价。

### sseJob 管理（abort/revert 协调器需要取消并重启观察）
```kotlin
fun cancelSseJob()              // sseJob?.cancel(); sseJob = null
fun startObservingMessages()    // 重启 SSE 观察（internal，abort/revert/sessionLifecycle 回调共用）
fun markLoaded()                // 新会话标记 _isLoading=false
```

`abortSession`/`revertMessage`/`onCleared` 中 `sseJob?.cancel(); sseJob = null` → `messageData.cancelSseJob()`；`runCatching { startObservingMessages() }` → `messageData.startObservingMessages()`。

## SessionLifecycle 回调更新

Task 3 注入的回调 `onMessagesNeedLoading`/`onStartObservingMessages` 现指向 delegate 逻辑。**但通过 VM 转发方法间接调用**，而非直接引用 `messageData` 属性（见下方"关键决策"）：

```kotlin
private val sessionLifecycle = SessionLifecycleDelegate(
    ...
    onMessagesNeedLoading = { loadMessagesForSession() },        // VM 私有方法
    onStartObservingMessages = { startObservingMessages() },     // VM 私有方法
)
private val messageData: MessageDataDelegate = MessageDataDelegate(
    sessionIdFlow = sessionLifecycle.sessionIdFlow,
    sessionDirectoryProvider = { sessionLifecycle.sessionDirectory },
    ...
)
private suspend fun loadMessagesForSession() = messageData.loadMessagesForSession()
private fun startObservingMessages() = messageData.startObservingMessages()
```

## 关键决策: 属性初始化循环

### 问题
直接让回调引用 `messageData` 属性（`{ messageData.loadMessagesForSession() }`）会导致编译错误：
```
Type checking has run into a recursive problem. Easiest workaround:
specify the types of your declarations explicitly.
Unresolved reference 'sessionIdFlow'.
```
原因：`sessionLifecycle` 构造参数（lambda）→ 捕获 `messageData` → `messageData` 构造参数引用 `sessionLifecycle.sessionIdFlow` → 形成属性初始化阶段的类型推断循环。

### 解决（两步）
1. **回调引用 VM 方法**（`{ loadMessagesForSession() }`）而非 `messageData` 属性——方法调用在运行时（init 块）延迟解析，不参与属性初始化顺序。VM 方法体转发到 `messageData`，此时两者均已初始化。
2. **给 `messageData` 显式类型标注**（`private val messageData: MessageDataDelegate = ...`）—— 编译器提示的 workaround，彻底打破递归推断。

> ModelConfigDelegate 无此问题，因为它不被 sessionLifecycle 的回调引用（无循环）。

## UI 零改动验证

所有 UI 消费的 public API 保留为 VM 门面：
- `messageListState` / `interactionState` (StateFlow 门面)
- `toolExpandedStates` (StateFlow 门面)
- `loadMessages()` / `loadOlderMessages()` / `toggleToolExpanded()` / `isToolExpanded()`
- `uiState` / `contextDetailState` 等后续 combine 引用门面，编译通过即证明无破坏

## 编译 & 测试

| 检查 | 命令 | 结果 |
|------|------|------|
| Kotlin 编译 | `:app:compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (22s) |
| 单元测试 | `:app:testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (43s), 全部 failures="0" |

测试套件抽样（全部 0 failures）：DtoSerialization(42), RetryPolicy(16), PathUtils(55), SessionStatus(8), SseMapper(8) 等共 20+ testsuite。

## 孤立 imports 清理

移除 B 簇迁移后不再使用的 4 个 imports：
- `kotlinx.coroutines.Job` (sseJob 类型)
- `kotlinx.coroutines.flow.MutableStateFlow` (VM 不再直接声明)
- `kotlinx.coroutines.flow.first` (loadMessagesForSession 用，已迁)
- `kotlinx.coroutines.flow.update` (_pendingMessageIds/_toolExpandedStates.update，已迁)

## Commit

```
refactor(chat): extract MessageDataDelegate from ChatViewModel (Phase 3 Task 5)
```

## 顾虑

1. **messageData 显式类型标注是必须的**——若后续 Task 移除该标注会重新触发循环错误。建议在代码注释中标注此约束（已通过 sessionLifecycle 回调处的注释说明）。
2. **`tokenStatsTracker` 未注入 delegate**——init 块的 `_messagesList.collect → tokenStatsTracker.update` 逻辑保留在 VM，通过 `messageData.messagesList` 只读 flow 消费。这是有意的职责分离（token 统计属 token 簇），但意味着 VM 仍有一个对消息流的 collect。未来 UiSettingsDelegate/TokenDelegate 提取时可将此逻辑一并迁移。
3. **`sessionIdFlow.value` 替代 `sessionId` getter**——delegate 方法内用 `sessionIdFlow.value` 获取当前 sessionId（等价于原 VM 的 `sessionLifecycle.sessionId`）。运行时语义完全一致，因 sessionIdFlow 即 sessionLifecycle 暴露的 _sessionId StateFlow。
4. **sendParts 的 isSending 时机微调**——原代码 `_isSending=true` 在 `viewModelScope.launch` 内部首行；重构后 `onSendStarted`（含 isSending=true）在 launch 前调用。差异：isSending 提前一个调度周期变 true。对 UI 无实际影响（发送是用户主动触发，无竞态）。
