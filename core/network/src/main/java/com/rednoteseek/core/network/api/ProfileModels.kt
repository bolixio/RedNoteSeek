package com.rednoteseek.core.network.api

import kotlinx.serialization.Serializable

/** 主页 / 收藏 / 专辑列表里的扁平笔记条目（v1/user_posted、v2/note/collect/page、v1/board/note 共用；收藏无 time）。 */
@Serializable
data class PostedNote(
    val note_id: String = "",
    val display_title: String = "",
    val type: String = "normal",                  // normal | video
    val xsec_token: String = "",
    val cover: NoteImage? = null,
    val user: NoteUser = NoteUser(),
    val interact_info: InteractInfo = InteractInfo(),
    val time: Long = 0,
)

@Serializable
data class PostedResult(
    val notes: List<PostedNote> = emptyList(),
    val cursor: String = "",
    val has_more: Boolean = false,
)

/** 用户主页资料（由页面 __INITIAL_STATE__ 转换而来）。计数为页面上的显示文本。 */
@Serializable
data class ProfileUser(
    val user_id: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val desc: String = "",
    val red_id: String = "",
    val follows: String = "0",
    val fans: String = "0",
    val interaction: String = "0",
)

/** 主页首屏：资料 + 服务端渲染的第一页笔记。 */
@Serializable
data class ProfileSnapshot(
    val user: ProfileUser = ProfileUser(),
    val notes: List<PostedNote> = emptyList(),
    val cursor: String = "",
    val has_more: Boolean = false,
)

/** 收藏里的一个专辑（由页面 __INITIAL_STATE__.board.userBoardList 转换而来）。封面为 http 地址。 */
@Serializable
data class Board(
    val id: String = "",
    val name: String = "",
    val total: Int = 0,
    val images: List<String> = emptyList(),
    /** 0 公开，其余为私密。 */
    val privacy: Int = 0,
)

@Serializable
data class BoardList(val boards: List<Board> = emptyList())
