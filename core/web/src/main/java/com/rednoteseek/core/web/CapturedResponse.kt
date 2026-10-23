package com.rednoteseek.core.web

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 页面钩子（[PageScripts.HOOK]）截获的一次 /api/sns/web/ 请求（另有消息模块的关注名单、推送 WebSocket 的帧）。
 *
 * @property path 去掉 `/api/sns/web/` 前缀和 query 的接口路径，如 `v2/search/notes`；
 *   其他接口只去掉 `/api/`，如 `im/web/users/following/all`；推送 WebSocket 的业务帧为 `ws/push`
 * @property request 请求体原文（GET 或非字符串请求体时为空串）
 * @property status HTTP 状态码；请求被中断（如页面跳走）时为 0
 * @property body 响应体原文
 * @property query 原始 query 字符串
 */
data class CapturedResponse(
    val path: String,
    val request: String,
    val status: Int,
    val body: String,
    /** URL 中 `?` 之后、`#` 之前的原始 query（未解码）。 */
    val query: String = "",
) {
    /** 取 query 参数并做 URL 解码；没有该参数时返回 null。 */
    fun queryParam(name: String): String? = query.split('&').firstNotNullOfOrNull { pair ->
        if (pair.substringBefore('=') == name) {
            val raw = pair.substringAfter('=', "")
            // 非法 % 转义会抛异常，退回未解码的原始值
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else null
    }

    companion object {
        private const val API_PREFIX = "/api/sns/web/"
        private const val OTHER_API_PREFIX = "/api/"
        private const val PUSH_SOCKET = "wss://apppush-rws.xiaohongshu.com"

        /** 解析钩子经 WebMessageListener 发来的消息；格式不对或不是 xhs 接口时返回 null。 */
        fun parse(message: String): CapturedResponse? {
            val obj = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
            return CapturedResponse(
                // 推送 WebSocket（点点 AI 的流式回答）没有 /api/ 路径，单独归为 PUSH_FRAME
                path = if (url.startsWith(PUSH_SOCKET)) ApiPaths.PUSH_FRAME else apiPath(url) ?: return null,
                request = obj["request"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                status = obj["status"]?.jsonPrimitive?.intOrNull ?: 0,
                body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                query = url.substringAfter('?', "").substringBefore('#'),
            )
        }

        /**
         * `https://edith.xiaohongshu.com/api/sns/web/v2/search/notes?x=1` → `v2/search/notes`；
         * `https://edith.xiaohongshu.com/api/im/web/users/following/all?page=1` → `im/web/users/following/all`
         */
        fun apiPath(url: String): String? {
            val start = url.indexOf(API_PREFIX)
            val rest = when {
                start >= 0 -> url.substring(start + API_PREFIX.length)
                url.contains(OTHER_API_PREFIX) -> url.substringAfter(OTHER_API_PREFIX)
                else -> return null
            }
            return rest.substringBefore('?').substringBefore('#')
        }
    }
}
