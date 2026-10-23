package com.rednoteseek.feature.profile

import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.network.api.PostedResult
import com.rednoteseek.core.network.api.ProfileSnapshot
import com.rednoteseek.core.network.api.ProfileUser
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.core.web.NoteListEvent
import com.rednoteseek.core.web.NoteListKind
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.PageLocation
import com.rednoteseek.core.web.UnreadResult
import com.rednoteseek.feature.profile.NoteListViewModel.State
import com.rednoteseek.feature.profile.NoteListViewModel.Target
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeNoteListSource()
    private val url = "https://www.xiaohongshu.com/user/profile/u1?xsec_token=AB"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(target: Target = Target.Profile(url)) =
        NoteListViewModel(source, target, clock = { testScheduler.currentTime })

    private fun page(kind: NoteListKind, userId: String?, cursor: String, vararg ids: String, hasMore: Boolean = true) =
        NoteListEvent.Page(kind, userId, cursor, ids.map { PostedNote(note_id = it) }, cursor = "next", hasMore = hasMore)

    private fun NoteListViewModel.loaded() = state.value as State.Loaded
    private fun NoteListViewModel.ids() = loaded().notes.map { it.note_id }

    @Test fun profileShowsSnapshotThenPages() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        assertEquals(listOf(url), source.profileOpens)
        val s = vm.loaded()
        assertEquals("博主", s.user?.nickname)
        assertEquals(listOf("a", "b"), s.notes.map { it.note_id })
        assertTrue(s.hasMore)

        source.events.emit(page(NoteListKind.POSTED, "u1", "p1", "c"))
        assertEquals(listOf("a", "b", "c"), vm.ids())
    }

    @Test fun profileIgnoresOtherUsersAndCollections() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.events.emit(page(NoteListKind.POSTED, "u2", "p1", "x"))
        source.events.emit(page(NoteListKind.COLLECTED, "u1", "", "y"))
        assertEquals(listOf("a", "b"), vm.ids())
    }

    @Test fun profileOpenFailureIsRetryable() = runTest(dispatcher) {
        source.snapshot = null
        val vm = newViewModel()
        vm.ensureOpen()
        assertEquals(State.Error("主页加载失败", true), vm.state.value)
        vm.retry()
        assertEquals(2, source.profileOpens.size)
    }

    @Test fun refreshKeepsListUntilNewFirstPage() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.events.emit(page(NoteListKind.POSTED, "u1", "p1", "c"))
        source.snapshot = source.snapshot!!.copy(notes = listOf(PostedNote(note_id = "new")))
        source.holdOpen = true
        vm.refresh()
        assertEquals(listOf("a", "b", "c"), vm.ids())
        assertTrue(vm.loaded().refreshing)
        source.releaseOpen()
        assertEquals(listOf("new"), vm.ids())
        assertFalse(vm.loaded().refreshing)
        assertEquals(2, source.profileOpens.size)
    }

    @Test fun refreshFailureKeepsList() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.snapshot = null
        vm.refresh()
        assertEquals(listOf("a", "b"), vm.ids())
        assertFalse(vm.loaded().refreshing)
    }

    @Test fun collectionsRefreshTimesOutKeepingList() = runTest(dispatcher) {
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        source.events.emit(page(NoteListKind.COLLECTED, "me", "", "m1"))
        vm.refresh()
        assertTrue(vm.loaded().refreshing)
        source.events.emit(page(NoteListKind.COLLECTED, "me", "", "m2"))
        assertEquals(listOf("m2"), vm.ids())
        assertFalse(vm.loaded().refreshing)
        vm.refresh()
        advanceTimeBy(25_000)
        assertEquals(listOf("m2"), vm.ids())
        assertFalse(vm.loaded().refreshing)
    }

    @Test fun collectionsWaitForFirstPage() = runTest(dispatcher) {
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        assertEquals(State.Loading, vm.state.value)
        assertEquals(1, source.collectionOpens)

        source.events.emit(page(NoteListKind.COLLECTED, "me", "", "m1"))
        val s = vm.loaded()
        assertNull(s.user)
        assertEquals(listOf("m1"), s.notes.map { it.note_id })
    }

    @Test fun collectionsEmptyPage() = runTest(dispatcher) {
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        source.events.emit(page(NoteListKind.COLLECTED, "me", "", hasMore = false))
        assertEquals(State.Empty, vm.state.value)
    }

    @Test fun collectionsOpenFailure() = runTest(dispatcher) {
        source.collectionsResult = false
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        assertEquals(State.Error("收藏加载失败", true), vm.state.value)
    }

    @Test fun boardShowsFirstPageThenOwnPagesOnly() = runTest(dispatcher) {
        val vm = newViewModel(Target.Board("b1"))
        vm.ensureOpen()
        assertEquals(listOf("b1"), source.boardOpens)
        assertEquals(listOf("x1", "x2"), vm.ids())
        source.events.emit(page(NoteListKind.BOARD, "b2", "c1", "other"))
        source.events.emit(page(NoteListKind.COLLECTED, "b1", "c1", "collected"))
        source.events.emit(page(NoteListKind.BOARD, "b1", "c1", "x3", hasMore = false))
        assertEquals(listOf("x1", "x2", "x3"), vm.ids())
        assertEquals(false, vm.loaded().hasMore)
    }

    @Test fun boardReopensWhenEngineMovedAway() = runTest(dispatcher) {
        val vm = newViewModel(Target.Board("b1"))
        vm.ensureOpen()
        source.where = PageLocation("/board/b1", collectTabActive = false)
        vm.ensureOpen()
        assertEquals(1, source.boardOpens.size)
        source.where = PageLocation("/user/profile/u9", collectTabActive = false)
        vm.ensureOpen()
        assertEquals(2, source.boardOpens.size)
    }

    @Test fun boardOpenFailureIsRetryable() = runTest(dispatcher) {
        source.board = null
        val vm = newViewModel(Target.Board("b1"))
        vm.ensureOpen()
        assertEquals(State.Error("专辑加载失败", true), vm.state.value)
    }

    @Test fun boardsListLoadsAndKeepsOldListWhenRefreshFails() = runTest(dispatcher) {
        val vm = BoardsViewModel(source, "me")
        assertEquals(BoardsViewModel.State.Loaded(listOf(Board(id = "b1", name = "旅行", total = 3))), vm.state.value)
        source.boards = null
        vm.load()
        assertEquals(BoardsViewModel.State.Loaded(listOf(Board(id = "b1", name = "旅行", total = 3))), vm.state.value)
        assertEquals(listOf("me", "me"), source.boardListOpens)
    }

    @Test fun boardsListErrorThenRetry() = runTest(dispatcher) {
        source.boards = null
        val vm = BoardsViewModel(source, "me")
        assertEquals(BoardsViewModel.State.Error("专辑加载失败"), vm.state.value)
        source.boards = emptyList()
        vm.load()
        assertEquals(BoardsViewModel.State.Loaded(emptyList()), vm.state.value)
    }

    @Test fun collectChangesReopenList() = runTest(dispatcher) {
        val vm = newViewModel(Target.Board("b1"))
        vm.ensureOpen()
        vm.onCollectChanges(3)
        assertEquals(1, source.boardOpens.size)
        vm.onCollectChanges(3)
        assertEquals(1, source.boardOpens.size)
        vm.onCollectChanges(4)
        assertEquals(2, source.boardOpens.size)
        vm.onCollectChanges(5, reload = false)
        vm.onCollectChanges(5)
        assertEquals(2, source.boardOpens.size)
    }

    @Test fun boardsListReloadsOnCollectChangesAndCreates() = runTest(dispatcher) {
        val vm = BoardsViewModel(source, "me")
        vm.onCollectChanges(0)
        assertEquals(1, source.boardListOpens.size)
        vm.onCollectChanges(1)
        assertEquals(2, source.boardListOpens.size)
        var done: Boolean? = null
        vm.create(BoardForm("新", "", public = false)) { done = it }
        assertEquals(true, done)
        assertEquals(listOf(BoardForm("新", "", public = false)), source.created)
        source.createdId = null
        vm.create(BoardForm("x", "", public = true)) { done = it }
        assertEquals(false, done)
    }

    @Test fun boardEditFlow() = runTest(dispatcher) {
        val vm = BoardEditViewModel(source, "b1")
        val events = mutableListOf<BoardEditViewModel.Event>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.startEdit()
        assertEquals(BoardForm("旅行", "", public = true), vm.state.value.editing)
        assertFalse(vm.isOwnWrite())
        vm.save(BoardForm("旅行2", "d", public = false))
        assertNull(vm.state.value.editing)
        assertTrue(vm.isOwnWrite())
        assertFalse(vm.isOwnWrite())
        assertEquals(BoardEditViewModel.Event.Renamed("旅行2"), events.first())
        vm.startEdit()
        source.deleteResult = false
        vm.delete()
        assertEquals(BoardEditViewModel.Event.Message("删除失败，请稍后重试"), events.last())
        source.deleteResult = true
        vm.delete()
        assertEquals(BoardEditViewModel.Event.Deleted, events.last())
    }

    @Test fun boardEditReadFailure() = runTest(dispatcher) {
        source.boardForm = null
        val vm = BoardEditViewModel(source, "b1")
        val events = mutableListOf<BoardEditViewModel.Event>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.startEdit()
        assertNull(vm.state.value.editing)
        assertEquals(listOf(BoardEditViewModel.Event.Message("读取专辑信息失败，请稍后重试")), events)
    }

    @Test fun riskIsTerminal() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.events.emit(NoteListEvent.Failed(NoteListKind.POSTED, "u1", "HTTP 461", risk = true))
        val risk = State.Error("触发风控，请稍后再试", false)
        assertEquals(risk, vm.state.value)
        source.events.emit(page(NoteListKind.POSTED, "u1", "p1", "c"))
        assertEquals(risk, vm.state.value)
    }

    @Test fun ensureOpenReopensWhenEngineMoved() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.where = PageLocation("/user/profile/u2", false)
        vm.ensureOpen()
        assertEquals(2, source.profileOpens.size)
    }

    @Test fun ensureOpenKeepsStateWhileNoteModalCloses() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.where = PageLocation("/explore/n1", false)
        vm.ensureOpen()
        assertEquals(1, source.profileOpens.size)
        assertEquals(listOf("a", "b"), vm.ids())
    }

    @Test fun staleTargetPageReopens() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        source.where = PageLocation("/user/profile/u1", collectTabActive = false, stale = true)
        vm.ensureOpen()
        assertEquals(2, source.profileOpens.size)
    }

    @Test fun collectionsReopenWhenTabNotActive() = runTest(dispatcher) {
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        source.events.emit(page(NoteListKind.COLLECTED, "me", "", "m1"))
        source.where = PageLocation("/user/profile/me", collectTabActive = false)
        vm.ensureOpen()
        assertEquals(2, source.collectionOpens)
    }

    @Test fun loadMoreThrottleAndFailure() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.ensureOpen()
        vm.loadMore()
        vm.loadMore()
        assertEquals(1, source.loadMoreCalls)
        advanceTimeBy(10_001)
        // 第二次被限频挡下，3000ms 后延迟重试发出一次
        assertEquals(2, source.loadMoreCalls)
        assertTrue(vm.loaded().loadMoreFailed)
        vm.loadMore()   // 失败后不再自动请求
        assertEquals(2, source.loadMoreCalls)
        vm.retryLoadMore()
        assertEquals(3, source.loadMoreCalls)
        assertEquals(false, vm.loaded().loadMoreFailed)
    }

    @Test fun timesOut() = runTest(dispatcher) {
        val vm = newViewModel(Target.Collections("me"))
        vm.ensureOpen()
        advanceTimeBy(20_001)
        assertEquals(State.Error("加载超时，请重试", true), vm.state.value)
    }
}

private class FakeNoteListSource : NoteListSource {
    override val events = MutableSharedFlow<NoteListEvent>()
    var snapshot: ProfileSnapshot? = ProfileSnapshot(
        user = ProfileUser(user_id = "u1", nickname = "博主"),
        notes = listOf(PostedNote(note_id = "a"), PostedNote(note_id = "b")),
        cursor = "p1", has_more = true,
    )
    var collectionsResult = true
    var board: PostedResult? = PostedResult(listOf(PostedNote(note_id = "x1"), PostedNote(note_id = "x2")), cursor = "c1", has_more = true)
    var boards: List<Board>? = listOf(Board(id = "b1", name = "旅行", total = 3))
    val boardOpens = mutableListOf<String>()
    val boardListOpens = mutableListOf<String>()
    var where = PageLocation("/user/profile/u1", collectTabActive = false)
    val profileOpens = mutableListOf<String>()
    var collectionOpens = 0
    var loadMoreCalls = 0
    /** 为 true 时 openProfile 挂起，直到 [releaseOpen]（模拟网页加载中）。 */
    var holdOpen = false
    private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    fun releaseOpen() = gate.complete(Unit)
    override suspend fun openProfile(url: String): ProfileSnapshot? {
        profileOpens += url
        if (holdOpen) gate.await()
        return snapshot
    }
    override suspend fun openCollections(selfUserId: String): Boolean { collectionOpens++; return collectionsResult }
    override suspend fun openBoards(selfUserId: String): List<Board>? { boardListOpens += selfUserId; return boards }
    override suspend fun openBoard(boardId: String): PostedResult? { boardOpens += boardId; return board }
    var createdId: String? = "new"
    val created = mutableListOf<BoardForm>()
    override suspend fun createBoard(selfUserId: String, form: BoardForm): String? { created += form; return createdId }
    var boardForm: BoardForm? = BoardForm("旅行", "", public = true)
    var updateResult = true
    var deleteResult = true
    val updates = mutableListOf<BoardForm>()
    override suspend fun readBoardForm(boardId: String): BoardForm? = boardForm
    override suspend fun updateBoard(boardId: String, form: BoardForm): Boolean { updates += form; return updateResult }
    override suspend fun deleteBoard(boardId: String) = deleteResult
    override suspend fun location(): PageLocation = where
    override fun loadMore() { loadMoreCalls++ }
    override suspend fun setFollow(userId: String, follow: Boolean) = false
    override suspend fun followingUnread(selfUserId: String) : UnreadResult = UnreadResult.Failed(risk = false)
}
