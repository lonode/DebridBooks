package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.PlaybackProgress
import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    @Test fun `part two remains in continue listening at its first millisecond`() {
        assertTrue(PlaybackProgress("part2", 1, 0).shouldContinue)
        assertFalse(PlaybackProgress("part1", 0, 0).shouldContinue)
        assertFalse(PlaybackProgress("part2", 1, 100, completed = true).shouldContinue)
    }

    @Test fun `resume rewinds five seconds without changing stored position`() {
        val progress = PlaybackProgress("part1", 0, 12_000)
        assertEquals(7_000L, progress.resumePositionMs())
        assertEquals(12_000L, progress.positionMs)
        assertEquals(0L, progress.copy(positionMs = 1_000).resumePositionMs())
    }
}
