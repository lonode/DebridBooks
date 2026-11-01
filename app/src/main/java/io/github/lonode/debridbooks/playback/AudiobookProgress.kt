package io.github.lonode.debridbooks.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.math.floor

/** Whole-book position; unknown file lengths are explicitly an equal-part estimate. */
data class AudiobookProgress(val fraction: Float, val estimated: Boolean) {
    // Reserve 100% for completion, rather than rounding up during the last minute.
    val percent: Int get() = floor(fraction.toDouble() * 100 + .00001).toInt().coerceIn(0, 100)
    val label: String get() = "${if (estimated) "≈ " else ""}$percent% of book"
}

fun audiobookProgress(parts: List<String>, durations: Map<String, Long>, partId: String?, positionMs: Long,
    completed: Boolean = false): AudiobookProgress? {
    if (completed) return AudiobookProgress(1f, false)
    if (partId == null && positionMs <= 0) return AudiobookProgress(0f, false)
    val index = parts.indexOf(partId)
    if (index < 0) return null
    val duration = durations[partId]?.takeIf { it > 0 }
    val within = if (duration != null) (positionMs.coerceAtLeast(0).toDouble() / duration).coerceIn(0.0, 1.0) else 0.0
    val exact = parts.all { (durations[it] ?: 0) > 0 }
    val fraction = if (exact) {
        val total = parts.sumOf { durations.getValue(it).toDouble() }
        (parts.take(index).sumOf { durations.getValue(it).toDouble() } + within * duration!!) / total
    } else (index + within) / parts.size
    return AudiobookProgress(fraction.toFloat().coerceIn(0f, .999999f), !exact)
}

/** Timeline data contains only stable part IDs and durations, never source URLs. */
fun audioPartDurations(player: Player): Map<String, Long> {
    if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyMap()
    val timeline = player.currentTimeline
    val window = Timeline.Window()
    return buildMap {
        for (index in 0 until timeline.windowCount) {
            timeline.getWindow(index, window)
            if (window.durationMs > 0) put(window.mediaItem.mediaId, window.durationMs)
        }
        player.currentMediaItem?.let { if (player.duration > 0) put(it.mediaId, player.duration) }
    }
}
