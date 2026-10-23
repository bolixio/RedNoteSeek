package com.rednoteseek.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSpacing
import kotlinx.coroutines.delay

/** 应用内提示的状态：一次只显示一条，新的替换旧的，停 2 秒后消失。 */
@Stable
class BsToastState {
    internal var text by mutableStateOf("")
        private set
    internal var visible by mutableStateOf(false)
        private set
    internal var id by mutableLongStateOf(0L)
        private set

    fun show(text: String) {
        this.text = text
        id++
        visible = true
    }

    internal fun hide(shownId: Long) {
        if (id == shownId) visible = false
    }
}

/**
 * 页面发提示：`LocalShowMessage.current("已收藏")`。由 AppShell 提供，提示位在底部导航（或系统导航条）上方。
 * 自带底栏的页面（笔记详情）和独立窗口（全屏大图）会被这个提示位压住或盖住，它们自己放 [BsToastHost]。
 */
val LocalShowMessage = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 提示位：胶囊、单行省略、淡入淡出。 */
@Composable
fun BsToastHost(state: BsToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.id) {
        if (state.visible) {
            delay(TOAST_MILLIS)
            state.hide(state.id)
        }
    }
    AnimatedVisibility(visible = state.visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = BsShapes.pill,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = ToastElevation,
        ) {
            Text(
                state.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = BsSpacing.xl, vertical = BsSpacing.md),
            )
        }
    }
}

private const val TOAST_MILLIS = 2_000L
private val ToastElevation = 6.dp
