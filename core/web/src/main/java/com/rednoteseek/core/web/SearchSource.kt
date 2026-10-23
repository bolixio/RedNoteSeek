package com.rednoteseek.core.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 搜索数据源。抽成接口，便于 ViewModel 做单测。 */
interface SearchSource {
    /** 截获到的搜索事件（热流，不重放）；调用方需在 [open] 之前开始收集，并按关键词过滤。 */
    val events: Flow<SearchEvent>

    /** 点点 AI 总结事件（热流，不重放），只在 [SearchMode.AI] 的结果页上出现；同样要在 [open] 之前开始收集。 */
    val summaries: Flow<AiSummaryEvent>

    /**
     * 打开某个关键词的搜索结果，页面会自己请求第一页。
     * 已停在该模式的结果页时用网页搜索框换词（SPA 内导航，不整页重载）；否则整页加载该模式的结果页。
     */
    suspend fun open(keyword: String, mode: SearchMode)

    /** 让页面加载下一页。 */
    fun loadMore()

    /** 在网页筛选面板里切换排序，页面会重新请求第一页。成功点到标签返回 true。 */
    suspend fun applySort(sort: SearchSort): Boolean

    /** 打开网页的「参考来源」抽屉（之后点来源笔记就点抽屉里的卡片）。点到或已打开返回 true。 */
    suspend fun openSummarySources(): Boolean

    /** 关闭「参考来源」抽屉。 */
    fun closeSummarySources()

    /**
     * 「继续问点点」：在 AI 结果页点「问点点」标签，网页新建一个会话并把当前总结挂进去（search/signal），
     * 拿到会话 ID 后后退回结果页（SPA 内返回，笔记和总结都还在）。任一步失败返回 null；已经离开结果页的话也会后退。
     */
    suspend fun continueInChat(): String?
}

class WebSearchSource(private val engine: XhsWebEngine) : SearchSource {
    override val events: Flow<SearchEvent> = engine.responses.mapNotNull(SearchParser::parse)
    override val summaries: Flow<AiSummaryEvent> =
        engine.responses.transform { captured -> SummaryParser.parse(captured).forEach { emit(it) } }

    override suspend fun open(keyword: String, mode: SearchMode) {
        engine.traceAction("search in page $keyword ($mode)")
        val keywordScript = if (mode == SearchMode.AI) PageScripts.AI_SEARCH_KEYWORD else PageScripts.SEARCH_KEYWORD
        // 同一关键词：URL 早已匹配，无法确认导航，直接整页加载
        if (engine.evaluate(keywordScript) != keyword && sendInPage(keyword, mode)) {
            // SPA 导航在 ~200ms 内发生；1 秒内 URL 没变成新关键词就当 Enter 被忽略，回退整页加载
            repeat(KEYWORD_POLL_TIMES) {
                delay(KEYWORD_POLL_MS)
                if (engine.evaluate(keywordScript) == keyword) {
                    engine.traceEvent("search navigated in page")
                    return
                }
            }
        }
        engine.load(if (mode == SearchMode.AI) XhsUrls.searchAi(keyword) else XhsUrls.search(keyword))
    }

    /**
     * 往网页搜索框填词并回车；结果页还在启动（网页脚本未接管 / 搜索框未渲染）就等它，
     * 不整页重载丢掉启动进度（冷启动时网页脚本约 2 秒才接管）。不在该模式可用的页面或等不到时返回 false。
     */
    private suspend fun sendInPage(keyword: String, mode: SearchMode): Boolean {
        val script = if (mode == SearchMode.AI) PageScripts.aiSearchInPage(keyword) else PageScripts.searchInPage(keyword)
        repeat(BOOT_WAIT_TIMES) {
            when (engine.evaluate(script)) {
                "sent" -> return true
                "elsewhere" -> return false
            }
            delay(KEYWORD_POLL_MS)
        }
        return false
    }

    override fun loadMore() = engine.scrollPage()

    override suspend fun applySort(sort: SearchSort): Boolean {
        engine.traceAction("sort ${sort.name}")
        if (engine.evaluate(PageScripts.OPEN_SEARCH_FILTER) == "missing") return false
        // 面板约 100ms 渲染出来（真机），标签一出现就点，不固定等待
        repeat(SORT_PICK_TIMES) {
            if (engine.evaluate(PageScripts.pickSearchSort(sort)) == "clicked") return true
            delay(SORT_PICK_POLL_MS)
        }
        return false
    }

    override suspend fun openSummarySources(): Boolean {
        engine.traceAction("summary sources")
        return engine.evaluate(PageScripts.OPEN_SUMMARY_SOURCES) != "missing"
    }

    override fun closeSummarySources() = engine.runScript(PageScripts.CLOSE_SUMMARY_SOURCES)

    override suspend fun continueInChat(): String? = coroutineScope {
        engine.traceAction("continue in chat")
        // 先挂上 search/signal 的监听再点标签（responses 是不重放的热流）
        val signal = async(start = CoroutineStart.UNDISPATCHED) {
            engine.responses.first { it.path == ApiPaths.SEARCH_SIGNAL && it.status != 0 }
        }
        // 点击之后无论取消还是后续哪步失败，都要把主页面退回 AI 结果页（finally 里不可取消地补一次）
        var clicked = false
        var backed = false
        try {
            if (engine.evaluate(PageScripts.ASK_TAB) != "clicked") {
                // 万一点击其实已生效（网页已跳到 /ai_chat_tab），也退回结果页
                backToResults()
                return@coroutineScope null
            }
            clicked = true
            // 网页生成会话 ID 并 SPA 跳到 /ai_chat_tab
            val id = pollPage { it.path.trimEnd('/') == ASK_TAB_PATH && it.conversationId.isNotEmpty() }?.conversationId
            // 总结挂进会话之后，对话页里才有总结正文
            val linked = id != null && withTimeoutOrNull(SIGNAL_TIMEOUT_MS) { signal.await() }?.status == 200
            val back = backToResults(afterClick = true)
            backed = true
            if (linked && back) id else null
        } finally {
            signal.cancel()
            if (clicked && !backed) withContext(NonCancellable) { backToResults(afterClick = true) }
        }
    }

    /** 每 [KEYWORD_POLL_MS] 读一次页面位置，满足 [done] 就返回；[ASK_TAB_POLL_TIMES] 次内没等到返回 null。 */
    private suspend fun pollPage(done: (AskTabState) -> Boolean): AskTabState? {
        repeat(ASK_TAB_POLL_TIMES) {
            AskTabState.parse(engine.evaluate(PageScripts.ASK_TAB_STATE))?.takeIf(done)?.let { return it }
            delay(KEYWORD_POLL_MS)
        }
        return null
    }

    /**
     * 停在 /ai_chat_tab 就后退并等回到 AI 结果页；只在这个页面上后退，别的页面（如 /explore）一律不动。
     * 返回是否停在 AI 结果页；读不到页面位置返回 false。
     */
    private suspend fun backToResults(afterClick: Boolean = false): Boolean {
        var now = AskTabState.parse(engine.evaluate(PageScripts.ASK_TAB_STATE)) ?: return false
        if (afterClick) {
            // 刚点过标签但 SPA 跳转可能还没发生：仍在结果页就稍等一会儿，等到 /ai_chat_tab 出现才有东西可退
            repeat(AFTER_CLICK_POLL_TIMES) {
                if (now.path.trimEnd('/') != AI_RESULT_PATH) return@repeat
                delay(KEYWORD_POLL_MS)
                now = AskTabState.parse(engine.evaluate(PageScripts.ASK_TAB_STATE)) ?: return false
            }
        }
        if (now.path.trimEnd('/') != ASK_TAB_PATH) return now.path.trimEnd('/') == AI_RESULT_PATH
        engine.evaluate(PageScripts.HISTORY_BACK)
        return pollPage { it.path.trimEnd('/') == AI_RESULT_PATH } != null
    }

    private companion object {
        const val AFTER_CLICK_POLL_TIMES = 10
        const val SORT_PICK_TIMES = 20
        const val SORT_PICK_POLL_MS = 100L
        const val KEYWORD_POLL_TIMES = 10
        const val KEYWORD_POLL_MS = 100L
        /** 等结果页启动最多 3 秒（× [KEYWORD_POLL_MS]）。 */
        const val BOOT_WAIT_TIMES = 30
        /** 「问点点」之后等页面跳转 / 后退最多 3 秒（× [KEYWORD_POLL_MS]）。 */
        const val ASK_TAB_POLL_TIMES = 30
        const val SIGNAL_TIMEOUT_MS = 5_000L
        const val ASK_TAB_PATH = "/ai_chat_tab"
        const val AI_RESULT_PATH = "/search_result_ai"
    }
}
