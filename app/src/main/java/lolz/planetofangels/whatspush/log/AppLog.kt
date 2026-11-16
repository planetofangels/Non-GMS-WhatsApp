package lolz.planetofangels.whatspush.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "WaPush"
    private const val MAX_BYTES = 256 * 1024L

    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "log.txt")
    }

    fun info(msg: String) = write(Log.INFO, "I", msg)
    fun warn(msg: String) = write(Log.WARN, "W", msg)
    fun error(msg: String) = write(Log.ERROR, "E", msg)

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    @Synchronized
    private fun write(priority: Int, level: String, msg: String) {
        Log.println(priority, TAG, msg)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText("${time.format(Date())} $level $msg\n")
        }
    }
}
