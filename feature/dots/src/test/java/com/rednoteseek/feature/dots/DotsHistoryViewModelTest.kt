package com.rednoteseek.feature.dots

import com.rednoteseek.core.web.ChatEvent
import com.rednoteseek.core.web.DotsConversation
import com.rednoteseek.feature.dots.DotsHistoryViewModel.Group
import com.rednoteseek.feature.dots.DotsHistoryViewModel.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DotsHistoryViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeDotsChatSource()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun conv(id: String, group: String) = DotsConversation(id, "标题$id", 1_792_611_706_333L, group)

    private val a = conv("a", "今天")
    private val b = conv("b", "今天")
    private val c = conv("c", "7天内")

    private suspend fun loadedViewModel(): DotsHistoryViewModel {
        val vm = DotsHistoryViewModel(source)
        source.events.emit(ChatEvent.Conversations(listOf(a, b, c), hasMore = false))
        return vm
    }

    @Test fun opensListAndGroupsConversations() = runTest(dispatcher) {
        val vm = DotsHistoryViewModel(source)
        assertEquals(1, source.listOpens)
        assertEquals(State.Loading, vm.state.value)
        source.events.emit(ChatEvent.Conversations(listOf(a, b, c), hasMore = false))
        assertEquals(State.Loaded(listOf(Group("今天", listOf(a, b)), Group("7天内", listOf(c)))), vm.state.value)
    }

    @Test fun emptyListHasNoGroups() = runTest(dispatcher) {
        val vm = DotsHistoryViewModel(source)
        source.events.emit(ChatEvent.Conversations(emptyList(), hasMore = false))
        assertEquals(State.Loaded(emptyList()), vm.state.value)
    }

    @Test fun duplicateConversationShownOnce() = runTest(dispatcher) {
        val vm = DotsHistoryViewModel(source)
        source.events.emit(ChatEvent.Conversations(listOf(a, a, c), hasMore = false))
        assertEquals(State.Loaded(listOf(Group("今天", listOf(a)), Group("7天内", listOf(c)))), vm.state.value)
    }

    @Test fun laterListReplacesCurrent() = runTest(dispatcher) {
        // 打开会话、删除前重新加载列表时网页都会再请求列表（标题会被点点更新）
        val vm = loadedViewModel()
        val renamed = a.copy(title = "新标题")
        source.events.emit(ChatEvent.Conversations(listOf(renamed), hasMore = false))
        assertEquals(State.Loaded(listOf(Group("今天", listOf(renamed)))), vm.state.value)
    }

    @Test fun timeoutShowsErrorAndRetryReopens() = runTest(dispatcher) {
        val vm = DotsHistoryViewModel(source)
        advanceTimeBy(15_001)
        assertEquals(State.Error("加载失败，请重试"), vm.state.value)
        vm.load()
        assertEquals(State.Loading, vm.state.value)
        assertEquals(2, source.listOpens)
    }

    @Test fun deleteRemovesConversationAndEmptyGroup() = runTest(dispatcher) {
        val vm = loadedViewModel()
        var done = false
        vm.delete(c) { done = true }
        assertEquals(listOf("c"), source.deleted)
        assertEquals(State.Loaded(listOf(Group("今天", listOf(a, b)))), vm.state.value)
        assertTrue(done)
        assertEquals(null, vm.deleting.value)
    }

    @Test fun deleteFailureKeepsListAndShowsMessage() = runTest(dispatcher) {
        val vm = loadedViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        source.deleteResult = false
        var done = false
        vm.delete(a) { done = true }
        assertEquals(State.Loaded(listOf(Group("今天", listOf(a, b)), Group("7天内", listOf(c)))), vm.state.value)
        assertEquals(listOf("删除失败"), messages)
        assertEquals(null, vm.deleting.value)
        assertTrue(done)
    }

    @Test fun loadIgnoredWhileDeleting() = runTest(dispatcher) {
        val vm = loadedViewModel()
        val opensBefore = source.listOpens
        val gate = CompletableDeferred<Boolean>()
        source.deleteGate = gate
        vm.delete(a)
        vm.load()
        assertEquals(opensBefore, source.listOpens)
        assertEquals(State.Loaded(listOf(Group("今天", listOf(a, b)), Group("7天内", listOf(c)))), vm.state.value)
        gate.complete(true)
        assertEquals(null, vm.deleting.value)
    }

    @Test fun deleteIsBusyUntilReplyAndOneAtATime() = runTest(dispatcher) {
        val vm = loadedViewModel()
        val gate = CompletableDeferred<Boolean>()
        source.deleteGate = gate
        vm.delete(a)
        assertEquals("a", vm.deleting.value)
        vm.delete(b)
        assertEquals(listOf("a"), source.deleted)
        gate.complete(true)
        assertEquals(null, vm.deleting.value)
        assertEquals(State.Loaded(listOf(Group("今天", listOf(b)), Group("7天内", listOf(c)))), vm.state.value)
    }
}
