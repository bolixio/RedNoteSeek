package com.rednoteseek.core.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * 全局唯一的 xhs 网页引擎（B3 方案）。
 *
 * 持有一个 WebView 打开真实网页：签名、Cookie、设备指纹都由页面自己处理，App 不直连 API。
 * document-start 钩子把 /api/sns/web/ 的请求和响应（及关注名单、推送 WebSocket 的业务帧）经 WebMessageListener 回传，发布到 [responses]。
 * 第二引擎（主页 / 收藏）传 null，按需加载。[name] 只用于 debug 包的耗时日志。
 * 必须在主线程创建和调用。
 */
class XhsWebEngine(appContext: Context, initialUrl: String? = XhsUrls.HOME, private val name: String = "main") {

    /** WebView 显示时切换为宿主 Activity，避免长期持有已销毁的 Activity。 */
    private val context = MutableContextWrapper(appContext.applicationContext)

    // 点点 AI 一次回答有近百个推送帧，连续到达时 64 条缓冲不够
    private val _responses = MutableSharedFlow<CapturedResponse>(extraBufferCapacity = 256)
    /** 截获到的所有 /api/sns/web/ 响应、关注名单与推送 WebSocket 的业务帧（点点 AI）（热流，不重放）。 */
    val responses: SharedFlow<CapturedResponse> = _responses.asSharedFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Unknown)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()
    private val _loginConfirmed = MutableStateFlow(false)
    /** 本次运行中 user/me 是否已确认登录；[restoreLogin] 的缓存态不算。 */
    val loginConfirmed: StateFlow<Boolean> = _loginConfirmed.asStateFlow()

    private val _qrLoginUrl = MutableStateFlow<String?>(null)
    /** 最近一次网页登录二维码的内容，可交给小红书 App 打开以确认登录。 */
    val qrLoginUrl: StateFlow<String?> = _qrLoginUrl.asStateFlow()

    private val followingPages = FollowingAccumulator()
    private val _following = MutableStateFlow<List<FollowingUser>?>(null)
    /** 最近一次截获的完整账号关注名单（网页每次加载时消息模块都会预取）；尚未截获时为 null。 */
    val following: StateFlow<List<FollowingUser>?> = _following.asStateFlow()

    private val _collectChanges = MutableStateFlow(0)
    /** 收藏 / 专辑写操作（收藏、取消收藏、加入专辑、专辑增删改）成功的次数，收藏页据此判断是否需要重新加载。 */
    val collectChanges: StateFlow<Int> = _collectChanges.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 桌面页面按桌面宽度布局后整体缩放，懒加载、评论容器等行为与 PC 版一致
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString = desktopUserAgent(WebSettings.getDefaultUserAgent(context))
        webViewClient = XhsWebViewClient(::traceEvent) { blockMedia }
    }

    /**
     * 不让网页下载笔记图片和视频（原生界面自己下载）。启动时就拦截：登录态要等 user/me 返回才知道，
     * 那时首页封面早已下完；确认是游客（用户要看着网页登录）才放开。在 WebView 的 IO 线程读取。
     */
    @Volatile private var blockMedia = true

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var traceAction = ""
    private var traceAt = 0L

    init {
        WebView.setWebContentsDebuggingEnabled(debuggable)
        check(
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) { "系统 WebView 版本过低，请在应用商店更新 Android System WebView" }

        WebViewCompat.addWebMessageListener(webView, PageScripts.BRIDGE_NAME, setOf(XhsUrls.ORIGIN)) { _, message, _, _, _ ->
            message.data?.let { raw ->
            // 滚动信号直接短路处理，不进解析器（body 为空会被丢弃）
            if (raw.contains("__scroll__")) {
                BottomBarVisibility.onScroll()
                return@addWebMessageListener
            }
            raw.let(CapturedResponse::parse)?.let(::onCaptured)
        }
        }
        // 必须在首次 loadUrl 之前注册，钩子才会先于页面脚本执行
        WebViewCompat.addDocumentStartJavaScript(webView, PageScripts.HOOK, setOf(XhsUrls.ORIGIN))
        initialUrl?.let(::load)
    }

    fun attachTo(hostContext: Context) {
        context.baseContext = hostContext
    }

    fun detach() {
        context.baseContext = context.applicationContext
    }

    fun load(url: String) {
        traceAction("load ${url.substringAfter(XhsUrls.ORIGIN).substringBefore('?')}")
        webView.loadUrl(url)
    }

    fun reloadHome() = webView.loadUrl(XhsUrls.HOME)

    /** 网页内容缩放：CSS zoom 对整页布局生效（含视频、图片、文字）。 */
    fun applyZoom(scale: Float) {
        val s = scale.coerceIn(0.5f, 1.2f)
        webView.evaluateJavascript("document.documentElement.style.zoom = '$s';", null)
    }

    private var started = false

    /** 第一次加载页面；只生效一次（Activity 重建时调用方会再调用）。 */
    fun start(url: String) {
        if (started) return
        started = true
        load(url)
    }

    /** 分步滚动整页，触发页面加载下一页。 */
    fun scrollPage() {
        traceAction("scroll page")
        webView.evaluateJavascript(PageScripts.SCROLL_PAGE, null)
    }

    /**
     * debug 包的耗时日志（logcat 标签 [TRACE_TAG]）：记下一次网页操作并开始计时，
     * 之后的页面加载完成、截获响应等事件都打印距这次操作的毫秒数。
     */
    fun traceAction(action: String) {
        if (!debuggable) return
        traceAction = action
        traceAt = SystemClock.elapsedRealtime()
        Log.d(TRACE_TAG, "[$name] → $action")
    }

    /** 打印一个事件距最近一次 [traceAction] 的耗时。 */
    fun traceEvent(event: String) {
        if (!debuggable) return
        Log.d(TRACE_TAG, "[$name]   +${SystemClock.elapsedRealtime() - traceAt}ms $event  (after: $traceAction)")
    }

    /** 在页面里执行脚本并取回结果（已解码为字符串）。 */
    suspend fun evaluate(js: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(decodeJsResult(raw)) }
    }

    /** 在页面里执行脚本，不关心结果。 */
    fun runScript(js: String) = webView.evaluateJavascript(js, null)

    /** 启动时先用上次确认的账号显示主界面；只在还没截获 user/me 时生效，之后以 user/me 为准。 */
    fun restoreLogin(userId: String, nickname: String, avatar: String) {
        _loginState.compareAndSet(LoginState.Unknown, LoginState.LoggedIn(userId, nickname, avatar))
    }

    /** 清空 WebView 的 Cookie 和本地存储后回到首页；页面重新请求 user/me 后状态变为游客。 */
    fun logout() {
        CookieManager.getInstance().removeAllCookies {
            WebStorage.getInstance().deleteAllData()
            _loginState.value = LoginState.Unknown
            _loginConfirmed.value = false
            blockMedia = false
            _qrLoginUrl.value = null
            reloadHome()
        }
    }

    private fun onCaptured(response: CapturedResponse) {
        traceEvent("captured ${response.path} ${response.status}")
        when (response.path) {
            ApiPaths.USER_ME -> LoginParsers.loginState(response.body)?.let {
                // 刚登录成功时立刻把 Cookie 写盘：WebView 约 30 秒才落盘一次，其间进程被杀会丢掉登录
                if (it is LoginState.LoggedIn && _loginState.value !is LoginState.LoggedIn) CookieManager.getInstance().flush()
                _loginState.value = it
                _loginConfirmed.value = it is LoginState.LoggedIn
                blockMedia = it !is LoginState.Guest
            }
            ApiPaths.QRCODE_CREATE -> LoginParsers.qrLoginUrl(response.body)?.let { _qrLoginUrl.value = it }
            ApiPaths.FOLLOWING_ALL -> followingPages.add(response)?.let { _following.value = it }
            ApiPaths.BOARD, ApiPaths.NOTE_MOVE, ApiPaths.NOTE_COLLECT, ApiPaths.NOTE_UNCOLLECT ->
                if (WriteReply.ok(response)) _collectChanges.value++
        }
        _responses.tryEmit(response)
    }

    private class XhsWebViewClient(
        private val trace: (String) -> Unit,
        private val blockMedia: () -> Boolean,
    ) : WebViewClient() {
        /**
         * 图片返回 1×1 透明 GIF：网页看到的是加载成功，布局（卡片高度由数据决定）和曝光统计照常；
         * 视频直接拒绝，App 用 ExoPlayer 自己播放。
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame || !blockMedia()) return null
            return when (XhsUrls.mediaKind(request.url.toString())) {
                XhsUrls.Media.IMAGE -> WebResourceResponse(
                    "image/gif", null, 200, "OK", CORS_HEADERS, ByteArrayInputStream(TRANSPARENT_GIF),
                )
                XhsUrls.Media.VIDEO -> WebResourceResponse(
                    "text/plain", null, 403, "Forbidden", CORS_HEADERS, ByteArrayInputStream(ByteArray(0)),
                )
                null -> null
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = trace("page started")

        override fun onPageFinished(view: WebView, url: String) = trace("page finished")

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // 搜索页会被重定向到 http，App 禁止明文流量，改回 https 重新加载
            val https = XhsUrls.toHttps(url)
            if (request.isForMainFrame && https != url) {
                view.loadUrl(https)
                return true
            }
            // 拦下 xhsdiscover:// 等拉起 App 的跳转，放行 http/https/about/blob/data
            return request.url.scheme !in setOf("http", "https", "about", "blob", "data")
        }
    }

    companion object {
        const val TRACE_TAG = "XhsTiming"

        private val CORS_HEADERS = mapOf("Access-Control-Allow-Origin" to "*")

        /** 1×1 透明 GIF。 */
        private val TRANSPARENT_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
    }
}
