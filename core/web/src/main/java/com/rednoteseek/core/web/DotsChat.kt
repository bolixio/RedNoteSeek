package com.rednoteseek.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 点点会话历史里的一个会话（保存在账号服务器上）。[group] 是列表里的分组标题，如「今天」。 */
data class DotsConversation(val id: String, val title: String, val updatedAt: Long, val group: String)

/** 会话里的一条消息（conversation/detail）。 */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage

    /**
     * [markdown] 为空且不是 [stopped] 表示这条是搜索页 instant 总结（网页不保存正文）；[sourceLabel] 如「参考76篇笔记」「ai总结48篇笔记生成」。
     * [stopped]：只有思考、没有回答的条目（中途停止生成），网页仍为它显示一条 `.ai-message`，保留它让回答序号与网页对齐。
     * [unsupported]：旅行攻略卡片等网页另行加载、App 暂不支持显示的回答（content_type 17），同样保留以对齐回答序号。
     */
    data class Answer(val markdown: String, val sourceLabel: String?, val stopped: Boolean = false, val unsupported: Boolean = false) : ChatMessage
}

/** 从对话引擎截获的会话列表、会话历史、流式回答、删除结果与参考来源。 */
sealed interface ChatEvent {
    data class Conversations(val items: List<DotsConversation>, val hasMore: Boolean) : ChatEvent

    /** 会话的消息，时间正序；只有第一页（20 条）。 */
    data class History(val sessionId: String, val messages: List<ChatMessage>) : ChatEvent

    /** 思考 / 搜索进度，如「正在搜 …」。 */
    data class Thinking(val sessionId: String, val text: String) : ChatEvent

    /** 正文增量（markdown）。 */
    data class Delta(val sessionId: String, val text: String) : ChatEvent

    /** 参考笔记数，如「参考85篇笔记」。 */
    data class SourceCount(val sessionId: String, val label: String) : ChatEvent

    /** 回答结束；[cancelled] 表示用户停止了生成。 */
    data class Finished(val sessionId: String, val cancelled: Boolean) : ChatEvent

    data class Deleted(val sessionIds: List<String>, val ok: Boolean) : ChatEvent

    /** 参考来源抽屉里的笔记（对话回答的 complex/source，或搜索总结的 dqa/detail）。 */
    data class Sources(val notes: List<SourceNote>) : ChatEvent
}

internal object ChatParser {
    private const val AGENT = "diandian-agent"
    private const val STREAM_PREFIX = "agent.stream."
    private const val CONTENT_USER = 4
    private const val CONTENT_ELEMENT = 7
    private const val CONTENT_ANSWER = 8
    private const val CONTENT_THINKING = 9
    private val CONTENT_ANSWERS = setOf(CONTENT_ELEMENT, CONTENT_ANSWER)
    private val KNOWN_CONTENT_TYPES = setOf(6, CONTENT_ELEMENT, CONTENT_ANSWER, CONTENT_THINKING)
    private const val SOURCE_HEADER = "dots_source_header"

    /** 不是点点会话的接口，或请求被中断（status=0）时返回空列表。 */
    fun parse(captured: CapturedResponse): List<ChatEvent> {
        if (captured.status == 0) return emptyList()
        return when (captured.path) {
            ApiPaths.DOTS_LIST -> listOfNotNull(conversations(captured))
            ApiPaths.DOTS_DETAIL -> listOfNotNull(history(captured))
            ApiPaths.DOTS_DELETE -> listOfNotNull(deleted(captured))
            ApiPaths.DOTS_SOURCES -> listOfNotNull(complexSources(captured))
            ApiPaths.DQA_DETAIL ->
                if (captured.status == 200) listOfNotNull(parseDqaDetailNotes(captured.body)?.let { ChatEvent.Sources(it) }) else emptyList()
            ApiPaths.PUSH_FRAME -> decodePushFrame(captured.body).flatMap(::stream)
            else -> emptyList()
        }
    }

    /** `data.items` 按顺序：分组标题（search_agent_conversation_time）之后的会话都属于这个分组。 */
    private fun conversations(c: CapturedResponse): ChatEvent.Conversations? {
        if (c.status != 200) return null
        val data = obj(c.body)?.obj("data") ?: return null
        var group = ""
        val items = data.objects("items").mapNotNull { item ->
            val spark = item.obj("spark") ?: return@mapNotNull null
            val dsl = spark.obj("dslData")
            when (spark.str("templateName")) {
                "search_agent_conversation_time" -> {
                    group = dsl?.str("title").orEmpty()
                    null
                }
                "search_agent_conversation_item" -> {
                    val id = dsl?.str("sessionId") ?: item.str("id") ?: return@mapNotNull null
                    DotsConversation(id, dsl?.str("question").orEmpty(), item.long("updateTimestamp") ?: 0L, group)
                }
                else -> null
            }
        }
        return ChatEvent.Conversations(items, data.bool("hasMore") == true)
    }

    /**
     * 消息新的在前：反转成时间正序；点点的相邻且 msg_id 相同的条目（思考 + 回答）合并成一条回答，
     * 只有思考的（中途停止）记为 stopped 的空回答。
     */
    private fun history(c: CapturedResponse): ChatEvent.History? {
        if (c.status != 200) return null
        val session = c.queryParam("session_id") ?: return null
        val data = obj(c.body)?.obj("data") ?: return null
        val messages = data.objects("messages").reversed()
        val out = mutableListOf<ChatMessage>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.str("sender_id") != AGENT) {
                userText(payloads(m))?.let { out += ChatMessage.User(it) }
                i++
                continue
            }
            val id = m.str("msg_id")
            val parts = mutableListOf<JsonObject>()
            while (i < messages.size && messages[i].str("sender_id") == AGENT && messages[i].str("msg_id") == id) {
                parts += payloads(messages[i])
                i++
            }
            answer(parts)?.let { out += it }
        }
        return ChatEvent.History(session, out)
    }

    private fun payloads(m: JsonObject): List<JsonObject> = m.obj("msg_info")?.objects("payloads").orEmpty()

    private fun userText(payloads: List<JsonObject>): String? =
        payloads.firstOrNull { it.int("content_type") == CONTENT_USER }
            ?.objects("inputs")?.firstOrNull()?.obj("info")?.str("text")

    /**
     * 回答的 markdown 在 content_type 8 的 elements 或 content_type 7 的单个 element 里：片段去掉首尾空白后用空行拼接，
     * dots_source_header 是参考标签（两种形式都认），商品卡片等未知 spark 不显示。只有思考（content_type 9）、没有回答内容的是中途停止的回答，
     * 记为 stopped 的空回答（网页同样为它显示一条 .ai-message）。没有 markdown 和标签、但有不认识的 ai_answer（如 content_type 17 的旅行攻略卡片，
     * 网页另行加载）记为 unsupported；没有 ai_answer / ai_thinking 返回 null。
     */
    private fun answer(payloads: List<JsonObject>): ChatMessage.Answer? {
        val answers = payloads.filter { it.str("message_type") == "ai_answer" || it.int("content_type") in CONTENT_ANSWERS }
        val thinking = payloads.any { it.str("message_type") == "ai_thinking" || it.int("content_type") == CONTENT_THINKING }
        if (answers.isEmpty()) return if (thinking) ChatMessage.Answer("", null, stopped = true) else null
        val elements = answers.flatMap { p ->
            if (p.int("content_type") == CONTENT_ELEMENT) listOfNotNull(p.obj("element")) else p.objects("elements")
        }
        val markdown = elements.filter { it.str("element_type") == "markdown" }
            .mapNotNull { it.obj("markdown")?.str("text")?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val label = elements.firstNotNullOfOrNull(::sourceLabel)
        val unsupported = markdown.isEmpty() && label == null &&
            answers.any { it.int("content_type") !in KNOWN_CONTENT_TYPES }
        return ChatMessage.Answer(markdown, label, unsupported = unsupported)
    }

    private fun sourceLabel(element: JsonObject): String? {
        val spark = element.obj("spark") ?: return null
        if (spark.str("template_name") != SOURCE_HEADER) return null
        return spark.obj("data")?.str("sub_title")
    }

    /** 推送帧：只认 `agent.stream.*`，按 `data.session_id` 归属；第一期的 davinci 帧没有这个前缀。 */
    private fun stream(m: JsonObject): List<ChatEvent> {
        val type = m.str("type") ?: return emptyList()
        if (!type.startsWith(STREAM_PREFIX)) return emptyList()
        val data = m.obj("data") ?: return emptyList()
        val session = data.str("session_id") ?: return emptyList()
        if (type == "agent.stream.end") return listOf(ChatEvent.Finished(session, data.str("finish_reason") == "cancel"))
        val payload = data.obj("payload") ?: return emptyList()
        val event: ChatEvent? = when (payload.int("content_type")) {
            CONTENT_THINKING -> payload.obj("thinking")
                ?.let { t -> t.str("content")?.takeIf { it.isNotBlank() } ?: t.str("action") }
                ?.takeIf { it.isNotBlank() }
                ?.let { ChatEvent.Thinking(session, it) }
            CONTENT_ELEMENT -> payload.obj("element")?.let { e ->
                if (e.str("element_type") == "markdown") {
                    e.obj("markdown")?.str("text")?.let { ChatEvent.Delta(session, it) }
                } else {
                    sourceLabel(e)?.let { ChatEvent.SourceCount(session, it) }
                }
            }
            else -> null
        }
        return listOfNotNull(event)
    }

    /** 请求体 `{"biz":"xhs_dots_pc","sessionIds":[...]}`；成功看 `success` 且 `code` 为 0。 */
    private fun deleted(c: CapturedResponse): ChatEvent.Deleted? {
        val ids = obj(c.request)?.get("sessionIds") as? JsonArray ?: return null
        return ChatEvent.Deleted(ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, WriteReply.ok(c))
    }

    private fun complexSources(c: CapturedResponse): ChatEvent.Sources? {
        if (c.status != 200) return null
        val info = obj(c.body)?.obj("data")?.obj("info") ?: return null
        val notes = info.objects("sources").mapNotNull { s ->
            val note = s.obj("note_info")
            val id = s.str("id") ?: note?.str("id") ?: return@mapNotNull null
            val cover = note?.objects("image_list")?.firstOrNull()
            val user = s.obj("user")
            SourceNote(
                id = id,
                title = note?.str("title").orEmpty(),
                coverUrl = cover?.str("url").orEmpty().let(XhsUrls::toHttps),
                coverWidth = cover?.int("width") ?: 0,
                coverHeight = cover?.int("height") ?: 0,
                author = user?.str("nick_name").orEmpty(),
                avatar = user?.str("avatar").orEmpty().let(XhsUrls::toHttps),
                likes = s.obj("interact_info")?.int("like_count") ?: 0,
                isVideo = s.str("type") == "video",
            )
        }
        return ChatEvent.Sources(notes)
    }

    private fun obj(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/** [PageScripts.ASK_TAB_STATE] 的结果：当前路径（去掉末尾 `/`）与 URL 里的 conversationId（没有为空串）。 */
internal data class AskTabState(val path: String, val conversationId: String) {
    companion object {
        fun parse(json: String): AskTabState? {
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return null
            return AskTabState(path.trimEnd('/'), (o["conversationId"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }
}
