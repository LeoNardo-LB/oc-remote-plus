# Phase 3: ChatViewModel Delegate 化分析摘要

> 来源: deep-explore agent 深度分析（2451L God Class → 6+2 delegate）

## Delegate 定义

| # | Delegate | 职责 | 私有态 | 风险 |
|---|----------|------|--------|------|
| 1 | **TerminalDelegate** | 终端 tab 管理（已委托 terminalWorkspace）| terminalWorkspace + 6 衍生流 | 极低 ★试点 |
| 2 | **ScrollPositionDelegate** | 滚动位置保存/恢复 | 5 个 mutableStateOf | 零 |
| 3 | **DraftInputDelegate** | 草稿/附件/@文件搜索/失败恢复 | 6 StateFlow + fileSearchJob | 低 |
| 4 | **SessionLifecycleDelegate** ★ | 会话身份/目录/懒创建/元信息 — **脊柱** | _sessionId + sessionDirectory + sessionLoaded + mutex | 高 |
| 5 | **ModelConfigDelegate** | Provider/Agent/Model/Variant/Command | 11 StateFlow + isModelExplicitlySelected | 中 |
| 6 | **MessageDataDelegate** | 消息 SSE 观察/加载/分页/发送态/工具展开 | 11 StateFlow + currentMessageLimit + sseJob | 中高 |
| 7 | **SessionActionsDelegate** | 28 个无状态 REST 操作 | 无（纯 UseCase 委托）| 低 |
| +8 | *UiSettingsDelegate*（可选）| 13 个 settings 流 + contextDetail | 无（只读派生）| 零 |

## 共享状态铁律

1. **`_sessionId` 由 SessionLifecycleDelegate 独占**，暴露 `sessionIdFlow: StateFlow<String>` — 6 个 combine 的 flatMapLatest 源
2. **跨域写入只通过意图方法**（如 `messageDataDelegate.onSendStarted()`），不直接写对方私有态
3. **协调方法留 VM**：`init`、`sendParts`、`sendMessage`、`revertMessage`、`abortSession`、`uiState` legacy 聚合

## VM 门面模式

VM 保留薄转发属性/方法（`val selectedAgent get() = modelConfigDelegate.selectedAgent`），UI 81 文件零改动。

## 执行顺序（风险递增）

1. TerminalDelegate — 验证 delegate + Hilt 注入模式
2. ScrollPositionDelegate — 零依赖
3. DraftInputDelegate — 依赖 sessionId（从 VM 传）
4. SessionLifecycleDelegate — 脊柱，稳定 sessionIdFlow
5. ModelConfigDelegate — 依赖 sessionIdFlow
6. MessageDataDelegate — 依赖 sessionIdFlow + 封装发送态
7. SessionActionsDelegate — 依赖 A/B/C 引用
8. VM 协调器精简 + 门面清理
