package dev.minios.ocremote.domain.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRepositoryTest {

    @Test
    fun `interface defines connectTerminal, sendInput and resize`() {
        val methods = TerminalRepository::class.java.declaredMethods.map { it.name }
        assertTrue(methods.contains("connectTerminal"))
        assertTrue(methods.contains("sendInput"))
        assertTrue(methods.contains("resize"))
    }
}
