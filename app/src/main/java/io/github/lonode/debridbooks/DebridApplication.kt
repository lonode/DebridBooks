package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import kotlinx.coroutines.launch
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import io.github.lonode.debridbooks.data.LibraryDatabase
import io.github.lonode.debridbooks.data.PreferencesRepository

private val Context.preferencesStore by preferencesDataStore(name = "listening")

class DebridApplication : Application() {
    val processOwner = java.util.UUID.randomUUID().toString()
    private val startup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        io.github.lonode.debridbooks.network.standalone.AbbRuntime.initialize(noBackupFilesDir)
        io.github.lonode.debridbooks.network.standalone.ProviderRegistry.current = io.github.lonode.debridbooks.data.RoomMagnetRegistry { database }
        startup.launch {
            try { database.audio().reconcileInterrupted(processOwner); audio.sweepOrphans() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { playbackRuntime.update { it.copy(message = "Some download cleanup could not finish. Your library has not been erased.") } }
        }
    }
    val connections by lazy { io.github.lonode.debridbooks.data.ConnectionStore(this, database.providerTransfers()) }
    val database: LibraryDatabase by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(io.github.lonode.debridbooks.data.MIGRATION_1_2, io.github.lonode.debridbooks.data.MIGRATION_2_3, io.github.lonode.debridbooks.data.MIGRATION_3_4, io.github.lonode.debridbooks.data.MIGRATION_4_5, io.github.lonode.debridbooks.data.MIGRATION_5_6).build()
        // Never use destructive migration: a missing migration must not erase progress.
    }
    val playbackRuntime = io.github.lonode.debridbooks.playback.PlaybackRuntime()
    val chapters by lazy { io.github.lonode.debridbooks.audio.ChapterRepository(database.audio(), database.library()) }
    val discovery by lazy { io.github.lonode.debridbooks.network.DiscoveryRepository(database.audio()) }
    val backup by lazy { io.github.lonode.debridbooks.data.LibraryBackup(database, connections, preferences) }
    val audio by lazy { io.github.lonode.debridbooks.audio.AudioRepository(this, database, connections) }
    val bookDetails by lazy { io.github.lonode.debridbooks.network.BookDetailsRepository() }
    val audioLibraryItems by lazy { io.github.lonode.debridbooks.data.AudioLibraryItems(database.library(), connections) }
    val comics by lazy { io.github.lonode.debridbooks.comics.ComicRepository(this, database.comics(), connections) }
    val preferences: PreferencesRepository by lazy { PreferencesRepository(preferencesStore) }
}
