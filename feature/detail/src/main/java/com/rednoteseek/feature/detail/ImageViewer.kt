package com.rednoteseek.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.rednoteseek.designsystem.component.BsInlineProgress
import com.rednoteseek.designsystem.component.BsToastHost
import com.rednoteseek.designsystem.component.BsToastState
import com.rednoteseek.designsystem.theme.BsSize
import com.rednoteseek.designsystem.theme.BsSpacing
import net.engawapg.lib.zoomable.ScrollGesturePropagation
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable

/**
 * 全屏大图：从 [startIndex] 开始，左右滑翻页，双指缩放、双击放大（放大后滑到边缘才翻页）。
 * 单击图片、左上角关闭或系统返回关闭；右下角只保存当前这一张。
 * 大图是独立窗口，会盖住 AppShell 的提示位，所以保存结果显示在自己的 [toast] 里。
 */
@Composable
internal fun ImageViewer(
    images: List<String>,
    startIndex: Int,
    saving: Boolean,
    toast: BsToastState,
    onSave: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val zoomState = rememberZoomState()
                AsyncImage(
                    model = images[page],
                    contentDescription = "第 ${page + 1} 张图",
                    contentScale = ContentScale.Fit,
                    onSuccess = { zoomState.setContentSize(it.painter.intrinsicSize) },
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomable(
                            zoomState,
                            scrollGesturePropagation = ScrollGesturePropagation.ContentEdge,
                            onTap = { onDismiss() },
                        ),
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(BsSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                }
                if (images.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        // 右边留出与关闭按钮同宽的空白，计数正好在屏幕中线
                        modifier = Modifier.weight(1f).padding(end = BsSize.touchTarget),
                    )
                }
            }
            IconButton(
                onClick = { onSave(pagerState.currentPage) },
                enabled = !saving,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.Black.copy(alpha = 0.5f),
                    disabledContentColor = Color.White,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(BsSpacing.lg),
            ) {
                if (saving) BsInlineProgress() else Icon(Icons.Outlined.Download, contentDescription = "保存这张图片")
            }
            BsToastHost(
                toast,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = BsSpacing.xl, vertical = BsSpacing.lg),
            )
        }
    }
}
