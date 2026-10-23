package com.rednoteseek.feature.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage

/** 把 `[笑哭R]` 这类表情画成图片的文字；[prefix] 接在最前面（如楼中楼的「昵称：」）。 */
@Composable
internal fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    prefix: AnnotatedString? = null,
) {
    val parts = remember(text) { splitEmoji(text) }
    val annotated = remember(parts, prefix) {
        buildAnnotatedString {
            if (prefix != null) append(prefix)
            parts.forEach { part ->
                when (part) {
                    is TextPart.Plain -> append(part.text)
                    is TextPart.Emoji -> appendInlineContent(part.name, part.name)
                }
            }
        }
    }
    val inlineContent = remember(parts) {
        parts.filterIsInstance<TextPart.Emoji>().distinctBy { it.name }.associate { emoji ->
            emoji.name to InlineTextContent(Placeholder(EmojiSize, EmojiSize, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(model = emoji.url, contentDescription = emoji.name, modifier = Modifier.fillMaxSize())
            }
        }
    }
    Text(annotated, modifier, color = color, style = style, inlineContent = inlineContent)
}

// 与网页一致：表情比文字略大，和文字垂直居中
private val EmojiSize = 1.25.em
