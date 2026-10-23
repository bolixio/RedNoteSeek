package com.rednoteseek.core.web

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.XhsResponse
import com.rednoteseek.core.network.api.SearchItem
import com.rednoteseek.core.network.api.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 从截获的 v2/search/notes 响应得到的事件。 */
sealed interface SearchEvent {
    /** 请求体里的关键词；请求体无法解析时为 null，调用方应视为匹配。 */
    val keyword: String?

    /** 请求体里的排序标签；请求体无 filters 时为 general，请求体无法解析时为 null。 */
    val sort: String?

    data class Page(
        override val keyword: String?,
        /** 请求体里的页码；无法解析时为 null。 */
        val page: Int?,
        /** 只含笔记，已过滤掉 hot_query 等条目。 */
        val items: List<SearchItem>,
        val hasMore: Boolean,
        override val sort: String? = null,
    ) : SearchEvent

    data class Failed(
        override val keyword: String?,
        val message: String,
        override val sort: String? = null,
    ) : SearchEvent
}

internal object SearchParser {
    private val responseSerializer = XhsResponse.serializer(SearchResult.serializer())

    /** 不是搜索接口，或请求被中断（status=0，如页面跳走）时返回 null。 */
    fun parse(captured: CapturedResponse): SearchEvent? {
        if (captured.path != ApiPaths.SEARCH_NOTES || captured.status == 0) return null
        val request = runCatching { Json.parseToJsonElement(captured.request).jsonObject }.getOrNull()
        val keyword = request?.get("keyword")?.jsonPrimitive?.contentOrNull
        val page = request?.get("page")?.jsonPrimitive?.intOrNull
        // 排序在 filters 的 sort_type 里；没有 filters 即默认的「综合」
        val sort = request?.let { req ->
            req["filters"]?.let { filters ->
                runCatching {
                    filters.jsonArray.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "sort_type" }
                        ?.jsonObject?.get("tags")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            } ?: SearchSort.GENERAL.tag
        }

        if (captured.status != 200) return SearchEvent.Failed(keyword, "HTTP ${captured.status}", sort)
        val response = runCatching { XhsHttp.json.decodeFromString(responseSerializer, captured.body) }
            .getOrElse { return SearchEvent.Failed(keyword, "无法解析搜索结果", sort) }
        val data = response.data
        if (response.code != 0 || data == null) {
            return SearchEvent.Failed(keyword, response.msg ?: "code=${response.code}", sort)
        }
        return SearchEvent.Page(keyword, page, data.items.filter { it.model_type == "note" }, data.has_more, sort)
    }
}

/**
 * 合并同一关键词的多页搜索结果：按请求页码排序，按笔记 id 去重。
 * 页面滚动时可能连续预取两页并乱序到达；同一页重复到达时以后到的为准。
 */
class SearchAccumulator {
    private val pages = sortedMapOf<Int, List<SearchItem>>()

    var hasMore: Boolean = true
        private set

    fun add(event: SearchEvent.Page): List<SearchItem> {
        val key = event.page ?: ((pages.keys.maxOrNull() ?: 0) + 1)
        pages[key] = event.items
        if (key == pages.lastKey()) hasMore = event.hasMore
        return pages.values.flatten().distinctBy { it.id }
    }
}
