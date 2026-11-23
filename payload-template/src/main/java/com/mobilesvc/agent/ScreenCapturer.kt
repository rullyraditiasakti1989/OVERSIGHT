package com.mobilesvc.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Screen capture through MediaProjection.
 *
 * The important thing to understand is what Android allows and what it does not:
 *
 * - A projection token **cannot** be created without a visible consent dialog. There is no
 *   permission, no manifest entry, no background path. The user taps "Start now", once.
 * - That consent survives until the projection is stopped or the process dies — it does not
 *   survive a reboot. So a captured screen is a session capability, and a fresh consent is needed
 *   after every restart.
 * - Since Android 14 (API 34) a `MediaProjection` may create **exactly one** `VirtualDisplay`
 *   for its lifetime, and `getMediaProjection` must be called only after the foreground service
 *   is already running with `mediaProjection` type. So the right shape is: one display, created
 *   once, kept alive, and frames pulled from it on demand — not a display per capture.
 *
 * That is why this class holds a session instead of doing a one-shot grab. Rebuilding a display
 * per cycle would throw on API 34+ after the first frame.
 */
class ScreenCapturer(private val context: Context) {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0

    val attached: Boolean get() = display != null

    /**
     * Opens the projection and creates the display.
     *
     * The order of operations is the whole reason this takes a callback, and it is the one thing
     * that cannot be reasoned out from the consent Intent alone:
     *
     * 1. `declareForeground()` — the service re-issues `startForeground` including the
     *    `mediaProjection` type. Android 14 requires the type to be declared *before* the token
     *    is exchanged, and `startForeground` is the only place to declare it.
     * 2. `getMediaProjection(resultCode, data)` — exchanges the consent token for a session.
     * 3. `createVirtualDisplay` — exactly once per session on API 34+.
     *
     * Getting 1 and 2 the wrong way round is silent, not loud: the platform refuses to hand out
     * the session and `getMediaProjection` returns null with no exception, so the collector
     * reports NO CONSENT forever and nothing in the log says why. That is exactly how this
     * failed on device, and why the order is spelled out here.
     */
    fun attach(resultCode: Int, data: Intent, declareForeground: () -> Boolean): Boolean {
        if (display != null) return true
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager ?: return false

        // Declared first. A failure here is logged and does not abort: the exchange is still
        // attempted so the reason lands in the log next to the foreground error.
        if (!declareForeground()) {
            android.util.Log.w(TAG, "foreground type declaration failed; exchanging token anyway")
        }

        val created = runCatching { manager.getMediaProjection(resultCode, data) }
            .onFailure { android.util.Log.e(TAG, "getMediaProjection threw (resultCode=$resultCode)", it) }
            .getOrNull()
        if (created == null) {
            // A null here means the token was already spent, the consent was cancelled, or the
            // foreground type was not in place at exchange time. The extras are logged because
            // "binder present or not" is the difference between those cases.
            val keys = runCatching { data.extras?.keySet()?.toList() }.getOrNull()
            android.util.Log.e(
                TAG,
                "getMediaProjection returned null (resultCode=$resultCode keys=$keys)"
            )
            return false
        }

        // Android 14 requires a registered callback before the first virtual display, and it
        // fires `onStop` when the user revokes from the status bar — without this the display
        // silently stops producing frames and every capture returns null.
        created.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                ScreenConsent.clear()
            }
        }, null)

        val metrics = screenMetrics()
        // Downscale at the display level, not after the fact: a 1080x2400 RGBA frame is 10 MB
        // and the encode is what costs battery. 720 wide is legible for anything a human reads.
        val scale = minOf(1f, 720f / metrics.widthPixels)
        width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtual = runCatching {
            created.createVirtualDisplay(
                "svc",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
        }.onFailure { android.util.Log.e(TAG, "createVirtualDisplay failed", it) }.getOrNull()

        if (virtual == null) {
            runCatching { created.stop() }
            imageReader.close()
            return false
        }

        projection = created
        reader = imageReader
        display = virtual
        return true
    }

    /**
     * Grabs the newest frame and writes it as a JPEG.
     *
     * Returns null when the projection has no frame yet — which is a real state on the first
     * cycle, not an error. The caller reports "NO FRAME", never an empty image.
     */
    fun grab(quality: Int = 70): File? {
        val imageReader = reader ?: return null
        var frame: Image? = null
        // The display produces frames continuously; a short spin covers the first-frame delay
        // without blocking the service loop for a whole cadence interval.
        var attempts = 0
        while (frame == null && attempts < 15) {
            frame = runCatching { imageReader.acquireLatestImage() }.getOrNull()
            if (frame == null) {
                attempts++
                Thread.sleep(100)
            }
        }
        val image = frame ?: return null

        return try {
            val bitmap = toBitmap(frame)
            val scaled = if (bitmap.width > 720) {
                val ratio = 720f / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap,
                    720,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== bitmap) bitmap.recycle() }
            } else {
                bitmap
            }
            val target = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(target).use {
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }
            scaled.recycle()
            target.takeIf { it.length() > 0 }
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { frame.close() }
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val padding = rowStride - pixelStride * image.width

        // copyPixelsFromBuffer needs a bitmap wide enough to hold the row padding; the padded
        // bitmap is then cropped. Skipping this yields a sheared image, not a crash.
        val padded = Bitmap.createBitmap(
            image.width + padding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val window = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (window != null) {
            @Suppress("DEPRECATION")
            window.defaultDisplay.getRealMetrics(metrics)
        } else {
            metrics.widthPixels = 1080
            metrics.heightPixels = 2400
            metrics.densityDpi = 420
        }
        return metrics
    }

    /** Drops clips and the display. Called when the loop stops or the user revokes. */
    fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    companion object {
        private const val TAG = "OversightPayload"

        /** Drops screenshots older than an hour so a failed upload cannot accumulate. */
        fun purge(context: Context) {
            val floor = System.currentTimeMillis() - 3_600_000L
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("shot_") && it.lastModified() < floor }
                ?.forEach { it.delete() }
        }

        /** True when this build of Android even has a workable path. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}

/**
 * Hand-off for the consent result.
 *
 * `MediaProjectionManager.getMediaProjection` needs the `resultCode` and `Intent` the consent
 * activity returned. Those cannot be serialised into the config asset — the token is bound to
 * the requesting process and dies with it. So the Activity parks them here, in the same process
 * as the service, and the service picks them up on its next cycle.
 */
object ScreenConsent {

    private var resultCode: Int = 0
    private var data: Intent? = null

    fun store(code: Int, intent: Intent) {
        resultCode = code
        data = intent
    }

    fun has(): Boolean = data != null

    fun take(): Pair<Int, Intent>? {
        val intent = data ?: return null
        return resultCode to intent
    }

    fun clear() {
        resultCode = 0
        data = null
    }
}
