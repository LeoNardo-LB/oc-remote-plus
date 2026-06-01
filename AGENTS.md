# AGENTS.md — OC Remote v2

Unofficial OpenCode Android client. Jetpack Compose + Kotlin + Hilt + Ktor.

## Build & Run

```bash
# Full build (dev flavor, release)
.\gradlew :app:assembleDevRelease

# All flavors: dev + beta, debug + release
.\gradlew :app:assembleDevDebug :app:assembleBetaRelease

# Unit tests (force rerun, avoid UP-TO-DATE skip)
.\gradlew :app:testDevDebugUnitTest --rerun

# Kotlin compile check (fast feedback loop)
.\gradlew :app:compileDevDebugKotlin
```

**JDK 21 required** — `build.gradle.kts` sets `jvmToolchain(21)` and `JavaVersion.VERSION_21`. Local builds also set `org.gradle.java.home` in `gradle.properties`.

**Proxy warning**: `gradle.properties` hardcodes `127.0.0.1:7897` HTTP proxy. Build fails if proxy is unreachable. Comment out the 4 `systemProp.*` lines when building without proxy.

## Product Flavors

| Flavor | applicationId | Purpose |
|--------|---------------|---------|
| `dev` | `dev.minios.ocremote.dev` | Development build, coexists with beta |
| `beta` | `dev.minios.ocremote` | Production release |

Always specify the flavor in gradle tasks: `assembleDevRelease`, `assembleBetaDebug`, etc. The CI workflow only builds `assembleRelease` (no flavor — needs updating).

## Architecture

Clean Architecture, 3 layers. **Dependency direction: UI → Domain ← Data.**

```
domain/          Pure Kotlin, no Android deps
  model/         13 data classes (SseEvent, Message, Part, Session, AppSettings, etc.)
  repository/    4 interfaces (Chat, Session, Server, Settings)
  usecase/       21 UseCases — ViewModel calls these, not API directly

data/            Android-aware implementations
  api/           OpenCodeApi.kt (Ktor HTTP), SseClient.kt, ServerConnection.kt
  dto/           API data transfer objects (request/ response/ common/)
  mapper/        DTO ↔ Domain Model converters
  repository/    Impl classes + EventDispatcher + EventHandler strategy pattern
    handler/     5 event handlers (Session, Message, Permission, Question, Misc)

service/         Android foreground service
  OpenCodeConnectionService.kt  Service lifecycle + WakeLock
  SseConnectionManager.kt       Connection/reconnect with exponential backoff
  AppNotificationManager.kt     Notification channels and event notifications

ui/
  screens/chat/      ChatScreen (~1100 lines) + 7 sub-packages
    components/      Chat UI components
    dialog/          Image preview, markdown preview dialogs
    input/           Message input bar
    markdown/        Markdown rendering
    terminal/        PTY terminal view over WebSocket
    tools/           Tool-call expandable cards
    util/            Chat-specific utilities
  screens/home/      HomeScreen + server cards + local runtime
  screens/sessions/  SessionListScreen + components
  screens/settings/  SettingsScreen + 9 picker dialogs
  screens/server/    Server settings/providers/model filter
  screens/about/     About screen
  screens/webview/   WebView fallback (OAuth, HTML errors)
  navigation/        NavGraph.kt + 10 type-safe Route objects in routes/
  components/        Shared components (PulsingDotsIndicator, ProviderIcon)

di/                Hilt modules (NetworkModule, DomainModule)
```

**Key patterns**:
- ViewModels delegate to UseCases. UseCases currently shell-delegate to OpenCodeApi.
- Repository implementations bridge EventDispatcher (state) + API (network).
- DI uses **KSP** (not kapt) for Hilt annotation processing.
- Terminal uses WebSocket transport for PTY streams; SSE for events.

## Critical Constraints

### ChatScreen.kt Editing Protocol
See `docs/chatscreen-editing-protocol.md`. Rules:
- Never edit in parallel across agents
- Always Read before Edit
- Run `compileDevDebugKotlin` after each edit
- Commit after each successful compilation
- On failure: `git checkout -- <file>`, re-read, retry

### Signing
- Release keystore lives at `app/keystore/release.jks` with password in `signing.properties`
- When `signing.properties` exists → release builds use release keystore
- When absent → release builds fall back to debug signing (line 67 of `build.gradle.kts`)
- CI uses GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD`)

### Version Management
- `versionName` and `versionCode` in `app/build.gradle.kts`
- CI extracts version by grepping `versionName = "..."` — **do not change the format**
- Tags follow `v2.0.0-beta.XX` (beta) or `v2.0.0-dev` (dev) pattern

### Test Gotchas
- `isReturnDefaultValues = true` — mocks return default values instead of throwing. This can mask bugs where mock data silently returns null/0/false
- Test runner: JUnit 4 + MockK 1.14.9 + Turbine 1.2.1 + coroutines-test
- No instrumented/UI tests in the project — unit tests only (~35 test files)

### Ktor Engine
Uses **OkHttp engine** explicitly for correct SSE streaming. Do not switch to other engines.

## Branches & Remotes

| Remote | URL | Role |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-remote-v2` | Fork (push access, current default) |
| upstream | `github.com:crim50n/oc-remote` | Upstream (owner: crim50n) — add manually if needed |

- `master` — stable, matches upstream
- Push: `git push origin master` / `git push origin <tag>`

## Localization

15 locales managed via `lokit.yaml`. When editing string resources, run `lokit` to sync translations.

## ProGuard

Release builds use R8 minification. Rules preserve:
- `kotlinx.serialization` annotated classes
- Ktor coroutine internals
- Mikepenz Markdown renderer state/models (async parsing)

## Android SDK

- `compileSdk` = 36, `minSdk` = 26, `targetSdk` = 35
- Compose BOM `2026.05.01`
