package io.github.lonode.debridbooks.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", primaryKeys = ["accountId", "itemId"])
data class BookEntity(
    val accountId: String,
    val itemId: String,
    val title: String,
    val author: String?,
    val addedAt: Long,
)

@Entity(
    tableName = "progress",
    primaryKeys = ["accountId", "itemId"],
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["accountId", "itemId"],
        childColumns = ["accountId", "itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ProgressEntity(
    val accountId: String,
    val itemId: String,
    val partId: String,
    val partIndex: Int,
    val positionMs: Long,
    val speed: Float,
    val completed: Boolean,
    val updatedAt: Long,
)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC, itemId")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE accountId = :accountId ORDER BY addedAt DESC, itemId")
    abstract suspend fun books(accountId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE accountId=:account AND itemId=:item") abstract suspend fun getBook(account: String, item: String): BookEntity?

    @Query("SELECT * FROM books") abstract suspend fun allBooks(): List<BookEntity>

    @Upsert
    abstract suspend fun upsertBook(book: BookEntity)

    @Query("SELECT * FROM audio_sources WHERE accountId = :account AND itemId = :item")
    abstract suspend fun audioSource(account: String, item: String): AudioSource?
    @Upsert protected abstract suspend fun upsertAudioSource(source: AudioSource)
    @Transaction open suspend fun rememberSource(book: BookEntity, source: AudioSource) {
        require(book.accountId == source.accountId && book.itemId == source.itemId)
        upsertBook(book)
        upsertAudioSource(source)
    }

    @Upsert
    protected abstract suspend fun upsertProgress(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE accountId = :accountId AND itemId = :itemId")
    abstract suspend fun progress(accountId: String, itemId: String): ProgressEntity?

    @Transaction
    open suspend fun checkpoint(book: BookEntity, progress: ProgressEntity) {
        require(book.accountId == progress.accountId && book.itemId == progress.itemId)
        require(progress.partId.isNotBlank() && progress.partIndex >= 0 && progress.positionMs >= 0)
        require(progress.speed.isFinite() && progress.speed in 0.5f..3.5f)
        // A stale asynchronous callback must not overwrite a newer checkpoint.
        val existing = progress(book.accountId, book.itemId)
        if (existing != null && existing.updatedAt > progress.updatedAt) return
        val storedBook = getBook(book.accountId, book.itemId)
        if (storedBook == null) upsertBook(book)
        else if (storedBook.title != book.title || storedBook.author != book.author) upsertBook(book.copy(addedAt = storedBook.addedAt))
        upsertProgress(progress)
    }

    @Query("DELETE FROM books WHERE accountId = :accountId AND itemId = :itemId")
    abstract suspend fun removeBook(accountId: String, itemId: String)
}

@Database(entities = [BookEntity::class, ProgressEntity::class, ComicEntity::class, AudioSource::class, AudioPersonal::class, AudioDownload::class, ListeningEntry::class, AudioSession::class, ProviderTransfer::class, ProviderAccount::class], version = 6, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun audio(): AudioLibraryDao
    abstract fun providerTransfers(): ProviderTransferDao
    abstract fun comics(): ComicDao
}
