package org.koitharu.kotatsu.core.logs

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_ENTRIES = 4000

@Singleton
class AppLogger @Inject constructor() {

	@Volatile
	var isEnabled: Boolean = false
		private set

	private val buffer = ArrayBlockingQueue<String>(MAX_ENTRIES)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var readerJob: Job? = null

	fun setEnabled(enabled: Boolean) {
		if (enabled == isEnabled) return
		isEnabled = enabled
		if (enabled) {
			buffer.clear()
			startReading()
		} else {
			stopReading()
		}
	}

	/** Drains the current buffer to a single string. */
	fun drainToString(): String {
		val lines = ArrayList<String>(buffer.size)
		buffer.drainTo(lines)
		return lines.joinToString("\n")
	}

	private fun startReading() {
		readerJob = scope.launch {
			try {
				Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
				val pid = android.os.Process.myPid().toString()
				val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "--pid", pid))
				val reader = BufferedReader(InputStreamReader(process.inputStream))
				try {
					while (isActive) {
						val line = reader.readLine() ?: break
						if (!buffer.offer(line)) {
							buffer.poll()
							buffer.offer(line)
						}
					}
				} finally {
					reader.close()
					process.destroy()
				}
			} catch (e: Exception) {
				Log.e("AppLogger", "Failed to read logcat", e)
			}
		}
	}

	private fun stopReading() {
		val job = readerJob ?: return
		readerJob = null
		runBlocking { job.cancelAndJoin() }
	}
}
