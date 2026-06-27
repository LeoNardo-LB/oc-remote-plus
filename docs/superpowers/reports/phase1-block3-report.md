# Phase 1 Block 3 Report — EventDispatcher Registry + MessageEventHandler Split

**Branch:** `refactor/phase1-data-foundation`
**Base HEAD:** `4a571ba5`
**Date:** 2026-06-27

## Status: DONE

Both Part 1 (Task 10) and Part 2 (Task 10b) are complete. All SSE + ViewModel
unit tests pass; behavior is identical to the pre-refactor broadcast model.

## Commits

| Part | Commit | Subject |
|------|--------|---------|
| 1 — Registry | `d6adab78` | EventDispatcher when-block dispatch → registry pattern (OCP) |
| 2 — Split | `3883a1ba` | split MessageEventHandler into 3 focused sub-event handlers |

## Chosen Registry Approach: **Plan B (manual registry)**

Selected over Hilt multi-binding (Plan A). Rationale:

1. **One handler maps to many event classes.** MiscEventHandler handles 15 events,
   SessionEventHandler 13. Hilt `@IntoMap @ClassKey` requires one `@Provides` per
   (eventClass → handler) entry — 39 boilerplate providers total. A manual `buildMap`
   lists each handler's events inline in one place.
2. **EventDispatcher already needs typed handler references.** It exposes read-only
   state delegated to concrete handlers (`messageHandler.messages`,
   `sessionHandler.sessions`, …) and cross-handler commands (`markSessionIdle`,
   `clearForSession`). These require concrete typed injection regardless, so the
   constructor already lists every handler.
3. **Lowest risk.** All handler internals are untouched. The constructor signature
   change in Part 2 (adding 3 message sub-handlers) only affected test setup blocks.

`processEvent` no longer hardcodes a handler call list. Adding a new event domain =
add one `bind()` call in `buildRegistry()`; `processEvent` itself never changes.

## EventDispatcher Changes (Part 1)

- **New `registry: Map<KClass<out SseEvent>, SseEventHandler>`** built by a private
  `buildRegistry()` helper that binds each of the 39 `SseEvent` subclasses to its
  single responsible handler (verified: zero duplicate registrations, exhaustive
  coverage).
- **`processEvent` rewritten**: the old broadcast (`sessionHandler.handle(ev)` …
  `sessionNextHandler.handle(ev)` for every event) is replaced by an O(1) registry
  lookup `registry[event::class]?.handle(event, serverId)`. An unregistered event
  logs a DEBUG warning (defensive; currently unreachable since all 39 events bind).
- **Kept unchanged** (per constraints):
  - `extractSessionId` when-block (pure function, L218).
  - Cross-cutting special cases: `SessionDeleted` cascade cleanup,
    `CommandExecuted → markSessionIdle`, `MessageUpdated(User) → recordUserMessage`.
- All delegate methods (`messages`, `parts`, `setMessages`, `clearForSession`, …)
  unchanged.

## MessageEventHandler Split (Part 2)

`MessageEventHandler` is now a **shared state store** (it no longer implements
`SseEventHandler`). The `_messages` / `_parts` / `assistantMessageIds` state is
tightly coupled across the message/part lifecycle — e.g. `handleMessagePartUpdated`
consults `assistantMessageIds` populated by `handleMessageUpdated`, and
`handleMessageUpdated(User)` seeds `_parts`. Splitting the state would have broken
these invariants, so the per-sub-event *dispatch* was extracted instead.

**New handler files** (each `@Singleton @Inject`, registered independently in the
registry, delegating to the shared store's `internal` handlers):

| File | Handles |
|------|---------|
| `handler/MessagePartHandler.kt` | `MessagePartUpdated`, `MessagePartDelta`, `MessagePartRemoved` |
| `handler/MessageUpdatedHandler.kt` | `MessageUpdated` |
| `handler/MessageRemovedHandler.kt` | `MessageRemoved` |

`MessageEventHandler.kt` retains all state, REST-sync (`setMessages` /
`mergeMessages` / `replaceMessages`), delta batching, `markSessionIdle`,
`pruneRevertedMessages`, and `clearFor*` methods. Its 5 `handleMessage*` methods
changed visibility `private → internal` so the sub-handlers (same package) can call
them. EventDispatcher's delegate methods still target `messageHandler.*` — unchanged.

## Verification

| Check | Result |
|-------|--------|
| `compileDevDebugKotlin` (after Part 1) | BUILD SUCCESSFUL (20s) |
| `testDevDebugUnitTest --rerun` (after Part 1) | BUILD SUCCESSFUL (43s) — all pass |
| `compileDevDebugKotlin` (after Part 2) | BUILD SUCCESSFUL (26s) |
| `testDevDebugUnitTest --rerun` (after Part 2) | BUILD SUCCESSFUL (43s) — all pass |
| Registry coverage | 39/39 events bound, 0 duplicates |
| Broadcast calls in `processEvent` | 0 |
| `extractSessionId` when-block | retained |
| 3 special cases | retained |
| Test updates | 7 test files (setup blocks only; no assertion logic changed) |

SSE-relevant suites confirmed green: `EventDispatcherIntegrationTest` (99
`processEvent` calls), `EventDispatcherTest`, `MessageEventHandlerTest`,
`MessageEventHandlerMergeTest`, `SessionNextEventTest`, `SseEventParserTest`, plus
the 3 `ChatViewModel*Test` suites.

## Concerns / Notes

1. **`MessageEventHandler.handle()` retained as a public aggregate entry point.**
   It is no longer an `override` (the class no longer implements `SseEventHandler`)
   and production dispatch never calls it — the registry routes to the 3
   sub-handlers. It exists solely so the existing `MessageEventHandlerTest` (44
   `handle()` calls) and `MessageEventHandlerMergeTest` (14 calls) keep working
   without rewriting 58 test invocations. This is a deliberate, documented trade-off
   (KDoc on the method explains it). A future cleanup could migrate those tests to
   drive the sub-handlers and then delete `handle()`.
2. **EventDispatcher constructor grew by 3 parameters** for the message
   sub-handlers. This is inherent to the typed-state-access design and was already
   the case for the other 5 handlers.
3. **Hilt DI requires no new module entries.** All new handlers are
   `@Inject constructor` singletons; Hilt resolves the dependency chain
   (`MessagePartHandler → MessageEventHandler → ()`) automatically.
