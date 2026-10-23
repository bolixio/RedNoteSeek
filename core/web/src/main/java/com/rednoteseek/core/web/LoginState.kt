package com.rednoteseek.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface LoginState {
    /** 还没截获到 user/me（页面加载中或网络失败）。 */
    data object Unknown : LoginState
    data object Guest : LoginState
    data class LoggedIn(val userId: String, val nickname: String, val avatar: String = "") : LoginState
}

internal object LoginParsers {

    /** 解析 v2/user/me。游客同样持有 web_session，所以只能看 data.guest；没有该字段时返回 null。 */
    fun loginState(body: String): LoginState? {
        val data = dataObject(body) ?: return null
        val guest = data["guest"]?.jsonPrimitive?.booleanOrNull ?: return null
        return if (guest) LoginState.Guest
        else LoginState.LoggedIn(
            userId = data.string("user_id"),
            nickname = data.string("nickname"),
            avatar = XhsUrls.toHttps(data.string("images")),
        )
    }

    /** 解析 v1/login/qrcode/create，返回网页登录二维码的内容 URL。 */
    fun qrLoginUrl(body: String): String? = dataObject(body)?.string("url")?.takeIf { it.isNotEmpty() }

    private fun dataObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject["data"] as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
