package io.github.lonode.debridbooks.domain

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object MobileContract {
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> decode(body: String): T = try { json.decodeFromString<T>(body) }
        catch (_: Exception) { throw IllegalArgumentException("Invalid server response") }
    fun parseDetails(body: String): BookDetails = decode<BookDetails>(body).let { it.copy(description = it.description?.take(4000)) }
    fun parseCapabilities(body: String): Capabilities = decode<Capabilities>(body).also {
        require(it.ok && (it.providerKeyValid ?: it.torboxKeyValid) == true)
        require(it.provider in setOf("torbox", "alldebrid"))
        require(it.addon.isNotBlank())
    }
    fun parseSearch(body: String): SearchPage = decode<SearchPage>(body).also { page ->
        require(page.results.size <= 1000)
        page.results.forEach { require(it.id.isNotBlank() && it.id.length <= 32000 && it.title.isNotBlank()) }
    }
    fun localId(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
@Serializable
data class Capabilities(val ok: Boolean, val addon: String, val provider: String = "torbox", val providerKeyValid: Boolean? = null, val torboxKeyValid: Boolean? = null, val sources: Map<String, Boolean> = emptyMap(), val features: ServerFeatures? = null)
@Serializable
data class SearchPage(val results: List<SearchItem>, val partial: Boolean = false, val warnings: List<SearchWarning> = emptyList())
@Serializable
data class ServerFeatures(val bookDetails: Boolean? = null, val searchWarnings: Boolean? = null, val streamFileSizes: Boolean? = null)
@Serializable
data class SearchWarning(val source: String, val code: String, val message: String)
@Serializable
data class BookDetails(val title: String? = null, val description: String? = null, val genre: String? = null, val year: Int? = null, val author: String? = null)

/** Forward explicit recording language only; metadata must never infer recording language. */
fun SearchItem.detailsLanguage(): String? = when (language?.lowercase(java.util.Locale.ROOT)) {
    "fr", "french" -> "fr"
    "en", "english" -> "en"
    else -> null
}
@Serializable
data class SearchItem(val id: String, val title: String, val author: String? = null, val provider: String? = null, val type: String = "audiobook", val poster: String? = null, val description: String? = null, val format: String? = null, val sizeText: String? = null, val language: String? = null, val tracker: String? = null, val releaseName: String? = null, val cached: Boolean = false, val searchSource: String? = null)

/** A sensitive configured prefix, never a diagnostic string. */
class InstallLink private constructor(val prefix: String) {
    companion object {
        fun parse(input: String): InstallLink {
            try {
                val normalized = input.trim().replace(Regex("^stremio:", RegexOption.IGNORE_CASE), "https:")
                val uri = URI(normalized)
                require(uri.scheme in setOf("https", "http") && uri.host != null && uri.rawUserInfo == null)
                require(uri.port == -1 || uri.port in 1..65535)
                val path = uri.rawPath.trimEnd('/').replace(Regex("/(manifest\\.json|configure)$", RegexOption.IGNORE_CASE), "")
                require(Regex("/[A-Za-z0-9_-]+").matches(path))
                require(uri.rawQuery == null && uri.rawFragment == null || uri.rawPath.endsWith("/manifest.json") || uri.rawPath.endsWith("/configure"))
                return InstallLink(URI(uri.scheme, null, uri.host, uri.port, path, null, null).toASCIIString())
            } catch (_: Exception) { throw IllegalArgumentException("Invalid configuration link") }
        }
    }
}

/** Display only metadata explicitly returned by the server; never decode resolver IDs. */
fun SearchItem.displayMetadata(): List<String> {
    val knownLanguage = language?.takeIf { it.isNotBlank() }?.let {
        when (it.lowercase()) { "fr" -> "French"; "en" -> "English"; "multi" -> "Multilingual"; else -> it.uppercase(java.util.Locale.ROOT) }
    }
    val source = tracker?.takeIf { it.isNotBlank() } ?: provider?.takeIf { it == "mangadex" }?.let { "MangaDex" }
    val extension = format?.takeIf { it.isNotBlank() && !it.equals(source, ignoreCase = true) } ?: Regex("""(?i)\b(m4b|mp3|flac|m4a|ogg|opus|cbz|cbr|cb7|cbt|pdf)\b""")
        .find(releaseName.orEmpty())?.value?.uppercase(java.util.Locale.ROOT)
    return listOfNotNull(knownLanguage, sizeText?.takeIf { it.isNotBlank() }, extension, source)
}
