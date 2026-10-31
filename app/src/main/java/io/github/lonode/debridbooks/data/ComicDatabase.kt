package io.github.lonode.debridbooks.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "comics", primaryKeys = ["accountId", "volumeId"])
data class ComicEntity(
    val accountId: String,
    val volumeId: String,
    val itemId: String,
    val title: String,
    val chapterId: String?,
    val resolver: ByteArray,
    val page: Int,
    val pageCount: Int,
    val completed: Boolean,
    val offline: Boolean,
    val updatedAt: Long,
    val downloadState: String = "NONE",
    val downloadedPages: Int = 0,
    val metadata: ByteArray? = null,
)
@Dao
abstract class ComicDao {
    @Query("SELECT * FROM comics") abstract suspend fun allEntries(): List<ComicEntity>
    @Query("UPDATE comics SET downloadState = 'RUNNING' WHERE accountId = :account AND volumeId = :volume AND downloadState IN ('QUEUED', 'RUNNING')")
    abstract suspend fun tryStart(account: String, volume: String): Int
    @Query("SELECT * FROM comics WHERE downloadState IN ('QUEUED', 'RUNNING') ORDER BY updatedAt")
    abstract suspend fun pending(): List<ComicEntity>
    @Query("UPDATE comics SET downloadState = :state, downloadedPages = :pages WHERE accountId = :account AND volumeId = :volume")
    abstract suspend fun downloadState(account: String, volume: String, state: String, pages: Int = 0)
    @Transaction open suspend fun enqueue(account: String, volume: String) {
        val entry = get(account, volume) ?: error("Comic missing")
        if (!entry.offline && entry.downloadState !in listOf("QUEUED", "RUNNING")) downloadState(account, volume, "QUEUED")
    }
    @Query("SELECT * FROM comics ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<ComicEntity>>
    @Query("SELECT * FROM comics WHERE accountId = :account ORDER BY updatedAt DESC")
    abstract fun observe(account: String): Flow<List<ComicEntity>>
    @Query("SELECT * FROM comics WHERE accountId = :account AND volumeId = :volume")
    abstract suspend fun get(account: String, volume: String): ComicEntity?
    @Query("UPDATE comics SET metadata = :payload WHERE accountId = :account AND itemId = :item")
    abstract suspend fun updateMetadata(account: String, item: String, payload: ByteArray)
    @Upsert abstract suspend fun save(entry: ComicEntity)
    @Query("UPDATE comics SET page = :page, completed = (completed OR :completed), updatedAt = :time WHERE accountId = :account AND volumeId = :volume")
    protected abstract suspend fun updatePage(account: String, volume: String, page: Int, completed: Boolean, time: Long)
    @Transaction open suspend fun checkpoint(account: String, volume: String, page: Int) {
        val entry = get(account, volume) ?: return
        require(page in 0 until entry.pageCount)
        updatePage(account, volume, page, page == entry.pageCount - 1, System.currentTimeMillis())
    }
    @Query("UPDATE comics SET offline = 1, downloadState = 'COMPLETE', downloadedPages = :pages WHERE accountId = :account AND volumeId = :volume")
    abstract suspend fun completeOffline(account: String, volume: String, pages: Int)
    @Query("UPDATE comics SET offline = 0, downloadState = 'NONE', downloadedPages = 0 WHERE accountId = :account AND volumeId = :volume")
    abstract suspend fun removeOffline(account: String, volume: String)
    @Query("DELETE FROM comics WHERE accountId = :account AND volumeId = :volume")
    abstract suspend fun remove(account: String, volume: String)
}
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `comics` (`accountId` TEXT NOT NULL, `volumeId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `title` TEXT NOT NULL, `chapterId` TEXT, `resolver` BLOB NOT NULL, `page` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `offline` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `downloadState` TEXT NOT NULL, `downloadedPages` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `volumeId`))")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE comics ADD COLUMN metadata BLOB")
    }
}
