package com.vector.console.ui.components

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.vector.console.ui.theme.PanelDeep

/** Small in-memory cache so scrolling back does not restart the stream. */
object HlsFrameCache {
    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    fun get(url: String): Bitmap? = cache.get(url)
    fun put(url: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) cache.put(url, bitmap)
    }
}

/**
 * Shows a real frame from an HLS feed instead of an empty black box. Each
 * instance owns one player and releases it as soon as the first frame lands.
 */
@UnstableApi
@Composable
fun HlsPreview(
    url: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap()
) {
    var frame by remember(url) { mutableStateOf(HlsFrameCache.get(url)) }

    Box(modifier = modifier.background(PanelDeep)) {
        if (frame == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).apply {
                        var player: ExoPlayer? = null
                        var captured = false

                        fun capture() {
                            if (captured || !isAvailable) return
                            val bmp = bitmap
                            if (bmp == null || bmp.width == 0 || bmp.height == 0) return
                            HlsFrameCache.put(url, bmp)
                            frame = bmp
                            captured = true
                            player?.release()
                            player = null
                        }

                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                                val dataSource = DefaultHttpDataSource.Factory()
                                    .setDefaultRequestProperties(headers)
                                val source = HlsMediaSource.Factory(dataSource)
                                    .createMediaSource(MediaItem.fromUri(url))
                                player = ExoPlayer.Builder(context).build().apply {
                                    setMediaSource(source)
                                    setVideoSurface(Surface(s))
                                    playWhenReady = true
                                    addListener(object : Player.Listener {
                                        override fun onRenderedFirstFrame() {
                                            postDelayed({ capture() }, 200)
                                        }
                                    })
                                    prepare()
                                }
                                postDelayed({ capture() }, 8000)
                            }

                            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit

                            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                                player?.release()
                                player = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
                        }
                    }
                }
            )
        }

        frame?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
