package com.rednoteseek.core.network.api

import com.rednoteseek.core.network.XhsHttp
import com.rednoteseek.core.network.XhsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStreamTest {

    private fun resource(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun video(json: String): NoteVideo? {
        val serializer = XhsResponse.serializer(NoteDetailResult.serializer())
        return XhsHttp.json.decodeFromString(serializer, json).data!!.items.first().note_card.video
    }

    @Test fun prefersMp4FromRealFeed() {
        assertEquals(
            "http://sns-video-zl.xhscdn.com/stream/79/110/259/01ea01e7e718700b010370039e1773bc56_259.mp4?sign=test",
            video(resource("feed_video_v1.json"))!!.playableUrl(),
        )
    }

    @Test fun fallsBackToFmp4() {
        val json = """{"data":{"items":[{"id":"n","note_card":{"video":{"media":{"stream":{
            "EF4":[],"EF5":[{"format":"fmp4","master_url":"http://x/a.m4s"}]}}}}}]}}"""
        assertEquals("http://x/a.m4s", video(json)!!.playableUrl())
    }

    @Test fun emptyStreamsGiveNull() {
        val json = """{"data":{"items":[{"id":"n","note_card":{"video":{"media":{"stream":{"EF4":[],"EF5":[]}}}}}]}}"""
        assertNull(video(json)!!.playableUrl())
    }
}
