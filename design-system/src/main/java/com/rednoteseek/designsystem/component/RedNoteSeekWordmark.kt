package com.rednoteseek.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.rednoteseek.designsystem.R

private val Shrikhand = FontFamily(Font(R.font.shrikhand_regular))
private val WordmarkSize = 28.sp
private val WordmarkLineHeight = 34.sp

/**
 * 搜索页顶栏标题「RedNoteSeek」。
 * Shrikhand 复古招牌斜体，「Seek」取主题强调色，随动态取色与深色模式变化；单个 Text，读屏读作「RedNoteSeek」。
 */
@Composable
fun RedNoteSeekWordmark(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text = buildAnnotatedString {
            append("RedNote")
            withStyle(SpanStyle(color = accent)) { append("Seek") }
        },
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
        style = TextStyle(fontFamily = Shrikhand, fontSize = WordmarkSize, lineHeight = WordmarkLineHeight),
        maxLines = 1,
    )
}
