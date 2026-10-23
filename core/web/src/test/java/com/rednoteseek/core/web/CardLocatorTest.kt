package com.rednoteseek.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardLocatorTest {

    private val order = (0 until 100).map { "n$it" }

    private fun snapshot(scrollY: Int, vararg cards: Pair<String, Int>) =
        CardSnapshot(found = false, scrollY = scrollY, viewport = 900, cards = cards.map { CardPosition(it.first, it.second) })

    @Test fun parsesSnapshotJson() {
        val parsed = CardSnapshot.parse(
            """{"found":false,"scrollY":16833,"viewport":900,"cards":[{"id":"n50","top":16000},{"id":"n51","top":16210}]}""",
        )
        assertEquals(snapshot(16833, "n50" to 16000, "n51" to 16210), parsed)
    }

    @Test fun malformedSnapshotIsNull() {
        assertNull(CardSnapshot.parse("not json"))
        assertNull(CardSnapshot.parse("""{"found":true}"""))
    }

    @Test fun estimatesLinearlyFromKnownCards() {
        // n50 在 10000、n59 在 11800 → 每张约 200px；n10 估计在 2000，再上移三分之一屏
        assertEquals(1700, CardLocator.nextScrollY(snapshot(10500, "n50" to 10000, "n59" to 11800), order, "n10"))
    }

    @Test fun estimatesBelowAsWell() {
        assertEquals(13700, CardLocator.nextScrollY(snapshot(10500, "n50" to 10000, "n59" to 11800), order, "n70"))
    }

    @Test fun singleKnownCardMovesOneScreenTowardsTarget() {
        assertEquals(9100 - 300, CardLocator.nextScrollY(snapshot(10000, "n50" to 10000), order, "n10"))
        assertEquals(10900 - 300, CardLocator.nextScrollY(snapshot(10000, "n50" to 10000), order, "n90"))
    }

    @Test fun neverScrollsAboveTop() {
        assertEquals(0, CardLocator.nextScrollY(snapshot(500, "n2" to 400, "n3" to 600), order, "n0"))
    }

    @Test fun unknownTargetOrNoKnownCardsGiveNull() {
        assertNull(CardLocator.nextScrollY(snapshot(0, "n1" to 0), order, "missing"))
        assertNull(CardLocator.nextScrollY(snapshot(0, "other" to 0), order, "n10"))
    }
}
