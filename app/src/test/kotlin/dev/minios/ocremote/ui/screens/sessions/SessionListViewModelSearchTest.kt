package dev.minios.ocremote.ui.screens.sessions

import android.util.Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.repository.EventDispatcher
import dev.minios.ocremote.domain.usecase.ManageSessionUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelSearchTest {

    private val api: OpenCodeApi = mockk()
    private val eventDispatcher: EventDispatcher = mockk(relaxed = true)
    private val manageSessionUseCase: ManageSessionUseCase = mockk()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `searchQuery state is initially empty`() {
        val initialState = SessionListUiState()
        assertEquals(null, initialState.searchQuery)
    }

    @Test
    fun `setSearchQuery updates the query state`() {
        val vm = createViewModel()
        vm.setSearchQuery("test query")
        assertEquals("test query", vm.searchQuery)
    }

    @Test
    fun `clearSearchQuery resets to null`() {
        val vm = createViewModel()
        vm.setSearchQuery("test")
        vm.clearSearchQuery()
        assertEquals(null, vm.searchQuery)
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
            manageSessionUseCase = manageSessionUseCase
        )
    }
}
