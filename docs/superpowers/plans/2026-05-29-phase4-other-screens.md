# Phase 4: 其余 Screen 模块重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Home/SessionList/Settings/Server 四个 Screen 模块从巨石文件拆分为职责单一的组件结构，ViewModel 委托 UseCase 不直接依赖 data 层，消除跨 Screen 重复组件。

**Architecture:** 每个模块执行相同模式：(1) 提取共享组件到 `ui/components/` → (2) 创建 `components/` 子目录提取私有组件 → (3) 创建 Route 包装 → (4) ViewModel 委托 UseCase。每个 Task 编译验证，保持 UI 行为不变。

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Hilt 2.59.2, kotlinx-coroutines 1.11.0

**前置条件:** Phase 0 (测试基础设施) + Phase 1 (Domain 层：model/repository 接口/UseCase) + Phase 2 (Chat 模块) + Phase 3 (Data 层：Repository 实现、EventReducer 拆分) 均已完成。

---

## File Structure

### 源码根: `app/src/main/kotlin/dev/minios/ocremote/`

| 操作 | 文件路径 | 职责 |
|------|----------|------|
| **共享组件** | | |
| 新建 | `ui/components/indicators/PulsingDotsIndicator.kt` | 脉冲指示器（消除 Home/SessionList 重复） |
| **Home 模块** | | |
| 修改 | `ui/screens/home/HomeScreen.kt` | 仅保留 Scaffold 骨架 + HomeScreen composable |
| 修改 | `ui/screens/home/HomeViewModel.kt` | 委托 UseCase，移除 data 层直接依赖 |
| 移动 | `ui/screens/home/ServerDialog.kt` → `ui/screens/home/components/ServerDialog.kt` | 服务器添加/编辑对话框 |
| 新建 | `ui/screens/home/components/LocalRuntimeCard.kt` | 本地运行时状态卡片 |
| 新建 | `ui/screens/home/components/LocalLaunchOptionsDialog.kt` | 本地启动选项对话框 |
| 新建 | `ui/screens/home/components/ServerCard.kt` | 服务器卡片 |
| 新建 | `ui/screens/home/components/EmptyServersView.kt` | 空服务器列表视图 |
| 新建 | `ui/screens/home/components/BatteryOptimizationBanner.kt` | 电池优化提示横幅 |
| 新建 | `ui/screens/home/HomeRoute.kt` | Route 包装：导航参数 + ViewModel 绑定 |
| **SessionList 模块** | | |
| 修改 | `ui/screens/sessions/SessionListScreen.kt` | 仅保留 Scaffold 骨架 + SessionListScreen composable |
| 修改 | `ui/screens/sessions/SessionListViewModel.kt` | 委托 UseCase，移除 data 层直接依赖 |
| 新建 | `ui/screens/sessions/components/ProjectHeader.kt` | 项目头部组件 |
| 新建 | `ui/screens/sessions/components/OpenProjectDialog.kt` | 打开项目对话框 |
| 新建 | `ui/screens/sessions/components/DirectoryRow.kt` | 目录行组件 |
| 新建 | `ui/screens/sessions/components/NewSessionQuickDialog.kt` | 快速新建会话对话框 |
| 新建 | `ui/screens/sessions/components/SessionRow.kt` | 会话行组件 |
| 新建 | `ui/screens/sessions/SessionListRoute.kt` | Route 包装 |
| **Settings 模块** | | |
| 修改 | `ui/screens/settings/SettingsScreen.kt` | 仅保留 Scaffold 骨架 + SettingsScreen composable |
| 修改 | `ui/screens/settings/SettingsViewModel.kt` | 委托 UseCase，移除 data 层直接依赖 |
| 新建 | `ui/screens/settings/components/SectionHeader.kt` | 设置分组标题 |
| 新建 | `ui/screens/settings/components/SettingsPickerDialog.kt` | 通用设置选择对话框（泛型） |
| 新建 | `ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt` | 本地服务器启动选项对话框 |
| 新建 | `ui/screens/settings/components/ThemePickerDialog.kt` | 主题选择对话框 |
| 新建 | `ui/screens/settings/components/LanguagePickerDialog.kt` | 语言选择对话框 |
| 新建 | `ui/screens/settings/components/FontSizePickerDialog.kt` | 字体大小选择对话框 |
| 新建 | `ui/screens/settings/components/MessageCountPickerDialog.kt` | 消息数选择对话框 |
| 新建 | `ui/screens/settings/components/ReconnectModePickerDialog.kt` | 重连模式选择对话框 |
| 新建 | `ui/screens/settings/components/TerminalFontSizeDialog.kt` | 终端字体大小对话框 |
| 新建 | `ui/screens/settings/components/ImageCompressionDialog.kt` | 图片压缩设置对话框（合并 MaxSide + Quality） |
| 新建 | `ui/screens/settings/components/SettingsDisplayNames.kt` | 显示名称辅助函数 |
| 新建 | `ui/screens/settings/SettingsRoute.kt` | Route 包装 |
| **Server 模块** | | |
| 修改 | `ui/screens/server/ServerSettingsScreen.kt` | 整合到模块内部 |
| 修改 | `ui/screens/server/ServerModelFilterScreen.kt` | 整合到模块内部 |
| 修改 | `ui/screens/server/ServerProvidersScreen.kt` | 提取组件到 components/ |
| 修改 | `ui/screens/server/ServerSettingsViewModel.kt` | 委托 UseCase，移除 data 层直接依赖 |
| 新建 | `ui/screens/server/components/ProviderRow.kt` | 提供商行组件（从 ServerProvidersScreen 提取） |
| 新建 | `ui/screens/server/ServerRoute.kt` | Route 包装 |
| **Domain UseCase 补充** | | |
| 新建 | `domain/usecase/ConnectServerUseCase.kt` | 连接服务器用例 |
| 新建 | `domain/usecase/DisconnectServerUseCase.kt` | 断开服务器用例 |
| 新建 | `domain/usecase/ManageLocalServerUseCase.kt` | 管理本地服务器用例 |
| 新建 | `domain/usecase/GetSessionListUseCase.kt` | 获取会话列表用例 |
| 新建 | `domain/usecase/GetSettingsFlowUseCase.kt` | 观察设置流用例 |
| 新建 | `domain/usecase/ManageServerProvidersUseCase.kt` | 管理服务器提供商用例 |

---

## Task 1: 提取共享组件 PulsingDotsIndicator

消除 HomeScreen 和 SessionListScreen 中的重复 `PulsingDotsIndicator` 实现。

**Files:**
- Create: `ui/components/indicators/PulsingDotsIndicator.kt`
- Modify: `ui/screens/home/HomeScreen.kt` (删除本地 PulsingDotsIndicator，改 import)
- Modify: `ui/screens/sessions/SessionListScreen.kt` (删除本地 PulsingDotsIndicator，改 import)

- [ ] **Step 1: 创建共享 PulsingDotsIndicator**

从 HomeScreen.kt 第 62-112 行提取（这是较完整的版本）。创建文件：

```kotlin
// ui/components/indicators/PulsingDotsIndicator.kt
package dev.minios.ocremote.ui.components.indicators

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated pulsing dots indicator used across multiple screens
 * (loading states, connection indicators).
 */
@Composable
fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    spacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    count: Int = 3
) {
    // 从 HomeScreen.kt 现有实现原样复制（保留完全相同的动画逻辑）
    // 具体实现从 HomeScreen.kt 第 62-112 行完整搬运
    // 保持所有参数默认值和动画参数不变
}
```

**搬运规则：** 将 HomeScreen.kt 中 `PulsingDotsIndicator` 的完整实现（约 50 行）原样复制到新文件，仅修改 `package` 声明和 `import` 语句。不改动任何动画参数、颜色、布局逻辑。

- [ ] **Step 2: 从 HomeScreen.kt 删除本地 PulsingDotsIndicator**

在 `HomeScreen.kt` 中：
1. 添加 `import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator`
2. 删除第 61-112 行的 `@Composable private fun PulsingDotsIndicator(...)` 完整函数体

- [ ] **Step 3: 从 SessionListScreen.kt 删除本地 PulsingDotsIndicator**

在 `SessionListScreen.kt` 中：
1. 添加 `import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator`
2. 删除 `@Composable private fun PulsingDotsIndicator(...)` 完整函数体

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add ui/components/indicators/PulsingDotsIndicator.kt \
        ui/screens/home/HomeScreen.kt \
        ui/screens/sessions/SessionListScreen.kt
git commit -m "refactor: extract shared PulsingDotsIndicator, remove duplicates from Home/SessionList"
```

---

## Task 2: 补充 Domain UseCase — Home/Session/Settings/Server 所需

Phase 1 定义了 5 个核心 UseCase。Phase 4 的 ViewModel 迁移需要额外 UseCase 来封装业务逻辑。

**Files:**
- Create: `domain/usecase/ConnectServerUseCase.kt`
- Create: `domain/usecase/DisconnectServerUseCase.kt`
- Create: `domain/usecase/ManageLocalServerUseCase.kt`
- Create: `domain/usecase/GetSessionListUseCase.kt`
- Create: `domain/usecase/GetSettingsFlowUseCase.kt`
- Create: `domain/usecase/ManageServerProvidersUseCase.kt`

- [ ] **Step 1: 创建 ConnectServerUseCase**

```kotlin
// domain/usecase/ConnectServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: establish connection to a remote server.
 * Encapsulates connection logic previously in HomeViewModel.
 */
class ConnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(server: ServerConfig): Result<Unit> {
        return serverRepository.connect(server)
    }
}
```

- [ ] **Step 2: 创建 DisconnectServerUseCase**

```kotlin
// domain/usecase/DisconnectServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

class DisconnectServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend operator fun invoke(serverId: String): Result<Unit> {
        return serverRepository.disconnect(serverId)
    }
}
```

- [ ] **Step 3: 创建 ManageLocalServerUseCase**

封装 HomeViewModel 中本地服务器管理的复杂逻辑（setup/start/stop/config）。

```kotlin
// domain/usecase/ManageLocalServerUseCase.kt
package dev.minios.ocremote.domain.usecase

import android.content.Context
import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage the local (Termux-based) server lifecycle.
 * Encapsulates setup, start, stop, and configuration operations
 * previously spread across HomeViewModel methods.
 */
class ManageLocalServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    fun getSetupCommand(): String = serverRepository.getLocalSetupCommand()

    suspend fun setupServer(context: Context): Result<Unit> {
        return serverRepository.setupLocalServer(context)
    }

    suspend fun startServer(context: Context): Result<Unit> {
        return serverRepository.startLocalServer(context)
    }

    suspend fun stopServer(context: Context): Result<Unit> {
        return serverRepository.stopLocalServer(context)
    }

    suspend fun refreshState(): Result<LocalServerState> {
        return serverRepository.getLocalServerState()
    }
}

/**
 * Domain model for local server status.
 */
data class LocalServerState(
    val status: String = "unavailable",
    val message: String? = null,
    val fixCommand: String? = null,
    val requiresOverlaySettings: Boolean = false
)
```

**注意：** `ServerRepository` 接口需要在 Phase 3 中已添加 `getLocalSetupCommand()`, `setupLocalServer()`, `startLocalServer()`, `stopLocalServer()`, `getLocalServerState()` 方法。如果 Phase 3 尚未添加这些方法，在 Task 2 执行时先在 `domain/repository/ServerRepository.kt` 接口中追加这些方法签名，然后在 `data/repository/impl/ServerRepositoryImpl.kt` 中委托给 `LocalServerManager`。

- [ ] **Step 4: 创建 GetSessionListUseCase**

```kotlin
// domain/usecase/GetSessionListUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSessionListUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(serverId: String): Flow<List<Session>> {
        return sessionRepository.getSessionsFlow(serverId)
    }
}
```

- [ ] **Step 5: 创建 GetSettingsFlowUseCase**

```kotlin
// domain/usecase/GetSettingsFlowUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.model.AppSettings
import dev.minios.ocremote.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSettingsFlowUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> {
        return settingsRepository.getSettings()
    }
}
```

- [ ] **Step 6: 创建 ManageServerProvidersUseCase**

```kotlin
// domain/usecase/ManageServerProvidersUseCase.kt
package dev.minios.ocremote.domain.usecase

import dev.minios.ocremote.domain.repository.ServerRepository
import javax.inject.Inject

/**
 * Use case: manage provider configurations for a connected server.
 * Encapsulates provider CRUD operations previously in ServerSettingsViewModel.
 */
class ManageServerProvidersUseCase @Inject constructor(
    private val serverRepository: ServerRepository
) {
    suspend fun loadProviders(serverUrl: String, username: String, password: String) =
        serverRepository.loadProviders(serverUrl, username, password)

    suspend fun setProviderEnabled(serverUrl: String, providerId: String, enabled: Boolean) =
        serverRepository.setProviderEnabled(serverUrl, providerId, enabled)

    suspend fun connectProviderApi(serverUrl: String, providerId: String, apiKey: String) =
        serverRepository.connectProviderApi(serverUrl, providerId, apiKey)

    suspend fun disconnectProvider(serverUrl: String, providerId: String) =
        serverRepository.disconnectProvider(serverUrl, providerId)

    suspend fun setModelVisible(serverUrl: String, providerId: String, modelId: String, visible: Boolean) =
        serverRepository.setModelVisible(serverUrl, providerId, modelId, visible)

    suspend fun saveServerConfig(serverUrl: String) =
        serverRepository.saveServerConfig(serverUrl)
}
```

- [ ] **Step 7: 更新 DomainModule 注册新 UseCase**

在 `di/DomainModule.kt` 中追加新的 `@Provides` 方法：

```kotlin
// 在 DomainModule object 中追加：

@Provides @Singleton
fun provideConnectServerUseCase(serverRepository: ServerRepository): ConnectServerUseCase =
    ConnectServerUseCase(serverRepository)

@Provides @Singleton
fun provideDisconnectServerUseCase(serverRepository: ServerRepository): DisconnectServerUseCase =
    DisconnectServerUseCase(serverRepository)

@Provides @Singleton
fun provideManageLocalServerUseCase(serverRepository: ServerRepository): ManageLocalServerUseCase =
    ManageLocalServerUseCase(serverRepository)

@Provides @Singleton
fun provideGetSessionListUseCase(sessionRepository: SessionRepository): GetSessionListUseCase =
    GetSessionListUseCase(sessionRepository)

@Provides @Singleton
fun provideGetSettingsFlowUseCase(settingsRepository: SettingsRepository): GetSettingsFlowUseCase =
    GetSettingsFlowUseCase(settingsRepository)

@Provides @Singleton
fun provideManageServerProvidersUseCase(serverRepository: ServerRepository): ManageServerProvidersUseCase =
    ManageServerProvidersUseCase(serverRepository)
```

- [ ] **Step 8: 编译验证**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 提交**

```bash
git add domain/usecase/ di/DomainModule.kt
git commit -m "feat(domain): add Phase 4 use cases for Home/Session/Settings/Server"
```

---

## Task 3: Home 模块 — 提取组件到 components/

将 HomeScreen.kt (1463 行) 中的 5 个私有 @Composable 提取到独立文件。

**Files:**
- Move: `ui/screens/home/ServerDialog.kt` → `ui/screens/home/components/ServerDialog.kt`
- Create: `ui/screens/home/components/LocalRuntimeCard.kt`
- Create: `ui/screens/home/components/LocalLaunchOptionsDialog.kt`
- Create: `ui/screens/home/components/ServerCard.kt`
- Create: `ui/screens/home/components/EmptyServersView.kt`
- Create: `ui/screens/home/components/BatteryOptimizationBanner.kt`
- Modify: `ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: 创建 components 目录**

```bash
mkdir -p ui/screens/home/components
```

- [ ] **Step 2: 移动 ServerDialog.kt 到 components/**

将 `ui/screens/home/ServerDialog.kt` 移动到 `ui/screens/home/components/ServerDialog.kt`：
1. 修改 package 声明为 `dev.minios.ocremote.ui.screens.home.components`
2. 确保 `fun ServerDialog(...)` 保持 `internal` 可见性（不变）
3. 补充缺失的 import（原文件通过同包隐式引用的可能需要显式 import）

**验证要点：** `ServerDialog` 接受参数 `editingServer: ServerConfig?`, `onSave: (...) -> Unit`, `onDismiss: () -> Unit`。移动后参数签名不变，仅 package 变更。

- [ ] **Step 3: 提取 LocalRuntimeCard**

从 HomeScreen.kt 第 399-799 行提取 `LocalRuntimeCard` composable。

创建 `ui/screens/home/components/LocalRuntimeCard.kt`：
- package: `dev.minios.ocremote.ui.screens.home.components`
- 将 `private fun LocalRuntimeCard(...)` 改为 `internal fun LocalRuntimeCard(...)`
- 完整搬运函数体，补充所有必要的 import
- 参数保持不变：所有参数从 HomeScreen.kt 中 `LocalRuntimeCard` 的签名原样复制

在 `HomeScreen.kt` 中：
1. 添加 `import dev.minios.ocremote.ui.screens.home.components.LocalRuntimeCard`
2. 删除 `LocalRuntimeCard` 函数定义（约 400 行）

- [ ] **Step 4: 提取 LocalLaunchOptionsDialog**

从 HomeScreen.kt 第 801-1165 行提取 `LocalLaunchOptionsDialog` composable。

创建 `ui/screens/home/components/LocalLaunchOptionsDialog.kt`：
- package: `dev.minios.ocremote.ui.screens.home.components`
- 将 `private fun` 改为 `internal fun`
- 完整搬运函数体和所有嵌套的局部 composable/helper

- [ ] **Step 5: 提取 EmptyServersView**

从 HomeScreen.kt 第 1167-1200 行提取 `EmptyServersView` composable。

创建 `ui/screens/home/components/EmptyServersView.kt`：
- 将 `private fun` 改为 `internal fun`
- 完整搬运函数体

- [ ] **Step 6: 提取 ServerCard**

从 HomeScreen.kt 第 1202-1421 行提取 `ServerCard` composable。

创建 `ui/screens/home/components/ServerCard.kt`：
- 将 `private fun` 改为 `internal fun`
- 完整搬运函数体和所有嵌套组件

- [ ] **Step 7: 提取 BatteryOptimizationBanner**

从 HomeScreen.kt 第 1423 行到文件尾提取 `BatteryOptimizationBanner` composable。

创建 `ui/screens/home/components/BatteryOptimizationBanner.kt`：
- 将 `private fun` 改为 `internal fun`
- 完整搬运函数体

- [ ] **Step 8: 更新 HomeScreen.kt import**

在 `HomeScreen.kt` 中添加所有新组件的 import：

```kotlin
import dev.minios.ocremote.ui.screens.home.components.ServerDialog
import dev.minios.ocremote.ui.screens.home.components.LocalRuntimeCard
import dev.minios.ocremote.ui.screens.home.components.LocalLaunchOptionsDialog
import dev.minios.ocremote.ui.screens.home.components.EmptyServersView
import dev.minios.ocremote.ui.screens.home.components.ServerCard
import dev.minios.ocremote.ui.screens.home.components.BatteryOptimizationBanner
import dev.minios.ocremote.ui.components.indicators.PulsingDotsIndicator
```

删除所有已提取组件的函数定义。HomeScreen.kt 应仅保留：
- import 块
- `@Composable fun HomeScreen(...)` 主函数（约 250-300 行的 Scaffold + 状态收集 + LazyColumn）

- [ ] **Step 9: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 提交**

```bash
git add ui/screens/home/
git commit -m "refactor(home): extract HomeScreen components — LocalRuntimeCard, ServerCard, EmptyServersView, etc."
```

---

## Task 4: Home 模块 — 创建 HomeRoute

创建 Route 包装层，解耦导航参数提取和 ViewModel 绑定。

**Files:**
- Create: `ui/screens/home/HomeRoute.kt`
- Modify: `ui/navigation/NavGraph.kt`

- [ ] **Step 1: 创建 HomeRoute.kt**

```kotlin
// ui/screens/home/HomeRoute.kt
package dev.minios.ocremote.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Route wrapper for HomeScreen.
 * Handles ViewModel binding and navigation parameter extraction.
 * NavGraph calls this instead of HomeScreen directly.
 */
@Composable
fun HomeRoute(
    onNavigateToSessions: (String, String, String, String, String) -> Unit,
    onNavigateToServerSettings: (String, String, String, String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val viewModel: HomeViewModel = hiltViewModel()
    HomeScreen(
        viewModel = viewModel,
        onNavigateToSessions = onNavigateToSessions,
        onNavigateToServerSettings = onNavigateToServerSettings,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAbout = onNavigateToAbout
    )
}
```

- [ ] **Step 2: 更新 NavGraph.kt 引用 HomeRoute**

在 `NavGraph.kt` 中：
1. 将 `import dev.minios.ocremote.ui.screens.home.HomeScreen` 改为 `import dev.minios.ocremote.ui.screens.home.HomeRoute`
2. 将 composable 块中的 `HomeScreen(...)` 改为 `HomeRoute(...)`

```kotlin
// 修改前
import dev.minios.ocremote.ui.screens.home.HomeScreen
// ...
HomeScreen(
    onNavigateToSessions = { ... },
    ...
)

// 修改后
import dev.minios.ocremote.ui.screens.home.HomeRoute
// ...
HomeRoute(
    onNavigateToSessions = { ... },
    ...
)
```

- [ ] **Step 3: 修改 HomeScreen 接受 ViewModel 参数**

调整 `HomeScreen.kt` 的 `HomeScreen` 函数签名，改为直接接受 ViewModel 而非内部 hiltViewModel()：

```kotlin
// 修改前
@Composable
fun HomeScreen(
    onNavigateToSessions: (String, String, String, String, String) -> Unit,
    onNavigateToServerSettings: (String, String, String, String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val viewModel: HomeViewModel = hiltViewModel()
    // ...
}

// 修改后
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToSessions: (String, String, String, String, String) -> Unit,
    onNavigateToServerSettings: (String, String, String, String, String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    // 直接使用传入的 viewModel
    // ...
}
```

删除 `HomeScreen` 内部的 `val viewModel: HomeViewModel = hiltViewModel()` 行。

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add ui/screens/home/HomeRoute.kt ui/screens/home/HomeScreen.kt ui/navigation/NavGraph.kt
git commit -m "refactor(home): add HomeRoute wrapper, decouple ViewModel from NavGraph"
```

---

## Task 5: Home 模块 — ViewModel 委托 UseCase

将 HomeViewModel (727+ 行) 从直接依赖 data 层改为委托 UseCase。

**Files:**
- Modify: `ui/screens/home/HomeViewModel.kt`

**当前依赖（需替换）：**
```kotlin
class HomeViewModel @Inject constructor(
    application: Application,
    private val serverRepository: ServerRepository,      // data.repository
    private val api: OpenCodeApi,                        // data.api
    private val localServerManager: LocalServerManager,  // data.repository
    private val settingsRepository: SettingsRepository,  // data.repository
) : AndroidViewModel(application)
```

**目标依赖：**
```kotlin
class HomeViewModel @Inject constructor(
    application: Application,
    private val getServerListUseCase: GetServerListUseCase,
    private val connectServerUseCase: ConnectServerUseCase,
    private val disconnectServerUseCase: DisconnectServerUseCase,
    private val manageLocalServerUseCase: ManageLocalServerUseCase,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val serverRepository: ServerRepository,  // 仅保留 domain 接口引用（用于 saveServer/deleteServer 等尚未提取的操作）
) : AndroidViewModel(application)
```

- [ ] **Step 1: 替换构造函数参数**

将 HomeViewModel 构造函数替换为：

```kotlin
class HomeViewModel @Inject constructor(
    application: Application,
    private val getServerListUseCase: GetServerListUseCase,
    private val connectServerUseCase: ConnectServerUseCase,
    private val disconnectServerUseCase: DisconnectServerUseCase,
    private val manageLocalServerUseCase: ManageLocalServerUseCase,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val serverRepository: ServerRepository,
) : AndroidViewModel(application)
```

更新 import 块：移除 `data.api.OpenCodeApi`, `data.repository.LocalServerManager`, `data.repository.SettingsRepository`（具体实现），替换为 domain 层 UseCase import。

- [ ] **Step 2: 替换服务器列表加载**

找到原 `init` 块或 `loadServers()` 方法中使用 `serverRepository.servers` / `api.*` 的地方：

```kotlin
// 修改前：直接观察 data 层 Flow
serverRepository.servers.collectLatest { ... }

// 修改后：通过 UseCase
getServerListUseCase().collectLatest { ... }
```

- [ ] **Step 3: 替换连接/断开逻辑**

```kotlin
// 修改前
fun connectToServer(serverId: String) {
    viewModelScope.launch {
        // 直接调用 api 或 serverRepository 的实现细节
        val server = ...
        api.connect(server.url, ...)
    }
}

// 修改后
fun connectToServer(serverId: String) {
    viewModelScope.launch {
        val server = _uiState.value.servers.find { it.id == serverId } ?: return@launch
        connectServerUseCase(server)
            .onSuccess { /* 更新 UI state */ }
            .onFailure { /* 更新 error state */ }
    }
}
```

同理替换 `disconnectFromServer` 方法。

- [ ] **Step 4: 替换本地服务器管理逻辑**

将 `refreshLocalRuntimeState()`, `setupLocalServer()`, `startLocalServer()`, `stopLocalServer()`, 以及所有 `localServer*` 设置方法改为委托 `manageLocalServerUseCase`：

```kotlin
// 修改前
fun refreshLocalRuntimeState() {
    val status = localServerManager.checkStatus()
    // ...
}

// 修改后
fun refreshLocalRuntimeState() {
    viewModelScope.launch {
        manageLocalServerUseCase.refreshState()
            .onSuccess { state ->
                _uiState.update { it.copy(localRuntimeStatus = ...) }
            }
            .onFailure { /* 处理错误 */ }
    }
}
```

- [ ] **Step 5: 替换设置读取**

```kotlin
// 修改前
init {
    settingsRepository.showLocalRuntime.collectLatest { ... }
    settingsRepository.localProxyEnabled.collectLatest { ... }
    // ... 20+ 个独立的 Flow 收集
}

// 修改后
init {
    getSettingsFlowUseCase().collectLatest { settings ->
        _uiState.update { currentState ->
            currentState.copy(
                showLocalRuntime = settings.showLocalRuntime,
                localProxyEnabled = settings.localProxyEnabled,
                localProxyUrl = settings.localProxyUrl,
                localProxyNoProxy = settings.localProxyNoProxy,
                localServerAllowLan = settings.localServerAllowLan,
                localServerUsername = settings.localServerUsername,
                localServerPassword = settings.localServerPassword,
                localServerRunInBackground = settings.localServerRunInBackground,
                localServerAutoStart = settings.localServerAutoStart,
                localServerStartupTimeoutSec = settings.localServerStartupTimeoutSec
            )
        }
    }
}
```

这将原来 20+ 个独立的 `collectLatest` 合并为一个，显著简化 init 块。

- [ ] **Step 6: 处理 serverRepository 直接调用**

HomeViewModel 中 `saveServer()` 和 `deleteServer()` 方法如果仍直接调用 `serverRepository`（domain 接口），暂时保留。这些方法在 domain 接口上操作，不违反 DIP。

如果 `serverRepository` 引用的是 `data.repository.ServerRepository`（具体类），改为引用 `domain.repository.ServerRepository`（接口），Phase 3 的 `@Binds` 绑定负责映射。

- [ ] **Step 7: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**如果编译失败：** 逐一检查是否有遗漏的 `api.*` 或 `localServerManager.*` 调用。每找到一个遗漏点，决定是提取新 UseCase 方法还是暂时委托 domain 层的 ServerRepository 接口方法。

- [ ] **Step 8: 提交**

```bash
git add ui/screens/home/HomeViewModel.kt
git commit -m "refactor(home): HomeViewModel delegates to UseCase, removes data layer direct dependencies"
```

---

## Task 6: SessionList 模块 — 提取组件到 components/

将 SessionListScreen.kt (1395 行) 中的私有 @Composable 提取到独立文件。

**Files:**
- Create: `ui/screens/sessions/components/ProjectHeader.kt`
- Create: `ui/screens/sessions/components/OpenProjectDialog.kt`
- Create: `ui/screens/sessions/components/DirectoryRow.kt`
- Create: `ui/screens/sessions/components/NewSessionQuickDialog.kt`
- Create: `ui/screens/sessions/components/SessionRow.kt`
- Modify: `ui/screens/sessions/SessionListScreen.kt`

- [ ] **Step 1: 创建 components 目录**

```bash
mkdir -p ui/screens/sessions/components
```

- [ ] **Step 2: 提取 ProjectHeader**

从 SessionListScreen.kt 第 508-545 行提取。

创建 `ui/screens/sessions/components/ProjectHeader.kt`：
- package: `dev.minios.ocremote.ui.screens.sessions.components`
- 将 `private fun ProjectHeader(...)` 改为 `internal fun ProjectHeader(...)`
- 完整搬运函数体，补充所有必要的 import

- [ ] **Step 3: 提取 OpenProjectDialog**

从 SessionListScreen.kt 第 547-980 行提取（约 430 行，最大的组件）。

创建 `ui/screens/sessions/components/OpenProjectDialog.kt`：
- 完整搬运函数体和所有嵌套 composable
- 注意该函数可能包含 `isAmoledTheme()` 辅助函数的引用，需要在文件内提供或提取为共享工具

- [ ] **Step 4: 提取 DirectoryRow**

从 SessionListScreen.kt 第 982-1051 行提取。

创建 `ui/screens/sessions/components/DirectoryRow.kt`：
- 完整搬运函数体

- [ ] **Step 5: 提取 NewSessionQuickDialog**

从 SessionListScreen.kt 第 1053-1177 行提取。

创建 `ui/screens/sessions/components/NewSessionQuickDialog.kt`：
- 完整搬运函数体

- [ ] **Step 6: 提取 SessionRow**

从 SessionListScreen.kt 第 1179 行到文件尾提取。

创建 `ui/screens/sessions/components/SessionRow.kt`：
- 完整搬运函数体（包含第 1213 行的 `cardContent` lambda）

- [ ] **Step 7: 提取 isAmoledTheme 辅助函数**

`isAmoledTheme()` 在 SessionListScreen.kt 第 59-64 行定义，且可能被多个组件使用。

创建 `ui/screens/sessions/components/SessionUiHelpers.kt`：
```kotlin
package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color

/**
 * Check if the current theme is AMOLED dark mode.
 */
internal fun isAmoledTheme(): Boolean {
    return MaterialTheme.colorScheme.background == Color.Black &&
           MaterialTheme.colorScheme.surface == Color.Black
}
```

如果 Home 模块或其他模块也使用相同的 `isAmoledTheme` 判断，考虑提升到 `ui/components/` 或 `ui/theme/` 作为共享工具。

- [ ] **Step 8: 更新 SessionListScreen.kt import**

添加所有新组件的 import，删除已提取的函数定义。SessionListScreen.kt 应仅保留：
- import 块
- `@Composable fun SessionListScreen(...)` 主函数（骨架 + 状态收集 + LazyColumn）

- [ ] **Step 9: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 提交**

```bash
git add ui/screens/sessions/
git commit -m "refactor(sessions): extract SessionListScreen components — ProjectHeader, SessionRow, etc."
```

---

## Task 7: SessionList 模块 — 创建 Route + ViewModel 委托

**Files:**
- Create: `ui/screens/sessions/SessionListRoute.kt`
- Modify: `ui/screens/sessions/SessionListScreen.kt`
- Modify: `ui/screens/sessions/SessionListViewModel.kt`
- Modify: `ui/navigation/NavGraph.kt`

- [ ] **Step 1: 创建 SessionListRoute.kt**

```kotlin
// ui/screens/sessions/SessionListRoute.kt
package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import java.net.URLDecoder

/**
 * Route wrapper for SessionListScreen.
 * Extracts navigation parameters (serverUrl, username, password, serverName, serverId)
 * and binds ViewModel.
 */
@Composable
fun SessionListRoute(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit
) {
    val viewModel: SessionListViewModel = hiltViewModel()
    SessionListScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateToSession = onNavigateToSession
    )
}
```

- [ ] **Step 2: 修改 SessionListScreen 接受 ViewModel 参数**

```kotlin
// 修改前
@Composable
fun SessionListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit
) {
    val viewModel: SessionListViewModel = hiltViewModel()
    ...
}

// 修改后
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit
) {
    // 直接使用传入的 viewModel
    ...
}
```

- [ ] **Step 3: 更新 NavGraph.kt**

将 NavGraph 中 `SessionListScreen(...)` 改为 `SessionListRoute(...)`。

- [ ] **Step 4: 替换 SessionListViewModel 构造函数**

当前：
```kotlin
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: OpenCodeApi,                    // data.api — 需替换
    private val serverConnection: ServerConnection,  // data.api — 需替换
    private val eventReducer: EventReducer,          // data.repository — 需替换
) : ViewModel()
```

目标：
```kotlin
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getSessionListUseCase: GetSessionListUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val serverRepository: ServerRepository,  // domain 接口
) : ViewModel()
```

- [ ] **Step 5: 替换 ViewModel 中的直接 API 调用**

```kotlin
// 修改前
fun loadSessions() {
    viewModelScope.launch {
        val response = api.getSessions(serverUrl, ...)
        // 处理响应
    }
}

// 修改后
fun loadSessions() {
    viewModelScope.launch {
        getSessionListUseCase(serverId).collectLatest { sessions ->
            _uiState.update { it.copy(sessionGroups = groupSessions(sessions), isLoading = false) }
        }
    }
}
```

同理替换 `createNewSession`, `deleteSession`, `renameSession` 等方法。

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add ui/screens/sessions/ ui/navigation/NavGraph.kt
git commit -m "refactor(sessions): add SessionListRoute, ViewModel delegates to UseCase"
```

---

## Task 8: Settings 模块 — 提取组件到 components/

将 SettingsScreen.kt (1206 行) 中的 18 个私有 @Composable 提取到独立文件。

**Files:**
- Create: `ui/screens/settings/components/SectionHeader.kt`
- Create: `ui/screens/settings/components/SettingsPickerDialog.kt` (泛型对话框)
- Create: `ui/screens/settings/components/LocalServerLaunchOptionsDialog.kt`
- Create: `ui/screens/settings/components/ThemePickerDialog.kt`
- Create: `ui/screens/settings/components/LanguagePickerDialog.kt`
- Create: `ui/screens/settings/components/FontSizePickerDialog.kt`
- Create: `ui/screens/settings/components/MessageCountPickerDialog.kt`
- Create: `ui/screens/settings/components/ReconnectModePickerDialog.kt`
- Create: `ui/screens/settings/components/TerminalFontSizeDialog.kt`
- Create: `ui/screens/settings/components/ImageCompressionDialog.kt`
- Create: `ui/screens/settings/components/SettingsDisplayNames.kt`
- Modify: `ui/screens/settings/SettingsScreen.kt`

- [ ] **Step 1: 创建 components 目录**

```bash
mkdir -p ui/screens/settings/components
```

- [ ] **Step 2: 提取 SettingsDisplayNames（辅助函数）**

从 SettingsScreen.kt 提取所有 `get*DisplayName` 函数：

创建 `ui/screens/settings/components/SettingsDisplayNames.kt`：
```kotlin
package dev.minios.ocremote.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.minios.ocremote.R

@Composable
internal fun getThemeDisplayName(theme: String): String { /* 从原文件搬运 */ }

@Composable
internal fun getFontSizeDisplayName(size: String): String { /* 从原文件搬运 */ }

@Composable
internal fun getLanguageDisplayName(code: String): String { /* 从原文件搬运 */ }

@Composable
internal fun getReconnectModeDisplayName(mode: String): String { /* 从原文件搬运 */ }

@Composable
internal fun getImageMaxSideDisplayName(px: Int): String { /* 从原文件搬运 */ }
```

同时提取 `amoledDialogModifier()` 和 `amoledDialogContainerColor()` 辅助函数到同一文件。

- [ ] **Step 3: 提取 SectionHeader**

从 SettingsScreen.kt 第 603-612 行提取。

- [ ] **Step 4: 提取 SettingsPickerDialog（泛型选择对话框）**

从 SettingsScreen.kt 第 932-1053 行提取 `<K> SettingsPickerDialog`。这是所有具体 PickerDialog 共用的泛型基础组件。

- [ ] **Step 5: 提取各 Picker Dialog**

逐一提取以下对话框组件（每个约 20-40 行）：
- `ThemePickerDialog` (第 1055-1072 行)
- `LanguagePickerDialog` (第 1074-1107 行)
- `FontSizePickerDialog` (第 1109-1126 行)
- `MessageCountPickerDialog` (第 1128-1141 行)
- `ReconnectModePickerDialog` (第 1143-1160 行)
- `TerminalFontSizeDialog` (第 1162-1201 行)

每个文件结构：
```kotlin
package dev.minios.ocremote.ui.screens.settings.components

// ... imports

@Composable
internal fun ThemePickerDialog(
    currentTheme: String,
    onThemeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 从原文件完整搬运
}
```

- [ ] **Step 6: 提取 ImageCompressionDialog**

将 `ImageCompressionMaxSideDialog` 和 `ImageCompressionQualityDialog` 合并为一个文件 `ImageCompressionDialog.kt`（它们功能相关，共享图片压缩设置的上下文）。

- [ ] **Step 7: 提取 LocalServerLaunchOptionsDialog**

从 SettingsScreen.kt 第 614-895 行提取（约 280 行，最大的组件）。

- [ ] **Step 8: 更新 SettingsScreen.kt**

添加所有组件的 import，删除已提取的函数定义。SettingsScreen.kt 应仅保留：
- import 块
- `@Composable fun SettingsScreen(...)` 主函数（骨架 + 状态收集 + 各 Section 布局）

- [ ] **Step 9: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 提交**

```bash
git add ui/screens/settings/
git commit -m "refactor(settings): extract SettingsScreen components — dialogs, pickers, display names"
```

---

## Task 9: Settings 模块 — 创建 Route + ViewModel 委托

**Files:**
- Create: `ui/screens/settings/SettingsRoute.kt`
- Modify: `ui/screens/settings/SettingsScreen.kt`
- Modify: `ui/screens/settings/SettingsViewModel.kt`
- Modify: `ui/navigation/NavGraph.kt`

- [ ] **Step 1: 创建 SettingsRoute.kt**

```kotlin
// ui/screens/settings/SettingsRoute.kt
package dev.minios.ocremote.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    SettingsScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}
```

- [ ] **Step 2: 修改 SettingsScreen 接受 ViewModel 参数**

```kotlin
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) { ... }
```

- [ ] **Step 3: 更新 NavGraph.kt**

将 `SettingsScreen(...)` 改为 `SettingsRoute(...)`。

- [ ] **Step 4: 重构 SettingsViewModel**

当前 SettingsViewModel (319 行) 暴露 30+ 个独立的 `StateFlow` 属性和 30+ 个 setter 方法，全部直接委托 `data.repository.SettingsRepository`。

目标：通过 `GetSettingsFlowUseCase` 和 `UpdateSettingsUseCase` 间接访问。

```kotlin
// 修改前
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository  // data 层具体类
) : ViewModel() {
    val appTheme = settingsRepository.appTheme.stateIn(...)
    val dynamicColor = settingsRepository.dynamicColor.stateIn(...)
    // ... 30+ 个独立 Flow

    fun setTheme(theme: String) { viewModelScope.launch { settingsRepository.setTheme(theme) } }
    fun setDynamicColor(enabled: Boolean) { viewModelScope.launch { settingsRepository.setDynamicColor(enabled) } }
    // ... 30+ 个 setter
}

// 修改后
class SettingsViewModel @Inject constructor(
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase
) : ViewModel() {

    val settings: StateFlow<AppSettings> = getSettingsFlowUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    // 保留细粒度的便捷属性（通过 settings map 过来），保持 SettingsScreen 兼容
    val appTheme = settings.map { it.appTheme }.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val dynamicColor = settings.map { it.dynamicColor }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // ... 其他属性同理

    fun setTheme(theme: String) {
        updateSetting { it.copy(appTheme = theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        updateSetting { it.copy(dynamicColor = enabled) }
    }
    // ... 其他 setter 同理

    private fun updateSetting(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val current = settings.value
            updateSettingsUseCase(transform(current))
        }
    }
}
```

**关键：** 保留 30+ 个便捷属性的公开 API 不变（`appTheme`, `dynamicColor` 等），SettingsScreen 无需修改绑定逻辑。仅改变数据来源从 30+ 独立 Flow 变为 1 个聚合 Flow + map。

- [ ] **Step 5: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add ui/screens/settings/ ui/navigation/NavGraph.kt
git commit -m "refactor(settings): add SettingsRoute, ViewModel delegates to UseCase with aggregated settings"
```

---

## Task 10: Server 模块 — 整合与组件提取

Server 模块有 4 个文件（共约 1281 行），复杂度相对可控。主要工作是提取 ProviderRow 组件和 ViewModel 委托 UseCase。

**Files:**
- Create: `ui/screens/server/components/ProviderRow.kt`
- Create: `ui/screens/server/ServerRoute.kt`
- Modify: `ui/screens/server/ServerProvidersScreen.kt`
- Modify: `ui/screens/server/ServerSettingsViewModel.kt`
- Modify: `ui/navigation/NavGraph.kt`

- [ ] **Step 1: 创建 components 目录**

```bash
mkdir -p ui/screens/server/components
```

- [ ] **Step 2: 提取 ProviderRow**

从 ServerProvidersScreen.kt 第 474-524 行提取 `ProviderRow` composable。

创建 `ui/screens/server/components/ProviderRow.kt`：
```kotlin
package dev.minios.ocremote.ui.screens.server.components

// ... imports

@Composable
internal fun ProviderRow(
    provider: ProviderToggle,
    onEnableToggle: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAuthClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 从 ServerProvidersScreen.kt 完整搬运
}
```

同时搬运 `extractOAuthDeviceCode` 辅助函数（第 469-472 行）到此文件或单独的 `UiHelpers.kt`。

- [ ] **Step 3: 更新 ServerProvidersScreen.kt import**

添加 `import dev.minios.ocremote.ui.screens.server.components.ProviderRow`，删除原函数定义。

- [ ] **Step 4: 创建 ServerRoute.kt**

Server 模块有 3 个 Screen 入口（ServerSettings, ServerProviders, ServerModelFilter），共享同一个 ServerSettingsViewModel。创建统一 Route：

```kotlin
// ui/screens/server/ServerRoute.kt
package dev.minios.ocremote.ui.screens.server

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ServerSettingsRoute(
    onNavigateBack: () -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToModelFilter: () -> Unit
) {
    val viewModel: ServerSettingsViewModel = hiltViewModel()
    ServerSettingsScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateToProviders = onNavigateToProviders,
        onNavigateToModelFilter = onNavigateToModelFilter
    )
}

@Composable
fun ServerProvidersRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: ServerSettingsViewModel = hiltViewModel()
    ServerProvidersScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun ServerModelFilterRoute(
    onNavigateBack: () -> Unit
) {
    val viewModel: ServerSettingsViewModel = hiltViewModel()
    ServerModelFilterScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack
    )
}
```

- [ ] **Step 5: 修改 Server Screen 函数签名**

对 `ServerSettingsScreen`, `ServerProvidersScreen`, `ServerModelFilterScreen` 三个函数：
1. 接受 `viewModel: ServerSettingsViewModel` 参数
2. 删除内部的 `val viewModel: ServerSettingsViewModel = hiltViewModel()` 行

- [ ] **Step 6: 更新 NavGraph.kt**

将 3 个 Screen 调用改为 Route 调用。

- [ ] **Step 7: 重构 ServerSettingsViewModel 构造函数**

当前：
```kotlin
class ServerSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: OpenCodeApi,                    // data.api — 需替换
    private val serverConnection: ServerConnection,  // data.api — 需替换
    private val settingsRepository: SettingsRepository,  // data 层具体类 — 需替换
) : ViewModel()
```

目标：
```kotlin
class ServerSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manageServerProvidersUseCase: ManageServerProvidersUseCase,
    private val serverRepository: ServerRepository,  // domain 接口
) : ViewModel()
```

替换所有 `api.*` 和 `settingsRepository.*` 调用为 UseCase 委托：
- `loadProviders()` → `manageServerProvidersUseCase.loadProviders(serverUrl, username, password)`
- `setProviderEnabled()` → `manageServerProvidersUseCase.setProviderEnabled(...)`
- `connectProviderApi()` → `manageServerProvidersUseCase.connectProviderApi(...)`
- `disconnectProvider()` → `manageServerProvidersUseCase.disconnectProvider(...)`
- `saveServerConfig()` → `manageServerProvidersUseCase.saveServerConfig(serverUrl)`

- [ ] **Step 8: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 提交**

```bash
git add ui/screens/server/ ui/navigation/NavGraph.kt
git commit -m "refactor(server): extract ProviderRow, add ServerRoutes, ViewModel delegates to UseCase"
```

---

## Task 11: 最终验证 — 全量编译 + UI 行为验证

**Files:**
- 全项目

- [ ] **Step 1: 全量编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行全量单元测试（如已有）**

Run: `./gradlew test`
Expected: ALL TESTS PASSED

- [ ] **Step 3: 手动 UI 验证 — Home 模块**

在 Debug APK 中验证：
- [ ] 主页服务器列表正常显示
- [ ] 添加/编辑/删除服务器对话框正常弹出和工作
- [ ] 服务器连接/断开功能正常
- [ ] 本地运行时卡片显示正确状态
- [ ] 本地服务器启动/停止功能正常
- [ ] 空服务器列表视图正确显示
- [ ] 电池优化横幅正确显示

- [ ] **Step 4: 手动 UI 验证 — SessionList 模块**

- [ ] 会话列表正确加载
- [ ] 项目头部信息正确显示
- [ ] 打开项目对话框功能正常
- [ ] 新建会话对话框功能正常
- [ ] 会话选择/删除/重命名功能正常
- [ ] 批量选择/删除功能正常

- [ ] **Step 5: 手动 UI 验证 — Settings 模块**

- [ ] 设置页面各 Section 正确显示
- [ ] 主题切换功能正常
- [ ] 语言切换功能正常
- [ ] 字体大小调整功能正常
- [ ] 各 PickerDialog 正确弹出和选择
- [ ] 本地服务器启动选项对话框功能正常
- [ ] 图片压缩设置功能正常

- [ ] **Step 6: 手动 UI 验证 — Server 模块**

- [ ] 服务器设置页面正确显示
- [ ] 提供商列表正确加载
- [ ] 提供商启用/禁用功能正常
- [ ] API Key 连接功能正常
- [ ] OAuth 认证流程正常
- [ ] 模型筛选页面功能正常
- [ ] 模型可见性切换功能正常

- [ ] **Step 7: 提交最终验证标记**

```bash
git add -A
git commit -m "refactor: Phase 4 complete — all screen modules refactored

- Home: extracted 6 components, added HomeRoute, ViewModel delegates to UseCase
- SessionList: extracted 5 components + helper, added SessionListRoute, ViewModel delegates to UseCase
- Settings: extracted 11 components, added SettingsRoute, ViewModel delegates to UseCase
- Server: extracted ProviderRow, added 3 ServerRoutes, ViewModel delegates to UseCase
- Shared: PulsingDotsIndicator extracted to ui/components/indicators/
- Domain: added 6 new UseCases for Phase 4 needs
- All ViewModels no longer directly depend on data layer concrete classes
- All UI behavior verified identical to pre-refactoring"
```

---

## 完成标准

Phase 4 完成时必须满足以下所有条件：

1. **编译通过** — `./gradlew assembleDebug` BUILD SUCCESSFUL
2. **测试通过** — 全量单元测试 PASSED（如有）
3. **UI 行为不变** — 上述验证清单全部通过
4. **文件结构达标**：
   - HomeScreen.kt < 350 行（骨架 + 状态收集 + LazyColumn）
   - SessionListScreen.kt < 350 行
   - SettingsScreen.kt < 350 行
   - 每个组件文件 < 300 行（特殊大组件除外）
5. **DIP 合规** — 所有 ViewModel 不 import `data.api.*` 或 `data.repository.*` 具体类
6. **DRY 合规** — PulsingDotsIndicator 只存在一份（`ui/components/indicators/`）
7. **Route 包装** — 每个 Screen 模块有对应的 Route 文件
8. **无 placeholder** — 所有组件文件包含完整实现

---

## Self-Review

### 1. Spec Coverage

| Spec 要求 | 对应 Task |
|-----------|-----------|
| HomeScreen.kt (1410行) 拆分 | Task 3 (组件提取) + Task 4 (Route) + Task 5 (ViewModel) |
| SessionListScreen.kt (1395行) 拆分 | Task 6 (组件提取) + Task 7 (Route + ViewModel) |
| SettingsScreen.kt (1206行) 拆分 | Task 8 (组件提取) + Task 9 (Route + ViewModel) |
| Server 模块整合 | Task 10 (ProviderRow提取 + Route + ViewModel) |
| ViewModel 统一迁移（委托 UseCase） | Task 5, 7, 9, 10 |
| 消除跨 Screen 重复组件 | Task 1 (PulsingDotsIndicator) |

### 2. Placeholder Scan

无 TODO/TBD/placeholder。所有代码步骤包含完整实现指引。部分 ViewModel 迁移步骤使用伪代码示意替换模式（因具体实现取决于 Phase 3 完成后的 Repository 接口签名），但每步都明确标注了"修改前"和"修改后"的对比。

### 3. Type Consistency

- `ConnectServerUseCase` 接受 `ServerConfig` → `HomeViewModel.connectToServer` 中查找 `_uiState.value.servers` 中的 `ServerConfig` 传入 ✓
- `ManageLocalServerUseCase.refreshState()` 返回 `Result<LocalServerState>` → `HomeViewModel.refreshLocalRuntimeState` 消费 `LocalServerState` 更新 UI ✓
- `GetSettingsFlowUseCase` 返回 `Flow<AppSettings>` → `SettingsViewModel` 的 `settings: StateFlow<AppSettings>` ✓
- `GetSessionListUseCase` 接受 `serverId: String` → `SessionListViewModel` 传入从 `SavedStateHandle` 提取的 serverId ✓
- 所有 Route 包装传递正确的 ViewModel 类型到 Screen ✓
- NavGraph 调用 `*Route(...)` 而非 `*Screen(...)` ✓
