package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Test

class JsResultTest {

    @Test fun decodesJsonString() {
        assertEquals("clicked", decodeJsResult("\"clicked\""))
    }

    @Test fun decodesEscapedJsonText() {
        assertEquals("""{"found":true}""", decodeJsResult("\"{\\\"found\\\":true}\""))
    }

    @Test fun nullAndUndefinedBecomeEmpty() {
        assertEquals("", decodeJsResult("null"))
        assertEquals("", decodeJsResult(null))
    }

    @Test fun nonStringValuesKeepTheirText() {
        assertEquals("42", decodeJsResult("42"))
        assertEquals("true", decodeJsResult("true"))
    }
}
