package com.rednoteseek.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamicColorScheme

// 小红书品牌红，只在不支持动态取色（Android 12 以下）时作为主色
private val BrandRed = Color(0xFFFF2442)
private val BrandRedDark = Color(0xFFFF3F5C)

// Android 12 以下没有壁纸取色：从品牌红生成完整配色（与系统动态取色同一套算法），主色保持品牌红
private val LightColors = dynamicColorScheme(seedColor = BrandRed, isDark = false)
    .copy(primary = BrandRed, onPrimary = Color.White)

private val DarkColors = dynamicColorScheme(seedColor = BrandRed, isDark = true)
    .copy(primary = BrandRedDark, onPrimary = Color.White)

/** 全 App 主题：Material 3，Android 12+ 跟随壁纸动态取色（与 BiliSeek 一致）。 */
@Composable
fun RedNoteSeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
