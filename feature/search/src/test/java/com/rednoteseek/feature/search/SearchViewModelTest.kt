package com.rednoteseek.feature.search

import com.rednoteseek.core.network.api.SearchItem
import com.rednoteseek.core.storage.history.SearchHistoryDao
import com.rednoteseek.core.storage.history.SearchHistoryEntity
import com.rednoteseek.core.storage.history.SearchHistoryRepository
import com.rednoteseek.core.web.AiSummaryEvent
import com.rednoteseek.core.web.SearchEvent
import com.rednoteseek.core.web.SearchMode
import com.rednoteseek.core.web.SearchSort
import com.rednoteseek.core.web.SearchSource
import com.rednoteseek.core.web.SourceNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeSearchSource()
    private val dao = FakeHistoryDao()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel() =
        SearchViewModel(source, SearchHistoryRepository(dao), clock = { testScheduler.currentTime })

    private fun page(keyword: String?, page: Int?, vararg ids: String, hasMore: Boolean = true, sort: String? = null) =
        SearchEvent.Page(keyword, page, ids.map { SearchItem(id = it) }, hasMore, sort = sort)

    @Test fun searchOpensPageAndShowsMatchingResults() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search(" 咖啡 ")
        assertEquals(listOf("咖啡"), source.opened)
        assertEquals(SearchViewModel.State.Loading, vm.state.value)

        source.events.emit(page("咖啡", 1, "a", "b"))

        val state = vm.state.value as SearchViewModel.State.Loaded
        assertEquals(listOf("a", "b"), state.items.map { it.id })
        assertEquals("咖啡", state.keyword)
        assertEquals(listOf("咖啡"), dao.keywords())
    }

    @Test fun blankKeywordGoesIdle() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("   ")
        assertEquals(SearchViewModel.State.Idle, vm.state.value)
        assertTrue(source.opened.isEmpty())
    }

    @Test fun newSearchCancelsPendingOpen() = runTest(dispatcher) {
        val vm = newViewModel()
        source.openGate = CompletableDeferred()
        vm.search("咖啡")
        vm.search("奶茶")
        assertEquals(listOf("咖啡", "奶茶"), source.opened)
        assertEquals(listOf("咖啡"), source.cancelledOpens)
    }

    @Test fun blankSearchCancelsPendingOpen() = runTest(dispatcher) {
        val vm = newViewModel()
        source.openGate = CompletableDeferred()
        vm.search("咖啡")
        vm.search("")
        assertEquals(listOf("咖啡"), source.cancelledOpens)
        assertEquals(SearchViewModel.State.Idle, vm.state.value)
    }

    @Test fun ignoresResultsOfOtherKeywords() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("奶茶", 1, "x"))
        assertEquals(SearchViewModel.State.Loading, vm.state.value)
    }

    @Test fun unknownKeywordCountsAsMatch() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page(null, null, "a"))
        assertTrue(vm.state.value is SearchViewModel.State.Loaded)
    }

    @Test fun emptyFirstPageShowsEmpty() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, hasMore = false))
        assertEquals(SearchViewModel.State.Empty, vm.state.value)
        assertTrue(dao.keywords().isEmpty())
    }

    @Test fun failureBeforeResultsShowsError() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(SearchEvent.Failed("咖啡", "HTTP 461"))
        assertEquals(SearchViewModel.State.Error("HTTP 461"), vm.state.value)
    }

    @Test fun failureAfterResultsKeepsResults() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        source.events.emit(SearchEvent.Failed("咖啡", "HTTP 461"))
        assertTrue(vm.state.value is SearchViewModel.State.Loaded)
    }

    @Test fun timesOutWithoutResults() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        advanceTimeBy(20_001)
        assertEquals(SearchViewModel.State.Error("加载超时，请检查网络后重试"), vm.state.value)
    }

    @Test fun newSearchDropsOldResults() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.search("奶茶")
        assertEquals(SearchViewModel.State.Loading, vm.state.value)
        source.events.emit(page("奶茶", 1, "x"))
        assertEquals(listOf("x"), (vm.state.value as SearchViewModel.State.Loaded).items.map { it.id })
    }

    @Test fun loadMoreIsThrottled() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.loadMore()
        vm.loadMore()
        assertEquals(1, source.loadMoreCalls)
        advanceTimeBy(3_001)
        vm.loadMore()
        assertEquals(2, source.loadMoreCalls)
    }

    @Test fun newSearchResetsLoadMoreThrottle() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.loadMore()
        assertEquals(1, source.loadMoreCalls)
        vm.search("奶茶")
        source.events.emit(page("奶茶", 1, "x"))
        vm.loadMore()
        assertEquals(2, source.loadMoreCalls)
    }

    @Test fun throttledLoadMoreRetriesOnce() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.loadMore()
        vm.loadMore()
        assertEquals(1, source.loadMoreCalls)
        advanceTimeBy(3_001)
        assertEquals(2, source.loadMoreCalls)
        advanceTimeBy(10_000)
        assertEquals(2, source.loadMoreCalls)
    }

    @Test fun loadMoreStopsWhenNoMorePages() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a", hasMore = false))
        vm.loadMore()
        assertEquals(0, source.loadMoreCalls)
    }

    @Test fun changingSortRestartsResultsForThatSort() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a", sort = "general"))
        vm.setSort(SearchSort.LATEST)
        assertEquals(listOf(SearchSort.LATEST), source.sortsApplied)
        assertEquals(SearchSort.LATEST, vm.sort.value)
        assertEquals(SearchViewModel.State.Loading, vm.state.value)
        source.events.emit(page("咖啡", 1, "x", sort = "general"))   // 旧排序的迟到结果被忽略
        assertEquals(SearchViewModel.State.Loading, vm.state.value)
        source.events.emit(page("咖啡", 1, "n1", "n2", sort = "time_descending"))
        assertEquals(listOf("n1", "n2"), (vm.state.value as SearchViewModel.State.Loaded).items.map { it.id })
    }

    @Test fun sameSortIsIgnored() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        vm.setSort(SearchSort.GENERAL)
        assertTrue(source.sortsApplied.isEmpty())
    }

    @Test fun failedSortFallsBack() = runTest(dispatcher) {
        source.applyResult = false
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a", sort = "general"))
        vm.setSort(SearchSort.MOST_LIKED)
        assertEquals(SearchSort.GENERAL, vm.sort.value)
        assertEquals(SearchViewModel.State.Error("无法切换排序，请重新搜索"), vm.state.value)
    }

    @Test fun newSearchResetsSort() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.setSort(SearchSort.LATEST)
        vm.search("奶茶")
        assertEquals(SearchSort.GENERAL, vm.sort.value)
    }

    @Test fun lateSortFailureDoesNotClobberNewSearch() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        val gate = CompletableDeferred<Boolean>()
        source.applyGate = gate
        vm.setSort(SearchSort.LATEST)
        vm.search("奶茶")
        gate.complete(false)
        assertEquals(SearchSort.GENERAL, vm.sort.value)
        assertEquals(SearchViewModel.State.Loading, vm.state.value)
        source.events.emit(page("奶茶", 1, "x"))
        assertEquals(listOf("x"), (vm.state.value as SearchViewModel.State.Loaded).items.map { it.id })
    }

    @Test fun sortWaitsForPendingOpen() = runTest(dispatcher) {
        val vm = newViewModel()
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate
        vm.search("咖啡")
        vm.setSort(SearchSort.LATEST)
        assertEquals(emptyList<SearchSort>(), source.sortsApplied)
        gate.complete(Unit)
        assertEquals(listOf(SearchSort.LATEST), source.sortsApplied)
    }

    @Test fun loadMoreWithoutNewPageMarksFailure()= runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.loadMore()
        advanceTimeBy(10_001)
        assertTrue((vm.state.value as SearchViewModel.State.Loaded).loadMoreFailed)
        vm.loadMore()   // 失败后不再自动请求
        assertEquals(1, source.loadMoreCalls)
        vm.retryLoadMore()
        assertEquals(2, source.loadMoreCalls)
        assertEquals(false, (vm.state.value as SearchViewModel.State.Loaded).loadMoreFailed)
    }

    @Test fun newPageClearsLoadMoreWatch() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.events.emit(page("咖啡", 1, "a"))
        vm.loadMore()
        source.events.emit(page("咖啡", 2, "b"))
        advanceTimeBy(10_001)
        assertEquals(false, (vm.state.value as SearchViewModel.State.Loaded).loadMoreFailed)
    }

    private fun TestScope.aiViewModel(modes: MutableList<SearchMode> = mutableListOf()) =
        SearchViewModel(
            source, SearchHistoryRepository(dao), clock = { testScheduler.currentTime },
            initialMode = SearchMode.AI, onModeChange = { modes += it },
        )

    private fun note(id: String) = SourceNote(id, "t$id", "", 0, 0, "a", "", 0, false)

    @Test fun aiModeOpensAiResultPage() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        assertEquals(listOf(SearchMode.AI), source.openedModes)
        assertEquals(SearchMode.AI, vm.mode.value)
    }

    @Test fun setModeSavesAndResearches() = runTest(dispatcher) {
        val modes = mutableListOf<SearchMode>()
        val vm = SearchViewModel(source, SearchHistoryRepository(dao), clock = { testScheduler.currentTime }, onModeChange = { modes += it })
        vm.search("咖啡")
        vm.setSort(SearchSort.LATEST)
        vm.setMode(SearchMode.AI)
        assertEquals(listOf(SearchMode.AI), modes)
        assertEquals(listOf(SearchMode.NOTES, SearchMode.AI), source.openedModes)
        assertEquals(listOf("咖啡", "咖啡"), source.opened)
        // 切模式会整页加载，网页排序回到综合
        assertEquals(SearchSort.GENERAL, vm.sort.value)
        vm.setMode(SearchMode.AI)
        assertEquals(listOf(SearchMode.AI), modes)
    }

    @Test fun setModeWithoutKeywordOnlySaves() = runTest(dispatcher) {
        val modes = mutableListOf<SearchMode>()
        val vm = SearchViewModel(source, SearchHistoryRepository(dao), onModeChange = { modes += it })
        vm.setMode(SearchMode.AI)
        assertEquals(listOf(SearchMode.AI), modes)
        assertTrue(source.opened.isEmpty())
    }

    @Test fun instantSummaryForCurrentKeyword() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("奶茶", "别的", null))
        assertEquals(null, vm.summary.value)
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "#### 胶囊机", "ai总结113篇笔记生成"))
        assertEquals(SearchViewModel.Summary("#### 胶囊机", null, "ai总结113篇笔记生成", streaming = false), vm.summary.value)
    }

    @Test fun notesModeIgnoresSummaries() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        assertEquals(null, vm.summary.value)
    }

    @Test fun generatedSummaryStreamsByConversation() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("根特")
        source.summaries.emit(AiSummaryEvent.Generating("根特", "c1"))
        assertEquals(SearchViewModel.Summary("", null, null, streaming = true), vm.summary.value)
        source.summaries.emit(AiSummaryEvent.Progress("c1", "搜索相关内容"))
        source.summaries.emit(AiSummaryEvent.SourceCount("c1", "ai总结58篇笔记生成"))
        source.summaries.emit(AiSummaryEvent.Delta("other", "别的会话"))
        source.summaries.emit(AiSummaryEvent.Delta("c1", "根特是"))
        source.summaries.emit(AiSummaryEvent.Delta("c1", "古城"))
        assertEquals(SearchViewModel.Summary("根特是古城", null, "ai总结58篇笔记生成", streaming = true), vm.summary.value)
        source.summaries.emit(AiSummaryEvent.Done("c1"))
        assertEquals(SearchViewModel.Summary("根特是古城", null, "ai总结58篇笔记生成", streaming = false), vm.summary.value)
    }

    @Test fun generationForOtherKeywordIgnored() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("根特")
        source.summaries.emit(AiSummaryEvent.Generating("布鲁日", "c2"))
        source.summaries.emit(AiSummaryEvent.Delta("c2", "布鲁日是"))
        assertEquals(null, vm.summary.value)
    }

    @Test fun doneWithoutTextHidesCard() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("根特")
        source.summaries.emit(AiSummaryEvent.Generating("根特", "c1"))
        source.summaries.emit(AiSummaryEvent.Done("c1"))
        assertEquals(null, vm.summary.value)
    }

    @Test fun idleStreamFinishesWithWhatArrived() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("根特")
        source.summaries.emit(AiSummaryEvent.Generating("根特", "c1"))
        source.summaries.emit(AiSummaryEvent.Delta("c1", "根特是"))
        advanceTimeBy(20_001)
        assertEquals(SearchViewModel.Summary("根特是", null, null, streaming = false), vm.summary.value)
    }

    @Test fun newSearchClearsSummaryAndClosesSources() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", "ai总结3篇笔记生成"))
        vm.openSources()
        vm.search("奶茶")
        assertEquals(null, vm.summary.value)
        assertEquals(null, vm.sources.value)
        assertEquals(1, source.closeSourcesCalls)
    }

    @Test fun sourcesLoadThenCacheForReopen() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", "ai总结2篇笔记生成"))
        vm.openSources()
        assertEquals(SearchViewModel.SourcesSheet.Loading, vm.sources.value)
        source.summaries.emit(AiSummaryEvent.Sources("咖啡", listOf(note("n1"), note("n2"))))
        assertEquals(SearchViewModel.SourcesSheet.Ready(listOf(note("n1"), note("n2"))), vm.sources.value)
        vm.closeSources()
        assertEquals(null, vm.sources.value)
        assertEquals(1, source.closeSourcesCalls)
        // 网页第二次打开抽屉走缓存、不再请求：直接用缓存的列表
        vm.openSources()
        assertEquals(SearchViewModel.SourcesSheet.Ready(listOf(note("n1"), note("n2"))), vm.sources.value)
        assertEquals(2, source.openSourcesCalls)
    }

    @Test fun modeChangeIsSavedFromViewModelScope() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<SearchMode>()
        val vm = SearchViewModel(
            source, SearchHistoryRepository(dao),
            onModeChange = { gate.await(); saved += it },
        )
        vm.setMode(SearchMode.AI)
        assertEquals(emptyList<SearchMode>(), saved)
        gate.complete(Unit)
        assertEquals(listOf(SearchMode.AI), saved)
    }

    @Test fun sourceNoteClickReopensDrawerFirst() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", "ai总结2篇笔记生成"))
        vm.openSources()
        source.summaries.emit(AiSummaryEvent.Sources("咖啡", listOf(note("n1"))))
        val before = source.openSourcesCalls
        var opened = false
        vm.openSourceNote { opened = true }
        assertEquals(before + 1, source.openSourcesCalls)
        assertEquals(true, opened)
    }

    @Test fun sourcesFailWhenPillMissingOrTimeout() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        source.openSourcesResult = false
        vm.openSources()
        assertEquals(SearchViewModel.SourcesSheet.Failed, vm.sources.value)
        source.openSourcesResult = true
        vm.openSources()
        assertEquals(SearchViewModel.SourcesSheet.Loading, vm.sources.value)
        advanceTimeBy(8_001)
        assertEquals(SearchViewModel.SourcesSheet.Failed, vm.sources.value)
    }

    @Test fun sourcesNeedSummary() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        vm.openSources()
        assertEquals(null, vm.sources.value)
        assertEquals(0, source.openSourcesCalls)
    }

    @Test fun continueInChatOpensConversationForKeyword() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", "ai总结2篇笔记生成"))
        val gate = CompletableDeferred<String?>()
        source.continueGate = gate
        val opened = mutableListOf<Pair<String, String>>()
        vm.continueInChat { id, title -> opened += id to title }
        assertEquals(true, vm.continuing.value)
        // 忙碌时再点不重复调用
        vm.continueInChat { id, title -> opened += id to title }
        assertEquals(1, source.continueCalls)
        gate.complete("c9")
        assertEquals(false, vm.continuing.value)
        assertEquals(listOf("c9" to "咖啡"), opened)
    }

    @Test fun continueInChatFailureShowsMessage() = runTest(dispatcher) {
        val vm = aiViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        source.continueResult = null
        var opened = false
        vm.continueInChat { _, _ -> opened = true }
        assertEquals(false, opened)
        assertEquals(false, vm.continuing.value)
        assertEquals(listOf("无法继续提问，请稍后再试"), messages)
    }

    @Test fun continueInChatReusesConversationForSameKeyword() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        val opened = mutableListOf<Pair<String, String>>()
        vm.continueInChat { id, title -> opened += id to title }
        vm.continueInChat { id, title -> opened += id to title }
        assertEquals(1, source.continueCalls)
        assertEquals(listOf("c1" to "咖啡", "c1" to "咖啡"), opened)
    }

    @Test fun newSearchCancelsContinue() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        val gate = CompletableDeferred<String?>()
        source.continueGate = gate
        var opened = false
        vm.continueInChat { _, _ -> opened = true }
        assertEquals(true, vm.continuing.value)
        vm.search("奶茶")
        assertEquals(false, vm.continuing.value)
        gate.complete("c9")
        assertEquals(false, opened)
    }

    @Test fun searchDuringContinueWaitsForCleanup() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        source.continueGate = CompletableDeferred()
        val cleanup = CompletableDeferred<Unit>()
        source.cleanupGate = cleanup
        vm.continueInChat { _, _ -> }
        source.events2.clear()
        vm.search("奶茶")
        assertEquals(emptyList<String>(), source.events2)
        cleanup.complete(Unit)
        assertEquals(listOf("cleanup", "open:奶茶"), source.events2)
    }

    @Test fun setSortCancelsContinue() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        source.continueGate = CompletableDeferred()
        val cleanup = CompletableDeferred<Unit>()
        source.cleanupGate = cleanup
        vm.continueInChat { _, _ -> }
        source.events2.clear()
        vm.setSort(SearchSort.LATEST)
        assertEquals(false, vm.continuing.value)
        assertEquals(emptyList<String>(), source.events2)
        cleanup.complete(Unit)
        assertEquals(listOf("cleanup", "sort"), source.events2)
    }

    @Test fun openSourcesIgnoredWhileContinuing() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("咖啡")
        source.summaries.emit(AiSummaryEvent.Instant("咖啡", "x", null))
        source.continueGate = CompletableDeferred()
        vm.continueInChat { _, _ -> }
        vm.openSources()
        assertEquals(null, vm.sources.value)
        assertEquals(0, source.openSourcesCalls)
    }

    @Test fun continueInChatNeedsFinishedSummary() = runTest(dispatcher) {
        val vm = aiViewModel()
        vm.search("根特")
        vm.continueInChat { _, _ -> }
        source.summaries.emit(AiSummaryEvent.Generating("根特", "c1"))
        vm.continueInChat { _, _ -> }
        assertEquals(0, source.continueCalls)
    }
}

private class FakeSearchSource : SearchSource {
    override val events = MutableSharedFlow<SearchEvent>()
    override val summaries = MutableSharedFlow<AiSummaryEvent>()
    val opened = mutableListOf<String>()
    val openedModes = mutableListOf<SearchMode>()
    val cancelledOpens = mutableListOf<String>()
    /** 非空时 open 挂起直到它完成，用来测新搜索取消旧的 open。 */
    var openGate: CompletableDeferred<Unit>? = null
    var loadMoreCalls = 0
    /** 按发生顺序记录 open / cleanup，用来测串行。 */
    val events2 = mutableListOf<String>()
    override suspend fun open(keyword: String, mode: SearchMode) {
        events2 += "open:$keyword"
        opened += keyword
        openedModes += mode
        try {
            openGate?.await()
        } catch (e: CancellationException) {
            cancelledOpens += keyword
            throw e
        }
    }
    override fun loadMore() { loadMoreCalls++ }
    var applyResult = true
    val sortsApplied = mutableListOf<SearchSort>()
    var applyGate: CompletableDeferred<Boolean>? = null
    override suspend fun applySort(sort: SearchSort): Boolean {
        events2 += "sort"
        sortsApplied += sort
        return applyGate?.await() ?: applyResult
    }
    var openSourcesResult = true
    var openSourcesCalls = 0
    var closeSourcesCalls = 0
    override suspend fun openSummarySources(): Boolean {
        openSourcesCalls++
        return openSourcesResult
    }
    override fun closeSummarySources() { closeSourcesCalls++ }

    var continueResult: String? = "c1"
    var continueCalls = 0
    /** 非空时 continueInChat 挂起直到它完成，用来测忙碌状态。 */
    var continueGate: CompletableDeferred<String?>? = null
    /** 非空时 continueInChat 被取消后的清理（NonCancellable）挂起直到它完成。 */
    var cleanupGate: CompletableDeferred<Unit>? = null
    override suspend fun continueInChat(): String? {
        continueCalls++
        try {
            continueGate?.let { return it.await() }
            return continueResult
        } finally {
            withContext(NonCancellable) {
                cleanupGate?.await()
                events2 += "cleanup"
            }
        }
    }
}

private class FakeHistoryDao : SearchHistoryDao {
    private val items = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    fun keywords(): List<String> = items.value.map { it.keyword }
    override fun observeAll(): Flow<List<SearchHistoryEntity>> = items
    override suspend fun upsert(item: SearchHistoryEntity) {
        items.value = listOf(item) + items.value.filter { it.keyword != item.keyword }
    }
    override suspend fun deleteOne(keyword: String) {
        items.value = items.value.filter { it.keyword != keyword }
    }
    override suspend fun clear() {
        items.value = emptyList()
    }
}
