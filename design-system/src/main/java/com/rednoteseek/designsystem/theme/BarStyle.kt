package com.rednoteseek.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 顶栏 / 底栏界面样式：整体缩放（图标文字等比自适应缩放）、背景色（预设色板）、背景不透明度。
 * 通过 [BarStyleProvider] 下发到全局；设置页调整后立即生效。
 */
@Immutable
data class BarStyle(
    val topScale: Float = 1f,
    val bottomScale: Float = 1f,
    /** null = 跟随主题背景色 */
    val barColor: Color? = null,
    val topOpacity: Float = 1f,
    val bottomOpacity: Float = 1f,
)

val LocalBarStyle = compositionLocalOf { BarStyle() }

/** 搜索结果网格列数。 */
val LocalGridColumns = compositionLocalOf { 2 }

/** 网页缩放比例（作用于 WebView 内容）。 */
val LocalPageZoom = compositionLocalOf { 1f }

/** 预设色板：浅色为主、与 RedNoteSeek 主题（墨绿+米白）搭配。第一项 null = 跟随主题。 */
val BarPalette: List<Pair<String, Color?>> = listOf(
    "跟随主题" to null,
    "米白" to Color(0xFFF7F4EC),
    "暖沙" to Color(0xFFF2EAD9),
    "浅豆绿" to Color(0xFFE4ECD9),
    "薄荷" to Color(0xFFDFF0E8),
    "浅雾蓝" to Color(0xFFE3EBF2),
    "藕粉" to Color(0xFFF6E8E4),
    "浅杏" to Color(0xFFF9EEDF),
    "淡藤紫" to Color(0xFFECE7F3),
    "纯白" to Color(0xFFFFFFFF),
)

/** 栏背景基础色：跟随主题时按深浅色取默认。 */
@Composable
fun barBaseColor(style: BarStyle): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return style.barColor ?: if (dark) Color(0xFF1B1D1A) else Color(0xFFEEF2EA)
}

/** 下发 [BarStyle] 到 Composition。 */
@Composable
fun BarStyleProvider(style: BarStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBarStyle provides style, content = content)
}

/** 底栏显隐信号：网页/列表滚动时隐藏，停止滚动 300ms 后显示。全局单例，滚动事件源调用 onScroll。 */
object BottomBarVisibility {
    private val _visible = androidx.compose.runtime.mutableStateOf(true)
    val visible: Boolean get() = _visible.value

    private var hideTask: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** 滚动事件：任何滚动（页面/WebView）都调用，立刻隐藏并在停止 300ms 后恢复。 */
    fun onScroll() {
        _visible.value = false
        hideTask?.cancel(false)
        hideTask = scheduler.schedule({ _visible.value = true }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}