package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneMangaCatalogTest {
    private val manga = "12345678-1234-1234-1234-123456789012"
    private val chapter1 = "12345678-1234-1234-1234-123456789013"
    private val chapter2 = "12345678-1234-1234-1234-123456789014"
    private val chapter3 = "12345678-1234-1234-1234-123456789015"
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    @Test fun `MangaDex searches and reads directly with descriptions covers and saver fallback`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            assertEquals("api.mangadex.org", URI(request.url).host)
            assertFalse(request.headers.containsKey("Authorization"))
            when (URI(request.url).path) {
                "/manga" -> ok("""{"result":"ok","data":[{"id":"$manga","attributes":{"title":{"en":"Series"},"description":{"en":"Description"}},"relationships":[{"type":"author","attributes":{"name":"Author"}},{"type":"cover_art","attributes":{"fileName":"cover.jpg"}}]}]}""")
                else -> ok("""{"result":"ok","baseUrl":"https://images.mangadex.network","chapter":{"hash":"abcdef","data":["1.jpg","2.jpg"],"dataSaver":[]}}""")
            }
        }
        val api = StandaloneApi(StandaloneConfig("synthetic-key"), http)
        val book = api.search("series", 1, true, true).results.single()
        assertEquals("MangaDex", book.searchSource)
        assertEquals(1, SearchSession(query = "series", pages = mapOf(1 to SearchPage(listOf(book)))).loadedFrom("MangaDex"))
        assertEquals("mangadex", book.provider); assertEquals("Description", book.description); assertTrue(book.poster!!.contains("cover.jpg.512.jpg"))
        assertEquals(manga, ResolverItem.decode(book.id).d)
        val first = api.pages(chapter1, true); val fresh = api.pages(chapter1, false)
        assertEquals(listOf("https://images.mangadex.network/data/abcdef/1.jpg", "https://images.mangadex.network/data/abcdef/2.jpg"), first.pages)
        assertEquals(first.pages, fresh.pages); assertEquals(3, requests.size)
    }
    @Test fun `MangaDex feed fetches remaining pages and deduplicates newest hosted chapters`() = runBlocking {
        var calls = 0
        val http = DirectTransport { request ->
            calls++
            val later = URI(request.url).query.contains("offset=2")
            if (!later) ok("""{"data":[{"id":"$chapter1","attributes":{"chapter":"1","pages":10,"publishAt":"2026-01-01"}},{"id":"$chapter2","attributes":{"chapter":"2","pages":0,"externalUrl":"https://other.invalid"}}],"total":3}""")
            else ok("""{"data":[{"id":"$chapter3","attributes":{"chapter":"1","pages":12,"publishAt":"2026-02-01"}}],"total":3}""")
        }
        val id = ResolverItem(n = "Long series", p = "m", d = "12345678-1234-1234-1234-123456789099").encode()
        val page = MangaDexClient(http).chapters(id, 0)
        assertEquals(2, calls); assertEquals(chapter3, page.chapters.single().id); assertEquals(1, page.total); assertFalse(page.hasMore)
        val exhausted = MangaDexClient(http).chapters(id, 100)
        assertTrue(exhausted.chapters.isEmpty()); assertFalse(exhausted.hasMore); assertEquals(2, calls)
    }
    @Test fun `MangaDex rejects malformed identifiers feed nonprogress and unsafe page hosts`() = runBlocking {
        val noRequests = DirectTransport { error("Must reject before network") }
        assertTrue(runCatching { MangaDexClient(noRequests).pages("../../private", true) }.isFailure)
        val unsafe = DirectTransport { ok("""{"baseUrl":"http://unsafe.invalid","chapter":{"hash":"abc","data":["1.jpg"]}}""") }
        val error = runCatching { StandaloneApi(StandaloneConfig("key"), unsafe).pages(chapter2, false) }.exceptionOrNull()!!
        assertFalse(error.toString().contains("unsafe.invalid")); assertNull(error.cause)
        val missing = DirectTransport { ok("""{"data":[],"total":5}""") }
        assertTrue(runCatching { MangaDexClient(missing).chapters(ResolverItem(n = "Series", d = "12345678-1234-1234-1234-123456789098").encode(), 0) }.isFailure)
    }
    @Test fun `metadata preserves resolver and language and refuses another volume or study guide`() = runBlocking {
        assertEquals(0.0, CatalogClient.score("Example Tome 1", "Example Tome 2"), 0.0)
        assertEquals(0.0, CatalogClient.score("Example", "Example study guide"), 0.0)
        val item = SearchItem(ResolverItem(n = "Unique Example [MP3]").encode(), "Unique Example [MP3]", language = "fr", releaseName = "Unique Example [MP3]")
        val http = DirectTransport { request ->
            assertEquals("itunes.apple.com", URI(request.url).host)
            assertFalse(request.url.contains("tbab")); assertTrue(URI(request.url).query.contains("country=FR"))
            ok("""{"results":[{"collectionName":"Unique Example","artistName":"Author","artworkUrl100":"https://images.invalid/100x100bb.jpg","description":"Paragraph one.&lt;br&gt;&lt;br&gt;Paragraph two.","releaseDate":"2017-01-01","primaryGenreName":"Fiction"}]}""")
        }
        val result = CatalogClient(http).enrich(item)
        assertEquals(item.id, result.id); assertEquals(item.language, result.language); assertEquals(item.releaseName, result.releaseName)
        assertEquals("Author", result.author); assertTrue(result.poster!!.contains("600x600bb.jpg"))
        val details = CatalogClient(http).details(ResolverItem.decode(item.id), "fr")
        assertEquals(2017, details.year); assertEquals("Fiction", details.genre)
    }
    @Test fun `catalog series membership and next book come from catalog relationships`() = runBlocking {
        val first = """{"asin":"B000000001","title":"Unique Hero","language":"french","authors":[{"name":"Writer"}],"series":[{"asin":"B000000099","title":"Hero series","sequence":"1"}]}"""
        val second = """{"asin":"B000000002","title":"Hero Returns","language":"french","authors":[{"name":"Writer"}],"release_date":"2026-01-01"}"""
        val http = DirectTransport { request ->
            assertEquals("api.audible.fr", URI(request.url).host)
            assertFalse(request.headers.containsKey("Authorization"))
            val params = URLDecoder.decode(URI(request.url).rawQuery, "UTF-8")
            when {
                URI(request.url).path.endsWith("B000000099") -> ok("""{"product":{"title":"Hero series","relationships":[{"asin":"B000000001","relationship_type":"series","relationship_to_product":"child","sequence":"1"},{"asin":"B000000002","relationship_type":"series","relationship_to_product":"child","sequence":"2"}]}}""")
                params.contains("asins=") -> ok("""{"products":[$first,$second]}""")
                else -> ok("""{"products":[$first]}""")
            }
        }
        val result = CatalogClient(http).series(ResolverItem(n = "Unique Hero [FR]"), "fr")!!
        assertEquals("Hero series", result.name); assertEquals("1", result.position); assertEquals("Hero Returns", result.next!!.title)
        assertEquals(2, result.books.size); assertTrue(result.books.first().current)
    }
    @Test fun `reentering a migrated provider key reuses its library scope after saved keys are removed`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val key = "synthetic-migrated-provider-key"
            val fingerprint = MobileContract.localId("alldebrid:$key")
            db.providerTransfers().bindAccount(ProviderAccount(fingerprint, "old-prefix-library-account"))
            val store = ConnectionStore(context, db.providerTransfers())
            store.clear()
            assertEquals("old-prefix-library-account", store.candidate(StandaloneConfig(key), null).accountId)
            assertEquals(MobileContract.localId("alldebrid:other-key"), store.candidate(StandaloneConfig("other-key"), null).accountId)
            assertEquals(StandaloneConfig.connect(StandaloneConfig("new-key")).accountId, store.candidate(StandaloneConfig("new-key"), null).accountId)
            assertFalse(fingerprint.contains(key))
        } finally { db.close() }
    }
    @Test fun `v5 migration preserves offline library and provider mappings survive database reopen with account isolation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "standalone-v6-migration.db"; context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            sql.createLegacyLibrary(5)
            sql.execSQL("INSERT INTO books VALUES ('a','book','Audio',NULL,1)")
            sql.execSQL("INSERT INTO progress VALUES ('a','book','part',0,55000,1.25,0,1)")
            sql.execSQL("INSERT INTO audio_sources VALUES ('a','book',X'1234')")
            sql.version = 5
        }
        var db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            assertEquals(55000L, db.library().progress("a", "book")!!.positionMs)
            assertArrayEquals(byteArrayOf(0x12, 0x34), db.library().audioSource("a", "book")!!.payload)
            RoomMagnetRegistry { db }.save("provider-account", "hash", "42", null)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.providerTransfers().bindAccount(ProviderAccount("key-fingerprint", "old-library-account")) }
            db.close(); db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
            val registry = RoomMagnetRegistry { db }
            assertEquals("old-library-account", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.providerTransfers().libraryAccount("key-fingerprint") })
            assertEquals("42", registry.entries("provider-account").single().id); assertTrue(registry.entries("other-account").isEmpty())
            registry.remove("provider-account", "hash"); assertTrue(registry.entries("provider-account").isEmpty())
            assertEquals(55000L, db.library().progress("a", "book")!!.positionMs)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
