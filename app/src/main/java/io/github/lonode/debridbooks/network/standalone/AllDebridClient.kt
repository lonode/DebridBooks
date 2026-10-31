package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.domain.MobileContract
import io.github.lonode.debridbooks.domain.OpeningProgress
import io.github.lonode.debridbooks.domain.OpeningStage
import io.github.lonode.debridbooks.network.MediaPart
import io.github.lonode.debridbooks.network.MobileApi
import io.github.lonode.debridbooks.network.Resolution
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class AllDebridClient(private val config: StandaloneConfig, private val http: DirectTransport = DirectHttp, private val registry: MagnetRegistry = ProviderRegistry.current) {
    private val scope = MobileContract.localId("alldebrid:${config.apiKey}")
    private suspend fun api(path: String, params: List<Pair<String, String>>? = null, version: String = "v4", file: ByteArray? = null): JsonObject = gate.run {
        val boundary = "DebridBooks${UUID.randomUUID()}"
        val body = file?.let {
            "--$boundary\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\"book.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n".toByteArray() + it + "\r\n--$boundary--\r\n".toByteArray()
        } ?: params?.let { parameters(it).toByteArray() }
        val response = http.execute(DirectRequest("https://api.alldebrid.com/$version/$path", mapOf("Authorization" to "Bearer ${config.apiKey}"), body,
            if (file != null) "multipart/form-data; boundary=$boundary" else "application/x-www-form-urlencoded")).requireSuccess("AllDebrid").json()
        if (response.string("status") != "success") {
            val code = (response["error"] as? JsonObject)?.string("code")
            throw DirectFailure("AllDebrid", when (code) {
                "AUTH_BLOCKED" -> "AUTH_BLOCKED"
                "AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY", "AUTH_USER_BANNED" -> "AUTH"
                "AUTH_USER_NOT_PREMIUM" -> "PREMIUM"
                "RATE_LIMIT", "TOO_MANY_REQUESTS" -> "LIMIT"
                else -> "UNAVAILABLE"
            })
        }
        response["data"] as? JsonObject ?: throw DirectFailure("AllDebrid", "INVALID_RESPONSE")
    }
    suspend fun validate() {
        val user = api("user")["user"] as? JsonObject ?: throw DirectFailure("AllDebrid", "INVALID_RESPONSE")
        if (user.string("username").isNullOrBlank()) throw DirectFailure("AllDebrid", "INVALID_RESPONSE")
        if (user.string("isPremium") == "false") throw DirectFailure("AllDebrid", "PREMIUM")
    }
    private suspend fun account(): List<JsonObject> {
        val data = api("magnet/status", emptyList(), "v4.1")
        if (data["magnets"] !is JsonArray && data["magnets"] !is JsonObject) throw DirectFailure("AllDebrid", "INVALID_RESPONSE")
        return data.objects("magnets")
    }
    private fun ready(item: JsonObject) = item.string("ready") == "true" || item.string("statusCode") == "4"
    suspend fun readyHashes(): Set<String> {
        val ready = account().filter(::ready)
        val ids = ready.mapNotNull { it.string("id") }.toSet()
        return (ready.mapNotNull { it.string("hash")?.lowercase() } + registry.entries(scope).filter { it.id in ids }.mapNotNull { it.infohash?.lowercase() }).toSet()
    }
    /** Search never calls this method: adding torrents requires a user's playback/download action. */
    suspend fun resolve(item: ResolverItem, onProgress: (OpeningProgress) -> Unit = {}): Resolution {
        onProgress(OpeningProgress(OpeningStage.WaitingForProvider))
        return resolverMutex.withLock {
            val hash = item.h ?: item.m?.let { Regex("(?i)btih:([a-f0-9]{40})").find(it)?.groupValues?.get(1) }
            val identity = hash?.lowercase() ?: MobileContract.localId(item.encode())
            val known = registry.entries(scope).firstOrNull { it.identity == identity }
            onProgress(OpeningProgress(OpeningStage.CheckingAccount))
            val existing = account()
            var torrent = existing.firstOrNull { hash != null && it.string("hash").equals(hash, true) }
                ?: existing.firstOrNull { known != null && it.string("id") == known.id }
            if (known != null && torrent == null) registry.remove(scope, identity)
            var id = torrent?.string("id")
            if (id == null) {
                val uploaded = if (item.u != null) {
                    onProgress(OpeningProgress(OpeningStage.FetchingTorrent))
                    when (val source = torrentSource(item.u)) {
                        is TorrentSource.File -> { onProgress(OpeningProgress(OpeningStage.AddingTorrent));api("magnet/upload/file", file = source.bytes) }
                        is TorrentSource.Magnet -> { onProgress(OpeningProgress(OpeningStage.AddingTorrent));api("magnet/upload", listOf("magnets[]" to source.value)) }
                    }
                } else {
                    onProgress(OpeningProgress(OpeningStage.AddingTorrent))
                    api("magnet/upload", listOf("magnets[]" to (item.m ?: hash?.let { "magnet:?xt=urn:btih:$it" } ?: throw DirectFailure("AllDebrid"))))
                }
                val entry = (uploaded.objects("magnets") + uploaded.objects("files")).firstOrNull() ?: throw DirectFailure("AllDebrid")
                id = entry.string("id")?.takeIf { entry["error"] == null || entry["error"] == JsonNull } ?: throw DirectFailure("AllDebrid")
                require(Regex("[0-9]{1,30}").matches(id))
                registry.save(scope, identity, id, hash ?: entry.string("hash")?.takeIf { Regex("[a-fA-F0-9]{40}").matches(it) })
            }
            onProgress(OpeningProgress(OpeningStage.CheckingTransfer))
            torrent = api("magnet/status", listOf("id" to id), "v4.1").objects("magnets").firstOrNull { it.string("id") == id } ?: throw DirectFailure("AllDebrid")
            if (!ready(torrent)) {
                if ((torrent.string("statusCode")?.toIntOrNull() ?: 0) > 4) throw DirectFailure("AllDebrid")
                return@withLock Resolution(false, emptyList(), "Preparing on AllDebrid. Try again shortly.")
            }
            onProgress(OpeningProgress(OpeningStage.ReadingFiles))
            val entry = api("magnet/files", listOf("id[]" to id)).objects("magnets").firstOrNull { it.string("id") == id } ?: throw DirectFailure("AllDebrid")
            if (entry["error"] != null && entry["error"] != JsonNull) throw DirectFailure("AllDebrid")
            val files = flatten(entry["files"]).filter { file ->
                file.name.substringAfterLast('.', "").lowercase() in if (item.t == "c") comicFormats else audioFormats
            }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            if (files.isEmpty()) return@withLock Resolution(false, emptyList(), "No supported files found in this torrent.")
            require(files.size <= 500)
            onProgress(OpeningProgress(OpeningStage.PreparingFiles, 0, files.size))
            val streams = files.mapIndexed { index, file ->
                val uri = URI(file.link)
                require(uri.scheme == "https" && uri.host in setOf("alldebrid.com", "www.alldebrid.com") && uri.rawUserInfo == null && uri.path.startsWith("/f/"))
                val link = api("link/unlock", listOf("link" to file.link)).string("link") ?: throw DirectFailure("AllDebrid")
                require(URI(link).scheme == "https")
                onProgress(OpeningProgress(OpeningStage.PreparingFiles, index+1, files.size))
                MediaPart(file.name, link, file.name, file.size)
            }
            // Apply the same URL/identity invariants as existing playback and download code.
            MobileApi.parseResolution(Json.encodeToString(Resolution(true, streams, "ok")))
        }
    }
    private sealed interface TorrentSource {
        class File(val bytes: ByteArray) : TorrentSource
        class Magnet(val value: String) : TorrentSource
    }
    private suspend fun torrentSource(url: String): TorrentSource {
        var current = url
        repeat(6) {
            val response = http.execute(DirectRequest(current, limit = 10 * 1024 * 1024))
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                val next = URI(current).resolve(response.location ?: throw DirectFailure("Torrent source")).toString()
                if (next.startsWith("magnet:?")) return TorrentSource.Magnet(next)
                StandaloneConfig.endpoint(next.substringBefore('?')); current = next
            } else {
                response.requireSuccess("Torrent source")
                require(response.bytes.isNotEmpty() && response.bytes[0] == 'd'.code.toByte()) { "Invalid torrent file" }
                return TorrentSource.File(response.bytes)
            }
        }
        throw DirectFailure("Torrent source")
    }
    class FileEntry(val name: String, val link: String, val size: Long?)
    companion object {
        private val gate = RequestGate(110)
        private val resolverMutex = Mutex()
        private val audioFormats = setOf("mp3", "m4b", "m4a", "flac", "aac", "ogg", "opus", "wav", "wma")
        private val comicFormats = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "pdf", "jpg", "jpeg", "png", "webp", "gif", "avif")
        fun flatten(value: JsonElement?, prefix: String = "", depth: Int = 0): List<FileEntry> {
            require(depth <= 30)
            val nodes = when (value) { is JsonArray -> value; is JsonObject -> JsonArray(listOf(value)); else -> JsonArray(emptyList()) }
            require(nodes.size <= 5000)
            val out = nodes.flatMap { node ->
                val obj = node as? JsonObject ?: throw DirectFailure("AllDebrid", "INVALID_RESPONSE")
                val name = prefix + (obj.string("n") ?: obj.string("filename") ?: "")
                if (obj["e"] != null) flatten(obj["e"], "$name/", depth + 1)
                else (obj.string("l") ?: obj.string("link"))?.let { listOf(FileEntry(name, it, (obj.string("s") ?: obj.string("size"))?.toLongOrNull()?.takeIf { n -> n > 0 })) } ?: emptyList()
            }
            require(out.size <= 5000)
            return out
        }
        internal fun naturalCompare(a: String, b: String): Int {
            val parts = Regex("[0-9]+|[^0-9]+")
            val left = parts.findAll(a).map { it.value }.toList(); val right = parts.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                val result = if (x.all(Char::isDigit) && y.all(Char::isDigit)) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y, ignoreCase = true)
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }
    }
}
