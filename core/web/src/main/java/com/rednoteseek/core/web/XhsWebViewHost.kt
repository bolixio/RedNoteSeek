package com.rednoteseek.core.web

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 把引擎的 WebView 挂进界面。WebView 必须真正挂载并有实际尺寸，页面懒加载才会工作；
 * 登录后由上层原生界面完全遮住即可（真机已验证遮住时滚动仍能触发翻页）。
 */
@Composable
fun XhsWebViewHost(engine: XhsWebEngine, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            engine.attachTo(context)
            // Activity 重建时 WebView 还挂在旧的宿主上，先摘下来
            (engine.webView.parent as? ViewGroup)?.removeView(engine.webView)
            // 必须显式 MATCH_PARENT：默认的 WRAP_CONTENT 会让 Chromium 把 CSS 的 100vh 算成 0，
            // 笔记弹层（height:100vh）高度为 0、评论区滚不动，评论翻页永远不触发（真机验证）
            engine.webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            engine.webView
        },
        modifier = modifier,
        onRelease = {
            // 摘下 WebView，避免全局 WebView 通过 parent 链持有已销毁的 Activity
            (it.parent as? ViewGroup)?.removeView(it)
            engine.detach()
        },
    )
}
