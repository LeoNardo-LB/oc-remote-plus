package dev.minios.ocremote.ui.screens.sessions

import android.util.Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.usecase.DeleteSessionUseCase
import dev.minios.ocremote.domain.usecase.ManageSessionUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelPaginationTest {

    private val api: OpenCodeApi = mockk()
    private val eventDispatcher: EventDispatcher = mockk(relaxed = true)
    private val manageSessionUseCase: ManageSessionUseCase = mockk()
    private val deleteSessionUseCase: DeleteSessionUseCase = mockk()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        // Relaxed mock returns default Object for StateFlow<List>.value;
        // set up proper empty collections to avoid ClassCastException
        every { eventDispatcher.sessions.value } returns emptyList()
        every { eventDispatcher.sessionStatuses.value } returns emptyMap<String, SessionStatus>()
        every { eventDispatcher.serverSessions.value } returns emptyMap<String, Set<String>>()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `hasMorePages is initially true`() {
        val vm = createViewModel()
        assertTrue(vm.hasMorePages)
    }

    @Test
    fun `isLoadingMore is initially false`() {
        val vm = createViewModel()
        assertFalse(vm.isLoadingMore)
    }

    @Test
    fun `resetPagination clears cursor state`() {
        val vm = createViewModel()
        vm.resetPagination()
        assertTrue(vm.hasMorePages)
        assertEquals(null, vm.currentCursor)
    }

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "serverUrl" to "http%3A%2F%2Flocalhost%3A8080",
                "username" to "",
                "password" to "",
                "serverName" to "Test",
                "serverId" to "srv1"
            )
        )
        return SessionListViewModel(
            savedStateHandle = savedStateHandle,
            eventDispatcher = eventDispatcher,
            api = api,
            manageSessionUseCase = manageSessionUseCase,
            deleteSessionUseCase = deleteSessionUseCase
        )
    }
}
