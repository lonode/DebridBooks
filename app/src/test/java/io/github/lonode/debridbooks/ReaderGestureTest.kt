package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.comics.ReaderGesturePolicy
import io.github.lonode.debridbooks.comics.ReaderTap
import org.junit.Assert.*
import org.junit.Test

class ReaderGestureTest {
    @Test fun `navigation taps respect both system edges and reserve center for controls`() {
        assertEquals(ReaderTap.None, ReaderGesturePolicy.tap(10f, 1000f, 60f, 60f, true))
        assertEquals(ReaderTap.None, ReaderGesturePolicy.tap(990f, 1000f, 60f, 60f, true))
        assertEquals(ReaderTap.Previous, ReaderGesturePolicy.tap(150f, 1000f, 60f, 60f, true))
        assertEquals(ReaderTap.Next, ReaderGesturePolicy.tap(850f, 1000f, 60f, 60f, true))
        assertEquals(ReaderTap.Controls, ReaderGesturePolicy.tap(500f, 1000f, 60f, 60f, true))
        assertEquals(ReaderTap.None, ReaderGesturePolicy.tap(850f, 1000f, 60f, 60f, false))
    }
    @Test fun `short drags vertical scrolls and diagonals do not turn pages`() {
        assertEquals(0, ReaderGesturePolicy.swipe(30f, 0f, 48f))
        assertEquals(0, ReaderGesturePolicy.swipe(50f, 200f, 48f))
        assertEquals(0, ReaderGesturePolicy.swipe(100f, 100f, 48f))
        assertEquals(1, ReaderGesturePolicy.swipe(-200f, 20f, 48f))
        assertEquals(-1, ReaderGesturePolicy.swipe(200f, 20f, 48f))
    }
}
