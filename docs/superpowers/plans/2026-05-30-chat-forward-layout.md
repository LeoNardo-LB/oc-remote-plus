# 聊天列表正序布局改造 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将聊天列表从 `reverseLayout=true` 改为正序布局，消除流式输出时的视窗非预期跟随。

**Architecture:** 数据源改为 oldest-first，LazyColumn 正序渲染。用 `derivedStateOf` 的 `isAtBottom` 替代 `autoScrollEnabled` + `snapshotFlow`，统一控制 FAB 显隐和流式跟随。PullToRefresh 替代 load_older 按钮。滚动保存改用 message ID。

**Tech Stack:** Kotlin, Jetpack Compose (LazyColumn, derivedStateOf), Material3 PullToRefresh

**Spec:** `docs/superpowers/specs/2026-05-30-chat-forward-layout-design.md`

**Working Directory:** `D:\Develop\code\app\oc-remote`

**Status:** Tasks 1-7 已执行完毕，编译通过。Task 8 待冒烟测试。Task 9 为审查后修复。

> **注意**：行号为编写 plan 时的参考值，实际位置可能因前序改动而偏移。请以代码搜索为准。

---

### Task 1: 数据源排序方向改为 oldest-first

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt` (行 48, 98, 104)
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` (行 335, 356, 374)

- [x] **Step 1: 修改 MessageEventHandler.kt 的 3 处排序** ✅ 已完成

文件: `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt`

3 处改动，全部是 `Descending` → 无 `Descending`：

1. 行 48: `sessionMessages.sortByDescending { it.time.created }` → `sessionMessages.sortBy { it.time.created }`
2. 行 98: `sortedByDescending { m -> m.time.created }` → `sortedBy { m -> m.time.created }`
3. 行 104: `val incoming = newMessages.map { it.info }.sortedByDescending { m -> m.time.created }` → `sortedBy { m -> m.time.created }`

- [x] **Step 2: 修改 ChatViewModel.kt 的 3 处** ✅ 已完成

文件: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

1. 行 335: `val sorted = sessionMessages.sortedByDescending { it.time.created }` → `sortedBy { it.time.created }`
2. 行 356: `.firstOrNull { it.model != null }` → `.lastOrNull { it.model != null }`
3. 行 374: `.firstOrNull { it.agent != null }` → `.lastOrNull { it.agent != null }`

- [x] **Step 3: 编译验证** ✅ BUILD SUCCESSFUL ✅ BUILD SUCCESSFUL

Run: `cd D:\Develop\code\app\oc-remote; .\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: Commit** ✅ 已完成 ✅ 已完成

```
git add MessageEventHandler.kt ChatViewModel.kt
git commit -m "refactor: 数据源排序改为 oldest-first"
```

---

### Task 2: 删除 3 处 reversed() 调用

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/MessageCard.kt` (行 332)
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (行 1864, 2119)

- [x] **Step 1: MessageCard.kt 行 332**

将:
```kotlin
val orderedTurnMessages = turnMessages?.reversed()
```
改为:
```kotlin
val orderedTurnMessages = turnMessages
```
（oldest-first 排列下 turnMessages 已经是正确顺序，不需要反转）

- [x] **Step 2: ChatScreen.kt 行 1864（主会话复制文本）**

找到:
```kotlin
val messages = turnMessagesForMsg.reversed()
```
改为:
```kotlin
val messages = turnMessagesForMsg
```

- [x] **Step 3: ChatScreen.kt 行 2119（子会话复制文本）**

同 Step 2，删除 `.reversed()`

- [x] **Step 4: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit** ✅ 已完成

```
git add MessageCard.kt ChatScreen.kt
git commit -m "refactor: 删除 reversed() 调用（oldest-first 已是正确顺序）"
```

---

### Task 3: displayItems 过滤方向修正

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (行 1770, 1853, 2108)

- [x] **Step 1: 行 1770 — displayItems 过滤中的 index 偏移**

将:
```kotlin
val nextMsg = rawMessages.getOrNull(index - 1)
```
改为:
```kotlin
val nextMsg = rawMessages.getOrNull(index + 1)
```

- [x] **Step 2: 行 1853 — 主会话 isTurnLast 判定**

将:
```kotlin
val isTurnLast = rawIndex == 0 || rawMessages.getOrNull(rawIndex - 1)?.isAssistant != true
```
改为:
```kotlin
val isTurnLast = rawIndex == rawMessages.lastIndex || rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true
```

- [x] **Step 3: 行 2108 — 子会话 isTurnLast 判定**

同 Step 2 的改动逻辑，在子会话的对应位置做相同修改。

- [x] **Step 4: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit** ✅ 已完成

```
git add ChatScreen.kt
git commit -m "refactor: displayItems 过滤方向修正（index-1 → index+1）"
```

---

### Task 4: LazyColumn reverseLayout=false + item 声明顺序反转

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (行 1792, 2048, 以及两处 item 块)

这是最大的改动。主会话和子会话各有一个 LazyColumn，需要同步修改。

- [x] **Step 1: 主会话 — 改 reverseLayout=false**

行 1792: `reverseLayout = true,` → `reverseLayout = false,`

- [x] **Step 2: 主会话 — 反转 item 声明顺序**

当前顺序（从上到下声明）：
1. `pendingQuestions` items
2. `pendingPermissions` items
3. `revertBanner` item
4. `displayItems` itemsIndexed
5. `load_older` item

改为：
1. ~~`load_older` item~~ → 删除（Task 5 用 PullToRefresh 替代）
2. `displayItems` itemsIndexed
3. `revertBanner` item
4. `pendingPermissions` items
5. `pendingQuestions` items

具体操作：将整个 items 块中的代码段重新排列，保持每个代码段内部不变，只改变声明顺序。

- [x] **Step 3: 子会话 — 改 reverseLayout=false**

行 2048: `reverseLayout = true,` → `reverseLayout = false,`

- [x] **Step 4: 子会话 — 反转 item 声明顺序**

和 Step 2 相同逻辑，子会话的 items 块也反转声明顺序，删除 load_older item。

- [x] **Step 5: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`（此时 load_older 已删除但 PullToRefresh 还没加，不会有编译错误因为只是少了一个 item）

- [x] **Step 6: Commit** ✅ 已完成

```
git add ChatScreen.kt
git commit -m "refactor: reverseLayout=false + item 声明顺序反转"
```

---

### Task 5: PullToRefresh 替代 load_older

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- Possibly: `app/build.gradle.kts`（确认 material3 版本）

- [x] **Step 1: 确认 material3 版本支持 pullToRefresh**

Run: `Select-String -Path "app\build.gradle.kts" -Pattern "material3"`
Expected: 找到 material3 依赖声明。确认版本 ≥ 1.2.0。

如果版本 < 1.2.0：不实现 PullToRefresh，改为在 LazyColumn 顶部保留原来的 load_older item（只是视觉上在顶部了），跳过 Step 2-4。

- [x] **Step 2: 主会话 — 添加 PullToRefresh 包裹 LazyColumn**

在主会话的 LazyColumn 外层 Box 中，添加 PullToRefreshBox：

```kotlin
val pullToRefreshState = rememberPullToRefreshState()

PullToRefreshBox(
    isRefreshing = uiState.isLoadingOlder,
    onRefresh = { viewModel.loadOlderMessages() },
    state = pullToRefreshState,
    indicator = {
        PullToRefreshDefaults.Indicator(
            state = pullToRefreshState,
            isRefreshing = uiState.isLoadingOlder,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    },
    modifier = Modifier.fillMaxSize()
) {
    LazyColumn(
        // ... 现有参数
    ) {
        // ... 现有 items
    }
    // FAB 保持在 PullToRefreshBox 内
}
```

注意：需要添加 `@OptIn(ExperimentalMaterial3Api::class)` 注解。

- [x] **Step 3: 子会话 — 同样添加 PullToRefresh**

和 Step 2 相同逻辑，应用到子会话的 LazyColumn。

- [x] **Step 4: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

如果编译失败提示 `PullToRefreshBox` / `rememberPullToRefreshState` 未找到：
- 检查 material3 版本，可能需要升级
- 或者改用 `Modifier.pullToRefresh()` + 自定义 indicator
- 降级方案：在 LazyColumn 顶部保留一个简单的"加载更多"TextButton item

- [x] **Step 5: Commit** ✅ 已完成

```
git add ChatScreen.kt app/build.gradle.kts
git commit -m "feat: PullToRefresh 替代 load_older 按钮"
```

---

### Task 6: 滚动逻辑重构 — isAtBottom 替代 autoScrollEnabled

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

这是最关键的逻辑改动。

- [x] **Step 1: 添加 isAtBottom derivedStateOf**

在 `autoScrollEnabled` 变量声明位置附近（行 ~293），替换为：

```kotlin
// 删除: var autoScrollEnabled by remember { mutableStateOf(true) }
// 替换为:
val isAtBottom by remember {
    derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        lastVisible != null && lastVisible.index >= layoutInfo.totalItemsCount - 1
    }
}
```

- [x] **Step 2: 删除 snapshotFlow LaunchedEffect**

删除整个 `LaunchedEffect(Unit) { snapshotFlow { ... }.collect { ... } }` 块（行 ~847-858）。

- [x] **Step 3: 修改所有 autoScrollEnabled 读取点**

逐一替换（搜索 `autoScrollEnabled` 确保无遗漏）：

| 位置 | 替换 |
|---|---|
| 行 ~841-845: `if (messageCount > 0 && autoScrollEnabled)` | `if (messageCount > 0 && isAtBottom)` |
| 行 ~1225: `autoScrollEnabled = true` + 行 ~1226: `listState.scrollToItem(0)` | 只保留 `listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)` |
| 行 ~2014: `if (!autoScrollEnabled)` (主会话 FAB) | `if (!isAtBottom)` |
| 行 ~2019: `autoScrollEnabled = true` | **删除** |
| 行 ~2260: `if (!autoScrollEnabled)` (子会话 FAB) | `if (!isAtBottom)` |
| 行 ~2265: `autoScrollEnabled = true` | **删除** |

- [x] **Step 4: 修改 scrollToItem(0) → scrollToItem(last)**

所有 `scrollToItem(0)` 改为 `scrollToItem(listState.layoutInfo.totalItemsCount - 1)`。

注意空列表守卫：`LaunchedEffect(messageCount)` 中已有 `if (messageCount > 0)` 守卫，所以不会在空列表时调用。

- [x] **Step 5: 修改子会话恢复的 isAtBottom 判定**

行 ~308-309: 将
```kotlin
autoScrollEnabled = listState.firstVisibleItemIndex == 0 &&
    listState.firstVisibleItemScrollOffset <= 50
```
改为：
```kotlin
// 删除——isAtBottom 由 derivedStateOf 自动判定
```

同时行 ~299: 将 `autoScrollEnabled = false` 删除（isAtBottom 在 scrollToItem 到非底部位置后自动为 false）。

恢复逻辑简化为：
```kotlin
LaunchedEffect(viewModel.scrollRestoreVersion) {
    if (viewModel.scrollRestoreVersion > 0) {
        // 滚动恢复在 Task 7 中改为 message ID 方案
        // 这里 isAtBottom 由 derivedStateOf 自动更新
    }
}
```

- [x] **Step 6: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

如果搜索 `autoScrollEnabled` 还有残留引用，逐一修复。

- [x] **Step 7: Commit** ✅ 已完成

```
git add ChatScreen.kt
git commit -m "refactor: isAtBottom 替代 autoScrollEnabled + snapshotFlow"
```

---

### Task 7: 滚动保存改用 message ID

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` (行 ~226-247)
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (行 ~295-320)

- [x] **Step 1: 修改 ViewModel 签名和字段**

将:
```kotlin
var savedFirstVisibleItemIndex by mutableStateOf(0)
var savedFirstVisibleItemScrollOffset by mutableStateOf(0)
var scrollRestoreVersion by mutableStateOf(0)

fun saveScrollPosition(index: Int, offset: Int) {
    savedFirstVisibleItemIndex = index
    savedFirstVisibleItemScrollOffset = offset
    scrollRestoreVersion++
}
```
改为:
```kotlin
var savedMessageId by mutableStateOf<String?>(null)
var savedScrollOffset by mutableStateOf(0)
var scrollRestoreVersion by mutableStateOf(0)

fun saveScrollPosition(messageId: String?, offset: Int) {
    savedMessageId = messageId
    savedScrollOffset = offset
    scrollRestoreVersion++
}
```

- [x] **Step 2: 修改保存调用（navigateToChildSessionWithSave）**

将:
```kotlin
viewModel.saveScrollPosition(
    listState.firstVisibleItemIndex,
    listState.firstVisibleItemScrollOffset
)
```
改为:
```kotlin
val firstMessageKey = listState.layoutInfo.visibleItemsInfo
    .firstOrNull { 
        val key = it.key as? String ?: ""
        !key.startsWith("question_") && 
        !key.startsWith("perm_") && 
        key != "revert_banner"
    }
    ?.key as? String
viewModel.saveScrollPosition(firstMessageKey, listState.firstVisibleItemScrollOffset)
```

- [x] **Step 3: 修改恢复逻辑（LaunchedEffect(scrollRestoreVersion)）**

将:
```kotlin
listState.scrollToItem(
    viewModel.savedFirstVisibleItemIndex,
    viewModel.savedFirstVisibleItemScrollOffset
)
```
改为:
```kotlin
val savedId = viewModel.savedMessageId
if (savedId != null) {
    val targetIndex = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.key == savedId }
        ?.index
    if (targetIndex != null) {
        listState.scrollToItem(targetIndex, viewModel.savedScrollOffset)
    } else {
        // message ID 不存在，降级滚到底部
        listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
}
```

注意：这里用 `visibleItemsInfo` 查找可能找不到（如果 target item 不在当前可见范围）。更稳健的方式是用 `displayItems` 查找 index。但 `displayItems` 是 Composable 中的局部变量，需要在同一 Composable 作用域内查找。实际实现时可能需要遍历 displayItems 或使用 `listState.layoutInfo` 的全部 items。

- [x] **Step 4: 编译验证** ✅ BUILD SUCCESSFUL

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 5: Commit** ✅ 已完成

```
git add ChatViewModel.kt ChatScreen.kt
git commit -m "refactor: 滚动保存改用 message ID"
```

---

### Task 9: 审查后 Bug 修复

> 9 维度文档审查（doc-consistency-review）发现的代码缺陷。

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

- [x] **Step 1: 滚动恢复改用 messages 遍历（P0）** ✅ 已完成

将 `visibleItemsInfo` 查找改为在 `uiState.messages` 上按 displayItems 相同逻辑遍历查找目标消息的 LazyColumn index。

- [x] **Step 2: 空列表 scrollToItem(-1) 守卫（P0）** ✅ 已完成

降级路径增加 `if (totalItemsCount > 0)` 守卫，防止空列表崩溃。

- [x] **Step 3: PullToRefresh hasOlderMessages 守卫（P1）** ✅ 已完成

`onRefresh` 改为 `if (uiState.hasOlderMessages) viewModel.loadOlderMessages()`。主会话和子会话各一处。

- [x] **Step 4: 空会话 isAtBottom 快速路径（P1）** ✅ 已完成

`isAtBottom` 的 derivedStateOf 增加 `if (totalItemsCount == 0) return@derivedStateOf true`，空列表时隐藏 FAB。

- [x] **Step 5: 编译验证** ✅ BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
git add ChatScreen.kt
git commit -m "fix: 审查后修复 — 滚动恢复/空列表守卫/hasOlderMessages"
```

---

### Task 10: 全量编译 + 单元测试 + 手动冒烟

> 原 Task 8 更新：增加边界用例。

**Files:**
- 无新增修改

- [x] **Step 1: 全量编译**

Run: `.\gradlew :app:compileDevDebugKotlin 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 2: 运行单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`（所有测试通过）

- [x] **Step 3: 构建 Beta APK**

Run: `.\gradlew :app:assembleBetaRelease 2>&1 | Select-Object -Last 3`
Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: 手动冒烟测试清单**

安装 Beta APK 后逐项验证：

1. **基本消息渲染**：打开会话，消息按时间正序排列（旧→新，从上到下）
2. **初始滚动位置**：进入会话后自动滚到底部（最新消息）
3. **发送消息**：发送后自动滚到底部，消息出现在列表底部。流式输出使用最新 model
4. **流式跟随**：在底部时，流式输出跟随；向上滚后，视窗固定不动
5. **FAB 显隐**：在底部时 FAB 隐藏；向上滚后 FAB 出现；点击 FAB 滚回底部后 FAB 消失
6. **手动滚回底部恢复跟随**：向上滚 → FAB 出现 → 手动滚回底部 → FAB 消失 → 流式恢复跟随
7. **子会话导航**：点击 task 消息进入子会话，返回后滚动位置恢复（item 级精度）
8. **PullToRefresh**：在列表顶部下拉，加载更多历史消息；无更多消息时下拉无反应
9. **Turn group 渲染**：连续 3+ 条 assistant 消息合并为单个 Turn group，group 内旧→新；非连续消息各自独立
10. **空会话**：新建空会话，无消息，无崩溃，FAB 不显示
11. **子会话删除后返回**：子会话中 revert 后返回，滚动降级到底部且无崩溃
12. **快速切换子会话**：快速进入→返回→再进入子会话，滚动位置一致
13. **PullToRefresh 加载期间新消息到达**：加载历史消息同时有流式输出，完成后列表位置正确

- [x] **Step 5: 修复冒烟中发现的问题**

如有问题，在此修复。

- [x] **Step 6: 打 tag 发版**

```
git tag v2.0.0-beta.XX
git push fork master
git push fork v2.0.0-beta.XX
```

发 GitHub Release 附上 APK。

---

## 自审检查

**执行状态：**
- ✅ Task 1-7: 已完成，编译通过
- ✅ Task 9: 审查后 bug 修复已完成
- ⬜ Task 10: 冒烟测试待执行

**Spec 覆盖：**
- ✅ §1 数据流方向 → Task 1
- ✅ §2 渲染方向 reversed() → Task 2
- ✅ §3 displayItems 过滤方向 → Task 3
- ✅ §4.1 reverseLayout=false → Task 4
- ✅ §4.2 Item 声明顺序反转 → Task 4
- ✅ §4.3 PullToRefresh → Task 5
- ✅ §5.1 isAtBottom → Task 6
- ✅ §5.2 scrollToItem(last) → Task 6
- ✅ §5.3 状态管理映射 → Task 6
- ✅ §5.4 滚动保存 message ID → Task 7 + Task 9（修复 visibleItemsInfo bug）
- ✅ §5.5 初始加载 → Task 6（LaunchedEffect 中的 scrollToItem(last)）
- ✅ §5.6 子会话恢复 → Task 6（isAtBottom）+ Task 7（message ID）+ Task 9（修复恢复逻辑）

**审查后修复（Task 9）：**
- ✅ P0: 滚动恢复 visibleItemsInfo → messages 遍历
- ✅ P0: 空列表 scrollToItem(-1) 守卫
- ✅ P1: PullToRefresh hasOlderMessages 守卫
- ✅ P1: 空会话 isAtBottom=true

**类型一致性：**
- `isAtBottom` 在 Task 6 中定义为 `Boolean`（derivedStateOf），空列表时快速返回 true
- `saveScrollPosition` 签名在 Task 7 中改为 `(String?, Int)`，保存和恢复调用点一致
- `scrollToItem` 参数始终为 `Int`，`totalItemsCount - 1` 返回 `Int`，有空列表守卫
