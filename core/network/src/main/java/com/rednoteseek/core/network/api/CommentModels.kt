package com.rednoteseek.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 评论列表（网页 comment/page、comment/sub/page 响应的 data）。 */
@Serializable
data class CommentResult(
    val has_more: Boolean = false,
    val cursor: String = "",
    val comments: List<XhsComment> = emptyList(),
)

@Serializable
data class XhsComment(
    val id: String = "",
    val note_id: String = "",
    @SerialName("content") val content: String = "",
    @SerialName("user_info") val userInfo: CommentUser = CommentUser(),
    @SerialName("like_count") val likeCount: String = "0",
    @SerialName("liked") val liked: Boolean = false,
    @SerialName("create_time") val createTime: Long = 0,
    @SerialName("sub_comment_count") val subCommentCount: String = "0",
    @SerialName("sub_comments") val subComments: List<XhsComment> = emptyList(),
    @SerialName("sub_comment_has_more") val subCommentHasMore: Boolean = false,
    @SerialName("at_users") val atUsers: List<CommentUser> = emptyList(),
    @SerialName("ip_location") val ipLocation: String = "",
    /** 楼中楼回复的那条评论；回复根评论作者时也有。 */
    @SerialName("target_comment") val targetComment: TargetComment? = null,
)

@Serializable
data class TargetComment(
    val id: String = "",
    @SerialName("user_info") val userInfo: CommentUser = CommentUser(),
)

@Serializable
data class CommentUser(
    @SerialName("user_id") val userId: String = "",
    @SerialName("nickname") val nickname: String = "",
    @SerialName("image") val image: String = "",
    @SerialName("xsec_token") val xsecToken: String = "",
)
