# Android Compose 生态库调研 — 代码/Diff/文件树/终端组件

> 调研日期：2026-06-20
> 目标：评估可替代 OC Remote 手搓组件的 Compose 生态库

## 当前 OC Remote 技术栈（基线）

| 组件 | 当前方案 | 状态 |
|------|----------|------|
| Markdown 渲染 | `com.mikepenz:multiplatform-markdown-renderer:0.41.0` (core+m3+coil3+code) | ✅ 已用库 |
| 语法高亮 | `dev.snipme:highlights:1.1.0` | ✅ 已用库 |
| 代码块 UI | `OcCodeBlock.kt` — 包装 mikepenz 的 `MarkdownHighlightedCodeBlock` | 🔧 半手搓 |
| Diff 查看 | `DiffHelpers.kt` (223 行) — 纯手搓 | ❌ 手搓 |
| 终端模拟 | `ChatTerminalView.kt` + WebSocket PTY — 纯手搓 Canvas 渲染 | ❌ 手搓 |
| 文件树 | 无此功能 | N/A |

---

## 1. 语法高亮 / 代码查看器

| 排名 | 库 | Stars | 最后更新 | 语言支持 | Compose | 评价 |
|------|-----|-------|----------|----------|---------|------|
| **★1** | [**dev.snipme:highlights**](https://github.com/SnipMeDev/Highlights) | 178 | 2025-09 (v1.1.0) | 18 种 (Kotlin/Java/Python/JS/Go/Rust/Swift/C/C++/C#/Dart/PHP/Ruby/Shell/TS/Perl/CoffeeScript) | 纯 Kotlin KMP，产出 AnnotatedString | **已在用**。纯 Kotlin 无 WebView，同步/异步模式，结果缓存，自定义主题。是当前最优解。 |
| **★2** | [**SnipMeDev/KodeView**](https://github.com/SnipMeDev/KodeView) | 113 | 2026-01 | 同上（基于 Highlights） | `CodeTextView` / `CodeEditText` Composable | Highlights 作者的 Compose 封装。若要从 mikepenz 解耦代码块 UI，可直接用。但 OC Remote 的 `OcCodeBlock` 已做了更好的定制（copy button、word wrap），收益有限。 |
| 3 | [**hossain-khan/compose-highlight**](https://github.com/hossain-khan/android-compose-highlight) | 7 | 2026-05 (v0.31.0, 非常活跃) | **190+ 种**（Highlight.js） | `SyntaxHighlightedCode` Composable + `HighlightThemeProvider` | WebView + JS bridge 方案。语言覆盖碾压 Highlights（190+ vs 18），但有 WebView 开销（~200ms 首次、~2-4MB RAM）。单例共享 WebView 缓解了问题。**若需要 Markdown 中渲染非主流语言代码（如 Lua/SQL/Dockerfile），值得考虑作为 fallback。** |
| 4 | [**Qawaz/compose-code-editor**](https://github.com/Qawaz/compose-code-editor) | ~150+ | 活跃 | Prettify 解析器，~20 种 | `parseCodeAsAnnotatedString()` + Text/TextField | 无 WebView，Prettify 语法。比 Highlights 更早的方案，API 更底层。无明显优势。 |
| 5 | [**Monkopedia/kodemirror**](https://github.com/Monkopedia/kodemirror) | 8 | 2026-04 (v0.2.0) | 20+ 种（CodeMirror 6 port） | 原生 Compose 组件 | **功能最全**：语法高亮 + Vim 模式 + 搜索替换 + 自动补全 + 代码折叠 + **side-by-side diff/merge** + 协同编辑。但 Android "未在真机测试"，单实例限制，大文件卡顿。**高风险高回报。** |
| 6 | [**uni7corn/SweetEditor-Compose**](https://github.com/uni7corn/SweetEditor-Compose) | 新 | 2026-04 | C++17 核心引擎 | Compose Multiplatform 包装 | IDE 级编辑器内核（inlay hints、phantom text、fold regions、completion pipeline）。极度早期，不适合生产。 |
| 7 | [**Irineu333/Highlight**](https://github.com/Irineu333/Highlight) | 43 | 2025-11 (v2.3.0) | 自定义正则 | `rememberAnnotatedString` | 正则驱动的高亮框架，非语法感知。适合自定义文本格式，不适合代码。 |
| 8 | [**markusressel/KodeEditor**](https://github.com/markusressel/KodeEditor) | ~100+ | 维护中 | 自定义 RuleBook | `KodeEditor` Composable | 支持行号 + minimap + pinch-to-zoom。基于 View 封装。 |

### 结论：语法高亮

**保持 `dev.snipme:highlights` 不变。** 它已经是该领域的最优解：
- 纯 Kotlin，无 WebView 开销
- KMP，与项目技术栈一致
- 活跃维护，18 种语言覆盖 OC Remote 需求（Kotlin/Python/JS/Go/Bash/JSON/YAML 等）
- 已与 mikepenz 深度集成

唯一短板：若 OpenCode 返回非常见语言（Lua、SQL、Dockerfile、TOML），Highlights 不支持。可考虑 `compose-highlight` 作为 fallback 层，但增加了 WebView 依赖。

---

## 2. Diff 查看器

**关键发现：Android Compose 生态中不存在成熟的专用 Diff Viewer 库。** 所有搜索结果均为 React/Vue/Dart/Web 方案。

| 排名 | 方案 | 类型 | 评价 |
|------|------|------|------|
| **★1** | **保持手搓 `DiffHelpers.kt`** | 自维护 | OC Remote 的 diff 需求简单（Edit/Write 工具的 +/- 行渲染），223 行手搓代码完全够用。引入外部库反而增加复杂度。 |
| 2 | [**Monkopedia/kodemirror**](https://github.com/Monkopedia/kodemirror) | Compose 库（含 diff/merge） | 内置 side-by-side diff/merge，但 Android 未测试，且引入整个 CodeMirror 编辑器栈过于重量级。 |
| 3 | 自行集成 diff 算法库 | 底层库 | 如 [java-diff-utils](https://github.com/java-diff-utils/java-diff-utils)（纯 Java Myers diff 算法），在算法层替换手搓的行级 diff，UI 仍用 Compose 渲染。 |

### 结论：Diff 查看器

**保持手搓方案。** 理由：
1. 无成熟 Compose 原生 diff 库
2. OC Remote 的 diff 场景有限（Edit 工具的 before/after、ApplyPatch），不需要 GitHub 级别的 split/unified 切换
3. `DiffHelpers.kt` 的 223 行代码维护成本低于引入+适配外部库
4. 若需增强（word-level diff、syntax-highlighted diff），可叠加 `dev.snipme:highlights` 给 diff 行加色，无需换库

---

## 3. 文件树 / 树形组件

OC Remote 目前无文件树功能。若未来需要（如浏览远端项目结构），以下库可用：

| 排名 | 库 | Stars | 最后更新 | Compose 原生 | 评价 |
|------|-----|-------|----------|-------------|------|
| **★1** | [**adrielcafe/Bonsai**](https://github.com/adrielcafe/bonsai) | **420** | 活跃（Maven Central） | ✅ 纯 Compose | **标杆库**。DSL 构建 Tree，支持：无限深度、懒加载、展开/折叠、选中、点击、自定义样式/图标。内置 `FileSystemTree`（文件系统集成）和 `JsonTree`（JSON 集成）。MIT 协议。**若需要文件树，这是首选。** |
| 2 | [**vooft/compose-treeview**](https://github.com/vooft/compose-treeview) | 14 | 2024-12+ | ✅ Compose Multiplatform | Bonsai 的 fork，增加了 iOS/Wasm 支持，Maven Central 发布。若需要 KMP 跨平台优于 Bonsai。 |
| 3 | [**AdrianKuta/Tree-Data-Structure**](https://github.com/AdrianKuta/Tree-Data-Structure) | ~100+ | 活跃 | ✅ `LazyTree` composable | 数据结构库 + Compose UI。通用 n-ary tree，`tree-structure-compose` 模块提供 `LazyTree`。比 Bonsai 更底层，自定义自由度高但开箱即用功能少。 |
| 4 | [**dingyi222666/TreeView**](https://github.com/dingyi222666/TreeView) | 146 | 2024-11 | ❌ RecyclerView | View-based，非 Compose。支持懒加载、水平滚动、选中模式。若能接受 View 互操作可用，但不推荐新项目。 |
| 5 | [**io.gitlab.kompose:tree-view**](https://gitlab.com/kompose/treeview) | 少 | 2024 | ✅ Compose | 动态懒加载树视图。Maven Central 可用但文档和社区几乎为零。 |

### 结论：文件树

**若需要文件树功能，选 Bonsai。** 理由：
- 420 stars，社区认可度最高
- 纯 Compose，与项目技术栈一致
- `FileSystemTree` 直接支持文件系统场景（虽然 OC Remote 是远端文件，需自定义 TreeNode）
- DSL 简洁，API 稳定

**但 OC Remote 是远程客户端**，文件在服务器端。Bonsai 的 `FileSystemTree` 基于 `java.io.File`，无法直接用。需要自定义 `Tree<InternetNode>` 用 DSL 构建，或实现自定义 node generator 从 API 拉取目录结构。Bonsai 的架构支持这种自定义（`Branch`/`Leaf` 接受任意泛型）。

---

## 4. Markdown 渲染

**当前已用 `com.mikepenz:multiplatform-markdown-renderer:0.41.0`，这是最优解，无需替换。**

能力确认：

| 特性 | 支持版本 | OC Remote 状态 |
|------|----------|---------------|
| 基础 Markdown（标题/列表/链接/图片） | ✅ 一直支持 | ✅ 在用 |
| **表格渲染** | ✅ v0.30.0+ | ✅ OC Remote 有 `MarkdownTable.kt` |
| **代码语法高亮** | ✅ v0.27.0+（通过 Highlights 集成） | ✅ OC Remote 有 `OcCodeBlock.kt` |
| Coil3 图片加载 | ✅ `multiplatform-markdown-renderer-coil3` | ✅ 在用 |
| Material 3 集成 | ✅ `multiplatform-markdown-renderer-m3` | ✅ 在用 |
| Extended Spans（圆角等高级文本样式） | ✅ 集成 `saket/extended-spans` | 可选启用 |
| 自定义组件（codeBlock/codeFence/custom） | ✅ `markdownComponents { }` DSL | ✅ OC Remote 已用 |
| 异步解析 | ✅ 内置 | ✅ |

**结论：Markdown 渲染无需改动。** mikepenz 已经覆盖了表格、代码高亮、图片等全部需求，且 OC Remote 已深度定制（`OcCodeBlock`、`MarkdownTable`）。

---

## 5. 终端模拟器

**这是手搓代码最重的部分（`ChatTerminalView.kt` + WebSocket PTY），也是替代收益最高的领域。**

| 排名 | 库 | Stars | 最后更新 | Compose 原生 | 评价 |
|------|-----|-------|----------|-------------|------|
| **★1** | [**connectbot/termlib**](https://github.com/connectbot/termlib) | 8 | 2026-03 (非常活跃) | ✅ **纯 Compose** | **最佳候选**。ConnectBot 团队（Android 终端领域权威）打造的新一代 Compose 终端。特性：libvterm JNI 核心、VT100/ANSI 准确模拟、256 色 + true color、East Asian 双宽字符、组合字符、文本选择（放大镜效果）、缩放、动态调整大小、多字体。Apache-2.0。**纯渲染组件**，调用方管理 PTY/连接/I-O——正好契合 OC Remote 的 WebSocket PTY 架构。 |
| 2 | [**termux/terminal-view**](https://github.com/termux/termux-app/wiki/Termux-Libraries) | (termux-app 54K) | 维护中 | ❌ View-based | **工业标准**。Termux 的终端 View，最成熟、最稳定。但基于 Android View（非 Compose），需 `AndroidView` 互操作。Java 编写。`com.termux.termux-app:terminal-view:0.118.0`。若 termlib 不够稳定可退而求其次。 |
| 3 | [**pavelc4/Rin**](https://github.com/pavelc4/Rin) | 21 | 2026 活跃 | ✅ Compose + Material 3 | 完整终端 App（非库），Kotlin + Rust JNI + vte ANSI parser。可参考其架构，但非可直接引用的库。 |
| 4 | [**dedeadend/DTerminal**](https://github.com/dedeadend/DTerminal) | 少 | 活跃 | ✅ Compose + Material 3 | 完整终端 App，Hilt + MVVM + Compose。技术栈与 OC Remote 一致，可参考。非库。 |
| 5 | [**libtermux-android**](https://github.com/libtermux/libtermux-android) | 0 (新) | 2026-04 | ✅ 含 `TerminalView` | 嵌入完整 Termux Linux 环境。**过于重量级**——OC Remote 只需要终端渲染，不需要 bootstrap 整个 Linux。 |

### 结论：终端模拟器

**强烈建议评估 `connectbot/termlib` 替换手搓的 `ChatTerminalView.kt`。** 理由：

1. **架构完美契合**：termlib 是"纯渲染组件"，调用方管理 PTY/连接。OC Remote 已有 WebSocket PTY 管道，只需把渲染层从手搓 Canvas 换成 `TermScreen` composable
2. **技术权威性**：ConnectBot 是 Android 终端领域历史最悠久的开源项目（Kenny Root / kruton），libvterm 是 VS Code 终端同款核心
3. **功能完整性远超手搓**：256 色、true color、双宽字符、组合字符、选择、缩放——手搓代码难以达到
4. **风险**：8 stars，较新（2025-11 创建）。但 ConnectBot 主项目信誉背书，且 Apache-2.0 允许 fork 自维护

**迁移路径**：
```
WebSocket PTY → [现有 SseConnectionManager 不变]
                ↓
        emulator.writeInput(data)  ← termlib API
                ↓
        TermScreen(terminalEmulator) ← termlib Composable
                ↓
        emulator.onKeyboardInput → WebSocket  ← 回写 PTY
```

**备选方案**：若 termlib 不够稳定，用 Termux `terminal-view` + `AndroidView` 包装。更重但最可靠。

---

## 6. Jetpack Compose 原生组件检查

| 组件 | Compose 原生提供？ | 说明 |
|------|-------------------|------|
| **TreeView** | ❌ 不存在 | Material 3 和 Compose Foundation 均无原生 TreeView。必须用第三方库（Bonsai）或手搓 `LazyColumn` + 缩进。 |
| **代码编辑器** | ❌ 不存在 | 无原生代码编辑器。`BasicTextField` 是最底层的文本输入。 |
| **Diff Viewer** | ❌ 不存在 | 无原生 diff 渲染组件。 |
| **终端** | ❌ 不存在 | 无原生终端模拟器。 |
| **Markdown** | ❌ 不存在 | 无原生 Markdown 渲染。（注：Android View 体系有 `markwon`，但非 Compose） |
| **语法高亮** | ❌ 不存在 | `AnnotatedString` 是底层原语，但无高亮引擎。 |
| **可展开列表** | ✅ `LazyColumn` | 可手搓树形结构，但无开箱即用的展开/折叠/缩进语义。 |

**结论：Compose 生态在这些 IDE 级组件上几乎没有原生支持，必须依赖第三方库或手搓。**

---

## 总结推荐

按优先级排序的行动建议：

| 优先级 | 行动 | 组件 | 理由 |
|--------|------|------|------|
| 🟢 **P0** | **保持不变** | Markdown 渲染 (mikepenz) | 已是最优解，深度定制完成 |
| 🟢 **P0** | **保持不变** | 语法高亮 (highlights) | 已是最优解，纯 Kotlin 无 WebView |
| 🟡 **P1** | **保持不变** | Diff 查看 (手搓) | 无成熟替代品，手搓 223 行够用 |
| 🟠 **P2** | **评估迁移** | 终端模拟器 → connectbot/termlib | 替代收益最高，手搓 Canvas 终端是最重的技术债。termlib 架构完美契合（纯渲染 + 调用方管 PTY） |
| 🔵 **P3** | **按需引入** | 文件树 → Bonsai | 仅当新增文件浏览功能时引入。DSL 支持自定义远端节点。 |
| ⚪ **低** | **观望** | kodemirror | 若其 Android 稳定性成熟，可一次性解决代码编辑 + diff/merge。目前风险过高。 |

### 不建议引入的

| 库 | 理由 |
|----|------|
| `compose-highlight` (WebView 方案) | OC Remote 已有纯 Kotlin 高亮，WebView 开销不划算。除非需要 190+ 语言。 |
| `SweetEditor-Compose` | 极度早期，C++ 核心增加构建复杂度 |
| `libtermux-android` | 嵌入完整 Linux 环境，OC Remote 只需终端渲染 |
| `dingyi222666/TreeView` | RecyclerView-based，非 Compose 原生 |
