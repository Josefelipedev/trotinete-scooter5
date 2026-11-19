package dev.josefelipe.trotinete.servico

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lasting diary of the proximity auto-lock: every lock and unlock with the readings behind it, link
 * changes, hand locks and unlocks, and a per-minute signal summary while connected. Logcat cannot do
 * this job: on a busy phone its ring buffer holds about a minute, so by the time someone asks "why
 * did it lock with me next to it?" the answer is gone.
 *
 * Kept in the app's private files and capped in size. Shown in Settings, and on debug builds also
 * readable from the PC: `adb shell run-as dev.josefelipe.trotinete cat files/autolock.log`.
 */
class AutoLockLog(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun write(line: String) {
        Log.i(TAG, "AUTO $line")
        val stamped = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())} $line\n"
        writer.execute {
            runCatching {
                file.appendText(stamped)
                if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(KEEP_CHARS).substringAfter('\n'))
            }.onFailure { Log.w(TAG, "Auto-lock log write failed", it) }
        }
    }

    /** The last [count] lines, newest first. Reads the file's tail: call it off the main thread. */
    fun latest(count: Int): List<String> = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val start = maxOf(0L, f.length() - TAIL_BYTES)
            val bytes = ByteArray((f.length() - start).toInt())
            f.seek(start)
            f.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }
            // a tail cut mid-file starts mid-line: drop that partial first line
            (if (start > 0) lines.drop(1) else lines).takeLast(count).asReversed()
        }
    }.getOrDefault(emptyList())

    companion object {
        const val FILE_NAME = "autolock.log"
        private const val TAG = "Trotinete"
        private const val MAX_BYTES = 512 * 1024L
        private const val KEEP_CHARS = 256 * 1024
        private const val TAIL_BYTES = 32 * 1024L

        /** One writer for every instance, so lines never interleave or race the trimming. */
        private val writer = Executors.newSingleThreadExecutor()
    }
}
