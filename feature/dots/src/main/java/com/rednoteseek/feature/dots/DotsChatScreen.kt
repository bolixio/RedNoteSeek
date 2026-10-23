package com.rednoteseek.feature.dots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rednoteseek.core.web.DotsChatSource
import com.rednoteseek.core.web.SourceNote
import com.rednoteseek.designsystem.component.BsErrorState
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.component.MarkdownText
import com.rednoteseek.designsystem.component.NoteSourcesSheet
import com.rednoteseek.designsystem.component.NoteSourcesState
import com.rednoteseek.designsystem.component.SourceCard
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 点点会话：历史消息 + 追问。用户消息靠右气泡，回答靠左（markdown）；生成中可停止。
 * 回答下方「参考 N 篇笔记」打开网页的来源抽屉，点笔记在对话引擎上打开详情。[title] 从历史页传入，继续问进入时是关键词。
 */
@Composable
fun DotsChatScreen(
    source: DotsChatSource,
    conversationId: String,
    title: String,
    onNoteClick: (noteId: String, order: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val vm: DotsChatViewModel = viewModel(
        key = "dots-chat:$conversationId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DotsChatViewModel(source, conversationId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val showMessage = LocalShowMessage.current
    LaunchedEffect(vm) { vm.messages.collect { showMessage(it) } }
    var input by rememberSaveable { mutableStateOf("") }
    val ready = state as? DotsChatViewModel.State.Ready

    // 外壳在隐藏底部导航时已留出系统导航条的空间：先扣掉它，再按键盘高度把输入栏顶上来
    Column(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.navigationBars).imePadding()) {
        BsTopBar(title = title.ifBlank { "点点" }, onNavigate = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                DotsChatViewModel.State.Loading -> BsLoadingState(text = "正在打开会话…")
                is DotsChatViewModel.State.Error -> BsErrorState(s.message, onRetry = vm::load)
                is DotsChatViewModel.State.Ready -> MessageList(s.items, onSourcesClick = vm::openSources)
            }
        }
        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = ready != null,
            generating = ready?.generating == true,
            onSend = { if (vm.send(input)) input = "" },
            onStop = vm::stop,
        )
    }
    sources?.let { sheet ->
        NoteSourcesSheet(
            state = remember(sheet) { sheet.toState() },
            onNoteClick = { n, order -> vm.openSourceNote { onNoteClick(n.id, order) } },
            onDismiss = vm::closeSources,
        )
    }
}

@Composable
private fun MessageList(items: List<DotsChatViewModel.Item>, onSourcesClick: (answerIndex: Int) -> Unit) {
    val listState = rememberLazyListState()
    // 每条回答在回答里的序号（与网页 .ai-message 同序），用户消息为 -1
    val answerIndexes = remember(items) {
        var n = 0
        items.map { if (it is DotsChatViewModel.Item.Answer) n++ else -1 }
    }
    // 有新内容（新消息、正文增长、思考进度）时滚到底部：末尾是高度为 0 的占位项，滚到它就是滚到最底
    val growth = when (val last = items.lastOrNull()) {
        is DotsChatViewModel.Item.Answer -> last.markdown.length + (last.thinking?.length ?: 0)
        is DotsChatViewModel.Item.User -> last.text.length
        null -> 0
    }
    LaunchedEffect(items.size, growth) { listState.scrollToItem(items.size) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = BsSpacing.lg, vertical = BsSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BsSpacing.lg),
    ) {
        itemsIndexed(items) { i, item ->
            when (item) {
                is DotsChatViewModel.Item.User -> UserBubble(item.text)
                is DotsChatViewModel.Item.Answer -> AnswerBlock(item, onSourcesClick = { onSourcesClick(answerIndexes[i]) })
            }
        }
        item(key = "bottom") { Spacer(Modifier) }
    }
}

/** 用户消息：靠右的圆角气泡，左侧留出 [UserBubbleInset]。 */
@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth().padding(start = UserBubbleInset), contentAlignment = Alignment.CenterEnd) {
        Surface(shape = BsShapes.md, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = BsSpacing.md, vertical = BsSpacing.sm),
            )
        }
    }
}

/** 点点的回答：生成中先显示转圈 + 思考进度；正文为 markdown；结束后可看参考来源。 */
@Composable
private fun AnswerBlock(answer: DotsChatViewModel.Item.Answer, onSourcesClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
        if (answer.streaming && answer.markdown.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BsInlineProgress()
                Spacer(Modifier.width(BsSpacing.sm))
                Text(
                    answer.thinking ?: "点点正在思考…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            answer.markdown.isNotEmpty() -> MarkdownText(answer.markdown)
            answer.stopped -> Hint("已停止生成")
            answer.unsupported -> Hint("这条是旅行攻略卡片，App 暂不支持显示，可在小红书里查看")
            !answer.streaming -> Hint("这是搜索页的 AI 总结，网页没有保存正文")
        }
        val count = sourceCount(answer.sourceLabel)
        if (!answer.streaming && count != null) {
            TextButton(onClick = onSourcesClick, contentPadding = PaddingValues(horizontal = BsSpacing.sm)) {
                Text("参考 $count 篇笔记", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 底部输入栏：生成中发送按钮换成停止按钮；输入为空时不能发送。 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BsSpacing.md, vertical = BsSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BsSpacing.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("继续问点点") },
            enabled = enabled,
            maxLines = INPUT_MAX_LINES,
            shape = BsShapes.lg,
            modifier = Modifier.weight(1f),
        )
        if (generating) {
            FilledTonalIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "停止生成") }
        } else {
            FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

/** 「参考85篇笔记」「ai总结48篇笔记生成」→ 85 / 48；没有数字返回 null（不显示来源按钮）。 */
private fun sourceCount(label: String?): String? = label?.let { Regex("\\d+").find(it)?.value }

private fun DotsChatViewModel.SourcesSheet.toState(): NoteSourcesState = when (this) {
    DotsChatViewModel.SourcesSheet.Loading -> NoteSourcesState.Loading
    DotsChatViewModel.SourcesSheet.Failed -> NoteSourcesState.Failed
    is DotsChatViewModel.SourcesSheet.Ready -> NoteSourcesState.Ready(notes.map { it.toCard() })
}

private fun SourceNote.toCard() =
    SourceCard(id, title, coverUrl, coverWidth, coverHeight, author, avatar, likes, isVideo)

/** 用户气泡左侧至少留出的宽度，让气泡不会占满整行。 */
private val UserBubbleInset = 48.dp

private const val INPUT_MAX_LINES = 4
