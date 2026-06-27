# Phase 2 Report: Util Extraction

**Status:** ✅ COMPLETE
**Branch:** `refactor/phase1-data-foundation`
**Base HEAD:** `24f61607`

## Goal

Extract 4 groups of pure logic functions from UI files into dedicated util
objects. UI files render; logic is independently testable. No behavior change.

## Results

| # | Extraction | New file (lines) | Source file (before → after) | Commit |
|---|-----------|------------------|------------------------------|--------|
| 1 | `QuestionParser` | `ui/screens/chat/util/QuestionParser.kt` (157) | `PartContent.kt` 708 → 568 | `894c5812` |
| 2 | `PromptBuilder` | `ui/screens/chat/util/PromptBuilder.kt` (96) | `ChatInputBar.kt` 592 → 478 | `836ab840` |
| 3 | `SlashCommandRegistry` | `ui/screens/chat/util/SlashCommandRegistry.kt` (37) | `ChatInputBar.kt` 478 → 478* | `d83f2e69` |
| 4 | `HighlightBuilder` | `ui/screens/viewer/HighlightBuilder.kt` (95) | `CodeSourceView.kt` 414 → 325 | `86aebfb1` |

\* Task 3 removed `clientCommands` + `SlashCommand` from ChatInputBar.kt; the
net line count is unchanged because the call site gained an import + a
qualified `SlashCommandRegistry.clientCommands()` call.

### Task 1 — QuestionParser
- Moved: `parseQuestionContent`, `parseQuestionFromToolData`
- Moved data classes (made `internal`, top-level): `ParsedQuestion`, `QHistOption`, `QHistItem`
- Wrapped in `object QuestionParser`. PartContent.kt calls `QuestionParser.parseXxx()`.
- Cleaned 4 orphaned `kotlinx.serialization.json.*` imports from PartContent.kt
  (kept `contentOrNull` / `jsonPrimitive`, still used by `Part.Agent`).

### Task 2 — PromptBuilder
- Moved: `buildPromptParts` (pure logic — `PromptPart` domain model + `PathUtils`).
- Wrapped in `object PromptBuilder`.
- Caller updated: `ChatScreen.kt` (import + `PromptBuilder.buildPromptParts(...)`).
- Cleaned orphaned `PromptPart` import from ChatInputBar.kt.

### Task 3 — SlashCommandRegistry
- Moved: `clientCommands` (`@Composable`, stays in UI layer) + `SlashCommand` data class.
- Wrapped `clientCommands` in `object SlashCommandRegistry`; `SlashCommand` kept top-level.
- Callers updated: `ChatInputBar.kt`, `ChatScreen.kt`, `SlashCommandSuggestions.kt`
  (gained explicit import — previously relied on same-package resolution).

### Task 4 — HighlightBuilder
- Moved: `buildHighlights`, `buildAnnotatedStringFromHighlights`, `rememberLanguage`,
  `buildAnnotatedLineWithAnnotations` + `ALPHA_MASK` constant.
- Wrapped in `object HighlightBuilder` (same package — no import needed in CodeSourceView).
- `rememberLanguage` kept as a plain function (NOT wrapped in `remember{}`) to preserve
  original recomposition behavior.
- Cleaned 11 orphaned imports (5 `dev.snipme.*` + `Color`, `AnnotatedString`, `SpanStyle`,
  `buildAnnotatedString`, `FontWeight`, `AlphaTokens`) — verified by analysis script.

## Verification

- **Compile check** (`compileDevDebugKotlin`) after each task: ✅ BUILD SUCCESSFUL
  - Task 1: 21s · Task 2: 23s · Task 3: 23s · Task 4: 16s
- **Unit tests** (`testDevDebugUnitTest --rerun`): ✅ BUILD SUCCESSFUL (31s)
  - **975 tests, 0 failures, 0 errors, 0 skipped** (102 suites)
- **Self-review grep**: no `private fun` definitions of the moved functions remain in UI files.

## Notes

- `QuestionParser.kt` is 157 lines, slightly over the soft "< 150 lines" guideline.
  The file is cohesive (2 parse functions + their 3 supporting data classes +
  KDoc); splitting would reduce cohesion. Kept as-is.
- All 4 util files use Kotlin `object` wrappers to namespace the functions, matching
  the requested `QuestionParser.parseXxx()` / `PromptBuilder.buildPromptParts()` call style.
- No `@Composable`-dependent logic was pushed into `domain/`; `clientCommands` (which
  uses `stringResource`) correctly stays in the UI layer.

## Commits

```
894c5812  refactor(phase2): extract QuestionParser from PartContent.kt
836ab840  refactor(phase2): extract PromptBuilder from ChatInputBar.kt
d83f2e69  refactor(phase2): extract SlashCommandRegistry from ChatInputBar.kt
86aebfb1  refactor(phase2): extract HighlightBuilder from CodeSourceView.kt
```
