package com.rednoteseek.core.web

import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.network.api.PostedResult
import com.rednoteseek.core.network.api.ProfileSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 第二引擎里网页的位置：用于返回主页 / 收藏页时判断是否需要重新打开。 */
data class PageLocation(val path: String, val collectTabActive: Boolean, val stale: Boolean = false, val subTab: String = "")

/** 用户主页、「我」的收藏与专辑的数据源（运行在第二网页引擎上）。抽成接口便于 ViewModel 单测。 */
interface NoteListSource {
    /** 截获到的主页笔记 / 收藏翻页事件（热流，不重放）；调用方需先收集再打开。 */
    val events: Flow<NoteListEvent>

    /** 打开用户主页（只接受 [XhsUrls.isProfileUrl] 地址），返回首屏；超时或失败返回 null。 */
    suspend fun openProfile(url: String): ProfileSnapshot?

    /** 打开自己的主页并点「收藏」标签；第一页经 [events] 到达。标签真正选中返回 true。 */
    suspend fun openCollections(selfUserId: String): Boolean

    /** 打开自己主页的「收藏 › 专辑」，读服务端渲染的专辑列表；超时或失败返回 null。 */
    suspend fun openBoards(selfUserId: String): List<Board>?

    /** 打开专辑页，返回首屏笔记；之后的页经 [events]（[NoteListKind.BOARD]）到达。超时或失败返回 null。 */
    suspend fun openBoard(boardId: String): PostedResult?

    /** 读专辑页上的专辑信息，作为编辑表单的初值；读不到返回 null。 */
    suspend fun readBoardForm(boardId: String): BoardForm?

    /** 在「收藏 › 专辑」点「创建专辑」并提交表单，返回新专辑 ID；失败返回 null。 */
    suspend fun createBoard(selfUserId: String, form: BoardForm): String?

    /** 在专辑页点「编辑专辑」并提交表单，以 v1/board 的响应为准。 */
    suspend fun updateBoard(boardId: String, form: BoardForm): Boolean

    /** 在专辑页的编辑表单里删除专辑（含确认框），以 v1/board 的响应为准。删除后网页会回到专辑列表。 */
    suspend fun deleteBoard(boardId: String): Boolean

    suspend fun location(): PageLocation

    /** 滚动整页，触发下一页。 */
    fun loadMore()

    /**
     * 在账号上关注 / 取消关注 [userId]：点第二引擎当前主页上网页自己的关注按钮（签名由页面处理）。
     * 引擎必须停在该用户主页；以截获到的接口响应为准，成功（或网页上已是目标状态）返回 true。
     */
    suspend fun setFollow(userId: String, follow: Boolean): Boolean

    /** 打开自己的主页并点开关注列表弹窗，读每位关注者的未看笔记数。 */
    suspend fun followingUnread(selfUserId: String): UnreadResult
}

class WebNoteListSource(private val engine: XhsWebEngine) : NoteListSource {

    override val events: Flow<NoteListEvent> = engine.responses.mapNotNull(NoteListParser::parse)

    /** 打开次数计数：新的打开会让旧的轮询退出，避免它在别人的页面上继续执行脚本。只在主线程读写。 */
    private var generation = 0

    /** 预热的「收藏 › 专辑」页还没被用过：首次 [openBoards] 直接读它。整页加载其他页面或发生收藏写操作即作废。只在主线程读写。 */
    private var warm = false

    /** 启动后让第二引擎先停在「收藏 › 专辑」；第二引擎已被用过（有过任何打开）就不做。 */
    suspend fun prewarm(selfUserId: String) {
        if (generation != 0 || !PageScripts.isValidNoteId(selfUserId)) return
        val myGen = ++generation
        engine.evaluate(PageScripts.MARK_STALE)
        // 等脚本返回期间可能已有别的打开（如用户点了收藏标签），就不再加载
        if (generation != myGen) return
        engine.load(XhsUrls.selfBoards(selfUserId))
        warm = true
    }

    /** 收藏 / 专辑写操作后调用：服务端渲染的专辑列表（数量、封面）已过时。 */
    fun discardWarm() {
        warm = false
    }

    override suspend fun openProfile(url: String): ProfileSnapshot? {
        if (!XhsUrls.isProfileUrl(url)) return null
        val targetId = PROFILE_ID.find(url)?.groupValues?.get(1) ?: return null
        val myGen = ++generation
        warm = false
        engine.evaluate(PageScripts.MARK_STALE)
        engine.load(url)
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MS)
            if (generation != myGen) return null
            // 只接受目标用户的首屏，防止读到上一个页面的数据
            NoteListParser.parseSnapshot(engine.evaluate(PageScripts.PROFILE_SNAPSHOT))
                ?.takeIf { it.user.user_id == targetId }
                ?.let {
                    engine.traceEvent("profile snapshot ready")
                    return it
                }
        }
        return null
    }

    override suspend fun openCollections(selfUserId: String): Boolean {
        if (!PageScripts.isValidNoteId(selfUserId)) return false
        val myGen = ++generation
        val selfPath = "/user/profile/$selfUserId"
        val start = location()
        // 已在自己主页就点网页的标签（SPA 内导航，不整页重载）；已在显示收藏笔记时再点不会发新请求，只能整页加载。
        // 「显示收藏笔记」与下面轮询的完成条件一致：收藏标签已选中且不在专辑子标签
        if (!start.stale && start.path.trimEnd('/') == selfPath && !(start.collectTabActive && start.subTab != "board")) {
            engine.traceAction("collect in page")
        } else {
            warm = false
            engine.evaluate(PageScripts.MARK_STALE)
            engine.load(XhsUrls.profile(selfUserId))
        }
        var sinceClick = CLICK_RETRY_MS
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MS)
            if (generation != myGen) return false
            sinceClick += POLL_INTERVAL_MS
            val here = location()
            // 引擎可能已被别的页面占用：不在自己的主页就既不返回也不点击
            if (here.stale || here.path.trimEnd('/') != selfPath) return@repeat
            if (here.collectTabActive && here.subTab != "board") {
                engine.traceEvent("collect tab active")
                return true
            }
            // 服务端渲染的标签会先出现，网页脚本稍后才接管点击，过早的点击会被忽略（真机验证）：
            // 以「标签真正选中」为准，点了 0.5 秒仍未选中就再点一次（真机：1.2 秒的点击被忽略，原先隔 2 秒才重试）。
            // 停在「收藏 › 专辑」时收藏标签已选中，要点的是其下的「笔记・N」子标签
            if (sinceClick >= CLICK_RETRY_MS) {
                val script = if (here.collectTabActive) PageScripts.COLLECT_NOTES_SUBTAB else PageScripts.COLLECT_TAB
                val result = engine.evaluate(script)
                engine.traceEvent("collect tab: $result")
                if (result != "missing") sinceClick = 0
            }
        }
        return false
    }

    override suspend fun openBoards(selfUserId: String): List<Board>? {
        if (!PageScripts.isValidNoteId(selfUserId)) return null
        val selfPath = "/user/profile/$selfUserId"
        if (warm) {
            warm = false
            val myGen = ++generation
            for (i in 0 until POLL_TIMES) {
                delay(POLL_INTERVAL_MS)
                if (generation != myGen) return null
                val here = location()
                // 预热页可能还在加载
                if (here.stale || here.path.trimEnd('/') != selfPath) continue
                // 已被页内切到收藏笔记：专辑列表不在页面上了，整页加载
                if (here.subTab != "board") break
                readBoards()?.let {
                    engine.traceEvent("prewarmed boards ready")
                    return it
                }
            }
            // 切走了，或预热页一直没加载出来：整页加载
        }
        return loadAndPoll(XhsUrls.selfBoards(selfUserId), selfPath) { readBoards() }
    }

    private suspend fun readBoards(): List<Board>? =
        NoteListParser.parseBoardList(engine.evaluate(PageScripts.BOARD_LIST))?.boards

    override suspend fun openBoard(boardId: String): PostedResult? {
        if (!PageScripts.isValidNoteId(boardId)) return null
        return loadAndPoll(XhsUrls.board(boardId), "/board/$boardId") {
            NoteListParser.parseBoardSnapshot(engine.evaluate(PageScripts.BOARD_SNAPSHOT))
        }
    }

    override suspend fun createBoard(selfUserId: String, form: BoardForm): String? {
        if (!PageScripts.isValidNoteId(selfUserId)) return null
        // 总是重新加载「收藏 › 专辑」：表单入口只在这个子标签上
        openBoards(selfUserId) ?: return null
        return submitBoard(create = true) { engine.evaluate(PageScripts.submitBoardForm(form)) }
            ?.let(WriteReply::createdBoardId)
    }

    override suspend fun readBoardForm(boardId: String): BoardForm? {
        if (!atBoard(boardId)) return null
        return BoardForm.parseDetails(engine.evaluate(PageScripts.BOARD_DETAILS))?.takeIf { it.first == boardId }?.second
    }

    override suspend fun updateBoard(boardId: String, form: BoardForm): Boolean {
        if (!atBoard(boardId)) return false
        return submitBoard(create = false) { engine.evaluate(PageScripts.submitBoardForm(form)) }
            ?.let(WriteReply::ok) ?: false
    }

    override suspend fun deleteBoard(boardId: String): Boolean {
        if (!atBoard(boardId)) return false
        return submitBoard(create = false) {
            // 先点「删除专辑」弹出确认框，再点确认
            var result = engine.evaluate(PageScripts.DELETE_BOARD)
            for (i in 0 until FORM_POLL_TIMES) {
                if (result != "asked") break
                delay(POLL_INTERVAL_MS)
                result = engine.evaluate(PageScripts.DELETE_BOARD)
            }
            if (result == "confirmed") "submitted" else result
        }?.let(WriteReply::ok) ?: false
    }

    /** 确保第二引擎停在该专辑页（已在就不重新加载）。 */
    private suspend fun atBoard(boardId: String): Boolean {
        if (!PageScripts.isValidNoteId(boardId)) return false
        val here = location()
        if (!here.stale && here.path.trimEnd('/') == "/board/$boardId") return true
        return openBoard(boardId) != null
    }

    /**
     * 打开专辑表单（网页脚本接管前的点击会被忽略，表单没出现就再点）后执行 [submit]，
     * 等待 v1/board 的响应；表单打不开、[submit] 没返回 submitted 或超时返回 null。
     */
    private suspend fun submitBoard(create: Boolean, submit: suspend () -> String): CapturedResponse? = coroutineScope {
        var opened = false
        for (i in 0 until FORM_POLL_TIMES) {
            val r = engine.evaluate(PageScripts.openBoardForm(create))
            if (r == "open") {
                opened = true
                break
            }
            delay(if (r == "clicked") CLICK_RETRY_MS else POLL_INTERVAL_MS)
        }
        if (!opened) return@coroutineScope null
        // 先挂上监听再提交（responses 是不重放的热流）
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { engine.responses.first { it.path == ApiPaths.BOARD && it.status != 0 } }
        }
        engine.traceAction(if (create) "create board" else "edit board")
        if (submit() != "submitted") {
            reply.cancel()
            return@coroutineScope null
        }
        reply.await()
    }

    /** 加载 [url]，轮询直到停在 [path] 且 [read] 读到数据；被新的打开取代或超时返回 null。 */
    private suspend fun <T> loadAndPoll(url: String, path: String, read: suspend () -> T?): T? {
        val myGen = ++generation
        warm = false
        engine.evaluate(PageScripts.MARK_STALE)
        engine.load(url)
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MS)
            if (generation != myGen) return null
            // 先核对路径，防止读到上一个页面的数据
            val here = location()
            if (here.stale || here.path.trimEnd('/') != path) return@repeat
            read()?.let {
                engine.traceEvent("snapshot ready $path")
                return it
            }
        }
        return null
    }

    override suspend fun location(): PageLocation = runCatching {
        val obj = Json.parseToJsonElement(engine.evaluate(PageScripts.PAGE_LOCATION)).jsonObject
        PageLocation(
            obj.getValue("path").jsonPrimitive.content,
            obj.getValue("collect").jsonPrimitive.boolean,
            obj["stale"]?.jsonPrimitive?.boolean ?: false,
            obj["sub"]?.jsonPrimitive?.content.orEmpty(),
        )
    }.getOrElse { PageLocation("", false) }

    override fun loadMore() = engine.scrollPage()

    override suspend fun setFollow(userId: String, follow: Boolean): Boolean {
        if (!PageScripts.isValidNoteId(userId)) return false
        val here = location()
        if (here.stale || here.path.trimEnd('/') != "/user/profile/$userId") return false
        val path = if (follow) ApiPaths.USER_FOLLOW else ApiPaths.USER_UNFOLLOW
        return coroutineScope {
            // 先挂上监听再点击（responses 是不重放的热流）
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { engine.responses.first { it.path == path && it.status != 0 } }
            }
            engine.traceAction(if (follow) "follow" else "unfollow")
            when (engine.evaluate(PageScripts.followButton(follow))) {
                "done" -> { reply.cancel(); true }
                "clicked" -> reply.await()?.let(WriteReply::ok) ?: false
                else -> { reply.cancel(); false }
            }
        }
    }

    override suspend fun followingUnread(selfUserId: String): UnreadResult {
        if (!PageScripts.isValidNoteId(selfUserId)) return UnreadResult.Failed(risk = false)
        val myGen = ++generation
        return coroutineScope {
            // 先挂上监听再加载（responses 是不重放的热流）
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                engine.responses.first {
                    it.path == ApiPaths.FOLLOWINGS_EXTRA && it.status != 0 && it.queryParam("user_id") == selfUserId
                }
            }
            val start = location()
            // 已在自己主页（如预热停在「收藏 › 专辑」）就直接点「关注」，不整页重载
            if (!start.stale && start.path.trimEnd('/') == "/user/profile/$selfUserId") {
                engine.traceAction("following list in page")
                // 上次的弹窗若没关掉，FOLLOWING_LIST 只会返回 open 而不点击，收不到新的未看数
                engine.evaluate(PageScripts.CLOSE_RELATION_PANEL)
            } else {
                warm = false
                engine.evaluate(PageScripts.MARK_STALE)
                engine.load(XhsUrls.profile(selfUserId))
            }
            var sinceClick = CLICK_RETRY_MS
            var result: UnreadResult = UnreadResult.Failed(risk = false)
            for (i in 0 until POLL_TIMES) {
                delay(POLL_INTERVAL_MS)
                if (generation != myGen) break
                if (reply.isCompleted) {
                    result = FollowParsers.unread(reply.await())
                    break
                }
                sinceClick += POLL_INTERVAL_MS
                val here = location()
                if (here.stale || here.path.trimEnd('/') != "/user/profile/$selfUserId") continue
                // 与收藏标签相同：网页脚本接管前的点击会被忽略，弹窗没出现就隔 0.5 秒再点
                if (sinceClick >= CLICK_RETRY_MS) {
                    val clicked = engine.evaluate(PageScripts.FOLLOWING_LIST)
                    engine.traceEvent("following list: $clicked")
                    if (clicked == "clicked") sinceClick = 0
                }
            }
            reply.cancel()
            // 关掉弹窗：下次在同一页面上读未看数还要再点开（已被别的打开取代就不碰页面）
            if (generation == myGen) engine.evaluate(PageScripts.CLOSE_RELATION_PANEL)
            result
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 8_000L
        const val POLL_TIMES = 150
        const val POLL_INTERVAL_MS = 100L
        const val CLICK_RETRY_MS = 500L
        const val FORM_POLL_TIMES = 30
        val PROFILE_ID = Regex("/user/profile/([0-9a-zA-Z]+)")
    }
}
