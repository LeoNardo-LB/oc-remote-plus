package dev.leonardo.ocremotev2.domain.model

/**
 * Classifies a URL string from a markdown `[text](url)` link into one of three types.
 * Pure Kotlin — no Android dependencies.
 */
sealed interface LinkTarget {
    /** Web URL: http://, https://, ftp://, mailto: */
    data class Web(val url: String) : LinkTarget

    /** Path relative to the session working directory: src/Foo.kt, ../docs/api.md */
    data class RelativePath(val path: String) : LinkTarget

    /** Absolute path: /home/user/Foo.kt or C:\Users\Foo.kt */
    data class AbsolutePath(val path: String) : LinkTarget
}

object LinkClassifier {
    private val windowsAbsoluteRegex = Regex("[A-Za-z]:[\\\\/].*")

    fun classify(url: String): LinkTarget = when {
        url.startsWith("http://") ||
        url.startsWith("https://") ||
        url.startsWith("ftp://") ||
        url.startsWith("mailto:") -> LinkTarget.Web(url)

        url.startsWith("/") -> LinkTarget.AbsolutePath(url)

        windowsAbsoluteRegex.matches(url) -> LinkTarget.AbsolutePath(url)

        else -> LinkTarget.RelativePath(url)
    }
}
