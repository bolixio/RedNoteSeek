package com.rednoteseek.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 详情底栏：点赞数（只显示）· 收藏（[collect]）· 评论数（点了滚到评论区）。
 * [onCommentsClick] 为 null 表示详情未到：列表卡片上没有评论数，只显示图标、不可点。
 */
@Composable
internal fun NoteBottomBar(card: NoteCard, onCommentsClick: (() -> Unit)?, collect: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                BottomStat(Icons.Outlined.FavoriteBorder, card.interact_info.liked_count, label = "点赞数")
                collect()
                BottomStat(
                    Icons.Outlined.ChatBubbleOutline,
                    if (onCommentsClick != null) card.interact_info.comment_count else "",
                    label = "评论",
                    onClick = onCommentsClick,
                )
            }
        }
    }
}

/** 底栏的一项：图标 + 数字，占三分之一宽；[busy] 时图标换成转圈。 */
@Composable
internal fun RowScope.BottomStat(
    icon: ImageVector,
    count: String,
    label: String,
    onClick: (() -> Unit)? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    busy: Boolean = false,
) {
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = BsSize.touchTarget)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$label $count" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs, Alignment.CenterHorizontally),
    ) {
        if (busy) BsInlineProgress() else Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(BsSize.icon))
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
