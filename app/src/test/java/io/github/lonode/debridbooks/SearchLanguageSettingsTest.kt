package io.github.lonode.debridbooks

import android.app.Application
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import io.github.lonode.debridbooks.ui.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchLanguageSettingsTest {
    @Test fun `language applies and persists without saving draft credentials or changing library identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val config = StandaloneConfig("saved-key", c411ApiKey = "saved-tracker", c411Url = "https://c411.invalid/api",
                tr4kerApiKey = "saved-tr4ker", tr4kerUrl = "https://tr4ker.invalid/api",
                yggrebornPasskey = "saved-ygg", yggrebornUrl = "https://ygg.invalid/api",
                abbDomain = "abb.invalid", jackettUrl = "https://jackett.invalid", jackettApiKey = "saved-jackett",
                languageFilter = "fr", preferredFormat = "m4b", maxSizeGB = 3.0, instantOnly = true)
            val expected = JsonObject(Json.parseToJsonElement(Json.encodeToString(config)).jsonObject + ("languageFilter" to JsonPrimitive("en")))
            var saved: Connection? = Connection(prefix = "https://legacy.invalid/resolver", accountId = "existing-library", standalone = config)
            val model = ConnectionViewModel({ saved }, { saved = it })
            advanceUntilIdle()
            model.draft.apiKey = "unsaved-provider-edit"
            model.draft.c411Key = "unsaved-tracker-edit"
            model.draft.c411Enabled = false
            model.setSearchLanguage("en")
            assertEquals("en", model.draft.language)
            assertSame(saved, model.connection.value)
            assertEquals("existing-library", saved!!.accountId)
            assertEquals("https://legacy.invalid/resolver", saved.prefix)
            assertEquals(expected, Json.parseToJsonElement(Json.encodeToString(saved.standalone!!)))
            assertEquals("unsaved-provider-edit", model.draft.apiKey)
            assertFalse(model.draft.c411Enabled)
            val reopened = ConnectionViewModel({ saved }, { error("No write on load") })
            advanceUntilIdle()
            assertEquals("en", reopened.draft.language)
            assertEquals("en", reopened.connection.value!!.standalone!!.languageFilter)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed language save preserves the active filter and rejects unsupported language`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val original = Connection(accountId = "existing-library", standalone = StandaloneConfig("key", languageFilter = "fr"))
            var writes = 0
            val model = ConnectionViewModel({ original }, { writes++; error("Storage unavailable") })
            advanceUntilIdle()
            assertTrue(runCatching { model.setSearchLanguage("en") }.isFailure)
            assertSame(original, model.connection.value)
            assertEquals("fr", model.draft.language)
            assertTrue(runCatching { model.setSearchLanguage("invalid") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `initial setup stages language until a verified account exists`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = ConnectionViewModel({ null }, { error("Do not save an unconfigured account") })
            advanceUntilIdle()
            model.setSearchLanguage("en")
            assertNull(model.connection.value)
            model.draft.apiKey = "synthetic-key"
            assertEquals("en", model.draft.config().languageFilter)
        } finally { Dispatchers.resetMain() }
    }
}
