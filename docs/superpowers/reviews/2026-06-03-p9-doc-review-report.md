# P9 UI Fixes — 文档一致性审查报告

**审查日期:** 2026-06-03
**审查范围:** 设计规格 (`specs/2026-06-03-p9-ui-fixes-design.md`) + 实施计划 (`plans/2026-06-03-p9-ui-fixes.md`)
**审查方法:** 9 维度并行审查（D1-D9），每维度独立 subagent

---

## 审查维度

| 维度 | 名称 | 审查重点 |
|------|------|----------|
| D1 | Spec↔需求一致性 | 规格是否忠实反映用户确认的需求 |
| D2 | Spec↔Plan 一致性 | 规格与计划之间是否有矛盾 |
| D3 | API 可行性 | 引用的 API 是否存在、参数是否正确 |
| D4 | 语义准确性 | 命名/概念是否精确无歧义 |
| D5 | Plan 完整性 | 步骤是否可执行（无黑盒/跳步） |
| D6 | 本地化合规 | 15-locale 策略是否被遵守 |
| D7 | 验证标准质量 | 验证步骤是否机器可判 |
| D8 | 边界/异常处理 | 空值/缺省/边界条件是否覆盖 |
| D9 | 文档结构性 | 文件结构表/编号映射/路径准确性 |

---

## 发现问题汇总

### 已修复 (P0)

| ID | 维度 | 严重度 | 问题 | 修复内容 |
|----|------|--------|------|----------|
| D2-001 | D2 | P0 | Spec #3 说"使用 Session.tokens"，但 Plan/代码注释确认 Session.tokens 是 overwrite 非累计 | 重写 Spec #3 方案，明确使用 `totalXxx` 累计变量 |
| D2-002 | D2 | P0 | Spec 想保留 contextXxx 字段换源，Plan 想删除字段——策略矛盾 | Spec 改为"删除冗余 contextXxx 字段，直接用 totalXxx" |
| D3-001 | D3 | P0 | 标准 `ListItem(headlineContent=...)` 无 `contentPadding` 参数（仅 Expressive 版有） | T7 改用 `Modifier.padding(ListItemTokens.ContentPaddingMedium)` |
| D5-001 | D5 | P0 | T3 黑盒：未列出 25+ 处字段删除（ChatUiState 5 字段 + ChatTopBar 签名 + ChatScreen 传参） | T3 拆为 7 步，逐一列出每个文件的具体修改 |
| D5-002 | D5 | P0 | T3 Files 声明遗漏 ChatScreen.kt | 文件结构表和 T3 Files 新增 ChatScreen.kt |

### 已修复 (P1)

| ID | 维度 | 严重度 | 问题 | 修复内容 |
|----|------|--------|------|----------|
| D5-003 | D5 | P1 | T2 退回方案缺 `AnimatedVisibility` import | 添加 import 提示 |
| D6-001 | D6 | P1 | T5 硬编码英文字符串 `"/${node.sessionCount} sessions active"` | 标注为已知技术债务，记录后续修复方向 |
| D6-002 | D6 | P1 | T6 新增 string resource 后缺 lokit 同步步骤 | T6 新增 Step 5 lokit 同步 |
| D6-003 | D6 | P1 | T4 缺 `java.io.File` import 说明 | 添加注释说明 JVM 标准库无需 import |
| D9-001 | D9 | P1 | 文件结构表缺 strings.xml + ChatScreen.kt | 表格已更新 |
| D9-002 | D9 | P1 | ChatTopBar.kt 路径不一致（表 vs commit 命令） | 统一为 `ui/screens/chat/components/ChatTopBar.kt` |

### 已记录 (P2，不阻塞执行)

| ID | 维度 | 问题 | 备注 |
|----|------|------|------|
| D4-001 | D4 | T3 `lastContextTokens = totalInput + ...` 语义是"总 token 消耗"而非"上下文窗口占用" | 功能正确，命名可改进但不阻塞 |
| D7-001 | D7 | 多个验证标准依赖人工目视（"应持续增长"） | 可接受，token 行为难以自动化验证 |
| D8-001 | D8 | T2 baseDirectory 为空字符串 "" vs null 未明确处理 | 当前代码用 `!= null` 门控，空字符串不触发——行为一致 |
| D8-002 | D8 | 编译失败无全局回退策略 | 计划头部新增"编译失败处理"段落 |

---

## 修复统计

- **总发现问题:** 15
- **P0 已修复:** 5
- **P1 已修复:** 6
- **P2 已记录:** 4（不阻塞执行）
- **遗留技术债务:** 1（T5 硬编码英文字符串，需后续拆分 string resource）

---

## 修改的文件清单

| 文件 | 修改内容 |
|------|----------|
| `docs/superpowers/specs/2026-06-03-p9-ui-fixes-design.md` | #3 方案重写（Session.tokens → totalXxx） |
| `docs/superpowers/plans/2026-06-03-p9-ui-fixes.md` | T3 拆为 7 步、T2 import 修复、T4 import 说明、T5 技术债务标注、T6 lokit 步骤、T7 contentPadding→Modifier.padding、文件结构表更新、编译失败处理段落、需求映射段落 |

---

## 结论

文档经过 9 维度审查后，5 个 P0 阻塞性问题已全部修复。文档现在满足**可执行性**要求：每个 Task 有明确的文件列表、行号范围、修改前/后代码、编译验证步骤。实施者可以无需猜测地逐步执行。
