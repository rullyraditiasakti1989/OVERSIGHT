package com.vector.console.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The globe surface: one `WebView` drawing a sphere with MapLibre GL JS.
 *
 * Three decisions are load-bearing and are why this is hand-rolled rather than
 * an `AndroidView` around a library:
 *
 *  - **No Maven dependency.** `WebViewAssetLoader` lives in `androidx.webkit`,
 *    which is not on the classpath and cannot be fetched offline. The asset
 *    origin is served by a `shouldInterceptRequest` hook instead — the same
 *    mechanism, forty lines, no download.
 *  - **A real https origin.** ES modules and workers are refused from `file://`
 *    and from `loadDataWithBaseURL`, so the page is served from
 *    `https://appassets.androidplatform.net/globe/` and intercepted. That origin
 *    is fictional: nothing leaves the process to answer it.
 *  - **Ink before pixels.** The view, the page and the style's background layer
 *    are all set to `Ink`, so the surface never flashes white while tiles load.
 */
private const val ORIGIN = "https://appassets.androidplatform.net"
private const val ASSET_ROOT = "globe"
private const val ENTRY = "$ORIGIN/$ASSET_ROOT/index.html"
private const val BRIDGE = "OVSBridge"
private const val INK_ARGB = 0xFF060708.toInt()

/** What the renderer tells the screen. Nothing here is inferred by Compose. */
sealed interface GlobeEvent {
    /** The sphere is drawn. [ms] is measured from module start, not from Kotlin. */
    data class Ready(val ms: Long) : GlobeEvent

    data class Camera(
        val lat: Double,
        val lon: Double,
        val zoom: Double,
        val bearing: Double,
        val pitch: Double
    ) : GlobeEvent

    data class Picked(val layer: String, val id: String, val lat: Double, val lon: Double) : GlobeEvent

    /** A tap that hit no contact. Closes the card instead of leaving it stuck. */
    data object Cleared : GlobeEvent

    data class Failed(val message: String) : GlobeEvent
}

/**
 * Handle on the live renderer. Sends are fire-and-forget: the page may not have
 * loaded yet, and a message that arrives early is dropped rather than queued —
 * every sender re-sends on the next state change anyway.
 *
 * The one exception is the camera. The WebView can be re-attached when the
 * screen changes shape (portrait stack to landscape row), which reloads the
 * page and loses where the operator was looking. So the last camera the page
 * reported is kept here and restored on the next `ready`.
 */
class GlobeHandle internal constructor() {
    internal var webView: WebView? = null

    private var lastCamera: Triple<Double, Double, Double>? = null

    /** Called by the canvas when the page announces itself. */
    internal fun onReady() {
        val camera = lastCamera ?: return
        send(
            JSONObject()
                .put("t", "view")
                .put("lat", camera.first)
                .put("lon", camera.second)
                .put("z", camera.third)
                .toString()
        )
    }

    internal fun remember(lat: Double, lon: Double, zoom: Double) {
        // A zero camera is the page's initial paint, not a position.
        if (lat == 0.0 && lon == 0.0 && zoom == 0.0) return
        lastCamera = Triple(lat, lon, zoom)
    }

    private fun send(json: String) {
        val view = webView ?: return
        view.evaluateJavascript("window.ovsApply(${JSONObject.quote(json)})", null)
    }

    fun applyTheme(ink: String, panel: String, line: String, bone: String, seal: String) {
        send(
            JSONObject()
                .put("t", "theme")
                .put("ink", ink)
                .put("panel", panel)
                .put("line", line)
                .put("bone", bone)
                .put("seal", seal)
                .toString()
        )
    }

    /** Replace a GeoJSON source. Layers bound to it re-render on the next frame. */
    fun source(id: String, geojson: String) {
        send(JSONObject().put("t", "source").put("id", id).put("geojson", JSONObject(geojson)).toString())
    }

    /**
     * Replace a source with a pre-serialised FeatureCollection.
     *
     * [source] wraps the payload in a `JSONObject`, which parses and re-encodes
     * the whole document — 800 satellites is a megabyte of that, on the main
     * thread. Here the text crosses as a JSON string and the page parses it once.
     */
    fun features(id: String, geojson: String, color: String? = null) {
        val message = StringBuilder(64 + geojson.length)
        message.append("{\"t\":\"source\",\"id\":").append(JSONObject.quote(id))
        if (color != null) message.append(",\"color\":").append(JSONObject.quote(color))
        message.append(",\"data\":").append(JSONObject.quote(geojson)).append('}')
        send(message.toString())
    }

    fun layerVisible(id: String, visible: Boolean) {
        send(JSONObject().put("t", "layer").put("id", id).put("on", visible).toString())
    }

    fun flyTo(lat: Double, lon: Double, zoom: Double, ms: Int = 900) {
        send(
            JSONObject()
                .put("t", "fly")
                .put("lat", lat)
                .put("lon", lon)
                .put("z", zoom)
                .put("ms", ms)
                .toString()
        )
    }

    /** Highlights one contact, or clears the ring when [lat] is null. */
    fun focus(id: String?, lat: Double? = null, lon: Double? = null) {
        val message = JSONObject().put("t", "focus")
        if (id != null && lat != null && lon != null) {
            message.put("id", id).put("lat", lat).put("lon", lon)
        }
        send(message.toString())
    }
}

@Composable
fun rememberGlobeHandle(): GlobeHandle = remember { GlobeHandle() }

/**
 * The canvas. Sized by the caller; the WebView fills whatever it is given and is
 * told to resize when the bounds change, because a WebGL surface does not reflow.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlobeCanvas(
    handle: GlobeHandle,
    modifier: Modifier = Modifier,
    onEvent: (GlobeEvent) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ready by remember { mutableStateOf(false) }

    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(INK_ARGB)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // The page is served from an intercepted https origin; there is
                // no reason to hand it the filesystem or a content resolver.
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
                // MapLibre's bundle is large and minified; debugging stays off.
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            addJavascriptInterface(
                Bridge { event ->
                    when (event) {
                        // The theme message is only reliable once the page has
                        // loaded, so ready is what unblocks it — and it is also
                        // where a remembered camera is handed back.
                        is GlobeEvent.Ready -> {
                            ready = true
                            handle.onReady()
                        }
                        is GlobeEvent.Camera -> handle.remember(event.lat, event.lon, event.zoom)
                        else -> Unit
                    }
                    onEvent(event)
                },
                BRIDGE
            )
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("GlobeJS", "${message.message()} @${message.lineNumber()}")
                    return true
                }
            }
            webViewClient = AssetClient(context)
            loadUrl(ENTRY)
        }
    }

    // The handle outlives recomposition; the WebView instance is what it points at.
    DisposableEffect(webView) {
        handle.webView = webView
        onDispose {
            handle.webView = null
            webView.destroy()
        }
    }

    // A WebView keeps painting and running timers when the app goes to the
    // background. Both are stopped explicitly: the globe is not a reason to hold
    // a GPU or a CPU core while the phone is in a pocket.
    DisposableEffect(lifecycle, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    webView.pauseTimers()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.resumeTimers()
                    webView.onResume()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .matchParentSize()
                // A WebView keeps the drawing buffer it was last laid out at, so
                // a bounds change (rotation, expand) leaves the globe painted at
                // the old size. Laying the view out again is what actually
                // resizes the surface; the JS ResizeObserver then resizes the
                // map inside it. Both are needed: this one fixes the view, that
                // one fixes the canvas.
                .onSizeChanged { size ->
                    if (size.width > 0 && size.height > 0) {
                        webView.measure(
                            View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY)
                        )
                        webView.layout(0, 0, size.width, size.height)
                        webView.evaluateJavascript("window.ovsResize && window.ovsResize()", null)
                    }
                },
            onReset = { it.loadUrl(ENTRY) }
        )
    }

    // A ready page is the only moment a theme message is guaranteed to land.
    LaunchedEffect(ready) {
        if (ready) {
            handle.applyTheme(
                ink = "#060708",
                panel = "#0C0E10",
                line = "#3A444A",
                bone = "#F3F5F4",
                seal = "#E05A48"
            )
        }
    }
}

/** The JS-facing side of the bridge. Runs on a WebView thread, not the main one. */
private class Bridge(private val sink: (GlobeEvent) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun post(raw: String) {
        val event = decode(raw) ?: return
        // The sink mutates Compose state. WebView calls JavascriptInterface
        // methods on a background thread, and a snapshot written off the main
        // thread is not reliably observed — which showed up as a rendered globe
        // still reading ACQUIRING RASTER. Hop to the main thread.
        main.post { sink(event) }
    }

    private fun decode(raw: String): GlobeEvent? = try {
        val json = JSONObject(raw)
        when (json.optString("t")) {
            "ready" -> GlobeEvent.Ready(json.optLong("ms"))
            "camera" -> GlobeEvent.Camera(
                json.optDouble("lat"),
                json.optDouble("lon"),
                json.optDouble("z"),
                json.optDouble("b"),
                json.optDouble("p")
            )
            "pick" -> GlobeEvent.Picked(
                json.optString("layer"),
                json.optString("id"),
                json.optDouble("lat"),
                json.optDouble("lon")
            )
            "clear" -> GlobeEvent.Cleared
            "error" -> GlobeEvent.Failed(json.optString("message").ifEmpty { "renderer error" })
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Serves `assets/globe/` over the fictional origin.
 *
 * The MIME type is not cosmetic: a module script served as `text/plain` is
 * refused outright, and so is a worker. `.mjs` must come back as
 * `text/javascript` with a charset.
 */
private class AssetClient(private val context: Context) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url: Uri = request.url ?: return null
        if (!url.scheme.equals("https", true) || url.host != "appassets.androidplatform.net") return null

        val path = url.path?.trimStart('/') ?: return null
        if (!path.startsWith("$ASSET_ROOT/")) return notFound()
        val name = path.removePrefix("$ASSET_ROOT/")
        // Interpolation is not an escape hatch into the rest of the APK.
        if (name.isEmpty() || name.contains("..")) return notFound()

        return try {
            WebResourceResponse(
                mimeOf(name),
                if (isText(name)) "utf-8" else null,
                200,
                "OK",
                mapOf("Cache-Control" to "no-cache"),
                context.assets.open("$ASSET_ROOT/$name")
            )
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun isText(name: String) = !name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".webp")

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
