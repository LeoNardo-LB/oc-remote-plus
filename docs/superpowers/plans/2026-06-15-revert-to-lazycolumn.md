# 回归 LazyColumn(reverseLayout=true) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除自定义 AnchoredLazyColumn，回归标准 LazyColumn(reverseLayout=true)，消除 SSE 流式输出时的闪烁和震荡。

**Architecture:** `reverseLayout=true` 原生提供底部锚定——item[0] 固定在视觉底部，高度增长时底部边缘不变，新 token 天然出现在视窗底部。根因是旧代码每次 SSE token 都调 `smoothScrollToBottom()`，与原生锚定竞争导致震荡；自定义 SubcomposeLayout 的 `remember(content)` 全量重建导致闪烁。回归标准 LazyColumn 后两者都消失。

**Tech Stack:** Jetpack Compose LazyColumn, LazyListState, reverseLayout

---

## 根因分析摘要

| 症状 | 根因 | 回归后如何消除 |
|------|------|---------------|
| 闪烁 | `remember(content)` 以 lambda 引用作 key，SSE 每个 token 都失效 → 全量 subcompose | LazyColumn 原生 item 复用/diff 机制 |
| 震荡 | `snapshotFlow` 自动滚动 + `smoothScrollToBottom()` 每个 token 与原生锚定竞争 | 移除激进自动滚动，reverseLayout 原生锚定 |

---

## File Structure

**Modify:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageList.kt` — AnchoredLazyColumn → LazyColumn，删除 snapToBottom 扩展
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — AnchoredLazyListState → LazyListState，移除激进自动滚动，清理 forceFollow 孤立代码

**Delete:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/components/AnchoredLazyColumn.kt`

**Unchanged (important context):**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionChecker.kt` — 独立文件，不受影响
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` L299-307 — `isAtBottom` derivedStateOf 保留（FAB 可见性判断需要）
- afterSend 逻辑中的 `smoothScrollToBottom()` 保留（发送消息后滚到底部）
- FAB 点击的 `snapToBottom()` 保留（用户点击跳到底部）

---

## ChatScreen.kt 编辑协议

> 遵循 `docs/chatscreen-editing-protocol.md`：
> 1. 编辑前必须 Read
> 2. 不可跨 agent 并行编辑
> 3. 每个编译单元后运行 `compileDevDebugKotlin`
> 4. 每次成功编译后 commit

---

### Task 1: 跨文件类型替换（ChatMessageList.kt + ChatScreen.kt）

> 两个文件的类型改动必须在同一次编译中完成，否则类型不匹配。

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageList.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

#### ChatMessageList.kt 改动

- [ ] **Step 1: 替换 import（L20-21）**

Edit ChatMessageList.kt:

old:
```kotlin
import dev.leonardo.ocremotev2.ui.components.AnchoredLazyColumn
import dev.leonardo.ocremotev2.ui.components.AnchoredLazyListState
```

new:
```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
```

- [ ] **Step 2: 替换参数类型（L84）**

old:
```kotlin
    listState: AnchoredLazyListState,
```

new:
```kotlin
    listState: LazyListState,
```

- [ ] **Step 3: 替换组件调用（L137-149）**

old:
```kotlin
                AnchoredLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                    contentPadding = PaddingValues(
                        start = SpacingTokens.MD.dp,
                        top = SpacingTokens.SM.dp,
                        end = SpacingTokens.MD.dp,
                        bottom = SpacingTokens.SM.dp
                    ),
                    reverseLayout = true,
                    isAtBottom = isAtBottom,
                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                ) {
```

new:
```kotlin
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                    contentPadding = PaddingValues(
                        start = SpacingTokens.MD.dp,
                        top = SpacingTokens.SM.dp,
                        end = SpacingTokens.MD.dp,
                        bottom = SpacingTokens.SM.dp
                    ),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                ) {
```

> 注意：移除了 `isAtBottom = isAtBottom` 参数。LazyColumn 没有这个参数。
> `isAtBottom` 参数本身保留在函数签名中（L90），因为 L392 的 FAB 显示逻辑仍需要它。

- [ ] **Step 4: 删除 AnchoredLazyListState.snapToBottom 扩展（L430-439）**

old:
```kotlin
/** Snap scroll to absolute bottom for reverseLayout AnchoredLazyColumn. */
private suspend fun AnchoredLazyListState.snapToBottom() {
    if (totalItemsCount == 0) return
    scrollToItem(0)
    repeat(3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
    }
}
```

删除整段，替换为空行。

#### ChatScreen.kt 改动

- [ ] **Step 5: 替换 import（L40）**

old:
```kotlin
import dev.leonardo.ocremotev2.ui.components.AnchoredLazyListState
```

new:
```kotlin
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
```

- [ ] **Step 6: 替换 listState 创建（L289）**

old:
```kotlin
    val listState = remember { AnchoredLazyListState() }
```

new:
```kotlin
    val listState = rememberLazyListState()
```

- [ ] **Step 7: 替换 smoothScrollToBottom / snapToBottom 签名和实现（L1123-1159）**

old:
```kotlin
/**
 * Animated auto-scroll to bottom. Used for automatic following during streaming
 * and force-follow window. Uses animation so the user can visually track the scroll
 * and optionally interrupt it by touching the screen.
 *
 * With reverseLayout=true, "bottom" = item 0.
 * Retries up to 48ms (3×16ms) to handle complex Markdown layout delays.
 * Reduced from 300ms/10 retries — the animation itself naturally handles
 * layout settling through its frames.
 */
private suspend fun AnchoredLazyListState.smoothScrollToBottom() {
    scrollToBottom()
    // Quick post-scroll check: if content still needs a final nudge
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

/**
 * Instant snap to bottom for explicit user actions (FAB click).
 * Uses hard scrollToItem — user intentionally wants to jump, no animation needed.
 */
private suspend fun AnchoredLazyListState.snapToBottom() {
    if (totalItemsCount == 0) return
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}
```

new:
```kotlin
/**
 * Animated auto-scroll to bottom. Used for after-send follow.
 *
 * With reverseLayout=true, "bottom" = item 0.
 * Retries up to 48ms (3×16ms) to handle complex Markdown layout delays.
 */
private suspend fun LazyListState.smoothScrollToBottom() {
    animateScrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}

/**
 * Instant snap to bottom for explicit user actions (FAB click).
 */
private suspend fun LazyListState.snapToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(0)
    var attempts = 0
    while (canScrollBackward && attempts < 3) {
        delay(16)
        if (!canScrollBackward) return
        scroll { scrollBy(-10_000f) }
        attempts++
    }
}
```

> 关键差异：
> - `AnchoredLazyListState` → `LazyListState`
> - `scrollToBottom()` → `animateScrollToItem(0)`（smoothScrollToBottom 用动画版）
> - `totalItemsCount` → `layoutInfo.totalItemsCount`（LazyListState 通过 layoutInfo 访问）
> - snapToBottom 保持 `scrollToItem(0)`（无动画，即时跳转）

- [ ] **Step 8: 编译验证**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatMessageList.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: AnchoredLazyColumn → LazyColumn, AnchoredLazyListState → LazyListState"
```

---

### Task 2: 移除激进自动滚动（ChatScreen.kt）

> 这是消除震荡的核心改动。

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: 删除 snapshotFlow 自动滚动 LaunchedEffect 及其注释（L467-538）**

old (L466-539):
```kotlin

    // Auto-scroll: follow new content only when the user is NOT actively scrolling.
    //
    // Root cause of spurious jumps: isAtBottom is a passive layout snapshot that can
    // transiently read as "true" during a fling gesture (the LazyColumn overshoots
    // to item 0+offset 0 for a frame). If a content change happens to emit at the
    // same frame, the old code treated it as "user is at bottom, scroll to follow"
    // and fired snapToBottom() — even though the user was in the middle of scrolling.
    //
    // Fix: use isScrollInProgress as the authoritative signal of user intent.
    // When the user is touching/dragging/flinging, isScrollInProgress = true →
    // auto-scroll is suppressed. The user is in control. When they release and
    // settle, isScrollInProgress → false, and only then does isAtBottom drive
    // the follow decision.
    //
    // Exceptions to the guard:
    // 1. forceFollow (just sent a message) — the user explicitly triggered this
    //    action, so we override isScrollInProgress to scroll to the response.
    // 2. isLoadingOlder/isLoading — pull-to-refresh and initial load are
    //    loading states where content changes should never trigger auto-scroll.
    LaunchedEffect(Unit) {
        var lastCount = 0
        var lastFingerprint = 0
        snapshotFlow {
            val msgs = messageState.messages
            val items = listState.totalItemsCount
            // Sum text lengths of ALL incomplete (streaming) assistant messages,
            // not just msgs.last(). This prevents losing auto-scroll tracking
            // when a tool-call inserts a new empty assistant message.
            val fingerprint = msgs.sumOf { msg ->
                if (msg.message is Message.Assistant && msg.message.time.completed == null) {
                    msg.parts.sumOf { part ->
                        when (part) {
                            is Part.Text -> part.text.length
                            is Part.Reasoning -> part.text.length
                            else -> 0
                        }
                    }
                } else { 0 }
            }
            Triple(items, fingerprint, msgs.size)
        }.conflate().collect { (count, fingerprint, _) ->
            val now = System.currentTimeMillis()

            // If user actively scrolled away during force-follow window, release it.
            // isScrollInProgress=true means the user is touching the screen; !isAtBottom
            // (with 64px tolerance) means they've scrolled noticeably away from bottom.
            if (now < forceFollowUntil && listState.isScrollInProgress && !isAtBottom) {
                userScrolledAwayInForceWindow = true
            }
            val forceFollow = now < forceFollowUntil && !userScrolledAwayInForceWindow

            // Auto-scroll is allowed only when:
            // (a) User is NOT actively scrolling (they've released the screen), OR force-follow overrides
            // (b) User is NOT loading older messages or initial loading
            val canAutoScroll =
                (!listState.isScrollInProgress || forceFollow) &&
                !messageState.isLoadingOlder &&
                !interaction.isLoading

            if (canAutoScroll) {
                if (count > lastCount && lastCount > 0 && (isAtBottom || forceFollow)) {
                    // New items appeared while user is passively at bottom (or force-follow)
                    listState.smoothScrollToBottom()
                } else if (count == lastCount && fingerprint != lastFingerprint && fingerprint > lastFingerprint && (isAtBottom || forceFollow)) {
                    // Same count but content grew (streaming delta) while user is passively at bottom
                    listState.smoothScrollToBottom()
                }
            }
            lastCount = count
            lastFingerprint = fingerprint
        }
    }

```

new:
```kotlin

```

> 删除整个注释块 + LaunchedEffect。reverseLayout=true 原生锚定不需要自动滚动。

- [ ] **Step 2: 删除 forceFollowUntil / userScrolledAwayInForceWindow 定义**

删除 L315:
```kotlin
    var forceFollowUntil by remember { mutableLongStateOf(0L) }
```

删除 L319:
```kotlin
    var userScrolledAwayInForceWindow by remember { mutableStateOf(false) }
```

> 这两个变量是 Task 2 Step 1 删除的 LaunchedEffect 的消费者。现在变孤立代码，必须清理。

- [ ] **Step 3: 清理 afterSend 逻辑中的 forceFollow 赋值（L791-793）**

old (L791-793):
```kotlin
                                coroutineScope.launch {
                                    userScrolledAwayInForceWindow = false
                                    forceFollowUntil = System.currentTimeMillis() + 2000
                                    val currentCount = listState.totalItemsCount
```

new:
```kotlin
                                coroutineScope.launch {
                                    val currentCount = listState.totalItemsCount
```

> 保留 `snapshotFlow { listState.totalItemsCount }.first { it > currentCount }` + `smoothScrollToBottom()` — 这是发送消息后等新 item 出现再滚到底部的正确逻辑。
> 仅删除 `userScrolledAwayInForceWindow = false` 和 `forceFollowUntil = ...` 两行孤立赋值。

- [ ] **Step 4: 编译验证**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

> 如果出现 unused import 警告（如 `mutableLongStateOf`、`conflate`），不影响编译。Kotlin 编译器不因 unused import 失败。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: remove aggressive auto-scroll during SSE streaming

reverseLayout=true provides native bottom anchoring — no smoothScrollToBottom
needed per SSE token. The old snapshotFlow auto-scroll competed with native
anchoring, causing viewport oscillation."
```

---

### Task 3: 删除 AnchoredLazyColumn.kt

**Files:**
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AnchoredLazyColumn.kt`

- [ ] **Step 1: 删除文件**

```powershell
Remove-Item -LiteralPath "app\src\main\kotlin\dev\minios\ocremote\ui\components\AnchoredLazyColumn.kt"
```

- [ ] **Step 2: 编译验证**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

> Task 1 已替换所有引用，此文件已无引用。

- [ ] **Step 3: 运行单元测试**

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --rerun
```

Expected: 所有测试 PASS，特别是 `ScrollPositionCheckerTest`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete AnchoredLazyColumn (reverted to LazyColumn)"
```

---

### Task 4: 构建 + 发布 dev release

- [ ] **Step 1: Bump version**

修改 `version.properties`:

old:
```properties
VERSION_CODE=445
VERSION_NAME=2.0.0-beta.245
```

new:
```properties
VERSION_CODE=446
VERSION_NAME=2.0.0-beta.246
```

- [ ] **Step 2: 构建 APK**

Run:
```powershell
.\gradlew --stop
.\gradlew :app:assembleDevRelease
```

Expected: `BUILD SUCCESSFUL`

Timeout: 300000ms

- [ ] **Step 3: 验证 APK 版本号**

Run:
```powershell
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
aapt2 dump badging "app\build\outputs\apk\dev\release\app-dev-release.apk" | Select-String "versionName"
```

Expected: `versionName='2.0.0-beta.246-dev'`

- [ ] **Step 4: Commit + Push + Tag + Release**

```powershell
git add version.properties
git commit -m "chore: bump version to v2.0.0-beta.246"
git push origin master
git tag -a "v2.0.0-beta.246-dev" -m "v2.0.0-beta.246-dev — revert to LazyColumn"
git push origin "v2.0.0-beta.246-dev"
gh release create "v2.0.0-beta.246-dev" "app\build\outputs\apk\dev\release\app-dev-release.apk" --title "v2.0.0-beta.246-dev" --notes "revert to LazyColumn(reverseLayout=true) — fix flicker and oscillation during SSE streaming" --prerelease
```

---

## 验证清单（用户在设备上确认）

发布后用户安装 APK，验证以下场景：

1. **SSE 流式输出（在底部）**：AI 回复时屏幕稳定，无闪烁，新 token 平滑出现在底部
2. **SSE 流式输出（向上滚动）**：向上滚动阅读旧消息，SSE 更新不打扰视窗
3. **发送消息后**：自动滚到底部看到新回复
4. **点击 FAB**：跳到底部
5. **无崩溃**：长时间 SSE 流不 crash

---

## 风险与回退

| 风险 | 概率 | 回退方案 |
|------|------|---------|
| reverseLayout 在某些设备上行为不一致 | 低 | 标准组件，数百万应用验证 |
| afterSend 的 smoothScrollToBottom 不够及时 | 低 | 加 retry 次数或改用 scrollToItem |
| FAB 显示时机变化 | 低 | isAtBottom 逻辑不变 |
| 完全回退 | — | `git revert` Task 1-3 的 commits |
