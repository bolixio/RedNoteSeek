package com.rednoteseek.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class XhsEmojiTest {

    @Test fun splitsKnownEmojiAndKeepsUnknownAsText() {
        assertEquals(
            listOf(
                TextPart.Plain("好"),
                TextPart.Emoji("[笑哭R]", "https://picasso-static.xiaohongshu.com/fe-platform/ca75b2fc85b0a3e171fe5df1cbf90efdcd3ba571.png"),
                TextPart.Plain("啊[未知]"),
            ),
            splitEmoji("好[笑哭R]啊[未知]"),
        )
    }

    @Test fun adjacentEmojisAndEdges() {
        assertEquals(
            listOf("[doge]", "[赞R]"),
            splitEmoji("[doge][赞R]").map { (it as TextPart.Emoji).name },
        )
        assertEquals(emptyList<TextPart>(), splitEmoji(""))
        assertEquals(listOf(TextPart.Plain("没有表情 [")), splitEmoji("没有表情 ["))
    }

    @Test fun tableHasAllWebEmojis() {
        assertEquals(166, XhsEmoji.urls.size)
        assertEquals(
            "https://picasso-static.xiaohongshu.com/fe-platform/abc0a1cd8434c5348e89e887cf8a4f93f352558c.png",
            XhsEmoji.urls["[玫瑰R]"],
        )
    }
}
