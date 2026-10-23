package com.rednoteseek.feature.dots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.web.ChatEvent
import com.rednoteseek.core.web.ChatMessage
import com.rednoteseek.core.web.DotsChatSource
import com.rednoteseek.core.web.SourceNote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个点点会话：整页加载会话页，以截获的 conversation/detail 为准显示历史；
 * 追问时往网页输入框填词并发送，回答从推送帧流式追加（按会话 ID 归属）。
 */
class DotsChatViewModel(
    private val source: DotsChatSource,
    private val conversationId: String,
) : ViewModel() {

    sealed interface Item {
        data class User(val text: String) : Item

        /**
         * 点点的回答。[thinking] 只在生成中、还没出正文时有；[stopped] 表示一个字都没收到就结束了（停止生成或超时）。
         * [unsupported] 是旅行攻略卡片，App 暂不支持显示。历史里正文为空（且不是 stopped / unsupported）的是搜索页的 AI 总结：网页不保存正文。
         */
        data class Answer(
            val markdown: String,
            val sourceLabel: String?,
            val thinking: String? = null,
            val streaming: Boolean = false,
            val stopped: Boolean = false,
            val unsupported: Boolean = false,
        ) : Item
    }

    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        /** [generating] 时最后一条是生成中的回答，不能再发送。 */
        data class Ready(val items: List<Item>, val generating: Boolean = false) : State
    }

    sealed interface SourcesSheet {
        data object Loading : SourcesSheet
        data class Ready(val notes: List<SourceNote>) : SourcesSheet
        data object Failed : SourcesSheet
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 参考来源弹层；未打开时为 null。 */
    private val _sources = MutableStateFlow<SourcesSheet?>(null)
    val sources: StateFlow<SourcesSheet?> = _sources.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var historyJob: Job? = null
    private var idleJob: Job? = null
    private var sourcesJob: Job? = null
    /** 来源弹层对应的回答序号（与网页 `.ai-message` 同序）；弹层关着时为 null。 */
    private var sourcesIndex: Int? = null
    /** 各回答已收到的参考来源：网页在同一页面第二次打开抽屉可能走缓存、不再请求。 */
    private val sourceCache = mutableMapOf<Int, List<SourceNote>>()

    init {
        // 先收集再打开：events 是不重放的热流（viewModelScope 用 Main.immediate，launch 会立即执行到 collect）
        viewModelScope.launch { source.events.collect { onEvent(it) } }
        load()
    }

    /** 整页加载会话；[HISTORY_TIMEOUT_MS] 内没收到历史就显示错误（可重试）。 */
    fun load() {
        idleJob?.cancel()
        sourceCache.clear()
        _state.value = State.Loading
        viewModelScope.launch { source.openConversation(conversationId) }
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            delay(HISTORY_TIMEOUT_MS)
            if (_state.value == State.Loading) _state.value = State.Error("加载失败，请重试")
        }
    }

    /** 追问。会话没加载好、生成中或输入为空时不发送，返回 false（输入框据此决定是否清空）。 */
    fun send(text: String): Boolean {
        val q = text.trim()
        val s = _state.value as? State.Ready ?: return false
        if (q.isEmpty() || s.generating) return false
        _state.value = s.copy(items = s.items + Item.User(q) + Item.Answer("", null, streaming = true), generating = true)
        touch()
        viewModelScope.launch {
            if (!source.send(q)) {
                idleJob?.cancel()
                _state.update { st ->
                    if (st is State.Ready && st.generating) st.copy(items = st.items.dropLast(2), generating = false) else st
                }
                _messages.emit("发送失败，请稍后再试")
            }
        }
        return true
    }

    /** 停止生成：点网页的停止按钮，服务器随即以 cancel 结束这次回答。 */
    fun stop() {
        if ((_state.value as? State.Ready)?.generating == true) source.stop()
    }

    /** 打开第 [answerIndex] 条回答的参考来源（点网页回答里的来源入口）；这条回答收到过就直接用。 */
    fun openSources(answerIndex: Int) {
        sourcesIndex = answerIndex
        _sources.value = sourceCache[answerIndex]?.let { SourcesSheet.Ready(it) } ?: SourcesSheet.Loading
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            if (!source.openSources(answerIndex)) {
                if (_sources.value == SourcesSheet.Loading) _sources.value = SourcesSheet.Failed
                return@launch
            }
            delay(SOURCES_TIMEOUT_MS)
            if (_sources.value == SourcesSheet.Loading) _sources.value = SourcesSheet.Failed
        }
    }

    /** 关闭来源弹层，同时关掉网页的来源抽屉。 */
    fun closeSources() {
        sourcesJob?.cancel()
        sourcesIndex = null
        if (_sources.value == null) return
        _sources.value = null
        source.closeSources()
    }

    /** 点来源笔记：先确保网页的来源抽屉开着（详情返回后网页可能已关掉），再交给 [onReady] 打开详情。 */
    fun openSourceNote(onReady: () -> Unit) {
        val index = sourcesIndex ?: return
        viewModelScope.launch {
            source.openSources(index)
            onReady()
        }
    }

    private fun onEvent(e: ChatEvent) {
        when (e) {
            is ChatEvent.History -> if (e.sessionId == conversationId && _state.value == State.Loading) {
                historyJob?.cancel()
                _state.value = State.Ready(e.messages.map(::toItem))
            }
            is ChatEvent.Thinking -> if (e.sessionId == conversationId) updateAnswer { it.copy(thinking = e.text) }
            is ChatEvent.Delta -> if (e.sessionId == conversationId) {
                updateAnswer { it.copy(markdown = it.markdown + e.text, thinking = null) }
            }
            is ChatEvent.SourceCount -> if (e.sessionId == conversationId) updateAnswer { it.copy(sourceLabel = e.label) }
            is ChatEvent.Finished -> if (e.sessionId == conversationId) finish()
            // 只认弹层正在等待（Loading）的那次请求：迟到的上一条回答的来源不能写进当前序号的缓存
            is ChatEvent.Sources -> sourcesIndex?.let { index ->
                if (_sources.value == SourcesSheet.Loading) {
                    sourceCache[index] = e.notes
                    _sources.value = SourcesSheet.Ready(e.notes)
                }
            }
            is ChatEvent.Conversations, is ChatEvent.Deleted -> Unit
        }
    }

    private fun toItem(m: ChatMessage): Item = when (m) {
        is ChatMessage.User -> Item.User(m.text)
        is ChatMessage.Answer -> Item.Answer(m.markdown, m.sourceLabel, stopped = m.stopped, unsupported = m.unsupported)
    }

    /** 更新生成中的那条回答（列表最后一条）；不在生成中时忽略。每个流式事件都重新计时。 */
    private fun updateAnswer(change: (Item.Answer) -> Item.Answer) {
        val s = _state.value as? State.Ready ?: return
        val last = s.items.lastOrNull() as? Item.Answer ?: return
        if (!s.generating || !last.streaming) return
        _state.value = s.copy(items = s.items.dropLast(1) + change(last))
        touch()
    }

    /** 回答结束（收到 end，或 [STREAM_IDLE_TIMEOUT_MS] 内没有新的流式事件）：保留已收到的内容。 */
    private fun finish() {
        idleJob?.cancel()
        _state.update { st ->
            val s = st as? State.Ready ?: return@update st
            if (!s.generating) return@update s
            val last = s.items.lastOrNull() as? Item.Answer
            val items = if (last != null && last.streaming) {
                s.items.dropLast(1) + last.copy(thinking = null, streaming = false, stopped = last.markdown.isEmpty())
            } else {
                s.items
            }
            s.copy(items = items, generating = false)
        }
    }

    private fun touch() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(STREAM_IDLE_TIMEOUT_MS)
            finish()
        }
    }

    private companion object {
        const val HISTORY_TIMEOUT_MS = 15_000L
        /** 生成中这么久没有新的流式事件就当作结束（真机上一次回答约 14 秒）。 */
        const val STREAM_IDLE_TIMEOUT_MS = 30_000L
        const val SOURCES_TIMEOUT_MS = 8_000L
    }
}
