package io.github.lonode.debridbooks.domain

/** Durable identity/position only: never persist a playable URL as an identity. */
data class PlaybackProgress(
    val partId: String,
    val partIndex: Int,
    val positionMs: Long,
    val completed: Boolean = false,
) {
    init {
        require(partId.isNotBlank())
        require(partIndex >= 0)
        require(positionMs >= 0)
    }

    val hasStarted: Boolean get() = partIndex > 0 || positionMs > 0
    val shouldContinue: Boolean get() = hasStarted && !completed
    fun resumePositionMs(): Long = (positionMs - 5_000L).coerceAtLeast(0L)
}
