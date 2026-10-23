package com.rednoteseek.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTextTest {

    @Test fun removesTopicMarkers() {
        assertEquals("第一次去意大利必看 #意大利旅行", cleanDesc("第一次去意大利必看 #意大利旅行[话题]#"))
    }

    @Test fun trimsBlankEdges() {
        assertEquals("正文", cleanDesc("  正文 \n"))
    }

    @Test fun extraTagsEmptyWhenAllInDesc() {
        assertEquals(emptyList<String>(), extraTags("正文 #意大利 #旅行", listOf("意大利", "旅行")))
    }

    @Test fun extraTagsKeepsMissingOnly() {
        assertEquals(listOf("美食"), extraTags("正文 #意大利", listOf("意大利", "美食")))
    }
}
