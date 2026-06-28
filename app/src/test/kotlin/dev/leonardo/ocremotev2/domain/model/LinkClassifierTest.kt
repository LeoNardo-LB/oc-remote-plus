package dev.leonardo.ocremotev2.domain.model

import dev.leonardo.ocremotev2.domain.model.LinkClassifier.classify
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        assertEquals("/home/user/project/src/Foo.kt", (result as LinkTarget.AbsolutePath).path)
    }

    @Test
    fun `Windows absolute path classified as AbsolutePath`() {
        assertTrue(classify("C:\\Users\\project\\src\\Foo.kt") is LinkTarget.AbsolutePath)
    }

    @Test
    fun `Windows absolute path with forward slash classified as AbsolutePath`() {
        assertTrue(classify("D:/projects/app/src/Main.kt") is LinkTarget.AbsolutePath)
    }

    @Test
    fun `relative path classified as RelativePath`() {
        val result = classify("src/Foo.kt")
        assertTrue(result is LinkTarget.RelativePath)
        assertEquals("src/Foo.kt", (result as LinkTarget.RelativePath).path)
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
