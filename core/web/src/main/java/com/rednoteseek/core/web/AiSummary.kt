package com.rednoteseek.core.web

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 搜索模式：普通结果页 `/search_result`，或带点点 AI 总结的 `/search_result_ai`。 */
enum class SearchMode { NOTES, AI }

/** 点点 AI 总结的一篇参考笔记（v1/search/dqa/detail 的 items）。 */
data class SourceNote(
    val id: String,
    val title: String,
    val coverUrl: String,
    val coverWidth: Int,
    val coverHeight: Int,
    val author: String,
    val avatar: String,
    val likes: Int,
    val isVideo: Boolean,
)

/** 从截获的点点 AI 请求与推送帧得到的事件。 */
sealed interface AiSummaryEvent {
    /** 热门词：dqa/instant 直接给出完整总结。[sourceLabel] 如「ai总结113篇笔记生成」。 */
    data class Instant(val keyword: String?, val markdown: String, val sourceLabel: String?) : AiSummaryEvent

    /** 冷门词：网页发起实时生成；之后同一 [conversationId] 的流式事件都属于这个关键词。 */
    data class Generating(val keyword: String, val conversationId: String) : AiSummaryEvent

    /** 生成进度，如「搜索相关内容」。 */
    data class Progress(val conversationId: String, val text: String) : AiSummaryEvent

    /** 参考笔记数，如「ai总结58篇笔记生成」。 */
    data class SourceCount(val conversationId: String, val text: String) : AiSummaryEvent

    /** 正文增量（markdown）。 */
    data class Delta(val conversationId: String, val text: String) : AiSummaryEvent

    data class Done(val conversationId: String) : AiSummaryEvent

    /** 参考来源抽屉里的笔记。网页在同一页面里第二次打开抽屉时走缓存，不会再请求。 */
    data class Sources(val keyword: String?, val notes: List<SourceNote>) : AiSummaryEvent
}

internal object SummaryParser {
    /** 实时生成总结的业务域；推送 WebSocket 上还有私信、点点对话页等其他帧。 */
    private const val DOMAIN = "dqa_chatsearch"
    private const val FRAGMENT_TEXT = 1
    private const val FRAGMENT_PROGRESS = 9
    private const val FRAGMENT_SOURCE_COUNT = 10
    private const val PROGRESS_RUNNING = 1

    /** 不是点点 AI 的接口，或请求被中断（status=0）时返回空列表。 */
    fun parse(captured: CapturedResponse): List<AiSummaryEvent> {
        if (captured.status == 0) return emptyList()
        return when (captured.path) {
            ApiPaths.DQA_INSTANT -> listOfNotNull(instant(captured))
            ApiPaths.SEND_AI -> listOfNotNull(generating(captured))
            ApiPaths.PUSH_FRAME -> decodePushFrame(captured.body).flatMap(::message)
            ApiPaths.DQA_DETAIL -> listOfNotNull(sources(captured))
            else -> emptyList()
        }
    }

    private fun instant(c: CapturedResponse): AiSummaryEvent.Instant? {
        if (c.status != 200) return null
        val info = obj(c.body)?.obj("simple_data")?.obj("base_info") ?: return null
        val text = info.str("text")?.takeIf { it.isNotBlank() } ?: return null
        val keyword = obj(c.request)?.obj("search_request")?.str("keyword")
        return AiSummaryEvent.Instant(keyword, text, info.str("totalCnt"))
    }

    private fun generating(c: CapturedResponse): AiSummaryEvent.Generating? {
        if (c.status != 200) return null
        val request = obj(c.request) ?: return null
        val conversation = request.str("appCid") ?: return null
        val keyword = request.obj("contentInfo")?.obj("content")?.str("summary") ?: return null
        return AiSummaryEvent.Generating(keyword, conversation)
    }

    private fun message(m: JsonObject): List<AiSummaryEvent> {
        val data = m.obj("data") ?: return emptyList()
        if (data.str("domain_id") != DOMAIN) return emptyList()
        val conversation = data.str("conversation_id") ?: return emptyList()
        return when (m.str("type")) {
            "signal" -> listOf(AiSummaryEvent.Done(conversation))
            "message" -> (data["data_fragments"] as? JsonArray).orEmpty().mapNotNull { f ->
                val fragment = f as? JsonObject ?: return@mapNotNull null
                when ((fragment["fragment_type"] as? JsonPrimitive)?.intOrNull) {
                    FRAGMENT_TEXT -> fragment.str("text")?.let { AiSummaryEvent.Delta(conversation, it) }
                    FRAGMENT_PROGRESS -> (fragment["thinking_progress_item"] as? JsonArray).orEmpty()
                        .mapNotNull { it as? JsonObject }
                        .firstOrNull { (it["progress_status"] as? JsonPrimitive)?.intOrNull == PROGRESS_RUNNING }
                        ?.str("progress_text")?.let { AiSummaryEvent.Progress(conversation, it) }
                    FRAGMENT_SOURCE_COUNT -> fragment.obj("query_status")?.str("text")?.let { AiSummaryEvent.SourceCount(conversation, it) }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun sources(c: CapturedResponse): AiSummaryEvent.Sources? {
        if (c.status != 200) return null
        val notes = parseDqaDetailNotes(c.body) ?: return null
        return AiSummaryEvent.Sources(c.queryParam("query"), notes)
    }

    private fun obj(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * 推送 WebSocket 帧 `{"v":1,"t":4,"b":{"d":{"b":[{"d":"<base64 JSON>"}]}}}` 解码为其中的 `{type, data}` 列表；
 * 格式不对或解码失败的项跳过。点点总结（[SummaryParser]）与点点对话（[ChatParser]）共用。
 */
internal fun decodePushFrame(body: String): List<JsonObject> {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val items = ((root["b"] as? JsonObject)?.get("d") as? JsonObject)?.get("b") as? JsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val encoded = ((item as? JsonObject)?.get("d") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        runCatching {
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            Json.parseToJsonElement(String(Base64.getDecoder().decode(padded), Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }
}

/**
 * `v1/search/dqa/detail` 的参考来源笔记（`data.items`）；不是该结构返回 null。
 * 搜索页总结的来源与对话页里搜索总结那条回答的来源共用。
 */
internal fun parseDqaDetailNotes(body: String): List<SourceNote>? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val items = (root["data"] as? JsonObject)?.get("items") as? JsonArray ?: return null
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.number(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    return items.mapNotNull { it as? JsonObject }.mapNotNull { item ->
        val id = item.text("id") ?: return@mapNotNull null
        val cover = (item["image_list"] as? JsonArray)?.firstOrNull() as? JsonObject
        SourceNote(
            id = id,
            title = item.text("title").orEmpty(),
            coverUrl = cover?.text("url").orEmpty().let(XhsUrls::toHttps),
            coverWidth = cover?.number("width") ?: 0,
            coverHeight = cover?.number("height") ?: 0,
            author = item.text("nick_name").orEmpty(),
            avatar = item.text("avatar").orEmpty().let(XhsUrls::toHttps),
            likes = item.number("like"),
            isVideo = item.text("type") == "video",
        )
    }
}
