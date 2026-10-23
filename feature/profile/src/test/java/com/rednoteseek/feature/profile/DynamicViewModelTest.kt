package com.rednoteseek.feature.profile

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.network.api.PostedResult
import com.rednoteseek.core.network.api.ProfileSnapshot
import com.rednoteseek.core.network.api.ProfileUser
import com.rednoteseek.core.storage.dynamic.DynamicCacheRepository
import com.rednoteseek.core.storage.dynamic.DynamicSnapshotDao
import com.rednoteseek.core.storage.dynamic.DynamicSnapshotEntity
import com.rednoteseek.core.storage.follow.FollowDao
import com.rednoteseek.core.storage.follow.FollowEntity
import com.rednoteseek.core.storage.follow.FollowRepository
import com.rednoteseek.core.web.BoardForm
import com.rednoteseek.core.web.NoteListEvent
import com.rednoteseek.core.web.NoteListKind
import com.rednoteseek.core.web.NoteListSource
import com.rednoteseek.core.web.PageLocation
import com.rednoteseek.core.web.UnreadResult
import com.rednoteseek.feature.profile.DynamicViewModel.Effect
import com.rednoteseek.feature.profile.DynamicViewModel.FeedNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeSource()
    private val dao = FakeDao()
    private val cacheDao = FakeCacheDao()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun url(id: String) = "https://www.xiaohongshu.com/user/profile/$id?xsec_token=T"
    private fun follow(vararg ids: String) {
        dao.rows.value = ids.map { FollowEntity(it, "n$it", "", url(it), 0) }
    }
    private fun note(id: String, time: Long) = PostedNote(note_id = id, time = time)
    private fun snap(id: String, vararg notes: PostedNote) =
        ProfileSnapshot(user = ProfileUser(user_id = id), notes = notes.toList())

    private fun cached(id: String, vararg notes: PostedNote) {
        cacheDao.rows[id] = DynamicSnapshotEntity(id, XhsHttp.json.encodeToString(ListSerializer(PostedNote.serializer()), notes.toList()))
    }

    private fun TestScope.newViewModel() = DynamicViewModel(
        source, FollowRepository(dao), DynamicCacheRepository(cacheDao), "me", clock = { testScheduler.currentTime },
    )

    private fun DynamicViewModel.feedIds() = state.value.feed.map { it.note.note_id }

    @Test fun mergeFeedSortsByTimeAndDedupes() {
        val merged = mergeFeed(
            mapOf(
                "u1" to listOf(note("a", 10), note("b", 30)),
                "u2" to listOf(note("c", 20), note("a", 10)),
            )
        )
        assertEquals(listOf("b", "c", "a"), merged.map { it.note.note_id })
        assertEquals("u1", merged.first().authorId)
    }

    @Test fun refreshVisitsAuthorsSequentiallyWithInterval() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        val vm = newViewModel()
        vm.refresh()
        // 第一步读未看数（读不到就全部访问），它也是一次页面加载，第一位作者要等 5 秒
        assertEquals(listOf("me"), source.unreadCalls)
        assertTrue(source.opens.isEmpty())
        advanceTimeBy(5_001)
        assertEquals(listOf(url("u1")), source.opens)
        assertTrue(vm.state.value.refreshing)
        assertEquals(1, vm.state.value.done)
        assertEquals(2, vm.state.value.total)
        advanceTimeBy(4_998)
        assertEquals(1, source.opens.size)
        advanceTimeBy(2)
        assertEquals(listOf(url("u1"), url("u2")), source.opens)
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf("b", "a"), vm.feedIds())
        assertEquals(null, vm.state.value.message)
    }

    @Test fun intervalCountsFromEndOfPreviousLoad() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        source.loadDelayMs = 4_000
        val vm = newViewModel()
        vm.refresh()
        // 第一位作者 5 秒时打开、4 秒才加载完，下一次打开要到 5 + 4 + 5 = 14 秒
        advanceTimeBy(13_999)
        assertEquals(1, source.opens.size)
        advanceTimeBy(2)
        assertEquals(2, source.opens.size)
    }

    @Test fun twoConsecutiveFailuresStop() = runTest(dispatcher) {
        follow("u1", "u2", "u3")
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        assertEquals(2, source.opens.size)
        assertFalse(vm.state.value.refreshing)
        assertEquals("连续 2 位作者加载失败，已停止", vm.state.value.message)
    }

    @Test fun singleFailureIsReportedAtEnd() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        assertEquals("1 位作者更新失败", vm.state.value.message)
        assertEquals(listOf("b"), vm.feedIds())
    }

    @Test fun riskStopsRefresh() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(5_001)
        source.events.emit(NoteListEvent.Failed(NoteListKind.POSTED, "u1", "HTTP 461", risk = true))
        advanceTimeBy(60_000)
        assertEquals(1, source.opens.size)
        assertFalse(vm.state.value.refreshing)
        assertEquals("触发风控，已停止刷新", vm.state.value.message)
    }

    @Test fun pauseKeepsPartialResults() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(5_001)
        vm.pause()
        advanceTimeBy(60_000)
        assertEquals(1, source.opens.size)
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf("a"), vm.feedIds())
    }

    @Test fun unfollowRemovesAuthorNotes() = runTest(dispatcher) {
        follow("u1", "u2")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        follow("u2")
        assertEquals(listOf("b"), vm.feedIds())
    }

    @Test fun unreadCountsSkipSeenAuthorsThatHaveCache() = runTest(dispatcher) {
        follow("u1", "u2")
        cached("u1", note("a", 10))
        cached("u2", note("old", 1))
        source.unread = UnreadResult.Counts(mapOf("u1" to 0, "u2" to 2))
        source.snapshots[url("u2")] = snap("u2", note("b", 20))
        val vm = newViewModel()
        assertEquals(listOf("a", "old"), vm.feedIds())
        vm.refresh()
        advanceTimeBy(60_000)
        assertEquals(listOf(url("u2")), source.opens)
        assertEquals(listOf("b", "a"), vm.feedIds())
        assertEquals(mapOf("u1" to 0, "u2" to 2), vm.state.value.unread)
        assertEquals("1 位作者没有未看的新笔记，沿用上次的内容", vm.state.value.message)
        assertTrue(cacheDao.rows.getValue("u2").notesJson.contains("\"b\""))
    }

    @Test fun authorsWithoutCacheOrUnknownCountAreVisited() = runTest(dispatcher) {
        follow("u1", "u2")
        source.unread = UnreadResult.Counts(mapOf("u1" to 0))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        assertEquals(listOf(url("u1"), url("u2")), source.opens)
    }

    @Test fun riskWhileReadingUnreadStops() = runTest(dispatcher) {
        follow("u1")
        source.unread = UnreadResult.Failed(risk = true)
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        assertTrue(source.opens.isEmpty())
        assertFalse(vm.state.value.refreshing)
        assertEquals("触发风控，已停止刷新", vm.state.value.message)
    }

    @Test fun unfollowDropsCachedSnapshot() = runTest(dispatcher) {
        follow("u1", "u2")
        cached("u1", note("a", 10))
        val vm = newViewModel()
        follow("u2")
        assertEquals(emptyList<String>(), vm.feedIds())
        assertFalse("u1" in cacheDao.rows)
    }

    @Test fun openNoteOnAuthorPageUsesCachedOrder() = runTest(dispatcher) {
        follow("u1")
        source.snapshots[url("u1")] = snap("u1", note("a", 10), note("b", 5))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        source.where = PageLocation("/user/profile/u1", collectTabActive = false)
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.openNote(FeedNote(note("b", 5), "u1"))
        assertEquals(1, source.opens.size)
        assertEquals(listOf(Effect.OpenNote("b", listOf("a", "b"))), effects)
    }

    @Test fun openNoteElsewhereReopensProfileAfterInterval() = runTest(dispatcher) {
        follow("u1")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        val vm = newViewModel()
        vm.refresh()
        advanceTimeBy(60_000)
        source.where = PageLocation("/user/profile/zz", collectTabActive = false)
        val effects = mutableListOf<Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.openNote(FeedNote(note("a", 10), "u1"))
        assertEquals(2, source.opens.size)
        assertEquals(listOf(Effect.OpenNote("a", listOf("a"))), effects)
    }

    @Test fun openNoteMissingFromProfileShowsMessage() = runTest(dispatcher) {
        follow("u1")
        source.snapshots[url("u1")] = snap("u1", note("a", 10))
        val vm = newViewModel()
        vm.openNote(FeedNote(note("gone", 1), "u1"))
        assertEquals("笔记打开失败，请刷新后重试", vm.state.value.message)
        assertEquals(null, vm.state.value.openingNoteId)
    }
}

private class FakeSource : NoteListSource {
    override val events = MutableSharedFlow<NoteListEvent>()
    val snapshots = mutableMapOf<String, ProfileSnapshot>()
    val opens = mutableListOf<String>()
    var where = PageLocation("", collectTabActive = false)
    var loadDelayMs = 0L
    var unread: UnreadResult = UnreadResult.Failed(risk = false)
    val unreadCalls = mutableListOf<String>()
    override suspend fun openProfile(url: String): ProfileSnapshot? {
        opens += url
        delay(loadDelayMs)
        return snapshots[url]
    }
    override suspend fun openCollections(selfUserId: String) = false
    override suspend fun openBoards(selfUserId: String): List<Board>? = null
    override suspend fun openBoard(boardId: String): PostedResult? = null
    override suspend fun readBoardForm(boardId: String): BoardForm? = null
    override suspend fun createBoard(selfUserId: String, form: BoardForm): String? = null
    override suspend fun updateBoard(boardId: String, form: BoardForm) = false
    override suspend fun deleteBoard(boardId: String) = false
    override suspend fun location(): PageLocation = where
    override fun loadMore() {}
    override suspend fun setFollow(userId: String, follow: Boolean) = false
    override suspend fun followingUnread(selfUserId: String): UnreadResult {
        unreadCalls += selfUserId
        return unread
    }
}

private class FakeCacheDao : DynamicSnapshotDao {
    val rows = linkedMapOf<String, DynamicSnapshotEntity>()
    override suspend fun getAll() = rows.values.toList()
    override suspend fun upsert(item: DynamicSnapshotEntity) { rows[item.userId] = item }
    override suspend fun delete(userId: String) { rows.remove(userId) }
}

private class FakeDao : FollowDao {
    val rows = MutableStateFlow<List<FollowEntity>>(emptyList())
    override fun observeAll(): Flow<List<FollowEntity>> = rows
    override suspend fun getAll(): List<FollowEntity> = rows.value
    override suspend fun upsert(item: FollowEntity) { rows.value = rows.value.filter { it.userId != item.userId } + item }
    override suspend fun delete(userId: String) { rows.value = rows.value.filter { it.userId != userId } }
}
