package com.rednoteseek.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rednoteseek.designsystem.format.mediaAspect
import com.rednoteseek.designsystem.theme.BsSpacing

/** 参考来源里的一篇笔记（点点总结 / 点点回答的来源，由各页面从接口模型转换而来）。 */
data class SourceCard(
    val id: String,
    val title: String,
    val coverUrl: String,
    val coverWidth: Int,
    val coverHeight: Int,
    val author: String,
    val avatar: String,
    val likes: Int,
    val isVideo: Boolean,
)

/** 参考来源弹层的内容：加载中 / 笔记列表 / 加载失败。 */
sealed interface NoteSourcesState {
    data object Loading : NoteSourcesState
    data class Ready(val notes: List<SourceCard>) : NoteSourcesState
    data object Failed : NoteSourcesState
}

/** 点点的参考来源。点笔记时网页那边点的是「参考来源」抽屉里的同一张卡片。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSourcesSheet(
    state: NoteSourcesState,
    onNoteClick: (note: SourceCard, order: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactDragHandle() },
    ) {
        Text(
            "参考来源",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
        )
        when (state) {
            NoteSourcesState.Loading -> Box(
                Modifier.fillMaxWidth().padding(BsSpacing.xl),
                contentAlignment = Alignment.Center,
            ) { BsInlineProgress() }
            NoteSourcesState.Failed -> Text(
                "加载失败，请关闭后重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(BsSpacing.lg),
            )
            is NoteSourcesState.Ready -> {
                val order = state.notes.map { it.id }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    contentPadding = PaddingValues(BsSpacing.sm),
                    verticalItemSpacing = BsSpacing.sm,
                    horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                ) {
                    items(state.notes, key = { it.id }) { n ->
                        NoteGridCard(
                            title = n.title,
                            coverUrl = n.coverUrl,
                            authorName = n.author,
                            authorAvatar = n.avatar,
                            likes = n.likes.toString(),
                            isVideo = n.isVideo,
                            onClick = { onNoteClick(n, order) },
                            coverAspect = mediaAspect(n.coverWidth, n.coverHeight),
                        )
                    }
                }
            }
        }
    }
}
