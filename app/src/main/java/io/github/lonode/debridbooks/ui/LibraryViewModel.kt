package io.github.lonode.debridbooks.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lonode.debridbooks.data.BookEntity
import io.github.lonode.debridbooks.data.LibraryDao
import io.github.lonode.debridbooks.data.ListeningPreferences
import io.github.lonode.debridbooks.data.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryState(
    val books: List<BookEntity> = emptyList(),
    val preferences: ListeningPreferences = ListeningPreferences(),
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val saveError: Boolean = false,
)

class LibraryViewModel(
    private val library: LibraryDao,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null

    init { reload() }

    fun reload() {
        observer?.cancel()
        mutableState.update { it.copy(loading = true, loadError = false) }
        observer = viewModelScope.launch {
            try {
                combine(library.observeBooks(), preferences.preferences) { books, settings ->
                    books to settings
                }.collect { (books, settings) ->
                    mutableState.update {
                        it.copy(books = books, preferences = settings, loading = false, loadError = false)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadError = true) }
            }
        }
    }

    fun setSpeed(value: Float) = save { preferences.setSpeed(value) }
    fun setUnmeteredOnly(value: Boolean) = save { preferences.setUnmeteredOnly(value) }

    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                mutableState.update { it.copy(saveError = false) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutableState.update { it.copy(saveError = true) }
            }
        }
    }
}
