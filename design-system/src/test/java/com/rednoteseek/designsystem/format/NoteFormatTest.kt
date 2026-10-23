package com.rednoteseek.designsystem.format

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteFormatTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 19, 15, 0)
    private fun fmt(millis: Long) = formatRelativeTime(millis, now, zone)

    @Test fun relativeTime() {
        assertEquals("刚刚", fmt(now - 30_000))
        assertEquals("5分钟前", fmt(now - 5 * 60_000))
        assertEquals("3小时前", fmt(now - 3 * 3_600_000))
        assertEquals("昨天 08:05", fmt(at(2026, 10, 18, 8, 5)))
        assertEquals("3天前", fmt(at(2026, 10, 16)))
        assertEquals("10-12", fmt(at(2026, 10, 12)))
        assertEquals("2024-10-12", fmt(at(2024, 10, 12)))
    }

    @Test fun unknownOrFutureTimeIsBlank() {
        assertEquals("", fmt(0))
        assertEquals("", fmt(now + 60_000))
    }

    @Test fun mediaAspectIsClamped() {
        assertEquals(0.75f, mediaAspect(1080, 1440), 0.001f)
        assertEquals(4f / 3f, mediaAspect(1920, 1080), 0.001f)
        assertEquals(1f, mediaAspect(1000, 1000), 0.001f)
        assertEquals(0.75f, mediaAspect(0, 0), 0.001f)
        assertEquals(0.75f, mediaAspect(1080, 3000), 0.001f)
    }
}
