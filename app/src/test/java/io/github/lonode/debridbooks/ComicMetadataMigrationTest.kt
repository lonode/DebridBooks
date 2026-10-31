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
class ComicMetadataMigrationTest {
    @Test fun `version three upgrade preserves offline pages and scopes metadata updates to account and book`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "comic-metadata-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.createLegacyLibrary(3)
            db.execSQL("INSERT INTO books VALUES ('a', 'book', 'Audio', NULL, 1)")
            db.execSQL("INSERT INTO progress VALUES ('a', 'book', 'part', 0, 12345, 1.0, 0, 1)")
            db.execSQL("INSERT INTO audio_sources VALUES ('a', 'book', X'0203')")
            db.execSQL("INSERT INTO comics VALUES ('a', 'v1', 'comic', 'Comic', NULL, X'01', 2, 5, 0, 1, 1, 'COMPLETE', 5)")
            db.execSQL("INSERT INTO comics VALUES ('a', 'v2', 'comic', 'Comic 2', NULL, X'01', 1, 5, 0, 0, 1, 'PAUSED', 1)")
            db.execSQL("INSERT INTO comics VALUES ('b', 'v1', 'comic', 'Other account', NULL, X'01', 0, 5, 0, 0, 1, 'NONE', 0)")
            db.version = 3
        }
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        var db = open()
        try {
            val old = db.comics().get("a", "v1")!!
            assertNull(old.metadata)
            assertTrue(old.offline)
            assertEquals(2, old.page)
            assertEquals("COMPLETE", old.downloadState)
            assertArrayEquals(byteArrayOf(2,3), db.library().audioSource("a", "book")!!.payload)
            assertEquals(12345L, db.library().progress("a", "book")!!.positionMs)
            db.comics().updateMetadata("a", "comic", byteArrayOf(4,5,6))
            db.close(); db = open()
            assertArrayEquals(byteArrayOf(4,5,6), db.comics().get("a", "v1")!!.metadata)
            assertArrayEquals(byteArrayOf(4,5,6), db.comics().get("a", "v2")!!.metadata)
            assertEquals("PAUSED", db.comics().get("a", "v2")!!.downloadState)
            assertNull(db.comics().get("b", "v1")!!.metadata)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
