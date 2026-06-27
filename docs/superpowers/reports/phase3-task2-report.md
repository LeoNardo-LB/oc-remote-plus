# Phase 3 Task 2 — DraftInputDelegate Extraction (D Cluster)

**Status**: ✅ COMPLETED
**Branch**: `refactor/phase1-data-foundation`
**Base HEAD**: `f7134ffa`
**Date**: 2026-06-27

## Objective

Extract the D cluster (draft text/attachments, @-file mention search, persisted-draft
load/save, failed-send/revert draft recovery) from `ChatViewModel` into a stateful
`DraftInputDelegate` class, following the pattern established by `TerminalDelegate` (Task 1).

## Files

| File | Lines | Change |
|------|-------|--------|
| `app/.../chat/DraftInputDelegate.kt` | 177 | **NEW** — owns all D-cluster state + methods |
| `app/.../chat/ChatViewModel.kt` | 2102 | was 2389 (**−287**) — 34 insertions, 148 deletions |
| `app/.../chat/TerminalDelegate.kt` | — | unchanged (reference pattern) |

## What Moved to DraftInputDelegate

**State** (7 fields):
- `_draftText`, `_draftAttachmentUris`, `_confirmedFilePaths` — draft input
- `_revertedDraftEvent` (SharedFlow) — revert one-shot event
- `_restoredDraft` — failed-send recovery
- `_fileSearchResults`, `fileSearchJob` — @-mention autocomplete

**Methods** (13):
- `searchFilesForMention`, `confirmFilePath`, `removeFilePath`, `clearFileSearch`, `clearConfirmedPaths`
- `updateDraftText`, `addDraftAttachment`, `removeDraftAttachment`, `clearDraft`, `consumeRestoredDraft`
- `saveDraft` (was private in VM, now `draftDelegate.saveDraft()` from `onCleared`)
- `restorePersistedDraft` (new — was inline in `loadSession`; returns `Draft?` so VM applies agent/variant)
- `restoreRevertedDraft`, `setRestoredDraft` (new — replaces direct `_restoredDraft.value =`)

## Constructor (8 params — NOT @Singleton/@Inject)

```
DraftInputDelegate(
    draftUseCase, manageAgentUseCase,      // injected deps forwarded by VM
    scope: CoroutineScope,                  // viewModelScope
    serverId: String,                       // immutable val
    sessionIdProvider,                      // { _sessionId.value }
    sessionDirectoryProvider,               // { sessionDirectory }
    selectedAgentProvider,                  // { _selectedAgent.value }  — cross-cluster for saveDraft
    selectedVariantProvider,                // { _selectedVariant.value } — cross-cluster for saveDraft
)
```

## Cross-Cluster Boundaries

| Boundary | Resolution |
|----------|------------|
| `saveDraft()` needs agent/variant (agent/model cluster) | Providers passed to delegate — delegate owns full `Draft` persistence |
| `loadSession` draft restore sets agent/variant | `restorePersistedDraft()` applies D-cluster fields + returns `Draft?`; VM applies agent/variant |
| `revertMessage` calls `restoreRevertedDraft` | VM keeps private wrapper → `draftDelegate.restoreRevertedDraft(payload)` |
| Failed send sets `_restoredDraft` | `draftDelegate.setRestoredDraft(payload)` |
| `uiState` combine referenced `_restoredDraft` | Now references `draftDelegate.restoredDraftState` |

## Verification

| Check | Result |
|-------|--------|
| `compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (23s) |
| `testDevDebugUnitTest --rerun` | ✅ 975 tests, 0 failures, 0 errors (40s) |
| UI facade names unchanged | ✅ all public properties/methods same name |

## Import Cleanup

Removed 3 orphaned imports from ChatViewModel (produced by this extraction):
- `kotlinx.coroutines.flow.MutableSharedFlow`
- `dev.leonardo.ocremotev2.domain.model.Draft`
- `kotlinx.coroutines.delay`

## Concerns

1. **8 constructor params** — slightly high but each is necessary (matches the
   stateful-delegate pattern; TerminalDelegate had 9). The 4 providers are lambdas
   that capture VM state, keeping the delegate decoupled from VM internals.

2. **`saveDraft()` cross-cluster read** — the delegate reads agent/variant via providers
   at save time. This is a one-way read (D → agent/model), acceptable for a persistence
   operation. Alternative (keep saveDraft in VM) was rejected because it would split
   draft persistence across two files.
