package com.rednoteseek.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.NoteUser
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/** 详情顶栏：返回 + 作者头像与昵称（[onAuthorClick] 非空时整体可点，进入作者主页）。 */
@Composable
internal fun NoteTopBar(user: NoteUser?, onBack: () -> Unit, onAuthorClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = BsSize.topBar).padding(horizontal = BsSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        if (user != null) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .clip(BsShapes.pill)
                    .then(if (onAuthorClick != null) Modifier.clickable(onClickLabel = "查看作者主页", onClick = onAuthorClick) else Modifier)
                    .padding(BsSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = user.avatar.ifBlank { user.image }.let(XhsUrls::toHttps),
                    contentDescription = null,
                    modifier = Modifier.size(BsSize.avatarSm).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.width(BsSpacing.sm))
                Text(
                    user.nickname.ifEmpty { user.nick_name },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
