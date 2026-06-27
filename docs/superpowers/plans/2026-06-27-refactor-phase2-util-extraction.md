# Phase 2: Util Extraction

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Extract 4 groups of pure logic functions from UI files to dedicated util files — UI only renders, logic is testable independently.

**Architecture:** Each extraction moves pure functions to a new file. The original UI file imports and calls the new util. No behavior change.

**Tech Stack:** Kotlin, Compose, dev.snipme.highlights (for HighlightBuilder)

## Global Constraints

- No behavior change — functions move, calling sites update
- Each util file < 150 lines
- Compile check after each extraction: `.\gradlew :app:compileDevDebugKotlin` (120s)
- Tests after all: `.\gradlew :app:testDevDebugUnitTest --rerun` (180s)
- Gradle daemon disabled; `.\gradlew --stop` if stuck

---

## Task 1: QuestionParser (from PartContent.kt)

**Source:** `ui/screens/chat/components/PartContent.kt` — `parseQuestionContent`, `parseQuestionFromToolData`
**Target:** `domain/util/QuestionParser.kt` (or `ui/screens/chat/util/` if depends on UI types)

- [ ] Read PartContent.kt, locate the two parse functions
- [ ] Create QuestionParser.kt with the functions (same signatures, public)
- [ ] Update PartContent.kt: import + replace calls with `QuestionParser.parseXxx()`
- [ ] Compile check, commit

## Task 2: PromptBuilder (from ChatInputBar.kt)

**Source:** `ui/screens/chat/input/ChatInputBar.kt` — `buildPromptParts`
**Target:** `domain/util/PromptBuilder.kt` (or `ui/screens/chat/util/`)

- [ ] Read ChatInputBar.kt, locate buildPromptParts
- [ ] Create PromptBuilder.kt
- [ ] Update ChatInputBar.kt: import + replace call
- [ ] Compile check, commit

## Task 3: SlashCommandRegistry (from ChatInputBar.kt)

**Source:** `ui/screens/chat/input/ChatInputBar.kt` — `clientCommands`
**Target:** `ui/screens/chat/util/SlashCommandRegistry.kt`

- [ ] Create SlashCommandRegistry.kt with the command list
- [ ] Update ChatInputBar.kt: import + replace call
- [ ] Compile check, commit

## Task 4: HighlightBuilder (from CodeSourceView.kt)

**Source:** `ui/screens/viewer/CodeSourceView.kt` — `buildHighlights`, `buildAnnotatedStringFromHighlights`, `rememberLanguage`, `buildAnnotatedLineWithAnnotations`
**Target:** `ui/screens/viewer/HighlightBuilder.kt` (depends on dev.snipme + Compose, stays in UI layer)

- [ ] Read CodeSourceView.kt, locate the 4 functions
- [ ] Create HighlightBuilder.kt with the functions
- [ ] Update CodeSourceView.kt: import + replace calls
- [ ] Compile check, commit

---

## Self-Review

- [ ] `rg "private fun parseQuestion|private fun buildPromptParts|private fun clientCommands|private fun buildHighlights" app/src/main/kotlin/` — these should be gone from UI files (moved to util)
- [ ] Each util file compiles independently
- [ ] All tests pass
