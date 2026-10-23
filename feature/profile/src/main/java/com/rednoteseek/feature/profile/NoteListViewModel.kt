package com.rednoteseek.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.network.api.ProfileUser
import com.rednoteseek.core.web.NoteListAccumulator
import com.rednoteseek.core.web.NoteListEvent
import com.rednoteseek.core.web.NoteListKind
import com.rednoteseek.core.web.NoteListSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 用户主页、「我」的收藏与专辑共用的笔记列表 ViewModel（数据都来自第二网页引擎）。
 *
 * 打开时必须先订阅 [NoteListSource.events] 再让网页加载：events 是不重放的热流，
 * 网页一加载就会发出第一页请求，晚订阅会漏掉。订阅用 [CoroutineStart.UNDISPATCHED]，
 * 保证 launch 返回前已经挂在 collect 上。
 */
class NoteListViewModel(
    private val source: NoteListSource,
    private val target: Target,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    sealed interface Target {
        data class Profile(val url: String) : Target
        data class Collections(val selfUserId: String) : Target
        data class Board(val boardId: String) : Target
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val kind = when (target) {
        is Target.Profile -> NoteListKind.POSTED
        is Target.Collections -> NoteListKind.COLLECTED
        is Target.Board -> NoteListKind.BOARD
    }
    /** 翻页事件的归属：主页 / 收藏为用户 ID，专辑为专辑 ID。 */
    private val userId: String = when (target) {
        is Target.Profile -> PROFILE_ID.find(target.url)?.groupValues?.get(1).orEmpty()
        is Target.Collections -> target.selfUserId
        is Target.Board -> target.boardId
    }
    private val targetPath = if (target is Target.Board) "/board/$userId" else "/user/profile/$userId"

    private var user: ProfileUser? = null
    private var opened = false
    /** 触发风控后不再自动打开或翻页。 */
    private var stopped = false
    /** 事件收集与首屏超时。 */
    private var collectJob: Job? = null
    /** 正在让网页打开本页（openProfile / openCollections 尚未返回）。 */
    private var openJob: Job? = null
    private var checkJob: Job? = null
    private var lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
    private var retryJob: Job? = null
    private var watchJob: Job? = null

    /**
     * 页面每次进入组合时调用。首次直接打开；之后检查第二引擎当前所在页面，
     * 不在本页（例如别的主页 / 收藏页把引擎带走了）就重新打开。
     * 路径以 `/explore/` 开头说明笔记弹层还没关完、引擎仍在本页之上，不算离开，避免无谓重开。
     */
    fun ensureOpen() {
        if (!opened) {
            open()
            return
        }
        if (stopped || openJob?.isActive == true || checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            val loc = source.location()
            // 过期标记表示这是上一次加载留下的旧页面，视为不在目标页
            val atTarget = !loc.stale && (loc.path.startsWith("/explore/") ||
                (loc.path.trimEnd('/') == targetPath &&
                    (target !is Target.Collections || loc.collectTabActive)))
            if (!atTarget && !stopped) open()
        }
    }

    /** 错误页的「重试」：清空列表重新打开。 */
    fun retry() = open()

    /**
     * 下拉刷新：重新打开页面，但旧列表留着直到新的第一页到达；打开失败或超时就保留旧列表。
     * 触发风控后不刷新。
     */
    fun refresh() {
        if (stopped || _state.value !is State.Loaded) return
        open(keepContent = true)
    }

    private var seenChanges: Int? = null

    /**
     * 页面显示时传入收藏 / 专辑写操作的次数（见 XhsWebEngine.collectChanges）：
     * 与上次看到的不同说明列表已过时（如在详情里换了专辑），重新打开。首次调用只记下；
     * [reload] 为 false（本页自己的改名 / 删除）时也只记下。
     */
    fun onCollectChanges(changes: Int, reload: Boolean = true) {
        val previous = seenChanges
        seenChanges = changes
        if (reload && previous != null && previous != changes && opened && !stopped) open()
    }

    private fun open(keepContent: Boolean = false) {
        opened = true
        stopped = false
        cancelAll()
        lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
        user = null
        val previous = _state.value as? State.Loaded
        _state.value = if (keepContent && previous != null) previous.copy(refreshing = true) else State.Loading
        val accumulator = NoteListAccumulator()
        // 整条链路都 UNDISPATCHED：launch 返回时收集已经挂上，之后才让网页加载
        collectJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.events
                    .filter { it.kind == kind && (it.userId == null || it.userId == userId) }
                    .collect { handle(it, accumulator) }
            }
            launch {
                delay(FIRST_PAGE_TIMEOUT_MS)
                failOpen("加载超时，请重试")
            }
        }
        openJob = viewModelScope.launch {
            when (target) {
                is Target.Profile -> {
                    val snapshot = source.openProfile(target.url)
                    if (snapshot == null) {
                        failOpen("主页加载失败")
                    } else {
                        user = snapshot.user
                        accumulator.seed(snapshot.notes, snapshot.has_more)
                        publish(accumulator)
                    }
                }
                is Target.Collections ->
                    // 第一页经 events 到达
                    if (!source.openCollections(target.selfUserId)) failOpen("收藏加载失败")
                is Target.Board -> {
                    val first = source.openBoard(target.boardId)
                    if (first == null) {
                        failOpen("专辑加载失败")
                    } else {
                        accumulator.seed(first.notes, first.has_more)
                        publish(accumulator)
                    }
                }
            }
        }
    }

    /**
     * 打开失败 / 首屏超时：刷新中就停掉刷新、保留旧列表；还没有内容时显示错误；
     * 已有新内容（第一页已到）则不处理。
     */
    private fun failOpen(message: String) {
        val s = _state.value
        when {
            s is State.Loaded && s.refreshing -> _state.value = s.copy(refreshing = false)
            s !is State.Loaded -> _state.value = State.Error(message, retryable = true)
        }
    }

    private fun handle(event: NoteListEvent, accumulator: NoteListAccumulator) {
        when (event) {
            is NoteListEvent.Page -> {
                accumulator.add(event)
                watchJob?.cancel()
                publish(accumulator)
            }
            is NoteListEvent.Failed -> when {
                event.risk -> {
                    stopped = true
                    _state.value = State.Error("触发风控，请稍后再试", retryable = false)
                    cancelAll()
                }
                // 已有结果时，后续某页失败不清空列表（由翻页失败监视提示）；刷新中失败则停掉刷新
                else -> failOpen(event.message)
            }
        }
    }

    private fun publish(accumulator: NoteListAccumulator) {
        val items = accumulator.items
        _state.value = if (items.isEmpty()) State.Empty else State.Loaded(user, items, accumulator.hasMore)
    }

    private fun cancelAll() {
        retryJob?.cancel()
        watchJob?.cancel()
        openJob?.cancel()
        collectJob?.cancel()
    }

    /** 列表接近底部时调用：让网页滚动加载下一页。限频；上一次翻页失败后等用户点重试。 */
    fun loadMore() {
        if (stopped) return
        val s = _state.value as? State.Loaded ?: return
        // 刷新中网页正在重新加载，等新的第一页
        if (!s.hasMore || s.loadMoreFailed || s.refreshing) return
        val now = clock()
        if (now - lastLoadMoreAt < LOAD_MORE_INTERVAL_MS) {
            // 被限频挡下：安排一次延迟重试，用户停在底部不动时也能翻到下一页
            if (retryJob?.isActive != true) {
                retryJob = viewModelScope.launch {
                    delay(LOAD_MORE_INTERVAL_MS - (now - lastLoadMoreAt))
                    loadMore()
                }
            }
            return
        }
        lastLoadMoreAt = now
        source.loadMore()
        watchLoadMore(s.notes.size)
    }

    /** 底部「重试」：清除失败标记后立即翻页。 */
    fun retryLoadMore() {
        _state.update { (it as? State.Loaded)?.copy(loadMoreFailed = false) ?: it }
        lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
        loadMore()
    }

    /** 翻页发出后一段时间列表没有增长，视为失败，底部改为「加载失败，重试」。 */
    private fun watchLoadMore(countBefore: Int) {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            delay(LOAD_MORE_TIMEOUT_MS)
            _state.update { s ->
                if (s is State.Loaded && s.hasMore && s.notes.size <= countBefore) s.copy(loadMoreFailed = true) else s
            }
        }
    }

    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Error(val message: String, val retryable: Boolean) : State
        data class Loaded(
            val user: ProfileUser?,
            val notes: List<PostedNote>,
            val hasMore: Boolean,
            val loadMoreFailed: Boolean = false,
            /** 下拉刷新中：列表是旧的，新的第一页还没到。 */
            val refreshing: Boolean = false,
        ) : State
    }

    private companion object {
        val PROFILE_ID = Regex("/user/profile/([0-9a-zA-Z]+)")
        const val FIRST_PAGE_TIMEOUT_MS = 20_000L
        const val LOAD_MORE_INTERVAL_MS = 3_000L
        const val LOAD_MORE_TIMEOUT_MS = 10_000L
    }
}
