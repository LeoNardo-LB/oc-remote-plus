# Phase 3 Task 4: ModelConfigDelegate 提取（A 簇）

> 分支: `refactor/phase1-data-foundation`
> 前置: Task 3 (SessionLifecycleDelegate — 脊柱，提供 `sessionIdFlow`)

## 状态

✅ **完成** — 编译通过 + 单元测试通过 + 已提交

## 变更文件

| 文件 | 操作 | 行数 |
|------|------|------|
| `app/.../ui/screens/chat/ModelConfigDelegate.kt` | **新增** | 340 |
| `app/.../ui/screens/chat/ChatViewModel.kt` | 修改 | 2222 → 1992 (**−230**) |

### ChatViewModel 行数变化

- 提取前: 2222 行
- 提取后: 1992 行
- 净减少: **230 行**

## 迁移内容（A 簇）

### 状态（11 StateFlow + 1 var + companion cache）

全部迁移至 `ModelConfigDelegate`:
`_allProviders, _providers, _hiddenModels, _defaultModels, _selectedProviderId,
_selectedModelId, _agents, _selectedAgent, _selectedVariant, _commands` (10 StateFlow)
+ `isModelExplicitlySelected` (var) + `sessionModelCache` (companion object)

### 方法

`loadProviders, applyProviderFilter, loadAgents, loadCommands, observeHiddenModels,
selectAgent, cycleVariant, selectModel` + 恢复逻辑 `applyDraftRestore, restoreModelFromCache`

### modelConfigState combine（含副作用）— 整体迁移 ✅

12 路 `combine`（9 内部 StateFlow + `messagePaging.observeMessages` + `sessionRepository.getSessionsFlow` + `tokenStatsTracker.stats`），以 `sessionIdFlow.flatMapLatest` 为源。

**自反馈副作用完整保留**（未拆分）:
- 从消息历史解析 model/agent → 回写 `_selectedAgent / _selectedProviderId / _selectedModelId`
- 解析结果写入 `sessionModelCache[sid]`
- context window fallback 链（tokenStats → session.model → currentModel）

combine 体内引用的 `sid` 来自 `sessionIdFlow.flatMapLatest`，与 SessionLifecycleDelegate 脊柱对接。

## VM 门面（UI 零改动）

ChatViewModel 保留薄转发:
```kotlin
val modelConfigState get() = modelConfig.modelConfigState
fun selectAgent(...) = modelConfig.selectAgent(...)
fun cycleVariant() = modelConfig.cycleVariant()
fun selectModel(...) = modelConfig.selectModel(...)
```

跨域读取点:
- `DraftInputDelegate` 的 `selectedAgentProvider / selectedVariantProvider` → `{ modelConfig.selectedAgentValue / selectedVariantValue }`
- `sendParts()` 的 variant → `modelConfig.selectedVariantValue`

## 依赖注入方式

与 SessionLifecycleDelegate / DraftInputDelegate 一致 — **非 Hilt**，VM 直接构造：
```kotlin
ModelConfigDelegate(
    selectModelUseCase, manageAgentUseCase, settingsRepository,
    sessionRepository, messagePaging, tokenStatsTracker,
    serverId, sessionIdFlow = sessionLifecycle.sessionIdFlow, scope = viewModelScope
)
```

> 注: 任务描述中列了 `chatRepository`，但 combine 与 A 簇方法均未引用它（消息流通过 `messagePaging.observeMessages`），
> 遵循 AGENTS.md §1.3（不添加未使用的参数），故未纳入构造函数。

## 验证

| 检查 | 命令 | 结果 |
|------|------|------|
| Kotlin 编译 | `:app:compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (27s) |
| 单元测试 | `:app:testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (40s) |
| 悬空引用 | grep A 簇私有态 | ✅ 无残留 |

## Commit

`<hash>` — `refactor(chat): extract ModelConfigDelegate from ChatViewModel (Phase 3 Task 4)`

## 顾虑

1. **init 时序**: `modelConfig` 在 `sessionLifecycle` 之后、`draftDelegate`/`terminalDelegate` 之前声明。`draftDelegate` 的 provider lambda 运行期才求值，引用 `modelConfig` 安全（构造期不求值）。
2. **sessionModelCache 跨 VM 共享**: 已移入 delegate 的 companion object，与原 VM companion 语义一致（进程级单例，跨 VM 重建保留，进程死亡清除）。
3. **selectModel 用 sessionIdFlow.value**: 替代原 `sessionId` getter，语义等价（StateFlow.value 同步读取当前会话 id）。
