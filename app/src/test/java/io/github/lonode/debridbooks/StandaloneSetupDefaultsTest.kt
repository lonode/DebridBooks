package io.github.lonode.debridbooks

import android.app.Application
import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.standalone.StandaloneConfig
import io.github.lonode.debridbooks.ui.SetupDraft
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneSetupDefaultsTest {
    @Test fun `new setup enables AudiobookBay with its default domain and preserves hidden tracker overrides`() {
        val draft = SetupDraft()
        draft.apiKey = "synthetic-key"
        draft.c411Enabled = true
        draft.c411Key = "synthetic-tracker-key"
        draft.c411Url = "https://custom-tracker.invalid/torznab"
        assertTrue(draft.abbEnabled)
        val saved = draft.config()
        assertEquals(StandaloneConfig.DEFAULT_ABB_DOMAIN, saved.abbDomain)
        assertEquals("https://custom-tracker.invalid/torznab", saved.c411Url)
        val reloaded = SetupDraft().apply { load(saved) }
        assertTrue(reloaded.abbEnabled)
        assertEquals(saved.c411Url, reloaded.config().c411Url)
        reloaded.load(StandaloneConfig("synthetic-key", instantOnly = true))
        assertFalse(reloaded.config().instantOnly)
    }

    @Test fun `older omitted AudiobookBay setting gains default while an explicit off choice survives persistence`() {
        val old = Json.decodeFromString<Connection>("""{"accountId":"existing-library","standalone":{"apiKey":"synthetic-key"}}""")
        assertEquals("existing-library", old.accountId)
        assertEquals(StandaloneConfig.DEFAULT_ABB_DOMAIN, old.standalone!!.abbDomain)
        val draft = SetupDraft().apply { load(old.standalone); abbEnabled = false }
        val saved = StandaloneConfig.connect(draft.config(), old)
        assertEquals(old.accountId, saved.accountId)
        val reloaded = Json.decodeFromString<Connection>(Json.encodeToString(saved))
        assertEquals("", reloaded.standalone!!.abbDomain)
        assertFalse(SetupDraft().apply { load(reloaded.standalone) }.abbEnabled)
    }

    @Test fun `legacy link without an AudiobookBay override uses server default and keeps library identity`() {
        val raw = """{"provider":"alldebrid","apiKey":"synthetic-key"}"""
        val prefix = "https://old-server.invalid/" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val migrated = StandaloneConfig.migrate(Connection(prefix, "existing-library"))
        assertEquals("existing-library", migrated.accountId)
        assertEquals(StandaloneConfig.DEFAULT_ABB_DOMAIN, migrated.standalone!!.abbDomain)
    }
}
