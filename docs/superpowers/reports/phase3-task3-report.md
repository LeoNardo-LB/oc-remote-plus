# Phase 3 Task 3: SessionLifecycleDelegate Extraction (C Cluster)

> Branch: `refactor/phase1-data-foundation` · Base HEAD: `6e6e824d`
> Status: **DONE** — compile ✅ · unit tests ✅ · UI零改动（门面）

## 概要

提取 `SessionLifecycleDelegate`（C 簇）—— delegate 化的**脊柱**。它独占会话身份
`_sessionId`，暴露稳定的 `sessionIdFlow: StateFlow<String>`，作为 6 个
`combine`/`flatMapLatest` 管道的源；同时拥有 `sessionDirectory`、`sessionLoaded`、
懒创建 mutex，供 `TerminalDelegate` / `DraftInputDelegate` 通过构造函数 provider 消费。

## 文件变更

| 文件 | 变化 | 行数 |
|------|------|------|
| `app/.../chat/SessionLifecycleDelegate.kt` | **新增** | 153 |
| `app/.../chat/ChatViewModel.kt` | 修改 | 2275 → 2223 (**−52**) |

ChatViewModel diff：58 insertions, 111 deletions（净 −53 含末尾换行）。

## SessionLifecycleDelegate 设计

构造参数（全部 per-ChatViewModel，**非** `@Singleton`/`@Inject`）：

```kotlin
internal class SessionLifecycleDelegate(
    private val manageSessionUseCase: ManageSessionUseCase,
    private val sessionRepository: SessionRepository,
    private val serverId: String,
    savedStateHandle: SavedStateHandle,            // 读 directoryParam + sessionId
    private val scope: CoroutineScope,
    private val onMessagesNeedLoading: suspend () -> Unit,   // 跨域回调 → MessageData
    private val onStartObservingMessages: () -> Unit,        // 跨域回调 → SSE 观察
)
```

**暴露**：
- `val sessionIdFlow: StateFlow<String>` — 6 个 combine 的源
- `val sessionId: String` — 同步读取
- `var sessionDirectory: String?`（private set）— REST 调用 directory 参数
- `val sessionLoaded: CompletableDeferred<Unit>` — 终端/其他域等待信号

**方法**：
- `loadSession()` — 加载会话信息 + 通过回调触发 messages 加载 + SSE 观察
- `ensureSession()` — 懒创建（mutex 完整包裹 + double-check + 通过回调启动 SSE 观察）
- `initForNewSession()` — 新会话路由参数初始化（替代 init 块的 else 分支）

### 关键设计决策：跨域副作用用回调注入

`loadSession`/`ensureSession` 原本混了 C 簇（session 信息）+ MessageData（listMessages
+ startObservingMessages）两个簇的逻辑。为保证 **mutex 完整包裹临界区**（保留原
单次执行语义——两个协程并发调 `ensureSession` 时只有一个真正创建并启动观察），
跨域副作用通过回调注入 delegate，而非拆散 mutex：

- `onMessagesNeedLoading` → VM 的 `loadMessagesForSession()`（currentMessageLimit + listMessages + setMessages）
- `onStartObservingMessages` → VM 的 `startObservingMessages()`

这样 mutex 逻辑、double-check、`_sessionId` 写入全部在 delegate 内，VM 只保留
MessageData 簇关切。`sessionMetaState`/`directoryState` 不迁移（依赖 VM 其他状态如
`sessionRepository.getSessionsFlow`、`sessionStatusManager`），仅改 `_sessionId` 引用。

## 6 个 combine 的 sessionId 引用更新

全部从 `_sessionId` 改为 `sessionLifecycle.sessionIdFlow`，combine 本体留在 VM
（留给各自 delegate task）：

| # | State（行号） | 形态 | 验证 |
|---|--------------|------|------|
| 1 | `modelConfigState` (L523) | `flatMapLatest` | ✅ |
| 2 | `messageListState` (L661) | `flatMapLatest` | ✅ |
| 3 | `sessionMetaState` (L763) | `combine` source | ✅ |
| 4 | `interactionState` (L804) | `combine` source | ✅ |
| 5 | `directoryState` (L857) | `flatMapLatest` | ✅ |
| 6 | `contextDetailState` (L872) | `flatMapLatest` | ✅ |

## Delegate Provider 重指向

| Delegate | Provider | 新指向 | 行号 |
|----------|----------|--------|------|
| TerminalDelegate | `sessionDirectoryProvider` | `{ sessionLifecycle.sessionDirectory }` | L396 |
| TerminalDelegate | `sessionLoaded` | `sessionLifecycle.sessionLoaded` | L397 |
| DraftInputDelegate | `sessionIdProvider` | `{ sessionLifecycle.sessionId }` | L414 |
| DraftInputDelegate | `sessionDirectoryProvider` | `{ sessionLifecycle.sessionDirectory }` | L415 |

## VM 门面（UI 零改动）

- `val sessionId: String get() = sessionLifecycle.sessionId`
- `fun getSessionDirectory(): String? = sessionLifecycle.sessionDirectory`

## 清理

- 删除 VM 私有状态：`_sessionId`、`directoryParam`、`sessionDirectory`、`sessionCreateMutex`、`sessionLoaded`
- 删除 VM 私有方法：`loadSession()`（迁入 delegate）、`ensureSession()`（迁入 delegate）
- 新增 VM 私有方法：`loadMessagesForSession()`（从旧 `loadSession` 提取的 MessageData 部分）
- 删除 4 个孤立 import：`CompletableDeferred`、`Mutex`、`withLock`、`ChatNav`
- 修正 L762 KDoc 过时引用 `[_sessionId]` → `[SessionLifecycleDelegate.sessionIdFlow]`
- init 块 else 分支（新会话）改为单行 `sessionLifecycle.initForNewSession()`

## 验证

| 检查 | 命令 | 结果 |
|------|------|------|
| Kotlin 编译 | `.\gradlew :app:compileDevDebugKotlin` | **BUILD SUCCESSFUL** (23s) |
| 单元测试 | `.\gradlew :app:testDevDebugUnitTest --rerun` | **BUILD SUCCESSFUL** (40s) |
| 残留 raw `_sessionId`/`sessionDirectory` 引用 | 全文扫描 | 仅注释行（实质代码零残留） |
| 6 combine 引用 | grep `sessionLifecycle.sessionIdFlow` | 6/6 匹配 |

## 顾虑

1. **回调注入 vs 纯 delegate**：`onMessagesNeedLoading`/`onStartObservingMessages` 是
   C→MessageData 的跨域耦合点。当 MessageDataDelegate（Task 6）提取后，这两个回调
   应重构为 delegate 间的意图方法调用（符合"共享状态铁律 §2：跨域写入只通过意图方法"）。
   当前形态是过渡态，mutex 语义正确。

2. **`loadMessagesForSession` 命名**：VM 现有 `fun loadMessages()`（L1174，公开，
   分页加载）与新增 `private suspend fun loadMessagesForSession()`（回调，初始化加载）
   并存。两者职责不同但名称相近，MessageDataDelegate task 时建议合并/重命名。

3. **`scope` 参数未使用**：`SessionLifecycleDelegate` 构造接收 `scope` 但当前未直接
   使用（loadSession/ensureSession 是 suspend，由调用方的协程驱动）。保留以与
   `TerminalDelegate` 构造签名一致，并为未来 delegate 内 launch 预留。如 lint 报
   unused，可移除——但当前编译无警告。

## Commit

`refactor(chat): extract SessionLifecycleDelegate from ChatViewModel (Phase 3 Task 3)`
