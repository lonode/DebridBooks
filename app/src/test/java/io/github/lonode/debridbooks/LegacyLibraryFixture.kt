package io.github.lonode.debridbooks

import android.database.sqlite.SQLiteDatabase

/** Historical test databases, independent of production migrations and Room exports. */
internal fun SQLiteDatabase.createLegacyLibrary(version: Int) {
    require(version in 1..5)
    execSQL("CREATE TABLE IF NOT EXISTS `books` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`))")
    execSQL("CREATE TABLE IF NOT EXISTS `progress` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `partId` TEXT NOT NULL, `partIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `speed` REAL NOT NULL, `completed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    if (version >= 2) {
        execSQL("CREATE TABLE IF NOT EXISTS `comics` (`accountId` TEXT NOT NULL, `volumeId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `title` TEXT NOT NULL, `chapterId` TEXT, `resolver` BLOB NOT NULL, `page` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `offline` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `downloadState` TEXT NOT NULL, `downloadedPages` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `volumeId`))")
    }
    if (version >= 4) execSQL("ALTER TABLE comics ADD COLUMN metadata BLOB")
    if (version >= 3) {
        execSQL("CREATE TABLE IF NOT EXISTS `audio_sources` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `payload` BLOB NOT NULL, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
    if (version >= 5) {
        execSQL("CREATE TABLE IF NOT EXISTS `audio_personal` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
    if (version >= 5) {
        execSQL("CREATE TABLE IF NOT EXISTS `audio_downloads` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `state` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `total` INTEGER, `manifest` BLOB, `status` TEXT, `updatedAt` INTEGER NOT NULL, `announced` INTEGER NOT NULL, `allowLowSpace` INTEGER NOT NULL, `owner` TEXT, PRIMARY KEY(`accountId`, `itemId`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
    if (version >= 5) {
        execSQL("CREATE TABLE IF NOT EXISTS `listening` (`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `day` TEXT NOT NULL, `milliseconds` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `itemId`, `day`), FOREIGN KEY(`accountId`, `itemId`) REFERENCES `books`(`accountId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
    if (version >= 5) {
        execSQL("CREATE TABLE IF NOT EXISTS `audio_session` (`slot` INTEGER NOT NULL, `accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`slot`))")
    }
}
