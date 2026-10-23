package com.rednoteseek.core.network.api

import kotlinx.serialization.Serializable

/** 搜索结果（网页 search/notes 响应的 data）及笔记卡片、详情共用的模型。 */
@Serializable
data class SearchResult(
    val has_more: Boolean = false,
    val items: List<SearchItem> = emptyList(),
)

@Serializable
data class SearchItem(
    val id: String = "",                          // note_id
    val model_type: String = "",
    val note_card: NoteCard = NoteCard(),
    val xsec_token: String = "",
    val index: Int = 0,
)

@Serializable
data class NoteCard(
    val type: String = "normal",                  // normal | video
    val title: String = "",
    val display_title: String = "",
    val desc: String = "",
    val user: NoteUser = NoteUser(),
    val interact_info: InteractInfo = InteractInfo(),
    val cover: NoteImage? = null,                 // v2 搜索结果的封面
    val image_list: List<NoteImage> = emptyList(),
    val video: NoteVideo? = null,
    val tag_list: List<NoteTag> = emptyList(),
    val time: Long = 0,
    val last_update_time: Long = 0,
    val share_url: String = "",
    val ip_location: String = "",                 // v1/feed 才有，如「上海」
)

@Serializable
data class NoteUser(
    val user_id: String = "",
    val nickname: String = "",
    val nick_name: String = "",                   // v2 搜索结果有时只给这个
    val image: String = "",
    val avatar: String = "",                      // v2 接口的头像字段
    val xsec_token: String = "",
)

@Serializable
data class InteractInfo(
    val liked: Boolean = false,
    val liked_count: String = "0",
    val collected: Boolean = false,
    val collected_count: String = "0",
    val comment_count: String = "0",
    val share_count: String = "0",
)

@Serializable
data class NoteImage(
    val url_default: String = "",
    val url: String = "",
    val info_list: List<ImageInfo> = emptyList(),
    val trace_id: String = "",
    val file_id: String = "",
    val width: Int = 0,                           // v1/feed 才有，详情媒体区按它定高度
    val height: Int = 0,
)

@Serializable
data class ImageInfo(
    val image_scene: String = "",
    val url: String = "",
)

@Serializable
data class NoteVideo(
    val media: NoteVideoMedia = NoteVideoMedia(),
)

@Serializable
data class NoteVideoMedia(
    /** 键为编码标识；真机 v1/feed 中是 EF4 / EF5 / EF6 / EF7（可能为空数组），不是 h264 / h265。 */
    val stream: Map<String, List<VideoStream>> = emptyMap(),
)

/**
 * 选出可播放地址：把所有流展平，优先 `format == "mp4"` 的第一条非空 master_url，否则取任意第一条非空。
 * 依据真机数据：EF4 为 mp4（兼容性最好、https 可直接取），EF5 为 fmp4（.m4s 分片）。
 */
fun NoteVideo.playableUrl(): String? {
    val streams = media.stream.values.flatten().filter { it.master_url.isNotBlank() }
    return (streams.firstOrNull { it.format == "mp4" } ?: streams.firstOrNull())?.master_url
}

@Serializable
data class VideoStream(
    val master_url: String = "",
    val backup_urls: List<String> = emptyList(),
    val avg_bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val format: String = "",
)

@Serializable
data class NoteTag(
    val id: String = "",
    val name: String = "",
    val type: String = "",
)
