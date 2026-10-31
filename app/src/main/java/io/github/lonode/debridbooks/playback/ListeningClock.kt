package io.github.lonode.debridbooks.playback

import java.time.Instant
import java.time.ZoneId

/** Actual listening, not seek distance or playback-speed-adjusted content length. */
class ListeningClock {
    private data class Sample(val account: String, val item: String, val part: String, val position: Long, val monotonic: Long, val wall: Long, val speed: Float)
    private var previous: Sample? = null
    fun sample(account: String?, item: String?, part: String, position: Long, playing: Boolean, speed: Float,
        monotonic: Long, wall: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, Long>> {
        val old = previous
        previous = if (playing && account != null && item != null) Sample(account, item, part, position, monotonic, wall, speed) else null
        if (old == null || old.account != account || old.item != item || old.part != part) return emptyList()
        val elapsed = monotonic - old.monotonic
        val advanced = (position - old.position) / old.speed
        if (elapsed !in 1..15_000 || advanced <= 0 || kotlin.math.abs(advanced - elapsed) > 500 + elapsed * .2) return emptyList()
        // Split using calendar midnight in the device's zone, including 23/25-hour DST days.
        val end = wall; var start = end - elapsed; val result = mutableListOf<Pair<String, Long>>()
        while (start < end) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val until = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            if (until <= start) break
            result += date.toString() to (until - start); start = until
        }
        return result
    }
    fun reset() { previous = null }
}
fun smartRewindMs(pausedForMs: Long): Long = when {
    pausedForMs < 30_000 -> 0
    pausedForMs < 5 * 60_000 -> 5_000
    pausedForMs < 60 * 60_000 -> 15_000
    else -> 30_000
}
fun inBedtime(hour: Int, start: Int): Boolean = if (start > 6) hour >= start || hour < 6 else hour >= start && hour < 6
