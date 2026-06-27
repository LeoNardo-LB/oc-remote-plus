# Phase 1 Follow-up Cleanup Report

**Branch:** `refactor/phase1-data-foundation`
**Base HEAD:** `63fa1493` (Phase 1 milestone)
**Date:** 2026-06-27

## Status: DONE

Both follow-ups completed. All changes compile and pass the full unit
test suite (975 tests, 0 failures).

## Follow-up 1: Rename `OpenCodeApi*Test` classes

After Block 2 split the monolithic `OpenCodeApi` into per-domain
interfaces (`SessionApi`, `MessageApi`, `FileApi`), six test classes
still carried the old `OpenCodeApi` prefix despite injecting the new
interfaces. Renamed file + class declaration to reflect the domain
interface actually under test.

| Old name | New name | Injected interface | Tests |
|---|---|---|---|
| `OpenCodeApiArchiveTest` | `SessionApiArchiveTest` | `SessionApi` | `updateSessionFields` (archive/unarchive) |
| `OpenCodeApiImportTest` | `SessionApiImportTest` | `SessionApi` | `importSession` |
| `OpenCodeApiMessageDeleteTest` | `MessageApiDeleteTest` | `MessageApi` | `deleteMessage` / `deleteMessagePart` |
| `OpenCodeApiSearchPaginationTest` | `SessionApiSearchPaginationTest` | `SessionApi` | `listSessions` (search/cursor/limit) |
| `OpenCodeApiVcsTest` | `FileApiVcsTest` | `FileApi` (+ `FileApiImpl`) | `getVcs` / `getVcsStatus` / `getVcsDiff` |
| `OpenCodeApiTest` | `DtoSerializationTest` | *(none — pure DTO serde)* | DTO round-trip characterization |

`OpenCodeApiTest` → `DtoSerializationTest`: this class injects no API
interface; it locks in the serialization contract for DTOs **not**
covered by `domain/model/SerializationTest`. The new name disambiguates
the two (data-layer DTOs vs domain models).

- Files renamed via `git mv` (history preserved).
- `rg` confirmed zero external references to the old class names.
- Naming convention `<Interface><Feature>Test` matches existing files
  in `data/api/`.

## Follow-up 2: Remove `MessageEventHandler.handle()`

`MessageEventHandler` retained a public `handle(event, serverId)`
aggregate after Block 3 — a `when` dispatcher over 5 message sub-events,
used **only by tests**. Migrated all test call sites to the `internal`
handlers, then deleted the method.

### Call-site mapping

| `handle()` event | → internal method | Sites |
|---|---|---|
| `SseEvent.MessageUpdated` | `handleMessageUpdated()` | 13 |
| `SseEvent.MessageRemoved` | `handleMessageRemoved()` | 3 |
| `SseEvent.MessagePartUpdated` | `handleMessagePartUpdated()` | 26 |
| `SseEvent.MessagePartDelta` | `handleMessagePartDelta()` | 14 |
| `SseEvent.MessagePartRemoved` | `handleMessagePartRemoved()` | 1 |
| **Total migrated** | | **57** |

The `serverId` parameter was dropped at every site: `handle()` accepted
it but never forwarded it to any internal handler (all internal methods
take only the event). Migrated call shape:

```kotlin
// before
handler.handle(SseEvent.MessagePartUpdated(part), "server1")
// after
handler.handleMessagePartUpdated(SseEvent.MessagePartUpdated(part))
```

### Deleted test

`returns false for non-message events` (passed `SseEvent.SessionCreated`)
was removed: it asserted `handle()`'s `else -> false` branch, which has
no internal-method equivalent. Dispatcher-level rejection of non-message
events now lives in `EventDispatcher`'s registry (unregistered event
classes are simply not routed). `MessageEventHandlerTest`: 25 → 24 tests.

### Production safety

Verified `MessageEventHandler.handle()` had **zero production callers**:
- `EventDispatcher.processEvent` (line 160) calls `registry[event::class].handle()`
  — that is `SseEventHandler.handle()` on the per-sub-event handlers
  (`MessagePartHandler`, `MessageUpdatedHandler`, `MessageRemovedHandler`),
  a **different method on different classes** that delegate to the
  `internal` handlers.
- `MessageEventHandler` is injected only as the shared state store
  (`messageHandler`), never registered in the dispatch registry.

## Verification

| Check | Command | Result |
|---|---|---|
| Compile + unit tests (FU-1) | `.\gradlew :app:testDevDebugUnitTest --rerun` | BUILD SUCCESSFUL (31s) |
| Compile + unit tests (FU-2) | `.\gradlew :app:testDevDebugUnitTest --rerun` | BUILD SUCCESSFUL (46s) |
| Affected suites | `MessageEventHandlerTest` / `MergeTest` | 24 / 4 tests, 0 failures |
| Full suite | all `*Test.xml` | 975 tests, 0 failures, 0 errors, 0 skipped |

The only compiler warnings are pre-existing unnecessary `!!` assertions
in `DtoSerializationTest.kt` (unchanged by this work).

## Commits

| Hash | Description |
|---|---|
| `94c52cbf` | `refactor(test): rename OpenCodeApi*Test to reflect tested domain interfaces` |
| `4e900e3a` | `refactor(sse): remove test-only MessageEventHandler.handle() aggregate` |

> Note: commit `94c52cbf` also swept in previously-untracked Phase 1
> review/report docs (`docs/superpowers/reports/phase1-block*-*.md`)
> that were present in the working tree; they belong to this branch.
