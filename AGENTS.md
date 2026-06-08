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
  theme/              Design token system
    Alpha.kt          5-level semantic alpha tokens (FAINT/MUTED/MEDIUM/HIGH/AMOLED)
    Color.kt          Brand color constants + semantic DiffAdded/DiffRemoved
    Motion.kt         Duration tokens + easing constants
    Shape.kt          AppShapes (Material) + ShapeTokens (component-level)
    Theme.kt          4 color schemes (light/dark/dynamic/amoled), AppTheme composable
    Type.kt           Typography configuration
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

### Release & Publish

**发布流程分为两种场景：**

#### 场景 A：Master 分支发正式 Release

从主仓库 `master` 发布正式版：

```bash
# 1. 确认在 master，工作区干净
git checkout master && git pull origin master

# 2. Bump 版本号（修改 app/build.gradle.kts 中的 versionCode 和 versionName）
#    versionCode += 1, versionName = "2.0.0-beta.XX"

# 3. 提交版本号变更（可单独 commit 或与代码合并一起）
git add app/build.gradle.kts && git commit -m "chore: bump version to v2.0.0-beta.XX"

# 4. 构建 Release APK（beta flavor 正式版，使用 release keystore）
.\gradlew --stop
.\gradlew :app:assembleBetaRelease

# 5. 推送到 remote
git push origin master

# 6. 打 tag（格式: v2.0.0-beta.XX 或 v2.0.0-dev）
git tag -a "v2.0.0-beta.XX" -m "v2.0.0-beta.XX — 简要说明"
git push origin "v2.0.0-beta.XX"

# 7. 创建 GitHub Release 并上传 APK
gh release create "v2.0.0-beta.XX" \
  "app/build/outputs/apk/beta/release/app-beta-release.apk" \
  --title "v2.0.0-beta.XX — 标题" \
  --notes "详细 changelog"
```

#### 场景 B：Worktree 分支推送预览版

从 worktree 的非 master 分支推送预览/草稿版（不覆盖正式 Release）：

```bash
# 1. Worktree 分支 build（dev flavor，debug 签名可用）
.\gradlew :app:assembleDevRelease

# 2. 用 gh 创建 Draft Release（非正式）
gh release create "v2.0.0-beta.XX-dev" \
  "app/build/outputs/apk/dev/release/app-dev-release.apk" \
  --title "v2.0.0-beta.XX-dev — worktree预览" \
  --notes "预览版，仅供测试" \
  --draft

# 或者直接用 `--prerelease` 标记为预发布
gh release create "v2.0.0-beta.XX-dev" \
  ... \
  --prerelease
```

**规则速查：**

| 场景 | 分支 | Flavor | Tag | Release |
|------|------|--------|-----|---------|
| 正式版 | master | `assembleBetaRelease` | `v2.0.0-beta.XX` | `gh release create` + APK |
| 预览版 | worktree | `assembleDevRelease` | `v2.0.0-beta.XX-dev` | `--draft` 或 `--prerelease` |

- `gh` CLI 不走代理，直接用直连（不加 `HTTP_PROXY`）
- APK 路径：beta → `app/build/outputs/apk/beta/release/app-beta-release.apk`；dev → `app/build/outputs/apk/dev/release/app-dev-release.apk`
- **完整步骤顺序**：bump version → commit → build → push master → tag → push tag → `gh release create`（附 APK）

### Gradle Timeout
执行 Gradle 命令时必须设置合理的超时时间，禁止无超时裸跑：
- **Kotlin 编译检查**（`compileDevDebugKotlin`）: 120 秒
- **单元测试**（`testDevDebugUnitTest`）: 180 秒
- **完整构建**（`assembleDevRelease` 等）: 300 秒
- **依赖解析/首次构建**: 可延长至 600 秒

**Windows Daemon 卡住问题：**
Gradle Daemon 在 Windows 上间歇性不释放 stdout 管道，导致命令行工具看到 `BUILD SUCCESSFUL` 输出后永不返回。已在 `gradle.properties` 中设置 `org.gradle.daemon=false` 禁用 daemon。如遇到卡住，额外执行 `.\gradlew --stop` 清理残留 daemon。

**注意：** `--no-daemon` 和 `org.gradle.daemon=false` 效果相同——都会 fork 一次性进程，构建结束自动销毁。

### Verification & Testing
**See `docs/verification-requirements.md` for the full 4-dimension verification framework.

**Must load `verification-before-completion` skill** before any completion claim. The Iron Law: no completion claims without fresh verification evidence.

Test infrastructure:
- Unit tests: JUnit 4 + MockK 1.14.9 + Turbine 1.2.1 + kotlinx-coroutines-test
- Instrumented tests: `HiltTestRunner` + `createComposeRule()` (in `androidTest/`)
- E2E flows: Maestro YAML in `maestro/` directory
- `isReturnDefaultValues = true` — mocks return default values instead of throwing. This can mask bugs where mock data silently returns null/0/false
- Each Layer requires: compile ✅ + unit tests ✅ + enhanced tests ✅ + Maestro flows (UI) + androidTest (UI)

environment:
- opencode username: opencode
- opencode password: save as environment variables ${OPENCODE_SERVER_PASSWORD}

### Ktor Engine
Uses **OkHttp engine** explicitly for correct SSE streaming. Do not switch to other engines.**

### Material 3 First
- **优先使用 Material 3 原生组件和原生样式**。能用 `LinearProgressIndicator`、`CircularProgressIndicator`、`IconButton` 等原生组件解决的，不要自定义 Canvas 绘制。
- **优先使用 Material 3 原生配色和动效**。颜色用 `MaterialTheme.colorScheme` 中的语义色，间距用 `dp` 常量或 Material token，不要硬编码。
- **仅在原生组件无法满足需求时才自定义**（如特殊动画效果），自定义组件也应尽量复用 Material token 系统。
- **禁止引入额外 UI 依赖库**（如 Accompanist），除非有充分的理由并经过讨论。

### Theme Token System
- **Alpha tokens** (Alpha.kt): 5 levels only — FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92). Use these instead of hardcoded `.copy(alpha = Xf)`.
- **Shape tokens** (Shape.kt): `AppShapes` for MaterialTheme, `ShapeTokens` object for component-level direct reference.
- **Motion tokens** (Motion.kt): semantic duration constants (BREATH_CYCLE, PULSE_CYCLE, TERMINAL). Use instead of hardcoded `AnimationSpec` durations.
- **Dark theme**: trust Material3 `darkColorScheme()` defaults. Only override 6 brand-differentiated tokens in Theme.kt.
- **Colors** (Color.kt): brand constants + semantic `DiffAdded`/`DiffRemoved`. No dead code.

## Branches & Remotes

_| Remote | URL | Role |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-remote-v2` | Fork (push access, current default) |
| upstream | `github.com:crim50n/oc-remote` | Upstream (owner: crim50n) — add manually if needed |

- `master` — stable, matches upstream
- Push: `git push origin master` / `git push origin <tag>`

## Localization

15 locales managed via `lokit.yaml`. When editing string resources, run `lokit` to sync translations.

## ProGuard_

Release builds use R8 minification. Rules preserve:
- `kotlinx.serialization` annotated classes
- Ktor coroutine internals
- Mikepenz Markdown renderer state/models (async parsing)

## Android SDK

- `compileSdk` = 36, `minSdk` = 26, `targetSdk` = 35
- Compose BOM `2026.05.01`
