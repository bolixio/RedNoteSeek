package com.rednoteseek.feature.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rednoteseek.designsystem.theme.BsSpacing

/**
 * 登录引导，叠在网页引擎（小红书网页）底部。
 * 用户可以直接在网页里登录；也可以等网页弹出扫码框后，把二维码链接一键交给小红书 App 确认，免去截图扫码。
 */
@Composable
fun XhsLoginOverlay(qrLoginUrl: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = OverlayTonal, shadowElevation = OverlayShadow) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(BsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BsSpacing.sm),
        ) {
            Text(
                if (qrLoginUrl == null) "请在上方网页中点击「登录」，出现扫码框后可一键跳转小红书 App 确认"
                else "已获取登录二维码，点击下方按钮在小红书 App 中确认登录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { qrLoginUrl?.let { error = openInXhsApp(context, it) } },
                enabled = qrLoginUrl != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("用小红书 App 登录") }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 成功跳转返回 null，否则返回给用户看的提示。 */
private fun openInXhsApp(context: Context, url: String): String? = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(XHS_APP_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    null
} catch (e: ActivityNotFoundException) {
    "未安装小红书 App，请在网页中扫码或用手机号登录"
}

private const val XHS_APP_PACKAGE = "com.xingin.xhs"

private val OverlayTonal = 3.dp
private val OverlayShadow = 8.dp
