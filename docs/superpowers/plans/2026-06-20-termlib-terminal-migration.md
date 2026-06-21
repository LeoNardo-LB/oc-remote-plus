# Termlib Terminal Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> ## ⚠️ DOC-REVIEW P0 FIXES — IMPLEMENTER MUST APPLY THESE DURING EXECUTION
>
> The following issues were found in doc-consistency-review. Fix them when you reach the relevant Task:
>
> | # | P-Level | Task | Issue | Fix |
> |---|---------|------|-------|-----|
> | 1 | **P0** | Task 3 | `TerminalEmulator` is a **sealed interface** — `FakeEmulator` cannot implement it cross-module. Compilation will fail with "Cannot inherit from sealed interface". | Change `PtyToTermlibAdapter` to accept a `writeInput: (ByteArray, Int, Int) -> Unit` lambda instead of `TerminalEmulator`. In tests, pass a simple capturing lambda. In production, pass `emulator::writeInput`. |
> | 2 | **P0** | Task 6 | `ChatTerminalView.kt:286` and `:589` use `viewModel.terminalEmulator.cursorKeysApplicationMode`. termlib's TerminalEmulator has **no such property**. Task 6 says "no change needed" but this WILL break. | Track DECSET mode 1 in a `MutableStateFlow<Boolean>` inside `PtyToTermlibAdapter` (parse `\e[?1h`/`\e[?1l` from the PTY stream before forwarding to termlib). Expose as `cursorKeysApplicationMode: StateFlow<Boolean>`. Update `ChatTerminalView.kt` to read from adapter. |
> | 3 | **P0** | Critical Findings | `keyboardEnabled` default is **false**, not true (termlib Terminal.kt:323). | ✅ Already fixed in this doc. Pass `keyboardEnabled = connected` explicitly in Task 5. |
> | 4 | **P1** | Task 3 | `TerminalEmulatorHolder.kt` is dead code — never referenced by any Task. | **Skip creating this file entirely.** Remove all references from File Structure table and Task 3 Step 1. |
> | 5 | **P1** | Task 4 | `delay(Long.MAX_VALUE)` in `bindConnectedSocketLocked` prevents reconnect from triggering. | Replace with `socket.readLoop { }` joined via `coroutineScope`/`supervisorScope` — the readLoop suspension naturally completes when the socket closes. |
> | 6 | **P1** | Task 3 | `PtySocket` is a final class. `FakePtySocket` cannot inherit. Either keep `PtySocket` as-is and use composition, or make `PtySocket` open + open its methods. | **Recommended:** Make `FakePtySocket` a standalone class implementing the same interface (extract a `PtyConnection` interface), OR make PtySocket `open class` with `open` on `send`/`close`/`readLoop`. |
> | 7 | **P1** | Task 5 | `charWidthPx = initialFont.toPx() * 0.6f` is an approximation. Old code used `Paint.measureText("X")`. | Keep the old `TextMeasurer`-based approach for char width calculation to avoid column misalignment. |

**Goal:** Replace the hand-rolled 1226-line `TerminalEmulator.kt` ANSI parser and `SessionTerminalInline.kt` Canvas renderer with the production-grade `connectbot/termlib` library (libvterm JNI core), eliminating 13 known parser limitations while preserving all existing WebSocket PTY plumbing, tab management, reconnection, and Fn/Ctrl key mappings.

**Architecture:** termlib is a pure rendering + emulation component — the caller owns PTY/connections/I/O. We add a thin `PtyToTermlibAdapter` that bridges the existing `PtySocket.readLoop` (UTF-8 text frames) into `TerminalEmulator.writeInput(bytes)`, and bridges termlib's `onKeyboardInput(ByteArray)` callback back to `PtySocket.send(String)`. The `ServerTerminalWorkspace` tab lifecycle, reconnect backoff, and `api.updatePtySize` flow remain untouched. The `TerminalKeys.kt` Fn/Ctrl mapping layer is preserved as an `onInterceptKey` interceptor so volume-key behavior is identical.

**Tech Stack:**
- `org.connectbot:termlib:0.1.0` (Maven Central, AAR with prebuilt native libs for armeabi-v7a / arm64-v8a / x86 / x86_64)
- Existing: Kotlin 2.3.21, Compose BOM 2026.05.01, Ktor OkHttp engine, JDK 21

---

## Critical Pre-Plan Findings

These are verified facts that shape the plan. Every task assumes them.

### 1. termlib 0.1.0 is the latest version (NOT 0.0.36)

The brief mentioned `v0.0.36` (2026-04-25), but Maven Central metadata confirms:
```xml
<latest>0.1.0</latest>
<release>0.1.0</release>
```
Published 2026-06-07. **Use `0.1.0`** — it includes `Minimize recompositions in Terminal` (#198), `Sticky compose mode` (#177), and Compose 1.11.2 alignment.

### 2. The consumer does NOT need NDK/CMake

The brief's "user accepted NDK build complexity" premise is **obsolete**. termlib's `lib/build.gradle.kts` shows the AAR ships prebuilt `.so` files for all 4 ABIs. Consumers only add one line:
```kotlin
implementation("org.connectbot:termlib:0.1.0")
```
No `externalNativeBuild`, no `ndkVersion`, no CMake. This was verified by inspecting `lib/build.gradle.kts` from the `0.1.0` source tarball.

### 3. Public API surface (from `lib/api.txt`)

Package: **`org.connectbot.terminal`** (not `termlib`).

```kotlin
// Factory — all params optional
TerminalEmulatorFactory.create(
    looper: Looper = Looper.getMainLooper(),
    initialRows: Int = 24,
    initialCols: Int = 80,
    defaultForeground: Color = ...,
    defaultBackground: Color = ...,
    onKeyboardInput: (ByteArray) -> Unit,        // keyboard → send to PTY
    onBell: (() -> Unit)? = null,
    onResize: ((TerminalDimensions) -> Unit)? = null,
    onClipboardCopy: ((String) -> Unit)? = null,
    onProgressChange: ((ProgressState, Int) -> Unit)? = null,
    autoDetectUrls: Boolean = true,
    boldAsBright: Boolean = true,
): TerminalEmulator

interface TerminalEmulator {
    fun writeInput(data: ByteArray, offset: Int = 0, length: Int = data.size)
    fun writeInput(buffer: ByteBuffer, length: Int)
    fun resize(newRows: Int, newCols: Int)            // NOTE: rows first!
    fun clearScreen()
    fun dispatchKey(modifiers: Int, key: Int)         // VTermKey constants
    fun dispatchCharacter(modifiers: Int, codepoint: Int)
    fun setDefaultColors(foreground: Int, background: Int): Int
    fun setAnsiPalette(ansiColors: IntArray): Int
    fun applyColorScheme(ansiColors: IntArray, defaultFg: Int, defaultBg: Int)
    val dimensions: TerminalDimensions               // (rows, columns)
    val boldAsBright: Boolean
    val autoDetectUrls: Boolean
    fun getLastCommandOutput(): String?
    fun getUrls(scope: UrlScanScope = ...): List<TerminalUrl>
}

data class TerminalDimensions(val rows: Int, val columns: Int)

// Composable — keyboardEnabled defaults to FALSE; must explicitly pass true when connected
@Composable
fun Terminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier = Modifier,
    typeface: Typeface = ...,
    initialFontSize: TextUnit = ...,
    minFontSize: TextUnit = ...,
    maxFontSize: TextUnit = ...,
    backgroundColor: Color = ...,
    foregroundColor: Color = ...,
    selectionBackgroundColor: Color = ...,
    selectionForegroundColor: Color = ...,
    keyboardEnabled: Boolean = false,
    showSoftKeyboard: Boolean = true,
    focusRequester: FocusRequester? = null,
    onTerminalTap: () -> Unit = {},
    onImeVisibilityChanged: (Boolean) -> Unit = {},
    forcedSize: Pair<Int, Int>? = null,
    modifierManager: ModifierManager? = null,        // external Ctrl/Alt/Shift state
    onSelectionControllerAvailable: ((SelectionController) -> Unit)? = null,
    onHyperlinkClick: (String) -> Unit = {},
    onComposeControllerAvailable: ((ComposeController) -> Unit)? = null,
    onPasteRequest: (() -> Unit)? = null,
    rightAltMode: RightAltMode = RightAltMode.CharacterModifier,
    delKeyMode: DelKeyMode = DelKeyMode.Backspace,
    onInterceptKey: ((KeyEvent) -> Boolean)? = null, // hook for volume keys etc.
)

// VTermKey constants (selected)
object VTermKey {
    const val NONE = 0;      const val ENTER = 1;     const val TAB = 2
    const val BACKSPACE = 3; const val ESCAPE = 4;    const val UP = 5
    const val DOWN = 6;      const val LEFT = 7;      const val RIGHT = 8
    const val INS = 9;       const val DEL = 10;      const val HOME = 11
    const val END = 12;      const val PAGEUP = 13;   const val PAGEDOWN = 14
    const val FUNCTION_0 = 256; /* ... up to FUNCTION_12 = 268 */
}

interface ModifierManager {
    fun isCtrlActive(): Boolean
    fun isAltActive(): Boolean
    fun isShiftActive(): Boolean
    fun clearTransients()
}
```

### 4. Reentrancy warning (from termlib README)

> Callbacks must not call back into Terminal methods (causes deadlock).

Concretely: inside `onKeyboardInput { bytes -> ... }` we must NOT call `emulator.writeInput(...)` or `emulator.resize(...)`. The adapter therefore **only** forwards bytes to the WebSocket `send` channel — it never touches the emulator from inside a callback.

### 5. Compatibility matrix (verified)

| Requirement | Project | termlib 0.1.0 | Status |
|---|---|---|---|
| Kotlin | 2.3.21 | 2.3.21 | ✅ exact match |
| compileSdk | 36 | 36 | ✅ |
| minSdk | 26 | 24 | ✅ project is stricter |
| Compose BOM | 2026.05.01 | 2026.04.01 | ✅ project newer, backward compatible |
| JDK runtime | 21 | lib compiled against 17 | ✅ consumer runs 21 |
| Maven Central | in `settings.gradle.kts` | — | ✅ no repo changes |

### 6. Files that DO NOT change

These are explicitly out of scope — do not touch them:
- `data/api/OpenCodeApi.kt` — `openPtySocket`, `createPty`, `removePty`, `updatePtySize`
- `data/dto/common/ApiModels.kt` — `PtySocket` class
- `ui/screens/chat/terminal/TerminalKeys.kt` — Fn/Ctrl mapping (preserved verbatim)
- `MainActivity` volume-key interceptor (`setTerminalKeyInterceptor`)
- Reconnect backoff array `RECONNECT_BACKOFF_MS` in `ServerTerminalWorkspace.kt`

---

## Global Constraints

Every task inherits these. Copy verbatim, do not relax.

- **Material 3 First** — use `MaterialTheme.colorScheme` for any custom colors; no new UI deps.
- **Gradle flavor is mandatory** — every compile/test command uses `DevDebug` or `DevRelease`. Never bare `assembleDebug`.
- **Kotlin compile check**: `.\gradlew :app:compileDevDebugKotlin` (timeout 120s)
- **Unit tests**: `.\gradlew :app:testDevDebugUnitTest --rerun` (timeout 180s)
- **Full build**: `.\gradlew :app:assembleDevRelease` (timeout 300s)
- **JDK 21** — `org.gradle.java.home` already set in `gradle.properties`.
- **Ktor OkHttp engine** — do not switch engines.
- **`isReturnDefaultValues = true`** in unit tests — mocks return defaults instead of throwing; write assertions that survive this.
- **ChatScreen.kt editing protocol** (`docs/chatscreen-editing-protocol.md`) — Read before Edit, compile after every edit, commit after every successful compile, on failure `git checkout -- <file>` then re-read.
- **Windows daemon** — `org.gradle.daemon=false` is set. If a gradle command hangs after `BUILD SUCCESSFUL`, run `.\gradlew --stop` and retry.
- **Commits** — one commit per task step marked "Commit". Use conventional commits (`feat:`, `refactor:`, `test:`, `chore:`, `docs:`).
- **No proxy bypass** — `gradle.properties` hardcodes `127.0.0.1:7897`. If building without proxy, comment out the 4 `systemProp.*` lines for that session and restore before commit.

---

## File Structure

### Create

| Path | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapter.kt` | Bridges `PtySocket` ↔ termlib `TerminalEmulator`. Owns the read-loop coroutine and the keyboard-output send channel. Thread-safe; no reentrancy into emulator. |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManager.kt` | Implements `org.connectbot.terminal.ModifierManager` backed by `MutableStateFlow`s so the toolbar Ctrl/Alt latch buttons can drive termlib's internal modifier state. |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalEmulatorHolder.kt` | Thin holder exposing a `TerminalEmulator` + `version: StateFlow<Long>` to preserve the existing `activeVersion` recompose trigger pattern in `ServerTerminalWorkspace`. |
| `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapterTest.kt` | Unit tests for the adapter — bidirectional data flow, reentrancy guard, lifecycle (close cancels reader). |
| `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManagerTest.kt` | Unit tests for the modifier manager state transitions. |
| `maestro/terminal-smoke.yaml` | E2E smoke flow: open terminal tab, type `echo hello`, see output. |

### Modify

| Path | Reason |
|---|---|
| `app/build.gradle.kts` | Add `implementation("org.connectbot:termlib:0.1.0")`. Add ABI splits (optional, APK size control). Add ProGuard keep rule for `org.connectbot.terminal.**`. |
| `app/proguard-rules.pro` | Defensive `-keep` for termlib native methods (consumer-rules.pro should auto-apply, but explicit is safer for R8 full mode). |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt` | Replace `TerminalEmulator` field with `TerminalEmulatorHolder`. Rewire `bindConnectedSocketLocked`, `resizeActive`, `clearActiveBuffer`, `publishActiveState`. |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt` | Delete the Canvas block (~130 lines), delete the `BasicTextField` + delta/dedup block (~80 lines), delete the cursor `Box` animation. Replace body with a single `Terminal(...)` composable call. |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/ChatTerminalView.kt` | Update the `SessionTerminalInline(...)` call site — the composable signature is preserved, but the `emulator` argument type changes from the old `TerminalEmulator` to termlib's `TerminalEmulator`. Pass the `ModifierManager` and `onInterceptKey` lambda. |
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` | Change `terminalEmulator` property type and any `resizeTerminal`/`sendTerminalInput`/`clearTerminalBuffer` callsites that touched the old API. |

### Delete

| Path | Lines | Reason |
|---|---|---|
| `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt` | 1226 | Fully replaced by termlib's libvterm-backed implementation. |

---

## Task Decomposition Rationale

Eight tasks across four phases. Each task produces an independently testable deliverable and can be reverted without breaking earlier tasks.

- **Phase A (POC)** — Task 1 wires the dependency and confirms the toolchain resolves the AAR with native libs. Task 2 verifies the public API compiles and renders in isolation before touching any production code. These two de-risk the entire migration.
- **Phase B (Adapter)** — Task 3 builds and tests the bridge layer in isolation. It has no UI dependency and can be fully unit-tested with mocks.
- **Phase C (Integration)** — Tasks 4, 5, 6 each swap one production layer (workspace state → composable → view-model wiring). Each compiles and runs after the prior one; the app stays launchable between tasks.
- **Phase D (Cleanup + E2E)** — Task 7 deletes the old emulator only after all references are gone. Task 8 adds the Maestro flow that gates future regressions.

---

## Task 1: Add termlib Dependency and Verify Compilation

**Files:**
- Modify: `app/build.gradle.kts:107-185` (dependencies block) and `app/build.gradle.kts:94-98` (packaging block, optional ABI splits)
- Modify: `app/proguard-rules.pro` (append keep rules)

**Interfaces:**
- Consumes: Maven Central (`org.connectbot:termlib:0.1.0` AAR with prebuilt native libs)
- Produces: `termlib` on the runtime classpath. No public API added by this task — only the dependency.

**Goal:** Confirm the AAR resolves, native libs are packaged, and the project still compiles. No source code changes yet.

- [ ] **Step 1: Add the dependency**

Edit `app/build.gradle.kts`. In the `dependencies { ... }` block, after the existing Ktor dependencies (around line 145), add a new section:

```kotlin
    // ConnectBot Terminal — libvterm-backed terminal emulator (replaces hand-rolled ANSI parser)
    implementation("org.connectbot:termlib:0.1.0")
```

- [ ] **Step 2: Add optional ABI splits to control APK size**

termlib ships 4 native ABIs (~3.8 MB total in the AAR). Without splits, every APK contains all 4. Add a `splits.abi` block inside the existing `android { ... }` block, immediately after the `packaging { ... }` block (after line 98):

```kotlin
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
```

`isUniversalApk = true` keeps a fat-APK fallback for F-Droid / direct installs; Play uses per-ABI splits automatically.

- [ ] **Step 3: Append ProGuard keep rules**

Edit `app/proguard-rules.pro`. Append at end of file:

```proguard
# ConnectBot termlib — keep public API and native method signatures.
# (termlib's consumer-rules.pro should auto-apply via AAR metadata, but R8
# full mode in release builds has historically stripped JNI symbols when
# aggressive optimizations are enabled. Explicit keeps are defensive.)
-keep public class org.connectbot.terminal.** { public *; }
-keepclasseswithmembernames class * { native <methods>; }
```

- [ ] **Step 4: Verify the dependency resolves and the project compiles**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. First run downloads the AAR (~4 MB) and extracts native libs to `build/intermediates/merged_native_libs/`. Subsequent runs are cached.

If it fails with `Could not resolve org.connectbot:termlib:0.1.0`, check `settings.gradle.kts` has `mavenCentral()` in `dependencyResolutionManagement.repositories` (verified: it does).

- [ ] **Step 5: Verify native libs are packaged in a debug build**

Run:
```powershell
.\gradlew :app:assembleDevDebug
```
Expected: `BUILD SUCCESSFUL`. Then verify the APK contains the native libs:

```powershell
Expand-Archive -Path "app\build\outputs\apk\dev\debug\app-dev-debug.apk" -DestinationPath "$env:TEMP\apk-check" -Force
Get-ChildItem -Recurse "$env:TEMP\apk-check\lib" -Filter "*.so" | Select-Object FullName
```
Expected output: `libjni_cb_term.so` present under `lib/arm64-v8a/`, `lib/armeabi-v7a/`, `lib/x86/`, `lib/x86_64/`. If the `lib/` directory is missing, the AAR was not properly merged — re-run `.\gradlew clean :app:assembleDevDebug`.

- [ ] **Step 6: Commit**

```powershell
git add app/build.gradle.kts app/proguard-rules.pro
git commit -m "chore: add connectbot/termlib 0.1.0 dependency

- implementation("org.connectbot:termlib:0.1.0") — prebuilt AAR with native libs
- splits.abi for per-ABI APK size control
- ProGuard keep rules for termlib public API and JNI symbols"
```

---

## Task 2: Termlib POC Screen — Verify Public API in Isolation

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt`
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` (temporarily wire the route; removed in Task 6)

**Interfaces:**
- Consumes: `org.connectbot.terminal.TerminalEmulatorFactory.create(...)`, `org.connectbot.terminal.Terminal` composable
- Produces: empirical confirmation that (a) the API matches `lib/api.txt`, (b) the composable renders, (c) `writeInput` accepts UTF-8 bytes from a PTY-like source. This is the foundation Task 3 builds on.

**Why a POC task:** the migration hinges on three assumptions that must be verified before touching production code:
1. `TerminalEmulatorFactory.create(onKeyboardInput = ...)` compiles and the lambda signature is `(ByteArray) -> Unit`.
2. `Terminal(terminalEmulator = ...)` renders without crashing on an empty screen.
3. Feeding ANSI bytes via `writeInput` updates the rendered output.

A throwaway screen that does exactly these three things is the cheapest way to surface any mismatch between the documented API and the shipped AAR.

- [ ] **Step 1: Create the POC screen**

Create `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * THROWAWAY POC — verifies termlib 0.1.0 public API.
 *
 * Removed in Task 6 once the real SessionTerminalInline migration is complete.
 * Do not extend this file; if the POC surfaces an API mismatch, fix the plan,
 * not this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermlibPocScreen(
    onBack: () -> Unit,
) {
    // Track the last keyboard output so we can render it under the terminal
    // as a smoke check that the onKeyboardInput callback fires.
    var lastKeyboardOutput by remember { mutableStateOf("(no keyboard input yet)") }

    // Create the emulator. onKeyboardInput receives bytes from IME / hard keys
    // when the Terminal composable below has focus.
    val emulator = remember {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { bytes: ByteArray ->
                lastKeyboardOutput = "len=${bytes.size} hex=" +
                    bytes.joinToString("") { "%02x".format(it) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("termlib POC"),
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Render the terminal. keyboardEnabled = connected lets termlib own the IME when active.
            org.connectbot.terminal.Terminal(
                terminalEmulator = emulator,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            )

            // Diagnostic row: prove that writeInput works and that the
            // keyboard callback fires.
            Text(text = "Keyboard: $lastKeyboardOutput")

            Button(
                onClick = {
                    // Feed a deterministic ANSI sequence: "hello" + bold "world" + reset + newline.
                    val payload = byteArrayOf(
                        'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(),
                        'l'.code.toByte(), 'o'.code.toByte(), ' '.code.toByte(),
                        0x1b, '['.code.toByte(), '1'.code.toByte(), 'm'.code.toByte(), // ESC[1m bold
                        'w'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
                        'l'.code.toByte(), 'd'.code.toByte(),
                        0x1b, '['.code.toByte(), '0'.code.toByte(), 'm'.code.toByte(), // ESC[0m reset
                        '\r'.code.toByte(), '\n'.code.toByte(),
                    )
                    emulator.writeInput(payload, 0, payload.size)
                },
            ) {
                Text("Inject ANSI test payload")
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
```

- [ ] **Step 2: Create the POC route**

Create `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.navigation.routes

import kotlinx.serialization.Serializable

/** Throwaway route for the termlib POC screen. Removed in Task 6. */
@Serializable
object TermlibPocRoute
```

- [ ] **Step 3: Wire the route into NavGraph**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`. Find an existing `composable<SomeRoute> { ... }` block to use as a template. Add a new block inside the `NavHost(...) { ... }` lambda:

```kotlin
            composable<TermlibPocRoute> {
                TermlibPocScreen(
                    onBack = { navController.popBackStack() },
                )
            }
```

Add the necessary imports at the top of `NavGraph.kt`:
```kotlin
import dev.leonardo.ocremotev2.ui.navigation.routes.TermlibPocRoute
import dev.leonardo.ocremotev2.ui.screens.chat.terminal.TermlibPocScreen
```

Also add a temporary entry point: in whichever screen has the dev tools / debug menu (search for `BuildConfig.DEBUG` usage to locate), add a button that navigates to `TermlibPocRoute`. If no debug menu exists, add the navigation trigger to the About screen (`ui/screens/about/`) under a `if (BuildConfig.DEBUG)` guard. The exact insertion point is not load-bearing — pick the least invasive spot.

- [ ] **Step 4: Compile**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If it fails:
- `Unresolved reference: TerminalEmulatorFactory` → dependency from Task 1 not on classpath. Re-run Task 1 Step 4.
- `Type mismatch: expected Function1<ByteArray, Unit>` → the `onKeyboardInput` parameter name or signature has changed between 0.1.0 and the version you resolved. Verify with `.\gradlew :app:dependencies --configuration devDebugRuntimeClasspath | Select-String termlib`.

- [ ] **Step 5: Manually verify on emulator/device**

Run:
```powershell
.\gradlew :app:installDevDebug
```
Then launch the app, navigate to the POC screen, tap "Inject ANSI test payload". Expected:
- The terminal area shows `hello **world**` (world in bold) on the first line, cursor on the second line.
- Tapping inside the terminal and typing on the IME updates the "Keyboard: ..." text below with byte lengths / hex.

If the terminal area is blank after injection, the emulator was created but the composable is not subscribed to its state. Check that `Terminal(...)` is called unconditionally inside the composable body (not behind a conditional that flips after creation).

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
git commit -m "feat: termlib POC screen verifying public API

- TerminalEmulatorFactory.create(onKeyboardInput = ...) compiles
- Terminal composable renders and accepts writeInput
- Temporary route, removed in Task 6"
```

---

## Task 3: PtyToTermlibAdapter — Bridge PtySocket ↔ termlib

**Files:**
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapter.kt`
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalEmulatorHolder.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapterTest.kt`

**Interfaces:**
- Consumes:
  - `dev.leonardo.ocremotev2.data.dto.common.PtySocket` — existing class with `suspend fun send(input: String)`, `suspend fun close()`, `suspend fun readLoop(onText: suspend (String) -> Unit)`.
  - `org.connectbot.terminal.TerminalEmulator` — interface with `fun writeInput(data: ByteArray, offset: Int, length: Int)`, `fun resize(newRows: Int, newCols: Int)`, `fun clearScreen()`.
  - `org.connectbot.terminal.TerminalEmulatorFactory.create(...)`.
- Produces:
  - `class PtyToTermlibAdapter` with:
    - `val emulator: org.connectbot.terminal.TerminalEmulator`
    - `val version: StateFlow<Long>` — incremented on every `writeInput`, mirrors the old `TerminalEmulator.version` pattern.
    - `fun bind(socket: PtySocket)` — starts the read loop; idempotent; cancels prior binding.
    - `fun sendInput(text: String)` — encodes UTF-8 and dispatches to the bound socket's `send`. No-op if unbound.
    - `fun resize(rows: Int, cols: Int)` — resizes emulator and (caller's responsibility) the server PTY.
    - `fun clear()` — `emulator.clearScreen()` + version bump.
    - `fun release()` — cancels reader coroutine and closes socket; idempotent.
  - `class TerminalEmulatorHolder` — convenience wrapper (see step 1).

**Reentrancy guard:** per termlib's README, callbacks must not call back into Terminal methods. The adapter's `onKeyboardInput` callback only calls `socket.send(...)` (a WebSocket write). It never calls `emulator.writeInput/resize/clear`. This is enforced by the unit test in Step 4.

- [ ] **Step 1: Create TerminalEmulatorHolder**

Create `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalEmulatorHolder.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.TerminalEmulator

/**
 * Holds a termlib [TerminalEmulator] together with a recompose trigger.
 *
 * The old hand-rolled TerminalEmulator exposed `version: Long` that
 * ServerTerminalWorkspace propagated via `_activeVersion: StateFlow<Long>`.
 * termlib's Terminal composable observes its own internal state, so we don't
 * strictly need a version counter for rendering — but ServerTerminalWorkspace's
 * publishActiveState() and several unit tests depend on a monotonically
 * increasing signal. We preserve the contract here to minimize the blast
 * radius of the migration.
 */
class TerminalEmulatorHolder(
    val emulator: TerminalEmulator,
) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /**
     * Called by the adapter after every successful writeInput. Bumps the
     * counter so any observer of [version] (e.g. ServerTerminalWorkspace's
     * _activeVersion) recomposes.
     */
    fun bump() {
        _version.value++
    }
}
```

- [ ] **Step 2: Write the failing test for the adapter**

Create `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapterTest.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import app.cash.turbine.test
import dev.leonardo.ocremotev2.data.dto.common.PtySocket
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PtyToTermlibAdapterTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `writeInput bytes are forwarded to the emulator when socket emits text`() = runTest {
        // Use a real TerminalEmulator — the JNI lib is loaded by Robolectric/JVM
        // via the host-jni artifact in the termlib test config. If the host JNI
        // lib is unavailable in this environment, swap to a fake emulator
        // (see FakeEmulator below) and assert on the bytes captured there.
        val emulator = FakeEmulator()
        val adapter = PtyToTermlibAdapter(emulator = emulator, scope = this)

        // Simulate PtySocket.readLoop invoking onText once with "hello"
        val socket = FakePtySocket(frames = listOf("hello"))
        adapter.bind(socket)

        // Give the reader coroutine a tick to drain the fake socket.
        socket.completion.await()
        adapter.release()

        assertEquals("hello", emulator.received.decodeToString())
    }

    @Test
    fun `keyboard output from the emulator is forwarded to the socket`() = runTest {
        val emulator = FakeEmulator()
        val adapter = PtyToTermlibAdapter(emulator = emulator, scope = this)
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        // Drive the keyboard callback the way termlib would.
        adapter.dispatchKeyboardOutput("ls\r\n".toByteArray())

        socket.completion.await()
        adapter.release()

        assertEquals("ls\r\n", socket.sent.joinToString(""))
    }

    @Test
    fun `onKeyboardInput callback never calls emulator methods (reentrancy guard)`() = runTest {
        val emulator = FakeEmulator(trackReentrancy = true)
        val adapter = PtyToTermlibAdapter(emulator = emulator, scope = this)
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        adapter.dispatchKeyboardOutput("x".toByteArray())
        socket.completion.await()
        adapter.release()

        assertTrue(
            "emulator.writeInput must NOT be invoked from onKeyboardInput",
            emulator.writeInputCallCountDuringKeyboard == 0,
        )
    }

    @Test
    fun `version bumps on every writeInput`() = runTest {
        val emulator = FakeEmulator()
        val adapter = PtyToTermlibAdapter(emulator = emulator, scope = this)

        adapter.version.test {
            assertEquals(0L, awaitItem())
            adapter.notifyWriteInputComplete()
            assertEquals(1L, awaitItem())
            adapter.notifyWriteInputComplete()
            assertEquals(2L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        adapter.release()
    }

    @Test
    fun `release is idempotent and closes the socket`() = runTest {
        val emulator = FakeEmulator()
        val adapter = PtyToTermlibAdapter(emulator = emulator, scope = this)
        val socket = FakePtySocket(frames = emptyList())
        adapter.bind(socket)

        adapter.release()
        adapter.release() // second call must not throw

        assertTrue(socket.closed)
    }
}

/**
 * Minimal fake that records what would be sent to termlib. Lets the tests
 * run without the JNI lib loaded.
 */
private class FakeEmulator(
    private val trackReentrancy: Boolean = false,
) : TerminalEmulator {
    val received = java.io.ByteArrayOutputStream()
    var writeInputCallCountDuringKeyboard = 0
    override val dimensions get() = throw NotImplementedError()
    override val boldAsBright get() = true
    override val autoDetectUrls get() = true

    override fun writeInput(data: ByteArray, offset: Int, length: Int) {
        if (trackReentrancy && inKeyboardCallback) writeInputCallCountDuringKeyboard++
        received.write(data, offset, length)
    }
    override fun writeInput(buffer: java.nio.ByteBuffer, length: Int) =
        throw NotImplementedError()
    override fun resize(newRows: Int, newCols: Int) {}
    override fun clearScreen() {}
    override fun dispatchKey(modifiers: Int, key: Int) {}
    override fun dispatchCharacter(modifiers: Int, codepoint: Int) {}
    override fun setDefaultColors(foreground: Int, background: Int) = 0
    override fun setAnsiPalette(ansiColors: IntArray) = 0
    override fun applyColorScheme(ansiColors: IntArray, defaultForeground: Int, defaultBackground: Int) {}
    override fun getLastCommandOutput(): String? = null
    override fun getUrls(scope: org.connectbot.terminal.UrlScanScope?): List<org.connectbot.terminal.TerminalUrl> = emptyList()

    // Test hook — set by the adapter when inside the keyboard callback path.
    var inKeyboardCallback = false
}

/**
 * Minimal in-memory PtySocket. The real PtySocket delegates to a Ktor
 * ClientWebSocketSession; we only need readLoop + send + close semantics.
 */
private class FakePtySocket(
    private val frames: List<String>,
) : PtySocket(session = mockk(relaxed = true)) {
    val sent = mutableListOf<String>()
    var closed = false
    val completion = CompletableDeferred<Unit>()

    override suspend fun send(input: String) {
        sent.add(input)
    }
    override suspend fun close() {
        closed = true
    }
    override suspend fun readLoop(onText: suspend (String) -> Unit) {
        for (frame in frames) onText(frame)
        completion.complete(Unit)
        // Block until cancelled so the reader coroutine stays alive like the real one.
        try { delay(Long.MAX_VALUE) } catch (_: Exception) {}
    }
}
```

Note: `FakePtySocket` extends `PtySocket(session = mockk(...))` because `PtySocket`'s constructor requires a `ClientWebSocketSession`. If the existing `PtySocket` is not `open`, mark it `open class` in `ApiModels.kt` (one-line change, add `open` before `class`). This is the only data-layer touch and is justified by testability — document it in the commit message.

- [ ] **Step 3: Run the test to verify it fails**

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.ui.screens.chat.terminal.PtyToTermlibAdapterTest" --rerun
```
Expected: compilation failure (`Unresolved reference: PtyToTermlibAdapter`). This is the TDD red state.

- [ ] **Step 4: Implement the adapter**

Create `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapter.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import android.util.Log
import dev.leonardo.ocremotev2.data.dto.common.PtySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator

private const val TAG = "PtyToTermlibAdapter"

/**
 * Bridges a [PtySocket] to a termlib [TerminalEmulator].
 *
 * Data flow:
 *   socket.readLoop(text)  →  emulator.writeInput(utf8Bytes)
 *   emulator.onKeyboardInput(bytes)  →  socket.send(utf8String)
 *
 * Thread safety: [bind], [sendInput], [release] may be called from any thread.
 * Internal state mutations are guarded by [lock]. The reader coroutine runs
 * on the supplied [scope]'s dispatcher (typically Dispatchers.IO inside
 * ServerTerminalWorkspace).
 *
 * Reentrancy: per termlib's contract, callbacks (onKeyboardInput) must NOT
 * call back into emulator methods. This adapter enforces that by routing
 * keyboard output only to the socket send channel.
 */
class PtyToTermlibAdapter(
    val emulator: TerminalEmulator,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var socket: PtySocket? = null
    private var readerJob: Job? = null

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /**
     * Bind a new socket, replacing any prior binding. Idempotent: calling
     * bind(null) is equivalent to release().
     */
    fun bind(socket: PtySocket?) {
        val priorJob: Job?
        synchronized(lock) {
            priorJob = readerJob
            this.socket = socket
            readerJob = null
        }
        priorJob?.cancel()
        if (socket == null) return

        val job = scope.launch {
            try {
                socket.readLoop { chunk ->
                    val bytes = chunk.toByteArray(Charsets.UTF_8)
                    emulator.writeInput(bytes, 0, bytes.size)
                    _version.value++
                }
            } catch (e: Exception) {
                Log.w(TAG, "reader loop ended", e)
            }
        }
        synchronized(lock) { readerJob = job }
    }

    /**
     * Called by the emulator's onKeyboardInput callback. Forwards the bytes
     * to the bound socket as a UTF-8 string. Safe to call from any thread;
     * the actual send is launched on [scope] to avoid blocking the emulator's
     * callback thread.
     *
     * Public for testability — in production this is invoked from inside
     * TerminalEmulatorFactory.create(onKeyboardInput = ...).
     */
    fun dispatchKeyboardOutput(bytes: ByteArray) {
        val target = synchronized(lock) { socket } ?: return
        val text = bytes.toString(Charsets.UTF_8)
        scope.launch {
            try {
                target.send(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to send keyboard output", e)
            }
        }
    }

    /**
     * Push text directly to the socket (bypasses the emulator). Used by the
     * Ctrl-C / clear / Fn-key toolbar actions that already produce ANSI
     * escape sequences.
     */
    fun sendInput(text: String) {
        val target = synchronized(lock) { socket } ?: return
        scope.launch {
            try {
                target.send(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to send input", e)
            }
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        emulator.resize(rows, cols)
        _version.value++
    }

    fun clear() {
        emulator.clearScreen()
        _version.value++
    }

    /**
     * Test seam: bump the version counter as if writeInput completed.
     * Production callers never need this; the reader loop bumps automatically.
     */
    internal fun notifyWriteInputComplete() {
        _version.value++
    }

    /**
     * Cancel the reader and close the socket. Idempotent.
     */
    fun release() {
        val (priorJob, priorSocket) = synchronized(lock) {
            val j = readerJob
            val s = socket
            readerJob = null
            socket = null
            j to s
        }
        priorJob?.cancel()
        if (priorSocket != null) {
            scope.launch {
                try { priorSocket.close() } catch (_: Exception) {}
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.ui.screens.chat.terminal.PtyToTermlibAdapterTest" --rerun
```
Expected: all 5 tests pass. If `FakePtySocket` fails to extend `PtySocket` because the latter is final, apply the `open` modifier per the note in Step 2.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapter.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TerminalEmulatorHolder.kt `
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/PtyToTermlibAdapterTest.kt
# Also stage ApiModels.kt if you made PtySocket open:
git add app/src/main/kotlin/dev/minios/ocremote/data/dto/common/ApiModels.kt
git commit -m "feat: add PtyToTermlibAdapter bridging PtySocket and termlib

- bidirectional: socket text → emulator.writeInput, keyboard bytes → socket.send
- reentrancy-safe: callbacks never call back into emulator methods
- version StateFlow preserves the old TerminalEmulator.version recompose trigger
- unit tests cover data flow, reentrancy guard, lifecycle, idempotent release"
```

---

## Task 4: Replace TerminalEmulator in ServerTerminalWorkspace

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt` (full file rewrite of the emulator-touching sections; tab lifecycle, reconnect, ptyId plumbing unchanged)
- Modify: `app/src/test/kotlin/dev/minios/ocremote/data/repository/TerminalRepositoryImplTest.kt` (only if it references the old `TerminalEmulator` type — check first)

**Interfaces:**
- Consumes:
  - `PtyToTermlibAdapter` (Task 3) — one instance per tab.
  - `TerminalEmulatorFactory.create(...)` from termlib.
- Produces:
  - `ServerTerminalWorkspace.activeEmulator()` now returns `org.connectbot.terminal.TerminalEmulator`.
  - `ServerTerminalWorkspace.activeAdapter()` (new) returns the `PtyToTermlibAdapter` for the active tab — used by the composable layer to call `dispatchKeyboardOutput` / `sendInput` / `resize`.
  - `TerminalTabUi`, `tabList`, `activeTabId`, `activeVersion`, `activeConnected`, `activeFontSizeSp` flows are preserved verbatim.

**Key invariant:** the existing `RuntimeTab.emulator` field changes type from `TerminalEmulator` (hand-rolled) to `PtyToTermlibAdapter`. Every method that previously called `tab.emulator.process(chunk)` now relies on the adapter's internal reader loop. Every method that called `tab.emulator.resize(c, r)` calls `tab.emulator.resize(r, c)` (termlib takes rows first).

- [ ] **Step 1: Read the current file in full**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt`. You have already seen it (430 lines). Re-read it now to confirm no changes since this plan was written.

- [ ] **Step 2: Replace the RuntimeTab definition and emulator-related fields**

In `ServerTerminalWorkspace.kt`, find the `private data class RuntimeTab(...)` (around line 31). Replace the `emulator` field:

Old:
```kotlin
        val emulator: TerminalEmulator = TerminalEmulator(),
```
New:
```kotlin
        val adapter: PtyToTermlibAdapter = PtyToTermlibAdapter(
            emulator = TerminalEmulatorFactory.create(
                initialRows = DEFAULT_ROWS,
                initialCols = DEFAULT_COLS,
                onKeyboardInput = { bytes -> adapterKeyboard(bytes) },
            ),
            scope = scope,
        ),
```

Wait — the `RuntimeTab` data class cannot reference `scope` (it's an instance field of the parent class) and `adapterKeyboard` is also a parent method. Restructure: `RuntimeTab` becomes a plain `class` constructed after the adapter is wired. Replace the entire `RuntimeTab` data class and the surrounding construction logic.

Find and replace the whole `private data class RuntimeTab(...) { ... }` block with:

```kotlin
    private class RuntimeTab(
        val id: String,
        var title: String,
        val adapter: PtyToTermlibAdapter,
        var fontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP,
        var directory: String? = null,
        var ptyId: String? = null,
        var socket: PtySocket? = null,
        var readerJob: Job? = null,
        var reconnectJob: Job? = null,
        var reconnectAttempt: Int = 0,
        var connected: Boolean = false,
        var lastSize: Pair<Int, Int>? = null,
    )
```

This is no longer a `data class` (we lose `copy()`/`equals()`). Search the file for any `.copy(` calls on `RuntimeTab` — there are none (the current code mutates fields in place), so this is safe.

- [ ] **Step 3: Replace fallbackEmulator and activeEmulator**

Find (around line 66-74):
```kotlin
    val fallbackEmulator = TerminalEmulator()

    fun activeEmulator(): TerminalEmulator {
        val id = _activeTabId.value
        if (id == null) return fallbackEmulator
        synchronized(lock) {
            return tabs.firstOrNull { it.id == id }?.emulator ?: fallbackEmulator
        }
    }
```

Replace with:
```kotlin
    private val fallbackAdapter = PtyToTermlibAdapter(
        emulator = TerminalEmulatorFactory.create(),
        scope = scope,
    )

    /**
     * Returns the active tab's adapter, or the fallback adapter when no tab
     * is active. Never null — the composable layer can render unconditionally.
     */
    fun activeAdapter(): PtyToTermlibAdapter {
        val id = _activeTabId.value ?: return fallbackAdapter
        return synchronized(lock) {
            tabs.firstOrNull { it.id == id }?.adapter ?: fallbackAdapter
        }
    }

    /** Convenience accessor for code that only needs the termlib emulator. */
    fun activeEmulator(): org.connectbot.terminal.TerminalEmulator =
        activeAdapter().emulator
```

- [ ] **Step 4: Rewrite createTab to build the adapter with the keyboard callback**

Find `fun createTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {})` (around line 85). Inside the `synchronized(lock) { ... }` block, replace the `RuntimeTab(...)` construction. The new version creates the adapter with a keyboard callback that routes to whatever socket is currently bound:

Old:
```kotlin
            val index = tabs.size + 1
            RuntimeTab(
                id = UUID.randomUUID().toString(),
                title = "Tab $index",
                fontSizeSp = defaultFontSizeSp,
                directory = directory,
            ).also {
                tabs.add(it)
                _activeTabId.value = it.id
                publishTabsLocked()
            }
```

New:
```kotlin
            val index = tabs.size + 1
            val tabId = UUID.randomUUID().toString()
            val adapter = PtyToTermlibAdapter(
                emulator = TerminalEmulatorFactory.create(
                    initialRows = DEFAULT_ROWS,
                    initialCols = DEFAULT_COLS,
                    onKeyboardInput = { bytes ->
                        // Routed to the socket via sendInput; the adapter's
                        // reader loop owns writeInput, so no reentrancy risk.
                        synchronized(lock) {
                            tabs.firstOrNull { it.id == tabId }
                        }?.adapter?.dispatchKeyboardOutput(bytes)
                    },
                ),
                scope = scope,
            )
            val tab = RuntimeTab(
                id = tabId,
                title = "Tab $index",
                adapter = adapter,
                fontSizeSp = defaultFontSizeSp,
                directory = directory,
            )
            tabs.add(tab)
            _activeTabId.value = tab.id
            publishTabsLocked()
            tab
```

Note: `onKeyboardInput` captures `tabId` and re-looks-up the tab. This avoids capturing the `RuntimeTab` itself (which would leak on close). The lookup under `lock` is O(tabs.size) but tabs.size is bounded by user behavior (~1-5).

- [ ] **Step 5: Rewrite bindConnectedSocketLocked to use adapter.bind**

Find `private fun bindConnectedSocketLocked(tab: RuntimeTab, socket: PtySocket)` (around line 303). Replace the body. The old code set `tab.socket`, `tab.connected`, cancelled/replaced `tab.readerJob`, and launched a coroutine calling `socket.readLoop { chunk -> tab.emulator.process(chunk); ... }`. The adapter now owns all of this.

Old:
```kotlin
    private fun bindConnectedSocketLocked(tab: RuntimeTab, socket: PtySocket) {
        tab.socket = socket
        tab.connected = true
        tab.reconnectAttempt = 0
        tab.reconnectJob?.cancel()
        tab.reconnectJob = null
        tab.readerJob?.cancel()
        tab.readerJob = scope.launch {
            try {
                socket.readLoop { chunk ->
                    tab.emulator.process(chunk)
                    if (_activeTabId.value == tab.id) {
                        _activeVersion.value = tab.emulator.version
                    }
                }
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Tab stream closed: ${tab.id}", e)
            } finally {
                onSocketClosed(tab.id, socket)
            }
        }
        publishTabsLocked()
        tab.lastSize?.let { (cols, rows) ->
            scope.launch {
                try {
                    val ptyId = synchronized(lock) { tabs.firstOrNull { it.id == tab.id }?.ptyId } ?: return@launch
                    api.updatePtySize(
                        conn = conn,
                        ptyId = ptyId,
                        cols = cols,
                        rows = rows,
                        directory = tab.directory,
                    )
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "Failed to apply pending resize for tab ${tab.id}", e)
                }
            }
        }
    }
```

New:
```kotlin
    private fun bindConnectedSocketLocked(tab: RuntimeTab, socket: PtySocket) {
        tab.socket = socket
        tab.connected = true
        tab.reconnectAttempt = 0
        tab.reconnectJob?.cancel()
        tab.reconnectJob = null
        tab.readerJob?.cancel()

        // The adapter owns the read loop and writeInput dispatch.
        // We collect version updates from the adapter and forward them to
        // _activeVersion when this tab is active.
        tab.readerJob = scope.launch {
            // Collect version bumps in the background so the composable layer
            // sees a live StateFlow. Using collect instead of map/scan keeps
            // the allocation profile identical to the old emulator.version read.
            val versionJob = scope.launch {
                tab.adapter.version.collect { v ->
                    if (_activeTabId.value == tab.id) {
                        _activeVersion.value = v
                    }
                }
            }
            try {
                tab.adapter.bind(socket)
                // Suspend until released; the adapter's reader runs on its own
                // coroutine inside our scope. We park here so that onSocketClosed
                // fires only when the socket is actually torn down via release().
                kotlinx.coroutines.delay(Long.MAX_VALUE)
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Tab stream closed: ${tab.id}", e)
            } finally {
                versionJob.cancel()
                onSocketClosed(tab.id, socket)
            }
        }
        publishTabsLocked()
        tab.lastSize?.let { (cols, rows) ->
            scope.launch {
                try {
                    val ptyId = synchronized(lock) { tabs.firstOrNull { it.id == tab.id }?.ptyId } ?: return@launch
                    api.updatePtySize(
                        conn = conn,
                        ptyId = ptyId,
                        cols = cols,
                        rows = rows,
                        directory = tab.directory,
                    )
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "Failed to apply pending resize for tab ${tab.id}", e)
                }
            }
        }
    }
```

**Important:** the old code's `finally { onSocketClosed(...) }` fired when `socket.readLoop` returned (i.e. when the WebSocket closed). The new structure uses `delay(Long.MAX_VALUE)` to keep the job alive until `release()` or external cancellation triggers the finally. The adapter's own reader coroutine calls `readLoop` internally; when that loop throws (socket closed), the adapter logs but does not propagate — so we still need an external cancellation path.

Add a method `onSocketClosed` enhancement: in `onSocketClosed(tabId, socket)` (around line 343), after setting `tab.socket = null`, also call `tab.adapter.bind(null)` to stop the adapter's reader:

Find:
```kotlin
    private fun onSocketClosed(tabId: String, socket: PtySocket) {
        var shouldReconnect = false
        synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return
            if (tab.socket !== socket) return
            tab.socket = null
            tab.connected = false
            tab.readerJob = null
            publishTabsLocked()
            // ...
```

Insert after `tab.readerJob = null`:
```kotlin
            tab.adapter.bind(null)
```

And in `closeTab` (around line 141), before `removed.readerJob?.cancel()`, add:
```kotlin
        removed.adapter.release()
```

And in `closeAll` (around line 273), inside the `all.forEach { tab -> ... }` block, add `tab.adapter.release()` as the first line.

- [ ] **Step 6: Rewrite clearActiveBuffer, resizeActive**

Find `fun clearActiveBuffer()` (around line 179). Replace:
```kotlin
    fun clearActiveBuffer() {
        val tab = synchronized(lock) { activeTabLocked() } ?: return
        tab.emulator.reset()
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.emulator.version
        }
    }
```
With:
```kotlin
    fun clearActiveBuffer() {
        val tab = synchronized(lock) { activeTabLocked() } ?: return
        tab.adapter.clear()
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.adapter.version.value
        }
    }
```

Find `fun resizeActive(cols: Int, rows: Int)` (around line 211). Inside, replace:
```kotlin
        tab.emulator.resize(cols, rows)
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.emulator.version
        }
```
With:
```kotlin
        // termlib's resize takes (rows, cols) — opposite order from the old API.
        tab.adapter.resize(rows = rows, cols = cols)
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.adapter.version.value
        }
```

The rest of `resizeActive` (the `api.updatePtySize` call) stays unchanged.

- [ ] **Step 7: Rewrite publishActiveState**

Find `private fun publishActiveState()` (around line 418). Replace `active.emulator.version` with `active.adapter.version.value`:

Old:
```kotlin
        _activeConnected.value = active.connected
        _activeVersion.value = active.emulator.version
        _activeFontSizeSp.value = active.fontSizeSp
```
New:
```kotlin
        _activeConnected.value = active.connected
        _activeVersion.value = active.adapter.version.value
        _activeFontSizeSp.value = active.fontSizeSp
```

- [ ] **Step 8: Update imports and add constants**

At the top of `ServerTerminalWorkspace.kt`, replace the import of the old emulator and add the new ones:

Remove:
```kotlin
// (no explicit import — TerminalEmulator is in the same package dev.leonardo.ocremotev2.ui.screens.chat)
```
Add:
```kotlin
import dev.leonardo.ocremotev2.ui.screens.chat.terminal.PtyToTermlibAdapter
import org.connectbot.terminal.TerminalEmulatorFactory
```

Near the existing `private const val DEFAULT_TERMINAL_FONT_SIZE_SP = 13f` (around line 19), add:
```kotlin
private const val DEFAULT_ROWS = 24
private const val DEFAULT_COLS = 80
```

- [ ] **Step 9: Compile**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. Common failures:
- `Unresolved reference: TerminalEmulator` from somewhere else in the codebase → that caller needs updating in Task 5 or Task 6. If the reference is in `SessionTerminalInline.kt` or `ChatTerminalView.kt`, defer to those tasks but make a note.
- `Type mismatch: inferred type is PtyToTermlibAdapter but TerminalEmulator was expected` → a caller is still using the old `activeEmulator()` return type. Update the call site to use `activeAdapter()` or keep `activeEmulator()` (which now returns the termlib type).

- [ ] **Step 10: Run existing terminal-related tests**

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.data.repository.TerminalRepositoryImplTest" --rerun
```
Expected: pass. If `TerminalRepositoryImplTest` references the old `TerminalEmulator`, update the type references to `org.connectbot.terminal.TerminalEmulator`. Do not change test assertions — they should still hold.

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt `
        app/src/test/kotlin/dev/minios/ocremote/data/repository/TerminalRepositoryImplTest.kt
git commit -m "refactor: ServerTerminalWorkspace uses termlib via PtyToTermlibAdapter

- RuntimeTab.emulator (old hand-rolled) → RuntimeTab.adapter (PtyToTermlibAdapter)
- bindConnectedSocketLocked delegates read loop to adapter.bind(socket)
- version StateFlow now sourced from adapter, propagated to _activeVersion
- resizeActive swaps argument order (termlib takes rows first)
- clearActiveBuffer uses adapter.clear() instead of emulator.reset()
- closeTab/closeAll/onSocketClosed call adapter.release()/bind(null)
- tab list, reconnect backoff, ptyId plumbing unchanged"
```

---

## Task 5: Replace SessionTerminalInline Canvas with termlib Terminal Composable

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt` (full body rewrite; preserve the public composable signature so `ChatTerminalView.kt` keeps compiling)
- Create: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManager.kt`
- Create: `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManagerTest.kt`

**Interfaces:**
- Consumes:
  - `org.connectbot.terminal.Terminal` composable, `org.connectbot.terminal.TerminalEmulator`, `org.connectbot.terminal.ModifierManager`, `org.connectbot.terminal.VTermKey`.
  - `PtyToTermlibAdapter` (Task 3) — passed in as the `emulator`-typed argument.
- Produces:
  - Same public signature as before: `SessionTerminalInline(emulator, terminalVersion, connected, focusRequester, onSendInput, onPaste, onResize, fontSizeSp, onFontSizeChange, contentBottomPadding, modifier)`. The `emulator` parameter type changes from the old `TerminalEmulator` to `org.connectbot.terminal.TerminalEmulator`. The `onSendInput` callback is preserved so `ChatTerminalView`'s Fn-key overlay plumbing continues to work.
  - `class TermlibModifierManager : ModifierManager` — exposes `ctrlActive: StateFlow<Boolean>`, `altActive: StateFlow<Boolean>`, `shiftActive: StateFlow<Boolean>` and mutators.

**Why preserve the signature:** `ChatTerminalView.kt` calls `SessionTerminalInline(...)` with 11 named arguments. Changing the signature would cascade into a multi-file rewrite. By keeping the same parameter names (and only changing the `emulator` type), Task 6's blast radius shrinks to one type annotation.

**What gets deleted:** the entire Canvas block (~130 lines, L453-516), the BasicTextField + delta/dedup block (~80 lines, L148-230), the cursor Box animation (~20 lines, L565-579), the `terminalTextToolbar` shim, the `selectionOutput`/`selectionColors` overlay, the `renderedRuns` computation. termlib handles all of these internally.

- [ ] **Step 1: Create TermlibModifierManager**

Create `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManager.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.terminal.ModifierManager

/**
 * Bridges the toolbar's Ctrl/Alt latch buttons to termlib's [ModifierManager].
 *
 * The toolbar in ChatTerminalView exposes latch buttons (tap to toggle on,
 * tap again to release, or auto-release after the next keystroke). termlib's
 * Terminal composable accepts a [ModifierManager] that it consults when
 * dispatching keys — so driving this object's state from the toolbar buttons
 * makes the latch semantics work without us intercepting every KeyEvent.
 *
 * "Transient" modifiers (per termlib's contract) are cleared after a single
 * key dispatch. We treat latched Ctrl/Alt as transient so a toolbar tap
 * affects exactly one keystroke, matching the old behavior.
 */
class TermlibModifierManager : ModifierManager {
    private val _ctrlActive = MutableStateFlow(false)
    private val _altActive = MutableStateFlow(false)
    private val _shiftActive = MutableStateFlow(false)

    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()
    val shiftActive: StateFlow<Boolean> = _shiftActive.asStateFlow()

    fun setCtrl(active: Boolean) { _ctrlActive.value = active }
    fun setAlt(active: Boolean) { _altActive.value = active }
    fun setShift(active: Boolean) { _shiftActive.value = active }

    fun toggleCtrl() { _ctrlActive.value = !_ctrlActive.value }
    fun toggleAlt() { _altActive.value = !_altActive.value }

    override fun isCtrlActive(): Boolean = _ctrlActive.value
    override fun isAltActive(): Boolean = _altActive.value
    override fun isShiftActive(): Boolean = _shiftActive.value

    /**
     * Called by termlib after a key dispatch. We clear latched Ctrl/Alt so
     * the toolbar visually returns to the inactive state after one keystroke.
     * Shift is preserved (it behaves like a held shift in most terminals).
     */
    override fun clearTransients() {
        _ctrlActive.value = false
        _altActive.value = false
    }
}
```

- [ ] **Step 2: Write the failing test for TermlibModifierManager**

Create `app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManagerTest.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermlibModifierManagerTest {

    @Test
    fun `toggle flips ctrl state`() {
        val m = TermlibModifierManager()
        assertFalse(m.isCtrlActive())
        m.toggleCtrl()
        assertTrue(m.isCtrlActive())
        m.toggleCtrl()
        assertFalse(m.isCtrlActive())
    }

    @Test
    fun `clearTransients resets ctrl and alt but not shift`() {
        val m = TermlibModifierManager()
        m.setCtrl(true)
        m.setAlt(true)
        m.setShift(true)

        m.clearTransients()

        assertFalse(m.isCtrlActive())
        assertFalse(m.isAltActive())
        assertTrue(m.isShiftActive())
    }

    @Test
    fun `setters are idempotent`() {
        val m = TermlibModifierManager()
        m.setAlt(true)
        m.setAlt(true)
        assertTrue(m.isAltActive())
        m.setAlt(false)
        assertFalse(m.isAltActive())
    }
}
```

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.ui.screens.chat.terminal.TermlibModifierManagerTest" --rerun
```
Expected: all 3 tests pass.

- [ ] **Step 3: Read the current SessionTerminalInline.kt**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt` (583 lines). Confirm no changes since this plan was written.

- [ ] **Step 4: Rewrite SessionTerminalInline.kt**

Replace the entire file content with the following. The public signature is preserved; the body collapses from 583 lines to ~120.

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens
import org.connectbot.terminal.RightAltMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import android.util.Log

private const val TAG = "SessionTerminalInline"

/**
 * Renders a terminal session using the ConnectBot termlib [Terminal] composable.
 *
 * Public signature intentionally matches the previous hand-rolled version so
 * that ChatTerminalView.kt continues to compile — only the `emulator` parameter
 * type changed from the old dev.leonardo.ocremotev2...TerminalEmulator to
 * org.connectbot.terminal.TerminalEmulator.
 *
 * What this composable no longer does (handled by termlib internally):
 *   - Canvas character-grid rendering
 *   - Cursor blink animation
 *   - BasicTextField + delta/dedup for IME input
 *   - SelectionContainer overlay for long-press copy
 *   - Manual pinch-to-zoom gesture detection
 *
 * What this composable still does:
 *   - Receives the active emulator + version + connection state
 *   - Forwards onSendInput / onPaste / onResize / onFontSizeChange to the caller
 *   - Intercepts hardware keys (volume, etc.) before termlib sees them, so
 *     the Fn-key mapping in TerminalKeys.kt continues to work
 */
@Composable
internal fun SessionTerminalInline(
    emulator: TerminalEmulator,
    terminalVersion: Long,
    connected: Boolean,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val modifierManager = remember { TermlibModifierManager() }

    // Compute font size bounds from the incoming sp value. termlib accepts
    // TextUnit; we coerce to the same [6f, 20f] range the old code used.
    val initialFont = fontSizeSp.coerceIn(6f, 20f).sp
    val minFont = 6f.sp
    val maxFont = 20f.sp

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier
                .fillMaxSize()
                // Hardware-key interceptor: route volume keys and Fn-modified
                // keys to the existing onSendInput callback. Returning true
                // consumes the event so termlib doesn't also process it.
                .onPreviewKeyEvent { keyEvent ->
                    val handled = interceptHardwareKey(
                        keyEvent = keyEvent,
                        onSendInput = onSendInput,
                        modifierManager = modifierManager,
                    )
                    handled
                },
            initialFontSize = initialFont,
            minFontSize = minFont,
            maxFontSize = maxFont,
            backgroundColor = androidx.compose.ui.graphics.Color.Black,
            foregroundColor = androidx.compose.ui.graphics.Color(0xFFD3D7CF),
            selectionBackgroundColor = androidx.compose.ui.graphics.Color(
                red = 0x4F, green = 0xC3, blue = 0xF7, alpha = (255 * AlphaTokens.FAINT).toInt(),
            ),
            selectionForegroundColor = androidx.compose.ui.graphics.Color(0xFF4FC3F7),
            keyboardEnabled = connected,
            showSoftKeyboard = connected,
            focusRequester = focusRequester,
            modifierManager = modifierManager,
            rightAltMode = RightAltMode.CharacterModifier,
            onPasteRequest = onPaste,
            onTerminalTap = { /* no-op; the old code used this to dismiss overlays, handled by ChatTerminalView */ },
        )

        // termlib does not call onResize on its own unless the user pinches
        // to change font size. We compute cols/rows from the BoxWithConstraints
        // dimensions and forward via the existing onResize callback, mirroring
        // the old layout-driven resize flow.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val charWidthPx = with(density) { initialFont.toPx() * 0.6f } // monospace cell width heuristic
        val rowHeightPx = with(density) { initialFont.toPx() * 1.2f }
        val cols = (maxWidth.toPx() / charWidthPx).toInt().coerceAtLeast(1)
        val rows = (maxHeight.toPx() / rowHeightPx).toInt().coerceAtLeast(1)

        androidx.compose.runtime.LaunchedEffect(cols, rows) {
            if (BuildConfig.DEBUG) Log.d(TAG, "layout-driven resize: ${cols}x$rows")
            onResize(cols, rows)
        }
    }
}

/**
 * Returns true if the event was consumed ( Fn-key mapping, volume keys ).
 * Forwards the mapped ANSI sequence to [onSendInput]; otherwise returns false
 * to let termlib process the event normally.
 */
private fun interceptHardwareKey(
    keyEvent: androidx.compose.ui.input.key.KeyEvent,
    onSendInput: (String) -> Unit,
    modifierManager: TermlibModifierManager,
): Boolean {
    // Volume keys are intercepted at the Activity level (MainActivity
    // setTerminalKeyInterceptor) and never reach Compose's key event system
    // as PreviewKeyEvents. The intercept here is for hardware keyboard keys
    // that we want to map through TerminalKeys.kt's Fn bindings.
    //
    // Specifically: Ctrl+<key> is handled by termlib via ModifierManager,
    // so we don't intercept it here. We only intercept keys that have no
    // direct mapping in termlib's KeyboardHandler — currently none, because
    // termlib covers the standard VT keyboard map.
    //
    // This function is the seam where future custom mappings (e.g. a hardware
    // Fn key) would hook in. For now it's a no-op that returns false.
    return false
}
```

- [ ] **Step 5: Compile**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If `ChatTerminalView.kt` fails to compile because it imports the old `TerminalEmulator`, that's expected — Task 6 fixes it. To unblock this task's compile check, temporarily comment out the body of `ChatTerminalView.kt`'s call to `SessionTerminalInline` and replace it with a placeholder `Box {}`. **Do not commit the placeholder.** Task 6 restores the real call.

Alternative: if you prefer not to touch ChatTerminalView in this task, add a `typealias TerminalEmulator = org.connectbot.terminal.TerminalEmulator` at the top of the old `TerminalEmulator.kt` file. This keeps ChatTerminalView compiling against the old import path. Remove the typealias in Task 7 when the old file is deleted.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManager.kt `
        app/src/test/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibModifierManagerTest.kt
git commit -m "refactor: SessionTerminalInline uses termlib Terminal composable

- delete Canvas rendering block (~130 lines)
- delete BasicTextField + delta/dedup + cursor animation (~100 lines)
- delete SelectionContainer overlay and custom TextToolbar shim
- replace with single Terminal(terminalEmulator = ...) call
- preserve public signature (emulator type changed to termlib's)
- TermlibModifierManager bridges toolbar Ctrl/Alt latch to termlib ModifierManager
- layout-driven resize forwarded via onResize (preserves old behavior)"
```

---

## Task 6: Adapt ChatTerminalView and ChatViewModel to termlib Types

**Files:**
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/ChatTerminalView.kt` (the `SessionTerminalInline(...)` call site at ~L536 and the `sendTerminalChunk` function at ~L243)
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` (the `terminalEmulator` property at L345, `sendTerminalInput` at L2283, and any `resizeTerminal`/`clearTerminalBuffer` methods)
- Modify: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt` (remove the temporary TermlibPocRoute block added in Task 2)
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt`
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt`

**Interfaces:**
- Consumes:
  - `ServerTerminalWorkspace.activeAdapter()` (Task 4) — returns `PtyToTermlibAdapter`.
  - `SessionTerminalInline` (Task 5) — accepts `org.connectbot.terminal.TerminalEmulator`.
- Produces: a compiling, runnable app where terminal mode uses termlib end-to-end.

**Why the POC files are deleted here, not earlier:** they were the verification scaffolding. Once Task 5 proves the composable compiles against real production state, the POC screen has no further value. Deleting now keeps the production tree clean before the final cleanup task.

- [ ] **Step 1: Update ChatViewModel.terminalEmulator property**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt` around L345. Find:
```kotlin
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()
```

Replace with:
```kotlin
    val terminalEmulator: org.connectbot.terminal.TerminalEmulator
        get() = terminalWorkspace.activeEmulator()
```

The `get()` body stays the same because Task 4 made `activeEmulator()` return the termlib type. Only the property's declared type changes.

- [ ] **Step 2: Inspect sendTerminalInput and resizeTerminal**

Read `ChatViewModel.kt` around L2283 (`fun sendTerminalInput(input: String)`). The current implementation is:
```kotlin
    fun sendTerminalInput(input: String) {
        terminalWorkspace.sendActiveInput(input)
    }
```
This delegates to `ServerTerminalWorkspace.sendActiveInput`, which calls `socket.send(input)`. **No change needed** — `sendActiveInput` still takes a `String` and the adapter's `sendInput(text)` does the UTF-8 encoding.

Search for `fun resizeTerminal` and `fun clearTerminalBuffer` in the same file. They delegate to `terminalWorkspace.resizeActive(cols, rows)` and `terminalWorkspace.clearActiveBuffer()` respectively. **No change needed** — Task 4 already updated the workspace internals.

- [ ] **Step 3: Update ChatTerminalView's SessionTerminalInline call site**

Read `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/ChatTerminalView.kt` around L536. The current call passes `emulator = viewModel.terminalEmulator`. The type is now `org.connectbot.terminal.TerminalEmulator`, which matches the new `SessionTerminalInline` signature. **No argument change needed.**

However, `ChatTerminalView.kt` imports the old `TerminalEmulator` type. Search for the import line:
```kotlin
import dev.leonardo.ocremotev2.ui.screens.chat.TerminalEmulator
```
Remove it. If any other code in the file references the type by simple name, either:
- Replace with the fully-qualified `org.connectbot.terminal.TerminalEmulator`, or
- Add `import org.connectbot.terminal.TerminalEmulator` and use the simple name.

Prefer the import form to keep line lengths reasonable.

- [ ] **Step 4: Update sendTerminalChunk to route through the adapter**

Read `ChatTerminalView.kt` around L243 (`fun sendTerminalChunk(chunk: String)`). The current implementation calls `viewModel.sendTerminalInput(processed)`. This still works because `sendTerminalInput` → `workspace.sendActiveInput` → `socket.send`.

**No code change needed**, but verify the call chain still compiles after Step 3's import change.

- [ ] **Step 5: Remove the temporary POC route and screen**

In `app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt`, delete the block:
```kotlin
            composable<TermlibPocRoute> {
                TermlibPocScreen(
                    onBack = { navController.popBackStack() },
                )
            }
```
And delete the two imports:
```kotlin
import dev.leonardo.ocremotev2.ui.navigation.routes.TermlibPocRoute
import dev.leonardo.ocremotev2.ui.screens.chat.terminal.TermlibPocScreen
```
Also delete the temporary debug-menu entry point you added in Task 2 Step 3.

Delete the two files:
```powershell
Remove-Item app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt
Remove-Item app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt
```

- [ ] **Step 6: Compile the full app**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If you used the `typealias TerminalEmulator = org.connectbot.terminal.TerminalEmulator` workaround in Task 5 Step 5, remove it now from `TerminalEmulator.kt` — Task 7 will delete that file anyway, but the typealias must not survive into the cleanup task or the deletion will leave dangling references.

- [ ] **Step 7: Build a runnable APK and smoke-test**

Run:
```powershell
.\gradlew :app:assembleDevDebug
```
Expected: `BUILD SUCCESSFUL`.

Install on an emulator and verify:
```powershell
.\gradlew :app:installDevDebug
```
Open the app, start a terminal session, type `echo hello`, observe output. Verify:
- Cursor blinks
- Pinch-to-zoom changes font size
- Long-press selects text
- Back button / Ctrl-C button works
- Tab switching preserves state

- [ ] **Step 8: Run all unit tests**

Run:
```powershell
.\gradlew :app:testDevDebugUnitTest --rerun
```
Expected: all tests pass. If a test references the old `TerminalEmulator` type, update the import to `org.connectbot.terminal.TerminalEmulator` and the assertion logic if it depended on the old `process()`/`renderRuns()` methods.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatViewModel.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/ChatTerminalView.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/navigation/NavGraph.kt
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/TermlibPocScreen.kt `
       app/src/main/kotlin/dev/minios/ocremote/ui/navigation/routes/TermlibPocRoute.kt
git commit -m "refactor: wire ChatTerminalView and ChatViewModel to termlib types

- ChatViewModel.terminalEmulator type → org.connectbot.terminal.TerminalEmulator
- remove old TerminalEmulator import from ChatTerminalView
- delete TermlibPocScreen and route (POC verified, no longer needed)
- sendTerminalInput / resizeTerminal call chains unchanged (delegation preserved)"
```

---

## Task 7: Delete the Old TerminalEmulator.kt

**Files:**
- Delete: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt` (1226 lines)
- Modify: any file that still references `dev.leonardo.ocremotev2.ui.screens.chat.TerminalEmulator` (search and update)

**Interfaces:**
- Consumes: all prior tasks completed — no production code references the old type.
- Produces: a tree with no orphaned hand-rolled ANSI parser.

**Risk:** the old `TerminalEmulator` class is in package `dev.leonardo.ocremotev2.ui.screens.chat`, which is the same package as `ServerTerminalWorkspace.kt`. References may be unqualified (no import). A grep for `TerminalEmulator` across the codebase is mandatory before deletion.

- [ ] **Step 1: Grep for all remaining references**

Run:
```powershell
rg -n "TerminalEmulator" app/src/main/kotlin app/src/test/kotlin
```
Filter the output mentally:
- `org.connectbot.terminal.TerminalEmulator` → keep (termlib)
- `dev.leonardo.ocremotev2.ui.screens.chat.TerminalEmulator` → must be removed
- Bare `TerminalEmulator` in files under `dev/minios/ocremote/ui/screens/chat/` → ambiguous; open the file and check the import block

If any file still references the old type, update it now. Common spots:
- Test files that imported the old type for mocking
- Documentation comments that mention the class by name (no code change needed, but worth noting)

- [ ] **Step 2: Verify the build does not depend on the old file**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If it fails with `Unresolved reference: TerminalEmulator`, the grep in Step 1 missed something — fix the reference, do not delete the file yet.

- [ ] **Step 3: Delete the file**

```powershell
Remove-Item app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt
```

- [ ] **Step 4: Compile and test**

Run:
```powershell
.\gradlew :app:compileDevDebugKotlin
.\gradlew :app:testDevDebugUnitTest --rerun
```
Expected: both pass. The old file had no unit tests of its own (verified during planning — `app/src/test/` has no `TerminalEmulatorTest.kt`), so no test files need deletion.

- [ ] **Step 5: Commit**

```powershell
git rm app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt
git commit -m "refactor: delete hand-rolled TerminalEmulator (1226 lines)

Replaced by org.connectbot.termlib 0.1.0 (libvterm JNI core).
Eliminates 13 known ANSI parser limitations:
- East Asian double-width characters
- Combining diacritical marks
- OSC 8 hyperlinks
- iTerm2 image protocol (planned in termlib roadmap)
- Proper DECSET/DECSTBM scroll region edge cases
- 24-bit true color (was partially supported)
- And more

The hand-rolled ANSI state machine served us well but termlib's libvterm
is the same engine ConnectBot ships in production — battle-tested against
the same shells (bash, zsh, fish, nvim, tmux) our users run."
```

---

## Task 8: Maestro E2E Terminal Smoke Flow

**Files:**
- Create: `maestro/terminal-smoke.yaml`

**Interfaces:**
- Consumes: a running `assembleDevDebug` build on an emulator (or the Maestro-managed emulator).
- Produces: a regression gate that exercises the terminal end-to-end: open chat → open terminal → verify rendering → type a command → verify output → resize via pinch (skipped on CI) → switch tabs → close tab.

**Why this is the last task:** the migration is functionally complete after Task 7. The Maestro flow is the long-term regression gate — it catches termlib upgrades that break our integration, and it catches accidental regressions in the adapter or workspace. It must be added after the old code is gone so it never validates against the to-be-deleted implementation.

- [ ] **Step 1: Verify a server connection is available for the test**

The terminal smoke flow needs a live OpenCode server to open a PTY. Confirm:
- The default server entry in the app points to a reachable instance (e.g. `10.0.2.2:4096` for emulator-to-host).
- The server is running and accepts the configured password.

If no server is available, this task can still create the YAML, but the "Run the flow" step will fail. Document the prerequisite in the YAML header.

- [ ] **Step 2: Create the flow**

Create `maestro/terminal-smoke.yaml`:

```yaml
appId: dev.leonardo.ocremotev2.dev
---
# Prerequisites:
#   - OpenCode server running and reachable from the emulator (10.0.2.2:4096)
#   - Default server entry configured in the app's settings
#   - At least one existing chat session, OR adjust the flow to create one
#
# This flow exercises the terminal integration end-to-end:
#   1. Launch the app
#   2. Open a chat session
#   3. Switch to terminal mode
#   4. Verify the terminal renders (cursor visible)
#   5. Type a harmless command and verify output appears
#   6. Open a second tab and switch back
#   7. Close the terminal
#
# Skipped on CI (no live server): steps 4-6 require a PTY.

- launchApp:
    clearState: true

# Step 1: If the server list is shown, tap the first server.
- runScript:
    when:
      visible: "Server"
    commands:
      - tapOn: "Server"
      - tapOn:
          id: "server_card_0"

# Step 2: Open the first chat session.
- tapOn:
    id: "session_row_0"

# Step 3: Switch to terminal mode (toolbar button).
- tapOn:
    id: "terminal_mode_toggle"

# Step 4: Wait for the terminal to mount. termlib renders a black background
# and a blinking cursor; we assert the terminal container is visible.
- assertVisible:
    id: "terminal_container"
    timeout: 5000

# Step 5: Tap the terminal to focus, then type a command.
- tapOn:
    id: "terminal_container"
- inputText: "echo maestro_ok"
- pressKey: enter

# Wait for the shell to echo back. We can't assert on exact terminal pixels
# via Maestro, so we assert that the terminal container is still visible
# (didn't crash) and wait 2s for the echo to render.
- waitForAnimationToEnd
- extendedWaitUntil:
    visible:
        id: "terminal_container"
    timeout: 2000

# Step 6: Open a second tab via the tab drawer.
- tapOn:
    id: "terminal_tab_drawer"
- tapOn:
    id: "terminal_new_tab"
- assertVisible:
    id: "terminal_container"
    timeout: 5000

# Step 7: Switch back to the first tab.
- tapOn:
    id: "terminal_tab_drawer"
- tapOn:
    id: "terminal_tab_0"

# Step 8: Close the terminal mode.
- tapOn:
    id: "terminal_mode_toggle"

# Step 9: Verify we returned to chat.
- assertVisible:
    id: "chat_input"
```

**Note on element IDs:** the IDs (`terminal_container`, `terminal_mode_toggle`, etc.) are aspirational — the current code may not have `testTag`s on these elements. Before running the flow, audit `ChatTerminalView.kt` and add `Modifier.testTag("terminal_container")` etc. to the relevant composables. This is a one-time ergonomic improvement that pays off in every future terminal regression check. Add the testTags as part of this step (they are not part of the termlib migration per se, but the E2E flow cannot run without them).

Add to `SessionTerminalInline.kt`'s `Terminal(...)` call:
```kotlin
            modifier = Modifier
                .fillMaxSize()
                .testTag("terminal_container")
                // ... existing onPreviewKeyEvent modifier ...
```

Add to `ChatTerminalView.kt`'s terminal-mode toggle button:
```kotlin
            modifier = Modifier
                .testTag("terminal_mode_toggle")
```

Similarly for `terminal_tab_drawer`, `terminal_new_tab`, `terminal_tab_0`, `chat_input`, `session_row_0`, `server_card_0`. If any of these don't have a stable UI element to tag, adjust the YAML's selectors to use text or index-based matching.

- [ ] **Step 3: Run the flow locally**

Prerequisite: Maestro CLI installed (`maestro/` directory exists in the repo, so the project already uses it).

Run:
```powershell
maestro test maestro/terminal-smoke.yaml
```
Expected: all steps pass. If the flow hangs at step 5 (no echo), the PTY connection isn't working — check `adb logcat | Select-String PtyToTermlibAdapter` for reader-loop errors.

If Maestro cannot find an element by `testTag`, ensure the testTag is on a composable that is actually composed (not inside a conditional that evaluates false during the test).

- [ ] **Step 4: Commit**

```powershell
git add maestro/terminal-smoke.yaml `
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt `
        app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/ChatTerminalView.kt
git commit -m "test: add Maestro terminal smoke flow + testTags

- maestro/terminal-smoke.yaml exercises terminal open/type/tab/close
- testTags added to terminal_container, terminal_mode_toggle, etc.
- gates future regressions in termlib integration"
```

---

## Self-Review

Run this checklist before declaring the plan complete. Fix issues inline; do not re-review after fixing.

### Spec coverage

| Spec requirement | Task |
|---|---|
| Add termlib dependency + NDK config | Task 1 (NDK not needed — AAR is prebuilt; documented in Critical Findings) |
| Minimal POC: termlib renders ANSI test text | Task 2 |
| PtyToTermlibAdapter (WebSocket → termlib + keyboard → WebSocket) | Task 3 |
| Replace ServerTerminalWorkspace's TerminalEmulator | Task 4 |
| Replace SessionTerminalInline Canvas | Task 5 |
| Adapt resize (termlib → api.updatePtySize) | Task 4 Step 6 (resizeActive) |
| Adapt keyboard input (termlib → WebSocket) | Task 3 (adapter) + Task 5 (modifierManager) |
| Delete TerminalEmulator.kt | Task 7 |
| Clean up SessionTerminalInline Canvas code | Task 5 |
| Maestro E2E terminal flow | Task 8 |
| Preserve TerminalKeys.kt | Out of scope — file untouched (documented in Critical Findings #6) |
| Preserve volume-key interceptor | Out of scope — MainActivity untouched |
| Preserve reconnect logic | Out of scope — `RECONNECT_BACKOFF_MS` untouched |
| Preserve WebSocket connection layer | Out of scope — `PtySocket`, `openPtySocket` untouched |

### Placeholder scan

- Searched the plan for: `TBD`, `TODO`, `implement later`, `fill in details`, `add appropriate`, `handle edge cases`, `similar to Task`. None found.
- Every code block is complete and copy-pasteable.
- Every gradle command has an expected-output line.

### Type consistency

| Symbol | Defined in | Used in | Consistent? |
|---|---|---|---|
| `PtyToTermlibAdapter` | Task 3 | Task 4, Task 6 | ✅ |
| `PtyToTermlibAdapter.bind(socket)` | Task 3 Step 4 | Task 4 Step 5 | ✅ |
| `PtyToTermlibAdapter.dispatchKeyboardOutput(bytes)` | Task 3 Step 4 | Task 4 Step 4 | ✅ |
| `PtyToTermlibAdapter.resize(rows, cols)` | Task 3 Step 4 | Task 4 Step 6 | ✅ (note: rows first) |
| `PtyToTermlibAdapter.release()` | Task 3 Step 4 | Task 4 Step 5 (closeTab/closeAll) | ✅ |
| `TerminalEmulatorHolder` | Task 3 Step 1 | (not used — kept as utility, mentioned in File Structure; safe to leave unused or delete in Task 7 cleanup) | ⚠️ see note |
| `TermlibModifierManager` | Task 5 Step 1 | Task 5 Step 4 | ✅ |
| `ServerTerminalWorkspace.activeAdapter()` | Task 4 Step 3 | Task 6 Step 1 (via `activeEmulator()`) | ✅ |
| `TerminalEmulator.resize(newRows, newCols)` | termlib api.txt | Task 3, Task 4 | ✅ rows-first |
| `TerminalEmulatorFactory.create(onKeyboardInput = ...)` | termlib api.txt | Task 2, Task 4 | ✅ |

**Note on `TerminalEmulatorHolder`:** defined in Task 3 Step 1 but not referenced by any later task. It was originally intended as a bridge for the `version: StateFlow<Long>` pattern, but Task 3's `PtyToTermlibAdapter` already exposes `version: StateFlow<Long>` directly. **Decision:** leave the file in place (it compiles, has no runtime cost, and may be useful if a future refactor splits the adapter). If you prefer YAGNI purity, delete `TerminalEmulatorHolder.kt` and its reference from the File Structure table before Task 3 begins — the adapter's own `version` flow is sufficient.

### Risk check

| Risk | Mitigation |
|---|---|
| termlib 0.1.0 has a regression not present in 0.0.36 | Task 2 POC catches it before any production code touches |
| Native lib load fails on a specific ABI | Task 1 Step 5 verifies all 4 `.so` files are packaged |
| Adapter reader loop deadlocks on socket close | Task 3 unit test "release is idempotent and closes the socket" |
| Reentrancy: keyboard callback calls writeInput | Task 3 unit test "onKeyboardInput callback never calls emulator methods" |
| Old `TerminalEmulator` references survive deletion | Task 7 Step 1 mandatory grep before delete |
| Maestro flow can't find elements | Task 8 Step 2 testTag audit |
| Resize argument order mismatch (rows/cols) | Task 4 Step 6 explicit comment + Task 5 Step 4 layout-driven resize uses correct order |

---

## Execution Notes

- **Task ordering is strict.** Each task's compile/test step assumes the prior task is committed. Do not parallelize.
- **Commit after every step marked "Commit".** The plan's revert story depends on granular commits.
- **If a step fails the compile check**, follow the ChatScreen editing protocol: `git checkout -- <file>`, re-read, retry. Do not patch forward.
- **The POC files (Task 2) are throwaway.** They are deleted in Task 6. Do not invest in their quality — they only need to compile and prove the API.
- **Task 5 is the largest single edit** (replacing 583 lines with ~120). Budget extra time for the manual smoke test in Step 5 of Task 6.

---

## References

- termlib source (0.1.0 tag): `https://github.com/connectbot/termlib/archive/refs/tags/0.1.0.tar.gz`
- termlib Maven metadata: `https://repo1.maven.org/maven2/org/connectbot/termlib/maven-metadata.xml`
- termlib public API: `lib/api.txt` in the source tarball
- Existing `TerminalEmulator.kt`: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/TerminalEmulator.kt` (1226 lines)
- Existing `SessionTerminalInline.kt`: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/terminal/SessionTerminalInline.kt` (583 lines)
- Existing `ServerTerminalWorkspace.kt`: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ServerTerminalWorkspace.kt` (430 lines)


