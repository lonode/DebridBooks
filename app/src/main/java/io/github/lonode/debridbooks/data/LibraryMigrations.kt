package io.github.lonode.debridbooks.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `audio_personal` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `audio_downloads` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `state` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `total` INTEGER, `manifest` BLOB, `status` TEXT, `updatedAt` INTEGER NOT NULL, `announced` INTEGER NOT NULL, `allowLowSpace` INTEGER NOT NULL, `owner` TEXT, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `listening` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `day` TEXT NOT NULL, `milliseconds` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`, `day`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `audio_session` (`slot` INTEGER NOT NULL, `accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`slot`))")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_transfers` (`accountId` TEXT NOT NULL, `identity` TEXT NOT NULL, `providerId` TEXT NOT NULL, `infohash` TEXT, PRIMARY KEY(`accountId`, `identity`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `provider_accounts` (`providerKey` TEXT NOT NULL, `libraryAccount` TEXT NOT NULL, PRIMARY KEY(`providerKey`))")
    }
}
