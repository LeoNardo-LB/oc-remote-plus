# 消息操作栏与统计栏统一设计

## 背景

当前 oc-remote 的用户消息撤回使用 SwipeToDismissBox（左滑/右滑），不适合聊天场景。Assistant 回复卡片头部有一个多余的复制按钮。需要参考 opencode webui 的交互模式进行统一。

参考项目：[OpenCodeUI](https://github.com/lehhair/OpenCodeUI)（React + TypeScript），其用户消息操作栏采用 hover/触屏始终显示的方式。

## 需求

1. **删除 AssistantMessageCard 头部多余复制按钮** — 标题行右端的 ContentCopy 图标删除，只保留统计栏底部的复制按钮
2. **去除用户消息滑动撤回** — 删除 SwipeToDismissBox 包裹
3. **用户消息新增统计栏** — 左侧显示时间（HH:mm）和耗时（duration），右侧始终显示操作按钮（Undo + Copy），复制按钮在最右侧
4. **风格统一** — 用户消息统计栏与 Assistant 统计栏使用相同的视觉风格

## 设计

### 用户消息统计栏

```
┌──────────────────────────────────────────┐
│  用户消息气泡内容                          │
│                                          │
│  HH:mm  12.3s                [Undo] [Copy]│
└──────────────────────────────────────────┘
```

- 左侧：`HH:mm` 格式时间 + 耗时（duration，格式如 `12.3s`），灰色小字（10sp, alpha=0.35f）
- 中间：弹性空白（Spacer with weight）
- 右侧：Undo 图标 + Copy 图标（Copy 在最右侧）
- 操作按钮始终可见（纯触屏设备）
- 点击 Undo → 弹出确认对话框（AlertDialog）→ 确认后执行撤回
- 点击 Copy → 通过 onCopyText 回调复制消息文本

### Assistant 回复统计栏（不变）

```
┌──────────────────────────────────────────┐
│  [Provider] "Response" HH:mm             │  ← 头部（已删除 Copy）
│                                          │
│  Assistant 回复内容                       │
│                                          │
│  [Provider] model  duration ↑in ↓out $   │  ← 统计栏（保留 Copy）
│  cost                    [Copy]          │
└──────────────────────────────────────────┘
```

- 头部：已删除右侧的 ContentCopy 图标
- 统计栏底部：保持现有的复制按钮不变

### 视觉规格

| 属性 | 用户消息统计栏 | Assistant 统计栏 |
|------|---------------|-----------------|
| 字号 | 10sp | 10sp |
| 颜色 | onSurface.copy(alpha=0.35f) | onSurface.copy(alpha=0.35f) |
| 图标大小 | 14dp | 14dp |
| 图标颜色 | onSurface.copy(alpha=0.35f) | onSurface.copy(alpha=0.35f) |
| 间距 | Arrangement.spacedBy(8.dp) | Arrangement.spacedBy(8.dp) |
| 对齐 | 左：时间 + duration，右：Undo + Copy（Copy 最右） | 左：provider/model/stats，右：Copy |

## 改动范围

### 文件清单

| 文件 | 改动 |
|------|------|
| `ChatMessageBubble.kt` | 删除 SwipeToDismissBox（L215-305），新增统计栏 Row |
| `AssistantMessageCard.kt` | 删除头部复制按钮（L125-134） |

### ChatMessageBubble.kt 改动细节

1. **删除**：`SwipeToDismissBox` 包裹及相关代码（confirmValueChange、dismissState、背景渲染等）
2. **新增**：气泡内容底部添加统计栏 Row

```kotlin
// 统计栏（在气泡内容 Column 的末尾）
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    // 左侧：时间
    Text(
        text = chatMessage.message.time.created.format(DateTimeFormatter.ofPattern("HH:mm")),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    )
    // 左侧：耗时（duration，与 Assistant 统计栏一致）
    val durationMs = chatMessage.message.time.completed - chatMessage.message.time.created
    if (durationMs > 0) {
        Text(
            text = formatDuration(durationMs), // 复用 AssistantMessageCard 的格式化函数
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
    // 弹性空白，将操作按钮推到右侧
    Spacer(modifier = Modifier.weight(1f))
    // 右侧：Undo 按钮（主会话中 onRevert != null 时显示）
    if (onRevert != null) {
        Icon(
            Icons.AutoMirrored.Outlined.Undo,
            contentDescription = "撤销",
            modifier = Modifier
                .size(14.dp)
                .clickable { showRevertDialog = true },
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
    // 右侧（最右）：Copy 按钮
    if (onCopyText != null) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = "复制",
            modifier = Modifier
                .size(14.dp)
                .clickable {
                    performHaptic(hapticView, hapticOn)
                    onCopyText()
                },
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
}
```

> **注意**：`chatMessage` 是 ChatMessageBubble 的现有参数；`formatDuration()` 是 AssistantMessageCard 中已有的工具函数（需提取为公共函数或直接引用）；`hapticView`/`hapticOn` 是 ChatMessageBubble 中已有的参数；`onRevert`/`onCopyText` 是现有的回调参数。

3. **保留**：`showRevertDialog` state 和 `AlertDialog`（用于 Undo 按钮的确认弹窗）
4. **注意**：消息气泡内容不再被 SwipeToDismissBox 包裹，直接放在 Column 中

### AssistantMessageCard.kt 改动细节

删除头部标题行的复制按钮（约 L125-134）：

```kotlin
// 删除这段：
if (onCopyText != null) {
    Icon(
        Icons.Default.ContentCopy,
        contentDescription = stringResource(R.string.chat_copy),
        modifier = Modifier
            .size(15.dp)
            .clickable {
                performHaptic(hapticView, hapticOn)
                onCopyText()
            },
        tint = textColor.copy(alpha = 0.3f)
    )
}
```

统计栏底部的复制按钮保持不变。

## 不涉及的改动

- 子会话（onRevert=null）的用户消息：不显示 Undo 按钮，只显示时间、duration 和 Copy
- Assistant 统计栏的其他元素（provider、model、duration、tokens、cost）：保持不变
- 撤回的实际逻辑（API 调用、store 更新）：保持不变，只改 UI 触发方式

## 验收标准

### 需求 1：删除头部多余复制按钮
- [ ] AssistantMessageCard 头部区域无 ContentCopy 图标
- [ ] 统计栏底部的 Copy 按钮仍然存在且功能正常

### 需求 2：去除用户消息滑动撤回
- [ ] 用户消息气泡不响应水平滑动手势
- [ ] 无滑动动画和背景提示

### 需求 3：用户消息统计栏
- [ ] 主会话用户消息底部显示统计栏：`HH:mm  duration  [Undo] [Copy]`
- [ ] 子会话用户消息底部显示统计栏：`HH:mm  duration  [Copy]`（无 Undo）
- [ ] Copy 在统计栏 Row 的最右侧
- [ ] 点击 Undo → 弹出确认 AlertDialog → 确认后撤回
- [ ] 点击 Copy → 复制消息文本（onCopyText 回调）
- [ ] duration 格式与 Assistant 统计栏一致（如 `12.3s`）
- [ ] 无 duration 数据时（消息未完成）不显示 duration

### 需求 4：风格统一
- [ ] 用户消息统计栏与 Assistant 统计栏的字号（10sp）、颜色（alpha=0.35f）、图标大小（14dp）完全一致

## 边界条件

| 场景 | 行为 |
|------|------|
| 消息发送中（未完成） | 不显示 duration，Undo 按钮不显示 |
| 消息无文本内容（纯图片/文件） | Copy 按钮跟随 onCopyText 回调（当前实现已处理空文本） |
| 子会话（onRevert=null） | 只显示时间 + duration + Copy，无 Undo |
| Snackbar | 由 ChatScreen 层的 onCopyText 回调处理（当前实现已包含 Snackbar），ChatMessageBubble 不自行管理 |
