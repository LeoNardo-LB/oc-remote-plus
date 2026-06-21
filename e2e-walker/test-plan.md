# OC Remote v2 — framework-migration 分支 E2E 测试计划

> **生成时间**: 2026-06-22
> **源码分支**: framework-migration (worktree: `D:\Develop\code\app\oc-wt-framework`)
> **被测应用**: `dev.minios.ocremote.dev` (dev flavor)
> **服务器**: 10.0.2.2:4096 (OpenCode server)
> **测试设备**: emulator-5554 (Pixel 系列, 1080×2400)
> **目标会话目录**: `D:\Develop\code\app\oc-remote` (Windows host, 经 10.0.2.2 映射到 server)

## 改动范围

本次测试聚焦于 `framework-migration` 分支相对 `master` 的两个核心改动：

| # | 改动 | 涉及关键文件 |
|---|------|--------------|
| C1 | FileTreePanel 目录展开/收起（懒加载子目录） | `WorkspaceUiState.kt`、`WorkspaceViewModel.kt`、`FileTreePanel.kt`、`FileTreeUtils.kt` |
| C2 | 终端从手搓 ANSI parser 迁移到 connectbot/termlib | `SessionTerminalInline.kt`、`PtyToTermlibAdapter.kt`、`ServerTerminalWorkspace.kt`、`TermlibModifierManager.kt`、`ChatTerminalView.kt` |

## 全局前置条件 (Pre-Condition)

执行任何测试项前，必须满足：

1. **模拟器在线**: `adb devices` 显示 `emulator-5554` 状态为 `online`
2. **应用已安装并启动**: `dev.minios.ocremote.dev` 已通过 `assembleDevRelease` 安装
3. **服务器可达**: OpenCode server 运行在 `10.0.2.2:4096`，凭证为 `opencode` / `${OPENCODE_SERVER_PASSWORD}`
4. **已添加服务器**: HomeScreen 中存在指向 `10.0.2.2:4096` 的服务器卡片
5. **至少一个会话**: 服务器下存在至少一个 chat session，工作区目录为 `D:\Develop\code\app\oc-remote`
6. **会话已在聊天屏打开**: 起始状态为聊天屏（非终端模式）

## UI 入口路径速查

```
ChatScreen
  └─ ChatTopBar → More (testTag="more_vert", 右上角 ⋮ 按钮)
       ├─ "View Workspace"   → WorkspaceScreen (FileTreePanel 默认)
       └─ "Terminal"         → ChatTerminalView (终端模式)
```

**WorkspaceScreen 顶栏关键元素 (testTag)**:
- `back_button` — 返回聊天
- `panel_file_tree` — 切到文件树面板（Folder 图标）
- `panel_git_changes` — 切到 Git 变更面板（CompareArrows 图标，带 Badge 显示变更数）

**FileTreePanel 工具栏**:
- Refresh 按钮（左上，Refresh 图标）
- "Show ignored" FilterChip（右上）

**FileTreeItem contentDescription 规则**:
- 目录未展开: `"Expand directory <name>"` (KeyboardArrowRight 图标)
- 目录已展开: `"Collapse directory <name>"` (KeyboardArrowDown 图标)
- 目录加载中: 无箭头，显示 16dp CircularProgressIndicator
- 文件: 无 contentDescription，Text 直接显示文件名

**终端虚拟键盘 (TerminalKeyboardOverlay)**:
- 上排: ESC, /, -, HOME, ↑, END, PGUP
- 下排: ↹(TAB), CTRL, ALT, ←, ↓, →, PGDN

---

## T1: FileTreePanel 目录展开/收起

### T1.1 根节点加载 — P0

**描述**: 进入工作区时，根目录节点被自动加载并按规则排序。

**前置条件**: 全局前置条件 + 当前在聊天屏。

**步骤**:
1. 点击聊天屏右上角 `more_vert` (坐标约 1007, 145)
2. 在弹出菜单中点击 "View Workspace" 项
3. 等待 WorkspaceScreen 渲染完成

**成功标准**:
- 顶栏标题显示 `oc-remote`（basename）和副标题 `D:\Develop\code\app\oc-remote`
- `panel_file_tree` 按钮的图标颜色为 `primary`（表示当前激活）
- `panel_git_changes` 按钮显示 Badge 数字（变更数量，通常 > 0）
- 文件树列表显示根节点，**目录在前**（按字母升序），**文件在后**（按字母升序）
- 每个目录行的 contentDescription 形如 `"Expand directory <name>"`
- 验证依据：`WorkspaceViewModel.init { loadDirectory("") }` + `toTreeNodes()` 的排序 `compareBy({ !it.isDirectory() }, { it.name.lowercase() })`
- 预期可见目录：`.git`, `.github`, `.kotlin`, `.maestro`, `app`, `docs`, `e2e-test`, `e2e-workspace`, `gradle`, `maestro`, `scripts`
- 预期可见文件：`.gitignore`, `AGENTS.md`, `backlog.md`, `build.gradle.kts`, `doc-review-report.md`, `gradle.properties`, `gradlew`

**失败迹象**:
- 列表为空且长时间无变化（`rootLoading` 卡住）
- 显示 `rootError` 文案（加载失败）
- 目录文件混合排序（排序逻辑回归）

---

### T1.2 目录展开 — 一级懒加载 — P0

**描述**: 点击未展开的目录行，触发懒加载其子节点，并展开显示。

**前置条件**: T1.1 已通过，当前在 FileTreePanel 且所有目录处于收起状态。

**步骤**:
1. 用 `ui-query find` 定位 contentDescription 为 `"Expand directory app"` 的元素，记录其坐标
2. 点击该坐标
3. 等待 ≤ 2 秒（网络往返 + 渲染）

**成功标准**:
- 被点击目录行的 contentDescription 从 `"Expand directory app"` 变为 `"Collapse directory app"`
- 箭头图标从 `KeyboardArrowRight` (▶) 变为 `KeyboardArrowDown` (▼)
- 该目录下方**新增**子节点列表，缩进加深（depth=1，左侧 padding 增加 `SpacingTokens.LG` = 16dp）
- 子节点按相同排序规则（目录在前、文件在后，各自字母序）
- 验证依据：`WorkspaceViewModel.toggleExpand(path)` 走 "Not loaded → trigger async load" 分支 → `loadDirectory(path)` 成功后调用 `rootNodes.withChildren(path, nodes.toTreeNodes())` 并把 `path` 加入 `expandedDirs`
- 预期 `app/` 子节点：`src/` (目录), `build.gradle.kts` (文件), `proguard-rules.pro` (文件)

**失败迹象**:
- contentDescription 不变（点击未生效或 state 未更新）
- 子节点为空（懒加载失败但未显示错误）
- 子节点排序错乱
- 出现异常的 loading spinner 永久旋转（`loadingDirs` 未被清除）

---

### T1.3 目录展开 — 二级懒加载 — P0

**描述**: 在已展开的一级目录下，点击其中的子目录，触发二级懒加载。

**前置条件**: T1.2 已通过，`app/` 已展开且 `src/` 子目录可见。

**步骤**:
1. 定位 `app/` 下新增的 `"Expand directory src"` 元素
2. 点击它
3. 等待 ≤ 2 秒

**成功标准**:
- `src/` 的 contentDescription 从 `"Expand directory src"` 变为 `"Collapse directory src"`
- `src/` 下方新增 `app/src/` 的子节点（depth=2，缩进再次增加 16dp）
- 验证依据：`flattenTree` 递归调用，当 `treeNode.node.path in expandedDirs && children != null` 时下钻
- 预期 `app/src/` 子节点：`androidTest/`, `main/`, `test/` (三个目录)

**失败迹象**:
- 二级目录展开失败（`withChildren` 递归未命中正确路径）
- depth 缩进未递增（`flattenTree` 的 depth 参数传递错误）
- 父级 `app/` 被错误地折叠（state 更新影响兄弟节点）

---

### T1.4 目录收起 — P0

**描述**: 点击已展开的目录行，收起其整棵子树（不清除缓存的子节点数据）。

**前置条件**: T1.3 已通过，`app/` 和 `app/src/` 都处于展开状态。

**步骤**:
1. 定位 `"Collapse directory app"` 元素
2. 点击它

**成功标准**:
- `app/` 的 contentDescription 从 `"Collapse directory app"` 变回 `"Expand directory app"`
- `app/` 下方所有子孙节点（包括 `src/` 及 `src/` 的子节点）从列表中消失
- 验证依据：`toggleExpand(path)` 走 `"Already expanded → collapse"` 分支，仅从 `expandedDirs` 移除 path，不删除 `dirCache` 数据
- 后续再次展开 `app/` **应立即显示子节点**（走 "Cached but not expanded → expand" 分支，不发起网络请求）

**失败迹象**:
- 仅收起直接子节点，孙子节点仍残留（`flattenTree` 递归判断错误）
- 收起后 `dirCache` 被清空（再次展开会重新发请求）
- `expandedDirs` 状态错误地影响其他目录

---

### T1.5 Show ignored 切换 — P1

**描述**: 切换 "Show ignored" FilterChip，控制是否显示被 `.gitignore` 忽略的节点。

**前置条件**: T1.1 已通过，当前在 FileTreePanel。

**步骤**:
1. 记录当前可见节点数量（关闭 Show ignored 状态下的 baseline）
2. 点击右上角的 "Show ignored" FilterChip
3. 观察列表变化
4. 再次点击关闭

**成功标准**:
- **开启后**: FilterChip 显示选中态（背景色变化、勾选图标）
- **开启后列表新增**被忽略的目录/文件，至少包括：`.gradle/`, `.idea/`, `.replicant/`, `.run/`, `.superpowers/`, `.worktrees/`, `.post-dev-verification/`
- **关闭后**: 这些被忽略节点重新消失，列表恢复到 baseline
- 验证依据：`FileTreeUtils.flattenTree` 中 `nodes.filter { showIgnored || !it.node.ignored }`
- `WorkspaceViewModel.toggleShowIgnored()` 仅翻转 `showIgnored` 字段，不重新请求网络

**失败迹象**:
- 开启 Show ignored 后列表不变（`FileNode.ignored` 字段未被正确设置）
- 关闭后部分被忽略节点仍残留（filter 未生效）
- 切换导致已展开的目录状态丢失

---

### T1.6 面板切换 — 文件树 ↔ Git 变更 — P0

**描述**: 在顶栏点击 `panel_git_changes` 切到 Git 变更面板，再切回文件树。

**前置条件**: T1.1 已通过，当前在 FileTreePanel。

**步骤**:
1. 点击顶栏 `panel_git_changes` 按钮（坐标约 1007, 148）
2. 等待 ≤ 2 秒（首次切换会触发 `loadGitChanges()`）
3. 观察 Git 变更列表
4. 点击顶栏 `panel_file_tree` 按钮（坐标约 881, 148）
5. 观察文件树是否恢复

**成功标准**:
- 切换后 `panel_git_changes` 图标变为 `primary` 色，`panel_file_tree` 变为 `onSurfaceVariant`
- Git 变更面板显示变更列表，每行格式：`<status letter> <file path> <+added> <-removed>`
  - 状态字母：`A`=Added, `M`=Modified, `D`=Deleted, `R`=Renamed, `?`=Untracked, `C`=Copied
- Git 变更数量与顶栏 Badge 数字一致（例如 58）
- 切回文件树后，**之前的展开状态应保留**（验证依据：`switchPanel` 只改 `currentPanel`，不动 `expandedDirs`/`rootNodes`）
- 验证依据：`WorkspaceViewModel.switchPanel(WorkspacePanel.GIT_CHANGES)` 在 `gitChanges.isEmpty() && !isNonGit && !gitLoading` 时触发首次加载

**失败迹象**:
- Git 面板为空（`getVcsStatus` 失败但未显示错误）
- 切换导致文件树状态重置（`switchPanel` 错误地调用了 `refreshRoot`）
- Badge 数字与实际变更数不一致

---

### T1.7 Refresh 按钮 — P1

**描述**: 点击 Refresh 按钮，清空缓存并重新加载根节点。

**前置条件**: T1.2 已通过，至少有一个目录处于展开状态。

**步骤**:
1. 确认当前有展开的目录（例如 `app/` 已展开）
2. 点击工具栏 Refresh 按钮（坐标约 85, 316）
3. 等待 ≤ 2 秒

**成功标准**:
- 所有目录回到收起状态（`expandedDirs` 被清空）
- 根节点列表重新加载（可能短暂显示 loading）
- **showIgnored 状态应保留**（验证依据：`refreshRoot()` 不修改 `showIgnored` 字段）
- 验证依据：`WorkspaceViewModel.refreshRoot()` 清空 `dirCache`、取消所有 `loadJobs`、重置 `expandedDirs` 和 `loadingDirs`，然后调用 `loadDirectory("")`

**失败迹象**:
- 展开状态未被清除（`expandedDirs` 重置失败）
- showIgnored 被错误重置
- 缓存未被清空（展开 `app/` 不发起新请求）

---

### T1.8 文件打开 — P2

**描述**: 点击文件节点，触发文件查看器（onOpenFile 回调）。

**前置条件**: T1.1 已通过。

**步骤**:
1. 在文件树中找到一个**文件**节点（非目录，例如 `AGENTS.md`）
2. 点击它

**成功标准**:
- 触发 `onOpenFile(filePath)` 回调，导航到文件查看器或显示文件内容
- 返回后能正常回到 FileTreePanel

**失败迹象**:
- 点击无反应（`FileTreeItem` 的 `clickable` 分支判断错误）
- 导航崩溃

**注意**: 此项为 P2，因为文件查看器本身不属于本次改动的范围；只验证文件节点的点击回调链是否被破坏。

---

## T2: 终端 termlib 迁移

### T2.1 终端入口可访问 — P0

**描述**: 从聊天屏通过菜单进入终端模式。

**前置条件**: 全局前置条件 + 当前在聊天屏。

**步骤**:
1. 点击聊天屏右上角 `more_vert`
2. 在弹出菜单中点击 "Terminal" 项
3. 等待 ≤ 3 秒（PTY 创建 + WebSocket 连接）

**成功标准**:
- 进入终端全屏视图：黑色背景，占据整个内容区域
- 状态栏颜色被强制为黑色（验证依据：`ChatTerminalView` 的 `DisposableEffect(isTerminalMode)` 设置 `statusBarColor = BLACK`）
- **底部虚拟键盘 overlay 可见**（TerminalKeyboardOverlay）：两排按钮
  - 上排: ESC, /, -, HOME, ↑, END, PGUP
  - 下排: ↹(TAB), CTRL, ALT, ←, ↓, →, PGDN
- 验证依据：`ChatScreen.kt` 的 `isTerminalMode -> { ChatTerminalView(...) }` 分支

**失败迹象**:
- 终端区域为空白或崩溃
- 虚拟键盘 overlay 缺失（`TerminalKeyboardOverlay` 渲染失败）
- 状态栏颜色未改变（`DisposableEffect` 未生效）

---

### T2.2 PTY 连接 + 终端渲染 — P0

**描述**: PTY WebSocket 连接建立后，termlib 正确渲染服务端 shell 输出。

**前置条件**: T2.1 已通过，处于终端模式。

**步骤**:
1. 进入终端模式后等待 ≤ 3 秒
2. 通过 `ui-query dump` 检查终端内容区域
3. （可选）触发一个 shell 命令查看输出更新

**成功标准**:
- 终端区域显示 shell 输出（例如 PowerShell 提示符、上次命令的输出残留）
- 输出文本颜色为浅色（前景色 `0xFFD3D7CF`），背景为纯黑
- **不出现**旧的 Canvas 字符网格渲染异常（乱码、错位、字符重叠）
- **不出现**永久空白（PTY 数据未到达 emulator）
- 验证依据：`PtyToTermlibAdapter.bind(socket)` 启动 readLoop，每个 chunk 调用 `writeInput(bytes, 0, bytes.size)` 推送给 termlib emulator

**失败迹象**:
- 终端区域永久空白（readLoop 未启动或 writeInput 未调用）
- 显示乱码（字符编码错误，应使用 UTF-8）
- 使用旧的 `TerminalEmulator.kt` 渲染逻辑（迁移不完整）

---

### T2.3 虚拟键盘 — 按钮 latch 行为 — P1

**描述**: 点击虚拟键盘的 latch 按钮（CTRL、ALT），验证它们能切换状态且不崩溃。

**前置条件**: T2.1 已通过，处于终端模式。

**步骤**:
1. 用 `ui-query find` 定位 text="CTRL" 的元素（注意：会匹配到 IconButton 容器和 Text 子节点两个结果，使用 `elementIndex: 0` 选容器）
2. 点击它
3. 再次点击（取消 latch）
4. 对 ALT 按钮重复上述操作

**成功标准**:
- 点击 CTRL 后无崩溃
- 点击 ALT 后无崩溃
- （可选）截图对比：latched 状态下按钮应有视觉变化（背景色或边框）
- 验证依据：`ChatTerminalView` 中 `onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched }`，`TermlibModifierManager.setCtrl(active)` 更新 StateFlow，termlib 在下次按键 dispatch 时查询此状态

**失败迹象**:
- 点击导致应用崩溃（`TermlibModifierManager` 实现 `ModifierManager` 接口错误）
- 按钮视觉状态不变（StateFlow 未被收集）

---

### T2.4 虚拟键盘 — 特殊键发送 — P1

**描述**: 点击 ESC、TAB、方向键等特殊键按钮，验证它们能正确发送 ANSI 序列到 PTY。

**前置条件**: T2.1 已通过，处于终端模式，PTY 已连接。

**步骤**:
1. 点击 ESC 按钮 (坐标约 76, 2121)
2. 点击 ↹(TAB) 按钮 (坐标约 76, 2221)
3. 点击 ↑ 方向键 (坐标约 694, 2121)
4. 点击 ↓ 方向键 (坐标约 694, 2221)

**成功标准**:
- 每次点击无崩溃
- 终端内容有相应反馈（例如 shell 历史导航、自动补全）
- 验证依据：`TerminalKeyboardOverlay` 的 `onSendInput = ::sendTerminalChunk`，`applyTerminalModifiers` 和 `applyTermuxFnBindings` 处理特殊键映射
- ESC 发送 `\u001B`，TAB 发送 `\t`，方向键根据 `cursorKeysApplicationMode` 发送对应 ANSI 序列

**失败迹象**:
- 点击导致崩溃
- PTY 无响应（`sendTerminalChunk` 路径中断）
- 方向键发送了错误的序列（DECSET mode 1 解析失败）

---

### T2.5 终端退出 — BackHandler — P0

**描述**: 按系统返回键，应退出终端模式回到聊天屏（而非直接退出会话）。

**前置条件**: T2.1 已通过，处于终端模式，且 `startInTerminalMode = false`（即从聊天屏菜单进入，非深链接启动）。

**步骤**:
1. 按系统返回键 (`adb shell input keyevent KEYCODE_BACK`)
2. 等待 1 秒

**成功标准**:
- 从终端模式切换回聊天消息列表视图
- 聊天屏顶栏恢复正常（非黑色状态栏）
- 终端会话资源**不被立即释放**（验证依据：`ChatTerminalView` 的 `onTerminalModeChanged(false)` 仅切换 UI 状态，`viewModel.closeAll()` 不被调用；终端 tab 在 Drawer 中仍存在）
- 验证依据：`BackHandler(enabled = isTerminalMode)` 在 `!startInTerminalMode` 时调用 `onTerminalModeChanged(false)`

**失败迹象**:
- 返回键无响应（`BackHandler` 未注册）
- 直接退出会话或应用（`onNavigateBack` 被错误调用）
- 终端资源被释放（再次进入终端需要重新创建 PTY）

---

### T2.6 终端 Drawer — Tab 管理 — P2

**描述**: 从屏幕左侧边缘长按或拖拽打开终端 Tab Drawer，验证 Tab 切换/新建/关闭。

**前置条件**: T2.1 已通过，处于终端模式。

**步骤**:
1. 在屏幕左侧边缘（约 x < 18dp 区域）长按或向右拖拽
2. 观察 Drawer 打开
3. （可选）点击 "New tab" 按钮创建新 Tab
4. （可选）点击 Tab 项切换
5. （可选）点击 Tab 右侧的 X 关闭 Tab

**成功标准**:
- Drawer 能正常打开，显示所有终端 Tab
- 每个 Tab 项显示 title（如 "Tab 1"）和连接状态（Offline 时显示红点）
- 离线 Tab 显示刷新按钮（点击触发 `reconnectTab`）
- 每个 Tab 有关闭按钮（X 图标）
- 底部有 "New tab" 和 "Keyboard" 两个按钮
- 验证依据：`ChatTerminalView` 的 `ModalNavigationDrawer` + `LazyColumn(terminalTabs)`

**失败迹象**:
- Drawer 无法打开（手势检测区域或逻辑错误）
- Tab 列表为空（`terminalTabs` StateFlow 未收集）
- 关闭 Tab 后资源泄漏（`closeTab` 的 cleanup 未完成）

---

### T2.7 终端重连上限 — P2

**描述**: 当 PTY 连接断开且重连失败时，不超过 `MAX_RECONNECT_ATTEMPTS = 20` 次。

**前置条件**: T2.1 已通过，处于终端模式，服务器可被临时断开（例如停止 OpenCode server）。

**步骤**:
1. 在终端模式下，停止 host 上的 OpenCode server
2. 等待 ≥ 2 分钟（让重连循环跑完）
3. 通过 `adb logcat` 过滤 `ServerTerminalWorkspace` tag，统计 "Reconnect failed" 日志条数
4. 恢复服务器

**成功标准**:
- 重连尝试次数 ≤ 20（验证依据：`reconnectLoop` 中 `if (snapshot.third >= MAX_RECONNECT_ATTEMPTS) return`）
- 达到上限后，Tab 状态变为 Offline（`connected = false`），不再自动重连
- Drawer 中该 Tab 显示 Offline 标识和刷新按钮
- 恢复服务器后，手动点击刷新按钮能重新连接

**失败迹象**:
- 无限重连（`MAX_RECONNECT_ATTEMPTS` 检查失效）
- 重连成功后状态未更新（`bindConnectedSocketLocked` 未正确发布状态）
- 达到上限后 Tab 被自动关闭（应保留为 Offline 状态供手动重连）

**注意**: 此项为 P2，需要破坏服务器可用性，可能影响其他测试；建议在独立环境或最后执行。

---

## T3: 回归测试

### T3.1 聊天消息渲染 — P0

**描述**: 验证 framework-migration 改动未破坏聊天消息列表的正常渲染。

**前置条件**: 全局前置条件。

**步骤**:
1. 从 HomeScreen 进入一个已有消息的会话
2. 滚动消息列表
3. 点击某条消息的 "Expand" 按钮（折叠的思考/工具调用）

**成功标准**:
- 消息列表正常渲染，无空白卡片、无重叠
- Markdown 内容（代码块、列表、链接）正确渲染
- 工具调用卡片正常展开/收起
- 滚动流畅，无 jitter
- Copy 按钮可点击

**失败迹象**:
- 消息渲染崩溃
- Markdown 组件异常（可能由终端迁移引发的依赖冲突）
- 列表无法滚动

---

### T3.2 导航往返 — P0

**描述**: 验证完整导航链路：聊天 → 工作区 → 聊天 → 终端 → 聊天。

**前置条件**: 全局前置条件 + 当前在聊天屏。

**步骤**:
1. 聊天屏 → More → "View Workspace" → 工作区
2. 工作区 → `back_button` → 聊天屏
3. 聊天屏 → More → "Terminal" → 终端模式
4. 终端模式 → 系统返回键 → 聊天屏
5. 聊天屏 → 系统返回键 → 会话列表
6. 会话列表 → 系统返回键 → HomeScreen

**成功标准**:
- 每一步导航都成功，无 ANR 或崩溃
- 返回后的屏幕状态正确（聊天屏消息保留滚动位置；工作区保留展开状态）
- 无内存泄漏迹象（多次往返后 UI 不卡顿）

**失败迹象**:
- 导航栈错乱（返回到错误的屏幕）
- 状态丢失（展开的目录、终端 Tab 等）
- 多次往返后 OOM 或严重卡顿

---

### T3.3 无崩溃 — Logcat 错误检查 — P0

**描述**: 执行完整测试流程后，检查 logcat 中无 FATAL EXCEPTION 或与改动相关的 ERROR/WARN。

**前置条件**: 已执行 T1.* 和 T2.* 的核心测试项。

**步骤**:
1. 执行完所有 P0 测试项后，运行 `adb logcat -d` 并过滤 error/fatal/exception
2. 重点过滤以下 tag：
   - `ServerTerminalWorkspace`
   - `PtyToTermlibAdapter`
   - `SessionTerminalInline`
   - `TermlibModifierManager`
   - `WorkspaceViewModel`
   - `AndroidRuntime`
   - `FATAL EXCEPTION`

**成功标准**:
- 无 `FATAL EXCEPTION` 导致的应用崩溃
- 与终端/工作区相关的 ERROR 日志数为 0
- WARN 日志可接受（如 "Reconnect failed" 在预期场景中），但需逐条审查

**失败迹象**:
- 任何 `FATAL EXCEPTION` 或 `AndroidRuntime` 错误
- NullPointerException / IllegalStateException 与改动代码相关
- Kotlin 协程的 `CancellationException` 未被正确处理（泄漏到日志）

---

### T3.4 终端焦点保持 — P1

**描述**: 验证 `keyboardEnabled = true` 的修复——进入终端模式时 IME 立即可用，不等 PTY 连接。

**前置条件**: T2.1 已通过，处于终端模式。

**步骤**:
1. 进入终端模式的瞬间观察是否立即弹出软键盘
2. 如果未弹出，点击终端区域任意位置
3. 观察软键盘是否出现

**成功标准**:
- 进入终端模式后，软键盘应在 ≤ 1 秒内自动弹出（或点击后立即弹出）
- 验证依据：`SessionTerminalInline` 中 `keyboardEnabled = true`（注释明确："Terminal mode always owns the keyboard — do not wait for connection"）
- 即使 PTY 尚未连接，键盘也能弹出

**失败迹象**:
- 软键盘延迟直到 PTY 连接完成（说明 `keyboardEnabled` 仍依赖 `connected`）
- 点击终端区域无法唤起软键盘（`focusRequester.requestFocus()` 失败）

---

### T3.5 终端重连后状态恢复 — P1

**描述**: 终端 PTY 断开重连后，之前的屏幕内容和滚动位置应保留（termlib emulator 持有屏幕缓冲）。

**前置条件**: T2.1 已通过，处于终端模式且 PTY 已连接、有可见的 shell 输出。

**步骤**:
1. 记录当前终端显示的内容快照
2. 通过 adb 临时禁用网络或重启 server，触发 PTY 断开
3. 恢复网络/server，等待自动重连或手动点击 Drawer 中的刷新按钮
4. 观察重连后的终端内容

**成功标准**:
- 重连后，之前的 shell 输出内容**仍可见**（emulator 缓冲未被清空）
- 新的 shell 会话在原内容下方继续输出
- Tab title 保持不变
- 验证依据：`RuntimeTab` 的 `emulator` 和 `adapter` 字段在重连过程中不被替换，只有 `socket` 被重新 bind

**失败迹象**:
- 重连后屏幕被清空（emulator 被错误地重建）
- Tab title 变化或 Tab 被关闭
- 重连后无法继续输入（socket bind 失败）

---

## 执行顺序建议

按依赖关系和优先级排序：

1. **T1.1** (根节点加载) → 解锁所有 T1.* 测试
2. **T1.2 → T1.3 → T1.4** (展开/收起核心链路)
3. **T1.5, T1.6, T1.7** (辅助功能)
4. **T2.1** (终端入口) → 解锁所有 T2.* 测试
5. **T2.2** (PTY 连接 + 渲染)
6. **T2.5** (终端退出)
7. **T2.3, T2.4, T3.4** (键盘交互)
8. **T2.6** (Drawer/Tab)
9. **T3.1, T3.2** (回归)
10. **T1.8** (文件打开，P2)
11. **T2.7, T3.5** (重连，P2，可能破坏环境)
12. **T3.3** (logcat 检查，最后执行)

## 探索阶段已验证项

以下测试项在 explore 阶段已实际执行并验证通过：

| ID | 状态 | 备注 |
|----|------|------|
| T1.1 | ✅ | 根节点按预期排序显示 |
| T1.2 | ✅ | `app/` 展开加载 `src/`, `build.gradle.kts`, `proguard-rules.pro` |
| T1.3 | ✅ | `app/src/` 展开加载 `androidTest/`, `main/`, `test/` |
| T1.4 | ✅ | 收起 `app/` 后子孙节点消失 |
| T1.5 | ✅ | 开启 Show ignored 后新增 `.gradle`, `.idea`, `.replicant`, `.run`, `.superpowers`, `.worktrees`, `.post-dev-verification` |
| T1.6 | ✅ | Git changes 显示 58 个变更，格式 `<status> <path> <+n> <-n>` |
| T1.7 | ✅ | Refresh 后展开状态清空，showIgnored 保留 |
| T2.1 | ✅ | 终端模式正常进入，虚拟键盘 overlay 可见 |
| T2.2 | ✅ | termlib 正确渲染 Windows 目录列表（PowerShell 格式） |
| T2.3 | ✅ | CTRL 点击无崩溃（latch 状态切换） |
| T2.4 | ✅ | ESC 点击无崩溃 |
| T2.5 | ✅ | 返回键退出终端模式回聊天 |
| T3.1 | ✅ | 聊天消息列表正常显示 |
| T3.2 | ✅ | 导航往返正常 |
| T3.4 | ✅ | 进入终端模式虚拟键盘立即可用 |

## 已知限制

1. **ADB input text 不可用于终端测试**: Compose 的 `BasicTextField` 和 termlib 的 Terminal composable 不接收 `adb shell input text` 的输入；必须通过应用内虚拟键盘按钮或实际 IME 输入。
2. **ui-query 对 Canvas 渲染的终端文本读取有限**: termlib 使用 Canvas 绘制字符网格，ui-query 抓取的是 AccessibilityTree，可能不包含完整的终端文本；终端内容验证需要配合截图或 logcat。
3. **Windows host shell 差异**: 服务器跑在 Windows 上，shell 是 PowerShell/cmd，ANSI 处理可能与 Linux 不同；部分键映射（如 Fn 键的 Termux 兼容绑定）的行为可能需要针对 Windows shell 调整预期。
4. ** emulator 网络限制**: 模拟器通过 `10.0.2.2` 访问 host，断网测试（T2.7、T3.5）需要在 host 上操作 server 进程，而非简单地禁用模拟器网络。
