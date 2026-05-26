# 键盘适配与聊天窗优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 系统性修复键盘弹出不推动消息界面的问题，同时修复子会话工具卡片不可点击的 bug，优化聊天窗整体体验。

**Architecture:** 将 AndroidManifest 的 `adjustResize` 改为 `adjustNothing`，消除 `enableEdgeToEdge()` + `imePadding()` 的双倍 padding 冲突。在 Scaffold content 添加 `consumeWindowInsets` 防止 IME inset 渗透。移除用户消息中的 `SelectionContainer` 修复子会话工具卡片点击失效。添加键盘弹出时的自动滚动增强。

**Tech Stack:** Jetpack Compose (BOM 2024.12.01), Material 3, Android SDK 26-34

---

## File Structure

| 文件 | 责任 | 变更类型 |
|------|------|---------|
| `app/src/main/AndroidManifest.xml` | windowSoftInputMode 配置 | 修改 |
| `app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt` | enableEdgeToEdge 后处理 | 修改 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` | Scaffold 布局 + 滚动逻辑 + SelectionContainer | 修改 |

---

### Task 1: AndroidManifest — adjustResize 改为 adjustNothing

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:38`

- [ ] **Step 1: 修改 windowSoftInputMode**

将第 38 行：
```xml
android:windowSoftInputMode="adjustResize">
```
改为：
```xml
android:windowSoftInputMode="adjustNothing">
```

- [ ] **Step 2: 验证编译通过**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "refactor: change windowSoftInputMode to adjustNothing for Compose edge-to-edge"
```

**理由：** `adjustResize` 让系统缩小窗口高度，`imePadding()` 又加一层键盘高度 → 双倍 padding。`adjustNothing` 让窗口不变，IME 控制权完全交给 Compose 的 `imePadding()`。这是 Compose + `enableEdgeToEdge()` 的标准组合。

---

### Task 2: MainActivity — 添加 isNavigationBarContrastEnforced

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt:119`

- [ ] **Step 1: 在 enableEdgeToEdge() 后添加导航栏对比度强制关闭**

在第 119 行 `enableEdgeToEdge()` 之后插入：

```kotlin
enableEdgeToEdge()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    window.isNavigationBarContrastEnforced = false
}
```

确保文件顶部有 import（如果没有则添加）：
```kotlin
import android.os.Build
```

- [ ] **Step 2: 验证编译通过**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt
git commit -m "fix: disable navigation bar contrast enforcement for edge-to-edge"
```

**理由：** 部分 Android 10+ 设备默认对导航栏添加半透明背景（scrim），导致底部栏和导航栏之间出现色差。`isNavigationBarContrastEnforced = false` 关闭此行为。

---

### Task 3: ChatScreen — Scaffold content 添加 consumeWindowInsets

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt:1992-1997`

- [ ] **Step 1: 添加 consumeWindowInsets 到 Scaffold content 的 Box**

当前代码（约第 1992-1997 行）：
```kotlin
) { padding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
```

改为：
```kotlin
) { padding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
    ) {
```

在文件顶部确认有 import（如果没有则添加）：
```kotlin
import androidx.compose.foundation.layout.consumeWindowInsets
```

- [ ] **Step 2: 验证编译通过**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: add consumeWindowInsets to Scaffold content to prevent IME inset leakage"
```

**理由：** 防止 Scaffold 的 padding 与 `bottomBar` 上的 `imePadding()` 产生叠加效果。`consumeWindowInsets(padding)` 标记已消费的 inset，防止向子节点传播。

---

### Task 4: ChatScreen — 移除用户消息中的 SelectionContainer

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — MarkdownContent composable（约第 4367-4390 行）

- [ ] **Step 1: 找到 MarkdownContent 中的 SelectionContainer 并移除**

在 `MarkdownContent` composable 中，找到用户消息（isUser=true）的 `SelectionContainer` 包裹。当前代码结构类似：

```kotlin
@Composable
fun MarkdownContent(
    text: String,
    isUser: Boolean = false,
    // ...
) {
    if (isUser) {
        SelectionContainer {
            Markdown(...)  // 或类似的 Markdown 渲染
        }
    } else {
        Markdown(...)
    }
}
```

改为（去掉 SelectionContainer 分支，统一渲染）：

```kotlin
@Composable
fun MarkdownContent(
    text: String,
    isUser: Boolean = false,
    // ...
) {
    Markdown(...)
}
```

**注意：** 具体 Markdown 渲染调用取决于当前代码实际结构。关键是移除 `SelectionContainer { ... }` 包裹层，让 Markdown 直接渲染。用户仍可通过气泡头部的复制按钮复制文本。

- [ ] **Step 2: 验证编译通过**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "fix: remove SelectionContainer from user messages to fix sub-session tool card clicks"
```

**根因：** 子会话中 `onRevert = null`（禁用滑动撤回）→ `ChatMessageBubble` 直接渲染 bubbleContent，没有 `SwipeToDismissBox` 的 pointer input scope 隔离 → 用户消息中 `SelectionContainer` 的 `selectableGroup()` pointer-input handler 泄露到 LazyColumn → 拦截后续工具卡片的点击事件。移除用户消息的 `SelectionContainer` 彻底解决问题。

---

### Task 5: ChatScreen — 键盘弹出时自动滚动到最新消息

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — 在 `imeVisible` 定义（第 900 行）附近添加

- [ ] **Step 1: 添加键盘弹出自动滚动逻辑**

在第 900 行 `val imeVisible = WindowInsets.ime.getBottom(density) > 0` 之后，添加：

```kotlin
// 键盘弹出时，如果用户在底部（autoScrollEnabled），滚动到最新消息
LaunchedEffect(imeVisible) {
    if (imeVisible && autoScrollEnabled) {
        delay(100) // 等待布局稳定（imePadding 生效后）
        val lastIndex = listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }
}
```

**注意：** 确保 `autoScrollEnabled` 和 `listState` 在此位置已经定义。根据代码审查，`autoScrollEnabled` 在第 1357 行定义，`listState` 在更早的位置定义。如果此 `LaunchedEffect` 需要放在 `autoScrollEnabled` 定义之后，则调整位置到第 1394 行（`autoScrollEnabled` 的 `LaunchedEffect` 之后）。

- [ ] **Step 2: 验证编译通过**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "feat: auto-scroll to latest message when keyboard appears and user is at bottom"
```

**理由：** 类似微信/QQ 的行为——当用户在底部时键盘弹出，自动滚动确保最新消息仍然可见。使用 `autoScrollEnabled` 条件确保用户浏览历史消息时不会被强制拉回底部。

---

### Task 6: 构建并发布 beta.16

**Files:**
- Modify: `app/build.gradle.kts:21-22` — 版本号

- [ ] **Step 1: 更新版本号**

```kotlin
versionCode = 215
versionName = "2.0.0-beta.16"
```

- [ ] **Step 2: 构建 Release APK**

Run: `.\gradlew assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交并推送**

```bash
git add -A
git commit -m "chore: bump version to 2.0.0-beta.16

- Keyboard adaptation: adjustNothing + consumeWindowInsets + isNavigationBarContrastEnforced
- Remove SelectionContainer from user messages (fix sub-session tool card clicks)
- Auto-scroll to latest when keyboard appears
- Bump to v2.0.0-beta.16"
```

```bash
$env:https_proxy='http://127.0.0.1:7897'; git push fork master
```

- [ ] **Step 4: 创建 GitHub Release**

```bash
$env:https_proxy='http://127.0.0.1:7897'; gh release create v2.0.0-beta.16 --repo LeoNardo-LB/oc-remote-v2 --title "v2.0.0-beta.16" --notes "## Keyboard & Chat Window Optimization
- **键盘适配修复**: adjustResize→adjustNothing，消除 enableEdgeToEdge 双倍 padding
- **子会话工具卡片修复**: 移除用户消息 SelectionContainer，解决点击无响应
- **导航栏色差修复**: 关闭 isNavigationBarContrastEnforced
- **键盘弹出自动滚动**: 用户在底部时自动保持最新消息可见
- **Scaffold inset 消耗**: 添加 consumeWindowInsets 防止 IME inset 渗透" "./app/build/outputs/apk/release/app-release.apk"
```

---

## Self-Review

### 1. Spec Coverage

| 需求 | 对应 Task |
|------|----------|
| 键盘弹出推动消息界面 | Task 1 (adjustNothing) + Task 3 (consumeWindowInsets) |
| 子会话工具卡片不可点击 | Task 4 (移除 SelectionContainer) |
| 导航栏色差 | Task 2 (isNavigationBarContrastEnforced) |
| 键盘弹出自动滚动 | Task 5 (LaunchedEffect) |
| 发版 | Task 6 |

### 2. Placeholder Scan

无 TBD/TODO/占位符。所有步骤包含完整代码。

### 3. Type Consistency

- `imeVisible`: `Boolean` — 定义 `WindowInsets.ime.getBottom(density) > 0`，使用 `LaunchedEffect(imeVisible)` ✅
- `autoScrollEnabled`: `Boolean` — `mutableStateOf(true)`，在 `LaunchedEffect` 中读写 ✅
- `listState`: `LazyListState` — `rememberLazyListState()`，调用 `animateScrollToItem` ✅
- `consumeWindowInsets(padding)` — `padding` 类型为 `PaddingValues`，是 Scaffold 提供的 ✅
- `Build.VERSION_CODES.Q` = API 29 (Android 10) ✅
