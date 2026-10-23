package com.rednoteseek.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.download.Downloader
import com.rednoteseek.core.web.NoteSource
import com.rednoteseek.designsystem.component.BsEmptyState
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsLoadMoreFooter
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.BsToastHost
import com.rednoteseek.designsystem.component.BsToastState
import com.rednoteseek.designsystem.format.formatRelativeTime
import com.rednoteseek.designsystem.theme.BsSpacing
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 笔记详情：作者顶栏 + 媒体 / 正文 / 评论 + 底栏（点赞数、收藏、评论数）。 */
@Composable
fun NoteScreen(
    noteId: String,
    searchOrder: List<String>,
    preview: NoteCard?,
    noteSource: NoteSource,
    downloader: Downloader,
    onBack: () -> Unit,
    onAuthorClick: (url: String) -> Unit,
) {
    val vm: NoteViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteViewModel(noteSource, saveNote = { card, index -> saveNoteMedia(downloader, card, index) }) as T
        }
    )
    val collectVm: NoteCollectViewModel = viewModel(
        key = "collect",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NoteCollectViewModel(noteSource) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val collectState by collectVm.state.collectAsStateWithLifecycle()
    // 正在看的大图序号；null 表示没打开
    var viewerStart by rememberSaveable { mutableStateOf<Int?>(null) }
    // 本页有自己的底栏，AppShell 的提示位会压在底栏上：提示显示在底栏上方；大图打开时显示在大图窗口里
    val pageToast = remember { BsToastState() }
    val viewerToast = remember { BsToastState() }
    LaunchedEffect(noteId) { vm.open(noteId, searchOrder, preview) }
    LaunchedEffect(vm) { vm.messages.collect { if (viewerStart != null) viewerToast.show(it) else pageToast.show(it) } }
    LaunchedEffect(collectVm) { collectVm.messages.collect { pageToast.show(it) } }
    // 收藏状态以详情接口为准（列表卡片上没有）
    val loadedCard = (state as? NoteViewModel.State.Loaded)?.card
    LaunchedEffect(noteId, loadedCard != null) {
        loadedCard?.let { collectVm.bind(noteId, it.interact_info.collected) }
    }
    LaunchedEffect(vm) { vm.authorUrls.collect(onAuthorClick) }
    // 返回时先关掉网页里的笔记弹层，回到搜索页后才能继续翻页
    val back = {
        collectVm.dismissPicker()
        vm.close()
        onBack()
    }
    BackHandler(onBack = back)

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 相对时间以进入页面时为准
    val now = remember { System.currentTimeMillis() }

    Column(Modifier.fillMaxSize()) {
        val shownCard = loadedCard ?: (state as? NoteViewModel.State.Preview)?.card
        NoteTopBar(
            user = shownCard?.user,
            onBack = back,
            onAuthorClick = if (loadedCard != null) vm::requestAuthorProfile else null,
        )
        Box(Modifier.weight(1f)) {
            when (val s = state) {
                NoteViewModel.State.Loading -> BsLoadingState()
                is NoteViewModel.State.Preview -> PreviewContent(s.card, now)
                is NoteViewModel.State.Error -> BsErrorState(s.message, onRetry = if (s.retryable) vm::retry else null)
                is NoteViewModel.State.Loaded -> NoteContent(
                    state = s,
                    preview = preview,
                    listState = listState,
                    now = now,
                    saving = saving,
                    onImageClick = { viewerStart = it },
                    onSaveVideo = { vm.save(null) },
                    onLoadMoreComments = vm::loadMoreComments,
                    onExpandReplies = vm::expandReplies,
                    onRetryComments = vm::retryLoadComments,
                )
            }
            BsToastHost(
                pageToast,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = BsSpacing.xl, vertical = BsSpacing.lg),
            )
        }
        if (shownCard != null && state !is NoteViewModel.State.Error) {
            val interact = shownCard.interact_info
            NoteBottomBar(
                card = shownCard,
                onCommentsClick = if (loadedCard != null) {
                    { scope.launch { listState.animateScrollToItem(COMMENTS_HEADER_INDEX) } }
                } else {
                    null
                },
            ) {
                CollectAction(
                    state = collectState,
                    // 列表卡片上没有收藏数，详情到达前只显示图标
                    count = if (loadedCard == null) "" else adjustCount(interact.collected_count, interact.collected, collectState.collected ?: interact.collected),
                    onCollect = collectVm::collect,
                    onChangeBoard = collectVm::changeBoard,
                    onRemoveFromBoards = collectVm::removeFromBoards,
                    onUncollect = collectVm::uncollect,
                )
            }
        }
    }
    collectState.picker?.let { boards ->
        BoardPickerSheet(boards, onPick = collectVm::pick, onDismiss = collectVm::dismissPicker)
    }
    val viewerCard = loadedCard
    val start = viewerStart
    if (viewerCard != null && start != null) {
        ImageViewer(
            images = imageUrls(viewerCard),
            startIndex = start,
            saving = saving,
            toast = viewerToast,
            onSave = vm::save,
            onDismiss = { viewerStart = null },
        )
    }
}

/** 「共 N 条评论」在列表里的位置（媒体、正文之后），底栏点评论数滚到这里。 */
private const val COMMENTS_HEADER_INDEX = 2

@Composable
private fun NoteContent(
    state: NoteViewModel.State.Loaded,
    preview: NoteCard?,
    listState: LazyListState,
    now: Long,
    saving: Boolean,
    onImageClick: (Int) -> Unit,
    onSaveVideo: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onExpandReplies: (String) -> Unit,
    onRetryComments: () -> Unit,
) {
    val card = state.card
    // 滑到距底部 3 项以内时请求下一页评论（ViewModel 内限频）
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .filter { (last, total) -> total > 0 && last >= total - 3 }
            .collect { onLoadMoreComments() }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "media") {
            NoteMedia(
                card,
                placeholders = preview?.let(::imageUrls).orEmpty(),
                onImageClick = onImageClick,
                videoSaving = saving,
                onSaveVideo = onSaveVideo,
            )
        }
        item(key = "body") { NoteBody(card, now) }
        item(key = "comments-header") {
            Column {
                HorizontalDivider(Modifier.padding(horizontal = BsSpacing.lg))
                Text(
                    "共 ${card.interact_info.comment_count} 条评论",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.md),
                )
            }
        }
        if (!state.commentsPending && state.comments.isEmpty() && !state.commentsHasMore) {
            item { BsEmptyState("还没有评论", icon = Icons.Outlined.ChatBubbleOutline, modifier = Modifier.height(CommentsEmptyHeight)) }
        }
        items(state.comments, key = { it.id }) {
            CommentItem(it, now, expanding = it.id in state.expandingReplies, onExpand = { onExpandReplies(it.id) })
        }
        item {
            BsLoadMoreFooter(
                isLoadingMore = state.commentsPending || (state.commentsHasMore && !state.commentsLoadFailed),
                errorMessage = if (state.commentsLoadFailed) "评论加载失败" else null,
                onRetry = onRetryComments,
                endText = if (!state.commentsPending && !state.commentsHasMore && state.comments.isNotEmpty()) "没有更多评论了" else null,
            )
        }
    }
}

/** 详情到达前：显示列表卡片上的封面、标题与作者，评论区位置转圈。 */
@Composable
private fun PreviewContent(card: NoteCard, now: Long) {
    // 视频的封面也先当图片显示
    val shown = card.copy(type = "normal")
    LazyColumn(Modifier.fillMaxSize()) {
        item { NoteMedia(shown) }
        item { NoteBody(shown, now) }
        item {
            Box(Modifier.fillMaxWidth().padding(BsSpacing.lg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/** 标题、正文（表情画成图片）、正文里没有的话题、发布时间 · 属地。 */
@Composable
private fun NoteBody(card: NoteCard, now: Long) {
    Column(
        Modifier.padding(horizontal = BsSpacing.lg, vertical = BsSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BsSpacing.sm),
    ) {
        val title = card.title.ifBlank { card.display_title }
        if (title.isNotBlank()) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        val desc = cleanDesc(card.desc)
        if (desc.isNotBlank()) EmojiText(desc, style = MaterialTheme.typography.bodyMedium)
        // 正文末尾通常已带 #话题，只显示正文里没出现的标签
        val extra = extraTags(desc, card.tag_list.map { it.name })
        if (extra.isNotEmpty()) {
            Text(
                extra.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val meta = listOf(formatRelativeTime(card.time, now), card.ip_location).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val CommentsEmptyHeight = 160.dp
