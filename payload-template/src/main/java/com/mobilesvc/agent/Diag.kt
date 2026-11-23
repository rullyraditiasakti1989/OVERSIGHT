package com.mobilesvc.agent

import android.content.Context
import java.io.File

/**
 * A small on-device log that survives the process.
 *
 * `logcat` is useless for the two questions that actually matter after a restart — did the boot
 * broadcast arrive, and was the foreground start accepted — because the buffer rotates during
 * boot and a refused start kills the process that would have reported it. This writes the same
 * facts to internal storage so they can be read afterwards.
 *
 * Capped, and appended with a single short line per event: a payload that grows a log without a
 * ceiling is a payload that eventually fails an install on a full device.
 */
object Diag {

    private const val FILE = "diag.log"
    private const val LIMIT = 64_000L

    fun log(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE)
            if (file.length() > LIMIT) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
}
