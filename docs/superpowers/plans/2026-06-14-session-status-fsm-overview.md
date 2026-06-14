# Session Status FSM Implementation Plan — Overview

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 FSM + 多层容错替换当前散乱的会话状态管理，解决"会话已结束仍显示输出中"和"消息一直排队中"等问题。

**Architecture:** 两层 FSM（Core: Idle/Busy/Retry 对齐服务器 + Activity: Waiting/Streaming/ToolCalling/Compacting 派生详情），新增 `SessionStatusFSM`（纯函数）+ `SessionStatusManager`（@Singleton 协调器），5 层容错（SSE 驱动 / 新鲜度检测 / REST 校验 / 心跳超时 / 交叉验证）。

**Tech Stack:** Kotlin, Hilt, Coroutines/Flow, JUnit 4 + MockK + Turbine

**Spec:** `docs/superpowers/specs/2026-06-14-session-status-fsm-design.md`

---

## Phase 概览

| Phase | 计划文件 | 核心内容 | 可独立验证 |
|-------|---------|---------|-----------|
| P1 | `2026-06-14-fsm-p1-core.md` | FSM 定义 + Manager + 并行运行 | ✅ 单元测试 + 日志对比 |
| P2 | `2026-06-14-fsm-p2-ui-integration.md` | ChatViewModel 从 statusFlow 读取 | ✅ 编译 + UI 手动测试 |
| P3 | `2026-06-14-fsm-p3-remove-premature-idle.md` | 移除 isPrematureIdle + 启用 L5 | ✅ 手动测试 |
| P4 | `2026-06-14-fsm-p4-remove-periodic-sync.md` | 移除 30s 轮询 + 启用 L2/L3 | ✅ 断网测试 |
| P5 | `2026-06-14-fsm-p5-bugfixes.md` | 4 个确定性 bug 修复 | ✅ 逐个验证 |

**执行顺序**：P1 → P2 → P3 → P4 → P5（严格顺序，每 Phase 依赖前一 Phase）

**并行约束**：不要并行执行不同 Phase。ChatScreen.kt / ChatViewModel.kt 的编辑遵循 `docs/chatscreen-editing-protocol.md`（不并行编辑、先 Read 再 Edit、每次编译）。
