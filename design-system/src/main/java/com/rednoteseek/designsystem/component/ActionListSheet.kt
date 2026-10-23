package com.rednoteseek.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.rednoteseek.designsystem.theme.BsSpacing

/** 底部操作列表的一项。[destructive] 的项文字与图标用错误色（删除、取消收藏这类）。 */
data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 「更多操作」/ 长按菜单：底部弹层，每项图标 + 文字；点一项先关闭弹层再执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionListSheet(actions: List<SheetAction>, onDismiss: () -> Unit, title: String? = null) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = BsSpacing.sm)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.sm),
                )
            }
            actions.forEach { action ->
                val error = MaterialTheme.colorScheme.error
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BsSpacing.lg),
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = if (action.destructive) error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
