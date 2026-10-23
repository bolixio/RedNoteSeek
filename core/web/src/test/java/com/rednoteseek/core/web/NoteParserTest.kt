package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteParserTest {

    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val feedRequest = """{"source_note_id":"6971af58000000000a03dba9","image_formats":["jpg","webp","avif"],"xsec_source":"pc_search","xsec_token":"AB="}"""

    private fun feed(status: Int = 200, body: String = resource("feed_v1.json"), request: String = feedRequest) =
        CapturedResponse("v1/feed", request, status, body)

    private fun comments(query: String, status: Int = 200, body: String = resource("comment_page_v2.json")) =
        CapturedResponse("v2/comment/page", "", status, body, query)

    @Test fun feedBecomesDetail() {
        val detail = NoteParser.parse(feed()) as NoteEvent.Detail
        assertEquals("6971af58000000000a03dba9", detail.noteId)
        assertEquals("意大利旅行攻略", detail.card.title)
        assertEquals("https://sns-avatar-qc.xhscdn.com/avatar/a1", detail.card.user.avatar)
        assertEquals(
            "http://sns-webpic-qc.xhscdn.com/202610180249/aaaa/notes_pre_post/1040g3k0001!nd_dft_wlteh_webp_3",
            detail.card.image_list.single().url_default,
        )
        assertEquals("意大利旅行", detail.card.tag_list.single().name)
        assertEquals(1080, detail.card.image_list.single().width)
    }

    @Test fun feedHttp461IsRisk() {
        assertEquals(
            NoteEvent.Failed("6971af58000000000a03dba9", "HTTP 461", risk = true, fromFeed = true),
            NoteParser.parse(feed(status = 461, body = "")),
        )
    }

    @Test fun feedRiskCodeIsRisk() {
        assertEquals(
            NoteEvent.Failed("6971af58000000000a03dba9", "账号异常", risk = true, fromFeed = true),
            NoteParser.parse(feed(body = """{"code":300011,"success":false,"msg":"账号异常"}""")),
        )
    }

    @Test fun feedWithoutItemsFails() {
        assertEquals(
            NoteEvent.Failed("6971af58000000000a03dba9", "笔记不存在或已删除", risk = false, fromFeed = true),
            NoteParser.parse(feed(body = """{"code":0,"success":true,"data":{"items":[]}}""")),
        )
    }

    @Test fun feedGarbageFails() {
        assertEquals(
            NoteEvent.Failed("6971af58000000000a03dba9", "无法解析笔记详情", risk = false, fromFeed = true),
            NoteParser.parse(feed(body = "<html>")),
        )
    }

    @Test fun abortedRequestsAreIgnored() {
        assertNull(NoteParser.parse(feed(status = 0, body = "")))
        assertNull(NoteParser.parse(comments("note_id=n1&cursor=", status = 0, body = "")))
    }

    @Test fun commentPageBecomesComments() {
        val event = NoteParser.parse(
            comments("note_id=6971af58000000000a03dba9&cursor=&top_comment_id=&image_formats=jpg,webp,avif"),
        ) as NoteEvent.Comments
        assertEquals("6971af58000000000a03dba9", event.noteId)
        assertEquals("", event.requestCursor)
        assertEquals(listOf("c1", "c2"), event.comments.map { it.id })
        assertEquals("6aa8d731000000001402cbf4", event.cursor)
        assertTrue(event.hasMore)
        assertEquals("小绿", event.comments[0].subComments.single().userInfo.nickname)
        assertEquals("上海", event.comments[0].ipLocation)
        assertEquals(1792000000000, event.comments[0].createTime)
    }

    @Test fun commentRequestCursorComesFromQuery() {
        val event = NoteParser.parse(comments("note_id=n1&cursor=abc")) as NoteEvent.Comments
        assertEquals("abc", event.requestCursor)
    }

    @Test fun commentHttp461IsRisk() {
        assertEquals(
            NoteEvent.Failed("n1", "HTTP 461", risk = true),
            NoteParser.parse(comments("note_id=n1&cursor=", status = 461, body = "")),
        )
    }

    @Test fun subCommentPageBecomesSubComments() {
        val event = NoteParser.parse(
            CapturedResponse("v2/comment/sub/page", "", 200, resource("comment_sub_page_v2.json"),
                "note_id=n1&root_comment_id=c1&num=10&cursor=s1&image_formats=jpg,webp,avif"),
        ) as NoteEvent.SubComments
        assertEquals("n1", event.noteId)
        assertEquals("c1", event.rootCommentId)
        assertEquals("s1", event.requestCursor)
        assertEquals(listOf("s2", "s3"), event.comments.map { it.id })
        assertEquals("s3", event.cursor)
        assertEquals("c1", event.comments[0].targetComment?.id)
        assertTrue(event.hasMore)
    }

    @Test fun subCommentWithoutRootIsIgnored() {
        assertNull(NoteParser.parse(CapturedResponse("v2/comment/sub/page", "", 200, resource("comment_sub_page_v2.json"), "note_id=n1")))
    }

    @Test fun subCommentRiskIsRisk() {
        assertEquals(
            NoteEvent.Failed("n1", "HTTP 461", risk = true),
            NoteParser.parse(CapturedResponse("v2/comment/sub/page", "", 461, "", "note_id=n1&root_comment_id=c1")),
        )
    }

    @Test fun otherPathsAreIgnored() {
        assertNull(NoteParser.parse(CapturedResponse("v2/search/notes", "", 200, "{}")))
    }

    @Test fun cachedCommentsParse() {
        val json = """{"comments":[{"id":"c1","note_id":"n1","content":"好","like_count":"3","sub_comments":[]}],"cursor":"cur","has_more":true}"""
        val event = NoteParser.parseCachedComments("n1", json)!!
        assertEquals("n1", event.noteId)
        assertEquals("", event.requestCursor)
        assertEquals(listOf("c1"), event.comments.map { it.id })
        assertEquals("cur", event.cursor)
        assertTrue(event.hasMore)
    }

    @Test fun cachedCommentsEmptyOrInvalidAreNull() {
        assertNull(NoteParser.parseCachedComments("n1", ""))
        assertNull(NoteParser.parseCachedComments("n1", "{oops"))
    }
}
