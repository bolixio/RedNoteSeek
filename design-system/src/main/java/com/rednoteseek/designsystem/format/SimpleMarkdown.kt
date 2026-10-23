package com.rednoteseek.designsystem.format

/** 一段行内文字及其样式。 */
data class MdSpan(val text: String, val bold: Boolean = false, val underline: Boolean = false)

/** 一行 markdown 解析出的块。 */
sealed interface MdBlock {
    val spans: List<MdSpan>

    data class Heading(override val spans: List<MdSpan>) : MdBlock
    data class Bullet(override val spans: List<MdSpan>) : MdBlock
    data class Paragraph(override val spans: List<MdSpan>) : MdBlock
}

/**
 * 点点 AI 回答用到的 markdown 子集：`#` 标题、`-` / `*` 列表、`**粗体**`、`<u>下划线</u>`、`---` 分隔线（丢弃），
 * 以及实体标记 `::dqa:underline:{"text":"…",…}::`（只保留 text）。零宽空格去掉，空行只用来分段。
 */
object SimpleMarkdown {
    private val ENTITY = Regex("""::dqa:underline:(\{.*?\})::""")
    private val ENTITY_TEXT = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^[-*]\s+(.*)$""")
    private val RULE = Regex("""^(-{3,}|\*{3,})$""")
    private val INLINE = Regex("""\*\*|<u>|</u>""")

    fun parse(markdown: String): List<MdBlock> {
        val text = ENTITY.replace(markdown.replace("\u200B", "")) { m ->
            ENTITY_TEXT.find(m.groupValues[1])?.groupValues?.get(1)?.replace("\\\"", "\"").orEmpty()
        }
        return text.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || RULE.matches(line)) return@mapNotNull null
            HEADING.matchEntire(line)?.let { return@mapNotNull MdBlock.Heading(inline(it.groupValues[1])) }
            BULLET.matchEntire(line)?.let { return@mapNotNull MdBlock.Bullet(inline(it.groupValues[1])) }
            MdBlock.Paragraph(inline(line))
        }
    }

    /** `**` 切换粗体，`<u>` / `</u>` 开关下划线；未闭合的标记一直作用到行尾（流式输出时常见）。 */
    private fun inline(line: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var bold = false
        var underline = false
        var last = 0
        fun flush(end: Int) {
            if (end > last) spans += MdSpan(line.substring(last, end), bold, underline)
        }
        INLINE.findAll(line).forEach { m ->
            flush(m.range.first)
            when (m.value) {
                "**" -> bold = !bold
                "<u>" -> underline = true
                else -> underline = false
            }
            last = m.range.last + 1
        }
        flush(line.length)
        return spans
    }
}
