package io.github.lonode.debridbooks.domain

/** Ephemeral, safe progress only: never include credentials, filenames, transfer IDs or URLs. */
enum class OpeningStage {
    ReadingSavedBook, CheckingCurrentSession, CheckingOfflineFiles, WaitingForProvider,
    CheckingAccount, FetchingTorrent, AddingTorrent, CheckingTransfer, ReadingFiles,
    PreparingFiles, RestoringPlace, StartingPlayback
}
data class OpeningProgress(val stage: OpeningStage, val completed: Int = 0, val total: Int? = null,
    val updatedAtNanos: Long = System.nanoTime()) {
    init { require(completed >= 0 && (total == null || total > 0 && completed <= total)) }
}
fun openingSeconds(startNanos: Long, nowNanos: Long): Long = ((nowNanos-startNanos).coerceAtLeast(0) / 1_000_000_000L)
