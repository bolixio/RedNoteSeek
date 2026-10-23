package com.rednoteseek.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.ActionListSheet
import com.rednoteseek.designsystem.component.BsConfirmDialog
import com.rednoteseek.designsystem.component.CompactDragHandle
import com.rednoteseek.designsystem.component.SheetAction
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/** 换专辑 / 移出专辑都要先取消收藏再重新收藏，执行前确认。 */
private enum class Recollect(val title: String, val confirm: String) {
    CHANGE("换专辑", "继续"),
    REMOVE("移出专辑", "移出"),
}

/**
 * 底栏的收藏项：未收藏时点击即收藏（随后选择专辑）；已收藏时从底部弹出操作列表：换专辑 / 移出专辑 / 取消收藏。
 * 收藏状态未知（详情未到）时只显示数字、不可点。[count] 是要显示的收藏数。
 */
@Composable
internal fun RowScope.CollectAction(
    state: NoteCollectViewModel.State,
    count: String,
    onCollect: () -> Unit,
    onChangeBoard: () -> Unit,
    onRemoveFromBoards: () -> Unit,
    onUncollect: () -> Unit,
) {
    val collected = state.collected
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Recollect?>(null) }
    BottomStat(
        icon = if (collected == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
        count = count,
        label = if (collected == true) "已收藏" else "收藏",
        tint = if (collected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        busy = state.busy,
        onClick = when {
            collected == null || state.busy -> null
            collected -> {
                { menu = true }
            }
            else -> onCollect
        },
    )
    if (menu) {
        ActionListSheet(
            title = "已收藏",
            actions = listOf(
                SheetAction("换专辑", Icons.AutoMirrored.Outlined.DriveFileMove) { confirm = Recollect.CHANGE },
                SheetAction("移出专辑", Icons.Outlined.FolderOff) { confirm = Recollect.REMOVE },
                SheetAction("取消收藏", Icons.Outlined.StarBorder, destructive = true, onClick = onUncollect),
            ),
            onDismiss = { menu = false },
        )
    }
    confirm?.let { action ->
        BsConfirmDialog(
            title = action.title,
            text = "网页版只能在收藏时选择专辑，所以会先取消收藏、再重新收藏：笔记会从原来的专辑移出，收藏时间变为现在。",
            confirmLabel = action.confirm,
            onConfirm = {
                confirm = null
                if (action == Recollect.CHANGE) onChangeBoard() else onRemoveFromBoards()
            },
            onDismiss = { confirm = null },
        )
    }
}

/** 「加入专辑」：列出网页面板里的专辑；关闭即不加入（笔记保持收藏）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardPickerSheet(boards: List<Board>, onPick: (index: Int, board: Board) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { CompactDragHandle() }) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = BsSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已收藏，加入专辑", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("不加入") }
            }
            if (boards.isEmpty()) {
                Text(
                    "还没有专辑，可以在「收藏」页新建",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(BsSpacing.lg),
                )
            }
            LazyColumn {
                itemsIndexed(boards, key = { _, b -> b.id }) { index, board ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index, board) }
                            .padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BsSpacing.md),
                    ) {
                        // 专辑第一张封面；没有封面时留底色
                        Box(
                            Modifier
                                .size(BsSize.avatar)
                                .clip(BsShapes.sm)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            board.images.firstOrNull()?.let { url ->
                                AsyncImage(
                                    model = XhsUrls.toHttps(url),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
                                Text(board.name, style = MaterialTheme.typography.bodyLarge)
                                if (board.privacy != 0) {
                                    Icon(
                                        Icons.Outlined.Lock,
                                        contentDescription = "私密",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(BsSize.iconXs),
                                    )
                                }
                            }
                            Text(
                                "${board.total} 篇笔记",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
