package com.rednoteseek.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rednoteseek.app.ui.AppShell
import com.rednoteseek.app.ui.NoteSrc
import com.rednoteseek.core.storage.SavedLogin
import com.rednoteseek.core.storage.ThemeMode
import com.rednoteseek.core.storage.follow.AccountFollow
import com.rednoteseek.core.storage.resolveDarkTheme
import com.rednoteseek.core.web.LoginState
import com.rednoteseek.core.web.SearchMode
import com.rednoteseek.core.web.WebNoteSource
import com.rednoteseek.core.web.WebSearchSource
import com.rednoteseek.core.web.XhsUrls
import com.rednoteseek.core.web.XhsWebViewHost
import com.rednoteseek.designsystem.component.BsLoadingState
import com.rednoteseek.designsystem.theme.BarStyle
import com.rednoteseek.designsystem.theme.BarStyleProvider
import com.rednoteseek.designsystem.theme.RedNoteSeekTheme
import com.rednoteseek.feature.auth.XhsLoginOverlay
import com.rednoteseek.feature.detail.NoteScreen
import com.rednoteseek.feature.dots.DotsChatScreen
import com.rednoteseek.feature.dots.DotsHistoryScreen
import com.rednoteseek.feature.profile.BoardScreen
import com.rednoteseek.feature.profile.BoardsScreen
import com.rednoteseek.feature.profile.CollectScreen
import com.rednoteseek.feature.profile.DynamicScreen
import com.rednoteseek.feature.profile.ProfileScreen
import com.rednoteseek.feature.search.SearchScreen
import com.rednoteseek.feature.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RedNoteSeekApp
        setContent {
            val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
            // 状态栏、导航条图标颜色跟随设置里的深色模式，而不只跟随系统
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B),
                    ) { darkTheme },
                )
                onDispose {}
            }
            RedNoteSeekTheme(darkTheme = darkTheme) { Root() }
        }
    }
}

@Composable
private fun Root() {
    val app = LocalContext.current.applicationContext as RedNoteSeekApp
    // 界面样式：读取用户自定义的顶/底栏缩放、颜色与透明度（DataStore 持久化）
    val topScale by app.settingsStore.topBarScale.collectAsStateWithLifecycle(initialValue = 1f)
    val bottomScale by app.settingsStore.bottomBarScale.collectAsStateWithLifecycle(initialValue = 1f)
    val barColorIndex by app.settingsStore.barColor.collectAsStateWithLifecycle(initialValue = 0)
    val topOpacity by app.settingsStore.topBarOpacity.collectAsStateWithLifecycle(initialValue = 1f)
    val bottomOpacity by app.settingsStore.bottomBarOpacity.collectAsStateWithLifecycle(initialValue = 1f)
    val barColor = com.rednoteseek.designsystem.theme.BarPalette.getOrNull(barColorIndex)?.second
    BarStyleProvider(
        style = BarStyle(topScale = topScale, bottomScale = bottomScale, barColor = barColor, topOpacity = topOpacity, bottomOpacity = bottomOpacity),
    ) {
    val engine = app.webEngine
    val pageEngine = app.pageEngine
    val session: SessionViewModel = viewModel()
    val loginState by engine.loginState.collectAsStateWithLifecycle()
    val qrLoginUrl by engine.qrLoginUrl.collectAsStateWithLifecycle()
    // 对话引擎第一次用到（进入点点会话 / 继续问）时才创建，之后才挂它的 WebView
    val chatActive by app.chatActive.collectAsStateWithLifecycle()
    // 三个引擎上收藏 / 专辑写操作成功的总次数：收藏页、专辑页据此判断列表是否过时
    val mainCollectChanges by engine.collectChanges.collectAsStateWithLifecycle()
    val pageCollectChanges by pageEngine.collectChanges.collectAsStateWithLifecycle()
    // 来源笔记在对话引擎上打开，它的收藏写操作也要计入；引擎未创建时不去触发创建
    val chatCollectChanges by remember(chatActive) {
        if (chatActive) app.chatEngine.collectChanges else flowOf(0)
    }.collectAsStateWithLifecycle(initialValue = 0)
    val collectChanges = mainCollectChanges + pageCollectChanges + chatCollectChanges
    val searchSource = remember(engine) { WebSearchSource(engine) }
    val noteSource = remember(engine) { WebNoteSource(engine) }
    val pageNoteSource = remember(pageEngine) { WebNoteSource(pageEngine) }
    val noteListSource = app.noteListSource
    // 两个引擎每次加载页面都会截获一份账号关注名单，以它为准同步本地关注表
    LaunchedEffect(engine, pageEngine) {
        merge(engine.following, pageEngine.following).filterNotNull().collect { users ->
            session.follows.sync(users.map { AccountFollow(it.userId, it.nickname, it.avatar, XhsUrls.profile(it.userId)) })
        }
    }
    // 启动不等 user/me：先用上次确认的账号进主界面；之后以 user/me 为准写回（游客即清除，退出登录也走这里）
    LaunchedEffect(engine) {
        val saved = app.settingsStore.lastLogin.first()
        saved?.let { engine.restoreLogin(it.userId, it.nickname, it.avatar) }
        // 登录过就直接停在第一次搜索能页内换词的页面，冷启动省掉一次整页加载：普通搜索用空的搜索结果页；
        // AI 总结用首页（空的 AI 结果页会被网页跳回首页，首页搜索框回车即进 AI 结果页）。没登录过打开首页登录
        val aiSummary = app.settingsStore.aiSummary.first()
        engine.start(if (saved != null && !aiSummary) XhsUrls.SEARCH_HOME else XhsUrls.HOME)
        engine.loginState.collect { state ->
            when (state) {
                is LoginState.LoggedIn -> app.settingsStore.setLastLogin(SavedLogin(state.userId, state.nickname, state.avatar))
                LoginState.Guest -> {
                    app.settingsStore.clearLastLogin()
                    // 缓存已过期而页面停在结果页：回首页登录（网页登录流程只在首页验证过）
                    if (engine.webView.url?.startsWith(XhsUrls.HOME) != true) engine.reloadHome()
                }
                LoginState.Unknown -> Unit
            }
        }
    }
    // 预热第二引擎：主引擎 user/me 确认登录 3 秒后先停在「收藏 › 专辑」（避开冷启动时与主引擎争抢；第二引擎已被用过则跳过）
    LaunchedEffect(pageEngine) {
        engine.loginConfirmed.first { it }
        delay(PREWARM_DELAY_MS)
        (engine.loginState.value as? LoginState.LoggedIn)?.let { noteListSource.prewarm(it.userId) }
    }
    // 预热后发生过收藏 / 专辑写操作：服务端渲染的专辑列表已过时
    LaunchedEffect(collectChanges) { noteListSource.discardWarm() }
    // 搜索页「AI 总结」开关：在 Root 读，搜索页从详情返回时不会因为重新读取而空一帧（丢滚动位置）；写回在搜索 ViewModel 的作用域
    val aiSummary by app.settingsStore.aiSummary.collectAsStateWithLifecycle<Boolean?>(initialValue = null)

    Box(Modifier.fillMaxSize()) {
        // 对话引擎（点点会话）挂在最底层，所有原生界面和另两个网页都会遮住它
        if (chatActive) XhsWebViewHost(app.chatEngine, Modifier.fillMaxSize().systemBarsPadding())
        // 第二引擎（主页 / 收藏）挂在更底层，必须先于主引擎：登录时主 WebView 要在上面可见
        XhsWebViewHost(pageEngine, Modifier.fillMaxSize().systemBarsPadding())
        // 网页引擎垫在最底层：登录时用户直接操作它；登录后被原生界面完全遮住，仍在后台加载页面
        XhsWebViewHost(engine, Modifier.fillMaxSize().systemBarsPadding())
        when (loginState) {
            LoginState.Unknown -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {},
            ) {
                // 一般几秒内就能连上；超过 5 秒才给「重试」，避免一进来就让人点
                var showRetry by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(5_000)
                    showRetry = true
                }
                BsLoadingState(
                    text = "正在连接小红书…",
                    action = if (showRetry) {
                        { TextButton(onClick = engine::reloadHome) { Text("重试") } }
                    } else {
                        null
                    },
                )
            }
            LoginState.Guest -> XhsLoginOverlay(
                qrLoginUrl = qrLoginUrl,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            is LoginState.LoggedIn -> AppShell(
                searchContent = { onNoteClick, onDotsHistory, onOpenChat, onAuthor ->
                    // 等读到开关再建页面：ViewModel 只在第一次创建时取初始模式
                    aiSummary?.let { on ->
                        SearchScreen(
                            searchSource = searchSource,
                            history = session.searchHistory,
                            onNoteClick = onNoteClick,
                            initialMode = if (on) SearchMode.AI else SearchMode.NOTES,
                            onModeChange = { mode -> app.settingsStore.setAiSummary(mode == SearchMode.AI) },
                            onDotsHistoryClick = onDotsHistory,
                            onContinueInChat = onOpenChat,
                            avatarUrl = (loginState as LoginState.LoggedIn).avatar,
                            onAvatarClick = { onAuthor(XhsUrls.profile((loginState as LoginState.LoggedIn).userId)) },
                        )
                    }
                },
                dynamicContent = { onNoteClick, onAuthor ->
                    DynamicScreen(
                        source = noteListSource,
                        follows = session.follows,
                        cache = session.dynamicCache,
                        selfUserId = (loginState as LoginState.LoggedIn).userId,
                        onNoteClick = onNoteClick,
                        onAuthorClick = onAuthor,
                    )
                },
                favoritesContent = { onAllClick, onBoardClick ->
                    BoardsScreen(
                        selfUserId = (loginState as LoginState.LoggedIn).userId,
                        source = noteListSource,
                        collectChanges = collectChanges,
                        onAllClick = onAllClick,
                        onBoardClick = onBoardClick,
                    )
                },
                allCollectContent = { onNoteClick, onBack ->
                    CollectScreen(
                        selfUserId = (loginState as LoginState.LoggedIn).userId,
                        source = noteListSource,
                        collectChanges = collectChanges,
                        onNoteClick = onNoteClick,
                        onBack = onBack,
                    )
                },
                boardContent = { boardId, name, onNoteClick, onBack ->
                    BoardScreen(boardId, name, noteListSource, collectChanges, onNoteClick, onBack)
                },
                noteContent = { noteId, src, order, preview, onBack, onAuthor ->
                    NoteScreen(
                        noteId = noteId,
                        searchOrder = order,
                        preview = preview,
                        noteSource = when (src) {
                            NoteSrc.SEARCH -> noteSource
                            NoteSrc.PAGE -> pageNoteSource
                            NoteSrc.CHAT -> app.chatNoteSource
                        },
                        downloader = session.downloader,
                        onBack = onBack,
                        onAuthorClick = onAuthor,
                    )
                },
                profileContent = { url, onNoteClick, onBack ->
                    ProfileScreen(
                        url = url,
                        source = noteListSource,
                        follows = session.follows,
                        selfUserId = (loginState as LoginState.LoggedIn).userId,
                        onNoteClick = onNoteClick,
                        onBack = onBack,
                    )
                },
                dotsHistoryContent = { onOpen, onBack ->
                    DotsHistoryScreen(source = app.chatSource, onOpen = onOpen, onBack = onBack)
                },
                dotsChatContent = { conversationId, title, onNoteClick, onBack ->
                    DotsChatScreen(
                        source = app.chatSource,
                        conversationId = conversationId,
                        title = title,
                        onNoteClick = { noteId, order -> onNoteClick(noteId, order, null) },
                        onBack = onBack,
                    )
                },
                settingsContent = {
                    val themeMode by app.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
                    val scope = rememberCoroutineScope()
                    SettingsScreen(
                                            nickname = (loginState as? LoginState.LoggedIn)?.nickname.orEmpty(),
                                            themeMode = themeMode,
                                            versionName = BuildConfig.VERSION_NAME,
                                            topBarScale = topScale,
                                            bottomBarScale = bottomScale,
                                            barColorIndex = barColorIndex,
                                            topBarOpacity = topOpacity,
                                            bottomBarOpacity = bottomOpacity,
                                            onTopBarScaleChange = { v -> scope.launch { app.settingsStore.setTopBarScale(v) } },
                                            onBottomBarScaleChange = { v -> scope.launch { app.settingsStore.setBottomBarScale(v) } },
                                            onBarColorChange = { idx -> scope.launch { app.settingsStore.setBarColor(idx) } },
                                            onTopBarOpacityChange = { v -> scope.launch { app.settingsStore.setTopBarOpacity(v) } },
                                            onBottomBarOpacityChange = { v -> scope.launch { app.settingsStore.setBottomBarOpacity(v) } },
                                            onThemeModeChange = { mode -> scope.launch { app.settingsStore.setThemeMode(mode) } },
                                            onClearHistory = { scope.launch { session.searchHistory.clear() } },
                                            onLogout = engine::logout,
                                        )
                },
            )
        }
    }
    }
}

private const val PREWARM_DELAY_MS = 3_000L
