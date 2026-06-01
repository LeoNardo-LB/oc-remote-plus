# UI Design Unification — OC Remote v2

> 日期：2026-06-02
> 方案：A — 系统优先（最大化利用 Material3 内建能力）
> 策略：一次设计，分段实现，分期交付
> 原则：统一性为王，复用系统自带设计，简洁高效

---

## 背景

OC Remote v2 是一个 Jetpack Compose + Material3 Android 应用，已具备完善的主题系统（浅色/深色/AMOLED/Dynamic Color），但存在以下不一致：

- 无全局 Shape 系统，圆角值分散内联
- 页面导航瞬间跳变，无过渡动画
- AMOLED 检测逻辑散落多个文件
- 无大屏/平板适配

本设计通过统一利用 Material3 内建设计系统，以最小改动实现最大统一性提升。

---

## 1. Shape 系统

### 1.1 新建文件

**`ui/theme/Shape.kt`**

定义两套 Material3 标准 Shape 层级：

```kotlin
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // Tag、Chip
    small = RoundedCornerShape(8.dp),         // 按钮、输入框
    medium = RoundedCornerShape(12.dp),       // 卡片、对话框（保持当前值）
    large = RoundedCornerShape(16.dp),        // 底部弹窗、大卡片
    extraLarge = RoundedCornerShape(28.dp)    // 全屏对话框、抽屉
)

val AmoledShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(2.dp),        // AMOLED 保留极微圆角
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(8.dp)
)
```

### 1.2 关联文件变更

| 文件 | 变更 |
|------|------|
| `ui/theme/Theme.kt` | `MaterialTheme(shapes = if (amoledDark) AmoledShapes else AppShapes)` |
| `ui/components/AmoledCard.kt` | 移除硬编码 `RoundedCornerShape(0.dp)`，改用 `MaterialTheme.shapes.*` |
| `SettingsScreen` | `RoundedCornerShape(20.dp)` → `MaterialTheme.shapes.large` |

---

## 2. 动画与运动系统

### 2.1 新建文件

**`ui/theme/Motion.kt`**

集中定义动画常量，不建框架：

```kotlin
object AppMotion {
    const val SHORT = 150       // 按钮、开关
    const val MEDIUM = 300      // 过渡、展开
    const val LONG = 500        // 页面级动画

    val StandardEasing = EaseInOut      // 通用缓动
    val EmphasizedEasing = EaseOut       // 进入动画
    val ExitEasing = EaseIn              // 退出动画
}
```

### 2.2 页面过渡动画

在 `navigation/NavGraph.kt` 中为 `NavHost` 添加过渡：

| 路由跳转 | 过渡类型 |
|----------|----------|
| 层级前进（Home→Sessions, Sessions→Chat） | `slideInHorizontally + fadeIn`（从右滑入） |
| 层级后退（Chat→Sessions, Sessions→Home） | `slideOutHorizontally + fadeOut`（向右滑出） |
| 同级切换 | `fadeIn + fadeOut`（淡入淡出） |

> Material Shared Axis 风格，通过 Compose 基础动画 API 手动实现

### 2.3 组件动画统一

| 文件 | 当前 | 替换为 |
|------|------|--------|
| ChatScreen 思考呼吸 | `tween(800ms, FastOutSlowInEasing)` | `tween(AppMotion.LONG, EaseOut)` |
| SessionListScreen 展开 | `AnimatedVisibility` 默认参数 | 统一 `AppMotion.MEDIUM` 时长 |

### 2.4 不变的部分

- `PulsingDotsIndicator` 的 1200ms 脉冲 — 品牌动画，保持独特节奏
- ChatScreen 手势动画（拖动、缩放）— 与运动系统无关
- 触觉反馈 — 已是独立系统

---

## 3. 主题统一性

### 3.1 AMOLED 状态提升为 CompositionLocal

在 `ui/theme/Theme.kt` 中新增：

```kotlin
val LocalAmoledMode = staticCompositionLocalOf { false } // 值极少变化，使用 staticCompositionLocalOf
```

在 `OpenCodeTheme` 内部提供：

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

### 3.2 AMOLED 检测统一

所有使用 AMOLED 判断的地方统一为一行：

```kotlin
val isAmoled = LocalAmoledMode.current
```

替换以下散落模式：
- `MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black`
- `ChatColors.kt` 的 `isAmoledTheme()` 函数

> 保留 `isAmoledTheme()` 函数签名不变，内部实现委托到 `LocalAmoledMode.current`，~22 个现有调用方无需修改

### 3.3 关联文件变更

| 文件 | 变更 |
|------|------|
| `ui/theme/Theme.kt` | 新增 `LocalAmoledMode` + Shape 切换逻辑 |
| `ui/components/AmoledCard.kt` | 简化，视觉常量来自主题 |
| `ui/screens/chat/util/ChatColors.kt` | 重构 `isAmoledTheme()` 委托到 `LocalAmoledMode.current` |
| `ui/screens/chat/util/SessionUiHelpers.kt` | 重构 `isAmoledTheme()` 委托到 `LocalAmoledMode.current` |
| `HomeScreen` | 硬编码检测 → `LocalAmoledMode.current` |
| 其他 `== Color.Black` 判断处 | 同上 |

### 3.4 统一后的数据流

```
AppSettings.amoledDark
  → OpenCodeTheme 传入
  → LocalAmoledMode 下发
  → 各组件统一读取
```

---

## 4. 自适应布局

### 4.1 依赖与基础设施

**新增依赖**（`app/build.gradle.kts`）：

```kotlin
implementation("androidx.compose.material3:material3-window-size-class")
// 版本由 Compose BOM 管理，无需显式指定
```

在 `MainActivity.kt` 中计算并下发：

```kotlin
val windowSizeClass = calculateWindowSizeClass(this)
```

通过 CompositionLocal 或直接传递给 NavGraph。

### 4.2 各屏幕适配方案

**HomeScreen（优先级最高）：**

| 宽度 | 布局 |
|------|------|
| Compact (< 600dp) | 现有 `LazyColumn`（不变） |
| Medium / Expanded (>= 600dp) | `LazyVerticalGrid(Adaptive(280.dp))`，自动分列 |

**SettingsScreen：**

| 宽度 | 布局 |
|------|------|
| Compact | 现有单列列表（不变） |
| Medium / Expanded | 添加 `maxWidth(600.dp)` 居中约束 |

### 4.3 不改的屏幕

| 屏幕 | 原因 |
|------|------|
| ChatScreen | 布局天然全宽，且编辑协议严格 |
| SessionListScreen | 目录结构适合单列 |
| AboutScreen | 内容太少，不值得适配 |

---

## 实施分期

### 第一期（基础设施 + 最高感知）

1. 新建 `Shape.kt` — AppShapes + AmoledShapes
2. 新建 `Motion.kt` — AppMotion 常量
3. `Theme.kt` — 新增 `LocalAmoledMode` + Shape 切换
4. `NavGraph.kt` — 添加页面过渡动画
5. `build.gradle.kts` — 添加 `material3-window-size-class` 依赖

### 第二期（统一清理 + HomeScreen 适配）

1. 全局替换 AMOLED 检测为 `LocalAmoledMode.current`
2. `AmoledCard.kt` 简化
3. `HomeScreen.kt` 网格布局适配
4. `SettingsScreen.kt` 居中约束

### 第三期（打磨）

1. 组件级动画参数统一（逐步替换硬编码为 `AppMotion.*`）
2. 验证所有屏幕在各模式下的表现

---

## 不做的事情

- 不引入自定义字体（保持系统字体）
- 不改变 Material3 设计语言
- 不重构 ChatScreen 布局结构
- 不引入新的第三方 UI 库
- 不改动品牌动画（PulsingDotsIndicator）
- 不做折叠屏状态（Activity 生命周期）特殊处理

---

## 验证标准

- [ ] 所有 Shape 值通过 `MaterialTheme.shapes.*` 访问，无硬编码圆角
- [ ] 页面导航有平滑过渡动画，前进/后退方向一致
- [ ] AMOLED 检测全部通过 `LocalAmoledMode.current`，无 `== Color.Black`
- [ ] HomeScreen 在 >= 600dp 宽度下显示多列网格
- [ ] `compileDevDebugKotlin` 编译通过
- [ ] `testDevDebugUnitTest` 全部通过
