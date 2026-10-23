package com.rednoteseek.feature.dots

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rednoteseek.core.web.DotsChatSource
import com.rednoteseek.core.web.DotsConversation
import com.rednoteseek.designsystem.component.ActionListSheet
import com.rednoteseek.designsystem.component.BsConfirmDialog
import com.rednoteseek.designsystem.component.BsEmptyState
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.component.SheetAction
import com.rednoteseek.designsystem.format.formatRelativeTime
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 点点会话历史：账号服务器上的会话（小红书 App 里删除也会同步），按网页给的分组（今天 / 7天内…）显示。
 * 点会话进入对话；长按或点「更多」可删除：先在 App 里确认，再去点网页侧边栏的 ×（网页没有确认框，不可恢复）。
 */
@Composable
fun DotsHistoryScreen(
    source: DotsChatSource,
    onOpen: (conversationId: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: DotsHistoryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DotsHistoryViewModel(source) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val deleting by vm.deleting.collectAsStateWithLifecycle()
    val showMessage = LocalShowMessage.current
    LaunchedEffect(vm) { vm.messages.collect { showMessage(it) } }
    var menuFor by remember { mutableStateOf<DotsConversation?>(null) }
    var confirmFor by remember { mutableStateOf<DotsConversation?>(null) }

    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "点点会话", onNavigate = onBack)
        when (val s = state) {
            DotsHistoryViewModel.State.Loading -> BsLoadingState()
            is DotsHistoryViewModel.State.Error -> BsErrorState(s.message, onRetry = vm::load)
            is DotsHistoryViewModel.State.Loaded ->
                if (s.groups.isEmpty()) {
                    BsEmptyState("还没有点点会话", icon = Icons.Outlined.AutoAwesome)
                } else {
                    // 相对时间按列表到达那一刻算
                    val now = remember(s) { System.currentTimeMillis() }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = BsSpacing.sm)) {
                        s.groups.forEachIndexed { index, group ->
                            if (group.title.isNotEmpty()) {
                                item(key = "group:$index") {
                                    Text(
                                        group.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
                                    )
                                }
                            }
                            items(group.items, key = { it.id }) { c ->
                                ConversationRow(
                                    conversation = c,
                                    now = now,
                                    onClick = { onOpen(c.id, c.title) },
                                    onMore = { menuFor = c },
                                )
                            }
                        }
                    }
                }
        }
    }

    menuFor?.let { c ->
        ActionListSheet(
            actions = listOf(SheetAction("删除", Icons.Outlined.DeleteOutline, destructive = true) { confirmFor = c }),
            onDismiss = { menuFor = null },
            title = c.title.ifBlank { "未命名会话" },
        )
    }
    confirmFor?.let { c ->
        BsConfirmDialog(
            title = "删除会话",
            text = "删除后无法恢复，小红书 App 里也会一并删除",
            confirmLabel = "删除",
            onConfirm = { vm.delete(c) { confirmFor = null } },
            onDismiss = { confirmFor = null },
            destructive = true,
            busy = deleting == c.id,
        )
    }
}

/** 一个会话：标题 + 更新时间；整行点击进入，长按或点「更多」弹出操作菜单。 */
@Composable
private fun ConversationRow(
    conversation: DotsConversation,
    now: Long,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = BsSpacing.lg, top = BsSpacing.xs, bottom = BsSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
            Text(
                conversation.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val time = formatRelativeTime(conversation.updatedAt, now)
            if (time.isNotEmpty()) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
