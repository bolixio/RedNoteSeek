package com.rednoteseek.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rednoteseek.core.network.api.SearchItem
import com.rednoteseek.core.storage.history.SearchHistoryRepository
import com.rednoteseek.core.web.AiSummaryEvent
import com.rednoteseek.core.web.SearchAccumulator
import com.rednoteseek.core.web.SearchMode
import com.rednoteseek.core.web.SearchEvent
import com.rednoteseek.core.web.SearchSort
import com.rednoteseek.core.web.SearchSource
import com.rednoteseek.core.web.SourceNote
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

class SearchViewModel(
    private val source: SearchSource,
    private val history: SearchHistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    initialMode: SearchMode = SearchMode.NOTES,
    /** 用户切换模式时回调（由调用方写回设置）。 */
    private val onModeChange: suspend (SearchMode) -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _historyList = MutableStateFlow<List<String>>(emptyList())
    val historyList: StateFlow<List<String>> = _historyList.asStateFlow()

    private val _sort = MutableStateFlow(SearchSort.GENERAL)
    val sort: StateFlow<SearchSort> = _sort.asStateFlow()

    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    /** 当前关键词的点点 AI 总结；没有（或不在 AI 模式）时为 null。 */
    private val _summary = MutableStateFlow<Summary?>(null)
    val summary: StateFlow<Summary?> = _summary.asStateFlow()

    /** 参考来源弹层；未打开时为 null。 */
    private val _sources = MutableStateFlow<SourcesSheet?>(null)
    val sources: StateFlow<SourcesSheet?> = _sources.asStateFlow()

    /** 「继续问点点」进行中（卡片按钮显示忙碌）。 */
    private val _continuing = MutableStateFlow(false)
    val continuing: StateFlow<Boolean> = _continuing.asStateFlow()

    /** 一次性提示文案。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var continueJob: Job? = null
    /** 本次运行里各关键词「继续问」得到的会话：再点同一关键词直接进入，不再新建会话。 */
    private val continued = mutableMapOf<String, String>()

    private var keyword: String? = null
    private var collectJob: Job? = null
    private var lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
    private var retryJob: Job? = null
    private var watchJob: Job? = null
    private var applyJob: Job? = null
    private var openJob: Job? = null
    private var summaryJob: Job? = null
    private var sourcesJob: Job? = null
    /** 当前关键词已收到的参考来源：网页在同一页面第二次打开抽屉时走缓存，不再请求。 */
    private var sourceNotes: List<SourceNote>? = null

    init {
        viewModelScope.launch {
            history.observe().collect { _historyList.value = it }
        }
    }

    /** 打开搜索结果（综合排序）。结果由页面自己请求，从 [SearchSource.events] 中按关键词与排序收取。 */
    fun search(keyword: String) {
        val kw = keyword.trim()
        // 换词时放弃进行中的继续问（网页若停在 /ai_chat_tab，新搜索的页内换词会回退整页加载）
        val prevContinue = continueJob
        prevContinue?.cancel()
        _continuing.value = false
        applyJob?.cancel()
        openJob?.cancel()
        resetSummary()
        if (kw.isEmpty()) {
            this.keyword = null
            collectJob?.cancel()
            retryJob?.cancel()
            watchJob?.cancel()
            _sort.value = SearchSort.GENERAL
            _state.value = State.Idle
            return
        }
        this.keyword = kw
        _sort.value = SearchSort.GENERAL
        // 先收集再打开：events / summaries 是不重放的热流（viewModelScope 用 Main.immediate，launch 会立即执行到 collect）
        startCollecting(kw, SearchSort.GENERAL)
        if (_mode.value == SearchMode.AI) startSummary(kw)
        val mode = _mode.value
        openJob = viewModelScope.launch {
            // 被取消的继续问还要在 NonCancellable 里把网页退回搜索页，等它收尾再打开，免得两边同时操作主引擎
            prevContinue?.join()
            source.open(kw, mode)
        }
    }

    /** 切换普通搜索 / AI 总结：写回设置；有关键词时按新模式重新搜索（会整页加载另一个结果页）。 */
    fun setMode(mode: SearchMode) {
        if (mode == _mode.value) return
        _mode.value = mode
        viewModelScope.launch { onModeChange(mode) }
        keyword?.let(::search)
    }

    /** 在网页筛选面板里切换排序；网页随即重新请求第一页。 */
    fun setSort(sort: SearchSort) {
        val previous = _sort.value
        if (sort == previous) return
        _sort.value = sort
        val kw = keyword ?: return
        startCollecting(kw, sort)
        applyJob?.cancel()
        // 进行中的继续问也要放弃，并等它退回搜索页后再点筛选面板
        val prevContinue = continueJob
        prevContinue?.cancel()
        _continuing.value = false
        applyJob = viewModelScope.launch {
            prevContinue?.join()
            // 换词可能还在等页内导航 / 回退整页加载，等它结束再点筛选面板，否则点在旧页面上
            openJob?.join()
            // 只在当前排序仍是这次要切的排序时才回滚，避免迟到的失败覆盖新操作
            if (!source.applySort(sort) && _sort.value == sort) {
                collectJob?.cancel()
                _sort.value = previous
                _state.value = State.Error("无法切换排序，请重新搜索")
            }
        }
    }

    /**
     * 必须先开始收集再触发网页请求：events 是不重放的热流
     * （viewModelScope 用 Main.immediate，launch 会立即执行到 collect）。
     */
    private fun startCollecting(kw: String, sort: SearchSort) {
        collectJob?.cancel()
        retryJob?.cancel()
        watchJob?.cancel()
        lastLoadMoreAt = -LOAD_MORE_INTERVAL_MS
        _state.value = State.Loading
        val accumulator = SearchAccumulator()
        collectJob = viewModelScope.launch {
            launch {
                delay(FIRST_PAGE_TIMEOUT_MS)
                if (_state.value == State.Loading) _state.value = State.Error("加载超时，请检查网络后重试")
            }
            source.events
                .filter { (it.keyword == null || it.keyword == kw) && (it.sort == null || it.sort == sort.tag) }
                .collect { event ->
                    when (event) {
                        // 已有结果时，后续某页失败不清空列表
                        is SearchEvent.Failed ->
                            if (_state.value !is State.Loaded) _state.value = State.Error(event.message)
                        is SearchEvent.Page -> {
                            val first = _state.value !is State.Loaded
                            val items = accumulator.add(event)
                            watchJob?.cancel()
                            _state.value =
                                if (items.isEmpty()) State.Empty
                                else State.Loaded(items, accumulator.hasMore, kw)
                            if (first && items.isNotEmpty()) history.add(kw)
                        }
                    }
                }
        }
    }

    /** 结果列表接近底部时调用：让页面滚动加载下一页。限频；上一次翻页失败后等用户点重试。 */
    fun loadMore() {
        val s = _state.value as? State.Loaded ?: return
        if (!s.hasMore || s.loadMoreFailed) return
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
        watchLoadMore(s.items.size)
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
                if (s is State.Loaded && s.hasMore && s.items.size <= countBefore) s.copy(loadMoreFailed = true) else s
            }
        }
    }

    /** 点来源笔记：先确保网页的「参考来源」抽屉开着（详情返回后网页可能已关掉），再交给 [open] 打开详情。 */
    fun openSourceNote(open: () -> Unit) {
        viewModelScope.launch {
            source.openSummarySources()
            open()
        }
    }

    /** 打开参考来源：点网页的「ai总结N篇笔记生成」，列表从截获的 dqa/detail 得到（同一关键词已有就直接用）。 */
    fun openSources() {
        if (_summary.value == null || _continuing.value) return
        _sources.value = sourceNotes?.let(SourcesSheet::Ready) ?: SourcesSheet.Loading
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            if (!source.openSummarySources()) {
                if (_sources.value == SourcesSheet.Loading) _sources.value = SourcesSheet.Failed
                return@launch
            }
            delay(SOURCES_TIMEOUT_MS)
            if (_sources.value == SourcesSheet.Loading) _sources.value = SourcesSheet.Failed
        }
    }

    fun closeSources() {
        sourcesJob?.cancel()
        if (_sources.value == null) return
        _sources.value = null
        source.closeSummarySources()
    }

    /**
     * 「继续问点点」：总结生成完才能用。在网页上点「问点点」拿到会话 ID 后交给 [onOpened]（导航到对话页，标题用关键词）；
     * 失败提示「无法继续提问，请稍后再试」。同一关键词本次运行里已继续问过，就直接进入那个会话。
     * 新搜索会取消进行中的继续问（见 [search]）。
     */
    fun continueInChat(onOpened: (conversationId: String, title: String) -> Unit) {
        val kw = keyword ?: return
        val sm = _summary.value ?: return
        if (sm.streaming || _continuing.value) return
        continued[kw]?.let {
            onOpened(it, kw)
            return
        }
        _continuing.value = true
        continueJob = viewModelScope.launch {
            val id = try {
                source.continueInChat()
            } finally {
                _continuing.value = false
            }
            if (id == null) {
                _messages.emit("无法继续提问，请稍后再试")
            } else {
                continued[kw] = id
                onOpened(id, kw)
            }
        }
    }

    private fun resetSummary() {
        summaryJob?.cancel()
        _summary.value = null
        sourceNotes = null
        closeSources()
    }

    /**
     * 收集 [kw] 的总结：热门词是一次性的 Instant；冷门词先有 Generating（带会话 ID），
     * 之后同一会话的进度、正文增量依次追加，直到 Done，或 [STREAM_IDLE_TIMEOUT_MS] 内没有新事件。
     */
    private fun startSummary(kw: String) {
        summaryJob = viewModelScope.launch {
            var conversation: String? = null
            var idle: Job? = null
            fun finish() {
                idle?.cancel()
                conversation = null
                // 一个字都没收到就不显示卡片
                _summary.update { s -> s?.takeIf { it.markdown.isNotBlank() }?.copy(progress = null, streaming = false) }
            }
            fun touch() {
                idle?.cancel()
                idle = launch {
                    delay(STREAM_IDLE_TIMEOUT_MS)
                    finish()
                }
            }
            source.summaries.collect { e ->
                when (e) {
                    is AiSummaryEvent.Instant -> if (e.keyword == null || e.keyword == kw) {
                        idle?.cancel()
                        conversation = null
                        _summary.value = Summary(e.markdown, null, e.sourceLabel, streaming = false)
                    }
                    is AiSummaryEvent.Generating -> if (e.keyword == kw) {
                        conversation = e.conversationId
                        _summary.value = Summary("", null, null, streaming = true)
                        touch()
                    }
                    is AiSummaryEvent.Progress -> if (e.conversationId == conversation) {
                        _summary.update { it?.copy(progress = e.text) }
                        touch()
                    }
                    is AiSummaryEvent.SourceCount -> if (e.conversationId == conversation) {
                        _summary.update { it?.copy(sourceLabel = e.text) }
                        touch()
                    }
                    is AiSummaryEvent.Delta -> if (e.conversationId == conversation) {
                        _summary.update { it?.copy(markdown = it.markdown + e.text, progress = null) }
                        touch()
                    }
                    is AiSummaryEvent.Done -> if (e.conversationId == conversation) finish()
                    is AiSummaryEvent.Sources -> if (e.keyword == null || e.keyword == kw) {
                        sourceNotes = e.notes
                        if (_sources.value == SourcesSheet.Loading) _sources.value = SourcesSheet.Ready(e.notes)
                    }
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data object Empty : State
        data class Loaded(
            val items: List<SearchItem>,
            val hasMore: Boolean,
            val keyword: String,
            val loadMoreFailed: Boolean = false,
        ) : State
        data class Error(val message: String) : State
    }

    /** 点点 AI 总结。[progress] 只在生成中、还没出正文时有；[sourceLabel] 如「ai总结58篇笔记生成」。 */
    data class Summary(
        val markdown: String,
        val progress: String?,
        val sourceLabel: String?,
        val streaming: Boolean,
    )

    sealed interface SourcesSheet {
        data object Loading : SourcesSheet
        data class Ready(val notes: List<SourceNote>) : SourcesSheet
        data object Failed : SourcesSheet
    }

    private companion object {
        /** 实时生成时这么久没有新的流式事件就收尾（真机上整段生成约 16 秒，片段间隔最长约 4 秒）。 */
        const val STREAM_IDLE_TIMEOUT_MS = 20_000L
        const val SOURCES_TIMEOUT_MS = 8_000L
        const val FIRST_PAGE_TIMEOUT_MS = 20_000L
        const val LOAD_MORE_INTERVAL_MS = 3_000L
        const val LOAD_MORE_TIMEOUT_MS = 10_000L
    }
}
