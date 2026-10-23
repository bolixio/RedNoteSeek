package com.rednoteseek.feature.profile

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.storage.dynamic.DynamicCacheRepository
import com.rednoteseek.core.storage.follow.FollowEntity
import com.rednoteseek.core.storage.follow.FollowRepository
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsEmptyState
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.format.formatRelativeTime
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/** 动态：账号关注作者的最新笔记。下拉刷新时依次访问有未看笔记的作者主页（限频，较慢）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicScreen(
    source: NoteListSource,
    follows: FollowRepository,
    cache: DynamicCacheRepository,
    selfUserId: String,
    onNoteClick: (noteId: String, order: List<String>, preview: NoteCard?) -> Unit,
    onAuthorClick: (url: String) -> Unit,
) {
    val vm: DynamicViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DynamicViewModel(source, follows, cache, selfUserId, SystemClock::elapsedRealtime) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is DynamicViewModel.Effect.OpenNote ->
                    onNoteClick(e.noteId, e.order, state.feed.firstOrNull { it.note.note_id == e.noteId }?.note?.toPreviewCard())
            }
        }
    }
    // 离开本页就停止刷新：第二引擎要让给主页 / 收藏 / 笔记详情
    // 导航一开始本页就离开 RESUMED（早于转场动画结束时的销毁），在这里暂停，避免与下一页争用第二引擎；
    // 销毁时再暂停一次兜底
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.pause() }
    // 卡片上的相对时间以进入页面时为准
    val now = remember { System.currentTimeMillis() }
    DisposableEffect(vm) { onDispose { vm.pause() } }

    Column(Modifier.fillMaxSize()) {
        BsTopBar(
            title = "动态",
            onNavigate = null,
            subtitle = when {
                !state.refreshing -> null
                state.checkingUnread -> "正在读取未看笔记数"
                else -> "正在更新 ${state.done}/${state.total}"
            },
        )
        if (state.authors.isEmpty()) {
            BsEmptyState("还没有关注的人\n在作者主页点「关注」后，回来下拉刷新", icon = Icons.Outlined.GroupAdd)
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = BsSpacing.sm, vertical = BsSpacing.sm),
                verticalItemSpacing = BsSpacing.sm,
                horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
            ) {
                item(key = "authors", span = StaggeredGridItemSpan.FullLine) {
                    AuthorRow(state.authors, state.unread, onAuthorClick)
                }
                val hint = state.message ?: if (state.feed.isEmpty() && !state.refreshing) "下拉刷新，读取关注作者的最新笔记" else null
                if (hint != null) {
                    item(key = "hint", span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(BsSpacing.sm),
                        )
                    }
                }
                items(state.feed, key = { it.note.note_id }) { item ->
                    Box {
                        PostedNoteCard(
                            note = item.note,
                            onClick = { vm.openNote(item) },
                            time = formatRelativeTime(item.note.time, now).ifEmpty { null },
                        )
                        // 正在打开：整张卡片盖一层遮罩 + 小转圈（要先把第二引擎带到作者主页，可能要几秒）
                        if (state.openingNoteId == item.note.note_id) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(BsShapes.md)
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = OpeningScrimAlpha)),
                                contentAlignment = Alignment.Center,
                            ) {
                                BsInlineProgress()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 关注作者横排：头像（右上角是未看笔记数）+ 昵称，点击进入其主页（取消关注在主页顶栏）。 */
@Composable
private fun AuthorRow(authors: List<FollowEntity>, unread: Map<String, Int>, onAuthorClick: (url: String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.md),
        contentPadding = PaddingValues(horizontal = BsSpacing.xs, vertical = BsSpacing.xs),
    ) {
        items(authors, key = { it.userId }) { author ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onAuthorClick(author.profileUrl) },
            ) {
                val count = unread[author.userId] ?: 0
                BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                    AsyncImage(
                        model = XhsUrls.toHttps(author.avatar),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(BsSize.avatarMd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Text(
                    author.nickname,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = BsSize.avatarMd * 1.5f),
                )
            }
        }
    }
}

private const val OpeningScrimAlpha = 0.4f
