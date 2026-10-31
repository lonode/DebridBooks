package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiscoveryCompatibilityTest {
    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val count = AtomicInteger()
        var status = 404
        val connection = Connection("http://127.0.0.1:${server.localPort}/synthetic", "account")
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) { check(!line.startsWith("Authorization:", true)); line = input.readLine() }
                    count.incrementAndGet()
                    val bytes = "{}".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: SocketException) { if (!server.isClosed) throw IllegalStateException("Fixture failed") }
        }
        override fun close() { server.close(); worker.join(5000) }
    }
    private val book = SearchItem("tbab:synthetic", "Book")
    @Test fun `unsupported discovery is suppressed per connection without changing playback source`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try { Fixture().use { fixture ->
            val repository = DiscoveryRepository(db.audio())
            assertNull(repository.load(fixture.connection, book).series)
            repository.load(fixture.connection, book)
            assertEquals(2, fixture.count.get())
            repository.load(Connection(fixture.connection.prefix + "-other", fixture.connection.accountId), book)
            assertEquals(4, fixture.count.get())
            assertEquals("tbab:synthetic", book.id)
            assertTrue(db.audio().allPersonal().isEmpty())
        } } finally { db.close() }
    }
    @Test fun `discovery outage is retried while valid empty metadata is cached`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try { Fixture().use { fixture ->
            val item = MobileContract.localId(book.id)
            db.library().upsertBook(BookEntity("account", item, book.title, null, 1))
            val repository = DiscoveryRepository(db.audio())
            fixture.status = 503
            repository.load(fixture.connection, book, item)
            assertEquals(2, fixture.count.get()); assertNull(db.audio().personal("account", item))
            fixture.status = 200
            val empty = repository.load(fixture.connection, book, item)
            assertTrue(empty.seriesAt > 0); assertTrue(empty.similarAt > 0)
            repository.load(fixture.connection, book, item)
            assertEquals(4, fixture.count.get())
        } } finally { db.close() }
    }
    @Test fun `series news validates dates retains exact titles and orders recent before upcoming`() {
        val today = java.time.LocalDate.of(2026, 10, 25)
        val current = CatalogBook("Current", released = "2026-10-20", current = true)
        val recent = CatalogBook("Exact release title", released = "2026-10-24")
        val upcoming = CatalogBook("Soon", released = "2026-11-01")
        val details = SeriesDetails("Series", books = listOf(upcoming, current, CatalogBook("Old", released = "2020-01-01"), recent, CatalogBook("Malformed", released = "not-a-date")))
        val news = seriesNews(listOf(details, details), today)
        assertEquals(listOf("Exact release title", "Soon"), news.map { it.second.title })
        assertFalse(news[0].second.upcoming); assertTrue(news[1].second.upcoming)
    }
}
