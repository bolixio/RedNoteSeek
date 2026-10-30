package com.rednoteseek.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 不能用 "rednoteseek_prefs"：那是旧版本存 Cookie 的文件，App 启动时会删除
private val Context.settingsDataStore by preferencesDataStore(name = "rednoteseek_settings")

/** 上次 user/me 确认已登录的账号：启动时先用它显示主界面，不等网页加载完。 */
data class SavedLogin(val userId: String, val nickname: String, val avatar: String = "")

/** 深色模式：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** App 设置：深色模式、搜索页 AI 总结开关与上次登录的账号。 */
class SettingsStore(private val context: Context) {

    private val themeModeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[themeModeKey]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = mode.name }
    }

    private val aiSummaryKey = booleanPreferencesKey("ai_summary")

    /** 搜索页「AI 总结」开关：开启时搜索走点点 AI 结果页。 */
    val aiSummary: Flow<Boolean> = context.settingsDataStore.data.map { it[aiSummaryKey] ?: false }

    suspend fun setAiSummary(on: Boolean) {
        context.settingsDataStore.edit { it[aiSummaryKey] = on }
    }

    private val loginUserIdKey = stringPreferencesKey("login_user_id")
    private val loginNicknameKey = stringPreferencesKey("login_nickname")
    private val loginAvatarKey = stringPreferencesKey("login_avatar")

    val lastLogin: Flow<SavedLogin?> = context.settingsDataStore.data.map { prefs ->
        prefs[loginUserIdKey]?.takeIf { it.isNotEmpty() }?.let { SavedLogin(it, prefs[loginNicknameKey].orEmpty(), prefs[loginAvatarKey].orEmpty()) }
    }

    suspend fun setLastLogin(login: SavedLogin) {
        context.settingsDataStore.edit {
            it[loginUserIdKey] = login.userId
            it[loginNicknameKey] = login.nickname
            it[loginAvatarKey] = login.avatar
        }
    }

    suspend fun clearLastLogin() {
        context.settingsDataStore.edit {
            it.remove(loginUserIdKey)
            it.remove(loginNicknameKey)
            it.remove(loginAvatarKey)
        }
    }


    // ==================== 界面样式（顶栏 / 底栏缩放、颜色、透明度）====================

    /** 顶栏整体缩放：1f 默认，0.6f~1.4f。图标文字等比缩放（LocalDensity 方案）。 */
    private val topBarScaleKey = floatPreferencesKey("bar_top_scale")

    val topBarScale: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[topBarScaleKey] ?: 1f).coerceIn(0.6f, 1.4f)
    }

    suspend fun setTopBarScale(v: Float) {
        context.settingsDataStore.edit { it[topBarScaleKey] = v.coerceIn(0.6f, 1.4f) }
    }

    /** 底栏整体缩放。 */
    private val bottomBarScaleKey = floatPreferencesKey("bar_bottom_scale")

    val bottomBarScale: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[bottomBarScaleKey] ?: 1f).coerceIn(0.6f, 1.4f)
    }

    suspend fun setBottomBarScale(v: Float) {
        context.settingsDataStore.edit { it[bottomBarScaleKey] = v.coerceIn(0.6f, 1.4f) }
    }

    /** 栏背景色板索引（BarPalette 下标）；0 = 跟随主题。 */
    private val barColorKey = intPreferencesKey("bar_color")

    val barColor: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[barColorKey] ?: 0
    }

    suspend fun setBarColor(index: Int) {
        context.settingsDataStore.edit { it[barColorKey] = index.coerceIn(0, 9) }
    }

    /** 顶栏背景不透明度 0f~1f。 */
    private val topBarOpacityKey = floatPreferencesKey("bar_top_opacity")

    val topBarOpacity: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[topBarOpacityKey] ?: 1f).coerceIn(0.2f, 1f)
    }

    suspend fun setTopBarOpacity(v: Float) {
        context.settingsDataStore.edit { it[topBarOpacityKey] = v.coerceIn(0.2f, 1f) }
    }

    /** 底栏背景不透明度 0f~1f。 */
    private val bottomBarOpacityKey = floatPreferencesKey("bar_bottom_opacity")

    val bottomBarOpacity: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[bottomBarOpacityKey] ?: 1f).coerceIn(0.2f, 1f)
    }

    suspend fun setBottomBarOpacity(v: Float) {
        context.settingsDataStore.edit { it[bottomBarOpacityKey] = v.coerceIn(0.2f, 1f) }
    }


    /** 搜索结果网格列数（2~4）。 */
    private val gridColumnsKey = intPreferencesKey("grid_columns")

    val gridColumns: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[gridColumnsKey] ?: 2).coerceIn(2, 6)
    }

    suspend fun setGridColumns(n: Int) {
        context.settingsDataStore.edit { it[gridColumnsKey] = n.coerceIn(2, 6) }
    }

    /** 网页缩放（0.5~1.2，1=100%），作用于所有 WebView 页面（搜索结果页是原生 UI，详情页是网页）。 */
    private val pageZoomKey = floatPreferencesKey("page_zoom")

    val pageZoom: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[pageZoomKey] ?: 1f).coerceIn(0.5f, 1.2f)
    }

    suspend fun setPageZoom(v: Float) {
        context.settingsDataStore.edit { it[pageZoomKey] = v.coerceIn(0.5f, 1.2f) }
    }
}
