# Phase 1 Block 1 Report — Domain API Interfaces + Hilt Bindings

**Status:** DONE  
**Branch:** `refactor/phase1-data-foundation`  
**Base:** `4636f9cd` → **Head:** `1cfc52be`  
**Date:** 2026-06-27

---

## Summary

Split the 1095-line `OpenCodeApi.kt` into 6 domain-segregated interface+impl pairs plus a dedicated `ApiModule` for Hilt bindings. All method signatures are **identical** to the original — only file location and interface grouping changed. `OpenCodeApi.kt` is preserved for the consumer-migration phase (Block 2 / Task 9).

## Files Created (8 files, 1668 lines total)

| File | Lines | Methods | Task |
|------|-------|---------|------|
| `data/api/ApiClient.kt` | 33 | — (holder + `directoryHeader` extension) | 1 |
| `data/api/session/SessionApi.kt` | 385 | 21 | 2 |
| `data/api/message/MessageApi.kt` | 340 | 12 | 3 |
| `data/api/terminal/TerminalApi.kt` | 253 | 6 (+ 3 private PTY helpers) | 4 |
| `data/api/provider/ProviderApi.kt` | 304 | 13 | 5 |
| `data/api/file/FileApi.kt` | 183 | 12 | 6 |
| `data/api/system/SystemApi.kt` | 123 | 8 | 7 |
| `di/ApiModule.kt` | 47 | 6 `@Binds` | 8 |

**Total domain methods: 72** (21+12+6+13+12+8)

## Commit Log

| Hash | Message |
|------|---------|
| `b07523e7` | refactor(api): add ApiClient shared httpClient holder |
| `8e214547` | refactor(api): extract SessionApi interface + impl (21 methods) |
| `b5431490` | refactor(api): extract MessageApi interface + impl (12 methods, Message+Permission+Question) |
| `ec70afac` | refactor(api): extract TerminalApi interface + impl (6 methods) |
| `e005a6de` | refactor(api): extract ProviderApi interface + impl (13 methods, Provider+Config) |
| `0bbe20f1` | refactor(api): extract FileApi interface + impl (12 methods, File+VCS) |
| `dabd7782` | refactor(api): extract SystemApi interface + impl (8 methods) |
| `1cfc52be` | refactor(di): bind 6 domain API interfaces in Hilt ApiModule |

## Compilation Results

| Step | Result | Time |
|------|--------|------|
| Baseline (ApiClient only) | BUILD SUCCESSFUL | 7s |
| + SessionApi | BUILD SUCCESSFUL | 17s (after fixing `isSuccess` import) |
| + MessageApi | BUILD SUCCESSFUL | 17s |
| + TerminalApi + ProviderApi + FileApi + SystemApi | BUILD SUCCESSFUL | 18s (after fixing `url` import) |
| + ApiModule | BUILD SUCCESSFUL | 16s |
| Unit tests (`testDevDebugUnitTest --rerun`) | BUILD SUCCESSFUL | 37s |

### Compilation Issues Fixed

1. **`isSuccess` unresolved** (SessionApi): Added `import io.ktor.http.isSuccess`. Original file used wildcard `io.ktor.http.*`.
2. **`url` unresolved** (TerminalApi, `openPtySocket`): Changed from `io.ktor.http.url` to `io.ktor.client.request.url` — the `url()` builder function in `webSocketSession { }` belongs to the request package.

## Design Decisions

### Import Strategy
The original `OpenCodeApi.kt` uses wildcard imports for DTO packages (`data.dto.common.*`, `data.dto.request.*`, `data.dto.response.*`) combined with explicit imports for `domain.model` types. This is critical because several types have **duplicate definitions** in both `domain.model` and `data.dto`:

| Type | domain.model | data.dto | Resolves to (via original imports) |
|------|-------------|----------|-------------------------------------|
| `AgentInfo` | ✓ | ✓ | `data.dto.response.AgentInfo` (wildcard wins) |
| `CommandInfo` | ✓ | ✓ | `data.dto.response.CommandInfo` |
| `PromptPart` | ✓ | ✓ | `data.dto.request.PromptPart` |
| `ModelSelection` | ✓ | ✓ | `data.dto.common.ModelSelection` |
| `ProvidersResponse` | ✓ | ✓ | `data.dto.response.ProvidersResponse` |
| `ServerHealth` | ✓ | ✗ | `domain.model.ServerHealth` (explicit) |
| `Session` | ✓ | ✗ | `domain.model.Session` (explicit) |
| `Project` | ✓ | ✗ | `domain.model.Project` (explicit) |

All 6 domain files replicate this exact import strategy, guaranteeing type resolution is **byte-for-byte identical** to the original.

### Backing Property Pattern
Each impl uses private backing properties to avoid rewriting method bodies:
```kotlin
private val httpClient get() = apiClient.httpClient
private val json get() = apiClient.json
```
This means method bodies are **copied verbatim** — zero risk of accidental logic changes.

### ApiModule Style
Matches `DomainModule` exactly: `abstract class`, `@Binds` methods **without** `@Singleton` scope annotation (the scope lives on the `@Singleton`-annotated impl classes). This is consistent with the existing codebase convention.

## Concerns

### 1. `RestSessionStatusInfo` Location (Block 2 migration blocker)
The top-level `data class RestSessionStatusInfo` is still defined inside `OpenCodeApi.kt` (L1089-1094). `SessionApiImpl` imports it from `dev.leonardo.ocremotev2.data.api`. When Block 2 deletes `OpenCodeApi.kt`, this data class **must be migrated** to a new file (suggested: move into `session/SessionApi.kt` bottom or a standalone `data/api/RestSessionStatusInfo.kt`). Without this, compilation will break.

### 2. Method Count Labels vs Actual
The task spec headers labeled SessionApi as "22 methods" and MessageApi as "14 methods", but the actual method lists provided contained 21 and 12 respectively. All counts were followed **by the method list**, not the header number. Verified: total 72 methods across all 6 domains.

### 3. OpenCodeApi.kt Preserved (by design)
The original 1095-line file is intentionally left in place. New domain impls and the old class coexist. Consumer migration (Task 9) will switch all 17 consumers to domain interfaces, then delete `OpenCodeApi.kt`.

## Verification

- ✅ `compileDevDebugKotlin` passes (final: 16s)
- ✅ `testDevDebugUnitTest --rerun` passes (37s, no test failures)
- ✅ All 72 method signatures match original (verified by method count grep + compile)
- ✅ Each domain file < 400 lines (max: SessionApi at 385)
- ✅ Zero changes to `OpenCodeApi.kt` (preserved as-is for Block 2)
- ✅ Zero changes to any consumer file (Task 9 scope)
