package io.github.lonode.debridbooks.network.standalone

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compatible resolver payloads preserve existing encrypted library entries and local keys. */
@Serializable
class ResolverItem(val h: String? = null, val m: String? = null, val u: String? = null,
    val n: String, val f: String? = null, val b: String? = null, val s: Long? = null,
    val t: String? = null, val p: String? = null, val d: String? = null, val a: String? = null, val x: String? = null) {
    fun encode(): String = "tbab:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(this).toByteArray(Charsets.UTF_8))
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(id: String): ResolverItem = try {
            require(id.startsWith("tbab:") && id.length <= 32000)
            json.decodeFromString<ResolverItem>(Base64.getUrlDecoder().decode(id.drop(5)).toString(Charsets.UTF_8)).also {
                require(it.n.isNotBlank() && it.n.length <= 2000)
                require(it.h == null || Regex("[a-fA-F0-9]{40}").matches(it.h))
                require(it.m == null || it.m.startsWith("magnet:?"))
                require(it.u == null || it.u.startsWith("https://"))
                require(it.x == null || Regex("/torrent/[0-9]+/[A-Za-z0-9._%~-]+/?").matches(it.x))
                if (it.a != null) {
                    val page = java.net.URI(it.a)
                    require(page.scheme == "https" && page.host != null && page.rawUserInfo == null && page.port == -1 && page.rawQuery == null && page.rawFragment == null)
                    require(page.path.startsWith("/abss/") || page.path.startsWith("/audio-books/"))
                }
            }
        } catch (_: Exception) { throw IllegalArgumentException("Invalid saved resolver") }
    }
}
