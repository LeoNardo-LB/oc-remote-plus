package dev.minios.ocremote

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OC Remote Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {
    
    override fun onCreate() {
        super.onCreate()

        // Crash log path: /Download/oc_remote_crash.txt
        val crashLogFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "oc_remote_crash.txt")

        // ---- Global uncaught exception handler ----
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashLogFile.parentFile?.mkdirs()
                crashLogFile.writeText(buildString {
                    append("App: OC Remote dev debug\n")
                    append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
                    append("Thread: $thread\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n\n")
                    append("--- Stack Trace ---\n")
                    append(StringWriter().also { throwable.printStackTrace(java.io.PrintWriter(it)) }.toString())

                    var cause = throwable.cause
                    var depth = 1
                    while (cause != null && depth < 5) {
                        append("\n--- Cause $depth ---\n")
                        append("Exception: ${cause.javaClass.name}\n")
                        append("Message: ${cause.message}\n")
                        append(StringWriter().also { cause.printStackTrace(java.io.PrintWriter(it)) }.toString())
                        cause = cause.cause
                        depth++
                    }
                })
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ---- Notify user on next launch ----
        if (crashLogFile.exists()) {
            try {
                val crashLog = crashLogFile.readText()
                Log.e("P0-1-CRASH", "Previous crash found:\n$crashLog")
                Toast.makeText(this, "崩溃日志已保存到 Download/oc_remote_crash.txt", Toast.LENGTH_LONG).show()
                crashLogFile.renameTo(File(crashLogFile.parent, "oc_remote_crash_read.txt"))
            } catch (e: Exception) {
                Log.e("P0-1-CRASH", "Failed to read crash log", e)
            }
        }
    }
}
