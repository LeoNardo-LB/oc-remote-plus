# Phase 1 Block 2 — Consumer Migration & OpenCodeApi Deletion

**Status:** DONE  
**Commit:** `4a571ba5`  
**Date:** 2026-06-27

## Summary

Migrated all 14 real consumers from injecting `OpenCodeApi` to the 6 domain interfaces (SessionApi, MessageApi, TerminalApi, ProviderApi, FileApi, SystemApi). Deleted `OpenCodeApi.kt` (1257 lines removed). Compilation + all unit tests pass.

## Consumer Migration Table

| Consumer | Injected Domain Interface(s) |
|---|---|
| **VcsRepositoryImpl** | FileApi |
| **FileRepositoryImpl** | FileApi |
| **ServerRepositoryImpl** | ProviderApi |
| **ServerDataStore** | SystemApi |
| **TerminalRepositoryImpl** | _(dead injection removed)_ |
| **ServerTerminalRegistry** | TerminalApi |
| **ServerTerminalWorkspace** | TerminalApi |
| **AgentRepositoryImpl** | FileApi + SystemApi |
| **McpRepositoryImpl** | SystemApi + ProviderApi |
| **SessionRepositoryImpl** | SessionApi + MessageApi |
| **SseConnectionManager** | SessionApi + MessageApi + FileApi |
| **ServerSettingsViewModel** | ProviderApi + SystemApi |
| **ChatRepositoryImpl** | MessageApi + SessionApi + TerminalApi + ProviderApi |
| **SessionListViewModel** | SessionApi + FileApi + SystemApi + TerminalApi |

### Comment-only updates (no code dependency)

| File | Change |
|---|---|
| ApiClient.kt | KDoc comment: "OpenCodeApi.directoryHeader" → "monolithic API class's directoryHeader" |
| NetworkModule.kt | Comment: "per-request in OpenCodeApi" → "per-request in domain Api implementations" |
| SessionRepository.kt (iface) | Comment: "delegates to OpenCodeApi" → "delegates to SessionApi" |
| SelectModelUseCase.kt | Comment: "instead of direct OpenCodeApi access" → "instead of direct API access" |

## RestSessionStatusInfo Migration

Moved the top-level `data class RestSessionStatusInfo` from `OpenCodeApi.kt:1089` to a new standalone file:
- **New location:** `data/api/RestSessionStatusInfo.kt`
- **Package:** `dev.leonardo.ocremotev2.data.api` (unchanged — zero import changes needed anywhere)

## Test File Updates

### data/api/ tests (signature lock + 1 real HTTP)

| Test File | Change |
|---|---|
| OpenCodeApiTest.kt | DTO serialization only — comments updated (no type reference) |
| OpenCodeApiArchiveTest.kt | mock `OpenCodeApi` → mock `SessionApi` |
| OpenCodeApiImportTest.kt | mock `OpenCodeApi` → mock `SessionApi` |
| OpenCodeApiMessageDeleteTest.kt | mock `OpenCodeApi` → mock `MessageApi` |
| OpenCodeApiSearchPaginationTest.kt | mock `OpenCodeApi` → mock `SessionApi` |
| OpenCodeApiVcsTest.kt | `OpenCodeApi(httpClient, json)` → `FileApiImpl(ApiClient(httpClient, json))` |

### data/repository/ tests

| Test File | Change |
|---|---|
| VcsRepositoryImplTest.kt | mock `OpenCodeApi` → mock `FileApi` |
| FileRepositoryImplTest.kt | mock `OpenCodeApi` → mock `FileApi` |
| TerminalRepositoryImplTest.kt | Removed `api` mock (dead injection removed from impl) |
| AgentRepositoryImplTest.kt | mock `OpenCodeApi` → mock `SystemApi` + `FileApi` |
| SessionRepositoryImplTest.kt | mock `OpenCodeApi` → mock `SessionApi` + `MessageApi` |
| ChatRepositoryImplTest.kt | mock `OpenCodeApi` → mock 4 interfaces |

### ui/ tests

| Test File | Change |
|---|---|
| SessionListViewModelPaginationTest.kt | mock `OpenCodeApi` → mock 4 interfaces |
| SessionListViewModelSearchTest.kt | mock `OpenCodeApi` → mock 4 interfaces |

### Unchanged (string literals / comments only)

- FileMapperTest.kt — "OpenCodeApi.kt" as test fixture data
- OffsetConverterTest.kt — comment referencing sample
- WorkspaceViewModelTest.kt — FileNode("OpenCodeApi.kt") fixture
- VcsRepositoryImplTest.kt — "src/api/OpenCodeApi.kt" fixture data in DTO assertions (harmless)

## Verification Results

| Check | Result |
|---|---|
| `compileDevDebugKotlin` | ✅ BUILD SUCCESSFUL (46s) — only pre-existing deprecation warnings |
| `testDevDebugUnitTest --rerun` | ✅ BUILD SUCCESSFUL (45s) — all tests pass |

## Concerns

1. **TerminalRepositoryImpl dead injection**: The `api: OpenCodeApi` parameter was injected but never actually called (all usages in TODO comments). Removed the parameter entirely. The `TerminalRepository` interface is a stub — real PTY lifecycle is managed by `ServerTerminalWorkspace` (which uses `TerminalApi` directly).

2. **Test class names**: 6 test files still have `OpenCodeApi*` in their class names (e.g., `OpenCodeApiVcsTest`). These are cosmetic — renaming them to match domain interfaces is a separate cleanup task. The internal mock types are correctly migrated.

3. **String fixture data**: Several tests use "OpenCodeApi.kt" as file path strings in test fixtures. These are harmless test data, not code references. Left unchanged to minimize diff.
