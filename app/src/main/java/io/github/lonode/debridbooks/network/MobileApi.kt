package io.github.lonode.debridbooks.network

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.comics.*
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
class Connection(val prefix: String = "", val accountId: String, val standalone: StandaloneConfig? = null) {
    val cacheKey: String get() = MobileContract.localId(standalone?.let { Json.encodeToString(it) } ?: prefix)
}
@Serializable
data class MediaPart(val title: String, val url: String, val filename: String? = null, @Serializable(with = FileSizeSerializer::class) val size: Long? = null) {
    val id: String get() = MobileContract.localId(filename ?: title)
}
@Serializable
data class Resolution(val ready: Boolean, val streams: List<MediaPart>, val status: String? = null) {
    fun preparationMessage(): String = safeProviderStatus(status) ?: "Media is preparing. Try again shortly."
}

/** Shared domain facade. App startup loads direct configurations; the old wire adapter remains for compatibility checks. */
class MobileApi(private val connection: Connection) {
    private val direct = connection.standalone?.let { StandaloneApi(it) }
    suspend fun capabilities() = direct?.capabilities() ?: MobileContract.parseCapabilities(get("health"))
    suspend fun search(query: String, page: Int = 1, comic: Boolean = false, series: Boolean = false, onlySources: Set<String> = emptySet(), forceAbb: Boolean = false, onProgress: suspend (SearchProgress) -> Unit = {}): SearchPage {
        require(page >= 1)
        direct?.let { return it.search(query, page, comic, series, onlySources, forceAbb, onProgress) }
        return MobileContract.parseSearch(get("app/search?q=${encode(query)}&page=$page" + if (comic) "&type=comic" + if (series) "&source=series" else "" else ""))
    }
    suspend fun resolve(id: String, onProgress: (OpeningProgress) -> Unit = {}): Resolution {
        require(id.isNotBlank() && id.length <= 32000)
        direct?.let { return it.resolve(id, onProgress) }
        onProgress(OpeningProgress(OpeningStage.WaitingForProvider))
        return parseResolution(get("app/streams/${encode(id)}", 45000))
    }
    suspend fun resolveReady(id: String): Resolution = resolve(id).also {
        if (!it.ready) throw MediaPreparingException(it.preparationMessage())
    }
    suspend fun details(id: String, language: String? = null): BookDetails {
        require(id.isNotBlank() && id.length <= 32000)
        require(language == null || language in setOf("fr", "en"))
        direct?.let { return it.details(id, language) }
        return MobileContract.parseDetails(get("app/details/${encode(id)}" + (language?.let { "?language=$it" } ?: "")))
    }
    suspend fun series(id: String, language: String? = null): SeriesDetails? = try {
        if (direct != null) direct.series(id, language) else json.decodeFromString<SeriesEnvelope>(get("app/series/${encode(id)}")).series?.also { require(it.books.size <= 500 && it.name.length <= 500) }
    } catch (failure: ApiFailure) { throw failure } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (_: Exception) { throw IllegalArgumentException("Invalid catalog response") }
    suspend fun similar(id: String, language: String? = null): SimilarDetails? = try {
        if (direct != null) direct.similar(id, language) else json.decodeFromString<SimilarEnvelope>(get("app/similar/${encode(id)}")).similar?.also { require(it.books.size <= 100) }
    } catch (failure: ApiFailure) { throw failure } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (_: Exception) { throw IllegalArgumentException("Invalid catalog response") }
    suspend fun chapters(id: String, offset: Int = 0): ChapterPage {
        require(offset >= 0)
        direct?.let { return it.chapters(id, offset) }
        return ComicContract.chapters(get("app/chapters/${encode(id)}?offset=$offset&limit=100"))
    }
    suspend fun pages(id: String, saver: Boolean, fresh: Boolean = false): PageResponse {
        require(Regex("[0-9a-fA-F-]{36}").matches(id))
        direct?.let { return it.pages(id, saver) }
        return ComicContract.pages(get("app/pages/${encode(id)}?saver=${if (saver) 1 else 0}&fresh=${if (fresh) 1 else 0}"))
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parseResolution(body: String): Resolution = try { json.decodeFromString<Resolution>(body).also { result ->
            require(!result.ready || result.streams.isNotEmpty())
            require(result.streams.size <= 500)
            require(result.streams.map { it.id }.distinct().size == result.streams.size)
            result.streams.forEach {
                val uri = URI(it.url)
                require(uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null)
            }
        } } catch (_: Exception) { throw IllegalArgumentException("Invalid media response") }
    }
    private suspend fun get(path: String, timeout: Int = 20000): String =
        HttpBoundary.text("${InstallLink.parse(connection.prefix).prefix}/$path", timeout)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
