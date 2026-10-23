package com.rednoteseek.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.XhsComment
import com.rednoteseek.core.web.CommentAccumulator
import com.rednoteseek.core.web.NoteEvent
import com.rednoteseek.core.web.NoteSource
import com.rednoteseek.core.web.OpenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NoteViewModel(
    private val source: NoteSource,
    /** 保存到相册：第二个参数是图片序号，null 表示视频。返回给用户看的结果。 */
    private val saveNote: suspend (NoteCard, Int?) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 一次性提示（下载结果），由界面经 LocalShowMessage 显示。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _authorUrls = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** 作者主页地址（取自网页笔记弹层），界面据此导航到主页。 */
    val authorUrls: SharedFlow<String> = _authorUrls.asSharedFlow()

    private var noteId: String? = null
    private var searchOrder: List<String> = emptyList()
    private var preview: NoteCard? = null
    private var openJob: Job? = null
    private var lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
    private var retryJob: Job? = null
    private var fallbackJob: Job? = null
    private val expandJobs = mutableMapOf<String, Job>()
    private var commentWatchJob: Job? = null

    fun requestAuthorProfile() {
        if (_state.value !is State.Loaded) return
        viewModelScope.launch {
            val url = source.readAuthorLink()
            if (url != null) _authorUrls.emit(url) else _messages.emit("无法打开作者主页")
        }
    }

    /**
     * 打开笔记；同一篇重复调用（如界面重组）不会再次点击。
     * [preview] 是列表卡片上已有的内容（封面、标题、作者），详情到达前先显示。
     */
    fun open(noteId: String, searchOrder: List<String>, preview: NoteCard? = null) {
        if (this.noteId == noteId) return
        this.noteId = noteId
        this.searchOrder = searchOrder
        this.preview = preview
        start(noteId)
    }

    /** 先关闭网页弹层，再重新打开。 */
    fun retry() {
        val id = noteId ?: return
        source.close()
        start(id)
    }

    /** 离开详情页时调用：关闭网页弹层，搜索页才能继续翻页。 */
    fun close() {
        // 已关闭则忽略，避免重复执行网页关闭脚本误触发 history.back()
        if (noteId == null) return
        openJob?.cancel()
        retryJob?.cancel()
        fallbackJob?.cancel()
        cancelWatchers()
        source.close()
        noteId = null
        _state.value = State.Loading
    }

    private fun cancelWatchers() {
        commentWatchJob?.cancel()
        expandJobs.values.forEach { it.cancel() }
        expandJobs.clear()
    }

    private fun start(id: String) {
        openJob?.cancel()
        retryJob?.cancel()
        fallbackJob?.cancel()
        cancelWatchers()
        _state.value = preview?.let(State::Preview) ?: State.Loading
        lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
        val comments = CommentAccumulator()
        var card: NoteCard? = null
        var commentsPending = true
        // 重建 Loaded 时保留展开中与翻页失败标记
        fun publish(cardNow: NoteCard, pending: Boolean = commentsPending) {
            val current = _state.value as? State.Loaded
            _state.value = State.Loaded(
                card = cardNow,
                comments = comments.items,
                commentsHasMore = comments.hasMore,
                commentsPending = pending,
                expandingReplies = current?.expandingReplies ?: emptySet(),
                commentsLoadFailed = current?.commentsLoadFailed ?: false,
            )
        }
        // 评论统一入口：真实事件与缓存兜底共用
        fun applyComments(event: NoteEvent.Comments) {
            comments.add(event)
            commentsPending = false
            commentWatchJob?.cancel()
            card?.let {
                publish(it)
                _state.update { s -> (s as? State.Loaded)?.copy(commentsLoadFailed = false) ?: s }
            }
        }
        openJob = viewModelScope.launch {
            // events 是不重放的热流：必须先订阅，再点击卡片
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.events
                    .filter { it.noteId == null || it.noteId == id }
                    .collect { event ->
                        when (event) {
                            is NoteEvent.Detail -> {
                                card = event.card
                                publish(event.card)
                                // 网页对打开过的笔记不再请求评论接口：先读一次页面缓存，没有再等一会儿读
                                if (commentsPending && fallbackJob?.isActive != true) {
                                    fallbackJob = viewModelScope.launch {
                                        source.readCachedComments(id)?.let {
                                            if (commentsPending) applyComments(it)
                                            return@launch
                                        }
                                        delay(CACHE_FALLBACK_MS)
                                        if (!commentsPending) return@launch
                                        val cached = source.readCachedComments(id)
                                        if (cached != null) {
                                            applyComments(cached)
                                        } else {
                                            commentsPending = false
                                            _state.update { (it as? State.Loaded)?.copy(commentsPending = false) ?: it }
                                        }
                                    }
                                }
                            }
                            is NoteEvent.Comments -> {
                                // 评论可能早于笔记内容到达，先存着
                                fallbackJob?.cancel()
                                applyComments(event)
                            }
                            is NoteEvent.SubComments -> {
                                comments.addReplies(event)
                                expandJobs.remove(event.rootCommentId)?.cancel()
                                card?.let { publish(it) }
                                _state.update { s -> (s as? State.Loaded)?.copy(expandingReplies = s.expandingReplies - event.rootCommentId) ?: s }
                            }
                            is NoteEvent.Failed -> when {
                                event.risk -> {
                                    // 风控是终态：停止收集、超时监视与后续处理
                                    _state.value = State.Error(RISK_MESSAGE, retryable = false)
                                    openJob?.cancel()
                                    fallbackJob?.cancel()
                                    cancelWatchers()
                                }
                                event.fromFeed && _state.value !is State.Loaded -> {
                                    commentsPending = false
                                    _state.value = State.Error(event.message, retryable = true)
                                }
                                else -> {
                                    // 评论 / 回复失败：不切错误页，只是不再等待第一页
                                    commentsPending = false
                                    _state.update { (it as? State.Loaded)?.copy(commentsPending = false) ?: it }
                                }
                            }
                        }
                    }
            }
            launch {
                delay(DETAIL_TIMEOUT_MS)
                if (_state.value.isOpening) _state.value = State.Error(TIMEOUT_MESSAGE, retryable = true)
            }
            if (source.open(id, searchOrder) == OpenResult.NotFound && _state.value.isOpening) {
                _state.value = State.Error(NOT_FOUND_MESSAGE, retryable = false)
            }
        }
    }

    /** 评论列表接近底部时调用；限频，没有更多时不再请求。 */
    fun loadMoreComments() {
        val s = _state.value as? State.Loaded ?: return
        if (!s.commentsHasMore) return
        if (s.commentsLoadFailed) return
        val now = clock()
        if (s.commentsPending) return
        if (now - lastLoadMoreAt < LOAD_MORE_INTERVAL_MS) {
            // 被限频挡下：安排一次延迟重试，用户停在底部不动时也能翻到下一页
            if (retryJob?.isActive != true) {
                retryJob = viewModelScope.launch {
                    delay(LOAD_MORE_INTERVAL_MS - (now - lastLoadMoreAt))
                    loadMoreComments()
                }
            }
            return
        }
        lastLoadMoreAt = now
        source.loadMoreComments()
        watchCommentLoad(s.comments.size)
    }

    private fun watchCommentLoad(countBefore: Int) {
        if (commentWatchJob?.isActive == true) return
        commentWatchJob = viewModelScope.launch {
            delay(LOAD_MORE_TIMEOUT_MS)
            _state.update { s ->
                if (s is State.Loaded && s.commentsHasMore && s.comments.size <= countBefore) s.copy(commentsLoadFailed = true) else s
            }
        }
    }

    /** 评论底部「重试」。 */
    fun retryLoadComments() {
        _state.update { (it as? State.Loaded)?.copy(commentsLoadFailed = false) ?: it }
        lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
        loadMoreComments()
    }

    /** 展开某条根评论的回复：在网页里点击「展开回复」，结果经截获的 v2/comment/sub/page 合并进来。 */
    fun expandReplies(rootCommentId: String) {
        val s = _state.value as? State.Loaded ?: return
        if (rootCommentId in s.expandingReplies) return
        _state.value = s.copy(expandingReplies = s.expandingReplies + rootCommentId)
        expandJobs[rootCommentId] = viewModelScope.launch {
            if (!source.expandReplies(rootCommentId)) {
                finishExpand(rootCommentId, "无法展开回复")
                return@launch
            }
            delay(EXPAND_TIMEOUT_MS)
            finishExpand(rootCommentId, "展开回复超时")
        }
    }

    private suspend fun finishExpand(rootCommentId: String, message: String) {
        expandJobs.remove(rootCommentId)
        _state.update { (it as? State.Loaded)?.copy(expandingReplies = it.expandingReplies - rootCommentId) ?: it }
        _messages.emit(message)
    }

    /** 无水印保存一张图片（[imageIndex]）或视频（null）到相册，结果经 [messages] 提示。一次只存一个。 */
    fun save(imageIndex: Int? = null) {
        val s = _state.value as? State.Loaded ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            val message = try {
                saveNote(s.card, imageIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "下载失败：${e.message ?: e::class.simpleName}"
            }
            _saving.value = false
            _messages.emit(message)
        }
    }

    sealed interface State {
        /** 详情尚未到达（[Loading] 或 [Preview]）。 */
        val isOpening: Boolean get() = this == Loading || this is Preview

        data object Loading : State
        /** 详情尚未到达，先显示列表卡片上已有的内容。 */
        data class Preview(val card: NoteCard) : State
        data class Error(val message: String, val retryable: Boolean) : State
        data class Loaded(
            val card: NoteCard,
            val comments: List<XhsComment>,
            val commentsHasMore: Boolean,
            /** 第一页评论到达前为 true，界面据此不显示「还没有评论」。 */
            val commentsPending: Boolean = false,
            /** 正在展开回复的根评论 id。 */
            val expandingReplies: Set<String> = emptySet(),
            /** 评论翻页发出后超时仍无新数据；界面显示重试，且不再自动翻页。 */
            val commentsLoadFailed: Boolean = false,
        ) : State
    }

    private companion object {
        const val DETAIL_TIMEOUT_MS = 15_000L
        const val CACHE_FALLBACK_MS = 2_500L
        const val LOAD_MORE_INTERVAL_MS = 3_000L
        const val LOAD_MORE_TIMEOUT_MS = 10_000L
        const val EXPAND_TIMEOUT_MS = 8_000L
        const val RISK_MESSAGE = "触发风控，请稍后再试"
        const val TIMEOUT_MESSAGE = "加载超时，请重试"
        const val NOT_FOUND_MESSAGE = "在网页中找不到这篇笔记，请回到搜索页重新进入"
    }
}
