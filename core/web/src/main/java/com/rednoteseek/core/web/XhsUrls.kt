package com.rednoteseek.core.web

import java.net.URLEncoder

object XhsUrls {
    const val ORIGIN = "https://www.xiaohongshu.com"
    const val HOME = "$ORIGIN/explore"

    /** 不带关键词的搜索结果页：已登录时启动就停在这里，第一次搜索即可页内换词（网页会发一次空关键词搜索，正常返回空结果）。 */
    const val SEARCH_HOME = "$ORIGIN/search_result/"

    private const val PROFILE_PREFIX = "$ORIGIN/user/profile/"

    private val PROFILE_PATH = Regex("^/user/profile/[0-9a-zA-Z]{1,64}$")

    /**
     * 只允许加载小红书用户主页地址到第二引擎。
     * 仅比较前缀会放过 `/user/profile/../../explore`、`%2e%2e`、反斜杠等路径穿越写法，
     * 所以用 URI 解析后逐项校验：https、固定主机、无 userInfo/端口/fragment，
     * 原始路径必须恰为 `/user/profile/<字母数字 id>`，且整串不含反斜杠、`%2e`、控制字符。
     * query 允许（真实链接带 xsec_token 等参数）。
     */
    fun isProfileUrl(url: String): Boolean {
        if (url.any { it == '\\' || it.isISOControl() } || url.contains("%2e", ignoreCase = true)) return false
        return try {
            val uri = java.net.URI(url)
            uri.scheme == "https" &&
                uri.host == "www.xiaohongshu.com" &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.rawFragment == null &&
                uri.rawPath != null && PROFILE_PATH.matches(uri.rawPath)
        } catch (e: Exception) {
            false
        }
    }

    /** 不带 xsec_token 的主页地址：自己的主页，以及关注名单里的作者（名单不含 token）。 */
    fun profile(userId: String): String = PROFILE_PREFIX + userId

    /** 自己主页的「收藏 › 专辑」：带参数直接加载即选中这两个标签，专辑列表随页面服务端渲染。 */
    fun selfBoards(userId: String): String = PROFILE_PREFIX + userId + "?tab=fav&subTab=board"

    /** 专辑页（与网页专辑卡片的链接相同）。 */
    fun board(boardId: String): String = "$ORIGIN/board/$boardId?source=web_user_page"

    /** 直接请求最终的 https 地址：`/search_result?` 会被重定向到 http 的 `/search_result/`。 */
    fun search(keyword: String): String =
        "$ORIGIN/search_result/?keyword=${URLEncoder.encode(keyword, "UTF-8")}&source=web_search_result_notes"

    /**
     * 点点 AI 结果页：笔记网格与普通结果页相同，顶部多一张 AI 总结。
     * 不带关键词时网页会跳回 /explore，所以没有对应的空结果页。
     */
    fun searchAi(keyword: String): String =
        "$ORIGIN/search_result_ai?keyword=${URLEncoder.encode(keyword, "UTF-8")}"

    /** 点点对话页（欢迎页）：网页加载时请求会话列表，侧边栏可展开会话历史。对话引擎只加载这个与 [dotsConversation]。 */
    fun dotsChat(): String = "$ORIGIN/ai_chat?m_source=pwa"

    /** 直接打开某个点点会话（真机验证可用；这不是笔记链接，没有 461 问题）。网页随即请求 conversation/detail。 */
    fun dotsConversation(id: String): String =
        "$ORIGIN/ai_chat?m_source=pwa&conversationId=${URLEncoder.encode(id, "UTF-8")}"

    enum class Media { IMAGE, VIDEO }

    /**
     * 笔记图片 / 头像 / 视频的 CDN 地址返回对应类型，其他返回 null。
     * 这些内容原生界面会自己下载，隐藏的网页不必再下一遍；网页脚本、样式与图标（fe-static 等）不在其列。
     */
    fun mediaKind(url: String): Media? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (!host.endsWith(".xhscdn.com")) return null
        return when {
            host.startsWith("sns-webpic") || host.startsWith("sns-avatar") || host.startsWith("sns-img") -> Media.IMAGE
            host.startsWith("sns-video") -> Media.VIDEO
            else -> null
        }
    }

    /** xhs 的接口数据和重定向里常出现 http 地址，而 App 禁止明文流量；只升级 xhs 自家域名。 */
    fun toHttps(url: String): String {
        if (!url.startsWith("http://")) return url
        val rest = url.removePrefix("http://")
        val host = rest.substringBefore('/').substringBefore(':')
        val isXhs = host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") || host.endsWith(".xhscdn.com")
        return if (isXhs) "https://$rest" else url
    }
}
