package com.rednoteseek.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * evaluateJavascript 回调给的是 JSON 编码后的返回值：字符串带引号与转义，undefined / null 为 "null"。
 * 解码成脚本里 return 的原始字符串；非字符串值保留其 JSON 文本。
 */
internal fun decodeJsResult(raw: String?): String {
    if (raw == null || raw == "null") return ""
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: element.toString()
}
