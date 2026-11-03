package io.github.lonode.debridbooks

import android.app.Application
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import io.github.lonode.debridbooks.network.standalone.*
import io.github.lonode.debridbooks.ui.SetupDraft
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V3xSourceTest {
    private val hash = "d".repeat(40)
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun feed() = ok("""<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><item>
        <title>Un livre [MP3]</title><guid isPermaLink="false">release-id</guid>
        <enclosure url="https://api.v3x.club/download/private.torrent?apikey=tracker-secret" length="1234"/>
        <torznab:attr name="infohash" value="$hash"/><torznab:attr name="seeders" value="2"/>
        </item></channel></rss>""")
    private fun params(request: DirectRequest) = URI(request.url).rawQuery.split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun `V3X settings are optional compatible and preserved by language and setup edits`() {
        val old = Json.decodeFromString<StandaloneConfig>("""{"apiKey":"provider"}""")
        assertTrue(old.v3xApiKey.isEmpty())
        assertFalse(StandaloneApi(old).capabilities().sources.getValue("v3x"))
        val config = StandaloneConfig("provider", abbDomain = "", v3xApiKey = "tracker-secret", v3xUrl = "https://v3x.invalid/torznab/api")
        val changed = config.withLanguageFilter("fr")
        assertEquals(config.v3xApiKey, changed.v3xApiKey)
        assertEquals(config.v3xUrl, changed.v3xUrl)
        val draft = SetupDraft().apply { load(changed) }
        assertTrue(draft.v3xEnabled)
        assertEquals(changed.v3xApiKey, draft.config().v3xApiKey)
        assertEquals(changed.v3xUrl, draft.config().v3xUrl)
        assertEquals(StandaloneConfig.connect(old).accountId, StandaloneConfig.connect(config).accountId)
        assertEquals("V3X", safeSource("V3X"))
        assertTrue(StandaloneApi(config).capabilities().sources.getValue("v3x"))
    }

    @Test fun `setup requires a key only when enabled and rejects unsafe endpoint or key`() {
        val draft = SetupDraft().apply { apiKey = "provider"; v3xEnabled = true }
        assertTrue(runCatching { draft.config() }.exceptionOrNull() is SetupProblem)
        draft.v3xEnabled = false
        assertTrue(draft.config().v3xApiKey.isBlank())
        for (url in listOf("http://v3x.invalid/api", "https://secret@v3x.invalid/api", "https://v3x.invalid/api?token=private")) {
            assertTrue(runCatching { StandaloneConfig("provider", v3xApiKey = "key", v3xUrl = url).validate() }.isFailure)
        }
        assertTrue(runCatching { StandaloneConfig("provider", v3xApiKey = "bad\nkey").validate() }.isFailure)
    }

    @Test fun `French-only search uses verified V3X categories pagination language and hash without public requests`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            when (URI(request.url).host) {
                "api.v3x.club" -> {
                    assertEquals("/torznab/api", URI(request.url).path)
                    assertEquals("tracker-secret", params(request)["apikey"])
                    assertEquals("search", params(request)["t"])
                    assertFalse(request.headers.containsKey("Authorization"))
                    assertFalse(request.url.contains("provider-secret"))
                    feed()
                }
                "api.alldebrid.com" -> {
                    assertFalse(request.url.contains("tracker-secret"))
                    ok("""{"status":"success","data":{"magnets":[]}}""")
                }
                else -> error("French-only search must not contact public sources")
            }
        }
        val config = StandaloneConfig("provider-secret", abbDomain = "", languageFilter = "fr", v3xApiKey = "tracker-secret", knabenEnabled = true)
        val search = TorrentSearch(config, http)
        for (comic in listOf(false, true)) {
            val page = search.search("Livre & auteur", 2, comic)
            assertFalse(page.partial)
            val item = page.results.single()
            assertEquals("fr", item.language)
            assertEquals("V3X", item.searchSource)
            assertEquals(hash, item.infoHash)
            assertEquals(hash, ResolverItem.decode(item.id).h)
            assertEquals(if (comic) "comic" else "audiobook", item.type)
            val request = requests.last { URI(it.url).host == "api.v3x.club" }
            assertEquals(if (comic) "7030" else "3030", params(request)["cat"])
            assertEquals("40", params(request)["offset"])
            assertEquals("40", params(request)["limit"])
            assertEquals("Livre & auteur", params(request)["q"])
        }
        assertTrue(requests.none { URI(it.url).path.contains("download") || URI(it.url).path.contains("upload") })
    }

    @Test fun `setup validates V3X through Torznab without downloading any result`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            if (URI(request.url).host == "api.alldebrid.com") ok("""{"status":"success","data":{"user":{"username":"synthetic"}}}""")
            else feed()
        }
        StandaloneApi(StandaloneConfig("provider", v3xApiKey = "tracker-secret"), http).validate()
        assertEquals(2, requests.size)
        assertTrue(requests.any { URI(it.url).host == "api.v3x.club" })
        assertTrue(requests.none { URI(it.url).path.contains("download") || URI(it.url).path.contains("upload") })
    }

    @Test fun `V3X failure is reported under its own name without hiding another tracker`() = runBlocking {
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "api.v3x.club" -> DirectResponse(403, ByteArray(0))
            "c411.org" -> feed()
            "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
            else -> error("Unexpected destination")
        } }
        val page = TorrentSearch(StandaloneConfig("provider", abbDomain = "", languageFilter = "fr", v3xApiKey = "key", c411ApiKey = "other"), http)
            .search("Livre", 1, false)
        assertEquals(1, page.results.size)
        assertTrue(page.partial)
        assertEquals("V3X", page.warnings.single().source)
    }

    @Test fun `legacy configuration migration retains V3X credentials and account identity`() {
        val raw = """{"provider":"alldebrid","apiKey":"provider","v3xApiKey":"tracker-secret","v3xUrl":"https://v3x.invalid/torznab/api"}"""
        val old = Connection("https://old.invalid/" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray()), "library")
        val migrated = StandaloneConfig.migrate(old)
        assertEquals("library", migrated.accountId)
        assertEquals("tracker-secret", migrated.standalone!!.v3xApiKey)
        assertEquals("https://v3x.invalid/torznab/api", migrated.standalone.v3xUrl)
    }
}
