package com.rednoteseek.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 账号关注名单里的一位用户。 */
data class FollowingUser(val userId: String, val nickname: String, val avatar: String)

/** 读取关注者未看笔记数的结果。 */
sealed interface UnreadResult {
    /** 作者 ID → 未看笔记数；只含关注列表弹窗首屏里的作者。 */
    data class Counts(val unread: Map<String, Int>) : UnreadResult
    data class Failed(val risk: Boolean) : UnreadResult
}

internal object FollowParsers {
    private const val HTTP_RISK = 461
    private const val CODE_RISK = 300011

    /**
     * 解析消息模块的 `im/web/users/following/all` 一页：`data.follow_user_d_t_o_list[]`
     * （`user_id`、`nick_name`、`avatar`；`status` 1 互相关注 / 2 单向关注，这里不区分）。
     * 请求失败或 code 不为 0 返回 null，避免把出错当成「没有关注」清空名单。
     */
    fun followingPage(captured: CapturedResponse): List<FollowingUser>? {
        if (captured.status != 200) return null
        val root = parseObject(captured.body) ?: return null
        if (root["code"]?.jsonPrimitive?.intOrNull != 0) return null
        val list = (root["data"] as? JsonObject)?.get("follow_user_d_t_o_list") as? JsonArray ?: return null
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.string("user_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FollowingUser(id, obj.string("nick_name"), XhsUrls.toHttps(obj.string("avatar")))
        }
    }

    /**
     * 解析 `v1/user/followings/extra`：`data.follow_extra_list[]`（`user_id`、`unread_note_count`，
     * 有未看时另带 `desc`「N篇笔记未看」）。
     */
    fun unread(captured: CapturedResponse): UnreadResult {
        if (captured.status != 200) return UnreadResult.Failed(risk = captured.status == HTTP_RISK)
        val root = parseObject(captured.body) ?: return UnreadResult.Failed(risk = false)
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code != 0) return UnreadResult.Failed(risk = code == CODE_RISK)
        val list = (root["data"] as? JsonObject)?.get("follow_extra_list") as? JsonArray
            ?: return UnreadResult.Failed(risk = false)
        return UnreadResult.Counts(
            list.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj.string("user_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to (obj["unread_note_count"]?.jsonPrimitive?.intOrNull ?: 0)
            }.toMap(),
        )
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

/**
 * 按页拼出完整的关注名单：网页每页请求 `size` 条（200），不满一页说明是最后一页。
 * 第 1 页重新开始；页码不连续或某页解析失败时丢弃这一轮。
 */
internal class FollowingAccumulator {
    private val users = mutableListOf<FollowingUser>()
    private var nextPage = 1

    /** 收到一页；名单完整时返回全部用户，否则返回 null。 */
    fun add(captured: CapturedResponse): List<FollowingUser>? {
        val page = captured.queryParam("page")?.toIntOrNull() ?: 1
        val size = captured.queryParam("size")?.toIntOrNull() ?: DEFAULT_SIZE
        if (page == 1) {
            users.clear()
            nextPage = 1
        }
        if (page != nextPage) return null
        val pageUsers = FollowParsers.followingPage(captured)
        if (pageUsers == null) {
            nextPage = 0
            return null
        }
        users += pageUsers
        nextPage = page + 1
        return if (pageUsers.size < size) users.distinctBy { it.userId } else null
    }

    private companion object {
        const val DEFAULT_SIZE = 200
    }
}
