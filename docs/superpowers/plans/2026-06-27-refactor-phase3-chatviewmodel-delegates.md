# Phase 3: ChatViewModel Delegate 化

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> **Analysis:** See `docs/superpowers/reports/phase3-chatviewmodel-analysis.md` for full delegate definitions, state clusters, and dependency matrix.

**Goal:** Split ChatViewModel (2451L God Class) into 6-8 delegates using composition-over-inheritance. VM becomes thin orchestrator with facade properties (UI zero-change).

**Architecture:** Each delegate is a `@Inject` class holding its own StateFlow + methods. VM injects delegates, exposes facade properties/methods. Cross-domain writes go through intent methods, never direct private state access.

## Global Constraints

- **UI zero-change**: VM keeps facade properties (`val xxx get() = delegate.xxx`) — 81 UI files must not be touched
- **sessionIdFlow spine**: SessionLifecycleDelegate owns `_sessionId`, exposes `StateFlow<String>`
- **Coordinator methods stay in VM**: `init`, `sendParts`, `sendMessage`, `revertMessage`, `abortSession`
- Each delegate is `@Inject constructor(...)` + `@Singleton` — Hilt auto-provides
- Compile after each task: `.\gradlew :app:compileDevDebugKotlin` (120s)
- Tests after each task: `.\gradlew :app:testDevDebugUnitTest --rerun` (180s)
- Gradle daemon disabled; `.\gradlew --stop` if stuck

## Delegate Pattern (apply to all)

```kotlin
@Singleton
class XxxDelegate @Inject constructor(
    private val someUseCase: SomeUseCase,
    // ... other deps
) {
    // Private state (moved from ChatViewModel)
    private val _xxx = MutableStateFlow(...)
    val xxx: StateFlow<...> = _xxx.asStateFlow()

    // Methods (moved from ChatViewModel, signatures unchanged)
    fun doSomething() { ... }
}
```

VM facade:
```kotlin
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val xxxDelegate: XxxDelegate,
    // ...
) : ViewModel() {
    // Facade — UI calls this, no change needed
    val xxx: StateFlow<...> get() = xxxDelegate.xxx
    fun doSomething() = xxxDelegate.doSomething()
}
```

---

## Tasks (risk-ascending order)

### Task 1: TerminalDelegate + ScrollPositionDelegate
Extract terminal (11 methods, already delegated to terminalWorkspace) + scroll (3 methods, 5 mutableStateOf). Zero cross-domain deps.

### Task 2: DraftInputDelegate
Draft/attachment/file-search/restore (D cluster). Depends on sessionId (passed from VM).

### Task 3: SessionLifecycleDelegate ★ spine
Session identity/directory/lazy-create (C cluster). Exposes sessionIdFlow. Highest impact — 6 combines depend on it.

### Task 4: ModelConfigDelegate
Provider/Agent/Model/Command (A cluster). Depends on sessionIdFlow from Task 3.

### Task 5: MessageDataDelegate
Message SSE/loading/pagination/send-state (B cluster). Depends on sessionIdFlow. Encapsulates send lifecycle methods.

### Task 6: SessionActionsDelegate
28 stateless REST operations (G cluster). Depends on A/B/C delegate refs.

### Task 7: VM orchestrator cleanup
Slim init/sendParts/revertMessage/abortSession. Facade property audit. Remove dead code.

---

## Self-Review

- [ ] ChatViewModel.kt < 600 lines (from 2451)
- [ ] Each delegate < 400 lines
- [ ] `rg "viewModel\." app/src/main/kotlin/.../ui/` — UI files unchanged (same property/method names)
- [ ] All 975+ tests pass
- [ ] App launches, chat works (send message, receive SSE, terminal, file viewer)
