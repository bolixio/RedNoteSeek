package com.rednoteseek.core.web

import com.rednoteseek.core.network.api.PostedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteListAccumulatorTest {

    private fun page(cursor: String, vararg ids: String, hasMore: Boolean = true) =
        NoteListEvent.Page(NoteListKind.POSTED, "u1", cursor, ids.map { PostedNote(note_id = it) }, "next", hasMore)

    @Test fun seedComesFirstEvenIfPagesArriveEarlier() {
        val acc = NoteListAccumulator()
        acc.add(page("p1", "c", "d"))
        val items = acc.seed(listOf(PostedNote(note_id = "a"), PostedNote(note_id = "b")), hasMore = true)
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.note_id })
    }

    @Test fun pagesAppendAndDedupe() {
        val acc = NoteListAccumulator()
        acc.seed(listOf(PostedNote(note_id = "a")), hasMore = true)
        acc.add(page("p1", "a", "b"))
        assertEquals(listOf("a", "b", "c"), acc.add(page("p2", "c")).map { it.note_id })
    }

    @Test fun samePageAgainReplacesIt() {
        val acc = NoteListAccumulator()
        acc.add(page("", "a"))
        assertEquals(listOf("a", "b"), acc.add(page("", "a", "b")).map { it.note_id })
    }

    @Test fun hasMoreFollowsLatest() {
        val acc = NoteListAccumulator()
        acc.seed(emptyList(), hasMore = true)
        assertTrue(acc.hasMore)
        acc.add(page("p1", "a", hasMore = false))
        assertFalse(acc.hasMore)
    }
}
