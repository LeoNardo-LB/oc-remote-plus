# Chat Link Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable clickable `[text](url)` links in ChatScreen AI messages to intelligently navigate based on link type — web URLs open browser, relative paths resolve against session directory and open in FileViewer/Workspace, absolute paths open in FileViewer.

**Architecture:** A custom `LocalUriHandler` wraps ChatScreen content, intercepting all markdown link clicks. `LinkClassifier` (pure Kotlin) categorizes URLs. ChatScreen resolves paths using `PathUtils.joinPath` against the session directory and delegates to existing `onOpenFile` callback or new `onOpenDirectory` callback. NavGraph wires `onOpenDirectory` to `WorkspaceNav`.

**Tech Stack:** Kotlin, Jetpack Compose, mikepenz multiplatform-markdown-renderer (uses `LocalUriHandler` for link clicks), Material 3, JUnit 4 + MockK for tests.

## Global Constraints

- **PathUtils** (`util/PathUtils.kt`) for all path operations — remote paths may use `/` or `\`
- **JDK 21** required; Compose BOM 2026.05.01
- **mikepenz markdown** version 0.43.0 — links handled via `LocalUriHandler` CompositionLocal
- **PowerShell** — use `;` not `&&`; `.\gradlew` with `--no-daemon`
- **Gradle timeouts:** compileDevDebugKotlin = 120s, unit tests = 180s
- **Material 3 First** — use semantic colors, no hardcoded values
- **ChatScreen.kt editing protocol** — Read before Edit, compile after each edit
- **Session directory** = `viewModel.directoryState` (sessionLifecycle.sessionDirectory), NOT server connection directory

---

### Task 1: PathUtils.joinPath + Unit Test

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/util/PathUtils.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocremotev2/util/PathUtilsTest.kt`

**Interfaces:**
- Produces: `PathUtils.joinPath(base: String, relative: String): String` — joins two path segments with a `/` separator, trimming excess separators from both sides. Returns `relative` if `base` is blank.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/leonardo/ocremotev2/util/PathUtilsTest.kt
package dev.leonardo.ocremotev2.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PathUtilsTest {

    @Test
    fun `joinPath joins base and relative with slash`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project", "src/Foo.kt"))
    }

    @Test
    fun `joinPath handles trailing slash on base`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project/", "src/Foo.kt"))
    }

    @Test
    fun `joinPath handles leading slash on relative`() {
        assertEquals("/home/user/project/src/Foo.kt",
            PathUtils.joinPath("/home/user/project", "/src/Foo.kt"))
    }

    @Test
    fun `joinPath handles trailing backslash on base`() {
        assertEquals("C:\\Users\\project/src/Foo.kt",
            PathUtils.joinPath("C:\\Users\\project\\", "src/Foo.kt"))
    }

    @Test
    fun `joinPath returns relative when base is blank`() {
        assertEquals("src/Foo.kt",
            PathUtils.joinPath("", "src/Foo.kt"))
    }

    @Test
    fun `joinPath returns base when relative is blank`() {
        assertEquals("/home/user/project",
            PathUtils.joinPath("/home/user/project", ""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*PathUtilsTest*" --no-daemon`
Expected: FAIL — `joinPath` method not found

- [ ] **Step 3: Implement joinPath in PathUtils**

Add this method to the `PathUtils` object in `app/src/main/kotlin/dev/leonardo/ocremotev2/util/PathUtils.kt`, after the existing `relativePath` method (after line 44):

```kotlin
    /**
     * Join two path segments with a `/` separator.
     * Handles trailing/leading separators on both [base] and [relative].
     * Returns [relative] unchanged if [base] is blank.
     */
    fun joinPath(base: String, relative: String): String {
        if (base.isBlank()) return relative
        if (relative.isBlank()) return base
        val normalizedBase = base.trimEnd(*SEPARATORS)
        val normalizedRelative = relative.trimStart(*SEPARATORS)
        return "$normalizedBase/$normalizedRelative"
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*PathUtilsTest*" --no-daemon`
Expected: PASS — all 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/util/PathUtils.kt app/src/test/kotlin/dev/leonardo/ocremotev2/util/PathUtilsTest.kt
git commit -m "feat(util): add PathUtils.joinPath for cross-platform path joining"
```

---

### Task 2: LinkClassifier + Unit Test

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifier.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifierTest.kt`

**Interfaces:**
- Produces: `LinkClassifier.classify(url: String): LinkTarget` — returns one of `LinkTarget.Web(url)`, `LinkTarget.RelativePath(path)`, or `LinkTarget.AbsolutePath(path)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifierTest.kt
package dev.leonardo.ocremotev2.domain.model

import dev.leonardo.ocremotev2.domain.model.LinkClassifier.classify
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkClassifierTest {

    @Test
    fun `http URL classified as Web`() {
        assertTrue(classify("http://example.com") is LinkTarget.Web)
    }

    @Test
    fun `https URL classified as Web`() {
        assertTrue(classify("https://github.com/repo") is LinkTarget.Web)
    }

    @Test
    fun `ftp URL classified as Web`() {
        assertTrue(classify("ftp://server/file") is LinkTarget.Web)
    }

    @Test
    fun `mailto URL classified as Web`() {
        assertTrue(classify("mailto:test@example.com") is LinkTarget.Web)
    }

    @Test
    fun `Unix absolute path classified as AbsolutePath`() {
        val result = classify("/home/user/project/src/Foo.kt")
        assertTrue(result is LinkTarget.AbsolutePath)
        assertTrue((result as LinkTarget.AbsolutePath).path == "/home/user/project/src/Foo.kt")
    }

    @Test
    fun `Windows absolute path classified as AbsolutePath`() {
        val result = classify("C:\\Users\\project\\src\\Foo.kt")
        assertTrue(result is LinkTarget.AbsolutePath)
    }

    @Test
    fun `Windows absolute path with forward slash classified as AbsolutePath`() {
        val result = classify("D:/projects/app/src/Main.kt")
        assertTrue(result is LinkTarget.AbsolutePath)
    }

    @Test
    fun `relative path classified as RelativePath`() {
        val result = classify("src/Foo.kt")
        assertTrue(result is LinkTarget.RelativePath)
        assertTrue((result as LinkTarget.RelativePath).path == "src/Foo.kt")
    }

    @Test
    fun `relative path with dots classified as RelativePath`() {
        assertTrue(classify("../docs/api.md") is LinkTarget.RelativePath)
    }

    @Test
    fun `relative path with subdirectory classified as RelativePath`() {
        assertTrue(classify("./config/settings.yaml") is LinkTarget.RelativePath)
    }

    @Test
    fun `bare filename classified as RelativePath`() {
        assertTrue(classify("README.md") is LinkTarget.RelativePath)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*LinkClassifierTest*" --no-daemon`
Expected: FAIL — `LinkClassifier` and `LinkTarget` not found

- [ ] **Step 3: Implement LinkClassifier**

```kotlin
// app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifier.kt
package dev.leonardo.ocremotev2.domain.model

/**
 * Classifies a URL string from a markdown `[text](url)` link into one of three types.
 * Pure Kotlin — no Android dependencies.
 */
sealed interface LinkTarget {
    /** Web URL: http://, https://, ftp://, mailto: */
    data class Web(val url: String) : LinkTarget

    /** Path relative to the session working directory: src/Foo.kt, ../docs/api.md */
    data class RelativePath(val path: String) : LinkTarget

    /** Absolute path: /home/user/Foo.kt or C:\Users\Foo.kt */
    data class AbsolutePath(val path: String) : LinkTarget
}

object LinkClassifier {
    private val windowsAbsoluteRegex = Regex("[A-Za-z]:[\\\\/].*")

    fun classify(url: String): LinkTarget = when {
        url.startsWith("http://") ||
        url.startsWith("https://") ||
        url.startsWith("ftp://") ||
        url.startsWith("mailto:") -> LinkTarget.Web(url)

        url.startsWith("/") -> LinkTarget.AbsolutePath(url)

        windowsAbsoluteRegex.matches(url) -> LinkTarget.AbsolutePath(url)

        else -> LinkTarget.RelativePath(url)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*LinkClassifierTest*" --no-daemon`
Expected: PASS — all 11 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifier.kt app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifierTest.kt
git commit -m "feat(domain): add LinkClassifier for URL type detection"
```

---

### Task 3: LinkUriHandler Composable + String Resources

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/LinkUriHandler.kt`
- Modify: `app/src/main/res/values/strings.xml` — add 3 link error strings

**Interfaces:**
- Consumes: `LinkClassifier.classify(url)`, `PathUtils.joinPath(base, relative)`, `PathUtils.fileName(path)`
- Produces: `rememberLinkUriHandler(...)` composable returning a `UriHandler` that intercepts markdown link clicks

- [ ] **Step 1: Add string resources**

Add these 3 strings inside the `<resources>` block in `app/src/main/res/values/strings.xml` (place near other chat-related strings):

```xml
<string name="chat_link_no_browser">No app available to open this link</string>
<string name="chat_link_no_workdir">Cannot resolve link — no working directory</string>
<string name="chat_link_only_files">Only files can be opened from absolute paths</string>
```

- [ ] **Step 2: Implement LinkUriHandler**

```kotlin
// app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/LinkUriHandler.kt
package dev.leonardo.ocremotev2.ui.screens.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler
import dev.leonardo.ocremotev2.R
import dev.leonardo.ocremotev2.domain.model.LinkClassifier
import dev.leonardo.ocremotev2.domain.model.LinkTarget
import dev.leonardo.ocremotev2.util.PathUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Creates a custom [UriHandler] that intercepts markdown link clicks.
 *
 * - Web links (http/https) → open browser via [Intent.ACTION_VIEW]
 * - Relative file paths → [onOpenFile] with resolved absolute path
 * - Relative directory paths → [onOpenDirectory] with resolved absolute path
 * - Absolute file paths → [onOpenFile]
 * - Absolute directory paths → Snackbar (only files supported)
 *
 * @param directory Session working directory for resolving relative paths
 * @param onOpenFile Callback to open a file (resolved path passed to FileViewerNav)
 * @param onOpenDirectory Callback to open a directory in the workspace tree
 */
@Composable
fun rememberLinkUriHandler(
    directory: String,
    onOpenFile: (filePath: String) -> Unit,
    onOpenDirectory: (directoryPath: String) -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
): UriHandler {
    val context = LocalContext.current
    val currentDirectory = rememberUpdatedState(directory)
    val currentOnOpenFile = rememberUpdatedState(onOpenFile)
    val currentOnOpenDirectory = rememberUpdatedState(onOpenDirectory)

    return remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                handleLinkClick(
                    uri = uri,
                    directory = currentDirectory.value,
                    context = context,
                    onOpenFile = currentOnOpenFile.value,
                    onOpenDirectory = currentOnOpenDirectory.value,
                    snackbarHostState = snackbarHostState,
                    coroutineScope = coroutineScope,
                )
            }
        }
    }
}

private fun handleLinkClick(
    uri: String,
    directory: String,
    context: Context,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
) {
    when (val target = LinkClassifier.classify(uri)) {
        is LinkTarget.Web -> {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_no_browser))
                }
            }
        }

        is LinkTarget.RelativePath -> {
            if (directory.isBlank()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_no_workdir))
                }
                return
            }
            val resolved = PathUtils.joinPath(directory, target.path)
            if (isLikelyDirectory(target.path)) {
                onOpenDirectory(resolved)
            } else {
                onOpenFile(resolved)
            }
        }

        is LinkTarget.AbsolutePath -> {
            if (isLikelyDirectory(target.path)) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.chat_link_only_files))
                }
            } else {
                onOpenFile(target.path)
            }
        }
    }
}

/**
 * Heuristic: a path is likely a directory if it ends with a separator
 * or its last segment has no dot (no file extension).
 */
private fun isLikelyDirectory(path: String): Boolean {
    if (path.endsWith("/") || path.endsWith("\\")) return true
    val fileName = PathUtils.fileName(path)
    return !fileName.contains(".")
}
```

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/LinkUriHandler.kt app/src/main/res/values/strings.xml
git commit -m "feat(chat): add LinkUriHandler for markdown link interception"
```

---

### Task 4: ChatScreen Integration

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `rememberLinkUriHandler(directory, onOpenFile, onOpenDirectory, snackbarHostState, coroutineScope)` from Task 3
- Produces: ChatScreen gains `onOpenDirectory: (String) -> Unit` parameter

- [ ] **Step 1: Read the relevant ChatScreen sections**

Read `ChatScreen.kt` lines 260-270 (function signature) and lines 525-540 (where Scaffold content begins) to understand the insertion points. You must Read before Edit per the ChatScreen editing protocol.

- [ ] **Step 2: Add `onOpenDirectory` parameter**

In the ChatScreen function signature (around line 267), add `onOpenDirectory` after `onOpenFile`:

Find:
```kotlin
    onOpenFile: (filePath: String) -> Unit = {},
```

Replace with:
```kotlin
    onOpenFile: (filePath: String) -> Unit = {},
    onOpenDirectory: (directoryPath: String) -> Unit = {},
```

- [ ] **Step 3: Add imports**

Add these imports at the top of `ChatScreen.kt` (near the existing `import androidx.compose...` block):

```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
```

- [ ] **Step 4: Create the link UriHandler and wrap content**

Find the line where `coroutineScope` is declared (search for `rememberCoroutineScope`). After the `directory` state is collected and `coroutineScope` is available, add:

```kotlin
    val linkUriHandler = rememberLinkUriHandler(
        directory = directory,
        onOpenFile = onOpenFile,
        onOpenDirectory = onOpenDirectory,
        snackbarHostState = snackbarHostState,
        coroutineScope = coroutineScope,
    )
```

Then wrap the existing `Scaffold(...) { ... }` call with `CompositionLocalProvider`:

Find the `Scaffold(` that begins the main screen layout, and wrap it:

```kotlin
    CompositionLocalProvider(LocalUriHandler provides linkUriHandler) {
        Scaffold(
            // ... existing Scaffold content stays unchanged ...
        ) {
            // ... existing content ...
        }
    }
```

**IMPORTANT:** The `CompositionLocalProvider` must wrap the entire `Scaffold`. The opening brace goes before `Scaffold(`, and the closing brace goes after the Scaffold's closing `}`. Do not change anything inside the Scaffold.

- [ ] **Step 5: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

If compilation fails: `git checkout -- app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt`, re-read the file, and retry.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt
git commit -m "feat(chat): integrate LinkUriHandler into ChatScreen via LocalUriHandler"
```

---

### Task 5: NavGraph Wiring

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/navigation/NavGraph.kt`

**Interfaces:**
- Consumes: `WorkspaceNav.createRoute(serverUrl, username, password, serverName, serverId, sessionId, directory)` — from existing `WorkspaceNav` object
- Produces: ChatScreen's `onOpenDirectory` callback wired to navigate to WorkspaceNav with resolved directory path

- [ ] **Step 1: Read NavGraph ChatScreen invocation**

Read `NavGraph.kt` around lines 480-530 where `onOpenWorkspace` and `onOpenFile` callbacks are defined. Understand the pattern: each callback uses `scope.launch` to get session directory, then calls `navController.navigate(...)`.

- [ ] **Step 2: Add `onOpenDirectory` callback**

After the existing `onOpenFile` lambda block (which ends around line 528 with `}`), add a new `onOpenDirectory` parameter:

Find the closing of the `onOpenFile` callback:
```kotlin
                onOpenFile = { filePath ->
                    scope.launch {
                        // ... existing code ...
                    }
                },
```

Immediately after the closing `},`, add:

```kotlin
                onOpenDirectory = { directoryPath ->
                    navController.navigate(
                        WorkspaceNav.createRoute(
                            serverUrl = params.server.serverUrl,
                            username = params.server.username,
                            password = params.server.password,
                            serverName = params.server.serverName,
                            serverId = params.server.serverId,
                            sessionId = params.sessionId,
                            directory = directoryPath
                        )
                    ) { launchSingleTop = true }
                },
```

**Note:** Unlike `onOpenFile` and `onOpenWorkspace`, `onOpenDirectory` does NOT need `scope.launch` because it doesn't need to fetch the session — the directory path is already resolved by the caller.

- [ ] **Step 3: Compile check**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all viewer + chat tests**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "*PathUtilsTest*" --tests "*LinkClassifierTest*" --no-daemon`
Expected: PASS — all tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/navigation/NavGraph.kt
git commit -m "feat(nav): wire onOpenDirectory to WorkspaceNav in NavGraph"
```

---

### Task 6: Final Verification

- [ ] **Step 1: Full compile**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `.\gradlew :app:testDevDebugUnitTest --no-daemon`
Expected: All tests pass (existing + new PathUtilsTest + LinkClassifierTest)

- [ ] **Step 3: Manual test checklist**

Deploy `assembleDevDebug` APK to emulator and verify:

1. **Web link:** AI sends `[GitHub](https://github.com)` → tap → browser opens
2. **Relative file:** AI sends `[see Foo](src/Foo.kt)` → tap → FileViewer opens with file content
3. **Relative directory:** AI sends `[docs](docs/)` → tap → Workspace tree opens
4. **Absolute file:** AI sends `[config](/etc/app.conf)` → tap → FileViewer opens
5. **Absolute directory:** AI sends `[home](/home/)` → tap → Snackbar "Only files..."
6. **Existing menu navigation still works:** Top bar "Open Workspace" → workspace tree
7. **Existing file opening still works:** Tool call file viewer → FileViewer

- [ ] **Step 4: Commit and tag**

```bash
git add -A
git commit -m "feat(chat): markdown link navigation — web, file, directory"
```
