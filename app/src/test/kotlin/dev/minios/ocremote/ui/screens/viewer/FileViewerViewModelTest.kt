package dev.minios.ocremote.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ContentType
import dev.minios.ocremote.domain.model.FileContent
import dev.minios.ocremote.domain.model.VcsDiffMode
import dev.minios.ocremote.domain.model.VcsFileDiff
import dev.minios.ocremote.domain.repository.ToolSnapshotCache
import dev.minios.ocremote.domain.usecase.GetFileContentUseCase
import dev.minios.ocremote.domain.usecase.GetFileDiffUseCase
import dev.minios.ocremote.domain.usecase.SubmitAnnotationsUseCase
import dev.minios.ocremote.ui.navigation.routes.FileViewerNav
import dev.minios.ocremote.ui.navigation.routes.ServerRouteParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val getFileContent = mockk<GetFileContentUseCase>()
    private val getFileDiff = mockk<GetFileDiffUseCase>()
    private val toolSnapshotCache = ToolSnapshotCache()
    private val submitAnnotations = mockk<SubmitAnnotationsUseCase>()

    private val serverId = "srv-abc123"
    private val directory = "/home/user/project"
    private val filePath = "src/main/kotlin/dev/minios/ocremote/MainActivity.kt"
    private val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
    private val encodedFilePath = URLEncoder.encode(filePath, "UTF-8")
    private val mdFilePath = "docs/README.md"
    private val encodedMdFilePath = URLEncoder.encode(mdFilePath, "UTF-8")

    private fun savedStateHandle(
        id: String = serverId,
        dir: String = encodedDirectory,
        path: String = encodedFilePath,
        source: String = FileViewerNav.Source.LIVE,
        toolPartIds: String = ""
    ) = SavedStateHandle(
        mapOf(
            ServerRouteParams.PARAM_SERVER_ID to id,
            FileViewerNav.PARAM_DIRECTORY to dir,
            FileViewerNav.PARAM_FILE_PATH to path,
            FileViewerNav.PARAM_SOURCE to source,
            FileViewerNav.PARAM_TOOL_PART_IDS to toolPartIds
        )
    )

    // --- Realistic test data (D7-003) ---

    private val sampleKotlinSource = """
        package dev.minios.ocremote

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.activity_main)
            }
        }
    """.trimIndent()

    private val sampleFileContent = FileContent(
        path = filePath,
        type = ContentType.TEXT,
        content = sampleKotlinSource
    )

    private val sampleMarkdownContent = FileContent(
        path = mdFilePath,
        type = ContentType.TEXT,
        content = "# OC Remote\n\nUnofficial OpenCode Android client.\n\n## Features\n\n- File viewer\n- Markdown preview\n"
    )

    private val samplePatch = """
        @@ -10,6 +10,8 @@
         import dagger.hilt.android.lifecycle.HiltViewModel
         import dev.minios.ocremote.domain.model.Session
         import dev.minios.ocremote.domain.repository.SessionRepository
        +import kotlinx.coroutines.flow.MutableStateFlow
        +import kotlinx.coroutines.flow.StateFlow
         import javax.inject.Inject

        @@ -25,4 +27,6 @@
                 private val sessionRepository: SessionRepository
             ) : ViewModel() {

        -    val sessions = mutableStateOf(emptyList())
        +    private val _sessions = MutableStateFlow(emptyList<Session>())
        +    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
        +
                 init { loadSessions() }
    """.trimIndent()

    private val sampleDiffs = listOf(
        VcsFileDiff(
            file = filePath,
            patch = samplePatch,
            additions = 4,
            deletions = 1,
            status = dev.minios.ocremote.domain.model.VcsStatus.MODIFIED
        )
    )

    private val binaryContent = FileContent(
        path = "assets/logo.png",
        type = ContentType.BINARY,
        content = "",
        mimeType = "image/png"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. LIVE source success loads content
    @Test
    fun `LIVE source success loads content`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false after success" }
        assert(state.content == sampleKotlinSource) { "content should match" }
        assert(!state.isBinary) { "isBinary should be false for text" }
        assert(state.error == null) { "error should be null on success" }
    }

    // 2. GIT_DIFF source success parses hunks
    @Test
    fun `GIT_DIFF source success parses hunks`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.mode == FileViewerMode.DIFF) { "mode should be DIFF" }
        assert(state.hunks.isNotEmpty()) { "hunks should be parsed from patch" }
        assert(state.hunks.size == 2) { "expected 2 hunks, got ${state.hunks.size}" }
        assert(state.diff != null) { "diff should be set" }
    }

    // 3. TOOL_SNAPSHOT source without cache sets missing error
    @Test
    fun `TOOL_SNAPSHOT source without cache sets missing error`() = runTest {
        toolSnapshotCache.clear()
        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.TOOL_SNAPSHOT),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.error != null) { "error should be set for missing snapshot" }
        assert(state.error == R.string.fileviewer_error_tool_snapshot_missing) {
            "error should be tool snapshot missing resource, was: ${state.error}"
        }

        coVerify(exactly = 0) { getFileContent(any(), any(), any()) }
        coVerify(exactly = 0) { getFileDiff(any(), any(), any()) }
    }

    // 4. Load failure sets error
    @Test
    fun `load failure sets error`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.failure(
            RuntimeException("Connection refused: port 4096")
        )

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false after failure" }
        assert(state.error == R.string.workspace_error_load_failed) {
            "error should be load failed resource, was: ${state.error}"
        }
    }

    // 5. Binary file sets isBinary + mimeType
    @Test
    fun `binary file sets isBinary and mimeType`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(binaryContent)

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.isBinary) { "isBinary should be true" }
        assert(state.mimeType == "image/png") { "mimeType should be image/png" }
        assert(state.content.isEmpty()) { "content should be empty for binary" }
    }

    // 6. Empty content sets isEmpty
    @Test
    fun `empty content sets isEmpty`() = runTest {
        val emptyContent = FileContent(
            path = filePath,
            type = ContentType.TEXT,
            content = ""
        )
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(emptyContent)

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.isEmpty) { "isEmpty should be true for blank content" }
        assert(state.content.isEmpty()) { "content should be empty" }
    }

    // 7. Empty patch sets hunks empty
    @Test
    fun `empty patch sets hunks empty`() = runTest {
        val emptyPatchDiff = VcsFileDiff(
            file = filePath,
            patch = "",
            additions = 0,
            deletions = 0,
            status = dev.minios.ocremote.domain.model.VcsStatus.MODIFIED
        )
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(
            listOf(emptyPatchDiff)
        )

        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.hunks.isEmpty()) { "hunks should be empty for empty patch" }
        assert(state.isEmpty) { "isEmpty should be true when hunks are empty" }
    }

    // 8. nextHunk clamps at last index
    @Test
    fun `nextHunk clamps at last index`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        val hunksCount = vm.uiState.value.hunks.size
        // Navigate to last hunk
        for (i in 0 until hunksCount) vm.nextHunk()
        assert(vm.uiState.value.currentHunkIndex == hunksCount - 1) {
            "currentHunkIndex should be last (${hunksCount - 1})"
        }
        // One more nextHunk should stay at last
        vm.nextHunk()
        assert(vm.uiState.value.currentHunkIndex == hunksCount - 1) {
            "currentHunkIndex should clamp at last (${hunksCount - 1})"
        }
    }

    // 9. prevHunk clamps at 0
    @Test
    fun `prevHunk clamps at 0`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.GIT_DIFF),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        // currentHunkIndex starts at 0
        assert(vm.uiState.value.currentHunkIndex == 0) {
            "currentHunkIndex should start at 0"
        }
        // prevHunk should stay at 0
        vm.prevHunk()
        assert(vm.uiState.value.currentHunkIndex == 0) {
            "currentHunkIndex should clamp at 0, was ${vm.uiState.value.currentHunkIndex}"
        }
    }

    // ===== Phase 2: Markdown toggle tests =====

    // 10. init with md file sets isMarkdown true
    @Test
    fun `init with md file sets isMarkdown true and keeps SOURCE renderMode`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            savedStateHandle(path = encodedMdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.isMarkdown) { "isMarkdown should be true for .md" }
        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) { "renderMode should default to SOURCE" }
    }

    // 11. init with kt file sets isMarkdown false
    @Test
    fun `init with kt file sets isMarkdown false`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)

        assert(!vm.uiState.value.isMarkdown) { "isMarkdown should be false for .kt" }
    }

    // 12. toggleRenderMode switches SOURCE to RENDER_PREVIEW
    @Test
    fun `toggleRenderMode switches SOURCE to RENDER_PREVIEW for markdown files`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            savedStateHandle(path = encodedMdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.RENDER_PREVIEW) {
            "renderMode should be RENDER_PREVIEW after toggle"
        }
    }

    // 13. toggleRenderMode no-op for non-markdown
    @Test
    fun `toggleRenderMode is no-op for non-markdown files`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should stay SOURCE for non-md"
        }
    }

    // 14. toggleRenderMode no-op in DIFF mode
    @Test
    fun `toggleRenderMode is no-op in DIFF mode`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)

        val vm = FileViewerViewModel(
            savedStateHandle(path = encodedMdFilePath, source = FileViewerNav.Source.GIT_DIFF),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        vm.toggleRenderMode()

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should stay SOURCE in DIFF mode"
        }
    }

    // 15. toggleRenderMode toggles back to SOURCE
    @Test
    fun `toggleRenderMode back from RENDER_PREVIEW to SOURCE`() = runTest {
        coEvery { getFileContent(any(), any(), any()) } returns Result.success(sampleMarkdownContent)

        val vm = FileViewerViewModel(
            savedStateHandle(path = encodedMdFilePath),
            getFileContent, getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )
        vm.toggleRenderMode()  // → RENDER_PREVIEW
        vm.toggleRenderMode()  // → SOURCE

        assert(vm.uiState.value.renderMode == FileViewerRenderMode.SOURCE) {
            "renderMode should be SOURCE after second toggle"
        }
    }

    // ===== Phase 2 Task 9: Tool snapshot tests =====

    // 16. TOOL_SNAPSHOT loads content from cache + clears on onCleared
    @Test
    fun `TOOL_SNAPSHOT source loads content from cache and clears on cleared`() = runTest {
        toolSnapshotCache.clear()
        toolSnapshotCache.put(
            "part-1",
            ToolSnapshotCache.Snapshot(
                filePath = "app/Main.kt", content = "class Main",
                before = null, after = null, toolName = "read"
            )
        )

        val vm = FileViewerViewModel(
            savedStateHandle(
                path = URLEncoder.encode("app/Main.kt", "UTF-8"),
                source = FileViewerNav.Source.TOOL_SNAPSHOT,
                toolPartIds = "part-1"
            ),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.isToolSnapshot) { "isToolSnapshot should be true" }
        assert(vm.uiState.value.content == "class Main") { "content should match snapshot" }
        assert(!vm.uiState.value.isLoading) { "isLoading should be false" }

        vm.cleanupToolSnapshots()
        assert(toolSnapshotCache.get("part-1") == null) { "cache should be cleared on cleanup" }
    }

    // 17. TOOL_SNAPSHOT_DIFF loads cumulative diff from multiple parts
    @Test
    fun `TOOL_SNAPSHOT_DIFF loads cumulative diff from multiple cached parts`() = runTest {
        toolSnapshotCache.clear()
        toolSnapshotCache.putAll(
            mapOf(
                "p1" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1\nline2\n", "line1-mod\nline2\n", "edit"),
                "p2" to ToolSnapshotCache.Snapshot("app/X.kt", null, "line1-mod\nline2\n", "line1-mod\nline2-new\n", "edit")
            )
        )

        val vm = FileViewerViewModel(
            savedStateHandle(
                path = URLEncoder.encode("app/X.kt", "UTF-8"),
                source = FileViewerNav.Source.TOOL_SNAPSHOT_DIFF,
                toolPartIds = "p1,p2"
            ),
            getFileContent,
            getFileDiff,
            toolSnapshotCache,
            submitAnnotations
        )

        assert(vm.uiState.value.mode == FileViewerMode.DIFF) { "mode should be DIFF" }
        assert(vm.uiState.value.isToolSnapshot) { "isToolSnapshot should be true" }
        assert(vm.uiState.value.toolSnapshotBefore == "line1\nline2\n") {
            "cumulativeBefore should be first part's before"
        }
        assert(vm.uiState.value.toolSnapshotAfter == "line1-mod\nline2-new\n") {
            "cumulativeAfter should be last part's after"
        }
    }

    // ===== Phase 3: Annotation tests =====

    @Test
    fun `addAnnotation creates annotation and updates state`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        val pos = sampleKotlinSource.indexOf("import android.os.Bundle")
        vm.addAnnotation("import android.os.Bundle", pos, pos + 25, "use alias")
        assertEquals(1, vm.uiState.value.annotations.size)
        assertEquals(0, vm.uiState.value.annotations[0].index)
    }

    @Test
    fun `addAnnotation in DIFF mode is ignored`() = runTest {
        coEvery { getFileDiff(serverId, directory, VcsDiffMode.GIT) } returns Result.success(sampleDiffs)
        val vm = FileViewerViewModel(savedStateHandle(source = FileViewerNav.Source.GIT_DIFF), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("text", 0, 4, "note")
        assertTrue(vm.uiState.value.annotations.isEmpty())
    }

    @Test
    fun `deleteAnnotation removes and renumbers`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        vm.addAnnotation("class", 10, 15, "n2")
        vm.addAnnotation("override", 20, 28, "n3")
        val firstId = vm.uiState.value.annotations[0].id
        vm.deleteAnnotation(firstId)
        val anns = vm.uiState.value.annotations
        assertEquals(2, anns.size)
        assertEquals(0, anns[0].index)
        assertEquals(1, anns[1].index)
    }

    @Test
    fun `submitAnnotations calls use case and clears on success`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        val result = vm.submitAnnotations("overall note")
        assertTrue(result.isSuccess)
        assertTrue(vm.uiState.value.annotations.isEmpty())
    }

    @Test
    fun `submitAnnotations failure does not clear`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.success(sampleFileContent)
        coEvery { submitAnnotations(any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("fail"))
        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff, toolSnapshotCache, submitAnnotations)
        vm.addAnnotation("import", 0, 6, "n1")
        vm.submitAnnotations("note")
        assertEquals(1, vm.uiState.value.annotations.size)
    }
}
