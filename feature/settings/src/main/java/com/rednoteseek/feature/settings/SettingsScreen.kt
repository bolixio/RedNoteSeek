package com.rednoteseek.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.rednoteseek.core.storage.ThemeMode
import com.rednoteseek.designsystem.component.BsConfirmDialog
import com.rednoteseek.designsystem.component.BsTopBar
import com.rednoteseek.designsystem.component.LocalShowMessage
import com.rednoteseek.designsystem.component.SettingsChoice
import com.rednoteseek.designsystem.component.SettingsChoiceSheet
import com.rednoteseek.designsystem.component.SettingsDivider
import com.rednoteseek.designsystem.component.SettingsGroup
import com.rednoteseek.designsystem.component.SettingsRow
import com.rednoteseek.designsystem.theme.BsShapes
import com.rednoteseek.designsystem.theme.BsSpacing

private val THEME_CHOICES = listOf(
    SettingsChoice(ThemeMode.SYSTEM, "跟随系统"),
    SettingsChoice(ThemeMode.LIGHT, "浅色"),
    SettingsChoice(ThemeMode.DARK, "深色"),
)

private fun ThemeMode.label(): String = THEME_CHOICES.first { it.value == this }.label

/** 设置页：账号、深色模式、清空搜索历史、关于、退出登录（单独红字卡片 + 确认）。纯 UI，操作由调用方实现。 */
@Composable
fun SettingsScreen(
    nickname: String,
    themeMode: ThemeMode,
    versionName: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onClearHistory: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showThemeSheet by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val showMessage = LocalShowMessage.current

    Column(modifier.fillMaxSize()) {
        BsTopBar(title = "设置", onNavigate = null)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BsSpacing.lg, vertical = BsSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.xl),
        ) {
            SettingsGroup("账号") {
                SettingsRow(
                    title = nickname.ifBlank { "已登录" },
                    icon = Icons.Outlined.AccountCircle,
                    subtitle = "小红书网页版登录",
                )
            }
            SettingsGroup("外观") {
                SettingsRow(
                    title = "深色模式",
                    icon = Icons.Outlined.DarkMode,
                    value = themeMode.label(),
                    onClick = { showThemeSheet = true },
                )
            }
            SettingsGroup("数据") {
                SettingsRow(
                    title = "清空搜索历史",
                    icon = Icons.Outlined.History,
                    subtitle = "只删除本机保存的搜索关键词",
                    onClick = { confirmClear = true },
                )
            }
            SettingsGroup("关于") {
                SettingsRow(title = "版本", icon = Icons.Outlined.Info, value = versionName)
                SettingsDivider()
                SettingsRow(
                    title = "使用声明",
                    icon = Icons.Outlined.Gavel,
                    subtitle = "仅供学习交流使用。数据版权归小红书及内容作者所有，请使用自己的账号并自行承担风控风险。",
                )
            }
            // 退出登录单独一张红字卡片（BiliSeek 设置页规范）
            Surface(
                shape = BsShapes.lg,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "退出登录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmLogout = true }
                        .padding(vertical = BsSpacing.lg),
                )
            }
        }
    }

    if (showThemeSheet) {
        SettingsChoiceSheet(
            title = "深色模式",
            choices = THEME_CHOICES,
            selected = themeMode,
            onSelect = onThemeModeChange,
            onDismiss = { showThemeSheet = false },
        )
    }
    if (confirmClear) {
        BsConfirmDialog(
            title = "清空搜索历史",
            text = "确定清空全部搜索历史？",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = {
                confirmClear = false
                onClearHistory()
                showMessage("已清空搜索历史")
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmLogout) {
        BsConfirmDialog(
            title = "退出登录",
            text = "退出后需要重新登录小红书网页版。",
            confirmLabel = "退出",
            destructive = true,
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
            onDismiss = { confirmLogout = false },
        )
    }
}
