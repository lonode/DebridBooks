package io.github.lonode.debridbooks.network

import io.github.lonode.debridbooks.domain.SearchWarning
import kotlinx.serialization.json.*

/** Contains only a status and allowlisted diagnostics; never stores response bodies or request URLs. */
class ApiFailure(val status: Int, val code: String? = null, val warnings: List<SearchWarning> = emptyList()) :
    IllegalStateException("Server request failed (HTTP $status)")

internal fun safeSource(source: String): String = when (source.lowercase()) {
    "c411" -> "C411"
    "tr4ker" -> "Tr4ker"
    "yggreborn" -> "YggReborn"
    "jackett" -> "Jackett"
    "mangadex" -> "MangaDex"
    "alldebrid" -> "AllDebrid"
    "audiobookbay" -> "AudiobookBay"
    "book details" -> "Book details"
    else -> "A search source"
}
internal fun parseApiFailure(status: Int, body: String?): ApiFailure {
    val codes = setOf("NO_SEARCH_SOURCES", "SEARCH_UNAVAILABLE", "SEARCH_FAILED", "BAD_LANGUAGE", "BAD_ITEM_ID", "UNSUPPORTED_ITEM")
    return try {
        val json = Json.parseToJsonElement(body ?: return ApiFailure(status)) as? JsonObject ?: return ApiFailure(status)
        val code = (json["code"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in codes }
        val warnings = (json["warnings"] as? JsonArray)?.take(32)?.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            val source = (obj["source"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SearchWarning(safeSource(source), "SOURCE_UNAVAILABLE", "Source unavailable or incomplete.")
        }.orEmpty()
        ApiFailure(status, code, warnings)
    } catch (_: Exception) { ApiFailure(status) }
}

/** Provider statuses are displayed as text, never as links or exception diagnostics. */
fun safeProviderStatus(status: String?): String? = status?.trim()?.takeIf {
    it.isNotEmpty() && it.length <= 500 &&
        !Regex("(?i)(https?[:/]|stremio:|tbab:|api.?key|token|passkey|[a-z0-9_+/=-]{32,})").containsMatchIn(it)
}
class MediaPreparingException(val displayStatus: String) : IllegalStateException("Media is not ready")
