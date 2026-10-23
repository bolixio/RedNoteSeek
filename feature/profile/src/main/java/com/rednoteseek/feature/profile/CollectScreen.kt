package com.rednoteseek.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.designsystem.component.BsTopBar

/** 「我」的全部收藏（不分专辑）：打开自己的主页并切到「收藏」标签。数据来自第二网页引擎。 */
@Composable
fun CollectScreen(
    selfUserId: String,
    source: NoteListSource,
    collectChanges: Int,
    onNoteClick: (noteId: String, order: List<String>, preview: NoteCard?) -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberNoteListViewModel(source, NoteListViewModel.Target.Collections(selfUserId))
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(collectChanges) { vm.onCollectChanges(collectChanges) }
    Column(Modifier.fillMaxSize()) {
        BsTopBar(title = "全部收藏", onNavigate = onBack)
        NoteListContent(state = state, vm = vm, emptyText = "还没有收藏", onNoteClick = onNoteClick, emptyIcon = Icons.Outlined.StarBorder)
    }
}
