package com.rednoteseek.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.network.api.ProfileUser
import com.rednoteseek.core.storage.follow.FollowRepository
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsEmptyState
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsLoadMoreFooter
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.component.NoteGridCard
import com.rednoteseek.designsystem.format.mediaAspect
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 用户主页：资料头 + 该用户发布的笔记。数据来自第二网页引擎。 */
@Composable
fun ProfileScreen(
    url: String,
    source: NoteListSource,
    follows: FollowRepository,
    selfUserId: String,
    onNoteClick: (noteId: String, order: List<String>, preview: NoteCard?) -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberNoteListViewModel(source, NoteListViewModel.Target.Profile(url))
    val state by vm.state.collectAsStateWithLifecycle()
    val title = (state as? NoteListViewModel.State.Loaded)?.user?.nickname?.ifBlank { null } ?: "主页"
    val user = (state as? NoteListViewModel.State.Loaded)?.user
    val followed by remember(follows) { follows.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val following = user != null && followed.any { it.userId == user.user_id }
    var followBusy by remember { mutableStateOf(false) }
    val showMessage = LocalShowMessage.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BsTopBar(
            title = title,
            onNavigate = onBack,
            actions = {
                // 在账号上关注 / 取消关注（点网页自己的按钮），成功后写入本地关注表；资料加载出来后才能操作，自己的主页不显示
                if (user != null && user.user_id.isNotBlank() && user.user_id != selfUserId && XhsUrls.isProfileUrl(url)) {
                    val toggle: () -> Unit = {
                        followBusy = true
                        scope.launch {
                            val ok = source.setFollow(user.user_id, follow = !following)
                            if (ok && following) follows.unfollow(user.user_id)
                            else if (ok) follows.follow(user.user_id, user.nickname, user.avatar, url)
                            followBusy = false
                            if (!ok) showMessage(if (following) "取消关注失败，请稍后重试" else "关注失败，请稍后重试")
                        }
                    }
                    if (following) {
                        OutlinedButton(
                            onClick = toggle,
                            enabled = !followBusy,
                            modifier = Modifier.padding(end = BsSpacing.sm),
                        ) { Text("已关注") }
                    } else {
                        Button(
                            onClick = toggle,
                            enabled = !followBusy,
                            modifier = Modifier.padding(end = BsSpacing.sm),
                        ) { Text("关注") }
                    }
                }
            },
        )
        NoteListContent(
            state = state,
            vm = vm,
            emptyText = "还没有笔记",
            emptyIcon = Icons.Outlined.PhotoLibrary,
            onNoteClick = onNoteClick,
            header = { user -> user?.let { ProfileHeader(it) } },
        )
    }
}

/**
 * 每个页面各自的 ViewModel（以目标区分 key）；每次进入组合都调用 [NoteListViewModel.ensureOpen]，
 * 从笔记详情返回时据此判断第二引擎是否还停在本页。
 */
@Composable
internal fun rememberNoteListViewModel(source: NoteListSource, target: NoteListViewModel.Target): NoteListViewModel {
    val vm: NoteListViewModel = viewModel(
        key = target.toString(),
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteListViewModel(source, target) as T
        },
    )
    LaunchedEffect(Unit) { vm.ensureOpen() }
    return vm
}

/** 主页与收藏页共用：按状态显示加载 / 错误 / 空 / 瀑布流（可下拉刷新）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteListContent(
    state: NoteListViewModel.State,
    vm: NoteListViewModel,
    emptyText: String,
    onNoteClick: (noteId: String, order: List<String>, preview: NoteCard?) -> Unit,
    emptyIcon: ImageVector? = null,
    header: (@Composable (ProfileUser?) -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            NoteListViewModel.State.Loading -> BsLoadingState()
            NoteListViewModel.State.Empty -> BsEmptyState(emptyText, icon = emptyIcon)
            is NoteListViewModel.State.Error ->
                BsErrorState(state.message, onRetry = if (state.retryable) vm::retry else null)
            // 下拉刷新：重新加载网页，新的第一页到达前保留旧列表
            is NoteListViewModel.State.Loaded -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                NoteGrid(
                    state = state,
                    onLoadMore = vm::loadMore,
                    onRetry = vm::retryLoadMore,
                    onNoteClick = { note -> onNoteClick(note.note_id, state.notes.map { it.note_id }, note.toPreviewCard()) },
                    header = header?.let { h -> { h(state.user) } },
                )
            }
        }
    }
}

@Composable
private fun NoteGrid(
    state: NoteListViewModel.State.Loaded,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onNoteClick: (PostedNote) -> Unit,
    header: (@Composable () -> Unit)?,
) {
    val gridState = rememberLazyStaggeredGridState()
    // 滑到距底部 10 条以内时提前请求下一页（ViewModel 内限频），多数时候不必等到底部转圈
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .filter { (last, total) -> total > 0 && last >= total - 10 }
            .collect { onLoadMore() }
    }
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = BsSpacing.sm, vertical = BsSpacing.sm),
        verticalItemSpacing = BsSpacing.sm,
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
    ) {
        if (header != null) {
            item(key = "header", span = StaggeredGridItemSpan.FullLine) { header() }
        }
        items(state.notes, key = { it.note_id }) { note ->
            PostedNoteCard(note = note, onClick = { onNoteClick(note) })
        }
        item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
            BsLoadMoreFooter(
                isLoadingMore = state.hasMore && !state.loadMoreFailed,
                errorMessage = if (state.loadMoreFailed) "加载失败" else null,
                onRetry = onRetry,
                endText = if (state.hasMore) null else "没有更多了",
            )
        }
    }
}

/** 详情到达前先显示的内容：主页卡片只有封面、标题、作者与点赞数。 */
internal fun PostedNote.toPreviewCard() = NoteCard(
    type = type,
    display_title = display_title,
    user = user,
    interact_info = interact_info,
    cover = cover,
)

@Composable
internal fun PostedNoteCard(note: PostedNote, onClick: () -> Unit, time: String? = null) {
    // 接口给的是 http 地址，App 禁止明文流量，需要升级为 https
    val cover = listOfNotNull(note.cover?.url_default, note.cover?.info_list?.firstOrNull()?.url)
        .firstOrNull { it.isNotBlank() }.orEmpty().let(XhsUrls::toHttps)
    NoteGridCard(
        title = note.display_title,
        coverUrl = cover,
        authorName = note.user.nickname.ifEmpty { note.user.nick_name },
        authorAvatar = note.user.avatar.ifBlank { note.user.image }.let(XhsUrls::toHttps),
        likes = note.interact_info.liked_count,
        isVideo = note.type == "video",
        onClick = onClick,
        coverAspect = mediaAspect(note.cover?.width ?: 0, note.cover?.height ?: 0),
        time = time,
    )
}

/** 资料头：头像 ｜ 昵称 / 小红书号；简介；关注 · 粉丝 · 获赞与收藏三栏数字。 */
@Composable
private fun ProfileHeader(user: ProfileUser) {
    Column(
        // 外层列表已有 sm 内边距，合计为页面标准左右边距 lg
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BsSpacing.sm, vertical = BsSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BsSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = XhsUrls.toHttps(user.avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(BsSize.avatarLg)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(BsSpacing.lg))
            Column(verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
                Text(user.nickname, style = MaterialTheme.typography.titleMedium)
                if (user.red_id.isNotBlank()) {
                    Text(
                        "小红书号：${user.red_id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (user.desc.isNotBlank()) {
            Text(user.desc, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BsSpacing.xl)) {
            ProfileStat(user.follows, "关注")
            ProfileStat(user.fans, "粉丝")
            ProfileStat(user.interaction, "获赞与收藏")
        }
    }
}

@Composable
private fun ProfileStat(count: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
