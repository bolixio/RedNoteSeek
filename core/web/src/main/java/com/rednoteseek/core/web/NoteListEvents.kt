package com.rednoteseek.core.web

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.XhsResponse
import com.rednoteseek.core.network.api.BoardList
import com.rednoteseek.core.network.api.PostedNote
import com.rednoteseek.core.network.api.PostedResult
import com.rednoteseek.core.network.api.ProfileSnapshot

enum class NoteListKind { POSTED, COLLECTED, BOARD }

/** 主页笔记（v1/user_posted）、收藏（v2/note/collect/page）与专辑笔记（v1/board/note）的截获事件。 */
sealed interface NoteListEvent {
    val kind: NoteListKind
    /** query 里的 user_id（专辑为 board_id）；缺失时为 null，调用方视为匹配。 */
    val userId: String?

    data class Page(
        override val kind: NoteListKind,
        override val userId: String?,
        /** 这次请求带的 cursor，第一页为空串。 */
        val requestCursor: String,
        val notes: List<PostedNote>,
        val cursor: String,
        val hasMore: Boolean,
    ) : NoteListEvent

    data class Failed(
        override val kind: NoteListKind,
        override val userId: String?,
        val message: String,
        val risk: Boolean,
    ) : NoteListEvent
}

internal object NoteListParser {
    private const val HTTP_RISK = 461
    private const val CODE_RISK = 300011

    private val serializer = XhsResponse.serializer(PostedResult.serializer())

    fun parse(captured: CapturedResponse): NoteListEvent? {
        val kind = when (captured.path) {
            ApiPaths.USER_POSTED -> NoteListKind.POSTED
            ApiPaths.COLLECT_PAGE -> NoteListKind.COLLECTED
            ApiPaths.BOARD_NOTE -> NoteListKind.BOARD
            else -> return null
        }
        if (captured.status == 0) return null
        val userId = captured.queryParam(if (kind == NoteListKind.BOARD) "board_id" else "user_id")
        if (captured.status != 200) {
            return NoteListEvent.Failed(kind, userId, "HTTP ${captured.status}", captured.status == HTTP_RISK)
        }
        val response = runCatching { XhsHttp.json.decodeFromString(serializer, captured.body) }
            .getOrElse { return NoteListEvent.Failed(kind, userId, "无法解析笔记列表", risk = false) }
        val data = response.data
        if (response.code != 0 || data == null) {
            return NoteListEvent.Failed(kind, userId, response.msg ?: "code=${response.code}", response.code == CODE_RISK)
        }
        return NoteListEvent.Page(kind, userId, captured.queryParam("cursor").orEmpty(), data.notes, data.cursor, data.has_more)
    }

    /** 解析 [PageScripts.BOARD_SNAPSHOT] 的输出；空串或格式不对返回 null。 */
    fun parseBoardSnapshot(json: String): PostedResult? {
        if (json.isBlank()) return null
        return runCatching { XhsHttp.json.decodeFromString(PostedResult.serializer(), json) }.getOrNull()
    }

    /** 解析 [PageScripts.BOARD_LIST] 的输出；空串或格式不对返回 null。 */
    fun parseBoardList(json: String): BoardList? {
        if (json.isBlank()) return null
        return runCatching { XhsHttp.json.decodeFromString(BoardList.serializer(), json) }.getOrNull()
    }

    /** 解析 [PageScripts.PROFILE_SNAPSHOT] 的输出；空串或格式不对返回 null。 */
    fun parseSnapshot(json: String): ProfileSnapshot? {
        if (json.isBlank()) return null
        return runCatching { XhsHttp.json.decodeFromString(ProfileSnapshot.serializer(), json) }.getOrNull()
    }
}

/**
 * 合并主页 / 收藏的笔记：首屏（seed）在前，之后各页按到达顺序追加，按 note_id 去重；
 * 同一 cursor 的页重复到达时以后到的为准；hasMore 以最近一次为准。
 */
class NoteListAccumulator {
    private var seedNotes: List<PostedNote> = emptyList()
    private val pages = linkedMapOf<String, List<PostedNote>>()

    var items: List<PostedNote> = emptyList()
        private set

    var hasMore: Boolean = true
        private set

    fun seed(notes: List<PostedNote>, hasMore: Boolean): List<PostedNote> {
        seedNotes = notes
        if (pages.isEmpty()) this.hasMore = hasMore
        return rebuild()
    }

    fun add(page: NoteListEvent.Page): List<PostedNote> {
        pages[page.requestCursor] = page.notes
        hasMore = page.hasMore
        return rebuild()
    }

    private fun rebuild(): List<PostedNote> {
        items = (seedNotes + pages.values.flatten()).distinctBy { it.note_id }
        return items
    }
}
