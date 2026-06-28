package dev.leonardo.ocremotev2.ui.screens.viewer

enum class FileType {
    TEXT,
    MARKDOWN,
    IMAGE,
    SVG,
    CSV,
    JSON;

    val supportsRender: Boolean get() = this != TEXT

    companion object {
        private val EXT_MAP: Map<String, FileType> = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
            "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE,
            "gif" to IMAGE, "webp" to IMAGE, "bmp" to IMAGE,
            "svg" to SVG,
            "csv" to CSV, "tsv" to CSV,
            "json" to JSON
        )

        fun fromExtension(filePath: String): FileType =
            EXT_MAP[filePath.substringAfterLast('.', "").lowercase()] ?: TEXT
    }
}
