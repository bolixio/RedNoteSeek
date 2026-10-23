package com.rednoteseek.core.web

/**
 * 搜索排序。tag 是网页请求体 filters 里 sort_type 的值，label 是网页筛选面板上的文字（真机实测）。
 * label 会拼进网页脚本，只能使用这里的常量。
 */
enum class SearchSort(val tag: String, val label: String) {
    GENERAL("general", "综合"),
    LATEST("time_descending", "最新"),
    MOST_LIKED("popularity_descending", "最多点赞"),
    MOST_COMMENTED("comment_descending", "最多评论"),
    MOST_COLLECTED("collect_descending", "最多收藏"),
    ;

    companion object {
        fun fromTag(tag: String?): SearchSort? = entries.firstOrNull { it.tag == tag }
    }
}
