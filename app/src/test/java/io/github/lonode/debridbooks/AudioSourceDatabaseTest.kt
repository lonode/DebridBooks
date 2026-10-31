package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioSourceDatabaseTest {
    @Test fun `version two upgrade preserves comic downloads and audio progress`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "audio-source-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.createLegacyLibrary(2)
            db.execSQL("INSERT INTO books VALUES ('account', 'item', 'Book', NULL, 1)")
            db.execSQL("INSERT INTO progress VALUES ('account', 'item', 'part', 0, 12345, 1.0, 0, 1)")
            db.execSQL("INSERT INTO comics VALUES ('account', 'volume', 'comic', 'Comic', NULL, X'01', 2, 3, 1, 1, 1, 'COMPLETE', 3)")
            db.version = 2
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            assertEquals(12345L, db.library().progress("account", "item")!!.positionMs)
            assertTrue(db.comics().get("account", "volume")!!.offline)
            assertEquals(2, db.comics().get("account", "volume")!!.page)
            val book = BookEntity("account", "item", "Book", null, 1)
            db.library().rememberSource(book, AudioSource("account", "item", byteArrayOf(1,2,3)))
            db.library().rememberSource(book.copy(accountId = "other"), AudioSource("other", "item", byteArrayOf(4)))
            assertArrayEquals(byteArrayOf(1,2,3), db.library().audioSource("account", "item")!!.payload)
            db.library().checkpoint(book, ProgressEntity("account", "item", "part", 0, 54321, 1f, false, 5))
            assertArrayEquals(byteArrayOf(1,2,3), db.library().audioSource("account", "item")!!.payload)
            db.library().removeBook("account", "item")
            assertNull(db.library().audioSource("account", "item"))
            assertNotNull(db.library().audioSource("other", "item"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `resolver account mismatch cannot insert a partial library item`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            try {
                db.library().rememberSource(BookEntity("a", "item", "Book", null, 1), AudioSource("b", "item", byteArrayOf(1)))
                fail("Mismatched account accepted")
            } catch (_: IllegalArgumentException) { }
            assertTrue(db.library().books("a").isEmpty())
        } finally { db.close() }
    }
}
