package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingTest {

    private fun page(page: Int, size: Int, ids: List<String>, status: Int = 200, code: Int = 0): CapturedResponse {
        val items = ids.joinToString(",") {
            """{"status":2,"verify_type":0,"limit_msg_status":0,"user_id":"$it","nick_name":"n$it","avatar":"http://sns-avatar-qc.xhscdn.com/avatar/$it.jpg"}"""
        }
        return CapturedResponse(
            path = ApiPaths.FOLLOWING_ALL,
            request = "",
            status = status,
            body = """{"data":{"follow_user_d_t_o_list":[$items]},"code":$code,"success":true,"msg":"成功"}""",
            query = "page=$page&size=$size",
        )
    }

    @Test fun parsesFollowingPageAndUpgradesAvatar() {
        assertEquals(
            listOf(FollowingUser("u1", "nu1", "https://sns-avatar-qc.xhscdn.com/avatar/u1.jpg")),
            FollowParsers.followingPage(page(1, 200, listOf("u1"))),
        )
    }

    @Test fun failedRequestIsNotAnEmptyList() {
        assertNull(FollowParsers.followingPage(page(1, 200, emptyList(), status = 461)))
        assertNull(FollowParsers.followingPage(page(1, 200, emptyList(), code = -100)))
        assertNull(FollowParsers.followingPage(CapturedResponse(ApiPaths.FOLLOWING_ALL, "", 200, "oops")))
    }

    @Test fun shortFirstPageIsComplete() {
        assertEquals(listOf("a", "b"), FollowingAccumulator().add(page(1, 200, listOf("a", "b")))?.map { it.userId })
    }

    @Test fun emptyFirstPageMeansNoFollows() {
        assertEquals(emptyList<FollowingUser>(), FollowingAccumulator().add(page(1, 200, emptyList())))
    }

    @Test fun fullPagesWaitForTheLastOne() {
        val acc = FollowingAccumulator()
        assertNull(acc.add(page(1, 2, listOf("a", "b"))))
        assertEquals(listOf("a", "b", "c"), acc.add(page(2, 2, listOf("c")))?.map { it.userId })
    }

    @Test fun skippedPageOrFailureDropsTheRound() {
        val acc = FollowingAccumulator()
        assertNull(acc.add(page(1, 2, listOf("a", "b"))))
        assertNull(acc.add(page(3, 2, listOf("e"))))
        val failing = FollowingAccumulator()
        assertNull(failing.add(page(1, 2, listOf("a", "b"))))
        assertNull(failing.add(page(2, 2, emptyList(), status = 500)))
        assertNull(failing.add(page(3, 2, listOf("e"))))
        // 新一轮从第 1 页重新开始
        assertEquals(listOf("x"), failing.add(page(1, 2, listOf("x")))?.map { it.userId })
    }

    @Test fun followReplySuccess() {
        assertTrue(WriteReply.ok(CapturedResponse(ApiPaths.USER_FOLLOW, "", 200, """{"code":0,"success":true,"data":{"fstatus":"follows"}}""")))
        assertFalse(WriteReply.ok(CapturedResponse(ApiPaths.USER_FOLLOW, "", 461, "")))
        assertFalse(WriteReply.ok(CapturedResponse(ApiPaths.USER_FOLLOW, "", 200, """{"code":300011,"success":false}""")))
    }
}

class UnreadParserTest {

    private fun extra(body: String, status: Int = 200) =
        CapturedResponse(ApiPaths.FOLLOWINGS_EXTRA, "", status, body, query = "user_id=me")

    @Test fun parsesUnreadCounts() {
        val body = """{"code":0,"success":true,"msg":"ok","data":{"follow_extra_list":[
            {"desc_style":"highlight","resource_type":"UNREAD_COUNT","user_id":"u1","unread_note_count":1,"desc":"1篇笔记未看"},
            {"user_id":"u2","unread_note_count":0},{"user_id":"u3"}]}}"""
        assertEquals(UnreadResult.Counts(mapOf("u1" to 1, "u2" to 0, "u3" to 0)), FollowParsers.unread(extra(body)))
    }

    @Test fun riskAndFailures() {
        assertEquals(UnreadResult.Failed(risk = true), FollowParsers.unread(extra("", status = 461)))
        assertEquals(UnreadResult.Failed(risk = true), FollowParsers.unread(extra("""{"code":300011,"success":false}""")))
        assertEquals(UnreadResult.Failed(risk = false), FollowParsers.unread(extra("""{"code":-1}""")))
        assertEquals(UnreadResult.Failed(risk = false), FollowParsers.unread(extra("oops")))
    }
}
