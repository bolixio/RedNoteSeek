package com.rednoteseek.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.XhsComment
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.format.formatRelativeTime
import com.rednoteseek.designsystem.theme.BsSpacing

/** 一条根评论：头像 ｜ 昵称 / 内容 / 时间 · 属地 + 点赞数，下面缩进楼中楼。[now] 用于相对时间。 */
@Composable
internal fun CommentItem(comment: XhsComment, now: Long, expanding: Boolean, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm)) {
        AsyncImage(
            model = comment.userInfo.image.let(XhsUrls::toHttps),
            contentDescription = null,
            modifier = Modifier.size(BsSize.avatarSm).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(BsSpacing.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
            Text(
                comment.userInfo.nickname,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EmojiText(comment.content, style = MaterialTheme.typography.bodyMedium)
            CommentMeta(comment, now)
            if (comment.subComments.isNotEmpty() || (comment.subCommentCount.toIntOrNull() ?: 0) > 0) {
                SubComments(comment, now, expanding, onExpand)
            }
        }
    }
}

/** 时间 · 属地（左）与点赞数（右）。 */
@Composable
private fun CommentMeta(comment: XhsComment, now: Long) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(formatRelativeTime(comment.createTime, now), comment.ipLocation).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.FavoriteBorder, contentDescription = "点赞数", modifier = Modifier.size(BsSize.iconXs), tint = muted)
        if (comment.likeCount != "0") {
            Spacer(Modifier.width(BsSpacing.xxs))
            Text(comment.likeCount, style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** 楼中楼：每条「昵称 回复 被回复人：内容」，回复的是根评论作者时省略「回复 xx」；末尾「展开 N 条回复」。 */
@Composable
private fun SubComments(comment: XhsComment, now: Long, expanding: Boolean, onExpand: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = BsSpacing.xs), verticalArrangement = Arrangement.spacedBy(BsSpacing.sm)) {
        comment.subComments.forEach { sub ->
            val target = sub.targetComment?.userInfo?.takeIf { it.userId.isNotBlank() && it.userId != comment.userInfo.userId }
            val prefix = buildAnnotatedString {
                withStyle(SpanStyle(color = muted)) { append(sub.userInfo.nickname) }
                if (target != null) {
                    append(" 回复 ")
                    withStyle(SpanStyle(color = muted)) { append(target.nickname) }
                }
                append("：")
            }
            Column(verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
                EmojiText(sub.content, style = MaterialTheme.typography.bodySmall, prefix = prefix)
                CommentMeta(sub, now)
            }
        }
        val total = comment.subCommentCount.toIntOrNull() ?: 0
        val remaining = total - comment.subComments.size
        if (comment.subCommentHasMore || remaining > 0) {
            if (expanding) {
                BsInlineProgress()
            } else {
                Text(
                    if (remaining > 0) "展开 $remaining 条回复" else "展开更多回复",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onExpand),
                )
            }
        }
    }
}
