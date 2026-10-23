package com.rednoteseek.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.core.web.NoteListSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 专辑页的「编辑专辑」：改名称 / 简介 / 公开，或删除专辑。都在网页专辑页的表单里完成（第二网页引擎）。 */
class BoardEditViewModel(
    private val source: NoteListSource,
    private val boardId: String,
) : ViewModel() {

    data class State(
        /** 非空时显示编辑表单（初值读自网页）。 */
        val editing: BoardForm? = null,
        /** 读取初值或提交中。 */
        val busy: Boolean = false,
    )

    sealed interface Event {
        data class Message(val text: String) : Event
        data class Renamed(val name: String) : Event
        data object Deleted : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** 本页刚成功的改名 / 删除还没被 [isOwnWrite] 认领。 */
    private var ownWritePending = false

    /**
     * 写操作次数变化时调用：本页正在提交或刚提交成功则返回 true（认领一次），
     * 页面据此不重新加载。计数在截获响应时就会变，可能早于或晚于提交返回，两种顺序都要认。
     */
    fun isOwnWrite(): Boolean {
        if (_state.value.busy) return true
        return ownWritePending.also { ownWritePending = false }
    }

    fun startEdit() = busy {
        val form = source.readBoardForm(boardId)
        if (form == null) _events.emit(Event.Message("读取专辑信息失败，请稍后重试"))
        else _state.update { it.copy(editing = form) }
    }

    fun cancel() {
        if (!_state.value.busy) _state.update { it.copy(editing = null) }
    }

    fun save(form: BoardForm) = busy {
        if (source.updateBoard(boardId, form)) {
            ownWritePending = true
            _state.update { it.copy(editing = null) }
            _events.emit(Event.Renamed(form.name))
            _events.emit(Event.Message("已保存"))
        } else {
            _events.emit(Event.Message("保存失败，请稍后重试"))
        }
    }

    fun delete() = busy {
        if (source.deleteBoard(boardId)) {
            ownWritePending = true
            _state.update { it.copy(editing = null) }
            _events.emit(Event.Deleted)
        } else {
            _events.emit(Event.Message("删除失败，请稍后重试"))
        }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
