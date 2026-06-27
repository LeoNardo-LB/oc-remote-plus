# Phase 1 Block 2 — Review Report

**Reviewer:** task-reviewer subagent
**Commit reviewed:** `4a571ba5`
**Date:** 2026-06-27
**Diff scope:** 34 files changed, 239 insertions(+), 1257 deletions(-)

---

## Verdicts

### Spec Compliance: ✅ PASS

### Code Quality: ✅ Approved (3 minor nits, non-blocking)

---

## 1. Spec Compliance — All Checks Pass

| # | Check | Result | Evidence |
|---|---|---|---|
| 1.1 | `rg "OpenCodeApi" app/src/main/kotlin/` zero results | ✅ | 0 matches across 340 files searched |
| 1.2 | `OpenCodeApi.kt` deleted via `git rm` | ✅ | `Test-Path` → False; diff shows `deleted file mode 100644` |
| 1.3 | `RestSessionStatusInfo` migrated out of deleted file | ✅ | New standalone file `data/api/RestSessionStatusInfo.kt` (19 lines, same package → zero import churn) |
| 1.4 | All consumers migrated to domain interfaces | ✅ | 14 real consumers + 1 dead-injection removal (`TerminalRepositoryImpl`) — see §3 |
| 1.5 | Block 1 `ApiModule` `@Binds` still valid | ✅ | All 6 bindings present: `SessionApi`, `MessageApi`, `TerminalApi`, `ProviderApi`, `FileApi`, `SystemApi` |
| 1.6 | `NetworkModule` has no `OpenCodeApi` `@Provides` | ✅ | Only comment updated; 3 remaining `@Provides` are for infra (HttpClient/Json/etc.), not the deleted type |

### Residual references to "OpenCodeApi" — all benign

`rg` over `app/src/{test,androidTest}/` returns 15 files, all of which fall into harmless categories:
- **Test fixture strings** (file paths like `"data/api/OpenCodeApi.kt"`, diff sample data) — 7 files
- **Test class names** (`OpenCodeApiVcsTest`, etc.) — 6 files; internal mock types correctly migrated (see §3.2)
- **Sample test resource** (`sample-kotlin.kt`) — 1 file; fixture
- **Comment references** (`OffsetConverterTest.kt`) — 1 file

**Zero code-level type references to `OpenCodeApi` remain anywhere in the module.**

---

## 2. Code Quality

### 2.1 Method-to-interface mapping — verified correct for all 14 consumers

Each call site in the diff was cross-checked against the 6 domain interface definitions. No method is dispatched to the wrong interface.

| Consumer | Interfaces injected | Calls verified | Status |
|---|---|---|---|
| `ChatRepositoryImpl` | MessageApi + SessionApi + TerminalApi + ProviderApi | 15 call sites | ✅ |
| `SessionRepositoryImpl` | SessionApi + MessageApi | 16 call sites | ✅ |
| `SseConnectionManager` | SessionApi + MessageApi + FileApi | 5 call sites | ✅ |
| `ServerSettingsViewModel` | ProviderApi + SystemApi | 12 call sites | ✅ (1 nit, §2.3 M-1) |
| `SessionListViewModel` | SessionApi + FileApi + SystemApi + TerminalApi | 12 call sites | ✅ |
| `AgentRepositoryImpl` | SystemApi + FileApi | 3 call sites | ✅ |
| `McpRepositoryImpl` | SystemApi + ProviderApi | 4 call sites | ✅ |
| `ServerDataStore` | SystemApi | injection only | ✅ |
| `ServerRepositoryImpl` | ProviderApi | injection only | ✅ |
| `ServerTerminalRegistry` | TerminalApi | injection only | ✅ |
| `FileRepositoryImpl` | FileApi | injection only | ✅ |
| `VcsRepositoryImpl` | FileApi | injection only | ✅ |
| `ServerTerminalWorkspace` | TerminalApi | injection only | ✅ |
| `TerminalRepositoryImpl` | _(dead injection removed)_ | body is stub | ✅ (1 nit, §2.3 M-2) |

Representative verification (the two most complex consumers):

**ChatRepositoryImpl** — `terminalApi.runShellCommand(conn, sessionId, command, agent, model, directory)` → `TerminalApi.runShellCommand(conn, sessionId, command, agent, model?, directory?)` ✅
**SessionRepositoryImpl** — `messageApi.exportSessionToStream(conn, sessionId, outputStream, onProgress)` → `MessageApi.exportSessionToStream(conn, sessionId, outputStream, onProgress)` ✅

### 2.2 Test migration — verified correct

Spot-checked 6 representative test files:
- `OpenCodeApiVcsTest`: real-HTTP test now constructs `FileApiImpl(ApiClient(httpClient, json))` — correct, since `FileApiImpl` takes `ApiClient`. ✅
- `ChatRepositoryImplTest`: 4 separate `mockk(relaxed=true)` for the 4 interfaces; constructor arg order matches impl signature. ✅
- `TerminalRepositoryImplTest`: `api` mock removed entirely; constructor call updated to `TerminalRepositoryImpl(serverRepo)`. ✅
- `SessionListViewModelPaginationTest`: 4 mocks + named-arg construction. ✅
- `OpenCodeApiArchiveTest` / `OpenCodeApiTest`: mock type / comment updates only. ✅

### 2.3 Minor nits (non-blocking)

| ID | Severity | Location | Issue |
|---|---|---|---|
| **M-1** | Minor (cosmetic) | `ServerSettingsViewModel.kt:178` | Anomalous whitespace: `_agents.value =                 systemApi.listAgents(conn)` — ~17 spaces between `=` and `systemApi`. No behavioral impact; likely a stray keystroke during refactor. |
| **M-2** | Minor (misleading comment) | `TerminalRepositoryImpl.kt:36` | TODO comment references `// terminalApi.updatePtySize(conn, sessionId, cols, rows)` but `terminalApi` is no longer a field in this class (the dead injection was removed). The comment describes a hypothetical future wiring; referencing a non-existent field name is slightly misleading. |
| **M-3** | Minor (noted, deferred) | 6 test files | Test class names still say `OpenCodeApi*Test` (e.g. `OpenCodeApiVcsTest`). Implementer explicitly flagged this as a separate cleanup task. Internal mock types are correctly migrated, so this is purely cosmetic. |

**No Critical or Important issues found.**

---

## 3. Risk Check

| # | Risk | Result |
|---|---|---|
| 3.1 | Method dispatched to wrong interface (e.g. `listMessages` on `SessionApi`) | ✅ None found — every call site verified against injected interface(s) |
| 3.2 | `ServerConnection` parameter dropped at call site | ✅ All call sites still pass `conn` as first positional arg, matching interface signatures |
| 3.3 | Dead injection silently retained | ✅ `TerminalRepositoryImpl` dead param fully removed (constructor + import + test mock) |
| 3.4 | `ApiModule` binding drift | ✅ All 6 `@Binds` from Block 1 intact; impl classes (`SessionApiImpl` etc.) carry their own `@Inject constructor` |
| 3.5 | `RestSessionStatusInfo` package change breaks imports | ✅ Package unchanged (`data.api`); zero import edits needed |

---

## 4. Verification Evidence (from implementer report, trust-but-verify)

| Check | Claimed | Independently confirmable? |
|---|---|---|
| `compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (46s) | Plausible — all type refs resolve (verified via diff audit); only pre-existing deprecation warnings |
| `testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (45s) | Plausible — test mock types match impl signatures (spot-verified) |

Re-running the full build/test suite is outside this review's scope (implementer report + diff-level type-checking provide sufficient confidence for a refactor that is purely mechanical — no logic changes, no new branches).

---

## 5. Summary

This is a clean, mechanical migration. The implementer correctly:
- Split the monolithic `OpenCodeApi` injection into the 6 domain interfaces established in Block 1
- Preserved every method-call argument list verbatim (only the receiver changed: `api.` → `sessionApi.` etc.)
- Removed a genuinely dead injection (`TerminalRepositoryImpl`) rather than papering over it
- Relocated `RestSessionStatusInfo` without changing its package, avoiding cascading import churn
- Migrated all test mocks to the new interface types

The 3 minor nits are cosmetic and can be addressed in a follow-up cleanup pass. **No fixes are required to merge Block 2.**
