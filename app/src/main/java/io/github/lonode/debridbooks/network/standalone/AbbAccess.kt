package io.github.lonode.debridbooks.network.standalone

import android.util.AtomicFile
import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable internal data class AbbCache(val html: String, val expires: Long, val saved: Long)
@Serializable internal data class AbbPause(val until: Long = 0, val failures: Int = 0, val reason: String = "", val nextRequest: Long = 0)
@Serializable internal data class AbbStored(val cache: Map<String, AbbCache> = emptyMap(), val pauses: Map<String, AbbPause> = emptyMap())
internal interface AbbStore { fun read(): AbbStored; fun write(value: AbbStored) }
internal class MemoryAbbStore : AbbStore {
    private var value = AbbStored()
    override fun read() = value
    override fun write(value: AbbStored) { this.value = value }
}
internal class FileAbbStore(file: File) : AbbStore {
    private val file = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }
    override fun read(): AbbStored = try {
        require(file.baseFile.length() <= 6_000_000)
        json.decodeFromString<AbbStored>(file.readFully().toString(Charsets.UTF_8))
    } catch (_: Exception) { AbbStored() }
    override fun write(value: AbbStored) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try { stream.write(json.encodeToString(value).toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
internal class AbbDocument(val html: String, val cached: Boolean = false, val cooldownUntil: Long? = null)

/** One queue covers every ABB search/detail request; public HTML and cooldowns stay out of backups. */
internal class AbbAccess(private val http: DirectTransport, private val store: AbbStore = MemoryAbbStore(),
    private val clock: () -> Long = System::currentTimeMillis, private val wait: suspend (Long) -> Unit = { delay(it) }) {
    private val mutex = Mutex()
    private var state: AbbStored? = null
    suspend fun peek(url: String): String? = mutex.withLock {
        val saved = state ?: store.read().also { state = it }
        saved.cache[url]?.takeIf { it.expires > clock() }?.html
    }
    suspend fun get(url: String, detail: Boolean = false, force: Boolean = false): AbbDocument = mutex.withLock {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
        val host = uri.host.lowercase()
        var saved = state ?: store.read().also { state = it }
        val now = clock()
        var pause = saved.pauses[host] ?: AbbPause()
        val cached = saved.cache[url]
        if (!force && cached != null && (cached.expires > now || pause.until > now))
            return@withLock AbbDocument(cached.html, true, pause.until.takeIf { it > now })
        if (!force && pause.until > now) throw DirectFailure("AudiobookBay", "COOLDOWN", pause.until)
        var current = url
        try {
            repeat(5) {
                currentCoroutineContext().ensureActive()
                val delayMs = (pause.nextRequest - clock()).coerceIn(0, 5000)
                if (delayMs > 0) wait(delayMs)
                currentCoroutineContext().ensureActive()
                pause = pause.copy(nextRequest = clock() + 5000)
                saved = saved.copy(pauses = saved.pauses + (host to pause))
                store.write(saved); state = saved
                val response = http.execute(DirectRequest(current, mapOf("Accept" to "text/html")))
                if (response.status in setOf(301, 302, 303, 307, 308)) {
                    val target = URI(current).resolve(response.location ?: throw DirectFailure("AudiobookBay"))
                    require(target.scheme == "https" && target.host.equals(host, true) && target.port == -1 && target.rawUserInfo == null)
                    current = target.toString()
                } else {
                    val html = response.text()
                    val issue = when {
                        response.status == 429 -> "LIMIT"
                        response.status == 403 || Regex("(?i)cf-browser-verification|cf-chl-|challenge-platform|Just a moment\\.\\.\\.").containsMatchIn(html) -> "BLOCKED"
                        response.status !in 200..299 -> "UNAVAILABLE"
                        else -> null
                    }
                    if (issue != null) {
                        val retry = retryAfterMillis(response.retryAfter, clock())
                        throw DirectFailure("AudiobookBay", issue, retry?.let { clock() + it })
                    }
                    val record = AbbCache(html, clock() + if (detail) 7 * 86400000L else 1800000L, clock())
                    val records = (saved.cache + (url to record)).entries.sortedByDescending { it.value.saved }
                    var bytes = 0
                    val bounded = records.take(64).filter { bytes += it.value.html.toByteArray().size; bytes <= 4_000_000 }.associate { it.toPair() }
                    saved = saved.copy(cache = bounded, pauses = saved.pauses + (host to AbbPause(nextRequest = pause.nextRequest)))
                    store.write(saved); state = saved
                    return@withLock AbbDocument(html)
                }
            }
            throw DirectFailure("AudiobookBay")
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            val issue = (failure as? DirectFailure)?.reason ?: "UNAVAILABLE"
            val attempts = (pause.failures + 1).coerceAtMost(10)
            val base = if (issue in setOf("LIMIT", "BLOCKED")) 1800000L else 900000L
            val duration = (base * (1L shl (attempts - 1))).coerceAtMost(86400000L)
            val until = maxOf(pause.until, clock() + duration, (failure as? DirectFailure)?.retryAt ?: 0)
            pause = pause.copy(until = until, failures = attempts, reason = issue)
            saved = saved.copy(pauses = saved.pauses + (host to pause))
            store.write(saved); state = saved
            // A cached page can remain useful while the network is paused; never pretend it is fresh.
            if (cached != null) return@withLock AbbDocument(cached.html, true, until)
            throw DirectFailure("AudiobookBay", issue, until)
        }
    }
    companion object {
        internal fun retryAfterMillis(raw: String?, now: Long): Long? {
            val seconds = raw?.trim()?.toLongOrNull()
            if (seconds != null) return seconds.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000 - now / 1000 - 1)?.times(1000)
            return try { (java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }
            catch (_: Exception) { null }
        }
    }
}
internal object AbbRuntime {
    @Volatile private var access: AbbAccess? = null
    fun initialize(directory: File) { access = AbbAccess(DirectHttp, FileAbbStore(File(directory, "abb-public-cache.json"))) }
    fun forTransport(http: DirectTransport): AbbAccess = if (http === DirectHttp) access ?: synchronized(this) {
        access ?: AbbAccess(http).also { access = it }
    } else AbbAccess(http)
}
