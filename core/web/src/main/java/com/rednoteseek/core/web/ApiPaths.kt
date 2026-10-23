package com.rednoteseek.core.web

/** 用到的 xhs Web 接口路径，格式同 [CapturedResponse.path]。 */
internal object ApiPaths {
    const val USER_ME = "v2/user/me"
    const val QRCODE_CREATE = "v1/login/qrcode/create"
    const val SEARCH_NOTES = "v2/search/notes"
    const val FEED = "v1/feed"
    const val COMMENT_PAGE = "v2/comment/page"
    const val COMMENT_SUB_PAGE = "v2/comment/sub/page"
    const val USER_POSTED = "v1/user_posted"
    const val COLLECT_PAGE = "v2/note/collect/page"
    const val BOARD_NOTE = "v1/board/note"
    /** 专辑的新建（POST）、修改（PUT）、删除（DELETE）共用此路径。 */
    const val BOARD = "v1/board"
    const val BOARD_USER = "v1/board/user"
    const val NOTE_COLLECT = "v1/note/collect"
    const val NOTE_UNCOLLECT = "v1/note/uncollect"
    /** 「加入专辑」：把已收藏的笔记放进专辑（`target_board_id`、`notes_id`）。 */
    const val NOTE_MOVE = "v1/note/move"
    const val USER_FOLLOW = "v1/user/follow"
    const val USER_UNFOLLOW = "v1/user/unfollow"
    /** 关注列表弹窗里每位关注者的未看笔记数。 */
    const val FOLLOWINGS_EXTRA = "v1/user/followings/extra"
    /** 消息模块每次页面加载都会预取的关注名单（不在 /api/sns/web/ 下）。 */
    const val FOLLOWING_ALL = "im/web/users/following/all"
    /** 点点 AI 搜索：热门词直接返回总结；冷门词返回空，随后网页发 [SEND_AI] 实时生成。 */
    const val DQA_INSTANT = "v1/dqa/instant"
    /** 冷门词实时生成总结的请求；请求体里的 appCid 就是流式帧的 conversation_id。 */
    const val SEND_AI = "search/send/ai"
    /** AI 总结的参考来源笔记（点「ai总结N篇笔记生成」时网页请求）。 */
    const val DQA_DETAIL = "v1/search/dqa/detail"
    /** 推送 WebSocket 收到的帧，不是 HTTP 接口，见 [CapturedResponse.parse]。 */
    const val PUSH_FRAME = "ws/push"

    /** 点点会话历史列表（不在 /api/sns/web/ 下）：`data.items` 里分组标题与会话交替。 */
    const val DOTS_LIST = "sns/agentspark/web/conversation/list"
    /** 一个会话的消息（query 参数 `session_id`），新的在前。 */
    const val DOTS_DETAIL = "sns/agentspark/web/conversation/detail"
    /** 删除会话（请求体 `sessionIds`），点网页侧边栏条目的 × 时发出，没有确认框。 */
    const val DOTS_DELETE = "sns/agentspark/web/conversation/delete"
    /** 对话回答的参考来源（点「搜索并总结N人真实经验」时网页请求）。 */
    const val DOTS_SOURCES = "sns/agentspark/web/v1/dqa/complex/source"
    /** 在 AI 结果页点「问点点」后，网页把当前总结挂进新会话的请求。 */
    const val SEARCH_SIGNAL = "search/signal"
}
