# Phase 3 Task 7 — ChatViewModel 协调器精简 + 门面审计 + 死代码清理

**日期**: 2026-06-27
**分支**: refactor/phase1-data-foundation
**状态**: ✅ 完成

## 执行摘要

Phase 3 收尾任务。6 个 delegate 已在 Task 1-6 提取完毕（Terminal + Scroll + DraftInput + SessionLifecycle + ModelConfig + MessageData + SessionActions）。本任务完成死代码清理、门面审计、协调器逻辑审计和全量验证。

ChatViewModel 从 **1220 → 1217 行**（-3 行，清理 3 个孤立 import）。

## 1. 死代码清理

### 已清理（delegate 提取后遗留的孤立 import）

| Import | 原因 |
|--------|------|
| `androidx.annotation.VisibleForTesting` | 标注的方法已迁移至 delegate，VM 内无 `@VisibleForTesting` 使用 |
| `dev.leonardo.ocremotev2.R` | R 资源引用随 UI 逻辑迁移至 delegate/sub-component |
| `dev.leonardo.ocremotev2.domain.repository.DraftRepository` | 构造函数不再直接声明此类型（由 DraftInputDelegate 持有） |

### 预先存在的死代码（提及，未删除 — 遵守 AGENTS.md "不得触碰预先存在的死代码"）

经 git 确认，以下符号在 `master` 分支上即为死代码（零调用），非本次 refactor 产生：

| 符号 | 类型 | 说明 |
|------|------|------|
| `getConnectionParams()` | VM public method | 全代码库零调用（含 master） |
| `ConnectionParams` | data class | 仅被 `getConnectionParams()` 引用，连带死代码 |
| `getLastAssistantText()` 门面 | VM public method | master 上即无调用；refactor 后变为委托门面，仍无调用。注：`SessionActionsDelegate` 内有对应实现 |

### 非死代码（已确认保留）

- `uiState` StateFlow + `ChatUiState` data class：UI 不直接使用，但 **测试大量依赖**（`ChatViewModelSendTest`、`ChatViewModelQueuedTest` 共 20+ 处 `vm.uiState.value` 断言）。注释标注 "Legacy uiState for backward compatibility (tests)"。
- `closeTerminalSession()`：在 `onCleared()` 中调用。
- `refreshSession()`、`onSessionUpdated()`：被单元测试调用。
- `serverName`、`serverId`、`directoryState` 等：VM 内部协调器使用。

## 2. 门面属性审计

**方法**: 提取 UI 目录所有 `viewModel.\w+` 引用（85 个唯一符号），与 ChatViewModel public 成员逐一对比。

**结果**: ✅ **无缺失门面** — 85/85 UI 引用在 ChatViewModel 中全部有对应门面属性/方法。

UI 引用分布：
- `ChatScreen.kt`: 70 个引用
- `components/ChatMessageList.kt`: 10 个引用
- `terminal/ChatTerminalView.kt`: 12 个引用

VM 有但 UI 未直接引用的 18 个成员均已确认被 VM 内部协调器、`onCleared()`、或单元测试使用，非死代码。

## 3. UiSettingsDelegate 提取评估

**决策**: ❌ **不提取**

**分析**: Settings 派生 StateFlow 共 13 个：
`chatFontSize`, `chatDensity`, `codeWordWrap`, `confirmBeforeSend`, `compactMessages`, `collapseTools`, `expandReasoning`, `showTurnDividers`, `hapticFeedback`, `keepScreenOn`, `compressImageAttachments`, `imageAttachmentMaxLongSide`, `imageAttachmentWebpQuality`

每个约 3 行（`settingsRepository.getSettingsFlow().map { it.xxx }.stateIn(...)`），合计约 39 行。

**占比**: 39 / 1217 ≈ **3.2%**，远低于 20% 提取阈值。保留在 VM 中。

## 4. 协调器逻辑审计

确认 4 个留在 VM 的协调器逻辑清晰，通过 delegate public API 协调，**无跨 delegate 直接私有态写入**：

| 协调器 | 编排范围 | 审计结果 |
|--------|---------|---------|
| `sendParts()` | Scroll↔MessageData↔SessionLifecycle↔ModelConfig↔SessionActions | ✅ 全部通过 delegate public API |
| `sendMessage(text, attachments)` | 构建 parts → sendParts | ✅ 纯转发 |
| `sendMessage(promptParts, attachments)` | 合并 parts → sendParts | ✅ 纯转发 |
| `abortSession()` | SessionStatusManager↔MessageData↔SessionActions (B↔C↔G) | ✅ 编排正确，SSE job 取消后重启 |
| `revertMessage()` | SessionStatusManager↔MessageData↔ChatRepository↔DraftDelegate↔SessionActions (B↔D↔G) | ✅ halt→revert→setRevert→reconnect→restoreDraft 编排正确 |

辅助引用确认合法：
- `scrollSignal`（构造参数，sendParts 中 `requestScrollToTop()`）
- `appendDiagnosticLog`（私有方法，refreshSessionTitleDelayed 中调用）

## 5. 验证结果

| 验证项 | 命令 | 结果 |
|--------|------|------|
| Kotlin 编译 | `compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (21s) |
| 单元测试 | `testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (22s, 975 tests) |
| 完整构建 | `assembleDevDebug` | ✅ BUILD SUCCESSFUL (14s) |

> 注：首次测试运行中 `PermissionAutoApproverTest > AutoApproveRule serialization round-trip` 出现 1 次失败，重跑后通过 — 确认为 flaky test，与本次改动（仅删除 3 个无关 import）无关。

## 6. 交付物

- `ChatViewModel.kt`: 删除 3 个 unused imports（-3 行）
- 本报告

## 7. Phase 3 完成总结

Phase 3（Task 1-7）完成。ChatViewModel 从原始 2450 行精简至 **1217 行**（-50.3%），通过 6 个 delegate 提取实现关注点分离：

| Delegate | Task | 职责 |
|----------|------|------|
| TerminalDelegate | Task 1 | WebSocket PTY 终端管理 |
| ScrollPositionDelegate | Task 2 | 滚动位置保存/恢复 |
| DraftInputDelegate | Task 3 | 草稿文本/附件/文件搜索 |
| SessionLifecycleDelegate | Task 4 | Session 创建/目录/聚焦 |
| ModelConfigDelegate | Task 5 | Provider/Model/Agent/Variant 选择 |
| MessageDataDelegate | Task 6 | 消息列表/SSE/分页/工具展开 |
| SessionActionsDelegate | Task 6 | Session 级 REST 操作（abort/share/fork/undo 等） |

VM 保留的核心职责：状态聚合（uiState/sessionMetaState/contextDetailState）、协调器编排（send/abort/revert）、settings 派生流（13 个）。
