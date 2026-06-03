# Document Review Report

**Reviewed Documents:**
- `docs/superpowers/specs/2026-06-03-material3-color-cleanup-design.md` (Spec)
- `docs/superpowers/plans/2026-06-03-material3-color-cleanup-plan.md` (Plan)

**Date:** 2026-06-03
**Rounds:** 2

---

## Round 1 Results

| Dimension | Result | P0 | P1 | P2 |
|-----------|--------|----|----|-----|
| D1 Context Consistency | ✅ PASS | 0 | 0 | 1 |
| D2 Internal Logic | ❌ FAIL | 1 | 3 | 2 |
| D3 External Facts | ✅ PASS | 0 | 0 | 1 |
| D4 Technical Feasibility | ❌ FAIL | 1 | 1 | 0 |
| D5 Executability | ✅ PASS | 0 | 0 | 3 |
| D6 Prerequisite Completeness | ❌ FAIL | 1 | 1 | 0 |
| D7 Acceptance Criteria | ❌ FAIL | 0 | 2 | 2 |
| D8 Boundary Completeness | ❌ FAIL | 1 | 2 | 2 |
| D9 Structural Clarity | ❌ FAIL | 0 | 2 | 2 |
| **Total** | | **4** | **11** | **13** |

### Round 1 Key Issues

| # | Level | Issue | Location |
|---|-------|-------|----------|
| 1 | **P0** | AMOLED theme core assumption wrong — only surface/surfaceContainerLowest/background are Color.Black, not secondaryContainer/primaryContainer/tertiaryContainer/errorContainer | Spec §3 Pattern, Plan Architecture |
| 2 | **P0** | toolOutputContainerColor callers (6 tool card files) missing from Affected Files | Spec §3, Plan Task 6 |
| 3 | **P0** | DiffHelpers.kt path wrong — `markdown/` should be `tools/` | Spec §2.2, Plan Task 3 |
| 4 | **P0** | Missing AmoledDialogParams callers (OpenProjectDialog, ServerProvidersScreen) not listed | Spec §3, Plan Task 8 |
| 5 | **P1** | TerminalEmulator: Spec says "should use surface" but Plan decides "do not change" | Spec §3 Notes vs Plan Task 7 |
| 6 | **P1** | AmoledCard listed in Affected Files but decided not to change | Spec §3 vs Plan Task 8 |
| 7 | **P1** | Switch track color discussion has 5 rounds of self-contradiction | Plan Task 8 Step 2 |
| 8 | **P1** | No Task dependency declarations | Plan |
| 9 | **P1** | Missing visual verification checklist for AMOLED mode | Spec §5, Plan |

---

## Round 1 Fixes Applied

| Fix | What Changed |
|-----|-------------|
| Spec §3 Pattern | Added note explaining AMOLED token resolution — only surface series is Black, others inherit Material 3 dark defaults; user accepted subtle visual shift |
| Spec §3 Affected Files | Added 6 tool card caller files |
| Spec §2.2 / §3 | Corrected DiffHelpers.kt path from `markdown/` to `tools/` |
| Spec §4 Not Changing | Added TerminalEmulator default background and AmoledCard |
| Spec §3 Important Notes | Removed TerminalEmulator "should use surface", replaced with cross-reference to §4 |
| Plan Architecture | Corrected AMOLED assumption statement |
| Plan Task 6 Files | Added 6 tool card files |
| Plan Task 8 Step 2 | Replaced 30-line discussion with concise final conclusion |
| Plan Dependencies | Added Task Dependencies section with ASCII DAG |
| Plan Task 3 | Fixed DiffHelpers.kt path |

Commit: `dc468c2` (bulk fix) + `c87a81a` (TerminalEmulator note fix)

---

## Round 2 Results

| Dimension | Result | Notes |
|-----------|--------|-------|
| D1-D3 | ✅ PASS | All P0/P1 resolved |
| D4-D6 | ✅ PASS | Core assumption corrected, callers listed, paths fixed |
| D7 | ✅ PASS (P2 remaining) | Visual verification checklist is brief but functional |
| D8 | ✅ PASS | Boundaries covered, callers listed |
| D9 | ✅ PASS | Structure clean, dependencies declared |

**1 new P1 found and immediately fixed:** Spec §3 Important Notes line 156 still said "should use surface" for TerminalEmulator while §4 said "do not change". Fixed in `c87a81a`.

---

## Residual Issues (P2 only — not blocking)

| # | Issue | Recommendation |
|---|-------|---------------|
| 1 | Plan Task 6 Step 1 table has no code context (only file+line+pattern) | Acceptable — implementer reads each file before edit |
| 2 | Plan Task 6 "Also clean up unused isAmoled" not file-specific | Acceptable — compile check catches issues |
| 3 | Spec §5 visual verification is manual/subjective | Could add screenshot checklist in future iteration |
| 4 | Plan Task 8 Step 4 AmoledCard discussion still slightly verbose | Acceptable — decision is clear |

---

## Conclusion

**✅ All P0/P1 issues resolved across 2 rounds.** Documents are ready for implementation.

Key design decision confirmed by user: AMOLED Color.Black replacements that produce subtle visual shifts (pure black → Material 3 dark tints) are acceptable, as they align with Material Design guidelines.
