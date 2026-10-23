package com.rednoteseek.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSpacing

/** 底部弹层的拖动条：M3 默认上下各留 22dp，这里压到 8dp + 4dp 的条 + 8dp（与 BiliSeek 一致）。所有 ModalBottomSheet 都用它。 */
@Composable
fun CompactDragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = BsSpacing.sm), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = HandleWidth, height = HandleHeight)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), BsShapes.pill),
        )
    }
}

private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
