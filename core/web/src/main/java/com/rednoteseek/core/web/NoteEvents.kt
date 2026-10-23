package com.rednoteseek.core.web

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.XhsResponse
import com.rednoteseek.core.network.api.CommentResult
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.NoteDetailResult
import com.rednoteseek.core.network.api.XhsComment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 从截获的 v1/feed 与 v2/comment/page 响应得到的事件。 */
sealed interface NoteEvent {
    /** 事件所属笔记；无法判断时为 null，调用方视为当前打开的笔记。 */
    val noteId: String?

    data class Detail(override val noteId: String, val card: NoteCard) : NoteEvent

    data class Comments(
        override val noteId: String?,
        /** 这次请求带的 cursor，第一页为空串。 */
        val requestCursor: String,
        val comments: List<XhsComment>,
        /** 下一页的 cursor。 */
        val cursor: String,
        val hasMore: Boolean,
    ) : NoteEvent

    /** 楼中楼一页回复（点击「展开回复」后网页请求的 v2/comment/sub/page）。 */
    data class SubComments(
        override val noteId: String?,
        val rootCommentId: String,
        val requestCursor: String,
        val comments: List<XhsComment>,
        val cursor: String,
        val hasMore: Boolean,
    ) : NoteEvent

    /**
     * risk = 风控信号（HTTP 461 或 code 300011），调用方应立即停止、不再重试。
     * fromFeed：失败来自 v1/feed（笔记内容），评论与回复失败为 false。
     */
    data class Failed(
        override val noteId: String?,
        val message: String,
        val risk: Boolean,
        val fromFeed: Boolean = false,
    ) : NoteEvent
}

internal object NoteParser {
    private const val HTTP_RISK = 461
    private const val CODE_RISK = 300011

    private val feedSerializer = XhsResponse.serializer(NoteDetailResult.serializer())
    private val commentSerializer = XhsResponse.serializer(CommentResult.serializer())

    /** 只处理 v1/feed、v2/comment/page 与 v2/comment/sub/page；请求被中断（status=0，如页面跳走）时返回 null。 */
    fun parse(captured: CapturedResponse): NoteEvent? {
        if (captured.status == 0) return null
        return when (captured.path) {
            ApiPaths.FEED -> parseFeed(captured)
            ApiPaths.COMMENT_PAGE -> parseComments(captured)
            ApiPaths.COMMENT_SUB_PAGE -> parseSubComments(captured)
            else -> null
        }
    }

    /** 解析 [PageScripts.readCachedComments] 的结果；空串或解析失败返回 null。 */
    fun parseCachedComments(noteId: String, json: String): NoteEvent.Comments? {
        if (json.isBlank()) return null
        val data = runCatching { XhsHttp.json.decodeFromString(CommentResult.serializer(), json) }.getOrNull()
            ?: return null
        return NoteEvent.Comments(noteId, "", data.comments, data.cursor, data.has_more)
    }

    private fun parseFeed(captured: CapturedResponse): NoteEvent {
        // feed 是 POST，笔记 ID 在请求体的 source_note_id 里
        val requestNoteId = runCatching {
            Json.parseToJsonElement(captured.request).jsonObject["source_note_id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (captured.status != 200) {
            return NoteEvent.Failed(requestNoteId, "HTTP ${captured.status}", captured.status == HTTP_RISK, fromFeed = true)
        }
        val response = runCatching { XhsHttp.json.decodeFromString(feedSerializer, captured.body) }
            .getOrElse { return NoteEvent.Failed(requestNoteId, "无法解析笔记详情", risk = false, fromFeed = true) }
        if (response.code != 0) {
            return NoteEvent.Failed(requestNoteId, response.msg ?: "code=${response.code}", response.code == CODE_RISK, fromFeed = true)
        }
        val item = response.data?.items?.firstOrNull()
            ?: return NoteEvent.Failed(requestNoteId, "笔记不存在或已删除", risk = false, fromFeed = true)
        return NoteEvent.Detail(item.id, item.note_card)
    }

    private fun parseComments(captured: CapturedResponse): NoteEvent {
        // comment/page 是 GET，笔记 ID 与 cursor 在 query 里
        val noteId = captured.queryParam("note_id")
        if (captured.status != 200) {
            return NoteEvent.Failed(noteId, "HTTP ${captured.status}", captured.status == HTTP_RISK)
        }
        val response = runCatching { XhsHttp.json.decodeFromString(commentSerializer, captured.body) }
            .getOrElse { return NoteEvent.Failed(noteId, "无法解析评论", risk = false) }
        val data = response.data
        if (response.code != 0 || data == null) {
            return NoteEvent.Failed(noteId, response.msg ?: "code=${response.code}", response.code == CODE_RISK)
        }
        return NoteEvent.Comments(noteId, captured.queryParam("cursor").orEmpty(), data.comments, data.cursor, data.has_more)
    }

    private fun parseSubComments(captured: CapturedResponse): NoteEvent? {
        val noteId = captured.queryParam("note_id")
        val rootId = captured.queryParam("root_comment_id") ?: return null
        if (captured.status != 200) {
            return NoteEvent.Failed(noteId, "HTTP ${captured.status}", captured.status == HTTP_RISK)
        }
        val response = runCatching { XhsHttp.json.decodeFromString(commentSerializer, captured.body) }
            .getOrElse { return NoteEvent.Failed(noteId, "无法解析回复", risk = false) }
        val data = response.data
        if (response.code != 0 || data == null) {
            return NoteEvent.Failed(noteId, response.msg ?: "code=${response.code}", response.code == CODE_RISK)
        }
        return NoteEvent.SubComments(noteId, rootId, captured.queryParam("cursor").orEmpty(), data.comments, data.cursor, data.has_more)
    }
}

/**
 * 合并同一笔记的评论页：按到达顺序追加、按评论 id 去重。
 * 打开笔记时页面可能重复请求第一页，重复的第一页不改变 [hasMore]。
 */
class CommentAccumulator {
    var items: List<XhsComment> = emptyList()
        private set

    var hasMore: Boolean = false
        private set

    fun add(event: NoteEvent.Comments): List<XhsComment> {
        val repeatedFirstPage = event.requestCursor.isEmpty() && items.isNotEmpty()
        items = (items + event.comments).distinctBy { it.id }
        if (!repeatedFirstPage) hasMore = event.hasMore
        return items
    }

    /** 把一页楼中楼回复合并到对应根评论：按回复 id 去重，hasMore 以本页为准；找不到根评论时忽略。 */
    fun addReplies(event: NoteEvent.SubComments): List<XhsComment> {
        items = items.map { root ->
            if (root.id != event.rootCommentId) root
            else root.copy(
                subComments = (root.subComments + event.comments).distinctBy { it.id },
                subCommentHasMore = event.hasMore,
            )
        }
        return items
    }
}
