package com.rednoteseek.core.web

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.XhsResponse
import com.rednoteseek.core.network.api.Board
import com.rednoteseek.core.network.api.BoardList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 新建 / 修改专辑的表单。名称不超过 [NAME_MAX] 个字（网页限制）。 */
data class BoardForm(val name: String, val desc: String, val public: Boolean) {
    companion object {
        const val NAME_MAX = 12

        /** 简介为空时网页显示（编辑表单里也会填上）的占位文字，读出时视为空。 */
        private const val EMPTY_DESC = "暂无简介"

        /** 解析 [PageScripts.BOARD_DETAILS] 的输出，返回（专辑 ID, 表单初值）。 */
        internal fun parseDetails(json: String): Pair<String, BoardForm>? {
            if (json.isBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val desc = obj["desc"]?.jsonPrimitive?.contentOrNull.orEmpty().takeIf { it != EMPTY_DESC }.orEmpty()
            return id to BoardForm(
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                desc = desc,
                public = (obj["privacy"]?.jsonPrimitive?.intOrNull ?: 0) == 0,
            )
        }
    }
}

/** 收藏笔记的结果。 */
sealed interface CollectOutcome {
    data object Failed : CollectOutcome

    /**
     * 已收藏。[boards] 是网页「加入专辑」面板列出的专辑（按面板顺序，供 [NoteSource.moveToBoard] 定位）；
     * 「加入专辑」提示没出现或面板没加载出来时为 null。
     */
    data class Collected(val boards: List<Board>?) : CollectOutcome
}

/** 写操作（关注、收藏、专辑）的响应解析。 */
internal object WriteReply {
    private val boardPage = XhsResponse.serializer(BoardList.serializer())

    /**
     * 是否成功：HTTP 200、`success` 为 true，且 `code` 或 `result` 为 0
     * （专辑的增删改用 `result`，其余用 `code`）。
     */
    fun ok(captured: CapturedResponse): Boolean {
        val root = root(captured) ?: return false
        val code = (root["code"] ?: root["result"])?.jsonPrimitive?.intOrNull ?: 0
        return root["success"]?.jsonPrimitive?.booleanOrNull == true && code == 0
    }

    /** `POST v1/board` 成功时返回新专辑的 ID（`data.id`）。 */
    fun createdBoardId(captured: CapturedResponse): String? {
        if (!ok(captured)) return null
        val data = root(captured)?.get("data") as? JsonObject ?: return null
        return data["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** 「加入专辑」面板请求的一页专辑（`v1/board/user`，与收藏页的专辑列表同结构）。 */
    fun boardPage(captured: CapturedResponse): List<Board>? {
        if (captured.status != 200) return null
        val response = runCatching { XhsHttp.json.decodeFromString(boardPage, captured.body) }.getOrNull() ?: return null
        if (response.code != 0) return null
        return response.data?.boards
    }

    private fun root(captured: CapturedResponse): JsonObject? {
        if (captured.status != 200) return null
        return runCatching { Json.parseToJsonElement(captured.body).jsonObject }.getOrNull()
    }
}
