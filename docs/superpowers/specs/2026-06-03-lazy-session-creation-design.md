# Spec: 会话延迟创建（Lazy Session Creation）

## 背景与动机

当前 oc-remote 在用户点击"新建会话"时，**立即调用 `POST /session` 创建会话**，然后导航到 ChatScreen。这导致：
- 用户未输入就退出时，产生空会话（会话列表中出现大量 "New session" 空条目）
- 与 OpenCode Web/TUI 行为不一致（它们在发送第一条消息时才创建会话）
- 创建会话的网络延迟阻塞导航

OpenCode 的策略是「延迟创建」：点击新建只打开空界面，发送第一条消息时才调用 `POST /session`。

## 目标

将 oc-remote 的会话创建逻辑改为与 OpenCode 一致的延迟创建策略。

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 目录选择时机 | 先选目录，再进空界面 | 保留现有 OpenProjectDialog 交互，用户已习惯 |
| 空界面展示 | 空聊天界面，无特殊展示 | 简洁，与现有 ChatScreen 一致 |
| ChatScreen 内新建 | 同步改造为延迟创建 | 三个入口行为一致，复用当前 directory |
| sessionId 为空时的语义 | 空字符串 = 新会话 | 最小改动，不需要新路由 |

## 改动范围

### 1. 导航层 — `ChatNav.kt`

- `sessionId` 参数语义扩展：空字符串表示"新会话，尚未创建"
- 新增 `directory` 参数：传递用户选择的项目目录（新建会话时使用）

### 2. `SessionListScreen` + `SessionListViewModel`

- FAB 点击 → 弹 OpenProjectDialog 选目录 → 导航到 `ChatScreen(sessionId="", directory=选中的目录)`
- `SessionListViewModel.createNewSession()` 方法**删除**（不再立即创建会话）

### 3. `ChatScreen` + `ChatViewModel`

- ChatViewModel 构造时从 savedStateHandle 获取 `directory` 参数
- `sessionId` 为空时，ChatScreen 展示空聊天界面（无消息、无特殊 UI）
- `sendMessage` 增加前置逻辑：
  1. 检查 `sessionId` 是否为空
  2. 如果为空 → 调用 `createSession(directory)` → 获取真实 sessionId
  3. 更新本地 `sessionId` 字段
  4. 用新 sessionId 调用 `sendPrompt`
  5. 如果非空 → 直接 `sendPrompt`（现有逻辑不变）

### 4. `ChatScreen` 菜单"新建会话" + `/new` 斜杠命令

- 改为直接导航到 `ChatScreen(sessionId="", directory=当前会话的 sessionDirectory)`
- `ChatViewModel.createNewSession(onResult)` 回调式方法**删除**

### 5. `NavGraph.kt`

- ChatScreen 路由注册增加 `directory` 参数
- 所有 `onNavigateToChat` / `onNavigateToSession` 调用点适配新签名

## 不改动的部分

- `OpenCodeApi.createSession` — API 层签名不变
- SSE 事件处理 — 不涉及
- 现有会话的恢复/同步逻辑 — 不涉及
- `ManageSessionUseCase.createSession` — UseCase 层不变
- `SessionListScreen` 的 OpenProjectDialog — UI 不变，只是回调后的行为从"创建会话"改为"导航到空界面"

## 关键流程

### 新建会话流程（改造后）

```
用户点击 FAB
  → 弹 OpenProjectDialog 选目录
  → 选择目录
  → 导航到 ChatScreen(sessionId="", directory="选中的目录")
  → 用户看到空聊天界面
  → 用户输入消息并点击发送
  → ChatViewModel.sendMessage()
    → 检测 sessionId 为空
    → createSession(directory) → POST /session → 获得 Session{id, ...}
    → 更新 sessionId = session.id
    → sendPrompt(sessionId, parts) → POST /session/{id}/prompt_async
  → 后续消息直接 sendPrompt（sessionId 已有值）
```

### 现有会话流程（不变）

```
用户从会话列表点击已有会话
  → 导航到 ChatScreen(sessionId="已有id", directory="")
  → ChatViewModel 加载消息
  → 用户发送消息 → sendPrompt（sessionId 非空，直接发送）
```

## 风险与边界情况

1. **创建会话失败**：`sendMessage` 中 `createSession` 失败 → 展示错误 toast，不发送消息，sessionId 保持为空，用户可重试
2. **并发发送**：sessionId 为空时用户快速连点发送 → 需要防重入（创建中加锁，后续消息排队等待创建完成）
3. **SSE 事件**：空 sessionId 时不订阅 SSE（ChatScreen 不调用 preLoadSessions 等需要 sessionId 的初始化逻辑）
4. **返回键**：用户进入空界面后按返回 → 直接回到 SessionListScreen，不留下空会话

## 验证标准

- [ ] 点击 FAB → 选目录 → 进入空 ChatScreen → 不产生 API 调用
- [ ] 在空 ChatScreen 发送第一条消息 → 触发 `POST /session` + `POST /session/{id}/prompt_async`
- [ ] 创建失败时展示错误，不产生空会话
- [ ] 菜单"新建会话" + `/new` 同样走延迟创建
- [ ] 已有会话的打开/恢复/发送流程不受影响
- [ ] 从空界面返回不留下空会话
