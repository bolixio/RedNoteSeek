package com.rednoteseek.feature.dots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.web.ChatEvent
import com.rednoteseek.core.web.DotsChatSource
import com.rednoteseek.core.web.DotsConversation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 点点会话历史：整页加载对话页，读网页请求的 conversation/list（只用第一页）。
 * 之后对话引擎每次加载页面（打开会话、删除前重新加载列表）网页都会再请求列表，收到就更新。
 */
class DotsHistoryViewModel(private val source: DotsChatSource) : ViewModel() {

    /** 列表里的一个分组（如「今天」）及其会话，保持接口顺序。 */
    data class Group(val title: String, val items: List<DotsConversation>)

    sealed interface State {
        data object Loading : State
        data class Loaded(val groups: List<Group>) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 正在删除的会话 ID：确认框据此显示忙碌；同一时间只删一个。 */
    private val _deleting = MutableStateFlow<String?>(null)
    val deleting: StateFlow<String?> = _deleting.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var timeoutJob: Job? = null

    init {
        // 先收集再加载：events 是不重放的热流（viewModelScope 用 Main.immediate，launch 会立即执行到 collect）
        viewModelScope.launch {
            source.events.filterIsInstance<ChatEvent.Conversations>().collect { e ->
                _state.value = State.Loaded(group(e.items))
            }
        }
        load()
    }

    /** 整页加载对话页；[LOAD_TIMEOUT_MS] 内没收到列表就显示错误（可重试）。 */
    fun load() {
        if (_deleting.value != null) return // 删除过程中会重新加载网页列表页，这时不再另起一次加载
        _state.value = State.Loading
        viewModelScope.launch { source.openList() }
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (_state.value == State.Loading) _state.value = State.Error("加载失败，请重试")
        }
    }

    /** 删除会话（界面上已确认过）：以截获的删除响应为准，成功才从列表移除。结束后（不论成败）调用 [onDone]。 */
    fun delete(conversation: DotsConversation, onDone: () -> Unit = {}) {
        if (_deleting.value != null) return
        _deleting.value = conversation.id
        viewModelScope.launch {
            try {
                if (source.delete(conversation)) {
                    _state.update { s -> if (s is State.Loaded) State.Loaded(remove(s.groups, conversation.id)) else s }
                } else {
                    _messages.emit("删除失败")
                }
            } finally {
                _deleting.value = null
                onDone()
            }
        }
    }

    /** 相邻且分组标题相同的会话归为一组；同一会话出现多次只保留第一次。 */
    private fun group(items: List<DotsConversation>): List<Group> {
        val groups = mutableListOf<Group>()
        items.distinctBy { it.id }.forEach { c ->
            val last = groups.lastOrNull()
            if (last != null && last.title == c.group) {
                groups[groups.lastIndex] = last.copy(items = last.items + c)
            } else {
                groups += Group(c.group, listOf(c))
            }
        }
        return groups
    }

    /** 移除会话；分组空了就去掉分组。 */
    private fun remove(groups: List<Group>, id: String): List<Group> =
        groups.map { g -> g.copy(items = g.items.filter { it.id != id }) }.filter { it.items.isNotEmpty() }

    private companion object {
        const val LOAD_TIMEOUT_MS = 15_000L
    }
}
