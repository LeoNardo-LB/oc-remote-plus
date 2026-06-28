package dev.leonardo.ocremotev2.ui.screens.viewer

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FileTypeTest {
    @Test
    fun `md extension maps to MARKDOWN`() {
        assertEquals(FileType.MARKDOWN, FileType.fromExtension("readme.md"))
    }
    @Test
    fun `markdown extension maps to MARKDOWN`() {
        assertEquals(FileType.MARKDOWN, FileType.fromExtension("doc.markdown"))
    }
    @Test
    fun `uppercase PNG maps to IMAGE`() {
        assertEquals(FileType.IMAGE, FileType.fromExtension("photo.PNG"))
    }
    @Test
    fun `all image extensions map to IMAGE`() {
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").forEach { ext ->
            assertEquals(".$ext should be IMAGE", FileType.IMAGE, FileType.fromExtension("file.$ext"))
        }
    }
    @Test
    fun `svg extension maps to SVG`() {
        assertEquals(FileType.SVG, FileType.fromExtension("icon.svg"))
    }
    @Test
    fun `csv and tsv map to CSV`() {
        assertEquals(FileType.CSV, FileType.fromExtension("data.csv"))
        assertEquals(FileType.CSV, FileType.fromExtension("data.tsv"))
    }
    @Test
    fun `json extension maps to JSON`() {
        assertEquals(FileType.JSON, FileType.fromExtension("config.json"))
    }
    @Test
    fun `kt extension maps to TEXT`() {
        assertEquals(FileType.TEXT, FileType.fromExtension("main.kt"))
    }
    @Test
    fun `unknown extension maps to TEXT`() {
        assertEquals(FileType.TEXT, FileType.fromExtension("file.xyz"))
    }
    @Test
    fun `no extension maps to TEXT`() {
        assertEquals(FileType.TEXT, FileType.fromExtension("Makefile"))
    }
    @Test
    fun `supportsRender is false for TEXT and JSON`() {
        assertFalse(FileType.TEXT.supportsRender)
        assertFalse(FileType.JSON.supportsRender)
        assertTrue(FileType.MARKDOWN.supportsRender)
        assertTrue(FileType.IMAGE.supportsRender)
        assertTrue(FileType.SVG.supportsRender)
        assertTrue(FileType.CSV.supportsRender)
    }
}
