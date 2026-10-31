package io.github.lonode.debridbooks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.data.BookEntity
import io.github.lonode.debridbooks.data.LibraryDatabase
import io.github.lonode.debridbooks.data.ProgressEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryDatabaseTest {
    private lateinit var database: LibraryDatabase
    private val book = BookEntity("account1", "book1", "A book", null, 1)
    private val checkpoint = ProgressEntity("account1", "book1", "part1", 0, 50_000, 1f, false, 20)

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java,
        ).build()
    }
    @After fun close() { database.close() }

    @Test fun `concurrent book writes do not lose records`() = runTest {
        val dao = database.library()
        (1..20).map { index -> async { dao.upsertBook(book.copy(itemId = "book$index")) } }.awaitAll()
        assertEquals(20, dao.books("account1").size)
    }

    @Test fun `checkpoints are account scoped and older writes cannot revert progress`() = runTest {
        val dao = database.library()
        dao.checkpoint(book, checkpoint)
        dao.checkpoint(book, checkpoint.copy(positionMs = 1_000, updatedAt = 10))
        dao.checkpoint(book.copy(accountId = "account2"), checkpoint.copy(accountId = "account2", positionMs = 8_000))
        assertEquals(50_000L, dao.progress("account1", "book1")!!.positionMs)
        assertEquals(8_000L, dao.progress("account2", "book1")!!.positionMs)
        dao.removeBook("account1", "book1")
        assertNull(dao.progress("account1", "book1"))
        assertNotNull(dao.progress("account2", "book1"))
    }

    @Test fun `invalid checkpoint cannot partially insert a book`() = runTest {
        val dao = database.library()
        try {
            dao.checkpoint(book, checkpoint.copy(positionMs = -1))
            fail("Invalid checkpoint accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(dao.books("account1").isEmpty())
    }
}
