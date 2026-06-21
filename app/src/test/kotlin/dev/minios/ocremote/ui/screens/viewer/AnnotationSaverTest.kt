package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.runtime.saveable.SaverScope
import dev.minios.ocremote.domain.model.Annotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationSaverTest {

    private val saver = AnnotationListSaver
    private val scope = SaverScope { true }

    @Test
    fun `empty list saves to null and restore of empty yields empty`() {
        val saved = with(saver) { with(scope) { save(emptyList()) } }
        // listSaver returns null for empty lists — nothing to persist
        assertEquals(null, saved)
        // restore with an empty list input yields an empty annotation list
        val restored = saver.restore(emptyList<Any>())
        assertEquals(emptyList<Annotation>(), restored)
    }

    @Test
    fun `single annotation round-trips through saver`() {
        val original = listOf(
            Annotation(
                id = "ann-1", index = 0,
                startChar = 10, endChar = 25,
                startLine = 3, startCol = 1, endLine = 3, endCol = 15,
                selectedText = "import android.os.Bundle",
                note = "use alias", createdAt = 1234567890L
            )
        )
        val saved = with(saver) { with(scope) { save(original) } }
        val restored = saver.restore(saved!!)
        assertEquals(1, restored!!.size)
        assertEquals("ann-1", restored[0].id)
        assertEquals(0, restored[0].index)
        assertEquals(10, restored[0].startChar)
        assertEquals("use alias", restored[0].note)
        assertEquals(1234567890L, restored[0].createdAt)
    }

    @Test
    fun `multiple annotations round-trip preserving creation order`() {
        val original = listOf(
            Annotation("a1", 0, 0, 5, 1, 1, 1, 6, "code1", "note1", 100),
            Annotation("a2", 1, 10, 20, 3, 1, 3, 11, "code2", "note2", 200),
            Annotation("a3", 2, 30, 40, 5, 1, 5, 11, "code3", "note3", 300)
        )
        val saved = with(saver) { with(scope) { save(original) } }
        val restored = saver.restore(saved!!)
        assertEquals(3, restored!!.size)
        assertEquals("a1", restored[0].id)
        assertEquals("a3", restored[2].id)
        assertEquals(2, restored[2].index)
    }

    @Test
    fun `special characters in note and selectedText preserved`() {
        val original = listOf(
            Annotation(
                id = "a1", index = 0,
                startChar = 0, endChar = 5,
                startLine = 1, startCol = 1, endLine = 1, endCol = 6,
                selectedText = "val x = \"中文测试\" // 🎉",
                note = "建议改成\nval y = '正确'",
                createdAt = 1000
            )
        )
        val saved = with(saver) { with(scope) { save(original) } }
        val restored = saver.restore(saved!!)!!
        assertEquals("val x = \"中文测试\" // 🎉", restored[0].selectedText)
        assertEquals("建议改成\nval y = '正确'", restored[0].note)
    }
}
