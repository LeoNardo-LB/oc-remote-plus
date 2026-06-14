# 自动滚动 isAtBottom 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 reverseLayout 下 isAtBottom 判定错误导致"最后一条消息任意一像素可见就被 SSE 流拖动滚动"的 bug。

**Architecture:** 提取 offset 判定逻辑为纯函数 `ScrollPositionChecker`，便于单元测试。修改 `ChatScreen.kt` 的 `isAtBottom` derivedStateOf 调用纯函数。纯函数方式避免了需要 Compose 运行时的 instrumented test。

**Tech Stack:** Kotlin, Jetpack Compose (LazyColumn reverseLayout), JUnit 4

**Spec:** `docs/superpowers/specs/2026-06-14-auto-scroll-isAtBottom-fix.md`

---

## File Structure

| 文件 | 职责 | 操作 |
|------|------|------|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionChecker.kt` | 纯函数：判断 reverseLayout LazyColumn 是否贴底 | Create |
| `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionCheckerTest.kt` | 单元测试：覆盖所有 offset 场景 | Create |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | 使用 ScrollPositionChecker 替换内联判定 | Modify (line 299-309) |

---

## Task 1: 写失败测试 — ScrollPositionCheckerTest

**Files:**
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionCheckerTest.kt`

- [ ] **Step 1: 创建测试文件**

```kotlin
package dev.minios.ocremote.ui.screens.chat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollPositionCheckerTest {

    // ========== reverseLayout = true 场景 ==========

    @Test
    fun `reverseLayout - empty list is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = -1,
            firstVisibleOffset = 0,
            totalItemsCount = 0,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 0 is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset -1 (layout jitter) is at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -1,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset -10 (user scrolled) is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -10,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset -223 (user scrolled far) is NOT at bottom`() {
        // 模拟器验证的实际值
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = -223,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 1 visible (item 0 gone) is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 1,
            firstVisibleOffset = 0,
            totalItemsCount = 10,
            tolerance = 1
        )
        assertEquals(false, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 50 within tolerance 64 is at bottom`() {
        // 正数 offset（overscroll 场景）在容差内仍算贴底
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 50,
            totalItemsCount = 10,
            tolerance = 64
        )
        assertEquals(true, result)
    }

    @Test
    fun `reverseLayout - item 0 at offset 100 beyond tolerance 64 is NOT at bottom`() {
        val result = ScrollPositionChecker.isAtBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 100,
            totalItemsCount = 10,
            tolerance = 64
        )
        assertEquals(false, result)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.ScrollPositionCheckerTest" --rerun`

Expected: **FAIL** — `ScrollPositionChecker` 未定义（Unresolved reference）

---

## Task 2: 实现 ScrollPositionChecker

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionChecker.kt`

- [ ] **Step 1: 创建纯函数文件**

```kotlin
package dev.minios.ocremote.ui.screens.chat.util

/**
 * Pure function for checking whether a reverseLayout LazyColumn is at the bottom.
 *
 * In reverseLayout=true, LazyListItemInfo.offset for item 0:
 * - Equals 0 when at bottom (scroll position = 0)
 * - Becomes negative when user scrolls to see older messages (forward scroll, delta < 0)
 *
 * The old code used `offset <= 64` which incorrectly included ALL negative values,
 * causing isAtBottom to return true even when the user had scrolled away.
 *
 * Correct logic: offset must be >= -tolerance (allowing tiny layout jitter)
 * AND <= +tolerance (allowing slight overscroll position).
 *
 * Verified via emulator: offset values of -12, -47, -92, -121, -157, -223
 * all incorrectly returned isAtBottom=true with the old `<= 64` logic.
 *
 * @param firstVisibleIndex Index of the first visible item (from visibleItemsInfo.firstOrNull())
 * @param firstVisibleOffset Offset of the first visible item
 * @param totalItemsCount Total items in the LazyColumn
 * @param tolerance Pixel tolerance for both directions (default: 1 for layout jitter)
 * @return true if the list is effectively at the bottom
 */
object ScrollPositionChecker {

    fun isAtBottom(
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        totalItemsCount: Int,
        tolerance: Int = 1
    ): Boolean {
        if (totalItemsCount == 0) return true
        if (firstVisibleIndex < 0) return false
        // reverseLayout: item 0 is at visual bottom
        // offset = 0 when at bottom, negative when scrolled up
        // Allow [-tolerance, +tolerance] range for layout jitter and slight overscroll
        return firstVisibleIndex == 0 &&
            firstVisibleOffset >= -tolerance &&
            firstVisibleOffset <= tolerance
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*.ScrollPositionCheckerTest" --rerun`

Expected: **PASS** — 所有 8 个测试通过

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionChecker.kt app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/util/ScrollPositionCheckerTest.kt
git commit -m "feat: add ScrollPositionChecker pure function for reverseLayout isAtBottom

- Pure function extracted for unit testability
- Fixes reverseLayout offset semantics: negative offset = user scrolled away
- Old code (offset <= 64) incorrectly included all negative values
- Verified via emulator: offset -223 returned isAtBottom=true (bug)
- New code (offset >= -1 && offset <= 1) correctly returns false"
```

---

## Task 3: 修改 ChatScreen.kt 使用 ScrollPositionChecker

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` (line 299-309)

- [ ] **Step 1: Read ChatScreen.kt 确认当前代码**

Run: Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` offset=295 limit=20

确认 line 299-309 的代码与预期一致。

- [ ] **Step 2: 替换 isAtBottom derivedStateOf**

将 line 299-309：

```kotlin
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) true
            else {
                info.visibleItemsInfo.firstOrNull()?.let {
                    it.index == 0 && it.offset <= 64
                } ?: false
            }
        }
    }
```

替换为：

```kotlin
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            ScrollPositionChecker.isAtBottom(
                firstVisibleIndex = first?.index ?: -1,
                firstVisibleOffset = first?.offset ?: 0,
                totalItemsCount = info.totalItemsCount
            )
        }
    }
```

- [ ] **Step 3: 添加 import**

在 ChatScreen.kt 的 import 区域添加：

```kotlin
import dev.minios.ocremote.ui.screens.chat.util.ScrollPositionChecker
```

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`

Expected: **BUILD SUCCESSFUL**（只有 warnings，无 errors）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: use ScrollPositionChecker for isAtBottom in ChatScreen

- Replace inline offset <= 64 with ScrollPositionChecker.isAtBottom
- Fixes: SSE stream dragging viewport when last message has any pixel visible
- Root cause: reverseLayout offset is negative when user scrolls, but <= 64 included negatives
- Verified: canScrollBackward=true but isAtBottom=true was the contradiction"
```

---

## Task 4: 手动验证

- [ ] **Step 1: 编译 debug APK**

Run: `.\gradlew :app:assembleDevDebug`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装到模拟器**

Run: `adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`

Expected: Success

- [ ] **Step 3: 手动测试场景**

| 场景 | 操作 | 预期 |
|------|------|------|
| 贴底 streaming | 在底部时 agent 回复 | 视窗跟随滚动 |
| 上滑后 streaming | 上滑后 agent 回复 | 视窗**不**跟随 |
| 发送后 forceFollow | 发消息后立即上滑 | forceFollow 立即释放 |
| FAB 显隐 | 上滑一点 | FAB 立即显示（之前需要上滑很远） |

- [ ] **Step 4: 如果测试通过，final commit（如有改动）**

```bash
git log --oneline -3
# 确认有 3 个 commit: ScrollPositionChecker + test + ChatScreen fix
```

---

## Self-Review

### Spec coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| `offset <= 64` → `offset >= -tolerance` | Task 2 (ScrollPositionChecker.isAtBottom) |
| BOTTOM_TOLERANCE = 1 | Task 2 (默认参数 tolerance=1) |
| 修改 ChatScreen.kt:305 | Task 3 (Step 2) |
| 不需要第三方库 | ✅ 纯 Kotlin 纯函数 |
| forceFollow 不需要额外改动 | ✅ 未触碰 forceFollow 逻辑 |

### Placeholder scan

- ✅ 无 TBD/TODO
- ✅ 所有代码完整
- ✅ 所有命令精确

### Type consistency

- `ScrollPositionChecker.isAtBottom(firstVisibleIndex: Int, firstVisibleOffset: Int, totalItemsCount: Int, tolerance: Int)` — Task 1 测试和 Task 2 实现一致
- Task 3 调用参数名与 Task 2 定义一致
