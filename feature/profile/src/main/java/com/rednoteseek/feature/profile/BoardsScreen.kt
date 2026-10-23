package com.rednoteseek.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 收藏标签页：先展示专辑文件夹，点击进入专辑；第一格「全部收藏」进入不分专辑的收藏列表
 * （不属于任何专辑的收藏只能在那里看到）。数据来自第二网页引擎。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsScreen(
    selfUserId: String,
    source: NoteListSource,
    /** 收藏 / 专辑写操作的次数（XhsWebEngine.collectChanges 之和），变了就重新读取。 */
    collectChanges: Int,
    onAllClick: () -> Unit,
    onBoardClick: (boardId: String, name: String) -> Unit,
) {
    val vm: BoardsViewModel = viewModel(
        key = "boards:$selfUserId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BoardsViewModel(source, selfUserId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val showMessage = LocalShowMessage.current
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(collectChanges) { vm.onCollectChanges(collectChanges) }
    LaunchedEffect(vm) { vm.messages.collect { showMessage(it) } }
    if (creating) {
        BoardFormDialog(
            title = "新建专辑",
            initial = BoardForm("", "", public = true),
            busy = saving,
            onSave = { form -> vm.create(form) { ok -> if (ok) creating = false } },
            onDismiss = { creating = false },
        )
    }
    Column(Modifier.fillMaxSize()) {
        BsTopBar(
            title = "收藏",
            onNavigate = null,
            actions = {
                if (state is BoardsViewModel.State.Loaded) {
                    IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建专辑") }
                }
            },
        )
        when (val s = state) {
            BoardsViewModel.State.Loading -> BsLoadingState()
            is BoardsViewModel.State.Error -> BsErrorState(s.message, onRetry = vm::load)
            is BoardsViewModel.State.Loaded -> {
                PullToRefreshBox(
                    isRefreshing = s.refreshing,
                    onRefresh = vm::load,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(BsSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
                    ) {
                        item(key = "all") {
                            FolderCard(
                                name = "全部收藏",
                                detail = "所有收藏的笔记",
                                // 用前几个专辑的第一张封面拼成「全部」的封面
                                images = s.boards.mapNotNull { it.images.firstOrNull() }.take(4),
                                onClick = onAllClick,
                            )
                        }
                        items(s.boards, key = { it.id }) { board ->
                            FolderCard(
                                name = board.name,
                                detail = "${board.total} 篇笔记",
                                images = board.images,
                                private = board.privacy != 0,
                                onClick = { onBoardClick(board.id, board.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一个专辑：2×2 封面拼图 + 名称（私密专辑带锁）+ 笔记数。封面不足 4 张时空位留底色。 */
@Composable
private fun FolderCard(name: String, detail: String, images: List<String>, onClick: () -> Unit, private: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BsShapes.md)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            repeat(2) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(2) { col ->
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            images.getOrNull(row * 2 + col)?.let { url ->
                                AsyncImage(
                                    model = XhsUrls.toHttps(url),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(BsSpacing.sm), verticalArrangement = Arrangement.spacedBy(BsSpacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (private) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = "私密",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(BsSize.iconXs),
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 一个专辑里的笔记：首屏来自专辑页服务端渲染的数据，往下滚动截获 v1/board/note 翻页。
 * 顶栏「编辑」可改名称 / 简介 / 公开或删除专辑（删除后返回专辑列表）。
 */
@Composable
fun BoardScreen(
    boardId: String,
    name: String,
    source: NoteListSource,
    collectChanges: Int,
    onNoteClick: (noteId: String, order: List<String>, preview: NoteCard?) -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberNoteListViewModel(source, NoteListViewModel.Target.Board(boardId))
    val editVm: BoardEditViewModel = viewModel(
        key = "board-edit:$boardId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BoardEditViewModel(source, boardId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val editState by editVm.state.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf(name) }
    val showMessage = LocalShowMessage.current
    // 本页的改名 / 删除不必重新加载（改名已就地更新标题，删除后直接返回）
    LaunchedEffect(collectChanges) { vm.onCollectChanges(collectChanges, reload = !editVm.isOwnWrite()) }
    LaunchedEffect(editVm) {
        editVm.events.collect { e ->
            when (e) {
                is BoardEditViewModel.Event.Message -> showMessage(e.text)
                is BoardEditViewModel.Event.Renamed -> title = e.name
                BoardEditViewModel.Event.Deleted -> onBack()
            }
        }
    }
    editState.editing?.let { form ->
        BoardFormDialog(
            title = "编辑专辑",
            initial = form,
            busy = editState.busy,
            onSave = editVm::save,
            onDismiss = editVm::cancel,
            onDelete = editVm::delete,
        )
    }
    Column(Modifier.fillMaxSize()) {
        BsTopBar(
            title = title.ifBlank { "专辑" },
            onNavigate = onBack,
            actions = {
                IconButton(onClick = editVm::startEdit, enabled = !editState.busy) {
                    if (editState.busy && editState.editing == null) {
                        BsInlineProgress()
                    } else {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑专辑")
                    }
                }
            },
        )
        NoteListContent(state = state, vm = vm, emptyText = "专辑里还没有笔记", onNoteClick = onNoteClick, emptyIcon = Icons.Outlined.FolderOpen)
    }
}
