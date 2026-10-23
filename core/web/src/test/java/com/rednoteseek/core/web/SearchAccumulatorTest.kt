package com.rednoteseek.core.web

import com.rednoteseek.core.network.api.SearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAccumulatorTest {

    private fun page(page: Int?, vararg ids: String, hasMore: Boolean = true) =
        SearchEvent.Page("咖啡", page, ids.map { SearchItem(id = it) }, hasMore)

    @Test fun ordersPagesByRequestPage() {
        val acc = SearchAccumulator()
        acc.add(page(2, "c", "d"))
        val items = acc.add(page(1, "a", "b"))
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.id })
    }

    @Test fun dropsDuplicateNotes() {
        val acc = SearchAccumulator()
        acc.add(page(1, "a", "b"))
        val items = acc.add(page(2, "b", "c"))
        assertEquals(listOf("a", "b", "c"), items.map { it.id })
    }

    @Test fun samePageArrivingAgainReplacesIt() {
        val acc = SearchAccumulator()
        acc.add(page(1, "a"))
        val items = acc.add(page(1, "a", "b"))
        assertEquals(listOf("a", "b"), items.map { it.id })
    }

    @Test fun unknownPageIsAppended() {
        val acc = SearchAccumulator()
        acc.add(page(1, "a"))
        val items = acc.add(page(null, "b"))
        assertEquals(listOf("a", "b"), items.map { it.id })
    }

    @Test fun hasMoreFollowsLastPage() {
        val acc = SearchAccumulator()
        assertTrue(acc.hasMore)
        acc.add(page(2, "c", hasMore = false))
        assertFalse(acc.hasMore)
        acc.add(page(1, "a", hasMore = true))   // 较早的页晚到，不覆盖最后一页的 hasMore
        assertFalse(acc.hasMore)
    }
}
