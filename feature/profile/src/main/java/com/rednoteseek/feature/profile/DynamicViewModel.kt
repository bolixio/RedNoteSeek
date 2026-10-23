package com.rednoteseek.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.storage.dynamic.DynamicCacheRepository
import com.rednoteseek.core.storage.follow.FollowEntity
import com.rednoteseek.core.storage.follow.FollowRepository
import com.rednoteseek.core.web.NoteListEvent
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.UnreadResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * 动态：网页端没有关注流，下拉刷新时在第二网页引擎里依次打开关注作者（账号关注名单）的主页，
 * 读首屏快照（带发布时间），按时间倒序合并。
 * 增量：先在自己主页的关注列表弹窗里读各作者的未看笔记数，只访问有未看的、没有缓存的作者，
 * 其余沿用 [cache] 里上次读到的笔记；读不到未看数时全部访问。
 * 只由用户手动触发；两次页面加载间隔至少 [VISIT_INTERVAL_MS]；遇风控或连续失败立即停止，不自动重试。
 */
class DynamicViewModel(
    private val source: NoteListSource,
    private val follows: FollowRepository,
    private val cache: DynamicCacheRepository,
    private val selfUserId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class FeedNote(val note: PostedNote, val authorId: String)

    data class State(
        val authors: List<FollowEntity> = emptyList(),
        val feed: List<FeedNote> = emptyList(),
        /** 作者 ID → 未看笔记数（最近一次刷新读到的）。 */
        val unread: Map<String, Int> = emptyMap(),
        val refreshing: Boolean = false,
        /** 刷新的第一步：正在读未看数（此时 [total] 还不知道）。 */
        val checkingUnread: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val message: String? = null,
        val openingNoteId: String? = null,
    )

    sealed interface Effect {
        /** 第二引擎已停在作者主页，可以按 [order] 去点网页里的卡片。 */
        data class OpenNote(val noteId: String, val order: List<String>) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = 1)
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    /** 每位作者最近一次读到的主页首屏笔记（作者 ID → 笔记）。 */
    private val snapshots = mutableMapOf<String, List<PostedNote>>()
    private var refreshJob: Job? = null
    private var riskJob: Job? = null
    private var openJob: Job? = null
    private var lastVisitAt = -VISIT_INTERVAL_MS

    init {
        viewModelScope.launch {
            cache.load().forEach { (id, json) -> decodeNotes(json)?.let { snapshots[id] = it } }
            follows.observe().collect { authors ->
                // 取消关注后立即从时间线移除该作者的笔记
                val ids = authors.map { it.userId }.toSet()
                (snapshots.keys - ids).forEach { cache.delete(it) }
                snapshots.keys.retainAll(ids)
                _state.update { it.copy(authors = authors, feed = mergeFeed(snapshots)) }
            }
        }
    }

    /** 下拉刷新：先读未看数，再依次访问需要更新的作者。 */
    fun refresh() {
        if (refreshJob?.isActive == true || openJob?.isActive == true) return
        val authors = _state.value.authors
        if (authors.isEmpty()) return
        _state.update { it.copy(refreshing = true, checkingUnread = true, done = 0, total = 0, message = null) }
        // 先挂上风控监听再开始加载页面（events 是不重放的热流）
        riskJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.events.first { it is NoteListEvent.Failed && it.risk }
            refreshJob?.cancel()
            finish("触发风控，已停止刷新")
        }
        refreshJob = viewModelScope.launch {
            val unread = paced { source.followingUnread(selfUserId) }
            if (unread is UnreadResult.Failed && unread.risk) {
                finish("触发风控，已停止刷新")
                return@launch
            }
            val counts = (unread as? UnreadResult.Counts)?.unread
            // 未看数为 0 且有缓存的作者跳过；不在弹窗首屏里（未知）的照常访问
            val targets = if (counts == null) authors
            else authors.filter { it.userId !in snapshots || (counts[it.userId] ?: 1) > 0 }
            val skipped = authors.size - targets.size
            _state.update { it.copy(checkingUnread = false, total = targets.size, unread = counts ?: it.unread) }
            var failed = 0
            var consecutive = 0
            targets.forEachIndexed { i, author ->
                val snapshot = visit(author.profileUrl)
                if (snapshot == null) {
                    failed++
                    consecutive++
                    if (consecutive >= MAX_CONSECUTIVE_FAILURES) {
                        finish("连续 $consecutive 位作者加载失败，已停止")
                        return@launch
                    }
                } else {
                    consecutive = 0
                    // 刷新途中被取消关注的作者不再加入
                    store(author.userId, snapshot)
                }
                _state.update { it.copy(done = i + 1, feed = mergeFeed(snapshots)) }
            }
            finish(
                when {
                    failed > 0 -> "$failed 位作者更新失败"
                    skipped > 0 -> "$skipped 位作者没有未看的新笔记，沿用上次的内容"
                    else -> null
                },
            )
        }
    }

    /** 离开动态页：停止刷新，保留已读到的结果。 */
    fun pause() {
        refreshJob?.cancel()
        openJob?.cancel()
        riskJob?.cancel()
        _state.update { it.copy(refreshing = false, checkingUnread = false, openingNoteId = null) }
    }

    /** 点动态里的笔记：确保第二引擎停在作者主页，再通知界面去详情页点卡片。 */
    fun openNote(item: FeedNote) {
        if (openJob?.isActive == true) return
        if (refreshJob?.isActive == true) {
            _state.update { it.copy(message = "正在刷新，请稍后再打开") }
            return
        }
        val author = _state.value.authors.firstOrNull { it.userId == item.authorId } ?: return
        val noteId = item.note.note_id
        openJob = viewModelScope.launch {
            _state.update { it.copy(openingNoteId = noteId, message = null) }
            val loc = source.location()
            val atAuthor = !loc.stale && loc.path.trimEnd('/') == "/user/profile/${author.userId}"
            val notes = snapshots[author.userId].takeIf { atAuthor }
                ?: visit(author.profileUrl)?.also { notes ->
                    // 打开途中被取消关注的作者不再加入时间线
                    store(author.userId, notes)
                }
            val order = notes?.map { it.note_id }
            _state.update { it.copy(openingNoteId = null, feed = mergeFeed(snapshots)) }
            if (order == null || noteId !in order) {
                _state.update { it.copy(message = "笔记打开失败，请刷新后重试") }
            } else {
                _effects.tryEmit(Effect.OpenNote(noteId, order))
            }
        }
    }

    /** 限频后打开一位作者的主页，返回首屏笔记；失败返回 null。 */
    private suspend fun visit(url: String): List<PostedNote>? = paced { source.openProfile(url)?.notes }

    /**
     * 限频执行一次页面加载。间隔从上一页加载结束算起（打开页面可能轮询十几秒），
     * 服务端看到的间隔才真正不少于 5 秒。
     */
    private suspend fun <T> paced(load: suspend () -> T): T {
        val wait = lastVisitAt + VISIT_INTERVAL_MS - clock()
        if (wait > 0) delay(wait)
        lastVisitAt = clock()
        return try {
            load()
        } finally {
            lastVisitAt = clock()
        }
    }

    /** 记下作者的首屏笔记并写入缓存；途中已被取消关注的作者不再加入。 */
    private suspend fun store(authorId: String, notes: List<PostedNote>) {
        if (_state.value.authors.none { it.userId == authorId }) return
        snapshots[authorId] = notes
        cache.save(authorId, XhsHttp.json.encodeToString(NOTES, notes))
    }

    private fun finish(message: String?) {
        _state.update { it.copy(refreshing = false, checkingUnread = false, message = message) }
        riskJob?.cancel()
    }

    private companion object {
        const val VISIT_INTERVAL_MS = 5_000L
        const val MAX_CONSECUTIVE_FAILURES = 2
        val NOTES = ListSerializer(PostedNote.serializer())

        fun decodeNotes(json: String): List<PostedNote>? =
            runCatching { XhsHttp.json.decodeFromString(NOTES, json) }.getOrNull()
    }
}

/** 合并各作者首屏笔记：按发布时间倒序，同一笔记只保留一次（无时间的排在最后）。 */
internal fun mergeFeed(snapshots: Map<String, List<PostedNote>>): List<DynamicViewModel.FeedNote> =
    snapshots.flatMap { (authorId, notes) -> notes.map { DynamicViewModel.FeedNote(it, authorId) } }
        .distinctBy { it.note.note_id }
        .sortedByDescending { it.note.time }
