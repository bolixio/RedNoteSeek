package com.rednoteseek.feature.dots

import com.rednoteseek.core.web.ChatEvent
import com.rednoteseek.core.web.ChatMessage
import com.rednoteseek.core.web.SourceNote
import com.rednoteseek.feature.dots.DotsChatViewModel.Item
import com.rednoteseek.feature.dots.DotsChatViewModel.SourcesSheet
import com.rednoteseek.feature.dots.DotsChatViewModel.State
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DotsChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val source = FakeDotsChatSource()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val history = listOf(ChatMessage.User("西班牙公共交通"), ChatMessage.Answer("", "ai总结48篇笔记生成"))
    private val historyItems = listOf(Item.User("西班牙公共交通"), Item.Answer("", "ai总结48篇笔记生成"))

    /** 打开会话 s1 并收到历史。 */
    private suspend fun readyViewModel(): DotsChatViewModel {
        val vm = DotsChatViewModel(source, "s1")
        source.events.emit(ChatEvent.History("s1", history))
        return vm
    }

    private fun DotsChatViewModel.ready() = state.value as State.Ready
    private fun DotsChatViewModel.lastAnswer() = ready().items.last() as Item.Answer
    private fun note(id: String) = SourceNote(id, "t$id", "", 0, 0, "a", "", 0, false)

    @Test fun opensConversationAndShowsHistory() = runTest(dispatcher) {
        val vm = DotsChatViewModel(source, "s1")
        assertEquals(listOf("s1"), source.conversationOpens)
        assertEquals(State.Loading, vm.state.value)
        source.events.emit(ChatEvent.History("other", listOf(ChatMessage.User("别的"))))
        assertEquals(State.Loading, vm.state.value)
        source.events.emit(ChatEvent.History("s1", history))
        assertEquals(State.Ready(historyItems), vm.state.value)
    }

    @Test fun unsupportedHistoryAnswerKeepsUnsupportedFlag() = runTest(dispatcher) {
        val vm = DotsChatViewModel(source, "s1")
        source.events.emit(ChatEvent.History("s1", listOf(ChatMessage.User("问"), ChatMessage.Answer("", null, unsupported = true))))
        assertEquals(State.Ready(listOf(Item.User("问"), Item.Answer("", null, unsupported = true))), vm.state.value)
    }

    @Test fun stoppedHistoryAnswerKeepsStoppedFlag() = runTest(dispatcher) {
        val vm = DotsChatViewModel(source, "s1")
        source.events.emit(ChatEvent.History("s1", listOf(ChatMessage.User("问"), ChatMessage.Answer("", null, stopped = true))))
        assertEquals(State.Ready(listOf(Item.User("问"), Item.Answer("", null, stopped = true))), vm.state.value)
    }

    @Test fun historyTimeoutShowsErrorAndRetryReopens() = runTest(dispatcher) {
        val vm = DotsChatViewModel(source, "s1")
        advanceTimeBy(15_001)
        assertEquals(State.Error("加载失败，请重试"), vm.state.value)
        vm.load()
        assertEquals(State.Loading, vm.state.value)
        assertEquals(listOf("s1", "s1"), source.conversationOpens)
        source.events.emit(ChatEvent.History("s1", history))
        assertEquals(State.Ready(historyItems), vm.state.value)
    }

    @Test fun sendAppendsTurnAndStreamsAnswer() = runTest(dispatcher) {
        val vm = readyViewModel()
        assertTrue(vm.send(" 那里的地铁票怎么买？ "))
        assertEquals(listOf("那里的地铁票怎么买？"), source.sent)
        assertTrue(vm.ready().generating)
        assertEquals(Item.User("那里的地铁票怎么买？"), vm.ready().items[2])
        assertEquals(Item.Answer("", null, streaming = true), vm.lastAnswer())

        source.events.emit(ChatEvent.Thinking("s1", "正在搜 “马德里 地铁票”"))
        assertEquals("正在搜 “马德里 地铁票”", vm.lastAnswer().thinking)
        source.events.emit(ChatEvent.SourceCount("s1", "参考85篇笔记"))
        source.events.emit(ChatEvent.Delta("other", "别的会话"))
        source.events.emit(ChatEvent.Delta("s1", "西班牙没有"))
        source.events.emit(ChatEvent.Delta("s1", "统一的地铁票"))
        assertEquals(Item.Answer("西班牙没有统一的地铁票", "参考85篇笔记", streaming = true), vm.lastAnswer())

        source.events.emit(ChatEvent.Finished("s1", cancelled = false))
        assertEquals(Item.Answer("西班牙没有统一的地铁票", "参考85篇笔记"), vm.lastAnswer())
        assertFalse(vm.ready().generating)
    }

    @Test fun cannotSendBlankOrWhileGeneratingOrBeforeLoaded() = runTest(dispatcher) {
        val loading = DotsChatViewModel(source, "s2")
        assertFalse(loading.send("早了"))
        val vm = readyViewModel()
        assertFalse(vm.send("   "))
        assertTrue(vm.send("第一问"))
        assertFalse(vm.send("第二问"))
        assertEquals(listOf("第一问"), source.sent)
    }

    @Test fun stopKeepsReceivedText() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.send("问")
        source.events.emit(ChatEvent.Delta("s1", "一半"))
        vm.stop()
        assertEquals(1, source.stopCalls)
        source.events.emit(ChatEvent.Finished("s1", cancelled = true))
        assertEquals(Item.Answer("一半", null), vm.lastAnswer())
        assertFalse(vm.ready().generating)
        // 不在生成中时停止不做任何事
        vm.stop()
        assertEquals(1, source.stopCalls)
    }

    @Test fun stopBeforeAnyTextMarksStopped() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.send("问")
        source.events.emit(ChatEvent.Thinking("s1", "分析"))
        vm.stop()
        source.events.emit(ChatEvent.Finished("s1", cancelled = true))
        assertEquals(Item.Answer("", null, stopped = true), vm.lastAnswer())
    }

    @Test fun idleStreamFinishesWithWhatArrived() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.send("问")
        source.events.emit(ChatEvent.Delta("s1", "一半"))
        advanceTimeBy(29_000)
        assertTrue(vm.ready().generating)
        advanceTimeBy(1_001)
        assertEquals(Item.Answer("一半", null), vm.lastAnswer())
        assertFalse(vm.ready().generating)
    }

    @Test fun sendFailureDropsTurnAndShowsMessage() = runTest(dispatcher) {
        val vm = readyViewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        source.sendResult = false
        vm.send("问")
        assertEquals(State.Ready(historyItems), vm.state.value)
        assertEquals(listOf("发送失败，请稍后再试"), messages)
    }

    @Test fun sourcesLoadThenCachePerAnswer() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.openSources(0)
        assertEquals(listOf(0), source.sourcesOpened)
        assertEquals(SourcesSheet.Loading, vm.sources.value)
        source.events.emit(ChatEvent.Sources(listOf(note("n1"), note("n2"))))
        assertEquals(SourcesSheet.Ready(listOf(note("n1"), note("n2"))), vm.sources.value)
        vm.closeSources()
        assertEquals(null, vm.sources.value)
        assertEquals(1, source.closeSourcesCalls)
        // 网页第二次打开同一抽屉可能走缓存、不再请求：直接用收到过的列表
        vm.openSources(0)
        assertEquals(SourcesSheet.Ready(listOf(note("n1"), note("n2"))), vm.sources.value)
        assertEquals(listOf(0, 0), source.sourcesOpened)
    }

    @Test fun sourcesFailWhenEntryMissingOrTimeout() = runTest(dispatcher) {
        val vm = readyViewModel()
        source.openSourcesResult = false
        vm.openSources(0)
        assertEquals(SourcesSheet.Failed, vm.sources.value)
        vm.closeSources()
        source.openSourcesResult = true
        vm.openSources(0)
        assertEquals(SourcesSheet.Loading, vm.sources.value)
        advanceTimeBy(8_001)
        assertEquals(SourcesSheet.Failed, vm.sources.value)
    }

    @Test fun sourceNoteClickReopensDrawerFirst() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.openSources(0)
        source.events.emit(ChatEvent.Sources(listOf(note("n1"))))
        var opened = false
        vm.openSourceNote { opened = true }
        assertEquals(listOf(0, 0), source.sourcesOpened)
        assertTrue(opened)
    }

    @Test fun lateSourcesForPreviousAnswerNotCached() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.openSources(0)
        vm.closeSources()
        vm.openSources(1)
        source.events.emit(ChatEvent.Sources(listOf(note("a"))))
        assertEquals(SourcesSheet.Ready(listOf(note("a"))), vm.sources.value)
        // 已经 Ready 后再来的来源事件（上一条回答迟到的）忽略，不覆盖也不写缓存
        source.events.emit(ChatEvent.Sources(listOf(note("b"))))
        assertEquals(SourcesSheet.Ready(listOf(note("a"))), vm.sources.value)
        vm.closeSources()
        vm.openSources(0)
        assertEquals(SourcesSheet.Loading, vm.sources.value)
        vm.closeSources()
        vm.openSources(1)
        assertEquals(SourcesSheet.Ready(listOf(note("a"))), vm.sources.value)
    }
}
