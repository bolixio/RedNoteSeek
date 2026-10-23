package com.rednoteseek.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 瀑布流里的一张笔记卡片：封面（按 [coverAspect]，默认 3:4）、视频角标、两行标题、作者头像与昵称，
 * 右下角是点赞数，或 [time] 非空时显示时间（动态页按时间排）。地址需由调用方升级为 https。
 */
@Composable
fun NoteGridCard(
    title: String,
    coverUrl: String,
    authorName: String,
    authorAvatar: String,
    likes: String,
    isVideo: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverAspect: Float = 3f / 4f,
    time: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BsShapes.md)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(coverAspect)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isVideo) {
                Surface(
                    shape = BsShapes.xs,
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(BsSpacing.xs),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "视频",
                        modifier = Modifier.padding(BsSpacing.xxs).size(BsSize.iconSm),
                    )
                }
            }
        }
        Column(Modifier.padding(BsSpacing.sm), verticalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = authorAvatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(BsSize.iconSm)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(BsSpacing.xs))
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (time == null) {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(BsSize.iconXs),
                    )
                    Spacer(Modifier.width(BsSpacing.xxs))
                }
                Text(
                    time ?: likes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
