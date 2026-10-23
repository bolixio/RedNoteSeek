package com.rednoteseek.designsystem.format

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleMarkdownTest {

    @Test fun headingsBulletsAndParagraphs() {
        val blocks = SimpleMarkdown.parse("选咖啡机要按需。\n\n#### 胶囊机\n- 小巧\n\n    \n---\n结尾")
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan("选咖啡机要按需。"))),
                MdBlock.Heading(listOf(MdSpan("胶囊机"))),
                MdBlock.Bullet(listOf(MdSpan("小巧"))),
                MdBlock.Paragraph(listOf(MdSpan("结尾"))),
            ),
            blocks,
        )
    }

    @Test fun boldAndUnderlineAcrossZeroWidthSpaces() {
        val blocks = SimpleMarkdown.parse("- \u200B**\u200B<u>牧高笛冷山</u>\u200B**\u200B：防风")
        assertEquals(
            listOf(MdBlock.Bullet(listOf(MdSpan("牧高笛冷山", bold = true, underline = true), MdSpan("：防风")))),
            blocks,
        )
    }

    @Test fun dqaEntityKeepsOnlyText() {
        val md = """- **::dqa:underline:{"text":"飞利浦HD7762","params":{"query":"飞利浦HD7762","keyword":"飞利浦HD7762"}}::**：千元内"""
        assertEquals(
            listOf(MdBlock.Bullet(listOf(MdSpan("飞利浦HD7762", bold = true), MdSpan("：千元内")))),
            SimpleMarkdown.parse(md),
        )
    }

    @Test fun boldLineIsNotABullet() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("最佳时间", bold = true), MdSpan("：傍晚")))),
            SimpleMarkdown.parse("**最佳时间**：傍晚"),
        )
    }

    @Test fun unclosedMarkersDuringStreaming() {
        // 流式输出时可能先到一半：未闭合的 ** 让后面的文字保持粗体，不报错
        assertEquals(
            listOf(MdBlock.Bullet(listOf(MdSpan("圣米歇尔", bold = true)))),
            SimpleMarkdown.parse("- **圣米歇尔"),
        )
    }
}
