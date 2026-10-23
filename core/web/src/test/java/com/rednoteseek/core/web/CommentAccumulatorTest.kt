package com.rednoteseek.core.web

import com.rednoteseek.core.network.api.XhsComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentAccumulatorTest {

    private fun page(requestCursor: String, vararg ids: String, hasMore: Boolean = true) =
        NoteEvent.Comments("n1", requestCursor, ids.map { XhsComment(id = it) }, cursor = "next", hasMore = hasMore)

    @Test fun appendsPagesInArrivalOrder() {
        val acc = CommentAccumulator()
        acc.add(page("", "a", "b"))
        assertEquals(listOf("a", "b", "c"), acc.add(page("cur1", "c")).map { it.id })
    }

    @Test fun dropsDuplicateComments() {
        val acc = CommentAccumulator()
        acc.add(page("", "a", "b"))
        assertEquals(listOf("a", "b", "c"), acc.add(page("cur1", "b", "c")).map { it.id })
    }

    @Test fun hasMoreFollowsLatestPage() {
        val acc = CommentAccumulator()
        assertFalse(acc.hasMore)
        acc.add(page("", "a"))
        assertTrue(acc.hasMore)
        acc.add(page("cur1", "b", hasMore = false))
        assertFalse(acc.hasMore)
    }

    @Test fun repeatedFirstPageDoesNotChangeHasMore() {
        val acc = CommentAccumulator()
        acc.add(page("", "a"))
        acc.add(page("cur1", "b", hasMore = false))
        acc.add(page("", "a", hasMore = true))   // 页面重复请求了第一页
        assertFalse(acc.hasMore)
        assertEquals(listOf("a", "b"), acc.items.map { it.id })
    }

    @Test fun repliesMergeIntoTheirRoot() {
        val acc = CommentAccumulator()
        acc.add(NoteEvent.Comments("n1", "", listOf(
            XhsComment(id = "c1", subComments = listOf(XhsComment(id = "s1"))),
            XhsComment(id = "c2"),
        ), cursor = "next", hasMore = false))
        val items = acc.addReplies(NoteEvent.SubComments("n1", "c1", "s1", listOf(XhsComment(id = "s1"), XhsComment(id = "s2")), "s2", hasMore = true))
        assertEquals(listOf("s1", "s2"), items.first { it.id == "c1" }.subComments.map { it.id })
        assertEquals(true, items.first { it.id == "c1" }.subCommentHasMore)
        assertEquals(emptyList<XhsComment>(), items.first { it.id == "c2" }.subComments)
    }

    @Test fun repliesForUnknownRootAreIgnored() {
        val acc = CommentAccumulator()
        acc.add(NoteEvent.Comments("n1", "", listOf(XhsComment(id = "c1")), cursor = "", hasMore = false))
        val items = acc.addReplies(NoteEvent.SubComments("n1", "zz", "", listOf(XhsComment(id = "s9")), "", hasMore = false))
        assertEquals(listOf("c1"), items.map { it.id })
        assertEquals(emptyList<XhsComment>(), items.single().subComments)
    }
}
