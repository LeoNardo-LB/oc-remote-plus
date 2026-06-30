# RenderableTurn Wiring Report

## Status: COMPLETE

## Commit

- **SHA**: `259ccdf1`
- **Message**: `perf(chat): pre-compute RenderableTurn, eliminate inline computation in MessageCard`

## Compile Result

`.\gradlew :app:compileDevDebugKotlin --no-daemon` — **BUILD SUCCESSFUL** (25s, first try, zero errors)

## Changes Summary

### 3 files changed, 177 insertions(+), 110 deletions(-)

| File | Change |
|------|--------|
| `tools/RenderableTurn.kt` | New (pre-existing from prior step). Fixed `formatError` param type from `(Any?) -> String?` to `(Message.Assistant.ErrorInfo?) -> String?` to match `formatAssistantErrorMessage` signature. |
| `components/ChatMessageList.kt` | Added pre-computation `remember` block (`renderableTurns`). Changed `MessageCard` call to pass `renderableTurn` instead of `turnMessages` + inline `copyText`. |
| `components/MessageCard.kt` | Net -40 lines. `MessageCardAssistant` now accepts `RenderableTurn` and iterates `renderItems` — all `filterRenderableParts` / `groupContextParts` / `reversed` / `flatMap` / `remember` computation removed. |

## What Was Eliminated (inline computation during composition)

1. **`renderableParts` remember block** — `orderedTurnMessages?.reversed()?.flatMap { filterRenderableParts }`
2. **`errorText` remember block** — `orderedTurnMessages?.reversed()?.firstNotNullOfOrNull { ... }`
3. **Render loop redundant computation** (NOT remembered) — `turnMsgs` + `filterRenderableParts(msg.parts)` + `groupContextParts(msgParts)` every composition
4. **`agentParts` computation** — `orderedTurnMessages?.flatMap { it.parts }?.filterIsInstance<Part.Agent>()`
5. **`agentName` computation** — `(orderedTurnMessages?.lastOrNull()?.message as? Message.Assistant)?.agent`
6. **`stepFinishes` remember block** — `orderedTurnMessages.flatMap { it.parts.filterIsInstance<Part.StepFinish>() }`
7. **`durationMs` computation** — `last.time.completed - first.time.created`
8. **`modelId` computation** — `(orderedTurnMessages?.lastOrNull()?.message as? Message.Assistant)?.modelId`

All of the above now happen once in a single `remember(rawMessages, displayItems, turnGroups)` block in `ChatMessageList`.

## Design Decisions

### `formatError` type fix
`computeRenderableTurn` originally declared `formatError: (Any?) -> String?`, but `formatAssistantErrorMessage` accepts `Message.Assistant.ErrorInfo?`. Kotlin function types are contravariant in parameters, so `(ErrorInfo?) -> String?` is NOT assignable to `(Any?) -> String?`. Fixed the param type to `(Message.Assistant.ErrorInfo?) -> String?`.

### `currentMessage` retained
The task description suggested removing `currentMessage` from `MessageCardAssistant`, but it's still needed for non-computed display fields (`assistantMsg.time.created` for timestamp, `assistantMsg.providerId` for provider icon). These are NOT part of `RenderableTurn`. Kept `currentMessage` — it's not inline computation, just property reads.

### `isTurnLast` retained as separate param
`RenderableTurn` pre-computes metadata gated by `isTurnLast` (agentName, modelId, durationMs, stepFinishes are null/empty when `!isTurnLast`). But the footer rendering logic still explicitly checks `isTurnLast` in conditions like `if (!hasFooter && isTurnLast && copyText != null)`. Removing these checks would cause non-turn-last messages to render spurious fallback footers. Kept `isTurnLast` as a separate param.

### `LocalShowTurnDividers` moved to top
Previously read inside the render loop via fully-qualified `dev.leonardo.ocremotev2.ui.screens.chat.util.LocalShowTurnDividers.current` on every iteration. Now read once at function top level as `showTurnDividers` and imported properly.

## Issues

None. Compiled clean on first attempt.
