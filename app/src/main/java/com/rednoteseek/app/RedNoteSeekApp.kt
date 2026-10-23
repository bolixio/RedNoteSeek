package com.rednoteseek.app

import android.app.Application
import com.rednoteseek.core.storage.AppContext
import com.rednoteseek.core.storage.SettingsStore
import com.rednoteseek.core.web.WebDotsChatSource
import com.rednoteseek.core.web.WebNoteListSource
import com.rednoteseek.core.web.WebNoteSource
import com.rednoteseek.core.web.XhsWebEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class RedNoteSeekApp : Application() {

    /** 全局唯一的网页引擎；首次访问时（主线程、首帧组合中）创建，读完登录缓存后由 Root 调 start 加载第一个页面。 */
    val webEngine: XhsWebEngine by lazy { XhsWebEngine(this, initialUrl = null) }

    /** 第二网页引擎：承载用户主页与「我」的收藏，避免打乱主引擎上的搜索页。初始不加载页面。 */
    val pageEngine: XhsWebEngine by lazy { XhsWebEngine(this, initialUrl = null, name = "page") }

    /**
     * 第二引擎的数据源与引擎同生命周期：它的打开计数（generation）与预热标记描述的是引擎当前的页面，
     * 放在 Activity 里会在重建（如系统切换深色模式）后变成新实例，计数归零、预热被重复触发。
     */
    val noteListSource: WebNoteListSource by lazy { WebNoteListSource(pageEngine) }

    private val _chatActive = MutableStateFlow(false)
    /** 对话引擎是否已创建：Root 据此把它的 WebView 挂到最底层（没用到点点会话就不创建第三个网页）。 */
    val chatActive: StateFlow<Boolean> = _chatActive.asStateFlow()

    /** 第三网页引擎：点点会话历史与对话，只加载 /ai_chat 页面。第一次用到时（主线程、组合中）才创建。 */
    val chatEngine: XhsWebEngine by lazy {
        XhsWebEngine(this, initialUrl = null, name = "chat").also { _chatActive.value = true }
    }

    /** 对话引擎的数据源（App 级，Activity 重建不变）。 */
    val chatSource: WebDotsChatSource by lazy { WebDotsChatSource(chatEngine) }

    /** 从点点回答的参考来源打开笔记：点的是对话引擎上来源抽屉里的卡片。 */
    val chatNoteSource: WebNoteSource by lazy { WebNoteSource(chatEngine) }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        // 旧直连栈的 CookieStore 曾把登录 Cookie 存在这里，已不再使用，删掉残留
        File(filesDir, "datastore/rednoteseek_prefs.preferences_pb").delete()
    }
}
