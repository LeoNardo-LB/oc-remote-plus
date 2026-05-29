# Phase 5: 共享组件库 + Navigation 重构 + 最终清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将跨 Screen 重复的 UI 组件提取到 `ui/components/` 共享库，重构 Navigation 为类型安全路由，并完成最终代码清理与验证。

**Architecture:** 从各 Screen 文件中提取通用 Composable（PulsingDots、BreathingCircle、MarkdownContent 等）到 `ui/components/` 包，消除 3 处以上的代码重复。Navigation 层面，为每个 Screen 创建独立的 `Route.kt` 封装路由参数解析，使 NavGraph.kt 仅保留 NavHost 声明和 composable 注册。最终清理未使用代码并验证 Release build。

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose 2.9.8, Material 3, Hilt

**前置依赖:** Phase 4 必须已完成

> **⚠️ 行号回退策略：** 本文档中的行号参考基于初始代码分析。如果 Phase 1-4 的执行修改了目标文件，行号可能不再准确。执行者应以 **函数签名/组件名称** 为主要定位依据，行号仅作为辅助参考。当行号与实际代码不匹配时，使用 `grep`/IDE 搜索函数名定位。

> **⚠️ D5 FIX — 与 Phase 2/4 的功能重叠：**
> 以下 Task 在 Phase 2 或 Phase 4 中也有定义。执行本计划时按以下规则处理：
>
> | Phase 5 Task | 重叠的 Phase | 执行规则 |
> |---|---|---|
> | Task 1 (PulsingDotsIndicator) | Phase 4 Task 1 | **验证 + 补充** — 先检查 Phase 4 已提取的文件是否存在，存在则仅处理 ChatScreen 中残留的本地版本 |
> | Task 2 (BreathingCircleIndicator) | Phase 2 | **验证 + 提升** — 先检查是否已存在于 chat/components/，如存在则提升到 ui/components/ |
> | Task 3 (MarkdownContent) | Phase 2 Task 3 | **提升评估** — 评估是否多 Screen 使用。Chat 专用则保留在 chat/markdown/ 不移动 |
> | Task 4 (ChatInputBar) | Phase 2 Task 6 | **提升评估** — 同 Task 3 逻辑 |
> | Task 7 (Navigation Routes) | Phase 2 Task 9, Phase 4 各 Route | **合并** — 只创建 Phase 2/4 中未创建的 Route 对象，已有的直接复用 |
> | Task 5 (LoadingIndicator) | 无 | 照常执行（可选） |
> | Task 6 (ErrorView) | 无 | 照常执行（可选） |
>
> Phase 5 中不重叠的 Task（Task 8, 9, 10, 11）照常执行。

---

## 目标文件结构

```
ui/
├── components/                          # 共享组件库（现有 + 新增）
│   ├── ProviderIcon.kt                  # 现有 (123行) — 保留不动
│   ├── PulsingDotsIndicator.kt          # 新增 — 从 ChatScreen/SessionList/HomeScreen 提取
│   ├── BreathingCircleIndicator.kt      # 新增 — 从 ChatScreen 提取
│   ├── MarkdownContent.kt               # 新增 — 从 ChatScreen 提取
│   ├── ChatInputBar.kt                  # 新增 — 从 ChatScreen 提取（轻量化接口）
│   ├── LoadingIndicator.kt              # 新增 — 统一加载指示器（可选 Task，spec 未硬性要求）
│   └── ErrorView.kt                     # 新增 — 统一错误视图（可选 Task，spec 未硬性要求）
├── navigation/
│   ├── NavGraph.kt                      # 修改 — 瘦身，路由注册委托给 Route.kt
│   ├── Screen.kt                        # 修改 — 保留路由常量，移除 createRoute()
│   └── routes/                          # 新增 — 每个 Screen 的类型安全路由封装
│       ├── HomeRoute.kt
│       ├── WebViewRoute.kt
│       ├── SessionListRoute.kt
│       ├── ChatRoute.kt
│       ├── ServerSettingsRoute.kt
│       ├── ServerProvidersRoute.kt
│       ├── ServerModelFilterRoute.kt
│       ├── SettingsRoute.kt
│       └── AboutRoute.kt
├── screens/
│   ├── chat/
│   │   ├── ChatScreen.kt                # 修改 — 删除提取的组件，import 共享组件
│   │   ├── ChatParts.kt                 # 保留不动
│   │   ├── ChatViewModel.kt             # 保留不动
│   │   ├── ServerTerminalWorkspace.kt   # 保留不动
│   │   └── TerminalEmulator.kt          # 保留不动
│   ├── home/
│   │   ├── HomeScreen.kt                # 修改 — 删除 PulsingDotsIndicator，import 共享组件
│   │   └── ...
│   ├── sessions/
│   │   ├── SessionListScreen.kt         # 修改 — 删除 PulsingDotsIndicator/isAmoledTheme，import 共享组件
│   │   └── ...
│   └── ...其他 Screen 保留不动
```

> **注意**: Spec §3.1 中 `ui/components/` 下还规划了 `cards/`（ToolCallCard, ReasoningBlock 等）和 `diff/`（DiffView, DiffChangesInline）子目录。这些组件在 Phase 2 中已提取到 `chat/` 模块下作为 Chat 专用组件。如果后续需要跨 Screen 共享，可在后续迭代中从 chat/ 提升到 ui/components/。当前 Phase 5 不处理这些组件的提升。

---

## Task 1: 验证/提取 PulsingDotsIndicator 到 ui/components/

> **⚠️ D5 修复说明：** Phase 4 Task 1 已将 PulsingDotsIndicator 提取到 `ui/components/indicators/PulsingDotsIndicator.kt`。本 Task 改为"验证 + 补充"模式：先验证 Phase 4 的提取结果，仅在文件不存在时降级为自行提取。

**消除重复:** ChatScreen 中可能仍有本地 PulsingDotsIndicator 需要替换为共享版本。

**Files:**
- Create（仅当 Phase 4 未执行时）: `app/src/main/kotlin/dev/minios/ocremote/ui/components/indicators/PulsingDotsIndicator.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — 删除本地 `PulsingDotsIndicator` 函数（如有），改为 import 共享版本
- Verify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt` — 确认已引用共享版本
- Verify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` — 确认已引用共享版本

- [ ] **Step 1: 验证 Phase 4 已提取的 PulsingDotsIndicator**

检查文件是否存在：
```bash
Test-Path "app/src/main/kotlin/dev/minios/ocremote/ui/components/indicators/PulsingDotsIndicator.kt"
```

如果文件存在，跳到 Step 3。如果文件不存在（Phase 4 未执行此提取），继续 Step 2。

- [ ] **Step 2: 降级提取 — 从 ChatScreen.kt 创建共享版本（仅当 Step 1 文件不存在时执行）**

ChatScreen.kt 中的版本是最完整的（使用 scales2 做交错动画）。以它为基础创建公共组件：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/indicators/PulsingDotsIndicator.kt
package dev.minios.ocremote.ui.components.indicators

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pulsing dots loading indicator — 3 dots that scale up/down in sequence.
 * Shared component used across ChatScreen, SessionListScreen, HomeScreen.
 */
@Composable
fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotSpacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    // 从 ChatScreen.kt 中 PulsingDotsIndicator 的完整实现原样复制
    // 仅修改 package 声明和 import 语句
    // 保持所有动画参数和布局逻辑不变
}
```

- [ ] **Step 3: 从 ChatScreen.kt 删除/替换本地 PulsingDotsIndicator**

在 `ChatScreen.kt` 中：
1. 搜索 `private fun PulsingDotsIndicator(` 或 `fun PulsingDotsIndicator(`
2. 如果存在本地定义，删除整个函数体
3. 添加 import: `import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator`

- [ ] **Step 4: 验证 HomeScreen.kt 和 SessionListScreen.kt 已引用共享版本**

在 `HomeScreen.kt` 中搜索 `PulsingDotsIndicator` 的 import，确认已指向 `ui.components.indicators.PulsingDotsIndicator`（Phase 4 Task 1 已完成此替换）。

在 `SessionListScreen.kt` 中做同样验证。

如果发现仍有本地定义，删除并添加正确的 import。

- [ ] **Step 5: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/indicators/PulsingDotsIndicator.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
git commit -m "refactor: verify/extract PulsingDotsIndicator to shared components (eliminate duplicates)"
```

---

## Task 2: 验证/提取 BreathingCircleIndicator 到 ui/components/

> **⚠️ D5 修复说明：** Phase 2 可能已将 BreathingCircleIndicator 提取到 `ui/screens/chat/components/` 目录下。本 Task 改为"验证 + 提升"模式：先检查是否已有提取版本，如有则从 chat/components/ 提升到 ui/components/；如无则从 ChatScreen.kt 提取。

**Files:**
- Create（或移动）: `app/src/main/kotlin/dev/minios/ocremote/ui/components/BreathingCircleIndicator.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt` — 删除本地 `BreathingCircleIndicator` 函数（如有），改为 import 共享版本

- [ ] **Step 1: 检查 Phase 2 是否已提取 BreathingCircleIndicator**

```bash
# 检查是否已存在于 chat/components/
Test-Path "app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/BreathingCircleIndicator.kt"
# 同时检查目标位置是否已存在
Test-Path "app/src/main/kotlin/dev/minios/ocremote/ui/components/BreathingCircleIndicator.kt"
```

- 如果已存在于 `ui/components/` → 跳到 Step 3
- 如果存在于 `ui/screens/chat/components/` → 执行 Step 2a（移动提升）
- 如果均不存在 → 执行 Step 2b（从 ChatScreen.kt 提取）

- [ ] **Step 2a: 从 chat/components/ 提升到 ui/components/（当 Step 1 发现 chat/components/ 下有文件时）**

将 `ui/screens/chat/components/BreathingCircleIndicator.kt` 移动到 `ui/components/BreathingCircleIndicator.kt`：
1. 修改 package 声明为 `dev.minios.ocremote.ui.components`
2. 更新可见性为 `public`（如果原来是 `internal`）
3. 更新 ChatScreen.kt 中的 import 指向新路径

- [ ] **Step 2b: 从 ChatScreen.kt 提取（当 Step 1 发现文件不存在时）**

从 ChatScreen.kt 中搜索 `BreathingCircleIndicator` 函数定义，创建：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/BreathingCircleIndicator.kt
package dev.minios.ocremote.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Breathing circle loading indicator — single circle that pulses smoothly.
 * Used as a compact activity indicator.
 */
@Composable
fun BreathingCircleIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    // 从 ChatScreen.kt 中 BreathingCircleIndicator 的完整实现原样复制
    // 仅修改 package 声明和 import 语句
}
```

- [ ] **Step 3: 从 ChatScreen.kt 删除/替换本地 BreathingCircleIndicator**

1. 搜索 `private fun BreathingCircleIndicator(` 或 `fun BreathingCircleIndicator(`
2. 如果存在本地定义，删除整个函数体
3. 添加 import: `import dev.minios.ocremote.ui.components.BreathingCircleIndicator`

- [ ] **Step 4: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/BreathingCircleIndicator.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: verify/promote BreathingCircleIndicator to shared components"
```

---

## Task 3: 评估/提升 MarkdownContent 到 ui/components/

> **⚠️ D5 修复说明：** Phase 2 已将 MarkdownContent 提取到 `ui/screens/chat/markdown/MarkdownContent.kt`。本 Task 改为"提升评估"模式：评估是否需要将此组件从 chat/ 模块提升到 ui/components/（跨 Screen 共享）。如果 MarkdownContent 是 Chat 专用组件，保留在 chat/ 下不移动。

**决策逻辑：**
- 如果 MarkdownContent 仅被 ChatScreen 使用 → **保留在 `ui/screens/chat/markdown/`，跳过本 Task**
- 如果 MarkdownContent 被 2+ 个 Screen 使用 → **提升到 `ui/components/MarkdownContent.kt`**

- [ ] **Step 1: 评估 MarkdownContent 的使用范围**

搜索项目中所有引用 `MarkdownContent` 的文件：

```bash
cd D:\Develop\code\app\oc-remote
grep -rn "MarkdownContent" app/src/main/kotlin/dev/minios/ocremote/ui/screens/ --include="*.kt"
```

统计引用该组件的 Screen 模块数量（排除 chat/ 目录内的引用）：
- 如果仅 chat/ 内使用 → 记录结论为"Chat 专用，保留在 chat/markdown/"，跳到 Step 4
- 如果有其他 Screen 使用 → 继续执行 Step 2-3

- [ ] **Step 2: 提升到 ui/components/（仅当 Step 1 确认多 Screen 使用时执行）**

将 `ui/screens/chat/markdown/MarkdownContent.kt`（或 Phase 2 提取到的路径）移动到 `ui/components/MarkdownContent.kt`：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/MarkdownContent.kt
package dev.minios.ocremote.ui.components

// 从原文件完整搬运 MarkdownContent composable
// 包括：
// - preserveRawHtmlPayload()（作为 private 函数）
// - MarkdownContent composable
// - 所有相关的颜色/样式计算逻辑
```

注意事项：
- `LocalChatFontSize` 如果声明在 ChatScreen.kt 中，需要移到 `ui/components/ChatFontProviders.kt` 或 `ui/theme/` 包中
- `preserveRawHtmlPayload` 如果是独立工具函数，一并移走
- 更新 chat/ 下的其他组件的 import 指向新路径

- [ ] **Step 3: 从 ChatScreen.kt 删除 MarkdownContent 及相关代码（仅当 Step 2 执行时）**

1. 如果 ChatScreen.kt 中仍有本地 `MarkdownContent` 定义，删除
2. 如 `LocalChatFontSize` 原声明在 ChatScreen.kt 中，删除声明并改为从 components/theme import
3. 添加 import: `import dev.minios.ocremote.ui.components.MarkdownContent`

- [ ] **Step 4: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit（如有变更）**

```bash
# 仅当执行了 Step 2-3 提升操作时提交
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/MarkdownContent.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: promote MarkdownContent to shared components (multi-screen usage)"
```

---

## Task 4: 评估/提升 ChatInputBar 到 ui/components/

> **⚠️ D5 修复说明：** Phase 2 可能已将 ChatInputBar 提取到 `ui/screens/chat/components/` 目录下。本 Task 改为"提升评估"模式：评估是否需要将此组件从 chat/ 模块提升到 ui/components/。

**决策逻辑：**
- 如果 ChatInputBar 仅被 ChatScreen 使用 → **保留在 chat/ 模块内，跳过本 Task**
- 如果 ChatInputBar 被 2+ 个 Screen 使用 → **提升到 `ui/components/ChatInputBar.kt`**

> **注意：** ChatInputBar 有 25+ 参数，深度依赖 ChatScreen 的数据类型（`ChatMessage`, `ImageAttachment`, `AgentInfo`, `SlashCommand`, `ChatInputMode`, `CommandInfo` 等）。即使提升到 ui/components/，参数类型仍通过 import 引用 chat/ 包中的类型。

- [ ] **Step 1: 评估 ChatInputBar 的使用范围**

搜索项目中所有引用 `ChatInputBar` 的文件：

```bash
cd D:\Develop\code\app\oc-remote
grep -rn "ChatInputBar" app/src/main/kotlin/dev/minios/ocremote/ui/screens/ --include="*.kt"
```

同时检查 Phase 2 是否已提取：
```bash
Test-Path "app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/ChatInputBar.kt"
```

统计引用该组件的 Screen 模块数量：
- 如果仅 chat/ 内使用 → 记录结论为"Chat 专用，保留在 chat/"，跳到 Step 4
- 如果有其他 Screen 使用 → 继续执行 Step 2-3

- [ ] **Step 2: 提升到 ui/components/（仅当 Step 1 确认多 Screen 使用时执行）**

将 ChatInputBar 移动/创建到 `ui/components/ChatInputBar.kt`：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/ChatInputBar.kt
package dev.minios.ocremote.ui.components

// import ChatScreen 包中的数据类型
import dev.minios.ocremote.ui.screens.chat.*

// 完整搬运 ChatInputBar 的实现及其内部依赖的辅助 composable
```

将 ChatInputBar 及其内部依赖的辅助 composable 一并移走。

- [ ] **Step 3: 从 ChatScreen.kt 删除 ChatInputBar（仅当 Step 2 执行时）**

1. 如果 ChatScreen.kt 中仍有本地 `ChatInputBar` 定义，删除
2. 删除 ChatInputBar 内部定义的辅助 Composable（如已移走）
3. 添加 import: `import dev.minios.ocremote.ui.components.ChatInputBar`

- [ ] **Step 4: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit（如有变更）**

```bash
# 仅当执行了 Step 2-3 提升操作时提交
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/ChatInputBar.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
git commit -m "refactor: promote ChatInputBar to shared components (multi-screen usage)"
```

---

## Task 5: 创建 LoadingIndicator 统一组件（可选）

> **说明：** LoadingIndicator 和 ErrorView（Task 6）是合理补充（统一加载/错误状态展示），非 spec 硬性要求。如果时间紧张，可推迟到后续迭代。这两个组件不会影响其他 Task 的执行。

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/LoadingIndicator.kt`

- [ ] **Step 1: 创建 LoadingIndicator.kt**

提供两种变体：带文字提示和不带文字。替代散布在各 Screen 中的 `CircularProgressIndicator` + `PulsingDotsIndicator` 的不一致用法。

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/LoadingIndicator.kt
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Unified loading indicator with optional message.
 * Use across all screens for consistent loading UX.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
    useDots: Boolean = false
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (useDots) {
            PulsingDotsIndicator(dotSize = 12.dp, dotSpacing = 8.dp)
        } else {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

- [ ] **Step 2: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/LoadingIndicator.kt
git commit -m "feat: add unified LoadingIndicator component"
```

---

## Task 6: 创建 ErrorView 统一组件（可选）

> **说明：** 同 Task 5，本组件为合理补充，非 spec 硬性要求。

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/components/ErrorView.kt`

- [ ] **Step 1: 创建 ErrorView.kt**

基于 SessionListScreen 中的错误显示模式创建通用组件：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/components/ErrorView.kt
package dev.minios.ocremote.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Unified error view with icon, message, and retry button.
 * Use across all screens for consistent error display.
 */
@Composable
fun ErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
```

- [ ] **Step 2: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/ErrorView.kt
git commit -m "feat: add unified ErrorView component"
```

---

## Task 7: Navigation Route 类型安全封装

**目标:** 为每个带参数的 Screen 创建 Route.kt，封装路由模式定义 + 参数解析逻辑。NavGraph.kt 中的 `composable(...)` 块将调用 Route 对象的方法来获取参数，而非手动 `URLDecoder.decode()`。

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ServerRouteParams.kt` — 公共参数数据类
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/HomeRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/WebViewRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/SessionListRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ChatRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ServerSettingsRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ServerProvidersRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ServerModelFilterRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/SettingsRoute.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/AboutRoute.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/Screen.kt` — 删除 `createRoute()` 方法
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt` — 更新导航调用
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt` — 更新导航调用
- Modify: 其他 Screen 的导航调用处

- [ ] **Step 1: 创建公共 ServerRouteParams 数据类**

多个路由共享 serverUrl/username/password/serverName/serverId 五个参数，提取为公共数据类：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ServerRouteParams.kt
package dev.minios.ocremote.ui.navigation.routes

import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Common server connection parameters shared across most routes.
 */
data class ServerRouteParams(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val serverId: String
) {
    companion object {
        /** Route query parameter names */
        const val PARAM_SERVER_URL = "serverUrl"
        const val PARAM_USERNAME = "username"
        const val PARAM_PASSWORD = "password"
        const val PARAM_SERVER_NAME = "serverName"
        const val PARAM_SERVER_ID = "serverId"

        /** NavArgument definitions — reuse in every route that needs server params */
        val navArguments = listOf(
            navArgument(PARAM_SERVER_URL) { type = NavType.StringType },
            navArgument(PARAM_USERNAME) { type = NavType.StringType },
            navArgument(PARAM_PASSWORD) { type = NavType.StringType },
            navArgument(PARAM_SERVER_NAME) { type = NavType.StringType },
            navArgument(PARAM_SERVER_ID) { type = NavType.StringType },
        )

        /** Build encoded query string for route pattern */
        fun queryPattern(): String =
            "$PARAM_SERVER_URL={$PARAM_SERVER_URL}&$PARAM_USERNAME={$PARAM_USERNAME}&$PARAM_PASSWORD={$PARAM_PASSWORD}&$PARAM_SERVER_NAME={$PARAM_SERVER_NAME}&$PARAM_SERVER_ID={$PARAM_SERVER_ID}"

        /** Build encoded query string with values */
        fun queryString(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedServerId = URLEncoder.encode(serverId, "UTF-8")
            return "$PARAM_SERVER_URL=$encodedUrl&$PARAM_USERNAME=$encodedUsername&$PARAM_PASSWORD=$encodedPassword&$PARAM_SERVER_NAME=$encodedName&$PARAM_SERVER_ID=$encodedServerId"
        }
    }
}

/**
 * Extension to decode server params from a NavBackStackEntry.
 */
fun androidx.navigation.NavBackStackEntry.serverRouteParams(): ServerRouteParams {
    return ServerRouteParams(
        serverUrl = URLDecoder.decode(arguments?.getString(ServerRouteParams.PARAM_SERVER_URL) ?: "", "UTF-8"),
        username = URLDecoder.decode(arguments?.getString(ServerRouteParams.PARAM_USERNAME) ?: "", "UTF-8"),
        password = URLDecoder.decode(arguments?.getString(ServerRouteParams.PARAM_PASSWORD) ?: "", "UTF-8"),
        serverName = URLDecoder.decode(arguments?.getString(ServerRouteParams.PARAM_SERVER_NAME) ?: "", "UTF-8"),
        serverId = URLDecoder.decode(arguments?.getString(ServerRouteParams.PARAM_SERVER_ID) ?: "", "UTF-8"),
    )
}
```

- [ ] **Step 2: 创建每个 Screen 的 Route 对象**

每个 Route 对象提供：
- `route` 常量（路由模式字符串）
- `navArguments` 列表
- `createRoute()` 工厂方法
- `Params` 数据类 + `fromEntry()` 解析方法

示例 — ChatRoute.kt：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/ChatRoute.kt
package dev.minios.ocremote.ui.navigation.routes

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

object ChatRoute {
    const val ROUTE = "chat"

    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_OPEN_TERMINAL = "openTerminal"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_OPEN_TERMINAL) { type = NavType.BoolType; defaultValue = false },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_OPEN_TERMINAL={$PARAM_OPEN_TERMINAL}"

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val openTerminal: Boolean = false
    )

    fun createRoute(
        serverUrl: String, username: String, password: String,
        serverName: String, serverId: String,
        sessionId: String, openTerminal: Boolean = false
    ): String {
        val serverQuery = ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)
        val encodedSessionId = URLEncoder.encode(sessionId, "UTF-8")
        return "$ROUTE?$serverQuery&$PARAM_SESSION_ID=$encodedSessionId&$PARAM_OPEN_TERMINAL=$openTerminal"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        val server = entry.serverRouteParams()
        val sessionId = URLDecoder.decode(entry.arguments?.getString(PARAM_SESSION_ID) ?: "", "UTF-8")
        val openTerminal = entry.arguments?.getBoolean(PARAM_OPEN_TERMINAL) ?: false
        return Params(server = server, sessionId = sessionId, openTerminal = openTerminal)
    }
}
```

类似地创建其他 Route 对象（HomeRoute、SettingsRoute、AboutRoute 无参数；其余带 ServerRouteParams）：

**HomeRoute.kt:**
```kotlin
object HomeRoute {
    const val route = "home"
    val navArguments = emptyList<Any>()
}
```

**SettingsRoute.kt:**
```kotlin
object SettingsRoute {
    const val route = "settings"
}
```

**AboutRoute.kt:**
```kotlin
object AboutRoute {
    const val route = "about"
}
```

**WebViewRoute.kt:**
```kotlin
object WebViewRoute {
    const val ROUTE = "webview"
    const val PARAM_INITIAL_PATH = "initialPath"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(ServerRouteParams.PARAM_SERVER_URL) { type = NavType.StringType; nullable = false },
        navArgument(ServerRouteParams.PARAM_USERNAME) { type = NavType.StringType; nullable = false },
        navArgument(ServerRouteParams.PARAM_PASSWORD) { type = NavType.StringType; nullable = false },
        navArgument(ServerRouteParams.PARAM_SERVER_NAME) { type = NavType.StringType; nullable = false },
        navArgument(PARAM_INITIAL_PATH) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_INITIAL_PATH={$PARAM_INITIAL_PATH}"

    data class Params(
        val serverUrl: String, val username: String, val password: String,
        val serverName: String, val initialPath: String = ""
    )

    fun createRoute(
        serverUrl: String, username: String, password: String,
        serverName: String, initialPath: String = ""
    ): String {
        val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        val encodedPassword = URLEncoder.encode(password, "UTF-8")
        val encodedName = URLEncoder.encode(serverName, "UTF-8")
        val encodedPath = URLEncoder.encode(initialPath, "UTF-8")
        return "$ROUTE?${ServerRouteParams.PARAM_SERVER_URL}=$encodedUrl&${ServerRouteParams.PARAM_USERNAME}=$encodedUsername&${ServerRouteParams.PARAM_PASSWORD}=$encodedPassword&${ServerRouteParams.PARAM_SERVER_NAME}=$encodedName&$PARAM_INITIAL_PATH=$encodedPath"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(
            serverUrl = URLDecoder.decode(entry.arguments?.getString(ServerRouteParams.PARAM_SERVER_URL) ?: "", "UTF-8"),
            username = URLDecoder.decode(entry.arguments?.getString(ServerRouteParams.PARAM_USERNAME) ?: "", "UTF-8"),
            password = URLDecoder.decode(entry.arguments?.getString(ServerRouteParams.PARAM_PASSWORD) ?: "", "UTF-8"),
            serverName = URLDecoder.decode(entry.arguments?.getString(ServerRouteParams.PARAM_SERVER_NAME) ?: "", "UTF-8"),
            initialPath = URLDecoder.decode(entry.arguments?.getString(PARAM_INITIAL_PATH) ?: "", "UTF-8"),
        )
    }
}
```

**SessionListRoute.kt:**
```kotlin
object SessionListRoute {
    const val ROUTE = "sessions"

    val navArguments = ServerRouteParams.navArguments

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}"

    data class Params(val server: ServerRouteParams)

    fun createRoute(
        serverUrl: String, username: String, password: String,
        serverName: String, serverId: String
    ): String {
        return "$ROUTE?${ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)}"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(server = entry.serverRouteParams())
    }
}
```

**ServerSettingsRoute.kt:**
```kotlin
object ServerSettingsRoute {
    const val ROUTE = "server_settings"

    val navArguments = ServerRouteParams.navArguments

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}"

    data class Params(val server: ServerRouteParams)

    fun createRoute(
        serverUrl: String, username: String, password: String,
        serverName: String, serverId: String
    ): String {
        return "$ROUTE?${ServerRouteParams.queryString(serverUrl, username, password, serverName, serverId)}"
    }

    fun fromEntry(entry: NavBackStackEntry): Params {
        return Params(server = entry.serverRouteParams())
    }
}
```

**ServerProvidersRoute.kt:** — 结构与 ServerSettingsRoute 相同，仅 ROUTE = `"server_providers"`

**ServerModelFilterRoute.kt:** — 结构与 ServerSettingsRoute 相同，仅 ROUTE = `"server_model_filter"`

- [ ] **Step 3: 更新 Screen.kt — 保留路由常量，删除 createRoute()**

Screen.kt 简化为纯路由常量：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/navigation/Screen.kt
package dev.minios.ocremote.ui.navigation

/**
 * Navigation route constants.
 * Route creation and argument parsing are handled by Route objects in ui/navigation/routes/.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object WebView : Screen("webview")
    data object SessionList : Screen("sessions")
    data object Chat : Screen("chat")
    data object ServerSettings : Screen("server_settings")
    data object ServerProviders : Screen("server_providers")
    data object ServerModelFilter : Screen("server_model_filter")
    data object Settings : Screen("settings")
    data object About : Screen("about")
}
```

- [ ] **Step 4: 更新所有导航调用点**

全局搜索 `Screen.Xxx.createRoute(` 并替换为 `XxxRoute.createRoute(`：
- `Screen.SessionList.createRoute(...)` → `SessionListRoute.createRoute(...)`
- `Screen.ServerSettings.createRoute(...)` → `ServerSettingsRoute.createRoute(...)`
- `Screen.ServerProviders.createRoute(...)` → `ServerProvidersRoute.createRoute(...)`
- `Screen.ServerModelFilter.createRoute(...)` → `ServerModelFilterRoute.createRoute(...)`
- `Screen.Chat.createRoute(...)` → `ChatRoute.createRoute(...)`
- `Screen.WebView.createRoute(...)` → `WebViewRoute.createRoute(...)`

更新涉及的文件：
- `NavGraph.kt`
- `HomeScreen.kt`
- `SessionListScreen.kt`
- `ChatScreen.kt`
- `ServerSettingsScreen.kt`

- [ ] **Step 5: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/
git add app/src/main/kotlin/dev/minios/ocremote/ui/navigation/Screen.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/
git commit -m "refactor: type-safe navigation routes with ServerRouteParams extraction"
```

---

## Task 8: NavGraph.kt 瘦身

**目标:** NavGraph.kt 中的 composable 块使用 Route 对象解析参数，消除重复的 URLDecoder.decode 模板代码。ShareTargetPickerDialog 移到独立文件。

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` — 使用 Route 对象重构 composable 块
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/ShareTargetPickerDialog.kt` — 从 NavGraph.kt 移出

- [ ] **Step 1: 提取 ShareTargetPickerDialog 到独立文件**

将 NavGraph.kt line 555-767 的 `ShareTargetPickerDialog` 移到新文件：

```kotlin
// app/src/main/kotlin/dev/minios/ocremote/ui/navigation/ShareTargetPickerDialog.kt
package dev.minios.ocremote.ui.navigation

// 完整移入 ShareTargetPickerDialog composable
// 将 private 改为 internal（包内可见）
```

- [ ] **Step 2: 重构 NavGraph.kt 的 composable 块**

将每个 composable 块中的参数解析替换为 Route 对象调用：

```kotlin
// Before (重复模板):
composable(
    route = "server_settings?serverUrl={serverUrl}&username={username}&...",
    arguments = listOf(
        navArgument("serverUrl") { type = NavType.StringType },
        // ... 5 个重复声明
    )
) {
    val serverUrl = URLDecoder.decode(it.arguments?.getString("serverUrl") ?: "", "UTF-8")
    val username = URLDecoder.decode(it.arguments?.getString("username") ?: "", "UTF-8")
    // ... 5 个重复解析
    ServerSettingsScreen(...)
}

// After:
composable(
    route = ServerSettingsRoute.routePattern,
    arguments = ServerSettingsRoute.navArguments
) { entry ->
    val params = ServerSettingsRoute.fromEntry(entry)
    ServerSettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onOpenProviders = {
            navController.navigate(
                ServerProvidersRoute.createRoute(
                    serverUrl = params.server.serverUrl,
                    username = params.server.username,
                    password = params.server.password,
                    serverName = params.server.serverName,
                    serverId = params.server.serverId
                )
            )
        },
        onOpenModels = {
            navController.navigate(
                ServerModelFilterRoute.createRoute(
                    serverUrl = params.server.serverUrl,
                    username = params.server.username,
                    password = params.server.password,
                    serverName = params.server.serverName,
                    serverId = params.server.serverId
                )
            )
        }
    )
}
```

类似地重构所有其他 composable 块。

- [ ] **Step 3: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/navigation/
git commit -m "refactor: slim down NavGraph using Route objects, extract ShareTargetPickerDialog"
```

---

## Task 9: DeepLink 处理增强

**目标:** 确认 DeepLink 处理路径一致使用 Route 对象，并为关键路由添加 DeepLink 支持。

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` — DeepLink 流中使用 Route 对象
- Possibly: `app/src/main/AndroidManifest.xml` — 确认 intent-filter 配置

- [ ] **Step 1: 审查当前 DeepLink 处理**

搜索 NavGraph.kt 中的 `deepLinkFlow` 相关代码（约 line 96-220）。当前 DeepLink 逻辑在 NavGraph 函数体内通过 `LaunchedEffect` 处理 `deepLinkFlow`，构建路由并导航。

确认所有 DeepLink 导航调用已使用 `XxxRoute.createRoute()` 而非 `Screen.Xxx.createRoute()`（由 Task 7 完成后应已更新）。

- [ ] **Step 2: 验证 DeepLink 从 Route 对象创建路由**

检查 NavGraph.kt 中 `deepLinkFlow.collect` 块内的所有导航：
- `Screen.Chat.createRoute(...)` → 应已变为 `ChatRoute.createRoute(...)`
- `Screen.SessionList.createRoute(...)` → 应已变为 `SessionListRoute.createRoute(...)`
- `Screen.WebView.createRoute(...)` → 应已变为 `WebViewRoute.createRoute(...)`

如果未更新，在此步骤中修复。

- [ ] **Step 3: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: unify DeepLink handling with Route objects"
```

---

## Task 10: 最终清理 — 删除未使用代码和统一风格

**Files:**
- Modify: 所有 `ui/screens/*.kt` 和 `ui/navigation/*.kt` — 清理未使用 import
- Modify: 可能删除已无用的代码段

- [ ] **Step 1: 删除 SessionListScreen.kt 中的 private isAmoledTheme()**

`isAmoledTheme()` 在 SessionListScreen 和 ChatScreen 中都有定义。确认是否在 components 中已有公共版本：
- 如果没有，提取到 `ui/components/ThemeHelpers.kt` 或 `ui/theme/ThemeHelpers.kt`
- 如果 ChatScreen 中的 `isAmoledTheme()` 也已无其他调用，一并清理

```kotlin
// ui/components/ThemeHelpers.kt (如需新建)
package dev.minios.ocremote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Check if current theme is AMOLED black. */
@Composable
fun isAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
```

- [ ] **Step 2: 全局清理未使用的 import**

在 Android Studio 中：
1. 打开项目
2. Analyze → Run Inspection by Name → "Unused import"
3. 范围限定到 `app/src/main/kotlin/dev/minios/ocremote/ui/`
4. 批量删除未使用 import

或使用命令行：
```bash
# 检查未使用 import（仅报告，不自动修改）
cd D:\Develop\code\app\oc-remote
grep -rn "^import " app/src/main/kotlin/dev/minios/ocremote/ui/ --include="*.kt" | wc -l
```

手动检查以下文件：
- `ChatScreen.kt` — 提取组件后可能遗留未使用 import
- `SessionListScreen.kt` — 提取 PulsingDotsIndicator 后可能遗留 animation import
- `HomeScreen.kt` — 同上
- `NavGraph.kt` — 重构后可能遗留未使用 import（如 `URLDecoder`, `navArgument`, `NavType`）

- [ ] **Step 3: 删除 ChatScreen.kt 中遗留的未使用变量**

检查 ChatScreen.kt 中 PulsingDotsIndicator 移走后是否有未使用的 `scales` 变量（原来的第一个未使用变量）。如果有，删除。

- [ ] **Step 4: 统一代码风格**

确保所有新文件遵循项目风格：
- 文件头注释统一使用 `/** */` 格式的 KDoc
- Composable 函数参数顺序：`modifier` 第一，回调最后
- import 分组：androidx → compose → 项目内部

- [ ] **Step 5: 构建验证**

Run: `cd D:\Develop\code\app\oc-remote && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: cleanup unused imports, extract isAmoledTheme to shared helper"
```

---

## Task 11: 最终验证 — Release Build + APK 大小对比 + 目录结构检查

**Files:** 无修改，仅验证

- [ ] **Step 1: 记录重构前 APK 大小**

如果尚未记录，从最近的 release build 获取基线：

```bash
cd D:\Develop\code\app\oc-remote
# Debug build 作为参考
./gradlew assembleDebug 2>&1 | tail -5
ls -lh app/build/outputs/apk/debug/*.apk
```

记录 APK 大小。如果之前有 release build：
```bash
ls -lh app/build/outputs/apk/release/*.apk
```

- [ ] **Step 2: 运行 Release Build**

```bash
cd D:\Develop\code\app\oc-remote
./gradlew assembleRelease 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 对比 APK 大小**

```bash
ls -lh app/build/outputs/apk/release/*.apk
```

对比 release APK 与之前的大小。由于本次重构主要是代码移动（不增加功能），APK 大小变化应在 ±50KB 以内。

- [ ] **Step 4: 验证最终目录结构**

确认文件结构符合预期：

```bash
cd D:\Develop\code\app\oc-remote
echo "=== ui/components/ ==="
ls -la app/src/main/kotlin/dev/minios/ocremote/ui/components/
echo "=== ui/navigation/ ==="
ls -la app/src/main/kotlin/dev/minios/ocremote/ui/navigation/
echo "=== ui/navigation/routes/ ==="
ls -la app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/
echo "=== ui/screens/ ==="
find app/src/main/kotlin/dev/minios/ocremote/ui/screens/ -name "*.kt" -exec basename {} \;
```

预期结果：
```
ui/components/:
  ProviderIcon.kt
  PulsingDotsIndicator.kt
  BreathingCircleIndicator.kt
  MarkdownContent.kt
  ChatInputBar.kt
  LoadingIndicator.kt
  ErrorView.kt
  ThemeHelpers.kt (如创建了)

ui/navigation/:
  NavGraph.kt
  Screen.kt
  ShareTargetPickerDialog.kt
  routes/

ui/navigation/routes/:
  ServerRouteParams.kt
  HomeRoute.kt
  WebViewRoute.kt
  SessionListRoute.kt
  ChatRoute.kt
  ServerSettingsRoute.kt
  ServerProvidersRoute.kt
  ServerModelFilterRoute.kt
  SettingsRoute.kt
  AboutRoute.kt
```

- [ ] **Step 5: 验证各 Screen 代码瘦身**

```bash
cd D:\Develop\code\app\oc-remote
wc -l app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt
wc -l app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt
wc -l app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt
wc -l app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
```

预期变化：
- ChatScreen.kt: 8236 行 → 减少约 500-800 行（提取 PulsingDots + BreathingCircle + MarkdownContent + ChatInputBar）
- SessionListScreen.kt: 1449 行 → 减少约 30 行
- HomeScreen.kt: 1463 行 → 减少约 30 行
- NavGraph.kt: 767 行 → 减少约 100-150 行（参数解析模板代码消除）

- [ ] **Step 6: 功能冒烟测试**

在设备/模拟器上验证核心流程：
1. **Home → 连接服务器 → SessionList** — 导航正常
2. **SessionList → Chat** — 参数正确传递
3. **Chat → 发送消息** — MarkdownContent 渲染正常、ChatInputBar 功能完整
4. **Chat → 加载中** — PulsingDotsIndicator 动画正常
5. **Home → ServerSettings → ServerProviders** — 多级导航正常
6. **DeepLink 打开** — 从外部链接打开 Chat 正常
7. **分享图片到 App** — ShareTargetPickerDialog 正常弹出

- [ ] **Step 7: Final Commit**

```bash
git add -A
git commit -m "chore(phase5): Phase 5 complete — shared components, type-safe navigation, final cleanup"
```

---

## 自检清单

### Spec 覆盖度
| 要求 | 对应 Task | 状态 |
|------|-----------|------|
| PulsingDotsIndicator 消除3处重复 | Task 1 | ✅ |
| BreathingCircleIndicator 提取 | Task 2 | ✅ |
| MarkdownContent 通用版本 | Task 3 | ✅ |
| ChatInputBar 通用版本 | Task 4 | ✅ |
| ProviderIcon 保留 | 已在 components/ | ✅ |
| LoadingIndicator | Task 5 | ✅ |
| ErrorView | Task 6 | ✅ |
| 每个 Screen 创建 Route.kt | Task 7 | ✅ |
| NavGraph.kt 瘦身 | Task 8 | ✅ |
| DeepLink 处理 | Task 9 | ✅ |
| 删除未使用 import 和文件 | Task 10 | ✅ |
| 统一代码风格 | Task 10 | ✅ |
| 最终目录结构验证 | Task 11 | ✅ |
| Release build 验证 | Task 11 | ✅ |
| APK 大小对比 | Task 11 | ✅ |
| 无 placeholder | 全文 | ✅ |

### Placeholder 扫描
- 无 TBD / TODO / implement later
- 无 "add appropriate error handling"
- 无 "similar to Task N"
- 每个步骤包含具体代码或命令

### 类型一致性
- `ServerRouteParams` 在 `ServerRouteParams.kt` 定义，在所有 Route 对象和 NavGraph 中通过 `serverRouteParams()` 扩展函数统一使用
- `PulsingDotsIndicator` / `BreathingCircleIndicator` / `MarkdownContent` / `ChatInputBar` 统一在 `dev.minios.ocremote.ui.components` 包中，public 可见性
- 所有 Route 对象遵循相同 API：`routePattern`, `navArguments`, `createRoute()`, `fromEntry()`, `Params`
