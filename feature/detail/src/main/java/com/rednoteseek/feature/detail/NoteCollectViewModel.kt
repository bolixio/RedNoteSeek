package com.rednoteseek.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.web.CollectOutcome
import com.rednoteseek.core.web.NoteSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页的收藏与专辑：都在网页笔记弹层里点网页自己的按钮完成，以截获的接口响应为准。
 * 网页只在「刚收藏」时提供「加入专辑」，所以换专辑 / 移出专辑都是先取消收藏（会移出所有专辑）再重新收藏，
 * 收藏时间会变成现在。
 */
class NoteCollectViewModel(private val source: NoteSource) : ViewModel() {

    data class State(
        /** 是否已收藏；详情到达前未知。 */
        val collected: Boolean? = null,
        val busy: Boolean = false,
        /** 非空时显示「加入专辑」选择（网页面板里的专辑，按面板顺序）。 */
        val picker: List<Board>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var noteId: String? = null

    /** 详情到达时调用：以 v1/feed 里的收藏状态为初值（同一篇只取第一次）。 */
    fun bind(noteId: String, collected: Boolean) {
        if (this.noteId == noteId) return
        this.noteId = noteId
        _state.value = State(collected = collected)
    }

    /** 收藏，并打开「加入专辑」选择。 */
    fun collect() = write {
        collectAndPick()
    }

    /** 换专辑：取消收藏后重新收藏，再选专辑。 */
    fun changeBoard() = write {
        if (uncollectQuietly()) collectAndPick()
    }

    /** 移出专辑：取消收藏后重新收藏，不加入任何专辑。 */
    fun removeFromBoards() = write {
        if (!uncollectQuietly()) return@write
        when (val outcome = source.collect()) {
            CollectOutcome.Failed -> _messages.emit("重新收藏失败，笔记已取消收藏")
            is CollectOutcome.Collected -> {
                _state.update { it.copy(collected = true) }
                if (outcome.boards != null) source.closeBoardPicker()
                _messages.emit("已移出专辑，笔记仍在收藏中")
            }
        }
    }

    fun uncollect() = write {
        if (uncollectQuietly()) _messages.emit("已取消收藏")
    }

    /** 在「加入专辑」里选了 [board]（[index] 为它在列表中的位置）。 */
    fun pick(index: Int, board: Board) = write {
        _state.update { it.copy(picker = null) }
        _messages.emit(if (source.moveToBoard(index, board)) "已加入「${board.name}」" else "加入专辑失败，笔记已收藏")
    }

    /** 不加入专辑：关掉网页面板，笔记保持收藏。 */
    fun dismissPicker() {
        if (_state.value.picker == null) return
        _state.update { it.copy(picker = null) }
        source.closeBoardPicker()
    }

    private suspend fun collectAndPick() {
        when (val outcome = source.collect()) {
            CollectOutcome.Failed -> _messages.emit("收藏失败，请稍后重试")
            is CollectOutcome.Collected -> {
                _state.update { it.copy(collected = true, picker = outcome.boards) }
                if (outcome.boards == null) _messages.emit("已收藏（未能打开专辑列表）")
            }
        }
    }

    /** 取消收藏，失败时提示并返回 false。 */
    private suspend fun uncollectQuietly(): Boolean {
        val ok = source.uncollect()
        if (ok) _state.update { it.copy(collected = false) } else _messages.emit("取消收藏失败，请稍后重试")
        return ok
    }

    /** 一次只做一个写操作。 */
    private fun write(block: suspend () -> Unit) {
        if (_state.value.busy || _state.value.collected == null) return
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
