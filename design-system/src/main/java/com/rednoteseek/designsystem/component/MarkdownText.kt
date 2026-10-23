package com.rednoteseek.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.rednoteseek.designsystem.format.MdBlock
import com.rednoteseek.designsystem.format.MdSpan
import com.rednoteseek.designsystem.format.SimpleMarkdown

/**
 * 显示 [SimpleMarkdown] 支持的 markdown 子集。整段渲染为一个 Text，才能用 [maxLines] 折叠；
 * [onOverflowChange] 报告折叠后是否有内容被截断（据此显示「展开」）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onOverflowChange: (Boolean) -> Unit = {},
) {
    val heading = MaterialTheme.typography.titleSmall.toSpanStyle()
    val text = remember(markdown, heading) { render(SimpleMarkdown.parse(markdown), heading) }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { onOverflowChange(it.hasVisualOverflow) },
    )
}

private fun render(blocks: List<MdBlock>, heading: SpanStyle): AnnotatedString = buildAnnotatedString {
    blocks.forEachIndexed { i, block ->
        // 标题前空一行，与上一节分开
        if (i > 0) append(if (block is MdBlock.Heading) "\n\n" else "\n")
        when (block) {
            is MdBlock.Heading -> withStyle(heading) { appendSpans(block.spans) }
            is MdBlock.Bullet -> {
                append("•  ")
                appendSpans(block.spans)
            }
            is MdBlock.Paragraph -> appendSpans(block.spans)
        }
    }
}

private fun AnnotatedString.Builder.appendSpans(spans: List<MdSpan>) = spans.forEach { s ->
    withStyle(
        SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            textDecoration = if (s.underline) TextDecoration.Underline else null,
        ),
    ) { append(s.text) }
}
