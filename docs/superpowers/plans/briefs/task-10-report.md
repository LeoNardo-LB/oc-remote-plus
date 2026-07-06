# Task 10 Report — Streaming UI reads activityFlow (A1, approach B: FSM + part.time combined)

**Status:** ✅ COMPLETED
**Commit:** `668384e3` — `refactor(chat): streaming UI reads activityFlow (A1, FSM+part.time combined)`
**Compile:** BUILD SUCCESSFUL in 55s (`:app:compileDevDebugKotlin`) — 0 errors, 2 pre-existing deprecation warnings (`Icons.Filled.HelpOutline` in PartContent.kt, untouched by this task).

## Decision implemented

Approach B: `isStreaming = (session activity is Streaming) AND (part.time.end == null)`.
FSM `activityFlow` is the authoritative gate (fixes the part.time-end-stuck-null bug where the reasoning timer never stopped if the SSE `*.ended` event was missed). `part.time.end` keeps per-part precision so only the *current* reasoning part shows the timer during a stream — not every historical reasoning part on the screen.

## How activityFlow was wired into sessionMetaState combine

`ChatViewModel.sessionMetaState` was a `combine` of 5 source flows (indices 0–4). Following the brief's guidance ("add activityFlow as the LAST source to avoid shifting existing indices"), I added `sessionStateService.activityFlow` as the **6th source (index 5)**.

```kotlin
val sessionMetaState: StateFlow<SessionMetaState> = combine(
    sessionLifecycle.sessionIdFlow,                         // 0  (unchanged)
    sessionRepository.getSessionsFlow(serverId),            // 1  (unchanged)
    sessionStateService.statusFlow,                         // 2  (unchanged)
    sessionRepository.getCurrentAgentFlow(serverId),        // 3  (unchanged)
    sessionRepository.getCurrentModelFlow(serverId),        // 4  (unchanged)
    sessionStateService.activityFlow,                       // 5  (NEW)
) { args ->
    ...
    val activities = args[5] as Map<String, SessionActivity?>   // NEW
    val isStreaming = activities[sid] is SessionActivity.Streaming  // NEW
    SessionMetaState(..., isStreaming = isStreaming)
}
```

Index verification: existing `args[0..4]` extractions are unchanged; the new `args[5]` cast is the only addition. `SessionActivity` requires no new import — it's covered by the existing wildcard `import dev.leonardo.ocremotev2.domain.model.*` (line 30). The `SessionMetaState` data class gained `val isStreaming: Boolean = false` (default keeps the legacy `uiState` combine and any other consumer unaffected).

## ChatMessageList gate (streamingMsgId)

`ChatMessageList` already receives `sessionMeta: SessionMetaState` as a parameter, so **no new parameter was needed** (the brief explicitly allowed this: "If it already receives sessionMeta ... use it"). The gate is a single suffix on the existing `remember`:

```kotlin
val streamingMsgId = remember(rawMessages) {
    rawMessages.lastOrNull {
        it.isAssistant && it.message.time.completed == null
    }?.message?.id
}?.takeIf { sessionMeta.isStreaming }
```

`?.takeIf { ... }` was chosen specifically to keep the `remember { }` call unconditional (Compose rules of composition). When the session is not streaming, `streamingMsgId` becomes `null` → the streaming-message height-compensation branch (line ~396) and the `CompensateState` key reset behave correctly without touching the layout path.

The pre-existing `AutoPag` `Log.d` debug logs (around line 234+) were left untouched as instructed; they were staged along with this one-line change, which is acceptable per the brief.

## PartContent CompositionLocal (Reasoning branch)

```kotlin
// BEFORE:
val isStreaming = part.time?.end == null
// AFTER (approach B):
val isStreaming = LocalSessionStreaming.current && (part.time?.end == null)
```

`durationMs` / `startTimeMs` still read `part.time` directly (unchanged) — only the `isStreaming` flag is gated. When the FSM activity leaves `Streaming` (e.g. on a missed `text.ended` followed by `session.updated` idle, or on background-then-foreground where the FSM has since transitioned to `Waiting`/`Idle`), `LocalSessionStreaming.current` flips to `false` and the timer stops even though `part.time.end` may still be null.

## LocalSessionStreaming definition + ChatScreen provider

`LocalSessionStreaming` is defined in `ChatCompositionLocals.kt` (the existing shared file for chat CompositionLocals — consistent with `LocalExpandReasoning`, `LocalToolExpandedStates`, etc.):

```kotlin
val LocalSessionStreaming = staticCompositionLocalOf { false }
```

`staticCompositionLocalOf` is appropriate here: the value changes only when the FSM activity transitions (low frequency), and — like `LocalOnViewTool` — using `static` avoids the read-tracking overhead that would otherwise invalidate every `PartContent` consumer on each ChatScreen recomposition.

It is provided in `ChatScreen`'s top-level `CompositionLocalProvider` (the same block that already provides `LocalOnViewTool`, `LocalToolExpandedStates`, etc.):

```kotlin
LocalSessionStreaming provides sessionMeta.isStreaming,
```

This single provider covers both `ChatMessageList` call sites (main-session branch at ~line 1027 and sub-session branch at ~line 1052), since both render inside that block. No per-callsite parameter threading was required.

## Files changed (5)

| File | Change |
|------|--------|
| `ChatViewModel.kt` | `SessionMetaState.isStreaming` field; `activityFlow` as 6th combine source (index 5); `isStreaming` computed from `SessionActivity.Streaming`. |
| `ChatMessageList.kt` | `streamingMsgId` gated by `?.takeIf { sessionMeta.isStreaming }` (1 line). (Pre-existing AutoPag debug logs staged unchanged.) |
| `PartContent.kt` | Reasoning `isStreaming` = `LocalSessionStreaming.current && (part.time?.end == null)`; import added. |
| `ChatScreen.kt` | `LocalSessionStreaming provides sessionMeta.isStreaming` in CompositionLocalProvider; import added. |
| `ChatCompositionLocals.kt` | `LocalSessionStreaming = staticCompositionLocalOf { false }` defined; `staticCompositionLocalOf` import added. |

## Self-review

- **Index safety** ✅ — `activityFlow` added as last source (index 5); all pre-existing `args[0..4]` extractions byte-identical.
- **Unconditional `remember`** ✅ — used `?.takeIf` suffix rather than wrapping `remember` in an `if`, so Compose's conditional-call rule is not violated.
- **Coverage** ✅ — both `ChatMessageList` call sites (main + sub session) sit inside the single `CompositionLocalProvider`, so one `provides` covers both. No per-callsite param needed.
- **No over-reach** ✅ — `durationMs`/`startTimeMs` left reading `part.time`; legacy `uiState` combine untouched (it just doesn't propagate `isStreaming`, which is fine — ChatScreen reads `sessionMetaState` directly at line 283).
- **Import hygiene** ✅ — `SessionActivity` covered by existing wildcard import; added explicit imports only for the new `LocalSessionStreaming` in the two consumers and `staticCompositionLocalOf` in the locals file.
- **Compose performance** ✅ — `staticCompositionLocalOf` chosen to match the existing `LocalOnViewTool` pattern (avoids per-token recomposition invalidation of all PartContent consumers).

## Concerns / follow-ups

- **No unit test** — Compose UI behavior (timer stop/start) is not covered by unit tests in this project; the brief scoped this as compile-only. Manual verification (open a session, trigger streaming, confirm timer stops on completion / on background-then-foreground) is recommended when a device is available.
- **Pre-existing debug logs** — the `AutoPag` `Log.d` lines in `ChatMessageList.kt` were committed as part of this change (acceptable per brief). They should be cleaned up in a separate pass before release.
- **Legacy `uiState` does not expose `isStreaming`** — intentional, since `uiState` is test-back-compat only and `ChatScreen` reads `sessionMetaState` directly. If a future test needs `isStreaming` via `uiState`, the legacy combine (line ~683) would need a corresponding `ChatUiState` field; out of scope here.
