# Phase 3 Task 1 Report — TerminalDelegate + ScrollPositionDelegate

**Status:** ✅ Complete
**Branch:** `refactor/phase1-data-foundation`
**Commit:** `f7134ffa`
**Date:** 2026-06-27

## Summary

First delegate-extraction pilot. Extracted two low-cross-domain clusters from
`ChatViewModel` into self-contained delegate classes. ChatViewModel keeps thin
facade properties/methods (identical names/signatures) so all 81 UI files are
unchanged.

## Created Delegate Files

| File | Lines | Location |
|------|-------|----------|
| `TerminalDelegate.kt` | 108 | `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/` |
| `ScrollPositionDelegate.kt` | 66 | `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/` |

### TerminalDelegate
- Owns the server-scoped `ServerTerminalWorkspace` (+ debug init log).
- 8 derived members: `terminalTabs`, `activeTerminalTabId`, `terminalVersion`,
  `terminalConnected`, `terminalFontSizeSp`, `terminalEmulator`,
  `terminalCursorKeysAppMode`.
- 10 methods: `openTerminalSession`, `createTerminalTab`, `switchTerminalTab`,
  `closeTerminalTab`, `reconnectTerminalTab`, `setTerminalFontSize`,
  `sendTerminalInput`, `clearTerminalBuffer`, `resizeTerminal`,
  `closeTerminalSession`.
- Owns the terminal font-size settings collector (moved from VM `init`).
- Constructor receives runtime context (see Concerns #1, #4).

### ScrollPositionDelegate
- Zero dependencies — pure state holder.
- 5 states: `savedMessageId`, `savedLazyIndex`, `savedScrollOffset`,
  `scrollRestoreVersion`, `hasPendingScrollRestore`.
- 3 methods: `clearPendingScrollRestore`, `saveScrollPosition`,
  `bumpScrollRestoreIfPending`.

## ChatViewModel Line Count

**2450 → 2201 lines (−249).**

## Verification

| Check | Result |
|-------|--------|
| `compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (27s) |
| `testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (40s) |
| Unit test count | **975 tests, 0 failures, 0 errors, 0 skipped** |
| UI files touched | 0 (facades preserve all public names/signatures) |

## Concerns

### 1. Delegates are NOT `@Singleton`/`@Inject` (deviation from plan)

The plan specifies every delegate as `@Inject constructor(...) @Singleton`.
This is **incorrect for any delegate holding per-ChatViewModel runtime state**.
Both delegates here hold context derived from `SavedStateHandle`
(server credentials) and/or the ViewModel's `viewModelScope`, which Hilt cannot
supply to a `@Singleton`.

**Resolution:** Both delegates are plain `internal class`es constructed
directly by `ChatViewModel`. Hilt-providable deps (`ServerTerminalRegistry`,
`SettingsRepository`) are still injected into the VM constructor and forwarded.

**Impact on future tasks:** This applies to ALL stateful delegates
(Tasks 2–5). Only `SessionActionsDelegate` (Task 6 — pure stateless UseCase
delegation) can legitimately be `@Singleton`/`@Inject`. Recommend the plan be
updated to reflect "plain class for stateful delegates; `@Inject` only for
stateless ones."

### 2. `getConnectionParams()` kept in ChatViewModel

The task listed 11 terminal methods including `getConnectionParams`. It is
**not terminal-related** — it returns connection-level params
(`serverUrl`, `username`, `password`, `serverName`, `serverId`) used for
sub-session navigation, and references `serverName` which has no terminal use.
Moved 10 of the 11 listed methods; `getConnectionParams` remains in the VM
(more naturally belongs to a future SessionLifecycleDelegate or
SessionActionsDelegate).

### 3. `savedMessageId` is pre-existing dead state

`savedMessageId` is declared but never assigned and never meaningfully read
(pre-existing, not introduced by this change). Preserved verbatim in
`ScrollPositionDelegate` per the AGENTS.md dead-code policy
("不得触碰预先存在的死代码"). Flagged for a future cleanup pass.

### 4. `terminalWorkspace` initialization dependency

`openTerminalSession`/`createTerminalTab` depend on `sessionDirectory` and
`sessionLoaded` (currently VM-owned). The delegate receives these as a
`sessionDirectoryProvider: () -> String?` lambda and a
`CompletableDeferred<Unit>` via constructor. **When Task 3
(SessionLifecycleDelegate) extracts `_sessionId`/`sessionDirectory`/
`sessionLoaded`, `TerminalDelegate`'s constructor must be updated** to receive
the `SessionLifecycleDelegate` reference instead of the raw lambda/deferred.
This is expected churn in a phased refactor — the lambda seam keeps the
boundary explicit.
