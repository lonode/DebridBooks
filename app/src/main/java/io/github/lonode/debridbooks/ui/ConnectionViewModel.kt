package io.github.lonode.debridbooks.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.network.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keep the in-memory connection across UI recreation without saving secrets in UI state bundles. */
class ConnectionViewModel internal constructor(
    private val readConnection: suspend () -> Connection?,
    private val saveConnection: suspend (Connection) -> Unit,
) : ViewModel() {
    constructor(app: DebridApplication) : this(
        { withContext(Dispatchers.IO) { app.connections.read() } },
        { connection -> withContext(Dispatchers.IO) { app.connections.save(connection) } },
    )
    val connection = mutableStateOf<Connection?>(null)
    val error = mutableStateOf(false)
    val loading = mutableStateOf(true)
    val draft = SetupDraft()
    var initialNavigationHandled = false
    init {
        viewModelScope.launch {
            try { connection.value = readConnection(); connection.value?.standalone?.let(draft::load) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error.value = true }
            finally { loading.value = false }
        }
    }

    /** Apply a search preference without saving unverified credential edits or stopping playback. */
    suspend fun setSearchLanguage(language: String) {
        require(language in setOf("", "fr", "en"))
        val current = connection.value
        val config = current?.standalone
        if (config != null) {
            val updated = Connection(current.prefix, current.accountId, config.withLanguageFilter(language))
            saveConnection(updated)
            connection.value = updated
        }
        draft.language = language
    }
}
