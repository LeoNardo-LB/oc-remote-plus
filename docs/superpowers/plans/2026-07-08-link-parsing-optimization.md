# Link Parsing Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace permissive file-extension regex with a whitelist, and extract paragraph link-detection logic into a shared utility that all Markdown components use.

**Architecture:** Pure-Kotlin `LinkClassifier` whitelist (domain layer) + composable-scoped `ClickableMarkdown` utility (UI layer) consumed by `text`, `paragraph`, `heading1` component overrides and `SimpleMarkdownTable` cells. No original Markdown text is modified.

**Tech Stack:** Kotlin, Jetpack Compose, Mikepenz multiplatform-markdown-renderer, JUnit 4 + MockK

## Global Constraints

- JDK 21, Compose BOM 2026.05.01, KSP (not kapt)
- PowerShell `;` not `&&`; Gradle `--no-daemon`
- ChatScreen.kt editing protocol: Read before Edit, `compileDevDebugKotlin` after each edit
- `LinkClassifier` is pure Kotlin (domain layer) — no Android/Compose imports
- `ClickableMarkdown.kt` lives in `ui/screens/chat/markdown/` package
- Material 3 first; use `MaterialTheme.colorScheme` semantic colors, no hardcoded values
- `extractClickableItems` and `ClickableItem` must be removed from `MarkdownContent.kt` after migration
- Commit after each task's compilation success

**Spec:** `docs/superpowers/specs/2026-07-08-link-parsing-optimization-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `domain/model/LinkClassifier.kt` | Modify | Whitelist-based `isLikelyFilePath()` |
| `test/.../domain/model/LinkClassifierTest.kt` | Modify | New whitelist behavior tests |
| `ui/screens/chat/markdown/ClickableMarkdown.kt` | Create | Shared `buildClickableMarkdown()` + `Modifier.clickableMarkdown()` + `ClickableItem` + `extractClickableItems()` |
| `ui/screens/chat/markdown/MarkdownContent.kt` | Modify | Remove inline logic; wire `text`/`paragraph`/`heading1` to shared utility |
| `ui/screens/chat/markdown/MarkdownTable.kt` | Modify | Add `uriHandler`/`linkColor` params; cells use shared utility |

---

## Task 1: LinkClassifier Whitelist Refactor

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifier.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifierTest.kt`

**Interfaces:**
- Produces: `LinkClassifier.FILE_EXTENSIONS: Set<String>`, `LinkClassifier.SPECIAL_FILENAMES: Set<String>`, `LinkClassifier.isLikelyFilePath(text: String): Boolean` (signature unchanged)

- [ ] **Step 1: Write new failing tests in LinkClassifierTest.kt**

Add these tests at the end of the existing `LinkClassifierTest` class (before the closing `}`):

```kotlin
    // === Whitelist behavior tests ===

    @Test
    fun `package name is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("com.example.foo"))
    }

    @Test
    fun `multi-segment package name is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("org.springframework.boot"))
    }

    @Test
    fun `version string is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("1.0.0-beta"))
    }

    @Test
    fun `unknown extension is not likely file path`() {
        assertFalse(LinkClassifier.isLikelyFilePath("unknown.xyz"))
    }

    @Test
    fun `Makefile is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Makefile"))
    }

    @Test
    fun `Dockerfile is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Dockerfile"))
    }

    @Test
    fun `gitignore is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath(".gitignore"))
    }

    @Test
    fun `yaml extension is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("config.yaml"))
    }

    @Test
    fun `toml extension is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("Cargo.toml"))
    }

    @Test
    fun `path to unknown file is likely file path`() {
        assertTrue(LinkClassifier.isLikelyFilePath("src/unknown"))
    }
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.domain.model.LinkClassifierTest" --no-daemon`
Expected: FAIL — `Makefile`, `Dockerfile`, `.gitignore` return false; `com.example.foo` returns true (old regex behavior)

- [ ] **Step 3: Implement the whitelist in LinkClassifier.kt**

Replace the entire `LinkClassifier` object body (lines 18-50) with:

```kotlin
object LinkClassifier {
    private val windowsAbsoluteRegex = Regex("[A-Za-z]:[\\\\/].*")

    fun classify(url: String): LinkTarget = when {
        url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("ftp://", ignoreCase = true) ||
        url.startsWith("mailto:", ignoreCase = true) -> LinkTarget.Web(url)

        url.startsWith("/") -> LinkTarget.AbsolutePath(url)

        url.startsWith("file://", ignoreCase = true) -> {
            val afterScheme = url.substringAfter("file://")
            LinkTarget.AbsolutePath(afterScheme)
        }

        windowsAbsoluteRegex.matches(url) -> LinkTarget.AbsolutePath(url)

        else -> LinkTarget.RelativePath(url)
    }

    /** Known file extensions for file-path detection in inline code. */
    val FILE_EXTENSIONS: Set<String> = setOf(
        // Languages
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "cs", "rb",
        "php", "swift", "m", "mm", "scala", "clj", "cljs", "ex", "exs",
        "erl", "hs", "lua", "pl", "pm", "r", "dart", "vue", "svelte",
        // JVM / Build
        "gradle", "groovy", "xml", "properties", "toml", "sbt",
        // Web / Config
        "html", "htm", "css", "scss", "sass", "less", "json", "json5",
        "yaml", "yml", "ini", "cfg", "conf", "env",
        // Docs
        "md", "mdx", "rst", "txt", "adoc", "tex", "pdf",
        // Data
        "csv", "tsv", "sql", "db", "sqlite",
        // Shell
        "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
        // Other
        "lock", "log", "diff", "patch",
    )

    /** Extensionless filenames that are valid clickable file paths. */
    val SPECIAL_FILENAMES: Set<String> = setOf(
        "Makefile", "makefile", "GNUmakefile",
        "Dockerfile", "Containerfile",
        "LICENSE", "LICENSE.md", "LICENSE.txt",
        "README", "CHANGELOG", "AUTHORS", "CONTRIBUTING",
        ".gitignore", ".gitattributes", ".editorconfig",
        ".env", ".env.local", ".env.production",
        ".npmrc", ".nvmrc", ".ruby-version",
        "Jenkinsfile", "Vagrantfile", "Gemfile", "Rakefile",
        "WORKSPACE", "BUILD", "BUILD.bazel",
    )

    private val fileExtensionRegex = Regex("\\.([A-Za-z0-9]+)$")

    /**
     * Heuristic: does this inline code content look like a file path or filename?
     *
     * - Contains a path separator (/ or \) → true (package names use '.', not '/')
     * - Has an extension → extension must be in [FILE_EXTENSIONS]
     * - No extension → must be in [SPECIAL_FILENAMES]
     */
    fun isLikelyFilePath(text: String): Boolean {
        if (text.contains('/') || text.contains('\\')) return true
        val extMatch = fileExtensionRegex.find(text)
        if (extMatch != null) {
            return extMatch.groupValues[1].lowercase() in FILE_EXTENSIONS
        }
        return text in SPECIAL_FILENAMES || text.lowercase() in SPECIAL_FILENAMES
    }
}
```

- [ ] **Step 4: Run all LinkClassifier tests to verify they pass**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocremotev2.domain.model.LinkClassifierTest" --no-daemon`
Expected: PASS — all 31 tests (21 existing + 10 new)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifier.kt app/src/test/kotlin/dev/leonardo/ocremotev2/domain/model/LinkClassifierTest.kt
git commit -m "refactor: LinkClassifier whitelist — eliminate package-name false positives

Replace permissive '.\w{1,10}$' regex with extension whitelist (~100 entries)
plus special-filename whitelist (Makefile, Dockerfile, .gitignore, etc).
'com.example.foo' no longer misidentified as file path."
```

---

## Task 2: Create ClickableMarkdown Shared Utility

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/ClickableMarkdown.kt`

**Interfaces:**
- Consumes: `LinkClassifier.isLikelyFilePath` from Task 1
- Produces: `ClickableItem` (sealed interface), `ClickableMarkdownResult` (data class), `buildClickableMarkdown()` (plain function), `Modifier.clickableMarkdown()` (@Composable extension)

- [ ] **Step 1: Create ClickableMarkdown.kt**

Create the file at `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/ClickableMarkdown.kt`:

```kotlin
package dev.leonardo.ocremotev2.ui.screens.chat.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import dev.leonardo.ocremotev2.domain.model.LinkClassifier
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

internal sealed interface ClickableItem {
    val text: String
    data class Link(override val text: String, val url: String) : ClickableItem
    data class CodePath(override val text: String) : ClickableItem
}

internal data class ClickableMarkdownResult(
    val annotatedString: AnnotatedString,
    val items: List<ClickableItem>,
)

/**
 * Extract clickable items from a markdown AST node:
 * - [text](url) markdown links → ClickableItem.Link
 * - `code` inline code that looks like a path → ClickableItem.CodePath
 */
private fun extractClickableItems(content: String, node: ASTNode): List<ClickableItem> {
    val items = mutableListOf<ClickableItem>()
    fun walk(n: ASTNode) {
        if (n.type == MarkdownElementTypes.INLINE_LINK) {
            val dest = n.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
            val textNode = n.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            if (dest != null && textNode != null) {
                val url = dest.getUnescapedTextInNode(content).toString()
                val rawText = textNode.getUnescapedTextInNode(content).toString()
                val linkText = rawText.removeSurrounding("[", "]")
                if (linkText.isNotEmpty() && url.isNotEmpty()) {
                    items.add(ClickableItem.Link(linkText, url))
                }
            }
        } else if (n.type == MarkdownElementTypes.CODE_SPAN) {
            val raw = n.getUnescapedTextInNode(content).toString()
            val codeText = raw.trim('`').trim()
            if (codeText.isNotEmpty() && LinkClassifier.isLikelyFilePath(codeText)) {
                items.add(ClickableItem.CodePath(codeText))
            }
        }
        n.children.forEach { walk(it) }
    }
    walk(node)
    return items
}

/**
 * Builds an [AnnotatedString] with clickable file paths overlaid on top of
 * standard markdown link rendering.
 *
 * 1. [buildMarkdownAnnotatedString] produces base text with standard links.
 * 2. Walks the AST for [CODE_SPAN] nodes; [LinkClassifier.isLikelyFilePath] filters them.
 * 3. Overlays underline + [linkColor] style on matched code paths.
 */
internal fun buildClickableMarkdown(
    content: String,
    node: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings,
    linkColor: Color,
): ClickableMarkdownResult {
    val rawAnnotated = content.buildMarkdownAnnotatedString(
        textNode = node,
        style = style,
        annotatorSettings = annotatorSettings,
    )
    val items = extractClickableItems(content, node)
    val codePaths = items.filterIsInstance<ClickableItem.CodePath>()
    val annotated = if (codePaths.isEmpty()) rawAnnotated else {
        buildAnnotatedString {
            append(rawAnnotated.text)
            rawAnnotated.spanStyles.forEach { range ->
                addStyle(range.item, range.start, range.end)
            }
            var searchFrom = 0
            for (cp in codePaths) {
                val idx = rawAnnotated.text.indexOf(cp.text, searchFrom)
                if (idx >= 0) {
                    addStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = linkColor,
                        ),
                        idx, idx + cp.text.length,
                    )
                    searchFrom = idx + cp.text.length
                }
            }
        }
    }
    return ClickableMarkdownResult(annotated, items)
}

/**
 * Registers tap-gesture handling for clickable items in the [ClickableMarkdownResult].
 *
 * Uses [TextLayoutResult] to map tap position → item → [uriHandler].openUri().
 * Must be called from a @Composable context.
 */
@Composable
internal fun Modifier.clickableMarkdown(
    result: ClickableMarkdownResult,
    layoutResultProvider: () -> TextLayoutResult?,
    uriHandler: UriHandler,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .clickable(
            interactionSource = interactionSource,
            indication = null,
        ) { }
        .pointerInput(result.annotatedString) {
            detectTapGestures { pos ->
                val layout = layoutResultProvider() ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(pos)
                val text = result.annotatedString.text
                var searchFrom = 0
                for (item in result.items) {
                    val idx = text.indexOf(item.text, searchFrom)
                    if (idx >= 0 && offset >= idx && offset < idx + item.text.length) {
                        when (item) {
                            is ClickableItem.Link -> uriHandler.openUri(item.url)
                            is ClickableItem.CodePath -> uriHandler.openUri(item.text)
                        }
                        return@detectTapGestures
                    }
                    if (idx >= 0) searchFrom = idx + item.text.length
                }
            }
        }
}
```

- [ ] **Step 2: Compile to verify no errors**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/ClickableMarkdown.kt
git commit -m "feat: ClickableMarkdown shared utility

Extract paragraph link-detection logic into reusable buildClickableMarkdown()
+ Modifier.clickableMarkdown(). Shared by text/paragraph/heading1/table
components in subsequent tasks."
```

---

## Task 3: Wire Up Component Overrides in MarkdownContent.kt

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownContent.kt`

**Interfaces:**
- Consumes: `buildClickableMarkdown`, `clickableMarkdown`, `ClickableMarkdownResult` from Task 2

This task:
1. Removes the inline `ClickableItem` sealed interface and `extractClickableItems` function (now in ClickableMarkdown.kt)
2. Removes unused imports from MarkdownContent.kt
3. Adds `text` component override
4. Refactors `paragraph` override to use shared utility (~40 lines removed)
5. Refactors `heading1` override to support file-path links

- [ ] **Step 1: Read the current MarkdownContent.kt**

Run: Read `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownContent.kt`
Confirm: lines 260-410 contain the components and inline logic to be replaced.

- [ ] **Step 2: Remove old ClickableItem and extractClickableItems**

Delete lines 374-410 (the `ClickableItem` sealed interface and `extractClickableItems` function). These are now in `ClickableMarkdown.kt`.

Also remove these now-unused imports from the top of the file:
```kotlin
import androidx.compose.ui.text.buildAnnotatedString  // if not used elsewhere
import com.mikepenz.markdown.utils.getUnescapedTextInNode  // if not used elsewhere
import org.intellij.markdown.MarkdownElementTypes  // if not used elsewhere
import org.intellij.markdown.ast.findChildOfType  // if not used elsewhere
```

**IMPORTANT:** Check each import — only remove if no other code in the file uses it. `getUnescapedTextInNode` is still used in `heading1`; `MarkdownElementTypes` may still be needed. Keep imports that are still referenced.

- [ ] **Step 3: Add `text` component override and refactor `paragraph`**

Replace the entire `markdownComponents(...)` block (approximately lines 260-325) with:

```kotlin
    val components = remember(density, isUser, linkListener, linkColor) {
        markdownComponents(
            text = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = buildClickableMarkdown(
                    model.content, model.node, model.typography.text, settings, linkColor,
                )
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                MarkdownBasicText(
                    text = result.annotatedString,
                    style = model.typography.text,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
                )
            },
            paragraph = { model ->
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = buildClickableMarkdown(
                    model.content, model.node, model.typography.text, settings, linkColor,
                )
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                MarkdownBasicText(
                    text = result.annotatedString,
                    style = model.typography.text,
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
                )
            },
            heading1 = { model ->
                val headingText = model.node.getUnescapedTextInNode(model.content)
                    .dropWhile { it == '#' }
                    .trim()
                val settings = annotatorSettings(linkInteractionListener = linkListener)
                val result = buildClickableMarkdown(
                    model.content, model.node, model.typography.text, settings, linkColor,
                )
                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                Column {
                    MarkdownBasicText(
                        text = result.annotatedString,
                        style = model.typography.text,
                        onTextLayout = { layoutResult = it },
                        modifier = Modifier.clickableMarkdown(result, { layoutResult }, uriHandler),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(top = spacing.block),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
                    )
                }
            },
            table = { model ->
                // Task 4 will update this call to pass uriHandler + linkColor
                SimpleMarkdownTable(model.content, model.node, model.typography.table)
            },
        )
    }
```

**Key changes:**
- `text` override added (NEW — covers list items, block quotes, etc.)
- `paragraph` reduced from ~60 lines to ~12 lines
- `heading1` now supports file-path links
- `table` passes `uriHandler` and `linkColor` to `SimpleMarkdownTable` (wired in Task 4)

**Fallback:** If you prefer to do Task 4 first (changing `SimpleMarkdownTable` signature before this task), update the `table` line to `SimpleMarkdownTable(model.content, model.node, model.typography.table, uriHandler, linkColor)` here and skip the signature change in Task 4 Step 2.

- [ ] **Step 4: Compile to verify**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownContent.kt
git commit -m "refactor: wire text/paragraph/heading1 to ClickableMarkdown utility

Paragraph drops ~40 lines of inline logic. text component override added
(covers list items, blockquotes). heading1 now supports file-path links.
Removes ClickableItem/extractClickableItems (moved to ClickableMarkdown.kt)."
```

---

## Task 4: Add Link Support to SimpleMarkdownTable

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownTable.kt`

**Interfaces:**
- Consumes: `buildClickableMarkdown`, `clickableMarkdown` from Task 2

- [ ] **Step 1: Read the current MarkdownTable.kt**

Run: Read `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownTable.kt`
Confirm: line 55-59 has `SimpleMarkdownTable` signature; lines 147-154 has cell rendering.

- [ ] **Step 2: Update the function signature**

Change the `SimpleMarkdownTable` function signature (line 55-59) from:

```kotlin
@Composable
internal fun SimpleMarkdownTable(
    content: String,
    tableNode: ASTNode,
    style: TextStyle,
) {
```

To:

```kotlin
@Composable
internal fun SimpleMarkdownTable(
    content: String,
    tableNode: ASTNode,
    style: TextStyle,
    uriHandler: androidx.compose.ui.platform.UriHandler,
    linkColor: androidx.compose.ui.graphics.Color,
) {
```

- [ ] **Step 3: Update cell rendering to use shared utility**

Replace the cell rendering block (approximately lines 115-155). Find the inner `cellContent` composable lambda and replace the `MarkdownBasicText` call inside each cell with:

```kotlin
                        val cellAnnotator = annotatorSettings()
                        val cellResult = remember(content, cell) {
                            buildClickableMarkdown(content, cell, cellStyle, cellAnnotator, linkColor)
                        }
                        var cellLayoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                        MarkdownBasicText(
                            text = cellResult.annotatedString,
                            style = cellStyle,
                            onTextLayout = { cellLayoutResult = it },
                            modifier = Modifier.clickableMarkdown(cellResult, { cellLayoutResult }, uriHandler),
                        )
```

This replaces the old:
```kotlin
                        MarkdownBasicText(
                            text = content.buildMarkdownAnnotatedString(
                                textNode = cell,
                                style = cellStyle,
                                annotatorSettings = annotator,
                            ),
                            style = cellStyle,
                        )
```

**Note:** The old `annotator` variable (line 66: `val annotator = annotatorSettings()`) can be removed if no longer used elsewhere in the file. Check before removing.

- [ ] **Step 4: Add necessary imports**

At the top of MarkdownTable.kt, add:

```kotlin
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
```

Remove the now-unused import if `annotatorSettings` is no longer called directly:
```kotlin
// Check: if annotator variable is fully removed, remove this:
// import com.mikepenz.markdown.annotator.annotatorSettings
// import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
```

- [ ] **Step 5: Update the table call in MarkdownContent.kt**

In `MarkdownContent.kt`, update the `table` component to pass the new parameters:

```kotlin
            table = { model ->
                SimpleMarkdownTable(model.content, model.node, model.typography.table, uriHandler, linkColor)
            },
```

- [ ] **Step 6: Compile to verify**

Run: `.\gradlew :app:compileDevDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/markdown/MarkdownTable.kt
git commit -m "feat: table cells support clickable file paths and markdown links

SimpleMarkdownTable now accepts uriHandler + linkColor. Each cell uses
buildClickableMarkdown() — inline code file paths and [text](url) links
in table cells are now clickable."
```

---

## Task 5: UI Verification on Emulator

**Files:**
- No file changes — manual verification only

- [ ] **Step 1: Build Release APK**

Run: `.\gradlew :app:assembleDevRelease --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install and launch**

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/dev/release/app-dev-release.apk
adb -s emulator-5554 shell am force-stop dev.leonardo.ocremotev2.dev
adb -s emulator-5554 shell am start -n dev.leonardo.ocremotev2.dev/dev.leonardo.ocremotev2.MainActivity
```

- [ ] **Step 3: Open a chat session and verify**

Navigate to a session with AI-generated markdown content. Verify:

1. **Package name not clickable**: Find a message with `` `com.example.foo` `` in inline code → should NOT have underline, should NOT be clickable
2. **File path clickable**: Find `` `src/Foo.kt` `` → should have underline, tap opens FileViewer
3. **Table cell file path**: Find a markdown table with `` `config.yaml` `` in a cell → should be clickable
4. **Table standard link**: Find `[docs](https://...)` in a table → should be clickable (previously broken)
5. **List item file path**: Find a list item with `` `Main.kt` `` → should be clickable (verify `text` override works)
6. **Special filename**: Find `` `Makefile` `` → should be clickable

- [ ] **Step 4: If list items are NOT clickable**

If verification #5 fails (list items don't get file-path detection), the `text` component override is not used by list rendering. Add diagnostic logging:

```kotlin
// In the text override, add temporarily:
android.util.Log.d("TextOverride", "text component invoked for: ${model.node.type}")
```

If no log appears when scrolling list items, override `orderedList` and `unorderedList` components as well. Use the same `buildClickableMarkdown` + `clickableMarkdown` pattern.

- [ ] **Step 5: Run unit tests**

Run: `.\gradlew :app:testDevDebugUnitTest --no-daemon --rerun`
Expected: ALL PASS

- [ ] **Step 6: Clean up any diagnostic logging**

Remove any temporary `Log.d` calls added during verification.

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit --allow-empty -m "chore: link parsing optimization verified on emulator

All 6 verification scenarios pass: package names not clickable, file paths
clickable in paragraphs/tables/headings/list items, special filenames work."
```

---

## Self-Review Checklist

- [x] **Spec coverage**: All 4 design sections covered (LinkClassifier refactor = Task 1, ClickableMarkdown utility = Task 2, component overrides = Task 3, table = Task 4, testing = Task 5)
- [x] **Placeholder scan**: No TBD/TODO/placeholder steps
- [x] **Type consistency**: `buildClickableMarkdown` signature consistent across Tasks 2/3/4; `ClickableMarkdownResult` used consistently
- [x] **Test coverage**: 10 new unit tests for whitelist behavior; 6 UI verification scenarios
