package com.rednoteseek.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rednoteseek.designsystem.theme.BarPalette
import com.rednoteseek.designsystem.theme.LocalBarStyle
import kotlin.math.roundToInt
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
    topBarScale: Float = 1f,
    bottomBarScale: Float = 1f,
    barColorIndex: Int = 0,
    topBarOpacity: Float = 1f,
    gridColumns: Int = 2,
    pageZoom: Float = 1f,
    bottomBarOpacity: Float = 1f,
    onTopBarScaleChange: (Float) -> Unit = {},
    onBottomBarScaleChange: (Float) -> Unit = {},
    onBarColorChange: (Int) -> Unit = {},
    onTopBarOpacityChange: (Float) -> Unit = {},
    onBottomBarOpacityChange: (Float) -> Unit = {},
    onGridColumnsChange: (Int) -> Unit = {},
    onPageZoomChange: (Float) -> Unit = {},
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
            SettingsGroup("界面样式") {
                BarStyleSliders(
                    topScale = topBarScale,
                    bottomScale = bottomBarScale,
                    topOpacity = topBarOpacity,
                    bottomOpacity = bottomBarOpacity,
                    colorIndex = barColorIndex,
                    gridColumns = gridColumns,
                    pageZoom = pageZoom,
                    onGridColumns = onGridColumnsChange,
                    onPageZoom = onPageZoomChange,
                    onTopScale = onTopBarScaleChange,
                    onBottomScale = onBottomBarScaleChange,
                    onTopOpacity = onTopBarOpacityChange,
                    onBottomOpacity = onBottomBarOpacityChange,
                    onColor = onBarColorChange,
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

/** 顶部或底部一条滑杆。 */
@Composable
private fun StyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, steps: Int = 0) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                (value * 100).let { "${it.roundToInt()}%" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/**
 * 界面样式调节：顶栏/底栏高度（等比缩放，图标文字自适应跟随）、背景色板（浅色为主）、上下栏独立透明度。
 * 拖动即时回调，调用方写入 DataStore 持久化。
 */
@Composable
private fun BarStyleSliders(
    topScale: Float,
    bottomScale: Float,
    topOpacity: Float,
    bottomOpacity: Float,
    colorIndex: Int,
    gridColumns: Int,
    pageZoom: Float,
    onGridColumns: (Int) -> Unit,
    onPageZoom: (Float) -> Unit,
    onTopScale: (Float) -> Unit,
    onBottomScale: (Float) -> Unit,
    onTopOpacity: (Float) -> Unit,
    onBottomOpacity: (Float) -> Unit,
    onColor: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        StyleSlider("搜索结果列数", gridColumns.toFloat(), 2f..6f, { onGridColumns(it.roundToInt()) }, steps = 3)
        StyleSlider("网页缩放", pageZoom, 0.5f..1.2f, onPageZoom)
        StyleSlider("顶栏高度", topScale, 0.6f..1.4f, onTopScale)
        StyleSlider("底栏高度", bottomScale, 0.6f..1.4f, onBottomScale)
        StyleSlider("顶栏不透明度", topOpacity, 0.2f..1f, onTopOpacity)
        StyleSlider("底栏不透明度", bottomOpacity, 0.2f..1f, onBottomOpacity)
        Text(
            "背景颜色",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp, start = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BarPalette.forEachIndexed { index, pair ->
                val bg = pair.second ?: MaterialTheme.colorScheme.surfaceVariant
                Box(
                    Modifier
                        .size(32.dp)
                        .background(color = bg, shape = CircleShape)
                        .clickable { onColor(index) },
                )
            }
        }
        Text(
            BarPalette.getOrNull(colorIndex)?.first ?: "跟随主题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
        )
    }
}
