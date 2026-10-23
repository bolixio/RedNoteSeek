package com.rednoteseek.core.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeoutOrNull

/** 点点会话数据源（对话引擎，只加载 /ai_chat 页面）。抽成接口，便于 ViewModel 做单测。 */
interface DotsChatSource {
    /** 截获到的会话事件（热流，不重放）；调用方需在打开页面之前开始收集，并按会话 ID 过滤。 */
    val events: Flow<ChatEvent>

    /** 整页加载对话页；网页随即请求会话列表（[ChatEvent.Conversations]）。 */
    suspend fun openList()

    /** 整页加载某个会话；网页随即请求它的消息（[ChatEvent.History]）和会话列表。 */
    suspend fun openConversation(id: String)

    /** 往网页输入框填问题并点发送；点到发送按钮返回 true。回答从 [events] 流式到达。 */
    suspend fun send(text: String): Boolean

    /** 点网页的停止生成按钮；服务器随即以 cancel 结束这次回答（[ChatEvent.Finished]）。 */
    fun stop()

    /** 在网页侧边栏删除会话（不可恢复，调用前须已在 App 里确认），以截获的删除响应为准。 */
    suspend fun delete(conversation: DotsConversation): Boolean

    /** 打开第 [answerIndex] 条回答的参考来源抽屉（之后点来源笔记就点抽屉里的卡片）。点到或已打开返回 true。 */
    suspend fun openSources(answerIndex: Int): Boolean

    /** 关闭参考来源抽屉。 */
    fun closeSources()
}

class WebDotsChatSource(private val engine: XhsWebEngine) : DotsChatSource {

    override val events: Flow<ChatEvent> =
        engine.responses.transform { captured -> ChatParser.parse(captured).forEach { emit(it) } }

    override suspend fun openList() {
        engine.evaluate(PageScripts.MARK_STALE)
        engine.load(XhsUrls.dotsChat())
    }

    override suspend fun openConversation(id: String) {
        engine.evaluate(PageScripts.MARK_STALE)
        engine.load(XhsUrls.dotsConversation(id))
    }

    override suspend fun send(text: String): Boolean {
        engine.traceAction("chat send")
        // 输入框随页面启动渲染；填好后发送按钮要等网页处理完 input 事件才变成可点
        if (!poll(FILL_POLL_TIMES) { engine.evaluate(PageScripts.chatFill(text)) == "filled" }) return false
        // 刚加载的页面上第一次输入会让网页换掉输入框元素（新元素是空的，发送按钮仍不可点，真机验证）：
        // 按钮迟迟不可点就重新填一次（脚本会找到换上来的新输入框）
        var attempts = 0
        return poll(SUBMIT_POLL_TIMES) {
            if (engine.evaluate(PageScripts.CHAT_SUBMIT) == "sent") return@poll true
            if (++attempts % REFILL_EVERY == 0) engine.evaluate(PageScripts.chatFill(text))
            false
        }
    }

    override fun stop() {
        engine.traceAction("chat stop")
        engine.runScript(PageScripts.CHAT_STOP)
    }

    override suspend fun delete(conversation: DotsConversation): Boolean = coroutineScope {
        engine.traceAction("delete conversation ${conversation.id}")
        // 先挂上监听再加载 / 点击（events 是不重放的热流）。旧文档在 load 之前可能还有一份在途的列表响应，
        // 只认 openList() 返回（已发出 load）之后到达的列表：新页面的列表请求必然在那之后才回来。
        // 响应都在主线程派发，普通的局部变量即可；万一仍认错，chatDelete 的标题核对会返回 mismatch，不会删错。
        var loadIssued = false
        val list = async(start = CoroutineStart.UNDISPATCHED) {
            events.filterIsInstance<ChatEvent.Conversations>().first { loadIssued }
        }
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            events.filterIsInstance<ChatEvent.Deleted>().first { conversation.id in it.sessionIds }
        }
        try {
            // 会话顺序会变（发过消息的会话移到最前）：重新加载列表页，按刚截获的列表定位侧边栏条目
            openList()
            // 旧文档的在途列表响应可能在 load 之后才到：等新文档真正开始（旧文档带过期标记，其 JS 上下文消失后不会再发）才认列表
            if (!poll(FRESH_POLL_TIMES) { engine.evaluate(PageScripts.PAGE_FRESH) == "fresh" }) return@coroutineScope false
            loadIssued = true
            val items = withTimeoutOrNull(REPLY_TIMEOUT_MS) { list.await() }?.items ?: return@coroutineScope false
            val index = items.indexOfFirst { it.id == conversation.id }
            if (index < 0) return@coroutineScope false
            var result = "loading"
            for (i in 0 until DELETE_POLL_TIMES) {
                result = engine.evaluate(PageScripts.chatDelete(index, items[index].title))
                if (result != "loading") break
                delay(POLL_MS)
            }
            if (result != "clicked") return@coroutineScope false
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }?.ok == true
        } finally {
            list.cancel()
            reply.cancel()
        }
    }

    override suspend fun openSources(answerIndex: Int): Boolean {
        engine.traceAction("chat sources $answerIndex")
        return engine.evaluate(PageScripts.chatOpenSources(answerIndex)) != "missing"
    }

    override fun closeSources() = engine.runScript(PageScripts.CLOSE_SUMMARY_SOURCES)

    /** 每 [POLL_MS] 试一次 [attempt]，成功返回 true；[times] 次都不成功返回 false。 */
    private suspend fun poll(times: Int, attempt: suspend () -> Boolean): Boolean {
        repeat(times) {
            if (attempt()) return true
            delay(POLL_MS)
        }
        return false
    }

    private companion object {
        const val POLL_MS = 100L
        /** 等输入框最多 3 秒。 */
        const val FILL_POLL_TIMES = 30
        /** 等发送按钮变成可点最多 3 秒。 */
        const val SUBMIT_POLL_TIMES = 30
        /** 发送按钮每这么多次（× [POLL_MS]）仍不可点就重新填词。 */
        const val REFILL_EVERY = 5
        /** 等新页面接管、侧边栏展开、条目出现最多 5 秒（× [POLL_MS]）。 */
        const val DELETE_POLL_TIMES = 50
        /** 等新文档开始（不再带过期标记）最多 5 秒。 */
        const val FRESH_POLL_TIMES = 50
        const val REPLY_TIMEOUT_MS = 8_000L
    }
}
