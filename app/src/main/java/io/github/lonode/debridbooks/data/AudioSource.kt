package io.github.lonode.debridbooks.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Encrypted resolver/metadata only; never a cleartext legacy ID or playable URL. */
@Entity(tableName = "audio_sources", primaryKeys = ["accountId", "itemId"], foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["accountId", "itemId"], childColumns = ["accountId", "itemId"], onDelete = ForeignKey.CASCADE,
)])
data class AudioSource(val accountId: String, val itemId: String, val payload: ByteArray)
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `audio_sources` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
