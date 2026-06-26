package org.koitharu.kotatsu.core.logs

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_ENTRIES = 2000
private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

@Singleton
class AppLogger @Inject constructor() {

	@Volatile
	var isEnabled: Boolean = false
		private set

	private val buffer = ArrayBlockingQueue<String>(MAX_ENTRIES)

	fun setEnabled(enabled: Boolean) {
		isEnabled = enabled
		if (!enabled) return
		buffer.clear()
		append("I", "AppLogger", "Verbose logging started")
	}

	fun log(priority: Int, tag: String?, message: String, throwable: Throwable? = null) {
		if (!isEnabled) return
		val level = when (priority) {
			Log.VERBOSE -> "V"
			Log.DEBUG   -> "D"
			Log.INFO    -> "I"
			Log.WARN    -> "W"
			Log.ERROR   -> "E"
			Log.ASSERT  -> "A"
			else        -> "?"
		}
		val body = if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message
		append(level, tag ?: "?", body)
	}

	/** Drains the current buffer to a single string without clearing the preference state. */
	fun drainToString(): String {
		val lines = ArrayList<String>(buffer.size)
		buffer.drainTo(lines)
		return lines.joinToString("\n")
	}

	private fun append(level: String, tag: String, message: String) {
		val line = "${DATE_FORMAT.format(Date())} $level/$tag: $message"
		if (!buffer.offer(line)) {
			buffer.poll()
			buffer.offer(line)
		}
	}
}
