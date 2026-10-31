package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.comics.*
import io.github.lonode.debridbooks.data.*
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryPresentationCacheTest {
    private fun image(context: Context, name: String) = File(context.cacheDir, name).apply {
        writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII="))
    }
    @Test fun `decoded covers are reused bounded and cleared with temporary cache`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val first = image(context, "cache-first.png"); val second = image(context, "cache-second.png")
        val repo = ComicRepository(context, db.comics(), ConnectionStore(context), coverMemoryBytes = 4)
        try {
            val bitmap = repo.coverBitmap(first.path, 320, 480)
            assertSame(bitmap, repo.coverBitmap(first.path, 320, 480))
            assertSame(bitmap, repo.cachedCover(first.path, 320, 480))
            assertNull(repo.cachedCover(first.path, 1024, 1536))
            repo.coverBitmap(second.path, 320, 480)
            assertNull(repo.cachedCover(first.path, 320, 480))
            assertNotNull(repo.cachedCover(second.path, 320, 480))
            repo.clearCache()
            assertNull(repo.cachedCover(second.path, 320, 480))
        } finally { first.delete(); second.delete(); db.close() }
    }
    @Test fun `thumbnail loading does not scan all pages and does not weaken reader integrity checks`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = ComicRepository(context, db.comics(), ConnectionStore(context))
        val source = image(context, "thumbnail-page.png")
        val entry = ComicEntity("1".repeat(64), "2".repeat(64), "3".repeat(64), "Comic", null, byteArrayOf(1), 0, 2, false, false, 1)
        try {
            db.comics().save(entry)
            val saved = repo.download(ReadingSession(entry, listOf(source.path, source.path))) { _, _ -> }
            File(saved.pages[1]).delete()
            val presentation = repo.libraryItem(saved.entry)
            assertEquals(saved.pages[0], presentation.fallbackCover)
            assertSame(presentation, repo.libraryItem(saved.entry.copy(page = 1, updatedAt = 2)))
            assertNull(repo.cachedLibraryItem(saved.entry.copy(offline = false)))
            assertNull(repo.cachedLibraryItem(saved.entry.copy(metadata = byteArrayOf(9))))
            try { repo.openOffline(saved.entry); fail("Incomplete offline book accepted") } catch (_: IllegalStateException) { }
        } finally { repo.delete(entry, history = true); source.delete(); db.close() }
    }
    @Test fun `audio presentation misses are retained but versions and accounts stay separate`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val items = AudioLibraryItems(db.library(), ConnectionStore(context))
        val book = BookEntity("a", "book", "Audio", null, 1)
        try {
            val missing = items.item(book)
            assertNull(missing.item)
            assertSame(missing, items.item(book))
            assertNull(items.cached(book.copy(addedAt = 2)))
            assertNull(items.cached(book.copy(accountId = "b")))
        } finally { db.close() }
    }
}
