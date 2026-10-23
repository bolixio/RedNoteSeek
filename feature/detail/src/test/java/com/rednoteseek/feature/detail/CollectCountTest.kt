package com.rednoteseek.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectCountTest {

    @Test fun collectCountFollowsThisSession() {
        assertEquals("13", adjustCount("12", initially = false, now = true))
        assertEquals("11", adjustCount("12", initially = true, now = false))
        assertEquals("12", adjustCount("12", initially = true, now = true))
        assertEquals("1.2万", adjustCount("1.2万", initially = false, now = true))
        assertEquals("0", adjustCount("0", initially = true, now = false))
    }
}
