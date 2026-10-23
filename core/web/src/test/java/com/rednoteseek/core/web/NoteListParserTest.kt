package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteListParserTest {

    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun userPostedBecomesPostedPage() {
        val page = NoteListParser.parse(
            CapturedResponse("v1/user_posted", "", 200, resource("user_posted_v1.json"), "num=30&cursor=p1&user_id=u1&image_formats=jpg,webp,avif"),
        ) as NoteListEvent.Page
        assertEquals(NoteListKind.POSTED, page.kind)
        assertEquals("u1", page.userId)
        assertEquals("p1", page.requestCursor)
        assertEquals(listOf("n10", "n11"), page.notes.map { it.note_id })
        assertEquals("p2", page.cursor)
        assertTrue(page.hasMore)
        assertEquals("104", page.notes[0].interact_info.liked_count)
        assertEquals("http://sns-webpic-qc.xhscdn.com/202610181229/aaaa/1040g2sg31u5!nc_n_webp_mw_1", page.notes[0].cover?.url_default)
    }

    @Test fun collectPageBecomesCollectedPage() {
        val page = NoteListParser.parse(
            CapturedResponse("v2/note/collect/page", "", 200, resource("collect_page_v2.json"), "num=30&cursor=&user_id=me"),
        ) as NoteListEvent.Page
        assertEquals(NoteListKind.COLLECTED, page.kind)
        assertEquals("me", page.userId)
        assertEquals("", page.requestCursor)
        assertEquals(listOf("m1"), page.notes.map { it.note_id })
    }

    @Test fun riskAndFailures() {
        assertEquals(
            NoteListEvent.Failed(NoteListKind.POSTED, "u1", "HTTP 461", risk = true),
            NoteListParser.parse(CapturedResponse("v1/user_posted", "", 461, "", "user_id=u1")),
        )
        assertEquals(
            NoteListEvent.Failed(NoteListKind.COLLECTED, "me", "账号异常", risk = true),
            NoteListParser.parse(CapturedResponse("v2/note/collect/page", "", 200, """{"code":300011,"success":false,"msg":"账号异常"}""", "user_id=me")),
        )
        assertEquals(
            NoteListEvent.Failed(NoteListKind.POSTED, "u1", "无法解析笔记列表", risk = false),
            NoteListParser.parse(CapturedResponse("v1/user_posted", "", 200, "<html>", "user_id=u1")),
        )
    }

    @Test fun otherPathsAndAbortedAreIgnored() {
        assertNull(NoteListParser.parse(CapturedResponse("v2/search/notes", "", 200, "{}")))
        assertNull(NoteListParser.parse(CapturedResponse("v1/user_posted", "", 0, "", "user_id=u1")))
    }

    @Test fun snapshotParses() {
        val snap = NoteListParser.parseSnapshot(resource("profile_snapshot.json"))!!
        assertEquals("元大咖", snap.user.nickname)
        assertEquals("3.2万", snap.user.fans)
        assertEquals(listOf("n1"), snap.notes.map { it.note_id })
        assertEquals("p1", snap.cursor)
        assertTrue(snap.has_more)
    }

    @Test fun emptyOrBadSnapshotIsNull() {
        assertNull(NoteListParser.parseSnapshot(""))
        assertNull(NoteListParser.parseSnapshot("not json"))
    }
}

class BoardParserTest {

    @Test fun boardNotePageUsesBoardIdAsOwner() {
        val captured = CapturedResponse(
            ApiPaths.BOARD_NOTE, "", 200,
            """{"code":0,"success":true,"data":{"cursor":"","has_more":false,"notes":[{"note_id":"n1","display_title":"t","type":"normal","time":5,"user":{"user_id":"u","nick_name":"x"}}]}}""",
            query = "board_id=b1&num=30&cursor=c1&image_formats=jpg,webp,avif",
        )
        val page = NoteListParser.parse(captured) as NoteListEvent.Page
        assertEquals(NoteListKind.BOARD, page.kind)
        assertEquals("b1", page.userId)
        assertEquals("c1", page.requestCursor)
        assertEquals(listOf("n1"), page.notes.map { it.note_id })
        assertEquals(false, page.hasMore)
    }

    @Test fun parsesBoardScriptOutputs() {
        val list = NoteListParser.parseBoardList("""{"boards":[{"id":"b1","name":"旅行","total":37,"images":["http://a/1"],"privacy":0}]}""")!!
        assertEquals("旅行", list.boards.single().name)
        assertEquals(37, list.boards.single().total)
        val first = NoteListParser.parseBoardSnapshot("""{"notes":[{"note_id":"n1","cover":{"url_default":"http://c","url":"","info_list":[{"image_scene":"WB_DFT","url":"http://d"}]},"user":{"user_id":"u","nickname":"x","avatar":""},"interact_info":{"liked_count":"3"}}],"cursor":"c","has_more":true}""")!!
        assertEquals("http://c", first.notes.single().cover?.url_default)
        assertEquals(true, first.has_more)
        assertNull(NoteListParser.parseBoardList(""))
        assertNull(NoteListParser.parseBoardSnapshot("oops"))
    }
}
