package dev.leonardo.ocremotev2.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger for SSE scroll-stability investigation. Writes to BOTH logcat
 * (tag "ScrollDiag") AND a file in public Downloads: /sdcard/Download/scroll_diag.log
 *
 * Uses a ring buffer (max [MAX_LINES] lines, trims to [TRIM_TO] when exceeded) to bound
 * memory during high-frequency SSE token logging.
 *
 * TEMPORARY — remove after root cause is confirmed. See
 * docs/research/sse-scroll-stability-iron-laws.md.
 */
object ScrollDiagLogger {
    private const val TAG = "ScrollDiag"
    private const val FILE_NAME = "scroll_diag.log"
    private const val MAX_LINES = 3000
    private const val TRIM_TO = 2000

    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var ctx: Context? = null
    private var cachedUri: Uri? = null
    @Volatile private var enabled = false

    fun init(context: Context) {
        ctx = context.applicationContext
        enabled = true
    }

    /** Clear buffer + file — call when entering chat to get a fresh capture. */
    fun reset() {
        synchronized(lines) { lines.clear() }
        cachedUri = null
        deleteFile()
        log("INIT", "ScrollDiagLogger reset")
    }

    fun log(event: String, vararg kv: Any) {
        if (!enabled) return
        val sb = StringBuilder("${timeFmt.format(Date())} $event")
        var i = 0
        while (i < kv.size) {
            sb.append(' ').append(kv[i]).append('=')
            i++
            if (i < kv.size) {
                sb.append(kv[i])
                i++
            }
        }
        val line = sb.toString()
        Log.d(TAG, line.substringAfter(' '))  // logcat without timestamp prefix
        synchronized(lines) {
            lines.addLast(line)
            if (lines.size > MAX_LINES) {
                repeat(MAX_LINES - TRIM_TO) { lines.removeFirst() }
            }
        }
        flush()
    }

    private fun flush() {
        val context = ctx ?: return
        val content = synchronized(lines) { lines.joinToString("\n") }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                flushMediaStore(context, content)
            } else {
                flushLegacy(context, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
        }
    }

    private fun flushMediaStore(context: Context, content: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        if (cachedUri == null) {
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            resolver.query(collection, proj, sel, arrayOf(FILE_NAME), null)?.use { c ->
                if (c.moveToFirst()) {
                    cachedUri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                }
            }
            if (cachedUri == null) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                cachedUri = resolver.insert(collection, v)
            }
        }
        cachedUri?.let { uri ->
            resolver.openOutputStream(uri, "w")?.use { it.write(content.toByteArray()) }
        }
    }

    private fun flushLegacy(context: Context, content: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        java.io.FileOutputStream(java.io.File(dir, FILE_NAME)).use {
            it.write(content.toByteArray())
        }
    }

    private fun deleteFile() {
        val context = ctx ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(FILE_NAME)
                )
            } else {
                java.io.File(context.getExternalFilesDir(null), FILE_NAME).delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
        }
    }
}
