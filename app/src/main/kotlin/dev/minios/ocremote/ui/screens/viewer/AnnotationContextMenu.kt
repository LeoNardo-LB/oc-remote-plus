package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalClipboardManager

/**
 * Adds "标注修改" item to the system text context menu via official
 * [Modifier.appendTextContextMenuComponents] API.
 *
 * When clicked: captures selected text via clipboard, strips line-number
 * gutter prefixes, and calls [onAnnotate].
 *
 * Usage: wrap content in `SelectionContainer`, apply this modifier to `Text`.
 *
 * @param onAnnotate callback with the captured selected text
 */
fun Modifier.annotationContextMenu(
    onAnnotate: (selectedText: String) -> Unit,
): Modifier = composed {
    val clipboard = LocalClipboardManager.current

    this.appendTextContextMenuComponents {
        item(
            key = AnnotationMenuKey,
            label = "标注修改",
        ) {
            // Clipboard capture: Android system copies selection to clipboard
            // when context menu is shown. Read it here.
            val selectedText = clipboard.getText()?.text.orEmpty()
            val cleaned = stripGutterNumbers(selectedText)
            if (cleaned.isNotBlank()) {
                onAnnotate(cleaned)
            }
            close()
        }
    }
}

/** Unique key for the annotation context menu item. */
private data object AnnotationMenuKey

/**
 * Strip line-number gutter prefixes from clipboard-captured text.
 * Gutter Text composables inside SelectionContainer may add line numbers
 * to the selected text. This regex removes "digits + whitespace" at line starts.
 */
internal fun stripGutterNumbers(text: String): String {
    return text.replace(Regex("(?m)^\\s*\\d+\\s"), "")
}
