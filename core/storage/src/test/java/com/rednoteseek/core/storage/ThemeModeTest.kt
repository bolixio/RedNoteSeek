package com.rednoteseek.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test fun systemFollowsSystem() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test fun explicitModesIgnoreSystem() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }
}
