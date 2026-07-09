package dev.leonardo.ocremotev2.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger for SSE scroll-stability investigation. Outputs to logcat
 * (tag "ScrollDiag") for easy `adb logcat -s ScrollDiag` capture.
 *
 * TEMPORARY — remove after root cause is confirmed. See
 * docs/research/sse-scroll-stability-iron-laws.md.
 */
object ScrollDiagLogger {
    private const val TAG = "ScrollDiag"
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: android.content.Context) { /* no-op, logcat only */ }
    fun reset() { Log.d(TAG, "RESET ScrollDiagLogger") }

    fun log(event: String, vararg kv: Any) {
        val sb = StringBuilder("${timeFmt.format(Date())} $event")
        var i = 0
        while (i < kv.size) {
            sb.append(' ').append(kv[i]).append('=')
            i++
            if (i < kv.size) { sb.append(kv[i]); i++ }
        }
        Log.d(TAG, sb.toString())
    }
}
