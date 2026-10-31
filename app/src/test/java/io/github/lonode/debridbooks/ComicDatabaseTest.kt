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
class ComicDatabaseTest {
    @Test fun `version one migration retains audio history and validates new comic schema`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "comic-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.createLegacyLibrary(1)
            db.execSQL("INSERT INTO books VALUES ('account', 'item', 'Preserved book', NULL, 1)")
            db.execSQL("INSERT INTO progress VALUES ('account', 'item', 'part', 0, 12345, 1.0, 0, 1)")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            assertEquals(12345L, database.library().progress("account", "item")!!.positionMs)
            val entry = ComicEntity("a", "v", "i", "Comic", null, byteArrayOf(1), 0, 3, false, true, 1)
            database.comics().save(entry)
            database.comics().checkpoint("a", "v", 2)
            database.comics().save(entry.copy(accountId = "b"))
            assertTrue(database.comics().get("a", "v")!!.completed)
            assertFalse(database.comics().get("b", "v")!!.completed)
            database.comics().checkpoint("a", "v", 0)
            assertTrue(database.comics().get("a", "v")!!.completed)
            database.comics().removeOffline("a", "v")
            assertEquals(0, database.comics().get("a", "v")!!.page)
            assertFalse(database.comics().get("a", "v")!!.offline)
            database.comics().enqueue("a", "v")
            database.comics().enqueue("a", "v")
            assertEquals(1, database.comics().pending().size)
            database.comics().downloadState("a", "v", "PAUSED")
            assertEquals(0, database.comics().tryStart("a", "v"))
            database.comics().downloadState("a", "v", "FAILED")
            database.comics().enqueue("a", "v")
            assertEquals(1, database.comics().tryStart("a", "v"))
            database.comics().completeOffline("a", "v", 3)
            assertEquals("COMPLETE", database.comics().get("a", "v")!!.downloadState)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
