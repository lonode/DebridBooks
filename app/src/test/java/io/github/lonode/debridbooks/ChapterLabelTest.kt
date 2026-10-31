package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.playback.chapterLabel
import org.junit.Assert.*
import org.junit.Test

class ChapterLabelTest {
    @Test fun `Harry Potter collection paths separate the chapter name from its volume`() {
        val label = chapterLabel("Harry.Potter.FR.[MP3].[INTEGRALE]-NOTAG/Harry.Potter.T01.L.Ecole.Des.Sorciers/12.Le.Miroir.du.Riséd.mp3", "Harry Potter")
        assertEquals("Le Miroir du Riséd", label.title)
        assertEquals("Tome 1 · L Ecole Des Sorciers", label.context)
        assertEquals("Le match de Quidditch", chapterLabel("11.Le.match.de.Quidditch.mp3").title)
    }

    @Test fun `chapter labels preserve accents meaningful titles decimal markers and Windows paths`() {
        val label = chapterLabel("Book\\Volume_02_The_Sequel\\Chapter_003_Épilogue.m4b")
        assertEquals("Épilogue", label.title)
        assertEquals("Tome 2 · The Sequel", label.context)
        assertEquals("1984", chapterLabel("1984.m4b").title)
        assertEquals("Chapter title 1", chapterLabel("Chapter title 1").title)
        assertEquals("Mr. Nobody", chapterLabel("Mr. Nobody").title)
        assertEquals("An interlude", chapterLabel("01.5_An_interlude.flac").title)
        assertEquals("Audio file", chapterLabel("").title)
        assertEquals("Chapter 4", chapterLabel("https://media.invalid/private?token=synthetic-secret", fallbackNumber = 4).title)
        assertNull(chapterLabel("files/01.Introduction.mp3").context)
    }
}
