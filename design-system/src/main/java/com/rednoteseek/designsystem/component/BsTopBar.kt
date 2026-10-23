package com.rednoteseek.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

enum class BsNavIcon { Back, Close, None }

/** 统一顶栏：返回 / 关闭图标 + 标题（可带副标题）+ 右侧操作。 */
@Composable
fun BsTopBar(
    title: String,
    onNavigate: (() -> Unit)?,
    modifier: Modifier = Modifier,
    navIcon: BsNavIcon = BsNavIcon.Back,
    subtitle: String? = null,
    titleIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BsSize.topBar)
            .padding(horizontal = BsSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigate != null && navIcon != BsNavIcon.None) {
            IconButton(onClick = onNavigate) {
                when (navIcon) {
                    BsNavIcon.Close -> Icon(Icons.Filled.Close, contentDescription = "关闭")
                    else -> Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        } else {
            Spacer(Modifier.width(BsSpacing.md))
        }
        if (titleIcon != null) {
            Icon(
                titleIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = BsSpacing.xs).size(BsSize.icon),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = BsSpacing.xs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
