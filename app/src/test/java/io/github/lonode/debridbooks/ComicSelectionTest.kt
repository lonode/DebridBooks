package io.github.lonode.debridbooks

import androidx.lifecycle.ViewModelStore
import io.github.lonode.debridbooks.comics.ComicViewModel
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DebridApplication::class)
class ComicSelectionTest {
    @Test fun `browsing torrent comic details never starts provider preparation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = ComicViewModel(RuntimeEnvironment.getApplication() as DebridApplication)
            store.put("comic", model)
            model.connect(Connection(accountId = "synthetic-account", standalone = StandaloneConfig("synthetic-key")))
            val first = SearchItem("unresolved-first", "First comic", type = "comic")
            model.select(first)
            assertEquals(first, model.state.value.selected)
            assertFalse("Selecting details must not schedule resolution", model.state.value.busy)
            assertTrue(model.state.value.streams.isEmpty())
            assertNull(model.state.value.reader)
            val second = SearchItem("unresolved-second", "Second comic", type = "comic")
            model.select(second)
            assertEquals(second, model.state.value.selected)
            assertFalse(model.state.value.busy)
            model.closeSelection()
            assertNull(model.state.value.selected)
            assertTrue(model.state.value.streams.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
