package com.rednoteseek.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
}
