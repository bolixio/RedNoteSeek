package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XhsUrlsTest {

    @Test fun searchUrlIsHttpsWithTrailingSlash() {
        assertEquals(
            "https://www.xiaohongshu.com/search_result/?keyword=%E5%92%96%E5%95%A1&source=web_search_result_notes",
            XhsUrls.search("咖啡"),
        )
    }

    @Test fun searchUrlEncodesSpaceAndAmpersand() {
        assertEquals(
            "https://www.xiaohongshu.com/search_result/?keyword=a+%26+b&source=web_search_result_notes",
            XhsUrls.search("a & b"),
        )
    }

    @Test fun aiSearchUrl() {
        assertEquals(
            "https://www.xiaohongshu.com/search_result_ai?keyword=%E5%92%96%E5%95%A1+%26",
            XhsUrls.searchAi("咖啡 &"),
        )
    }

    @Test fun dotsChatUrls() {
        assertEquals("https://www.xiaohongshu.com/ai_chat?m_source=pwa", XhsUrls.dotsChat())
        assertEquals(
            "https://www.xiaohongshu.com/ai_chat?m_source=pwa&conversationId=6a86dec3-fc82-4f99-8acf-b766824721de",
            XhsUrls.dotsConversation("6a86dec3-fc82-4f99-8acf-b766824721de"),
        )
        assertEquals("https://www.xiaohongshu.com/ai_chat?m_source=pwa&conversationId=a%26b", XhsUrls.dotsConversation("a&b"))
    }

    @Test fun upgradesXhsHostsToHttps() {
        assertEquals(
            "https://sns-webpic-qc.xhscdn.com/202610180239/aaaa/1040g2sg!nc_n_webp_mw_1",
            XhsUrls.toHttps("http://sns-webpic-qc.xhscdn.com/202610180239/aaaa/1040g2sg!nc_n_webp_mw_1"),
        )
        assertEquals(
            "https://www.xiaohongshu.com/search_result/?keyword=x",
            XhsUrls.toHttps("http://www.xiaohongshu.com/search_result/?keyword=x"),
        )
    }

    @Test fun leavesOtherUrlsUntouched() {
        assertEquals("https://www.xiaohongshu.com/explore", XhsUrls.toHttps("https://www.xiaohongshu.com/explore"))
        assertEquals("http://example.com/a", XhsUrls.toHttps("http://example.com/a"))
        assertEquals("http://evilxhscdn.com/a", XhsUrls.toHttps("http://evilxhscdn.com/a"))
        assertEquals("xhsdiscover://home", XhsUrls.toHttps("xhsdiscover://home"))
    }

    @Test fun desktopUserAgentKeepsChromeVersion() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; PJD110 Build/BP2A.250605.015; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/155.0.8059.43 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/155.0.8059.43 Safari/537.36",
            desktopUserAgent(mobile),
        )
    }

    @Test fun acceptsRealProfileUrls() {
        assertTrue(XhsUrls.isProfileUrl("https://www.xiaohongshu.com/user/profile/63a99c54000000002600558b?channel_type=web_search_result_notes&parent_page_channel_type=web_profile_board&xsec_token=ABHUc-DOWB_x%3D&xsec_source=pc_search"))
        assertTrue(XhsUrls.isProfileUrl("https://www.xiaohongshu.com/user/profile/60b70d160000000001008804"))
    }

    @Test fun rejectsUnsafeProfileUrls() {
        listOf(
            "https://www.xiaohongshu.com/user/profile/../../explore",
            "https://www.xiaohongshu.com/user/profile/%2e%2e/x",
            "https://www.xiaohongshu.com/user/profile/a/../../b",
            "http://www.xiaohongshu.com/user/profile/abc",
            "https://evil.com/user/profile/abc",
            "https://www.xiaohongshu.com.evil.com/user/profile/abc",
            "https://www.xiaohongshu.com/user/profile/abc#x",
            "https://user@www.xiaohongshu.com/user/profile/abc",
            "https://www.xiaohongshu.com:444/user/profile/abc",
            "https://www.xiaohongshu.com/user/profile/",
            "https://www.xiaohongshu.com/user/profile/abc\nx",
            "https://www.xiaohongshu.com/user/profile/a\\b",
        ).forEach { assertFalse(it, XhsUrls.isProfileUrl(it)) }
    }

    @Test fun noteImagesAvatarsAndVideosAreMedia() {
        assertEquals(XhsUrls.Media.IMAGE, XhsUrls.mediaKind("https://sns-webpic-qc.xhscdn.com/202610190225/8e1f/notes_pre_post/1040g3k8!nc_n_webp_mw_1"))
        assertEquals(XhsUrls.Media.IMAGE, XhsUrls.mediaKind("https://sns-avatar-qc.xhscdn.com/avatar/1040g2jo?imageView2/2/w/120/format/jpg"))
        assertEquals(XhsUrls.Media.IMAGE, XhsUrls.mediaKind("http://sns-img-hw.xhscdn.com/abc"))
        assertEquals(XhsUrls.Media.VIDEO, XhsUrls.mediaKind("https://sns-video-v27.xhscdn.com/stream/1/110/259/01e8.mp4"))
    }

    @Test fun pageAssetsAndOtherHostsAreNotMedia() {
        listOf(
            "https://fe-static.xhscdn.com/formula-static/xhs-pc-web/public/resource/js/index.js",
            "https://fe-platform.xhscdn.com/platform/104101l031g2ff03ql1k3.json",
            "https://fe-video-qc.xhscdn.com/fe-platform/ed8f.ico",
            "https://edith.xiaohongshu.com/api/sns/web/v2/search/notes",
            "https://sns-webpic.example.com/a.jpg",
            "https://evil.com/sns-webpic-qc.xhscdn.com/a.jpg",
            "data:image/png;base64,AAAA",
        ).forEach { assertEquals(it, null, XhsUrls.mediaKind(it)) }
    }
}
