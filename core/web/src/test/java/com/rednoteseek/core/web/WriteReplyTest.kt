package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteReplyTest {

    private fun reply(body: String, status: Int = 200, path: String = ApiPaths.BOARD) = CapturedResponse(path, "", status, body)

    @Test fun boardWritesUseResultInsteadOfCode() {
        // 真机样本：POST / PUT / DELETE v1/board
        assertTrue(WriteReply.ok(reply("""{"result":0,"success":true,"msg":"","data":{"desc":"d","id":"b1","name":"RNS测试"}}""")))
        assertTrue(WriteReply.ok(reply("""{"success":true,"msg":"","data":{},"result":0}""")))
        assertFalse(WriteReply.ok(reply("""{"result":-1,"success":false,"msg":"x"}""")))
    }

    @Test fun collectAndMoveUseCode() {
        assertTrue(WriteReply.ok(reply("""{"code":0,"success":true,"msg":"成功"}""", path = ApiPaths.NOTE_COLLECT)))
        assertTrue(WriteReply.ok(reply("""{"code":0,"success":true,"msg":"成功","data":{}}""", path = ApiPaths.NOTE_MOVE)))
        assertFalse(WriteReply.ok(reply("""{"code":300011,"success":false}""", path = ApiPaths.NOTE_COLLECT)))
        assertFalse(WriteReply.ok(reply("", status = 461, path = ApiPaths.NOTE_COLLECT)))
        assertFalse(WriteReply.ok(reply("oops")))
    }

    @Test fun createdBoardId() {
        assertEquals("b1", WriteReply.createdBoardId(reply("""{"result":0,"success":true,"data":{"id":"b1","name":"n"}}""")))
        assertNull(WriteReply.createdBoardId(reply("""{"result":0,"success":true,"data":{}}""")))
        assertNull(WriteReply.createdBoardId(reply("""{"result":1,"success":false,"data":{"id":"b1"}}""")))
    }

    @Test fun boardPanelPage() {
        val body = """{"code":0,"success":true,"msg":"","data":{"boards":[{"id":"b1","name":"RNS测试","privacy":1,"total":0,"images":[]},{"id":"b2","name":"旅行","total":37}]}}"""
        assertEquals(listOf("b1", "b2"), WriteReply.boardPage(reply(body, path = ApiPaths.BOARD_USER))?.map { it.id })
        assertNull(WriteReply.boardPage(reply("", status = 500, path = ApiPaths.BOARD_USER)))
    }

    @Test fun boardDetailsToForm() {
        assertEquals(
            "b1" to BoardForm("美食", "", public = true),
            BoardForm.parseDetails("""{"id":"b1","name":"美食","desc":"暂无简介","privacy":0}"""),
        )
        assertEquals(
            "b2" to BoardForm("RNS测试", "临时", public = false),
            BoardForm.parseDetails("""{"id":"b2","name":"RNS测试","desc":"临时","privacy":1}"""),
        )
        assertNull(BoardForm.parseDetails(""))
    }
}
