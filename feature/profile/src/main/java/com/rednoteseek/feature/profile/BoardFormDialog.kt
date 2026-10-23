package com.rednoteseek.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.designsystem.component.BsConfirmDialog
import com.rednoteseek.designsystem.component.CenterDialog
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 新建 / 编辑专辑的表单：名称（必填，最多 12 字，网页限制）、简介、是否公开。
 * [onDelete] 非空时按钮行最左显示「删除专辑」（先二次确认）。
 */
@Composable
internal fun BoardFormDialog(
    title: String,
    initial: BoardForm,
    busy: Boolean,
    onSave: (BoardForm) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial.name) }
    var desc by remember { mutableStateOf(initial.desc) }
    var public by remember { mutableStateOf(initial.public) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameError = when {
        name.isBlank() -> "请输入专辑名称"
        name.length > BoardForm.NAME_MAX -> "不能超过 ${BoardForm.NAME_MAX} 个字"
        else -> null
    }
    val nameFocus = remember { FocusRequester() }
    // 弹出即聚焦名称框，直接弹出键盘
    LaunchedEffect(Unit) { nameFocus.requestFocus() }
    CenterDialog(
        title = title,
        onDismiss = onDismiss,
        dismissible = !busy,
        buttons = {
            if (onDelete != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除专辑") }
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            TextButton(
                onClick = { onSave(BoardForm(name.trim(), desc.trim(), public)) },
                enabled = !busy && nameError == null,
            ) { Text(if (busy) "处理中…" else "完成") }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BsSpacing.sm)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                isError = nameError != null && name.isNotEmpty(),
                supportingText = { Text(nameError ?: "${name.length}/${BoardForm.NAME_MAX}") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
            )
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("简介（可不填）") },
                minLines = 2,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("公开专辑", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭后其他人无法查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = public, onCheckedChange = { public = it }, enabled = !busy)
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        BsConfirmDialog(
            title = "删除专辑",
            text = "专辑删除后无法恢复（笔记会继续保持收藏）。",
            confirmLabel = "删除",
            destructive = true,
            busy = busy,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
