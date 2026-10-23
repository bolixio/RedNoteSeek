package com.rednoteseek.designsystem.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd")
private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * 小红书式相对时间：刚刚 / N分钟前 / N小时前 / 昨天 HH:mm / N天前（7 天内）/ MM-dd（今年）/ yyyy-MM-dd。
 * 时间未知（0）或在未来时返回空串。
 */
fun formatRelativeTime(millis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis <= 0 || millis > now) return ""
    val diff = now - millis
    if (diff < 60_000) return "刚刚"
    if (diff < 3_600_000) return "${diff / 60_000}分钟前"
    if (diff < 86_400_000) return "${diff / 3_600_000}小时前"
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
    return when {
        days == 1L -> "昨天 " + time.format(HOUR_MINUTE)
        days < 7 -> "${days}天前"
        time.year == today.year -> time.format(MONTH_DAY)
        else -> time.format(FULL_DATE)
    }
}

/** 媒体 / 封面宽高比（宽 / 高）：按图片或视频的实际比例，限制在 3:4 到 4:3 之间；宽高未知时取 3:4。 */
fun mediaAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 3f / 4f
    return (width.toFloat() / height).coerceIn(3f / 4f, 4f / 3f)
}
