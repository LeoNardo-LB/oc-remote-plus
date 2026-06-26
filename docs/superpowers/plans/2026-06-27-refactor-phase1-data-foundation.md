# Phase 1: Data Foundation — OpenCodeApi Domain Segregation + SSE Registry

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 988-line OpenCodeApi into 6 domain-segregated interfaces and convert EventDispatcher's `when` dispatch to a registry pattern — without changing any method signatures.

**Architecture:** Each domain gets an interface + impl in the same file (Kotlin style). Hilt DI binds each interface. 17 consumers migrate from injecting `OpenCodeApi` to injecting specific domain interfaces. OpenCodeApi.kt is deleted. EventDispatcher switches from hardcoded `when` to `Map<KClass, SseEventHandler>` registry.

**Tech Stack:** Kotlin, Ktor (OkHttp engine), Hilt (KSP), MockK, JUnit4

## Global Constraints

- **Method signatures must NOT change** — only file location and interface grouping
- JDK 21, Compose BOM 2026.05.01, Ktor OkHttp engine (do not switch)
- Gradle daemon disabled (`org.gradle.daemon=false`); run `.\gradlew --stop` if stuck
- ChatScreen.kt editing protocol applies if touched (it shouldn't be in this phase)
- Proxy in gradle.properties (127.0.0.1:7897) — ensure proxy is running or comment out
- Existing OpenCodeApiTest (529L) must pass after refactor (signatures unchanged)
- Kotlin compile check: `.\gradlew :app:compileDevDebugKotlin` (120s timeout)
- Full build: `.\gradlew :app:assembleDevDebug` (300s timeout)
- Unit tests: `.\gradlew :app:testDevDebugUnitTest --rerun` (180s timeout)

---

## File Structure

```
data/api/
  ApiClient.kt                    NEW — shared httpClient holder + utility extensions
  ServerConnection.kt             KEEP
  sse/SseClient.kt                KEEP
  session/SessionApi.kt           NEW — interface SessionApi + class SessionApiImpl (~22 methods)
  message/MessageApi.kt           NEW — interface MessageApi + class MessageApiImpl (~14 methods, includes Permission)
  terminal/TerminalApi.kt         NEW — interface TerminalApi + class TerminalApiImpl (~6 methods)
  provider/ProviderApi.kt         NEW — interface ProviderApi + class ProviderApiImpl (~13 methods, includes Config)
  file/FileApi.kt                 NEW — interface FileApi + class FileApiImpl (~12 methods, includes VCS)
  system/SystemApi.kt             NEW — interface SystemApi + class SystemApiImpl (~12 methods)
  OpenCodeApi.kt                  DELETE (after all consumers migrated)

data/repository/
  EventDispatcher.kt              MODIFY — when → registry
  handler/SseEventHandler.kt      KEEP (interface, may add registry support)

di/
  NetworkModule.kt                MODIFY — add @Binds for each domain interface
```

---

## Domain Grouping (locked)

Methods assigned to each domain. Every OpenCodeApi method maps to exactly one domain:

### SessionApi (22 methods)
`listSessions, getSession, getSessionRaw, createSession, deleteSession, updateSession, updateSessionFields, abortSession, getSessionDiff, listSessionChildren, getSessionTodos, listSessionStatus, fetchSessionStatus, shareSession, unshareSession, summarizeSession, revertSession, unrevertSession, forkSession, importSession, executeCommand`

### MessageApi (14 methods — Message + Permission + Question)
`listMessages, listMessagesRaw, getMessage, deleteMessage, deleteMessagePart, exportSessionToStream, promptAsync, replyToPermission, listPendingPermissions, replyToQuestion, rejectQuestion, listPendingQuestions`

### TerminalApi (6 methods)
`createPty, removePty, updatePtySize, openPtySocket, listPtyShells, runShellCommand`

### ProviderApi (13 methods — Provider + Auth + Config)
`getProviders, listProviderCatalog, getProviderAuthMethods, authorizeProviderOauth, completeProviderOauth, setProviderApiKey, removeProviderAuth, getConfig, getGlobalConfig, updateConfig, updateGlobalConfig, disposeGlobal, disposeInstance`

### FileApi (12 methods — File + VCS)
`findFiles, readFile, searchText, probeDirectory, listDirectory, findSymbols, getFileStatus, getVcs, getVcsStatus, getVcsDiff, listProjects, getCurrentProject`

### SystemApi (8 methods)
`getHealth, getServerPaths, listAgents, listCommands, listSkills, getMcpStatus, connectMcpServer, disconnectMcpServer`

> **Total: 62 unique methods** across 6 domain interfaces. Every OpenCodeApi method maps to exactly one domain.

---

## Task 1: Create ApiClient (shared httpClient holder)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/ApiClient.kt`
- Reference: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/OpenCodeApi.kt:1-30` (current httpClient injection)

**Interfaces:**
- Consumes: `HttpClient` (from Hilt NetworkModule)
- Produces: `ApiClient` class — shared by all domain impls

- [ ] **Step 1: Read current OpenCodeApi constructor to understand httpClient injection**

Run: Read `OpenCodeApi.kt` lines 1-35. Note how `httpClient` and any shared utilities (URL building, error handling) are used.

- [ ] **Step 2: Create ApiClient.kt**

```kotlin
package dev.leonardo.ocremotev2.data.api

import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared HTTP client holder for all domain API implementations.
 * Each domain ApiImpl injects this to access the configured Ktor client.
 */
@Singleton
class ApiClient @Inject constructor(
    val httpClient: HttpClient
)
```

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL (ApiClient is new, no consumers yet)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/ApiClient.kt
git commit -m "refactor(api): add ApiClient shared httpClient holder"
```

---

## Task 2: Create SessionApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/session/SessionApi.kt`
- Reference: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/OpenCodeApi.kt` — all Session methods (see grouping above)

**Interfaces:**
- Consumes: `ApiClient` (from Task 1), `ServerConnection`
- Produces: `SessionApi` interface — consumed by `SessionRepositoryImpl`, `ChatRepositoryImpl`

- [ ] **Step 1: Create the interface + impl**

Create `session/SessionApi.kt`. Define `interface SessionApi` with all 22 method signatures **copied verbatim** from OpenCodeApi (same parameter types, same return types, same `suspend`). Create `class SessionApiImpl @Inject constructor(private val apiClient: ApiClient) : SessionApi` and **move all 22 method bodies** from OpenCodeApi into SessionApiImpl.

> The method bodies are identical — only the containing class changes. Replace `httpClient` references with `apiClient.httpClient`.

Example structure (showing 2 of 22 methods — repeat for all):

```kotlin
package dev.leonardo.ocremotev2.data.api.session

import dev.leonardo.ocremotev2.data.api.ApiClient
import dev.leonardo.ocremotev2.data.api.ServerConnection
// ... all other imports from OpenCodeApi that Session methods need
import javax.inject.Inject
import javax.inject.Singleton

interface SessionApi {
    suspend fun listSessions(conn: ServerConnection, cursor: String? = null, limit: Int? = null): List<dev.leonardo.ocremotev2.data.dto.response.SessionResponse>
    suspend fun getSession(conn: ServerConnection, sessionId: String): Session
    // ... all 22 signatures copied verbatim from OpenCodeApi
}

@Singleton
class SessionApiImpl @Inject constructor(
    private val apiClient: ApiClient
) : SessionApi {

    override suspend fun listSessions(conn: ServerConnection, cursor: String? = null, limit: Int? = null): List<...> {
        // Body copied verbatim from OpenCodeApi.listSessions
        // Replace httpClient → apiClient.httpClient
    }

    override suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        // Body copied verbatim
    }

    // ... all 22 methods
}
```

- [ ] **Step 2: Compile check (OpenCodeApi still has the methods too — temporary overlap)**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL (both old and new coexist temporarily)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/session/SessionApi.kt
git commit -m "refactor(api): extract SessionApi interface + impl (22 methods)"
```

---

## Task 3: Create MessageApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/message/MessageApi.kt`
- Reference: OpenCodeApi.kt — Message + Permission + Question methods (14 total per grouping)

**Interfaces:**
- Consumes: `ApiClient`, `ServerConnection`
- Produces: `MessageApi` interface — consumed by `ChatRepositoryImpl`

- [ ] **Step 1: Create interface + impl**

Same pattern as Task 2. Define `interface MessageApi` with 14 method signatures. Create `MessageApiImpl @Inject constructor(apiClient: ApiClient)`. Move method bodies from OpenCodeApi.

Includes: `listMessages, listMessagesRaw, getMessage, deleteMessage, deleteMessagePart, exportSessionToStream, promptAsync, replyToPermission, listPendingPermissions, replyToQuestion, rejectQuestion, listPendingQuestions`

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/message/MessageApi.kt
git commit -m "refactor(api): extract MessageApi interface + impl (14 methods, Message+Permission)"
```

---

## Task 4: Create TerminalApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/terminal/TerminalApi.kt`
- Reference: OpenCodeApi.kt — PTY methods (6 total)

**Interfaces:**
- Consumes: `ApiClient`, `ServerConnection`
- Produces: `TerminalApi` — consumed by `TerminalRepositoryImpl`, `ServerTerminalRegistry`

- [ ] **Step 1: Create interface + impl**

6 methods: `createPty, removePty, updatePtySize, openPtySocket, listPtyShells, runShellCommand`

> **Note:** `openPtySocket` returns a `WebSocketSession` (Ktor). Ensure the import for `io.ktor.client.plugins.websocket.webSocketSession` is included.

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/terminal/TerminalApi.kt
git commit -m "refactor(api): extract TerminalApi interface + impl (6 methods)"
```

---

## Task 5: Create ProviderApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/provider/ProviderApi.kt`
- Reference: OpenCodeApi.kt — Provider + Auth + Config methods (13 total)

**Interfaces:**
- Consumes: `ApiClient`, `ServerConnection`
- Produces: `ProviderApi` — consumed by `ServerRepositoryImpl`, `ServerDataStore`

- [ ] **Step 1: Create interface + impl**

13 methods: `getProviders, listProviderCatalog, getProviderAuthMethods, authorizeProviderOauth, completeProviderOauth, setProviderApiKey, removeProviderAuth, getConfig, getGlobalConfig, updateConfig, updateGlobalConfig, disposeGlobal, disposeInstance`

> **Note:** `authorizeProviderOauth` and `completeProviderOauth` have complex parameter types (port, redirectUri, etc.). Copy signatures exactly.

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/provider/ProviderApi.kt
git commit -m "refactor(api): extract ProviderApi interface + impl (13 methods, Provider+Config)"
```

---

## Task 6: Create FileApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/file/FileApi.kt`
- Reference: OpenCodeApi.kt — File + VCS methods (12 total)

**Interfaces:**
- Consumes: `ApiClient`, `ServerConnection`
- Produces: `FileApi` — consumed by `FileRepositoryImpl`, `VcsRepositoryImpl`

- [ ] **Step 1: Create interface + impl**

12 methods: `findFiles, readFile, searchText, probeDirectory, listDirectory, findSymbols, getFileStatus, getVcs, getVcsStatus, getVcsDiff, listProjects, getCurrentProject`

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/file/FileApi.kt
git commit -m "refactor(api): extract FileApi interface + impl (12 methods, File+VCS)"
```

---

## Task 7: Create SystemApi (interface + impl)

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/system/SystemApi.kt`
- Reference: OpenCodeApi.kt — System methods (8 total)

**Interfaces:**
- Consumes: `ApiClient`, `ServerConnection`
- Produces: `SystemApi` — consumed by `ServerRepositoryImpl`, `AgentRepositoryImpl`, `McpRepositoryImpl`

- [ ] **Step 1: Create interface + impl**

8 methods: `getHealth, getServerPaths, listAgents, listCommands, listSkills, getMcpStatus, connectMcpServer, disconnectMcpServer`

- [ ] **Step 2: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/system/SystemApi.kt
git commit -m "refactor(api): extract SystemApi interface + impl"
```

---

## Task 8: Hilt DI Bindings

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/di/NetworkModule.kt` (or DomainModule — check which handles API bindings)
- Reference: Current OpenCodeApi binding in DI

**Interfaces:**
- Consumes: All 6 domain impls (Tasks 2-7)
- Produces: Hilt-injectable domain interfaces for consumers

- [ ] **Step 1: Read current DI module to find OpenCodeApi binding**

Run: Grep `OpenCodeApi` in `di/` directory. Determine if it's `@Provides` or `@Inject constructor` auto-provided.

- [ ] **Step 2: Add @Binds for each domain interface**

In the appropriate DI module (likely NetworkModule or a new `ApiModule`), add bindings:

```kotlin
// In NetworkModule or new ApiModule:
@Binds
@Singleton
abstract fun bindSessionApi(impl: SessionApiImpl): SessionApi

@Binds
@Singleton
abstract fun bindMessageApi(impl: MessageApiImpl): MessageApi

@Binds
@Singleton
abstract fun bindTerminalApi(impl: TerminalApiImpl): TerminalApi

@Binds
@Singleton
abstract fun bindProviderApi(impl: ProviderApiImpl): ProviderApi

@Binds
@Singleton
abstract fun bindFileApi(impl: FileApiImpl): FileApi

@Binds
@Singleton
abstract fun bindSystemApi(impl: SystemApiImpl): SystemApi
```

> If the module is an `object` not `abstract class`, convert to `abstract class` or create a separate `@Module @InstallIn(SingletonComponent::class) abstract class ApiModule`.

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/di/
git commit -m "refactor(di): bind 6 domain API interfaces in Hilt"
```

---

## Task 9: Migrate Consumers (17 files)

**Files:**
- Modify: All 17 files that reference `OpenCodeApi` (see consumer list below)
- Delete: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/OpenCodeApi.kt`

**Consumer list** (grep `OpenCodeApi` to confirm):
`AgentRepositoryImpl, ChatRepositoryImpl, FileRepositoryImpl, McpRepositoryImpl, ServerDataStore, ServerRepositoryImpl, ServerTerminalRegistry, SessionRepositoryImpl, TerminalRepositoryImpl, VcsRepositoryImpl, NetworkModule, SessionRepository(iface), SelectModelUseCase, SseConnectionManager, ServerTerminalWorkspace, ServerSettingsViewModel, SessionListViewModel`

**Interfaces:**
- Consumes: Domain interfaces (Tasks 2-7) + DI bindings (Task 8)
- Produces: Zero remaining `OpenCodeApi` references

- [ ] **Step 1: Map each consumer to its needed domain interface(s)**

For each consumer, determine which OpenCodeApi methods it calls, then map to the domain interface(s) that contain those methods. Create a migration table:

| Consumer | Injects (old) | Injects (new) |
|----------|--------------|---------------|
| SessionRepositoryImpl | OpenCodeApi | SessionApi |
| ChatRepositoryImpl | OpenCodeApi | SessionApi + MessageApi |
| TerminalRepositoryImpl | OpenCodeApi | TerminalApi |
| ServerRepositoryImpl | OpenCodeApi | ProviderApi + SystemApi |
| FileRepositoryImpl | OpenCodeApi | FileApi |
| ... | ... | ... |

Run: For each consumer file, grep `openCodeApi\.` to see which methods are called, then map to domains.

- [ ] **Step 2: Migrate Repository impls (batch)**

For each Repository impl, change constructor injection:

```kotlin
// BEFORE:
class ChatRepositoryImpl @Inject constructor(
    private val openCodeApi: OpenCodeApi,
    ...
)

// AFTER:
class ChatRepositoryImpl @Inject constructor(
    private val sessionApi: SessionApi,
    private val messageApi: MessageApi,
    ...
)
```

Then find-and-replace all `openCodeApi.` calls within that file to the appropriate domain variable (`sessionApi.` or `messageApi.`).

Process all Repository impls: AgentRepositoryImpl, ChatRepositoryImpl, FileRepositoryImpl, McpRepositoryImpl, ServerRepositoryImpl, SessionRepositoryImpl, TerminalRepositoryImpl, VcsRepositoryImpl.

- [ ] **Step 3: Migrate non-Repository consumers**

- `ServerDataStore` — check which methods it calls, inject corresponding interface
- `ServerTerminalRegistry` — likely TerminalApi
- `SelectModelUseCase` — likely ProviderApi
- `SseConnectionManager` — check methods
- `ServerTerminalWorkspace` — likely TerminalApi
- `ServerSettingsViewModel` — likely ProviderApi
- `SessionListViewModel` — likely SessionApi
- `SessionRepository` (interface) — if it references OpenCodeApi types, update imports
- `NetworkModule` — if it has @Provides for OpenCodeApi, remove it (replaced by Task 8 bindings)

- [ ] **Step 4: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL — zero `OpenCodeApi` references remain

If errors: grep for remaining `OpenCodeApi` references:
```bash
rg "OpenCodeApi" app/src/main/kotlin/ --type kotlin
```
Fix each remaining reference.

- [ ] **Step 5: Delete OpenCodeApi.kt**

```bash
git rm app/src/main/kotlin/dev/leonardo/ocremotev2/data/api/OpenCodeApi.kt
```

- [ ] **Step 6: Compile + unit test verification**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All tests pass (OpenCodeApiTest should pass since signatures unchanged — update its imports if needed)

> **If OpenCodeApiTest fails:** It likely injects `OpenCodeApi` directly. Update it to inject the relevant domain interface(s) instead. The test assertions remain identical.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(api): migrate 17 consumers to domain interfaces, delete OpenCodeApi

- All consumers now inject domain-specific interfaces (SessionApi, MessageApi, etc.)
- OpenCodeApi.kt deleted (988L → 6 domain files)
- Method signatures unchanged — existing tests pass"
```

---

## Task 10: SSE EventDispatcher — Registry Pattern

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/EventDispatcher.kt`
- Reference: `handler/SseEventHandler.kt` (interface)

**Interfaces:**
- Consumes: `SseEventHandler` implementations (SessionEventHandler, MessageEventHandler, etc.)
- Produces: `EventDispatcher` with registry-based dispatch (OCP compliant)

- [ ] **Step 1: Read current EventDispatcher dispatch logic**

Read `EventDispatcher.kt` fully. Identify:
1. The `extractSessionId` when-block (L144-189) — this stays as-is (it's a pure function, not dispatch)
2. How events are routed to handlers (the actual dispatch mechanism)
3. Special-case handling (SessionDeleted L92, CommandExecuted L105, MessageUpdated+User L110)

- [ ] **Step 2: Identify the dispatch pattern to replace**

Determine if EventDispatcher currently:
- (a) Calls each handler's `handle()` for every event (handler filters internally), or
- (b) Routes events to specific handlers via when/is-checks

This determines the registry design:
- If (a): registry maps `KClass<out SseEvent>` → `SseEventHandler`
- If (b): registry maps event type → list of handlers

- [ ] **Step 3: Design the registry**

Based on Step 2, implement a registry that allows new event types to be handled by registering a handler, without modifying the dispatcher:

```kotlin
class EventDispatcher @Inject constructor(
    private val handlers: Map<KClass<out SseEvent>, SseEventHandler>
    // Hilt multi-binding or manual map construction
) {
    fun dispatch(event: SseEvent) {
        val sessionId = extractSessionId(event)
        // Special cases remain as explicit checks (they're cross-cutting, not domain-specific)
        handleSpecialCases(event)

        // Registry dispatch: find handler(s) for this event type
        handlers[event::class]?.handle(event, sessionId)
    }
}
```

> **Hilt multi-binding approach:** Use `@IntoMap` with `@ClassKey(SseEvent.SessionStatus::class)` annotations on each handler's @Provides. This way, adding a new handler = adding a new @Provides entry, no dispatcher change.

- [ ] **Step 4: Implement the registry**

Refactor EventDispatcher to use the registry. Keep `extractSessionId` as a private function (unchanged). Keep special-case handling (SessionDeleted/CommandExecuted/MessageUpdated+User) as explicit early-return checks before registry dispatch.

- [ ] **Step 5: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Unit test verification**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All SSE-related tests pass (EventDispatcherIntegrationTest 667L, SessionNextEventTest 758L, etc.)

> **If tests fail:** The registry dispatch must produce identical behavior to the old when-block. Check handler ordering and event routing.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(sse): EventDispatcher when-block → registry pattern (OCP)

New event types now handled by registering a handler, no dispatcher change needed."
```

---

## Task 10b: Split MessageEventHandler by sub-event (spec 3.1.6)

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/MessageEventHandler.kt` (496L)
- Create (if splitting to separate files): `handler/MessagePartHandler.kt`, `handler/MessageUpdatedHandler.kt`, `handler/MessageRemovedHandler.kt`

**Interfaces:**
- Consumes: Registry pattern from Task 10
- Produces: Fine-grained message event handlers, each registered independently

- [ ] **Step 1: Read MessageEventHandler.kt to identify sub-event branches**

Read the full file. Identify how it internally dispatches between `MessagePartUpdated`, `MessagePartDelta`, `MessagePartRemoved`, `MessageUpdated`, `MessageRemoved`. Note shared logic vs per-sub-event logic.

- [ ] **Step 2: Split into focused handlers**

Extract each sub-event's handling into a separate handler class that implements `SseEventHandler`:

- `MessagePartUpdatedHandler` — handles `MessagePartUpdated` + `MessagePartDelta` + `MessagePartRemoved` (Part lifecycle, tightly coupled)
- `MessageUpdatedHandler` — handles `MessageUpdated`
- `MessageRemovedHandler` — handles `MessageRemoved`

Each handler is `@Inject constructor` + `@Singleton`, registered in the EventDispatcher's handler map via the registry from Task 10.

> Shared utilities (e.g., part list reconstruction) extract to a `MessageHandlerHelper` or keep as internal functions shared via the same package.

- [ ] **Step 3: Delete old MessageEventHandler.kt (or keep as deprecated facade if other code references it)**

```bash
# Only if no remaining references:
git rm app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/handler/MessageEventHandler.kt
```

- [ ] **Step 4: Compile + test**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All SSE tests pass (SessionNextEventTest 758L, EventDispatcherIntegrationTest 667L)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(sse): split MessageEventHandler into 3 focused sub-event handlers

- MessagePartUpdatedHandler (Part lifecycle)
- MessageUpdatedHandler
- MessageRemovedHandler
Each registered independently in EventDispatcher registry."
```

---

## Task 11: Full Build + Integration Verification

**Files:** None (verification only)

- [ ] **Step 1: Full debug build**

Run: `.\gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL (300s timeout)

- [ ] **Step 2: All unit tests**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: All pass (180s timeout)

- [ ] **Step 3: Install to emulator and smoke test**

Install: Use replicant `adb-app install` with `app/build/outputs/apk/dev/debug/app-dev-debug.apk`

Smoke test via Maestro or manual:
- Connect to server (10.0.2.2:4096)
- Send a message (exercises SessionApi + MessageApi)
- Open session list (exercises SessionApi)
- Open terminal (exercises TerminalApi)
- Open settings (exercises ProviderApi)

- [ ] **Step 4: Commit phase 1 completion marker**

```bash
git commit --allow-empty -m "milestone: Phase 1 Data Foundation complete

- OpenCodeApi (988L) → 6 domain interfaces
- 17 consumers migrated to domain interfaces
- EventDispatcher when-block → registry pattern
- All tests pass, smoke test verified"
```

---

## Self-Review Checklist

After completing all tasks, verify:

- [ ] `rg "OpenCodeApi" app/src/main/kotlin/` returns zero results (fully deleted)
- [ ] `rg "class OpenCodeApi" app/src/` returns zero results
- [ ] Each domain API file is < 250 lines
- [ ] `compileDevDebugKotlin` passes
- [ ] `testDevDebugUnitTest --rerun` passes
- [ ] `assembleDevDebug` passes
- [ ] No method signatures changed (diff confirms only file moves + interface wrapping)
- [ ] EventDispatcher dispatch uses registry, not `when` (only `extractSessionId` when remains)
- [ ] MessageEventHandler split into 3 focused sub-event handlers
