package com.rednoteseek.feature.detail

import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.XhsComment
import com.rednoteseek.core.web.NoteEvent
import com.rednoteseek.core.web.NoteSource
import com.rednoteseek.core.web.CollectOutcome
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.web.OpenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeNoteSource()
    private val card = NoteCard(title = "意大利旅行攻略")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(saveNote: suspend (NoteCard, Int?) -> String = { _, _ -> "已保存" }) =
        NoteViewModel(source, saveNote, clock = { testScheduler.currentTime })

    private fun comments(requestCursor: String, vararg ids: String, hasMore: Boolean = true) =
        NoteEvent.Comments("n1", requestCursor, ids.map { XhsComment(id = it) }, cursor = "next", hasMore = hasMore)

    @Test fun opensNoteAndShowsDetail() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", listOf("n0", "n1"))
        assertEquals(listOf("n1"), source.opened)
        assertEquals(listOf(listOf("n0", "n1")), source.orders)
        assertEquals(NoteViewModel.State.Loading, vm.state.value)

        source.events.emit(NoteEvent.Detail("n1", card))
        assertEquals(NoteViewModel.State.Loaded(card, emptyList(), commentsHasMore = false, commentsPending = true), vm.state.value)
    }

    @Test fun commentsArrivingBeforeDetailAreKept() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(comments("", "c1"))
        source.events.emit(NoteEvent.Detail("n1", card))
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(listOf("c1"), state.comments.map { it.id })
        assertEquals(true, state.commentsHasMore)
    }

    @Test fun commentPagesAreMerged() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(comments("", "c1", "c2"))
        source.events.emit(comments("cur1", "c2", "c3", hasMore = false))
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(listOf("c1", "c2", "c3"), state.comments.map { it.id })
        assertFalse(state.commentsHasMore)
    }

    @Test fun ignoresOtherNotes() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n2", card))
        assertEquals(NoteViewModel.State.Loading, vm.state.value)
    }

    @Test fun riskStopsWithoutRetry() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(NoteEvent.Failed("n1", "HTTP 461", risk = true))
        assertEquals(NoteViewModel.State.Error("触发风控，请稍后再试", retryable = false), vm.state.value)
    }

    @Test fun ordinaryFailureAfterDetailKeepsContent() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(NoteEvent.Failed("n1", "无法解析评论", risk = false))
        assertEquals(NoteViewModel.State.Loaded(card, emptyList(), commentsHasMore = false, commentsPending = false), vm.state.value)
    }

    @Test fun ordinaryFailureBeforeDetailIsRetryable() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Failed("n1", "笔记不存在或已删除", risk = false, fromFeed = true))
        assertEquals(NoteViewModel.State.Error("笔记不存在或已删除", retryable = true), vm.state.value)
    }

    @Test fun cardNotFoundShowsError() = runTest(dispatcher) {
        source.result = OpenResult.NotFound
        val vm = newViewModel()
        vm.open("n1", emptyList())
        assertEquals(
            NoteViewModel.State.Error("在网页中找不到这篇笔记，请回到搜索页重新进入", retryable = false),
            vm.state.value,
        )
    }

    @Test fun timesOutWithoutDetail() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        advanceTimeBy(15_001)
        assertEquals(NoteViewModel.State.Error("加载超时，请重试", retryable = true), vm.state.value)
    }

    @Test fun retryClosesThenReopens() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        advanceTimeBy(15_001)
        vm.retry()
        assertEquals(1, source.closeCalls)
        assertEquals(listOf("n1", "n1"), source.opened)
        assertEquals(NoteViewModel.State.Loading, vm.state.value)
    }

    @Test fun openingTheSameNoteAgainIsIgnored() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        vm.open("n1", emptyList())
        assertEquals(listOf("n1"), source.opened)
    }

    @Test fun loadMoreCommentsIsThrottledAndStopsAtEnd() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(comments("", "c1"))
        vm.loadMoreComments()
        vm.loadMoreComments()
        assertEquals(1, source.loadMoreCalls)
        source.events.emit(comments("cur1", "c2", hasMore = false))
        advanceTimeBy(3_001)
        assertEquals(1, source.loadMoreCalls)
    }

    @Test fun throttledLoadMoreRetriesOnce() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(comments("", "c1"))
        vm.loadMoreComments()
        vm.loadMoreComments()
        assertEquals(1, source.loadMoreCalls)
        advanceTimeBy(3_001)
        assertEquals(2, source.loadMoreCalls)
        advanceTimeBy(10_000)
        assertEquals(2, source.loadMoreCalls)
    }

    @Test fun riskIsTerminal() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(NoteEvent.Failed("n1", "HTTP 461", risk = true))
        source.events.emit(NoteEvent.Failed("n1", "code=1", risk = false))
        source.events.emit(comments("", "c1"))
        assertEquals(NoteViewModel.State.Error("触发风控，请稍后再试", retryable = false), vm.state.value)
    }

    @Test fun commentsPendingClearsOnFirstPage() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        assertEquals(true, (vm.state.value as NoteViewModel.State.Loaded).commentsPending)
        source.events.emit(comments("", hasMore = false))
        assertEquals(false, (vm.state.value as NoteViewModel.State.Loaded).commentsPending)
    }

    @Test fun cachedCommentsFillInWhenPageSendsNone() = runTest(dispatcher) {
        source.cached = comments("", "c1", hasMore = false)
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        advanceTimeBy(2_501)
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(listOf("c1"), state.comments.map { it.id })
        assertFalse(state.commentsPending)
    }

    @Test fun noCacheClearsPending() = runTest(dispatcher) {
        source.cached = null
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        advanceTimeBy(2_501)
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(emptyList<XhsComment>(), state.comments)
        assertFalse(state.commentsPending)
        // 详情到达时读一次，2.5 秒后再读一次
        assertEquals(2, source.cachedCalls)
    }

    @Test fun realCommentsCancelFallback() = runTest(dispatcher) {
        source.cached = null
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.cached = comments("", "cached")
        advanceTimeBy(1_000)
        source.events.emit(comments("", "c1"))
        advanceTimeBy(5_000)
        // 只有详情到达时的那一次立即读取，延迟兜底被真实评论取消
        assertEquals(1, source.cachedCalls)
        assertEquals(listOf("c1"), (vm.state.value as NoteViewModel.State.Loaded).comments.map { it.id })
    }

    @Test fun cachedCommentsAreReadImmediately() = runTest(dispatcher) {
        source.cached = comments("", "c1", hasMore = false)
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(listOf("c1"), state.comments.map { it.id })
        assertFalse(state.commentsPending)
        assertEquals(1, source.cachedCalls)
    }

    @Test fun cacheMissRetriesAfterDelay() = runTest(dispatcher) {
        source.cached = null
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.cached = comments("", "c1", hasMore = false)
        advanceTimeBy(2_501)
        assertEquals(listOf("c1"), (vm.state.value as NoteViewModel.State.Loaded).comments.map { it.id })
        assertEquals(2, source.cachedCalls)
    }

    @Test fun previewShownUntilDetailArrives() = runTest(dispatcher) {
        val preview = NoteCard(display_title = "卡片标题")
        val vm = newViewModel()
        vm.open("n1", emptyList(), preview)
        assertEquals(NoteViewModel.State.Preview(preview), vm.state.value)
        source.events.emit(NoteEvent.Detail("n1", card))
        assertEquals(card, (vm.state.value as NoteViewModel.State.Loaded).card)
    }

    @Test fun previewTimesOut() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList(), NoteCard())
        advanceTimeBy(15_001)
        assertEquals(NoteViewModel.State.Error("加载超时，请重试", retryable = true), vm.state.value)
    }

    @Test fun previewCardNotFoundShowsError() = runTest(dispatcher) {
        source.result = OpenResult.NotFound
        val vm = newViewModel()
        vm.open("n1", emptyList(), NoteCard())
        assertEquals(
            NoteViewModel.State.Error("在网页中找不到这篇笔记，请回到搜索页重新进入", retryable = false),
            vm.state.value,
        )
    }

    @Test fun retryShowsPreviewAgain() = runTest(dispatcher) {
        val preview = NoteCard(display_title = "卡片标题")
        val vm = newViewModel()
        vm.open("n1", emptyList(), preview)
        source.events.emit(NoteEvent.Failed("n1", "HTTP 500", risk = false, fromFeed = true))
        vm.retry()
        assertEquals(NoteViewModel.State.Preview(preview), vm.state.value)
    }

    @Test fun reopenAfterClose() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        vm.close()
        vm.open("n1", emptyList())
        assertEquals(listOf("n1", "n1"), source.opened)
    }

    @Test fun authorLinkIsEmitted() = runTest(dispatcher) {
        val vm = newViewModel()
        val urls = mutableListOf<String>()
        backgroundScope.launch { vm.authorUrls.collect { urls += it } }
        source.authorLink = "https://www.xiaohongshu.com/user/profile/u1?xsec_token=AB"
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.requestAuthorProfile()
        assertEquals(listOf("https://www.xiaohongshu.com/user/profile/u1?xsec_token=AB"), urls)
    }

    @Test fun missingAuthorLinkIsReported() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        source.authorLink = null
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.requestAuthorProfile()
        assertEquals(listOf("无法打开作者主页"), messages)
    }

    @Test fun saveReportsMessage() = runTest(dispatcher) {
        val vm = newViewModel(saveNote = { _, _ -> "已保存到相册" })
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.save(0)
        assertEquals(listOf("已保存到相册"), messages)
        assertFalse(vm.saving.value)
    }

    @Test fun savePassesImageIndex() = runTest(dispatcher) {
        val saved = mutableListOf<Int?>()
        val vm = newViewModel(saveNote = { _, index -> saved += index; "已保存到相册" })
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.save(2)
        vm.save(null)
        assertEquals(listOf(2, null), saved)
    }

    @Test fun saveFailureBecomesMessage() = runTest(dispatcher) {
        val vm = newViewModel(saveNote = { _, _ -> error("磁盘已满") })
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.save()
        assertEquals(listOf("下载失败：磁盘已满"), messages)
    }

    @Test fun closeClosesTheWebModal() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        vm.close()
        assertEquals(1, source.closeCalls)
    }

    @Test fun commentFailureBeforeDetailDoesNotErrorThePage() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Failed("n1", "无法解析评论", risk = false))
        assertEquals(NoteViewModel.State.Loading, vm.state.value)
        source.events.emit(NoteEvent.Detail("n1", card))
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(false, state.commentsPending)
    }

    @Test fun expandingRepliesMergesThem() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(NoteEvent.Comments("n1", "", listOf(XhsComment(id = "c1", subCommentCount = "3", subComments = listOf(XhsComment(id = "s1")))), cursor = "", hasMore = false))
        vm.expandReplies("c1")
        assertEquals(listOf("c1"), source.expanded)
        assertEquals(setOf("c1"), (vm.state.value as NoteViewModel.State.Loaded).expandingReplies)
        vm.expandReplies("c1")   // 展开中重复点击被忽略
        assertEquals(listOf("c1"), source.expanded)
        source.events.emit(NoteEvent.SubComments("n1", "c1", "", listOf(XhsComment(id = "s1"), XhsComment(id = "s2")), "s2", hasMore = false))
        val state = vm.state.value as NoteViewModel.State.Loaded
        assertEquals(emptySet<String>(), state.expandingReplies)
        assertEquals(listOf("s1", "s2"), state.comments.single().subComments.map { it.id })
    }

    @Test fun expandFailureIsReported() = runTest(dispatcher) {
        source.expandResult = false
        val vm = newViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.expandReplies("c1")
        assertEquals(listOf("无法展开回复"), messages)
        assertEquals(emptySet<String>(), (vm.state.value as NoteViewModel.State.Loaded).expandingReplies)
    }

    @Test fun expandTimesOut() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.expandReplies("c1")
        advanceTimeBy(8_001)
        assertEquals(listOf("展开回复超时"), messages)
        assertEquals(emptySet<String>(), (vm.state.value as NoteViewModel.State.Loaded).expandingReplies)
    }

    @Test fun riskCancelsPendingExpand() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.expandReplies("c1")
        source.events.emit(NoteEvent.Failed("n1", "HTTP 461", risk = true))
        advanceTimeBy(8_001)
        assertEquals(emptyList<String>(), messages)
        assertEquals(NoteViewModel.State.Error("触发风控，请稍后再试", retryable = false), vm.state.value)
    }

    @Test fun expandingSurvivesCommentPage() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        vm.expandReplies("c1")
        source.events.emit(comments("", "c1"))
        assertEquals(setOf("c1"), (vm.state.value as NoteViewModel.State.Loaded).expandingReplies)
    }

    @Test fun commentLoadWithoutNewPageMarksFailure()= runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        source.events.emit(NoteEvent.Detail("n1", card))
        source.events.emit(comments("", "c1"))
        vm.loadMoreComments()
        advanceTimeBy(10_001)
        assertEquals(true, (vm.state.value as NoteViewModel.State.Loaded).commentsLoadFailed)
        vm.loadMoreComments()
        assertEquals(1, source.loadMoreCalls)
        vm.retryLoadComments()
        assertEquals(2, source.loadMoreCalls)
        assertEquals(false, (vm.state.value as NoteViewModel.State.Loaded).commentsLoadFailed)
    }

    @Test fun closeTwiceClosesOnce() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open("n1", emptyList())
        vm.close()
        vm.close()
        assertEquals(1, source.closeCalls)
    }
}

private class FakeNoteSource : NoteSource {
    override val events = MutableSharedFlow<NoteEvent>()
    var result = OpenResult.Opened
    val opened = mutableListOf<String>()
    val orders = mutableListOf<List<String>>()
    var closeCalls = 0
    var loadMoreCalls = 0
    var cached: NoteEvent.Comments? = null
    var cachedCalls = 0
    var authorLink: String? = null
    override suspend fun readAuthorLink(): String? = authorLink

    override suspend fun readCachedComments(noteId: String): NoteEvent.Comments? {
        cachedCalls++
        return cached
    }

    override suspend fun open(noteId: String, searchOrder: List<String>): OpenResult {
        opened += noteId
        orders += searchOrder
        return result
    }

    var expandResult = true
    val expanded = mutableListOf<String>()
    override suspend fun expandReplies(rootCommentId: String): Boolean { expanded += rootCommentId; return expandResult }

    override fun loadMoreComments() { loadMoreCalls++ }

    override fun close() { closeCalls++ }

    override suspend fun collect(): CollectOutcome = CollectOutcome.Failed
    override suspend fun uncollect() = false
    override suspend fun moveToBoard(index: Int, board: Board) = false
    override fun closeBoardPicker() {}
}
