package io.github.lonode.debridbooks.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PlaybackAction {
    data class Sleep(val minutes: Int = 0, val endOfPart: Boolean = false) : PlaybackAction
    data class Jump(val partId: String, val positionMs: Long) : PlaybackAction
    data class Bookmark(val note: String = "") : PlaybackAction
    data class Speed(val value: Float) : PlaybackAction
    data object Undo : PlaybackAction
    data object Retry : PlaybackAction
    data object Close : PlaybackAction
    data object BeginSelection : PlaybackAction
}
data class RuntimeState(val sleepRemainingMs: Long = 0, val sleepAtEnd: Boolean = false, val undoAvailable: Boolean = false,
    val message: String = "", val recovering: Boolean = false)
/** UI bridge only; the service owns actions, timers, persistence and playback. */
class PlaybackRuntime {
    private val mutable = MutableStateFlow(RuntimeState())
    val state = mutable.asStateFlow()
    var handler: ((PlaybackAction) -> Unit)? = null; internal set
    fun send(action: PlaybackAction) { handler?.invoke(action) }
    internal fun update(change: (RuntimeState) -> RuntimeState) { mutable.value = change(mutable.value) }
}
