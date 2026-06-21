package dev.leonardo.ocremotev2.domain.model

import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationPromptBuilderTest {

    private val builder = AnnotationPromptBuilder

    private fun makeAnn(index: Int, sl: Int, sc: Int, el: Int, ec: Int, note: String) =
        Annotation("ann-$index", index, 0, 10, sl, sc, el, ec, "code", note, 1000L * index)

    @Test
    fun `single annotation with overall note`() {
        val result = builder.build(listOf(makeAnn(0, 12, 1, 13, 15, "fix this")),
            "请按标注修改", "src/App.kt", "/project")
        assertTrue(result.contains("修改意见：请按标注修改"))
        assertTrue(result.contains("文件名: /project/src/App.kt"))
        assertTrue(result.contains("1. 12:1 - 13:15 fix this"))
    }

    @Test
    fun `empty overall note fills with 无`() {
        val result = builder.build(listOf(makeAnn(0, 5, 1, 5, 10, "note")), "", "App.kt", "/proj")
        assertTrue(result.contains("修改意见：无"))
    }

    @Test
    fun `multiple annotations numbered by creation order`() {
        val anns = listOf(makeAnn(0, 45, 1, 45, 10, "first"), makeAnn(1, 12, 1, 13, 15, "second"))
        val result = builder.build(anns, "无", "App.kt", "/proj")
        assertTrue(result.contains("1. 45:1 - 45:10 first"))
        assertTrue(result.contains("2. 12:1 - 13:15 second"))
    }

    @Test
    fun `special characters preserved`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "改为\"正确\"的值")),
            "无", "App.kt", "/proj")
        assertTrue(result.contains("改为\"正确\"的值"))
    }

    @Test
    fun `relative path resolved with directory`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "src/main/App.kt", "/home/user/project")
        assertTrue(result.contains("文件名: /home/user/project/src/main/App.kt"))
    }

    @Test
    fun `absolute path used directly`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "/home/user/project/src/App.kt", "/home/user/project")
        assertTrue(result.contains("文件名: /home/user/project/src/App.kt"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty annotation list throws`() {
        builder.build(emptyList(), "n", "App.kt", "/proj")
    }

    @Test
    fun `windows drive letter path used directly`() {
        val result = builder.build(listOf(makeAnn(0, 1, 1, 1, 5, "n")), "无",
            "D:/Develop/App.kt", "D:/Develop")
        assertTrue(result.contains("文件名: D:/Develop/App.kt"))
    }
}
