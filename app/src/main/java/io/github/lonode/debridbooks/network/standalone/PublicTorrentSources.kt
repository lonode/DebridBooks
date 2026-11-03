package io.github.lonode.debridbooks.network.standalone

import java.io.File
import kotlinx.serialization.json.*

internal data class PublicTorrentPage(val results: List<TorrentResult>, val cached: Boolean, val cooldown: Long?)

/** Public catalogue requests only; 1337x detail pages supply hashes for cross-source deduplication. */
internal class PublicTorrentSources(http: DirectTransport, private val directory: File? = null) {
    private fun store(name: String): AbbStore = directory?.let { FileAbbStore(File(it, name)) } ?: MemoryAbbStore()
    private val bay = AbbAccess(http, store("bay-public-cache.json"), service = "API Bay", accept = "application/json")
    private val knaben = AbbAccess(http, store("knaben-public-cache.json"), service = "Knaben", accept = "application/json")
    private val x = AbbAccess(http, store("1337x-public-cache.json"), service = "1337x")
    suspend fun search(source: String, query: String, page: Int, comic: Boolean, onPartial: suspend (PublicTorrentPage) -> Unit = {}): PublicTorrentPage {
        require(page in 1..10000 && query.isNotBlank() && query.length <= 500)
        val document = when (source) {
            "API Bay" -> bay.get("https://apibay.org/q.php?${parameters(listOf("q" to query, "cat" to if (comic) "602" else "102"))}")
            "Knaben" -> knaben.get("https://api.knaben.org/v2/search?${parameters(listOf("q" to query,
                "c" to if (comic) "9002000" else "1003000", "s" to "40", "f" to ((page - 1) * 40).toString(), "o" to "seeders", "d" to "desc"))}")
            "1337x" -> x.get("https://1337x.to/category-search/${encoded(query).replace("+", "%20")}/Other/$page/")
            else -> throw IllegalArgumentException("Unknown public source")
        }
        val results = when (source) {
            "API Bay" -> parseBay(document.html, comic).drop((page - 1) * 40).take(40)
            "Knaben" -> parseKnaben(document.html, comic)
            else -> parse1337x(document.html, comic)
        }
        if (source != "1337x") return PublicTorrentPage(results, document.cached, document.cooldownUntil)
        val hashed = linkedMapOf<String, TorrentResult>()
        for (result in results) {
            // Detail requests share the source cache, pacing and cooldown; no torrent is uploaded.
            val item = resolve(ResolverItem(n = result.name, x = result.detail1337x))
            val hash = requireNotNull(item.h)
            hashed.putIfAbsent(hash, TorrentResult(result.name, hash, item.m, size = result.size,
                seeders = result.seeders, tracker = result.tracker))
            onPartial(PublicTorrentPage(hashed.values.toList(), document.cached, document.cooldownUntil))
        }
        return PublicTorrentPage(hashed.values.toList(), document.cached, document.cooldownUntil)
    }
    suspend fun resolve(item: ResolverItem): ResolverItem {
        val path = item.x ?: return item
        // Resolver payloads contain a relative path, never an arbitrary destination URL.
        require(Regex("/torrent/[0-9]+/[A-Za-z0-9._%~-]+/?").matches(path))
        val document = x.get("https://1337x.to$path", detail = true)
        val magnet = Regex("""(?is)href\s*=\s*["'](magnet:\?[^"'<>]+)["']""").find(document.html)?.groupValues?.get(1)
            ?.let(TorrentSearch::plainText) ?: throw DirectFailure("1337x", "INVALID_RESPONSE")
        val hash = Regex("(?i)(?:[?&])xt=urn:btih:([a-f0-9]{40})(?:&|$)").find(magnet)?.groupValues?.get(1)?.lowercase()
            ?: throw DirectFailure("1337x", "INVALID_RESPONSE")
        return ResolverItem(h = hash, m = magnet, n = item.n, f = item.f, b = item.b, s = item.s, t = item.t)
    }
    companion object {
        /** Schema/category reference: Knaben API v2 and Jackett's Knaben category mappings.
         * Only an infohash-derived magnet is retained; origin URLs and descriptions stay unused. */
        internal fun parseKnaben(text: String, comic: Boolean): List<TorrentResult> {
            val root = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw DirectFailure("Knaben", "INVALID_RESPONSE") }
            val rows = root["hits"] as? JsonArray ?: throw DirectFailure("Knaben", "INVALID_RESPONSE")
            if (rows.size > 300) throw DirectFailure("Knaben", "INVALID_RESPONSE")
            val category = if (comic) 9002000 else 1003000
            return rows.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val categories = row["categoryId"] as? JsonArray ?: return@mapNotNull null
                if (categories.none { (it as? JsonPrimitive)?.intOrNull == category }) return@mapNotNull null
                val name = row.string("title")?.takeIf { it.isNotBlank() && it.length <= 2000 } ?: return@mapNotNull null
                fun validHash(value: String?) = value?.takeIf { Regex("[a-fA-F0-9]{40}").matches(it) && it.any { c -> c != '0' } }?.lowercase()
                val explicit = validHash(row.string("hash"))
                val fromMagnet = row.string("magnetUrl")?.takeIf { it.startsWith("magnet:?") }?.let {
                    validHash(Regex("(?i)(?:[?&])xt=urn:btih:([a-f0-9]{40})(?:&|$)").find(it)?.groupValues?.get(1))
                }
                if (explicit != null && fromMagnet != null && explicit != fromMagnet) return@mapNotNull null
                val hash = explicit ?: fromMagnet ?: return@mapNotNull null
                val origin = (row.string("cachedOrigin") ?: row.string("tracker"))?.filterNot { it.isISOControl() }?.trim()?.take(100)?.takeIf { it.isNotBlank() }
                TorrentResult(name, hash, "magnet:?xt=urn:btih:$hash", size = row.string("bytes")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    seeders = (row.string("liveSeeders")?.toIntOrNull() ?: row.string("seeders")?.toIntOrNull() ?: 0).coerceAtLeast(0),
                    tracker = origin?.let { "Knaben · $it" } ?: "Knaben")
            }.distinctBy { it.hash }.take(40)
        }
        internal fun parseBay(text: String, comic: Boolean): List<TorrentResult> {
            val rows = try { Json.parseToJsonElement(text).jsonArray } catch (_: Exception) { throw DirectFailure("API Bay", "INVALID_RESPONSE") }
            require(rows.size <= 10000)
            return rows.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                if (id == "0") return@mapNotNull null // API Bay's no-results sentinel.
                val hash = row.string("info_hash")?.takeIf { Regex("[a-fA-F0-9]{40}").matches(it) && it.any { c -> c != '0' } }?.lowercase() ?: return@mapNotNull null
                val name = row.string("name")?.takeIf { it.isNotBlank() && it.length <= 2000 } ?: return@mapNotNull null
                if (row.string("category") != if (comic) "602" else "102") return@mapNotNull null
                TorrentResult(name, hash, "magnet:?xt=urn:btih:$hash", size = row.string("size")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    seeders = row.string("seeders")?.toIntOrNull()?.coerceAtLeast(0) ?: 0, tracker = "API Bay")
            }
        }
        internal fun parse1337x(html: String, comic: Boolean): List<TorrentResult> {
            if (!Regex("(?i)<table\\b|No results (?:were returned|found)").containsMatchIn(html)) throw DirectFailure("1337x", "INVALID_RESPONSE")
            return Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(html).mapNotNull { match ->
                val row = match.groupValues[1]
                if (!Regex("""(?i)href\s*=\s*["']/sub/${if (comic) "39" else "52"}/(?:[0-9]+/)?["']""").containsMatchIn(row)) return@mapNotNull null
                val link = Regex("""(?is)<a\b[^>]*href\s*=\s*["'](/torrent/[0-9]+/[A-Za-z0-9._%~-]+/?)["'][^>]*>(.*?)</a>""").find(row) ?: return@mapNotNull null
                val name = TorrentSearch.plainText(link.groupValues[2]).takeIf { it.isNotBlank() && it.length <= 2000 } ?: return@mapNotNull null
                fun cell(column: Int) = Regex("""(?is)<td\b[^>]*class\s*=\s*["'][^"']*\bcoll-$column\b[^"']*["'][^>]*>(.*?)</td>""").find(row)?.groupValues?.get(1)?.let(TorrentSearch::plainText).orEmpty()
                val size = Regex("(?i)([0-9.,]+)\\s*(TB|GB|MB|KB|B)\\b").find(cell(4))
                val factor = when (size?.groupValues?.get(2)?.uppercase()) { "TB" -> 1099511627776.0; "GB" -> 1073741824.0; "MB" -> 1048576.0; "KB" -> 1024.0; else -> 1.0 }
                TorrentResult(name, size = ((size?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0) * factor).toLong().coerceAtLeast(0),
                    seeders = cell(2).replace(",", "").toIntOrNull()?.coerceAtLeast(0) ?: 0, tracker = "1337x", detail1337x = link.groupValues[1])
            }.distinctBy { it.detail1337x }.take(40).toList()
        }
    }
}

internal object PublicTorrentRuntime {
    @Volatile private var direct: PublicTorrentSources? = null
    fun initialize(directory: File) { direct = PublicTorrentSources(DirectHttp, directory) }
    fun forTransport(http: DirectTransport): PublicTorrentSources = if (http === DirectHttp) direct ?: synchronized(this) {
        direct ?: PublicTorrentSources(http).also { direct = it }
    } else PublicTorrentSources(http)
}
