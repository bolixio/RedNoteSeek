package com.rednoteseek.feature.detail

import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.web.CollectOutcome
import com.rednoteseek.core.web.NoteEvent
import com.rednoteseek.core.web.NoteSource
import com.rednoteseek.core.web.OpenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class NoteCollectViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeCollectSource()
    private val travel = Board(id = "b1", name = "旅行", total = 3)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(collected: Boolean): Pair<NoteCollectViewModel, MutableList<String>> {
        val vm = NoteCollectViewModel(source)
        vm.bind("n1", collected)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        return vm to messages
    }

    @Test fun collectThenPickBoard() = runTest(dispatcher) {
        source.outcome = CollectOutcome.Collected(listOf(travel))
        val (vm, messages) = newViewModel(collected = false)
        vm.collect()
        assertEquals(true, vm.state.value.collected)
        assertEquals(listOf(travel), vm.state.value.picker)
        vm.pick(0, travel)
        assertNull(vm.state.value.picker)
        assertEquals(listOf(0 to "b1"), source.moves)
        assertEquals(listOf("已加入「旅行」"), messages)
    }

    @Test fun dismissPickerClosesWebPanelAndKeepsCollected() = runTest(dispatcher) {
        source.outcome = CollectOutcome.Collected(listOf(travel))
        val (vm, _) = newViewModel(collected = false)
        vm.collect()
        vm.dismissPicker()
        assertNull(vm.state.value.picker)
        assertEquals(1, source.panelCloses)
        assertEquals(true, vm.state.value.collected)
    }

    @Test fun collectFailureKeepsState() = runTest(dispatcher) {
        val (vm, messages) = newViewModel(collected = false)
        vm.collect()
        assertEquals(false, vm.state.value.collected)
        assertEquals(listOf("收藏失败，请稍后重试"), messages)
        assertFalse(vm.state.value.busy)
    }

    @Test fun changeBoardUncollectsThenCollects() = runTest(dispatcher) {
        source.outcome = CollectOutcome.Collected(listOf(travel))
        val (vm, _) = newViewModel(collected = true)
        vm.changeBoard()
        assertEquals(listOf("uncollect", "collect"), source.calls)
        assertEquals(listOf(travel), vm.state.value.picker)
    }

    @Test fun changeBoardStopsWhenUncollectFails() = runTest(dispatcher) {
        source.uncollectResult = false
        val (vm, messages) = newViewModel(collected = true)
        vm.changeBoard()
        assertEquals(listOf("uncollect"), source.calls)
        assertEquals(true, vm.state.value.collected)
        assertEquals(listOf("取消收藏失败，请稍后重试"), messages)
    }

    @Test fun removeFromBoardsRecollectsWithoutBoard() = runTest(dispatcher) {
        source.outcome = CollectOutcome.Collected(listOf(travel))
        val (vm, messages) = newViewModel(collected = true)
        vm.removeFromBoards()
        assertEquals(listOf("uncollect", "collect"), source.calls)
        assertNull(vm.state.value.picker)
        assertEquals(1, source.panelCloses)
        assertEquals(true, vm.state.value.collected)
        assertEquals(listOf("已移出专辑，笔记仍在收藏中"), messages)
    }

    @Test fun removeFromBoardsReportsLostCollection() = runTest(dispatcher) {
        val (vm, messages) = newViewModel(collected = true)
        vm.removeFromBoards()
        assertEquals(false, vm.state.value.collected)
        assertEquals(listOf("重新收藏失败，笔记已取消收藏"), messages)
    }

    @Test fun nothingBeforeDetailArrives() = runTest(dispatcher) {
        val vm = NoteCollectViewModel(source)
        vm.collect()
        assertTrue(source.calls.isEmpty())
    }
}

private class FakeCollectSource : NoteSource {
    override val events = MutableSharedFlow<NoteEvent>()
    var outcome: CollectOutcome = CollectOutcome.Failed
    var uncollectResult = true
    val calls = mutableListOf<String>()
    val moves = mutableListOf<Pair<Int, String>>()
    var panelCloses = 0
    override suspend fun open(noteId: String, searchOrder: List<String>) = OpenResult.Opened
    override suspend fun readCachedComments(noteId: String): NoteEvent.Comments? = null
    override fun loadMoreComments() {}
    override suspend fun expandReplies(rootCommentId: String) = false
    override fun close() {}
    override suspend fun readAuthorLink(): String? = null
    override suspend fun collect(): CollectOutcome { calls += "collect"; return outcome }
    override suspend fun uncollect(): Boolean { calls += "uncollect"; return uncollectResult }
    override suspend fun moveToBoard(index: Int, board: Board): Boolean { moves += index to board.id; return true }
    override fun closeBoardPicker() { panelCloses++ }
}
