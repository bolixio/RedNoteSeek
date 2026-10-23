package com.rednoteseek.feature.detail

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage

/**
 * 视频播放器，基于 Media3 ExoPlayer。
 *
 * - 自动播放（muted=false）；用户可点击 ▶︎ / ⏸ 按钮控制
 * - 进入 detail 时自动 init，离开时自动 release
 * - url 来自 NoteVideo.playableUrl()（v1/feed 的 video.media.stream，优先 mp4）
 * - 第一帧渲染出来之前在上面盖着封面 [coverUrl]，避免从预览切过来时黑屏闪一下
 */
@Composable
fun VideoPlayer(
    url: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

    Box(modifier) {
        PlayerSurface(exoPlayer)
        if (!firstFrameShown && coverUrl != null) {
            AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PlayerSurface(exoPlayer: ExoPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true   // 显示原生控制条
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
