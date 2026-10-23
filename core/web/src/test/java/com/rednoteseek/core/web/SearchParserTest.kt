package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParserTest {

    private val fixture = checkNotNull(javaClass.classLoader!!.getResourceAsStream("search_notes_v2.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun captured(
        path: String = "v2/search/notes",
        request: String = """{"keyword":"咖啡","page":2,"page_size":20,"sort":"general","note_type":0}""",
        status: Int = 200,
        body: String = fixture,
    ) = CapturedResponse(path, request, status, body)

    @Test fun parsesNotesAndDropsHotQuery() {
        val page = SearchParser.parse(captured()) as SearchEvent.Page
        assertEquals("咖啡", page.keyword)
        assertEquals(2, page.page)
        assertTrue(page.hasMore)
        assertEquals(listOf("6a97885a0000000028033a5d", "6a2f6f46000000000f03114a"), page.items.map { it.id })

        val first = page.items[0].note_card
        assertEquals(
            "http://sns-webpic-qc.xhscdn.com/202610180239/aaaa/1040g2sg324jl7cpf0a7!nc_n_webp_mw_1",
            first.cover?.url_default,
        )
        assertEquals("https://sns-avatar-qc.xhscdn.com/avatar/a1.jpg", first.user.avatar)

        val second = page.items[1].note_card
        assertEquals("video", second.type)
        assertEquals("元大咖", second.user.nick_name)
        assertEquals("", second.user.nickname)
        assertNull(second.cover)
    }

    @Test fun unparsableRequestGivesNullKeywordAndPage() {
        val page = SearchParser.parse(captured(request = "")) as SearchEvent.Page
        assertNull(page.keyword)
        assertNull(page.page)
    }

    @Test fun ignoresOtherPaths() {
        assertNull(SearchParser.parse(captured(path = "v2/user/me")))
    }

    @Test fun ignoresAbortedRequests() {
        assertNull(SearchParser.parse(captured(status = 0, body = "")))
    }

    @Test fun httpErrorBecomesFailed() {
        assertEquals(SearchEvent.Failed("咖啡", "HTTP 461", sort = "general"), SearchParser.parse(captured(status = 461, body = "")))
    }

    @Test fun apiErrorBecomesFailed() {
        assertEquals(
            SearchEvent.Failed("咖啡", "账号异常", sort = "general"),
            SearchParser.parse(captured(body = """{"code":300011,"success":false,"msg":"账号异常"}""")),
        )
    }

    @Test fun garbageBodyBecomesFailed() {
        assertEquals(SearchEvent.Failed("咖啡", "无法解析搜索结果", sort = "general"), SearchParser.parse(captured(body = "<html>")))
    }

    @Test fun requestWithoutFiltersIsGeneralSort() {
        val page = SearchParser.parse(captured()) as SearchEvent.Page
        assertEquals("general", page.sort)
    }

    @Test fun sortComesFromFilters() {
        val request = """{"keyword":"咖啡","page":1,"page_size":20,"sort":"general","filters":[{"tags":["time_descending"],"type":"sort_type"},{"tags":["不限"],"type":"filter_note_type"}]}"""
        val page = SearchParser.parse(captured(request = request)) as SearchEvent.Page
        assertEquals("time_descending", page.sort)
    }

    @Test fun unparsableRequestHasNoSort() {
        val page = SearchParser.parse(captured(request = "")) as SearchEvent.Page
        assertNull(page.sort)
    }

    @Test fun sortTagsMapToEnum() {
        assertEquals(SearchSort.MOST_LIKED, SearchSort.fromTag("popularity_descending"))
        assertEquals(SearchSort.GENERAL, SearchSort.fromTag("general"))
        assertNull(SearchSort.fromTag("unknown"))
        assertNull(SearchSort.fromTag(null))
    }
}
