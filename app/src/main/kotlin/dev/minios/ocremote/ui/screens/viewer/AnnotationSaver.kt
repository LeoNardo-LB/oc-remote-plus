package dev.minios.ocremote.ui.screens.viewer

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.minios.ocremote.domain.model.Annotation

/**
 * Saver for `List<Annotation>` — survives configuration changes (rotation).
 *
 * Placed in the UI layer (not `domain/model`) to keep domain pure-Kotlin
 * and free of Compose dependencies. Each annotation is flattened to 11
 * primitive values, then chunked back on restore. Bundled via
 * `rememberSaveable` or used standalone for testing.
 */
val AnnotationListSaver = listSaver<List<Annotation>, Any>(
    save = { list ->
        list.flatMap { ann ->
            listOf(
                ann.id, ann.index, ann.startChar, ann.endChar,
                ann.startLine, ann.startCol, ann.endLine, ann.endCol,
                ann.selectedText, ann.note, ann.createdAt
            )
        }
    },
    restore = { saved ->
        saved.chunked(11).map { items ->
            Annotation(
                id = items[0] as String,
                index = items[1] as Int,
                startChar = items[2] as Int,
                endChar = items[3] as Int,
                startLine = items[4] as Int,
                startCol = items[5] as Int,
                endLine = items[6] as Int,
                endCol = items[7] as Int,
                selectedText = items[8] as String,
                note = items[9] as String,
                createdAt = items[10] as Long
            )
        }
    }
)
