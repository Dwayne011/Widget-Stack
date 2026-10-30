package dev.widgetstack.spike

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single logging entry point for the spike.
 *
 * Every line goes to three places:
 *  1. Logcat under the tag [TAG] (filter with `adb logcat -s WidgetStack`).
 *  2. A rolling in-memory buffer shown live in [MainActivity].
 *  3. `files/spike.log` so the "Share log" button can export a full session.
 *
 * Exceptions are logged with their full stack trace, because most of what this
 * spike needs to learn arrives as an exception from AppWidgetService or RemoteViews.
 */
object SpikeLog {
    const val TAG = "WidgetStack"
    private const val MAX_LINES = 2000

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "spike.log")
        i("log", "SpikeLog initialised, file=${file?.absolutePath}")
    }

    fun d(scope: String, msg: String) = write(Log.DEBUG, scope, msg, null)
    fun i(scope: String, msg: String) = write(Log.INFO, scope, msg, null)
    fun w(scope: String, msg: String, t: Throwable? = null) = write(Log.WARN, scope, msg, t)
    fun e(scope: String, msg: String, t: Throwable? = null) = write(Log.ERROR, scope, msg, t)

    /** Runs [block], logging its duration, and rethrows after logging any failure. */
    inline fun <T> timed(scope: String, what: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            val r = block()
            d(scope, "$what took ${(System.nanoTime() - start) / 1_000_000} ms")
            return r
        } catch (t: Throwable) {
            e(scope, "$what FAILED after ${(System.nanoTime() - start) / 1_000_000} ms", t)
            throw t
        }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun logFile(): File? = file

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun write(level: Int, scope: String, msg: String, t: Throwable?) {
        val levelChar = when (level) {
            Log.DEBUG -> 'D'; Log.INFO -> 'I'; Log.WARN -> 'W'; else -> 'E'
        }
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(levelChar).append(" [").append(scope).append("] ").append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
        }
        Log.println(level, TAG, "[$scope] $msg" + (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        try { file?.appendText(line + "\n") } catch (_: Exception) { /* never let logging crash the app */ }
        listeners.forEach { it() }
    }
}
