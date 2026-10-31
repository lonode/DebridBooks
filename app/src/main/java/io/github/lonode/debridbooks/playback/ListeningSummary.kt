package io.github.lonode.debridbooks.playback

import java.time.LocalDate

data class ListeningSummary(val today: Long, val week: Long, val month: Long, val total: Long, val streak: Int, val best: Int)
fun listeningSummary(days: Map<String, Long>, today: LocalDate = LocalDate.now()): ListeningSummary {
    val valid = days.mapNotNull { (day, time) -> runCatching { LocalDate.parse(day) }.getOrNull()?.takeIf { it <= today && time >= 0 }?.let { it to time } }.toMap()
    var streak = 0
    var cursor = if ((valid[today] ?: 0) >= 60_000) today else today.minusDays(1)
    while ((valid[cursor] ?: 0) >= 60_000) { streak++; cursor = cursor.minusDays(1) }
    var best = 0; var run = 0; var last: LocalDate? = null
    valid.filterValues { it >= 60_000 }.keys.sorted().forEach { day -> run = if (last?.plusDays(1) == day) run + 1 else 1; best = maxOf(best, run); last = day }
    return ListeningSummary(valid[today] ?: 0, valid.filterKeys { it >= today.minusDays(6) }.values.sum(),
        valid.filterKeys { it >= today.minusDays(29) }.values.sum(), valid.values.sum(), streak, best)
}
