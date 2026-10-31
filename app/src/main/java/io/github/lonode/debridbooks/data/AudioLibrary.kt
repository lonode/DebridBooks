package io.github.lonode.debridbooks.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class AudioChapter(val title: String, val startMs: Long)
@Serializable data class AudioBookmark(val id: String, val partId: String, val positionMs: Long, val title: String, val note: String = "", val createdAt: Long)
@Serializable data class BookExtras(val saved: Boolean = false, val alertSince: String? = null, val alerted: Set<String> = emptySet(), val releaseCheckedAt: Long = 0, val rating: Int = 0, val speed: Float? = null,
    val bookmarks: List<AudioBookmark> = emptyList(), val discovery: io.github.lonode.debridbooks.domain.DiscoveryRecord? = null, val chapters: Map<String, List<AudioChapter>> = emptyMap())
@Entity(tableName = "audio_personal", primaryKeys = ["accountId", "itemId"], foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["accountId", "itemId"], childColumns = ["accountId", "itemId"], onDelete = ForeignKey.CASCADE)])
data class AudioPersonal(val accountId: String, val itemId: String, val json: String, val updatedAt: Long)
@Entity(tableName = "audio_downloads", primaryKeys = ["accountId", "itemId"], foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["accountId", "itemId"], childColumns = ["accountId", "itemId"], onDelete = ForeignKey.CASCADE)])
data class AudioDownload(val accountId: String, val itemId: String, val state: String = "QUEUED", val bytes: Long = 0,
    val total: Long? = null, val manifest: ByteArray? = null, val status: String? = null, val updatedAt: Long = System.currentTimeMillis(), val announced: Boolean = false, val allowLowSpace: Boolean = false, val owner: String? = null)
@Entity(tableName = "listening", primaryKeys = ["accountId", "itemId", "day"], foreignKeys = [ForeignKey(
    entity = BookEntity::class, parentColumns = ["accountId", "itemId"], childColumns = ["accountId", "itemId"], onDelete = ForeignKey.CASCADE)])
data class ListeningEntry(val accountId: String, val itemId: String, val day: String, val milliseconds: Long)
@Entity(tableName = "audio_session", primaryKeys = ["slot"])
data class AudioSession(val slot: Int = 0, val accountId: String, val itemId: String)

val libraryJson = Json { ignoreUnknownKeys = true }
fun AudioPersonal?.extras(): BookExtras = try { this?.let { libraryJson.decodeFromString<BookExtras>(it.json) } ?: BookExtras() } catch (_: Exception) { BookExtras() }

@Dao abstract class AudioLibraryDao {
    @Query("SELECT * FROM audio_personal") abstract fun observePersonal(): Flow<List<AudioPersonal>>
    @Query("SELECT * FROM audio_downloads") abstract fun observeDownloads(): Flow<List<AudioDownload>>
    @Query("SELECT * FROM listening") abstract fun observeListening(): Flow<List<ListeningEntry>>
    @Query("SELECT * FROM progress") abstract fun observeProgress(): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM audio_personal WHERE accountId=:account AND itemId=:item") abstract suspend fun personal(account: String, item: String): AudioPersonal?
    @Upsert abstract suspend fun putPersonal(value: AudioPersonal)
    @Transaction open suspend fun updatePersonal(account: String, item: String, change: (BookExtras) -> BookExtras) {
        val previous = personal(account, item)?.let { libraryJson.decodeFromString<BookExtras>(it.json) } ?: BookExtras()
        val value = change(previous)
        require(value.rating in 0..5 && (value.speed == null || value.speed.isFinite() && value.speed in .5f..3.5f))
        require(value.bookmarks.size <= 1000 && value.bookmarks.all { it.positionMs >= 0 && it.note.length <= 2000 && it.title.length <= 500 })
        require(value.chapters.values.sumOf { it.size } <= 20000 && value.chapters.size <= 500 && value.chapters.values.all { it.size <= 5000 })
        putPersonal(AudioPersonal(account, item, libraryJson.encodeToString(value), System.currentTimeMillis()))
    }
    @Query("SELECT * FROM audio_downloads WHERE accountId=:account AND itemId=:item") abstract suspend fun download(account: String, item: String): AudioDownload?
    @Query("SELECT * FROM audio_downloads WHERE state IN ('QUEUED','RUNNING') ORDER BY updatedAt") abstract suspend fun pending(): List<AudioDownload>
    @Query("SELECT * FROM audio_downloads WHERE state='WAITING' OR state='READY'") abstract suspend fun waiting(): List<AudioDownload>
    @Query("SELECT * FROM audio_downloads") abstract suspend fun allDownloads(): List<AudioDownload>
    @Query("UPDATE audio_downloads SET manifest=:manifest,bytes=:bytes WHERE accountId=:account AND itemId=:item AND state='RUNNING'") abstract suspend fun partial(account: String, item: String, manifest: ByteArray, bytes: Long)
    @Query("SELECT * FROM audio_downloads WHERE state='COMPLETE'") abstract suspend fun completed(): List<AudioDownload>
    @Upsert abstract suspend fun putDownload(value: AudioDownload)
    @Transaction open suspend fun enqueue(value: AudioDownload) {
        val previous = download(value.accountId, value.itemId)
        if (previous?.state !in setOf("COMPLETE", "QUEUED", "RUNNING")) putDownload(value.copy(manifest = previous?.manifest, bytes = previous?.bytes ?: 0))
    }
    @Query("UPDATE audio_downloads SET bytes=:bytes,total=:total WHERE accountId=:account AND itemId=:item AND state='RUNNING'") abstract suspend fun downloadProgress(account: String, item: String, bytes: Long, total: Long?)
    @Query("UPDATE audio_downloads SET state='RUNNING',updatedAt=:now,owner=:owner WHERE accountId=:account AND itemId=:item AND state='QUEUED'") abstract suspend fun tryStart(account: String, item: String, now: Long, owner: String = "test"): Int
    @Query("UPDATE audio_downloads SET state=:state,status=:message,updatedAt=:now WHERE accountId=:account AND itemId=:item AND state!='COMPLETE'") abstract suspend fun state(account: String, item: String, state: String, message: String?, now: Long = System.currentTimeMillis())
    @Query("UPDATE audio_downloads SET state='READY',total=:total,status='Ready. Open the app to download.' WHERE accountId=:account AND itemId=:item AND state='WAITING'") abstract suspend fun ready(account: String, item: String, total: Long?): Int
    @Query("UPDATE audio_downloads SET state='QUEUED',updatedAt=:now WHERE accountId=:account AND itemId=:item AND state='READY'") abstract suspend fun startReady(account: String, item: String, now: Long): Int
    @Query("UPDATE audio_downloads SET announced=1 WHERE accountId=:account AND itemId=:item AND state='READY'") abstract suspend fun announced(account: String, item: String)
    @Query("UPDATE audio_downloads SET status=:message WHERE accountId=:account AND itemId=:item AND state='WAITING'") abstract suspend fun waitingStatus(account: String, item: String, message: String)
    @Query("DELETE FROM audio_downloads WHERE accountId=:account AND itemId=:item") abstract suspend fun removeDownload(account: String, item: String)
    @Query("UPDATE audio_downloads SET state='PAUSED' WHERE state='RUNNING' AND (owner IS NULL OR owner!=:owner)") abstract suspend fun reconcileInterrupted(owner: String)
    @Query("SELECT * FROM listening WHERE accountId=:account AND itemId=:item AND day=:day") abstract suspend fun listening(account: String, item: String, day: String): ListeningEntry?
    @Query("SELECT * FROM listening") abstract suspend fun allListening(): List<ListeningEntry>
    @Query("SELECT * FROM audio_personal") abstract suspend fun allPersonal(): List<AudioPersonal>
    @Query("SELECT * FROM progress") abstract suspend fun allProgress(): List<ProgressEntity>
    @Upsert abstract suspend fun putListening(value: ListeningEntry)
    @Transaction open suspend fun addListening(account: String, item: String, day: String, milliseconds: Long) {
        require(milliseconds in 1..60_000 && day.length == 10)
        val previous = listening(account, item, day)?.milliseconds ?: 0
        putListening(ListeningEntry(account, item, day, previous.coerceAtMost(Long.MAX_VALUE - milliseconds) + milliseconds))
    }
    @Upsert abstract suspend fun session(value: AudioSession)
    @Query("SELECT * FROM audio_session WHERE slot=0") abstract suspend fun session(): AudioSession?
    @Query("DELETE FROM audio_session") abstract suspend fun closeSession()
}
