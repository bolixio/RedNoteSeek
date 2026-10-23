package com.rednoteseek.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.component.MarkdownText
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing

/** 结果网格顶部的点点 AI 总结：默认折叠，生成中先显示进度、再逐字出现；生成完才能看参考来源、继续问点点。 */
@Composable
internal fun AiSummaryCard(
    summary: SearchViewModel.Summary,
    onSourcesClick: () -> Unit,
    continuing: Boolean,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val progress = summary.progress ?: if (summary.streaming && summary.markdown.isEmpty()) "点点正在总结…" else null
    Surface(shape = BsShapes.md, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(BsSpacing.md), verticalArrangement = Arrangement.spacedBy(BsSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(BsSize.iconSm),
                )
                Spacer(Modifier.width(BsSpacing.xs))
                Text("点点总结", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!summary.streaming) {
                    TextButton(onClick = onSourcesClick) {
                        Text(sourceText(summary.sourceLabel), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onContinueClick, enabled = !continuing) {
                        if (continuing) BsInlineProgress() else Text("继续问点点", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            progress?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BsInlineProgress()
                    Spacer(Modifier.width(BsSpacing.sm))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (summary.markdown.isNotEmpty()) {
                MarkdownText(
                    summary.markdown,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                    onOverflowChange = { overflow = it },
                )
                if (overflow || expanded) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
            }
        }
    }
}

/** 「ai总结58篇笔记生成」→「参考 58 篇笔记」。 */
private fun sourceText(label: String?): String =
    label?.let { Regex("\\d+").find(it)?.value }?.let { "参考 $it 篇笔记" } ?: "参考笔记"

private const val COLLAPSED_LINES = 6
