package com.rednoteseek.core.web

/** 移动 UA 会被导向不支持搜索的 H5 页面；沿用 WebView 真实的 Chrome 版本号，只把平台换成 Windows 桌面。 */
internal fun desktopUserAgent(defaultUserAgent: String): String {
    val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1) ?: "130.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/$chrome Safari/537.36"
}
