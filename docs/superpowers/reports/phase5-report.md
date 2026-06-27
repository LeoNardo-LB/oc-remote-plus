# Phase 5: Test Coverage Audit + Gap Filling

**Branch**: `refactor/phase1-data-foundation`
**Base HEAD**: `3410b237`
**Date**: 2026-06-27

## Coverage Audit

### Delegate Coverage (7 delegates in `ui/screens/chat/`)

| Delegate | Direct Test | Indirect Coverage | Status |
|----------|------------|-------------------|--------|
| `SessionLifecycleDelegate` | **NEW** — 4 tests (ensureSession fast path, creation, mutex concurrency, initForNewSession) | None found (grep: 0 hits for `ensureSession` in tests) | **Gap filled** |
| `ScrollPositionDelegate` | **NEW** — 7 tests (save/bump/clear/multi-save/re-arm) | None found (grep: 0 hits for `scrollPosition` in tests) | **Gap filled** |
| `MessageDataDelegate` | None | ChatViewModelQueuedTest + ChatViewModelStreamingTest (~30 @Test) | Sufficient — no action |
| `DraftInputDelegate` | None | ChatViewModelSendTest (~15 @Test, covers draft/send flow) | Sufficient — no action |
| `ModelConfigDelegate` | None | ChatViewModelSendTest + ManageServerProvidersUseCaseTest (grep: `selectModel`/`loadProviders` hits) | Sufficient — no action |
| `SessionActionsDelegate` | None | ChatViewModelDeleteTest (~12 @Test) | Sufficient — no action |
| `TerminalDelegate` | None | TerminalRepositoryImplTest + PtyToTermlibAdapterTest + ChatViewModel*Test | Sufficient — no action |

### Util Coverage (3 utils)

| Util | Direct Test | Evidence | Status |
|------|------------|----------|--------|
| `QuestionParser` | **NEW** — 10 tests (parseQuestionContent 3 formats + parseQuestionFromToolData 4 scenarios) | grep `QuestionParser` in tests: **0 hits** (PartRenderLogicTest does NOT call it) | **Gap filled** |
| `HighlightBuilder` | **NEW** — 8 tests (rememberLanguage 5 + buildHighlights 3) | grep `HighlightBuilder` in tests: **0 hits** (FileViewerViewModelTest does NOT call it) | **Gap filled** |
| `SlashCommandRegistry` | Skipped | Uses `@Composable` + `stringResource` — requires instrumented test, not unit-testable | No action — by design |

## Changes

### New test files (4 files, 29 tests)

| File | Tests | Coverage |
|------|-------|----------|
| `app/src/test/.../chat/util/QuestionParserTest.kt` | 10 | parseQuestionContent (opencode text/JSON/plain), parseQuestionFromToolData (structured/answers/fallback) |
| `app/src/test/.../viewer/HighlightBuilderTest.kt` | 8 | rememberLanguage (extensions/uppercase/unknown), buildHighlights (kotlin/python/empty) |
| `app/src/test/.../chat/ScrollPositionDelegateTest.kt` | 7 | save/bump/clear lifecycle, multi-save versioning, re-arm after clear |
| `app/src/test/.../chat/SessionLifecycleDelegateTest.kt` | 4 | ensureSession fast-path/creation/mutex-concurrency, initForNewSession |

### Infrastructure change

- `app/build.gradle.kts`: added `testImplementation("org.json:json:20240303")` — QuestionParser uses `org.json.JSONObject/JSONArray` which require the real JAR (Android SDK stub returns defaults, masking bugs)

## Verification

```
.\gradlew :app:testDevDebugUnitTest --rerun
→ BUILD SUCCESSFUL in 24s
→ 1004 tests | 0 failures | 0 errors
```

**Test count**: 975 (existing) + 29 (new) = **1004 total**

## Decisions

- **Skipped SlashCommandRegistry**: `@Composable` function with `stringResource` — not unit-testable without Robolectric/instrumented test
- **Skipped HighlightBuilder AnnotatedString builders**: `buildAnnotatedStringFromHighlights` / `buildAnnotatedLineWithAnnotations` use compose-ui-text (`AnnotatedString`, `SpanStyle`) which has no precedent in existing unit tests — risk of classpath issues outweighs value; `rememberLanguage` + `buildHighlights` cover the core pure logic
- **Skipped 4 low-priority delegates**: MessageDataDelegate, DraftInputDelegate, ModelConfigDelegate, SessionActionsDelegate are transitively covered by 5 ChatViewModel*Test files (~60+ @Test methods)
