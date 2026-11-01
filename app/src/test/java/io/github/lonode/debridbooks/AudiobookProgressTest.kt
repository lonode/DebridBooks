package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.data.BookExtras
import io.github.lonode.debridbooks.data.libraryJson
import io.github.lonode.debridbooks.playback.audiobookProgress
import org.junit.Assert.*
import org.junit.Test

class AudiobookProgressTest {
    private val parts = listOf("short", "long")
    private val durations = mapOf("short" to 1000L, "long" to 9000L)

    @Test fun `overall percentage weights unequal parts by their durations`() {
        val value = audiobookProgress(parts, durations, "long", 4000)!!
        assertEquals(50, value.percent)
        assertFalse(value.estimated)
        assertEquals("50% of book", value.label)
    }

    @Test fun `unknown durations produce an explicitly approximate whole book percentage`() {
        val value = audiobookProgress(parts, mapOf("long" to 9000), "long", 4500)!!
        assertEquals(75, value.percent)
        assertTrue(value.estimated)
        assertEquals("≈ 75% of book", value.label)
        assertEquals(50, audiobookProgress(parts, emptyMap(), "long", 4500)!!.percent)
    }

    @Test fun `completion is one hundred percent even for an old book with no duration metadata`() {
        assertEquals(100, audiobookProgress(emptyList(), emptyMap(), "old-part", 0, completed = true)!!.percent)
        assertFalse(audiobookProgress(emptyList(), emptyMap(), "old-part", 0, completed = true)!!.estimated)
    }

    @Test fun `unstarted book is zero but an unknown saved part has no fabricated percentage`() {
        assertEquals(0, audiobookProgress(emptyList(), emptyMap(), null, 0)!!.percent)
        assertNull(audiobookProgress(parts, durations, "missing", 500))
        assertNull(audiobookProgress(emptyList(), emptyMap(), "old-part", 500))
    }

    @Test fun `positions clamp and one hundred percent is reserved for completion`() {
        assertEquals(0, audiobookProgress(parts, durations, "short", -100)!!.percent)
        assertEquals(99, audiobookProgress(parts, durations, "long", Long.MAX_VALUE)!!.percent)
        assertEquals(99, audiobookProgress(listOf("long"), mapOf("long" to 10000), "long", 9999)!!.percent)
    }

    @Test fun `old personal data remains readable and durations survive serialization`() {
        val old = libraryJson.decodeFromString<BookExtras>("""{"saved":true,"rating":4}""")
        assertTrue(old.audioParts.isEmpty())
        assertTrue(old.audioDurationsMs.isEmpty())
        val updated = old.copy(audioParts = parts, audioDurationsMs = durations)
        val restored = libraryJson.decodeFromString<BookExtras>(libraryJson.encodeToString(updated))
        assertEquals(updated, restored)
        assertTrue(restored.saved)
        assertEquals(4, restored.rating)
    }
}
