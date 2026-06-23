# Markdown 排版间距调研报告

> 调研日期：2026-06-18  
> 目标：为 oc-remote（Android AI 聊天客户端）的对话流 markdown 渲染确定「块级间距 / 行距 / 字号」的最佳取值，给出有数据支撑的推荐方案。  
> 痛点：用户反馈"标题↔正文、正文↔列表等元素之间间距不舒服"。

---

## 一、核心结论（TL;DR）

1. **根因定位**：oc-remote 当前块级元素间距（mikepenz `markdownPadding.block`）默认只有 **2dp**，是所有主流方案里最小的——这是"元素之间挤"的直接元凶。行距其实没问题（正文 1.57，已达 WCAG 标准）。
2. **行距现状 OK**：正文 14sp / lineHeight 22sp = **1.57**，已超过 WCAG 1.5 下限，接近 GitHub 1.5、Tailwind prose 1.75 的中间值。**不需要动行距**。
3. **唯一需要调的核心参数**：`markdownPadding(block = ...)`，从 2dp 提到 **8dp**（±2dp 真机微调）。次要可调 list/listItemBottom。
4. 推荐值见 [第四节](#四针对-oc-remote-的推荐方案)。

---

## 二、各来源排版参数对比

### 2.1 正文行距（line-height）

| 来源 | 正文 line-height | 备注 |
|------|-----------------|------|
| **oc-remote 现状** | **1.57**（14sp/22sp） | MarkdownContent 覆盖了 Type.kt 的 bodyMedium(1.43) |
| GitHub (事实标准) | **1.5**（16px/24px） | `.markdown-body{line-height:1.5}` |
| Tailwind prose base | 1.75（16px/28px） | 面向长文博客，偏宽松 |
| Tailwind prose lg | 1.78（18px/32px） | |
| WCAG 1.4.12 (W3C) | **≥ 1.5**（强制下限） | 无障碍标准 |
| USWDS（美政府设计系统） | 1.5 区间 | |
| Pimp my Type | 1.5–1.6 | 60-80 字符行长 |
| 行高安全区（综合） | 正文 **1.4–1.7**，标题 1.1–1.4 | |
| ChatGPT 用户社区 | 嫌默认太高，要求 compact 1.0–1.3 | AI 聊天场景偏紧凑诉求 |
| mikepenz Type.kt bodyMedium | 1.43（14/20，未覆盖时） | 偏紧，但 MarkdownContent 已覆盖为 1.57 |

**结论**：oc-remote 正文 1.57 已处于合理区间，无需调整。标题行距（titleLarge 等）当前多在 1.27–1.5，也合理。

### 2.2 块级元素间距（block spacing）⭐ 核心

| 来源 | 段落↔段落 | 标题前 | 标题后 | 列表周围 | 引用 |
|------|----------|--------|--------|----------|------|
| **oc-remote 现状 (mikepenz block)** | **2dp**（所有块统一） | 2dp | 2dp | list 8dp | blockQuote h16/v0 |
| **GitHub** | p 下 10px (≈0.625em) | h1-6 前 **24px** (1.5em) | h1-6 后 **16px** (1em) | ul margin **0**，padding-left 2em | margin 0，padding 0 1em，border-left .25em |
| Tailwind prose base | p 上下 20px (1.25em) | — | — | li p 20px | 上下 32px (2em) |
| Typora | p 上下 1rem (16px) | | | | |
| WCAG 1.4.12 | 段后 **≥ 2×字号** (32px@16px) | — | — | — | — |
| USWDS | 段落 ≥ 1em，列表项 ≥ 0.5em | | | | |

**关键观察**：
- GitHub 标题前 24px / 后 16px（@16px 正文）→ 换算到 14sp 正文约 **标题前 21sp / 后 14sp**。
- GitHub 段落间 10px → 14sp 正文约 **8.75sp**。
- **oc-remote 的 2dp 比任何主流方案都小 4-10 倍**。GitHub 段落间都是 10px，标题前 24px。
- mikepenz 的 `block` 是**统一值**（所有块之间相同），不像 GitHub 那样标题/段落分别设。所以选一个折中值即可。

### 2.3 标题层级（字号）

| 来源 | h1 | h2 | h3 | h4 |
|------|-----|-----|-----|-----|
| GitHub (@16px base) | 2em 32px | 1.5em 24px | 1.25em 20px | 1em 16px |
| oc-remote 现状 (Type.kt 映射) | titleLarge 22sp | titleMedium **16sp** | titleSmall 14sp | bodyLarge 16sp |
| 字号比（GitHub） | 2.0× | 1.5× | 1.25× | 1.0× |
| 字号比（oc-remote） | 1.57× | **1.14×** | 1.0× | 1.14× |

**观察**：oc-remote 的 h2（16sp）仅比正文（14sp）大 14%，**层级感弱**（GitHub h2 是 1.5×）。h1 的 22sp 也偏小（GitHub 2×=28sp）。这是"标题和正文区分不明显"的潜在原因，但属次要问题。

### 2.4 列表 / 引用 / 代码块

| 元素 | GitHub | oc-remote (mikepenz 默认) | 评价 |
|------|--------|--------------------------|------|
| 列表项间距 | li+li margin-top .25em (4px) | listItemBottom 4dp | ✅ 一致 |
| 列表缩进 | padding-left 2em (32px) | listIndent 8dp | ⚠️ 偏小，移动端可接受 |
| 列表周围 | ul margin 0（靠 p 补） | list 8dp | ✅ 合理 |
| 引用块 | border-left .25em (4px), padding 0 1em (16px) | blockQuote h16/v0, blockQuoteThickness 2dp | ⚠️ 边框偏细 |
| 代码块 | padding 16px, font 85%, line-height 1.45, radius 6px | codeBlock PaddingValues(8dp), radius 8dp | ✅ 接近 |

---

## 三、关键发现

1. **`block=2dp` 是异常值**：所有主流方案块间距至少 8-10px 起（GitHub 段落 10px，标题前 24px）。mikepenz 默认 2dp 明显偏小，这是用户痛点的直接原因。**生产代码调 `Markdown()` 时未传 padding 参数，吃了这个默认值**。

2. **行距不是问题**：正文 1.57 已达标。之前怀疑"行距偏紧"是误判（看的是 Type.kt 的 bodyMedium 1.43，但 MarkdownContent 实际覆盖为 1.57）。

3. **AI 聊天 vs 长文的权衡**：
   - 长文阅读器（Tailwind prose 1.75、Typora）偏宽松，牺牲密度换舒适。
   - AI 聊天（ChatGPT 用户要 compact、GitHub 技术文档 1.5）偏紧凑，信息密度优先。
   - oc-remote 是 AI 聊天 → 取 GitHub 风格（1.5 区间）更合适，**不要学 Tailwind 的 1.75**。

4. **标题层级弱**（次要问题）：h2 仅 1.14×，GitHub 是 1.5×。若要增强结构感可调，但优先级低于 block 间距。

5. **换行策略**（独立问题）：当前助手消息走标准 markdown（单换行=软换行）。标准渲染下，LLM 混用单/双换行会造成节奏不均。但这是产品决策（标准/智能/强制），与间距数值无关。

---

## 四、针对 oc-remote 的推荐方案

基于"AI 聊天 + 移动端 + 14sp 正文"定位，取 GitHub（事实标准、技术文档风格）为主基准，WCAG 为下限。

### 4.1 必改（解决核心痛点）

```kotlin
// MarkdownContent.kt 调用 Markdown() 时新增 padding 参数
padding = markdownPadding(
    block = 8.dp,            // ⭐ 核心：2dp → 8dp（GitHub 段落间 10px 的等效值）
)
```

| 参数 | 现状 | 推荐 | 依据 | 允许微调范围 |
|------|------|------|------|-------------|
| **block** | 2dp | **8dp** | GitHub 段落间 10px；WCAG 段后≥2×字号（14sp→28sp 太大，取折中） | 6–10dp |

> 真机验证建议：在 playground 把 block slider 调到 6/8/10/12，对比标题↔正文舒适度。预计 8dp 是甜点。

### 4.2 可选优化（次要）

| 参数 | 现状 | 推荐 | 依据 |
|------|------|------|------|
| list | 8dp | 8dp（保持） | 合理 |
| listItemBottom | 4dp | 4dp（保持） | 与 GitHub li+li 4px 一致 |
| listIndent | 8dp | 12dp（可选） | GitHub 2em≈移动端稍大更清晰 |
| blockQuoteThickness | 2dp | 3dp（可选） | GitHub .25em，增强引用视觉 |

### 4.3 标题层级增强（可选，若仍觉结构弱）

| 标题 | 现状 | 推荐 | 依据 |
|------|------|------|------|
| h1 (titleLarge) | 22sp | 22sp（保持） | — |
| h2 (titleMedium) | 16sp | **18sp**（可选） | 向 GitHub 1.5× 靠拢（14×1.5=21，取 18 折中避免过大） |
| h3 (titleSmall) | 14sp | 15sp（可选） | 略大于正文 |

> 标题改动涉及 Type.kt 映射，影响面大，建议作为第二阶段，先验证 block 间距效果。

### 4.4 不建议改

- **行距**：1.57 已合理，不动。
- **正文字号**：14sp 适合移动端 AI 聊天（GitHub 桌面 16px，移动端通常 14-15sp）。
- **强制段落换行**：不要用于助手消息（会拆碎列表/代码），保持标准或智能。

---

## 五、数据来源

| 来源 | 类型 | 链接/说明 |
|------|------|----------|
| GitHub Primer / sindresorhus github-markdown-css | 事实标准 CSS | cdn.jsdelivr.net/gh/sindresorhus/github-markdown-css |
| Tailwind Typography | 社区主流默认值 | raw.githubusercontent.com/tailwindlabs/tailwindcss-typography |
| WCAG 1.4.12 Text Spacing | W3C 无障碍标准 | w3.org/WAI/WCAG21/Understanding/text-spacing |
| USWDS Typography | 美政府设计系统 | designsystem.digital.gov/components/typography |
| Typora Line Spacing | 编辑器默认值 | support.typora.io/Line-Spacing |
| Obsidian Forum | 社区讨论（同类痛点） | forum.obsidian.md（多个 heading spacing 帖） |
| ChatGPT Community | AI 聊天用户诉求 | community.openai.com（compact density 请求） |
| mikepenz 0.41.0 源码 | 当前库的 API 与默认值 | markdownPadding(block=2.dp,...) |
| Pimp my Type / UX Stack Exchange | 排版理论 | line-height 1.5-1.6 |

---

## 六、下一步

1. 在 playground 真机验证 `block` slider（6/8/10/12dp），敲定最终值。
2. （可选）验证 listIndent、blockQuoteThickness 微调。
3. 定值后落地到 `MarkdownContent.kt`（一行参数改动）。
4. 标题层级增强作为独立的第二阶段任务（若需要）。
