# SSE Scroll Stability — Iron Laws & Regression History

> **权威文档**。AGENTS.md 的 "SSE Scroll Stability" 段落和代码内注释均引用本文档。
> 任何修改聊天列表滚动行为的人，**必须先读本文档**。

## 1. 背景：SSE → UI 管道

流式输出（SSE token）到达 UI 的管道：

```
SSE token 到达
    ↓
48ms delta 批处理（MessageEventHandler.scheduleFlush）
    ↓ 单次 flush = 1 次 StateFlow 更新 = 1 次重组
高度补偿（layout{} modifier，仅 streaming message）
    ↓ requestScrollToItemNoCancel 抵消高度增长
渲染
```

违反管道任何一环，都会重新引入闪烁 / 块状输出 / 视窗跳动。

## 2. 四条铁律（2026-07 修正版）

### 铁律 1：`Markdown()` 必须用 `rememberMarkdownState(content, retainState=true)`

无状态的 `Markdown(content=...)` 每次重组都重新解析 markdown AST → 高度振荡 → 闪烁。

**位置**：`markdown/MarkdownContent.kt` 的所有渲染入口。

### 铁律 2：`scheduleFlush()` 绝不能取消正在运行的 timer

每个 token 都取消 in-flight timer 会在到达速率 > 1/48ms 时饿死 flush → 块状突发输出。

**位置**：`MessageEventHandler.kt:58`。实现：`if (batchJob?.isActive == true) return`。

### 铁律 3：`layout{}` 高度补偿只作用于 streaming message

对所有 assistant 消息作用会让已完成消息暴露在不稳定测量下 → 已完成消息也跳动。

**位置**：`ChatMessageList.kt` 的 `itemModifier` 条件分支 `if (isStreamingMsg)`。

**注意 multi-message turn**：`isStreamingMsg` 用 `(turnGroups[rawIndex] ?: listOf(msg)).any { it.message.id == streamingMsgId }`（提交 `92a30e48`）。因为 displayItems 的 turn 代表是 **oldest**，而 streaming 是 **newest**，单消息匹配会让 multi-message turn 补偿失效。`.any{}` 是正确的。displayItems 每 turn 只 1 个代表 item，所以 `.any{}` 不会导致补偿泄漏到多个 item。

### 铁律 4（★ 本次修正）：LaunchedEffect 必须双 key `(isScrollInProgress, isAtBottom)`

> **这条铁律曾经写反了**，是 2026-07 本次回归的直接原因。详见第 3 节。

`autoScrollEnabled` 和 `shouldCompensate` 的 LaunchedEffect **必须同时 key `isScrollInProgress` 和 `isAtBottom`**。

`isAtBottom` 作为 key 是**自愈机制**：当用户通过**非拖动方式**回到底部（fling 惯性、SSE 内容推送、补偿滚动），`isScrollInProgress` 不会变化（这些不是手势拖动），但 `isAtBottom` 会从 false→true。只有把 `isAtBottom` 放进 key，LaunchedEffect 才会在这些时刻重新触发，及时把 `shouldCompensate` 重置为 false、`autoScrollEnabled` 重置为 true。

**单 key（仅 `isScrollInProgress`）的后果**：
- 用户回到底部后，`shouldCompensate` 卡在 `true` → 每个 SSE token 的 `delta > 0` 都触发 `requestScrollToItemNoCancel` → 视窗周期性跳动
- `autoScrollEnabled` 卡在陈旧值 → `LaunchedEffect(messageCount)` 在不该 scroll 时 scroll

**位置**：
- `ChatScreen.kt` autoScroll LaunchedEffect
- `ChatMessageList.kt` shouldCompensate LaunchedEffect

## 3. 回归历史：为什么这个能力"反复出现又消失"

### 3.1 时间线

| 提交 | 行为 | 说明 |
|------|------|------|
| `6bad1cc` (v360) | **双 key** `(isScrollInProgress, isAtBottom)` | ✅ 用户验证正常 |
| `1b7e1ea5` | 补偿扩大到所有 assistant | 误修，后被纠正 |
| `dd55c3bf` | 修 viewport 漂移 | |
| `ac303cf1` | **移除 isAtBottom 作为条件** | 注释误判 isAtBottom 导致 premature reset |
| `46e65854` | **恢复 isAtBottom 作为条件** | commit 明说 "beta.360-verified behavior" |
| **`67e46011`** | **key 从双改单**（核心回归提交） | 基于未验证的理论假设，同时改了多项 |
| `76e1a35f` | **把单 key 写进 AGENTS.md 铁律** | ★ 固化了错误，成为反复回归的根源 |
| `92a30e48` | isStreamingMsg 改 `.any{}` | 修 multi-message turn，正确，但非本次回归因 |
| (本次修复) | **恢复双 key + 修正铁律** | 回到 v360 验证行为 |

### 3.2 根因机制（本次 2026-07 回归）

`67e46011` 的 commit 理由：*"isAtBottom 在 SSE 期间瞬态翻转会 lock autoScrollEnabled=true → viewport snaps to bottom"*。

这个理论**在实际运行中不成立**，实际发生的恰恰相反：

1. 用户滚到中间阅读历史（`shouldCompensate=true`）
2. SSE 在底部输出，补偿工作，视窗稳定
3. 用户通过 fling 惯性 / SSE 推送回到底部
4. **单 key 下**：`isScrollInProgress` 没变（非拖动），LaunchedEffect 不触发，`shouldCompensate` 保持 `true`
5. 底部仍在每个 token 执行 `requestScrollToItemNoCancel(firstVisibleItemIndex, scrollOffset + delta)` → 视窗周期性跳动

双 key（v360）下：步骤 3 中 `isAtBottom` false→true 触发 LaunchedEffect，`shouldCompensate=false`，补偿停止，视窗稳定。

### 3.3 反复回归的模式

```
  有人发现跳动
       ↓
  按"直觉"改 key 策略 / 补偿范围
       ↓
  短期看似修复（因为碰巧绕过当前场景）
       ↓
  写进 AGENTS.md 当铁律
       ↓
  下一个人按铁律"纠正"代码 → 又跳
       ↓
  （循环）
```

**打破循环的唯一方法**：铁律必须有 git 历史 + 用户验证证据支撑，不能基于理论假设。v360 是**唯一**有用户明确验证"正常"的版本，任何偏离 v360 行为的改动必须附带验证证据。

## 4. 关键代码位置速查

| 关注点 | 文件 | 行号（本次修复后） |
|--------|------|------|
| autoScroll LaunchedEffect | `ChatScreen.kt` | ~333 |
| shouldCompensate LaunchedEffect | `components/ChatMessageList.kt` | ~160 |
| streaming message 识别 | `components/ChatMessageList.kt` | ~448 |
| layout 高度补偿 modifier | `components/ChatMessageList.kt` | ~449-471 |
| 48ms flush | `MessageEventHandler.kt` | ~58 |
| Markdown stateful 渲染 | `markdown/MarkdownContent.kt` | ~364 |
| isAtBottom 定义 | `ChatScreen.kt` | ~326 |
| snapToBottom 扩展 | `util/ChatScrollUtils.kt` | ~26 |
| requestScrollToItemNoCancel 反射 | `components/ChatMessageList.kt` | ~698（LazyListReflection） |

## 5. 验证方法

### 5.1 静态检查（改完代码必做）

1. `grep -rn "LaunchedEffect(listState.isScrollInProgress)" app/src/main/kotlin/` —— **应为 0 结果**（必须都是双 key）
2. `grep -rn "LaunchedEffect(listState.isScrollInProgress, isAtBottom)" app/src/main/kotlin/` —— **应 ≥ 2 结果**（ChatScreen + ChatMessageList）
3. `grep -rn "rememberMarkdownState" app/src/main/kotlin/` —— Markdown 渲染入口必须有
4. `grep -rn "batchJob?.cancel" app/src/main/kotlin/` —— scheduleFlush 路径不应有

### 5.2 动态检查（真机）

1. **底部跟随**：SSE 输出时在底部 → 视窗平滑跟随新内容，不跳动
2. **阅读历史稳定**：滚到中间 → SSE 输出时视窗纹丝不动
3. **回归自愈**：滚到中间 → SSE 输出 → fling 回到底部 → 视窗稳定跟随，无残余跳动
4. **multi-message turn**：一个 turn 内多条消息 → 补偿正确作用

## 6. 决策记录

### 为什么不把 isStreamingMsg 改回单消息匹配（v360 原始版）？

因为 v360 之后 `92a30e48` 修了一个真实问题：displayItems 的 turn 代表是 **oldest**，streaming 是 **newest**，单消息匹配让 multi-message turn 补偿失效。`.any{}` 是正确修复。在当前 displayItems 结构下（每 turn 1 个代表 item），`.any{}` 不会泄漏到多个 item，行为安全。

### 为什么不保留 67e46011 的 `delta <= 500` 上限？

当前代码（HEAD）实际已经没有这个上限了（后续提交移除了），补偿条件是 `shouldCompensate && lastHeight > 0 && delta > 0`。本次修复不动这个条件，只动 key 策略——最小变更原则。

## 7. 变更日志

| 日期 | 提交 | 变更 |
|------|------|------|
| 2026-06-22 | v360 (`6bad1cc`) | 双 key，用户验证正常 |
| 2026-07-01 | `67e46011` | key 改单（回归引入） |
| 2026-07-01 | `76e1a35f` | 错误铁律写入 AGENTS.md |
| 2026-07-09 | 本次 | 恢复双 key + 修正铁律 + 本文档 |

---

**最后提醒**：如果你发现视窗又跳动了，**第一步**是运行第 5.1 节的静态检查。90% 的概率是某个 LaunchedEffect 又被改成了单 key。不要在未读本文档、未理解双 key 自愈机制的情况下修改 key 策略。
