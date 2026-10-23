package com.rednoteseek.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.playableUrl
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.format.mediaAspect
import com.rednoteseek.designsystem.theme.BsSpacing

/** 笔记的图片地址；列表卡片没有图片列表时用封面。接口给的是 http 地址，App 禁止明文流量，需要升级为 https。 */
internal fun imageUrls(card: NoteCard): List<String> =
    card.image_list.ifEmpty { listOfNotNull(card.cover) }.mapNotNull { image ->
        image.url_default.ifBlank { image.info_list.firstOrNull()?.url.orEmpty() }.ifBlank { null }?.let(XhsUrls::toHttps)
    }

/** 媒体区比例：视频按流的宽高，图文按第一张图（列表卡片按封面）。 */
private fun cardAspect(card: NoteCard): Float {
    if (card.type == "video") {
        val stream = card.video?.media?.stream?.values?.flatten()?.firstOrNull { it.width > 0 && it.height > 0 }
        if (stream != null) return mediaAspect(stream.width, stream.height)
    }
    val first = card.image_list.firstOrNull() ?: card.cover
    return mediaAspect(first?.width ?: 0, first?.height ?: 0)
}

/**
 * 图片轮播（多图时下方圆点）或视频。高度按 [cardAspect]。
 * [placeholders] 是预览时已显示（已在内存缓存）的图片，垫在对应页下面，详情图片加载完成前不闪。
 * [onImageClick] 非空时点图片打开大图；[onSaveVideo] 非空时视频右上角有下载按钮。
 */
@Composable
internal fun NoteMedia(
    card: NoteCard,
    placeholders: List<String> = emptyList(),
    onImageClick: ((index: Int) -> Unit)? = null,
    videoSaving: Boolean = false,
    onSaveVideo: (() -> Unit)? = null,
) {
    val images = imageUrls(card)
    val aspect = cardAspect(card)
    if (card.type == "video") {
        val videoUrl = card.video?.playableUrl()?.let(XhsUrls::toHttps)
        if (videoUrl != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.Black)) {
                VideoPlayer(url = videoUrl, coverUrl = placeholders.firstOrNull() ?: images.firstOrNull(), modifier = Modifier.fillMaxSize())
                if (onSaveVideo != null) {
                    IconButton(
                        onClick = onSaveVideo,
                        enabled = !videoSaving,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White,
                            disabledContainerColor = Color.Black.copy(alpha = 0.5f),
                            disabledContentColor = Color.White,
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(BsSpacing.sm),
                    ) {
                        if (videoSaving) BsInlineProgress() else Icon(Icons.Outlined.Download, contentDescription = "保存视频")
                    }
                }
            }
            return
        }
    }
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { images.size })
    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().aspectRatio(aspect).background(MaterialTheme.colorScheme.surfaceContainer),
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (onImageClick != null) Modifier.clickable(onClickLabel = "查看大图") { onImageClick(page) } else Modifier),
            ) {
                placeholders.getOrNull(page)?.takeIf { it != images[page] }?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                AsyncImage(
                    model = images[page],
                    contentDescription = "第 ${page + 1} 张图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (images.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = BsSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs, Alignment.CenterHorizontally),
            ) {
                repeat(images.size) { index ->
                    Box(
                        Modifier
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
    }
}

private val DotSize = 6.dp
