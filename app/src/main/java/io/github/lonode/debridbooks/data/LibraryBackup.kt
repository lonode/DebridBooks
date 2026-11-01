package io.github.lonode.debridbooks.data

import androidx.room.withTransaction
import io.github.lonode.debridbooks.domain.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class BackupProgress(val part: String, val index: Int, val position: Long, val speed: Float, val finished: Boolean, val updatedAt: Long)
@Serializable data class BackupBook(val account: String, val item: String, val title: String, val author: String?, val addedAt: Long,
    val source: SearchItem? = null, val progress: BackupProgress? = null, val personal: BookExtras = BookExtras(), val personalAt: Long = 0)
@Serializable data class BackupComic(val account: String, val volume: String, val item: String, val title: String, val chapter: String?,
    val resolver: String, val metadata: SearchItem?, val page: Int, val count: Int, val completed: Boolean, val updatedAt: Long)
@Serializable data class BackupPreferences(val speed: Float, val unmetered: Boolean, val comicSaver: Boolean, val immersiveHelp: Boolean, val options: AppOptions)
@Serializable data class BackupPayload(val format: String = "debridbooks-library", val version: Int = 1, val books: List<BackupBook>, val comics: List<BackupComic>,
    val listening: List<ListeningEntryBackup>, val preferences: BackupPreferences)
@Serializable data class ListeningEntryBackup(val account: String, val item: String, val day: String, val milliseconds: Long)
@Serializable private data class BackupEnvelope(val format: String = "debridbooks-encrypted", val version: Int = 1, val salt: String, val iv: String, val data: String)
class BackupPreview internal constructor(internal val payload: BackupPayload) {
    val books get() = payload.books.size
    val comics get() = payload.comics.size
    val bookmarks get() = payload.books.sumOf { it.personal.bookmarks.size }
}
object BackupProtection {
    private val json = Json { ignoreUnknownKeys = false }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size in 10..1024 && salt.size == 16)
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        require(plain.size <= 10 * 1024 * 1024)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(password, salt))
        cipher.updateAAD("debridbooks-encrypted:1".toByteArray())
        val encode = Base64.getEncoder()
        return json.encodeToString(BackupEnvelope(salt = encode.encodeToString(salt), iv = encode.encodeToString(cipher.iv), data = encode.encodeToString(cipher.doFinal(plain)))).toByteArray()
    }
    fun open(bytes: ByteArray, password: CharArray): ByteArray {
        require(bytes.size <= 15 * 1024 * 1024)
        val envelope = json.decodeFromString<BackupEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.format == "debridbooks-encrypted" && envelope.version == 1 && envelope.data.length <= 14 * 1024 * 1024)
        val decode = Base64.getDecoder(); val salt = decode.decode(envelope.salt); val iv = decode.decode(envelope.iv); val encrypted = decode.decode(envelope.data)
        require(iv.size == 12 && encrypted.size in 17..10 * 1024 * 1024 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD("debridbooks-encrypted:1".toByteArray())
        return cipher.doFinal(encrypted)
    }
}
class LibraryBackup(private val db: LibraryDatabase, private val secrets: SecretCodec, private val preferences: PreferencesRepository) {
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val personal = db.audio().allPersonal().associateBy { it.accountId to it.itemId }
        val progress = db.audio().allProgress().associateBy { it.accountId to it.itemId }
        val books = mutableListOf<BackupBook>()
        val all = db.library().allBooks()
        require(all.size <= 5000)
        for (book in all) {
            val id = book.accountId to book.itemId
            val source = db.library().audioSource(book.accountId, book.itemId)?.let { libraryJson.decodeFromString<SearchItem>(secrets.reveal(it.payload, book.accountId)) }
            val checkpoint = progress[id]?.let { BackupProgress(it.partId, it.partIndex, it.positionMs, it.speed, it.completed, it.updatedAt) }
            books += BackupBook(book.accountId, book.itemId, book.title, book.author, book.addedAt, source, checkpoint, personal[id]?.let { libraryJson.decodeFromString<BookExtras>(it.json) } ?: BookExtras(), personal[id]?.updatedAt ?: 0)
        }
        val comics = db.comics().allEntries().map { entry -> BackupComic(entry.accountId, entry.volumeId, entry.itemId, entry.title, entry.chapterId,
            secrets.reveal(entry.resolver, entry.accountId), entry.metadata?.let { libraryJson.decodeFromString<SearchItem>(secrets.reveal(it, entry.accountId)) }, entry.page, entry.pageCount, entry.completed, entry.updatedAt) }
        require(comics.size <= 5000)
        val options = preferences.preferences.first()
        val payload = BackupPayload(books = books, comics = comics, listening = db.audio().allListening().map { ListeningEntryBackup(it.accountId, it.itemId, it.day, it.milliseconds) },
            preferences = BackupPreferences(options.speed, options.unmeteredOnly, options.comicSaver, options.immersiveHelpDismissed, options.options))
        validate(payload)
        BackupProtection.seal(libraryJson.encodeToString(payload).toByteArray(), password)
    }
    suspend fun preview(bytes: ByteArray, password: CharArray): BackupPreview = withContext(Dispatchers.IO) {
        val decoded = BackupProtection.open(bytes, password)
        val payload = Json.decodeFromString<BackupPayload>(decoded.toString(Charsets.UTF_8))
        validate(payload); BackupPreview(payload)
    }
    suspend fun merge(preview: BackupPreview): Boolean = withContext(Dispatchers.IO) {
        val data = preview.payload; validate(data)
        db.withTransaction {
            val known = db.library().allBooks().associateBy { it.accountId to it.itemId }
            for (book in data.books) {
                currentCoroutineContext().ensureActive()
                val local = known[book.account to book.item]
                val entry = local ?: BookEntity(book.account, book.item, book.title, book.author, book.addedAt)
                db.library().upsertBook(entry)
                if (book.source != null) db.library().rememberSource(entry, AudioSource(book.account, book.item, secrets.protect(libraryJson.encodeToString(book.source), book.account)))
                book.progress?.let { checkpoint -> db.library().checkpoint(entry, ProgressEntity(book.account, book.item, checkpoint.part, checkpoint.index, checkpoint.position, checkpoint.speed, checkpoint.finished, checkpoint.updatedAt)) }
                val previous = db.audio().personal(book.account, book.item)
                val old = previous?.let { libraryJson.decodeFromString<BookExtras>(it.json) } ?: BookExtras()
                val newer = if (book.personalAt > (previous?.updatedAt ?: -1)) book.personal else old
                val marks = (if (book.personalAt > (previous?.updatedAt ?: -1)) old.bookmarks + book.personal.bookmarks else book.personal.bookmarks + old.bookmarks).associateBy { it.id }.values.sortedBy { it.createdAt }.takeLast(1000)
                val merged = newer.copy(bookmarks = marks, chapters = old.chapters + book.personal.chapters)
                db.audio().putPersonal(AudioPersonal(book.account, book.item, libraryJson.encodeToString(merged), maxOf(book.personalAt, previous?.updatedAt ?: 0)))
            }
            for (comic in data.comics) {
                val previous = db.comics().get(comic.account, comic.volume)
                if (previous != null && previous.updatedAt >= comic.updatedAt) continue
                val entry = ComicEntity(comic.account, comic.volume, comic.item, comic.title, comic.chapter, secrets.protect(comic.resolver, comic.account),
                    comic.page, comic.count, comic.completed, previous?.offline ?: false, comic.updatedAt, previous?.downloadState ?: "NONE", previous?.downloadedPages ?: 0,
                    comic.metadata?.let { secrets.protect(libraryJson.encodeToString(it), comic.account) })
                db.comics().save(entry)
            }
            val validBooks = db.library().allBooks().map { it.accountId to it.itemId }.toSet()
            for (row in data.listening) {
                currentCoroutineContext().ensureActive()
                if ((row.account to row.item) !in validBooks) continue
                val previous = db.audio().listening(row.account, row.item, row.day)?.milliseconds ?: 0
                db.audio().putListening(ListeningEntry(row.account, row.item, row.day, maxOf(previous, row.milliseconds)))
            }
        }
        try {
            preferences.setSpeed(data.preferences.speed); preferences.setUnmeteredOnly(data.preferences.unmetered)
            preferences.setComicSaver(data.preferences.comicSaver); preferences.setImmersiveHelpDismissed(data.preferences.immersiveHelp)
            preferences.setOptions { data.preferences.options.copy(whatsNewSeen = it.whatsNewSeen) }; true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
    }
    companion object {
        fun validate(data: BackupPayload) {
            val hash = Regex("[a-f0-9]{64}")
            require(data.format == "debridbooks-library" && data.version == 1 && data.books.size <= 5000 && data.comics.size <= 5000 && data.listening.size <= 100_000)
            require(data.books.distinctBy { it.account to it.item }.size == data.books.size && data.comics.distinctBy { it.account to it.volume }.size == data.comics.size)
            for (book in data.books) {
                require(hash.matches(book.account) && hash.matches(book.item) && book.title.length in 1..1000 && (book.author?.length ?: 0) <= 1000)
                require(book.source == null || book.source.id.length <= 32000 && MobileContract.localId(book.source.id) == book.item)
                book.progress?.let { require(hash.matches(it.part) && it.index in 0..499 && it.position in 0..604800000L && it.speed.isFinite() && it.speed in .5f..3.5f && it.updatedAt >= 0) }
                require(book.personal.rating in 0..5 && (book.personal.speed == null || book.personal.speed.isFinite() && book.personal.speed in .5f..3.5f))
                require(book.personal.audioParts.size <= 5000 && book.personal.audioParts.all { it.length <= 100 })
                require(book.personal.audioDurationsMs.size <= 5000 && book.personal.audioDurationsMs.all { (id, duration) -> id.length <= 100 && duration > 0 })
                require(book.personal.bookmarks.size <= 1000 && book.personal.chapters.size <= 500)
                book.personal.bookmarks.forEach { require(it.id.length in 1..100 && hash.matches(it.partId) && it.positionMs in 0..604800000L && it.note.length <= 2000 && it.title.length <= 500) }
                book.personal.chapters.forEach { (key, chapters) -> require(key.length <= 100 && chapters.size <= 5000 && chapters.all { it.startMs in 0..604800000L && it.title.length <= 500 }) }
            }
            for (comic in data.comics) require(hash.matches(comic.account) && hash.matches(comic.item) && hash.matches(comic.volume) && comic.resolver.length <= 32000 &&
                MobileContract.localId(comic.resolver) == comic.item && comic.title.length in 1..1000 && comic.page in 0..4999 && comic.count in 1..5000 && comic.page < comic.count)
            for (row in data.listening) { require(hash.matches(row.account) && hash.matches(row.item) && row.milliseconds in 0..315360000000L); java.time.LocalDate.parse(row.day) }
            require(data.preferences.speed.isFinite() && data.preferences.speed in .5f..3.5f)
            data.preferences.options.validate()
        }
    }
}
