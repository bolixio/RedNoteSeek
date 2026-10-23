package com.rednoteseek.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 居中弹窗外壳，外观与 [BsConfirmDialog]（M3 AlertDialog）一致：大圆角、大标题、24dp 内边距。
 * 内容过长时只滚中间部分；底部按钮行由调用方放（默认靠右）。[dismissible] 为 false 时点空白 / 返回都关不掉。
 */
@Composable
fun CenterDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier
                .padding(horizontal = DialogMargin)
                .widthIn(min = DialogMinWidth, max = DialogMaxWidth),
        ) {
            Column(Modifier.padding(vertical = BsSpacing.xl)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = BsSpacing.xl),
                )
                Spacer(Modifier.height(BsSpacing.lg))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BsSpacing.xl),
                    content = content,
                )
                Spacer(Modifier.height(BsSpacing.xl))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = BsSpacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

// 与 M3 AlertDialog 相同的宽度范围
private val DialogMinWidth = 280.dp
private val DialogMaxWidth = 560.dp
private val DialogMargin = 24.dp
