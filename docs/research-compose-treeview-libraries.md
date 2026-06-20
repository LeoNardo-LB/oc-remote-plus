# Compose TreeView / File-Tree 库调研报告

> 调研日期：2026-06-20 ｜ 范围：Android Jetpack Compose + Compose Multiplatform 生态
>
> **核心结论：截至 2026-06，Google/JetBrains/Accompanist 均未提供官方 Compose 树组件。**
> Compose Foundation 无 TreeView；JetBrains compose-multiplatform 仅有一个 `codeviewer` 示例 App（非库）。
> 所有可选方案均来自个人维护者。最热门的 `adrielcafe/bonsai` 实际上**已停止维护**（最后发布 2022-04）。

---

## 候选库一览（按可信度排序）

| # | 库名 | 维护者 | Stars | 最后活动 | 最新版本 | 活跃？ | 背书 | License | Maven Central | 平台 | 关键能力 |
|---|------|--------|------:|----------|----------|--------|------|---------|---------------|------|----------|
| 1 | **[adrielcafe/bonsai](https://github.com/adrielcafe/bonsai)** | Adriel Café（@stone-payments，同时维护 Voyager 3k★） | 420 | 2022-04（commit & release） | 1.0.0 (2022-04-21) | ❌ **已停维护** ~4 年 | 知名个人（Voyager 作者） | MIT | ✅ `cafe.adriel.bonsai:bonsai-*` | Android + Desktop | 懒加载✓ 无限深度✓ 文件系统✓ JSON✓ DSL✓ 选择/展开/样式✓ |
| 2 | **[vooft/compose-treeview](https://github.com/vooft/compose-treeview)** | vooft（个人） | 14 | 2026-05-01（最新 release 0.6.1） | 0.6.1 (2026-05-01) | ✅ **非常活跃**（每 1-3 月发版） | 个人（Bonsai fork） | MIT | ✅ `io.github.vooft:compose-treeview-core` | Android + iOS + Desktop + Wasm | 基于 Bonsai 重构，KMP 全平台，Web demo 可用 |
| 3 | **[AdrianKuta/Tree-Data-Structure](https://github.com/AdrianKuta/Tree-Data-Structure)** (`tree-structure-compose` 模块) | AdrianKuta（个人） | 21 | 2026-06-08（v4.2.0） | 4.2.0 (2026-06-08) | ✅ **非常活跃**（Compose 模块刚于 6 月 7-8 日新增） | 个人 | MIT | ✅ `com.github.adriankuta:tree-structure-compose:4.2.0` | Android + iOS + Desktop + Wasm + JS | `LazyTree` + `TreeNodeRow`（foundation-only，无 Material 依赖）+ 完整数据结构（遍历/导航/栈安全） |
| 4 | [mr0xf00/lazytreelist](https://github.com/mr0xf00/lazytreelist) | mr0xf00 | 13 | 2022-10（仅 1 次 release） | 0.1.0 (2022-10-20) | ❌ **已死** ~3.5 年无更新 | 个人 | Apache-2.0 | ✅ `io.github.mr0xf00:lazytreelist:0.1.0` | 仅 Android | `LazyTreeList` 实验性 DSL，仅基础功能 |
| 5 | [AmrDeveloper/TreeView](https://github.com/AmrDeveloper/treeview) | AmrDeveloper | 195 | 持续更新 | 2.1.0+ | ✅ 活跃 | 个人 | MIT | ✅ `com.github.amrdeveloper:treeview` | Android（**非 Compose**，基于 RecyclerView） | 多 root、选择、动态增删、2D 滚动。**不符合"Compose 组件"要求**，仅作参考 |

---

## 重要排除项（看起来像但不是）

| 项目 | 排除原因 |
|------|----------|
| **JetBrains/compose-multiplatform** | 框架本体，不包含树组件。仅有一个 `codeviewer` 示例 App（非可复用库） |
| **androidx.compose.foundation** | Google 官方 Foundation 层无 TreeView。Compose 1.12.0-alpha03 / BOM 2026.05.00 均未规划 |
| **Accompanist** | 已停止维护（Google 官方建议迁移到原生 Compose），从未提供过树组件 |
| **Chen-Xi-g/MultiplatformUI** | KMP UI 组件库，但组件清单中**无 Tree**，仅有 SwipeButton 等 |
| **zahid4kh/deskit** | Compose Desktop 组件库，仅含 FileChooser 对话框，**非树组件** |
| **PranavPurwar/Macaw / Raival-e/Prism / SysAdminDoc/FileExplorer** | 均为**完整文件管理器 App**，非可复用库；内置自实现的树 |
| **JetBrains SpaceCode / IntelliJ tree** | 存在于 Swing/JCEF，未移植到 Compose |
| **react-arborist / headless-tree** | React 生态，与 Compose 无关 |

---

## 详细评估

### 🥇 推荐选项 1：`vooft/compose-treeview`

- **GitHub**: https://github.com/vooft/compose-treeview
- **Maven**: `io.github.vooft:compose-treeview-core:0.6.1`
- **维护者**: vooft（个人开发者）
- **来源**: Bonsai 的 fork，重构并继续维护
- **活跃度证据**:
  - 0.0.1 → 0.6.1 共 14 个版本（2024-12 至 2026-05）
  - 2026 年内已发 5 版（0.4.0 → 0.6.1）
  - Dependabot 自动跟进 Kotlin 2.3.20 / Compose 1.10.3 / AGP 9.1.0
- **平台覆盖最全**: Android + iOS + Desktop + Wasm（含在线 demo）
- **能力**: DSL、自定义节点/图标、展开/折叠、选择、点击
- **风险**: 个人维护，star 数仅 14（社区采用度低）

### 🥈 推荐选项 2：`AdrianKuta/Tree-Data-Structure` (`tree-structure-compose`)

- **GitHub**: https://github.com/AdrianKuta/Tree-Data-Structure
- **Maven**: `com.github.adriankuta:tree-structure-compose:4.2.0`
- **维护者**: AdrianKuta（同时镜像在自托管 Gitea）
- **活跃度证据**:
  - v4.2.0 发布于 2026-06-08（**12 天前**）
  - `tree-structure-compose` 模块于 2026-06-07 全新创建
  - 同步新增 Android target（minSdk 21）+ 默认 `TreeNodeRow` + Android 示例
- **特点**: 数据结构优先，Compose 仅作视图层。栈安全（深度树无 SOF）、O(n) 周期检测、完整遍历 API
- **Compose UI 极简**: `LazyTree(root)` 一行调用，foundation-only（无 Material 依赖）
- **风险**: Compose 模块太新（< 2 周），API 可能未稳定；star 数仅 21

### ⚠️ 不推荐：`adrielcafe/bonsai`

- **曾经的事实标准**（420 stars，搜索引擎排名最高）
- **但已停维护 4 年**：最后一次 commit 2022-04-15，最后 release 2022-04-21
- 作者 Adriel Café 现全力维护 Voyager（最近 2026-03 更新）
- **不要新项目采用**；现有项目应评估迁移到 vooft fork

### ❌ 不推荐：`mr0xf00/lazytreelist`

- 仅 0.1.0 一个版本（2022-10），之后无任何更新
- 仅 Android，不支持 Desktop/iOS
- 实验性 DSL，无文件系统集成

---

## 选型建议（针对本项目 oc-remote）

考虑到本项目是 **Jetpack Compose + Ktor + 单 Android 平台**的 OpenCode 客户端：

| 场景 | 推荐 |
|------|------|
| 需要文件浏览器（OpenCode 会话的文件树） | **vooft/compose-treeview** — 唯一同时活跃 + 有文件系统集成的选项 |
| 需要自定义树（如会话消息线程、工具调用层级） | **AdrianKuta tree-structure-compose** — 数据结构更完整，Compose 层更轻量 |
| 想完全控制 UI、避免依赖小众库 | **自行实现**（基于 `LazyColumn` + 缩进 + 展开/折叠状态，~150 行 Kotlin） |

**对 oc-remote 的特别提醒**：项目 AGENTS.md 强调"禁止引入额外 UI 依赖库（如 Accompanist），除非有充分理由"。引入上述任一库前，建议在 PR/ADR 中说明理由，或评估自行实现的成本（树组件本身不复杂，复杂度在于文件系统异步加载、状态恢复、性能）。

---

## 调研方法与搜索覆盖

已执行的搜索维度：
1. Google/Jetpack 官方（androidx compose foundation、JetBrains compose-multiplatform、Accompanist）
2. Compose Multiplatform 生态（klibs.io、JetBrains 库）
3. 大厂开源（Netflix/Uber/Twitter Compose UI 库）— 无相关结果
4. GitHub topic 搜索（tree-view + compose + stars/pushed 过滤）
5. IDE/编辑器类项目（deskit、ComposeDesktopTemplate、MultiplatformUI）
6. Maven Central 已发布包验证
7. 经典 View 系统的 TreeView（AmrDeveloper）作对照

**未发现的高可信度选项**：经过 4 轮并行搜索（共 ~30 条查询），未发现任何 Google/JetBrains/大厂背书的 Compose 树组件。这是一个明确的市场空白。
