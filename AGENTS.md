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

三 flavor 体系，三个包可同时安装共存：

| Flavor | applicationId | 应用名 | 用途 |
|--------|---------------|--------|------|
| `dev` | `dev.leonardo.ocremoteplus.dev` | OC Remote Dev | 开发预览（worktree 构建） |
| `beta` | `dev.leonardo.ocremoteplus.beta` | OC Remote Beta | 公开测试版 |
| `stable` | `dev.leonardo.ocremoteplus` | OC Remote | 正式发布 |

Always specify the flavor in gradle tasks: `assembleDevRelease`, `assembleBetaRelease`, `assembleStableRelease`, etc.

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
- **SessionStateService is the single source of truth for session status & streaming activity** (idle/busy/retry + Waiting/Streaming/ToolCalling). All UI reads `statusFlow`/`activityFlow`; all status writes flow through its pure-function FSM (`SessionStateFSM`) with an exhaustive transition matrix + self-driven staleness/REST recovery loop. Do NOT reintroduce per-handler status state — `SessionStatusManager` and `SessionEventHandler._sessionStatuses` were removed for this reason. See `docs/research/session-status-sync-investigation.md` for the redesign rationale.

## OpenCode Server API Reference

完整接口文档见 [`docs/opencode-api-reference.md`](docs/opencode-api-reference.md)。

涵盖 62 个 REST/WebSocket 端点 + 52 种 SSE 事件类型，包括：
- Session / Message / Permission / Question 的 CRUD 与操作接口
- Provider / Auth / Config 配置接口
- PTY 终端（WebSocket）、File / Find 文件操作接口
- SSE 事件体系（含 22 种 `session.next.*` 细粒度事件）
- 所有数据模型的完整 JSON Schema
- Token / Context Usage 的语义说明和推荐计算方式

**开发新功能或调试接口问题时，务必先查阅此文档。**

## Critical Constraints

### ChatScreen.kt Editing Protocol
See `docs/chatscreen-editing-protocol.md`. Rules:
- Never edit in parallel across agents
- Always Read before Edit
- Run `compileDevDebugKotlin` after each edit
- Commit after each successful compilation
- On failure: `git checkout -- <file>`, re-read, retry

### Path Handling (Cross-Platform Remote Paths)

Remote file paths can use `/` or `\` depending on server OS. **Always use `PathUtils`** (`util/PathUtils.kt`):

| Operation | ✅ Use | ❌ Don't |
|-----------|--------|---------|
| Filename | `PathUtils.fileName(path)` | `substringAfterLast('/')`, `File(path).name` |
| Parent dir | `PathUtils.parentDir(path)` | `substringBeforeLast('/')` |
| Relative path | `PathUtils.relativePath(path, prefix)` | manual `removePrefix` |

JDK APIs (`File.name`, `Path.of`) only recognize `/` on Android — `\` paths from Windows servers break.
### Signing
- Release keystore lives at `app/keystore/release.jks` with password in `signing.properties`
- When `signing.properties` exists → release builds use release keystore
- When absent → release builds fall back to debug signing (line 67 of `build.gradle.kts`)
- CI uses GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD`)
- Debug-signed APKs are installable but cannot overwrite a release-signed installation (different signatures)

### Version Management

遵循 [Semantic Versioning 2.0.0](https://semver.org/) 规范，适配 Android 双 flavor 场景。

#### 版本号格式

```
MAJOR.MINOR.PATCH[-LABEL.NUMBER]
```

| 字段 | 含义 | 递进条件 |
|------|------|---------|
| MAJOR | 大版本 | 不兼容的架构变更、完整重写、品牌重塑 |
| MINOR | 功能版本 | 新功能、新屏幕、新 API 对接（向下兼容） |
| PATCH | 修复版本 | Bug 修复、性能优化、UI 调整（向下兼容） |
| LABEL | 预发布标签 | `beta`（公开测试）或 `dev`（开发预览） |
| NUMBER | 预发布序号 | 同一版本的第 N 次预发布，从 1 开始 |

#### 版本号示例

```
1.0.0              ← 正式稳定版
1.0.1-beta.1       ← 1.0.1 的第一个 beta 测试版
1.0.1-beta.2       ← 1.0.1 的第二个 beta（修复测试反馈）
1.0.1              ← 1.0.1 正式版（beta 结束后发布）
1.1.0-beta.1       ← 1.1.0 新功能 beta
1.1.0-dev.3        ← 1.1.0 的第三个开发预览（worktree 构建）
```

#### 单一真相源

- **`version.properties`** at project root（唯一来源）:
  ```properties
  VERSION_CODE=10
  VERSION_NAME=1.0.1
  ```
- `VERSION_CODE`：整数，**永远只增不减**，每次构建 +1。Android 用此判断更新顺序。
- `VERSION_NAME`：显示字符串，遵循上述 SemVer 格式。
- `app/build.gradle.kts` 从 `version.properties` 读取 — 禁止在 build.gradle.kts 中硬编码版本号。
- CI 通过 grep `version.properties` 提取版本 — **不要改变文件格式**。

#### Git Tag 格式

Tag = `v` + VERSION_NAME：
- `v1.0.0` — 正式版
- `v1.0.1-beta.1` — beta 预发布
- `v1.1.0-dev.3` — dev 预览

#### 发版规则速查

| 类型 | 分支 | Flavor | 版本号示例 | Tag | GitHub Release |
|------|------|--------|-----------|-----|----------------|
| 正式版 | master | `assembleStableRelease` | `1.0.1` | `v1.0.1` | `gh release create`（正式） |
| Beta | master | `assembleBetaRelease` | `1.0.1-beta.1` | `v1.0.1-beta.1` | `--prerelease` |
| Dev | worktree | `assembleDevRelease` | `1.0.1-dev.1` | `v1.0.1-dev.1` | `--prerelease` |

- **dev flavor** (`dev.leonardo.ocremoteplus.dev`)：开发预览，独立 applicationId，可与正式版共存。
- **beta flavor** (`dev.leonardo.ocremoteplus.beta`)：公开测试版，独立 applicationId，可与正式版共存。
- **stable flavor** (`dev.leonardo.ocremoteplus`)：正式包名，覆盖安装。
- **只发一个包**：每次发版只创建一个 GitHub Release，不重复发多个。发新版前**先删除旧版 Release 和 Tag**（`gh release delete <old> --yes && git push origin --delete <old>`），确保 Releases 页面只保留最新版本。
- **默认发预发布版**：除非用户明确说明"正式发版"或"发 stable"，否则一律发 beta 或 dev 预发布版（`--prerelease`）。
- `gh` CLI 不走代理，直接用直连（不加 `HTTP_PROXY`）。
- APK 路径：
  - stable → `app/build/outputs/apk/stable/release/app-stable-release.apk`
  - beta → `app/build/outputs/apk/beta/release/app-beta-release.apk`
  - dev → `app/build/outputs/apk/dev/release/app-dev-release.apk`。

#### 完整发版步骤

**步骤顺序（严禁颠倒 bump 和 build）：**

```
1. bump version → 修改 version.properties
2. commit → git commit -m "chore: bump version to vX.Y.Z"
3. build → .\gradlew --stop && .\gradlew :app:assembleBetaRelease
4. push → git push origin master
5. tag → git tag -a "vX.Y.Z" -m "vX.Y.Z — 简要说明"
6. push tag → git push origin "vX.Y.Z"
7. release → gh release create "vX.Y.Z" "APK路径" --prerelease(可选) --title --notes
```

**严禁在 `version.properties` 修改前执行 `assemble*`**，否则 APK 内嵌的版本号与 tag/release 名称不一致。

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
- opencode server port: 4096
- opencode username: opencode
- opencode password: save as environment variables ${OPENCODE_SERVER_PASSWORD}
- emulator host access: use `10.0.2.2` to reach the host machine from Android emulator

### SSE Scroll Stability

The SSE → UI pipeline is: **48ms token batching** → **height compensation** → **render**. Violating any of these reintroduces flicker, chunky output, or viewport jump-to-bottom:

- **`Markdown()` must use `rememberMarkdownState(content, retainState=true)`** — stateless `Markdown(content=...)` re-parses every recomposition → height oscillation → flicker.
- **`scheduleFlush()` must NOT cancel an in-flight timer** — cancelling on every token starves flushes when rate > 20/s → chunky burst output.
- **`layout{}` compensation applies to streaming message ONLY** (`if (isStreamingMsg)`) — applying to all assistant messages exposes completed messages to unstable measurement.
- **`LaunchedEffect` for autoScroll/shouldCompensate MUST key on BOTH `isScrollInProgress` AND `isAtBottom`** — `isAtBottom` as a key is the self-healing mechanism that resets `shouldCompensate=false` / `autoScrollEnabled=true` when the user returns to the bottom via non-drag means (fling inertia, SSE content push). Keying on `isScrollInProgress` ALONE leaves these flags stuck stale → viewport jitter every SSE token. This is the beta.360-verified behavior. **Do NOT remove `isAtBottom` from the key.** See `docs/research/sse-scroll-stability-iron-laws.md` for the full regression history.

### Ktor Engine
Uses **OkHttp engine** explicitly for correct SSE streaming. Do not switch to other engines.**

### Material 3 First
- **优先使用 Material 3 原生组件和原生样式**。能用 `LinearProgressIndicator`、`CircularProgressIndicator`、`IconButton` 等原生组件解决的，不要自定义 Canvas 绘制。
- **优先使用 Material 3 原生配色和动效**。颜色用 `MaterialTheme.colorScheme` 中的语义色，间距用 `dp` 常量或 Material token，不要硬编码。
- **仅在原生组件无法满足需求时才自定义**（如特殊动画效果），自定义组件也应尽量复用 Material token 系统。
- **禁止引入额外 UI 依赖库**（如 Accompanist），除非有充分的理由并经过讨论。

### Theme Token System
- **Alpha tokens** (Alpha.kt): 7 semantic constants — SELECTED(0.12) / DIFF_BG(0.10) / FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92). Use these instead of hardcoded `.copy(alpha = Xf)`.
- **Spacing tokens** (Spacing.kt): 6 grid-based constants — XS(4) / SM(8) / MD(12) / LG(16) / XL(24) / XXL(32). Use `SpacingTokens.LG.dp` instead of hardcoded `16.dp` for standard spacing.
- **Shape tokens** (Shape.kt): `AppShapes` for MaterialTheme, `ShapeTokens` object for component-level direct reference.
- **Motion tokens** (Motion.kt): semantic duration constants (BREATH_CYCLE, PULSE_CYCLE, TERMINAL). Use instead of hardcoded `AnimationSpec` durations.
- **Dark theme**: trust Material3 `darkColorScheme()` defaults. Only override 6 brand-differentiated tokens in Theme.kt.
- **Colors** (Color.kt): brand constants + semantic `DiffAdded`/`DiffRemoved`. No dead code.

## Branches & Remotes

_| Remote | URL | Role |
|--------|-----|------|
| `origin` | `github.com:LeoNardo-LB/oc-remote-plus` | Fork (push access, current default) |
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
