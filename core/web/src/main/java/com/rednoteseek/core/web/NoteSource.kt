package com.rednoteseek.core.web

import com.rednoteseek.core.network.api.Board
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class OpenResult { Opened, NotFound }

/** 笔记详情数据源。抽成接口，便于 ViewModel 做单测。 */
interface NoteSource {
    /** 截获到的详情 / 评论事件（热流，不重放）；调用方需在 [open] 之前开始收集，并按笔记 ID 过滤。 */
    val events: Flow<NoteEvent>

    /** 在搜索页里打开笔记弹层。[searchOrder] 是 App 搜索结果的笔记 ID 顺序，用来定位已被回收的卡片。 */
    suspend fun open(noteId: String, searchOrder: List<String>): OpenResult

    /** 读页面状态里缓存的评论（网页对打开过的笔记不再请求评论接口）；没有则返回 null。 */
    suspend fun readCachedComments(noteId: String): NoteEvent.Comments?

    /** 让弹层里的评论加载下一页。 */
    fun loadMoreComments()

    /** 在网页里点击该根评论的「展开回复」，点到返回 true。 */
    suspend fun expandReplies(rootCommentId: String): Boolean

    /** 关闭笔记弹层，回到搜索页（滚动位置不变）。 */
    fun close()

    /** 读出网页笔记弹层里作者主页的链接（网页自己生成，含 xsec_token）；取不到返回 null。 */
    suspend fun readAuthorLink(): String?

    /**
     * 收藏弹层里的笔记（点网页的收藏按钮），成功后立刻点「加入专辑」并读出面板里的专辑。
     * 已经收藏时不再点击，返回 Collected(null)。
     */
    suspend fun collect(): CollectOutcome

    /** 取消收藏弹层里的笔记；取消收藏会把笔记移出所有专辑。成功（或本来就未收藏）返回 true。 */
    suspend fun uncollect(): Boolean

    /** 在「加入专辑」面板里选第 [index] 个专辑（[collect] 返回的顺序），以 v1/note/move 的响应为准。 */
    suspend fun moveToBoard(index: Int, board: Board): Boolean

    /** 关闭「加入专辑」面板（不加入专辑）。 */
    fun closeBoardPicker()
}

/**
 * 只通过点击搜索页上的卡片打开笔记：真机验证中直接调用页面路由会让 v1/feed 返回 HTTP 461。
 * 卡片被虚拟列表回收时，先按 [CardLocator] 估算的位置分步滚动，直到卡片重新出现。
 */
class WebNoteSource(private val engine: XhsWebEngine) : NoteSource {

    override val events: Flow<NoteEvent> = engine.responses.mapNotNull(NoteParser::parse)

    override suspend fun open(noteId: String, searchOrder: List<String>): OpenResult {
        if (!PageScripts.isValidNoteId(noteId)) return OpenResult.NotFound
        engine.traceAction("open note $noteId")
        var scrolls = 0
        var sinceScroll = SCROLL_SETTLE_MS
        var pendingPolls = 0
        // 滚动后虚拟列表要一会儿才按新位置重新渲染：每 SETTLE_POLL_MS 查一次，卡片一出现就点；
        // 等满 SCROLL_SETTLE_MS 仍没出现，再按新的卡片位置重新估算、滚动
        while (true) {
            val snapshot = CardSnapshot.parse(engine.evaluate(PageScripts.reportCards(noteId)))
                ?: return OpenResult.NotFound
            if (snapshot.found) {
                when (engine.evaluate(PageScripts.clickCard(noteId))) {
                    "clicked" -> {
                        engine.traceEvent("card found after $scrolls scrolls, click")
                        return OpenResult.Opened
                    }
                    // 网页脚本还没接管卡片：稍等再点，等不到就放弃（绝不在接管前点，见 clickCard）
                    "pending" -> if (++pendingPolls > MAX_PENDING_POLLS) return OpenResult.NotFound
                    else -> return OpenResult.NotFound
                }
                delay(SETTLE_POLL_MS)
                continue
            }
            if (sinceScroll >= SCROLL_SETTLE_MS) {
                if (scrolls >= MAX_LOCATE_ATTEMPTS) return OpenResult.NotFound
                val y = CardLocator.nextScrollY(snapshot, searchOrder, noteId) ?: return OpenResult.NotFound
                engine.evaluate(PageScripts.scrollTo(y))
                scrolls++
                sinceScroll = 0
            }
            delay(SETTLE_POLL_MS)
            sinceScroll += SETTLE_POLL_MS
        }
    }

    override suspend fun readCachedComments(noteId: String): NoteEvent.Comments? {
        if (!PageScripts.isValidNoteId(noteId)) return null
        return NoteParser.parseCachedComments(noteId, engine.evaluate(PageScripts.readCachedComments(noteId)))
    }

    override fun loadMoreComments() {
        engine.traceAction("more comments")
        engine.runScript(PageScripts.SCROLL_COMMENTS)
    }

    override suspend fun expandReplies(rootCommentId: String): Boolean {
        if (!PageScripts.isValidNoteId(rootCommentId)) return false
        engine.traceAction("expand replies $rootCommentId")
        return engine.evaluate(PageScripts.expandReplies(rootCommentId)) == "clicked"
    }

    override fun close() = engine.runScript(PageScripts.CLOSE_NOTE)

    override suspend fun readAuthorLink(): String? =
        engine.evaluate(PageScripts.AUTHOR_LINK).takeIf(XhsUrls::isProfileUrl)

    override suspend fun collect(): CollectOutcome = coroutineScope {
        // 先挂上监听再点击（responses 是不重放的热流）
        val reply = async(start = CoroutineStart.UNDISPATCHED) { awaitReply(ApiPaths.NOTE_COLLECT) }
        val pages = Channel<List<Board>?>(Channel.UNLIMITED)
        val pageJob = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.responses.filter { it.path == ApiPaths.BOARD_USER && it.status != 0 }
                .collect { pages.trySend(WriteReply.boardPage(it)) }
        }
        try {
            engine.traceAction("collect")
            when (engine.evaluate(PageScripts.collectButton(true))) {
                "done" -> return@coroutineScope CollectOutcome.Collected(null)
                "clicked" -> if (reply.await()?.let(WriteReply::ok) != true) return@coroutineScope CollectOutcome.Failed
                else -> return@coroutineScope CollectOutcome.Failed
            }
            // 「加入专辑」提示只显示几秒，收藏成功后立刻去点
            var joined = false
            for (i in 0 until JOIN_POLL_TIMES) {
                val r = engine.evaluate(PageScripts.JOIN_BOARD)
                if (r == "clicked" || r == "open") {
                    joined = true
                    break
                }
                delay(JOIN_POLL_MS)
            }
            if (!joined) return@coroutineScope CollectOutcome.Collected(null)
            // 面板每页 15 个专辑，满页就滚动面板加载下一页
            // 第一页没等到（超时或解析失败）时返回 null，不能当成「没有专辑」
            var boards: MutableList<Board>? = null
            for (i in 0 until MAX_PANEL_PAGES) {
                val page = withTimeoutOrNull(REPLY_TIMEOUT_MS) { pages.receive() } ?: break
                boards = (boards ?: mutableListOf()).apply { addAll(page) }
                if (page.size < PANEL_PAGE_SIZE) break
                engine.evaluate(PageScripts.SCROLL_BOARD_PANEL)
            }
            CollectOutcome.Collected(boards)
        } finally {
            reply.cancel()
            pageJob.cancel()
        }
    }

    override suspend fun uncollect(): Boolean = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) { awaitReply(ApiPaths.NOTE_UNCOLLECT) }
        engine.traceAction("uncollect")
        when (engine.evaluate(PageScripts.collectButton(false))) {
            "done" -> { reply.cancel(); true }
            "clicked" -> reply.await()?.let(WriteReply::ok) ?: false
            else -> { reply.cancel(); false }
        }
    }

    override suspend fun moveToBoard(index: Int, board: Board): Boolean = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) { awaitReply(ApiPaths.NOTE_MOVE) }
        engine.traceAction("move to board ${board.id}")
        // 面板还没加载到该位置时脚本会滚动面板，稍后重试
        var result = "loading"
        for (i in 0 until JOIN_POLL_TIMES) {
            result = engine.evaluate(PageScripts.pickBoard(index, board.name))
            if (result != "loading") break
            delay(JOIN_POLL_MS * 3)
        }
        if (result == "clicked") {
            reply.await()?.let(WriteReply::ok) ?: false
        } else {
            reply.cancel()
            false
        }
    }

    override fun closeBoardPicker() = engine.runScript(PageScripts.CLOSE_BOARD_PANEL)

    /** 等待下一次 [path] 的响应；超时返回 null。 */
    private suspend fun awaitReply(path: String): CapturedResponse? =
        withTimeoutOrNull(REPLY_TIMEOUT_MS) { engine.responses.first { it.path == path && it.status != 0 } }

    private companion object {
        const val REPLY_TIMEOUT_MS = 8_000L
        const val JOIN_POLL_TIMES = 30
        const val JOIN_POLL_MS = 100L
        const val PANEL_PAGE_SIZE = 15
        const val MAX_PANEL_PAGES = 10
        const val MAX_LOCATE_ATTEMPTS = 8
        const val SCROLL_SETTLE_MS = 600L
        const val SETTLE_POLL_MS = 100L
        /** 等网页脚本接管卡片最多 5 秒（× [SETTLE_POLL_MS]）。 */
        const val MAX_PENDING_POLLS = 50
    }
}
