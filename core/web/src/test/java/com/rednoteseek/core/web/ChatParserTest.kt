package com.rednoteseek.core.web

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试数据裁剪自真机抓到的真实响应，用户 ID 换成了假的。 */
class ChatParserTest {

    private fun captured(path: String, body: String, request: String = "", status: Int = 200, query: String = "") =
        CapturedResponse(path, request, status, body, query)

    /** 按推送 WebSocket 的格式包一层：每个 payload 做 base64 后放进 b.d.b[].d。 */
    private fun frame(vararg payloads: String): CapturedResponse {
        val items = payloads.joinToString(",") {
            """{"d":"${Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))}"}"""
        }
        return captured("ws/push", """{"v":1,"t":4,"m":"x","b":{"d":{"a":1,"b":[$items]}}}""", status = 101)
    }

    @Test fun conversationListGroupsByTimeHeader() {
        val events = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/list",
            body = """{"data":{"cursor":0,"hasMore":true,"items":[
              {"id":"今天","itemType":"spark","spark":{"templateName":"search_agent_conversation_time","dslData":{"title":"今天"}}},
              {"id":"6a86dec3-fc82-4f99-8acf-b766824721de","itemType":"spark","updateTimestamp":1792611706333,"spark":{"dslData":{"question":"西班牙地铁购票攻略与推荐","sessionId":"6a86dec3-fc82-4f99-8acf-b766824721de"},"templateName":"search_agent_conversation_item"}},
              {"id":"7天内","itemType":"spark","spark":{"templateName":"search_agent_conversation_time","dslData":{"title":"7天内"}}},
              {"id":"318ba0f4-7ece-4646-af9f-3375ef9e808c","itemType":"spark","updateTimestamp":1792000000000,"spark":{"dslData":{"question":"帐篷推荐"},"templateName":"search_agent_conversation_item"}}
            ]},"code":0,"success":true,"msg":"成功"}""",
            query = "biz=xhs_dots_pc&pageFrom=0&size=20&preCursor=0",
        ))
        assertEquals(
            listOf(
                ChatEvent.Conversations(
                    listOf(
                        DotsConversation("6a86dec3-fc82-4f99-8acf-b766824721de", "西班牙地铁购票攻略与推荐", 1792611706333, "今天"),
                        // 没有 dslData.sessionId 时用条目的 id
                        DotsConversation("318ba0f4-7ece-4646-af9f-3375ef9e808c", "帐篷推荐", 1792000000000, "7天内"),
                    ),
                    hasMore = true,
                ),
            ),
            events,
        )
    }

    @Test fun historyIsReversedAndAnswerPartsMerged() {
        val events = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/detail",
            body = """{"code":0,"success":true,"msg":"成功","data":{"has_more":false,"messages":[
              {"msg_id":"ai-msg_17ca934dc1374b39","sender_id":"diandian-agent","session_id":"s1","timestamp":1792611657099,"msg_info":{"payloads":[
                {"content_type":9,"message_type":"ai_thinking","thinking":{"action":"搜索","content":"正在搜 “帐篷 推荐 口碑 露营”"}}]}},
              {"msg_id":"ai-msg_17ca934dc1374b39","sender_id":"diandian-agent","session_id":"s1","timestamp":1792611657099,"msg_info":{"payloads":[
                {"content_type":6,"message_type":"ai_answer","base_deeplink":{"underline":"xhsdiscover://search/result"}},
                {"content_type":8,"message_type":"ai_answer","elements":[
                  {"element_type":"spark","spark":{"id":"dots_source_header","template_name":"dots_source_header","data":{"sub_title":"参考80篇笔记","queryTitle":"参考80篇笔记"}}},
                  {"element_type":"markdown","markdown":{"text":"看了下站内口碑，帐篷这东西没有\"万能神帐\"。\n\n#### 徒步 / 轻量化帐篷\n"}},
                  {"spark":{"template_name":"search_agent_image_text_list_v2","data":{"image_single_list":[{"images":[{"note_id":"681f5e730000000012004d08","source_nickname":"小谢同学"}]}]}}},
                  {"element_type":"markdown","markdown":{"text":"\n\n徒步党核心看重量。"}}
                ]}]}},
              {"msg_id":"msg_17ca934dc1374b39","sender_id":"u1","session_id":"s1","timestamp":1792611657018,"msg_info":{"payloads":[
                {"content_type":4,"message_type":"user_input","inputs":[{"type":"TEXT","info":{"text":"帐篷有哪些口碑好的推荐笔记？"}}]}]}},
              {"msg_id":"ai-f220bfaf7737e5a66ab366c1deadc285","sender_id":"diandian-agent","session_id":"s1","timestamp":1792610234840,"msg_info":{"payloads":[
                {"content_type":6,"message_type":"ai_answer"},
                {"content_type":8,"message_type":"ai_answer","elements":[
                  {"element_type":"spark","spark":{"id":"dots_source_header","template_name":"dots_source_header","data":{"sub_title":"ai总结48篇笔记生成"}}}]}],
                "extra":{"anchor_type":"SEARCH_OFFLINE"}}},
              {"msg_id":"f220bfaf7737e5a66ab366c1deadc285","sender_id":"u1","session_id":"s1","timestamp":1792610234839,"msg_info":{"payloads":[
                {"content_type":4,"message_type":"user_input","inputs":[{"type":"TEXT","info":{"text":"西班牙公共交通"}}]}]}}
            ]}}""",
            query = "session_id=s1&biz=xhs_dots_pc&cursor=&count=20",
        ))
        assertEquals(
            listOf(
                ChatEvent.History(
                    "s1",
                    listOf(
                        ChatMessage.User("西班牙公共交通"),
                        // 搜索页 instant 总结：网页不保存正文，只有参考标签
                        ChatMessage.Answer("", "ai总结48篇笔记生成"),
                        ChatMessage.User("帐篷有哪些口碑好的推荐笔记？"),
                        // 思考条目与回答条目同 msg_id 合并；商品卡片不显示；正文片段用空行拼接
                        ChatMessage.Answer(
                            "看了下站内口碑，帐篷这东西没有\"万能神帐\"。\n\n#### 徒步 / 轻量化帐篷\n\n徒步党核心看重量。",
                            "参考80篇笔记",
                        ),
                    ),
                ),
            ),
            events,
        )
    }

    @Test fun thinkingOnlyAnswerIsKeptAsStopped() {
        // 中途停止的回答在历史里只剩思考步骤：记为停止的空回答，回答序号才能与网页的 .ai-message 对齐
        val events = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/detail",
            body = """{"code":0,"success":true,"data":{"has_more":false,"messages":[
              {"msg_id":"ai-msg_2","sender_id":"diandian-agent","msg_info":{"payloads":[{"content_type":9,"thinking":{"action":"分析","content":""}}]}},
              {"msg_id":"msg_2","sender_id":"u1","msg_info":{"payloads":[{"content_type":4,"inputs":[{"type":"TEXT","info":{"text":"问"}}]}]}}
            ]}}""",
            query = "session_id=s1",
        ))
        assertEquals(
            listOf(ChatEvent.History("s1", listOf(ChatMessage.User("问"), ChatMessage.Answer("", null, stopped = true)))),
            events,
        )
    }

    @Test fun historyReadsContentType7ElementAndMarksType17Unsupported() {
        // 真实数据：追问回答 / 被终止的回答是 content_type 7 的单个 element，旅行攻略卡片是没有内容的 content_type 17
        val events = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/detail",
            body = """{"code":0,"success":true,"data":{"has_more":false,"messages":[{"timestamp":1792676570431,"msg_info":{"payloads":[{"content_type":17,"message_type":"ai_answer"}]},"current_version":1,"session_id":"6a86dec3-fc82-4f99-8acf-b766824721de","msg_id":"ai-msg_a5a45c5ee2734740","sender_id":"diandian-agent","status":1,"max_version":1,"reply_to":"msg_a5a45c5ee2734740"},{"msg_id":"msg_a5a45c5ee2734740","sender_id":"u1","status":1,"current_version":1,"max_version":1,"reply_to":"msg_a5a45c5ee2734740","session_id":"6a86dec3-fc82-4f99-8acf-b766824721de","timestamp":1792676570380,"msg_info":{"payloads":[{"content_type":4,"inputs":[{"type":"TEXT","info":{"text":"Only day 1 in Madrid please"}}],"message_type":"user_input"}]}},{"session_id":"6a86dec3-fc82-4f99-8acf-b766824721de","msg_id":"ai-msg_799300df811c4eaa","timestamp":1792676410226,"status":1,"current_version":1,"max_version":1,"sender_id":"diandian-agent","msg_info":{"payloads":[{"content_type":6,"message_type":"ai_answer"},{"content_type":7,"element":{"element_type":"markdown","markdown":{"text":"你终止了本次回答，试试重新问我吧"}},"message_type":"ai_answer"}]},"reply_to":"msg_799300df811c4eaa"},{"sender_id":"u1","msg_info":{"payloads":[{"content_type":4,"inputs":[{"type":"TEXT","info":{"text":"Plan a detailed 7 day Spain itinerary with budget"}}],"message_type":"user_input"}]},"reply_to":"msg_799300df811c4eaa","msg_id":"msg_799300df811c4eaa","timestamp":1792676410173,"status":1,"current_version":1,"max_version":1,"session_id":"6a86dec3-fc82-4f99-8acf-b766824721de"},{"timestamp":1792676369560,"sender_id":"diandian-agent","current_version":1,"reply_to":"msg_bdcfe1387cb84b6b","session_id":"6a86dec3-fc82-4f99-8acf-b766824721de","msg_id":"ai-msg_bdcfe1387cb84b6b","msg_info":{"payloads":[{"content_type":6,"message_type":"ai_answer"},{"content_type":7,"element":{"element_type":"markdown","markdown":{"text":"3天马德里，最划算的就是买一张10次Metrobus卡（Tarjeta Mult"}},"message_type":"ai_answer"}]},"status":1,"max_version":1},{"status":1,"current_version":1,"msg_id":"msg_bdcfe1387cb84b6b","max_version":1,"reply_to":"msg_bdcfe1387cb84b6b","session_id":"6a86dec3-fc82-4f99-8acf-b766824721de","timestamp":1792676369506,"sender_id":"u1","msg_info":{"payloads":[{"content_type":4,"inputs":[{"type":"TEXT","info":{"text":"Which one for 3 days in Madrid?"}}],"message_type":"user_input"}]}}]}}""",
            query = "session_id=s1",
        ))
        val messages = (events.single() as ChatEvent.History).messages
        assertEquals(6, messages.size)
        assertEquals(ChatMessage.User("Which one for 3 days in Madrid?"), messages[0])
        assertTrue((messages[1] as ChatMessage.Answer).markdown.startsWith("3天马德里，最划算的就是买一张10次Metrobus卡"))
        assertEquals(ChatMessage.User("Plan a detailed 7 day Spain itinerary with budget"), messages[2])
        assertEquals(ChatMessage.Answer("你终止了本次回答，试试重新问我吧", null), messages[3])
        assertEquals(ChatMessage.User("Only day 1 in Madrid please"), messages[4])
        assertEquals(ChatMessage.Answer("", null, unsupported = true), messages[5])
    }

    @Test fun failedHistoryEmitsNothing() {
        assertTrue(ChatParser.parse(captured("sns/agentspark/web/conversation/detail", "", status = 461, query = "session_id=s1")).isEmpty())
    }

    @Test fun streamEvents() {
        val events = ChatParser.parse(frame(
            """{"type":"agent.stream.start","biz":"xhs_dots_pc","seq":0,"data":{"msg_id":"msg_1-start","session_id":"s1","sender_id":"diandian-agent","payload":{"content_type":9,"message_type":"ai_thinking","thinking":{"action":"分析","content":""}}}}""",
            """{"type":"agent.stream.delta","seq":1,"data":{"msg_id":"ai-msg_1-rt-1","session_id":"s1","payload":{"content_type":9,"message_type":"ai_thinking","thinking":{"action":"search_request","content":"正在搜 “马德里 地铁票”"}}}}""",
            """{"type":"agent.stream.delta","seq":7,"data":{"msg_id":"ai-msg_1","session_id":"s1","payload":{"content_type":6,"base_deeplink":{"underline":"xhsdiscover://search/result"}}}}""",
            """{"type":"agent.stream.delta","seq":8,"data":{"msg_id":"ai-msg_1","session_id":"s1","payload":{"element":{"element_type":"spark","spark":{"id":"dots_source_header","template_name":"dots_source_header","data":{"sub_title":"参考85篇笔记"}}},"content_type":7,"message_type":"ai_answer"}}}""",
            """{"type":"agent.stream.delta","seq":9,"data":{"msg_id":"ai-msg_1","session_id":"s1","payload":{"content_type":7,"element":{"element_type":"markdown","markdown":{"text":"西班牙没有全国统一的地铁票，"}},"message_type":"ai_answer"}}}""",
            """{"type":"agent.stream.delta","seq":20,"data":{"msg_id":"ai-msg_1","session_id":"s1","payload":{"content_type":7,"element":{"element_type":"spark","spark":{"template_name":"search_agent_image_text_list_v2","data":{}}},"message_type":"ai_answer"}}}""",
            """{"type":"agent.stream.signal","seq":10,"data":{"msg_id":"ai-msg_1","session_id":"s1","signal":"rn-notification","payload":{"event_data":{"card_id":"ai-msg_1"},"event_key":"source_panel_data_ready"}}}""",
            """{"type":"agent.stream.end","seq":62,"data":{"msg_id":"msg_1-end","session_id":"s1","finish_reason":"stop","guide_actions":[]}}""",
        ))
        assertEquals(
            listOf(
                // start 的思考内容为空，用 action
                ChatEvent.Thinking("s1", "分析"),
                ChatEvent.Thinking("s1", "正在搜 “马德里 地铁票”"),
                ChatEvent.SourceCount("s1", "参考85篇笔记"),
                ChatEvent.Delta("s1", "西班牙没有全国统一的地铁票，"),
                ChatEvent.Finished("s1", cancelled = false),
            ),
            events,
        )
    }

    @Test fun cancelledStreamAndAckFrames() {
        val events = ChatParser.parse(frame(
            """{"request_id":"a211102c-0ba5-4ebc-972f-9917369fb34f","code":0,"msg":"成功"}""",
            """{"type":"agent.stream.end","seq":21,"data":{"msg_id":"msg_3-end","session_id":"s2","finish_reason":"cancel"}}""",
        ))
        assertEquals(listOf(ChatEvent.Finished("s2", cancelled = true)), events)
    }

    @Test fun ignoresSearchSummaryFrames() {
        // 第一期 AI 搜索页的实时生成（davinci）没有 agent.stream. 前缀
        val davinci = """{"type":"message","data":{"bot_id":"davinci","conversation_id":"c1","domain_id":"dqa_chatsearch","data_fragments":[{"fragment_type":1,"text":"根特是"}]}}"""
        assertTrue(ChatParser.parse(frame(davinci, """{"type":"signal","data":{"conversation_id":"c1","domain_id":"dqa_chatsearch"}}""")).isEmpty())
    }

    @Test fun deleteReply() {
        val ok = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/delete",
            body = """{"success":true,"code":0}""",
            request = """{"biz":"xhs_dots_pc","sessionIds":["s1"]}""",
        ))
        assertEquals(listOf(ChatEvent.Deleted(listOf("s1"), ok = true)), ok)
        val failed = ChatParser.parse(captured(
            "sns/agentspark/web/conversation/delete",
            body = """{"success":false,"code":-1}""",
            request = """{"biz":"xhs_dots_pc","sessionIds":["s1"]}""",
        ))
        assertEquals(listOf(ChatEvent.Deleted(listOf("s1"), ok = false)), failed)
    }

    @Test fun complexSources() {
        val events = ChatParser.parse(captured(
            "sns/agentspark/web/v1/dqa/complex/source",
            body = """{"code":0,"success":true,"msg":"成功","data":{"total":27,"info":{"card_id":"ai-msg_1","title":"参考来源","sub_title":"共 27 条来源","has_more_sources":true,"sources":[
              {"user":{"nick_name":"聊露营的大头","avatar":"https://sns-avatar-qc.xhscdn.com/avatar/a1.jpg","user_id":"u1"},"id":"6a43b8fa0000000011005d97","type":"video",
               "note_info":{"id":"6a43b8fa0000000011005d97","title":"26年帐篷推荐第一期，200到1000价位","video":{"height":1920,"width":1080},
                 "image_list":[{"width":1080,"height":1439,"url":"http://ci.xiaohongshu.com/1040g0083221qh7j86s505q22f0p6r5phg9n70lg?imageView2/2/w/1080/format/jpg"}]},
               "interact_info":{"liked":false,"like_count":549}},
              {"user":{"nick_name":"山野装备志","avatar":"https://sns-avatar-qc.xhscdn.com/avatar/a2.jpg","user_id":"u2"},"id":"6a90500500000000260367cb","type":"note",
               "note_info":{"id":"6a90500500000000260367cb","title":"Hilleberg③｜最经典的6顶帐篷",
                 "image_list":[{"width":941,"height":1672,"url":"http://ci.xiaohongshu.com/notes_pre_post/1040g3k0324cguub3n43g5o276el0braibgmket0"}]},
               "interact_info":{"liked":false,"like_count":19}}
            ]}}}""",
            query = "card_id=ai-msg_1",
        ))
        assertEquals(
            listOf(
                ChatEvent.Sources(
                    listOf(
                        SourceNote(
                            "6a43b8fa0000000011005d97", "26年帐篷推荐第一期，200到1000价位",
                            "https://ci.xiaohongshu.com/1040g0083221qh7j86s505q22f0p6r5phg9n70lg?imageView2/2/w/1080/format/jpg", 1080, 1439,
                            "聊露营的大头", "https://sns-avatar-qc.xhscdn.com/avatar/a1.jpg", 549, true,
                        ),
                        SourceNote(
                            "6a90500500000000260367cb", "Hilleberg③｜最经典的6顶帐篷",
                            "https://ci.xiaohongshu.com/notes_pre_post/1040g3k0324cguub3n43g5o276el0braibgmket0", 941, 1672,
                            "山野装备志", "https://sns-avatar-qc.xhscdn.com/avatar/a2.jpg", 19, false,
                        ),
                    ),
                ),
            ),
            events,
        )
    }

    @Test fun searchSummarySourcesFromDqaDetail() {
        // 对话页里搜索总结那条回答的来源走 v1/search/dqa/detail，与第一期同一段解析
        val events = ChatParser.parse(captured(
            "v1/search/dqa/detail",
            body = """{"code":0,"success":true,"data":{"items":[
              {"id":"6a5b8977000000000401d8f3","title":"西班牙公交车攻略","nick_name":"小o","avatar":"https://sns-avatar-qc.xhscdn.com/avatar/a3.jpg","like":33,"type":"note","user_id":"u3",
               "image_list":[{"url":"http://sns-na-i11.xhscdn.com/1040g008322p2oct67a6g5o9th08gj0arojf5b7o","width":2560,"height":1708}]}
            ]}}""",
            query = "message_id=3510501089937911561&conversation_id=c&query=%E8%A5%BF%E7%8F%AD%E7%89%99",
        ))
        assertEquals(
            listOf(
                ChatEvent.Sources(
                    listOf(
                        SourceNote(
                            "6a5b8977000000000401d8f3", "西班牙公交车攻略",
                            "https://sns-na-i11.xhscdn.com/1040g008322p2oct67a6g5o9th08gj0arojf5b7o", 2560, 1708,
                            "小o", "https://sns-avatar-qc.xhscdn.com/avatar/a3.jpg", 33, false,
                        ),
                    ),
                ),
            ),
            events,
        )
    }

    @Test fun otherPathsAndAbortedRequestsAreIgnored() {
        assertTrue(ChatParser.parse(captured("v2/search/notes", "{}")).isEmpty())
        assertTrue(ChatParser.parse(captured("sns/agentspark/web/conversation/list", "{}", status = 0)).isEmpty())
        assertTrue(ChatParser.parse(captured("ws/push", "not json", status = 101)).isEmpty())
    }

    @Test fun askTabStateParsesPathAndConversation() {
        assertEquals(AskTabState("/ai_chat_tab", "c-1"), AskTabState.parse("""{"path":"/ai_chat_tab/","conversationId":"c-1"}"""))
        assertEquals(AskTabState("/search_result_ai", ""), AskTabState.parse("""{"path":"/search_result_ai","conversationId":""}"""))
        assertNull(AskTabState.parse("null"))
        assertNull(AskTabState.parse(""))
    }
}
