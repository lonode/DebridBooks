package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.playback.*
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.MediaPart
import io.github.lonode.debridbooks.network.Resolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerNavigationTest {
    @Test fun `readable chapter metadata keeps provider filenames part identities URLs and queue order unchanged`() {
        val raw = "Harry.Potter.T01.L.Ecole.Des.Sorciers/12.Le.Miroir.du.Riséd.mp3"
        val first = MediaPart(raw, "https://media.invalid/synthetic-private", raw, 1234)
        val second = MediaPart("Book/13.Nicolas.Flamel.mp3", "https://media.invalid/second", "Book/13.Nicolas.Flamel.mp3")
        val queue = audioQueue("account", "item", SearchItem("resolver", "Harry Potter"), Resolution(true, listOf(first, second)))
        assertEquals(listOf(first.id, second.id), queue.map { it.mediaId })
        assertEquals(first.url, queue.first().localConfiguration!!.uri.toString())
        assertEquals("Le Miroir du Riséd", queue.first().mediaMetadata.subtitle.toString())
        assertEquals(raw, first.filename)
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            player.setMediaItems(queue)
            val rows = chapterQueue(player)
            assertEquals("Tome 1 · L Ecole Des Sorciers", rows.first().context)
            assertEquals("Nicolas Flamel", rows[1].title)
            assertFalse(rows.toString().contains("synthetic-private"))
            player.seekTo(91_000)
            assertTrue(selectChapter(player, rows.first()))
            assertEquals(91_000L, player.currentPosition)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }
    private fun player(): ExoPlayer {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ExoPlayer.Builder(context).build().apply {
            setMediaItems((1..3).map { n -> MediaItem.Builder().setMediaId("chapter-$n")
                .setUri("https://example.invalid/private-media-$n")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Book").setSubtitle("Chapter title $n").build()).build() })
        }
    }
    @Test fun `selecting chapters preserves paused intent and tapping current chapter preserves its position`() {
        val player = player()
        try {
            val chapters = chapterQueue(player)
            assertEquals(listOf("Chapter title 1", "Chapter title 2", "Chapter title 3"), chapters.map { it.title })
            assertFalse(chapters.toString().contains("private-media"))
            assertTrue(selectChapter(player, chapters[2]))
            assertEquals(2, player.currentMediaItemIndex); assertEquals(0L, player.currentPosition); assertFalse(player.playWhenReady)
            player.seekTo(42_000)
            assertTrue(selectChapter(player, chapters[2])); assertEquals(42_000L, player.currentPosition)
            player.playWhenReady = true
            assertTrue(selectChapter(player, chapters[1])); assertTrue(player.playWhenReady)
            assertEquals(1, player.currentMediaItemIndex)
        } finally { player.release() }
    }
    @Test fun `selection follows identity after queue reorder and ignores a removed chapter`() {
        val player = player()
        try {
            val chapters = chapterQueue(player)
            player.moveMediaItem(2, 0)
            assertTrue(selectChapter(player, chapters[2])); assertEquals("chapter-3", player.currentMediaItem!!.mediaId)
            player.removeMediaItem(player.currentMediaItemIndex)
            val index = player.currentMediaItemIndex
            assertFalse(selectChapter(player, chapters[2])); assertEquals(index, player.currentMediaItemIndex)
        } finally { player.release() }
    }
    @Test fun `jumps clamp at boundaries and use the live player position without starting playback`() {
        assertEquals(0L, shiftedPosition(3_000, 60_000, -10_000))
        assertEquals(60_000L, shiftedPosition(57_000, 60_000, 10_000))
        assertEquals(67_000L, shiftedPosition(57_000, C.TIME_UNSET, 10_000))
        assertEquals(Long.MAX_VALUE, shiftedPosition(Long.MAX_VALUE - 1, C.TIME_UNSET, 30_000))
        val player = player()
        try {
            player.seekTo(42_000)
            shiftPlayback(player, 10_000); assertEquals(52_000L, player.currentPosition)
            shiftPlayback(player, -30_000); assertEquals(22_000L, player.currentPosition)
            shiftPlayback(player, -30_000); assertEquals(0L, player.currentPosition)
            assertFalse(player.playWhenReady)
        } finally { player.release() }
    }
}
