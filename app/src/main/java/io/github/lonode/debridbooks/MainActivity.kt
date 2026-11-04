package io.github.lonode.debridbooks

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lonode.debridbooks.ui.AppUpdateViewModel
import io.github.lonode.debridbooks.ui.DebridApp
import io.github.lonode.debridbooks.ui.LibraryViewModel

class MainActivity : ComponentActivity() {
    private var incomingSearch by mutableStateOf<String?>(null)
    private var libraryEvent by mutableStateOf(0)
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") intent.getStringExtra(android.app.SearchManager.QUERY)?.takeIf { it.length in 1..500 }?.let { incomingSearch = it }
        intent?.getStringExtra("search_query")?.takeIf { it.length in 1..500 }?.let { incomingSearch = it }
        if (intent?.getBooleanExtra("open_library", false) == true) { incomingSearch = null; libraryEvent++ }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val app = application as DebridApplication
        setContent {
            val model: LibraryViewModel = viewModel(factory = viewModelFactory {
                initializer { LibraryViewModel(app.database.library(), app.preferences) }
            })
            val updates: AppUpdateViewModel = viewModel(factory = viewModelFactory {
                initializer { AppUpdateViewModel() }
            })
            DebridApp(model, incomingSearch, libraryEvent, updates)
        }
    }
}
