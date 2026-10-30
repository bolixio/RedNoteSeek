package com.rednoteseek.feature.detail

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage

/**
 * 视频播放器，基于 Media3 ExoPlayer + TextureView。
 *
 * 不用 PlayerView/SurfaceView：SurfaceView 是独立窗口挖洞图层，在本 App（WebView 垫底 + Compose 导航）
 * 的图层结构里滚动/转场时洞和最后一帧不同步，出现画面残影遮挡（真机踩坑）。TextureView 是普通 View 层，
 * 严格跟随布局滚动与回收，无残影。
 *
 * - 自动播放；进入 detail 自动 init，离开自动 release
 * - 第一帧渲染出来之前在上面盖着封面 [coverUrl]，避免黑屏闪一下
 * - 切后台自动暂停，回前台恢复（音视频行为与官方客户端一致）
 */
@Composable
fun VideoPlayer(
    url: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = autoPlay
        }
    }

    var firstFrameShown by remember(url) { mutableStateOf(false) }
    DisposableEffect(url) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameShown = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 切后台自动暂停、回前台恢复
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> exoPlayer.playWhenReady = autoPlay
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            exoPlayer.setVideoSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            exoPlayer.setVideoSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrameShown && coverUrl != null) {
            AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}