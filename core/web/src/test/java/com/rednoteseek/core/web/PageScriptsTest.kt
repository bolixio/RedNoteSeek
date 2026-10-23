package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 上没有 JS 引擎，这里做结构检查：括号配对、try / catch 配对。
 * 语法错误的脚本在 evaluateJavascript 里只会静默返回 null（真机上曾因此导致所有笔记都打不开）。
 */
class PageScriptsTest {

    private val scripts = mapOf(
        "HOOK" to PageScripts.HOOK,
        "SCROLL_PAGE" to PageScripts.SCROLL_PAGE,
        "CLOSE_NOTE" to PageScripts.CLOSE_NOTE,
        "SCROLL_COMMENTS" to PageScripts.SCROLL_COMMENTS,
        "OPEN_SEARCH_FILTER" to PageScripts.OPEN_SEARCH_FILTER,
        "PROFILE_SNAPSHOT" to PageScripts.PROFILE_SNAPSHOT,
        "COLLECT_TAB" to PageScripts.COLLECT_TAB,
        "COLLECT_NOTES_SUBTAB" to PageScripts.COLLECT_NOTES_SUBTAB,
        "BOARD_LIST" to PageScripts.BOARD_LIST,
        "BOARD_SNAPSHOT" to PageScripts.BOARD_SNAPSHOT,
        "BOARD_DETAILS" to PageScripts.BOARD_DETAILS,
        "PAGE_LOCATION" to PageScripts.PAGE_LOCATION,
        "AUTHOR_LINK" to PageScripts.AUTHOR_LINK,
        "MARK_STALE" to PageScripts.MARK_STALE,
        "PAGE_FRESH" to PageScripts.PAGE_FRESH,
        "followButton" to PageScripts.followButton(true),
        "FOLLOWING_LIST" to PageScripts.FOLLOWING_LIST,
        "CLOSE_RELATION_PANEL" to PageScripts.CLOSE_RELATION_PANEL,
        "openBoardForm" to PageScripts.openBoardForm(true),
        "submitBoardForm" to PageScripts.submitBoardForm(BoardForm("旅行", "简介", public = false)),
        "DELETE_BOARD" to PageScripts.DELETE_BOARD,
        "collectButton" to PageScripts.collectButton(true),
        "JOIN_BOARD" to PageScripts.JOIN_BOARD,
        "SCROLL_BOARD_PANEL" to PageScripts.SCROLL_BOARD_PANEL,
        "pickBoard" to PageScripts.pickBoard(3, "旅行"),
        "CLOSE_BOARD_PANEL" to PageScripts.CLOSE_BOARD_PANEL,
        "reportCards" to PageScripts.reportCards("n1"),
        "clickCard" to PageScripts.clickCard("n1"),
        "scrollTo" to PageScripts.scrollTo(100),
        "pickSearchSort" to PageScripts.pickSearchSort(SearchSort.LATEST),
        "searchInPage" to PageScripts.searchInPage("咖啡"),
        "SEARCH_KEYWORD" to PageScripts.SEARCH_KEYWORD,
        "aiSearchInPage" to PageScripts.aiSearchInPage("咖啡"),
        "AI_SEARCH_KEYWORD" to PageScripts.AI_SEARCH_KEYWORD,
        "OPEN_SUMMARY_SOURCES" to PageScripts.OPEN_SUMMARY_SOURCES,
        "CLOSE_SUMMARY_SOURCES" to PageScripts.CLOSE_SUMMARY_SOURCES,
        "expandReplies" to PageScripts.expandReplies("c1"),
        "readCachedComments" to PageScripts.readCachedComments("n1"),
        "chatFill" to PageScripts.chatFill("西班牙地铁票怎么买"),
        "CHAT_SUBMIT" to PageScripts.CHAT_SUBMIT,
        "CHAT_STOP" to PageScripts.CHAT_STOP,
        "chatDelete" to PageScripts.chatDelete(2, "西班牙地铁购票攻略与推荐"),
        "chatOpenSources" to PageScripts.chatOpenSources(1),
        "ASK_TAB" to PageScripts.ASK_TAB,
        "ASK_TAB_STATE" to PageScripts.ASK_TAB_STATE,
        "HISTORY_BACK" to PageScripts.HISTORY_BACK,
    )

    @Test fun bracketsAreBalanced() {
        scripts.forEach { (name, js) ->
            assertEquals("$name {}", js.count { it == '{' }, js.count { it == '}' })
            assertEquals("$name ()", js.count { it == '(' }, js.count { it == ')' })
            assertEquals("$name []", js.count { it == '[' }, js.count { it == ']' })
        }
    }

    @Test fun everyCatchHasATry() {
        scripts.forEach { (name, js) ->
            assertEquals("$name try/catch", Regex("""\btry\s*\{""").findAll(js).count(), Regex("""(?<!\.)\bcatch\s*\(""").findAll(js).count())
        }
    }

    @Test fun listPagePatternMatchesOnlyListRequests() {
        val pattern = Regex(PageScripts.LIST_PAGE_PATTERN)
        listOf(
            "https://edith.xiaohongshu.com/api/sns/web/v2/search/notes",
            "/api/sns/web/v1/user_posted?num=30&cursor=abc&user_id=u1",
            "/api/sns/web/v2/note/collect/page?num=30&cursor=&user_id=u1",
            "/api/sns/web/v1/board/note?board_id=b1&cursor=c&num=30",
        ).forEach { assertTrue(it, pattern.containsMatchIn(it)) }
        listOf(
            "/api/sns/web/v1/board/user?num=15",
            "/api/sns/web/v1/board",
            "/api/sns/web/v2/comment/page?note_id=n1",
            "/api/sns/web/v1/feed",
            "/api/sns/web/v2/search/notes/extra",
        ).forEach { assertTrue(it, !pattern.containsMatchIn(it)) }
    }

    @Test fun scrollSkipsWhileNextPageIsLoading() {
        // HOOK 记录在途的列表翻页请求，SCROLL_PAGE 在有在途请求时不滚、滚动中出现在途请求就停
        assertTrue(PageScripts.HOOK.contains("__rnsListPending"))
        assertTrue(PageScripts.SCROLL_PAGE.contains("__rnsListPending"))
    }

    @Test fun hookForwardsPushSocketBusinessFrames() {
        // 点点 AI 的流式回答只走推送 WebSocket；只转发业务帧（t 为 4），心跳等不转发
        assertTrue(PageScripts.HOOK.contains("window.WebSocket = HookedWS"))
        assertTrue(PageScripts.HOOK.contains("apppush-rws."))
        assertTrue(PageScripts.HOOK.contains("'\"t\":4'"))
    }

    @Test fun jsStringEscapesUserText() {
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\"", PageScripts.jsString("a\"b\\c\n\u2028"))
        val js = PageScripts.submitBoardForm(BoardForm("'); alert(1); ('", "x", public = true))
        assertTrue(js.contains("set(input, \"'); alert(1); ('\");"))
    }

    @Test fun searchInPageEscapesKeyword() {
        val js = PageScripts.searchInPage("\"); alert(1); (\"")
        assertTrue(js.contains("set.call(i, \"\\\"); alert(1); (\\\"\");"))
        // 只在验证过的结果页上用：/explore 的搜索框会进 AI 搜索页，/search_result_ai 未验证
        assertTrue(js.contains("'/search_result'") && js.contains("'/search_result/'"))
        assertTrue(js.contains("'keypress'"))
    }

    @Test fun aiSearchInPageEscapesKeywordAndUsesTextarea() {
        val js = PageScripts.aiSearchInPage("\"); alert(1); (\"")
        assertTrue(js.contains("set.call(i, \"\\\"); alert(1); (\\\"\");"))
        assertTrue(js.contains("#search-input-ai") && js.contains("HTMLTextAreaElement"))
        // AI 结果页与 /explore 的搜索框都会进 AI 结果页
        assertTrue(js.contains("'/search_result_ai'") && js.contains("'/explore'"))
        assertTrue(js.contains("'keydown', 'keypress', 'keyup'"))
    }

    @Test fun aiKeywordOnlyOnAiResultPage() {
        assertTrue(PageScripts.AI_SEARCH_KEYWORD.contains("'/search_result_ai'"))
        assertTrue(!PageScripts.SEARCH_KEYWORD.contains("_ai"))
    }

    @Test fun scrollScriptsUseAiFeedsContainer() {
        // AI 结果页滚动的是 .ai-feeds-page 容器，不是 window
        assertTrue(PageScripts.SCROLL_PAGE.contains(".ai-feeds-page"))
        assertTrue(PageScripts.reportCards("n1").contains(".ai-feeds-page"))
        assertTrue(PageScripts.scrollTo(100).contains(".ai-feeds-page"))
    }

    @Test fun cardScriptsLookInSourceDrawerFirst() {
        // 来源抽屉的卡片没有 data-note-id，按封面链接里的笔记 ID 匹配
        val selector = ".drawer section.note-item a.cover[href*=\"/n1?\"]"
        assertTrue(PageScripts.reportCards("n1").contains(selector))
        assertTrue(PageScripts.clickCard("n1").contains(selector))
    }

    @Test fun pageLocationReportsSubTab() {
        assertTrue(PageScripts.PAGE_LOCATION.contains("sub: new URLSearchParams(location.search).get('subTab') || ''"))
    }

    @Test fun collectNotesSubTabSkipsTopLevelTabs() {
        // 顶层标签（笔记 / 收藏 / 点赞）带 sub-tab-list，收藏下的「笔记・N」不带
        assertTrue(PageScripts.COLLECT_NOTES_SUBTAB.contains(".reds-tab-item:not(.sub-tab-list)"))
    }

    @Test fun hookCapturesAgentsparkApis() {
        // 点点会话的列表 / 历史 / 删除 / 来源接口不在 /api/sns/web/ 下
        assertTrue(PageScripts.HOOK.contains("""\/api\/sns\/agentspark\/web\/"""))
    }

    @Test fun chatFillEscapesTextAndFindsBothInputs() {
        val js = PageScripts.chatFill("\"); alert(1); (\"")
        assertTrue(js.contains("var t = \"\\\"); alert(1); (\\\"\";"))
        assertTrue(js.contains("set.call(ta, t);"))
        // 会话中与欢迎页的输入框不同
        assertTrue(js.contains(".input-container textarea") && js.contains(".ai-chat-welcome__input textarea"))
        assertTrue(js.contains("HTMLTextAreaElement"))
        // 加载新页面前打过标记的旧文档不操作
        assertTrue(js.contains("if (window.__rnsStale) return 'missing';"))
    }

    @Test fun chatFillHandlesTravelRichInput() {
        val js = PageScripts.chatFill("西班牙地铁票怎么买")
        // 旅游攻略技能模式下输入框是 contenteditable div
        assertTrue(js.contains(".xhs-travel-rich-input"))
        assertTrue(js.contains("InputEvent"))
    }

    @Test fun chatSubmitNeverClicksStopButton() {
        // 生成中发送按钮变成停止（pause-mode），发送脚本绝不能点它
        assertTrue(PageScripts.CHAT_SUBMIT.contains(".submit-button-wrapper:not(.disabled):not(.pause-mode)"))
        assertTrue(PageScripts.CHAT_SUBMIT.contains(".wendian-btn--sendable"))
        assertTrue(PageScripts.CHAT_SUBMIT.contains("if (window.__rnsStale) return 'pending';"))
        assertTrue(PageScripts.CHAT_STOP.contains(".submit-button-wrapper.pause-mode"))
    }

    @Test fun chatDeleteChecksTitleBeforeClicking() {
        val js = PageScripts.chatDelete(2, "a\"b")
        assertTrue(js.contains("items[2]"))
        assertTrue(js.contains("!== \"a\\\"b\""))
        // 侧边栏要派发 pointer / mouse 事件才会展开，单纯 click() 不行
        assertTrue(js.contains("'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'"))
        assertTrue(js.contains(".xhs-ai-history-item-close"))
        assertTrue(js.contains("if (window.__rnsStale) return 'loading';"))
    }

    @Test fun chatOpenSourcesPicksAnswerByIndex() {
        val js = PageScripts.chatOpenSources(1)
        assertTrue(js.contains("querySelectorAll('.ai-message')[1]"))
        assertTrue(js.contains("button.xhs-v3-tool-call-capsule") && js.contains(".xhs-spark-source-header-text"))
        // 旅行技能回答的「思考完成」按钮不是来源入口，只认「搜索并总结N人」
        assertTrue(js.contains("/总结\\d+人/"))
    }

    @Test fun askTabOnlyOnAiResultPage() {
        assertTrue(PageScripts.ASK_TAB.contains("'/search_result_ai'") && PageScripts.ASK_TAB.contains("问点点"))
        assertTrue(PageScripts.ASK_TAB_STATE.contains("get('conversationId')"))
    }
}
