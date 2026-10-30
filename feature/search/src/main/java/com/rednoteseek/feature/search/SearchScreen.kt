package com.rednoteseek.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.SearchItem
import com.rednoteseek.core.storage.history.SearchHistoryRepository
import com.rednoteseek.core.web.SearchMode
import com.rednoteseek.core.web.SearchSort
import com.rednoteseek.core.web.SearchSource
import com.rednoteseek.core.web.SourceNote
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsEmptyState
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsLoadMoreFooter
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.component.NoteGridCard
import com.rednoteseek.designsystem.component.NoteSourcesSheet
import com.rednoteseek.designsystem.component.NoteSourcesState
import com.rednoteseek.designsystem.component.RedNoteSeekWordmark
import com.rednoteseek.designsystem.component.SettingsGroup
import com.rednoteseek.designsystem.component.SettingsRow
import com.rednoteseek.designsystem.component.SourceCard
import com.rednoteseek.designsystem.format.mediaAspect
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing
import com.rednoteseek.designsystem.theme.LocalBarStyle
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

/**
 * 搜索页。点击笔记时把当前结果的笔记 ID 顺序一并交出去，
 * 详情页靠它在网页里定位已被虚拟列表回收的卡片。
 */
@Composable
fun SearchScreen(
    searchSource: SearchSource,
    history: SearchHistoryRepository,
    onNoteClick: (noteId: String, searchOrder: List<String>, preview: NoteCard?) -> Unit,
    modifier: Modifier = Modifier,
    initialMode: SearchMode = SearchMode.NOTES,
    onModeChange: suspend (SearchMode) -> Unit = {},
    onDotsHistoryClick: () -> Unit = {},
    onContinueInChat: (conversationId: String, title: String) -> Unit = { _, _ -> },
    avatarUrl: String = "",
    onAvatarClick: () -> Unit = {},
) {
    val vm: SearchViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(searchSource, history, initialMode = initialMode, onModeChange = onModeChange) as T
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val historyList by vm.historyList.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val continuing by vm.continuing.collectAsStateWithLifecycle()
    val showMessage = LocalShowMessage.current
    LaunchedEffect(vm) { vm.messages.collect { showMessage(it) } }
    var keyword by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    // 顶栏折叠：搜索框 / 排序钉在顶部，只有艺术字与头像这一行随列表滚动收起、反向滑动再出现。
    // titleOffset ∈ [-titleHeightPx, 0]；onPreScroll 先于列表消费滚动
    var titleHeightPx by remember { mutableIntStateOf(0) }
    var titleOffset by remember { mutableFloatStateOf(0f) }
    val collapseConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val limit = titleHeightPx.toFloat()
                if (limit <= 0f) return Offset.Zero
                val prev = titleOffset
                titleOffset = (titleOffset + available.y).coerceIn(-limit, 0f)
                return Offset(0f, titleOffset - prev)
            }
        }
    }
    val submit: (String) -> Unit = { kw ->
        keyword = kw
        titleOffset = 0f
        keyboard?.hide()
        vm.search(kw)
    }
    val focusRequester = remember { FocusRequester() }
    // 有结果（或加载中 / 出错）时系统返回先取消搜索回到历史，清空并聚焦搜索框
    BackHandler(enabled = state !is SearchViewModel.State.Idle) {
        keyword = ""
        vm.search("")
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 界面样式：顶栏等比缩放（Density 方案，图标/文字/间距自适应）
    val barStyle = LocalBarStyle.current
    val baseDensity = LocalDensity.current
    val topScaledDensity = Density(baseDensity.density * barStyle.topScale, baseDensity.fontScale * barStyle.topScale)
    Column(modifier.fillMaxSize().nestedScroll(collapseConnection)) {
        CompositionLocalProvider(LocalDensity provides topScaledDensity) {
        // 外层 Box 承担折叠：measure 拿到顶栏原始高度，按 titleOffset 缩减可见高度并整体上移
        Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (titleHeightPx != placeable.height) titleHeightPx = placeable.height
                    val visible = (placeable.height + titleOffset).roundToInt().coerceIn(0, placeable.height)
                    layout(placeable.width, visible) { placeable.place(0, titleOffset.roundToInt()) }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = BsSpacing.lg, end = BsSpacing.lg, top = BsSpacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RedNoteSeekWordmark()
                AsyncImage(
                    model = avatarUrl.ifEmpty { null },
                    contentDescription = "我的主页",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(BsSize.avatar)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onAvatarClick),
                )
            }
        }
        // 每次搜索都要加载一次真实网页，所以只在用户确认时触发，不做输入即搜
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索笔记") },
            singleLine = true,
            shape = BsShapes.pill,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    // 清空并回到搜索历史
                    IconButton(onClick = { submit("") }) { Icon(Icons.Outlined.Close, contentDescription = "清空") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(keyword) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = BsSpacing.lg, end = BsSpacing.lg, top = BsSpacing.md, bottom = BsSpacing.sm)
                .focusRequester(focusRequester),
        )
        if (state !is SearchViewModel.State.Idle) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = BsSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                modifier = Modifier.padding(bottom = BsSpacing.xs),
            ) {
                item(key = "ai") {
                    FilterChip(
                        selected = mode == SearchMode.AI,
                        onClick = { vm.setMode(if (mode == SearchMode.AI) SearchMode.NOTES else SearchMode.AI) },
                        label = { Text("AI 总结") },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(BsSize.iconSm)) },
                    )
                }
                items(SearchSort.entries, key = { it.name }) { option ->
                    FilterChip(
                        selected = option == sort,
                        onClick = { vm.setSort(option) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        }

        Box(Modifier.fillMaxSize()) {
            when (val s = state) {
                SearchViewModel.State.Idle -> HistoryPanel(
                    history = historyList,
                    onPick = submit,
                    onClear = vm::clearHistory,
                    onDotsClick = onDotsHistoryClick,
                )
                SearchViewModel.State.Loading -> BsLoadingState()
                SearchViewModel.State.Empty -> BsEmptyState("没有找到相关笔记", icon = Icons.Outlined.SearchOff)
                is SearchViewModel.State.Error -> BsErrorState(s.message, onRetry = { submit(keyword) })
                is SearchViewModel.State.Loaded -> key(s.keyword) {
                    ResultGrid(
                        notes = s.items,
                        hasMore = s.hasMore,
                        loadMoreFailed = s.loadMoreFailed,
                        onLoadMore = vm::loadMore,
                        onRetry = vm::retryLoadMore,
                        onNoteClick = { item -> if (!continuing) onNoteClick(item.id, s.items.map { it.id }, item.note_card) },
                        // lambda 要显式标 @Composable：在 let 里推断不出期望类型
                        header = if (mode == SearchMode.AI) {
                            summary?.let { sm ->
                                @Composable {
                                    AiSummaryCard(
                                        sm,
                                        onSourcesClick = vm::openSources,
                                        continuing = continuing,
                                        onContinueClick = { vm.continueInChat(onContinueInChat) },
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        sources?.let { sheet ->
            NoteSourcesSheet(
                state = remember(sheet) { sheet.toState() },
                onNoteClick = { n, order -> vm.openSourceNote { onNoteClick(n.id, order, null) } },
                onDismiss = vm::closeSources,
            )
        }
    }
}

@Composable
private fun ResultGrid(
    notes: List<SearchItem>,
    hasMore: Boolean,
    loadMoreFailed: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onNoteClick: (SearchItem) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val gridState = rememberLazyStaggeredGridState()
    // 总结常比第一页笔记晚到：卡片插到最前面时网格会保持原来的第一项不动，卡片落在屏幕上方；用户还停在顶部就滚回去露出卡片
    val hasHeader = header != null
    LaunchedEffect(hasHeader) {
        if (hasHeader && gridState.firstVisibleItemIndex <= 1) gridState.scrollToItem(0)
    }
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
            item(key = "summary", span = StaggeredGridItemSpan.FullLine) { header() }
        }
        items(notes, key = { it.id }) { item ->
            NoteCard(item = item, onClick = { onNoteClick(item) })
        }
        item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
            BsLoadMoreFooter(
                isLoadingMore = hasMore && !loadMoreFailed,
                errorMessage = if (loadMoreFailed) "加载失败" else null,
                onRetry = onRetry,
                endText = if (hasMore) null else "没有更多了",
            )
        }
    }
}

/** 搜索历史：顶部是「点点会话」入口；标签排成多行，点了直接搜；标题行右侧垃圾桶清空全部。 */
@Composable
private fun HistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDotsClick: () -> Unit,
) {
    if (history.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            DotsEntry(onDotsClick, Modifier.padding(horizontal = BsSpacing.lg))
            BsEmptyState("输入关键词开始搜索", modifier = Modifier.weight(1f), icon = Icons.Outlined.Search)
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BsSpacing.lg),
    ) {
        DotsEntry(onDotsClick)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "清空搜索历史",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm)) {
            history.forEach { kw ->
                SuggestionChip(
                    onClick = { onPick(kw) },
                    label = { Text(kw, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = BsShapes.pill,
                )
            }
        }
    }
}

/** 「点点会话」入口（独立页面；进入时才创建对话引擎）。 */
@Composable
private fun DotsEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsGroup(title = null, modifier = modifier.padding(vertical = BsSpacing.sm)) {
        SettingsRow(title = "点点会话", icon = Icons.Outlined.AutoAwesome, onClick = onClick)
    }
}

@Composable
private fun NoteCard(item: SearchItem, onClick: () -> Unit) {
    val nc = item.note_card
    val firstImage = nc.image_list.firstOrNull()
    // 接口给的是 http 地址，App 禁止明文流量，需要升级为 https
    val cover = listOfNotNull(
        nc.cover?.url_default,
        firstImage?.info_list?.firstOrNull()?.url,
        firstImage?.url_default,
    ).firstOrNull { it.isNotBlank() }.orEmpty().let(XhsUrls::toHttps)
    val avatar = nc.user.avatar.ifBlank { nc.user.image }.let(XhsUrls::toHttps)
    val author = nc.user.nickname.ifEmpty { nc.user.nick_name }
    NoteGridCard(
        title = nc.display_title.ifBlank { nc.title },
        coverAspect = mediaAspect(nc.cover?.width ?: 0, nc.cover?.height ?: 0),
        coverUrl = cover,
        authorName = author,
        authorAvatar = avatar,
        likes = nc.interact_info.liked_count,
        isVideo = nc.type == "video",
        onClick = onClick,
    )
}

private fun SearchViewModel.SourcesSheet.toState(): NoteSourcesState = when (this) {
    SearchViewModel.SourcesSheet.Loading -> NoteSourcesState.Loading
    SearchViewModel.SourcesSheet.Failed -> NoteSourcesState.Failed
    is SearchViewModel.SourcesSheet.Ready -> NoteSourcesState.Ready(notes.map { it.toCard() })
}

private fun SourceNote.toCard() =
    SourceCard(id, title, coverUrl, coverWidth, coverHeight, author, avatar, likes, isVideo)
