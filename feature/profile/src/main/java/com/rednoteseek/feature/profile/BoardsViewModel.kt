package com.rednoteseek.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.core.web.NoteListSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 收藏标签页：「我」的专辑列表（自己主页「收藏 › 专辑」服务端渲染的数据，第二网页引擎）。 */
class BoardsViewModel(
    private val source: NoteListSource,
    private val selfUserId: String,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        data class Loaded(val boards: List<Board>, val refreshing: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var seenChanges: Int? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _saving = MutableStateFlow(false)
    /** 正在新建专辑。 */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        load()
    }

    /** 同 [NoteListViewModel.onCollectChanges]：写操作次数变了就重新读取专辑列表。 */
    fun onCollectChanges(changes: Int) {
        val previous = seenChanges
        seenChanges = changes
        if (previous != null && previous != changes) load()
    }

    /** 新建专辑；成功后写操作次数会变，列表随之重新读取。成功返回 true（界面据此关闭表单）。 */
    fun create(form: BoardForm, onDone: (Boolean) -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val id = source.createBoard(selfUserId, form)
            _saving.value = false
            _messages.emit(if (id != null) "已新建专辑「${form.name}」" else "新建专辑失败，请稍后重试")
            onDone(id != null)
        }
    }

    /** 错误页的「重试」与下拉刷新：重新打开专辑页读取；刷新失败时保留已有列表。 */
    fun load() {
        if (loadJob?.isActive == true) return
        val old = _state.value as? State.Loaded
        _state.value = old?.copy(refreshing = true) ?: State.Loading
        loadJob = viewModelScope.launch {
            val boards = source.openBoards(selfUserId)
            _state.value = when {
                boards != null -> State.Loaded(boards)
                old != null -> old
                else -> State.Error("专辑加载失败")
            }
        }
    }
}
