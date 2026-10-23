package com.rednoteseek.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 间距：页面左右边距 lg，卡片内边距 md，同行元素 sm，图标与文字 xs。 */
object BsSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object BsSize {
    val iconSm = 18.dp      // 行内小图标
    val icon = 24.dp        // 标准图标
    val iconLg = 28.dp      // 竖排互动按钮 / 快捷入口
    val avatarSm = 28.dp    // 评论头像
    val avatar = 40.dp      // 列表头像
    val avatarMd = 48.dp    // 动态页关注作者
    val avatarLg = 64.dp    // 个人页头像
    val touchTarget = 48.dp
    val iconXs = 14.dp      // 卡片 / 评论里的点赞小图标
    val iconMd = 20.dp      // 设置行图标、行尾箭头
    val illustration = 48.dp // 空状态插图图标
    val topBar = 56.dp      // 顶栏最小高度
}

object BsShapes {
    val xs = RoundedCornerShape(4.dp)            // 标签、封面角标
    val sm = RoundedCornerShape(8.dp)            // 封面、小卡片、输入框
    val md = RoundedCornerShape(12.dp)           // 卡片、楼中楼底板
    val lg = RoundedCornerShape(20.dp)           // 快捷块、设置分组卡片
    val pill = RoundedCornerShape(percent = 50)  // Chip、搜索框
}
