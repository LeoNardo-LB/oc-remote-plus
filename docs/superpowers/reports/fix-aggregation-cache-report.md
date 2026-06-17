# Fix Report: cacheHitRate + write/listDirectory aggregation

**Commit**: `f142828`
**Branch**: `feat/chat-info-density`
**Date**: 2026-06-18

## 3 issues fixed

### 1. cacheHitRate formula bug (ContextStats.kt)

**Problem**: `cacheRead / input` yields 29788% when cacheRead(202560) >> input(680).
**Fix**: Changed to `cacheRead / (input + cacheRead)` — cache hit rate as proportion of total reads.
**Test**: 3 cases — equal split → 0.5f, no cache → 0f, both zero → null.

### 2. write + listDirectory not aggregated (PartGrouping.kt)

**Problem**: `CONTEXT_GROUP_TOOLS` missed `write` and `listDirectory`, so these tools rendered individually instead of collapsing into context group cards.
**Fix**: Added both to the set. Updated `ContextToolSummary` with `write` field and `contextToolSummary()` counting logic (`listDirectory` → list bucket, `write` → write bucket).
**Test**: `listDirectory` and `write` each verified as grouped context tools.

### 3. ContextToolGroupCard missing write count display

**Problem**: Collapsed summary only showed read/search/list counts; write operations were invisible.
**Fix**: Added conditional Text for `summary.write > 0` after list block, matching existing style.
**Strings**: Added `chat_context_count_write` in both `values/strings.xml` (中文) and `values-en/strings.xml` (EN).

## Files changed (7)

| File | Change |
|------|--------|
| `ContextStats.kt` | cacheHitRate formula |
| `PartGrouping.kt` | CONTEXT_GROUP_TOOLS + ContextToolSummary + contextToolSummary |
| `ContextToolGroupCard.kt` | write count display |
| `values/strings.xml` | chat_context_count_write (zh) |
| `values-en/strings.xml` | chat_context_count_write (en) |
| `ContextStatsTest.kt` | 3 cacheHitRate tests (replaced 1) |
| `PartGroupingTest.kt` | updated summary test + 2 new group tests |

## Verification

- `compileDevDebugKotlin`: BUILD SUCCESSFUL (30s)
- `testDevDebugUnitTest (*PartGroupingTest* *ContextStatsTest*)`: BUILD SUCCESSFUL (31s)
- No unrelated changes detected in diff.
