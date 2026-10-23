package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturedResponseTest {

    @Test fun parsesBridgeMessage() {
        val message = """{"url":"https://edith.xiaohongshu.com/api/sns/web/v2/search/notes","request":"{\"keyword\":\"咖啡\"}","status":200,"body":"{\"code\":0}"}"""
        val captured = CapturedResponse.parse(message)!!
        assertEquals("v2/search/notes", captured.path)
        assertEquals("""{"keyword":"咖啡"}""", captured.request)
        assertEquals(200, captured.status)
        assertEquals("""{"code":0}""", captured.body)
    }

    @Test fun missingOptionalFieldsUseDefaults() {
        val captured = CapturedResponse.parse("""{"url":"/api/sns/web/v2/user/me"}""")!!
        assertEquals("v2/user/me", captured.path)
        assertEquals("", captured.request)
        assertEquals(0, captured.status)
        assertEquals("", captured.body)
    }

    @Test fun rejectsNonApiUrl() {
        assertNull(CapturedResponse.parse("""{"url":"https://www.xiaohongshu.com/explore","status":200,"body":""}"""))
    }

    @Test fun rejectsMalformedMessage() {
        assertNull(CapturedResponse.parse("not json"))
        assertNull(CapturedResponse.parse("[1]"))
        assertNull(CapturedResponse.parse("""{"status":200}"""))
    }

    @Test fun apiPathStripsQueryAndFragment() {
        assertEquals(
            "v2/comment/page",
            CapturedResponse.apiPath("https://edith.xiaohongshu.com/api/sns/web/v2/comment/page?note_id=1&cursor=#x"),
        )
        assertNull(CapturedResponse.apiPath("https://www.xiaohongshu.com/explore"))
    }

    @Test fun apiPathOfFollowingListKeepsModulePrefix() {
        assertEquals(
            "im/web/users/following/all",
            CapturedResponse.apiPath("https://edith.xiaohongshu.com/api/im/web/users/following/all?page=1&size=200"),
        )
    }

    @Test fun keepsQueryAndDecodesParams() {
        val captured = CapturedResponse.parse(
            """{"url":"https://edith.xiaohongshu.com/api/sns/web/v2/comment/page?note_id=n1&cursor=&image_formats=jpg%2Cwebp#x","status":200,"body":""}""",
        )!!
        assertEquals("note_id=n1&cursor=&image_formats=jpg%2Cwebp", captured.query)
        assertEquals("n1", captured.queryParam("note_id"))
        assertEquals("", captured.queryParam("cursor"))
        assertEquals("jpg,webp", captured.queryParam("image_formats"))
        assertNull(captured.queryParam("top_comment_id"))
    }

    @Test fun parsesPushSocketFrame() {
        val message = """{"url":"wss://apppush-rws.xiaohongshu.com/rwp","request":"","status":101,"body":"{\"v\":1,\"t\":4}"}"""
        val captured = CapturedResponse.parse(message)!!
        assertEquals("ws/push", captured.path)
        assertEquals(101, captured.status)
        assertEquals("""{"v":1,"t":4}""", captured.body)
    }

    @Test fun mapsDianDianApiPaths() {
        assertEquals("v1/dqa/instant", CapturedResponse.apiPath("https://so.xiaohongshu.com/api/sns/web/v1/dqa/instant"))
        assertEquals("search/send/ai", CapturedResponse.apiPath("https://so.xiaohongshu.com/api/sns/web/search/send/ai"))
        assertEquals("v1/search/dqa/detail", CapturedResponse.apiPath("https://edith.xiaohongshu.com/api/sns/web/v1/search/dqa/detail?message_id=1"))
    }

    @Test fun malformedEscapeFallsBackToRaw() {
        val captured = CapturedResponse("v2/comment/page", "", 200, "", query = "note_id=n1&cursor=%zz")
        assertEquals("%zz", captured.queryParam("cursor"))
        assertEquals("n1", captured.queryParam("note_id"))
    }

    @Test fun mapsDotsChatApiPaths() {
        assertEquals(
            "sns/agentspark/web/conversation/list",
            CapturedResponse.apiPath("https://edith.xiaohongshu.com/api/sns/agentspark/web/conversation/list?biz=xhs_dots_pc&pageFrom=0&size=20&preCursor=0"),
        )
        assertEquals(
            "sns/agentspark/web/v1/dqa/complex/source",
            CapturedResponse.apiPath("https://edith.xiaohongshu.com/api/sns/agentspark/web/v1/dqa/complex/source?card_id=ai-msg_1"),
        )
        assertEquals("search/signal", CapturedResponse.apiPath("https://so.xiaohongshu.com/api/sns/web/search/signal"))
    }
}
