package dev.minios.ocremote.ui.screens.viewer

import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ContentType
import dev.minios.ocremote.domain.model.FileContent
import dev.minios.ocremote.domain.model.VcsDiffMode
import dev.minios.ocremote.domain.model.VcsFileDiff
import dev.minios.ocremote.domain.usecase.GetFileContentUseCase
import dev.minios.ocremote.domain.usecase.GetFileDiffUseCase
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
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val getFileContent = mockk<GetFileContentUseCase>()
    private val getFileDiff = mockk<GetFileDiffUseCase>()

    private val serverId = "srv-abc123"
    private val directory = "/home/user/project"
    private val filePath = "src/main/kotlin/dev/minios/ocremote/MainActivity.kt"
    private val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
    private val encodedFilePath = URLEncoder.encode(filePath, "UTF-8")

    private fun savedStateHandle(
        id: String = serverId,
        dir: String = encodedDirectory,
        path: String = encodedFilePath,
        source: String = FileViewerNav.Source.LIVE
    ) = SavedStateHandle(
        mapOf(
            ServerRouteParams.PARAM_SERVER_ID to id,
            FileViewerNav.PARAM_DIRECTORY to dir,
            FileViewerNav.PARAM_FILE_PATH to path,
            FileViewerNav.PARAM_SOURCE to source
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

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff)

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
            getFileDiff
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.mode == FileViewerMode.DIFF) { "mode should be DIFF" }
        assert(state.hunks.isNotEmpty()) { "hunks should be parsed from patch" }
        assert(state.hunks.size == 2) { "expected 2 hunks, got ${state.hunks.size}" }
        assert(state.diff != null) { "diff should be set" }
    }

    // 3. TOOL_SNAPSHOT source sets unsupported error (graceful, NOT exception)
    @Test
    fun `TOOL_SNAPSHOT source sets unsupported error`() = runTest {
        val vm = FileViewerViewModel(
            savedStateHandle(source = FileViewerNav.Source.TOOL_SNAPSHOT),
            getFileContent,
            getFileDiff
        )

        val state = vm.uiState.value
        assert(!state.isLoading) { "isLoading should be false" }
        assert(state.error != null) { "error should be set for unsupported source" }
        assert(state.error == R.string.fileviewer_error_tool_snapshot_unsupported) {
            "error should be tool snapshot unsupported resource, was: ${state.error}"
        }

        // Verify no use case was called (not silent downgrade)
        coVerify(exactly = 0) { getFileContent(any(), any(), any()) }
        coVerify(exactly = 0) { getFileDiff(any(), any(), any()) }
    }

    // 4. Load failure sets error
    @Test
    fun `load failure sets error`() = runTest {
        coEvery { getFileContent(serverId, directory, filePath) } returns Result.failure(
            RuntimeException("Connection refused: port 4096")
        )

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff)

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

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff)

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

        val vm = FileViewerViewModel(savedStateHandle(), getFileContent, getFileDiff)

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
            getFileDiff
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
            getFileDiff
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
            getFileDiff
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
}
