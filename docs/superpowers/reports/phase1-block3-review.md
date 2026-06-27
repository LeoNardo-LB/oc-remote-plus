# Phase 1 Block 3 Review — EventDispatcher Registry + MessageEventHandler Split

**Reviewer:** task-reviewer
**Date:** 2026-06-27
**Commits reviewed:** `d6adab78` (registry), `3883a1ba` (handler split)
**Diff scope:** 12 files, +211 / −19

---

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| **Spec Compliance** | ✅ PASS |
| **Code Quality** | Approved |

---

## 1. Spec Compliance — ✅ PASS

All 6 checklist items verified against source:

| # | Requirement | Status | Evidence |
|---|-------------|--------|----------|
| 1 | `processEvent` uses registry lookup (no `when(event)` dispatch) | ✅ | `EventDispatcher.kt:154-163` — `registry[event::class]?.handle(...)` replaces the 6-line broadcast |
| 2 | `extractSessionId` when-block retained (out of scope) | ✅ | `EventDispatcher.kt:218+` — pure function, untouched |
| 3 | 3 special cases retained | ✅ | SessionDeleted cascade `:167-174`; CommandExecuted→markSessionIdle `:180-182`; MessageUpdated+User→recordUserMessage `:185-187` |
| 4 | Registry covers all 39 SSE event types (zero omissions) | ✅ | Cross-checked `SseEvent.kt`: 36 `data class` + 3 `data object` (ServerConnected, ServerHeartbeat, LspUpdated) = 39. Registry binds exactly 39. Zero duplicates (compile-time `map[e] = handler` overwrites would silently hide dupes, but manual review confirms none). |
| 5 | MessageEventHandler split into 3 focused handlers | ✅ | `MessagePartHandler.kt` (27 lines, 3 events), `MessageUpdatedHandler.kt` (25 lines, 1 event), `MessageRemovedHandler.kt` (24 lines, 1 event) |
| 6 | New event type → only `bind()` call changes | ✅ | `processEvent` is handler-agnostic; adding an event domain = one `bind()` line in `buildRegistry()` |

### Registry coverage matrix (39/39)

| Handler | Events bound | Count |
|---------|-------------|-------|
| sessionHandler | ServerConnected, ServerHeartbeat, ServerInstanceDisposed, SessionCreated, SessionUpdated, SessionDeleted, SessionStatus, SessionIdle, SessionError, SessionDiff, SessionCompacted, VcsBranchUpdated, ProjectUpdated | 13 |
| messageUpdatedHandler | MessageUpdated | 1 |
| messageRemovedHandler | MessageRemoved | 1 |
| messagePartHandler | MessagePartUpdated, MessagePartDelta, MessagePartRemoved | 3 |
| permissionHandler | PermissionAsked, PermissionReplied | 2 |
| questionHandler | QuestionAsked, QuestionReplied, QuestionRejected | 3 |
| miscHandler | TodoUpdated, CommandExecuted, PtyCreated, PtyUpdated, PtyDeleted, WorkspaceReady, WorkspaceFailed, FileEdited, McpToolsChanged, FileWatcherUpdated, InstallationUpdated, InstallationUpdateAvailable, WorktreeReady, WorktreeFailed, LspUpdated | 15 |
| sessionNextHandler | SessionNext | 1 |
| **Total** | | **39** |

---

## 2. Code Quality — Approved

All 5 checklist items verified:

| # | Check | Status | Evidence |
|---|-------|--------|----------|
| 1 | Registry lookup is O(1) | ✅ | `Map<KClass<out SseEvent>, SseEventHandler>` — HashMap get by KClass key |
| 2 | Unregistered-event fallback | ✅ | `EventDispatcher.kt:161-163` — `BuildConfig.DEBUG` guarded `Log.w` warning. Defensive; currently unreachable since all 39 bind. |
| 3 | Shared state access correct | ✅ | `_messages`, `_parts`, `assistantMessageIds` remain in `MessageEventHandler`. 5 `handleMessage*` methods changed `private → internal` (same package). 3 sub-handlers inject `store: MessageEventHandler` and call `store.handleMessageX(event)`. |
| 4 | Internal method calls correct | ✅ | Each sub-handler's `when` block matches its registered events: `MessagePartHandler` → Updated/Delta/Removed; `MessageUpdatedHandler` → Updated; `MessageRemovedHandler` → Removed. No cross-routing. |
| 5 | No import residue / orphan code | ✅ | `MessageEventHandler` no longer references `SseEventHandler` (verified: zero occurrences in file). New `import kotlin.reflect.KClass` added to EventDispatcher. New handler files have minimal clean imports. |

---

## 3. Testing Verification

| Check | Status | Notes |
|-------|--------|-------|
| `compileDevDebugKotlin` | ✅ | Report confirms BUILD SUCCESSFUL after both Part 1 (20s) and Part 2 (26s) |
| `testDevDebugUnitTest --rerun` | ✅ | BUILD SUCCESSFUL after both parts (43s each). SSE suites green: `EventDispatcherIntegrationTest` (99 processEvent calls), `EventDispatcherTest`, `MessageEventHandlerTest` (44 handle() calls), `MessageEventHandlerMergeTest` (14 calls), `SessionNextEventTest`, `SseEventParserTest`, 3× `ChatViewModel*Test` |
| Assertion logic unchanged | ✅ | All 7 test-file diffs are constructor setup additions only (3 new handler params). Zero assertion lines touched. |

---

## 4. Risk Assessment

### 4a. Registry approach (Plan B manual Map vs Hilt multi-binding) — **Reasonable**

The rationale in the report is sound:
- **One handler → many events**: MiscEventHandler binds 15 events, SessionEventHandler 13. Hilt `@IntoMap @ClassKey` requires one `@Provides` per (eventClass → handler) pair = 39 boilerplate providers. Manual `buildMap` lists them inline in one readable location.
- **Constructor already needs typed refs**: EventDispatcher delegates read-only state to concrete handlers (`messageHandler.messages`, `sessionHandler.sessions`, …) and cross-handler commands (`markSessionIdle`, `clearForSession`). These require concrete typed injection regardless. Adding multi-binding on top would duplicate the wiring without removing the constructor params.
- **Lowest risk**: Handler internals untouched; only dispatch mechanism changed.

**Trade-off acknowledged**: The registry is a runtime construct (not compile-checked for exhaustiveness). A new `SseEvent` subclass added without a `bind()` call would hit the DEBUG-warning fallback silently. Mitigation: the sealed-class `when` in `extractSessionId` provides a compile-time exhaustiveness checkpoint for the event taxonomy, and the integration test exercises all 39 paths.

### 4b. `MessageEventHandler.handle()` retained as public aggregate entry — **Acceptable (transitional)**

The method is no longer an `override` (class no longer implements `SseEventHandler`) and production dispatch never calls it — the registry routes to the 3 sub-handlers. It exists solely so `MessageEventHandlerTest` (44 calls) and `MessageEventHandlerMergeTest` (14 calls) keep working without rewriting 58 invocations.

- **Pro**: Zero test churn, clean separation of "state store" from "dispatch unit".
- **Con**: Dead code in the production path; a reader could mistake `handle()` for the real entry point.
- **Verdict**: KDoc on the method documents the trade-off explicitly. Acceptable as a transitional measure. Recommend a follow-up ticket to migrate those tests to drive the sub-handlers and then delete `handle()`.

### 4c. Special-case ordering (before/after registry dispatch) — **Correct**

Special cases execute **after** the registry dispatch (lines 166-187), matching the original broadcast model where `*.handle(event)` ran first, then cascade/side-effects. Verified:

- **SessionDeleted**: `sessionHandler` receives it via registry → removes session from `_sessions`. Then cascade calls `clearForSession` on the other 5 handlers. Order preserved. ✓
- **CommandExecuted**: `miscHandler` receives it via registry → records the executed command. Then `messageHandler.markSessionIdle(sessionId)`. Order preserved. ✓
- **MessageUpdated(User)**: `messageUpdatedHandler` receives it → updates `_messages` + seeds `_parts`. Then `sessionHandler.recordUserMessage(...)`. Order preserved. ✓

No regression risk: in the old broadcast model, handlers without a matching `when` case were no-ops anyway; the registry simply makes that routing explicit.

---

## Summary

Block 3 is a clean, well-scoped refactor. The registry pattern achieves the Open/Closed Principle goal — `processEvent` is now closed for modification; new event domains only touch `buildRegistry()`. The MessageEventHandler split correctly preserves the tightly-coupled shared state while extracting per-sub-event dispatch. All 39 events are bound, all tests pass, and the two documented trade-offs (manual registry, retained `handle()`) are justified and non-blocking.

**Recommendation:** Proceed to next block. Optionally file a follow-up to retire `MessageEventHandler.handle()` after migrating its 58 test callers.
