# Task 4+5 (merged): staleness guard + triggerRestValidation + syncFromRest

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt`
- Modify: `app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt`

**Interfaces:**
- Consumes: Task 3's SessionStateService skeleton (placeholder triggerRestValidation, the 3 callbacks, _fsmStates, applyTransition), `SessionRepository.fetchSessionStatuses(serverId, directory): Result<Map<String, RestSessionStatusInfo>>`, `Project(worktree=...)`, `RestSessionStatusInfo(type=...)`
- Produces: real `triggerRestValidation` (absence=idle closed loop), staleness guard (init coroutine), `syncFromRest(projects): SyncResult`

## Step 1: Replace triggerRestValidation placeholder + add staleness guard (Task 4)

In `SessionStateService.kt`:

**1a. Add constants (top-level, near HISTORY_MAX):**
```kotlin
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L
```

**1b. Add init + staleness fields + guard (inside the class, after the `@Volatile currentServerId` line):**
```kotlin
private var stalenessJob: kotlinx.coroutines.Job? = null

init { startStalenessGuard() }

private fun startStalenessGuard() {
    stalenessJob?.cancel()
    stalenessJob = appScope.launch {
        while (isActive) {
            delay(STALENESS_CHECK_INTERVAL_MS)
            checkStaleness()
        }
    }
}

private fun checkStaleness() {
    val now = System.currentTimeMillis()
    _fsmStates.value.forEach { (sessionId, state) ->
        if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
            Log.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
            triggerRestValidation(sessionId)
        }
        if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
            Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
            triggerRestValidation(sessionId)
        }
    }
}
```
Needs imports: `kotlinx.coroutines.Job`, `kotlinx.coroutines.isActive`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.launch`. (`isActive` is `kotlin.coroutines.coroutineContext[job]?.isActive` equivalent — the import `kotlinx.coroutines.isActive` is the coroutineScope extension; inside `appScope.launch { while(isActive) }` it resolves correctly.)

**1c. Replace the placeholder `triggerRestValidation` with real impl:**
```kotlin
internal fun triggerRestValidation(sessionId: String) {
    val sid = currentServerId ?: return
    val directory = directoryResolver.resolve(sessionId)
    appScope.launch {
        try {
            val result = sessionRepoProvider.get().fetchSessionStatuses(sid, directory)
            result.onSuccess { statuses ->
                val serverStatus = statuses[sessionId]
                if (serverStatus != null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                    onRestValidation(sessionId, serverStatus)
                } else if (directory != null) {
                    // Server deletes idle sessions from its status map — absence means idle.
                    // Only trust this when we queried the session's own directory.
                    if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                    onRestValidation(sessionId, SessionStatus.Idle)
                }
                // directory == null + absent -> skip (avoid false idle on unknown instance)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
        }
    }
}
```

## Step 2: Add syncFromRest (Task 5)

In `SessionStateService.kt`, add (top-level data class + private helper + suspend fun):

```kotlin
data class SyncResult(val totalSessions: Int, val busyCount: Int)
```
```kotlin
// inside class:
private fun toStatus(info: RestSessionStatusInfo): SessionStatus = when (info.type) {
    "busy" -> SessionStatus.Busy
    "retry" -> SessionStatus.Retry(attempt = info.attempt ?: 0, message = info.message ?: "", next = info.next ?: 0L)
    else -> SessionStatus.Idle
}

suspend fun syncFromRest(projects: List<Project>): SyncResult {
    val sid = currentServerId ?: return SyncResult(0, 0)
    val aggregated = mutableMapOf<String, SessionStatus>()
    val dirs: List<String?> = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
    for (dir in dirs) {
        sessionRepoProvider.get().fetchSessionStatuses(sid, dir)
            .onSuccess { aggregated += it.mapValues { toStatus(it.value) } }
    }
    // Absence semantics: local non-Idle absent from REST
    for ((sessionId, state) in _fsmStates.value) {
        if (state.core !is SessionStatus.Idle && sessionId !in aggregated) {
            aggregated[sessionId] = if (incompleteChecker.hasIncomplete(sessionId)) state.core  // protect (SSE may still stream)
                                     else SessionStatus.Idle                                       // absent = idle
        }
    }
    for ((sessionId, status) in aggregated) applyTransition(sessionId, FsmEvent.RestValidation(status))
    return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
}
```
Needs imports: `dev.leonardo.ocremotev2.data.api.RestSessionStatusInfo`, `dev.leonardo.ocremotev2.domain.model.Project`.

## Step 3: Add tests (ADAPT to Task 3's UnconfinedTestDispatcher pattern)

The existing `SessionStateServiceTest.kt` (from Task 3) uses `UnconfinedTestDispatcher` + `@After cancel` fixture (see that file). Mirror that pattern for these new tests. Use `Provider { mockk(relaxed = true) }` (NOT `providerOf`). Use `setServerId("svr1")` (exists from Task 3). `RestSessionStatusInfo(type="busy")` and `Project(worktree="D:/p")` are valid (all fields have defaults).

```kotlin
@Test fun `triggerRestValidation absence with known directory marks Idle`() = runTest {
    // Use the SAME fixture as existing tests (UnconfinedTestDispatcher + advanceUntilIdle)
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())  // absent
    val service = newServiceWith(fakeRepo)  // helper matching Task 3 fixture
    service.setServerId("svr1")
    service.directoryResolver = DirectoryResolver { "D:/proj" }
    service.onClientSendParts("s1")
    service.triggerRestValidation("s1")
    advanceUntilIdle()
    assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
}

@Test fun `triggerRestValidation absence with null directory stays Busy`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val service = newServiceWith(fakeRepo)
    service.setServerId("svr1")
    service.directoryResolver = DirectoryResolver { null }  // unknown dir
    service.onClientSendParts("s1")
    service.triggerRestValidation("s1")
    advanceUntilIdle()
    assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // no false idle
}

@Test fun `syncFromRest aggregates multiple directories`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses("svr1", "D:/projA") } returns Result.success(mapOf("s1" to RestSessionStatusInfo(type = "busy")))
    every { fakeRepo.fetchSessionStatuses("svr1", "D:/projB") } returns Result.success(mapOf("s2" to RestSessionStatusInfo(type = "busy")))
    val service = newServiceWith(fakeRepo)
    service.setServerId("svr1")
    val result = service.syncFromRest(listOf(Project(worktree = "D:/projA"), Project(worktree = "D:/projB")))
    assertEquals(2, result.totalSessions)
    assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
    assertEquals(SessionStatus.Busy, service.statusFlow.value["s2"])
}

@Test fun `syncFromRest marks absent non-idle session Idle when no incomplete`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val service = newServiceWith(fakeRepo)
    service.setServerId("svr1")
    service.onClientSendParts("s1")  // local Busy
    service.syncFromRest(listOf(Project(worktree = "D:/p")))
    assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
}

@Test fun `syncFromRest protects absent session with incomplete messages`() = runTest {
    val fakeRepo = mockk<SessionRepository>(relaxed = true)
    every { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
    val service = newServiceWith(fakeRepo)
    service.setServerId("svr1")
    service.directoryResolver = DirectoryResolver { "D:/p" }
    service.incompleteChecker = IncompleteAssistantChecker { true }
    service.onClientSendParts("s1")
    service.syncFromRest(listOf(Project(worktree = "D:/p")))
    assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // protected
}
```

Extract a `newServiceWith(repo)` helper that mirrors the existing Task 3 fixture (UnconfinedTestDispatcher + Provider + return service). Keep `@After` cancel of the dispatcher scope.

## Step 4: Run tests

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionStateServiceTest*"` (180000ms)
Expected: all PASS (existing 5 + new 5 = 10)

## Step 5: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateService.kt app/src/test/kotlin/dev/leonardo/ocremotev2/data/repository/SessionStateServiceTest.kt
git commit -m "feat(state): staleness guard + triggerRestValidation absence=idle + syncFromRest unify recovery"
```
