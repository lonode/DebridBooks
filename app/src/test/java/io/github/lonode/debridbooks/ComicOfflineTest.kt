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
class ComicOfflineTest {
    @Test fun `offline pages finalize atomically and deletion retains reading history`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = ComicRepository(context, db.comics(), ConnectionStore(context))
        val entry = ComicEntity("a".repeat(64), "b".repeat(64), "c".repeat(64), "Synthetic comic", null, byteArrayOf(1), 0, 1, false, false, 1)
        val source = File(context.cacheDir, "synthetic-page.png")
        source.writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII="))
        try {
            db.comics().save(entry)
            val offline = repo.download(ReadingSession(entry, listOf(source.absolutePath))) { _, _ -> }
            assertTrue(db.comics().get(entry.accountId, entry.volumeId)!!.offline)
            assertEquals(1, repo.openOffline(offline.entry).pages.size)
            repo.checkpoint(entry, 0)
            repo.delete(offline.entry)
            assertFalse(File(offline.pages.single()).exists())
            assertTrue(db.comics().get(entry.accountId, entry.volumeId)!!.completed)
            assertFalse(db.comics().get(entry.accountId, entry.volumeId)!!.offline)
        } finally { source.delete(); db.close() }
    }
    @Test fun `missing and failed downloads are not exposed as offline`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repo = ComicRepository(context, db.comics(), ConnectionStore(context))
        val entry = ComicEntity("d".repeat(64), "e".repeat(64), "f".repeat(64), "Missing comic", null, byteArrayOf(1), 0, 1, false, true, 1)
        try {
            db.comics().save(entry)
            try { repo.openOffline(entry); fail("Missing pages marked offline") } catch (_: IllegalStateException) { }
            assertFalse(db.comics().get(entry.accountId, entry.volumeId)!!.offline)
            try { repo.download(ReadingSession(entry, listOf("/nonexistent/synthetic.png"))) { _, _ -> }; fail("Incomplete save accepted") }
            catch (_: java.io.IOException) { }
            assertFalse(db.comics().get(entry.accountId, entry.volumeId)!!.offline)
            val parent = File(context.noBackupFilesDir, "comic-pages/${entry.accountId}")
            assertFalse(parent.listFiles().orEmpty().any { it.name.startsWith("staging-") })
        } finally { db.close() }
    }
}
