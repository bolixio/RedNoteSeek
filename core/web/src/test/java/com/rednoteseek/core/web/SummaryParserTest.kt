package com.rednoteseek.core.web

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryParserTest {

    private fun captured(path: String, body: String, request: String = "", status: Int = 200, query: String = "") =
        CapturedResponse(path, request, status, body, query)

    /** 按推送 WebSocket 的格式包一层：每个 payload 做 base64 后放进 b.d.b[].d。 */
    private fun frame(vararg payloads: String): CapturedResponse {
        val items = payloads.joinToString(",") {
            """{"d":"${Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))}"}"""
        }
        return captured("ws/push", """{"v":1,"t":4,"m":"x","b":{"d":{"a":1,"b":[$items]}}}""", status = 101)
    }

    private fun davinci(type: String, fragments: String = "[]", conversation: String = "c1", domain: String = "dqa_chatsearch") =
        """{"type":"$type","data":{"bot_id":"davinci","conversation_id":"$conversation","domain_id":"$domain","data_fragments":$fragments}}"""

    @Test fun instantWithSummary() {
        val events = SummaryParser.parse(captured(
            "v1/dqa/instant",
            body = """{"code":0,"success":true,"simple_data":{"request_dqa_instant":true,"base_info":{"id":"1","text":"#### 胶囊机\n- **小巧**","totalCnt":"ai总结113篇笔记生成"},"elements":[]}}""",
            request = """{"search_request":{"keyword":"咖啡机推荐","page":1}}""",
        ))
        assertEquals(listOf(AiSummaryEvent.Instant("咖啡机推荐", "#### 胶囊机\n- **小巧**", "ai总结113篇笔记生成")), events)
    }

    @Test fun instantWithoutSummaryEmitsNothing() {
        val events = SummaryParser.parse(captured(
            "v1/dqa/instant",
            body = """{"code":0,"success":true,"request_dqa_instant":true,"simple_data":{"request_dqa_instant":true}}""",
            request = """{"search_request":{"keyword":"根特"}}""",
        ))
        assertTrue(events.isEmpty())
    }

    @Test fun failedInstantEmitsNothing() {
        assertTrue(SummaryParser.parse(captured("v1/dqa/instant", body = "", status = 461)).isEmpty())
    }

    @Test fun sendAiStartsGeneration() {
        val events = SummaryParser.parse(captured(
            "search/send/ai",
            body = """{"code":0,"success":true}""",
            request = """{"uuid":"u1","appCid":"c1","domain":"red","bizType":"dqa_chatsearch","contentInfo":{"contentType":101,"content":{"type":1,"data":"{ \"content\": \"根特\" }","summary":"根特"}}}""",
        ))
        assertEquals(listOf(AiSummaryEvent.Generating("根特", "c1")), events)
    }

    @Test fun streamFragments() {
        val events = SummaryParser.parse(frame(
            davinci("message", """[{"fragment_type":9,"thinking_progress_item":[{"progress_status":2,"progress_text":"问题分析中"},{"progress_status":1,"progress_text":"搜索相关内容"},{"progress_status":0,"progress_text":"总结答案"}]}]"""),
            davinci("message", """[{"fragment_type":10,"query_status":{"text":"ai总结58篇笔记生成"}}]"""),
            davinci("message", """[{"fragment_type":1,"index_in_msg":0,"text":"根特是"},{"fragment_type":11,"images":[]}]"""),
            davinci("signal"),
        ))
        assertEquals(
            listOf(
                AiSummaryEvent.Progress("c1", "搜索相关内容"),
                AiSummaryEvent.SourceCount("c1", "ai总结58篇笔记生成"),
                AiSummaryEvent.Delta("c1", "根特是"),
                AiSummaryEvent.Done("c1"),
            ),
            events,
        )
    }

    @Test fun ignoresOtherDomainsAndChatAgentFrames() {
        val chat = """{"type":"agent.stream.delta","data":{"session_id":"s1","payload":{"content_type":7}}}"""
        val events = SummaryParser.parse(frame(
            davinci("message", """[{"fragment_type":1,"text":"私信"}]""", domain = "im"),
            chat,
        ))
        assertTrue(events.isEmpty())
    }

    @Test fun malformedFrameIsIgnored() {
        assertTrue(SummaryParser.parse(captured("ws/push", "not json", status = 101)).isEmpty())
        assertTrue(SummaryParser.parse(captured("ws/push", """{"v":1,"t":4,"b":{"d":{"b":[{"d":"!!!"}]}}}""", status = 101)).isEmpty())
    }

    @Test fun sourcesFromDetail() {
        val events = SummaryParser.parse(captured(
            "v1/search/dqa/detail",
            body = """{"code":0,"success":true,"data":{"items":[
              {"id":"n1","title":"露营清单","nick_name":"万物清单","avatar":"http://sns-avatar-qc.xhscdn.com/a.jpg","like":1231,"type":"note",
               "image_list":[{"url":"http://sns-na-i27.xhscdn.com/c1.jpg","width":1152,"height":1536}]},
              {"id":"n2","title":"","nick_name":"b","avatar":"","like":5,"type":"video","image_list":[]}
            ]}}""",
            query = "message_id=1&conversation_id=c&query=%E9%9C%B2%E8%90%A5",
        ))
        assertEquals(
            listOf(
                AiSummaryEvent.Sources(
                    "露营",
                    listOf(
                        SourceNote("n1", "露营清单", "https://sns-na-i27.xhscdn.com/c1.jpg", 1152, 1536, "万物清单", "https://sns-avatar-qc.xhscdn.com/a.jpg", 1231, false),
                        SourceNote("n2", "", "", 0, 0, "b", "", 5, true),
                    ),
                ),
            ),
            events,
        )
    }

    @Test fun otherPathsAndAbortedRequestsAreIgnored() {
        assertTrue(SummaryParser.parse(captured("v2/search/notes", "{}")).isEmpty())
        assertTrue(SummaryParser.parse(captured("v1/dqa/instant", "{}", status = 0)).isEmpty())
    }
}
