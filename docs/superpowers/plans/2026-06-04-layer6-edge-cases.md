# Layer 6: Edge Case Polish — Structured Backlog

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish edge cases across SSE/connection, messages, sessions, errors, tools, permissions, and misc UI areas to reach production quality.
**Architecture:** Each group is a themed batch of related fixes. Items within a group share files and can be implemented together. This is a prioritized backlog — implement groups in order of business value.
**Tech Stack:** Kotlin, Jetpack Compose (Material3), StateFlow, Hilt, DataStore, Ktor
**Depends on:** Layers 1–5 must be complete
**Spec reference:** `docs/superpowers/specs/2026-06-04-gap-fix-design.md` → Layer 6

**Note:** This layer uses a lightweight structured backlog format. Each group lists items, complexity, key files, and dependencies. Full TDD steps are not expanded — use the project's standard TDD pattern (test → verify fail → implement → verify pass → commit) when implementing.

---

## Group 6.1: SSE / Connection Robustness

**Complexity:** M (4 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/service/SseConnectionManager.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/EventDispatcher.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/handler/MessageEventHandler.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/api/SseClient.kt`

**Depends on:** Layer 1 (SSE connection), Layer 2 (event handling)

### Items:

- [ ] **6.1.1 — Event sequence validation:** Ensure SSE events are processed in order. Add sequence number validation in `EventDispatcher.processEvent()`. Log out-of-order events. Key: check `SseEvent` ordering in `processEvent`.
  - File: `EventDispatcher.kt` — add sequence counter, log warnings on gaps
  - Test: `EventDispatcherTest.kt` — verify events processed in order

- [ ] **6.1.2 — Instance dispose cascade:** When `SseEvent.ServerInstanceDisposed` arrives, gracefully close all sessions and clean up state. Currently only `SessionDeleted` cascades cleanup.
  - File: `EventDispatcher.kt` — add `ServerInstanceDisposed` handler that clears all session data for the disposed directory
  - Test: `EventDispatcherTest.kt` — verify cleanup after instance disposed

- [ ] **6.1.3 — Connection health monitoring:** Add periodic heartbeat check. If no `ServerHeartbeat` received within 60s, mark connection as unhealthy and trigger reconnect.
  - File: `SseConnectionManager.kt` — add heartbeat timeout tracker, trigger reconnect on timeout
  - Test: Unit test for timeout logic

- [ ] **6.1.4 — Shell real-time output streaming:** Ensure PTY terminal output streams in real-time via WebSocket without buffering delays. Verify WebSocket frame processing is chunk-oriented, not message-oriented.
  - File: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalView.kt` or similar
  - Verify: visual check of terminal output latency

---

## Group 6.2: Message Polish

**Complexity:** L (6 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/PartContent.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/ToolCardScaffold.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt`

**Depends on:** Layer 4 (message rendering), Layer 5 (tool cards)

### Items:

- [ ] **6.2.1 — Shell command mode:** Detect when user input starts with `/` and treat it as a command (not a chat message). Show command suggestions.
  - File: `ChatInputBar.kt` — add `/` prefix detection, show command completion dropdown
  - File: `ChatViewModel.kt` — add `sendCommand(text)` method that routes `/` commands

- [ ] **6.2.2 — Custom commands support:** Allow `!command_name` shortcuts that expand to predefined prompts. Store in DataStore.
  - File: `domain/model/AppSettings.kt` — add `customCommands: Map<String, String>` field
  - File: `SettingsRepositoryImpl.kt` — persist custom commands
  - File: `ChatInputBar.kt` — expand `!` shortcuts before sending

- [ ] **6.2.3 — Tool group folding:** When multiple consecutive tool calls appear in a message, fold them into a collapsed group showing "N tool calls". Expand on tap.
  - File: `AssistantTurnBubble.kt` — detect consecutive `Part.Tool` runs, wrap in `ToolGroupCard`
  - New file: `ui/screens/chat/tools/cards/ToolGroupCard.kt`
  - Complexity: M — requires tracking consecutive tool parts

- [ ] **6.2.4 — Message inline edit:** Allow user to edit their own message (re-send with modifications). Shows edit icon on user messages.
  - File: `MessageCard.kt` — add edit `IconButton` on user messages
  - File: `ChatViewModel.kt` — add `editMessage(messageId, newText)` that calls API to resend
  - Note: depends on API support for message edit/revert

- [ ] **6.2.5 — Long output truncation:** Truncate tool output exceeding 10K chars with "Show more" button. Load full output on demand.
  - File: All tool card files (`BashToolCard`, `SearchToolCard`, etc.)
  - Strategy: Add `output.take(MAX_VISIBLE)` with expandable "Show all" button
  - Reuse: Extract into a shared `TruncatedOutput` composable

- [ ] **6.2.6 — Diff virtualization:** For very large diffs (>500 lines), use `LazyColumn` instead of `Column` to avoid OOM. Only render visible lines.
  - File: `DiffHelpers.kt` — replace `Column` with `LazyColumn` in `DiffView`
  - Key: change `Column { diffLines.forEach { ... } }` to `LazyColumn { items(diffLines) { ... } }`

---

## Group 6.3: Session Enhancements

**Complexity:** M (4 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/SessionListScreen.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/sessions/components/`
- `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- `app/src/main/kotlin/dev/minios/ocremote/domain/model/Session.kt`

**Depends on:** Layer 1 (session management)

### Items:

- [ ] **6.3.1 — Session export (Redact/Sanitize):** Export session as JSON with option to redact sensitive data or sanitize file paths.
  - File: `OpenCodeApi.kt` — add `exportSession(conn, sessionId, format)` API call
  - New file: `ui/screens/sessions/components/ExportDialog.kt` — redact/sanitize toggle
  - Share via `Intent.ACTION_SEND`

- [ ] **6.3.2 — Workspace grouping:** Group sessions by workspace/project in the session list.
  - File: `SessionListScreen.kt` — add grouping by `session.projectId` or `session.directory`
  - Add collapsible section headers per workspace

- [ ] **6.3.3 — Session diff viewing:** Show file changes for a session in a dedicated diff view, not just in the PatchCard.
  - File: `SessionListScreen.kt` — add "View changes" action per session
  - New file: `ui/screens/sessions/components/SessionDiffView.kt`
  - Use existing `DiffView` component

- [ ] **6.3.4 — Session tree visualization:** Display parent-child session relationships as a tree/graph.
  - File: `SessionListScreen.kt` — group child sessions under parent with indent
  - Use `session.parentId` to build tree hierarchy
  - Show expand/collapse for sub-agent sessions

---

## Group 6.4: Error Handling

**Complexity:** S (3 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`
- `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeScreen.kt`

**Depends on:** Layer 1 (connection)

### Items:

- [ ] **6.4.1 — Offline request queue:** When network is unavailable, queue sent messages locally and retry when connection is restored.
  - File: `ChatViewModel.kt` — add pending queue in `sendMessage()`, flush on reconnect
  - File: `SettingsRepositoryImpl.kt` — persist queued messages
  - Key: use Room or DataStore for queue persistence

- [ ] **6.4.2 — Server version check:** Compare client version with server version on connect. Show warning if incompatible.
  - File: `HomeScreen.kt` — add version check after server health check
  - File: `OpenCodeApi.kt` — parse version from `ServerHealth` response
  - Show `AlertDialog` for major version mismatch

- [ ] **6.4.3 — Error log export:** Collect recent errors and allow exporting as text file for bug reports.
  - File: `ChatViewModel.kt` — collect `_error` events with timestamps
  - New file: `ui/screens/settings/components/ErrorLogSection.kt`
  - Export via `Intent.ACTION_SEND` with text/plain

---

## Group 6.5: Additional Tool Cards

**Complexity:** M (5 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/cards/` (new files)
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/tools/DefaultToolCardResolver.kt`

**Depends on:** Layer 4 Step 4.9 (ToolCardResolver)

### Items:

- [ ] **6.5.1 — RepoCloneToolCard:** Show repo URL + destination + progress.
  - New file: `cards/RepoCloneToolCard.kt`
  - Register in `DefaultToolCardResolver` under `"clone"`, `"repo_clone"`
  - Display: URL, destination path, clone progress if available

- [ ] **6.5.2 — LSPToolCard:** Show LSP operation (hover, definition, references) + result.
  - New file: `cards/LSPToolCard.kt`
  - Register under `"lsp"`, `"lsp_hover"`, `"lsp_definition"`, `"lsp_references"`
  - Display: operation type, symbol, result text

- [ ] **6.5.3 — SkillToolCard:** Show skill name + description + parameters.
  - New file: `cards/SkillToolCard.kt`
  - Register under `"skill"`, `"skill_create"`, `"skill_execute"`
  - Display: skill name, description, execution status

- [ ] **6.5.4 — PlanToolCard:** Show plan steps with status indicators (pending/done/active).
  - New file: `cards/PlanToolCard.kt`
  - Register under `"plan"`, `"plan_create"`, `"plan_execute"`
  - Display: step list with checkmarks for completed steps

- [ ] **6.5.5 — Tool output fold interaction:** Add long-press on collapsed tool output to show full content in a dialog.
  - File: `ToolCardScaffold.kt` — add `onLongClick` modifier to expanded content
  - Show `AlertDialog` with full output text and copy button

---

## Group 6.6: Permission Enhancements

**Complexity:** S (2 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/` (permission UI)
- `app/src/main/kotlin/dev/minios/ocremote/data/repository/PermissionAutoApprover.kt`

**Depends on:** Layer 5 Steps 5.1–5.4

### Items:

- [ ] **6.6.1 — Directory-level permission UI:** Show which directory a permission applies to. Allow user to scope permission to current directory or global.
  - File: Permission card UI — add directory display and scope selector
  - File: `PermissionAutoApprover.kt` — support `directoryPattern` matching

- [ ] **6.6.2 — Permission info detail:** Show expanded info about what a permission request entails (tool name, input preview, directory scope).
  - File: Permission card UI — add expandable detail section
  - Show first 200 chars of tool input for context

---

## Group 6.7: Misc UI Polish

**Complexity:** M (5 items)
**Key files:**
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/input/ChatInputBar.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/components/PartContent.kt`
- `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt`

**Depends on:** Layer 4 (UI components)

### Items:

- [ ] **6.7.1 — Multi-line input:** Expand text input to multi-line (max 5 lines). Show line count indicator.
  - File: `ChatInputBar.kt` — change `maxLines` from 1 to 5, add `verticalScroll`
  - Add visual indicator for multi-line mode

- [ ] **6.7.2 — Input history:** Save sent messages and allow navigation with up/down arrows.
  - File: `ChatInputBar.kt` — add history buffer, handle up/down key events
  - File: `ChatViewModel.kt` — maintain `inputHistory: List<String>` in memory
  - Max 50 entries, in-memory only (no persistence needed)

- [ ] **6.7.3 — Agent Part UI:** Render `Part.Agent` with a distinctive visual treatment (avatar icon + agent name badge).
  - File: `PartContent.kt` — add `is Part.Agent -> { ... }` case
  - Show agent name with a colored badge/icon
  - Currently `Part.Agent` may be rendered minimally or skipped — add explicit rendering

- [ ] **6.7.4 — FileEdited UI reaction:** When `Part.Patch` or edit tools show file changes, highlight the changed filename in the file tree / session list.
  - File: `SessionListScreen.kt` — show changed file indicator per session
  - Use `Session.Summary.files` count

- [ ] **6.7.5 — McpToolsChanged UI reaction:** When MCP tools change (new tools registered/removed), show a brief notification in the chat.
  - File: `EventDispatcher.kt` — handle `McpToolsChanged` event if it exists
  - File: `ChatScreen.kt` — show `Snackbar` or inline notification
  - Low priority — depends on server sending this event

---

## Implementation Order

Recommended implementation order by business value:

1. **Group 6.1** — SSE/Connection: Foundation for reliability
2. **Group 6.2** — Message Polish: Most visible user improvements
3. **Group 6.7** — Misc UI: Quick wins
4. **Group 6.5** — Tool Cards: Extend tool coverage
5. **Group 6.4** — Error Handling: Edge case resilience
6. **Group 6.3** — Session Enhancements: Nice-to-have
7. **Group 6.6** — Permission: Minor polish

---

## Verification Strategy

For each group:

```bash
# Compile check (120s timeout)
.\gradlew :app:compileDevDebugKotlin

# Unit tests (180s timeout)
.\gradlew :app:testDevDebugUnitTest --rerun

# Commit after each group
git add -A
git commit -m "feat: Layer 6 Group N.X - [description]"
```

- [ ] Group 6.1 complete — SSE/connection robustness verified
- [ ] Group 6.2 complete — Message polish verified
- [ ] Group 6.3 complete — Session enhancements verified
- [ ] Group 6.4 complete — Error handling verified
- [ ] Group 6.5 complete — Additional tool cards verified
- [ ] Group 6.6 complete — Permission enhancements verified
- [ ] Group 6.7 complete — Misc UI polish verified
- [ ] Full test suite passes: `.\gradlew :app:testDevDebugUnitTest --rerun`
