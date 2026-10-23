package com.rednoteseek.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.designsystem.component.BsToastHost
import com.rednoteseek.designsystem.component.BsToastState
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.theme.BsSpacing

/** 点击列表里的笔记：笔记 ID、列表顺序（定位网页卡片用）、卡片上已有的内容。 */
typealias NoteClick = (noteId: String, order: List<String>, preview: NoteCard?) -> Unit

/** 笔记详情所在的网页引擎：主引擎（搜索页）、第二引擎（主页 / 收藏 / 专辑）或对话引擎（点点回答的参考来源）。 */
enum class NoteSrc { SEARCH, PAGE, CHAT }

/** 四个底部标签 + 压栈的笔记详情、用户主页、全部收藏、专辑、点点会话与对话（打开时隐藏底部导航栏）。 */
enum class Destination(val route: String, val label: String) {
    SEARCH("search", "搜索"),
    DYNAMIC("dynamic", "动态"),
    FAVORITES("favorites", "收藏"),
    SETTINGS("settings", "设置"),
    NOTE("note/{noteId}?src={src}", "笔记"),
    PROFILE("profile?url={url}", "主页"),
    COLLECT_ALL("collect_all", "全部收藏"),
    BOARD("board/{boardId}?name={name}", "专辑"),
    DOTS_HISTORY("dots_history", "点点会话"),
    DOTS_CHAT("dots_chat/{conversationId}?title={title}", "点点对话"),
    ;

    val isTab: Boolean get() = this == SEARCH || this == DYNAMIC || this == FAVORITES || this == SETTINGS

    /** 底部导航图标：选中实心、未选中描边。 */
    fun navIcon(selected: Boolean): ImageVector = when (this) {
        SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
        DYNAMIC -> if (selected) Icons.Filled.DynamicFeed else Icons.Outlined.DynamicFeed
        FAVORITES -> if (selected) Icons.Filled.Bookmarks else Icons.Outlined.Bookmarks
        SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
        NOTE, PROFILE, COLLECT_ALL, BOARD, DOTS_HISTORY, DOTS_CHAT -> Icons.Outlined.Search
    }

    companion object {
        fun noteRoute(noteId: String, src: NoteSrc): String = "note/$noteId?src=${src.name}"

        fun profileRoute(url: String): String = "profile?url=" + Uri.encode(url)

        fun boardRoute(boardId: String, name: String): String = "board/$boardId?name=" + Uri.encode(name)

        fun dotsChatRoute(conversationId: String, title: String): String =
            "dots_chat/" + Uri.encode(conversationId) + "?title=" + Uri.encode(title)
    }
}

/**
 * App 外壳：NavHost + 底部导航。各页面内容由调用方以 lambda 传入，外壳不关心页面内部。
 * 切换标签时保存 / 恢复各页状态（搜索结果与滚动位置不丢）。
 */
@Composable
fun AppShell(
    searchContent: @Composable (
        onNoteClick: NoteClick,
        onDotsHistory: () -> Unit,
        onOpenChat: (conversationId: String, title: String) -> Unit,
        onAuthorClick: (url: String) -> Unit,
    ) -> Unit,
    dynamicContent: @Composable (onNoteClick: NoteClick, onAuthorClick: (url: String) -> Unit) -> Unit,
    favoritesContent: @Composable (onAllClick: () -> Unit, onBoardClick: (boardId: String, name: String) -> Unit) -> Unit,
    allCollectContent: @Composable (onNoteClick: NoteClick, onBack: () -> Unit) -> Unit,
    boardContent: @Composable (boardId: String, name: String, onNoteClick: NoteClick, onBack: () -> Unit) -> Unit,
    noteContent: @Composable (noteId: String, src: NoteSrc, order: List<String>, preview: NoteCard?, onBack: () -> Unit, onAuthor: (url: String) -> Unit) -> Unit,
    profileContent: @Composable (url: String, onNoteClick: NoteClick, onBack: () -> Unit) -> Unit,
    dotsHistoryContent: @Composable (onOpen: (conversationId: String, title: String) -> Unit, onBack: () -> Unit) -> Unit,
    dotsChatContent: @Composable (conversationId: String, title: String, onNoteClick: NoteClick, onBack: () -> Unit) -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val current = Destination.entries.firstOrNull { it.route == currentDestination?.route }
    val hideBottomBar = current != null && !current.isTab
    // 各来源最近一次点击笔记时的列表顺序，交给详情页定位网页里的卡片
    val orders = remember { mutableStateMapOf<NoteSrc, List<String>>() }
    // 各来源最近一次点击的笔记 ID 与卡片内容，详情到达前先显示
    val previews = remember { mutableStateMapOf<NoteSrc, Pair<String, NoteCard?>>() }

    fun openNote(src: NoteSrc, noteId: String, order: List<String>, preview: NoteCard?) {
        orders[src] = order
        previews[src] = noteId to preview
        navController.navigate(Destination.noteRoute(noteId, src)) { launchSingleTop = true }
    }

    fun openTab(dest: Destination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val toast = remember { BsToastState() }
    val showMessage = remember(toast) { { text: String -> toast.show(text) } }
    CompositionLocalProvider(LocalShowMessage provides showMessage) {
        Scaffold(
            bottomBar = {
                if (!hideBottomBar) {
                    NavigationBar {
                        Destination.entries.filter { it.isTab }.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { openTab(dest) },
                                icon = { Icon(dest.navIcon(selected), contentDescription = null) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.SEARCH.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(Destination.SEARCH.route) { entry ->
                        searchContent(
                            { noteId, order, preview ->
                                // 转场动画期间旧页面仍可点击，连点会复用详情页打开第二篇：只在本页仍是栈顶时导航
                                if (navController.currentBackStackEntry?.id != entry.id) return@searchContent
                                openNote(NoteSrc.SEARCH, noteId, order, preview)
                            },
                            {
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.DOTS_HISTORY.route) { launchSingleTop = true }
                                }
                            },
                            { conversationId, title ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.dotsChatRoute(conversationId, title)) { launchSingleTop = true }
                                }
                            },
                            { url ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.profileRoute(url)) { launchSingleTop = true }
                                }
                            },
                        )
                    }
                    composable(Destination.DYNAMIC.route) { entry ->
                        dynamicContent(
                            { noteId, order, preview ->
                                if (navController.currentBackStackEntry?.id != entry.id) return@dynamicContent
                                openNote(NoteSrc.PAGE, noteId, order, preview)
                            },
                            { url ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.profileRoute(url)) { launchSingleTop = true }
                                }
                            },
                        )
                    }
                    composable(Destination.FAVORITES.route) { entry ->
                        favoritesContent(
                            {
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.COLLECT_ALL.route) { launchSingleTop = true }
                                }
                            },
                            { boardId, name ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.boardRoute(boardId, name)) { launchSingleTop = true }
                                }
                            },
                        )
                    }
                    composable(Destination.COLLECT_ALL.route) { entry ->
                        allCollectContent(
                            { noteId, order, preview ->
                                if (navController.currentBackStackEntry?.id != entry.id) return@allCollectContent
                                openNote(NoteSrc.PAGE, noteId, order, preview)
                            },
                            { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                        )
                    }
                    composable(
                        Destination.BOARD.route,
                        arguments = listOf(
                            navArgument("boardId") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        boardContent(
                            entry.arguments?.getString("boardId").orEmpty(),
                            entry.arguments?.getString("name").orEmpty(),
                            { noteId, order, preview ->
                                if (navController.currentBackStackEntry?.id != entry.id) return@boardContent
                                openNote(NoteSrc.PAGE, noteId, order, preview)
                            },
                            { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                        )
                    }
                    composable(Destination.DOTS_HISTORY.route) { entry ->
                        dotsHistoryContent(
                            { conversationId, title ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.dotsChatRoute(conversationId, title)) { launchSingleTop = true }
                                }
                            },
                            { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                        )
                    }
                    composable(
                        Destination.DOTS_CHAT.route,
                        arguments = listOf(
                            navArgument("conversationId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        dotsChatContent(
                            entry.arguments?.getString("conversationId").orEmpty(),
                            entry.arguments?.getString("title").orEmpty(),
                            { noteId, order, preview ->
                                if (navController.currentBackStackEntry?.id != entry.id) return@dotsChatContent
                                openNote(NoteSrc.CHAT, noteId, order, preview)
                            },
                            { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                        )
                    }
                    composable(Destination.SETTINGS.route) { settingsContent() }
                    composable(
                        Destination.NOTE.route,
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("src") { type = NavType.StringType; defaultValue = NoteSrc.SEARCH.name },
                        ),
                    ) { entry ->
                        val src = NoteSrc.entries.firstOrNull { it.name == entry.arguments?.getString("src") } ?: NoteSrc.SEARCH
                        val noteId = entry.arguments?.getString("noteId").orEmpty()
                        noteContent(
                            noteId,
                            src,
                            orders[src].orEmpty(),
                            previews[src]?.takeIf { it.first == noteId }?.second,
                            {
                                // 转场中重复返回会多弹一层：只在本页仍是栈顶时出栈
                                // （不用生命周期判断：按下时 entry 可能不在 RESUMED，会吞掉返回）
                                if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack()
                            },
                            { url ->
                                if (navController.currentBackStackEntry?.id == entry.id) {
                                    navController.navigate(Destination.profileRoute(url)) { launchSingleTop = true }
                                }
                            },
                        )
                    }
                    composable(
                        Destination.PROFILE.route,
                        arguments = listOf(navArgument("url") { type = NavType.StringType }),
                    ) { entry ->
                        profileContent(
                            entry.arguments?.getString("url").orEmpty(),
                            { noteId, order, preview ->
                                if (navController.currentBackStackEntry?.id != entry.id) return@profileContent
                                openNote(NoteSrc.PAGE, noteId, order, preview)
                            },
                            { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() },
                        )
                    }
                }
                // 唯一的提示位：有底部导航时在导航栏上方，否则在系统导航条上方
                BsToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(horizontal = BsSpacing.xl, vertical = BsSpacing.lg))
            }
        }
    }
}
