package io.github.lonode.debridbooks.playback

import androidx.media3.common.Player

data class PlayerChapter(val id: String, val title: String, val fileSize: Long?, val startMs: Long = 0, val endMs: Long? = null, val embedded: Boolean = false, val context: String? = null)

/** Only presentation data; never copy temporary media URLs into UI state. */
fun chapterQueue(player: Player?): List<PlayerChapter> {
    if (player == null || !player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
    return (0 until player.mediaItemCount).map { index ->
        val item = player.getMediaItemAt(index)
        val label = chapterLabel(item.mediaMetadata.subtitle?.toString().orEmpty(), item.mediaMetadata.title?.toString().orEmpty(), index + 1)
        PlayerChapter(item.mediaId, label.title,
            item.mediaMetadata.extras?.getLong("fileSize", 0)?.takeIf { it > 0 },
            context = item.mediaMetadata.extras?.getString("chapterContext") ?: label.context)
    }
}

/** Resolve against the live queue, so an old row cannot jump to a different reordered chapter. */
fun selectChapter(player: Player?, chapter: PlayerChapter): Boolean {
    if (player == null || !player.isCommandAvailable(Player.COMMAND_GET_TIMELINE) ||
        !player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) return false
    val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == chapter.id } ?: return false
    if (index != player.currentMediaItemIndex || chapter.embedded && (player.currentPosition < chapter.startMs || chapter.endMs?.let { player.currentPosition >= it } == true)) player.seekTo(index, chapter.startMs)
    return true
}

fun shiftedPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val position = positionMs.coerceAtLeast(0)
    val shifted = if (deltaMs > 0) position.coerceAtMost(Long.MAX_VALUE - deltaMs) + deltaMs
        else (position + deltaMs).coerceAtLeast(0)
    return if (durationMs > 0) shifted.coerceAtMost(durationMs) else shifted
}
fun shiftPlayback(player: Player?, deltaMs: Long) {
    player?.let { it.seekTo(shiftedPosition(it.currentPosition, it.duration, deltaMs)) }
}

fun chapterAt(chapters: List<io.github.lonode.debridbooks.data.AudioChapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs + 250 }.coerceAtLeast(0)
fun parsePlaybackTime(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 1..3 || parts.any { !Regex("[0-9]{1,4}").matches(it) }) return null
    val values = parts.map { it.toLong() }
    if (values.drop(1).any { it > 59 }) return null
    val seconds = values.fold(0L) { total, next -> total * 60 + next }
    return (seconds * 1000).takeIf { it <= 7 * 24 * 60 * 60 * 1000L }
}
