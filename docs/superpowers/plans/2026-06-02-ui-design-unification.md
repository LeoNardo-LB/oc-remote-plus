# UI Design Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking tracking.

**Goal:** 统一 OC Remote v2 的设计系统，通过 Material3 内建能力消除散落的魔法数字和重复检测逻辑。

**Architecture:** 新建 Shape.kt 和 Motion.kt 集中设计常量；在 Theme.kt 中新增 `LocalAmoledMode` CompositionLocal 替代散落的颜色比较检测；NavGraph 添加标准 Material3 页面过渡动画；HomeScreen 引入 WindowSizeClass 支持网格布局。

**Tech Stack:** Kotlin + Jetpack Compose + Material3 (BOM 2026.05.01) + material3-window-size-class

**Spec:** `docs/superpowers/specs/2026-06-02-ui-design-unification.md`

**Verification:** 本计划涉及 UI/主题层改动，无单元测试覆盖。每步验证通过编译检查 `.\gradlew :app:compileDevDebugKotlin`。

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Shape.kt` | **新建** | AppShapes + AmoledShapes 定义 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Motion.kt` | **新建** | AppMotion 动画常量 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Theme.kt` | 修改 | 新增 LocalAmoledMode + Shape 接入 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/components/AmoledCard.kt` | 修改 | 使用主题 Shape 替代硬编码 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatColors.kt` | 修改 | isAmoledTheme() 委托到 LocalAmoledMode |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionUiHelpers.kt` | 修改 | 删除重复的 isAmoledTheme()，改为导入 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` | 修改 | 添加页面过渡动画 |
| `app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt` | 修改 | 计算 WindowSizeClass |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt` | 修改 | 网格布局适配 |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt` | 修改 | 居中约束 |
| `app/build.gradle.kts` | 修改 | 添加 window-size-class 依赖 |
| 若干 ServerDialog/LocalLaunchOptionsDialog 等文件 | 修改 | 内联检测改为 LocalAmoledMode |

---

## Phase 1: 基础设施

### Task 1: 创建 Shape.kt

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Shape.kt`

- [ ] **Step 1: 创建 Shape.kt 文件**

```kotlin
package dev.minios.ocremote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val AmoledShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/theme/Shape.kt
git commit -m "feat(ui): add centralized Shape system (AppShapes + AmoledShapes)"
```

---

### Task 2: 创建 Motion.kt

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Motion.kt`

- [ ] **Step 1: 创建 Motion.kt 文件**

```kotlin
package dev.minios.ocremote.ui.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut

object AppMotion {
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500

    val StandardEasing = EaseInOut
    val EmphasizedEasing = EaseOut
    val ExitEasing = EaseIn
}
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/theme/Motion.kt
git commit -m "feat(ui): add centralized Motion constants (AppMotion)"
```

---

### Task 3: 更新 Theme.kt — LocalAmoledMode + Shape 接入

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/theme/Theme.kt`

- [ ] **Step 1: 添加 LocalAmoledMode CompositionLocal**

在 Theme.kt 文件的 import 区域之后、`LightColorScheme` 定义之前，添加：

```kotlin
val LocalAmoledMode = compositionLocalOf { false }
```

注意：`compositionLocalOf` 的 import `androidx.compose.runtime.compositionLocalOf` 应该已经在文件的 import 区域中。如果不在，需要添加。

- [ ] **Step 2: 在 OpenCodeTheme 中应用 Shape + 包裹 CompositionLocalProvider**

找到第 134–138 行的 `MaterialTheme(...)` 调用：

```kotlin
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
```

替换为：

```kotlin
    CompositionLocalProvider(LocalAmoledMode provides amoledDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = if (amoledDark) AmoledShapes else AppShapes,
            content = content
        )
    }
```

需要的 import：
- `androidx.compose.runtime.CompositionLocalProvider` — 如果尚未存在则添加
- `dev.minios.ocremote.ui.theme.AppShapes` / `AmoledShapes` — 同包，无需 import

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/theme/Theme.kt
git commit -m "feat(ui): add LocalAmoledMode CompositionLocal + wire Shape system"
```

---

### Task 4: 添加 window-size-class 依赖

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 添加依赖**

在 `build.gradle.kts` 第 115 行 `implementation("androidx.compose.material3:material3")` 之后添加：

```kotlin
    implementation("androidx.compose.material3:material3-window-size-class")
```

版本由 Compose BOM `2026.05.01`（第 110 行）管理，无需显式版本号。

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/build.gradle.kts
git commit -m "build: add material3-window-size-class dependency"
```

---

### Task 5: NavGraph 添加页面过渡动画

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`

- [ ] **Step 1: 添加 import**

在 NavGraph.kt 的 import 区域添加：

```kotlin
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseIn
import dev.minios.ocremote.ui.theme.AppMotion
```

- [ ] **Step 2: 为 NavHost 添加默认过渡动画**

找到第 202 行的 `NavHost(` 调用：

```kotlin
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
```

替换为：

```kotlin
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = { slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(AppMotion.MEDIUM, easing = EaseOut)
        ) + fadeIn(animationSpec = tween(AppMotion.MEDIUM)) },
        exitTransition = { slideOutHorizontally(
            targetOffsetX = { -it / 3 },
            animationSpec = tween(AppMotion.MEDIUM, easing = EaseIn)
        ) + fadeOut(animationSpec = tween(AppMotion.MEDIUM)) },
        popEnterTransition = { slideInHorizontally(
            initialOffsetX = { -it / 3 },
            animationSpec = tween(AppMotion.MEDIUM, easing = EaseOut)
        ) + fadeIn(animationSpec = tween(AppMotion.MEDIUM)) },
        popExitTransition = { slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(AppMotion.MEDIUM, easing = EaseIn)
        ) + fadeOut(animationSpec = tween(AppMotion.MEDIUM)) }
    ) {
```

效果说明：
- **前进**：新页面从右侧滑入，旧页面向左缩退 1/3（Material Shared Axis 风格）
- **后退**：新页面从左侧 1/3 处滑入，旧页面向右滑出
- 同时叠加 fadeIn/fadeOut 增加平滑感

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
git commit -m "feat(ui): add Material shared-axis page transition animations to NavHost"
```

---

## Phase 2: 统一清理 + HomeScreen 适配

### Task 6: isAmoledTheme() 委托到 LocalAmoledMode

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatColors.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionUiHelpers.kt`

**策略：** 不修改 ~22 个调用方。只修改 `isAmoledTheme()` 的实现，让它委托到 `LocalAmoledMode.current`。所有现有调用方无需任何改动。

- [ ] **Step 1: 修改 ChatColors.kt 中的 isAmoledTheme()**

找到第 10–13 行：

```kotlin
@Composable
internal fun isAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
```

替换为：

```kotlin
@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current
```

添加 import：`import dev.minios.ocremote.ui.theme.LocalAmoledMode`

可以移除不再使用的 import：`MaterialTheme`（如果文件中无其他使用）、`Color`（如果文件中无其他使用）。注意：文件中 `AgentColors` 等仍可能使用 `Color`，请检查后再决定是否移除。

- [ ] **Step 2: 修改 SessionUiHelpers.kt 中的重复 isAmoledTheme()**

找到 `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionUiHelpers.kt` 第 11–13 行的重复定义：

```kotlin
@Composable
internal fun isAmoledTheme(): Boolean {
    val colors = MaterialTheme.colorScheme
    return colors.background == Color.Black && colors.surface == Color.Black
}
```

替换为：

```kotlin
@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current
```

添加 import：`import dev.minios.ocremote.ui.theme.LocalAmoledMode`

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/util/ChatColors.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/SessionUiHelpers.kt
git commit -m "refactor(ui): delegate isAmoledTheme() to LocalAmoledMode CompositionLocal"
```

---

### Task 7: 替换内联 AMOLED 检测

**Files:**
- Modify: Home 模块 — `ServerDialog.kt`（约第 97 行）, `LocalLaunchOptionsDialog.kt`（约第 60 行）
- Modify: Settings 模块 — `SettingsScreen.kt`（约第 121 行）, `SettingsDisplayNames.kt`（约第 79/94 行）
- Modify: Server 模块 — `ServerModelFilterScreen.kt`（约第 55 行）

**模式：** 搜索所有 `MaterialTheme.colorScheme.background == Color.Black` 的出现，替换为 `LocalAmoledMode.current`。

- [ ] **Step 1: 搜索所有内联检测点**

Run: `rg "colorScheme\.background == Color\.Black" --line-number app/src/main/kotlin/`

列出所有匹配的文件和行号。

- [ ] **Step 2: 逐文件替换**

对每个匹配文件：
1. 将 `val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black` 替换为 `val isAmoled = LocalAmoledMode.current`
2. 添加 import `import dev.minios.ocremote.ui.theme.LocalAmoledMode`
3. 如果不再使用 `Color`，移除对应的 import

- [ ] **Step 3: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "refactor(ui): replace inline AMOLED detection with LocalAmoledMode"
```

---

### Task 8: 简化 AmoledCard.kt

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/components/AmoledCard.kt`

- [ ] **Step 1: 替换硬编码圆角**

找到第 71 行 `AmoledElevatedCard` 的 shape 默认值：

```kotlin
shape: Shape = RoundedCornerShape(12.dp),
```

替换为：

```kotlin
shape: Shape = MaterialTheme.shapes.medium,
```

找到 `AmoledSurface`（约第 107 行）的 shape 默认值：

```kotlin
shape: Shape = RoundedCornerShape(0.dp),
```

替换为：

```kotlin
shape: Shape = MaterialTheme.shapes.extraSmall,
```

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/components/AmoledCard.kt
git commit -m "refactor(ui): use theme shapes in AmoledCard components"
```

---

### Task 9: HomeScreen 网格布局适配

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: 在 MainActivity 中计算 WindowSizeClass**

在 `MainActivity.kt` 的 import 区域添加：

```kotlin
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
```

注意：`material3-window-size-class` 的 API 在不同 Compose BOM 版本中可能不同。如果 `calculateWindowSizeClass()` 不可用，请查阅当前 BOM 2026.05.01 对应的 API。优先使用 `calculateWindowSizeClass(activity)` 或 `currentWindowAdaptiveInfo()` 取决于 API 可用性。

在 `setContent` 块中（约第 136 行之后），添加 WindowSizeClass 计算，并通过参数传递给 `NavGraph`。

**方案 A（如果 `calculateWindowSizeClass(Activity)` 可用）：**

```kotlin
val windowSizeClass = calculateWindowSizeClass(this)
```

将 `windowSizeClass` 传递给 `NavGraph`，再由 NavGraph 传递给 HomeScreen。

**方案 B（如果使用 adaptive API）：**

查阅 BOM 2026.05.01 的实际 API，使用对应方法。在 `setContent` 内部：

```kotlin
val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
```

- [ ] **Step 2: 更新 NavGraph 接收 WindowSizeClass**

在 `NavGraph` 函数签名中添加 `windowSizeClass: WindowSizeClass` 参数，并将其传递给需要自适应的屏幕（目前仅 HomeScreen）。

- [ ] **Step 3: 修改 HomeScreen 使用条件布局**

在 `HomeScreen` 函数签名中添加 `windowSizeClass: WindowSizeClass` 参数。

找到第 154–286 行的 `LazyColumn` 块。在其前面添加判断：

```kotlin
val useGrid = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
```

当 `useGrid = true` 时，将 `LazyColumn` 替换为 `LazyVerticalGrid`：

```kotlin
LazyVerticalGrid(
    columns = GridCells.Adaptive(280.dp),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    // 保持相同的 item 内容
}
```

当 `useGrid = false` 时，保持现有 `LazyColumn` 不变。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/MainActivity.kt app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt
git commit -m "feat(ui): add WindowSizeClass adaptive grid layout for HomeScreen"
```

---

### Task 10: SettingsScreen 居中约束

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: 为 SettingsScreen 内容添加居中约束**

找到 `SettingsScreen` 中包裹设置列表的 `Column` + `verticalScroll` 容器。在其外层添加一个 `Box` 居中约束：

```kotlin
Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .widthIn(max = 600.dp)  // 大屏上限制最大宽度
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 现有的设置项内容
    }
}
```

需要添加 import：`import androidx.compose.foundation.layout.widthIn`

- [ ] **Step 2: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(ui): add maxWidth constraint for SettingsScreen on large screens"
```

---

## Phase 3: 打磨

### Task 11: 统一组件动画参数

**Files:**
- Modify: `ChatScreen.kt` — 思考呼吸动画
- Modify: `SessionListScreen.kt` — AnimatedVisibility 参数
- 其他使用硬编码动画时长的文件

- [ ] **Step 1: 搜索硬编码动画参数**

Run: `rg "tween\(|durationMillis\s*=" --line-number app/src/main/kotlin/dev/minios/ocremote/ui/`

列出所有匹配的文件和行号。

- [ ] **Step 2: 替换 ChatScreen 思考呼吸动画**

在 ChatScreen 中找到 `tween(800, FastOutSlowInEasing)` 或类似的长时动画，替换为：

```kotlin
tween(AppMotion.LONG, AppMotion.EmphasizedEasing)
```

添加 import：`import dev.minios.ocremote.ui.theme.AppMotion`

注意：保留 `PulsingDotsIndicator` 的 1200ms 脉冲动画不变（品牌动画）。

- [ ] **Step 3: 替换 SessionListScreen 展开/折叠动画参数**

在 `SessionListScreen.kt` 中的 `AnimatedVisibility` 调用，如果使用了默认或硬编码的动画时长，统一为 `AppMotion.MEDIUM`。

- [ ] **Step 4: 编译验证**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor(ui): unify component animation parameters with AppMotion constants"
```

---

### Task 12: 最终验证

- [ ] **Step 1: 完整编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 所有测试通过

- [ ] **Step 3: 验证清单**

对照 spec 验证清单逐项确认：
- [ ] 所有 Shape 值通过 `MaterialTheme.shapes.*` 访问，无硬编码圆角（`rg "RoundedCornerShape" app/src/main/kotlin/` 确认）
- [ ] 页面导航有平滑过渡动画，前进/后退方向一致（需手动在设备上验证）
- [ ] AMOLED 检测全部通过 `LocalAmoledMode`，无 `== Color.Black` 比较（`rg "background == Color.Black" app/src/main/kotlin/` 应无结果）
- [ ] HomeScreen 在宽屏下显示多列网格（需在模拟器或平板上验证）
